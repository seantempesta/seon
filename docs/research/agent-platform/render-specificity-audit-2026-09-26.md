---
type: audit
created: 2026-09-26
source-commit: 005babe83cddf874a897bd75ce3ef2d3b5d00d37
status: source findings and bounded installed observations; implementation not performed
scope: original values, schema render pairs, floor, transcript, REPL and MCP
---

# Render specificity and computation cost

Seon has schema render pairs, a structural value printer, saved evaluation text, and a transcript that reads that text. The smallest composition keeps original values and evidence references, lets each schema's pair select forms, and uses the same general printer for the resulting values.

The new law is not met. Exception/refusal paths explicitly disable pairs; `shown-result` changes contract-error values for presentation; the floor selects diagnostic error fields; nested source-producing pairs are suppressed; and changed-read production overwrites shown text with a separately printed diff. Most requested entity families already have pairs. The missing-pair problem is concentrated in reports and derived return values, while several existing pairs are bypassed or return terminal prose instead of forms.

## Authority, baseline and limits

`CLAUDE.md` was read end to end. The authority is **Three points, and cost decides**, **Render ordinary values totally**, **Apply presentation limits once**, and **One error shape**, at the source commit above. Older designs' structural-only printing and error-specific dispatch instructions do not override these laws. The owner supplied this statement:

