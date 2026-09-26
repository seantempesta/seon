---
type: audit
created: 2026-09-26
status: defects confirmed; source census complete within the stated grammar; destructive delivery proofs not run
scope: src/, script/seon/dev/mcp.clj, dependency mechanisms, and the 106-instance prior census
source-commit: 60d323a081669aa01762cc1e8f09b714bb8f12e0
---

# Error delivery to outside and Seon agents

Seon already constructs flat refusals, retains Throwable evidence, records fault occurrences, and has two paths that show newly recorded errors. The smallest repair is to carry those ordinary values through the existing evaluation, settlement and rendering owners, preserving the evaluated result and attaching diagnostics once.

**The new law is not met.** A live SCI evaluation returning `{:audit/value 42 :seon.render/ai 'audit.missing/renderer}` lost its value in the shown response, returned a renderer error with MCP success, and recorded no branch error. An ordinary nested exception arrived as two lines of prose without either ex-data map. A different agent's recorded core fault reached MCP, but only as a message/signature summary. Recording, showing a message, and delivering the error as data are three different guarantees; current code often proves only one.

## Evidence and limits

CLAUDE.md was read end to end before inspection. Its four error laws, including **“The REPL path is total; faults are loud beside the result”**, govern this report. A historical design's panic rule does not override the new law. The requested `lost-error-seams-design-2025-09-25.md` does not exist; the corresponding file is [lost-error-seams-design-2026-09-25.md](lost-error-seams-design-2026-09-25.md), based on [the 106-instance audit](lost-errors-audit-2026-09-25.md). Task proposals follow [language requirements §7](language-requirements-2026-09-24.md#7-where-task-information-lives-owner-2026-09-24), with identity correction §3b.6.

Source references below are **at `60d323a08`**, not a moving checkout. An unmodified `git archive` snapshot supplied the static census. All 104 Clojure source files in scope parsed under the JVM reader feature; no source was evaluated by that census. Appendix A inventories constructor/carrier owners and contract evidence, Appendix B inventories every catch, and Appendix C contains the reproducible census program. These are source observations, not 104-file installed proofs. Generic throw carriers and delegated constructors prevent equating a textual count with an exact count of semantically undeclared escaping errors.

The live default was PID **80197**, start instant **2026-09-26T17:26:52.059Z**, archive **39ee6f3f332b2ae2ff3421f5447a40ee95d02330**. `bin/seon status` and MCP `runtime_status` agreed on `:faulted`: one open signature, four errored receipts, one unowned namespace, one agent/three procs. The existing signature was `fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3`, a `ClassCastException` attributed in status to `seon.schema/valid-candidate-value?`. It predates this audit. Startup's reported readiness was 17,831 ms; status also reported pre-existing operations above ten seconds. Those are degraded baseline evidence, not costs incurred or excused by this audit.

Only serial small probes were made: JVM read-only forms, and ordinary mistakes in the audit's own branch `audit-error-delivery-astra-20260926`. Its fork commit was `6ab802ac-330a-5744-8173-462f3322b226`, basis **536870995**. Retirement returned `:seon.dev.mcp/retired? true`. No tests, provider requests, fault injection into another agent, process signals, reloads, resets, or worktrees were used. Gate/test execution, recording failure, actual background-proc failure, and failed boot were therefore **not experimentally induced**. Their delivery rows below distinguish source traces from live proof. No full paid next model context was generated; its receipt-to-transcript function was called read-only.

Tool wall times: JVM exception 0.2 s; branch creation 0.7 s tool group including a source read; SCI exception 0.9 s; contract refusal 0.3 s; definition refusal 0.6 s tool group including a source read; renderer refusal 0.8 s tool group including a source read; branch retirement 0.7 s tool group including a source read. Read-only receipt/query/transcript evaluations reported **3–4 ms** host execution. These coarse tool-group timings are not isolated owner benchmarks. Heap/allocation and per-armed-function timings for the SCI probes were unavailable in their replies; no performance pass is claimed. Static census ran in 0.75 s shell wall time. Source/test changes: **0/0 lines**. No publication or adoption was requested.

During inspection other lanes changed `render.clj`, `render/value.clj`, `sci/kernel.clj`, MCP and subsequently more owners. Those changes were not edited, reverted, or treated as installed fixes. New task `6b8d26a16ce7` (`8fc41d895`, `resources/seon/tasks/seon.fault.edn`) already calls for deleting the panic/record policy; reuse it. The report's baseline remains fixed so owners can falsify each finding against their actual diff.

## 1. Learn the right way

