# SmartTube Parental Time Limit (fork patch)

This fork adds an **optional, PIN-protected time-limit feature** to SmartTube.
After the configured amount of use, the app locks itself for a mandatory rest
period. Rest is enforced even across app restarts and TV reboots.

This is a **personal-fork** modification. It is not part of upstream
SmartTube and is not supported by the upstream project.

---

## 1. What the feature does

| Behaviour | Default |
|---|---|
| Watching time per session | **30 min** |
| Mandatory rest after the limit | **30 min** (wall-clock, enforced across restarts) |
| What the limit measures | **Time spent in the app** (see §1.1) |
| Warning before the lock | a toast appears **60 s** before the quota runs out |
| On lock | the video is **paused**, the lock screen shows a live countdown |
| PIN protects | enable/disable, change limit, change rest, change counting mode, change PIN, reset timer, reset everything, parent options on the lock screen |
| Storage | a separate `parental_limit` SharedPreferences file |
| First-run flow | parent is forced to set a PIN before the feature can be enabled |

The feature is **off** by default. Enable it from
*App Settings → Parental time limit* (a top-level entry, right below
*General*).

### 1.1 Counting modes

| Mode | What counts | What does not |
|---|---|---|
| **Time spent in the app** (default) | any time a non-lock Activity is in the foreground: browsing, searching, playing, PiP | time the app is fully backgrounded |
| **Time actually watching** | only while the playback engine reports an active playback | paused videos, browsing, searching, and background (PiP) playback |

Both modes use the same limit, the same rest period and the same PIN
protection — they only differ in *what the quota measures*.

In *Time actually watching* mode the controller polls
`PlaybackView.isPlaying()` (via `PlaybackPresenter`) on the 500 ms tick and
banks the elapsed playback time whenever the video stops.

---

## 2. Files added / changed

```text
smarttubetv/src/main/AndroidManifest.xml                                                              (modify)
+
common/src/main/java/com/liskovsoft/smartyoutubetv2/common/parental/ParentalLimitData.java           (new)
common/src/main/java/com/liskovsoft/smartyoutubetv2/common/parental/ParentalLimitController.java     (new)
common/src/main/java/com/liskovsoft/smartyoutubetv2/common/parental/PinHasher.java                   (new)
smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/parental/ParentalLimitActivity.java      (new)
smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main/MainApplication.java             (modify)
+
common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/ParentalLimitSettingsPresenter.java  (new)
common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/AppDataSourceManager.java            (modify)
common/src/main/res/values/strings.xml                                                              (modify)
common/src/main/res/values-zh/strings.xml                                                           (modify)
common/src/main/res/values-zh-rTW/strings.xml                                                        (modify)
```

The controller lives in the `common` module so the settings presenter can
reach it without a circular dependency on the `tv` module. The lock Activity
is registered with the controller at runtime
(`ParentalLimitController.setLockActivityClass(...)`) for the same reason.

### 2.1 Fork-specific build changes

| Change | Why |
|---|---|
| `applicationId` → `org.smalltube.stable` | lets the patched APK be installed **side-by-side** with the upstream SmartTube |
| `app_name` / `browse_title` → `SmallTube` | makes it obvious which app is the patched one |
| `ststable` now ships **all four ABIs** (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) | the inherited `abiFilters` only produced ARM, which made the APK fail with `INSTALL_FAILED_NO_MATCHING_ABIS` on x86 TVs |
| `google-services.json` moved aside | the upstream Firebase key isn't registered for the new package name; Crashlytics/Firebase are disabled in this fork |
| `.mise.toml` pins `temurin-17.0.21+9` | the upstream README asks for JDK 14, which was never published for arm64 macOS; the project's own CI already uses JDK 17 |

---

## 3. State machine

```text
   ┌─────────┐  limit reached  ┌──────────┐  rest elapsed  ┌─────────┐
   │ READY   │ ──────────────▶ │ RESTING  │ ─────────────▶ │ READY   │
   └────▲────┘                 └──────────┘                 └─────────┘
        │
        │ foreground / background transitions do not change
        │ READY/RESTING; only the ticker is gated.
        │
   ┌─────────┐  parent PIN  ┌────────────────┐
   │ ACTIVE  │ ───────────▶ │ PARENT_OVERRIDE│ (extends current session)
   └─────────┘              └────────────────┘
```

* **DISABLED** – feature is off, no timing occurs.
* **READY** – feature is on but no Activity is in foreground.
* **ACTIVE** – feature is on, at least one Activity is in foreground; the
  ticker is running and `usedMs` is accumulating.
* **RESTING** – `restUntilWall` is in the future; the lock Activity is on
  top of the stack; the Back key is consumed.

`PARENT_OVERRIDE` is not a persistent state — it's a one-shot operation
(extend / skip / reset) that mutates the persisted timer and exits.

---

## 4. Time accumulation
* The rest period uses `System.currentTimeMillis()` because rest must
  survive a TV reboot, where `elapsedRealtime` resets.
* `usedMs` is persisted every 5 s while ticking, so an OS-initiated
  process kill loses at most ~5 s of progress.
* When the app comes back to the foreground mid-rest, the lock Activity
  is launched immediately (no warm-up grace period).

This is a best-effort, app-level mechanism — a determined child with
ADB / system access can still uninstall or clear app data, opening a
different YoTube client, etc. For device-level enforcement you also need
Android TV's system-level parental controls or a device-management app.

