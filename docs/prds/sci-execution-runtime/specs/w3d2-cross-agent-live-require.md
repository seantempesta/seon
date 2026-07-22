---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W3d2 — cross-agent live require of authored namespaces

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Read FIRST:
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3d risks 3/4 and its "cross-agent live-require gate remains open"
finding: `registry-load-fn` serves only namespaces already registered
in its process atom and returns no corpus source
(`host/context.clj:905` era — re-derive); successful host eval never
calls `install-nursery!`/`register-wrappers!`; registry reconstruction
happens only at host start. The anchor requires corpus-backed
`:load-fn` plus cross-agent live require without restart
(program-synthesis "Runtime is lazily materialized from facts").

## Goal

Agent B can `(require 'my.shared)` and call a function agent A authored
THIS session, without a host restart:

1. **Post-eval propagation**: a successful host eval batch that records
   new/updated authored functions installs them into the registry
   through the EXISTING install path (`install-nursery!` /
   `register-wrappers!` — graduate.clj's one mechanism; W3d1's
   fingerprint stamping rides along automatically). Strengthen the
   post-eval seam in `host/eval.clj`; no second install path.
2. **Corpus-backed load-fn**: when the registry atom lacks a requested
   namespace, the load-fn falls back to the CORPUS at the current
   database value (the record/query surfaces host.context already
   owns) — serving stored namespace source so require works for
   namespaces authored before this host generation too. One load-fn,
   registry-first then corpus; never a third resolution path.
3. **Falsifiers** (grounding risks 3/4): contexts A and B live in one
   host — A authors `my.shared/f` (specced) via an eval batch; B
   requires and calls it without restart (valid call passes, wrong
   call fails structurally — instrumentation covers the new var). And
   the dependency-closure case: B cold-invokes/evals namespace N2
   requiring authored N1 — the closure materializes.

## Owned paths (touch nothing else)

- `src/seon/host/context.clj` (load-fn + registry seams),
  `src/seon/host/eval.clj` (post-eval install hook),
  `src/seon/host/graduate.clj` ONLY if the install path needs a seam
  it lacks (read first; report)
- writer tests (new/extended host registry/graduate suites — enumerate)

Protected: everything else. No commits, no lifecycle ops (another lane
owns `src/seon/execution.cljs`).

## Gates

Full `bin/test-writer` (baseline 369/2773 — record after).
