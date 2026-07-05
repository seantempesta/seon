---
type: research
status: active
tags: [research, schema, agent]
---

# Malli instrumentation error data — what the report callback carries

Grounding for `seon.error/record!` ([[error-blame-strict-gate-2026-07-03]]
"Revision 2026-07-04"): what data malli's instrument `:report` callback
actually receives, what our envelope keeps vs discards, EDN-safety of
`m/explain`, async/Promise behavior, and prior art. Every claim cited to
`reference-code/malli` (the vendored ground truth) or `src/seon`.

## TL;DR

1. **Malli's `:report` receives the FULL args vector in every report type**
   — `::invalid-input`/`::invalid-output`/`::invalid-arity`/`::invalid-guard`
   all carry `:args` (and output carries `:value` too); construction sites
   `reference-code/malli/src/malli/core.cljc:2213-2220`. Our own wrapper
   (`src/seon/instrument.cljc:341-360`) mirrors the same keys exactly.
2. **Our `explain-payload` already destructures `:args` and discards it** —
   it keeps only the failing leaf (`got-edn` + `arg-index`). Full-args
   capture is a pure READ: one `tokens/bounded-pr-str` of the `:args` malli
   already passes. Zero new plumbing. (Compose with `pr-str-readable`'s
   fn-stubbing walk for round-trip safety.)
3. **`m/explain` output is NOT EDN-safe** — `:schema` (top-level and per
   error leaf) is a live Schema reify object; `:value` is the raw runtime
   value. Sanctioned serializers: `m/form`, `me/humanize`, `me/error-value`
   (masking via `::me/mask-valid-values`, `error.cljc:395`). **Surprise:
   our envelope's `:seon.error.malli/errors` (line 225) persists RAW leaf
   maps with live Schema objects inside — `record!` must sanitize or drop.**
4. **Async: malli's stock wrapper validates the Promise OBJECT synchronously**
   (no then/await anywhere in `-instrument-f`); our `injecting-fschema` does
   `.then`-validate the resolved value — but attaches **no rejection
   handler**, so a rejected Promise is observed by NO instrumentation layer
   today; it falls through to the caller (eval's await-catch for turn-scoped
   calls) or `unhandledRejection`. **And an async invalid-output becomes an
   async REJECTION, not a sync throw.** Unwrappable async shapes get no
   output validation at all (structural opt-out).
5. **Prior art: the violation-as-data shape IS the `(type, data)` report
   pair** (= `m/-fail!`'s ex-data `{:type … :data …}`, `core.cljc:203-207`).
   virhe/pretty's intermediate is a print document (`{:title :body}` fipp
   groups), not a reusable data shape — mirror the report map, don't reuse
   virhe.

## 1. What malli's `:report` callback receives

`m/-instrument` (`reference-code/malli/src/malli/core.cljc:3110-3131`)
defaults `:scope` to `#{:input :output :guard}` and `:report` to `m/-fail!`
(line 3126-3128), then delegates to the schema's `-instrument-f`.

### Single-arity `:=>` — the construction sites

