---
type: research
status: active
tags: [research, agent, web, flow]
---

# Co-located oracle package — shape, runtime, Python↔CLJS API, eval tier (2026-06-28)

## TL;DR

- **Recommendation: persistent Node sidecar (option a). Do NOT revive
  GraalVM (option b).** There is nothing to revive — no clojure-python /
  GraalPy polyglot work exists in this repo (only a super-repl *wishlist*
  checkbox `docs/prds/super-repl/prd.md:602` and the libdatahike
  native-image spike, a different use). The oracle code is
  **ClojureScript** (parser is `.cljc`, the eval cage is CLJS+SCI), so the
  GraalVM path would force a parallel **JVM-Clojure reimplementation** of
  the same oracle — two code paths for one job, banned by "Slow Is Fast."
  And the repo's own sidecar-spike flags GraalVM Substrate-VM
  **signal-handler cohabitation crashes** (SIGSEGV/SIGBUS) when sharing a
  process with another VM
  (`docs/prds/agent-runtime/sidecar-spike/prd.md:21,211`) — putting a
  GraalVM isolate in-process with PyTorch/CPython on the A100 is exactly
  that failure class, and a worker crash costs a ~66 s model reload. The
  Node sidecar is **strictly better for our needs**: it reuses the
  existing shadow-cljs `:node-script` build, the proven SCI eval mechanism,
  adds only a Node binary to the image, and the ~50–100 µs IPC tax it pays
  is noise against the ~100 ms internet round-trip we are killing.

- **The package = an `op`-dispatched flexible oracle, layered ON TOP of
  a378adfa's lean `seon.worker-validator` — not a duplicate.** One JSON
  envelope `{op, …}` per stdin line dispatches to handlers:
  `parse`/`validate` (delegates to the EXISTING `validate`), `eval` (new,
  SCI), `retrieve` (later seam). "Whatever code we need" = register a new
  op handler in the dispatch map. The lean parse-only bundle stays for
  callers that want the smallest/fastest cold start; the oracle bundle is a
  **superset entry that REUSES `validate`**, never reimplements it.

- **Eval tier = bare `sci.core` + `sci.interrupt`, DB-free.** The full
  `seon.eval` cage is too heavy (pulls cljs.js self-host, the analyzer,
  `seon.db`, the program graph, instrumentation), and even
  `seon.render.sci` is coupled to `seon.db`/`seon.eval`
  (`src/seon/render/sci.cljs:69-70` — it reconstitutes agent-ns source from
  the DB). The MINIMAL lean eval is the 8-line smoke pattern already proven
  in that file (`src/seon/render/sci.cljs:149-156`): `sci/init
  {:interrupt-fn <wall-clock-deadline>}` + `sci/eval-string*`. That makes
  the structural oracle a **correctness** oracle ("does the generated form
  actually RUN, no unbound var / arity error / throw, inside a budget")
  with no pod state, ~0.2 ms warm.

- **Transport: keep a378adfa's stdio `--serve` line protocol** (one JSON
  object per line; embedded newlines survive as `\n` inside the JSON
  string) for the sequential single-client denoise loop — it is optimal
  there. Promote to **loopback UDS** only when a *second concurrent* caller
  appears (e.g. an out-of-band retrieve while a denoise stream is mid-flight).
  Do NOT add localhost HTTP — no benefit over UDS, more framing overhead.

---

## 1. Grounding — what already exists (cite-checked 2026-06-28)

### The lean parse tier is BUILT (a378adfa landed it)

`src/seon/worker_validator.cljs` (154 lines) + the `:worker-validator`
shadow target (`shadow-cljs.edn:169-176`) already ship the parse/syntactic
tier:

- `validate` (`worker_validator.cljs`) — a PURE fn `code → {:forms n :tier
  :parse :errors [{:error-kind :span :source}]}`. It calls
  `seon.repl.internal/parse-forms`.
