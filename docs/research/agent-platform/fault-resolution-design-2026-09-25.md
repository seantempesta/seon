---
type: design
status: proposed
created: 2026-09-25
scope: A fault is resolved when the program at its origin has changed since its latest occurrence, derived with Datahike as-of rather than written by resolve!.
---
# Derived fault resolution (2026-09-25)

**What exists.** `seon.problems/open-faults` (src/seon/problems.clj:135) keeps each signature
that has no `:seon.error/resolved-tx` after its latest occurrence. Only `seon.problems/resolve!`
(problems.clj:153) writes that fact, and only tests call it. `seon.error/opening`
(src/seon/error.clj:1325) reads the same fact to decide whether a recurrence is a new opening,
which gets a new message id (error.clj:1352). The result is that nothing is ever resolved on default.
**Approach:** use a value Datahike already holds. A fault is resolved when its implicated
declaration's `:seon.program/definition-digest` *now* differs from the digest *as of its latest
occurrence's transaction*. A recurrence under the new code moves that transaction past the
change, so the two digests match again and the fault is open. One comparison answers both
"fixed" and "not recurred since". Nothing new is stored and nothing locks.

## What the occurrence already records (probe, MCP JVM, default, read-only)

- All 18 error rows on `:cluster-default` carry `:seon.instrument/fn`. So do their
  occurrences. It is the failing function, from the contract reporter or `stack-failing-function`
  (error.clj:642-646). No row carries a definition digest or `:seon.error/chain`.
- `:seon.error.occurrence/last-at` is `:seon.db/no-history?` (seon.error.occurrence.edn). Its
  current datom's `tx` is therefore the latest occurrence's transaction.
- `:cluster-default` holds 4,833 `:seon.fn` rows with `:seon.program/definition-digest`. Default's
  program rows derive from files, so a landed fix changes the row.
- Probe: `[?e :seon.error/signature ?sig] [?e :seon.instrument/fn ?f] [?e :seon.error/occurrences ?o]
  [?o :seon.error.occurrence/last-at _ ?tx]` with `(max ?tx)`, then
  `(:seon.program/definition-digest (d/entity (d/as-of db tx) [:seon.fn/sym f]))` compared with
  the same lookup on `db`. It took **28 ms for all 18** signatures.
  - Changed, so the fault would resolve: `0c3d12e2` `seon.problems/failed-tests` (the triage's G,
    fixed by e33009381), `371424a9` `seon.turn/step` (A′, eb14a2f22), and `b184bc84`
    `seon.fn/unresolved-macro-refusal`.
  - Unchanged, so the fault stays open: the live A `d3ef61b6`, B `c0ae227e`, D (`4265ebcd`,
    `39f38724`, `ba8186fe`, `9379e5aa`) and F `0f1602ea`. The same holds for the noise E and C
    and for H `504062b8`.
  - No row, so unknown: `konserve.impl.defaults/get-lock` and `seon.cluster.agent-test/fn`.

**No producer fact is needed for the rule.** The recorded `:seon.instrument/fn` together with
Datahike history (`as-of`) already carries the program identity at the occurrence. Storing a
digest copy would be a stamp.

## The rule

`seon.error/resolution` takes a `[database error-row]` and returns one of three values:
- `nil`, meaning the fault is open;
- `{:seon.error/resolved-by :seon.program/definition-digest, :seon.instrument/fn f,
  :seon.error/digest-then d0, :seon.error/digest-now d1}`;
- `{:seon.error/resolved-by :seon.error/resolved-tx …}`.

It works as follows:
1. `tx` is the maximum `last-at` tx over the row's occurrences.
2. `then` is the digest in `(d/as-of database tx)` and `now` is the digest in `database`, both
   read at `[:seon.fn/sym (:seon.instrument/fn row)]`. When both are digests and they differ,
   the fault is resolved by program change. When either is absent the rule is unknown and the
   fault stays open, because silence is never health.
3. Otherwise the existing `resolved-tx` test applies (error.clj:1319 `resolved?`).

Both readers go through this one predicate, and it is decided at the writer's authority:
- `open-faults` keeps the entries whose resolution is `nil`.
- `opening` returns a key for the opening the recurrence starts. The key is the digest current
  at recurrence (digest-resolved) or the resolution tx id (resolve!-resolved). The message id
  stays `id([signature recipient opening])`. As a result, a recurrence under changed code wakes
  its owner once, and repeats under the same code are deduplicated as they are today.

**Cost.** One `as-of` entity lookup and one current lookup per signature, O(signatures), with
no whole-program work. The writer pays one lookup per commit. The measured cost is 28 ms for 18.
If status needs it, the result can be memoized on `[signature tx now-digest-datom-tx]`, which
are content keys, never a stamp.

