---
type: report
status: committed, awaiting root runs
created: 2026-09-25
tags: [agent-platform, db, performance, read-evidence, projection]
---

# Lane fix-cost-db-opus: landing (2026-09-25)

`seon.db` owns read evidence and the carried projection. Three per-call costs followed the wrong
input:

- the read-result walk was proportional to protocol checks per node;
- the projection's hit path read an as-of view's declaration index before its memo;
- a stale unnarrowed read's witness scanned the whole since-history index.

Each is fixed at the owner in one slice. No new cache, registry or path was added.

## Broken or open first

1. **Nothing is proven installed.** Hook publication is paused and the load rule applies, so no test
   ran and default (pid 23282) still runs the old code. Root must adopt the three commits, then run
   the commands below.
2. **The digest still costs time in proportion to every captured read's size.** After the walk fix,
   the rest is `seon.schema/canonical-value-string`, at about 120 ms for one form's 468 reads (13.5 MB
   of canonical text). This is out of scope; see finding A.
3. **Turn evidence captures platform-internal reads.** This is out of scope; see finding B. It is why
   a trivial form carries 468 or 7,286 reads.
4. Default's cumulative profile, from the fix-turn-hang drives, shows two more costs:
   - `carried-projection`: 186,064 calls at a 166 µs mean, 31 s in total;
   - `read-evidence`: 946 calls at a 36 ms mean, 34 s in total, 5.2 s at most.

   This lane fixed only the as-of term of the projection cost. The committed and speculative hit
   paths were already 6 µs on default's own values.

RESET NEEDED: no. Default needs adoption of the three commits, or `bin/seon down && bin/seon start`,
to run them.

## Defect 1: read-evidence digests every captured read (commit 901bcaa5b)

- **What the cost followed.** For the real data, I replayed the 468 stored read requests of evaluation
  89952 (`(my.turn/turns-left)`) on default. They are wildcard pulls of every namespace row, 13.5 MB
  of canonical text, and the largest single result is 5.07 MB. The costs:

  | step | time |
  |---|---|
  | replay (Datahike) | 68 ms |
  | `stable-value` walk | 677–709 ms |
  | canonical string | 118 ms |
  | SHA-256 | 8 ms |
  | `read-result-digest` in total | **812 ms per form** |

  The walk asked `db.utils/db?` for every node, which is three `satisfies?` misses
  (`datahike/db/utils.cljc:101`), and then `connection?`.
- **Seam.** Every Datahike value is a record: `DB`, `FilteredDB`, `HistoricalDB`, `AsOfDB` and
  `SinceDB` (`datahike/db.cljc:307-685`). This was verified at the REPL: `record?` is true for all six
  views, including a `with` result. A `Connection` matches no stable shape, so it falls to `:else`.
  Both checks were redundant with `record?` and are deleted.
- **Duplicate collapsed.** Each read was walked twice: once by `stable-read-result` for the retained
  result and once inside `read-result-digest`. The same happened on the replay side of
  `read-evidence-current?`. Now one walk per read feeds both, and `stable-read-result` is deleted.
- **After, measured on the same 468 results in a scratch copy with equal output (`=` true):** the walk
  took 11.9 ms against 677 ms. Replaying and digesting the 62 stale reads of evaluation 88019 took
  133 ms and 731 MB, against 652 ms and 3.7 GB.
- **Regression.** `seon.db-test/the-stable-walk-costs-less-than-encoding-the-read-result`. It uses
  2,000 maps of 11 entries.
  - Before, the walk took 66 ms and allocated 414 MB, against 20 ms to encode. After, it takes
    2.2 ms and 8 MB, so the assertion `walk < encoding` holds.
  - It also asserts that a database value and a connection inside a result still decline.
- **Lines.** db.clj +28/−35, which is a net −7 including the comment. The deleted duplicate is
  `stable-read-result` (5 lines), plus the second walks in `read-result-digest` and in
  `read-evidence-current?`. The test change is +28/−13, and two existing tests were converted to
  `stable-value`.

## Defect 2: declares-program? per rendered node (commit 70d52e826)

- **What the cost followed.** Measured at the REPL on default:

  | value | `declares-program?` | `carried-projection` |
  |---|---|---|
  | committed, `with`, history, since | 2.5 µs | 6 µs |
  | **as-of view** | **1,573 µs** | **1,643 µs** |

  All of the as-of cost is `(first (d/datoms asof :avet :seon.schema/key))`, which merges the temporal
  index. `as-of-key` costs 0.5 µs.
- **Seam.** `carried-projection` creates a cell only under a key whose value declared its program, and
  each key names that value's declaration datoms. The keys are the as-of key (commit plus time point)
  and the commit key. A hit is therefore sound before the check. The function now looks up the as-of
  view key and the commit key first, through core.cache `lookup` plus `hit`, so LRU recency is kept.
  `declares-program?` runs only on a miss. The committed identity is computed once, not twice.
- **After:** a cached as-of read takes 1.2 µs for the lookup plus the builder memo, against 1,643 µs.
  The cell is `identical?` to the one the old path returns.
