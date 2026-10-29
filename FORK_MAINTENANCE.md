# Fork Maintenance Guide — SmallTube (parental time limit)

> **Read this first if you are a new session picking this fork up.**
> It contains everything needed to continue the work, and to re-sync with
> upstream SmartTube without re-discovering the hard-won lessons.

---

## 0. TL;DR

| | |
|---|---|
| **What** | A personal fork of [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) with a PIN-protected parental time limit |
| **App id** | `org.smalltube.stable` (deliberately ≠ upstream's `org.smarttube.stable`, so both can be installed side by side) |
| **Display name** | `SmallTube` |
| **Our branch** | `feature/parental-time-limit` |
| **Upstream** | `master` of `yuliskov/SmartTube` |
| **Base commit** | `bbed8fbbf` (upstream 32.68) |
| **Build** | `./gradlew :smarttubetv:assembleStstableRelease` → `smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_<ver>_universal.apk` |
| **Feature docs** | [`PARENTAL_LIMIT.md`](PARENTAL_LIMIT.md) — what the feature does and how it behaves |
| **Sync upstream** | `./scripts/sync-upstream.sh` (see §4) |

---

## 1. Orientation: the two documents

| Doc | Purpose |
|---|---|
| **PARENTAL_LIMIT.md** | The *product* doc: what the feature does, the state machine, the counting modes, the settings UI, acceptance tests, known limitations. Update it when behaviour changes. |
| **FORK_MAINTENANCE.md** (this file) | The *engineering* doc: repo layout, how to sync with upstream, where every integration point is, how to build, and the traps that have already bitten us. |

---

## 2. Repository layout

```
SmartTube/
├── common/                                  ← shared library module
│   └── src/main/java/com/liskovsoft/smartyoutubetv2/common/
│       ├── parental/                       ← ★ OUR CODE (3 files, self-contained)
│       │   ├── ParentalLimitData.java          prefs + salted SHA-256 PIN
│       │   ├── ParentalLimitController.java    lifecycle + state machine
│       │   └── PinHasher.java
│       ├── app/presenters/settings/
│       │   └── ParentalLimitSettingsPresenter.java   ← ★ OUR CODE (settings UI)
│       └── misc/AppDataSourceManager.java    ← ✎ 1 hook: top-level settings entry
├── smarttubetv/                            ← the TV app module
│   └── src/main/java/com/liskovsoft/smartyoutubetv2/tv/
│       ├── parental/ParentalLimitActivity.java   ← ★ OUR CODE (lock screen)
│       └── ui/main/MainApplication.java      ← ✎ 1 hook: controller init
├── scripts/                                ← ★ OUR CODE (maintenance tooling)
│   ├── setup-build-env.sh                      one-shot build env setup
│   ├── sync-upstream.sh                        merge upstream into the fork
│   ├── verify-fork.sh                          build + sanity checks
│   ├── adb_pair.py / adb_pair_retry.py         wireless-debugging pairing
│   └── adb_pair_retry.py
├── .mise.toml                               ← ★ pins Temurin 17.0.21+9
├── PARENTAL_LIMIT.md                        ← ★ feature doc
└── FORK_MAINTENANCE.md                      ← ★ this file
```

★ = added by us, never conflicts with upstream.
✎ = upstream file we modified, **this is the entire conflict surface** (4 files).

---

## 3. Architecture in one page

### 3.1 Why the controller lives in `common`, not `smarttubetv`

`ParentalLimitSettingsPresenter` (in `common`) needs to reach the controller.
The lock screen `ParentalLimitActivity` (in `smarttubetv`) also needs it.

If the controller lived in `smarttubetv`, `common` could not import it →
circular module dependency. So the controller lives in `common` and the
lock Activity is injected at runtime:

```java
// MainApplication.onCreate()
ParentalLimitController.setLockActivityClass(ParentalLimitActivity.class);
ParentalLimitController.init(this);
```

The controller never imports anything from the `tv` module. **Keep it that
way** — it is what makes the fork cheap to re-base.

### 3.2 The four integration points

Everything the fork does to upstream code is these four hooks:

| # | File | What we add | Where |
|---|---|---|---|
| 1 | `smarttubetv/.../tv/ui/main/MainApplication.java` | 2 imports + 2 lines in `onCreate()` | right after `setupViewManager()` |
| 2 | `common/.../misc/AppDataSourceManager.java` | 1 import + 1 `SettingsItem` | right after the *General* entry |
| 3 | `common/.../presenters/settings/GeneralSettingsPresenter.java` | 1 menu entry | end of `appendMiscCategory()` |
| 4 | `smarttubetv/src/main/AndroidManifest.xml` | 1 `<activity>` block | next to `WebBrowserActivity` |

Plus the **build-file** changes, which are separate because they are about
the fork's identity rather than the feature:

| # | File | Change |
|---|---|---|
| 5 | `smarttubetv/build.gradle` | `applicationId` → `org.smalltube.stable`; all four ABIs; APK filename `SmallTube_parental-limit_...`; optional `-PexcludeJ2v8=true` |
| 6 | `smarttubetv/src/ststable/res/values/strings.xml` | `app_name` / `browse_title` → `SmallTube` |
| 7 | `common/src/ststable/res/values/update_urls.xml` | emptied (disables the upstream auto-update) |
| 8 | `smarttubetv/google-services.json` | moved to `google-services.json.disabled` (package name is not registered with Firebase) |

And the **string resources** in `common/src/main/res/values{,-zh,-zh-rTW}/strings.xml`
(48 `parental_limit_*` entries each).

### 3.3 The state machine

```
DISABLED ──enable+PIN──▶ READY ⇄ ACTIVE ──quota hit──▶ RESTING ──wall-clock deadline──▶ READY
                            │                            │
                            └── parent PIN ──────────────┴──▶ PARENT_OVERRIDE (one-shot)
```

* **READY** — feature on, nothing in foreground.
* **ACTIVE** — at least one non-lock Activity in foreground; the 500 ms tick
  accumulates time.
* **RESTING** — `restUntilWall` is in the future. The lock Activity is on top,
  the Back key is consumed, playback is paused.
* **PARENT_OVERRIDE** — not a state; a verified PIN action that mutates the
  persisted timer (extend / skip / reset).

### 3.4 Two clocks on purpose

| Quantity | Clock | Why |
|---|---|---|
| Foreground / playback accumulation | `SystemClock.elapsedRealtime()` | monotonic — changing the system clock cannot shorten a session |
| Rest deadline | `System.currentTimeMillis()` (absolute instant) | must survive the app being closed **and the TV being switched off** |

`ParentalLimitController.refreshRestState()` converts an expired rest into a
fresh quota. It is called from **three** places because the ticker stops
while the lock screen holds the foreground:

1. `onActivityStarted()` — "the TV was off, the kid came back"
2. the 500 ms tick — "the rest ended while the app was open"
3. `ParentalLimitActivity.refresh()` — "the lock screen is showing"

> **If you add a third foreground Activity type, re-check that the ticker
> still runs.** A missed `refreshRestState()` call = the user is locked out
> forever.

---

## 4. Syncing with upstream

### 4.1 The strategy

Our branch is **upstream `master` + 9 additive commits**. Sync = *merge*
upstream into our branch.

Why merge rather than rebase or cherry-pick:

* The conflict surface is tiny and fully known (§3.2) — 4 code hooks + 4
  build files + 3 string files.
* Merging keeps history; rebasing rewrites 9 commits on every sync and makes
  the fork harder to reason about.
* Git's 3-way merge handles the string files almost automatically (we only
  *append* to the end of the list).

