# Contributing

Thanks for taking a look at DSH Android. This is a small, dependency-light
project, so the process is deliberately simple.

## Ground rules

- Keep the Android shell **plain Java + platform Views**. No AndroidX, Compose,
  Gradle, or JavaScript shell.
- Do not commit generated artifacts: engine payloads, `jniLibs/`, APKs,
  keystores, or caches (see `.gitignore`).
- Match the naming, structure, and error-handling style of the surrounding
  code.
- Keep user data private: never log API keys, engine tokens, or profile
  contents.

## Building

See [docs/build-from-source.md](docs/build-from-source.md). The short version:

```sh
./scripts/stage-engine.sh
node engine/fetch-debs.mjs
node engine/assemble-native.mjs
node engine/assemble-engine.mjs
./scripts/build-apk.sh
```

The build must stay reproducible from the pinned versions in
`engine/stage/pnpm-lock.yaml` and `engine/provenance.json`.

## Testing

Before opening a pull request:

1. Build the APK and confirm `apksigner verify` passes.
2. Install on an arm64 device (Android 7.0+) and walk through
   [docs/device-testing.md](docs/device-testing.md).
3. Confirm the engine boots, the web UI loads, and safe mode still works.
4. Check that the APK size budget in [docs/size-budget.md](docs/size-budget.md)
   is not exceeded.

If you cannot test on hardware, say so explicitly in the pull request.

## Commits

- One logical change per commit.
- Imperative subject line, 72 characters or fewer.
- Explain *why* in the body when it is not obvious.

## Reporting bugs

Include the app version, Android version, device model, and the relevant
section of the in-app log viewer (Settings → Logs). Redact API keys and tokens.
