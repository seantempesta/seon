---
type: research
status: proposed
date: 2026-10-08
lane: cost-cards
---

# Cost design, merged: five patterns, one mechanism, the CO cards (cost-cards, 2026-10-08)

**What exists.** Three independent designs of the same waste: Opus's
[whole-program-per-call.md](whole-program-per-call.md) and [focused-instrumentation.md](focused-instrumentation.md),
Astra's [cost-design-astra.md](cost-design-astra.md); Astra's measured check owners
([check-alloc-astra.md](../check-alloc/check-alloc-astra.md), revision reuse landed `44cce8fe4`); the request-cost reply
([feedback-design.md](../feedback/feedback-design.md), slices 2-5 pending). **Data flow** of the two operations this
note ranks by: eval request → recording transaction → test step (`seon.turn/test-step`: proofs, then
`seon.test/changes` → `seon.test/run` → admission → world → members) → reply; and a check (`seon.test/run`: selection,
`seon.test/admit-run`, `seon.test.runner/reach-digests`, `seon.test/clean-request-handle`). **Principal approach**
(owner 2026-10-08: "find root causes and patterns rather than playing whack a mole"): name the five patterns the
measurements fall into, give each its one design change, and cut the cards from that change, ordered by measured
waste per ordinary eval request and per test check.

Owner rulings this note follows: keep every contract and make the checks cheap ("We own the malli specs so we can
control how the checks happen and we can make them cheap", answered); algorithms first; small slices with
measured-cost proofs.

## 0. Measurement status: everything predates rip-context

Every number below, in the sources and in this lane's probes, was taken on default PID 78227 (archive `ade6e3059`,
started 11:49:51Z), **before** lane rip-context's uncommitted deletion of the system turn, prompt selection
(`seon.cluster.prompt`), context capture and compaction lands. A measurement lane re-measures the core operations
after it lands. Classification per number:

- **Disappears with rip-context:** the model pass's context projection (W-8, ~750 MB per model pass, 350-405 MB of
  it the projection: `seon.cluster.prompt/prompt`, `seon.render.walk/history`, `declared-sources`), system turns
  (`seon.turn/system-turn`, `virtual-turn!`, `generate-turn`), compaction and capture. None of these is on the
  `:ping` or check path below, so no CO card targets them.
- **Unknown share; re-measure first:** the `:ping`'s 48 `seon.db/q` / 83 MB in `seon.db/vector-query-call`
  (feedback-design §1) and its 128-138 reads. Some may be capture or prompt reads; CO-12 is a diagnosis that runs
  only on the post-rip measurement.
- **Survives (owners untouched by rip-context: `seon.test`, `seon.test.runner`, `seon.instrument`, `seon.schema`,
  `seon.cluster.instruction`; `seon.db` and `seon.turn` are touched but not these functions):** every row of §2's
  table. The cards cite them as parents; each card's first step re-measures its own parent.

Health at this lane's start: default **faulted** (5 open faults, 53 errored receipts, 4 failed tests, cache
499,044,294 / 512,000,000 bytes, live heap 1.23 GB). Over-bound: `seon.schema/call-with-projection-state` mean 2.1 s
over 800 calls; `seon.schema/pull-selector?` 424,569 calls untested-hot.

## 1. Where Opus and Astra agree, where they differ, and root's resolution

**Agree.** (a) The cost class is algorithmic: O(program) work to learn that zero or one declaration changed; armed
validation is a few ms of a 94 ms quiet `:ping`, so instrumentation focus alone does not make ordinary work fast.
(b) Key a derivation by the content revisions of what it reads (`seon.db/content-revisions`, our Datahike fork's
attribute revisions), stored in `seon.cache`; no new cache, registry, listener roster or engine; no generic
incremental Datalog; differential dataflow rejected. (c) Datahike supplies identities, witnesses and deltas; Seon
does not reimplement them. (d) Identity predicates are O(1) and the cost is the armed wrapper (27×). (e) The writer
validates one report three times (PB-10). (f) Boundary-only arming (Opus I4, Astra B2) needs an owner ruling.

