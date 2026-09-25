---
type: audit
status: static audit; proposed retirements, no runtime proof
created: 2026-09-25
source-pin: 056ef6fe0
scope: all tracked source under src/ and script/
---

# Duplicate paths: one REPL and two rendering consumers

Seon already has the necessary owners: the reader, SCI evaluator, turn settlement, declared render selection, print nodes, blob storage, and test request. The principal composition is to carry values and their evidence through those owners once, then emit AI text or HTML from the same selected tree.

**The strongest cuts are the floor/emitter, MCP reconstruction, supplied-default/schema mirrors, and the extra candidate test entrance.** The measured ledger below contains **1,628 existing lines in direct retirement/replacement candidates**, and **1,434 conditional lines**. With the explicitly proposed source addition ceilings of 455 and 320, respectively, they would remove **2,287 net source/script lines**. This is a budget proposal, not an implemented saving. It covers only measured forms, excludes caller edits and schema/test changes, and must not be added to the overlapping historical estimates in the plans.

## 1. Findings that must constrain the plans

1. **MCP is still a parallel REPL.** SCI uses the real evaluator but omits ordinary turn settlement; the bridge's own tool description says so. JVM mode reads and evaluates separately. The bridge parses submitted source before either server-side reader reads it again. A shared evaluator call alone does not satisfy read → eval → record → print.
2. **“Printing twice” is literal.** The floor builds nodes, recursively redispatches renderers, fits/re-emits them, and emits both faces. HTML preparation skips the AI fit. MCP subsequently converts nodes back to semantic data and the bridge prints that data again. Error result preparation calls the floor twice. Browser fallback reparses saved text and pretty-prints it.
3. **There are two path interpreters with different semantics.** `render.data/at` distinguishes absent from present nil; `print/value-at` collapses them. A shared cursor cannot simply alias the latter and preserve the former's refusal contract.
4. **The plans disagree about durable values.** README §7 and B1 §3b R5 say to delete `get_value` and MCP blobs; render design §3f keeps `get_value` and adds artifact digests to evaluations. These are competing storage contracts. The newer owner instruction establishes one shared path, but does not by itself select a retention policy. Neither plan may book the other's incompatible savings.
5. **Test reports discard native operands before recording.** `runner/failure-report` stores rendered expected/actual text; `failure-message` renders them again for a second string. These are not merely two transports of one stored value. Schema migration and native evidence are prerequisites to deleting that path.
6. **The 85k figure is source only.** At the pinned revision: **104 `src/` files / 85,040 lines**, **17 `script/` files / 6,416 lines**, **121 files / 91,456 lines total**. Script test files are included in that denominator because they are in the requested tree. No test was executed.
7. **Do not count everything named “result,” “context,” or “runtime-eval” as duplication.** Effect receipts record side effects; tests record assertions and exercised definitions; shown text records an observation; live result objects preserve identity. The browser's runtime-eval channel announces invalidation; it does not evaluate source.

The requested audit is the only edited file. No production code, plan, skill, test, JVM, or `bin/seon` was changed or run. Static evidence cannot establish loaded behavior, performance, memory use, adoption, or arming. Those measurements are **unavailable**, not passing. Concurrent commits were arriving; all measured ranges below use **`056ef6fe0`**, rather than a moving working-tree line number. Initial inspection started at `fed60b7b9`.

## 2. Method and counting

The complete `rg --files src script` population was scanned for definitions and presentation, reading, evaluation, recording, error, configuration, and projection operations. High-risk implementations were read with their first-party callers. Caller searches included source, scripts, schema declarations, tests and launchers: a function registered in a schema is a caller-reachable renderer even without a literal Clojure invocation. The full-tree inventory in §12 exposes the breadth of that scan; it is not a claim that every function received a semantic proof.

Counts are **physical lines from the opening of the named definition through its matching closing delimiter**, including its docstring, contract, comments and internal blank lines, excluding blank lines between forms. Each range can be checked with `git show PIN:path | sed -n 'START,ENDp' | wc -l`. Whole-file counts use newline counts, as `wc -l` does. No “about 70 lines of a 120-line function” is booked. Mixed functions are inventoried at full size and given **zero deletion credit** until their exact replacement is scoped. Ledger groups do not overlap.

“Delete/replace” counts the old implementation being removed. Some public names must remain as thinner delegates; their replacement lines must fit the addition budget. Addition ceilings are **audit proposals**, not measured code. Caller, declaration and regression costs must be charged separately before implementation; a high gross-delete/add ratio does not waive the 100-line rule.

Authorities consulted: `AGENTS.md`; [render design](one-render-path-design-2026-09-25.md) §§1–6, particularly shell-order revision `0b83d61a5`; [prior art](value-printer-prior-art-2026-09-25.md); [clipping inventory](clipping-sites-2026-09-25.md); [one harness](one-test-harness/design-2026-09-24.md); [platform plan](../../prds/agent-platform/plan/README.md) and its open lane specifications. Historical source ranges in those documents were not treated as current measurements.

Dependency seams checked locally:

| Dependency / pin | Existing seam | What it supplies; when/cost |
|---|---|---|
| fipp `255a19061fdc20d67eca09a17e9af282aa8bf909` | `reference-code/fipp/src/fipp/engine.cljc:12` serialize, `:68` annotate-rights, `:92` annotate-begins, `:164` format-nodes | Layout of already selected document data per emission. It does not choose Seon's cuts or authorize evaluating arbitrary values. Keep selection upstream; do not adopt the EDN visitor's unknown-object fallback. |
| Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6` | `core.cljc:2606` children, `:2637` validator, `:2653` explainer, `:2782` entries, `:2837` deref-all; `registry.cljc:97` schema | Read the retained compiled contract; derive plans on contract/supplier changes, not eight mirror queries per call. Preserve a reference's declared name before dereferencing it. |
| Existing Seon dependency boundaries | `db/carried-projection`, `sci.kernel/with-arm`, `blob/get`, `blob/put!`, `cluster.agent/acquire-context!` | Reuse the current owners. This audit proposes no replacement evaluator, cache, executor, storage engine or branch constructor. Runtime costs of changed compositions remain to be measured. |

## 3. Rendering and printing

### 3.1 The shared floor is surrounded by additional walkers

| Implementations (current function size) | Consumer/caller evidence | One composition / deletion account |
|---|---|---|
| `render/value.clj:47-131`: datum 17, bounded-text 24, causes 8, link-lines 19, floor 13; constants 7 | Boot diagnostics `cluster/boot.clj:289,328`; panic `flow.clj:229-230`, `cluster/store.clj:616-617`; test runner `:111` | Projection-free access must survive, but it must use the same safe selection/emission. Replace these old bodies in **R1**; do not leave an “emergency” second walker. |
| `render/value.clj:446-585`: value-node* 121, value-node 18 | `prepare :630`; selected producer called through project-node | Keep `prepare` as the entrance. Shell-order allocation owns B and dispatches a reached renderer once. **R1**. |
| `print.cljc:1054-1299`: preserve-requery, fit-entry, fit-children, projected-text, fit-text, structural-elision, fit-node, fit: **237** definition lines | `value/prepare :634`; fit-entry itself reads printed key text at `:1080` | Delete the second traversal/search; retained leaves/cuts carry their coordinates. **R1**, plus the 47-line fit-only constructor helpers. |
| `print.cljc:18-338,496-912`: Sink, three sinks, tables, emit multimethods, node/text/Hiccup/both emitters | `prepare :643`; direct users include grader `eval/drive.clj:135` and prompt `cluster/prompt.clj:280` | fipp document layout plus HTML emission from one selected tree. Keep public emitter adapters where callers need them. **R1** lists each measured form. |
| `render.clj:1062-1205`: project-entry 9, project-children 7, bounded-error-node 11, project-node* 104, project-node 9: **140** | `value.clj:26-27` delayed resolution; recursive calls in project-node* | Fold reached producer selection into the allocator; keep declared-producer / selection / invoke-selected. **R2**. Prevent repeated producer invocation without another normalization pass. |
| `render.clj:588-609` selection 22; `:986-1025` invoke-selected 40; `:1217-1277` raw-output 61; AI 16 / HTML 17 | `render-call :1390`, declared pairs, web/walk | Keep the guarded selected invocation. Raw-output still enforces final target shapes; migration to “smaller data or final text” belongs here. **Zero additional credit** for those mixed functions. |
| `value/prepare :611-658` 48 | AI, HTML, SCI shown-result, errors, MCP, web /data | Today only AI gets fit; HTML is separately traversed. One prepared selection must supply both faces, with new preparation only for a requested cut. The 48-line orchestration is **kept/refactored**, not counted again. |

**Important sizing correction:** the floor/layout retirement is **1,099 measured old definition lines**, rather than importing the design's approximate 1,150 physical-span estimate. It includes the old window functions; retrieval must not claim those again. It excludes unrelated generators, `compare-values`, schema declarations, caller conversions and the HTML side-by-side work. R1's +310 ceiling comes from the design's broad fold; it is not proof that hostile-value classification, layout, and retrieval all fit. Keep its staged proof and explicit over-100-line review boundary.

### 3.2 Other consumers and early serialization

| Surface / implementation | Actual use | Disposition |
|---|---|---|
| MCP bridge `elision-value :371` 10, enrich-sequential-tail 12, enrich-collection-tail-elisions 18, enrich-projection-elisions 17, printed-value-event 8 | `execute-clj-eval :734` maps these over returned events | **R3 = 65**. Emit prepared text plus native cut coordinates from the JVM; JSON/PREPL adapters only encode the envelope. The bridge must not rediscover a tail from an admission sentinel. |
| `repl/pretty-response :457-463` 7 and `live-response :465-480` 16 | `repl/render-html :482` uses live result or reparsed shown string | **R5 counts only pretty-response's 7.** Live object lookup is legitimate retention, but must use the shared selection, not new independent HTML decisions. Historical bytes stay exact; unavailable objects need a visible retrieval refusal. |
| `eval.drive/read-result :125-137` 13 | `run-receipts :167` grades the result by read → emit-text → read | **R5 counts 13.** Read the native/admitted artifact or evaluation value through its owner. The current emitter's default length can change the grader's value. Grading must never infer data from a clipped display. |
| `repl/source-text :91-97` 7; syntax-tokens 43; render-emission-html 24; response 22; text 18 | Generated forms, prompt grammar, HTML syntax spans | Keep source formatting and envelope grammar. Source code formatting is not value selection; two final faces are lawful. Do not bill these as a second evaluator or automatically replace code-dispatch with the value floor. |
| `cluster/ bounded-face :633-645` 13, bounded-summary 11; operator top 8, short-message 8, cluster-summary 34, status-summary 10; two constants 4 | runtime observation → terminal status, `operator/-main` | **R4 = 88**. One runtime observation renderer selects counts/identities; terminal and MCP consume its output. Preserve complete observation and a retrieval route, including unavailable observations. |
| `problems/ai-prose :587-633` 47 | Declared problems pair; nested `render-ai` and profile/default fallback | Return its smaller family map to current preparation. No nested B. **0 whole-function credit**; the query/domain selection survives. |
| `render/ns.clj`: render-data 39; render-ai 32; render-html 9; omission helpers 21 | Namespace page; schema reference cap 40; AI distance alternatives | Keep domain views and definition queries. **R8 = 21** unreachable omission helper lines, after removing their guards/calls. Changing reference selection to names is a domain conversion, not deletion of every namespace renderer. |
| `render/web.clj:616-650` reverse connection presentation | `take width` after sources acquired | Remove that extra display cut; reuse the existing query-page continuation. Mixed rendering owner: **0 whole-function credit**. |
| Bootstrap candidate spending `bootstrap.clj:371-395,518-560` | Token-priced opening subjects | Return selected identities/read forms through declared rendering. Subject membership/query bounds remain. **0 whole-function credit** pending exact cut. |
| `ai/tokens.cljc`: calibrate 44, recent-calibration 11, estimate-of-characters 16, estimate 13, estimate-chars 11, budget-report 40 | Provider admission, floor and display estimates | One estimator already exists. Keep it; convert tokens to floor characters once. The duplicate is repeated spending in fit/bootstrap/callers, not multiple estimator libraries. |
| `cluster/prompt.clj`: priced-unit 4, retained-count 20, dropped-elision 18, selection-segments 16, select 33, compose 5 | Prompt admission of whole saved units | Keep whole-unit selection. It needs full-prompt capacity accounting and an explicit oversized-newest outcome. **0 deletion credit** for calling this “another truncator.” |
| `docstring/format-findings :334-358` 25; markdown formatter (inventory §12); changed-test/failure-excerpts 15; citations hints | Hook/terminal reports; max-length/180-char and top-4/6/20/3 cuts | **R9 counts only failure-excerpts = 15**: consume structured test results, not FAIL/ERROR log parsing. Remove formatter substring branches in place; preserve their useful reports. Counts of entire formatters are not deletable counts. |
| `script/seon/dev/harness_costs.clj`, issues/clj-kondo/state/test_roots/dependency_digest/schema_split and operator scripts | Measurements, lint/issue feedback, protocol or artifact serialization | Keep measurements and protocols; send arbitrary displayed data to the shared edge when one is needed. No additional result store is established merely by `pr-str`, `spit`, or a log file. §12 includes their lexical presentation sites. |

Schema-declared per-domain AI/HTML producers in agent, config, background, effect, issue, maintenance, note, plan, message, status, db, error, test, run, oversight and namespace modules are included in §12. **A pair is not two competing selections.** Domain producers choose smaller content and actions; final text/Hiccup differs by consumer. Replacing every custom HTML card with a printer is neither required nor a justified deletion.

### 3.3 Transcript and context are the next large cut, but not 2,484 free lines

`render/transcript.clj` contains several real views and repeated acquisition/adapter work:

| Existing implementation | Measured lines | Consumer |
|---|---:|---|
| history :404-432 | 29 | candidate-history :668-737 (70), projection :739-753 (15), AI/HTML history |
| emission :485-522; evaluation-text :524-532 | 38 + 9 | transcript entries → repl/text; already delegates grammar |
| agent-history-under :945-995; agent-history :998-1011 | 51 + 14 | history pair, runtime/namespace pages |
| render-session :1381-1494 | 114 | web session, ledger-context |
| outline-unit :1574-1612; render-outline :1649-1729 | 39 + 81 | web outline; saved AI bytes vs rendered view |
| acquire-ledger-data :1856-1880; ledger-turn-body :1882-1978; render-ledger :2377-2427 | 25 + 97 + 51 | web card/ledger/default session |
| render-runtime-html :2429-2484 | 56 | runtime entity pair |
| context/selection :76-108; capture-tx :359-402 | 33 + 44 | selected evaluations and exact provider prompt evidence |

Callers are real: `web.clj:3255-3258,3275,3322,3362` routes to these views. `render-ledger-context :1995` calls render-session. The outline and ledger are **not dead**. Session problems, budget analysis, effect links and missing-evidence diagnostics must survive if the duplicated display work is removed.

Keep `render.walk/history`, `seon.eval/of-agent`, `repl/entity-emission`, saved `seon.eval/shown`, and `prompt/compose`. Let transcript views consume those same ordered evaluation units; retain only their layout and domain analysis. B2 should first claim a reviewed set of adapter/acquisition definitions, not the entire file. **No transcript-wide deletion is included in 3,062**, and the outline's 39 lines are not entirely the toggle slated for deletion. This is a larger opportunity requiring a behavior→surviving-function map before spending its line budget.

## 4. Retrieval of values and cuts

| Existing path | Measured implementation / callers | Shared-path ruling |
|---|---|---|
| MCP get_value | cluster/mcp-get-value :555-610, **56**; bridge execute-get-value :876-904, **29**; window :311-355, **45** | Keep protocol adapter if get_value survives. Retrieve digest → read-artifact → artifact-value → data/at → prepare at offset. Delete old window family through **R1 only**. String-window arm is mixed into mcp-get-value: **0 additional credit**. |
| Browser /data | web/data-response :3511-3622 (112 lines); data/parse-cursor 18; data/at 44 | Already shares blob/read-artifact/at. Feed the same prepared fragment/cuts to HTML. Keep server custody, response encoding, missing/unreadable errors and entity-currentness distinction. |
| Generated requery | print/value-at :403-411, **9**; requery-form :444-455, **12**; value/path-url **5**, path-link **5** | Keep public `value-at` and requery-form, but make at the presence-aware traversal authority. Add ordinal steps once, with matching selection order. **0 credit** for removing an API generated into user prompts. |
| Generated entity read | data/pull-at :91-108, **18**; render-form-value emits that symbol | Keep pull → same cursor traversal. A requested attribute remains that attribute, not its containing entity. |
| Artifact wrapper | value/artifact **28**, artifact-edn **5**, read-artifact **5**, artifact-value **5** | One admitted-value artifact shape if durability is chosen. Do not parse arbitrary shown prose as that artifact. |
| Storage | blob/get :371-395 **25**, read-chunk :360-369 **10**, read-staged-chunk :340-358 **19**, put! **9** | Keep storage/chunk APIs; byte I/O bounds are not floor cuts. Digest lookup and current entity lookup have different temporal guarantees. |
| Entity observation | data/outgoing-page **17**, incoming-page **41**, entity-observation **41** | Keep index-page acquisition bounds and snapshot-bound continuation. A cut in already acquired values cannot replace a bound on database work. |

A single request should choose **source identity** (live handle, immutable digest, or current entity), then **path/offset**, then invoke the same preparation. It must preserve present nil, missing path, beyond-end, unknown total, swept blob and unavailable live object outcomes. The current parse-cursor silently replaces malformed paths/offsets with root/zero (`:27-44`); the new design's coordinate refusal must replace that behavior explicitly, not claim it already refuses.

## 5. Evaluation entrances: count the bypasses, not every reader

| Entrance | Read / evaluate / record / print today | Lines and retained owners |
|---|---|---|
| Product reply | turn/read-source → reader events → turn/evaluate-sources → evaluate-for-install → evaluate; turn terminal settlement; shown-result | read-source **7**; evaluate-sources **131**; evaluate-for-install **25**; evaluate **480**; shown-result **46**. One core SCI evaluator, surrounded by policy and settlement. |
| Generated/system reads | system-turn **176** → evaluate-sources; record-evaluated-tx **58**, record-evaluated-call **72** | A second settlement entry with idempotent already-evaluated recording; it must converge on evaluation-facts, not be deleted before cached read replay/idempotence survives. |
| Submitted/resumed turn | cluster.agent/submit-source! → resume-turn **166** → evaluate-sources | Keep the same ordinary turn entry for outside agents; completion/custody remain agent-owned. |
| MCP SCI | bridge sci-evaluation-form **45** → sci.eval/evaluate; **no ordinary run/receipt settlement** | Replace non-settling arm with ordinary submit-source! and terminal evidence. **Do not count all 45 as free deletion**; a transport request remains. |
| MCP JVM | require-single-clj-form! **44**, jvm-evaluation-form **14** → core/read-string + core/eval inside PREPL; mcp-valf/mcp-project | A separate host evaluator. Keep diagnostic host access where necessary, but attach the shared result/error/printing boundary. **R7 = 80** duplicated lexical pre-reader/helper lines, once the one JVM owner returns reader-position refusals. |
| Ordinary test body | test/run → member-result **120** → bounded-result **104** → sci.eval/run-test / run-tests → runner/run-var! **37** / run-vars! **41** | It shares custody/SCI arm but directly invokes the captured test body, records through commit-results!, and does not go through source evaluate/shown-result. Adapt the shared execution owner to an already-read/body input; do not stringify/reread the test function. |
| Pre-install candidate test | run-candidate-test! reads test source and calls evaluate, resolves its Var, then run-test; evaluate-candidate loops gate set | **C1 = 87** (three functions in ledger). Replace with one seon.test/run request on the actual candidate and record its full outcome; preserve seed/auto-check and source location guarantees. |
| Operator PREPL | script/operator/prepl transport writes forms and reads events; cluster/mcp-io-prepl delegates to clojure.core.server/prepl | Host transport is distinct from source parsing and settlement. Keep framing, request identity, deadlines and reconnection. No second user-source reader belongs in the client. |
| Web runtime-eval | web/render-step :2607-2745, input handling :2697-2732 | **Not an evaluator.** It invalidates derived render state on a code-change notification. Debug renderer experiments invoke a renderer through SCI kernel; they are not a second source REPL. |
| eval/drive | run-episode! **61**, run-sample! **54** → inbound! **33** → ordinary agents | Episode orchestration, not a second SCI interpreter. Only read-result's print→read path is booked in R5. |
| Program installation | evaluate-native! **5**, installers and schema-expression evaluation | Internal installation of already-read declarations. Audit fidelity/contract ownership, but do not label each sci/eval-form occurrence another public REPL. |

There are **two user-source interpreter backends** (SCI and host JVM), **three submission policies** (product/settled turn, non-settling MCP SCI, host PREPL), and **two test-entry compositions** (ordinary request and candidate gate). These are more useful counts than counting occurrences of `eval`. Multiple entrances and two execution backends can compose one read/eval/record/print contract.

Keep `sci.reader/read`, `sci.eval/one-event` / evaluate's supplied event, `sci.kernel/with-arm`, `db/call-with-custody`, `turn/evaluation-facts`, and ordinary turn submission/settlement. Move final preparation to the shared terminal boundary while carrying native result + provenance. Record the exact shown bytes as the terminal observation once. A test-body adapter supplies a callable and assertion recorder to the same ownership boundary; it must not introduce a new result registry or pretend clojure.test reports are source evaluations.

The 480-line evaluate body is a shrink target, **not 480 deletable lines**: it owns admission, interruptions, declarations, namespace transition, captured output, result identity and failure handling. Neither the generic REPL nor the harness budget may book its wholesale removal.

## 6. Error shaping

Errors follow the same value path. These separate normalization/formatting stages need consolidation:

| Owner / function sizes | What it currently does | Account |
|---|---|---|
| error.refusal/chain **15**, root-frame **9**, refusal **27**, diagnostic **31** | Native cause/diagnostic owner | Keep and harden here. chain uses Throwable->map and unbounded cause iteration; new safe Throwable traversal in the floor should be shared with this owner, not a second full chain implementation. |
| value/causes **8**, link-lines **19**, floor **13**, datum **17**, bounded-text **24** | Separate capped Throwable/data text walker | **R1**, already counted. Projection-free failure handling must remain possible. |
| sci.kernel/failure-value :526-572 **47**; sci.reader/reader-error and error-event; sci.eval/evaluate's failure-result | Eval/reader error envelopes, triage/source location and full cause context | Keep producer-specific error facts, send them through the common value renderer. **0 whole-function deletion**; preserving error alternatives and interruption facts is substantive. |
| cluster/nil-deref? **5**, first-seon-frame **7**, exception-summary **19**, mcp-projection-error **19** | Reduces JVM exception to selected class/frame/message; projection failure retains only a failure message | **R6 = 50**. Native diagnostic/chain plus common floor must replace these branches. `mcp-project` further admits only the summary message at `:439-442`; remove that lossy selection in its owning rewrite. |
| repl/error-text **19** | Reads saved triage and uses main/ex-str; falls back to stored error text | Keep compatibility until stored errors are native declared values. Preserve historic shown text, source and phase; no credit for erasing diagnostic context. |
| error/classifying-error-data **23**, bounded-admission **24**, bounded-error-admission **14**, bounded-text **18**, fit-fact-payload **40** | Projects error evidence and repeatedly halves inline fields; full evidence may live in blob | **Storage**, not free presentation deletion. New ordinary-value law exposes a follow-up: structured native evidence + one durable reference can retire inline string reshaping, but storage/schema/identity changes need their own design. **0 credit here.** |
| error/prepare-result :473-505 **33** | Prints an offending value once through HTML preparation for “complete” text and again for AI; creates separate result id and blob | **C8 = 33**, only after a shared value retention policy. Full text is not a native artifact. Preserve access to the offending value and shown observation through the common result owner. |
| runner/report-value **26**, message-text **13**, failure-message **11** | Throwable-specific rendering; message budget in characters vs token-budget; repeated expected/actual rendering | **R10 = 50**. Capture native operands, render only at the result boundary. failure-report's 25 lines and report-identity's 19 require schema/identity conversion and are **not** extra deletion credit. |
| test/world-refusal :1643-1652 **10**; runner/bounded-drift **11**; reach-digests **30** | Offender cut to 400 before ex-data; sets cut to 10; “first 10” message | Preserve native offending value/drift sets; floor renders them. bounded-drift belongs to **C2** only; other mixed forms get **0**. |
| Boot/Flow/store/test panic callers | Calls to floor, sometimes two failures | Keep separate original and recording failures as data/cause evidence. One output per distinct fault is not duplicate storage. Do not remove loud emergency output to make the path count look smaller. |

The two `bounded-text` functions have different contracts: error's is durable storage admission, value's is display. Unifying the spelling alone would corrupt one contract. Similarly, keep `fault/record!` (128 lines), delivery and the failure of recording visible; they are not optional formatter scaffolding.

## 7. Result recording and retention

| Record / implementation | Consumer / distinct guarantee | Shared-path change |
|---|---|---|
| Live result objects, evaluator result handle | Later evaluation of result/e… preserves original object identity | Keep process-local map owned by the SCI context. A blob cannot replace an atom, closure or live object. |
| `turn/evaluation-facts :102-198` **97**; receipt-settle-call **78**; record-evaluated-call **72** / tx **58** | Durable evaluation/source/namespace/read evidence, shown text, terminal outcome | One fact constructor already serves settlement. Retain it; make all REPL entrants use it through the existing writer policy. Different idempotence/custody entry conditions are not wholesale duplicates. |
| `sci.eval/shown-result` **46** | Chooses what was shown and returns it in evaluation envelope | Retain result/provenance logic; prepare once at terminal presentation, and record its exact bytes. Not a second result store by itself. |
| `cluster/mcp-project :388-494` **107** | Re-admits and serializes oversized results into blob; no evaluation fact; exports projected semantic data | Replace MCP-specific result policy with ordinary settlement/retention. The function includes config/refusal/projection behaviors: **not 107 free lines**, and its error helpers are counted only R6. |
| `value/artifact*` **43** total | Native admitted value/marker envelope for retrieval | One artifact representation if retained. Keep distinct from human-readable “complete” error text. |
| `error/prepare-result` **33** | Separate error offending-value result id, shown text, text blob | Conditional C8. Link the offending value through common retention instead. Error identity/occurrences still belong to error owner. |
| `runner/record-latest-tx` **160**, record-tx **17**, commit-results! **37**, prepare-failures! **71** | Assertion outcomes, dependency/input evidence, completion, run/member identity and blobs | Keep durable test authority. Generic evaluation success does not prove assertions ran or required tests passed. Share value encoding/printing, not the domain schema. |
| `effect/request*`, turn receipt start/settle owners | Intent, handler, background work, terminal effect result | Keep effect receipts where required for recovery and provenance. Do not replace them with shown text. |
| `context/capture-tx` **44**, contribution-row **22**; turn/record-attempt! **168** | Exact provider prompt/contributions vs provider attempt, billed usage/reasoning/outcome | These describe different events. Preserve exact prompt evidence and link evaluations; no automatic credit for duplicating a string whose observation is intentional. |
| `cluster/source/record-results-at-head!` **70**, record-results! **11** | Carries/reconciles test evidence to publication authority | Calls existing test recording; retain lineage/evidence semantics. Simplify only with accepted evidence remaining queryable after branch unlink. |

The one REPL law eliminates **MCP-only result policy** and **error-only printing/retention**, not evaluation facts, effect receipts, test evidence, and exact prompt captures as distinct concepts. A single shared storage implementation must still store these different facts.

## 8. Other parallel buildouts

### 8.1 Supplied defaults, schema shapes and projection transport

`call_preparation.clj` is **1,556 lines**. It models contract arity/slots as database shape rows, queries them, maintains supplier snapshots and plan caches, then prepares a call. `instrument` and `sci.eval` already hold compiled contracts. Callers verified by `rg`: `instrument.clj:247`, `sci/eval.clj:249,1805`, `sci/kernel.clj:632`, `error.clj:940`, `db.clj:66,69`.

**C3 = 329 measured definition lines** for the mirror-query/plan implementations listed in §11. Keep `supply`, `prepare`, `hook` and snapshot ownership until all semantics move to the retained-contract seam. Use `mr/schema`, Malli arities/children/entries, and declared-name matching. Do not claim all 1,556 lines; supplier selection, ambiguity refusal, caller-wins, partial insertion and variadics are still work.

`fn/schema_shape.clj` is **467 lines**, a second schema codec/fingerprint/row representation. **C4 = 385 definition lines**. Keep `typed-key-facts` (19) and its `key-kind` dependency (21), and repoint their owner if the file retires. `program.cljc:791,988,1001,1036-1047,1155`, `fn.clj:2902,2940,3156`, and `instrument.clj:605,616` are real callers. This is not dead code; A1-13 must convert row producers and consumers atomically. Schema attributes/tests are outside the measured subtotal.

Projection construction is already partly composed: `projection-from-rows` (221) parses/validates rows and calls `build-projection` (207); `load-projection` (24) acquires rows; `projection-from-database` (12) reads carriage. Incremental `projection-with-declarations` (239) reuses compiled state. **Do not add 221+207+239 as duplicate deletions**: row decoding, cold compilation and incremental invalidation are distinct. The full row loader may still shrink, but requires proof of what moves.

The second **transport**, however, is real: ambient forms/projection/registration state beside explicit carried projection. **C5 = 224** old definition lines; list in §11. Existing pure `projection-with-schema`, `projection-without-schema`, `projection-with-function-contract`, plus `db/carried-projection` are the surviving path. Convert every reader and evaluated schema-expression registration together. This is the projection sweep's budget, not an additional A1 budget.

**C6 = 45**: `projection-pure-data` and `materialize-projection` have no production caller found outside their own definitions/self-call. Tests do use them. Treat as a **test-only API retirement candidate**, not proven dead globally; convert those tests to the real constructor if serialization is not a product requirement.

### 8.2 Read currency

`db/read-evidence-current?` still has three decisions: compare revisions, inspect history patterns, replay read and compare a retained value/digest. Revision equality now goes first; A2's dated statement that the historical scan runs first is stale at this pin.

**C7 = 139** old definition lines: stable-read-result, read-result-digest, replay-read, changed-read-attributes, index-evidence-current. Keep `dependency-revision`, read evidence and `read-evidence-changes`/index-pattern-change where diagnostics need changed datoms. Let changed reads rerun once through the ordinary evaluation owner.

Condition: native broad/speculative/history semantics must remain conservative. Removing finer currency proofs may cause more rerenders or reevaluations; arbitrary effects must never enter replay. This deletion is not a measured speedup, and read-result retention used by debug inspection must survive independently of replay-for-validity.

### 8.3 Harness worlds and ambient restoration

The current harness already calls agent acquisition/release, but `test/clean-request-handle` (92), program-overlay-tx (81), prepare-checkpoint! (26), open-world! (15) and member-result (120) still assemble preparation around that owner. The one-harness design correctly assigns reusable checkpoint/world semantics to agent acquisition. **Moving 214 lines to another file saves zero lines.** No whole-body credit is booked for these orchestration functions.

**C2 = 192** ambient snapshot/restore/drift definition lines. Keep platform diagnosis until confinement and projection ownership are proven; the ordinary path cannot remove evidence of shared Var damage merely because its branch is isolated. `restore-live-cluster-schema!` is live: `test.clj` member cleanup and `test_support.clj:848` call it. The 11-line bounded-drift presentation helper is counted here once.

### 8.4 Configuration, publication, execution and profiling: avoid false savings

- `config/compile-settings` 52 → compile-manifest 28 → apply-compiled! 69 → reconcile-call 10 → `reconcile/plan` and checked writer is an existing composition. `config/apply!` (26), operator config action, boot facts and test seed application are callers, not independent configuration engines. Remove a concrete fallback only after every acquisition supplies the proper projection. **0 booked.**
- File indexing and interpreted declaration capture converge on program facts but still have different source acquisition. `source/publish!` (169), `fn/index!`, SCI installers and export must share canonical analysis/digest/admission. Manifest/seal/snapshot remnants have active callers; B1's historical −600/−250/−200 cannot be counted again without a current function map. **0 booked.**
- `source/accept-merge!` (49) must use `seon.test/select` (347) for current obligations rather than a subset gate. This is writer-side acceptance policy, not a reason to replace test selection. **0 whole-function credit.**
- Flow launcher and SCI arm solve admission vs interpreted interruption/actual exit. The flow spec expressly keeps the launcher until B2 proves equivalent bounded admission. A generic REPL is not that proof. **0 launcher credit.**
- Wrapper profiling, SCI evaluation timing and test request timing measure different extents. Keep inclusive per-definition observations and request clocks; do not collapse their numbers into an invented “one duration.” C1's persistence/query design is a feature, not funded by deleting ordinary rendering.
- `my.*` wrappers often differ only by custody and declared contracts. Thin adapters are not parallel platforms. Count only duplicates that repeat an owner's decisions.

## 9. Dead-code and caller findings

The caller search was static; dynamic/private external invocation remains possible.

| Candidate | Caller verification | Verdict |
|---|---|---|
| ns/omission-value :414-424 **11**, omission-text :439-448 **10** | `rg -n 'omission-value|omission-text|included-count|compact-ai-text|html-view' src script test resources/seon/schemas`; compact-ai-text called with count(items) at :584; render-html passes count(functions) at :836; html-view forwards count | **Unreachable omission behavior**, not literally no references. Delete helper calls/branches and unused included-count parameters together. R8 credits helpers only. |
| schema/projection-pure-data **5**, materialize-projection **40** | `rg -n 'projection-pure-data|materialize-projection' src script test resources/seon/schemas`; tests in schema_test and schema/datahike_test; no production external caller | **Test-only candidate**, C6 conditional. Not “zero callers.” |
| transcript/render-session, render-outline, render-ledger | Web callers :3255-3258,3275,3322,3362; schema/runtime consumers | **Live.** Consolidation needs UI behavior preservation; not dead-code removal. |
| data/pull-at, print/value-at | Generated forms in render-form-value / requery-form; tests | **Live indirect callers.** Literal call-only grep would incorrectly delete them. |
| render-database-identity-ai | `resources/seon/schemas/seon.db.edn:198` | **Live schema registration.** |
| restore-live-cluster-schema! | Member cleanup plus test support/tests | **Live safety mechanism**, C2 gated. |
| print/emit-both, window, project-node | prepare, mcp-get-value and delayed requiring-resolve respectively | **Live duplicates**, removable only with caller conversion. |
| B4 historical test/fast, bounds, selection files; seon/search | Absent from complete file census at pin | **Already gone. Zero new deletion credit.** Their historical line totals must not finance current work. |

No additional function is asserted dead solely from name similarity or absence in the source-only call graph.

## 10. What each design/plan should claim

Only this audit was edited. These are specific corrections for the owning plans, not a parallel implementation schedule.

| Existing design / open lane | Exact claim to carry forward |
|---|---|
| One-render-path §§3/6; shell-order `0b83d61a5` | **R1 1,099**, **R2 140**, **R3 65**, **R4 88**, R5's pretty-response **7**, **R6 50**, **R8 21**, **R9 15**, **R10 50**. Total **1,535** old definition lines allocated to rendering. Keep source/test/schema additions separate. Offset window and error floor are already in R1. Side-by-side UI/durable artifacts have no blanket transcript deletion credit. |
| Prior-art §3 | Replace its older ~480-line saving with a link to the chosen R1 implementation scope. Its allocator-only +100 does not price Throwable, coordinate, layout, HTML or durable artifact work. It is evidence, not another budget. |
| Clipping-sites inventory | Supersede the fixed structural-limit recommendation with current one-budget selection. Preserve acquisition/admission bounds. Replace approximate site totals with ledger definitions; mixed-function line savings remain unpriced. |
| B1 §3b R3–R5 / D1 O1b, ordinary outside REPL | **R7 80** bridge-reader lines; R5's grader **13** belongs to the common value path but outside render UI. Non-settling SCI arm must go through submit-source!, with branch/run identity and terminal reconciliation. Do not claim projection/get_value removal while render §3f still depends on it. MCP R3/R6 savings are assigned to rendering once, not again here. |
| One-test-harness S13 / D1 O2b | **C1 87** candidate test entrance lines. seon.test/run is the retained selector/recorder. Candidate fixture/input/source-line and ordinary evaluation settlement proofs precede retirement. |
| One-test-harness S8 / B4 | **C2 192**, gated on confinement, validity and projection sweep. S5/S6 test-support/caller savings are outside this source/script census and require their own measured ledger. Stop reusing already-deleted worker/process-file estimates. |
| A1-6, A1-13 | **C3 329 + C4 385 = 714**. Retained-contract planning funds mirror removal; typed-key-facts/key-kind stay. Row schemas and producer/consumer conversions land atomically. Do not count a 1,556-line call-preparation deletion as established. |
| Projection-as-a-read sweep / A1-12 | **C5 224**, plus **C6 45** only if the test-only serialization contract is retired. Ambient transport retirement is one cut, not separate A1/B2/B4 credits. Row-loader shrink is not yet priced. |
| A2 | **C7 139** read-currency alternatives. Retain diagnostic changed-datom reads and query bounds; state the potential extra reevaluations. Codec removal still needs its independent native-value/history guarantee. |
| B3 errors/tasks/dials | **C8 33** double-rendered error result, conditional on common retention. Error storage admission is not deleted on the rendering budget. Retire individual presentation dials with the last reader, not execution/storage limits by name. Task/config features need their own cuts. |
| B2 turn/history/context | Reuse all R/C allocations above. The session/outline/ledger acquisition map in §3.3 defines the next audit slice; **no 2,484-line transcript claim**, no whole 480-line evaluate claim, no whole launcher claim. |
| C1 profiling | No duplicate printer/evaluator deletion assigned. Reuse the one terminal renderer for reports and existing wrapper observations. Feature additions need a separate justified budget. |
| D1 merge/export; namespace-agents-first-loop | Reuse test/run, agent acquisition, source publication and writer-side acceptance. Preserve conflict, tested-head and retained-evidence semantics. No new tested-results store or outside-agent context builder. |
| B1b operator/boot; realities lifecycle | Keep boot/recovery and process identity. Status simplification is R4 once. Branch acquisition/release is already the composition; don't finance new lifecycle code with moved test code. |
| Flow-owns-running-machinery | Preserve launcher admission, actual-exit and error delivery guarantees. Route entrants to the existing owner; no line credit for replacing it with an unbounded executor queue. |
| Platform README | Replace aggregate historical “remaining savings” with the nonoverlapping groups here, and reconcile the explicit get_value/blob contradiction before assigning implementation. |

## 11. Ranked cuts and exact retirement ledger

Ranked by **measured old lines / proposed source addition ceiling**, not claimed implementation ratios. “C” groups are conditional on the proofs above; their rank does not move them ahead of dependencies. “R” still needs caller/schema/regression proof and review. Zero-add R8 is first because it is a narrow unreachable behavior.


| Rank | Group | Old lines | Source addition ceiling | Proposed source net cut | Old/add |
|---|---|---:|---:|---:|---:|
| 1 | R8 — Namespace unreachable omission helpers | 21 | 0 | 21 | no additions |
| 2 | R7 — Bridge's duplicate source reader | 80 | 10 | 70 | 8.00 |
| 3 | C7 — Read currency replay alternative | 139 | 20 | 119 | 6.95 |
| 4 | R3 — MCP elision reconstruction and reprint | 65 | 10 | 55 | 6.50 |
| 5 | C4 — Schema shape mirror codec | 385 | 60 | 325 | 6.42 |
| 6 | R6 — Error-specific MCP reduction | 50 | 10 | 40 | 5.00 |
| 7 | C2 — Ambient test snapshot and restoration | 192 | 40 | 152 | 4.80 |
| 8 | R2 — Nested renderer walk | 140 | 30 | 110 | 4.67 |
| 9 | C6 — Test-only projection serializer/materializer | 45 | 10 | 35 | 4.50 |
| 10 | C5 — Ambient projection transport and registration delta | 224 | 50 | 174 | 4.48 |
| 11 | C3 — Supplied-default mirror query/planning | 329 | 80 | 249 | 4.11 |
| 12 | R1 — Shell-order floor and library layout | 1,099 | 310 | 789 | 3.55 |
| 13 | R4 — Status cuts and second terminal face | 88 | 40 | 48 | 2.20 |
| 14 | C8 — Error-only double rendering and result handle | 33 | 15 | 18 | 2.20 |
| 15 | R5 — Shown-text reparsing | 20 | 10 | 10 | 2.00 |
| 16 | R10 — Test failure text assembly | 50 | 25 | 25 | 2.00 |
| 17 | C1 — Pre-install candidate test execution | 87 | 45 | 42 | 1.93 |
| 18 | R9 — Parse failure logs for hook feedback | 15 | 10 | 5 | 1.50 |

| Scope | Old lines | Source addition ceilings | Proposed net |
|---|---:|---:|---:|
| Direct retirement/replacement candidates | 1,628 | 455 | 1,173 |
| Conditional retirements | 1,434 | 320 | 1,114 |
| Combined, if all prerequisites hold | 3,062 | 775 | 2,287 |

These are gross old-body replacement budgets, not a promise that all 3,062 lines disappear without replacement. The combined 2,287-line figure is source/script net **before** caller, schema and test costs. Conditional groups require the stated semantic proofs. Each range below belongs to exactly one group; file moves earn no deletion credit. No production deletion was made in this audit.

### R1. Shell-order floor and library layout

Measured old definitions: **1099** lines; proposed source addition ceiling: **310**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/print.cljc:18-22` | `Sink` | 5 |
| `src/seon/print.cljc:24-28` | `sink?` | 5 |
| `src/seon/print.cljc:64-66` | `sink-generator` | 3 |
| `src/seon/print.cljc:192-204` | `append-chunk!` | 13 |
| `src/seon/print.cljc:206-212` | `soft-separator` | 7 |
| `src/seon/print.cljc:214-231` | `TextSink` | 18 |
| `src/seon/print.cljc:233-237` | `text-sink` | 5 |
| `src/seon/print.cljc:239-241` | `face-class` | 3 |
| `src/seon/print.cljc:243-245` | `hiccup-token` | 3 |
| `src/seon/print.cljc:247-255` | `append-hiccup!` | 9 |
| `src/seon/print.cljc:257-276` | `close-frame` | 20 |
| `src/seon/print.cljc:278-295` | `HiccupSink` | 18 |
| `src/seon/print.cljc:297-301` | `hiccup-sink` | 5 |
| `src/seon/print.cljc:303-316` | `TeeSink` | 14 |
| `src/seon/print.cljc:318-323` | `tee-sink` | 6 |
| `src/seon/print.cljc:325-338` | `sink-result` | 14 |
| `src/seon/print.cljc:377-380` | `structural-cut?` | 4 |
| `src/seon/print.cljc:382-387` | `visible-items` | 6 |
| `src/seon/print.cljc:389-399` | `node-description` | 11 |
| `src/seon/print.cljc:457-489` | `render-elision-ai` | 33 |
| `src/seon/print.cljc:496-500` | `table-row` | 5 |
| `src/seon/print.cljc:502-526` | `table-data` | 25 |
| `src/seon/print.cljc:528-530` | `pad-left` | 3 |
| `src/seon/print.cljc:532-548` | `table-text` | 17 |
| `src/seon/print.cljc:550-557` | `emit-table` | 8 |
| `src/seon/print.cljc:559-564` | `emit-separated` | 6 |
| `src/seon/print.cljc:566-579` | `emit-sequential` | 14 |
| `src/seon/print.cljc:581-591` | `same-key-namespace` | 11 |
| `src/seon/print.cljc:593-599` | `unqualified-key-node` | 7 |
| `src/seon/print.cljc:601-611` | `emit-entry` | 11 |
| `src/seon/print.cljc:613-648` | `emit-map-like` | 36 |
| `src/seon/print.cljc:650-652` | `emit` | 3 |
| `src/seon/print.cljc:654-656` | `emit` | 3 |
| `src/seon/print.cljc:671-673` | `emit` | 3 |
| `src/seon/print.cljc:675-677` | `emit` | 3 |
| `src/seon/print.cljc:679-692` | `emit` | 14 |
| `src/seon/print.cljc:694-697` | `emit` | 4 |
| `src/seon/print.cljc:699-703` | `emit` | 5 |
| `src/seon/print.cljc:705-707` | `emit` | 3 |
| `src/seon/print.cljc:709-711` | `emit` | 3 |
| `src/seon/print.cljc:713-715` | `emit` | 3 |
| `src/seon/print.cljc:717-727` | `object-class-text` | 11 |
| `src/seon/print.cljc:729-743` | `emit` | 15 |
| `src/seon/print.cljc:745-747` | `emit` | 3 |
| `src/seon/print.cljc:749-753` | `emit` | 5 |
| `src/seon/print.cljc:755-761` | `emit` | 7 |
| `src/seon/print.cljc:763-765` | `emit` | 3 |
| `src/seon/print.cljc:767-771` | `emit` | 5 |
| `src/seon/print.cljc:773-775` | `emit` | 3 |
| `src/seon/print.cljc:777-789` | `emit` | 13 |
| `src/seon/print.cljc:856-879` | `emit-node` | 24 |
| `src/seon/print.cljc:881-889` | `emit-text` | 9 |
| `src/seon/print.cljc:891-899` | `emit-hiccup` | 9 |
| `src/seon/print.cljc:901-912` | `emit-both` | 12 |
| `src/seon/print.cljc:974-980` | `requery-fields` | 7 |
| `src/seon/print.cljc:1013-1052` | `elision-node` | 40 |
| `src/seon/print.cljc:1054-1069` | `preserve-requery` | 16 |
| `src/seon/print.cljc:1073-1084` | `fit-entry` | 12 |
| `src/seon/print.cljc:1086-1137` | `fit-children` | 52 |
| `src/seon/print.cljc:1139-1142` | `projected-text` | 4 |
| `src/seon/print.cljc:1144-1169` | `fit-text` | 26 |
| `src/seon/print.cljc:1171-1187` | `structural-elision` | 17 |
| `src/seon/print.cljc:1189-1232` | `fit-node` | 44 |
| `src/seon/print.cljc:1234-1299` | `fit` | 66 |
| `src/seon/render/value.clj:38-42` | `text-limits` | 5 |
| `src/seon/render/value.clj:44-45` | `text-full` | 2 |
| `src/seon/render/value.clj:47-63` | `datum` | 17 |
| `src/seon/render/value.clj:65-88` | `bounded-text` | 24 |
| `src/seon/render/value.clj:90-97` | `causes` | 8 |
| `src/seon/render/value.clj:99-117` | `link-lines` | 19 |
| `src/seon/render/value.clj:119-131` | `floor` | 13 |
| `src/seon/render/value.clj:297-304` | `stable-entries` | 8 |
| `src/seon/render/value.clj:306-309` | `counted-size` | 4 |
| `src/seon/render/value.clj:311-355` | `window` | 45 |
| `src/seon/render/value.clj:357-366` | `display-value` | 10 |
| `src/seon/render/value.clj:446-566` | `value-node*` | 121 |
| `src/seon/render/value.clj:568-585` | `value-node` | 18 |
| `src/seon/render/value.clj:597-609` | `pager` | 13 |

