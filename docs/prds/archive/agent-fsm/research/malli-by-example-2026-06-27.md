---
type: research
status: active
tags: [research, schema, reference]
---

# Malli by Example — verified primer for `seon.schema/register!` and `:malli/schema`

Grounded by reading the real library source in `reference-code/malli/` and
Seon's own bridge. Every claim below carries a `file:line` citation. Nothing
here is from training-memory; where the bridge does NOT support a shape, that
is stated as a hard constraint.

## TL;DR

- Agents declare attribute shapes with `(seon.schema/register! ::k <malli-form>)`
  and never write datahike schema directly. `register!` returns `k`, validates
  the Malli form at registration time, and (CLJS) requires a multi-segment
  keyword namespace. (`src/seon/schema.cljc:191`, `:219`, `:222`.)
- Every public fn carries `{:malli/schema [...]}` and is instrumented at boot
  from the program graph — a WRONG schema throws at runtime, so schemas must be
  correct, not decorative. (`src/seon/instrument.cljc:315`.)
- Function schemas have exactly four canonical shapes:
  - nullary `[:=> [:cat] Ret]`
  - named-positional `[:=> [:catn [::a A] [::b B]] Ret]` (Seon-preferred for data fns)
  - map-in / map-out `[:=> [:cat ::request] ::response]` (preferred for API surfaces)
  - multi-arity `[:function [:=> ...] [:=> ...]]`
  An `:=>` input MUST be `:cat` or `:catn`; a nullary is `[:cat]` (empty). (`core.cljc:2149,2154`.)
- The malli→datahike bridge lives in `src/seon/db/internal.cljs` (NOT
  `schema/internal.cljc`). It maps scalar heads, `:seon.db/ref`, container
  cardinality, and the `:seon.db/identity` / `:seon.db/component` properties.
  Unmappable shapes throw. (`src/seon/db/internal.cljs:181-360`.)
- House rules (Seon-authored data): NO `:any` / `:some` / `[:maybe X]`; use
  `{:optional true}` and "absent = no key"; concrete types only; register a
  repeated shape ONCE under `:seon.<domain>/<name>` and reference it.
- The single best copyable example file is `src/seon/db/examples.cljs` — every
  form there compiles and is test-exercised.

---

## 1. `register!` — the contract

`(seon.schema/register! k v)` → returns `k`. (`src/seon/schema.cljc:191-232`.)

Mechanics, each cited:

- Returns the registered keyword `k`. (`schema.cljc:198,232`.)
- CLJS gate: the keyword NAMESPACE must be multi-segment (≥2 dot-separated
  segments). `:workout/date` throws; `:fitness.workout/date` / `:my.kb/title`
  are fine. JVM skips this gate. (`schema.cljc:222` →
  `schema/internal.cljc:145-166`.)
- Compile gate: the Malli form is compiled against the live registry; an
  invalid form (or one that references an UNregistered keyword) throws a
  legible `:user-input` ex-info naming common storable types. So referenced
  schemas must be registered FIRST (load-order convention). (`schema.cljc:223`
  → `internal.cljc:117-143`.)
