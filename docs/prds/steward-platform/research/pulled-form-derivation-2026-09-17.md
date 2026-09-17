---
type: research
status: landed
created: 2026-09-17
tags: [schema, datahike, pull, contracts]
---

# Pull-result forms derive from entity schemas and selectors

## Result

`seon.schema/pulled-form-in` now derives the Malli form of one Datahike
pull result from the supplied immutable projection, entity schema key, and
selector:

```clojure
[:=>
 [:cat :seon.schema/projection
  :seon.schema/registry-key
  :seon.schema/pull-selector]
 :seon.schema/pulled-form-result]
```

The result is either an EDN-readable Malli form or a flat
`:seon.error/value`. `seon.schema/projection-with-pulled-form-in` registers
the form through `projection-with-schema`; `pulled-schema-key` derives its
registry identity as `seon.id/id` of `[schema-key selector]`; and
`projection-cache-value` retains that extended projection on the supplied
projection's own cache. Repeating the same derivation returns the identical
extended projection.

The selector grammar is declared once as `:seon.schema/pull-selector`. Its
predicate delegates validity to Datahike's `compile-pull-plan`, and the
derivation consumes Datahike's parsed `PullSpec` rather than maintaining a
second parser (`reference-code/datahike/src/datahike/pull_api.cljc:52-73`).

## Dependency ledger and selector coverage

- Wildcards add `:db/id` and every attribute from the entity schema. Explicit
  `:db/id` also adds it; an ordinary selector does not
  (`pull_api.cljc:372-377`, `:450-455`).
- A bare ref produces `[:map [:db/id :int]
  [:db/ident {:optional true} :keyword]]`, matching `db-ident-and-id`
  (`pull_api.cljc:298-302`, `:353-357`).
- A sub-selector recursively derives the target entity's form. Component
  targets come from `:seon.db/component-schema`; peer targets come from the
  selected identity attribute's `:seon.program/row-schema`; reverse targets
  come from the projection's entity shape index
  (`pull_api.cljc:335-339`).
- A bare component ref derives the declared component target under wildcard,
  because Datahike expands it without a sub-selector
  (`pull_api.cljc:345-351`). A component cycle returns the same typed refusal
  used for an unsupported recursion shape rather than under-admitting a full
  nested value.
- Cardinality comes from the schema bridge's existing
  `form->cardinality-in` and `form->child-form-in`. Cardinality-many pull
  results are vectors (`pull_api.cljc:353-357`). The form records Datahike's
  default maximum of 1,000 members, an explicit numeric `:limit`, or no
  maximum for `:limit nil` (`pull_api.cljc:315`, `:323`).
- `:as` changes the output map key (`pull_api.cljc:316`). `:default` makes
  the key required and unions the declared value form with the exact default
  literal (`pull_api.cljc:361-365`). Both have actual-pull fixture coverage.
- Reverse attributes use the forward attribute declaration and are
  single-valued exactly when that forward ref is a component; otherwise the
  output is a vector (`pull_api.cljc:328-329`).
- Recursion is deliberately refused. Datahike may return `{:db/id n}` for an
  already-seen entity (`pull_api.cljc:238-243`), while the same selector may
  return a full nested map elsewhere. The refusal is
  `:seon.schema/unsupported-pull-selector` and names the selector element in
  `:seon.error/diagnostic-offending`. Dynamic, undeclared, unstorable, and
  reverse non-ref attributes use the same evidence-complete refusal.

## Regression and verification

`seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector`
uses `seon.test-support/with-database` and real populated `:seon.test/test`
and `:seon.ns/ns` rows. It validates actual `seon.db/pull` values for a
scalar selector, `:as`, `:default`, an expanded peer ref, wildcard over an
entity schema containing the cardinality-many component ref
`:seon.test/failures`, and a reverse ref. It also proves a wrong scalar type
fails, the derived registry entry exists, projection-cache reuse is by
identity, and recursion returns the typed refusal.

The requested isolated command:

```text
bin/test-fast --paths src/seon/schema.clj resources/seon/schemas/seon.schema.edn test/seon/schema_test.clj -- seon.schema-test
```

initially refused before launching tests because overlay admission required
the then-held foreign caller files `src/seon/db.clj` and
`test/seon/db_test.clj`. After lane `transaction-report-schema` landed, the
exact command above was green:

```text
Ran 28 tests containing 469 assertions.
0 failures, 0 errors.
```

This is fast iteration evidence. The orchestrator still owes the cold
path-limited gate and platform proof. The supported Seon runtime/evaluation
MCP tools were absent, so there is no live JVM/SCI evaluation proof; the
existing issue `docs/seon/issues/seon-mcp-tools-absent-in-codex-lane-again.md`
records that shared failure. No default-cluster operation was performed.

## Exact database follow-up

Lane `transaction-report-schema` has released `src/seon/db.clj`,
`resources/seon/schemas/seon.db.edn`, and `test/seon/db_test.clj`. The
follow-up lane must:

1. Replace `seon.db/pull`'s three bare `:map` output arms with a
   selector-specific derived contract. The pull request must carry the entity
   schema key alongside the same selector handed to Datahike; derive once via
   `projection-with-pulled-form-in`, name `pulled-schema-key` in the reader
   contract, and validate the returned value before it crosses `pull-call`.
   Apply the same construction element-wise to `pull-many`.
2. Fixed-selector readers change their outputs from stored-entity or
   hand-written pulled mirrors to their registered derived keys:
   `seon.cluster.message/read`, `seon.cluster.message/send!`,
   `my.message/read`, `my.message/send!`, `my.message/reply!`,
   `seon.note/add!`, `seon.note/forget!`, `my.note/notes`, `my.note/add!`,
   `my.note/forget!`, `seon.test/run-owned`, `seon.test/run`, and
   `seon.render.transcript/agent-history`.
3. `seon.eval/of-agent` derives its final selector once (including forced
   identity, turn, and ordinal attributes), hands that identical value to
   both `seon.db/pull` and `pulled-form-in`, and changes its output element
   from `:seon.eval/entity` plus a hand-added map constraint to that derived
   key plus its reader-added `:t` entry.
4. Delete the now-redundant hand-written pulled shapes and per-attribute
   widenings only in the same follow-up that moves their callers:
   `:seon.message/pulled`, `:seon.test.failure/value`,
   `:seon.render.transcript/pulled-transaction`, and the remaining inline
   `[:map [:db/id :int]]` reader contracts. The stored entity schemas remain
   transaction shapes only.

The follow-up must extend `seon.db-test` with each public pull arity and
`pull-many`, including nil results and flat dependency errors; those outcomes
remain outside the derived map form.
