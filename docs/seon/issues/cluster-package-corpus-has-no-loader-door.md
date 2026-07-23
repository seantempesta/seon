---
type: issue
status: open
severity: blocker
tags: [issue, packages, agent, runtime]
---

# Load cluster package corpus namespaces into agent programs

## Problem

The per-cluster package root has no ingestion path from wrapper source files to
the database-backed program corpus. A valid
`packages/corpus/seon/packages/js/*.cljs` leaf is therefore invisible to a live
agent loader.

## Evidence

`script/seon/dev/cluster.clj:75-95` creates only `npm/package.json` and
`deps.edn`. The CLJS execution acquisition admits namespace sources written by
the REPL process (`src/seon/execution.cljs:342-356,669-708`), and
`src/seon/execution/runtime.cljs:648-681` supplies only those acquired rows to
the eval loader. An absent namespace reaches the terminal rethrow at
`src/seon/eval.cljs:818-885`.

The 2026-07-22 package-wrapper exemplar installed `fast-deep-equal@3.1.3` and
placed a valid leaf at
`data/clusters/pkg-wrapper-exemplar/packages/corpus/seon/packages/js/fast_deep_equal.cljs`.
No current source path scans or transacts it. Directly evaluating the file into
an agent would be a workaround, not package-corpus loading.

## Expected owner

WP-W package installation and boundary graduation should connect the
per-cluster packages corpus to the one existing `:seon.ns`/`:seon.fn`/schema
corpus mechanism. The loader should continue consuming database data rather
than opening files itself.

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

## 2026-07-22 implementation checkpoint

The data path is implemented but not graduated: `:seon.packages/package` refs
stamp ordinary corpus rows, WP-K plans corpus upserts and corpus-first removal,
and execution acquisition joins installed rows while enforcing the computed
`seon.packages.js.` prefix plus exact `:seon.packages/as` equality. Focused
package and execution suites pass.

The required live gate remains open. The isolated `pkg-wrapper-exemplar` boot
failed before readiness in protected concurrent lifecycle work with
`ReferenceError: admission is not defined` from
`seon.agent.lifecycle/resume!`. Therefore require/call, ruling-15 steering,
removal, and restart reconstruction are not yet proven. Evidence is recorded in
`tmp/orchestrator/pkg-door-summary.txt`; do not archive this issue until those
four proofs pass.

The independent E2E seam drive then reached the new acquisition query on a
cluster with no installed package rows. Every real turn failed with
`Bad entity attribute :seon.packages/package ... not defined in current
schema` from `src/seon/execution.cljs:343-366`. Registering the ref in process
memory (`src/seon/packages.cljc:20`) does not install its Datahike schema before
the raw protocol query names it. Acceptance additionally requires either
installing the provenance attribute before acquisition or structurally omitting
the package clauses until the attribute is installed. Evidence:
`logs/operator-e2e-seam-20260722/pod/36305bfe-d296-41ae-ada2-a4c70d707ecf.log`.
