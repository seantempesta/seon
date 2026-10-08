---
title: Agent-loop bounded split, independent Sol review
type: research
status: superseded 2026-10-08 by the agent-loop ledger
date: 2026-10-08
lane: spec-split-sol
---

> **Superseded 2026-10-08** by [the agent-loop ledger](../../../../seon/schedule/agent-loop-ledger.md). Its S5-07a-f table is held there as one row blocked on `redo/pass-and-recording.md`. Dated evidence only; do not launch from it.

# Agent-loop split — spec-split-sol

The installed owners already provide an interleaved batch fold, two recording entrances and a per-agent Flow proc. Split by those owners: make the lookup changes independently, retain the approved atomic origin conversion, then replace each context/recording/lifecycle family in place. Do not launch S4–S7 as their present §5 rows.

This is a specification and root decision packet, not an implementation or a claim that the target APIs exist. **Ready** means the change is specified below; **blocked** means the named question must be answered and its contract/proof closure reviewed before an implementation lane starts. A blocked row is deliberately not advertised as a fifteen-minute implementation. No prototype ran in default.

## Authorities and evidence

Read: `CLAUDE.md`; the approved `docs/seon/specs/agent-loop.md`; `2026-10-07/lane-rules.md`; S1 v3.1, S3 v2 and S2b-1 v3 design notes. The data-oriented Clojure, REPL, testing, modeling, Datahike and Flow skills supplied the boundary rules. Root's rulings in S3 v2 supersede the larger S3 row in the spec. The baseline source anchors below were verified when HEAD was **a4d438dc6c9c7dc130e83d48a5b76b007f8cb1e0**, using `git show`, not another lane's working files. During this review root landed S1-01 (`f50ef6059`) and S1-02 (`438328b34`); those rows are retained as the split inventory but are **already complete, do not relaunch**. Source anchors for the rest remain that audited revision; reconcile later landings before assignment. New names are explicitly labelled new. Rebase anchors before assigning after another landing.

Dependency gitlinks at that HEAD: Malli `43c6dca8d2`, SCI `ce0aa53b9b`, Datahike `e5453d967b`, core.async `dc35f3e0d7`. These are guarantees used here:

| Seam | Guarantee, inputs, event and cost |
|---|---|
| Malli `core.cljc:2672`, `validator` | Compile/reuse the validator of the supplied schema; schema object owns caching. Compile touched contracts once against the packaged projection; no per-lookup recompile. |
| SCI `core.cljc:112`, `copy-var*` | Copies doc/arglists/name/ns, omits Malli metadata; lookup reads current SCI metadata, DB supplies program contract. No host metadata fallback. |
| SCI `impl/namespaces.cljc:2303`, `dir-fn` | Current namespace public names, alias aware; guard unknown namespace first. Cost follows that namespace. |
| SCI `impl/namespaces.cljc:2351`, `find-doc`; `:2369`, `apropos` | Existing metadata walk and name search. The former prints today; adapt its walk into data, never its printer. Cost follows loaded interns. |
| Datahike `api/impl.cljc:111`, `rseek-datoms`; `:295`, `index-range` | Seek/range delegates to native indexes; reverse seek is not restricted to the requested agent/attribute. Check both before accepting its datom. |
| Datahike `db/transaction.cljc:1055`, `in-transaction-db`, and `:1172` | A transaction function sees earlier operations and splices its result; pure numbering at the writer, no counter atom. |
| core.async `flow.clj:234` | Transition receives stop/pause/resume and carries state. Stop is not proof of an active host call's exit; preserve Seon's actual-exit owner. |

Health/evidence, broken first:

- Initial `bin/seon status` and MCP `runtime_status` reached pid 65866/archive `7f1b865…`, not current HEAD. Health `:faulted`, three open faults, five over-bound warnings; `seon.schema/call-with-projection` max 15,254 ms and `seon.db/transact-call` max 7,803 ms. These are status observations, not timings of this lane's source reads.
- Read-only MCP JVM form as explicit root/default:

  ```clojure
  (let [connection (seon.cluster.boot/connection "default")
        database (seon.db/db connection)]
    {:basis (seon.db/basis-t database)
     :installed (into {} (map (fn [s] [s (boolean (ns-resolve (symbol (namespace s)) (symbol (name s))))])
                      '[seon.turn/system-turn seon.turn/record-evaluated-call
                        seon.turn.batch/test-request seon.eval/of-agent seon.task/assign!
                        seon.test/verified? seon.sci.eval/documentation-value seon.cluster.agent/turn-step])
     :origin-schema (seon.db/pull database [:seon.schema/key :seon.schema/form]
                                [:seon.schema/key :seon.cluster.eval/origin])})
  ```

  Answer was an error, `:seon.error/message "The run was evaluated but not recorded."`, offending `#:seon.batch{:applied true, :tx-count 0}`; no timing/result delivered. Existing defect class: `seon.batch/a-jvm-run-answers-evaluated-but-not-recorded`, declared at `resources/seon/tasks/seon.batch.edn:1`; no new guessed handle was minted. The absence of a delivered value prevents interpreting the origin-schema result.
- A subsequent minimal read answered `No live Seon JVM for cluster default`. No start/reset/stop was attempted. Independent source work continued.
- Root's replacement subsequently appeared as pid 89251/archive `a4d438dc6…`. Explicit root/default JVM read:

  ```clojure
  (let [connection (seon.cluster.boot/connection "default")]
    {:basis (seon.db/basis-t (seon.db/db connection))
     :installed (mapv (fn [s] [s (boolean (ns-resolve (symbol (namespace s)) (symbol (name s))))])
                     '[seon.turn/system-turn seon.turn/record-evaluated-call seon.turn.batch/test-request
                       seon.eval/of-agent seon.task/assign! seon.test/verified?
                       seon.sci.eval/documentation-value seon.cluster.agent/turn-step])})
  ```

  Answer `result/e1faacafed9e8`, **1 ms**, basis `536871549`, all eight Vars present, no delivered error. This proves presence only: it does not prove target semantics or a production caller for `test-request`.
- Latest operator health remained faulted: `seon.turn.world/record-fault` branch-custody failure (status supplied existing task id `3d2fb1b41dc8`; `record-fault` is the producer symbol, not a verified task handle), a store-roster refusal, transaction max 5,291 ms. No further mutation/prototype was necessary. RESET NEEDED from this lane: no.

## Assignment contract and budget rows

Every code row below includes its proof in the same commit. Existing means the named deftest exists at pinned HEAD, **not that it was green here**. New means write that exact test, through the agent entrance/canonical writer, and assert only the described DATA. New tests go in the stated existing test file; no fixture copies or Var replacement. A slice touching a schema also publishes/adopts on a live-store branch and compiles its consumers. No schema-only staging commit with no producer.

The fifteen-minute estimate covers one implementation family and its focused proof; cold boot/check overhead is reported separately. If the family cannot fit roughly 150 edited lines plus its focused proof, return the measured boundary to root; do not silently absorb the next row. A single deletion can exceed that number in removed lines. All ready rows require HEAD boot, the listed tests green, zero lint errors and a commit/release. Changed function contracts below are exact intended input/output semantics; use existing registered members and error variants. Preserve installed read-refusal unions until errors S-reads.

Budget codes used on **every** row:

| Code | Exact probe and row (wall ms, allocated MB; also armed calls, attempted/durable commits, store growth) |
|---|---|
| L | `seon.pass-probe/window` (`test/seon/pass_probe.clj:129`) around that row's exact lookup through MCP SCI on one retained disposable agent; first then five warm calls. Measure owner evaluation separately from whole pass; owner <1 s/<10 MB, no worsening. Whole ordinary pass target <200 ms/<5 MB, one machinery commit. Never subtract two noisy windows and call the result owner allocation. |
| O | `(seon.pass-probe/run-mcp running "sci" 1 #{} false)` and JVM equivalent (`:307`), plus `run-model running 1 #{}` (`:328`) through `support/with-provider` HTTP fixture. No paid provider. Same parent/change, ordinary <200 ms/<5 MB, definition <300 ms/<30 MB, after-definition <150 ms/<10 MB; report known misses without claiming this slice fixes all of them. |
| H | 1/10/200 exchanges with ~2 KB shown text each, after a fresh proc acquisition and after append; `window` around the owning acquisition/append. Append <10 ms and <=3× appended text allocation. Cold read ms/MB recorded once; root sets its budget from the **composed** landed projection. S3 partial seek numbers are not the budget. |
| I | Fixture-driven opening and input, separate `window`s then one wake cycle: each <200 ms/<5 MB and one machinery commit. Count task-home delivery writes separately. |
| T | `(seon.pass-probe/run-check running 'seon.test-test/interpreted-test-bodies-use-the-sci-interrupt-bound 2)` (`:360`): one cold, one warm; runner overhead <300/<150 ms, <20 MB, excluding body work. Also report member and request commits separately. |
| D | The row's named narrow existing proof, with `window` on its actual entrance; deleted path has zero calls/writes afterwards. Report parent/change ms/MB and file/source/test line delta; do not invent a speedup for deletion alone. |
| F | Deterministic injected refusal/timeout/late-exit scenarios through existing writer/body injection. Count attempts, durable commits and held resources; measure successful O row too. No timeout sleep in tests; use `support/await-event!`. Unknown-outcome ownership is not a latency claim. |

Unavailable per-thread allocation on a virtual thread is **unknown**, never 0 (existing task `seon.batch/allocation-is-unmeasured-on-a-virtual-thread`). A whole-JVM window includes other work; identify it as such. A number worse than parent blocks landing. S0 targets do not authorize an unrelated performance rewrite in a fifteen-minute slice.

## S1 — independently reachable lookup families

All S1 evaluation-owner rows serialize because they own `src/seon/sci/eval.clj` and `test/seon/sci/documentation_test.clj`. Do not put nine agents on different hunks of those files. Temporary qualified-only lookup availability is loadable; `(help)` teaches only the final installed set. Parallel lanes are the file-disjoint render/dead-owner/deletion rows listed later.

### S1-01 — delete the unwired loop namespace (landed f50ef6059; group A; no dependencies)

- Baseline ownership (now deleted by the cited landing): `src/seon/agent/loop.clj:26–230` (all functions), `test/seon/agent/loop_test.clj:1`, and `resources/seon/schemas/seon.agent.loop.edn:1`. No production source caller exists outside the deleted namespace; its tests are its only callers.
- Correct references in `resources/seon/tasks/seon.task.edn:15`, `resources/seon/tasks/seon.bootstrap.edn:13`, `docs/seon/architecture/reference-code.md:48`, and the four stale passages in `2026-10-07/walk-delete/{design-notes.md:24,108,134,walk-delete-notes.md:12}`. Say S4 uses the proc transcript; never invent `seon.agent.loop/context` (no such HEAD function).
- Contracts: none added/changed; retire schema and its tests with their sole producer. Keep `seon.bootstrap/help-value` unchanged here.
- Proof: no new deletion test. Existing `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`; candidate publication/adoption; full reference search excluding dated evidence must have no live target. Done when boot and retirement succeed. Budget D.

### S1-02 — namespace directory render with one pull (landed 438328b34; group A; no dependencies)