| # | Question | Opus | Astra | Resolution (root's recommendation) and reason |
|---|---|---|---|---|
| D1 | Where does a delta come from? | A bounded log of committed reports per connection (`d/listen`), retained in `seon.cache` | A listener report is one transaction's `:tx-data` under a batch's final `:db-after`, and the promise is delivered before the callback: a listener can be overtaken and is never freshness proof | **Astra.** Advance only from a delta the caller *holds* (the writer's own report, a speculative `:tx-data`), and only when the cached entry's revisions equal the report's `:db-before` revisions on the attributes read (a contiguity check); the result is keyed by the `:db-after` revisions. Anything else derives. No delta log. This answers Opus's open question 2 with neither option. |
| D2 | A cold reach acquisition | Keep whole-attribute maps; advance them by delta after a `defn` | Demand acquisition of the requested closure; per-node reuse; SCC rerun on the affected region | **Astra first, Opus only if measured.** The measured bucket is cold acquisition repeated per check (0.77 GB), and most of it is decoding (`seon.db/datoms->data` 0.737 GB, pattern 2). Fix decoding (CO-4), then acquire the closure (CO-5). Delta advance of reach maps and SCC digest reuse (Opus S3, S4) are shelved until CO-5's after-`defn` cost is 10× over the closure. |
| D3 | Branch sharing (LR-BRANCHREACH) | Resolve a branch value's revisions through its source commit | Removing connection/generation from keys is unsound; a lineage witness must come from the fork, eviction must miss, not lie | **Astra's correctness condition, Opus's seam.** LR-BRANCHREACH stays the row (FK-DH batch); its proof adds eviction, divergent-sibling and merge cases. Lower value after CO-5 (a miss then costs the closure, not the program). |
| D4 | Predicates inside schemas | I1: call the unwrapped predicate | Do not "globally uninstrument predicates" | **Opus I1, bounded as Astra asks.** The predicate's Var stays armed for every direct call; only a schema's internal call of it reads the original. Inside a schema the predicate *is* the check, and its own contract (`[:=> [:cat :any] :boolean]`) is vacuous on input. Nothing is uninstrumented. |
| D5 | PB-10's "checked report" | A record type only the validator constructs | A forged flag or relaxed report schema is not equivalent | **Astra's option "smaller helper input".** The writer validates the report once at its hook; helpers below it declare the narrower facts they need (the datoms of one entity, the owner set), derived after validation, fully contracted. No relaxed full-report schema, no flag. PB-10's card already reads this way for the helpers; its `:seon.db/checked-report` name is root's to reword at launch. |
| D6 | Scope | I3: honor contract `:scope`, elide arms that accept everything | Scope is a Malli option Seon hardcodes; narrowing loses a guarantee and needs a declared reason | **Both, within the owner's answer.** `compiled-wrapper` reads the contract's `:scope` (default full) and elides only arms whose schema accepts every value (identical refusals). A narrower declared scope is not used: the owner kept every check. I4 (boundary arming) is off the table under that answer. |
| D7 | `q`'s guard | I7: drop the guard; the body refuses | Not addressed | **One alignment.** The guard and the body both compute `query-input-position` over the same normalized query; the body already returns `query-input-shape-error`. Owner question Q1 below; recommended: the guard is deleted and the contract's output union keeps the refusal. |

## 2. The five patterns

Each pattern: the one design change that removes the class, the measured costs it explains, its cards. Per-request
numbers are this lane's probes unless cited. Rows that already exist keep their ids.

### P1. Work proportional to everything instead of the change

**Design change (the one mechanism).** A derivation is a pure function of the attributes it reads. Its key is
`[kind arguments (content-revisions database reads)]` in `seon.cache`; equal revisions are an exact hit, O(reads).
On a miss it derives from the requested closure, not the population. It advances from a delta only under D1's
contiguity check. A witness that is absent (another connection generation, eviction, restart) is unknown and
derives; it never certifies. Branch values share entries only through a fork-supplied lineage witness (D3).

| Measured | Owner | Card |
|---|---|---|
| `seon.test/changes` re-derived on every eval request of an agent whose branch differs from base: 16-24 ms, 48 MB (thread) per call, 367 armed calls, 6 reads; `digest-differences` runs twice per step (changes, then the overlay), 31-34 ms together | `seon.test/changes`, `digest-differences`, `clean-request-handle` | **CO-1** |
| `program-written-since?` scans program history at admission when revisions differ or are missing: 1.09 GB sampled in a two-test check window; a direct call 1,537 ms, 3.36 GB | `seon.test/admit-run`, `seon.test.runner/program-written-since?` | **CO-3** |
| Reach acquisition reads whole attributes for a 98-name closure of 8,374 declarations: 301 ms, 698 MB cold for one test (Astra P); 0.77 GB per check window | `seon.test.runner/reach-attribute`, `reach-declarations`, `reach-graph` | CO-4 (pattern 2 part), **CO-5** |
| Toolkit namespaces re-derived by a whole function-population join at every `ensure-cluster-entity!`: 0.325 GB first window, 0.059 GB candidate (variable) | `seon.cluster.instruction/toolkit-namespaces` via `seon.cluster/ensure-cluster-entity!` | **CO-6** |
| A definition's projection key copies ~30 k declaration datoms, 17.8 MB per `defn` | `seon.db/declaration-content-key` | PB-08 (existing; uses D1's held-report advance) |
| Whole-program cache entries per adoption, +129 MB / 4.5 min | `seon.task` tasks-tx, `seon.fn`, `seon.schema`, `seon.cluster` kinds | PB-14 (existing) |
| Schema-only derivations re-derived per projection, 25-37 MB | `seon.error`, `seon.schema` | PB-07a, PB-07b (existing) |
| Every Var walked on a program change, 10-30 MB | `seon.sci.eval/base-bindings` | PB-11 (existing) |
| Branch values miss all reach attributes, 2.3 s / 86 MB per load | `seon.db/content-revisions` | LR-BRANCHREACH (existing, D3) |

