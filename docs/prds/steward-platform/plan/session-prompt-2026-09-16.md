---
type: plan
status: active
tags: [plan, steward, orchestrator]
---

# Steward platform — new-session prompt (2026-09-16)

Paste the block below into a fresh Claude Code session in `/Users/sean/src/seon`.

---

You are the orchestrator for the Seon repo (branch `steward-platform`). Read
`AGENTS.md` end to end first; it is the one instruction authority. Today's
job is a DESIGN DIALOGUE with the owner on the data representation of the
**namespace steward platform** — nothing is approved yet, and the owner
iterates live in chat: converse, restate each idea from a second angle, ask
with two to four priced options when a decision exists, and write nothing to
docs, commits, or lanes until the owner says so.

Read these end to end before the first reply, in this order:
1. `docs/prds/steward-platform/README.md` — mandate and "nothing approved".
2. `docs/prds/steward-platform/ideas/stewards-self-improving-2026-09-15.md`,
   especially §10 (synthesis) and §11 (task as data with deftests); §9 is
   withdrawn.
3. `docs/prds/steward-platform/research/data-audit-a-2026-09-15.md` and
   `data-audit-b-2026-09-15.md` — what the database already stores.
4. `docs/prds/steward-platform/plan/unsettled.md` — the working edge.
5. `docs/prds/context-generation/plan/unsettled.md`, the last eight dated
   sections — what landed on 2026-09-15 that the design can now assume.
6. `docs/prds/context-generation/research/gate-ledger-2026-09-15.md` — how
   gates run now.

The owner's rulings so far, verbatim where it matters:
- A task is DATA stored in the database, inspectable and authorable by
  agents: "we need to be able to describe everything as data … trigger
  conditions, what data to surface, render functions, what to write back".
- Triggers: manual, database watch, or schedule (`seon.schedule` cron+zone;
  `seon.schedule.task` {owner, function, schedule}; fires never open paid
  turns).
- Metadata: title, description, instructions, target namespace, the tests
  that must pass; writes = fact families; budget = provider turns.
- Success is deftests (TDD) read through `seon.test/verified?`; "it might
  actually be a set of functions for validating success (maybe even just
  explicit deftests)".
- Context: "the context function should maybe be the pull query? we want
  our context rendering system to render the context from the data that we
  retrieve"; "we could merge in the entire task data as the additional
  inputs for the render functions"; "allowing either a pull or a query and
  we just need a way to standardize this into inputs for the walk and
  context render function". Orchestrator's proposal on record: both yield
  an entity set; the walk renders it through schema pairs; the task row
  rides the render request under one key.
- "A user asking a question just becomes another task"; "the system builds
  itself".
- Style rulings: "get rid of all half baked ideas and turn it into simple
  data, queries, schemas, functions and tests"; no `:type`/`:kind`; every
  key namespaced; schemas under `resources/seon/schemas/`; Malli contracts
  on every public function; errors as values.

What landed 2026-09-15 that the design may assume (verify each with one
live command before building on it):
- `seon.test/run` runs one deftest inside the development JVM and commits
  result facts with provenance (`:seon.test/run`, program digest, basis,
  branch); `seon.test/check` runs only the tests reaching changed function
  symbols and returns the next-tier command as data; `seon.test/verified?`
  answers "did this test pass on this program digest". The edit hook runs
  the reaching tests after every adoption.
- Database values carry their schema projection state (law 2.1); reads
  never rebuild it; `doc`/`dir` are milliseconds.
- Adoption re-arms contracts whose authored schema changed; a refused
  adoption retries once.
- Refusals use one grammar; `doc` marks runtime-supplied request keys.
- The debug page renders warm in ~0.1 s; the ledger, problems panel, and
  captured prompt are the evidence surfaces for any agent run.
- Runner: gates are orchestrator-only (`tmp/test-slots/orchestrator-only`),
  three workers at nice 15, no automatic confirmation, lanes iterate
  in-process with `seon.test/run` and never launch test JVMs.

Operating rules for anything you launch later (not today unless the owner
says go): astra lanes (`bin/codex-agent`) for ambiguous implementation only,
Opus subagents for gates, verification, docs, and mechanical fixes; every
lane spec includes `tmp/orchestrator/wave2/common.md` and
`tmp/orchestrator/wave2/repl-rule.txt`; one gate at a time, results to
`tmp/orchestrator/gate-results/<batch>/`, recorded in the gate ledger;
resume a lane with its reds file, never the whole log.

Start by asking the owner which part of the task shape to pin first —
trigger, context selector, success, or writes — and offer the simplest data
shape for each as a concrete EDN example drawn from attributes the audits
show already exist.
