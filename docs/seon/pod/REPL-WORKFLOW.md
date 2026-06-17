---
type: reference
status: active
tags: [reference, pod, cljs, mcp]
---

# CLJS pod — REPL workflow (V0 / pre-WASM)

How to drive the running CLJS pod from an editor or MCP client. Currently
this means **shadow-cljs nREPL piggyback** to the long-running Node
process. When Phase 3 lands (WASM-Tauri), this changes — see
[[wasm-spike-2026-05-20]] §"REPL access".

## V0 — Node + shadow-cljs

### Boot

```sh
cd /Users/sean/src/seon

# Terminal 1 — watcher (compiles + writes .shadow-cljs/nrepl.port)
clj -M:cljs watch client

# Terminal 2 — Node host loads the compiled bundle
node out/client/main.js

```

The watcher pins shadow nREPL to **`:7889`**. The Node host writes its
bound HTTP port to `tmp/seon-port` (default `7890`; override via
`SEON_PORT`).

### MCP eval against the running pod

The active CLJS MCP server is registered as `seon_cljs`:

```clojure
;; Smoke — proves the runtime is alive
mcp__seon_cljs__eval {
  code: "(require '[cljs.core.async :as a])
         (a/go (println (a/<! (seon.client/datahike-smoke-test!))))"
}
;; => {:rows #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]}, :status :pass, :datoms 6}

;; Inspect process-lifetime state
mcp__seon_cljs__eval { code: "@seon.client/!state" }
;; => {:boot-at "...", :reload-count <N>, :heartbeat-id #object [Timeout ...]}

;; Configure the fs sandbox (default is deny-all)
mcp__seon_cljs__eval { code: "(seon.fs/configure!
                                  {:seon.fs/allowed-roots [\"/Users/me/work\"]
                                   :seon.fs/read-only? false})" }

```

### Iteration surface — `seon.repl/dev-init!`

For core experiments (spec verification, REPL-as-data-shape work,
testing claims one form at a time) the V0 pod ships a tiny init
helper that opens a history-enabled datahike conn AND initializes
bootstrap-CLJS, both as defonce'd atoms. **Decoupled from
`seon.client/start-agent!`** — you don't have to spin the stub LLM,
web server, or broadcast watcher just to test how an eval-str error
is shaped or how `:tx-meta` propagates.

```clojure
;; Once per pod boot. Idempotent — subsequent calls are O(atom-deref).
mcp__seon_cljs__eval { code: "(.then (seon.repl/dev-init!) prn)" }
;; => {:compile-state #object[Atom ...] :conn #object[Atom ...]}

;; After init:
;;   @seon.repl/!compile-state  — bootstrap-CLJS compile-state
;;   @seon.repl/!conn           — :memory datahike conn (:keep-history? true)

```

### Two eval surfaces — pick the one matching your question

The pod has two distinct paths to "evaluate this CLJS code." Use the
one whose semantics match the question you're asking.

| Surface | Mechanism | Use when |
|---|---|---|
| **Host eval** | `mcp__seon_cljs__eval` piggybacks shadow's nREPL into the `:client` runtime. Forms see every var statically required by `seon.client` (rewrite-clj, datahike, cljs.js, the whole core). | Core-library questions. "Does rewrite-clj parse comments alongside forms?" "Does `(d/history db)` return the tx datoms I expect?" — no in-pod simulation needed. |
| **Bootstrap-CLJS eval** | `(seon.eval/eval @seon.repl/!compile-state "...")` compiles + runs the string through `cljs.js` against the persistent compile-state. | The question IS what an LLM-emitted form experiences. Error shapes (`:kind :compile` vs `:runtime`), `(ns other)` switches, `(def x …)` cross-call persistence gotcha, `^:async`/`await`. |

Both surfaces write to the same datahike conn (`@seon.repl/!conn`)
when they do persistence work, so `:tx-meta {:seon.eval/id ...}`
tagging and history queries behave the same through either path.

```clojure
;; Host eval — testing a core library
mcp__seon_cljs__eval { code:
  "(rewrite-clj.parser/parse-string-all \";; hi\\n(+ 1 2)\\n\")" }

;; Bootstrap-CLJS eval — testing what the agent will see
mcp__seon_cljs__eval { code:
  "(.then (seon.eval/eval @seon.repl/!compile-state \"Let\")
          (fn [r] (js/console.log (pr-str r))))" }

```

`seon.repl/!compile-state` is nil until `dev-init!` runs. Call it
once at the start of any iteration session.

## Platform core — what's robust, what to know

### Bootstrap analyzer cache: discover-and-load-all

