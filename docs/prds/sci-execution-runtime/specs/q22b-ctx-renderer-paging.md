---
type: prd
status: active
tags: [prd, architecture, agent]
---

# q22b — the namespaces and warnings ctx renderers page and preserve errors

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Read FIRST: the audits
`docs/prds/sci-execution-runtime/research/live-namespaces-render-defect-2026-07-22.md`
and `live-warnings-render-defect-2026-07-22.md`, the issue
`docs/seon/issues/unbounded-runtime-acquisitions-exceed-frame.md`, and
the TWO landed paging precedents: `src/seon/runtime/admission.cljs`
(q21) and `src/seon/execution.cljs` (q22a — including its provenance
catch).

## Goal (the agent-context slice of q22)

1. `src/seon/agent/ctx/namespaces.cljs` (~683 KB grouped acquisition
   against a hardcoded 4 MiB assumption at `:409`; error-swallowing at
   `:418-424`) and `src/seon/agent/ctx/warnings.cljs` (~550 KB
   unbounded at `:119`; swallowing at `:143-147`) both converge on the
   landed paging precedent: index-page cursors + bounded pull batches
   at ONE frozen database value, complete acquisition (context renders
   must not silently truncate — page to completion like the
   precedents), page sizes provably under the 64 KiB floor.
2. **Error preservation**: a top-level database error surfaces as the
   block's honest render-failure WITH the original structured error
   (the reactive-context law: a render that cannot acquire says so
   truthfully) — never `:seon.error/data nil` or a manufactured
   message.
3. Renders stay byte-equivalent for healthy acquisitions (context
   prose untouched — tests assert structure/presence, never wording).

## Falsifiers

- Paged ≡ current at one frozen basis for both renderers (the q21 set-
  equality idiom applied to their acquisition results).
- Injected frame-too-large yields the structured error inside the
  block's failure render, both blocks.
- Page-size probe under the floor, reported.

## Owned paths (touch nothing else)

- `src/seon/agent/ctx/namespaces.cljs`,
  `src/seon/agent/ctx/warnings.cljs`
- their test files (enumerate)

Protected: everything else. Live read-only MCP probes allowed; no
commits, no lifecycle ops.

## Gates

Focused ctx/namespaces/warnings selectors, then full `bin/test-cljs`
once (baseline 1504/7261 — record after). A live render proof: eval
the two renderers against the live database value via MCP and show
healthy output (the blocks render, no failure text).
