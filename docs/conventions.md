---
type: reference
status: active
tags: [reference]
---

# Seon Code Conventions

This is the agent-facing standards doc AND the rubric a code audit uses. If a
convention here disagrees with the running code, the running code wins and this
doc is a bug — flag it.

**Where the code is (owner rulings, 2026-07-27).** Fresh `src/` and `test/` are
the system and the default project; `bin/test` is the gate. **The CLJS build is
OFF — CLJ and the JVM only**, and the `:cljs` alias is dead. The old system is
the quarry under `src-old/`/`test-old/`, reachable only behind explicitly
old-facing aliases; nothing new invests in it. When this doc cites a `src-old/`
file it is citing the quarry as a worked example, not a live owner. Two
mechanisms the old system owned — always-on instrumentation (`seon.instrument`)
and the `seon.db` facade — do **not** exist in the fresh tree yet; the sections
below say so where it matters.

## Why These Conventions Matter

These aren't arbitrary style rules. They're the foundation for **AI agents to
write reliable software**.

When every function has:

- **Namespaced keys** → Agents can query "what accepts `:seon.agent.search/pattern`?" instead of guessing
- **Malli schemas** → Contracts are machine-readable, generatively testable, and the thing a review checks a call against.
- **Fully spec'd args (the hard rule)** → Contracts are complete. Map-in/map-out helps API *accretion* (add an optional field without breaking callers); fully-spec'd positional (`:catn`) is often the better shape for utilities. Pick by fit; the invariant is that every arg is named, spec'd, and validated.
- **Registered schemas** → A queryable database of all data shapes in the system. `schema/register!` derives the datahike attribute schema for free.

The result: agents can discover, compose, and validate code without
hallucinating interfaces.

---

## Runtime tiers

Seon has two runtime process kinds: one cluster JVM per store and disposable
leaf runtimes.

- **Cluster JVM (`.clj` / `.cljc`)** — owns the Datahike writer, run loop,
  guarded evals, program graph, render pipeline, and the cluster's HTTP/SSE web
  UI. Reads are pointers into immutable database values and writes call the
  co-located transaction owner.
- **Leaf runtimes** — run packages and selected platform workers on demand.
  They own no durable state and receive only admitted ordinary request data.

The browser is a client, not a runtime process. It receives static assets and
Datastar element patches and has no database or application runtime.

Store open takes one `flock` assertion before Datahike is opened. A second
cluster JVM for the same store refuses loudly. This is the one fenced exception
where coordination precedes the database. Supervision, bounded evals, and
component restart protect the cluster JVM; a separate render process is not a
containment boundary. Lifecycle is `core.async.flow`'s, not Integrant's — the
fresh tree has no Integrant dependency, and `seon.flow` is the foundation the
boot design grows from.

`core.async.flow` is the one scheduling substrate. Runtime owners are procs
with `step-fn`s; bounded channels and `conns` form the `graph-def`; the report
channel and flow-monitor provide the operational surface. Custom owners use
`flow.spi/ProcLauncher` without forking Flow. Workload channels use
`executor-for :io` or `executor-for :compute`; guarded eval additionally owns
the one `:interrupt-fn`, a platform thread, and its admitted permit.

### Lane Discipline: `.clj` / `.cljs` / `.cljc`

Choose a source extension by the runtime that owns the code:

- **`.clj`** — cluster-JVM owners and JVM platform leaves.
- **`.cljs`** — JavaScript leaf-runtime owners only. None exist in the fresh
  tree, and no new `.cljs` may be added while the CLJS build is off.
- **`.cljc`** — the default for genuinely portable capability cores and pure
  transformations. Reader conditionals occur only at the entry expression that
  bridges platform ceremony.

### The `.internal` namespace pattern

A public namespace stays small and whitelisted so its source renders cleanly
into agent context. **Complex plumbing moves to a sibling `<ns>.internal`
namespace** — un-whitelisted, not rendered into context, free to be as
intricate as it needs to be. The public ns is the discoverable contract; the
`.internal` ns is the machinery.

- `seon.schema` (public `register!`, the registry) ↔ `seon.schema.internal`
  (form gates and decomposition), with `seon.schema.form` and
  `seon.schema.datahike` as the other siblings — the live example in `src/`.
- Quarry example: `seon.agent.search` (public `grep`) ↔
  `seon.agent.search.internal` (hard caps, envelope helpers, the rg `--json`
  parser, the allowlist gate).

