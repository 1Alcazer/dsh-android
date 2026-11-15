# Build from source

The build has no Gradle and no Android Studio dependency. It is a pipeline of
small, inspectable steps:

```
stage DSH tree → fetch + verify debs → patch ELF → pack payload
              → aapt2 compile/link → javac → d8 → zip → apksigner
```

The same pipeline was used to produce `dist/DSH-Android-0.1.1.apk` on-device.

## Prerequisites

| Tool | Why | Notes |
| --- | --- | --- |
| Node.js ≥ 22 | assembly scripts, `tar`, `crypto` | 26.x used here |
| pnpm 10 | staged production DSH tree | `corepack enable pnpm` |
| `dpkg-deb` | unpack Termux `.deb` runtime packages | `dpkg` package |
| GNU tar | pack/extract payloads | base system |
| Python 3 | custom APK packager | stdlib only |
| JDK 17+ | `javac`, `keytool`, `apksigner` | JDK 21 used here |
| Android SDK platform 36 | `android.jar` | `platforms/android-36` |
| Android build-tools | `d8.jar`, `apksigner.jar` | 36.0.0 used here |
| `aapt2` | resource compile/link | Android-native binary needed on-device |

On Termux the working set is:

```sh
pkg install nodejs-lts python dpkg aapt2 openjdk-21
# plus an Android SDK layout containing platforms/android-36 and
# build-tools/36.0.0 (d8.jar, apksigner.jar)
```

On a desktop Linux host, install the same tools from your distribution and
point the build at your SDK.

## 1. Stage the engine

```sh
./scripts/stage-engine.sh
```

This copies the pinned `engine/stage/package.json` and
`engine/stage/pnpm-lock.yaml` into `.cache/stage-dsh` and runs:

```sh
pnpm install --prod --ignore-scripts --frozen-lockfile
```

The lockfile pins every transitive package and integrity hash. Refresh it only
when deliberately bumping DSH, and record the new version in
`engine/provenance.json`, `Engine.DSH_VERSION`, and the payload manifest.

## 2. Fetch and verify the native runtime packages

```sh
node engine/fetch-debs.mjs
```

Downloads the Termux aarch64 packages listed in `engine/provenance.json` into
`engine/cache/debs` and verifies each SHA-256. Re-running is safe; existing
files are reused. To build offline, place the exact `.deb` files in that
directory first.

## 3. Assemble the runtime libraries

```sh
node engine/assemble-native.mjs
```

This extracts and patches 26 libraries into
`android/app/src/main/jniLibs/arm64-v8a`:

| Group | Files |
| --- | --- |
| Node core | `libnode.so`, ICU, OpenSSL, SQLite, c-ares, zlib, libffi, libc++ |
| bash | `libbash.so`, `libreadline-8.so`, `libncursesw-6.so`, `libiconv.so`, `libcharset.so`, `libandroid-support.so` |
| git | `libgit.so`, `libgit-remote-http.so`, `libcurl.so`, `libnghttp2.so`, `libnghttp3.so`, `libngtcp2.so`, `libngtcp2_crypto_ossl.so`, `libssh2.so`, `libpcre2-8.so` |

Every file is renamed to a `lib*.so` name, its SONAME is rewritten to match,
and its `RUNPATH` becomes `$ORIGIN`. The script prints a per-file patch count.
It refuses to overwrite with a partial result; rerun it after changing
`engine/provenance.json`.

## 4. Assemble the engine payload

```sh
node engine/assemble-engine.mjs
```

This:

1. copies the staged `node_modules` tree with symlinks preserved;
2. removes `@deepseek-ai/libreoffice-kit-wasm` (146 MB) and package test
   directories;
3. applies the app-boot internals fallback (`engine/payload-patch.mjs`);
4. copies Git templates out of the verified git deb into `engine/git`;
5. writes `assets/dsh-engine.tar.gz` and `assets/dsh-engine.manifest.json`.

The manifest records the exact byte count and SHA-256. The app compares that
hash at boot, so do not hand-edit the tarball without updating the manifest.

Useful overrides:

```sh
node engine/assemble-engine.mjs --src /path/to/node_modules --out /path/to/assets
```

## 5. Build and sign the APK

```sh
./scripts/build-apk.sh
```

Stage by stage:

1. `aapt2 compile` the resources;
2. `aapt2 link` with `android.jar` (minSdk 24, target 36);
3. `javac --release 17` against the platform jar;
4. `d8` to `classes.dex`;
5. `scripts/package_apk.py` adds dex, assets, and libs, stores
   `AndroidManifest.xml`, `resources.arsc`, and the engine payload uncompressed,
   aligns stored entries, and deflates native libraries;
6. the APK is signed with APK Signature Scheme v2. `apksigner` is tried
   first; because some Android/Termux runtimes truncate its output near
   130 MB, the script validates the archive and falls back to
   `scripts/sign-apk-v2.py`, which inserts the same v2 block directly.
   v2 is required for targetSdk 36 and is sufficient for minSdk 24.

Output:

```
dist/DSH-Android-0.1.1.apk
```

Environment overrides:

```sh
ANDROID_SDK_ROOT=/path/to/sdk
JAVA_HOME=/path/to/jdk
AAPT2=/path/to/aapt2
BUILD_TOOLS=/path/to/build-tools/36.0.0
PLATFORM=/path/to/android-36/android.jar
VERSION_NAME=0.1.1 VERSION_CODE=2 MIN_SDK=24 TARGET_SDK=36
```

`package_apk.py` uses only the Python standard library and does not need
`zipalign`; it writes aligned entries directly.

## Signing

If `android/keystore/dsh-release.jks` is absent, the build creates a 4096-bit
RSA key with alias `dsh` and the development password `dsh-android-release`.
That is convenient for local builds, **not** suitable for public releases.
For a real release, create your own keystore and change the paths/passwords in
`scripts/build-apk.sh`, or sign the unsigned APK yourself:

```sh
apksigner sign --ks my-release.jks --out DSH-Android.apk \
  android/app/build/unsigned.apk
```

Both signing paths keep the certificate in the keystore, so a new build
upgrades an installed older version instead of conflicting with it. Keystores
and APKs are gitignored.

## Reproducibility

The build is pinned at four levels:

- `engine/stage/pnpm-lock.yaml` — exact npm dependency graph and integrity
  hashes;
- `engine/provenance.json` — Termux `.deb` versions and SHA-256;
- `engine/assemble-engine.mjs` — deterministic payload pruning and patch
  rules;
- `android/app/src/main/assets/dsh-engine.manifest.json` — payload hash
  checked at runtime.

The APK signature and ZIP metadata vary with timestamps, so compare the engine
payload hash and the embedded file list rather than the whole APK when
auditing a build.

## Cleaning

```sh
rm -rf android/app/build dist
rm -rf .cache/stage-dsh engine/cache
```

The last command removes fetched debs and the staged tree; the next build
downloads them again. `android/app/src/main/jniLibs` and
`assets/dsh-engine.tar.gz` are generated and gitignored; regenerate them with
steps 2–4.