## Declared sacrifices, each detected and never locked

- **An off-origin fix is not seen.** When the fix lands in a caller or producer rather than in
  `:seon.instrument/fn`, the fault stays open. D is the example: the contract of `basis-t`
  fails, and the fix belongs in `db/db` or `capture-tx`. Once `:seon.error/chain` is recorded
  (triage §c.4), the implicated set widens to `:seon.instrument/fn` ∪ the first-party functions
  in the root link's `:seon.error/frames`. A change to any of them resolves the fault. Until
  then `resolve!` stays as the explicit statement for off-origin fixes. It is one fact, already
  tested.
- **A false resolution reopens.** A digest can change without fixing the fault, for example
  an unrelated edit to a large `seon.turn/step`. It can also change when a reload ran before
  publication. In both cases the next occurrence reopens the signature and delivers it again.
- **Dependency and anonymous origins** (`konserve…/get-lock`, `…-test/fn`) have no row. They
  stay open until their producer stops them. For C, S1b's release treats its own interrupt as
  exit.

## Test-branch occurrences

The occurrence rows cannot tell test branches from agent branches. 32 of 33 origin branches are
already unlinked. No `:seon.test.run/branch`, `tested-branch` or `:seon.store/branch` datom on
default names any of them (probe; unindexed attributes may be under-reported). No derived rule
can therefore discount them after the fact. The fix is at the producer and is already designed.
Under 59b52dd2e plus lane-fix-fault-delivery item 1, a fault recorded under a test member is
copied to that member's branch, never to `:cluster-default` (fault.clj:143-145, `copy?`). The
member's result then owns it, either consumed or red. When the branch is unlinked the
occurrence goes with it by retention, and the test evidence survives. Requirement 8.4 ("unlinking
never drops an open core fault") keeps holding for agent attempts. This design adds nothing
there. Faults already copied from tests (E `b49c357c`, C) need one root `resolve!` each, or a
default reset. That is root's call, not a lane's: **RESET NEEDED or root resolve**.

## Data flow

```
fault! -> record! -> commit-call (occurrence upsert; last-at datom at tx T)
                         \-> opening = resolution(db, row) -> message id per opening
fix lands -> publication rewrites :seon.fn row digest at T' > T
reader: open-faults(db) = entries where resolution(db,row) = nil
        resolution: digest(as-of T) != digest(now) ? resolved : resolved-tx? : open
recurrence at T'' > T' -> as-of T'' digest = now -> open again, new opening -> one wake
```

## What disappears

- The routine `resolve!` call after a fix, which no one ever makes. `resolve!` remains only
  for off-origin fixes until the chain lands, and is then a candidate for deletion.
- Stale signatures holding health at `:faulted`: 3 of the 18 today, and each future fix at its
  origin.
- Nothing about first-ever deduplication changes. The opening key only gains the digest
  alternative.

## Owner files and slices

| slice | owner file | change | est. lines |
|---|---|---|---|
| 1 | `src/seon/error.clj` | `resolution` (contracted, private readers); `opening` calls it; opening contract `[:or :nil [:int {:min 0}] :seon.program/definition-digest :seon.db/error-result]` | ~35 |
| 1 | `resources/seon/schemas/seon.error.edn` | `:seon.error/resolution` map schema (resolved-by enum of the two attributes, fn, digests, tx) | ~8 |
| 1 | `src/seon/problems.clj` | `open-faults` filters on `error/resolution`; entries carry `:seon.error/resolution` when resolved | ~10 |
| 1 | `test/seon/problems_test.clj` | one regression on a branch from the captured commit: `fault!` on a program function → open; canonical redefinition (agent evaluation path) → resolved-by digest; `fault!` again → open, new message id | ~45 |
| 2 | `src/seon/cluster.clj` (670-708) + `src/seon/problems.clj` render pair | status adds `:seon.dev.mcp/resolved-fault-count`; health uses open only (unchanged); problems AI/HTML list resolved signatures as one line each: signature, fn, `then→now` digest prefixes | ~25 |
| 3 | (after triage §c.4 chain) `src/seon/error.clj` | widen the implicated set to chain root-link first-party functions | ~10 |

Status and MCP: `runtime_status` `open-faults` lists only open signatures, as today, and adds a
resolved count. The MCP branch-errors window (error.clj:1593) already shows new occurrences, so a
recurrence after resolution appears there and reopens. The problems page shows "resolved by
change to `f` (d0→d1)", so a human can see why and check a false resolution.

## Open question for the owner

Should `resolve!` be deleted now, accepting that off-origin fixes (D, H) stay open until the chain
slice lands, or kept until slice 3? The recommendation is to keep it until slice 3: it is one fact
and an explicit statement, never an inference.
