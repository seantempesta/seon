---
title: "SP-3 web half handover"
type: reference
status: active
created: 2026-10-08
---

# SP-3 web half handover

The shell half landed in 363a27d54. The web half is uncommitted in the tree; lane impl-sp3 released it.

## Edits left in the tree

- `src/seon/web/jvm.clj`: `capture-body!` drops `max-inline` and always calls `blob/stage-binary!`; staged octets and no `force-blob?` return the inline body, else the blob body with `:seon.blob/staged-write`. Both callers and `fetch`'s binding converted; `SequenceInputStream` and `ByteArrayInputStream` imports dropped.
- `test/seon/web/jvm_test.clj`: suite manifest sets `blob-threshold 8` instead of `max-inline-bytes 8`; the two `apply-config!` overrides now set only `blob-threshold` (8 for search, 4096 for the inline fetch test); `with-file-database` takes an optional seed overlay; new test `a-body-between-the-old-ceilings-is-a-blob` plus a `/between-ceilings` route (6000 bytes).
- `config/default.edn` and `resources/seon/schemas/seon.config.web.edn` keep `:seon.config.web/max-inline-bytes` (restored). The web lane deletes it with the web change.

## Observed

- In the new test, a 6000-byte fetch returns `:my.web.body/bytes 6000` with `:octet-values` (inline) while the db threshold query returns the single value 4096. A direct `blob/stage-binary!` of a 6000-byte `ByteArrayInputStream` in the same fixture returns `:seon.blob/staged-path`, so the fetch path differs from the direct call. Cause not found. The `capture-body!` logic reads correctly, so suspect the stream (`bounded-input` proxy) or which connection/db `fetch` reads.
- Shell lesson that may apply: the shell fixture seeded the db threshold at 4096 and ignored the `blob-threshold` in the effective map, so the threshold must be seeded in the database, not the effective map.
- Debug instrumentation inside the throwaway JVM is unreliable: a `throw` becomes `:my.web/transport-failed`, and `spit` to the repo `tmp/` never appeared. Return data through the test instead.

## Fixture timings (throwaway check JVM)

- `a-body-between-the-old-ceilings-is-a-blob`: 38 to 55 s against the 5 s bound.
- `oversized-bodies-spill-byte-exactly-through-the-blob-tier`: 38.8 s.
- `public-fetch-settles-text-and-binary-body-representations`: ran past the check time limit.
- Full run of the three namespaces: 39 tests, 232 pass, 5 fail, 2 error, 120 s.

## Separate finding

`seon.config-test/the-default-document-has-one-canonical-complete-location` is red at HEAD: `config/default.edn` lacks `:seon.config.blob/compress?`, `compress-min-bytes`, `compress-max-bytes` (commit 89583e65a) and `:seon.config.test/step?` (34dac2655). Adding them is a behavior decision (the blob schema says absent means off, its declared default is true), so the owners of those dials decide.
