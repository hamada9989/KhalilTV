# Android Debugging: Replicating AerioTV iOS's Debug Logging

Reference for getting the AerioTV **Android** app to emit the same detailed
diagnostic logging you see when you run the iOS app from Xcode with **CMD+R on
the Debug scheme**. It documents exactly what the iOS build logs, where, and how,
then maps each piece to an Android equivalent.

This doc lives in the iOS repo for convenience; copy it into the Android project
when you act on it.

---

## TL;DR: what shows up in the Xcode console on CMD+R (Debug)

Four streams land in the Xcode console, interleaved:

1. **`debugLog()` firehose** - the bulk of it. A `#if DEBUG`-only `print()`
   wrapper, ~657 call sites, tagged with emoji/bracket prefixes per subsystem.
   Compiles to **nothing** in Release.
2. **libmpv log messages** - the player core's own logs, requested at the
   `warn` level in Debug (`error` in Release).
3. **`NSLog` traces** - a small set of always-on refcount logs (audio session,
   idle timer / wake lock).
4. **System / framework logs** - URLSession, os_log from Apple frameworks, etc.

Two more streams are collected but **not** in the Xcode console:

5. **`AttemptLogStore`** - an on-screen, in-app diagnostic log (the player's
   stats/attempt panel).
6. **`DebugLogger.shared`** - a runtime-toggled, credential-sanitized,
   file-backed logger written to disk and shareable from the app.

Android equivalent in one line: **Timber (gated on `BuildConfig.DEBUG`) +
per-subsystem tags + the libmpv log observer + an OkHttp interceptor + a toggled
file logger with sanitization + an in-app log panel**, all read through
`adb logcat` and/or exported to a file.

---

## 1. `debugLog()` - the Debug-only console firehose

`App/DebugLogger.swift`:

```swift
@inline(__always)
func debugLog(_ message: @autoclosure () -> String) {
    #if DEBUG
    let line = message()
    _debugConsoleQueue.async { print(line) }   // serial .utility queue
    #endif
}
```

Key properties:

- **Debug-only.** Wrapped in `#if DEBUG`, so in Release every call site compiles
  to nothing (zero cost, no leak). This is why the console is rich in Debug and
  silent in Release.
- **Built synchronously, written asynchronously** on a dedicated serial
  `.utility` queue. This matters: a synchronous `print()` blocks the caller when
  the console pipe backs up. On mpv's event-drain and render threads that
  backpressure stalls the mpv core (its bounded event queue fills, the demuxer
  stops reading) and produces a Debug-only "first frame then freeze" that does
  not exist in Release. Deferring only the write keeps the mpv threads
  non-blocking; the serial queue preserves ordering.
- **Output target:** the Xcode console (stdout) via `print`. Nothing else.

### The marker convention

Each line is prefixed with a subsystem marker (an emoji or a `[BRACKET]` tag) so
you can eyeball-filter the firehose. A representative sample (by frequency):

```
TV emoji        channel / EPG / Dispatcharr data load
[MPV-DIAG]      libmpv diagnostics (avsync, frame timing, decode)
[MPV-PIP]       picture-in-picture engage/teardown
[MPV-ERR]       libmpv errors
[MPV-BG]        backgrounding / foregrounding of the player
game-controller emoji   multiview D-pad commands ([MV-Cmd])
blue-diamond emoji      channel store / fetchDispatcharr
green-circle emoji      playback running / state transitions
NWHTTP          raw network HTTP request/response
[VOD-Episodes]  VOD series episode loading
lock emoji      auth / credential plumbing
compass emoji   guide focus / navigation ([GuideFocus])
link emoji      stream URL resolution
clapper emoji   playback start
```

The point is the **convention**, not the exact glyphs: one stable prefix per
subsystem so a noisy log is greppable.

### Android equivalent

- Use **Timber** (or raw `android.util.Log`) gated on `BuildConfig.DEBUG`:

  ```kotlin
  // Application.onCreate()
  if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
  ```

  A `debugLog("...")` helper that no-ops in release mirrors the iOS `#if DEBUG`
  exactly:

  ```kotlin
  inline fun debugLog(tag: String, message: () -> String) {
      if (BuildConfig.DEBUG) Log.d(tag, message())   // lambda is not evaluated in release
  }
  ```

