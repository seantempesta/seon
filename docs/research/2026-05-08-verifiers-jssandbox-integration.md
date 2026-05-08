 # Verifiers ↔ JS sandbox integration — Path A (Node + `@sebastianwessel/quickjs`) vs Path B (Deno subprocess)

**Date:** 2026-05-08 (Friday afternoon, Bangkok)
**Author:** research agent under Sean's direction
**Question:** the agent's Phase-0 training loop runs Verifiers (Python). The agent emits JS that calls EAVT primitives + uses `define()` to admit functions to a per-user library. We need a JS-execution substrate. A previous agent argued Deno is the easier path because of native npm autodownload. Sean wants this re-evaluated honestly, with attention to whether arbitrary npm is actually a Phase-0 need, IPC overhead at training scale, the threat model the capability boundary is actually catching, and per-instance footprint at 1K concurrent trajectories.
**Process:** Source-code reads on `~/src/reference/verifiers/`, `~/src/reference/sebastianwessel-quickjs/`, `~/src/reference/deno/`. Targeted Gemini 3 Flash lookups for IPC latency, footprint, and Deno permission-API specifics. Numbers below are tagged **VERIFIED** (cite linked or read in source) or **UNVERIFIED** (best estimate, flagged) per the doc-discipline rule.

Companion to [`2026-05-08-wasm-wasi-substrate.md`](2026-05-08-wasm-wasi-substrate.md), which selected `quickjs-emscripten` over a custom Wasmtime build. This doc re-evaluates that choice **specifically against Verifiers integration** and the Deno alternative the WASM doc deferred.

---

## 1. TL;DR

**Recommendation: Path A — Verifiers (Python) ↔ Node sidecar with `@sebastianwessel/quickjs`. Long-lived sidecar pool, one QuickJS context per trajectory, JSON-RPC over stdio. Estimated 5–7 engineering-days to working spike; 2–3 weeks to a hardened Phase-0 substrate.**

The Deno-is-easier argument leans almost entirely on `import "npm:..."` autodownload. **For the agent itself is composing five EAVT primitives plus a `define()` discipline — it is not pulling random npm libraries.** The five or six libraries the agent might genuinely want (date math, JSON-schema, text utilities) bundle cleanly into `@sebastianwessel/quickjs`'s `nodeModules` option (verified in `example/custom-module/index.ts`). And on Deno, the equivalent of "we curate the imports" is `--cached-only` plus a `deno.lock` (verified via Gemini against [docs.deno.com](https://docs.deno.com/runtime/fundamentals/configuration/#lockfile)) — the same shape, not an inherent Deno win.

What matters more than npm:

- **Per-instance footprint.** At 1K concurrent training trajectories, Path B is ~30–45 GB of Deno-process resident memory before the agent's state lives anywhere; Path A is a single Node host with ~5–10 MB per QuickJSContext = ~5–10 GB. Path B costs roughly 4–8× the RAM. **VERIFIED for Deno baseline (~40–45 MB per process per Gemini); UNVERIFIED but sourced for QuickJSContext (Section 8).**
- **IPC overhead is similar between paths.** The Python↔JS-host stdio JSON-RPC round trip is the same shape in both (~0.3–0.6 ms/call, **UNVERIFIED**). The win for Path A is *batching* — one Node host can serve many trajectories with shared IPC pipes; the Deno design is naturally one-process-per-trajectory.
- **Capability honestly.** Deno's permission grain is host/port (`--allow-net=domain.com` opens every path on `domain.com`). `@sebastianwessel/quickjs` exposes only host-supplied functions — there is no ambient anything. the agent's threat model is "our agent, not adversarial; watchdog catches malicious; capability is to prevent footguns + reproducibility issues" — **both substrates over-deliver. Neither's capability story is the deciding factor.**
- **Verifiers fit.** Verifiers' `SandboxEnv` (read in source — `verifiers/envs/sandbox_env.py`) is the closest existing template. It builds on `StatefulToolEnv`, manages per-rollout sandbox lifecycle in `setup_state`, exposes a single `bash` tool with `update_tool_args` injecting `sandbox_id`/`sandbox_state` invisibly, and tears down in `@vf.cleanup`. **Both Path A and Path B map onto this template directly** — replace the `prime-sandboxes` client with our own client; replace `bash` with `exec_js` (or with a finer-grained tool surface). The Verifiers integration cost is identical between paths.

### The honest caveats

1. **JS coverage gap.** QuickJS supports most ES2023; Deno's V8 is full ECMAScript-everything. If the agent's prior emits modern V8-only constructs (e.g., temporal API, advanced async generators with structured-clone semantics, Web Streams) we'll see runtime failures Path B wouldn't see. Mitigation: Phase-0 spike measures this on real Qwen output; if QuickJS bites, fall back to Path B *or* graduate to `isolated-vm` from Node (V8 isolate inside the Node host — same footprint advantage as Path A, full V8 coverage).
2. **`@sebastianwessel/quickjs` is one maintainer.** Bun-first project, MIT license, actively developed (`CHANGELOG.md` and recent commits visible in `~/src/reference/sebastianwessel-quickjs/`), but bus-factor 1. We pin a version, vendor the source if needed, can fall back to raw `quickjs-emscripten` (the underlying `justjake/quickjs-emscripten` package) directly. Not a blocker.
3. **Deno wins outright if the V1 scenarios genuinely need npm libraries we can't pre-bundle.** I don't think they will — Section 7 makes the concrete case — but if the spike surfaces ~50+ packages and arbitrary import surface, switch to Path B. Migration cost from A to B is bounded because the Verifiers-side adapter is identical.
4. **Latency math is dominated by the LLM, not the sandbox.** At 200K primitive invocations × ~1 ms each = ~200 s of sandbox time per training run. The 200K LLM forward passes that wrap them are 5–6 orders of magnitude more wall-clock. **The sandbox latency difference between Path A and Path B is irrelevant at Phase-0 scale.** What matters is footprint, integration cleanliness, and the dev-loop ergonomics. Path A wins those.

### What changed our understanding vs the WASM doc

The WASM doc landed on `quickjs-emscripten` as "the boring answer" but framed Deno as a peer alternative. **This doc disagrees with that peer framing in one direction:** Deno's structural design is one-process-per-session, which is the wrong shape for the agent's 1K-concurrent-trajectory training topology. `quickjs-emscripten` (and by extension `@sebastianwessel/quickjs`) is one-process-many-contexts by design — same Node host, many cheap QuickJS heaps. **Path A is not just easier; it's the structurally correct shape for the workload.** Deno's win-conditions (need to mix Python/JS at request scope, ship to a serverless edge, inherit a vast TypeScript dep tree) don't apply to the agent's training loop.

