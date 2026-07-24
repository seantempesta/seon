---
type: issue
status: resolved
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

## Resolution

The corpus and paged committed acquisition were complete; the JVM claimant's
fact-first namespace loader deliberately replays stored definitions without
executing top-level `schema/register!` forms. The claimant wrapper for
`seon.schema/schema-definition` nevertheless consulted the JVM process-local
candidate registry, which therefore had no `my.plan` registrations.

Commit `0ae0fda9e` binds schema lookup to the writer session's retained,
immutable committed projection. The mechanism is namespace-independent: its
regression proves the `:my.plan/plan-request` key set and an unrelated
`:my.kb/claim` definition through the same live SCI registry path.

The first isolated drive then reached plan persistence and exposed a second
stale host-only allocation implementation. It treated the allocation builder's
transaction request map as transaction data. Commit `3fd9137f6` deletes that
duplicate and binds the claimant wrapper to the portable
`seon.db.id/allocate!` contract. The real SCI-wrapper/serialized-writer
regression passes 1 test / 19 assertions.

On the rebuilt isolated `planschema` cluster, JVM claimant
`99081@2026-07-24T10:21:35.424583Z` evaluated the nested `plan!` call
successfully:

```clojure
{:my.plan/ok? true
 :my.plan/root "mft542256r45"
 :my.plan/ids
 {:root "mft542256r45"
  "schema" "q2oi8xrcv5iq"
  "persist" "n2r7qiwi500m"
  "read" "b770ervohnnj"}}
```

The same run committed the three `:my.planschema.memory/*` schemas, wrote the
fact, and read back `"CLAIMANT_MEMORY_ALIVE"`. Full build, claim, eval,
transaction, and clean-shutdown evidence is in
`tmp/orchestrator/planschema-gate.log`.

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
- A rebuilt isolated-cluster DeepSeek drive creates the persistent nested plan
  and writes and later reads a schema-backed fact through the JVM claimant.
- The default-cluster cross-turn memory re-drive remains the program's final
  integration gate, not an unresolved schema-acquisition defect.
