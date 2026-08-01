---
type: prd
status: active
tags: [prd, agent, database, testing]
---

# Graders in fact-space — the plan sketch (owner session, 2026-08-01)

The owner's frame: the grader is an agent that does EVERYTHING by
modifying database facts in sci-eval land — never files, never diffs,
never a second toolchain. The platform exercises itself; every grading
action is itself an auditable transcript.

## Vocabulary (the dependency's words, ruled by the vocab table)

The "branch?" is exactly a **Datahike branch**. A cluster IS a branch;
so the exam surface, the agent's run, and the grading session are all
branches related by **commit IDs**:

- **agent starting point** = the commit ID the agent's cluster forked
  from (the prepared exam surface's published commit);
- **agent ending point** = that branch's head when the run closes;
- **anything in between** = every turn's basis transaction is already a
  recorded fact (capture-before-provider commits the rendered basis;
  receipts carry ordinals), so any mid-run state is one `as-of` or one
  fork-from-commit away.

## The loop, entirely in fact-space

1. **Surface preparation.** A grader agent forks the base commit and
   transacts the exam surface as program facts — functions and schemas
   through the SAME persistence rule every agent uses (defn +
   `:malli/schema` → program rows). `acquire!` installs interpreted
   corpus from rows, so the surface exists the moment the facts commit.
   No files touched.
2. **The run.** A fresh agent boots in a cluster forked from the exam
   commit; every turn commits; the transcript accumulates as facts.
3. **Grading.** The grader forks the ENDING commit into its own grading
   branch — runs tests, queries, cleanups, refactors — without the
   agent's record ever being touched. Mid-run probes fork a mid-run
   basis. The grading session is itself a REPL transcript rendered in
   the same debug display.
4. **The judge** is just another agent whose context renders the graded
   transcript — no separate judge machinery.

## Already built

branch fork (~17 ms, registry/branch!), per-turn basis records, agents
authoring functions as facts (w3d1), the door installing interpreted
corpus from rows, `seon.db/q`+`pull` for grader inspection (landed
5599d72b2 with the fresh-JVM door proof), `:seon.test` facts +
seon.test.runner.

## Open design questions (grounding audit before any implementation)

1. **Precedence.** "Rewrite any and all seon functions" in fact-space
   requires that a current agent-authored row for an EXISTING core
   function WINS in the interpreted install over the compiled-var
   binding. Today's acquire! order must be read and ruled on. Blast
   radius note: the driver itself runs compiled — a fact-space rewrite
   changes what AGENTS run, never the driver. That containment is a
   feature but must be stated and proven, not assumed.
2. **Fork as a grader operation.** Branch ops are operator/registry
   level today. The grader needs fork-from-commit mediated by the
   harness/driver (an effectful capability), not raw branch API in
   agent land.
3. **Tests through the door.** seon.test.runner as a grader-callable
   surface over `:seon.test` facts on the grading branch.
4. **Store hygiene at scale.** Twenty concurrent exam branches × prep +
   grading forks per generation — branch lifecycle (retire-branch!) and
   store growth need a measured answer, not an assumption.

## The oversight loop (owner refinement, 2026-08-01 evening)

NOT blind RL. The bootstrap is generated and refined by an OVERSEER
AGENT running many simulations with FULL UNDERSTANDING of every prior
attempt: it reads earlier generations' branches, transcripts, and
scores — all database facts, so the overseer's experimental memory is
simply queries — forms a hypothesis ("agents get confused here, they
expect this"), tweaks the bootstrap forms or the surface, re-runs the
simulation, and compares. Iterative comprehension-driven tweaking,
watchable in the same debug display as any other session. First
planned experiment once the harness exists: pinned-bootstrap vs
bare-tail (the owner's testable hypothesis that losing the core
degrades the agent).

## The two phases (owner refinement, 2026-08-01 late evening — ruling #30)

Phase 1 — the self-taught bootstrap. The core system is a rock-solid
minimal-battery REPL, faithful enough that an agent's prior Clojure
knowledge just works. During simulations agents author plain functions
operating on the database or shared libraries; nothing they can CALL is
restricted — only what they may PERSIST to the program graph (the
persistence gate, ruling #30). The judge scores both objective success
AND whether the agent CHEATED; winning transcripts become the new
bootstrap for that agent model.

Phase 2 — the unrestricted judge session. An LLM judge connects to a
session with ALL restrictions lifted and may author and improve the
base and core system itself. Then iterate: re-run the simulations and
measure whether agents do better on the improved core.

## Sequencing

Held behind the audit-and-planning stage (owner: nothing launches until
done right). Order: minimal-surface floor proven rock-solid → grounding
audit for the four questions above → design spec for owner ruling →
driver/inspect-ai repoint → generation zero (manual, small n) → the
self-improving loop.
