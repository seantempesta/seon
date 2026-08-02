---
type: reference
status: active
tags: [reference]
---

# Seon Code Conventions

This is the agent-facing standards doc AND the rubric a code audit uses. If a
convention here disagrees with the running code, the running code wins and this
doc is a bug — flag it.

Fresh `src/` and `test/` are the system; `bin/test` is the gate. Seon is CLJ on
the JVM. The deleted CLJS/pod system under `src-old/` and `test-old/` is quarry,
not a source of current APIs or ownership.

## Why These Conventions Matter

These aren't arbitrary style rules. They're the foundation for **AI agents to
write reliable software**.

When every function has:

- **Namespaced keys** → Agents can query "what accepts `:seon.search/pattern`?" instead of guessing
- **Malli schemas** → Contracts are machine-readable, generatively testable, and the thing a review checks a call against.
- **Fully spec'd args (the hard rule)** → Contracts are complete. Map-in/map-out helps API *accretion* (add an optional field without breaking callers); fully-spec'd positional (`:catn`) is often the better shape for utilities. Pick by fit; the invariant is that every arg is named, spec'd, and validated.
- **Registered schemas** → One admitted population of queryable shapes.
  First-party declarations live in `resources/seon/schema.edn`; runtime
  registrations pass through the same admission rules.

The result: agents can discover, compose, and validate code without
hallucinating interfaces.

---

## Runtime tiers

One JVM process may host several sovereign clusters. One process-root Datahike
store is fenced by a lifetime `flock`; each cluster is a named branch with its
own connection, program graph, agent graphs, render graph, and web service.
Clusters share only process-root resources such as the store holder and the
`:io`/`:compute` executors. `src/seon/cluster.clj` and
`src/seon/cluster/store.clj` own that boundary.

The browser and model provider are external clients/services, not Seon runtime
processes. Browser updates cross one SSE connection as Datastar morphs; model
calls cross the HTTP boundary in `seon.ai`. There is no pod, replica JVM, or
separate render process. Lifecycle is `core.async.flow`'s, not Integrant's.

`core.async.flow` is the one scheduling substrate. Runtime owners are procs
with `step-fn`s; bounded channels and `conns` form the `graph-def`; the report
channel and flow-monitor provide the operational surface. Custom owners use
`flow.spi/ProcLauncher` without forking Flow. Workload channels use
`executor-for :io` or `executor-for :compute`. Guarded eval runs synchronously
inside the caller's bounded `:compute` submission and arms the one
thread-scoped `:interrupt-fn`; it owns no pool or semaphore.

### Lane Discipline: `.clj` / `.cljc`

Choose a source extension by the runtime that owns the code:

- **`.clj`** — cluster-JVM owners and JVM platform leaves.
- **`.cljc`** — the default for genuinely portable capability cores and pure
  transformations. Reader conditionals occur only at the entry expression that
  bridges platform ceremony.

### The `.internal` namespace pattern

A public namespace stays small so its source and contracts are easy to read.
Complex plumbing may move to a sibling `<ns>.internal` namespace. This is code
organization, never a callability rule: every function in a cluster's program
graph is callable, and rendering a function into context does not grant or
deny execution.

- `seon.schema` owns registry semantics; `seon.schema.internal`,
  `seon.schema.form`, and `seon.schema.datahike` own decomposition and the
  Datahike projection.

Reach for `.internal` when helpers obscure the public contract. Do not create
one merely to hide callable functions or to avoid fixing an existing owner.

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
;; INSIDE seon.search namespace:
::pattern             ;; => :seon.search/pattern
::match               ;; => :seon.search/match

;; OUTSIDE (user code with alias):
(ns my-app.core
  (:require [seon.search :as search]))

::pattern             ;; => :my-app.core/pattern  (WRONG namespace!)
::search/pattern      ;; => :seon.search/pattern (correct)
```

**Inside your namespace, `::keyword` is the preferred form.** It's shorter,
refactor-safe, and guarantees the keyword matches the namespace that owns it.
Keyword namespaces ARE real code namespaces — `::subject` in `seon.email.message`
is `:seon.email.message/subject`, and the schema for that data lives in that
namespace. Only use explicit `:seon.foo/bar` form in cross-namespace references
or docstring examples showing external callers.

### Schema Registration

Author shipped first-party schemas in the one EDN map at
`resources/seon/schema.edn`. Section comments are editorial:
`seon.schema.edn` loads the complete population, refuses duplicate keys or unresolved
references, and `seon.schema.datahike` derives Datahike attribute declarations
from the admitted Malli forms.

```clojure
;; resources/seon/schema.edn — search section
{:seon.search/pattern
 [:string {:min 1 :description "ripgrep regex pattern"}]

 :seon.search/max-results
 [:int {:min 1 :description "cap on matches returned"}]

 :seon.search/request
 [:map {:closed true}
  [:seon.search/pattern :seon.search/pattern]
  [:seon.search/max-results {:optional true} :seon.search/max-results]]}
