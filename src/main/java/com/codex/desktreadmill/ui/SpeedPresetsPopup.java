package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.model.SpeedPreset;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** The Presets button's popup: switch to a saved speed, save the current one, or remove one. */
final class SpeedPresetsPopup {
    private final Project project;
    private final TreadmillSettings settings;
    private final Supplier<UnitSystem> units;
    /** The speed field in km/h; not positive when the field holds no valid speed. */
    private final DoubleSupplier currentSpeedKmh;
    private final DoubleConsumer applySpeedKmh;
    private final Supplier<String> invalidSpeedMessage;

    SpeedPresetsPopup(Project project, TreadmillSettings settings, Supplier<UnitSystem> units,
                      DoubleSupplier currentSpeedKmh, DoubleConsumer applySpeedKmh,
                      Supplier<String> invalidSpeedMessage) {
        this.project = project;
        this.settings = settings;
        this.units = units;
        this.currentSpeedKmh = currentSpeedKmh;
        this.applySpeedKmh = applySpeedKmh;
        this.invalidSpeedMessage = invalidSpeedMessage;
    }

    void showUnderneathOf(JComponent anchor) {
        UnitSystem displayUnits = units.get();
        DefaultActionGroup group = new DefaultActionGroup();
        List<SpeedPreset> presets = settings.getSpeedPresets();
        for (SpeedPreset preset : presets) {
            String label = TreadmillBundle.message("presets.item", preset.name,
                    WorkoutText.speed(preset.speedKmh, displayUnits), displayUnits.speedUnit());
            group.add(new DumbAwareAction(label) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    applySpeedKmh.accept(preset.speedKmh);
                }
            });
        }
        if (!presets.isEmpty()) {
            group.addSeparator();
        }
        group.add(new DumbAwareAction(TreadmillBundle.message("presets.saveCurrent")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                saveCurrentSpeed();
            }
        });
        if (!presets.isEmpty()) {
            DefaultActionGroup removeGroup = DefaultActionGroup.createPopupGroup(
                    () -> TreadmillBundle.message("presets.removeGroup"));
            for (SpeedPreset preset : presets) {
                removeGroup.add(new DumbAwareAction(preset.name) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent event) {
                        settings.removeSpeedPreset(preset.name);
                    }
                });
            }
            group.add(removeGroup);
        }
        JBPopupFactory.getInstance()
                .createActionGroupPopup(TreadmillBundle.message("presets.popup.title"), group,
                        DataManager.getInstance().getDataContext(anchor),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showUnderneathOf(anchor);
    }

    private void saveCurrentSpeed() {
        double speedKmh = currentSpeedKmh.getAsDouble();
        if (speedKmh <= 0 || speedKmh > 25) {
            Messages.showErrorDialog(project, invalidSpeedMessage.get(), TreadmillBundle.message("notification.title"));
            return;
        }
        UnitSystem displayUnits = units.get();
        String defaultName = WorkoutText.speed(speedKmh, displayUnits) + " " + displayUnits.speedUnit();
        String name = Messages.showInputDialog(project,
                TreadmillBundle.message("presets.dialog.message"),
                TreadmillBundle.message("presets.dialog.title"), null, defaultName, null);
        if (name == null || name.isBlank()) {
            return;
        }
        settings.addSpeedPreset(new SpeedPreset(name.trim(), speedKmh));
    }
}
