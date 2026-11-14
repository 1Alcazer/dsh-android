# Third-party notices

DSH Android is an unofficial shell. It redistributes the official DeepSeek
Harness production tree and a pinned Android arm64 runtime built from Termux
packages.

The APK ships this information and the full copyleft texts inside
`assets/licenses/`; the in-app viewer is **About → Third-party licenses and
notices**. The canonical source in this repository is this file plus:

```
android/app/src/main/assets/licenses/NOTICE.txt
android/app/src/main/assets/licenses/GPL-2.0.txt
android/app/src/main/assets/licenses/GPL-3.0.txt
android/app/src/main/assets/licenses/LGPL-2.1.txt
android/app/src/main/assets/licenses/DSH-MIT.txt
android/app/src/main/assets/licenses/Node-MIT.txt
```

## DeepSeek Harness

| Item | Value |
| --- | --- |
| Package | `@deepseek-ai/dsh@0.2.2-rc.2` |
| License | MIT |
| Source | https://github.com/deepseek-ai/deepseek-harness |
| Full text | `assets/licenses/DSH-MIT.txt` |

The APK contains the production npm dependency closure under
`filesDir/engine/node_modules` (and in the build tree at
`engine/cache/payload/engine/node_modules`). Every npm package keeps its own
license file; those licenses apply to the respective packages. The pruned
optional package `@deepseek-ai/libreoffice-kit-wasm` is not redistributed.

## Native runtime

All packages are Termux aarch64 builds. Versions and SHA-256 values are pinned
in `engine/provenance.json`.

| Package | Version | License | Upstream |
| --- | --- | --- | --- |
| nodejs | 26.13.1 | MIT | https://nodejs.org/ |
| libc++ | 30 | Apache-2.0 WITH LLVM-exception | https://llvm.org/ |
| openssl | 3.6.5 | Apache-2.0 | https://www.openssl.org/ |
| c-ares | 1.34.9 | MIT | https://c-ares.org/ |
| libicu | 79.1 | Unicode-3.0 | https://unicode-org.github.io/icu/ |
| libsqlite | 3.54.1-1 | Public domain | https://sqlite.org/ |
| zlib | 1.4 | Zlib | https://zlib.net/ |
| libffi | 3.8.0 | MIT | https://github.com/libffi/libffi |
| bash | 5.3.20 | GPL-3.0-or-later | https://www.gnu.org/software/bash/ |
| libandroid-support | 29-1 | Apache-2.0 + MIT | https://github.com/termux/libandroid-support |
| libiconv | 1.19.1 | LGPL-2.1-or-later | https://www.gnu.org/software/libiconv/ |
| readline | 8.3.6 | GPL-3.0-or-later | https://www.gnu.org/software/readline/ |
| ncurses | 6.5.20250830 | X11/MIT-style | https://invisible-island.net/ncurses/ |
| git | 2.56.1 | GPL-2.0-only | https://git-scm.com/ |
| libcurl | 8.23.0 | curl license (MIT-like) | https://curl.se/docs/copyright.html |
| libnghttp2 | 1.71.0 | MIT | https://nghttp2.org/ |
| libnghttp3 | 1.19.0 | MIT | https://github.com/ngtcp2/nghttp3 |
| libngtcp2 | 1.26.0 | MIT | https://github.com/ngtcp2/ngtcp2 |
| libssh2 | 1.11.1 | BSD-3-Clause | https://libssh2.org/ |
| pcre2 | 10.49 | BSD-3-Clause | https://pcre.org/ |

The Android framework libraries (`libc`, `libdl`, `libm`, `liblog`, and
friends) are provided by the operating system and are not redistributed.

Full texts for the copyleft licenses are shipped:

- GPL-2.0 (`assets/licenses/GPL-2.0.txt`) — git
- GPL-3.0 (`assets/licenses/GPL-3.0.txt`) — bash, readline
- LGPL-2.1 (`assets/licenses/LGPL-2.1.txt`) — libiconv
- Node.js license (`assets/licenses/Node-MIT.txt`) — includes bundled notices

## Corresponding source for GPL/LGPL binaries

The GPL and LGPL binaries were built from the Termux recipes for the exact
versions above:

```
https://github.com/termux/termux-packages/tree/master/packages/<package>
```

Each recipe points at the upstream source tarball and carries the Termux
patches used for Android. Upstream source is also available from the project
URLs in the table.

### Modifications to the binaries

The shipped binaries differ from upstream only by mechanical ELF string
substitutions performed by `engine/elf-patch.mjs` in this repository:

- SONAMEs are rewritten to Android-safe names (`libz.so.1` → `libz-1.so`,
  `libreadline.so.8` → `libreadline-8.so`, and so on);
- matching `DT_NEEDED` entries in dependents are changed;
- the Termux build-time `RUNPATH` is replaced with `$ORIGIN`.

No executable code is changed. The script that performs the substitutions is
part of the corresponding build materials and is included in this repository.
The exact output is pinned by the payload hash in
`android/app/src/main/assets/dsh-engine.manifest.json` and by the SHA-256 values
in `engine/provenance.json`.

### Written offer

For any GPL/LGPL component, the complete corresponding source can be obtained
from the Termux recipe and upstream URLs above, together with the unmodified
build scripts in this repository. If you received the APK without access to
this repository, the same source is available from the Termux package
repository and the upstream projects.

## No warranty

These components are distributed in the hope that they will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See the individual licenses for details.