### R2. Nested renderer walk

Measured old definitions: **140** lines; proposed source addition ceiling: **30**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/render.clj:1065-1073` | `project-entry` | 9 |
| `src/seon/render.clj:1075-1081` | `project-children` | 7 |
| `src/seon/render.clj:1083-1093` | `bounded-error-node` | 11 |
| `src/seon/render.clj:1095-1198` | `project-node*` | 104 |
| `src/seon/render.clj:1200-1208` | `project-node` | 9 |

### R3. MCP elision reconstruction and reprint

Measured old definitions: **65** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `script/seon/dev/mcp.clj:371-380` | `elision-value` | 10 |
| `script/seon/dev/mcp.clj:384-395` | `enrich-sequential-tail` | 12 |
| `script/seon/dev/mcp.clj:397-414` | `enrich-collection-tail-elisions` | 18 |
| `script/seon/dev/mcp.clj:416-432` | `enrich-projection-elisions` | 17 |
| `script/seon/dev/mcp.clj:434-441` | `printed-value-event` | 8 |

### R4. Status cuts and second terminal face

Measured old definitions: **88** lines; proposed source addition ceiling: **40**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `script/seon/operator.clj:1435-1437` | `status-top` | 3 |
| `script/seon/operator.clj:1439-1445` | `top` | 7 |
| `script/seon/operator.clj:1447-1453` | `short-message` | 7 |
| `script/seon/operator.clj:1455-1488` | `cluster-summary` | 34 |
| `script/seon/operator.clj:1490-1499` | `status-summary` | 10 |
| `src/seon/cluster.clj:629-631` | `observation-top` | 3 |
| `src/seon/cluster.clj:633-645` | `bounded-face` | 13 |
| `src/seon/cluster.clj:647-657` | `bounded-summary` | 11 |

### R5. Shown-text reparsing

Measured old definitions: **20** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/eval/drive.clj:125-137` | `read-result` | 13 |
| `src/seon/repl.clj:457-463` | `pretty-response` | 7 |

