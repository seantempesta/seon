---
type: reference
status: read-only research; all citations verified at HEAD 252e6e5bd
created: 2026-09-22
tags: [agent-platform, d1, merge, write-back, datahike-branch, definition-digest]
---

# Merge and write-back — data pack

Facts only. Every `file:line` below was opened at HEAD `252e6e5bd` on branch
`refactor/agent-platform`. Live probes ran read-only on `default` (PID 38968,
root `/Users/sean/src/seon`, custody `(seon.cluster.boot/connection "default")`).

## Summary (ten lines)

1. Datahike already supplies every versioning primitive the design needs:
   `branch!`, `commit-id`, `parent-commit-ids`, `commit-as-db`, `branch-as-db`,
   `since`/`as-of`/`history`, and a real `merge!` that records multi-parent lineage.
2. `merge!` carries lineage ONLY — the caller supplies tx-data; it has no
   expected-basis guard and bypasses the transaction operation's stale-basis check.
3. `since` is NOT an O(delta) index. Measured: 3 datoms in 134.6 ms, 802,730 datoms
   in 144.4 ms — a ~130 ms floor proportional to RETAINED HISTORY, not the delta.
4. Common-ancestor walking is cheap by comparison: the full 244-commit ancestry of
   `cluster-default` read in 759 ms; a fixed-depth `:parents` walk is O(commits).
5. Declaration identity already exists as one vector (`program/identity-attributes`)
   and one digest (`program/definition-digest`, landed `f27b96c19`).
6. Three-way diff has no owner, but its two halves exist: `source/changed-identities`
   does commit-vs-commit identity extraction; `test/changed-since-green` does
   digest comparison across two database values.
7. Merge as a transaction is already prototyped by `source/publish!`: scratch branch
   off an expected commit, reconcile, validate, `force-branch!` with
   `:expected-current-commit`. That is a guarded merge without multi-parent lineage.
8. Write-back does not exist in `src/`. `:seon.fn/file` + `:seon.fn/form-span`
   (half-open UTF-8 bytes) cover 4461 of 4462 functions; `seon.edit` owns lossless
   byte splices. `effect/write-back-adds` is effect provenance, not source export.
9. Whole-file regeneration is NOT possible today: rows retain per-declaration source
   only. Inter-form comments, blank lines, form ordering and `.cljc` arm structure
   are not stored anywhere. Verdict: span diffs, not regeneration.
10. The gate exists (`fn/gate-sets`, `test/select`); conflict facts do not
    (`seon.task` is unimplemented — no schema file, no `seon.task/` reference in src).

## 1. What Datahike gives us

| Fact | `file:line` |
|---|---|
| `branches` — branch-name set from the store roster | `reference-code/datahike/src/datahike/versioning.cljc:182` |
| `branch-history` — backtracks `:meta :datahike/parents`, returns db values in order | `reference-code/datahike/src/datahike/versioning.cljc:191` |
| `branch!` — create branch from commit-id OR branch keyword, CoW secondary indices | `reference-code/datahike/src/datahike/versioning.cljc:212` |
| `force-branch!` — git-reset-like, with `:expected-current-commit` guard and head readback | `reference-code/datahike/src/datahike/versioning.cljc:323` |
| `commit-id` — `[:meta :datahike/commit-id]` | `reference-code/datahike/src/datahike/versioning.cljc:457` |
| `parent-commit-ids` — `[:meta :datahike/parents]` | `reference-code/datahike/src/datahike/versioning.cljc:463` |
| `commit-as-db` — materialize any commit id; must be released | `reference-code/datahike/src/datahike/versioning.cljc:469` |
| `release-materialized-db` | `reference-code/datahike/src/datahike/versioning.cljc:490` |
| `branch-as-db` | `reference-code/datahike/src/datahike/versioning.cljc:499` |
| **`merge!` EXISTS** — "responsibility of the caller to make sure that tx-data contains the data to be merged"; routed through the writer | `reference-code/datahike/src/datahike/versioning.cljc:734` |
| `merge-async!` | `reference-code/datahike/src/datahike/versioning.cljc:750` |
| `merge-writer!` — applies tx-data via `core/with`, stamps `:datahike/merge-parents` | `reference-code/datahike/src/datahike/writing.cljc:860` |
| `merge-db!` writer dispatch | `reference-code/datahike/src/datahike/writer.cljc:426` |
| `merge-db` / `merge-db!` public API | `reference-code/datahike/src/datahike/api/impl.cljc:357` |
| `since` → `SinceDB` wrapper, temporal index required | `reference-code/datahike/src/datahike/api/impl.cljc:148` |
| `as-of` → `AsOfDB` wrapper | `reference-code/datahike/src/datahike/api/impl.cljc:153` |
| `history` → `HistoricalDB` wrapper, idempotent | `reference-code/datahike/src/datahike/api/impl.cljc:185` |
| `SinceDB` is a FILTERING record over `origin-db`, not a delta index | `reference-code/datahike/src/datahike/db.cljc:633` |
| `:db/retract` / `:db.fn/retractAttribute` transaction handling | `reference-code/datahike/src/datahike/db/transaction.cljc:1060`, `:1073` |
| final-report validator hook (`nil` accepts, any value rejects) | `reference-code/datahike/src/datahike/db/transaction.cljc:1206` |
| `transact!` writer op with `:datahike/expected-basis-t` precondition | `reference-code/datahike/src/datahike/writing.cljc:880` |

