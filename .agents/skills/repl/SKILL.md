---
name: repl
description: "Distinguish the agent reply reader, persistent agent SCI context, MCP JVM evaluation, and raw JVM REPL. Use for source fidelity, read evidence, result inspection, private state, or reload verification."
---

# REPL surfaces and durable observations

Use [root instructions](../../../AGENTS.md) for lane verification and
[agent-loop.md](../../../docs/seon/specs/agent-loop.md) for the one REPL path and
[TASK-CHAIN](../../../docs/seon/schedule/cards/TASK-CHAIN.md) for the task lifecycle (the target).
The [ledger](../../../docs/seon/schedule/agent-loop-ledger.md) names what is
installed. Context is a static prefix, the agent's own forms and results, and
one fresh current-state block; older turn PRDs are historical research.

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
  reply, `seon.turn/code-sources` for code). Repairs are delivered beside the
  values as `:seon.repl/repairs` (`seon.repl/response-entries`). Each form's one semantic read happens when the one REPL
  path evaluates it (`seon.turn/read-row`).
- An agent turn evaluates in its own SCI context, forked from the cluster
  base with its private layer carried over (see below); a host REPL
  expression proves nothing about it. SCI itself provides reusable contexts,
  isolated forks, and interning (`sci.core/init`, `sci.core/fork`,
  `sci.core/intern` in `reference-code/sci/src/sci/core.cljc`).
- MCP JVM mode evaluates with the host reader at the live `*ns*` and host
  `eval`: no SCI observation and no program facts, but its forms are recorded
  as an ordinary run of the named agent (`seon.turn.batch/jvm-request`). Read
  the complete returned reply and report that surface explicitly.
- A raw JVM REPL has Clojure's ordinary reader and evaluation behavior:
  `clojure.main/repl` (`reference-code/clojure/src/clj/clojure/main.clj`).

For this wave verify cluster default through `bin/seon status` and MCP
with its default root/cluster selection. The tool still requires the
`code` argument. Do not send a provider request for a loop proof:
use virtual replies through the ordinary proc (turn PRD §12).

## Context, private layer, and handles

Installed: `seon.sci.eval/program-context` derives the frozen program context
from one database value, one `seon.cache` entry of kind
`:seon.sci.eval/program-context` per program content; `seon.sci.eval/base-ctx`
forks it. `seon.sci.eval/fork-for-turn`
forks the current base and, when the agent's previous context is held, carries
the private layer in memory (the private `regenerate-agent-context!`), preserving its owned
Vars, atoms and result objects; inherited program bindings are replaced.
**[TARGET]** the fork becomes the context, with no regeneration diff
(`docs/prds/agent-platform/plan/lane-b2-walk-flow-fork.md` §1). SCI's
generation-based isolation is supplied by `sci.core/fork`
(`reference-code/sci/src/sci/core.cljc`). Private objects never enter the
base or another agent; a JVM restart loses them.
Do not teach a serialization/restoration ladder.

Accepted functions, schemas, and tests persist as program facts.
The REPL admits a `defn` without a Malli contract (CLAUDE.md: it returns with
a warning); the merge refuses it (`seon.cluster.source/accept-merge!`: every carried function declares a complete
`:malli/schema`). A plain `def` gets the temporary-state note from the private `seon.repl/def-note`.

Results bind actual objects in an evaluation-id map.
`seon.id/evaluation` derives ids from branch, turn, and ordinal;
`seon.id/symbol-in` builds `result/e<id>` handles. The evaluation stores the printed value
from the value renderer, plus out and error, not the result object.

## History and inspection

Read evidence is captured by `seon.db/read-evidence` and judged by
`seon.db/read-evidence-current?`.

The evaluation schema declares its AI/HTML pair. `seon.repl/text` is the one
REPL grammar; the render pair enters at
`render-ai` and `render-html`, and the saved printed value renders unchanged
through `value-text`.

**[TARGET]** `my.turn/evals` and `my.turn/eval` (PRD §15; the `my.turn` namespace
defines neither):
maps of source, printed value, `:t`, error, and full read evidence.
A missing live object is reported as gone while saved text remains.

## Probe and reload accurately

Call a JVM private function through its Var, for example
`(#'some.namespace/private-fn request)`. The real dependency probe
uses this form in `create-plan` of `test/seon/datahike_fork_test.clj`
(`(#'query/create-plan-via-ir ...)`).

