---
title: "Plan (Opus): generative checks and measured gates"
type: research
status: active
created: 2026-10-15
---

# Plan (Opus): generative checks and measured gates

Lane `measured-gates-plan-opus`, one of two independent plans (dig `dig-measured-gates.md`, 06e156774). HEAD
06e156774. Probes on agent `measured-gates-plan-opus`'s own branch in SCI mode, read-only over packaged forms.

**What exists.** The runner runs deftests as members, keys each result by reach digest (the content of its static
closure, schemas included: `seon.test.runner/reach-digests`), reuses another branch's run of the same digest
(`:seon.test.member/reused-run`), and stores each member's cost (`calls`, `alloc-bytes`, `run-ms`, `top`; 006fadb61).
A test that reads a supplied input (`seon.test/task`) already keys its result by that input's content as well
(`seon.test.runner/task-inputs`, `task-identity`). Overruns turn into fail reports with data
(`seon.test.runner/duration-failures`). Nothing generates from a schema or checks a function against its `:=>`.
**Approach in one line:** the derived check is a test member that judges one subject (a declaration), by
generalising the task input to a subject input; everything else (selection, digest, reuse, cost, done rule) is the
installed runner.

## 1. One mechanism: a member judges one subject

The model is `malli.instrument/check` (Malli: "Checks all registered function schemas using generative testing",
a map from symbol to explanation), taken whole but run per declaration through the runner instead of over the
whole program.

- **Two derived-check deftests, written once** in `seon.check-test`: `schema-generates-and-validates` and
  `function-meets-its-contract`. Each reads `(seon.test/subject)` (the task reader, generalised: the supplied input is a
  subject declaration, a task being one kind of subject). No deftest is written per schema or function.
- **Schema check body:** `malli.generator/generator` (cached on the compiled schema by `m/-cached`; constructed and
  not gated), then `malli.generator/sample` with `{:seed s :size n}` twice: the first run warms caches, the second is
  measured. Then `malli.core/validator` over the values, with `malli.core/explain` on the first invalid value as the red's
  data. A red names `:malli.generator/no-generator`, `:malli.generator/such-that-failure` or the explain.
- **Function check body:** `malli.generator/check` on the subject's own value (the branch's Var: compiled or
  interpreted, whatever the branch runs), with `:malli.generator/=>iterations` from a dial. That function sets
  `::m/function-checker` to `malli.generator/function-checker`, which generates from the input schema, applies
  the function, validates output and guard, and shrinks failures to `::m/explain-input`, `::m/explain-output` or
  `::m/explain-guard`. A function whose input names a binding schema (connection, agent, context) cannot be
  generated. The first cut answers it as typed unknown `:binding-input`. Phase 1 counts how many there are.
- **Identity and reach.** The member gains `:seon.test.member/subject` (the declaration's identity value: schema key or
  symbol). Its reach digest is `id[closure(check deftest), closure(subject)]`, which is the fold `task-reach-digests`
  already does, with the subject's closure digest as the input identity. When two branches hold the same schema, the
  second run is a `reused-run` (question 3). An unchanged schema never runs again.
- **Selection.** A derived check of S is a test reaching every declaration in S's closure. A changed declaration X
  selects the checks of each S whose closure holds X, by the same reversed walk as `seon.test.runner/tests-reaching`.
  The work is proportional to X's dependents. Phase 1 measures that number for widely used keys.
- **Cost.** The member's cost is stored as it is today. The check adds its units of work as member data:
  `:seon.check/values`, `:seon.check/nodes` (generated tree size), `:seon.check/calls-made`. One store, no
  `:seon.schema.cost/*` (FEEDBACK-LOOP §2.5 is superseded, never built).

## 2. The gate, and where it never applies

**Where it applies.** The gate applies only inside test members: derived checks and authored deftests. Their inputs
are generated and bounded (seed, size, iterations) or fixture branches. **It never applies** to an agent's eval
request, a render, a live result or a model call over real data. Those are measured and shown (§4) and are never
refused or failed for being heavy (owner, 2026-10-15).

**Signals.** The gate uses allocation (body-thread bytes, which catches unarmed library work, as the `nearest` case
showed) and armed calls (which names our own repeated work). Both repeat exactly warm (COST-REG measured max/min
1.00). Wall ms spreads 4.7–9.8×, so it is only an execution bound and is never compared.

**Against what.** The target is cost per unit of work, which is what the work must do. Generating or validating a
value of k nodes is O(k), so the units are:

| Check | Unit | Dial (`:seon.config.check/*`) |
|---|---|---|
| schema generate | bytes per generated node | `gen-bytes-per-node` |
| schema validate | bytes per validated node | `check-bytes-per-node` |
| function | bytes and armed calls per call, per input node | `call-bytes-per-node`, `calls-per-call` |
| sizes | values, size, seed, `=>iterations` | `samples` 10, `size` 10, `seed` 0, `iterations` 20 |

