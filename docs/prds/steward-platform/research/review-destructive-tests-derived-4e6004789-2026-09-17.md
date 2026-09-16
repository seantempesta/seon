---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, testing, destructive, F1]
---

# Orchestrator review — `4e6004789` + `7279ccfde` (destructiveness declared at the owner, derived by reach)

Read: the landing note, the diff stat (12 files, +497/−130), the
`src/seon/test.clj` additions (`destroyers`, `host`, `host-text`), the
`src/seon/test/runner.clj` replacement of the hand list, the `fn.clj`
indexer clause and the schema additions.

**Accepted.**
- The hand list `seon.test.runner/destructive-owners` (three symbol strings)
  is deleted. Each owner declares `:seon.fn/destroys` — a nonblank string
  saying what it deletes that it did not create — in its own metadata at its
  definition, admitted by the indexer as a program fact. Two owners declare;
  `seon.cluster.store/create-store!` deliberately does not, with the
  reasoning in the attribute's schema docstring.
- ONE derivation on both hosts: the cold tier checker filters manifest rows
  by the attribute; the in-process side queries it on the cluster's
  database. A program declaring nothing is a typed unknown / thrown drift
  refusal, never "no destructive tests".
- `seon.test/host` answers `:in-process` or `:isolated-snapshot` with the
  owner, what it destroys, the call path and the cold command; a test with
  no program row is the typed unknown and `run` refuses it on a development
  root. The render pair prints "runs: …" and the AI render offers the
  requery form.
- **The departure from the slice wording is correct**: the host is derived
  through `:seon.fn/destroys` → `:seon.fn/calls` → `seon.test/host` rather
  than stored on the test entity. Storing it would be a materialised closure
  (the call-graph research's "no materialised closure" and derive-or-die).
  F1's "index it so agents can query where it runs" is satisfied by a
  one-call query and by the rendered line.
- The two protected-path touches (7 lines in `fn.clj`'s `var-row`, one
  metadata key in `operator.clj`) were necessary and landed between the
  call-graph lane's own commits without conflict.

**Boundary accepted:** no in-process proof — six adoption attempts over two
hours waited 5–8 minutes each behind other lanes' publications on the
operator lifecycle lock and were then refused by the open
snapshot-change-without-retry defect; the schema change forces a complete
publication. The cold gate is the proof. Before-numbers on `default`: 124
destructive tests of 1,836 (49/16/3 per owner, union in 46 ms); the owner
set is unchanged so the union must stay 124.

**Systemic note for the working edge:** seventeen concurrent `init --dev`
publications from lanes' edit hooks queued on one lock. Fewer simultaneous
editing lanes until S9/S5 change how adoption is requested.

**Gate requested:** batch 105 = platform, then `seon.test-reaching-test
seon.test.runner-test seon.test-runner-test seon.fn-test`.