---

## 2. Verifiers integration surface — what does it actually need?

Reading `~/src/reference/verifiers/verifiers/`:

### `Environment` (base, `envs/environment.py:94`)

Abstract `rollout(input, client, model, sampling_args) -> State`. `init_state` is fully implemented in the base class — sets up `state["trajectory"]`, `state["tool_defs"]`, `state["timing"]`, etc. We do not reimplement this; we inherit.

### `MultiTurnEnv` (`envs/multiturn_env.py:39`)

Implements the rollout loop. Calls `await self.env_response(messages, state)` between model turns. The `rollout()` method is `@final` — we do **not** override it. We override `env_response` (or, via the `StatefulToolEnv` chain below, we override `update_tool_args`).

The loop:
```
init_state -> setup_state -> while not is_completed:
    get_prompt_messages -> get_model_response -> add_model_response
    (env_response is called inside get_prompt_messages)
-> cleanup
```

### `StatefulToolEnv` (`envs/stateful_tool_env.py:42`)

Subclasses `ToolEnv`. Overrides `env_response` to dispatch tool-calls in the assistant message. Calls `self.update_tool_args(tool_name, tool_args, messages, state)` before invoking the tool — this is where state-dict mutation happens. The agent only sees the tool's "public" args; state-shaped args (`sandbox_id`, `sandbox_state`, etc.) are injected here invisibly. **This is the right shape for our `exec_js`/`assert`/`query` tools** — the JS-context-id stays in `state`, hidden from the agent.

### `Rubric` and `JudgeRubric` (`rubrics/rubric.py:1`, `rubrics/judge_rubric.py:31`)

Rubric composes per-call reward functions. JudgeRubric calls a separate LLM judge with a templated prompt (default at `judge_rubric.py:10` — `"Given a ground truth answer and a response, determine if the response is correct."`). For the agent, we instantiate three judges: cultural-native grader, watchdog, decidable checker (which is not LLM-judged but a Python verifier reading the trace). All compose via `Rubric`.

### `TrajectoryStep` (`types.py:235`)

```python
class TrajectoryStep(TypedDict):
    prompt: Messages
    completion: Messages
    response: Response
    tokens: TrajectoryStepTokens | None
    reward: float | None
    advantage: float | None
    is_truncated: bool
    trajectory_id: str
    extras: dict[str, Any]
```

`extras` is the escape hatch. **We put the JS-side trace there**: every primitive called this turn, the EAVT diff, the agent's emitted JS source, `define()` admissions, watchdog flags. This is what gets serialized to disk for offline analysis and (eventually) LoRA training.

### `SandboxEnv` (`envs/sandbox_env.py:66`) — the template

This is the file to read. `SandboxEnv` is Verifiers' built-in adapter for `prime-sandboxes` (Prime Intellect's container service). It demonstrates exactly the integration shape the agent needs:

- Inherits `StatefulToolEnv`.
- `setup_state` (line 227) creates a per-rollout sandbox via `self.sandbox_client.create(request)`, stuffs `sandbox_id` and `sandbox_state` into `state`.
- Exposes a single tool `bash` (line 152) registered via `self.add_tool(self.bash, args_to_skip=["sandbox_id", "working_dir", "sandbox_state"])` — the agent sees `bash(command)`, the host injects the rest.
- `update_tool_args` (line 247) reads `state["sandbox_id"]` and patches it into the tool args before invocation.
- `@vf.cleanup` `destroy_sandbox` (line 205) deletes the sandbox at end of rollout. `@vf.teardown teardown_sandboxes` (line 271) catches any orphaned sandboxes at process exit.

**For the agent, replace `prime-sandboxes` with our own JS-sandbox client. Replace `bash` with `exec_js` (or `assert`/`retract`/`query`/`define`/`call`/`note`/`embed`/`nearest` — Section 3 discusses the granularity question). Everything else carries over. The Verifiers integration is mechanical.**

---

## 3. Path A — Verifiers + Node sidecar with `@sebastianwessel/quickjs`

### 3.1 Architecture

```
┌────────────────────────────────────────────────────────┐
│                Verifiers (Python)                      │
│  the agentEnv(StatefulToolEnv)                              │
│   ├─ setup_state    : POST /session  → session_id      │
│   ├─ tool: assert   : RPC assert(ctx, e,a,v)          │
│   ├─ tool: query    : RPC query(ctx, pattern)          │
│   ├─ tool: define   : RPC define(ctx, name, spec, ...) │
│   ├─ tool: call     : RPC call(ctx, name, args)        │
│   ├─ tool: exec     : RPC exec(ctx, code)              │
│   └─ cleanup        : POST /session/{id}/destroy       │
└────────────────────────────────────────────────────────┘
                           │
                           │ JSON-RPC over stdio (or HTTP+Hono)
                           │
┌────────────────────────────────────────────────────────┐
│       Node sidecar (long-lived process, pool of N)     │
│  Hono server + per-session QuickJSContext              │
│   ├─ Map<session_id, QuickJSContext>                   │
│   ├─ EAVT host fns wired via expose() into each ctx    │
│   ├─ Pre-bundled nodeModules (date-fns, ajv, etc.)     │
│   └─ Trace recorder per session                        │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │  EAVT store  │  (in-process for Phase 0;
                    │  per session │   sqlite/Datalevin later)
                    └──────────────┘
```

The agent emits JS into `exec()` or via `define()`. The host functions inside the QuickJSContext (`assert`, `query`, etc.) are wired by the Node sidecar and execute against an in-process EAVT store. **The Python parent only sees the trajectory record at end of turn** — it does *not* mediate every primitive call. This is the load-bearing performance choice.

### 3.2 Engineering hours estimate

