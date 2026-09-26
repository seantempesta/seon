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

- The reply reader turns model text into ordered source forms and reader
  evidence. It preserves source and namespace, attaches preceding prose
  to its form, and retains trailing prose only in the raw reply.
  Current owner: `seon.cluster.reply/sources` (`src/seon/cluster/reply.clj:308`).
- An agent turn evaluates in its own SCI context, forked per turn from the
  cluster base with its private layer carried over (see below); a host REPL
  expression proves nothing about it. SCI itself provides reusable contexts,
  isolated forks, and interning:
  `reference-code/sci/src/sci/core.cljc:331`, `:345`, `:260`.
- MCP JVM mode evaluates on the host prepl, not through a turn.
  Read the complete returned envelope and report that surface explicitly.
  Clojure's prepl owners are `prepl` and `io-prepl`
  (`reference-code/clojure/src/clj/clojure/core/server.clj:194`, `:275`).
- A raw JVM REPL has Clojure's ordinary reader and evaluation behavior:
  `reference-code/clojure/src/clj/clojure/main.clj:368`.

For this wave verify cluster default through `bin/seon status` and MCP
with its default root/cluster selection. The tool still requires the
`code` argument. Do not send a provider request for a loop proof:
PRD §12 requires virtual replies through the ordinary proc.

## Context, private layer, and handles

Installed: `base-ctx` derives the program-only base from one database value,
memoized by program identity (`src/seon/sci/eval.clj:3350`). Each turn,
`fork-for-turn` (`:3156`) forks the current base and, when the agent's previous
context is held, carries the private layer in memory, preserving its context
handle, owned Vars, atoms and result objects (`regenerate-agent-context!`,
`:3082`). **[TARGET]** B2 makes the fork the context, with no regeneration
diff (`docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md` §0). SCI's generation-based isolation
is supplied by `reference-code/sci/src/sci/core.cljc:345`. Private objects
never enter the base or another agent; a JVM restart loses them.
Do not teach a serialization/restoration ladder.

Accepted functions, schemas, and tests persist as program facts.
A `defn` without a Malli contract is refused at installation. A plain
`def` gets the temporary-state note specified in PRD §12.
The note generator is `def-note` (`src/seon/repl.clj:119`).

Results bind actual objects in an evaluation-id map.
`seon.id/evaluation` derives ids from branch, turn, and ordinal;
`seon.id/symbol-in` builds `result/e<id>` handles
(`src/seon/id.clj:67`, `:59`). The evaluation stores shown text
from the value renderer, plus out and error, not the result object.

## History and inspection

System turns store opening and refreshed read evaluations. Before an agent
turn, every distinct read form's latest evidence is checked against changes
since its evaluation `:t` (installed: `system-turn`,
`src/seon/turn.clj:2157`). Generated and agent-written reads participate;
writes and effects never rerun. Compaction wipes evaluations and regenerates
the opening.

The evaluation schema declares its AI/HTML pair; the walk renders
evaluations in order through that pair. `seon.repl/text` is the one
REPL grammar (`src/seon/repl.clj:264`); the render pair enters at
`render-ai` and `render-html` (`:449`, `:474`), and saved shown text renders
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
and instrumentation mode (`projection-from-database`, `src/seon/schema.clj:3113`;
`effective`, `src/seon/config.clj:819`).

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [configuration (seon.config/effective database "default")]
       (require 'seon.oversight :reload)
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

1. **Name your branch.** Call the MCP `branch` tool with `action create` and a
   `name`. It branches off the cluster head's commit through
   `seon.cluster.registry/branch!` (`src/seon/cluster/registry.clj:192`) and is
   built in `script/seon/dev/mcp.clj:478` (`branch-form`). There is no default
   name. These refuse as `:seon.error` values: the cluster's own branch, and a
   name that already exists.
2. **Code by evaluating forms there.** Call `eval_clj` with `mode sci` and
   `branch <name>`. The form runs as an ordinary agent run of the branch's
   hosted agent `mcp/<name>` (`run-form`, `script/seon/dev/mcp.clj:395-438`).
3. **What the branch context is not, today.**
   - Rows are written only by a turn's settlement
     (`analyze-settlement`, `src/seon/turn.clj:967`; `gate-function-install`,
     `src/seon/turn.clj:3328`).
   - Overriding a declaration with `:seon.fn/host-bound?` true refuses by name
     when called (`src/seon/sci/eval.clj:1722-1737`). An unadmitted class fails
     SCI analysis ("Unable to resolve classname").
4. **Test there.** `seon.test/run` (`src/seon/test.clj:2188`) takes the branch
   handle as `:seon.test/execution` and records on its connection. It runs the
   branch's program rows, so it does not test an in-memory def (measured).
5. **Accept.** Accepting work is D1 §2c's merge:
   root's `my.program/prepare-merge!`, `accept-merge!` and `write-back!`
   (`src/my/program.clj`, over `seon.cluster.source`). They merge the branch's
   changed program rows through the tested head; the basis is each merged row's
   definition digest, so other writes never stale a merge or its write-back.
   Private SCI defs never merge. Write-back splices the accepted declarations
   into their one file; it refuses `:not-analyzable` when a declaration names a
   namespace its file does not require (fully qualify only required namespaces).
   Host-bound declarations always go through the files.
6. **Retire.** Call `branch` with `action retire`. It releases the retained
   context (`src/seon/cluster/agent.clj:887`), then calls `retire-branch!`
   (`registry.clj:339`). If an agent still holds the branch, retire refuses.

A branch evaluation that persists rows needs an armed agent whose custody is
the branch. The work then goes through `seon.cluster.agent/submit-source!`
(`src/seon/cluster/agent.clj:660`), the path a model reply uses. A worker is
created on a candidate by `seon.issue/start!` (`src/seon/issue.clj:1506`).