- **Regression.**
  `seon.db-test/a-cached-as-of-projection-answers-without-reading-its-declarations` asserts that 50
  cached reads cost less than a quarter of 50 first-declaration-datom reads.
- **Lines.** db.clj +30/−16, net +14, of which +5 is the new comment; the other lines were moved, not
  added. Nothing smaller exists: the fix is to reorder the lookups, and no duplicate to delete was
  found (`rg declares-program?` shows one owner).

## Defect 3: read-evidence-changes scans the store per stale read (commit d46bbae77, handed over by fix-cost-capabilities)

- **What the cost followed.** On evaluation 88019, 73 of 480 reads are stale, and 58 of those have no
  attribute set (50 with source `:all`, 8 with plan `:all`). Each got the pattern `{}`, which is
  `(d/datoms (d/since (d/history db) basis) :eavt)`: a single scan takes **1,400 ms and allocates
  2.4 GB** (`post-process-datoms` builds the vector, `datahike/db.cljc:173-191`).
- **Seam, chosen over the handoff's "materialize once per basis":**
  - The first transaction after the basis is itself a changed datom, its `:db/txInstant`.
    `d/seek-datoms db :aevt :db/txInstant (inc basis)` finds it in 0.6 ms and 3.5 KB. It is then
    confirmed in the reader's own since view, in 0.13 ms.
  - Materializing once per basis would still be one store-sized scan per turn.
  - The `d/listen` tx-data would need an atom beside the connection.
- **The per-read `read-evidence-current?` re-check was kept.** It is not a duplicate: `system-plan`
  asks `every?` over the whole vector, which stops at the first false read. Only this re-check
  classifies each read.
- **After, measured on 88019:**
  - the new path took 930 ms and 4.7 GB, and 800 ms and 4.5 GB of that was the per-read re-check
    under the *old* walk still loaded in default;
  - with the defect 1 walk, the 62 stale replays take 133 ms;
  - projected: about 0.4 s, against 27,026 ms and 31.6 GB;
  - it returns the same 14 witnesses, except that the `:all` witness is now the first transaction's
    `:db/txInstant` datom instead of the lowest-entity datom (`[575 :db/cardinality …]`).
- **Regression.**
  `seon.db-test/an-unnarrowed-reads-witness-is-the-first-transaction-after-its-basis`. It takes real
  query evidence and removes its patterns and replay inputs as data. It then asserts three things:
  - the witness is exactly `[tx :db/txInstant tx]`;
  - the read is stale;
  - the call takes less than 100 ms.
- **Lines.** db.clj +18/−8. The added lines are the seek branch, its comment and the docstring.
  Nothing was deleted: `index-pattern-change`'s `:else [:eavt []]` branch is kept, because no other
  producer emits `{}` after this change but a malformed pattern would otherwise throw obscurely.

## Net

| slice | src/seon/db.clj | test/seon/db_test.clj |
|---|---|---|
| 901bcaa5b | +28 / −35 | +28 / −13 |
| 70d52e826 | +30 / −16 | +19 / −0 |
| d46bbae77 | +18 / −8 | +36 / −0 |
| **total** | **+76 / −59, net +17** | **+83 / −13** |

## Root runs (serially, after adopting the three commits)

```
bin/test-check default --test seon.db-test/the-stable-walk-costs-less-than-encoding-the-read-result
bin/test-check default --test seon.db-test/a-cached-as-of-projection-answers-without-reading-its-declarations
bin/test-check default --test seon.db-test/an-unnarrowed-reads-witness-is-the-first-transaction-after-its-basis
bin/test-check default --ns seon.db-test
bin/test-check default --ns seon.read-evidence-test
bin/test-check default --ns seon.directory-test
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
```

Repeat these probes after adoption:

- `(db/carried-projection (db/as-of db (dec (:max-tx db))))`: expect a few µs.
- `(time (db/read-evidence-changes db (:seon.cluster.eval/read-evidence (db/pull db [{:seon.cluster.eval/read-evidence ['*]}] 88019)) 536878215))`:
  expect about 0.4 s and 14 witnesses.

## Out-of-scope findings (named, not edited)

- **A. `src/seon/schema.clj:853` `canonical-value-string` is armed and recurses through its Var.**
  One form's digest makes 539,778 armed calls (873 ms inclusive in default's profile). Its owner could
  recurse through a local fn, the way `stable-value` does. That is the rest of the digest cost.
  Suggested issue: `docs/seon/issues/canonical-value-string-arms-every-recursive-node.md`.
- **B. The turn's read-evidence sink (`src/seon/turn.clj:4753`) binds around the whole
  `evaluate-for-install`.** Platform reads are recorded as the form's dependencies:
  - `(my.turn/turns-left)` carries 468 namespace-row wildcard pulls (evaluation 89952);
  - `(quote 1)` carries 7,286 reads (evaluation 73041).

  Narrowing the capture to the form's own reads would reduce both the digest cost and the number of
  stale reads. That belongs to the turn/sci owner. The existing issue
  `docs/seon/issues/read-evidence-digests-every-captured-read-result.md` should cite it.
