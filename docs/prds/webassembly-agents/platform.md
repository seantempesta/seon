---
type: prd
status: active
tags: [prd, pod, cljs, wasm, agent]
---

# Platform: WebAssembly Agents

(Branch: `webassembly-agents`. This doc tracks the substrate work
on that branch — what shipped, what's next, where we want to land.)

The substrate the agent writes against — bootstrap-CLJS eval + datahike +
test infrastructure + capability-bounded WASM containment — sized for
the **whole** roadmap, not just today's MVP. Tracks what shipped, what's
next, and where we want to land.

## Goals

1. **Self-hosted CLJS eval inside WASM.** Agent emits any valid CLJS;
   it runs under wasmtime + wasm-rquickjs with the analyzer fully
   populated, error shapes meaningful, and async/await native.
2. **Test infrastructure the agent uses by writing data, not by
   reading console output.** Capture every assertion event into
   structured EDN the renderer can format into the agent's context.
3. **Function-level Malli instrumentation** matching the JVM side —
   every fn with `:malli/schema` validated at call time on CLJS too.
4. **Dynamic deps.** Agent runs `(seon.deps/install ...)` from the
   REPL and acquires new CLJS or npm packages without a substrate
   rebuild. Capability-bounded: fs cache + HTTP + (eventually) a
   curated registry, all via WIT imports.

The point: the agent should be able to **write live code**, not just
read pre-bundled code. Today's MVP delivers eval + tests; the runway
beyond MVP turns the pod into a real package-installable workspace.

## Design constraints (apply to every phase)

- **Functions take data, return data. Spec both ends.** Every public
  fn on this platform has a registered `:malli/schema` for its input
  map and output map. The agent must be able to look at any
  `seon.*` fn and learn its contract from the schema without reading
  the body.
- **No globals, no atoms in the API.** Atoms are an implementation
  detail when they truly fit (e.g. a transient builder local to one
  fn that converts back to a value before returning); they're never
  the way a caller communicates with a function. State that has to
  cross fn boundaries belongs in the datahike conn, which IS a data
  surface — queries return data, transacts take data.
- **Per-call binding > install! mutation.** When a library wants a
  "global" handler (cljs.test's `*current-env*`, etc.), wrap the
  call in a `binding` of a per-call value instead of mutating the
  var globally. Behavior depends on the call, not on whether some
  prior code did setup.
- **Capabilities are explicit.** Every external action (HTTP, fs,
  npm install, package fetch) flows through a WIT import the agent
  can see and the host can deny. No ambient authority.

## Current state (2026-05-22)

### ✅ Shipped — MVP substrate

| Capability | Status | Where |
|---|---|---|
| Self-hosted CLJS eval (Node V0 pod) | shipped | `src/seon/eval.cljs`, `src/seon/repl.cljs` |
| Self-hosted CLJS eval (WASM) | shipped | `src/seon/wasm_eval_smoke.cljs`, `pod-host/wasm-tauri/` |
| Bootstrap analyzer-cache auto-load | shipped | `seon.eval/load-all-analysis-caches!` |
| `seon.repl/dev-init!` (history-on conn + bootstrap) | shipped | `src/seon/repl.cljs` |
| Two-eval-surfaces story (host nREPL + bootstrap-CLJS) | shipped | `docs/seon/pod/REPL-WORKFLOW.md` |
| Undeclared-var detection w/ analyzer + globalThis fallback | shipped | `seon.eval/truly-undeclared?` |
| `^:async`/`await` in eval'd forms | shipped | inherited from CLJS 1.12.145 |
| `eval-batch` WIT export (multi-form runs) | shipped | `src/seon/wasm_eval_smoke.cljs`, `pod-host/wasm-tauri/src-wit-eval-smoke/eval-smoke.wit` |
| `cljs.test` self-hosted (deftest + run-tests) | shipped | bootstrap `:entries` expansion + analyzer-cache auto-load |
| Bootstrap-macros workaround | shipped | `bin/fix-bootstrap-macros` (defensive; not currently needed) |
| `mcp__seon_cljs__eval` MCP server | shipped | `bin/mcp-server-cljs` |
| Capability-bounded WIT surface (fs/http/mcp/capability-prompt) | designed | `pod-host/wasm-tauri/src-wit/seon-pod.wit` (not yet built into eval-smoke) |

### 🚧 Known gaps the MVP doesn't address

| Gap | Impact | Tracked at |
|---|---|---|
| Test output goes to stdout, not capturable as data | Agent context can't show test results inline | [[#test-capture-as-data]] |
| Malli instrumentation only on JVM side | Agent-defined fns don't get runtime validation | [[#cljs-instrumentation]] |
| `cljs.test` test discovery / selective run | Agent runs ALL tests every time | [[#selective-test-run]] |
| Bootstrap entries hand-curated in `shadow-cljs.edn` | Adding a CLJS lib = rebuild + redeploy | [[#dynamic-cljs-deps]] |
| Node-side `(js/require ...)` only sees bundled npm deps | Agent can't `npm install` mid-session | [[#dynamic-npm-deps]] |
| WASM has no outbound capabilities yet | Agent can't fetch packages, no API calls | [[#wasm-capabilities]] |

---

## Solution design — Phased roadmap

### Phase 1 — MVP substrate (DONE)

Today's deliverables. See [[research/m2-findings-2026-05-21]] for the WASM landmines + workarounds, and [[../../seon/pod/REPL-WORKFLOW]] for the iteration surface.

### Phase 2 — Test infra promoted to data

Goal: every cljs.test event becomes an EDN datom the renderer can show. Foundation for the agent reading test results as part of its context, not by parsing console output.

#### Capture mechanism

**Data in, data out — no globals, no atoms in the API.** cljs.test already supports a per-run `:reporter` slot on its environment (`cljs.test/*current-env*`). We bind a per-call environment whose reporter accumulates into a local builder, then return the events as data.

```clojure
;; src/seon/test/runner.cljs

(schema/register! ::test-event
  [:map
   [:type :keyword]                       ; :pass | :fail | :error | :summary | :begin-test-ns ...
   [:expected {:optional true} :any]
   [:actual   {:optional true} :any]
   [:message  {:optional true} :string]
   [:file     {:optional true} :string]
   [:line     {:optional true} :int]])

(schema/register! ::run-request
  [:map
   [::vars       [:vector :symbol]]       ; fully-qualified test vars
   [::ns-filter  {:optional true} :keyword]])

(schema/register! ::run-result
  [:map
   [::events  [:vector ::test-event]]
   [::summary [:map [:test :int] [:pass :int] [:fail :int] [:error :int]]]])

(defn run-vars
  "Run the given test vars, return collected events as data. The
   reporter callback closes over a transient builder that's reified
   to an immutable vector at return — no global, no atom escape."
  {:malli/schema [:=> [:cat ::run-request] ::run-result]}
  [{::keys [vars]}]
  (let [!builder (volatile! (transient []))   ; impl detail, scoped to fn
        env      (-> (cljs.test/empty-env)
                     (assoc :reporter (fn [m] (vswap! !builder conj! m))))]
    (binding [cljs.test/*current-env* env]
      (cljs.test/test-vars (mapv resolve vars)))
    (let [events  (persistent! @!builder)
          summary (or (->> events (filter #(= :summary (:type %))) last)
                      {:test 0 :pass 0 :fail 0 :error 0})]
      {::events events
       ::summary summary})))
```

The volatile is a local builder — the API is pure: `::run-request → ::run-result`. Both schemas registered, both validatable, no shared state.

cljs.test's reporter slot is a callback because that's how the underlying test runner streams events (some tests are async). Wrapping the streaming-callback in a local builder + returning data at the end is the standard pattern.

#### Per-test transact

Each `(deftest)` definition can ALSO transact a `:seon.test/*` entity into the agent's datahike conn — matching the spec §"Data model" section. The renderer's `recent-evals` / `warnings` tiles read test status from DB state via Datalog queries (also data in / data out).

```clojure
(defn record-run!
  "Transact run results onto the corresponding :seon.test entities.
   Returns the tx-report (data). No hidden state."
  {:malli/schema [:=> [:cat ::record-request] ::tx-report]}
  [{::keys [conn run-result agent-id]}]
  (let [now      (js/Date.)
        per-test (group-by :var (::events run-result))
        tx-data  (for [[var-sym events] per-test
                       :let [failed? (some #(#{:fail :error} (:type %)) events)]]
                   {:seon.test/sym (str var-sym)
                    (if failed? :seon.test/last-failed-at
                                :seon.test/last-passed-at) now
                    :seon.test/last-failure (when failed?
                                              (pr-str (first (filter #(#{:fail :error} (:type %)) events))))})]
    (db/transact! {:seon.db/conn conn :seon.db/tx-data tx-data})))
```

Same shape — `::record-request → ::tx-report`. The conn is passed in (not reached for via a global), the time is captured at the boundary, the events drive the tx-data via a pure transformation.

#### Out of MVP scope, in scope here

- Auto-run on define (spec [[agent-repl-mvp#d6]]) — when an eval's tx asserts on a `:seon.fn`, a post-eval fn queries reverse-targets, runs them via `run-vars`, then records via `record-run!`. All data-shaped; the eval-batch caller chains them.
- Per-agent reporter — pass a custom `:reporter` fn into `cljs.test/empty-env` before binding. The fn is part of the call's input, not registered globally.

**Anti-pattern to avoid:** a `(seon.test/install!)` that mutates `cljs.test/report` globally. That makes the test machinery's behavior depend on whether `install!` has been called and whether some other code re-rebound it. The per-call binding-of-env approach is the data-friendly version.

**Effort:** ~1 day. Pure CLJS work, no WASM changes.

### Phase 3 — CLJS function instrumentation

Goal: every fn the agent defines with `:malli/schema` is validated at call time, same as the JVM side per CLAUDE.md.

Malli ships CLJS instrumentation via `malli.instrument`. It wraps each schema'd fn with a validating proxy that throws on bad input/output. The wiring needed:

1. **Compile-time hook** (or post-eval hook in `seon.eval/eval-batch!`): after a `(defn …)` evaluates, if the var has `:malli/schema` meta, call `(malli.instrument/instrument! 'agent.ns/foo)`.
2. **Schema registry sync**: every `(schema/register! ::foo ...)` updates the global registry; instrumented fns pick up the change on next call.
3. **Error shape**: instrumentation throws ex-info with `{:type :malli.core/invalid-input}` — the eval wrapper translates to the spec's error shape.

The CLJS pod already pulls Malli (see `seon.schema` requires). The remaining work is the post-eval wire-up + a single `seon.dev/instrumentation` setup fn that mirrors the Integrant key on the JVM side.

**Effort:** ~2-3 days. Some Malli-CLJS gotchas around macro vs runtime expansion of `:malli/schema`.

### Phase 4 — Test discovery + selective run

Goal: agent runs specific tests, not the whole suite. The cljs.test API supports this; we just need helpers:

```clojure
(seon.test/run 'agent.foo/my-test)              ; one test by sym
(seon.test/run-fn 'agent.foo/analyze)           ; all tests targeting one fn
(seon.test/run-ns 'agent.foo)                   ; all tests in a ns
(seon.test/run-all)                             ; everything
```

Backed by:

- `:seon.test/target` ref → reverse-index query for "tests for fn X"
- `ns-publics` / `(:test (meta var))` traversal for ad-hoc discovery
- `cljs.test/test-vars-block` to actually run

Combined with Phase 2's capture, the result is data-shaped: `{:tests [{:sym "..." :events [...]}] :summary {...}}`.

**Effort:** ~1 day after Phase 2 is done.

### Phase 5 — Dynamic CLJS deps

Goal: `(seon.deps/install "[org.clojars.foo/bar \"1.0\"]")` from the REPL acquires a CLJS dep without rebuilding the substrate.

#### What has to happen

1. **Resolve coordinate** → URL (clojars, maven, github).
2. **Download** the jar / source archive over HTTPS.
3. **Extract** `.cljc` / `.cljs` sources + any analyzer caches.
4. **Make available to cljs.js**: cljs.js's `:load` callback already lets us serve sources from arbitrary places. Today it routes to `shadow.cljs.bootstrap.node/load`; we'd add a custom `:load` that first checks the bootstrap cache, then a writable "dynamic deps" cache, then fails over to HTTP fetch.
5. **Compile transitive deps**: cljs.js handles this via the analyzer pass; the `:load` callback drives it.
6. **Persist to local cache** so the install is one-shot.

#### Constraints

- **Pure CLJS only.** Deps that need JVM interop (Java classes) can't run here. This rules out most of the existing Clojure ecosystem; the realistic surface is the smaller CLJS-native ecosystem (cljsjs, reagent, re-frame, cljs.spec.alpha, etc.).
- **Macro deps need eval-time compilation.** cljs.js handles this but adds startup cost on first load.
- **No `:foreign-libs`** without a JS-bundling step at install time.

#### WASM capabilities required

- `wasi:http/types` (outbound HTTPS) — already in the eval-smoke build (gated by `-S http=y` at runtime).
- `wasi:filesystem` (writable cache dir) — easy to add via a preopen mount in the Tauri host or wasmtime CLI flag.
- An optional `seon:packages/registry` WIT interface — host-mediated package resolution if we don't trust the agent to pick coordinates.

**Effort:** ~1-2 weeks. The hard part is the dependency-graph walk + analyzer-cache reconciliation, not the download/cache.

### Phase 6 — Dynamic npm deps

Goal: `(seon.deps/npm-install "lodash")` adds a runtime-loadable npm package.

#### Node pod (V0)

Cheap. `(js/require "child_process").spawn("npm", ["install" "lodash"])` exists. After it finishes, `(js/require "lodash")` finds it under `node_modules/`. The agent does this today; we just need a wrapper that returns a Promise + handles failures.

**Effort:** ~half day.

#### WASM pod

Harder. No `child_process`; no live npm in QuickJS. Options:

1. **Host-mediated install** — Tauri (or another host) provides a WIT interface `seon:npm/install: func(pkg: string) -> result<list<file>, install-error>`. Host runs npm on the desktop, returns the resulting file tree. Pod writes them into a preopened `node_modules/` dir. `(js/require ...)` from CLJS works against the writable preopen.
2. **Pre-bundle a "universal" npm set** — pick 50 common packages, bundle once. Limited but works without host bridge.
3. **CDN-based** — fetch UMD/ESM builds from unpkg.com over `wasi:http`, eval into globalThis as a synthetic module. Bypasses node_modules entirely but loses Node's resolution semantics.

Recommend option 1 for the Tauri shell, option 3 as a fallback for headless wasmtime.

**Effort:** ~1-2 weeks depending on host model.

### Phase 7 — Capability hardening

Goal: the agent has explicitly bounded access. Every external action — HTTP, fs read, fs write, npm install, package fetch — flows through a WIT import. The Tauri host decides which to grant.

The `pod-host/wasm-tauri/src-wit/seon-pod.wit` world already drafts `fs`, `mcp`, `capability-prompt` interfaces. Once Phases 5/6 land, we add `packages`, `http-fetch`, and possibly `process` for the npm bridge.

Per-agent capability grants come from a config the host reads at boot. Production deployments lock down everything except `eval`; development unlocks `fs`/`http`/etc.

**Effort:** ongoing; trails Phases 5/6.

---

## What we want the agent to be able to do

End-state vignette:

```clojure
;; Agent decides it needs a new dep
(seon.deps/install '[reagent/reagent "1.2.0"])
;; Pod fetches, caches, makes analyzer-visible. Returns :installed.

;; Agent requires + uses it
(require '[reagent.core :as r])
(def app (r/atom {:count 0}))
(swap! app update :count inc)

;; Agent writes a test using it
(deftest reagent-atom-works
  (let [a (r/atom 0)] (swap! a inc) (is (= 1 @a))))

;; Agent runs the test, sees structured results
(seon.test/run 'cljs.user/reagent-atom-works)
;; => {:summary {:pass 1 :fail 0 :error 0} :events [...]}

;; Renderer turns those events into a context tile the agent reads next turn.
```

Today's agent can write the deftest and run it. They can't install the dep — that's Phase 5. Path is clear; substrate is ready to grow into it.

---

## Capability gates

Each phase needs specific WASI/WIT capabilities. Tracking what's available + when we need each:

| Capability | Currently | Phase needing it | Notes |
|---|---|---|---|
| `wasi:clocks/wall-clock@0.2.3` | ✓ available | MVP | Imported by eval-smoke |
| `wasi:filesystem/preopens@0.2.3` | ✓ available | MVP | Bootstrap dir mount |
| `wasi:filesystem/types@0.2.3` | ✓ available | MVP | fs.readFile |
| `wasi:random/random@0.2.3` | ✓ available | MVP | `random-uuid` |
| `wasi:http/types@0.2.9` | gated by `-S http=y` | Phase 5 | Outbound HTTPS |
| `wasi:sockets/tcp@0.2.3` | not imported | Phase 5+ | Lower-level than wasi-http |
| `wasi:cli/environment@0.2.3` | not imported | Phase 5 | Reading env vars (DEEPSEEK_API_KEY etc) |
| Writable preopen for cache | not yet | Phase 5 | `--dir cache::cache --writable` |
| `seon:packages/install` WIT | not designed | Phase 5 | Host-mediated install |
| `seon:npm/install` WIT | not designed | Phase 6 | Host shells out to npm |
| `seon:fs/sandbox` WIT | drafted | Phase 7 | Already in `seon-pod.wit` |

---

## Decisions pending

- **Where does the writable cache live?** Per-agent (`~/.seon/agents/<id>/cache`) vs. shared across agents (`~/.seon/cache`). Sharing saves disk; per-agent is cleaner for capability bounds. Recommend shared with content-addressed paths.
- **Pre-install vs lazy install?** When the agent does `(require '[foo.bar])` and foo.bar isn't in the cache, do we auto-fetch or error and require explicit `seon.deps/install`? Recommend auto-fetch on first require with a capability prompt (yes / no / always-for-this-prefix).
- **Per-call return vs DB persistence?** Phase 2's `run-vars` returns events+summary as data inline (caller sees them in the eval-batch response). Cross-turn history needs a transact onto `:seon.test/*` entities so subsequent renders pick them up. Both — return the data inline so the agent sees immediate results, AND `record-run!` writes to DB so the warnings tile + history queries work across turns. Two specced fns, no shared mutable state.
- **Bootstrap vs runtime compile?** If a CLJS dep arrives via Phase 5 install, do we re-emit bootstrap artifacts (so subsequent boots are fast) or always compile fresh from source (simpler but slower cold start)? Recommend cache compiled JS + analyzer transit alongside source for warm restarts.

---

## Anchors for follow-on docs

### `^test-capture-as-data`

Phase 2 design notes — go in `research/test-capture.md` once we start.

### `^cljs-instrumentation`

Phase 3 design notes — go in `research/cljs-instrumentation.md` once we start.

### `^selective-test-run`

Phase 4 design notes.

### `^dynamic-cljs-deps`

Phase 5 design notes — the biggest piece. Will need its own sub-PRD probably.

### `^dynamic-npm-deps`

Phase 6 — sub-PRD likely.

### `^wasm-capabilities`

Phase 7 — capability matrix expanded.

---

## Reference

- Spec the agent's writing against: [[agent-repl-mvp]]
- WASM landmines + workarounds: [[research/m2-findings-2026-05-21]]
- Iteration loop docs: [[../../seon/pod/REPL-WORKFLOW]]
- WASM spike design (precursor): [[research/wasm-spike-2026-05-20]]
- V0 Node pod state: [[research/v0-state-2026-05-20]]
- Project root: `pod-host/wasm-tauri/` (Rust + WIT workspace)
- Bootstrap output: `out/bootstrap/` (analyzer caches + per-ns JS)
