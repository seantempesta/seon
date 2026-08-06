---
type: research
status: active
tags: [research, architecture, agent]
---

# Bun shared-memory options for execution children (2026-07-20)

Can the immutable content that every execution child materializes (~90 MB
program load + ~91 MB session/admission, per
[[child-footprint-bisect-2026-07-20]]) live in the parent or a shared medium
instead of being duplicated per child? Every claim below is grounded in the
vendored Bun source actually running the children
(`reference-code/bun`, commit d8ecf098, Rust/C++/JSC tree — not upstream Zig
Bun and not general Node knowledge). Seon-side grounding:
`src/seon/execution/host.cljs`, `src/seon/execution.cljs` (`start-child!`
:1046), `src/seon/runtime/admission.cljs`, `src/seon/schema.cljc`.

## The one governing fact

Both duplicated bands are **constructed JS heap object graphs**, not byte
payloads:

- the ~90 MB load band is the heap produced by executing every compiled
  namespace's top-level forms (the bisect falsified parse/artifact shape:
  a single-file release bundle loads *higher*, 104.6 vs 89.1 MB);
- the ~91 MB admission band is `db/open-session!` plus
  `admission/prepare-committed!` → `committed-projection`
  (`src/seon/runtime/admission.cljs:209-223` — `reader/read-string` every
  schema row) → `schema/build-projection`, which **eagerly compiles every
  Malli schema** (`m/schema k options` over the whole registry,
  `src/seon/schema.cljc:307-315`) plus dependency indexes and the entity
  catalog. The raw acquired wire bytes are capped at ≤6 MB
  (`admission.cljs:246`, `::db/max-result-weight (* 6 1024 1024)`), so
  ≥85 MB of that band is materialization, not data shipping.

No mechanism in this Bun tree — or in JSC — shares live JS heap object
graphs between VMs, between Workers, or between processes. Everything below
is a corollary of that.

## 1. Worker threads: full VM per worker, no heap sharing, weaker containment

- Each `new Worker` runs `WebWorker::start_vm`
  (`src/jsc/web_worker.rs:834`), which calls
  `VirtualMachine::init_worker` (`:922`) — the comment at `:909-912` is
  explicit: "initWorker builds a full JSC VM ... ~50–100ms (release)".
  Per-worker state includes its own arena (`:873`), a **clone** of the
  parent's env map (`:886-895`), its own transpiler, module loader, and JSC
  heap. Only the standalone-module graph pointer (`:928`,
  `graph: parent.standalone_module_graph`, compiled executables only) and
  process-level structures (mimalloc, binary text pages) are shared.
- Therefore a Worker running the Seon child artifact re-executes all
  top-level forms into **its own** heap: the ~90 MB program band and the
  ~91 MB admission band recur per worker. Savings vs a child process ≈ the
  6 MB bare-bun floor plus some allocator/runtime bookkeeping — noise at
  this scale.
- Containment is strictly weaker than processes:
  - **Kill semantics are cooperative.** `terminate()` →
    `WebWorker__notifyNeedTermination` (`web_worker.rs:682-692`) raises a
    JSC TerminationException "at the next JSC safepoint" (`:330`,
    `:366-372`, via VMTraps). Code stuck in native/FFI or a non-yielding
    loop that JSC cannot trap is unkillable without killing the whole pod.
    SIGKILL on a child process is unconditional
    (`host.cljs` `kill-process!` :119).
  - **No per-worker memory cap.** `resourceLimits` in
    `src/js/node/worker_threads.ts` is a module-level stub
    (`let resourceLimits = {}` at :320, exported verbatim at :1375);
    nothing in `web_worker.rs` or the C++ Worker consumes a memory limit.
    `BUN_JSC_forceRAMSize` is process-wide.
  - **Shared crash domain.** A native crash, OOM abort, or JSC assertion
    in any worker kills every agent in the process; today one child's
    death is one agent's fault envelope (`host.cljs` `exit-child!` :193).
- Heap *separation* between workers does hold (separate JSC VMs), so
  agent-to-agent JS-level isolation survives. But the memory motivation is
  absent and the containment cost is real. **Workers are not a lever for
  this problem.** They could only ever be argued for spawn-latency
  (~50–100 ms VM build vs full process boot), and even that is dominated by
  Seon's own admission time.

## 2. SharedArrayBuffer + structuredClone: bytes only, in-process only

- SAB is enabled (`JSC::Options::useSharedArrayBuffer() = true`,
  `src/jsc/bindings/ZigGlobalObject.cpp:305`).
