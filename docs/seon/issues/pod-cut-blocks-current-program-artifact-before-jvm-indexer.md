---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, testing]
---

# Replace the pod pages producer before the writer gate can run

## Problem

The Group 1–4 pod cut made the protected Group 5 `seon.client` Shadow build
uncompilable before the JVM program indexer exists. That build is still the
only producer of current program initialization pages, so the operator cannot
publish a source-current artifact and `bin/test-writer` correctly refuses to
discover tests.

Removing the client build from readiness now would conceal the missing pages
producer. Reusing the older manifest would conceal deleted program and schema
rows. Neither is a valid seam repair.

## Evidence

A frozen `bin/seon up` on 2026-07-26 successfully built the writer AOT jar and
then stopped at watcher first-flush readiness. The watcher log at
`logs/operator/watcher/c0e27b08-d6f0-4564-be33-21cd3e715263.log` reports:

- `[:client] Build failure: seon.agent not available, required by seon/client.cljs`
- `[:test] Build failure: my.data not available, required by seon/agent/ctx/driver.cljs`

Commit `96ce8cdfc` removes the retired pod `:test` and `:lora-audit` builds and
their default watcher-readiness input. Its focused operator proofs pass:
`seon.dev.config-test` runs 10 tests / 92 assertions and
`seon.dev.process-test` runs 80 tests / 474 assertions, both with zero
failures and errors.

The remaining `:client` failure is not another watcher entry to prune:
`script/seon/dev/program_artifact.clj` calls the protected
`seon.client/index-project!`, `seon.client/rebuild-index!`, and
`seon.client/project-pages`. The surviving `authority-density-client` Shadow
build compiles, but does not produce those program pages.

The final direct gate transcript is
`tmp/plan-evidence/pod-seam-test-writer-2026-07-26.log`.
`bin/test-writer` exits 1 before discovery because
`seon.dev.artifact/current-manifest` rejects the stale manifest. No
authoritative post-cut test count exists yet.

## Owner

The ordered JVM-indexer unit owns the replacement pages producer. Group 5 then
deletes `seon.client` and removes its Shadow build from readiness.

## Acceptance

- The JVM indexer produces current program initialization pages with Shadow
  stopped.
- The protected Group 5 `seon.client` source and its Shadow build are deleted.
- `bin/seon up` publishes a manifest whose input and program-row digests match
  the frozen source.
- `bin/test-writer` discovers and runs its complete suite without a stale
  artifact bypass.
