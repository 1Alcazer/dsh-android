#!/usr/bin/env node
// Reproducible check of the DeepSeek account sign-in start.
//
// Boots the bundled DSH engine with a given trust store, drives the account
// plugin over the engine's own HTTP API and reports the phase the sign-in
// attempt reaches:
//
//   waiting-browser + authorizeUrl  -> the platform was reached, sign-in works
//   failed / no-response            -> the engine cannot reach the platform
//
// Without a usable trust store the engine fails with
// "UNABLE_TO_GET_ISSUER_CERT_LOCALLY" and the app shows "Network error,
// please retry" while signing in.
//
// Usage (on the device, after engine/assemble-engine.mjs and
// engine/assemble-native.mjs have produced the payload and the native libs):
//
//   DSH_NATIVE=<dir with libnode.so and friends> \
//   DSH_PAYLOAD=<extracted payload dir> \
//   node scripts/verify-account-signin.mjs <ca-bundle.pem|-> [label]
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';

const NATIVE = process.env.DSH_NATIVE || '';
const PAYLOAD = process.env.DSH_PAYLOAD || '';
const WORK = process.env.DSH_WORK || '/data/data/com.opencode.android/cache/tmp/dsh-verify';
const bundle = process.argv[2] || '-';
const label = process.argv[3] || bundle;

if (!NATIVE || !PAYLOAD) {
  console.error('set DSH_NATIVE (directory with libnode.so) and DSH_PAYLOAD (extracted engine payload)');
  process.exit(64);
}

const DSH = `${PAYLOAD}/node_modules/@deepseek-ai/dsh/lib/bin.js`;
const env = {
  PATH: '/system/bin:/system/xbin',
  HOME: `${WORK}/home`,
  TMPDIR: `${WORK}/tmp`,
  DSH_HOME: `${WORK}/home`,
  LD_LIBRARY_PATH: NATIVE,
  LANG: 'C.UTF-8',
  LC_ALL: 'C.UTF-8',
  NO_COLOR: '1',
  TERM: 'dumb',
};
if (bundle !== '-') {
  env.SSL_CERT_FILE = bundle;
  env.NODE_EXTRA_CA_CERTS = bundle;
}

fs.rmSync(WORK, { recursive: true, force: true });
for (const dir of ['home', 'tmp', 'ws']) fs.mkdirSync(`${WORK}/${dir}`, { recursive: true });

const child = spawn('/system/bin/linker64',
  [`${NATIVE}/libnode.so`, '--expose-internals', DSH, 'web', '--no-open', '--host', '127.0.0.1', '--port', '0'],
  { cwd: `${WORK}/ws`, env, stdio: ['ignore', 'pipe', 'pipe'] });

let ready = null;
const onLine = (chunk) => {
  for (const line of chunk.toString().split('\n')) {
    if (!line.trim()) continue;
    console.log(`[engine] ${line}`);
    const match = line.match(/dsh web: (https?:\/\/\S+)/);
    if (match && !ready) ready = match[1];
  }
};
child.stdout.on('data', onLine);
child.stderr.on('data', onLine);

const deadline = Date.now() + 90_000;
while (!ready && Date.now() < deadline) {
  await new Promise((resolve) => setTimeout(resolve, 250));
}
if (!ready) {
  console.error('engine did not start');
  child.kill('SIGKILL');
  process.exit(1);
}

const url = new URL(ready);
const origin = `${url.protocol}//${url.host}`;
const launch = await fetch(ready, { redirect: 'manual' });
const cookie = (launch.headers.getSetCookie ? launch.headers.getSetCookie() : [])
  .map((value) => value.split(';')[0]).join('; ');
console.log(`engine ready at ${origin} (session cookie ${cookie ? 'issued' : 'missing'})`);

async function rpc(endpoint, payload) {
  const body = { type: 'client-request', rpcId: randomUUID(), method: endpoint, payload };
  const response = await fetch(`${origin}/api/${endpoint}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify(body),
  });
  return response.text();
}

const client = { version: '0.2.2-rc.2', locale: 'en', timezoneOffsetSeconds: 0 };
await rpc('account/startSignIn', { args: { client, callbackOrigin: origin, loginSource: 'web' } });

let reached = false;
for (let attempt = 0; attempt < 30; attempt++) {
  await new Promise((resolve) => setTimeout(resolve, 1000));
  const state = await rpc('account/getState', { args: {} });
  console.log(`[state] ${state.slice(0, 400)}`);
  if (state.includes('authorizeUrl')) {
    reached = true;
    break;
  }
  if (state.includes('"phase":"failed"') || state.includes('"phase":"expired"')) {
    console.log(`RESULT(${label}): sign-in FAILED -> ${state.slice(0, 300)}`);
    child.kill('SIGKILL');
    process.exit(3);
  }
}

if (!reached) {
  console.log(`RESULT(${label}): sign-in did not settle in time`);
  child.kill('SIGKILL');
  process.exit(2);
}

// The platform redirects the popup straight to the loopback callback. The
// engine's session cookie is SameSite=Strict, so that cross-site request does
// not carry it; the route still has to be reachable without a cookie.
const anonymous = await fetch(`${origin}/oauth/callback?code=test&state=test`, { redirect: 'manual' });
console.log(`[callback] without session cookie -> HTTP ${anonymous.status}`
  + (anonymous.status === 401 ? ' (cookie-gated, sign-in could not complete)' : ' (route reached)'));

console.log(`RESULT(${label}): sign-in reached the browser stage with an authorize URL`);
child.kill('SIGKILL');
process.exit(anonymous.status === 401 ? 3 : 0);