- The serializer shares the backing store **only** when
  `m_context == SerializationContext::WorkerPostMessage`
  (`src/jsc/bindings/webcore/SerializedScriptValue.cpp:1850-1864`); in
  every other context — including process IPC, which serializes with
  `for_cross_process_transfer: true` (`src/jsc/ipc.rs:409-411`) — a shared
  buffer degrades to a plain byte copy (`SerializedScriptValue.cpp:1880-1888`).
- So SAB is: same-process only, raw bytes only. There is no way to place a
  CLJS persistent map, a Malli validator closure, or any JS object graph in
  a SAB. Cross-process SAB does not exist in this tree.

## 3. Bun.mmap: real, page-cache-shared bytes — but the child cost is not bytes

- `Bun.mmap(path, {shared, size, offset})` exists:
  `src/runtime/api/BunObject.rs:1736-1861`. Defaults to `MAP_SHARED`
  (`:1780-1795`), returns a **no-copy** `Uint8Array` over the mapping via
  `make_typed_array_with_bytes_no_copy` (`:1850-1859`), munmaps on GC.
  macOS is supported (only Windows throws, `:1737-1741`).
- Caveat: the underlying `bun_sys::mmap_file`
  (`src/sys/lib.rs:3385-3421`) always opens `O_RDWR` and maps
  `PROT_READ | PROT_WRITE`. There is no read-only mapping mode, so the file
  must be writable by the child and a buggy/hostile child can scribble into
  the shared file. A per-child copy-on-write private view (`shared: false`
  → `MAP_PRIVATE`) still requires the O_RDWR open. Treating this as a
  containment-grade read-only medium would need a Bun-side `PROT_READ`
  option or file-permission gymnastics.
- What it would save: N children mapping the same projection/transit file
  share one set of resident page-cache pages for the **encoded bytes**.
  But the encoded committed projection is ≤6 MB on the wire today
  (`admission.cljs:246`), and the moment a child decodes a slice into CLJS
  data + compiled Malli validators, that materialization is private heap
  again. mmap converts ~6 MB/child of transient shipping into shared
  pages; it cannot touch the ~85+ MB of materialized projection unless the
  child also stops materializing eagerly. Lazy per-slice decode is a
  Seon-side redesign for which mmap is merely the transport; the savings
  come from the laziness, not the mapping.

## 4. Bytecode/compile caches: exist, but attack a cost the bisect already cleared

- `--bytecode` is a **`bun build`** option (`src/cli/Arguments.rs:438`,
  `:1859`), constrained to `target=bun` (`:2003-2006`) and to CJS unless
  `--compile` (`:2340-2352` — "ESM bytecode requires --compile").
  Generation goes through JSC (`src/jsc/CachedBytecode.rs:12-33`).
- At runtime the parser detects a `@bytecode` pragma in an already-bundled
  file (`src/js_parser/parse/parse_entry.rs:2340-2377`), the adjacent
  bytecode is carried as a heap `Box<[u8]>`
  (`src/runtime/jsc_hooks.rs:2704-2735`), and
  `ZigSourceProvider.cpp:117-141` wraps it in `JSC::CachedBytecode` freed
  with `defaultAllocatorFree` — i.e. the runtime bytecode is **private
  heap, not an mmapped file**; the no-op destructor path is only for
  `--compile` standalone binaries whose graph lives in the executable
  image. So N children do not page-cache-share runtime bytecode.
- More decisively: the bisect already measured that parse/compile is not
  the band. The 7.5 MB `:simple` release bundle (one parse) loads to
  104.6 MB — *more* than the 933-file dev artifact — and
  `BUN_JSC_forceRAMSize=64Mi` cutting load 89→76 MB shows a further share
  is GC headroom. The ~85–100 MB is executed-top-level heap. Bytecode
  caching could shave child startup CPU/latency (skip parsing 7.5 MB of
  JS), worth a bounded experiment for spawn latency only — expect ~0 MB
  steady-state memory change. There is no automatic disk bytecode cache
  for ordinary module loads; the `RuntimeTranspilerCache`
  (`src/ast/transpiler_cache.rs:1-33`) caches **transpiled JS source**, not
  bytecode, and its output is likewise per-process heap.

## 5. posix_spawn / fork prewarm: impossible, definitively

- The only spawn mechanism in the tree is `posix_spawn(2)`
  (`src/spawn_sys/lib.rs:7-9`; the actual call at
  `src/spawn_sys/spawn_process.rs:1128`). There is no fork-and-continue
  API anywhere in `src/`.
