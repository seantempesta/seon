---
type: research
status: active
tags: [research, runtime, operator, wave/program-graph-indexing]
---

# In-place development source adoption

The owner ruled an explicitly selected development cluster follows source
edits without reforking or discarding agent facts. The hook configuration now
selects `tmp/juniper-context-live`, cluster `juniper-context`. Other clusters
retain their existing publication behavior. The development JVM must host only
that cluster because Clojure Vars are process-wide.

## Dependency ledger

- Datahike serializes transaction functions at its writer:
  `reference-code/datahike/src/datahike/db/transaction.cljc`.
  `seon.fn/reconcile-tx` derives replacement operations there.
- Program identity, owned attributes and component replacement already belong
  to `src/seon/program.cljc`. Reconciliation reuses `exact-replacement-tx`;
  identity attributes survive deletion.
- `reference-code/datahike/src/datahike/versioning.cljc` supplies immutable
  `commit-as-db`. Its `merge!` only records parent relationships; it does not
  compute an application merge. Source adoption therefore reads the sealed
  published rows and uses the existing index transaction compiler.
- `src/seon/sci/eval.clj` owns acquisition and committed-row installation.
  Clojure `require :reload` updates host Vars, and SCI acquisition publishes
  those Vars through the existing context. No evaluator or result cache is
  introduced.
- `reference-code/sci/src/sci/core.cljc` exposes `namespace-state`. An indexed
  test can lack an installed namespace; deletion installation checks this
  actual state before asking SCI to unmap its Var.

## Implementation

`bin/seon init --dev NAME [--changed PATH...]` publishes through the existing
source owner, then adopts on the live connection. The hook supplies this
explicit target. Safe scalar changes retain their existing fast projection;
structural changes reuse the compiled rows and schema projection from the
immutable published database, rather than compiling them again from files.

The existing cluster identity records `:seon.source/commit-id` only after
database reconciliation, loaded definitions, and SCI acquisition finish.
Reload failures leave the previous adoption marker. Cleanup instrumentation
errors are suppressed under an original failure rather than replacing it.
An edit during adoption refuses completion and leaves the next hook event to
converge the newer source. The live web server is not restarted by adoption.

Current source absence plus historical definition facts identifies true
deleted functions. This matters on retry: their database retraction can have
committed before loaded installation failed. Merely comparing the current
database to the desired rows loses that installation work. Host-only Vars
that never had a definition remain intact.

## Live observations

Before the first development adoption, the isolated cluster had two agents,
two authored plan items, eleven messages, fifty-two contributions and 1,536
runs. After database reconciliation, every captured identity remained.
The existing watched UI was creating source runs every one or two seconds;
the root stopped its web server. Two later observations agreed on 1,610 runs.
That separate source-reuse defect remains under investigation.

The first adoption's cleanup masked its primary failure with missing
`:seon.context/append-request` in the old instrumentation projection. The
operator now preserves primary failures. Subsequent exact evidence showed a
deleted indexed test whose namespace was absent from SCI; the deletion step
now checks the dependency's actual namespace state. Verification must include
the loaded deleted Var as well as its absent indexed source.

The regression `seon.source-reconciliation-test` covers exact replacement,
component deletion, identity retention, new schema/test rows, unrelated agent
facts, repeat convergence, and writer-time ordering. The first run had one
incorrect assertion omitting a retained identity attribute (85 assertions,
one failure); that expectation is corrected. Later gate and automatic-hook
proof results follow below. UI paint remains unclaimed
while the web server is deliberately stopped.

At the next live observation, published and adopted source commit IDs both
equaled `6a9dfa36-76b0-5a50-98bc-1ae66926c19c`. The indexed and loaded `whoami`
arglists both reflected `([] [request])`; the deleted `render-identity-text`
Var was absent from both host Clojure and SCI. Normal MCP SCI evaluation of
`(:doc (meta #'seon.cluster.source/database))` returned the exact edited
sentence, "Read the exact published source commit without opening its branch."
No manual reload or refork was used for that edit.

Every baseline identity remained: two agents, eleven messages, both plan
items, all fifty-two original contributions, and all 1,536 original runs.
The current counts were fifty-three contributions and 1,611 runs after the
root's one controlled source-render probe. Root continues the browser proof.

The combined focused gate `run.n6x9Oo` finished with 35 tests, 306 assertions,
two failures and zero errors. Both failures belong to the existing
`live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`
case: `seon.schema.datahike/resolve-malli-form-in` rejected its regex Malli
fixture during runtime instrumentation. The source reconciliation regression
passed. The newer historical-deletion assertion still requires its final
focused gate; this combined run preceded that assertion. That final
source-only gate subsequently passed: `run.f5oKiZ`, one test and 86 assertions,
zero failures or errors.
