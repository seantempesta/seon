---
type: research
status: landed
created: 2026-09-17
tags: [contracts, program-graph, schema]
---

# Contract findings as a program-graph query

## Result

`seon.fn/contract-findings` derives incomplete contracts from one immutable
database value and returns findings ordered by descending current function
caller count. Its declared contract is:

```clojure
[:=> [:cat :seon.db/database-value]
 [:or :seon.fn.contract/report :seon.error/value]]
```

Each report member carries `:seon.fn/sym`, a contract position (contract,
output, or `[input index]`), the offending form, one closed finding keyword,
and `:seon.fn.contract/caller-count`. Missing specs are findings for every
function row, private included; absence is not interpreted as health.

The schema-placement hook refused the assignment's requested
`resources/seon/schemas/seon.fn.edn` location because declarations in the
`:seon.fn.contract/*` namespace must live in
`resources/seon/schemas/seon.fn.contract.edn`. The canonical file owns the
same requested enum and report shape without weakening or renaming them.

## Dependency ledger

- `seon.schema.internal/permissive-positions`
  (`src/seon/schema/internal.cljc:22`) remains the one classifier for
  undefined, bare-value, value-tail, and unguarded-tail positions. The query
  excludes its explicitly justified polymorphic boundaries.
- `seon.schema.form/database-attributes`
  (`src/seon/schema/form.cljc:63`) derives the registered attribute set from
  the immutable projection; no attribute roster is maintained.
- `seon.schema.datahike/storable-attribute-in?`
  (`src/seon/schema/datahike.clj:299`) asks the bridge whether each referenced
  registered attribute can become a Datahike attribute.
- `:seon.fn/calls` is an explicitly indexed symbol value
  (`resources/seon/schemas/seon.fn.edn:28`); the query counts current function
  callers from those facts and sorts descending.
- Datahike reverse traversal over an indexed ordinary symbol edge uses AVET,
  the same index shape as an indexed ref
  (`reference-code/datahike/src/datahike/db/search.cljc:140-157`); Datahike
  has no VAET (`reference-code/datahike/src/datahike/db.cljc:310`).

## Canonical fixture census

Measured once over `seon.test-support/with-database` after the regression was
green. The fixture population produced **3,660** findings:

| Finding kind | Count |
|---|---:|
| `:seon.fn.contract.finding/missing-spec` | 3,225 |
| `:seon.fn.contract.finding/bare-map` | 304 |
| `:seon.fn.contract.finding/maybe` | 123 |
| `:seon.fn.contract.finding/unguarded-variadic` | 8 |
| `:seon.fn.contract.finding/any` | 0 |
| `:seon.fn.contract.finding/some` | 0 |
| `:seon.fn.contract.finding/bare-value` | 0 |
| `:seon.fn.contract.finding/unstorable-attribute` | 0 |

The zero `:any`/`:some`/bare-value counts mean the current occurrences carry
the explicit polymorphic-boundary exemption accepted by the existing checker;
the synthetic regression proves an unjustified `:any` is reported.

## Top 20 by current caller count

| Rank | Function | Callers | Position | Kind | Form |
|---:|---|---:|---|---|---|
| 1 | `seon.db/pull` | 210 | output | bare-map | `:map` |
| 2 | `seon.db/transact!` | 97 | output | bare-map | `:map` |
| 3 | `seon.cluster.message/render-ai` | 42 | output | maybe | `[:maybe :seon.render/source]` |
| 4 | `seon.repl/render-ai` | 42 | output | maybe | `[:maybe :string]` |
| 5 | `seon.cluster.agent/render-creation-ai` | 40 | output | maybe | `[:maybe :string]` |
| 6 | `seon.cluster.agent/render-situation-ai` | 40 | output | maybe | `[:maybe :string]` |
| 7 | `seon.cluster/render-ai` | 40 | output | maybe | `[:maybe :seon.render/source]` |
| 8 | `seon.config/render-ai` | 40 | output | maybe | `[:maybe :string]` |
| 9 | `seon.context/capture-ai` | 40 | output | maybe | `[:maybe :string]` |
| 10 | `seon.effect/render-ai` | 40 | output | maybe | `[:maybe :string]` |
| 11 | `seon.error/diagnostic` | 40 | input 0 | bare-map | `:map` |
| 12 | `seon.error/render-faults-ai` | 40 | output | maybe | `[:maybe :seon.render/source]` |
| 13 | `seon.render.ns/render-ai` | 40 | output | maybe | `[:maybe :seon.render/source]` |
| 14 | `seon.schema.form/attr-form-properties` | 39 | output | bare-map | `:map` |
| 15 | `seon.schema.form/attr-form-properties` | 39 | output | maybe | `[:maybe :map]` |
| 16 | `seon.schema/projection-from-database` | 39 | input 0 | bare-map | `:map` |
| 17 | `seon.test-support/with-database` | 39 | contract | missing-spec | `:seon.fn.contract/missing` |
| 18 | `seon.schema/call-with-projection` | 37 | input 0 | bare-map | `:map` |
| 19 | `seon.cluster.message/render-html` | 34 | output | maybe | `[:maybe :seon.render/hiccup]` |
| 20 | `seon.cluster.agent/render-identity-html` | 33 | output | maybe | `[:maybe :seon.render/hiccup]` |

## Regression and verification boundary

`contract-findings-ranks-each-incomplete-function-contract` uses the canonical
database fixture and `program-fn-row`. It installs an `:any` input, bare
`:map` output, missing spec, complete contract, and one current caller. The
report returns exactly the three expected findings, ranks the called function
first, and emits none for the complete contract.

The prescribed `--paths` run could not launch: its published graph was three
commits behind HEAD and recursively requested protected foreign paths,
including `src/seon/db.clj`; the orchestrator's
`tmp/orchestrator/prepare-head-base-1.log` ended `exit=1`. Verification
therefore used the assignment's isolated HEAD-worktree fallback with only this
lane's diff and the populated `reference-code` link:

```text
Ran 61 tests containing 422 assertions.
0 failures, 0 errors.
```

This is a fast iteration tally. The orchestrator still owes the cold
path-limited gate and platform proof.

The supported Seon runtime/eval MCP tools were absent from this lane, so no
live query against `default` was possible. The existing untracked issue
`docs/seon/issues/the-supported-mcp-runtime-tools-were-absent-from-a-bounded-lane.md`
already records that shared tool failure; this lane did not duplicate or edit
the foreign note.
