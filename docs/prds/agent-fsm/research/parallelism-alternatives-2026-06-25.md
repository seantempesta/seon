---
type: research
status: active
tags: [research, agent, flow]
---

# Parallelism + Isolation Alternatives for the CLJS Pod (2026-06-25)

Survey of CURRENT (2025-26) parallelism + fault-isolation options for the
long-lived CLJS/Node pod, evaluated against the worker_threads + terminate
baseline. Problem recap: multiple AI agents share ONE Node event loop; a
synchronous runaway eval locks the WHOLE pod (including the wall-clock
watchdog, which sits on the same blocked thread). We want (a) fault
isolation — a runaway eval freezes only its own unit and is killable — and
ideally (b) real parallelism, all coordinated through the single-writer
datahike store (units never share mutable memory; they `listen!` the tx-log
and push via datastar SSE).

**Two axes, both REQUIRED (owner criterion 2026-06-25) — rank on BOTH:**

- **ISOLATION** — one eval must not lock/crash the others, and a runaway
  must be killable WITHOUT a healthy thread issuing the kill.
- **CAPABILITY-LOADING** — agents `npm install` new packages into the
  cluster and **ALL agents gain those capabilities IN REALTIME, WITHOUT
  RESTARTING**. Node's `require` is already runtime-dynamic (install →
  `require()`, no restart), so a real-Node execution environment gives this
  for free; a pure-WASM sandbox does NOT (no ambient Node — every capability
  must be bridged across the host boundary).

These axes pull in OPPOSITE directions: the strongest thread-free isolation
(WASM) is the weakest at realtime npm capability-loading. That tension
re-orders the ranking below versus a pure-isolation view.

---

## Prior seon art (grounding — read these, don't re-derive)

This is NOT a greenfield question. The repo already contains:

