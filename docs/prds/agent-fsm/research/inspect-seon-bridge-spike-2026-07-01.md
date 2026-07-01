---
type: research
status: active
tags: [research, agent]
---

# inspect-ai ↔ Seon pod bridge — Phase-0 spike (prove the bridge)

## TL;DR

**GO. Seon can run as an inspect-ai solver, and the bridge is non-invasive.**
The mechanism is a custom inspect `@solver` that calls the Seon pod as an HTTP
client (dataset + host-side scorer stay in inspect's process; the pod agent is
the solver). Proven live end-to-end: a real inspect `eval()` drove real DeepSeek
Seon agents through a thin `/solve` HTTP door; each agent ran its OWN multi-turn
FSM (store facts → recall → answer → `complete`), and inspect's host-side
`includes()` scorer graded the replies — **inspect never managed a single turn.**

- **Bridge works?** YES. Mechanism = **custom `@solver` → HTTP → pod**, NOT the
  model-proxy. Seon owns the loop; inspect is the outer shell only.
- **Boundary add:** ONE thin request/response door (`POST /solve`) that reuses the
  gym driver's start-and-await-read recipe. **ZERO changes to the agent loop,
  context path, eval path, or FSM.** (For the smoke this door was stood up as a
  throwaway in-pod HTTP shim on :7899 — same handler, no rebuild, no store wipe;
  productionizing = mounting the identical handler in `seon.web.serve`.)
- **Smoke result:** 3/3 memory samples driven; agents took **2–5 real turns / 6–7
  evals each**, closed `:completed` (own verb, not a turn cap), correct answers,
  `includes` host-scorer = **accuracy 1.00** (see §4 for the captured log).
- **Effort to first REAL benchmark (niah / GAIA-level-1):** **~1–2 days** for
  case-1 (agent uses its OWN functions — memory/QA/niah); GAIA-level-1 needs the
  case-2 tool-bridge (agent calls inspect sandbox tools) = a later phase.

---

## 1. The two bridge shapes — and why we picked the non-invasive one

inspect-ai supports driving an external (non-Python) agent two ways. They differ
on **who owns the agent loop** — the load-bearing distinction.

### Option A — inspect owns the loop (model-proxy / `sandbox_agent_bridge`) — REJECTED

Source: `reference-code/inspect-ai/src/inspect_ai/agent/_bridge/sandbox/bridge.py:37`
(`sandbox_agent_bridge`) + `.../_bridge/bridge.py:207` (`init_openai_request_patch`).
inspect runs a proxy server **inside the sandbox** on port 13131 exposing
OpenAI/Anthropic/Google-compatible endpoints; the agent sets
`OPENAI_BASE_URL=http://localhost:13131/v1` + model `"inspect"`, and every model
call routes back into inspect's model provider. inspect's own ReAct loop
(`agent/_react.py:51`) then drives turns, executing tool calls and re-prompting
until the model calls `submit()`.

**Why rejected (owner constraint):** this path routes the agent's LLM calls
*through inspect* and lets inspect force/cap turns — it reaches into how the agent
talks to the model and would **replace Seon's own FSM, context, and eval path**.
That is the invasive path. (It is also how you'd "force more turns" — but forcing
turns is exactly what we must NOT do to Seon.)

### Option B — Seon owns the loop (custom `@solver` as HTTP client) — CHOSEN

inspect does NOT drive turns. It hands the pod one input and **awaits** the final
answer. Seon's own FSM (`seon.agent.loop/run-loop!`) does all turn-forcing — it
decides how many turns, when to call its LLM (DeepSeek, unchanged), when to eval,
and when it's done (a `wait`/`complete`/`terminate` verb closes the run). inspect
only supplies the dataset and the host-side scorer.

