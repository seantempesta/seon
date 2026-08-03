---
type: issue
status: open
severity: friction
tags: [issue, schema, config, flow, render]
---

# Replace recurring anonymous runtime contracts with named predicates

## Problem

Public runtime functions and registry declarations again use anonymous `:any`
or `:some` where an existing named contract, a concrete predicate, or a
discoverable domain schema describes the value. This reopens a contract class
that the archived database-boundary repair claimed to have removed.

The previous audit also combined two different census scopes: all exact
`:any` tokens in parsed active source, but only `:some` tokens inside
`:malli/schema` metadata. That produced a non-reproducible `58/22` headline.

## Evidence

### Method and dependency ledger

The census was re-derived on 2026-08-03 from the current shared tree, not
copied from the render-vocabulary lane:

- `resources/seon/schema.edn` was parsed as EDN and every exact `:any` and
  `:some` leaf was walked structurally.
- Every active `src/**/*.clj` and `src/**/*.cljc` file was read with
  `clojure.tools.reader` using the JVM reader-conditional branch. One pass
  counted exact keyword values in all parsed forms; a second counted only
  values below `:malli/schema` metadata. Comments and strings therefore do not
  affect either parsed count.
- The transaction verdict is grounded in the existing
  `:seon.store/transaction-data` declaration at
  `resources/seon/schema.edn:2677-2688` and Datahike's accepted map/vector/seq
  transaction inputs at
  `reference-code/datahike/src/datahike/api/impl.cljc:30-42` (selected
  submodule commit `0e8601d7f2f68c01070e13a95483bc82be04cabc`).
- The Flow emission verdict is grounded in the step-function transform
  contract—`[state' output]`, with `output` a map of output id to messages and
  no nil message—at
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:166-178,245-260`
  (selected submodule commit
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`).

The current counts are:

| Scope | `:any` | `:some` |
|---|---:|---:|
| `resources/seon/schema.edn` leaves | 19 across 18 keys | 0 |
| All parsed active source forms | 58 | 23 |
| `:malli/schema` metadata only | 54 | 22 |
| Parsed source data outside function contracts | 4 | 1 |

### Divergences from the render-vocabulary summary

- The schema count agrees exactly: 19 `:any` leaves across 18 schema keys and
  zero `:some` leaves.
- The inherited active-source `58 :any / 22 :some` does not describe one
  reproducible scope. All parsed source forms are `58/23`; public function
  contract metadata is `54/22`. The omitted twenty-third `:some` is the
  literal schema tag in `undefined-types` at
  `src/seon/schema/internal.cljc:20`, beside a literal `:any`; neither is a
  function contract.
- The numerical `58 :any` total still matches, but its current instance set
  does not match the lane's detailed log. The current tree additionally has a
  connection input on `config/apply-compiled!` at `src/seon/config.clj:242`
  and the synthetic schema-reference registry's `:any` at
  `src/seon/render/ns.clj:102`; neither appears in that log's source listing.
- All five named concrete defects are confirmed: `raw` returns `:any`
  (`src/seon/render/hiccup.clj:75`), instrumentation returns `[:set :any]`
  (`src/seon/instrument.clj:108`), database inputs use `:any`
  (`src/seon/config.clj:242,271-272`; `src/seon/render/walk.clj:340`), Flow's
  proc constructor types Vars as `:any` (`src/seon/flow.clj:95-96`), and 19
  transaction-data returns use `[:vector :some]`.
- The summary classed provider documents as genuinely polymorphic. The current
  tree's more specific issue
  [[provider-json-contracts-use-unbounded-any]] correctly narrows that verdict:
  provider variability is real, but JSON cannot carry arbitrary JVM values,
  so `:seon.ai/usage` and `:seon.ai/sent` need one named recursive JSON-value
  schema.

### Registry population verdicts

Each row is one schema-key instance. `:seon.render.data/window` is the only key
with two leaves.

