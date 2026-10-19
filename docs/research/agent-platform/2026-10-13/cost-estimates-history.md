---
title: "Cost estimates: prior work, rulings and the stage that owns them"
type: research
status: active
created: 2026-10-13
---

# Cost estimates: prior work, rulings and the stage that should own them

Research only, 2026-10-13. Basis: the working tree at HEAD e0e18c0d4 and its history. Nothing was run or changed.

Tags: **[V]** verified: I read the text or ran the search in this session. **[I]** inference from what was read.

## 0. Bottom line

- Measurement exists and works: `seon.profile/measure`, a per-evaluation reply cost, per-definition stored cost entities, and per-test member cost. 67 test call sites use `profile/measure` [V].
- Expectation does not exist as data. No task file in `resources/seon/tasks/` contains the phrase "cost formula" or "Expected from the work" [V, two empty greps]. The expectation lives in owner prose, spec prose, and card prose. Tests carry only absolute bounds such as `(<= ms 10)` [V].
- The owner rule is unambiguous: derive an expectation from what the work is proportional to, then treat 10x over it as a defect. Invented targets are not allowed [V, Section 3].
- The best existing model is `docs/seon/specs/merge.md` (a table with "Measured" beside "Expected from the work") and about fifteen cards that write an "Expected from the work" paragraph before the measured parent [V]. None of it is checked by a proof.

## 1. What exists and works today

### 1a. Measurement Vars (`src/seon/profile.clj`)

| Var | Line | What it does [V] |
|---|---|---|
| `seon.profile/measure` | 636 | Calls `f` once. Returns `:seon.profile/value`, `:seon.profile/ms`, `:seon.profile/alloc-bytes` (calling thread only, `:seon.profile/alloc-scope :thread`), `:seon.cost/jvm-window`, `:seon.profile/calls`, `:seon.profile/reads`, `:seon.profile/ranked-by-self` and `ranked-by-calls` (top `hot-limit` = 20), `:seon.profile/called`. Schema `:seon.profile/measured-call`. |
| `seon.profile/calls-of` | 707 | Outermost entries of one armed definition over a measured call. |
| `seon.profile/slow-ms` | 56 | `1000`. Docstring: "The owner's bound (2026-09-23): every operation over one second is explained." |
| `seon.profile/live-costs` | 520 | A request's own per-definition values from its sink, for its reply. |
| `seon.profile/transaction-observation` | 533 | Turns a sink's delta into `:seon.fn.cost/entity` upserts (one per symbol and scope, warm and cold samples), written on the world's next transaction. |
| `seon.profile/moved?` | 574 | Hysteresis test for a change in a stored sample. |
| `seon.profile/summary` | 587 | Summary of cells. |
| `seon.profile/cells`, `cell`, `counts`, `histogram-calls` | 130, 349, 365, 377 | Per-callable cumulative cells. |

Per-call recording, from the ns docstring [V]: each armed callable owns one cell of JDK accumulators (a log2 latency histogram, total and self nanoseconds, entries, throws by class). The cells live in `cells-by-key`, a `ConcurrentHashMap` (profile.clj:124). They are cumulative and "A JVM starts every cell from zero (owner ruling): what survives a restart is what was stored." A definition's cold and warm calls are never averaged together.

### 1b. Reply cost schemas (`resources/seon/schemas/seon.cost.edn`) [V]

Keys in use: `:seon.cost/ms`, `stages`, `calls`, `reads`, `transactions`, `alloc-mb`, `cpu-ms`, `jvm-window`, `step`, `writer`, `commit`, `entrance`, `shared`, `tests`, `diff`, `live`, `counts`, `request`, `gc-ms`, `jit-ms`. The `:seon.cost/jvm-window` docstring says it "includes concurrent unrelated work; never causal request attribution."

### 1c. Stored per-definition cost (what is persisted)

- Entity `:seon.fn.cost/entity`, keyed `:seon.fn.cost/key [sym scope]`, with attributes `warm-digest`, `warm-calls`, `warm-self-us`, `warm-total-us`, and the `cold-*` set (`src/seon/db.clj:3815-3817` [V]).
- Read by `seon.db/stored-costs` (db.clj:3827) and compared by `seon.batch/cost-diff` (batch.clj:303) with dials `:seon.config.fn.cost/min-calls`, `:seon.config.fn.cost/min-total-us`, `:seon.config.fn.cost/hysteresis` (db.clj:3857-3858) [V].
- The reply's diff against main is built in `src/seon/mcp.clj:291-292` [V].
- Test member cost: `:seon.test.member/*` on the stored member, the `top` list bounded by `:seon.config.test.cost/top`. Schema `resources/seon/schemas/seon.test.member.edn:23` [V].
- Storage bound: commit 289d37810, "health plan: storage budget, ≤~1 GB/day under heavy use if collection reclaims it; carry more where cost is fixed, optimize where proportional" [V, commit message].

### 1d. Dials that remain