- `parse-forms` (`src/seon/repl/internal.cljc:561`, signature
  `:malli/schema [:=> [:cat :string] …]`) requires only `clojure.string` +
  `rewrite-clj` (`internal.cljc:75-78`) — pure CLJC, no DB, no malli
  instrumentation, no pod state. Measured ~366 µs / parse (per the
  diffusion CLAUDE.md + colocation note).
- Errors already carry the **char span** (`:span [start end]`, absolute
  offsets) and the classified `:error-kind` — exactly what the Python side
  maps to canvas token positions via `build_offset_map`/`span_to_positions`.
- Wire: `--serve` runs a persistent `readline` line server
  (`worker_validator.cljs` `serve!`) — one JSON-encoded code string in per
  line, one JSON result line out. One-shot mode (no flag) reads all of fd 0
  and exits. The `:tier` field + the explicit **"EVAL-TIER SEAM"** docstring
  note anticipate this design.

**This design does NOT touch that file.** It composes around it.

### The eval mechanism is ALSO proven — but the existing wrapper is pod-coupled

`src/seon/render/sci.cljs` runs **agent-authored CLJS forms under SCI** with
a wall-clock `:interrupt-fn` that throws an un-catchable, un-forgeable
interrupt — proven on Node/CLJS, ~0.2 ms warm, survives a hostile
`try/catch` (docstring `sci.cljs:1-34`). BUT it requires `seon.db` +
`seon.eval` (`sci.cljs:69-70`) because it reconstitutes the agent ns's
stored source from the DB and resolves vars through the program graph. That
coupling is wrong for the worker (no DB there). **The reusable core is the
two leaf libs it sits on** — `sci.core` + `sci.interrupt` — and the
self-contained smoke at `sci.cljs:149-156`:

```clojure
(let [c (sci/init {:interrupt-fn deadline-interrupt-fn :classes base-classes})]
  (sci/eval-string* c "<form string>"))   ; throws on a tripped deadline
```

SCI 0.13.53 is already on the pod's `:cljs` alias (`deps.edn:329`), and it
is a pure interpreter — it does **not** pull `cljs.js`, the analyzer, or the
program graph. Adding it to the worker bundle costs the interpreter (tens of
KB compiled), not the ~MB self-host compiler.

### The deployment vehicle is the custom worker image

Per `custom-image-and-seon-colocation-2026-06-28.md`: the custom image =
`runpod/flash` base (torch 2.9.1 stock, works) + transformers 5.11.0 + the
model + **Node + the seon CLJS bundle** as the new layer. The Python
`gpu_worker.py` calls the LOCAL bundle between denoise steps. The pod is
already loopback-only, so co-location makes that a feature, not a blocker.

### GraalVM in this repo — what's actually there

`grep -rilE 'graal|polyglot|libpython|clojure-python'` over `docs/` + `src/`
returns **no clojure-python / GraalPy polyglot implementation**. The hits:

- `docs/prds/super-repl/prd.md:602` — an **unchecked wishlist** box:
  "Multi-language dispatch (Python via libpython-clj, JS via GraalJS)." Not
  built.
- `docs/prds/agent-runtime/sidecar-spike/` — libdatahike compiled via
  GraalVM `native-image` to a C-ABI shared lib, addressed **out-of-process**
  by a Rust sidecar over UDS. Its load-bearing finding is the case AGAINST
  in-process polyglot: a GraalVM Substrate VM and another runtime in the
  **same process** fight over signal handlers → SIGSEGV/SIGBUS
  (`sidecar-spike/prd.md:21`, `:211`). Their own conclusion was "sidecar
  shape is mandatory."

So "revive the GraalVM clojure-python work" = build it **from scratch**,
against the repo's own evidence that in-process Substrate-VM cohabitation
crashes.

---

## 2. Runtime / deployment mechanism — decision

