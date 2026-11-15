# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [0.1.1] - 2026-11-15

Reliability and sign-in release. Fixes the WebView popup path that DeepSeek
account sign-in depends on, plus several long-running stability issues.

### Fixed

- **DeepSeek account sign-in.** The WebView now enables multiple windows and
  implements `onCreateWindow`, so the `window.open()` popup used by
  `dsh-client-ui-settings-account` is no longer dropped. The popup inherits
  the cookie jar and can complete the loopback `/oauth/callback` exchange,
  after which `window.close()` dismisses it. The "Copy sign-in link" fallback
  still works.
- **Android 7 verifier crash.** `onRenderProcessGone(WebView,
  RenderProcessGoneDetail)` moved to an API-26-only client subclass; the base
  client no longer references an API-26 type on minSdk 24.
- **Predictive back.** Registered an `OnBackInvokedCallback` on Android 13+
  and enabled `android:enableOnBackInvokedCallback`, so back navigation no
  longer bypasses the WebView history when targeting Android 16.
- **Session persistence.** Cookies are flushed on pause, page finish, and
  destroy, so the 30-day auth cookie survives process death.
- **Stale-token recovery.** A 401 reloads the one-time token URL twice and
  then restarts the engine for a fresh token, instead of looping on a dead
  URL. The token entry is removed from WebView history after the redirect.
- **Attachment URLs.** `dsh-app://app/api/...` URLs used by file previews are
  mapped to the loopback server and proxied with the current cookie.
- **Engine cleanup.** Stop, crash, and stale-PID cleanup now walk `/proc` and
  kill descendant bash/git/session processes, not just the Node parent.
- **Workspace selection.** `$EXTERNAL_STORAGE/DSH` is only used when it is
  actually writable; otherwise the app-private workspace is selected.
- **Log safety.** Timestamp formatting is thread-local, file writes are
  synchronized, and the engine log rotates at 2 MiB with one previous file.
- **Foreground service type.** Switched from `dataSync` (capped at six hours
  on Android 15+) to `specialUse`, with the required subtype property.
- **Network security.** Cleartext is restricted to `127.0.0.1`, `localhost`,
  and `::1`; external traffic must use TLS.
- **Crash recovery.** The service retries an unexpectedly stopped engine with
  bounded backoff (3 s, 10 s, 30 s, 60 s, 120 s) and honours explicit stop.
- **V8 heap sizing.** The old-space limit now scales with physical RAM (512 MB
  to 2 GB) instead of a binary low-RAM flag.

### Added

- About screen diagnostics: WebView package/version, outbound HTTPS
  reachability, and live loopback engine status.

### Build

- The APK is signed with APK Signature Scheme v2 only, which is what
  targetSdk 36 requires. `apksigner` is tried first; when it truncates large
  output (seen on some Android/Termux runtimes), `scripts/sign-apk-v2.py`
  writes the same v2 block directly and the result still verifies with the
  official `apksigner`.

### Known limitations

- Device-side installation and real account sign-in still need validation on
  physical hardware; see [docs/device-testing.md](docs/device-testing.md).

## [0.1.0] - 2026-11-14

First public preview. Signed arm64 APK published as a release asset.

### Added

- Native Java/Android shell that runs the official
  `@deepseek-ai/dsh@0.2.2-rc.2` engine on a bundled Node.js 26 runtime.
- Bundled GNU bash 5.3.20 and Git 2.56.1 with HTTPS transport, so DSH's shell
  and version-control tools work on-device.
- Foreground service that keeps the engine alive with a low-importance
  notification and wake lock, plus start/stop actions.
- WebView UI with one-time token authentication against the loopback engine.
- One-time seeding of curated model providers: OpenRouter, Groq, Cerebras,
  DeepSeek, Together AI, and SiliconFlow.
- Safe mode: backs up the profile patch and boots with official bundle
  defaults when an extension breaks startup.
- Native settings for restart/stop, payload re-extraction, storage access,
  telemetry (off by default), battery settings, logs, and about.
- In-app crash/engine log viewer, third-party license texts, and download,
  upload, and microphone bridges.
- On-device build pipeline (`aapt2` → `javac` → `D8` → package → `apksigner`)
  with no Gradle.

### Known limitations

- Office document conversion is unavailable; the WASM LibreOffice kit is
  pruned from the payload to keep the APK small.
- Git remotes must use HTTPS; SSH is not bundled.
- `arm64-v8a` only.
- Signed with a local development key; not a Play Store build.
- Installed-APK verification on physical hardware is pending; see
  [docs/device-testing.md](docs/device-testing.md).
