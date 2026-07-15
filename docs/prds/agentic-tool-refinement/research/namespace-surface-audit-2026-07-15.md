---
type: research
status: active
tags: [research, agent, schema, capability]
---

# Namespace surface audit — 2026-07-15

## Dependency ledger

- ClojureScript `1.12.145` analyzer metadata is read through
  `src/seon/analyzer_info.cljs`; `var-projection` owns the durable `:seon.fn`
  projection consumed by boot indexing and the eval tee.
- Malli `0.20.0` function metadata and schema forms are persisted through
  `src/seon/schema.cljc`, `src/seon/indexing.clj`, and `src/seon/eval.cljs`.
- Datahike commit `6f90b339768b1a02066dce3b6fcc93a200758fcc`
  supplies the immutable database value. Namespace rendering reads only
  `:seon.ns`, `:seon.fn`, and `:seon.schema` facts from that value.
- `src/seon/agent/ctx/namespaces.cljs` owns required compact cards;
  `src/seon/agent/ctx.cljs` owns database-derived transitive referenced-schema
  closure. Focused coverage is `test/seon/agent/ctx/namespaces_test.cljs`.
- Live evidence comes from `seon.agent.debug/ctx-preview` for ACME agent
  `metal-hairs-lose`, the same projection used by its successful BFCL turn.

## Exact live projection

The namespace block is 22,106 estimated tokens across seventeen namespaces.

| Namespace | Tokens | Functions | Function tokens | Schemas | Schema tokens |
|---|---:|---:|---:|---:|---:|
| `seon.db` | 3,966 | 36 | 1,740 | 85 | 2,214 |
| `seon.agent.fs` | 2,732 | 13 | 502 | 70 | 2,215 |
| `my.plan` | 2,309 | 14 | 485 | 57 | 1,811 |
| `seon.agent.shell` | 2,085 | 8 | 336 | 48 | 1,733 |
| `my.canvas` | 1,695 | 11 | 648 | 37 | 1,034 |
| `seon.agent.search` | 1,444 | 2 | 89 | 38 | 1,338 |
| `seon.agent.web` | 1,377 | 3 | 122 | 43 | 1,240 |
| `seon.schema` | 1,151 | 24 | 1,009 | 11 | 128 |

Across the block, 508 schema lines reduce to 483 unique exact lines. One shared
copy of duplicates would remove about 988 tokens. That is useful but cannot
explain most of the block.

## Falsifiable defect

Compact cards equate Clojure public visibility with agent capability.
`seon.schema` presents `clear-all!`, projection activation, registry relinking,
snapshot, restore, and dependency-analysis functions beside the intended
schema-definition function. `seon.db` presents boot assertions, provenance
initialization, listener lifecycle, ambient scope helpers, schema compilation,
and raw lazy entities beside query, pull, history, coordinates, and transact.

These functions remain public because first-party namespaces call them through
the canonical facade. A renderer symbol exclusion set would create a second
registry and make every new function unsafe by default. Docstring prefixes
such as `INTERNAL:` are prose, not policy.

## Candidate mechanism

Persist positive agent-facing metadata as an optional `:seon.fn` fact projected
from colocated analyzer metadata. Compact cards derive function rows from that
fact, then derive transitive schema closure only from selected contracts.
Current namespace source remains full regardless of the marker. Domain/entity
schemas use a positive structural inclusion rule so useful data models do not
disappear merely because no function references them.

The metadata spelling remains unsettled until analyzer, boot index, eval tee,
and Clojure metadata behavior are probed together. The design uses one fact and
one derivation; it adds no catalog, blocklist, duplicated renderer, or
benchmark-specific policy.

## Acceptance

- The same database value renders byte-identical compact cards.
- A marked public function appears with complete contracts; an unmarked public
  implementation function remains in the program graph but is absent from
  required compact cards and function menus.
- Current namespace full source is unchanged.
- Visible-function schema closure remains complete and deterministic; positive
  domain/entity schemas remain discoverable.
- Live ACME context no longer advertises registry reset, boot, provenance,
  listener, or ambient-scope implementation functions.
- Focused namespace/program-graph tests pass, then frozen real-tool Inspect
  samples compare selection, composition, and outcome—not exact prose.