| Instance | Leaves | Verdict | Evidence and replacement |
|---|---:|---|---|
| `:seon.sci.admit/request` | 1 | replace with a named schema | Its value slot repeats the already declared `:seon.sci.admit/value`; `resources/seon/schema.edn:47-49`. |
| `:seon.sci.admit/admitted` | 1 | replace with a named schema | Its value slot repeats `:seon.sci.admit/value`; `resources/seon/schema.edn:48-49`. |
| `:seon.sci.admit/value` | 1 | genuinely polymorphic | This is the one named boundary for whatever an SCI evaluation produced; `resources/seon/schema.edn:29-49`. |
| `:seon.ai/usage` | 1 | replace with a named schema | Values are recursive JSON, not arbitrary JVM objects; `resources/seon/schema.edn:170-173` and [[provider-json-contracts-use-unbounded-any]]. |
| `:seon.ai/sent` | 1 | replace with a named schema | Same named recursive JSON-value schema; `resources/seon/schema.edn:176-179`. |
| `:seon.config/apply-request` | 1 | replace with a named schema | The connection slot is a live branch connection and should reference the existing `:seon.store/branch-connection`; `resources/seon/schema.edn:819-825,2690`. |
| `:seon.render.data/path` | 1 | tightening changes behaviour—owner ruling required | Current navigation also accepts arbitrary set members; restricting the documented keyword/string/integer steps changes reachable values; `resources/seon/schema.edn:998-1001`, `src/seon/render/data.clj:46-64`. |
| `:seon.render.data/window` | 2 | replace with a named schema | Entry keys and values are arbitrary rendered data, already named by `:seon.render/value`; `resources/seon/schema.edn:1013-1017,2393`. |
| `:seon.error/source` | 1 | genuinely polymorphic | The one total normalizer accepts Throwables, Flow reports, flat errors, and transition data; `resources/seon/schema.edn:1149-1156`, `src/seon/error.clj:168-190`. |
| `:seon.sci.eval/session-def` | 1 | replace with a named schema | The faithful session value repeats `:seon.sci.admit/value`; `resources/seon/schema.edn:1241-1254`. |
| `:seon.sci.eval/evaluation` | 1 | replace with a named schema | Its admitted value repeats `:seon.sci.admit/value`; `resources/seon/schema.edn:1273-1282`. |
| `:seon.cluster.loop/evaluation` | 1 | replace with a named schema | Its admitted value repeats `:seon.sci.admit/value`; `resources/seon/schema.edn:1488-1501`. |
| `:seon.reconcile/adopt-identities` | 1 | replace with a named schema | Each value is an identity lookup-ref pair, not an arbitrary vector; name the tuple and reference the existing schema-value boundary for its value; `resources/seon/schema.edn:2243-2250`, `src/seon/reconcile.cljc:318-353`. |
| `:seon.render/literal` | 1 | delete | The render-model wave deletes the literal arm rather than preserving a second declaration model; `resources/seon/schema.edn:2325-2338`. |
| `:seon.render/unit` | 1 | delete | This umbrella `map-of` is superseded by declared `:seon.render/ai` and `:seon.render/html`; `resources/seon/schema.edn:2340-2351`. |
| `:seon.render/output` | 1 | delete | Output validity belongs to the declared projection schema; this second anonymous output contract is residue; `resources/seon/schema.edn:2340-2357`. |
| `:seon.render/value` | 1 | genuinely polymorphic | This is the named arbitrary-data boundary used by the generic value floor; `resources/seon/schema.edn:2374-2395`, `src/seon/render/value.clj:121-138`. |
| `:seon.render.walk/lookup` | 1 | genuinely polymorphic | Datahike accepts an entity id or heterogeneous lookup ref; this named dependency boundary intentionally follows Datahike; `resources/seon/schema.edn:2844-2852`. |

### Active function-contract verdicts

Counts in parentheses are exact keyword leaves in that contract. Grouped rows
list every instance and line; no occurrence is hidden behind an aggregate.