- **`docs/prds/agent-runtime/sci-interrupt-validation-2026-06-21.md`** —
  **Layer 1 is SHIPPED + live-verified.** Agent-authored *interpreted* fns
  run under **SCI** (`seon.render.sci`, flag `SEON_TILE_SCI`, default on); a
  wall-clock `:interrupt-fn` fires at the top of every interpreted `recur`
  and aborts a `(loop [] (recur))` in-process at ~251ms vs a 250ms budget,
  ~0.2ms warm overhead, no worker, DB value passed by reference, uncatchable
  by hostile `catch/finally`. **Residual it does NOT cover:** a native host
  loop (`while(true)` inside a compiled helper) or a native-regex ReDoS — the
  interrupt only fires on *interpreted* entry. Bounding that class needs a
  killable worker/process (the PRD's "Layer 2", deferred). Note: SCI runs
  IN-PROCESS → npm capabilities stay fully DIRECT.
- **`docs/prds/agent-fsm/research/worker-threads-spike-2026-06-25.md`** —
  **the baseline this task references is already MEASURED + positive.** A
  bare worker = **~14ms spawn, ~8MB RSS** (+~17ms/+8MB for the real
  bootstrap ana-caches; full cljs.js compile-state heavier).
  **`worker.terminate()` killed a synchronous `(while true)` in 0.8ms while
  the main heartbeat kept ticking (91/100 beats during the hang).** Warm
  postMessage round-trip ~0.02ms; structured-clone of a ~20KB eval payload
  ~0.05ms. Verdict: "Step-1 eval-offload pencils out"; warm pool of 4-8 is
  affordable, per-agent-worker affordable at tens of agents. It also
  identifies the dominant per-worker cost: the **cljs.js bootstrap
  compile-state** (45 ana-cache files, 2.04MB), not SCI.
- **`docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md`** +
  **`.../verification-cljs-wasm-2026-05-28.md`** — a **full WASM-pod track
  was already explored**: **wasm-rquickjs + wasmtime + Tauri**, a real WASM
  **Component Model** with **WIT-typed imports/exports** where "the host
  decides exactly what `wasi:*` capabilities to grant." This is precisely the
  host-bridged-capability model the owner is asking about — already
  prototyped. BUT the falsification run found hard blockers: `(str 1 2 3)`,
  `defrecord`, `defprotocol`, `defmulti`, `deftype` all fail
  (`clojure is not defined` at macroexpand) **even in the "green" artifact**;
  `(require ...)` of a not-pre-warmed ns **hard-panics the instance**; no
  per-eval timeout (a ReDoS hung 121s); and the **build is non-reproducible
  (a fresh rebuild hangs forever on init)**. cljs.js-in-QuickJS is, as of
  that run, **not viable without substantial fixes**.

The new owner criterion (realtime npm) lands ON TOP of this: the WASM track
already required hand-bridging every capability across WIT, and even getting
the *interpreter itself* to run there is unsolved.

---

## TL;DR — re-ranked with realtime-npm weighted heavily

The capability-loading requirement is close to decisive. It rewards a
**real-Node execution environment** (direct dynamic `require`) and penalizes
a WASM sandbox (every npm fn must be host-bridged). Combined with isolation:

1. **worker_threads (+ tinypool or Comlink) — wins BOTH axes; it is the
   answer for the killable/parallel layer.** ISOLATION: proven preemptive
   kill (`terminate()` in 0.8ms; the watchdog lives on the main thread, not
   the blocked worker). CAPABILITY: a worker is a **real Node environment**;
   `require` resolves from `node_modules` at call time, so a worker can
   `require()` a freshly-installed package **without restart** — npm stays
   DIRECT for all agents (modulo: `require` must run *after* the install; a
   long-lived pooled worker re-requires, it does not snapshot). SCI + shadow
   output run unmodified inside. Cost is exactly what the spike measured and
   accepted. Comlink (~1.1KB) makes the worker call read like a local async
   CLJS fn; tinypool (38KB) gives pooling without Piscina's bulk.

2. **SCI Layer-1 (already shipped) as the in-process first line — keep it.**
   ISOLATION: cures the *interpreted* runaway in-process (the common case)
   at ~0.2ms, no worker, no serialization. CAPABILITY: in-process → npm fully
   DIRECT. It does NOT cover native host loops / ReDoS — that residual is
   exactly what #1 (worker terminate) backstops. **The shipped architecture
   is already the right hybrid: SCI in-process for interpreted evals, a
   killable worker for the native-loop residual.** Nothing newer displaces
   this; it just needs Layer 2 wired in.

3. **isolated-vm — the only "separate-isolate + dynamic host capability
   injection" option, but maintenance-mode + can crash the pod.** ISOLATION:
   real V8 isolates with memory limits + a script `timeout`; BUT it is
   officially in **maintenance mode**, "increasingly unstable due to changes
   in v8," and an OOM/runaway "can, and even commonly, crash the process" —
   that last point partially DEFEATS "freeze only the unit." CAPABILITY:
   host modules can be injected dynamically via `Reference`/`ExternalCopy`
   (`jail.set`) **without restart**, so a new npm package's functions CAN be
   exposed live — but as **manually marshalled references**, not ambient
   `require`. Sweet-spot on paper (isolate isolation + dynamic injection),
   undercut by the crash risk and bus-factor. Fallback, not a spike target.

4. **QuickJS-in-WASM / Wasmtime-fuel — best pure thread-free isolation +
   polyglot, but the realtime-npm requirement and prior seon blockers push
   it to DEFER.** ISOLATION (excellent): QuickJS `setInterruptHandler` +
   `setMemoryLimit` interrupt a runaway **deterministically on the SAME
   thread**, no OS thread, no `terminate()`; Wasmtime **fuel** is fully
   deterministic (same program+fuel → trap at the exact same instruction).
   CAPABILITY (poor for OUR rule): a WASM guest has **NO ambient Node** — no
   `require`, no npm, no native modules. A freshly-installed npm package
   reaches the agent ONLY as a **host-bridged import** (WIT export / host
   callback). See the honest analysis below — for a handful of stable
   capabilities (DB, fetch) host-bridging is *fine and matches the existing
   wire pattern*; for **arbitrary, numerous, realtime-installed npm
   packages it does NOT scale** (you'd hand-bridge each package's API across
   the boundary, and the seon WASM track already shows even cljs.js barely
   runs there + the build is non-reproducible).

**Bottom line:** the shipped **SCI-in-process + killable-worker** hybrid
already satisfies BOTH axes and is the recommended direction; worker_threads
is the right Layer-2 mechanism (and its spike is already green). QuickJS-WASM
is the more *exciting* isolation tech but the realtime-npm criterion + prior
blockers make it the wrong near-term bet.

**Single most surprising/important find:** the question is largely
**already answered in-repo** — Layer 1 (SCI interrupt) ships and the
worker_threads baseline is already measured green (`terminate()` 0.8ms,
~14ms/8MB per worker), while the WASM alternative was explored to the point
of finding cljs.js doesn't run cleanly under QuickJS and the build is
non-reproducible. The *technical* surprise: Wasmtime fuel is fully
deterministic kill with no OS thread — but it's a Rust host-embedder API,
and the seon-side realization (wasm-rquickjs+WIT) is exactly the
host-bridge-every-capability model the npm requirement disfavors.

---

## Survey table (both axes)

| Option | Category | ISOLATION (kill a sync runaway?) | CAPABILITY (realtime npm, no restart?) | Cost | CLJS / shadow fit | Maturity | Verdict |
|---|---|---|---|---|---|---|---|
| **SCI Layer-1** (shipped) | in-process interpreter interrupt | YES for *interpreted* loops; NO for native host loop / ReDoS | DIRECT (in-process require) | ~0.2ms/eval | native; already live | shipped + verified | **Keep — first line** |
| **worker_threads** (raw) | OS-thread parallelism | YES — `terminate()` 0.8ms; watchdog off-thread | DIRECT — worker is real Node, `require` resolves at call time post-install | ~14ms spawn / ~8MB (+bootstrap) | SCI + shadow run as-is | core Node API, stable | **Top pick (Layer 2)** |
| **tinypool** | pool over workers | inherits terminate | DIRECT | 38KB wrapper; pools amortize startup | transparent | active (Vitest's pool) | **Use for pooling** |
| **Piscina** | pool over workers | inherits terminate | DIRECT | ~800KB; more features | transparent | mature | heavier tinypool |
| **workerpool** | pool over workers | inherits terminate | DIRECT | small | transparent | mature | fine alt |
| **Comlink** | proxy-RPC over workers | inherits terminate | DIRECT | ~1.1KB gzip | makes worker calls read like local async CLJS | v4.4.2 (Nov 2024) | **Ergonomic layer of choice** |
| **isolated-vm** | separate V8 isolate | partial — `timeout`+memLimit, but OOM/runaway can crash whole process | host-injected via `Reference`/`ExternalCopy` (no restart) but MANUAL marshal, not ambient require | full isolate | SCI/shadow run inside | **maintenance mode; unstable** | fallback only |
| **QuickJS-emscripten** | WASM JS-engine sandbox | YES — `setInterruptHandler`+memLimit, SAME thread, no OS thread | NO ambient Node — every npm fn host-bridged; prior seon run: cljs.js broken, require panics, build non-reproducible | ~500KB WASM (1MB asyncified) | runs plain JS; interpreter-on-interpreter + known cljs.js blockers | pre-1.0, active | **Defer (npm + prior blockers)** |
| **wasm-rquickjs + wasmtime + WIT** | WASM Component, host grants `wasi:*` | YES (fuel/epoch/interrupt) | NO ambient Node — WIT-typed host bridge per capability | Rust host + build orchestrator | the prior seon WASM track; cljs.js unproven | pre-1.0; prototype archived | **Defer (same)** |
| **Wasmtime/Wasmer fuel** | host-side WASM fuel/epoch | YES — deterministic fuel trap; no OS thread | host-bridged only | fuel instrumentation pricey; epoch ~10% | not Node-resident | mature/GA | host-side track only |
| **node:vm** | context, NOT a sandbox | NO (same thread; sync loop still locks) | DIRECT (same process) | ~free | runs JS | **explicitly not a security boundary; vm2 deprecated/CVEs** | **do not use for untrusted** |
| **Bun** | newer runtime | same worker model | node-compat require, mostly direct | faster startup; **worker/SAB/Atomics partial** | shadow targets Node; compat risk on our exact primitives | 1.2+, prod-used | not worth a swap now |
| **Deno** | newer runtime | Workers = OS thread + V8 isolate + **per-worker permission sandbox** | npm: specifiers, mostly direct + permission-gated | sub-ms isolate context | migration off Node/shadow defaults | mature, strong perms | parked; best perms model if ever rebuilding |
| **missionary** | CLJS async/stream | NO (single-threaded) | n/a | ~free | composes w/ native ^:async/await; backpressured flows | active | **in-unit glue (backpressure)** |
| **promesa** | CLJS promises + CSP | NO (single-threaded) | n/a | ~free | closest to the pod's native ^:async/await | v11+ active | **in-unit glue (promise interop)** |
| **core.async (CLJS)** | CSP | NO (go = state machine, 1 thread) | n/a | ~free | pod is deliberately core.async-FREE | mature | skip (against pod policy) |
| **SharedArrayBuffer + Atomics** | shared mem / signaling | n/a (a primitive) | n/a | zero-copy | cross-worker kill-flag / wait-notify | standard (Bun partial) | **fast abort-signal beside the DB, NOT the data substrate** |

---

## The two axes, made explicit

**ISOLATION** and **CAPABILITY-LOADING** are independent and BOTH required.
Mechanisms separate cleanly:

| | Kills interpreted runaway | Kills native-loop/ReDoS runaway | npm realtime, no restart |
|---|---|---|---|
| SCI Layer-1 (in-proc) | yes (in-process) | no | yes (direct) |
| worker terminate | yes | yes (preemptive) | yes (direct, real Node) |
| isolated-vm | yes (timeout) | yes (timeout) but OOM may crash pod | partial (host-injected, manual) |
| QuickJS-WASM interrupt | yes (same-thread) | yes (same-thread) | no (host-bridged only) |
| Wasmtime fuel | yes | yes (deterministic) | no (host-bridged only) |
| node:vm | no | no | yes (direct, no isolation) |

The winning combination on BOTH axes is **real-Node execution (direct npm) +
a killable boundary (worker terminate)**, backed by **in-process SCI** for
the cheap common case. That is the architecture the repo is already on.

### Why WASM loses the capability axis (honest analysis, as asked)

A WASM guest (QuickJS-in-WASM or a wasm-rquickjs Component) has **no ambient
Node**: no `require`, no `node_modules`, no native addons. So a freshly
`npm install`ed package becomes available to a sandboxed agent ONLY if the
HOST explicitly bridges it across the boundary — concretely:

- **Per-function host callbacks / WIT exports.** The host wraps each fn of
  the npm package as an import the guest can call (QuickJS: register a host
  function handle; wasm-rquickjs: declare it in the **WIT** interface and
  regenerate the Component). The guest calls a thin proxy; the real work runs
  host-side.
- **Cost/complication:** (1) every capability is a hand-written, typed bridge
  — you do not get a package's API "for free" the way `require('x')` gives
  it; (2) realtime install means **regenerating/extending the host bridge at
  runtime**, which for the WIT-Component path means a build step (the prior
  research's "build orchestrator"), i.e. NOT trivially realtime; for raw
  QuickJS you can register host fns at runtime, but you still author the
  marshalling per fn and per value-shape; (3) data crosses as copies/handles
  (no shared object graph), so stateful/streaming npm APIs are awkward.

**Is the host-bridge actually fine because the architecture ALREADY routes
capabilities over the wire?** Partly — and this is the strongest argument
*for* WASM. DB ops already go host→wire-server; a small, STABLE set of
capabilities (DB read/query, fetch, embeddings) bridged once across WIT is
the *same pattern* and is genuinely fine. The mismatch is **scale and
dynamism**: the owner's requirement is "agents install ARBITRARY packages and
ALL agents get them in REALTIME." That is an open-ended, frequently-changing
capability set — exactly what a hand-bridged surface does NOT absorb cheaply.
Bridging the DB once is not bridging an unbounded, agent-chosen npm frontier
on every install. So: host-bridge is fine for the *fixed* capability spine,
and **defeats the purpose for the realtime-arbitrary-npm requirement.** Add
the prior-art blockers (cljs.js's `str`/`defrecord`/`require` broken under
QuickJS, non-reproducible build) and WASM is not the near-term answer — even
though its isolation + polyglot ("run anything we compile to WASM") are the
best in the survey and worth keeping as a *long-horizon* track.

### isolated-vm — the dynamic-injection sweet spot, with a sharp edge

isolated-vm CAN expose host capabilities into an isolate **at runtime
without restart**: you marshal host functions/objects in via `ivm.Reference`
/ `ExternalCopy` and `jail.set(...)`. So when an agent installs a package,
the host can `require()` it (host-side, real Node) and inject the needed
references into existing isolates live — capability-loading without restart,
mediated. That is the one option combining *isolate-grade* isolation with
*dynamic* capability injection. The disqualifiers for OUR "freeze only the
unit" goal: it is in **maintenance mode**, "increasingly unstable" against
new v8, and an OOM/CPU runaway "can, and even commonly, crash the process" —
a guest can take the pod down, which is the opposite of the guarantee we
want. Keep as a documented fallback if worker-per-eval cost ever proves
unacceptable AND we accept the bus-factor/crash risk.

---

## Other candidates (brief)

- **Wasmtime/Wasmer fuel** — the cleanest statement of thread-free
  deterministic kill ("same program + same fuel → interrupted at the same
  location"; epoch variant ~10% slowdown but non-deterministic). It's a Rust
  host-embedder API, so relevant only to the *deferred* host-side
  "CLJS-eval-in-WASM" track (the wasm-rquickjs+wasmtime line already in the
  repo), not a Node-resident drop-in. Same npm-capability limitation as
  QuickJS.
- **Bun** — faster worker startup (~2-4x startup wins) would shave per-worker
  cost, but `worker_threads`/`SharedArrayBuffer`/`Atomics.waitAsync` support
  is *partial/degraded* (the exact primitives our isolation+signaling need),
  and shadow targets Node. Does nothing for isolation. Not worth a runtime
  swap when tinypool already amortizes startup. Re-evaluate only if a
  worker-startup measurement shows it dominant AND Bun's worker support has
  hardened.
- **Deno** — best *isolation model* (real OS-thread workers + V8 isolates +
  a **per-worker permission sandbox** a worker can't escalate), and npm via
  `npm:` specifiers keeps capability-loading mostly direct + permission-gated.
  Cost: leaving Node/shadow defaults is non-trivial. Park as the "if we ever
  rebuild the runtime" option.
- **node:vm** — different *context*, same thread/process, documented escape
  vectors, so **not a security boundary**; a sync loop still locks the pod;
  `vm2` is deprecated with sandbox-escape CVEs. Ruled out for untrusted eval.
- **CLJS-native concurrency (promesa / missionary / core.async)** — all
  SINGLE-THREADED; *coordination, not isolation*; none stops a sync runaway.
  Role = in-unit glue once isolation is solved elsewhere. **promesa** is
  closest to the pod's native `^:async`/`await` (best default, and it's the
  natural glue for awaiting worker/host-callback promises). **missionary**
  adds true backpressured streaming + cancellation (reach for it where a unit
  consumes a tx-log/SSE stream and must apply backpressure — a known gap in
  core.async CLJS). The pod is deliberately **core.async-FREE**; don't
  reintroduce it.
- **SharedArrayBuffer + Atomics** — excellent as a *fast cross-worker signal*
  (a kill-flag the main-thread watchdog flips, a progress counter,
  wait/notify) and zero-copy large-buffer handoff — NOT the data substrate
  (datahike is; units share no mutable memory by design). Use as an instant
  "abort this worker" flag beside the DB. (Bun's `Atomics.waitAsync`/large
  transfers are the partially-supported area — another reason to stay on Node
  for this.)

---

## What to spike next (given BOTH axes)

The worker_threads ISOLATION spike is already **done and green**, and SCI
Layer-1 ships. So the next spikes are about the *capability axis* and wiring
Layer-2 into the live path — NOT re-proving threads, and NOT (yet) QuickJS:

1. **Realtime-npm-into-a-worker spike (the capability claim).** Start a
   pooled worker, `npm install` a package into the cluster's `node_modules`
   at runtime, then have the ALREADY-RUNNING worker `require()` it and call
   it — confirm no restart needed and the resolution picks up the fresh
   install. Also confirm shadow/cljs `js/require` interop reaches it. Decision
   gate: does "install → all agents get it realtime" hold for a pooled worker
   (it should, since `require` resolves at call time), and what's the cache
   gotcha (a worker that already failed-resolved the name caches the miss)?

2. **Wire Layer-2 (worker terminate-on-deadline) into the live eval path.**
   Take the green spike harness and connect it to
   `seon.agent.turn/eval-batch!` so the native-loop/ReDoS residual that SCI
   Layer-1 can't catch is offloaded to a killable worker, terminated on the
   wall-clock deadline. Decision gate: per-agent-worker vs shared pool at our
   agent counts (the spike says pool at hundreds); does the cljs.js
   bootstrap compile-state per worker stay within the measured ~16-25MB.

**Do NOT spike QuickJS-WASM next.** Prior seon research already found cljs.js
broken under QuickJS + a non-reproducible build, and the realtime-npm
requirement makes its capability story expensive. Revisit only as a
long-horizon polyglot/strong-isolation track, after the worker+SCI hybrid is
in production and IF a "run arbitrary compiled-to-WASM guests" need emerges
that outweighs direct npm.

---

## Open calls for the owner

- **Is the immediate ask ISOLATION-only, or also PARALLELISM?** SCI Layer-1
  already cures the common interpreted runaway in-process with npm fully
  direct. If throughput isn't the pain, the remaining work is just wiring the
  killable worker for the native-loop residual — no new tech needed.
- **WASM polyglot vs realtime-npm — which goal wins?** They conflict. WASM
  buys best-in-class isolation + "run anything compiled to WASM," but makes
  every npm capability a host-bridge and (per prior art) doesn't yet run
  cljs.js. If realtime-arbitrary-npm is a hard product requirement, WASM is
  out for the eval path; if the capability spine is actually small+stable,
  the WIT host-bridge is fine and WASM becomes viable — but only after the
  cljs.js-under-QuickJS and non-reproducible-build blockers are fixed.
- **isolated-vm crash risk** — its OOM-can-crash-the-process behavior likely
  disqualifies it for "freeze only the unit"; confirm we rule it out despite
  its unique dynamic-host-injection capability.
- **Runtime swap (Bun/Deno)** — parked. Revisit Deno only if the per-worker
  permission sandbox becomes a hard requirement.

---

## Sources

In-repo prior art:
`docs/prds/agent-runtime/sci-interrupt-validation-2026-06-21.md` ·
`docs/prds/agent-fsm/research/worker-threads-spike-2026-06-25.md` ·
`docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md` ·
`docs/prds/agent-runtime/research/verification-cljs-wasm-2026-05-28.md`

External:

- isolated-vm: [npm](https://www.npmjs.com/package/isolated-vm) ·
  [GitHub (maintenance-mode + OOM-crash + Reference/ExternalCopy injection)](https://github.com/laverdet/isolated-vm) ·
  [running untrusted JS in Node](https://pixeljets.com/blog/executing-untrusted-javascript/)
- quickjs-emscripten: [GitHub](https://github.com/justjake/quickjs-emscripten) ·
  [QuickJSRuntime API (setInterruptHandler/setMemoryLimit)](https://github.com/justjake/quickjs-emscripten/blob/main/doc/quickjs-emscripten-core/classes/QuickJSRuntime.md) ·
  [npm](https://www.npmjs.com/package/quickjs-emscripten) ·
  [OOM-in-vm issue #30](https://github.com/justjake/quickjs-emscripten/issues/30)
- Wasmtime: [Interrupting Execution](https://docs.wasmtime.dev/examples-interrupting-wasm.html) ·
  [Deterministic Execution (fuel)](https://docs.wasmtime.dev/examples-deterministic-wasm-execution.html) ·
  [Config API](https://docs.wasmtime.dev/api/wasmtime/struct.Config.html)
- Worker pools: [tinypool](https://github.com/tinylibs/tinypool) ·
  [Piscina](https://github.com/piscinajs/piscina) ·
  [piscina vs threads vs workerpool](https://npm-compare.com/piscina,threads,workerpool)
- Comlink: [GitHub](https://github.com/googlechromelabs/comlink) ·
  [npm v4.4.2](https://www.npmjs.com/package/comlink) ·
  [Next.js 15 + Comlink (2025)](https://park.is/blog_posts/20250417_nextjs_comlink_examples/)
- worker_threads: [Node.js v26 docs](https://nodejs.org/api/worker_threads.html) ·
  [worker threads problematic but work (Inngest)](https://www.inngest.com/blog/node-worker-threads) ·
  [SAB + Atomics](https://blogtitle.github.io/using-javascript-sharedarraybuffers-and-atomics/)
- Bun: [Bun vs Node 2026 benchmarks (Strapi)](https://strapi.io/blog/bun-vs-nodejs-performance-comparison-guide) ·
  [3 months in production (DEV)](https://dev.to/synsun/bun-vs-nodejs-in-production-what-three-months-of-real-traffic-taught-me-3d96)
- Deno: [Security & permissions](https://docs.deno.com/runtime/fundamentals/security/) ·
  [Web workers](https://docs.deno.com/examples/web_workers/) ·
  [Deno Workers: V8 isolation + permission sandbox (2026)](https://nut-charoenpattanasirikul.medium.com/understanding-deno-workers-v8-isolation-message-passing-and-the-permission-sandbox-0d856f26b2e3)
- CLJS concurrency: [missionary](https://github.com/leonoel/missionary) ·
  [promesa](https://github.com/funcool/promesa) ·
  [CLJS vs core.async backpressure (Szabo)](https://mauricio.szabo.link/blog/2020/06/11/clojurescript-vs-clojure-core-async/)
- shadow-cljs: [GitHub](https://github.com/thheller/shadow-cljs) ·
  [3.4.11 changelog](https://github.com/thheller/shadow-cljs/blob/master/CHANGELOG.md)
