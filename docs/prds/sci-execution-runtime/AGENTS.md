---
type: reference
status: active
tags: [prd, agent, architecture]
---

# SCI execution-runtime runbook

This file localizes the root `AGENTS.md` for the active runtime program. The
deleted pod/host/U4–U12 implementation runbook is Git archaeology, not current
instruction.

## Establish the current boundary

Read in this order before designing or editing:

1. `docs/TRANSFER_PROMPT.md` for orientation;
2. root `AGENTS.md` (or its `CLAUDE.md` compatibility link) for binding law;
3. the top WORKING EDGE in `plan/unsettled.md` for current evidence and state;
4. `plan/README.md` for the numbered owner rulings and program ordering;
5. `docs/seon/issues/index.md` for the generated ranked open queue; and
6. the relevant current target under `docs/seon/architecture/`.

`roadmap.md` is only a compatibility pointer to `plan/README.md`. The dated
blocks below the current WORKING EDGE and the older sections of the large plan
are archaeology. Open only the history linked by the chosen boundary; never
infer current state from a historical green gate or an old executable brief.

Derive live state before scheduling: `git status --short`, `git log`,
`bin/seon status`, the selected issue note, and the smallest falsifier. The
tree and running process outrank prose.

## Current program-graph contract

Build indexing statically analyzes the JVM projection of first-party `src/`
and `test/` through clj-kondo, then slices exact source from analyzer locations.
It records namespace rows plus every function/test definition, including
private and uncontracted helpers, without evaluating application forms.
Dependency caches improve resolution but never publish external definitions.
Global schemas come from the separately admitted family declarations under
`resources/seon/schemas/` and are never namespace-owned. The source owners are
`src/seon/fn/analyzer.clj` and `src/seon/fn.clj`; clj-kondo source is pinned at
`reference-code/clj-kondo`.

Runtime eval uses the same canonical program rows but a deliberately narrower
publication policy. A durable agent-authored function requires a complete
Malli input/output contract; schemas and tests use their own admitted row
shapes. Scratch expressions and process-local definitions remain outside the
database program graph. Schema changes stage in an isolated registration delta
and materialize only from the terminal transaction report's `db-after`. The
runtime owner is `src/seon/sci/eval.clj`; canonical row and exact-replacement
semantics live in `src/seon/program.cljc`.

Schemas are global, identified by `:seon.schema/key`, never owned by the
namespace that happened to register them. Replacement or removal refuses while
a schema/function contract depends on the affected key or while any directly
or transitively affected Datahike attribute has current data. After those
dependencies and current datoms are retracted, the change may commit.
Historical simulation combines old temporal datoms with the historical global
schema row at the same basis; Datahike's physical schema map is current-only,
and `:seon.db/no-history? true` intentionally discards old values.

The registration boundary graduated on 2026-07-30 at 606 tests / 2,680
assertions / 0 failures / 0 errors. Do not reopen it from older prose. Start
with `research/registration-lifecycle-postfix-audit-2026-07-30.md` and
`research/schema-removal-history-probe-2026-07-30.md` if a new falsifier
touches that contract.

## Live update and initialization are different operations

Re-evaluating a Var changes loaded behavior in that JVM. It does not reconcile
`:seon.fn`, `:seon.ns`, `:seon.schema`, or `:seon.test` database facts.

- The edit hook incrementally publishes safe first-party changes to the one
  `:current-src` branch; structural changes select a complete scratch rebuild.
- `bin/seon init` requests a complete `current-src` publication explicitly.
- Existing clusters remain sovereign and are never source-synchronized.
- `bin/seon init CLUSTER --force` destroys that branch and reforks it from the
  published commit.

A proof after a file edit must name whether it exercised only the loaded Var or
a cluster forked from the newly published commit. Initialization reloads
the static analyzer/index owner inside a live store-owning JVM before building;
it never reloads application namespaces or mutates existing cluster facts.

## Work and proof

Begin with a dependency ledger: exact maintained dependency revision,
`reference-code/` paths, first-party call sites/tests, and the shortest
falsifier. Read actual dependency source before naming or designing an
interface. Use the matching repository skills, including
`data-oriented-clojure` before Clojure, `data-modeling` + `datahike` for schema
lifecycle, `clojure-testing` for recurring proof, and `repl` for live eval.

Use one existing mechanism and delete superseded paths. A test proves a failure
class only when the recurring runner discovers it. Index/acquisition/process
changes also require a reset-boundary or reopen live proof; fixture loading is
not boot.

Codex orchestrators use native collaboration tools. Claude orchestrators use
`bin/codex-agent`. Subagents execute their assigned task and do not delegate.
Every lane owns explicit paths and returns evidence that the top-level agent
reviews against source. Shared-tree changes use path-limited commits and never
overwrite unrelated work.