| Instance | Verdict | Evidence and replacement |
|---|---|---|
| `seon.ai/sink?` (`:any` ×1) | genuinely polymorphic | A total predicate must answer for every value; `src/seon/ai.clj:77-81`. |
| `seon.ai/completion-text` (`:any` ×1) | replace with a named schema | The input is provider JSON, not an arbitrary JVM object; `src/seon/ai.clj:590-608` and [[provider-json-contracts-use-unbounded-any]]. |
| `mailbox-step`, `turn-step`, `armer-step` transform inputs (`:any` ×3) | genuinely polymorphic | These are the dependency-owned Flow message positions; each transform still interprets only its declared input id: `src/seon/cluster/agent.clj:123-128,177-182,454-459`. |
| `mailbox-step`, `turn-step`, `armer-step` emissions (`:some` ×3) | replace with a named schema | Name Flow's `output-id -> non-nil messages` shape once: `src/seon/cluster/agent.clj:128,182,459`; dependency contract at `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:245-260`. |
| `seon.cluster.loop/disposition`, `messages` (`:any` ×2) | replace with a named schema | Both inspect an admitted evaluation value and should reference `:seon.sci.admit/value`; `src/seon/cluster/loop.clj:224,241`. |
| `seon.cluster.loop/terminal-tx` (`:some` ×1) | replace with a named schema | It returns transaction data; use `:seon.store/transaction-data`; `src/seon/cluster/loop.clj:283-290`. |
| `open-call`, `claim-tx`, `claim-call`, `release-tx`, `release-call`, `close-tx`, `close-call`, `plan-tx`, `plan-call` (`:some` ×9) | replace with a named schema | Every docstring and body returns Datahike transaction data; `src/seon/cluster/run.clj:231-243,264-271,275-296,311-316,320-328,332-338,342-356,370-377,381-394`. Use `:seon.store/transaction-data`. |
| `open-tx`, `receipt-start-tx`, `receipt-start-call`, `receipt-settle-tx`, `receipt-refusal-tx` (`:some` ×5) | replace with a named schema | These are transaction-data constructors/transitions; `src/seon/cluster/run.clj:449-455,478-501,514-539,549-571`. Use `:seon.store/transaction-data`. |
| `receipt-settle-call`, `receipt-refusal-call`, `recover-tx`, `recover-call` (`:some` ×4) | replace with a named schema | These return transaction data, including the legitimate empty vector; `src/seon/cluster/run.clj:764-792,819-843,866-872,876-914`. Use `:seon.store/transaction-data`. |
| `seon.cluster.work/unbound-value?` (`:any` ×1) | replace with a named schema | It walks the already admitted value; reference `:seon.sci.admit/value`; `src/seon/cluster/work.clj:124-135`. |
| `seon.config/apply-compiled!` (`:any` ×1) | replace with a named schema | The input is the live connection passed to `reconcile!`; use `:seon.store/branch-connection`; `src/seon/config.clj:239-252`. |
| `seon.config/effective` (`:any` ×2) | replace with a named schema | Both arities pass the input directly to Datahike reads; use `:seon.db/database-value`; `src/seon/config.clj:267-281`. |
| `seon.error/refusal` (`:any` ×1) | replace with a named schema | The function walks a Throwable cause chain; name a Throwable predicate instead of accepting arbitrary values; `src/seon/error.clj:168-180`. |
| `seon.flow/var-process` (`:any` ×2) | replace with a named schema | Both overloads immediately require `var?`; name and register the Var predicate; `src/seon/flow.clj:82-111`, [[flow-generators-reuse-one-mutable-sample]]. |
| `seon.instrument/instrumented` (`:any` ×1) | replace with a named schema | The return is a set of Vars, not arbitrary values; reuse the named Var predicate; `src/seon/instrument.clj:102-117`. |
| `seon.render/call-with-walk-context` (`:any` ×1) | genuinely polymorphic | This higher-order owner returns exactly whatever its supplied body returns; `src/seon/render.clj:90-107`. |
| `seon.render/declaration?` (`:any` ×1) | genuinely polymorphic | It is a total classifier over arbitrary data on render-namespaced keys; `src/seon/render.clj:232-245`. |
| `seon.render.block/select` (`:any` ×1) | replace with a named schema | The selected value is the generic render-value boundary; reference `:seon.render/value`; `src/seon/render/block.clj:556-601`. |
| `seon.render.data/at` (`:any` ×2) | replace with a named schema | Both the root input and successful wrapped output are generic render values; reference `:seon.render/value`; `src/seon/render/data.clj:40-64`. |
| `seon.render.hiccup/raw` (`:any` ×1) | delete | The guarded-render wave deletes `Raw` and `raw`; do not add a temporary second schema; `src/seon/render/hiccup.clj:68-77`. |
| `seon.render.hiccup/raw?` (`:any` ×1) | delete | The predicate disappears with the `Raw` escape hatch; `src/seon/render/hiccup.clj:79-83`. |
| `seon.render.hiccup/hiccup?` (`:any` ×1) | genuinely polymorphic | This is the total predicate that defines the accepted Hiccup grammar; `src/seon/render/hiccup.clj:89-130`. |
| `seon.render.hiccup/shorthand` (`:any` ×1) | tightening changes behaviour—owner ruling required | Invalid heads currently become a flat error value; a narrow input schema would make instrumentation reject before that behavior runs; `src/seon/render/hiccup.clj:253-277`. |
| `seon.render.hiccup/->string` (`:any` ×1) | tightening changes behaviour—owner ruling required | Invalid values currently serialize to the empty string as an explicit total fallback; a Hiccup-only contract removes that behavior; `src/seon/render/hiccup.clj:470-509`. |
| `seon.render.walk/refs` (`:any` ×1) | replace with a named schema | The first argument is dereferenced and queried as a database value; use `:seon.db/database-value`; `src/seon/render/walk.clj:327-355`. |
| `seon.render.web/server?`, `mult?` (`:any` ×2) | genuinely polymorphic | Both are total registered predicates for opaque dependency objects; `src/seon/render/web.clj:81-109`. |
| `seon.render.web/render-step` transform input (`:any` ×1) | genuinely polymorphic | This is Flow's message position; the function dispatches by the declared input id before interpreting it; `src/seon/render/web.clj:585-612,664-701`. |
| `seon.render.web/feed` (`:any` ×2) | genuinely polymorphic | The raw Ring request and Datastar/http-kit SSE response are a third-party boundary whose return is dependency-owned; `src/seon/render/web.clj:754-774,785-846`. |
| `seon.render.web/inbound` (`:any` ×1) | replace with a named schema | Both branches return an ordinary Ring response map with status, headers, and body; name that response shape; `src/seon/render/web.clj:921-956`. |
| `seon.schema/direct-references` (`:any` ×1) | replace with a named schema | Its second input is a Malli definition; use `:seon.schema/definition`; `src/seon/schema.clj:372-397`. |
| `seon.schema/enum-members` (`:any` ×1) | replace with a named schema | The returned members are the documented keyword/string/integer union; name and reuse that enum-member shape; `src/seon/schema.clj:991-1000`. |
| `seon.schema/call-with-registration-delta` (`:any` ×2) | genuinely polymorphic | Both higher-order arities return exactly the supplied body's value; `src/seon/schema.clj:2039-2060`. |
| `seon.schema/snapshot-state`, `restore-state!` (`:any` ×2) | replace with a named schema | These private test seams exchange the exact process-local schema-state map; name that state rather than accepting all values; `src/seon/schema.clj:2135-2145`. |
| `seon.schema/register-all!` (`:any` ×1) | replace with a named schema | The variadic values alternate registry key and Malli definition; use the existing `:seon.schema/kvs` name after giving it an honest pair shape; `src/seon/schema.clj:2148-2167`. |
| `seon.schema/schema-definition` (`:any` ×1) | replace with a named schema | It returns a registered Malli definition or nil; use `[:maybe :seon.schema/definition]`; `src/seon/schema.clj:2221-2225`. |
| `seon.schema.form/attr-form-properties` (`:any` ×1) | replace with a named schema | The input is a Malli definition; reference `:seon.schema/definition`; `src/seon/schema/form.cljc:16-21`. |
| `seon.schema.form/map-shape?` (`:any` ×1) | genuinely polymorphic | This is a total structural predicate and deliberately answers false for non-schema values; `src/seon/schema/form.cljc:23-27`. |
| `seon.schema.form/map-entries` (`:any` ×2) | replace with a named schema | Input is a Malli definition and output is a vector of named map-entry forms; `src/seon/schema/form.cljc:29-35`. |
| `seon.schema.form/schema-properties` (`:any` ×1) | replace with a named schema | Input is a Malli definition; `src/seon/schema/form.cljc:37-44`. |
| `seon.schema.form/enum-members` (`:any` ×2) | replace with a named schema | Input is a Malli definition and output uses the same enum-member union as the public owner; `src/seon/schema/form.cljc:76-84`. |
| `seon.schema.form/nilable-value-schema?` (`:any` ×1) | genuinely polymorphic | This total structural predicate returns false for every non-`[:maybe ...]` value; `src/seon/schema/form.cljc:86-90`. |
| `seon.sci.admit/admit-value` (`:any` ×1) | replace with a named schema | Its output slot is exactly the declared `:seon.sci.admit/value`; `src/seon/sci/admit.clj:445-457`. |
| `seon.sci.eval/interrupted?` (`:any` ×1) | genuinely polymorphic | It is a total predicate over arbitrary caught values and nested cause chains; `src/seon/sci/eval.clj:243-255`. |
| `seon.sci.eval/invoke` (`:any` ×2) | replace with a named schema | Arguments are a vector of arbitrary admitted values and the result slot is `:seon.sci.admit/value`; name/reference both instead of repeating leaves; `src/seon/sci/eval.clj:513-535`. |
| `seon.sci.eval/store-faithful-edn`, `store-faithful?` (`:any` ×2) | genuinely polymorphic | These total codec probes intentionally test any runtime value for exact EDN fidelity; `src/seon/sci/eval.clj:628-646`. |

