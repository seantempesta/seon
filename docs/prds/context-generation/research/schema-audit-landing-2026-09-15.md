---
type: research
status: active
tags: [schema, contracts, diagnostics]
---

# Schema audit — 2026-09-15

## Verification boundary

Work in progress. The checker intentionally reports every unjustified position; it does not exempt another lane's files. No default stop, refork, or reseed was performed. The run7-wave owned turn, message, plan, bootstrap, and accretion paths are excluded from edits, not from inspection.

## Authority and dependency ledger

Read the repository AGENTS.md, run6-blockers-landing-2026-09-15.md, the archived contract-errors-lack-the-failing-path-and-vector-vs-seq-is-untaught.md issue, commits d4f8a536c and 4a559d658, and the run 4/5/7 model accounts. Inspected the instrument/error/schema.internal owners.

- Malli function wrappers: `reference-code/malli/src/malli/core.cljc:2204`; input validation precedes execution, output and `:=>` guards follow it. An argument-only condition requiring refusal before an effect belongs in the input schema, not an after-execution guard.
- Authored exemption facts: `src/seon/schema/admission.clj:184`; reuse `:seon.schema.admission/exemption`, `:seon.schema.admission/reason`, and a generator on the exact permissive schema node.
- Schema population: `src/seon/schema/form.cljc` derives database attributes. The checker uses the canonical database fixture and stored `:seon.fn/spec`, `:seon.fn/arities`, and `:seon.schema/form` rows.
- Datahike writer: `reference-code/datahike/src/datahike/db/transaction.cljc:1152`; transaction validation remains within the existing database owner.

## Live inventory method

Read-only JVM MCP, root `/Users/sean/src/seon`, cluster `default`, session `schema-audit`. The first query saw 1,016 function specifications. The saved candidate population contains 181 rows; a separately queried transient test row disappeared between queries, so these are dated observations, not maintained counts. Each entry below is inspected as schema syntax: literal enum/equality values and property maps are not schema slots. `test/seon/schema_audit_test.clj` is the recurring graph query and checker.

The independent arity query returned eight public rows without a maximum and without a guard: `seon.db/diff`, `seon.schema/register-all!`, and six CLI entry points. The checker cross-checks those graph rows against parsed tails. Stored aliases are followed for nilability; a stored `:maybe` cannot be justified as polymorphism.

```clojure
(let [database @(seon.operator/connection "default")]
  (seon.db/q '[:find ?sym ?spec
               :where [?f :seon.fn/private? false]
               [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]] database))
```

## Function inventory

Every row from the live candidate query is retained, including false positives and excluded ownership. “Justified” means the explanation is recorded on each actual schema node; this table is a dated report, not the checker's exemption list. Concrete changes and final gate results are recorded below as they complete.

