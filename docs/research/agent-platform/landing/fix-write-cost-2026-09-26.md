---
type: landing
status: committed, unadopted and untested (root runs the list below)
lane: fix-write-cost-opus
created: 2026-09-26
authority: docs/research/agent-platform/one-repl-language-2026-09-25.md §2.10; docs/seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md
---

# Landing: fix-write-cost (2026-09-26)

What exists: every `seon.db/transact!` commits through Datahike's file store, and each read or write
reads its projection from `seon.db/carried-projection`, which is memoized by declaration-attribute
revisions, commit and declaration content. This lane measured where one trivial root run and one
declaration write spend their time. It removed the one input that re-derived the projection without
changing it, and fixed the read-refusal restamp root assigned mid-lane.

## Broken or not reached first

- **The 50 ms target for a trivial MCP run is not reachable from `seon.db`, `seon.schema` or
  `seon.blob`.** On a quiet warm JVM the run costs 122-136 ms. About 75 ms of that is three Datahike
  file-store commits at 24-31 ms each: the dependency's durable write of a 238 KB fused root record, with
  a blob force and a directory force per value. The remaining floor is listed under "Floor" below.
- **The P3a stage table overstates the projection cost.** `call-with-projection-state` (193 ms) and
  `with-publication!` (107 ms) are inclusive wrappers. The first is a dynamic binding around the whole
  submission or turn; the second wraps the settlement's commit. In the P3a window, `declaration-content-key`
  (46 ms) and `arity-content-key` came from other threads writing test branches. seon.profile counts
  every thread in the JVM. In 8 profiled runs, a projection miss (`content-projection`) occurred only in
  runs where concurrent `seon.test*` functions also grew. Quiet runs had 0 misses, 3 transactions, and
  no `declaration-content-key` call.
- **The adoption cost was not measured after the change.** Lanes do not run `bin/seon init`, and default
  runs the committed program: my edits are unadopted. The 18.4 s attributed to `call-with-projection` x86
  is inclusive of `full-source-refresh!` and `development-source-refresh!`, because `refresh-source!`
  wraps its whole body in it (`src/seon/cluster.clj:2979`). No `refresh-source!` change is proposed; the
  per-write projection fix below applies to every row write adoption makes.
- Allocation could not be attributed. JVM-wide allocation grows 1-2 MB/ms while idle, because root's
  concurrent work runs in the same JVM, so the ~500 MB/run delta is not this run's.
- Default was replaced twice during the lane (pids 76020 → 84287 → 87270). The lane did not cause
  either replacement.

## What changed

1. **`609a6365d` schema: the projection no longer reads function source.** `projection-ranges` read
   `[:seon.fn/sym :seon.fn/source]` into `function-source-admissions`. No code has read that value since
   `575b0d710` deleted its last reader (`rg` over src/test/script: no consumer). Because
   `:seon.fn/source` was a projection attribute, every body-only write advanced a declaration revision
   (`datahike.db/speculative-cache-context`). Each such write missed the revision and commit tiers and
   re-derived the whole projection, which is identical to the one before.
   The change deletes the range, the admissions, and their fingerprint section. The fingerprint
   string/version is now v3, so a v2 base recomputes its fingerprint from data
   (`reusable-projection-fingerprint`). The other readers of `projection-attributes` add the program
   attributes themselves (`call_preparation.clj:457`, `sci/eval.clj:3025`), so source edits still
   invalidate what reads source.
2. **`fe38ddc39` db: a read handed a read refusal passes it on verbatim.** Six sites assoc'd their own
   `:seon.db/refused-read-operation` over a refusal: `db`, both sites in `q`, `pull-call`,
   `datoms-call` and `database-view`. They now call one private owner, `refused-read`, which stamps a
   value only when it is not already a read refusal. Issue:
   `docs/seon/issues/a-read-handed-a-refusal-restamps-its-operation.md`. Root should close it after the
   turn-test run.

Net lines: `src/seon/schema.clj` +12 / −67, `src/seon/db.clj` +16 / −6, `test/seon/db_test.clj` +25.
Source net is **−45**.

