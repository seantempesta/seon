---
name: repl
description: "Distinguish and probe Seon's agent-reply reader, an agent turn in its fresh SCI fork with the agent's defs restored, a cluster io-prepl/MCP eval_clj session, and a raw JVM REPL. Use for reply parsing, prose-vs-code classification, Markdown fences, reader refusals, persistence of the agent's defs, source fidelity, namespace attribution, private-Var probes, or reload-before-retest work. Do not load it merely for ordinary Clojure syntax or application code that happens to be evaluated at a REPL."
---

# REPL — distinguish the four surfaces

Four surfaces share Clojure syntax but not an execution contract:

- **The Seon agent-form reader** splits a model's text reply into ordered form
  source strings. This is what this skill is mostly about. Fresh Seon uses
  `seon.cluster.reply/sources` over `seon.sci.reader/read`; deleted reader
  implementations are Git-history quarry and are not on this path
  (`src/seon/cluster/reply.clj:1-48,310-389`).
- **An agent turn in SCI** executes frozen sources through
  `seon.sci.eval/evaluate`. Each turn gets a fresh generation-aware fork of the
  cluster's program-only base, then rehydrates only the selected agent's defs.
  Every form in that turn shares the fork; the next turn forks the then-current
  base again (`src/seon/sci/eval.clj:1509-1693`;
  `src/seon/cluster/loop.clj:1-1706`).
- **Cluster `io-prepl` / MCP `eval_clj`** sends a form to the live cluster
  JVM's `clojure.core.server/io-prepl`. It reads, evaluates, and returns a
  structured envelope; a bare value evaluates normally, and the agent-reply
  prose classifier is absent (`src/seon/cluster.clj:176-390`;
  `reference-code/clojure/src/clj/clojure/core/server.clj:228-296`;
  `script/seon/dev/mcp.clj:305-665`).
- **A raw `clojure -M:dev` JVM REPL** is Clojure's ordinary
  read-eval-print loop. Bare values evaluate and print, and there is no Seon
  repair layer (`reference-code/clojure/src/clj/clojure/main.clj:368-467`).

If a generic REPL probe behaves differently from an agent turn, that is not a
contradiction; first name which surface you are on.

## Operating clusters from a REPL session

`seon.operator` is the sanctioned control surface on the `io-prepl`/`eval_clj`
jvm surface. Its ordinary functions include `start!`, `stop!`, `restart!`,
`status`, `banner`, `clusters`, `publish!`, and `refork!`; readiness output is
derived per call, never stored (`src/seon/operator.clj`; live verification:
`(seon.operator/clusters)` returns the current advertisement and branch
census). `start!` REFUSES a running name
with a flat `:seon.boot/refused` error rather than implicitly halting, and a
failed boot deliberately leaves the degraded instance up for diagnosis. There
is no `reset` verb: var-level hot reload is automatic, and destructive refork
is the explicit `refork!`. Terminal attach is `rlwrap nc` against the
advertised prepl port (the namespace docstring documents the flow); no nREPL
server exists.

### Prove the agent session boundary

Use an actual agent turn when the claim concerns the SCI evaluation context,
terminal receipt, contracted program publication, or the agent's defs. A
direct `io-prepl` form proves only host-JVM evaluation; it never passes through
the agent reply reader or the turn's terminal transaction
(`reference-code/clojure/src/clj/clojure/core/server.clj:228-296`;
`src/seon/cluster/loop.clj:1-1706`).

For the full split between program rows, base context, per-turn fork, and
agent-scoped defs, read
[`program-state.md`](../data-oriented-clojure/references/program-state.md).

An evaluation's namespace precedence is explicit form namespace → committed
agent assignment → `user`. `agent-namespace` queries
`:seon.cluster.agent/namespace`; it never reconstructs `my.agents.<id>`
(`src/seon/sci/eval.clj:271-295,1540-1693`;
`test/seon/sci/eval_test.clj:1-1989`). A successful contracted `defn`
returns SCI's Var value and admits as the same `:seon.print/var` face as `def`,
rendered `#'namespace/name` (`src/seon/sci/eval.clj:1695-1857`;
`test/seon/sci/eval_test.clj:1-1989`). An untriaged failed receipt renders in
both transcript projections as a Clojure execution-error face; triage data,
when present, remains the receipt's own error presentation
(`src/seon/render/transcript.clj:1-996`;
`test/seon/render/transcript_test.clj:1-893`).

## The agent-reply surface

The one SCI reader returns ordered events with exact source spans. It rejects
`#=` and unknown tags, returns flat error values for malformed input, and
tracks the namespace in effect while reading
(`src/seon/sci/reader.cljc:28-116,296-405`).

`seon.cluster.reply/sources` then decides which events are code:

- Structured top-level lists, vectors, maps, and sets are reply forms.
- A bare symbol is a reply form only when it occupies its own source line and
  the reply also contains structured code. This includes a trailing standalone
  symbol that a human might have intended as prose.
- Other text becomes single-`;` source comments attached to a form — the one
  it precedes, or for a trailing span the one it follows. This is an internal
  parser representation of agent-written input, never a displayed result.
- Markdown fence lines are stripped before reading because backticks otherwise
  read as plausible symbols.

EVERY REPLY SOURCE THAT IS EXECUTED CARRIES A READER EVENT. Prose alone is
never an executable form source: a comment-only source has no event, so no
`:seon.cluster.eval` receipt is started for it. The turn still durably commits
the raw reply and its empty source list, then settles a typed `::no-forms`
refusal; prose cannot leave an unsettled form. The 105-forms/102-receipts gap
of 2026-08-08 is historical evidence from before this intent and refusal
settlement path, when deepseek-v4-flash chat-template control markup
(`<assistant1>`, `<｜｜DSML｜｜AgentThoughts>…`) arrived in the completion's
`content` field and read as prose
(`docs/seon/issues/a-runs-last-form-can-close-without-a-receipt.md`).

