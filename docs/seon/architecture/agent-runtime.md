---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, agent, sci, turn, flow]
---

# Agent execution

An agent owns a retained SCI context and a Flow graph within one cluster. Its
program comes from the cluster's database; its private defs, atoms, closures and
result objects stay in memory. The ordered evaluation facts are its durable
history. [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) specifies the
execution and rendering cut; [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md)
owns task transitions and [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md)
owns candidate acceptance. The flows below are targets where they replace current
acquisition, duplicated turn paths or delivery machinery.

## 1. Program, context and private objects

The program-only base context derives from one database value and its carried
projection. Today `seon.sci.eval/base-ctx` and per-turn fork reconstruction provide
acquisition (`src/seon/sci/eval.clj:2218`). The target acquires the base once and
installs admitted differences into a replacement base generation. Each retained
agent context advances at its idle execution boundary; ordinary fact writes
advance custody without reinstalling definitions or discovering private state.

Changed definitions include agent writes, deletion, resolver bindings and contracts,
not only file-adoption identities. B1's exact `:seon.program/definition-digest`
travels with the actual installed callable. A changed committed definition wins
over a private binding of the same symbol and reports the shadowed identity.
Initializers are not replayed when accepted evaluated roots are installed.

SCI `fork` creates another env atom over shared namespace data. `bind-root!` copies
an inherited Var into a different generation before mutation, but mutates a
same-generation Var in place (`reference-code/sci/src/sci/core.cljc:345-350`;
`reference-code/sci/src/sci/impl/utils.cljc:362-379`). Mutating a base-owned Var can
therefore affect active forks. Private closures may retain earlier Var references;
two successive program updates must prove the intended behavior while preserving
private atom identity.

A JVM Var binding sees its root replaced by reload. Capturing a root does not freeze
its indirect JVM calls or contract. A1/B2 prove old/new/candidate contexts against
direct and indirect calls, aliases/refers/imports, macros, dynamic Vars and noncallable
defs before retiring existing resolution/isolation. Digest equality at install is
necessary evidence, not that proof. Unsupported SCI interop is a typed boundary;
falling back to another loaded body cannot certify a candidate's changed definition.

Every current program function remains callable regardless of whether it appears in
the prompt. Supplied defaults come from the scoped environment by the declared
argument names; supplied arguments win. Prompt selection does not confer execution
permission or create another program registry.

## 2. Wake, admission and serial ownership

A Datahike listener receives a transaction report. Seon's wake owner uses the report
to notify the relevant agent through a sliding-1 channel; the notification says to
rederive work from facts. It does not carry the only copy of a message or task.
Listen before deriving the initial state so a concurrent transaction is not lost.

`next-agent-work` reads one current database value for this agent's open turn and
unanswered wakes. Answering derives from wake transaction `:t` and a qualifying
ordinary turn's basis. Opening alone and system-only turns do not answer a wake.
An accepted ordinary reply does; the handling claim is a separate relation.
Inside-wake classification uses declared message sender semantics, independent of
message subject. Preserve no-paid-loop-after-refusal and budget behavior.

The target turn proc runs on `:io`, dispatching evaluation to the carried compute
executor with its interruption arm and admitted bound. Flow reads the next input
only when the transform returns. Out-of-proc submissions and debug controls share
its completion permit. One agent cannot evaluate two turns concurrently through
different entry points.

A compute Future's timed get bounds waiting, not the body's lifetime. If nested
work outlives that wait, returning the outer transform would allow overlap unless
serial ownership is retained until actual exit. Keep completion/permit and capacity
admission until a controlled late-host-call proof shows no subsequent wake or debug
submission enters the context early. Workload tags and `futurize` do not supply a
bounded executor queue by themselves. No new core.async timeout patch is required.

