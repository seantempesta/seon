# Eval path audit 2 — 2026-09-27

**Verdict: no.** This is not yet a straightforward read → SCI evaluate → derive → gate → settle → reply pipeline. SCI evaluates ordinary expressions, but Seon decides important effects from syntax before execution, implements a second `do` evaluator, admits at most one declaration per evaluation, reads accepted source again, and can replace an evaluated value with a later failure. The owner's concern is justified. Adding `let`, `when`, or another nested-form recognizer would perpetuate the defect.

This is an audit, not an implementation approval. No production source was edited and no tests were started. The document is the only deliverable. Static checks and the exact root-owned regression list are at the end.

## Evidence, scope, and limits

Source citations below are relative to the repository and pinned to **496138b75bd16b7015f6d4758269556f0aa9ec8a**, copied using `git archive` into an owned disposable directory. The audited path files were unchanged when compared with HEAD `a2fd4676cdf71b2dfac8264e03a5ae027d399f64`. A checkout is not proof of installation.

At entry, `bin/seon status` took **530 ms**. MCP `runtime_status`, explicitly selecting `/Users/sean/src/seon`, cluster `default`, reported **faulted**, ten open faults, including `seon.turn/settle-batch-refusal!`; no missing readiness layer. The process was PID **60186**, started **2026-09-27T17:04:03.353Z**, running archive **5c49f374c251e1c6d92c7cdb170f66b6cd30c540**, not this audit's source pin. Reported readiness was **10,112 ms**; cache weight **45,293,820 / 512,000,000 bytes** is cache occupancy, not heap usage. I did not start a corpus, test request, replacement JVM, or restart. No new eval-path latency or heap measurement is claimed.

Read authorities: `AGENTS.md`; `.agents/skills/repl/SKILL.md`; `.agents/skills/data-oriented-clojure/SKILL.md`; [first audit](eval-path-audit-2026-09-26.md), [review](review-eval-path-audit-2026-09-26.md), [trace](eval-trace-2026-09-26.md), [reader collapse](one-reader-collapse-2026-09-25.md), and [REPL language design](one-repl-language-2026-09-25.md). The former design's honest two-read proposal is not the owner's present one-reader target. Historical timings in those documents are not measurements of today's checkout.

Dependency revisions inspected:

| Dependency | Revision | Verified seam and guarantee |
|---|---|---|
| SCI | `743d1c4317d95af9d1af74928392b913c0d1b91d` | `reference-code/sci/src/sci/impl/interpreter.cljc:29`: `eval-form*` recursively evaluates `do` itself; other forms go through SCI analysis and evaluation. `:89` interleaves parsing and evaluation in `eval-string*`. |
| SCI context | same | `reference-code/sci/src/sci/core.cljc:334`: `fork` copies environment ownership, not arbitrary mutable host objects. `:701`, `:721`, `:736`: namespace bindings, interns, and state are readable. These are APIs in the pinned maintained fork; do not claim all are upstream guarantees. |
| SCI definition | same | `reference-code/sci/src/sci/impl/analyzer.cljc:832` builds a definition node with source location; `impl/evaluator.cljc:25` evaluates initializer/metadata, binds the Var root, replaces metadata, and returns the Var. This implementation supplies no definition-commit callback for Seon. |
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` | `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:1393` analyzes `do` children; `:1350` analyzes lexical bodies; `:940`, `:1040`, `:1068`, `:1852`, `:1938` analyze/register definitions. Nested syntax is supported. Analysis is not evidence that an initializer executed. |
| edamame | `196ea5e4eff390428d8cfd33661cc415a2eec76b` | `reference-code/edamame/src/edamame/core.cljc:126`, `:144`: `parse-next` / `parse-next+string` consume one form and retain exact consumed source through a source reader. SCI's parser (`impl/parser.cljc:142`) supplies live aliases, syntax-quote resolution and readers to edamame. |
| Malli | `43c6dca8d2d8df3162b13725939954807be4a4ed` | `reference-code/malli/src/malli/core.cljc:2596` (`schema`), `:3130` (`function-schema`) compile/validate schema identity. Seon's contract product and `program/contract-facts` already consume compiled Malli arities (`src/seon/program.cljc:1100`). Keep that authority. |

The requirement should mean **no extra nesting-dependent language restrictions imposed by Seon**. SCI is an interpreter with its own JVM/compiler and host-object limits, not a promise to implement every JVM Clojure construct. Static kondo analysis cannot tell which branch ran, recover arbitrary computed `eval` source, or serialize captured host objects. Language execution, safe contract entry, and durable/replayable publication are distinct guarantees. An unsupported publication must be a diagnostic beside the evaluated value, not a claim that valid Clojure was unreadable.

## 1. Where facts come from syntax or position

These are the fact-producing or fact-dropping sites, including the consumers that turn a parser guess into durable authority. Some position joins are necessary provenance today; deleting them without replacement would admit false facts.

| ID | Owner and evidence | Decision; consequence |
|---|---|---|
| F01 | `src/seon/sci/reader.cljc:195`, `:251`, `:306`, `:331`, `:342`, `:351`, `:363` | `namespace-info`, `function-declaration`, `test-declaration`, `schema-declaration`, `schema-unregister`, `namespace-unmap?`, `declaration-facts` lift identities, source contracts and namespace clauses from the outer form. Recursive definitions are not uniformly represented; only schema/unmap/ns facts still directly select evaluator operations. |
| F02 | `reader.cljc:384`, `:395` | `next-reading-context` predicts the namespace, aliases and refers from `ns`, quoted `in-ns`, literal `require`, or a special recursive `do` mention. This is a reader-time simulation of execution. A namespace change inside a function/conditional cannot be predicted this way. |
| F03 | `src/seon/cluster/reply.clj:63`, `:192`, `:308`, `:403` | Segmentation uses those events to freeze source namespaces and then drops the parsed forms. Prose/scalar classification can drop valid forms before evaluation. `sources` rereads for segmentation; `evaluate` reads each retained source again. |
| F04 | `src/seon/sci/eval.clj:470`, `:3877` | `row` and `declared-row` trust the reader's schema identity to decide whether to create a registration delta at all. Nested calls, computed keys and calls through functions lack the marker and reach the isolation guard. |
| F05 | `eval.clj:3884`, `:3892`, `:3911` | A single syntactic schema identity also decides which delta member is inspected; the expression's return must equal that identity. Multiple changed schemas cannot be reported. `schema/changed-keys` already exists but is not the authority here. |
| F06 | `eval.clj:505`, `:562`, `:584` | Actual intern snapshots are a good start, but `program-value?` selects functions/tests rather than all changed Vars. A new data `def` stays private while file indexing accepts `def`/`defonce` (`src/seon/fn.clj:259`). Replacing an existing function with data is refused by the provenance classifier. This is a runtime classification mismatch, not a reader bug. |
| F07 | `eval.clj:608`, `:627` | `program-change` requires exactly one analyzed occurrence with the same symbol and exact start position as SCI Var metadata; rejects rebound roots/repeated occurrences; exempts generated host helpers by shared position. It prevents matching an unexecuted declaration to a different root, but rejects repeated valid definitions and computed/generated declarations. Never replace this with symbol-only matching. |
| F08 | `eval.clj:905`; `src/seon/fn.clj:1533` | `analyzed-rows` sends the **one form's source**, starting namespace and rootless-Var prelude to `source-rows`. It is not whole-run analysis. Earlier/later source context is reconstructed, and a child's namespace change can be analyzed under the outer form's starting namespace. |
| F09 | `eval.clj:960`, `:1000`, `:1033`, `:1042` | Admission counts declared identities, refuses more than one or a definition plus removal, and only exempts a top-level `ns-unmap` from removal refusal. Seon recursively evaluates `do` children to admit/arm between them. `let`/`if`/macro bodies have different admission timing. |
| F10 | `eval.clj:1235`, `:1266`, `:3945` | Namespace bindings are partly observed from SCI, but `namespace-source` reconstructs `ns` source by recognizing a standalone `require`. `unmap-row` observes full namespace changes only with the reader's unmap marker, and suppresses a context row if a declaration/deletion row exists. One starting namespace is selected, not every changed namespace. |
| F11 | `eval.clj:4695`, `:4709`, `:4837`, `:4854` | Reader marker controls extra captures; a reader `ns` fact can override observed ending ns; only the first Var row proceeds. `definition-row` correctly prefers runtime contract/workload/test metadata (`:926`), but it is downstream of these filters and derives a digest with the supplied starting namespace row. |
| F12 | `src/seon/fn/analyzer.clj:600`, `:653`, `:716`, `:829` | Kondo analysis is supplemented with another syntax-tree parse: literal `def`, `require` spans, Var quote spans, namespaced attribute maps, task maps after names. Imports/renames are attributed by containment in `ns`/`require` spans. Call ownership is filled from the innermost analyzed definition span. These are not all top-level-only, but they are positional facts outside kondo's returned analysis. |
| F13 | `src/seon/fn.clj:259`, `:692`, `:775`, `:838`, `:1435`, `:1466` | Kondo definition head families decide persistent row kinds/inline facts; runtime namespace preludes reconstruct require specs from aliases/refers (renamed local names are not faithfully retained); namespace context refuses unsupported rename evidence. Whole-file contexts collapse by namespace name. These must not override observed runtime bindings. |
| F14 | `src/seon/fn/signature.cljc:17`, `:39`, `:62`, `:124`; `src/seon/program.cljc:1112`; `src/seon/fn.clj:1675` | A separate JVM reader and handwritten defining-form grammar decide declaration metadata/digests and source arities. Only defn/defn-/defmulti signatures are accepted; defmulti requires a literal `fn` dispatch. Thus kondo accepting a computed function `def` is insufficient for the contract-facts path. Source/contract arity agreement is necessary; this duplicate reader is not. |
| F15 | `src/seon/fn.clj:1099`, `:1117`, `:1208`; `src/seon/turn.clj:1013` | Call/keyword/reference facts are attached to the singular supplied program row, filtered by source span, and settlement analysis runs only when that row and a receipt source exist. No row means no corresponding published declaration analysis, regardless of what executed. |
| F16 | `src/seon/turn.clj:619`, `:639`, `:1319` | `namespace-rows` enumerates starting namespace plus segmentation's source namespace names. A program namespace row is consulted only **after** its name was selected. `row-tx` additionally creates fn/test namespaces, but does not close the set of all receipt/ending-ns references. This is the nested `in-ns` settlement defect. |
| F17 | `turn.clj:1597`, `:3398`, `:5080`, `:5397` | Settlement, install gates, speculative working values and post-commit installation consume singular `:seon.program/row`. Widening only `definition-row` leaves silent first-row loss or gate omissions in these consumers. Convert the contracts and every quoted/schema consumer in the same slice. |
| F18 | `src/my/program.clj:682`, `:720`, `:736`, `:801`; `eval.clj:327`, `:419` | A second mutation path: explicit program deletion transacts before changing SCI; selected native mutations are redirected/refused by operation and positional arguments. This is not top-level syntax walking, but it defeats “all program effects settle once” if left outside the evaluation delta. The advice can name `my.program/define!`, which this file does not define. Do not advertise it as an installed API. |
| F19 | `src/seon/program.cljc:1394` | `deletion-row` consumes the reader unregister identity or falls back to a three-item unmap form with two quoted symbols. It can manufacture fn/test deletion identities from operands instead of observed removals. The observed-removal branch already exists and should be the authority. |
| F20 | `src/seon/render.clj:462`; `eval.clj:4894` | `head-function` assigns a result producer from a contracted non-macro top-level head symbol resolved after evaluation. `(f x)` and `(do (f x))` can therefore acquire different producer evidence for the same returned value. This is presentation/contract provenance, not a definition fact; derive any such provenance at the actual call. |
| F21 | `eval.clj:4058` | Rendered REPL entries are all read before evaluation, then evaluated in the same request namespace with run/ordinal/handle removed. The `::rendering?` flag suppresses recursive rendering, not writes. No read-only enforcement is present at this boundary; render forms can reach mutation owners and their new rows are not passed through the ordinary settlement. |

Two subtleties matter. `clj-kondo` **does see nested defs**; the main defect is not its inability to recurse. And saving just the nested def's substring loses lexical capture: `(let [k 3] (defn f ... [x] (+ k x)))` can create a working closure, but reacquiring `(defn f ... [x] (+ k x))` alone cannot reconstruct `k`. The exact-source extraction (`fn.clj:163`, `:702`) and reinstallation (`eval.clj:1872`) make this a real publication-design constraint. Full-run analysis fixes visibility, not closure serialization.

## 2. Counted form-shape inventory

**Count: 52 semantic branch families in the audited path.** Counting convention: one numbered row per distinct shape-dependent policy, even when implemented by several Boolean tests. Repeated consumers are separate when they decide a different outcome. Generic error handling, row-identity dispatch, Malli schema grammar, and SCI/kondo's own language implementation are excluded. This is a reproducible policy-site count, not a count of every `if` token. F06/F07's runtime provenance/type rules and F18's native-operation policy are additional restrictions, not hidden in the shape count.

| # | Shape-dependent branch | Location |
|---|---|---|
| 01 | Parse `ns` name/doc/attribute/require clauses through Clojure spec | `reader.cljc:195` |
| 02 | Namespace operand must be a two-element quoted symbol | `reader.cljc:226` |
| 03 | Require arguments must be quoted literal values | `reader.cljc:234`, `:241` |
| 04 | `defn`/`defn-`: name, optional doc/map, single/multiple arities | `reader.cljc:251` |
| 05 | `deftest` name and metadata | `reader.cljc:306` |
| 06 | Resolve bare/qualified/aliased/referred operator, default bare to core | `reader.cljc:317` |
| 07 | `register!`, exactly three items and a literal qualified key | `reader.cljc:331` |
| 08 | `unregister!`, exactly two items and a literal qualified key | `reader.cljc:342` |
| 09 | `ns-unmap`, exactly three items | `reader.cljc:351` |
| 10 | Declaration dispatch: `ns` versus first matching declaration family | `reader.cljc:363` |
| 11 | Find nested namespace mentions only through bare `do` | `reader.cljc:384` |
| 12 | Advance parser context on top-level `ns` | `reader.cljc:395` |
| 13 | Advance parser context on literal top-level `in-ns` | `reader.cljc:409` |
| 14 | Advance aliases/refers on literal top-level `require` | `reader.cljc:417` |
| 15 | Erase namespace attribution on recognized nested change | `reader.cljc:443` |
| 16 | Refuse a map that names reserved `:seon.repl/*` response keys | `reader.cljc:571` |
| 17 | Recover malformed input at syntactically plausible column-zero anchors | `reader.cljc:712` |
| 18 | Scalar is code according to line position and presence of structured code | `cluster/reply.clj:97`, `:171` |
| 19 | Drop an unqualified symbol ending `=>` at prompt position | `cluster/reply.clj:125` |
| 20 | Collection or `#`-prefixed form counts as structured code at permitted position | `cluster/reply.clj:141` |
| 21 | Prose/read-error classification via code-line, readable suffix and parsed prefix | `cluster/reply.clj:246`, `:251`, `:269`, `:281` |
| 22 | Reader schema/deletion/ns facts become row kinds | `sci/eval.clj:470` |
| 23 | Only one reader event accepted by the per-form evaluator | `sci/eval.clj:1109` |
| 24 | Bare `do` is recursively evaluated/admitted by Seon | `sci/eval.clj:1033`, `:1042` |
| 25 | Top-level unmap marker controls removal admission | `sci/eval.clj:995` |
| 26 | Standalone `require` splices quoted operands into stored ns source | `sci/eval.clj:1235` |
| 27 | Reader schema marker decides registration-delta binding | `sci/eval.clj:3884` |
| 28 | Syntactic unregister key must equal returned identity and disappear from delta | `sci/eval.clj:3892` |
| 29 | Syntactic register key must equal returned identity and exist in delta | `sci/eval.clj:3911` |
| 30 | Unmap marker enables complete namespace snapshots/diff | `sci/eval.clj:3959`, `:4709` |
| 31 | Declaration/deletion row suppresses standalone namespace-context row | `sci/eval.clj:3977` |
| 32 | Dispatch evaluation as live declaration, schema result, `do`, or generic form | `sci/eval.clj:4805` |
| 33 | Reader ns declaration overrides SCI's ending namespace | `sci/eval.clj:4837` |
| 34 | `do` rows versus ordinary admission; collapse vector to first row | `sci/eval.clj:4854` |
| 35 | Literal-value grammar: quote, scalar, recursive collections | `fn/analyzer.clj:642` |
| 36 | Var-quote parser tag becomes source fact | `fn/analyzer.clj:675` |
| 37 | Bare/core `require` node becomes clause span | `fn/analyzer.clj:679` |
| 38 | Bare/core `def`, three items or docstring/four items, literal initializer | `fn/analyzer.clj:685` |
| 39 | Namespaced map after name/optional doc is declaration metadata | `fn/analyzer.clj:698` |
| 40 | Map after name/optional doc naming `:seon/tasks` gets positional marker | `fn/analyzer.clj:708`; refusal `fn.clj:634` |
| 41 | Import/rename belongs to namespace by containment in ns/require form | `fn/analyzer.clj:716` |
| 42 | Definition family/arglists/literal flag decides whether a Var becomes a function row | `fn.clj:259` |
| 43 | Only kondo `defn`/`defn-` gets inline fact; literal def gets constant fact | `fn.clj:775` |
| 44 | Seed annotation entry is symbol, literal fn, entity map or vector of maps | `fn.clj:526` |
| 45 | Invocation annotation target is qualified symbol or `[:seon.fn/sym ...]` | `fn.clj:644` |
| 46 | Metadata grammar: defining head family, optional doc/map, trailing multi-arity map | `fn/signature.cljc:39` |
| 47 | Signature grammar: defn/defn- or defmulti with literal fn dispatch | `fn/signature.cljc:62` |
| 48 | Signature body is vector or list of vector-headed arities; positional `&` | `fn/signature.cljc:85`, `:99` |
| 49 | Reader unregister marker becomes a schema deletion identity | `program.cljc:1412` |
| 50 | Unmap fallback accepts three items with quoted namespace/name operands | `program.cljc:1424` |
| 51 | JVM MCP requires exactly one form and advises wrapping multiple forms in `do` | `script/seon/dev/mcp.clj:390` |
| 52 | A symbol-headed, contracted non-macro call supplies the return producer | `render.clj:462` |

Rows 44–45 describe explicit Seon annotation data; their existence is not itself a language defect. Likewise exact source spans for diagnostics and kondo lexical call ownership should survive. The deletion target is independent language interpretation and fact authority, not all conditionals indiscriminately.

## 3. Gates and the actual current path

The current successful route is approximately:

`agent/submit-source!` → reply segmentation/reader → frozen source plan → `turn/resume-turn` → `fork-for-turn` → per-form acquire/read → reader-directed schema path or Seon-directed `do` or SCI → snapshot/analysis/admission → render → install/test gate → speculative `db/with` for later forms → prepare receipts → one settlement transaction → install committed rows → offer run report → read saved observation → MCP rendering.

* **Entry gate is not the advertised pre-evaluation policy.** `admit-program-change!` runs after SCI changes Vars (`eval.clj:987`); it restores rejected changes. `missing-contract` explicitly says no entry dial exists (`:2460`). Its non-macro/non-host function check and post-eval arming (`:1007`, `:1016`) are real. There is no implemented general schema/test/test-first `:gate`/`:warn` switch on this route. A rollback cannot undo an initializer's external effects. The source tests intentionally enforce this current restricted behavior (`test/seon/sci/entry_gate_test.clj:85`, `:120`, `:242`).
* **Install gate is real but singular.** `turn/gate-function-install:3398` only enters for a function row with a spec; otherwise a function row is removed (`:3470`). `evaluate-candidate` (`eval.clj:5103`) tests the retained candidate in a disposable branch/context; it does not simply re-evaluate the candidate function to discover its result. Preserve that distinction and context ownership.
* **Test gate is not a language parser.** `fn/gate-set`, `accretion/auto-check:190`, `gate-report:337` select and combine evidence. Skipped effectful/non-generatable checks are explicit; zero failures can install even with zero selected tests. `run-candidate-test` (`eval.clj:5026`) separately re-evaluates a test declaration and invokes the real runner. Keep dependency/test evidence and Malli validation, remove no gate merely because it follows evaluation.
* **Arming in two worlds is not necessarily duplicate work.** Runtime candidate arming and committed/acquired-context arming serve different contexts. Delete redundant work only after proving identical function, contract and context inputs. Preserve generation-aware restoration (`eval.clj:712`, `:830`) and private-layer regeneration (`:3320`). A shallow SCI fork does not roll back shared atoms or arbitrary host effects.
* **“One reader” currently means one facade, not one read.** Reply segmentation reads; `one-event` reads the retained string again; `analyzed-rows/source-rows` invoke kondo; the analyzer reparses kondo nodes; signature code invokes JVM `read`. A necessary independent static kondo pass is not a second evaluator, but the duplicated execution reads and handwritten signature grammar are avoidable.
* **JVM MCP is another entrance, not this same recorded loop.** `script/seon/dev/mcp.clj:370` reads one source form then calls host `eval`; `:403` sends SCI source through `evaluate-source!`. A target claiming all entrants share recording still needs to compose the JVM evaluation stage into the shared owner. Do not conflate today's JVM inspection tool with agent SCI execution.

## 4. Totality: where the value or reply can disappear

“Has a catch” is not “returns the value.” The boundary must retain the result before any subsequent stage can fail. These are the concrete failure classes across this route; ordinary helper throws feed the listed boundaries.

| ID | Boundary | Failure mode and evidence |
|---|---|---|
| T01 | Submitted text | `reply.clj:141`, `:171`, `:192` can exclude valid scalar/form-shaped data as prose/prompt material; `reader.cljc:571` refuses valid maps with response keys. No evaluation occurs, but callers cannot claim a general Clojure entrance. |
| T02 | Evaluator setup | Context building, environment/projection/profile preparation precede the inner evaluation try (`eval.clj:4430`–`:4556`). These can throw without an evaluation envelope. |
| T03 | Loaded identity fence | `call-detecting-program-change` (`eval.clj:1617`) runs work and returns a program-change refusal **instead of** its result if identity changed. `trusted-evaluation:4360` throws that refusal. Preserve trust status beside the value. |
| T04 | Post-SCI admission | Analysis, provenance, contract compilation and arming failures flow into `failure-result` (`eval.clj:4601`, `:4917`), substituting failure for a value SCI already produced. Restoration is useful; discarding the value is not. |
| T05 | Ending namespace on failure | `eval-in-form!` saves actual ns only after successful execution (`eval.clj:4755`); `failed-evaluation:4259` writes starting ns as ending ns. A namespace changed before a throw is not accurately observed, even though print options are captured in `finally`. |
| T06 | Printing and result admission | `shown-result:4108` catches declared rendering failure and falls back, a good local boundary. Profile/bind-result preparation and diagnostic/fallback printing still have escapes (`:4135`, `:4143`, `:4180`); failure rendering can itself fail. Outer `evaluate` rethrows once arm-state exists (`:4920`). Result storage/admission failure must not erase the live result. |
| T07 | Install/test gate | `refuse-install:4966` rebuilds a failure evaluation and removes the program row/bindings, replacing the evaluated value; `accept-candidate!:4945` can throw after restoration. Candidate branch setup/test/cleanup (`:5055`, `:5103`) can escape; cleanup failure must be secondary to retained result/evidence. |
| T08 | Speculative settlement | `turn/settled-value:1410` catches ExceptionInfo as data; `with-settled:1420` silently keeps the old DB on refusal. Later forms can run against stale facts; the refusal is not delivered at this seam. No new retry or second store is needed: carry the refusal beside the working value. |
| T09 | Per-form turn postprocessing | `evaluate-sources:5031` appends the evaluation only at `:5176`, after gate, disposition validation, generated-read recording and another result binding. Disposition refusal uses `unrun:5143` to replace the result. A throw before append loses that evaluation and may lose the completed prefix. |
| T10 | Whole-loop phase | `resume-turn:5321` wraps the whole evaluation loop, not an accumulating envelope. `phase:3551` catches only ExceptionInfo; other Throwables escape. A failure map replaces the vector, so prior values are not available to settlement. Problem derivation also drops phase-failed output (`:5383`). |
| T11 | Settlement preparation | `settle-batch!:3857` builds every terminal request and runs issue-completion tests **before** the commit `phase`; terminal render/classification/delivery/fault preparation can throw. These test calls are production behavior, not tests run by this audit. |
| T12 | Primary settlement refusal | The normal refusal arm (`turn.clj:4074`) deliberately retains each shown value and adds diagnostics. This is good. But it preserves unresolved receipt/ending namespace refs (`:4124`) and reconstructs submission from predicted namespaces (`:4143`). If the refusal settlement is refused, it throws (`:4160`); `settle!:4168` has the same second-write vulnerability. |
| T13 | Applied versus durable | `settlement-lost!:3834` routes a later durable failure to fault/stop and names lost runs. An applied reply is not proof of durability. No handler can promise stored values after storage failure, but it can still return held values with that failure if the process remains alive. |
| T14 | Installation after commit | `resume-turn:5397` installs rows after settlement and before the normal report returns. An install throw can leave committed receipts yet prevent a normal reply. Publication/adoption state must accompany the committed result rather than veto delivery. |
| T15 | Flow reply port | `turn/step:6040` closes a parked submission without a value; `:6188` closes any unanswered port in `finally`; no-permit exit also has no result offer. `cluster/agent.clj:1524` closes queued replies on retirement. Await produces closed/timeout evidence, not the evaluated values. Closing a run/faulting a graph is not replying to its submitting caller. |
| T16 | Direct source caller | `cluster/agent.clj:806` returns refusal only for a run absent on the basis; the outcome arm (`:813`) returns id/basis/durable and drops an opened run's refusal and `:error` status. This is the confirmed 22-form caller loss. The values may still be in receipts. |
| T17 | MCP observation/transport | `script/seon/dev/mcp.clj:446` reads `run-observation` only after getting a basis; an observation/render failure occurs after successful work. `run-events:449` parses the terminal EDN with a swallowed parse error and emits only saved evaluations when present. It cannot reconstruct lost in-memory values or a missing basis. Transport exceptions can answer an error, not the successful prefix. |

Also keep static analysis failure loud: `fn/analyzer.clj:944` swallows a parse Throwable in `referenced-program-namespaces` and returns only the starting ns, potentially reducing analysis context. `turn/analyze-settlement-form:1013` recognizes a particular namespace refusal before selecting the returned row; widening error unions must not silently turn another refusal into absent analysis.

### The four reported failures, reconciled

1. **Nested `defn`:** historically missed; the audited source now has real intern observation and a specific nested-`do` workaround. One contracted nested definition can pass; multiple definitions, generated/repeated provenance and contract-source grammar still refuse. Do not report all nested defs red from source alone. Existing `entry_gate_test` explicitly exercises accepted nested `do` and multiple-definition refusal.
2. **Nested `in-ns`:** actual next-form namespace is carried by `evaluate-sources`, but namespace creation is selected from the segmentation plan. That mismatch can reject both normal and refusal settlement. Fix the namespace-reference closure, not a new `do` walker.
3. **Nested/computed `register!`:** confirmed in the [registration diagnosis](landing/register-anywhere-diagnosis-2026-09-27.md), run `1eb1f23ad83d`, **415 ms**. The guard at `schema.clj:1311` is correct: it prevents process-wide mutation. Its caller only binds the overlay for the reader-recognized shape. Git history in that diagnosis identifies the narrowing from all evaluations (`c7c04247a`) to reader-marked declarations (`10dfe0ff4`). Restore the scope, keep the guard.
4. **22 top-level registrations:** the same diagnosis identifies run `ce27ba34bd16`, **22 stored receipts**, fault `c4cd72ebcfa0`. `cluster/invalid-value-retraction` (`src/seon/cluster.clj:1241`, especially `:1265`) builds a set for every cardinality-many attribute; a declared vector rejects it. Component edges are retracted without removing owned children, so writer validation correctly refuses. `db.clj:3472` already normalizes according to declared collection type. The refusal settlement succeeded and preserved shown values; `evaluate-source!` dropped the outcome/refusal to its direct caller. This is **not** evidence that all 22 values vanished from storage.

The diagnosis's **35,618 ms** maximum source call, **14,879 ms** evaluation and **20,457 ms** transaction are reported measurements from that lane. Its JVM-lifetime profile totals overlap and must not be added as exclusive time. The unchanged-projection retraction scan was **826 ms** across 117 attributes; eleven attributes would lose values. Repeated whole-projection work is a measured concern; an exclusive allocation/time split remains unmeasured. This audit does not authorize further >10-second probes.

One correction to that document: its claim that branch MCP accepts exactly one form is stale relative to `script/seon/dev/mcp.clj:403` and the tool's main description; both branch and root SCI use `run-form`/code segmentation. The tool argument description still contradicts this. JVM mode is the exactly-one-form bridge. Do not base the fix on the stale branch limit.

## 5. Straightforward target and what survives

**One execution reader → SCI evaluation per top-level form → observed effects plus one whole-run static analysis → gates → one transaction → total reply.** Reading and evaluation must be interleaved, not “read all forms in the initial namespace, then eval.” Aliases, tagged readers, syntax quote and `*ns*` for the next top-level form come from SCI after the previous one. A form already read is not retroactively reread when it contains `in-ns`; ordinary Clojure has the same boundary.

1. **Read.** Feed the submitted code stream to edamame/SCI's source reader once. Preserve form, exact source, positions and reader error as values. Remove presentation fences before this stage; do not infer a namespace or program row from form shape. Keep prose extraction only as an explicit model-message presentation adapter. Code mode must accept scalar forms and ordinary maps. Kondo independently reads source for static analysis; “one reader” here means one execution parse, not eliminating the analyzer's parser.
2. **Evaluate.** Hold the agent's existing SCI fork/custody and call `sci/eval-form` once per top-level form. Delete Seon's recursive `do` evaluator. Record the result immediately; in `finally`, capture actual ending namespace and the relevant mutation observations even after a throw. Keep bounds, interruption, program-identity fences and private-state preservation, but attach their post-eval failures beside values.
3. **Observe.** Start a schema registration delta for every evaluation through the existing `begin-registration-delta` / `call-with-registration-delta` (`schema.clj:3576`, `:3603`), consume `changed-keys:3622`. The call owns actual keys/forms, not a reader marker or return value. Observe Var root/metadata changes, removals, and changed namespace bindings using existing SCI snapshots. Derive final program facts only from executed effects, never all kondo candidates. A final diff records final state; it does not by itself record transient intern-then-unmap or repeated writes. Preserve required execution provenance separately when policy depends on those events.
4. **Analyze and join.** Analyze the run's exact source once, retaining original offsets and each form's observed namespace context. Kondo is the owner of static definitions/usages; attach these to observed mutations by credible declaration provenance. False branches, uncalled functions and a nonexecuted `defonce` initializer must not become installed definitions. Repeated names select the actual final write, not the first analyzed occurrence. Generated/eval definitions need their actually evaluated source or an explicit unpublishable diagnostic. Do not fabricate a source span or silently persist a symbol-matched body.
5. **Gate.** Separate pre-entry checks that can be known statically from publication/testing over the resulting candidate. Compile actual contracts with Malli in the candidate projection; gate every changed declaration. A publication refusal can roll back program mutations while preserving the evaluation's value. Generated definitions and same-form contract calls expose a missing seam: after-form observation is too late to arm `(let [] (defn f ...) (f bad))`. Either retain an explicitly limited entry guarantee temporarily or add a reviewed definition-time seam in SCI. Do not reintroduce a Seon AST interpreter to simulate it.
6. **Settle.** Carry a vector of changed rows, with one canonical final row per identity and ordered evaluation observations. Derive required namespace upserts from every namespace reference in the final owning transaction—receipt start/end, declaration owners and binding targets as required by their schemas—rather than a hand list from segmentation. Apply one writer-checked transaction for the run. Reuse actual schema collection normalization; validate full owners, digests and dependencies at the writer. Keep speculative immutable candidate values only where later forms need them; a refusal must be explicit, never replaced by the old DB unnoticed. Bring `my.program` evaluation mutations into this owner rather than transacting a parallel program change mid-form.
7. **Reply.** Retain a completed-prefix envelope independently of settlement. Values, output, handles, durations and diagnostics are separate members. Formatting failure uses the floor; recording/installation failure appends diagnostics; a double settlement failure still answers the submitting channel with held evidence before fault escalation. Do not invent a fallback store or retry loop. A dead process cannot reply; that physical limit is different from deliberately closing a live caller's port without the available value.

What already matches: SCI's parser/evaluator; retained agent contexts; generation-aware snapshots/restoration; runtime metadata in `definition-row`; kondo's nested analysis and call graph; the schema delta owner; compiled Malli contracts; disposable gate worlds; a normal batch transaction; refusal receipts preserving shown values; successful replies using the settlement's basis (`agent.clj:815`, MCP `:446`). Keep and compose these.

What disappears: reader-produced program identities; reader-simulated namespace advancement; the outer-form-only delta; return-equals-schema-key checks; the extra `do` evaluator; one-declaration/mixed-removal restrictions; singular-row interfaces; positional namespace selection; repeated execution reads; duplicate source-signature reading; silent speculative refusals; value-replacing postprocessing; empty close as an error reply.

### The unresolved guarantee has three priced options

These are alternatives for the provenance/entry seam only, not reasons to delay the independent fixes below. Estimates are engineering judgment, not measured work.

| Option | Guarantee | Cost and sacrifice |
|---|---|---|
| **A — observed final effects (recommended first slice)** | All SCI-supported forms execute through SCI; replayable declarations publish; unsupported provenance is explicit beside the value. Contracts are armed before the next top-level form. | Approximately 2–3 days for the declaration slice, +80–120/−190–260 source lines. Sacrifices a guarantee of immediate arming of a new definition called inside an arbitrary single form; must be an explicit temporary owner decision, not a hidden regression. |
| B — SCI definition-time observation | Preserve same-form entry arming and executed-definition provenance across ordinary nested forms without a second interpreter. | Approximately 4–7 days including maintained-fork review and root integration, an additional +60–100/−20–40 lines. No verified callback exists today; implement at `eval-def`/mutation owners, outside atom retry side effects. Arbitrary `eval` and lexical replay still need source/capture evidence. |
| C — general replayable runtime closures | Promise durable reinstallation of arbitrary computed definitions and captured environments. | Unbounded with current guarantees; first 1–2 days are a feasibility study, not a delivery promise. No sound fixed LOC price: host objects/external effects cannot generally be reconstructed by serializing a closure. Sacrifices smallness and is not recommended. |

The renderer entrance must also enforce the existing read-only render contract through the same evaluator stage; a recursion flag is not that enforcement. A render failure may not mutate or replace the subject it was meant to show.

The target removes nesting restrictions. It does not justify falsely promising C's guarantee from A's snapshot.

## 6. Deletion-first slices, ordered by risk

Prices are estimated **source** lines added/deleted, including replacements rather than pretending whole functions disappear unchanged. Tests are separately budgeted at 20–50 lines per behavior class using existing fixtures, not a new harness. Slices must be reviewed before implementation. No slice is approved by this audit; root owns central tests and publication. Ranges overlap if implemented together and must not be added to claim precise savings.

Audit-local corpus labels below are explicit proposed behavior cases, **not invented results** from the other lane:

* C01 single contracted nested def; C02 two distinct nested defs; C03 repeated same-name definitions; C04 definition plus removal.
* C05 nested `in-ns` then a second top-level expression; C06 namespace change followed by throw; C07 require/alias then next-form syntax quote or auto-resolved keyword.
* C08 registration in do/let/when/function; C09 computed schema key, multiple registrations, arbitrary final return; C10 register then unregister/no-op delta; C11 the reported 22-form schema run and unchanged vector-valued schema redeclaration.
* C12 scalar/maps/prompt-like symbols as code; C13 false branch/uncalled def body/defonce no-op; C14 generated/eval definition; C15 captured-local closure and fresh acquisition; C16 same-form invalid call after defining function.
* C17 render/gate/refusal/installation failure after a successful value; C18 second settlement failure/closed-port path with a completed prefix; C19 forward declarations, renamed refers and call edges.

| Slice | What disappears and replacement | Price (add/delete) | Class regression and intended corpus result |
|---|---|---|---|
| P0: namespace/refusal correctness | Replace predicted-name selection in `turn/namespace-rows` (19 lines) and lossy `agent/evaluate-source!` outcome construction (6 lines); close the namespace refs of actual terminal rows and retain outcome/refusal. Reuse writer normalization in `cluster/invalid-value-retraction`, replacing the set coercion branch. | +25–45 / −25–45 | C05, C11; direct caller sees refusal and stored values. C06 also needs P2. No language broadening or new gate. |
| P1: total post-eval envelope | Replace value-substituting `refuse-install` (20 lines), `failure-result` post-eval use, `unrun` disposition use, and whole-loop failure-vector substitution; replace empty error closes with terminal envelopes. Consolidate preservation with existing refusal receipt machinery, not another printer. Roughly 120–180 existing lines replaced/deleted across eval/turn/agent/MCP. | +80–120 / −120–180 | C17–C18: result/prefix survives every injected local failure, one answer, truthful commit status. Actual process death remains a transport failure. |
| P2: plural effects and registrations | Delete `declared-row` (68 lines), schema reader recognizers (20), multiple-declaration helpers/restrictions (~25), unmap-marker-specific collection and first-row plumbing (~50–80). Use one always-scoped schema delta plus observed namespace/Var effects; convert every row consumer and schema together. | +80–120 / −165–210 | C02/C04/C06/C08–C10, with explicit rollback and all rows gated. C02 needs P3 if the `do` path still counts early declarations; stage as one loadable cut or retain old admission until both land. |
| P3: one definition authority | Remove `top-level-do?`/`top-level-evaluation` (38 lines), singleton `analyzed-rows` adapter (21), repeated-identity refusal and per-child reanalysis (~40–60); replace handwritten signature reader/shape extraction (about 80–100 of its 147 lines) with analysis plus runtime metadata/Malli arities. Retain source-provenance checks until replaced, not an optimistic symbol join. | +80–120 / −190–260 | C01–C04/C13/C19. C14–C16 become honest diagnostic/guarantee cases under option A, not automatic publication-green. Option B is needed for full C16 entry parity. |
| P4: one execution read and live context | Delete reader declaration/context prediction (`reader.cljc:195`–`:447`, 253 lines before shared-helper retention), `one-event` reread (48), repeated reply parsing/planning (~50–80), and namespace-source reconstruction (30). Preserve cursor/source/error adapters and explicit prose policy. Feed the parsed form through the shared request instead of source-only plans. | +90–140 / −280–360 | C07/C12 and all nested namespace cases. One parse per executable form; original source and errors preserved; no retrospective rereading inside a form. JVM and renderer entrants must use the same stage interface. |
| P5: analysis and working-value cost | Remove redundant kondo-tree parsing from `parsed-facts` (62 lines) only after its missing metadata facts have a dependency/runtime owner; remove extra mentioned-namespace parse (~17), per-row settlement reanalysis (~22), redundant growing-prefix line counting and projection recomputation (~20–40). Cache by actual immutable input content; don't introduce a parallel cache. | +40–70 / −110–140 | C19, C11 cost regression: one run analysis, candidate projection work proportional to changed inputs. Separate acquisition and warm time; compare parent/change before claiming sub-second. |

**Concurrent landing update:** after the audit pin, `bd3d35b19` replaced the many-value set validation in `cluster.clj:1241` with validation of each stored member through `schema.datahike/value-schema`. HEAD `95f44cbc3` includes it and a follow-up issue about revalidating unchanged attributes. P0’s collection-type subfix is therefore already landed in source; do not implement it again. Its new exact regression is `seon.schema-in-place-test/a-kept-vector-many-attribute-drops-only-refused-members`. This audit has not run that regression or proved adoption. The namespace/refusal subfixes remain.

P0 and P1 have the least language-semantic risk and stop losing evidence while the larger cut is prepared. P2–P4 are coupled at interfaces; do not land a plural producer into singular consumers. P5 follows positive behavior proof, not speculative optimization. `my.program`'s early transaction owner must join P2 or be named as a remaining violation of the one-settlement guarantee.

The large deletion ranges are not permission for broad churn: each implemented slice must show actual removed paths and actual diff counts. Any >100 added-line slice without corresponding deletions requires the prescribed smaller-alternative review. The new dependency callback in option B requires its own review rather than hiding inside a “simple cleanup.”

## 7. Root-owned verification and landing

Existing tests that should be selected/updated centrally, named exactly (not run by this lane):

1. `seon.sci.entry-gate-test/every-definition-a-form-makes-is-gated-and-armed`
2. `seon.sci.entry-gate-test/a-form-changes-the-program-by-one-indexed-declaration`
3. `seon.sci.entry-gate-test/a-definition-is-the-root-its-analyzed-declaration-ran`
4. `seon.sci.reader-test/namespace-tracking-has-repl-semantics-and-fails-closed`
5. `seon.sci.reader-test/syntax-quote-and-auto-resolution-follow-the-evaluating-context`
6. `seon.cluster.reply-test/every-plan-source-carries-a-reader-event`
7. `seon.sci.restoration-test/a-refused-batch-restores-the-context-that-accepted-a-candidate`
8. `seon.sci.restoration-test/acceptance-that-throws-restores-the-evaluating-context`
9. `seon.sci.program-change-test/an-evaluation-refuses-its-value-when-the-loaded-program-changes`
10. `seon.schema-redeclare-test/seeded-schema-families-survive-development-adoption`
11. `seon.schema-in-place-test/a-kept-vector-many-attribute-drops-only-refused-members` (concurrent landing)
12. `seon.sci.entry-gate-test/a-top-level-do-admits-each-child-before-the-next-runs` (must expose the same-form arming guarantee before deletion)
13. `seon.fn.publication-signature-test/declaration-metadata-distinguishes-attribute-maps-from-bodies`
14. `seon.fn.publication-signature-test/a-multimethod-contract-uses-its-dispatch-function-arities`

Names 2 and 9 encode policies this design intentionally changes; revise their assertions rather than preserving invalid restrictions to get green. Add one regression per C05/C08–C11/C17–C18 behavior class through the real submission/receipt/reply path, with real SCI, armed contracts and canonical fixture worlds. Do not merely test a reader recognizer or call private settlement copies. The document-only change requires no behavioral test run.

Foreign boundary: shared source/default were owned by other lanes; the workspace had unrelated staged/unstaged/submodule changes and the live JVM had open faults. I did not modify, resume, message, or test their work. A committed archive plus this document's overlay is the static-check input. No worktree was created. The registration diagnosis was read and cited above. The named `valid-clojure-corpus-probe-2026-09-27.md` was still absent on the final check; C01–C19 are this audit’s proposed cases, with no claimed lane result or fabricated crosswalk.

Static verification results follow. They prove only lint/contract compilation of the selected archive, not publication, adoption, runtime correctness, browser paint, or green corpus behavior.

The required REPL skill itself has stale installed claims: `.agents/skills/repl/SKILL.md:43` points `fork-for-turn` at line 3156 (audited owner is `eval.clj:3320`); `:55` names `src/seon/repl.clj:119` / `def-note`, while that source path is absent. Treat this as a documentation defect, not an installed API or authority for preserving the old temporary-state note. This audit owns no skill file.

### Static check evidence

Final input: `git archive 95f44cbc3ab1455e3a54587d53da6d619d074323`, extracted into `tmp/astra-eval-audit-2/verify-final`, with this document copied over its destination. No working-tree source changes were included.

* `clj-kondo --lint src script test --cache false`, from that archive: **0 errors, 961 warnings**, **8,035 ms** reported by kondo (**8,087 ms** process elapsed), exit 2 for warnings. This meets the explicit zero-error instruction; it is not a zero-finding lint pass. The earlier archive at `a2fd4676c` had 0 errors/963 warnings in 6,885 ms. The repeat was necessary because another lane changed source and a regression before this commit.
* Contract helper, invoked in the live JVM with explicit root/cluster and without invoking `clojure.test`: **485 files**, **2,609 ms**, **0 src/test findings, 10 script findings**. The standard dedicated `src`/`test` check on the preceding archive covered **472 files**, **2,458 ms**, zero findings. The helper compiles against the archive's packaged projection, using the carried projection only if its forms match (`test/seon/contracts_compile_test.clj:315`). These are bulk full-source scans, proportional to the 485 inputs and their contracts; they are not ordinary eval timing measurements.
* The ten broader-scope findings are pre-existing symbol/Var-valued contract forms that the checker cannot canonicalize: `script/seon/dev/docstring.clj` at **285, 318, 334, 360**, and `script/seon/dev/markdown.clj` at **1020, 1050, 1090, 1124, 1170, 1215**. Example: `seon.dev.docstring/check-source` declares `[:=> [:cat check-source-request-schema] check-response-schema]`; the checker reports `seon.schema/canonical-definition` refusing an EDN-readable schema representation. They are outside this document's ownership. This broader contract check is **not green**, and no source was changed to silence it.
* A concurrent default restart ended the first named MCP session; the tool returned `session-lost`, not a compile result. Reconnection used a new owned session. Final status: PID **66629**, started **2026-09-27T17:36:08.820Z**, archive **95f44cbc3**, still faulted with ten open faults. I did not initiate that restart or run the tests visible in its status.

The final compile operation was the following read-only inspection (with timing and findings summarized in the returned value):

```clojure
(let [root "/Users/sean/src/seon/tmp/astra-eval-audit-2/verify-final"
      paths (seon.contracts-compile-test/source-files
             (mapv #(str root "/" %) ["src" "script" "test"]))]
  (seon.contracts-compile-test/check
   {:seon.contracts-compile-test/root root
    :seon.contracts-compile-test/paths paths
    :seon.contracts-compile-test/candidate
    (seon.db/carried-projection
     (seon.db/db (seon.cluster.boot/connection "default")))}))
```

Production source/test delta: **0 / 0 lines**. No behavioral tests or provider runs started, no adoption attempted, and no runtime-green claim. Disposable archive inputs can be removed after this evidence is committed; the input commits and reproducible commands above remain the durable record.
