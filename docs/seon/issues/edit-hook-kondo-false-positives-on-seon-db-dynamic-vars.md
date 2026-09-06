---
type: issue
status: open
severity: friction
tags: [issue, tooling, edit-hook, clj-kondo]
---

# Edit hook blocks test edits on kondo false positives for real dynamic vars

## Problem

Observed 2026-08-14 night: any edit to `test/seon/sci/eval_test.clj` or
`test/seon/db_test.clj` was blocked by error-level `unresolved-var` findings
for `seon.db/*conn*`, `seon.test-support/event-backstop-seconds`, and
`seon.db/*read-evidence-sink*`, although all three Vars existed. The findings
preceded the attempted edits, so unrelated additions were refused. Two class
regressions had to land in fresh namespaces instead of beside their sibling
tests.

## Evidence

The stale-cache cause was confirmed on 2026-09-05 when
`seon.fn/build-manifest` consistently refused the current
`src/seon/print.cljc:997` with `seon.print/fit-text is called with 5 args but
expects 4`. The current definition and call both have five arguments, and a
standalone cache-disabled clj-kondo analysis was clean.

The two cache entries disagreed:

- `.clj-kondo/.cache/v1/clj/seon.print.transit.json`, written 2026-08-17,
  records fixed arity 4 and identifies its source as `<stdin>`;
- `.clj-kondo/.cache/v1/cljc/seon.print.transit.json`, written 2026-09-05,
  records the current fixed arity 5.

This is not contamination from retained test checkouts.
`seon.fn/source-roots` is exactly `["src" "test"]`, and `source-files`
resolves those roots against the repository before enumerating canonical
`.clj` and `.cljc` paths (`src/seon/fn.clj:22,72-82`). The 48 old
`tmp/test-runs/**/src/seon/print.cljc` copies are outside that census.

clj-kondo explains the deterministic false result. Its cache synchronization
loads a CLJ dependency namespace from `:clj` and then `:cljc`
(`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:174-188`). Resolution
for a CLJ call in a CLJC file prefers the `:clj` definition over the CLJ arm
of the `:cljc` definition
(`reference-code/clj-kondo/src/clj_kondo/impl/utils.clj:520-533`). The old
four-argument stdin entry therefore wins even though the current CLJC entry
was refreshed later.

Commit `9502dc9da` stopped synthesized stdin analysis from writing this shared
cache by passing `:cache false` for `"-"` paths
(`src/seon/fn/analyzer.clj:152-157`). The poisoned CLJ entry predates that
repair, and the repair did not invalidate existing entries. Deleting that one
ignored cache file is the bounded publication falsifier for this diagnosis.

## Owner

`seon.fn.analyzer` owns the distinction between synthesized and canonical
analysis and the cache directory handed to clj-kondo. clj-kondo's existing
`cache-version` is the literal storage-format directory `v1`
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:21,195-201`); it is not
derived from Seon's analyzer policy and cannot invalidate pre-`9502dc9da`
stdin entries by itself.

The smallest durable repair is to remove cache entries whose stored source is
`<stdin>` at this existing analyzer cache boundary before cached canonical
analysis. That directly removes the state which the current writer policy
forbids, retains canonical first-party and dependency cache entries, and needs
no second cache-version counter. Disabling the complete cache would also be
correct, but would discard its cross-process dependency-analysis value.

## Acceptance

- No cache entry produced from synthesized stdin can participate in a later
  canonical source analysis, including an entry left by an older checkout.
- A CLJC namespace with a stale opposite-language stdin entry resolves calls
  against its current canonical definition.
- Error-level unresolved-var and invalid-arity findings still block real
  source defects.
- Retained directories outside `src` and `test` remain absent from the
  manifest census.
