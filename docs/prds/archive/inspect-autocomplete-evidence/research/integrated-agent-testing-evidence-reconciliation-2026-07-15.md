---
type: research
status: completed
tags: [research, agent, database, schema]
---

# Integrated agent-testing evidence reconciliation — 2026-07-15

## Question and verdict

This bounded audit answers what the current runtime-reliability checkout has
actually integrated from the Inspect, ACME tool-refinement, planning, and
autocomplete lanes. It distinguishes executable source and retained native
evidence from experiment designs and target architecture.

The foundation is real and substantial: content-pinned Inspect execution,
native task/scorer adapters, exact turn and database-coordinate retention,
deterministic ordinary-tool fixtures, a first-class long-term-planning task,
positive program-graph tool eligibility, and a canonical observed-autocomplete
manifest are implemented. The old ACME branch has no missing tracked result
that should be blindly cherry-picked.

The headline experiment is not graduated. There is no accepted Inspect run in
which a named larger planning model produces a plan, a separately identified
smaller model encodes that plan through `my.plan`, executes representative
ordinary work, survives restart, and reaches the frozen scorer thresholds.
Current source contains the scorer contract and offline arms for that run;
historical live drives supply useful failure evidence, not the reproducible
comparison. Likewise, one successful Qwen 3.5 2B BFCL sample and one failed
Qwen2.5 Coder 0.5B database sample do not establish broad small-model tool
usability or the 90% target.

Audit source was the moving shared checkout on
`codex/runtime-reliability-refactor`; the last source snapshot observed before
writing was `f5e35a8e7ccbb308c339d9994f2ad8b875490265`, followed by unrelated
concurrent commits. Claims below therefore bind to named files and commits,
not an assertion that the shared worktree stayed frozen. No provider call,
cluster operation, ACME edit, branch switch, or worktree mutation occurred.
The protected `e1_inspect_samples.jsonl` was read-only and remains untracked
and unchanged. The protected shared-schema report was not read or touched.

## Dependency and mechanism ledger

| Dependency or mechanism | Selected identity | Source grounded in | Current consequence |
|---|---|---|---|
| Inspect AI | Gitlink `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; installed version is revision-bearing | `reference-code/inspect-ai/src/inspect_ai/solver/_task_state.py`, `solver/_solver.py`, `_eval/task/run.py`, `log/_log.py`; `src-inspect-ai/evaluation-sources.lock.json` | `TaskState.metadata` is carried into native sample logs; Seon's solver can retain exact pod evidence without inventing another log format. Source admission also checks the deliberately selected nested viewer overlay. |
| Inspect Evals | Gitlink `97c99f5f6507fc5d1449fe3247f267d591f64350`, release `0.14.3` | `reference-code/inspect-evals/src/inspect_evals/bfcl/solve/single_turn_solver.py`, `bfcl/score/scorer.py` | Seon adapts generation while retaining BFCL's upstream AST scorer. |
| Python OpenAI client | exactly `2.45.0` | `src-inspect-ai/pyproject.toml`, `uv.lock`, `evaluation-sources.lock.json` | Standard Inspect provider runs have an admitted client identity; pod-backed runs use Seon's separately recorded Node/provider boundary. |
| Datahike | maintained fork `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/`; `seon.db`, turn coordinates, autocomplete exporter | Turn and export evidence binds to complete immutable database coordinates rather than a bare basis number. |
| ClojureScript | application `1.12.145`; reference `946d75f3483c0c8e784e6668bff2c71a25619a77` | `reference-code/clojurescript/`; `seon.repl.autocomplete` | The export is produced by the current runtime renderer at an as-of database value, not reconstructed by Python. |
| Malli | application `0.20.0`; reference `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc`; program graph and namespace renderer | Complete function contracts and referenced-schema closures remain executable data; agent-facing eligibility is one positive program fact. |
| Canonical tool surface | current `:seon.fn/agent-facing?` program facts | `src/seon/agent/ctx/namespaces.cljs`, `menu.cljs`, `src/my/ns.cljs`, `config/system.edn` | Compact cards, menus, and `my.ns/functions` share one eligibility source. There is no benchmark-only tool registry. |
| Inspect fixtures and scorers | current `src-inspect-ai` source | `tasks/frozen_tool_rows.py`, `tool_scorers.py`, `planning.py`, `tasks/long_term_planning.py` | Offline behavior is testable through native Inspect tasks and database/outcome scorers, but live lifecycle isolation still waits for the operator lease. |

The focused source-grounded checkpoint run during this audit was:

```text
.venv/bin/pytest -q tests/test_planning.py tests/test_autocomplete_manifest.py \
  tests/test_frozen_tool_rows.py tests/test_bfcl_adapter.py
83 passed in 5.48s

