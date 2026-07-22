---
type: prd
status: active
tags: [prd, architecture]
---

# seon.ns.source — extract the core namespace-source parser from analyzer-info

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. Stopping early
to report is FREE. If source contradicts this spec, stop and report.

Authority: the accepted design review
`docs/prds/sci-execution-runtime/research/ns05-ns5-design-review-2026-07-21.md`
§"analyzer-info disposition" and smell 1. Its finding: `seon.analyzer-info`
mixes two owners — bootstrap analyzer-state projection (dies at W5 with
the cljs.js band, ruling 6) and CORE namespace-source machinery that
must survive: the persisted `:seon.ns.require/*` schema registrations
(`analyzer_info.cljs:214-255`) and the pure source parser
(`analyzer_info.cljs:257-323`) used by `client.cljs:219,1338` and
`agent.cljs:71`.

## Goal

Extract the surviving band to **`seon.ns.source`** (new file
`src/seon/ns/source.cljs`): the pure namespace-source parsing functions
and the `:seon.ns.require/*` schema registrations they own (registered
KEYS keep their exact names — they are persisted attributes; note the
key↔ns relationship in the docstring per the key-namespaces ruling; if
placing the functions makes a different ns name more honest, stop and
report per that ruling). `seon.analyzer-info` keeps ONLY the
analyzer-state projection band and now requires the new owner for
anything it still uses; consumers rewire:

- `src/seon/client.cljs` (`:219`, `:1338`) and `src/seon/agent.cljs`
  (`:71`) require `seon.ns.source` for the parsing/schema surface (keep
  any genuine analyzer-state uses pointed at analyzer-info — read each
  call site and split honestly; enumerate your per-site decisions).
- `src/seon/eval.cljs` (`:56`, death row): require-line edit only.

Real move, all callers updated, no compat shims. Line numbers are from
the review — re-derive from current source.

## Owned paths (touch nothing else)

- `src/seon/analyzer_info.cljs`, new `src/seon/ns/source.cljs`
- require/call-site edits only: `src/seon/client.cljs`,
  `src/seon/agent.cljs`, `src/seon/eval.cljs`
- test files requiring the moved fns (rg; enumerate)

Protected: everything else. Another lane owns `src/seon/host/*` right
now. No commits, no lifecycle ops.

## Gates

Focused ns/agent/client selectors, then full `bin/test-cljs` once
(baseline 1501/7244 — record after). rg proof: the moved fns defined
once; analyzer-info's remaining content is analyzer-state only (show
its remaining public surface in the summary).