- Baseline ownership (now changed by the cited landing): `src/seon/render/ns.clj:219` `public-functions`, `:235` `directory`, `:249` `render-ai`, `:322` `render-html`, and `:192` `function-ai` docstring; `resources/seon/schemas/seon.render.ns.edn`; `test/seon/render/ns_test.clj`.
- Replace the reverse pull's two fields with `sym/private?/doc/arglists` plus namespace aliases, unlimited. `public-functions` returns that **held pull**, and each render face calls it once, and its names or directory rows derive from that held pull. Convert `render-html` from sorting the old symbol vector to filtering public rows and deriving their local symbols; do not render AI first or make a second pull in HTML. `directory` reads arglists with `seon.sci.reader/read` using those held aliases; columns become `[:sym :arglists :summary]`. Remove its call to `sci.eval/directory-value` and per-function contracts. Do not change `sci.eval/arglists-reader` here: move no code across this file boundary.
- Contracts: `public-functions [unit namespace-symbol] -> maybe pulled namespace under exactly that selector | :seon.db/invalid-read-error`; the pulled map declares optional aliases (local/target-ns symbol pairs) and optional unlimited reverse function vector (sym/private? required; doc/arglists optional, with their existing registered attribute schemas). Missing pull stays nil rather than a invented namespace; `directory [unit namespace-symbol] -> existing columns/rows shape with three columns | existing DB/read errors`; `render-ai [unit] -> existing namespace-view | existing errors`, narrowing description only; `render-html [unit] -> :seon.render/hiccup | :seon.db/invalid-read-error` unchanged. Arglists read failure remains its returned data, not `[]`. `function-ai` only loses the false unescaped-text claim.
- Proof existing `seon.render.ns-test/a-namespace-renders-as-one-graph-node` (`ns_test.clj:60`), converted data expectation, and `seon.render.ns-test/namespace-bindings-render-in-clojures-own-words` (`:91`). New row in the former: aliased keyword in stored arglists resolves to its full keyword and private function absent. Done when member names and rows come from the one pull, zero documentation-contract calls. Budget L, render `seon.task` at distance 1.

### S1-03 — keep interpreter bindings out of stored namespaces (blocked Q1; group B)

- Exact owner: `src/seon/fn.clj:201` `namespace-entity`, `:846` `interpreter-refer-entities`, `:865` `runtime-namespace-form`, `:944` `runtime-analysis-batch`; `test/seon/fn_test.clj`. S1 v3.1 §4.2 still delegates a design choice to the implementer.
- Recommended resolution: keep the augmented analysis prelude, retain the unaugmented runtime namespace form by its namespace/span in the **existing analysis value**, and supply that held source to `namespace-entity` for runtime declarations only. Do not blindly subtract matching refers from authored file namespaces: an explicit authored `[clojure.repl :refer [doc]]` is real source. No printed-source regex rewrite.
- Contracts: keep `runtime-analysis-batch`'s two arities `(database, vector requests [, dependency-analysis]) -> analysis map`; its analysis value must additionally carry the exact original namespace-source association. `namespace-entity` remains `(projection, contexts, context, entry) -> entity`, but contexts' source authority must distinguish authored and synthetic spans. The **exact association key/type is undecided**; this row cannot launch until Q1 supplies it. No new stored schema/registry.
- Proof existing `seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch` (`:80`), `seon.fn-test/a-fold-derived-definition-has-static-index-edge-parity` (`:480`). New `seon.fn-test/runtime-namespace-source-keeps-only-authored-refers`: runtime definition stores no interpreter-generated refers/source require; explicit authored refer remains; stored call edge for bare `doc` remains the declared target. Assert stored source/refers/call-arities data. Done when write-back source loads without retired bootstrap macros. Budget O definition/after-definition.

### S1-04 — doc metadata and authored reference classification (blocked Q17; group C; after S1-03)

- Own `src/seon/sci/eval.clj:1848` `documentation-unavailable`, `:1997` `function-doc-map`, `:2056` `documentation-value`, `:2079` `program-doc-var`; schema `resources/seon/schemas/seon.sci.eval.edn`; documentation test file.
- Add `reference-in` (new) and widen macro inputs to authored syntax. Alias-qualified symbols expand by the current SCI alias, simple resolved Vars use `sci/var->symbol`; other syntax passes unchanged. Use SCI Var doc/arglists, retain `agent-documentation-contract` and override note from DB. Unknown/list/number return the widened existing documentation-unavailable variant. Do not rewrite the contract owner or add library-host metadata fallback.
- Contracts: new `authored-reference` is the declared `:any` syntax boundary (reason: lookup classifies arbitrary authored syntax, generator includes `nil 42 "x" 'x '(quote x) :x :seon.task/intent`); `reference-in [ctx authored] -> authored-reference`; `documentation-unavailable [authored] -> documentation-unavailable-error` whose member uses that boundary. `function-doc-map [database row metadata]` and `[database row metadata overridden?]` replace its old two/three input arities; metadata is a declared open map with optional `:doc :string` and `:arglists :seon.schema/value`, or nil when no SCI Var exists. Output retains the current doc-parts/arglists/contract/note map or DB error; convert its sole external caller `documentation-value` and its recursive call. `directory-value` does **not** call this function at HEAD and remains unchanged until S1-07a. Resolve current metadata at macro execution and pass it explicitly; never keep a copied host Var. `documentation-value [db authored resolved] -> lookup data | existing read errors | documentation-unavailable-error`. Macro constructor `program-doc-var [] -> existing SCI Var boundary`, expands reference resolution at execution, not construction. Keep `documentation-value` three arguments; current SCI metadata comes from the supplied evaluation world at its call, the existing registered ctx member, and is passed explicitly to the private helper. If the installed supply cannot provide this member without changing the public arity, stop for Q17 instead of an ambient lookup.
- Delete `function-doc-map`'s arglists re-read, not the helper still used by old directory. Program row without SCI Var gets `declaration-absent` arglists. Pure SCI/library Var returns its metadata parts without a contract invented by Seon.
- Existing proofs `seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy`, `seon.sci.documentation-test/documentation-keeps-named-contracts-and-guarded-result-shapes`, `seon.sci.documentation-test/a-named-private-override-documents-the-jvm-boundary`. New `seon.sci.documentation-test/doc-classifies-authored-references`: unknown symbol, `(doc 'x)`, `(doc 42)` have documentation-unavailable type; alias and own def show current doc/arglists; `:out` empty. Done when both current program doc and library/own Var doc answer data. Budget L `(doc seon.task/create!)`, `(doc clojure.set/union)`.

### S1-05 — schema and namespace doc branches (ready; group C; after S1-04)

- Same eval/schema/documentation-test files; `documentation-value:2056`, `program-documentation:1835` until S1-07a deletes its directory caller. Add the schema/namespace branches **inside this owner**, not a second doc dispatcher.
- Qualified keyword: one schema pull -> key, EDN form, description. Simple namespace: SCI metadata plus directory public names, DB namespace doc for program namespaces; no `:in [] :out []`. Unknown schema/ns uses documentation-unavailable. Check `sci/find-ns` before `dir-fn`.
- Contract `documentation-value` retains S1-04 inputs; result union gains named `schema-doc` (`{:key qualified-keyword :form schema-definition :description string? optional}`) and `namespace-doc` (docstring parts plus `:publics` vector symbols); old public-function map/error alternatives remain. `docstring-parts [string] -> {:summary string :body string :example string}` unchanged.
- Existing `seon.sci.documentation-test/an-example-ends-at-the-next-fixed-heading` (`:327`). Extend new `seon.sci.documentation-test/doc-classifies-authored-references`: declared keyword returns exact parsed form, unknown keyword returns error type, known empty namespace has empty publics, unknown namespace refuses. Done when schema and namespace inputs are admitted by instrumentation. Budget L `(doc :seon.task/intent)`, `(doc seon.task)`.

### S1-06 — task and test doc branches (ready; group C; after S1-05)

- Same eval/schema/documentation-test files, `documentation-value:2056`; read-only dependencies `src/seon/task.clj:381` `home`, `src/seon/test.clj:1425` `recorded-result`, `:1562` `verified?`. No edits to those owners.
- After Var resolution, task-symbol is one full pull at explicit task home and returned as the task; test-symbol returns `{:sym t :recorded result :verified? verdict}`. No task-edge walk, copied task, latest-green predicate or result on another branch.
- Contract `documentation-value` union adds pulled task and `test-doc`, where `:recorded` has the exact recorded-result union and `:verified?` has `[:or :boolean :seon.test/host-error]`. Retain existing read errors. Task/Var collision resolves Var first. Delete nothing beyond unreachable old branch code within that function.
- New exact proofs `seon.sci.documentation-test/task-doc-reads-home-declaration-and-branch-proofs` (task created/revised at home after fork is found, must-read set retained, branch-tagged proof remains branch-local); `seon.sci.documentation-test/lookup-does-not-certify-a-stale-test-result` (after reached-definition change, recorded green still visible and verified? false; incomplete latest execution does not expose older green). Done when data and custody match S1 v3.1 §4.3. Budget L on a task with must-read and a test with an actual recorded result; missing-result timing is not enough.

### S1-07a — SCI directory data (ready; group C; after S1-06, S1-02 and S1-01)

- Own eval/schema/documentation-test files; `directory-value:2019`, `program-dir-var:2095`, `install-program-doc!:2137`, `build-base-ctx:348`; `src/seon/repl.clj:37` directory render pair and `resources/seon/schemas/seon.repl.edn`.
- Replace `directory-value` in place (keep name until installer cut) with SCI `dir-fn` -> summaries, no contracts; macro passes authored syntax/current ctx and alias expansion. Qualified directory remains usable through the current installer; bare referrals are still its current installation until S1-09. Searches are the next separate item S1-07b.
- Contracts: `directory-value [ctx authored] -> var-summaries | documentation-unavailable-error`; ctx is registered `:seon.sci.eval/ctx`, authored is the authored-reference boundary. Macro passes its supplied calling context explicitly after S1-04 alias resolution, replacing `[db namespace present?]` at every call. S1-01/02 have removed the two other callers. No DB read is needed for this directory. `var-summary` has `:sym :symbol`, optional native arglists and summary string; `var-summaries` vector. `program-dir-var [] -> SCI Var boundary`; changed installer retains its ctx/db/projection signature and returns ctx.
- Delete `program-documentation`, directory render AI/HTML and its directory schema; **retain `program-documentation-selector` because `documentation-value` still uses it**; `arglists-reader` when its last eval caller leaves (render row S1-02 reads aliases locally). Do not leave wrappers forwarding retired directory shapes.
- Existing `seon.sci.documentation-test/directory-observes-a-function-installed-after-context-acquisition` (vector shape). New `seon.sci.documentation-test/directory-answers-data`: program/library/unknown namespace; known empty namespace returns []; rows preserve native arglists and summary, no contracts or `:out`. Done when lookup names/metadata are SCI-owned; all deleted references converted. Budget L `(dir seon.task)`.

### S1-07b — SCI search data (ready; group C; after S1-07a)

- Own `src/seon/sci/eval.clj:348` build-base-ctx and `:2137` install-program-doc!, the eval schema and documentation-test file; new private `apropos-value` and `find-doc-value` in the existing lookup section. Install qualified SCI Vars beside doc/dir; bare names arrive in S1-09.
- Use existing SCI apropos result as a vector; adapt find-doc's metadata walk over interned Vars and namespace metadata into var-summaries. Delete its printing at this one replacement boundary; no second printer or regex parser.
- Contracts: `apropos-value [ctx lookup-pattern] -> [:vector :qualified-symbol]`; `find-doc-value [ctx lookup-pattern] -> var-summaries`, including simple namespace symbols. `lookup-pattern` is string (empty allowed) or the existing named regex predicate; ctx is `:seon.sci.eval/ctx`. Existing installer signature/result is unchanged. Per-entry summaries use `docstring-parts`, optional native arglists, and no contract lookup.
- New `seon.sci.documentation-test/searches-answer-data`: exact matching qualified Var symbols, documented namespace returned, empty pattern admitted, regex/string equivalence, `:out` empty. Existing `seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy` guards the shared lookup boundary. Done when qualified searches return DATA and no print-doc call remains in their path. Budget L `(clojure.repl/apropos "task")`, `(clojure.repl/find-doc "symbol-in")`.

### S1-08 — source returns code data (ready; group C; after S1-04)

- Own eval/schema/documentation-test files; `stored-source:2106` retained, `stored-source-fn:2127` body adapted, `install-program-doc!:2137` gains qualified source macro. Read-only host dependency `clojure.repl/source-fn`.
- New `source-value` resolves authored input through S1-04, gets branch stored source first (private/tests included), then host library source, else documentation-unavailable. Macro returns `{:seon.sci.eval/sym sym :seon.render/ai source-text}`; source-fn returns bare string or nil. No extra `:source` duplicate, disk reader or printer change.
- Contracts: `source-value [db authored] -> named source-value | documentation-unavailable-error`; source-value map requires qualified symbol and string `:seon.render/ai`; `stored-source-fn [symbol] -> maybe string` unchanged; macro constructor `[] -> SCI Var boundary`. Source operation quotes syntax once and evaluates no target body.
- Existing `seon.sci.documentation-test/source-at-the-agent-repl-prints-the-stored-declaration` (`:306`) converts to source map DATA; add program-private/test/library/missing rows. The printer-fix already landed at `131082e0d`; one declared golden can compare root shown source if root wants it, but that is not a second source printer test. Done when qualified source and source-fn work, nested value holds source once. Budget L `(clojure.repl/source seon.id/symbol-in)`.

