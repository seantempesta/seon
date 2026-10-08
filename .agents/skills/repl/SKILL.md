---
name: repl
description: "Distinguish the agent reply reader, persistent agent SCI context, MCP JVM evaluation, and raw JVM REPL. Use for source fidelity, read evidence, result inspection, private state, or reload verification."
---

# REPL surfaces and durable observations

Use [root instructions](../../../AGENTS.md) and
[the active plan §6](../../../docs/prds/agent-platform/plan/README.md#6-implementation-proof-and-recovery)
for lane verification cadence. Use [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 for the target. Later sections supersede
fresh forks per turn, restored defs, and serialized results. The
[agent runtime](../../../docs/seon/architecture/agent-runtime.md) diagrams
show the additive context and since-query diff.

## Name the surface before making a claim

- The forgiving stage is one linear scan (`seon.sci.forgive/forgive`, no
  read, no alias resolved). In a model reply, outside a code fence and an open
  form, a line is code when it starts a form at column zero or is one literal
  or qualified symbol; other lines become `;` comments, fence lines go, and
  words after a form on its line become a comment. In both modes an
  unbalanced text is repaired (a stray closer dropped, missing closers
  inserted before the next column-zero line), each change a
  `:seon.batch/repair`; balanced text passes through byte for byte.
  `seon.sci.forgive/segments` turns the scan's spans into ordered sources with
  preceding prose as each one's comment (`seon.turn/planned-sources` for a
  reply, `seon.turn/code-sources` for code); the repairs are not yet delivered
  beside the values. Each form's one semantic read happens when the one REPL
  path evaluates it (`seon.turn/read-row`).
- An agent turn evaluates in its own SCI context, forked from the cluster
  base with its private layer carried over (see below); a host REPL
  expression proves nothing about it. SCI itself provides reusable contexts,
  isolated forks, and interning (`init`, `fork`, `intern`:
  `reference-code/sci/src/sci/core.cljc:320`, `:334`, `:260`).
- MCP JVM mode evaluates with the host reader at the live `*ns*` and host
  `eval`: no SCI observation and no program facts, but its forms are recorded
  as an ordinary run of the named agent (`seon.turn.batch/jvm-request`). Read
  the complete returned envelope and report that surface explicitly.
- A raw JVM REPL has Clojure's ordinary reader and evaluation behavior:
  `reference-code/clojure/src/clj/clojure/main.clj:368`.

For this wave verify cluster default through `bin/seon status` and MCP
with its default root/cluster selection. The tool still requires the
`code` argument. Do not send a provider request for a loop proof:
use virtual replies through the ordinary proc (turn PRD §12).

## Context, private layer, and handles

Installed: `seon.sci.eval/base-ctx` derives the program-only base from one
database value, memoized by program identity. `seon.sci.eval/fork-for-turn`
forks the current base and, when the agent's previous context is held, carries
the private layer in memory (`regenerate-agent-context!`), preserving its owned
Vars, atoms and result objects; inherited program bindings are replaced.
**[TARGET]** the fork becomes the context, with no regeneration diff
(`docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md` §1). SCI's
generation-based isolation is supplied by `sci/fork`
(`reference-code/sci/src/sci/core.cljc:334`). Private objects never enter the
base or another agent; a JVM restart loses them.
Do not teach a serialization/restoration ladder.

Accepted functions, schemas, and tests persist as program facts.
The REPL admits a `defn` without a Malli contract (CLAUDE.md: it returns with
a warning); the merge refuses it (`my.program/propose-merge!` over
`seon.cluster.source`: every carried function declares a complete
`:malli/schema`). A plain `def` gets the temporary-state note from `def-note`
(`src/seon/repl.clj`).

Results bind actual objects in an evaluation-id map.
`seon.id/evaluation` derives ids from branch, turn, and ordinal;
`seon.id/symbol-in` builds `result/e<id>` handles (`src/seon/id.clj`). The evaluation stores shown text
from the value renderer, plus out and error, not the result object.

## History and inspection

Installed: `seon.turn/system-turn` projects the declared opening and every
distinct retained read form before an agent turn, and a distinct read whose
evidence is no longer current (`seon.db/read-evidence-current?`) is evaluated
again; with `write?` it saves them as one closed system turn with no provider
attempt. Generated and agent-written reads participate; writes and effects never
rerun. `seon.turn/compact!` retracts an idle agent's evaluations; the next
system walk is fresh.

The evaluation schema declares its AI/HTML pair; the walk renders
evaluations in order through that pair. `seon.repl/text` is the one
REPL grammar (`src/seon/repl.clj`); the render pair enters at
`render-ai` and `render-html`, and saved shown text renders unchanged
through `value-text`.

**[TARGET]** `my.turn/evals` and `my.turn/eval` (PRD §15; `src/my/turn.clj`
defines neither):
maps of source, shown text, `:t`, error, and full read evidence.
A missing live object is reported as gone while saved text remains.

## Probe and reload accurately

Call a JVM private function through its Var, for example
`(#'some.namespace/private-fn request)`. The real dependency probe
uses this form at `test/seon/datahike_fork_test.clj:39`.

A file edit is not a live proof. Reload or adopt the changed definition,
rerun the same form against the same inputs, and name the mechanism
exercised. Re-evaluating a contracted Var replaces its wrapper;
`seon.instrument/apply!` documents re-arming with the supplied projection
(`src/seon/instrument.clj`). Without
`:seon.instrument/changed-identities` it collects and re-arms the complete
loaded program; name the changed identities when you have them.

For the selected development cluster, the complete JVM form is below.
Choose the namespace to reload; the database supplies both the projection
and instrumentation mode (`seon.schema/projection-from-database`,
`seon.config/effective`).

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [configuration (seon.config/effective database "default")]
       (require 'seon.health :reload)
       (seon.instrument/apply!
        {:seon.config/on-core-error (:seon.config/on-core-error configuration)
         :seon.schema/projection projection})))))
