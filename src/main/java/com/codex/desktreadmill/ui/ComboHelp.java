package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionMode;
import com.intellij.openapi.ui.ComboBox;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.util.function.Supplier;

public final class ComboHelp {
    private ComboHelp() {
    }

    public static void configureAlgorithmCombo(
            ComboBox<CalorieAlgorithm> combo,
            Supplier<CalorieAlgorithm> defaultAlgorithm
    ) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof CalorieAlgorithm algorithm) {
                    setText(algorithm.getLabel() + (algorithm == defaultAlgorithm.get() ? " (default)" : ""));
                    setToolTipText(algorithm.getDescription());
                    list.setToolTipText(algorithm.getDescription());
                }
                return component;
            }
        });
        combo.addItemListener(event -> updateAlgorithmTooltip(combo));
        updateAlgorithmTooltip(combo);
    }

    public static void configureModeCombo(ComboBox<SessionMode> combo) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SessionMode mode) {
                    setText(mode.getLabel());
                    setToolTipText(mode.getDescription());
                    list.setToolTipText(mode.getDescription());
                }
                return component;
            }
        });
        combo.addItemListener(event -> updateModeTooltip(combo));
        updateModeTooltip(combo);
    }

    public static void updateAlgorithmTooltip(ComboBox<CalorieAlgorithm> combo) {
        Object selected = combo.getSelectedItem();
        if (selected instanceof CalorieAlgorithm algorithm) {
            combo.setToolTipText(algorithm.getDescription());
        }
        combo.repaint();
    }

    public static void updateModeTooltip(ComboBox<SessionMode> combo) {
        Object selected = combo.getSelectedItem();
        if (selected instanceof SessionMode mode) {
            combo.setToolTipText(mode.getDescription());
        }
        combo.repaint();
    }
}