### S1-09 — one lookup installation and bare analysis targets (ready; group C; after S1-03/07b/08)

- Own eval, `resources/seon/schemas/seon.sci.binding.edn`, documentation tests and `test/seon/fn_test.clj`; constructors `program-doc-var:2079`, `program-dir-var:2095`, installer `:2137`, call `:2391`, build `:348`, acquisition refer `:2566`.
- Add lookup Vars to `sci/init` namespaces after host injected copies; install once. Add `refer-declared-bindings! [ctx projection] -> ctx`, resolve every declared target from ctx then refer the same Var into core at build/acquisition. Retarget doc/dir, add source/apropos/find-doc declarations. Help remains acquired program Var.
- Delete old installer and call, bootstrap doc/dir macros (`src/seon/bootstrap.clj:161,166`) and build's duplicate bootstrap adds; macro constructors are **renamed/moved in this same commit** to exact new names `doc-var`, `dir-var`, `source-var` and `source-fn` (the latter replaces `stored-source-fn`, not the host library function). Constructors keep `[] -> SCI Var boundary`; `source-fn [symbol] -> [:maybe :string]` and every call converted. No temporary compatibility macro. Retarget task must-reads in `resources/seon/tasks/seon.sci.eval.edn:123,190,204` to surviving owners and update documentation task refs.
- Existing proofs `seon.sci.documentation-test/retained-context-receives-the-current-repl-macro-through-its-core-alias`, `seon.sci.documentation-test/bare-test-macros-resolve-without-namespace-referrals`, `seon.sci.documentation-test/retained-context-receives-new-bare-test-referrals`. New `seon.sci.documentation-test/bare-and-qualified-lookups-share-vars`: all five identical Vars fresh/stored/retained; acquired branch help has bare=qualified Var. New `seon.fn-test/lookup-analysis-names-the-installed-repl-vars`: stored call-arities target each `clojure.repl/*` for bare/qualified/explicit authored refer forms.
- Done when both spellings and analysis agree, base init/fork ordering proven in **disposable** context, bootstrap source no longer required for doc/dir. Budget O; acquisition counts and L bare forms.

### S1-10 — literal help and correct directory forms (ready; group A after S1-09; collision with S1-01 on bootstrap task file)

- Own `src/seon/bootstrap.clj:12` help, `:17` help-value, `:67,73` renderers; `resources/seon/schemas/seon.help.edn:1`, `test/seon/help_test.clj`; `src/seon/render/ns.clj:77` namespace-form; `src/seon/run.clj:58` usage-form; affected directory-form tests in `test/my/turn_test.clj`, `test/seon/render/history_test.clj`, `test/seon/turn_test.clj`; `resources/seon/tasks/seon.documentation.edn` and bootstrap task declaration.
- `help-value [] -> vector help-entry`, help-entry requires string `:seon.help/says` and vector authored forms `:seon.help/forms`. No DB input/render pair. `help` expands to zero-arg help-value. Entries are the S1 approved quoted examples, handles/errors/DB operations and **the landed S2b-1 same-line rule**. Fix `namespace-form` and usage-form to `(dir ns)`; preserve their existing read-form/form contracts.
- Delete help render pair and its embedded EDN scanner; delete existing `seon.help-test/help-evidence-tracks-its-code-version` with its read-evidence subject (reported red in S1 design, not rerun here). Do not teach future bail/completion APIs yet.
- New `seon.help-test/help-names-only-installed-forms`: resolve each list head, excluding example-local defn/deftest names, in a fresh agent world; assert resolution booleans DATA, no parsing help text. Existing `seon.help-test/an-agent-identity-offers-help-as-its-operation`; convert exact generated-form assertions in `my.turn-test/the-usage-test-renders-the-lifecycle-walkthrough` (`turn_test.clj:73`), `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain` (`history_test.clj:26`), `seon.render.history-test/a-neighbour-the-walk-reached-renders-by-its-own-shape` (`:131`), and `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix` (`turn_test.clj:994`). Add row to `seon.render.ns-test/a-namespace-renders-as-one-graph-node`: evaluate namespace-form's held output, directory answers vector. Done when help has no reads and every shown operation exists. Budget L `(help)` and generated directory form.

### S1-11 — retire my.program/source family (ready; group A after S1-08/10)

- Own `src/my/program.clj:849` file-line, `:862` nearest, `:874` source; retain declaration `:837`, search `:914`, declaration-diff `:1035` but correct their source doc references (`:918,1039`). Own `test/my/program_read_test.clj:7,20` and `resources/seon/tasks/my.program.edn:59`; convert caller `test/my/program_test.clj:135` to standard source DATA.
- Delete the disk source/nearest family and its two subject tests; preserve declaration/search. This is a named behavior change: missing lookup no longer fuzzy-ranks neighbours; use apropos separately. No new function/contract.
- Existing `my.program-read-test/search-ranks-bounds-and-offers-more` (`:27`) and `my.program-read-test/sizes-list-lines-largest-first` (`:38`); standard source proof in S1-08. Done when live caller/reference search has no retired source API. Budget D and L source. No fixture history rewrites of captured old replies.

## S3 — retain the approved atomic conversion

### S3-01 — author to origin everywhere (ready; group B; after S2b-1, already landed)

