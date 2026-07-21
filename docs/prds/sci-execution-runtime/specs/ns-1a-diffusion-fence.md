---
type: prd
status: active
tags: [prd, architecture]
---

# NS-1a — diffusion fence, mechanical half

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

## Goal

Fence the diffusion subsystem (a preserved experimental tree) so the
main system never depends on it: no file outside the diffusion tree may
require `seon.diffusion.*`, `seon.worker-eval`, or
`seon.worker-validator`. The diffusion tree MAY require main (that
direction is legal and exists today). This is a robustness/hygiene
boundary: the main build must be able to evolve without dragging the
experimental tree.

Design authority (read it):
`docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
§7 (membership, boundary rule, fence-violating edges table §7.4, NS-1a
package cut §10).

## Verified current state (grounded 2026-07-21, this session)

- `src/seon/repair/candidates.cljc:31` requires
  `[seon.diffusion.grammar :as grammar]`; the only use is
  `grammar/levenshtein` at `:101`. This is the edge to remove.
- `src/seon/diffusion/grammar.cljc:54` defines `levenshtein` (pure fn,
  no deps beyond core).
- `src/seon/eval.cljs:63` requires `seon.diffusion.grammar` — this file
  is on W5 death row (the whole eval-engine band deletes). It gets a
  DATED ALLOWLIST row in the fence gate, not a fix.
- No other file outside `src/seon/diffusion/`, `src/seon/worker_eval.cljs`,
  `src/seon/worker_validator.cljs` requires the fenced namespaces
  (verified by rg over ns forms; prose/docstring mentions don't count).
- `test/seon/log_test.cljs:151` shows the first-party idiom for a test
  that reads source files off disk with node `fs` inside `bin/test-cljs`.
- The `:lora-audit` build (`shadow-cljs.edn:313`) has
  `:ns-regexp "needle-lora-audit-test$"` which matches ZERO files under
  `test/` — report what you find about its membership/orphan status; do
  NOT delete or edit the build (W9 owns build-matrix changes).

## Work

1. **Move `levenshtein`** from `seon.diffusion.grammar` into
   `seon.repair.candidates` (it is a pure fn; candidates is its only
   main-tree consumer). Update `candidates.cljc` to drop the
   `seon.diffusion.grammar` require and call its own `levenshtein`.
   In `grammar.cljc`, require `[seon.repair.candidates ...]` and delegate
   or refer to the moved fn (diffusion→main requires are legal) so
   diffusion-side callers keep working. Preserve the docstring content;
   fix any doc references that name the old owner.
2. **Add the fence gate** as a conformance test that runs under
   `bin/test-cljs` (model it on the `log_test.cljs:151` fs-reading
   idiom; suggested location `test/seon/diffusion_fence_test.cljs`, name
   it what the tree's conventions suggest). It must:
   - scan every `src/**/*.clj{,s,c}` ns form (bracket-anchored require
     matching so prose mentions don't false-positive);
   - assert zero requires of `seon.diffusion.*`, `seon.worker-eval`,
     `seon.worker-validator` from files outside
     `src/seon/diffusion/`, `src/seon/worker_eval.cljs`,
     `src/seon/worker_validator.cljs`;
   - carry exactly ONE dated allowlist entry:
     `src/seon/eval.cljs` — "dies at W5 (deletion inventory); remove
     this row with that band". The allowlist is data in the test with
     the date and reason, so removal is one-line.
   - fail with a message that names the violating file and the fenced
     namespace it requires (steering-quality failure output).
3. **Write `src/seon/diffusion/AGENTS.md`** — a tight localized
   authority (not a status diary): the subsystem is a PRESERVED
   experimental tree (owner directive 2026-07-21); the fence rule (main
   never requires diffusion; diffusion may require main; providers are
   explicit-config opt-in only); membership (grammar, retrieval, oracle,
   scaffold, worker-eval, worker-validator; `seon.ai.diffusiongemma`
   joins at NS-1b as `seon.diffusion.gemma`); its builds
   (`:worker-validator`, `:worker-oracle-eval`, `:bootstrap` shared
   until W5 decision 12); its gate runs in its own lane, not the main
   program gates; pointer to the design doc above.
4. **Report** (summary only, no edits): the `:lora-audit` orphan
   finding, and any other fence-adjacent smell you notice.

## Owned paths (touch nothing else)

- `src/seon/repair/candidates.cljc`
- `src/seon/diffusion/grammar.cljc`
- the new fence test file under `test/seon/`
- `src/seon/diffusion/AGENTS.md` (new)

Protected: everything else. Another agent lane is active in this same
checkout on unrelated paths — do not run `bin/seon up`/`restart`/`down`,
do not commit, do not touch git state. Leave your diff in the working
tree for orchestrator review.

## Gates (run them; report honest results)

- `bin/test-cljs` focused on the repair + diffusion + new fence
  selectors (use the runner's focused selection; do not run overlapping
  full suites while iterating).
- Compile both worker bundles: `npx shadow-cljs compile worker-validator
  worker-oracle-eval` (they must still build with the flipped require
  direction).
- If a compile/test fails on infrastructure (shadow cache lock,
  concurrent build weirdness) rather than an assertion, wait briefly and
  retry once before reporting.

Behavior must be preserved exactly: same repair candidate ranking, same
grammar exports usable from the worker bundle. No feature loss.
