---
title: Agent competency suite
type: research
status: proposed (design only; no code)
date: 2026-10-08
lane: competency-spec
---

# Agent competency suite

What exists: tasks with proofs (`seon.task/create!` src/seon/task.clj:439, `assign!` :467, proofs derived by
`task-edges` :321 from deftests tagged `:seon/tasks`), the model loop (`seon.turn`, request at turn.clj:4636-4638),
`seon.ai/request-body` (src/seon/ai.clj:561-599, one optional system message plus one user message at :572-575),
attempt rows with provider usage (turn.clj:4219-4250) and the test runner recording member results. Data flow: root
creates an agent run (settings overlay = the variant, own branch, setup pass, assignment, wake) → the agent works
through the one REPL path → root runs the task's proofs on the agent's branch → one query reads the score from facts
already stored. The suite adds tasks, proofs, two dials, one adapter function, two small facts and one query; no
harness, runner, store or printer.

## 0. Prerequisite: a model pass within the ordinary-pass budget

Owner ruling 2026-10-08: no world accepts 926-945 MB and 536-568 ms per model pass through the fixture provider
(`seon.pass-probe/run-model running 1 #{}`, test/seon/pass_probe.clj:328; an MCP SCI pass of the same form costs about
23 MB). **No model run of this suite starts until `run-model` reports a model pass inside the ordinary-pass budget
(agent-loop §3: < 200 ms, < 5 MB, 1 machinery transaction, Seon's work only).** Lane `model-pass-cost` owns that cut.
Everything in section 6 marked "today" needs no model pass; everything else waits.

## 1. The tasks

One task file, `resources/seon/tasks/seon.competency.edn` (task symbols `seon.competency/<name>`). One proof namespace,
`test/seon/competency_test.clj`, holding the proofs, each task's `setup` code (evaluated on the run's branch before
assignment) and its `oracle` code (a known solution, used only by the red-to-green regression in slice C1). Each run is
a fresh agent on its own branch, so every task resets by construction: the branch is unlinked after scoring.

A proof is a JVM deftest. Its body runs as its member (`kernel/*host-world*` bound at src/seon/test.clj:277) on a
branch off the agent's commit, so it reaches the agent's SCI definitions only through the agent's own path:
`support/agent-value` (test/seon/test_support.clj:465), the one REPL path in a given context. Every proof asserts data.

```clojure
(ns seon.competency-test
  "Proofs of the seon.competency tasks; each runs as its member on a branch off the run's agent."
  {:seon.test/fixture true}
  (:require [clojure.test :refer [deftest is]]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- v
  "The value `code` answers in this member's world."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [code]
  (support/agent-value (kernel/world-ctx) code))
```

The namespace's `:seon.test/fixture` marker keeps these red-until-worked proofs out of bare namespace selection
(src/seon/program.cljc:283-296); slice C1 proves the bulk tier skips them and a named run still runs them.

| # | Task (`seon.competency/…`) | What the agent must do | Setup | Passes |
|---|---|---|---|---|
| T1 | `area-is-contracted` | In `comp.geo`, `area [w h]` with contract `[:=> [:cat pos-int? pos-int?] pos-int?]` returning `w*h` | none | 1-2 |
| T2 | `quantity-schema-then-function` | Register `:comp.order/qty` as `[:int {:min 1}]`, then `comp.order/double-qty` contracted on it | none | 1-3 |
| T3 | `slug-has-a-passing-test` | Write `comp.text-test/slug-lowercases` (≥2 `is`) for the given `comp.text/slug` and run it green with `my.test/check` | `slug` | 2-4 |
| T4 | `app-calls-util-through-an-alias` | Create `comp.util/clamp` (0-10), then `comp.app` aliasing `comp.util` as `u` with `bounded` calling `u/clamp` | none | 2-3 |
| T5 | `total-takes-a-tax-rate` | Change `comp.price/total` from `[items]` to `[items tax-rate]`; fix its caller `invoice` to pass `0.1` | `total`, `invoice` | 2-4 |
| T6 | `ledger-total-reads-the-database` | Register `:comp.ledger/id` (identity) and `:comp.ledger/amount`, transact 10, 20 and 30, define `comp.ledger/total` over a query | none | 2-5 |
| T7 | `encode-all-uses-the-library` | `comp.lib` holds a reversible string encoder among six functions; find it with `dir`/`doc`/`source` and define `comp.use/encode-all` over a vector | `comp.lib` | 2-4 |
| T8 | `mean-test-goes-green` | `comp.stats-test/mean-of-one-and-two` is red; fix `comp.stats/mean` without changing the test | `mean` (uses `quot`), the red test | 2-4 |

Each task's intent names its namespace and the success test; its `:must-read` names the setup functions.

