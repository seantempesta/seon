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

## 5e. Isolation, throughput, and the Python passthrough (owner follow-ups)

### TWO kinds of state — a fresh conn isolates the DB, NOT the JS runtime

Owner's sharp question: a fresh conn hides sample-1's data, but "the runtime will
still have other previously defined symbols/functions" — is that fine? The honest
answer: there are TWO state layers, and a fresh conn clears only one.

- **DB state** (datoms — the KB, the agent's defined `:seon.fn`/`:seon.schema`
  entities, messages, turns): swapping to a fresh `:memory` conn makes ALL of it
  vanish for the next sample. Clean.
- **JS-runtime state** (the *compiled* vars): when an agent `def`s a fn, the
  self-host compiler ALSO installs the compiled var into the **process-shared
  compile-state** (`seon.eval` docstring, `eval.cljs:18`: *"Vars defined in one
  eval persist for the next — compile-state is process-shared, defonce'd at
  boot"*) and `goog.globalEval`s the JS into the **shared host runtime**
  (`eval.cljs:745`). A fresh conn does NOT clear this.

**Why it's fine in practice (the owner's intuition is right):**

1. **The agent's context is DERIVED FROM THE DB, not the runtime.** Sample 2's
   agent is *told* (in its prompt) only what its fresh conn contains — it has no
   knowledge that sample 1's fns exist, so it won't call them. "Reset the DB → it
   seems like it's not there" is exactly correct: what the agent can SEE and
   reason about is DB-derived, and that's clean.
2. **Namespaces are per-agent-id.** Each agent's home ns is `my.agent.<its-id>`;
   every scratch sample mints a NEW child with a NEW id, so sample 2's fns land in
   a different ns than sample 1's — no collision, no overwrite. Old symbols sit
   inert in a namespace nobody references.

**Where the sharp edge actually is (name it, don't over-worry it):** a leaked
MUTABLE value (an `atom`, a `globalThis` stash) from sample 1 persists in the
runtime; sample 2 could reach it ONLY by guessing the exact symbol — which it has
no reason to (nothing in its context points there). Soft leak, not hard
contamination. Plus slow memory growth over thousands of samples (RSS, not
correctness — a periodic POD restart, not per-sample, handles it).

**The rule:** a fresh `:memory` conn per sample gives **DB + context isolation**
(the isolation that determines what the agent DOES), cheaply, no reset. It does
NOT give **runtime isolation** (leftover compiled vars). For memory/QA that's fine
— behavior is a function of the (clean) context. If you ever need TRUE runtime
isolation (adversarial samples probing leaked state, or bounding memory), that's
**process-per-sample / pod-in-container** — which is exactly where the case-2
Docker path already goes. **It converges: the case-2 isolation IS the runtime-
isolation answer, for free.**

### Contamination → the in-memory scratch conn, NOT a pod reset

A pod reset between samples is the WRONG mechanism — seconds (restart + re-seed),
global (kills every other agent), wipes the whole store. The gym already has the
right, cheap tool: **`seon.client/open-agent-conn!` (`src/seon/client.cljs:596`)**
opens a fresh **in-process `:memory` datahike conn** — no process boot, no disk,
sub-second. `run-scenario!` swaps the root `seon.db/*conn*` to it and restores in a
`finally`, removing minted schema keys (`driver.cljs` isolation docstring). Per
sample: open scratch conn → mint child → drive → read answer → drop conn. "Speed
up the reset" is moot — we sidestep it.

### The real throughput lever: `*conn*` is a single dynamic root (not fiber-local)

`seon.db/*conn*` is ONE `defonce ^:dynamic` root per pod runtime
(`src/seon/db.cljs:337`). `with-agent` / tx-context ARE fiber-local
(AsyncLocalStorage, safe under concurrent agents — `db.cljs:356`), but the CONN is
not. inspect runs samples in parallel; two solves swapping `*conn*` globally would
race. Two answers:

- **v1 (baseline): `inspect eval --max-samples 1`** — serial, each sample
  opens/swaps/drops its own `:memory` conn. Correct, contamination-free, fast
  enough for a baseline. **Do this first.**
- **v2 (throughput): make `*conn*` fiber-local** — bind it in the SAME
  AsyncLocalStorage scope `with-agent` already uses, so concurrent solves each see
  their own conn. Bounded follow-up; NOT needed for the first baseline. This — not
  pod-reset speed — is the parallelism lever.

### The `my.*` tool library the bridge adapts (owner's frame)

The agent's native tool surface is already `my.*` fns (`my.kb` memory;
`my.ui`/`my.canvas`/`my.data` UI toolkit) + `seon.agent.fs` (files). The case-2 tool
library = **`my.*` siblings in the SAME house style** (map-in/out,
errors-as-values, capability-gated), and the bridge ADAPTS them per harness (local
backend vs sandbox exec). One documented fn corpus the agent already understands;
the adapter swaps the backend. Design the contracts against the existing
`seon.agent.fs` template.