There is no `db/diff` and no datom-level branch comparator. Datom comparison must
be built from `since`/`history`/`datoms`.

**Probe A — branch roster and ancestry depth** (`read_only true`, `mode jvm`, 759 ms):

```clojure
(let [conn (seon.cluster.boot/connection "default") db (seon.db/db conn) store (:store db)
      cid (datahike.api/commit-id db)
      depth (loop [[c & r] [cid] seen #{} n 0]
              (if (and c (< n 5000))
                (if (seen c) (recur r seen n)
                  (let [raw (konserve.core/get store c nil {:sync? true})]
                    (if raw (recur (concat r (get-in raw [:meta :datahike/parents])) (conj seen c) (inc n))
                        (recur r seen n)))) n))]
  {:branches (datahike.api/branches conn) :branch (get-in db [:config :branch])
   :commit cid :basis-t (seon.db/basis-t db) :ancestry-count depth})
```

```clojure
{:branches ["db" "current-src" "cluster-default"]
 :branch "cluster-default"
 :commit "6ab2b497-5b74-5c0e-97ab-2f78a0df1f6c"
 :basis-t 536871155
 :ancestry-count 244}
```

**Probe B — the cost model for `since`** (556 ms envelope):

```clojure
(let [conn (seon.cluster.boot/connection "default") db (seon.db/db conn)
      t (seon.db/basis-t db) h (datahike.api/history db)
      timed (fn [f] (let [s (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) s) 1e6)]))]
  {:fns (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db)
   :digested (datahike.api/q '[:find (count ?e) . :where [?e :seon.program/definition-digest]] db)
   :spanned (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/form-span]] db)
   :filed (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/file]] db)
   :since (mapv (fn [n] [n (timed #(count (datahike.api/datoms (datahike.api/since h (- t n)) :eavt)))])
                [1 10 100 1000])})
```

```clojure
{:fns 4462 :digested nil :spanned 4461 :filed 4461
 :since [[1 [3 134.571542]] [10 [30 130.282208]] [100 [127123 131.93125]] [1000 [802730 144.430916]]]}
```

**Verdict (1).** Datahike supplies branch, ancestry and multi-parent merge commits;
it supplies no diff. `since` costs a flat ~130 ms floor at this store size
regardless of delta, so "datoms since C" is O(retained history), NOT O(delta);
walking `:parents` to find the common ancestor is the cheap half (244 commits in
759 ms including a konserve read per commit), and the expensive half is the scan —
so narrow by ATTRIBUTE (`:seon.program/definition-digest`) before joining identities,
exactly as `source/changed-identities` and `test/changed-definition-symbols` do.

`:digested nil` is the expected pre-reset observation recorded in
`docs/prds/agent-platform/landing/lane-b1-definition-digest-2026-09-22.md:26-28`
("RESET NEEDED — RESET batch 1"). The digest-based diff cannot be probed live yet.

## 2. Declaration identity and the three-way diff

