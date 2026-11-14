# Platform patches

There is exactly **one source-level patch** in the engine payload, plus
packaging-time transformations of native binaries. Everything else is the
official `@deepseek-ai/dsh@0.2.2-rc.2` production tree.

## 1. app-boot internals fallback

**File:** `engine/payload-patch.mjs`
**Applied by:** `engine/assemble-engine.mjs`

### Problem

The official launcher reaches Node's internal ESM loaders through
`node-addon-require-builtin`, a native addon. There is no Android arm64 build
of that addon in the dependency tree, so importing `dsh-app-boot` fails with
`MODULE_NOT_FOUND` before the engine can start.

### Change

Upstream source:

```js
const addon = createRequire(import.meta.url)("node-addon-require-builtin");
const esmModule = addon.requireBuiltin(
```

Patched source:

```js
// DSH-ANDROID patch: expose-internals fallback
let addon; try { addon = createRequire(import.meta.url)("node-addon-require-builtin"); } catch { addon = { requireBuiltin: (id) => createRequire(import.meta.url)(id) }; }
const esmModule = addon.requireBuiltin(
```

The fallback uses `require('internal/…')`, which Node permits when started with
`--expose-internals` — the same flag the official desktop launcher uses. The
Android shell always passes that flag (and so does the safe-mode helper).

### Scope and safety

- Candidates are discovered under
  `.pnpm/@deepseek-ai+dsh-app-boot@*/…/lib/index.js` and
  `lib/worker/profile-resolution-bootstrap.js`, with a flat-layout fallback.
- Each file is checked for the marker before patching, so repeated builds are
  idempotent (`0 patched, 2 already up to date` on this payload).
- If the upstream pattern is absent, the build fails with an actionable error.
  It never silently ships an unpatched engine.
- The patch is covered by the payload SHA-256 in
  `assets/dsh-engine.manifest.json`.

### Verify

```sh
grep -R "DSH-ANDROID patch: expose-internals fallback" \
  engine/cache/payload/engine/node_modules/.pnpm/@deepseek-ai+dsh-app-boot@*
```

## 2. Native ELF renaming and RUNPATH rewriting

**File:** `engine/elf-patch.mjs`
**Applied by:** `engine/assemble-native.mjs`

Android only extracts APK entries named `lib<name>.so` from `lib/<abi>/`.
Termux packages use normal Linux names (`libz.so.1`, `libreadline.so.8`, …).
The assembler makes equal-length byte replacements in the ELF string table:

| Upstream | Shipped |
| --- | --- |
| `libz.so.1` | `libz-1.so` |
| `libcrypto.so.3` | `libcrypto-3.so` |
| `libssl.so.3` | `libssl-3.so` |
| `libicui18n.so.79` | `libicui18n-79.so` |
| `libicuuc.so.79` | `libicuuc-79.so` |
| `libicudata.so.79` | `libicudata-79.so` |
| `libc++_shared.so` | `libcpp_shared.so` |
| `libreadline.so.8` | `libreadline-8.so` |
| `libncursesw.so.6` | `libncursesw-6.so` |

Each dependent's `DT_NEEDED` entry is changed consistently. The Termux
build-time RUNPATH `/data/data/com.termux/files/usr/lib` (including duplicated
path lists) is replaced with `$ORIGIN`, so the set resolves inside the app's
native library directory.

Binaries are not otherwise modified:

- Node → `libnode.so`
- bash → `libbash.so`
- git → `libgit.so`
- `git-remote-http` → `libgit-remote-http.so`

The executable bits inside the APK are irrelevant; Android controls execution
from `nativeLibraryDir`, and the app exposes them through symlinks in
`filesDir/bin`.

## 3. Runtime safe-mode helper (generated, not shipped)

`Engine.writeSafeModeScript()` writes
`node_modules/@deepseek-ai/dsh/dsh-android-safe-mode.mjs` into the extracted
payload on first extraction. It imports `sanitizeProfile` and
`PROFILE_TEMPLATES` and renames the profile patch. It must live inside the
`dsh` package because the pnpm tree only resolves bare imports from there.

This file is regenerated if missing, is never part of the APK asset, and does
not affect the payload hash.

## 4. Profile seeding (runtime data, not a patch)

`ProviderPresets.ensureProfile()` writes a normal DSH profile on first boot:
`package.json` plus `cordis.patch.yml` containing the curated providers. This
is the same file DSH's Models page edits. Once it exists, the shell never
rewrites it. Safe mode is the only operation that deliberately moves it aside
(with a timestamped `.bak` copy).

## What is not patched

- The DSH web frontend and client bundles.
- Engine CLI arguments other than `--expose-internals`, `--no-open`,
  `--host 127.0.0.1`, and `--port 0`.
- Provider plugins, tool schemas, session storage, or telemetry code.
- The official production dependency versions (except the removed optional
  LibreOffice engine and test directories).

## Upgrading DSH

When bumping the engine version:

1. update `engine/stage/package.json` and regenerate the lockfile;
2. re-run `scripts/stage-engine.sh`;
3. run `node engine/assemble-engine.mjs` and watch the patch report;
4. if the app-boot pattern changed, update `engine/payload-patch.mjs` and this
   document deliberately;
5. update `Engine.DSH_VERSION`, the README table, and the payload manifest;
6. rebuild and re-run the device checklist.

The build fails loudly rather than producing a subtly broken payload.
