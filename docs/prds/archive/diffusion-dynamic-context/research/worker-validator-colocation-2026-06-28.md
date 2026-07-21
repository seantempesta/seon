---
type: research
status: active
tags: [research, diffusion, agent, flow]
---

# Worker-validator: co-located parse oracle on the diffusion GPU worker

## TL;DR

A new LEAN, STANDALONE CLJS build target (`:worker-validator`) compiles
`seon.worker-validator` + its minimal deps to a single ~50KB Node bundle
(`out/worker-validator/main.js`). It exposes the PARSE/SYNTACTIC tier of
the eval-renoise oracle as a co-located local call on the GPU worker, so
the renoise loop validates a partial canvas WITHOUT a ~100ms internet
round-trip.

- **Bundle:** `out/worker-validator/main.js` — **~50KB** (51,664 bytes),
  `:optimizations :simple`.
- **Warm parse latency (the hot path):** **0.065ms** (good code) /
  **0.115ms** (broken code) per `validate` call — sub-millisecond, beats
  the ~0.4ms co-location target.
- **Cold subprocess spawn:** ~100ms (node startup + bundle load
  dominates). This is NOT a win over the wire — so the hot loop MUST use
  the persistent `--serve` mode (spawn once, reuse).
- **Dependency surface stayed LEAN:** `seon.worker-validator` →
  `seon.repl.internal` → `clojure.string` + `rewrite-clj` (pure CLJC).
  NO datahike, NO malli instrumentation, NO pod/DB state. `parse-forms`
  did NOT pull anything heavy.

## What it is

`src/seon/worker_validator.cljs` — a PURE fn of an input code string:

    (validate code) → {:forms  <int>          ; evaluable top-level forms
                       :tier   :parse          ; which oracle tier ran
                       :errors [{:error-kind <kw>      ; :eof / :unmatched-delimiter / …
                                 :span [<start> <end>] ; ABSOLUTE char offsets
                                 :source <string>}]}   ; byte-faithful bad span

It runs `seon.repl.internal/parse-forms` (the same parser the pod's
eval-batch path uses — `src/seon/repl/internal.cljc:561`) and projects
its `:read`-failure entries (`:error-kind` + `:span` +`:source`,
`internal.cljc:670-677`) into a JS-serializable shape.

The validator's job ends at char spans. The Python worker maps those
spans → canvas token positions via `build_offset_map` /
`span_to_positions` (`tmp/flash-diffgemma/diffgemma_common.py:184,211`).
The contract aligns exactly: `span_to_positions(offset_map, span)` takes
a `[s, e]` char span (`diffgemma_common.py:221`) — precisely what
`parse-forms :span` and this validator emit.

## How the Python worker calls it

Two modes, ONE bundle:

### Hot path — persistent `--serve` (use this in the renoise loop)

The cold subprocess spawn (~100ms) cancels the co-location win, so the
worker spawns the validator ONCE and reuses it. Wire protocol: ONE
JSON-ENCODED string per stdin line in (JSON-encoding so multi-line code
survives as `\n`), ONE JSON result line per request out.

    node out/worker-validator/main.js --serve

