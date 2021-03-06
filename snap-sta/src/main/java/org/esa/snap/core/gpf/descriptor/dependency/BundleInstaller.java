/*
 *
 *  * Copyright (C) 2016 CS ROMANIA
 *  *
 *  * This program is free software; you can redistribute it and/or modify it
 *  * under the terms of the GNU General Public License as published by the Free
 *  * Software Foundation; either version 3 of the License, or (at your option)
 *  * any later version.
 *  * This program is distributed in the hope that it will be useful, but WITHOUT
 *  * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 *  * more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  *  with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.snap.core.gpf.descriptor.dependency;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.gpf.descriptor.ToolAdapterOperatorDescriptor;
import org.esa.snap.core.gpf.operators.tooladapter.ProcessExecutor;
import org.esa.snap.core.gpf.operators.tooladapter.ToolAdapterIO;
import org.esa.snap.core.util.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Installer class for adapter bundles.
 *
 * @author  Cosmin Cara
 * @since   5.0.4
 */
public class BundleInstaller implements AutoCloseable {
    private static final int TIMEOUT = 20000;
    private static final int BUFFER_SIZE = 262144;
    private static Logger logger = Logger.getLogger(BundleInstaller.class.getName());
    private static final Path baseModulePath;

    private final ExecutorService executor;
    private ToolAdapterOperatorDescriptor descriptor;
    private ProgressMonitor progressMonitor;
    private Callable<Void> callback;
    private int taskCount;

    static {
        baseModulePath =  SystemUtils.getApplicationDataDir().toPath().resolve("modules").resolve("lib");
    }

    /**
     * Creates an installer for the given adapter descriptor.
     *
     * @param descriptor    The adapter descriptor
     */
    public BundleInstaller(ToolAdapterOperatorDescriptor descriptor) {
        this.descriptor = descriptor;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Checks if the bundle file (archive or installer) is present.
     *
     * @param bundle    The bundle to be checked
     */
    public static boolean isBundleFileAvailable(Bundle bundle) {
        if (bundle == null) {
            return false;
        }
        File targetLocation = bundle.getTargetLocation();
        String entryPoint = bundle.getEntryPoint();
        return (targetLocation != null && entryPoint != null &&
                targetLocation.exists() && Files.exists(baseModulePath.resolve(entryPoint))) ||
                bundle.getDownloadURL() != null;
    }

    /**
     * Sets a progress monitor for this installer.
     *
     * @param monitor   The progress monitor
     */
    public void setProgressMonitor(ProgressMonitor monitor) {
        this.progressMonitor = monitor;
    }

    /**
     * Sets a callback to be invoked when the execution completes.
     *
     * @param completionCallback    The completion callback
     */
    public void setCallback(Callable<Void> completionCallback) {
        this.callback = completionCallback;
    }

    /**
     * Install the bundle (if any) of the current adapter operator.
     *
     * @param async If <code>true</code>, installation will be done on a separate thread.
     */
    public void install(boolean async) {
        final Bundle descriptorBundle = this.descriptor.getBundle();
        Path sourcePath = baseModulePath.resolve(descriptorBundle.getEntryPoint());
        if (!Files.exists(sourcePath)) {
            File source = descriptorBundle.getSource();
            if (source != null) {
                sourcePath = descriptorBundle.getSource().toPath();
            }
        }
        Callable<Void> action;
        MethodReference<Path, Bundle> firstStep = null;
        if (!descriptorBundle.isLocal()) {
            firstStep = this::download;
            taskCount++;
        }
        switch (descriptorBundle.getBundleType()) {
            case ZIP:
                try {
                    MethodReference<Path, Bundle> secondStep = this::uncompress;
                    if (firstStep != null) {
                        firstStep = MethodReference.from(firstStep.andThen(secondStep));
                    } else {
                        firstStep = secondStep;
                    }
                    taskCount++;
                    action = new Action(sourcePath, descriptorBundle, firstStep);
                    if (this.progressMonitor != null) {
                        this.progressMonitor.beginTask("Installing bundle", 100);
                    }
                    if (async) {
                        executor.submit(action);
                    } else {
                        action.call();
                    }
                } catch (Exception e) {
                    logger.warning(e.getMessage());
                };
                break;
            case INSTALLER:
                try {
                    MethodReference<Path, Bundle> secondStep = this::install;
                    if (firstStep != null) {
                        firstStep = MethodReference.from(firstStep.andThen(secondStep));
                    } else {
                        firstStep = secondStep;
                    }
                    taskCount++;
                    action = new Action(sourcePath, descriptorBundle, firstStep);
                    if (this.progressMonitor != null) {
                        this.progressMonitor.beginTask("Installing bundle", 100);
                    }
                    if (async) {
                        executor.submit(action);
                    } else {
                        action.call();
                    }
                } catch (Exception e) {
                    logger.warning(e.getMessage());
                };
                break;
            default:
                break;
        }
    }

    @Override
    public void close() throws Exception {
        this.executor.shutdownNow();
    }

    private boolean isInstalled() {
        Bundle bundle = this.descriptor.getBundle();
        return bundle != null && bundle.isInstalled();
    }

    private void copy(Path source, Bundle bundle) throws IOException {
        File targetLocation = bundle.getTargetLocation();
        if (targetLocation == null) {
            throw new IOException("No target defined");
        }
        Path targetPath = targetLocation.toPath();
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }
        Files.copy(source, targetPath.resolve(source.getFileName()));
    }