### Python passthrough — a `my.py/run` capability fn (source stays DATA)

Trivial and safe. Clojure strings take literal newlines inside `"..."` (only `"`
and `\` need escaping), so multi-line Python "just works" as a string literal:

```clojure
(my.py/run {:my.py/source "import sys\nprint(sys.version)"})
;=> {:my.py/stdout "3.12.11\n" :my.py/stderr "" :my.py/exit 0}
```

The load-bearing rule (same as `seon.web.reactive.call`'s "args stay DATA, never
recompiled"): **the fn ships the source to the interpreter as an ARGUMENT/stdin,
never by string-concatenating it into a shell line** (the injection trap). So
`my.py/run` pipes the source to `python -` (or writes a temp file and runs it),
captures stdout/stderr/exit into a map. The agent never builds a shell command;
the fn does, with the source as stdin. Under inspect+Docker it pipes to the
sandbox's `python`; standalone, to local `python`. Same contract, adapter swaps
the backend — the `my.*`-fn-plus-adapter pattern above. (Bitter-Lesson: pass the
LANGUAGE through, don't hand-craft a tool schema per interpreter.)

## 5f. `/solve` correctness audit — verifier findings + the greeting root-cause

The productionized `POST /solve` handler (`src/seon/web/serve.cljs` `handle-solve!`
/ `solve-once!`) was audited by an independent `seon-verifier` against the gym's
battle-tested `agent-reply-text` + the real FSM source. It returns a green 200 but
was **recording garbage** — the exact "if we don't record correctly we're fucked"
failure. Ranked defects (all confirmed live — a drive returned the bootstrap
greeting twice as the "reply", `closed_reason :waited`):

| # | Defect | Severity | Root |
|---|---|---|---|
| 1 | **Reply query includes the bootstrap greeting** + uses the datahike-cljs banned double-identity-join shape (`serve.cljs:486`). Returns the greeting as the benchmark answer. | **CRITICAL** | the greeting exists (see below) |
| 3 | **Timeout reports a false `closed_reason`** — reads `last-closed-reason` of a PRIOR run → `:completed`/`:waited` when the task actually timed out (`serve.cljs:495`). | **HIGH** | no timeout discrimination |
| 2 | **Split-snapshot race** — `derive-state` and `latest-run-start-ms` deref `@db/*conn*` independently (two snapshots) (`serve.cljs:471`). NB the verifier CORRECTED the earlier hypothesis: `derive-state` CANNOT return `:idle` mid-run (a run stays `:open` between turns per `loop.cljs:200-284` / `run.cljs:212-268`) — so the risk is the split snapshot + the boot-greeting run, not inter-turn idle. | **MEDIUM** | two derefs |
| 4 | **Turn/eval counts are agent-wide** → inflated by the greeting run's +1 turn + its evals (`serve.cljs:477-485`). | **LOW-MED** | the greeting run exists |

**The unifying root cause (owner's diagnosis): the bootstrap greeting.** Defects
1, 4, and half of 2 all stem from `seon.client/bootstrap-turn!` (`client.cljs:2224`)
eval'ing `(message/user "Hi — I'm up …")` as turn 0 of every minted agent. That
greeting is a chat-UX artifact welded into the CREATION mechanism — it does ZERO
wire-up (compile-state + seed run before turn 0; standing context is derived every
turn — the docstring says so), nothing depends on it (the gym has to work AROUND it,
`driver.cljs:1558`), and the UI already renders `● idle` from `derive-state` without
it. The only functional piece of turn 0 is the `(wait …)` park → `:idle`.

**Decision (owner-directed): remove the greeting at the root, don't band-aid the
reader.** `bootstrap-turn!` → park-only (`(wait "awaiting first task")`), no
`message/user` hello. Then a fresh agent's message log is EMPTY until a real message
arrives → `/solve` reply extraction needs NO `q-from` filter, defect 1 + 4 vanish,
and the gym drops its turn-0 workarounds. This is a **Core change** (`seon.client`),
verified separately (resume + gym suite + UI idle render stay green). Open sub-choice
(owner): keep the eval'd park-turn-0 (agent sees "I parked" in its own transcript —
self-context continuity + the `:waited` run close) vs drop turn 0 entirely (set
`:idle` with no turn). Recommendation: **keep-park-drop-hello** (minimal, preserves
self-context).

**Still needed regardless of the greeting** (independent of the root fix): defect 3
(emit `closed_reason:"timeout"` + a `timed_out` bool on the clock-exit path) and
defect 2 (take ONE `@db/*conn*` snapshot per poll, pass it to both reads). Fix all
three before the endpoint records a real benchmark.

**Live falsification (orchestrator, on the pod after the implementer's guard
landed):**

- Happy path CORRECT: `{"input":"…remember launch date 1969-07-20…","timeout_ms":180000}`
  → `{"turns":3,"evals":9,"closed_reason":":completed","reply":"The launch date is
  1969-07-20."}`. The `latest-run-start-ms` guard successfully skips the greeting
  idle → the reply is the ANSWER, not the greeting. So the happy path records
  truthfully.
- **Timeout path LIES (defects 1 + 3 confirmed):** same task with `timeout_ms:2000`
  → `{"turns":1,"evals":2,"closed_reason":":waited","reply":"Hi — I'm up and
  connected to the shared store…"}`. The task did NOT complete — the clock cut it —
  but the endpoint reports the GREETING run's `:waited` close and the GREETING as
  the reply. A scorer reads this as a legit termination with a (wrong) answer =
  **benchmark-corrupting false success.** Plus the real task run kept executing
  orphaned after the response (token burn). This is the exact "record incorrectly →
  fucked" failure; the greeting removal + a `timed_out` flag both fix it.

## 5h. Blast radius of deleting turn 0 — verified: production is already zero-run-safe

An independent Explore pass mapped every site that might assume "≥1 run/turn exists"
for a minted agent. **Verdict: the derived-state architecture already treats a
zero-run agent as a first-class valid `:idle` state** (`agent.cljs:414` documents "a
fresh agent with no open run is `:idle`"). No HARD breaks in production
render/derive/wake. The only HARD sites are test/gym + one HTTP heuristic:

| Layer | Verdict | Detail |
|---|---|---|
| **derive.cljs** (current-run, derive-state, run/agent-turn-count, last-closed-reason, derive-status, armable-agent-ids) | **nil-safe by design** | zero-run → `:idle`, counts → 0, reason → nil. No NPE. |
| **Wake / resume** (`agent.cljs:378` gate, `loop.cljs` wake-handler `:idle`→`open-run!` CAS) | **works** | a zero-run agent is `:idle` → first message opens run #1 exactly as it opens run #2 today. Wake trigger install is SEPARATE from `bootstrap-turn!` (`client.cljs:2447`). |
| **Transcript** (ai + html twins) | **SOFT** | explicit empty placeholder ("no events yet…"); "nil when the agent has not acted yet". Renders clean. |
| **Inspector / UI** (`render/default`, `web/debug`, `ui/world`, `ui/header`, `web/datastar`) | **nil-safe** | count-based, placeholders, derive in try/catch. |
| **Gym** (`driver.cljs` cause-scoping at 760/803/809/860/886 + `agent-reply-text` q-from) | **SOFT** | turn-0 exclusion keyed on `:seon.agent.run/cause` (bootstrap run has none) — becomes vacuous, stays correct; simplifiable. |
| **`driver.cljs:1642` `ensure-agent!` calls `bootstrap-turn!`** | **HARD (compile)** | unresolved symbol if deleted → must remove the call. |
| **`driver_test.cljs:992` `[:count 2]` greeting+reply pin** | **HARD (test)** | asserts b sends EXACTLY 2 user msgs (greeting+reply). Without turn 0 → 1. Re-express to `[:count 1]` (it's the double-identity-join regression pin — re-key, don't delete). |
| **`serve.cljs:433-473` `/solve` boot-idle heuristic** | **HARD/SOFT** | `latest-run-start-ms` + the "skip the boot idle" rationale become moot (a zero-run agent has `latest-run-start-ms = 0 < injected-at`, guard still correct). Simplify + fix the stale comments; re-verify timing. |

**The per-agent ns + require wiring is REAL and REQUIRED — but it already lives
OUTSIDE the boot turn (verified).** A new agent DOES need `(ns my.agent.<id>
(:require [seon.agent.message :as message] [seon.agent :as agent]
[seon.agent.lifecycle :refer [wait complete pause resume terminate]]
[seon.schema :as schema] [seon.db :as db] [seon.agent.todo :as todo]))` evaluated
into its home namespace so its reflexive `(message/user …)` / `(wait …)` /
`(db/query …)` forms resolve. That is `seon.eval/setup-agent-ns!` (`eval.cljs:1388`),
called by **`boot-one-agent!` (`client.cljs:2065`)** — the per-agent boot slice that
ALSO does `agent/boot!` (entity/DB state), arms the wake trigger, and hosts the id.
`bootstrap-turn!` is a SEPARATE, LATER call (`client.cljs:2448`) doing ONLY the
greeting + park. **So deleting `bootstrap-turn!` leaves ALL ns/require wiring +
entity state + trigger arming intact** — the deterministic setup already lives in
`boot-one-agent!`; the boot turn adds nothing to it. This is the crux: the wiring the
owner rightly insisted on is NOT in the turn.

**The change (surgical, for owner sign-off):**
1. `seon.client`: DELETE `bootstrap-turn!`, `hello-source`, `park-source`; remove the
   call in `start-agent!` (`client.cljs:2447`). Minting = transact the agent's
   `:seon.agent/*` datoms + arm the wake trigger → `:idle`, zero runs, ready.
2. `test/seon/gym/driver.cljs`: remove the `bootstrap-turn!` call in `ensure-agent!`;
   the cause-scoping + `agent-reply-text` q-from filter can stay (vacuous) or be
   simplified — keep them for now to minimize churn.
3. `test/seon/gym/driver_test.cljs:992`: re-express the `[:count 2]` pin to `[:count 1]`
   (still pins the double-identity-join direction bug, just without the greeting).
4. `serve.cljs` `/solve`: simplify the boot-idle heuristic (now solving a non-problem)
   + apply the timeout-honesty (defect 3) + single-snapshot (defect 2) fixes.

**Verification bar before commit:** resume (parked agent wakes on a new message), the
full gym suite (`bin/test-cljs`), the UI idle/parked render, and the `/solve` smoke
incl. the 2s-timeout case reporting an HONEST timeout. Live-prove each.

## 5i. Duplicate init paths → ONE configurable `init-agent!` (owner-directed cleanup)

Agent init is smeared across THREE places with overlapping steps and no single
configurable entry — a "one mechanism" violation:

| Fn | Steps | Called by |
|---|---|---|
| `boot-one-agent!` (`client.cljs:2053`) | `setup-agent-ns!` → `agent/boot!` (entity) → `install-wake-trigger!` → `runtime-id/host!` | `start-agent!` (boot + `/agents/new`) |
| `arm-agent!` (`client.cljs:1985`) | `runtime-id/host!` → `setup-agent-ns!` → `install-wake-trigger!` | `rearm-wake-triggers!` (hot-reload) + spawn hook `!arm-child-fn` |
| `bootstrap-turn!` (`client.cljs:2224`) | open run → eval greeting + park → close `:waited` | `start-agent!`, once, after boot-one-agent! |

**The duplication:** `boot-one-agent!` and `arm-agent!` both do
`setup-agent-ns!` + `install-wake-trigger!` + `runtime-id/host!` in DIFFERENT order;
`boot-one-agent!` adds `agent/boot!`. `arm-agent!`'s docstring even says "Same order
as the re-arm loop: host → setup-ns → install-trigger" — two hand-synced copies of
the same wiring (drift risk). `bootstrap-turn!` is a vestigial mint-only third step.
The ONLY real difference between mint and re-arm is whether the ENTITY is created.

**The fix: ONE `init-agent!`, map-in, deterministic, configurable via args.**

```clojure
(schema/register! ::init-agent-request
  [:map [:seon.agent/id ...] [:seon.agent/mint? {:optional true} :boolean]
        [:seon.agent/purpose {:optional true} ...] [:seon.agent/parent {:optional true} ...]
        [:seon.agent/llm-fn {:optional true} ...] [:seon.agent/compile-state {:optional true} ...]])

(defn ^:async init-agent!
  "The ONE way an agent comes to life or re-arms. Deterministic: wire the home
   ns (requires), ensure the entity (mint? only), arm the wake trigger, host the
   id. NO turn-0 ceremony. Ready = :idle, zero runs, fully wired."
  {:malli/schema [:=> [:cat ::init-agent-request] ::agent-ready]}
  [{:seon.agent/keys [id purpose parent llm-fn compile-state mint?]}] …)
```

Fixed step order: ensure compile-state → `setup-agent-ns!` (require wiring) →
*(mint?)* `agent/boot!` (entity + purpose/parent) → `install-wake-trigger!` →
`runtime-id/host!`. `boot-one-agent!`, `arm-agent!`, and the spawn hook collapse
into this one call with different args; `bootstrap-turn!` is DELETED. `/agents/new`
AND `/solve` both call it with `mint? true` + `purpose` — HTTP mint and harness mint
share ONE init, zero divergence. This is the proper landing for the greeting removal:
the required wiring becomes the body of one configurable fn, the ceremony is gone,
and mint-vs-rearm is a single arg not two hand-synced copies.

**Scope note:** this is a slightly bigger Core change than "delete the greeting"
(unify 2-3 fns), but it's the CORRECT one — and it's the owner's explicit ask ("one
proper way to init the agent with proper args"). Needs the same verification bar
(resume/hot-reload re-arm, gym suite, UI idle-render, `/solve` incl. timeout) PLUS a
hot-reload re-arm live-proof (the `arm-agent!` path must still work through the
unified fn). `rearm-wake-triggers!` calls it with `mint? false`.

## 5j. Phase-0 CLOSE — end-to-end honest smoke against the productionized `/solve`

After the greeting removal + `/solve` timeout-honesty fix (committed `bfac6f50`),
the full inspect→pod→scorer loop was re-run against the PRODUCTIONIZED `/solve` in
`seon.web.serve` (not the throwaway shim). Two inspect tasks, both `status=success`:

**`seon_memory_smoke` (happy path, 3 samples, host-side `includes`): accuracy 1.000.**
Each a fresh agent running its OWN multi-turn FSM, honest recording:

| sample | score | closed | turns | evals | reply |
|--------|-------|--------|-------|-------|-------|
| 1 | C | `:completed` | 4 | 11 | "Dana Okafor led Project Zephyr, which launched in March 2021." |
| 2 | C | `:completed` | 5 | 10 | "The Helios reactor in Reykjavik produces **42 megawatts**." |
| 3 | C | `:completed` | 1 | 7 | "Priya Raman commands the Orion mission, which carries 6 crew members." |

**`seon_timeout_honesty` (forced 2s timeout, custom scorer): accuracy 1.000.**

| sample | score | timed_out | closed | reply |
|--------|-------|-----------|--------|-------|
| 1 | C | **True** | **timeout** | `''` (empty — NO false answer) |

The timeout scorer marks CORRECT only when `timed_out=True` AND `closed_reason`
contains `"timeout"` — it explicitly FAILS a false success. It passed: the pod
recorded the clock cut-off HONESTLY (not a stale `:completed`/greeting), carried
end-to-end through inspect. **This is the anti-"we're-fucked-if-we-mis-record" proof.**

**Phase-0 = DONE.** A proven, non-invasive, HONEST inspect bridge: Seon's pod agent
runs as an inspect `@solver` via HTTP; it owns its own multi-turn loop (inspect never
manages turns); host-side scorer + answer key stay in inspect's process; and the pod
records truthfully on both the happy path AND under a timeout. `/solve` is
PRODUCTIONIZED in `seon.web.serve` (schema'd handler + seeded route, commit
`bfac6f50`), not a shim.

Effort to the first REAL benchmark (niah / memory-QA): **~1–2 days** (case-1) — a
real dataset through the same `@solver` + `pass^k` epochs + the isolation-per-sample
+ parallelism refinements (§5c/§5e). SEPARATE owner-gated step; NOT started.

## 5k. CORRECTION + fix — `/solve` isolated the NAMESPACE, not the DATABASE

**Correction to §5j / earlier claims.** The Phase-0 close claimed `/solve` "isolates
per sample (mints a scratch child per request)." That was **namespace-only** isolation
(fresh agent → clean `my.agent.<id>`), NOT database isolation: every request ran in the
SHARED live `*conn*`, so sample N's agent saw samples 1..N-1's facts in `my.kb`. A
benchmark (B2) scored 37.5% purely from cross-sample contamination (agents quoted other
samples' facts). Fresh agent ≠ fresh store — the empty-message-log check gave a false
sense of isolation. §5c specified the per-sample scratch-store fix; it was never
implemented in the productionized `/solve`. Owned + fixed.

**The fix (`seon.web.serve/solve-once!`, mirrors the gym's `run-scenario!`):** each
`/solve` request now (1) saves `prev-conn` + the FULL `@schema/*schemas` snapshot, (2)
`set!`s `*conn*` to a fresh `:memory` scratch conn (`open-agent-conn!`), (3) SEEDS the
core into it (`boot-seed!` under a primary `with-agent` scope — so the agent has its
schema + `my.kb` toolkit + blocks; an unseeded void is useless), (4) mints+arms the
scratch agent via **`init-agent!`** (NOT `start-agent!` — the latter re-`set!`s `*conn*`
back to the cluster store, which clobbers isolation and drove a scratch agent's turns
onto the shared wire; a real CAS-fail during impl proved this), (5) runs the existing
message→poll→read against the scratch conn, (6) `finally` restores `*conn*` + the schema
registry. serve.cljs can't require seon.client (cycle), so `open-agent-conn!`/
`boot-seed!`/`init-agent!` are INJECTED via `set-solve-deps!` (same seam as
`!create-agent-fn`). **SERIAL-ONLY** — the async wake path reads root `@db/*conn*`, so
the benchmark must run `--max-samples 1`; fiber-local conn is a separate future item.

**Live-proof (orchestrator-verified, not just the implementer's report):**
- **Within-sample store→retrieve:** one `/solve` stored WOMBAT and replied
  `"The project codeword is WOMBAT — stored and confirmed."`, `:completed`.
- **Isolation across samples:** a SEPARATE `/solve` asked "what is the codeword?" —
  agent `cBW` searched its KB and NEVER surfaced WOMBAT (empty reply, hit `:turn-limit`
  hunting a codeword absent from its fresh store). Sample 2 got a clean store.
- **Shared store intact (my own Datalog query over the cluster):** the scratch agents
  (`dHH`/`cBW`) are NOT in the cluster, and no codeword landed in the shared `my.kb` —
  the `finally` restored `*conn*` to the cluster conn; scratch drives never touched it.

Live-agent shared-store model (start-agent!/`/chat`/`/agents/new`) is UNCHANGED —
this is `/solve`-only. The benchmark harness (B1) can now re-run B2 for a real baseline.

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