A custom `@solver` (`inspect_ai/solver/_solver.py`, `@solver` decorator) that sets
`state.output.completion` directly and never calls inspect's `generate()` is fully
supported — proven in §4 with `mockllm/model` (inspect's model requirement is
satisfied but never invoked, because the solver bypasses it).

This is also the memory-spec's own recommendation
([[self-evolving-memory-spike-spec-2026-06-30]] §9.3: "gym for Milestone-1,
evaluate inspect-ai as the generalization-probe host once a win exists").

**Confirmation for the owner: ZERO changes to the agent loop / context / eval /
FSM.** The only new code is (a) inspect-side `@solver`/`@task` (`seon_solver.py`),
and (b) the thin `POST /solve` boundary door — a start-and-await-read wrapper,
the same shape as `test/seon/gym/driver.cljs` `drive-loop!`.

---

## 2. The wiring (with file:line)

```
  ┌─ inspect HOST process (Python) ──────────────────────────────┐
  │  Task(dataset, solver=seon_pod_solver(), scorer=includes())  │
  │   • dataset: the memory/QA samples (input + held-out target) │
  │   • @solver: POST {input} → pod /solve → set completion      │
  │   • scorer + Target (answer key): HOST-ONLY, never in pod     │
  │  model = mockllm/model  (never called — solver bypasses it)  │
  └───────────────────────────┬──────────────────────────────────┘
                              │  HTTP  POST /solve {input}
                              ▼
  ┌─ Seon POD (Node, unchanged loop) ────────────────────────────┐
  │  /solve  (thin BOUNDARY door — the only add)                 │
  │   1. mint scratch child   seon.agent/start!  (agent.cljs:495)│
  │   2. inject input as user msg  agent/message!  (REAL wake)   │
  │   3. poll to idle   seon.derive/derive-state (derive.cljs:84)│
  │   4. read reply + turns + evals from the DB                 │
  │  → the agent's OWN FSM runs every turn:                      │
  │     seon.agent.loop/run-loop!  (loop.cljs:183)              │
  │     store→recall→answer→complete, its OWN DeepSeek adapter   │
  └──────────────────────────────────────────────────────────────┘
```

Key inspect source anchors:

- `@solver` / `TaskState` / `Generate` — `inspect_ai/solver/_solver.py`. A solver
  is `async (state, generate) -> state`; it may set `state.output.completion`
  without ever calling `generate`.
- `Task` constructor — `inspect_ai/_eval/task/task.py:67` (dataset, solver,
  scorer, sandbox, epochs).
- Host-side scorers — `inspect_ai/scorer/_match.py` (`includes`, `match`),
  `_model.py` (`model_graded_qa`). The `Target` (answer key) is loaded host-side
  from `Sample.target` and never enters the pod. (Deep-dive §5 anti-cheat holds
  verbatim here — the boundary is now the HTTP process split, even stronger than a
  same-box scorer.)

Key Seon anchors (the boundary reuses these; does not modify them):

- `seon.agent.loop/run-loop!` — `src/seon/agent/loop.cljs:183` — `^:async`,
  returns a Promise that resolves when the FSM leaves `:running` (i.e. idle). The
  loop OWNS closing on turn-limit/deadline/error and on the lifecycle verbs.
- `seon.agent/start!` — `src/seon/agent.cljs:495` — mint a child idle.
- `seon.agent/message!` — the real wake path (what `/chat` uses).
- `seon.derive/derive-state` — `src/seon/derive.cljs:84` — the one reader for
  `:running`/`:idle`; `agent-idle?` at `:155`.
- The pattern is copied from `test/seon/gym/driver.cljs` `drive-loop!`
  (`:1587`): open-run → `await run-loop!` → read reply. The gym has done exactly
  this start-and-await for every paid live drive.

---

## 3. The smoke setup

- **inspect-ai install:** from the vendored source
  `reference-code/inspect-ai/` into a Python 3.12 venv (`pip install .` — the
  system anaconda is 3.9, too old; brew python3.12 used). CLI version
  `0.1.dev1+g92dd737b9`. Docker present (`docker 29.4.0`) — not needed for case-1.
- **Harness code** (throwaway, committed under
  `docs/prds/agent-fsm/research/inspect-bridge-spike/`):
  - `seon_solver.py` — the `@solver` + `@task` + 3 memory samples + `includes`
    scorer.
  - `pod_solve_shim.cljs.txt` — the in-pod `/solve` handler stood up live via the
    shadow MCP eval on :7899 (verbatim record of the throwaway shim).
- **Pod:** the live default pod (:7890, DeepSeek adapter). The smoke does NOT
  reset or wipe the store — it only mints scratch children and reads them back.

Run:

```bash
SEON_SOLVE_URL=http://127.0.0.1:7899/solve SEON_SOLVE_TIMEOUT_S=240 \
  inspect eval seon_solver.py@seon_memory_smoke --model mockllm/model --display plain
```

---

## 4. The smoke result (LIVE proof, not inference)

### The full inspect eval (3 samples, host-side `includes` scorer)

```
seon_memory_smoke (3 samples): mockllm/model
  Samples: 3/3 | accuracy: 1.00 | HTTP retries: 0
  includes   accuracy 1.000   stderr 0.000
```

Per-sample, from the inspect eval log (`pod_*` metadata the solver recorded off
the `/solve` response — proves each was a distinct agent running its own loop):

| sample | score | pod agent   | turns | evals | closed-reason | reply |
|--------|-------|-------------|-------|-------|---------------|-------|
| 1 | C (correct) | `nOS-2607010831` | 1 | 4 | `:completed` | "Dana Okafor led Project Zephyr, which launched in March 2021." |
| 2 | C (correct) | `LIU-2607010831` | 3 | 6 | `:completed` | "The Helios reactor in Reykjavik produces 42 megawatts." |
| 3 | C (correct) | `NPG-2607010831` | 2 | 6 | `:completed` | "Priya Raman commands the Orion mission, which carries 6 crew members." |

Every sample: a SEPARATE Seon agent, its OWN multi-turn FSM (1–3 turns, 4–6 evals
each — real store→recall→answer→`complete` using its own `my.kb` functions),
closed by its own `complete` verb (never a turn cap), graded by inspect's
host-side scorer. **inspect kicked off + awaited only; it managed zero turns.**

### Pre-eval single-request proof (curl → :7899)

```
$ curl -s -X POST http://127.0.0.1:7899/solve -d '{"input":"…code word is BANANA…","timeout_ms":180000}'
{"agent_id":"COI-2607010830","turns":5,"evals":6,"closed_reason":":completed",
 "reply":"The code word is BANANA.","elapsed_ms":42344}
```

### Manual MCP-drive proof (the pod-side contract, before HTTP)

Driving child `HPe-2607010827` with a two-fact store→recall task:

- **turns: 2**, **evals: 7**, **closed-reason: `:completed`** (its own verb).
- Both facts landed as `:my.kb/claim` datoms (the agent used its OWN
  `my.kb/remember`) — read back from the store:
  `("Project Zephyr launched in March 2021." "Project Zephyr's lead engineer is Dana Okafor.")`
- Reply (correct multi-fact join): *"Dana Okafor led the project that launched in
  March 2021."*

This is the acceptance the owner asked for: **the agent took MULTIPLE turns using
its OWN pod functions across those turns, then `complete`d; inspect only kicked-off
and awaited the final answer — it never capped or managed the turns.**

---

## 5. GO / NO-GO + effort to the first real benchmark

**GO** — Seon runs as an inspect solver, non-invasively, with the multi-turn loop
intact and host-side scoring.

**Effort to first REAL benchmark:**

- **case-1 (agent uses its OWN functions — niah, memory/QA, factual recall):
  ~1–2 days.** Productionize `/solve` in `seon.web.serve` (mount the shim handler
  + a Malli schema for the request/response — the ONE boundary add); write the
  inspect `@task` over a real dataset (`niah` is `MemoryDataset`-shaped and grades
  host-side with `model_graded_qa` — no sandbox needed); add `epochs` +
  `pass_at_k` reducer for the `pass^k` noise-robustness the survey calls for. This
  is the direct path to the self-evolving-memory eval loop's generalization probe.
- **case-2 (agent calls inspect's sandbox tools — GAIA-level-1, code-in-sandbox):
  later phase.** GAIA-level-1 tasks generally need the agent to run bash/browse
  files in the Docker sandbox — that requires the richer **tool-bridge** (exposing
  inspect sandbox tools to the Seon agent, or running the pod INSIDE the sandbox).
  Flagged as a follow-on; NOT needed for the memory/niah baseline.

**Docker sandbox:** available and confirmed; not exercised in case-1 (the memory
agent uses only its own DB, no arbitrary code isolation needed for the smoke). The
process split (inspect host ↔ pod) already gives the anti-cheat property (answer
key never reaches the pod). Docker/mvm is the defense-in-depth for case-2.

---

## 5b. Tool-calling — the real case-2 design (Seon has NO tool-call concept)

The load-bearing architectural fact (owner-flagged): **Seon agents do not do
OpenAI-style structured tool-calling.** There is no `tools=[…]` array, no
`tool_calls` in the model response, no `submit()` tool. A Seon agent acts by
**writing and evaling Clojure forms** — its "tools" ARE the functions visible in
its namespace context (`my.kb/remember`, `db/transact!`, `todo/add!`, and any fn
it authors). The FSM's turn = "LLM emits forms → pod evals them → results become
context." This is why case-1 works with zero tool plumbing: the memory agent's
tools are its OWN `my.kb` functions, already in its context.

**Implication for case-2 (agent must act in inspect's Docker sandbox — bash,
file IO, web browse):** we CANNOT hand the Seon agent inspect's tool schemas
(it has nowhere to put them). Instead, **for each inspect sandbox capability we
author a Seon function** that the agent evals, and that function performs the
action against the sandbox. Two viable shapes:

- **(A) Author `:seon.fn`s that shell into the sandbox.** e.g. a
  `sandbox/bash` fn in the agent's context whose body does an HTTP/exec call to
  the inspect sandbox (inspect exposes `sandbox().exec(...)` host-side;
  `SandboxEnvironment.exec` — `util/_sandbox/environment.py:104`). The agent
  writes `(sandbox/bash "ls /workdir")`, the pod evals it, the fn round-trips to
  inspect's sandbox and returns the `ExecResult` as data. The agent NEVER sees a
  tool schema — it sees a documented Clojure fn, the native Seon idiom. This is
  the "write functions for all tools" path the owner named, and it FITS Seon
  (functions-as-tools is exactly how the agent already works).
- **(B) Run the whole pod INSIDE the Docker sandbox** and give the agent
  file/exec fns that act on the sandbox's own local FS (the pod IS in the box).
  Heavier (pod boot per sample), but the isolation is airtight and the fns are
  just local `fs`/`child_process` wrappers. This is closer to inspect's normal
  "agent runs in the sandbox" model.

**Recommendation:** case-2 = shape (A) — a small library of `sandbox/*` Seon fns
(bash, read-file, write-file, maybe web-search) authored once into the agent's
context, each ~10 lines, each a normal instrumented `:seon.fn`. This is a
**bounded, additive** phase: no loop change, no tool-calling retrofit — it
extends the agent's function corpus, which is the intended extension point.
GAIA-level-1 needs this; niah / memory / factual-QA do NOT (they're pure case-1).

Cross-link: this is the same insight as [[reference_seon_bitter_lesson_application]]
— "merge the LANGUAGE into the harness vs handcrafted tool catalogs." Seon's
answer to a tool catalog is a fn library, and that's what case-2 authors.

## 5c. Skeptical design review (Gemini) — triaged against Seon's real architecture

Full review: [[inspect-bridge-spike/gemini-skeptical-review-2026-07-01]]. It raised
five concerns. Triaged honestly — some are real and change the design, some are
partly mitigated by Seon's actual architecture (the reviewer assumed a naive
shared-runtime pod):

| Concern | Real? | Verdict / mitigation |
|---|---|---|
| **State pollution between samples** (defs/atoms bleed sample→sample) | **REAL for a shared pod, but the gym already solved it** | The gym runs every scenario on a **fresh scratch `:memory` conn** with per-run schema-key cleanup (`driver.cljs` isolation docstring). A benchmark sample = a fresh scratch child on a fresh scratch conn, not a shared store. The correct `/solve` reuses the gym's isolation, not the live default store. So: solvable with the EXISTING mechanism, but the smoke's shim (which minted children into the live store) is NOT the benchmark shape — the benchmark `/solve` must open an isolated conn per sample. **Design change: `/solve` uses `client/open-agent-conn!` (the gym's isolated path), one per sample.** |
| **Concurrency collision** (inspect runs N samples in parallel, one pod) | **REAL** | Two options: (a) `--max-samples 1` serialize (simplest, slow); (b) isolated conn + isolated child per request so concurrent `/solve` calls don't share a store (the gym's scratch-conn model already supports parallel hermetic runs — MEMORY notes "hermetic test fixtures" for exactly this). Prefer (b); fall back to (a) for v1. Containerized pod-per-sample (Gemini's rec) is the case-2 answer, overkill for case-1. |
| **Host-escape via `(js/require "child_process")`** | **REAL for case-2 untrusted code** | For case-1 (memory/QA) the agent only touches its own DB — no host risk beyond what the live pod already permits. For case-2, Gemini is right: run the pod **inside** the Docker sandbox (shape B), don't rely on a `sandbox/` namespace wrapper on the host (the eval runtime can bypass it). **This flips my §5b recommendation for case-2 toward pod-in-sandbox.** |
| **Orphaned pod process burns DeepSeek $ after inspect timeout** | **REAL** | `/solve` must pass limits INTO the pod (`max_turns`/`max_tokens`/`timeout_ms`) and the pod's FSM enforces them natively (it already has turn-limit + deadline bounds — wire them to the payload). Response returns `termination_reason` + `tokens_used`. |
| **Zero per-turn transcript / token accounting in inspect's viewer** | **REAL tradeoff of Option B** | We lose inspect's per-turn viewer + auto token/cost. Mitigation: the `/solve` response returns turns + tokens + the eval trajectory, and the solver writes them into `state.metadata` / `store` so inspect's log has them (coarser than per-turn model events, but present). This is the accepted cost of keeping Seon's loop intact — and Seon has its OWN inspector/transcript for turn-level debugging. |

**Net:** none of these block case-1; three of them (isolation-per-sample,
limits-in-payload, pod-in-sandbox-for-case-2) are concrete design refinements that
the productionized `/solve` must incorporate. The smoke proved the *bridge*; these
refinements make it a *sound benchmark harness*.

## 5d. Reframe (owner steer) — design the functions to BE the tool surface, bridge for compatibility

Owner direction: *"properly design our functions and have them just be compatible
in some way via a bridge"* + *"we'll need to write functions for all tools since
Seon doesn't have tool calling."* This reframes case-2 away from "mock/wrap
inspect's tools ad hoc" toward a **first-class, reusable Seon capability library**:

- **Seon's tools ARE functions — so design a proper `seon.tools.*` (or agent-context)
  library** of capability fns (shell/exec, file read/write, http/fetch, web-search),
  each a normal instrumented `:seon.fn` with a Malli schema, documented in the
  agent's context the same way `my.kb` is. These are Seon's native tool surface —
  useful to agents in general, not just under inspect.
- **The bridge is thin + adapts, it does not define the tools.** A harness adapter
  (inspect, or later another harness) maps each Seon capability fn to the harness's
  execution substrate: under inspect-with-Docker, `seon.tools/bash` runs against the
  sandbox; standalone, it runs locally (pod-in-sandbox = it's just local exec). The
  fn's CONTRACT (name, schema, returns data) is stable; the bridge swaps the backend.
- **This is the Bitter-Lesson stance** ([[reference_seon_bitter_lesson_application]]):
  don't hand-craft a per-harness tool catalog — give the agent the LANGUAGE + a
  clean fn library, and let a thin adapter make it compatible with whatever harness
  supplies the sandbox. One fn corpus, N harness adapters.

**Open design question for the owner (genuinely undecided — needs a decision before
case-2 build):** where do the capability fns' side effects execute?
1. **Pod-in-sandbox** (Gemini's rec): fns do LOCAL `child_process`/`fs` inside the
   container; the bridge just boots the pod in inspect's Docker sandbox. Airtight
   isolation, native speed, no per-call HTTP. Cost: a pod image + boot-per-sample.
2. **Fns round-trip to the harness's exec API:** pod stays on host, `seon.tools/bash`
   HTTP-calls inspect's `sandbox().exec`. Simpler infra, but host-escape risk +
   per-call latency (Gemini flagged both). 

Recommendation: **(1) pod-in-sandbox for case-2**, with the SAME `seon.tools.*`
fn contracts so case-1 (pod-on-host, fns hit the DB) and case-2 (pod-in-container,
fns hit local shell) share one library and differ only in the adapter/boot. Design
the fn contracts now; defer the case-2 container work until a case-1 benchmark lands.

**Don't reinvent — `seon.agent.fs` is already the template.** Seon ALREADY has a
capability-fn library: `src/seon/agent/fs.cljs` (`read-file`, `write-file`,
`list-dir`, `stat`, `file-exists?`, `walk-dir`) — map-in/map-out, errors-as-values
(`:seon.agent.fs/ok?` discriminator, never throws), and an explicit allowlist gate
(`configure!`/`grants`, `SEON_FS_ROOT`, default-deny, read-only flag). This is
EXACTLY the "functions as tools" surface. Case-2 = **extend this pattern** with the
missing capabilities (an `exec`/shell fn, an `http`/fetch fn) using the SAME house
rules, and point its `configure!` roots at the sandbox FS. The bridge for case-2 is
then trivial: boot the pod in the container, `configure!` fs+exec to the container's
own root — the agent's fns act locally, natively, isolated. No new tool-calling
concept, no per-harness catalog — one gated fn library, the harness supplies the
box. Design the `exec`/`http` fns as `seon.agent.*` siblings of `seon.agent.fs`.

## 6. Blockers / caveats

- **No blockers to case-1.** The one honest caveat: the smoke's `/solve` door was a
  throwaway in-pod shim (stood up via MCP eval — the fastest path to a live proof).
  Productionizing is mounting the identical handler in `seon.web.serve` with a Malli
  request/response schema + a seeded `:seon.route/*` row — a small, well-scoped
  boundary add (the same class as the existing `/agent/{id}/complete` handler), NOT
  loop surgery. The shim already proved the handler logic against the live pod; the
  production version is that logic behind a schema.
- **Python version:** inspect-ai needs 3.10+; the box's default anaconda is 3.9.
  Use a brew-python 3.12 venv (documented above).
- **case-2 tool-bridge** is a genuine additional phase — do not promise GAIA-level-1
  on the case-1 timeline.
- **Turn budget:** the pod's own FSM turn-limit / deadline still bound a run
  (that's Seon's cap, not inspect's). `/solve` passes a wall-clock `timeout_ms`;
  inspect's per-sample `time_limit` is a separate outer guard.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
