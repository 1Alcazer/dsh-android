# Architecture

## Shape of the app

```
┌─────────────────────────────────────────────────────────────┐
│ Java shell (single app process)                             │
│                                                             │
│  MainActivity ── WebView ── http://127.0.0.1:<port>/?token=… │
│       │                                                     │
│       ├── Engine (singleton, one worker thread)             │
│       │      ├── extracts assets/dsh-engine.tar.gz          │
│       │      ├── prepares symlinks and environment          │
│       │      └── spawns libnode.so → DSH web server         │
│       │                                                     │
│       └── EngineService (foreground, wake lock)             │
│                                                             │
│  SettingsActivity · LogsActivity · AboutActivity            │
└─────────────────────────────────────────────────────────────┘
                          │ child process
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Bundled runtime (APK jniLibs/arm64-v8a)                     │
│  libnode.so (Node 26.13.1) + ICU, OpenSSL, SQLite, c-ares…  │
│  libbash.so (GNU bash 5.3.20) + readline, ncurses, iconv    │
│  libgit.so (Git 2.56.1) + libcurl/nghttp2/nghttp3/ngtcp2…   │
└─────────────────────────────────────────────────────────────┘
                          │ extracted to filesDir/engine
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Official @deepseek-ai/dsh production tree (pnpm layout)     │
│  node_modules/.pnpm/…  web profile, plugins, tools          │
└─────────────────────────────────────────────────────────────┘
```

Everything runs on the phone. The only network traffic is what DSH itself
makes: model APIs, package/plugin downloads you request, and git remotes.

## Why this design

DSH is a Node application. Android does not ship Node, and Android's app-data
directories are not usable as an executable location on modern target SDKs
(W^X). Shipping a full Linux userland or an emulator would be large, slow, and
fragile. Instead the APK carries a small, pinned Android-native runtime in the
one place Android guarantees executable code — `nativeLibraryDir` — and exposes
it to the engine through symlinks.

The trade-offs are deliberate:

- **No proot/chroot.** The engine runs as the app UID against Android's libc,
  not against a fake root filesystem.
- **No from-source DSH build.** The published production tree is what desktop
  users run, so plugin and tool behaviour matches upstream.
- **No Gradle.** A four-stage pipeline (`aapt2`, `javac`, `d8`, packaging)
  keeps the build small and inspectable and works in Termux.

## Boot sequence

1. `MainActivity` starts the foreground `EngineService` and registers as an
   `Engine.Listener`.
2. `Engine.ensureEngine()` compares the payload SHA-256 from
   `assets/dsh-engine.manifest.json` with the marker
   `filesDir/engine/.payload-sha256`. On a mismatch it deletes the extracted
   tree, checks free space, and streams the asset into
   `/system/bin/tar -xzf - -C filesDir/engine`.
3. `ProviderPresets.ensureProfile()` creates `filesDir/dsh-home/profiles/web`
   once. The patch file is never rewritten after that.
4. `Engine.launch()` prepares `filesDir/bin` symlinks and starts:
   ```
   nativeLibraryDir/libnode.so --expose-internals \
     filesDir/engine/node_modules/@deepseek-ai/dsh/lib/bin.js \
     web --no-open --host 127.0.0.1 --port 0
   ```
5. DSH prints `dsh web: http://127.0.0.1:<port>/?token=…`. The engine parses the
   line and publishes `RUNNING`; the activity loads that URL.
6. The WebView follows the one-time token URL, receives a session cookie, and
   the DSH interface loads. Later loads use the cookie alone.

## Files and directories

All paths are app-private unless noted.

| Path | Contents |
| --- | --- |
| `filesDir/engine/` | extracted DSH tree, `git/templates`, payload marker |
| `filesDir/dsh-home/` | `DSH_HOME`: profiles, sessions, credentials, plugins |
| `filesDir/bin/` | `node`, `bash`, `sh`, `git`, `git-core/*` symlinks |
| `filesDir/home/` | `HOME`, including the seeded `.gitconfig` |
| `filesDir/workspace/` | fallback workspace if shared storage is unavailable |
| `cacheDir/tmp/` | `TMPDIR` |
| `filesDir/logs/` | engine log and crash log |
| `/storage/emulated/0/DSH/` | default workspace when storage is writable |

The workspace is created on shared storage so DSH can edit real projects. If
the directory cannot be created, the app falls back to app-private storage and
the engine still starts.

