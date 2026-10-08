---
type: research
status: proposed
created: 2026-10-08
lane: agent-message-audit
---

# Agent messages: census and proposed edits

Seon already carries evaluated values and errors through one REPL path, then selects an AI render and prints the result. The smallest change is to return smaller maps from the existing producers and delete their prose composers; retain the value printer, native Clojure operations, and saved observations.

This is an audit and editing proposal, **not an installed implementation**. Lane: `agent-message-audit`. Owner instructions: 2026-10-08, “optimal clojure” and “always use clojure terms and built in tools.”

## Findings and evidence boundary

Broken first:

- The live contract error contains its type and operation; its AI render drops both. The contract sentence repeats path, expected value, fix and contract. Shortening only the sentence would leave an unidentified error.
- A two-key `select-keys` result becomes a 1,567-token agent/history render. The actual result still has exactly `:seon.agent/id` and `:seon.agent/branch`. Fix render selection before editing all the expanded history strings.
- `(help)` returns bare prose, is 1,201 tokens with its reply, and itself triggers `:large-value`. Its “NEXT turn” advice describes model turns, not the synchronous MCP caller. The forgiving reader and the help text also disagree about prose handling. Teach forms and `;;` comments without claiming Markdown can never be accepted.
- Every successful value `def` repeats 87 tokens of note/envelope overhead beyond the proposed ordinary reply. Do not repeat it, and do not decide persistence from the spelling `def`: function-valued defs may be admitted.
- Output is now bounded and has a working-shaped `subs` continuation. It still consumes 1,575 tokens for a small `println` probe. Do not report the previously fixed unbounded-output defect as current.
- Startup baseline: shell status and MCP `runtime_status` answered; default was `:faulted`, with 5 open-fault entries, 36 errored receipts, an error without a task in the displayed top list, and analyzer calls over one second. Existing examples include tasks `1f67a74c7846`, `fd586f8c0e99`, `8ab559348c1a`. This audit did not resolve those faults.

Source snapshot began at HEAD `76daa8fd5`; live default was committed archive `9c88ae5b8152e951d67606824c3ac315fa340d89`, PID 72173, start `2026-10-08T02:27:20.846Z`. Source observations and live observations are distinguished below. No source was loaded into default; all SCI probes used `agent="agent-message-audit"`, `cluster="default"`, `mode="sci"`, branch `:agent-7bf49eb7d100`. Root is the orchestrator, not the probe agent. No provider call was made.

Foreign working-tree boundary at entry: `src/seon/repl.clj`, `src/seon/sci/eval.clj`, `src/seon/turn.clj`, `resources/seon/schemas/{seon.cluster.eval,seon.eval,seon.repl}.edn`, `test/seon/{mcp_entrance,render/transcript}_test.clj`, plus unrelated docs and an untracked differential test. None was edited or adopted by this lane. The census records the observed checkout; it is not proof that all those bytes are installed.

Raw MCP text, arguments, `isError`, complete before text, concrete proposed after text, and estimates are in [probes](agent-message-audit-probes.json). The live result handles in that file are historical evidence, not permanent APIs. The initial `(seon.repl/text {})` trial **did not violate a contract**; it returned a prompt. The successful violation is the separate `contract-violation` row.

### Measurement

Use `seon.ai.tokens/estimate`, shipped calibration: floor of UTF-16 string length divided by 3.2 (`src/seon/ai/tokens.cljc:129–160`). Verified live: `(seon.ai.tokens/estimate "12345678901234567890123456789012")` returned `10`. These are estimates, not provider token counts. Python in the committed [census script](agent-message-audit-census.py) reproduces that calculation offline so the audit does not send its own large transcript back through MCP. Reply sizes below count all MCP text content, excluding the outer JSON transport. Proposed sizes count the complete proposed reply with the same result handle. Whitespace is included.

Stored `:seon.eval/value-tokens` is not the same measurement as the whole shown MCP reply: the large string reports 1,575 value tokens and 1,500 shown tokens; the complete text reply is 1,591. The 1,600-element vector printed in 56 tokens and did **not** emit `:large-value`. Do not infer findings from collection count alone.

| Probe | Form | Complete reply tokens | Evaluation ms | Observed outcome |
|---|---|---:|---:|---|
| Help | `(help)` | 1,201 | 17 | Bare text; large-value finding |
| Contract | `(seon.sci.admit/admit {:seon.sci.admit/value [1 2 3] :seon.sci.admit/caps {} :seon.sci.admit/interrupt-fn (fn [])})` | 414 | 4 | Three missing-key problems, repeated prose |
| Unknown symbol | `(identity message-audit-missing)` | 88 | 1 | Native resolution wording; internal frame |
| Arity | `(inc 1 2)` | 81 | 0 | Native Clojure arity sentence |
| Large collection | `(vec (range 1600))` | 56 | 0 | 32 visible members; `(drop 32 result/...)` |
| Large string | `(apply str (repeat 5000 "a"))` | 1,591 | 0 | Large-value finding and `subs` continuation |
| Large output | `(dotimes [i 550] (println "audit" i))` | 1,575 | 3 | Large-output finding, saved-output continuation |
| Slow form | `(do (Thread/sleep 1100) :done)` | 120 | 1,105 | Deliberately awaited 1.1 s; `:slow` |
| Value def | `(def x 1)` | 117 | 0 | Persistence note |
| Refused write | `(seon.db/transact! [{:message.audit/unknown 1}])` | 146 | 1 | Error returned as `:value`; MCP `isError=false` |
| Documentation | `(doc seon.repl/text)` | 167 | 4 | Useful contract plus historical commentary and empty example |
| Reader error | `{:a}` | 86 | absent | Odd map literal; no fabricated timing |
| No assigned task | `(my.task/steps {})` | 103 | 1 | Sentence and a plan example inside fix prose |
| Profile advice | `(seon.profile/summary (seon.profile/cells) 5)` | 419 | 20 | Three lists repeat five/seven functions as sentences; 59,232 armed calls |
| Held task | `(seon.render.ns/held-task-ai {:seon.render/value {:seon.agent/id "agent-message-audit"}})` | 181 | 1 | Intent, progress, current step and all steps duplicate information |
| Narrow map | See `branch-identity` in probes | 1,567 | 0 | Entity dispatch expands two keys into history; cuts operations |

