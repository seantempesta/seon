---
type: research
status: complete
tags: [research, context, render, sci]
---

# Default AI source boundary — 2026-09-06

## Question

As declared `:seon.render/ai` functions become authored Clojure source, what is
the smallest honest default for values without a domain renderer? This audit is
limited to the existing form provenance, database reads, admission artifact,
and value floor. Message and plan renderers are outside this inventory.

## Existing owners

`seon.render/render-form` is already the source-provenance owner. For an entity
it derives an identity lookup from installed identity attributes
(`src/seon/render.clj:901-925`). Its attribute branch is not suitable for the
new default: it emits a query for **every** entity possessing the attribute,
without constraining the reached entity (`src/seon/render.clj:915-920`). That
form cannot reproduce the value that caused the render.

The render request already distinguishes value, database snapshot, result
blob, walk attribute, and presentation identity (`src/seon/render.clj:105-170`).
Only an entity identity or result-blob digest is a durable requery identity;
`:seon.render.call/id` is the last presentation fallback
(`src/seon/render.clj:115-133`) and must never be emitted as executable source.

`seon.render.value/prepare` remains the terminal value owner. It admits a live
value or reconstructs semantic data from stored `result-edn`, projects it, fits
it, and emits both sinks (`src/seon/render/value.clj:483-560`).
`seon.render.value/render-ai` merely selects the text from that preparation
(`src/seon/render/value.clj:635-656`). Source construction must not move into
this namespace and must not make it rediscover where a value came from.

Admission already solves opaque-value preservation. A print node converts
Vars, types, classes, and objects to explicit reference/opaque data rather than
reader-hostile `#object` text (`src/seon/sci/admit.clj:397-435`); the same node
is canonical EDN (`src/seon/sci/admit.clj:437-457`) and is the sole durable
artifact from which semantic and receipt views derive
(`src/seon/render/value.clj:577-612`). SCI also binds a just-evaluated value as
`result/eN` inside its specific turn fork (`src/seon/sci/eval.clj:533-547`).
That symbol is valid authored source only in that fork, not a historical or
cross-run identifier.

The public database functions already provide the query boundary needed by
authored source. `seon.db/q` and `seon.db/pull` use the calling agent's database
when one is not supplied and propagate flat errors (`src/seon/db.clj:1118-1133`,
`src/seon/db.clj:1218-1239`). Generated source should name these qualified
functions, rather than the current unqualified `db` value and `db/pull`
symbols. SCI evaluates ordinary forms through its context; its namespace and
Var operations are context-scoped rather than JVM-global
(`reference-code/sci/src/sci/core.cljc:263-278`).

## Smallest in-place change

Keep one source builder in `src/seon/render.clj`, replacing the current default
AI floor selection with source derived by `render-form`; keep
`seon.render.value/render-ai` as the terminal function called by that source.
The generated expression should have the conceptual shape:

```clojure
(seon.render.value/render-ai
 {:seon.render/value (seon.db/pull '[*] [:identity/attribute value])})
```

Call preparation must continue to supply the floor's caps/profile world; the
source contains only the reproducible read. A flat error returned by the read
then remains the value handed to the total floor. Do not put a `get`, `get-in`,
or destructuring operation immediately around a database call: each would turn
a flat query error into an unrelated missing-key/nil result.

The cases are:

1. **Reached entity.** Emit a qualified `seon.db/pull` using the identity lookup
   already derived by `entity-lookup`. `:db/id` is valid only while the source
   is bound to the same sovereign database snapshot; a durable identity
   attribute is preferred when present.
2. **Reached attribute or nested scalar.** The source builder needs the
   reached entity lookup plus the exact cursor path. Pull the entity, then use
   the existing total `seon.render.data/at` semantics, which distinguishes a
   present nil from a missing path (`src/seon/render/data.clj:42-72`). A tiny
   existing-owner helper may compose those two operations while preserving a
   flat pull error; this belongs in `seon.render.data`, not in the terminal
   formatter. The current unit carries the walk attribute but `render-argument`
   does not carry the cursor (`src/seon/render.clj:135-158`), so the caller must
   hand the existing `:seon.render.data/cursor` through. No new route or render
   type is required.
3. **Anonymous value from the current turn.** Use the already-bound `result/eN`
   symbol when the source and value are being composed in that same turn fork.
4. **Stored or historical result.** Use the existing durable evaluation/result
   reference and its stored `result-edn`/result-blob artifact, then hand that
   unit to `seon.render.value/render-ai`. Never print the semantic JVM value
   back into source. This preserves opaque values and oversized-result requery
   identity.

Ownership is consequently narrow: `src/seon/render.clj` owns default source
selection and entity form construction; `src/seon/render/data.clj` owns an
error-preserving pull-plus-cursor accessor only if no existing public composed
read is found; the walk/web caller owns carrying its already-existing cursor
and subject identity; `src/seon/render/value.clj` and `src/seon/sci/admit.clj`
remain unchanged. Schema edits are needed only to declare an existing cursor
field on the render unit if it is not already declared.

## Unresolved provenance gaps

The current request does not carry a durable evaluation identity, only an
optional result-blob digest. A digest is adequate for requery but this audit did
not find an agent-facing existing function that turns that digest into a floor
unit without separately supplying blob custody. Historical anonymous values
therefore cannot yet receive honest executable source from the evidence visible
at this boundary. The caller must carry the existing evaluation/result fact or
the source builder must refuse with a flat diagnostic; emitting a literal is
incorrect.

The current request also drops `:seon.render.data/cursor` before producer
invocation, so attribute and nested-scalar source cannot be made exact from
`:seon.render.walk/attribute` alone. Until subject identity plus cursor are
carried together, keep the diagnostic rather than retain the current global
attribute query.

Finally, `seon.render.value/render-ai` requires caps on its unit and currently
returns explanatory text when they are absent (`src/seon/render/value.clj:494`,
`src/seon/render/value.clj:643-656`). Before adopting the wrapper form, a
focused call-preparation proof must show that its declared unit contract
receives those defaults during ordinary SCI evaluation. If it does not, the
correct fix is to expose the existing supplied-default contract on this
function, not to bake caps into generated source or introduce another
formatter.
