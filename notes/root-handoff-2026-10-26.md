# Root's handoff (2026-10-26, before a compaction)

What a fresh root needs in one read, beyond AGENTS.md, docs/plan.md and docs/decisions.md. The history is in notes/merge-log.md and git; the 2026-10-25 handoff is in notes/archive/.

## The state of the system
- Main is green on what bin/prove and the named process tests cover. The last full run (2026-10-26 00:48, notes/research-2026-10-26/full-run-summary.log) took 497 s for `bin/test --all` and 153 s for `bin/check 100`; its reds are fixed and merged.
- Pins (docs/forks.md): Chez fa6a4c73, Jolt 2ceb680e, Datahike 336de2ab, http-client e866277d (seantempesta forks, jolt/<topic> branches).
- Paid runs: about $1.2 spent of the owner's $30 for 2026-10-25/26.

## The direction (owner, 2026-10-26)
One derivation: notes/one-derivation-2026-10-26.md is the statement; notes/one-derivation-joint-design.md on jolt/webui-root-chat is the approved design (revision 3 and later), with its two reviews in notes/. In brief: ctx pulled once and patched → view → parts → (html part) / (ai part) → diff; the agent's request is a pure function of the db at its session's t's (byte stable, nothing stored as text); evals run once; changes from outside arrive as inbox messages carrying ctx-shaped diffs; the REPL only evaluates and records, and every enrichment is a render over facts.
Slices: 1 ctx facts (ctx-atom); 2 view, parts, the agent page (agent view); 3 one diff (related-sections); 4a the screen deleted; 4b the request as parts, with judge and seon_context (related-sections + agent view); 4c the debug view re-derived; 5 every page off the walk; 6 messages and changes as inbox entries (agent loop). Each slice: proofs red → green, prove, main-test by name, a browser check, and the live-agents measurement against notes/live-baselines-2026-10-26.md.

## Lessons from 2026-10-25/26 (also in AGENTS and decisions)
- Run `seon.main-test` by name at every merge: the laws (dates in docstrings, catches, an error's raiser) are process tests bin/prove leaves out; dated docstrings came in with almost every branch.
- Never edit or commit a tracked file while a verification runs: it makes the binary stale.
- A full run on a clean main each day: it found hangs, a binding bug in Jolt and stale checks bin/prove never ran.
- An independent review for any design this size: two reviews found a design written against a withdrawn formula, inflated deletions and an unstable prefix.
- The owner asks for causes, not patches: the repeat rule was deleted once its causes were fixed.
- `bin/check` names that are full-run steps (e2e-calc, durable-read) are not modes; an unknown mode prints FAILED.

## Open with the owner
docs/plan.md, "Waiting on the owner".
