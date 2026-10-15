---
title: "Dig: measured gates and generative checks"
type: research
status: active
created: 2026-10-15
---

# Dig: measured gates, generative checks and stage exits (2026-10-15)

Lane `dig-measured-gates`. Research only; no source edited. Checked against the working tree at HEAD `f4f2e4874` (branch `refactor/agent-platform`). No JVM was started, so every count below is from source or git, not a run. Owner goal (2026-10-15): every declared schema generates and validates quickly; tests also run generatively; agent-written functions are generatively tested against their contracts and measured; slow-by-target is gated per chain stage (Design: data and schemas; Prove: tests; Implement: functions).

## 1. History

- **COST-REG** (`docs/seon/schedule/cards/COST-REG.md`, status active). Slice 1 landed as `006fadb61`: a test member stores its own `calls`, `alloc-bytes`, `run-ms` and `top` (`seon.test/measured-body`, written by `seon.test.runner/complete-members`). Slices 2 to 6 (baseline capture at assignment, red `:seon.test/cost-regression`, root acceptance, health fold) are marked superseded by FEEDBACK-LOOP. The card's own measurements motivate the gate: allocation and armed calls repeat exactly warm (max/min 1.00), wall ms spreads 4.7 to 9.8x at single-digit ms, and `malli.error/levenshtein` makes 0 armed calls, so calls alone would have missed the 15.4 GB `my.program/nearest` form.
- **`809310b7a`** ("stored member cost shown, not gated"): cut the COST-REG baselines, dials and growth tests and kept the stored member cost as data. Its message says the five rules and about 600 deletable lines (`seon.profile.measurement`, `seon.health/definition-comparisons`, seven dials). The reason is recorded in FEEDBACK-LOOP §1: a digest's p50 "moves with its callers, not its code", and the same-test comparison needs a baseline kept honest. The owner's false-regression ruling (FEEDBACK-LOOP §2, 2026-10-10) is the bar it must clear.
- **`78c32f6ff`**: deleted the seven unread `:seon.config.test.cost` dials (factor, floor, assign-runs), kept `top`, and added the three `:seon.config.fn.cost` defaults HEAD was missing (its config test was red).
- **FEEDBACK-LOOP** (`docs/seon/schedule/cards/FEEDBACK-LOOP.md`, active). Its §2 step 5 specifies schema gen and validate cost: at contract writing, `n` values from each new or changed schema via `malli.generator/sample` with a fixed seed, recorded as `:seon.schema.cost/{gen-us,check-us,samples}` on the schema entity once per `:seon.program/definition-digest`, with a finding over `:seon.config.schema.cost/max-us`. The card's commit `e548ca9a3` re-sliced it to stages 14 and 15. **Not built**: no `schema.cost` or `config.schema` string exists under `src`, `resources` or `test`.
- **Plan**: `docs/seon/plan.md` §2 essential 1 (line 107): "Committed (FEEDBACK-LOOP stages 1–13, 16 …). Being cut to one line: an agent learns it wrote slow software in forty tokens, not five hundred." The "cut to one line" is still open.
- **Other history for the gate question**: `ab866dc1e` (reply compares live cost with main via `seon.batch/cost-diff`), `e181623ba` (cost entities hold latest values; retired `:seon.db.transaction/{ms,calls,hot,repeated}` stamps, which had no reader), `7be9c0274` (current-state block shows `:seon.context/cost`), `4e22d2256` (`check` excludes declared-long tests in process).

## 2. What measures today

