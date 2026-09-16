---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, publication, adoption, store]
---

# Orchestrator review — publication write-volume fix (`c847249b1` … `633004d12`)

Read: the landing note in full, commits A `c847249b1`, B `f614aff94`, C1
`800b8758a`, C2 `11880700e`, `c89dc007d`, `32867359c`, `b95e08db4`,
`dbe7f389e`, `633004d12`; the A and B hunks in `src/seon/cluster.clj` in full.

**Accepted.**
- A: the adopted basis load catches ONLY the typed
  `:seon.cluster.source/source-absent` refusal, reports and logs the missing
  commit id, and reconciles against the live cluster database exactly as the
  no-prior-commit branch does. Any other failure still throws. The regression
  arranges absence without deleting from the store.
- B: currentness derives from the published database (`:seon.source/digest`
  plus the indexed files' relative digests) — derive-or-die applied to the
  artifact mirror that measurably went stale; the artifact is a manifest
  cache validated by relative-path shape and digest, never by a remembered
  commit id; an old cache can require analysis but never a new database
  publication by itself.
- C1: an empty test recording returns before scratch acquisition and
  `force-branch!`; C2: an unchanged source digest yields no seal transaction,
  changed seals reuse entity ids and retract only removed members.
- Measured on `default` after the fix: edit 2 = 3 → 2 source transactions,
  168 source datoms (from 538,569 per rebuild), 378 changed objects
  (from ~950), and both live edits reported "incremental scalar
  publication … development cluster converged". The refusal loop is gone
  and the artifact was rewritten.

**Boundary the lane named, and I accept:** the cluster side is still
15,946 datoms per adoption, 15,860 of them from `seon.issue/adopt!`'s
retract-everything comparison (`issue.clj:247-258`, lookup refs compared
against `{:db/id n}`), which is the task-loop lane's file; recorded for
that lane's next resume. `datahike.api/tx-range` does not exist in our
fork; the lane measured from the history index instead — correct.

**Rejected.** Nothing.

**Gate requested:** batch 103 = platform, then `seon.cluster.boot-test
seon.cluster.source-test seon.test-test`.
