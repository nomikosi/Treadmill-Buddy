package com.codex.desktreadmill.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedSessionsPanelTest {
    @Test
    void theOldSessionsDialogExplainsADayCountItCannotUse() {
        for (String days : new String[]{"365", " 30 ", "1,000", "1"}) {
            assertNull(SavedSessionsPanel.DAYS_VALIDATOR.getErrorText(days), days);
            assertTrue(SavedSessionsPanel.DAYS_VALIDATOR.canClose(days), days);
        }
        for (String days : new String[]{"", "abc", "0", "-5", "2.5"}) {
            assertNotNull(SavedSessionsPanel.DAYS_VALIDATOR.getErrorText(days), days);
            assertFalse(SavedSessionsPanel.DAYS_VALIDATOR.canClose(days), "used to close and silently do nothing: " + days);
        }
    }
}