| Fact | `file:line` |
|---|---|
| `identity-attributes` = `[:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym :seon.fn.file/relative-path :seon.lint/id]` — ONE ordered vector | `src/seon/program.cljc:17` |
| `row-identity` — the `[attribute value]` pair a row carries | `src/seon/program.cljc:312` |
| `definition-digest-excluded-attributes` — `:db/id`, `:seon.fn/file`, `:seon.fn/form-span`, `:seon.fn/calls`, `:seon.fn/references`, `:seon.fn/keywords`, `:seon.fn/writes`, `:seon.fn/call-arities`, `:seon.program/analyzed-source-digest`, `:seon.program/definition-digest`, `:seon.schema.admission/source` | `src/seon/program.cljc:323` |
| `definition-digest` — `(id/digest 64 [(schema/canonical-data-string parts)])` over declaration + optional resolver context | `src/seon/program.cljc:336` |
| `declaration-at` — the declaration whose span contains a byte position | `src/seon/program.cljc:268` |
| `changed-attributes` — owned non-identity attributes whose values differ | `src/seon/program.cljc:987` |
| `exact-replacement-tx-in` / `exact-replacement-tx` — retract-then-assert by identity | `src/seon/program.cljc:1021`, `:1036` |
| `deletion-row` — typed identities removed by one REPL deletion | `src/seon/program.cljc:1043` |
| B1 digest contract (what it hashes / excludes / where computed) | `docs/prds/agent-platform/plan/lane-b1-one-publication-path.md:249` (§2g) |
| Landed at `f27b96c19`; `:seon.program/analyzed-source-digest` unchanged | `docs/prds/agent-platform/landing/lane-b1-definition-digest-2026-09-22.md:12-17` |

Arity is NOT part of identity: `:seon.fn/sym` alone identifies a function;
`:seon.fn/arities` are component rows, and `:seon.fn/call-arities` is explicitly
EXCLUDED from the digest (`program.cljc:330`). No per-arity merge granularity exists.

**Existing partial computations of the three-way diff:**

| Function | What it already does | `file:line` |
|---|---|---|
| `source/changed-identities` | Given a store, a publication report and a prior commit: materializes BOTH commits, builds `tx-data` from `(d/datoms (d/since (d/history after) (db/basis-t before)) :eavt)`, hands it to `fn/report-identities`. This IS the two-commit identity diff. | `src/seon/cluster/source.clj:159` |
| `fn/report-identities` | Identities touched by a report, pulling identity attributes from `db-before` AND `db-after`, so retracted identities survive | `src/seon/fn.clj:3121` |
| `source/deleted-identities` | Identities with historical definitions and no current definition (`not-join` over the source attribute) | `src/seon/cluster/source.clj:202` |
| `test/changed-since-green` | Compares `definition-digests` at an `as-of` basis against the current database and returns symbols whose digest differs — the CHANGED/UNCHANGED half of the classification | `src/seon/test.clj:61` |
| `test/definition-digests` (private) | Reads the stored `:seon.program/definition-digest` for a symbol set | `src/seon/test.clj:644` |
| `test/changed-definition-symbols` (private) | Assertion+retraction identities in one lineage via `db/since` on `db/history` | `src/seon/test.clj:658` |
| `db/value-changes` (private) / `db/apply-diff` | editscript diff/patch of SHOWN VALUES, not database rows — not reusable for row merge | `src/seon/db.clj:3101`, `:3128` |

**Per-identity classification** — the table in
`docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:111-117` is the
declared target. With `digest(X, id)` = the stored digest of identity `id` on commit
`X`, and `⊥` for absent, over common ancestor `C`, agent branch `A`, cluster head `B`:

| `digest(C)` | `digest(A)` | `digest(B)` | Result |
|---|---|---|---|
| d | d | d | unchanged |
| d | e≠d | d | changed-on-A — take A |
| d | d | e≠d | changed-on-B — keep B |
| d | e≠d | e | same edit both sides — not a conflict |
| d | e≠d | f∉{d,e} | **conflict** |
| ⊥ | d | ⊥ | added on A |
| ⊥ | d | e≠d | **conflict** (both added, different content) |
| d | ⊥ | d | retracted on A |
| d | ⊥ | e≠d | **conflict** (delete vs edit) |
| d | present, digest absent | — | typed refusal, never "unchanged" |

**Verdict (2).** Identity and digest both exist and are single-owner. No function
computes the three-way classification; `changed-identities` supplies the two-commit
identity set and `changed-since-green` supplies the digest comparison, so the missing
piece is one pure function over three `{identity → digest}` maps.

## 3. Merge as a transaction

