---
type: issue
status: resolved
tags: [issue, cljs, flow]
---

# Changed-test omitted the program-source artifact identity

## Failure

The changed-test fast path launched the immutable Shadow test bundle directly
but supplied only `SEON_CONFIG` and `SEON_RENDER_STRICT`. Every
`seon.index-core-test` use of `seon.client/index-core!` then failed with:

```text
The admitted program-source artifact identity is absent.

```

The published test manifest already carried the exact program-source path and
SHA-256 digest. `bin/test-cljs` exported both before launching Bun, while
`seon.dev.changed-test/run-javascript!` discarded them. The tests were not
stale; they correctly required the same admitted artifact used by the pod.

## Resolution

`seon.dev.changed-test/test-process-environment` now projects the selected
manifest's program-source path and digest into the direct Bun process. It
resolves the relative member beneath the configured checkout root and rejects
an incomplete identity. Ambient program-source variables cannot override the
selected manifest.

The full one-shot fallback still delegates artifact publication and export to
`bin/test-cljs`; it passes no duplicate identity.

## Evidence

- Focused operator proof passes 17 tests/52 assertions. It covers manifest
  projection, explicit config/render overrides, stale ambient identity
  replacement, and fail-closed omission.
- `bin/test-cljs --test=seon.index-core-test` passes 17 tests/119 assertions.
- The exact previously failing fast path,
  `bin/seon test changed --path test/seon/index_core_test.cljs`, passes the
  same 17 tests/119 assertions with zero failures and zero errors.