Deleted: the `function-source-admissions` path in `schema.clj`, about 55 lines:
- the source-row reduce in `projection-from-rows` (31 lines);
- the fingerprint section;
- the constructor, options, change-detection and incremental-fingerprint members;
- the `projection-with-function-contract` update;
- the `:seon.schema/function-source-rows` range.

The optional `:seon.schema/function-source-rows` key stays in
`resources/seon/schemas/seon.schema.edn:64`, which this lane does not own. Nothing supplies it now, and it
can be deleted with that file's next holder.

## REPL proof (default, JVM mode, scratch ns `lane.write-cost`, explicit connection)

- A body-only write before the change: `(d/with db [[:db/add e :seon.fn/source (str src "\n")]])` on
  `seon.blob/digest`, then `carried-projection` of the result took **51.17 ms**. The derived projection
  was `identical?` to the basis's (forms, contracts and fingerprint equal). A second read took 0.2 ms.
- The change's inputs on real data:
  - `projection-from-rows` without the source range gives equal forms, function contracts, schema and
    function admissions, exports, pure predicates and registry keys; 5,253 source admissions were
    carried and never read.
  - The Datahike attribute revisions of the five remaining projection attributes are **equal** before
    and after the body-only write, while `:seon.fn/source`'s revision changed.
  - The write therefore takes the revision tier (`carried-projection`, `db.clj` committed/speculative
    branches), a ~0.2 ms hit instead of 51 ms.
- Read refusal: on the loaded code, `(db/pull (db/q refusal …) …)` names `seon.db/pull`. With
  `refused-read` it returns the q refusal verbatim (`=`) and still stamps a plain error value
  `seon.db/db`. Both inputs and the output validate against `:seon.db/error-result` under the carried
  projection (`projection-validator`, explicit check, because scratch copies are unarmed).
- Cost of `load-projection` from an identical base: 35-42 ms, which is O(program) even when nothing
  differs. That is what every declaration-touching write still pays: a new function, or a spec or schema
  change.

## Measurements (default, warm, 2026-09-26)

Trivial root run, `(seon.cluster.agent/evaluate-source! {… :seon.agent/id "root" … "(+ 1 2)"})`:

| sample | ms |
|---|---|
| quiet, fresh JVM 84287 | 136.8, 148.9, 129.5, 123.4 |
| quiet, later | 121.9-136.1 (3 transactions, no projection miss) |
| with root's concurrent test runs | 146-195 (4-6 transactions and misses in the window) |

Commit floor:

| measurement | ms |
|---|---|
| raw `datahike.api/transact!` of `{:tx-data []}` | 23.2-26.6 (quiet), 28-31 (loaded) |
| the same through `seon.db/transact!` | 24.9-27.3 |
| an idempotent `{:seon.agent/id "root"}` upsert, seon vs raw | ~28 vs ~24-28 |
| `d/with` of the same | 0.05 ms |
| one fsync'd 512 B or 140 KB file | 3-4 ms (size-independent) |

- Seon's writer adds ~1-4 ms per small transaction; `write-report-error` measured 8-9 ms over 3
  transactions.
- Files written per empty commit: 2 files of ~238 KB each (the fused root record grows ~56 B per commit),
  and 8 files when the diff buffer flushes.
- Reads per run: 62 `seon.db/q` and 43 `seon.db/pull` (148-154 `with-declarations` entries, one per read
  plus nested decodes), issued by `seon.turn`/`seon.cluster.agent`. `seon.db/q` costs ~85-160 µs against
  raw `d/q` 3.7 µs and `d/q-with-evidence` 4.2 µs. The outer arming adds nothing measurable (armed
  vs `:malli.instrument/original` equal within noise). The body's armed private checks sum to ~70 µs:
  - `encode-query-request` 18 µs
  - `decode-query-result` 16 µs
  - `query-value-type-error` 12 µs
  - `query-attribute-error` 9 µs
  - `malformed-query-pattern-error` 6 µs
  - `aligned-query-arguments` 3 µs
  - `append-query-evidence!` 2.5 µs
  - `with-declarations` 1.2 µs

  About 10-35 ms per run.
