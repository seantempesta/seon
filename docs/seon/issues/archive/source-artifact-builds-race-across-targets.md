---
type: issue
status: resolved
severity: blocker
tags: [issue, component, flow]
---

# Serialize source artifact builds across targets

## Problem

The default and ACME operators hold target-local lifecycle locks while building
checkout-global outputs. Concurrent source builds can delete and copy the same
writer class directory and standalone jar.

## Evidence

A concurrent `bin/seon restart` and `bin/acme up` both entered
`clojure -T:build writer-uber`. ACME failed with a
`FileAlreadyExistsException` for
`target/database-server-classes/seon/items.cljs`. `seon.dev.artifact/build!`
previously called the fixed-path tools.build target outside any checkout-global
lock, while the two target configurations deliberately select different
`:seon.dev.config/process-dir` values.

Sequential builds from the same source head then published different
timestamp-insensitive writer content digests: default `9c5a36d1…` and ACME
`5247aa97…`. `seon.dev.artifact/digest-jar` hashes sorted entry names and bytes,
not ZIP timestamps, so serialization closes corruption but does not by itself
make repeated `writer-uber` output reproducible.

## Owner

The canonical source artifact transaction in `seon.dev.artifact`, using the
existing kernel-owned file lock in `seon.dev.state`.

The implemented cache identity hashes `build.clj`, `deps.edn`, every copied
`src/` byte, `reference-code/datahike/src-secondary`, the resolved `:writer`
classpath and dependency tree, Clojure CLI identity, and the selected Java
runtime. A cache hit is admitted only when the current jar's sorted-entry
content digest matches the atomically published cache record.

## Acceptance

- Every source target in one checkout derives the same artifact-build lock.
- Preparation, writer/client/bootstrap/CSS builds, output hashing, and manifest
  publication complete before another target enters its source build.
- A deterministic test holds the default build lock and proves ACME cannot
  enter until release.
- One canonical writer build is content-addressed and reused across unchanged
  flavor builds, or the underlying compilation is made reproducible.
- Source, dependency, compiler/runtime, missing-output, invalid-record, and
  corrupt-jar changes rebuild instead of reusing stale bytes.
- A later coordinated default/ACME build completes without fixed-output
  collisions and both flavor manifests identify the same writer content
  digest.

## Resolution

One checkout-derived kernel lock now owns dependency preparation, canonical
writer reuse or rebuild, flavor-local client/bootstrap/CSS builds, output
hashing, and manifest publication. The persisted writer record is admitted
only when its complete source/dependency/toolchain fingerprint matches and the
current jar re-hashes to the recorded content digest.

## Verification

- The focused operator gate passed 16 tests and 54 assertions, including
  adjacent CLI coverage. Deterministic tests prove cross-target exclusion,
  one writer build with equal downstream identity, invalidation, and corrupt
  jar recovery.
- The complete operator gate passed 103 tests and 606 assertions; the writer
  gate passed 68 tests and 388 assertions.
- Concurrent `bin/seon restart` and `bin/acme restart` no longer entered the
  fixed writer output together. Default built the jar; ACME waited and logged
  `reuse canonical database server`.
- Both ready target manifests published writer digest
  `80054020288e0777fb6960edff6ac8f879a4dcfc5e61d34ff924b42bd3318444`.