`:seon.config.fn.cost/{min-calls, min-total-us, hysteresis, cold-calls}` and `:seon.config.test.cost/top` [V]. Commit 78c32f6ff deleted the seven other `:seon.config.test.cost` dials ("alloc/calls/ms factor and floor, assign-runs") and kept `top` [V].

### 1e. Health

`seon.health/regressions` (`src/seon/health.clj:509`) remains, backed by `boot-comparisons` (463) [V]. The definition-comparison branch was deleted: `seon.profile.measurement`, `definition-comparisons`, `seon.profile/begin`, `explain` and `explain-slow` are absent from `src/` and `test/` [V, empty greps; commit 554db75c2, 809310b7a].

### 1f. Tests that assert cost

Only absolute bounds, no formula. Examples [V]:

- `test/seon/merge_test.clj:209-211`, a squash-merge probe: `(is (<= (:seon.profile/ms measured) 10))`, `(is (<= (:seon.profile/alloc-bytes measured) 5000000))`.
- `test/seon/context_cost_test.clj:33`: `(is (< (:seon.profile/ms measured) 50.0))`.
- `test/seon/program_test.clj:112`, `test/seon/db_test.clj:720`, `test/seon/eval_alloc_test.clj:51-72`, `test/seon/pass_cost_test.clj:17-53`, `test/seon/instrument_test.clj:1073-1106`: `profile/measure` used to read cost, most without a bound.

One task states a bound with its reason, the closest existing form to a cost expectation:

- `resources/seon/tasks/my.agent.edn:7`: "allocated 1,333.8 MB in 11,737 armed calls ... about 110 MB and 980 calls per retire, for agents that had each run a few trivial passes. Retirement stops one graph, releases one connection and unlinks one branch; that work should be proportional to the agent's live handles, not to the program or the store. ... Done when one retire of an idle agent's branch allocates under 5 MB, measured by that probe." [V]

### 1g. Commit-level expectation in cards

`docs/seon/schedule/cards/README-commit-cost.md:58` has a table headed "Saves per commit (expected from the work)" [V]. `RP-5.md:128` ("Expected from the work: one clj-kondo run per defining form, its text proportional to the form plus its other-namespace definitions") and `PB-07a.md:70` ("Expected: an empty window is one query; measured parent 12.8 MB, 13.5 ms") are typical [V]. About fifteen card files carry this pattern (grep of `Expected from the work|expected from the work` across `docs/`).

## 2. Designed but not built, or superseded

| Item | Where | Status [V unless marked] |
|---|---|---|
| Schema generation and check cost per digest: `:seon.schema.cost/{gen-us,check-us,samples}` on the schema entity, dial `:seon.config.schema.cost/max-us`, a finding when over budget | `docs/seon/schedule/cards/FEEDBACK-LOOP.md:69-71` | Not built. `seon.config.schema.cost` appears in neither `resources/seon` nor `src/seon`. |
| COST-REG: a reaching test whose allocation, calls or time exceed its baseline is red with `:seon.test/cost-regression`; baselines captured at assignment; dial factor and floor; `assign-runs` default 32 | `docs/seon/schedule/cards/COST-REG.md` (card lines 1-80 read; slices 2-3 marked superseded) | Slice 1 landed (006fadb61: "a test member's stored result keeps its own cost"). Slices 2-3 superseded by FEEDBACK-LOOP and their dials deleted (78c32f6ff). Commit 809310b7a: "cut COST-REG baselines/dials, growth tests and guards" and "stored member cost shown, not gated" [V]. It is later than the card's commit 1f0015865 [V by log order]. |
| FEEDBACK-LOOP: every reply carries its live cost and the diff to main | `FEEDBACK-LOOP.md` lines 10-17, 43-77 | Largely built: `:seon.cost/diff`, `live`, `tests`, `shared` exist in `seon.cost.edn`. The schema-cost part (above) is not. |
| TASK-CHAIN Implement proof row: "the cost formula where the subject states one" | `docs/seon/schedule/cards/TASK-CHAIN.md:127` | Written, but conditional and vacuous today: no task states a formula [V, empty grep]. |
| merge.md cost table "Measured / Expected from the work" | `docs/seon/specs/merge.md:273-285` | A prose table. Expectations are not checked by any test. |
| Budget section of the agent-loop spec, codes O, RP, PB | `docs/seon/specs/agent-loop.md` §2 (line ~88-100), `docs/seon/schedule/agent-loop-ledger.md:44-52` | Prose budgets per probe code. `test/seon/pass_probe.clj` exists [V]; the expectations are not data. |

[I] Counting the above: the expectation is written in prose on cards, specs and the ledger; the mechanism to check it (`profile/measure` plus a bound) is built; the link between them, a stated formula that a proof reads, is not.

## 3. Owner rulings on cost (quoted)

1. **2026-10-08, invented targets** (memory `feedback_expected_cost_not_targets_2026_10_08.md`, quoting the owner): "I don't remember setting a 60MB target (remember don't set arbitrary targets), but jesus why does a defn cost 100MB? Use common sense and ask whether we are likely doing something wrong rather than arbitrary limits for both time, cpu and memory." Same memory: "for any operation, write what it must do and what that is proportional to ... → expected cost; measure per stage; 10× over = find the wrong work." [V]