- `seon.schema/declaration-projection` over `packaged-forms` (the build `refresh-source!` makes on every
  publication): 72 ms. It is not the adoption cost.

## Floor for the trivial run, and what moves it (root ruled 2026-09-26)

About 75 ms of the run is three durable commits and 10-35 ms is about 105 armed reads. The rest is
evaluation, print and the Flow hand-off.

### Option 2, coalescing the writer's forces: measured, and refuted as a latency lever. No change; no RESET NEEDED

Root granted this option. I measured it on scratch Datahike file stores created in default's JVM under
`tmp/fix-write-cost-opus/` (deleted afterwards). Each store held a 20,000-entity population (100k datoms)
with `:keep-history? true`, fused roots and `:diff-buf-size 256`.

| store | median serial single-row commit | 24 concurrent transactions |
|---|---:|---|
| `:branching-factor 4096` (current) | 19.1 ms | 34.7 ms, **2 commits** |
| `:branching-factor 512` | 20.8 ms | 59.8 ms, 2 commits |
| 4096 with `:commit-wait-time 5` | 25.9 ms | 37.0 ms, 2 commits |

Where one commit's time goes, measured on default's own head record (241 KB) written into a scratch
Konserve file store:
- with `:sync-blob? true` (the real setting): 8.0-10.8 ms per value;
- with forces off: 1.6-2.1 ms.

So serialization and write cost about 1.6 ms, and the blob force plus the directory force cost about 8 ms.

The Datahike writer commits each value in turn on the per-key filestore path the owner ruled on
2026-09-23 (konserve fork `5b39fdd`, Seon `20d317e19`):
1. the index nodes, written concurrently (`reference-code/datahike/src/datahike/writing.cljc:423-434`);
2. the immutable commit record `[cid db-to-store]`, awaited;
3. only then the mutable branch head `[branch db-to-store]` (`writing.cljc:560-571`).

That is two ordered durable writes of the same record, four forces, about 19 ms, and the ordering is what
keeps branch history intact after a crash.

Conclusions:
- **The writer already coalesces concurrent work.** Its commit loop drains every queued report into one
  commit (`reference-code/datahike/src/datahike/writer.cljc:224-230`, `poll!`), so 24 concurrent
  transactions took 2 commits with no wait. `:commit-wait-time` only delays the next commit
  (`writer.cljc:285`): +6.8 ms serial, with no burst gain.
- **The branching factor does not buy latency.** Force cost is independent of size (a 512 B and a 140 KB
  fsync both measured 3-4 ms), so a smaller root saves at most about 1 ms of serialization per value. It
  made bursts slower (60 vs 35 ms), and it re-opens the publication flush amplification that chose 4096
  (`src/seon/cluster/store.clj:185-191`).
- I therefore changed no configuration and no store creation default. Default needs no reset from this
  lane.
- Fewer forces per commit, with durability unchanged, would need one of these; none is within this grant:
  - a Datahike change that writes the head as a pointer to the already-durable commit record. The record
    must still be written, so this saves only the serialization, not a force;
  - Konserve's ordered batch with one directory barrier, which the owner reverted on 2026-09-23. The
    2026-08-02 issue measured it slower on APFS.

  I recorded these results in the open issue's disposition rather than adding a new mechanism.

### Option 1, folding the close into the settlement: the hunk (turn.clj is held by the seams lane for R1; not applied)

A run of agent-authored source (the reply-size case, `fold-or-close`, `turn.clj:2800-2815`) settles its
last form in `settle-batch!`. The next pass then derives `:close` and commits `close-turn`
(`turn.clj:5081-5100`) as a third transaction. The close can be decided at the writer inside the
settlement instead, on the mid-transaction value after the receipts. This removes one commit and one Flow
pass, about 30-36 ms (`close-turn` measured 32-36 ms inclusive).

