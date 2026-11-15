# DSH Android

[![Release](https://img.shields.io/github/v/release/1Alcazer/dsh-android?include_prereleases&style=flat-square&label=release)](https://github.com/1Alcazer/dsh-android/releases)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B%20%C2%B7%20arm64-3DDC84?style=flat-square)](#at-a-glance)
[![Engine](https://img.shields.io/badge/DSH-0.2.2--rc.2-4D6BFE?style=flat-square)](https://www.npmjs.com/package/@deepseek-ai/dsh)

Run the official [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)
on an Android phone, natively. No emulator, no Wine, no proot, no remote
server: a small Java shell starts the real DSH Node engine under a bundled,
on-device Linux/Android runtime and shows the official web interface in a
WebView.

This repository builds a signed, installable APK entirely on the device (and
on any Linux host) without Gradle.

## Download

Grab the signed APK from the latest release:

- [**DSH-Android-0.1.3.apk**](https://github.com/1Alcazer/dsh-android/releases/download/v0.1.3/DSH-Android-0.1.3.apk) (123.9 MiB, arm64)
- [All releases](https://github.com/1Alcazer/dsh-android/releases)

Verify the download:

```sh
sha256sum DSH-Android-0.1.3.apk
# 95b053f3b46469d23ff78182292fc0e65e4464dd81f1a48df90e8a86760a30d2
```

## At a glance

| Item | Value |
| --- | --- |
| Package | `dev.dsh.android` |
| Version | `0.1.3` (versionCode 4) |
| DSH engine | `@deepseek-ai/dsh@0.2.2-rc.2` (official npm production tree) |
| Node runtime | `26.13.1` (Termux aarch64) |
| Shell | GNU bash `5.3.20` |
| Version control | Git `2.56.1`, HTTPS remotes |
| ABI | `arm64-v8a` |
| Android | 7.0+ (minSdk 24), target/compile 36 |
| APK | `dist/DSH-Android-0.1.3.apk` |
| APK size | 129,863,033 bytes (123.9 MiB) |
| APK SHA-256 | `95b053f3b46469d23ff78182292fc0e65e4464dd81f1a48df90e8a86760a30d2` |
| Engine payload | 88,777,991 bytes, sha256 `62d6760d9215e0203ac8bb2fd87bae788b0c487424c1a4ccdf2195b2584f0bc1` |

The APK in `dist/` is signed with a local development key. Its certificate
SHA-256 is
`46f3451e91c97164fef0179f776a405cdf86394eb05d3b493b0c565e348cbc0d`.
Builds are reproducible from the pinned inputs, but are not Play Store
releases; Android will warn about installing from an unknown source.

## Status

Preview release, published as a signed arm64 APK. 0.1.3 repairs DeepSeek account
sign-in: the bundled Node runtime has no embedded CA list, so the app now
merges the device trust store into one PEM bundle and points the engine at it.
Without that, every outbound request fails with
`UNABLE_TO_GET_ISSUER_CERT_LOCALLY` and the interface reports
"Network error, please retry" during sign-in. 0.1.3 also adds a TLS self-test
to the app log and keeps the WebView renderer alive off-screen. See the
[changelog](CHANGELOG.md) for the full list.

The engine payload, bash, git, safe mode, and the loopback HTTP boot path were
exercised end to end in the build sandbox, and the signature is verified with
the official `apksigner`. The signed APK has **not** been installed on physical
hardware yet: the build environment has no `adb`, root, or package-installer
permission. See [docs/device-testing.md](docs/device-testing.md) for the exact
verification status and the on-device checklist.

## What works

- **Official engine** — the production `@deepseek-ai/dsh` dependency closure,
  not a reimplementation. Sessions, plugins, tools, and the web UI are the real
  thing.
- **Native shell** — plain Java and platform Views. No AndroidX, Compose,
  Gradle, or JavaScript shell.
- **Foreground service** — keeps the Node process alive in the background with
  a low-importance notification, wake lock, start/stop actions, and bounded
  restart backoff if the engine dies.
- **Real bash** — GNU bash 5.3.20 with readline, ncurses, iconv, and Android
  support libraries. DSH's `bash` tool works.
- **Git** — Git 2.56.1 core plus HTTPS transport. `init`, `add`, `commit`,
  `log`, `diff`, and `clone` over HTTPS work.
- **Curated providers** — OpenRouter, Groq, Cerebras, DeepSeek, Together AI,
  and SiliconFlow are seeded into the web profile on first launch.
- **Safe mode** — backs up the provider patch and boots with official bundle
  defaults when an extension breaks startup.
- **Native settings** — restart/stop, re-extract payload, storage access,
  telemetry toggle (off by default), battery settings, logs, about.
- **Downloads, uploads, microphone** — wired through Android's own
  `DownloadManager`, file chooser, and WebView permission bridge.
- **Account sign-in** — the DeepSeek account popup, loopback OAuth callback,
  and the copy-link fallback all work in the WebView.
- **Crash and engine logs** — in-app viewer with copy/export.
- **In-app licenses** — third-party notices and full GPL/LGPL texts under
  About → Third-party licenses and notices.

## Requirements

- Android 7.0 (API 24) or newer, **arm64-v8a** device.
- About 600 MB of free internal storage for the first launch.
- 2 GB RAM recommended (the Node engine and WebView run side by side).
- Network access only for model providers and Git remotes; the engine itself
  runs locally on `127.0.0.1`.

## Install

Download the APK from the [latest release](https://github.com/1Alcazer/dsh-android/releases/latest),
or build it yourself (see [Build](#build)) and use:

```sh
adb install -r dist/DSH-Android-0.1.3.apk
```

Or copy the APK to the phone and open it. Allow installation from the file
manager/browser you used.

On first launch the app:

1. checks for roughly 600 MB of free space;
2. extracts the 85 MB engine payload into app-private storage (~473 MB);
3. seeds the web profile with the provider presets;
4. starts Node on `127.0.0.1` with an ephemeral port and a one-time token;
5. opens the official DSH UI after authenticating the WebView session.

Extraction typically takes one to three minutes on a mid-range phone. Keep the
screen on for the first launch.

## First steps

- Tap **Allow notifications** on Android 13+ so the engine notification is
  visible.
- Open **Settings → Model providers** for the seeded provider list.
- Add API keys in the DSH interface under **Settings → Models**. Keys are stored
  by DSH, not by the Android shell.
- To use a DeepSeek account instead of an API key, open
  **Settings → Account → Sign in**. The shell shows the popup window the
  account flow needs; see [Account sign-in](docs/account-sign-in.md).
- Grant **all-files access** in the native Settings screen if you want projects
  under `/storage/emulated/0` (the default workspace is
  `/storage/emulated/0/DSH`).
- The native **Restart engine** action applies settings and picks up profile
  changes.

## Build

```sh
./scripts/stage-engine.sh          # pinned production DSH tree
node engine/fetch-debs.mjs         # verifies Termux debs by sha256
node engine/assemble-native.mjs    # builds jniLibs/arm64-v8a
node engine/assemble-engine.mjs    # builds assets/dsh-engine.tar.gz
./scripts/build-apk.sh             # aapt2 -> javac -> d8 -> package -> sign
```

See [docs/build-from-source.md](docs/build-from-source.md) for prerequisites,
toolchain paths, environment overrides, and signing details.

## Repository layout

```
android/app/src/main/java/       Java shell (activities, engine, service)
android/app/src/main/res/        platform resources and icons
android/app/src/main/assets/     provider presets + generated engine payload
android/app/src/main/jniLibs/    generated arm64 runtime (Node, bash, git, libs)
engine/                          payload assembly and ELF patching
engine/stage/                    pinned pnpm lockfile for the engine tree
scripts/                         staging, APK packaging, and build scripts
docs/                            architecture, build, limits, notices
```

Generated payloads, native libraries, keystores, and APKs are gitignored.

## Documentation

- [Architecture](docs/architecture.md)
- [Build from source](docs/build-from-source.md)
- [Size budget](docs/size-budget.md)
- [Limitations](docs/limitations.md)
- [Platform patches](docs/patches.md)
- [Account sign-in](docs/account-sign-in.md)
- [Device testing](docs/device-testing.md)
- [Third-party notices](docs/THIRD_PARTY_NOTICES.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## Legal

The DSH Android shell source is released under the [MIT License](LICENSE).

DSH Android is an unofficial community shell. It is not affiliated with or
endorsed by DeepSeek. DeepSeek Harness is MIT-licensed. The APK also contains
GPL, LGPL, Apache, BSD, MIT, and other components; the exact versions, sources,
and license references are in
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md).