| Fact | `file:line` |
|---|---|
| `db/transact!` — canonical writer; validates against the cluster schema projection, returns full Datahike report for explicit-connection system callers; bounded by `:seon.config.db/write-time-limit-ms` | `src/seon/db.clj:4516` |
| `write-owned-values-error` — validates complete owning values through BOTH sides of the report; EAVT for children, AVET for owners (no pull 1,000 cap) | `src/seon/db.clj:3606`, called `:4040` |
| `fn/reconcile-tx` — builds the reconciliation transaction from rows | `src/seon/fn.clj:3107` |
| `fn/index!` — populates ONE fresh scratch branch from static analysis; an explicit `:seon.source/previous-database` selects IN-PLACE reconciliation; `:seon.reconcile/adopt-identities` narrows `published-index-rows` to exactly the changed identities | `src/seon/fn.clj:3306` |
| `fn/published-index-rows` (`:3141`) — reads compiled rows with PORTABLE program refs and complete owned components (the cross-branch-safe row reader) | `src/seon/fn.clj:3141` |
| `source/publish!` — the existing guarded-merge prototype: scratch branch off `expected-commit`, reconcile, seal, then `force-branch!` with `:expected-current-commit`; refuses `::stale-publication`; retires scratch on every exit | `src/seon/cluster/source.clj:355`, head advance at `:461` |
| `fn/unresolved-callers` — calls into indexed namespaces with no current definition; the surviving-caller refusal | `src/seon/fn.clj:1520` |

**Derived facts are NOT carried; they are recomputed.** `:seon.fn/calls`,
`:seon.fn/references`, `:seon.fn/call-arities` are excluded from the digest
(`program.cljc:327-330`) precisely because they are observations of the analyzed
population. `published-index-rows` re-reads them as portable rows for transfer, and
`index!` with `:seon.reconcile/adopt-identities` reindexes exactly the named
identities (`fn.clj:3330-3333`). So: carried per-row, recomputed per-closure.

**Cost.** README §5 records the incremental publication numbers:
explicit no-change publication 264 ms, repeated docstring edit 2,723 ms
(`docs/prds/agent-platform/plan/README.md:216`). D1 §4 records the whole-graph
static gate at 1,838 of 2,157 tests in 130.58 ms and `commit-as-db` at 1.857 ms
(`docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:285`).
No per-declaration reindex cost is measured anywhere; the 2,723 ms docstring-edit
figure is the nearest upper bound for "one changed declaration, full publication path".

**Verdict (3).** Non-conflicting rows land through `db/transact!` on a branch
connection opened by `store/open-branch!`, with `program/exact-replacement-tx-in`
for replacements, upserts for additions and retraction for deletions —
`publish!` already does exactly this shape on a scratch branch. Datahike's
`merge!` adds only the multi-parent `:datahike/parents` stamp and bypasses the
expected-basis guard (`versioning.cljc:734`; `writing.cljc:860`), so either the
fork gains a guard or the head advance stays `force-branch!` with
`:expected-current-commit` and gives up multi-parent lineage.

## 4. Write-back to files

| Fact | `file:line` |
|---|---|
| `:seon.fn/file` — "Indexed source file shared by function and test declarations; **absent on agent-admitted definitions**" | `resources/seon/schemas/seon.fn.edn:15` |
| `:seon.fn/form-span` — `[:tuple :int :int]`, "**Half-open UTF-8 byte offsets** of this declaration's exact source within its indexed file" | `resources/seon/schemas/seon.fn.edn:16` |
| `:seon.fn/source` — `[:string {:min 1}]`, the exact authored form text | `resources/seon/schemas/seon.fn.edn:186` |
| `:seon.ns/source` — optional, the namespace form's source | `resources/seon/schemas/seon.ns.edn:23` |
| `:seon.fn.file/relative-path` (identity) + `:seon.fn.file/digest` (64-char) — the file row holds a DIGEST, never bytes | `resources/seon/schemas/seon.fn.file.edn:1-4` |
| Tests carry the same two facts optionally | `resources/seon/schemas/seon.test.edn:97-98` |
| `seon.edit/byte-span` — converts Java char indices to the half-open UTF-8 byte span so edits are joinable with indexer spans | `src/seon/edit.clj:14` |
| `seon.edit/form`, `/exact`, `/lines` — lossless splices returning `:seon.edit/source` + span + line range | `src/seon/edit.clj:320`, `:406`, `:468` |
| `seon.edit.jvm/edit*` — stale-source refusal and the filesystem seam | `src/seon/edit/jvm.clj:115`, `:18` (`stale-source`) |
| `my.fs/write!` — per-file atomic write under `:my.fs/precondition` (expected absence or prior digest) | `src/my/fs.clj:57` |
| `effect/write-back-adds` — **effect provenance resolution, NOT source export**: resolves `:seon.effect/form-span` to file and declaration refs via `program/declaration-at` | `src/seon/effect.clj:272` |
| `cluster.export/export!` — copies an open STORE, nothing to do with source files | `src/seon/cluster/export.clj:333` |
| D1's write-back design (staging, file bases, exact spans, reanalysis, integration) | `docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:209-244` (§2d) |

