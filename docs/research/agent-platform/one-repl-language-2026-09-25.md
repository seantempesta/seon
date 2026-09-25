---
type: design
status: revised design (Opus, revision 5, amended with the three corrections of Astra's ACCEPT-AFTER-FIXES review ff0615479: settlement printing, the fault-window contract, retrieval retirement; Q3 ruled (c); implementation authorized in slice order): Astra's revision-4 review (5f7558427, REVISE, seven findings and Q1-Q3) integrated in place, with the owner's rulings of 20:50Z (faults as errors in MCP replies) and 23:00Z (renderers read the stable db value; errors reference the world); builds on the composition law (AGENTS.md 14ccb65f0, ba41af0c0, 5dba6a14c) and the landings of S1, R3, R7 and the read-sink fix; no production code
created: 2026-09-25
revised: 2026-09-25
lane: design-one-repl-language-opus → revise-one-repl-opus → revise2-one-repl-opus → design-one-pipeline-opus
source-pin: 19a29c683 for every src/, script/ and resources/ citation (src/seon/sci/eval.clj is read from `git show HEAD:` because another lane holds uncommitted edits to it); §3.1-§3.3, §4 and §5 keep their 19e9fe5f4 / 3785970b9 citations, marked where they have moved
scope: the one REPL path as a composition of stages (read, evaluate, record, print) with every entrant supplying only its transport and its stage values; one definition and deletion authority; gated write-back; configuration layering; auto-pulled context; ordered and priced slices; requirement rows still to revise
---

# One REPL, one pipeline: read → evaluate → record → print, stages as values

**What exists.** Seon already owns every stage of one REPL path, and the agent's run is already their composition:

- **read**: the one reader, `seon.sci.reader/read` (`src/seon/sci/reader.cljc:860`), called twice per form today:
  once by the reply planner `seon.turn/planned-sources` (`src/seon/turn.clj:3124`, prose-tolerant, for model replies)
  at submission and again by `one-event` (`src/seon/sci/eval.clj:957`) in the namespace in effect;
- **evaluate**: `seon.sci.eval/evaluate` (`eval.clj:3882`) behind `evaluate-for-install` (`:4439`), with S1's single
  definition authority (`admit-program-change!`, `:860`);
- **record**: `turn/evaluation-facts` (`turn.clj:104`) and the receipt builders (`receipt-row` `:891`,
  `receipt-terminal-assertions` `:1372`, `receipt-read-evidence-tx` `:1382`), used by both `settle!` (`:3793`) and
  `record-evaluated-call` (`:1454`);
- **print**: `seon.repl` ("the ONE generator of REPL bytes", `src/seon/repl.clj:1-3`) over the render selection and
  floor, called today from inside the evaluator (`shown-result`, `eval.clj:3659`);
- **the composition**: `submit-source!` (`src/seon/cluster/agent.clj:635`) → run transaction → wake → the agent's Flow
  turn proc (`agent.clj:525`) → `turn/step` (`turn.clj:5564`) → `evaluate-sources` (`turn.clj:4681`) → `settle!`.

**Data flow after this design.** An evaluation request is a plain map: custody (root, cluster, branch, agent), source
text, the **segmenter** and the **evaluation stage** (a reader context and an evaluator, as qualified symbols checked
by declared schema name, defaulting to the agent's SCI path) and the declared bounds. The agent's run carries it.
`evaluate-sources`, the one callable composition, reads each form once with the stage's reader context, evaluates
it, finalizes every replacement of the result, prints it once and builds its record; the entrant's lifecycle owner
persists it (settlement for a run, the recording writer for a test member). Every entrant keeps only its transport,
custody and stage choice; errors and faults travel as values that reference the world.

**The principal Clojure approach** is `clojure.main/repl`'s own: the REPL is a loop over stage functions passed as
options (`reference-code/clojure/src/clj/clojure/main.clj:368`, `:414` destructures `read eval print caught`), and
`clojure.core.server/prepl` takes its printer as `valf` (`core/server.clj:194`, `:266-273` resolves a symbol). Seon's
pipeline is the same shape expressed as data on a durable run, plus the record stage Clojure does not have. The
precedent inside Seon is `seon.test/run`'s `:seon.test/recording-writer` (`src/seon/test.clj:2070`): a stage passed
as a value, with a default. No new loop, recorder, printer or store is added.

**What disappears** (§2.9): the MCP-only printer and projection (`mcp-project`, `mcp-valf`, `mcp-projection-error`,
`mcp-effective` and the ThreadLocal marks, `src/seon/cluster.clj:244-534`), the JVM error summary
(`exception-summary` family, `:334-367`), the dead `mcp-io-prepl` (`:535-553`), the bridge's two evaluation forms,
its error window and window bases, its re-decoding of shown text and its status printer
(`script/seon/dev/mcp.clj:37-38`, `:280-303`, `:347-431`, `:511-563`; `cluster.clj:633-658`): about 590 source lines
against about 310 added over P1-P5, and about 134 more with retrieval (P7, Q3 (c) as ruled). Until P7 and P6 land the milestone is a partial
migration (§2.9).

## 0. Broken things first

Revision-4 probes ran on default (pid 37460, started 2026-09-25T19:31:55Z, health `faulted`, 21 open faults; since
replaced by root's nuke, so they are historical and revision 5 ran none) through MCP, with one session (`design-one-pipeline`), JVM mode read-only except one SCI `(+ 1 2)` on the shared context.

1. **An MCP SCI evaluation records nothing, while an agent's evaluation records its whole history.**
   - Before: 108 evaluation entities and 42 runs. After SCI `(+ 1 2)`: still 108 and 42. The call returned `3` with
     an unsaved admission record (`:seon.eval/allocated-bytes 579392`, 5 ms).
   - An agent's stored evaluation carries `:seon.cluster.eval/{at author id ns ordinal read-basis-transaction
     read-evidence run source}`, `:seon.eval/{duration-ms shown}` and `:seon.sci.eval/ending-ns`.
   - Cause: `sci-evaluation-form` (`mcp.clj:380-425`) calls `seon.sci.eval/evaluate` directly with no agent, run,
     connection or handle, and without a branch it evaluates in the cluster's **shared** context.
2. **An MCP JVM failure loses its cause and names the wrong frame.** `(/ 1 0)` in JVM mode answers
   `exception-summary`'s map: message "Divide by zero", class, and frame
   `[seon.operator.prepl$io_prepl invokeStatic "prepl.clj" 57]`. That is the transport's own frame, chosen by
   `first-seon-frame` (`cluster.clj:339-346`) because its name starts with `seon.`. The `:via` chain, `ex-data` and
   trace are dropped (`cluster.clj:348-366`), and no error occurrence is stored (`seon.error/occurred-since` over the
   probe window answered `[]`).
3. **Every JVM value is reprinted by a second printer.** A JVM answer arrives as
   `#:seon.dev.mcp{:value {...} :windowed? false}`: `mcp-valf` → `mcp-project` admits the value, builds a print-node
   artifact, prepares a tree, reconstructs `admit/semantic-value` and prints it with `admit/canonical-edn`
   (`cluster.clj:388-534`). It is outside the one render path.
4. **The reply planner silently drops code that the one reader reads.** Probe (JVM, pure calls):

   | Text | `turn/planned-sources` | `seon.sci.reader/read` |
   |---|---|---|
   | six forms: `(import …)`, `(Date.)`, `#?(:clj 1 :cljs 2)`, `#"a+"`, `(str ::x)`, a syntax-quote | 4 sources; the reader conditional and the regex are gone, no refusal (0.86 ms) | — |
   | `#?(:clj 1 :cljs 2)` | "Your reply had no form; only comments/prose" | 1 event |
   | `#"a+"` | no forms | 1 event |
   | `1 #?(:clj 2)` | no forms | 2 events |
   | `(+ 1 2) (inc 1)` | — | 2 events |

   Revision 3 routed MCP SCI text through `submit-source!`, which calls `planned-sources` (`agent.clj:694`). That
   would have lost forms without a word. **An outside agent's code is code, not a model reply,** so its reader stage
   must be the code segmenter, with every form read once by the loop (§2.2). The planner's silent drop of a top-level reader conditional is a defect in its own
   right: it needs an issue from root (suggested path
   `docs/seon/issues/reply-planner-drops-top-level-reader-conditionals-and-regexes.md`; this lane writes no issue).
5. **Print precedes record, and a refused install prints twice.** `evaluate` renders the shown result inside the
   evaluator (`eval.clj:4141` for failures, `:4380` for success). `refuse-install` renders again for the refusal
   (`:4500`). `accept-candidate!` replaces the value after it was printed (`:4488`). Settlement then stores whichever
   text survived. The target is one print of the final evaluation (§2.5).
6. **The recorder for completed evaluations has no production caller.** `record-evaluated-tx` (`turn.clj:1527`) and
   its transaction function `record-evaluated-call` (`:1454`) are called only by three test namespaces
   (`test/seon/cluster/{prompt,evaluate_sources}_test.clj`, `test/seon/render/transcript_test.clj`). It hard-codes
   `:seon.cluster.eval/author :system` (`:1481`) and refuses an agent with an open run (`:1497`). §2.4 decides its
   fate.
7. **`mcp-io-prepl` is dead in production.** The listener's accept function is `seon.operator.prepl/io-prepl`
   (`src/seon/cluster/boot.clj:426`, `script/seon/operator.clj:351`). `seon.cluster/mcp-io-prepl` (`cluster.clj:535-553`)
   is called only by `test/seon/mcp_test.clj:23`.
8. **JVM mode keeps a copy of the one-form policy.** Since R7 (2828bc808), JVM mode reads through the one reader, but
   `jvm-evaluation-form` repeats `one-event`'s count and message (`mcp.clj:367-378`), because `one-event` reads SCI
   namespace bindings from a context (R7 landing, "Remaining duplicate").
9. **A submitted run may pay the whole-history refresh.** After a closed non-generate run, an agent that holds an issue
   runs `system-turn` (`turn.clj:5295-5305`), whose candidate selection cost 3.3 s for root
   (task-context design §0.3, reported there). The task-context design owns the fix, and M1 is blocked on it (§2.10).
9a. **No product-agent writer exists.** The only agent-row writer found is `seed-root-agent!` (`cluster.clj:3231`);
   `arm!` requires a committed agent entity (`agent.clj:1060-1062`). A named outside agent for MCP therefore has no
   writer to call, which is why §2.6 uses root on a branch, behind probe P-RB.
9b. **Stage compatibility cannot come from arming alone.** Malli instruments a function against the schema it is
   given (`reference-code/malli/src/malli/core.cljc:3131-3153`); an armed Var with another contract can still return
   the wrong stage value. §2.0 checks the declared schema by name.

**Still open from revisions 1-3, carried here as their durable home** (S1 landed as 91eb8e4a5 and the revise-2
commits c09aad058, 10887f9a9, c21a89118, e8cada147, which closed revision 3's items 2 and 3 and the nested-unmap hole
of item 4(iii) by refusal):

10. **A qualified `defn` into another namespace is refused, in SCI as in Clojure**
    (`reference-code/sci/src/sci/impl/analyzer.cljc:839-849`; Clojure `Compiler.java:538-552`). A same-namespace
    qualified `defn` is refused as unindexed because `seon.fn/source-rows` names it `ns/ns/f` (S1 landing). 1.10's
    clause stays deferred (§2.2).
11. **Deletion keeps two authorities.** Top-level `ns-unmap` is special-cased by the reader
    (`src/seon/sci/reader.cljc:395-404`, pin 3785970b9) and `my.program/ns-unmap!` commits and then mutates the shared
    base (`src/my/program.clj:527-542`, same pin). A nested unmap of a row-backed Var is now refused as unindexed
    instead of silently removing the Var (S1 landing table). §3.2 is the design.
12. **Entry refusals render as contract findings** (`docs/seon/issues/entry-gate-refusals-name-evaluate-and-render-as-contract-findings.md`),
    and SCI `(/ 1 0)` shows "failure-value refused … got nil nil" (bridge landing).
13. **`config apply` loses layers for the cluster it applies, and nine readers have no bound cluster identity.**
    Revision 3's items 8 and 9, unchanged; §4.4 is the design.
14. **Inline render functions get no supplied defaults** (`src/seon/sci/kernel.clj:633-640`; revision 3 item 10);
    the fix belongs to `call_preparation.clj`'s owner (§2.5).
15. **Allocation is large and unattributed.** A trivial SCI evaluation allocated 579 KB here, a `(vec (range 300))`
    457 MB (bridge landing), and a first refused `defn` 648 MB (S1 revise-2 landing); issue
    `e38986c8c` records ~0.5 GB for a trivial evaluation. §2.10 measures per stage.

## 1. The failures, and what would have prevented each

| # | Failure (verified source) | Cause | Prevention |
|---|---|---|---|
| 1 | OOMs: an overlay `pr-str`'d as `:seon.error/offending` (open-items.md:162-168); an infinite `(range)` key hashed by floor code (:52-61); a >2 GB PREPL answer (fix-schedule-2026-09-23.md:184) | unbounded printing, hashing and ordering before presentation | classify with a bound before hash, order or path; bound serialization during encoding; one presentation boundary |
| 2 | Publication order (5c80755da) | incoherent publication | fixed by atomic publication; the reverse edge is a typed `breaks`/`callers` field (§5) |
| 3 | Shipped `no-provider true` (fixed 3a7ffa69c) | development policy in the shipped layer | separated layers, with each default's provenance reported (§4.4) |
| 4 | Refusals dropped data (`boot.clj:527-536`; open-items.md:49) | print-then-read inside the system | typed refusal values kept; rendered at the edge |
| 5 | MCP is a parallel REPL (audit §1.1) | a second submission and result policy | every MCP mode is an agent run with its own stage values (§2.6) |
| 6 | Paused hooks, stale Vars; a 16,352 ms two-file publication | whole-program recomputation in publication | cost follows changed inputs (publication owner's cut); intended branch differences reported separately from drift |
| 7 | False premises (S2b-c landing :36-48) | unsupported diagnosis; stale graph | the task opener pulls positive analysis evidence and its unknowns; an empty answer is never proof |
| 8 | Three floor design rounds before prior art (717e01bbb) | the dependency seam was not in view | a design task receives the seams its subjects reach (§5) |
| 9 | Issues cite mentions as subjects; 45 open notes not indexed as open (revision 3) | prose parsed for relations | tasks carry code-owned subject edges and test-owned proof edges; the Markdown body is linked evidence (the task owner's plan, §3.5) |

## 2. The one REPL path as a composition of stages

### 2.0 The pipeline, as data

**One callable composition.** The pipeline is `seon.turn/evaluate-sources` (`turn.clj:4681`), which already runs
every agent turn, submitted source, opening, refresh and page preview. It takes a request map and answers the final,
printed evaluations. What happens to them afterwards belongs to the entrant's **lifecycle owner**: `settle!` for a
run, the test request's `:seon.test/recording-writer` for a test member, nothing for a preview. The run is the
lifecycle of every recorded REPL entrant except test members (§2.7); every entrant enters the same composition.

The request, with registered keys except the three marked new:

```clojure
{:seon.boot/root         "/Users/sean/src/seon"          ; custody: which world
 :seon.boot/cluster-name "default"
 :seon.agent/branch      :lane-x                          ; absent: the cluster's own branch
 :seon.agent/id          "root"                           ; §2.6
 :seon.cluster.reply/text "(in-ns 'n)\n(defn f …)"
 :seon.turn/segmenter    'seon.cluster.reply/code-segments ; NEW; absent: the reply planner
 :seon.turn/evaluation-stage                              ; NEW; absent: the SCI stage
 {:seon.turn/read-context 'seon.repl.jvm/read-context     ;   the reader context for the next form
  :seon.turn/evaluate     'seon.repl.jvm/evaluate}        ;   the evaluator
 :seon.ns/name           'n                               ; the explicit namespace, which wins (§2.6)
 :seon.await/bound       {…}}                             ; the entrant's completion bound
```

`:seon.cluster.eval/evaluation-stage` (NEW) records the stage map on each evaluation that did not use the default.

**The order of the stages, and when each becomes durable.** For each segment, in order:

1. **segment** (at submission). The segmenter separates code from prose and answers segments that keep their **raw
   source** and offsets. The reply planner (`planned-sources`, `turn.clj:3124`) is the segmenter for model replies;
   `code-segments` answers the whole text as one segment. Segmentation never reads semantically. The run stores
   the segments (`:seon.turn/sources`, as today).
2. **read** (in the loop, once per form). The loop asks the stage's `read-context` for the reader context left by
   the previous evaluation (SCI: `reader-context`, `eval.clj:940`, over the forked context; JVM: the host namespace's
   aliases, Vars, classes and data readers), then calls the one reader for the **next** form at the segment's offset.
   That is the only read: the event is passed to the evaluator through the existing seam (`evaluate-for-install`
   takes `:seon.sci.eval/event`, `eval.clj:4448-4461`), so `one-event` does not read again. A reader failure refuses
   that form; the refusal keeps the unread remainder of the source verbatim, and later forms do not run.
3. **evaluate** (the stage's `evaluate`). Answers a native `:seon.sci.eval/evaluation`, with no shown text.
4. **finalize**. Every replacement of the result happens before the print: install acceptance and refusal
   (`accept-candidate!` `eval.clj:4465`, `refuse-install` `:4491`), the disposition refusal
   (`disposition-rule-error` and `unrun-evaluation`, `turn.clj:4772-4789`, `eval.clj:3828`), **and the disposition's
   completion**, which today adds `:my.turn/delivered-to` and re-admits the value inside terminal-data preparation
   (`evaluation-terminal-data`, `turn.clj:3466-3485`). That completion moves ahead of the print, so the saved text
   shows the value that is committed. Each constructor answers a native evaluation value; none prints.
5. **print** the final value (§2.5). The form's read sink still covers it, so render reads stay the form's. The bytes
   are **speculative** until step 7 commits them.
6. **record construction**. `evaluation-facts` (`turn.clj:104`) builds the facts, including the shown bytes it
   consumes (`:126-128`). Nothing is written yet.
7. **persist**, by the lifecycle owner (settlement for a run), through its existing branches: `settle!` commits the
   prepared data, and on a writer-time refusal takes its one bounded refusal branch (`turn.clj:3840-3863`; the batch
   form at `:3622-3633`, `:3779-3788`). Only after a commit does any entrant receive a result.

**The guarantee is one saved and returned rendering of the committed terminal value**, not one render call. If the
writer refuses, the speculative success bytes are discarded, never stored or returned; the refusal is a distinct
native value, rendered through the same print owner, and persisted by the existing refusal branch. That path
therefore renders twice and attempts two transactions, and the proofs count both honestly. The refusal branch's own
printers, `refusal-terminal-data`'s `pr-str` (`turn.clj:3704`) and `settle-batch-refusal!`'s (`:3753`, lost-error F10),
retire into the same owner, coordinated once with F10's owner. Nothing re-runs the source or renders inside a
transaction function. If storing the refusal fails, that is a core failure and panics visibly under
`:seon.config/on-core-error :panic` (AGENTS.md "Panic visibly"); no durable answer is promised that storage did not
produce.

**Stage values are qualified symbols of program functions, checked by declared schema name.** A symbol survives the
run's durability where a function object would not. Each stage has a registered function schema:
`:seon.turn/segmenter-fn`, `:seon.turn/read-context-fn` and `:seon.turn/evaluate-fn`. The default evaluator's input is
the full request it requires today, `[:and :seon.sci.eval/request :seon.test.accretion/candidate-context-request]`
(`eval.clj:4442-4445`), and its output `:seon.sci.eval/evaluation`. At submission the symbol is resolved to its
function row in the execution's program, and its declared `:malli/schema` must **be** the stage schema, by name
(AGENTS.md "Match supplied defaults by declared schema name"; structural resemblance is not identity). A missing
row, or a different or broader contract, refuses at submission by name before any run is written. At the loop
boundary the returned value is validated against the stage's output schema through the existing arming owner; an
invalid value refuses as the form's error. No function-subtyping check and no stage registry are added.

**Record and print are not stage values.** An entrant cannot choose a recorder or a printer: the evaluation's facts
and its declared renderer decide.

### 2.1 The entrants today, stage by stage

"Shared" means the entrant calls the owner named in §2.0; "copy" means it has its own. Citations at 19a29c683.

| Entrant | Transport | read | evaluate | record | print |
|---|---|---|---|---|---|
| Agent turn (model reply) | Flow turn proc (`agent.clj:525`) → `turn/step` (`turn.clj:5564`) | shared, but **twice**: `planned-sources` parses at submission, `one-event` (`eval.clj:957`) again per form | shared: `evaluate-for-install` (`eval.clj:4439`) via `gate-function-install` (`turn.clj:3222`) | shared: `settle!` (`turn.clj:3793`) → `evaluation-facts` (`:3533`) | shared, but **inside the evaluator**, and again for refusals (§0.5, finding 4) |
| System-submitted source (`virtual-turn!`, `turn.clj:2293`; the page's send) | `submit-source!` (`agent.clj:635`) → run → same proc | as above | shared | shared | as above |
| System openings and refreshes | `system-turn` (`turn.clj:2077`) | as above | shared, through `evaluate-sources` | shared | as above |
| Page preview | `preview-sources` (`turn.clj:4822`) | as above | shared, same fork and loop | none, by declaration | as above |
| **MCP SCI mode** | io-prepl form `sci-evaluation-form` (`mcp.clj:380-425`) | `one-event` inside `evaluate` (single form) | shared `evaluate`, but **no agent, run or handle**, and the **shared** context without a branch | **none** (§0.1) | shared shown text, then **re-decoded** (`mcp.clj:280-303`) and chosen by `mcp-project` (`cluster.clj:420-431`) |
| **MCP JVM mode** | io-prepl form `jvm-evaluation-form` (`mcp.clj:347-379`) | the one reader (R7), plus a **copy** of `one-event`'s count policy (`mcp.clj:367-378`) | **copy**: `clojure.core/eval` in the form | **none**; the exception is summarised, not stored (§0.2) | **copy**: `mcp-valf` → `mcp-project` → `admit/canonical-edn` (`cluster.clj:388-534`) |
| MCP fault window | a second prepl form, `window-form`/`branch-errors!` (`mcp.clj:511-563`) | — | **copy**: `seon.error/occurred-since` over the bridge atom `window-bases` (`mcp.clj:37`) | **copy**: bases held in the bridge process | **copy**: the bridge's own map, clipped at 20 (`mcp.clj:38`) |
| MCP `runtime_status` | prepl form over `mcp-runtime-observation` (`cluster.clj:659`) | — | status read | none | **copy**: `bounded-face`/`bounded-summary` (`cluster.clj:633-658`) |
| MCP `branch` | prepl form `branch-form` (`mcp.clj:442-482`) | — | registry calls | the registry's | **copy**: the bridge builds its own `refuse#` error maps |
| MCP `get_value` | `execute-get-value` (`mcp.clj:753-782`) → `mcp-get-value` (`cluster.clj:555-611`) | — | blob read of the artifact `mcp-project` wrote (`cluster.clj:444-479`) | — | **copy**: its own window over the artifact |
| Test member | `seon.test/run` (`test.clj:2061`) → `member-result` (`:1846`) → `bounded-result` (`:211`) on a virtual thread | **copy** by necessity today: `resolve-test` (`:1411`), symbol to Var | SCI bodies under the SCI arm (`sci.eval/run-test`, `eval.clj:4530`); host bodies unarmed (`runner/run-var!`, `runner.clj:401`) | **copy**: the test owner's own result transaction; no evaluation facts | **copy**: `failure-text`, `failure-message` (`test.clj:37-69`), `failure-report` (`runner.clj:160`) |
| Operator commands (`bin/seon`) | `seon.operator.prepl/io-prepl` (`resources/seon/operator/prepl.clj:50`) | Clojure's reader | Clojure's `eval` | none (diagnostic; ruled exception, review P1-F) | `mcp-valf` when `seon.cluster` is loaded (`prepl.clj:26-27`), else `pr-str`; the floor for failures (`:6-15`) |
| Publication | `seon.cluster.source/publish!` (`src/seon/cluster/source.clj:570`) | the analyzer over file bytes, and Clojure's reader in `require :reload` (`cluster.clj:2144`) | Clojure's compiler | program rows (the program partition) | the publication report |

The agent's composition is complete for five entrants except for the double read and the in-evaluator print, which
P1 and P2 fix for all of them at once. Publication is not a REPL entrant: files are the write-back of accepted rows,
and publication shares with the evaluator the one stage that matters, the analyzer, which S1's
`admit-program-change!` also calls. A future "evaluate this file in my context" entrant is `code-segments` over the
file's bytes, with the file as provenance.

### 2.2 Read once (slice P2, a prerequisite of P3 and P4)

- **Segmentation is not reading.** The reply planner drops code it misreads as prose (§0.4), so outside agents'
  code uses `code-segments`, which answers the raw text as one segment. The planner stays for model replies. Its
  silent drops are its own defect (§0.4's issue).
- **The loop reads each form after the namespace effects of the previous one**, as `clojure.main/repl` does
  (`main.clj:414-446`), with the reader context the evaluation stage supplies. So `(require '[x :as a])` followed by
  `::a/k` reads correctly in both evaluators, and JVM code is read with host mappings, never with an SCI context.
- **What the loop needs from the reader:** the next event from an offset, with its end offset. The reader already
  resumes the same text from an offset during recovery (`advance-to!`, shared with `skip-fences!`, S1 revise-2
  finding 6). Whether `seon.sci.reader/read` exposes a one-event-from-offset call is UNVERIFIED; if not, P2 adds that
  entry over `advance-to!`, and nothing else.
- **Refusal.** A malformed form refuses as the reader's declared error, with its position; the remainder of the
  segment is kept verbatim on the refusal, and nothing after it runs. An unmatched trailing closer remains a reader
  error after the forms before it (S1 P1-C).
- **What P2 deletes:** the second read of each form (the loop now always supplies the event, so `one-event`'s read
  runs only for its other callers), the planner's second parse of the same text (`reply.clj:350-359`, pin
  3785970b9), and, in P4, the JVM copy of the count policy. The exact deletion is priced by P2's call-graph trace.
- **Proof:** a reader-invocation counter shows one read per form for a reply, an MCP call and a JVM call; effects run
  once; namespace changes, aliases, a reader conditional and a regex each read correctly; a malformed trailing form
  refuses with the remainder retained.

**Qualified definitions stay deferred** (1.10's `(defn other.ns/f …)` clause; §0.10). The later cut separates the
reader context from the target evaluation and analysis context, expands once under the existing arm, and passes the
target namespace and aliases to `definition-rows`. Proofs: a single expansion; a target alias resolves; a
reader-resolved `::kw` behaves correctly; an unknown namespace refuses by name; a private def works; the session
namespace is unchanged; `seon.fn/source-rows` stops doubling a same-namespace qualified name. No shim.

### 2.3 Evaluate

**The SCI stage is the default and is unchanged.** S1 landed its single definition authority (landings
`lane-impl-repl-m1-2026-09-25.md`, `lane-impl-repl-s1-revise-2-2026-09-25.md`): every changed function or test Var
matches exactly one analyzed row by the analyzer's byte span and SCI's root-write mark, or the form refuses as
`:seon.sci.eval/unindexed-change` or `:seon.sci.eval/multiple-declarations`; contracts validate and arm inside the
boundary; one capture before any mutation restores at every failure point; a restoration that cannot run leaves the
execution unavailable with its whole cause. Limits: classes of a refused `defrecord` stay defined; point-free `def`s
and macro-generated `defn`s refuse as unindexed until the analyzer classifies them.

**The removal member** is still to build (S4, §3.2): before the form, the evaluator holds SCI's `namespace-state`
(`reference-code/sci/src/sci/core.cljc:751-760`, O(1)); afterwards only the namespace maps not `identical?` to the
held ones are diffed by key; a removed intern counts only when the held before-state gives it a row-backed identity.

**The JVM stage** is `{:seon.turn/read-context seon.repl.jvm/read-context, :seon.turn/evaluate seon.repl.jvm/evaluate}`,
`jvm-evaluation-form`'s body as two contracted program functions:

- `read-context`: the namespace's `ns-aliases`, `ns-map` Vars and classes, and `*data-readers*` (`mcp.clj:357-364`),
  creating the namespace with `clojure.core` referred when the explicit request namespace names a new one.
- `evaluate`: bind `*ns*`; take the host change mark; `eval` the supplied event's form with `*out*`/`*err*` bound to
  the same byte-bounded capture the SCI evaluator uses; on a throw, build the error with `kernel/failure-value`
  (`src/seon/sci/kernel.clj:526`), so class, message, `ex-data` and chain reach settlement's error storage and
  delivery; offer `:seon.render.web/runtime-eval` to the page channels the request carries when the mark changed;
  answer `:seon.sci.eval/evaluation` with the admitted value (`admit/admit-value`), the ending namespace
  (`:seon.sci.eval/ending-ns` is a symbol, `seon.sci.eval.edn:177`, so a host namespace needs no row), the admission
  record, and **no program row**. `gate-function-install` sees no candidate row and passes it through.

**The host change mark is a cost question.** Today's `loaded-code-mark` (`cluster.clj:253-275`) walks every loaded
namespace's mappings, so its cost follows the JVM's size, not the change. P4 measures it per evaluation. The smaller
alternative, only `clojure.lang.Var/rev`, detects Var creation and root changes but not an unmap; P4 either shows
the full mark within the regression bound or states that trade for root.

**JVM evaluations are never program** (AGENTS.md, "One JVM, many realities"). **Bounds:** host code cannot be
interrupted, so the JVM stage's bound is the entrant's completion bound (§2.6): at expiry the caller gets the run id,
the agent stays busy until the host call exits, and nothing overlaps or replays.

**The test stage** is `{read-context: the member's SCI or host context, evaluate: seon.test/capture-evaluate}` (§2.7).

### 2.4 Record

**Errors and facts reference the world; they never copy it** (owner ruling 23:00Z). A database value becomes its
commit id (`seon.db/commit-id`) and basis-t; an SCI context becomes the agent id and branch; a request becomes the
evaluation or run reference (`:seon.cluster.eval/id`, `:seon.turn/id`); a stored value becomes its blob digest. This
applies to every error the pipeline produces: the JVM stage's failure, a stage-contract refusal, a submission
refusal, `evaluate-source!`'s timeout answer, and the fault observation. None carries a database value, a context,
a request map or a connection.

| Entrant or material | Disposition |
|---|---|
| Product replies, system submissions, MCP SCI and MCP JVM | one run each, settled by `settle!`; the stage map on each non-default evaluation |
| Generated openings and refreshed reads | the same recording owners (`system-turn`); historical text and read evidence kept; writes never replay |
| Refresh candidates | only evaluations of the default stage: `read-only-evaluation?` (`turn.clj:1959`) excludes any evaluation carrying `:seon.cluster.eval/evaluation-stage`, because refresh re-evaluates in SCI |
| Test members and task proofs | the same composition and `evaluation-facts`, persisted by the test request's recording writer onto the retaining branch (§2.7) |
| Test-selection refusals | the test request's own declared result |
| Raw source, evaluation facts, program rows | source and provenance kept apart from declarations; only the program partition merges |
| Live result objects, private state | existing SCI-context handles (`result/e…`); never serialized; after restart, typed unknown; a JVM result has no handle |
| Error occurrences, result and report blobs | existing staging, publication and retention owners; core faults also get cluster visibility and delivery |
| The fault observation of each settled evaluation | lost-error design §M, as revised there (its M2-M6): computed once **before** settlement from the captured **cluster** database value (not the agent's branch value); its exact shown text saved with the reply's units; one `:seon.cluster.eval/fault-window` component referencing the cluster identity and commit and the distinct lower and upper bases; only a committed settlement advances the lower bound; the window belongs to a batch's last form; first, compacted and changed-lineage windows are `:seon.error/window-unknown`; history renders the saved text and never recomputes (count and last-at keep no history) (P3m) |
| Host prepl (operator commands, `mode "host"`) | nothing, by the ruled diagnostic exception; the reply says `:seon.dev.mcp/recorded? false` |

**`record-evaluated-call` is not a JVM recorder.** JVM evaluations are runs, so settlement records them, and the
completed-run recorder keeps no production caller (§0.6). Whether tests keep it as a fixture writer or move to
`virtual-turn!` is root's call (about 130 lines, `turn.clj:1454-1585`).

**The durable result reference** (retrieval of a value beyond the print budget) is Q3, decided before P4's
deletions are booked (§2.6).

### 2.5 Print

Selection per face, first match wins (11.1; AGENTS.md "Render ordinary values totally"): the value's own key; a
contract-fitting render function in the **viewing** agent's namespace; the schema's declared pair; the floor.

- **One finalization point (P1).** The print stage is called once by `evaluate-sources` on the value step 4 of §2.0
  finalized. It replaces six printers: `evaluate`'s failure and success renders (`eval.clj:4141`, `:4380`),
  `refuse-install`'s (`:4500`), `unrun-evaluation`'s `pr-str` (`:3856-3862`), which today shows a disposition
  refusal outside the render path, and the settlement refusal branch's two `pr-str`s (`turn.clj:3704`, `:3753`),
  which render a writer-time refusal through the same owner (§2.0, step 7). The evaluator carries print's inputs as data: the admitted value, its record and a
  top-level call's declared returns (`:seon.render.call/function`, computed today at `eval.clj:4376-4377`).
- **Renderers may read the stable database value** (owner ruling 23:00Z). The print stage supplies the form's
  database value (the request's `:seon.db/db`) by declared schema name to any renderer whose contract declares it,
  exactly as call preparation supplies defaults; caller arguments win. The value is immutable, so a render read is
  reproducible, and the form's read sink records it.
- **The shown bytes are the evaluating agent's view,** saved exactly and returned verbatim after persistence. Another
  viewer gets a current view, labelled as such, from the same selected value, tree inputs, profile and render-program
  identity.
- **MCP returns the stored shown bytes of the run's evaluations** in ordinal order, plus the fault observation's
  rendered unit, and adds no printing. An MCP session's rendered context is the task-context design's view; a
  taskless agent gets its loud no-task value.
- **Render inputs come from call preparation (1.13).** For an inline function value, the shared owner consumes its
  retained declared contract through Malli (`m/schema`, `m/properties`, the cached `m/validator`,
  `reference-code/malli/src/malli/core.cljc:2561`, `:2592`, `:2637`); this belongs to `call_preparation.clj`'s owner
  (§0.14).
- **The host prepl's presentation is bounded.** Its `valf` prints an operator protocol reply (a request form's
  declared, bounded map, marked by the operator's request form) with `pr-str` for `operator.clj`'s Clojure reader
  (`script/seon/operator.clj:133`), and every other value with the floor's AI face under the floor's one budget; the
  floor for failures (`prepl.clj:6-15`). Unlimited `pr-str` of an arbitrary host value is gone with `mcp-valf`.
- **OPEN 9.5 (owner): may the floor realize a lazy value?** Unchanged: the recorder never realizes a value to store
  it; a `*print-length*`-style bounded prefix labelled as realized is the alternative, for the floor owner.

### 2.6 MCP: transport, custody and stage choice

Each `eval_clj` call is one agent run. The bridge decides only custody, the segmenter and the evaluation stage:

| `eval_clj` call | Agent and custody | Segmenter | Stage | Lifecycle |
|---|---|---|---|---|
| `mode "sci"`, no branch | root, on the cluster's branch (Q2 (b)) | code | SCI | root's run |
| `mode "sci"`, `branch` | root, on that branch: root's inherited row, armed on the branch's handle by this session, which records that it armed it (UNVERIFIED seam P-RB, below) | code | SCI | root's run on the branch |
| `mode "jvm"` | root, on the cluster's branch (Q1 (a), ruled) | code | JVM | root's run |
| `mode "jvm"`, `branch` | refused, as today (`mcp.clj:579-583`) | — | — | — |
| `mode "host"` | none: the diagnostic host prepl, named by the caller | Clojure's | Clojure's | none (ruled exception) |

**The one entry, `seon.cluster.agent/evaluate-source!`,** is `submit-source!` plus completion, in its owner:

1. resolve custody from the running instance (the cluster handle, or the branch handle through `acquire-context!`)
   and check the stage symbols (§2.0);
2. register a Datahike listener for this agent's terminal run fact **before** submitting (`d/listen`, as
   `src/seon/cluster/wake.clj:499` uses it);
3. call `submit-source!`, which allocates and returns the run id (`agent.clj:697`, answered at `:716`);
4. await that run's terminal fact with `seon.await/await!` (`src/seon/await.clj:106`) under the request's bound,
   filtering by run id, then re-read durable state once, so a close before the listener saw it is not missed;
5. answer the run id, the stored shown bytes of its evaluations and its fault observation, read by run id through
   `run-receipts` (`turn.clj:1720`) and `current-run` (`:332`), which P3 makes public with their contracts. The
   outcome is `:seon.cluster.eval/outcome` (lost-error §M4), which the bridge encodes as `isError`.

**Faults are errors in the reply** (owner ruling 20:50Z). The fault observation is lost-error design §M's, exactly
(§2.4's row): one query owner, `occurred-since` changed in place to return every signature touched in the window
with its resolution as data; the observation is one unit of the reply, sharing the reply's one presentation budget
and the render owner's continuation; the outcome is `:error` when the evaluation failed or the observation holds a
new, undelivered or unknown fault, and MCP only encodes it as `isError`. A core fault raised during the call answers
as the call's error value, never as an empty success. The bridge's window machinery retires in the same cut (P3m).
The bridge never classifies or formats a fault.

**Namespace continuation is per evaluation stage.** The explicit request namespace wins. Otherwise the starting
namespace is the ending namespace of this agent's latest evaluation **of the same stage** on this branch (the
default stage's evaluations carry no stage key). For SCI it must resolve to a namespace row through the existing
pull (`agent.clj:665-677`), or the submission refuses naming it. For JVM it is a symbol (a host namespace need not be a
row). A JVM ending namespace never continues an SCI run, or the reverse. With no prior evaluation of the stage, the
request namespace, else `user` for JVM and the agent's assigned namespace for SCI.

**Busy, timeout and exit are the agent's.** The run transaction refuses a busy agent. Flow runs one transform at a
time on the turn proc's thread (`agent.clj:525-545`); across graph replacement `arm!` refuses until the previous
executor has terminated. At the bound the answer is the run id and the requery form over `run-receipts`; ownership is
retained; the next submission is refused as busy until settlement; nothing replays.

**Settings stay the agent's.** The bridge changes no setting. A submitted source run evaluates only its sources and
opens no provider call (`virtual-turn!`, `turn.clj:2293`: "without a provider"); whether the agent's graph later
opens a model call is the agent's ordinary policy. P3 proves that no provider attempt row follows a submission; if
one does, P3 stops and reports it rather than setting `no-provider` on root.

**Retirement.** `branch retire` disarms only what the session armed, in order: `disarm!` (`agent.clj:1280-1305`)
joins the executor, which is the actual-exit proof; `release-context!` (`:804`) releases the context; the branch
retires. If the executor has not exited, the branch and its custody are **not** released and the answer names the
live run. A branch held by an agent the session did not arm keeps today's refusal (`mcp.clj:476`).

**P-RB (UNVERIFIED, first step of P3):** that root's inherited row can be armed on a lane branch's handle through the
existing `arm!` (`agent.clj:1044`, which requires a committed agent entity, `:1060-1062`) and routed separately from
root on the cluster branch. If it cannot, branch SCI needs a named outside agent, and **no product-agent writer
exists**: the only agent writer found is `seed-root-agent!` (`cluster.clj:3231`). That writer, its contract and its
failure cleanup would then be a priced prerequisite (about +25 in `seon.agent`), not bridge state.

**What the bridge keeps:** transport, discovery, sessions, and adapters for `runtime_status`, `branch` and `get_value`
that call owners and encode their answers. `runtime_status`'s observation renders through ordinary selection (P5,
render slice 6 row 9); `branch`'s refusals become declared errors produced by `seon.cluster.registry` (P5);
`get_value` follows Q3.

### 2.7 Tests: the member enters the same composition

A test member is an isolated agent context for one body (`test.clj:2074-2075`). It enters `evaluate-sources` like any
entrant, with its own custody and stage; `seon.test/run` keeps batching, selection, bounds, isolation, retention and
exit ownership.

- **Input adaptation.** The member's source is its call form, `(ns/test-name)`, segmented by `code-segments` and read
  once by the loop. `seon.test/capture-evaluate` resolves the Var through `resolve-test` (`test.clj:1411`) from the
  read form's head symbol, and runs the capture owner, which wraps the body once and returns its typed report
  (`runner.clj:359-399`; SCI bodies through `sci.eval/run-test`, host bodies unarmed through `runner/run-var!`,
  because a host body may evaluate its own SCI contexts). `bounded-result`'s thread and exit observation stay around
  the whole call.
- **Result adaptation.** `capture-evaluate` answers `:seon.sci.eval/evaluation` whose admitted value is the
  `:seon.test.runner/captured-result`, with an error present exactly when the body failed or was not runnable. Its
  contract is `:seon.turn/evaluate-fn`, so it is checked like any stage.
- **Record and print.** The loop finalizes and prints it once; `evaluation-facts` builds its facts; the request's
  `:seon.test/recording-writer` persists them onto the **retaining** branch before the member branch is unlinked,
  with member origin and tested identities. `failure-report` and `report-identity` (`runner.clj:160-204`) keep native
  operands, rendered once, which retires audit R10 (50 lines).
- **Not a run per member.** The member does not take a wake and run transaction per body, because the composition
  does not need one: the run is a lifecycle, and the test request is the member's lifecycle. The cost of a run is
  measured in P3 (§2.10), not assumed.
- **The candidate gate is one ordinary `seon.test/run` request** on the actual candidate, retiring audit C1 (87 lines),
  conditional on candidate fixture, input and source-location proofs. Task proofs are ordinary deftests naming their
  task; selection refusals stay the request's declared result.

### 2.8 The host prepl, the page and openings

- **The host prepl** (`prepl.clj:50-73`) is the transport for operator commands and MCP, and the `mode "host"`
  diagnostic entrant: for a cluster whose layer is not ready, and for root diagnosing a JVM whose agents are wedged.
  It is never chosen after a timeout or refusal; the caller names it. Its unrecorded status is explicit in every
  reply, and it is outside every "each MCP evaluation is a run" claim.
- **The page's send** already uses `submit-source!`, and its preview `evaluate-sources` without a record. Choosing
  `code-segments` for the message form's code belongs to `render/web.clj`'s owner.
- **System openings and refreshes** already use the composition; P1, P2 and the refresh exclusion of §2.4 are their
  only changes.

### 2.9 What disappears

Counts are source lines at 19a29c683 from the `def` boundaries; each deletion converts every caller in its slice,
including every caller of `project-next-prepl-value!` (`mcp.clj:108`, `:354`, `:389`, `:517`, `:687`, `:766`; `operator.clj:230-231`).

| Deleted | Where | Lines | Slice | Replaced by |
|---|---|---:|---|---|
| six pre-edge printers become one owner | `eval.clj:4141`, `:4380`, `:4500`, `:3856-3862`; `turn.clj:3704`, `:3753` | ≈ 26 | P1 | the finalization point and the refusal branch through the same owner |
| the per-form second read; the JVM count copy | loop, `mcp.clj:367-378` | ≈ 25 (traced in P2) | P2 | one read per form |
| `decoded-projection-event`, `shown-event` | `mcp.clj:280-303` | 24 | P3 | stored shown bytes |
| `sci-evaluation-form`, `remote-evaluation-form` | `mcp.clj:380-431` | 52 | P3 | one `evaluate-source!` form |
| `execute-clj-eval`, reduced to transport | `mcp.clj:564-662` | ≈ 54 of 99 | P3, P4 | — |
| `window-bases`, `max-window-errors`, `window-form`, `branch-errors!`, the extra text block | `mcp.clj:37-38`, `:511-563` | 55 | P3m | the fault observation |
| `transcript.clj`'s recomputed windows | `render/transcript.clj:346-402` | ≈ 45 (+10), lost-error §M7's count | P3m | the saved observation text plus its `fault-window` |
| `jvm-evaluation-form` (its reader mapping moves) | `mcp.clj:347-379` | 24 | P4 | the JVM stage |
| `mcp-projection`, `project-next-prepl-value!`, `consume-mcp-projection!` | `cluster.clj:244-246`, `:277-304` | 31 | P4 | nothing |
| `nil-deref?`, `first-seon-frame`, `exception-summary` (R6) | `cluster.clj:334-367` | 34 | P4 | `kernel/failure-value` and error storage |
| `mcp-projection-error`, `mcp-project`, `mcp-valf`, `mcp-io-prepl` | `cluster.clj:368-553` | 187 | P4 | the one print; the host prepl's bounded `valf` |
| projection lookup | `prepl.clj:26-27` | 2 | P4 | — |
| `bounded-face`, `bounded-summary` | `cluster.clj:633-658` | 26 | P5 | ordinary render selection |
| the bridge's `refuse#` maps | `mcp.clj:442-482` | ≈ 8 | P5 | registry-declared errors |
| **Total, P1-P5** | | **≈ 593** | | |
| `get_value`'s old-artifact adapter: `hex-digit?`, `digest!`, `path!`, `execute-get-value`; `mcp-get-value`; `mcp-effective` | `mcp.clj:728-782`; `cluster.clj:555-611`, `:309-333` | ≈ 134 (137 less the mark call P4 removes) | P7 | the one continuation entrance |
| `record-evaluated-tx`, `record-evaluated-call` and helpers | `turn.clj:1454-1585` | ≈ 130 | root's call | `virtual-turn!` in fixtures |
| R10, C1 (conditional) | runner, test, eval | 137 | P6 | §2.7 |

**Moved, not deleted:** `identity-hash-map?`, `loaded-code-mark`, `loaded-code-changed?` (`cluster.clj:247-276`) into
the JVM stage, and the JVM reader mapping (`mcp.clj:357-364`) into `read-context`.

**Compliance and Q3.** The owner chose Q3 (c): retrieval through the one continuation entrance. "MCP is transport,
custody and stage choice" is true only after P1-P5 and P7. Between P4 and P7 the milestone is a **partial
migration**, and says so:

- P4 deletes `mcp-project`, the artifact producer, and removes the deleted mark call from `execute-get-value`
  (`mcp.clj:765-769`). `get_value` then remains a **limited adapter over artifacts written before P4**, labelled so in
  its tool description; nothing new is written to it.
- A value produced after P4 that exceeds the print budget answers the render owner's typed unavailable cut, never a
  replay of its origin.
- P7 imports the render plan's continuation entrance, not the retired producer: live SCI backing (the evaluation's
  `result/e…` handle) expands; a JVM result, which has no handle, answers unavailable; nothing replays. The render
  plan's §3g still describes MCP artifact production (`one-render-path-design-2026-09-25.md:467-475`); that
  assumption is reconciled with the render owner before P7 integrates.
- P7 then deletes the limited adapter, and only P7 books those lines.

### 2.10 What each stage costs: hypotheses to measure, not prices

No recording cost is accepted by this design. AGENTS.md's regression law applies (over 20% or 50 ms on the same
probe, parent against change: fix before commit), and a sub-250 ms end-to-end aspiration never supersedes it.

| Stage | Observed so far (historical, not reproduced) | Hypothesis | Measurement required |
|---|---|---|---|
| segment and read | planner 0.86 ms for six forms; the one reader answered four texts within a 5 ms round trip | one read per form after P2 is no slower | reader invocations and time per form, before and after P2 |
| evaluate, SCI | `(+ 1 2)` 5 ms, 579 KB; a contracted one-line `defn` 16.3 ms, 33 MB (S1 landing) | unchanged | per form, with allocation |
| evaluate, JVM | a small query in a 3 ms round trip | the host mark walks all namespaces | mark cost per evaluation (§2.3) |
| acquisition and projection | `agent.clj:647-653`: carrying projection state took a source turn from 728 to 147 ms, with no stage breakdown | projection or profile derivation may dominate | separately |
| record | none for MCP today | a run transaction plus settlement should cost milliseconds, not 145 ms | transactions and their time, per run |
| print | in the evaluator today | once per evaluation | per evaluation |
| completion | a second prepl round trip for the window today | one listener, one filtered await, one recheck | per call |
| refresh after a run | none for MCP | `system-turn` after a closed run for an agent holding an issue (`turn.clj:5295-5305`) costs seconds today (task-context design §0.3) | the task-bearing root case |

**The required measurement** (P3 and P4 landings): the same finite request on the parent and the change: one form,
a multi-form request, and warm repeats; each stage above timed separately, with allocation and retained memory.
Redundant work found is removed at its owner in the same slice; any cost that remains is priced separately as new
durability and needs a root ruling.

**The refresh dependency blocks M1.** Skipping refresh for submitted runs would change semantics, and a root without
an issue only hides the dependency. The task-context design's incremental refresh (view-scoped candidates) must land
first, or M1 is declared blocked on it. P3's proof exercises a task-bearing root.

## 3. REPL-native program change

### 3.1 The change loop

| Step | REPL form | Owner | Status at 19a29c683 |
|---|---|---|---|
| write | `(defn f …)` after `(in-ns 'n)` | reader → `evaluate` → change set (§2.3) → gate → settlement | definition authority **landed** (S1); MCP unsettled (§0.1, P3) |
| write, qualified | `(defn n/f …)` | §2.2 | **deferred** (1.10 clause) |
| delete | `(my.program/ns-unmap! …)`, `remove-ns!`, or nested native `ns-unmap` | §3.2 | two authorities; the nested hole now refuses (§0.11); **outside M1** |
| read first | `(my.program/callers …)`, `tests-reaching`, `breaks`, `reads-key`, `history` | `src/my/program.clj` | exists |
| test | `(my.test/check {:seon.test/changed ['n/f]})` | `src/my/test.clj:14-28` | exists; cost on branch rows UNVERIFIED |
| merge | root: `prepare-merge!`, then `accept-merge!` | `src/seon/cluster/source.clj:747-844` | replacements only |
| write back | root: `write-back!` after accept | §3.3 | **missing** |

### 3.2 Deletion: one authority, the public forms kept (P0-B)

Requirement 1.10 and AGENTS.md name `my.program/ns-unmap!` and `remove-ns!` as the public deletion forms, and
this revision keeps them. Retiring them in favor of native forms alone would be an authority and API change,
which the re-review flags as needing a root ruling. **The hybrid:** keep the public names, and let **one writer**
decide.

1. **The public forms become native changes in the executing fork.** `ns-unmap!` performs the native unmap in
   the calling execution's fork only. Its own transaction and its base mutation (`my/program.clj:510-546`) are
   deleted. The removal is then observed by §2.3's removal member, exactly like a nested native `ns-unmap`,
   which closes hole (iii) without interception. The reader's `:seon.sci.reader/ns-unmap?` special case
   (`reader.cljc:395-404`) and `unmap-row`'s guard retire. The subject is derived from the removed intern
   itself, whatever form the arguments took.
2. **The writer decides.** Settlement writes the removal with the run. The referrer check (`breaks` blocking,
   plus the schema-owning-namespace refusal) runs **inside the transaction function**, as
   `retract-operation!` does today (`my/program.clj:520-537`).
3. **`remove-ns` is a namespace change, not a set of unmaps.** It changes namespace identity, aliases, imports
   and resolver state, and it can remove a namespace that has no interns
   (`reference-code/sci/src/sci/impl/namespaces.cljc:604-609`; compare `sci-ns-unmap` at `:567-587`, which also
   removes refers and imports).
   - The removal member therefore carries one namespace entry: the namespace name plus its row-backed
     identities.
   - The writer decides that whole entry, keeping the schema-owning-namespace refusal.
   - `native-call-refusal`'s `remove-ns` redirect retires only once `remove-ns!` works through this path.
     `ns-unalias!` keeps its component retraction until aliases join the change set.
4. **Failure order.** The fork changes first, and the database write comes last. S1's single restoration capture restores the fork
   at every failure point, including a nested unmap followed by a throw, which has already changed the fork.
   No shared base is ever mutated by an agent's call. The base advances through ordinary adoption, and other
   executions detect the loaded-identity change at their boundary.

**Proofs** (root runs; none was run here):

- a top-level `ns-unmap!`, and a nested native `ns-unmap` of a row-backed function, each retract the row in
  settlement;
- a referenced function refuses, and its Var is re-interned in the fork;
- a nested unmap followed by a throw restores the fork and writes nothing;
- `remove-ns!` of a namespace with rows, of an empty namespace, and of a schema-owning namespace (which
  refuses);
- a mixed define-and-remove form refuses, naming both members;
- a private Var with no row unmaps with no write;
- the base is untouched until adoption, and a second agent acquiring the branch head sees neither the Var nor
  the row;
- every caller of the retiring internals is converted in the same slice.

Deletion is still **not merged**: `prepare-merge!` refuses retractions (`source.clj:767`) until R8.

### 3.3 Write-back: prove the candidate before exposing it (P0-C)

`my.edit/form!` is an effect capability that refuses without an evaluation context (`src/my/edit.clj:35-60`;
`effect.clj:724-735`). A digest fence proves fresh bytes, not acceptance or equivalence. Publishing a candidate
and then republishing the originals is not rollback either: observers may already have seen the candidate,
history has advanced, and restoring the files does not undo the accepted cluster rows.

**The route.** Root calls `seon.cluster.source/write-back!` with `[source held-store acceptance]`.
**M1 constrains it to one physical file.**

1. **Capture and authenticate the bases.**
   - `accept-merge!` accretes two members onto its transaction report (open map; `source.clj:803-844`):
     `:seon.source/accepted-identities`, the `delta` it already computes, and `:seon.source/tested-commit`, the
     `e-id` it already reads.
   - The acceptance value is that report plus root's named acceptance. It carries the destination before and
     after commits, the tested commit, the accepted identities and the source-publication commit whose file
     digests are the base.
   - Transaction entities are resolved to identities through the identity owner on the report's `db-after`. An
     unchanged identity attribute need not appear in the tx datoms.
   - **There are two fences, because there are two authorities.** The cluster head must still equal the
     report's after-commit. The source publication head (`current-src`) must still equal the base commit.
     Either move refuses the stale acceptance.
2. **Recover the provenance from the before-state.** A replacement row may intentionally lack file
   coordinates (`src/seon/program.cljc:96-105`), so the base declaration is read from the report's
   **`db-before`**:
   - its `:seon.fn/file` entity, resolved to a path;
   - its exact selector: head (`defn` or `defn-`), name, and dispatch.

   A target with no base file, or one whose file is absent from the base commit's
   `:seon.source/relative-file-digests`, refuses by name. The replacement bytes are the accepted row's stored
   **canonical** source (1.14). Files and rendered context therefore agree.
3. **Build and prove the candidate in memory, before anything is exposed.**
   - Read the file's held bytes and check that their digest equals the base digest.
   - Fold every replacement for the file over **one** candidate string with `seon.edit/form`
     (`src/seon/edit.clj:314-373`: pure, lossless, unambiguous), passing its context-byte bound.
   - Analyze the candidate string with the ordinary analyzer through its existing stdin seam
     (`analyzer/analyze` with `::analyzer/paths ["-"]`, as `src/seon/fn.clj:933-934` does). Analyze the base
     bytes the same way.
   - **Require three things:**
     - every accepted identity's semantic digest in the candidate equals the accepted row's digest;
     - every other declaration in the file has a digest equal to the base;
     - the `ns` form and its requires and aliases (the resolver context) are byte-equal to the base.

     `program/digest-map` deliberately excludes file and lint rows (`program.cljc:462-476`), which is why the
     third, exact check accompanies the semantic ones.
   - Missing evidence, or equal-looking refusal maps, is never proof of equality.

   *Disagreement with the review, with evidence.* The review puts this check inside `publish!`, before its
   guarded pointer update (`source.clj:601-685`). But `publish!` populates from files on disk, so a check
   there comes after the candidate is already on disk and visible to colleagues and hooks. The in-memory proof
   comes before any exposure and uses the same analyzer. It leaves `publish!` without a second verification
   seam.

   *Hybrid.* Prove in memory (this step). Then let `publish!` reindex the real path ordinarily. Then require
   that the published commit's recorded digest for the path equals the candidate's digest, and that the
   analyzer digest is unchanged (step 4). Rows are a deterministic function of bytes and analyzer, so this
   carries the in-memory proof over to the published head.
4. **Fence the installation, and name every failure.**
   - At the existing file writer's installation boundary, recheck that the disk digest still equals the base.
     If a colleague has edited the file, refuse and write nothing.
   - Write the candidate to a temporary sibling and rename it atomically.
   - Call `publish!` with `:seon.source/expected-commit-id` set to the fenced source head. Overlapping
     publication is **detected**, not locked: the pre-scratch check refuses (`source.clj:586-593`), and
     `d/force-branch!` refuses inside it with `:expected-current-commit`.
   - Then check the published digest (step 3's hybrid).
   - **Failures:**
     - If the publication refuses or throws, or the published digest differs, restore the original bytes
       **only while the disk bytes still equal this candidate**. A colleague's intervening edit is never
       overwritten.
     - A failed restore leaves a named **write-back-unavailable** state for the path, with the evidence
       retained.
     - Every failure reports **accepted-but-not-written**: the accepted cluster rows are not undone.
   - **Process loss** after the rename and before publication leaves the disk holding a candidate already
     proven equal. The next ordinary publication converges it. The originals held in memory are not relied
     on.

There is one source owner, one analyzer and the existing guarded publication. No filesystem transaction
framework is added. Additions and retractions in write-back are **R9b**, after R8. Price: S5 in §6.

### 3.4 Milestones, ordered

**M1 — one existing-function replacement lands from an MCP session (option B, recommended).** The slices land in
order P1, P2, P3, P3m, P4, P5, S5 (§6), after the task-context design's incremental refresh. S1 has landed. B is a
partial migration until P7 (retrieval) and P6 (tests in the composition) land. The flow:

1. A lane creates its branch and opens an MCP SCI session with an explicit `session_id`.
2. It evaluates `(in-ns 'n)` and then `(defn f …)`. Each call is one run of root on the branch (armed there by the
   session, P-RB), segmented as code, read once per form and settled by `settle!`; settlement records one row.
3. It runs `(my.test/check {:seon.test/changed ['n/f]})` on the branch. The existing harness is used until P6
   lands, and until then there is no "one path for tests" claim.
4. Root runs `prepare-merge!` with the task, `accept-merge!` with the tested run, and `write-back!` (S5) for the
   one file. Root names the accepter and the tested head.

The proof root runs for M1:

- the same source through `virtual-turn!` and through MCP gives the same settled row and the same shown bytes;
- a multi-form MCP call reads each form once, after the previous form's namespace effects; an alias required by one
  form reads in the next; a top-level reader conditional and regex are evaluated, not dropped;
- effects run once; a busy agent refuses; a timeout answers the run id and keeps ownership; no provider attempt row;
- JVM `(/ 1 0)` stores one error occurrence with its whole chain, and the MCP answer is its declared renderer's text;
- a JVM `defn` offers the page's runtime-eval signal, and a JVM `(+ 1 2)` does not;
- a refresh never re-evaluates a JVM evaluation; a JVM ending namespace never continues an SCI run;
- an incompatible stage symbol refuses at submission, before any run;
- a core fault committed between two calls appears in the second call's fault observation, the reply's `isError`
  agrees, and every error in the reply references the world (commit id, agent and branch, run or evaluation id), never
  a database value, context or request;
- a task-bearing root pays no whole-history refresh for a submitted run;
- a second call and an affected caller run the new definition; focused tests are tied to its digest;
- a moved cluster head, a moved source head and a moved file each refuse write-back; the accepted row, the file
  bytes and the reindexed digest agree; an injected publication refusal restores only an unmodified candidate and
  reports accepted-but-not-written.

Each phase is timed separately on the parent and the change, with allocation and retained memory (§2.10):
acquisition, segmentation and reading, evaluation, finalization and print, settlement, completion, test selection and
body, merge, candidate proof, installation. A regression over 20% or 50 ms is fixed at its owner before commit.

**D1 — the first dogfood milestone: a task settles through M1.** A lane takes one task whose fix replaces one
existing function (a task in `resources/seon/tasks/`, chosen by root), works
it through MCP SCI on its own branch, names the task on its proof deftest, and drops the task from the replacement's
attribute map in the same accepted row (7.6). Root merges; write-back lands the one file.

**M2** adds new functions and tests, deletion (S4) and its merge (R8), additions and retractions in write-back
(R9b). **M3** adds qualified definitions (§2.2) and the durable result store, following the owner's ruling.

### 3.5 Tasks in the codebase: status

Tasks are already in the codebase, in a different shape from revision 3's T1-T4 plan, which this revision withdraws:

- task files `resources/seon/tasks/<owning-ns>.edn` are indexed at publication by `index-tasks!`
  (`src/seon/cluster/source.clj:526`), beside the Markdown `index-issues!` (`:514`);
- a task is named by a content handle (`seon.issue/task-handle`, `src/seon/issue.clj:533`), not a
  `:seon.task/id` keyword, and code names it in `:seon/tasks` (for example `evaluate`'s `#{"132fee0467c8"}`);
- admission and merge refuse unknown or dangling task names (9bfa6fff2, d83286cee, 9bff631b2);
- an agent's context is a composed render of the agent entity through its task (owner 21:25Z, 8b733cce3; the
  task-context design, reviewed in 595048666).

What this design still asks of this area is only what the pipeline needs: an MCP session agent may name its task by
handle, and without one it gets the task-context design's loud no-task value. The Markdown indexer's retirement
(revision 3's T4, about 490 lines of `issue.clj`) belongs to the task owner's plan, not this one.

## 4. Co-location, owner rulings, and configuration

### 4.0 Owner rulings, 2026-09-25

1. **Render selection is per face, and the first match wins** (11.1; §2.5). Schema pairs stay as the shipped
   default. The viewing agent's namespace holds overrides, and values may carry their own. The reverse view is
   a typed `callers` field. About 695 schema properties are references, not duplicate implementations, so no
   migration earns deletion credit.
2. **Supplied defaults go by declared schema name, for all functions; caller arguments win** (1.13). Two gaps
   are named for their owners:
   - renderers **may** read the database (owner 23:00Z): the stable database value is a contract-declared input
     supplied as a default by schema name, never an ambient read (§2.5); 47 of 105 renderers already read it
     (landing 549b4d58c);
   - inline function values need preparation (§0.14, §2.5).
3. **Configuration: do the move** (13.1-13.2; §4.4).

### 4.1 The rules, for agents with shell tools

- **G1: write native qualified references.** Metadata and data hold qualified symbol and keyword values. Grep
  finds a spelling; the analyzer resolves identity. `(defn notice-prose …)` does not contain the text
  `seon.error/notice-prose`.
- **G2: a fact lives with its owner** (1.2). A task id appears literally at its definition and at every
  referrer (1.9). Code owns subject edges, tests own proof edges, and EDN owns only the task's own data (7.4).
- **G3: grep finds spellings; the index answers relations,** with analysis-digest evidence and typed unknowns.
  A keyword mention is not a read (`my/program.clj:211-226`).
- **G4: a data input carries its identity:** the resolved documents, digests, layers, environment decisions
  and default provenance (§4.4).

### 4.2 The indexed form a REPL agent queries

| Edge | Stored as | Status |
|---|---|---|
| calls / references / invokes | `:seon.fn/calls` etc. | exists (29,375 test call edges) |
| keyword mentions / writes | `:seon.fn/keywords`, `:seon.fn/writes` | exists (mentions, not proven reads) |
| contract → schema keys | `program/contract-facts` with input/output/guard refs (`program.cljc:1007-1081`), queried by `reads-key` | exists; verify population |
| schema → default renderer | schema property; `breaks` `render-declared-by` | exists; `render-referrers` (`my/program.clj:237-241`) must bind the renderer first (S6) |
| agent namespace → override renderer | contract fit (634d8aaa2) | exists as selection |
| definition → task handles | `:seon/tasks` values, indexed by `index-tasks!` | exists (§3.5) |
| test → task, must-read, see-also, test seeds | — | the task owner's plan (§3.5) |
| config compilation → documents, digests, layers | — | S7 (§4.4) |
| evaluation → value, error, rows, evaluator | evaluation row + refs; `:seon.cluster.eval/evaluation-stage` when not the default | partly; §2.4, P4 |

### 4.3 Each data-file family

| Family | Verdict |
|---|---|
| Schema EDN, 211 files | keep: Malli owns the registry |
| Render pairs in schema EDN | keep as the shipped default (ruling 1) |
| `config/default.edn` dial decisions | move onto the dial schemas (S7) |
| `config/default.edn` `:seon.config/initialization` | move to `config/initialization.edn`, read by name |
| `config/development.edn` | keep, sparse, as a deployment layer |
| `config/test.edn` | delete after a verified no-reader check (D0) |
| Help and getting-started | one REPL-protocol docstring; fix `src/seon/cluster/instruction.clj:23` in that cut |
| Issue notes `docs/seon/issues/*.md` | the task owner's migration (§3.5); bodies stay linked evidence, never parsed |
| Task data | `resources/seon/tasks/<owning-ns>.edn`, indexed at publication (exists, §3.5) |
| Test seeds | keep in code |
| Docs, skills, research | keep Markdown; symbol links for navigation, pinned `file:line` for history |
| Reference code | navigation from task subjects' dependency seams (§5) |

### 4.4 Configuration by situation (ruling 3; slice S7)

**What exists.**

- `compile-settings` merges defaults, then one manifest, then environment (`config.clj:474-525`).
- `registration-defaults` reads `:seon.config/default` from dial schemas (`:154-168`).
- `effective-in` returns only the stored row's dials (`config.clj:772-789`).
- Per-agent settings are merged by `seon.ai/settings` (`src/seon/ai.clj:316-323`).

**The layers, least to most specific** (13.2):

| # | Layer | Lives in | Changed by |
|---|---|---|---|
| 1 | shipped default | the dial schema's `:seon.config/default`, reason in `:description` | a program change |
| 2 | deployment overlays, ordered | sparse EDN documents | `config apply NAME PATH…`, `start --config` |
| 3 | environment | the typed environment map at compile (exists) | the launcher |
| 4 | cluster facts | `:seon.cluster/settings`, of the overlay schema agents use | a transaction; never erased by re-apply |
| 5 | per-agent settings | `:seon.agent/settings` (exists) | the agent or root |

The environment is a compile input that already exists. Requirement 13.2 omits it, and §7 proposes adding it.

**The stored row.** Layers 1-3 are compiled and exact-reconciled as today. **In the same transaction** the row
records the selection and its evidence:

- `:seon.config/layers`: the ordered documents, each as a path relative to the named operator root plus a
  content digest;
- the typed environment decisions that were applied;
- each default's provenance (schema key and schema digest).

This evidence comes from the compiled inputs, not from a second configuration authority. Layers 4 and 5 are
merged at read.

**Apply semantics.**

- An explicit `config apply NAME PATH…` **replaces** the recorded stack.
- `start` without `--config` **reuses** the recorded stack, and `start --config` replaces it.
- Boot (`boot.clj:432`) and publication re-apply (`:664-676`) recompile from the recorded paths, re-reading and
  comparing digests. The hard-coded development branch goes.
- Re-apply triggers on a changed recorded document, **or** a changed dial-schema default, **or** changed
  initialization content. Only clusters whose recorded inputs changed are re-applied.
- `apply-compiled!` retains the cluster settings entity as it retains other clusters' rows.

**The readers.**

- `seon.config/effective` itself derives layers 1-4, taking `[db cluster-name]`. An optional agent id adds
  layer 5, so there is one reader with an optional agent.
- A refused read (`:seon.db/invalid-read`, `:seon.config/error`) propagates **before** any `merge`, since an
  error map is also a map.
- The final merged value is validated against `:seon.config/effective`.
- All nine sites (§0.13) and `turn.clj:4020-4023` call the reader with the cluster name taken from their
  caller's handle or environment.
- The transcript reader (`render/transcript.clj:1180-1182`) keeps its **historical** database value, the
  observation made at opening time. It does not switch to a head read.

**The dial move** is one scripted sweep:

1. Select dials by `:seon.config/dial` membership.
2. Write `:seon.config/default` and fold the reason into `:description`.
3. Keep `:seon.config/available-processors` symbolic.
4. Keep explicit absence as optional-with-no-default.
5. Never write `false` for `no-provider`.
6. Keep `validate-default-decisions`.

**Proof** (root runs, before and after, recorded in the landing):

- `(config/default-decisions)`, `config/defaults` and `(config/default-population)` digests are equal;
- `(config/effective db "default")` is unchanged;
- a `seon.test` seed world gives the same effective map and population;
- `config apply default config/development.edn X.edn` keeps both layers through a publication re-apply;
- a changed dial-schema default re-applies;
- a branch carrying a second cluster's row returns this cluster's dials from every converted reader;
- a refused read surfaces as the refusal, not as a merged map.

## 5. Auto-pulled context, derived by query plus render function

Each pull is an ordinary evaluation of a short named call, built **once as a typed observation at the common
evaluation boundary** and rendered by both faces. Following 1.9 and 5a.3, a pull shows **ids and counts plus
the call that expands them**, never the expansion by default. The exception is the subject's own source, which
renders whole (5.8).

| Class | Where | Query (owner) | Cost |
|---|---|---|---|
| a change was accepted | the settled evaluation | per identity in the accepted change set: caller ids and render referrers as typed fields, reaching-test count, open task ids from `:seon/tasks`, the check form. For a removal: the before-state incoming edges | bounded by matched edges; `render-referrers` bound first; target ≤ 50 ms, UNMEASURED |
| a printer call was introduced | the same observation | new `:seon.fn/calls` ∩ known printers | advice only; macros and aliases make it incomplete |
| a test world | the member's evidence | documents, digests, layers, environment decisions, default provenance (§4.4) | one small map |
| a task's subjects | the task opener | `(task :id)`: subject ids, proof ids, must-reads, triggering observations; hop 1 is content for seeds, hop 2 is addresses (5a.2-5a.3) | measured by 5.10's brief budget |
| a design task | the task opener | the dependency seams its subjects reach, with guarantees; the gitlink pin labels the source shown | one query |
| faults and errors since the last evaluation | every settled response | durable per-execution basis (8.3); first and lost observations typed | today the bridge's window (`mcp.clj:511-563`); becomes the settlement-recorded fault observation of lost-error design §M, in P3m |

## 6. Slice plan, priced

**Rules.**

- Deletions are counted at 19a29c683 from the `def` boundaries (§2.9). Audit-ledger groups
  (`duplicate-paths-audit-2026-09-25.md` §11) are named where they apply (R6, R7 landed, R10, C1), each once.
- Relocated code is neither added nor deleted. Every size is an UNVERIFIED estimate until its landing measures the
  diff, with schema, caller and regression costs.
- Each slice converts every caller of what it deletes, including tests, in the same commit (AGENTS.md step 5), and
  proves schema changes by adoption on a branch of the live store before they reach default (owner 23:00Z item 3).
- Each hot-path slice times the same probe on parent and change (§2.10); a regression over 20% or 50 ms is fixed
  before commit.
- File ownership is root's ledger (`tmp/orchestrator/file-ownership.md`). `src/seon/sci/eval.clj` had another lane's
  uncommitted edits at this pin.

| # | Slice | Owner files | Added | Deleted | Net | Proof root runs |
|---|---|---|---:|---:|---:|---|
| **S1** | landed: one definition authority | — | — | — | +59 measured | its landings |
| **P1** | one finalization point: all result replacements including the disposition's completion, then one print; constructors answer native values; the refusal branch renders through the same owner (the `pr-str`s at `turn.clj:3704`, `:3753` retire, with F10's owner); the evaluator carries the head function as data; the form's sink covers print; the database value supplied to renderers by schema name | `src/seon/sci/eval.clj`, `src/seon/turn.clj` | +40 | −26 | +14 | a refused install, a disposition refusal and a success each save one rendering; a writer-time refusal publishes no speculative success; a completed disposition's value and text agree; a batch refusal uses ordinary selection; failed refusal storage is a core failure; `unrun-evaluation` prints nothing; render reads stay in the form's evidence; preview and refresh comparisons hold |
| **P2** | read once: segments keep raw source; the loop reads the next form with the stage's reader context and passes the event; the reader's next-event-from-offset entry if missing; remainder kept on refusal; stage keys and their three function schemas; the submission-time stage check | `src/seon/turn.clj`, `src/seon/cluster/reply.clj`, `src/seon/sci/reader.cljc`, `src/seon/cluster/agent.clj` (check only), the turn and eval schemas | +55 | −25 (traced) | +30 | the §2.2 proofs; an incompatible stage symbol refuses before any run |
| **P3** | MCP SCI is root's run: `evaluate-source!`; P-RB, then root armed on a branch by the session; continuation per stage; `run-receipts`/`current-run` public; retirement without releasing live custody | `src/seon/cluster/agent.clj`, `src/seon/turn.clj` (two contracts), `script/seon/dev/mcp.clj` | +65 | −130 (shown decoding 24, SCI forms 52, `execute-clj-eval` ≈ 54) | −65 | M1's SCI proofs; the §2.10 measurement with a task-bearing root |
| **P3m** | the fault observation, exactly lost-error design §M (folded here as its "pipeline that owns MCP reply assembly"): the cluster value captured before settlement, saved shown text, the `fault-window` component, the committed-cursor and last-form rules, lineage unknowns, `occurred-since` in place, one budget, outcome and `isError` | the settlement writer, `seon.cluster.eval` schema (+1 attribute and component, +1 error schema, counted once across both plans), `src/seon/error.clj`, `render/transcript.clj`, `mcp.clj` | +45 | −100 (window 55, transcript ≈ 45) | −55 | lost-error §M's class regression rows, including a fault resolved inside the window, a later recurrence leaving earlier saved text unchanged, and a failed settlement not advancing the lower bound |
| **P4** | MCP JVM mode is root's run with the JVM stage; `mode "host"` with a bounded `valf`; the MCP projection, error summary, marks and dead prepl deleted; `get_value` reduced to the labelled old-artifact adapter (its mark call removed) | new `src/seon/repl/jvm.clj`, `src/seon/turn.clj` (refresh exclusion), `src/seon/cluster.clj`, `mcp.clj`, `resources/seon/operator/prepl.clj`, `script/seon/operator.clj`, the eval schema, `test/seon/cluster/mcp_test.clj`, `test/seon/mcp_test.clj`, `test/seon/render/web_context_test.clj` | +80 | −278 (R6 34, projection/valf/io-prepl 187, marks 31, JVM form 24, lookups 2) | −198 | M1's JVM proofs; `bin/seon status`, `start` and `config apply` read their replies; a large host value is bounded in `mode "host"` |
| **P5** | `runtime_status` renders its observation through selection; `branch` refusals are registry-declared errors | `src/seon/cluster.clj`, `src/seon/cluster/registry.clj`, `mcp.clj` | +25 | −34 | −9 | status and branch answers unchanged in content, rendered by their declared renderers |
| **P7** | retrieval through the one continuation entrance (Q3 (c), owner's choice): `get_value` validates wire input and calls the render owner's `units` with the evaluation's source reference; a result with no retained backing answers the typed unavailable cut; the old-artifact adapter deleted | `mcp.clj`, `src/seon/cluster.clj` | +20 | −134 | −114 | a live SCI result expands beyond the budget; a JVM result answers unavailable; nothing replays |
| **S5** | write-back of one file (§3.3) | `src/seon/cluster/source.clj` | +75 | 0 | +75 | the M1 write-back proofs |
| **P6** | tests enter the composition: `capture-evaluate`, recording through `evaluation-facts` and the recording writer, native report printed once, candidate gate as one request | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/sci/eval.clj`, the test schema | +90 | −137 (R10 50, C1 87, conditional) | −47 | a member leaves one evaluation on the retaining branch, read once; failure keeps native operands; the candidate gate records a whole run |
| **S4** | deletion's removal member (§3.2) | `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/my/program.clj` | +30 | ≈ −50 | −20 | the §3.2 proofs |
| **S6** | accepted-change observation (§5 row 1) | `src/my/program.clj`, `src/seon/turn.clj` | +40 | 0 | +40 | ≤ 50 ms measured |
| **S7** | configuration (§4.4) | config owners | +75 | ≈ −30 | +45 | the §4.4 proof list |
| **D0** | delete `config/test.edn` after a no-reader check | file | 0 | −6 | −6 | HEAD loads |
| later | qualified definitions; R8; R9b; the completed-run recorder (≈ −130, root's call); a product-agent writer if P-RB fails (≈ +25); the planner's silent drops | — | unpriced | — | — | — |

**Dependencies.** P2 needs P1's finalization point (the loop owns both). P3 and P4 need P2 and the task-context
design's incremental refresh (§2.10). P3m follows lost-error P2 (delivery evidence) and R4, and needs **render slice 2(i)** (one render path for the
reply's units, lost-error §M's order), as that design orders; that is a different render cut from the continuation
entrance, which only P7 needs. M1 therefore waits on render slice 2(i) through P3m.
P7 needs the render plan's continuation entrance (`one-render-path-design-2026-09-25.md` §3g), which is not installed
at this pin (`seon.render/units` with a source reference does not exist): its +20 is the adapter only, and the
entrance itself is the render plan's unpriced cut.

**M1: three options** (simplest viable first):

| Option | Slices | Guarantee | Added / deleted / net | Sacrifice |
|---|---|---|---|---|
| A. MCP evaluations become agent runs, read once and printed once (a partial migration) | P1 + P2 + P3 + P3m + P4 + P5 | every MCP evaluation is a run; faults arrive as errors; the MCP printer, summary, window, forms and status printer are gone | +310 / −593 / **−283** | no retrieval beyond the budget for results produced after P4 (typed unavailable); `get_value` reads only older artifacts; nothing lands in files |
| **B. A plus a landable replacement (recommended as the first dogfood milestone, labelled partial)** | A + S5 | as A, and one replacement lands REPL → merge → one file | +385 / −593 / **−208** | as A; tests outside the composition until P6 |
| C. The full one-path milestone | B + P7 + P6 | MCP is transport, custody and stage choice only; tests enter the composition | +495 / −864 / **−369** | waits for the render plan's continuation entrance; C1's credit is conditional (without it −282) |

B is recommended as the first dogfood milestone: it lands the owner's MCP law and the dogfood path without waiting
for the continuation entrance, and it is labelled partial until P7 and P6 land. All three wait on the refresh cut and,
through P3m, on render slice 2(i).

**Owner questions, with the review's recommendations applied.**

- **Q1, where a JVM evaluation runs:** settled as **(a), root's run on root's Flow graph** (ruled, and recommended by
  the review). A stuck host call holds root; `mode "host"` is the separate recovery access. Options (b) and (c) are
  withdrawn: (b) changes the identity ruling, (c) resurrects a second driver.
- **Q2, SCI without a branch:** **(b), root's run**, recommended by both; enforced as root custody, never inferred
  for an anonymous caller. (a), refusing branchless SCI, stays available as a narrower product constraint.
- **Q3, retrieval beyond the print budget: ruled (c)** by the owner, taking the recommendation. The table is kept as
  the record of the choice:

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| (a) Retire `get_value`; results beyond the budget answer a typed unavailable cut (a reduced milestone) | one printer, no MCP result owner | −137, in P4 | no drill-down beyond the budget until the render plan's entrance lands |
| **(c) Retrieval through the one continuation entrance (recommended for the full milestone)** | one retrieval path for page and MCP; live results expand; nothing replays | P7's +20 plus the render plan's unpriced entrance | M1's full claim waits for that cut |
| (b) Keep `get_value` as it is | — | — | withdrawn: P4 deletes its producer, so it would read only old blobs; it does not preserve retrieval |

## 7. Requirement rows still to revise (a diff against 19e9fe5f4; not applied)

The revised rows 1.2 and 1.9-1.14, 3b.6, 7.11-7.12, 11, 12 and 13 already absorb most of revision 2's proposals.
Revision 2's own row numbers 11.x-13.x collided with them and are withdrawn. What remains:

| Row | Change | Proposed text |
|---|---|---|
| 1.10 | stage | "…the entrance admits `(defn other.ns/f …)` **(deferred to M3, §2.2: single expansion, reader versus target context)**; `my.program/ns-unmap!`/`remove-ns!` and nested native `ns-unmap` are removals in the evaluation's change set, decided by one writer at settlement; `remove-ns` is a namespace change…" |
| 1.15 | new | **One definition authority.** A form's changed Vars (root or metadata) match its analyzed rows one-to-one, and its removed interns match row-backed identities; the set is admitted whole or refused whole and restored at every failure point; an unindexed change refuses as such |
| 5c.3 | extend | Also true for MCP and test members: each entrant's result is the stored shown text of its settled evaluation |
| 7.6 | clarify | Settlement's "diff removes the id from its subjects" is the id's removal from the accepted replacement's attribute map, merged and written back with it (D1) |
| 7.13 | new | **Write-back is proven before exposure:** accepted replacement rows are spliced into one candidate, proven equal by the ordinary analyzer in memory, fenced by both heads and the file digest, published ordinarily and checked by digest; a failure restores only an unmodified candidate and reports accepted-but-not-written |
| 8.6 | revise | An MCP JVM evaluation is a run of root's with the JVM evaluator stage, recorded and printed like any evaluation; `mode "host"` names the unrecorded diagnostic prepl, chosen by the caller, never as a fallback (§2.6, Q1) |
| 8.8 | new | **An MCP evaluation is a run of root's under explicit custody:** on the cluster's branch, or on a lane branch where the session armed root (P-RB); custody selected on every call; completion by listening before submitting, then rechecking by run id; namespace continuation per evaluation stage, the explicit namespace winning; retirement disarms only what the session armed and never releases custody before actual exit; faults arrive as errors in the reply |
| 1.16 | new | **The REPL path is one composition of stage values.** `evaluate-sources` segments, reads each form once, evaluates, finalizes, prints once and builds the record; a submission names its segmenter and evaluation stage as qualified symbols whose declared contracts are the stage schemas by name; record and print are not choosable; the lifecycle owner persists (§2.0) |
| 1.17 | new | **Code is segmented as code.** An entrant submitting code (MCP, files, generated forms) uses `code-segments`; the reply planner is only for model replies (§0.4, §2.2) |
| 8.9 | new | **Errors and faults reference the world.** Every error the pipeline produces or returns carries commit id, agent and branch, run or evaluation id and blob digests, never a database value, context, request or connection (§2.4) |
| 9.5 | note | The recorder never realizes a value in order to store it; place the bounded-prefix alternative beside "never realize" for the owner (§2.5) |
| 13.2 | extend | Add the typed environment between the overlays and the cluster facts; record the stack, environment decisions and default provenance atomically; an explicit apply replaces the stack, a start reuses it; re-apply on changed documents, schema defaults or initialization |
| 13.3 | new | **One effective-configuration reader** derives layers 1-4 for a named cluster (plus 5 for an optional agent), propagates read refusals before merging, and validates the final value; historical readers keep their database value |

## 8. Verification limits

- **Runtime evidence is historical.** The revision-4 probes ran on default pid 37460 (session `design-one-pipeline`,
  read-only JVM forms and one SCI `(+ 1 2)` on the shared context): the 108/42 counts (§0.1), the JVM `(/ 1 0)`
  answer and empty error window (§0.2), and the planner-against-reader table (§0.4). That JVM has since been replaced
  by root's nuke for a fresh store; revision 5 ran **no** probe, as root instructed, and nothing here is reproduced
  on the new store.
- **Revision 5 is source reading only**, at 19a29c683 (`eval.clj` via `git show HEAD:`): Astra's revision-4 review
  (5f7558427); lost-error design §M; the render plan §3g; `agent.clj` (`arm!`, `disarm!`, the namespace pull);
  `turn.clj` (`run-receipts`, `current-run`, the refresh call sites); `unrun-evaluation`; the ending-namespace schema;
  the agent writers (only `seed-root-agent!` found); the owner rulings of 20:50Z and 23:00Z.
- **Not run:** no write, submission, test, adoption, arming or new code.
- **Unverified, established by the owning slice:**
  - P-RB: arming root's inherited row on a lane branch's handle (P3's first step);
  - whether `seon.sci.reader/read` offers a next-event-from-offset call (P2);
  - P2's exact deletions, from its call-graph trace;
  - the host change mark's cost (P4);
  - every stage cost in §2.10, and whether a submitted run opens a provider call (P3);
  - every line estimate in §6.
- **This lane's delta.** Source and test: 0 / 0. Changed path: this file only.