- Own `src/seon/turn.clj:485` plan-tx-for-author/plan-tx, `:597` source-rows, `:635` plan-call, `:714` system-run-plan, `:791` append-generated-call, `:877` receipt-start-tx, `:897` receipt-row, `:919` receipt-start-call, `:1236` recorded-evaluation, `:1289` record-evaluated-call, `:1788` latest-evaluations, `:3294` terminal-agent-ordinal, `:4878` evaluate-sources generated gate; `src/seon/turn/batch.clj:637` evaluation-record; eval schema and data-modeling skill paragraph `:180`.
- Exactly S3 v2: enum `[:enum :model :outside]`, context-inert, optional on receipt. Model plan writes model, MCP SCI/JVM outside; refresh/generated paths write no origin. Four consumers convert; recorded duplicate comparison includes origin. Keep retired author declaration optional until S4; write no author after this commit. Fold plan-tx-for-author into plan-tx.
- Contracts: retain each existing arity/result schema; replace author map member with registered origin in plan-call and receipt start contracts. Add complete `receipt-row [run-eid request] -> :seon.cluster.eval/receipt` contract: run-eid is resolved positive eid; request declares required turn id/ordinal/at and optional source/comment/ns/origin, exactly receipt-start-tx's map plus optional existing `:seon.eval/origin`; output also declares string :db/id. It is the **initial receipt row**, not terminal content. `recorded-evaluation [request] -> immutable recorded-content` declares id/ordinal/at plus optional source/comment/origin and precisely receipt-terminal-attributes; read-evidence input admits vector of existing read-evidence-entity maps, output the set of those maps without :db/id. All field value types are the existing registered attributes; no run/ns requirement on this comparison projection. Preserve empty/missing evidence exactly as current cond-> does; no blanket `:map` escape for those two currently uncontracted functions. Tighten evaluation-record's result from :map to existing terminal recording-request shape with outside origin. No pass-id/ordinal work.
- Mechanical test conversion owns these files (25 author sites): bootstrap_test, cluster/turn_test, context_selection_test, loop_proof_test, render/transcript_run_test, schema/datahike_parity.edn, turn_loop_test, turn_test, turn_work_test. Convert only data assertions; old captured transcripts stay evidence. **This is one mechanical conversion, not nine independent code lanes**; splitting with dual stamps adds a temporary state and tests root did not approve. Expect ~100–150 edited lines; if compiling the new complete private contracts makes it larger, split that contract-only EQUIVALENT preparation as a separate prior lane naming the same existing proofs.
- New `seon.mcp-entrance-test/entrant-origin-is-recorded-on-each-evaluation`: SCI outside/JVM outside/fixture model model, exact stored origin, author absent. Existing `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`; `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix`, `seon.turn-test/receipt-transitions-preserve-one-terminal-outcome`, `seon.turn-test/recovery-preserves-terminal-receipts-exactly`, `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, `seon.turn-test/a-refused-refusal-settlement-is-answered-as-a-value`.
- Additional exact reaching conversion tests: `seon.bootstrap-test/creation-opens-one-zero-form-generated-run-with-a-real-task`; `seon.context-selection-test/compact-replaces-only-observed-evaluation-refs`; `seon.render.transcript-run-test/render-run-selects-only-the-requested-run`; `seon.turn-work-test/a-generated-run-resumes-then-requests-one-more-form`; `seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`, `seon.cluster.turn-test/accepted-first-party-definition-reaches-an-existing-agent-next-turn`, `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`, `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`; `seon.turn-test/generated-read-evidence-rejects-turn-activity`, `seon.turn-test/a-turn-dependent-generated-form-keeps-its-value-beside-the-fault`, `seon.turn-test/a-terminal-refusal-settles-when-its-run-has-vanished`, `seon.turn-test/run-derives-its-opening-database-and-starting-namespace`, `seon.turn-test/a-replaced-result-is-shown-by-the-one-print-owner`, `seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole`; `seon.turn-loop-test/a-refused-terminal-commit-still-closes-the-run`.
- Exact existing long proof `seon.loop-proof-test/virtual-loop-end-to-end` (`test/seon/loop_proof_test.clj:305`) and schema parity fixture run in root's central tier; report them as not run by this lane rather than green. The focused entrant/receipt proofs above remain mandatory before the S3 commit. Done when all new entrant rows agree, no new author write, replay idempotent and retirement-compatible schema adopted. Budget O, delta neutral.

## S4 — seven candidate families; Q2/Q3/Q4 block implementation

S4's missing exchange representation is the principal blocker. The spec requires a prose-only exchange **with no evaluation**, while HEAD records exchanges through evaluation receipts attached to runs. No existing value can be named as its complete new contract without deciding where that exchange lives. A purported fifteen-minute transcript patch that ignores this would drop history or invent a second store.

### S4-01 — writer ordinals and the first history consumer (blocked Q2; group B; after S3-01)

- Own `src/seon/db.clj` (new `last-agent-ordinal` adjacent to native read owners), `src/seon/turn.clj:956` receipt-settle-batch-call and `:1289` record-evaluated-call, `src/seon/eval.clj:17` of-agent; eval schema; `test/seon/context_selection_test.clj`, `test/seon/mcp_entrance_test.clj`.
- S3 v2 §6 algorithm: unique tuple `:seon.cluster.eval/agent-ordinal` `[agent-id positive ordinal]`, one assignment per ordered writer batch; replay gets no new number and comparison excludes the number. Reverse-seek accepts only same attr/agent. Narrow of-agent's ordered read to tuple range, retaining its public arities and requested selector.
- Contracts: new `last-agent-ordinal [database agent-id] -> nonnegative int | existing invalid-read error`; writer functions retain database/request -> transaction-data, throw existing write refusals. `of-agent` keeps both existing selector-dependent result/refusal unions. Tuple types validated by Seon's final writer, not raw Datahike's permissive tuple check. The first consumer prevents a schema-only dead fact; Q2 must settle assignment boundary before history filtering.
- New `seon.context-selection-test/evaluation-ordinals-follow-writer-order`: agents before/after all others, absent agent at each lexical boundary, multi-form batch, SCI/JVM writer order, refused write consumes no ordinal, identical replay no gap, conflicting replay refuses. Assert pulled tuples/ids only. Existing `seon.context-selection-test/selection-references-terminal-evaluations-in-writer-decided-order` converts ordering assertion. Done when stored tuples unique and narrow read exactly matches writer order. Budget H. If combined writer/read/schema proof exceeds 150 lines, first land ordinal+of-agent read on all origins, then filter assignment/origin with S4-03; do not separate its schema from its writer.

### S4-02 — opening once per assignment revision (blocked Q2; group B; after S1-10/S3-01)

- Own `src/seon/turn.clj:1719` declared-sources, `:2024` system-turn, `src/seon/bootstrap.clj:312` seed-tx; task/eval schemas; `test/seon/cluster/turn_test.clj`. Replace the opening producer in place with `(help)`, task doc, then references' doc/source; use the normal pass, origin opening, and one completion fact in its settlement.
- Proposed contract: opening input is existing assignment-view plus explicit db/agent world; result is ordinary finished pass plus assignment key/task revision. The **fact owner/key and whether references use doc or source are unresolved Q2**. Delete seed-generated run only when its caller routes to this opening; never disable refresh first. No consumerless optional fact preparation.
- New `seon.cluster.turn-test/opening-runs-once-per-assignment-revision`: first opening saved before provider, unchanged assignment does not rerun, changed revision/new assignment does, crash before completion reruns, opening never advances answeredness. Assert pass origins, evaluation count and completion fact. Existing `seon.cluster.turn-test/generated-opening-preserves-one-provider-turn-budget` converts. Done when producer and completion writer share the one path. Budget I.

### S4-03 — durable exchange projection and assignment history (blocked Q2; group B; after S4-01/02)

- Own `src/seon/render/transcript.clj:246` receipt-entry, `:726` history-entries, `src/seon/eval.clj:17` of-agent, `src/seon/repl.clj` stored emission/response owner and cost-attributes; their schema files and `test/seon/render/transcript_run_test.clj`.
- Reuse the stored-to-emission owner and schema-derived cost attributes; read exactly opening/input/model for the active assignment, exclude outside/test. Preserve original source/comment and every shown/error/diagnostic/fault/output/finding unit; no EDN decode of already printed result and no second render.
- Contract remains `history-entries [declared request] -> vector existing history-entry` until Q2 names the prose-only exchange shape; after that add exactly that union. Do not write a hand-listed pull decoder in seon.db. Delete transient re-render for stored units only, not HTML's own consumer path.
- New `seon.render.transcript-run-test/reacquired-history-is-the-finished-exchange`: append equals restart for values/errors/diagnostics/faults/output/findings/trailing prose and prose-only no-eval exchange; another assignment excluded; outside/test excluded by same predicate. This is a declared golden/round-trip law, not dozens of text tests. Existing `seon.render.transcript-run-test/render-run-selects-only-the-requested-run`. Done when cold history and appended history are the same held units. Budget H.

### S4-04 — proc transcript state and whole-window prompt (blocked Q2/Q4; group B; after S4-03)

- Own `src/seon/turn.clj:4390` call-turn and `:5824` step; `src/seon/cluster/prompt.clj:349` acquire-context-report/`:403` prompt, prompt schema; `test/seon/cluster/prompt_test.clj`.
- Acquire transcript once in proc init/new assignment, append finished passes, supply held text at call-turn. Replace prompt's walk/selection with token-budget validation of that text; refuse over-window, no clipping. Keep settings/calibration resolution once per call.
- Contract `step` retains Flow four arities and report shape, state additionally holds assignment identity plus transcript (owned by proc, no atom/cache); `prompt [db request] -> existing rendered-context | declared prompt error`, request gains held transcript and presented basis **as decided by Q2/Q4**. `call-turn` retains its report contract. Delete select/compose/elision helpers only when all their callers have converted in this commit; otherwise separate S4-07 after their last caller leaves.
- Existing `seon.cluster.prompt-test/the-history-path-never-refits-a-rendered-unit`, `seon.cluster.prompt-test/the-prompt-reads-the-cluster-its-caller-names`; convert `seon.cluster.prompt-test/a-budget-smaller-than-the-history-keeps-whole-newest-units` to new `seon.cluster.prompt-test/an-over-window-transcript-refuses-without-dropping-exchanges`, assert error type+budget report, not a text substring. Done when normal repeated call has zero cold-history acquisition and provider receives whole held transcript. Budget H/O.

### S4-05 — message/error input pass (blocked Q3; group B; after S4-04)

- Own `src/seon/context.clj:257` message-custody, `src/seon/turn.clj:3173` delivery-rows and input dispatch from step, task/eval/input schemas and `test/seon/cluster/turn_test.clj`.
- One generated form returns newly delivered identities/data at captured per-home bases, origin input, its writer records **exactly identities read**. Replace message history append as inputs become authoritative; no second inbox state. Errors at task home use explicit home custody.
- Contract and form name are explicitly TBD in spec §4.4. Q3 must name input request/result, stored delivery membership and writer validation; cannot infer them from message-custody. After decision: `(db-value, agent-id, delivered identity set, per-home bases) -> undelivered input data | declared read refusal`; settlement request carries exact read identities. No high-water transaction comparison across homes.
- New `seon.cluster.turn-test/input-records-only-the-items-it-read`: message arriving between read/settlement remains due; restart does not redeliver committed input; branch/home t values never compared; error delivered in next input. Assert identity sets and stored delivery facts. Done when input shown once and answeredness unchanged. Budget I.

### S4-06 — remove refresh and evaluation read-evidence writes (blocked Q2; group B; after S4-02/04/05)

- Own turn `:1686` unit-read, declared-sources, latest-evaluations, read-only-evaluation?, code-currency/current?, system-plan, generated-read-fault, system-turn (`:1686–2232`), `:1217` receipt-read-evidence-tx; `src/seon/render.clj:1721` acquire-context! callers; eval evidence schema; `test/seon/turn_test.clj`, `test/seon/turn_error_test.clj`.
- Delete refresh owner and its last callers atomically; stop persisting evaluation freshness evidence. Retain runtime observation/evidence used by rendering, proof reach and contract instrumentation. **Do not delete the evaluation entity schema named receipt, its run ref, or effect provenance yet**; Q2 distinguishes freshness from the recording family.
- Retained settlement contracts lose optional persisted read-evidence/executed/code-evidence only after last consumers' closure is established; no new API. Delete refresh-specific tests (`generated-read-evidence-rejects-turn-activity`, `a-turn-dependent-generated-form-keeps-its-value-beside-the-fault`, generated refresh cases in system-turn tests); replacement opening/history proofs are S4-02/03.
- New `seon.turn-test/settlement-does-not-store-refresh-evidence`: form makes 2,000 finite reads, returned result correct, zero new evidence/executed/code-evidence datoms. Done when no refresh source/call/schema target survives, adopted retirement proven before reset. Budget O plus same read-heavy row. This is one large deletion family, not fifteen-minute code design; root must approve its complete caller closure first.

### S4-07 — delete old capture/walk prompt closure (blocked Q4; group D; after S4-04/06)

- Own `src/seon/context.clj:309–415` capture-ai/html/contribution-entity/capture-tx/contribution-hash; capture call in turn `:4390`; `src/seon/cluster/prompt.clj:171–402` history selection/capture mismatch; old context-walk entry in render `:1721` **only after enumerating web/debug callers**; schemas and prompt/context selection tests.
- Delete captured prompt/contribution writes, obsolete walk/context composition and only tests of those mechanisms. Do not delete ordinary entity rendering or live HTML history. Each removed function's callers/ref schema leave with it.
- No new contracts. Convert current prompt proof to held transcript; new proof `seon.cluster.prompt-test/a-model-call-stores-basis-without-prompt-capture` asserts basis stored, capture datoms zero. Existing `seon.cluster.prompt-test/identical-context-does-not-depend-on-a-retained-prompt-cache`. Done when generated prompt has no walk/capture path and web consumers have a declared surviving data entrance. Budget O/H. Q4 must give exact web-file/test closure; until then this deletion is not assignable.

## S5 — recording; perform the admission design before the final switch

### S5-01 — pending transaction ownership (blocked Q5; group E; independent of S4)

- Own `src/seon/db.clj:5683` transact-call, `resources/seon/schemas/seon.db.edn:1`, `seon.db.write.edn:1`, `seon.db.write.attempt.edn:1` and `seon.blob.edn:1` under `resources/seon/schemas/`, `src/seon/blob.clj:304` with-publication!, writer/blob tests. Current transact-call discards the pending promise after timeout; with-publication! always releases permit in finally.
- Expose completion as runtime data and retain blob permit until its actual terminal outcome. Known refusal may retry; unknown cannot. No pending promise in a persisted error, no fallback writer/retry registry.
- Proposed contracts: transact-call's successful report/read-error union gains a **runtime pending result** containing completion and write identity; with-publication! retains its argument arity but returns this result too. Q5 must choose the one live owner/roster and its resource-transfer contract (or blocking await instead), including error delivery and disarm. No new atom justified here.
- New `seon.db-test/a-write-timeout-keeps-its-pending-completion` and `seon.blob-publication-test/an-unknown-root-commit-keeps-publication-owned`: use a latch-held injected writer completion, unknown reply then eventual success/refusal; assert one attempt, permit live until completion then released, one durable report. Confirmed existing target files: `test/seon/db_test.clj` and `test/seon/blob_publication_test.clj`; new names are future tests. Existing writer proof `seon.db-test/transaction-success-and-refusal-shapes-are-declared` and blob proof `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` reach the changed unions/permit. Done when no premature permit release and no retry. Budget F/O. Cannot launch as a two-line output-schema widening.

### S5-02 — live health replaces profile measurement persistence (blocked Q6; group E; independent)

- Own `src/seon/health.clj:460` definition-comparisons, `:508` regressions; profile `:395` observation; measurement owner `src/seon/profile/measurement.clj:142` significant-tx and its schemas; call sites turn `:3505`, turn.batch `:709`; `test/seon/health_storage_test.clj`, `test/seon/health_test.clj`, `test/seon/profile/measurement_test.clj`. Resource owners: `resources/seon/schemas/seon.health.edn:1`, `seon.profile.edn:1`, `seon.profile.measurement.edn:1` under that directory.
- Owner decided no profile measurement upserts. Replace health's stored definition measurement read with existing live profile observations since boot; remove significant-tx calls and measurement-error plumbing from both settlements. Preserve boot comparison records. Do not merely turn writes off while health silently reads stale measurements.
- Contract regressions stays `[db] -> seon.health/regressions`; definition-comparisons must take a **live supplied snapshot**, not implicitly query an atom. Q6 decides what current positive evidence remains when two digests were not observed since boot. Delete significant-tx/stored-measurements/measurement-id and tests of stored bucket movement with the final reader; keep profiling cells.
- New `seon.health-storage-test/health-uses-live-profile-without-writing-measurements`: current live observation shown after calls, no measurement datoms; unknown comparative evidence explicit. Existing `seon.health-test/a-slower-boot-phase-is-a-regression-until-it-recovers`. Done when every settlement has zero measurement datoms and health is current. Budget O/T; report removal of the 51–78% datom family, do not promise that percentage on a new workload.

### S5-03a–j — ten separate admission design proofs (group F; after S1-03)

Each row below is **one design lane**, not ten probes in one lane. It owns only its new notes path under `2026-10-08/s5-admission/<case>-design.md`; no production or test changes, therefore no new/changed function contracts or deletion. Read-only owners are `src/seon/batch.clj:513` apply-pending and `:543` derive-step, `src/seon/turn/batch.clj:226` analyze-form, `:241` write-entity, `:303` derive-form, `:515` apply-facts, and `src/seon/fn.clj:944` runtime-analysis-batch. Those source spans exist at pinned HEAD. Never prototype in default.

For every case: run finite input through the one entrance on the lane's disposable SCI branch; record input forms, original spans, immutable write-time observations, returned values, proposed/pulled facts, contract admission and owner ms/MB (O definition row). The lane must establish the smallest dependency seam or report the falsifier. Done when root can approve that case's exact observation/admission contract and its under-150-line implementation packet. No claimed pass for a future API. Existing positive proof `seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity` is the common comparison case, not a substitute for these new data assertions.

The following **new** regression names all live in existing `test/seon/cluster/turn_test.clj` when their implementation lands; this design lane specifies their asserted DATA and authors no test-only future target. S5-04 waits for all ten approved cases. These design notes can run in parallel because their files and disposable worlds differ; later regression edits serialize on cluster/turn_test.clj.

| Item / notes case | Exact new proof | Asserted DATA, not printed text |
|---|---|---|
| S5-03a / redef | `seon.cluster.turn-test/a-pass-records-the-definition-each-form-observed` | def, early use, redef, late use: early/late values differ as bodies specify; final entity latest digest; each evaluation's call digest and diagnostics match the definition it observed. |
| S5-03b / macro | `seon.cluster.turn-test/a-pass-admits-a-macro-before-its-use` | Macro defined then expanded once: expansion write span joins its call span, body value correct, final macro digest/edge correct. |
| S5-03c / removal | `seon.cluster.turn-test/a-pass-records-unmap-and-namespace-removal-in-order` | Define/use/unmap and create/remove-ns: later resolution absent, retained earlier evidence correct; final DB declaration/ref sweeps obey writer refusal rules. |
| S5-03d / alias | `seon.cluster.turn-test/a-pass-analyzes-aliases-at-their-observed-position` | ns/alias then two alias-qualified uses with changed alias: exact target symbols/keywords and recorded namespaces match each position; no final-alias substitution. |
| S5-03e / nested | `seon.cluster.turn-test/a-pass-joins-nested-and-cross-namespace-definitions-once` | Nested def with/without captured local and cross-ns def: admitted/private classification, exact outer/original spans, namespace and dependency edges correct; no second analysis of the same declaration. |
| S5-03f / private | `seon.cluster.turn-test/a-pass-preserves-private-definition-custody` | Private function can be used in its world, its admitted private flag retained; a non-function private def remains live context state and creates no program entity. |
| S5-03g / schema | `seon.cluster.turn-test/a-pass-admits-a-schema-before-its-use` | New named schema then contracted definition/call and write/read: input gate uses newly admitted schema; pulled value and schema match; malformed schema refuses at its producer. |
| S5-03h / failed-def | `seon.cluster.turn-test/a-failed-definition-does-not-certify-a-later-dependent-form` | Failure before completed bind: no new digest/green proof; later dependent use returns its actual value/error beside local diagnostics, never stale admitted facts invented for failure. |
| S5-03i / lazy | `seon.cluster.turn-test/a-pass-captures-definitions-realized-after-the-form-body` | Finite lazy realization that defines a function: write-time metadata/source span matches actual definition; realized result recorded once; later redefinition does not rewrite earlier observation. Identify whether realization is in eval or printing; do not create writes in a read-only render. |
| S5-03j / prose | `seon.cluster.turn-test/a-pass-keeps-original-definition-spans-after-prose` | Leading/interleaved/trailing prose and repaired input: admitted declaration's original coordinates and form ordinal match reader output; final source equals original authored definition, not concatenated analysis offsets. |

### S5-04 — analysis once and captured facts (blocked Q7; group B; after S5-03a–j)

- Own turn.batch analyze-form/derive-form `:226,303`, batch derive-step/apply-pending `:513,543`, fn runtime-analysis-batch `:944`; their schema and `test/seon/fn_test.clj`, `test/seon/cluster/turn_test.clj`.
- Replace per-form analysis with one pass input preserving code slices' original positions and observed namespace/aliases. Join exact write-time observations, not final Var roots. Preserve same-pass admission with the seam proven by S5-03a–j. Delete per-form kondo only when the proven replacement is reachable.
- Inputs/results are the existing batch request/entry/write/change shapes extended by captured immutable definition metadata; **exact observation shape and admission contract await Q7**. No new evaluator; all functions retain success/error behavior and their existing owners.
- Existing `seon.fn-test/defining-forms-share-one-form-local-kondo-batch` changes to pass-batch work count; `seon.fn-test/a-fold-derived-definition-has-static-index-edge-parity`; cluster redefinition/alias/schema/unmap proofs above. New `seon.fn-test/a-pass-records-the-definition-each-form-observed` includes the accepted S5-03a–j table. Done when one analysis call only if definitions observed, zero otherwise, no lost/reforged facts. Budget O, definitions at positions 1/10/200. This is still too large until Q7 prices per-family implementation subitems with exact diff/caller bounds; do not launch it as a fifteen-minute integration.

### S5-05 — observed faults instead of window/routing (blocked Q8; group B; after S5-01)

- Own `src/seon/turn.clj:3441` fault-window/with-fault-windows, `:3505` settle-batch!, `src/seon/turn/world.clj:136` record-fault, `src/seon/batch.clj` record-faults and failure accumulation, error/fault schemas and `test/seon/cluster/turn_test.clj`.
- Pass observed occurrence identities into branch settlement and deliver its own failures; keep authoritative counted signature/task-home write. Delete window query and problem-assignment routing when final caller leaves. Do not take errors from a later branch query (it mixes unrelated occurrences).
- Contract fault accumulation is ordered vector of observed error occurrence identities alongside held batch, settle input receives it; record-fault retains its current declared receipt until coordinated errors cut changes it. Q8 decides custody/commit ordering with that cut and exact occurrence shape; schema cannot be invented by loop lane.
- Existing `seon.cluster.turn-test/a-red-form-stays-with-its-author-and-the-fold-continues` (`:1510`). New `seon.cluster.turn-test/a-pass-delivers-only-the-errors-it-observed`: own error delivered, unrelated concurrent signature absent, repeated signature increments home count with one notification, branch retirement preserves home evidence. Done when no window query and exactly counted home writes. Budget F/O, new signature extra home commit separately.

### S5-06 — JVM entrant without SCI acquisition (ready semantics; blocked Q9 proof fixture; group E; after S3-01)

- Own `src/seon/mcp.clj:265` jvm-run, `src/seon/turn/batch.clj:923` jvm-request/jvm-show (at `:891`), world jvm-world `:77`, MCP tests. Stop calling `agent/acquire-context!` for JVM mode; use an already held agent context only, else stable handle answers gone-value/class.
- Contracts mcp jvm-run keeps `(custody, code, maybe ns, cluster) -> [vector shown-text, boolean]`; world and request success types unchanged, context remains optional; gone-value must use installed result-owner error/value union, **Q9 names the current roster read and test-support seam**, no second roster. JVM never changes SCI current namespace/answeredness; host calls separately owned until exit.
- Existing `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle` converts cold row to gone-value, retained row keeps live dig; `seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run`. New `seon.mcp-entrance-test/a-jvm-call-does-not-acquire-an-sci-world`: cold invocation records outside origin, handle class present, zero acquisition calls; two overlapping host invocations remain individually owned, writer ordinals unique. Done when no hidden serialization/acquisition. Budget O/F.

### S5-07 — one recording owner and held reply (blocked Q5/Q7/Q8; group B; after S5-01/04/05/06)

- Own turn.batch `:658` record-facts, `:709` settle, turn `:3505` settle-batch!, `:3796` settle-batch-refusal!, `:5076` with-saved; mcp observed `:215`; evaluation/program writer schema and tests.
- Reuse one existing settlement owner, derive one transaction on current writer head with digest checks, evaluations/call/agent/faults/blob roots. Adapt entrants one at a time **while existing record contracts remain accepted**; remove old owner only with its last caller. Finish returns held values plus diagnostics even after record/install failure.
- Exact combined settlement request/commit union must be approved in Q5/Q7/Q8; current `seon.batch/commit` lacks terminal pending ownership. No third settle function or new runner. Convert existing helpers' arities only with all callers.
- Existing `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, `seon.turn-test/a-refused-refusal-settlement-is-answered-as-a-value`, `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`; `seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved`. Convert its current refused-row assertion (currently only unsaved refusal, `mcp_entrance_test.clj:149–153`) to value+diagnostic under the approved total-path law. New `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure`: SCI/JVM/model/test rows, attempted/durable counts and diagnostic identities. Done when same writer union/finish value reaches all entrants. Budget O/F/T.
- S5-07 is an integration boundary, **never a lane assignment**. Its common request contract must exist before the following serial subitems. All are blocked by Q5/Q7/Q8; their exact per-entrant request member closure is not approved. Do not solve this by inventing six parallel settlement owners. Each item adds its specified row to the one new `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure` table, or the test-member proof named below, and runs only relevant existing proof Vars above. Shared test file means serial ownership even when source owners differ.

