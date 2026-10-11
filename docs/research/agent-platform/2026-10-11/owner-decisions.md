---
type: research
title: Owner decisions and direction, morning of 2026-10-11
date: 2026-10-11
---

# Owner decisions and direction, morning of 2026-10-11

Recorded by root before compacting. Fable's pass 2 folds these into `docs/seon/plan.md` §7; until then this page is
their record.

1. **Focus: improving the tasks.** The next work is the task pipeline and task quality, not more platform machinery.
   Embedding search for related symbols comes later.
2. **Fences.** The owner is "especially worried about all fences": they patch issues that should be solved at the
   root, and they add overhead and complexity, so avoid them. For each fence: name the failure it prevents and remove
   the root cause instead. The three causes found by Fable's sweep (12 of 19 fences guard hypothetical failures):
   landing writes into the shared working tree; two evaluators; database values held across transactions.
3. **Auto-retire.** A task whose proofs are green on main retires itself, derived not authored.
4. **Proofs.** How to write good proofs is not yet clear. Keep a proof catalogue of good and bad examples with why
   (seeded from `designed-task-examples.md`, this weekend's green proofs and the failures) to structure them.
5. **TASK-CHAIN** (`docs/seon/schedule/cards/TASK-CHAIN.md`):
   - Build it generally and experiment with variations step by step, keeping the steps that make the task better at
     each stage, as many stages as are worth their model calls.
   - Agents may update any task field. The pipeline overwrites a few fields between stages (stage, instructions).
     Context goes through the same generation function. Byte-stable context for caching is nice; the right context
     matters more (removing references to wrong functions or schemas changes the next agent's work).
   - Fable then Astra review the card in sequence, then root and owner discuss.
6. **Stages advance by stage proofs: one system end to end.** A stage is done when its stage proofs (generic deftests
   per stage type, evaluated against the task) are green. Every stage records an explicit decision
   (`:seon.task.stage/name`, `/decision`, `/reason`, `/keys`); "nothing to do" is `:unchanged` with a reason, never an
   absence. The agent knows exactly what is left before it can advance.
7. **Guessed names** ("Unable to resolve symbol"): answer at the failure with the nearest real symbols and their
   calling forms; learn guessed → resolved from the agent's next successful form; link confirmed resolutions to the
   task's references; a name guessed by two or more agents becomes a mined task (teach it, build it, or alias only a
   standard Clojure/library word). Card §6b.
8. **Fable re-plan in two passes.** Pass 1 (`fable-replan.md`): analysis, simpler ways, draft order, numbered questions.
   Root and owner answer the questions in that file. Pass 2: Fable rewrites `plan.md`.

State at this point:
- The collection that broke the entrance is fixed: f51240f34, live on default. Follow-up task:
  `seon.sci.eval/a-context-holds-the-basis-it-acquired-not-the-database-value`.
- Round 8's landing was refused because two interpreted-only reds were labelled compiled at the gate (be337100e). The
  first automatic landing has still not happened.
- Fable lane `fable-replan-2` is finishing pass 1: the fence table, TASK-CHAIN placement, Q12 (remove fence root
  causes or freeze the fences) and Q13 (where TASK-CHAIN goes).
