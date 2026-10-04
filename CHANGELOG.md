# Changelog

The build renders the section for the version it builds into the plugin's change notes, so this file is the only place to edit them.

## [Unreleased]

- The tool window no longer scrolls sideways at common widths. Saved-session rows that don't fit are cut off at the edge and show in full on hover, and the totals and streak line under the heatmap now takes two lines.

## [1.3.1]

- A clearer plugin description. The plugin itself is unchanged.

## [1.3.0]

- New leaves the clock empty: opening the tool window afterwards, in any project or after a restart, no longer loads the previous walk back, and the form offers a fresh name instead of the old one.
- Undo after Reset restores the walk even when the clock was started again in the meantime.
- The settings page is under Settings | Tools | Treadmill Buddy, as documented, instead of Other Settings, and Settings search finds its individual options.
- Number fields read thousands separators where they cannot be misread, so a 10,000-step goal is no longer saved as 10 steps. An ambiguous decimal such as 1,500 is flagged with both readings.
- Another IDE briefly holding the history lock no longer triggers the disk-failure warning: saves retry quietly, closing the IDE waits a moment for the lock, and a separate warning appears only when the lock stays busy.
- The 30-second autosave writes the history file on a background thread instead of the UI thread, and reading the history no longer waits for a write in progress.
- Move reminders count only time at the keyboard: after 10 minutes without input no reminders arrive, so they no longer pile up in the notification log overnight.
- CSV exports re-saved in Excel import again: semicolon separators, decimal commas, localized dates and booleans, Windows-1252 text, and ids rewritten in scientific notation are read, and rows that can't be read are counted in the import message.
- Deleting old sessions in bulk can be undone from the notification, the dialog rejects a day count it cannot use, and it no longer reports deleting zero sessions.
- CSV exports write modes and algorithms as stable ids; exports with the older English labels still import.
- Screen readers can read the workout clock and find the 14-day chart and the activity heatmap, and the floating clock's close button is labelled.

## [1.2.0]

- Paused Save and Resume keep newer progress saved by another IDE and merge only fields edited locally, including speed, name, and targets. Completed or deleted sessions are not restarted by stale forms.
- History writes always require the cross-process lock and merge the latest file. Pending saves and deletions retry in the background when the lock or file is unavailable.
- Loading the session already on the clock preserves its live progress. Deletion and Undo now coordinate with the current session so later saves do not restore deleted history.
- Partial seconds survive pauses, saves, and IDE restarts. Pending whole seconds are credited at the previous rate before speed, incline, or calorie algorithm changes.
- New walking activity is attributed to the day it happened, including resumed older sessions and walks across midnight, keeping daily goals, charts, and streaks accurate.
- Imported step totals stay intact when walking resumes; only new distance uses the current height estimate. Reset also clears fractional timing and step progress.
- Resume and paused Save validate the complete form. Edited calorie, weight, and interval targets take effect on Save or Resume; changed interval lengths restart the walking block while retaining recorded totals.
- Profile measurements, speed, and goals preserve their exact metric values across unit changes. Editable numbers work across locales, and small positive goals use enough decimal places to stay valid.
- CSV import supports quoted names containing commas, quotes, and line breaks, and rejects malformed quoting before saving any rows. TCX exports with incomplete speed histories distribute distance across the full workout.
- History cleanup uses the most recent recorded activity and protects the session currently on the clock, including while paused.

## [1.1.1]

- Walking time is no longer lost when a running session is replaced: New, a mode switch, or loading another session now saves the current walk first.
- Cross-IDE history: writes take a cross-process lock and merge the file under it, a session resumed in another IDE keeps its newer state here and on shutdown, a session deleted in another IDE disappears here too, and a history file that cannot be parsed is set aside instead of overwritten.
- CSV and JSON import no longer skip a session just because its name and start minute match another one; rows that carry an id are matched by id.
- Interval walks: the walk block after a break starts with a fresh idle window instead of auto-pausing on its first tick, and the idle auto-pause credits walking up to the moment the threshold was crossed.
- Several open project windows now mirror the shared session's speed, incline, algorithm, and name, so Resume or Save in a second window no longer overwrites the walk with that window's stale fields.
- Non-numeric input such as NaN is rejected everywhere, the clock renders correctly under Arabic and Persian locales, and Save and Import say so when the history file could not be written.
- The plugin is now built with Java 21, which the 2024.3 platform requires.
- The first-run profile dialog now saves the weekly goal, rest days, and streak-risk hour it shows.
- The Settings page no longer reports unsaved changes in imperial mode just from opening it, and no longer nudges the stored weight and height on every OK.
- The session-complete notification uses your display units instead of always saying km.
- Reset asks for confirmation on a session with walked time and can be undone from the notification.
- The floating clock stays closed once you dismiss it; the Float Clock button brings it back and re-enables the automatic opening.
- TCX export writes a schema-valid sport value so strict importers accept the file.
- Imports and Undo no longer change which session the clock opens with after a restart; a stray Shift or Ctrl press no longer resumes an auto-paused session; a hand-edited history file with null fields no longer breaks export or the list.
- Since 1.1.0: the trash icon deletes the highlighted session (undoable) and bulk cleanup moved to the history icon; balloons close by themselves; personal-record and countdown-clock fixes for re-imported sessions; lossless CSV round trip; safer history migration that keeps the old copy until the new store confirms the write; a warning when the history file cannot be written.

