---
type: reference
status: active
tags: [reference, agent]
---

# Phase 3 — `diffusion/build!` EXECUTION SPEC (Opus-executable)

**For the executing agent: decisions are pre-made; reality contradicting a
step = STOP + report, never improvise.** Context: the approved
verified code-buffer v2 plan (Phases 0-2 SHIPPED: `src-diffusion/` guided loop
+ worker `mode:"guided"` + provider `:guided` + worker-eval
`op:"repair"`/`op:"run-tests"` + pod pre-flight autofix). Read
`docs/prds/diffusion-dynamic-context/CLAUDE.md` "Current state" +
[[research/../../agent-ctx/research/form-autofix-system-2026-07-05]] first.

**PRECONDITION: the namespace-reorg runbook has EXECUTED and landed.**
Post-reorg names used throughout: `seon.eval.repair`,
`seon.eval.repair.candidates`, `seon.eval.test-runner`, `seon.worker.eval`.
If the reorg has NOT landed, STOP — do not build against the old names.

## What Phase 3 is

The pod-side verb that turns "PRD paragraph in" into "proven
`:seon.schema`/`:seon.test`/`:seon.fn` rows in the DB": it drives the
local worker's guided mode phase-by-phase (schemas → tests → functions),
then REPLAYS the committed text through the NORMAL agent-eval tee — the
pod is the authority; the worker session was advisory.

## The verb — `seon.diffusion.build/build!` (new ns, `.cljs`)

Map-in/map-out, `::` schemas registered in the ns, errors-as-values,
`:malli/schema` on every public fn, docstring conventions apply.

Request (all keys `::` in `seon.diffusion.build`):
- `::intent` (string, required) — the short PRD paragraph.
- `::ns` (keyword-as-symbol string? NO — a plain string ns name, required)
  — the TARGET namespace for the built forms; rides the worker `prelude`
  as `(ns <name> (:require [seon.schema :as schema] [cljs.test]))` — wait:
  the worker session evals in `cljs.user`; the prelude is
  `(require '[seon.schema :as schema])` + `(require '[cljs.test])` ONLY.
  The target-ns placement happens at POD REPLAY (the tee records
  `:seon.fn/ns` from the eval context) — the verb evals replayed forms
  with the agent's current-ns machinery exactly as agent-typed forms
  would be. Do NOT invent a second ns-routing mechanism.
- `::phases` (optional, default `["schemas" "tests" "functions"]`) —
  strings, each must be a `seon.diffusion.grammar/phase-grammars` key.
- `::plan-text` (optional string) — strong-model PRD/plan; passes through
  to every phase's worker payload as `plan_text`... the worker payload key
  does NOT exist yet: v1 = PREPEND it to the phase prompt text (no worker
  change). Do not add a worker key in this unit.
- `::checks` (optional, `[{:seon.diffusion.build/call string
  :seon.diffusion.build/expect string}]`) — caller acceptance checks,
  forwarded to the `functions` phase payload (`checks`, string keys).
- `::seed`, `::entropy-bound` (optional passthroughs; defaults worker-side).

Response:
- `::ok?` boolean; `::committed` (the final combined source string);
  `::teed` (vector of FQ sym strings that landed as rows);
  `::tests` ({::pass ::fail ::error} from the replay-side run);
  `::phase-results` (per-phase worker metadata: done/rounds/repairs/
  tok-per-s — tokens, per the standing rule);
  on failure `:seon/error` (standard envelope) + `::diverged`
  (vector of {::form ::seon.error/message}) + `::teed` (the prefix that DID
  land — never a silent partial: the error NAMES everything not teed).

## Orchestration (inside `build!`)

1. Per phase, in order: call the provider path — do NOT hand-roll HTTP.
   Use `seon.ai.diffusiongemma`'s guided request machinery directly
   (`complete`-level fn with an explicit payload map, NOT the agent-loop
   `current-llm-fn`): mode `guided`, `phase`, `prelude` (fixed:
   `"(require '[seon.schema :as schema])(require '[cljs.test])"`),
   `hints` true, `repair` true, phase prompt (below), plus `checks` on the
   functions phase. Endpoint = the configured `SEON_DG_ENDPOINT`
   (`http://127.0.0.1:17860` local worker; `bin/seon start dg-worker`).