### 4.2 The workflow

```bash
cd ~/www/SmartTube

# Report only - how many commits behind are we?
./scripts/sync-upstream.sh --check

# Do the merge
./scripts/sync-upstream.sh
```

The script will:

1. Add the `upstream` remote if missing.
2. Fetch and report how many upstream commits we are behind.
3. Merge `upstream/master`.
4. On conflict, print the per-file resolution guide and stop.

Then:

```bash
# Resolve conflicts using §5 below
git add <resolved files>
git commit

# Rebuild + sanity check
./scripts/verify-fork.sh

# Test on the TV (§7)

# Tag a known-good state
git tag fork/v<upstream-version>-parental<N>
git push origin --tags
```

### 4.3 If the merge goes badly wrong

```bash
git merge --abort          # back to where we were
```

Our work is never lost — it is 9 commits on top of upstream and the upstream
side is untouched.

### 4.4 Sync checklist

After every upstream merge, verify:

- [ ] `./scripts/verify-fork.sh` passes (all 5 check groups).
- [ ] `ParentalLimitController` still compiles — it depends on
      `PlaybackPresenter`, `PlaybackView` and `AppDialogPresenter`, all of
      which upstream refactors occasionally.
- [ ] `ParentalLimitActivity` still compiles — it depends on
      `AppDialogPresenter.appendStringsCategory` and `UiOptionItem.from`.
