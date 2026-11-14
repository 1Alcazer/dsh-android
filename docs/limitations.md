# Limitations

This is an unofficial, sideloaded build of a desktop-oriented harness. It runs
the real engine, but the platform imposes real constraints. Read this before
filing a bug.

## Verification status

- The engine payload, Node runtime, bash, git, safe mode, and the
  `401 → token → cookie → 200` web boot path were executed and validated in the
  build sandbox.
- The signed APK was **not installed on a phone** by the build environment:
  there is no `adb`, no root, and no package-installer permission. Installation
  and on-device UI behaviour still need the checklist in
  [device-testing.md](device-testing.md).
- Everything below describes designed behaviour and known constraints, not a
  claim of exhaustive device coverage.

## Platform and hardware

- **arm64 only.** The bundled runtime is Termux `aarch64`. There is no
  `armv7`, `x86`, or `x86_64` build. 32-bit-only devices are unsupported.
- **Android 7.0+ (API 24).** Target SDK is 36. Newer Android versions apply
  stricter foreground-service and storage rules; the shell requests the
  `dataSync` foreground type and handles a refused background start, but OEM
  battery managers can still stop the process.
- **System WebView required.** The UI is Android's WebView. A missing or
  forcibly disabled WebView shows an error; there is no native fallback UI for
  the DSH interface.
- **Storage.** First launch needs ~700 MiB total and checks for 500 MiB free
  before extraction. A 16 GB phone with little free space will fail the check
  cleanly rather than half-extract.
- **RAM.** 2 GB is the practical minimum and 3 GB+ is recommended. The Node
  heap is capped at 768 MiB on low-RAM devices and 1536 MiB otherwise; very
  large contexts can still be killed by the system.

## Missing engine features

- **Office document conversion is unavailable.** The upstream
  `@deepseek-ai/libreoffice-kit-wasm` package (146 MiB unpacked) was removed to
  keep the APK near 124 MiB. DSH tools that convert `.docx`/`.xlsx`/`.pptx` to
  PDF or images will report a missing engine. There is no on-demand installer
  in this build. Text, code, Markdown, and ordinary file editing are unaffected.
- **Third-party desktop native addons will not load.** Plugins that ship
  prebuilt Linux/desktop `.node` binaries, or that download them for
  `linux-x64`, cannot run under this Android Node build. Pure-JavaScript
  plugins work.
- **Desktop-only integration points are absent.** Clipboard-image paste,
  drag-and-drop from a desktop file manager, multi-window workflows, and
  hardware-accelerated local model runtimes are not part of the shell.

## Shell and developer tools

- **Git over HTTPS only.** The HTTP transport helper and libcurl are bundled;
  there is no `ssh` binary, no `ssh-keygen`, and no SSH agent. `git@github.com:…`
  remotes cannot work. Public HTTPS clones work; private HTTPS remotes need a
  token in the URL or a credential helper you provide yourself.
- **Interactive git prompts are disabled** (`GIT_TERMINAL_PROMPT=0`) and
  `GIT_PAGER=cat` avoids the unbundled `less`. A command that would normally
  ask for a password fails instead of hanging.
- **Only core git helpers are exposed.** `git-remote-https`, `git-upload-pack`,
  `git-receive-pack`, and `git-upload-archive` are symlinked. Shell and Perl
  helpers such as `git submodule`, `git filter-branch`, `git send-email`,
  `git p4`, and `git cvsimport` are not shipped (no Perl runtime either).
  Everyday `init`/`add`/`commit`/`log`/`diff`/`clone`/`pull`/`push` are fine.
- **No Python, Perl, Ruby, or Java runtime for user commands.** bash and git are
  available to the agent, but scripts that invoke `python3` or `perl` fail.
  The Android shell itself is Java, not an exposed runtime.
- **Android's W^X rules apply.** Executables must live in the APK's native
  library directory. That is why `bash`, `git`, and Node have `lib*.so` names
  and are exposed through symlinks. You cannot drop an arbitrary binary into
  the workspace and execute it on modern Android.

## Shell behaviour

- **One instance, loopback only.** The engine listens on `127.0.0.1` with an
  ephemeral port and token auth. You cannot reach it from a PC on the same
  Wi-Fi network. This is intentional.
- **Re-extract deletes the extracted engine only.** `dsh-home` (sessions,
  credentials, plugins, profile) survives. A corrupt `dsh-home` is not repaired
  by re-extraction; use safe mode or clear app data.
- **Safe mode resets only the provider patch.** It backs up
  `cordis.patch.yml` and boots official bundle defaults. Installed packages and
  sessions remain; a broken installed package may still need removal from the
  DSH home.
- **Provider presets are seeded once.** If you delete all providers or edit the
  patch file, the shell will not re-add them. The native Settings screen is
  informational; keys are entered in DSH's Models page.
- **No auto-update.** Installing a newer APK preserves app data, but the engine
  payload is replaced only when its hash changes. Downgrades are not supported
  by Android.

## Telemetry and networking

- Official telemetry export is **off by default** (`DSH_TELEMETRY_DISABLED=1`)
  and must be enabled explicitly in native Settings.
- Model API keys and traffic go directly from the phone to the provider you
  configure. The shell does not proxy or inspect them.
- Cleartext traffic is enabled app-wide because the engine is loopback HTTP.
  No non-loopback cleartext endpoint is configured by the shell.

## Legal and support

- This is an unofficial community build, not a DeepSeek product. Report shell
  bugs here; report engine bugs upstream.
- GPL/LGPL components (bash, readline, git, iconv) are redistributed under
  their licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for
  versions, sources, and how the binaries were modified for Android.
