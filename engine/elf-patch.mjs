/**
 * Minimal ELF byte patcher for the vendored Termux runtime.
 *
 * We need Android-safe file names from the jniLibs directory: files must start
 * with "lib" and end with ".so". The vendored SONAMEs (libz.so.1, …) do not, so
 * we rename them in the ELF string table and in every dependent's DT_NEEDED
 * consistently. All replacements are crafted to equal length and applied
 * byte-wise, which is safe for .strtab entries (no offsets change).
 *
 * We also rewrite the Termux build-time RUNPATH (/data/data/com.termux/files/usr/lib)
 * to $ORIGIN so the libraries resolve their siblings inside the app's
 * nativeLibraryDir regardless of where it is unpacked.
 */

/** Exact original → Android-safe replacement (equal or shorter length). */
export const SONAME_RENAMES = new Map([
  ['libz.so.1', 'libz-1.so'],
  ['libcrypto.so.3', 'libcrypto-3.so'],
  ['libssl.so.3', 'libssl-3.so'],
  ['libicui18n.so.79', 'libicui18n-79.so'],
  ['libicuuc.so.79', 'libicuuc-79.so'],
  ['libicudata.so.79', 'libicudata-79.so'],
  ['libc++_shared.so', 'libcpp_shared.so'],
  ['libreadline.so.8', 'libreadline-8.so'],
  ['libncursesw.so.6', 'libncursesw-6.so'],
])

const TERMUX_RUNPATH = '/data/data/com.termux/files/usr/lib'

function replaceAll(buf, from, to) {
  const needle = Buffer.from(from)
  if (to === null) {
    // Caller-validated two-phase replacement (see replaceRunpath).
    throw new Error('internal: use replaceRunpath for the RUNPATH value')
  }
  const replacement = Buffer.from(to)
  if (replacement.length > needle.length) {
    throw new Error(`replacement longer than original: ${from} -> ${to}`)
  }
  let count = 0
  let index = 0
  for (;;) {
    const found = buf.indexOf(needle, index)
    if (found === -1) break
    replacement.copy(buf, found)
    if (replacement.length < needle.length) buf.fill(0, found + replacement.length, found + needle.length)
    index = found + needle.length
    count++
  }
  return count
}

/**
 * Repoint the Termux build RUNPATH to $ORIGIN.
 *
 * The literal byte sequence of the RUNPATH value is NUL-terminated inside the
 * ELF .strtab, but the same bytes also appear inside Node's embedded
 * configure-JSON (`process.config`), where raw NULs are illegal. Occurrences
 * followed by NUL are true strtab entries and are patched to "$ORIGIN" with
 * zero padding; other occurrences are metadata and are blanked with spaces
 * (same length, valid JSON, never used at runtime).
 */
function replaceRunpath(buf) {
  const needle = Buffer.from(TERMUX_RUNPATH)
  const origin = Buffer.from('$ORIGIN')
  const blanks = Buffer.alloc(needle.length, 0x20)
  // Some packages carry a duplicated RUNPATH
  // (/data/data/com.termux/files/usr/lib:/data/data/com.termux/files/usr/lib).
  // Patch the whole colon-separated string, not individual occurrences, so the
  // loader never sees blank directory segments (which produce loud warnings).
  const termuxPath = /^\/data\/data\/com\.termux\/files\/usr\/lib(?::\/data\/data\/com\.termux\/files\/usr\/lib)*$/
  let count = 0
  let index = 0
  for (;;) {
    const found = buf.indexOf(needle, index)
    if (found === -1) break
    const start = Math.max(0, buf.lastIndexOf(0, found) + 1)
    const end = buf.indexOf(0, found + needle.length)
    const value = end === -1 ? '' : buf.subarray(start, end).toString('latin1')
    if (end !== -1 && end - start <= 512 && termuxPath.test(value)) {
      origin.copy(buf, start)
      buf.fill(0, start + origin.length, end)
      count++
      index = end + 1
      continue
    }
    const after = buf[found + needle.length]
    if (after === 0) {
      origin.copy(buf, found)
      buf.fill(0, found + origin.length, found + needle.length)
      count++
    } else {
      blanks.copy(buf, found)
    }
    index = found + needle.length
  }
  return count
}

/**
 * Patch an ELF buffer: rename SONAMEs/DT_NEEDED entries and repoint RUNPATH.
 * @param {Buffer} input
 * @returns {{ bytes: Buffer, renames: number, runpaths: number }}
 */
export function patchElf(input) {
  const buf = Buffer.from(input) // copy
  if (buf.length < 4 || buf.readUInt32BE(0) !== 0x7f454c46) {
    throw new Error('not an ELF file')
  }
  let renames = 0
  for (const [from, to] of SONAME_RENAMES) renames += replaceAll(buf, from, to)
  const runpaths = replaceRunpath(buf)
  return { bytes: buf, renames, runpaths }
}

/** File name for the SONAME a vendored library exposes. */
export function androidSafeName(soname) {
  return SONAME_RENAMES.get(soname) ?? soname
}