2. **2026-10-08, dials** (same memory): "no hardcoded limits: every size/count/budget/heap/timeout is a config dial with a default sized for a 16 GB machine ... Never derive defaults from host RAM percentage." [V]

3. **2026-10-08, spec §2 Budgets** (`docs/seon/specs/agent-loop.md`): "Owner 2026-10-08: no arbitrary targets. Every cost is judged against what the work must do: an expectation from first principles (what the work is proportional to and the rough cost that implies), the measured parent and change, and 10× or more over the expectation is a defect to diagnose, and every regression is reported with its numbers." [V]

4. **2026-10-09, regressions fail tests** (memory `feedback_cost_regressions_fail_tests_2026_10_09.md`): "We should make auto regressions create failing tests if the refactoring was slower than it previously was." Gating was later reduced to "shown, not gated" by 809310b7a. [V for both; the reduction being a decision against this ruling is [I], and the owner has not been recorded as ruling on the reduction.]

5. **2026-10-12, perf proofs end to end** (memory `feedback_perf_proofs_end_to_end_2026_10_12.md`): after `f18ab95e3`, the owner asked "why are we caching something that doesn't benefit from the cache?" Rule: "a performance lane's red proof is the targeted cost itself ... A count is supporting evidence only. A measured regression or 'no demonstrated speedup' means do not commit." [V]

6. **2026-10-12, take measured wins** (memory `feedback_take_measured_wins_2026_10_12.md`): "Don't literally screw up good options because of an arbitrary time we made up." And: "a proof bound we invented is a hypothesis, not a law." [V]

7. **CLAUDE.md** (project, section "Proof and performance"): "Judge cost against what the work must do (what it is proportional to), never an invented target: 10× over that expectation means wrong work to find and fix." And "Over ten seconds needs explicit owner authorization and an issue even when justified." [V, CLAUDE.md:604 and the performance bullets]

**Tension to surface [I]:** rule 4 (red on regression) and the current "shown, not gated" design disagree. The owner should confirm whether 809310b7a overrode rule 4 or whether rule 4 still stands and only its implementation was deferred.

## 4. Recommendation: which stage owns the expectation

The chain as written in `docs/seon/schedule/cards/TASK-CHAIN.md` has Mine, Design (Survey and Model folded in, line 199), Prove, Implement, Land and Retrospective. I found no "Define" stage [V, grep]. Survey is therefore inside Design.

**Design should produce the expectation; Prove should hold it as an assertion; Implement should show it met; Land should only compare.**

Reasons, grounded in what exists:

- CLAUDE.md "How we design" step 2 puts the cost budget in the interface specification, "Give it a cost budget derived from what the work must do", before the body and beside the proofs. That is Design's output in the chain, and Design is where the data flow is traced, so the proportionality can be stated from the producer's inputs [V; mapping to Design [I]].
- Design is the only stage that sees the data flow and the measured parent together. Prove writes the red proof, and a proof cannot be written before its bound exists.
- Land is the only stage with the main baseline. `seon.batch/cost-diff` already compares a live value with main's stored value by identity [V]. Land should report that comparison (it already does in the reply), not invent a second check.

Concretely, with the existing mechanisms:

1. **Design** records on the task, as data, the work's proportionality and its size parameter, not a number: for example `:seon.task/cost` `{:proportional-to ...}` with the input counts named in the intent by backticked symbol (such as "declarations changed", "callers", "agents' live handles"). The measured parent on the same real data is taken in Design and written beside it, as the card format already does (`README-commit-cost.md:58`). [I: this is a new attribute; the `my.agent.edn:7` intent shows the prose version works.] Before adding a schema key, check the registry: the `seon.cost` family already exists and should be reused.
2. **Prove** writes the proof as a `seon.profile/measure` assertion on the probe at real size, with `:seon.profile/ms` and `:seon.profile/alloc-bytes` bounds derived from the Design expectation (the parent for a perf change; for a new function, the work-proportional expectation, with 10x as the defect line). Call counts stay supporting evidence, per the 10-12 rule.
3. **Implement** must show the measured value within the bound on the branch, by the same probe. A measured regression is not committed.
4. **Land** reports `:seon.cost/diff` against main's stored entity (already built), as a finding. Whether it also reds is the open owner question in Section 3, item 4.

What this does not do: it does not add a dial for any expectation. The shipped dials stay as they are; expectations are per-task data derived from the work.

## 5. Verification limits

- I did not run any probe or test. All numbers quoted are from commit messages, cards or memory, not re-measured.
- `grep` for "cost formula" and "Expected from the work" in `resources/seon/tasks/` returned no matches; if the task files are loaded from a different path at runtime, this should be rechecked via `seon.task` ingestion.
- The memory index file is over its line limit; the cost memory files were read directly, and their text is quoted from those files.
