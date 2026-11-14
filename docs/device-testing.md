# Device testing

This document records what has been verified, what has not, and the checklist
for a release candidate.

## What the build environment verified

The signed APK was produced and inspected; the engine and bundled tools were
executed against the same payload and environment the app uses.

| Check | Result |
| --- | --- |
| `apksigner verify` (v1/v2/v3) | pass; certificate SHA-256 `46f3451e…cbc0d` |
| Manifest / package | `dev.dsh.android`, versionName 0.1.0, minSdk 24, target 36 |
| ZIP layout | manifest, `resources.arsc`, payload stored and 4-byte aligned |
| Payload integrity | asset SHA-256 equals `dsh-engine.manifest.json` |
| Payload contents | 26 native libs, `libbash.so`, `libgit.so`, `libgit-remote-http.so`, `git/templates` |
| DSH version | `0.2.2-rc.2` |
| DSH web boot | `dsh web: http://127.0.0.1:<port>/?token=…` |
| HTTP auth flow | token URL → 303, session cookie → index 200 (41,184 bytes) |
| bash | `GNU bash 5.3.20`, pipelines, `set -euo pipefail`, arithmetic |
| bash from Node | `execFileSync('bash', …)` works through the app-style `PATH` |
| git | 2.56.1; init (templates copied), add, commit, log, diff |
| git HTTPS | `clone --depth 1 https://github.com/octocat/Hello-World.git` succeeds |
| safe mode | backs up `cordis.patch.yml`, removes the original, exits 0 |
| ELF checks | `$ORIGIN` RUNPATH on every copied library, Android-safe SONAMEs |

## What was not verified

- Installing and launching the APK on a physical phone or emulator.
- WebView rendering, cookie persistence, and the DSH UI on Android.
- Foreground-service notification, wake lock, and OEM background policies.
- DownloadManager, file chooser, microphone, and notifications runtime prompts.
- All-files storage access and workspace switching on Android 10+.

The sandbox has no `adb`, no root, and no package-installer permission. Treat
the checklist below as required before calling a build device-ready.

## Install

```sh
adb install -r dist/DSH-Android-0.1.0.apk
# or copy the APK to the phone and open it
```

If an older build signed with a different key is installed:

```sh
adb uninstall dev.dsh.android
adb install dist/DSH-Android-0.1.0.apk
```

Watch logs while testing:

```sh
adb logcat -s DshAndroid:V chromium:V AndroidRuntime:E
```

## Checklist

### 1. Fresh install and first boot

- [ ] App opens to the branded splash screen, not a blank window.
- [ ] Progress advances through `Unpacking … %` and `Starting …`.
- [ ] First boot completes within about three minutes on a mid-range phone.
- [ ] The DSH interface appears with no login screen (loopback token flow).
- [ ] The engine notification appears; tapping it opens the app.
- [ ] Airplane mode: the engine still boots and the local UI renders. Model
      calls fail with a normal network error, not a crash.
- [ ] Kill and reopen the app: it reuses the extracted payload (fast boot) and
      sessions are still present.

### 2. Storage

- [ ] With all-files access granted, the workspace is `/storage/emulated/0/DSH`.
- [ ] Without it, the app falls back to app-private workspace and the UI still
      works.
- [ ] A test file created by the agent is visible in a file manager at the
      workspace path.
- [ ] A device with under 500 MiB free shows the clean "not enough free space"
      error and retry, not a partial engine.

### 3. bash and git

Ask the agent to run, or open a DSH terminal if available:

```sh
bash --version | head -1
printf '%s\n' a b | while read x; do echo "got $x"; done
git --version
git init /storage/emulated/0/DSH/git-test
cd /storage/emulated/0/DSH/git-test
printf 'hello\n' > a.txt
git add a.txt
git -c user.name=DSH -c user.email=dsh@example.invalid commit -m first
git log --oneline
git diff --stat
```

Expected: bash `5.3.20(1)-release`, two `got` lines, git `2.56.1`, one commit,
and the `.git/hooks/pre-commit.sample` plus `.git/info/exclude` templates.

Public HTTPS clone:

```sh
cd /storage/emulated/0/DSH
git clone --depth 1 https://github.com/octocat/Hello-World.git hello
ls hello
```

Expected: `README`, no prompt, exit 0. SSH remotes are expected to fail
(no ssh binary).

### 4. Providers

- [ ] DSH Models page lists OpenRouter, Groq, Cerebras, DeepSeek, Together AI,
      and SiliconFlow on a fresh profile.
- [ ] Adding a key for at least one provider lets a chat request succeed.
- [ ] Native **Settings → Model providers** shows the same list and the
      `*_API_KEY` variable names.
- [ ] Restart the engine: the provider list and entered keys persist.
- [ ] Safe mode: provider patch is moved to `cordis.patch.yml.bak-<timestamp>`
      and the engine boots with official defaults.

### 5. Shell controls

- [ ] **Restart engine** reconnects the WebView without duplicating processes.
- [ ] **Stop engine** leaves the process stopped and the UI shows a clean error.
- [ ] **Re-extract engine files** rebuilds the payload and keeps sessions.
- [ ] **Copy engine log** / log viewer shows useful lines and can export.
- [ ] Force a crash in a debug copy and confirm `crash.log` is written.

### 6. Android integrations

- [ ] Download a file from the DSH UI; it lands in Downloads and the
      notification completes. On Android 9 the storage prompt appears once.
- [ ] File upload opens the system picker and the chosen file reaches DSH.
- [ ] Microphone permission is requested on first use and audio capture works.
- [ ] Notifications permission prompt appears on Android 13+.
- [ ] Rotate the phone; the WebView keeps its state.
- [ ] Background the app for 10 minutes with battery optimization disabled; the
      engine is still running when you return.
- [ ] Kill the app from recents; the service/engine state is recoverable on the
      next launch.

## Troubleshooting

| Symptom | Likely cause | Action |
| --- | --- | --- |
| "App not installed" / parse error | wrong ABI or corrupted download | verify sha256, use arm64 device |
| Signature mismatch on update | previous build signed with another key | uninstall, then install |
| Stuck at "Unpacking" | low storage or killed process | free ~700 MiB, keep screen on |
| "Not enough free space" | less than 500 MiB available | free space and retry |
| Engine exits before ready | broken profile/plugin | **Safe mode**, then remove the offending package |
| Blank WebView after boot | engine stopped or WebView crash | restart engine; check log viewer |
| Provider list missing | profile already existed or was edited | edit `cordis.patch.yml` manually or use a fresh profile |
| Git clone asks for credentials | SSH remote or private HTTPS repo | use HTTPS with a token; interactive prompts are disabled |
| Background engine dies | OEM battery manager | disable battery optimization for DSH Android |
| UI slow / reloads | low RAM or WebView under pressure | close other apps, use a 3 GB+ device |
| Office conversion tool errors | LibreOffice WASM engine not bundled | expected; see [limitations](limitations.md) |

## Reporting a bug

Include:

- phone model, Android version, WebView version;
- APK SHA-256 and DSH version from **About**;
- steps to reproduce;
- the engine log from **Logs → Share** and, for crashes, `crash.log`;
- whether safe mode changes the behaviour.

Never paste API keys or session contents into a report.
