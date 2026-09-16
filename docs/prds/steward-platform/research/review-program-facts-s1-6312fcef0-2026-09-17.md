---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, program-graph, S1]
---

# Orchestrator review — `6312fcef0` (S1, analysis on both seams)

Read: the landing note, the diff stat (15 files), the `sci/eval.clj` hunks in
full, the `fn.clj` and `program.cljc` hunks, and the parity regression.

**Accepted.**
- The hand-built row from Var metadata (`sci/eval.clj:396-430`) is deleted.
  `definition-row` now selects the analysed declaration for the accepted
  Var's identity from `seon.fn/source-rows` — a new public, Malli-contracted
  function that runs the indexer's own `runtime-analysis-batch` with the
  caller's namespace row (name, requires, aliases, refers — including
  uncommitted ones) and builds rows through `analysis-rows-by-file`,
  `canonical-row` under the threaded shapes and `declaration-row :agent`.
  Only runtime-only facts merge onto it: normalised contract data, workload,
  test markers. No file coordinates, no defaults (I1).
- Schema declarations evaluated by an agent now compute the same contract
  facts (`generatable?`, `shape`) through `with-contract-facts` against the
  candidate projection, so the resource seam and the evaluation seam produce
  the same `:seon.schema` entity; the earlier "indexer omits register!" issue
  is resolved per the ruling (schemas declare in resources).
- The permanent regression
  `indexed-and-evaluated-declarations-are-the-same-entities` indexes a
  fixture file through `build-artifact` + `reconcile-tx` and evaluates the
  same forms through the real turn transitions, pulls the five identities on
  both branches, normalises with the publication's own normaliser, and
  asserts equality apart from `:db/id`, admission source and file
  coordinates, plus non-empty evaluated `:seon.fn/calls`. 21/21 in process.
- Two defects the regression exposed were fixed at their owners, not
  papered: re-analysis merged into an already analysed candidate without
  clearing old graph facts (now replaced), and schema canonicalisation
  dropped derived contract facts (now retained).
- Filed, correctly out of scope: publication transactions carry no
  agent/turn `tx-meta` (S4 groundwork), and the evaluator reload regression
  needing re-arming.

**Rejected.** Nothing.

**Note for S2.** With both seams analysing, `:seon.fn/calls` can become a
required key; S2 launches after this gate is green.

**Gate requested:** batch 102 = platform, then `seon.program-test seon.fn-test
seon.turn-test seon.sci.eval-test`.
