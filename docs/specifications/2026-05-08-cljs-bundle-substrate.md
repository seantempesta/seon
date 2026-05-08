# PRD — Self-hosted ClojureScript runtime for the agent

**Status**: draft, awaiting Sean review
**Author**: Claude (with Sean)
**Created**: 2026-05-08
**Last meaningful update**: 2026-05-08 (session lifecycle + two-channel state model)
**Supersedes**: the scittle-based runtime in `harness/sidecar/sidecar.mjs` + `harness/cljs/bootstrap.cljs`
**Related research**: [`docs/research/2026-05-08-cljs-bundle-bootstrap.md`](../research/2026-05-08-cljs-bundle-bootstrap.md), [`docs/research/2026-05-08-seon-borrow-list.md`](../research/2026-05-08-seon-borrow-list.md), [`docs/research/2026-05-08-qwen-cljs-eval.md`](../research/2026-05-08-qwen-cljs-eval.md)

---

## 0. Maintaining this doc

**This doc is the current spec — always.** It describes the system as it is, not as it was. Past versions live in `git log -p` for this file; design history is reachable by following commit hashes referenced inline where they're worth knowing.

Rules:

- **Update in the same commit as the code change.** A hurdle landing, a TBD resolving, an architecture change, a library swap — all edit this doc *with* the code. Stale specs are worse than no spec.
- **When a TBD in §6 resolves, fold the answer into §4 and delete the §6 entry.** The question is preserved by git; the answer is part of the live design.
- **When a hurdle in §5 clears, delete it from §5.** §5 shrinks to empty over the build; §4 grows to cover the as-built reality.
- **Reference commits, not history sections.** If a previous design is worth knowing — a gotcha that bit us, a fallback we took, a library we swapped out — add a one-line entry in §10 "Gotchas worth knowing" with the commit hash. The agent can `git show <hash>` to dig.
- **Sean owns Status, Scope (§3), Success Criteria (§2), and Risks (§7).** Agents propose changes via PR-style edits, Sean accepts.
- **Update "Last meaningful update" when any section changes substantively.** Typo fixes don't count.

---

## 1. The end-state runtime

the agent's runtime is a **fully-functional ClojureScript environment** the agent has total authorship over. We give the agent a database, a scratch atom, a small set of default functions, and an initial schema that demonstrates how to extend everything. Beyond that, anything valid Clojure goes — defmacro, defprotocol, deftype, ns-management, requiring npm packages, generating embeddings, building a vector-search index, the lot. The thesis: **good primitives, the LLM learns the best way to manage its own context.**

### 1.1 What the agent sees on connect

A fresh QuickJS context with the bundle loaded. Pre-wired:

- A **Datascript connection** living at `(:db/conn @ctx)`. Holds the agent's persistent state.
- A **`ctx` atom** — `{:db/conn …, :messages …, :instructions …}` plus whatever the agent puts in. Ephemeral scratch (rebuilt from DB on resume; not persisted directly).
- An **initial Datascript schema** for messages, instructions, and persisted forms. Same schema demonstrates the patterns the agent will use to extend it (turtles).
- A handful of **default reactors** registered against the Datascript conn via `d/listen!`. They react to incoming transactions (user messages, system events, tool results) by transacting responses. The agent overrides by re-registering with the same key. There is no agent-loop — the runtime is reactive (see §4.7).
- A `(context)` fn that projects `@ctx` into the prompt-shaped string used when the agent is itself called by the model layer. The agent overrides by redefinition.
- Standard libraries pre-required: `malli.core`, `malli.generator`, `malli.instrument`, `edamame.core`, `clojure.test`, `clojure.test.check`, `datascript.core`, plus the agent's own `agent.user` namespace as the working surface.
- A `(help)` fn the agent calls to get oriented.

### 1.2 Forms are the unit of persistence

Every valid top-level form the agent emits — `defn`, `def`, `defmacro`, `defprotocol`, `register-entity!`, `transact!`, anything — is stored as a row in the database. **Eval succeeds → form persists. Eval throws → form does not persist** (but the error is captured in the trace).

This sidesteps file-editing entirely. The agent doesn't manage source files; it emits forms. The harness stores the source string + a parsed dependency set. On resume, the harness topo-sorts the form sequence by symbol references and re-evals each in order. Redefining a name is fine — latest version wins on replay; earlier versions live in form history.

The "library" (curated subset of admitted functions with specs and tests) is a **lens** on top of this: a form qualifies for the library if it's a `defn` with `:malli/schema` metadata and has a passing paired test. Library admission is a tag, not a separate storage path. All forms persist; some additionally earn the library lens.

### 1.3 The substrate is the means, not the goal

Self-hosted ClojureScript is the substrate that delivers this vision. We chose it because:

1. **Real Malli** — `m/validate`, `m/explain`, registry, var-metadata introspection (`(meta var)`).
2. **Generative testing** — `malli.generator` + `test.check` work the way malli's docs assume; sci breaks down at exactly this surface.
3. **Real macros + protocols + deftype** — defmacro and defprotocol/extend-protocol/deftype work natively. Required for malli's instrumentation, for `clojure.test`, and for any agent-emitted code that uses Clojure's full metaprogramming.
4. **Return-value classification** — every form's eval returns a real CLJS value (Var with metadata, tagged map, plain value). The gate inspects values, not source.