- **Map the iOS markers to Logcat tags.** Logcat already filters by `TAG`, so use
  a stable tag per subsystem (`MPV-DIAG`, `MV-Cmd`, `Channels`, `Net`, `VOD`,
  `Auth`, `GuideFocus`, ...). That gives you the same eyeball-filtering, plus
  `adb logcat -s MPV-DIAG:* Net:*`.
- **Off-thread writes:** `android.util.Log` is already non-blocking enough for
  most cases, but if you log heavily from the mpv render/event thread, route
  through a single-threaded handler/dispatcher the way iOS uses
  `_debugConsoleQueue`, to avoid perturbing mpv timing.

---

## 2. The libmpv log bridge

`App/MPVPlayerView.swift`:

```swift
#if DEBUG
checkError(mpv_request_log_messages(mpv, "warn"))
#else
checkError(mpv_request_log_messages(mpv, "error"))
#endif
```

and in the event loop:

```swift
case MPV_EVENT_LOG_MESSAGE:
    if let msg = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(event.pointee.data)) {
        // msg.pointee.prefix / .level / .text  ->  filtered, then debugLog'd + appended to logStore
    }
```

- **Debug requests `warn`+**, Release requests `error`+. So Debug surfaces mpv's
  warnings (decoder fallbacks, demuxer retries, A/V desync notes) that Release
  hides.
- Each `MPV_EVENT_LOG_MESSAGE` carries `prefix` (mpv module, e.g. `vd`, `ao`,
  `cplayer`), `level`, and `text`. iOS noise-filters these, then routes them to
  both the console (`debugLog`) and the on-screen `AttemptLogStore`.

### Android equivalent

libmpv is the **same C library** on Android, so the API is identical:

- Call `mpv_request_log_messages(handle, "warn")` (debug) / `"error"` (release).
  If you use `mpv-android` / `MPVLib`, it exposes this and a log-message
  observer; otherwise call libmpv directly through your JNI bridge.
- In your event/observer loop, handle `MPV_EVENT_LOG_MESSAGE` and forward
  `prefix + level + text` to `Log` with a `MPV` tag (mirror the iOS noise filter
  so you do not drown in `v`/`debug` spam if you raise the level).
- mpv log levels you can request: `no, fatal, error, warn, info, status, v,
  debug, trace`. `warn` matches iOS Debug; bump to `v` or `debug` when chasing a
  specific decode/demux issue.

---

## 3. `NSLog` - always-on subsystem traces

A few subsystems use `NSLog` instead of `debugLog`, which means they print in
**both** Debug and Release (NSLog is not compiled out):

```
Shared/AudioSessionRefCount.swift   [MV-Audio]   audio-session activate/deactivate refcount
Shared/IdleTimerRefCount.swift      [IdleTimer]  screen-idle / wake-lock refcount
```

These trace shared-resource refcounts (audio session, idle timer) where an
over-decrement or leak is a real bug, so they are deliberately always-on.

### Android equivalent

The Android port has the analogous shared resources - audio focus and the
`PARTIAL_WAKE_LOCK` used during recording / keep-awake. Add `Log.i` refcount
traces on the audio-focus request/abandon and the wake-lock acquire/release with
`MV-Audio` / `WakeLock` tags. Keep them on in release too (they are cheap and
catch refcount leaks).

---

## 4. System / framework logs

In the Xcode console you also get Apple framework logs for free: URLSession
errors, os_log from AVFoundation / CoreMedia, memory warnings, etc.

### Android equivalent

- **HTTP:** add an OkHttp `HttpLoggingInterceptor` at `BODY` level in debug,
  `NONE` (or `BASIC`) in release. This is the closest match to the iOS `NWHTTP`
  lines plus `DebugLogger.logNetwork`.
