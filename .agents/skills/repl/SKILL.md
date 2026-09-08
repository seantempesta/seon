---
name: repl
description: "Distinguish the agent reply reader, persistent agent SCI context, MCP JVM evaluation, and raw JVM REPL. Use for source fidelity, read evidence, result inspection, private state, or reload verification."
---

# REPL surfaces and durable observations

Use [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§10 for lane rules and §13–§15 for the target. Later sections supersede
fresh forks per turn, restored defs, and serialized results. The
[agent runtime](../../../docs/seon/architecture/agent-runtime.md) diagrams
show the additive context and since-query diff.

## Name the surface before making a claim

- The reply reader turns model text into ordered source forms and reader
  evidence. It preserves source and namespace, attaches preceding prose
  to its form, and retains trailing prose only in the raw reply.
  Current owner: `src/seon/cluster/reply.clj:360`.
- An agent turn uses its own persistent SCI context. This is the §14
  target, not a property proved by a host REPL expression. SCI itself
  provides reusable contexts, isolated forks, and interning:
  `reference-code/sci/src/sci/core.cljc:330`, `:345`, `:260`.
- MCP JVM mode evaluates on the host prepl, not through a turn.
  Read the complete returned envelope and report that surface explicitly.
  Clojure's prepl owner is
  `reference-code/clojure/src/clj/clojure/core/server.clj:228`.
- A raw JVM REPL has Clojure's ordinary reader and evaluation behavior:
  `reference-code/clojure/src/clj/clojure/main.clj:368`.

For this wave verify cluster default through `bin/seon status` and MCP
with its default root/cluster selection. The tool still requires the
`code` argument. Do not send a provider request for a loop proof:
PRD §12 requires virtual replies through the ordinary proc.

## Persistent context, private layer, and handles — target

Fork the cluster base once for each agent. Preserve that live context
between turns and intern accepted base diffs into it. Private defs and
atoms retain object identity; they never enter the base or another agent.
A JVM restart loses them. Do not teach a serialization/restoration ladder.

Accepted functions, schemas, and tests persist as program facts.
A `defn` without a Malli contract is refused at installation. A plain
`def` gets the temporary-state note specified in PRD §12.
The note generator currently lives at `src/seon/repl.clj:52`; its
presence does not prove the turn/private-state target has landed.

Results bind actual objects in an evaluation-id map.
`seon.id/evaluation` derives ids from branch, turn, and ordinal;
`seon.id/symbol-in` builds `result/e<id>` handles
(`src/seon/id.clj:49`, `:42`). The evaluation stores shown text
from the value renderer, plus out and error, not the result object.

## History and inspection — target

System turns store opening and refreshed read evaluations. Before an agent
turn, every distinct read form's latest evidence is checked against changes
since its evaluation `:t`. Generated and agent-written reads participate;
writes and effects never rerun. Compaction wipes evaluations and regenerates
the opening.

The evaluation schema declares its AI/HTML pair; the walk renders
evaluations in order through that pair. `seon.repl/text` is the one
REPL grammar (`src/seon/repl.clj:246`); render functions currently enter
at `:315` and `:323`. Do not infer stored-shown-text support from those
entry points alone.

`my.turn/evals` and `my.turn/eval` are the §15 inspection target:
maps of source, shown text, `:t`, error, and full read evidence.
A missing live object is reported as gone while saved text remains.
The debug prompt preview adds the would-be system turn without writing.

## Probe and reload accurately

Call a JVM private function through its Var, for example
`(#'some.namespace/private-fn request)`. The real dependency probe
uses this form at `test/seon/datahike_fork_test.clj:31`.

A file edit is not a live proof. Reload or adopt the changed definition,
rerun the same form against the same inputs, and name the mechanism
exercised. Re-evaluating a contracted Var replaces its wrapper;
`seon.instrument/apply!` documents re-arming with the supplied projection
at `src/seon/instrument.clj:685`.

For the selected development cluster, the complete JVM form is below.
Choose the namespace to reload; the database supplies both the projection
and instrumentation mode (`src/seon/schema.clj:930`, `src/seon/config.clj:533`).

```clojure
(let [connection (seon.operator/connection "default")
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