| Option | Per-call latency | Cold start | New deps on image | Code-path risk | Crash blast radius |
|---|---|---|---|---|---|
| **(a) Persistent Node sidecar** *(RECOMMEND)* | ~0.1–0.4 ms (parse 366 µs, SCI eval ~0.2 ms) + ~50–100 µs IPC | ~100 ms bundle load, paid ONCE at worker warm-up | Node binary + one JS bundle | **None** — reuses CLJS oracle verbatim | Sidecar crash ≠ model crash; Python respawns it |
| (b) GraalVM clojure-python polyglot | ~0 IPC (in-process) | GraalVM JDK + GraalPy startup; native-image build | GraalVM JDK + GraalPy + a from-scratch polyglot bridge | **High** — forces a parallel JVM-Clojure reimpl of CLJS oracle (two code paths) | In-process with PyTorch/CPython → Substrate-VM signal cohabitation SIGSEGV (repo-proven), takes the 66 s model load down with it |
| (c) Subprocess-per-call | parse + ~100 ms node spawn EVERY call | n/a | Node binary + bundle | None | n/a |

**(c) is disqualified for the hot loop** — its own docstring says a fresh
node spawn is ~100 ms, "NO win over an internet round-trip"
(`worker_validator.cljs`). Keep it only as the test/one-shot mode (already
the default in the bundle).

**(b) is disqualified on three independent grounds**, any one sufficient:

1. **Nothing to revive + wrong language.** No GraalPy polyglot exists here,
   and the oracle is CLJS. GraalVM polyglot runs **JVM** Clojure. We would
   reimplement `validate` + the SCI eval on the JVM and maintain it in
   lock-step with the CLJS pod — the exact "v2 / parallel namespace to house
   a fix" anti-pattern CLAUDE.md bans. `parse-forms` is `.cljc` so it ports,
   but the whole point of co-location is to run *the same oracle the pod
   runs*; a JVM fork drifts.
2. **Crash risk, repo-proven.** In-process GraalVM Substrate VM + CPython +
   PyTorch is precisely the signal-handler cohabitation the sidecar-spike
   found fatal (`sidecar-spike/prd.md:21`). On a GPU worker a crash =
   re-pull the 50 GB model, ~66 s. The whole feature exists to *reduce* loop
   cost; a crash-prone runtime defeats it.
3. **The IPC tax it removes is noise.** (a)'s ~50–100 µs UDS/stdio tax is
   <0.1 % of the ~100 ms internet hop being eliminated, and <50 % of the
   parse itself. There is no latency budget that (b) unlocks and (a) misses.

**Verdict: persistent Node sidecar. GraalVM is not worth reviving — the
Node-sidecar Python-API path is strictly better for this workload.** The
"easier Python API" the owner anticipated is the correct one on the merits,
not just on effort.

---

## 3. Package shape — the flexible op-dispatched oracle

### Layering (no duplication)

```
seon.worker.oracle        ; NEW — the flexible entry: op-dispatch + --serve loop
  ├─ requires seon.worker-validator   ; REUSE `validate` (parse tier) — unchanged
  ├─ requires seon.worker.eval        ; NEW leaf — bare SCI eval, DB-free
  └─ dispatch map {op-kw → handler}   ; the "whatever code we need" seam

seon.worker-validator     ; a378adfa's lean leaf — parse only, stays as-is
  └─ requires seon.repl.internal → clojure.string + rewrite-clj  ; pure cljc

seon.worker.eval          ; NEW leaf — requires ONLY sci.core + sci.interrupt
                          ;   (NOT seon.db / seon.eval / seon.render.sci — those are pod-coupled)
```

Two shadow targets, both `:node-script`, `:optimizations :simple`,
`:devtools {:enabled false}` (mirroring the validator block,
`shadow-cljs.edn:169-176`):

- `:worker-validator` (exists) — parse-only, smallest bundle, fastest cold
  start. Keep for callers that never eval.
- `:worker-oracle` (new) — `:main seon.worker.oracle/-main`. Parse + eval +
  the retrieve seam. ~one SCI interpreter heavier; still no cljs.js.

