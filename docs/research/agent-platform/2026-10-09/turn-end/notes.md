---
type: research
status: complete
created: 2026-10-09
lane: turn-end
---

# Turn end: green proofs stop the loop; a no-form reply gets one follow-up

Task `seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green` (bench E0-E1, F1 and F2:
`docs/research/agent-platform/2026-10-08/bench/e0-e1.md`). What exists: `seon.turn/next-agent-work` opens a model call
for an unanswered wake or when `seon.turn/continuing-reply?` says the latest reply continues; the test step records
the proofs' results on the agent's branch before the next derivation; `seon.context/proofs` derives each proof's
status from those results. Approach: derive both decisions from stored facts in the existing derivation; nothing new
is stored.

## F1: all-green proofs end the model calls

- **What marks completion: nothing stored.** `seon.context/task-done?` is true when the held task has proofs and
  every one is `:green` by `seon.context/proofs`, read on the agent's branch: the same derivation the block shows.
  `:seon.task/status` already declares `:done` as "derived from its assignment, proofs and turns, never stored", and
  F5 keeps status derived. RT-3's retraction of the assignment (and AE-4's component edge) will later be written in
  the transaction recording the green; once it lands, the absence of a held assignment already stops the loop
  (`next-agent-work` needs one), and `task-done?` stays the block's status read. No schema change.
- **Block.** The Turns section's `:seon.task/status` is `:done` once all proofs are green (before `:capped`, then
  `:working`). The proofs section shows the green counts as before.
- **Loop.** `next-agent-work` continues a reply only when the task is not done. An unanswered outside wake still
  opens a call (the agent may answer a message), and that reply does not continue past green either.
- **Merge.** None here: the branch keeps the change; root's review lane reads green branches. F5's merge-request half
  stays behind RESPEC R-2.
- **Cost.** One `seon.context/proofs` read per derivation that would otherwise continue. Measured on default
  (JVM mode, a task with one proof): 3-12 ms on a database whose reach digests are cached, 29 ms on a fresh database
  value; a cold first read for a sym was 0.04-1.7 s (`seon.test.runner/reach-digests`, the existing task
  `seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program`). In the loop the step has just run the
  same proofs and the block reads the same digests right after, so the cold cost moves, it is not added.

## F2: a no-form reply gets one follow-up, bounded

Options: (a) one more call whose history shows the no-form diagnostic, bounded; (b) wait for the next wake, which
never comes for a bench agent (today's behavior: 9 of 65 runs lost). Chosen: (a), N = 1. `continuing-reply?`
continues a reply that evaluated no form when the model reply before it evaluated one, or there is none; a second
such reply in a row waits. The spec's reader rule already says the no-form diagnostic "tells the model to act"; the
§3.5 row now says the same. No new note text: the recorded exchange already carries the reader's diagnostic. The cap
bounds the rest. Not an owner decision: it restores what the reader rule promises, and a deliberate stop stays
`my.turn/wait`.

## Proofs

- `seon.turn-work-test/a-prose-only-reply-gets-one-more-call-and-a-second-waits` (replaces CX-2's
  `a-prose-only-reply-waits`; red on HEAD).
- `seon.cluster.turn-test/a-model-request-is-help-own-exchanges-and-the-block-last` (CX-1's proof, now four fixture
  replies of which the third turns the proof green: three calls, block `:done`, `next-agent-work` nil; red on HEAD).
- `seon.turn-work-test/situation-totality-property`: the oracle's continuation rule follows F2.
- Green: `seon.turn-work-test`, `seon.context-test`, `my.agent-test` (A1). The broader turn namespaces
  (`seon.turn-continue-test`, `seon.turn-loop-test`, `seon.gen.loop-test`, `seon.turn-backstop-test`,
  `seon.cluster.turn-test` without long tests) hold 17 reds with the change, every one red on HEAD too (20 there; the two check JVMs overlapped, so the three
  HEAD-only reds may be load).

## What the bench should see

Post-green calls: 0 on runs with no outside wake after green (was 97 of 322). Capped green runs: only those that
reach green on the last call. Prose-ended runs: a lone no-form reply costs one call instead of the run; two in a row
still end it.