- A `:map` schema carrying `{:seon.db/entity true}` in its properties is
  rewritten to also carry `{:seon.entity/id-attr <its-identity-entry>}`, which
  catalogues it as a stored entity KIND (the renderer enumerates instances by
  walking that id-attr's index — no per-row kind stamp). Maps WITHOUT the
  marker (request/response envelopes, view inputs) are NOT kinds; `register!`
  is silent about them. Entity-kind-ness is DECLARED, never inferred.
  (`schema.cljc:200-218,225` → `internal.cljc:74-115`.)
- `register-all!` registers `[k v]` pairs and returns the set of keys; odd
  arity throws. (`schema.cljc:242-260`.)
- `current-keys` → `(set keys)` snapshot; `enum-members` → members of an
  `:enum` form or `[]` (never nil). (`schema.cljc:234-240,150-158`.)

### Shared-shape rule (house rule)

If the SAME shape appears in two or more registrations, register the shape ONCE
under a `:seon.<domain>/<name>` keyword and REFERENCE it — never inline a
duplicated constraint. Canonical examples in the registry itself: `:seon.db/id`
is `[:string {:min 14 :max 14}]` and every id attr references it;
`:seon.db/ref` is the one canonical ref shape every ref attr references.
(`schema.cljc:88-104`.)

```clojure
;; ONE canonical shape, then reference it everywhere:
(schema/register! :seon.db/id [:string {:min 14 :max 14}])          ; schema.cljc:104
(schema/register! :my.kb/note-id [:and {:seon.db/identity true} :seon.db/id]) ; references it
```

---

## 2. `register!` catalog — copyable, verified examples

Every example below is the SAME shape used live in the codebase; citations are
to a real registration.

### Identity / natural key

```clojure
;; Plain identity (the simplest natural-key form):
(schema/register! :my.reading/id [:string {:seon.db/identity true}])  ; examples.cljs:53
;; Identity that also constrains to the canonical id shape:
(schema/register! :seon.agent.todo/id [:and {:seon.db/identity true} :seon.db/id]) ; todo.cljs:41
```

- Why/footgun: `{:seon.db/identity true}` makes transacting the SAME value an
  UPSERT (update-in-place, no duplicate), and lets you read by lookup-ref
  `[:my.reading/id "r1"]`. Both the `[:string {…}]` and the `[:and {…} ref]`
  shapes are recognized as identity. (`schema/internal.cljc:24-31`.)

### Plain scalars

```clojure
(schema/register! :my.reading/title  :string)            ; examples.cljs:54
(schema/register! :my.reading/rating :int)               ; examples.cljs:55
(schema/register! :seon.agent.todo/created-at :inst)     ; todo.cljs:45
(schema/register! :my.kb/title [:string {:min 1}])       ; length-constrained
(schema/register! :my.kb/score [:int {:min 0 :max 100}]) ; value-constrained
```

- Why/footgun: on `:string`, `{:min :max}` constrain LENGTH (`count`); on
  `:int`/`:double`/`:float` they constrain VALUE. Different meaning, same
  syntax. (`core.cljc:814-817` — `:string` uses `(-min-max-pred count)`,
  `:int` uses `(-min-max-pred nil)`.) Storable scalar types: `:string :int
  :double :float :boolean :keyword :inst :uuid :symbol`. (`db/internal.cljs:185-193`.)

### Cardinality-many (vector / set of scalars)

```clojure
(schema/register! :my.reading/tags [:vector :keyword])   ; examples.cljs:56
```

- Why/footgun: a `[:vector X]` / `[:set X]` / `[:sequential X]` container
  bridges to `:db.cardinality/many`; the CHILD `X` is the value type. So this
  attr holds a SET of keyword values, and transacting more tags ADDS to the set
  (to replace, retract the attr first). (`db/internal.cljs:267-284`; replace
  pattern `examples.cljs:122-134`.)

### Plain ref (intra-DB reference)

```clojure
(schema/register! :my.reading/author :seon.db/ref)       ; examples.cljs:57
```

- Why/footgun: a ref stores an EID. `:seon.db/ref` is the ONE canonical ref
  shape (`[:or :int :string [:tuple :keyword <lookup-val>]]`, `schema.cljc:88-93`)
  — reference it, never inline a ref shape. Link by tempid (same tx) or
  lookup-ref; NEVER put a human-readable name in the ref slot — join through it
  instead. (`examples.cljs:162-172`.)

### Component ref (parent owns the children)

```clojure
(schema/register! :my.reading/notes
                  [:vector {:seon.db/component true} :seon.db/ref])  ; examples.cljs:58-59
(schema/register! :seon.agent/sections
                  [:vector {:seon.db/component true} :seon.db/ref])  ; agent.cljs:165
```

- Why/footgun: `{:seon.db/component true}` → `:db/isComponent true`. Component
  children are pulled inline as nested maps and are DELETED with the parent
  (`retractEntity` cascades). The props map may sit after the head OR after the
  child — the bridge accepts either. (`db/internal.cljs:123-134,300,350`.)

### Entity-kind `:map` (declares a stored kind)

```clojure
(schema/register! :seon.agent.todo/todo
  [:map {:seon.db/entity true}        ; ← DECLARES this is a stored kind
   [:seon.agent.todo/id          :seon.agent.todo/id]         ; identity entry
   [:seon.agent.todo/title       :seon.agent.todo/title]
   [:seon.agent.todo/created-at  :seon.agent.todo/created-at]
   [:seon.agent.todo/description {:optional true} :seon.agent.todo/description]])
;; todo.cljs:78-84
```

- Why/footgun: the `{:seon.db/entity true}` marker is what catalogues the kind
  and derives `:seon.entity/id-attr` from the map's identity entry. WITHOUT it,
  the same map is treated as a transient envelope, never a kind. Each entry's
  value schema is the ATTR's registered keyword (or an inline form). Entries
  without `{:optional true}` are required. (`schema.cljc:200-218`;
  `schema/internal.cljc:74-115,86-101`.)

### Enum

```clojure
(schema/register! :seon.ai/provider [:enum :deepseek :anthropic :openai-compat]) ; ai.cljs:114
(schema/register! :seon.log/level   [:enum :error :warn :info :debug])           ; log.cljs:176
```

- Why/footgun: the bridge stores enums as `:db.type/keyword` and ONLY supports
  KEYWORD-valued enums — a string-valued `:enum` throws `unbridgeable-malli-form`.
  Keep enum members keywords. (`db/internal.cljs:222-233`.)

---

## 3. The malli→datahike bridge — exact mapping

Bridge entry point: `malli->datahike-attr` in `src/seon/db/internal.cljs:286-350`
(vector form `malli->datahike-schema` `:352-360`). It resolves the registered
Malli form, finds the value type, the cardinality, and reads two properties. It
is invoked lazily — the FIRST `transact!` touching an attr installs its
datahike schema. (`db/internal.cljs:1208-1256`.)

| Malli registration (what the agent writes) | datahike attr field | cite |
|---|---|---|
| `{:seon.db/identity true}` property | `:db/unique :db.unique/identity` | `internal.cljs:349` |
| `:seon.db/ref` (head/whole form) | `:db/valueType :db.type/ref` | `internal.cljs:219-220` |
| `{:seon.db/component true}` property | `:db/isComponent true` | `internal.cljs:350` |
| `[:vector X]` / `[:set X]` / `[:sequential X]` | `:db/cardinality :db.cardinality/many` (child `X` is the value type) | `internal.cljs:267-275` |
| scalar (non-container) | `:db/cardinality :db.cardinality/one` | `internal.cljs:271-275` |
| `:string` | `:db.type/string` | `internal.cljs:185` |
| `:int` | `:db.type/long` (NOTE: long, not "int") | `internal.cljs:186` |
| `:double` | `:db.type/double` | `internal.cljs:187` |
| `:float` | `:db.type/float` | `internal.cljs:188` |
| `:keyword` | `:db.type/keyword` | `internal.cljs:189` |
| `:boolean` | `:db.type/boolean` | `internal.cljs:190` |
| `:inst` | `:db.type/instant` | `internal.cljs:191` |
| `:uuid` | `:db.type/uuid` | `internal.cljs:192` |
| `:symbol` | `:db.type/symbol` | `internal.cljs:193` |
| `[:enum :a :b]` (KEYWORD members only) | `:db.type/keyword` | `internal.cljs:222-228` |
| `[:and base …extra]` | bridges on `base` | `internal.cljs:236-237` |
| `[:or A B]`, all alts → ONE type | that one type | `internal.cljs:249-256` |
| `[:or …]` MIXED types / unmappable alt | `:db.type/string`, value stored as pr-str'd EDN | `internal.cljs:249-257,362-379` |

### Banned / unbridgeable (hard constraints for the manual)

- `:number` is NOT a Malli type — use `:int` or `:double`. (`internal.cljs:200`,
  `schema/internal.cljc:135-138`.)
- `:any` / `:some` / `:nil` have no datahike value type → throw
  `unbridgeable-malli-form` if used as a STORED attr. (Allowed only in fn
  schemas at third-party boundaries; never as a persisted attr value.)
  (`internal.cljs:259-265`.)
- A `:map` / `:map-of` / `:tuple` cannot be a single attr's stored value type
  (head not in the type map → throws). A `:map` schema is an ENTITY shape (its
  entries each become their own attrs), not one attr's value. The lone
  exception is a `:db.secondary/only` vector-of-float tuple (embeddings).
  (`internal.cljs:259-265,327-347`.)
- String-valued `:enum` → throws (keyword members only). (`internal.cljs:227-233`.)

---

## 4. Function `:malli/schema` forms — the four canonical shapes

An `:=>` schema is `[:=> <input> <output>]` (optionally a third guard child);
its INPUT must be `:cat` or `:catn`, else malli throws `::invalid-input-schema`.
(`core.cljc:2149` requires 2-3 children; `:2154` rejects non-`:cat`/`:catn`
inputs.) `:cat` = unnamed positional args (0+ children); `:catn` = NAMED
positional, each child a `[tag schema]` entry. (`core.cljc:2981` `:cat`,
`:2995` `:catn` — entry-schema = tagged children like `:map`.)

### Nullary — `[:=> [:cat] Ret]`

```clojure
(defn current-provider
  {:malli/schema [:=> [:cat] :seon.ai/provider]}   ; ai.cljs:322
  [] ...)
```

- Why/footgun: a no-arg fn is `[:cat]` (empty `:cat`), NOT `[]` and NOT
  `[:cat :nil]`. (`core.cljc:2981` — `:cat` allows zero children.)

### Named-positional — `[:=> [:catn [::a A] [::b B]] Ret]` (Seon-preferred for data fns)

```clojure
(defn titles-by-author
  {:malli/schema [:=> [:catn [::author-name :string]] [:vector :string]]}  ; examples.cljs:166
  [author-name] ...)

(defn replace-tags!
  {:malli/schema [:=> [:catn [::id :string] [::tags [:vector :keyword]]] :any]}  ; examples.cljs:130
  [id tags] ...)
```

- Why/footgun: each arg gets a NAME via the `[tag schema]` entry — this is what
  makes a positional arg "specced and named" (the invariant), satisfying the
  no-bare-arg rule WITHOUT map-wrapping. An optional positional slot uses a
  props entry: `[::opt {:optional true} :int]` (entry-schema accepts
  `[tag props schema]`, same as `:map`). (`core.cljc:2434-2447`.)

### Map-in / map-out — `[:=> [:cat ::request] ::response]` (preferred for API surfaces)

```clojure
;; Register the request + response :map schemas first…
(schema/register! ::do-thing-request  [:map [::id ::id] [::opt {:optional true} ::opt]])
(schema/register! ::do-thing-response [:map [::result ::result]])

(defn do-thing
  {:malli/schema [:=> [:cat ::do-thing-request] ::do-thing-response]}
  [{::keys [id opt]}] ...)
```

- Why/footgun: ONE namespaced-keyword map in, one out; the request/response are
  REGISTERED `:map` schemas (discoverable, extensible). A response that is a
  success/failure union is an `:or` of two `:map` envelopes discriminated by a
  literal — see the real `::transact-response`
  (`[:or [:map [::ok? [:= true]] …] [:map [::ok? [:= false]] …]]`,
  `db.cljs:159-178`). These request/response maps are NOT entity kinds — they
  carry no `{:seon.db/entity true}`. (`db.cljs:132-141,159-178`.)

### Multi-arity — `[:function [:=> …] [:=> …]]`

```clojure
(defn ^:async transact!
  {:malli/schema
   [:function
    [:=> [:cat ::transact-request] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data]] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data] [::tx-meta ::tx-meta]]
         ::transact-response]]}
  [...] ...)
;; db.cljs:501-506 — REAL, and it mixes a map-in arity with named-positional arities.
```

- Why/footgun: `:function` wraps ≥1 `:=>` children, each a DISTINCT arity (malli
  groups by arity and rejects duplicates / non-function children). Use it for
  genuine multi-arity fns; every arity must be fully specced. (`core.cljc:2241,2246-2248`.)

### `^:async` + the `:any` return caveat

Two correct, distinct patterns — pick by whether your fn AWAITS internally:

```clojure
;; (a) ^:async fn: instrument AWAITS the returned Promise, then validates the
;;     RESOLVED value against the output schema. So type the output as the
;;     precise resolved shape (NOT a Promise):
(defn ^:async transact! {:malli/schema [:function [:=> [:cat ::transact-request] ::transact-response] …]} …)
;;     → output ::transact-response (the resolved envelope). db.cljs:422,501-506;
;;       await-then-validate wrapper: instrument.cljc:202-265.

;; (b) plain fn that RETURNS a transact! Promise un-awaited: the SYNCHRONOUS
;;     return is a Promise object, not the envelope map — so type it :any:
(defn rename-reading!
  {:malli/schema [:=> [:catn [::id :string] [::new-title :string]] :any]}  ; examples.cljs:111
  [id new-title]
  (db/transact! {::db/tx-data [{:my.reading/id id :my.reading/title new-title}]}))
```

- Why/footgun: for `^:async` simple-fixed-arity fns the output schema describes
  the RESOLVED value (validated on Promise resolution). For `^:async` variadic /
  multi-arity fns, output validation is DEFERRED — only input + arity are
  checked. (`instrument.cljc:267-303`.) If a NON-async fn just hands back a
  Promise, that Promise is the return value the validator sees, so `:any` is the
  honest type. `:any` here is the documented third-party/JS-shape exception, not
  a license to skip specs.

---

## 5. Why a wrong schema throws — instrumentation

`:malli/schema` metadata is not decorative. At boot, `instrument-from-db!`
queries the program graph for every `:seon.fn/sym` + `:seon.fn/spec`, resolves
the live JS var, and installs a validating wrapper (input + arity always;
output for sync and simple-async fns). The eval-tee instruments newly-defined
fns inline between boots. Default-ON; `SEON_INSTRUMENT=0/false/off/no` is the
only kill-switch. (`instrument.cljc:315-363,163-175`.) Consequence: a schema
that does not match how the fn is actually called — or what it returns — throws
at runtime. Fix the root cause (the call or the schema), never coerce around it.

Opt-outs exist only for the "errors are values" envelope verbs (they own their
validation and must never throw on bad input — e.g. `seon.db/transact!`,
`seon.agent.fs`, `seon.agent.message`). These are a hardcoded FQ-symbol set,
not a per-fn flag, because the CLJS analyzer strips schema/metadata markers.
(`instrument.cljc:42-88`.)

---

## 6. House rules (Seon-authored data) — each with a one-line example

- NO `[:maybe X]`. Optional = ABSENT, never nil. Use a `{:optional true}` map
  entry / `:catn` slot.
  - Don't: `[:map [::nick [:maybe :string]]]`
  - Do: `[:map [::nick {:optional true} :string]]` (`todo.cljs:84`)
- NO `:any` / `:some` for Seon-authored data. `:any` is allowed ONLY at genuine
  third-party / JS-return boundaries (a datahike report, an un-awaited Promise,
  an opaque Malli FORM value).
  - Allowed: `(schema/register! :seon.schema/form :any)` — a Malli schema form
    is a recursive heterogeneous third-party structure. (`schema.cljc:130`.)
- Concrete types only. Every persisted field has a specific type; `:number` is
  not a type.
  - Do: `[:int {:min 0}]` or `:double` — not `:number`.
- Absent = no key; retraction is EXPLICIT. Omitting a key in a transact map
  leaves the attr unchanged; clear with `[:db/retract eid :attr]`.
  (`examples.cljs:115-120`.)
- Shared shape → register ONCE, reference everywhere. If the same constraint
  appears twice, name it `:seon.<domain>/<name>` and reference it.
  - Do: `:seon.db/id` `[:string {:min 14 :max 14}]`, referenced by every id
    attr. (`schema.cljc:104`.)
- Keyword namespaces are DOMAINS with ≥2 segments (CLJS-enforced).
  - Don't: `:kb/title`. Do: `:my.kb/title`. (`schema/internal.cljc:145-166`.)

---

## Appendix — the copyable reference file

`src/seon/db/examples.cljs` is the curated, test-exercised DB manual: schema
registration (`register-reading-schema!` :48), writes via `transact!` (:71-141),
Datalog query shapes (:148-189), pull/entity reads (:195-211), and an inventory
discovery call (:217). It uses the `:my.reading/*` demo domain and every form
compiles — copy a body and swap in your own `:my.<domain>/*` attrs.
