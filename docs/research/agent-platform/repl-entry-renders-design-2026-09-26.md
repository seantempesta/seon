---
type: design
status: "revision 2 (Opus): every finding of Astra review-repl-entry-renders-2026-09-26.md ruled and REPL-verified (§7); history policy for the owner in §4; back to Astra"
created: 2026-09-26
scope: AI renders as REPL entries evaluated read-only on the one REPL path; curated namespaces; the agent-side API reality check that the dogfood milestone depends on
---
# AI renders are REPL entries, evaluated on the one REPL path

**What exists.** Every stage this ruling needs is installed.

- The graph walk (`seon.render.walk/graph`, `walk.clj:1045`) orders nodes definitions-first.
- Today's openings are already comment-plus-form source. `task-ai` (`render/ns.clj:154`), `render-identity-ai`
  (`cluster/agent.clj:282`) and 23 other producers return `:seon.render/source` strings.
- `declared-sources` (`turn.clj:1879`) re-reads those strings. `system-turn` (`:2086`) evaluates them through
  `evaluate-sources` (`:4742`), records them as `:system` evaluations with their exact shown text, and binds their
  handles into the agent's context after commit (`:2256`).
- `system-plan` (`:1986`) refreshes changed reads, the agent's own read-only reads included.

**Data flow after this design.** An AI render function returns entries, the `:seon.cluster.reply/form` vector an
agent reply already becomes. The walk composes nodes' entries in graph order. The **existing** `system-turn`
evaluates them through `evaluate-sources` on a read-only fork of the viewing agent's **program world** (§2.3) and
records them as it does today. `shown-result` prints each result structurally (§2.4). `seon.repl/text` lays out a
`clojure.main` transcript.

**Model.** One entry grammar, one read per form, one terminal printer, one recorder. An entry's source is read
once, evaluated once and printed once as data; renderers run only where the walk selects a node, never inside
printing. The two new things are one positive value, read access in custody, checked at the one writer; and one
fix in call preparation's plan basis. There is no new loop, reader, evaluator, printer, cache or store, and no
list of special cases. Where revision 1 needed a special case (comment-only entries, recursive renders, a
transcript cache, a legend inside a value), the model changed instead.

## 0. Broken things first (ranked, from the agent's side; statuses at f0efd9cc8)