| Function | Permissive shape | Decision |
|---|---|---|
| `seon.ai/completion-text` | `:any` | Justified on schema node: The decoded provider HTTP body is foreign data of any JSON shape; this total boundary reports malformed bodies as error values. |
| `seon.ai/sink?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.artifact/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.await/blocking-deref?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.blob/input-stream?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.blob/store-faithful-edn` | `:any` | Justified on schema node: The EDN round-trip probe accepts arbitrary Clojure values and reports unsupported objects by absence of faithful text. |
| `seon.blob/store-faithful?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.bootstrap-drive/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.call-preparation/callee-identity` | `:seon.schema/value` | Justified on schema node: SCI supplies JVM Vars, SCI Vars and interpreted function objects; an unrecognized callable has no durable identity. |
| `seon.call-preparation/hook` | `:seon.schema/value` | Justified on schema node: SCI's call-preparation hook receives arbitrary host or interpreted callable objects and forwards arguments to that callable's own contract. |
| `seon.call-preparation/state?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster.agent/armer-step` | `:any`; `:some` | Justified on schema node: core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract. |
| `seon.cluster.agent/mailbox-step` | `:any`; `:some` | Justified on schema node: core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract. |
| `seon.cluster.message/render-inbox-ai` | `:seon.schema/value` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.cluster.message/render-inbox-html` | `:seon.schema/value` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.cluster.store/connection-object?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster.store/connection?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster.store/database-value?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster.store/file-lock?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster/cluster-name?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.cluster/mcp-get-value` | `:any` | Justified on schema node: The MCP retrieval boundary returns the arbitrary original evaluation value at the requested path. |
| `seon.cluster/mcp-valf` | `:any` | Justified on schema node: Clojure's prepl hands the projection arbitrary evaluation results, including live JVM objects and nil. |
| `seon.cluster/socket-server?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.db/apply-diff` | `:seon.schema/value` | Justified on schema node: A prior shown Clojure value may be scalar, nil or any collection; editscript paths determine the changed subvalues. |
| `seon.db/connection?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.db/database-value?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.db/datoms` | `[:* :seon.schema/value]` | Justified on schema node: Datahike index components include arbitrary attribute values. The function guard checks index, component count and argument-map exclusivity. |
| `seon.db/diff` | `[:* :seon.schema/value]` | Justified on schema node: Arguments are forwarded to the supplied Var; its program-graph contract and read plan own their shapes and arity. |
| `seon.db/q` | `[:* :seon.schema/value]` | Justified on schema node: Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query. |
| `seon.edit/valid-form-operation?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.effect/request!` | `:seon.schema/value`; `:seon.schema/value`; `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: The effect boundary reports malformed owners and requests as values; the resolved capability's own declared contract validates the heterogeneous request. |
| `seon.env/environment-state?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.env/environment?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.error.refusal/refusal` | `:seon.schema/value` | Justified on schema node: Throwable normalization accepts arbitrary source values; a non-Throwable has no exception data. |
| `seon.error/ai-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/diagnostic` |  | False positive in broad candidate query: nested value reference or literal, not a requested permissive input slot. |
| `seon.error/edit-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/elision-html` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/elision-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/error?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.error/index-refusal-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/instrumentation-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/mcp-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/refusal` | `:any` | Justified on schema node: Throwable normalization accepts arbitrary source values; a non-Throwable has no exception data. |
| `seon.error/refusal-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/render-ai` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/render-faults-html` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/render-html` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/time-limit-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.error/unclassified-prose` | `:seon.schema/value` | Justified on schema node: The total error render boundary receives a raw error, an acquired entity or a render unit and must describe unrecognized values without refusing them. |
| `seon.eval.drive/run-episode!` | `:seon.schema/value` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.eval.drive/run-sample!` | `:seon.schema/value` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.eval.drive/run-sample-json!` | `:seon.schema/value` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.fn.schema-shape/fingerprint` | `:seon.schema/value` | Justified on schema node: Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes. |
| `seon.fn.schema-shape/normalized-form` | `:seon.schema/value`; `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes. |
| `seon.fn.schema-shape/shape-row` | `:seon.schema/value`; `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes. |
| `seon.fn.schema-shape/typed-key-facts` | `:seon.schema/value` | Justified on schema node: Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes. |
| `seon.fs/content?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.fs/write-precondition?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.id/digest` | `:any` | Justified on schema node: Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil. |
| `seon.id/id` | `:any`; `:any` | Justified on schema node: Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil. |
| `seon.instrument-test/many-problem-output` | `:any` | Justified on schema node: The instrumentation regression deliberately returns arbitrary supplied values to exercise an invalid-output explanation. |
| `seon.instrument/armable` | `:any` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.instrument/instrumented` | `:any` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.oversight/proc-ping` | `:any` | Justified on schema node: core.async.flow permits arbitrary process identifiers; this projection preserves the supplied identifier, including for a missing reply. |
| `seon.print/compare-values` | `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.print/node-child?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.print/node?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.print/print-char?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.print/print-number?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.print/sink?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.print/value-at` | `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.registry-isolation-test/value` |  | False positive in broad candidate query: nested value reference or literal, not a requested permissive input slot. |
| `seon.render.data/at` | `:any`; `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.hiccup/->string` | `:any` | Justified on schema node: The total Hiccup serializer accepts arbitrary candidates; invalid candidates have the documented empty-string result. |
| `seon.render.hiccup/hiccup?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.render.hiccup/raw` | `:any` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.render.hiccup/raw?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.render.hiccup/shorthand` | `:any` | Justified on schema node: A foreign tag candidate may have any shape; invalid tags return a diagnostic instead of throwing. |
| `seon.render.transcript/render-history-ai` | `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.transcript/render-history-html` | `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.value/artifact-value` | `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.value/window` | `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.web/feed` | `:any`; `:any` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.render.web/inbound` | `:any` | Concrete contract replaces this permissive slot; see changes below. |
| `seon.render.web/mult?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.render.web/render-step` | `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render.web/server?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.render/call-with-walk-context` | `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render/project-node` | `:any` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render/unknown-ai` | `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.render/unknown-html` | `:seon.schema/value` | Justified on schema node: The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds. |
| `seon.schedule/schedule-step` | `:any`; `:some` | Justified on schema node: core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract. |
| `seon.schedule/valid-cron?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schedule/valid-timezone?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schema.admission/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.schema.datahike/decode-attribute-value` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/decode-attribute-value-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form->cardinality` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form->child-form` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form->datahike-value-type` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form->datahike-value-type-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form-children` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/form-head` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/resolve-datahike-form` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/resolve-datahike-form-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/resolve-malli-form` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.datahike/resolve-malli-form-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.form/attr-form-properties` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema.form/enum-members` | `:any`; `:any` | Justified on schema node: Malli enum members are arbitrary literal values; non-enum candidate forms return an empty vector. |
| `seon.schema.form/map-entries` | `:any`; `:any` | Justified on schema node: A raw Malli map entry contains a key, optional property map and schema form; this inspection preserves those heterogeneous values. |
| `seon.schema.form/map-shape?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schema.form/namespaced-properties` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema.form/nilable-value-schema?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schema.form/schema-properties` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema.form/widen-component-children` | `:any`; `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema.internal/assert-non-nilable-value-schema!` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.internal/map-identity-entry-key` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema.internal/map-required-attrs` | `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/byte-array?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schema/call-with-forms` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/call-with-projection` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/call-with-projection-state` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/call-with-registration-delta` | `:any`; `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/candidate-shapes` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/candidate-shapes-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/canonical-data-fingerprint` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/canonical-data-string` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/canonical-definition` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/compilable-form` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/direct-references` | `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/enum-members` | `:any` | Justified on schema node: Malli enum members are arbitrary literal values, not a homogeneous collection. |
| `seon.schema/explain-candidate-value` | `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/explain-shape` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/explain-shape-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/identity-only-projection` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/identity-only-projection-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/malli-form?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.schema/matching-shapes` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/matching-shapes-in` | `:seon.schema/value` | Justified on schema node: Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema. |
| `seon.schema/projection-cache-value` | `:seon.schema/value` | Justified on schema node: A projection cache key is ordinary heterogeneous data chosen by its caller; cached values are the supplied thunk's arbitrary result. |
| `seon.schema/register-all!` | `[:* :any]`; `:any` | Justified on schema node: Malli's repeated concatenation enforces complete keyword/definition pairs; there is no additional cross-pair relation. |
| `seon.schema/schema-definition` | `:any`; `:any` | Justified on schema node: Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged. |
| `seon.schema/valid-candidate-value?` | `:seon.schema/value`; `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.sci.admit/canonical-edn` | `:any` | Justified on schema node: The canonical EDN encoder accepts arbitrary Clojure data and represents unsupported host values through admission. |
| `seon.sci.admit/interrupt-fn?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.sci.admit/semantic-value` | `:any` | Justified on schema node: A print node represents an arbitrary original Clojure value; semantic decoding preserves its scalar or collection shape. |
| `seon.sci.eval/bind-result!` | `:any` | Justified on schema node: SCI result bindings retain the actual arbitrary result object, including nil, without serialization. |
| `seon.sci.eval/ctx?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.sci.eval/projection-state?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.sci.kernel/adopt-arm` | `:any` | Justified on schema node: The caller's body determines its return type; adopting the kernel arm preserves that exact result. |
| `seon.sci.kernel/arm?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.sci.kernel/failure-value` | `:any` | Justified on schema node: A kernel failure can carry any thrown or returned value; normalization must preserve evidence of an unrecognized failure. |
| `seon.sci.kernel/interrupted?` | `:any` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.shell/output?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.shell/stdin?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.test.accretion/data-contract!` | `:seon.schema/value` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.test.accretion/generatable?` | `:any`; `:any` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.test.cache/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.test.fast/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.test.runner/-main` | `[:* :string]` | Justified on schema node: Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics. |
| `seon.test.runner/var-reference?` | `:seon.schema/value` | Justified on schema node: A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape. |
| `seon.turn/append-generated-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/close-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/disposition` | `:any` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/open-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/open-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/plan-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/plan-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-settle-batch-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-settle-batch-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-settle-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-settle-tx` | `:some`; `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-start-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/receipt-start-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/recover-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/recover-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/refresh-call` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/refresh-tx` | `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/step` | `:any`; `:some` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |
| `seon.turn/unbound-value?` | `:any` | REQUIRED IN run7-wave OWNED PATH: correct or justify these positions; this lane does not edit them. |