The deliberate slow form is a probe of the warning, not a performance regression. No other measured probe evaluation exceeded one second. MCP wall time includes acquisition and settlement; those phases were not separately profiled. Initial runtime heap observations: approximately 1,229 MB used, 505 MB live, 10,997 MB max. Those are runtime-wide observations, not memory attributed to this audit.

## Rules: ten lines

1. Return data; prose states only what the data cannot.
2. An error is one flat map with type, operation, one fixed short message, and declared members.
3. Keep values in data keys; never print them again inside Seon-authored message text.
4. Use Clojure words: function, Var, namespace, argument, map, key, seq, exception.
5. Preserve native Clojure/SCI error meaning; use “Unable to resolve symbol” and “Wrong number of args,” not refusal essays.
6. Offer a runnable form over the actual value when possible: `get-in`, `assoc`, `vec`, `take`, `drop`, `subs`, `ns-publics`.
7. Show guidance once or on request; emit findings only when their condition holds.
8. Show internal identity, frames, or digests only when needed to act; keep other evidence queryable.
9. Renderers return maps/vectors; one value printer prints errors, findings, output, and operations.
10. Measure estimated tokens; preserve the whole value and give a complete continuation when the printer cuts it.

“Fixed” applies to Seon's variant message. A dependency exception's original message remains evidence; do not parse and rewrite arbitrary exception strings or copy them into a second Seon sentence. For native arity/resolution errors, retain their structured count/symbol at the producer, or retain the native sentence once when those facts are unavailable. No regex extraction of prose is proposed.

| Message class | Default shown budget, estimated tokens | Detail policy |
|---|---:|---|
| Seon-authored fixed message | 16 | Facts in typed members; native exception text follows the printer budget |
| Fix | 40 for a form; 16 for prose | No generic “inspect and retry”; omit when no justified fix exists |
| Finding | 80 | Kind, observed amount, declared bound, one relevant operation |
| Ordinary reply metadata | 40 | Result handle, elapsed ms; optional keys only when present |
| Ordinary error | 160 | Keep type, operation and actionable problems; larger multi-problem errors may use 250 |
| Help | 220 | Core protocol and runnable discovery; detailed help on request |
| Task opening | 180 excluding authored task intent | Preserve owner instructions; compact derived steps/identity |
| Documentation summary | 120 | Contract and arglists retained; full documentation remains inspectable |
| Directory row | 50 | Symbol, arglists, one summary; whole directory uses normal printer budget |
| Operation | 80 | Whole form or a whole-form requery, never a cut inside runnable code |
| Ordinary entity render | 300 | Identity, relevant state, next reads; source/error evidence on request |
| Output or deliberately large value | Existing configured printer budget | No message-specific second truncator or arbitrary loss of original data |

These are proposed acceptance bounds for representative fixtures, not new runtime clippers. A long function's source or the owner's full task instructions are not shortened merely to satisfy a summary budget.

## Census: owners, routes and current representations

[The static site ledger](agent-message-audit-sites.json) captures 999 candidate expressions in 562 containing definitions/resource owners: 388 error-message entries, 40 error fixes, 6 finding-fix entries, 170 Malli `:error/message` entries, 279 literal constructor arguments, 105 AI attachments, and 11 map-literal operation producers. Entries include file/line, current source expression, source-file SHA-256, literal token estimate, proposed source/text, proposed literal estimate, family treatment and after budget. Concrete fixed-message edits are in the script's `MESSAGE_EDITS`; unedited literals explicitly compare equal. A dynamic lower bound is not a measured full message, and a family after-budget is not falsely labeled an exact count. Every value removed from interpolated prose must be retained in declared members before that edit can land. The six finding-fix entries include one declaration as well as the five actual rules. Constructor helpers and references can share one runtime producer; these are **site counts, not 999 distinct runtime messages**.

The script does not evaluate code. Dynamic expressions have only a literal lower bound, explicitly labeled; their exact before/after size is input-dependent. It excludes authored task resources, arbitrary user output, and dependency exception strings. It inventories explicit keyed sites in `src`, `resources/seon`, `script`, `bin/seon-hook` and `bin/test-check`, then the following route census covers the producers that do not use literal keys. A new arbitrary function can return any value; enumerating its possible strings is not a finite census. All such results still enter the value/exception/documentation routes below.

