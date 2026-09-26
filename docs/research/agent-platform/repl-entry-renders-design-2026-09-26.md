---
type: design
status: "first pass (Opus, design lane design-repl-entry-renders-opus); awaiting Astra review; no source edits"
created: 2026-09-26
scope: AI renders as REPL entries evaluated read-only in the viewing agent's SCI world; curated namespaces; the agent-side API reality check that the dogfood milestone depends on
---
# AI renders are REPL entries, evaluated on the one REPL path

**What exists.** Every stage this ruling needs is already installed. The graph walk
(`seon.render.walk/graph`, `walk.clj:1045`) orders nodes definitions-first (`finished-graph`, `:1027`). Today's
declared openings are already comment+form source: `task-ai` (`render/ns.clj:154`), `render-identity-ai`
(`cluster/agent.clj:282`) and 23 other producers return `:seon.render/source` strings. `declared-sources`
(`turn.clj:1879`) re-reads those strings with the reply planner, and `system-turn` (`turn.clj:2086`) evaluates and
records them. `preview-sources` (`turn.clj:4888`) is "the same parse, the same fork, and the same
`evaluate-sources` an ordinary turn runs", and it persists no evaluation.

**Data flow after this design.** An AI render function returns entries, a `:seon.cluster.reply/sources` vector
(the registered shape an agent reply already becomes). The walk composes each node's entries in graph order. The
entries are then evaluated by `evaluate-sources` on one disposable fork of the viewing agent's context. That fork's
custody is read-only. Each result is printed by `shown-result`, and the transcript is assembled by `seon.repl/text`.
Shown text is reused while its read evidence is current.

**The principal Clojure approach** is `clojure.main/repl`'s: stages passed as values (`main.clj:368`, `:414`). There
is no new loop, reader, evaluator, printer, cache or store. The only new value is one stage value, *read-only
custody*, supplied to the existing fork.

## 0. Broken things first (ranked, from the agent's side)

These are the defects the reality check (§1) found, ranked by how much they block the dogfood milestone. The
milestone is: MCP branch → `defn` → reaching tests → root merge → write-back.

