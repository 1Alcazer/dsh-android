/** Tiny subset of glob: only the `**` worker-dir pattern used for patches. */

import { readdirSync, statSync } from 'node:fs'
import { join, dirname, basename } from 'node:path'

export function globSync(pattern) {
  // Supports: <prefix>/**/<literal> or <segment><star><version><literal>
  // Kept intentionally small — the payload is built from a known pnpm/npm layout.
  const starSegment = pattern.split('/').findIndex((segment) => segment.includes('*'))
  if (starSegment === -1) return existsSafe(pattern) ? [pattern] : []
  const root = pattern.split('/').slice(0, starSegment).join('/') || '/'
  const tail = pattern.split('/').slice(starSegment)
  return walk(root, tail)
}

function walk(dir, remaining) {
  if (remaining.length === 0) return [dir]
  const [head, ...rest] = remaining
  let entries = []
  try { entries = readdirSync(dir) } catch { return [] }
  const matches = []
  if (head.includes('*')) {
    const prefix = head.slice(0, head.indexOf('*'))
    const suffix = head.slice(head.indexOf('*') + 1)
    for (const entry of entries) {
      if (!entry.startsWith(prefix) || !entry.endsWith(suffix)) continue
      const next = join(dir, entry)
      matches.push(...walk(next, rest))
    }
    // Also allow a full wildcard directory segment like "**"
    if (head === '**') matches.push(...walk(dir, rest))
  } else {
    if (entries.includes(head)) matches.push(...walk(join(dir, head), rest))
  }
  return matches
}

function existsSafe(path) {
  try { statSync(path); return true } catch { return false }
}

export function fileExists(path) {
  return existsSafe(path)
}

export { dirname, basename, join }
