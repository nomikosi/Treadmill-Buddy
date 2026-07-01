# Treadmill Buddy

![Treadmill Buddy hero](docs/treadmill-buddy.png)

Treadmill Buddy is an IntelliJ IDEA plugin for developers who use an under-desk treadmill while they work. It keeps a digital stopwatch inside the IDE, estimates calories, tracks distance and steps, and helps you save walking sessions without leaving your coding flow.

## What It Does

- Opens a `Treadmill Buddy` tool window inside IntelliJ IDEA.
- Shows a large seven-segment digital clock for workout time.
- Offers a movable floating clock with `Pause` / `Resume` and `Save` controls.
- Saves sessions so you can pause, resume, load, or delete them later.
- Tracks elapsed time, countdown time, distance, steps, calories, and targets.
- Updates countdown estimates live when speed or calorie algorithm changes.
- Auto-pauses after keyboard inactivity, then resumes when typing starts again.
- Stores profile and default settings in IntelliJ settings.

## Session Modes

`Marathon`

Counts up from `00:00:00`. Use it for open-ended treadmill work sessions where you want to track time, distance, steps, and calories as you go.

`Calorie burn`

Counts down from the estimated time needed to burn a calorie target. Enter calories and treadmill speed, and the clock previews the required time before you start.

`KG burn`

Counts down from the estimated time needed to burn a target mass in kg. If the goal would require more than 99 days, the plugin nudges you toward a smaller goal first.

## Calorie Algorithms

Treadmill Buddy includes four calorie-estimation models:

- `ACSM flat treadmill (default)`: uses the widely referenced ACSM walking/running oxygen-cost equations at 0% incline.
- `Compendium MET gross`: uses speed bands from the Compendium of Physical Activities and includes resting energy.
- `Compendium MET active`: uses Compendium MET speed bands minus 1 MET for a conservative active-calorie estimate.
- `Distance cost per km`: estimates calories from common cost-of-transport values per kg per kilometer.

Each algorithm has a hover tooltip in the UI. The default algorithm can be changed from `Settings | Tools | Treadmill Buddy`.

## Settings

On first IDE startup, Treadmill Buddy asks for:

- Weight in kg
- Age
- Height in cm
- Default calorie algorithm
- Auto-pause timeout

You can edit these later from `Settings | Tools | Treadmill Buddy`. The auto-pause timeout defaults to `10` minutes, and `0` disables auto-pause.

## Workout Behavior

- Changing treadmill speed during a workout updates future distance, calorie, and countdown estimates.
- Changing calorie algorithm during `Calorie burn` or `KG burn` updates the remaining countdown time.
- Speeds above `20 km/h` show the warning: `slow down coyote beep beep!!`
- Saved sessions appear as rows with a `Bin` button for deletion.

## Build

```powershell
gradle composedJar
```

To create an installable ZIP manually after building:

```powershell
gradle buildPlugin
```

The plugin ZIP is written under `build/distributions`.

## License

Treadmill Buddy is licensed under the [Apache License 2.0](LICENSE).