- Fork-after-warm cannot be built either: VM construction **eagerly spawns
  the concurrent JIT worklist thread and the Heap parallel-marking
  helpers** (`src/jsc/bindings/ZigGlobalObject.cpp:320-329` documents
  exactly these threads while gating them off for one-shot `bun -e`).
  `fork(2)` preserves only the calling thread; the forked child would hold
  JSC GC/JIT locks owned by threads that no longer exist, plus mimalloc's
  per-thread heaps and kqueue/dispatch state. A warmed-parent fork model
  (the classic Zygote) is unreachable on this runtime. Do not propose it
  again.

## 6. IPC serialization: JSC advanced mode; shipping vs re-querying

- `Bun.spawn({ipc})` defaults to `Mode::Advanced`
  (`src/runtime/api/bun/js_bun_spawn_bindings.rs:520-546`, default at
  `:545`; env `NODE_CHANNEL_SERIALIZATION_MODE=advanced` at `:1019`),
  which is JSC `SerializedScriptValue` with length-prefixed frames
  (`src/jsc/ipc.rs:202-217` — "Only valid for bun <--> bun"; serialize at
  `:400-411` with `for_cross_process_transfer: true`). `Mode::Json` exists
  but is for node:cluster compatibility.
- Seon ships transit-encoded **strings** through this channel
  (`host.cljs` `send-message!` :116-117 over
  `execution/encode-message`), so each message costs: transit encode
  (parent) → JSC string serialize → copy through the pipe → JSC
  deserialize → transit decode into fresh CLJS structures (child). Shipping
  the whole committed projection over IPC instead of the child re-querying
  would not share anything — it would land as exactly the same private
  materialized graph, minus the database session's read path. The current
  design (wire carries database coordinates; child queries within ≤6 MB
  result-weight caps) is already the cheaper shape.

## Ranked levers at N=100 children

| # | Lever | Saved MB/child (N=100 total) | Cost | Containment impact |
|---|---|---|---|---|
| 1 | `BUN_JSC_forceRAMSize` spawn-env (+ mimalloc purge tuning, `MIMALLOC_OS_TAG` labels) | ~13 at load, and bounds the unbounded burst band (~200 worst-case) → 1.3–20 GB | one spawn-env line + turn-scale measurement | none |
| 2 | Shrink the child require closure (child needs eval + render + db session, not the pod graph) | unknown fraction of ~85–100; audit first | PRD-level | none |
| 3 | Lazy projection materialization: compile Malli validators on first use instead of `m/schema` over the whole registry at admission (`schema.cljc:307-315`) | large fraction of ~91 (eager compile + indexes) | Seon-side redesign of `build-projection`/`activate-projection!`; must keep wrapper-verification semantics | none |
| 4 | Bytecode via `bun build --bytecode` (CJS) for the child artifact | ~0 steady memory; startup CPU/latency only | bounded experiment in release artifact machinery | none |
| 5 | `Bun.mmap`-shared projection/blob bytes | ≤6/child for the projection (wire is already capped there); real only for future large read-mostly byte tiers (blobs) | small, but needs a read-only story (`mmap_file` is `O_RDWR` + `PROT_WRITE`, `src/sys/lib.rs:3385-3421`) | shared writable file unless Bun grows `PROT_READ` mode |
| 6 | Workers instead of processes | ~6 (process floor only; both big bands recur per worker VM) | medium | **negative**: cooperative-only termination, no memory caps (`worker_threads.ts:320` stub), shared crash domain |

Levers 1–2 are the bisect's existing follow-ups, unchanged by this audit.
Lever 3 is the one genuinely new shared-memory-adjacent finding: the
duplication is eager computation, so the fix is laziness, not sharing.

## Impossible — do not re-propose

- **Sharing live JS object graphs** (CLJS data, Malli validators, compiled
  program state) between processes or Workers, by any mechanism: JSC heaps
  are strictly per-VM (`web_worker.rs:834-931`), SAB is bytes-only and
  in-process-only (`SerializedScriptValue.cpp:1850-1864`,
  `ipc.rs:409-411`), and IPC/structuredClone always copy.
- **fork-after-warm / zygote children**: VM construction eagerly spawns
  concurrent JIT and GC marker threads (`ZigGlobalObject.cpp:320-329`);
  only `posix_spawn` exists (`spawn_process.rs:1128`).
- **Per-Worker memory caps** via `resourceLimits`: a stub
  (`worker_threads.ts:320`, `:1375`).
- **Page-cache-shared runtime bytecode**: runtime bytecode is a private
  heap `Box<[u8]>` (`jsc_hooks.rs:2704-2735`,
  `ZigSourceProvider.cpp:117-141`); and the load band is executed-heap,
  not parse, so it would not help even if shared.
- **Cross-process SharedArrayBuffer**: no mechanism in the tree.
