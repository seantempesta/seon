# AGENTS.md: how we build Seon

Seon is where many agents do real work, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This file is how we work on it. It is short on purpose. Decisions with their reasons live in `docs/DECISIONS.md` (`../notes/DECISIONS.md` until it moves here). Failure points live in `docs/DRAGONS.md`, and words in `docs/vocabulary.md`.

## The spirit (why the first version felt like magic)

The first version ran overnight: a dozen lanes in parallel, each owning one directory, each told to go past experiments and get something working. By morning there was a guided tour of a running system:
- a thousand agents forked in a few seconds;
- an idle agent at 1.4 MB;
- a dashboard showing the whole system live;
- every number on screen measured, not claimed.

Keep that. Concretely:

1. **Make it run, then make it right, then make it fast, and measure each step.** A working end-to-end slice beats a perfect design document. Build the smallest real thing, run it, look at it, then improve it.
2. **The demo is the spec.** Every capability is proven by something you can watch: a tour step, a dashboard tile, a REPL form with its reply. If you can't show it running, it isn't done.
3. **Insight is a feature.** The system shows what it's doing: health, throughput, memory, every eval, every merge, every error, live. A user should understand the system by looking at it. Nothing important is hidden in a log.
4. **Numbers, always, and honest ones.**
   - Estimate before you measure, and derive the estimate from what the work must do.
   - Measure at three or more sizes, under the bench lock, and report the machine load.
   - When the result surprises you, find out why; that is where the discoveries are.
   - Say plainly what was not measured.
5. **Own the whole stack.** When the cause is in Chez, Jolt, Datahike or konserve, fix it there with a proper fix on a `jolt/<topic>` branch. The forks are ours. Most of the big wins came from removing waste the old design hid:
   - code copied on every fork;
   - a thread per `go` block;
   - an O(N²) branch list;
   - a cache that slowed every miss;
   - a flush per eval.
6. **Parallel and owned.** Work is split into lanes that each own their files, report with commits and numbers, and never touch another lane's tree. Root integrates. A lane that finds its plan's premise wrong stops and sends it back with the evidence.
7. **Small.** About 10k lines of our own code. When something grows, ask what else should be carrying it. Every addition names what it replaces.

## Get out of the way (owner, 2026-10-20)

An agent has a real REPL into a real session. Clojure's own semantics are the interface: `ns` creates a namespace, `defn` defines a function, `ns-unmap` removes one. We teach exactly that, live by it, and record what happened. We never decide what an agent is "allowed" to do.

The instinct to add a gate is wrong here. Examples: "only record defs in namespaces the db already knows", "is this a known kind of form?", "refuse unless it matches the list". That instinct produced a check that silently threw away every new namespace an agent created.

When you feel it, stop and ask three questions:
- **What would a plain Clojure REPL do?** Do that.
- **Is this recording what happened, or judging it?** Record it. The proofs and the merge judge it, not a filter in the middle.
- **What breaks if the check isn't there?** If the answer is "nothing we've observed", don't add it. If something real breaks, fix that thing at its cause.

## The model (everything is one of these)

- **A process.** Agents are forked OS processes: half a millisecond, a megabyte or two, isolated, and memory returned on exit. Root holds threads; anything that forks stays single-threaded.
- **A Flow proc.** Every component is a step-fn proc with an id, the same verbs (send, ping, pause, resume, stop, restart via `update!`) and bounded channels. Procs are declared in `resources/seon/system.edn` and started explicitly. Ordering is dataflow.
- **A task.** All work is task entities with `:needs`, a plan and proofs that accumulate. Desired state lives on attributes, and observed state is facts. One React-like reconciler closes the gap, and it costs only what changed. Merge is a task.
- **A value.** State is immutable Datahike values with structural sharing. Each process owns its connection: agents own their branch, and root owns main. Cost follows what changed: tx reports, digests, shared nodes.
- **A render.** Every view, the model's context included, is a render declared by a schema. Renders are incremental, and HTML patches go out through Datastar. UI actions are evals.

## Laws (short; the reasons are in DECISIONS)

- **Contracts on every function.** Malli contracts are armed from the definition, and every contract must generate (`mg/check` runs in `bin/check`).
- **No guards or fences.** Do the right thing. Name every outside-world failure point in `DRAGONS.md`, with its one edge function and its declared error. Nothing else catches.
- **No added caches.** A content-keyed cache is allowed only for a unit measured as expensive.
- **Effects go through owner procs** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is batched,** with a flush window of 10 ms by default. A write that is followed by an effect outside the database waits for its flush.
- **Estimates are proofs.** A function whose input grows carries a cost proof: an overrun, or growth steeper than the work needs, is red like any failing test. Every contracted call is counted (`seon.profile`), and every eval records its costliest functions.
- **Tests run by reach digest:** only tests reaching a change run, and results are shared by digest. Only good tests: real assertions, sensitive to mutation, not timing-only.
- **Use the library's words and mechanisms:** Clojure, core.async.flow, Datahike, Malli, git, nREPL.
- **Procs follow the step-fn contract** (F2's rules, `docs/DECISIONS.md`):
  - the step-fn is a Var with the four arities, and `describe` declares `:params`, `:ins`, `:outs`, an honest `:workload` and a `:ping-map-fn`;
  - `transform` is `(state, in, msg) -> [state' outputs]`, outputs are data, and an effect is an output to the `:io` proc that owns it;
  - a step never blocks on another proc: request/reply is a message out, a pending entry keyed by request id, and a message back;
  - state is plain data under its schema, and a live host object lives only in its owning proc (opened in `init`, released at `::flow/stop`);
  - channel buffers are bounded by their loss semantics;
  - no thread, future, go-loop or atom outside procs, except an edge reader thread for a blocking read that feeds a proc's in-port;
  - the fork server and agents run the same contract in their own single-threaded loop.

## How to work a change

1. **Read** DECISIONS, the owning note, and the code you'll touch. Read the dependency before writing around it.
2. **Estimate** the cost and say what it is proportional to: what N counts, the least work the data needs (one pass? one lookup per row?), how it grows. Write it as a cost proof (`seon.cost/proof`) beside the behaviour proofs; its unit costs come from `seon.cost/units`, never from a run.
3. **Write the proofs** first, red.
4. **Build the simplest form** that makes it green.
5. **Measure** parent against change: alternating runs, release build, bench lock. Keep it only if it's a measured win.
6. **Show it** in the dashboard or the tour if users would care.
7. **Commit** with explicit paths, numbers in the message, and the source cited for anything ported.

## The machine

- **Builds:** run as `nice -n 15 /usr/bin/lockf -k ~/src/seon-jolt/build.lock bin/build`, which pins the patched Chez via `JOLT_CHEZ`.
- **Probes and timings:** probes run under `nice -n 10`. No timed runs while load is above ~20.
- **Hygiene:** kill only your own PIDs, delete only paths you created, install nothing system-wide.
- **Secrets:** never printed. DeepSeek is the default model; check the context before any paid call.

## The bar for "done"

- It runs end to end, through the same path agents use.
- `bin/check` is green.
- Its numbers are in the commit.
- You can watch it work.
