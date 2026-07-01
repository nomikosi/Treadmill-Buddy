package com.codex.desktreadmill.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class FloatingClockWindow {
    private final JDialog dialog;
    private final DigitalClockDisplay display = new DigitalClockDisplay();
    private final JButton pauseResumeButton = new JButton("Start");
    private final JButton saveButton = new JButton("Save");
    private Point dragStart;

    public FloatingClockWindow(Project project, Runnable pauseResumeAction, Runnable saveAction) {
        Frame owner = WindowManager.getInstance().getFrame(project);
        dialog = new JDialog(owner, "Treadmill Buddy Clock", false);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        dialog.getRootPane().setBorder(JBUI.Borders.customLine(JBColor.border()));
        dialog.setContentPane(createContent(pauseResumeAction, saveAction));
        dialog.pack();
    }

    public void showWindow() {
        if (dialog.isVisible()) {
            dialog.toFront();
            return;
        }
        if (dialog.getLocation().x == 0 && dialog.getLocation().y == 0) {
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        dialog.setVisible(true);
    }

    public void setDisplay(String dayPrefix, String timeText) {
        display.setDisplay(dayPrefix, timeText);
    }

    public void setPauseResumeText(String text) {
        pauseResumeButton.setText(text);
    }

    private JPanel createContent(Runnable pauseResumeAction, Runnable saveAction) {
        JPanel content = new JPanel(new BorderLayout());
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBorder(JBUI.Borders.empty(4, 8));
        titleBar.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        JBLabel title = new JBLabel("Treadmill Buddy Clock");
        title.setHorizontalAlignment(SwingConstants.LEFT);
        JButton close = new JButton("x");
        close.setFocusable(false);
        close.addActionListener(event -> dialog.setVisible(false));
        pauseResumeButton.setFocusable(false);
        saveButton.setFocusable(false);
        pauseResumeButton.addActionListener(event -> pauseResumeAction.run());
        saveButton.addActionListener(event -> saveAction.run());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(pauseResumeButton);
        actions.add(saveButton);
        actions.add(close);

        titleBar.add(title, BorderLayout.CENTER);
        titleBar.add(actions, BorderLayout.EAST);
        installDrag(titleBar);
        installDrag(display);

        content.add(titleBar, BorderLayout.NORTH);
        content.add(display, BorderLayout.CENTER);
        return content;
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
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }
}
