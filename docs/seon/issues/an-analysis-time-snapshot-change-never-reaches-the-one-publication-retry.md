---
type: issue
status: open
severity: friction
tags: [issue, publication, adoption, source, bounded-execution]
---

# An analysis-time snapshot change never reaches the one publication retry

`seon.cluster/source-change-phases` (`src/seon/cluster.clj:608`) declares what
"the source changed under this publication" means, and
`retrying-source-change` (`src/seon/cluster.clj:624`) gives that event its one
retry. Only two seams carry the declared cause: the analyzer's span read
(`:seon.fn/source-changed-during-analysis`) and the post-adoption digest
compare (`::source-changed-during-adoption`).

Two more seams raise the SAME event without the cause, so they refuse straight
out of `refresh-source!` with no retry:

- `stable-manifest` — "Source changed while current-src was being analyzed;
  retry." (`src/seon/cluster.clj:1910`); the message asks the caller to do what
  the one retry already exists for.
- `incremental-source-refresh!` — "Source changed while incremental publication
  was being analyzed." (`src/seon/cluster.clj:2020`).

Observed on 2026-09-16 while probing cohosted adoption in the shared checkout:
a neighbouring lane's edit to `src/` during a publication ended the operation
with the `stable-manifest` refusal, from a code path whose own declaration says
the next read converges.

Fix: give both refusals the declared source-change cause so the one retry owns
every seam of the same event, and keep the retry bound at one. Regression:
a publication whose snapshot moves once in each of the four seams completes;
a snapshot that keeps moving refuses NAMING the phase.