```

`schema/register!` is the admitted runtime surface for agent-authored schemas,
not a second shipped-schema authoring path. Runtime registrations stage in an
isolated registry delta and publish only from the terminal transaction report's
`db-after` in `seon.sci.eval`. Ordinary first-party namespaces call
`schema.edn/load!` to activate the packaged population; they do not duplicate
their declarations in Clojure forms.

Identity attributes are declared via `{:seon.db/identity true}` on the
declared shape — there is **no** magic `:seon/id` key:

```clojure
{:seon.document/id [:string {:min 1 :seon.db/identity true}]}
;; entities addressed by lookup ref: [:seon.document/id "d1a2b3c4e5f6"]
```

The other facets are properties on the same registered shape:
`{:seon.db/unique true}` (`:db.unique/value`), `{:seon.db/component true}`,
`{:seon.db/index true}`, and `{:seon.db/no-history? true}`. The complete
derivation is `seon.schema.datahike/malli->datahike-attr` — read it rather than
guessing which property maps to which facet.

### Shared schema shapes — register once, reference everywhere

If the same shape appears in two or more registered schemas, the shape itself
must be declared once and referenced by key. Inlining the same constraint in
several EDN entries is drift.

```clojure
{:seon.cluster.run/process [:string {:min 1}]
 :seon.cluster.run/live-processes [:set :seon.cluster.run/process]}
```

If the Malli→datahike bridge doesn't yet handle a reference shape you need, fix
the bridge (`src/seon/schema/datahike.clj`) — never duct-tape by inlining the
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
{:seon.search/request
 [:map {:closed true}
  [:seon.search/pattern :seon.search/pattern]
  [:seon.search/paths {:optional true} :seon.search/paths]
  [:seon.search/max-results {:optional true} :seon.search/max-results]]

 :seon.search/success
 [:map {:closed true}
  [:seon.search/matches :seon.search/matches]]

 :seon.search/response
 [:or :seon.search/success :seon.error/value]}
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
(defn search
  "Search the requested paths and return matches or an error value."
  {:malli/schema [:=> [:cat :seon.search/request] :seon.search/response]}
  [{::keys [pattern paths max-results]}]
  ;; Plain synchronous CLJ. Expected failures return `:seon.error/value`.
  ...)
```

#### Shape (b): named positional via `:catn`

Each positional slot gets its own fully-namespaced spec. Use this for natural
data-processing fns or to mirror a well-known API (Datahike does this — see
`seon.db/q` and `seon.db/pull`). The slot names in the `:catn` document the
positions; the return is still fully specced.

```clojure
;; `:seon.decode/attr` and `:seon.decode/value` are declared in schema EDN.

(defn decode-edn-value
  "Decode a pulled value back to its real shape."
  {:malli/schema
   [:=> [:catn [:seon.decode/attr :seon.decode/attr]
                 [:seon.decode/value :seon.decode/value]]
    :seon.decode/value]}
  [attr v]
  ...)
```

Multi-arity is allowed when **every** arity is fully specced — use a
`:function` schema wrapping one `:=>` per arity (see `seon.db/pull`). Use map-in/map-out
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

Expected failures at an agent/runtime boundary are flat `:seon.error` values;
they do not throw into the run loop. The shared shape is declared once in
the error section of `resources/seon/schema.edn`:

```clojure
{:seon.error/kind :seon.example/invalid-request
 :seon.error/message "The request cannot be applied."
 :seon.error/data {:seon.example/id "example-1"}}
```

This is a hard convention: an agent's eval survives a bad call and can inspect,
repair, and retry. Three consequences:

1. **The fn's body owns SEMANTIC failures; the schema owns SHAPE.** A
   semantically invalid call returns a specific flat error value. A
   shape-invalid call is the `:malli/schema`'s business; instrumentation checks
   input and output contracts in `:panic` mode, and SCI evaluation turns the
   violation into an agent-readable error value.
2. **Callers MUST inspect the returned rail.** Do not treat any truthy map as
   success. A domain response either has its success shape or is a
   `:seon.error/value`.
3. **Never signal an expected error out of band.** Transport failures,
   timeouts, non-2xx responses, and invalid input ride the value channel.
   Throwing is reserved for programmer faults, deliberate fail-loud boot
   gates, and transaction-function refusal.

Not everything is agent-facing. Core code that runs INSIDE a transaction is the
deliberate exception: a `[:db.fn/call ...]` transition REFUSES an ineligible
request by throwing, which aborts the whole transaction atomically. That is the
database enforcing a fence, not an error-handling style — see
`src/seon/cluster/run.clj`.

