#!/usr/bin/env python3
"""Package and align an unsigned APK without the Android SDK's glibc zipalign.

The linked resource APK supplies AndroidManifest.xml, resources.arsc and res/.
We add classes.dex, assets/ and lib/<abi>/ with controlled compression:

  * resources.arsc, AndroidManifest.xml and the engine payload stay STORED and
    4-byte aligned (required by the platform for targetSdk >= 30);
  * native libraries are DEFLATED because the manifest extracts them at install
    time (android:extractNativeLibs="true"), which keeps the APK smaller;
  * all remaining entries are DEFLATED.

This is a deliberate stand-in for `zipalign -p 4`: Python's zipfile writes the
local header, and a local extra field pads the entry data to the requested
alignment.
"""
import argparse
import os
import struct
import sys
import zipfile

DEFAULT_TIME = (2020, 1, 1, 0, 0, 0)
PADDING_ID = 0xD935


def pad_for(zf, name, align):
    """Return an extra field that aligns this entry's data to `align` bytes."""
    name_len = len(name.encode("utf-8"))
    # Local header is 30 bytes + name + extra; leave room for the 4-byte header.
    base = zf.fp.tell() + 30 + name_len + 4
    padding = (align - (base % align)) % align
    return struct.pack("<HH", PADDING_ID, padding) + b"\0" * padding


def add_entry(zf, name, data, compress, align=4):
    info = zipfile.ZipInfo(name, date_time=DEFAULT_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    info.create_system = 3
    if not compress:
        info.extra = pad_for(zf, name, align)
    zf.writestr(info, data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--dex", required=True)
    parser.add_argument("--assets")
    parser.add_argument("--libs")
    parser.add_argument("--lib-path", default="lib/arm64-v8a")
    parser.add_argument("--compress-level", type=int, default=6)
    args = parser.parse_args()

    with zipfile.ZipFile(args.base) as base:
        base_entries = {info.filename: base.read(info.filename) for info in base.infolist()
                        if not info.is_dir()}

    if args.out and os.path.exists(args.out):
        os.remove(args.out)

    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_DEFLATED,
                         compresslevel=args.compress_level) as out:
        # Manifest and resources table first, both stored and aligned.
        for name in ("AndroidManifest.xml", "resources.arsc"):
            if name not in base_entries:
                raise SystemExit(f"missing {name} in {args.base}")
            add_entry(out, name, base_entries[name], compress=False)

        for name in sorted(base_entries):
            if name in ("AndroidManifest.xml", "resources.arsc"):
                continue
            add_entry(out, name, base_entries[name], compress=True)

        with open(args.dex, "rb") as handle:
            add_entry(out, "classes.dex", handle.read(), compress=True)

        if args.assets:
            for root, _, files in sorted(os.walk(args.assets)):
                for file in sorted(files):
                    path = os.path.join(root, file)
                    rel = os.path.relpath(path, args.assets).replace(os.sep, "/")
                    with open(path, "rb") as handle:
                        data = handle.read()
                    # Already-compressed payload: storing avoids a pointless pass.
                    stored = rel.endswith(".gz")
                    add_entry(out, "assets/" + rel, data, compress=not stored)

        if args.libs:
            for file in sorted(os.listdir(args.libs)):
                path = os.path.join(args.libs, file)
                if not os.path.isfile(path):
                    continue
                with open(path, "rb") as handle:
                    data = handle.read()
                add_entry(out, args.lib_path + "/" + file, data, compress=True)

    print(f"packaged {args.out}: {os.path.getsize(args.out)} bytes")


if __name__ == "__main__":
    sys.exit(main())
