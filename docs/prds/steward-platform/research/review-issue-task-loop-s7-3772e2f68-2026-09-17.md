---
type: research
status: reviewed — three changes required before the gate
created: 2026-09-17
tags: [review, orchestrator, issue, turn, task-loop]
---

# Orchestrator review — `3772e2f68` (S7, the issue task loop)

Read: the landing note in full, the diff stat (17 files, +836/−183), the
`turn.clj`, `issue.clj`, `db.clj`, `cluster/agent.clj`, `bootstrap.clj` and
schema hunks, and the recorded shown texts and live proof.

**Accepted.**
- T1: `start-tx` admits an issue with tests or a detector and refuses neither
  by name, inside its `:db.fn/call`; settlement's done-query is tests, else
  the detector; `resolved-tx` written by settlement only.
- T2: continuation for an issue agent derives from issue-open ∧ budget; the
  conversational disposition path is untouched, as instructed.
- T3: the per-turn status is the existing generated read re-evaluated after
  every closed turn; its shown texts are exactly the shape the owner asked
  for (done condition, the exact tests, pass/fail with the failure's shown
  text, turns remaining); it renders on the agent's debug page (screenshot
  retained).
- T4 / C4: exhaustion is one atomic transaction writing
  `:seon.issue/budget-exhausted-tx` and the single root message; resume is
  the same `start!` with a larger total budget — one function, no second
  path. Live proof on `default`: two real provider turns, exhaustion, root
  message, page.
- The `db.clj` retention narrowing (a nonempty test set may not be emptied;
  a detector-only issue may be created) is accretive and correct.

**Required before the gate (the lane is resumed with these).**
1. **`issue-status-read?` classifies an evaluation by parsing its source
   text and matching the head symbol `my.issue/status`** (`turn.clj:2113`),
   then exempts it from the generated-read guard and the since-diff dedupe.
   That is classification by name over text, AGENTS.md §2.2's banned
   substitute, inside the turn loop. Derive it instead from the read's
   declared provenance: the opening generates this read from the issue's
   render, so the evaluation request (or the evaluation's read evidence)
   can carry the declared origin as a fact, and the guard consults that.
   No `edn/read-string` of source in the loop.
2. **The toolkit exclusion is a name list** — `(remove #{'my.shell 'my.edit})`
   in `cluster/agent.clj`. Ruling D2 says a session's teaching is pulled in
   when the namespace is required. Derive the toolkit from what the agent's
   session requires (its namespace's requires / the issue's declared
   dependencies), not by subtracting two names.
3. **`:seon.issue/turns-remaining` is declared on the issue entity schema as
   "derived status data".** A derivable value must not be a stored
   attribute (derive-or-die). If it is only a key of the status view, declare
   it on the status view's schema, not the entity's; confirm nothing stores
   it.

Minor, not blocking: `turns-left` converts error values to exceptions with
`doseq`/`throw`; a typed refusal returned to the caller fits the loop's error
model better.

**Gate after the three changes:** the namespaces in
`tmp/orchestrator/gate-requests/issue-task-loop.txt`.
