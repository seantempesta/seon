---
type: issue
status: open
severity: blocker
tags: [issue, architecture, process, testing, class-kill]
---

# Make mutable resources carry their root and lifetime

## Problem

Mutable files, connections, children, executors, and operator roots can be
created outside the scope that settles and releases them. Some operations
reopen borrowed custody; others share an installation or repository path even
after selecting an isolated root. Cleanup and contention are therefore
remembered conventions instead of consequences of construction.

## Evidence

Nine open issues span 2026-08-02 through 2026-08-11:
[[artifact-releases-the-fence-between-install-and-start]],
[[concurrent-provider-calls-fail-with-a-closed-response-body]],
[[deletable-directories-have-no-claim-or-size-facts]],
[[dependency-class-cache-prepare-races-concurrent-jvm-launches]],
[[flow-monitor-test-resources-outlive-their-cleanup-scope]],
[[jvm-operator-work-takes-the-installation-lock-for-one-root]],
[[render-adversarial-roots-outlive-their-experiment]],
[[render-live-proof-roots-have-no-lifecycle-owner]], and
[[test-runner-cleans-a-worker-root-while-kondo-is-still-writing]].

The archive repeats resource reacquisition and shared-root contention on
2026-08-07, 2026-08-08, and 2026-08-11 in
[[archive/refork-held-a-store-across-the-arm-that-released-it]],
[[archive/init-force-destroys-the-branch-then-refuses-its-own-second-store-open]],
[[archive/an-isolated-operator-root-locks-the-shared-repository-root]], and
[[archive/export-fallback-reopens-an-already-connected-branch]].

## Owner

The root/resource constructors and their operation-specific completion values.

## Acceptance

- A constructor returns one ownership value carrying the selected root,
  resource, every owned child completion, and release operation.
- Mutable paths derive only from that root; only immutable inputs may be
  shared across roots or workers.
- Borrowers receive custody and have no reopen operation; cleanup is reachable
  only after all owned completions settle.
- Cross-root and interrupted-operation properties prove no contention, leak,
  early deletion, or second acquisition can be constructed.
