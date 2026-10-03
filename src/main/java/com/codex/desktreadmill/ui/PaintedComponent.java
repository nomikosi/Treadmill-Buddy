package com.codex.desktreadmill.ui;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * A custom-painted component that screen readers can still find. A plain
 * JComponent has no accessible object at all, so assistive tools skip it;
 * subclasses put what they paint into their accessible name.
 */
abstract class PaintedComponent extends JComponent {
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }
            };
        }
        return accessibleContext;
    }
}
