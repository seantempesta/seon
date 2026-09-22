---
type: landing
status: implemented; focused proof partially blocked by foreign publication state
created: 2026-09-23
tags: [agent-platform, a2, datahike, registry, blob]
---

# A2 c9/c11 — registry and blob

## Slice

Base with a published test graph: `394b58f09`. Datahike was inspected at pinned
gitlink `cc2b2bc7`; Konserve was inspected at pinned gitlink `8cd9144f`.

- `registry/retire-branch!` now lets Datahike decide main-branch and active-
  connection refusal at `versioning.cljc:286-288,310-315`, translates those two
  dependency types once, and treats `:branch-does-not-exist` as the idempotent
  success. Its public name and explicit Seon refusal rules are unchanged for the
  candidate-lifecycle release caller.
- `commit-present?` and both commit-existence pre-reads were deleted. `branch!`
  translates the dependency's absent source for creation; `commit-as-db` decides
  absence for reset. Reset's process-exclusivity check remains before
  `force-branch!`.
- Registry docstrings now cite the branch roster at `versioning.cljc:182-189`
  and `delete-branch!` at `:279-320`.
- `stored-binary` is the one Konserve callback-value unwrapper. Both
  `stored-digest-and-size` and `read-octets` use it inside their `bget` callbacks.
  Streaming verification and `bget-range` remain unchanged; no store-base
  accessor was added.
- The UTF-8 round-trip regression now corrupts the stored bytes under the digest
  and positively asserts the named digest mismatch.

Changed production size: registry `-20` lines net; blob `+10` lines net (the new
helper includes its complete Malli contract). Test change: blob `+9` lines net.

## Evidence

`bin/seon status` observed `default` pid 51528 healthy before work. It was never
opened, reloaded, reset, stopped, or used for a probe.

Raw JVM load on the shared tree:

```text
clojure -M -e "(require 'seon.cluster.registry 'seon.blob)"
exit 0, 14.5 s
```

Focused command, exactly as assigned:

```text
bin/test-fast --paths src/seon/cluster/registry.clj src/seon/blob.clj \
  test/seon/cluster/registry_test.clj test/seon/blob_test.clj -- \
  seon.cluster.registry-test seon.blob-test
```

On the shared tree the runner selected published graph
`d73e0a6ce0420c376f5fbff73acdd18fb03740cdf299b981b2035932af50642e`,
reported it eight commits behind the then-moving HEAD, and armed 1,696
contracts. The real scratch file-store cases proved:

- unavailable commit translation passed;
- main-branch retirement translated Datahike
  `:cannot-delete-main-db-branch` to the declared Seon rule;
- connected-branch retirement translated Datahike
  `:branch-has-active-connection` to the declared Seon rule;
- scratch branch retirement and repeated absent retirement passed;
- UTF-8 blob round-trip, stable address, and injected digest mismatch passed.

The command did not produce a green namespace tally. The three-red questions:

1. `retiring-one-cluster-reclaims-only-its-own-tail` completed its assertions
   but took 5,919.53 ms against its existing 5,000 ms bound. This is surviving
   GC behavior outside c9; the bound was not widened.
2. Binary blob fixtures were refused before the blob subject because their
   anonymous `:seon.config.eval.result/blob-threshold` row was classified as an
   unowned entity by the concurrently changing program/schema publication.
   This is a foreign fixture/publication boundary, not wanted c11 behavior;
   no schema or fixture outside the owned files was edited.
3. A detached `394b58f09` snapshot with the repository `reference-code` and
   published-base directory linked reached armed-contract initialization, then
   recording refused because that detached root has no published `:current-src`.
   Per the lane rule, no base was fabricated and no gate was run.

The shared run was interrupted only after the repeated foreign binary-fixture
refusals were established; its owned test subprocess and snapshot were cleaned
by `bin/test-fast`. The detached fallback worktree was removed after recording
this evidence.

## Limits

This proves the raw JVM definitions load and exercises the c9/c11 public
behaviors on scratch file stores. It is not a complete focused green because
the published program authority did not match the moving shared tree. The
orchestrator still owns the cold gate, platform proof, and a rerun after the
foreign publication/schema cut is coherent.
