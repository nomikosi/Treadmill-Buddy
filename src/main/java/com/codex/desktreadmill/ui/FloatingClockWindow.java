package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class FloatingClockWindow {
    private static final Color PIN_ENABLED = new JBColor(new Color(0x2E7D32), new Color(0x6FBF73));
    private static final Color PIN_DISABLED = new JBColor(new Color(0xC62828), new Color(0xE57373));

    private final TreadmillSettings settings = TreadmillSettings.getInstance();
    private final JDialog dialog;
    private final DigitalClockDisplay display = new DigitalClockDisplay();
    private final JButton pauseResumeButton = new JButton(TreadmillBundle.message("button.start"));
    private final JButton saveButton = new JButton(TreadmillBundle.message("button.saveSession"));
    private Point dragStart;

    public FloatingClockWindow(Project project, Runnable pauseResumeAction, Runnable saveAction) {
        Frame owner = WindowManager.getInstance().getFrame(project);
        dialog = new JDialog(owner, TreadmillBundle.message("floating.title"), false);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        dialog.getRootPane().setBorder(JBUI.Borders.customLine(JBColor.border()));
        dialog.setContentPane(createContent(pauseResumeAction, saveAction));
        dialog.pack();
    }

    /** Opens the clock on the user's request, which also re-arms the automatic opening. */
    public void showWindow() {
        settings.setFloatingClockAutoShow(true);
        showNow();
    }

    /**
     * Opens the clock because a session started - unless the user closed it
     * earlier, in which case it stays out of the way until they ask for it
     * with the Float Clock button again.
     */
    public void showIfWanted() {
        if (settings.isFloatingClockAutoShow()) {
            showNow();
        }
    }

    private void showNow() {
        if (dialog.isVisible()) {
            dialog.toFront();
            return;
        }
        Point stored = storedLocation();
        if (stored != null) {
            dialog.setLocation(stored);
        } else if (dialog.getLocation().x == 0 && dialog.getLocation().y == 0) {
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        dialog.setVisible(true);
    }

    private void closeByUser() {
        settings.setFloatingClockAutoShow(false);
        dialog.setVisible(false);
    }

    public void setDisplay(String dayPrefix, String timeText) {
        display.setDisplay(dayPrefix, timeText);
    }

    public void setPauseResumeText(String text) {
        pauseResumeButton.setText(text);
    }

    public void dispose() {
        dialog.dispose();
    }

    private Point storedLocation() {
        if (!settings.hasFloatingClockLocation()) {
            return null;
        }
        Point point = new Point(settings.getFloatingClockX(), settings.getFloatingClockY());
        // A remembered position can be off-screen after a monitor change.
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(point)) {
                return point;
            }
        }
        return null;
    }

    private JPanel createContent(Runnable pauseResumeAction, Runnable saveAction) {
        JPanel content = new JPanel(new BorderLayout());
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBorder(JBUI.Borders.empty(4, 8));

        JBLabel title = new JBLabel(TreadmillBundle.message("floating.title"));
        title.setHorizontalAlignment(SwingConstants.LEFT);
        JButton close = new JButton("x");
        close.setFocusable(false);
        close.addActionListener(event -> closeByUser());
        pauseResumeButton.setFocusable(false);
        saveButton.setFocusable(false);
        pauseResumeButton.addActionListener(event -> pauseResumeAction.run());
        saveButton.addActionListener(event -> saveAction.run());
        reserveWidestLabel(pauseResumeButton,
                TreadmillBundle.message("button.start"),
                TreadmillBundle.message("button.pause"),
                TreadmillBundle.message("button.resume"));

        JToggleButton pinButton = new JToggleButton();
        pinButton.setFocusable(false);
        pinButton.setToolTipText(TreadmillBundle.message("floating.pin.tooltip"));
        pinButton.setSelected(settings.isFloatingClockPinned());
        if (pinButton.isSelected() && dialog.isAlwaysOnTopSupported()) {
            dialog.setAlwaysOnTop(true);
        }
        pinButton.addActionListener(event -> {
            if (dialog.isAlwaysOnTopSupported()) {
                dialog.setAlwaysOnTop(pinButton.isSelected());
            }
            settings.setFloatingClockPinned(pinButton.isSelected());
            updatePinButton(pinButton);
        });
        updatePinButton(pinButton);
        reserveWidestLabel(pinButton,
                TreadmillBundle.message("floating.pinned"),
                TreadmillBundle.message("floating.unpinned"));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(pauseResumeButton);
        actions.add(saveButton);
        actions.add(pinButton);
        actions.add(close);

        titleBar.add(title, BorderLayout.CENTER);
        titleBar.add(actions, BorderLayout.EAST);
        installDrag(titleBar);
        installDrag(display);

        content.add(titleBar, BorderLayout.NORTH);
        content.add(display, BorderLayout.CENTER);
        return content;
    }

    /**
     * The dialog is packed once, while the button shows its initial label. A
     * later, longer label ("Resume" after "Start") would be clipped, so size
     * the button for the widest text it will ever show.
     */
    private static void reserveWidestLabel(AbstractButton button, String... labels) {
        String current = button.getText();
        int width = 0;
        int height = 0;
        for (String label : labels) {
            button.setText(label);
            Dimension size = button.getPreferredSize();
            width = Math.max(width, size.width);
            height = Math.max(height, size.height);
        }
        button.setText(current);
        button.setPreferredSize(new Dimension(width, height));
    }

    private static void updatePinButton(JToggleButton pinButton) {
        if (pinButton.isSelected()) {
            pinButton.setText(TreadmillBundle.message("floating.pinned"));
            pinButton.setForeground(PIN_ENABLED);
        } else {
            pinButton.setText(TreadmillBundle.message("floating.unpinned"));
            pinButton.setForeground(PIN_DISABLED);
        }
    }

    private void installDrag(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragStart = event.getLocationOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragStart == null) {
                    return;
                }
                Point current = event.getLocationOnScreen();
                Point location = dialog.getLocation();
                int dx = current.x - dragStart.x;
                int dy = current.y - dragStart.y;
                dialog.setLocation(location.x + dx, location.y + dy);
                dragStart = current;
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragStart = null;
                Point location = dialog.getLocation();
                settings.setFloatingClockLocation(location.x, location.y);
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }
}