A file edit is not a live proof. Reload the changed definition,
rerun the same form against the same inputs, and name the mechanism
exercised. Re-evaluating a contracted Var replaces its wrapper;
`seon.instrument/apply!` documents re-instrumenting with the supplied registry. Without
`:seon.instrument/changed-identities` it collects and re-instruments the complete
loaded program; name the changed identities when you have them.

For the selected development cluster, the JVM form below has the request shape `seon.test` passes to
`seon.instrument/apply!`. Choose the namespace to reload; the database supplies the registry and the admission caps
(`seon.schema/projection-from-database`, `seon.config/effective`, `seon.config/result-caps`).

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)
      configuration (seon.config/effective database "default")]
  (seon.schema/call-with-projection
   projection
   (fn []
     (require 'seon.health :reload)
     (seon.instrument/apply!
      {:seon.sci.admit/caps (seon.config/result-caps configuration)
       :seon.schema/projection projection
       :seon.db/db database}))))
```

Use actual agent turns for persistence, isolation, publication, or
outcome-storage claims. HTTP reachability and a successful host eval
prove neither those behaviors nor browser repaint.

## An agent works on its own branch

Forms and timings are in the branch REPL note, `docs/prds/agent-platform/landing/lane-branch-repl-2026-09-23.md`.

1. **Name your agent; it gets a branch.** Call `eval_clj` with an `agent` id
   that is new: it gets its own branch off main on first use,
   recorded as its `:seon.agent/branch`. `my.agent/create-branch!`,
   `my.agent/branches` and `my.agent/retire-branch!`
   manage branches by name; main and an existing name
   refuse as `:seon.error` values.
2. **Code by evaluating forms there.** Call `eval_clj` with `mode sci` and that
   `agent`. The forms run as an ordinary run of that agent on its branch.
3. **What the branch context is not, today.**
   - Entities are written only by the run's terminal writer (`seon.turn/settle!`).
   - A host-bound declaration SCI cannot interpret keeps its compiled Var, and
     with none it refuses by name when called (`seon.sci.eval`, the
     interpretation-failure branch of acquisition).
4. **Test there.** `seon.test/run` takes the branch
   handle as `:seon.test/execution` and records on its connection. It runs the
   branch's program entities.
5. **A done chain is merged by root.** A task's proofs are its `:seon.task/proofs` field. The agent makes them
   green on its branch; its turn proposes `:done`, root re-judges it (TASK-CHAIN §2.4), and the chain's last task
   merges the branch to main once (§2.6). Installed until S3b-1: root assigns the task (`seon.task/assign!`), the
   agent names its proofs with `:seon/tasks` metadata (until S2c-2), and a merge request is the assignment:
   root reads `(my.root/merge-requests)` and merges one with `(my.root/merge! {:seon.task/assignment key})`
   (`seon.merge/merge!`: one transaction from the merge base, the facts inside main's writer and, when the branch
   changed declarations, the declarations prepared and accepted on a merge branch; the assignment closes
   `:merged` in it; then write-back and one git commit), or closes it with `my.root/close!` and a reason. The
   proofs gate acceptance; other reaching tests' reds are shown as `:seon.source/red`. The merge takes the branch's
   changed program entities through the tested head; the basis is each merged entity's
   definition digest, so other writes never stale a merge or its write-back.
   Private SCI defs never merge. Write-back splices the accepted declarations
   into their one file; it refuses `:not-analyzable` when a declaration names a
   namespace its file does not require (fully qualify only required namespaces).
   Host-bound declarations always go through the files.
6. **Delete the branch.** Call `my.agent/retire-branch!`. It
   releases the retained contexts (`seon.cluster.agent/release-context!`), then
   deletes the branch (`seon.cluster.registry/retire-branch!`). If an agent
   still holds the branch, it refuses; your own branch records its
   retirement now and is deleted once your run exits.

A branch evaluation that persists entities needs an instrumented agent whose checked-out branch is
the branch. The work then goes through `seon.cluster.agent/submit-source!`, the path a model reply uses. Root gives
the worker its task on that branch (installed: `seon.task/assign!`; target: `seon.task/chain!`, TASK-CHAIN §2.1).