- **Media:** ExoPlayer is not in play here (you use libmpv), so the media logs
  come from the libmpv bridge in section 2. System Logcat tags
  (`AudioTrack`, `MediaCodec`, `OMX`, `CCodec`) are gold for the
  hardware-decode issues noted in the Android memory (e.g. HEVC-in-TS).

---

## 5. `AttemptLogStore` - the on-screen diagnostic log

`App/PlayerView.swift`:

```swift
final class AttemptLogStore: ObservableObject, @unchecked Sendable {
    @Published var lines: [String] = []
}
```

- A small, in-memory, **on-screen** log shown in the player's stats/attempt
  panel. The Coordinator and PlayerView append short human-readable lines as
  playback progresses:

  ```
  logStore.append("Player: MPV (libmpv)")        // real lines carry a short status glyph
  logStore.append("MPV: swap stream -> <title>")
  logStore.append("MPV: unpause live -> snap to live edge")
  ```

- Purpose: let a user (or you, on device, with no Xcode attached) watch the
  playback state machine live - codec, hwdec path, stream swaps, live-edge snaps,
  decode errors - without a cable.

### Android equivalent

A `StateFlow<List<String>>` (or a bounded ring buffer) in the player
view-model, rendered in a debug overlay / bottom sheet you can toggle. Append
the same milestones (player init, hwdec decision, stream swap, EOF, errors). This
is how you debug on a physical TV / phone with only `adb` for a cable.

---

## 6. `DebugLogger.shared` - the file-backed, toggled, sanitized logger

This is the **persistent** log (separate from the Debug-only console firehose).
`App/DebugLogger.swift`, `DebugLogger.shared`:

- **Where:** a file at `Documents/aerio_debug_logs.txt`, exposed in the iOS
  Files app (On My iPhone > Aerio) via `UIFileSharingEnabled`, so a user can
  attach it to a GitHub issue.
- **Runtime-toggled, not Debug-only:** gated on
  `UserDefaults["debugLoggingEnabled"]` (a Settings > Developer switch). Off by
  default; every method early-returns when disabled. This is what you ask a
  remote user to flip on before reproducing a bug.
- **Categories:** `App, Error, Network, Playback, EPG, Channels, Lifecycle,
  Performance, Decode`.
- **Levels:** `DEBUG, INFO, WARN, ERROR, CRITICAL, NET, PERF, LIFECYCLE` (each
  with an icon for scanability).
- **Specialized entry points** (each pre-formats + sanitizes):
  `log`, `logError`, `logNetwork(method/url/status/duration/bytes)`,
  `logPlayback(event/url/detail)`, `logEPG(event/channelID/count/duration)`,
  `logChannelLoad(serverType/count/duration)`, `logLifecycle`,
  `logPerformance(operation/duration)`, `logDecodeError(type/error/payload)`.
- **Credential sanitization (port this - it is a security control).** Every
  message is run through `DebugLogger.sanitize()` before hitting disk. Regexes
  redact, in order:
  - Xtream path creds `/(movie|series|live)/<user>/<pass>/` (keeps `<user>`,
    redacts the password slot)
  - Xtream query creds `?username=...&password=...`
  - `api_key=...` query params
  - `Authorization` / `X-API-Key` / `X-Plex-Token` header values
  - Emby `Token="..."`
  - JWT `"access"/"refresh": "<jwt>"` in JSON bodies
- **Rotation:** at 10 MB the file is moved to `aerio_debug_logs_archive.txt` and
  a fresh file started.
- **Session header:** on enable, writes a banner with app version+build, device
  model, OS version, and resident memory.
- **Line format:**
  `[yyyy-MM-dd HH:mm:ss.SSS] <icon> [LEVEL    ] [Category] <message>  - file:line`

Note: `DebugLogger.shared` writes to the **file only** (not the console). The
console firehose is `debugLog()`. They are two systems; the file logger is the
one a user can hand you.

### Android equivalent

- A `Timber.Tree` that writes to a file in app-private storage, or a small custom
  logger. Mirror the **categories/levels**, the **timestamped line format**, the
  **10 MB rotation**, and the **session header** (BuildConfig version, `Build.MODEL`,
  `Build.VERSION.RELEASE`, `Debug.getMemoryInfo` / `Runtime` memory).