Those classifications and the exact-source return contract are current at
`src/seon/cluster/reply.clj:20-60,155-268,330-389`. The reader-facing
`sources` function reports unbalanced or malformed code as
`:seon.cluster.reply/unreadable`; the turn loop then makes one bounded
delimiter-repair pass over isolated `:unclosed` or `:stray-closer` spans with
Parinfer indent mode, accepting a candidate only after the SCI reader confirms
it (`src/seon/cluster/loop.clj:72-144,1456-1469`). A reply with no code —
empty, or whole-text prose — returns `:seon.cluster.reply/no-forms` carrying
that text (`src/seon/cluster/reply.clj:330-389`).

Practical rule: write code as ordinary balanced Clojure. Agent-written source
may use comments for thinking preserved beside a form. **[TARGET — owner
decision 11]** Displayed REPL content is the form followed by its actual
computed value—never a comment-only pseudo-result, a `;; =>` annotation, or
prose framed as comments. Current comment-output owners are recorded under the
strict REPL display wave in `docs/seon/issues/index.md`; do not mistake those
known implementation defects for the display contract. The repair pass handles
only isolated reader delimiter errors; it does not infer arbitrary missing code
or repair unrelated malformed syntax
(`src/seon/cluster/reply.clj:20-48,210-244,310-355`;
`docs/prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md`,
decision 11).

## Probing a live or raw JVM

Use one form, then read the whole returned envelope. `io-prepl` distinguishes
`:ret`, output, tap, namespace, timing, and exception data
(`reference-code/clojure/src/clj/clojure/core/server.clj:228-296`).

### Call an internal/private var

Var-quote bypasses public resolution and gives the Var itself; invoke it in
function position:

```clojure
;; Illustrative private-Var call; `db` and `clauses` are prepared by the
;; planner probe.
(#'datahike.query/create-plan-via-ir db clauses #{} nil nil)
```

This is the exact planner probe retained by Seon
(`test/seon/datahike_fork_test.clj:31-33`). For a private atom, remember that
`@#'ns/private-atom` yields the atom and `@@#'ns/private-atom` yields its
contents; the observed trap is recorded in
`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md`
§6.

### Reload before rerunning the same probe

After editing a namespace, load the edited definition into the JVM before
claiming the probe still fails:

```clojure
;; Illustrative continuation of the same prepared planner probe.
(require 'datahike.query :reload)
(#'datahike.query/create-plan-via-ir db clauses #{} nil nil)
```

`:reload` forces the named lib to load again; `:reload-all` also reloads libs
it loads directly or indirectly
(`reference-code/clojure/src/clj/clojure/core.clj:6149-6205`). Rerun the exact
same form against the same immutable inputs so the before/after comparison
changes only the edited code. The planner repair used this sequence
(`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Evidence”).

When the edited host Var is a contracted Seon public function, re-evaluating
its `defn` also replaces the Malli wrapper. Run `seon.instrument/apply!` after
loading the definition and before repeating the probe. The call must receive
the handed schema projection; in a cluster probe use the projection state and
pass the projection explicitly:

```clojure
(schema/call-with-projection-state
 projection-state
 (fn []
   (let [projection (schema/handed-projection)]
     (seon.instrument/apply!
      {:seon.config/on-core-error :panic
       :seon.schema/projection projection}))))
```

A bare `apply!` refuses before collecting contracts when no projection is
handed, avoiding a resource reread (`src/seon/instrument.clj:539-622`).
The operation is idempotent.

For a running flow proc whose step function is stored as a Var, re-evaluating
the `defn` updates the next step without rebuilding topology
(`src/seon/flow.clj:123-164`;
`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md`
§4). Reloading is evidence only after the re-run; the edit on disk alone does
not change an already-running JVM.

That live Var update is not database program-graph indexing. File or
schema-resource edits do not change a cluster's `:seon.fn`, `:seon.ns`,
`:seon.schema`, or `:seon.test` facts. The edit hook statically publishes safe
changes to the one `:current-src` branch and selects a complete rebuild for
structural changes; existing clusters never synchronize. `bin/seon init
CLUSTER --force` destroys and reforks that branch from the published commit.
A REPL proof after an edit must say whether it proves only the loaded Var or a
cluster forked from the newly published commit (`AGENTS.md`, “Hot reload is
not program-graph indexing”).

## Fast diagnosis

| Symptom | Surface and next move |
|---|---|
| Reply became prose or the wrong forms | Agent reply: call `seon.cluster.reply/sources` with the actual run/form namespace or the result of `seon.sci.eval/agent-namespace`. |
| `:seon.cluster.reply/unreadable` | Agent reply: the turn may repair one isolated delimiter span; other malformed Clojure remains a typed refusal. |
| A def is live now but missing after restart | Agent turn: inspect its terminal receipt plus `:seon.def` row, then cold-acquire a fresh cluster context (`src/seon/cluster/loop.clj:1-1706`; `src/seon/sci/eval.clj:1270-1693`). |
| Bare map/keyword evaluates and prints | Expected in `io-prepl` and raw JVM REPLs. |
| A private function is unresolved | JVM probe: invoke `#'fully.qualified.ns/var`. |
| The same old result appears after an edit | Reload/re-evaluate the owning namespace, then rerun the identical probe. |
