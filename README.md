# Screen Time Tracker

Sideloadable Android app that tracks how long the screen is **on and unlocked**.
Supports Android 10 (API 29) through Android 17 (API 37).

- Today's total (live), plus day / week / month history with daily averages
- Days follow your sleep, not the clock: a day runs from waking up until you sleep (see below)
- % of waking day: screen time as a share of the time you were actually awake
- Per-app time (% of waking day and % of screen time) for each day, week and month
- Optional **active use**: only the screen time when the phone is held or touched, via
  Accessibility. Shown next to screen time everywhere: blue = screen on, purple = actively used
- CSV export: daily, weekly, monthly totals, apps per day, or every individual session
- No internet permission use, no account; data stays on the phone

## How it works
Android records screen-on/off and lock-screen shown/hidden events. With **Usage access**
granted, the app reads those events and counts time where the screen is on and the lock
screen is not showing. Android only keeps this history ~1 week, so the app copies it into
its own database on open and every 3 hours in the background (WorkManager). On first run it
imports whatever history the phone still has.

### Waking days
A break of 5 hours or more with no use (settable, 3–12h) counts as sleep. The day ends at the last
use before it, and the next day starts at the first use after and is dated by that. Up at 5 pm and
on the phone until 4 am is one day, dated the day it started; use after midnight isn't a new day.
- Checking the time at night doesn't break up sleep: an unlock under 5 minutes with an hour of no use
  either side is ignored when looking for sleep. Its screen time counts towards the day before.
- No 5-hour break (a night without sleep) makes one long day; a 5-hour nap starts a new one.
- A day's start can be set by hand: *Day started … · change* on Today, or *Change when this day
  started* on any day. Pick a time, *Start a new day now*, or go back to the detected start. A start
  set in the sleep before a day, or up to 12h into it, moves that day's start (use before it goes to
  the day before); anywhere else it starts a new day. Stored as a list of times in settings.
- % of waking day uses each day's time awake (start to sleep, or so far for today); weeks and months
  use the total screen time over the total time awake.
- Logic: `data/DaySplitter.kt` (tests in `app/src/test`). Applies to all history, since it's worked
  out from the stored sessions each time.

### Active use
Screen time alone counts a video playing on the table, or a screen left on. Active use counts
only the part of screen time when the phone is **held** or was **touched** in the last 15 s.
Podcasts with the screen off never count. (Separate listening tracking was removed in 1.5.)

- **Held**: the accelerometer, only while the screen is on (25 readings/s, batched). Each
  second's average reading is compared with the last; a hand makes it wander (tilt and drift),
  while lying still or vibrating (car, speaker) doesn't. It counts as held after 2 moving
  seconds out of 3, and stops at the last movement once it has been still for 15 s.
  Logic and thresholds: `data/HeldDetector.kt` (tests in `app/src/test`).
- **Touched**: an accessibility service (`data/ActiveService.kt`) with an invisible 1-pixel
  overlay that is told a touch happened elsewhere (never where), plus tap/typing events. It
  can't read screen content.
- The home screen shows what the sensor sees right now ("movement" score; moving above 0.03),
  useful for checking the thresholds on a real phone.
- Only recorded live, from when it's switched on; older days have screen time only.

## Build
    git clone https://github.com/SpiralCrunch/ScreenTime.git && cd ScreenTime
    ./gradlew assembleRelease      # unit tests: ./gradlew testDebugUnitTest
Output: `app/build/outputs/apk/release/app-release.apk`

Release builds are signed only when `SCREENTIME_KEYSTORE`, `SCREENTIME_KEYSTORE_PASSWORD`,
`SCREENTIME_KEY_ALIAS` and `SCREENTIME_KEY_PASSWORD` are set in `~/.gradle/gradle.properties`.
Without them the APK is unsigned. Official releases are published with `./release.sh`.

## Install
1. With [Obtainium](https://github.com/ImranR98/Obtainium) (gets updates automatically): *Add App* →
   `https://github.com/SpiralCrunch/ScreenTime`. Or download the APK from the
   [latest release](https://github.com/SpiralCrunch/ScreenTime/releases/latest) and open it; allow
   "install unknown apps" for that source.
2. Open Screen Time → *Open Usage access settings* → enable Screen Time.
3. If the switch is greyed out (Android 13+ restricted settings for sideloaded apps):
   Settings › Apps › Screen Time › ⋮ › *Allow restricted settings*, then retry.
4. Optional, for active use: in the app tap *Count only real use · set up* › *Open settings* ›
   Screen Time active use (often under Downloaded apps) › on. Same greyed-out fix as above.

## License
Free software under the [GNU General Public License v3.0](LICENSE). You may use, study, change and
share it. If you distribute a modified version, it must also be released under the GPL-3.0 with
its source code.
