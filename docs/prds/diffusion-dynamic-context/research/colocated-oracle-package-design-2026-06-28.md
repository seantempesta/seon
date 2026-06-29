---
type: research
status: active
tags: [research, agent, web, flow]
---

# Co-located oracle package — shape, runtime, Python↔CLJS API, eval tier (2026-06-28)

## TL;DR

- **Recommendation, split by tier (the runtime is a persistent SIDECAR
  either way — NOT GraalVM):**
  - **PARSE tier → persistent BABASHKA (bb) server.** PROVEN feasible:
    `bin/test-parser` already runs the `parse-forms` test suite under
    `bb --classpath src:test` on bb's built-in rewrite-clj — NO shadow
    build, NO Node, NO pod. A persistent bb process is the simplest
    deployable that does the immediate, hot, per-checkpoint job: a single
    native binary (~10–30 ms cold, ~0.1 ms warm) + the `.cljc` source on the
    classpath, NO compile step. And because `parse-forms` is **purely
    structural** (rewrite-clj read + error-span classification, no
    semantics), bb parses CLJS-flavored canvas forms **bit-identically to the
    pod** — zero fidelity loss. bb likely SUPERSEDES a378adfa's Node
    `:worker-validator` as the parse deployable; the Node bundle stays valid
    and is the right call only if eval also lives in Node (one artifact).
  - **EVAL tier → the CLJS path (a378adfa's bundle / cljs.js), reserved for
    FAITHFUL CLJS.** bb's built-in SCI evals **Clojure** semantics — a good
    proxy for syntactic + most semantic correctness, but NOT true CLJS: js
    interop (`(.json x)`), `^:async`/`await`, and pod behavior won't eval
    faithfully, and the worker emits CLJS-flavored code. So eval fidelity is
    tiered: an SCI-class proxy for the fast "does it run" check, upgrading to
    **cljs.js self-host (Node)** when the form needs real CLJS semantics
    (interop/async). Which tier you need is driven by what the worker
    generates (§5).
- **Do NOT revive GraalVM (option b).** There is nothing to revive — no
  clojure-python / GraalPy polyglot work exists in this repo (only a
  super-repl *wishlist* checkbox `docs/prds/super-repl/prd.md:602` and the
  libdatahike native-image spike, a different use). The oracle code is
  **ClojureScript** (parser is `.cljc`, the faithful eval is CLJS), so the
  GraalVM path would force a parallel **JVM-Clojure reimplementation** of the
  same oracle — two code paths for one job, banned by "Slow Is Fast." And the
  repo's own sidecar-spike flags GraalVM Substrate-VM **signal-handler
  cohabitation crashes** (SIGSEGV/SIGBUS) when sharing a process with another
  VM (`docs/prds/agent-runtime/sidecar-spike/prd.md:21,211`) — putting a
  GraalVM isolate in-process with PyTorch/CPython on the A100 is exactly that
  failure class, and a worker crash costs a ~66 s model reload. The ~50–100 µs
  IPC tax a persistent sidecar (bb OR Node) pays is noise against the ~100 ms
  internet round-trip we are killing — GraalVM's only edge (zero IPC) buys
  nothing.

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

### Babashka runs `parse-forms` TODAY — `bin/test-parser`

`bin/test-parser` (in the repo) is a bb runner for the parse-forms test ns:

```bash
exec bb --classpath src:test -e '(require (quote seon.repl.internal-test)) …'
```

Its own header: *"seon.repl.internal is pure CLJC (rewrite-clj +
clojure.string only), so its test ns runs on babashka's built-in rewrite-clj
with NO shadow build and NO pod."* Verified live: `bb` is installed
(v1.12.212), and `parse-forms` (`seon.repl.internal`) loads under it from the
`.cljc` source with **no compile step**. So a bb parse-oracle is **proven
feasible, not speculative.** bb also bundles **SCI**, so one bb process can
do BOTH parse (rewrite-clj) AND a Clojure-semantics eval.

---

## 2. Runtime / deployment mechanism — decision