**Existing seams:** `src/seon/cluster/agent.clj:118` wake channel, `:540` submission,
`:805-845` disarm; `src/seon/turn.clj:2888` work, `:2980-3007` answering;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,271-320`
Future creation and proc loop.

## 3. One turn path and honest completion

The turn owner chooses a source: generated system forms, a provider reply, stored
unevaluated forms during the current execution, or no forms for close. These feed
one evaluation-and-settlement path. Opening, evaluation outcomes and closure are
writer decisions against current facts; transaction count varies with forms and
outcomes. `open-call` is the existing admission owner (`src/seon/turn.clj:395`);
its current already-open behavior must not be described as a new refusal.

One evaluation records source, shown text, stdout, error and read evidence under
its turn/ordinal identity. `seon.id/evaluation` derives that identity through the
shared identity owner; a finite digest is not claimed mathematically collision-free.
The actual result stays in the context and is bound by its result symbol. It is
not a second durable entity or serialized recovery image.

SCI's `time-limit`/`:interrupt-fn` checks interpreted function entry and loop
execution; it does not interrupt an arbitrary host call
(`reference-code/sci/doc/interrupt.md:52,63-87`). Provider HTTP work has its own
deadline. A completion observer names the agent, turn and admitted part that failed
to finish. Its allowance accounts for all evaluations, provider attempts and
settlement, not one evaluation plus one HTTP request. A blocked host body can remain
alive after the fault is recorded. It retains serial ownership; late settlement of
an already closed turn refuses at the writer.

A failed/stopped graph or missing expected proc is unavailable, not an idle healthy
agent. Disarm and cleanup wait for actual owned work to exit under their declared
bounds. Cancellation, a delivered error and an empty ping cannot substitute for exit.

## 4. Read currency and history

Before an ordinary turn, the system considers the latest evaluation of each distinct
read form, including namespace and read basis. A2's `read-evidence-current?` is the
one currency authority. It uses captured dependency plans and source identity plus
revisions; unsupported or broad plans remain conservatively unknown. Writes and
effects are never replayed. Changed reads append ordinary system evaluations.
The target removes duplicate currency logic only when the native revision mechanism
covers the eligible reads, including transaction-time changes.

The agent's history is `seon.eval/of-agent`, ordered by turns and ordinal, rendered
through the evaluation schema's AI/HTML pair. AI uses the exact saved shown text,
without rerendering old results or evaluating historical source. HTML uses the live
object when retained and saved text when unavailable. The walk must honor its
requested output; today's history path requests AI unconditionally
(`src/seon/render/walk.clj:979-1016`).

Prompt composition selects whole units under its budget. Stored evaluation bytes
remain stable, but selection can change which units appear in a later prompt;
whole-prompt byte immutability is not promised. Presentation limits apply once in
the AI render functions/value renderer. Query-work cuts and evaluation deadlines
remain separate controls, each naming its own bound. An elision describes omitted
work and requery identity; it does not invent an omitted count it never observed.
HTML has no presentation clipping.

Compaction clears the agent's evaluations and regenerates its opening from current
record facts through the same system-turn path. It is not manual history editing.
Debug execution uses the ordinary evaluation/settlement owner and explicit custody;
previewing stored history never silently reexecutes effects.

## 5. Tasks, candidates and recovery

B3 trigger creates/resolves task identity without launching an agent. Start validates
its done condition and assignment, then composes the first work with this lifecycle.
Repair tasks expose test/detector obligations each turn; conversations expose the
triggering-message/reply relation. Repeated actionable occurrences must reach parked
or exhausted agents through the existing wake owner without duplicating tasks or
notifications. Namespace responsibility does not route work independently of tasks.

D1 candidates carry their fork commit, connection, program context and assigned task.
A fork inherits agent/turn/schedule facts; startup must explicitly avoid recovering
or running unrelated inherited work. Cluster-qualified messages arriving after the
fork reach the candidate's own message owner. Shared-only forwarding needs a separate
custody/read-evidence proof; copying message facts is not that interface.

The existing definition-time candidate fork stays until D1 supplies its complete
replacement, including conflict-basis checks, invalid-candidate refusal and private
state preservation. Candidate-local green is not automatic shared merge. D1 validates
the combined program and B4 runs its exact definitions before explicit acceptance.

After process failure, interrupted execution never resumes. Recovery closes open
turns and marks unfinished evaluations interrupted; the agent adapts from recorded
shown text. Private objects and result handles are unavailable after restart. Durable
facts remain subject to the selected branch and retention policy. A destructive reset
also loses those facts. Recovery for candidate branches must obey their task-only
lifecycle instead of applying ordinary whole-cluster recovery indiscriminately.

The [web UI](ui.md) observes these same facts. REPL reachability, a successful write,
loaded behavior, terminal facts and visible paint each require their own evidence.