Each shipped default is **10× the median measured over the packaged corpus** in phase 1. That makes the owner's
"10× over expectation is wrong work" the default without a separate factor dial, and the target is a measured fact,
not an invented one. Measured on the first 730 keys: generation is 5,765 bytes/node at the median, 34 KB at p90 and
4.7 MB at p99. Validation is 500 bytes/node at the median.

**Over the target is red with data:** `:seon.check/over {:unit … :observed … :target … :values … :nodes …}`,
beside the member's own `:seon.test.member/top`. The top is the "responsible instrumented functions":
`seon.profile/explain-slow` does not exist in `src` (dig §2), so COST-REG's citation is corrected to `top`.

**Authored deftests have no unit of work.** Their bound is the execution ceiling. The hard-coded 5000 ms
(`seon.test/run`'s interpreted limit and `seon.test/declared-bound-ms`) becomes `:seon.config.test/bound-ms`, and
`:seon.config.test/bound-mb` is added. Both ship from the p99 of main's stored members. `duration-failures` becomes
`bound-failures` over ms and bytes, and the declared-long marker (`:seon.test/long-ms` plus its reason) raises them.
The specific per-unit gate for an Implement change lives in the changed function's own derived check.

## 3. Stage exits come from the one done rule

The stages need no per-stage code. `seon.cluster.source/done-refusals` already refuses a red test that the branch
defined or changed (`:seon.source/stray-red-error`). The clause widens from "defined or changed" to "defined,
changed, **or judging a subject in `:seon.program/identities`**". With that:

- **Design** (makes data): every schema it registers or changes has a green derived check, within target.
- **Prove** (makes tests): proofs are `designed-red?`, and a bound failure is not a designed red. So "red for the
  stated reason and fast" is the existing Prove handoff plus `bound-failures`.
- **Implement** (makes functions): proofs green, and each changed function's derived check green within target.

**Stubs.** A Design stub throws `:seon.error/unimplemented`. Its function check answers typed unknown
`:unimplemented` rather than red. Mid-chain an unknown does not refuse. On the last task (no `next`) it refuses, the
same rule the stray-red clause applies to every red. This is the one judgment the hybrid must confirm.

## 4. The reply's cost: one line

Measured on this lane's trivial evals, `:seon.repl/request` runs 35–45 printed lines, about 430 tokens (estimated).
Of that, `:jvm-window`, `:shared` and `:unmeasured` are labelled unattributable.

The reply carries one map instead:

```clojure
{:seon.cost/line {:ms 504 :alloc-mb 625 :calls 35503 :tx 1 :tests [3 0] :top [seon.db/declaration-content-key 10.1]}}
```

That is about 40 tokens. `:tests` is `[ran red]`, and `:top` is the request's own heaviest self-time definition from
its sink. Over-target check entries are appended only when present. The full `:seon.cost/request` value stays where
the request is recorded, with the form that pulls it again shown as data beside the line. The current-state block
keeps `:seon.context/cost`.

## 5. What it deletes and reuses

**Deletes:**
- `:seon.config.test/auto-check-cases` and its two test writes (no reader since c537242c0).
- `seon.test.accretion/generatable?`, `schema-row`, `schema-row-in` and `:seon.schema/generatable?` once their
  consumers read the derived check's result (one derivation).
- `every-packaged-schema-answers-generatability-as-a-boolean`, a 12 s declared-long test that generates nothing.
- `seon.test.accretion`'s auto-check render branch and `:seon.test.accretion/auto-check-result` (no producer).
- Both hard-coded 5000s.
- FEEDBACK-LOOP stages 14–15 (`seon.schema.cost/measure!`, `:seon.schema.cost/*`, `:seon.config.schema.cost/*`),
  never built, revised in place.

**Reuses:** the runner, the member cost, reach digests, `reused-run`, the task-input fold, `duration-failures`,
`designed-red?`, `done-refusals`, `tests-reaching`, and Malli's `generator`, `sample`, `validator`, `explain`,
`check` and `function-checker`.

## 6. Phases (HEAD boots after each)

| # | Change | Kind | Red proof (data assertion) |
|---|---|---|---|
| 1 | Corpus measurement: every packaged key generated and validated per §1, as a committed script run in a throwaway check JVM (never default's JVM mode, never chunked on default: see finding 4). Outputs: failures by type, per-unit medians giving the shipped dials, dependents per key, the functions with binding inputs. Committed as `script/seon/check_corpus.clj` plus evidence. The failing families are filed as tasks at their producers. | REPL, read-only | `seon.error-test/an-error-variant-generates` (an `are` table: `:my.edit/no-match-error`, `:my.shell/error` generate without `:malli.generator/such-that-failure`). Red today: §7. |
| 2 | Subject input: `seon.test/subject`, `:seon.test.member/subject`, reach digest by the subject's closure (generalising `task-inputs`). | host-bound (runner) | `seon.test.runner-test/a-subject-check-runs-once-per-subject-content`: the same schema on two branches → second member has `:seon.test.member/reused-run`; a changed form → new run. |
| 3 | `seon.check-test/schema-generates-and-validates`, plus subject selection as reaching tests. | host-bound (selection) | `seon.check-test/a-changed-schema-is-checked-in-its-reply`: a branch registers `[:and :int [:fn neg?] [:fn pos?]]` → the reply's test step holds a red member, subject = that key, `:malli.generator/such-that-failure`. |
| 4 | Bound and target dials; `bound-failures` (ms, bytes, per unit). | host-bound (config) | `seon.test.runner-test/a-member-over-its-bound-is-red-with-its-numbers`: rows for ms over, bytes over, per-node over, under → `:seon.check/over` data or green. |
| 5 | `seon.check-test/function-meets-its-contract` via `malli.generator/check`. | host-bound | rows: `(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (str x))` → red with `::m/explain-output`; a 64 MB-per-call body → `:seon.check/over` with `top`; a binding input → `:unknown :binding-input`. |
| 6 | `done-refusals` widened to subjects, with `:unimplemented` unknown mid-chain. | REPL-first (critical path: patch to its owner) | `seon.cluster.source-test` rows: a red schema check of a changed key refuses; a stub's unknown passes mid-chain and refuses last. |
| 7 | The one cost line in the reply. | host-bound (`seon.mcp`) | `seon.request-cost-test/a-reply-carries-one-cost-line`: `(keys (:seon.cost/line reply))` ⊆ six keys; the requery form is present and returns the full request cost. |
| 8 | §5 deletions, each with its last caller converted. | Sonnet, `--test` | `bin/seon check` plus the converted tests green. |

## 7. Verified at the REPL vs assumed

**Verified:**
- There are 3,813 packaged schema keys.
- In the first 300 keys (sorted), 30 fail with `:malli.generator/such-that-failure`, and every one is an error
  variant written `[:and :seon.error/base [:map …]]`. Malli's `:and` generates from the first child and filters by
  the rest. The 30 failures cost 20.7 s and ~105 GB. The 268 that generate cost 460 ms in total. 2 have no generator.
- In the next 430 non-error keys there are 8 such-that failures (`:seon.ai/completion`, `:seon.boot/instance`,
  `:seon.call-preparation/refusals`, …), each 0.5–0.9 s, plus 7 with no generator and 3 invalid schemas
  (`:seon.await/*`). 18 keys take over 10 ms, for example `:seon.batch/request` at 105 ms.
- Across both batches, no generated value failed its own validator.
- Medians for 10 values: generate 41 µs and validate 3 µs. At p99: 30 ms and 343 µs.
- A trivial reply's cost map runs 35–45 lines.
- `my.program/ns-unmap!` with `'ns 'name` is refused: the call matches the `[context sym]` arity, contrary to its
  docstring example.

**Assumed** (proved in the phases):
- Subject reach composes through `task-reach-digests` without new reach code.
- Per-node allocation repeats exactly on the second sample run.
- The p99-of-members bound does not red cold runs.
- The binding-input share of contracted functions is small.
- A stored full request cost exists to requery: the location is unverified.
- The full corpus count: the remaining ~2,800 non-error keys were not run (a chunk hit the 30 s SCI time limit). Extrapolated from the samples: all 269 keys named `*error` (30 of 30 sampled failed) plus about 4% of the rest, roughly 400 failing keys.

**Findings for root** (out of scope; the producer task files are not this lane's):
1. Redefining a function in `scratch.mgates` re-interpreted the dependent value `def`s on the next requests
   (`seon.sci.eval/install-row!`, "Cannot interpret scratch.mgates/r200"). Test-step times were 24 s and 12 s, with
   step allocation of 60–105 GB, on requests that ran no tests.
2. The error variants' `[:and base map]` form made every sampled error schema (30 of 30; 269 keys are named `*error`) fail to generate, at ~0.7 s and ~3.5 GB each. The fix
   belongs to the errors cut (`:multi` on `:seon.error/type`, or Malli's `:merge`).
3. The `ns-unmap!` arity collision with its docstring example (above).
4. This lane's probes allocated ~400 GB in total on default and caused ~3 min of GC across requests. Phase 1 must
   run its chunks from a throwaway check JVM, not default.