```clojure
(deftest ^{:seon/tasks #{'seon.competency/area-is-contracted}} t1-area-is-contracted
  (is (= 6 (v "(comp.geo/area 2 3)")))
  (is (some? (:seon.error/type (v "(comp.geo/area -1 3)"))) "the armed contract refuses")
  (is (some? (v "(:malli/schema (meta #'comp.geo/area))"))))

(deftest ^{:seon/tasks #{'seon.competency/quantity-schema-then-function}} t2-quantity-schema-then-function
  (is (= ["[:int {:min 1}]"] (v "(seon.db/q '[:find [?f ...] :where [?s :seon.schema/key :comp.order/qty]
                                                                  [?s :seon.schema/form ?f]] (seon.db/db))")))
  (is (= 4 (v "(comp.order/double-qty 2)")))
  (is (some? (:seon.error/type (v "(comp.order/double-qty 0)")))))

(deftest ^{:seon/tasks #{'seon.competency/slug-has-a-passing-test}} t3-slug-has-a-passing-test
  (let [[passes failures errors] (v "(first (seon.db/q '[:find ?p ?f ?e :where
                                      [?m :seon.test.member/symbol comp.text-test/slug-lowercases]
                                      [?m :seon.test.member/pass-count ?p] [?m :seon.test.member/fail-count ?f]
                                      [?m :seon.test.member/error-count ?e]] (seon.db/db)))")]
    (is (<= 2 passes))
    (is (= [0 0] [failures errors]))))

(deftest ^{:seon/tasks #{'seon.competency/app-calls-util-through-an-alias}} t4-app-calls-util-through-an-alias
  (is (= [10 0 7] (v "(mapv comp.app/bounded [15 -3 7])")))
  (is (= 'comp.util (v "(ns-name (get (ns-aliases 'comp.app) 'u))"))))

(deftest ^{:seon/tasks #{'seon.competency/total-takes-a-tax-rate}} t5-total-takes-a-tax-rate
  (is (= '([items tax-rate]) (v "(:arglists (meta #'comp.price/total))")))
  (is (== 33.0 (v "(comp.price/total [10 20] 0.1)")))
  (is (= "Total: 33.0" (v "(comp.price/invoice [10 20])"))))

(deftest ^{:seon/tasks #{'seon.competency/ledger-total-reads-the-database}} t6-ledger-total-reads-the-database
  (is (= 3 (v "(count (seon.db/q '[:find ?e :where [?e :comp.ledger/id]] (seon.db/db)))")))
  (is (= 60 (v "(comp.ledger/total (seon.db/db))"))))

(deftest ^{:seon/tasks #{'seon.competency/encode-all-uses-the-library}} t7-encode-all-uses-the-library
  (is (= (v "(mapv comp.lib/shift-letters [\"ab\" \"Seon\"])") (v "(comp.use/encode-all [\"ab\" \"Seon\"])")))
  (is (= ["ab"] (v "(mapv comp.lib/unshift-letters (comp.use/encode-all [\"ab\"]))"))))

(deftest ^{:seon/tasks #{'seon.competency/mean-test-goes-green}} t8-mean-test-goes-green
  (is (= 3/2 (v "(comp.stats/mean [1 2])")))
  (is (= (get-in setups ['seon.competency/mean-test-goes-green :test-source])
         (v "(seon.db/q '[:find ?s . :where [?t :seon.test/sym comp.stats-test/mean-of-one-and-two]
                                              [?t :seon.test/source ?s]] (seon.db/db))"))
      "the test is unchanged"))
```

## 2. The comparison matrix

| Axis | Values |
|---|---|
| Message wrapping | **A** `:transcript`: one user message holding the REPL transcript (today). **B** `:chat`: the opening and each input as user messages, each form text as an assistant message, its envelope as the next user message. **C** `:prefix`: B plus a final assistant message `{"prefix": true}` holding the prompt the model continues from (`comp.geo=> ` for REPL, `{:forms [` for DATA); DeepSeek beta only (`https://api.deepseek.com/beta`), since Anthropic refuses prefill on Claude 4.6 and later (docs/prds/next-turn/research/token-level.md:58) |
| Agent input | **REPL**: text read by the reply reader (agent-loop §4.3), envelopes shown as the REPL presentation. **DATA**: the reply is one EDN map `{:ns comp.geo :forms [(defn …) …]}`, envelopes shown as maps including `:form` (agent-loop §4.4 DATA) |
| Model | `deepseek-flash`, thinking `:disabled` (config/default.edn:402, :429); `deepseek-v4-pro` second, once flash's table is read |