Four candidates. The axes that matter: cold start, deploy simplicity,
**parse fidelity** (does it read CLJS-flavored forms like the pod?),
**eval fidelity** (does it run them with real CLJS semantics?), and crash
blast radius next to PyTorch on the GPU.

| Option | Cold start | Warm per-call | Deploy artifact | Parse fidelity | Eval fidelity | Crash blast radius |
|---|---|---|---|---|---|---|
| **bb persistent server** *(RECOMMEND — parse tier)* | ~10–30 ms (native binary) | ~0.1 ms | **single `bb` binary + `.cljc` source on classpath — NO build** | **Exact** (same rewrite-clj; structural, language-agnostic; `bin/test-parser`-proven) | **Clojure** proxy via bb-SCI — NOT true CLJS (no js interop / `^:async`) | bb crash ≠ model crash; Python respawns |
| **Node sidecar** *(RECOMMEND — faithful eval tier)* | ~100 ms bundle load (once) | ~0.1–0.4 ms | Node binary + `clj -M:cljs compile` bundle | Exact (same `parse-forms`, compiled) | **cljs.js self-host = TRUE CLJS**; or cljs-SCI proxy | Sidecar crash ≠ model crash; Python respawns |
| (b) GraalVM polyglot | GraalVM JDK + GraalPy; native-image build | ~0 IPC (in-process) | GraalVM JDK + GraalPy + from-scratch bridge | needs JVM reimpl | JVM Clojure (also not CLJS) | **In-process w/ PyTorch/CPython → Substrate-VM signal SIGSEGV (repo-proven); takes the 66 s model load down** |
| (c) Subprocess-per-call | spawn EVERY call (~10–100 ms) | — | bb or Node | Exact | per chosen runtime | n/a |