`seon.eval/init-bootstrap!` walks `out/bootstrap/ana/*.transit.json`
and loads EVERY analyzer cache shadow emitted, not just `cljs.core`.
The takeaway: **whatever's listed in `shadow-cljs.edn :bootstrap
:entries` is automatically analyzer-visible** to the agent's eval.
Adding a new ns to that vector + recompiling bootstrap is the whole
story — no second load-list to maintain.

Currently bundled (analyzer-visible in agent eval): `cljs.core`,
`cljs.test`, `clojure.set`, `clojure.string`, `clojure.walk`, plus
the transitive deps of cljs.core (`cljs.reader`, `cljs.tools.reader.*`,
`cljs.analyzer.*`, `clojure.set`, `cognitect.transit`, etc.).

This solves a real fragility — without the discover-and-load pass,
shadow's `boot/init` short-circuits the cljs.core analyzer load
(its filter sees `:name` already set by `(dump-core)` and skips),
leaving unqualified core refs (`(reduce + (range 10))`) resolving
to `cljs.user.reduce` (undefined → nil) at runtime.

### Undeclared-var detection: dual-strategy

`seon.eval/eval` rejects on truly-undeclared symbols. With the
analyzer-cache load above, most refs resolve through the analyzer
itself and never reach the rejection path. For warnings that DO
slip through (e.g. agent code references a ns not in the bootstrap
cache but bundled into `:client` at the JS level), a runtime
`goog.getObjectByName` fallback resolves them via globalThis — so
bundled vars don't false-positive. Real typos (`Let` in `cljs.user`)
reject with `:seon.error/kind :compile` + `:seon.eval/warning-type
:undeclared-var` / `:undeclared-ns`.

### cljs.test self-hosted

`(require '[cljs.test :refer-macros [deftest is run-tests]])` works
inside both the V0 pod's bootstrap-CLJS eval AND the wasm eval-smoke
component. Confidence-run shape:

```clojure
(require '[cljs.test :refer-macros [deftest is run-tests]])
(deftest mytest (is (= 49 (* 7 7))))
(run-tests)
;; stdout: "Ran 1 tests containing 1 assertions. 0 failures, 0 errors."
;; return: nil

```

Avoid `(with-out-str (run-tests))` inside eval-str — that combo
trips a `cljs.core$macros/str` expansion edge case in self-host.
Read test output via host stdout instead.

### Test capture as data — `seon.test.runner` (Phase 2, shipped 2026-05-22)

For agent-facing usage where the test output must be readable as
DATA (not parsed from stdout), use `seon.test.runner`. Same
`(deftest …)` definitions; different runner.

```clojure
(require '[cljs.test :refer-macros [deftest is]]
         '[seon.test.runner :as runner])

(deftest mytest (is (= 49 (* 7 7))))

;; Pure capture — returns events + summary as data, no DB write.
(runner/run-vars {:seon.test.runner/vars ['cljs.user/mytest]})
;; => {:seon.test.runner/events
;;       [{:type :begin-test-var, :var cljs.user/mytest, :ns cljs.user}
;;        {:type :pass, :expected "(= 49 (* 7 7))", :actual "(= 49 49)",
;;         :var cljs.user/mytest, :ns cljs.user}
;;        {:type :end-test-var, :var cljs.user/mytest, :ns cljs.user}
;;        {:type :summary, :test 1, :pass 1, :fail 0, :error 0}]
;;     :seon.test.runner/summary {:test 1 :pass 1 :fail 0 :error 0}}

;; Full surface — run + stash full result on agent's ns + record
;; projection to DB. The convenience the agent's eval-batch uses
;; after a (defn …) that touches a :seon.fn (spec D4).
(.then (runner/run-and-record! {:seon.test.runner/vars ['cljs.user/mytest]})
       prn)
;; => Promise<{:seon.test.runner/run-id "Ab12Cd34Ef"
;;             :seon.test.runner/run-result <same shape as above>
;;             :seon.test.runner/tx-report {:seon.db/ok? true ...}}>

;; To dig into a stashed run later, the agent's home ns has a
;; (result <id>) helper wired by seon.eval/setup-agent-ns! — the
;; same helper used for eval results. The full event sequence is
;; on globalThis, NOT in the DB.
(result "Ab12Cd34Ef")
;; => {:seon.test.runner/events [...] :seon.test.runner/summary {...}}

```

**Storage model.** The full result lives on the agent's ns (via
the run-id stash). The DB row carries ONLY the surfaced
projection:

| Attr | Purpose |
|---|---|
| `:seon.test/sym` | "cljs.user/mytest" |
| `:seon.test/last-passed-at` / `:last-failed-at` | timestamps |
| `:seon.test/last-failure-summary` | ≤200-char rendered failure (for warnings tile) |
| `:seon.test/last-run-id` | pointer back to the agent-ns stash |