### P2. Seon layers wrapping Datahike and Malli at every step

**Design change.** A layer checks a value where it enters (entrant, writer, namespace boundary) with one compiled
check; it never re-derives what its caller just held. Concretely: a predicate inside a schema is the check, not an
armed call (D4); an identity-only schema is a class check; an arm that accepts every value is not run (D6); a read
resolves its projection once and aligns its inputs once; a stored value is decoded only when its stored form
differs. Every contract stays armed (owner).

| Measured | Owner | Card |
|---|---|---|
| In one `:ping` window (JVM-wide, quiet-ish): `database-value?` 8,008 calls, `transaction-report-datom?` 7,679, `projection-cache-value` 5,059, `schema-database` 1,246, `connection-object?` 1,272, `environment?` 967; wrapped `database-value?` 10 k calls 1.46 ms / 1.76 MB vs original 0.16 ms / 88 B | `seon.schema/runtime-predicate` (schema predicates resolve to the armed Var) | **CO-7**, **CO-8** |
| `carried-projection` and `projection-cache-key` 510 calls each per `:ping` window; 79 `carried-projection` calls in one `pull`; a tiny `pull` makes 39 armed calls (14 `database-value?`, 3 `schema-database`); `seon.db/pull` 36 µs vs 8 µs | `seon.db/carried-projection`, `with-declarations` | **CO-9** |
| One-clause `seon.db/q` 47.7 µs vs `d/q` 2.2 µs; the guard `query-call-valid?` 2.9 µs repeats the body's alignment | `seon.db/q`, `query-call-valid?`, `aligned-query-arguments` | **CO-10** |
| Every wrapper validates input, output and guard, including `[:cat :any]` inputs and `:any` outputs | `seon.instrument/compiled-wrapper` (scope hardcoded `#{:input :output :guard}`), Malli fork `-instrument-f` | **CO-11** |
| `seon.db/datoms->data` 0.737 GB inside the 0.77 GB reach bucket; `seon.db/datoms` ~35× `d/datoms` | `seon.db/datoms` (W-24) used by `reach-attribute` | W-24 (lane datoms-decode, in flight), **CO-4** |
| The writer validates one report three ways: 21 k `transaction-report-datom?` calls for 157 datoms, 14 MB per `defn` | `seon.db/write-owned-values-error`, `write-digest-error`, `write-report-error` | PB-10 (existing, D5) |

