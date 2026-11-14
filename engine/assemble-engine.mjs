#!/usr/bin/env node
/**
 * Assemble the embedded DSH engine payload:
 *   android/app/src/main/assets/dsh-engine.tar.gz
 *
 * The payload is the *production* dependency closure of `@deepseek-ai/dsh`
 * (npm-installed with scripts disabled), with:
 *   1. @deepseek-ai/libreoffice-kit-wasm removed (146 MB). Office document
 *      conversion is therefore unavailable in this build; see
 *      docs/limitations.md before promising it to users;
 *   2. package test suites removed (never loaded at runtime);
 *   3. android platform patches applied (engine/payload-patch.mjs);
 *   4. packed as tar.gz with symlinks preserved (node resolves the pnpm
 *      virtual store as-is).
 *
 * Usage:
 *   node engine/assemble-engine.mjs [--src <node_modules>] [--out <assets dir>]
 *
 * The APK bootstrap extracts the tar.gz on first launch into filesDir/engine.
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, cpSync, readdirSync, statSync,
} from 'node:fs'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyAppBootFallback } from './payload-patch.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
function arg(name, fallback) {
  const index = args.indexOf(name)
  return index >= 0 ? args[index + 1] : fallback
}
const src = arg('--src', join(root, '.cache', 'stage-dsh', 'node_modules'))
const assetsDir = arg('--out', join(root, 'android', 'app', 'src', 'main', 'assets'))
const work = join(root, 'engine', 'cache', 'payload')

if (!existsSync(src)) {
  console.error(`payload source not found: ${src}\nRun scripts/stage-engine.sh first (it produces this with pnpm install --prod --ignore-scripts).`)
  process.exit(1)
}

console.log('assemble-engine: copying production tree (symlinks preserved)…')
rmSync(work, { recursive: true, force: true })
mkdirSync(join(work, 'engine'), { recursive: true })
cpSync(src, join(work, 'engine', 'node_modules'), { recursive: true, verbatimSymlinks: true })

const payloadNodeModules = join(work, 'engine', 'node_modules')

console.log('assemble-engine: pruning…')
const pnpmDir = join(payloadNodeModules, '.pnpm')
const pruned = []
for (const entry of readdirSync(pnpmDir)) {
  if (entry.startsWith('@deepseek-ai+libreoffice-kit-wasm@')) {
    rmSync(join(pnpmDir, entry), { recursive: true, force: true })
    pruned.push(entry)
  }
}
// package test suites — never required at runtime
let removedTests = 0
function removeTests(dir) {
  let entries = []
  try { entries = readdirSync(dir, { withFileTypes: true }) } catch { return }
  for (const entry of entries) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'tests') {
        rmSync(path, { recursive: true, force: true })
        removedTests++
        continue
      }
      if (entry.name === 'node_modules' && dir === join(payloadNodeModules, '.bin')) continue
      removeTests(path)
    }
  }
}
removeTests(pnpmDir)
console.log(`assemble-engine: pruned ${pruned.length} big dependencies, ${String(removedTests)} test directories`)

console.log('assemble-engine: applying platform patches…')
applyAppBootFallback(payloadNodeModules)

// Git is shipped as libgit.so in jniLibs (Android only extracts lib*.so), but
// `git init` still wants the upstream template tree (sample hooks, exclude).
console.log('assemble-engine: staging Git templates…')
const provenance = JSON.parse(readFileSync(join(root, 'engine', 'provenance.json'), 'utf8'))
const gitDeb = join(root, 'engine', 'cache', 'debs', provenance.components.git.file)
if (!existsSync(gitDeb)) {
  throw new Error(`git deb missing: ${gitDeb}\nRun: node engine/fetch-debs.mjs`)
}
const gitTree = join(root, 'engine', 'cache', 'git-tree')
const gitTemplates = join(gitTree, 'data', 'data', 'com.termux', 'files', 'usr',
  'share', 'git-core', 'templates')
if (!existsSync(gitTemplates)) {
  rmSync(gitTree, { recursive: true, force: true })
  mkdirSync(gitTree, { recursive: true })
  execFileSync('dpkg-deb', ['-x', gitDeb, gitTree], { stdio: 'inherit' })
}
const stagedGit = join(work, 'engine', 'git')
cpSync(gitTemplates, join(stagedGit, 'templates'), { recursive: true })
writeFileSync(join(stagedGit, 'VERSION'), `${provenance.components.git.version}\n`)
console.log(`assemble-engine: bundled Git ${provenance.components.git.version} templates`)

console.log('assemble-engine: packing tar.gz…')
mkdirSync(assetsDir, { recursive: true })
const outFile = join(assetsDir, 'dsh-engine.tar.gz')
rmSync(outFile, { force: true })
execFileSync('tar', ['-C', join(work, 'engine'), '-czf', outFile, '.'], { stdio: ['ignore', 'inherit', 'inherit'] })

const bytes = readFileSync(outFile)
const sha256 = createHash('sha256').update(bytes).digest('hex')
const stat = statSync(outFile)
const manifest = {
  schema: 1,
  dshVersion: '0.2.2-rc.2',
  gitVersion: provenance.components.git.version,
  packed: 'tar.gz with symlinks preserved',
  patches: ['app-boot internals fallback (engine/payload-patch.mjs)'],
  pruned: pruned.concat(`${String(removedTests)} test directories`),
  bytes: stat.size,
  sha256,
}
writeFileSync(join(assetsDir, 'dsh-engine.manifest.json'), JSON.stringify(manifest, null, 2))
console.log(`assemble-engine: ${outFile} (${String(stat.size)} bytes) sha256=${sha256}`)
