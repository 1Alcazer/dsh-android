#!/bin/sh
# Stage the production @deepseek-ai/dsh dependency tree used to build
# android/app/src/main/assets/dsh-engine.tar.gz.
#
# The lockfile in engine/stage is intentionally committed: it pins every
# transitive package and integrity hash, so repeated builds of the same engine
# version produce the same tree. Refresh it deliberately when bumping DSH.
#
# Requires: node >= 22 and pnpm 10 (corepack enable pnpm).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
STAGE=${STAGE_DIR:-$ROOT/.cache/stage-dsh}

command -v pnpm >/dev/null 2>&1 || {
  echo "pnpm not found. Install it (for example: corepack enable pnpm) and retry." >&2
  exit 1
}

mkdir -p "$STAGE"
cp "$ROOT/engine/stage/package.json" "$STAGE/package.json"
cp "$ROOT/engine/stage/pnpm-lock.yaml" "$STAGE/pnpm-lock.yaml"

echo "staging production DSH tree in $STAGE"
cd "$STAGE"
pnpm install --prod --ignore-scripts --frozen-lockfile

[ -f "$STAGE/node_modules/@deepseek-ai/dsh/package.json" ] || {
  echo "staging failed: @deepseek-ai/dsh is missing from $STAGE/node_modules" >&2
  exit 1
}
echo "staged $(node -p "require('./node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null || echo dsh)"
echo "next: node engine/fetch-debs.mjs && node engine/assemble-native.mjs && node engine/assemble-engine.mjs"
