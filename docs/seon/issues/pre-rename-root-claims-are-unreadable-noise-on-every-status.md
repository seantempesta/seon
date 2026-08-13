---
type: issue
status: open
severity: friction
tags: [issue, operator, class/n1, wave/operator-lock-scope-follow-up]
---

# Pre-rename root claims are unreadable noise on every `status`

## Problem

`bin/seon status` prints one `record unreadable …: The external claim is
invalid.` line per stale root claim, before any of its actual output. The
claims are not corrupt: they were written before the rename pass and record
the creator as `#:seon.dev.process{:pid … :start-instant …}`, while
`root-claim?` now requires `:seon.boot/pid` / `:seon.boot/start-instant`.

Every lane pays this on every status, and it teaches the reader to skim past
refusals — the diagnostic-quality cost the "loud failures" ethos is meant to
prevent. Most of these roots (`tmp/o4-delegation-diagnosis-root` and
friends) no longer exist.

## Evidence

Observed 2026-08-08 04:31 while proving an unrelated operator fix:

```text
record unreadable /Users/sean/src/seon/data/operator/claims/roots/5387ef50-….edn: The external claim is invalid.
… four more …
orphan seon JVMs: none
```

The named file:

```clojure
#:seon.operator.claim{:root "…/tmp/o4-delegation-diagnosis-root"
                      :creator #:seon.dev.process{:pid 4883
                                                  :start-instant "2026-08-05T15:19:11.630Z"}
                      …}
```

`:start-instant` is also a STRING there, where the current shape is an inst.

## Owner

`resources/seon/operator/state.clj` (`root-claim?`, `read-claim-records`) and
whoever owns claim hygiene for roots that no longer exist.

## Acceptance criteria

- A stale claim for a root that no longer exists is reclaimed rather than
  reported forever — operator state is disposable, so deletion is the
  expected repair, not migration.
- What remains unreadable says WHICH key it did not recognise, so the reader
  can tell a rename leftover from a truncated write.
- `bin/seon status` on a healthy installation prints no refusal lines.

## Independent characterization — 2026-08-10

The same eight warnings remain on the running shared root. All eight files are
readable EDN maps, and all eight fail the current private `root-claim?`
predicate; none is byte-corrupt or a foreign repository's claim.

The records divide into two exact stale shapes:

- seven carry `:seon.operator.claim/repository-root
  "/Users/sean/src/seon"`, but their creator uses the deleted
  `:seon.dev.process/pid` and `:seon.dev.process/start-instant` keys, with the
  start instant stored as `java.lang.String`; current validation requires
  `:seon.boot/pid` plus an `inst?` `:seon.boot/start-instant`;
- `1ff66f77-6d55-351a-a96a-37d657a5d485.edn` is an older destroyed/cleanup
  record with no creator and no repository-root at all.

The managed paths are also mixed, which rules out "all foreign" and "all
already absent" explanations: five still exist and three are absent. Every
path is a historical test/proof root below this checkout's `tmp/` or `target/`.
No record was deleted during verification.

## N1 disposition — 2026-08-12

Still open in the operator-state owner. Reconcile and remove stale claim files
whose recorded roots no longer exist, and make `read-claim-records` return one
flat refusal naming the first unknown/mistyped key for a genuinely unreadable
record. No claim was deleted by this lane.