| Item | Exact family / files at HEAD | Deletion, contract and proof DATA | Dependencies / budget |
|---|---|---|---|
| S5-07a | turn.batch record-facts:658/settle:709; turn settle-batch!:3505, settlement request schema | Choose which existing owner survives, then derive its one final transaction. Delete no reachable old entrance yet. Change only its request/commit union approved by Q5/Q7/Q8. Existing stale-definition/refused-settlement proofs assert refusal keys and values; new table writer row asserts one durable commit and digest-checked head. | S5-01/04/05/06 + Q5/Q7/Q8; O/F |
| S5-07b | mcp jvm-run:265; turn.batch jvm-request:923 | Route JVM to the approved existing writer; retire only old JVM record adapter. Entrant return `[shown-text-vector boolean]` remains, held value/diagnostic handles survive. New JVM failure row asserts saved value and one occurrence identity; existing JVM handle and timing proofs. | 07a; O/F |
| S5-07c | mcp sci-run:236/observed:215; turn with-saved:5076 | Route SCI reply from held settlement, no DB rebuild. Delete its last old finish path only. Existing SCI saved-reply proof changes refusal expectation to value+diagnostic; exact reply union inherited from 07a. | 07b; O/F |
| S5-07d | turn call-turn:4390/evaluate-sources:4878/settle-batch!:3505; cluster agent turn-step:511 | Model uses same writer/held reply. Delete model-specific old recording only after its call is converted; call count and model origin retained. New model failure row asserts values, observed diagnostics and one machinery commit; existing red-form-continues proof. | 07c; O/F |
| S5-07e | test member-result:1987; turn.batch test-request:950; world test-agent-world:70 | Convert one member settlement adapter, **not yet** attempt-turn deletion (S6-02). Approved member/requester custody is required first (Q11). Contract captured-result/timing and completion inputs unchanged except common writer result. New `seon.test-test/a-member-keeps-its-value-through-recording-failure`: body value/diagnostic preserved, member vs aggregate commits counted separately. | 07d + S6-02a + Q11; T/F |
| S5-07f | turn settle-batch-refusal!:3796/with-saved:5076 and surviving/retired owner chosen in07a | Delete final old owner and convert every residual call in the same commit. Do not add a forwarding wrapper. Contracts retire with their sole producers; surviving request is 07a's. Run all four failure rows and stale-definition proof; no old-owner calls after complete reference search. | 07e; O/F/T |

If any adapter includes >150 lines because its finish value or custody is not inherited from the approved writer contract, stop and return the missing decision; adapters should be boring translations, not fresh design.

## S6 — cut lifecycle by family, with an actual-exit owner retained

### S6-01 — effect provenance without uncommitted eval ref (blocked Q10; group E; before S6-04)

- Own `src/seon/effect.clj:210` evaluation-eid, `:227` open-call, request context/dispatch `:410,735`; `resources/seon/schemas/seon.effect.edn:1`; `test/seon/effect_test.clj`. Pass id+position as values, never ref to an unrecorded evaluation. Remove evaluation-eid lookup and run-ref requirement only after request producers supply values.
- Contracts effect open-request/request-context/receipt require pass identity and nonnegative position; `open-call [db open-request] -> transaction-data` unchanged, pure writer derives effect identity, not execution. Exact key choice/migration from run/form-ordinal is Q10; keep old fields accepted only during a producer conversion, then retire with last caller.
- Existing `seon.effect-test/request-commits-before-io-dispatch-and-settles-once`, `seon.effect-test/guarded-sci-evaluation-supplies-the-effect-identity-context`, `seon.effect-test/effect-refs-its-evaluation-and-handler` converts to observed identity values; `seon.effect-test/background-settlement-carries-its-connection-across-a-thread-hop`. Done when an effect succeeds before RECORD with no evaluation lookup and remains idempotent by its own identity. Budget F/O.

### S6-02a — bounded test body through pass (blocked Q11; group E; after S5-07d/S6-01)