### R6. Error-specific MCP reduction

Measured old definitions: **50** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/cluster.clj:334-337` | `nil-deref?` | 4 |
| `src/seon/cluster.clj:339-346` | `first-seon-frame` | 8 |
| `src/seon/cluster.clj:348-366` | `exception-summary` | 19 |
| `src/seon/cluster.clj:368-386` | `mcp-projection-error` | 19 |

### R7. Bridge's duplicate source reader

Measured old definitions: **80** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `script/seon/dev/mcp.clj:27-27` | `max-form-preview-chars` | 1 |
| `script/seon/dev/mcp.clj:249-252` | `reader-whitespace?` | 4 |
| `script/seon/dev/mcp.clj:254-259` | `skip-comment!` | 6 |
| `script/seon/dev/mcp.clj:261-279` | `next-form-position!` | 19 |
| `script/seon/dev/mcp.clj:281-286` | `form-preview` | 6 |
| `script/seon/dev/mcp.clj:288-331` | `require-single-clj-form!` | 44 |

### R8. Namespace unreachable omission helpers

Measured old definitions: **21** lines; proposed source addition ceiling: **0**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/render/ns.clj:414-424` | `omission-value` | 11 |
| `src/seon/render/ns.clj:439-448` | `omission-text` | 10 |

### R9. Parse failure logs for hook feedback

Measured old definitions: **15** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `script/seon/dev/changed_test.clj:177-191` | `failure-excerpts` | 15 |

### C1. Pre-install candidate test execution

Measured old definitions: **87** lines; proposed source addition ceiling: **45**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/sci/eval.clj:3968-3976` | `candidate-test-result` | 9 |
| `src/seon/sci/eval.clj:4007-4030` | `run-candidate-test!` | 24 |
| `src/seon/sci/eval.clj:4032-4085` | `evaluate-candidate` | 54 |

### C2. Ambient test snapshot and restoration

Measured old definitions: **192** lines; proposed source addition ceiling: **40**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/test/runner.clj:483-499` | `live-cluster-schema-states` | 17 |
| `src/seon/test/runner.clj:501-504` | `projection-form-keys` | 4 |
| `src/seon/test/runner.clj:506-591` | `restore-live-cluster-schema!` | 86 |
| `src/seon/test/runner.clj:593-602` | `schema-restore-drift` | 10 |
| `src/seon/test/runner.clj:604-645` | `ambient-snapshot` | 42 |
| `src/seon/test/runner.clj:647-657` | `bounded-drift` | 11 |
| `src/seon/test/runner.clj:675-696` | `ambient-drift` | 22 |

### C3. Supplied-default mirror query/planning

Measured old definitions: **329** lines; proposed source addition ceiling: **80**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/call_preparation.clj:328-333` | `by-fingerprint` | 6 |
| `src/seon/call_preparation.clj:359-386` | `named-entry-facts` | 28 |
| `src/seon/call_preparation.clj:723-744` | `contract-transaction` | 22 |
| `src/seon/call_preparation.clj:784-799` | `argument-validators` | 16 |
| `src/seon/call_preparation.clj:864-899` | `supplied-map-entries` | 36 |
| `src/seon/call_preparation.clj:971-987` | `arity-plan-rows` | 17 |
| `src/seon/call_preparation.clj:989-1145` | `plan-for` | 157 |
| `src/seon/call_preparation.clj:1170-1216` | `plan` | 47 |

### C4. Schema shape mirror codec

Measured old definitions: **385** lines; proposed source addition ceiling: **60**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/fn/schema_shape.clj:10-12` | `normalization-revision` | 3 |
| `src/seon/fn/schema_shape.clj:14-17` | `map-properties` | 4 |
| `src/seon/fn/schema_shape.clj:21-27` | `split-schema-form` | 7 |
| `src/seon/fn/schema_shape.clj:29-37` | `canonical-map-entry` | 9 |
| `src/seon/fn/schema_shape.clj:39-57` | `canonical-form` | 19 |
| `src/seon/fn/schema_shape.clj:59-65` | `local-registry-form?` | 7 |
| `src/seon/fn/schema_shape.clj:67-73` | `fingerprint` | 7 |
| `src/seon/fn/schema_shape.clj:75-81` | `authored-form` | 7 |
| `src/seon/fn/schema_shape.clj:83-98` | `compiled-references` | 16 |
| `src/seon/fn/schema_shape.clj:100-113` | `form-fingerprint` | 14 |
| `src/seon/fn/schema_shape.clj:115-145` | `reference-fingerprints` | 31 |
| `src/seon/fn/schema_shape.clj:147-155` | `prepare-forms` | 9 |
| `src/seon/fn/schema_shape.clj:157-169` | `form-projection` | 13 |
| `src/seon/fn/schema_shape.clj:171-196` | `normalized-form` | 26 |
| `src/seon/fn/schema_shape.clj:240-243` | `schema-form?` | 4 |
| `src/seon/fn/schema_shape.clj:245-255` | `child-row` | 11 |
| `src/seon/fn/schema_shape.clj:257-275` | `entry-row` | 19 |
| `src/seon/fn/schema_shape.clj:277-285` | `ordered-rows` | 9 |
| `src/seon/fn/schema_shape.clj:287-301` | `form-parts` | 15 |
| `src/seon/fn/schema_shape.clj:306-351` | `encode-form` | 46 |
| `src/seon/fn/schema_shape.clj:353-367` | `shape-row` | 15 |
| `src/seon/fn/schema_shape.clj:369-391` | `read-form` | 23 |
| `src/seon/fn/schema_shape.clj:393-401` | `row-form` | 9 |
| `src/seon/fn/schema_shape.clj:403-417` | `database-form` | 15 |
| `src/seon/fn/schema_shape.clj:419-430` | `compiled-in` | 12 |
| `src/seon/fn/schema_shape.clj:432-453` | `row-signature` | 22 |
| `src/seon/fn/schema_shape.clj:455-467` | `assert-consistent!` | 13 |

### C5. Ambient projection transport and registration delta