| Producer and source anchor | Current representation | Proposed representation / deletion | Size rule |
|---|---|---|---|
| `seon.error.refusal/{error,diagnostic,chain,references}`, `src/seon/error/refusal.clj`; per-domain sites in ledger | Typed or legacy flat errors; dates/layers, converted evidence, message fields | Existing constructor plus declared variant data; errors spec owns constructor/catch changes | E: message ≤16, reply ≤160/250 |
| `seon.instrument/violation`, `src/seon/instrument.clj:450–594`; `seon.error/{explain-problem,problem-parts,problem-sentence,scalar-text}` | “refused argument 0 `request` at … expected … got … Fix … Contract … Called from …” plus the same structured problems | Malli report → one error map; delete bespoke English grammar. Keep `:in`, value, schema and native Malli message once | 414 →156 measured example |
| `seon.error/{reply-value,rendered-reply,render-ai}`, `src/seon/error.clj:1851–2133` | Selected fields; message-only error becomes bare string; type/operation lost | Render declared variant members, type, operation; one compact map, including message-only failures | Same error bounds, no second printer |
| `seon.sci.kernel/execution-error-text`, `seon.sci.eval/{failure-text,interpreted-arity-message,rendered-evaluation}` | Native errors and stacktrace vectors; Seon translations | Native wording, authored location only where useful; structured variant data. Do not change reader/evaluator for editorial consistency | Unknown 88→50, arity 81→42 |
| `seon.sci.reader` and `seon.sci.forgive`, `src/seon/sci/reader.cljc`, `src/seon/sci/forgive.clj`; `seon.batch` | Reader exception; repairs are separate stage values | Preserve offending source and read location; concise odd-map message. Repairs remain data, not comments | Reader 86→44 |
| `seon.repl/findings`, `src/seon/repl.clj:303–363`; `resources/seon/schemas/seon.config.eval.edn:10–34` | Five kinds with repeated advice strings | Keep kind/measure; move executable advice into operations. Do not claim a large value proves a bad query | Finding ≤80 |
| `seon.repl/def-note`, `src/seon/repl.clj:140–161` | Persistence lecture on every successful Var-valued `def` | Teach true lifetime once in help. Use admission result for exceptional persistence feedback, not another reader over source | Reply 117→30 |
| `seon.repl/{response-entries,reply-map,text,render-ai}`, `src/seon/repl.clj:113–288,395,588` | Hand-composed `#:seon.repl{...}` with already-shown values spliced into it | Keep one response owner; response data reaches printer once. Coordinate with printer and one-path owners, preserve saved historical text | Metadata ≤40; value has its own share of same budget |
| `seon.repl/shown-output`, `src/seon/repl.clj:175–193` | Bounded string plus saved-output `subs` form | Keep full captured output; use a live-output symbol for the elision form when available (printer-rules S3), with stored-output retrieval only after the live value is gone. Represent finding as compact data | 1,575 current; printer budget unchanged |
| `seon.bootstrap/{help-value,render-help-ai}`, `src/seon/bootstrap.clj:17–71` | Fourteen long paragraphs, bare text | Instruction vector and discovery forms; omit tool roster duplicate; contract example uses `inc` | Reply 1,201→183 |
| `seon.cluster.instruction/{getting-started-text,instruction-ai}`, `src/seon/cluster/instruction.clj:20–71` | Separate instructional string returned verbatim, falsely says every uncontracted defn is scratch | Remove that false persistence claim; keep only distinct entry policy; source the same declared help data. Preserve caller-specific protocol, no copied tutorial | ≤80 on first entry |
| `seon.bootstrap/{task-message,supervision-tx}`, `src/seon/bootstrap.clj:206,278` | Bootstrap assignment and generated “Read …”/“What are you doing?” prose | Short assignment preserving empty-input behavior and required proof; supervision actions as existing values. Do not introduce another task model | Assignment ≤80; event ≤40 |
| `seon.render.ns/{task-ai,held-task-ai}`, `src/seon/render/ns.clj:101,160`; `seon.task/steps-view:601` | Task intent plus id/name/symbol/ns, current step repeated in full steps | Keep intent, useful identity, current step and progress; offer full steps as `get-in` | Held-task reply 181→95 |
| `my.task/{unassigned,open,claim!}`, `src/my/task.clj:15–100`; `seon.task/refused` | Literal explanation, fix embedded as example; open-task claim offers | “No task assigned.” + actual plan form; keep claim parameters as data; no imagined task ids | 103→56 for no-task reply |
| `seon.sci.eval/{documentation-value,function-doc-map,documentation-contract,docstring-parts}`, `src/seon/sci/eval.clj:1871–2112` | `:summary`, `:body`, `:arglists`, `:in`, `:out`, empty `:example`; source docstrings contain design history | Contract, arglists, result summary; omit empty members. Full docstring is still explicit detail. Shorten owner docstrings where they contain historical narrative | Doc 167→55 |
| `seon.repl/render-directory-ai:37`, SCI directory owner, `clojure.repl/{apropos,source,find-doc}` adapters | Directory rows are already data; source/find-doc may still print | Keep native discovery names; rows stay data. Preserve returned source whole unless printer cuts with a continuation | Row ≤50 |
| `seon.profile/{summary,observation-line,growth-line,explain,explain-slow}`, `src/seon/profile.clj:538–649` | Repeated lines with “inclusive total … max … x…” and proportionality sermon | One table of functions/calls/times and rank indexes; explain inclusive time once in doc | Profile 419→180 |
| `seon.mcp/{tool-table,eval-arguments,status-arguments}`, `src/seon/mcp.clj:30–75` | Tool description repeats mode/defaults and branch tutorial also present in arguments | Short purpose; argument descriptions own defaults. Teach live handles once, with `(keys result/...)` | Tool description ≤100; argument ≤40 |
| `seon.mcp/{decoded,arguments,instance,call,printed,result}`, `src/seon/mcp.clj:104–167,371–424` | JSON error wrapper, prose “Unknown tool; … names the tools,” readiness essays | Outer MCP fields unchanged; inner error map type/operation/message plus actual tools/readiness data | Error ≤160; wrapper fixed protocol |
| `seon.print/{elision-form,elision-text,render-elision-ai}`, `src/seon/print.cljc:319–410`; `seon.render.value` | `…N (drop …)`, string `…+N (subs …)`, explicit elision map printed to a string | Keep core continuation forms and correct offsets. Return explicit elision data; printer alone chooses readable syntax and counts omitted data | Continuation ≤80; small vector remains 56 |
| `seon.render.value/{insufficient-budget,compact-failure}`, `src/seon/render/value.clj:1331,200ff` | Budget prose in characters; compact failure maps | Report display size in tokens; retain numeric offsets as coordinates, not alleged token counts | Message ≤16; diagnostic ≤120 |
| `seon.error/{ai-prose,notice-ai-prose,mcp-prose,elision-prose,unclassified-prose,index-refusal-prose}` | Domain evidence interpolated into strings plus repeated “inspect/retry” | Return existing typed evidence maps; delete prose adapters with errors S-renders | ≤160/250; raw evidence via handle |
| `seon.error/{message-tx,finding,render-faults-ai}`, `src/seon/error.clj:1252,2135,2440`; `seon.fault`, `seon.problems` | Error notifications, occurrence summaries, open-fault data | Reference one recorded error; message contains its short message, not serialized error. Count repeats; show resolution | Notification ≤80, finding ≤160 |
| `seon.render/{render-call,declared-producers,unknown-ai}` and `seon.render.walk/{neighborhood,related}` | Entity dispatch, related rows, fallback prose and continuation operations | Honor requested projection; render only matching declared value. Avoid expanding an identity into all history | Narrow-map reply 1,567→41 |
| `seon.turn/{render-ai,declared-sources}`; `seon.render.transcript/{render-ai,render-run-ai,render-history-ai,render-runtime-ai,render-agent-header}` | “Run … opened … It completed” plus already-shown history strings | Structured state for run summaries; retain saved shown evaluations once. No re-clipping or repeating them inside identity render | Summary ≤80; history selected as whole units |
| `seon.test/{recorded-result,run}`, `seon.test.runner`, `seon.test.accretion/render-ai` | Unknowns, failure values, test evidence and reports | Keep unknown/test identity/status; assertion values as data, message is prose only | Test result ≤160, run summary ≤300 |
| `seon.cluster.message/{render-ai,render-inbox-ai}` and system message producers | Authored content plus envelopes, timestamps/ordinals; error text may be encoded content | Preserve authored message; compact routing metadata, reference error data rather than stringify | Envelope ≤50; no arbitrary bound on authored instructions |
| `seon.ai` provider/attempt errors and `seon.effect` / `seon.background` | Failure prose, effect “Request/Result,” background message-only error | Domain state/error map; request and result references once; no raw private reasoning/body expansion | Error ≤160; receipt ≤160 |