```

Use actual agent turns for persistence, isolation, publication, or
outcome-storage claims. HTTP reachability and a successful host eval
prove neither those behaviors nor browser repaint.

## An agent works on its own branch

Forms and timings are in [the landing note](../../../docs/prds/agent-platform/landing/lane-branch-repl-2026-09-23.md).

1. **Name your agent; it gets a branch.** Call `eval_clj` with an `agent` id
   that is new: it gets its own branch off main on first use,
   recorded as its `:seon.agent/branch`. `my.agent/create-branch!`,
   `my.agent/branches` and `my.agent/retire-branch!` (`src/my/agent.clj`)
   manage branches by name; main and an existing name
   refuse as `:seon.error` values.
2. **Code by evaluating forms there.** Call `eval_clj` with `mode sci` and that
   `agent`. The forms run as an ordinary run of that agent on its branch.
3. **What the branch context is not, today.**
   - Entities are written only by a turn's settlement (`seon.turn/settle!`).
   - A host-bound declaration SCI cannot interpret keeps its compiled Var, and
     with none it refuses by name when called (`seon.sci.eval`, the
     interpretation-failure branch of acquisition).
4. **Test there.** `seon.test/run` takes the branch
   handle as `:seon.test/execution` and records on its connection. It runs the
   branch's program entities.
5. **Request a merge, then root merges.** Root assigns the task (`seon.task/assign!`);
   the agent writes a deftest naming it by symbol (`^{:seon/tasks #{'ns/task-name}}`) and calls
   `(my.program/propose-merge! {})`, which runs those proofs on its branch.
   Root reads `(my.program/proposals)` and passes one open merge request to
   `my.program/prepare-merge!`, then the answer to `accept-merge!` (which
   closes the merge task) and that answer to `write-back!`
   (`src/my/program.clj`, over `seon.cluster.source`). Only the proofs gate
   acceptance; other reaching tests' reds are shown as `:seon.source/red`. They merge the branch's
   changed program entities through the tested head; the basis is each merged entity's
   definition digest, so other writes never stale a merge or its write-back.
   Private SCI defs never merge. Write-back splices the accepted declarations
   into their one file; it refuses `:not-analyzable` when a declaration names a
   namespace its file does not require (fully qualify only required namespaces).
   Host-bound declarations always go through the files.
6. **Delete the branch.** Call `my.agent/retire-branch!` (`src/my/agent.clj`). It
   releases the retained contexts (`seon.cluster.agent/release-context!`), then
   deletes the branch (`seon.cluster.registry/retire-branch!`). If an agent
   still holds the branch, it refuses; your own branch records its
   retirement now and is deleted once your run exits.

A branch evaluation that persists entities needs an armed agent whose custody is
the branch. The work then goes through `seon.cluster.agent/submit-source!`, the path a model reply uses. Root assigns
the worker its task on that branch with `seon.task/assign!`.
