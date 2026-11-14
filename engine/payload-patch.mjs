/**
 * Source patches applied to the production DSH dependency tree before packaging.
 *
 * These are not upstream fixes — they are platform adaptations for Android,
 * documented in docs/patches.md. Every patch is applied with strict
 * exact-match checks and is idempotent: a payload that already carries a patch
 * is detected by its marker and skipped; a payload whose files changed shape
 * fails the build with an actionable error instead of silently shipping an
 * unpatched engine.
 *
 * Patch 1 — app-boot internals fallback (Android has no
 * `node-addon-require-builtin-android-arm64` package). The upstream code hard
 * requires the addon to reach Node's internal module loaders; we fall back to
 * `require('internal/...')`, which the official desktop launcher also enables
 * via `--expose-internals`. The Android shell launches the embedded Node with
 * the same flag.
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { globSync } from './glob.mjs'

const MARKER = 'DSH-ANDROID patch: expose-internals fallback'
const ORIGINAL = '\tconst addon = createRequire(import.meta.url)("node-addon-require-builtin");\n\tconst esmModule = addon.requireBuiltin('
const REPLACEMENT = `\t// ${MARKER}\n\tlet addon; try { addon = createRequire(import.meta.url)("node-addon-require-builtin"); } catch { addon = { requireBuiltin: (id) => createRequire(import.meta.url)(id) }; }\n\tconst esmModule = addon.requireBuiltin(`

export function applyAppBootFallback(payloadDir, log = console.log) {
  const candidates = globSync(`${payloadDir}/.pnpm/@deepseek-ai+dsh-app-boot@*/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js`)
    .concat(globSync(`${payloadDir}/.pnpm/@deepseek-ai+dsh-app-boot@*/node_modules/@deepseek-ai/dsh-app-boot/lib/worker/profile-resolution-bootstrap.js`))
  if (candidates.length === 0) {
    // Flat (non-pnpm) layouts: single copy directly under node_modules.
    const flat = [`${payloadDir}/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js`]
    candidates.push(...flat.filter((path) => existsFile(path)))
  }
  if (candidates.length === 0) {
    throw new Error('app-boot internals fallback: no candidate files found — payload layout changed; update engine/payload-patch.mjs')
  }
  let patched = 0
  let skipped = 0
  for (const path of candidates) {
    const text = readFileSync(path, 'utf8')
    const patchedLine = '\tlet addon; try { addon = createRequire(import.meta.url)("node-addon-require-builtin"); } catch { addon = { requireBuiltin: (id) => createRequire(import.meta.url)(id) }; }'
    const looksPatched = text.includes(MARKER)
      || (text.includes('catch { addon = { requireBuiltin:') && !text.includes(ORIGINAL))
    if (text.includes(MARKER)) { skipped++; continue }
    if (text.includes(ORIGINAL)) {
      writeFileSync(path, text.replace(ORIGINAL, REPLACEMENT))
      patched++
      log(`patched ${path}`)
      continue
    }
    if (text.includes(patchedLine)) {
      // Already patched by an older build that omitted the audit marker:
      // add the marker comment without touching the executable code.
      writeFileSync(path, text.replace(patchedLine, `\t// ${MARKER}\n${patchedLine}`))
      patched++
      log(`marked ${path}`)
      continue
    }
    throw new Error(`app-boot internals fallback: pattern not found in ${path} — the dependency changed; review engine/payload-patch.mjs`)
  }
  log(`app-boot internals fallback: ${String(patched)} patched, ${String(skipped)} already up to date`)
  return { patched, skipped }
}

function existsFile(path) {
  try { readFileSync(path); return true } catch { return false }
}
