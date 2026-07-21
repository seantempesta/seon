---
type: research
status: active
tags: [research, agent]
---

# Balanced-agent benchmark battery — design + scaffold (task #90 follow-on)

## TL;DR

The proven inspect↔Seon bridge (`memory_qa_bench.py` → `seon_pod_solver` →
host-side scorer → `pass^k`, chunked under the ~1hr bg-task cap) so far measures
ONE axis: store→retrieve memory (now `pass^4 = 1.000` after the output-discipline
lift). The owner wants a **BALANCED** agent, so this doc designs and scaffolds a
**four-axis battery** — one small held-out benchmark per core capability — all
mirroring the memory harness exactly (dataset + host-side scorer + `/solve` solver
+ `pass^k` + chunkable). **File-only: nothing here has been run live** (a benchmark
on broken rendering is invalid; runs wait for the Core render-fix + a free pod).

The four axes:

| # | Axis | Capability tested | Samples | Scorer | Status |
|---|------|-------------------|---------|--------|--------|
| 1 | **Memory** | store → discriminating-retrieve across turns | 16 | `includes` (host-side substring) | DONE (`memory_qa_bench.py`) |
| 2 | **Planning + resume** | decompose a multi-step task into durable todos, complete all, survive a mid-task interruption | 10 | custom `plan_completion` (reply-substring AND all-todos-closed) | SCAFFOLDED |
| 3 | **Coding / eval** | write a spec'd fn + `:malli/schema` + make it pass a probe | 12 | custom `code_probe` (reply carries the probe output the fn must produce) | SCAFFOLDED |
| 4 | **Tool-use / data** | use the DB/todo/kb verbs to accomplish a concrete data task, read the answer back | 12 | `token_includes` (host-side word-boundary match on the computed value) | SCAFFOLDED |

All four are **held-out judges**: general task shapes, host-side answer keys that
NEVER enter the pod, and **zero** answer-shaped or benchmark-aware guidance
anywhere (see § Anti-cheat). They test capability; they do not leak solutions.

## Why these four = "balanced" (per CLAUDE.md "Exercising agents")

CLAUDE.md names two canonical shapes — **long-term planning** (survives
interruption/restart) and **db-backed memory** (store then retrieve). The memory
axis covers the second. This battery adds the first (planning + resume) and two
more competencies a *balanced* coding-agent runtime must have — it writes code
and it drives its own data verbs:

- **Memory (axis 1)** — the runtime's whole thesis is "history agents can learn
  from"; if recall doesn't survive a turn, nothing else matters. Already proven.
- **Planning + resume (axis 2)** — the OTHER canonical CLAUDE.md shape. A balanced
  agent breaks a multi-step task into durable plan items up front and closes them
  as each lands, so an interruption lets it RESUME from what's open rather than
  re-plan. Win = continuity across turns/restarts, not one-shot completion. This
  is the axis that most directly stresses the FSM + durable todo store.