Reach for `.internal` whenever a public fn's helpers would otherwise bloat the
context the agent reads. Keep the public surface to the named request/response
schemas plus the function fns.

---

## Malli Schema Patterns

All public APIs use Malli schemas for contract specification. This enables:

- **A machine-checkable contract** — the `:malli/schema` is what a test, a generator, and a reviewer check the function against (see [Instrumentation](#instrumentation)).
- Generative testing via `mg/sample` + `m/validate` (see Testing Strategy)
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
(schema/register! ::doc-id [:string {:min 1 :seon.db/identity true}])
;; entities addressed by lookup-ref: [::doc-id "d1a2b3c4e5f6"]
```

The other facets are properties on the same registered shape:
`{:seon.db/unique true}` (`:db.unique/value`), `{:seon.db/component true}`,
`{:seon.db/index true}`, and `{:seon.db/no-history? true}`. The complete
derivation is `seon.schema.datahike/malli->datahike-attr` — read it rather than
guessing which property maps to which facet.

Centralized id allocation (`seon.db.id/allocate!`, a candidate matching the
identity attribute's registered generator policy, a serialized writer rejecting
collisions) is a **quarry** mechanism in `src-old/seon/db/id.cljc`; the fresh
tree has no id allocator yet. Known-id reconciliation remains an intentional
upsert.

### Shared schema shapes — register once, reference everywhere

If the same shape appears in two or more registered schemas, the shape itself
must be a registered schema the others reference. Inlining compact, readable,
or legacy identity syntax across multiple `register!` calls is a smell.

```clojure
;; ONE canonical shape (lives in its owning ns)
(schema/register! ::compact-value [:and :string [:re #"^[a-z][a-z0-9]{11}$"]])

;; EVERY id attr references it — no inline shape
(schema/register! ::agent-id   [:and {:seon.db/identity true} ::compact-value])
(schema/register! ::session-id [:and {:seon.db/identity true} ::compact-value])
```

If the Malli→datahike bridge doesn't yet handle a reference shape you need, fix
the bridge (`src/seon/schema/datahike.cljc`) — never duct-tape by inlining the
shape at each site. `resolve-malli-form` there is what follows a keyword
reference to its stored shape.

### Provenance attrs are a smell — the tx entity already records it

Before registering `created-by`/`created-at`/`updated-by`/`source-turn`:
Datahike's transaction is a real entity, it is stamped with `:db/txInstant`, and
Seon writes exactly two durable provenance refs on it — `:seon.db/user` and
`:seon.db/process`. Who, through which stable process, and when wrote a datom is
a join through the datom's transaction, not a domain attribute. Turn, eval,
replay, and test details are not provenance and are never copied onto domain
entities. The only legitimate stored form is a pre-event snapshot coordinate.
Full rule + join recipe: the `/datahike` skill, "Transaction metadata".

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

## Errors Are Values (agent-facing function boundaries)

Functions an agent calls directly — the flat `my.*` tool functions — enter the
one guarded `seon.effect/request!` function carrying the request
identity. `my.fs`, `my.shell`, `my.web`, and `my.blob` follow the same rule;
their protected policy and platform leaves remain under `seon.*`. These
functions **return an envelope; they do not throw**. The canonical shape:

```clojure
{::ok? true  …data…}                                   ; success
{::ok? false ::error "<guiding message>"
             ::raw-error "<underlying detail>"}        ; failure
```

This is a hard convention, not a style: an agent's eval must survive a bad call
and read the failure as data, the same way `seon.db/transact!` and
`seon.agent.search/grep` do. Two consequences:

1. **The fn's body owns SEMANTIC failures; the schema owns SHAPE.** A
   semantically-bad call (blank content, denied path, unknown id) returns the
   `::ok? false` envelope from the body's guards. A shape-invalid call is the
   `:malli/schema`'s business — checked by tests and generators today, and by
   the instrumentation wrapper once it re-lands; either way the eval boundary
   surfaces it as a structured `:seon/error` value, never a crash.
2. **Callers MUST read the envelope.** A call can succeed while the write
   didn't happen (`::ok? false`). Translate cryptic underlying errors into a
   guiding `::error` and preserve the original at `::raw-error`.
3. **Never signal an expected error out of band.** Transport failures,
   timeouts, non-2xx responses, and invalid input ride the VALUE channel as the
   surface's envelope (`{:seon.db/ok? false}`, `:seon/error`). Throwing — or,
   in a `.cljs` leaf, rejecting a Promise — is reserved for genuine bugs and
   deliberate fail-loud boot gates.

Not everything is agent-facing. Core code that runs INSIDE a transaction is the
deliberate exception: a `[:db.fn/call ...]` transition REFUSES an ineligible
request by throwing, which aborts the whole transaction atomically. That is the
database enforcing a fence, not an error-handling style — see
`src/seon/cluster/run.cljc`.

When an error is genuinely a caller bug to fix vs. a core bug to report, tag it:
`:seon.error/data` carries `:seon.error/kind` (`:user-input` vs `:core-bug`).

---

## Instrumentation

**Current state: the fresh tree has no runtime instrumentation.** `src/` carries
no `seon.instrument`, so a `:malli/schema` today is checked by tests,
generators, and review — not by a wrapper on every call. Write the schema
anyway and write it correctly: it is the contract of record, the generator
source, and the thing the instrumentation rung will switch on. Never treat "it
isn't enforced yet" as licence for a loose or absent schema.

The rest of this section describes the mechanism the old system ran and the
fresh tree will re-land; it is the design target, not today's behaviour.

Instrumentation was **DB-driven**, not a compile-time macro. The mechanism reads
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

**Opt-out is structural, never a name list**: `seon.instrument/async-unwrappable?`
computes it from real properties (the `^:async` flag + the live fn's arity shape +
the schema form) — an async fn that cannot take the Promise-aware injecting
wrapper registers no wrapper at all. There is no hand-maintained symbol set.

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

**Current state: the fresh tree has no `seon.db`.** Until the store owner lands,
`src/` and `test/` call `datahike.api` directly (see
`test/seon/cluster/run_test.clj`) and derive their attribute declarations with
`seon.schema.datahike/malli->datahike-schema`. That is correct today; it is not
a licence to spread connection handling — keep Datahike calls in the namespace
that owns the store or the fixture, never scattered through domain logic.

The target below is the rule the moment `seon.db` exists.

`seon.db` is the **sole database API**. Nothing outside its own namespace tree
touches `datahike.api`. Everything else uses `db/transact!`, `db/query`,
`db/pull`, `db/entity`, `db/listen!`.

Inside the cluster JVM, `seon.db` is co-located with the transaction owner:

- **Reads are local and synchronous** — `query`/`pull`/`entity` resolve against
  the invocation's immutable database value. Compose them in straight-line
  code.
- **Writes are direct function calls** — `transact!` calls the sole
  transaction owner and returns an **envelope**, never a throw. A leaf or
  remote client crosses the typed protocol only through its admitted effect
  request; the agent path has no database wire.

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
`seon.ai.claude` names below are illustrative — no `seon.ai` namespace exists in
the fresh tree; the quarry's live provider adapters are `seon.ai.anthropic` /
`seon.ai.openai-compat`. The schema-composition *pattern* is what to take.)

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

Test namespaces (`*_test.clj` / `*_test.cljc` — the shapes `bin/test`
discovers) are exempt from most conventions:

- **No `:malli/schema`** on `deftest` or test helper functions
- **No required arg shape** — test helpers may use bare positional args
- **No namespace docstrings** — tests are self-documenting via test names
- **Non-namespaced keys are fine** in test data literals

Conventions that **do** apply in tests:

- **Namespaced keys when calling production functions** — match the real API
- **Example tests at minimum** — generative tests where the schema is the unit;
  see Testing Strategy
- **Meaningful test names** — `grep-finds-matches-test`, not `test1`

---

## Converter Functions (Map-In Pattern)

When converting external data (SDK messages, raw JS payloads) to internal
entities, the map-in pattern fits well when you expect to add optional context
later without changing the signature:

```clojure
(schema/register! ::sdk-message :any) ; third-party SDK boundary
(schema/register! ::sdk-message-request
                  [:map
                   [::sdk-message ::sdk-message]
                   [::ai/message-id :seon.db.id/compact-value]])

(defn sdk-message->entity
  "Convert an SDK message to a seon.ai message entity.

   Request keys:
   ::sdk-message   - Required. Raw SDK message map
   ::ai/message-id - Required. Candidate supplied by the atomic allocator

   Returns an entity map addressed by an identity attr."
  {:malli/schema [:=> [:cat ::sdk-message-request] ::ai/message]}
  [{::keys [sdk-message] ::ai/keys [message-id]}]
  {::ai/message-id message-id
   ::ai/content (extract-content sdk-message)})
```

The transaction boundary invokes `seon.db.id/allocate!` and calls this pure
converter from its transaction builder. A converter never queries for or mints
an identity on its own.

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

(The `seon.ai.agent` / `seon.ai.claude` multimethods shown here are
illustrative; neither namespace exists in the fresh tree, and the quarry's
provider surface is the simpler descriptor-row + adapter-fn shape in
`seon.ai.anthropic` / `seon.ai.openai-compat`, not a multimethod registry. The
pattern is documented because it's the sanctioned way to build an extensible
provider surface when one is needed. Note the standing boundary: a hosted
provider is a descriptor ROW selecting one of two wire cores, never a new
adapter arm.)

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
2. **Generative tests** — find edge cases and validate schema contracts
   (`mg/sample` + `m/validate`, and seeded `test.check` properties over the
   production boundary).

Both run on the JVM in the same `clojure.test` suite. Example tests alone don't
prove the schema's edges; generative tests alone don't show real-world usage.

### Running tests

`bin/test` is the gate. It runs every `*_test.clj` / `*_test.cljc` namespace
under `test/` on the source classpath with in-memory Datahike — no artifact, no
operator, seconds per cycle. Its exit code is the verdict.

```bash
bin/test                        # every suite under test/
bin/test seon.cluster.run-test  # exactly these namespaces
```

Use the selection while iterating and the full run at the natural unit
boundary. `bin/test-cljs` and `bin/test-writer` belong to the quarry and are
**not** the gate — the CLJS build is off.

**Live verification still outranks a green suite.** Falsify a change with an
observed datom, log line, or REPL result, not by inference.

See the `/clojure-testing` skill for fixtures, generators, and debugging.

### Example Tests (Documentation + Integration)

Plain, synchronous `clojure.test` — no async ceremony, no Promises. A
database-backed test opens its **own in-memory Datahike** connection, transacts
the derived attribute declarations, and releases in a `finally`. There is no
ambient connection to `set!`: pass the connection.

```clojure
(ns seon.cluster.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.schema.datahike :as schema.datahike]))

(defn- with-model-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      ;; Under :schema-flexibility :write an attribute must be INSTALLED before
      ;; it is transacted — a registered-but-uninstalled attr throws.
      (d/transact connection
                  (schema.datahike/malli->datahike-schema [::name]))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(deftest reads-back-a-row
  (testing "a transacted value comes back out"
    (with-model-database
      (fn [connection]
        (d/transact connection [{::name "Alpha"}])
        (is (= "Alpha" (d/q '[:find ?n . :where [_ ::name ?n]] @connection)))))))
```

`test/seon/cluster/run_test.clj` is the worked example: fixture, deterministic
clock, and a seeded state-machine property over the real database.

### Generative Tests (Contract Validation)

A registered schema is a generator. Enumerate valid shapes with `mg/sample` and
check them with `m/validate`; for anything involving two calls, a commit, or
recovery, write an explicit seeded `test.check` property that invokes the
production boundary and observes database facts independently of the returned
value.

```clojure
(deftest grep-request-generative-test
  (testing "every valid request shape round-trips through the schema"
    (doseq [request (mg/sample ::search/grep-request {:seed 20260727 :size 20})]
      (is (m/validate ::search/grep-request request)))))
```

Pin the seed so a failure is reproducible, and print the schema key, seed, size,
generated value, and shrunk check on failure.

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

;; BAD: an agent-facing function that THROWS instead of returning an envelope
(defn grep [req] (throw (ex-info "no match" {})))   ; must return {::ok? false …}

;; BAD: using :or in destructuring for optional API values
(defn process [{::keys [model] :or {model "default"}}]
  ...)  ; doesn't apply when {::model nil} is passed

;; BAD: only generative tests, no example tests
(deftest foo-test
  (doseq [input (mg/sample ::input)]
    (is (m/validate ::output (foo input)))))
;; Missing: example tests showing intended usage patterns!

;; BAD: scattering datahike.api through domain logic. Today the store owner
;; and the test fixture call it directly; once seon.db exists it is the sole
;; database API and nothing outside it touches datahike.api.
(defn summarize [conn] (d/transact conn tx-data) ...)
```

---

## File Organization

Keep it simple — one file per namespace with schemas and functions together.
Promote heavy plumbing to a sibling `<ns>.internal` namespace (see
[The `.internal` namespace pattern](#the-internal-namespace-pattern)); don't
split into `core.clj` / `schema.clj` prematurely.

```
src/seon/
├── schema.cljc                ; seon.schema (public register! + registry)
└── schema/
    ├── internal.cljc          ; seon.schema.internal (form gates)
    ├── form.cljc              ; seon.schema.form (shared form inspection)
    └── datahike.cljc          ; seon.schema.datahike (the Datahike bridge)
```

`test/` mirrors `src/`. The quarry keeps its own mirrored pair — `test-old/`
mirrors `src-old/` — until a namespace is explicitly adopted into `src/` by
`git mv`.

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

## Function Docstrings

Every public fn's docstring **first line is a complete, standalone sentence** —
it is the summary shown wherever the fn renders compactly (most importantly the
compact namespace card, which shows ONLY line 1) and the first thing every reader
sees. The rules:

- **One complete sentence, ≤ 72 chars (78 hard cap), ending in terminal
  punctuation** (`.` / `?` / `!`) — never a mid-sentence hard-wrap.
- **States the action + its data effect, not the mechanism.** Mechanism, gotchas,
  and worked notes go in the BODY (after a blank line) — the body renders only in
  the full-source view.
- **Imperative for side-effecting verbs** (`Store…`, `Mint…`, `Retract…`);
  **noun-phrase for pure queries** (`Agent ids whose…`, `Snapshot of…`) — the mood
  signals purity at a glance.
- **Backtick-quote identifiers** (`` `::owner` ``, `` `:idle` ``).

```clojure
;; BAD — line 1 hard-wraps mid-sentence (renders broken in a card)
(defn armable-agent-ids
  "Agent ids whose DERIVED state is `:idle` — not `:terminated` AND with no
   OPEN run. ..."
  ...)

;; GOOD — line 1 is a complete ≤72-char sentence; detail moves to the body
(defn armable-agent-ids
  "Agent ids whose derived state is `:idle` — the ones a trigger can WAKE.

   <mechanism / caveats continue here, rendered in the full view only>"
  ...)
```

Promoting a clean line 1 often leaves the old continuation dangling as a
lowercase fragment — re-open the body sentence so the full docstring still reads
(it is a small body edit, not a pure prepend). Enforced by `seon.dev.docstring`
(warn-only, via the dev hook): it flags a public fn whose line 1 is missing,
> 78 chars, or lacks terminal punctuation. Namespace docstrings follow the same
current-state discipline — see [Namespace Docstrings](#namespace-docstrings).

---

## Context, Render, and the System Message

How agent-facing context is assembled — sections as functions of the database
value at render time, and the LLM system message — is unbuilt in the fresh
tree. The intended target is
[[docs/seon/architecture/context.md]]; the ordering lives in
[[docs/prds/sci-execution-runtime/plan/README.md]]. Don't pin specifics here.
The comment grammar below is settled and applies now.

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

**Unbuilt.** web-render is cut from the dev process set (owner ruling
2026-07-27) and the UI arrives later as an in-process pipeline. This is the
target shape, recorded so nothing invents a different one.

The web UI uses the cluster JVM's one in-process render Flow: transaction →
`listen!` interest wake through `(sliding-buffer 1)` → guarded render proc →
equality suppression → `mult` → per-tab `(sliding-buffer 1)` tap → per-tab
`:io` writer proc → one bounded SSE connection. Initial paint is the only
whole-page render; later updates are stable-ID Datastar element patches.
Rendered snapshots are never stored.

---

## Reload Lifecycle Hooks for `defonce` State

**Not wired in the fresh tree** — there is no `clj-reload` dependency and no
`user/reload` today. Keep the rule anyway, because it is what makes a namespace
reloadable when the dev loop lands: Seon is a runtime system where agents
live-code and update namespaces, and a `defonce` atom survives a reload holding
stale references — old closures, dead channels, orphaned threads. Every
`defonce` with mutable runtime state gets lifecycle hooks. Better still, prefer
not to hold runtime state in a `defonce` at all: the database owns durable
state, and a flow graph owns its own procs and channels.

clj-reload calls two per-namespace 0-arg hooks if they exist:

| Hook | When | Use For |
|------|------|---------|
| `before-ns-unload` | Before ns is removed | Stop go-loops, drain promises, stop procs, remove watches |
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

1. **Any `defonce` holding runtime state must have hooks.** Caches, registries, go-loops, channels, promises, and Flow procs.
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