- Own `src/seon/test.clj:179` attempt-writers, `:184` attempt-request-context, `:1987` member-result, `:216` bounded-result; turn.batch test-request `:950`, world test-agent-world `:70`; `resources/seon/schemas/seon.test.edn:1`, `seon.test.member.edn:1`, `seon.test.run.edn:1`, `seon.test.runner.edn:1`, `seon.batch.edn:1` under that directory; `test/seon/test_test.clj` and `test/seon/effect_test.clj`.
- Resolve/seed once as today, supply resolved body stage to the same fold, origin test, member settlement and request aggregate distinct; retain bound/reach/admission/actual-exit cleanup. Keep effect-attempt preparation until S6-02b. Delete no branch before body exits. This ordering avoids the circular dependency between final old-settlement retirement and the nonexistent test entrance.
- member-result keeps declared captured-result/timings result; test-request keeps request -> batch request but its body/world contract must name supplied member identity, requester recording connection and completion. Q11 settles whether pass rows live on member/requester and exactly which request aggregate the caller receives; don't hand-wave the existing separate settlement connection.
- Existing `seon.test-test/interpreted-test-bodies-use-the-sci-interrupt-bound`, `seon.test-test/recording-preserves-admission-and-refuses-a-deleted-definition`, `seon.test-test/overlapping-admissions-reserve-complementary-memberships`; effect proof above. New `seon.test-test/a-member-records-one-pass-before-its-request-aggregate`: origin test, one body evaluation/commit, aggregate on requester, timeout resources retained to actual exit. This item does not assert absence of the still-needed attempt turn. Done when the resolved body stage uses the same fold, result contract is unchanged, and tests observe their injected world. Budget T/F.

### S6-02b — delete effect attempt preparation (blocked Q10/Q11; group E; after S6-02a/S5-07e/S6-01)

- Own only `src/seon/test.clj:179` attempt-writers and `:184` attempt-request-context, its caller in `bounded-result:216`, `resources/seon/schemas/seon.test.edn:1`, `seon.test.member.edn:1`, `seon.effect.edn:1` under that directory and `test/seon/test_test.clj`, `test/seon/effect_test.clj`. The body adapter from02a is already installed.
- Delete attempt agent creation/open-turn wrappers after effect pass+position provenance is supplied. Remove the effect-attempt flag from the member/runner contract and every caller in this commit; exact caller closure is Q10's inventory, not permission to leave a dummy flag. Retained member result/timing and actual-exit callback contracts remain02a's.
- Existing `seon.effect-test/guarded-sci-evaluation-supplies-the-effect-identity-context`, `seon.effect-test/background-settlement-carries-its-connection-across-a-thread-hop`, and `seon.test-test/interpreted-test-bodies-use-the-sci-interrupt-bound`. New `seon.test-test/a-test-effect-needs-no-attempt-agent-or-turn`: actual durable effect commits with member pass+position values, no attempt-agent/open-run facts, cleanup after actual exit only. Done when no source/schema caller needs attempt identity. Budget T/F.

### S6-03 — one live cursor and prose exchange (blocked Q2; group B; after S4-03/S5-07f)

- Own `src/seon/turn.clj:597` source-rows, `:635` plan-call, `:770` submission-open-tx, `:3007` planned-sources, `:3024` code-sources, `:4689` read-row, `:4878` evaluate-sources; `src/seon/cluster/agent.clj:644` segmenter/submission; `src/seon/sci/forgive.clj:389` segments; batch/read/source schemas and reader/entrant tests.
- Feed existing `seon.sci.forgive/next-form` live stepper into the one fold from whole text; original cursor spans plus comment/trailing prose retained. Delete segments/structural-stage **only where no JVM failure-span caller needs it**; `failure-step:364` still uses structural scanning. `forms` is already absent at HEAD; do not schedule it. No new reader/parser or repair logic.
- `evaluate-sources` changes request from rows to text+read stage while keeping evaluated-batch result; every caller changes in commit. Cursor request/read-step contracts are already declared at HEAD `forgive.clj:313–349`; prose-only exchange result is Q2's additional output, no fake evaluation. Remove segmenter request key after final caller.
- Existing `seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules` (landed S2b-1), `seon.cluster.turn-test/reply-reading-follows-evaluated-alias-and-dynamic-require-state`, `seon.cluster.turn-test/a-prose-only-provider-reply-settles-without-parking-the-turn-proc`. New `seon.cluster.turn-test/one-cursor-reads-each-form-in-its-current-world`: custom tag counted once, alias defined then read, errors continue, trailing prose stored, prose-only no evaluation. Done when model/MCP SCI do one semantic read and span pass absent on success; JVM retains host read stage. Budget O, 28.7 KB mixed reader corpus and 100 forms.

### S6-04 — delete intent/recovery runs after provenance conversion (blocked Q10; group B; after S6-01/02b/03)

- Own turn `:697` open-tx/plan-call, `:1473` recover-tx/recover-call, `:4313` open-turn, `:4390` call-turn freeze, `:5092` resume-turn/close/generate families, `:5485` turn; cluster boot recovery caller, turn/run schema/tests.
- Remove pre-eval intent commit and interrupted run recovery; forms that already committed effects remain durable, interrupted pass bookkeeping is lost. Pass id minted before eval via existing `seon.id/id`; keep evaluation id `id/evaluation(pass-id, position)`. Model call attempt accounting must already ride final settlement, including failure without evaluations.
- Retained call/proc/report contracts use approved pass request, no open/run situation. Q10 must inventory all `*open*`/run duty callers and schema retirement before deletion; stop pretending open-tx alone is the whole family. Existing `seon.cluster.turn-test/turn-intent-is-the-complete-crash-falsifier` retires with intent; new `seon.cluster.turn-test/a-crash-before-record-keeps-only-durable-effects` asserts effect survives, no partial pass rows, next wake derives work (does not resume). Keep effect recovery test for independently durable effects. Done when successful model pass has one machinery commit. Budget O/F; root reset manifest required.

### S6-05 — writer answeredness/cap and continuation (blocked Q12; group B; after S6-04)

- Own turn `:2584` outside-wake-t, `:2622` spent-runs/episode-runs, `:2782` continuing-reply?, `:2805` next-agent-work, `:2881` latest-answering-turn-t, `:2908` unanswered-wakes; agent/call schemas and `test/seon/turn_work_test.clj`.
- Replace episode derivation with model-call count at writer against consumed outside anchor; committed model pass alone advances `:seon.agent/answered-t` to **presented** basis. >=1 evaluated form continues unless bail/cap; prose/only-read-errors waits; provider/context failure counts but does not answer; new outside wake resumes. Schedule firing arms only, no provider means no pass.
- Retained `next-agent-work [db agent-request] -> work | nil | error` loses run situations only after S6-04; exact new bounded work union and failed-call row shape are Q12. No stored duplicate counter where writer can derive from call rows. Delete turns-left/episode helpers only with last source/help/test caller.
- Existing `seon.turn-work-test/a-wake-arriving-mid-turn-opens-the-next-turn`, `seon.turn-work-test/two-wakes-in-one-transaction-are-one-turn`, `seon.turn-work-test/only-a-turn-whose-reply-came-from-a-model-attempt-answers` convert to presented basis; new `seon.turn-work-test/model-passes-answer-only-the-basis-they-presented`: table all §4.5 cases, assert answered-t/call counts/disposition and one assigner notification at cap. Done when self wake/retry never refills and input/opening/outside/test never answer. Budget O/I/F.

### S6-06a — proof eligibility at assignment writer (blocked Q13; group E; after S6-05)

- Own `src/seon/task.clj:467` assign! and `:421` task-call, `resources/seon/schemas/seon.task.edn:1` assignment/refusal members and existing `test/seon/task_ingestion_test.clj`. Retain the installed assignment request/result arity and add exactly no-proof/all-current-green refusal variants; writer validates the proof basis there, not a caller pre-read.
- Refuse no proofs and all current positive green; unknown/red can be assigned. No lifecycle API change or success completion in this item. Exact task-home/branch program proof basis is Q13; reuse verified? instead of another green predicate. Delete no unrelated task helpers.
- New `seon.task-ingestion-test/assignment-requires-a-non-green-proof`: no proofs/all current green refuse and assignment datoms unchanged; unknown/red permits; stale task revision refuses under existing policy. Done when assignment writer alone decides eligible proofs and launches have a valid assignment. Budget F/O.

### S6-06b — reasoned bail gives task back (blocked Q13; group E; after S6-06a)

- Own `src/my/task.clj:163` done!, `src/my/turn.clj:5` complete, `src/seon/run.clj:107` complete, task assignment/resolution writer and turn disposition settlement; `resources/seon/schemas/seon.task.edn:1`, `my.turn.edn:1` under that directory, `test/seon/task_ingestion_test.clj` and `test/my/turn_test.clj`; **new** `resources/seon/schemas/my.task.edn` only if Q13 chooses a new admitted my.task request/result schema. It does not exist at pinned HEAD and is not an installed claim. Q13 must approve compatibility/caller closure before these owners are claimed.
- New `my.task/complete! [request] -> bail-value | exact task refusal/error union`, request declares supplied agent/task custody and required nonblank string reason. Writer returns broken/impossible task to assigner with one durable notification. No success resolution. Delete legacy completion only if Q13 selects replacement and lists its example/test callers; keep no wrapper whose meaning silently switches from success to bail.
- New `seon.task-ingestion-test/completion-with-a-reason-gives-the-task-back`: assignment returned, no success resolution, one assigner message, absent/blank reason refuses at producer. Existing `my.turn-test/a-blank-completion-is-an-error-value-not-a-throw` changes to the surviving approved API's refusal DATA; `my.turn-test/a-disposition-is-an-ordinary-value` covers the proc consumer. Done when bail and proof completion are distinct data, caller closure loadable. Budget F/O.

### S6-07 — retire backstop/parking without losing actual exit (blocked Q14; group B; after S6-04/05)

- Own turn `:5635` await-turn-permit!, `:5685` write-refusal bound family, `:5766` arm-turn-completion-backstop!, `:5824` step; cluster agent `:1368` interrupt-turn!, `:1402` turn-completion-failure!, `:1422` cancel-turn-backstop!, `:1465` disarm!, `:1508` stop-graph!; config/run schema and concurrency tests.
- Delete broad turn allowance/parking/episode permit only after provider/form/caller lifecycle bounds have a named actual-exit owner. Keep turn-thread holder/interrupt handling (`agent.clj:511`) and independent JVM invocation ownership. Storage failure stops graph once, no fault/retry loop.
- step four arities/report shape retained; disarm/stop wait result must explicitly name missing exit and retain resources on timeout. Q14 supplies the replacement lifecycle deadline once old backstop dial disappears; no arbitrary new timer/pool. No proof by channel close or cancellation.
- Existing `seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole` converts to remaining bound owner; new `seon.cluster.turn-test/a-timed-out-pass-keeps-ownership-until-it-exits`: blocked host call, deadline, unrelated agent progresses, same SCI agent cannot overlap, actual exit releases once. Exact existing disarm proofs: `seon.cluster.agent-test/disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` (`agent_test.clj:845`) and `seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop` (`:925`); convert the latter to the retained actual-exit bound, not timer retirement. `seon.cluster.agent-test/disarm-drops-the-route-before-closing-it` (`:793`) currently uses with-redefs and must be converted to data/channel injection with this owner change, not copied into the new proof. Q14 remains the replacement deadline question. Done when resources only retire after observed exit, graph stop on storage failure. Budget F/O.

## S7 — proof delivery and completion

### S7-01 — SCI run-test-var uses the one test entrance (blocked Q15; group C; independent of S4/S5)

