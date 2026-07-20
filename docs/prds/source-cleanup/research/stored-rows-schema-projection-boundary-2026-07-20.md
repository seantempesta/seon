---
type: research
status: complete
tags: [research, schema, runtime]
---

# Stored rows to schema projection boundary (2026-07-20)

## Decision

Stage 5 collapses stored schema/function-form decoding into `seon.schema`, the
existing pure owner of `build-projection`. The database queries, execution-wire
payload, admission transition, compiler loading, and eval transaction remain
with their current owners. This is a decode-and-build deduplication, not a new
program registry or publication path.

The implementation boundary owns:

- `src/seon/schema.cljc` and `test/seon/schema_test.cljs` for the pure decoder,
  row normalization, projection construction, and exhaustive malformed-row
  tests;
- `src/seon/runtime/admission.cljs` and
  `test/seon/runtime/admission_test.cljs` for deletion of the admission-local
  decoder and one delegation test;
- `src/seon/eval.cljs` and the existing execution/eval tests for deletion of
  the execution-child decoder and the single function-contract decoder; and
- optionally `src/seon/ai/typeahead.cljs` plus its existing tests, only after
  the three correctness consumers are closed.

No query, transaction, transport, runtime activation, instrumentation, or UI
behavior moves into `seon.schema`.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding | Consequence |
|---|---|---|---|
| ClojureScript reader | `946d75f3483c0c8e784e6668bff2c71a25619a77`; runtime `org.clojure/clojurescript` `1.12.145` | `reference-code/clojurescript/src/main/cljs/cljs/reader.cljs:9-13,131-140,142-190` shows that `cljs.reader/read-string` wraps `cljs.tools.reader.edn` while merging a mutable process-global tag table and default reader function | Call `cljs.tools.reader.edn/read-string` directly with the fixed stored-form options; ambient custom readers cannot change canonical-program meaning in one process only. |
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f`; runtime `0.20.0` | `src/seon/schema.cljc:293-375` already compiles all schema and function forms against one complete composite registry and derives every projection index | Decode rows and call `build-projection`; do not compile a second registry or partially activate decoded forms. |
| Canonical schema encoding | first-party `schema/register!` / `schema/form-string` | `src/seon/schema.cljc:255-279,282-291` proves registered forms round-trip through EDN and stores their full `pr-str` | Stored schema strings are canonical EDN, not display text or arbitrary source forms. |
| Canonical function-contract encoding | current eval tee and host recorder | `src/seon/eval.cljs:2468-2521` and the in-flight host extraction in `src/seon/host/record.clj:91-128` obtain the analyzer-projected spec string before persistence | The decoder consumes the persisted string; it does not re-read function source or analyzer state. |
| Admission reconstruction | `seon.runtime.admission` | `src/seon/runtime/admission.cljs:197-223,241-285` acquires both row populations from one database value, decodes them locally, validates, reconciles instrumentation, then activates | Acquisition and publication remain here; only lines 213-223 collapse to the schema owner. |
| Execution-child reconstruction | `seon.execution` and `seon.eval` | `src/seon/execution.cljs:470-475` emits deterministic row vectors; `src/seon/eval.cljs:886-934` currently decodes/builds/activates before authored loading | Wire ordering remains a transport property; the child calls the same pure row decoder as admission. |
| Incremental function transition | current eval transaction compiler | `src/seon/eval.cljs:2604-2625,3626-3649` decodes a newly accepted `:seon.fn/spec`, combines it with the old projection, and validates before commit | The single-form decode must use the same reader policy, but candidate validation and error-to-agent conversion remain in eval. |
| Typeahead best effort | `seon.ai.typeahead` | `src/seon/ai/typeahead.cljs:789-798` try-decodes a stored function spec solely to derive injectable keys | It is a downstream optional consumer. Its catch-and-empty affordance behavior stays intact. |

The Datahike authority is deliberately absent from the pure owner. Admission
continues to query keyword schema keys and string function symbols; execution
continues to send ordinary row vectors. `rows->projection` accepts those
already acquired values and performs no database read.

## Current duplication and concrete failure

`seon.runtime.admission/committed-projection` and
`seon.eval/load-authored-program!` independently perform the same three
operations:

1. decode every `[schema-key form-string]` row;
2. coerce every function identity with `symbol` and decode its form string;
3. call `schema/build-projection`.

`seon.eval/function-contract-transition` independently performs the contract
half a third time. A reader-policy repair made in only one copy can therefore
admit one projection in the client and a different projection in the stable
execution child. That violates the committed-generation contract and can
surface later as an apparently intermittent Malli or transaction failure.

The shortest present falsifier is to temporarily bind a custom tagged-literal
reader in one runtime, persist a tagged form, and observe that ambient
`cljs.reader/read-string` behavior depends on process state. The fixed design
does not accept ambient custom readers at all: canonical rows either decode
under the explicit stored-form table in every consumer or fail before any
projection is activated.

## Exact pure API

### `read-stored-form`

The adversarial finding calls this helper private while also requiring
`seon.eval` to call it. Those requirements cannot both hold across namespaces.
Make it a public, `^:no-doc`, non-agent-facing pure function in `seon.schema`;
the narrow public surface is preferable to either a fourth decoder or a
schema namespace that understands eval tee rows.

The proposed shape is one closed namespaced request:

```clojure
(read-stored-form
  {::stored-attribute :seon.fn/spec
   ::stored-identity 'my.example/run
   ::stored-form "[:=> [:cat :int] :int]"})