### P3. Work not derived from need

**Design change.** A step runs only what something it reads has changed since its last answer; otherwise its
answer is its record. The automatic test step (owner Q-B: after every eval request that evaluated a form) was
accepted on the premise "an unchanged program costs one reach-digest lookup per proof". **Measured false today**:

| Measured (this lane, `:ping` as agent `cost-cards-probe`, 3 runs) | Owner | Card |
|---|---|---|
| A `:ping` costs 719-794 ms; the test step 655-731 ms of it: `seon.test/run` 627-707 ms, of which `clean-request-handle` (the world build) 460-533 ms, because four unchanged red reaching tests re-execute every request | `seon.test/run` reuse rule (reds never reused) | **F11** (existing; its question is answered: re-executed) |
| Even when every member answers from its record, the request writes an admission: `selection-admission` 10-16 ms plus `admit-run`'s transaction | `seon.test/run`, `selection-admission`, `admit-run` | **CO-2** |
| System turns, refresh and context capture per model pass | `seon.turn` system turn, `seon.cluster.prompt` | deleted by rip-context (no card) |
| Toolkit derivation on every cluster ensure (also P1) | `seon.cluster/ensure-cluster-entity!` | CO-6 |

### P4. Copies instead of references

**Design change.** Produce the stored or keyed form at the source and pass references (ids, digests, the held
value). A cache key is the inputs' stored digests, never the inputs; an error holds references (CLAUDE.md "Errors
hold references, never copies").

| Measured | Owner | Card |
|---|---|---|
| `:seon.task/tasks-tx` key holds the whole forms map and every task file's text, ~10 MB per entry and O(program) hashing per lookup | `seon.task` | PB-14 (existing) |
| The projection key copies every declaration datom (also P1) | `seon.db/declaration-content-key` | PB-08 |
| A contract refusal of `seon.cluster.agent/acquire-context!` (a map missing `:seon.cluster/name`) carries the whole boot instance as `:offending` (ServerSocket, flow graphs, executors, the store lock); the printer bounds it, but a recorded error would store its shown text | `seon.instrument` refusal construction | finding: filed by root as a task on `seon.instrument` (this lane edits no task file) |
| The `:ping`'s 83 MB in `vector-query-call` across 48 queries (unknown owner) | to be named | **CO-12** (diagnosis, post-rip) |

### P5. Retention without an owner

**Design change.** Every retained value is a `seon.cache` kind weighed in bytes or a roster entry with an
admission and retirement fact. The cache runs full (499-511 MB of 512 MB in every probe today), so P1 keys
evict and cold acquisition repeats: P5 multiplies P1. No CO card: PB-14 (entries that follow the program), MEM
rows and LR-BRANCHREACH's eviction proof own it. The measurement lane should report cache hit rate per kind
(Caffeine `recordStats`) beside the re-measure.

### P6 (added from the evidence). Cost not attributed to the request that caused it