| # | Defect | Status | Where |
|---|---|---|---|
| D1 | MCP SCI on a branch could not run: a nested syntax-quote gensym in `run-form` | **fixed** fbd0897df; verified: branch SCI runs | `script/seon/dev/mcp.clj` |
| D2 | `:seon.agent/admission-closed-tx` was not installed on the live store, and a live host's retirement wrote `:db/current-tx` | **fixed** fbd0897df + 2fe36dca1; verified: retired `design-repl-entry-renders`, `-2`, `dre-review-probe`, `dre-review-probe-2`. Issue `hosted-agent-retirement-writes-a-keyword-transaction-ref.md` is resolved by 2fe36dca1 | `seon.cluster.host` |
| D3 | `my.test/check` fails on the uncompilable `boot_test.clj:147`, and `my.test/run` answers `[]` or a missing-entity refusal | open | `my-test-check-fails-on-an-unrelated-uncompilable-test-file.md` (b5741240e fixes the compile error) |
| D4 | A test defined earlier in the same run neither reaches nor gates a later redefinition: `(dre-sq 4)` ⇒ `64` against a test asserting 16 | open, blocker | `a-redefinition-in-the-same-run-as-its-test-installs-ungated.md` |
| D5 | No agent-callable merge proposal (`prepare-merge!`/`accept-merge!` need a held store) | open | a `my.*` wrapper over `source.clj:781`/`:831` |
| D6 | `preview-sources` commits its forms' writes | open | `preview-sources-commits-its-forms-writes.md`; the §2.3 stage fixes it |
| D7 | **Corrected.** The hook bypasses preparation when the environment has no connection, and derives its plan from the connection, not the held value. The database *supplier* already prefers the held value (verified §7 #5). | open | `call_preparation.clj` `hook` (`:1111`) |
| D8 | No `source`/`apropos`; `clojure.repl/source` prints nothing | open | SCI `clojure.repl` over `:seon.fn/source` |
| D9 | `dir` inlines every referenced schema (10–35 KB), printing properties as strings | open | `dir`'s producer |
| D10 | The auto-check reports the callee symbol as "actual" when the call threw | open | `auto-check-reports-the-function-symbol-as-the-actual-value.md` |
| D11 | A raw pull inlines nested declared render text past B (78,918 characters for one agent row) | open; §2.4 removes the class | `a-raw-pull-inlines-nested-declared-render-text-past-the-floor.md` |
| D12 | Refusal noise: `Example: No docstring example is available.` and a false `Contract finding` line | open | refusal renderer |
| D13 | `*ns*` shows `#object[sci.lang.Namespace]`; `require` shows `user` through a run but `nil` through a preview | Var printing **fixed** a1b01c92a (verified `#'user/dre-cell`); the rest open | 5c.4 |
| D14 | Ordinary work over a second (`(+ 1 2)` 753 ms as a run; the debug graph 3.4 s) | open | turn/settlement, auto-check |
| D15 | **New.** The reply planner silently drops a form that uses an alias required by an earlier form in the same text. `(require '[clojure.string :as a])\n::a/k\n(+ 1 2)` plans two forms; `::a/k` vanishes without a word. Passing entries directly evaluates it (`:clojure.string/k`). | open | `turn/planned-sources` (`:3152`) |
| D16 | **New.** A failing AI renderer's refusal is rendered by the same renderer again: one shown value nested `:seon.error/offending` ≥ 6 levels deep, each "The selected renderer did not return an observation." (branch on a stale program, a1b01c92a) | open; §2.4 removes the class | `seon.render` invocation of `seon.error/render-ai` |
| D17 | **New.** An `ns` form evaluated at the REPL does not write the namespace's declaration. `(ns user (:require [my.program :as program]))` left `:seon.ns/aliases` absent and `:seon.ns/requires` unchanged. | open; prerequisite of §3 | the namespace declaration producer |

## 1. API reality check from the agent's side

Unchanged from revision 1, except for the statuses in §0. It was run on archive 052552039 through the owners the
MCP bridge calls. R-rows that now differ were re-run on fbd0897df/f0efd9cc8 and are noted in §0.

| # | Forms | Shown (exact; length) | Verdict |
|---|---|---|---|
| R1 | `(require '[my.program :as program])` | `user` | should print `nil`; the alias persists across runs |
| R2 | `(dir my.program)` | a map of every referenced schema, then `:functions` (10,4xx) | D9 |
| R3 | `(doc my.program/callers)` | `{:summary "Read direct callers and their declaration spans and recorded call arities.", …, :in [:cat [:map [:seon.program/subject :seon.program/subject]]], :out [:or …], :supplied [:seon.db/db]}` (522) | good |
| R4 | `(source my.program/callers)`, `(clojure.repl/source my.program/callers)` | unresolved symbol / `nil` with no `:out` | D8 |
| R6 | `(seon.db/pull '[*] [:seon.agent/id "mcp/dre"])` | 78,918 characters of nested turn transcripts | D11 |
| R6 | `(seon.issue/task-edges (seon.db/db) "cea47b015b23")` | `#:seon.task{:subjects #{seon.schema}, :proofs #{}, :referrers #{}}` | good; the agent has no attached task (`:seon.agent/task` is not in the registry) |
| R7 | `defn` with `(* x x)`, then `(* x x x)` | first installs; second refused, with `actual: user/dre-sq` | D10 |
| R8 | `tests-reaching` after a REPL `deftest` | across runs, gating `[user/dre-sq-test]` (fbd0897df); within one run, "No program declaration names user/dre-sq" | D4 |
| R9 | `(my.test/check {})`, `(my.test/run)` | compile error at `boot_test.clj:147:43`; `[]` or a missing-entity refusal | D3 |
| R10 | propose a merge | no function | D5 |

Plumbing that should not be agent API: `my.program/native-call-refusal` and `my.program/supplied-context`, whose
zero-argument call refuses.

## 2. The render contract

### 2.1 One entry grammar, one transcript grammar

**An entry is `:seon.cluster.reply/form`**: `{:seon.cluster.eval/source <exact bytes>, :seon.cluster.eval/comment
<;; prose>?}` (`seon.cluster.reply.edn:1-11`). That is what the reader loop consumes. A render function's AI output
schema is `:seon.cluster.reply/sources`; the HTML output stays `:seon.render/hiccup`.

- **Every entry has a source.** There are no comment-only entries: a comment is always the prose above a form.
  That is the grammar the reader already keeps (`:seon.cluster.eval/comment`, "the agent's own prose above it").
- **A node reached twice renders once** ("each node once"). Its later referrers show the reference as data in
  their own results, so no back-reference entry exists (§2.2).
- **`:seon.ns/name` on an entry is not promised.** The loop's namespace is the one in effect (`turn.clj:4778-4885`).
  An entry that needs another namespace says `(in-ns …)` as a form.
- **`:seon.eval/origin` becomes declared**, optional, on `:seon.cluster.reply/form`, taking over
  `:seon.render/source-blocks`' job.
- **One vocabulary, the loop's.** `:seon.repl/entry` and `:seon.repl/entries` (`seon.repl.edn:36-43`, `:93`) carry a
  form value and have one producer, `run/usage-form` (`run.clj:58-84`), which returns a vector of them under a
  `:seon.render/form` contract. It converts to `:seon.cluster.reply/sources` (spelled by `seon.repl/source-text`),
  and both schemas are retired.
- `:seon.render/source`, `:seon.render/source-blocks` and the third output key `:seon.render/form` go when their
  last producer converts.

**A renderer builds forms as data and spells each once** with `seon.repl/source-text` (`repl.clj:91`, pprint
code-dispatch, `'x`). Those bytes are the source that the loop reads once (§2.3). The renderer itself reads
nothing (today `task-ai` pre-reads `task-edges` at `ns.clj:163`): reads happen in evaluation, where the read sink
records them.

```clojure
;; seon.render.ns/task-ai, after
(defn task-ai
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.cluster.reply/sources]}
  [{{id :seon.task/id} :seon.render/value}]
  [{:seon.cluster.eval/comment (str ";; Task " id " is attached to you.")
    :seon.cluster.eval/source (repl/source-text
                               (list 'seon.db/pull (list 'quote [:seon.task/name :seon.task/kind :seon.task/ns
                                                                 :seon.task/intent :seon.task/must-read])
                                     [:seon.task/id id]))}
   {:seon.cluster.eval/comment ";; Its subjects, proofs (tests that name it) and referrers."
    :seon.cluster.eval/source (repl/source-text (list 'seon.issue/task-edges '(seon.db/db) id))}])
```

**The transcript grammar** (5c.3, owned by `seon.repl/text`, `repl.clj:264`, changed in place). Each evaluation
prints, each part on its own line:

1. the prompt, the comment, and the source;
2. `:out` text;
3. the printed value, or the `clojure.main` error text (`repl/error-text`, `repl.clj:149`);
4. machine comment lines: the floor's cut legend when the value was cut, then `;; result/e… · N ms`.

**No value carries a comment**, and the `#:seon.repl{:value … :result … :ms …}` wrapper goes. That removes the
defect class of `the-cut-legend-inside-a-repl-reply-comments-out-the-reply-tail.md`: the legend already ends the
floor's text, so it lands on its own line once no map wraps it. The context ends with `ns=> `.

- Ruling 45's test `seon.repl-test/nothing-emitted-is-comment-shaped` is superseded for entry comments and machine
  lines by the 2026-09-26 render law. The owner confirms that when the grammar slice lands.
- Help (`help-shows-its-map-because-a-macro-call-names-no-producer.md`) is the same model: a help node's entries
  are `;; topic text` above the topic's example form (5c.6). `(help)` returns data like any function.

### 2.2 Composition over the graph

`walk/graph` gives `::graph-order` and `::back-references`. Composition is one `into` over that order:

- A non-history node contributes its render's entries.
- A history node (turn, receipt) contributes its saved text, laid out by `seon.repl/text`, and is never
  re-evaluated.
- A node already contributed contributes nothing.

One entries vector per prompt goes to one `evaluate-sources` call, so there is one fork and one loop. Aliases:
§3.

### 2.3 Where evaluation happens

**Why this is the same system.** Each stage is an installed function, extended in place:

| Stage | Existing function | What changes |
|---|---|---|
| world | `seon.sci.eval/fork-for-turn` (`eval.clj:2962`) | fork the viewing agent's **base program context** (`:seon.sci.eval/base-ctx` of its held entry), with read access in custody |
| segment | none: entries are already `:seon.cluster.reply/form`s | `system-turn`'s declared forms come from render output directly; `declared-sources`' re-read (`turn.clj:1907-1929`) goes, and so does D15's planner path for renders |
| read | `evaluate-for-install` → `one-event` → `seon.sci.reader/read` (`eval.clj:4467-4489`, `:957-966`), once per form in the SCI namespace table in effect | finalization consumes that event's form, not a second read (below) |
| evaluate | `seon.sci.eval/evaluate` (`eval.clj:3908`) | none |
| finalize | `disposition-rule-error` (`turn.clj:4721`) | takes the evaluation's read form instead of re-reading `source` (`:4725-4727`) |
| print | `seon.sci.eval/shown-result` (`eval.clj:3674`) → `render.value/prepare` | the AI face is structural (§2.4) |
| record | `system-turn` (`turn.clj:2086`) and its settlement; `bind-result!` into the agent's context after commit (`:2256`) | none (policy, §4) |
| transcript | `seon.repl/text` (`repl.clj:264`) | the §2.1 grammar, in place |

**Read-only, decided at the authority.** Revision 1's "drop two connections" is withdrawn, because it revoked
nothing (verified in §7 #1). With both carriers removed, `(seon.db/transact! (seon.db/branch-connection
(seon.db/db) :dre-review-probe) …)` committed `ro-explicit`, and SCI can reach `(seon.cluster.boot/connection
"default")` as a live `datahike.connector.Connection`.

The writer's rule is the flaw. "An absent binding means the caller is outside an agent evaluation, so a live
explicit connection is allowed" (`db.clj:4862` docstring) reads absence as permission. The fix is one **positive**
value:

- **Custody carries access.** `{:seon.db/db d :seon.db/access :read}` (a new enum key on the existing custody
  request). `db/call-with-custody` (`db.clj:377`) already binds custody for the evaluation's thread and binds it
  the same way.
- **`seon.db/transact!` refuses every connection**, elided or explicit, under read access, with one declared
  error naming the attempted transaction. Every durable write an agent form can cause passes through it:
  - datahike.api is not resolvable from SCI (verified: `datahike.api/transact` is an unresolved symbol);
  - effects are opened by `db/transact!` of `effect/open-call` before dispatch (`effect.clj:837-844`), so an
    effect never starts;
  - program installs transact through the same writer.
  So the one check covers the DB, effects and program rows, without a deny-list of names.
- **Private objects are not in the world.** `sci/fork` is shallow: an agent-context fork `(swap! dre-cell inc)`
  changed the parent to 1 (verified). A fork of the **base program context** does not contain the agent's private
  defs: `@dre-cell` is an unresolved symbol there, while `(my.agent/identity)` and `(doc my.program/callers)` answer
  (verified). Render functions are program rows, so they are in the base context. This narrows "the viewing
  agent's own SCI world" to its *program* world, a reading the owner confirms (§4).
- **SCI-local state is disposable.** `require`, aliases and `defn` in the fork stay in the fork: an alias created
  in the fork was absent from the parent (verified). A `defn` under read access is refused at its install
  transaction, so it shows its refusal.
- **Residual authority, an owner decision.** The residual is host functions that mutate JVM state without a
  transaction (a host atom inside a `seon.*` namespace, registry/konserve writers such as
  `seon.cluster.registry/branch!`). The writer check does not catch them. Two options:
  - **(a)** Render entries are produced only by declared, merge-gated render functions, so they are trusted code,
    and read access guards their mistakes in the DB, effects and the program.
  - **(b)** Admission also refuses an entry whose callee's transitive reach (`db/call-reach`, existing analysis)
    includes a declared non-transaction writer.
  - **(a) is recommended.** (b) cannot see higher-order or interop calls, so it is weaker than it looks.

### 2.4 Terminal printing: renders run at the walk, never inside a print

`shown-result` → `prepare` invokes declared pairs for reached maps (`value.clj:630` `declared` →
`render.clj:1153-1220`). With AI pairs returning entries, a printed result could select an entry producer, and
printing would need to evaluate. That is recursion, and D16 shows its failure form today.

**Rule.** The AI face of a printed result is the floor over the value, structural, with no declared pair. That is
the existing option `:seon.render.value/options {:seon.render.value/structural? true}` (`value.clj:635`), already
used by `error.clj:940` and `repl.clj:471`. Entry producers run only where the walk selects a node. So:

- An entity inside a result prints as its data under B. That removes D11: no nested transcript text.
- A renderer that reads back its own entity (`render-note-form`, `note.clj:131-137`) produces a pull whose result
  prints as data. There is no cycle.
- A thrown failure prints as `clojure.main` error text. A returned error value prints as data, and its `:seon.error/*`
  keys are the story. A failing render function is one walk-level failure unit (`render/renderer-failure`,
  `walk.clj:793`), never re-rendered, which removes D16.
- The HTML face is unchanged: declared hiccup pairs still compose, and the owner's explorable page keeps its
  renderers.

This is the same floor, not another printer. What goes: the AI-face producer invocation inside `prepare`, and AI
producers whose only job was prettifying a result (e.g. note prose `Note "x": y`). They are counted in §5.

### 2.5 Bounds, errors, timeouts

- **Admission.** Rendered entries are admitted like a reply. The summed source bytes are bounded by
  `:seon.config.eval.result/max-source`, the bound `planned-sources` applies to a reply today, and the entry count
  by the walk's `max-nodes`. Over-bound refuses by name before evaluation.
- **Per form.** `:seon.sci.eval/time-limit-ms`.
- **Aggregate.** The owning system turn's bound. `evaluate-sources` has no aggregate deadline and
  `await-turn-part!` only observes (`turn.clj:4349-4353`). The slice must name the missing terminal event and
  keep ownership until SCI's interrupt has actually exited, with timeout distinct from exit (AGENTS "Bound
  admission and await actual exit").
- **Errors.** The next entry runs after a refused one: a mid-list `(my.agent/done)` was refused by the disposition
  rule and `(+ 1 2)` still ran (verified). A `:completed`/`:wait` disposition stops the loop (`turn.clj:4880-4882`).
  Render entries never contain one, and admission refuses one by name.

### 2.6 No transcript cache

Revision 1's cache is withdrawn. Read evidence is not complete input evidence. With one captured pull plus a
local atom, the answer changed from `[… 0]` to `[… 1]` while `read-evidence-current?` stayed `true` (verified, §7
#2). Namespace tables, print settings and profiles are further inputs.

Reuse stays with the dependencies: Datahike's query and pull caches, and the walk's entity-pull reuse. Any later
cache keys only computations with complete immutable inputs.

Refresh is the other direction. It decides to **re-evaluate**, never to skip, so it may use incomplete evidence
conservatively:

- a recorded entry with changed read evidence (`db/read-evidence-changes`, `db.clj:1072`) is re-evaluated;
- so is an entry with empty or unknown evidence;
- nothing is skipped on the claim that it is unchanged.

Refresh derives from recorded read provenance, not from reparsed shown text. That is audit F11, taken in the same
slice as feeding `system-turn` entries (§5).

### 2.7 Three node renders (target illustrations, not installed output)

These were evaluated by hand on the one path (a preview over the branch agent's context) and laid out in the
§2.1 grammar. `seon.repl/text` still prints the wrapper map today (review probe: `"user=> (+ 1 2)\n#:seon.repl{:value
3}"`), so these are targets until the grammar slice lands. Saved in `tmp/design-repl-entry-renders/`:

- `agent.txt`: 276 bytes, 3 forms;
- `task.txt`: 1,115 bytes, 3 forms;
- `ns.txt`: 1,792 bytes, 3 forms.

```
user=> ;; Task cea47b015b23 is attached to you.
(seon.db/pull '[:seon.task/name :seon.task/kind :seon.task/ns :seon.task/intent :seon.task/must-read] [:seon.task/id "cea47b015b23"])
#:seon.task{:name "agents-can-validate",
 :kind :namespace,
 :ns seon.schema,
 :intent
   "An agent can validate a value against a registered schema key from its SCI context in one ordinary call. …",
 :must-read
   [seon.schema/explain-candidate-value
    seon.schema/projection-from-database
    seon.schema/valid-candidate-value?]}
user=> ;; Its subjects, proofs (tests that name it) and referrers.
(seon.issue/task-edges (seon.db/db) "cea47b015b23")
#:seon.task{:subjects #{seon.schema}, :proofs #{}, :referrers #{}}
user=> (doc seon.schema/valid-candidate-value?)
{:summary "True when value satisfies schema-key in the supplied generation.", …, :out :boolean}
user=>
```

## 3. Curated namespaces

- **Attachment.** The viewing agent's own namespace declaration: `:seon.ns/aliases`
  (`seon.ns.edn:2,26`; `:seon.ns.alias/binding`, "the :as portion of a Clojure require libspec") on its
  `:seon.agent/namespace`. No new attribute. A task attaches functions through `:seon.task/must-read`, rendered as
  `(doc f)`, and after D8 as `(source f)`.
- **Ownership.** Aliases belong to their owning namespace. Only the **viewer's own** namespace's aliases render as
  `(require '[t :as l])` then `(dir t)`, as the first entries of the viewer's namespace node. An alias of another
  reached namespace is that namespace's data (its `ns` declaration, printed), never executed in the viewer. So two
  reached namespaces that use one local name for different targets cannot collide: only one set of aliases
  (the viewer's) is ever required. Definition order does not need alias edges, because the viewer's requires come
  first by construction.
- **Adoption, through the normal producer.** Root's namespace changes by evaluating its `ns` form at the REPL,
  e.g. `(ns my.agents.root (:require [my.program :as program] …))`, keeping its current requires and refers.
  **D17 blocks this today**: the REPL `ns` form writes no declaration (verified). Fixing D17 is the prerequisite.
  `namespace-seed-call` (`cluster/agent.clj:166`) returns `[]` for an existing namespace, so seeding is only for
  new agents.
- **Root gets** `[my.agent :as agent] [my.program :as program] [my.test :as test] [my.message :as message]
  [my.plan :as plan] [seon.db :as db]`, plus D5's merge function once it exists.
- **The toolkit** (`:seon.cluster/toolkit`, `instruction/toolkit-namespaces`, `:seon.ns/context-relevant?`) stays
  until its consumers read aliases; it is deleted in the same slice as its last consumer.

## 4. History policy (for the owner): the smallest that persists exact shown text

**Proposal: every entry the agent is shown is a recorded system evaluation, through the existing `system-turn`**:

- exact shown text, read evidence and ordinal, as today;
- handles bound into the agent's context after commit by the existing `bind-result!` (`turn.clj:2256`);
- changed reads refreshed by the existing `system-plan`, the agent's own read-only reads included (`:2017-2026`).

The render law changes two inputs of that recorder and no policy:

1. the declared forms come from entry data instead of re-read strings;
2. they evaluate on the read-only program fork (§2.3) instead of the agent's writable fork.

Recording by the lifecycle owner is separate from admitting writes inside a form: the recorder writes, the form
cannot. This satisfies "Persist exact shown text", "the one REPL path … its results and facts are stored and
queryable", and "Derive wakes and completion" (refresh). It deletes nothing that policy requires. Revision 1's
options A (never record) and C (record on change) are withdrawn; each would change durable-history policy on its
own.

**For the owner to confirm:**

1. This policy.
2. "Own SCI world" means the agent's program world (base context), not its private scratch defs (§2.3).
3. Residual host authority option (a).
4. Supersession of ruling 45 for entry comments and machine lines (§2.1).

## 5. Deletions and the first slice (gross deletions separated from conversions; estimates until the caller audit)

**Gross deletions.** A body is removed and its callers are converted:

| Removed | Where | Gross lines |
|---|---|---|
| String re-read of declared forms | `turn/declared-sources` (`turn.clj:1879-1936`) | 57 |
| Response-map wrapper | `repl.clj` `def-note` (`:119-142`), `response-entries` + `response` (`:169-237`); `value-text`/`error-text` survive | ≈93 |
| Source-output plumbing | `render.clj` `:seon.render/source-blocks` handling and `render-default-ai-source`; exact spans at the caller audit | ≈60 |
| Second read in finalization | `disposition-rule-error`'s `read-source` call | ≈3 |
| Second entry vocabulary | `:seon.repl/entry`, `:seon.repl/entries` schemas | ≈12 |

**Conversions.** Each body changes shape, and the net is measured at the time:

- 25 `:seon.render/source` producers and 8 `:seon.render/form` producers become entry producers, `run/usage-form`
  included.
- `repl/entity-emission` (`repl.clj:368-447`, callers `:449-454`, `transcript.clj:1466`, `:1864`) keeps its field
  translation, and its render-time reads move to selection.
- `help-value` becomes help-topic entries; `render-help-html` survives.
- AI producers that only prettified results are deleted where no walk node uses them.

**Reconciled with the agent-centric audit** (`agent-centric-audit-2026-09-26.md`). These rows are taken inside
this design's slices, so each cut happens once:

| Audit | What this design does | Estimate (the audit's; spans at f0efd9cc8) | Slice |
|---|---|---|---|
| **F06** the transcript converts evaluations into another model and back | Taken. The transcript's evaluation view becomes the evaluation owner's canonical value (`turn/evaluation-facts`), rendered by `seon.repl/text` in the §2.1 grammar and ordered by `walk/graph`. Deleted with callers: `receipt-entry` (`transcript.clj:254`), `entry-order` (`:298`), `emission` (`:485`), `projected-entry` (`:544`), `candidate-history` (`:663-733`), `projection` (`:734`); `render-ai` (`:750`) calls the shared composition. The span `:254-790` is 536 lines; the audit prices −420/+70 after keeping message ordering, selected runs, historical bytes and error windows. Callers converted: `web.clj:1490` (preview), `eval/drive.clj:284`, history views. This is the "parallel projection" row of revision 1, now priced | −420 / +70 | with the transcript grammar (§5 "then" step 3), and it needs `entity-emission`'s conversion |
| **F11** refresh rereads and reparses all past evaluations | Taken. `latest-evaluations` (`turn.clj:1934`) stops parsing `repl/shown-value` and replaying diffs, and `read-only-evaluation?` (`:1968`) stops reparsing source. Candidates come from recorded read evidence per distinct source (§2.6). The shown-value diff-as-value (B6) goes with it | −115 / +45 | with step 4 (entries into `system-turn`) |
| **F17** "preview" suppresses settlement, not effects | Same defect as D6; fixed by the first slice's read access | — | first slice |
| **F10** every continuing turn regenerates the private context | **Not removed.** Render evaluation never takes the `regenerate-agent-context!` path: it forks the base program context without `agent-ctx` (§2.3), so it adds no regeneration. Ordinary agent turns still call `fork-for-turn` with `agent-ctx` (`turn.clj:4967`, `sci/eval.clj:2979`). F10's unchanged-program shortcut belongs to the context owner's lane | — | outside this design |

With F06 and F11 the design's gross deletions are about −760 (the table above ≈225, F06 420, F11 115), against
about +115 added in conversion (F06 70, F11 45) plus the first slice's ≈95. All are estimates until the caller
audit. The walk's `neighborhood` (≈750) stays excluded; it belongs to task-context-install slice B.

**First slice (a correctness fix that grows code, so it is justified here per AGENTS).** It establishes the stage
in the existing preview owner before any renderer migrates, and it deletes nothing. Its justification is D6 and
D7, not deletion:

1. `seon.db`: `:seon.db/access` on the custody request; `call-with-custody` binds it; `transact!` refuses under
   `:read` with one declared error (≈25 lines including schema and error).
2. `fork-for-turn`: `:seon.sci.eval/access :read` forks the base program context and marks custody (≈10).
3. `call_preparation/hook`: acquire the plan from the scoped `:seon.db/db` when present, the connection otherwise.
   Keep supplied-argument precedence, and correct the docstring's temporality (`:1111-1132`) (≈10).
4. `preview-sources`: accept `:seon.cluster.reply/sources` directly, and fork with read access (≈8).
5. Regressions on the real agent path (≈40 test lines). Each must hold:
   - under read access, each of these leaves the branch's datoms unchanged and shows its refusal: an elided write,
     an explicit branch-connection write, a supplied writer (`my.note/add!`), a nested writer, an effect
     (`my.message/send`), and a `defn`;
   - a private atom is unreachable;
   - a `require` inside the fork works and does not leak to the parent;
   - a supplied read answers at the held value, not the head;
   - with no connection, supplied defaults still apply (`(my.note/notes)` answers);
   - an alias-qualified keyword after an in-list `require` evaluates.

**Then, in order:**

1. Finalization takes the read form (§2.3).
2. The terminal AI print becomes structural (§2.4).
3. The transcript grammar (§2.1), with ruling-45 test supersession.
4. `task-ai`, then the scripted producer sweep, feeding `system-turn` entries; `declared-sources` deleted.
5. D17, then aliases for root.
6. The toolkit deletion.

## 6. Cost

**Parent (measured, JVM, read-only).**

- Root's `seon.cluster.prompt/prompt`: 492 ms cold, 5 ms warm; 37,627 bytes in 53 units.
- `/agent/root/debug?n=3`: 3.40 s and 3.50 s, 133 nodes (103 turns or receipts), 83,619 AI characters. This is
  already over the one-second law, and its responsible armed functions are not yet profiled.

**Candidate stage costs (measured, feasibility only).**

- One fork: 42–45 ms; 42.7 ms for a base-context fork with 3 forms.
- 60 real read forms in one `evaluate-sources`: 168–201 ms.
- A read-only fork with 7 forms: 79 ms.
- An `as-of` held value's first supplied call: 471 ms (the cold as-of projection, open-items).

**Not measured, and required for acceptance on parent and candidate, cold and warm, with armed profiles:** renderer
invocation, graph acquisition, composition, the recorded system turn, final prompt bytes, allocated and retained
memory, and a node with many entries. One fork per prompt is necessary, not sufficient: 30 forks would be 1.26 s.
Any operation over one second is fixed or justified beside its number; over ten seconds needs owner authorization
and an issue.

## 7. Rulings on Astra's review (every finding verified at the REPL)

Probes: MCP SCI on disposable branches `dre-review-probe` (default fbd0897df/a1b01c92a) and `dre-review-probe-3`
(f0efd9cc8), plus JVM scratch `tmp.dre-review` calling the installed owners. All branches were retired and the
scratch namespace removed (§8).

| # | Ruling | REPL evidence |
|---|---|---|
| 1 Blocking | **Accepted.** Read-only is a positive access value checked at `seon.db/transact!` (DB, effects and installs share that writer), plus a base-program fork for private objects; residual host authority is an owner decision (§2.3). The first slice lands it in the preview owner before renderers migrate. | Both carriers removed: elided `transact!` refused, but an explicit `(seon.db/branch-connection (seon.db/db) :dre-review-probe)` write committed `ro-explicit`. `(type (seon.cluster.boot/connection "default"))` from branch SCI ⇒ `datahike.connector.Connection`. `datahike.api/transact` unresolved. Agent-context fork `(swap! dre-cell inc)` ⇒ parent `1`; base-context fork `@dre-cell` ⇒ unresolved, `(my.agent/identity)` answers. A fork `require` alias is absent from the parent (`nil`). |
| 2 High | **Accepted; cache withdrawn** (§2.6). | Reproduced: pull + atom, `:before [… 0]`, `:after [… 1]`, `:evidence-count 1`, `:current? true`. |
| 3 High | **Accepted.** Finalization consumes the read event's form; `def-note`'s `edn/read-string` goes with the wrapper; renders bypass the planner (which also silently drops forms, D15). | Direct entries `(require '[clojure.string :as dre-s2])` then `::dre-s2/k` ⇒ `:clojure.string/k`; the same text through `planned-sources` plans 2 of 3 forms, dropping `::dre-s3/k`. `disposition-rule-error` calls `read-source` (`turn.clj:4725-4727`). |
| 4 High | **Accepted.** Terminal AI print is structural; renderers run only at walk nodes (§2.4). The transcript grammar moves into the end-to-end slice; §2.7 is marked target. | Top-level pulls of a task and a note printed as data, but `my.note/add!`'s return printed as prose through its pair. A failing `seon.error/render-ai` nested its own refusal ≥ 6 levels in one shown value (D16). The review's `seon.repl/text` probe still shows `#:seon.repl{:value 3}`. |
| 5 High | **Accepted, D7 corrected**: the defect is `hook`'s plan basis and its bypass without a connection; the supplier already prefers the held value. The fix stays in the first slice. The snapshot-by-commit landing (10d6fdff5) covers older held values. | Held `as-of` value: `(my.note/notes)` ⇒ one note; head ⇒ two. No connection: `(my.note/notes)` ⇒ "argument count of 0", so preparation was bypassed. The issue `call-preparation-snapshot-…` evidence was corrected accordingly. |
| 6 High | **Accepted.** A and C withdrawn; §4 proposes corrected B as the policy: the existing recorder, a read-only fork, existing handles and refresh. | `system-turn` binds handles into `agent-ctx` after commit (`turn.clj:2256`); `system-plan` includes agent reads (`:2017-2026`). |
| 7 Medium | **Accepted.** Every entry has a source; no comment-only entries (a node renders once); `:seon.ns/name` on entries is not promised; `:seon.repl/entry` is retired and `run/usage-form` converted (§2.1). | The review's validation (comment-only entry ⇒ `false`) is taken as given; the model no longer needs one. |
| 8 Medium | **Accepted.** Admission bounds are reused from the reply path; aggregate bound and exit are named as slice requirements; the §6 numbers are relabelled as feasibility, with the required acceptance measurements listed. | Stage timings in §6 (fork 42.7 ms, 7 forms 79 ms). |
| 9 Medium | **Accepted.** Only the viewer's own aliases execute; adoption goes through the `ns` form (blocked by D17); the toolkit stays until consumers migrate (§3). | `(ns user (:require [my.program :as program]))` then a pull: no `:seon.ns/aliases`, requires unchanged. `my.program`'s own aliases pulled as components `{local db, target seon.db}` … (ownership shape). |
| 10 Medium | **Accepted.** Revision 1's 640/+120 is retracted; gross deletions and conversions are separated; the first slice is justified as a fix, not by deletion (§5). | — |

## 8. Verification limits and cleanup

- All branches this lane created were retired through the MCP `branch` tool, each answering `:seon.dev.mcp/retired?
  true`: `design-repl-entry-renders`, `design-repl-entry-renders-2`, `dre-review-probe`, `dre-review-probe-2`.
  `dre-review-probe-3` (retired answering the same). The JVM scratch namespace
  `tmp.dre-review` is removed. Default was restarted twice by root during this revision; no probe stopped it.
- Probes that call owners from a scratch namespace run those owners armed; the scratch helper functions are
  unarmed.
- No source was changed and no tests ran. The first slice's regressions (§5) are for root to run via
  `bin/test-check` after implementation.
- Raw evidence: `tmp/design-repl-entry-renders/`.