---

## 5. Build & install

The repository uses the standard SmartTube Gradle project.

### 5.1 Prerequisites

* **JDK 14** (older is fine; newer can cause crashes — see upstream README).
* Android SDK with platform-tools (`adb`).
* Git submodules initialised.

### 5.2 Initialise

```bash
cd SmartTube
git submodule update --init
```

### 5.3 Build a debug APK

The product flavors match upstream: `stbeta`, `ststable`, `stfdroid`.
For a personal fork the easiest choice is the same one you already use,
e.g.:

```bash
# Build only:
./gradlew :smarttubetv:assembleStstableDebug

# Or build + push to a connected TV:
./gradlew :smarttubetv:installStstableDebug
```

The output APK is in:
```text
smarttubetv/build/outputs/apk/ststable/debug/smarttubetv-ststable-debug.apk
```

### 5.4 Install manually

```bash
# Enable developer options + ADB debugging on your TV first.
adb connect <TV_IP_ADDRESS>
adb devices

adb install -r smarttubetv/build/outputs/apk/ststable/debug/smarttubetv-ststable-debug.apk
```

### 5.5 Signing & co-existence with upstream

* `ststable` → `applicationId org.smarttube.stable`
* `stbeta`   → `applicationId org.smarttube.beta`
* `stfdroid` → `applicationId app.smarttube.fdroid`

Debug builds are signed with the Android debug key, which differs from
upstream's release key, so a debug build **cannot replace an installed
release build of the same flavor** — it installs side-by-side instead.
This is usually fine for a personal fork; install under a different
flavor (e.g. `stbeta`) if you want both versions side-by-side.

To install a release build that **does** replace your upstream install,
sign the APK with the same key as upstream — outside the scope of this
patch.

---

## 6. First-run setup

1. Install the patched APK on your TV.
2. Open the app and go to **App Settings → General → Parental time limit**.
3. Toggle **Enable time limit** on — the dialog will ask you to set a
   parent PIN (≥ 4 characters) before continuing.
4. Pick a **Watching time per session** (15–120 min) and a **Mandatory
   rest** (15–120 min).
5. Done. From now on, after the kid has used the app for the configured
   amount of foreground time, the lock screen will appear and the
   countdown will begin.

---

## 7. Parent actions on the lock screen

When the lock screen is showing, press **Parent options** and enter the
PIN to access:

* Extend session by 10 minutes
* Extend session by 30 minutes
* Skip current rest
* Reset timer
* Disable time limit (returns to home)
* Cancel

> "Skip rest" and "Reset timer" do **not** re-prompt for PIN — they are
> already gated by the PIN entry that opened the menu.

---

## 8. Acceptance tests

| # | Scenario | Expected |
|---|---|---|
| 1 | Use the app for the configured limit; the lock appears | ✓ |
| 2 | Background the app for 5 min, return | timer paused during the 5 min |
| 3 | Use 29 min, force-stop, reopen | ≤ 1 min remaining (no fresh quota) |
| 4 | Rest lock visible, force-stop, reopen | lock still showing, same deadline |
| 5 | Wrong PIN on parent options | access denied |
| 6 | Right PIN on parent options | menu opens |
| 7 | After rest ends, use again, hit limit | lock appears again |
| 8 | Browse ↔ Playback ↔ Search switching | timer keeps running, no resets |
| 9 | 60 s before the limit | a warning toast appears |
| 10 | The lock engages while a video plays | the video is paused (no audio behind the lock) |
| 11 | Lock screen | shows the live countdown and “you can watch again in N minutes” |
| 12 | Manual system clock change mid-rest | wall-clock rest deadline is honoured; monotonic foreground timer unaffected |
| 13 | *Time actually watching* mode: browse for 5 min, no playback | no time counted |
| 14 | *Time actually watching* mode: play for 5 min, pause for 5 min | only ~5 min counted |
| 15 | *Time actually watching* mode: switch to playback mode | the mode change requires the PIN |

---

## 9. Known limitations

1. **App-level only.** A determined kid with access to TV settings or
   another YoTube client can still bypass this. For device-level
   enforcement use Android TV's built-in restricted profiles / app
   pinning in combination.
2. **Foreground = AGREE.** A long PiP session in the background while
   the kid is doing homework still counts toward the timer (any Activity
   in the foreground stack increments).
3. **One profile.** The feature tracks one cumulative quota for all
   users of the app. There's no per-child profile.
4. **System clock.** Rest uses wall-clock; if your kid has the TV
   settings PIN they can manually move the clock forward. Foreground
   time uses monotonic clock and is unaffected.
5. **No remote PIN unlock.** Parent actions require physical access to
   the TV (the PIN is typed with the remote). No out-of-band unlock.

---

## 10. Reverting

If you want to drop the feature, the changes are scoped:

```bash
git revert <merge-commit-of-this-patch>
# or, manually:
#   - delete smarttubetv/.../tv/parental/         (4 files)
#   - delete common/.../parental/ParentalLimitSettingsPresenter.java
#   - revert AndroidManifest.xml
#   - revert MainApplication.java
#   - revert GeneralSettingsPresenter.java
#   - revert strings.xml
```

The 30 lines added to `MainApplication.java` and
`GeneralSettingsPresenter.java` are isolated additions and can be
deleted without affecting any other functionality.