- Own `src/seon/sci/eval.clj:281–324` agent-test namespace adaptations, `:4188` run-tests/`:4204` run-test; SCI test schema/`test/seon/sci/documentation_test.clj`, `resources/seon/tasks/seon.sci.eval.edn:216`.
- Install a SCI-side run-test-var adapter in the **existing** agent-test-namespace map. Resolve the SCI Var's symbol and call the canonical named check; no host clojure.test side runner that can certify a different program. Unknown/no-test returns declared not-runnable data.
- Q15: standard run-test-var consumes a Var and expects nil/printed reporting, but Seon test Vars can be absent in acquired SCI. Choose symbol/Var compatibility and returned captured-result/report shape before naming the adapter's precise contract. Prefer symbol resolution into existing my.test/check over manufacturing test Vars. Existing run-tests/run-test are bounded capture helpers, not proof-request substitutes.
- New `seon.sci.documentation-test/run-test-var-records-the-branch-test`: own branch definition then test, run via agent, stored proof result digest is current branch definition; nonexistent/non-test target returns named error; no host root mutation. Existing `seon.sci.documentation-test/clojure-test-at-the-agent-repl-reports-into-the-evaluations-output` must be deliberately converted, not deleted accidentally. Done when task `9236eba3f99e` resolves through current evidence. Budget T.

### S7-02 — proof result input (blocked Q3/Q11; group B; after S4-05/S6-02b)

- Own `src/seon/test/runner.clj:1546` complete-members, `:1971` commit-results!, task-home completion/input consumer in turn/context; result/input schemas; `test/seon/test_test.clj`.
- Deliver exact completed proof identities from task home as input passes, origin input, and mark only read identities delivered. No branch t compared with home t; incomplete/red/unknown never treated as green. Result producer remains runner, no parallel completion watcher.
- complete-members/commit-results! keep native evidence schemas; extend only their declared delivery output with Q3's input identity shape. New `seon.test-test/proof-input-delivers-only-completed-current-evidence`: latest complete red/green/unknown rows delivered, pending not promoted, home result remains after member branch retirement, new result arriving mid-input remains due. Done when restart delivery once and agent sees the proof it can act on. Budget I/T.

### S7-03 — complete only after fresh proofs of changed reach (blocked Q13/Q16; group B; after S7-02/S6-06b)

- Own `src/seon/task.clj:698` task-resolution, `src/seon/test.clj:72` changed-since-green/`:1562` verified?/`:2225` run; proof completion writer and `test/seon/test_test.clj`, task ingestion tests. Select proofs of the just-changed code through existing current reach owner; after completed current green evidence, resolve at task home and notify assigner once.
- `verified?` keeps both exact existing arities/error union; no ad hoc green predicate. Resolution request must include assignment/task revision plus proof definition/input/reach evidence checked against current program; Q16 chooses the cross-branch snapshot and idempotent recovery boundary. No pre-existing green completion.
- New `seon.task-ingestion-test/a-task-completes-only-from-fresh-proofs-after-its-change`: old green, incomplete latest, red/unknown/current changed-reach rows cannot complete; fresh positive all-green completes; crash between proof commit/home completion recovers once, competing task revision refuses. Existing `seon.test-test/recording-preserves-admission-and-refuses-a-deleted-definition`. Done when one durable completion/message and evidence current to combined program. Budget T/F.

## Root's open questions and simpler alternatives

These are questions for reconciliation, not permission requests to the owner during this lane.

| Q | Decision required; simplest alternative proposed |
|---|---|
| Q1 | Which exact analysis-value field carries unaugmented runtime namespace source? Keep source by span in the existing analysis value; preserve explicit authored refers. Reject subtract-equal-ref heuristics and another reader. |
| Q2 | What is the durable **exchange without evaluation**, its assignment boundary and completion fact owner? Decide this before transcript code. Prefer extending the existing pass/call record for prose-only exchange and linking ordinary evaluation rows to that assignment; do not create a second transcript entity/store or fake no-form evaluation. Specify how task revision change acquired from home interacts with the assigned revision, currently refused by task/assignment. |
| Q3 | Name the input form and identity/result/delivery schemas, per-home basis validation and zero-input positive fact. Reuse one recorded pass and exact identity set; do not infer delivery from latest t. |
| Q4 | Which web/debug callers of prompt/walk remain and their exact replacement request? Keep existing HTML rendering over saved exchanges; delete only AI context traversal after its last caller. The old walk-delete notes invent a missing `seon.agent.loop/context`. |
| Q5 | Where does pending writer completion and blob permit live until exit? Choose one existing execution roster/state owner, exact transfer/result contract, definitive refusal vs unknown and cleanup. If retaining both safely costs a new subsystem, price a blocking owner wait as the simpler option. |
| Q6 | What does live health report when parent/current digests have no since-boot positive observation? Report typed unknown rather than stale stored comparison; keep boot comparison separately. Specify snapshot input; do not preserve measurements just to feed old health. |
| Q7 | Which admission seam permits one end-of-pass analysis while preserving schema→use and write→read? Existing apply-pending gives a validated database to later forms; SCI bindings alone do not supply that guarantee. One bounded early admission at a real dependency boundary is simpler than a second speculative database/registry, if root accepts its measured cost. All ten design probes must precede integration. |
| Q8 | Which error-cut version lands with observed occurrence settlement and task-home count/notification? Keep installed constructor/refusal unions until coordinated cut. Do not duplicate error signatures/tasks/routing in loop code. |
| Q9 | What installed context-roster read supplies an **already acquired** SCI context without acquisition, and what fixture proves it? Reuse routing roster; no lookup cache or implicit cold acquisition. |
| Q10 | Exact pass provenance keys and complete `*open*`/effect/attempt/boot caller closure? Keep durable effect receipts and convert observed pass+position values before deleting intent/evaluation references. Receipt recording entity is not exclusively a refresh artifact. |
| Q11 | Where are test member pass rows recorded, where is request aggregate recorded, and when can member branch retire? Existing test-agent-world has a requester settlement connection but no production caller; source presence is not integration. Reuse runner's actual-exit owner. |
| Q12 | Exact model-call row, writer counting anchor and work union for failed/no-evaluation calls? Use current call facts plus presented basis, no episode counter; refuse absent provider before admitting model work. |
| Q13 | Compatibility for my.turn/complete and my.task/done!, proof eligibility at task home versus branch program, and refactor tasks whose existing proofs are all green? Require one newly failing behavior proof rather than weakening no-green assignment law. |
| Q14 | Which declared deadline bounds disarm/join after deleting backstop? Preserve thread/exit owner; deleting broad timer does not permit unbounded wait. The exact existing disarm proofs are inventoried in S6-07; choose the retained deadline before converting them. |
| Q15 | Does run-test-var take an absent acquired SCI test Var, a symbol, or both; what data does it return? Prefer canonical named test request by symbol. No test Var manufacturing/second runner. |
| Q17 | S1 v3.1 says documentation-value keeps three arguments, but SCI metadata/context must be explicit or supplied by registered world schema. Which of those two is the approved boundary? Prefer adding a supplied ctx argument and converting every macro caller; avoid an ambient context lookup disguised as unchanged arity. S1-04 is blocked until this is exact. |
| Q16 | Which current program identity/evidence does home completion validate after branch proof commit, including crash window? One idempotent task writer checks assignment/revision/proof identities, never a latest-green bool. |

Other inaccuracies/size traps found:

- S3 spec §5/§4.4 origin wording is stale relative to approved S3 v2. Only model/outside are S3; opening/input/test and ordinals come with their writers later. Root revises spec in place, not this lane.
- S1 v3.1 still says S2b-1 unlanded and teaches obsolete same-line behavior in §4.5. At pinned HEAD every form on a line that starts code runs (owner 2026-10-08). Help must teach this actual rule. Its install-option ordering, stored-source seam and recorded-test verified? cost were explicitly unproved; do not call design citations installation evidence.
- S4–S7 have no reviewed design notes. Their whole-row assignments necessarily make the implementer design missing data/custody/bounds and then test it. The blocked rows above expose those choices; root must not launch them merely because this packet contains an id.
- A naive dependency split was cyclic: test adapter → final old-settlement retirement → test-body introduction. Correct order here is model writer adapter → bounded body stage (S6-02a) → member adapter → retirement, with effect-attempt deletion separate (S6-02b). Q11 must prove that staged contract before launch.
- S5 admission has ten distinct cases, several requiring incompatible fixture/provenance boundaries. One lane is one case, then root assembles the design; no thirty-five-minute “try every case then implement” assignment.
- The current total-path regression conflict is concrete: MCP test asserts no unsaved values after refusal; approved law returns evaluated value beside later failure. It needs deliberate conversion with the recording cut.
- Skill current claims are stale: REPL skill describes fork-for-turn regeneration/system refresh and says uncontracted defn is refused; Flow skill names four-entry LRU/old three-write turn; data-modeling says “do not add author-kind stamp.” S3 already owns the stamp paragraph correction; root should schedule the remaining current-claim correction with the corresponding cuts. This lane edits none of those foreign files.
- Delete only **persistent refresh** evidence. Runtime observation, proof reach, contract-call sink and schema-driven cost members have surviving readers. Generic key-name grep cannot establish a deletion closure.
- Not every pair that has different function names can parallelize: nearly all loop work shares turn.clj; every lookup shares eval.clj; all member work shares test.clj. Separate serial work items, not simultaneous owners.

## Dependency and parallel ledger

Groups denote file ownership, not fixed JVM slots. A can overlap B/C only with the specific exceptions shown; E subfamilies can overlap only after final claimed sets are compared. A row marked blocked is **never ready work**. Source line edits are estimates, not an implementation permission to expand scope.

| Item | One item | Dependencies | Parallel group/status |
|---|---|---|---|
| S1-01 | Delete unwired agent.loop | none | A landed f50ef6059; do not relaunch |
| S1-02 | One-pull namespace directory render | none | A landed 438328b34; do not relaunch |
| S1-03 | Store authored namespace source/refers | Q1 | B blocked; fn file collision S1-09/S5-04 |
| S1-04 | Doc SCI metadata/reference classification | S1-03, Q17 | C blocked |
| S1-05 | Schema/namespace doc | S1-04 | C serial |
| S1-06 | Task/test doc custody/evidence | S1-05 | C serial |
| S1-07a | SCI directory data | S1-01/02/06 | C serial; repl ownership too |
| S1-07b | SCI search data | S1-07a | C serial |
| S1-08 | Code data source | S1-04 | C serial |
| S1-09 | One lookup installation/bare targets | S1-03/07b/08 | C serial; bootstrap/fn ownership |
| S1-10 | Literal help/correct directory forms | S1-09 | A; turn/run test collisions B |
| S1-11 | Retire my.program/source family | S1-08/10 | A |
| S3-01 | Atomic author→origin conversion | S2b-1 landed | B ready; turn/batch/test closure |
| S4-01 | Writer ordinals + narrow first read | S3-01, Q2 | B blocked |
| S4-02 | Opening once at assignment revision | S1-10/S3-01, Q2 | B blocked |
| S4-03 | Durable exchange/history projection | S4-01/02, Q2 | B blocked; transcript/repl |
| S4-04 | Proc transcript + whole-window prompt | S4-03, Q4 | B blocked |
| S4-05 | Message/error input pass | S4-04, Q3 | B blocked |
| S4-06 | Delete refresh/persisted freshness | S4-02/04/05 | B blocked |
| S4-07 | Delete old capture/walk closure | S4-04/06, Q4 | D blocked; turn collision B |
| S5-01 | Pending writer/blob ownership | Q5 | E blocked; db collision S4-01 |
| S5-02 | Live health, zero profile upserts | Q6 | E blocked; settlement collision B |
| S5-03a | Admission design: redef/use | S1-03 | F design; disjoint notes |
| S5-03b | Admission design: macro/use | S1-03 | F design; disjoint notes |
| S5-03c | Admission design: unmap/remove-ns | S1-03 | F design; disjoint notes |
| S5-03d | Admission design: ns/alias | S1-03 | F design; disjoint notes |
| S5-03e | Admission design: nested/cross-ns | S1-03 | F design; disjoint notes |
| S5-03f | Admission design: private def | S1-03 | F design; disjoint notes |
| S5-03g | Admission design: schema/use | S1-03 | F design; disjoint notes |
| S5-03h | Admission design: failed def/dependent use | S1-03 | F design; disjoint notes |
| S5-03i | Admission design: lazy realization | S1-03 | F design; disjoint notes |
| S5-03j | Admission design: prose/spans | S1-03 | F design; disjoint notes |
| S5-04 | One analysis/captured admission | S5-03a–j, Q7 | B blocked; further split after decision |
| S5-05 | Observed faults replace windows | S5-01, Q8 | B blocked |
| S5-06 | JVM avoids cold SCI acquisition | S3-01, Q9 | E blocked; mcp/turn.batch |
| S5-07a | Existing writer final derivation | S5-01/04/05/06, Q5/Q7/Q8 | B blocked |
| S5-07b | JVM settlement adapter | S5-07a | E blocked; common tests serialize |
| S5-07c | SCI held-reply adapter | S5-07b | E blocked; common tests serialize |
| S5-07d | Model settlement adapter | S5-07c | B blocked |
| S5-07e | Member settlement adapter | S5-07d/S6-02a, Q11 | E blocked; test owner |
| S5-07f | Delete final old settlement | S5-07e | B blocked |
| S6-01 | Effect pass provenance values | Q10 | E blocked; effect owner |
| S6-02a | Bounded test body uses one fold | S5-07d/S6-01, Q11 | E blocked; test owner |
| S6-02b | Delete effect attempt preparation | S6-02a/S5-07e/S6-01, Q10/Q11 | E blocked; test owner |
| S6-03 | One live cursor/prose exchange | S4-03/S5-07f, Q2 | B blocked |
| S6-04 | Delete intent/recovery runs | S6-01/02b/03, Q10 | B blocked/reset |
| S6-05 | Writer answeredness/cap/continue | S6-04, Q12 | B blocked |
| S6-06a | Assignment writer proof refusals | S6-05, Q13 | E blocked; task owner |
| S6-06b | Reasoned bail and caller conversion | S6-06a, Q13 | E blocked; task/run/turn |
| S6-07 | Delete backstop/parking, keep exit | S6-04/05, Q14 | B blocked/reset |
| S7-01 | SCI run-test-var canonical check | Q15 | C blocked; eval file |
| S7-02 | Proof results as input | S4-05/S6-02b, Q3/Q11 | B blocked; runner |
| S7-03 | Fresh-proof task completion | S7-02/S6-06b, Q16 | B blocked; task/test |