Measured old definitions: **224** lines; proposed source addition ceiling: **50**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema.clj:1233-1242` | `candidate-forms` | 10 |
| `src/seon/schema.clj:1271-1276` | `call-with-forms` | 6 |
| `src/seon/schema.clj:1278-1284` | `call-with-projection` | 7 |
| `src/seon/schema.clj:1286-1293` | `call-with-projection-state` | 8 |
| `src/seon/schema.clj:1295-1296` | `active-projection` | 2 |
| `src/seon/schema.clj:1341-1351` | `update-candidate-forms!` | 11 |
| `src/seon/schema.clj:1755-1820` | `register!` | 66 |
| `src/seon/schema.clj:1822-1839` | `unregister!` | 18 |
| `src/seon/schema.clj:1841-1846` | `contribute-candidate-forms!` | 6 |
| `src/seon/schema.clj:3644-3648` | `current-projection` | 5 |
| `src/seon/schema.clj:3650-3655` | `handed-projection` | 6 |
| `src/seon/schema.clj:3663-3667` | `snapshot` | 5 |
| `src/seon/schema.clj:3671-3688` | `begin-registration-delta` | 18 |
| `src/seon/schema.clj:3698-3715` | `call-with-registration-delta` | 18 |
| `src/seon/schema.clj:3717-3723` | `changed-candidate-keys` | 7 |
| `src/seon/schema.clj:3733-3740` | `registration-delta-form` | 8 |
| `src/seon/schema.clj:3742-3747` | `commit-registration-delta!` | 6 |
| `src/seon/schema.clj:3749-3759` | `restore!` | 11 |
| `src/seon/schema.clj:3765-3770` | `registered-schemas` | 6 |

### C6. Test-only projection serializer/materializer

Measured old definitions: **45** lines; proposed source addition ceiling: **10**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema.clj:2403-2407` | `projection-pure-data` | 5 |
| `src/seon/schema.clj:2462-2501` | `materialize-projection` | 40 |

### C7. Read currency replay alternative

Measured old definitions: **139** lines; proposed source addition ceiling: **20**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/db.clj:568-572` | `stable-read-result` | 5 |
| `src/seon/db.clj:574-585` | `read-result-digest` | 12 |
| `src/seon/db.clj:962-1026` | `replay-read` | 65 |
| `src/seon/db.clj:1074-1089` | `changed-read-attributes` | 16 |
| `src/seon/db.clj:1091-1131` | `index-evidence-current` | 41 |

### R10. Test failure text assembly

Measured old definitions: **50** lines; proposed source addition ceiling: **25**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/test/runner.clj:91-116` | `report-value` | 26 |
| `src/seon/test/runner.clj:118-130` | `message-text` | 13 |
| `src/seon/test/runner.clj:132-142` | `failure-message` | 11 |

### C8. Error-only double rendering and result handle