### Every declared AI renderer

The ledger found the following 50 attached renderer symbols. This list is a **source attachment census** (the related render audit also counts three callable history producers, for 53), not a claim that all are reachable/installed. It supplies the complete rewrite decision for attached AI render producers; exact current body expressions are reached from the ledger's attachment to the symbol, not guessed from its name.

| Renderer family (symbols share the named namespace) | Current data/text | Proposed data and budget |
|---|---|---|
| `seon.agent/render-settings-ai`; `seon.config/render-ai` | Settings map; config sentence | Keep relevant nonsecret settings; omit duplicate derived defaults in summaries, ≤300 |
| `seon.ai/{attempt-ai,model-ai,provider-ai}` | Attempt printer text; model/provider sentences | Attempt facts as data; model/provider identity and capabilities, ≤300 |
| `seon.background/render-ai` | Message string only | Full compact typed error, ≤160 |
| `seon.bootstrap/render-help-ai` | Joined instruction lines | Help map above, 183-token reply |
| `seon.cluster.agent/{render-creation-ai,render-identity-ai,render-situation-ai}` | Creation/situation sentences; identity map and related state | Requested identity stays requested; creation retains branch and next read, ≤180 |
| `seon.cluster.instruction/instruction-ai` | Verbatim string | Declared instruction data, ≤80 excluding authored instruction content |
| `seon.cluster.message/{render-ai,render-inbox-ai}` | Message/inbox envelopes and content | Authored content once; minimal routing data, ≤50 metadata |
| `seon.cluster.status/render-ai`; `seon.cluster/render-ai` | Cluster state/operations | Keep readiness and actual errors; detail on request, ≤300 |
| `seon.context/capture-ai` | Returns nil (dead attachment) | Delete the attachment; no fabricated capture summary, 0 |
| `seon.db/{render-rejection-ai,render-transaction-ai}` | Rejection map/report data and operations | Typed refusal or affected identities, basis if useful; no complete tx duplicate, ≤160/300 |
| `seon.effect/render-ai` | Receipt prose plus Request/Result text | Receipt state, operation, result/reference, ≤160 |
| `seon.error/{ai-prose,elision-prose,finding-ai,mcp-prose,render-ai,render-faults-ai}` | Mix of strings and selected maps | Typed error/finding/elision maps; delete prose variants, ≤160/250 |
| `seon.health/render-ai`; `seon.problems/{missing-model-ai,ai-prose}` | Health map; configuration explanation; ai-prose prints its own map | Actual failure/counts/actionable model setting, ≤300/160 |
| `seon.print/render-elision-ai` | Serialized elision map | Elision data to value printer; core requery form, ≤80 |
| `seon.render.ns/{agent-namespace-ai,function-ai,held-task-ai,render-ai,render-alias-ai,render-import-ai,render-refer-ai,task-ai}` | Namespace/function/task maps | Retain Clojure identities and relationships; remove duplicated task state, ≤300/180/50 as applicable |
| `seon.render.schema/render-ai` | Schema definition data | Keep actual schema; explanation only on request, printer budget for large schema |
| `seon.render.test/{render-ai,render-run-ai}` | Test definition/proof/run data | Test identity and current result; failure data; ≤160/300 |
| `seon.render.transcript/{render-history-ai,render-run-ai,render-runtime-ai}` | Historical text/run/runtime | Whole saved units once; small runtime state, ≤80 summary; delete empty-string render-history-ai attachment |
| `seon.render.value/{render-ai,render-database-identity-ai}` | Value text; database identity | Keep sole printer; database identity only when useful for subsequent read, ≤80 identity |
| `seon.render.var/render-ai` | Var symbol, persisted definition/check status, operations | Keep actual Var/admission/check facts; remove boilerplate note elsewhere, ≤120 |
| `seon.render/unknown-ai` | Typed unknown/fallback | Preserve unknown status and why, ≤120 |
| `seon.repl/{render-ai,render-directory-ai}` | REPL text and directory rows | One response map; directory rows already appropriate, ≤50/row |
| `seon.test.accretion/render-ai` | Entry/gate outcome | Verdict and actual findings; no repeated contract tutorial, ≤160 |