`core.cljc:2203-2221` (the `:=>` schema's `-instrument-f`):

```clojure
(fn [& args]
  (let [args (vec args), arity (count args)]
    (when wrap-input
      (when-not (<= min arity (or max miu/+max-size+))
        (report ::invalid-arity {:arity arity, :arities #{{:min min :max max}}, :args args, :input input, :schema schema}))
      (when-not (validate-input args)
        (report ::invalid-input {:input input, :args args, :schema schema})))
    (let [value (apply f args)]
      (when (and wrap-output (not (validate-output value)))
        (report ::invalid-output {:output output, :value value, :args args, :schema schema}))
      (when (and wrap-guard (not (validate-guard [args value])))
        (report ::invalid-guard {:guard guard, :value value, :args args, :schema schema}))
      value)))
```

Per report type:

| type | keys | full args? | notes |
|---|---|---|---|
| `::invalid-arity` | `:arity :arities :args :input :schema` | **yes** (`(vec args)`, line 2210) | `:arities` = `#{{:min … :max …}}` |
| `::invalid-input` | `:input :args :schema` | **yes** | `:input` = the `:cat`/`:catn` Schema OBJECT |
| `::invalid-output` | `:output :value :args :schema` | **yes** | `:value` = the actual return value |
| `::invalid-guard` | `:guard :value :args :schema` | **yes** | exists (line 2220); guard validates `[args value]` |

`:input`/`:output`/`:guard`/`:schema` are all live Schema objects (children
resolved at line 2204-2206), NOT forms.

### Multi-arity `:function`

`core.cljc:2280-2295`: each child `:=>` arity is instrumented individually
(so the per-arity reports above apply unchanged); the dispatch layer adds
one more construction site — `core.cljc:2291`:

```clojure
report-arity #(report ::invalid-arity {:arity arity, :arities arities, :args args, :input input, :schema this})
```

(here `:arities` is a set of arity keys, `:input` may be nil for an unknown
arity, `:schema` is the whole `:function` schema.)

### `:fn-name` is added by `mi/instrument!`, not the core wrapper

`reference-code/malli/src/malli/instrument.cljs:105` — when a `:report`
option is supplied, `instrument!` wraps it:

```clojure
(cond-> $ report (update :report (fn [r] (fn [t data] (r t (assoc data :fn-name (symbol (name n) (name s))))))))
```

So our `ei/report-fn` (passed at `src/seon/instrument.cljc:511`) receives
`:fn-name` as a qualified symbol on every report. (malli.dev.pretty's
formatters, `dev/pretty.cljc:51-84`, consume exactly these keys —
independent confirmation of the contract.)

### Our own wrapper emits the identical shape

`injecting-fschema`'s `-instrument-f` (`src/seon/instrument.cljc:341-360`)
reports `{:arity … :arities … :args args :input input :schema s}`,
`{:input input :args args :schema s}`, and
`{:output output :value v/ret :args args :schema s}` — the same keys, so
`explain-payload` needs no per-wrapper branching. (Ours omits
`::invalid-guard`; we never use `:=>` guards.)

## 2. What `explain-payload` keeps — and discards

`src/seon/error/instrument.cljc:175-233`. The destructure at line 181-182:

```clojure
[report-type {:keys [input output args value schema fn-name arity arities]
              :as _data}]
```

**`:args` is already in hand.** It is used only as the explain VALUE for
the input case (line 187: `[input args :seon.error.kind/malli-instrument-input]`),
then the envelope keeps:

- `got-edn` — ONLY the failing leaf value, `(get-in explain-value (:in first-leaf))`
  bounded to 50 tokens (lines 199-200, 222);
- `arg-index` — `(first (:in first-leaf))` when numeric (lines 206-209);
- `return-value-edn` — bounded `:value`, output case only (line 227-228).

This confirms the research doc's Gap 1 verbatim
([[error-time-travel-reproduction-2026-07-04]] §4 gap 1): the full arg
vector is discarded even though malli hands it over on every report.

### Full-args capture = a pure read

Add to the `cond->` (around line 226):

```clojure
args (assoc :seon.error.malli/args-edn (tokens/bounded-pr-str args 200))
```

Zero new plumbing — `args` is already destructured; every report type
carries it (§1). The one printer is `seon.ai.tokens/bounded-pr-str`
(`src/seon/ai/tokens.cljc:88-95` — `pr-str` clipped to a token budget with
an ellipsis marker).

**Serialization caveat**: `bounded-pr-str` calls plain `pr-str`; args can
contain fn objects (a naive print yields unreadable `#object[…]`). This ns
already owns the fix — `pr-str-readable`
(`src/seon/error/instrument.cljc:98-115`) postwalks fns into the
`:seon.error.malli/fn` placeholder before printing. The safe bounded form
is compose-then-clip:

```clojure
(tokens/clip-str (pr-str-readable args) 200)
```

(or teach `bounded-pr-str` an optional pre-walk — but that touches the ONE
estimator ns; the local composition is cheaper). Note the clip means
`args-edn` is `read-string`-able only when under budget — same accepted
trade `got-edn` already makes; for push-button re-invocation the budget
should be generous relative to the 50-token leaf bound.

## 3. Is `m/explain` output EDN-safe / transactable? NO — serialize first

`m/explainer` (`core.cljc:2643-2658`) returns:

```clojure
{:schema schema'   ; the compiled Schema OBJECT (a reify), not a form
 :value value      ; the raw runtime value, unbounded
 :errors errors}
```

Each error is `miu/-error`
(`reference-code/malli/src/malli/impl/util.cljc:19-21`):

```clojure
{:path path, :in in, :schema schema, :value value (, :type type)}
```

— again a live Schema object under `:schema` and the raw offending value
under `:value`. Schema objects are reify instances (they do print as their
form via `IPrintWithWriter`, but embedded `[:fn #object[…]]` closures make
even the printed form unreadable, and datahike/nippy cannot transact the
object). **Not persistable as-is.**

Sanctioned serializers:

- **`m/form`** — Schema → data form (what our envelope already does at
  `error/instrument.cljc:216-217`); may still embed fn objects, hence
  `pr-str-readable`'s stubbing walk.
- **`me/humanize`** (`reference-code/malli/src/malli/error.cljc:374-390`)
  — pure strings in maps/vectors, fully EDN-safe.
- **`me/error-value`** (`error.cljc:392-403`) — "the parts of value that
  are in error", **with masking of valid (potentially sensitive) siblings**:
  the documented options at `error.cljc:395-398` are
  `::me/mask-valid-values` (the mask, e.g. `'...`), `::me/keep-valid-values`,
  `::me/accept-error`, `::me/wrap-error`. Implementation:
  `-error-value`/`-masked` at `error.cljc:227-239`. malli.dev.pretty's own
  printer defaults `::me/mask-valid-values '...`
  (`dev/pretty.cljc:17`) — masking is the blessed way to quote an
  offending map without leaking every valid field.

### Minimal persistable EDN shape

Everything the error datom needs, all queryable strings/vectors/keywords:

```clojure
{:seon.error.malli/schema     (m/form schema)            ; via pr-str-readable if fns
 :seon.error.malli/path       (vec (:in first-leaf))     ; data path into the value
 :seon.error.malli/explain-path (vec (:path first-leaf)) ; schema path
 :seon.error.malli/leaf-type  (:type first-leaf)         ; e.g. :malli.core/missing-key
 :seon.error.malli/humanized  (me/humanize exp)          ; strings only
 :seon.error.malli/got-edn    (bounded leaf value)       ; offending value, bounded
 ;; optional, masked context around the offense:
 :seon.error.malli/error-value-edn
 (tokens/clip-str (pr-str-readable (me/error-value exp {::me/mask-valid-values '...})) 100)}
```

Our envelope already produces all but the last — the shape is right.

### Load-bearing surprise: `:seon.error.malli/errors` is not transactable

`error/instrument.cljc:225`:

```clojure
(seq leafs) (assoc :seon.error.malli/errors (mapv #(into {} %) leafs))
```

Each leaf map still carries the live Schema object (`:schema`) and the raw
value (`:value`). Today this only rides ex-data → `:seon.error/data` in an
in-memory eval envelope, so nothing breaks. The moment `record!` persists
the envelope, this key fails serialization (or bloats unboundedly).
`record!` must either drop it or sanitize per leaf
(`update :schema m/form` + bounded `:value`), and the registered shape
`[:vector :map]` (line 78) should tighten to the sanitized leaf shape.

## 4. Async fns — who sees a rejected Promise?

### Malli's stock wrapper: synchronous, Promise-blind

`core.cljc:2216-2218`: `(let [value (apply f args)] (when … (not
(validate-output value))) …)` — output validation runs **immediately on
whatever `f` returned**. For a `^:async` fn that is the `js/Promise`
object itself → `::invalid-output` on every call (the exact failure
`seon.instrument`'s docstring names, `src/seon/instrument.cljc:191-193`).
There is no `.then`, no await, no rejection handler anywhere in malli's
`-instrument` path — a rejected Promise passes through malli untouched.

### Our layer: resolution validated, rejection NOT

Routing (`register-target!`, `src/seon/instrument.cljc:364-399`):

- **async + simple fixed-arity `:=>`** → `injecting-fschema`. Its wrapper
  (`instrument.cljc:349-356`):

  ```clojure
  (let [ret (apply f args)]
    (if (and ret (fn? (.-then ret)))
      (.then ret (fn [v]
                   (when (and wrap-out (not (vout v)))
                     (report :malli.core/invalid-output …))
                   v))
      …))
  ```

  One-arg `.then` — **resolution** is validated against the output schema;
  **rejection has no handler** (no second `.then` arg, no `.catch`). The
  wrapper returns the chained Promise, so the rejection propagates to the
  caller unchanged, unobserved by instrumentation.
- **async + variadic / multi-arity / `:function` / unresolvable var** →
  `async-unwrappable?` (`instrument.cljc:180-214`) → **register NOTHING**.
  Neither inputs nor outputs are validated at runtime for these fns; the
  schema is contract-documentation only.

Wrapping order: there is only ONE wrapper layer. We register the
`injecting-fschema` reify into malli's function-schema registry
(`m/-register-function-schema!`, line 395); malli's `mi/instrument!` does
the var surgery (`instrument.cljs:77-85`, `malli$instrument$original` /
`malli$instrument$instrumented?`) and invokes OUR `-instrument-f` — our
code runs inside malli's machinery, not stacked on top of it.

### Conclusion — the answer for `record!`

When an async instrumented fn's Promise **rejects**, today **no
instrumentation layer observes it**:

1. malli: no handler (sync wrapper, and for our fns it isn't even in the
   path — ours is).
2. our `.then(onResolve)`: rejection skips the callback; the chained
   Promise re-rejects with the same reason.
3. the caller: turn-scoped calls go through `seon.eval`'s Promise
   auto-await, whose catch converts the rejection into a `:seon/error`
   envelope — that is the ONLY structured observer today.
4. anything core-internal that neither awaits nor `.catch`es →
   Node `unhandledRejection` (the proposal's "net" layer is genuinely
   load-bearing, not belt-and-braces).

**Second surprise**: when the RESOLVED value fails the output schema,
`report-fn` throws **inside the `.then` callback** — which does not throw
synchronously at the call site; it converts the chained Promise into a
**rejection** carrying the envelope. So async `invalid-output` violations
are only as visible as rejections are (points 3-4 above). A `record!` call
inside the wrapper (or a `.catch`-arm added alongside the `.then`) would
make both async failure modes first-class datoms regardless of caller
behavior.

Output-validation coverage summary for `^:async` fns:

| shape | input | output |
|---|---|---|
| simple fixed-arity `:=>` | sync, validated | validated on RESOLUTION (violation ⇒ rejection, not throw) |
| variadic / multi-arity / `:function` | not validated (opt-out) | not validated |
| rejection (any shape) | — | unobserved by instrumentation; eval-boundary or unhandledRejection |

## 5. Prior art — reuse the report pair, not virhe

- **The canonical violation-as-data IS the `(type, data)` report pair.**
  `m/-fail!` → `m/-exception` (`core.cljc:203-207`):
  `(ex-info (str type) {:type type, :message type, :data data})` — every
  malli-internal failure, including instrumentation with the default
  reporter, is already `{:type <kw> :data <the §1 map>}` in ex-data. There
  is no richer structured IR anywhere in malli; this is the shape to
  mirror (and the shape `explain-payload` already consumes).
- **virhe is print-only.** `malli.dev.virhe/-format` is a multimethod
  dispatching on ex-data `:type` (`dev/virhe.cljc:183`), returning
  `{:title … :body [:group …]}` — fipp layout documents (visual groups,
  `:break`s, color visits), not a data model of the violation. Not worth
  reusing for datoms.
- **malli.dev.pretty's formatters** (`dev/pretty.cljc:51-84`) are the
  reference CONSUMER of the §1 keys (`args`/`value`/`input`/`output`/
  `fn-name`) — useful as a checklist that our envelope covers everything
  the human-facing reporter shows. Its `reporter`/`thrower`
  (`pretty.cljc:164-179`) just print/throw strings.
- **Worth borrowing, small**: `me/with-error-messages`
  (`error.cljc:332-337`, per-leaf `:message` as data), `me/error-value`
  with `::me/mask-valid-values` (§3 — masked offending-value context), and
  `me/with-spell-checking` (`error.cljc:339-372`, adds
  `::me/likely-misspelling-of` paths — a stronger version of our
  hand-rolled `hint-for`, `error/instrument.cljc:157-169`).

  **CORRECTION (2026-07-05, hygiene-sweep evaluation — do NOT retry the
  hint-for replacement):** the `with-spell-checking` claim above is
  REFUTED on both legs, live-proven on the pod. (1) It only tags
  `::m/extra-key` (closed maps) and `::m/invalid-dispatch-value`
  (`error.cljc:349-355`; extra-key emission gated on `:closed` in
  `core.cljc:1306-1312`) — seon request schemas are OPEN maps, so on our
  envelope path it is a no-op (live: open-map explain → only
  `::m/missing-key`, no misspelling tag). (2) Even on a closed map its
  levenshtein length-threshold (`-similar-key`, `error.cljc:261-264`)
  rejects the same-name-different-namespace near-miss
  (`:seon.web/url` vs `:seon.agent.web/url` → nil
  `::likely-misspelling-of`, live-proven) — the exact wrong-ns rule
  `hint-for` exists for (revived codebase-wide in `8cbabc69`).
  `hint-for` stays; live: the instrumented `seon.agent.web/fetch` still
  yields "you passed :seon.web/url — the key is :seon.agent.web/url".
