# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

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