### Parsed source data outside function contracts

These five tokens explain the difference between the full parsed-source and
function-contract counts. They are still explicitly verdicted.

| Instance | Tokens | Verdict | Evidence |
|---|---:|---|---|
| `seon.schema.internal/undefined-types` | `:any` ×1, `:some` ×1 | genuinely polymorphic, justified inspection data | These are literal Malli tags the checker rejects, not anonymous value contracts; `src/seon/schema/internal.cljc:20`. |
| `seon.render.ns/schema-ref-registry` | `:any` ×1 | genuinely polymorphic, justified inspection data | The synthetic registry resolves an arbitrary qualified schema reference only so Malli can reveal reference closure; it does not validate a runtime value; `src/seon/render/ns.clj:97-103`. |
| `seon.schema/_value-type` | `:any` ×1 | genuinely polymorphic | This installs the one named `:seon.schema/value` boundary used when dependency-owned data is genuinely arbitrary; `src/seon/schema.clj:767-769`. |
| `seon.schema/_kvs-type` | `:any` ×1 | replace with a named schema | `:seon.schema/kvs` is alternating registry-key/definition data and needs an honest pair shape; `src/seon/schema.clj:773-775`. |

The archived issue
[[archive/database-and-transaction-boundaries-use-anonymous-any-contracts]]
records the previous repair and is direct evidence that the transaction-data
instances are recurrence, not a new design.

## Owner

The schema owner for shared value, database, transaction-data, Flow emission,
JSON, Var, Ring-response, and process-local schema-state predicates. The
render-model wave owns the explicit deletion rows. The owner must rule on the
three behavior-tightening rows before production edits.

## Acceptance

- Delete every row marked delete in its already ordered render wave.
- Replace every named-schema row with one declared, reusable schema and an
  honest generator where Malli requires one; no anonymous equivalent remains.
- Obtain and record an owner ruling before tightening the path, shorthand, or
  serializer behavior.
- Retain `:any` only at the rows justified above, with the named boundary used
  everywhere one already exists.
- Re-run both structural censuses. Report registry leaves, all parsed active
  source forms, and `:malli/schema` metadata as three separate counts so one
  headline cannot mix their scopes again.
- Generated values pass every new predicate, invalid values fail at the one
  contract rather than a hand check, and the affected recurring tests plus a
  fresh cluster proof pass.
