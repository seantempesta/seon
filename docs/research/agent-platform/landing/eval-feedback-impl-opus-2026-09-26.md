---
type: landing
lane: one-commit-a-opus (applying eval-feedback-design-opus patch P1)
date: 2026-09-26
status: committed with d6942b298 already in HEAD; RESET NEEDED; unmeasured
---

# Landing: honest `:ms` and recorded evaluation cost (P1)

**What changed.** Applied `tmp/eval-feedback-design-opus/p1-honest-ms-and-recorded-cost.patch` unchanged:
- `seon.sci.eval/evaluate`: `:seon.eval/duration-ms`, the reply's `:ms`, is one clock difference from the
  evaluation's entrance to its shown answer, minus Seon's own program acquisition inside the arm.
- That acquisition is recorded as `:seon.eval/acquisition-ms`, and the kernel's allocation measurement as
  `:seon.eval/allocated-bytes`. Both are on the receipt (`seon.turn`, `seon.cluster.eval` schema) and in the
  saved response (`seon.render.transcript`).
- `:seon.config.eval/bounds` gains `:seon.eval/allocated-bytes` (512 MiB). The reply shows the finding
  only when that declared bound is exceeded; the finding machinery landed in d6942b298.
- **Deleted:** `explained-evaluation` (the profile explanation appended to `:out`) and the per-evaluation
  `profile/begin` mark.
- Treating Seon overhead as a fault is not part of this (owner rule: simplest first). Acquisition cost stays queryable.

**Checks.** The patch applied cleanly on HEAD. `seon.contracts-compile-test/check` over the three changed
source files found nothing. The schema EDN files read. `turn.clj` and `transcript.clj` compile in a
throwaway namespace; `turn.clj` fails there only on `db/absent-call`, which the running JVM (8e4ac24c9)
predates. Linted with `clj-kondo --cache false` so the shared analysis cache was not touched during the restart.

**Not verified.** No test was run and nothing is measured; that waits for the restart.
