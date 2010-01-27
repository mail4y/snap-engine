/*
 * $Id: $
 *
 * Copyright (C) 2009 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.bc.ceres.binding.swing;

import com.bc.ceres.binding.PropertyDescriptor;
import com.bc.ceres.binding.swing.internal.TextFieldEditor;
import com.bc.ceres.core.Assert;
import com.bc.ceres.core.ServiceRegistry;
import com.bc.ceres.core.ServiceRegistryManager;

import java.util.ServiceLoader;

/**
 * A registry for {@link PropertyEditor}.
 *
 * @author Marco Zuehlke
 * @version $Revision$ $Date$
 * @since BEAM 4.6
 */
public class PropertyEditorRegistry {

    private static PropertyEditorRegistry instance;

    private final ServiceRegistry<PropertyEditor> registry;
    private final PropertyEditor defaultEditor;

    private PropertyEditorRegistry() {
        registry = ServiceRegistryManager.getInstance().getServiceRegistry(PropertyEditor.class);

        final ServiceLoader<PropertyEditor> serviceLoader = ServiceLoader.load(PropertyEditor.class);
        for (final PropertyEditor propertyEditor : serviceLoader) {
            registry.addService(propertyEditor);
        }
        defaultEditor = registry.getService(TextFieldEditor.class.getName());
    }

    public static synchronized PropertyEditorRegistry getInstance() {
        if (instance == null) {
            instance = new PropertyEditorRegistry();
        }
        return instance;
    }

    public static synchronized void setInstance(PropertyEditorRegistry registry) {
        instance = registry;
    }

    /**
     * Gets a {@link PropertyEditor} by its class name.
     *
     * @param className The class name of the {@link PropertyEditor}.
     *
     * @return the value editor or {@code null} if no editor exist for the given class name.
     */
    public PropertyEditor getPropertyEditor(String className) {
        return registry.getService(className);
    }

    /**
     * Finds a matching {@link PropertyEditor} for the given {@link com.bc.ceres.binding.PropertyDescriptor}.
     * <p/>
     * At first , if set, the property {@code "valueEditor"} of the value descriptor
     * is used. Afterwards all registered {@link PropertyEditor}s are tested,
     * whether the can provide an editor. As a fallback a {@link TextFieldEditor} is returned.
     *
     * @param propertyDescriptor the value descriptor
     *
     * @return the editor that can edit values described by the value descriptor
     */
    public PropertyEditor findPropertyEditor(PropertyDescriptor propertyDescriptor) {
        Assert.notNull(propertyDescriptor, "propertyDescriptor");
        PropertyEditor propertyEditor = (PropertyEditor) propertyDescriptor.getAttribute("propertyEditor");
        if (propertyEditor != null) {
            return propertyEditor;
        }
        for (PropertyEditor editor : registry.getServices()) {
            if (editor.isValidFor(propertyDescriptor)) {
                return editor;
            }
        }
        return defaultEditor;
    }
}
