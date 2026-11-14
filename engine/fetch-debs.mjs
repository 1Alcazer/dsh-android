#!/usr/bin/env node
/**
 * Fetch the vendored Termux aarch64 runtime debs into engine/cache/debs.
 * Verifies sha256 against engine/provenance.json. Safe to re-run.
 *
 * Usage: node engine/fetch-debs.mjs [--cache <dir>]
 */
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync, existsSync, rmSync, cpSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const cacheIdx = args.indexOf('--cache')
const cacheDir = cacheIdx >= 0 ? args[cacheIdx + 1] : join(root, 'engine', 'cache', 'debs')
const localIdx = args.indexOf('--local')
const localDir = localIdx >= 0 ? args[localIdx + 1] : null

const provenance = JSON.parse(readFileSync(join(root, 'engine', 'provenance.json'), 'utf8'))
mkdirSync(cacheDir, { recursive: true })

async function download(url, dest, expectedSha256) {
  if (localDir) {
    const src = join(localDir, dest.split('/').pop())
    if (existsSync(src)) {
      const bytes = readFileSync(src)
      const actual = createHash('sha256').update(bytes).digest('hex')
      if (actual !== expectedSha256) throw new Error(`sha256 mismatch in ${src}`)
      cpSync(src, dest)
      console.log(`copied  ${dest}`)
      return
    }
  }
  if (existsSync(dest)) {
    const actual = createHash('sha256').update(readFileSync(dest)).digest('hex')
    if (actual === expectedSha256) { console.log(`cached  ${dest}`); return }
    console.log(`stale   ${dest} (refetching)`)
    rmSync(dest)
  }
  console.log(`fetch   ${url}`)
  const response = await fetch(url, { redirect: 'follow' })
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`)
  const bytes = Buffer.from(await response.arrayBuffer())
  const actual = createHash('sha256').update(bytes).digest('hex')
  if (actual !== expectedSha256) {
    throw new Error(`sha256 mismatch for ${url}\n  expected ${expectedSha256}\n  actual   ${actual}`)
  }
  writeFileSync(dest, bytes)
  console.log(`ok      ${dest} (${bytes.length} bytes)`)
}

const jobs = Object.entries(provenance.components).map(([name, component]) => {
  const url = `${provenance.baseUrl}/${component.filename}`
  return download(url, join(cacheDir, component.file), component.sha256)
})
await Promise.all(jobs)
console.log(`fetch-debs: all runtime debs verified in ${cacheDir}`)
