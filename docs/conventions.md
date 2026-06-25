---
type: reference
status: active
tags: [reference]
---

# Seon Code Conventions

This is the agent-facing standards doc AND the rubric a code audit uses. It
describes the system as it actually is on the current branch — a dual CLJ +
CLJS system backed by datahike, with always-on instrumentation. If a convention
here disagrees with the running code, the running code wins and this doc is a
bug — flag it.

## Why These Conventions Matter

These aren't arbitrary style rules. They're the foundation for **AI agents to
write reliable software**.

When every function has:

- **Namespaced keys** → Agents can query "what accepts `:seon.agent.search/pattern`?" instead of guessing
- **Malli schemas** → Contracts are machine-readable. Property tests validate automatically. Every schema'd fn is instrumented at runtime.
- **Fully spec'd args (the hard rule)** → Contracts are complete. Map-in/map-out helps API *accretion* (add an optional field without breaking callers); fully-spec'd positional (`:catn`) is often the better shape for utilities. Pick by fit; the invariant is that every arg is named, spec'd, and validated.
- **Registered schemas** → A queryable database of all data shapes in the system. `schema/register!` derives the datahike attribute schema for free.

The result: agents can discover, compose, and validate code without
hallucinating interfaces.

---

## The Dual-Track System (CLJ + CLJS)

Seon is **one system split across two co-equal halves that converge at the wire
boundary**. The current pod ↔ wire-server split is the seed of this model.

- **JVM / server (`.clj`)** — the server and heavy processing. The
  **authoritative DB writer** (today the `wire-server` process). Heavy compute,
  durable storage, and the sole write path live here. In practice the live JVM
  side today is **basically just the datahike writer** — most of the broader
  `.clj` app (Integrant system, the `core.async.flow` routing backbone) is
  **paused**, kept for when JVM core-systems integration resumes. Treat
  `core.async.flow` as a parked JVM-track concern, not an active one.
- **On-device / pod (`.cljs`)** — runs on device (a long-running Node process
  today). Holds a **read-only replica of the DB** (local lazy db values; memory
  ∝ working set) and does **local function execution** (the agent loop, eval,
  render). It **forwards every write over a Unix socket** to the JVM writer; it
  never writes the store directly. Uses native CLJS `^:async`/`await` for
  Promise interop — **no `core.async` in the pod**.

### Lane Discipline: `.clj` / `.cljs` / `.cljc`

Surfaces use **`.cljs` files alongside `.clj` files** — the CLJS compiler reads
`.cljs`, the CLJ compiler reads `.clj`, neither sees the other's. Choose by
where the code runs:

- **`.cljs`** — on-device code: the agent loop, `seon.eval`, `seon.ctx`,
  `seon.render`, `seon.db` (the pod's read-local + write-forwarding face),
  `seon.agent.*`, the inspector UI (`seon.web.inspector`/`serve`).
- **`.clj`** — server/heavy code: the authoritative datahike writer and the
  Integrant-managed JVM app.
- **`.cljc`** — only genuinely platform-portable code (e.g. `seon.schema`,
  `seon.instrument`). Don't author a `.cljc` for a namespace that has a live
  sibling on the other track unless both sides converge on its shape.

The active focus is the CLJS pod + the datahike wire-server. The broader JVM
main-app integration is currently paused; assume CLJS-pod context unless a task
is explicitly JVM-track.

### The `.internal` namespace pattern

A public namespace stays small and whitelisted so its source renders cleanly
into agent context. **Complex plumbing moves to a sibling `<ns>.internal`
namespace** — un-whitelisted, not rendered into context, free to be as
intricate as it needs to be. The public ns is the discoverable contract; the
`.internal` ns is the machinery.

- `seon.db` (public, taught) ↔ `seon.db.internal` (commit machinery, arg
  normalization, conn resolution).
- `seon.agent.search` (public `grep`) ↔ `seon.agent.search.internal` (hard
  caps, envelope helpers, the rg `--json` parser, the allowlist gate).

Reach for `.internal` whenever a public fn's helpers would otherwise bloat the
context the agent reads. Keep the public surface to the named request/response
schemas plus the verb fns.

---

## Malli Schema Patterns

All public APIs use Malli schemas for contract specification. This enables:

- **Always-on instrumentation** — every public fn with `:malli/schema` is validated at runtime (see [Always-On Instrumentation](#always-on-instrumentation)).
- Generative testing via `mg/sample` + `m/validate` (JVM-track; see Testing Strategy)
- Self-documenting APIs for agents
- **Function discovery** — "What functions return `::grep-response`?" is a database query.

### Understanding `::` Keyword Syntax

The `::` creates **auto-resolved namespaced keywords**:

```clojure
;; INSIDE seon.agent.search namespace:
::pattern             ;; => :seon.agent.search/pattern
::match               ;; => :seon.agent.search/match

;; OUTSIDE (user code with alias):
(ns my-app.core
  (:require [seon.agent.search :as search]))

::pattern             ;; => :my-app.core/pattern  (WRONG namespace!)
::search/pattern      ;; => :seon.agent.search/pattern (correct)
```

**Inside your namespace, `::keyword` is the preferred form.** It's shorter,
refactor-safe, and guarantees the keyword matches the namespace that owns it.
Keyword namespaces ARE real code namespaces — `::subject` in `seon.email.message`
is `:seon.email.message/subject`, and the schema for that data lives in that
namespace. Only use explicit `:seon.foo/bar` form in cross-namespace references
or docstring examples showing external callers.

### Schema Registration

Register schemas using `schema/register!` with `::` auto-namespaced keywords.
`schema/register!` is the **single source of truth** for all attribute schemas:
register the Malli type and the system auto-derives the datahike attribute
declaration. You never write datahike schema by hand.

```clojure
(ns seon.agent.search
  (:require [seon.schema :as schema]))

;; Each registration is a separate form for easy editing
(schema/register! ::pattern
                  [:string {:min 1
                            :description "ripgrep regex pattern"}])

(schema/register! ::max-results
                  [:int {:min 1
                         :description "cap on matches returned"}])

(schema/register! ::line-number
                  [:int {:min 1}])
```

Identity attributes are declared via `{:seon.db/identity true}` on the
registered shape — there is **no** magic `:seon/id` key:

```clojure
(schema/register! ::doc-id [:string {:seon.db/identity true}])
;; entities addressed by lookup-ref: [::doc-id "d1"]
```

The 14-char id shape is minted with `seon.db/new-id!` — hand-written id strings
fail validation.

### Shared schema shapes — register once, reference everywhere

If the same shape appears in two or more registered schemas, the shape itself
must be a registered schema the others reference. Inlining the same
`[:string {:min 14 :max 14}]` across multiple `register!` calls is a smell.

```clojure
;; ONE canonical shape (lives in its owning ns)
(schema/register! :seon.db/id [:string {:min 14 :max 14}])

;; EVERY id attr references it — no inline shape
(schema/register! ::agent-id   :seon.db/id)
(schema/register! ::session-id :seon.db/id)
```

If the Malli→datahike bridge doesn't yet handle a reference shape you need, fix
the bridge — never duct-tape by inlining the shape at each site.

### Request/Response Schema Pattern

Define separate schemas for requests and responses with namespaced keys:

```clojure
;; Request schema — required + optional fields
(schema/register! ::grep-request
                  [:map
                   [::pattern ::pattern]                          ; required
                   [::paths {:optional true} ::paths]             ; optional
                   [::max-results {:optional true} ::max-results]])

;; Response schema — namespaced keys for outputs
(schema/register! ::grep-response
                  [:map
                   [::ok? ::ok?]
                   [::matches {:optional true} ::matches]
                   [::error {:optional true} ::error]])
```

### Public Function Pattern

**The hard rule: every public function must fully spec and validate ALL its
arguments and its return value** via `:malli/schema`. That is the only
invariant. Two argument shapes are sanctioned; choose by fit, not by default:

1. **Map-in / map-out** — one namespaced-keyword map in, one out. Reach for it
   when the function is an **API surface you expect to accrete** — adding an
   optional field never breaks callers, and the named request/response schemas
   are self-documenting and discoverable.
2. **Named positional** — each argument is a fully-namespaced-keyword-spec'd
   slot via Malli `:catn` (named positional), inside a `:=>`/`:function`
   schema. Often the **better shape for utilities** and for mirroring a
   well-known API (e.g. datahike). A 2-arg data transform reads more naturally
   positional than map-wrapped.

The invariant is **completeness of specs, not map-wrapping**: every argument is
named, spec'd, and validated, whether it sits in a map or a positional slot.
The violation is an *unspecced* or *bare-keyword* argument — NOT a positional
one. Do not flag a fully-spec'd positional public fn; that is a sanctioned
shape, not a smell.

#### Shape (a): map-in / map-out — for accreting API surfaces

```clojure
(defn ^:async grep
  "Search file contents under the allowed roots. Always resolves to a
   ::grep-response envelope (never throws — errors are values).

   Request keys:
     ::pattern     - Required. ripgrep regex
     ::paths       - Optional. roots to search
     ::max-results - Optional. cap on matches

   Response keys:
     ::ok?         - true/false
     ::matches     - on success, the hits
     ::error       - on failure, a guiding message"
  {:malli/schema [:=> [:cat ::grep-request] ::grep-response]}
  [{::keys [pattern paths max-results]}]
  (let [max-results (or max-results in/default-max-results)]   ; explicit or, not :or
    (try
      (let [roots (if (seq paths) (vec paths) (in/default-roots))]
        (cond
          (str/blank? pattern) (in/fail "::pattern is required and non-blank")
          (empty? roots)       (in/fail "nothing searchable — grant a root")
          :else                (in/success-from (in/exec-rg pattern roots max-results))))
      (catch :default e
        (in/fail (str "search failed — " e))))))
```

#### Shape (b): named positional via `:catn`

Each positional slot gets its own fully-namespaced spec. Use this for natural
data-processing fns or to mirror a well-known API (datahike does this — see
`seon.db/pull`, `seon.db/query`). The slot names in the `:catn` document the
positions; the return is still fully specced.

```clojure
(schema/register! ::attr :keyword)

(defn decode-edn-value
  "Decode a pulled value back to its real shape."
  {:malli/schema [:=> [:catn [::attr ::attr] [::value :any]] :any]}
  [attr v]
  ...)
```

Multi-arity is allowed when **every** arity is fully specced — use a
`:function` schema wrapping one `:=>` per arity (see `seon.db/transact!`, which
has a map-in arity AND two datahike-shaped positional arities). Use map-in/map-out
when you expect the surface to accrete optional fields; positional is fine — and
often clearer — for utilities and well-known-API mirrors.

### Private Helper Pattern

Private functions use positional args for internal convenience and may stay
unspecced. (Positional is also a sanctioned *public* shape — see shape (b) —
but public positional fns must spec every slot via `:catn`, whereas privates
need no specs.)

```clojure
(defn- extract-content [sdk-message]
  ;; Implementation with positional args, no spec
  ...)
```

### Handling Optional Values

Use `or` for defaults since destructuring `:or` doesn't apply when the key is
present with a nil value:

```clojure
;; WRONG - :or doesn't apply when {::model nil} is passed
(defn- impl [{::keys [model] :or {model default-model}}]
  ...)

;; CORRECT - explicit or handles nil values
(defn- impl [{::keys [model]}]
  (let [model (or model default-model)]
    ...))
```

(Remember: **optional = absent**. Never store nil. To clear a persisted field,
retract it explicitly — omitting a key means "leave unchanged".)

---

## Errors Are Values (agent-facing verb boundaries)

Functions an agent calls directly — the capability verbs — **return an
envelope, they do NOT throw**. The canonical shape:

```clojure
{::ok? true  …data…}                                   ; success
{::ok? false ::error "<guiding message>"
             ::raw-error "<underlying detail>"}        ; failure
```

This is a hard convention, not a style: an agent's eval must survive a bad call
and read the failure as data, the same way `seon.db/transact!` and
`seon.agent.search/grep` do. Two consequences:

1. **`^:async` verbs are excluded from instrumentation's throwing validator**
   (it would break the never-throws contract) — they're listed in
   `seon.instrument/skip-syms`. The `:malli/schema` stays as the discoverable
   contract; the function body's guards enforce shape and return the error
   envelope instead.
2. **Callers MUST read the envelope.** An eval can succeed while the write
   didn't happen (`::ok? false`). Translate cryptic underlying errors into a
   guiding `::error` and preserve the original at `::raw-error`.

When an error is genuinely a caller bug to fix vs. a core bug to report, tag it:
`:seon.error/data` carries `:seon.error/kind` (`:user-input` vs `:core-bug`).

---

## Always-On Instrumentation

All public functions with `:malli/schema` metadata are **instrumented at
runtime**. Every call validates inputs, outputs, and arity. **There is no "off"
mode** (a `SEON_INSTRUMENT` env var is a kill-switch for debugging only,
defaults ON).

Instrumentation is **DB-driven**, not a compile-time macro. The mechanism reads
the **program graph** — the `:seon.fn/spec` rows the analyzer persists for every
schema'd fn — and wraps from there. Two lifecycle points:

1. **Boot** — `seon.instrument/instrument-from-db!` walks every `:seon.fn`
   row in the DB and instruments the live var. The DB has the COMPLETE set; a
   compile-time `collect!` over the leaf namespace would miss most of it.
2. **Every fn-defining eval** — when an agent evals a `defn` with a
   `:malli/schema`, the same machinery instruments it on the spot (tee).

You never call `mi/collect!`, `mi/instrument!`, or `dev/start!`. Add
`:malli/schema`, reload, and the fn is instrumented. When you see an
instrumentation error, **read it and fix the root cause** — either you called
the function wrong, or the schema doesn't match reality. A wrong schema is a
bug.

**Opt-out** for the errors-as-values verbs lives in `seon.instrument/skip-syms`
(a set of fully-qualified symbols / `[ns fn]` pairs), because the CLJS analyzer
strips schema-prop markers from `:malli/schema` metadata.

### Schema Introspection

```clojure
(require '[seon.schema :as schema])

;; All schemas for a namespace
(schema/schemas-in-namespace "seon.agent.search")

;; Is a schema registered?
(schema/registered? ::pattern)
```

---

## Database Access

`seon.db` is the **sole database API**. Never touch `datahike.api` directly
outside `src/seon/db/`. Everything else uses `db/transact!`, `db/query`,
`db/pull`, `db/entity`, `db/listen!`.

On the pod, `seon.db` is a thin face over the dual-track wire boundary:

- **Reads are local and synchronous** — `query`/`pull`/`entity` resolve against
  the current db value (`@*conn*`, the read-only replica). Compose them in
  straight-line code.
- **Writes are forwarded to the JVM writer** — `transact!` is `^:async` and
  routes over the Unix socket to the authoritative writer. Await it (it
  auto-awaits) and you get an **envelope**, never a throw.

```clojure
(require '[seon.db :as db]
         '[seon.schema :as schema])

;; Register before you transact — transact! refuses unregistered attrs
(schema/register! ::name :string)
(schema/register! ::rank :int)

(db/transact! {::db/tx-data [{::name "Alpha" ::rank 1}]})

(db/query {::db/query '[:find ?n :where [?e ::name ?n] [?e ::rank ?r] [(> ?r 0)]]})
```

Every map key in and out of `seon.db` is fully namespaced under `:seon.db/*` —
that is what lets one Datalog query join the data in the DB to the functions
that operate on it. Reactions install via `db/listen!`/`unlisten!` by key; the
agent's own wake-up is a listener over newly-added
`:seon.agent.message/to` datoms targeting it.

See the `/datahike` skill (and the Datalog cheat sheet in the `seon.db`
namespace docstring) for the full query idiom set, lookup-refs, upsert, and
retraction.

---

## Schema Composition Across Namespaces

Provider namespaces extend base namespaces by referencing their schemas. This is
the EAV pattern: entities are bags of namespaced attributes. (The `seon.ai` /
`seon.ai.claude` example below is real **JVM-track** code — `.clj`, currently
paused. On the active CLJS pod the provider adapters are `seon.ai.anthropic` /
`seon.ai.openai-compat`. The schema-composition *pattern* is track-independent.)

### Base + Provider Pattern

```clojure
;; seon.ai — base namespace defines generic schemas
(schema/register! ::session-id :seon.db/id)
(schema/register! ::role [:enum "user" "assistant" "system"])

;; seon.ai.claude — provider extends base
(ns seon.ai.claude
  (:require [seon.ai :as ai]
            [seon.schema :as schema]))

(schema/register! ::message-id [:string {:seon.db/identity true}])

;; Reference base schemas in composite schemas
(schema/register! ::message-entity
  [:map
   [::message-id ::message-id]          ; identity attribute
   [::ai/role ::ai/role]                ; reference base!
   [::ai/content ::ai/content]          ; reference base!
   [::message-type ::message-type]      ; claude-specific
   [::cache-tokens {:optional true} ::cache-tokens]])
```

### Entity-Centric Thinking (EAV Pattern)

In datahike's EAV model, an entity is a bag of namespaced attributes, not a row
in a table. A single entity can carry attributes from multiple namespaces:

```clojure
;; This is ONE entity, not separate "rows" in different "tables"
{:seon.ai.claude/message-id "msg-abc12345678"
 ;; Base seon.ai attributes (generic to all providers)
 :seon.ai/role "assistant"
 :seon.ai/content "Hello!"
 :seon.ai/timestamp #inst "2026-06-25T..."
 ;; Claude-specific attributes (only present for Claude messages)
 :seon.ai.claude/message-type "assistant"
 :seon.ai.claude/cache-tokens 150}
```

**Why this matters:**

- No schema migration needed when adding provider-specific fields
- Queries can filter by any attribute from any namespace
- Generic code works on `:seon.ai/*` attributes; provider code adds its own

### When NOT to Use `:malli/schema`

Some types cannot be generated for property testing. Omit `:malli/schema` and
document expected types in the docstring instead — connection managers, atoms,
process handles, and other opaque runtime objects. (Prefer to keep such objects
out of public agent-facing surfaces entirely; the pod's `*conn*` is bound for
you, never threaded through call sites.)

### `:any` at third-party interface boundaries

The no-`:any` rule is the default **for seon-authored data** — it nudges agents
toward precise specs. But at a **third-party interface boundary** — where the
value is whatever an external library (datahike, a JS API) hands back and we do
not control its shape — `:any` is acceptable, because there is no honest tighter
type.

```clojure
;; datahike returns an arbitrary result set / pulled map / opaque db value.
;; :any is honest here — this is the documented exception, not a smell.
(defn query
  {:malli/schema [:=> [:cat ::query-request] :any]}
  [...])
```

The compliance checker flags **all** `:any`/`:some`/`:maybe` as a non-blocking
nudge; at a genuine boundary that warning is an accepted judgment call.

### Test Code Exemptions

Test namespaces (`*_test.cljs` / `*_test.clj`) are exempt from most conventions:

- **No `:malli/schema`** on `deftest` or test helper functions
- **No required arg shape** — test helpers may use bare positional args
- **No namespace docstrings** — tests are self-documenting via test names
- **Non-namespaced keys are fine** in test data literals

Conventions that **do** apply in tests:

- **Namespaced keys when calling production functions** — match the real API
- **Example tests at minimum** — generative tests where the schema is the unit
  (JVM-track); see Testing Strategy
- **Meaningful test names** — `grep-finds-matches-test`, not `test1`

---

## Converter Functions (Map-In Pattern)

When converting external data (SDK messages, raw JS payloads) to internal
entities, the map-in pattern fits well when you expect to add optional context
later without changing the signature:

```clojure
(defn sdk-message->entity
  "Convert an SDK message to a seon.ai message entity.

   Request keys:
     ::sdk-message   - Required. Raw SDK message map
     ::ai/session-id - Optional. Parent session to attach

   Returns an entity map addressed by an identity attr."
  [{::keys [sdk-message] ::ai/keys [session-id]}]
  (let [content (extract-content sdk-message)]
    (cond-> {::ai/message-id (db/new-id!)
             ::ai/content content}
      session-id (assoc ::ai/session-id session-id))))
```

Positional is fine when each slot is specced via `:catn`; prefer map-in for
converters you expect to grow new optional inputs.

**Anti-pattern:**

```clojure
;; BAD: *unspecced* positional args. Positional is fine WITH a :catn per-slot
;; spec; the violation is the missing spec, not the positions.
(defn sdk-message->entity [sdk-message session-id]
  ...)
```

---

## Provider Multimethod Pattern

(The `seon.ai.agent` / `seon.ai.claude` multimethods shown here are real
**JVM-track** code — `.clj`, currently paused; `:claude` is the live dispatch
value there. The active CLJS pod uses the simpler adapter-fn shape in
`seon.ai.anthropic` / `seon.ai.openai-compat` rather than this multimethod
registry. The pattern is documented because it's the sanctioned way to build an
extensible provider surface when one is needed.)

For extensible provider systems (like AI providers), use multimethods with
keyword dispatch. This allows adding new providers without modifying existing
code.

### Defining Extension Points

```clojure
(ns seon.ai.agent
  "Provider-agnostic agent extension points.")

;; Dispatch on :provider key
(defmulti normalize-message
  "Convert provider-specific message to a ::ai/message entity."
  :provider)

(defmulti parse-result
  "Extract final stats from a result message."
  :provider)

;; Default for unknown providers
(defmethod normalize-message :default
  [{:keys [provider]}]
  (throw (ex-info (str "Unknown provider: " provider
                       ". Did you require the provider namespace?")
                  {:provider provider})))
```

### Implementing Providers

```clojure
(ns seon.ai.claude
  (:require [seon.ai.agent :as agent]))

(defmethod agent/normalize-message :claude
  [{:keys [message session-id]}]
  (sdk-message->entity {::sdk-message message ::ai/session-id session-id}))

(defmethod agent/parse-result :claude
  [{:keys [message]}]
  {::status (if (= "success" (:subtype message)) :completed :failed)
   ::cost-usd (:total_cost_usd message)})
```

### Why Multimethods Over Protocols

- **Keyword dispatch** — provider is data (`:claude`, `:gemini`), not a type
- **No wrapper objects** — just pass maps with a `:provider` key
- **Namespace loading** — `(require '[seon.ai.claude])` registers implementations
- **Extensibility** — third parties add providers without modifying source

---

## Testing Strategy

Tests serve two purposes:

1. **Example tests** — document intended usage, show how functions compose.
   The bread-and-butter of the active CLJS suite.
2. **Generative tests** — find edge cases, validate schema contracts. A
   **JVM-track** idiom today (`mg/sample` + `m/validate` in `.clj` tests); the
   CLJS pod relies on always-on instrumentation for runtime contract checks.

On the CLJS side, write example tests; reach for generative tests on the JVM
where the schema is the unit under test. (Example tests alone don't prove the
schema's edges; generative tests alone don't show real-world usage.)

### Running tests

- **CLJS suite (active track):** `bin/test-cljs` runs the full `.cljs` suite in
  a fresh `:node-test` JVM (no live-pod contention, ~160s). Use it as the batch
  checkpoint — **once, after a unit of work**, not after each sub-step. Never
  fire overlapping `cljs.test/run-tests` in the live pod (it wedges the shared
  async continuation; restart the pod for a pristine run).
- **Live verification:** to verify a single behavior fast, eval the fn directly
  against the live pod rather than running a whole test namespace. **Live proof,
  not inference** — read a datom back, observe the running system.
- **JVM track (paused):** tests run inside the running JVM via REPL verbs —
  `(user/run-tests 'seon.foo-test)`, `(user/test-affected 'seon.foo)` — never by
  spawning a separate process.

See the `/clojure-testing` skill for fixtures, generators, and debugging.

### Example Tests (Documentation + Integration)

Show the intended workflow — executable documentation. For a behavior that
doesn't touch the DB, a plain `cljs.test/async` test is enough — the
capability verbs return Promises, so await them (`<p!` / `.then`) and read the
envelope:

```clojure
(deftest grep-finds-matches-test
  (testing "grep returns a matches envelope for a real pattern"
    (async done
      (-> (search/grep {::search/pattern "defn ^:async" ::search/max-results 5})
          (.then (fn [{::search/keys [ok? matches]}]
                   (is (true? ok?))
                   (is (vector? matches))
                   (done)))))))
```

**DB-backed CLJS tests** open a fresh **in-memory datahike** conn per test and
`set!` it as the root `db/*conn*` (CLJS has no `binding` across async hops, so
`set!` the root, not `binding`). There is no shared `with-test-db` fixture on
the CLJS side — `with-test-db` / `with-test-node` live in `seon.test-utils`
(`.clj`, JVM track). Each CLJS test ns defines a small local helper; the
canonical shape (see `test/seon/db_test.cljs` `fresh-conn` and
`test/seon/ctx_test.cljs`):

```clojure
(defn- fresh-conn []                                  ; returns a Promise of a conn
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(deftest reads-back-a-row-test
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (set! db/*conn* conn)                ; root set!, not binding
                 (schema/register! ::name :string)
                 (-> (db/transact! {::db/tx-data [{::name "Alpha"}]})
                     (.then (fn [_]
                              (is (= #{["Alpha"]}
                                     (db/query {::db/query '[:find ?n :where [_ ::name ?n]]})))
                              (done)))))))))
```

### Generative Tests (Contract Validation)

**JVM-track idiom.** Generative tests over registered schemas — `mg/sample`
to enumerate valid shapes, `m/validate` to check them — run on the JVM (`.clj`
tests); the CLJS suite doesn't currently use them. Frame contract-checking as a
JVM-side concern and lean on always-on instrumentation for runtime validation
on the pod.

```clojure
;; .clj test (JVM): find edge cases the schema allows but you didn't think of
(deftest grep-request-generative-test
  (testing "every valid request shape round-trips through the schema"
    (doseq [request (mg/sample ::search/grep-request {:size 20})]
      (is (m/validate ::search/grep-request request)))))
```

---

## Anti-Patterns to Avoid

```clojure
;; BAD: :pre/:post - not instrumentable, no generative testing
(defn process [x]
  {:pre [(m/validate ::input x)]}
  ...)

;; BAD: *unspecced* positional args in a public API. Positional is fine WITH a
;; :catn per-slot spec on :malli/schema; bare/unspecced args are the violation.
(defn process [pattern paths max-results]
  ...)

;; BAD: non-namespaced keys in a public API - ambiguous
(defn process [{:keys [pattern paths]}]
  {:ok? true})

;; BAD: a magic :seon/id identity key - identity is {:seon.db/identity true}
;;      on a registered attr, addressed by lookup-ref [::doc-id "d1"]
{:seon/id "abc" ::title "x"}

;; BAD: an agent-facing verb that THROWS instead of returning an envelope
(defn grep [req] (throw (ex-info "no match" {})))   ; must return {::ok? false …}

;; BAD: using :or in destructuring for optional API values
(defn process [{::keys [model] :or {model "default"}}]
  ...)  ; doesn't apply when {::model nil} is passed

;; BAD (JVM-track): only generative tests, no example tests
(deftest foo-test
  (doseq [input (mg/sample ::input)]
    (is (m/validate ::output (foo input)))))
;; Missing: example tests showing intended usage patterns!

;; BAD: touching datahike.api directly outside src/seon/db/
(d/transact conn tx-data)   ; use seon.db/transact!
```

---

## File Organization

Keep it simple — one file per namespace with schemas and functions together.
Promote heavy plumbing to a sibling `<ns>.internal` namespace (see
[The `.internal` namespace pattern](#the-internal-namespace-pattern)); don't
split into `core.cljs` / `schema.cljs` prematurely.

```
src/seon/
├── agent/
│   ├── search.cljs            ; seon.agent.search (public grep + schemas)
│   └── search/internal.cljs   ; seon.agent.search.internal (plumbing)
└── db.cljs                    ; seon.db (read-local + write-forward face)
    db/internal.cljs           ; seon.db.internal (commit machinery)
```

Tests mirror the `src/` structure under `test/`.

---

## Namespace Docstrings

Every namespace should have a comprehensive docstring. It is a living
assessment — purpose, architecture position, the conventions it follows, and a
worked example of the core move. Docstrings RENDER into agent context
(code-as-data), so keep them current-state and concise: no dates, no issue
refs, no changelog ("first X, now Y"). The visible Malli spec documents the
args; don't re-explain them in prose. See
[[docs/seon/concepts/namespace-stewardship.md]] for the full format.

---

## Context, Render, and the System Message

How agent-facing context is assembled — sections as functions of the DB at
render time, the soul/AGENTS sections, and the LLM system message — is being
decoupled right now (a generic file-section loader; the system message becomes
hardcoded system-specific content; soul/AGENTS become ordinary context
sections). For the current model see
[[docs/prds/agent-fsm/context-render.md]] (P3). Don't pin specifics here while
it's in flux.

### Comment levels — prose vs code

The rendered agent context is meant to read as one eval'able Clojure source:
every non-form line is a comment. Which comment marker you use is NOT
decoration — it carries meaning, and the rule is fixed:

- **`;` (single)** — **prose**. Rendered agent-facing prose blocks (the
  `system-text` body, the soul/AGENTS sections, the inventory header, warnings
  prose, the open-todos guidance, any narrative the model reads) AND ordinary
  trailing inline code comments. If a line is words-for-a-reader, it is a single
  `;`.
- **`;;` (double)** — **code block comments**. A standalone comment sitting
  ABOVE a form in real source (the standard Clojure block-comment level). Code
  keeps `;;` for these; do NOT downgrade real-code block comments to `;`.
- **`;;;` (triple)** — reserved for runtime-STRUCTURE demarcation in the
  rendered context, not for body text. Each top-level section is wrapped by a
  start/stop bracket (`seon.ctx/section-bracket-ai`):

  ```clojure
  ;;; ┌─ <section-name> ─
  …section body…
  ;;; └─ end <section-name> ─
  ```

  The transcript's per-event lines (`;;; ◀` inbound / `;;; ▶` outbound) are the
  other `;;;` runtime-structure use. Body text inside a section is never `;;;`.

The practical test: **prose → `;`, block-comment-before-code → `;;`, inline
comment → `;`.** Prose rendered into context uses single `;` precisely so it
reads as a comment to the Clojure reader while staying visually distinct from
real-code `;;` block comments. One shared `seon.ctx/quote-lines` primitive emits
the prose `;` lines; don't hand-roll a `(str ";; " …)` prefixer in a section fn.

---

## SSE Patterns

See the `/datastar-web-ui` skill for SSE patterns (direct response vs background
push, buffer design, refresh triggers, handler hot reload). The pod's UI is
`seon.web.inspector` + `serve` (hiccup, `.cljs`).

---

## Reload Lifecycle Hooks for `defonce` State

**(JVM / clj-reload track.)** Seon is a runtime system where agents live-code
and update namespaces. On the JVM track, `defonce` atoms survive `user/reload`
(clj-reload) but may hold stale references — old closures, dead channels,
orphaned threads. Every `defonce` with mutable runtime state **must** have
lifecycle hooks. (On the CLJS pod, shadow hot-reload re-evaluates top-level
forms; `defonce` guards load-bearing runtime state like `seon.db/*conn*` so a
reload doesn't wipe the live connection.)

clj-reload calls two per-namespace 0-arg hooks if they exist:

| Hook | When | Use For |
|------|------|---------|
| `before-ns-unload` | Before ns is removed | Stop go-loops, drain promises, cancel schedulers, remove watches |
| `after-ns-reload` | After ns is reloaded | Re-populate from Integrant system, restart background processes |

### Pattern

```clojure
(defonce my-state (atom nil))

(defn before-ns-unload []
  (when-let [s @my-state]
    (stop! s)
    (reset! my-state nil)))

(defn after-ns-reload []
  (when (nil? @my-state)
    (reset! my-state (init-from-integrant-system))))
```

### Rules

1. **Any `defonce` holding runtime state must have hooks.** Caches, registries, go-loops, channels, promises, schedulers.
2. **`before-ns-unload` must be idempotent.** It may be called when state is already nil.
3. **`after-ns-reload` should re-derive from Integrant.** The system map is the source of truth.
4. **Don't add hooks for `defonce` holding immutable config.** Pure values, schema definitions.
5. **Exceptions in `before-ns-unload` are swallowed** by clj-reload. Keep it simple.

---

## Numeric Limits and Defaults

Avoid arbitrary "magic numbers". Every limit should have a documented source.

| Category | Example | Guideline |
|----------|---------|-----------|
| **External constraints** | API rate limits, protocol specs | Document the source |
| **Domain bounds** | Percentages 0-100, ratios 0-1 | Mathematical constraints — always add |
| **Internal allocations** | Port ranges, batch sizes | Document why and how to override |
| **Safety caps** | Max recursion, timeout | Document rationale, make configurable |

### Rule: Don't Set Arbitrary Defaults for "No Limit"

```clojure
;; BAD - arbitrary large number to mean "unlimited"
::max-turns (or max-turns 999999)

;; GOOD - don't pass the flag when unlimited
(cond-> base-args
  max-turns (into ["--max-turns" (str max-turns)]))
```

### Rule: Document Limit Sources in Schemas

```clojure
;; GOOD - source documented
(schema/register! ::iv-rank
  [:double {:min 0.0 :max 1.0
            :description "percentile rank (mathematical bound)"}])

;; BAD - arbitrary, undocumented
(schema/register! ::timeout
  [:int {:min 1000 :max 300000}])  ; Why these numbers?
```

### When to Add `:max` Constraints

Add `:max` when an external API/protocol enforces it, a mathematical/domain
constraint exists, or memory/performance safety requires it (document why).
Don't add `:max` "just to be safe", when the underlying system has no limit, or
to prevent hypothetical abuse (use rate limiting instead). For a parameter that
can legitimately be unlimited, set `:min` only and handle omission gracefully.
