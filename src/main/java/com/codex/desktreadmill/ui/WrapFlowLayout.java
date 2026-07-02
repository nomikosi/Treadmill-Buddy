package com.codex.desktreadmill.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A {@link FlowLayout} that reports the height of its wrapped rows.
 * Plain FlowLayout wraps components but keeps claiming single-row height,
 * so wrapped rows get clipped in a BorderLayout edge.
 */
public final class WrapFlowLayout extends FlowLayout {
    public WrapFlowLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension size = layoutSize(target, false);
        size.width -= getHgap() + 1;
        return size;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getWidth();
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }
            Insets insets = target.getInsets();
            int maxRowWidth = targetWidth - insets.left - insets.right - getHgap() * 2;

            Dimension result = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component component : target.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + size.width > maxRowWidth) {
                    result.width = Math.max(result.width, rowWidth);
                    result.height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) {
                    rowWidth += getHgap();
                }
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            result.width = Math.max(result.width, rowWidth) + insets.left + insets.right + getHgap() * 2;
            result.height += rowHeight + insets.top + insets.bottom + getVgap() * 2;
            return result;
        }
    }
}