Unattached helper renderers also appear in the route census: `seon.ai/registry-ai`, `seon.problems/stale-var-ai`, transcript `render-ai`, and error prose helpers. Value-owned `:seon.render/ai` overrides and user-defined render functions use the same dispatch and budget; their arbitrary content cannot be exhaustively enumerated ahead of evaluation.

### Offered operations and envelope keys

Operation producers beyond literal maps: `my.program/{propose-merge!,proposals,prepare-merge!,accept-merge!,write-back!}` (`src/my/program.clj:469–598`); `my.task/open:51`; `seon.task/{assign!,steps-view}:494,607`; `seon.instrument` contract warning `:748`; `seon.agent/render-settings-ai:169`; `seon.cluster.agent` identity render `:299`; `seon.cluster.status/render-ai:173`; `seon.cluster.message` inbox/message `:366,632`; `seon.db/render-transaction-ai:5808`; `seon.error/finding:2191`; `seon.health/render-ai:672`; `seon.render.{var,transcript,walk}` at `:139,:956,:1263`. Keep the existing forms, parameters and authority. The rewrite is structural: one complete form, with values carried as data. The observed `(seon.db/pull '…1 ...)` is not an offered runnable operation. Executed elision checks returned `[730 32 true]`: saved-output suffix count 730, first omitted vector value 32, and `(ns-publics 'user)` contains the actual scratch Var. Thus the current output elision works, but still violates the new preference for a live-value core form. This is the existing task `seon.render.value/a-cut-operation-in-render-data-is-not-runnable`.

Outer MCP protocol stays `content` → `[{type "text", text ...}]`, `isError` boolean (`seon.mcp/result:127`). A thrown evaluation error has `isError=true`; a returned domain refusal can be `:value` with `isError=false`, as observed. Do not silently change that meaning in an editorial slice.

The REPL currently orders these keys: `:seon.repl/findings`, `interrupted`, `result`, `out`, `note`, `ns`, `ms`, `calls`, `repeated`, `errors`, `diagnostics`, `value`, `error`. Keep full keyword identities; a namespaced map is Clojure reader syntax, not a second alias protocol. Omit absent optional keys, retain false/zero, preserve value when later rendering/recording fails. Do not collapse a diagnostic into the evaluated error or hide a returned error inside a Var.


### Outside-JVM and edit-feedback producers

These also show messages to outside agents, so they cannot be omitted from the census:

| Producer | Current text/data | Proposed | Budget / proof |
|---|---|---|---|
| `script/seon/dev/mcp.clj/{static-tools,start-remedy,call-tool,failure-text,error-result}` | “No live Seon JVM for cluster … under …: run …”; timeout sentence says outcome unknown **then suggests retry**; bridge failure copies cause into prose | Flat bridge error data with cluster/root/pid/bound/outcome; operation to inspect status. Preserve unknown outcome; never recommend repeating a possibly-effectful evaluation just because transport timed out | ≤160; bridge fixture through real MCP transport, process outcome unchanged |
| `bin/seon-hook/{format-clj-kondo-findings,contract-compile-refusal,findings-feedback,schema-admission-feedback,clip-feedback,markdown-feedback,docstring-feedback,derived-write-feedback}` | Heading, one sentence per finding, count cap and second string clip; duplicated source in reasons | Finding vector of rule/span/severity/message; whole result to shared printer, one elision form, no copy of patch/source values into prose | ≤250 feedback fixture plus printer cuts; printer-audit E03/A001–A009 own conversion |
| `bin/seon-hook/{build-review-prompt,render-file-block,load-rubric,load-skills,run-review-worker!}` | Review instructions and serialized/cut file blocks; review currently disabled | Whole declared instruction units and exact selected source data; no hidden quota or invented truncation. Keep review-disabled state explicit | Configured whole-unit budget; do not launch paid reviews for this audit |
| `script/seon/dev/{markdown,docstring}.clj` result formatters; `bin/test-check` | Findings as lines and “(truncated)”; exceptions printed as complete values | Native findings/report map through the one available printer; transport JSON/EDN encoding remains paired with reader | ≤250 ordinary report; off-JVM availability must be proven, not an invented fallback printer |
| `script/seon/operator.clj` status/check/refusal output and shell launchers | “SEON status OK” even when nested health says faulted; phase/test maps, literal shell errors | Status headline reflects actual degraded state; map carries counts/outcome/phases, literal shell usage stays concise | ≤300 summary; `seon.operator/status-prints-ok-while-the-cluster-is-still-starting` covers headline family |

A no-JVM bridge cannot call the JVM printer. Prove the existing CLJC core loads in its Babashka world, or keep a declared transport error value for the client printer; do not add a handwritten compact EDN serializer. The source ledger includes the script/bin constructor sites; unkeyed headings, prompt templates and forwarding functions are explicitly owned in this table and in printer-audit's full site ledger.

## Concrete rewrites, ranked

No live usage histogram was queried; a frequency claim would be invented. The explicit workload below is **planning input per 100 evaluations**, not production telemetry. Rank by assumed occurrences × observed complete reply tokens. Repeated historical context can amplify every row further. Compare proposed text in the probes file; all after text is illustrative target output, not output from an installed patch.

