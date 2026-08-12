# InappUpdate — Google Play In-App Update for Godot 4

A thin wrapper around the [Play In-App Update API](https://developer.android.com/guide/playcore/in-app-updates)
that lets you offer or force an app update **from inside the game**, without sending
the player to the Play Store manually.

> 📱 **Android only.** This plugin works exclusively on Android with Google Play
> Services. It relies on the Google Play In-App Update API, which does not exist on
> iOS, desktop, web, or Android devices without Google Play (Huawei, etc.). On any
> non-Android platform the singleton is simply absent and every call is a safe no-op,
> so you can leave the code in place for cross-platform projects.

Godot: **4.6** · Platform: **Android only**.

> ⚠️ This updates the **whole application** (APK/AAB) through Google Play only.
> It cannot hot-swap native code (`.so` / GDExtension): the linker loads native
> libraries only from the installed APK, and Play forbids downloading executable
> code at runtime. Any C++/GDExtension change therefore means a new Play release
> plus this plugin. PCK patches remain an option for assets/scripts only.

---

## Description

The plugin ships a native Android library (AAR) plus a GDScript node wrapper. It
exposes the two update flows Google Play supports:

| Mode | Behaviour | When to use |
|---|---|---|
| **Flexible** | Downloads in the background, the player keeps playing. Installed and restarted on demand. | Regular updates. Non-intrusive. |
| **Immediate** | Full-screen blocking Play UI; Play handles download and restart itself. | Critical updates (broken save format, mandatory hotfix). Usually `updatePriority` 4–5. |

Enable it via **Project → Project Settings → Plugins → InappUpdate → Enable**.

---

## Methods

### Action methods
| Method | What it does |
|---|---|
| `check_for_update()` | Ask Play whether an update exists (async → signals). |
| `start_flexible_update()` | Start a background download. |
| `start_immediate_update()` | Start a blocking update. |
| `start_recommended_update() -> UpdateType` | Start whichever type `recommended_update_type()` returns. |
| `complete_flexible_update()` | Install the downloaded flexible update and restart the app. |
| `is_available() -> bool` | Whether the native singleton is present (true only on Android with the AAR). |

### Query methods (decide the update type from code)
Valid **after** a successful `check_for_update()` (they read the cached
`AppUpdateInfo`), so you can decide flexible/immediate **without** waiting inside a
signal handler.

| Method | Returns |
|---|---|
| `is_update_available() -> bool` | Whether an update exists. |
| `is_flexible_allowed() -> bool` | Whether flexible is allowed for this update. |
| `is_immediate_allowed() -> bool` | Whether immediate is allowed. |
| `get_update_priority() -> int` | Priority 0–5 (0 without the Play Developer API). |
| `get_available_version_code() -> int` | versionCode of the available update (-1 if none). |
| `get_client_version_staleness_days() -> int` | Days the update has been available on the device (-1 if unknown). |
| `get_install_status() -> int` | Raw `InstallStatus`. |
| `recommended_update_type() -> UpdateType` | `NONE` / `FLEXIBLE` / `IMMEDIATE` per the rules below. |

**Enum `InappUpdate.UpdateType`:** `NONE`, `FLEXIBLE`, `IMMEDIATE`.

`recommended_update_type()` escalates to **IMMEDIATE** when
`priority >= immediate_priority_threshold` (default 4) **or**
`staleness >= immediate_staleness_days` (default 14, `-1` disables it) and immediate
is allowed; otherwise **FLEXIBLE** if allowed; otherwise **NONE**. The thresholds are
node fields:

```gdscript
updater.immediate_priority_threshold = 5   # only priority 5 is mandatory
updater.immediate_staleness_days = -1      # never escalate by "age"
```

### Signals
| Signal | Meaning |
|---|---|
| `update_available(version_code: int, priority: int)` | An update exists. `priority` 0–5 from the Play Console. |
| `update_not_available()` | No update. |
| `update_check_failed(reason: String)` | The check failed (no network / not installed from Play / etc.). |
| `update_download_progress(bytes_downloaded, total_bytes)` | Flexible download progress. |
| `update_downloaded()` | Flexible download finished → call `complete_flexible_update()`. |
| `update_flow_canceled()` | The player dismissed the update dialog. |
| `update_flow_failed(reason: String)` | The flow/install failed. |
| `install_status_changed(status: int)` | Raw Play `InstallStatus` (constants in `InappUpdate.gd`). |

---

## Usage example

1. Enable the plugin: **Project → Project Settings → Plugins → InappUpdate → Enable**.
2. Add an `InappUpdate` node to an autoload (or create it with `InappUpdate.new()`).

```gdscript
extends Node

@onready var updater := InappUpdate.new()

func _ready() -> void:
    add_child(updater)
    updater.update_available.connect(_on_available)
    updater.update_downloaded.connect(_on_downloaded)
    updater.update_flow_failed.connect(func(r): push_warning("update fail: %s" % r))
    updater.check_for_update()

func _on_available(_version_code: int, _priority: int) -> void:
    # Decide the type straight from code, no branching in the handler:
    match updater.recommended_update_type():
        InappUpdate.UpdateType.IMMEDIATE:
            updater.start_immediate_update()
        InappUpdate.UpdateType.FLEXIBLE:
            updater.start_flexible_update()
    # or in one line:  updater.start_recommended_update()

func _on_downloaded() -> void:
    # show "Update ready, restart?" and on confirmation:
    updater.complete_flexible_update()
```

You can also decide the type **outside** the signal handler — e.g. to defer the
decision to your own moment in the UI:

```gdscript
if updater.is_update_available():
    if updater.is_immediate_allowed() and updater.get_update_priority() >= 4:
        updater.start_immediate_update()
    elif updater.is_flexible_allowed():
        show_soft_update_banner(updater.get_available_version_code())
```

The plugin drives the immediate flow and the post-download restart itself,
including resuming an interrupted immediate update when the app returns to the
foreground (handled in `onMainResume`).

### Platform prerequisites (required)

The plugin only works when the platform conditions are met:

1. **The app is published on Google Play** (at least on a closed/internal track)
   under the same `applicationId` and signed with the **same key** as the installed
   build. A build "out of thin air" (adb/Godot deploy) always reports "no update".
2. **The Play Core dependency is pulled in automatically** —
   `com.google.android.play:app-update(-ktx):2.1.0` is declared both in the native
   `build.gradle` and in `_get_android_dependencies()`. No extra project wiring needed.
3. **Internet connection** — the API talks to Play. No extra permission required
   (Play Services provides it).
4. **`versionCode` increases monotonically** between releases (otherwise Play does
   not see a "newer" build).
5. **For immediate/priority logic:** the update priority (`updatePriority`, 0–5) is
   set at release time only through the **Google Play Developer API**
   (`Edits.tracks.releases[].inAppUpdatePriority`) — there is no field for it in the
   Console web UI. Without it `priority` is always 0, so decide flexible/immediate by
   your own logic (e.g. the `version_code` gap).
6. **Test access:** the device account is added to testers of the relevant track
   (Internal testing / Internal app sharing).

Nothing else in the app manifest, permissions, or `google-services.json` needs
editing — the discovery `meta-data` lives inside the AAR.

---

## Building the AAR yourself

You don't need to build anything to use the plugin — prebuilt AARs are committed
under `addons/inapp_update/bin/`. Build only when you change the native Kotlin code.

**Requirements:** JDK 17, Android SDK, internet access for Maven.

The Gradle wrapper (`gradlew`, `gradle/`) is copied from the Godot `android/build/`
template (Gradle 8.13). `local.properties` with `sdk.dir` is created automatically
on the first build, or set it manually:

```bash
cd addons/inapp_update/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # if missing
./gradlew assembleRelease assembleDebug
```

Copy the built AARs into `bin/`:

```bash
cp build/outputs/aar/InappUpdatePlugin-release.aar ../bin/release/
cp build/outputs/aar/InappUpdatePlugin-debug.aar   ../bin/debug/
```

The file names must match the paths in
`InappUpdatePlugin.gd → _get_android_libraries()`.

> **Kotlin version.** The `org.jetbrains.kotlin.android` plugin in `build.gradle`
> must be able to read the `godot-*-api.jar` metadata. For Godot 4.6 that jar is
> built with Kotlin 2.1.0, so the Kotlin plugin must be **2.1.0** too (1.9.x fails
> with `incompatible version of Kotlin ... metadata is 2.1.0`). Re-check this when
> you change the Godot version.

> `godotVersion` in `build.gradle` must match the Godot version you export with
> (currently `4.6.0.stable`). The `org.godotengine:godot` artifact is pulled from
> Maven Central.

---

## Testing

In-App Update **cannot** be verified on a debug APK installed via `adb install` or
deployed straight from Godot — Play does not know about such a build. Use one of the
following:

### Option A — Internal App Sharing (fastest)
1. Build and **sign with the release key** an AAB (`export_presets.cfg → Android
   release`, `export_format=AAB`).
2. Play Console → **Internal app sharing** → upload the AAB with a **higher
   `versionCode`** than the version installed on your device.
3. Install the **previous** version on the device (from the same Internal App Sharing
   or Internal testing).
4. Open the game → `check_for_update()` should return `update_available`.

### Option B — Internal testing track
1. Upload version N to the **Internal testing** track and install it on the device via
   the tester link.
2. Upload version N+1 to the same track.
3. Launch the installed version N → an update is offered.

Handy notes:
- `versionCode` is driven by your export setup — a new build must have a higher code.
- The device account must be in the track's tester list.
- Watch `install_status_changed` and logcat tag `InappUpdatePlugin` while debugging.
- Immediate priority (`updatePriority` 0–5) is set **only through the Play Developer
  API** at publish time (not in the Console UI) — see the prerequisites above.

---

## Limitations / notes

- Requires an Android device with Play Services; on devices without Google Play
  (Huawei, etc.) it is unavailable and `check_for_update` returns a failure.
- A downloaded flexible update stays available for ~a few days; if the player does
  not install it, Play may clear it and a re-download starts from scratch.
- The plugin keeps no state between game launches — call `check_for_update()` again
  after each restart.
- Native `.so`/GDExtension code is updated **only** by this full app update; a PCK
  cannot replace it.

---

## License

MIT — see [LICENSE](LICENSE).