This is **layering, not a v2**: `oracle` calls `validate` and the SCI
`eval-form` — there is exactly one of each fn in the tree. The lean target
remaining is a deliberate "smallest possible parse oracle" artifact, not a
stale duplicate.

### The op surface (v1)

| `op` | handler | request fields | response |
|---|---|---|---|
| `"parse"` (alias `"validate"`) | `seon.worker-validator/validate` (REUSED) | `code` | `{op, id?, forms, tier:"parse", errors:[{error-kind, span, source}]}` |
| `"eval"` | `seon.worker.eval/eval-form` (NEW) | `code`, `budget-ms?` (dflt 50) | `{op, id?, ok, value? , error?{kind, message}}` |
| `"retrieve"` | seam — `nil` handler for now → `{op, id?, error:{kind:"unimplemented"}}` | `query`, `k?` | later: `{op, id?, hits:[…]}` |

The dispatch is a plain map `op → (fn [req] resp-map)`. Registering "whatever
code we need" = `assoc` a new op handler. That IS the extensibility the owner
asked for — no new mechanism, just another pure handler.

### Pure, DB-free, stateless on the worker

Every handler is a pure fn of its request map. No pod, no datahike, no
`*ctx*`, no globalThis program graph. The eval handler builds a fresh
bounded SCI ctx (or reuses one warm ctx; see §5). State that *is* allowed: a
single warm SCI ctx + a single readline loop — runtime artifacts, not domain
state.

---

## 4. Python ↔ CLJS API (concrete)

### Wire: one JSON object per line, both directions

Evolve a378adfa's `--serve` (which currently sends a bare JSON **string** per
line) to one JSON **object** per line. Embedded newlines in `code` still
survive — they are `\n`-escaped inside the JSON string value. This is the
*minimal* change that makes the same pipe carry `op`-dispatched requests.

**Request (worker → sidecar), one line:**

```json
{"op":"parse","id":42,"code":"(defn mean [xs] (/ (reduce + xs) (count xs"}
```

**Response (sidecar → worker), one line:**

```json
{"op":"parse","id":42,"forms":0,"tier":"parse",
 "errors":[{"error-kind":"eof","span":[0,46],"source":"(defn mean [xs] (/ (reduce + xs) (count xs"}]}
```

```json
{"op":"eval","id":43,"ok":false,"error":{"kind":"throw","message":"Wrong number of args (1) passed to: mean"}}
```

`id` is echoed so the worker can correlate; for the strictly sequential
denoise loop (one checkpoint at a time, one in-flight request) it is
optional. `error-kind` / `tier` are keyword **names** (strings) — the
boundary flattens keywords via the existing `->js` discipline
(`worker_validator.cljs` `->js`).

### How the Python worker drives it (between denoise steps)

Spawn ONCE at worker warm-up (in the Flash `@Endpoint.__init__`, alongside
the model load), reuse for every checkpoint, terminate on shutdown:

```python
import json, subprocess

class Oracle:
    def __init__(self):
        self.p = subprocess.Popen(
            ["node", "/opt/seon/oracle.js", "--serve"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, bufsize=1)            # line-buffered

    def call(self, op, code, **opts):
        req = {"op": op, "code": code, **opts}
        self.p.stdin.write(json.dumps(req) + "\n"); self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def close(self):
        self.p.terminate()

# in the denoise loop, per checkpoint:
r = oracle.call("parse", canvas_text)          # ~0.4 ms, local
if r["errors"]:
    positions = span_to_positions(r["errors"], offset_map)   # renoise targets
    # ... re-noise those canvas positions, continue denoising ...
elif oracle.call("eval", canvas_text, **{"budget-ms": 50})["ok"] is False:
    # structurally valid but does not RUN → renoise the whole form
    ...
```

Sequential pipe, one in-flight request — head-of-line blocking is a
non-issue because the denoise loop is itself sequential.

### Bundling onto the worker image (the Node layer)

In CI, before the docker build:

