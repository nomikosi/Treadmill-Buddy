# Treadmill Buddy

![Treadmill Buddy hero](docs/treadmill-buddy.png)

Treadmill Buddy is a JetBrains IDE plugin for developers who use an under-desk treadmill while they work. It keeps a digital stopwatch inside the IDE, estimates calories, tracks distance and steps, and helps you save walking sessions without leaving your coding flow.

## What It Does

- Opens a `Treadmill Buddy` tool window inside the IDE.
- Works in metric (km/h, kg, cm) or imperial (mph, lb, in) units — switchable any time in settings; stored data converts automatically.
- Shows a large seven-segment digital clock for workout time (light and dark theme aware).
- Adds a status bar widget with live time and calories; click it for a quick-actions menu.
- Offers a movable floating clock with `Pause` / `Resume`, `Save`, and `Pinned` / `Unpinned` (always on top) controls; position and pin state are remembered.
- Saves sessions so you can pause, resume, load, or delete them later (deletion is undoable from the notification).
- Tracks elapsed time, countdown time, distance, steps, calories, incline, and targets.
- Shows today / last-7-days / all-time totals, a 14-day distance chart, a six-month activity heatmap, and your walking-day streak (with 0-2 configurable rest days per week and an evening "streak at risk" hint).
- Stores session history in `~/.treadmill-buddy/sessions.json`, shared across all JetBrains IDEs and safe across IDE reinstalls.
- Exports session history to CSV or JSON, imports both back (JSON restores the full model including speed segments), and exports single sessions as TCX workouts with per-minute trackpoints for Garmin Connect, Strava, and similar services.
- Supports optional daily and weekly goals (steps, distance, or calories) with progress bars, a status bar progress glyph, and a congratulation notification when you hit them.
- Lets you save named speed presets and switch between them with one click; multi-speed sessions get a per-speed breakdown tooltip.
- Tracks personal records (longest session, best day distance and steps) and notifies you when you break one.
- Updates countdown estimates live when speed, incline, or calorie algorithm changes.
- Auto-pauses after keyboard and mouse inactivity, then resumes when typing starts again; a `Keep Running While Idle` toggle suspends this for reading-heavy walking or meetings.
- Optionally reminds you to move after a configurable sitting time.
- Provides IDE actions (`Start/Pause Treadmill Session`, `New Treadmill Session`, ...) in the Tools menu, the tool window title bar, and Find Action, so you can bind keyboard shortcuts.
- Keeps one shared workout running across all open project windows — timing is wall-clock based, so a busy IDE never shortens your session.
- Stores profile and default settings in IDE settings.

## Session Modes

`Marathon`

Counts up from `00:00:00`. Use it for open-ended treadmill work sessions where you want to track time, distance, steps, and calories as you go.

`Calorie burn`

Counts down from the estimated time needed to burn a calorie target. Enter calories and treadmill speed, and the clock previews the required time before you start.

`KG burn`

Counts down from the estimated time needed to burn a target body mass (kg, or lb in imperial mode). If the goal would require more than 99 days, the plugin nudges you toward a smaller goal first.

`Interval walk`

Alternates walking and break blocks (for example 25 minutes walking, 5 minutes standing) with a chime and notification at each switch. The clock counts down the current block; distance, steps, and calories accumulate only while walking.

## Calorie Algorithms

Treadmill Buddy includes four calorie-estimation models:

- `ACSM treadmill (default)`: uses the widely referenced ACSM walking/running oxygen-cost equations, including the incline term when you set an incline.
- `Compendium MET gross`: uses speed bands from the Compendium of Physical Activities and includes resting energy.
- `Compendium MET active`: uses Compendium MET speed bands minus 1 MET for a conservative active-calorie estimate.
- `Distance cost per km`: estimates calories from common cost-of-transport values per kg per kilometer.

Each algorithm has a hover tooltip in the UI. The default algorithm can be changed from `Settings | Tools | Treadmill Buddy`.

## Settings

On first IDE startup, Treadmill Buddy shows a notification inviting you to set up:

- Units (metric or imperial)
- Weight (kg or lb)
- Height (cm or in)
- Default calorie algorithm
- Auto-pause timeout
- Move-reminder interval
- Optional daily and weekly goals (steps, distance, or calories)
- Streak rest days per week (0-2)

You can edit everything later from `Settings | Tools | Treadmill Buddy`; switching units converts the displayed values on the spot, and all stored data stays metric internally, so nothing is lost by switching back and forth.

- The auto-pause timeout defaults to `10` minutes; `0` disables auto-pause.
- The move reminder is off (`0`) by default; set it to e.g. `60` to get a nudge after an hour without walking.
- The daily goal is `None` by default. Pick steps, distance, or calories and a value; the tool window then shows a progress bar for today, and you get a one-time notification each day you reach the goal.
- Only data that actually feeds the calorie models is collected — weight and height.

## Workout Behavior

- Changing treadmill speed or incline during a workout updates future distance, calorie, and countdown estimates.
- Changing calorie algorithm during `Calorie burn` or `KG burn` updates the remaining countdown time.
- Invalid speed, incline, or target values are flagged inline on the field as you type.
- Speeds above `20 km/h` show the warning: `slow down coyote beep beep!!`
- Keyboard and mouse activity keep the session alive; after the idle timeout it auto-pauses, and typing resumes it.
- If the machine goes to sleep, the session auto-pauses instead of crediting the slept time.
- Session completion shows a notification (no modal dialog interrupting your typing).
- Saved sessions appear in a list with duration, distance, calories, and date; double-click or press Enter to load one, use the toolbar to delete, import CSV/JSON, or export sessions as CSV, JSON, or TCX. The list shows the 25 most recent sessions with a Show All toggle, and refreshes when the IDE regains focus so walks saved in another JetBrains IDE appear immediately.
- The CSV export always uses metric columns (`speed_kmh`, `distance_km`), regardless of the display units, so exported data stays comparable, and re-importing skips sessions you already have.

## Build and Development

```powershell
./gradlew buildPlugin    # builds the installable ZIP under build/distributions
./gradlew runIde         # launches a sandbox IDE with the plugin installed
./gradlew test           # runs the unit tests
./gradlew verifyPlugin   # runs the IntelliJ Plugin Verifier
```

The project targets IntelliJ Platform 2024.3+ (`sinceBuild 243`) and only depends on `com.intellij.modules.platform`, so it runs in IntelliJ IDEA, PyCharm, WebStorm, and every other JetBrains IDE.

## Releasing

Pushing a `v*` tag runs the release workflow, which builds, signs, and publishes the plugin to JetBrains Marketplace. It needs these repository secrets: `PUBLISH_TOKEN` (Marketplace permanent token), `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` (plugin signing, see the [JetBrains signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)).

## License

Treadmill Buddy is licensed under the [Apache License 2.0](LICENSE).