- **Coding / eval (axis 3)** — Seon IS "infrastructure for AI agents to write
  reliable software." An agent that can't author a small `:malli/schema`'d fn that
  actually runs is not balanced no matter how good its memory is. This axis
  exercises the eval path + instrumentation + the schema-first discipline end to
  end (the agent's code must survive its OWN instrumentation to score).
- **Tool-use / data (axis 4)** — the agent's power is the verb surface
  (`db/query`, `db/transact!`, `todo`, `message`, `my.kb`). This axis is a
  concrete data task (transact structured rows, then query/aggregate them back)
  where the SHAPE of the stored data — not a remembered fact — is what's scored.
  It catches "knows the fact but can't drive the tools to compute over it," a
  failure mode distinct from memory.

Optional 5th axis (multi-hop reasoning / retrieval) is sketched in § Future — it
reuses axis-1's dataset generator with a 3-fact chain, so it's cheap to add once
the four core axes have a live baseline. Kept out of the first battery to hold the
"small per axis" budget.

## Shared harness contract (identical to memory_qa_bench)

Every axis module is the SAME four pieces, so a new axis is a dataset + a scorer,
never new bridge code:

1. **Dataset** — a `.jsonl` loaded by `json_dataset` with fields `id` / `input` /
   `target` (+ optional `metadata`). The `input` is the full self-contained task
   prompt (the agent reads its own toolkit from context — we never name verbs it
   must call in an answer-shaped way; we describe the TASK). The `target` is the
   host-side answer key.
2. **Solver** — `seon_pod_solver` (imported from `seon_solver.py`, UNCHANGED).
   POSTs `input` to `/solve`; the pod's own FSM runs every turn; the reply +
   metadata (`pod_turns`, `pod_evals`, `pod_closed_reason`, `pod_timed_out`) come
   back and are recorded to `state.metadata`. Inspect never manages a turn.
3. **Scorer** — host-side. Two flavours:
   - **`includes()` / `token_includes()`** (axes 1, 4) — the held-out `target`
     in the reply. Axis 1 uses inspect's `includes()` (its targets are unique
     surnames / distinctive numbers); axis 4 uses a `token_includes()` that
     word-boundary-delimits the match so a small-integer computed target (e.g.
     `2`) can't false-match inside a larger number (`22`). Same unambiguous
     guarantee, made explicit for short numeric answers.
   - **custom `@scorer`** (axes 2, 3) — reads the reply AND the `/solve` metadata
     to judge a mechanism (all todos closed; the code produced the probe output).
     Same pattern as `timeout_honesty()` already in `seon_solver.py`.
4. **`pass^k`** — `Epochs(K, pass_at(K))`, K=4 default (`SEON_PASS_K`), so
   weak-model variance is averaged (single-sample drives are NOISE — a flipped run
   is model variance, not signal). Each `@task` has a `_smoke` variant (2-3
   samples, 1 epoch) to prove wiring before the paid pass^k run.

**Run shape** (from the B2 lesson — the environment kills bg tasks at ~1hr):
chunk `4 samples × K4` per `inspect eval` invocation, `--max-samples 1` (serial —
`/solve` swaps the root `*conn*` per sample, so it is NOT concurrency-safe), each
chunk writes its own `.eval`, merge by sample id. Mean drive ≈ 2 min → a 12-sample
K4 axis ≈ 96 min total → 3 chunks. `--model mockllm/model` (never called; the
solver sets the completion directly).

## Axis 2 — planning + resume (`planning_resume_bench.py`)

**Task shape.** A self-contained multi-step task with N (3-4) discrete steps that
must each be recorded as a durable todo and completed, with a **synthesis step**
at the end that requires having done the earlier ones. The prompt describes the
work and asks the agent to (a) break it into a plan up front, (b) complete each
step, (c) report a final synthesis token that only exists if all steps ran.

Example (dataset row, paraphrased): *"Plan and carry out a 3-step task: (1) record
three project facts in your knowledge base, (2) create a todo for each of the
three deliverables and complete them as you go, (3) once all three are done,
report the SUM of the three numeric facts as your final answer. Plan first, then
execute, then report."* Target = the sum (host-side).

**Resume variant.** A subset of samples carries `metadata.interrupt: true`. The
resume proof CANNOT be forced from inspect (inspect never manages turns — the pod
owns the loop). Two honest options, both scaffolded, pick one at run time:

- **(a) Short-budget probe** (default, no pod changes) — set a tight
  `timeout_ms` (e.g. 45s) on the first `/solve`, then issue a SECOND `/solve` to
  the SAME agent id with a "continue from your open todos" nudge and a generous
  budget. Scorer passes iff the final reply has the synthesis token AND the
  metadata shows the second call closed `:completed` with the todos that were open
  after call 1 now closed. This tests genuine resume-from-open-items. Requires a
  tiny `/solve` extension (accept an optional `agent_id` to reuse an agent instead
  of always minting) — **NOTED as a Core-gated prerequisite, not built here.**