shadow-cljs `:target :bootstrap` is the build path. The bundle includes the CLJS compiler (`cljs.js`) plus all of the above; `cljs.js/eval-str` compiles agent source to JS at runtime against a per-session compile-state atom.

### 1.4 The trajectory we're on

This MVP ships the smallest version that lets us put an agent in front of a CLJS environment and **observe**. Get it playing fast; tighten and extend in response to what actually happens. Decisions deferred to "after observation":

- Resume vs init function naming — TBD.
- Atom→DB add-watch sync — TBD whether the harness wires it automatically or the agent does.
- `core.async` adoption — agents can opt in for complex coordination; default is just listeners.
- Dynamic npm `require` — eventual; not MVP.
- Vector DBs / embeddings — agent opts in once npm interop lands.

These are flagged in §6.

## 2. Success criteria

The substrate is MVP-ready when all of the following pass in the test harness:

### 2.1 Bundle smoke (the load-bearing first demo)

```clojure
;; eval-1
(require '[malli.core :as m])
(m/validate :int 42)
;; => true

;; eval-2 — attr-map form, idiomatic per seon
(defn double-it
  "Doubles n."
  {:malli/schema [:=> [:cat :int] :int]}
  [n]
  (* 2 n))
;; => #'agent.user/double-it
;; (meta v) returns {:malli/schema [:=> [:cat :int] :int] :name double-it :ns agent.user :doc "Doubles n." :line ... :column ...}

;; eval-3 (proves persistence across calls)
(double-it 21)
;; => 42
```

### 2.2 Forms-as-persistence

- Agent emits a sequence of forms across multiple turns. Each valid form lands in the DB with its source, turn number, and parsed dependency set.
- Eval errors do not persist (form is dropped; error reaches the agent in the response envelope).
- Querying the form history returns the ordered sequence: `(query [:find ?src ?turn :where [?f :agent.form/source ?src] [?f :agent.form/turn ?turn]])`.

### 2.3 Replay-on-resume

- Kill the QuickJS session.
- Restart with the same DB.
- The harness reads the persisted forms, topo-sorts by dependency, and re-evals each.
- The agent finds: same defs callable, same atom state (rebuilt from DB), same `(library)` listings, same `(messages)` history.

### 2.4 Override default fns

- Agent emits a new `(defn context [ctx] …)`. It replaces the default. Subsequent prompts use the agent's projection.
- Same for `(health)` and `(agent-loop)`.
- The override is a regular form — same persistence, same replay.

### 2.5 Library gate (the lens)

- A `defn` with `:malli/schema` attr-map metadata + a paired `(deftest test-name …)` with passing assertions is admitted to the library.
- `(library)` lists it.
- Library-admitted fns also get auto-generated property tests via `mg/check` (N=25 default), failures recorded.
- A `defn` without spec or test still persists and is callable; it's just not in the library lens.

### 2.6 The four real libraries load

`malli.{core,error,generator,instrument,util}`, `clojure.test.check.{core,generators,properties}`, `edamame.core`, `datascript.core`, `cljs.test` — all `require` cleanly inside the bundle.

### 2.7 Performance ceiling (informational, not gating)

- Bundle parse on session start: target <3s.
- Per-form `eval-str`: target <300ms median.
- Memory after 100 turns: target <500 MB.
- Replay 100 forms on resume: target <30s.

If any are 10× worse, that's a red flag we discuss before extending.

## 3. Scope

### 3.1 In scope (MVP)