## Native runtime mechanics

Android only extracts APK entries named `lib<name>.so` from `lib/<abi>/`. The
runtime is therefore normalised by `engine/assemble-native.mjs` and
`engine/elf-patch.mjs`:

- Node is shipped as `libnode.so`; bash as `libbash.so`; git as `libgit.so`.
- Termux SONAMEs are rewritten to Android-safe names in place, with equal-length
  replacements in the string table and in every dependent's `DT_NEEDED`:
  `libz.so.1` → `libz-1.so`, `libcrypto.so.3` → `libcrypto-3.so`,
  `libssl.so.3` → `libssl-3.so`, `libicu*.so.79` → `libicu*-79.so`,
  `libc++_shared.so` → `libcpp_shared.so`, `libreadline.so.8` →
  `libreadline-8.so`, `libncursesw.so.6` → `libncursesw-6.so`.
- Termux's build-time `RUNPATH` (`/data/data/com.termux/files/usr/lib`, sometimes
  duplicated) is replaced with `$ORIGIN`, so libraries resolve next to
  `libnode.so` after Android extracts them.
- Git's HTTP transport helper is shipped as `libgit-remote-http.so`.
  `git` looks for `git-remote-https` in `GIT_EXEC_PATH`; the app creates
  symlinks for `git-remote-http`, `git-remote-https`, `git-upload-pack`,
  `git-receive-pack`, and `git-upload-archive`.
- `GIT_TEMPLATE_DIR` points at the upstream template tree staged inside the
  engine payload, so `git init` installs the usual sample hooks and `exclude`.

The `--expose-internals` flag is required because the official launcher reaches
Node's internal module loaders through a native addon that has no Android
build. See [patches](patches.md).

## Process and lifecycle

`Engine` serialises all mutating work on a single worker thread:
`start`, `stop`, `restart`, `enterSafeMode`, and `reinstallEngine`. State
changes and log lines are posted to the main thread for listeners. A
generation counter invalidates stale boot attempts after a stop or restart.

A startup watchdog fails the boot after 180 seconds. If the child process exits
unexpectedly, the engine publishes `FAILED` with the exit code; the UI exposes
retry and safe-mode actions.

`EngineService` is a `dataSync` foreground service on Android 10+. It holds a
partial wake lock while extracting, starting, or running, and updates its
notification from engine state. It stops itself when the user chooses **Stop**
from the notification or native settings. On Android 12+ a background
foreground-service start may be refused; the app logs the refusal instead of
crashing, and the next foreground activity promotes the service again.

## Safe mode

Safe mode is a small Node script written into the extracted engine package:

```js
import { sanitizeProfile } from '@deepseek-ai/dsh-app-boot'
import { PROFILE_TEMPLATES } from '@deepseek-ai/dsh-official-bundles'
const dir = process.argv[2]
const backup = sanitizeProfile('dsh', dir, PROFILE_TEMPLATES.web.bundles)
```

It must live under `node_modules/@deepseek-ai/dsh/` because the payload is an
isolated pnpm tree where bare imports only resolve from the package that owns
them. `sanitizeProfile` renames `cordis.patch.yml` to
`cordis.patch.yml.bak-<timestamp>`; sessions and installed packages are kept.
The engine then writes an empty `[]` patch if the profile directory survives
without one, and boots with official bundle defaults.

## Provider seeding

`assets/provider-presets.json` is the single source of truth for the curated
list. `ProviderPresets.ensureProfile()` writes `profiles/web/package.json` and
`cordis.patch.yml` only when `package.json` is absent. After that the profile
belongs to the user and the shell never touches it. The native Settings screen
shows the same list through `ProviderPresets.describe()` and points users to
DSH's Models page for keys.

## Network and authentication

The engine binds only to `127.0.0.1` on an ephemeral port:
`--host 127.0.0.1 --port 0`. DSH protects the first request with a random token
in the URL; the WebView follows it, obtains an HTTP-only session cookie, and
continues with the cookie. There is no listening socket on the LAN/Wi-Fi
interface.

`android:usesCleartextTraffic="true"` is required for loopback HTTP; it does not
route traffic off-device and is scoped to the app.

## Resource tuning

`NODE_OPTIONS=--max-old-space-size=…` is set to 768 MB on low-RAM devices and
1536 MB otherwise. The engine is a single Node process, so memory pressure is
mostly V8 heap plus the WebView. No swap file or extra daemon is used.