2. Phase prompts (template fns in the new ns, forms-only contract):
   - schemas: intent + "PHASE 1 — declare ONLY (schema/register! …) forms…"
   - tests: intent + the phase-1 committed text + "PHASE 2 — write ONLY
     (cljs.test/deftest …) forms testing the functions you will define…"
   - functions: intent + phases-1+2 committed text + "PHASE 3 — define the
     functions; the tests above must pass."
   Mirror `src-diffusion/src/seon_diffusion/domain_demo.py`'s proven
   prompt shapes — do not re-invent wording style.
3. `:tests` phase worker payload gets `repair` false (tests reference
   not-yet-defined fns; repair would "fix" them to wrong existing syms) —
   the phase grammar + parse gate carry it. `eval` gating for the tests
   phase happens worker-side already (grammar-locked; the worker loop
   evals deftest forms fine — cljs.test is in the prelude).
4. A phase with `done:false` → STOP the build, return the error envelope
   with everything collected so far (honest partial).
5. REPLAY-COMMIT: parse the combined committed text pod-side
   (`seon.repl.internal/parse-forms`); eval each form IN ORDER through the
   same entry the agent loop uses (find it in `seon.eval` — the path
   `eval-form-entry!`/`eval-batch!` that records + tees; call it the way
   `seon.agent.loop` does, tee ENABLED). First failure → stop, build the
   `::diverged` error (forms after the failure are NOT attempted, named in
   the error). The pod pre-flight autofix (`:symbols`) applies to replayed
   forms exactly as to agent-typed ones — that is intended, not a bug;
   any `↻ fixed` on replay gets surfaced in `::phase-results` as
   `::replay-fixes`.
6. After a clean replay: run the built tests via `seon.eval.test-runner`
   (`run-vars` on the deftest vars just teed — the `:seon.test` rows name
   them) → `::tests`. Failing tests = `::ok?` false with the failure list
   (rows STAY teed — they are real, honest state; the publish gate already
   treats failing tests as unpublished).
7. NO retry logic in the verb (turn-level retry owns transport); NO
   `core.async` (pod is `^:async`/await); every worker await via the
   existing provider promise path.

## Agent exposure

Manifest/loadout: add the ns to the agent-visible surface the same way
`my.*` toolkit nss are exposed in `config/system.edn` (find the
`:namespaces`/loadout section pattern — follow it exactly; acme gets it
via `config/acme.edn` if separate). The verb IS agent-callable:
`(seon.diffusion.build/build! {:seon.diffusion.build/intent "…"})`.

## Tests (hermetic) + live proof

- Unit tests (`test/seon/diffusion/build_test.cljs`): stub the worker call
  (inject the guided-response fn — a request-shape seam, not js/fetch
  monkeypatching): phase sequencing, done:false stop, divergence naming
  (feed a committed text whose 2nd form breaks), tests-fail path,
  response schema round-trip. In-memory conn per clojure-testing skill.
- LIVE PROOF (acme, ours): `bin/seon start dg-worker`; drive ONE real
  build from an acme agent turn (uncoached DeepSeek: tell the agent to
  build the expense domain via the verb) — verify by QUERY: 3
  `:seon.schema` rows, ≥1 `:seon.test` row passing, 2 `:seon.fn` rows,
  `(the-fn …)` returns the right answer evaluated live, and READ the
  rendered transcript (flag garbage). Paste real outputs.
- `cd src-diffusion && .venv/bin/pytest -q` (wire contract untouched —
  should stay green with zero edits there; if a Python edit feels needed,
  STOP: this unit is pod-side only).
- Full `bin/test-cljs` ONCE at the end; honest counts.
- Commit: NOTHING — report back; the orchestrator commits.

## Pre-made decisions (do not reopen)

- Worker stays advisory; pod replay is the ONLY tee path (no parallel
  writer, no worker-side persistence).
- Prefix-tee on divergence (in-order), remainder named in the error.
- `:tests` phase: worker `repair` OFF; replay-side autofix stays ON.
- No `mode/enter` sentinel, no multi-pass convergence, no op-axis (still
  CUT until measured); ONE build = one pass through the phase list.
- `my.plan`-phase and strong-model plan CALLS are Phase 4 — `::plan-text`
  is a pass-through string here, nothing more.
- tok/s + token sizes in every report surface (standing rule).