- **(b) Single-call durable-plan check** (works today, no pod change) — one
  `/solve`; scorer passes iff the reply has the synthesis token AND
  `pod_closed_reason == :completed` AND (via a read-back door, see § Open) all
  plan todos are `✓`. This proves durable decomposition + completion but not the
  cross-call resume.

Both are scaffolded; the module defaults to (b) (runs today) and gates (a) behind
`SEON_SOLVE_RESUME=1` so the resume-from-interrupt run lands the moment the
`/solve` `agent_id` reuse extension exists.

**Scorer** — `plan_completion` (custom): reply-substring of the synthesis target
AND `pod_closed_reason == "completed"` (not turn-limit/timeout). Optionally, when
a read-back door is wired, all-todos-closed. Rationale: a balanced agent that
rambles past the synthesis or never closes its plan fails, exactly as the B2
output-discipline lesson demands.

## Axis 3 — coding / eval (`coding_eval_bench.py`)

**Task shape.** Give a crisp spec for a small pure function: name, argument
shape, and 2-3 input→output examples, and ask the agent to (a) define the fn in
one of its own `my.*` namespaces WITH a `:malli/schema`, (b) call it on a
HOLD-OUT probe input given in the prompt, and (c) report the probe result as its
final answer. The `:malli/schema` requirement means the fn must survive the pod's
always-on instrumentation to produce any output — so a wrong schema fails the
axis for free (the eval throws, no probe output, miss).

Example (dataset row, paraphrased): *"Define a function that returns the number of
vowels in a lowercase string, with a correct :malli/schema. Examples: 'hello'→2,
'sky'→0, 'aeiou'→5. Then call it on the probe word 'benchmark' and report the
result as your final answer."* Target = `3` (host-side; the probe word is chosen so
the answer isn't in the examples).

**Why the probe is hold-out.** The examples teach the spec; the probe input is a
DIFFERENT input whose answer is not derivable by copying an example. The agent
must actually run its own code on the probe — copying an example number won't
match. This is the anti-echo guard, same spirit as the memory distractors.

**Scorer** — `code_probe` (custom): reply-substring of the host-side probe target
AND `pod_evals >= 1` (the agent actually ran an eval — a reply with the right
number but zero evals is suspicious and flagged, though substring is the primary
signal). Substring is safe because the probe targets are chosen to be unambiguous
(a specific integer / short string not otherwise present). Tasks span: string
predicate, small arithmetic/reduce, list transform, simple parse — each a pure fn
a weak model can plausibly write, none requiring library knowledge beyond core
Clojure. Kept to 12 samples across ~4 task families × 3 variants.

## Axis 4 — tool-use / data (`tool_use_data_bench.py`)

**Task shape.** A concrete data task that is about DRIVING THE VERBS over
structured data, not recalling a fact: transact a small set of structured rows
(the prompt gives the rows), then query/aggregate them and report the computed
result. The scored thing is the RESULT OF A QUERY the agent had to run — a fact it
was told, transformed by a computation it had to drive.

Example (dataset row, paraphrased): *"You are given five expense records, each with
a category and an amount: {groceries 40} {transport 15} {groceries 25} {utilities
60} {transport 10}. Store each as a structured entry in your knowledge base, then
query them back and report the TOTAL amount for the 'groceries' category as your
final answer."* Target = `65` (host-side; the sum is not stated, only derivable by
storing + querying + summing).

**Why this is distinct from memory.** Memory (axis 1) scores a stored fact read
back verbatim. Here the answer EXISTS NOWHERE in the prompt — it is the output of
a group-by/sum the agent had to express against its own stored datoms. It fails if
the agent can recall the rows but can't drive `db/query` to aggregate them. Task
families: category-sum (group-by), max/min-of-field, count-matching-predicate,
join-two-record-sets. 12 samples.

**Scorer** — `token_includes()` host-side on the computed target (a unique
integer / short token), word-boundary-delimited so a small-integer answer can't
false-match inside a larger number. Same unambiguous guarantee as axis 1.

## Anti-cheat (standing rule — held-out judges)

