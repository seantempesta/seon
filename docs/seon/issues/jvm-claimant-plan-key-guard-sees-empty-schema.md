---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, schema]
---

# Preserve plan request definitions in JVM claimant schema projection

## Problem

A live JVM-claimant eval called `my.plan/plan!` with the registered
`:my.plan/plan-request` keys `:my.plan/title`, `:my.plan/goal`,
`:my.plan/pace`, and `:my.plan/children`. The call reached the toolkit
function but its schema-derived unknown-key guard returned:

```text
plan!: unknown key :my.plan/title Accepted my.plan keys: .

```

The empty accepted-key list means the claimant's active schema projection did
not supply the request definition to `my.plan.internal/schema-map-keys`.
Consequently a valid durable plan cannot be created through the maintained
`my.plan/plan!` mechanism.

## Evidence

The default-cluster drive7 live drive on 2026-07-24 used agent
`slick-dryers-brake`.

- Run `ku09qo4q6u57` opened after a prior parser-visible fault had closed and
  released cleanly.
- Turn `httr4snax2fr` reached the full claimant cursor through `:evaling`,
  `:evaled`, and `:published`, then closed `:done`.
- Eval receipt `fh304565fe3p` is terminal `:done` with
  `:seon.eval/ok? true`. Its exact source is a valid
  `(my.plan/plan! {:my.plan/title ... :my.plan/goal ...
  :my.plan/pace :multi-session :my.plan/children [...]})` call.
- The persisted result is
  `{:my.plan/ok? false, :my.plan/error "plan!: unknown key
  :my.plan/title Accepted my.plan keys: ."}`.
- The next DeepSeek attempt succeeded, but turn `l0l39nnule3j` faulted
  visibly before eval while trying to inspect the missing schema definition.
  The run closed `:error` and released custody; the cluster remained ready.

The defining source path is:

- `src/my/plan.cljc` registers `:my.plan/plan-request` and calls
  `my.plan.internal/check-plan-keys` before creating a plan.
- `src/my/plan/internal.cljc` derives accepted keys by walking
  `(seon.schema/schema-definition :my.plan/plan-request)`.
- `test/my/plan_test.cljs` proves the same derivation in the CLJS test
  projection, but there is no equivalent live JVM-claimant proof.

The exact live datoms and reply/eval evidence are retained in
`tmp/orchestrator/drive7-gate.log`.

## Owner

The schema acquisition and binding-table owner for the JVM claimant must make
the same registered request definition visible to
`seon.schema/schema-definition` that the CLJS runtime and tests observe.
`my.plan` must continue deriving its accepted key set from that one schema;
do not add a hand-maintained key list or a claimant-only bypass.

## Acceptance

- A focused JVM claimant test calls `my.plan.internal/schema-map-keys` for
  `:my.plan/plan-request` and obtains the registered keys including
  `:my.plan/title`, `:my.plan/goal`, `:my.plan/pace`, and
  `:my.plan/children`.
- A host-tier `my.plan/plan!` call with nested labelled children returns
  `{:my.plan/ok? true ...}` and persists the root plus every child.
- A rebuilt default-cluster DeepSeek drive creates that persistent plan,
  advances its steps across later turns, writes and later reads schema-backed
  facts, renders the final canvas, and closes `:completed`.