- [ ] The `GeneralSettingsPresenter` hook still lands in a sensible place
      (upstream may have reorganised `appendMiscCategory`).
- [ ] The `MainApplication.onCreate()` hook is still before any Activity
      starts.
- [ ] Upstream did **not** add its own `app_name` / `browse_title`
      overrides that clash with the `SmallTube` rename.
- [ ] Upstream did **not** re-add an `update_urls.xml` item.
- [ ] Smoke-test on the TV (§7).

---

## 5. Per-file resolution guide

Use this when the merge conflicts. In each case: **keep our block, take
theirs everywhere else in the same file.**

### 5.1 `smarttubetv/src/main/java/.../tv/ui/main/MainApplication.java`

Two additions. Ours is near the top of `onCreate()`:

```java
// imports
import com.liskovsoft.smartyoutubetv2.tv.parental.ParentalLimitActivity;
import com.liskovsoft.smartyoutubetv2.common.parental.ParentalLimitController;

// in onCreate(), right after setupViewManager();
        // Initialize the parental time-limit controller before any Activity starts
        // so it can register its ActivityLifecycleCallbacks and gate relaunches
        // while the configured rest period is still active.
        ParentalLimitController.setLockActivityClass(ParentalLimitActivity.class);
        ParentalLimitController.init(this);
```

*If upstream reordered `onCreate()`*, keep the block but move it to just
before the end of `onCreate()` — it must run before any Activity starts.

### 5.2 `common/src/main/java/.../common/misc/AppDataSourceManager.java`

One import and one `SettingsItem`, added right after the *General* entry:

```java
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.ParentalLimitSettingsPresenter;

// ...
        settingItems.add(new SettingsItem(
                context.getString(R.string.parental_limit_settings_title),
                () -> ParentalLimitSettingsPresenter.instance(context).show(),
                R.drawable.settings_app));
```

*If upstream added its own new settings entries*, just add ours next to
them — order does not matter functionally.

### 5.3 `common/src/main/java/.../presenters/settings/GeneralSettingsPresenter.java`

One entry appended to `appendMiscCategory()`:

```java
        // Parental time-limit entry. The sub-presenter handles its own PIN flow,
        // so we just delegate to it.
        options.add(UiOptionItem.from(getContext().getString(R.string.parental_limit_settings_title),
                getContext().getString(R.string.parental_limit_settings_desc),
                optionItem -> ParentalLimitSettingsPresenter.instance(getContext()).show(),
                false));
```

### 5.4 `smarttubetv/src/main/AndroidManifest.xml`

One `<activity>` block, placed next to `WebBrowserActivity`:

```xml
        <!-- Parental time-limit lock screen. Launched by ParentalLimitController
             when the cumulative foreground quota is exhausted. Not exported and
             excluded from recents so it cannot be reopened from the recents UI.
             NOTE: noHistory must stay off - the parent-options dialog opens on
             top of this screen, and noHistory would destroy it, leaving the
             controller's lock flag stuck. -->
        <activity
            android:name="com.liskovsoft.smartyoutubetv2.tv.parental.ParentalLimitActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleTop"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|layoutDirection"
            android:screenOrientation="landscape"
            android:theme="@style/App.Theme.Leanback.Preferences.Opaque"/>
```

