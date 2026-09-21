---
type: issue
status: open
severity: blocker
tags: [issue, operator, runtime, test, class/n4, class-kill, wave/class-kill-queue]
---

# Make mutable resources carry their root and lifetime

## Problem

Mutable files, connections, children, executors, and operator roots can be
created outside the scope that settles and releases them. Some operations
reopen borrowed custody; others share an installation or repository path even
after selecting an isolated root. Cleanup and contention are therefore
remembered conventions instead of consequences of construction.

## Evidence

Current open members carry `class/n4` and are derived with
`bin/issues-index --class class/n4`.

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

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.cluster.store-test`, `seon.operator-test`, `seon.dev.fresh-operator-test`). Audited HEAD `7e35df213:src/seon/cluster/store.clj:117-134` derives canonical root/lock paths; `src/seon/operator.clj:928-960` explicitly distinguishes borrowed versus acquired stores and releases acquired custody in finally. Process executors remain explicitly owned by `resources/seon/operator/runtime.clj:11-30`. These owners still exist, with substantial corrections; they do not prove absence of every cross-root leak. Current member claims include `deletable-directories-have-no-claim-or-size-facts.md` and `dependency-cache-lock-wait-has-no-deadline.md`. Need the root-isolation/interruption tests; launching them is prohibited by the owner correction. No resource leak was reproduced in this triage. Retain blocker pending verification of the class's resource-lifetime members.

surface: store-process

## Folded members — 2026-09-21

All four open members are archived (`status: superseded`, `superseded-by` this
note); full evidence stays at `archive/<name>.md`. The membership query no
longer returns them, so the list is stated here.

| Member (in `archive/`) | Claim | Current file:line |
|---|---|---|
| `artifact-releases-the-fence-between-install-and-start.md` | The standalone artifact releases the process-root store after publishing packaged source and reacquires it only at cluster startup, so a competing JVM can win the gap after this one has already mutated the root | `src/seon/artifact.clj:74` (`store/release-store!`), cluster start after it |
| `dependency-cache-lock-wait-has-no-deadline.md` | A paths-only gate waited 178,522 ms for `target/dev-dependency-cache.lock` in `FileChannel.lock` before any coordinator started, so the liveness watchdog did not cover it; tools.build 0.10.5 `process` has no execution deadline | `dev_cache.clj` (`with-cache-lock`), `bin/test` preparation |
| `render-adversarial-roots-outlive-their-experiment.md` | An intentional render-fault root survived at 29.92 GiB (30,366,868 KiB store + a 1,012,267,319-byte log, 132 core-fault records) with no declared lifecycle owner | `tmp/render_adversarial_probe.clj:69-102` |
| `render-live-proof-roots-have-no-lifecycle-owner.md` | A manually created live-proof root survived at 88.09 GiB, written mostly by one complete source publication, with no declaration connecting it to the experiment that could reap it | isolated operator-root lifecycle |

The two `tmp/` roots named above no longer exist; the claim folded here is the
missing lifecycle owner, not those directories. This note's own re-verification
already named `dependency-cache-lock-wait-has-no-deadline.md` as a current
member claim, so the fold preserves rather than changes its subject.