| Rank | Producer/example | Assumed occurrences | Before → after tokens | Occurrences × before | Tokens saved |
|---:|---|---:|---:|---:|---:|
| 1 | Narrow map expanded into agent/history | 5 | 1,567 →41 | 7,835 | 7,630 |
| 2 | Contract refusal | 10 | 414 →156 | 4,140 | 2,580 |
| 3 | Profile advice result | 8 | 419 →180 | 3,352 | 1,912 |
| 4 | Repeated value-def note | 25 | 117 →30 | 2,925 | 2,175 |
| 5 | Held task opening | 15 | 181 →95 | 2,715 | 1,290 |
| 6 | Refused write | 15 | 146 →75 | 2,190 | 1,065 |
| 7 | Contracted function doc | 10 | 167 →55 | 1,670 | 1,120 |
| 8 | Unknown symbol | 15 | 88 →50 | 1,320 | 570 |
| 9 | Help | 1 | 1,201 →183 | 1,201 | 1,018 |
| 10 | Slow finding/reply | 8 | 120 →63 | 960 | 456 |

Other measured rewrites: reader 86→44, native arity 81→42, no-task reply 103→56. Do not rank ordinary user-requested bulk strings/output as editorial waste: their 1,591/1,575-token replies mainly contain the requested content. The findings around them can shrink without deleting the content. The concrete proposed outputs are editorial fixtures: all 13 read successfully with the Clojure reader; all 21 captured replies have independently recomputed token estimates. Reading a proposed form is a syntax check, not proof of installation or that every offered operation is implemented.

Representative current → proposed **wording**, with values moved to data:

- Contract: “seon.sci.admit/admit refused argument 0 `request` at […] … Fix: Supply … Contract: …” → `:seon.error/message "Invalid arguments."`, `:seon.error/operation seon.sci.admit/admit`, Malli problems with `[0 key]` paths. The concrete proposal preserves all three problems. It does not recommend setting an unbounded flag merely to satisfy the first alternative.
- Write: “seon.db/transact! refused transaction data at [0 :message.audit/unknown]: expected an installed attribute, got an undeclared attribute … Fix: Use …” → `:message "Undeclared attribute."`, operation, type and problem path. A random generated `:example :N/j` is not a valid repair; remove it.
- Def: “x lives only in your SCI context … Nothing defined with def is persisted … temporary data-modeling experiments …” → no per-def note; help states true lifetime once. A function-valued def is governed by admission, not this slogan.
- Unknown: “Unable to resolve symbol: message-audit-missing” plus internal `identity` frame → message stem, offending symbol, `(ns-publics 'user)`. Do not offer `(resolve 'missing)` as if it repairs a missing definition.
- Reader: “The map literal starting with :a contains 1 form(s). Map literals must contain an even number of forms.” → “Map literal must contain an even number of forms.” plus source and location if available. Never invent the missing value.
- Slow: the 70-token proportionality lecture → existing profiling form as an operation. Its summary is JVM-wide and includes concurrent work; **it does not identify the responsible call by itself**. Use the evaluation's recorded call evidence for attribution.
- Large value: “A value this big usually means a query or function returns more than it should: select what you need.” → kind and measures, with the printer's actual `take`/`drop`/`subs` continuation. Do not emit a generic form whose symbol is unbound.
- Large allocation: “Something realized far more than it returned (an unbounded lazy sequence, a whole-store read, a copied collection) …” → observed allocation, bound, call evidence. Allocation alone proves none of those proposed causes.
- Replaced definition: “The program advanced over these definitions of yours …” → the replaced symbols and a short “Definitions changed.” Preserve a redefinition operation only if the actual authored source is available.
- Large output: “Printed output is saved and re-read in every later context: return the value instead of printing it.” → “Return the value to inspect it.” once, alongside the kind and output-token measure. Printing is sometimes the intended operation.
- Render-walk elision: “Additional render-walk content was elided by the active render profile … Request the next offset …” → count/bound/path and one complete continuation form. Offsets stay coordinates; displayed **sizes** use estimated tokens.
- Bootstrap task: “Define a durable contracted function named largest …” → “Define contracted `largest`: greatest `:example/amount`, or `{}` for empty input. Call it, read its stored contract, then report the result.” Preserve all required behavior; no additional task machinery.

The concrete typed Malli proposal depends on the reviewed errors cut. The unknown/reader/arity examples show the compact content selection; their final variant/type/operation must be supplied by their installed producer and counted when that producer conversion lands. Their smaller numbers are therefore editorial comparisons, not passing acceptance-test budgets for the complete future typed variants.

## Dependency and printer coordination

