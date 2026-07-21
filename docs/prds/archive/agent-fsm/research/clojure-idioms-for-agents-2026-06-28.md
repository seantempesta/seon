---
type: research
status: active
tags: [research, agent]
---

# Clojure idioms for agents — rewiring from imperative/OO to data-oriented Seon

The recurring failure: an agent (or orchestrator) coming from Java/Python/JS
writes Clojure in its native accent — imperative accumulator loops, class/type
hierarchies, exceptions as control flow, bare map keys, ad-hoc validation,
stored-then-cleared state — and *guesses* library semantics from training
memory instead of reading the vendored source. The result is confident, wrong
code that the always-on instrumentation then rejects at runtime, or worse,
quietly drifts from the system's grain.

This doc distills the ~13 highest-frequency "gut-instinct anti-patterns" into
WRONG→RIGHT pairs, each grounded in a real file:line I opened (seon canon or
`reference-code/`). It is the raw material behind the `data-oriented-clojure`
skill — the skill is the lean trigger; this is the evidence.

## TL;DR

- **Stop guessing library semantics.** The single most expensive habit. Every
  load-bearing claim below has a `reference-code/<lib>/...:line` behind it
  because the source is checked out and grep-able — read it, don't reverse-
  engineer it from memory (`CLAUDE.md` "Slow Is Fast" §3).
- **An entity is its attributes + connections — there are NO kinds.** Datahike
  has no type/class/kind. Find by attribute-presence, identify by
  `:db.unique/identity`, relate/remove by refs, scope by `:seon.db/origin`
  (`datahike-primer.md` §0). The `:kind`/`:type` discriminator field is the OO
  reflex this system most wants you to unlearn.
- **Data is the interface.** Maps in, maps out; every key fully namespaced;
  every public fn carries a `:malli/schema` and is instrumented at runtime — a
  wrong schema throws (`reference-code/malli/src/malli/core.cljc:2203-2220`).
- **Errors are values at agent verb boundaries**, not thrown exceptions
  (`docs/conventions.md:303-329`).
