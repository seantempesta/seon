---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, malli, boot]
---

# An unregistered `:ifn` schema breaks every source publication

## Problem

`src/seon/flow.clj:100` declares `[:map-of :qualified-keyword :ifn]` inside the
`:malli/schema` of the new `seon.flow/start-graph!`. `:ifn` is not a registered
Malli schema — Malli's default registry has `fn?` and `ifn?` predicates and a
`:fn` schema, but no `:ifn` keyword — so `malli.core/schema` fails
`::invalid-schema` the moment `seon.schema/build-projection` (`schema.clj:1453`)
builds the function projection for that Var.

Because projection building runs during source publication, the failure is not
local to Flow: **any** process that publishes current source dies at boot. The
class is "an unvalidated schema keyword reaches a whole-tree projection pass",
and the loud-failure question is why a nonexistent schema name is only caught at
publication time rather than where it is declared.

## Evidence

- `src/seon/flow.clj:100` (uncommitted working-tree edit at 2026-08-11 22:45
  local) — `[::joins {:optional true} [:map-of :qualified-keyword :ifn]]`.
- `rg -n ":ifn" reference-code/malli/src/malli/core.cljc` — no hits; `:ifn` is
  absent from Malli's registry.
- `rg -n ":ifn" src/ resources/` — one hit, the line above; nothing registers it.
- Three concurrent ablation drives (FULL, HALF, QUARTER — the paid
  minimum-context experiment of
  [the ablation plan](../../prds/sci-execution-runtime/research/minimum-context-ablation-plan-2026-08-11.md))
  all aborted at `cluster/refresh-source!` with
  `Execution error (ExceptionInfo) at malli.core/-exception (core.cljc:203). :malli.core/invalid-schema`,
  stack `seon.schema$build_projection$fn__74320 ... schema.clj 1453`. Logs:
  `tmp/ablation/full-run.log`, `tmp/ablation/half-run.log`,
  `tmp/ablation/quarter-run.log`. The FLOOR drive, launched minutes earlier
  against the pre-edit tree, published and ran normally.
- Collateral: each aborted drive had already created its root, so
  `tmp/ablation/drive-roots/{full,half,quarter}-01/clusters/store` now exists and
  `ablation.run-variant/-main` refuses those roots as non-fresh. Reruns need new
  root names.

## Instance status

The instance is fixed: `e019ffbd8` ("Make graph join ordering structural")
committed `[:map-of :qualified-keyword [:fn clojure.core/ifn?]]` at 22:54 local,
about nine minutes after the three drives died. The three roots those drives had
already created (`{full,half,quarter}-01`) remain unusable and the drives were
relaunched into `-02` roots. **The class is still open** — see acceptance.

## Acceptance

- `:ifn` is replaced by a registered schema (`ifn?`, or a declared
  `:seon.flow/...` join-function schema under `resources/seon/schemas/`), and
  `cluster/refresh-source!` completes on a clean tree.
- One regression proves the class dead: an unregistered schema keyword in a
  `:malli/schema` fails at the declaration's own owner with a message naming the
  Var and the unknown schema, instead of aborting a whole-tree projection pass
  with a bare `::invalid-schema`.

## Owner

The lane editing `src/seon/flow.clj` (graph-construction / fanout-join work,
`git status` also shows `src/seon/cluster.clj`, `src/seon/cluster/agent.clj`,
`test/seon/flow_test.clj`).

## Closure — 2026-08-13

No `:ifn` remains anywhere in `src/seon/flow.clj`; the cited declaration was rewritten and publication succeeds. The fresh inline `[:fn clojure.core/ifn?]` predicate at `seon.flow/start-graph!` is a new instance of the anonymous-contract class (N6), recorded in [the 2026-08-13 triage](../../prds/sci-execution-runtime/research/issue-triage-2026-08-13.md).