    private Path download(Path target, Bundle bundle) throws IOException {
        String remoteURL = bundle.getDownloadURL();
        if (remoteURL == null || remoteURL.isEmpty()) {
            throw new IOException("No remote URL");
        }
        return download(remoteURL, target);
    }

    private void uncompress(Path source, Bundle bundle) throws IOException {
        File targetLocation = bundle.getTargetLocation();
        if (targetLocation == null) {
            throw new IOException("No target defined");
        }
        ToolAdapterIO.unzip(source, targetLocation.toPath(), this.progressMonitor, taskCount);
        Files.deleteIfExists(source);
    }

    private void install(Path source, Bundle bundle) throws IOException {
        int exit;
        File targetLocation = bundle.getTargetLocation();
        if (targetLocation == null) {
            throw new IOException("No target defined");
        }
        try {
            if (this.progressMonitor != null) {
                this.progressMonitor.setSubTaskName("Installing...");
            }
            copy(source, bundle);
            this.progressMonitor.worked(50 / taskCount);
            final Path exePath = targetLocation.toPath().resolve(bundle.getEntryPoint());
            ToolAdapterIO.fixPermissions(exePath);
            List<String> arguments = new ArrayList<>();
            arguments.add(exePath.toString());
            String args = bundle.getArguments();
            if (args != null) {
                Collections.addAll(arguments, args.split(" "));
            }
            ProcessExecutor executor = new ProcessExecutor();
            exit = executor.execute(arguments);
            this.progressMonitor.worked(50 / taskCount);
            Files.deleteIfExists(exePath);
        } catch (Exception ex) {
            logger.severe(ex.getMessage());
            throw new IOException(ex);
        }
        if (exit != 0) {
            throw new RuntimeException(String.format("Not successfully installed [exit code = %s]", exit));
        }
    }

    private Path download(String remoteUrl, Path targetFile) throws IOException {
        if (remoteUrl == null || remoteUrl.isEmpty() || targetFile == null) {
            throw new IllegalArgumentException("Invalid download parameters");
        }
        URL url = new URL(remoteUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        long length = connection.getContentLengthLong();
        double divisor = (double) length * (double) (100 / taskCount);
        Path tmpFile;
        if (!Files.exists(targetFile) || length != Files.size(targetFile)) {
            Files.deleteIfExists(targetFile);
            Files.createDirectories(targetFile.getParent());
            tmpFile = Files.createTempFile(targetFile.getParent(), "tmp", null);
            if (this.progressMonitor != null) {
                this.progressMonitor.setSubTaskName("Downloading...");
            }
            try (InputStream inputStream = connection.getInputStream()) {
                try (OutputStream outputStream = Files.newOutputStream(tmpFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read, totalRead = 0;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0 ,read);
                        totalRead += read;
                        if (this.progressMonitor != null) {
                            this.progressMonitor.worked((int) (totalRead / divisor));
                        }
                    }
                    outputStream.flush();
                }
            } catch (IOException ex) {
                Files.deleteIfExists(tmpFile);
                throw new IOException(ex.getCause());
            }
            Files.move(tmpFile, targetFile);
        }
        return targetFile;
    }

    private void installFinished() {
        String alias = this.descriptor.getAlias();
        if (isInstalled()) {
            logger.info(String.format("Installation of bundle for %s completed", alias));
        } else {
            logger.severe(String.format("Bundle for %s has not been installed", alias));
        }
        if (this.callback != null) {
            try {
                this.callback.call();
            } catch (Exception e) {
                logger.warning(e.getMessage());
            }
        }
    }

    @FunctionalInterface
    interface MethodReference<T, U> extends BiConsumer<T, U> {

        static <T, U> MethodReference<T, U> from(BiConsumer<T, U> consumer) {
            return consumer::accept;
        }

        @Override
        default void accept(T t, U u) {
            try {
                throwingAccept(t, u);
            } catch (final Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        void throwingAccept(T t, U u) throws Exception;
    }

    class Action implements Callable<Void> {
        private Path source;
        private Bundle bundle;
        private BiConsumer<Path, Bundle> method;

        Action(Path source, Bundle bundle, BiConsumer<Path, Bundle> methodRef) throws Exception {
            this.source = source;
            this.bundle = bundle;
            this.method = methodRef;
        }

        @Override
        public Void call() throws Exception {
            try {
                this.method.accept(this.source, this.bundle);
                return null;
            } finally {
                installFinished();
            }
        }
    }
}
