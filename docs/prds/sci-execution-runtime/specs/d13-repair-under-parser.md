---
type: prd
status: active
tags: [prd, architecture]
---

# D13 — parser repair: one namespace under the parser

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

## Goal (owner ruling + philosophy, 2026-07-22)

Repair is confident-fix-or-error: delimiter repair fires only on
provable fixes; symbol repair applies only a UNIQUE passing candidate
(2+ = refuse ambiguous, 0 = error). "Candidates" is private machinery,
not a decision surface. Therefore:

1. MERGE `seon.repair` + `seon.repair.candidates` into ONE namespace
   **`seon.repl.parse.repair`** (`src/seon/repl/parse/repair.cljc`) —
   the parser's repair submechanism. Preserve every public fn and the
   exact unique-winner/threshold/ranking semantics; the scoring fns
   may become private if no external consumer needs them (check:
   `seon.diffusion.grammar` requires `levenshtein`, `seon.worker-eval`
   uses the repair op sweep, `seon.host.preflight` consumes candidates
   — keep what they need public, in the one merged owner).
2. Registered keys follow the owner: `:seon.repair/*` →
   `:seon.repl.parse.repair/*` in the same move (applied-repair facts
   are persisted → this rides the next reset boundary; no-lock-in
   ruling). rg literal keyword uses everywhere (envelopes, renders,
   tests, host preflight).
3. Update the fence/conformance expectations and any docstrings
   teaching the old names (docstrings render to agents — true
   current-state).

## Owned paths

`src/seon/repair.cljc` + `src/seon/repair/candidates.cljc` → the one
new file; every consumer's require/alias/key lines (enumerate — src,
test, diffusion tree, host); test files renamed to mirror.

Protected: everything else; another lane owns `script/seon/dev/*` and
`src/my/blob*`. No commits, no lifecycle ops.

## Gates

Focused repair/preflight/worker selectors; full `bin/test-cljs`
(baseline 1514/7293) + `bin/test-writer` (369/2781). rg proof: zero
`seon.repair` tokens outside the new owner.