- **`seon.profile/measure`** (`src/seon/profile.clj`, around line 636): thread allocation, wall ms and armed calls over a body. Its calls are the JVM-wide `seon.profile` cells, so concurrent work counts. Its output includes `:seon.cost/jvm-window` (line 694), built by `seon.batch/jvm-window` (`src/seon/batch.clj:192`).
- **Cells**: `seon.profile/cell` (line 130), per callable: histogram, total, self, max, entries, throws, and an ordinal. `seon.profile/cells` (349) and `counts` (365) read them.
- **Per-world sink**: `seon.batch/sink` (line 94), `top` (143), `seon.profile/sink-counts` (624). This is the per-request call map (inclusive and self nanoseconds per `[sym digest]`), and it is the live number a reply uses.
- **`seon.profile/explain-slow`**: no `defn` exists in `src` (grep). `COST-REG` refers to it; it is gone or was never landed. The slow threshold survives only as `seon.profile/slow-ms` = 1000 (`profile.clj:56`), used once for `:seon.profile/over-second-count` (line 606). FEEDBACK-LOOP §1 marks `seon.profile/begin`, `explain`, `explain-slow` for deletion after S8.
- **What a test member stores**: `seon.test.member/top` (`resources/seon/schemas/seon.test.member.edn:23`), at most `:seon.config.test.cost/top` rows `[sym calls self-ms]`. `calls`, `alloc-bytes` and `run-ms` per `006fadb61` (not re-read in the schema file here).
- **Reply cost map producer**: `seon.mcp/eval-clj` (`src/seon/mcp.clj`): `:seon.cost/jvm-window` at line 418–419, `:seon.repl/request` at line 447 from `seon.batch/request-cost` (`batch.clj:211`). `request-cost` builds `:seon.cost/stages` with an `:unattributed` remainder (line 223). `:seon.cost/step` comes from `seon.flow` (`flow.clj:301`) and is attached in `seon.turn` (`turn.clj:5877–5880`). `:seon.cost/diff` is `seon.batch/cost-diff` (`batch.clj:291`), attached in `mcp.clj:280–301`.
- **Size of the cost map**: commit `ab866dc1e` records "Trivial reply: 0 entries, ~193 tokens, 1 transaction" for the diff part only. The full map was not measured in this dig. Planners should measure it on a reply before deciding the one-line cut.

## 3. What bounds and gates today

- **Per-test bound is a hard-coded 5000 ms in two places.** `src/seon/test.clj:369`: `(or (:seon.test/long-ms …) 5000)` becomes `:seon.sci.eval/time-limit-ms` for interpreted bodies. `src/seon/test.clj:2540` (`declared-bound-ms`): `(get declared % 5000)`. Compiled bodies are bounded by `.join` on `timeout-ms` (test.clj:389), from `:seon.test/check-time-limit-ms` (request, or the cluster dial, test.clj:2786–2802; schema `resources/seon/schemas/seon.test.edn:199`). A declared-long test sets `:seon.test/long-ms` (e.g. 12000 on `every-packaged-schema-answers-generatability-as-a-boolean`). CLAUDE.md calls a hard-coded policy number a defect, so this is one.
- **Live dials**: `:seon.config.test/step?` and `:seon.config.test/step-time-limit-ms` (10000 ms, `resources/seon/schemas/seon.config.test.edn`); `:seon.config.test.cost/top` (10); `:seon.config.fn.cost/{min-calls 20, cold-calls 1, min-total-us 1000, hysteresis 1.41}` (`seon.config.fn.cost.edn`). `:seon.config.schema.cost/max-us`: absent.
- **Dead dial**: `:seon.config.test/auto-check-cases` is declared (`seon.config.test.edn:1`) and set by two tests (`test/seon/program_test.clj:1501`, `test/seon/config_application_test.clj:39`), but nothing in `src` or `script` reads it. Its reader was in the install gate, deleted by `c537242c0` ("the dead install-gate keys and accretion's gate machinery go"). `seon.test.accretion` still renders `:auto-check` failures (`src/seon/test/accretion.clj:83`) and the schema keeps `auto-check-result` (`seon.test.accretion.edn`), with no producer found.
- **Stage predicates gate nothing on cost** (§5).

## 4. Generative checks today