Use a qualified, source-owned `:seon.error/kind`; do not maintain a closed
central enum of error kinds. The error section of `resources/seon/schema.edn`
deliberately
defines it as a qualified keyword.

---

## Instrumentation

`seon.instrument` is the one Malli instrumentation owner. Its selection is
computed: every loaded public Var carrying `:malli/schema`, with no namespace
allowlist. `apply!` collects those schemas and applies input/output wrappers in
development (`:seon.config/on-core-error :panic`); production `:record` removes
the wrappers because Malli's non-throwing reporter cannot prevent the invalid
call from running.

Interpreted functions use the same contract family. `seon.sci.eval` installs a
committed function through `seon.instrument/wrap-interpreted`, using the row's
contract and the same `:panic`/`:record` dial. Contract violations are flat
`:seon.error` values at the evaluation boundary.

Re-evaluating a host `defn` replaces its Var root and strips its wrapper.
Re-run `seon.instrument/apply!` after hot reload; the operation is idempotent.
Do not call Malli's collection/instrumentation functions directly, maintain a
symbol roster, or disable a noisy contract. Fix the caller or the schema.

### Schema Introspection

```clojure
(require '[seon.schema :as schema])

;; All schemas for a namespace
(schema/schemas-in-namespace "seon.search")

;; Is a schema registered?
(schema/registered? ::pattern)
```

---

## Database Access

`seon.db` is the one database namespace for all things Datahike (owner
ruling 2026-08-02 #41). Every core data function offers two interfaces —
Datahike's own positional arity and Datahike's own argument-map arity
(`{:query :args}` for `q`, `{:selector :eid}` for `pull`, `{:tx-data
:tx-meta}` for `transact!`, `{:index :components}` for `datoms`; the
dependency's keys, never an invented envelope) — and both may elide the
db/conn argument to assume the current database of the calling agent's
cluster. Dependency failures become flat `:seon.error` values; returns
are admit-clean for SCI contexts; reads never cross a remote protocol.
It is not called a "facade" — it is the db namespace, intercepting
Datahike's calls only for error-value and ambient-custody semantics.

All first-party code calls `seon.db`; only `seon.db` itself and the
store/registry custody owners (flock, open/release, branch management)
require `datahike.api`. Migration in flight: `transact!` now lives at
`seon.db/transact!`, and the core namespace surface has landed. The
remaining migration is the counted first-party direct-call sweep in
`docs/seon/issues/seon-db-is-not-the-one-database-namespace.md`; new code
never adds a direct `datahike.api` call site.

```clojure
(require '[seon.db :as db])

;; Uses the current cluster binding.
(db/q '[:find ?id
        :where [_ :seon.cluster.agent/id ?id]])

;; Uses one explicit immutable database value.
(db/pull database-value
         [:seon.cluster.agent/id :seon.cluster.agent/namespace]
         [:seon.cluster.agent/id "agent-1"])
```

Snapshot once and pass that database value through related reads. Do not
re-deref a connection at every leaf or invent a generic database coordinate
map. For query, pull, lookup-ref, upsert, retraction, and transaction-function
mechanics, use the `datahike` skill and the selected source under
`reference-code/datahike/`.

---

## Schema Composition Across Namespaces

### Entity-Centric Thinking (EAV Pattern)

An entity is its attributes and connections, not a row carrying a stored kind.
One entity may carry attributes owned by several namespaces; generic queries
select by attribute presence and follow refs.

```clojure
{:seon.cluster.agent/id "agent-1"
 :seon.cluster.agent/namespace 'my.agents.agent-1
 :seon.config.ai/thinking :high}
```

The AI override is an ordinary attribute on the same agent entity. Absence
means inherit the cluster setting; no provider-specific entity, `:type` stamp,
or parallel override registry is needed. Cross-namespace composition uses the
same move: declare each leaf once and reference it from composite EDN forms.

### Opaque runtime values still have contracts

Every public function carries a correct `:malli/schema`, including functions
that accept connections, channels, sinks, or other process-local objects.
Declare a named predicate schema with an honest generator, as
the store and web sections of `resources/seon/schema.edn` do. Do not
omit the contract merely because the value is opaque.

### `:any` at third-party interface boundaries

The no-`:any` rule is the default **for Seon-authored data**. At a
**third-party interface boundary** — where the value is whatever a library or
HTTP peer returns and Seon does not control its shape — `:any` is acceptable
because there is no honest tighter type.

```clojure
;; A decoded provider response is a foreign document.
{:seon.ai/decoded-body :any}
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
boundary. Do not restore a second runner for deleted CLJS or writer suites.

**Live verification still outranks a green suite.** Falsify a change with an
observed datom, log line, or REPL result, not by inference.

See the `/clojure-testing` skill for fixtures, generators, and debugging.

### Example Tests (Documentation + Integration)

Plain, synchronous `clojure.test`. A database-backed test uses
`seon.test-support/with-database`, which opens its own in-memory Datahike
connection, installs the production schema/program population, and releases
and deletes it in a `finally`.

```clojure
(ns seon.cluster.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.test-support :as test-support]))