The original file-disjoint implementation set was S1-01, S1-02 and S3-01; root has now landed the first two. Remaining ready implementation is S3-01 (root already owns its in-flight work); Q1 seam resolution is independent design-only work. Do not overlap or resume those sessions. Later C lookups serialize; F case notes can parallelize on distinct documents/worlds. No delegation/session messages issued by this bounded lane.

## Landing and verification

Only `docs/research/agent-platform/2026-10-08/split/spec-split-sol-split.md` is owned. Both independent splitters initially selected the same generic lane/path `spec-split-codex`; that already-committed artifact is preserved. This independently reviewed version has a unique lane/path; no peer session was resumed or messaged, and the preserved artifact is not edited by this lane. Source/test delta: 0/0. This packet has **53 individually named candidate items**; blocked implementation packets remain intentionally unlaunchable until root supplies their named contract decisions. S5-04 is explicitly an unsized integration boundary requiring a further priced split after its ten probes, not a falsely ready fifteen-minute lane. No provider calls, branch prototypes, new production functions or store schema changes. Tests/schema retirement mentioned in candidate rows are future requirements, not results of this lane.

Full working-tree `clj-kondo --lint src script test --cache false --parallel`: **4,519 ms, 0 errors, 1,454 warnings**. This is lint evidence, not a test or zero-findings claim. The lane commit requests exact `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection` and `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`. Candidate `6fbba160798b3a199e7fef0a963a3342b60dbe16`, parent `438328b34`: **check passed**, stored run `74ef610e646c`, 2 executed / 0 reused / 5 assertions passed / 0 fail / 0 error, **2,028 ms**. Total **59,194 ms**, publication **1,965 ms**, adoption **975 ms**, throwaway prepare **3,707 ms**, boot **46,104 ms**, readiness **41,620 ms**. No allocation/memory measurement was emitted by this check: MB unknown. Both source/test roots are unchanged by this lane; candidate diff against its parent contains only this document. The 76 adopted identities reflect root's already-landed source program relative to the live a4d438dc6 archive, not permission for documentation to widen gates.

The slow boot/publication is not a performance pass. Link existing defect classes rather than duplicate them: `docs/seon/issues/from-zero-boot-takes-minutes.md`, `seon.cluster/boot-publication-phases-are-invisible` (`resources/seon/tasks/seon.cluster.edn:34`), and adoption cost task `seon.cluster/adoption-write-costs-sixteen-seconds` (`:30`). Those links do not attribute the measured 46 s to an unprofiled internal function. This packet changes no owning performance code. Its documentation commit uses the two exact proof Vars above through bin/lane; a subsequent evidence-only correction repeats that check against the final packet. Documentation must not expand program gate input roots. Foreign boundary during read was default replacement, and earlier shared S2b-1 working files were never loaded; all specification anchors pin committed `a4d438dc6`. Root alone reconciles the independent split and revises approved owning sections. Do not treat a proposed contract marked Q as approved executable work.

## Source anchor audit (a4d438dc6)

These are declaration starts re-derived from the pinned Git objects after S2b-1 landed. They override historical line numbers copied in the approved designs. Each existing name below was found, not inferred from a spec. The surrounding producer/caller bodies of the changed boundaries were read. Current S1-02 anchors at `438328b34`: `src/seon/render/ns.clj` directory-selector:219, public-functions:224, public-rows:233, read-arglists:242, directory:250, render-ai:264, render-html:338, namespace-form:78. Its landed directory now takes `[pulled namespace-name]`, not the baseline `[unit namespace-name]`; later lanes use that installed held-value seam. S1-01's deleted namespace/schema/test paths no longer exist at current HEAD.

| Exact file | Function:line |
|---|---|
| `src/seon/sci/eval.clj` | `build-base-ctx`:348; `binding-rows`:613; `namespace-source`:671; `namespace-context-row`:704; `acquire-program!`:2323; `program-documentation`:1835; `directory-value`:2019; `documentation-value`:2056; `function-doc-map`:1997; `arglists-reader`:1916; `stored-source`:2106; `stored-source-fn`:2127; `program-doc-var`:2079; `program-dir-var`:2095; `install-program-doc!`:2137; `agent-test-namespace`:318; `evaluate`:3828 |
| `src/seon/sci/arm.clj` | `arm!`:45; `write-event`:71 |
| `src/seon/sci/observe.clj` | `observe`:19 |
| `src/seon/turn.clj` | `plan-tx-for-author`:485; `plan-tx`:490; `source-rows`:597; `plan-call`:635; `system-run-plan`:714; `append-generated-call`:791; `receipt-start-tx`:877; `receipt-row`:897; `receipt-start-call`:919; `receipt-settle-batch-call`:956; `recorded-evaluation`:1236; `record-evaluated-call`:1289; `receipt-read-evidence-tx`:1217; `system-turn`:2024; `system-plan`:1903; `latest-evaluations`:1788; `generated-read-fault`:1954; `disposition`:3095; `entry-bindings`:4867; `settle-batch!`:3505; `settle-batch-refusal!`:3796; `settle-evaluated!`:3624; `fault-window`:3441; `with-fault-windows`:3461; `open-turn`:4313; `call-turn`:4390; `resume-turn`:5092; `close-turn`:5273; `generate-turn`:5295; `step`:5824; `turn-rows-state`:4682; `read-row`:4689; `publish-row`:4775; `evaluate-sources`:4878; `planned-sources`:3007; `code-sources`:3024; `code-evidence`:4837; `next-agent-work`:2805; `await-turn-permit!`:5635; `arm-turn-completion-backstop!`:5766; `recover-tx`:1473; `open-tx`:697 |
| `src/seon/turn/batch.clj` | `write-entity`:241; `namespace-facts`:470; `analyze-form`:226; `captured-locals`:160; `derive-form`:303; `record-facts`:658; `evaluation-record`:637; `settle`:709; `program-facts-call`:602; `test-request`:950; `read-only?`:790 |
| `src/seon/batch.clj` | `evaluate-step`:355; `apply-pending`:513; `derive-step`:543; `form-step`:587; `record-faults`:651 |
| `src/seon/db.clj` | `transact-call`:5683; `transaction-result`:5908; `transact!`:5937; `stamp-transaction`:3584; `index-page`:3261 |
| `src/seon/blob.clj` | `with-publication!`:304 |
| `src/seon/turn/world.clj` | `record-fault`:136 |
| `src/seon/fn.clj` | `namespace-entity`:201; `runtime-analysis-batch`:944; `interpreter-refer-entities`:846 |
| `src/seon/render/ns.clj` | `namespace-form`:77; `function-ai`:192; `public-functions`:219; `directory`:235; `render-ai`:249; `render-html`:322; `task-ai`:100 |
| `src/seon/repl.clj` | `render-directory-ai`:37; `render-directory-html`:48 |
| `src/seon/bootstrap.clj` | `help`:12; `help-value`:17; `render-help-ai`:67; `render-help-html`:73; `situation`:98; `seed-tx`:312; `dir`:161; `doc`:166 |
| `src/seon/cluster/agent.clj` | `segmenter`:644; `submit-source-in-projection`:690; `turn-step`:511; `arm!`:1223; `disarm!`:1465; `stop-graph!`:1508 |
| `src/seon/cluster/prompt.clj` | `select`:284; `compose`:272; `prompt`:403; `capture-mismatch`:318; `acquire-context-report`:349 |
| `src/seon/context.clj` | `message-custody`:257; `capture-ai`:309; `capture-html`:320; `contribution-entity`:336; `capture-tx`:359; `contribution-hash`:415 |
| `src/seon/render/walk.clj` | `root-acquisition`:502; `history`:840; `graph`:1117; `related`:1211 |
| `src/seon/eval.clj` | `of-agent`:17 |
| `src/seon/effect.clj` | `evaluation-eid`:210; `open-call`:227; `request*`:735 |
| `src/seon/task.clj` | `home`:381; `task-edges`:321; `assign!`:467; `assignment`:496; `task-resolution`:698; `resolution`:722 |
| `src/my/task.clj` | `own-task`:108; `done!`:163 |
| `src/seon/test.clj` | `attempt-request-context`:184; `bounded-result`:216; `member-result`:1987; `recorded-result`:1425; `verified?`:1562; `resolve-test`:1484 |
| `src/seon/test/runner.clj` | `run-var!`:428; `complete-members`:1546; `commit-results!`:1971; `exit-tx`:2010 |
| `src/my/program.clj` | `file-line`:849; `nearest`:862; `source`:874; `search`:914; `declaration-diff`:1035 |
| `src/seon/sci/forgive.clj` | `segments`:389; `next-form`:333 |
| `src/seon/mcp.clj` | `jvm-run`:265; `sci-run`:236 |
| `src/seon/run.clj` | `usage-form`:58; `complete`:107; `wait`:90 |

S3 exact existing test/resource conversion files: `test/seon/bootstrap_test.clj`, `test/seon/cluster/turn_test.clj`, `test/seon/context_selection_test.clj`, `test/seon/loop_proof_test.clj`, `test/seon/render/transcript_run_test.clj`, `test/seon/schema/datahike_parity.edn`, `test/seon/turn_loop_test.clj`, `test/seon/turn_test.clj`, `test/seon/turn_work_test.clj`, plus new entrant-origin row in `test/seon/mcp_entrance_test.clj`. The schema is `resources/seon/schemas/seon.cluster.eval.edn`.

Library pins: SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`; Datahike `e5453d967b749794944dbdfe64b751519983ba59`; Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`; core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`. SCI `reference-code/sci/src/sci/impl/namespaces.cljc:2303` dir-fn owns alias/public-name walk, `:2351` find-doc owns metadata traversal and `:2369` apropos owns name search; metadata changes are observed on each lookup. Malli `reference-code/malli/src/malli/core.cljc:2672` validator reuses the compiled schema object; compile at adoption/arming, not per directory member. Flow `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:211` init and `:235` transition specify the proc state/lifecycle seam; a transition is not proof that an arbitrary host call has exited. Datahike pending and transaction-function details remain S5c/S4a proof obligations; no new upstream seam is claimed without that proof.
