// TLS self-test for the bundled Node runtime.
//
// The engine has no embedded CA list, so account sign-in fails with
// "Network error, please retry" whenever the trust store the app configured is
// missing or ignored. This probe performs a real handshake with the same
// environment the engine runs with: any HTTP status proves that certificate
// verification works.
const target = process.argv[2] || 'https://api.deepseek.com/';
const started = Date.now();

try {
  const response = await fetch(target, {
    redirect: 'manual',
    signal: AbortSignal.timeout(15000),
  });
  console.log(`probe ${target} -> HTTP ${response.status} in ${Date.now() - started} ms`);
  await response.body?.cancel();
} catch (error) {
  const cause = error.cause?.code || error.cause?.message || error.name;
  console.log(`probe ${target} -> FAILED ${cause}: ${error.message}`);
}