Measured old definitions: **33** lines; proposed source addition ceiling: **15**. Consumers, retained functions and prerequisite guarantees are described in §§3–10.

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/error.clj:473-505` | `prepare-result` | 33 |

## 12. Whole-tree lexical inventory

All 121 tracked files at the pin are listed, including files with no matched definitions. Entries give definition name, inclusive source range, physical line count and scan flags. **P** = presentation/cuts, **V** = retrieval, **E** = reader/evaluation, **X** = error conversion, **S** = settlement/recording, **W** = world/projection/configuration. Flags identify inspection candidates, not duplicate behavior or deletion credit. A caller may inherit a concern without containing its literal spelling; this scan is not a call graph. Reader-conditional/nested definitions are covered by whole-file scanning but are not always independently indexed. Only §11 constitutes the deletion ledger.

| File | File lines | Matched definitions: name@range [lines; flags] |
|---|---:|---|
| `script/seon/dev/changed_test.clj` | 380 | `analyze-host@130-168` [39; X]; `failure-excerpts@177-191` [15; P]; `format-result@325-380` [56; P] |
| `script/seon/dev/citations.clj` | 164 | `spans@14-19` [6; P]; `parse-cite@21-30` [10; P]; `citations@43-66` [24; P]; `index-blobs@68-83` [16; P]; `verdict@103-120` [18; P]; `check@122-146` [25; P]; `-main@148-162` [15; P] |
| `script/seon/dev/citations_test.clj` | 32 | No lexical candidate |
| `script/seon/dev/clj_kondo.clj` | 96 | `ensure-dependency-cache!@43-96` [54; X] |
| `script/seon/dev/dependency_digest.clj` | 189 | No lexical candidate |
| `script/seon/dev/docstring.clj` | 420 | `format-findings@334-358` [25; P] |
| `script/seon/dev/harness_costs.clj` | 97 | `wrapper-overhead@55-80` [26; PE]; `measure@82-97` [16; PW] |
| `script/seon/dev/issues.clj` | 42 | `run!@28-42` [15; P] |
| `script/seon/dev/markdown.clj` | 1,266 | `parse-frontmatter@115-142` [28; P]; `bare-url-links@180-198` [19; P]; `rule-trailing-whitespace@315-333` [19; P]; `exact-sha-tokens@624-643` [20; P]; `split-on-character@680-686` [7; P]; `dependency-name@688-689` [2; P]; `fix-trailing-whitespace@853-868` [16; P]; `parse-index-record@970-983` [14; P]; `validate-repository-pins@1117-1161` [45; X]; `format-violations@1163-1206` [44; P] |
| `script/seon/dev/markdown_test.clj` | 591 | `authority-errors@37-78` [42; P] |
| `script/seon/dev/mcp.clj` | 1,084 | `max-form-preview-chars@27-27` [1; P]; `window-bases@39-39` [1; P]; `max-window-errors@40-40` [1; P]; `discovery-rows@98-120` [23; PE]; `read-clj-endpoint@145-189` [45; E]; `form-preview@281-286` [6; P]; `require-single-clj-form!@288-331` [44; X]; `collect-prepl-response!@333-356` [24; PE]; `decoded-projection-event@358-369` [12; X]; `enrich-projection-elisions@416-432` [17; E]; `printed-value-event@434-441` [8; P]; `one-shot-events!@443-455` [13; E]; `jvm-evaluation-form@486-499` [14; PE]; `sci-evaluation-form@501-545` [45; PEW]; `branch-form@563-602` [40; P]; `execute-branch@604-630` [27; X]; `window-form@632-654` [23; PE]; `branch-errors!@656-683` [28; EX]; `execute-clj-eval@685-784` [100; EX]; `runtime-observation@800-821` [22; PEX]; `execute-get-value@876-904` [29; PE]; `tools@906-947` [42; E]; `send-response@967-975` [9; P]; `handle-request@987-1023` [37; PX]; `start-parent-watchdog!@1030-1046` [17; X]; `-main@1048-1084` [37; X] |
| `script/seon/dev/state.clj` | 69 | `write-edn!@18-32` [15; P] |
| `script/seon/dev/test_roots.clj` | 118 | No lexical candidate |
| `script/seon/operator.clj` | 1,529 | `diagnostic@53-77` [25; X]; `advertisements@106-114` [9; E]; `read-prepl-val@133-140` [8; E]; `prepl-value!@142-204` [63; EX]; `live-root-value!@213-225` [13; E]; `request-form@227-232` [6; PE]; `connected!@234-252` [19; E]; `down!@264-285` [22; EX]; `force-stop!@287-300` [14; E]; `child-environment@302-317` [16; P]; `head-capture-form@319-342` [24; X]; `launch-form@344-380` [37; PEX]; `child-runtime-properties@409-428` [20; P]; `record-cache-reference!@501-522` [22; P]; `launch-child!@524-591` [68; P]; `gitlinks@626-634` [9; P]; `leading-fields@662-670` [9; P]; `checkout-pins@672-698` [27; P]; `nuke-attempt!@802-830` [29; X]; `wipe-derived-state!@864-888` [25; E]; `named-source@1057-1065` [9; P]; `restore-heads!@1176-1205` [30; PE]; `move-to-head!@1207-1333` [127; X]; `request!@1335-1361` [27; X]; `top@1439-1445` [7; P]; `cluster-summary@1455-1488` [34; P]; `status-summary@1490-1499` [10; P]; `-main@1501-1529` [29; PX] |
| `script/seon/operator_move_test.clj` | 166 | No lexical candidate |
| `script/seon/operator_nuke_test.clj` | 32 | No lexical candidate |
| `script/seon/schema_split.clj` | 141 | `merge-files@48-77` [30; P]; `write-split!@79-102` [24; P]; `check-forms!@104-120` [17; P] |
| `src/my/agent.clj` | 80 | `branch@59-80` [22; W] |
| `src/my/background.clj` | 78 | `invalid-call@10-17` [8; PX] |
| `src/my/edit.clj` | 104 | No lexical candidate |
| `src/my/fs.clj` | 109 | No lexical candidate |
| `src/my/issue.clj` | 35 | No lexical candidate |
| `src/my/message.clj` | 67 | No lexical candidate |
| `src/my/note.clj` | 53 | No lexical candidate |
| `src/my/plan.clj` | 173 | No lexical candidate |
| `src/my/program.clj` | 666 | `read-result@16-37` [22; X]; `locate@45-66` [22; X]; `render-referrers@228-245` [18; P]; `proposed-plan@247-274` [28; P]; `supplied-context@411-437` [27; X]; `refusal@454-467` [14; X]; `native!@507-508` [2; E] |
| `src/my/shell.clj` | 42 | No lexical candidate |
| `src/my/test.clj` | 61 | No lexical candidate |
| `src/my/turn.clj` | 34 | No lexical candidate |
| `src/my/web.clj` | 41 | No lexical candidate |
| `src/seon/agent.clj` | 244 | `identity@12-33` [22; P]; `archived?@35-48` [14; P]; `effective-settings@125-161` [37; X]; `render-settings-ai@163-167` [5; P]; `render-settings-html@169-244` [76; PW] |
| `src/seon/ai/tokens.cljc` | 210 | `recent-calibration@116-126` [11; P]; `budget-report@171-210` [40; P] |
| `src/seon/ai.clj` | 1,511 | `attempt-ai@112-116` [5; P]; `attempt-html@118-122` [5; P]; `rendered-model@178-184` [7; P]; `agent-setting-attributes@325-342` [18; X]; `extra-body@641-667` [27; X]; `request-body@674-715` [42; X]; `unreadable-stream-data@724-732` [9; X]; `stream-event@770-836` [67; PX]; `parsed-completion@883-950` [68; X]; `completion-text@952-984` [33; X]; `caused-by?@1138-1142` [5; P]; `truncation@1162-1195` [34; X]; `send-request@1306-1455` [150; X] |
| `src/seon/artifact.clj` | 109 | `-main@78-109` [32; P] |
| `src/seon/await.clj` | 153 | `diagnostic@28-53` [26; X]; `await!@106-153` [48; P] |
| `src/seon/background.clj` | 115 | `render-ai@11-20` [10; P]; `render-html@22-31` [10; P]; `poll@33-87` [55; PX] |
| `src/seon/blob.clj` | 395 | `store-faithful-edn@43-57` [15; P]; `binary-threshold@65-82` [18; X]; `verify-stored!@143-160` [18; X]; `stage-binary!@232-294` [63; X]; `get@371-395` [25; VX] |
| `src/seon/bootstrap.clj` | 872 | `help-value@22-70` [49; P]; `render-help-ai@72-76` [5; P]; `render-help-html@78-101` [24; P]; `situation@103-165` [63; X]; `entry-form-source@204-212` [9; P]; `direct-candidates@303-344` [42; P]; `beyond-closure-budget@369-394` [26; X]; `candidate-cost@503-507` [5; P]; `pull-result@577-623` [47; X]; `root-candidate@625-640` [16; P]; `next-entry-in@642-719` [78; PX]; `next-entry@721-731` [11; W]; `supervision-tx@789-845` [57; P] |
| `src/seon/call_preparation.clj` | 1,556 | `schema-fingerprint-query@194-200` [7; P]; `incoherent@202-215` [14; X]; `coherent-supplier@217-283` [67; P]; `by-fingerprint@328-333` [6; P]; `named-entry-facts@359-386` [28; W]; `error-fingerprints-query@435-439` [5; P]; `derive-snapshot@441-534` [94; X]; `snapshot-memo@567-580` [14; W]; `unavailable@1222-1246` [25; X]; `supply@1250-1321` [72; X]; `partial-insertions@1356-1386` [31; P]; `partial-preparation@1388-1408` [21; P]; `prepare@1410-1488` [79; X] |
| `src/seon/cluster/agent.clj` | 1,507 | `namespace-seed-call@162-191` [30; PW]; `render-identity-ai@278-304` [27; P]; `render-id-ai@306-310` [5; P]; `render-identity-html@312-370` [59; P]; `render-id-html@372-394` [23; P]; `render-situation-ai@396-430` [35; P]; `render-creation-ai@432-439` [8; P]; `render-creation-html@441-453` [13; P]; `assigned-to@455-475` [21; P]; `submit-source!@635-653` [19; W]; `submit-source-in-projection@655-723` [69; PX]; `fenced?@725-750` [26; P]; `acquire-context!@873-1041` [169; W]; `arm-graph!@1094-1179` [86; W] |
| `src/seon/cluster/boot.clj` | 702 | `stand-cluster-runtime!@50-133` [84; W]; `stand-boot-layers!@161-260` [100; W]; `diagnostic@263-297` [35; EX]; `carried-environment@313-318` [6; P]; `uncaught-emergency!@320-330` [11; P]; `record-uncaught!@332-372` [41; P]; `start!@393-453` [61; EXW]; `readiness@455-473` [19; E]; `stop!@475-505` [31; E]; `readable-response@527-536` [10; PX]; `request!@550-693` [144; EXW]; `banner@695-702` [8; PE] |
| `src/seon/cluster/branch.clj` | 14 | No lexical candidate |
| `src/seon/cluster/export.clj` | 339 | `progress!@82-87` [6; P]; `warn!@98-106` [9; P]; `copy-store!@198-232` [35; X] |
| `src/seon/cluster/instruction.clj` | 79 | No lexical candidate |
| `src/seon/cluster/message.clj` | 766 | `inbound-tx@149-177` [29; P]; `delivery@179-239` [61; P]; `data-link@287-293` [7; P]; `format-ai@303-339` [37; P]; `render-ai@341-347` [7; P]; `render-html@349-415` [67; P]; `render-inbox-ai@430-446` [17; P]; `render-inbox-html@473-493` [21; P]; `read@593-610` [18; P] |
| `src/seon/cluster/process.clj` | 326 | `start-instant-unavailable!@32-47` [16; X]; `run-process!@202-326` [125; X] |
| `src/seon/cluster/prompt.clj` | 459 | `validate-request!@176-186` [11; P]; `history-contributions@188-217` [30; P]; `priced-unit@227-230` [4; P]; `retained-count@232-251` [20; P]; `select@301-333` [33; P]; `capture-mismatch@335-364` [30; XW]; `acquire-context-report@366-419` [54; PW]; `prompt@421-459` [39; W] |
| `src/seon/cluster/registry.clj` | 548 | `record-fork!@240-264` [25; W] |
| `src/seon/cluster/reply.clj` | 393 | `strip-prompt-markers@123-137` [15; P]; `structured-code-indexes@139-162` [24; P]; `plan-sources@185-234` [50; P]; `readable-code-suffix@244-260` [17; P]; `comment-prose-failure@274-295` [22; P] |
| `src/seon/cluster/source.clj` | 845 | `snapshot@54-97` [44; P]; `database@290-301` [12; W]; `publication-error@403-417` [15; X]; `stale-publication-error@419-428` [10; P]; `record-results-at-head!@430-499` [70; XSW]; `publication-input-digest!@525-537` [13; X]; `accept-merge!@797-845` [49; X] |
| `src/seon/cluster/status.clj` | 177 | `unknown@14-19` [6; X]; `thread-counts@27-47` [21; X]; `snapshot@49-92` [44; X]; `agents@102-162` [61; VX]; `render-ai@164-168` [5; P]; `render-html@170-177` [8; P] |
| `src/seon/cluster/store.clj` | 630 | `listener-failed!@586-619` [34; PX] |
| `src/seon/cluster/wake.clj` | 550 | `declarations-refusal@214-251` [38; PX]; `arming-refusal@253-277` [25; X]; `wake-matchers@391-430` [40; W] |
| `src/seon/cluster.clj` | 3,710 | `report-source-progress!@90-107` [18; E]; `socket-server?@124-128` [5; E]; `render-ai@201-214` [14; P]; `render-html@216-233` [18; P]; `project-next-prepl-value!@277-297` [21; E]; `mcp-effective@309-332` [24; W]; `exception-summary@348-366` [19; PE]; `mcp-projection-error@368-386` [19; X]; `mcp-project@388-494` [107; PV]; `mcp-valf@496-533` [38; PE]; `mcp-io-prepl@535-553` [19; PE]; `mcp-get-value@555-610` [56; PV]; `bounded-face@633-645` [13; P]; `bounded-summary@647-657` [11; PE]; `mcp-runtime-observation@659-741` [83; PW]; `resolve-bootstrap@774-813` [40; E]; `cluster-paths@815-832` [18; E]; `dropped-summary@1319-1349` [31; P]; `accrete-schema-population!@1574-1641` [68; W]; `populate-source!@1643-1789` [147; W]; `source-base!@1830-1866` [37; W]; `require-coherent-program!@1903-1933` [31; P]; `call-with-program-change@2146-2175` [30; X]; `arm-host-program!@2425-2457` [33; W]; `adopt-rows!@2542-2578` [37; W]; `development-source-refresh!@2580-2704` [125; W]; `candidate-gate!@2727-2770` [44; W]; `refresh-source!@2876-2964` [89; W]; `seed-cluster-facts!@3124-3137` [14; W]; `ensure-entity!@3161-3216` [56; P]; `emit-core-fault!@3328-3351` [24; PX]; `loop-handle@3353-3404` [52; P]; `projection-executor@3406-3421` [16; W]; `core-error-mode@3447-3458` [12; P] |
| `src/seon/config.clj` | 802 | `render-ai@49-62` [14; P]; `render-html@64-85` [22; P]; `result-caps@103-128` [26; X]; `refuse!@170-176` [7; X]; `read-edn-map@178-204` [27; W]; `validate-layer@206-240` [35; W]; `admit-initialization-rows@253-334` [82; W]; `admit-initialization@336-351` [16; W]; `validate-default-decisions@373-411` [39; W]; `compile-settings@474-525` [52; W]; `compile-manifest@527-554` [28; W]; `desired-tempid@564-566` [3; P]; `population-transaction-data@568-616` [49; W]; `require-functions!@618-648` [31; PW]; `apply-compiled!@661-729` [69; W]; `apply!@731-756` [26; W]; `effective@760-770` [11; W]; `effective-in@772-802` [31; X] |
| `src/seon/context.clj` | 416 | `selection-refusal@63-74` [12; X]; `capture-html@320-334` [15; P]; `capture-tx@359-402` [44; S] |
| `src/seon/db.clj` | 4,723 | `error-render-ai@57-58` [2; P]; `error-render-html@59-60` [2; P]; `schema-refusal@190-202` [13; X]; `dependency-error@204-222` [19; X]; `carry-projection-state@256-275` [20; P]; `foreign-connection-error@457-491` [35; P]; `replay-read@962-1026` [65; P]; `projection-fallback@1230-1245` [16; PX]; `construction-projection@1400-1411` [12; W]; `carried-projection@1480-1567` [88; W]; `read-declarations@1569-1592` [24; W]; `ask-declarations@1630-1645` [16; PW]; `registered-attribute-candidates@1721-1727` [7; P]; `unknown-attribute-error@1737-1750` [14; P]; `value-type-error@1752-1766` [15; P]; `lookup-ref-error@1768-1795` [28; P]; `query-argument-message@2254-2261` [8; P]; `validate-pulled-value@2445-2481` [37; W]; `pull-budget-error@2506-2519` [14; X]; `pull-call@2521-2577` [57; P]; `database-view@2855-2890` [36; X]; `value-changes@2980-3005` [26; V]; `rejection-message@3100-3112` [13; PX]; `rejected-value@3114-3150` [37; X]; `write-error-identity@3406-3410` [5; P]; `write-owned-values-error@3459-3676` [218; P]; `arity-mismatches@3969-3980` [12; W]; `write-render-target-error@3982-4020` [39; P]; `removed-definition-error@4022-4080` [59; P]; `write-report-validator@4241-4255` [15; W]; `retention-check@4316-4364` [49; P]; `transact-call@4409-4520` [112; XW]; `rendered-value@4537-4541` [5; P]; `render-transaction-ai@4543-4566` [24; P]; `render-transaction-html@4583-4616` [34; P]; `render-rejection-ai@4618-4623` [6; P]; `render-rejection-html@4625-4630` [6; P] |
| `src/seon/edit/jvm.clj` | 164 | No lexical candidate |
| `src/seon/edit.clj` | 514 | `byte-span@14-23` [10; P]; `prefix-end-within@33-43` [11; P]; `suffix-start-within@45-55` [11; P]; `context-window@57-79` [23; P]; `parse-root@116-128` [13; X]; `single-form@148-167` [20; X]; `bounded-values@206-219` [14; P]; `splice@229-231` [3; P]; `lossless-candidate@261-285` [25; X]; `form@314-373` [60; X]; `replace-occurrences@384-394` [11; P]; `exact@396-452` [57; X]; `lines@454-497` [44; PX] |
| `src/seon/effect.clj` | 945 | `payload-face@61-63` [3; P]; `render-ai@75-103` [29; P]; `render-html@105-141` [37; P]; `accepts-request?@185-193` [9; W]; `open-call@248-269` [22; X]; `settle-call@301-361` [61; X]; `interrupt-call@363-404` [42; X]; `with-request-context@449-479` [31; W]; `handler-failure@637-645` [9; X]; `background-time-limit@681-718` [38; X]; `request*@720-934` [215; XW] |
| `src/seon/env.clj` | 425 | `print-method@51-62` [12; P]; `environment-state@81-94` [14; X]; `replace-environment!@96-110` [15; X]; `declared-member-rows@146-178` [33; X]; `absent-member-error@208-220` [13; X]; `construct@222-249` [28; X]; `boot-environment@263-274` [12; E]; `scope@276-308` [33; X]; `supplied-agent-id@335-353` [19; X]; `require-environment@355-372` [18; X]; `carry-state@412-425` [14; X] |
| `src/seon/error/refusal.clj` | 149 | `machinery-namespace-prefixes@4-12` [9; E]; `frame-function@18-41` [24; P]; `root-frame@65-73` [9; P]; `chain@75-89` [15; PX]; `refusal@123-149` [27; X] |
| `src/seon/error.clj` | 2,438 | `message@117-146` [30; X]; `classifying-error-data@290-312` [23; X]; `bounded-text@329-346` [18; X]; `fit-fact-payload@432-471` [40; PX]; `prepare-result@473-505` [33; PS]; `validate-declaration!@507-521` [15; X]; `prepare@523-678` [156; XS]; `fact-source@723-737` [15; X]; `explain-problem@815-865` [51; P]; `problem-sentence@867-891` [25; P]; `scalar-text@893-905` [13; P]; `refusal-value-text@907-927` [21; P]; `reader-correction@929-970` [42; W]; `refusal-data@972-1058` [87; EW]; `instrumentation-prose@1100-1133` [34; P]; `commit-call@1342-1466` [125; W]; `recording@1497-1582` [86; W]; `latest-fact@1674-1699` [26; X]; `rendered-error-value@1701-1716` [16; PW]; `evidence-path@1777-1784` [8; P]; `render-ai@1786-1799` [14; P]; `render-html@1801-1850` [50; P]; `render-undeclared-ai@1860-1865` [6; P]; `render-undeclared-html@1867-1873` [7; P]; `fault-entities@1884-1895` [12; P]; `for-agent@1925-1941` [17; W]; `render-faults-ai@1968-1979` [12; P]; `render-faults-html@1981-2004` [24; P]; `evidence-text@2021-2026` [6; P]; `elision-html@2055-2070` [16; P]; `observed-key-generator@2300-2305` [6; P] |
| `src/seon/eval/drive.clj` | 425 | `read-result@125-137` [13; P]; `sample-cluster-name@369-370` [2; P]; `run-sample!@372-425` [54; P] |
| `src/seon/eval.clj` | 61 | No lexical candidate |
| `src/seon/fault.clj` | 234 | `record!@33-160` [128; PXW]; `carried-receipt@171-177` [7; P]; `fault!@179-234` [56; X] |
| `src/seon/flow.clj` | 1,108 | `graph-executor@76-95` [20; W]; `proc-failed!@198-232` [35; PX]; `submission-capacity-error@330-339` [10; X]; `required-launcher-configuration@536-551` [16; X]; `dropped-fault-descriptor@742-761` [20; X]; `merge-dropped-fault@763-771` [9; P]; `overflow-core-fault@773-798` [26; X]; `report-committer-loss!@937-959` [23; P] |
| `src/seon/fn/analyzer.clj` | 848 | `analyzed-source-path@80-97` [18; P]; `write-mirror!@498-517` [20; P] |
| `src/seon/fn/schema_shape.clj` | 467 | `fingerprint@67-73` [7; P]; `form-fingerprint@100-113` [14; P]; `reference-fingerprints@115-145` [31; P]; `key-kind@198-218` [21; P]; `typed-key-facts@220-238` [19; P]; `child-row@245-255` [11; P]; `entry-row@257-275` [19; P]; `encode-form@306-351` [46; P]; `compiled-in@419-430` [12; W] |
| `src/seon/fn/signature.cljc` | 147 | `declaration-parts@62-98` [37; P] |
| `src/seon/fn.clj` | 3,948 | `require-committed!@49-59` [11; P]; `text-context@114-126` [13; P]; `exact-source@161-178` [18; P]; `exact-form-span@180-193` [14; P]; `seed-contract@534-537` [4; P]; `var-row@633-777` [145; P]; `runtime-analysis-batch@869-928` [60; PW]; `analyze-forms@1099-1158` [60; PW]; `namespace-context@1308-1337` [30; P]; `source-rows@1406-1455` [50; W]; `contract-findings@1852-1993` [142; W]; `manifest-data@2386-2407` [22; P]; `removed-definition-caller-paths@2537-2562` [26; P]; `analyzed-files@2630-2676` [47; W]; `backfill-contract-facts!@2945-3041` [97; W]; `changed-schema-keys@3043-3052` [10; W]; `incremental-projection@3054-3085` [32; W]; `reconcile-tx-in@3400-3476` [77; W]; `reconcile-tx@3478-3490` [13; W]; `reached-entities@3519-3551` [33; P]; `published-index-rows@3553-3646` [94; W]; `database-manifest@3676-3719` [44; W]; `reconciled-paths@3721-3747` [27; PW]; `reconcile-call@3749-3764` [16; W]; `index!@3779-3948` [170; W] |
| `src/seon/fs/jvm.clj` | 901 | `window-pass@276-314` [39; P]; `read-window@478-493` [16; P]; `read@495-510` [16; P]; `blob-pass@561-592` [32; V] |
| `src/seon/fs.clj` | 410 | `write-edn-atomically!@15-29` [15; P]; `footprint@53-66` [14; P]; `admit-destructive-path!@89-163` [75; P]; `log-deletion!@182-211` [30; P] |
| `src/seon/id.clj` | 85 | `printed@29-38` [10; P]; `id@40-50` [11; P] |
| `src/seon/instrument.clj` | 1,123 | `caller-frame@118-138` [21; P]; `failure-cause@152-164` [13; X]; `actionable-problem@253-281` [29; P]; `bind-contract-predicates@424-454` [31; P]; `compiled-wrapper@624-726` [103; X]; `registration-error@827-842` [16; X]; `apply!@844-965` [122; X] |
| `src/seon/issue/detect.clj` | 333 | `declared-keys@16-21` [6; W] |
| `src/seon/issue/opening.clj` | 233 | `render-candidate@111-113` [3; P]; `render-candidate@115-118` [4; P]; `render-candidate@120-126` [7; P]; `render-candidate@128-137` [10; P]; `render-candidate@139-156` [18; P]; `render-candidate@158-168` [11; P]; `render-candidate@170-177` [8; P]; `render-candidate@179-185` [7; P]; `render-candidate@187-189` [3; P]; `source@191-208` [18; X]; `context@214-233` [20; X] |
| `src/seon/issue.clj` | 1,403 | `note-opened@68-94` [27; P]; `parse-note@96-122` [27; P]; `qualified-token@173-184` [12; P]; `citation-attributes@186-215` [30; W]; `citation-spellings@217-229` [13; P]; `cited-span@245-251` [7; P]; `citation@253-268` [16; P]; `render-ai@858-864` [7; P]; `render-html@866-873` [8; P] |
| `src/seon/maintenance.clj` | 969 | `result-entity@38-71` [34; X]; `last-collection-in@214-249` [36; X]; `operation-name@322-326` [5; P]; `render-report-ai@395-399` [5; PW]; `render-report-html@401-430` [30; PW]; `exception-value@432-446` [15; X]; `incomplete-collection!@709-724` [16; X]; `collect!@932-955` [24; X]; `observe-footprint!@957-969` [13; P] |
| `src/seon/note.clj` | 308 | `note-line@45-50` [6; P]; `render-note-ai@52-56` [5; P]; `render-notes-ai@58-73` [16; P]; `note-html@75-105` [31; P]; `render-note-html@107-111` [5; P]; `render-notes-html@113-129` [17; P]; `render-note-form@131-137` [7; P]; `render-notes-form@139-143` [5; P]; `add-note-call@173-205` [33; P]; `forget-note-call@207-230` [24; P] |
| `src/seon/oversight.clj` | 340 | `unavailable-text@238-247` [10; P] |
| `src/seon/plan.clj` | 1,587 | `cluster-agent-acquire-context!@29-30` [2; W]; `plan@347-397` [51; X]; `item@399-435` [37; X]; `step-summary@450-460` [11; P]; `run-issue-tests!@690-725` [36; W]; `outline-numbers@1334-1347` [14; P]; `step-line@1358-1369` [12; P]; `refusal-line@1371-1383` [13; P]; `render-item-ai@1395-1399` [5; P]; `render-item-html@1427-1435` [9; P]; `render-ready-items-ai@1451-1457` [7; P]; `render-ready-items-html@1459-1466` [8; P]; `update-example@1468-1485` [18; P]; `format-plan-ai@1494-1517` [24; P]; `render-plan-ai@1519-1550` [32; P]; `render-plan-html@1552-1587` [36; P] |
| `src/seon/print.cljc` | 1,307 | `literal@12-16` [5; P]; `print-number?@30-34` [5; P]; `print-char?@52-56` [5; P]; `value-at@403-411` [9; V]; `requery-form@444-455` [12; V]; `render-elision-ai@457-489` [33; P]; `emit-table@550-557` [8; P]; `emit-separated@559-564` [6; P]; `emit-sequential@566-579` [14; P]; `emit-entry@601-611` [11; P]; `emit-map-like@613-648` [36; P]; `emit@650-652` [3; P]; `emit@654-656` [3; P]; `emit@671-673` [3; P]; `emit@675-677` [3; P]; `emit@679-692` [14; P]; `emit@694-697` [4; P]; `emit@699-703` [5; P]; `emit@705-707` [3; P]; `emit@709-711` [3; P]; `emit@713-715` [3; P]; `emit@729-743` [15; P]; `emit@745-747` [3; P]; `emit@749-753` [5; P]; `emit@755-761` [7; P]; `emit@763-765` [3; P]; `emit@767-771` [5; P]; `emit@773-775` [3; P]; `emit@777-789` [13; P]; `emit-node@856-879` [24; P]; `emit-text@881-889` [9; P]; `emit-hiccup@891-899` [9; P]; `emit-both@901-912` [12; P]; `fit-entry@1073-1084` [12; P]; `fit-children@1086-1137` [52; P]; `fit-text@1144-1169` [26; P]; `fit-node@1189-1232` [44; P]; `fit@1234-1299` [66; P] |
| `src/seon/problems.clj` | 635 | `problems@400-446` [47; W]; `resolution-text@471-476` [6; P]; `html-report@509-578` [70; P]; `ai-prose@591-635` [45; P] |
| `src/seon/profile.clj` | 286 | `top@152-166` [15; P]; `explain@204-241` [38; P]; `summary@269-286` [18; P] |
| `src/seon/program.cljc` | 1,417 | `edn-round-trip-symbol?@69-94` [26; P]; `component-id@694-696` [3; P]; `map-binding-entries@733-773` [41; P]; `binding-row@842-888` [47; P]; `label-facts@966-971` [6; P]; `arity-row@1004-1051` [48; P]; `declaration-row@1218-1296` [79; W] |
| `src/seon/reconcile.cljc` | 454 | `plan@299-320` [22; W] |
| `src/seon/render/agent.clj` | 24 | No lexical candidate |
| `src/seon/render/block.clj` | 96 | `surface-id@61-96` [36; P] |
| `src/seon/render/data.clj` | 235 | `at@46-89` [44; V]; `observation-error@110-122` [13; X] |
| `src/seon/render/hiccup.clj` | 525 | `void-elements@213-217` [5; P]; `shorthand@258-332` [75; PX]; `append-element!@428-464` [37; X] |
| `src/seon/render/ns.clj` | 836 | `schema-definition-text@229-234` [6; P]; `schema-registration-line@236-239` [4; P]; `referenced-schema-summary@241-251` [11; P]; `render-alias-ai@302-313` [12; P]; `render-alias-html@315-321` [7; P]; `render-refer-ai@323-335` [13; P]; `render-refer-html@337-345` [9; P]; `render-import-ai@347-354` [8; P]; `render-import-html@356-362` [7; P]; `render-data@370-408` [39; P]; `function-source@456-458` [3; P]; `compact-schema-line@466-470` [5; P]; `compact-function-line@472-477` [6; P]; `referenced-schema-ai-section@495-514` [20; P]; `full-ai-text@516-541` [26; P]; `compact-ai-text@561-574` [14; P]; `referenced-schema-html@602-619` [18; P]; `requires-html@621-635` [15; P]; `full-html-view@637-689` [53; P]; `function-ai@744-774` [31; P]; `render-ai@795-826` [32; P]; `render-html@828-836` [9; P] |
| `src/seon/render/route.clj` | 58 | No lexical candidate |
| `src/seon/render/test.clj` | 115 | `render-ai@40-62` [23; P]; `render-html@91-115` [25; P] |
| `src/seon/render/transcript.clj` | 2,484 | `candidate-entity-ids@148-180` [33; P]; `floor-text@434-446` [13; P]; `undisposed-run-text@534-542` [9; P]; `reasoning-attempts@578-618` [41; V]; `render-ai@755-760` [6; P]; `missing-selected-run@781-793` [13; X]; `render-run-ai@795-811` [17; P]; `render-html@876-885` [10; P]; `turn-header@887-908` [22; P]; `render-run-html@910-927` [18; P]; `agent-history-under@945-995` [51; P]; `render-history-ai@1013-1017` [5; P]; `render-history-html@1019-1028` [10; P]; `runtime-owner@1030-1050` [21; X]; `render-runtime-ai@1052-1069` [18; P]; `runtime-message-link@1105-1109` [5; P]; `runtime-turn-table@1111-1138` [28; V]; `turn-reply@1190-1193` [4; V]; `render-agent-header@1318-1325` [8; P]; `reread-summary@1337-1351` [15; P]; `render-session-loading@1353-1379` [27; P]; `render-session@1381-1494` [114; PW]; `outline-renderer@1564-1572` [9; P]; `outline-unit@1574-1612` [39; P]; `outline-everything@1614-1647` [34; P]; `render-outline@1649-1729` [81; PW]; `ledger-acquisition@1731-1745` [15; W]; `results-summary@1784-1788` [5; P]; `ledger-turn-body@1882-1978` [97; P]; `render-ledger-turn@1980-1989` [10; P]; `render-ledger-context@1991-1999` [9; P]; `render-captured-prefix@2098-2112` [15; P]; `prefix-problem@2114-2152` [39; P]; `problem-summary@2261-2275` [15; P]; `render-ledger@2377-2427` [51; P]; `render-runtime-html@2429-2484` [56; PX] |
| `src/seon/render/value.clj` | 741 | `render-agent-render-profile@22-23` [2; P]; `render-project-node@26-27` [2; P]; `datum@47-63` [17; P]; `bounded-text@65-88` [24; PX]; `link-lines@99-117` [19; PX]; `floor@119-131` [13; P]; `render-database-identity-ai@184-195` [12; P]; `print-option-keys@197-202` [6; P]; `node-id@235-259` [25; PX]; `path-url@265-269` [5; P]; `print-options@277-281` [5; P]; `render-profile@283-295` [13; P]; `window@311-355` [45; PX]; `attribute-value@388-423` [36; P]; `breadcrumbs@587-595` [9; P]; `pager@597-609` [13; P]; `prepare@611-658` [48; P]; `render-ai-data@660-664` [5; P]; `render-html-data@666-671` [6; P]; `read-artifact@708-712` [5; V]; `render-prepared@720-727` [8; P]; `render-ai@729-734` [6; P]; `render-html@736-741` [6; P] |
| `src/seon/render/walk.clj` | 974 | `connection-attributes@103-122` [20; P]; `acquisition-members@252-350` [99; P]; `root-pull-plan@375-406` [32; W]; `namespace-connections@408-427` [20; P]; `acquired-tree@483-533` [51; PW]; `root-acquisition@535-573` [39; W]; `namespace-render-distance@593-597` [5; P]; `declared-acquisition@657-692` [36; P]; `neighborhood@710-807` [98; PW]; `history@926-974` [49; PX] |
| `src/seon/render/web.clj` | 3,806 | `debug-query@196-238` [43; V]; `debug-query-strings@240-267` [28; P]; `surface-html@378-400` [23; P]; `page-result@435-572` [138; P]; `generic-entity@599-658` [60; P]; `forward-attribute@665-669` [5; P]; `declared-entity-units@694-714` [21; W]; `debug-diagnostic@723-736` [14; X]; `turn-function-result@738-761` [24; X]; `debug-value-html@795-798` [4; P]; `debug-subject-link@817-825` [9; PV]; `debug-header-html@843-916` [74; PV]; `graph-node-id@925-927` [3; P]; `graph-model@929-972` [44; PV]; `debug-renderer-link@1009-1011` [3; P]; `debug-renderer-contract@1013-1022` [10; P]; `debug-renderer-definition@1024-1035` [12; P]; `debug-preview-html@1037-1043` [7; P]; `debug-read-dependencies-html@1045-1080` [36; P]; `experiment-preview-html@1102-1118` [17; P]; `debug-selected-renderer-details@1120-1137` [18; P]; `applicable-renderers-html@1211-1247` [37; P]; `render-source-call@1426-1510` [85; P]; `debug-render-experiment@1512-1582` [71; P]; `debug-page-result@1765-1844` [80; V]; `program-key@1994-2008` [15; E]; `coalesce-floor@2071-2078` [8; P]; `current-page@2242-2258` [17; W]; `watched-registration-keys@2260-2265` [6; P]; `failed-page-result@2296-2349` [54; PX]; `unreportable-page-result@2351-2373` [23; P]; `render-pass@2375-2489` [115; PW]; `derive-context!@2532-2605` [74; W]; `render-step@2607-2745` [139; P]; `cluster-effective!@3121-3136` [16; X]; `ensure-namespace-owner!@3138-3169` [32; X]; `debug-response@3281-3398` [118; PW]; `create-owner-response@3444-3460` [17; P]; `context-action-response@3462-3490` [29; PXW]; `data-response@3511-3622` [112; VXW]; `handler@3651-3678` [28; W]; `port-floor@3684-3693` [10; P]; `start!@3729-3796` [68; P] |
| `src/seon/render.clj` | 1,873 | `render-web-derive-context!@46-47` [2; P]; `render-walk-neighborhood@50-51` [2; P]; `render-value@58-60` [3; P]; `agent-render-profile@62-88` [27; P]; `request-projection@93-121` [29; W]; `request-profile@123-168` [46; PXW]; `render-argument@196-231` [36; P]; `floor-producer?@251-255` [5; P]; `ambiguity@277-293` [17; PX]; `render-invocation-argument@451-481` [31; P]; `floor-producer@561-568` [8; P]; `producer@643-648` [6; P]; `render-program-evidence@702-720` [19; P]; `render-cost-fact@860-868` [9; P]; `unknown@908-931` [24; PX]; `unknown-output@979-987` [9; P]; `invoke-selected@989-1028` [40; PEW]; `invoked@1048-1056` [9; P]; `valid-projection?@1058-1061` [4; E]; `project-node*@1095-1198` [104; P]; `invoke-producer@1210-1218` [9; P]; `raw-output@1220-1280` [61; P]; `render-ai@1282-1297` [16; P]; `render-html@1299-1315` [17; P]; `source-provenance-error@1327-1338` [12; PX]; `render-form@1340-1355` [16; PV]; `render-default-ai-source@1357-1365` [9; P]; `render-form-value@1367-1391` [25; P]; `render-call@1393-1575` [183; PX]; `acquire-context!@1632-1652` [21; W]; `renderer-failure@1667-1703` [37; P]; `walk-error@1722-1736` [15; P]; `walk@1773-1873` [101; PX] |
| `src/seon/repl.clj` | 519 | `render-directory-ai@32-42` [11; P]; `render-directory-html@44-48` [5; P]; `source-text@91-97` [7; P]; `error-text@149-167` [19; X]; `response-entries@169-214` [46; P]; `text@264-281` [18; P]; `syntax-tokens@287-329` [43; P]; `render-emission-html@335-358` [24; P]; `render-ai@449-455` [7; P]; `pretty-response@457-463` [7; P]; `render-html@482-519` [38; P] |
| `src/seon/run.clj` | 124 | `wait@90-105` [16; X]; `complete@107-124` [18; X] |
| `src/seon/schedule.clj` | 829 | `fire-call@323-429` [107; X]; `settle-call@450-500` [51; X]; `execution-context@550-570` [21; W]; `settle!@589-626` [38; W]; `invoke-handler@628-659` [32; X]; `fire-due!@661-715` [55; W] |
| `src/seon/schema/admission.clj` | 505 | `similar-identities@37-60` [24; P]; `parse-source@72-108` [37; X]; `file-namespace@228-233` [6; P]; `house-findings@235-277` [43; P]; `exact-reuse-findings@293-321` [29; P]; `registration-finding@352-388` [37; X]; `undefined-slot-finding@390-416` [27; P]; `admit@418-498` [81; X]; `-main@500-505` [6; P] |
| `src/seon/schema/datahike.clj` | 576 | `registration-form@31-33` [3; P]; `canonical-print-string@348-356` [9; P] |
| `src/seon/schema/edn.clj` | 644 | `duplicate-attribute@117-122` [6; X]; `directory-resource-paths@151-196` [46; P]; `resource-filename@256-259` [4; P]; `validate-resource-placement!@261-306` [46; P]; `admit-changed-identities@515-548` [34; W]; `admit@550-644` [95; W] |
| `src/seon/schema/internal.cljc` | 528 | `assert-compilable-schema!@432-489` [58; PX]; `assert-non-nilable-value-schema!@491-528` [38; P] |
| `src/seon/schema.clj` | 4,133 | `assert-acyclic-references!@108-128` [21; P]; `runtime-predicate@146-187` [42; X]; `compilable-form@286-373` [88; P]; `unresolved-reference-in@478-489` [12; P]; `unresolved-reference-refusal@491-556` [66; X]; `projection-registry@583-640` [58; W]; `canonical-definition@642-758` [117; P]; `projection-fingerprint@917-947` [31; P]; `projection-fingerprint-version@949-949` [1; P]; `replace-fingerprint-entry@951-959` [9; P]; `frame-namespace@1091-1095` [5; P]; `frame-source-path@1142-1154` [13; P]; `warn-classpath-fallback!@1209-1227` [19; P]; `declaration-population@1244-1269` [26; X]; `call-with-projection@1278-1284` [7; W]; `call-with-projection-state@1286-1293` [8; W]; `assert-config-display!@1353-1376` [24; W]; `malli-form?@1447-1481` [35; P]; `register!@1755-1820` [66; P]; `render-declaration-properties@1848-1849` [2; P]; `render-declarations-in@1851-1868` [18; P]; `arity-render-input@1944-1950` [7; P]; `render-contract-observation@1952-1994` [43; P]; `render-contract-refusal!@1996-2025` [30; PX]; `assert-render-contracts!@2027-2037` [11; P]; `build-projection@2185-2391` [207; W]; `projection-fingerprint-from-data@2409-2418` [10; P]; `reusable-projection-fingerprint@2420-2425` [6; P]; `fingerprint-with@2573-2583` [11; P]; `projection-with-declarations@2693-2931` [239; W]; `projection-rows@2933-2948` [16; V]; `deleted-predicate-functions@2965-2997` [33; W]; `projection-from-rows@2999-3219` [221; W]; `refuse-projection-source@3221-3243` [23; X]; `load-projection@3261-3284` [24; W]; `database-projection@3286-3287` [2; W]; `projection-from-database@3289-3300` [12; W]; `projection-with-schema@3302-3325` [24; W]; `pulled-selector-refusal@3334-3346` [13; X]; `pulled-attribute-entry@3390-3505` [116; X]; `projection-with-pulled-form-in@3556-3576` [21; W]; `pulled-form-in@3578-3591` [14; W]; `projection-without-schema@3593-3623` [31; W]; `projection-with-function-contract@3625-3642` [18; W]; `canonical-schema-rows@3791-3819` [29; P]; `diagnostic-present-attrs@4059-4065` [7; P] |
| `src/seon/sci/admit.clj` | 829 | `canonical-edn@85-95` [11; P]; `refuse-over-bound!@116-135` [20; X]; `object-node@181-190` [10; P]; `open-node@245-361` [117; PX]; `failed-node!@433-442` [10; X]; `rethrow-or-degrade!@444-470` [27; X]; `missing-bound-refusal@662-675` [14; X] |
| `src/seon/sci/eval.clj` | 4,094 | `definition-rows@464-512` [49; PW]; `one-event@551-581` [31; X]; `bind-result!@583-600` [18; PS]; `evaluation-projection@683-690` [8; EW]; `database-effective-config@699-725` [27; X]; `install-function-from-database!@767-801` [35; EX]; `committed-row?@857-878` [22; W]; `interpretation-error@908-917` [10; X]; `interpretation-failure@919-929` [11; X]; `call-detecting-program-change@960-996` [37; X]; `loaded-program@1024-1034` [11; W]; `install-row!@1076-1270` [195; EXW]; `evaluate-native!@1272-1276` [5; E]; `install-evaluated-rows!@1365-1436` [72; W]; `host-namespace!@1478-1554` [77; PX]; `documentation-unavailable@1703-1711` [9; X]; `declaration-absent@1718-1731` [14; X]; `missing-contract@1733-1749` [17; EX]; `documentation-contract@1776-1794` [19; W]; `acquisition-refusal@1958-1984` [27; X]; `acquire-program!@2075-2386` [312; W]; `latest-print-fact@2388-2399` [12; P]; `session-print-options@2401-2416` [16; P]; `fork-for-turn@2502-2534` [33; S]; `derive-base-ctx@2632-2661` [30; SW]; `base-ctx-key@2663-2679` [17; W]; `acquired-database?@2752-2787` [36; W]; `record-refusals-once!@2793-2832` [40; W]; `cluster-ctx*@2920-2946` [27; SW]; `fork-cluster-ctx@2948-3020` [73; W]; `declared-row@3022-3099` [78; PXW]; `failure-text@3160-3184` [25; PE]; `shown-result@3186-3231` [46; PS]; `unrun-evaluation@3353-3388` [36; P]; `evaluate@3401-3880` [480; PEXSW]; `install-candidate-function!@3893-3911` [19; W]; `evaluate-for-install@3913-3937` [25; X]; `refuse-install@3955-3966` [12; S] |
| `src/seon/sci/kernel.clj` | 677 | `ensure-function!@162-179` [18; X]; `new-armed@214-236` [23; P]; `acquire-arm@296-328` [33; PX]; `failure-value@526-572` [47; X]; `invoke@585-677` [93; EX] |
| `src/seon/sci/reader.cljc` | 944 | `offset-cursor@70-74` [5; P]; `function-declaration@251-303` [53; P]; `schema-declaration@374-383` [10; P]; `nested-executable-declarations@429-449` [21; P]; `skip-fences!@524-550` [27; P]; `read-events@552-644` [93; PX]; `reader-error@648-678` [31; X]; `cause-data@702-708` [7; P]; `parse-classification@714-761` [48; P]; `error-event@823-836` [14; P]; `recovering-events@838-883` [46; P] |
| `src/seon/shell/jvm.clj` | 446 | `cwd-path@40-71` [32; X]; `write-array!@140-153` [14; X]; `copy-blob-stdin!@155-212` [58; VX]; `execute@340-413` [74; X] |
| `src/seon/shell.clj` | 28 | No lexical candidate |
| `src/seon/test/accretion.clj` | 414 | `data-contract!@72-89` [18; X]; `observation@107-139` [33; PE]; `auto-check@157-226` [70; X]; `seed-for@228-235` [8; P]; `test-failure@242-259` [18; P]; `auto-check-failure@261-272` [12; P]; `install-refusal@339-359` [21; X]; `render-failure@361-377` [17; P]; `render-ai@379-404` [26; P]; `render-html@406-414` [9; P] |
| `src/seon/test/cache.clj` | 256 | `gitlink-digests@176-205` [30; P]; `input-evidence-digest@240-244` [5; P] |
| `src/seon/test/runner.clj` | 1,842 | `report-identity@186-204` [19; P]; `report-error!@262-269` [8; P]; `report-event!@271-288` [18; P]; `run-vars!@359-399` [41; PX]; `run-var!@401-437` [37; S]; `restore-live-cluster-schema!@506-591` [86; W]; `bounded-drift@647-657` [11; P]; `reach-canonical@704-708` [5; P]; `reach-entries@914-961` [48; X]; `reach-digests@963-992` [30; P]; `program-written-since?@1013-1026` [14; W]; `program-revisions@1028-1044` [17; W]; `program-digest@1046-1062` [17; X]; `execution-refusal!@1104-1110` [7; X]; `complete-members@1288-1423` [136; W]; `record-latest-tx@1425-1584` [160; XS]; `record-tx@1586-1602` [17; S]; `commit-results!@1677-1713` [37; S]; `record-interrupted!@1715-1742` [28; S]; `result-read-error@1787-1798` [12; X] |
| `src/seon/test.clj` | 2,405 | `failure-text@35-55` [21; PV]; `changed-since-green@68-104` [37; X]; `acquire-context!@123-124` [2; W]; `apply-seeds!@126-173` [48; W]; `bounded-result@209-312` [104; E]; `destructive-reach@365-399` [35; W]; `admission-refusal!@1064-1079` [16; P]; `resolve-test@1409-1474` [66; XW]; `program-overlay-tx@1544-1624` [81; PW]; `world-refusal@1643-1652` [10; X]; `open-world!@1654-1668` [15; W]; `clean-request-handle@1697-1788` [92; W]; `member-result@1843-1962` [120; PW]; `arm-first-loads!@2015-2066` [52; XW]; `run@2068-2367` [300; ESW] |
| `src/seon/turn.clj` | 5,608 | `cluster-agent-acquire-context!@52-53` [2; W]; `context-capture-tx@58-59` [2; S]; `evaluation-facts@102-198` [97; E]; `opening-db@280-300` [21; X]; `refuse!@318-328` [11; X]; `open-run-tx-call@476-505` [30; S]; `namespace-rows@577-604` [28; W]; `receipt-settle-tx*@971-974` [4; S]; `receipt-settle-tx@976-993` [18; S]; `receipt-settle-batch-call@995-1008` [14; S]; `receipt-settle-batch-tx@1010-1021` [12; S]; `row-tx@1212-1335` [124; SW]; `record-evaluated-call@1452-1523` [72; S]; `record-evaluated-tx@1525-1582` [58; S]; `receipt-settle-call@1584-1661` [78; S]; `render-ai@1756-1841` [86; P]; `render-html@1843-1850` [8; P]; `read-only-evaluation?@1957-1971` [15; W]; `generated-read-fault@2022-2073` [52; X]; `system-turn@2075-2250` [176; PXSW]; `compact-call@2252-2282` [31; X]; `repair-source@3090-3110` [21; P]; `gate-function-install@3220-3299` [80; PE]; `phase@3385-3414` [30; X]; `evaluation-terminal-data@3426-3539` [114; SW]; `settle-batch!@3543-3604` [62; S]; `refusal-terminal-data@3627-3694` [68; PS]; `settle-batch-refusal!@3696-3762` [67; PS]; `provider-targets@3930-3944` [15; W]; `record-attempt!@3946-4113` [168; P]; `call-turn@4265-4612` [348; PSW]; `disposition-rule-error@4626-4645` [20; X]; `evaluate-sources@4647-4777` [131; ESW]; `preview-sources@4779-4815` [37; P]; `turn@5132-5205` [74; W]; `turn-completion-error@5207-5242` [36; PX]; `await-turn-permit!@5268-5311` [44; P]; `write-refusal-bound@5313-5338` [26; P]; `write-refusal-error@5340-5360` [21; PX]; `offer-write-refusal-fault!@5362-5385` [24; P]; `offer-turn-backstop-fault!@5387-5408` [22; PX] |
| `src/seon/web/extract.clj` | 14 | No lexical candidate |
| `src/seon/web/jvm.clj` | 468 | `split-once@223-229` [7; P]; `unquote-parameter@245-251` [7; P]; `fetch@275-367` [93; X] |
| `src/seon/web/search.clj` | 32 | `organic-results@23-32` [10; P] |

Population: **121 files / 91,456 lines**; **978 matched top-level definitions**. Large orchestration forms remain visible at their full size, but are not booked as deletions merely because one branch duplicates a concern.

## 13. Reproduction and limits

The following is a committed measurement recipe embedded here to honor the assignment's one-file boundary. It reads Git objects and this document only; it runs no Clojure, tests, hooks or JVM. It verifies each retirement range's physical count, rejects overlapping ledger ranges and checks the 3,062-line gross total. Names and semantics still require reading the cited forms and callers.

```python
import pathlib, re, subprocess
pin = "056ef6fe0"
doc = pathlib.Path("docs/research/agent-platform/duplicate-paths-audit-2026-09-25.md").read_text()
ledger = doc.split("## 11. Ranked cuts and exact retirement ledger", 1)[1].split("## 12.", 1)[0]
seen = set()
cache = {}
count = 0
for path, start, end, name, expected in re.findall(
    r"\| \x60((?:src|script)/[^\x60]+):(\d+)-(\d+)\x60 \| \x60([^\x60]+)\x60 \| (\d+) \|", ledger
):
    if path not in cache:
        cache[path] = subprocess.check_output(["git", "show", pin + ":" + path], text=True).splitlines()
    start, end, expected = int(start), int(end), int(expected)
    actual = len(cache[path][start - 1:end])
    assert actual == end - start + 1 == expected, (path, name, actual, expected)
    for line in range(start, end + 1):
        assert (path, line) not in seen, (path, line)
        seen.add((path, line))
    count += actual
