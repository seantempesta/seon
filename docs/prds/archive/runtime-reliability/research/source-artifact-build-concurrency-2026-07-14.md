---
type: research
status: active
tags: [research, component, flow]
---

# Source artifact build concurrency

## Dependency ledger

- `io.github.clojure/tools.build` `0.10.5`, selected only by the root `:build`
  alias. Its source is not mirrored under `reference-code/`; this change does
  not alter or infer a tools.build API contract. It treats the existing
  `build/writer-uber` call as the observed fixed-output operation.
- Babashka `1.12.212`, including its bundled `babashka.fs` and JVM interop,
  provides path construction and the existing operator runtime selected by
  `bin/seon` plus `bb.edn`.
- `seon.dev.state/with-lock` is the maintained kernel `FileLock` mechanism.
  `test/seon/dev/process_test.clj` already proves exclusion and cleanup.
- `seon.dev.artifact/build!` is the single source artifact transaction.
  `seon.dev.cli/reconcile-development!` calls it while holding a target-local
  `:stack` lock.

## Observed failure

Default and ACME correctly use separate lifecycle/process coordinates, so their
`:stack` locks do not exclude one another. Both builds nevertheless invoke
`build/writer-uber`, whose current `build.clj` deletes and recreates
`target/database-server-classes` and
`target/seon-database-server-standalone.jar`. A concurrent run failed copying
`seon/items.cljs` with `FileAlreadyExistsException`.

The collision is broader than the writer call: the source transaction also
publishes checkout-global bootstrap and CSS outputs. Locking only
`writer-uber` would leave output validation and hashing able to observe a later
target's replacement bytes.

## Implemented constraint

`seon.dev.artifact/build!` derives a repository-wide lock coordinate from
`:seon.dev.config/root`, ignoring the target-local process directory. The lock
brackets preparation, every source build step, output validation and hashing,
and the flavor-specific atomic manifest publication. Packaged consumers remain
read-only and do not acquire the source-build lock.

This deliberately serializes source builds in one checkout. It does not merge
the targets' runtime locks, Shadow caches, client build ids, outputs, process
state, databases, or endpoints.

## Canonical writer reuse

The lock alone did not reuse the first target's writer jar. Sequential
same-head builds published default writer digest `9c5a36d1…` and ACME digest
`5247aa97…`. These are `digest-jar` values over sorted jar entry names and
bytes, deliberately omitting ZIP timestamps, so the difference is
compiled/package content rather than only archive metadata.

The implemented cache derives one input digest from exactly the local values
that `build/writer-uber` copies or selects:

- all `src/` bytes and `reference-code/datahike/src-secondary`;
- `build.clj`, including the AOT namespace set and tools.build operations;
- `deps.edn`, including `:writer`, `:build`, maintained SHAs, versions, paths,
  exclusions, and JVM flags;
- resolved `clojure -Spath -M:writer` and `clojure -Stree -M:writer` output;
- `clojure -Sdescribe`, which identifies Clojure CLI and config selection; and
- the selected `JAVA_CMD` plus its `-version` output.

Writer dependency prep still runs first because a cold git dependency may need
its prep output before `-Spath` resolves. Under the checkout lock, a matching
cache record is accepted only if the canonical jar exists and its current
timestamp-independent content digest matches the recorded output digest. A
miss warms the writer classpath, runs `writer-uber` once, verifies the jar, and
atomically publishes `{input-digest, writer-digest}`. Later default or ACME
builds reuse that jar while still building their own client plus the maintained
bootstrap and CSS outputs.

## Evidence and live proof

The deterministic operator regressions start default and ACME build thunks
with different process directories, hold the first checkout lock, and prove
the second cannot enter until release. They then prove two unchanged target
builds execute `writer-uber` once and receive the same published writer digest.
Local source, `deps.edn`, compiler/runtime identity, and corrupt-jar changes
each force another build.

A coordinated live default/ACME restart admitted only the default target to
`writer-uber`. ACME waited on the checkout lock, logged `reuse canonical
database server`, and both ready manifests published the same writer content
digest, `80054020288e0777fb6960edff6ac8f879a4dcfc5e61d34ff924b42bd3318444`.
Both targets then served valid root/data gzip SSE frames and remained healthy.