Renderers read the projection via Datalog; the agent reaches the
blob via `(result <run-id>)` when it wants to dig deeper.

**Reporter mechanism.** The runner claims the `::runner/capture`
reporter keyword via per-event `defmethod`s. `cljs.test/test-vars`'
`Var`-instance precondition is sidestepped — we look up each
symbol's compiled fn through `goog.getObjectByName` and drive it
directly. `(is …)` inside the test body still calls
`cljs.test/do-report`, which dispatches through our defmethods,
so captured event shape matches what `t/test-vars` would produce
(minus a real `Var` reference — we use a `#js {:sym <sym>}`
stand-in).

**Build wiring.** `seon.test.runner` is `:require`d from
`seon.client`, so it's in the `:client` bundle. Agent eval-str
calls reach it via the analyzer's globalThis fallback (see
`seon.eval/truly-undeclared?`) — no bootstrap-entries change
needed.

### Bootstrap-macros workaround (defensive)

`bin/fix-bootstrap-macros` is a babashka script that rewrites
empty-namespace `Symbol` literals in `out/bootstrap/js/*$macros.js`
if shadow's `:bootstrap` target ever regresses on user-listed
`:macros` entries. The current build doesn't need it — recent
shadow-cljs versions handle this correctly — but it's a one-call
safety net. Run after `clj -M:cljs compile bootstrap` if you see
"Use of undeclared Var /try-expr" or similar empty-ns analyzer
errors at agent-eval time.

### WASM confidence runs

`pod-host/wasm-tauri/eval-smoke-build/` builds a wasm32-wasip2
component that exposes WIT exports: `init-bootstrap`, `eval-form`,
`eval-batch`. Same self-hosted CLJS surface as the V0 pod; same
`out/bootstrap/` cache (mounted via `--dir`); same analyzer-cache
discovery + `cljs.test` support. Use for end-to-end confidence runs
AFTER Node iteration is green. The wasmtime CLI invokes each
`--invoke` as a fresh component instance, so a single `eval-batch`
call IS the session.

```bash
cd pod-host/wasm-tauri && ./build-eval-smoke

# Run a multi-form test program inside wasm:
wasmtime run -S http=y \
  --dir /Users/sean/src/seon/out/bootstrap::bootstrap \
  --invoke 'eval-batch("(require ...) (deftest ...) (run-tests)")' \
  eval-smoke-build/target/wasm32-wasip2/release/eval_smoke.wasm

```

### Hot reload

Edit any `.cljs` in `src/seon/`, save. shadow-cljs recompiles in ~1s;
the running runtime gets a websocket message; `^:dev/before-load`
cleanup runs (heartbeat, broadcast watcher, agent kick listener);
namespaces re-load; `^:dev/after-load` rewires.

`defonce` state survives:
`!agent-conn`, `!compile-state`, `seon.schema/*schemas`, `seon.fs/!config`,
`seon.eval/!timeout-ms`, `seon.eval/timeout-sentinel`,
`seon.web.serve/!server` + `!sse-connections`, etc.

### Stopping

```sh
pkill -f "clj.*shadow.cljs.devtools.cli watch"
pkill -f "node out/client/main.js"

```

### Common failure modes

- **`no shadow-cljs watcher running`** — watcher isn't up. Restart Terminal 1.
- **`Cannot read properties of null (reading 'findInternedVar')`** — bootstrap CLJS didn't load. Recompile `:bootstrap` (`clj -M:cljs compile bootstrap`).
- **`EADDRINUSE 127.0.0.1:7890`** — another pod still listening. `lsof -ti :7890 | xargs kill`, or set `SEON_PORT=0` for ephemeral.
- **Eval returns `nil` for `(go ...)`** — `go` returns a channel immediately; the value lands in stdout from the `println` side-effect. Look in the Node host's stderr/stdout, not the eval's `=>`.

## What changes when Phase 3 (WASM-Tauri) ships

The CLJS pod will run inside a `wasm32-wasip2` Component embedded in
wasmtime — there's no Node process to reach via nREPL. Editor/MCP access
goes through a **WIT-typed `eval` interface**, surfaced by an
`mcp-server-seon` binary that bridges stdio JSON-RPC to the running
Tauri host's IPC.

```text
[editor] ←stdio→ [mcp-server-seon] ←IPC→ [Tauri host] ←WIT→ [wasm pod]
                                                              │
                                                              └ eval-form
                                                                interrupt
                                                                query
                                                                inspect-agent

```

No raw nREPL — that contradicts the WASM containment story. The
WIT-typed `eval` surface is intentionally narrower than nREPL, and the
host owns the capability boundary. See `pod-host/wasm-tauri/` (Rust
workspace) for the implementation in progress.