- `harness/cljs-bundle/` — shadow-cljs project producing `dist/agent-bundle.js`.
- Replacement of scittle in `harness/sidecar/sidecar.mjs`. Bundle loads in place; host bridge (`globalThis.__agent__`) keeps the same shape; `__agent_eval` rewritten to call into the CLJS-side `agent.eval/eval-source!`.
- New CLJS namespaces under `harness/cljs-bundle/src/agent/`:
  - `agent.bootstrap` — initializes compile state and the `ctx` atom.
  - `agent.eval` — edamame split → per-form eval against shared compile-state atom.
  - `agent.classify` — return-value classification (Var? tagged-map? plain value?).
  - `agent.persist` — every successful form's source + parsed deps lands in DB.
  - `agent.replay` — topo-sort persisted forms and re-eval on resume.
  - `agent.schema` — Malli registry (lifted from `seon.schema`).
  - `agent.datascript-schema` — `malli->datascript` derivation (lifted-and-trimmed from `seon.db.schema`).
  - `agent.test` — `deftest`/`is` over `cljs.test`, with `:agent/tests-fn` metadata.
  - `agent.gate` — library-admission lens (specs + tests + generative).
  - `agent.user` — agent-facing namespace with default `context`/`health`/`agent-loop`/primitives.
  - `agent.host` — host bridge surface (reserved namespace; agent can't redefine).
- An **initial Datascript schema** for messages, instructions, persisted forms, and ctx-state extensions. Acts as both the runtime and the teaching example.
- `test-sidecar.mjs` exercising §2 success criteria end-to-end.

### 3.2 Deferred (named, not skipped)

These are part of the long-term vision; they're not MVP because we want to observe an agent in the simpler runtime first.

- **Dynamic npm `require`** for arbitrary packages (vector DBs, embeddings, anything CLJS↔JS-interop-compatible). Architecturally clear; gated on observing what the agent reaches for.
- **Atom→DB `add-watch` auto-sync.** The agent can use atoms freely as scratch; whether the harness automatically mirrors atom changes into the DB is open. MVP gives the agent the building blocks (atoms work, `add-watch` works, transactions work) and lets it choose its discipline.
- **Checkpointable session snapshots.** Beyond replay-from-forms, a snapshot mechanism that captures compile-state + atom state for instant resume. MVP just replays.
- **Sourcemap-aware error unmangling** for agent error messages.
- **Bundle splitting / lazy loading.** One concatenated bundle for MVP.
- **Trajectory record format, persona generation, watchdog, MCTS branching.** Separate specs.

### 3.3 Out of scope (skipped)

- Hot-reloading the bundle. Sessions get a fresh parse.
- Bundle minification beyond shadow-cljs's `:release` default.

### 3.4 Explicitly preserved

- The boundary contract: the agent's only tool is `eval(source) → {value, persisted, library_admitted, error, trace_delta}`.
- The JSON-RPC-over-stdio protocol with the Python driver.
- The `@sebastianwessel/quickjs` substrate.
- The Datascript bridge concept (host wires Datascript into the QuickJS context); the surface and schema details are MVP scope per §3.1.

### 3.5 Reserved namespaces

Only `agent.host.*` is reserved. The agent has free reign over `agent.user.*`, `user.*`, and any other namespace it wants to create for organization. (Earlier draft restricted to `agent.user.*` / `user.*` only; relaxed because the agent's autonomy over namespace structure is part of "fully functional CLJS.")

## 4. Architecture

### 4.0 Two state-change channels (only)

Anything the agent or host wants to persist or share crosses one of two channels. There are no others.

| Channel | Lifetime | Use |
|---|---|---|
| **`ctx` atom** (`agent.user/ctx`) | Ephemeral; in-memory only; rebuilt at session start | Db conn handle, session metadata, agent's in-flight scratch (caches, compiled regex, intermediate computation) |
| **DB transactions** | Durable; the source of truth; replayable | Messages, instructions, persisted forms, errors, heartbeats, tool requests/results, anything the agent wants to remember |

The `ctx` atom holds the conn (`(:db/conn @ctx)`) but is *itself* not persisted — nothing in the atom survives a process restart unless the agent transacts it into the DB. This is the discipline: scratch goes in the atom, history goes in the DB.

### 4.1 Layers (bottom to top)

```
Python driver (Verifiers MultiTurnEnv)
       ↕ JSON-RPC over stdio
Node sidecar (sidecar.mjs)
       ↕ @sebastianwessel/quickjs runSandboxed
QuickJSContext (one per the agent session)
   ├── agent-bundle.js (shadow-cljs :target :bootstrap output, concatenated)
   │     ├── cljs.{core,js,analyzer,compiler,tools.reader}
   │     ├── malli.{core,error,generator,instrument,util}
   │     ├── clojure.test.check.{core,generators,properties}
   │     ├── edamame.core
   │     ├── datascript.core
   │     ├── cljs.test
   │     └── agent.{bootstrap,eval,classify,persist,replay,schema,
   │              datascript-schema,test,gate,user,host}
   ├── globalThis.__agent__       (host bridge — Datascript JS API + library hooks)
   ├── globalThis.__agent_eval    (entry point: source → JSON envelope)
   ├── compile-state atom         (per-session, shared across cljs.js/eval-str calls)
   └── ctx atom                   ({:db/conn …, :messages …, :instructions …, …})
       ↕ agent.eval/eval-source!
agent-emitted Clojure source
```

### 4.2 The `ctx` atom

A regular CLJS atom, named `agent.user/ctx`, holding a map. Pre-loaded at session start with:

- `:db/conn` — the Datascript connection. **Ephemeral**: not persisted directly; rebuilt from DB facts on resume.
- `:messages` — the conversation history reference (the actual messages live in DB; this is a query view).
- `:instructions` — the current system-prompt-shaped guidance the agent's `(context)` fn projects from.
- Anything the agent puts in. Agents should treat it as scratch.

The agent reads via `@ctx`. Writes via `(swap! ctx assoc …)` or `(reset! ctx …)`. The harness does not auto-sync atom changes to DB in MVP — the agent chooses when to call `(transact! …)` to persist something.

Why an atom and not just the DB: ephemeral state matters. The Datascript conn itself can't go in the DB. Compiled regex objects, in-flight HTTP futures, an LRU cache the agent built — these are valid scratch. The atom is the place for them.

### 4.3 The DB (Datascript) — initial schema

The initial schema is small. It demonstrates the patterns the agent will use to extend it.

```clojure
{;; Messages — every input/output/intermediate the agent or user produces
 :agent.message/role     {:db/valueType :db.type/keyword}    ; :user :assistant :system :tool
 :agent.message/content  {:db/valueType :db.type/string}
 :agent.message/turn     {:db/valueType :db.type/long :db/index true}
 :agent.message/parent   {:db/valueType :db.type/ref}        ; for branching/edits

 ;; Instructions — initial + dynamic guidance the (context) fn reads
 :agent.instruction/source  {:db/valueType :db.type/keyword} ; :seed :agent :user :system
 :agent.instruction/active  {:db/valueType :db.type/boolean}
 :agent.instruction/content {:db/valueType :db.type/string}
 :agent.instruction/turn    {:db/valueType :db.type/long}

 ;; Persisted forms — every valid Clojure form the agent has emitted
 :agent.form/source     {:db/valueType :db.type/string}
 :agent.form/turn       {:db/valueType :db.type/long :db/index true}
 :agent.form/symbol     {:db/valueType :db.type/symbol :db/index true}  ; the def'd name, if any
 :agent.form/depends-on {:db/valueType :db.type/symbol :db/cardinality :db.cardinality/many}
 :agent.form/library    {:db/valueType :db.type/boolean}     ; admitted to library lens?
 :agent.form/spec       {:db/valueType :db.type/string}      ; pr-str of :malli/schema if present

 ;; Library — admitted-fn metadata snapshots for fast lookup
 :agent.library/name        {:db/valueType :db.type/symbol :db/unique :db.unique/identity}
 :agent.library/spec        {:db/valueType :db.type/string}
 :agent.library/admitted-at {:db/valueType :db.type/long}
 :agent.library/form        {:db/valueType :db.type/ref}}
```

The agent extends the schema with `(agent.schema/register-entity! ::my-thing [:map …])` — same Malli registry, same auto-derivation to Datascript schema (the `seon.db.schema/malli-map->datalevin-schema` pattern, ported). When the registry changes, the DB schema is mirrored.

This is the "turtles" piece: the schema for messages and forms is *itself* defined via `agent.schema/register-entity!`, so the agent reads the source of `agent.bootstrap` and sees how to extend.

### 4.4 Form-based persistence

Every successful eval of a top-level form persists to DB. The flow:

```
1. Agent emits source (one or many top-level forms in one eval call)
2. agent.eval/eval-source! splits via edamame.core/parse-string-all
3. For each form:
   a. cljs.js/eval-str against the session compile-state
   b. If eval throws → record error in trace, do not persist this form
   c. If eval succeeds → classify the return value (agent.classify):
      - Var → derive name, ns, :malli/schema metadata, :agent/tests-fn metadata
      - Tagged map ({:agent/op …}) → record op + payload
      - Other value → record as plain value
   d. Parse the form's symbol references (edamame walk) to derive depends-on
   e. Transact :agent.form/* + :agent.library/* (if it earns the lens)
4. Return JSON envelope: {value, persisted: [...], library_admitted: [...], error, trace_delta}
```

**Redefinition semantics**: latest wins per `:agent.form/symbol`. If the agent emits `(defn foo [n] (* 2 n))` on turn 5 and `(defn foo [n] (* 3 n))` on turn 12, both forms persist as history but replay only re-evals the latest. The earlier form is reachable in DB for the agent to inspect.

**Side-effect-in-def caveat**: if a form has a side effect during eval (e.g. `(transact! …)` at the top level), replay re-runs it. For pure defs this is fine; for transactions it means DB facts may double if not idempotent. MVP punts on this — the agent should keep top-level forms pure-or-idempotent. We watch and react if it bites.

### 4.5 Replay on resume

On session start, the harness checks for an existing DB. If one's there:

```
1. Load DB facts into a fresh Datascript conn
2. Read all :agent.form/* entities, ordered by turn
3. For each :agent.form/symbol, keep only the latest turn (redefinition winnowing)
4. Topo-sort the surviving forms by :agent.form/depends-on
5. Re-eval each form's :agent.form/source against a fresh compile-state
6. Atoms get rebuilt by their defining forms; the (ctx) atom is initialized
   with :db/conn pointing at the loaded conn
7. Session is now equivalent to where it left off
```

If a form fails to re-eval (e.g. a dependency was retracted), it's flagged in the resume report and skipped. The agent is told what didn't replay so it can decide.

### 4.6 The library gate (a lens, not a store)

A form earns the library lens when:

- It's a `defn` (return value is a `Var`).
- The Var's metadata has `:malli/schema` matching the canonical Malli function schema shape (`[:=> ...]` or `[:function ...]`).
- The Var's metadata's `:malli/schema` is a real Malli schema (parses via `m/schema`; references resolve through `agent.schema`'s registry).
- A paired `deftest` exists in the same eval (or already in the form history) whose `:agent/tests-fn` metadata matches the defn's name.
- Running the test fn produces ≥1 pass and 0 fails.
- (Optional, configurable) `mg/check` over the spec produces 0 failing inputs in N=25 generated cases.

When all are met, the form is also tagged `:agent.form/library true` and a snapshot row goes in `:agent.library/*`. The agent's `(library)` query reads the snapshot.

When any are not met, the form still persists, is still callable; it's just not library-admitted. The eval response tells the agent which gate it missed and how to fix it. Failure is informational, not blocking.

### 4.7 The reactor model — DB as the universal interface

**The runtime is reactive, not loop-driven.** Every external event — user message, tool result, system signal, clock tick — is a Datascript transaction with `:tx/source` metadata identifying the origin. The agent registers listeners (`d/listen!`) against the conn; each listener gets the tx-report, filters by source, and reacts by transacting back with `:tx/source :agent`. There is no `(agent-loop)`, no polling, no special "receive input" host fn.

#### 4.7.1 Tx-meta convention

Every transaction carries metadata. The harness and agent both honor it:

```clojure
{:tx/source :user      ; one of :user :agent :system :tool :clock
 :tx/turn   N          ; monotonic, set by host or agent
 :tx/cause  <ref>      ; optional: db/id of the tx this one responds to
 :tx/at     <ms>}      ; wall-clock at tx time
```

Listeners filter on `(:tx/source tx-meta)`. The agent's writes carry `:tx/source :agent` so other listeners (or the agent's own debug listeners) can distinguish.

#### 4.7.2 External events as DB transactions

| Event | Tx shape | Source |
|---|---|---|
| User message | `{:agent.message/role :user :agent.message/content "…" :agent.message/turn N}` | `:user` |
| Tool/MCP result | `{:agent.tool-result/request-id <id> :agent.tool-result/value …}` | `:tool` |
| Resume signal | `{:agent.session/event :resumed :agent.session/at <ms>}` | `:system` |
| Heartbeat probe | `{:agent.heartbeat/at <ms>}` | `:system` |
| Clock tick | `{:agent.clock/tick N :agent.clock/at <ms>}` | `:clock` |
| Schema change ack | `{:agent.schema/changed <delta>}` | `:system` |
| Driver control | `{:agent.control/op :pause}` etc. | `:system` |
| Error from harness | `{:agent.error/kind … :agent.error/message …}` | `:system` |

All events are facts. The agent reacts via listeners, never via a special host primitive.

#### 4.7.3 Default reactors

`agent.bootstrap` registers a small set of default reactors at session start. The agent overrides any of them by re-registering with the same listener key.

```clojure
;; conn lives at (:db/conn @ctx); agent.user/conn is a convenience alias

(defn react-to-user-message
  "Default user-message reactor: transact an assistant reply.
  Override by re-registering :default-user-reactor with new behavior."
  [{:keys [tx-data tx-meta] :as report}]
  (when (= :user (:tx/source tx-meta))
    (doseq [msg (new-user-messages tx-data)]
      (transact-with-meta!
        [{:agent.message/role :assistant
          :agent.message/content (default-respond msg)
          :agent.message/in-reply-to (:db/id msg)
          :agent.message/turn (next-turn)}]
        {:tx/source :agent}))))

(defn react-to-heartbeat
  "Default heartbeat: ack with current liveness state. Override to add
  agent-specific health (queue depth, model latency, etc.)."
  [{:keys [tx-data tx-meta]}]
  (when (and (= :system (:tx/source tx-meta))
             (heartbeat-probe? tx-data))
    (transact-with-meta!
      [{:agent.heartbeat/response :ok :agent.heartbeat/at (now)}]
      {:tx/source :agent})))

;; Registered at session start in agent.bootstrap
(d/listen! conn :default-user-reactor react-to-user-message)
(d/listen! conn :default-heartbeat-reactor react-to-heartbeat)
```

`(d/listen! …)` calls are **regular forms** that persist and replay like any other. On resume, the listener-registration forms re-execute and reattach to the new conn. Override = re-register with the same key (Datascript replaces the listener for that key). No special protocol.

#### 4.7.4 Listener safety

Listeners that throw will print to console by default in Datascript. The harness wraps every default listener in a try/catch that transacts the error as an `:agent.error/*` fact. The agent's error reactor (default registered) decides what to do — log, retry, escalate, give up. Errors are facts; recovery is reactor logic.

#### 4.7.5 The `(context)` projection

`(context)` is the one default fn the agent overrides via redefinition (not via re-registration), because it's a pure read of `@ctx` used by whatever model-layer calls into the agent's runtime. Default:

```clojure
(defn context
  "Project @ctx into the prompt-shaped string returned to the model layer.
  Override to change what the agent sees on each turn."
  []
  (let [{:keys [messages instructions]} @ctx]
    (str instructions "\n\n" (recent-messages-text messages 20))))
```

Override is a normal `(defn context …)` form — same persistence, same replay.

#### 4.7.6 The sidecar's job collapses

With the reactor model, the Node sidecar (`harness/sidecar/sidecar.mjs`) becomes a **bidirectional DB↔RPC adapter**:

- **RPC in → tx in.** The driver's `{method: "session.user-message", params: {content}}` becomes a `transact!` of `:agent.message/role :user` with `tx-meta {:tx/source :user}`.
- **Tx out → RPC out.** The sidecar listens for `:agent.message/role :assistant` txs (`:tx/source :agent`) and relays them back as RPC responses. Turn boundary = first assistant message after a user message.
- **Eval RPC** still exists for the agent to emit forms (replaces what `session.eval` does today).
- **Tool calls**: agent transacts `:agent.tool-request/*`; the sidecar (or a tool-handler reactor) sees it, makes the actual I/O call, transacts `:agent.tool-result/*` with `:tx/source :tool`. Same pattern.

The agent never sees JSON-RPC. It sees a database that changes, and reacts.

### 4.8 Eval flow per turn (concrete)

```
Driver:    {method: "session.eval", params: {session_id, source}}
   ↓
Sidecar:   evalCode(`export default __agent_eval(${JSON.stringify(source)})`)
   ↓
QuickJS:   __agent_eval(source) → agent.eval/eval-source!(source)
   ↓
CLJS:      forms = (edamame.core/parse-string-all source)
           for each form:
             value = (cljs.js/eval-str compile-state form-source 'agent.user opts)
             if error → record in trace, continue
             classified = (agent.classify/classify value)
             deps = (extract-symbol-deps form)
             (transact! [(form->datom form classified deps turn)])
             when (admits-to-library? classified) → (transact! library-snapshot)
           return {:value last-value
                   :persisted [...]
                   :library_admitted [...]
                   :error nil
                   :trace_delta [...]}
   ↓
Sidecar:   relays JSON envelope back through JSON-RPC
```

### 4.9 Session lifecycle

Three phases: **init**, **run**, **resume**. The `init!` fn is the locus where everything that needs to happen at session start gets specified. The agent overrides it by emitting a new `(defn init! …)` form like any other override.

#### 4.9.1 Init (fresh session)

```clojure
(defn init!
  "Wire up the session. Override to add defaults, custom listeners, or initial state."
  []
  ;; 1. Create the Datascript conn with initial schema
  (let [conn (d/create-conn (agent.datascript-schema/initial-schema))]

    ;; 2. Initialize the ctx atom (the agent's only ephemeral state surface)
    (reset! ctx {:db/conn    conn
                 :session/id (random-uuid)
                 :session/started-at (now)})

    ;; 3. Register default listeners — each is a filter-fn + handler-fn pair
    (d/listen! conn :default/user-message-handler
      (filter-and-handle
        (fn filter-user [tx-report]
          (= :user (-> tx-report :tx-meta :tx/source)))
        (fn handle-user [tx-report]
          ;; Render the agent's interface via (context), call the model,
          ;; transact the response. Default is a stub; agents override.
          (let [prompt (context)
                response (call-model prompt)]
            (transact-with-meta!
              [{:agent.message/role :assistant
                :agent.message/content response}]
              {:tx/source :agent})))))

    (d/listen! conn :default/heartbeat-handler ...)
    (d/listen! conn :default/error-handler ...)

    ;; 4. Announce we're ready so listeners that fire on :ready can run
    (transact-with-meta!
      [{:agent.session/event :ready :agent.session/at (now)}]
      {:tx/source :system})

    conn))
```

`init!` is called once at session start by the host bridge after the bundle parses. It runs to completion before any external traffic is allowed in. The agent reads `init!`'s source the same way it reads any other namespace — that's how it learns the patterns it can extend or override.

#### 4.9.2 Run (steady state)

External event arrives as a tx → all registered listeners fire → those whose filter-fn returns true execute their handler → the handler may transact more facts (which fires more listeners). The default user-message handler renders `(context)`, calls into the model, transacts the response. Tool calls, heartbeats, errors — all flow through the same listener mechanism.

The agent's interface — the prompt-shaped string the model sees — is whatever `(context)` returns at the moment a relevant tx fires. That projection re-renders on every matching tx; if `(context)` reads from `@ctx` and the ctx changed since the last render, the model gets the updated view.

#### 4.9.3 Resume

```clojure
(defn resume!
  "Replay-on-start variant of init!. Loads persisted forms in dep order and
  re-evaluates them. The (defn init! ...) the agent emitted is replayed too,
  so all of the agent's customizations re-attach. After replay, transact a
  :agent.session/event :resumed fact so reactors that care about resumption
  can run."
  []
  (let [conn (d/create-conn (agent.datascript-schema/initial-schema))]
    (reset! ctx {:db/conn conn :session/id (existing-session-id) ...})
    (let [forms (load-persisted-forms)]
      (doseq [form (topo-sort-by-deps forms)]
        (try (cljs.js/eval-str form ...)
             (catch :default e
               (record-replay-failure! form e)))))
    (transact-with-meta!
      [{:agent.session/event :resumed :agent.session/at (now)}]
      {:tx/source :system})
    conn))
```

`resume!` is essentially `init!` minus the schema-creation (already established by replay) plus a topological replay of every persisted form. Since `init!` is itself a persisted form (the agent's override of it), replaying it re-attaches every listener and every default the agent customized. The `:agent.session/event :resumed` fact at the end is what reactors filter on if they want to do resume-time work (e.g., greet the user back, refresh stale data).

The host decides which one to call by checking whether persisted forms exist for the session-id.

#### 4.9.4 What "the magic" looks like

When we say "the magic gets specified in init," we mean: the entire wiring of conn + ctx + listeners + initial reactor set lives inside `init!`. The agent reads `init!`'s source, sees how each piece connects, and customizes by override. There's no hidden setup elsewhere. If something happens at session start, it happens in `init!` (or a fn `init!` calls).

This is the surface that teaches the agent the system. New session → read `init!` → see the patterns → extend.

### 4.10 Locked substrate (the contract Carve-out 2 builds against)

These are invariant. They get built the same way regardless of how the still-open policy questions in §6 resolve. An implementation agent reading this spec can treat them as load-bearing contracts.

| # | Component | What it is | Notes |
|---|---|---|---|
| 1 | Bundle build | shadow-cljs `:target :bootstrap` + concatenation | Carve-out 1 |
| 2 | `agent.eval/eval-source!` | edamame split → per-form `cljs.js/eval-str` against shared compile-state | Returns each form's value + classification |
| 3 | `agent.classify` | `var?` / `(meta v)` / tagged-map detection | Pure data-shape inspection of return values |
| 4 | `agent.persist` | `:agent.form/*` row on every successful eval; errors don't persist | Form-source + parsed deps + classification tag |
| 5 | Symbol-dependency parser | edamame walk → set of referenced known symbols | Imperfect; covers ~95%; we measure |
| 6 | `agent.replay` | topo-sort persisted forms by deps, latest-wins per name, re-eval each | On session start; reports unreplayable forms |
| 7 | `agent.schema` | Malli registry (`register!`, `register-entity!`, `registered-schemas`) | Lift of `seon.schema` |
| 8 | `agent.datascript-schema` | `malli->datascript` derivation | Port-and-trim of `seon.db.schema/malli-map->datalevin-schema` |
| 9 | `agent.gate` | Library lens: `:malli/schema` + paired test + `mg/check` → `:agent.library/*` snapshot | Form persists either way; gate decides admission |
| 10 | `agent.user/ctx` atom | Holds `:db/conn` + ephemeral scratch | Not persisted directly; conn rebuilt from facts on resume |
| 11 | Sidecar adapter | RPC in → tx in; tagged tx out → RPC out | Pattern locked; mapping table partially policy |
| 12 | Tx-meta convention | `{:tx/source, :tx/turn, :tx/cause, :tx/at}` | Source set extends; shape stable |
| 13 | Error-as-facts | eval/replay/handler errors → `:agent.error/*` rows | Error handling is reactor logic, not bespoke |

**What is NOT in the locked contract** (still policy, deferred until observation):
- The reactor wrapper API (`react!`-on-query vs listener-with-filter) — both are ~50 LOC on top of `d/listen!`. Final shape pending §6.
- Default reactor implementations (which queries/handlers ship by default) — see §6.
- Async patterns inside handlers — see §6.
- Resume-event hook framing — see §6.

Carve-out 2 builds rows 2-13 (row 1 is Carve-out 1's territory). The reactor wrapper lands at the end of Carve-out 2 as a small module once the policy is decided; it's not blocking.

## 5. Hurdles to clear

Each is a concrete obstacle. Each has a gate that says "we've cleared it" and a TBD-fallback for the most likely failure. Ordered MVP-first: get an agent playing as fast as possible.

**Hurdle 1 — Bundle compiles and loads in QuickJS.** Smallest viable shadow-cljs config (cljs.core + cljs.js + malli.core + edamame.core), concatenated bundle, loaded into a `@sebastianwessel/quickjs` context, §2.1 smoke passes. **Gate**: §2.1 round-trip works. **Fallback if `eval-str` is async-only in QuickJS**: use the lower-level sync `cljs.js/eval`, or pre-load every entry namespace and provide a sync `:load`. **Unblocks**: every other hurdle.

**Hurdle 2 — All four library families load.** Malli (core/error/generator/instrument/util), test.check (core/generators/properties), edamame, Datascript, cljs.test all `require` cleanly in the bundle. **Gate**: §2.6. **Fallback if Datascript's `extend-clj` macros break self-host**: bind the npm package via `globalThis.__agent__` (same pattern as the spike, ~30 min). **Unblocks**: ctx atom + DB schema work.

**Hurdle 3 — Initial schema + ctx atom + default fns.** Define the initial Datascript schema (§4.3), wire it into `agent.bootstrap`, define `agent.user/ctx` atom, ship default `context`/`health`/`agent-loop` (§4.7). **Gate**: a fresh session boots, `@ctx` returns the expected map, `(context)` returns the default projection, `(health)` returns `:ok`. **TBD**: schema fields will need iteration once we put an agent in front of it; the §4.3 list is a starting bet. **Unblocks**: form-based persistence.

**Hurdle 4 — Form-based persistence.** Implement `agent.persist` (transact `:agent.form/*` per successful form) and `agent.classify` (return-value classifier). Errors don't persist. Symbol-dependency parsing via edamame walk. **Gate**: §2.2. **TBD**: redefinition semantics (latest-wins vs. all-versions-callable). MVP picks latest-wins; revisit if we observe agents intentionally building up state via repeated defs. **Unblocks**: library lens, replay.

**Hurdle 5 — Replay on resume.** Implement `agent.replay`: topo-sort persisted forms and re-eval on session start. **Gate**: §2.3 — kill the session, restart with the same DB, agent finds same state. **TBD**: what to do when a form fails to re-eval (skip + report? abort + manual triage?). MVP: skip, report. **Unblocks**: cutover (we won't cut over without a working resume).

**Hurdle 6 — Library lens (gate as opt-in).** Implement `agent.gate`: detect spec+test combo, run the test, optionally run `mg/check`, tag form `:agent.form/library true` and snapshot to `:agent.library/*`. Forms that miss the gate persist anyway. **Gate**: §2.5. **TBD**: whether `mg/check` over `:=>` schemas works in self-host. **Fallback**: hand-roll the property-test loop (~40 LOC). **Unblocks**: override + cutover.

**Hurdle 7 — Override default fns.** No new code; this is a test that the persistence + classify + replay machinery handles redefinitions of the default fns correctly. **Gate**: §2.4 — agent overrides `(context)`, the override persists, replay restores it. **Unblocks**: cutover.

**Hurdle 8 — Cutover.** Replace scittle in `harness/sidecar/sidecar.mjs`. Delete the regex tokenizer. Delete `harness/cljs/bootstrap.cljs`. `test-sidecar.mjs` updated to exercise §2. **Gate**: existing test scenarios pass against the new substrate. **Unblocks**: putting an agent in front of it.

After Hurdle 8 we **stop and observe**. The Python driver work is sequenced after this; once an agent is emitting forms against the live substrate, we'll see what's right and what isn't and edit this spec accordingly.

## 6. Open questions

These are explicit "we'll know when we get there" markers. Each has a fallback so a bad answer doesn't kill the project. When one resolves, fold the answer into §4 and delete the entry here (git keeps the question).

1. **Resume vs init function.** Does the agent see a `(resume!)` it can override (post-replay hook), or does it just react to a `:agent.session/event :resumed` tx like any other system event? Leaning toward the latter (replay → host transacts a resumed event → agent's reactor decides what "resume" means for it). MVP: no special hook; resumed-event reactor pattern. Confirm when we observe.
2. **Atom→DB sync.** Whether the harness automatically `add-watch`es `agent.user/ctx` and mirrors changes into DB, vs. the agent calling `transact!` explicitly. MVP: explicit. The agent can wire `add-watch` itself if it wants; the primitives are there.
3. **Side-effect-in-def safety.** Replay re-runs every persisted form. If a form has a top-level side effect (transact, http call, file write) and isn't idempotent, replay double-fires. MVP: document + warn; don't enforce. Revisit if it bites.
4. **Form-dependency parsing depth.** Symbol references inside a form are the obvious deps. What about side-effects on resolved-elsewhere atoms? Macros that expand to references the static parse misses? MVP: edamame walks the form, collects all unbound symbols that resolve to known names. Imperfect; gets us 95%; we measure.
5. **Library gate verifier severity.** Do `mg/check` failures hard-reject from library lens, or soft-flag? MVP: soft-flag (admit with a warning) so generative mismatches don't block trivial functions; tighten if we see agents gaming it.
6. **Listener async pattern.** Datascript listeners fire sync as part of the tx. If a reactor needs to do heavy async work (LLM call), it should transact a request entity and let another reactor handle the I/O — not block the tx. MVP: this is the convention; document and watch. If agents reach for `core.async` go-blocks inside listeners, observe and decide whether to bless or steer away.
7. **Listener-error propagation.** Default behavior wraps listeners in try/catch and transacts errors as `:agent.error/*` facts. Open: should errors that fire inside the agent's *own* (non-default) listeners also get auto-wrapped, or is that too magical? MVP: only default listeners are auto-wrapped; agent-defined listeners own their error handling.
8. **Bundle size + parse time.** Research estimates 3-5 MB minified, ~3s parse in QuickJS. Real numbers TBD until Carve-out 1's report lands.
9. **Per-form `eval-str` latency.** Real numbers TBD. If median is >300ms, the agent's iteration speed degrades — we'd look at compile-state hot paths or batch evals.
10. **Memory growth across long sessions.** Real numbers TBD.
11. **DB persistence between sessions.** Datascript is in-memory. §2.3 "resume" requires the DB to survive a process restart. Three options: (a) Datascript + serialize-on-tx (or every N txs) to EDN file — simple, I/O-heavy; (b) append-only tx log + replay-on-resume — cheap writes, slow long-session resume; (c) migrate to Datahike now (built-in persistence; ~3-6× bundle size depending on which build) — solves natively but bigger bundle. **MVP bet: (a) — Datascript with serialize-on-tx-batch (every 10 txs or 5s, whichever first), EDN file under `harness/sessions/<session-id>/db.edn`.** Revisit if I/O dominates or if we're already migrating to Datahike for other reasons. This is substrate; needs to land in Carve-out 2 (call it Carve-out 2.5 if scope creep matters).

## 7. Risks

- **Bundle doesn't survive `@sebastianwessel/quickjs` sandbox** for some non-obvious reason (browser-globals shim, stack size, parsing quirk). Probability low-medium, impact high (Hurdle 1 fails). Mitigation: Hurdle 1 is the smallest possible test, fails fast, gives us early signal. Pivot would be a different QuickJS wrapper or Deno subprocess.
- **A library doesn't self-host cleanly.** Probability medium per library, impact low (each has a fallback in Hurdle 2). Cluster of 3+ failures would suggest the substrate is wrong.
- **Replay drift.** Forms that re-evaluate to a different state than they originally produced (timing, randomness, side-effect order). Probability medium, impact medium. Mitigation: §6.5; document behavior, let agents discover and adapt.
- **Initial schema is wrong.** Probability high; impact low (it's MVP, we revise). Mitigation: ship it, observe, edit.
- **Bundle perf is much worse than estimate.** Probability medium, impact medium (slow iteration). Mitigation: measure first, optimize only what hurts.

## 8. What I want from review

- §1 vision — does this match the system you're describing? Gaps?
- §2 success criteria — these are the gates; sign-off here defines "done enough."
- §3 scope — what's in/out/deferred, and the namespace policy relaxation.
- §4.3 initial schema — is this the right starting bet, or do you want different fields?
- §5 hurdle ordering — anything I should sequence differently? Anything missing?
- §6 open questions — for each, "MVP picks X" is the bet; sign off or override.
- §7 risks — anything weighted wrong?

After review I add this to "Active work items" in CLAUDE.md, commit the spec, kick off Hurdle 1.

## 9. Out of scope (tracked elsewhere)

- Python driver (`harness/driver/aria_driver.py`) — separate spec, blocked on this.
- Trajectory record format, scenario format, persona generation, watchdog, MCTS branching — all later phases of the broader the agent plan.
- a sibling project — independent project; this substrate doesn't touch it.

## 10. Gotchas worth knowing

*One-line entries with commit hashes. Add only when an issue is worth a future agent's time — not a changelog. Format: `- <date> — <one-line description> — <commit-hash>`*

*Empty until the first one lands.*
