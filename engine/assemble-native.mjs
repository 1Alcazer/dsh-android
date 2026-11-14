#!/usr/bin/env node
/**
 * Extract the vendored Termux debs into android/app/src/main/jniLibs/arm64-v8a.
 *
 * Every library is copied under an Android-safe RUNPATH ($ORIGIN) so the whole
 * set resolves inside the app's nativeLibraryDir at runtime. The Node binary is
 * written as libnode.so (Android only unpacks files matching lib<name>.so).
 *
 * Usage:
 *   node engine/assemble-native.mjs [--debs <dir>] [--out <jniLibs dir>]
 *
 * When <debs> is missing, engine/fetch-debs.mjs is run first.
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  mkdirSync, readFileSync, writeFileSync, rmSync, copyFileSync, existsSync, chmodSync,
} from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { SONAME_RENAMES, androidSafeName } from './elf-patch.mjs'
import { patchElf } from './elf-patch.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
function arg(name, fallback) {
  const index = args.indexOf(name)
  return index >= 0 ? args[index + 1] : fallback
}
const debsDir = arg('--debs', join(root, 'engine', 'cache', 'debs'))
const outDir = arg('--out', join(root, 'android', 'app', 'src', 'main', 'jniLibs', 'arm64-v8a'))

const provenance = JSON.parse(readFileSync(join(root, 'engine', 'provenance.json'), 'utf8'))
const term = (...parts) => parts.join('/')

if (!existsSync(debsDir) || !existsSync(join(debsDir, Object.values(provenance.components)[0].file))) {
  console.log('assemble-native: debs missing, fetching…')
  execFileSync(process.execPath, [join(root, 'engine', 'fetch-debs.mjs'), '--cache', debsDir], { stdio: 'inherit' })
}

const extractDir = join(root, 'engine', 'cache', 'extracted')
rmSync(extractDir, { recursive: true, force: true })
mkdirSync(extractDir, { recursive: true })

for (const [name, component] of Object.entries(provenance.components)) {
  const deb = join(debsDir, component.file)
  if (!existsSync(deb)) throw new Error(`deb missing: ${deb} (run engine/fetch-debs.mjs)`)
  const actual = createHash('sha256').update(readFileSync(deb)).digest('hex')
  if (actual !== component.sha256) throw new Error(`deb checksum mismatch: ${deb}`)
  const dest = join(extractDir, name)
  mkdirSync(dest, { recursive: true })
  execFileSync('dpkg-deb', ['-x', deb, dest], { stdio: 'inherit' })
}

const usr = name => join(extractDir, name, 'data', 'data', 'com.termux', 'files', 'usr')

/** file under a component's usr tree → target jniLibs name (pre-rename). */
const FILES = [
  { from: `${usr('nodejs')}/bin/node`, to: 'libnode.so' },
  { from: `${usr('zlib')}/lib/libz.so.1`, to: SONAME_RENAMES.get('libz.so.1') },
  { from: `${usr('c-ares')}/lib/libcares.so`, to: 'libcares.so' },
  { from: `${usr('libsqlite')}/lib/libsqlite3.so`, to: 'libsqlite3.so' },
  { from: `${usr('libffi')}/lib/libffi.so`, to: 'libffi.so' },
  { from: `${usr('openssl')}/lib/libcrypto.so.3`, to: SONAME_RENAMES.get('libcrypto.so.3') },
  { from: `${usr('openssl')}/lib/libssl.so.3`, to: SONAME_RENAMES.get('libssl.so.3') },
  { from: `${usr('libicu')}/lib/libicudata.so.79`, to: SONAME_RENAMES.get('libicudata.so.79') },
  { from: `${usr('libicu')}/lib/libicui18n.so.79`, to: SONAME_RENAMES.get('libicui18n.so.79') },
  { from: `${usr('libicu')}/lib/libicuuc.so.79`, to: SONAME_RENAMES.get('libicuuc.so.79') },
  { from: `${usr('libc++')}/lib/libc++_shared.so`, to: SONAME_RENAMES.get('libc++_shared.so') },
  // Real GNU bash for DSH's bash tool. Android only extracts lib*.so from an
  // APK, so the shell binary is shipped as libbash.so and linked into the
  // app-private bin directory at boot.
  { from: `${usr('bash')}/bin/bash`, to: 'libbash.so' },
  { from: `${usr('libandroid-support')}/lib/libandroid-support.so`, to: 'libandroid-support.so' },
  { from: `${usr('libiconv')}/lib/libiconv.so`, to: 'libiconv.so' },
  { from: `${usr('libiconv')}/lib/libcharset.so`, to: 'libcharset.so' },
  { from: `${usr('readline')}/lib/libreadline.so.8`, to: SONAME_RENAMES.get('libreadline.so.8') },
  { from: `${usr('ncurses')}/lib/libncursesw.so.6`, to: SONAME_RENAMES.get('libncursesw.so.6') },
  // Git for the agent's version-control workflows. The helper executables
  // (git-remote-http, templates) are packed separately into the engine payload.
  { from: `${usr('git')}/bin/git`, to: 'libgit.so' },
  { from: `${usr('git')}/libexec/git-core/git-remote-http`, to: 'libgit-remote-http.so' },
  { from: `${usr('libcurl')}/lib/libcurl.so`, to: 'libcurl.so' },
  { from: `${usr('libnghttp2')}/lib/libnghttp2.so`, to: 'libnghttp2.so' },
  { from: `${usr('libnghttp3')}/lib/libnghttp3.so`, to: 'libnghttp3.so' },
  { from: `${usr('libngtcp2')}/lib/libngtcp2.so`, to: 'libngtcp2.so' },
  { from: `${usr('libngtcp2')}/lib/libngtcp2_crypto_ossl.so`, to: 'libngtcp2_crypto_ossl.so' },
  { from: `${usr('libssh2')}/lib/libssh2.so`, to: 'libssh2.so' },
  { from: `${usr('pcre2')}/lib/libpcre2-8.so`, to: 'libpcre2-8.so' },
]

// The lib*.so.X entries in debs are symlinks to lib*.so.X.Y real files; dpkg-deb
// preserves them — resolve to the real file when the entry is a link.
import { lstatSync, readlinkSync } from 'node:fs'
function realFile(path) {
  let current = path
  for (let i = 0; i < 8; i++) {
    if (!existsSync(current) || !lstatSync(current).isSymbolicLink()) return current
    current = join(dirname(current), readlinkSync(current))
  }
  throw new Error(`symlink chain too deep at ${path}`)
}

rmSync(outDir, { recursive: true, force: true })
mkdirSync(outDir, { recursive: true })

const manifest = []
for (const { from, to } of FILES) {
  const source = realFile(from)
  if (!existsSync(source)) throw new Error(`missing payload file: ${from}`)
  const { bytes, renames, runpaths } = patchElf(readFileSync(source))
  const target = join(outDir, to)
  writeFileSync(target, bytes, { mode: 0o755 })
  chmodSync(target, 0o755)
  const sha256 = createHash('sha256').update(bytes).digest('hex')
  manifest.push({ file: to, sha256, bytes: bytes.length })
  console.log(`jni  ${to.padEnd(20)} ${String(bytes.length).padStart(9)} bytes  (${renames} renames, ${runpaths} runpaths)`)
}

writeFileSync(join(root, 'engine', 'cache', 'jni-manifest.json'), JSON.stringify({ files: manifest }, null, 2))
console.log(`assemble-native: wrote ${manifest.length} libraries to ${outDir}`)
console.log('assemble-native: rows are byte-patched; SONAMEs are Android-safe; RUNPATHs point to $ORIGIN')