**Coverage.** Probe B: 4462 functions, 4461 with `:seon.fn/file`, 4461 with
`:seon.fn/form-span`. One function has neither — consistent with the schema note
that agent-admitted definitions carry no file.

**What exists in `src/` today for write-back: nothing.** `rg` over `src/` finds no
function that emits source text from program rows; the only `write-back` identifier
is `effect/write-back-adds:272`, which resolves provenance INTO the database.

**Span diffs vs whole-file regeneration.**

| Need | Span diff | Regenerate from rows |
|---|---|---|
| changed identity | replace `[start end)` with `:seon.fn/source` | rewrite whole file |
| added identity | needs an insertion point — **no fact supplies one** | append in row order |
| retracted identity | delete `[start end)` | omit |
| multiple edits per file | apply in DESCENDING span order against original bytes (D1 §2d.2) | n/a |
| inter-form comments (`;` prose between top-level forms) | preserved | **LOST — not stored** |
| blank lines / formatting between forms | preserved | **LOST** |
| top-level `(comment …)` blocks, `def`s the analyzer does not index | preserved | **LOST** |
| form ordering | preserved | must be invented |
| `.cljc` reader conditionals at file level | preserved | **LOST** — the analyzer records `:lang` per entry (`src/seon/fn/analyzer.clj:42`, `:111`, `:172`, `:295`) but there is no stored file-level arm structure |
| `ns` form | untouched unless changed | `:seon.ns/source` exists, so this one part is regenerable |

**Verdict (4). Diffs, decisively.** Whole-file regeneration is not possible at HEAD:
the row population stores per-declaration source and a file DIGEST, never file bytes,
so every byte between declarations — comments, blank lines, unindexed forms, reader
conditional structure — has no representation to regenerate from. The existing
splice owner (`seon.edit`) already speaks the same half-open UTF-8 byte unit as
`:seon.fn/form-span` (`edit.clj:14-24`), which makes span replacement a direct
composition. The one gap span diffs do not close is the INSERTION POINT for a new
declaration; D1 §2d.1 already rules that a genuinely new declaration supplies its
destination and position, and ambiguous namespace-to-file mapping refuses.

## 5. The gate

| Fact | `file:line` |
|---|---|
| `fn/gate-sets` — reaching tests through the shared reverse graph; map arity seeds one frontier with all changed symbols | `src/seon/fn.clj:1506` |
| `test/select` — selection from explicit cluster or published-branch custody; requires indexed symbol edges on `:seon.fn/calls`, `:seon.fn/references`, `:seon.test/reach`; reuses valid recorded green; unknown coverage REFUSES; no filesystem reads | `src/seon/test.clj:830` |
| `test/run` / `run-owned` — the execution entry | `src/seon/test.clj:536`, `:577` |
| D1's gate contract: required tests = static reaching tests ∪ the task's explicit tests; missing coverage refuses by name | `docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:162-174` (§2c.4-5) |
| Measured static gate: 1,838 of 2,157 tests for seed `seon.id/id` in 130.58 ms (2026-09-21, HEAD `209a6652a`) | `docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:59` |
| B4 owns the one execution authority | `docs/prds/agent-platform/plan/lane-b4-tests-in-process.md:73`, `:293` |

The intermediate branch is a legitimate test host today: `test/select` takes the
branch from `(get-in (db/schema-database database) [:config :branch])`
(`src/seon/test.clj:860`) and runs from an explicit database value, and
`source/publish!` already opens a scratch branch connection and transacts on it
(`src/seon/cluster/source.clj:403`).