- **Port the sanitizer regexes verbatim** - same auth surfaces (Xtream
  path/query, api_key, Authorization/X-API-Key, JWT). This keeps credentials out
  of a log a user emails you.
- Gate it on a Settings toggle persisted in DataStore (the Android analog of the
  `debugLoggingEnabled` UserDefaults flag).
- Export via a `Share` intent (FileProvider) so a user can attach it to a GitHub
  issue, the same flow as the iOS Files-app handoff.

---

## How to view, side by side

| iOS (Debug, CMD+R)                         | Android                                                        |
|--------------------------------------------|----------------------------------------------------------------|
| Xcode console = `debugLog` + mpv + NSLog + system | `adb logcat` (filter to the app pid + tags)             |
| In-app stats panel = `AttemptLogStore`     | In-app debug overlay backed by a `StateFlow`                   |
| Files app file = `DebugLogger.shared`      | App-private file, exported via a Share intent                  |

### adb logcat recipes

```bash
# Only this app's logs (replace with your applicationId)
adb logcat --pid=$(adb shell pidof -s com.aeriotv.android)

# Only specific subsystems (tags), everything else silenced
adb logcat -s MPV-DIAG:V MV-Cmd:V Channels:V Net:V Auth:V

# Clear, then capture a reproduction to a file
adb logcat -c && adb logcat --pid=$(adb shell pidof -s com.aeriotv.android) > repro.log

# Include the hardware-decode system tags when chasing codec issues
adb logcat -s MPV-DIAG:V c2.qti:* MediaCodec:* CCodec:* AudioTrack:*
```

---

## Component-by-component mapping

| iOS piece                       | What it does                              | Android equivalent                                         |
|---------------------------------|-------------------------------------------|------------------------------------------------------------|
| `debugLog()` `#if DEBUG`        | console firehose, compiled out in release | `Timber.DebugTree` / `Log.d` gated on `BuildConfig.DEBUG`   |
| emoji / `[BRACKET]` markers     | per-subsystem prefixes                    | Logcat `TAG` per subsystem                                 |
| `_debugConsoleQueue` async      | keep mpv threads non-blocking             | log off the mpv thread via a single dispatcher if heavy    |
| `mpv_request_log_messages`      | mpv core logs at `warn` (debug)           | same libmpv call + `MPV_EVENT_LOG_MESSAGE` observer        |
| `NSLog` refcount traces         | always-on audio/idle refcounts            | `Log.i` on audio-focus + wake-lock acquire/release         |
| URLSession / framework logs     | free in the console                       | OkHttp `HttpLoggingInterceptor` + system Logcat tags       |
| `AttemptLogStore`               | on-screen live diagnostic panel           | `StateFlow<List<String>>` debug overlay                    |
| `DebugLogger.shared`            | toggled, sanitized, rotated file log      | file-writing `Timber.Tree` + DataStore toggle + Share      |
| `DebugLogger.sanitize()`        | redact creds before disk                  | port the regexes verbatim (security)                       |

---

## Practical recipe to match the iOS detail on Android

1. **Plant a debug Timber tree** + a `debugLog` helper that no-ops in release.
   Tag each subsystem to mirror the iOS markers.
2. **Wire the libmpv log observer** at `warn` (debug) / `error` (release) and
   forward `prefix/level/text` to a `MPV` tag. Bump to `debug`/`v` when chasing a
   decode or demux bug.
3. **Add an OkHttp logging interceptor** (`BODY` in debug) for the network layer.
4. **Refcount-trace** audio focus and the wake lock (always on).
5. **Add the on-screen debug overlay** backed by a `StateFlow` for cable-free,
   on-device debugging (mirrors `AttemptLogStore`).
6. **Add the toggled file logger** with the same categories, levels, line
   format, rotation, session header, and **the ported credential sanitizer**;
   export it with a Share intent.
7. **Read it** with the `adb logcat` recipes above; hand users the exported file.

Doing 1, 2, and 5 gets you ~90% of the live "CMD+R Debug" experience; adding
6 gets you the user-shareable log.
