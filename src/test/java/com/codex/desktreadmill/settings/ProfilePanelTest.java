package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.UnitSystem;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ProfilePanelTest {
    @TempDir Path directory;

    @Test
    void smallDistanceGoalsRemainValidThroughUnitSwitchesAndSave() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.METRIC);
            settings.setDailyGoalType(GoalType.DISTANCE);
            settings.setDailyGoalValue(0.05);
            settings.setWeeklyGoalType(GoalType.DISTANCE);
            settings.setWeeklyGoalValue(0.003);
            ProfilePanel panel = panel(settings);
            for (int i = 0; i < 5; i++) {
                units(panel, UnitSystem.IMPERIAL);
                assertNull(panel.validateInput());
                assertEquals(0.05, panel.getDailyGoalValueMetric(), 0);
                assertEquals(0.003, panel.getWeeklyGoalValueMetric(), 0);
                units(panel, UnitSystem.METRIC);
                assertNull(panel.validateInput());
            }
            assertFalse(panel.isModified(settings));
            panel.applyTo(settings);
            assertEquals(0.05, settings.getDailyGoalValue(), 0);
            assertEquals(0.003, settings.getWeeklyGoalValue(), 0);
        });
    }

    @Test
    void untouchedImperialFormIsValidAndPreservesExactMetricValues() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
            ProfilePanel panel = panel(settings);
            assertNull(panel.validateInput());
            assertFalse(panel.isModified(settings));
            panel.applyTo(settings);
            assertEquals(170, settings.getProfile().heightCm, 0);
            assertEquals(70, settings.getProfile().weightKg, 0);
        });
    }

    @Test
    void editedWeightIsConvertedAndMarkedModified() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
            ProfilePanel panel = panel(settings);
            text(panel, "weightField").setText("160");
            assertTrue(panel.isModified(settings));
            assertNull(panel.validateInput());
            assertEquals(UnitSystem.IMPERIAL.weightToKg(160), panel.getProfile().weightKg, 0);
        });
    }

    @Test
    void aVisibleSmallEditStillCounts() throws Exception {
        onEdt(() -> {
            ProfilePanel panel = panel(settings(UnitSystem.IMPERIAL));
            text(panel, "weightField").setText("154.4");
            assertEquals(UnitSystem.IMPERIAL.weightToKg(154.4), panel.getProfile().weightKg, 0);
        });
    }

    @Test
    void repeatedUnitSwitchesPreserveHeightWeightAndBothDistanceGoals() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.METRIC);
            settings.setDailyGoalType(GoalType.DISTANCE);
            settings.setDailyGoalValue(1.234567);
            settings.setWeeklyGoalType(GoalType.DISTANCE);
            settings.setWeeklyGoalValue(7.654321);
            ProfilePanel panel = panel(settings);
            for (int i = 0; i < 10; i++) {
                units(panel, UnitSystem.IMPERIAL);
                assertNull(panel.validateInput());
                units(panel, UnitSystem.METRIC);
            }
            assertFalse(panel.isModified(settings));
            panel.applyTo(settings);
            assertEquals(170, settings.getProfile().heightCm, 0);
            assertEquals(70, settings.getProfile().weightKg, 0);
            assertEquals(1.234567, settings.getDailyGoalValue(), 0);
            assertEquals(7.654321, settings.getWeeklyGoalValue(), 0);
        });
    }

    @Test
    void editsSurviveSwitchingUnitsBeforeApply() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
            ProfilePanel panel = panel(settings);
            text(panel, "heightField").setText("67.125");
            units(panel, UnitSystem.METRIC);
            units(panel, UnitSystem.IMPERIAL);
            panel.applyTo(settings);
            assertEquals(UnitSystem.IMPERIAL.heightToCm(67.125), settings.getProfile().heightCm, 0);
        });
    }

    @Test
    void localeChangesNeverMakeTheFormRejectItsOwnValues() throws Exception {
        Locale original = Locale.getDefault();
        try {
            for (String locale : new String[]{"ar-EG", "fa-IR", "de-DE"}) {
                Locale.setDefault(Locale.forLanguageTag(locale));
                onEdt(() -> {
                    TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
                    ProfilePanel panel = panel(settings);
                    assertNull(panel.validateInput());
                    assertFalse(panel.isModified(settings));
                    units(panel, UnitSystem.METRIC);
                    assertNull(panel.validateInput());
                    assertEquals(170, panel.getProfile().heightCm, 0);
                });
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void imperialBoundaryValuesValidateUsingTheirExactMetricValues() throws Exception {
        onEdt(() -> {
            for (double[] bounds : new double[][]{{20, 90}, {300, 250}}) {
                TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
                var profile = settings.getProfile().copy();
                profile.weightKg = bounds[0];
                profile.heightCm = bounds[1];
                settings.setProfile(profile);
                ProfilePanel panel = panel(settings);
                assertNull(panel.validateInput());
            }
        });
    }

    @Test
    void changingGoalTypeDoesNotReuseThePreviousDistanceBaseline() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.IMPERIAL);
            settings.setDailyGoalType(GoalType.DISTANCE);
            settings.setDailyGoalValue(1);
            ProfilePanel panel = panel(settings);
            assertEquals("0.6", text(panel, "goalValueField").getText());
            ComboBox<?> type = (ComboBox<?>) field(panel, "goalTypeCombo");
            type.setSelectedItem(GoalType.STEPS);
            type.setSelectedItem(GoalType.DISTANCE);
            assertEquals(UnitSystem.IMPERIAL.distanceToKm(0.6), panel.getDailyGoalValueMetric(), 0);
        });
    }

    @Test
    void aThousandsSeparatedStepsGoalMeansTenThousandSteps() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.METRIC);
            ProfilePanel panel = panel(settings);
            ((ComboBox<?>) field(panel, "goalTypeCombo")).setSelectedItem(GoalType.STEPS);
            ((ComboBox<?>) field(panel, "weeklyGoalTypeCombo")).setSelectedItem(GoalType.STEPS);
            text(panel, "goalValueField").setText("10,000");
            text(panel, "weeklyGoalValueField").setText("70.000");
            assertNull(panel.validateInput());
            assertEquals(10_000, panel.getDailyGoalValueMetric(), 0, "used to be saved as a 10-step goal");
            assertEquals(70_000, panel.getWeeklyGoalValueMetric(), 0);
            text(panel, "goalValueField").setText("7.5");
            assertNotNull(panel.validateInput(), "steps are whole numbers");
        });
    }

    @Test
    void anAmbiguousDecimalIsRejectedWithBothReadings() throws Exception {
        onEdt(() -> {
            TreadmillSettings settings = settings(UnitSystem.METRIC);
            ProfilePanel panel = panel(settings);
            ((ComboBox<?>) field(panel, "goalTypeCombo")).setSelectedItem(GoalType.CALORIES);
            text(panel, "goalValueField").setText("1,500");
            String message = panel.validateInput();
            assertNotNull(message);
            assertTrue(message.contains("1500") && message.contains("1.5"), message);
            text(panel, "goalValueField").setText("1500");
            assertNull(panel.validateInput());
            text(panel, "weightField").setText("72,500");
            assertTrue(panel.validateInput().contains("72500"), "weights are checked too");
        });
    }

    private TreadmillSettings settings(UnitSystem units) {
        TreadmillSettings settings = new TreadmillSettings(directory.resolve("sessions.json"));
        settings.setUnitSystem(units);
        return settings;
    }

    private static ProfilePanel panel(TreadmillSettings settings) {
        ProfilePanel panel = new ProfilePanel();
        panel.setValues(settings);
        return panel;
    }

    private static JBTextField text(ProfilePanel panel, String name) throws Exception {
        return (JBTextField) field(panel, name);
    }

    private static void units(ProfilePanel panel, UnitSystem units) throws Exception {
        ((ComboBox<?>) field(panel, "unitsCombo")).setSelectedItem(units);
    }

    private static Object field(ProfilePanel panel, String name) throws Exception {
        Field field = ProfilePanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }

    private static void onEdt(CheckedRunnable action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
