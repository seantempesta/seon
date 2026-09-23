---
type: landing
status: landed
created: 2026-09-23
tags: [agent-platform, seon.db, bounded-execution, M4, N3]
---
# Lane m4-write-bound: every write is bounded (M4 prerequisite)

Extends README §4 row 1.6 and `plan/lane-flow-owns-running-machinery.md` §2 N3
("The blocking, bounded now", R6) and "The dependency between N1–N4 and the error
route": the system-write bound in `seon.db` precedes any synchronous
`seon.fault/fault!` caller.

## Design (written before code)

**Seam.** Datahike (`reference-code/datahike`, pinned `131ca6360`) already makes
admission and acknowledgement one awaitable value. `datahike.writer/transact!`
(`src/datahike/writer.cljc:393-417`) and `merge-db!` (`:429-444`) create a
`throwable-promise` and return it at once; admission — `dispatch!` →
`LocalWriter/-dispatch!`'s `put!` into the transaction queue, whose completion
callback resolves a closed queue as `writer-shut-down-error` (`:46-55`) — runs
inside the go block that later delivers that promise (`:398-402`). The promise's
timed deref is `CompletableFuture.get(ms)` (`src/datahike/tools.cljc:93-107`). So a
deadline started immediately before the dispatch call covers admission AND
acknowledgement with one timed deref; no second timer, no watcher thread, no
Seon-side queue.

**The bypass (parent `a118b34b4`).** `seon.db` is the only Datahike write owner in
`src/` (no `d/transact`, `d/merge-db!` or `d/load-entities` outside `db.clj`). In
`db.clj` there is exactly one write path, `transact-call`'s dispatch
(`db.clj:4367`). It bypassed the bound for every write whose provenance names no
agent:

| file:line (parent) | bypass |
|---|---|
| `src/seon/db.clj:4283-4309` `agent-provenance?` | decided "system write → no bound" from `:seon.db/user` (retired ruling 1r) |
| `src/seon/db.clj:4358-4363` | `write-time-limit-ms` is nil unless `agent-write?` |
| `src/seon/db.clj:4371-4374` | nil limit → unbounded `(deref pending)` — publication, adoption, reseed, boot recovery, collection, fault/error writes, every explicit-connection system caller |
| `src/seon/db.clj:4367-4369` | the clock `started` began AFTER dispatch; harmless today (dispatch returns at once) but the deadline did not formally include admission |

`fresh-connection` (`db.clj:117-121`, `d/create-database`) is a test-generator
constructor, not a transaction path.

**Change, at the one owner.** Delete `agent-provenance?`; every write takes the
branch's `:seon.config.db/write-time-limit-ms` (smallest asserted value, else the
declared default); start the clock before dispatch and deref for the remaining
deadline. The refusal is the existing typed outcome-unknown value
(`:seon.db/transaction-outcome-unknown true`, schema `resources/seon/schemas/seon.db.edn:310-311`),
unchanged: no retry, no replacement write, the transaction handed back.

**Bound as declared data.** The existing dial
`:seon.config.db/write-time-limit-ms` (`resources/seon/schemas/seon.config.db.edn:2`,
default 600,000 ms). Reason: it is the only declared database-write deadline; the
600 s default came from `docs/seon/issues/the-thirty-second-write-bound-fails-program-publication-under-load.md`
(an 87k-datom publication exceeded 30 s under load), so a system-wide bound at that
default does not refuse publication. A caller needing a shorter deadline (N2's
nested stop cleanup) passes it later as the smaller of the two; not built here.

**Cost.** O(1) per write: one `System/nanoTime`, one subtraction, one timed
`CompletableFuture.get`. The dial read (`d/datoms :aevt` on one attribute) was
already paid per write for agent writes; it is now paid for all, proportional to
the number of asserted dial values (one per configured branch), never the store.
Nothing proportional to the program or store.

