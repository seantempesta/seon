# Principles: what Seon is for, and how to tell when it is going wrong

Every agent reads this page whole before any work: Claude sessions directly, Seon agents by reference in their context. Each principle is one sentence, why, what it rules out, and what enforces it; one "not yet enforced" is owed a law ([the plan](plan.md#enforcement-owed)). A breach is reported with its evidence and becomes a task to make it impossible. This page and [AGENTS.md](../AGENTS.md) are pruned after each retrospective, never appended to between. The owner's words are in [decisions](decisions.md).

## 1. The measure is turns from a rough task to merged code

**A user's request, however rough, becomes a task, an agent works it, and the only finish is its change merged into main.**

*Why.* Seon is an assembly line for software. Every Seon agent has a task, and every task a plan, however sparse ("fix it": first find what it is). Agents differ only by their task and the data on their record, which is their context. A stuck session is read by a refine agent, which fixes the plan; a session that went well is kept as a precedent; formulaic work becomes a template. Each exists to remove turns.

*Rules out.* Counting landings, tests or lines as progress. Reading a task that never merged as a small number: it is a failure. Work that removes no turn while no agent can merge: then everything goes to the one path through. Carrying on over a failure of the system itself: it stops the work loudly and is fixed at its cause first.

*Enforced by:* not yet enforced (turns are counted by hand).

## 2. There is only the Seon agent

**Seon is built from the inside out, for an agent in a forked process with a real REPL on its own branch of the one store; every other caller gets a thin wrapper over what that agent has.**

*Why.* Seon's strengths are inside it: content digests, cheap branches, the program as data, recorded reads, warm forks, live code loading, Flow. Something built for an outside caller starts cold and uses none of them. Claude sessions get exactly a Seon agent's capabilities, so their work deepens what every agent uses.

*Rules out.* A design that begins at a script, an MCP tool, a server op or the web. Booting a system to do what a fork of a running one does. A second system for inspecting, reviewing or judging: a review is a task. Gating what an agent may do, or cutting its context ([processes](processes.md#an-agent-is-never-gated)). Treating worktrees and a system per checkout as more than temporary.

*Enforced by:* not yet enforced.

## 3. Work as a person at a REPL

**Try something small on real data, look at the result and the reasoning behind it, keep what works as a baseline, and improve from there, toward the task's plan.**

*Why.* A form evaluated on real data is the cheapest evidence there is, and most ideas are discarded, so the 90% win for 10% of the effort comes first. Exploring needs nobody's yes; what others will build on (a schema others read, shared machinery, a pin) then gets a written plan citing the forms evaluated.

*Rules out.* A formal study where one agent on one task would do. A theory about an agent formed before reading what it saw and reasoned. Code built on a library whose source was not read. A proof never seen red. Load made to find a race.

*Enforced by:* not yet enforced ("red first" is claimed, never checked).

## 4. One mechanism per job

**Each job has one mechanism, each shape one named schema and each fact one home; a better one replaces the old and deletes it in the same landing.**

*Why.* With two, every reader learns both, and a fix reaches one of them. A need is met in order: what exists; the library, read in its source and fixed in our fork so every caller gains; only then something new. Schemas are the vocabulary: contracts name the malli schemas a function takes and returns, so the program index joins a function to the functions that produce or consume its schemas and to their tests, and that is what an agent is shown. A bug is a class: the fix changes what allowed it.

*Rules out.* A second mechanism, a special case, a hand-kept list, a duplicate schema. Our own layer over a library that could be fixed. A replacement landed beside what it replaces. A capability landed with nothing using it. A rule written for one incident. Removing useful functionality: a removal comes to the owner as an audit of why it is not needed, what supersedes it and how its users migrate.

*Enforced by:* health's `:unschemed-key` ([health](health.md#a-key-with-no-schema)); `seon.main-test/every-name-a-form-uses-is-a-var`. Not yet enforced: a duplicate schema or an unnamed public shape seen twice; `Deletes:` checked against the deletions ledger.

## 5. Boring by construction

**Almost everything is immutable data, safe to share, cache and pass anywhere; the few places that meet a changing world each have one owner, built so the failure cannot happen, and everything after them stays plain.**

- **Effects** (shell, files, network, model calls, processes): the one effects system; declared, bounded, killable, answering errors as data ([flow](flow.md#effects-one-owner-and-its-workers-per-kind)).
- **State and time:** Flow procs and channels; a proc's state is its own; it waits on a channel, and the only clock is an effect's declared deadline, delivered as a message ([flow](flow.md#the-step-fn-contract-every-proc-follows)).
- **Writes:** the one write path, checked against the schema; one writer per branch; one merge, on main's program ([storage](storage.md#the-write-checks-every-entity-it-touches)).
- **The program:** changed only by evaluating a top-level definition form, which saves it; anything else an eval changes is kept as that eval.
- **What a process runs on** (program, registry, config, pins, binary): one reference `{:branch :commit :t}`, set at the one start; prove, land, the test service, agents, test children and update all ask that owner, which reloads, rebuilds or answers a typed error.
- **Shapes and the wire:** one fully namespaced malli schema per shape stored, synced or offered as an interface; one codec with a format version.
- **Lifecycle and resources:** clean start, stop and resume, a path back after a crash; bounds on ticks, bytes, memory and time, declared once ([config](config.md#constants)).

*Why.* A guard at each caller multiplies with the callers and hides the bug. A check belongs inside the one owner, where every path meets it, and a law goes red on any path around it; a failure is then a loud bug in one known place.

*Rules out.* A guard, retry or special case at a caller. An atom, a lock, a poll, a wait on a clock. An effect outside the effects system; a catch outside a named edge ([failure points](failure-points.md)). A caller's own guess at the binary, registry or pin it holds. Switching off, sampling or waiving a mechanism because it has a bug.

*Enforced by:* `bin/laws` (catch, clocks, measures, resolution, transactions); contracts armed at definition ([checks](checks.md#contracts-armed-at-definition)); the test service's `:seon.agent/program-differs` refusal. Not yet enforced: effects routed at Jolt's host primitives; a law that no program loads outside the one start; atoms.

## 6. Nothing changed, nothing runs; a change costs what it touched

**Every result is kept with what it read, so it is reused while that is the same and redone only where it moved.**

*Why.* Correctness and cost together. A verdict that depends only on what it read is never flaky and anyone can reuse it; one that reads the machine (wall time, load, what a process happened to load) can be neither trusted nor reused. A change of size d costs O(d). The lower bound is worked out first, as arithmetic, and is the test's budget; ten times over means its cause is found first, never that a good change is held back. The order of growth is settled in the design; past that, correct and simple first, fast later. What is provably the same is shared unless recomputing costs less: measure, then one policy everywhere ([cost](cost.md#what-things-cost-here)).

*Rules out.* Recomputing per call, per fork or per process. A cold start. Reading the whole for a part. An estimate taken from the last measurement. A test that asserts on time or load, makes load, or checks more than the most important 95% of what an agent asks for. "Flaky" as a status: a result's status is written once. A schedule deciding when something runs ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)). Raising a bound that was reached.

*Enforced by:* tests rerun by reach and verdicts reused by digest ([source](source.md#tests-run-by-reach)); a test's tick budget ([merging](merging.md#a-tests-tick-budget)) and health's `:unestimated-test`. Not yet enforced: a law that no test reads wall time, load or system memory; a second status at one digest filed as a defect.

## 7. Say what you know, and say it when it is wrong

**Every claim is marked measured or hypothesis, and anything these principles rule out is reported at once, with its evidence, to whoever can fix it.**

*Why.* You see a broken principle first, and a wrong claim passed on costs every reader a turn. A measured claim gives its number and source (command, log, file:line); a hypothesis names the observation that would settle it. A cause is named after both ends of its code path are read, a cost after it is measured warm, twice. The owner's words are quoted as said: a question or an aside is not a ruling.

*Rules out.* A claim from one cold measurement, an old note, or a model of a library in place of its source. Working around a broken principle, or waiting for someone else to notice. A ruling widened beyond its sentence.

*Enforced by:* not yet enforced.