- **C.** Relayed from fix-cost-capabilities: the forward `:seon.fn/calls` closure walk is duplicated in
  `render.clj:783-803`, `db.clj:1413-1427` (`builder-digest`), `fn.clj:2288-2302` and `effect.clj`.
  Root should file this.

## Round 2: reds on 6ab6cd5e (commit 775057d2a, tests only)

- **db-test.** I compared 006.txt against the earlier baseline
  `verify-6ab6ae2a-results.txt.d/015.txt`. The only new red member is my own
  `the-stable-walk-costs-less-than-encoding-the-read-result`. Its assertion was wrong:
  `stable-value` keeps the parent's shape with a false flag, so for a nested database or connection
  it returns `[false [{:db nil}]]`, not `[false nil]`. The test now asserts the flag.
- **The 13 `(identical? upstream (read-call))` fails, and the other db-test reds, were already red
  in the baseline.** Their upstream value is a
  `projection-fallback` refusal. Eight baseline reds are now gone.
- **read-evidence-test.** No earlier run of it is on disk; every earlier file lists it as "pending
  (not started)".
  - All four fails come from one cause: line 47,
    `(read-evidence-current? @connection evidence)`, is false on the same database value,
    immediately after the SCI evaluation and before any write. The `(empty? changes)` fail follows
    from it. The old `{}` full scan would also have returned a changed datom once the read was
    judged stale.
  - By reading the code, none of my three db.clj slices changes that answer:
    - 901bcaa5b computes the same `[stable? value]` pair and digest. `record?` covers every
      Datahike value, and a connection falls to `:else`.
    - d46bbae77 does not touch `read-evidence-current?`.
    - 70d52e826 reaches `construction-projection` under the same `declares-program?` condition as
      before.
  - I could not reproduce it at the REPL: `with-database` needs a `seon.test/run` handle, and the
    MCP SCI binding of the sink does not reach the host.
  - The commits in the same adoption that change this evaluation's captured reads are ca17618c0,
    which binds the form's sink inside `evaluate`, and 9481086e0, which records no call-preparation
    reads.
  - The assertion now prints each stale read's request, digest and revision, so one rerun names the
    read. I prepared no db.clj patch because no fault in db.clj is shown.
- Root runs:
  `bin/test-check default --test seon.read-evidence-test/another-recipients-message-keeps-junipers-inbox-current`
  and `bin/test-check default --test seon.db-test/the-stable-walk-costs-less-than-encoding-the-read-result`.

## Round 3: what the diagnostic named (verify-2055 003.txt)

- **The stale reads at line 47 are stale before any write.** Line 47 checks the same database value
  the evaluation read, before the test transacts anything. The stale entries are of two kinds:
  - whole-database reads (`:datahike.read/attributes :all`) on the test branch
    (`:agent-349afc98eb61`, `:agent-8455c556a22e`), which carry no `:seon.db/read-request`, so no
    replay can prove them current;
  - reads on the foreign connection `:cluster-default`, among them `(pull [*] [:seon.fn/sym
    my.agents.root/largest])` and the program-wide `?sym ?digest` query. These are the host
    cluster's program reads, and the test branch's value cannot prove them current.
- **d46bbae77 does not change which reads are stale.** `read-evidence-changes` skips current reads
  first, through the same `read-evidence-current?`, and that function is unchanged.
- **For a stale read with no attribute set, the witness semantics are unchanged.** Before
  d46bbae77, such a read got the pattern `{}`, and `index-pattern-change` answered it with
  `(d/datoms since-history :eavt)`, the first datom of *any* change after the basis. On default
  that witness was `[575 :db/cardinality …]`. After d46bbae77 it is the first transaction's
  `:db/txInstant`. Both are "anything changed since the basis". Matching patterns against a
  once-built since view, or against the tx-data between the bases, gives the same answer for `{}`,
  because the read retained no pattern to narrow it. So line 59 would fail with the old code too,
  once line 47 is false.
- **Owner of the defect.** The fault lies with the producer that captures these reads into the
  form's evidence. The candidates are the sink scope around `evaluate` and its shown result
  (ca17618c0, `src/seon/sci/eval.clj`) and the whole-database evidence that
  `append-database-evidence!` records (`src/seon/db.clj`, near line 501) with plan `:all`. The
  foreign-connection reads would also need to be refused, or recorded with their own connection's
  currency. No db.clj edit was made: the witness is correct for the evidence it is handed.

## Verification limits

- The lane ran no tests, per the load rule.
- clj-kondo gives the same result before and after: 1 stale-cache error (`parser.type/->Variable`)
  and 19 warnings in db.clj, and 0 errors in db_test.clj.
- The new code was proved as scratch copies on default's real data. No Var in default was
  redefined, and the edited namespace was not loaded into default.
- Every probe was bounded to one evaluation's stored reads.
