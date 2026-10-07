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

- The forgiving stage segments model text into ordered sources structurally
  (tags unread, no alias resolved): preceding prose becomes each form's
  comment, prompt markers go, delimiters are repaired where unambiguous, and
  trailing prose stays only in the raw reply (`seon.sci.forgive/segments`,
  `src/seon/sci/forgive.clj:333`; `seon.turn/planned-sources` :3397 for a
  reply, `seon.turn/code-sources` :3413 for code). Each form's one semantic
  read happens when the one REPL path evaluates it (`seon.turn/read-row`).
- An agent turn evaluates in its own SCI context, forked per turn from the
  cluster base with its private layer carried over (see below); a host REPL
  expression proves nothing about it. SCI itself provides reusable contexts,
  isolated forks, and interning:
  `reference-code/sci/src/sci/core.cljc:331`, `:345`, `:260`.
- MCP JVM mode evaluates with the host reader at the live `*ns*` and host
  `eval`: no SCI observation and no program facts, but its forms are recorded
  as an ordinary run of the named agent (`seon.turn.batch/jvm-request`). Read
  the complete returned envelope and report that surface explicitly.
- A raw JVM REPL has Clojure's ordinary reader and evaluation behavior:
  `reference-code/clojure/src/clj/clojure/main.clj:368`.

For this wave verify cluster default through `bin/seon status` and MCP
with its default root/cluster selection. The tool still requires the
`code` argument. Do not send a provider request for a loop proof:
PRD §12 requires virtual replies through the ordinary proc.

## Context, private layer, and handles

Installed: `base-ctx` derives the program-only base from one database value,
memoized by program identity (`src/seon/sci/eval.clj:3350`). Each turn,
`fork-for-turn` (`:3320`) forks the current base and, when the agent's previous
context is held, carries the private layer in memory, preserving its context
handle, owned Vars, atoms and result objects (`regenerate-agent-context!`,
`:3246`). **[TARGET]** B2 makes the fork the context, with no regeneration
diff (`docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md` §0). SCI's generation-based isolation
is supplied by `reference-code/sci/src/sci/core.cljc:345`. Private objects
never enter the base or another agent; a JVM restart loses them.
Do not teach a serialization/restoration ladder.

Accepted functions, schemas, and tests persist as program facts.
A `defn` without a Malli contract is refused at installation. A plain
`def` gets the temporary-state note specified in PRD §12.
The note generator is `def-note` (`src/seon/repl.clj:122`).

Results bind actual objects in an evaluation-id map.
`seon.id/evaluation` derives ids from branch, turn, and ordinal;
`seon.id/symbol-in` builds `result/e<id>` handles
(`src/seon/id.clj:67`, `:59`). The evaluation stores shown text
from the value renderer, plus out and error, not the result object.

## History and inspection

System turns store opening and refreshed read evaluations. Before an agent
turn, every distinct read form's latest evidence is checked against changes
since its evaluation `:t` (installed: `system-turn`,
`src/seon/turn.clj:2227`). Generated and agent-written reads participate;
writes and effects never rerun. Compaction wipes evaluations and regenerates
the opening.

The evaluation schema declares its AI/HTML pair; the walk renders
evaluations in order through that pair. `seon.repl/text` is the one
REPL grammar (`src/seon/repl.clj:264`); the render pair enters at
`render-ai` and `render-html` (`:540`, `:565`), and saved shown text renders
unchanged through `value-text` (`:143-147`).

**[TARGET]** `my.turn/evals` and `my.turn/eval` (PRD §15; `src/my/turn.clj`
defines neither):
maps of source, shown text, `:t`, error, and full read evidence.
A missing live object is reported as gone while saved text remains.
The debug prompt preview adds the would-be system turn without writing.

## Probe and reload accurately

Call a JVM private function through its Var, for example
`(#'some.namespace/private-fn request)`. The real dependency probe
uses this form at `test/seon/datahike_fork_test.clj:37`.

A file edit is not a live proof. Reload or adopt the changed definition,
rerun the same form against the same inputs, and name the mechanism
exercised. Re-evaluating a contracted Var replaces its wrapper;
`seon.instrument/apply!` documents re-arming with the supplied projection
(`src/seon/instrument.clj:799-808`). Without
`:seon.instrument/changed-identities` it collects and re-arms the complete
loaded program; name the changed identities when you have them.

For the selected development cluster, the complete JVM form is below.
Choose the namespace to reload; the database supplies both the projection
and instrumentation mode (`projection-from-database`, `src/seon/schema.clj:3196`;
`effective`, `src/seon/config.clj:862`).

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

## A lane works on its own branch

Verified at default's REPL on 2026-09-23. Forms and timings are in
[the landing note](../../../docs/prds/agent-platform/landing/lane-branch-repl-2026-09-23.md).

1. **Name your agent; it gets a branch.** Call `eval_clj` with an `agent` id
   that is new: it gets its own branch off the cluster head on first use,
   recorded as its `:seon.agent/branch`. `my.agent/create-branch!`,
   `my.agent/branches` and `my.agent/retire-branch!` (`src/my/agent.clj`)
   manage lane branches by name; the cluster's own branch and an existing name
   refuse as `:seon.error` values.
2. **Code by evaluating forms there.** Call `eval_clj` with `mode sci` and that
   `agent`. The forms run as an ordinary run of that agent on its branch.
3. **What the branch context is not, today.**
   - Entities are written only by a turn's settlement
     (`analyze-settlement`, `src/seon/turn.clj:1017`; `gate-function-install`,
     `src/seon/turn.clj:3403`).
   - Overriding a declaration with `:seon.fn/host-bound?` true refuses by name
     when called (`src/seon/sci/eval.clj:1722-1737`). An unadmitted class fails
     SCI analysis ("Unable to resolve classname").
4. **Test there.** `seon.test/run` (`src/seon/test.clj:2205`) takes the branch
   handle as `:seon.test/execution` and records on its connection. It runs the
   branch's program entities, so it does not test an in-memory def (measured).
5. **Propose, then root accepts.** Root assigns the task (`seon.task/assign!`);
   the agent writes a deftest naming it by symbol (`^{:seon/tasks #{'ns/task-name}}`) and calls
   `(my.program/propose-merge! {})`, which runs those proofs on its branch.
   Root reads `(my.program/proposals)` and passes one open proposal to
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
6. **Retire.** Call `branch` with `action retire`. It releases the retained
   context (`src/seon/cluster/agent.clj:887`), then calls `retire-branch!`
   (`registry.clj:339`). If an agent still holds the branch, retire refuses.

A branch evaluation that persists entities needs an armed agent whose custody is
the branch. The work then goes through `seon.cluster.agent/submit-source!`
(`src/seon/cluster/agent.clj:663`), the path a model reply uses. Root assigns
the worker its task on that branch with `seon.task/assign!` (`src/seon/task.clj:378`).