- **Derive, don't store.** A surface is a function of the DB at render time;
  when the underlying problem is fixed the query returns empty and the surface
  vanishes. No stored counters, no "mark-as-seen" (`CLAUDE.md` "Reactive
  context").
- **Immutability is the substrate.** A db is a *value*, not a place; thread one
  snapshot through a unit of work instead of re-reading `@*conn*`
  (`datahike-primer.md` §1).

---

## The anti-patterns

### 1. Mutable accumulator loop → `reduce` / `map` / `into`

**Wrong instinct** (build a result by mutating in a loop):

```clojure
(let [acc (atom [])]
  (doseq [x xs]
    (when (pos? (:score x))
      (swap! acc conj (:label x))))
  @acc)
```

**Idiomatic:**

```clojure
(into [] (comp (filter (comp pos? :score)) (map :label)) xs)
;; or, when there's genuine carry-along + early exit:
(reduce (fn [acc x] (if (done? acc) (reduced acc) (step acc x))) init xs)
```

**Why here:** an in-body atom for pure traversal state is a place-oriented tell;
the codebase's own audit flagged exactly this (`collect-ns-order`'s `seen`/
`order` atoms, `apply-agent-budget`'s `loop`/`recur` early-exit) as
non-idiomatic and recommended `reduce`+`reduced`
(`docs/prds/agent-fsm/research/clojure-idiom-audit-2026-06-25.md:98-124`). Pure
recursion or `reduce` keeps state in the call, not in a mutable cell — no
spooky action, output depends only on input.

### 2. OO class / `:kind` / `:type` discriminator → entity = attributes + connections

**Wrong instinct** (model a "type" and branch on it):

```clojure
{:type :route :pattern "/world" :method :get}     ; a "Route object"
(case (:type ent) :route ... :agent ...)          ; dispatch on kind
(defn find-routes [db] (filter #(= :route (:type %)) (all-entities db)))
```

**Idiomatic** (the thing IS a route because it carries route attributes):

```clojure
;; it's a route because it asserts :seon.route/pattern + :seon.route/method
{:seon.route/pattern "/world" :seon.route/method :get}
;; FIND by attribute presence (scan the attr's index):
(db/query {::db/query '[:find ?e :where [?e :seon.route/pattern]]})
;; IDENTIFY by a :db.unique/identity attr; RELATE/REMOVE by refs.
```

**Why here:** datahike entities have no type, class, or kind — schema attaches
to *attributes* (`:db/valueType`/`:db/cardinality`/`:db/unique`), never to
entities, and an entity is open (it may carry attributes from several domains at
once). "What you're looking at" is decided by which attributes are present and
how refs connect it (`datahike-primer.md:24-53`). The owner has corrected this
one repeatedly; `:seon.entity/id-attr` is *attribute-presence enumeration*, not
a kind stamp — there is deliberately no `:seon.entity/kind`
(`datahike-primer.md:39-42`). A `:kind`/`:type` field is the imperative reflex
to recreate a class system; reframe in attributes + connections + provenance.

### 3. Table/row thinking → EAV bag of namespaced attrs

**Wrong instinct** (separate "tables" per record type, migrate to add a column):

```clojure
;; "messages table" vs "claude_messages table" — and a migration to add a field
```

**Idiomatic** (one entity carries attrs from multiple namespaces; no migration):

```clojure
{:seon.ai.claude/message-id "msg-abc12345678"
 :seon.ai/role "assistant"          ; generic base attrs
 :seon.ai/content "Hello!"
 :seon.ai.claude/cache-tokens 150}  ; provider-specific attrs, only when present
```

**Why here:** in EAV an entity is a bag of namespaced attributes, not a row in a
table. Adding a provider-specific field needs no schema migration; queries
filter by any attribute from any namespace; generic code works on `:seon.ai/*`
while provider code adds its own (`docs/conventions.md:445-467`). Thinking in
tables makes you reach for migrations and joins the model doesn't need.

### 4. Bare/unnamespaced map keys → fully-namespaced keyword keys

**Wrong instinct:**

```clojure
(defn process [{:keys [pattern paths]}] {:ok? true})
```

**Idiomatic** (every key namespaced; `::` auto-resolves to the owning ns):

```clojure
(defn process [{::keys [pattern paths]}] {::ok? true})
;; ::pattern in seon.agent.search IS :seon.agent.search/pattern
```

**Why here:** namespaced keys are the load-bearing rule the rest of the system
depends on — a single Datalog query can join function specs to the data those
functions operate on, and "what accepts `:seon.agent.search/pattern`?" becomes a
DB query instead of a guess (`docs/conventions.md:22, 401-406`). Bare keys are
ambiguous and uncollatable; the keyword namespace IS a real code namespace that
owns the schema for that data (`docs/conventions.md:98-120`). `seon.db/transact!`
won't even accept a bare attribute — it refuses unregistered attrs at the
boundary (`src/seon/db.cljs:841-857`).

### 5. Exceptions as control flow → errors-as-values envelopes

**Wrong instinct** (throw, expect the caller to try/catch):

```clojure
(defn grep [req] (throw (ex-info "no match" {})))
```

**Idiomatic** (agent-facing verbs return an envelope, never throw):

```clojure
{::ok? true  …data…}                                   ; success
{::ok? false ::error "<guiding message>"
             ::raw-error "<underlying detail>"}        ; failure
```

**Why here:** an agent's eval must survive a bad call and read the failure as
data — the same way `seon.db/transact!` and `seon.agent.search/grep` do
(`docs/conventions.md:303-329`, `src/seon/agent/search.cljs:10`). Because these
verbs must never throw, they are explicitly excluded from instrumentation's
throwing validator via `seon.instrument/skip-syms`
(`src/seon/instrument.cljc:42-88`) — a throw, even one the eval boundary catches
as data, aborts the `::ok?` contract. Throwing-as-flow breaks the loop;
returning data lets the agent inspect, recover, and retry.

### 6. Ad-hoc validation / `:pre`/`:post` → `:malli/schema` + always-on instrumentation

**Wrong instinct:**

```clojure
(defn process [x]
  {:pre [(m/validate ::input x)]}   ; not instrumentable, no gen testing
  ...)
```

**Idiomatic** (declare the contract as metadata; the system instruments it):

```clojure
(defn do-thing
  {:malli/schema [:=> [:cat ::do-thing-request] ::do-thing-response]}
  [{::keys [id option]}] ...)
```

**Why here:** every public fn with `:malli/schema` is wrapped at runtime; the
wrapper validates args and return on every call and *throws* (`report` defaults
to `-fail!`) on `::invalid-input`/`::invalid-output`
(`reference-code/malli/src/malli/core.cljc:2203-2220`, `-instrument` at
`:3110`). A wrong schema is therefore a runtime bug, not a doc nit — read the
error and fix the root cause (`docs/conventions.md:333-369`). `:pre`/`:post`
gets none of this: not instrumented, not discoverable, not generatively
testable (`docs/conventions.md:707-710`). The schema is also the machine-
readable contract agents discover ("what returns `::grep-response`?").

### 7. Stored/cached derived state + "mark-as-seen" → derive-at-render (reactive context)

**Wrong instinct** (store a counter / a last-error / an ack flag):

```clojure
(swap! warnings conj {:msg "stale" :seen? false})   ; a notification queue
```

**Idiomatic** (a section is a pure fn of the DB; it renders only when the query
returns rows; when the problem is fixed the query is empty and the surface
vanishes):

```clojure
(defn stale-section [db]
  (when-let [rows (seq (db/query {::db/db db ::db/query '[…find stale…]}))]
    (render rows)))
```

**Why here:** the system is self-healing because nothing is stored that needs to
be cleared — no acknowledgement, no stored "last error", no notification system
(`CLAUDE.md` "Reactive context — derived by default"). Caching is the perf
escape hatch (memoize an expensive derivation), not a license to bifurcate into
"stored fast path + derived slow path"; `:memory` reads are sub-ms — measure
before caching (same section; `datahike-primer.md:160-169`). Cross-agent
coordination falls out for free: a section that doesn't filter by agent-id sees
the whole cluster.

### 8. A db is a "place" you re-read → a db is a VALUE you thread

**Wrong instinct** (re-deref the connection at each leaf, then worry about races):

```clojure
(defn step [] (let [a (q1 @*conn*) b (q2 @*conn*)] ...)) ; two different values!
```

**Idiomatic** (snapshot once, thread the value through the unit of work):

```clojure
(let [db @*conn*]                       ; one immutable value
  (let [a (db/query {::db/db db …}) b (db/query {::db/db db …})] ...))
```

**Why here:** `(d/q query db)`, `(d/pull db sel eid)`, `(d/entity db ref)` are
referentially transparent over a db value — it cannot change under you; the
"race" you think you have is usually the artifact of re-reading `@*conn*` three
times instead of threading one value (`datahike-primer.md:55-74`). On the pod
each `@*conn*` deref *reconstitutes* a fresh db value from the store with lazy
node fetch, so re-reading is also a perf cost; `seon.db` already exposes an
explicit-db arity for every read — use it (`datahike-primer.md:62-73`). And when
you DO need a fence against a concurrent writer, the primitive is `:db.fn/cas`
(pure data that crosses the wire), not a re-read-and-check
(`datahike-primer.md:92-122`).

### 9. Threading connections/objects through call sites → `db` first arg / bound `*conn*`

**Wrong instinct** (pass the conn around, or worse pass `db` in the middle):

```clojure
(defn session-evals [agent-id db] ...)   ; db buried second
```

**Idiomatic** (db is the first parameter; the live conn is bound, not threaded):

```clojure
(defn session-evals [db agent-id] ...)   ; db first — see Domain Guidelines
;; and the pod's *conn* is bound for you; never thread it through every fn
```

**Why here:** "Functions receive `db` as first parameter" is a stated domain
guideline, and the codebase's own audit flagged `effective-cap`/
`current-session`/`session-evals` for burying `db` second
(`docs/conventions.md` / `clojure-idiom-audit-2026-06-25.md:142-146`). Opaque
runtime objects (conn managers, atoms, process handles) should stay OUT of
public agent-facing surfaces entirely — the pod's `*conn*` is bound for you,
never threaded through call sites (`docs/conventions.md:468-474`). Threading a
connection everywhere is the OO instinct to pass a service handle; here the
handle is ambient and the *value* is what you pass.

### 10. Guessing a library's behavior → read the vendored source, test in the REPL

**Wrong instinct:** write `:db.fn/call` to fence a write, or assume `as-of`
changes a db's basis-t, or assume `memoize` on a db value is cheap — all from
training memory.

**Idiomatic:** open `reference-code/` and confirm before you write. Examples
where memory would have been wrong:

- An inline tx-fn (`:db.fn/call`) carries a **closure** and CANNOT cross the
  pod↔wire-server frame (only values cross); the wire-crossable fence is
  `:db.fn/cas`, which is pure data (`datahike-primer.md:76-90`,
  `reference-code/datahike/src/datahike/db/transaction.cljc:1052-1068`).
- `as-of`/`since`/filtered db values report their **origin db's** `max-tx`, NOT
  the as-of point — Gemini got this wrong; the source is right
  (`datahike-primer.md:124-140`,
  `reference-code/datahike/src/datahike/db.cljc:493`).
- `memoize` on a db value confirms a hash hit with `=`, and `equiv-db` walks the
  *entire* EAVT index — faulting every node in from disk on a cache HIT
  (`datahike-primer.md:142-169`, `reference-code/datahike/src/datahike/db.cljc:674`).

**Why here:** the default failure is writing Clojure from training-memory in the
wrong mindset, *guessing* library semantics, producing confident wrong code
(`CLAUDE.md` "Slow Is Fast" §3). The reference source is vendored precisely so
you never have to guess — never unzip a deployed dep, `reference-code/` has the
same source checked out and grep-able. A 30-second REPL experiment beats hours
of debugging (`CLAUDE.md` §4).

### 11. Imperative async (callback pyramids, promise-as-control) → CLJS native `^:async`/`await`

**Wrong instinct** (callback nesting, or a bare top-level `await`):

```clojure
(.then (js/fetch url) (fn [r] (.then (.json r) (fn [j] ...))))  ; pyramid
(await result/123)   ; top-level — throws "await can only be used in async contexts"
```

**Idiomatic** (`await` only inside an `^:async` fn; agents get data, not Promises):

```clojure
(defn ^:async fetch-thing [url]
  (let [resp (await (js/fetch url))]
    (await (.json resp))))
```

**Why here:** the pod is core.async-free; `await` is a *macro* that asserts
`(:async &env)` and only expands inside an `^:async` fn body
(`reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977`); a
top-level `(await x)` macroexpansion throws because there is no async env. The
eval batch path auto-awaits a returned Promise so quick verbs read as
synchronous (`src/seon/eval.cljs` `maybe-await-value`), and a second
instrument pass over an already-wrapped `^:async` fn mis-detects it as sync and
wedges the pod — never re-instrument in one process. Full grounding lives in the
`clojurescript` skill; don't roll core.async into the pod.

### 12. `:or` destructuring for present-nil keys → explicit `or`

**Wrong instinct:**

```clojure
(defn impl [{::keys [model] :or {model default-model}}] ...)
;; :or does NOT apply when {::model nil} is explicitly passed
```

**Idiomatic:**

```clojure
(defn impl [{::keys [model]}]
  (let [model (or model default-model)] ...))
```

**Why here:** `:or` only fills a *missing* key; a present `nil` slips through and
becomes your value (`docs/conventions.md:283-296`). This pairs with "optional =
absent, never store nil" — to clear a persisted field you retract it explicitly;
omitting a key means "leave unchanged" (`docs/conventions.md:298-299`). Coming
from languages where `null` and "absent" blur, this bites every time.

### 13. Reaching for `:any`/`:some`/`:maybe` → precise specs (with the boundary exception)

**Wrong instinct** (loose specs because the precise one is more typing):

```clojure
(schema/register! ::result :any)
(schema/register! ::name [:maybe :string])
```

**Idiomatic** (concrete type; optionality via `{:optional true}`, not `:maybe`):

```clojure
(schema/register! ::result ::grep-response)
;; optional field absent vs present — handled in the :map, not the value type:
[:map [::name {:optional true} ::name]]
```

**Why here:** `:any`/`:some`/`:nil`/`[:maybe X]`/mixed-type enums are *rejected*
by `validate-persisted-schemas!` at startup (`datahike` skill "Banned Types");
the no-`:any` rule nudges agents toward precise, discoverable, generatively-
testable specs. The *documented exception* is a genuine third-party boundary —
where the value is whatever an external library (datahike, a JS API) hands back
and there is no honest tighter type, `:any` is acceptable
(`docs/conventions.md:476-493`). The violation is loose-by-laziness, not
loose-at-a-real-boundary.

### 14. `foo-v2` / parallel namespace to "house a fix" → fix in place

**Wrong instinct:**

```clojure
;; "I'll add do-thing-new and deprecate do-thing"
;; "I'll make a v2 schema and migrate callers later"
;; "I'll put the fix in a fresh ns to dodge the require cycle"
```

**Idiomatic:** the fix lives in the existing fn/schema/ns. Bump the schema in
place and fix the callers in the same patch; fix the require cycle; change the
implementation.

**Why here:** the whole repo is on a feature branch — atomic refactors are the
*cheap* option, not the expensive one (`CLAUDE.md` "Don't be a dumbass"). A
parallel `foo-v2` leaves two versions in the tree, doubles the surface for the
next bug, and the comment explaining why the duplicate exists outlives everyone
who knew the reason. This is the same "register the shape once, reference it
everywhere" rule applied to code: duplication guarantees drift.

---

## Exemplar-source grounding — the anti-patterns, shown in live seon code

The anti-patterns above cite the docs/primer that MANDATE each idiom; this
section grounds them in the actual exemplar SOURCE doing it right, so an agent
can see the pattern in working code (not just a prescription). The richest single
exemplar is `seon.agent.todo` — its own docstring calls it "THE EXEMPLAR
store/retrieve ns" (`src/seon/agent/todo.cljs:6-8`).

- **#1 reduce/into over a mutable loop:** `src/seon/agent/todo/internal.cljs:226-236`
  (`compile-plan` emits the whole tx with one `mapv`); `:258` of
  `src/seon/render/value.cljs` (`into {} (map …)`); `reference-code/datahike/.../db/
  transaction.cljc:730` (`into #{} (comp (filter …) (map …))` transducer). Genuine
  local mutable build uses `volatile!`, never an atom:
  `src/seon/agent/todo/internal.cljs:202-216`. Short-circuit a fold with `reduced`:
  `reference-code/malli/src/malli/core.cljc:1068-1078`.
- **#2/#3 no-kinds, entity = attrs + refs:** `src/seon/agent/todo.cljs:35-46`
  (the three ref kinds — `::owner` SCOPE ref, `::parent` plain TREE ref with no
  cascade, `::depends-on` cardinality-many DAG ref — and `::id` as a
  `{:seon.db/identity true}` attr); FIND-by-attribute-presence queries at
  `src/seon/agent/todo/internal.cljs:78-99` (`root-ids`/`all-root-ids` use
  `not-join` on attribute presence, never a `:kind` filter); `:seon.entity/id-attr`
  is attribute-presence enumeration, not a kind stamp, at
  `src/seon/schema.cljc:106-120,206-220`.
- **#4 namespaced keys + the `::`-expansion trap:** `src/seon/db.cljs:16-19`
  (ns docstring); the real gotcha that a `.internal` ns must write the FULLY-
  qualified key, NOT `::id` (which would expand to the wrong ns):
  `src/seon/agent/todo/internal.cljs:13-17`.
- **#5 errors-as-values:** `src/seon/agent/todo/internal.cljs:22,43-50`
  (`fail` + `write-result` map a tx envelope to the response shape, never throw);
  `src/seon/error.cljs` (the whole `->map` error-to-data converter, with
  `loop`/`recur` cause-walk at :36-41 and `cond->` assembly at :68-73);
  `src/seon/db.cljs:38-42` (transact! returns an envelope, "never a throw").
- **#6 `:malli/schema` + the two arg shapes:** `src/seon/agent/todo.cljs:77-177`
  (every request/response registered, map-in/map-out verbs); named-positional
  `:catn` at `src/seon/schema.cljc:221`; multi-arity `:function` at
  `src/seon/db.cljs:502-507` (transact!) and `src/seon/error.cljs:52-54` (`->map`).
- **#8/#9 db-as-value, threaded:** `src/seon/agent/todo.cljs:293,305,339`
  (each read binds `db @db/*conn*` ONCE then threads it); the explicit-`::db/db`
  arity everywhere in `src/seon/agent/todo/internal.cljs:61-186`.
- **cond->/case/cond + threading (the functional-core staples):** `cond->`
  optional-key build at `src/seon/agent/todo.cljs:192-200`; `case` dispatch at
  `:233-241,248,262,274`; `cond` guard ladder at `:180-201`; `->>` pipeline at
  `src/seon/agent/todo/internal.cljs:61-76`; `some->`/`some->>` nil-safe nav at
  `src/seon/error.cljs:61` and `src/seon/render.cljs:305`.
- **multimethods / `reify` (the rare "data over objects" escalation):** the entire
  integrant component lifecycle is multimethods dispatching on the config key —
  `reference-code/integrant/src/integrant/core.cljc:457-535`; malli schemas are
  `reify`'d protocols, not classes — `reference-code/malli/src/malli/core.cljc:1044-1083`;
  routing/config as data — `reference-code/reitit/doc/basics/route_syntax.md`.

## Minimal exemplar set — READ these to absorb idiomatic seon-Clojure by example

A new agent should READ these before writing pod Clojure. They are dense,
in-grain, and self-documenting — "the code IS the agent's manual."

1. **`src/seon/agent/todo.cljs`** — THE exemplar store/retrieve ns (says so at
   :6-8). The whole file: `register!` per attr with shared-shape references
   (:35-46), the work-queue `rules` as readable Datalog DATA passed as a parameter
   (:50-62), map-in/map-out verbs with `:malli/schema` (:173-345), `cond`/`case`
   guards, `cond->` optional-key map builds, `::ok?` error envelopes. If you read
   one file, read this.
2. **`src/seon/agent/todo/internal.cljs`** — the DERIVED-views companion: pure
   Datalog that recomputes blocked/ready/roll-up every read (nothing derivable is
   stored, :52-186), `->>` pipelines (:56-76), the `volatile!` two-pass plan
   compiler (:190-239), the reverse-ref recursive pull (:101-112). Shows
   derive-don't-store + functional accumulation.
3. **`src/seon/db.cljs`** (ns docstring :1-56 + the Datalog cheat sheet :67-115 +
   `transact!`/`query` :502-599) — namespaced-keyword maps in/out, the `:function`
   multi-arity schema pattern, db-as-a-VALUE threading, and the no-kinds query
   idioms (FIND by attribute presence, ref-join, upsert-by-identity).
4. **`src/seon/error.cljs`** (`->map`, the whole 73-line file) — errors-as-data end
   to end in one compact showcase: `loop`/`recur` over a bounded cause chain
   (:36-41), `cond->` conditional assembly (:68-73), `some->` nil-safe access
   (:61), a `:function` multi-arity schema (:52-54).
5. **`src/seon/schema.cljc`** (`register!` :193-234 + the shared-shape registry
   :88-136) — schema-first discipline: register ONCE, reference everywhere
   (`:seon.db/id`, `:seon.db/ref` as canonical shapes); the `{:seon.db/entity true}`
   marker derives `:seon.entity/id-attr` instead of stamping a `:kind`; the
   justified `:any` third-party-boundary exception.

---

## Grounding index (the strongest citations)

| Claim | File:line |
|---|---|
| Instrumentation wraps every schema'd fn, validates in/out, throws on mismatch | `reference-code/malli/src/malli/core.cljc:2203-2220` (`-instrument` `:3110`) |
| No entity kinds — find by attribute-presence, identify by unique attr, relate by refs | `docs/prds/agent-fsm/research/datahike-primer.md:24-53` |
| A db is a VALUE not a place; thread one snapshot | `datahike-primer.md:55-74`; `reference-code/datahike/src/datahike/db.cljc:493,674` |
| Errors-as-values envelope at verb boundaries; verbs skip the throwing validator | `docs/conventions.md:303-329`; `src/seon/instrument.cljc:42-88` |
| `await` is a macro requiring `(:async &env)` — no top-level await | `reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977` |
| transact! refuses unregistered/bare attrs at the boundary | `src/seon/db.cljs:841-857` |
| `:or` doesn't apply to present-nil; optional=absent, retract to clear | `docs/conventions.md:283-299` |

## Notes / open questions

- Items 1, 9 are grounded partly in the codebase's own idiom audit (an LLM
  finding the orchestrator largely accepted) rather than a library source — they
  are *house* idioms, flagged as such.
- The `^js %`-in-`#()` fragility nit and `:seon.eval/keys` destructuring nit from
  that audit were left OUT — they're real but low-leverage micro-nits, not
  mindset shifts; folding them in would dilute the skill.