## Resource inventory

Pending final graph/resource reconciliation. No stored attribute is narrowed in place. Read-only installed-schema query confirmed that `:seon.reconcile/adopt-identities`, `:seon.render.walk/lookup`, `:seon.schema/arguments`, and `:seon.schema/kvs` are schema declarations, not installed database attributes in default.

## Checker checkpoint

- Initial canonical fast run: 73 tests, 425 assertions, one expected inventory failure, zero errors.
- Isolated checker run after correcting a local annotation syntax error: 73 tests, 429 assertions, one expected inventory failure, zero errors. The failure reports existing permissive contracts because only the checker paths were overlaid.
- The first attempted annotation used a property-bearing schema alias that Malli rejected. Replaced it with the alias's exact underlying `:any` shape plus local exemption properties; no semantics were widened.
- A valid transparent `[:schema properties :seon.schema/value]` exposed a separate schema-render coherence limitation (one dereference); this audit uses the equivalent direct type. An issue will record the owning seam.
- Final checker fast run: 73 tests, 642 assertions, 217 inventory assertions failed, zero errors. The isolated gate reproduced the same 217 unjustified positions: 73 tests, 646 assertions, zero errors. No non-inventory test failed. This is the inventory-first checkpoint, before overlaying the contract repairs.
- `bin/test --paths src/seon/schema/internal.cljc test/seon/schema_audit_test.clj --platform`: 84 tests, 505 assertions, zero failures or errors. The last checker edit adds recognition of Malli's existing `:gen/gen` property; schema syntax and alias-nilability regressions pass.

## Concrete contracts and refusal grammar

Pending final verification.