⚠️ **`noHistory` must stay off.** The parent-options dialog opens on top of
this Activity; with `noHistory` the lock screen is destroyed underneath and
the controller's lock flag gets stuck.

### 5.5 `smarttubetv/build.gradle`

Three separate edits — check all three survived the merge:

1. **APK filename** (inside `applicationVariants.all`):
   ```java
   def project = "SmallTube"
   def newApkName = sprintf("%s_parental-limit_%s_%s_%s.apk", [project, flavor, version, arch]);
   ```
2. **`ststable` flavor**:
   ```java
   ststable {
       applicationId "org.smalltube.stable"
       targetSdkVersion project.properties.compileSdkVersion
       // Ship every ABI so the APK installs on any Android TV (some sets
       // are x86_64 rather than ARM).
       ndk {
           abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
       }
   }
   ```
   ⚠️ **The `abiFilters` block is not optional.** Upstream inherits
   `abiFilters 'armeabi-v7a', 'arm64-v8a'` from `defaultConfig`, so without
   this the "universal" APK contains no x86 and fails to install on an
   x86_64 TV with `INSTALL_FAILED_NO_MATCHING_ABIS`.
3. **Optional j2v8 exclusion** (inside `android { }`), used to build a
   smaller APK for testing:
   ```java
   if (project.hasProperty('excludeJ2v8') && project.property('excludeJ2v8') == 'true') {
       packagingOptions { jniLibs { exclude '**/libj2v8.so' } }
   }
   ```

### 5.6 `smarttubetv/src/ststable/res/values/strings.xml`

Ours in full — upstream's is just the two SmartTube strings:

```xml
<resources>
    <!-- Parental-limit fork: renamed to SmallTube so it coexists with upstream
         SmartTube on the same device. applicationId is also changed (see
         smarttubetv/build.gradle) so the two installs do not collide. -->
    <string translatable="false" name="app_name"><![CDATA[SmallTube]]></string>
    <string translatable="false" name="browse_title"><![CDATA[SmallTube]]></string>
</resources>
```

### 5.7 `common/src/ststable/res/values/update_urls.xml`

Keep ours (empty). Upstream's points at the official release channel and
would download and install the **original** SmartTube APK over this fork.

```xml
<resources>
    <!-- ... disables the upstream auto-update ... -->
    <string-array name="update_urls">
    </string-array>
</resources>
```

### 5.8 `common/src/main/res/values*/strings.xml`

We only ever **append** `parental_limit_*` entries at the end of the file.
On conflict, take upstream's file and re-append our block. The block is
contiguous and alphabetically named, so it is easy to re-locate:

```
common/src/main/res/values/strings.xml        → 48 parental_limit_* entries
common/src/main/res/values-zh/strings.xml      → 48 (Simplified Chinese)
common/src/main/res/values-zh-rTW/strings.xml   → 48 (Traditional Chinese)
```

The three files must stay in sync — `verify-fork.sh` checks this.

### 5.9 `smarttubetv/google-services.json`

We moved it to `google-services.json.disabled`. If upstream touches it,
leave ours disabled — the Firebase project is not registered for the
`org.smalltube.stable` package, so applying the Google Services plugin
fails the build with *"No matching client found for package name
'org.smalltube.stable'"*.

---

## 6. Building

### 6.1 One-time setup

```bash
./scripts/setup-build-env.sh
```

That script installs and pins everything:

* **mise** + **Temurin 17.0.21+9** (pinned in `.mise.toml`).
  *Why not JDK 14* (what the upstream README asks for): JDK 14 was never
  published for arm64 macOS, and the upstream CI already builds with JDK 17.
  `sourceCompatibility` is still 1.8, so the produced bytecode is unchanged.
* **Android SDK** platform 34 + build-tools 34.0.0 + platform-tools.
* **The protoc/Rosetta workaround** — see §8.1. Without it the build dies.

