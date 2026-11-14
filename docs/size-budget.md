# Size budget

The shipped APK is **129,830,240 bytes (123.8 MiB)**. Almost all of it is the
official engine and the native runtime; the Java shell is about 50 KiB of dex.

## Where the bytes go

| Part | Raw | In APK | Notes |
| --- | ---: | ---: | --- |
| DSH engine payload | 88,864,081 B (84.8 MiB) | 84.8 MiB | stored uncompressed for fast extraction |
| Native runtime (26 libs) | 110,022,864 B (104.9 MiB) | 38.9 MiB | deflated; Android extracts at install |
| Java dex | ~109 KiB | ~52 KiB | plain platform Views, no AndroidX |
| License texts | ~240 KiB | ~39 KiB | notices + GPL/LGPL/MIT texts |
| Manifest, resources, icons | ~20 KiB | ~20 KiB | one adaptive icon + small UI assets |
| **Total** | — | **123.8 MiB** | |

Largest native files after patching:

| File | Raw | In APK |
| --- | ---: | ---: |
| `libnode.so` (Node) | 51,010,000 B | 16.4 MiB |
| `libicudata-79.so` | 33,559,760 B | 12.2 MiB |
| `libcrypto-3.so` | 5,213,680 B | 2.3 MiB |
| `libgit.so` | 3,614,432 B | 1.7 MiB |
| `libicui18n-79.so` | 3,374,256 B | 1.2 MiB |
| `libgit-remote-http.so` | 2,110,904 B | 1.0 MiB |

Git and bash together add about 5.5 MiB in the APK. They were included because
DSH's agent tools call both directly; a coding harness without a real shell and
version control would be a demo, not a usable build.

## What was removed from the upstream tree

| Removed | Saving | Consequence |
| --- | ---: | --- |
| `@deepseek-ai/libreoffice-kit-wasm@0.2.3` | 146 MiB unpacked | Office document conversion is unavailable (see [limitations](limitations.md)) |
| 27 `test`/`tests` directories | small | none at runtime |

Without these prunes the engine payload would be roughly 235 MiB and the APK
roughly 276 MiB. The Office engine alone is larger than the rest of the app.

## Installed footprint

| Item | Approximate size |
| --- | ---: |
| APK in `/data/app` | 124 MiB |
| Extracted native libs | 105 MiB |
| Extracted engine payload | 473 MiB |
| DSH home after first boot | tens of MiB, grows with sessions |
| **First-launch total** | **about 700 MiB** |

`Engine.ensureEngine()` requires 500 MiB free before extraction, which covers
the 473 MiB engine tree plus overhead. The rest of the footprint exists before
that check because Android has already installed the APK and extracted the
native libraries. A phone with less than ~800 MiB free is not a good target.

## Why the payload is stored uncompressed

`assets/dsh-engine.tar.gz` is an 85 MiB gzip stream. Re-deflating it would save
little (the data is already compressed), slow every extraction, and make the
ZIP layout worse. `scripts/package_apk.py` stores it and aligns its data
offset, the same treatment `zipalign -p` gives stored assets. `resources.arsc`
and `AndroidManifest.xml` are stored and aligned for the same reason.

## Runtime memory

The Node heap cap is 768 MiB on low-RAM devices and 1536 MiB otherwise.
The practical floor is around 2 GB of RAM for a comfortable session with the
WebView and a model-sized context; 3 GB or more is preferable. No swap or
secondary process is used beyond Node itself.

## Future space options

These are deliberately **not** in this build:

- Restoring the LibreOffice WASM engine (+146 MiB unpacked) or downloading it
  on demand from npm. Any such download must be verified, extracted into the
  pnpm store, and followed by an engine restart; none of that is implemented
  here.
- Dropping git or bash (saves ~5.5 MiB, removes required agent tools).
- Splitting the engine into an install-time asset pack. Android asset packs
  would reduce the base APK but move the same bytes to the Play delivery; this
  build is sideloaded, so there is nothing to split.
