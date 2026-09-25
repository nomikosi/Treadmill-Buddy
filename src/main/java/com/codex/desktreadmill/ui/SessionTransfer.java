package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.codex.desktreadmill.transfer.SessionCsvCodec;
import com.codex.desktreadmill.transfer.SessionImport;
import com.codex.desktreadmill.transfer.SessionJsonCodec;
import com.codex.desktreadmill.transfer.SessionTcxCodec;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The file dialogs and notifications around session import and export: CSV
 * and JSON both ways, and TCX out (per session) so a walk can be imported into
 * Garmin Connect, Strava, and similar fitness services. The formats and the
 * import matching live in the {@code transfer} package.
 */
public final class SessionTransfer {
    private SessionTransfer() {
    }

    public static void exportCsv(@Nullable Project project, TreadmillSettings settings) {
        List<SessionData> sessions = settings.getSessions();
        if (notifyIfEmpty(project, sessions)) {
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.title"),
                TreadmillBundle.message("transfer.export.csv.description"), "csv", "treadmill-sessions.csv");
        if (wrapper == null) {
            return;
        }
        writeFile(project, wrapper, SessionCsvCodec.buildCsv(sessions), sessions.size());
    }

    public static void exportJson(@Nullable Project project, TreadmillSettings settings) {
        List<SessionData> sessions = settings.getSessions();
        if (notifyIfEmpty(project, sessions)) {
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.title"),
                TreadmillBundle.message("transfer.export.json.description"), "json", "treadmill-sessions.json");
        if (wrapper == null) {
            return;
        }
        String json = SessionJsonCodec.buildJson(sessions);
        writeFile(project, wrapper, json, sessions.size());
    }

    public static void exportTcx(@Nullable Project project, @Nullable SessionData session) {
        if (session == null || session.elapsedSeconds == 0 || session.createdMillis <= 0) {
            Messages.showInfoMessage(project,
                    TreadmillBundle.message("transfer.export.tcx.noSelection"),
                    TreadmillBundle.message("transfer.export.tcx.dialogTitle"));
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.tcx.title"),
                TreadmillBundle.message("transfer.export.tcx.description"),
                "tcx", safeFileName(session.name) + ".tcx");
        if (wrapper == null) {
            return;
        }
        writeFile(project, wrapper, SessionTcxCodec.buildTcx(session), 1);
    }

    /** Returns the number of imported sessions, or -1 when the dialog was cancelled. */
    public static int importCsv(@Nullable Project project, TreadmillSettings settings) {
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("csv")
                        .withTitle(TreadmillBundle.message("transfer.import.title"))
                        .withDescription(TreadmillBundle.message("transfer.import.csv.description")),
                project, null);
        if (file == null) {
            return -1;
        }
        List<SessionData> parsed;
        try {
            parsed = SessionCsvCodec.parseCsv(Files.readString(file.toNioPath()));
        } catch (IOException | IllegalArgumentException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.import.csv.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("transfer.import.errorTitle"));
            return -1;
        }
        List<SessionData> toImport = SessionImport.selectNewSessions(parsed, settings.getSessions());
        for (SessionData session : toImport) {
            SessionImport.rehydrateAfterImport(session, settings.getProfile());
        }
        return finishImport(project, settings, toImport);
    }

    /**
     * One store write for the whole file: saving row by row rewrites the JSON
     * once per session, which freezes the EDT on a real backup. The balloon
     * says when that write did not reach disk rather than claiming success
     * for sessions that exist only in memory.
     */
    private static int finishImport(@Nullable Project project, TreadmillSettings settings, List<SessionData> toImport) {
        boolean persisted = settings.saveSessions(toImport);
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message(persisted ? "notification.import.done" : "notification.import.failed",
                        toImport.size()));
        return toImport.size();
    }

    /**
     * Imports sessions from a JSON export. Unlike CSV, this round-trips the
     * full model (speed segments, interval config), so it's the backup/restore
     * path. Returns the number of imported sessions, or -1 when cancelled.
     */
    public static int importJson(@Nullable Project project, TreadmillSettings settings) {
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle(TreadmillBundle.message("transfer.import.title"))
                        .withDescription(TreadmillBundle.message("transfer.import.json.description")),
                project, null);
        if (file == null) {
            return -1;
        }
        List<SessionData> imported;
        try {
            imported = SessionJsonCodec.parseJson(Files.readString(file.toNioPath()));
        } catch (IOException | JsonSyntaxException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.import.json.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("transfer.import.errorTitle"));
            return -1;
        }
        return finishImport(project, settings, SessionImport.selectNewSessions(imported, settings.getSessions()));
    }

    private static boolean notifyIfEmpty(@Nullable Project project, List<SessionData> sessions) {
        if (!sessions.isEmpty()) {
            return false;
        }
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.export.none"));
        return true;
    }

    private static @Nullable VirtualFileWrapper pickSaveFile(
            @Nullable Project project, String title, String description, String extension, String defaultName) {
        FileSaverDescriptor descriptor = createSaveDescriptor(title, description);
        descriptor.withExtensionFilter(extension);
        return FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((VirtualFile) null, defaultName);
    }

    private static void writeFile(@Nullable Project project, VirtualFileWrapper wrapper, String content, int count) {
        try {
            Files.writeString(wrapper.getFile().toPath(), content);
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message("notification.export.done", count, wrapper.getFile().getName()));
        } catch (IOException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.write.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("notification.title"));
        }
    }

    /**
     * 2024.3 ships only the varargs FileSaverDescriptor constructor, which 2025.1+
     * deprecates in favor of the two-arg one that 2024.3 lacks. A direct call to
     * either breaks one side (deprecation warning vs. NoSuchMethodError), so pick
     * the constructor reflectively at runtime.
     */
    private static FileSaverDescriptor createSaveDescriptor(String title, String description) {
        try {
            try {
                return FileSaverDescriptor.class
                        .getConstructor(String.class, String.class)
                        .newInstance(title, description);
            } catch (NoSuchMethodException onlyVarargsAvailable) {
                return FileSaverDescriptor.class
                        .getConstructor(String.class, String.class, String[].class)
                        .newInstance(title, description, new String[0]);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No usable FileSaverDescriptor constructor", exception);
        }
    }

    private static String safeFileName(String name) {
        String safe = name.replaceAll("[^\\w\\-. ]", "_").trim();
        return safe.isBlank() ? "treadmill-session" : safe;
    }
}