| # | Defect | Evidence (§1 row) | Owner / seam |
|---|---|---|---|
| D1 | **MCP SCI on a branch cannot run.** Every call fails before it reaches the JVM with `Unable to resolve symbol: handle__7651__auto__`. The `run-form` template nests a second syntax-quote inside `~(if branch `(…handle#…))`, and a nested syntax-quote mints a *different* gensym. Proof: `` `(let [h# 1] ~`(inc h#)) `` ⇒ `(let [h__3042159__auto__ 1] (inc h__3042158__auto__))`. This blocks the milestone's first step. | R0 | `script/seon/dev/mcp.clj:392-414` (07a03cdd4) |
| D2 | **Branch retirement is broken, and so is every hosted-agent run's first read.** `:seon.agent/admission-closed-tx` is declared (`seon.agent.edn:32`) and read by `host.clj:27`, but it is not installed on default's store. Every run logs `Bad entity attribute … not defined in current schema` twice, and `branch retire` throws. This lane's branch **could not be retired** (§7). | R0, cleanup | `seon.cluster.host`, schema adoption |
| D3 | **`my.test/check` fails for a reason outside the agent's work.** It answers `Syntax error compiling var at (seon/cluster/boot_test.clj:147:43)` wrapped as `seon.sci.kernel/failure-value refused … got nil nil`. `my.test/run` and `my.test/run-owned` answer `[]` after a REPL `deftest` of the agent's own function: an empty answer is reported as health. | R9 | `my.test`, `seon.test/run` |
| D4 | **A REPL `deftest` is not linked to what it calls.** `(program/tests-reaching {:seon.program/subject 'user/dre-sq})` answers no tests after `(deftest dre-sq-test (is (= 16 (dre-sq 4))))`, before and after, with `:gap :outside-graph`. The merge gate needs this link ("every replaced function reached by one", `source.clj:831`). | R8 | analyzer reach for REPL-defined tests |
| D5 | **No agent-callable merge proposal.** `prepare-merge!`/`accept-merge!` (`source.clj:781`, `:831`) take a held store and an issue id; no `my.*` function exposes them. An agent cannot "propose a merge" at the REPL. | R10 | new `my.*` wrapper over the existing owners |
| D6 | **`preview-sources` writes.** It is documented "persisting nothing", but `(seon.db/transact! …)` and `(my.note/add! …)` in a preview committed two notes on the branch (§2.3 probe P2). Its fork inherits the agent's custody connection (`eval.clj:4059`) and environment connection (`call_preparation.clj:1203`). | P2 | this design's stage value (§2.3) |
| D7 | **Call preparation reads the connection's head, not the evaluation's database value.** `prepare` derives `database` from `(:seon.db/connection environment)` (`call_preparation.clj:1203-1205`), not from the scoped `:seon.db/db` (`eval.clj:3977`). A render evaluated "at value X" is therefore supplied the current head, and with no connection it is supplied nothing: `(my.agent/identity)` refused `missing :seon.db/db` (P3). | P3 | `seon.call-preparation` |
| D8 | **No source and no discovery.** `clojure.repl/source` prints nothing for `my.program/callers` (returns `nil`, no `:out`); `source` and `apropos` are unresolved. `my.program` has no `source` function; `(doc my.program/source)` refuses. | R3, R4 | SCI `clojure.repl` bindings over `:seon.fn/source` |
| D9 | **`dir` is unreadable.** `(dir my.program)` is 10.4 KB and `(dir my.plan)` 34.6 KB, because every referenced schema is inlined (the whole `:seon.env/environment`). Schema properties print as a *string*: `[:map "{:description …, :seon.render/ai seon.render.value/render-ai …}" …]`. Error schemas print `[:and "Error evidence is unavailable." :seon.error/base …]`. `my.agent/branch`'s `:in` prints the identity schema as a map key paired with itself. `my.test/run`'s `:in`/`:out` are a `declaration-absent` refusal *string*. | R2, R5 | `dir`'s producer and the nested-render printer |
| D10 | **The auto-check misreports a throw.** Redefining `dre-sq` as `(* x x x)` was refused with `arguments: [2097152] expected: :int actual: user/dre-sq`. The real cause is long overflow (2097152³ > 2⁶³); the report names the function where the value or exception belongs. | R7 | `seon.test.accretion` failure report |
| D11 | **Pulling one's own agent row costs 78,918 characters.** Nested `:seon.runtime/turns` render through their declared pair as whole transcript strings. That is the provenance rule's "declared text is not charged to B", applied inside a raw pull. The nested strings also still show `#:seon.repl{:value 3, :result …, :ms 0}` (5c.3). | R6 | floor provenance (one-render-path §3d) |
| D12 | **Every refusal carries two noise lines:** `Example: No docstring example is available.` and `Contract finding: <fn> returned this error, and none of its declared error alternatives matches it.` The second one fires for an unresolved symbol and for a user function's argument refusal, where the user function returned nothing. | R3, R7 | refusal renderer / `seon.sci.kernel/failure-value` |
| D13 | **Printing breaks REPL convention.** `(defn …)` shows `#object[sci.lang.Var]`, not `#'user/dre-sq`. `*ns*` shows `#object[sci.lang.Namespace]`. `(require …)` shows `user` through a run but `nil` through a preview, so one form prints two values on two entrances of one pipeline. | R1, R6 | 5c.4 |
| D14 | **Ordinary work is over a second.** `(+ 1 2)` takes 753 ms as a branch run. A run with one `defn` and one redefinition takes 1,853 ms (`call-with-custody` 4,363 ms inclusive ×16, `with-database` ×2 2,876 ms). The test batch takes 3,651 ms. The root debug graph at N=3 takes 3.4–3.5 s twice, with no warm gain. | R0, R7, §5 | turn/settlement and auto-check cost owners |

**Out-of-scope findings.** I did not file them as issues because this assignment commits only this document. Each
needs root to file it under `docs/seon/issues/`: D1, D2, D3, D4, D6, D7, D10 and D11.

## 1. API reality check from the agent's side

**Method.** The MCP `branch` tool created `:design-repl-entry-renders` off default (basis-t 536874324; default pid
19443, HEAD archive 052552039, health `faulted`, 9 open signatures, hook publication off). MCP `eval_clj` SCI on
that branch failed (D1). So in a scratch JVM namespace I called the same owners the bridge calls, without the
broken template: `seon.cluster.host/host!` (agent `mcp/dre`, namespace `user`), then
`seon.cluster.agent/evaluate-source!` with segmenter `seon.cluster.reply/code-sources`, then
`seon.eval.drive/run-receipts`. That is the bridge's documented path, minus the syntax-quote. No default Var was
redefined, and the scratch namespace was removed. Shown text is exact. Long values are abridged where marked
`…`, with their full length in characters.

| # | Forms (one run) | Shown (exact; length) | Verdict |
|---|---|---|---|
| R0 | MCP `eval_clj` sci, branch, `(help)` | `Syntax error compiling at (23:927). Unable to resolve symbol: handle__7651__auto__` | **broken** (D1) |
| R0 | `(+ 1 2)` via `evaluate-source!` | `3` (753 ms; stdout 2× `Bad entity attribute :seon.agent/admission-closed-tx`) | works; slow, noisy (D2, D14) |
| R1 | `(require '[my.program :as program])` | `user` | should print `nil` (D13); the alias persists across runs (good) |
| R2 | `(dir my.program)` | `{:schemas {:my.program/breaks-request [:map …] … :seon.program/breakage [:map "{:description\n \"Pure program-fact read…\",\n :seon.render/ai seon.render.value/render-ai…}" …] …}, :functions [{:sym my.program/breaks, :arglists …, :doc "Read the referrers a retraction would strand, with a prospective plan.", :in …, :out …, :supplied [:seon.db/db]} …]}` (10,4xx) | data is right, presentation broken (D9) |
| R3 | `(doc my.program/source)` | `seon.sci.eval/documentation-unavailable refused :seon.fn/sym at []: expected the declared requirement (:seon.sci.eval/public-documentation), got a symbol my.program/source. Fix: No public documentation is available; request an installed public declaration. … Example: No docstring example is available.` | missing function (D8), noise (D12) |
| R3 | `(doc my.program/callers)` | `{:summary "Read direct callers and their declaration spans and recorded call arities.", :body "Empty groups are absent; unknown coverage remains explicit.", :example "(my.program/callers {:seon.program/subject 'seon.turn/open?})", :arglists ([{database :seon.db/db, subject :seon.program/subject, :as request}]), :in [:cat [:map [:seon.program/subject :seon.program/subject]]], :out [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error], :supplied [:seon.db/db]}` (522) | **good**: contract, supplied inputs, example |
| R4 | `(source my.program/callers)`, `(apropos "task")` | `seon.sci.eval/evaluate refused source at []: expected a resolvable symbol (:symbol), got an unresolved symbol source. Fix: Define or require this symbol. Example: No docstring example is available.\nContract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.` (322 / 323) | missing (D8), noise (D12) |
| R4 | `(require '[clojure.repl :refer [source]])` `(clojure.repl/source my.program/callers)` | `user`, then `nil` with no `:out` | **broken** (D8) |
| R5 | `(dir my.plan)`, `(dir my.issue)`, `(dir my.test)`, `(dir my.agent)` | 34,640 / 10,680 / ~3,300 / ~7,000; `my.test/run :in "seon.sci.eval/declaration-absent refused at []: … :seon.fn/spec …"` | D9 |
| R6 | `(seon.db/pull '[*] [:seon.agent/id "mcp/dre"])` | `{:db/id 39872, :seon.agent/branch :design-repl-entry-renders, … :seon.agent/runtime {… :seon.runtime/turns ["Run 5ea2ef45c4e9, opened #inst \"2026-09-26T02:30:14.914-00:00\". It completed.\n\nuser=> (+ 1 2)\n#:seon.repl{:value 3, :result result/e8f73204c2cb9, :ms 0}" …` (78,918) | D11 |
| R6 | `(my.agent/identity {})`, `*ns*` | `#:my.agent{:id "mcp/dre", :namespace user, :steward "mcp/dre"}`; `#object[sci.lang.Namespace]` | identity good; `*ns*` D13 |
| R6 | `(seon.db/q '[:find ?id ?name :where [?t :seon.task/id ?id] [?t :seon.task/name ?name]])` | `#{["cea47b015b23" "agents-can-validate"] ["132fee0467c8" "definitions-bypass-entry-gate"] ["300aca1ad57c" "tests-reaching-saturates"]}` | works; the agent has **no attached task** (`:seon.agent/task` is not in the registry) |
| R6 | `(seon.issue/task-edges (seon.db/db) "cea47b015b23")` | `#:seon.task{:subjects #{seon.schema}, :proofs #{}, :referrers #{}}` | works; `(seon.db/db)` with the database elided works |
| R7 | `(defn dre-sq "Square x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))` `(dre-sq 4)` | `#object[sci.lang.Var]`, `16` | works; prints D13 |
| R7 | same `defn` with `(* x x x)`, then `(dre-sq 4)` | `seon.test.accretion/install-refusal\nFix the contract or the function and re-evaluate the defn.\n\nGate: 0 tests · 0 passed · 0 failed · auto-check 25/25 · install refused\n\nFailure group (1)\n\nAuto-check seed 733254833444966175\narguments: [2097152]\nexpected: :int\nactual: user/dre-sq\nwhy: ["should be an integer"]\n\nAdvisories\nNo example test gates user/dre-sq; add a deftest in your namespace that calls it, then (my.test/run).`, then `16` | refusal right, report wrong (D10); the old definition is kept (good) |
| R7 | `(dre-sq "a")` | `user/dre-sq refused argument 0 (0-based) at []: expected an integer (:int), got a string "a". Fix: Supply an integer at []. Called from sci.lang.Var (lang.cljc:265). Example: No docstring example is available.\n"a"\nContract finding: user/dre-sq returned this error, and none of its declared error alternatives matches it.` | armed contract works; noise and a duplicated `"a"` line (D12) |
| R8 | `(program/tests-reaching {:seon.program/subject 'user/dre-sq})`, before and after `(clojure.test/deftest dre-sq-test (clojure.test/is (= 16 (dre-sq 4))))` | both `{:seon.program/subject user/dre-sq, :seon.program/kind :seon.fn/sym, :seon.db/basis-t 536874334, :seon.program/unknown [#:seon.program{:gap :outside-graph, :message "This supplied database value answers; callers outside its program graph are unknown."}]}` | **broken** (D4) |
| R8 | `(program/callers {:seon.program/subject 'my.program/callers})` | `{… :seon.program/callers #{my.program-test/program-refusals-carry-complete-distinguishable-declared-schemas my.program-test/program-reads-name-the-referrers-and-propose-without-writing seon.fn-test/process-owner-is-a-queryable-program-namespace}, :seon.program/call-sites [#:seon.fn{:sym my.program…` (1,189) | **good** |
| R9 | `(my.test/check {})` | `seon.sci.kernel/failure-value refused  at []: expected the declared requirement (:successful-evaluation), got nil nil. Fix: Syntax error compiling var at (seon/cluster/boot_test.clj:147:43). …` | **broken** (D3) |
| R9 | `(my.test/run)`, `(my.test/run-owned {})` | `[]`, `[]` | silent empty (D3) |
| R10 | propose a merge | no `my.*` function; `seon.cluster.source/prepare-merge!` needs `[source held-store candidate issue]` | **missing** (D5) |

**What is confusing to an agent.** (a) `dir` answers with every referenced schema, so an agent cannot see the
function list. (b) Many functions take a request map whose keys are partly supplied, and only `doc`'s
`:supplied` says so; `dir` does too, but inside 10 KB. (c) Zero-argument calls (`(my.agent/identity)`) work, and
so do `(my.agent/identity {})` and `(program/callers {…})`. The single request map is consistent, but it is
explained only in `doc`. (d) The help text (§5 capture) teaches `result/e…` handles, `(my.test/run)` and
`(seon.turn/turns-left)` as prose, not forms.

**Unexplained or wrong `my.*`:** `my.program/native-call-refusal` and `my.program/supplied-context` are
plumbing, not agent API, and should be private or moved. `my.test/run` has no declaration row. `my.program` has
no `source` and no way to reach a function's tests' source. `my.agent` has no `task`. There is no merge.

## 2. The render contract

### 2.1 The entry, as data

**No new schema.** An AI render function's output is `:seon.cluster.reply/sources`, which is
`[:vector :seon.cluster.reply/form]` (`seon.cluster.reply.edn:1-11`). Each entry is
`{:seon.cluster.eval/source <exact bytes>, :seon.cluster.eval/comment <;; prose>?, :seon.ns/name <ns>?}`. That is
the value the reply reader already makes from an agent's text, and `evaluate-sources` already consumes it. One
declared addition: `[:seon.eval/origin {:optional true} :seon.eval/origin]` on `:seon.cluster.reply/form`. It
carries the issue identity that `:seon.render/source-blocks` carries today (`seon.render.edn:68`), so that schema
goes.

A renderer builds a form as data and spells it once with `seon.repl/source-text` (`repl.clj:91`, pprint
code-dispatch, `'x` reader quotes). That spelling is the source the reader reads exactly once in the loop (§2.3).
Nothing re-reads a joined string: today's B3 was the prose planner re-reading concatenated text
(`declared-sources`, `turn.clj:1907-1929`), which goes. An HTML render keeps returning hiccup.

```clojure
;; seon.render.ns/task-ai, after: data in, entries out (every line runnable)
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

The render does no reads (today `task-ai` calls `issue/task-edges` itself, `ns.clj:163`). The reads happen when the
entry is *evaluated*, so the read sink records them, and that recorded read evidence is what the cache compares.

**Face by output schema.** `:seon.cluster.reply/sources` is the AI output schema, and `:seon.render/hiccup` is the
HTML one. `:seon.render/source`, `:seon.render/source-blocks` and the third output key `:seon.render/form`
(`seon.render.edn:178-179`) are deleted. Every `:seon.render/form` producer (`note.clj:133`, `:141`,
`cluster/agent.clj:239`, `:252`, `render/ns.clj:123`, `:129`, `run.clj:66`, `error.clj:2015`) already returns a
form, so it wraps that form as one entry.

### 2.2 Composition over the graph

`seon.render.walk/graph` gives `::graph-order` (definitions before uses, turns by `:seon.turn/opened-tx`) and
`::back-references`. Composition is one `into` over that order:

- **Non-history node**: its AI render's entries, concatenated in order.
- **History node** (a turn or `:seon.cluster.eval/receipt`): nothing is evaluated. Its **saved** shown text is
  printed through `seon.repl/text` exactly as today ("Recover facts, not execution"; "never clip HTML or historical
  units again").
- **A node already rendered** (a back-reference): one comment entry, `;; see <lookup> above`. It has no form, so
  nothing is evaluated for it.
- The transcript ends with the viewing agent's `ns=> ` prompt (5c.3).

Measured on root today at N=3 (§5): 133 nodes, of which 103 are turns or receipts. So **30 nodes evaluate
entries** and 103 print saved text.

### 2.3 Where evaluation happens: the stages, and one stage value

**Why this is the same system.** Each stage is an installed function. The render path calls it and adds no copy:

| Stage | Existing function it calls | What the render path supplies |
|---|---|---|
| world | `seon.sci.eval/fork-for-turn` (`eval.clj:2962`) on the viewing agent's held context (the routing roster's `[branch agent-id]` entry) | the **read-only custody stage value** (below), applied to the fork |
| segment | none needed: entries are already `:seon.cluster.reply/form`s | — |
| read | `seon.sci.reader/read` (`reader.cljc:785`), via `evaluate-sources`' loop (`turn.clj:4742`) and `evaluate-for-install` (`eval.clj:4467`), once per form in the namespace in effect | — |
| evaluate | `seon.sci.eval/evaluate` (`eval.clj:3908`) inside `evaluate-sources`, under `:seon.sci.eval/time-limit-ms` and `await-turn-part!` | `:seon.db/db` = the prompt's held database value, so every form reads one value |
| finalize | `evaluate-sources`' disposition rule (`disposition-rule-error`, `turn.clj:4721`, applied at `:4833`) | — |
| print | `seon.sci.eval/shown-result` (`eval.clj:3665`): selection, then the floor | — |
| record | **none**: the lifecycle owner of a render is the prompt, as it is for a preview (`preview-sources`, `turn.clj:4888`, "nothing settles, no evaluation entity exists, and no `result/eN` handle is bound") | the provider capture already stores the exact prompt text (`:seon.context.capture/prompt`) |
| transcript | `seon.repl/text` (`repl.clj:264`), the one generator of REPL bytes | — |

So the render path is `preview-sources` with its entries supplied, and without the prose planner.
`preview-sources` today calls `planned-sources` on text (`turn.clj:4912`). The render passes
`:seon.cluster.reply/sources` directly, which is the key `evaluate-sources` already takes.

**The one stage value: read-only custody.** The viewing agent's own world, forked, with no write authority. A
fork holds two carriers of the connection, and a render must drop both:

1. `::custody :seon.db/connection`, which `evaluate` reads (`eval.clj:4059-4060`) and binds through
   `db/call-with-custody` (`db.clj:370`; `eval.clj:4092`). With it absent, every elided `seon.db` write refuses by
   name. Proof P1: `(seon.db/transact! …)` answered `This read needs a connection, and no cluster connection is
   bound…`, and the branch gained no datom. The wording says "read" for a write; that is a message defect.
2. The environment's `:seon.db/connection` (`seon.env`, branch layer), which call preparation supplies to `my.*`
   writers (`call_preparation.clj:1201-1205`). Proof P2: with only (1) removed, `(my.note/add! …)` still committed
   `dre-ro2`.

The honest composition is therefore a scope of the environment, applied once at the fork. The environment owner
already has the narrowing function, `seon.env/scope` (`env.clj:276`). It refuses non-turn members, so the stage
needs one sibling in the same owner: a read environment, which is the carried environment without its
connection. `fork-for-turn` then drops `::custody`'s connection when handed that stage. **D7 must be fixed in the
same slice.** Call preparation must supply `:seon.db/db` from the scoped environment's `:seon.db/db` member
(`eval.clj:3977`), not from `(db/db connection)`. Otherwise read-only renders lose every supplied read. Proof P3:
with both carriers removed, `(my.agent/identity)` refused `missing :seon.db/db` and `missing :seon.agent/id`.
That fix is also a correctness fix for ordinary evaluations, which should read the value they hold.

Program changes in a render (`defn`, `ns-unmap!`) happen only in the disposable fork. Probe P4:
`(defn dre-ro-fn …)` returned a Var, and no `:seon.fn/sym` row existed afterwards. The fork is discarded, so
nothing reaches the program. A renderer that emits a mutating form is a defect in the renderer, and the refusal it
produces is printed like any error.

**Why not something smaller.** (a) `d/with` speculative writes would let render forms "write" and would need a
second settlement path. (b) Relying only on `generated-read-fault` (`turn.clj:2033`) is after the fact, and it
checks evidence, not effects. (c) Leaving renders on `system-turn` records every render as history (option B in
§6).

### 2.4 Caching, bounds, errors and the floor

- **Cache.** Key: `[program identity of the viewing context, namespace, entry source bytes]`. Value:
  `{shown, read-evidence}` from the evaluation. Reuse while `db/read-evidence-current?` holds at the new database
  value, which is the same check the walk's entity pull uses (`walk.clj:441`). The key lives in the existing
  `render/shared-cache` (`walk.clj:490`), which already holds walk pulls, so there is **no new cache**. A form
  whose read evidence is empty (a pure form, or a form whose reads were not captured) is never reused, because it
  is unknown, not current. This is the refresh rule `system-turn` applies today (`read-only-evaluation?`,
  `turn.clj:1968`), moved to the render owner.
- **Bounds.** Per form: `:seon.sci.eval/time-limit-ms` (evaluate). Per render: the walk's `max-nodes` and
  `max-collection` cuts (already `::cuts`). Per prompt: whole-unit selection (`prompt/select`, `prompt.clj:301`).
  A node's entries are one unit, so selection never splits a node.
- **Errors.** A failing form's evaluation already carries its refusal or exception shown text from the same
  `shown-result` (P1's transact! refusal). It prints as the REPL prints it, and the next entry still runs. A
  render function that throws, or returns an invalid value, gives the walk's existing renderer-failure unit
  (`render/renderer-failure`, `walk.clj:793`), printed as `;; renderer <sym> failed: …` followed by the error.
- **Floor and provenance.** Each result is a raw value, so the floor's tight budget B applies. The permissive
  rendered-token ceiling applies only where a *declared renderer's own text* reaches the floor, and that happens
  only for a result value that carries a declared pair. D11 shows the hazard: nested declared text inside a raw
  pull escaped B at 78,918 characters. The ruling says "its provenance budget applies to the printed
  transcript". Read that as: B per form result. The transcript as a whole is bounded by whole-unit prompt
  selection, never by a second clipper.

### 2.5 Three node renders, probed live

The forms were evaluated by hand on the one path (`preview-sources` over the branch agent's held context, which
takes the same fork and `evaluate-sources` as a turn), then laid out with the `seon.repl/text` grammar. The prompt
is `user=>` because the viewing agent's namespace is `user`. Saved as
`tmp/design-repl-entry-renders/{agent,task,ns}.txt`.

**Agent node** (276 bytes; 3 forms; 66 ms including the 42 ms fork):

```
user=> ;; This is your agent. You work on branch :design-repl-entry-renders.
(my.agent/identity)
#:my.agent{:id "mcp/dre", :namespace user, :steward "mcp/dre"}
user=> (my.agent/branch)
#:seon.agent{:branch :design-repl-entry-renders, :mode :live}
user=> (my.message/inbox)
[]
```

**Task node** (1,115 bytes; 3 forms; 20 ms):

```
user=> ;; Task cea47b015b23 is attached to you.
(seon.db/pull '[:seon.task/name :seon.task/kind :seon.task/ns :seon.task/intent :seon.task/must-read] [:seon.task/id "cea47b015b23"])
#:seon.task{:name "agents-can-validate",
 :kind :namespace,
 :ns seon.schema,
 :intent
   "An agent can validate a value against a registered schema key from its SCI context in one ordinary call. Historical: passed on branch exp-validate-2 from the brief alone, never delivered; that branch is not retained.",
 :must-read
   [seon.schema/explain-candidate-value
    seon.schema/projection-from-database
    seon.schema/valid-candidate-value?]}
user=> ;; Its subjects, proofs (tests that name it) and referrers.
(seon.issue/task-edges (seon.db/db) "cea47b015b23")
#:seon.task{:subjects #{seon.schema}, :proofs #{}, :referrers #{}}
user=> (doc seon.schema/valid-candidate-value?)
{:summary
   "True when value satisfies schema-key in the supplied generation.",
 :body "",
 :example "",
 :arglists ([projection schema-key value]),
 :in
   [:cat :seon.schema/projection :seon.schema/registry-key
    :seon.schema/value],
 :out :boolean}
```

**Namespace node**, curated `my.program` (1,792 bytes; 3 forms; 40 ms). The compact `dir` is written as an
ordinary form over today's `dir` value. After D9 is fixed, the entry is plain `(dir my.program)`.

```
user=> ;; Curated for you: the program API.
(require '[my.program :as program])
nil
user=> (mapv (juxt :sym :doc) (:functions (dir my.program)))
[[my.program/breaks
  "Read the referrers a retraction would strand, with a prospective plan."]
 [my.program/callers
  "Read direct callers and their declaration spans and recorded call arities."]
 … 9 more pairs, one per public function, verbatim in ns.txt …
 [my.program/tests-reaching
  "Read the current gate set, derived by seon.fn/gate-set."]]
user=> (doc program/callers)
{:summary
   "Read direct callers and their declaration spans and recorded call arities.",
 :body "Empty groups are absent; unknown coverage remains explicit.",
 :example
   "(my.program/callers {:seon.program/subject 'seon.turn/open?})",
 :arglists
   ([{database :seon.db/db, subject :seon.program/subject, :as request}]),
 :in [:cat [:map [:seon.program/subject :seon.program/subject]]],
 :out
   [:or :seon.program/breakage :seon.program/read-refused-error
    :seon.program/not-found-error],
 :supplied [:seon.db/db]}
```

Compare today's `task-ai` output for the same entity (539 characters, 12.6 ms). It is a mix of comment prose and
one form. The subjects, proofs and must-reads are pre-read into comments, and the form at the end is printed as
`(seon.issue/task-edges (seon.db/db) "cea47b015b23")`: the agent reads its answer twice, once as prose and once as
a form it was not shown the result of. `render-alias-ai` prints `(dir (quote my.program))` (`ns.clj:58`, the
5c.4 leak).

## 3. Curated namespaces

**Registry search.** The candidates are `:seon.ns/requires`, `:seon.ns/aliases` (components of
`:seon.ns.alias/binding` `{:seon.ns.alias/local, :seon.ns.alias/target-ns}`, "equivalent to the :as portion of a
Clojure require libspec", `seon.ns.alias.edn`), `:seon.ns/refers`, `:seon.cluster/toolkit` with
`:seon.cluster/toolkit-namespaces` (a cluster-level rendering hint, `cluster/instruction.clj:31`), and
`:seon.ns/context-relevant?` (on 15 namespaces).

**Attachment: the agent's own namespace declaration**, meaning `:seon.ns/aliases` on its `:seon.agent/namespace`.
No new attribute. That is Clojure's own attachment: `(ns my.agents.root (:require [my.program :as program]))`
makes `program/` resolvable, and the alias row already exists and already has a render pair
(`render-alias-ai`/`-html`, `ns.clj:54`, `:61`). A task attaches through `:seon.task/must-read` (functions): each
must-read renders as `(doc f)`, and after D8, `(source f)`. Its namespace is required by the agent's declaration
when the agent takes the task, not by the task. Root today has `:seon.ns/requires [clojure.test my.message
my.turn seon.bootstrap seon.db]` and **no aliases**.

**Render of an alias binding** (AI): two entries, the require and then the list.

```clojure
[{:seon.cluster.eval/source "(require '[my.program :as program])"}
 {:seon.cluster.eval/source "(dir my.program)"}]
```

The function-level `doc` entries come from the task's must-reads, not from the namespace. Otherwise every agent
would read every contract in every curated namespace. The walk already reaches alias bindings as components of
the agent's namespace (`seon.ns.refer/binding` shows 5 nodes at level 2 today). Aliases are components in the same
way, so no walk change is needed.

**Root gets** exactly the API the milestone uses:
`[my.agent :as agent] [my.program :as program] [my.test :as test] [my.message :as message] [my.plan :as plan]
[seon.db :as db]`, plus the merge wrapper once D5 lands (a `my.program` or new `my.merge` function over
`prepare-merge!`/`accept-merge!`). `seon.bootstrap` and `my.turn` stay required without an alias, because root
calls them by full name today.

**What goes:** `:seon.cluster/toolkit`, `:seon.cluster/toolkit-namespaces`, `instruction/toolkit-namespaces`
(`instruction.clj:31-56`, 26 lines), the toolkit reconciliation in the cluster writer (`cluster.clj:3120-3172`,
about 20 of its lines), the renderer's toolkit count (`cluster.clj:192`, `:232`), and `:seon.ns/context-relevant?`
with its 15 metadata sites. Their job becomes "seed a new agent namespace's aliases". That seeding goes in
`namespace-seed-call` (`cluster/agent.clj:166`), which already writes the namespace.

## 4. Deletions and the first slice

| Replaced | Where | Lines |
|---|---|---|
| Producers print source strings; the planner re-reads them | `turn/declared-sources` (`turn.clj:1879-1936`) | 57 |
| Source-output plumbing and the third output key | `render.clj` `:seon.render/source-blocks` handling (`:1141`, `:1225-1252`, `:1518-1551`), `render-form`, `render-default-ai-source` and `render-form-value` (`:1362-1400`) | ≈110 |
| The `#:seon.repl{…}` response wrapper | `repl.clj` `def-note` … `response` (`:119-237`); `text` keeps `input-text` + shown | 113 |
| Render-time acquisition (B5) | `repl/entity-emission` (`repl.clj:368-448`) | 78 |
| Help as a literal vector | `bootstrap/help-value` + `render-help-*` (`bootstrap.clj:16-80`), `instruction.clj` getting-started text | 63 + 20 |
| Toolkit hint (§3) | `instruction.clj:31-56`, cluster writer and renderer lines | ≈50 |
| 25 `:seon.render/source` producers, 8 `:seon.render/form` producers | listed in §2.1 | net ≈ −150 (string building → data) |
| The walk's old flat units, once `declared-sources` is gone | `walk/neighborhood` and its acquisition (`walk.clj:71-822`); the remaining callers are `render.clj:50` and `web.clj:473` | ≈750, shared with task-context-install slice B |
| The transcript renderer's parallel agent-session projection (B7) | `seon.render.transcript` (2,481 lines, 117 functions) | needs a caller audit of web and drive (pipeline doc §5 estimated ≈500) |

Excluding the walk and transcript rows, the design deletes ≈640 lines and adds ≈120: the env read scope, the
call-preparation fix, the cache entry, the fork's stage parameter, the composition `into`, and one schema key.
Net ≈ −520.

**Smallest first slice (≈ +60 / −80, one loadable cut).** It proves the stage and one renderer end to end, and
changes no opening that is still on `system-turn`:

1. `seon.env`: a read environment (the carried value without `:seon.db/connection`), next to `scope`.
   `fork-for-turn` takes it as a stage and drops `::custody`'s connection. `call_preparation.clj:1203` supplies
   `:seon.db/db` from the scoped environment (D7).
2. `preview-sources` accepts `:seon.cluster.reply/sources` in place of text, and forks read-only (fixes D6).
3. Convert `task-ai` to return entries (as in §2.1), and let `declared-sources` accept a sources vector unchanged
   next to strings for this one producer. The string path goes when the last producer converts.
4. Regressions, one per behavior class: a render entry that writes (elided `transact!`, a supplied `my.note/add!`,
   a `defn`) leaves the branch unchanged and shows its refusal. A supplied read (`(my.agent/identity)`) answers
   at the held value, not at the head. `task-ai`'s entries evaluate to the §2.5 text.

Then, in order: convert the other producers (scripted, one sweep); switch composition to `graph` order; delete
the string path, `:seon.render/source(-blocks)`, `:seon.render/form` and `declared-sources`; then the response
wrapper and help (5c.3, 5c.6); then toolkit to aliases.

## 5. Cost: root's context today and under the design

**Today (measured, default pid 19443, JVM, read-only).** `seon.cluster.prompt/prompt` for root took 492 ms cold
and 5 ms warm, producing 37,627 bytes in 53 units with 51 `my.agents.root=>` prompts. The text ends
`turns left: 100 of 100` (5c.3), and 51 units carry `#:seon.repl{…}`. There is no stored capture to compare
against, because no provider runs (`:seon.context.capture/prompt` count is nil). The owner's debug graph,
`/agent/root/debug?n=3`, took 3.40 s and then 3.50 s, returning 339,120 bytes. It lists 133 nodes (level 1: 64,
level 2: 66, level 3: 2) with 83,619 AI characters: 52 receipts, 51 turns, 10 messages, 9 errors, 5 refer
bindings, 2 namespaces, and one each of runtime, fn, agent and plan. Today's per-node AI render is 7–13 ms
(task 12.6, ns 7.5, agent 8.2).

**Under the design (measured stage costs; totals are estimates).**
- One read-only fork costs 41.6–44.8 ms.
- 60 real read forms (40 `doc`s of `my.*` functions and 20 pulls) evaluated in one preview took 200.6 ms, then
  168.4 ms, with the fork included. That is **2.1–2.6 ms per form**, and it produced 34,702 characters.
- Root's 30 non-history nodes at about 2 forms each ≈ 60 forms, so ≈ 170–200 ms cold with no cache.
- Warm, every form whose read evidence is current reuses its shown text. That costs one
  `read-evidence-current?` per form, the same order as today's 5 ms warm prompt.
- The 103 history nodes print saved text: 0 evaluations, the same cost as today.
- Bytes: the three probed nodes are 276, 1,115 and 1,792 bytes. Root's non-history part is estimated at ≈ 15–30
  KB, replacing today's opening (help alone is ≈ 3.3 KB of prose). The history part is unchanged.
- **Unmeasured:** the armed cost of the new stage, the composed prompt's exact bytes, and the cache hit rate.
  Those need the first slice.

The cold cost stays under a second only if composition evaluates one fork per prompt, not one per node. Measured:
one fork per node would be 30 × 42 ms = 1.26 s, over the law.

## 6. One genuinely new decision (for root and the owner): do render entries become history?

Today's openings are **recorded** as system evaluations with `result/e…` handles, and they are refreshed by the
since-diff (`system-turn`). The ruling says renders are evaluated on a *disposable fork*. Three options, simplest
first:

- **A (recommended). Never recorded.** Renders are evaluated fresh per prompt on a read-only fork, cached by read
  evidence. The provider capture keeps the exact bytes the model saw. The agent reruns a form to get a live
  handle. *Guarantee:* the prompt is always current, and history holds only what agents did. *Cost:* the first
  slice plus about 200 lines deleted from `system-turn`'s opening and refresh path. *Sacrifice:* opening values
  have no `result/e…` handle, and the transcript's past openings are visible only in captures.
- **B. Recorded as today, produced as data.** Keep `system-turn`, and feed it entries instead of strings.
  *Guarantee:* handles and the since-diff stay. *Cost:* the smallest code change. *Sacrifice:* keeps the
  refresh-by-reprint braid (B6) and the history growth, and the read-only fork is unused.
- **C. Recorded on change only.** A render is evaluated per prompt, and only entries whose shown text changed
  since the last turn are appended as system evaluations. *Guarantee:* handles for what changed. *Cost:* A plus a
  diff writer. *Sacrifice:* two paths for one render (fresh and recorded).

## 7. Verification limits, cleanup and RESET NEEDED

- **The MCP SCI path itself was not exercised** (D1). The reality check called its owners from a JVM scratch
  namespace, which is the same composition minus the bridge template.
- **Branch `:design-repl-entry-renders` and its hosted agent `mcp/dre` remain.** `branch retire` throws on the
  uninstalled `:seon.agent/admission-closed-tx` (D2). The branch holds the probe's disposable rows: notes
  `dre-preview-probe`, `dre-preview-2` and `dre-ro2` (written through the preview, D6), and `user/dre-sq` with
  `user/dre-sq-test`. **Root:** after D2's attribute is installed, run `branch retire design-repl-entry-renders`.
  Default was not stopped, and none of its Vars were redefined. The scratch JVM namespace `tmp.dre-probe` was
  removed.
- Probes ran unarmed where they called owners from a scratch namespace. The evaluation stages themselves ran
  armed (the profile lines show armed definitions).
- No source was changed and no tests ran. The first slice's regressions are listed in §4 for root to run through
  `bin/test-check default --ns …` after implementation.
- Raw evidence: `tmp/design-repl-entry-renders/` (root prompt today, debug page N=3, the three transcripts).
