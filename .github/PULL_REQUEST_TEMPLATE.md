<!-- Thanks for contributing! -->

## Description

<!-- What does this change and why? -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change (native ↔ JS contract change → major version)
- [ ] Docs / chore

## Platforms affected

- [ ] Android
- [ ] iOS
- [ ] JS / TypeScript only

## Checklist

- [ ] `npm run codegen` run and `nitrogen/generated` committed (if specs changed)
- [ ] `npm run typecheck` passes
- [ ] `npm run build` passes (bob + config plugin)
- [ ] Tested on a **physical device** for the affected lifecycle scenario (Android swipe-to-kill / reboot, iOS force-quit SLC resume, or offline buffer sync) and pasted the result
- [ ] Native ↔ JS contract kept in sync (Nitro specs / Swift / Kotlin / TS types)
- [ ] If a `StartOptions` default changed: updated in `normalizeStartOptions` **and** mirrored natively
- [ ] If the config plugin changed: re-ran prebuild and verified Info.plist / AndroidManifest output; still idempotent
- [ ] Docs are **honest** about platform limits (no overselling iOS background GPS or OEM-killer survival)
- [ ] Docs and CHANGELOG updated
