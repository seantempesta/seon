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