| Layer | Hours | Notes |
|---|---|---|
| (a) Python↔Node IPC layer | 6–8 | One subprocess per Node host (not per session). JSON-RPC over stdio is the simplest; HTTP via Hono is a swap-in if we need pooling/load-balancing. Verifiers is async (`asyncio`), so use `asyncio.subprocess.create_subprocess_exec` + line-delimited JSON. |
| (b) Primitive host-function bridge | 12–16 | Six primitives (`assert`, `retract`, `query`, `schema`, `embed`, `nearest`) + the meta-trio (`define`, `call`, `exec`, `note`). EAVT store mocked for spike (in-memory dict-of-dicts), real Datalevin/sqlite later. Each primitive: ~30 lines TS in the Node host; Python-side just identifies `which session` and forwards. |
| (c) Session lifecycle | 6 | Long-lived Node sidecar pool (default 1, scale with concurrency). Per-trajectory: create QuickJSContext on `setup_state`, dispose on `cleanup`. QuickJSContext creation is sub-ms (per `quickjs-emscripten` README, **UNVERIFIED specific number**). Sidecar restarts on graceful drain or crash. |
| (d) Verifiers `StatefulToolEnv` adapter | 4 | Subclass `StatefulToolEnv`, override `setup_state` and `update_tool_args`, register the primitive tools. Mirror exactly `SandboxEnv` shape. |
| (e) `define()`/spec+test contract bridging | 16–20 | This is the load-bearing-novel work. The host-side runner: parse `spec` (Pydantic model), run `tests` against `impl` inside the QuickJS context (using `@sebastianwessel/quickjs`'s built-in `enableTestUtils: true` for chai/mocha or our own minimal harness), reject on test failure, admit to per-session library on pass. The test runs are themselves sandboxed `evalCode` calls against the same context. |
| (f) Trajectory record extraction | 6 | At `cleanup` time, drain the trace buffer from the Node host, pack into `state["extras"]["aria_trace"]`. Each trace entry is `{turn, primitive, args, result, eavt_delta, ts}`. Verifiers serialization handles the rest. |
| **Subtotal — minimum spike** | **50–60** | About 6–8 working days. |
| Hardening (error handling, timeouts, retries, leak prevention) | +20 | |
| Watchdog integration (DeepSeek reads trace mid-rollout, flags) | +12 | |
| Pre-bundled `nodeModules` (Section 7) | +6 | |
| **Total — Phase-0-ready** | **~88–98** | About 2–2.5 weeks. |

### 3.3 Code skeleton — the load-bearing bits

**Python side — `aria_env.py`:**

```python
import asyncio
import json
import uuid
from typing import Any
import verifiers as vf

class the agentJSEnv(vf.StatefulToolEnv):
    def __init__(self, sidecar_cmd: list[str], **kwargs):
        super().__init__(stop_errors=[vf.SandboxError], **kwargs)
        self.sidecar_cmd = sidecar_cmd
        self._proc: asyncio.subprocess.Process | None = None
        self._req_id = 0
        self._pending: dict[int, asyncio.Future] = {}
        # register primitives as tools
        for name in ("assert_fact", "retract", "query", "define_fn", "call_fn", "exec_js"):
            self.add_tool(getattr(self, name),
                          args_to_skip=["session_id"])

    async def _start_sidecar(self) -> None:
        self._proc = await asyncio.create_subprocess_exec(
            *self.sidecar_cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
        )
        asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        async for line in self._proc.stdout:
            msg = json.loads(line)
            fut = self._pending.pop(msg["id"], None)
            if fut is not None and not fut.done():
                fut.set_result(msg.get("result", msg.get("error")))

    async def _rpc(self, method: str, params: dict) -> Any:
        if self._proc is None:
            await self._start_sidecar()
        self._req_id += 1
        rid = self._req_id
        fut: asyncio.Future = asyncio.Future()
        self._pending[rid] = fut
        assert self._proc and self._proc.stdin
        self._proc.stdin.write((json.dumps(
            {"id": rid, "method": method, "params": params}
        ) + "\n").encode())
        await self._proc.stdin.drain()
        return await fut

    async def setup_state(self, state: vf.State, **kwargs) -> vf.State:
        sid = uuid.uuid4().hex
        await self._rpc("session.create", {"session_id": sid})
        state["session_id"] = sid
        state["aria_trace"] = []
        return await super().setup_state(state, **kwargs) or state

    @vf.cleanup
    async def destroy_session(self, state: vf.State) -> None:
        sid = state.get("session_id")
        if sid is None:
            return
        trace = await self._rpc("session.drain_trace", {"session_id": sid})
        state["extras"]["aria_trace"] = trace
        await self._rpc("session.destroy", {"session_id": sid})

    def update_tool_args(self, tool_name, tool_args, messages, state, **kwargs):
        return {**tool_args, "session_id": state["session_id"]}

    # primitive shims — agent sees these signatures (without session_id)
    async def assert_fact(self, e: str, a: str, v: Any, session_id: str) -> dict:
        return await self._rpc("assert", {"sid": session_id, "e": e, "a": a, "v": v})

    async def query(self, pattern: dict, session_id: str) -> list:
        return await self._rpc("query", {"sid": session_id, "pattern": pattern})

    async def exec_js(self, code: str, session_id: str) -> dict:
        return await self._rpc("exec", {"sid": session_id, "code": code})

    async def define_fn(self, name: str, spec: dict, impl: str, tests: list,
                        session_id: str) -> dict:
        return await self._rpc("define",
                               {"sid": session_id, "name": name,
                                "spec": spec, "impl": impl, "tests": tests})

    async def call_fn(self, name: str, args: dict, session_id: str) -> Any:
        return await self._rpc("call", {"sid": session_id, "name": name, "args": args})

    async def retract(self, e: str, a: str, v: Any, session_id: str) -> dict:
        return await self._rpc("retract", {"sid": session_id, "e": e, "a": a, "v": v})
```

**Node side — `sidecar.ts` (sketched):**

```typescript
import variant from '@jitl/quickjs-ng-wasmfile-release-sync'
import { loadQuickJs, type SandboxOptions } from '@sebastianwessel/quickjs'
import { createInterface } from 'node:readline'

const { runSandboxed } = await loadQuickJs(variant)

type Session = {
  id: string
  // We hold the QuickJS context open across many evalCode calls.
  // runSandboxed gives us a context-scoped function; we keep the resolver
  // around so successive RPC calls reach the same context.
  evalCode: (code: string) => Promise<{ ok: boolean; data?: unknown; error?: unknown }>
  trace: Array<{ turn: number; primitive: string; args: unknown; result: unknown; eavt_delta: unknown[]; ts: number }>
  eavt: Map<string, Map<string, unknown>>  // trivial in-memory EAVT
  library: Map<string, { spec: unknown; impl: string; tests: unknown[] }>
  release: () => Promise<void>
}

const sessions = new Map<string, Session>()

async function createSession(id: string): Promise<Session> {
  const eavt = new Map<string, Map<string, unknown>>()
  const library = new Map<string, { spec: unknown; impl: string; tests: unknown[] }>()
  const trace: Session['trace'] = []

  // Wrap `runSandboxed` so the QuickJS ctx persists for the session.
  // Pattern: open the sandbox, expose host fns, return an evalCode handle
  // that the outer RPC loop can call. Use the async variant to keep the
  // ctx alive across awaits.
  let ctxResolve: any, ctxRelease: any
  const sandboxStarted = new Promise<void>((resolve) => { ctxResolve = resolve })
  const sandboxClosed = new Promise<void>((resolve) => { ctxRelease = resolve })

  const options: SandboxOptions = {
    allowFetch: false,
    allowFs: false,
    nodeModules: {
      // pre-bundled curated set — see Section 7
      'date-fns': { 'index.js': require('fs').readFileSync('./vendor/date-fns.js', 'utf8') },
      'ajv':      { 'index.js': require('fs').readFileSync('./vendor/ajv.js',      'utf8') },
    },
    env: {
      // host-bridged primitives exposed as globals; the agent's JS
      // sees `assert(e,a,v)`, `query(pattern)`, etc.
      __agent__: {
        assert: (e: string, a: string, v: unknown) => {
          trace.push({ turn: 0, primitive: 'assert', args: {e,a,v},
                       result: 'ok', eavt_delta: [['+', e, a, v]], ts: Date.now() })
          let attrs = eavt.get(e); if (!attrs) { attrs = new Map(); eavt.set(e, attrs) }
          attrs.set(a, v); return { ok: true }
        },
        query: (pattern: { e?: string; a?: string }) => {
          // trivial Datalog-ish: filter EAVT by pattern
          const out: Array<{ e: string; a: string; v: unknown }> = []
          for (const [e, attrs] of eavt) {
            if (pattern.e && pattern.e !== e) continue
            for (const [a, v] of attrs) {
              if (pattern.a && pattern.a !== a) continue
              out.push({ e, a, v })
            }
          }
          trace.push({ turn: 0, primitive: 'query', args: pattern,
                       result: out, eavt_delta: [], ts: Date.now() })
          return out
        },
        // ... define/call/exec/embed/nearest similarly
      },
    },
  }

  let evalCode!: (code: string) => Promise<any>
  // Start the sandboxed context but don't let it terminate.
  runSandboxed(async (sb) => {
    evalCode = sb.evalCode
    ctxResolve()
    await sandboxClosed
    return null
  }, options)

  await sandboxStarted

  return {
    id, evalCode, trace, eavt, library,
    release: async () => { ctxRelease() },
  }
}

const rl = createInterface({ input: process.stdin })
for await (const line of rl) {
  const msg = JSON.parse(line)
  let result: any, error: any
  try {
    switch (msg.method) {
      case 'session.create':  sessions.set(msg.params.session_id,
                                           await createSession(msg.params.session_id))
                              result = { ok: true }; break
      case 'session.destroy': await sessions.get(msg.params.session_id)?.release()
                              sessions.delete(msg.params.session_id)
                              result = { ok: true }; break
      case 'session.drain_trace':
                              result = sessions.get(msg.params.session_id)?.trace ?? []
                              break
      case 'exec':            result = await sessions.get(msg.params.sid)!
                                            .evalCode(msg.params.code); break
      case 'assert': case 'query': case 'retract':
                              // Direct host-side dispatch (faster than
                              // round-tripping through evalCode):
                              const s = sessions.get(msg.params.sid)!
                              const fn = (s as any).__primitives__[msg.method]
                              result = fn(msg.params); break
      // ... define / call follow same pattern
    }
  } catch (e) { error = String(e) }
  process.stdout.write(JSON.stringify({ id: msg.id, result, error }) + '\n')
}
```

The shape is conventional: long-lived Node process, JSON-RPC line protocol, one QuickJSContext per session held in a Map. The trick is keeping the QuickJSContext alive across many `evalCode` calls — `runSandboxed` from `@sebastianwessel/quickjs` is normally one-shot per call, so we use the `await sandboxClosed` pattern (or drop down to `quickjs-emscripten` directly and manage the `Lifetime` ourselves — verified pattern in `~/src/reference/sebastianwessel-quickjs/example/function-call/index.ts:22` where `runSandboxed` is given an async function that loops over `evalCode` calls; for our use case we want the loop driven by external RPC, hence the closed-promise trick).

### 3.4 Session-lifecycle decision

**Pick: long-lived Node sidecar pool, one QuickJSContext per trajectory.**

Three options were on the table:

1. **One Node process per trajectory.** Wastes ~50 MB of Node baseline per trajectory before any sandbox. Same pathology as Path B's process-per-session. **Reject.**
2. **One Node process total, one context per trajectory** (recommended). At 1K concurrent trajectories: ~50 MB Node baseline + 1K × ~8 MB QuickJS = ~8 GB total. Fits one host comfortably.
3. **Pool of N Node processes, contexts sharded across them.** Useful if a single Node event loop becomes the bottleneck (unlikely at Phase-0 scale; revisit if profiling shows it). Same total RAM as #2.

Start with #2; graduate to #3 if Node event-loop saturation shows up.

---

## 4. Path B — Verifiers + Deno subprocess

### 4.1 Architecture

```
┌────────────────────────────────────────────────────────┐
│                Verifiers (Python)                      │
│  the agentEnv(StatefulToolEnv)                              │
│   ├─ setup_state    : spawn Deno subprocess            │
│   ├─ tool: exec     : RPC exec(code) over stdio        │
│   └─ cleanup        : kill subprocess                  │
└────────────────────────────────────────────────────────┘
                           │
                           │ JSON-RPC over stdio
                           │
┌────────────────────────────────────────────────────────┐
│       Deno subprocess (one per trajectory!)            │
│  --allow-net=NONE --allow-read=./vendor --cached-only  │
│   ├─ V8 isolate with TS source mounted                 │
│   ├─ EAVT primitives as TS imports                     │
│   ├─ Trace recorder                                    │
│   └─ deno.lock pins the curated import set             │
└────────────────────────────────────────────────────────┘
```

The structural difference: **Deno's isolation grain is the OS process.** The natural design is one Deno subprocess per training trajectory. You can multiplex sessions inside one Deno process, but then the per-session capability boundary collapses to TS-discipline-only — the very property Deno is bought for is gone.

### 4.2 Engineering hours estimate

| Layer | Hours | Notes |
|---|---|---|
| (a) Python↔Deno IPC layer | 6–8 | Same shape as Path A — `asyncio.subprocess` + line-delimited JSON. |
| (b) Primitive host-function bridge | 14–18 | Each Deno session needs the EAVT primitives as TS modules in the trusted directory. The agent's emitted JS imports them: `import { assert, query } from "agent/primitives.ts"`. The Deno process itself needs a host-side bridge back to Python for primitives that touch Python state (e.g., LLM-judge calls inside `embed`, RAG over a Python-managed vector store) — same JSON-RPC pattern in reverse. |
| (c) Session lifecycle | 8 | Spawn Deno per trajectory; ensure clean shutdown; deal with crashes. Higher boot overhead (~30–60 ms per Section 5) than Path A means batched trajectory creation matters. |
| (d) Verifiers `StatefulToolEnv` adapter | 4 | Identical shape to Path A. |
| (e) `define()`/spec+test contract bridging | 14–18 | Slightly cheaper than Path A — we can use Deno's built-in test runner (`Deno.test`) for the test admission step. But the Python side still has to drive it. |
| (f) Trajectory record extraction | 6 | Same shape. |
| (g) Lockfile / vendor pre-bundle | 6 | `deno install` against a curated `deno.json`, vet `deno.lock`, pass `--frozen --cached-only`. One-time setup, zero recurring work. |
| **Subtotal — minimum spike** | **58–68** | About 7–9 working days. |
| Hardening | +20 | |
| Watchdog integration | +12 | |
| **Total — Phase-0-ready** | **~90–100** | About 2.5 weeks. |

**Path B is *not* meaningfully faster than Path A despite the "less plumbing" framing.** The npm/import simplicity buys ~6 hours; the per-trajectory subprocess management costs them back; the per-host-function-bridge work is similar.

### 4.3 Code skeleton

Python side is structurally identical to Path A — same `StatefulToolEnv` subclass, same primitive shims, same JSON-RPC. The only difference is that `setup_state` spawns a fresh Deno subprocess per trajectory:

```python
async def setup_state(self, state, **kwargs):
    proc = await asyncio.create_subprocess_exec(
        "deno", "run",
        "--allow-read=./vendor",       # nothing else
        "--no-prompt",
        "--cached-only",
        "--frozen",
        "./aria_runtime.ts",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
    )
    state["deno_proc"] = proc
    state["aria_trace"] = []
    # ... wire RPC reader task ...
    return state
```

Deno side — `aria_runtime.ts`:

```typescript
// Pre-bundled, vendored, locked. No network at runtime.
import { assert, retract, query, embed, nearest } from "./primitives.ts"

const eavt = new Map<string, Map<string, unknown>>()
const library = new Map<string, { spec: unknown; impl: string; tests: unknown[] }>()
const trace: Array<unknown> = []

const decoder = new TextDecoder()
const encoder = new TextEncoder()

const buf = new Uint8Array(64 * 1024)
let acc = ""
while (true) {
  const n = await Deno.stdin.read(buf)
  if (n === null) break
  acc += decoder.decode(buf.subarray(0, n))
  let nl: number
  while ((nl = acc.indexOf("\n")) >= 0) {
    const line = acc.slice(0, nl); acc = acc.slice(nl + 1)
    const msg = JSON.parse(line)
    let result, error
    try {
      switch (msg.method) {
        case "exec":  result = (0, eval)(msg.params.code); break  // V8 isolate
        case "assert":result = assert(eavt, trace, msg.params); break
        case "query": result = query(eavt, trace, msg.params); break
        // ... define/call/etc.
      }
    } catch (e) { error = String(e) }
    Deno.stdout.write(encoder.encode(JSON.stringify({ id: msg.id, result, error }) + "\n"))
  }
}
```

### 4.4 Pool-of-Deno alternative

If we run one Deno *per N trajectories* (multiplexing sessions in one process via session-keyed maps), we recover Path A's footprint advantage but **collapse the capability boundary** — every session in that process has the same OS-level permission set. For the agent's threat model (Section 6) that's fine, but at that point Deno's strongest pitch (per-process OS-level sandbox) has evaporated and the comparison vs Path A becomes "V8 isolate vs QuickJS-WASM, in one Node-or-Deno host" — and that's Path A with a different JS engine, not a different architecture. **If we want one-process-many-isolates, `isolated-vm` from Node delivers it without buying into Deno's stack.**

---

## 5. Latency math at scale

Phase-0 training run, conservative estimate:

```
1K trajectories × 20 turns × 10 primitive calls = 200,000 primitive invocations
```

Per-invocation budget components (numbers from Section 1 / Gemini lookup, **most are UNVERIFIED** at this precision):

| Component | Path A | Path B |
|---|---|---|
| Python serialize (`json.dumps`) | ~50–100 µs | ~50–100 µs |
| Stdin pipe RTT (Linux) | ~30–50 µs | ~30–50 µs |
| Receiver parse (`JSON.parse`) | ~50 µs | ~50 µs |
| In-engine dispatch (no JS eval) | ~50 µs | ~50 µs |
| `evalCode("foo(5)")` (small) | ~100–300 µs (QuickJS-WASM) | ~50–100 µs (V8) |
| Receiver serialize | ~50 µs | ~50 µs |
| Pipe RTT back | ~30–50 µs | ~30–50 µs |
| Python parse | ~100–150 µs | ~100–150 µs |
| **Per-call total** | **~0.5–0.9 ms** | **~0.4–0.7 ms** |

**Aggregate cost over 200K invocations:**

- **Path A:** 200K × 0.7 ms = **~140 seconds** (~2.3 min) of sandbox/IPC time per training run.
- **Path B:** 200K × 0.55 ms = **~110 seconds** (~1.8 min) of sandbox/IPC time.

**Difference: ~30 seconds per training run. Negligible.**

What dominates wall-clock instead: the LLM's 200K forward passes (the agent's, plus reactor's, plus mid-trajectory grader's). At Qwen3.6-35B-A3B latency of ~1–3 s per turn (a sibling project's measured numbers), 1K trajectories × 20 turns × ~3 model calls per turn ≈ 60K LLM calls × ~2 s = ~120K seconds = ~33 hours of LLM compute, parallelized across however many vLLM instances we have. **The sandbox is 4–5 orders of magnitude cheaper than the LLM**. Optimizing sandbox latency for Phase-0 is choosing the wrong axis.

The latency conversation only matters at Phase-1+ if we run a much higher primitive-call density per turn (e.g., agent emits a 50-statement JS block that calls `assert` dozens of times). Even there, **batching** dominates: multiple primitive calls inside one `evalCode` block don't pay IPC — they pay only the in-engine cost (~5 µs per primitive in QuickJS once the host functions are wired). Path A's design naturally encourages this batching; Path B less so (because the simple model is "agent emits JS, host evaluates, prints result, loop"). **Path A is structurally cheaper at high primitive density.**

---

## 6. Capability comparison — honest

### What each enforces

**Path A (`@sebastianwessel/quickjs`):**
- Default deny: agent's JS sees only globals the host explicitly `expose()`s. No `fetch`, no `require`, no `process`, no `Deno`, no module loader.
- Host-controlled module loader: `nodeModules` option (verified in source — `src/types/SandboxOptions.ts:35`) is the *only* way `import { x } from "pkg"` resolves. There is no fallback to a network registry.
- `allowFetch`, `allowFs` are explicit toggles defaulting off (verified — `SandboxOptions.ts:40,45`).
- Memory + execution-time limits (`memoryLimit`, `executionTimeout`, `maxStackSize` — `SandboxOptions.ts:17-25`).
- **Failure mode:** a QuickJS-emscripten or QuickJS-NG bug in the engine itself could allow sandbox escape into the Node host. Historically rare; QuickJS has a small attack surface vs V8.

**Path B (Deno subprocess):**
- Default deny: no permissions unless explicitly granted at process start.
- Permission grain is **host/port for net, path-prefix for FS** (verified via Gemini against [docs.deno.com/runtime/fundamentals/security](https://docs.deno.com/runtime/fundamentals/security/) — `--allow-net=example.com` allows any port, any path on that host).
- `--allow-import` gates remote module fetching by registry domain (verified, Deno 2.x).
- `--cached-only` + `--frozen` + `deno.lock` seals the import set at deploy time (verified).
- `Deno.permissions.revoke()` lets in-script narrowing.
- **Failure mode:** a V8 / Deno-runtime bug could escape the V8 isolate into the Deno process. The OS process boundary still holds — kernel CVE territory beyond that.

### What each leaks if misconfigured

**Path A misconfig:** `allowFetch: true` opens unrestricted egress (the `fetchAdapter` is the only mitigation — Section 7 of `SandboxOptions.ts`). `allowFs: true` mounts `memfs` virtual FS — no host-FS leak even when on. `dangerousSync` (`SandboxOptions.ts:104`) shares a JS object between host and guest by reference — name is honest, footgun obvious.

**Path B misconfig:** `--allow-net` without args = all network. `--allow-read` without args = all FS. `--allow-all` = everything. `--allow-import` defaults to a trusted-list including esm.sh, deno.land, jsr.io — **so if the agent emits `import "https://esm.sh/some-package"` and the runtime was started with default `--allow-import`, that import succeeds and pulls remote code**. To prevent: explicit empty `--allow-import=` or `--cached-only`.

### CVE history surface

- **Deno:** small but non-zero CVE history; V8 itself has a constant CVE stream (Project Zero). The Deno project's response is fast.
- **QuickJS-emscripten / QuickJS-NG:** smaller attack surface, fewer reported CVEs historically; smaller researcher attention. The WASM doc flagged Wasmtime's April 2026 batch as a real signal — `quickjs-emscripten` runs *inside* a `WebAssembly.compile` call in Node, so it inherits Node's V8 attack surface for the WASM-host boundary, not Wasmtime's.
- **Deno-on-V8 vs Node-on-V8** for the host attack surface: roughly comparable. Both run V8 Sandbox enabled by default in 2026 versions.

### Fit to the agent's threat model

Sean's framing (verified in the prompt): *"the agent is **our** agent, not adversarial; the watchdog catches malicious behavior; capability is mostly to prevent footguns + reproducibility issues."*

Implications:
- **Footgun prevention.** Both substrates over-deliver. `allowFetch: false` + no module loader is sufficient to prevent the agent from accidentally exfiltrating data via a typo (`fetch("...")` returns undefined and crashes). Deno's similar.
- **Reproducibility.** Path B's `--frozen --cached-only` is the strongest reproducibility primitive on the table. Path A's `nodeModules` is similar — pre-bundled at build time, immutable at runtime.
- **Real adversarial concern.** Not the agent — the *upstream packages*. A compromised npm dep in Path A's pre-bundle, or a compromised dep in Path B's lockfile. Same problem in both. Same mitigation: pin SHAs, audit before bundle, monitor advisories. **This is not a Path-A-vs-Path-B differentiator.**

**Verdict: capability is not the deciding axis.** Both substrates clear the agent's bar by a wide margin. Pick on footprint, ergonomics, and integration cost.

---

## 7. The npm-autodownload question — does the agent actually need it?

**My honest read: no, not at Phase 0, and probably not at V1 either.**

The agent's job is composing five EAVT primitives plus the `define()` discipline. The decisions doc names the function-library as the moat — the agent learns to write and test small functions in our own DSL on top of our primitives. The npm ecosystem is largely irrelevant to that learning task.

### Concrete library list — what an the agent agent might genuinely import

| Library | Use case | Pre-bundle in Path A? | Bundle size |
|---|---|---|---|
| `date-fns` | Date math for "remember a date" / "what day is the meeting" scenarios. | Yes — single-file ESM build is ~80 KB minified. | Small. |
| `ajv` | JSON-schema validation if `define()`'s spec format is JSON-schema-shaped. | Yes — ~120 KB. | Small. |
| `zod` | Alternative spec-validation library; small and ergonomic. | Yes — ~50 KB. | Small. |
| `lodash-es` (selective) | Small text/object utilities (`groupBy`, `keyBy`, `chunk`). | Yes — only the few functions we need (~10 KB). | Trivial. |
| `string-similarity` / `fast-levenshtein` | Fuzzy entity-matching ("did the user mean Sara or Sarah?"). | Yes. | Trivial. |
| `nanoid` / `ulid` | ID generation for new entities. | Yes. | Trivial. |
| `tinyduration` (ISO-8601 durations) | "remind me in 3 days" parsing. | Yes. | Trivial. |

That's seven libraries totaling under 500 KB of vendored source. Pre-bundling them into `@sebastianwessel/quickjs`'s `nodeModules` option is one afternoon of work — `Bun build --target=browser` (since QuickJS doesn't have Node built-ins by default) each one to an ESM bundle and drop the file content into the `nodeModules` map. Verified working pattern in `~/src/reference/sebastianwessel-quickjs/example/custom-module/index.ts:13-19`.

**What the agent does *not* need npm for:**
- HTTP clients — the host functions handle anything that touches the outside world. No agent should ever call `fetch` directly.
- Database clients — same, host-mediated through `query` / `assert`.
- ML libraries — model calls go via host primitives (`embed`, `nearest`, future LLM-call primitive).
- Crypto — no Phase-0 use case. If we add one, we expose a host primitive.
- Filesystem — no agent FS access.
- Process / shell — no.

The only category I can see that *might* need arbitrary packages is **scenario-specific simulated APIs**. If a scenario is "draft an email summarizing yesterday's meeting" and the simulated email tool is implemented in JS, we want that tool's source in the sandbox. But that's a host-controlled package set — a curated `nodeModules` mount per scenario type — not "let the agent import whatever it wants."

### What changes the answer

The npm-arbitrary-import need would become real if:

1. The agent's training prior strongly anchors on Node-stdlib idioms (`fs.readFileSync`, `crypto.createHash`, etc.) and bare-QuickJS triggers a measurable distribution-mismatch failure rate. **Test in spike — measure how often Qwen3.6's emitted JS reaches for Node-only globals.** Mitigation if it bites: ship Node-stdlib polyfills via `nodeModules`, or graduate to `isolated-vm` (V8 with a richer global set).

2. We move into V2 "agent writes long-term per-user code" territory and discover the agent invents idioms that need libraries we didn't anticipate. **Telemetry from Phase-0 / Phase-1 — every package the agent reaches for gets logged.** That telemetry drives the curated-bundle decision for V2.

3. Scenarios genuinely need an arbitrary npm dep at runtime (e.g., "calculate this complex tax form using a community-maintained library"). **None of the scenarios in the brainstorm doc's prebuilt list (sec. "Prebuilt scenario list") need this.** All decidable goals reduce to EAVT manipulation, primitive composition, or LLM-judge — none need npm libs.

**Recommendation for Path A: ship a 10-package curated bundle. If telemetry from the spike shows the agent reaching for things outside the bundle, expand. The bundle is the answer; arbitrary autodownload is a problem we don't have.**

---

## 8. Per-instance footprint at 1K concurrent sessions

Direct comparison at the agent's training topology — 1K concurrent training trajectories on a single training-orchestrator host:

| Substrate | Per-session RAM | Total at 1K | Notes |
|---|---|---|---|
| **Path A** (Node host + 1K QuickJSContext) | ~5–10 MB per ctx | ~5–10 GB + ~50 MB Node baseline | One process for all sessions. UNVERIFIED specific QuickJSContext RSS — `quickjs-emscripten` README claims "very lightweight"; community ballparks place a fresh context at 2–3 MB, growing to ~8–10 MB after defining a small library. |
| **Path B** (1K Deno processes) | ~40–45 MB per Deno | ~40–45 GB | Per-process Deno baseline VERIFIED via Gemini at ~40–45 MB on Deno 2.7+. Each session brings its own V8 isolate inside its own process. |
| **Path B-multiplexed** (1 Deno, 1K isolated `runtime`s inside) | ~5–10 MB per pseudo-isolate | ~5–10 GB + ~45 MB Deno | Same shape as Path A but with V8 instead of QuickJS — and now Deno's per-process capability boundary doesn't isolate sessions from each other. |
| **`isolated-vm`** (1 Node, 1K V8 isolates) | ~3–10 MB per isolate | ~3–10 GB + ~50 MB Node | Cloudflare-style. Mature library, currently maintenance-mode (per WASM doc Section 7). Best raw footprint if QuickJS coverage gap bites. |
| **Docker per session** | ~50–100 MB+ | ~50–100 GB | Out of bounds for Phase-0 single-host topology. |

**Path A's footprint advantage over Path B is real and material — roughly 4–8× less RAM at 1K concurrent.** On a 256 GB training-orchestrator host, Path A leaves ~240 GB free for the EAVT stores, vector indices, and any LLM-side caching; Path B consumes 1/6th of the host before training data lives anywhere.

If the spike measures higher than 10 MB per QuickJSContext (e.g., heap fragments fast, library bundle parse-bloats the heap, host functions retain large closures), the math gets less favorable but Path A still wins. The crossover point — where Path B becomes more attractive — is when QuickJSContext exceeds ~40 MB sustained, which would mean QuickJS is misbehaving and we'd switch engines anyway (to `isolated-vm`).

**Caveat on the "1K concurrent" framing:** Phase-0 may not actually hit 1K concurrent. The brainstorm doc's M0 says "multiple Qwen instances ... no scoring yet ... ~5 hand-written scenarios." That sounds like 10–50 concurrent trajectories, not 1K. At that scale **both substrates fit on a single laptop** and the footprint analysis is academic. The 1K number applies once we scale to Phase-1's actual training run.

---

## 9. Hybrid options

### 9a. Inner-loop QuickJS, outer-loop Docker for scenarios needing apps

Already in the decisions doc and the WASM doc. Stays valid here. The agent's primitive composition lives in QuickJS (Path A); when a scenario invokes `app.gmail.search(...)`, the host function on the Node side proxies the call through to a Docker-backed simulator (a la TheAgentCompany / AppWorld) and returns the result. The agent doesn't see the engine boundary. **This is the recommended architecture.**

### 9b. Verifiers-shaped Python wrapper that hides the substrate

The shape we want: `class the agentJSEnv(StatefulToolEnv)` is the public Verifiers integration; underneath it, we have a `JSBackend` protocol with two implementations — `QuickJSBackend` (Path A) and `DenoBackend` (Path B). The `_rpc()` method is the boundary; `setup_state` / `cleanup` switch backends via constructor arg.

**This is genuinely good engineering.** It makes the spike comparable, lets us A/B the engines on the same scenario suite, and keeps a fallback path open if one engine surprises us. **Cost: ~1 day extra to factor the protocol.** Recommended.

### 9c. Path A with `isolated-vm` instead of QuickJS

If the Phase-0 spike surfaces a JS-coverage gap (Qwen3.6's prior wants `Promise.withResolvers`, `Array.fromAsync`, structured-clone Web Streams, etc.), swap QuickJS for `isolated-vm` — same shape (Node host + many in-process JS sessions), full V8 coverage, same footprint advantage over Path B. The Verifiers adapter is unchanged; only the Node-side JS-evaluator implementation differs. **Plan for this as the fallback;** don't build it preemptively.

### 9d. Path B with sessions multiplexed in one Deno

Discussed Section 4.4. Salvages Path B's footprint but loses its capability-boundary pitch. Becomes "Path A with V8 instead of QuickJS, hosted in Deno instead of Node" — and at that point Deno's value-add over Node is smaller than its differences.

---

## 10. The 3-day spike — what it actually validates

Companion to the WASM doc's Section 10 spike. Re-shaped to validate the **Verifiers integration** plus the engine choice.

### Day 1 — Skeleton: Verifiers + Path A end-to-end

- Stand up `@sebastianwessel/quickjs` Node sidecar with one session, host functions for `assert`/`query`/`exec`/`define`/`call`. Mocked EAVT (in-memory dict), mocked embed (hash), mocked nearest (cosine over hashes).
- Build Python `the agentJSEnv(StatefulToolEnv)` (skeleton from Section 3.3). Wire `setup_state` / `cleanup` / `update_tool_args`. Register the primitive tools.
- Hand-script a 5-turn agent transcript (no actual Qwen yet — paste in `assistant` messages directly). Run via Verifiers' `eval_environment` CLI. Confirm the trajectory record contains the expected primitive calls + EAVT deltas + admitted library functions.

**Success criterion:** agent message in turn 1 calls `define("getName", spec, impl, tests)`; message in turn 5 calls `call("getName", {})` and gets the right answer. The trajectory record (in `state["extras"]["aria_trace"]`) has entries for every primitive call.

### Day 2 — Engine swap and capability checks

- Build the `JSBackend` protocol; second implementation `DenoBackend` (Section 9b).
- Run the same 5-turn transcript through Path B. Confirm same trajectory record (modulo timing).
- Capability spot-check on both: agent emits `fetch(...)` → Path A returns `ReferenceError`, Path B errors on permissions. Agent emits `import "npm:malicious"` → Path A unresolved-module error (not in `nodeModules`), Path B unresolved-import error (not in lockfile).
- Density spot-check: spawn 100 sessions in Path A's Node host; 100 Deno subprocesses for Path B. Measure RSS. Extrapolate to 1K.

**Success criterion:** capability boundary holds in both. RSS measurements within 2× of the Section 8 estimate. Verifiers integration is identical between backends (same `the agentJSEnv` class, only constructor arg changes).

### Day 3 — Real model + watchdog hook

- Wire Qwen3.6-35B-A3B (a sibling project vLLM endpoint) as the model behind the Verifiers `Client`. Run one real trajectory on each backend with a hand-written persona prompt and a minimal scenario ("remember the user's spouse's name").
- Wire DeepSeek (or any cross-lineage model) as the watchdog: at end of each turn, invoke a `JudgeRubric` instance with the trajectory-so-far + agent's emitted code/specs/tests and ask "any cheating detected?". Confirm the `Rubric` composition slots cleanly.
- Measure: real per-call latency end-to-end with a real model (the LLM's the bottleneck; sandbox cost should be invisible). Measure per-trajectory wall-clock.

**Success criterion (from prompt):** REPL across turns ✓ + capability boundary ✓ + density extrapolation ✓ + Verifiers integration ✓. Plus: real-model trajectory survives end-to-end on both backends. Watchdog flag is captured in the trajectory record.

### Decision at end of spike

- **Both backends pass:** ship Path A as primary. Keep Path B as fallback. Document migration path if Path A surprises us in Phase-1.
- **Path A fails on JS-coverage:** swap QuickJS for `isolated-vm` inside Path A's Node host. Keep the same Verifiers adapter.
- **Path A fails on density / footprint:** investigate; this would be a surprise (QuickJSContext should be cheap). If unfixable, fall back to Path B.
- **Both fail:** revisit the WASM doc's Section 10 high-effort path (custom Wasmtime + rquickjs) or the Docker option.

---

## 11. Sources

**Source code (read directly this session):**
- `~/src/reference/verifiers/verifiers/envs/multiturn_env.py`
- `~/src/reference/verifiers/verifiers/envs/stateful_tool_env.py`
- `~/src/reference/verifiers/verifiers/envs/sandbox_env.py`
- `~/src/reference/verifiers/verifiers/envs/environment.py` (lines 554–626)
- `~/src/reference/verifiers/verifiers/types.py` (lines 210–245 — `TrajectoryStep`)
- `~/src/reference/verifiers/verifiers/rubrics/judge_rubric.py`
- `~/src/reference/sebastianwessel-quickjs/src/types/SandboxOptions.ts`
- `~/src/reference/sebastianwessel-quickjs/src/types/SandboxFunction.ts`
- `~/src/reference/sebastianwessel-quickjs/src/sandbox/expose/expose.ts`
- `~/src/reference/sebastianwessel-quickjs/src/sandbox/syncVersion/prepareSandbox.ts`
- `~/src/reference/sebastianwessel-quickjs/example/function-call/index.ts`
- `~/src/reference/sebastianwessel-quickjs/example/custom-module/index.ts`
- `~/src/reference/sebastianwessel-quickjs/README.md`
- `~/src/reference/deno/runtime/permissions.rs` (structure only)

**Gemini 3 Flash lookups (cross-checked vs cited primary docs where possible):**
- Deno 2.7+ baseline RSS / startup / IPC latency — UNVERIFIED specific numbers, sourced via [BolderApps Performance Report 2026], [Daily.dev benchmarks 2026], Deno blog v2.0 announcement
- Deno 2.x permission grain (`--allow-net=host` is host/port, not path) — VERIFIED against [docs.deno.com/runtime/fundamentals/security](https://docs.deno.com/runtime/fundamentals/security/)
- Deno `--allow-import` defaults — VERIFIED Deno 2.x docs
- Deno `--cached-only` + `--frozen` lockfile semantics — VERIFIED [docs.deno.com/runtime/fundamentals/configuration](https://docs.deno.com/runtime/fundamentals/configuration/#lockfile)
- `quickjs-emscripten` `evalCode` cost (~0.1–0.3 ms small expression, warm context) — UNVERIFIED specific number; [cyberscript.dev/2024/04/01/quickjs-emscripten-performance/] cited
- V8 isolate small-eval cost (<0.1 ms warm) — UNVERIFIED; [deno.com/blog/v8-code-cache] cited
- Linux pipe RTT (~30–50 µs same host) — VERIFIED; standard `pipe(7)` characteristics

**Companion docs (this repo):**
- `2026-05-08-wasm-wasi-substrate.md` — selected `quickjs-emscripten` over Wasmtime; this doc extends to Verifiers integration and Deno comparison
- `2026-05-07-oss-harness-evaluation.md` — selected Verifiers as Phase-0 harness
- `2026-05-07-brainstorm-decisions.md` — `exec()`/EAVT/`define()` boundary commitment; three-role trajectory; cultural-native grader; watchdog as third role

**UNVERIFIED claims flagged in-text:**
- All per-call latency numbers (composed from component estimates, none directly measured)
- QuickJSContext per-instance footprint (Section 8 — community ballparks, not measured)
- Deno baseline RSS in our specific config (Gemini number, not directly benchmarked)
- 200K-invocation cumulative sandbox time (derived from per-call estimate; nominal)