- **`malli.generator` uses in `src`**: `src/seon/error.clj` (line 23; `mg/generate` at size 1, seed 0 for error examples, line 1828; about 40 `gen/` generators from line 44 to 2973, for contract-based error generation); `src/seon/test/accretion.clj:7,11–22` (`generatable?`); `src/seon/schema.clj:336` (a comment). `generatable?` calls `mg/generator` only: it builds a generator and returns a boolean. It never calls `mg/generate` or validates a value.
- **In `test`**: about twenty files call `mg/generate` with seeds (`flow_test.clj:333`, `schema_test.clj:211,1704`, `health_test.clj:67,130`, `effect_test.clj:1084`, and others). `refusal_grammar_test.clj:111` uses `::m/function-checker mg/function-checker` with `::mg/=>iterations 1` on `[:=> [:cat [:= 7]] :int]`. This is the only function-checker use found.
- **Instrumentation**: `src/seon/instrument.clj` has no `malli.generator` call and no `:gen` option. Contracts are checked at call time (inputs prevent entry, outputs refuse), not generated.
- **Per-schema generate-and-validate check**: none. `every-packaged-schema-answers-generatability-as-a-boolean` (`test/seon/test/accretion_test.clj`, around line 100–135) asserts `every? boolean?` and that the two routes agree. It does not generate or validate. Its docstring records 2.5 s at the REPL and 7.4 s under a request for about 3,350 packaged schemas. (Another note counts 3,568 keys on 2026-09-27: `schema-row-in` docstring.)
- **Per-function generative check**: none automatic. The auto-check machinery is the dead dial above.
- **Count of schemas that fail `mg/generate` today**: not recorded in any test or note found. `accretion_test.clj:56,68` give two explicit false cases. A count needs a run; this dig could not do one (no JVM mode).
- **Malli facts** (`docs/research/agent-platform/2026-10-10/astra-malli-behavior.md:82`): `malli.generator/function-checker` generates arguments, applies the function, checks output and then the optional guard. It defaults to 100 trials per arity, overridable by `:malli.generator/=>iterations`. It returns nil or failure data with shrinks. Its seed does not survive a changed generator (same file, line 275).

## 5. Stage exits