> we have 3 kinds of data and we need to make sure the right information is at each point. One chunk of data is the original and it is potentially used by many different functions so if we return it as is there's a bunch of info it doesn't need. The render functions are supposed to fix this and convert it to forms that explicitly show the right data. Within those calls the evals will trigger additional renders if they are present or they will be rendered by the value renderer (default for the system for all values that don't have a renderer). Those are the only points. Think about this problem from a data and computation cost (algorithmic analysis). If it's costly to pull data that is never used it's even more costly to show it to an agent if it's irrelevant. If it's more costly to touch something that is already cached then we leave that alone and we focus on making a good render function. ... the default render function is where the improvements go BUT THEY MUST BE GENERAL. No specific 'if s/error/' shit. The specific stuff is in the render function because we know absolutely this function transforms a specific schema to ai forms.

**Broken baseline first.** `bin/seon status` and MCP `runtime_status`, explicit root `/Users/sean/src/seon`, cluster `default`, reported PID **28001**, start **2026-09-26T19:21:21.030Z**, health **faulted**, one signature, four errored receipts and one unowned namespace. The pre-existing signature is `fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3`, `ClassCastException`, operation `seon.schema/valid-candidate-value?`. Readiness was 86,633 ms. Status contains earlier calls above ten seconds; this audit did not initiate them. The JVM's source archive is **b4b90d405**. Current checkout behavior is not thereby installed behavior.

Source anchors below refer to **005babe83**, including the two requested commits `43637ef5a` and `e0e730dfb` and root's follow-up `66a405636`. No production files were dirty at initial inspection. Unrelated `.gitmodules`, `reference-code/CLM`, `reference-code/http-kit`, `docs/prds/next-turn/` and `src-clm/` work was preserved. The ownership ledger assigns only this report to this lane.

Concurrent source boundary: root landed `36a3714b4` during this audit, splitting `:seon.sci.kernel/execution-error` from the guard error and making the guard observation required again. Its diff changes the declaration/contract, not `called-frames`, structural rendering or the original-value issue. Follow-up should use that execution-error pair; installed observations below remain on the earlier JVM. Root also added the asynchronous durability law in `356a86cba`; retaining original evidence does not imply delaying every reply for a synchronous commit. The audit claims neither bootability nor adoption of these concurrent changes.

Five JVM calls used `read_only=true`, namespace `user`, explicit root/cluster and 5,000-ms deadlines. No defs, reloads, SCI runs, branches, provider requests, tests, processes or worktrees were created. One query mistakenly included uninstalled `:seon.test.result/id` and then passed its refusal to `pull`; that failed probe is recorded below. Its correction checks `integer?` before pulling. Reads kept basis **536871049**. MCP itself retained oversized response artifacts; `read_only` is not a promise that its blob tier writes nothing. No branch retirement was needed.

This is an audit, not a first-pass implementation design or a change to loaded behavior. Source/test delta is **0/0 lines**. The report neither edits the issue index nor adopts the historical plans' implementation details. Existing task `0ae6b217abdd` in `resources/seon/tasks/seon.sci.eval.edn:123` describes structural error printing; its acceptance needs reconciliation with the new law. Task `c088e4367dc5` at `:93` already covers the insufficient-floor-budget refusal handling gap. Reuse these authorities instead of creating duplicate defects. The earlier [error-delivery audit](error-delivery-audit-2026-09-26.md) supplies historical delivery evidence, not a current pass.

## 1. Specific logic in the response path

Classification distinguishes **content selection**, **domain rendering already in a pair**, **general rendering**, and **transport/control decisions**. A reference to `:seon.error/*` is not automatically a presentation defect: constructing or propagating a failure differs from selecting its visible fields. Conversely, a helper called from a renderer can still contain a second selection or printing path.

### Floor, printer and data routes

| Anchor | Behavior | Finding and owner |
|---|---|---|
| `src/seon/render/value.clj:118`, `:127`, `:147` | `causes`/`link-lines`/`floor` select eight causes, twelve ex-data entries, six **first-party** frames and three suppressed throwables, then truncate at 8,192 characters. | Throwable class/cycle handling is general; Seon first-party frame selection is domain-specific. The error base/kernel pair owns the visible stack. Keep a projection-free emergency printer for renderer failure, but make its operation general, with honest loss/retrieval, rather than a second error presentation policy. These limits do not retain complete error evidence by themselves. |
| `value.clj:175`, `:226`, `:239`, `:268` | Normalize pulled ref/cardinality shapes; declared database identity renderer; safe keyword lookup; schema identity lookup. | General storage/reference semantics, or an explicitly named database-identity renderer. No turn/error/task content roster here. Preserve original values; transaction-shape conversion is selection input, not permission to replace a returned entity. |
| `value.clj:314`, `:366` | Root identity and `:seon.repl/handle` supply paths/requery identity. | General provenance plumbing. The handle's spelling does not make this evaluation-specific content selection. |
| `value.clj:420`, `:483` | Throwable becomes `:via` class/message/ex-data without frames; scalar, collection, Var, SCI record/type and namespace cases. | Class-based printing is general. The original Throwable remains held; omitting stack acquisition in a display projection can be valid. Do not reuse this display projection as the complete error record. Contrast with the producer loss in §2. |
| `value.clj:717` | `declared` only consults pairs for safe maps, with an SCI context and without `structural?`; identity-only projections use schema declarations. | Map/context restrictions are general dispatch restrictions, not domain selection, but block the full ordinary-value precedence law. Identity projection is general and declaration-driven. `:seon.error/value` at `:744` unwraps a render-call failure; it is protocol handling, not a reason to summarize an arbitrary error value. |
| **`value.clj:748`** | `diagnostic-line` extracts unknown producer, error message, and **offending error's message**, clips to 400 characters, reports only the first failure plus a count. | **Specific content selection in the floor.** Put the selection in the `:seon.render/unknown` and error-base pairs. A general failure attachment may retain the original result and diagnostic reference; it must not know an error-inside-error layout. Raw diagnostics are returned at `:1170`, but their downstream stringification is a separate loss (§2). |
| `value.clj:847`, `:857` | Resolve peer references to schema-declared identities; do not expand components as peer refs. | General reference behavior. It pulls only identity attributes, unlike `walk/entity-lookup`. Repeated equal referents can still cause repeated reads; measure misses before changing this. |
| `value.clj:924`, `:1139`, `:1252` | Construct insufficient-budget failure; detect returned floor failure by `:seon.error/at`; print refusals structurally. | Failure construction/control, not domain content selection. Replace shape-guessing with the declared result decision during the common-path change; do not add an error-key exception to the allocator. |
| `value.clj:1191`, `:1225` | Artifact admission/read failures and retained admission metadata. | Storage grammar, not a turn/message renderer. Admission limits must remain distinct from presentation limits. |
| `src/seon/print.cljc:275`, `:288`, `:363`, `:370` | General requery, elision render function, malformed-node diagnostics, node-face emission. | `render-elision-ai` is already a specific renderer for the printer's own elision value. `diagnostic-text` constructs a printer fault; it does not identify an incoming error schema. `leaf-ops` handles general print-node faces. No `:seon.turn/*`, task-kind or error-value content branch was found in the printer. |
| `print.cljc:401`, `:443`, `:630` | Nested AI projected text is quoted; collection length/level rules; reference extraction from supplied identity attributes. | General node grammar. Quoting a terminal projection does not implement recursive evaluation of render forms. `prepare` clears length/level before its emission (`value.clj:1114`); other callers must not apply them to saved history. Reference extraction is schema-derived, not an entity-kind list. |
| `src/seon/render/data.clj:47`, `:80`, `:124`, `:142`, `:184` | Typed path lookup/refusal, database refusal forwarding, paged outgoing/incoming datoms and identity membership. | General path/query machinery. No schema-specific incoming value formatter was found. `at` nevertheless embeds the entire original value as `:seon.error/offending` (`:68`, `:75`); use a held reference in its error producer. Cursor/read bounds are not presentation trimming. |

### Selection

| Anchor in `src/seon/render.clj` | Behavior | Finding and owner |
|---|---|---|
| `:119`, `:141`, `:166` | Recognize database/profile refusals and attach render-request identity. | Control/propagation. Not a schema's visible-field selector. The declared operation result should carry this decision; shared code need not guess from `:seon.error/at`. |
| `:262` | Treat `:seon.error/value` and nil as non-source alternatives in `source-return?`. | Specific legacy contract grammar, not content selection. Delete with the source-vs-terminal split; do not carry an error-specific exception into the general entry grammar. |
| **`:363`, `:501–541`** | Exclude `:seon.error/base` from alternatives; branch on error `:at`; use error metadata's declared schema, direct-call alternatives, then error-base pair. Entity ownership is root-only; nested values use the reaching attribute's declaration. | **Error-specific dispatch.** General producer/schema evidence should select the owning pair; the base/domain content composition belongs in error pairs. Entity/attribute handling is general provenance, but root-only ownership means an entity inside an anonymous vector can fall to the floor despite having a pair. Preserve explicit attribute requests; do not replace them by rendering their parent. |
| `:585`, `:594`, `:628`, `:639`, `:693` | Explicit value → viewing namespace → declared pair → floor; agent namespace fetched by exact selector. | General precedence is the right concern here. Viewer-namespace lookup is context acquisition, not agent content rendering. |
| **`:1183–1253`** | Selection for floor-reached maps, including a root evaluation result, checks explicit value then declared pair, **omitting viewer-namespace stage**. It discards selected AI source producers at `:1214–1229`. A returned map with `:seon.error/at` is treated as renderer failure. | **Different value selection path and suppression of forms.** The new law requires nested results to take their own renderer. Use the common selection/evaluation stages; do not merely remove the source suppression and print unevaluated forms. Returned error classification is an invocation/output concern, not content selection. |
| `:1178`, `:1247` | Error-shaped values bypass output validation, then error-shaped renderer returns become diagnostics. | Specific error-shape heuristics. Use the producer's declared result and invocation outcome. Do not move failure detection into an error renderer; move only error content there. |
| `:983–1068` | `unknown-stable-evidence` projects operation/exception class into a render failure; `unknown-ai/html` select fields. | `unknown-ai/html` are correctly located schema renderers. `unknown` also places the entire failure in offending (`:1020`); original diagnostic should be referenced, with selected content in its pair. |
| `:1359–1374` | Default generated read is `pull '[*]`, or `pull-at` with `[*]`. | General fallback, with excessive acquisition for a selected field (§4). Schema-specific selectors belong in the pair; typed arbitrary paths still need general retrieval. |
| `:1651`, `:1665`, `:1764` | Turn-opening basis, namespace and current transaction context. | Acquisition semantics, not presentation of a turn. Do not relocate these into a turn renderer. |
| `:1686–1722` | Renderer failure recording and responsible-agent message construction. | Error producer/delivery boundary. Its prose delivery is not a general printer requirement; preserve the saved diagnostic reference for the receiver's pair. |

### REPL, transcript, evaluator and MCP

| Anchor | Behavior | Finding and owner |
|---|---|---|
| `src/seon/repl.clj:31`, `:120`, `:162`, `:215`, `:257`, `:343`, `:372`, `:449` | Directory columns; definition notes; value/error/diagnostics response fields; changed-read handle line; emission HTML; receipt-to-emission projection. | **Mostly already inside schema rendering.** Directory uses `:seon.repl/directory`; evaluation uses `:seon.cluster.eval/receipt` → `render-ai/html`. These may know evaluation keys. Keep selection there; do not move them to the floor. The duplicated receipt conversion in transcript can call this owner. `data-text`/`shown-value` parsing is a separate historical-text/type conflation, not evidence that every REPL key branch violates the law. |
| `repl.clj:459–489` | HTML can reacquire a live result and force structural printing if the saved AI emission lacks a renderer. | Evaluation pair owns historical presentation, but absence of a saved AI renderer does not determine current HTML precedence. Preserve saved text as historical observation and distinguish any explicit live-value view. Do not infer schema policy from a past output field. |
| `src/seon/render/transcript.clj:148`, `:233`, `:254`, `:298`, `:515` | Select/order `:message`, `:eval`, `:run`; convert receipts; dispatch to message, REPL and turn formatters. | Domain session composition: turn/runtime/history pairs are attached here. Not a general floor. There is duplicate evaluation adaptation (`receipt-entry` + `emission`, versus `repl/entity-emission`) to remove. |
| **`transcript.clj:419–439`** | Builds message source plus `format-ai` text and a separately floor-printed `about/reason` map; prefixes current/pending custody. | Message-specific selection split across the session and message pair. Message pair owns sender/content/about/reason; session renderer owns the current/pending relationship. Preserve both facts; compose the result once. |
| **`transcript.clj:346–373`, `:691–701`** | Pulls saved fault references, resolves current error state, takes `latest-fact`, and floor-prints a vector without requiring an SCI context. | Error observation/content is assembled in the session. A fault may acquire a later occurrence than the evaluation window that saved its signature. Snapshot/occurrence identity belongs to the observation producer; error pair owns visible selection. Do not re-render cached shown text on every turn. |
| `transcript.clj:761`, `:779`, `:789`, `:854`, `:980`, `:1019` | Turn, message/inbox forms, turn header, history and runtime renderers. | Already schema-specific pairs or their helpers. Their known fields are expected here. They need the ordinary entry contract, not a relocation into the floor. |
| `transcript.clj:1107–1284`, `:1294–1318`, `:1464–1584`, `:1747–1809`, `:1828–1948`, `:1974–2062`, `:2126–2203`, `:2334–2456` | Debug/session projections of turns, attempts, effects, rereads, budgets, errors and problems; `evaluation-match` inspects parsed `:seon.error/data :seon.sci.reader/phase`. | Domain HTML render helpers, not universal rendering. `readable-shown` and reread/problem classifiers infer facts from printed strings; move those facts to producer data, with these views consuming them. Six-line preview at `:1945` is HTML disclosure with full content available, not deletion of the original. Do not use preview bytes as saved history. |
| **`src/seon/sci/eval.clj:3929–3968`** | `shown-result/enrich` detects instrument check, validates contract-error, stores offending result, pulls function docs, merges result metadata and doc into the returned error. | **Domain-specific producer transformation for display.** Contract-error producer owns references to offending data and function; `:seon.instrument/contract-error` pair owns documentation/expected/offending/fix forms. General `shown-result` must return the same original value. |
| **`sci/eval.clj:3876`, `:3980`, `:4420`, `:4736`; `src/seon/turn.clj:3851`, `:3922`, `:4937`** | Force `structural?` for diagnostics, thrown evaluation errors, install refusals and settlement failures. | **Bypass of existing error pairs.** Select the error pair like any other value; suppress only re-entry of the currently executing renderer, not the error family. Keep original result beside subsequent failures. |
| `sci/eval.clj:3850`, `:3892` | Failure-name string and Throwable-to-flat diagnostic conversion. | Producer/error-record semantics. A short failure label can coexist with the complete reference; it cannot replace it. Normalization belongs at the error producer, not inside the floor. |
| `script/seon/dev/mcp.clj:297`, `:309`, `:439–478` | Decode MCP envelope, forward saved text, obtain history responses, project run receipts to prepl events; mark failures. | Transport adaptation mostly preserves existing bytes. `run-events` fallback to `shown/error` is a weaker second response path when canonical response is absent; use one evaluation observation. Error/turn keys here classify protocol outcomes, not domain content. A missing canonical response must remain an explicit unavailable observation. |
| `mcp.clj:78`, `:419`, `:515`, `:556`, `:630` | Transport/branch/evaluation failures; Throwable stringification and exception flags. | Failure producers/transport control. They should emit the common error reference/value for rendering, not acquire their own abbreviated content policy. This is not a reason to move branch operations into an error pair. |
| **`src/seon/cluster.clj:348–469`** (MCP caller) | Recognizes saved evaluations; otherwise admits/artifacts the value and calls `prepare` **without db/ctx/projection**; converts print tree back to semantic value for wire printing. | JVM MCP effectively skips declared pairs. This also explains why installed error pairs do not make JVM probe output compact. The bridge's `shown-event` does not repair the upstream selection. `mcp-plain` at `:333` is a separate bounded fallback, not proof of a common saved observation. |

This inventory covers the domain conditions and related protocol decisions in the named paths; table ranges group repetitive field copies and HTML view helpers. It is not a claim that every source line in the repository was audited. A renderer's filename is not the test: its declared input and role in the data flow are.

## 2. Producers and the original value

| Producer/change | Cost and evidence judgment | Move |
|---|---|---|
| **`43637ef5a`**, `src/seon/error/refusal.clj:146–159`, `src/seon/sci/kernel.clj:689–779` | `called-frames` stops at the first SCI frame. However `stack-frames` (`refusal.clj:45`) already obtains and converts the **whole** JVM stack before that filter. The change saves output/storage, not stack capture complexity. Evaluator frames can distinguish evaluator/core faults from author mistakes. Declaring every unrecorded exception an author's exception does not prove the removed frames never matter. | Retain complete first-party cause evidence once, by reference. Kernel/error pair selects author location and called-code frames. Do not restore repeated inline stacks to every error rendering. |
| Same commit: remove copied `:seon.sci.admit/record`, unconditional duration guard, wrapper class | Evaluation duration/allocation/outcome have an existing record owner. Avoiding an inline duplicate is consistent with references. The original error must retain an addressable evaluation relationship if consumers need that evidence. Only emit a deadline observation when the deadline actually fired. | Retain the bookkeeping removal, verify the reference path; do not re-add a copied record to shrink visible text later. |
| Same commit: `author-throwable`, `without-links`, refusal-own-link removal | Removing a repeated message/ex-data copy can be legitimate deduplication. Skipping SCI wrappers is safe only when their unique location, ex-data and suppressed causes remain represented. `author-throwable` follows causes, so wrapper-only suppressed evidence needs an explicit retention proof. A byte-size assertion below 600 does not establish completeness. | One chain with relationships; renderer selects what the agent needs. Preserve distinct evidence instead of equating wrapper with irrelevant. Historical commit message's ~3 KB → 480 B is **not** a fresh installed measurement. |
| **`e0e730dfb`**, `sci/eval.clj:3876–3980`; `resources/seon/schemas/seon.cluster.eval.edn:7`; `turn.clj:1390` | Saving exact shown text is required and valid. The problem is treating floor-printed text as the complete structured diagnostic: the schema is `:string`, and `floor-text` can elide. No raw diagnostic vector/reference is attached by `shown-result`; only the string survives its return. The value itself is retained on `:seon.sci.admit/value`, so error-result and later diagnostic have different durability. | Save original diagnostic references **beside** shown bytes and original result; render once through their pairs. Do not delete historical text, parse it to recover facts, or count a string as complete error data. |
| `sci/eval.clj:3933–3966` enrichment | Re-admits/stores offending value and fetches function docs during printing, then changes the original returned error. This is extra read/write/allocation work at every matching failure unless result-id already exists. | Producer supplies evidence identity once; contract-error pair reads requested documentation. Already-held/cached documentation is left intact. |
| **`src/seon/turn.clj:2270–2281`** | Reconstructs values from saved strings, diffs them, drops `:seon.eval/renderer`, replaces `:seon.eval/shown` with `pr-str {:seon.repl/changes ...}`. This is a fourth presentation point and no floor budget applies to that spelling. The actual result object is not overwritten here, but its shown observation is. | Produce a declared change value referencing original before/after observations; its pair selects changed paths. Store its separately rendered observation through the existing REPL stages. |
| `src/seon/error.clj:280–318`, `:425–463`, `:584–610`, `:680–696` | `classifying-error-data` removes ex-data/deeper frames on admission overflow; `fit-fact-payload` repeatedly halves inline field caps; normalization reshapes DB/schema refusals. These are storage/admission projections, **not automatically presentation trimming**. They are valid only with complete evidence still reachable once, or a typed admission failure explicitly saying it is unavailable. They cost repeated admission/serialization and can leave only a marker when full admission also fails. | Keep complete references; remove repeated string payload fitting when the observation/one-blob model makes it redundant. Do not relabel missing evidence “rendered less.” The shape conversion is owned by the relevant refusal producer, not an all-errors special-case table. |
| `error.clj:465–513`, `:1721–1739` | `prepare-result` selects the offending leaf, stores artifact plus shown text; error renderer re-pulls by signature even when passed a held observation. Selecting a leaf for an explicit result handle can be valid, but it is not the whole error. Re-reading mutable latest facts can change historical meaning and touches already acquired data. | Separate durable original error from selected evidence handles. Error pair uses the held observation and offers explicit reads for additional evidence. |
| `sci/eval.clj:2410–2476` | `doc`/`dir` are intentionally queries returning documentation projections, not the original function entity. Summary-only directory rows are legitimate API semantics. But directory production eagerly reads all referenced schemas and embeds their forms; its existing renderer prints them all. | Preserve API data where useful; select namespace summaries/contracts/schema references in the directory pair. Do not delete function source, doc bodies or cached schema forms just to make `dir` shorter. |
| `render/value.clj:420` versus `refusal.clj:45` | The general Throwable display can avoid calling stack accessors altogether. The error producer's full chain is a different owning value with a completeness requirement. | Maintain this distinction: not collecting optional display detail can be cheaper; discarding required already-captured fault evidence is not the same optimization. |

## 3. Coverage and real-value sizes

Token estimates below are **ceil(characters / 3.2)**, Seon's shipped prior (`src/seon/ai/tokens.cljc:44`), not provider tokenizer measurements. A raw `pr-str` size estimates the amount of content available to an unselective floor; it is **not** a claim that current bounded output always emits all those tokens. The raw/floor distinction matters for large values and retrieval markers.

Installed schema property probe used Malli schemas and `seon.schema.internal/entity-properties`, including inheritance. It confirmed base/error pair inheritance: absence of `:seon.render/ai` directly on an `:and` declaration does **not** mean the pair is missing. `entity-properties` at `src/seon/schema/internal.cljc:122` merges inherited map and root properties. Both `:seon.instrument/contract-error` and `:seon.sci.kernel/error` resolve to `seon.error/render-ai`.

| Frequently shown family | Coverage at source / installed observation | Real-value cost and implication |
|---|---|---|
| Errors/refusals | Base pair in `resources/seon/schemas/seon.error.edn:314`; inherited by contract/kernel errors. Existing pair `src/seon/error.clj:1809` returns prose, not forms. Failure paths and JVM MCP bypass it. | Stored fault eid **34837**, signature above: **3,104 chars ≈970 tokens**. Direct installed `error/render-ai` returned **171 chars ≈54 tokens** (exact text in §6). It drops class field, operation, chain and retrievable evidence from this AI response. A shorter existing pair is not yet sufficient. |
| Evaluation receipts/results | `seon.cluster.eval/receipt` pair at `seon.cluster.eval.edn:37`, installed confirmed. Arbitrary result values still need their own pairs. | Receipt **34529**, id `2ec2c5c5c83b`: **16,612 chars ≈5,192 tokens** raw; saved shown string **3,153 `pr-str` chars ≈986 tokens**, read-evidence **12,814 chars ≈4,005 tokens**. The receipt pair prevents most receipt bookkeeping display; querying `[*]` still acquires it. String counts include escaping/quotes. |
| Turns | `seon.turn/turn` → transcript run pair (`seon.turn.edn:5`), installed confirmed. | Turn **34522**, `12a2b18544e6`: **275 chars ≈86 tokens** raw. Pair expands associated history, so output may exceed the turn row. This is coverage, not a reason to invent another turn summary in the floor. |
| Messages | `seon.message/message` → message pair (`seon.message.edn:67`), installed confirmed. Source-producing pair is suppressed during nested value printing. | Message **34521**, `1802437d`: **351 chars ≈110 tokens** raw. Its content must remain whole. Session-only additional content is identified in §1. |
| Tasks/issues | Both `seon.task/task` (`seon.task.edn:24`) and `seon.issue/issue` (`seon.issue.edn:47`) have installed pairs. | Task **34504**, `132fee0467c8`: **771 chars ≈241 tokens**; issue **33019**, `a-blocking-realization-is-not-bounded-by-the-interrupt`: **1,607 chars ≈503 tokens**. Missing nested dispatch is different from missing pair coverage. |
| Functions/namespaces | `seon.fn/fn` → function pair (`seon.fn.edn:132`), `seon.ns/ns` → namespace pair (`seon.ns.edn:13`), installed confirmed. `dir` has a pair and explicit override. **`documentation-value` returns a plain `:map`, no named documentation pair** (`sci/eval.clj:2478`). | Function **2009**, `my.agent-test/held-branch`: **2,955 chars ≈924 tokens**; namespace **8206**, `my.agent-test`: **1,553 chars ≈486 tokens**. Actual `documentation-value` for `seon.db/pull`: **719 chars ≈225 tokens**, keys `summary/body/example/arglists/in/out`. No frequency claim is inferred from one sample. |
| Test definitions versus results | `seon.test/test` and test result declaration at `seon.test.edn:62` use `render.test`; **`seon.test/run-result` (`:341`) and `seon.test.run/run` have no AI/HTML pair**, installed confirmed. | Stored run **34584**, `a0a3d5a5fc3b`: **1,287 chars ≈403 tokens**. This is a real persisted run, not a freshly executed returned report. Returned run reports can additionally contain every captured result; no fresh report was produced for this audit. |
| Diffs | `seon.db.diff/paths` has no pair (`seon.db.diff.edn:7`); `seon.repl/changes` aliases it (`seon.repl.edn:16`). `turn.clj:2281` directly prints a wrapper with no declared change-result schema. `seon.program/three-way` also lacks a pair (`seon.program.edn:18`); `program.cljc:631` returns identity sets and three digests per conflict, and `cluster/source.clj:860` declares it as a merge conflict result. `seon.source/proposal` (`seon.source.edn:107`) and `my.program/merge-proposal` (`my.program.edn:27`) lack pairs too. These source findings were not separately probed in the installed registry. | No changed-read sample was captured in these bounded probes, so **per-occurrence token cost is unmeasured**. Cost is proportional to printed changed paths and replacement subtrees; it can include a whole changed value. Acceptance must measure a real reread and a merge diff separately. |

Pairs are not universally terminal AI implementations of the new law: error and directory pairs return final strings; task/function/namespace/message/test pairs often return source strings; nested source-producing selection suppresses them. Adding declarations alone therefore cannot establish coverage. Required proof is a real returned/nested value rendered through the viewing agent's normal path.

## 4. Acquisition and algorithmic cost

Let A be installed attributes, E reached entities, D their datoms including expanded components, O error occurrences, H historical evaluations, and B the floor character allowance. Text selection can be bounded by B while acquisition already paid for D; a cache hit changes this comparison.

| Site | Inputs, recomputation and current cost | Smaller operation / limit |
|---|---|---|
| **`src/seon/render/walk.clj:166–175`** `entity-lookup` | `pull '[*]` solely to find an identity, on each uncached lookup. Datahike wildcard expands components. O(D) payload to return one identity. | Pull `[:db/id ...declared identity attrs]`, as floor `referent-identity` already does. Sample receipt: **16,612 →52 chars**; fault **3,104 →104**; function **2,955 →53**; namespace **1,553 →42**. These are returned-value sizes, not measured disk IO or allocations. |
| **`walk.clj:1014–1030`** `finished-graph` | After bounded graph traversal, bulk wildcard-pulls every reached node. O(sum D), not just O(E); read-evidence components can be fetched even when an evaluation pair consumes only saved shown text. | Feed identity/minimum context to source-producing pairs and let their forms request content; terminal history needs the receipt selector. Retain full existing values by reference if already acquired. Do not replace this with another hand-maintained per-kind pull table. |
| `walk.clj:130–158`, `:366–385`, `:409–455` | `root-selector` enumerates A, reads all scalar attributes plus bounded forward identities, then declared reverse concerns. Compiled pull plan is cached; entity results reuse read evidence. Selector construction precedes cache lookup. Depth is query-work policy. | Improve misses that acquire unused fields. Leave valid retained entity values and compiled plans intact; do not rewalk/copy them merely to shrink output. Narrow schema forms at the render owner and measure visited datoms/misses. |
| **`src/seon/error.clj:1663–1718`, `:1729`** | Observation selector is memoized by projection, but pulls all occurrence children; `latest-fact` sorts O occurrences (O(O log O)) and sums them; `rendered-error-value` repeats the pull for a held signature. | Use the supplied immutable observation; for explicit latest queries read the actual latest occurrence and a justified count source. Avoid a second cache or stamp. Historical reads must name their own occurrence/basis. |
| **`src/seon/render/transcript.clj:356–372`** | Query fault edges for **all** agent evaluations, group, then select shown eids. For each selected edge, pull observation plus resolution; repeated signatures repeat work. O(H fault edges + repeated occurrence pulls). | Bind selected evaluation ids in the query; group shared references once within the held database derivation. Pass error references to the pair. Cached shown history remains unchanged. |
| `transcript.clj:40–72`, `:254–295` | Receipt selector includes `:seon.error/at/layer/operation/data-edn` at `:64`; `receipt-entry` does not consume them. It also carries data used by HTML/history basis paths. | Remove the four unused error fields. Do not remove read-basis/print/interruption evidence without checking all consumers; the receipt-to-emission duplication is a separate deletion. |
| `sci/eval.clj:2265–2284`, `:2433–2476` | Directory reads every public function's complete doc/spec/arity refs and all referenced schema forms, per directory production, before a renderer chooses output. | Directory pair selects summaries/contracts and executable explicit schema reads; avoid opening all schema forms just to display their names. Cached original forms need no rewrite. No whole-namespace timing was measured here. |
| `src/seon/print.cljc:275`; `render.clj:1372`; `render/data.clj:90` | A leaf continuation can wildcard-pull the root then select the path. O(D) for a requested field. | Generate an exact selector when the typed path is an attribute path; retain general value navigation for arbitrary map/index paths. Verify path/requery equality. |
| `src/seon/cluster.clj:394–443` | JVM MCP admits the whole value and serializes an artifact before floor selection; large responses use its reconstructed value. Work scales with admitted value/storage bounds, not only B. | Preserve original backing and common render entry; admission remains a durability decision. Do not claim a small first page implies sublinear capture. The audit's first schema response retained a **10,313-byte** artifact. |
| `render/value.clj:857–875` | For a cardinality-many peer ref, `map one` resolves all supplied members before floor admission. Repeated eids are not memoized in this helper. | General improvement: resolve only selected members, reuse identities within the held observation. Prove counts with finite refs; avoid a new global cache. |

Pinned dependency evidence: Datahike **19ad462aa67763049d49756b5fdc0f258a2830d8**, `reference-code/datahike/src/datahike/pull_api.cljc:16` has no implicit limit; `:315–323` applies only an explicit limit; `:335–357` expands explicit subpatterns/components and otherwise yields ref identities. `compile-pull-plan` is at `:80`. Malli **a89ebef0572628b9198b825e5aa8b59484902593**, `reference-code/malli/src/malli/core.cljc:2600` supplies properties and `:2645` supplies the cached `validator`; Seon's inheritance composition is `schema/internal.cljc:100–128`. No replacement library or new cache is proposed. Current raw values, not documentation, establish the measured sizes. Other inspected dependency pins:

| Dependency | Pin |
|---|---|
| `reference-code/sci` | `743d1c4317d95af9d1af74928392b913c0d1b91d` |
| `reference-code/fipp` | `255a19061fdc20d67eca09a17e9af282aa8bf909` |

## 5. Ranked moves and file-disjoint slices

These are reviewable audit recommendations, not approved production edits. Line counts are approximate gross deletions/conversions at the pinned revision; transferred content is not counted as a net deletion. Each path has one slice owner, including its tests and schema files. Shared integration happens in rank order; independent owners may prepare changes against the declared interface. No file is assigned to two concurrent slices.

| Rank / slice | Exclusive files and anchors | Move and deletions | Acceptance: value, text and cost |
|---|---|---|---|
| **1 — Error values and their pairs** | `src/seon/error.clj:1663–1823`, `src/seon/error/refusal.clj:146`, `src/seon/sci/kernel.clj:689`; `resources/seon/schemas/seon.error.edn`, `seon.instrument.edn`, `seon.sci.kernel.edn`; existing error/kernel tests | Keep full chain by reference, remove `called-frames` capture truncation (~15 lines); replace prose-only error AI output (~14 lines) with forms selecting operation/class/message/location and refusal expected/offending/fix, plus evidence queries. Move contract documentation selection here from slice 3. Remove unconditional held-signature repull (~9 lines). | An ordinary divide-by-zero first observation targets **≤200 estimated tokens**; a one-leaf contract refusal **≤400**, with runnable evidence reads. All distinct cause data/Seon frames remain retrievable; bookkeeping record is referenced. Targets are provisional acceptance ceilings, not permission to truncate. Compare actual parent/candidate examples and identity of held values. |
| **2 — Common selection and general floor** | `src/seon/render.clj:262,363,501,1183`, `src/seon/render/value.clj:127,717,748`, `src/seon/print.cljc:370`; `resources/seon/schemas/seon.render.edn`, `seon.render.value.edn`, `seon.print.edn` and existing render/print tests | Replace error-specific dispatch with common declared producer evidence; delete base-exclusion/error-only arm (~15–20 lines), nested source suppression (~16), duplicate nested precedence. Move diagnostic visible-field extraction (~15) to slice 1's pair; general fallback remains. Evaluate selected forms through the existing stages, with current-producer re-entry exclusion. | Value override, viewer namespace and schema pair work for root and nested results, including errors and entities in vectors. Nested result invokes its pair once; no double evaluation. Unknown values spend one B and carry general cuts. Same selected tree/visible content for AI/HTML. No error-key content branch remains in floor. New failures retain the original value plus one diagnostic. |
| **3 — Evaluation, saved observations and read changes** | `src/seon/sci/eval.clj:3876–3980,4420,4736`, `src/seon/turn.clj:2270,3851,3922,4937`, `src/seon/repl.clj:162,459`; `seon.cluster.eval.edn`, `seon.sci.eval.edn`, `seon.repl.edn`, `seon.db.diff.edn`; their existing tests | Remove enrichment from general `shown-result` (~40 lines), family-wide structural options (~10), diagnostic-only string path (~15), and direct diff text replacement (~12). Carry original/diagnostic references and one saved text. Add the named documentation/change pairs where their values are declared. | `identical?` original result before/after printing where applicable; rendered error does not mutate the result. Saved text is unchanged on replay. Changed read shows changed paths plus a full-value query, targets **≤250 tokens for one scalar path**, and does not print full unchanged values. Retain a real diff sample and report its measured size; errors/failures are recorded and delivered, not only strings. |
| **4 — Transcript and message composition** | `src/seon/render/transcript.clj:233–295,346–373,419–439,456–493`, `src/seon/cluster/message.clj:341`, `resources/seon/schemas/seon.message.edn`, `seon.runtime.edn`, `seon.turn.edn`, `seon.render.transcript.edn`; transcript/message tests | Reuse `repl/entity-emission` instead of receipt conversion plus emission (~80 lines converted/deleted); move about/reason selection into message pair (~10 lines transferred); use selected fault ids, held observations and error pair. Session keeps ordering/custody labels. | MCP response, next context and saved history use the same evaluation bytes. Message body preserved; metadata overhead target **≤100 tokens per ordinary message**. Rendering selected run does not scan unrelated fault windows. A later occurrence cannot change old delivered fault evidence. |
| **5 — Acquisition** | `src/seon/render/walk.clj:166,366,1014`, `src/seon/render/data.clj:80`; existing walk/data tests | Replace wildcard identity lookup (one selector replacement); remove unconditional wildcard graph expansion (~2–5 lines, replacing with existing identity/required history acquisition). Narrow continuation pull where supported. Do not add a schema-specific pull table to the floor or invalidate retained caches. | Identity lookup returns same identity using sample sizes **52/104/53/42 chars**, not **16,612/3,104/2,955/1,553**. Read evidence/cut continuation remain correct. Count pulled datoms and cache misses. Warm unchanged render performs no new whole-entity acquisition; fix >20% or 50-ms regressions. |
| **6 — Report and directory coverage** | `src/seon/render/test.clj:40`, `src/seon/render/ns.clj:298`; `seon.test.edn:341`, `seon.test.run.edn`, function/namespace/task schema files, `seon.program.edn`, `seon.source.edn`, `my.program.edn`; render-test/ns tests | Add report/run render pairs to existing render owners; use counts, failed/pending/unfinished members and evidence forms. Refine directory/namespace selection and add comparison/proposal pairs selecting changed identities, conflicts and explicit digest reads. Existing test assertion formatting is reused; no second test summary owner. Small additions are justified by missing behavior, not claimed as net deletion. | Green run summary targets **≤200 tokens plus explicit test/evidence queries** (sample raw run ≈403). Never conceal red/unknown/unfinished members. A large directory first observation targets **≤1,000 tokens with contracts and explicit further reads**, measured on a real namespace. A single-conflict comparison targets **≤250 tokens plus evidence reads**, measured on a real proposal before integration. Slice 3 owns the directory producer changes if needed; schema selection alone cannot save producer reads. |
| **7 — MCP convergence** | `src/seon/cluster.clj:333–469`, `script/seon/dev/mcp.clj:297–325,439–478`, prepl resource and existing MCP tests | Pass the same held render world and observation through the shared stages; retire semantic-node reprinting/evaluation fallback when the common observation supplies bytes (~25–40 lines converted). Preserve required transport framing. | Same small real value and same profile give the same selected content through JVM/SCI/history where their entrant semantics permit. One missing-attribute refusal names member, expected and fix without repeated whole request copies; complete evidence remains addressable. A first page and its continuation use one backing value. |

The principal alternative to a new mechanism in every row is an existing pair, selector, held value, or deletion. Slice 2's forms evaluation is the largest dependency: it must use the installed REPL stages and current custody law, not revive connection denylists from the older entry-render design. If that needs more than roughly 100 added production lines without matching removals, return a smaller cut and prices to root before implementation. The changes above do not require clearing caches, rebooting the store, or adding a renderer registry.

For each implemented slice, root should verify publication/adoption/arming, then run one focused `seon.test/run` on the lane branch and repeat the same normal agent observation. This audit ran none; it cannot report green tests or installed fixes. Root owns platform/bulk verification. Performance targets are sub-second ordinary operations, recorded with parent/candidate sizes and timings; no unmeasured overhead is accepted as “cached” or “cold.”

## 6. Reproducible bounded evidence

All forms below used MCP JVM mode, root `/Users/sean/src/seon`, cluster `default`, namespace `user`, `read_only=true`, timeout 5,000 ms. They define no Vars and call no mutation owner. Sizes describe finite stored values at basis 536871049. The exact forms are preserved here so evidence does not depend on disposable files.

### P1 — Installed schema inspection

```clojure
(let [t (System/nanoTime)
      d @(seon.cluster.boot/connection "default")
      p (seon.db/carried-projection d)
      fs (:seon.schema.projection/forms p)]
  {:basis (seon.db/basis-t d)
   :schemas (mapv (fn [k] [k (get fs k)])
                  [:seon.instrument/contract-error :seon.sci.kernel/evaluation-error
                   :seon.repl/directory :seon.test/run-result :seon.repl/changes])
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Result: **0.216708 ms**, prepl **11 ms**. The guessed `:seon.sci.kernel/evaluation-error` was absent; the real name is `:seon.sci.kernel/error`, confirmed in P4. `:seon.repl/changes` is `:seon.db.diff/paths`. Schema output was elided by MCP, artifact **becc28bd3a7554ba5eed46ff49c06f73b98abddca3329d09079cca3a67a7832f**, size **10,313 bytes**. This is itself evidence that a general response can pay for large original content before floor selection; it is not a complete unelided schema dump in the chat response.

### P2 — Failed sample query

P3 below was first run with `:seon.test.result/id` inserted before `:seon.test.run/id`, `(when eid ...)` instead of `(when (integer? eid) ...)`, the eid returned directly, and `(count (pr-str row))` unconditionally. The database correctly returned a refusal for the uninstalled attribute; the probe incorrectly used that map as a pull entity-id. The resulting input-contract exception was delivered with `isError:true` and retained as artifact **a2857ea40ffa9b7eb7f4aba1aade53d30925790f54c03481fb09a13b4cf6e9c5**, **25,143 bytes**. Its visible data repeats the offending query refusal in three alternative-schema problems. No sample result from this aborted batch is used. The 25,143-byte artifact is not a token count or a measurement of a normally sized domain error.

### P3 — Corrected real entity samples

```clojure
(let [t (System/nanoTime)
      d @(seon.cluster.boot/connection "default")
      attrs [:seon.error/signature :seon.cluster.eval/id :seon.turn/id
             :seon.message/id :seon.task/id :seon.issue/id :seon.fn/sym
             :seon.ns/name :seon.test.run/id]]
  {:basis (seon.db/basis-t d)
   :samples
   (mapv (fn [a]
           (let [eid (seon.db/q '[:find (min ?e) . :in $ ?a :where [?e ?a _]] d a)
                 row (when (integer? eid) (seon.db/pull d '[*] eid))]
             [a (if (map? eid) :refused eid) (when row (get row a))
              (count row) (when row (count (pr-str row)))])) attrs)
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Result, **7.402459 ms**, prepl **12 ms**, no MCP windowing:

```clojure
[[:seon.error/signature 34837 "fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3" 11 3104]
 [:seon.cluster.eval/id 34529 "2ec2c5c5c83b" 16 16612]
 [:seon.turn/id 34522 "12a2b18544e6" 8 275]
 [:seon.message/id 34521 "1802437d" 4 351]
 [:seon.task/id 34504 "132fee0467c8" 8 771]
 [:seon.issue/id 33019 "a-blocking-realization-is-not-bounded-by-the-interrupt" 10 1607]
 [:seon.fn/sym 2009 my.agent-test/held-branch 21 2955]
 [:seon.ns/name 8206 my.agent-test 9 1553]
 [:seon.test.run/id 34584 "a0a3d5a5fc3b" 14 1287]]
```

### P4 — Inherited pair coverage

```clojure
(let [t (System/nanoTime)
      d @(seon.cluster.boot/connection "default")
      p (seon.db/carried-projection d)
      ks [:seon.error/base :seon.instrument/contract-error :seon.sci.kernel/error
          :seon.cluster.eval/eval :seon.turn/turn :seon.message/message
          :seon.task/task :seon.issue/issue :seon.fn/fn :seon.ns/ns
          :seon.test/test :seon.test/run-result :seon.test.run/run
          :seon.repl/directory :seon.repl/documentation :seon.repl/change-result]]
  {:pairs
   (mapv (fn [k]
           (let [f (get (:seon.schema.projection/forms p) k)]
             [k (if f
                  (select-keys
                   (seon.schema.internal/entity-properties
                    (malli.core/schema f {:registry (:seon.schema.projection/registry p)}))
                   [:seon.render/ai :seon.render/html])
                  :absent)])) ks)
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Result **0.594375 ms**, prepl **5 ms**. Pair symbols are in §3. Test run/result yielded `{}`; guessed documentation/change-result/eval schema names yielded `:absent`. Receipt's correct name was checked in P5. Artifact **1009102a4cfd7bd6f530bb110945c269ffd92afbec9870c9843ec2b80fbcbdb2**, **6,057 bytes**. These observations verify effective schema properties, not pair invocation through an SCI agent.

### P5 — Pull sizes, actual error renderer and memory observation

```clojure
(let [t (System/nanoTime)
      rt (Runtime/getRuntime)
      heap #(- (.totalMemory rt) (.freeMemory rt))
      before (heap)
      d @(seon.cluster.boot/connection "default")
      p (seon.db/carried-projection d)
      measure (fn [eid a]
                (let [row (seon.db/pull d '[*] eid)
                      small (seon.db/pull d [:db/id a] eid)]
                  [eid (count (pr-str row)) (count (pr-str small))]))
      e (seon.db/pull d '[*] 34529)
      fault (seon.db/pull d '[*] 34837)
      doc (seon.sci.eval/documentation-value d 'seon.db/pull 'seon.db/pull)
      shown (seon.error/render-ai {:seon.render/value fault :seon.db/db d})]
  {:basis (seon.db/basis-t d)
   :pulls (mapv (fn [[eid a]] (measure eid a))
                [[34529 :seon.cluster.eval/id] [34837 :seon.error/signature]
                 [2009 :seon.fn/sym] [8206 :seon.ns/name]])
   :evaluation-fields (mapv (fn [[k v]] [k (count (pr-str v))]) (sort-by key e))
   :fault-ai-chars (count shown) :fault-ai shown
   :doc-chars (count (pr-str doc)) :doc-keys (vec (keys doc))
   :receipt-pair
   (select-keys
    (seon.schema.internal/entity-properties
     (malli.registry/schema (:seon.schema.projection/registry p) :seon.cluster.eval/receipt))
    [:seon.render/ai :seon.render/html])
   :heap-delta (- (heap) before)
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Result **61.290583 ms**, prepl **65 ms**. Pull pairs and main field sizes are in §§3–4. Exact error AI output:

```text
class clojure.lang.Symbol cannot be cast to class clojure.lang.Keyword (clojure.lang.Symbol and clojure.lang.Keyword are in unnamed module of loader 'app') Occurrences: 1.
```

Other evaluation field `pr-str` lengths: id 14, source 8, comment 58, at 37, author 7, ns 15, ordinal 1, read-basis 9, run 15, duration 2, renderer 29, renderer-fn 14, ending-ns 14, db/id 5. Heap delta **+113,737,496 bytes** is process-wide used-heap movement across this compound call; it includes concurrent activity and GC effects, is not retained memory or per-function allocation, and does **not** establish a memory pass. Artifact **9b283d34fd6fee2f8c8174d4d37dd08dbd42c0e385b05eca42d3b4157aa0c2e8**, **5,529 bytes**.

No successful audit form exceeded one second; coarse tool wall time was 0.3–0.4 seconds per call. These are bounded inspection costs, not end-to-end agent-turn, browser-paint, cold-load, publication, or per-armed-function performance measurements. No such pass is claimed. No evidence establishes how often each schema is shown in paid agent turns; the coverage inventory follows current first-party call paths and one sample per available stored family.

Validation: the repository's `seon.dev.markdown/validate-file` returned `:valid? true` and no violations; `git diff --check` passed. No code/test gates were run for this documentation-only change.

The report is the only released path. No production behavior, schema, skill, issue index or other lane's files were changed.