Enforced identically across all four axes:

- **General task shapes only.** Prompts describe a TASK (store these facts / plan
  these steps / define this fn / aggregate these rows). No prompt names the verb
  sequence to "get the answer," and no prompt is aware it is a benchmark beyond
  the neutral "you are being tested on <capability>" framing the memory axis
  already uses.
- **Host-side scoring; the answer key never enters the pod.** `target` lives in
  the JSONL and is compared in inspect's process. `/solve` receives only `input`.
  Verified structurally: the solver POSTs `{"input": ...}` — `target` is not in
  the body.
- **No answer-shaped guidance anywhere.** No change to `src/seon/**`,
  `config/**`, `seon-skills/**`, or the agent's context may reference these tasks,
  their answers, or their shapes. The output-discipline lift ("finishing is an
  act") is the LAST allowed class of change — a GENERAL standing teaching, dataset
  unseen. Any per-axis tuning must be that general, or it is overfitting and gets
  reverted (accretive-or-revert, no overfit — the gym law).
- **Hold-out probes.** Axes 3 and 4 score a probe/computation whose answer is NOT
  in the prompt's examples, so copying an example can't pass. Axis 1's distractors
  and axis 2's synthesis token serve the same role.
- **Datasets test capability, not solutions.** The generators (see § Generators)
  parameterize surface details (names, numbers) so the battery can be regenerated
  held-out if a set ever leaks into training/guidance.

## Generators (regeneratable, held-out)

Each axis ships a tiny generator (`gen_<axis>_dataset.py`) that emits the `.jsonl`
from a table of (surface-name, numbers) tuples, so a fresh held-out set is one
command away if a set is ever suspected of leaking. The committed `.jsonl` is the
frozen set used for the trend; the generator is the escape hatch. (Axis 1 already
has a frozen set; its generator is documented here for parity but the existing
`memory_qa_dataset.jsonl` stays the trend anchor.)

## What is NOT built here (Core-gated / owner-gated)

- **No live runs.** Every module is import-clean and `_smoke`-ready but UNRUN.
  Runs wait for the render-fix to land + the pod to free (a benchmark on broken
  rendering is invalid).
- **`/solve` `agent_id` reuse** (axis-2 resume variant (a)) — a small boundary
  extension to `/solve` to reuse an existing scratch agent across two calls.
  NOTED, not built (would touch `src/seon/web/serve.cljs`, out of this lane).
- **A read-back door** for "all todos closed" scoring (axis 2 option (b) full
  form) — either a `/solve` metadata addition (`open_todos` count) or a separate
  read endpoint. The scaffold scores on reply + `closed_reason` today and upgrades
  to todo-state when the door exists. NOTED.
- **The 5th axis (multi-hop reasoning).** Sketched in § Future; a 3-fact-chain
  reuse of the memory generator. Deferred to keep the first battery small.

## Future — 5th axis (multi-hop reasoning / retrieval)

Reuse axis-1's store→retrieve but with a 3-fact CHAIN (A relates to B, B relates
to C; ask a question that requires composing A→B→C from stored facts). Tests
multi-hop retrieval + reasoning, not just single-fact recall. Cheap to add: same
`includes` scorer, same solver, a chain-generator over the axis-1 table. Left out
of the first battery to hold the per-axis budget; add once the four core axes have
a live baseline to compare against.

## Files (this deliverable)

- `planning_resume_bench.py` + `planning_resume_dataset.jsonl` + `gen_planning_resume_dataset.py`
- `coding_eval_bench.py` + `coding_eval_dataset.jsonl` + `gen_coding_eval_dataset.py`
- `tool_use_data_bench.py` + `tool_use_data_dataset.jsonl` + `gen_tool_use_data_dataset.py`
- `battery.py` — a thin index task that names all four axes + the run recipe (docs,
  not a combined run — chunking is per-axis).

All under `docs/prds/agent-fsm/research/inspect-bridge-spike/`, reusing
`seon_solver.py` unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
</content>
</invoke>