Six cells (3 wrappings × 2 inputs) × 8 tasks × 3 repetitions = 144 runs per model. Every run: call cap 5 per wake,
stop sequence absent, temperature absent, the same opening (`(help)` for its input, the task's `doc`).

## 3. Scoring per run, read from the database

A run is identified by its agent: the variant is its settings overlay (`:seon.agent/settings`), the task is its
assignment (`:seon.task/assigned`). No run entity is added.

| Score | Recorded today | Missing (minimal fact, consumer) |
|---|---|---|
| Proofs green | test member rows `:seon.test.member/pass-count`, `fail-count`, `error-count` under a `:seon.test.run/*` request with `:seon.test.run/branch` | none; scoring runs the task's proofs by identity (`seon.test/run` with `:seon.test/identities`, as `my.test/run-owned` does, src/my/test.clj:31) on the agent's branch as root |
| Passes | today one `:seon.turn` per model pass with `:seon.turn/attempts`; after S4/S6 the `:model`-origin evaluations grouped by pass id | none |
| Prompt, completion, cached tokens | `:seon.ai.usage/prompt-tokens`, `completion-tokens`, `cached-tokens` on each attempt row (turn.clj:4243; `normalize-usage` ai.clj:864-875 maps DeepSeek's `prompt_cache_hit_tokens`) plus the raw `:seon.ai.attempt/usage-edn` | none; misses = prompt − cached |
| Read errors, refusals | `:seon.cluster.eval/error` is shown text only; the no-forms refusal is a row with `:seon.cluster.reply/no-forms-error` text (turn.clj:3037) | `:seon.cluster.eval/error-type`, the error's `:seon.error/type` keyword. Consumers: this score and the errors cut's per-signature recording |
| Fabricated results | not recorded | the reader's imitation note (agent-loop §4.3, `;; =>` and `ns=>` prose lines, "deferred to the exchange") carried in `:seon.cluster.eval/diagnostics` with its `:seon.error/type`. Consumers: the agent's next context and this score |
| Wall time | `:seon.ai.attempt/at` per attempt; the turn's `opened-tx`/`closed-tx` instants; provider latency only as the model row's overwritten `:seon.ai.model/last-latency-ms` | `:seon.ai.attempt/latency-ms` from the value `complete` already returns (ai.clj:1311). Consumers: this score and the S0 probe's provider share |
| Cost | derived: usage × the model row's `input`/`cached-input`/`output-usd-per-mtok` (live: 0.15 / 0.003 / 0.60) | none for off-peak; peak (×2, weekdays 01-04 and 06-10 UTC) is counted from `:seon.ai.attempt/at`, no stored schedule exists after the window entities were deleted |

The query `seon.competency/scores` takes a database and answers one map per run; its AI render is one row per run and
a per-cell summary (median passes, green share, tokens, cost); its HTML face is the same table.

## 4. What the system needs

| Need | Exists | Missing | Smallest slice | Waits for |
|---|---|---|---|---|
| Wrapping dial | No. `request-body` builds one user message from `:seon.ai/prompt` | `:seon.config.ai/wrapping` (`:transcript :chat :prefix`), agent overlay over the model row's default; `seon.ai/messages`, one pure function `[wrapping opening exchanges] → messages` with one branch per wire format; `request-body` takes `:seon.ai/exchanges` when present | C5 | lands today (pure); used after S4 |
| Exchanges to build from | Evaluation rows hold `:seon.cluster.eval/source` and `:seon.eval/shown`, but today's prompt is the system-turn refresh text | The proc-held transcript as exchange values (agent-loop S4) | — | S4 |
| Data entrance | The read stage is a value (`:seon.batch/read-stage`, src/seon/sci/batch.clj:19-23) with modes `:reply` and `:code` | A `:data` mode: read the reply map once with locations, evaluate each `:forms` element at its source span, `:ns` as the pass's start namespace (the seam MCP's `namespace` argument uses); the DATA presentation is S4's | C6 | repl-plan P7 (one read at the live cursor) |
| Entrance dial | No | `:seon.config.ai/entrance` (`:repl :data`); `:data` also selects the DATA presentation | C6 | as above |
| Launch N runs | Pieces: `my.agent/settings!`, own branch per agent, `seon.task/assign!`, `my.message/send` as the wake, `:seon.config.run/max-episode-runs` as the cap (pass_probe.clj:340-344) | One function composing them per run `{task variant n}`: settings overlay, setup as an outside pass, assign, wake; after S6 it is the S6 launch seam | C7 | prerequisite §0 |
| Results query and render | No | `seon.competency/scores` plus its render pair | C4 | lands today on oracle runs |

**Today's loop versus the cut.** Runnable today without a model pass: the tasks, the proofs, the oracle regression,
the score query and the wrapping adapter as a pure function. Not runnable today: any model run. Recommendation: do
**not** start model runs on today's loop. It costs 926-945 MB per pass (owner ruling, §0), and its context is the
system-turn refresh that S4 deletes, so variant A measured today would measure a context nobody keeps, and B and C have
no exchanges to build from. Order: §0's cut → one A × REPL smoke run of T1 to validate scoring → S4 → full matrix.

## 5. Run budget (DeepSeek flash)

Prices, USD per million tokens, off-peak: cache miss 0.15, cache hit 0.003, output 0.60 (config/default.edn:537-541,
read back live from the model row; provider page 2026-10-07 in docs/research/agent-platform/2026-10-07/deepseek/
findings.md). Peak doubles everything. Calibration: live run 1 spent about US$0.005 on 11 calls, 124,157 prompt tokens
(86% cache hits) and 3,555 completion tokens (docs/research/agent-platform/2026-10-07/live-run/report.md).

Per run, assuming an opening of ~3k tokens, ~1.4k appended per pass and ~400 completion tokens per pass:

| Run | Prompt tokens (hit / miss) | Completion | Cost |
|---|---|---|---|
| 3 passes | 13.2k (7.4k / 5.8k) | 1.2k | ~US$0.0016 |
| 5 passes (cap) | 29k (20.4k / 8.6k) | 2.0k | ~US$0.0026 |

Full matrix, 144 runs: **~US$0.23 typical, ~US$0.37 if every run hits the cap, ≤ US$0.75 at peak.** With no cache hits
at all the cap case is ~US$0.0055 per run, ~US$0.80 per matrix. Proof runs and scoring cost nothing. `deepseek-v4-pro`
(0.66 / 0.022 / 1.98) is about 4× flash. Wall time: 1-5 s per call (live run 1), so 5-25 s per run.

## 6. Slices (each about 15 minutes)

| # | Slice | Files | Proof tests | Runs |
|---|---|---|---|---|
| C0 | Prerequisite: a model pass within the ordinary-pass budget (lane `model-pass-cost`) | its own | `seon.pass-probe-test` model row inside agent-loop §3 | owner's lane |
| C1 | Task file with T1; `seon.competency-test` with `v`, T1's setup/oracle data and proof; the red-to-green regression. First probe: a host proof body reaches a branch-only SCI definition through `kernel/world-ctx`; then that the bulk tier skips the fixture namespace | `resources/seon/tasks/seon.competency.edn`, `test/seon/competency_test.clj`, `test/seon/competency_oracle_test.clj` | `seon.competency-oracle-test/t1-goes-red-to-green` (fresh agent branch via `support/with-running-agent`, setup through `seon.mcp/call`, proofs red, oracle, proofs green) | today |
| C2 | T2-T4: setups, oracles, proofs | same three | `seon.competency-oracle-test/t2-…`, `t3-…`, `t4-…` | today |
| C3 | T5-T8 | same three | `t5-…` through `t8-…` | today |
| C4 | `seon.competency/scores` and its render pair over oracle runs (passes 0, proofs green, tokens a typed unknown) | `src/seon/competency.clj`, its schema resource, test | `seon.competency-test/scores-read-an-oracle-run` (in a non-fixture namespace) | today |
| C5 | `:seon.config.ai/wrapping`; `seon.ai/messages`; `request-body` takes exchanges | `src/seon/ai.clj`, `resources/seon/schemas/seon.config.ai.edn`, `test/seon/ai_test.clj` | `seon.ai-test/each-wrapping-builds-its-wire-messages` (A one user message, B alternating roles, C ends with `"prefix" true`) | today |
| C6 | `:seon.config.ai/entrance` and the `:data` read mode | `src/seon/sci/forgive.clj`, `src/seon/sci/batch.clj`, tests | `seon.sci.forgive-test/a-data-reply-evaluates-each-form-at-its-span` | after repl-plan P7 |
| C7 | The launch function and the two facts (`:seon.ai.attempt/latency-ms`, `:seon.cluster.eval/error-type`) | `src/seon/competency.clj`, `src/seon/turn.clj` (attempt row), schemas | `seon.competency-test/a-fixture-provider-run-scores-green` (scripted oracle replies through `support/with-provider`, tokens from the fixture's usage) | after C0 |
| C8 | Smoke: one A × REPL run of T1 on deepseek-flash, then the full matrix after S4 | none (root forms) | the score table, committed under `docs/research/agent-platform/<date>/competency/` | after C0, then S4 |

## Limits

Read, not run: the critical assumption in C1 (a proof body reaching branch SCI definitions through `world-ctx` and
`support/agent-value`) and the fixture marker's effect on the bulk tier. The cost per run is an estimate from one
live run's token profile, not a measurement of these tasks. The one live read on default was the flash price row and
the attempt count (1).