**Simplest alternative considered.** (a) Leave system writes unbounded and bound
only `fault!`'s own call — rejected: the PRD requires the bound at `seon.db`, and a
per-caller bound is the "fix at the site" habit. (b) A request-carried bound
(ruling 1r's "the request carries the obligation") — more machinery, no present
caller; the dial suffices for M4. (c) This slice: delete the provenance branch.

## Changed paths and lines

- `src/seon/db.clj`: +21 −49 (net −28). `agent-provenance?` deleted; `transact-call`
  bounds every write; `transact!` docstring corrected.
- `test/seon/db_test.clj`: +9 −99 (net −90). The agent-only bound test becomes the
  class regression `a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound`
  (no agent provenance; real Datahike writer via `datahike.writer/create-writer`
  on the canonical fixture branch, held by a `CountDownLatch`; no mocks, no sleep).
  Deleted as tests of deleted machinery / a retired assumption:
  `a-system-write-carries-no-per-write-bound`, `the-write-bound-derives-from-the-writes-own-provenance`,
  `a-write-user-datahike-cannot-parse-names-no-agent`. The regression's target write
  changed from a bare `{:seon.agent/id …}` row (refused by the write-report validator
  once the writer released, so it never settled) to a `:seon.fn/doc` datom on
  `[:seon.fn/sym 'seon.db/transact!]`.
- Contracts: no `:malli/schema` added or changed (one removed with its function);
  nothing new to compile against the packaged projection.

## Evidence (default pid 90963, load average 14–15)

- Lint: `clj-kondo --lint src/seon/db.clj test/seon/db_test.clj` → 0 errors (19
  pre-existing warnings), 438 ms.
- Adoption: `bin/seon init --dev default --changed src/seon/db.clj test/seon/db_test.clj`
  refused five times (concurrent publications, then another lane's uncommitted
  `render.clj`, a `seon.db` dependent), then succeeded; the test file was adopted
  again after the target change (10 s). Filed:
  `docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`.
  Adoption verified at the REPL: `(resolve 'seon.db/agent-provenance?)` → nil;
  the new test Var resolves.
- `bin/test-check default --policy named --test seon.db-test/a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound`
  → run `62323a02cee6`, pass 7 / fail 0 / error 0, passed? true (47.2 s wall).
  Earlier runs: `96f9455829e8` red (the bare agent row never settled; fixed as
  above), `727fb8df98a2` error from another lane's reload of `seon.instrument`
  mid-run, two admission refusals ("Test run admission refused inconsistent
  evidence.").
- `bin/test-check default --policy named --test seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`
  → run `f282e641fd07`, pass 6 / fail 0, passed? true (21.7 s).
- Hot-path probe, the same form on parent and on this commit: 22 sequential
  `seon.db/transact!` of `[[:db/add [:seon.fn/sym 'seon.db/transact!] :seon.fn/doc (str "probe " i)]]`
  on a disposable `d/branch!` of default (connected, then released and deleted),
  first two dropped:

  | code | first write | median | min | max |
  |---|---|---|---|---|
  | parent `a118b34b4` (loaded before adoption) | 417 ms | 74.9 ms | 64.7 ms | 88.9 ms |
  | this commit | 439 ms | 66.7 ms | 47.0 ms | 124.3 ms |

  No slowdown (median −11 %). The ~70 ms per ordinary write is itself
  `write-report-error`/`write-owned-values-error` validation, outside this slice.

## Timings over one second

| operation | wall | justification / disposition |
|---|---|---|
| test-check, one member (4 runs) | 17.6–47.2 s | DEFECT: runner overhead around a sub-5 s body; filed `docs/seon/issues/a-one-test-in-process-test-check-takes-twenty-to-fifty-seconds.md` |
| adoption attempts 1–3 | 24.8–65.6 s | DEFECT: whole-program source refresh before refusing; filed with the adoption note |
| adoption of the test file | 10 s | same class; filed |
| 22-write probe | 2.0 s | 22 sequential writes × ~70 ms validation each |

## Verification limits

- The bound fires in the regression at 25 ms on the canonical fixture branch; the
  600 s default on default is not exercised by a real hang.
- No `seon.fault/fault!` caller exists yet (M4 step 0 is the next slice); this
  proves only the `seon.db` seam the PRD requires first.
- Platform and integration tiers not run (plan §6: once per cut).
- No RESET NEEDED.

## Follow-up A: the lookup-ref pull happy path (orchestrator follow-up)

Closes `docs/seon/issues/a-lookup-ref-pull-sorts-the-whole-installed-schema-on-the-happy-path.md`.
`lookup-ref-error` and `attribute-installed?` read Datahike's installed schema
(`dbi/-schema`) directly; the sorted whole-schema observation (proportional to 2,429
attributes) is built only on refusal. Cost now O(1) per lookup ref.

- Hot-path timing, same `pull-probe` form parent vs fix: lookup-ref `seon.db/pull`
  606 µs → 67.6 µs; `attribute-installed?` 429 µs → 6.3 µs; `d/pull` 6.3–7.8 µs.
  Target within 2× of `d/pull` not met; the remaining gap is shared by the eid path
  (60 µs) and unattributed (see the issue's resolution).
- Adoption: 10 attempts; nine refused "The source head changed before publication."
  (14–80 s each) while other lanes adopted; the tenth succeeded in 34 s. Same class
  as `docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`.
- Reaching tests: `bin/test-check default --policy incremental --changed seon.db/lookup-ref-error --changed 'seon.db/attribute-installed?'`
  → run `b2f5b25ee8cc`, executed 51, reused 17, pass 573, fail 34, error 34, 121 s.
  Every red is another class, none reads a lookup ref: fixture agent rows missing the
  required `:seon.agent/branch` (my.plan/note/message/agent tests), a missing
  `:seon.program/definition-digest`, and "Host-bound declaration
  seon.cluster.source/dependency-digests must change through the loaded source files"
  (my.program/turn tests). No lookup-ref or attribute-installed message appears in the
  output. The 121 s run is the runner defect filed above.

## Follow-up B: nil source in read currency (orchestrator follow-up)

Closes `docs/seon/issues/read-evidence-currency-hands-a-nil-source-to-index-evidence.md`
(its turn.clj backstop items stay with their owner). `read-evidence-current?` takes
the index check only when a source was found; `:all` plans replay.

- Reproduced before the fix (exact stored message, 2.7 ms throw); fixed: `true` by
  replay, 5.5 ms. The source-present path gains one `when`; not separately timed on
  the parent.
- Adoption `bin/seon init --dev default --changed src/seon/db.clj test/seon/db_test.clj`:
  first attempt, 22 s (whole-program refresh; same filed class).
- `bin/test-check default --policy named --test seon.db-test/a-retained-read-whose-plan-names-no-source-is-answered-by-replay --test seon.db-test/retained-read-evidence-invalidates-only-on-a-depended-attribute`
  → run `deb20e255a9d`: new regression green; the neighbour errors at its bare
  `{:seon.agent/id …}` fixture write (required `:seon.agent/branch`), a cross-file
  class filed as `docs/seon/issues/fixture-agent-rows-lack-the-required-agent-branch.md`.
  One earlier attempt was refused at admission ("inconsistent evidence"), 12 s.