Activate the JDK in your shell:

```bash
eval "$(mise activate zsh)"    # add to ~/.zshrc
```

### 6.2 Building

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :smarttubetv:assembleStstableRelease
```

Output:

```
smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_<ver>_universal.apk
```

Variants: `stbeta` / `ststable` / `stfdroid`. Only `ststable` carries the
`SmallTube` rename and the emptied `update_urls.xml`; if you use another
flavor you must port those changes.

For a **smaller test APK** (drops the 32 MB j2v8 JS engine, only used by
the built-in web browser):

```bash
./gradlew :smarttubetv:assembleStstableRelease -PexcludeJ2v8=true
```

### 6.3 Verifying

```bash
./scripts/verify-fork.sh
```

Checks the build, the package identity, the ABIs, that the upstream update
manifest is still disabled, that the strings are complete in all three
languages, and that the lock Activity is registered without `noHistory`.

### 6.4 Signing

The fork uses a personal keystore:

```
keystore.properties        → git-ignored, points at release.keystore
release.keystore           → git-ignored
```

`keystore.properties`:

```properties
storeFile=../release.keystore
storePassword=<password>
keyAlias=smarttube-parental
keyPassword=<password>
```

The `storeFile` path is relative to the **`smarttubetv/`** module directory,
so `../release.keystore` points at the repo root. Getting this wrong gives
*"Keystore file '.../smarttubetv/smarttubetv/release.keystore' not found"*.

Rotate the password with:

```bash
keytool -storepasswd -keystore release.keystore
```

---

## 7. Testing

### 7.1 On the TV

Install with ADB (preferred) or a FAT32 USB stick:

```bash
adb install -r smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_<ver>_universal.apk
```

> The APK must contain **all four ABIs** or it will not install on an
> x86_64 TV.

### 7.2 Acceptance tests

See `PARENTAL_LIMIT.md §8` for the full table (19 scenarios). The ones that
catch regressions most often:

| # | Scenario | Why it matters |
|---|---|---|
| 16 | Lock → switch the TV **off** → wait past the rest → turn it on | the wall-clock rest expiry |
| 17 | Lock → wait past the rest **without** switching off | `refreshRestState()` must fire from the lock screen's own timer |
| 9 | 60 s before the limit | the warning toast |
| 10 | Lock while a video plays | playback must actually stop |
| 1 (menu) | Parent options → select an item | the menu must be D-pad usable |
| 13–15 | *Time actually watching* mode | the second counting mode |

### 7.3 Suggested test values

For a quick functional test set **watching time = 15 min** and **rest =
15 min**, then switch to the real values once everything works.

---

## 8. Traps we have already hit

### 8.1 protoc is x86_64 despite its name

`protobuf-gradle-plugin` downloads
`protoc-3.17.3-osx-aarch_64.exe` from Maven Central — but that file is
**actually an x86_64 binary**. On Apple Silicon the build dies with:

```
Cannot run program ".../protoc-3.17.3-osx-aarch_64.exe":
error=86, Bad CPU type in executable
```

**Fix** (already automated in `setup-build-env.sh`): install Rosetta 2 and
replace the cached binary with a wrapper:

```bash
cd ~/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/3.17.3/<hash>/
mv protoc-3.17.3-osx-aarch_64.exe protoc.real.bin
cat > protoc-3.17.3-osx-aarch_64.exe <<'WRAPPER'
#!/bin/bash
exec arch -x86_64 "$(dirname "$0")/protoc.real.bin" "$@"
WRAPPER
chmod +x protoc-3.17.3-osx-aarch_64.exe
```

Do **not** try to "fix" this by copying Homebrew's `protoc` over it — the
Homebrew build is a different version and dynamically links
`libprotoc.36.2.0.dylib`, which is not next to the cached binary.

### 8.2 The "universal" APK was not universal

Upstream's `defaultConfig` sets `abiFilters 'armeabi-v7a', 'arm64-v8a'`,
and the `ststable` flavor inherited it. The resulting "universal" APK had no
x86 at all, so it failed to install on an x86_64 TV. The symptom was a
silent *"安装失败"* with no log — and it looked like a signing problem, a
filesystem problem and a size problem before the real cause turned up.

**Lesson:** when an APK will not install, get the actual error code
(`adb install` or `adb logcat PackageManager:E`). Guessing wasted hours.

### 8.3 Framework `AlertDialog` is unusable on leanback themes

The parent-options menu was first built with `android.app.AlertDialog`.
Under the leanback theme its list items render **greyed out and
unselectable with a D-pad**. Use the app's own
`AppDialogPresenter` + `appendStringsCategory` instead — that is what every
other menu in SmartTube uses.

### 8.4 `noHistory` on the lock screen

`android:noHistory="true"` destroys the Activity as soon as another
Activity covers it. The parent-options dialog covers the lock screen →
the lock Activity is destroyed → the controller's lock flag is never
cleared → the lock can never be shown again.

### 8.5 The quota must be reset when the rest expires

`beginRestAndLock()` leaves `usedMs == limit`. If the rest expiry does not
clear it, the next tick sees `used >= limit` and locks again — an infinite
lock loop, i.e. the kid can never get back in. This is exactly the
"switch the TV off and come back later" case the feature exists for.

### 8.6 `SystemClock.elapsedRealtime()` vs `System.currentTimeMillis()`

Both are needed. Using wall-clock for the *session* timer would let a kid
move the TV clock forward and gain time instantly. Using elapsed-realtime
for the *rest* deadline would reset the rest whenever the app (or the TV)
restarted.

### 8.7 The upstream update channel points at the original app

`common/src/ststable/res/values/update_urls.xml` contains a URL to the
official release manifest. Left alone, the app downloads and installs the
**original** SmartTube APK. It is now empty.

### 8.8 Port-scanning an Android TV can get your IP blocked

Scanning a few thousand ports on the TV made every subsequent connection
fail with `No route to host` / `Host is down` — firewall behaviour, not
closed ports. Stop scanning; read the port off the TV screen instead.

### 8.9 `adb pair` codes expire in minutes

A stale pairing code fails with
`protocol fault (couldn't read status message)`. Open the
*"Pair device with pairing code"* dialog **immediately** before pairing.
`scripts/adb_pair_retry.py` retries for 5 minutes so the timing is not
critical.

---

## 9. Quick reference

```bash
# environment
eval "$(mise activate zsh)"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# build
./gradlew :smarttubetv:assembleStstableRelease
./gradlew :smarttubetv:assembleStstableRelease -PexcludeJ2v8=true   # smaller test APK

# verify
./scripts/verify-fork.sh

# upstream sync
./scripts/sync-upstream.sh --check     # report only
./scripts/sync-upstream.sh             # merge
git merge --abort                      # bail out

# install / test
adb connect <TV_IP>:5555
adb install -r smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_*_universal.apk

# git
git log --oneline bbed8fbbf..HEAD     # our fork commits
git diff --stat $(git merge-base HEAD upstream/master)..HEAD
```

---

## 10. Decision log

| Decision | Rationale |
|---|---|
| Rename to `SmallTube` / `org.smalltube.stable` | So the patched build installs **side by side** with upstream SmartTube. The user wanted to validate the feature before removing the original. |
| Ship all four ABIs | The TV is x86_64. Upstream's "universal" is ARM-only. |
| Empty `update_urls.xml` | Otherwise the app installs the original SmartTube over the fork. |
| Disable `google-services.json` | The Firebase project is not registered for the new package name. |
| Controller in `common`, lock Activity in `smarttubetv` | Avoids a circular module dependency; the Activity is injected via `setLockActivityClass()`. |
| Two clocks (monotonic + wall-clock) | Monotonic for the session (immune to clock changes), wall-clock for the rest (must survive the TV being off). |
| PIN stored as salted SHA-256, not PBKDF2 | The threat is a kid reading the file, not brute force. Zero new crypto dependencies. |
| Settings entry at the top level of App Settings | Nested under *General → Other player settings* it was effectively undiscoverable with a TV remote. |
