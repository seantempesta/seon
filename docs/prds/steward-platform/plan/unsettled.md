# Steward platform — working edge

## 2026-09-15 17:00Z — opened

- Docs relocated from the context-generation program: the draft (now an
  idea, not a PRD) and the two audits. No plan yet; the owner forms the
  plan of attack from the ideas folder.
- Candidate first slices (from the audits, unapproved): test-run
  provenance + results on `:current-src` (lane test-provenance running);
  `seon.cluster.message/unanswered` + `answered?` + `seon.test/verified?`;
  the render request keys; `my.task` entities; `seon.program/source-files`.

## 2026-09-16 00:20Z — task prototype written for iteration

- Owner rulings in the design dialogue: success = a set of deftests, at least
  one, all passing, run by settlement; a session is an agent's turn chain and
  parallel sessions are parallel agents; an instance is just an entity (the
  worker agent plus its plan step); context via `:seon.render/units` on the
  task schema; no `writes` family.
- [task-prototype-2026-09-16.md](task-prototype-2026-09-16.md): one `my.task`
  family, `:my.task/agent` declared as the wake, two example entities as exact
  transaction data, the functions' contracts, the opening as the walk would
  draw it, six open questions. Nothing in `src/` yet.
- Research lanes running: test-attribution-plan (cross-namespace red-test
  attribution and steward alerts as the task trigger), suite-efficiency-plan,
  cold-page-plan; fix lane hook-publication-race.
- 00:50Z owner: no wake attribute on the task; the focus is task definition →
  a running agent entity. Prototype revised: `start!` is `ensure-entity!`
  generalised (creation-tx + task agent ref + plan + `generated-run-tx` in one
  transaction; the bootstrap's hard-coded task message becomes a task entity).
  Lanes launched since: reach-digest (per-test reach digest; probes first),
  cold-page-kills (approved plan, two slices). test-attribution plan landed
  (option 1 recommended; owner decision pending; its wake fact is now a
  separate question from starting a task).
- 01:05Z owner: "no fake tasks; index all the real tasks we need for
  self-building and repair." [plan/README.md](README.md) opened as the
  roadmap: sections A (task definition), B (test evidence), C (signals as
  facts), D (chatting task and steward), E (batches, merge), F (disk),
  G (platform defects), H (live proof ladder), five pending decisions.
  Owner rulings folded in: the chatting test is relative to the user's newest
  message; workers add tests, never remove (pending confirmation); batches on
  forked clusters merged by exact entity replacement; write-back by file span.
- 01:20Z owner: data first — good schemas, solid names, linked in the graph;
  issues rot because unlinked and unassigned; namespace-centric.
  [namespace-data-model-2026-09-16.md](namespace-data-model-2026-09-16.md)
  written from mined evidence: no production namespace has a steward
  (2/411), no test links to its subject (0 holders), faults name functions
  as strings, 240 issue notes link to code only in prose. Proposed:
  test subject/namespace-under-test/reach-digest, fn file/span, error fn
  ref + steward, `seon.issue` entities indexed from notes, `seon.lint`,
  eval elisions; the namespace view as one pull; landing order; four
  decisions. reach-digest beat 1 landed (`24d12bd48`): 39 s full pass,
  median closure 668 functions — must be incremental.
- 01:45Z owner: faults stored properly (detect, look up, assign; one task per
  class so occurrences roll into one agent's context); find every place with
  prose or unlinked data; never strings for symbols; refs everywhere.
  Data model §7 written: `seon.fault` (class, identity without process) +
  `:seon.error/fault`/`fn`/`proc-fn` refs; the full 29-entity list of prose,
  EDN-text and string-named storage with verdict and writer seam.
- 02:30Z owner: Clojure/Datahike terms only (no "fault"/"class"); the error
  entity is keyed by its signature = seon.id/id over a sorted map of
  {kind throwable-class fn frame}, process moves to `seon.error.occurrence`
  component entities; ONE family for issues and tasks (`seon.issue`; the
  my.task prototype folds in). Data model §9; spec
  [issue-family-spec-2026-09-16.md](issue-family-spec-2026-09-16.md).
  Lanes launched: error-graph, issue-family; running: reach-digest (beat 2),
  attempt-and-eval-facts, program-provenance (corrected: file/span + lint
  entities, no derived subject).
- 03:20Z **RESET NEEDED on default**: the error-graph lane's schema change
  (`:seon.error/signature` becomes an identity; occurrence components,
  `2320dc1a9`) makes development adoption refuse ("predates the incompatible
  schema change"); program-provenance landed (`3402913f3`: file/span on
  function and test entities, `seon.lint` entities with exact replacement)
  but could not prove on default. The orchestrator reforks default ONCE
  after issue-family (seon.issue.edn), reach-digest (seon.test.edn) and
  attempt-and-eval-facts (seon.ai.*.edn) land, batching every schema
  change; then reseeds and re-runs each lane's live proof. Lane
  entity-pairs launched (P2); issue-settlement spec written, launches when
  turn.clj is free.
- Owner rulings 03:05Z: one steward first (`seon.render.web`); new issues
  are authored as entities in the database, the folder is an export; the
  issue block links and never copies; vocabulary is entities/attributes/
  values; DeepSeek sessions may run freely; prerequisites P1–P11 in the
  issue-family spec §6 come first.
- 03:55Z default reforked (pid 7595). Two blockers found on it: (1) every
  system turn refused — generated reads depended on every identity
  attribute incl. turn/eval/attempt ids; introduced by `563034709`
  (value-renderer identity pulls); fixed `474234fb7` (inert identities
  excluded via the shared `wake/inert-attributes`), Juniper install and
  prompt live again, forbidden dependencies 14 → 0. (2) development
  adoption rejected: `[:db/retract e :seon.fn/form-span]` without the tuple
  value; program-provenance resumed to fix. entity-pairs landed
  (`ac34ce5a3`). Trial design written:
  [issue-context-trials-2026-09-16.md](issue-context-trials-2026-09-16.md)
  (issue: doc/dir arglists EDN reader; seven renderings A–G; measures from
  facts). Two stale issues archived.
