---
name: repl
description: "Distinguish and probe Seon's agent-reply reader, a live cluster io-prepl/MCP eval_clj session, and a raw JVM REPL. Use for reply parsing, prose-vs-code classification, Markdown fences, reader refusals, source fidelity, namespace attribution, private-Var probes, or reload-before-retest work. Do not load it merely for ordinary Clojure syntax or application code that happens to be evaluated at a REPL."
---

# REPL — distinguish the three surfaces

Three surfaces share Clojure syntax but not a reader contract:

- **The Seon agent-form reader** splits a model's text reply into ordered plan
  source strings. This is what this skill is mostly about. Fresh Seon uses
  `seon.cluster.reply/sources` over `seon.sci.reader/read`; the retired
  `src-old/seon/repl/parse.cljc` repair system is not on this path
  (`src/seon/cluster/reply.cljc:1-48,306-348`).
- **Cluster `io-prepl` / MCP `eval_clj`** sends a form to the live cluster
  JVM's `clojure.core.server/io-prepl`. It reads, evaluates, and returns a
  structured envelope; a bare value evaluates normally, and the agent-reply
  prose classifier is absent (`src/seon/cluster.clj:926-986`;
  `reference-code/clojure/src/clj/clojure/core/server.clj:228-296`;
  `script/seon/dev/mcp.clj:532-548`).
- **A raw `clojure -M:dev` JVM REPL** is Clojure's ordinary
  read-eval-print loop. Bare values evaluate and print, and there is no Seon
  repair layer (`reference-code/clojure/src/clj/clojure/main.clj:368-467`).

If a generic REPL probe behaves differently from an agent reply, that is not a
contradiction; first name which surface you are on.

## The agent-reply surface

The one SCI reader returns ordered events with exact source spans. It rejects
`#=` and unknown tags, returns flat error values for malformed input, and
tracks the namespace in effect while reading
(`src/seon/sci/reader.cljc:28-116,296-405`).

`seon.cluster.reply/sources` then decides which events are code:

- Structured top-level lists, vectors, maps, and sets are plan forms.
- A bare symbol is a plan form only when it occupies its own source line and
  the reply also contains structured code. This includes a trailing standalone
  symbol that a human might have intended as prose.
- Other text becomes single-`;` source comments attached to the next form;
  trailing or pure prose becomes a comment-only source.
- Markdown fence lines are stripped before reading because backticks otherwise
  read as plausible symbols.

Those classifications and the exact-source return contract are current at
`src/seon/cluster/reply.cljc:20-48,143-240,306-348`. There is no delimiter
auto-repair in this path. Unbalanced or malformed code returns
`:seon.cluster.reply/unreadable`; an empty reply returns
`:seon.cluster.reply/no-forms` (`src/seon/cluster/reply.cljc:306-348`).

Practical rule: write code as ordinary balanced Clojure. Use single-`;`
comments for prose you intentionally want preserved beside a form, and do not
expect parinfer or a repair pass to guess missing delimiters.

## Probing a live or raw JVM

Use one form, then read the whole returned envelope. `io-prepl` distinguishes
`:ret`, output, tap, namespace, timing, and exception data
(`reference-code/clojure/src/clj/clojure/core/server.clj:228-296`).

### Call an internal/private var

Var-quote bypasses public resolution and gives the Var itself; invoke it in
function position:

```clojure
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

For a running flow proc whose step function is stored as a Var, re-evaluating
the `defn` updates the next step without rebuilding topology
(`src/seon/flow.clj:83-115`;
`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md`
§4). Reloading is evidence only after the re-run; the edit on disk alone does
not change an already-running JVM.

## Fast diagnosis

| Symptom | Surface and next move |
|---|---|
| Reply became prose or the wrong plan forms | Agent reply: call `(seon.cluster.reply/sources exact-text 'user)` with an explicit namespace symbol. |
| `:seon.cluster.reply/unreadable` | Agent reply: fix malformed Clojure; no repair layer will close it. |
| Bare map/keyword evaluates and prints | Expected in `io-prepl` and raw JVM REPLs. |
| A private function is unresolved | JVM probe: invoke `#'fully.qualified.ns/var`. |
| The same old result appears after an edit | Reload/re-evaluate the owning namespace, then rerun the identical probe. |
