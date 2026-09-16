# Steward platform — working edge

## 2026-09-15 17:00Z — opened

- Docs relocated from the context-generation program: the draft (now an
  idea, not a PRD) and the two audits. No plan yet; the owner forms the
  plan of attack from the ideas folder.
- Candidate first slices (from the audits, unapproved): test-run
  provenance + results on `:current-src` (lane test-provenance running);
  `seon.cluster.message/unanswered` + `answered?` + `seon.test/verified?`;
  the render request keys; `my.task` rows; `seon.program/source-files`.

## 2026-09-16 00:20Z — task prototype written for iteration

- Owner rulings in the design dialogue: success = a set of deftests, at least
  one, all passing, run by settlement; a session is an agent's turn chain and
  parallel sessions are parallel agents; an instance is just an entity (the
  worker agent plus its plan step); context via `:seon.render/units` on the
  task schema; no `writes` family.
- [task-prototype-2026-09-16.md](task-prototype-2026-09-16.md): one `my.task`
  family, `:my.task/agent` declared as the wake, two example rows as exact
  transaction data, the functions' contracts, the opening as the walk would
  draw it, six open questions. Nothing in `src/` yet.
- Research lanes running: test-attribution-plan (cross-namespace red-test
  attribution and steward alerts as the task trigger), suite-efficiency-plan,
  cold-page-plan; fix lane hook-publication-race.
- 00:50Z owner: no wake attribute on the task; the focus is task definition →
  a running agent entity. Prototype revised: `start!` is `ensure-entity!`
  generalised (creation-tx + task agent ref + plan + `generated-run-tx` in one
  transaction; the bootstrap's hard-coded task message becomes a task row).
  Lanes launched since: reach-digest (per-test reach digest; probes first),
  cold-page-kills (approved plan, two slices). test-attribution plan landed
  (option 1 recommended; owner decision pending; its wake fact is now a
  separate question from starting a task).