Python side (sketch):

    proc = subprocess.Popen(
        ["node", "out/worker-validator/main.js", "--serve"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    # per checkpoint:
    proc.stdin.write(json.dumps(code) + "\n"); proc.stdin.flush()
    result = json.loads(proc.stdout.readline())
    spans = [e["span"] for e in result["errors"]]   # → good_clamp_for_renoise

Reuse turns the ~100ms cold spawn into the ~0.1ms warm parse.

### One-shot (testing only)

    echo -n '(def mean [[v] ...)' | node out/worker-validator/main.js
    → {"forms":0,"tier":"parse",
       "errors":[{"error-kind":"unmatched-delimiter",
                  "span":[0,19],"source":"(def mean [[v] ...)"}]}

Simple to drive from a shell, but pays the ~100ms spawn each call — do
NOT use it in the per-checkpoint loop.

## Verified round-trips (live)

| Input | `forms` | `errors` |
| --- | --- | --- |
| `(defn mean [v] (/ (reduce + v) (count v)))` | 1 | `[]` |
| `(def mean [[v] ...)` | 0 | `unmatched-delimiter` span `[0,19]` |
| `(def a 1)\n(def b [1 2 3\n(def c 2)` | 2 | `eof` span `[10,24]` |

The third (multi-line, JSON-encoded over `--serve`) confirms the `\n`
survives the wire and the good forms before/after the broken one still
count — per-form error isolation is preserved.

## The build

`shadow-cljs.edn` — new `:worker-validator` block:

```clojure
:worker-validator
{:target           :node-script
 :output-to        "out/worker-validator/main.js"
 :main             seon.worker-validator/-main
 :devtools         {:enabled false}
 :compiler-options {:warnings-as-errors false
                    :optimizations      :simple
                    :externs            ["externs/node_fs.js"]}}
```

- `:optimizations :simple` (NOT `:advanced`): small bundle + fast cold
  start, keeps `require("fs")`/`require("readline")` names, skips the
  slow advanced pass. The bundle is leaf code with no live REPL runtime,
  so advanced renaming buys nothing.
- `:devtools false`: run-to-exit / persistent subprocess, never a shadow
  runtime.
- **Additive only** — the running `:client` watcher IGNORES unwatched
  builds, so this does not touch the live pod. `:repl` / `:node-test`
  are untouched. Build it in a FRESH JVM, NOT cljs-watch:

      clj -M:cljs compile worker-validator

  (89 files, 0 warnings, ~7s wall.)

## Bundling onto the worker image (the Node layer)

The custom GPU image already carries a Node runtime for the worker glue.
The validator slots in as a build artifact:

1. On the build host: `clj -M:cljs compile worker-validator` → produces
   `out/worker-validator/main.js` (a self-contained CJS bundle — no
   `node_modules` needed at runtime; `fs`/`readline` are Node built-ins).
2. COPY that single file into the image (e.g. `/opt/seon/worker-validator/main.js`).
   Nothing else from the seon tree is required — it is ONE file.
3. The Python worker spawns it once in `--serve` mode at startup and
   pipes each candidate canvas to it per checkpoint.

No Python changes here, no GPU deploy — the owner drives the image build.

## The eval-tier extension seam

The FIRST version is the parse tier ONLY. The seam is deliberate and
narrow so the heavy stack never contaminates this lean bundle:

- `validate` returns `:tier :parse`. The eval tier adds its OWN fn
  (`validate-eval`, or a `:tier` dispatch) that runs AFTER a clean parse,
  compiles/evals the forms, and APPENDS eval-level errors with the same
  `{:error-kind :span :source}` shape.
- That tier pulls `cljs.js` / the program-graph / instrumentation — the
  heavy subtree. It must live behind its own require so the parse bundle
  stays ~50KB and ~0.1ms. If/when added, it likely needs its OWN build
  target (`:worker-validator-eval`) rather than swelling this one, OR a
  conditional require gated by a flag — measure the bundle delta first.
- The wire shape does NOT change: more entries in `:errors`, same keys.
  The Python `span_to_positions` consumes eval spans identically to parse
  spans (it is already span-source-agnostic —
  `diffgemma_common.py:212` notes "a parse-forms :span, or a runtime
  symbol's substring range").

## Files

- `src/seon/worker_validator.cljs` — the ns (validate / ->js /
  validate-json / serve! / -main).
- `shadow-cljs.edn` — the `:worker-validator` build block.
- Parser ground truth: `src/seon/repl/internal.cljc:561` (`parse-forms`),
  `:670-677` (the `:read` entry shape).
- Span consumer: `tmp/flash-diffgemma/diffgemma_common.py:184,211,225`.