```

This proves those offline adapters and discriminators at current source. It is
not a substitute for a live model ladder, restart, or lease proof.

## Reconciliation by requested capability

### Live Inspect agent testing

**Implemented:** `seon_inspect.solver` drives the production `/agents/run`
door and retains final database coordinates plus ordered prompt/reply/eval
evidence in native `.eval` metadata. Commits `582d0c4d`, `ffdad065`,
`4ddbdea3`, `43b9c021`, `9428aebe`, and `de176aad` strengthen evidence
retention, run admission, operator-target identity, timeout identity, and
interruption finalization. `tasks/frozen_tool_rows.py` runs deterministic
shell, file, and web rows through native Inspect tasks; failures at the pod
boundary invalidate a sample rather than becoming model scores.

The most useful live artifacts are:

- the original ten-sample Qwen 3.5 2B BFCL baseline at
  `evals/runs/2026-07-14-bfcl-qwen35-2b-unchanged/`, which scored 0/10 but
  predated exact turn retention;
- the retained four-turn forensic rerun at
  `evals/runs/2026-07-15-inspect-turn-evidence-qwen-smoke/`;
- the corrected one-turn native completion at
  `evals/runs/2026-07-15-bfcl-native-complete-qwen-smoke/`, which kept the
  upstream scorer and scored 1.0; and
- the Qwen2.5 Coder 0.5B database diagnostic at
  `evals/runs/2026-07-15-p0-db-qwen25coder05b/`, which closed `:no-forms`
  with all four database-workflow checks absent.

**Missing:** static-URL mode serializes use of an explicitly owned target but
does not own it. There is still no token-fenced per-sample create/status/
restart/release lease, so concurrent isolated samples, identity-preserving
restart, and cleanup cannot graduate. The complete frozen ordinary-work
development/milestone/blind battery also has not produced one accepted serial
scorecard under coherent source, artifact, config, model, and native-log
identity.

### Large planner to small executor

**Implemented as contract and fixtures:** `planning.py` defines
`pretransacted`, `model_authored`, and `no_plan` arms and scores database
outcome, plan provenance/history, verified closes, report-before-close, and
address-step coverage. `planner_worker_fixtures.py` contains two concise
planner handoff stimuli. `tests/test_planning.py` falsifies caller-supplied arm
labels, missing history, fabricated outcomes, unverified closes, incomplete
observations, and reports delivered after close. Commit `8df08bd0` integrated
this evidence machinery.

**Historical live evidence only:** the 2026-07-12 plan-preload pilot showed
that a handed-down Markdown plan was encoded at turn 0, turn 17, or never
across three DeepSeek scenarios. Where a plan existed, expectations steered
verification; where it did not, the agent stored an incorrect value as
verified knowledge. Restart read-back through database facts worked. That
pilot also observed a root consultation/replanning path, but it did not bind a
separately identified larger planner and smaller executor into a native Inspect
comparison.

**Missing:** `planner_worker_fixtures.py` explicitly calls its future
`planner_worker` task not yet implemented. The current `model_authored` arm
means the executing model authored its own plan; it is not the requested
larger-planner/smaller-executor arm. No accepted `.eval` binds planner model,
worker model, exact plan handoff, `my.plan` transactions, restart, ordinary
read/process/write/report outcome, and scorer provenance in one run. The
pretransacted arm remains a diagnostic control, not a substitute.

### Long-term planning and restart continuity

**Implemented:** commit `1946850e` makes `long_term_planning` a first-class
Inspect task. Its offline good/bad solvers exercise the real answer,
trajectory, decompose-first, and close-adjacency scorer. `planning.py` also has
the injected phase-one, restart, phase-two, snapshot choreography. Historical
live pilots proved durable plan and database read-back across a pod restart.

**Missing:** the task's live endpoint still reaches the fail-closed cluster
boundary because it cannot safely own restart and cleanup. Therefore the
current task proves scorer discrimination and historical runtime capability,
not a reproducible accepted plan-survives-restart run on the refactored
operator.

### Autocomplete and typeahead

**Implemented:** commit `633987e5` strengthened the existing
`seon.repl.autocomplete/export!` path into
`seon.autocomplete.export/v1`. It retains complete coordinates, runtime/source/
config/profile identities, content-addressed observed rows, deterministic
splits, one deduplicated referenced-schema closure collection, and addressed
rejections. `seon_inspect.autocomplete_manifest` verifies every structural and
content digest and selects frozen rows without rebuilding Seon projections.

The historical typeahead replay remains useful calibration. On its ten-row
corpus, the local arm reported `.533` outcome versus DeepSeek `.70`, about 7x
lower median reply latency, and zero observed glyph uptake. That demonstrates
the step/lock/repair loop's value; it does not prove the selection glyph or
autocomplete head accuracy. Earlier first-form stopping improved balanced
shape while head correctness remained near zero, and the LoRA audit retained
149 hard failures among 557 pairs.

**Missing:** the canonical manifest supports only honest `observed`
projection semantics. Counterfactual and substantive-next-form targets,
staged-current-world replay, layered parse/schema/eval/database scoring, and
historical fair-scorer calibration are not implemented on this manifest. No
accepted training or serving claim follows from export correctness alone.

### Default `my.*` context and tool discoverability

**Implemented:** `config/system.edn` installs the same database-derived
namespace block for ordinary agents and requires `my.plan`, `my.kb`,
`my.data`, `my.ui`, `my.canvas`, and `my.blob` alongside the core database,
schema, message, lifecycle, search, filesystem, shell, and web namespaces.
Commit `bc2f587b` makes agent eligibility explicit and positive on program
facts; `cd73b0f3` makes `my.ns/functions` consume the same fact. Current source
marks the ordinary `my.*` functions directly, while implementation/private
functions remain indexed and available for deliberate inspection without
appearing as tools.

**Not yet the final `my.*`-first surface:** `my.ns` and `my.skills` are not in
the ordinary home-require list, and foundational actions still intentionally
live under `seon.db`, `seon.schema`, and `seon.agent.*`. The live namespace
audit measured 1,034 indexed functions, 114 eligible functions, and a 20,406-
token namespace block after curation. Referenced schemas remain the dominant
weight. The frozen battery must determine whether names, argument shapes, or
owner placement are unclear before moving functions merely to satisfy a
namespace aesthetic.

### Small-model usability target

**Specified, not achieved:** the agentic-tool-refinement PRD requires at least
90% deterministic success overall plus category floors on frozen ordinary
read/process/write/restart/report work. The experimental rule is sound: change
normal function names, schemas, arguments, and envelopes only when a clustered
failure demonstrates a general discoverability defect, then rerun the frozen
battery.

Current model evidence is diagnostic:

- Qwen 3.5 2B moved one identical BFCL sample from four formless turns and
  score 0 to one native `complete` form and score 1.0 after a contradictory
  adapter was fixed;
- the earlier unchanged Qwen 3.5 2B ten-sample BFCL slice scored 0/10 and
  lacked exact turn forensics;
- Qwen2.5 Coder 0.5B failed the single database workflow by copying prompt
  material rather than emitting forms; and
- the historical local typeahead arm remained below DeepSeek accuracy.

No sub-1B, 1.5B, 2B, 3B, or 4B model has passed the frozen representative
battery, and no lower-bound claim is justified. The useful current conclusion
is that a simpler model is a sensitive interface probe, not that the interface
is already simple-model complete.

## ACME branch and protected evidence disposition

The dedicated ACME worktree is clean at
`ecd8d889d0f1c218f33b8bd777104cab3d8693b9`. That tip is not an ancestor of
the current branch. `git cherry` reports six non-patch-identical commits and
two patch-equivalent commits. This is compatible with the prior semantic
integration audit, not evidence of a missing cherry-pick:

| ACME branch change | Current disposition |
|---|---|
| `c0d0eecf` establish refinement lane | Superseded by the expanded current agentic and Inspect PRDs. |
| `3bf5b953` root telemetry | Reimplemented as `de414b99`. |
| `b7ccbe0e` run/transcript policy | Reimplemented as `5cfc0127`, with warm-schema correction `131e438c`. |
| `aa6737cb` recovery load order | Reimplemented as `0ebe5f43`. |
| `2f348806` live config apply | Reimplemented as `b1337b41`. |
| `d84527d1` issue disposition | Reconciled into current issue/archive evidence. |
| `91d14430` shared-checkout policy | Patch-equivalent and now strengthened in root instructions. |
| `ecd8d889` Qwen baseline | Patch-equivalent; both the report and native `.eval` exist in the current tree. |

Thus the branch contains no unreviewed tracked source to merge wholesale. Its
ignored database bytes and eventual worktree/branch removal remain governed by
the unit-9 preservation and owner-authorization gate.

The protected `e1_inspect_samples.jsonl` contains 16 rows for one
`celsius->fahrenheit` task: eight `arm1_guided_refine` and eight
`arm3_naked_oracle` records. It is a narrow, untracked experimental sample,
not evidence for the planner/worker, ordinary-work, autocomplete, or 90%
graduation claims. This audit did not stage, normalize, move, or edit it.

## Exact remaining order

1. Finish unit 6's runtime correctness and unit 1's ownership contract, then
   publish the token-fenced operator lease consumed by Inspect.
2. Freeze one representative development/milestone/blind membership and its
   category floors; run one admitted serial ordinary-work sample through
   native finalization before widening the matrix.
3. Implement current-world replay and layered scoring over the canonical
   autocomplete manifest; retain observed, counterfactual, and substantive
   target modes as explicitly distinct artifacts.
4. Add the actual planner/worker Inspect arm: record the larger planner's
   identity and output, have the smaller worker encode it through `my.plan`,
   execute and restart on the same lease, then score database outcome and
   plan evidence.
5. Run small-alone, large-alone, large-plan/small-execute, and pretransacted
   diagnostic arms on identical frozen samples. Accept a tool-surface change
   only after the whole development set improves without a category
   regression.
6. Open the blind set once, preserve native logs and scorecards, and graduate
   only at the declared overall and category floors. Worktree cleanup remains
   unit 9 and owner-authorized.
