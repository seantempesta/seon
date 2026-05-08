# WASM / WASI as Phase-0 code-execution substrate — deep dive

**Date:** 2026-05-08
**Author:** research agent under Sean's direction
**Question:** Sean asked for an honest evaluation of WASM/WASI as the agent's Phase-0 substrate. Does it actually deliver: lightweight + safe, REPL with persistent state across many calls, capability-based access control over the EAVT primitives, multi-tenant scale at 10K–1M, and compatibility with Qwen3.6's JS+Python coding prior?
**Process:** Gemini 3 Pro web research (two surveys completed), plus direct WebFetch verification on every load-bearing repo / blog / advisory. Numbers below are tagged **VERIFIED** (cite linked) or **UNVERIFIED** (best estimate, flagged).

---

## 1. TL;DR

**WASM is the strategically cleanest substrate, but it is *not* the obvious Phase-0 winner over the boring alternative.** The cleanest WASM stack for the agent's REPL shape is **Wasmtime + a custom Rust module that embeds rquickjs (or wraps Javy's underlying crate) + Wizer pre-init snapshot**, kept alive in a long-lived `Store`. That delivers all four of Sean's requirements — capability sandbox, REPL persistence in JS heap inside linear memory, JS as the agent's emit target, fan-out density. But:

1. **The standard Javy CLI is one-shot** — built as a WASI Command module with a `_start` entry point. It does not give you persistent state across calls. To get REPL behaviour we have to build our own embedding (~a Rust crate plus a custom `.wasm`), not just `javy build`. The widely-cited "Shopify uses Javy" pattern is *also* one-shot per request — Shopify Functions wipes linear memory between events. ([Shopify engineering blog](https://shopify.engineering/javascript-in-webassembly-for-shopify-functions))
2. **The serverless WASM platforms (Fermyon Spin, WasmCloud, Fastly Compute@Edge) are all request-scoped by design.** WasmCloud's docs explicitly say components "only run when invoked" and delegate state to providers ([wasmcloud.com/docs/concepts/components](https://wasmcloud.com/docs/concepts/components)). For the agent's REPL we are *not* riding any of these platforms — we are running raw Wasmtime with our own session-state plumbing.
3. **Wasmtime had multiple critical CVEs in April 2026**, including two aarch64 sandbox-escape bugs (CVE-2026-34971 Cranelift, CVE-2026-34987 Winch) and a pooling-allocator data leak (CVE-2026-34988). Wasmtime's security posture is excellent — fast disclosure, fast fixes, public advisories — but the "WASM is mathematically sandboxed" framing is wrong when the actual JIT is what gets compromised. ([advisories](https://github.com/advisories?query=wasmtime))
4. **The boring alternative is `quickjs-emscripten` from a Node host (or `rquickjs` from a Rust host).** It already does exactly what we want — `context.evalCode("function foo(x){return x+1}")` then later `context.evalCode("foo(5)")` returns 6 from the persistent QuickJS heap. ([quickjs-emscripten README](https://github.com/justjake/quickjs-emscripten)) No WASM-build pipeline, no Wizer step, no Component Model dance. It loses WASM's mathematical-memory boundary but keeps QuickJS's capability-by-import model and runs fast enough for thousands of trajectories.

**Recommendation: WASM is *secondary*, not primary, for Phase-0.** Build the spike on `quickjs-emscripten` from a Rust or Node host with capability-restricted host functions. The architecture is identical to the WASM version (host-controlled imports, persistent QuickJS heap, agent-defined functions live in the JS heap). If/when the V2 multi-tenant runtime forces stronger isolation, port the QuickJS layer to `quickjs.wasm` running inside Wasmtime — same API, harder boundary. **The contract Sean already committed to (`exec` + EAVT primitives + `define`) abstracts this cleanly: engine swap is a 1-week refactor, not an architectural redesign.**

**Reconciliation with the Gemini-3-Pro recommendation.** Gemini Q1's verdict was "Raw Wasmtime + Wizer, or Extism (backed by Wasmtime)." Gemini Q3's verdict was "Deno Subhosting, or native QuickJS embedded in Rust." I land closer to Q3 because Q1 isn't accounting for Phase-0 build cost — building the custom rquickjs `.wasm` plus Wizer pipeline plus Linker capability wiring is ~10× the effort of pulling `quickjs-emscripten` off npm, and Phase-0's purpose is shipping a training-loop substrate fast enough to run trajectories before the next the client lead sync. **Extism is the strongest WASM-stack compromise** — it gives us persistent linear memory across calls without us having to write the custom-embed-and-Wizer plumbing ourselves, because Extism's Plugin abstraction does it. If the spike picks WASM after all (Section 10), Extism is where to start, not raw Wasmtime.

The one scenario where WASM wins Phase-0 outright is if the agent ever needs to run *non-JS* code — a scenario already deferred. Until then, WASM's win is "future-proof portability and a stronger sandbox," neither of which is binding in Phase-0.

---

## 2. Verified numbers table

| Runtime | Latest stable (May 2026) | Per-instance memory | Boot time | License | JS support | Capability model | Suitable for REPL? |
|---|---|---|---|---|---|---|---|
| **Wasmtime** | [v44.0.1, 2026-04-30](https://github.com/bytecodealliance/wasmtime/releases/tag/v44.0.1) (LTS line at v36.0.9, 2026-05-05) | ~20–25 MB runtime RSS (Gemini cite, Arm benchmarks); per-instance marginal cost ≈ guest linear memory | ~5 µs with Pooling Allocator + `InstancePre` (Gemini cite, BCA blog) | Apache-2.0 with LLVM Exception | None native; via Javy / StarlingMonkey / quickjs.wasm | WASI Preview 2 imports; explicit `Linker` config; capability-by-default-deny | **Yes** — `Store` is documented to persist instance state across calls ([docs.rs](https://docs.rs/wasmtime/latest/wasmtime/struct.Store.html)) |
| **Wasmer** | [v7.1.0, 2026-03-27](https://github.com/wasmerio/wasmer/releases) | "incredibly lightweight" (no specific number on README) | Near-native (UNVERIFIED) | MIT | None native; WASIX extends WASI | Default-deny: "No file, network, or environment access, unless explicitly enabled" ([README](https://github.com/wasmerio/wasmer)) | Yes (analogous to Wasmtime) |
| **WasmEdge** | [v0.16.2, 2026-04-15](https://github.com/WasmEdge/WasmEdge) | Optimized for edge / IoT | UNVERIFIED | Apache-2.0 (CNCF) | Has JS support via QuickJS plugin | WASI imports | Yes |
| **Spin (Fermyon)** | [v4.0.0, 2026-04-20](https://github.com/fermyon/spin) | Bound by Wasmtime + Spin SDK | Sub-ms (UNVERIFIED — Fermyon claims) | Apache-2.0 | Via StarlingMonkey | WASI Preview 2 | **No — request-scoped only.** State delegated to Key-Value / SQLite providers. |
| **WasmCloud** | [v2.1.0, 2026-05-07](https://github.com/wasmCloud/wasmCloud) | Component-sized | UNVERIFIED | Apache-2.0 (CNCF) | Polyglot via Component Model | Capability-providers (Redis / KV / etc abstracted behind a WIT interface) | **No — explicitly stateless components.** ([wasmcloud.com docs](https://wasmcloud.com/docs/concepts/components)) |
| **Extism** | [v1.21.0, 2026-03-26](https://github.com/extism/extism) | Negligible above underlying Wasmtime/Wazero | UNVERIFIED | BSD-3 | Via QuickJS PDK | Host-SDK style (13+ language SDKs) | **Yes — Plugin retains linear memory and variables between calls** (Gemini Q1 synthesis, matches Extism's "persistent memory" feature claim). Strong dark-horse Phase-0 candidate. |
| **Wizer (pre-init)** | [v11.0.3, 2026-03-10](https://github.com/bytecodealliance/wizer) | n/a (build tool) | 1.35×–6.00× faster instantiation per its own benchmarks | Apache-2.0 | n/a | n/a | Not a runtime; complements Wasmtime |
| **Javy (CLI)** | [v8.1.1, 2026-04-06](https://github.com/bytecodealliance/javy) | 869 KB static / 1–16 KB dynamic-linked module | <<1ms post-Wizer (UNVERIFIED) | Apache-2.0 | QuickJS — most ES2023 | Inherits Wasmtime / WASI | **No — `_start` one-shot.** ([Gemini synthesis](#) cross-checked vs README example) |
| **Javy crate (custom embedding)** | same | same | same | same | same | same | **Yes — if you build your own `.wasm` exporting `eval_js(code)` and keep the `Store` alive.** |
| **quickjs-emscripten** | actively maintained, modular npm packages | "very lightweight" (UNVERIFIED) | Near-instant (UNVERIFIED) | MIT | Full QuickJS (most ES2024 via `quickjs-ng` upstream) | Host JS environment controls imports via `Lifetime` and `expose` | **Yes — designed for it.** README and Gemini both confirm `evalCode` accumulates state in a persistent `Context`. ([README](https://github.com/justjake/quickjs-emscripten)) |
| **Boa (JS engine)** | [v0.21.1, 2026-03-29](https://github.com/boa-dev/boa) | Larger Rust binary (UNVERIFIED) | UNVERIFIED — slower than QuickJS | MIT / Unlicense | ~94.1% Test262 conformance ([boajs.dev/boa/test262](https://boajs.dev/boa/test262/)) | Pure-Rust, can be embedded freely | Yes — but slower; "experimental" framing on README |
| **StarlingMonkey** | active (no version visible on README) | C/C++ (~56% C, ~31% C++) | UNVERIFIED | Mozilla / BCA | SpiderMonkey — production JS | WASI 0.2.0 / Component Model | Used by Fastly Compute and Spin JS SDK — **but production usage is request-scoped** |
| **isolated-vm** | [v6.0.2, 2025-10-16](https://github.com/laverdet/isolated-vm) | ~3–10 MB per isolate (UNVERIFIED, Cloudflare ballpark) | Single-digit ms | ISC | V8 — full | Cap-by-default-deny via API | Mature but **maintenance mode**; known issues at long-lived shape (Gemini cites issue #113 perpetual-GC reports — UNVERIFIED specific issue ID) |
| **Pyodide** | [v0.29.4, 2026-05-07](https://github.com/pyodide/pyodide) | ~60–100 MB starting (UNVERIFIED, Gemini estimate) | 2–5s cold (UNVERIFIED) | MPL-2.0 | n/a (Python) | Inherits WASM | Yes (CPython on WASM is inherently REPL-shaped) but heavy |

**Notes on verification:**
- Version numbers and release dates were directly fetched from each project's GitHub releases page or repo on 2026-05-08.
- Per-instance memory and boot-time numbers without an explicit citation are Gemini-3-Pro estimates and explicitly flagged UNVERIFIED.
- Cloudflare's [10K isolates per host](https://blog.cloudflare.com/cloud-computing-without-containers/) and [3 MB per isolate](https://blog.cloudflare.com/cloud-computing-without-containers/) numbers come from Cloudflare's own blog and were verified in this session. Their post predates 2026 (originally 2018, updated since) — treat as ballpark, not 2026 measurement.

---

## 3. The REPL / persistent-state question (load-bearing)

This is the section that decides the answer. Sean's requirement: **agent inspects schema, defines a function with spec + tests, runs the tests, iterates, commits the function to a per-user library, calls it next turn — all inside a live session that lives 5–30 minutes.**

### What "persistent state in WASM" actually means

A WASM module's *linear memory* is a contiguous buffer the runtime maps. A `Store` (Wasmtime's term) owns one or more `Instance`s, each with its own linear memory. **As long as you hold the `Store` alive and call exported functions on the same `Instance`, linear memory persists exactly as you'd expect** — including any heap structures the embedded JS engine has built up inside it. Wasmtime docs are explicit on this:

> "A `Store` is intended to be a short-lived object in a program. No form of GC is implemented at this time so once an instance is created within a `Store` it will not be deallocated until the `Store` itself is dropped."
> — [docs.rs/wasmtime, struct.Store](https://docs.rs/wasmtime/latest/wasmtime/struct.Store.html)

"Short-lived" here is relative to the program lifetime — within a process, a `Store` can hold a long-running session perfectly fine. The doc warning is about leaking `Store`s in a worker pool, not about session length.

### The hot-define-and-call pattern

WASM itself does **not** allow new compiled functions to be added to a running module — there's no in-sandbox JIT, no module mutation. **The trick is to embed a JS engine and let *it* do the dynamic compilation.** The agent emits JS source; the host calls `exec_js(source)`; the embedded QuickJS parses and stores it inside its own heap (which lives inside the WASM linear memory); a later `exec_js("foo(5)")` finds the function still resident and runs it.

This works for QuickJS-WASM in Wasmtime, but **only if the WASM module exports `exec_js` (or equivalent), not just `_start`**. The standard Javy CLI does *not* — it bundles your JS as a WASI Command with a `_start` entry. [Gemini Q2 synthesis cross-checked vs Javy README](https://github.com/bytecodealliance/javy):

> "The Javy CLI is designed strictly for **oneshot-only** execution. It compiles your JS into a WASI Command module with a single `_start` entry point. ... For stateful embeddings, developers use the underlying `rquickjs` or `javy` crates."

So the WASM REPL stack we want is:

1. Write a small Rust crate that embeds `rquickjs` (or `javy`'s underlying `quickjs-wasm-rs`) and exposes a `eval_js(code: &str) -> Result<String>` export.
2. Compile that crate to `wasm32-wasi` (or wasm32-wasip2 for Component Model).
3. Run Wizer on the resulting `.wasm` to snapshot the post-init state (QuickJS engine constructed, stdlib parsed). [Wizer reports 1.35x–6.00x speedup depending on workload.](https://github.com/bytecodealliance/wizer)
4. At session start, instantiate the Wizer-snapshotted module in a fresh Wasmtime `Store`. Hold the `Store` and `Instance` for the session.
5. On every agent turn, call `eval_js(...)` with whatever the agent emitted — `define` requests, test runs, library calls, EAVT primitive invocations.
6. Capability-controlled host functions (`assert`, `query`, `embed`, `nearest`, etc.) are wired into the `Linker` and importable from the WASM guest. The agent's JS sees them as normal functions; the host enforces the semantic boundary.

That stack works. It is also a non-trivial build — easily 1–2 weeks of plumbing for a Rust engineer who hasn't done WASM before. The QuickJS-emscripten alternative (Section 7) gets you the same agent-visible API in ~2 days.

### Gotchas in the WASM REPL pattern

- **QuickJS heap fragmentation over a 30-minute session.** Successive `eval` calls grow and fragment the engine's heap; the WASM linear memory grows monotonically until you kill the instance. Gemini flagged this and it matches QuickJS's documented GC behaviour. Mitigation: configure Wasmtime memory limits, kill+respawn on threshold breach, make the per-user function library re-loadable from durable storage (which we want anyway for cross-session persistence).
- **Each `eval` re-parses.** The agent re-feeding the same library function on every call wastes work. Pattern: `define` admits the function once, the host caches its source, subsequent `call(name, args)` looks up by name and emits `name(JSON.parse(args))` into the engine. The function's parsed AST stays in QuickJS's compiled-bytecode cache.
- **Component Model resources are stable as of WASI 0.2.0** ([component-model.bytecodealliance.org](https://component-model.bytecodealliance.org/)) but the broader spec is still draft. For Phase-0 we don't need Component Model; classic WASI Preview 1 imports are enough. Skip the complexity.
- **Pre-initialization is the difference between viable and not.** Without Wizer, every session pays the QuickJS bootstrap cost (parsing the standard library) on every `Store` instantiation. Shopify's experience says Wizer is the make-or-break for Javy at production density. ([Shopify blog](https://shopify.engineering/javascript-in-webassembly-for-shopify-functions))
- **No native debugger.** Debugging an embedded QuickJS-in-Wasmtime when the agent's JS misbehaves is painful — you have a stack trace from QuickJS but no breakpoint surface. Native QuickJS or `quickjs-emscripten` from a Node host both have substantially better dev-loop ergonomics.

### Bottom line for this section

Yes, WASM supports the REPL pattern Sean asked for. The path is concrete and well-trodden by the Bytecode Alliance crates. But the path is **harder than the obvious alternatives by ~10x in plumbing-effort terms**, and the "extra safety" of WASM linear-memory boundaries is undermined by April 2026's CVE batch (Section 5).

---

## 4. JS-on-WASM landscape

| Engine | Approach | Persistent state | JS coverage | Footprint | Verdict for the agent |
|---|---|---|---|---|---|
| **Javy CLI** | QuickJS → WASI Command | **No** (one-shot `_start`) | Most ES2023 | 869 KB static / 1–16 KB dynamic | Wrong shape — production model is per-request reset |
| **Javy crate / rquickjs (custom embed)** | QuickJS via Rust binding → custom `.wasm` exporting `eval_js` | **Yes** | Same as Javy | Same | **The WASM-stack pick if WASM wins.** Real work to build. |
| **quickjs-emscripten** | QuickJS → emscripten WASM, hosted from JS (Node/browser) | **Yes — designed for it** ([README](https://github.com/justjake/quickjs-emscripten)) | Same QuickJS coverage | Modular npm packages | **The non-WASM-stack pick.** Drop-in REPL. |
| **StarlingMonkey** | SpiderMonkey → WASM, used by Fastly + Spin JS SDK | Production usage is request-scoped; persistence not the design intent | Production JS (full SpiderMonkey) | Larger than QuickJS | Worth a look only if QuickJS's ES coverage gap actually bites. It hasn't bitten Shopify. |
| **Boa** | Pure Rust, can compile to WASM | Possible but underexplored | ~94.1% Test262 ([boajs.dev/boa/test262](https://boajs.dev/boa/test262/)) | Larger Rust binary, slower than QuickJS | Strong in 2027+ when memory-safety matters more than perf. Skip for Phase-0. |
| **SpiderMonkey-on-WASM** | Yes, exists (StarlingMonkey is this) | See above | See above | See above | See above |
| **V8-on-WASM** | Hypothetical / impractical | n/a | n/a | n/a | V8's JIT violates W^X; nobody is shipping this. |
| **Native QuickJS (no WASM)** | C library embedded in Rust/Go host directly | **Yes** | Same QuickJS coverage | Tiny | Faster than WASM-wrapped QuickJS, but loses WASM's memory-isolation property — relies on the C engine's own correctness. |

**Coverage check:** QuickJS via Javy / quickjs-emscripten covers the JS our agent will plausibly emit. The agent isn't writing browser code — it's writing function bodies that compose EAVT primitives. ES2023's syntax surface (arrow functions, destructuring, async/await, classes, `Map`/`Set`, generators) is more than enough. SpiderMonkey/StarlingMonkey only matters if we want full `fetch`/`Streams`/Web-API surface — we don't, the host functions are explicit.

**Where the agent's training prior lives:** Qwen3.6's coding prior is JS+Python. Both QuickJS-flavoured JS and Pyodide-flavoured Python are in-distribution for the model. **The agent does not need to know it's running inside WASM.** From its perspective it's just writing JS that calls a small, sharp set of host functions. The substrate choice is ours, not the model's.

---

## 5. Capability model — what's actually enforced

### WASI's import-based capability model

WASI is structurally capability-based: a guest module can only call host functions that the host explicitly imported into its `Linker`. There is no ambient filesystem, no ambient network, no ambient anything. Wasmtime's docs emphasise this:

> "WASI's security model keeps users safe today, and also helps us prepare for shared-nothing linking and nanoprocesses in the future ... users can only access files explicitly granted." — [docs.wasmtime.dev/security](https://docs.wasmtime.dev/security.html)

For the agent this is a clean fit. The EAVT primitives (`assert`, `retract`, `query`, `schema`, `embed`, `nearest`, `note`, `define`, `call`, `exec`) are wired as host functions in the `Linker`. The agent's WASM module imports exactly those, nothing else. No surprise filesystem, no surprise socket. Per-user `Linker` instances let us scope access per session — user A's WASM module only sees the host functions wired against user A's DB. This is genuinely cleaner than V8 isolates, where capability control is API-surface discipline rather than module-import discipline.

### What's enforced vs what relies on host config

The runtime *enforces* that an unimported function cannot be called. But **the host must explicitly choose what to import.** A misconfigured `Linker` that adds a generic `wasi-filesystem` preopen leaks the entire FS into the sandbox. The mathematical sandbox doesn't protect against operator misconfiguration. The pattern that mitigates this for us: a single `aria_runtime` host crate that builds the `Linker` from a hardcoded primitive list; sessions cannot inject extra imports.

Wasmer's posture is identical and explicit: "Secure by default. No file, network, or environment access, unless explicitly enabled." ([README](https://github.com/wasmerio/wasmer))

### CVE history — the honest read

Wasmtime had a notable batch of advisories in **April 2026** (these are public on [github.com/advisories](https://github.com/advisories?query=wasmtime)):

- **CVE-2026-34971 (critical, 2026-04-09)** — miscompiled guest heap access enables sandbox escape on aarch64 Cranelift backend. (Patched in Wasmtime 43.0.1.)
- **CVE-2023-26489 (critical, x86_64 historical)** — Cranelift x86_64 backend used 35-bit effective addresses instead of 33-bit, allowing modules to read/write memory up to 34 GB away from the linear memory base. Patched long ago; cited here to show the JIT-miscompile attack class is recurring, not aarch64-specific.
- **CVE-2023-51661 (Wasmer)** — WASI filesystem path-traversal bypass on Wasmer, allowing modules to read host files outside pre-opened directories. Logic flaw in the host-side WASI implementation, not the WASM sandbox itself — illustrates that "the runtime enforces capabilities" is shorthand for "the host's WASI implementation enforces capabilities, and that implementation has bugs."
- **CVE-2026-34987 (critical, 2026-04-10)** — Winch compiler backend on aarch64 may allow a sandbox-escaping memory access.
- **CVE-2026-34988 (low, 2026-04-09)** — data leakage between pooling allocator instances.
- **CVE-2026-34983 (low, 2026-04-09)** — use-after-free after cloning `wasmtime::Linker`.
- **CVE-2026-35195 (moderate, 2026-04-09)** — out-of-bounds write or crash in component-model string transcoding.
- **CVE-2026-34941 (moderate, 2026-04-09)** — heap OOB read in component-model UTF-16 string transcoding.

Total advisories on the project to date: ~42, per the GHSA database. **This is not a black mark on Wasmtime — it reflects that the project is being actively fuzzed and audited, vulnerabilities are publicly disclosed and fixed quickly.** It's how mature security-critical projects look. But the framing "WASM is mathematically sandboxed" is wrong — the sandbox is implemented by the JIT, the JIT has bugs, the JIT gets exploited. The right framing: "WASM has a smaller and better-formalised attack surface than a Linux kernel; multiple recent critical bugs were found and patched within days; we deploy in pooling-allocator-off mode for the highest-isolation tenants."

**Operational implication for the agent:** if we ship Wasmtime, we run it on x86_64 only (the aarch64 Cranelift / Winch bugs above are aarch64-specific), pin to known-clean releases, subscribe to the Bytecode Alliance security feed, and accept that we will be patching CVEs the same way we patch Linux kernel CVEs. None of this is a reason to *not* use Wasmtime; it is a reason to not oversell its safety.

For comparison, V8 has had its own CVE stream forever (Project Zero finds them constantly); Linux containers leak via kernel CVEs regularly. There is no boundary that doesn't get probed.

---

## 6. Multi-tenant / parallel scaling

| Substrate | Density | Cold start | State model | Verified? |
|---|---|---|---|---|
| **Cloudflare Workers (V8 isolate)** | 10K+ isolates per host; ~3 MB per isolate | <5 ms | External (Durable Objects, KV) — request-scoped compute | [Cloudflare blog](https://blog.cloudflare.com/cloud-computing-without-containers/) — ballpark; original 2018 |
| **Fermyon Cloud (Spin / Wasmtime)** | "3K–5K applications per K8s node" (Gemini cite, marked UNVERIFIED — Fermyon density blog claim) | Sub-ms | External (Spin KV / SQLite providers) — request-scoped | [fermyon.com/blog](https://www.fermyon.com/blog/webassembly-serverless-density) (referenced by Gemini, not directly verified this session) |
| **Fastly Compute@Edge** | "Tens of thousands per edge node" (Gemini, UNVERIFIED) | Microsecond | Per-request | UNVERIFIED |
| **Wasmer Edge** | UNVERIFIED | UNVERIFIED | UNVERIFIED | n/a |
| **Fly Machines (Firecracker)** | ~100s–~1.5K VMs per host depending on RAM | ~300 ms VM boot | Full memory state via snapshot/thaw | [fly.io blog](https://fly.io/blog/fly-machines/) — boot time verified; density UNVERIFIED |
| **E2B / Modal Sandbox** | Modal docs say up to 24h sandbox lifetime, 5min default ([modal.com docs](https://modal.com/docs/guide/sandbox)) | E2B uses Firecracker, Modal uses gVisor (per Gemini Q3, UNVERIFIED specific runtime) | Filesystem snapshots for >24h workloads | Lifetime verified; runtime-tech UNVERIFIED |
| **Docker per session** | Whatever the host can fit | Seconds | Native | Operationally familiar |
| **AppWorld Docker simulator** | "Dozens to hundreds concurrent" (Gemini, UNVERIFIED) | n/a | Per-task isolated apps | [arXiv 2407.18901](https://arxiv.org/abs/2407.18901) is the right paper; resource-per-env not in abstract |
| **TheAgentCompany** | "~8 vCPU / 32 GB RAM per session" (Gemini UNVERIFIED — Gemini also fabricated arxiv ID 2412.18001, correct ID is **2412.14161**, [arxiv.org/abs/2412.14161](https://arxiv.org/abs/2412.14161)) | Seconds | Real Gitlab/Rocket.Chat per-task | Resource-per-task UNVERIFIED — Gemini hallucinated the arxiv ID, treat the resource estimate skeptically too |

### Density math for the agent's specific shape

**Phase-0 target: 10K concurrent training sessions, each holding a 5–30 minute REPL with ~10–50 MB of accumulated state.**

- A 256 GB host with ~50 MB per WASM session = ~5K sessions per host on RAM alone. CPU is the bottleneck before RAM (each session is sometimes evaluating, mostly idle waiting for the LLM). At 10K total sessions, ~2 hosts cover RAM; CPU determined by per-second eval demand from the training loop.
- Same shape on V8 isolates (`isolated-vm` from Node): density similar to Cloudflare's 10K-per-16-GB number — actually more efficient than WASM on memory because V8 shares the runtime across isolates whereas each WASM `Instance` carries its own engine state inside linear memory. 1–2 hosts cover RAM.
- Same shape on Firecracker (E2B/Modal/Fly): ~50 MB overhead per VM + 50 MB session state = ~100 MB per session → 256 GB / 100 MB ≈ 2.5K sessions per host → ~4 hosts at 10K. More expensive but stronger isolation.
- Docker: ~200 MB per container minimum + session state → ~1K per host → ~10 hosts at 10K. Operationally familiar; ~10× the host count.

**For Phase-0 (10K sessions), all four substrates fit on commodity hardware.** The cost differences become meaningful only at the V2 1M-user shape, where they were already analyzed in [`2026-05-07-separation-and-sandbox.md`](2026-05-07-separation-and-sandbox.md).

### Long-lived state — none of the production WASM platforms support it

Re-emphasising the gotcha: **Fermyon Cloud, Fastly Compute@Edge, WasmCloud, Wasmer Edge are all designed for request-scoped components.** WasmCloud's docs are explicit ("components only run when invoked ... when a component needs state, it uses a provider"). Spin's persistence story is Spin KV / SQLite — also external. We are not deploying the agent onto any of these platforms. We run our own Wasmtime processes with our own session lifecycle. The serverless WASM world is the wrong shape for our REPL pattern.

---

## 7. Comparison with V8 isolates / native QuickJS / Docker / Firecracker

For the agent's specific requirements (REPL + per-user state + capability-controlled primitives + thousands of concurrent + JS prior), here's the honest tradeoff matrix:

| Substrate | REPL fit | Capability model | JS-on-substrate | Implementation effort | Per-host density |
|---|---|---|---|---|---|
| **Wasmtime + custom rquickjs `.wasm` + Wizer** | Excellent | WASI imports, very clean | QuickJS native | **High** — build custom wasm, integrate Wizer, wire Linker | High (~5K/256GB) |
| **`quickjs-emscripten` from Node host** | Excellent (designed for it) | Host JS controls all imports | QuickJS native | **Low** — pull npm package, wrap | High (similar) |
| **`rquickjs` native from Rust host (no WASM wrap)** | Excellent | Host Rust controls all imports | QuickJS native | **Low–medium** — Rust crate, well-documented | High |
| **`isolated-vm` from Node host** | Good but maintenance-mode | V8 cap-by-API-discipline | V8 native (full ECMAScript) | Low — npm package | Highest (~10K/16GB ballpark) |
| **Deno Subhosting (managed)** | Excellent | OS-process isolation around V8 | TypeScript native | Lowest — managed service | Managed |
| **Pyodide in WASM** | Excellent (Python REPL by design) | WASM + import-controlled | n/a (Python) | Medium | Low (60–100 MB per session) |
| **Docker per session** | Possible (long-running container with stdin REPL) | Linux namespaces | Anything | Medium — orchestration | Low (~1K/256GB) |
| **Firecracker per session (Modal/E2B/Fly)** | Excellent | Full kernel boundary | Anything | Low — managed | Medium (~2.5K/256GB) |

**For Phase-0 specifically**, the contenders that actually fit:

1. **`quickjs-emscripten` from Node** — fastest spike, REPL works out of the box, capability-by-host-import, runs anywhere Node runs. The "boring" answer. **My recommended pick.**
2. **`rquickjs` from Rust** — same shape as #1, slightly more performant, better ergonomics if the rest of the agent's host is Rust. Good Phase-0 if we want to pre-position for the eventual WASM port.
3. **Wasmtime + custom rquickjs `.wasm`** — strategically cleanest, ~10× the build effort, marginal Phase-0 win. **Right answer for V2; wrong investment for Phase-0.**
4. **`isolated-vm`** — works, mature, fast — but maintenance-mode and known to have long-lived-isolate pain. Skip.
5. **Pyodide** — viable if/when the agent emits Python, which is a deferred question. Not Phase-0.

The hybrid Sean already left on the menu in the brainstorm doc — "lightweight engine for inner primitive loop, Docker-backed when a scenario needs an app surface" — remains intact under any of these picks. The inner loop is `quickjs-emscripten` (or `rquickjs`); the outer Docker layer is invoked via primitives like `app.gmail.search(...)` when a scenario actually requires an app surface, exactly per the AppWorld / TheAgentCompany shape.

---

## 8. Production users

Who's actually running WASM at long-lived stateful shape?

- **Shopify Functions (Javy):** Production but **one-shot per request**. Confirmed in [Shopify's engineering blog](https://shopify.engineering/javascript-in-webassembly-for-shopify-functions). Volume is high; pattern is wrong shape for us.
- **Fastly Compute@Edge (StarlingMonkey + Wasmtime fork):** Production, request-scoped.
- **Fermyon Cloud (Spin):** Production, request-scoped, advertises high density.
- **WasmCloud:** Production, explicitly stateless components.
- **Cosmonic (commercial WasmCloud distribution):** Same shape.
- **Cloudflare Workers (V8 isolate primary, WASM secondary):** Production, request-scoped, [Durable Objects bolt persistence on as a separate actor model](https://blog.cloudflare.com/cloud-computing-without-containers/).
- **Screeps (V8 isolate via `isolated-vm`):** Production, **long-lived stateful per-user JS sessions** — closest analog to the agent's shape. Players' code runs continuously. Pattern works.
- **Algolia (V8 isolate):** Production for sandbox-evaluating user code on web crawls.
- **TripAdvisor (V8 isolate for SSR):** Production, request-scoped.

**Honest read:** the ecosystem of "long-lived stateful WASM sessions per user" is essentially empty. The closest analogue in any sandbox is Screeps on `isolated-vm` (V8, not WASM) — and even Screeps had to build their own perpetual-GC mitigations. For the agent, this is *not* terrifying — Sean's session length (5–30 minutes) is much shorter than Screeps's persistent-player model. But it does mean we will be early-adopters of the pattern at any non-trivial scale, and there is no off-the-shelf reference architecture to copy.

---

## 9. Honest gotchas

- **Debugging.** Stack traces from QuickJS-inside-Wasmtime are a tracing puzzle. Prefer native or emscripten QuickJS for Phase-0; the dev loop is dramatically better.
- **Observability.** No native flame-graph for what's happening inside an embedded JS heap. We will need to instrument the host functions to get any useful trace. Same problem in V8 isolates and native QuickJS, though.
- **Wasmtime CVE rate.** April 2026 batch is a real signal of attack-surface attention. Plan for monthly patch cycles.
- **Wizer is a build-step, not a runtime.** If we want fast cold start, Wizer becomes part of CI. Failures in the snapshot phase need to be caught before deploy.
- **QuickJS heap fragmentation.** Already covered. Mitigation is kill-and-respawn at memory threshold.
- **Component Model is stable but ecosystem is thin.** The promise of "components from any language compose" is real for new code; but neither the rest of the agent stack nor the host environment uses it. Skip for Phase-0.
- **WASI Preview 2 `wasi:io`/streams have surprising semantics.** If we wire async host functions via streams, the back-pressure model is non-obvious. For Phase-0 use synchronous host functions.
- **Pooling allocator data leak (CVE-2026-34988).** If we use Wasmtime's pooling allocator (which is *the* recommended pattern for high-density), we need to be on the patched release. This is the kind of bug that bites operationally.
- **"WASM density" numbers from vendor blogs are mostly request-throughput, not concurrent-stateful-instance counts.** Don't treat Fermyon's 3K–5K-per-node number as if it applies to our shape. We are not their workload.
- **rquickjs / javy crate is a smaller ecosystem than the JS world expects.** Bug fixes ship in months, not days. Plan for occasional patch-fork.

---

## 10. Recommended next step

**Spike: build the per-user function library + REPL pattern in `quickjs-emscripten` from a Node or Rust host with mocked EAVT primitives.**

Concrete shape, ~3 days of focused work:

1. **Day 1 — Skeleton and contract.**
   - Single Node (or Rust) process. Per-session: `new QuickJSContext()`, hold for the session.
   - Wire 6 host functions as JS-side imports: `assert`, `query`, `schema`, `embed`, `nearest`, `define`. Mock the underlying EAVT store with an in-memory map; mock embed with a hash; mock nearest with cosine over the hash. The point is the *contract* shape, not the storage.
   - `define(name, spec, impl, tests)` runs `tests` against `impl` and either admits to a per-session library map or rejects. `call(name, args)` looks up by name and invokes.

2. **Day 2 — REPL behaviour and capability checks.**
   - Verify: agent can `define("getName", spec, impl, tests)` in turn 1, and `call("getName", {})` in turn 5 returns the expected value. Verify: function definitions persist in QuickJS heap across `evalCode` calls.
   - Verify: a function impl that tries to call a not-allowed primitive (e.g., `fetch`, `require`) fails because the import isn't wired. This is the capability-model spot-check.
   - Verify: 1000 sequential `evalCode` calls don't crash the context. Measure heap growth per call.

3. **Day 3 — Density and port-readiness.**
   - Spawn 100 contexts in the same Node process; verify they're truly isolated. Measure RAM. Extrapolate to host density.
   - Document the boundary cleanly: which calls cross the host/guest line, what types they take. This is what the eventual Wasmtime port re-implements without rewriting the agent's code.
   - Write a `~50 LOC adapter` sketch showing how the same `exec(code) / define(name,...)` API would be wired against a Wasmtime + custom-rquickjs stack. Don't build it; just verify the contract is portable.

**Success criterion:** Sean can fire up the spike, paste a multi-turn agent transcript that defines a function and uses it three turns later, and watch it work. Capability boundary verified by attempting a forbidden primitive and getting a clean error. Density extrapolation reads ≥1K sessions per commodity host.

**If the spike succeeds:** the boundary commitment in the 2026-05-08 decision (`exec` + EAVT primitives + `define`) is validated as portable across engines. WASM stays on the menu for V2 — the port is mechanical at that point. Phase-0 ships on the boring `quickjs-emscripten` (or `rquickjs`) substrate.

**If the spike fails on capability-control or density:** revisit Wasmtime + custom rquickjs `.wasm` (the higher-effort WASM path) before considering Docker-per-session. Pyodide stays out of Phase-0 regardless — the Python substrate question is not load-bearing yet.

---

## Sources

**Primary (verified by direct WebFetch this session):**
- Wasmtime releases — https://github.com/bytecodealliance/wasmtime/releases (v36.0.9, 2026-05-05)
- Wasmtime Store docs — https://docs.wasmtime.dev/api/wasmtime/struct.Store.html
- Wasmtime security model — https://docs.wasmtime.dev/security.html
- Wasmtime advisories — https://github.com/advisories?query=wasmtime (April 2026 CVE batch)
- Wasmer repo + releases — https://github.com/wasmerio/wasmer (v7.1.0, 2026-03-27)
- WasmEdge — https://github.com/WasmEdge/WasmEdge (v0.16.2, 2026-04-15)
- Fermyon Spin — https://github.com/fermyon/spin (v4.0.0, 2026-04-20)
- WasmCloud — https://github.com/wasmCloud/wasmCloud (v2.1.0, 2026-05-07) and https://wasmcloud.com/docs/concepts/components
- Extism — https://github.com/extism/extism (v1.21.0, 2026-03-26)
- Wizer — https://github.com/bytecodealliance/wizer (v11.0.3, 2026-03-10)
- Javy — https://github.com/bytecodealliance/javy (v8.1.1, 2026-04-06)
- quickjs-emscripten — https://github.com/justjake/quickjs-emscripten
- Boa — https://github.com/boa-dev/boa (v0.21.1, 2026-03-29)
- StarlingMonkey — https://github.com/bytecodealliance/StarlingMonkey
- isolated-vm — https://github.com/laverdet/isolated-vm (v6.0.2, 2025-10-16, maintenance-mode notice)
- Pyodide — https://github.com/pyodide/pyodide (v0.29.4, 2026-05-07)
- QuickJS-NG — https://github.com/quickjs-ng/quickjs (v0.14.0, April 2026)
- Cloudflare Workers blog — https://blog.cloudflare.com/cloud-computing-without-containers/
- Fly Machines blog — https://fly.io/blog/fly-machines/
- Modal Sandbox docs — https://modal.com/docs/guide/sandbox
- Shopify engineering on Javy — https://shopify.engineering/javascript-in-webassembly-for-shopify-functions
- Component Model overview — https://component-model.bytecodealliance.org/
- AppWorld arxiv — https://arxiv.org/abs/2407.18901
- TheAgentCompany arxiv — https://arxiv.org/abs/2412.14161 (Gemini hallucinated 2412.18001; correct ID confirmed by direct fetch)

**Secondary (Gemini-3-Pro synthesis, partially cross-checked):**
- `/tmp/gemini-q1-out.md` — runtime survey, contributed Wasmtime v44.0.1, ~5µs boot via Pooling Allocator, Extism's persistent-memory pattern, V8 isolate ~15-30 MB baseline, two historical CVEs (2023-26489 Cranelift x86_64, 2023-51661 Wasmer FS path traversal), WASI Preview 3 RC status
- `/tmp/gemini-q2-out.md` — JS-on-WASM landscape survey, key load-bearing finding (Javy CLI is one-shot; rquickjs custom embed is stateful) verified vs Javy README
- `/tmp/gemini-q3-out.md` — persistence + scaling + alternatives synthesis, density numbers for Fermyon / Cloudflare cross-checked vs primary blog

**Unverified (flagged in-text):**
- All per-instance memory and boot-time numbers without an explicit citation
- Fermyon's "3K–5K applications per K8s node" — Gemini citation, not directly verified this session
- TheAgentCompany "8 vCPU / 32 GB per session" — Gemini number, hallucinated arxiv ID makes this estimate suspect