```bash
clj -M:cljs compile worker-oracle      # → out/worker-oracle/main.js (one file)
```

In `build-image.sh` / the Dockerfile (the Seon layer on top of the validated
base — `custom-image-and-seon-colocation-2026-06-28.md` keeps that base):

```dockerfile
# Node runtime (LTS); the bundle is plain CJS (:node-script), no node_modules needed
RUN apt-get update && apt-get install -y --no-install-recommends nodejs && rm -rf /var/lib/apt/lists/*
COPY out/worker-oracle/main.js /opt/seon/oracle.js
```

The `:node-script` bundle inlines `rewrite-clj` + SCI; `require("fs")` /
`require("readline")` are Node builtins, so **no `npm install`** on the
image. Image growth is the Node binary (~50 MB) + the JS bundle (small) —
trivial against the 15 GB torch image, and far cheaper than a GraalVM JDK.

### Promotion path: stdio → UDS (only when needed)

If a second concurrent caller appears (e.g. a retrieve fired out-of-band
while a denoise stream is mid-flight, or multiple model streams share one
sidecar), switch the transport to a **loopback Unix domain socket**: the
bundle opens `net.createServer` on `/tmp/seon-oracle.sock`, frames the SAME
`{op,…}` JSON per line, and Python connects with `socket.AF_UNIX`. The op
envelope is transport-agnostic, so this is a transport swap with zero handler
changes. Do not reach for HTTP — UDS gives concurrency + request ids with
less overhead and no port management.

---

## 5. Eval tier — SCI, lean and bounded

### Why SCI, not the cage and not `seon.render.sci`

- `seon.eval/eval` (`src/seon/eval.cljs:970`) is the full pod cage: it pulls
  `cljs.js` (self-host compiler), `cljs.analyzer`, `seon.db`, `seon.schema`,
  `seon.instrument`, the analyzer-info/program-graph, and `test.runner`
  (`eval.cljs:36-58`). Megabytes, DB-coupled, instrumented — wrong for a
  stateless oracle.
- `seon.render.sci` is closer (it IS SCI) but requires `seon.db` +
  `seon.eval` to reconstitute agent-ns source from the DB
  (`sci.cljs:69-70`). The worker has no DB.
- The **minimal** eval is the two leaf libs both of those sit on —
  `sci.core` + `sci.interrupt` — exactly the self-contained smoke at
  `src/seon/render/sci.cljs:149-156`. No DB, no analyzer, no compile step.

### `seon.worker.eval/eval-form` (proposed shape)

```clojure
(ns seon.worker.eval
  "Lean, DB-free SCI eval for the co-located oracle. Turns the structural
   parse oracle into a CORRECTNESS oracle: does the generated form actually
   RUN — no unbound var, no arity error, no throw — within a wall-clock
   budget. Requires ONLY sci.core + sci.interrupt (NOT seon.db/seon.eval —
   those are pod-coupled; see src/seon/render/sci.cljs:69-70)."
  (:require [sci.core :as sci]
            [sci.interrupt :as interrupt]))

(defn- deadline-interrupt-fn [deadline]
  ;; fires at the top of every interpreted fn/loop entry — un-catchable
  (fn [] (when (> (js/Date.now) deadline) (interrupt/check-interrupted))))
  ;; (exact interrupt call mirrors render.sci:134-147; ground against that)

(defn eval-form
  "Pure: eval `code` under a fresh bounded SCI ctx. Returns
   {:ok true :value <pr-str>} | {:ok false :error {:kind :throw|:interrupt|:parse :message s}}."
  [code budget-ms]
  (let [deadline (+ (js/Date.now) (or budget-ms 50))
        c (sci/init {:interrupt-fn (deadline-interrupt-fn deadline)})]
    (try {:ok true :value (pr-str (sci/eval-string* c code))}
         (catch :default e
           {:ok false :error {:kind (if (interrupt-ex? e) :interrupt :throw)
                              :message (ex-message e)}}))))
```

