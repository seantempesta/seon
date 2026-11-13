# Principles: how Seon is meant to work, and when to raise the alarm

Required reading for every session, helper and Seon agent before any work, and handed on whole to every helper or subagent
you start, so no one researches or builds from a fragment of the picture. The owner's words behind each are in
[decisions](decisions.md); the rules that enforce them are in [AGENTS.md](../AGENTS.md).

## What we are building

**An assembly line for software.** A user's request, however rough ("fix it"), becomes a task; an agent improves its plan and
works it; if it is still stuck at a configured turn count, a refine agent reads that session, fixes the plan and resets the
session's evals to a better starting point, and the agent resumes. The loop repeats until the task is solved and merged. The
solved session is curated: it becomes an example later agents start from, and training data.

**The measure** is the number of turns an agent takes from a roughly specified task to completed, merged code. A task that
never merges is a failure, not a small number. Work is ranked by the turns it removes ([AGENTS §1](../AGENTS.md)).

**Gold sessions and templates.** A session that went well is kept with its task. When a new task looks like an earlier one
(by embedding similarity or the schemas it touches), the agent is shown that session as a possible precedent: "this earlier
task may be related, and here is how it was solved; if it isn't related, set it aside and do your own work." Work that turns
out to be formulaic becomes a template function: parameterized, so tasks can be created and assigned on the fly. A problem
that recurs across the store (a class of bad docs, schemas, tests) is solved once, then cloned and adapted, and agents swarm
the instances.

## Seon is a live REPL platform

Work like a person at a REPL: try something small, look at the result and at your own reasoning, adjust, and settle on what
works as a baseline, then improve it. Prefer the 90% win for 10% of the effort, and expect to discard ideas along the way.
Experiments are informal loops by one agent on one task, not formal studies. This holds for Seon agents and for the sessions
that build Seon.

## Boring by construction

Almost everything in Seon is immutable data: database values, messages, code as values. Immutable data is safe to share,
cache and pass anywhere. The risk lives in a short, known list of places where the system touches a changing world, and each
has exactly one sanctioned way, built carefully so the failure cannot happen, not checked for after:

| Where it can go wrong | The one way |
|---|---|
| External effects: shell, files, network, model calls, processes | the one effects system: declared, bounded, killable, answering errors as data |
| State and concurrency | Flow procs and channels; a proc's state is its own; no atoms, locks or spin-waits |
| Time and waiting | data on channels: a proc waits by blocking on a channel for a reply, a close or an error; the only time anywhere is an effect's declared deadline, delivered as a message |
| Writes to the store | the one write path, checked against the schema; one writer per branch; one merge |
| Redefining code | evaluation that records the new form |
| Data shapes | one named, fully namespaced malli schema per shape that is stored, synced or offered as an interface |
| The wire between processes | one codec (jfasl) with a format version; an unknown tag is an error |
| Process lifecycle | clean start, pause, stop and resume, and a path back after a crash |
| Resources | declared bounds on ticks, bytes, memory and time |
| Dependency pins | one pin per library, proved whole before use |
| Derived data and caches | reused only by what was read, never relied on |

Everything downstream relies on those places and stays plain: **no guards, retries, fences or special cases.** A failure is a
loud bug in the one sanctioned place, fixed at its cause, so its whole class cannot recur.

## How designs are chosen

- **Native to the Seon agent.** There is only the Seon agent. Claude sessions get exactly its capabilities, through its own
  ops; anything built for outside callers (scripts, MCP tools, server ops, the web) is a thin wrapper over the native design.
- **Seon's strengths first.** Content digests, one store with cheap branches, the program indexed as data, recorded reads,
  the ratom's change tracking, warm forks in milliseconds, live code loading, Flow. Never rebuild what an outside tool would
  do on a cold machine.
- **Library first.** Search prior art and read the library's source before writing our own. Improve the library in our fork
  (Datahike, Flow, Jolt) so every caller gains, rather than layering a custom mechanism on top.
- **Correct and simple first, fast later,** inside the uniform Flow design; never a special fast path for a marginal gain.
- **One mechanism per job.** If several designs do one job, design one better one that meets all their goals, and delete the
  rest in the same landing. Never remove functionality, and never argue a change by its size.
- **Cost proportional to the change.** A change costs what it touched, at million-token contexts with many agents at once,
  never the whole page or the whole program. Hard-won mechanisms (the ratom's tracking) are kept and built on.
- **Share what is provably the same,** unless recomputing costs less than sharing; measure both, then pick one policy and
  apply it everywhere.
- **Schemas are the vocabulary.** Contracts name the schemas a function takes and returns, so the program index can join on
  them: from a function, its schemas, the functions that produce or consume them, and their tests. That graph drives what an
  agent is shown and how it finds compatible code. A duplicate schema is a bug.
- **Tests check the goal, fast.** A test states what an agent asks and must get back, and checks its most important 95% in
  milliseconds: no load, no mass simulation, nothing booted that a fork already holds.
- **What we keep lives in git.** Code and EDN representations of everything of value; the store is erasable while it holds
  only test data, and stops being erasable once it holds data we keep.

## Raise the alarm

You are the one in the field: you will see these rules broken before anyone else does. When you find a second mechanism for
one job, a guard around a bug, a wait on a clock, a hand-kept list, a duplicate schema, an effect outside the effects system,
an estimate taken from the last run, or a design built from the outside in, stop and say so to whoever you report to, with
the evidence. Don't work around it, and don't wait for someone else to notice.