| Mechanism and pinned source | Guarantee and cost | Adopt / retain in Seon |
|---|---|---|
| Clojure `ex-info`/`ex-data`; `Throwable->map`, `reference-code/clojure/src/clj/clojure/core_print.clj:473`; `clojure.main/ex-triage`, `main.clj:207`, `ex-str`, `:268`. Gitlink `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`. | `Throwable->map` preserves class/message/data per cause, each link's top frame, and root trace. `ex-triage` derives phase/location and a summary; it is **lossy**. Work scales with cause links plus root frames; no inherent bounded traversal or suppressed-exception traversal. | Keep Throwable until one boundary datafies it. Use triage as an optional human face, never as the sole error record. Seon's `error.refusal/chain` already adds first-party frames per link. Preserve suppressed failures too; a cleanup failure is not in `ex-cause`. |
| [Cognitect anomalies](https://github.com/cognitect-labs/anomalies#rationale), first-party README, retrieved 2026-09-26. | Open maps separate information from exception unwinding. Categories include incorrect, unavailable, interrupted, busy, unsupported, not-found, conflict and fault; they distinguish caller correction from callee repair. No delivery/durability guarantee. | Adopt the distinction in each producer's declaration and handling, not a new universal Seon category stamp or error predicate. Dependencies' native categories remain in their original ex-data. Schema identity and the producer's declared alternatives govern Seon. |
| Malli `explainer`/`explain`, `reference-code/malli/src/malli/core.cljc:2661`, `:2678`; `humanize`, `error.cljc:374`; instrumentation report, `core.cljc:2229`. Gitlink `a89ebef0572628b9198b825e5aa8b59484902593`. | Explanation is `{schema,value,errors}` with paths and offending values; humanization derives messages. Reuse compiled explainers, recomputing only for changed schema inputs. Validation traverses the checked value; do not re-explain a whole world to report one refusal. | Retain the explanation's data and declared schema form, printing only at the face. The current wrapper correctly throws through Malli's report to prevent invalid input entering the body; it must not turn explanation machinery failure into an undeclared bare-base success. |
| Datahike `db.utils/entid` and `entid-strict`, `reference-code/datahike/src/datahike/db/utils.cljc:116`, `:149`; lookup-ref failures `:127–146`. Gitlink `19ad462aa67763049d49756b5fdc0f258a2830d8`. | Exceptions carry structured `:error`, `:entity-id`, attribute/context evidence. Writer failures and unsuccessful lookups are not the same as absence. Cost follows the failed operation. | `seon.db/dependency-error` is the correct adapter owner. Retain native data and cause; adapt once into a declared Seon error. Do not parse the message or treat any map returned by `pull` as an entity. |
| SCI location wrapping, `reference-code/sci/src/sci/impl/utils.cljc:62`, `:143–182`. Gitlink `7d28561e0b8b94fa08227dc80c7a8c43bc8aa308`. | Carries `:type :sci/error`, file/line/column and callstack, wraps the original Throwable as cause. A callstack can contain live holders, so serializing an SCI context wholesale is not implied. | Preserve source location and cause as data once. A Java wrapper frame is not the author's source location. Do not regenerate location from triage when a faithful SCI location already exists. |
| prepl `reference-code/clojure/src/clj/clojure/core/server.clj:194–260`; nREPL `middleware/interruptible_eval.clj:117–124`, `middleware/caught.clj:40–56`. nREPL gitlink `f3a044153eaa6050e7447655dab5bc42cfdd68c2`. | prepl separates `:out`, `:err` and terminal `:ret`, whose `:exception` distinguishes a thrown evaluation. nREPL conveys `:eval-error`, `:ex`, `:root-ex`; caught middleware has an explicit Throwable printing option. These protocols do **not** guarantee a complete cause map by default. | Adopt explicit terminal outcome and separate diagnostics/output, while making the error ordinary data inside the existing Seon reply. Neither stderr nor MCP `isError` is the error payload. Do not copy prepl's replacement of a value when printing fails: Seon's newer totality law requires preserving it. |
| Flow `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:102`, `:313–319`. Gitlink `dc35f3e0d7bc2eef502e77982f48641f025c8051`. | Loop reports carry pid, operation, channel, count and Throwable. The default error channel uses a sliding buffer of 100; it is not a durable fault log. | Seon's per-proc fault port (`flow.clj:355–385`) already avoids that dropping route. Retain synchronous owner disposition and identity references. An unread FutureTask or stderr-only fallback still loses delivery even when Flow catches the original exception. |

### One recommended agent-facing error shape

**One flat map satisfying one named extension of `:seon.error/base`, with the producer's own fields and complete native cause evidence.** Existing members are sufficient for the normal case:

```clojure
{:seon.error/at #inst "2026-09-26T00:00:00Z"
 :seon.error/layer :seon.sci.kernel/evaluation
 :seon.error/operation seon.sci.kernel/failure-value
 :seon.error/message "The evaluation threw."
 :seon.error/location <declared source location>
 :seon.error/chain
 [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"
   :seon.error/message "audit outer"
   :seon.error/data {:audit/operation :read-only :audit/value 7}
   :seon.error/frames <this link's Seon frames>}
  {:seon.error/throwable-class "clojure.lang.ExceptionInfo"
   :seon.error/message "audit inner"
   :seon.error/data {:audit/cause :sentinel}}]}
```

This is schematic, not an admitted example: angle-bracket values must satisfy their named schemas. A refusal additionally carries the actual operation/member, expected requirement, offending value or existing result/blob reference, and a concrete fix when known. Do not invent a fix for an unexplained core failure. Keep missing facts absent; explicitly represent unavailable evidence. Keep symbols/keywords native. Producer identity belongs to the declaration/provenance already passed through settlement, not a new discriminator. Distinguish the fault's error value from its signature and occurrence records.

A successful evaluated value remains that value. Later diagnostics are an optional collection on the **existing evaluation record**, not an error wrapper substituted for the value and not a second result pipeline. Its schema must be declared when implemented; this audit does not introduce a new undeclared key. Both faces render the value and those flat diagnostics using the same floor, with retrievable cuts. Causal storage should preserve the complete admitted evidence once; do not duplicate the whole error under `data`, `offending`, `shown` and a wrapper chain. Live worlds are referenced by existing agent/branch/commit/evaluation/result identities.

## 2. Audit findings

### 2.1 Producers and declaration failures

The census found **563 constructor/carrier-owning definitions across 79 files**, containing **704 constructor calls** (451 `ex-info`, 253 calls named `diagnostic`) and **164 base-shaped map literals**. Those 868 observations overlap: a literal inside `ex-info` is not a second incident. Two generator definitions are included and identified by name in Appendix A. Factory calls that merely forward existing data are carriers, not automatically new error families.

There are **306 named schema keys** statically extending the base, including aliases. **408 of the 563 owners have no named error-schema reference in their own written contract; 349 have none anywhere in their definition.** These are exact declaration-evidence gaps, **not 408 proven undeclared agent errors**. A helper may receive a declaration from a caller; a thrown carrier may be normalized by its boundary. Conversely, a contract mentioning a named schema does not prove its body constructs that schema, forwards the correct arity union, preserves metadata, or gets recorded. Appendix A marks every owner accordingly instead of turning unknown into green.

Eight concrete declaration failures/gaps deserve producer review first:

| Producer | Model error | Evidence / consequence |
|---|---|---|
| `script/seon/dev/mcp.clj:414`, `run-form` | Hand-builds an unarmed-runtime error with no named extension or supplied declaration. | Its contract returns a source string; the generated remote value has only base/cluster fields. This is an agent-facing error producer outside the program error declaration route. |
| `script/seon/dev/mcp.clj:497`, `branch-form` | One generic map closure manufactures several branch refusals without named alternatives. | Missing runtime, own branch, duplicate branch and held branch all share the same ad hoc map. `execute-branch` then wraps the printed value. |
| `script/seon/dev/mcp.clj:354`, `evaluation-mode!` and `:626–640`, validation response | Internal `:seon.dev.mcp/failure` maps become the final error payload without a named base extension. | Input validation loses Throwable class/chain in the final reply, not merely inside a transport carrier. |
| `script/seon/dev/mcp.clj:588`, `branch-errors!` | Observer failure is only `window :unknown` plus a message. | No complete cause or named error identity at the very boundary meant to deliver faults. |
| `src/seon/instrument.clj:631`, local `minimal` in `compiled-wrapper` | Explicitly selects `:seon.error/base` as its declaration when refusal construction fails. | `error/validate-declaration!` at `error.clj:524` explicitly rejects base as an error declaration. This is a direct producer/recorder contradiction. |
| `src/seon/fault.clj:157`, `record!` missing target | Constructs a new branch-recording error with no named declaration. | Failed fault storage needs its own declared diagnostic beside the original fault. |
| `src/seon/fault.clj:175–187`, pre-fact failure | Returns a message-only synthetic fact plus the recording Throwable. | The original source remains only with the caller; this tuple alone cannot reconstruct it. It is not a full flat agent error or proof of delivery. |
| `src/seon/error.clj:1924`, `fault-entities` | Synthesizes an unavailable-fault map without a distinct declared schema. | The diagnostic reader must report a named unavailable-evidence error, not manufacture another unclassified error while rendering faults. |

**Count: eight confirmed boundary/declaration gaps**, not eight globally exhaustive undeclared families. The exhaustive syntax inventory and 408 contract gaps bound the follow-up; proving every escaping alternative needs the producer/body analysis and regressions required by CLAUDE.md. That proof is not supplied by regex, this census, or the existence of 306 schemas.

Positive seams: `error.refusal/diagnostic` at `refusal.clj:91` preserves an explicitly supplied Throwable's chain; `error/validate-declaration!` rejects an invalid/missing supplied identity; `error/declared-output-validators` at `error.clj:1769` reads a declared arity rather than guessing a returned error from every registered schema. Recent `3cf5ff927`, `c236d2d97` and `9a1c50470` moved settlement identity to producers and corrected analysis inheritance. These are real source improvements, not a claim that all producer paths now supply identity.

### 2.2 Keeping the whole cause

`error.refusal/chain` (`:78–89`) keeps every `ex-cause` link, per-link ex-data and first-party frames. It does not read `.getSuppressed`; `stack-frames` drops frames with no file. `root-frame` and `chain` walk causes without cycle detection. Ordinary acyclic Throwables work; complete suppressed evidence and arbitrary Throwable totality are not proved. `render.value/floor` separately handles up to eight links, six frames and three suppressed throwables per link (`value.clj:43`, `:96–143`), but only prints suppressed class/message, and some frame/suppressed omissions lack an explicit count. A floor's lossy display does not repair lost stored data.

The clearest remaining reductions are:

- `sci/kernel.clj:652–662`: an existing flat refusal keeps the deepest ex-data but discards newly wrapping causes/frames. `links` was already computed at `:646`, then is used only by the other arm. B05 remains.
- `turn.clj:3503`: every caught ExceptionInfo becomes a phase refusal, even an undeclared internal one; only message/deepest refusal is merged. Narrowing from Throwable fixed part of B04, not this loss.
- `call_preparation.clj:382`: validator compilation becomes an incoherent-row sentence and nil validator. At `:900`, the supplier Throwable is placed under `offending`, not consumed through `:seon.error/throwable`; class is retained, its normalized chain is not. The supplier failure then becomes unavailable input.
- `test/runner.clj:946`, `test.clj:108,1517,1520`, `cluster/status.clj:44,92,162`, `schema/admission.clj:100,366,493`, `web/jvm.clj:349`, `ai.clj:802`: message-only unknowns, findings, extraction errors or provider stream errors lose the original cause.
- `my/program.clj:33` retains class/ex-data on its undeclared read-failure arm but omits the cause chain. `instrument.clj:197,214,236` calls `failure-cause :159`, which explicitly returns only a string, so failed lookup evidence also loses its chain.
- `maintenance.clj:66`: the Throwable is buried under an offending map as `:seon.error/exception`, which `diagnostic` does not consume. `effect.clj:553` similarly retains only ex-data.
- `cluster/export.clj:224`: both clone and fallback failures become messages. `program.cljc:476` reduces projection failure to `evidence-unavailable` text.
- `schema/datahike.clj:569` catches every Throwable as malformed EDN; `schema/edn.clj:607` forwards selected data through a new refusal without the original cause. Expected malformed input and broken machinery are not interchangeable.
- `blob.clj:289`, `cluster/store.clj:334,507,515`, `cluster.clj:3353`, `cluster/export.clj:337`, `eval/drive.clj:399,413`: cleanup precedes rethrow without protecting the primary. A cleanup throw can mask the first failure. Sites using `.addSuppressed` are better locally, but the central cause serializer still loses those additions.

### 2.3 Swallowers, stringifiers and clippers

Appendix B names all **281 catch clauses**. It distinguishes direct fallbacks, explicit lossy conversions, expected rejection cases, and preserved/delegated evidence. A catch-and-return is not itself a defect: missing-file absence, a supported unavailable charset, a known branch-already-exists result, and a retained Throwable awaiting its actual owner can be valid. A broad catch around a parser is not proof that every failure was syntax rejection.

High-impact swallowers remain `ai/stream-fold` (`ai.clj:879`, sink Throwable → nil), first-party source-root discovery (`schema.clj:1048,1053`, failure → missing roots), bridge discovery/session cleanup (`mcp.clj:132,214,235`), and `run-events`/branch result parse (`mcp.clj:446,536`, Throwable → nil). `fn/analyzer.clj:854,904` supplies empty arglists/namespace fallback. The latter parser cases may have a legitimate malformed-source policy; no evidence proves independent parser faults are surfaced elsewhere. The audit flags them as unresolved, not as repaired by downstream lint.

The more dangerous non-catch equivalent is `test/accretion.clj:234–239`: a caught Exception is first retained as a value, then **every Exception** becomes `:skipped-non-generatable` and a message with zero executions. `generatable?` at `:46` also treats `::mg/no-generator` with a cause as ordinary unavailability. Malli can use that category when evaluating an authored generator itself threw (`reference-code/malli/src/malli/generator.cljc`, `-create-from-gen`). Narrow to the dependency's declared unsupported case and preserve machinery failures.

Stringification census: **48 constructor observations in 44 owners contain `pr-str`**, in Appendix A's `P` marks. Some print an identity or fixed grammar, not arbitrary values; this is a review count, not 48 confirmed unbounded errors. Confirmed value-bearing groups include:

| Owner | Remaining expansion or conversion before final delivery |
|---|---|
| `db.clj:1829,2329,2610,3177,3482,3556,4163,4581` | Argument/query/value/conflict/reference collections expanded into messages. |
| `error.clj:924,2059,2102` | Scalar/evidence and HTML diagnostics use `pr-str`; size/type does not establish a display budget. |
| `sci/eval.clj:3853,3919,4374` | Failure message and nonstring shown fallback are printed; triage is saved as a string instead of retaining the cause as the delivered value. |
| `repl.clj:153–167,192` | Error triage becomes prose and is stringified into the response; live location differs between MCP and context (probe P6). |
| `render.clj:1890–1898` | Walk constructs a full printed selected-unit map and replaces an exception with a sentence. |
| `test/accretion.clj:144,146,419,424` | Expected schema/humanization, arguments and actual failure values are printed in producers/renderers. |
| `schema/internal.cljc:485,533–540`; `schema/admission.clj:409–410` | Whole invalid schema/form/path text embedded in diagnostics before the ordinary floor. |
| `problems.clj:541,612`; `cluster/status.clj:177` | Offending values and observed status values become raw strings. |
| `background.clj:55`; `fn.clj:59–60` | Invalid result reference and rejected database entity are expanded into diagnostic text. |
| `cluster.clj:438,474`; `operator/prepl.clj:36–47` | A projection/printing failure replaces the original value with floor/error text. This is the totality defect even when text size is bounded. |
| `mcp.clj:46–51,78,547,588,691,739,936–1005` | JSON coercion/string messages have no common value-floor guarantee; symbols and keywords become strings. Hot-reload/watchdog/outer-loop failures are message-only. |

Clippers with a separate owner remain: MCP's `take 20` (`mcp.clj:44,574`); status's top selection (`cluster.clj:649–673`); error evidence admission/fitting (`error.clj:234–338,426–465`); three rendered accretion failures (`accretion.clj:443`); renderer text ceiling (`render/value.clj:699–711`). Storage admission limits may legitimately govern persisted evidence, and a renderer may deliberately show less. They must not be confused with a second presentation budget, silent loss, or unbounded work performed before clipping. The projection-free Throwable floor is an explicitly retained emergency seam; it still needs diagnostic retention and honest omission evidence, not deletion merely because it prints.

### 2.4 What each agent actually receives

“Complete” means class, message, ex-data/cause links, source location, and expected/offending/fix when the producer knows them. “Data” means the error remains a structured value at the face; every MCP text block being JSON does not make a prose `val` a structured error.

| Failure class | Claude/Codex MCP result | Seon next context | Completeness, bounds, duplication; evidence |
|---|---|---|---|
| Own exception | **Delivered as prose**, `isError:true`, ordinal and run id; no ex-data or chain. | Saved receipt is prose plus triage string; `repl/text` renders a different wrapper location. | Incomplete. Error renderer selected prose before the floor, so no cut/requery recovers what it omitted. P1/P5/P6. The actual live value may still exist under its result handle; that is not delivery. |
| Contract refusal | **Delivered as prose**, `isError:true`: function, argument, expected range, actual `0`, fix, `sci.lang.Var` frame. | Same shown refusal in the receipt; transcript may additionally carry error formatting/triage. | Useful but not full data. Probe printed offending `0` twice (sentence and appended shown value). `error/render-ai`/`prepare-result` are the owners. P2. |
| Definition refusal | **Delivered as prose**, `isError:true`, names `user/audit-missing-contract`, missing `:malli/schema`, example fix. | Saved shown refusal; no new error occurrence in branch window. | Correctly refuses ordinary mistake; full authored declaration/location/error schema absent from shown error. P3. No paid context requested. |
| Gate/test failure | Source trace: post-eval gate/settlement may produce refusal prose or replace the receipt; test result renderers summarize failure rows. | Saved receipt/test facts, or phase refusal after a gate failure. | **Not live-probed: no test runs authorized.** `turn.clj:3328,3783–3816,3912–3948`; `test/accretion.clj:388–449`; `test.clj:282–318,2392–2451`. Test/fault recording can throw before terminal response. No completeness/once-only guarantee. |
| Renderer fault during own run | **Delivered as an EDN error map with MCP success**, no exception bit. Original `42` disappears. | Saved shown text is the replacement diagnostic, with neither `eval/error` nor renderer member in the pulled receipt. | Live **fail** of totality and diagnostic outcome classification; authored missing-renderer refusal, not injected renderer implementation crash. Map includes operation/fix but only refused message/class, no chain; duplicates render-unknown members under `data` and top level. No new branch error. P4/P7. |
| Settlement, recording or classification fault during own run | Source trace: can escape as prepl error/panic or replace evaluated value; request may await a terminal receipt that failed. | Core occurrence/closed-run evidence if those writes succeed; absence of a terminal receipt otherwise. | **Not injected.** `settle!`, `refusal-terminal-data`, `fault!`, `failure-value` can replace/rethrow. Storage failure should stop the affected execution, but still answer the waiting outside caller with the held value and failure data when available. |
| Another agent/background proc (Flow, wake, schedule) | P0's first JVM reply included **branch-errors JSON summary** of an existing `rc-m4-terminal` fault. Later branch calls saw only their branch, not the cluster copy. | Branch history explicitly filters cluster copies of other branches' faults; `for-agent` selects stewardship or own occurrences, not every root-actionable fault. Messages, if created, contain error prose. | Partial delivery, incomplete causes/locations; independent top-20 bound and cursor. No new background failure induced. `error.clj:1476–1489,1628,1940`; `transcript.clj:395`; `schedule.clj:602`; `flow.clj:202`. |
| JVM-mode probe error | **Delivered as printed native Throwable map** inside the projected MCP value: both messages, both ex-data maps, full root trace in the small probe; `isError:true`. | No root evaluation recording on this path in source; it does not enter SCI run settlement. | P0: 9,748-byte artifact advertised with digest and retrieval. It is not the named flat Seon shape. `jvm-evaluation-form` directly evals (`mcp.clj:383–391`); `mcp-project` stores blobs but no evaluation transaction (`cluster.clj:418–424`). §8.6's new recording target remains unmet. |
| Operator/boot failure | Source trace: boot request returns diagnostic/prose and process/CLI may print full floor; early failures have no cluster. Live status exposed existing fault summary only. | No automatic agent-context delivery for an early failure with no store; caught ownerless failures can be stderr only. | **No failed boot induced.** `boot.clj:315–369,753`; `operator/prepl.clj:36`; `mcp.clj:739,967,1003`. Current operator improvements lead with FAILED/cause, but operator readability is not MCP/context delivery. |

There are **eight delivery defect classes** in this matrix: own exceptions, contract refusals, definition refusals, gate/test failures, renderer failures, other late pipeline failures, background/other-agent failures, and operator/boot failures. “Undelivered” here includes missing structured diagnostic content, not only zero bytes. Three ordinary failures demonstrably returned prose; one renderer failure demonstrably lost the result and success classification. JVM nested cause delivery worked for the tested small value, with a separate recording/shape gap. Do not claim all eight were fault-injected.

### 2.5 Recording and delivery are divergent

**Recorded but not reliably delivered as error data:**

1. `fault/record!` writes the originating branch and optionally the held cluster branch (`fault.clj:130–170`). Its `delivered-to` set (`:35–54`) proves selected message recipients from a transaction, not that any context/MCP reply consumed them. An empty recipient set can accompany `committed? true`; policy tests only the write.
2. `error/commit-call` (`error.clj:1436,1476`) infers interrupted/core responsibility from the presence of an exception class. An attributed flat core fault can receive no message; an attributed Throwable can be sent to the broken executing agent instead of its responsible namespace/root. With no steward and no escalation dial, an unattributed fault is stored with no recipient. This remains E01.
3. Message content is `ai-prose(notice(...))` (`error.clj:1348–1361`), not the error value. The notification's signature is a useful retrieval identity, but it does not substitute for a data diagnostic in the receiving context.
4. Transcript windows derive `(previous settlement tx, current settlement tx]` through historical queries (`transcript.clj:346–403`). Occurrence `last-at` and `count` are **no-history** (`resources/seon/schemas/seon.error.occurrence.edn:3–5`). Later recurrence can change what an old as-of window can recover. Historical observation is recomputed rather than saved. A fault occurring after the last settlement has no such window until another settlement happens.
5. `occurred-since` filters already resolved faults, sums cumulative occurrence counts, and returns only signature/operation/message/count/branches. It does not return the error evidence. Resolution before observation can erase a fault from delivery. A cumulative count is not the number of events since the lower bound.
6. `for-agent` (`error.clj:1940–1974`) selects stewardship/own-turn errors. A root-actionable orphan core fault does not qualify merely because root exists. A worker's branch does not automatically see another branch's new cluster-copy fault.

**Delivered outside, or without proof of, the required recording path:** JVM-mode probe exceptions (P0/source trace); MCP transport/validation/watchdog/parse failures; early operator failures without a store. P4 has a shown evaluation receipt but no core occurrence. Its deliberately absent renderer is an authored refusal: that absence alone does **not** prove a core-recording violation. What P4 proves is loss of the original shown value and missing diagnostic outcome classification. A registered renderer that throws, or a broken recorder, requires its own later acceptance probe. Ordinary agent mistakes **are** recorded as evaluation receipts (P5), and need not become core-fault occurrences. Do not “fix” that correct separation by creating a core incident for every invalid authored call.

**Lost or reduced before either route:** AI sink throw; discovery/cleanup failures; late unconsumed IO callback/test task results; generator failures turned into skips; source-root failure; full primary causes replaced by unprotected cleanup; class/message-only branch-window failures.

**`new-faults` is gone; the cursor is not.** No `:seon.dev.mcp/new-faults` appears in the pinned bridge. `window-bases` remains an atom keyed by `[root cluster branch]`, not per durable agent evaluation (`mcp.clj:43,583,599`). It advances after observing a basis, even though the returned errors are clipped to 20. The remainder is obtainable only by the offered requery; it will not be automatically delivered by the next window. Calls from different sessions in the same bridge share this cursor. Reconnection loses it: root begins at 0, an unknown branch gets an unknown-window sentence. This differs from the stored-basis target in requirements §8.3/§8.7.

**Status is observation, not delivery.** `runtime_status` exposes a count/top list and a signature, not the whole flat diagnostic. Its failures become message-only unknowns. The native problems query can retrieve more, which is useful recovery but does not prove that the causing agent was told.

**Root wake policy:** the 2026-09-24 no-paid-root default explains why `escalate-to` is absent; the newer delivery law still requires the diagnostic in the next applicable root/MCP context. Showing data need not issue a provider request. Keep paid scheduling separate from guaranteed observation. Do not silently enable paid turns as an audit fix, and do not use the old default to justify dropping data delivery.

## 3. Reconcile the prior 106 instances

These dispositions refer to the **old concrete mechanism**, not a green installed test. `Addressed` means source deletion/change was inspected; `open` means its mechanism remains; `partial` means a narrower fix exists with a remaining path; `unproved` retains the prior uncertainty. All original IDs occur exactly once below. Several IDs share one producer; never count them as separate task families.

| Prior IDs | Disposition at baseline | Current evidence |
|---|---|---|
| A01, A02, A03 | Open | `flow.clj:420–477` callback throw remains in unread FutureTask; `test.clj:290–300` watcher joins/releases without `.get`; `effect.clj:471–502` cancel without late result consumer. |
| A04 | Addressed by deletion | Error fan-out/committer removed (`5d8e803f7`, `c37d1ca79`); proc port is `flow.clj:355`. |
| A05, A06, A11 | Addressed in source | Operator race/census joins, `298753fc2`; inspect `script/seon/operator.clj` join/boot owners. No boot drill run here. |
| A07, A08, A12 | Addressed in source | Process `join-output :114`, shell `task-outcomes :321`, dependency-digest bounded output join `:57–80`; `be3226eaa`, `81f78faa3`, `e98de259c`. |
| A09 | Partial | `render/web.clj:2886` now calls synchronous fault owner while painting; non-close failure after painting false remains ignored; thread/close lifecycle unproved. |
| A10 | Partial | `turn.clj:5685` contains recorder failure and returns it to promise; failure before entering that try in the executor's outer loop remains uncontained. |
| A13 | Partial | `operator_move_test.clj:24–30` now bounds child/output waits; non-aborting assertions can still proceed without proved exit. |
| B01, B12, B13, B32 | Addressed by deletion/replacement | Committer removed; render/ns has no catch/walk; old render count/window-realization code gone; eval/drive receipt parser catches gone. New floor/result defects are separately named above. |
| B02 | Addressed narrowly | `schema.clj:171–186` separates missing file and preserves CompilerException cause. This does not prove every missing transitive file is benign. |
| B03, B04, B05, B06, B07, B08, B09, B10, B11 | Open | Current locations in §§2.2–2.3; narrowing `phase` did not preserve ExceptionInfo wrappers. |
| B14, B25 | Addressed in source | Shell catches declared reaped stdin pipe messages only (`shell/jvm.clj:337`); operator stop/callback handling changed in `298753fc2`. |
| B15, B16, B17, B18, B19, B20, B21, B22, B23 | Open | Web extraction/status and bridge discovery/parse/window/watchdog/reload paths, Appendix B. |
| B24 | Open | `script/seon/dev/docstring.clj:303,382` still catches parser exceptions to nil, outside primary census scope but in the prior list. |
| B26, B27, B28, B29, B30, B31, B33, B35 | Open/unproved broad fallback | Analyzer, print identity, repl parsing, transcript reader, declaration normalization, web JSON, bootstrap prose parsing still use fallback catches. Expected syntax-domain failure is not a proof for arbitrary Throwable. |
| B34 | Unproved | Operator fallback classpath/dependency handling has changed; no missing-input probe performed. Do not infer closure from a commit subject. |
| C01, C02 | Open | `turn.clj:2816` opening-deferred and `next-agent-work :2950` can decline entry before post-pass exhaustion recording. Missing dial/pre-exhausted rearm proof still absent. |
| C03 | Addressed old dropping route | Synchronous faults replaced the counted dropping buffer/channel; `turn.clj:5602`, `flow.clj:202`. Empty recipients/emergency-only failures remain delivery defects. |
| C04 | Partial | Test request now records `::stop-cause :deadline` (`test.clj:2408`, `:2459`), but `plan.clj:721–725` still checks only top-level error then returns nil. |
| C05, C06 | Addressed in source | Dependency output deadline is loud; `issue.clj:71–89` now throws a nonzero git history refusal and uses process owner. |
| D01, D02, D03, D04 | Addressed old mismatch | Instrument wrapper no longer catches and returns a refusal outside Malli; AI attempt renderers declare missing-root alternative (`ai.clj:112–125`); background poll propagates db error (`background.clj:71`). Minimal bare-base failure remains a separate gap. |
| E01, E06, E08, E09 | Open | Recipient/class inference, emergency-only uncaught handling, internal failures as ordinary refusals, mixed acquisition faults/mistakes remain (`error.clj:1476`, `boot.clj:333`, `turn.clj:3503`, `sci/eval.clj:2622`). |
| E02, E03, E04 | Addressed old seam | Test writer uses fault policy (`test.clj:2326–2336`, `:2395`), acquisition binds custody (`sci/eval.clj:2677`). A02 late-result loss still exists. |
| E05, E07 | Addressed old mechanism | Instrument's recorder catch removed; kernel rethrows carried fault receipt (`kernel.clj:642`). The latter now conflicts with **new totality** for post-evaluation faults; old closure is not current compliance. |
| F01, F02, F03, F04 | Addressed old rendering mechanism | Complete-HTML error detour and full-key/window ordering path replaced by one tree. Raw admission/serialization still occurs before some floors (`error.clj:503`, `cluster.clj:375`). |
| F05, F06, F07, F09, F10, F11, F14, F17, F18, F21 | Open | Value/prose constructors remain; see §2.3, and original family sites against pinned source. |
| F08, F13, F20, F23 | Addressed old sites | Fault last-resort no longer `(str fault)`; old repl HTML fallback retired; old bridge `content-text` printer replaced; render/ns now emits calls rather than printing whole summaries. JSON delivery has its own remaining issue. |
| F12, F15, F16, F19 | Partial | Web debug/status/string-producing paths remain; schema drift gained floor (`b95676d59`); old fn error renderer changed but rejection messages still raw-print entity; status changed face without removing all raw printers. |
| F22 | Unproved/open bound | `error/scalar-text :912–924` still assumes scalar printing is affordable. |
| G01, G02, G03 | Open | Effect cancel, work-launcher cancel/drain, agent arming-delay stop waits (`effect.clj:499`, `flow.clj:625`, `agent.clj:879,1429`). |
| G04, G05, G06, G07, G08, G09, G10, G11, G12 | Addressed in source | Shell/process/operator joins and shared process use in issue/test-cache; same commits as A07/A08/A12/A05. No destructive proof repeated. |
| G13 | Partial | Operator move test asserts child exit then cleanup continues; assertions do not form an ownership barrier (`operator_move_test.clj:116`). |
| G14, G15, G16 | Unproved/open | Shared delayed derivations may block outside caller deadline; `flow/var-process :306–324` still accepts `:compute`, so dependency timed future ownership remains reachable by declaration. |

## 4. Ranked fix plan, with file-disjoint ownership

No implementation is proposed downstream merely to reinterpret lost prose. Fix the producer or the stage that discarded the value. The slices below have **exclusive source-file ownership**; shared changes must be passed to the named slice owner. Tests/schema resources follow their corresponding owner. They are not parallel grants and this audit launches no agents. Estimated deletions are physical source lines to verify in design review, not measured diffs. Avoid adding over 100 unmatched lines.

| Rank / exclusive slice | Producer and model error | Smallest change and deletions | Acceptance probe (future implementation work) |
|---|---|---|---|
| **1. Value/diagnostic rendering** — `render.clj`, `render/value.clj`, `repl.clj` | `value.clj:672–687`, `render/unknown :997`, `repl/error-text :153`: renderer refusal replaces result; error value becomes summary text. | Keep original value plus one declared diagnostic in the existing render result; render flat error fields through common floor. Delete refusal-as-replacement and duplicate prose error route (~25–70 lines). Keep the emergency floor; make its failure visible beside the held value. | P4 returns `42` and exactly one cause-bearing diagnostic; P1/P2 contain data and correct source location; AI/HTML agree on cuts; MCP and next context show the same saved observation. |
| **2. Evaluation/settlement** — `sci/eval.clj`, `sci/kernel.clj`, `turn.clj` | `shown-result :3859`, `phase :3503`, `refusal-terminal-data :3783`, `failure-value :652`: late failure overwrites/throws away result; arbitrary ExceptionInfo mistaken for declared refusal. | Separate evaluated value from later stage diagnostics within existing records; preserve outer chain; classify only producer-declared refusals. Delete triage-as-authority, overwritten receipt, repeated refusal-settlement rendering and member guesses (~40–100 lines). Defer actual rendering implementation to slice 1's API. | One finite form returns 42 while an injected renderer/settlement/classification fault is separately recorded/delivered; no reevaluation; repeated cause counted once. Recording failure stops affected work and answers caller with held value/diagnostic, not a hung terminal wait. |
| **3. Fault identity, storage and observation** — `error.clj`, `error/refusal.clj`, `fault.clj` | `fault/policy :190`, `error/commit-call :1476`, `occurred-since :1628`: panic mode stops otherwise viable work; exception presence guesses responsibility; summary is not error data. | Reuse task `6b8d26a16ce7`; delete mode split and infer-from-Throwable routing (~30–70 lines here; cross-owner call-site retirement assigned to their slices). Keep signature/occurrence/message writer. Preserve suppressed causes, declare recorder failures, return touched error values/references with resolution and honest cumulative counts. Delivery observation must not require paid scheduling. | Same core fault as Throwable and flat declared value has same owner and data delivery; no steward still reaches root's observation; resolved-before-observation remains visible; a storage failure preserves both failures without retry/store fallback. |
| **4. MCP/JVM and context observation** — `script/seon/dev/mcp.clj`, `cluster.clj`, `render/transcript.clj`, `resources/seon/operator/prepl.clj` | `mcp.clj:43,554–603,679`, `cluster.clj:438`, `transcript.clj:346`: second cursor/query/clip; JVM bypasses record stage; print failure replaces value. | Consume the existing evaluation result and saved diagnostic units from slice 2, preserving native EDN data; JVM supplies evaluator to the same stages. Delete `window-bases`, `window-form`, `branch-errors!`, top-20, second eval/requery block and historical recomputation (~100–150 lines); delete extra value-replacing print catch. | Two agents on separate branches receive one new cluster fault once each; bridge restart loses nothing; >20 faults use common floor/requery; JVM P0 has recorded root evaluation; successful value plus diagnostic gives explicit non-success observation without changing value. |
| **5. Async outcome consumption** — `flow.clj`, `effect.clj`, `cluster/agent.clj`, `cluster/host.clj`, `cluster/wake.clj` | `flow/io-terminal! :407`, `execute-io-work! :441`, `effect/dispatch :467`: callback throw/late task outcome has no reader; cancellation treated as terminal. | Dispose each body and callback failure at the existing terminal owner before releasing submission; preserve cleanup separately; await actual exit. Delete unread FutureTask, cancelled-success terminal and redundant downstream disposition (~20–60 lines). Use existing graph/submission rosters, no new supervisor. | Finite latch-held task exceeds wait, then throws; held ownership survives timeout; caller/context receives body and cleanup diagnostics exactly once after their actual exits; other graph remains usable. |
| **6. Test/gate failure producers** — `test.clj`, `test/runner.clj`, `test/accretion.clj`, `plan.clj` | `test.clj:290`, `accretion.clj:234`, `runner.clj:946`, `plan.clj:721`: late result lost, engine error becomes skip, incomplete request becomes nil. | Consume late result before release, preserve causes in unknown/red data, skip only declared generator absence, propagate stopped/unfinished request. Delete broad generator skip, message-only conversions and top-error-only consumer (~15–45 lines). | No-generator without cause is advisory; generator that throws is red with cause; late body+cleanup failures both observable; plan refuses deadline/unfinished results. These tests were intentionally not run by this audit. |
| **7. Dependency/default/schema boundaries** — `call_preparation.clj`, `instrument.clj`, `schema.clj`, `schema/admission.clj`, `schema/datahike.clj`, `schema/edn.clj`, `schema/internal.cljc`, `db.clj`, `program.cljc`, `fn.clj`, `fn/analyzer.clj`, `src/my/program.clj` | `call_preparation.clj:382,900`, `instrument.clj:631`, `schema/admission.clj:493`: internal failure dressed as unavailable input/undeclared base. | Pass actual Throwable to one diagnostic adapter, declare only actual boundary error alternatives, preserve unknown; narrow parser/generator cases. Remove message value expansion and stale-source fallbacks (~30–80 lines). Each file can subdivide into a separate grant; keep same schema producer/consumer slice. | Wrong caller input refuses before body with expected/offending data; validator compilation/default supplier failure is separately a core diagnostic; chained source/codec failure remains complete. No whole-registry error matching. |
| **8. Background, operator and peripheral catches** — `ai.clj`, `web/jvm.clj`, `render/web.clj`, `schedule.clj`, `maintenance.clj`, `cluster/boot.clj`, `cluster/store.clj`, `cluster/status.clj`, `cluster/export.clj`, `cluster/process.clj`, `blob.clj`, `edit.clj`, `edit/jvm.clj`, `eval/drive.clj`, `script/seon/operator.clj`, `script/seon/dev/docstring.clj` | Sink/log-only and cause-dropping catches; `boot.clj:333`, `store.clj:610`, `ai.clj:879`, `web/jvm.clj:349`. | Delete swallowing catches, preserve primary through cleanup, deliver error data through existing operation/proc caller and fault owner. Early boot returns diagnostic to operator/MCP without inventing a database. Remove silent lint-clean outcome and message-only status (~20–60 lines per bounded subgroup). | Failing sink, extraction, listener, schedule handler, early boot and cleanup each produce data in their real caller/context; no merely stderr-only pass. Storage-unavailable fault remains visible to outside caller and stops affected execution. |

Operations should cost O(cause links + retained frames/evidence) for normalization and O(shown value nodes) for printing. Store once per occurrence, observe once per committed evaluation. `occurred-since` currently scans occurrence datoms and joins/sorts; do not call its complexity O(window) merely because it accepts `since`. Reuse the existing Datahike dependency/read evidence, measure on a captured value, and avoid another cache or registry. No new polling/retry loop is justified.

### Task entries to create or amend

These are proposals, not writes. Use `resources/seon/tasks/<namespace>.edn` and the existing task writer/id owner; do not fabricate IDs. Subject edges go on the owning definitions as `:seon/tasks`; proof edges go on the regression deftests. Evidence and measurements remain linked to this report, not copied into task EDN as a log. Search existing task names/identities before creation. Task rows are data and do not ride program merge.

| Namespace | Name | Intent / reuse |
|---|---|---|
| `seon.render.value` | `preserve-value-when-rendering-fails` | Show original result plus one structured diagnostic; covers live P4. Coordinate with existing `c088e4367dc5`, `shown-result-ignores-the-floor-refusal`, in `seon.sci.eval`. |
| `seon.sci.eval` | `deliver-evaluation-errors-as-values` | Preserve chain and SCI location in saved MCP/context observation, including P1/P2/P3; align existing `f548d10ecb69`, `evaluate-declares-too-few-refusals`. |
| `seon.turn` | `late-faults-accompany-evaluated-values` | Settlement, classification and recording diagnostics never replace a completed evaluation. |
| `seon.fault` | `delete-panic-record-policy-branch` | **Reuse `6b8d26a16ce7`**, already created during audit. |
| `seon.error` | `deliver-core-faults-to-responsible-agent` | Writer distinguishes author mistake/core failure without Throwable guessing; no committed-but-unobservable opening. Reconcile existing lost-seams E01/M work. |
| `seon.error.refusal` | `preserve-primary-and-suppressed-causes` | One normalization retains cleanup faults and truthful location with explicit evidence limits. |
| `seon.dev.mcp` | `consume-recorded-diagnostic-observation` | Retire bridge cursor/second query/top-20; use same saved data observation as Seon. |
| `seon.cluster` | `record-jvm-evaluations-on-shared-path` | JVM evaluator enters record/print stages without SCI custody; no second evaluator. |
| `seon.render.transcript` | `keep-fault-observations-historical` | Save exact observation once; no as-of reconstruction from no-history occurrence counters. |
| `seon.flow` | `consume-callback-failure-before-release` | Eliminate A01/unread FutureTask; preserve every terminal failure. |
| `seon.effect` | `retain-handler-until-actual-exit` | Cancel requests interruption; late result remains owned and delivered. |
| `seon.test` | `consume-late-test-outcomes` | Join, consume, record outcome and cleanup, then release. |
| `seon.test.accretion` | `generator-fault-is-not-a-skip` | Narrow unsupported-generation case; any engine cause is red and delivered. |
| `seon.plan` | `propagate-unfinished-test-request` | A stopped/unfinished test request cannot become nil success. |
| `seon.call-preparation` | `preserve-supplier-and-validator-faults` | Missing supplied value differs from broken supplier/checker; keep cause. |
| `my.program` | `preserve-program-read-causes` | Retain chain and source location on the non-declared read failure branch, not only class/ex-data. |
| `seon.instrument` | `declare-refusal-construction-failure` | Replace bare-base fallback with its own named diagnostic while retaining original validation evidence. |
| `seon.schema.admission` | `admission-engine-fault-is-not-a-finding` | Only declared author mistakes become findings; all other causes survive. |
| `seon.ai` | `deliver-stream-sink-failure` | Sink failure cannot silently continue provider fold as success. |
| `seon.cluster.boot` | `deliver-operator-fault-without-store` | Early boot/ownerless failures are caller-visible data, independently of durable store availability. |
| `seon.cluster.store` | `deliver-listener-recording-failure` | Recording/cleanup error reaches live observer, not stderr only. |
| `seon.web.jvm` | `preserve-extraction-failure` | Retain original extraction cause beside usable fetch value. |
| `seon.dev.docstring` | `parser-failure-is-not-clean-lint` | Replace swallowed parser error with a named diagnostic. |

## Final assessment

The census covers **104 files, 563 producer/carrier owners, 704 constructor calls, 164 overlapping base-map literals, 306 named base-extension keys, and 281 catch clauses**. **408 owners lack a named error reference in their written contract**; eight boundary/declaration gaps are confirmed above. Appendix B identifies **35 broad swallow/default candidates, five additional emergency-only catches, 40 lossy catch conversions, nine cleanup-masking risks, and three value-replacing catches**; **48 constructor observations in 44 owners** stringify with `pr-str`. These sets distinguish confirmed losses from cases still needing narrowing or a delivery proof. There are **eight delivery defect classes**, with explicit live/source-only distinctions. These counts must not be represented as an exact all-path undeclared-error total or eight fault-injection passes.

The recommended shape is **one flat, named extension of `:seon.error/base`, preserving cause data, location and actionable refusal fields**, attached beside an unchanged result when the fault happens later. Top eight fixes, in impact order: preserve results on renderer failure; preserve diagnostic values through settlement; correct fault responsibility/storage/observation; retire MCP's separate cursor and JVM record bypass; consume every async outcome; stop treating test-engine failures as skips; preserve dependency/default/schema causes; replace sink/operator/background swallowers with caller-visible diagnostics.


## Appendix A. Complete constructor/carrier syntax inventory
Each entry is `owner@definition-line [construction-lines] flag`. Paths and line numbers refer to the pinned archive. `C` means at least one of the 306 named base extensions is referenced in the owner's written contract; `D` means only its definition body references one; `U` means neither does. `P` marks an owner with `pr-str` inside at least one constructor observation. A map literal and its surrounding constructor share one displayed line where applicable. These marks answer **what declaration evidence exists**, not whether a complete body-derived error union is proved. In particular C is not a pass, and D/U are not automatically undeclared: ordinary throwable carriers and supplied-declaration factories occur here. The serializer and delivery defects described above apply independently of these flags.

The inventory includes `ex-info`, calls named `diagnostic`, and literal maps with all three base identity fields. Returned existing errors, helper calls such as `refuse!`, and dependency throws are traced through their constructing owner, not counted once again at every caller. Arbitrary producer indirection, macros and native exceptions cannot be exhaustively resolved by this syntax grammar. Thus this is the reproducible constructor census, **not proof that every dynamically escaping error has a schema**. The two generator owners are test-data constructors, not observed core incidents.

Flags: **C=155, D=59, U=349**; D+U=408.

**`script/seon/dev/mcp.clj`**

`endpoint-error@142 [147] D`; `read-clj-endpoint@161 [164,192] U`; `current-clj-session!@238 [253] U`; `source!@262 [266] U`; `collect-prepl-response!@270 [281,285] UP`; `namespace-symbol!@337 [347] U`; `evaluation-mode!@352 [357] U`; `branch-name!@468 [473] U`; `execute-branch@524 [531] D`; `execute-clj-eval@605 [623] D`; `digest!@779 [784] U`; `path!@788 [794] U`; `execute-tool@871 [882] U`.

**`src/my/agent.clj`**

`branch@59 [78] U`.

**`src/my/background.clj`**

`invalid-call@10 [12] UP`.

**`src/my/program.clj`**

`read-result@24 [38] C`; `locate@53 [64,70,74] U`; `names-through@76 [85] U`; `caller-data@101 [111,116,127,132,139] U`; `tests-reaching@167 [185] C`; `key-data@187 [201,206,215] U`; `render-referrers@236 [252] U`; `proposed-plan@255 [263] U`; `breaks@281 [312] C`; `entity-facts@348 [358] U`; `history@360 [375,379,385,394,400,404] C`; `supplied-context@414 [431] C`; `cluster-custody@491 [499] C`; `overrides@584 [596] C`; `refusal@608 [613] C`; `deletion-report@628 [631,636] U`; `retract-operation!@658 [667,682,688] C`; `ns-unalias!@729 [746,756,765,769] C`; `native-call-refusal@777 [811] U`.

**`src/seon/agent.clj`**

`identity@12 [24] CP`; `archived?@35 [45] CP`; `archive-call@58 [66,68] U`; `update-settings-call@87 [94,99] U`; `effective-settings@124 [140] C`.

**`src/seon/ai.clj`**

`agent-setting-attributes@329 [341] C`; `extra-body@644 [652,661] C`; `request-body@677 [705] D`; `unreadable-stream-data@727 [730] C`; `parsed-completion@894 [905,913,924,937,956] D`; `completion-text@963 [977] D`; `truncation@1172 [1190] C`; `send-request@1317 [1391,1392,1410,1436,1452] D`; `complete@1468 [1505] D`.

**`src/seon/artifact.clj`**

`refuse!@13 [16] U`.

**`src/seon/await.clj`**

`diagnostic@28 [45] C`; `timeout-observation@55 [58] C`; `await-port-operations@75 [93] C`.

**`src/seon/background.clj`**

`poll@33 [51,84] CP`.

**`src/seon/blob.clj`**

`binary-threshold@65 [75,77] U`; `stage-file!@84 [90,92] U`; `verify-stored!@143 [150,152] U`; `stage-binary!@232 [257,259] U`; `get@371 [387,389] U`.

**`src/seon/bootstrap.clj`**

`situation@102 [116] C`.

**`src/seon/call_preparation.clj`**

`incoherent@200 [206] C`; `unavailable@834 [838] C`; `supply@862 [884,901,920] C`; `prepare@1022 [1051] C`.

**`src/seon/cluster.clj`**

`report-source-progress!@89 [99,100] U`; `mcp-effective@308 [323] C`; `mcp-get-value@499 [535] U`; `refused!@695 [698] U`; `warn-low-space!@834 [865] U`; `dropped-summary@1267 [1291] U`; `accrete-schema-population!@1522 [1568] U`; `call-with-program-change@2106 [2119,2120] U`; `adoption-guard-tx@2535 [2549] U`; `ensure-entity!@3197 [3245] CP`; `disarm-agents!@3574 [3613] U`.

**`src/seon/cluster/agent.clj`**

`steward-call@141 [167] U`; `namespace-seed-call@173 [190,193,199] D`; `assigned-to@444 [463] U`; `steward-of@466 [484] U`; `segmenter@641 [653] C`; `submit-source-in-projection@680 [713,746] C`; `evaluate-source!@754 [781] C`; `acquire-once!@934 [951] U`; `acquire-context!@973 [1012,1015,1029,1039,1071,1088] U`; `arm!@1155 [1186] U`; `arm-graph!@1206 [1220,1225] U`; `open-turn!@1317 [1324] U`; `turn-completion-failure!@1328 [1337] U`; `record-interruption!@1361 [1383] U`; `agents-to-arm@1478 [1488] U`; `armer-step@1494 [1630] U`.

**`src/seon/cluster/boot.clj`**

`stand-cluster-runtime!@30 [52] U`; `stand-boot-layers!@147 [183,196] U`; `diagnostic@260 [273,287,288] C`; `refuse!@296 [299] U`; `memory-pressure-crossed!@399 [411] D`; `start!@464 [477,478] U`; `request-world@604 [615] U`; `request!@619 [758] U`.

**`src/seon/cluster/export.clj`**

`refuse!@89 [93] U`; `clone!@122 [137] U`; `copy-store!@198 [208] U`.

**`src/seon/cluster/host.clj`**

`agent-row@24 [31] U`; `refusal@34 [38,39] U`; `start!@70 [87] U`; `retire!@155 [176] U`.

**`src/seon/cluster/message.clj`**

`inbound-tx@149 [157,163,169] UP`; `delivery@179 [196,204,216] UP`; `read@593 [605] CP`; `send-value@616 [627,635,644] U`; `send-call@674 [683,684,703] U`; `send!@706 [734] CP`; `decline@751 [762,770,778] C`.

**`src/seon/cluster/process.clj`**

`start-instant-unavailable!@28 [37,38] U`; `signal-pids!@157 [168] U`; `run-process!@230 [257,258,309,311] U`.

**`src/seon/cluster/prompt.clj`**

`effective-ai-settings@48 [56] C`; `refuse!@159 [162] U`; `capture-mismatch@335 [356] C`.

**`src/seon/cluster/registry.clj`**

`refuse!@82 [86] U`; `head-guard-tx@141 [153] U`; `record-fork!@249 [272] U`; `branch-blobs@456 [466] U`; `branch-heads@493 [502] U`; `retention-cutoff@548 [572] U`.

**`src/seon/cluster/reply.clj`**

`refused@46 [55] C`.

**`src/seon/cluster/source.clj`**

`refuse!@39 [42] U`; `resolve-population@375 [387,388] U`; `publication-error@404 [411] C`; `record-results-at-head!@431 [452,483] U`; `publication-input-digest!@557 [564,569] U`; `declarations-held-tx@817 [827,828] U`; `prepare-merge!@840 [917] C`; `accept-merge!@932 [957] C`; `write-back-refusal@1004 [1008] C`.

**`src/seon/cluster/status.clj`**

`unknown@14 [17] C`; `thread-counts@27 [43] U`.

**`src/seon/cluster/store.clj`**

`fresh-file-lock@99 [114] U`; `refuse!@203 [207] U`; `listener-failed!@586 [600] U`.

**`src/seon/cluster/wake.clj`**

`declarations-refusal@215 [233,240,248] CP`; `arming-refusal@254 [273] C`; `deliver!@375 [386] U`; `route!@433 [498] U`.

**`src/seon/config.clj`**

`result-caps@105 [123] U`; `refuse!@172 [176,178] U`; `read-edn-map@180 [190,196] U`; `validate-layer@208 [215,230] U`; `admit-initialization-rows@255 [263,277,291,305,320] U`; `admit-initialization@338 [342] U`; `validate-default-decisions@375 [384,401] U`; `compile-settings@476 [493,514] U`; `compile-manifest@529 [537] U`; `population-transaction-data@576 [591] U`; `require-functions!@626 [642,646] UP`; `accrete-row-attributes!@669 [692] U`; `apply-compiled!@695 [764] U`; `held-overlay@767 [776] U`; `effective-in@831 [856] C`.

**`src/seon/context.clj`**

`selection-refusal@63 [68] C`; `transaction-read@112 [115] U`; `refuse-selection!@150 [158] U`; `capture-tx@359 [376] D`.

**`src/seon/db.clj`**

`error-value@163 [166] U`; `schema-refusal@207 [213] C`; `dependency-error@221 [236] D`; `foreign-connection-error@485 [504] CP`; `replay-read@989 [1044] DP`; `projection-fallback@1261 [1271] C`; `construction-projection@1431 [1442] U`; `loaded-builder@1488 [1534] U`; `read-declarations@1642 [1658] C`; `unknown-attribute-error@1810 [1814] DP`; `value-type-error@1825 [1828] DP`; `lookup-ref-error@1841 [1856] DP`; `malformed-query-pattern-error@1913 [1919] D`; `query-find-attributes@2018 [2041] U`; `query-input-shape-error@2336 [2339] D`; `missing-pull-selector-error@2496 [2503] D`; `validate-pulled-value@2518 [2544] D`; `pull-budget-error@2579 [2584,2585] C`; `pull-call@2594 [2609] CP`; `database-view@2928 [2944] C`; `rejected-value@3187 [3198,3203,3204] C`; `invalid-write@3263 [3283] U`; `write-owned-values-error@3523 [3550,3553] UP`; `write-render-target-error@4071 [4101] U`; `removed-definition-error@4111 [4159] CP`; `write-agent-retraction-error@4189 [4200] U`; `write-digest-error@4337 [4375] UP`; `write-report-error@4388 [4456] U`; `retention-check@4541 [4577] UP`; `write-refusal@4658 [4673,4674] U`; `transact-call@4682 [4739,4756] U`; `missing-transaction-data-error@4775 [4780] C`; `transact!@4914 [4961] C`.

**`src/seon/edit.clj`**

`parse-root@116 [122] C`; `single-form@148 [162] C`; `lossless-candidate@261 [271,279] U`; `form@314 [346,361] C`; `exact@396 [415,426] C`; `lines@454 [464,478] C`.

**`src/seon/edit/jvm.clj`**

`flat-error@7 [11] C`.

**`src/seon/effect.clj`**

`capabilities@143 [153] U`; `accepts-request?@169 [177] U`; `open-call@232 [241,243] U`; `settle-call@285 [296,297,307,308] U`; `interrupt-call@347 [361,362,372,373] U`; `interrupt!@600 [603] U`; `handler-failure@621 [624] C`; `background-time-limit@665 [688,698] C`; `request*@704 [715,722,760,768,777,804] U`.

**`src/seon/env.clj`**

`environment-state@81 [88,89] U`; `replace-environment!@96 [104,105] U`; `declared-member-rows@146 [162,164] U`; `absent-member-error@208 [211] C`; `construct@222 [228] C`; `scope@276 [289,291,302] C`; `supplied-agent-id@335 [347] C`; `require-environment@355 [362] C`; `refuse-incomplete-environment!@374 [387] C`; `refuse-absent-environment!@389 [402] U`; `carry-state@412 [420,421] U`.

**`src/seon/error.clj`**

`throwable-generator@53 [55] U`; `prepare-result@467 [499,500] U`; `validate-declaration!@520 [529,530] U`; `prepare@536 [579,637] D`; `fact-source@742 [751] U`; `recording@1537 [1568] D`; `latest-fact@1718 [1726] U`; `fault-entities@1917 [1924] UP`; `error-base-generator@2326 [2328] U`.

**`src/seon/error/refusal.clj`**

`diagnostic@91 [119] U`.

**`src/seon/eval.clj`**

`of-agent@9 [35] C`.

**`src/seon/eval/drive.clj`**

`await-fact!@50 [64] U`; `inbound!@71 [81,92,102] U`; `run-episode!@297 [320,329] U`; `run-sample!@364 [390] U`.

**`src/seon/fault.clj`**

`record!@56 [157] UP`; `fault!@205 [265] D`.

**`src/seon/flow.clj`**

`var-process@286 [313,318,369] U`; `submission-capacity-error@397 [401] C`; `required-launcher-configuration@540 [548,550] U`; `stop-work-launcher!@621 [639] C`; `submit!@679 [736,750] U`.

**`src/seon/fn.clj`**

`require-committed!@52 [56] UP`; `source-context@131 [142] U`; `exact-source@164 [174,177] U`; `seed-refused!@484 [491] U`; `assert-seed-contracts!@568 [580] U`; `task-refused!@614 [619,624] U`; `invocation-attributes@671 [678] U`; `var-row@693 [714] U`; `unresolved-macro-refusal@1000 [1011] D`; `analyze-forms@1192 [1233] CP`; `assert-clean-analysis!@1346 [1351] U`; `program-targets@1393 [1411] U`; `namespace-context@1418 [1434] U`; `read-or-throw!@1841 [1846] U`; `capability-refused!@2425 [2429] U`; `assert-capability-contracts!@2453 [2470,2488] U`; `build-artifact@2540 [2559] U`; `analyzed-artifacts@2615 [2657] U`; `caller-files@2684 [2692,2705,2717,2722,2738] U`; `removed-definition-caller-paths@2743 [2756,2767] U`; `invoker-paths@2770 [2786,2801] U`; `analyzed-files@2836 [2859] U`; `assert-one-row-per-identity!@3054 [3062] U`; `assert-populated!@3066 [3072] U`; `desired-rows@3278 [3323] U`; `index-tempids@3351 [3366] U`; `compile-index-transaction@3377 [3448] U`; `seek@3530 [3537] U`; `file-identities@3788 [3802] U`; `database-manifest@3816 [3832,3838] U`; `reconciled-paths@3861 [3882] UP`; `reconcile-call@3889 [3903] U`; `index!@3919 [3979] C`.

**`src/seon/fn/analyzer.clj`**

`invoke-kondo@362 [427] U`; `analyze@714 [746] U`.

**`src/seon/fn/signature.cljc`**

`refused!@7 [11] U`.

**`src/seon/fs.clj`**

`refuse-deletion!@71 [74] U`; `source-directory@213 [219] U`; `delete-recursively-impl!@264 [273,280,301] U`.

**`src/seon/fs/jvm.clj`**

`flat-error@35 [38] U`; `refuse!@45 [48] U`.

**`src/seon/instrument.clj`**

`supplied-entry-problems@241 [250] U`; `violation@282 [380] U`; `bind-contract-predicates@423 [443,446] UP`; `wrap-interpreted@457 [502] D`; `boundary-refusal@543 [554] U`; `compiled-wrapper@579 [630,631,660,674] D`; `registration-error@782 [790] C`.

**`src/seon/issue.clj`**

`note-opened@71 [80,81] U`; `notes@138 [145] U`; `citation-attributes@189 [210,211] U`; `index-tx@325 [414,430,467] U`; `tasks-tx@639 [649] U`; `adopt-tasks-tx@654 [663] U`; `refuse!@777 [781] U`; `subject-row@794 [803,812,820] U`; `detector-rows@837 [842,850,862] U`; `status@998 [1026] C`; `adopt-tx@1141 [1155] U`; `done?@1261 [1276] U`; `require-test-refs!@1291 [1296] U`; `create-tx@1304 [1325,1332,1340,1348,1355,1363] U`; `start-tx@1396 [1417,1426] U`; `exhaust-tx@1461 [1488] U`; `settle-call@1493 [1501] U`; `add-tx@1525 [1537] U`; `guard-call@1571 [1580] U`; `report@1614 [1639] U`.

**`src/seon/issue/opening.clj`**

`source@191 [207] C`; `context@214 [232] C`.

**`src/seon/maintenance.clj`**

`result-entity@38 [50,61,67] U`; `last-collection-in@214 [244] C`; `exception-value@432 [436,438] C`; `incomplete-collection!@709 [713,719] U`; `refuse-misspelled-options!@897 [919,922] U`; `collect!@932 [943] C`.

**`src/seon/note.clj`**

`refuse!@149 [152] U`.

**`src/seon/plan.clj`**

`refuse!@82 [85] U`; `read-result!@98 [103] U`; `resolve-subject!@165 [173] U`; `plan@347 [362] C`; `item@399 [410] C`; `owned-step-eid!@534 [538,553] U`; `add-step-call@574 [580,590,606] U`; `query-deadline@640 [651] U`; `stale-issue-tests@662 [675,683] U`; `run-issue-tests!@690 [704,723] U`; `done-query-result@727 [739] U`; `complete-step-call@789 [797,811,827] U`; `start-step-call@886 [892,904] U`; `update-step-call@928 [934] U`; `refuse-duplicate-identities!@983 [987] U`; `refuse-duplicate-positions!@997 [1002] U`; `refuse-dependency-cycle!@1015 [1021] U`; `compile-tree@1144 [1148,1171,1185,1206] U`.

**`src/seon/problems.clj`**

`error-signatures@113 [126] U`.

**`src/seon/program.cljc`**

`declaration-refused!@154 [158,159] U`; `declaration-at@317 [336] C`; `digest-map-refusal@407 [413,414] C`; `key-kind@774 [793] UP`; `sequential-binding-children@838 [851,852,868,869] U`; `binding-row@885 [922,923] U`; `one-by-key@945 [953,954] U`; `input-slots@966 [980,981] U`; `arity-row@1011 [1021,1022] U`; `contract-facts@1051 [1094,1095] U`.

**`src/seon/reconcile.cljc`**

`refuse!@48 [52] U`.

**`src/seon/render.clj`**

`request-projection@98 [122] U`; `request-profile@128 [155,168] C`; `ambiguity@290 [295] C`; `unknown@986 [1002] C`; `raw-output@1236 [1259,1275,1290] D`; `source-provenance-error@1346 [1352] C`; `render-form-value@1393 [1408] D`; `render-call@1419 [1474] D`; `walk-error@1748 [1754] U`.

**`src/seon/render/data.clj`**

`at@47 [62] C`; `observation-error@99 [104] C`.

**`src/seon/render/hiccup.clj`**

`shorthand@258 [277,302,321] CP`; `append-element!@428 [434] U`.

**`src/seon/render/route.clj`**

`path@39 [56] U`.

**`src/seon/render/transcript.clj`**

`read-or-throw@338 [343] U`; `candidate-history@663 [730] U`; `missing-selected-run@776 [781] C`; `runtime-owner@1025 [1038] C`; `prefix-problem@2114 [2129] U`; `render-runtime-html@2429 [2447] D`.

**`src/seon/render/value.clj`**

`node-id@244 [263] CP`; `insufficient-budget@838 [843] C`; `prepare@986 [1041,1046] C`; `read-artifact@1107 [1122] C`.

**`src/seon/render/walk.clj`**

`connection-observation@215 [231] C`; `distance-cap-unit@613 [631] C`; `neighborhood@697 [729] D`; `continue@805 [821] C`; `history@856 [870] C`; `graph@1032 [1051] C`.

**`src/seon/render/web.clj`**

`debug-diagnostic@672 [678] C`; `turn-function-result@687 [703] C`; `debug-page-result@1719 [1776] U`; `failed-page-result@2264 [2295] U`; `render-step@2566 [2622,2624] U`; `write-package!@2725 [2756] C`; `await-feed-package!@2777 [2789] C`; `feed@2805 [2853,2869,2884,2891] U`; `cluster-effective!@3078 [3085,3091] U`; `ensure-namespace-owner!@3095 [3119] C`; `context-action-response@3600 [3618] U`; `bind-handlers@3743 [3751] U`; `start!@3843 [3870,3898] U`.

**`src/seon/run.clj`**

`usage-form@58 [79] U`; `wait@90 [100] C`; `complete@107 [119] C`.

**`src/seon/schedule.clj`**

`task-created-at@198 [213,214] U`; `fire-call@293 [336,337,349,350,365,366] U`; `settle-call@420 [431,432,462,463] U`; `transact-result!@494 [502] U`; `invoke-handler@598 [614,625] D`.

**`src/seon/schema.clj`**

`assert-acyclic-references!@107 [114] DP`; `runtime-predicate@145 [175,176,186] U`; `compilable-form@243 [308] DP`; `unresolved-reference-refusal@431 [465,467] D`; `refuse-unresolved-reference!@498 [520] U`; `canonical-definition@582 [598,639,677] D`; `canonical-value-string@777 [812] D`; `declaration-population@1167 [1186,1192] D`; `register-core-predicate!@1221 [1247] D`; `update-candidate-forms!@1264 [1270] D`; `assert-config-display!@1276 [1290,1299] D`; `assert-entity-partition!@1453 [1467] U`; `assert-complete-contract!@1475 [1536] D`; `register!@1688 [1722,1734] D`; `unregister!@1755 [1766] D`; `render-contract-refusal!@1929 [1943,1958] DP`; `deleted-predicate-functions@2815 [2837,2840] D`; `projection-from-rows@2849 [2888,2897,2904,2928,2944,2950,2967,2974] D`; `refuse-projection-source@3037 [3054] C`; `load-projection@3076 [3091] U`; `pulled-selector-refusal@3149 [3156] C`; `projection-without-schema@3408 [3424] D`; `projection-validator@3675 [3684] U`; `projection-explainer@3761 [3770] U`; `shape-projection@3776 [3781] U`; `identity-only-descriptors-in@3784 [3797,3807] U`.

**`src/seon/schema/admission.clj`**

`read-declarations@164 [170] U`.

**`src/seon/schema/datahike.clj`**

`storage-schema@52 [62] U`; `malli->datahike-attr-in@147 [153,170,174] U`; `assert-storable-schema!@203 [214] D`; `storable-properties-in@268 [277] U`; `refuse-slot!@316 [320] U`.

**`src/seon/schema/edn.clj`**

`unreadable-file!@125 [129] U`; `directory-resource-paths@152 [182,191] U`; `read-schema-resource@209 [221,231,240,249] U`; `validate-resource-placement!@262 [273,287,299] U`; `merge-schema-resources@309 [318] U`; `refusal!@450 [458] U`.

**`src/seon/schema/internal.cljc`**

`entity-maps@82 [90] U`; `entity-entries@100 [113] U`; `contract-error!@222 [226] D`; `assert-compilable-schema!@449 [485] DP`; `assert-non-nilable-value-schema!@508 [533] DP`.

**`src/seon/sci/admit.clj`**

`refuse-over-bound!@117 [127,128] U`; `rethrow-or-degrade!@498 [515,516] U`; `missing-bound-refusal@716 [725] U`; `required-cap@731 [747,749] U`.

**`src/seon/sci/eval.clj`**

`carried-cluster-name@212 [226] U`; `build-base-ctx@327 [344,346] U`; `row-backed?@659 [667] U`; `execution-unavailable@756 [766,767,781] C`; `unindexed-change@848 [852] C`; `multiple-declarations@864 [870] C`; `admit-program-change!@943 [976,1000] U`; `one-event@1040 [1052,1061,1075,1076] D`; `evaluation-projection@1220 [1227] U`; `database-effective-config@1236 [1258] U`; `instrumentation-config@1264 [1275] U`; `install-function-from-database!@1304 [1316,1317] U`; `install-refusing-root!@1436 [1446] C`; `interpretation-error@1457 [1462] C`; `call-detecting-program-change@1509 [1530] U`; `loaded-commit-id@1559 [1569] U`; `loaded-program@1573 [1582] U`; `function-digests@1590 [1600] U`; `install-row!@1631 [1665,1666,1792,1793] C`; `host-namespace!@2038 [2097,2106,2109,2112] U`; `load-core-namespaces!@2121 [2141] U`; `documentation-unavailable@2268 [2271] C`; `declaration-absent@2283 [2292] C`; `missing-contract@2298 [2307] C`; `documentation-schemas@2328 [2338] U`; `documentation-contract@2341 [2347] U`; `stored-source@2510 [2527] U`; `acquisition-refusal@2570 [2584] C`; `acquire-program!@2693 [2714,2765,2869,2871] U`; `declared-row@3696 [3717,3719,3733,3735] U`; `shown-result@3859 [3878] D`; `trusted-evaluation@4083 [4091] U`; `evaluate@4102 [4226,4227,4341,4394,4403,4405,4418,4426] D`; `accept-candidate!@4655 [4672] U`; `refuse-install@4676 [4687,4688] C`.

**`src/seon/sci/kernel.clj`**

`memory-pressure-error@133 [137] CP`; `ensure-function!@262 [273,274] U`; `own-arm@332 [339] U`; `acquire-arm@380 [385,386] U`; `failure-value@628 [664] U`; `invoke@700 [731,732,789] U`.

**`src/seon/sci/reader.cljc`**

`error-value@14 [19] U`; `refusal-handler@29 [33] U`; `reject-read-eval@45 [48] U`; `read-events@490 [523] D`.

**`src/seon/shell/jvm.clj`**

`cwd-path@40 [47,55] U`; `write-array!@141 [145,147] U`; `copy-blob-stdin!@156 [162,164,179,181,190,192,202,204] U`; `task-outcomes@324 [345] C`; `execute@368 [409,427] U`; `run@431 [452,453] C`.

**`src/seon/test.clj`**

`unknown@33 [36] C`; `apply-seeds!@131 [143] C`; `destructive-paths@411 [424] U`; `selection-refusal@526 [531] C`; `selection-read!@541 [549] U`; `select@692 [713] C`; `admission-refusal!@1094 [1098,1109] UP`; `admission-members@1111 [1127] U`; `admit-run@1161 [1223,1310] U`; `resolve-test@1457 [1466,1513] C`; `program-overlay-tx@1624 [1682] C`; `world-refusal@1705 [1711] C`; `world-base@1760 [1767] U`; `clean-request-handle@1805 [1843,1850,1887] U`; `request-refusal@1905 [1909] C`; `arm-first-loads!@2122 [2154,2170] C`; `run@2188 [2336,2386,2448,2507] C`.

**`src/seon/test/accretion.clj`**

`data-contract!@75 [85,86] U`; `install-refusal@388 [395] C`.

**`src/seon/test/cache.clj`**

`git-output@21 [31] U`.

**`src/seon/test/runner.clj`**

`run-vars!@364 [372] C`; `run-selected-tests@444 [463,465] U`; `reach-facts@723 [746,757] U`; `reach-refresh@786 [795] U`; `reach-content@876 [882] U`; `reach-entries@906 [947] U`; `reach-digests@955 [973] CP`; `program-digest@1038 [1050] C`; `execution-refusal!@1096 [1097,1102] U`; `execution-read@1104 [1106] U`; `prepare-failures!@1185 [1195] U`; `record-latest-tx@1432 [1503,1505,1513,1520,1529] U`; `recorded-member-results@1637 [1682] C`; `result-read-error@1840 [1846] C`.

**`src/seon/turn.clj`**

`opening-db@288 [306] C`; `refuse!@326 [332,333] C`; `require-open-run@356 [370] C`; `analyze-settlement@967 [986] U`; `declaration-diverged-since-open?@1190 [1208,1216,1227] U`; `generated-read-fault@2081 [2148] C`; `system-turn@2157 [2173] C`; `compact-call@2334 [2353,2354] U`; `agent-eid@2667 [2682] U`; `gate-function-install@3328 [3344,3361] U`; `phase@3486 [3505] D`; `settle-batch-refusal!@3818 [3878] C`; `settle!@3885 [3942] D`; `disposition-rule-error@4732 [4748] C`; `evaluate-sources@4753 [4774,4788,4888] U`; `restore-evaluations!@4944 [4955] C`; `episode-exhausted!@5293 [5312,5324] UP`; `close-escaped-run!@5331 [5350] U`; `turn-completion-error@5443 [5457] CP`; `turn-completion-backstop-failure@5480 [5483] U`; `agent-dial@5485 [5495] U`; `await-turn-permit!@5504 [5519] UP`; `write-refusal-bound@5553 [5571] UP`; `write-refusal-error@5580 [5588] CP`; `record-write-refusal-fault!@5602 [5612] U`; `arm-turn-completion-backstop!@5642 [5656] U`; `step@5700 [5764,5844] D`.

**`src/seon/web/jvm.clj`**

`ensure-declared-length!@113 [118] U`; `bounded-input@125 [130] U`.

## Appendix B. All catch sites and route dispositions
One row per catch clause; nested cleanup catches count separately. Flags are mutually exclusive so the count reconciles to 281. They describe the local route and its inspected consumer, **not an installed delivery pass**:

- **S — broad swallow/default candidate:** caught Exception/Throwable/RuntimeException becomes nil, empty/default data, original text or unreadable marker. There are legitimate syntax/round-trip domains among these; those need narrowing/proof, while sink and source-root loss are confirmed defects.
- **L — lossy conversion:** at least one arm discards Throwable/cause evidence into message, selected ex-data, a hidden Throwable field or a later skip. A complete original is not passed to the diagnostic serializer. This includes helper `failure-cause` returning a string in `instrument.clj:159`, and `my.program/read-result` keeping class/ex-data but no chain.
- **M — primary can be masked:** cleanup or state repair runs before rethrow without protecting the original. No cleanup-failure injection was made.
- **O — emergency/log-only local catch:** stderr is the last local route. A prior record may exist; this is not a claim every invocation is unrecorded. The emergency failure itself lacks caller/context delivery.
- **T — original evaluated value replaced:** catch substitutes projection/floor output. The selected-renderer refusal path is live-proved in P4; the three caught exception paths themselves are source evidence.
- **D — declared/specific case branch:** a named type/property or narrow exception class selects handling and usually rethrows other failures. This is a candidate correct local seam, not blanket approval: B02 transitive missing file, accretion no-generator with cause, interpreter interruption, and rendering-carried-receipt cases still need the checks in the report.
- **R — retained or delegated:** Throwable rethrown, suppressed on a primary, returned to an owner, or supplied to a cause-taking helper. Local retention does not establish complete serialization or delivery. Central suppressed-cause loss, fault policy, async consumption and producer declaration gaps still apply.

**Counts:** S=35, L=40, M=9, O=5, T=3, D=50, R=139; total=281. The headline swallower candidate count is **35**, with **5 additional emergency-only catches**; lossy catch conversions **40**. These do not include non-catch drop paths or prior out-of-scope script sites.

| Pinned file:line | Owning definition | Route |
|---|---|---|
| `script/seon/dev/mcp.clj:132` | `seon.dev.mcp/discovery-rows` | S |
| `script/seon/dev/mcp.clj:214` | `seon.dev.mcp/close-clj-session!` | S |
| `script/seon/dev/mcp.clj:234` | `seon.dev.mcp/open-clj-session!` | R |
| `script/seon/dev/mcp.clj:235` | `seon.dev.mcp/open-clj-session!` | S |
| `script/seon/dev/mcp.clj:300` | `seon.dev.mcp/decoded-projection-event` | R |
| `script/seon/dev/mcp.clj:342` | `seon.dev.mcp/namespace-symbol!` | S |
| `script/seon/dev/mcp.clj:446` | `seon.dev.mcp/run-events` | S |
| `script/seon/dev/mcp.clj:536` | `seon.dev.mcp/execute-branch` | S |
| `script/seon/dev/mcp.clj:547` | `seon.dev.mcp/execute-branch` | L |
| `script/seon/dev/mcp.clj:588` | `seon.dev.mcp/branch-errors!` | L |
| `script/seon/dev/mcp.clj:626` | `seon.dev.mcp/execute-clj-eval` | R |
| `script/seon/dev/mcp.clj:680` | `seon.dev.mcp/execute-clj-eval` | D |
| `script/seon/dev/mcp.clj:691` | `seon.dev.mcp/execute-clj-eval` | L |
| `script/seon/dev/mcp.clj:739` | `seon.dev.mcp/runtime-observation` | L |
| `script/seon/dev/mcp.clj:792` | `seon.dev.mcp/path!` | S |
| `script/seon/dev/mcp.clj:936` | `seon.dev.mcp/handle-request` | L |
| `script/seon/dev/mcp.clj:967` | `seon.dev.mcp/start-parent-watchdog!` | O |
| `script/seon/dev/mcp.clj:997` | `seon.dev.mcp/-main` | L |
| `script/seon/dev/mcp.clj:1003` | `seon.dev.mcp/-main` | L |
| `src/my/program.clj:33` | `my.program/read-result` | L |
| `src/seon/ai.clj:660` | `seon.ai/extra-body` | L |
| `src/seon/ai.clj:802` | `seon.ai/stream-event` | L |
| `src/seon/ai.clj:879` | `seon.ai/stream-fold` | S |
| `src/seon/ai.clj:1166` | `seon.ai/interruptible-lines` | R |
| `src/seon/ai.clj:1390` | `seon.ai/send-request` | R |
| `src/seon/ai.clj:1427` | `seon.ai/send-request` | R |
| `src/seon/ai.clj:1450` | `seon.ai/send-request` | R |
| `src/seon/await.clj:146` | `seon.await/await!` | D |
| `src/seon/blob.clj:52` | `seon.blob/store-faithful-edn` | S |
| `src/seon/blob.clj:289` | `seon.blob/stage-binary!` | M |
| `src/seon/bootstrap.clj:93` | `seon.bootstrap/render-help-html` | S |
| `src/seon/bootstrap.clj:230` | `seon.bootstrap/calls-symbol?` | S |
| `src/seon/bootstrap.clj:240` | `seon.bootstrap/contains-history-query?` | S |
| `src/seon/call_preparation.clj:382` | `seon.call-preparation/derive-snapshot` | L |
| `src/seon/call_preparation.clj:881` | `seon.call-preparation/supply` | R |
| `src/seon/call_preparation.clj:900` | `seon.call-preparation/supply` | L |
| `src/seon/cluster.clj:149` | `seon.cluster/cluster-name?` | D |
| `src/seon/cluster.clj:438` | `seon.cluster/mcp-project` | T |
| `src/seon/cluster.clj:474` | `seon.cluster/mcp-valf` | T |
| `src/seon/cluster.clj:2133` | `seon.cluster/call-with-program-change` | R |
| `src/seon/cluster.clj:3353` | `seon.cluster/serve!` | M |
| `src/seon/cluster/agent.clj:548` | `seon.cluster.agent/turn-step` | D |
| `src/seon/cluster/agent.clj:650` | `seon.cluster.agent/segmenter` | S |
| `src/seon/cluster/agent.clj:879` | `seon.cluster.agent/stop-owned-graphs!` | S |
| `src/seon/cluster/agent.clj:883` | `seon.cluster.agent/stop-owned-graphs!` | R |
| `src/seon/cluster/agent.clj:903` | `seon.cluster.agent/release-context!` | R |
| `src/seon/cluster/agent.clj:969` | `seon.cluster.agent/acquire-once!` | M |
| `src/seon/cluster/agent.clj:1124` | `seon.cluster.agent/acquire-context!` | R |
| `src/seon/cluster/agent.clj:1127` | `seon.cluster.agent/acquire-context!` | R |
| `src/seon/cluster/agent.clj:1131` | `seon.cluster.agent/acquire-context!` | R |
| `src/seon/cluster/agent.clj:1137` | `seon.cluster.agent/acquire-context!` | R |
| `src/seon/cluster/agent.clj:1150` | `seon.cluster.agent/acquire-context!` | D |
| `src/seon/cluster/agent.clj:1292` | `seon.cluster.agent/arm-graph!` | R |
| `src/seon/cluster/agent.clj:1429` | `seon.cluster.agent/disarm!` | S |
| `src/seon/cluster/boot.clj:418` | `seon.cluster.boot/memory-pressure-crossed!` | O |
| `src/seon/cluster/boot.clj:500` | `seon.cluster.boot/start!` | R |
| `src/seon/cluster/boot.clj:518` | `seon.cluster.boot/start!` | R |
| `src/seon/cluster/boot.clj:579` | `seon.cluster.boot/stop!` | R |
| `src/seon/cluster/boot.clj:753` | `seon.cluster.boot/request!` | R |
| `src/seon/cluster/export.clj:211` | `seon.cluster.export/copy-store!` | R |
| `src/seon/cluster/export.clj:224` | `seon.cluster.export/copy-store!` | L |
| `src/seon/cluster/export.clj:337` | `seon.cluster.export/export!` | M |
| `src/seon/cluster/host.clj:142` | `seon.cluster.host/host!` | R |
| `src/seon/cluster/host.clj:148` | `seon.cluster.host/host!` | R |
| `src/seon/cluster/process.clj:128` | `seon.cluster.process/join-output` | R |
| `src/seon/cluster/process.clj:208` | `seon.cluster.process/terminate-subprocess!` | D |
| `src/seon/cluster/process.clj:209` | `seon.cluster.process/terminate-subprocess!` | R |
| `src/seon/cluster/registry.clj:224` | `seon.cluster.registry/branch!` | D |
| `src/seon/cluster/registry.clj:357` | `seon.cluster.registry/retire-branch!` | D |
| `src/seon/cluster/source.clj:386` | `seon.cluster.source/resolve-population` | R |
| `src/seon/cluster/source.clj:399` | `seon.cluster.source/retire-scratch!` | R |
| `src/seon/cluster/source.clj:497` | `seon.cluster.source/record-results-at-head!` | R |
| `src/seon/cluster/source.clj:512` | `seon.cluster.source/record-results!` | R |
| `src/seon/cluster/source.clj:563` | `seon.cluster.source/publication-input-digest!` | R |
| `src/seon/cluster/source.clj:739` | `seon.cluster.source/publish!` | R |
| `src/seon/cluster/source.clj:1136` | `seon.cluster.source/proven-file` | D |
| `src/seon/cluster/source.clj:1189` | `seon.cluster.source/write-back-commits` | R |
| `src/seon/cluster/status.clj:44` | `seon.cluster.status/thread-counts` | L |
| `src/seon/cluster/status.clj:92` | `seon.cluster.status/snapshot` | L |
| `src/seon/cluster/status.clj:162` | `seon.cluster.status/agents` | L |
| `src/seon/cluster/store.clj:328` | `seon.cluster.store/acquire-flock!` | D |
| `src/seon/cluster/store.clj:334` | `seon.cluster.store/acquire-flock!` | M |
| `src/seon/cluster/store.clj:507` | `seon.cluster.store/open-store!` | M |
| `src/seon/cluster/store.clj:515` | `seon.cluster.store/open-store!` | M |
| `src/seon/cluster/store.clj:610` | `seon.cluster.store/listener-failed!` | O |
| `src/seon/config.clj:194` | `seon.config/read-edn-map` | R |
| `src/seon/db.clj:312` | `seon.db/resolve-database-value` | R |
| `src/seon/db.clj:606` | `seon.db/read-result-digest` | D |
| `src/seon/db.clj:2086` | `seon.db/decode-query-field` | R |
| `src/seon/db.clj:2297` | `seon.db/supplied-database-value` | R |
| `src/seon/db.clj:2491` | `seon.db/q` | R |
| `src/seon/db.clj:2646` | `seon.db/pull-call` | R |
| `src/seon/db.clj:2867` | `seon.db/datoms-call` | R |
| `src/seon/db.clj:2924` | `seon.db/index-page` | R |
| `src/seon/db.clj:2961` | `seon.db/database-view` | R |
| `src/seon/db.clj:2974` | `seon.db/database-identity` | R |
| `src/seon/db.clj:3197` | `seon.db/rejected-value` | R |
| `src/seon/db.clj:3751` | `seon.db/write-owned-values-error` | D |
| `src/seon/db.clj:4719` | `seon.db/transact-call` | D |
| `src/seon/db.clj:4753` | `seon.db/transact-call` | R |
| `src/seon/db.clj:4772` | `seon.db/with` | R |
| `src/seon/edit.clj:121` | `seon.edit/parse-root` | R |
| `src/seon/edit.clj:278` | `seon.edit/lossless-candidate` | R |
| `src/seon/edit/jvm.clj:163` | `seon.edit.jvm/edit` | R |
| `src/seon/effect.clj:499` | `seon.effect/dispatch` | R |
| `src/seon/effect.clj:553` | `seon.effect/context-handler` | L |
| `src/seon/effect.clj:908` | `seon.effect/request*` | R |
| `src/seon/effect.clj:910` | `seon.effect/request*` | R |
| `src/seon/effect.clj:914` | `seon.effect/request*` | R |
| `src/seon/error.clj:750` | `seon.error/fact-source` | R |
| `src/seon/error.clj:1056` | `seon.error/refusal-data` | D |
| `src/seon/eval/drive.clj:399` | `seon.eval.drive/run-sample!` | M |
| `src/seon/eval/drive.clj:413` | `seon.eval.drive/run-sample!` | M |
| `src/seon/fault.clj:173` | `seon.fault/record!` | R |
| `src/seon/fault.clj:175` | `seon.fault/record!` | L |
| `src/seon/fault.clj:235` | `seon.fault/fault!` | R |
| `src/seon/fault.clj:238` | `seon.fault/fault!` | R |
| `src/seon/flow.clj:232` | `seon.flow/proc-failed!` | O |
| `src/seon/flow.clj:341` | `seon.flow/var-process` | R |
| `src/seon/flow.clj:347` | `seon.flow/var-process` | R |
| `src/seon/flow.clj:374` | `seon.flow/var-process` | O |
| `src/seon/flow.clj:380` | `seon.flow/var-process` | S |
| `src/seon/flow.clj:466` | `seon.flow/execute-io-work!` | R |
| `src/seon/flow.clj:475` | `seon.flow/execute-io-work!` | R |
| `src/seon/fn.clj:140` | `seon.fn/source-context` | R |
| `src/seon/fn.clj:1900` | `seon.fn/call-reach` | D |
| `src/seon/fn.clj:1940` | `seon.fn/reverse-closure` | D |
| `src/seon/fn/analyzer.clj:854` | `seon.fn.analyzer/stored-arglists` | S |
| `src/seon/fn/analyzer.clj:904` | `seon.fn.analyzer/referenced-program-namespaces` | S |
| `src/seon/fs.clj:255` | `seon.fs/attributes` | D |
| `src/seon/fs.clj:324` | `seon.fs/delete-recursively-impl!` | D |
| `src/seon/fs/jvm.clj:143` | `seon.fs.jvm/relative-observation` | D |
| `src/seon/fs/jvm.clj:469` | `seon.fs.jvm/read-opened` | D |
| `src/seon/fs/jvm.clj:491` | `seon.fs.jvm/read-window` | R |
| `src/seon/fs/jvm.clj:716` | `seon.fs.jvm/write` | D |
| `src/seon/fs/jvm.clj:727` | `seon.fs.jvm/write` | D |
| `src/seon/fs/jvm.clj:730` | `seon.fs.jvm/write` | R |
| `src/seon/fs/jvm.clj:852` | `seon.fs.jvm/glob` | R |
| `src/seon/fs/jvm.clj:857` | `seon.fs.jvm/glob` | R |
| `src/seon/fs/jvm.clj:897` | `seon.fs.jvm/stat` | R |
| `src/seon/instrument.clj:197` | `seon.instrument/program-graph-arglists` | L |
| `src/seon/instrument.clj:214` | `seon.instrument/jvm-arglists` | L |
| `src/seon/instrument.clj:236` | `seon.instrument/diagnostic-arglists` | L |
| `src/seon/instrument.clj:665` | `seon.instrument/compiled-wrapper` | R |
| `src/seon/instrument.clj:884` | `seon.instrument/apply!` | R |
| `src/seon/issue.clj:187` | `seon.issue/qualified-token` | S |
| `src/seon/issue.clj:426` | `seon.issue/index-tx` | S |
| `src/seon/issue.clj:584` | `seon.issue/file-declarations` | R |
| `src/seon/issue.clj:713` | `seon.issue/index-tasks!` | D |
| `src/seon/issue.clj:954` | `seon.issue/generate!` | D |
| `src/seon/maintenance.clj:66` | `seon.maintenance/result-entity` | L |
| `src/seon/maintenance.clj:453` | `seon.maintenance/attempt` | R |
| `src/seon/maintenance.clj:869` | `seon.maintenance/collect-store!` | R |
| `src/seon/maintenance.clj:873` | `seon.maintenance/collect-store!` | R |
| `src/seon/plan.clj:519` | `seon.plan/ready-subjects` | R |
| `src/seon/plan.clj:1327` | `seon.plan/plan!` | R |
| `src/seon/print.cljc:91` | `seon.print/generated-item-identity` | S |
| `src/seon/print.cljc:272` | `seon.print/compare-values` | D |
| `src/seon/program.cljc:94` | `seon.program/edn-round-trip-symbol?` | S |
| `src/seon/program.cljc:476` | `seon.program/digest-map` | L |
| `src/seon/render.clj:1896` | `seon.render/walk` | L |
| `src/seon/render/data.clj:42` | `seon.render.data/parse-cursor` | S |
| `src/seon/render/transcript.clj:1196` | `seon.render.transcript/readable-shown` | S |
| `src/seon/render/transcript.clj:1390` | `seon.render.transcript/render-session` | D |
| `src/seon/render/transcript.clj:1625` | `seon.render.transcript/outline-everything` | D |
| `src/seon/render/transcript.clj:1667` | `seon.render.transcript/render-outline` | D |
| `src/seon/render/value.clj:92` | `seon.render.value/bounded-text` | R |
| `src/seon/render/value.clj:122` | `seon.render.value/link-lines` | R |
| `src/seon/render/value.clj:672` | `seon.render.value/declared` | T |
| `src/seon/render/web.clj:172` | `seon.render.web/read-browser-edn` | D |
| `src/seon/render/web.clj:2261` | `seon.render.web/entrance-fault!` | D |
| `src/seon/render/web.clj:2379` | `seon.render.web/render-pass` | R |
| `src/seon/render/web.clj:2384` | `seon.render.web/render-pass` | R |
| `src/seon/render/web.clj:2886` | `seon.render.web/feed` | R |
| `src/seon/render/web.clj:2951` | `seon.render.web/same-origin?` | D |
| `src/seon/render/web.clj:3319` | `seon.render.web/graph-debug-html` | S |
| `src/seon/render/web.clj:3893` | `seon.render.web/start!` | D |
| `src/seon/repl.clj:89` | `seon.repl/shown-value` | S |
| `src/seon/repl.clj:124` | `seon.repl/def-note` | S |
| `src/seon/repl.clj:166` | `seon.repl/error-text` | S |
| `src/seon/schedule.clj:622` | `seon.schedule/invoke-handler` | R |
| `src/seon/schema.clj:171` | `seon.schema/runtime-predicate` | D |
| `src/seon/schema.clj:173` | `seon.schema/runtime-predicate` | R |
| `src/seon/schema.clj:575` | `seon.schema/projection-registry` | R |
| `src/seon/schema.clj:736` | `seon.schema/canonical-reference-graph` | S |
| `src/seon/schema.clj:759` | `seon.schema/direct-reference-keys-in` | R |
| `src/seon/schema.clj:1048` | `seon.schema/first-party-source-roots` | S |
| `src/seon/schema.clj:1053` | `seon.schema/first-party-source-roots` | S |
| `src/seon/schema.clj:1396` | `seon.schema/malli-form?` | S |
| `src/seon/schema.clj:1403` | `seon.schema/malli-form?` | D |
| `src/seon/schema.clj:1441` | `seon.schema/pull-selector?` | D |
| `src/seon/schema.clj:1720` | `seon.schema/register!` | R |
| `src/seon/schema.clj:3249` | `seon.schema/pulled-attribute-entry` | R |
| `src/seon/schema/admission.clj:100` | `seon.schema.admission/parse-source` | L |
| `src/seon/schema/admission.clj:366` | `seon.schema.admission/registration-finding` | L |
| `src/seon/schema/admission.clj:493` | `seon.schema.admission/admit` | L |
| `src/seon/schema/datahike.clj:198` | `seon.schema.datahike/declared-storable?` | R |
| `src/seon/schema/datahike.clj:374` | `seon.schema.datahike/reader-round-trips?` | S |
| `src/seon/schema/datahike.clj:569` | `seon.schema.datahike/decode-attribute-value-in` | L |
| `src/seon/schema/edn.clj:218` | `seon.schema.edn/read-schema-resource` | R |
| `src/seon/schema/edn.clj:238` | `seon.schema.edn/read-schema-resource` | R |
| `src/seon/schema/edn.clj:527` | `seon.schema.edn/admit-changed-identities` | R |
| `src/seon/schema/edn.clj:607` | `seon.schema.edn/admit` | L |
| `src/seon/schema/internal.cljc:482` | `seon.schema.internal/assert-compilable-schema!` | R |
| `src/seon/sci/admit.clj:547` | `seon.sci.admit/project` | R |
| `src/seon/sci/admit.clj:563` | `seon.sci.admit/project` | R |
| `src/seon/sci/admit.clj:801` | `seon.sci.admit/admit-walk` | D |
| `src/seon/sci/eval.clj:805` | `seon.sci.eval/restore-or-unavailable!` | R |
| `src/seon/sci/eval.clj:998` | `seon.sci.eval/admit-program-change!` | R |
| `src/seon/sci/eval.clj:1377` | `seon.sci.eval/declaration-source-value` | S |
| `src/seon/sci/eval.clj:1749` | `seon.sci.eval/install-row!` | R |
| `src/seon/sci/eval.clj:2067` | `seon.sci.eval/host-namespace!` | R |
| `src/seon/sci/eval.clj:2911` | `seon.sci.eval/acquire-program!` | R |
| `src/seon/sci/eval.clj:3372` | `seon.sci.eval/base-ctx` | R |
| `src/seon/sci/eval.clj:3483` | `seon.sci.eval/record-refusals-once!` | R |
| `src/seon/sci/eval.clj:4617` | `seon.sci.eval/evaluate` | R |
| `src/seon/sci/eval.clj:4620` | `seon.sci.eval/evaluate` | R |
| `src/seon/sci/eval.clj:4670` | `seon.sci.eval/accept-candidate!` | R |
| `src/seon/sci/kernel.clj:371` | `seon.sci.kernel/own-arm` | R |
| `src/seon/sci/kernel.clj:768` | `seon.sci.kernel/invoke` | R |
| `src/seon/sci/kernel.clj:787` | `seon.sci.kernel/invoke` | R |
| `src/seon/sci/reader.cljc:521` | `seon.sci.reader/read-events` | R |
| `src/seon/sci/reader.cljc:778` | `seon.sci.reader/recovering-events` | R |
| `src/seon/sci/reader.cljc:847` | `seon.sci.reader/read` | R |
| `src/seon/shell/jvm.clj:99` | `seon.shell.jvm/virtual-task` | R |
| `src/seon/shell/jvm.clj:274` | `seon.shell.jvm/await-exit` | D |
| `src/seon/shell/jvm.clj:313` | `seon.shell.jvm/output-descriptor` | D |
| `src/seon/shell/jvm.clj:337` | `seon.shell.jvm/task-outcomes` | D |
| `src/seon/shell/jvm.clj:340` | `seon.shell.jvm/task-outcomes` | R |
| `src/seon/shell/jvm.clj:421` | `seon.shell.jvm/execute` | R |
| `src/seon/shell/jvm.clj:425` | `seon.shell.jvm/execute` | R |
| `src/seon/shell/jvm.clj:445` | `seon.shell.jvm/run` | R |
| `src/seon/shell/jvm.clj:447` | `seon.shell.jvm/run` | R |
| `src/seon/test.clj:108` | `seon.test/changed-since-green` | L |
| `src/seon/test.clj:311` | `seon.test/bounded-result` | L |
| `src/seon/test.clj:317` | `seon.test/bounded-result` | L |
| `src/seon/test.clj:320` | `seon.test/bounded-result` | R |
| `src/seon/test.clj:1046` | `seon.test/select` | D |
| `src/seon/test.clj:1517` | `seon.test/resolve-test` | L |
| `src/seon/test.clj:1520` | `seon.test/resolve-test` | L |
| `src/seon/test.clj:1729` | `seon.test/open-world!` | R |
| `src/seon/test.clj:1730` | `seon.test/open-world!` | R |
| `src/seon/test.clj:1752` | `seon.test/prepare-checkpoint!` | R |
| `src/seon/test.clj:1755` | `seon.test/prepare-checkpoint!` | R |
| `src/seon/test.clj:1853` | `seon.test/clean-request-handle` | R |
| `src/seon/test.clj:1857` | `seon.test/clean-request-handle` | R |
| `src/seon/test.clj:1861` | `seon.test/clean-request-handle` | R |
| `src/seon/test.clj:1872` | `seon.test/clean-request-handle` | D |
| `src/seon/test.clj:1890` | `seon.test/clean-request-handle` | R |
| `src/seon/test.clj:2001` | `seon.test/member-result` | R |
| `src/seon/test.clj:2066` | `seon.test/member-result` | R |
| `src/seon/test.clj:2166` | `seon.test/arm-first-loads!` | R |
| `src/seon/test.clj:2443` | `seon.test/run` | R |
| `src/seon/test.clj:2449` | `seon.test/run` | R |
| `src/seon/test/accretion.clj:46` | `seon.test.accretion/generatable?` | D |
| `src/seon/test/accretion.clj:61` | `seon.test.accretion/schema-row` | D |
| `src/seon/test/accretion.clj:234` | `seon.test.accretion/auto-check` | L |
| `src/seon/test/runner.clj:941` | `seon.test.runner/reach-entries` | R |
| `src/seon/test/runner.clj:946` | `seon.test.runner/reach-entries` | L |
| `src/seon/test/runner.clj:1859` | `seon.test.runner/run-results` | R |
| `src/seon/test/runner.clj:1889` | `seon.test.runner/latest-results` | R |
| `src/seon/turn.clj:1372` | `seon.turn/settled-value` | L |
| `src/seon/turn.clj:3503` | `seon.turn/phase` | L |
| `src/seon/turn.clj:5357` | `seon.turn/close-escaped-run!` | R |
| `src/seon/turn.clj:5439` | `seon.turn/turn` | R |
| `src/seon/turn.clj:5440` | `seon.turn/turn` | R |
| `src/seon/turn.clj:5640` | `seon.turn/record-turn-backstop-fault!` | R |
| `src/seon/turn.clj:5688` | `seon.turn/arm-turn-completion-backstop!` | R |
| `src/seon/turn.clj:5690` | `seon.turn/arm-turn-completion-backstop!` | R |
| `src/seon/web/jvm.clj:60` | `seon.web.jvm/admitted-uri` | L |
| `src/seon/web/jvm.clj:180` | `seon.web.jvm/decoded-text` | D |
| `src/seon/web/jvm.clj:181` | `seon.web.jvm/decoded-text` | D |
| `src/seon/web/jvm.clj:182` | `seon.web.jvm/decoded-text` | D |
| `src/seon/web/jvm.clj:349` | `seon.web.jvm/fetch` | L |
| `src/seon/web/jvm.clj:354` | `seon.web.jvm/fetch` | D |
| `src/seon/web/jvm.clj:358` | `seon.web.jvm/fetch` | D |
| `src/seon/web/jvm.clj:363` | `seon.web.jvm/fetch` | R |
| `src/seon/web/jvm.clj:429` | `seon.web.jvm/search` | S |
| `src/seon/web/jvm.clj:455` | `seon.web.jvm/search` | D |
| `src/seon/web/jvm.clj:459` | `seon.web.jvm/search` | D |
| `src/seon/web/jvm.clj:464` | `seon.web.jvm/search` | R |

## Appendix C. Reproducible static measurement program
This program only reads source/schema EDN and writes the local census JSON; it does not load Seon, call its REPL, or run tests. Its small accumulator records parser failures in the audit process only. Save the block as `tmp/error-delivery-static.clj`. Prepare the committed inputs with `git archive 60d323a081669aa01762cc1e8f09b714bb8f12e0 src resources script | tar -x -C tmp/error-delivery-audit-baseline` after creating that directory. Run `bb tmp/error-delivery-static.clj` using the repository's Babashka dependencies. Every owner, site, complete normalized catch form, written contract and named-error reference is emitted. Group constructor/literal rows by `[file ownerline owner]` for Appendix A; Appendix B classifications are the explicit reviewed table, not an automated safety judgment.

Recorded output: `{:files 104, :schemas 306, :sites {"constructor" 704, "catch" 281, "literal" 164}, :parse-failures []}`. The classification grammar deliberately reports missing proof rather than guessing error identity from a map.

```clojure
(require '[edamame.core :as e] '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str] '[cheshire.core :as json] '[clojure.walk :as w])
(def root "tmp/error-delivery-audit-baseline/")
(def schema-files (filter #(.endsWith (.getName %) ".edn") (file-seq (io/file root "resources/seon/schemas"))))
(def schemas (apply merge (map #(edn/read-string (slurp %)) schema-files)))
(defn descendants [x] (tree-seq coll? seq x))
(defn error-schema? [k seen]
 (when-not (seen k)
  (let [f (get schemas k)]
   (or (= k :seon.error/base)
       (and (keyword? f) (error-schema? f (conj seen k)))
       (and (vector? f) (= :and (first f))
            (some #(and (keyword? %) (error-schema? % (conj seen k))) (rest f)))))))
(def errors (set (filter #(and (not= :seon.error/base %) (error-schema? % #{})) (keys schemas))))
(defn err-refs [form] (vec (sort (set (filter errors (descendants form))))))
(defn aliases [s]
 (let [n (e/parse-string s {:all true :features #{:clj} :read-cond :allow :auto-resolve (fn [a] (if (= a :current) 'user a))})]
  (into {:current (second n)}
        (for [v (descendants n) :when (and (vector? v) (symbol? (first v)))
              :let [i (.indexOf v :as) j (.indexOf v :as-alias)]
              :when (or (>= i 0) (>= j 0))]
          [(nth v (inc (max i j))) (first v)]))))
(def files (concat (filter #(and (.isFile %) (re-find #"\.clj[cs]?$" (.getName %))) (file-seq (io/file root "src"))) [(io/file root "script/seon/dev/mcp.clj")]))
(def failures (atom []))
(def rows
 (vec (mapcat
  (fn [f]
   (try
    (let [s (slurp f) as (aliases s) nsname (:current as)
          forms (e/parse-string-all s {:all true :features #{:clj} :read-cond :allow :auto-resolve (fn [a] (get as a a))})]
     (mapcat
      (fn [form]
       (let [definition? (and (seq? form) (#{'defn 'defn- 'def 'defonce 'defmacro} (first form)))
             n (when definition? (second form))
             attr (when definition? (first (filter map? (drop 2 form))))
             contract (or (:malli/schema attr) (:malli/schema (meta n)))
             owner (str (or nsname 'unknown) "/" (or n "<top-level>"))]
        (keep
         (fn [x]
          (let [head (when (and (seq? x) (symbol? (first x))) (first x))
                kind (cond (= 'catch head) "catch"
                           (and head (#{"ex-info" "diagnostic"} (name head))) "constructor"
                           (and (map? x) (every? #(contains? x %) [:seon.error/at :seon.error/layer :seon.error/operation])) "literal")]
           (when kind
            {:file (subs (str f) (count root)) :line (:row (meta x)) :end (:end-row (meta x))
             :kind kind :owner owner :ownerline (:row (meta form)) :form (pr-str x)
             :contract (pr-str contract) :contract-errors (mapv str (err-refs contract))
             :definition-errors (mapv str (err-refs form))
             :declares-identity (str/includes? (pr-str form) ":seon.error/declared-schema")})))
         (descendants form)))) forms))
    (catch Exception x (swap! failures conj [(str f) (ex-message x)]) []))) files)))
(spit "tmp/error-delivery-ast.json" (json/generate-string {:errors (mapv str (sort errors)) :rows rows :failures @failures}))
(prn {:files (count files) :schemas (count errors) :sites (frequencies (map :kind rows)) :parse-failures @failures})
```

## Appendix D. Probe requests and raw replies
All calls used root `/Users/sean/src/seon`, cluster `default`, and `timeout_ms:5000`. JVM calls used `read_only:true`. SCI calls selected only `audit-error-delivery-astra-20260926`. The branch was created before P1 and retired after P7; creation/retirement were the only branch lifecycle mutations. The wrappers below retain all returned MCP content blocks and the outer `isError` when present. Each text block is JSON-decoded once solely for readability; its `val` strings remain exactly those received. Absent `isError` is preserved as absence. No elisions have been inserted into reply content. Requests shown separately are the input forms; the bridge's own escaped form string is retained in the reply.

### P0 — jvmProbe

```clojure
(throw (ex-info "audit outer" {:audit/operation :read-only :audit/value 7} (ex-info "audit inner" {:audit/cause :sentinel})))
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "jvm",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "{:seon.dev.mcp/value {:via [{:type clojure.lang.ExceptionInfo, :message \"audit outer\", :data #:audit{:operation :read-only, :value 7}, :at [user$eval242513 invokeStatic \"NO_SOURCE_FILE\" 1]} {:type clojure.lang.ExceptionInfo, :message \"audit inner\", :data #:audit{:cause :sentinel}, :at [user$eval242513 invokeStatic \"NO_SOURCE_FILE\" 1]}], :trace [[user$eval242513 invokeStatic \"NO_SOURCE_FILE\" 1] [user$eval242513 invoke \"NO_SOURCE_FILE\" 1] [clojure.lang.Compiler eval \"Compiler.java\" 7757] [clojure.lang.Compiler eval \"Compiler.java\" 7712] [clojure.core$eval invokeStatic \"core.clj\" 3236] [clojure.core$eval invoke \"core.clj\" 3232] [user$eval242491 invokeStatic \"NO_SOURCE_FILE\" 1] [user$eval242491 invoke \"NO_SOURCE_FILE\" 1] [clojure.lang.Compiler eval \"Compiler.java\" 7757] [clojure.lang.Compiler eval \"Compiler.java\" 7747] [clojure.lang.Compiler eval \"Compiler.java\" 7712] [clojure.core$eval invokeStatic \"core.clj\" 3236] [clojure.core.server$prepl$fn__9102 invoke \"server.clj\" 235] [clojure.core.server$prepl invokeStatic \"server.clj\" 231] [clojure.core.server$prepl doInvoke \"server.clj\" 194] [clojure.lang.RestFn invoke \"RestFn.java\" 428] [seon.operator.prepl$io_prepl invokeStatic \"prepl.clj\" 57] [seon.operator.prepl$io_prepl doInvoke \"prepl.clj\" 50] [clojure.lang.RestFn applyTo \"RestFn.java\" 140] [clojure.lang.Var applyTo \"Var.java\" 707] [clojure.core$apply invokeStatic \"core.clj\" 667] [clojure.core.server$accept_connection invokeStatic \"server.clj\" 74] [clojure.core.server$start_server$fn__9044$fn__9045$fn__9047 invoke \"server.clj\" 118] [clojure.lang.AFn run \"AFn.java\" 22] [java.lang.Thread run \"Thread.java\" 1516]], :cause \"audit inner\", :data #:audit{:cause :sentinel}, :phase :execution}, :seon.dev.mcp/windowed? true, :seon.blob/digest \"80fd5aee9ddcee360ab95d0889f730d30fcc70f9c60354cdd7d32d4f273f2eb3\", :seon.blob/size 9748, :seon.dev.mcp/retrievable? true}",
            "ns": "user",
            "exception": true
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/failure": "evaluation",
        "seon.dev.mcp/form": "(throw (ex-info \"audit outer\" {:audit/operation :read-only :audit/value 7} (ex-info \"audit inner\" {:audit/cause :sentinel})))",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    },
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/branch-errors": {
          "seon.db/basis-t": 536870995,
          "seon.dev.mcp/count": 1,
          "seon.dev.mcp/errors": [
            {
              "seon.error/signature": "fa60ad84404c9720c3e6cd72ecdba9756f92bde2721e5568cf43d96d54a72aa3",
              "seon.error/operation": "seon.cluster.agent/turn-step",
              "seon.error.occurrence/message": "class clojure.lang.Symbol cannot be cast to class clojure.lang.Keyword (clojure.lang.Symbol and clojure.lang.Keyword are in unnamed module of loader 'app')",
              "seon.error.occurrence/count": 1,
              "seon.error.occurrence/branch": [
                "rc-m4-terminal"
              ]
            }
          ],
          "seon.dev.mcp/branch": "cluster",
          "seon.dev.mcp/since": 0
        }
      }
    }
  ],
  "isError": true
}
```

### P1 — sciProbe

```clojure
(throw (ex-info "audit own exception" {:audit/offending 7} (ex-info "audit own cause" {:audit/detail :sentinel})))
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "sci",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "Execution error (ExceptionInfo) at (REPL:1:1).\naudit own cause",
            "seon.cluster.eval/ordinal": 0,
            "seon.turn/id": "aede218492d4",
            "exception": true
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/failure": "evaluation",
        "seon.dev.mcp/form": "(throw (ex-info \"audit own exception\" {:audit/offending 7} (ex-info \"audit own cause\" {:audit/detail :sentinel})))",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ],
  "isError": true
}
```

### P2 — contractProbe

```clojure
(seon.id/digest 0 [])
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "sci",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "seon.id/digest refused length at []: expected an integer ([:int {:min 1, :max 64}]), got an integer 0. Fix: Supply an integer at []. Called from sci.lang.Var (lang.cljc:265).\n0",
            "seon.cluster.eval/ordinal": 0,
            "seon.turn/id": "168e25ab4bc4",
            "exception": true
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/failure": "evaluation",
        "seon.dev.mcp/form": "(seon.id/digest 0 [])",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ],
  "isError": true
}
```

### P3 — definitionProbe

```clojure
(defn audit-missing-contract [x] x)
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "sci",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "user/audit-missing-contract declares no :malli/schema; add a complete contract to its attribute map, e.g. (defn f \"Doc.\" {:malli/schema [:=> [:cat :int] :int]} [x] x).",
            "seon.cluster.eval/ordinal": 0,
            "seon.turn/id": "d8d86d8fa4bf",
            "exception": true
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/failure": "evaluation",
        "seon.dev.mcp/form": "(defn audit-missing-contract [x] x)",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ],
  "isError": true
}
```

### P4 — renderProbe

```clojure
{:audit/value 42 :seon.render/ai 'audit.missing/renderer}
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "sci",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "{:seon.error/at #inst \"2026-09-26T17:40:51.477Z\",\n :seon.error/data\n   #:seon.render.unknown{:call\n      [:seon.cluster.eval/source\n       \"{:audit/value 42 :seon.render/ai 'audit.missing/renderer}\"],\n    :output :seon.render/ai,\n    :producer audit.missing/renderer,\n    :reason :refused,\n    :refused-operation seon.sci.eval/install-function-from-database!,\n    :throwable \"clojure.lang.ExceptionInfo\"},\n :seon.error/expected :seon.render/rendered,\n :seon.error/fix\n   \"Inspect the renderer's refusal and repair its declared output.\",\n :seon.error/layer :seon.render/invocation,\n :seon.error/member :seon.render/output,\n :seon.error/message\n   \"The selected renderer did not return an observation.\",\n :seon.error/offending\n   \"Selected function has no acquired row; acquire its declaration before installing it.\",\n :seon.error/operation seon.render/unknown,\n :seon.render.unknown/call\n   [:seon.cluster.eval/source\n    \"{:audit/value 42 :seon.render/ai 'audit.missing/renderer}\"],\n :seon.render.unknown/output :seon.render/ai,\n :seon.render.unknown/producer audit.missing/renderer,\n :seon.render.unknown/reason :refused,\n :seon.render.unknown/refused-operation\n   seon.sci.eval/install-function-from-database!,\n :seon.render.unknown/throwable \"clojure.lang.ExceptionInfo\"}",
            "seon.cluster.eval/ordinal": 0,
            "seon.turn/id": "0be05015b9ab"
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/form": "{:audit/value 42 :seon.render/ai 'audit.missing/renderer}",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ]
}
```

### P5a — recordProbe

```clojure
(let [c (seon.db/branch-connection @(seon.cluster.boot/connection "default") :audit-error-delivery-astra-20260926) d (seon.db/db c)] {:audit/evaluations (seon.db/q '[:find [?e ...] :where [?e :seon.cluster.eval/source "(throw (ex-info \"audit own exception\" {:audit/offending 7} (ex-info \"audit own cause\" {:audit/detail :sentinel})))"]] d) :audit/errors (seon.error/occurred-since d 536870995)})
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "jvm",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "#:seon.dev.mcp{:value #:audit{:evaluations [34975], :errors []}, :windowed? false}",
            "ns": "user",
            "ms": 4
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/form": "(let [c (seon.db/branch-connection @(seon.cluster.boot/connection \"default\") :audit-error-delivery-astra-20260926) d (seon.db/db c)] {:audit/evaluations (seon.db/q '[:find [?e ...] :where [?e :seon.cluster.eval/source \"(throw (ex-info \\\"audit own exception\\\" {:audit/offending 7} (ex-info \\\"audit own cause\\\" {:audit/detail :sentinel})))\"]] d) :audit/errors (seon.error/occurred-since d 536870995)})",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ]
}
```

### P5b — receiptProbe

```clojure
(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection "default") :audit-error-delivery-astra-20260926))] (seon.db/pull d [:seon.cluster.eval/id :seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/triage-edn :seon.eval/renderer] 34975))
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "jvm",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "#:seon.dev.mcp{:value {:seon.cluster.eval/id \"19e832061b47\", :seon.cluster.eval/source \"(throw (ex-info \\\"audit own exception\\\" {:audit/offending 7} (ex-info \\\"audit own cause\\\" {:audit/detail :sentinel})))\", :seon.eval/shown \"Execution error (ExceptionInfo) at (REPL:1:1).\\naudit own cause\", :seon.cluster.eval/error \"Execution error (ExceptionInfo) at (REPL:1:1).\\naudit own cause\", :seon.cluster.eval/triage-edn \"{:clojure.error/class clojure.lang.ExceptionInfo, :clojure.error/line 265, :clojure.error/cause \\\"audit own cause\\\", :clojure.error/symbol sci.lang.Var/applyTo, :clojure.error/source \\\"lang.cljc\\\", :clojure.error/phase :execution}\"}, :windowed? false}",
            "ns": "user",
            "ms": 3
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/form": "(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection \"default\") :audit-error-delivery-astra-20260926))] (seon.db/pull d [:seon.cluster.eval/id :seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/triage-edn :seon.eval/renderer] 34975))",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ]
}
```

### P6 — contextProbe

```clojure
(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection "default") :audit-error-delivery-astra-20260926)) e (seon.db/pull d [:seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/triage-edn] 34975)] (seon.repl/text e))
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "jvm",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "#:seon.dev.mcp{:value \"user=> (throw (ex-info \\\"audit own exception\\\" {:audit/offending 7} (ex-info \\\"audit own cause\\\" {:audit/detail :sentinel})))\\n#:seon.repl{:error \\\"Execution error (ExceptionInfo) at sci.lang.Var/applyTo (lang.cljc:265).\\\\naudit own cause\\\"}\", :windowed? false}",
            "ns": "user",
            "ms": 4
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/form": "(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection \"default\") :audit-error-delivery-astra-20260926)) e (seon.db/pull d [:seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/triage-edn] 34975)] (seon.repl/text e))",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ]
}
```

### P7 — renderRecordProbe

```clojure
(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection "default") :audit-error-delivery-astra-20260926))] {:audit/errors (seon.error/occurred-since d 536870995) :audit/renderer-receipt (seon.db/q '[:find [(pull ?e [:seon.eval/shown :seon.cluster.eval/error :seon.eval/renderer]) ...] :where [?e :seon.cluster.eval/source "{:audit/value 42 :seon.render/ai 'audit.missing/renderer}"]] d)})
```

```json
{
  "content": [
    {
      "type": "text",
      "text": {
        "seon.dev.mcp/namespace": "user",
        "seon.dev.mcp/session-id": "default",
        "seon.dev.mcp/cluster": "default",
        "seon.dev.mcp/mode": "jvm",
        "seon.dev.mcp/events": [
          {
            "tag": "ret",
            "val": "#:seon.dev.mcp{:value #:audit{:errors [], :renderer-receipt [#:seon.eval{:shown \"{:seon.error/at #inst \\\"2026-09-26T17:40:51.477Z\\\",\\n :seon.error/data\\n   #:seon.render.unknown{:call\\n      [:seon.cluster.eval/source\\n       \\\"{:audit/value 42 :seon.render/ai 'audit.missing/renderer}\\\"],\\n    :output :seon.render/ai,\\n    :producer audit.missing/renderer,\\n    :reason :refused,\\n    :refused-operation seon.sci.eval/install-function-from-database!,\\n    :throwable \\\"clojure.lang.ExceptionInfo\\\"},\\n :seon.error/expected :seon.render/rendered,\\n :seon.error/fix\\n   \\\"Inspect the renderer's refusal and repair its declared output.\\\",\\n :seon.error/layer :seon.render/invocation,\\n :seon.error/member :seon.render/output,\\n :seon.error/message\\n   \\\"The selected renderer did not return an observation.\\\",\\n :seon.error/offending\\n   \\\"Selected function has no acquired row; acquire its declaration before installing it.\\\",\\n :seon.error/operation seon.render/unknown,\\n :seon.render.unknown/call\\n   [:seon.cluster.eval/source\\n    \\\"{:audit/value 42 :seon.render/ai 'audit.missing/renderer}\\\"],\\n :seon.render.unknown/output :seon.render/ai,\\n :seon.render.unknown/producer audit.missing/renderer,\\n :seon.render.unknown/reason :refused,\\n :seon.render.unknown/refused-operation\\n   seon.sci.eval/install-function-from-database!,\\n :seon.render.unknown/throwable \\\"clojure.lang.ExceptionInfo\\\"}\"}]}, :windowed? false}",
            "ns": "user",
            "ms": 4
          }
        ],
        "seon.dev.mcp/runtime": "clj",
        "seon.dev.mcp/cluster-state": "advertised",
        "seon.dev.mcp/form": "(let [d (seon.db/db (seon.db/branch-connection @(seon.cluster.boot/connection \"default\") :audit-error-delivery-astra-20260926))] {:audit/errors (seon.error/occurred-since d 536870995) :audit/renderer-receipt (seon.db/q '[:find [(pull ?e [:seon.eval/shown :seon.cluster.eval/error :seon.eval/renderer]) ...] :where [?e :seon.cluster.eval/source \"{:audit/value 42 :seon.render/ai 'audit.missing/renderer}\"]] d)})",
        "seon.dev.mcp/root": "/Users/sean/src/seon"
      }
    }
  ]
}
```