assert count == 3062, count
print({"pin": pin, "gross_old_lines": count, "overlapping_lines": 0})
```

Caller evidence can be repeated at the same pin with Git's grep rather than searching a moving checkout. Search declarations as well as code; use full symbols and unqualified aliases before deciding something has no callers. Representative commands for the disputed cases:

```sh
git grep -n -E 'omission-(value|text)|compact-ai-text|html-view' 056ef6fe0 -- src/seon/render/ns.clj
git grep -n -E 'projection-pure-data|materialize-projection' 056ef6fe0 -- src script test
git grep -n -E 'restore-live-cluster-schema!|render-database-identity|pull-at|value-at' 056ef6fe0 -- src script resources/seon/schemas test
git grep -n -E 'evaluate-candidate|run-candidate-test!|runtime-eval|record-evaluated' 056ef6fe0 -- src script
```

Initial caller verification used `rg` over source, scripts, schemas and the relevant tests; the pinned commands above make those searches repeatable despite concurrent work. A textual absence is weaker than a language-level reachability proof. The only unconditional dead-behavior credit is R8, whose callers were inspected and supply full counts; C6 is explicitly test-only and conditional.

**Delivery boundary:** one documentation file; production source/test changes **0 / 0**. No code was loaded, tests run, JVM contacted, cluster altered, or implementation accepted. Boot/reload/publication/probe/test timings, runtime allocation and memory are unmeasured. Those are required before any implementation claim, but were expressly outside this static assignment. The source pin and exact ranges preserve this audit when other lanes change the shared tree.