(`interrupt-ex?` mirrors `render.sci:161` — the interpreter may re-wrap the
interrupt as a `:sci/error`; classify it. Ground the exact `sci.interrupt`
call against `render.sci` before writing.)

### What the eval tier proves (honest scope)

SCI is an **interpreter with its own namespace/var model** — it does NOT
have the pod's program-graph fns, the agent's prior defs, or malli
instrumentation. That is the right scope for a correctness oracle: it answers
**"is this a runnable, self-contained, non-throwing Clojure form within a
budget"** (catches unbound symbols, arity errors, type errors, divide
patterns, non-termination via the interrupt) — which is precisely the
renoise control signal "does the code RUN," not "does it behave bit-identical
to the instrumented pod." Flagged explicitly so nobody expects pod parity.
If a future tier needs real pod semantics, that is a *remote* call back to
the pod (rare, like retrieval), not an in-loop local one.

### Cost

Parse tier ~0.4 ms. `sci/init` is the eval-tier cost; reuse ONE warm ctx
across calls to amortize it (re-`init` only if a prior eval mutated it in a
way that matters — for read-only correctness checks a fresh `init` per call
is also fine and simplest). Warm SCI eval is ~0.2 ms (proven, `sci.cljs`
docstring). Both tiers stay sub-millisecond — the co-location win holds.

---

## 6. Composition with a378adfa's validator (explicit)

- The `parse`/`validate` op calls `seon.worker-validator/validate`
  **verbatim**. The validator's `:tier :parse` field and its EVAL-TIER SEAM
  docstring were written for exactly this — the oracle realizes the seam by
  *adding a sibling handler*, not by editing `validate`.
- The oracle's `--serve` loop generalizes the validator's `serve!` (same
  `readline` line server) to read a JSON **object** and dispatch on `op`,
  defaulting a bare-string line to `{:op "parse" :code <string>}` so the
  current Python caller keeps working through the transition.
- The lean `:worker-validator` target stays as the minimal parse-only
  artifact. The `:worker-oracle` target is the flexible superset. One
  `validate`, one `eval-form`, one parser — no parallel implementations.

### Suggested next steps (build order, owned by an impl lane — not done here)

1. Add `seon.worker.eval` (bare SCI, ~30 lines) + a unit test
   (`eval-form` on a good form → `{:ok true}`; on `(/ 1 0)` /
   `(undefined-fn)` / `(loop [] (recur))` → the three error kinds).
2. Add `seon.worker.oracle` (op-dispatch + the generalized `--serve` loop)
   + the `:worker-oracle` shadow target.
3. CI: `clj -M:cljs compile worker-oracle`; Dockerfile Node layer + `COPY`.
4. Python `Oracle` class in `gpu_worker.py` (§4); wire `parse` then `eval`
   into the denoise checkpoint.
5. `retrieve` seam stays a stub until the retrieval-denoising experiment
   needs it — decide then whether the HNSW index is co-located or a remote
   knn call (the one round-trip we may keep, since retrieval fires rarely).

---

## Pointers

- [[custom-image-and-seon-colocation-2026-06-28]] — the latency play this
  realizes; the custom-image layering.
- `src/seon/worker_validator.cljs` + `shadow-cljs.edn:150-176` — a378adfa's
  lean parse tier this layers on.
- `src/seon/repl/internal.cljc:561` — `parse-forms` (the parser; cljc, lean).
- `src/seon/render/sci.cljs:149-156` — the proven bare-SCI eval+interrupt
  pattern the eval tier copies (DB-free slice of it).
- `src/seon/eval.cljs:36-58,970` — the full cage, documented here as
  TOO HEAVY for the worker.
- `docs/prds/agent-runtime/sidecar-spike/prd.md:21,211` — the repo evidence
  that in-process GraalVM Substrate-VM cohabitation crashes.
- `docs/prds/super-repl/prd.md:602` — the only "GraalVM/libpython" mention:
  an unchecked wishlist box, not built.