## [1.1.0]

- New **Interval walk** mode: alternating walk/break blocks (e.g. 25/5 minutes) with a chime and notification at each switch.
- Speed presets: save named treadmill speeds and switch with one click from the new Presets button.
- Per-speed session breakdown: hover the Distance tile to see how long you walked at each speed.
- Optional weekly goal (steps, distance, or calories) with its own progress bar, next to the daily goal.
- Walking streak is now computed over the full history and supports 0-6 configurable rest days per week; a "streak at risk" hint appears in the evening.
- Six-month activity heatmap (GitHub-style) in the tool window.
- Personal records (longest session, best day distance/steps) with a notification when you break one.
- CSV and JSON import, JSON export, and per-session TCX export (with per-minute trackpoints) for Garmin Connect / Strava; JSON round-trips the full model for backup/restore.
- The floating clock and big clock show the current interval block ("Walk" / "Break"), and break blocks are exempt from the inactivity auto-pause.
- Session history refreshes when the IDE regains focus, so walks saved in another JetBrains IDE appear immediately; the saved-sessions list shows the 25 most recent with a Show All toggle.
- Daily-goal progress glyph in the status bar widget.
- New "Keep Running While Idle" toggle (status bar popup / Tools menu) for reading-heavy walking.
- Session history now lives in `~/.treadmill-buddy/sessions.json`, shared across all JetBrains IDEs and safe across IDE reinstalls; existing history migrates automatically.

## [1.0.2]

- Fully remove the deprecated file-chooser constructor reference flagged by the Marketplace verifier (resolved at runtime for 2024.3 and 2025.1+ compatibility).

## [1.0.1]

- Replace a deprecated file-chooser API flagged by the Marketplace verifier.

## [1.0.0]

- Metric / imperial unit setting (km/h-kg-cm or mph-lb-in), switchable any time; stored data stays metric and converts in the UI.
- Optional daily goal (steps, distance, or calories) with a progress bar in the tool window and a once-per-day notification when reached.

## [0.3.0]

- Incline support with the full ACSM grade equations.
- Optional "time to move" reminder after a configurable sitting time.
- 14-day distance chart and walking-day streak in the tool window.
- Actions for start/pause, save, and new session in the Tools menu, tool window title bar, and status bar popup - bindable to shortcuts.
- Inline field validation instead of modal error dialogs.
- Session deletion is now undoable from a notification.
- Saved sessions sorted newest first; floating clock remembers position and pin state.
- Age removed from the profile - no algorithm used it.
- Dark plugin icon, message-bundle scaffolding, and Marketplace signing/publishing pipeline.

## [0.2.0]

- Drift-proof session timing based on wall-clock deltas instead of raw timer ticks.
- One shared workout engine across all project windows; sessions keep running when the tool window is closed.
- Status bar widget with live time and calories; click to pause/resume.
- Dark-theme support for the seven-segment clock.
- Completion and save confirmations are now notifications instead of modal dialogs.
- Saved sessions list with toolbar, session details, keyboard support, and CSV export.
- Today / 7 days / all-time totals for distance and calories.
- Auto-pause now counts mouse activity too, and pauses after system sleep instead of crediting slept time.
- Floating clock gained a Pin (always on top) toggle.
- Proper resource cleanup on tool window close and plugin unload.

## [0.1.0]

- Initial Treadmill Buddy release with digital stopwatch, floating clock, saved sessions, calorie algorithms, countdown modes, and configurable profile settings.