**(c) is disqualified for the hot loop** — a per-call spawn ( `worker_validator.cljs`
notes ~100 ms for the Node bundle; bb is cheaper at ~10–30 ms but still a
per-call tax) negates the co-location win. Keep it only as a test/one-shot
mode (already the bundle's default).

**(b) is disqualified on three independent grounds**, any one sufficient:

1. **Nothing to revive + wrong language.** No GraalPy polyglot exists here,
   and the faithful oracle is CLJS. GraalVM polyglot runs **JVM** Clojure. We
   would reimplement `validate` + eval on the JVM and maintain it in
   lock-step with the CLJS pod — the exact "v2 / parallel namespace to house
   a fix" anti-pattern CLAUDE.md bans. (Note: GraalVM's eval would be JVM
   Clojure, which is *also not CLJS* — it has the same interop/async
   infidelity as bb-SCI, with none of bb's simplicity.)
2. **Crash risk, repo-proven.** In-process GraalVM Substrate VM + CPython +
   PyTorch is precisely the signal-handler cohabitation the sidecar-spike
   found fatal (`sidecar-spike/prd.md:21`). On a GPU worker a crash =
   re-pull the 50 GB model, ~66 s.
3. **The IPC tax it removes is noise.** A persistent sidecar's ~50–100 µs
   stdio/UDS tax is <0.1 % of the ~100 ms internet hop being eliminated.
   There is no latency budget (b) unlocks that a sidecar misses.

### The split recommendation — bb for parse, CLJS for faithful eval

The parse tier and the eval tier have **opposite** fidelity requirements, and
that is what decides the runtime:

- **Parse tier → bb.** Parsing is purely structural — rewrite-clj reads the
  s-expression shape and classifies read errors; it does NOT interpret
  semantics, so it reads CLJS-flavored canvas forms **identically** to the
  pod (and `bin/test-parser` proves the exact test suite passes under bb).
  There is therefore **zero fidelity cost** to running parse on bb, and bb is
  the **simplest possible deployable**: one native binary + the `.cljc`
  source, no shadow build, no Node, ~10–30 ms cold / ~0.1 ms warm persistent.
  For the immediate, hot, per-checkpoint job (which is parse-only today —
  spans → renoise positions), **bb wins on simplicity + proven-feasibility**
  and supersedes the Node `:worker-validator` as the parse deployable.

- **Eval tier → the CLJS path (Node).** The eval tier is a *correctness*
  oracle for the CLJS the worker emits, so fidelity matters. bb-SCI evals
  **Clojure**, which mishandles exactly the CLJS-specific surface the worker
  produces: js interop (`(.json x)`, `(.-foo o)`), `^:async`/`await`,
  CLJS-only core behavior. Those would throw under bb-SCI as *false
  negatives* on valid CLJS — poisoning the control signal. **cljs.js
  self-host (Node) is the only path with true CLJS semantics**, so the
  faithful eval tier lives in Node. (A cljs-SCI proxy in the Node bundle is a
  lighter middle option — better interop than bb-SCI, still an interpreter,
  still no async — usable as a fast pre-filter; §5.)

- **Hybrid, if eval-fidelity proves to bite:** bb as the always-warm front
  door (parse + a cheap Clojure-proxy eval pre-filter), shelling out to a
  Node cljs.js process ONLY when a faithful CLJS eval is required. Costs two
  runtimes on the image — adopt only if the proxy's false-negative rate on
  real generated forms is measured and unacceptable. Default: don't; pick the
  one runtime the tier needs.

**Deployment angle (both bundle onto the image's runtime layer):** bb ships
as one ~80 MB native binary + the tiny `.cljc` source (no build step at all);
the Node path ships a ~50 MB node binary + a pre-compiled bundle
(`clj -M:cljs compile` in CI). For **parse-only**, bb is simpler (no compile,
no node_modules, source is the artifact). For **parse + faithful eval**, Node
is the single-runtime answer and bb would only add a second runtime — so the
image-layer choice follows the tier decision above: bb if parse-only is
enough for the near term; Node (a378adfa's bundle) the moment faithful CLJS
eval enters the loop.

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

## 5. Eval tier — fidelity ladder, lean and bounded

### Three eval runtimes, ranked by CLJS fidelity (pick by what the worker emits)

The worker emits **CLJS-flavored** code, so the eval-tier runtime is chosen on
fidelity, not just speed:

1. **bb-SCI (Clojure)** — cheapest, lives in the bb parse server (one process,
   no extra runtime). Catches unbound vars, arity, throws, non-termination on
   **pure-Clojure** forms. **Mishandles CLJS-specific surface** — js interop
   (`(.json x)`, `(.-foo o)`), `^:async`/`await`, CLJS-only core — throwing
   *false negatives* on valid CLJS. Use only as a cheap pre-filter, and only
   while the worker's output is pure-Clojure-shaped (the `:defn-with-specs` /
   data-modeling MVP largely is).
2. **cljs-SCI (the Node bundle, `sci.core` compiled to JS)** — the lean
   middle. Better than bb-SCI: CLJS reader features + basic JS interop work,
   because it's SCI *in a JS host*. Still an interpreter, still **no
   `^:async`/`await`**, still not bit-identical to compiled CLJS. The
   `eval-form` shape below.
3. **cljs.js self-host (Node)** — the only **TRUE CLJS** semantics (real
   compiler → real JS). Heaviest (the ~MB self-host compiler; this is what the
   full `seon.eval` cage uses). Reserve for forms whose correctness genuinely
   depends on interop/async — the faithful tier.

**Recommendation:** the *fast* eval check is cljs-SCI (#2) in the Node bundle
(the `eval-form` below); escalate to cljs.js (#3) only when a faithful CLJS
verdict is required. bb-SCI (#1) is the front-door pre-filter ONLY in the
hybrid (§2) — its CLJS infidelity makes it unsafe as the sole eval oracle.

### Why SCI (#2), not the full cage and not `seon.render.sci`

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

**Near term (parse-only loop) — the bb deployable, simplest path:**

1. ✅ **DONE + offline-proven (2026-06-28).** `bin/oracle-server` (bb,
   committed): a persistent line loop that adds `src/` to the classpath
   (cwd-independent, via `*file*` — the `bin/test-parser` approach), requires
   `seon.repl.internal`, reads one `{op,…}` JSON line from stdin, runs
   `parse-forms` → the **byte-identical-to-Node** `{forms, tier:"parse",
   errors:[{error-kind, span, source}]}` shape, writes one JSON line. `op`/`id`
   echoed (design §4); a bare-JSON-string line (the Node `--serve` framing) is
   also accepted, so the same pipe carries either framing. Pure fn of input —
   no DB/pod/build. Offline-proven: round-trips match (`(def mean [[v] ...)` →
   `unmatched-delimiter` span `[0,19]`, identical to the Node validator
   docstring example; `(foo` → `eof`; multi-form `(+ 1 2)\n(- 3 1)` → `forms:2`),
   driven over the REAL stdin/stdout pipe from the Python `Oracle` shim
   (`tmp/flash-diffgemma/oracle_shim.py`, gitignored worker dir). **Measured:
   spawn→1st-response (cold) ~21 ms; warm per-call ~0.05–0.12 ms over 500
   calls** — confirming the design's ~10–30 ms cold / ~0.1 ms warm claims. The
   contract match to the Node `:worker-validator` is by construction (`validate`
   is the same `parse-forms`→filter→flatten logic; `error-kind`/`tier` flattened
   to NAME strings exactly as `worker_validator.cljs` `->js` does) → the Python
   shim is **runtime-agnostic** (swap bb↔Node = change spawn argv only).
2. Image layer: install `bb` + `COPY src/seon/repl/internal.cljc` (+ deps) +
   `bin/oracle-server` onto the worker; Python `Oracle` spawns
   `bb … bin/oracle-server` once (§4 shape, swap `node …` → `bb …`). Wire
   `parse` into the denoise checkpoint. (The `Oracle` shim is built +
   offline-proven — `tmp/flash-diffgemma/oracle_shim.py`; only the image
   bundling + GPU wiring remain, owner-owned.)

**When faithful CLJS eval enters the loop — the Node path (a378adfa's bundle):**

3. Add `seon.worker.eval` (cljs-SCI proxy, ~30 lines) + a unit test
   (`eval-form` on a good form → `{:ok true}`; on `(/ 1 0)` /
   `(undefined-fn)` / `(loop [] (recur))` → the three error kinds); add the
   cljs.js escalation only when interop/async correctness is needed (§5).
4. Add `seon.worker.oracle` (op-dispatch + the generalized `--serve` loop) +
   the `:worker-oracle` shadow target; CI `clj -M:cljs compile worker-oracle`;
   Dockerfile Node layer + `COPY`. Python `Oracle` switches the sidecar to
   `node …` (or runs both: bb front door + Node eval — the §2 hybrid).
5. `retrieve` seam stays a stub until the retrieval-denoising experiment needs
   it — decide then whether the HNSW index is co-located or a remote knn call
   (the one round-trip we may keep, since retrieval fires rarely).

The `{op,…}` JSON-line API (§4) is **identical across bb and Node**, so the
Python `Oracle` class and the renoise wiring do not change when the eval tier
migrates from bb to Node — only the spawned binary does.

---

## Pointers

- [[custom-image-and-seon-colocation-2026-06-28]] — the latency play this
  realizes; the custom-image layering.
- `src/seon/worker_validator.cljs` + `shadow-cljs.edn:150-176` — a378adfa's
  lean parse tier this layers on.
- `bin/test-parser` — PROOF that `parse-forms` runs under `bb --classpath
  src:test` on bb's built-in rewrite-clj, no shadow build (the bb-feasibility
  ground for the parse-tier recommendation). `bb` verified installed v1.12.212.
- `src/seon/repl/internal.cljc:561,75-78` — `parse-forms` (pure cljc;
  rewrite-clj + clojure.string only → why bb runs it faithfully).
- `src/seon/render/sci.cljs:149-156` — the proven bare-SCI eval+interrupt
  pattern the eval tier copies (DB-free slice of it).
- `src/seon/eval.cljs:36-58,970` — the full cage, documented here as
  TOO HEAVY for the worker.
- `docs/prds/agent-runtime/sidecar-spike/prd.md:21,211` — the repo evidence
  that in-process GraalVM Substrate-VM cohabitation crashes.
- `docs/prds/super-repl/prd.md:602` — the only "GraalVM/libpython" mention:
  an unchecked wishlist box, not built.