(deftest reads-back-a-row
  (testing "a transacted value comes back out"
    (test-support/with-database
      (fn [connection]
        (d/transact connection [{:seon.cluster.run/id "r1"}])
        (is (= "r1"
               (d/q '[:find ?id .
                      :where [_ :seon.cluster.run/id ?id]]
                    @connection)))))))
```

Use `:seon.test-support/extra-schema` only when schema installation itself is
the subject. `test/seon/cluster/run_test.clj` is the worked example for the
fixture, a deterministic clock, and a seeded state-machine property.

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

;; BAD: scattering datahike.api through domain logic. Reads and writes use
;; seon.db; store and registry retain custody operations only.
(defn summarize [conn] (d/transact conn tx-data) ...)
```

---

## File Organization

Keep it simple — one file per namespace for functions and one owning EDN entry
for shipped schemas.
Promote heavy plumbing to a sibling `<ns>.internal` namespace (see
[The `.internal` namespace pattern](#the-internal-namespace-pattern)); don't
split into `core.clj` / `schema.clj` prematurely.

```
resources/seon/schema.edn     ; admitted first-party declarations

src/seon/
├── schema.clj                 ; registry and runtime registration
└── schema/
    ├── internal.cljc          ; seon.schema.internal (form gates)
    ├── edn.clj                ; packaged population admission
    ├── form.cljc              ; seon.schema.form (shared form inspection)
    └── datahike.cljc          ; seon.schema.datahike (the Datahike bridge)
```

`test/` mirrors `src/`. Deleted implementations remain only as quarry under
`src-old/` and `test-old/`; never port their namespace layout into the fresh
tree.

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

An agent's context is a REPL session rendered from one immutable database
value. `seon.cluster.prompt/prompt` performs one `seon.render/walk` from the
agent's namespace and captures the exact AI text before the provider call. The
namespace debug route renders the same walk through the HTML projection; it is
not a second hand-built context surface.

The transcript is ordered forms, outputs, and receipts. Forms remain reader
input; outputs use the stock-REPL-shaped `seon.print` text sink and are not
rewritten into comments merely to make the whole transcript readable as one
file. Admission stores the data projection, while render time chooses text,
hiccup, or the tee sink so both faces come from one traversal.

### Comment levels — prose vs code

For authored Clojure source, comment levels carry their ordinary meaning:

- **`;` (single)** — prose and trailing inline explanation.
- **`;;` (double)** — **code block comments**. A standalone comment sitting
  above a form.
- **`;;;` (triple)** — runtime-structure demarcation inside implementation
  source, not an alternate prose level and not a transcript bracket protocol.

---

## SSE Patterns

`seon.render.web` owns the in-process web Flow. Database transactions and the
stream channel wake `render-step`; it derives a complete map of current pages,
suppresses an unchanged map, and publishes the new map through one mult. Each
feed taps that mult with `(sliding-buffer 1)`, computes changed stable-ID
fragments against its last page, and writes Datastar morph events from one
virtual thread. A slow tab therefore receives the newest complete page state;
rendered snapshots are transport values, never durable facts.

---

## Hot Reload and Source Publication

Re-evaluating a `defn` changes that Var in the running JVM immediately; Flow
proc definitions reference Vars, so behavior updates without rebuilding the
graph. A topology change uses Flow's lifecycle directly: stop, create the new
graph, start. Do not add Integrant or namespace-reload hooks.

File edits do not mutate database program facts. The edit hook publishes safe
same-identity changes to `:current-src`; structural changes fall back to a
complete scratch publication. Existing clusters remain sovereign until an
operator explicitly reforks one. After re-evaluating host functions in
development, re-run `seon.instrument/apply!` because replacing a Var root
removes its Malli wrapper.

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
{:seon.metric/iv-rank
 [:double {:min 0.0 :max 1.0
           :description "percentile rank (mathematical bound)"}]}

;; BAD - arbitrary, undocumented
{:seon.remote/timeout-ms
 [:int {:min 1000 :max 300000}]} ; Why these numbers?
```

### When to Add `:max` Constraints

Add `:max` when an external API/protocol enforces it, a mathematical/domain
constraint exists, or memory/performance safety requires it (document why).
Don't add `:max` "just to be safe", when the underlying system has no limit, or
to prevent hypothetical abuse (use rate limiting instead). For a parameter that
can legitimately be unlimited, set `:min` only and handle omission gracefully.
