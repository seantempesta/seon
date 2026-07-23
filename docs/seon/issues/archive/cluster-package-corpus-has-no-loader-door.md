---
type: issue
status: resolved
severity: blocker
tags: [issue, packages, agent, runtime]
---

# Load cluster package corpus namespaces into agent programs

## Problem

The per-cluster package root had no ingestion path from wrapper source files to
the database-backed program corpus. A valid
`packages/corpus/seon/packages/js/*.cljs` leaf was therefore invisible to a
live agent loader.

## Evidence

`script/seon/dev/cluster.clj:75-95` created only `npm/package.json` and
`deps.edn`. CLJS execution acquisition admitted only namespace sources written
by the REPL process, while the loader consumed only those acquired database
rows. An absent namespace reached the terminal rethrow in `src/seon/eval.cljs`.

The package-wrapper exemplar installed `fast-deep-equal@3.1.3` and supplied the
real wrapper under the cluster package corpus. Directly evaluating that file
would have manufactured REPL provenance rather than exercising package-corpus
loading.

## Acceptance

- Installing or reconciling a cluster package wrapper transacts its namespace,
  functions, schemas, source, and require edges through the ordinary corpus
  authority with explicit package provenance.
- The JS loader derives admission from the `seon.packages.js.` prefix and the
  installed package row, without a hand-maintained namespace list.
- A fresh live agent can require and call the wrapper; another cluster without
  the installed row cannot.
- Removal retracts the wrapper corpus rows, and restart reconstructs the same
  visibility from durable inputs.

## Resolution

`:seon.packages/package` is a native ref from each ordinary namespace,
function, and schema row to the installed package ledger entity. WP-K install
plans transact the stamped corpus rows with the ledger row; removal plans
retract corpus entities before the ledger. Execution admission joins that ref
to the installed row and computes locality from the `seon.packages.js.` prefix
plus exact wrapper namespace equality.

Package-empty databases exposed a second structural condition: neither query
clauses nor pull patterns may name package provenance until that schema is
installed. Acquisition now selects the ordinary REPL-only query and pull forms
when installed schema does not support package provenance.

## Proof

- Focused packages: `tmp/orchestrator/pkg-door-focused-packages-final.log` —
  9 tests, 50 assertions, zero failures/errors.
- Focused execution: `tmp/orchestrator/pkg-door-focused-execution-final.log` —
  40 tests, 185 assertions, zero failures/errors.
- Full CLJS checkpoint before the live-discovered pull-pattern completion:
  `tmp/orchestrator/pkg-door-full-cljs.log` — 1,561 tests, 7,714 assertions,
  zero failures/errors. The final structural pull omission is covered by the
  package-empty live boot and completed turn below; the orchestrator owns the
  serial combined-tree rerun.
- Restart reconstruction plus live require/call:
  `tmp/orchestrator/pkg-door-live-restart-reconstruct.log` records the clean
  restart over the durable database and package tree, and
  `tmp/orchestrator/pkg-door-live-call-after-require.json` records reply
  `true` from `seon.packages.js.fast-deep-equal/equal?` with nested data.
- Ruling-15 steering: `tmp/orchestrator/pkg-door-live-steering.json` records
  the flat `:unserializable-value` result with the tier-local guidance.
- Removal: `tmp/orchestrator/pkg-door-live-removal-count-final.json` records
  ledger count `0`; `tmp/orchestrator/pkg-door-live-restart-after-removal.log`
  records clean reconstruction after that retraction.
- Package-empty regression: `tmp/orchestrator/pkg-door-empty-up-final.log`
  records clean fresh boot,
  `tmp/orchestrator/pkg-door-empty-schema-check-final.json` records
  `zero-package-schema`, and
  `tmp/orchestrator/pkg-door-empty-plain-turn.json` records one completed live
  turn with reply `42`.
- Cleanup: `tmp/orchestrator/pkg-door-empty-down-final.log`,
  `tmp/orchestrator/pkg-door-empty-status-final.log`, and
  `tmp/orchestrator/pkg-door-live-status-final.log` record both isolated
  operators fully down.

No source file was evaluated directly to establish wrapper provenance, and no
other lane's cluster or operator participated in the final proof.