**Conflict facts.** `seon.task` does NOT exist: no `resources/seon/schemas/seon.task.edn`,
and `rg -l "seon\.task/" src/ resources/` returns nothing. It is B3's declared target
(`AGENTS.md`, "Target, B3/D1/D2"). D1 §2c.2 already specifies the conflict identity:
derived through `seon.id` from the subject, competing definitions and basis, stored
with both source commits and a resolvable subject-local detector, repeating
idempotently (`lane-d1-isolation-merge-writeback.md:149-155`). The simpler
alternative — a merge-report ROW on the intermediate branch — needs no task family
and no detector, but then a conflict is not schedulable work and nothing wakes the
agent; conflicts would only be seen by whoever reads the branch.

**Verdict (5).** Selection and execution exist and already accept an explicit
branch-scoped database value. Conflict representation is entirely missing, and its
cheapest honest form is a conflict row per identity on the intermediate branch
(both competing digests, both source commits, the subject identity), upgraded to
`seon.task` rows when B3 lands so the conflict can actually be assigned.

## 6. Smallest closure, in dependency order

| # | Change | State | Owning spec row |
|---|---|---|---|
| 0 | `:seon.program/definition-digest` present on every declaration row in a live store | **partial** — landed at `f27b96c19`, absent on `default` (`:digested nil`) pending RESET batch 1 | B1 c5 (`lane-b1-one-publication-path.md:372`) |
| 1 | Common ancestor of two branch commits by `:parents` walk | **missing** — primitives exist (`versioning.cljc:463`, `:191`); no Seon function | D1 §2b (`lane-d1-isolation-merge-writeback.md:104`) |
| 2 | `{identity → digest}` map for one commit, narrowed by attribute before identity join | **partial** — `test/definition-digests:644` for symbols only; needs all six identity attributes | D1 §2b; B1 c5 |
| 3 | Pure three-way classification over `(C, A, B)` digest maps → `{:unchanged :take-a :keep-b :conflict :added :retracted}` | **missing** — the one genuinely new pure function; belongs in `seon.program` beside `changed-attributes:987` | D1 §2b ("Keep pure comparison in `program`") |
| 4 | Portable rows for the taken identities | **exists** — `fn/published-index-rows:3141` with an explicit identity vector | D1 §2b |
| 5 | Apply to intermediate branch as one transaction | **exists** — `db/transact!:4516` + `program/exact-replacement-tx-in:1021`, with `write-owned-values-error:3606` and `fn/unresolved-callers:1520` as the deletion/surviving-caller refusals | D1 §2c.3 |
| 6 | Reindex derived facts for the merged closure | **exists** — `fn/index!:3306` with `:seon.reconcile/adopt-identities` | B1 c4, c8 |
| 7 | Select and run the gate on the intermediate branch | **exists** — `fn/gate-sets:1506`, `test/select:830`, `test/run:536` | D1 §2c.4-5; B4 §2 |
| 8 | Advance the cluster pointer against the tested head | **partial** — `force-branch!` with `:expected-current-commit` (`versioning.cljc:323`) is guarded and in use at `source.clj:459`; Datahike `merge!:734` has lineage but no guard | D1 §2c.6-7 ("One writer guard and validator") |
| 9 | Conflict rows on the intermediate branch | **missing** | D1 §2c.2; B3 task family |
| 10 | Write-back: resolve file+span per accepted identity | **partial** — facts present on 4461/4462 functions; the resolution pattern exists at `effect.clj:272` but inverted | D1 §2d.1 |
| 11 | Write-back: group per file, splice in descending span order, write under digest precondition | **partial** — `seon.edit/form:320`, `/exact:406`, `edit.jvm/edit*:115`, `my.fs/write!:57` all exist; no composer over a row set | D1 §2d.2 |
| 12 | Write-back: reanalyze staged bytes and compare digests per identity | **exists as parts** — `fn/analyze-forms:964`, `source/publish!:355`; no staged-checkout caller | D1 §2d.3; B1 c2 |

**The smallest concrete change that unblocks everything above it** is #3 —
one pure function in `seon.program` taking three `{identity → digest}` maps and
returning the classification, with #1 and #2 as its two small readers. Everything
from #4 down is composition of functions that already exist and are already used
by `source/publish!` on exactly this shape (scratch branch → reconcile → validate →
guarded head advance). Nothing in the merge half requires a new mechanism;
the write-back half requires one composer (#11) and no new storage.