The reply under-reports its eval request by two to four orders of magnitude (`:ms 1` for a 3.8 s request,
`:alloc-mb 0.7` for 1.17 GB; feedback-design §1), and `seon.profile/measure` counts every thread's calls. An agent
cannot see that its read-only `:ping` costs 720 ms. **Design change:** fold what the owners already count into one
cost value on the reply (feedback-design slices 2-5, owned by that design's lanes). CO-0 gives proofs a
per-function count without a top-N cap.

## 3. Cards in order of measured waste

Per ordinary eval request first, then per test check. Each is about fifteen minutes; each proof counts calls or
reads with `seon.profile/measure` (never JVM-wide allocation).

| Order | Card | Pattern | Measured parent | Expected after |
|---|---|---|---|---|
| 1 | F11 (existing) | P3 | 460-533 ms world build + re-executed reds per `:ping` | 0 executed members, no world build |
| 2 | CO-0 | P6 | proofs cannot count a function past `hot-limit` | `(seon.profile/calls-of m sym)` |
| 3 | CO-1 | P1 | `changes` 16-24 ms / 48 MB, `digest-differences` ×2 31-34 ms, per request | one revision digest, ~0.1 ms; `digest-differences` 0 calls on a hit, 1 on a miss |
| 4 | CO-2 | P3 | `selection-admission` 10-16 ms + one transaction per request | 0 transactions when every member answers from record |
| 5 | CO-3 | P1 | history fallback 1.09 GB per check window; 1.5 s / 3.36 GB per call | 0 history reads when both witnesses are present |
| 6 | CO-4 | P2 | `datoms->data` 0.737 GB of reach's 0.77 GB | 0 decode calls in reach acquisition |
| 7 | CO-5 | P1 | 301 ms / 698 MB cold for a 98-name closure (8,374 declarations) | reads ∝ closure |
| 8 | CO-6 | P1/P3 | ≤ 0.325 GB per check window | 0 toolkit queries on an unchanged cluster |
| 9 | CO-7 | P2 | ~8 k `database-value?`, ~7.7 k `transaction-report-datom?` armed calls per `:ping` window | schema-internal predicate calls leave the armed cell |
| 10 | CO-8 | P2 | identity schema check 0.15 µs via the armed predicate | class check ~0.008 µs, 0 armed calls |
| 11 | CO-9 | P2 | 79 `carried-projection` per `pull`, 510 per `:ping` window | 1 per read |
| 12 | CO-10 | P2 | guard 2.9 µs of one `q`'s 47.7 µs, a second alignment | 1 alignment per `q` |
| 13 | CO-11 | P2 | every arm runs, `:any` included | elided arms run no validator |
| 14 | CO-12 | P4 | `:ping` 48 `q`, 83 MB (pre-rip) | owner table, then rows |

Shelved by D2: Opus S3 (reach maps advanced by delta) and S4 (SCC digest reuse), until CO-5's after-`defn` cost is
10× its closure. Existing rows on the same patterns keep their ids and order: PB-08, PB-10, PB-14, PB-07a/b,
PB-11, LR-BRANCHREACH, EN-7, W-24, DH-5.

## 4. Owner questions

1. **Q1 (CO-10):** `seon.db/q`'s guard recomputes the alignment its body computes and refuses with the same shape
   error. Delete the guard (recommended: the contract's input and output schemas stay; the body's refusal is the
   check), or keep it and pass the body's alignment to it through the result?
2. **Q2 (CO-7):** a predicate called from inside a schema no longer counts in that predicate's profile cell (its
   direct calls still do; the time stays in the caller's self time). Accept?

## 5. Probe forms (JVM mode, default, agent `cost-cards`)

```clojure
;; changes on an agent branch, twice; the revision digest it would be keyed by
(let [cluster (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
      ex (seon.cluster.agent/acquire-context! cluster "cost-cards-probe")
      conn (:seon.db/connection ex)]
  [(seon.profile/measure #(seon.test/changes ex conn))
   (seon.profile/measure #(seon.test.runner/program-revisions-digest (seon.db/db conn)))])
;; => changes 18 identities; 23.7 ms / 17.9 ms, 367 calls, 6 reads, 48.4 / 47.8 MB (:thread);
;;    revisions digest 0.11 ms, 16 calls, 0 reads, 46 KB

;; a :ping's test-step owners, from the profile cells' deltas around one seon.mcp/call (3 runs)
;; => request 719-794 ms; test-step 655-731; run 627-707; clean-request-handle 460-533;
;;    changes 16-20; digest-differences 2 calls 31-34; selection-admission 10-16; admit-run 2;
;;    reach-digests 2 calls 0.3
```

The JVM-mode `seon.test/run` probe on the probe agent's branch was refused by the writer (`:seon.db.write/validation-refusal`,
"names branch … not the writing cluster's branch"): a JVM evaluation recorded as one agent cannot admit a run on
another agent's branch. That is the custody rule working, not a defect; the step's numbers come from the cells.