Authority: [errors spec](../../../../seon/specs/errors.md), especially §5, P1–P4, cut and S-renders; CLAUDE Presentation. Read the concurrently landed [render audit](../printer-rules/render-audit.md), the [printer contract and enforcement proposal](../printer-rules/printer-audit-printer-rules.md#general-printer-contract--fifteen-rules), and [Opus printer rules R1–R14](../printer-rules/printer-rules-opus-printer-rules.md#part-2-general-rules-for-the-one-value-printer). The directory was empty at the initial inspection; these are now the explicit handoff. No foreign files or sessions were edited or messaged. Their source classifications corrected the table above: config/model/provider/situation/creation return sentences; capture and history attachments are dead.

Shared boundary: message owners produce small typed maps; the printer owner controls layout, one budget, atomic offered forms and continuation syntax. Errors print through that same value printer as compact maps. Do not add an error-specific serializer, printer, cache, limiter, result store or renderer registry. Honor fully qualified schema names and the existing world supply. There is no permission to edit the other lane's owned files.


The printer proposals agree on one budget and fixed messages but differ on some enforcement exemptions. This audit follows CLAUDE's actual law: a small numeric value is still a value and belongs in data; no namespace-wide exemption licenses a second printer. Use **elision form**, plain `clojure.core` over the live result, lazy first. No `seon.print/value-at` in an offered form. Keep saved historical text unchanged. For live output, do not silently change `result/e...` from the evaluated value into an envelope: the printer owner must choose a separate held-output binding or another compatible form, then prove it through MCP. Slice 12 includes that S3 dependency.

The render audit permits bare help/background prose as genuine text; this message proposal is deliberately stricter for these structured cases: help has runnable discovery operations, and background failures must retain variant members. An authored instruction string and already-saved historical text remain text. The AI render itself must not call the printer: `seon.ai/attempt-ai` should return its pruned **value**, not the unit wrapper; `seon.problems/ai-prose` should return its derived data and operations. Contract output schemas, not a second render mechanism, change with those edits.

Pinned dependency evidence:

| Pin and seam | Guarantee / caller / input | Recompute and cost |
|---|---|---|
| Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, `reference-code/malli/src/malli/core.cljc:2210–2240`, `-instrument-f` | Supplies invalid-input/output/guard/arity/schema-at-call reports; Seon `violation` consumes schema, arguments and value. Throw the report error to prevent invalid input entering the function | Validation each armed call; explain only refusal; proportional to checked structure, not every program definition |
| Same pin, `malli.error/error-message:288`, `with-error-messages:332ff`, `with-spell-checking:343ff` | Native error wording, `:in`, spelling evidence; do not recreate English or edit distance | On refusal; retain compiled schemas/explainers through their dependency cache |
| SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`, `sci/impl/resolve.cljc:332` | “Unable to resolve symbol”; called during analysis of submitted forms | Once per failed resolution, preserve source location |
| Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, `clojure/lang/ArityException.java:29` | Native arity wording; do not substitute “refused argument count” | Exception construction; no registry scan |

Deletion plan: `problem-sentence`/bespoke schema English retire with the errors adapter; prose AI renderers retire with S-renders; repeated `def-note` and copied help paragraphs disappear; profile string rows become data. Projection and printing should be proportional to selected data and printed budget. Do not promise a measured speedup: this lane measured reply sizes, not a candidate implementation.

Existing task mapping: `seon.instrument/contract-refusal-carries-wrapper-bookkeeping`; `seon.error/a-missing-key-message-reads-badly`; `seon.error/an-error-render-drops-the-members-that-say-what-is-missing`; `seon.error/error-messages-print-growing-values`; `seon.repl/every-def-repeats-the-same-persistence-note`; `seon.bootstrap/help-teaches-the-wrong-tools`; `seon.render/a-renderer-is-chosen-by-key-presence`; `seon.render.value/a-cut-operation-in-render-data-is-not-runnable`. The audit's work task is `user/work-of-agent-message-audit`, minted handle `2e789c957098`. Existing task references avoid duplicate issues. The baseline Boolean ISeq failure was recorded through the task writer as `seon.flow/var-process-reports-an-iseq-error-without-a-task`, handle `dbf4c0eff700` (54 ms); no cause is asserted and it is not assigned until a failing proof exists. New implementation tests below must be attached to their existing task before assignment.

## Ordered slices, approximately fifteen minutes each

These are bounded editing/proof slices, not a promise that boot-critical error conversion fits fifteen minutes. Stop at the named boundary; the errors cut retains its own dependency-complete rollout. All test names below are **proposed** except the audit landing check. No test is claimed installed or green merely because it appears here.

Every proof enters `seon.mcp/call` with an `eval_clj` JSON request naming the test's own agent/cluster/SCI mode (existing entrance pattern: `test/seon/mcp_entrance_test.clj:26`). Use the same recorded response data that produces the MCP text; assert its narrow data keys, then `seon.ai.tokens/estimate` on the returned text. Do not parse the current elided display as EDN, implement another reader, or test private producer strings as a substitute for entrance behavior. This needs a small support extension to expose the entrance's existing response value when it currently exposes only shown text; it is **not** permission to create a second response model. If a definition is host-bound, prove via the candidate archive's isolated JVM.

| Order / slice | Owned files, when available | Proposed proof var and data assertion through MCP | Token bound / stop boundary |
|---|---|---|---|
| 0. Expose entrance response evidence | `test/seon/mcp_entrance_test.clj`, existing `test/seon/test_support.clj` | `seon.mcp-entrance-test/message-proof-reads-the-same-response-value`: response's result handle/value matches saved evaluation and MCP text; use production response owner | No alternate formatter; reuse one fixture. Coordinate with current owner first |
| 1. Preserve narrow requested values | `src/seon/render.clj`, `test/seon/mcp_entrance_test.clj` | `.../a-selected-agent-map-stays-selected`: the two requested keys remain the data shown, no attached history; returned live value unchanged | ≤80 for narrow map. If dispatch redesign exceeds slice, hand to existing render task |
| 2. Retain error identity | `src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`, entrance test | `.../a-contract-error-keeps-type-operation-and-problems`: invalid call shows actual type/operation plus three problems | ≤250. Keep existing type until errors cut; do not tighten producer contracts piecemeal |
| 3. Remove repeated def lesson | `src/seon/repl.clj`, `src/seon/bootstrap.clj`, entrance test | `.../defs-do-not-repeat-lifetime-help`: two value defs have no repeated note; admitted function-valued def is not labeled unpersisted | ≤60 per def. No new session atom |
| 4. Compact help data | `src/seon/bootstrap.clj`, `resources/seon/schemas/seon.help.edn`, entrance test | `.../help-is-data-with-runnable-discovery`: map/vector instructions, declared examples, no false NEXT-turn requirement for MCP | ≤220. Preserve function/test declaration guidance |
| 5. Short finding data | `resources/seon/schemas/seon.config.eval.edn`, `resources/seon/schemas/seon.repl.edn`, `src/seon/repl.clj`, entrance test | `.../findings-name-measures-and-runnable-operations`: large string/output and controlled slow call expose kinds and measures, operations are forms | ≤80/finding plus content printer budget. Slow test uses a bounded injected external wait, not sleep as production synchronization |
| 6. Contract wording adapter | Errors-owner slice: `src/seon/instrument.clj`, `src/seon/error.clj`, owning schema files, entrance test | `.../contract-errors-use-malli-problems`: input/output/arity rows preserve native Malli path/value/schema; invalid input prevents entry | ≤250 with three problems. Implement as part of errors P4/cut; do not fork that work |
| 7. Refused-write wording | `src/seon/db.clj`, `resources/seon/schemas/seon.db.write.edn`, entrance test | `.../an-undeclared-attribute-is-one-error`: actual write basis unchanged, type/operation/path retained, no invented example key | ≤160. Convert only this class; remaining writer errors stay with errors owner |
| 8. Native errors and reader wording | `src/seon/sci/kernel.clj`, `src/seon/sci/reader.cljc`, `src/seon/sci/eval.clj`, entrance test | `.../native-errors-keep-their-clojure-meaning`: resolution, arity and odd map input return error data; location retained where authored | ≤160/row. Split files into separate slices if ownership/cut requires |
| 9. Doc summary | `src/seon/sci/eval.clj`, docstring owner `src/seon/repl.clj`, entrance test | `.../doc-keeps-contract-and-omits-empty-sections`: real contracted fn's input/output/arglists present, empty example absent | ≤120. Full body remains inspectable |
| 10. Task opening | `src/seon/render/ns.clj`, `resources/seon/schemas/seon.task.edn`, entrance test | `.../task-opening-keeps-intent-and-current-step`: canonical task/step writers, owner intent unchanged, complete full-step read offered | ≤180 overhead excluding intent. No handwritten task entities |
| 11. Profile table | `src/seon/profile.clj`, `resources/seon/schemas/seon.profile.edn`, entrance test | `.../profile-summary-returns-call-data-once`: same known profile facts represented once; all rank selections preserved | ≤220 for seven functions, no string rows. Isolate injected measurements from live concurrent counters |
| 12. Atomic operations / elisions | Printer owner: `src/seon/print.cljc`, `src/seon/render/value.clj`, entrance test | `.../offered-operations-remain-runnable`: every shown operation reads and runs on own branch; `drop`/`subs` continuation recovers exact suffix | ≤80/continuation; value uses configured budget. One printer, no second clipping |
| 13. Domain prose renders | Sequential one-family slices in `src/seon/{background,effect,error}.clj`, each owning schema and entrance test | `.../domain-errors-retain-their-variant-members`: table of that family's real failures exposes discriminant, operation and domain members | ≤160/error. One family per slice after errors S-renders dependencies |
| 14. Tool descriptions and transport failures | `src/seon/mcp.clj`, entrance test | `.../mcp-descriptions-and-failures-are-compact`: tools' schemas/defaults unchanged; malformed/unknown-tool calls keep outer error flag and inner error data | ≤100/description, ≤160/error |

After slice 14, take one additional ~15-minute checkpoint each for bridge error values (`script/seon/dev/mcp.clj`, proposed `bridge-timeout-keeps-unknown-outcome`, ≤160), hook feedback data (`bin/seon-hook`, proposed `hook-feedback-keeps-finding-data`, ≤250), and CLI report data (`bin/test-check`, `script/seon/operator.clj`, proposed `operator-report-keeps-health-state`, ≤300). These proofs execute the external script in the fixture and inspect its decoded result through MCP, with the actual script reader/transport preserved. They share printer-audit E03/A001–A009 ownership and must not duplicate those conversions.

Schema changes publish and adopt with consumers on a disposable live-store branch before landing. Tests name current variants; targets are not assumed installed. Convert existing text assertions only with replacement data proofs. In particular, `test/seon/instrument_test.clj:594,723,1537` contains wording assertions/golden coverage that the contract slice must address. Current foreign entrance/transcript edits are a file-ownership dependency, not a reason to stop the audit. Broad tiers remain root's work.

## Landing

Documentation and evidence only; source/test line delta **0/0**. No runtime implementation claim, no restart, no default JVM definitions, no provider requests, no worktree, no amend. The census/probe artifacts and this report are the lane's only candidate paths.

Required working-tree lint: `clj-kondo --lint src script test --cache false` completed in **6,987 ms**, **0 errors**, **1,455 warnings** (exit 2 from warnings). This is a repo-wide read-only lint, not an ordinary REPL operation; its cost follows the scanned source/test corpus. The warnings were not silently counted as a clean lint. The required named candidate check passed: run `e3b62455c25e`, executed 1, reused 0, assertions 2 passed / 0 failed / 0 errored; test body 1,510 ms, test phase 1,601 ms. Total check 82,816 ms: queue 6,021; archive 1,065; classpath 644; prepare 9,078; share 1,306; JVM ready 48,489; boot phase 54,535; source 3,015; publication 8,511; adoption 1,329. Candidate source SHA `cb3be55cac3d4de6c96561d3247cb6f2d2cff65f`, 328 adopted identities. This explicit `--test` check rehearsed HEAD despite documentation-only inputs: existing tasks `seon.operator/check-decides-documentation-by-input-roots` and `seon.operator/check-rehearsal-cost-follows-drift-not-the-change`. This is a tooling defect/cost, not a justified ordinary operation and not a message-edit regression. The user explicitly requires this gate for landing; the audit does not waive it or change the operator outside its assignment. `bin/lane commit` repeats the required check and records its final result in the commit command output.

Required landing test: `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings` (`test/seon/contracts_compile_test.clj:961`). This checks the documentation/no-source boundary; it does not prove proposed message changes. No broader test namespace was run, so no claim about its pre-existing reds is made. The existing help evidence task records `seon.help-test/help-evidence-tracks-its-code-version` red on a prior HEAD; it was not rerun or attributed to this audit.