```

Its Malli schema is:

```clojure
[:=>
 [:cat
  [:map {:closed true}
   [::stored-attribute [:enum :seon.schema/form :seon.fn/spec]]
   [::stored-identity [:or :qualified-keyword :qualified-symbol]]
   [::stored-form :string]]]
 :any]
```

`:any` is justified here: a Malli form is genuinely polymorphic EDN. The
function is still total over schema-valid requests in the data sense: it
returns the decoded EDN form or throws one structured `ex-info` describing a
corrupt/unreadable canonical-program row. It never mutates candidate or active
schema state.

Use `clojure.edn/read-string` on CLJ and call
`cljs.tools.reader.edn/read-string` directly on CLJS with an explicit standard
stored-form options map. Do not use `cljs.reader/read-string` for this helper:
its implementation merges the mutable `cljs.reader/*tag-table*` even when the
caller supplies options. Accepted tags are only the EDN standard forms Seon can
produce and consume identically at both active boundaries (`inst` and `uuid`;
no ambient default function and no application custom tags). The CLJS options
name those two reader functions explicitly and reject every unknown tag. This
is the one reader-table policy for both stored attributes.

Reject:

- a non-string form before invoking the reader;
- an empty string or a decode result that is absent;
- an unknown/custom tag;
- malformed or trailing unread data; and
- a decoded value that does not round-trip as the canonical stored EDN value.

The last check must compare structural re-read equality, not demand byte
equality from historical rows: whitespace is not semantic corruption. Read
the value, `pr-str` it, read that canonical string with the same table, and
require equality. A reader that consumes only the first value must also prove
EOF separately so `":int :string"` is rejected rather than silently becoming
`:int`.

Decode failure data contains exactly the attribute, row identity, and a
bounded projection of the offending string, plus
`:seon.schema/error :seon.schema/unreadable-stored-form`. It must not contain
the complete possibly hostile string in an exception message. Fault scope is
owned by the invoking boundary: admission/execution reconstruction treats a
bad durable row as a core publication failure; eval's pre-commit candidate
path retains its existing `:user-input` error conversion. The pure decoder
does not guess operational fault scope.

### `rows->projection`

The only ordinary public constructor takes a closed namespaced request:

```clojure
(rows->projection
  {::schema-rows [[schema-key form-string] ...]
   ::function-contract-rows [[symbol-or-string form-string] ...]})
```

Request schema:

```clojure
[:map {:closed true}
 [::schema-rows [:vector [:tuple :qualified-keyword :string]]]
 [::function-contract-rows
  [:vector [:tuple [:or :qualified-symbol :string] :string]]]]
```

The output is the existing projection map from `build-projection`; do not
invent a second result envelope or projection schema in this unit.

Normalization is deterministic and strict:

- schema identities remain qualified keywords;
- a contract string is coerced once with `symbol` and must become a qualified
  symbol; an input qualified symbol stays unchanged;
- duplicate schema keys or duplicate normalized function symbols are rejected
  instead of silently taking the last `into {}` value;
- every form is decoded with `read-stored-form`, carrying its attribute and
  normalized identity into failures; and
- only after both complete maps decode successfully does `build-projection`
  validate and construct the immutable projection.

No partial value is activated or returned on failure. Input row order cannot
change the projection, its fingerprint, or which duplicate error is reported;
normalize/sort by identity before decoding and duplicate detection.

## Exact caller edits and deletions

1. In `src/seon/schema.cljc`, add the request schemas, the explicit strict
   decoder, deterministic row-map helper, and `rows->projection` immediately
   before `build-projection`. `rows->projection` delegates to
   `build-projection`; `build-projection` remains the one compiled-projection
   owner.
2. In `src/seon/runtime/admission.cljs`, delete the `cljs.reader` require and
   delete lines 213-223 of `committed-projection`. Keep the public seam because
   admission tests and initialization intentionally call it, but make its body
   only translate admission request keys to `schema/rows->projection` keys.
   Queries, `execute-many`, publication retry, instrumentation reconciliation,
   and activation are unchanged.
3. In `src/seon/eval.cljs`, replace lines 924-929 in
   `load-authored-program!` with `schema/rows->projection`. Do not delete the
   namespace's `cljs.reader` require: many unrelated source/result/query reads
   still use it.
4. In `function-contract-transition`, replace the line-2617 direct reader call
   with `schema/read-stored-form`, passing `:seon.fn/spec`, the normalized
   qualified symbol, and the spec string. Preserve the existing reduction,
   changed-symbol set, candidate `build-projection`, rejection envelope, and
   no-commit-on-error behavior.
5. After those three callers and their tests are green, route
   `seon.ai.typeahead/injected-request` through `schema/read-stored-form` with
   the same contract identity if the row identity is available at that call
   boundary. If it is not, leave this optional best-effort read alone rather
   than inventing a fake identity or widening the decoder contract. This site
   does not block the correctness dedup exit.

The implementation must finish with exactly one occurrence of the stored
schema/contract reader policy. Ordinary EDN reads for source forms, query text,
result data, usage, and UI request bodies are unrelated and remain local.

## Decode and error policy

Malformed canonical rows are not recoverable by skipping them. Omitting one
schema or contract would publish a projection whose fingerprint and wrapper
population no longer describe database truth. Therefore:

- `rows->projection` fails the whole pure construction;
- admission's existing publication catch records one core fault and leaves the
  prior projection unavailable/retained according to its transition;
- execution-child loading rejects the host request and never begins authored
  compilation against a partial projection;
- a pre-commit eval contract decode remains an agent-visible rejected program
  change and transacts no tee rows; and
- typeahead, if folded in, remains best effort and converts the same decode
  failure to no injectables because it is a read-only affordance, never an
  admission authority.

Do not return an error map from `rows->projection`: callers could accidentally
activate it because projections are maps too. Structured exceptions are the
existing pure validation convention in `build-projection`; operational
boundaries already convert/record them correctly.

## Dependency order and active U4 overlap

This Stage-5 unit is downstream of the active runtime U4 integration. U4 is
currently editing the extraction/recording side (`src/seon/host/record.clj`,
`src/seon/host/context.clj`, and `src/seon/db/id.cljc` are dirty in the shared
tree) and has been moving execution-host responsibilities out of pod-side eval.
That does not invalidate the pure owner, but it can move or delete the current
`function-contract-transition` caller.

Implementation order:

1. wait for U4 to commit and release its paths;
2. re-read the resulting producer and host/execution load boundary, and map
   every surviving `:seon.schema/form` / `:seon.fn/spec` decode before editing;
3. implement and test the pure schema owner first;
4. migrate admission;
5. migrate the stable execution-child load path;
6. migrate whichever post-U4 owner performs the incremental function-contract
   transition;
7. optionally migrate typeahead; then run the focused gates and rescan.

No implementation lane should edit `src/seon/host/record.clj`,
`src/seon/host/context.clj`, or `src/seon/db/id.cljc` for this unit. If U4 leaves
both the old eval tee and the new host recorder active, that is a serious
one-mechanism conflict: stop and reconcile the recording owner before applying
this decode cleanup.

## Focused falsifiers

1. Build a projection from one schema row and one contract row, once with a
   string contract identity and once with the equivalent symbol. Require equal
   forms, contracts, dependency indexes, and fingerprint.
2. Feed the same rows in reverse order and require the same projection data.
3. Supply duplicate schema keys and duplicate contract identities where one is
   a string and one a symbol. Both must fail deterministically; neither may
   silently win.
4. For each stored attribute, supply malformed EDN, an unknown tag, an empty
   string, trailing second data, an unqualified identity, and a non-string form.
   Require the same bounded structured error fields and no candidate/active
   schema-state mutation.
5. Supply standard `#uuid` and `#inst` values nested as schema data and prove
   both active runtime reader branches reconstruct equal values. This pins the
   explicit reader table rather than only its rejection side.
6. Bind or install an ambient custom reader and prove `read-stored-form` still
   rejects its tag. This directly falsifies process-local reader drift.
7. In the admission test, redefine `schema/rows->projection`, call
   `admission/committed-projection`, and require the exact ordinary acquired
   rows were delegated once. Retain the current behavioral assertion that
   real rows produce the expected forms/contracts.
8. In the execution-child test, pass the same row vectors through
   `load-authored-program!` and require the activated object is the result of
   the shared constructor. A malformed row must reject before any authored
   namespace load callback runs.
9. Drive one malformed newly authored function contract through the eval
   transaction compiler. Require an agent-visible rejected program change,
   zero tee transaction rows, and the prior active projection unchanged.
10. Rescan with
    `rg -n "reader/read-string.*(spec|form)|build-projection" src/seon` and
    inspect every hit. The two full stored-row comprehensions and the
    single-contract direct read must be gone; unrelated EDN reads remain.

Focused test order is `seon.schema-test`, then
`seon.runtime.admission-test`, then the exact execution/eval selectors owning
`load-authored-program!` and candidate program rejection. The unit exit also
requires the complete CLJS suite because projection activation and
instrumentation are process-wide contracts. No live cluster proof is needed
for the pure dedup itself beyond the program's later frozen-source graduation;
the acceptance signal is byte-/data-identical projection reconstruction at
both runtime consumers and fail-closed handling of corrupt rows.

## Exit measure

The unit is closed only when one explicit stored-form reader policy in
`seon.schema` feeds admission, execution-child loading, and incremental
function-contract decoding; all local duplicate comprehensions are deleted;
malformed rows fail before activation or commit; focused tests and the full
CLJS gate pass; and the final rescan accounts for every remaining reader hit.