Two cases keep the separate `:close` pass:
- an issue-bearing agent, because `plan/run-issue-tests!` must see the settled rows before the close;
- a generated system turn (no `:seon.turn/reply-size`), because its next opening form is generated only
  after this one settles.

```clojure
;; after next-ordinal (turn.clj ~2345)
(defn close-folded-call
  "Close a replied run in the settlement that gave its last form a terminal
  fact, decided at the writer after the batch's receipts. An agent holding an
  issue keeps the separate close pass: its tests must see the settled rows."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [::id ::id] [:seon.agent/id :seon.agent/id]]]
                  :seon.store/transaction-data]}
  [database {run-id ::id agent-id :seon.agent/id}]
  (let [run (db/pull database [:seon.turn/reply-size :seon.turn/closed-tx] [::id run-id])]
    (if (or (not (:seon.turn/reply-size run))
            (:seon.turn/closed-tx run)
            (next-ordinal database run-id)
            (db/q '[:find ?issue . :in $ ?id :where [?agent :seon.agent/id ?id]
                    [?issue :seon.issue/agent ?agent]] database agent-id))
      []
      (close-call database {::id run-id :seon.turn/closed-tx "datomic.tx"}))))

;; settle-batch!: the close precedes plan/settle-call, as in close-turn
         :seon.db/tx-data
         (-> (into (receipt-settle-batch-tx (mapv :seon.turn.loop/receipt prepared))
                   (mapcat :seon.db/tx-data)
                   prepared)
             (into (when-not (some closing-settlement? prepared)
                     (map (fn [[agent-id run-id]]
                            [:db.fn/call #'close-folded-call {::id run-id :seon.agent/id agent-id}]))
                     (distinct (map (juxt :seon.agent/id :seon.turn/id) requests))))
             (into (map (fn [agent-id] [:db.fn/call #'plan/settle-call agent-id]))
                   (distinct (map :seon.agent/id requests))))

;; resume-turn: a folded close reports :closed, so episode-exhausted! and the pass see it
                (if (or (:seon.turn.loop/settled last-prepared)
                        (:seon.turn.loop/undisposed? last-prepared)
                        (:seon.turn/closed-tx (db/pull (:db-after outcome) [:seon.turn/closed-tx]
                                                       [:seon.turn/id run-id])))
                  (report :closed (count gated))
                  (report :released (count gated)))
```

Needed before landing it: `close-call`'s own contract accepts the two-key request (`turn.clj:455-460`),
and the proof on default is a trivial root run showing 2 transactions and a closed run, plus the existing
turn tests. The `(if (some? close-tx) …)` refusal arm in `settle-batch-refusal!` is unchanged: a refused
batch never reached the close.

The third option, `:sync-blob? false`, stays inadmissible (out-of-order loss).

## Out-of-scope finding (filed)

`docs/seon/issues/profile-explanations-charge-other-threads-and-binding-wrappers.md`. seon.profile's
growth window is JVM-wide, a call that completes inside the window contributes its whole duration, and
binding wrappers (`seon.schema/call-with-projection[-state]`, `seon.db/call-with-custody`) enclose whole
operations. They rank first and absorb concurrent threads' work. No earlier issue covered this
(`rg seon.profile docs/seon/issues`).

## Root runs (lanes run no tests)

- `bin/test-check default --test seon.db-test/a-function-body-write-reads-its-basis-projection`
- `bin/test-check default --test seon.db-test/database-operations-preserve-refusals-at-their-declared-boundary`
- `bin/test-check default --test seon.turn-test/an-open-turn-read-refusal-refuses-the-writer-verbatim`
- `bin/test-check default --ns seon.schema-test` (fingerprint v3, incremental equals full build)
- `bin/test-check default --ns seon.db-test`
- `bin/test-check default --ns seon.call-preparation-test` (its snapshot key shrinks by `:seon.fn/source`)
- After adoption: repeat the body-only `d/with` probe above (expect < 1 ms), and one
  `bin/seon init --dev default --changed <one edited function file>` with its profile lines, against the
  11-27 s in the adoption issue.