- **Design**: `seon.step.design/handoff` (`src/seon/step/design.clj:24–55`). Checks: `:sections` (What exists and Approach present), `:problem-changed`, `:references` (each resolves), `:producer-not-cited` (What exists cites a first-party function and a deftest, Approach a function), `:data-not-modeled` (a `:seon.schema/key` changed on the branch, or one is referenced). **No generation, no cost.** Schema validity is only that the key changed.
- **Prove**: `seon.step.prove/handoff` (`src/seon/step/prove.clj`). Checks: `:no-proofs`, `:not-a-deftest`, `:not-designed-red` (via `runner/designed-red?`), `:unreached` (a changed or Approach-cited function in none of the proofs' reaching sets, `seon.test.runner/tests-reaching`). **No cost, and it runs no tests itself.** A proof's duration is bounded only by the runner's 5000 ms.
- **Implement**: no predicate (TASK-CHAIN §4, `docs/seon/schedule/cards/TASK-CHAIN.md` around line 477). Its done rule is `seon.cluster.source/done-refusals` (`src/seon/cluster/source.clj:1254`): proofs green, no regression of main, no stray red, no contract gap. Its template text (`resources/seon/tasks/seon.step.implement.edn`) asks only for complete contracts. **No cost.**

## 6. Prior attempts and why they stopped

- **Baseline-relative test cost gate (COST-REG slices 2–6)**: cut in `809310b7a` and superseded by FEEDBACK-LOOP. Reasons on record: baselines cost ~250–470 ms of request overhead per member and gating sets run to 326–2,338 tests for platform symbols; wall ms is noisy (COST-REG measurements); calls miss unarmed library work; the owner rules a false regression is the bug.
- **Definition p50 gate**: `seon.health/definition-comparisons` deleted (`809310b7a`, FEEDBACK-LOOP §1): traffic-driven p50 per digest moves with callers.
- **Install-time auto-check gate**: `469107b79` seeded candidate contract auto-checks, `d8040a452` wired green-to-install feedback, `c537242c0` deleted the install gate and its keys (the auto-check dial lost its reader).
- **Generatability**: `69f82fbcc` "Derive schema generatability for accretion testing" (boolean only).
- **Generative behaviour specs**: `86fcbd711` (card `BEHAVIOR-SPEC.md`: "a task's proof states behaviour, data and cost"; proofs as contract plus guard, seeded properties, growth over n to 8n against a reference). Not read in full here.
- **Stored schema gen and validate cost (FEEDBACK-LOOP §2.5)**: designed in `388423db4` and `e548ca9a3`, never implemented.

## What exists to build on

- Measurement: `seon.profile/measure`, `seon.profile/calls-of`, `seon.profile/sink-counts`, `seon.batch/sink`, `seon.batch/top`, `seon.batch/request-cost`, `seon.batch/jvm-window`, `seon.batch/cost-diff`, `seon.test/measured-body`, `seon.test.runner/complete-members`, `:seon.test.member/top`.
- Schema entity: `:seon.schema/key`, `:seon.program/definition-digest`, `:seon.schema/generatable?` (`src/seon/test/accretion.clj`, `generatable?`, `schema-row-in`, which compiles through the projection's registry).
- Dials: `:seon.config.test.cost/top`, `:seon.config.fn.cost/*`, `:seon.config.test/step-time-limit-ms`, `:seon.test/check-time-limit-ms`, `:seon.test/long-ms` markers.
- Stage predicates: `seon.step.design/handoff`, `seon.step.prove/handoff`, `seon.cluster.source/done-refusals`, the shared `seon.step/*` helpers (`changed`, `results`, `cited-fns`, `deftest?`).
- Malli: `malli.generator/generator`, `malli.generator/generate`, `malli.generator/sample`, `malli.generator/function-checker` (`reference-code/malli/src/malli/generator.cljc`), `::mg/=>iterations`.
- Existing sentinel: `script/seon/sentinel.clj` (end-to-end probes measured through `seon.profile/measure`, reply cost as the figure).

## Open questions for the planners

1. **Reconcile the goal with the rule against invented targets.** CLAUDE.md says to judge cost against what the work must do, "never an invented target", and the owner's 2026-10-08 memory says "expected cost, not targets". "Configurable perf targets" can be dials only if each is derived from the stage's work (for example, a schema's budget from its size, a proof's from its reach). Which is the owner's intent?
2. **Which signal gates?** Allocation and armed calls repeat exactly warm; wall ms does not. COST-REG's own evidence favours allocation plus calls as the gate, and wall ms as a finding only. Confirm before a dial is designed.
3. **Schema gen and validate** (FEEDBACK-LOOP §2.5): place it at Design (the schema is the stage's product) or at Prove (as a proof)? Its cost is already keyed by the digest, so it is measured once per change. Who writes it, and what does a failing sample refuse?
4. **Per-function generative tests from contracts**: the function's contract is already the instrumented input and output check. Generating from it (`mg/function-checker`) needs a generator per declared input, and `:gen/elements` annotations in `src` show some schemas already carry them. Is the proof the generated run, or a reaching test that calls it?
5. **Dead dial**: delete `:seon.config.test/auto-check-cases` (and the two test writes) or reuse its name for the generative check? A declared dial with no reader misleads an agent reading the config.
6. **Hard-coded 5000 ms** (test.clj:369 and :2540): move to a dial whose default is derived from the proof's stage, or keep the declared-long mechanism only?
7. **Count first.** The number of registered schemas failing `mg/generate` is unknown. A lane on its own branch (not root, not JVM mode) can measure it by generating each packaged schema with a seed and a size budget, and record the failures and times. That count decides how large the schema stage's work is.
8. **Measure the reply map** (§2): tokens of the full `:seon.repl/request` on a typical eval, before choosing the one-line cut.

## Stated unknowns

- No JVM was started. Every behaviour above is from source or history, not a run.
- `seon.profile/explain-slow` is absent from `src`; the card's reference was not checked against deleted history beyond the grep.
- `calls`, `alloc-bytes` and `run-ms` on the member are attributed to `006fadb61` and COST-REG; I did not re-read `resources/seon/schemas/seon.test.member.edn` beyond line 23.
