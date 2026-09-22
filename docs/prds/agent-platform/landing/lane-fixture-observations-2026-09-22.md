---
type: landing
status: complete
created: 2026-09-22
tags: [agent-platform, tests, fixtures, evidence]
---

# Fixture observation declarations

## Result

The bulk-tier fixture admission refusal at HEAD `264960070` was caused by tests
that reach the canonical published-root helper without a per-test
`:seon.test/fixture-observation`. The runner is unchanged. Each affected test is
retained and now declares the concrete boundary that a branch of the shared base
cannot isolate.

No test in this slice had a subject limited to datoms or history on one branch,
so none was converted to `with-database` or `with-branched-database`. No test was
deleted.

## Per-test disposition

| Test | Disposition | Why a shared-base branch is insufficient |
|---|---|---|
| `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate` | Declare and retain | The subject is retirement of a physical-store scratch branch after a refused publication. |
| `seon.cluster.source-test/incremental-upsert-records-source-identity-on-the-expected-commit` | Declare and retain | The subject compares the physical-store publication head with a second incremental publication. |
| `seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows` | Declare and retain | The subject spans fixture files, a full publication and a later incremental publication. |
| `seon.cluster.source-test/incremental-upsert-derives-scalar-safety-from-the-installed-schema` | Declare and retain | The installed schema on one physical-store publication governs a later publication attempt. |
| `seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` | Declare and retain | The subject compares cluster branch heads retained across a second physical-store publication. |
| `seon.cluster.source-lineage-test/publication-advances-one-branch-and-retires-scratch` | Declare and retain | The subject observes three physical-store publication heads and scratch-branch retirement. |
| `seon.cluster.source-lineage-test/stale-incremental-upsert-preserves-the-newer-publication` | Declare and retain | The subject applies an incremental upsert based on an older physical-store publication head. |
| `seon.cluster.source-lineage-test/failed-and-stale-builds-preserve-the-published-head` | Declare and retain | The subject holds one physical-store publication while a competing publication advances the head. |
| `seon.cluster.source-lineage-test/existing-clusters-remain-on-their-chosen-source-commit` | Declare and retain | The subject pins two cluster branch heads across a second physical-store publication. |
| `seon.cluster.source-evidence-test/latest-test-evidence-survives-rebuilding-from-an-older-base` | Declare and retain | The subject requires evidence to survive a physical-store rebuild and concurrent publication. |
| `seon.reset-edges-test/source-publication-refuses-an-outside-caller-and-keeps-its-head` | Declare and retain | The subject combines fixture files with a physical-store head that must survive a refused second publication. |
| `seon.test.runner-test/the-canonical-platform-tier-preserves-file-local-uncertainty` | Declare and retain | The subject is the whole analyzed manifest and loaded Vars used to classify file-local destructive reach; neither is isolated by a database branch. |

The table includes every test named by
`/var/folders/d6/78_m9wb92wg1f3qbt85s1r400000gn/T/clojure-14940640171669965446.edn`.
The other four declarations cover tests in the same requested namespaces that
reach the same expensive owner and are selected by the named proof.

## Verification

Command (one JVM, HEAD plus only the five test paths):

```text
bin/test-fast --paths test/seon/cluster/source_test.clj test/seon/cluster/source_lineage_test.clj test/seon/cluster/source_evidence_test.clj test/seon/reset_edges_test.clj test/seon/test/runner_test.clj -- seon.cluster.source-test seon.cluster.source-lineage-test seon.cluster.source-evidence-test seon.reset-edges-test seon.test.runner-test
```

Run `b7bb620f7584`, program digest
`45d059456599a0b38b679f952f71e73d44fb9d304dd0b74e4716d6b0dd633c56`:
54 executed, 0 unchanged, 402 assertions, 22 failures and 1 error. The run loaded
all five namespaces, armed 1,692 production-contract wrappers, admitted all 54
tests without a fixture-observation refusal, and executed every declared test.
Thus the changed gate boundary is proven; the named behavioral suite is not green.

Foreign HEAD failures observed by the run include publication semantics already
in the selected sources (injected refusal not observed, transaction count off by
one, equal-digest rebuild skipped, missing retained `tested-branch`), existing
duration-bound failures, reset-edge schema/issue-index drift, and runner-state
failures. The only error was
`source-publication-refuses-an-outside-caller-and-keeps-its-head`, where program
indexing refused a missing `:seon.issue.citation/file`. None is caused by adding
Var metadata, and this slice does not change their mechanisms or bounds.

`bin/seon status` observed the running system before the edit. MCP
`runtime_status` and `eval_clj` were unavailable in this session, so no MCP REPL
evidence is claimed. The named path-scoped run is also the HEAD-load proof for
the changed test namespaces. The orchestrator still owns the cold-gate rerun.

## Scope

Changed paths are the five test namespaces above and this landing note. The
runner, fixture helpers, B4 replacement design and production sources are
unchanged. The two pre-existing modified documentation files and every
pre-existing untracked file were preserved.
