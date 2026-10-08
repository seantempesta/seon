---
title: Agent competency suite and context bench
type: research
status: design data (tasks, proofs, grade, experiments); slices in the agent-loop ledger §3.11 and cards/README-bench.md
date: 2026-10-08
revised: 2026-10-08 night (lane bench: today's context, the bench, experiments)
lane: competency-spec, bench
---

# Agent competency suite and context bench

**What exists (HEAD `e1a71b7c8`).** A model agent's context is `(help)` as the system message, its own model
exchanges, and the current-state block last (`seon.context/model-context`, CX-1). A model turn opens only with an
assignment, read at the task home (CX-2, `cd1e3455c`); a reply with no form waits. The block
(`seon.context/current-state`) shows the task (symbol, intent, must-read), its proofs with status, source and failures
(`seon.context/proofs`), every name, schema key and doc the intent and must-read reference, resolved by
`seon.ref/references` (arglists, contract, first doc line, and `doc`/`source` as offered operations), unread messages
and turns left. After an eval request that evaluated a form, the proofs and then the tests reaching the change run on
the agent's branch (F4a-c). There is no entity walk, no system turn, no opening, no prompt selection. Run 3 turned a
proof green on DeepSeek flash. A fixture provider drives the same path in tests (`seon.test-support/with-provider`).

**What this document adds.** Owner, 2026-10-08 night: "We are not certain about the best ways to present context to
the agents and we need a standardized test to experiment with"; "Everything is coming from the context present in a
task … I want to focus on writing really good tasks"; "make sure you are coming up with different strategies and
testing them to see what agents respond best to. We need to be able to grade on how many turns, errors, success
criteria". So: a fixed set of really good tasks (§1, §4), one grade per run read from the database (§2), presentation
strategies each switchable by a dial (§3), and experiments that vary one strategy at a time (§5). Data flow of one
run: root assigns a task to a fresh agent on its own branch with a variant's settings overlay → the agent works
through the one REPL path → its proofs turn green or its cap ends it → `seon.competency/grade` reads the run from the
branch. No harness, runner, store or printer of its own.

## 1. A really good task

The bench measures presentation only if the tasks are good; a vague task measures the task. Every bench task:

1. **States what must become true, short**, and how it is observed ("Done when …"), in the intent.
2. **References are typed and resolve** (CLAUDE.md "Write tasks as data"): every Seon or library name in backticks as a
   fully qualified symbol, every schema as a backticked fully qualified keyword, `:seon.task/must-read` holding the
   names the agent must read. The block resolves them through `seon.ref`; an unresolved one is a problem the bench
   counts as a task defect, never the agent's. A name the agent must create (e.g. `comp.geo/area`) does not resolve
   yet: it is named in the intent as the target, not in must-read.
3. **Has a red proof** before assignment (`seon.task/assign!` refuses `:no-proofs` and `:proofs-green`), tagged with
   the task in `:seon/tasks`, asserting data through `v` (below), one behavior.
4. **Has an oracle**: the known solution, evaluated through MCP by a fixture agent, turns the proof red to green
   (`seon.competency-oracle-test`). A task whose oracle is not green is not run.
5. **Cannot be copied**: answers derived from a seed (the agent id) where a guess or an earlier run would pass
   (`needle`, §4.4).

Task authoring is itself a strategy (§3, S7): the same proof can carry two task wordings, prose and typed.

Proofs live in `test/seon/competency_test.clj`, tasks in `resources/seon/tasks/seon.competency.edn`, oracles in
`test/seon/competency_oracle_test.clj` (group K). The proof namespace is `:seon.test/long`, so the bulk tier skips it
and the step runs it by identity. A proof reaches the agent's SCI definitions:

```clojure
(defn- v "The value `code` answers in this member's world." [code] (support/agent-value (kernel/world-ctx) code))
```

## 2. The grade: one run, read from the database

A run is its agent: the task it held, its settings overlay (the variant) and what its model passes recorded on its
branch. `seon.competency/grade [database agent-id task]` (`test/seon/competency.clj`, on every cluster JVM's test
classpath) answers one map; landed as BENCH-1 (`bb89f96c8`).

| Key | Read from | Today |
|---|---|---|
| `green?`, `proofs` | `seon.context/proofs` on the branch: every proof's latest result at its current reach digest | yes |
| `model-calls`, `attempts` | call rows (`:seon.turn/*`) of the agent carrying `:seon.turn/attempts` | yes |
| `calls-to-green` | model calls opened before the transaction by which each proof had a green member row (`:seon.test.member/*`) | yes |
| `forms` | evaluations with `:seon.cluster.eval/origin :model` | yes |
| `agent-errors` | those evaluations carrying `:seon.cluster.eval/error` (the agent's own mistakes) | yes; by type needs `:seon.cluster.eval/error-type` (errors cut) |
| `platform-errors` | `:seon.cluster.eval/faults` refs on them plus `:seon.ai.attempt/error` | yes |
| `no-form-calls` | model calls with no evaluation (a wasted call, or the waiting reply after green) | yes |
| `repeated-forms` | model evaluations whose source repeats an earlier one's | yes |
| `prompt-tokens`, `cached-tokens`, `completion-tokens` | `:seon.ai.usage/*` on the attempts (the fixture provider reports none: 0) | yes |
| `wall-ms` | first model call's opening to the last one's closing, transaction instants | yes |
| `variant` | the agent's settings overlay | BENCH-3 |
| invented output | the reader's imitation note (agent-loop §4.3) on the evaluation | missing: a fact on the evaluation |
| paging (T14) | `:seon.cluster.eval/result-reads` (D1): cut followed, or the producer re-run | after D1 |
| answer (T15) | the validated answer (A1) and its proof | after A1, F6 |
| gave back (T9) | the give-back with its reason (F7) | after F7 |

`seon.competency/table [grades]` (BENCH-3) folds grades into one row per cell: runs, green share, median and spread of
calls-to-green, agent and platform errors per run, wasted calls per run, tokens and cost (`:seon.ai.model/*` prices)
per run. The AI render of a grade is the map; the table is the experiment's result, committed with its run ids.

## 3. Presentation strategies, each a dial

Each strategy is a dial in one new configuration schema, `seon.config.context` (wire format stays in
`seon.config.ai`), set per agent through its settings overlay, with today's behavior as the shipped default. No
hardcoded choice: `seon.context/model-context` and `seon.context/current-state` read the dial from the agent's
effective configuration. Every variant must keep the request a prefix of the next one's (the provider cache), except
where the strategy is exactly that.

| # | Strategy | Dial and values (default first) | What it tests | Card |
|---|---|---|---|---|
| S1 | Wire format | `:seon.config.ai/wrapping` `:transcript` · `:chat` | the model reading its own forms as its own messages (run 1: it advised itself) | CX-12 |
| S2 | Where the block sits | `:seon.config.context/block-at` `:last` · `:first` (after help, before history) | recency versus a stable early statement of the task | BENCH-7 |
| S3 | Reference depth | `:seon.config.context/references` `:summary` (arglists, contract, doc line, offered `doc`/`source`) · `:names` (name and operations only) · `:source` (summary plus the definition's source text inlined) | inlined text against offered operations: tokens, calls spent on `doc`/`source` | BENCH-6 |
| S4 | Help | `:seon.config.context/help` `:full` · `:short` (three lines: block, `defn` in the task's namespace, green proofs finish) | whether instructions beyond the block help | BENCH-8 |
| S5 | History length | `:seon.config.context/history` `:all` · a count N (the last N exchanges, one line before them naming the elided count and the requery form `(seon.eval/of-agent …)`) | tokens against lost context | BENCH-9 |
| S6 | Proof shown | `:seon.config.context/proofs` `:source` (proof source and failures, today) · `:names` (symbol and status) | whether seeing the test is what solves the task | BENCH-10 |
| S7 | Task wording | not a dial: two tasks sharing one proof, `…-prose` (intent as plain sentences, empty must-read) and `…-typed` (§1: backticked names, must-read, done-when) | the owner's "really good tasks" | BENCH-2 |
| S8 | Test step in history | none until CX-11 lands; then `:seon.config.context/step` `:shown` · `:hidden` | whether the model needs each pass's step in its history | after CX-11 |

The model (`deepseek-flash`, thinking disabled) is a control, not a strategy; `deepseek-v4-pro` is one later cell on
the winning variant only. The data entrance (EDN instead of REPL text) waits for its row (DATA) and is not in the
first round.

## 4. The tasks

### 4.0 The standard set

The first experiments use a set that runs today with no unbuilt machinery, then widen.

| Set | Tasks | Needs | Runs today |
|---|---|---|---|
| **Core** | T1-T4 (function and contract, schema then function, a passing test, alias between namespaces) | C2 (tasks, proofs, oracles), BENCH-3 (launch) | after BENCH-2, BENCH-3 |
| Paging | T14 (the entry after the fatal one) | D1, D2 for the paging columns; green alone today | green only, after D2's producer |
| Answer | T15 (answer from a query and source) | A1, F6 | no |
| Give back | T9 (impossible, contradictory proofs), T9h (possible twin) | F7 for T9; T9h none | T9h only |
| Conflict | T10-T13 | MA-3 … MA-11 and a model root | no |

Each run: a fresh agent (`bench-<experiment>-<cell>-<n>`) on its own branch of default (the competency cluster, M0, is
not needed for tasks with no merge: the branch is deleted after grading), the call cap 6 (`:seon.config.run/max-episode-runs`),
temperature and stop sequence absent, one outside message "Work your task." as the wake. Without F5 the agent is not
told the task is done; the block shows its proofs green and help says green proofs finish it, so a no-form reply
(waiting) after green is expected and graded as a no-form call after `calls-to-green`.

### 4.1 Core (T1-T8)

| # | Task (`seon.competency/…`) | What the agent must do | Setup | Calls |
|---|---|---|---|---|
| T1 | `area-is-contracted` | In `comp.geo`, `area [w h]` contracted `[:=> [:cat [:int {:min 1}] [:int {:min 1}]] [:int {:min 1}]]` | none | 1-2 |
| T2 | `quantity-schema-then-function` | Register `:comp.order/qty` `[:int {:min 1}]`, then `comp.order/double-qty` on it | none | 1-3 |
| T3 | `slug-has-a-passing-test` | Write `comp.text-test/slug-lowercases` (≥2 `is`) for the given `comp.text/slug`; green | `slug` | 2-4 |
| T4 | `app-calls-util-through-an-alias` | `comp.util/clamp` (0-10); `comp.app` aliasing it as `u`, `bounded` calls `u/clamp` | none | 2-3 |
| T5 | `total-takes-a-tax-rate` | `comp.price/total [items]` → `[items tax-rate]`; fix caller `invoice` to pass `0.1` | `total`, `invoice` | 2-4 |
| T6 | `ledger-total-reads-the-database` | Register ledger attributes, transact 10, 20, 30, `comp.ledger/total` over a query | none | 2-5 |
| T7 | `encode-all-uses-the-library` | Find the reversible encoder in six-function `comp.lib` with `dir`/`doc`/`source`; `comp.use/encode-all` | `comp.lib` | 2-4 |
| T8 | `mean-test-goes-green` | Fix `comp.stats/mean` (uses `quot`) so its red test goes green, test unchanged | `mean`, red test | 2-4 |

T1 landed in C1 (`c6ae8875d`) with a prose intent; BENCH-2 adds its typed twin and T2-T4 in both wordings. T3 and T5-T8
need setup definitions on main before the agent forks; the setup is part of each task's oracle test and of the launch.
T7 is the reference-depth task: under S3 `:source` the encoder's source is in the block, under `:names` the agent
must call `doc` or `source`.

```clojure
(deftest ^{:seon/tasks #{'seon.competency/area-is-contracted}} t1-area-is-contracted
  (is (= 6 (v "(comp.geo/area 2 3)")))
  (is (some? (:seon.error/type (v "(comp.geo/area -1 3)"))) "the armed contract refuses")
  (is (some? (v "(:malli/schema (meta #'comp.geo/area))"))))
(deftest ^{:seon/tasks #{'seon.competency/quantity-schema-then-function}} t2-quantity-schema-then-function
  (is (= [:int {:min 1}] (v "(seon.schema/form :comp.order/qty)")))   ; BENCH-2 confirms the reader at the REPL
  (is (= 4 (v "(comp.order/double-qty 2)")))
  (is (some? (:seon.error/type (v "(comp.order/double-qty 0)")))))
(deftest ^{:seon/tasks #{'seon.competency/slug-has-a-passing-test}} t3-slug-has-a-passing-test
  (let [[p f e] (v "(first (seon.db/q '[:find ?p ?f ?e :where [?m :seon.test.member/symbol comp.text-test/slug-lowercases]
                     [?m :seon.test.member/pass-count ?p] [?m :seon.test.member/fail-count ?f] [?m :seon.test.member/error-count ?e]] (seon.db/db)))")]
    (is (<= 2 p)) (is (= [0 0] [f e]))))
(deftest ^{:seon/tasks #{'seon.competency/app-calls-util-through-an-alias}} t4-app-calls-util-through-an-alias
  (is (= [10 0 7] (v "(mapv comp.app/bounded [15 -3 7])")))
  (is (= 'comp.util (v "(ns-name (get (ns-aliases 'comp.app) 'u))"))))
```

T5-T8 proofs as in the first version of this spec (`c6ae8875d`'s parent, §4.1), carried by C3.

### 4.2 Giving back (T9, T9h)

- **T9 `february-has-twenty-nine-days`, impossible.** `comp.cal/days-in-month [year month]` with two contradictory
  proofs: 29 for February 2023, and agreement with the Gregorian rule. Correct: see the contradiction and give the task
  back within 1-3 calls with a reason naming both proofs (F7). Graded: gave back, calls before it, reason names a proof.
- **T9h `days-in-month-follows-gregorian-leap-rules`, hard but possible.** The same function without `java.time`,
  matching a 12-row table with 1900, 2000 and 2024. A give-back here is premature and grades as wrong.

### 4.3 Conflicting changes against a moving target (T10-T13)

Unchanged in intent, re-expressed in git's words by merge.md: T10 (rename with a dangling caller on the target), T10c
(disjoint changes, merges), T11 (same entity changed on both sides: unmerged), T12 (an attribute retracted while the
target's data uses it), T13 (two agents refactoring one function). They wait on the merge rows (ledger §3.10) and a
model root (MA-17); their tables and proofs stay in this file's history (`c6ae8875d`).

### 4.4 Using cut results (T14, T14s, T14n, T14q)

The value printer cuts a large value and ends the cut with a runnable elision form on the value's handle. Each task's
producer is one-shot (re-running answers nothing), seeded from the agent id, so the only way to the answer is the held
value or its elision form.

| # | Task | Producer | Question |
|---|---|---|---|
| T14 | `the-entry-after-the-fatal-one` | `(comp.log/drain!)`: 3,000 log maps, one `:fatal` at a seeded index in 2,200-2,800 | `comp.find/after-fatal`: the `:msg` after the `:fatal` one |
| T14s | `the-passphrase-near-the-end` | `(comp.doc/fetch!)`: ~60,000 characters, `PASSPHRASE: <word>` in the last 2,000 | `comp.find/passphrase` |
| T14n | `the-port-at-depth-nine` | `(comp.cfg/load!)`: config nested 9 deep, decoy `:port` keys at depths 2-5 | `comp.find/service-port` |
| T14q | `the-largest-order-total` | 3,000 `:comp.shop/order` entities, a plain `q` (set cut: waits on PR-02) | `comp.find/top-customer` |

Graded after D1: followed the cut (an evaluation reading the producer's `result/…` handle or running its elision
form), re-ran the producer, guessed (answer defined with no read before it).

### 4.5 Answering from data (T15, T15x)

A question task declares its answer's Malli schema; `my.agent/answer!` (A1) stores the validated answer as Datahike
data; the proof reads it (F6). T15 `top-march-customer-and-discount`: seeded orders and tiers plus a documented
`comp.price` namespace; the answer needs a query and reading source. T15x `top-february-refund-customer` has no answer:
the right response is to give it back (F7). Both wait on A1, F6, F7.

## 5. Experiments

**Method.** One strategy varies per experiment; every other dial stays at its default; the task set, model, cap and
wake are fixed. A cell is (strategy value × task); each cell runs **5 repetitions** first (DeepSeek flash with
temperature absent is not deterministic; one T1 run costs about US$0.002). A difference counts when the green shares
differ by at least 2 of 5 or the calls-to-green ranges do not overlap; otherwise the cell is re-run to 10 before a
conclusion. The winning value becomes the default for the next experiment (a dial change, recorded with the table).
Each experiment's table is committed beside this file as `bench-E<n>.md` with its run agent ids, HEAD and dial values.

| Exp | Strategy | Cells | Runs | Runs today? |
|---|---|---|---|---|
| **E0** | none: baseline noise | T1 typed, defaults | 5 | after BENCH-3 (T1 exists) |
| **E1** | S7 task wording | T1-T4 × {prose, typed} | 40 | after BENCH-2, BENCH-3: **the first experiment** (no new dial) |
| E2 | S1 wire format | core × {`:transcript`, `:chat`} | 40 | after CX-12 |
| E3 | S3 reference depth | core + T7 × {`:names`, `:summary`, `:source`} | 75 | after BENCH-6, C3 |
| E4 | S2 block placement | core × {`:last`, `:first`} | 40 | after BENCH-7 |
| E5 | S4 help | core × {`:full`, `:short`} | 40 | after BENCH-8 |
| E6 | S5 history | T3, T4, T8 (multi-call tasks) × {`:all`, 2} | 30 | after BENCH-9, C3 |
| E7 | S6 proof shown | core × {`:source`, `:names`} | 40 | after BENCH-10 |
| E8 | paging | T14 family on the winning defaults | 20 | after D1, D2 |

E0 and E1 together cost under US$0.10. The full first round (E0-E7) is about 345 runs, under US$1 off-peak.

**Before the first paid run** (root's decision, flagged): ledger C0 gates the full matrix on a model pass costing an
ordinary pass. E0-E1 are small live runs of the kind runs 1-3 already were; this spec proposes they run before C0 and
the matrix (E2 onward at full size) waits for it.

## 6. What the system must still build for the bench

| Need | Exists | Missing | Card or row |
|---|---|---|---|
| A run's grade | `seon.competency/grade` | variant column, cost | BENCH-1 (landed), BENCH-3 |
| Launch one run | settings overlay through the agent's own REPL, `assign!` at the home, outside message | one function `{task variant n}`; root's settings route (RESPEC R-9) would remove the agent-REPL workaround | BENCH-3 |
| Core tasks | T1 | T2-T4 typed and prose; T5-T8 | BENCH-2, C3 |
| Dials S2-S6 | none | `seon.config.context` and its reads in `seon.context` | BENCH-6 … BENCH-10 |
| Wire format | none | `seon.ai/messages`, `:seon.config.ai/wrapping` | CX-12 |
| Error types | shown text only | `:seon.cluster.eval/error-type` | errors cut |
| Invented output | not recorded | the imitation note as a diagnostic fact | RP rows |
| Paging columns | handles bound per evaluation | `:seon.cluster.eval/result-reads` | D1, D2 |
| Completion | proofs green shown in the block | done without a merge (F5's first half) | RESPEC R-2 |
| Give back, answers | none | F7, A1, F6 | ledger §3.9, §3.11 |
| Branch cleanup | `my.agent/retire-branch!` | the launch deletes each run's branch after grading | BENCH-3 |

## Limits

The grade is proven on a fixture-provider run (one call, a green proof); its token and wall columns read real values
only from a provider that reports usage. `calls-to-green` takes, per proof, its first green member row: a proof that went
green, red and green again counts its first green. Costs are estimates from run 1's token profile. The dial names in §3
are proposals the BENCH cards fix when they land.
