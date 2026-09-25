---
type: design
status: revised design (Opus, revision 4, first pass of the one-pipeline cut; for Astra review and root ruling); integrates the owner's 2026-09-25 composition law (AGENTS.md 14ccb65f0, ba41af0c0, 5dba6a14c) and the landings of S1, R3, R7 and the read-sink fix; no production code
created: 2026-09-25
revised: 2026-09-25
lane: design-one-repl-language-opus → revise-one-repl-opus → revise2-one-repl-opus → design-one-pipeline-opus
source-pin: 19a29c683 for every src/, script/ and resources/ citation (src/seon/sci/eval.clj is read from `git show HEAD:` because another lane holds uncommitted edits to it); §3.1-§3.3, §4 and §5 keep their 19e9fe5f4 / 3785970b9 citations, marked where they have moved
scope: the one REPL path as a composition of stages (read, evaluate, record, print) with every entrant supplying only its transport and its stage values; one definition and deletion authority; gated write-back; configuration layering; auto-pulled context; ordered and priced slices; requirement rows still to revise
---

# One REPL, one pipeline: read → evaluate → record → print, stages as values

**What exists.** Seon already owns every stage of one REPL path, and the agent's run is already their composition:

- **read**: the one reader, `seon.sci.reader/read` (`src/seon/sci/reader.cljc:860`), with two segmenters over it:
  the reply planner `seon.turn/planned-sources` (`src/seon/turn.clj:3124`, prose-tolerant, for model replies) and
  `one-event` (`src/seon/sci/eval.clj:957`, one form in the namespace in effect);
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
text, the **reader** and **evaluator** stage values (qualified symbols, defaulting to the agent's SCI path) and the
declared bounds. The agent's run carries that map. `evaluate-sources` reads each form with the reader value,
evaluates it with the evaluator value, and hands the final evaluation to the one print call. Settlement records it.
Every entrant keeps only its transport and its stage choice.

**The principal Clojure approach** is `clojure.main/repl`'s own: the REPL is a loop over stage functions passed as
options (`reference-code/clojure/src/clj/clojure/main.clj:368`, `:414` destructures `read eval print caught`), and
`clojure.core.server/prepl` takes its printer as `valf` (`core/server.clj:194`, `:266-273` resolves a symbol). Seon's
pipeline is the same shape expressed as data on a durable run, plus the record stage Clojure does not have. The
precedent inside Seon is `seon.test/run`'s `:seon.test/recording-writer` (`src/seon/test.clj:2070`): a stage passed
as a value, with a default. No new loop, recorder, printer or store is added.

**What disappears** (§2.9): the MCP-only printer and projection (`mcp-project`, `mcp-valf`, `mcp-projection-error`,
`mcp-effective` and the ThreadLocal marks, `src/seon/cluster.clj:244-534`), the JVM error summary
(`exception-summary` family, `:334-367`), the dead `mcp-io-prepl` (`:535-553`), the bridge's two evaluation forms,
its error window and window bases, and its re-decoding of shown text (`script/seon/dev/mcp.clj:37-38`, `:280-303`,
`:347-431`, `:511-563`): about 400 source lines, against about 175 added over five slices.

## 0. Broken things first

Revision-4 probes ran on default (pid 37460, started 2026-09-25T19:31:55Z, health `faulted`, 21 open faults) through
MCP, with one session (`design-one-pipeline`), JVM mode read-only except one SCI `(+ 1 2)` on the shared context.

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
   must be the code reader (§2.2). The planner's silent drop of a top-level reader conditional is a defect in its own
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
   runs `system-turn` (`turn.clj:5293-5301`), whose candidate selection cost 3.3 s for root
   (task-context design §0.3). Whether root holds an issue on default is UNVERIFIED. The task-context design owns the
   fix; §2.10 prices the dependency.

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

The pipeline is the agent's run. Its input is one request map, and every stage is a function the map names:

```clojure
{:seon.boot/root       "/Users/sean/src/seon"          ; custody: which world
 :seon.boot/cluster-name "default"
 :seon.agent/branch    :lane-x                         ; absent: the cluster's own branch
 :seon.agent/id        "root"                          ; or "mcp/<session-id>" (§2.6)
 :seon.cluster.reply/text "(in-ns 'n)\n(defn f …)"
 :seon.turn/reader     'seon.cluster.reply/code-sources ; absent: seon.turn/planned-sources
 :seon.turn/evaluator  'seon.repl.jvm/evaluate          ; absent: seon.sci.eval/evaluate-for-install
 :seon.turn/starting-ns [:seon.ns/name n]              ; absent: the agent's assignment
 :seon.await/bound     {…}}                            ; the entrant's declared completion bound
```

The composition, with the owner of each arrow:

1. **read** at submission. `submit-source!` resolves `:seon.turn/reader` and calls it on the text, in place of the
   hard-wired `planned-sources` (`agent.clj:694`). Either reader answers `:seon.cluster.reply/sources`, which the run
   stores as today (`:seon.turn/sources`). Each form is read again, in the namespace then in effect, by its evaluator
   (`one-event` for SCI). That is today's two reads, unchanged; §2.2 names the once-only slice.
2. **evaluate** on the agent's Flow turn proc. `evaluate-sources` resolves the run's `:seon.turn/evaluator` and
   calls it where it calls `evaluate-for-install` today (`turn.clj:4771`). Both evaluators answer
   `:seon.sci.eval/evaluation`, so gating, dispositions, ordinals and namespace continuation are untouched.
3. **record** by settlement. `settle!` → `evaluation-facts` → the receipt builders store the evaluation, its read
   evidence and its program change. The evaluator's name is recorded on the evaluation when it is not the default
   (`:seon.cluster.eval/evaluator`, a qualified symbol; absence is the default), so a reader of history knows which
   evaluator produced it.
4. **print** once, after the evaluation is final. `evaluate-sources` calls the one shown-result owner on the final
   evaluation instead of the evaluator printing it (§2.5). The shown bytes are stored and returned verbatim.

A stage value is a **qualified symbol resolved to a program Var**, not an inline function. The run is durable data:
recovery closes an interrupted run from its facts (AGENTS.md, "Recover facts, not execution"), and a symbol survives
that where a function object would not. `:seon.boot/root`, `:seon.boot/cluster-name`, `:seon.agent/branch` and `:seon.await/bound` are registered keys; `:seon.turn/reader`, `:seon.turn/evaluator` and `:seon.cluster.eval/evaluator` are the three new ones (P3, P4), each an optional qualified symbol. The contract each stage must satisfy is its default's declared contract
(`[:=> [:cat :seon.sci.eval/request] :seon.sci.eval/evaluation]` for the evaluator; text, namespace and bound to
`[:or :seon.cluster.reply/sources :seon.cluster.reply/no-forms-error]` for the reader). The resolved Var's own armed
contract enforces it. An unresolvable or unregistered stage symbol refuses at submission by name
(`:seon.agent/source-submission-refused-error`), before any run is written.

**What is not a stage value.** Record and print are shared and have no alternative. An entrant cannot choose a
recorder or a printer, because the laws give it none: the evaluation's facts and its declared renderer decide.

### 2.1 The entrants today, stage by stage

"Shared" means the stage calls the owner named in §2.0; "copy" means the entrant has its own. Citations at 19a29c683.

| Entrant | Transport | read | evaluate | record | print |
|---|---|---|---|---|---|
| Agent turn (model reply) | Flow turn proc (`agent.clj:525`) → `turn/step` (`turn.clj:5564`) | shared: `planned-sources` (`turn.clj:3124`) then `one-event` (`eval.clj:957`) | shared: `evaluate-for-install` (`eval.clj:4439`) via `gate-function-install` (`turn.clj:3222`) | shared: `settle!` (`turn.clj:3793`) → `evaluation-facts` (`:3533`) | shared, but **inside the evaluator**, up to twice (§0.5) |
| System-submitted source (`virtual-turn!`, `turn.clj:2293`; the page's send) | `submit-source!` (`agent.clj:635`) → run → same proc | shared | shared | shared | shared (as above) |
| System openings and refreshes | `system-turn` (`turn.clj:2077`) | shared | shared, through `evaluate-sources` | shared | shared (as above) |
| Page preview | `preview-sources` (`turn.clj:4822`) | shared | shared, same fork and loop | none, by declaration ("nothing settles") | shared (as above) |
| **MCP SCI mode** | io-prepl form `sci-evaluation-form` (`mcp.clj:380-425`) | shared: `one-event` inside `evaluate` (single form) | shared `evaluate`, but **no agent, run or handle**, and the **shared** context without a branch | **none** (§0.1) | shared shown text, then **re-decoded** by `decoded-projection-event`/`shown-event` (`mcp.clj:280-303`) and chosen by `mcp-project` (`cluster.clj:420-431`) |
| **MCP JVM mode** | io-prepl form `jvm-evaluation-form` (`mcp.clj:347-379`) | shared reader `seon.sci.reader/read` (R7), plus a **copy** of `one-event`'s count policy (`mcp.clj:367-378`) | **copy**: `clojure.core/eval` in the form | **none**; a JVM exception is summarised, not stored (§0.2) | **copy**: `mcp-valf` → `mcp-project` → `admit/canonical-edn` (`cluster.clj:388-534`) |
| MCP error window | a second prepl form per evaluation, `window-form`/`branch-errors!` (`mcp.clj:511-563`) | — | **copy**: `seon.error/occurred-since` over a bridge atom `window-bases` (`mcp.clj:37`) | **copy**: bases held in the bridge process | **copy**: the bridge's own map, clipped at 20 (`mcp.clj:38`) |
| Test member | `seon.test/run` (`test.clj:2061`) → `member-result` (`:1846`) → `bounded-result` (`:211`) on a virtual thread | **copy** (by necessity): `resolve-test` (`:1411`) resolves a symbol to a Var; there is no text | shared SCI arm for SCI bodies (`sci.eval/run-test`, `eval.clj:4530`); host bodies unarmed through `runner/run-var!` (`runner.clj:401`) | **copy**: the test owner's own result transaction through `:seon.test/recording-writer` (`test.clj:2070`); no evaluation facts | **copy**: `failure-text`, `failure-message` (`test.clj:37-69`) and `failure-report` (`runner.clj:160`) render operands to strings early |
| Operator commands (`bin/seon`) | `seon.operator.prepl/io-prepl` (`resources/seon/operator/prepl.clj:50`), Clojure's `prepl` | Clojure's reader | Clojure's `eval` | none (diagnostic; ruled exception, review P1-F) | `mcp-valf` when `seon.cluster` is loaded (`prepl.clj:26-27`), else `pr-str`; the floor for failures (`:6-15`) |
| Publication | `seon.cluster.source/publish!` (`src/seon/cluster/source.clj:570`) | the analyzer `seon.fn/source-rows` over file bytes, and Clojure's reader in `require :reload` (`cluster.clj:2144`) | Clojure's compiler, through `require :reload` | program rows (the program partition), not evaluation facts | the publication report |

Two lessons from the table:

- **The agent's composition is already complete.** Five entrants use it end to end. Only the MCP modes and the test
  member own copies, and the MCP modes own the most (a second evaluator, printer, error summary, error window and
  blob-backed result store).
- **Publication is not a REPL entrant, and should not become one.** Files are the write-back of accepted rows, and
  the default cluster's rows derive from files. Publication shares the one stage that matters with the evaluator: the
  analyzer, which S1's `admit-program-change!` also calls through `definition-rows`. A future "evaluate this file in
  my context" entrant would be the code reader over the file's bytes, with the file as the source's provenance; it
  needs no new stage.

### 2.2 Read

**Two segmenters over one reader, chosen by the entrant.** A model reply is prose with code in it, and
`planned-sources` exists to find the code (fences, prompt markers, delimiter repair). An outside agent's code, a
file or a generated form is code, and the planner drops parts of it (§0.4). The code segmenter is
`seon.cluster.reply/code-sources`: the one reader's events, each as one source, with reader errors refused whole
exactly as `one-event` refuses them. It is about ten lines over `seon.sci.reader/read`, with the reader context the
agent's context gives (`reader-context`, `eval.clj:940`). No reader is added.

**Read once is still a target.** Today the reply is segmented at submission and each form is read again by its
evaluator; `reply/sources` also parses twice (`src/seon/cluster/reply.clj:350-359`, pin 3785970b9). A form whose
reading depends on an earlier form in the same submission (`(require '[x :as a])` then `::a/k`) is read at submission
without that alias. That is today's behaviour for replies, and the code segmenter inherits it. **S0** carries the
reader event (exact bytes, namespace, aliases, repair evidence) from submission into evaluation, and reads each
successive form after the namespace effects of the form before it, as `clojure.main/repl` does. S0 is unpriced until
its call graph is traced, and no other slice depends on it.

**The JVM evaluator reads with the one reader too.** R7 (2828bc808) made JVM mode read with `seon.sci.reader/read`
in the target namespace, giving the reader that namespace's `ns-aliases`, `ns-map` Vars and classes, and
`*data-readers*` (`mcp.clj:357-364`). That mapping is the JVM counterpart of `reader-context`, and it moves into the
JVM evaluator (§2.3). The count policy it copies from `one-event` goes with it: the JVM evaluator receives one
segmented source, as the SCI evaluator does.

**Qualified definitions stay deferred** (1.10's `(defn other.ns/f …)` clause; §0.10). The later cut separates the
reader context (the session's) from the target evaluation and analysis context, expands once under the existing arm,
and passes the target namespace and aliases to `definition-rows`. Its proofs: a macro counter shows a single
expansion; a target alias resolves; a reader-resolved `::kw` behaves correctly; an unknown namespace is refused by
name; a private def works; the session namespace is unchanged. It also needs `seon.fn/source-rows` to stop doubling a
same-namespace qualified name. Until then agents use `in-ns` then `defn`. No shim is authorized.

### 2.3 Evaluate

**The SCI evaluator is the default and is unchanged by this design.** S1 landed its single definition authority
(landings `lane-impl-repl-m1-2026-09-25.md`, `lane-impl-repl-s1-revise-2-2026-09-25.md`): every changed function or
test Var matches exactly one analyzed row by the analyzer's byte span and SCI's root-write mark, or the form refuses as
`:seon.sci.eval/unindexed-change` or `:seon.sci.eval/multiple-declarations`; contracts validate and arm inside the
boundary; one capture taken before any mutation restores the context at every failure point; a restoration that
cannot run leaves the execution unavailable with its whole cause. Its limits stand: classes defined by a refused
`defrecord` stay defined; point-free `def`s and macro-generated `defn`s are refused as unindexed until the analyzer
classifies them.

**The removal member of the change set** is still to build (S4, §3.2). Removals are the program identities whose
intern disappeared. Before the form runs, the evaluator holds SCI's `namespace-state`
(`reference-code/sci/src/sci/core.cljc:751-760`, an O(1) read); afterwards only the namespace maps that are not
`identical?` to the held ones are diffed by key. A removed intern counts only when the held before-state gives it a
row-backed identity (`:seon.fn/sym` or `:seon.test/sym` in the acquired database). A private row-less Var unmaps with
no program write.

**The JVM evaluator, `seon.repl.jvm/evaluate`**, is the second value. It is `jvm-evaluation-form`'s body as a
contracted program function instead of a quoted string sent to the prepl:

1. bind `*ns*` to the request namespace (created with `clojure.core` referred, as JVM mode does today);
2. read the one source with `seon.sci.reader/read` and the JVM namespace mapping above;
3. take `loaded-code-mark` (moved here from `cluster.clj:247-276`: it is this evaluator's program-change
   observation, the counterpart of SCI's `turn-interns`), then `eval` the form with `*out*` and `*err*` bound to the
   same byte-bounded capture the SCI evaluator uses;
4. on a throw, build the error with `kernel/failure-value` (`src/seon/sci/kernel.clj:526`), the SCI evaluator's own
   failure normalizer, so the whole cause (class, message, `ex-data`, chain) reaches settlement and its error
   storage; nothing summarises it;
5. when `loaded-code-changed?`, offer `:seon.render.web/runtime-eval` to the page channels the request carries (the
   world passed explicitly, not read from `running-instances`);
6. answer `:seon.sci.eval/evaluation`: the admitted value (`admit/admit-value`, as the SCI evaluator admits), the
   ending namespace, the admission record with duration, outcome and allocation, and **no program row**.

JVM evaluations are never program: the default cluster's program derives from files, and a host `defn` changes a
compiled Var, not a row (AGENTS.md, "One JVM, many realities"). `gate-function-install` sees no candidate row and
passes the evaluation through.

**Bounds.** The SCI evaluator interrupts interpreted code at its time limit. The JVM evaluator cannot interrupt host
code (AGENTS.md, "SCI interrupts interpreted code, not arbitrary host calls"). Its bound is therefore the entrant's
completion bound (§2.6): at expiry the caller gets the run id and a requery form, the agent stays busy until the host
call exits, and nothing overlaps or replays.

**The test evaluator** is the capture owner: `sci.eval/run-test` for SCI bodies and `runner/run-var!` for host
bodies, under `bounded-result`'s thread and exit observation (§2.7).

### 2.4 Record

| Entrant or material | Disposition |
|---|---|
| Product replies, system submissions, MCP SCI and MCP JVM | one run each, settled by `settle!`; the evaluator's name on each non-default evaluation |
| Generated openings and refreshed reads | the same recording owners (`system-turn`); historical text and read evidence kept; writes never replay |
| Refresh candidates | **only default-evaluator evaluations**: `read-only-evaluation?` (`turn.clj:1959`) excludes any evaluation carrying `:seon.cluster.eval/evaluator`, because refresh re-evaluates in SCI and would run a JVM form in the wrong evaluator |
| Test members and task proofs | the same receipt builders around the existing capture and bounds (§2.7) |
| Test-selection refusals | the test request's own declared result (no member evaluation exists to record) |
| Raw source, evaluation facts, program rows | source and provenance kept apart from declarations; only the program partition merges |
| Live result objects, private state | existing SCI-context handles (`result/e…`); never serialized; after restart, typed unknown; a JVM result has no handle and its durable form is its shown text |
| Error occurrences, result and report blobs | existing staging, publication and retention owners; core faults also get cluster visibility and delivery |
| Browser cuts, MCP retrieval | one cut entrance, never re-evaluating the origin; `get_value`'s blob store waits for the owner's result-store ruling (§2.9, Q3) |
| Host prepl (operator commands, `mode "host"`) | nothing, by the ruled diagnostic exception; the reply says `:seon.dev.mcp/recorded? false` |

**`record-evaluated-call` is not the JVM recorder.** Revision 3's hybrid would have recorded JVM evaluations through
it (8.6, probe P-86). Under this design JVM evaluations are runs, so settlement records them and the completed-run
recorder keeps no production caller (§0.6). Whether tests keep it as a fixture writer or move to `virtual-turn!` is
root's call; it is about 130 lines (`turn.clj:1454-1585`) that no entrant uses.

**The durable result reference stays the owner's open decision** (revision 3 §2.3, unchanged): `render.value/artifact`
is a print node, not a plain-data artifact; an entity lookup plus basis-t is not a pinned observation across
divergent branches without a commit identity; the schema is unpriced. M1 returns the shown bytes and, for SCI, a live
handle, and keeps the complete error and test evidence through the existing owners.

### 2.5 Print

Selection per face, first match wins (11.1; AGENTS.md "Render ordinary values totally"): the value's own key; a
contract-fitting render function in the **viewing** agent's namespace; the schema's declared pair; the floor.

- **Print leaves the evaluator (slice P1).** `shown-result` (`eval.clj:3659`) is called once by `evaluate-sources`,
  after `gate-function-install` and any install refusal have made the evaluation final. The evaluator carries the
  inputs print needs as data: the admitted value, its record, and the declared returns of a top-level call's head
  (`:seon.render.call/function`, computed today at `eval.clj:4376-4377` from the execution context and form). The
  form's read sink covers the print as it does today (`form-read-sink`, the read-sink landing), so render reads stay
  the form's. The three render sites (`eval.clj:4141`, `:4380`, `:4500`) become one, and an install refusal no longer
  replaces a text that was already printed.
- **The shown bytes are the evaluating agent's view,** rendered once and saved exactly. Another viewer gets a current
  view, labelled as such, from the same selected value, tree inputs, profile and render-program identity.
- **MCP returns the shown bytes of the run's evaluations,** in ordinal order, as `seon.repl` stored them. It adds no
  printing (§2.6). The owner's rendered context for an MCP session agent is the task-context design's one view
  (`task-context-install-design-2026-09-25.md` §3); a session agent without a task gets its loud no-task value (§5
  there).
- **Render inputs come from call preparation (1.13).** For an inline function value, the shared owner consumes the
  retained declared contract through Malli (`m/schema`, `m/properties`, the cached `m/validator`,
  `reference-code/malli/src/malli/core.cljc:2561`, `:2592`, `:2637`). Caller arguments win; no contract is inferred
  from shape. This belongs to `call_preparation.clj`'s owner (§0.14).
- **The host prepl keeps what it needs:** no database, configuration or projection; `valf` is `pr-str` for protocol
  values (read back by `operator.clj`'s Clojure reader, `script/seon/operator.clj:133`) and the floor for failures
  (`prepl.clj:6-15`); `:out`/`:err` bounded at the collector; machine responses never clipped and reparsed. With
  `mcp-valf` gone, `prepl.clj:26-27` loses its lookup and `operator.clj:230-231` its projection mark.
- **OPEN 9.5 (owner): may the floor realize a lazy value?** Unchanged from revision 3: the recorder never realizes a
  value to store it; a `*print-length*`-style bounded prefix labelled as realized is the alternative to put beside
  "never realize", for the floor owner (`one-render-path-design-2026-09-25.md`).

### 2.6 MCP: transport and stage choice, nothing else

The bridge becomes a view (AGENTS.md "The Seon agent is the perspective"). Each `eval_clj` call is one agent run, and
the bridge's only decisions are which agent, which reader and which evaluator:

| `eval_clj` call | Agent | Reader | Evaluator | Record | Print |
|---|---|---|---|---|---|
| `mode "sci"`, no branch | root, on the cluster's branch (the orchestrator acts as root) | code | SCI (default) | root's run | stored shown bytes |
| `mode "sci"`, `branch` | `mcp/<session-id>` on that branch, created and armed by the bridge's call, owned by its session entry | code | SCI (default) | that agent's run | stored shown bytes |
| `mode "jvm"` | root, on the cluster's branch (ruled: JVM mode is root's) | code | `seon.repl.jvm/evaluate` | root's run | stored shown bytes |
| `mode "host"` (new name for the diagnostic path) | none | Clojure's | Clojure's | none (ruled exception) | the host prepl's `valf` |

**The one entry, `seon.cluster.agent/evaluate-source!`,** is `submit-source!` plus completion, in the owner of
`submit-source!`. The bridge sends one prepl form that calls it with the request map of §2.0:

1. resolve custody from the running instance: the cluster handle, or the branch handle through `acquire-context!`,
   and the agent (for `mcp/<session-id>`, create and arm it if the session entry does not already own it);
2. register a Datahike listener for the terminal fact of this agent's runs **before** submitting (the notification
   seam: `d/listen`, as `src/seon/cluster/wake.clj:499` uses it);
3. call `submit-source!`, which allocates and returns the run id (`agent.clj:697`, answered at `:716`);
4. await that run's terminal fact with `seon.await/await!` (`src/seon/await.clj:106`) under the request's bound,
   filtering on the run id, then re-read durable state once so a close that landed before the listener saw it is
   not missed;
5. answer the run id, each evaluation's stored shown bytes in ordinal order, and the **errors since** this agent's
   previous evaluation (`seon.error/occurred-since` from that evaluation's stored
   `:seon.cluster.eval/read-basis-transaction`; a first evaluation answers the typed initial value). The errors are
   error values, rendered by their declared renderer like any value; nothing clips them to 20.

**Busy, timeout and exit** are the agent's, not the bridge's:

- the run transaction refuses a busy agent;
- Flow runs one transform at a time on the turn proc's thread (`agent.clj:525-545`), so a second submission's wake
  is served only after the first transform returns; across graph replacement `arm!` refuses until the previous
  executor has terminated (`agent.clj:1044`);
- at the bound, the answer is the run id and a requery form that reads that run's evaluations by run id (P3 names
  the existing reader it uses; none is added), ownership is retained, and the next submission is refused as busy until
  settlement; there is no replay.

**Session identity.** The session key stays `[root cluster session-id]`. A branch evaluation requires an explicit
`session_id`; `"default"` is refused there because it would collide across callers. The session entry records the
agent id and the branch it armed on. Namespace continuation is the previous run's recorded ending namespace, passed as
`:seon.turn/starting-ns` (`agent.clj:661`); the bridge keeps no namespace state. The agent's settings carry
`:seon.config.ai/no-provider true`, so a submitted run never makes a provider attempt.

**Retirement.** `branch retire` retires only the agents the session owns, in order: `disarm!` (`agent.clj:1280`)
joins the executor, which is the actual-exit proof; the agent is archived; `release-context!` (`agent.clj:804`)
releases the context; the branch retires. A branch held by an agent the session does not own keeps today's refusal
(`mcp.clj:442-482`).

**What the bridge keeps:** transport, discovery, sessions, `runtime_status`, `branch`, and `get_value` until the
owner rules on the result store (Q3). `runtime_status` still renders its own bounded summary
(`cluster.clj:633-658`); that is a status view with its own printer and is named in §2.9 as the next cut, not booked
here.

### 2.7 Tests: the member is an entrant with its own reader and evaluator values

A test member has no text, so its reader stage is `resolve-test` (symbol → Var) and its evaluator stage is the capture
owner. Neither is a copy of an agent stage; both are values the pipeline accepts. What the member still copies is the
**record** and the **print**:

- **Record through the receipt builders.** The capture owner wraps the body once and returns its typed report
  (`runner.clj:359-399`, `:447-469`, pin 3785970b9), for SCI members (`run-test`) and host members (`run-var!`,
  unarmed by design, because a host body may evaluate its own SCI contexts). The member's evaluation is recorded
  through `evaluation-facts` and the receipt builders onto the **retaining** branch before the member branch is
  unlinked (`test.clj:2080-2093`, `:2235-2259`, pin 3785970b9), with member origin and tested identities, through the
  request's `:seon.test/recording-writer`. There is no recorder for tests alone.
- **A test member is not a run.** Running each member as a submitted run would cost a run transaction, wake and
  settlement per member (about 147 ms, `agent.clj:648`) across 1,669 members: minutes per full request. The member is
  an isolated agent context for one body (`test.clj:2074-2075`); its driver is the test request, which owns the
  batch, bounds and isolation. That driver is the test entrant's transport.
- **Print once.** `failure-report` and `report-identity` (`runner.clj:160-204`) keep native operands, rendered once
  by the one render path. This retires audit R10 (`report-value`, `message-text`, `failure-message`; 50 lines).
- **The candidate gate is one ordinary `seon.test/run` request** on the actual candidate. This retires audit C1
  (87 lines), conditional on candidate fixture, input and source-location proofs.
- Task proofs are ordinary deftests naming their task (7.3); selection refusals stay the request's declared result.

### 2.8 The host prepl, the page and openings

- **The host prepl** is Clojure's `prepl` with Seon's `valf` (`prepl.clj:50-73`). It stays the transport for
  operator commands and MCP, and it is the `mode "host"` diagnostic entrant: available when the cluster layer is not
  ready (boot publishes readiness per layer) and whenever root needs to see a JVM whose agents are wedged. It is never
  chosen as a fallback after a timeout or refusal; the caller names it.
- **The page's send** is already `submit-source!`; its preview is already `evaluate-sources` without a record. The
  page takes the same reader choice as MCP: the code reader for the message form's code, the planner for pasted
  replies. That choice belongs to `render/web.clj`'s owner and is not in these slices.
- **System openings and refreshes** already use the pipeline. The refresh exclusion of §2.4 is their only change.

### 2.9 What disappears

Counts are source lines at 19a29c683, from `awk` over the `def` boundaries.

| Deleted | Where | Lines | Slice | Replaced by |
|---|---|---:|---|---|
| `decoded-projection-event`, `shown-event` | `mcp.clj:280-303` | 24 | P3 | the stored shown bytes |
| `sci-evaluation-form`, `remote-evaluation-form` | `mcp.clj:380-431` | 52 | P3 | one `evaluate-source!` form |
| `window-bases`, `max-window-errors`, `window-form`, `branch-errors!` | `mcp.clj:37-38`, `:511-563` | 55 | P3 | errors since the agent's previous evaluation, in every agent's answer |
| `execute-clj-eval`, reduced to transport | `mcp.clj:564-662` | ≈ 54 of 99 | P3, P4 | — |
| `jvm-evaluation-form` (its 9-line reader mapping moves) | `mcp.clj:347-379` | 24 | P4 | `seon.repl.jvm/evaluate` |
| `mcp-projection`, `project-next-prepl-value!`, `consume-mcp-projection!` | `cluster.clj:244-246`, `:277-304` | 31 | P4 | nothing: the evaluator holds its own mark |
| `nil-deref?`, `first-seon-frame`, `exception-summary` (audit R6) | `cluster.clj:334-367` | 34 | P4 | `kernel/failure-value` and error storage |
| `mcp-projection-error` | `cluster.clj:368-387` | 20 | P4 | — |
| `mcp-project` | `cluster.clj:388-495` | 108 | P4 | the one print, once |
| `mcp-valf` | `cluster.clj:496-534` | 39 | P4 | `pr-str` / floor in the host prepl |
| `mcp-io-prepl` (dead) | `cluster.clj:535-553` | 20 | P4 | — |
| projection lookup and marks | `prepl.clj:26-27`, `operator.clj:230-231` | 4 | P4 | — |
| three render sites become one | `eval.clj:4141`, `:4380`, `:4500` | ≈ 10 | P1 | one call in `evaluate-sources` |
| **Unconditional total** | | **≈ 475** | | |
| `get_value` and its blob store: `hex-digit?`, `digest!`, `path!`, `execute-get-value`; `mcp-get-value`; `mcp-effective` | `mcp.clj:728-782`; `cluster.clj:555-611`, `:309-333` | 137 | after Q3 | the one cut entrance, if the owner rules so |
| `record-evaluated-tx`, `record-evaluated-call` and their helpers | `turn.clj:1454-1585` | ≈ 130 | root's call | `virtual-turn!` in fixtures |
| R10, C1 (conditional) | runner, test, eval | 137 | P6 | §2.7 |

**Moved, not deleted:** `identity-hash-map?`, `loaded-code-mark`, `loaded-code-changed?` (`cluster.clj:247-276`, 30
lines) into the JVM evaluator. **Remaining after this cut, named for the next one:** `runtime_status`'s
`bounded-face`/`bounded-summary` printer (`cluster.clj:633-658`); the bridge's `branch-form` building its own
`refuse#` error maps (`mcp.clj:442-482`), which should be declared errors of `seon.cluster.registry`; the reply
planner's silent drops (§0.4).

### 2.10 What each stage costs

| Stage | Today, measured | After | Evidence |
|---|---|---|---|
| read (segment) | planner 0.86 ms for six forms; the code reader answered four texts inside one 5 ms round trip | the same owners | this lane's probes |
| evaluate, SCI | `(+ 1 2)` 5 ms, 579 KB; a contracted one-line `defn` 16.3 ms, 33 MB | unchanged | this probe; S1 landing |
| evaluate, JVM | a small query 3 ms round trip | the same `eval`, plus one `loaded-code-mark` walk over `all-ns` before and after (O(namespaces)) | this probe; UNMEASURED for the mark |
| record | MCP: nothing; agent: run transaction, wake and settlement, about 147 ms per source turn | **every MCP call pays it**: about 5 ms → about 150 ms | `agent.clj:648` (an earlier measurement, not repeated here) |
| print | once or twice inside the evaluator; the render profile is derived once per turn (14.7 ms of a 48.8 ms six-form turn) | once per evaluation | `turn.clj:4704-4710` |
| completion | the bridge's second prepl round trip for the window | one listener, one filtered promise, one recheck query | — |
| refresh after a run | none for MCP | **3.3 s if the agent holds an issue** (§0.9) | task-context design §0.3 |

The target is a warm MCP SCI or JVM evaluation under 250 ms end to end, measured by P3 and P4 on the parent and the
change with allocation. The refresh cost is the task-context design's to remove; until it lands, P3 must show that a
submitted MCP run does not trigger `system-turn` (root holding no issue, or the refresh skipped for runs the agent
did not open), or stop.

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
order P1, P3, P4, S5 (§6). S1 has landed. P6 (tests through the receipt builders) lands when its conditional C1
proofs are ready. The flow:

1. A lane creates its branch and opens an MCP SCI session with an explicit `session_id`.
2. It evaluates `(in-ns 'n)` and then `(defn f …)`. Each call is one run of `mcp/<session-id>` on the branch,
   read by the code reader and settled by `settle!`; settlement records one row (S1, P3).
3. It runs `(my.test/check {:seon.test/changed ['n/f]})` on the branch. The existing harness is used until P6
   lands, and until then there is no "one path for tests" claim.
4. Root runs `prepare-merge!` with the task, `accept-merge!` with the tested run, and `write-back!` (S5) for the
   one file. Root names the accepter and the tested head.

The proof root runs for M1:

- the same source through `virtual-turn!` and through MCP gives the same settled row and the same shown bytes;
- a multi-form MCP call evaluates in order, and a top-level reader conditional and regex are evaluated, not dropped;
- effects run once; a busy agent refuses; a timeout answers the run id and keeps ownership; no provider attempt row;
- JVM `(/ 1 0)` stores one error occurrence with its whole chain, and the MCP answer is its declared renderer's text;
- a JVM `defn` offers the page's runtime-eval signal, and a JVM `(+ 1 2)` does not;
- a refresh never re-evaluates a JVM evaluation;
- an error written between two calls appears in the second call's errors since;
- a second call and an affected caller run the new definition; focused tests are tied to its digest;
- a moved cluster head, a moved source head and a moved file each refuse write-back; the accepted row, the file
  bytes and the reindexed digest agree; an injected publication refusal restores only an unmodified candidate and
  reports accepted-but-not-written.

Each phase is timed separately, with allocation: segmentation, evaluation, settlement, print, completion, test
selection and body, merge, candidate proof, installation. Any phase over one second names its owner.

**D1 — the first dogfood milestone: a task settles through M1.** A lane takes one task whose fix replaces one
existing function (a task in `resources/seon/tasks/`, chosen by root), works
it through MCP SCI on its own branch, names the task on its proof deftest, and drops the task from the replacement's
attribute map in the same accepted row (7.6). Root merges; write-back lands the one file.

**M2** adds new functions and tests, deletion (S4) and its merge (R8), additions and retractions in write-back
(R9b), and S0. **M3** adds qualified definitions (§2.2) and the durable result store, following the owner's ruling.

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
   - the render boundary should stop passing the database as a caller argument (landing 549b4d58c: 47 of 105
     renderers read it);
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
| evaluation → value, error, rows, evaluator | evaluation row + refs; `:seon.cluster.eval/evaluator` when not the default | partly; §2.4, P4 |

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
| errors since the last evaluation | every settled response | durable per-execution basis (8.3); first and lost observations typed | today the bridge's window (`mcp.clj:511-563`); becomes part of `evaluate-source!`'s answer in P3 |

## 6. Slice plan, priced

**Rules.**

- Deletions are counted at 19a29c683 from the `def` boundaries (§2.9). Audit-ledger groups
  (`duplicate-paths-audit-2026-09-25.md` §11) are named where they apply (R6, R7 landed, R10, C1), each once.
- Relocated code is neither added nor deleted. Lines outside the ledger are shown and never used to excuse growth.
- Every size is an UNVERIFIED estimate until its landing measures the diff, with schema, caller and regression costs.
- Each slice converts every caller of what it deletes, including tests, in the same commit (AGENTS.md step 5).
- File ownership is root's ledger (`tmp/orchestrator/file-ownership.md`). At this pin `src/seon/sci/eval.clj` has
  another lane's uncommitted edits, so P1 waits for that file.

| # | Slice | Owner files | Added | Deleted | Net | Proof root runs |
|---|---|---|---:|---:|---:|---|
| **S1** | landed: one definition authority (91eb8e4a5 and the revise-2 commits) | — | — | — | +59 measured | its landings |
| **P1** | print once: `evaluate-sources` calls `shown-result` on the final evaluation; the evaluator carries the head function as data; the read sink covers the print | `src/seon/sci/eval.clj`, `src/seon/turn.clj` | +20 | −10 | +10 | a refused install shows only the refusal; a success shows once; a form's render reads stay in its read evidence; preview and refresh `unchanged?` comparisons still hold |
| **P3** | MCP SCI is an agent run: `:seon.turn/reader` on submission with `code-sources`; `evaluate-source!` (custody, listen, submit, await, recheck, shown bytes, errors since); the `mcp/<session-id>` agent on a branch, owned and retired by its session; root's run without a branch | `src/seon/cluster/agent.clj`, `src/seon/cluster/reply.clj`, `src/seon/turn.clj` (submission key only), `script/seon/dev/mcp.clj`, the turn and agent schemas | +105 | −185 (shown decoding 24, SCI forms 52, window 55, `execute-clj-eval` ≈ 54) | −80 | the M1 proofs for SCI; a `defn` via MCP is one settled row; retire leaves unowned agents |
| **P4** | MCP JVM mode is a run with the JVM evaluator: `:seon.turn/evaluator` resolved in `evaluate-sources` and recorded as `:seon.cluster.eval/evaluator`; `seon.repl.jvm/evaluate`; refresh excludes non-default evaluations; `mode "host"` names the diagnostic prepl; the MCP projection, error summary and marks deleted | new `src/seon/repl/jvm.clj`, `src/seon/turn.clj`, `src/seon/cluster.clj`, `script/seon/dev/mcp.clj`, `resources/seon/operator/prepl.clj`, `script/seon/operator.clj`, the eval schema, `test/seon/cluster/mcp_test.clj`, `test/seon/mcp_test.clj`, `test/seon/render/web_context_test.clj` | +71 | −290 (R6 34, `mcp-project` 108, `mcp-valf` 39, projection error 20, marks 31, `mcp-io-prepl` 20, JVM form 24, prepl lookups 4, transport rest ≈ 10) | −219 | the M1 proofs for JVM; `bin/seon status`, `start` and `config apply` still read their replies |
| **S5** | write-back of one file (§3.3): acceptance members on `accept-merge!`, two fences, before-state provenance, in-memory candidate proof, installation fence, guarded restore, unavailable state, accepted-but-not-written report | `src/seon/cluster/source.clj` | +75 | 0 | +75 | the M1 write-back proofs |
| **P6** | tests through the receipt builders onto the retaining branch; host members unarmed as today; native report rendered once; candidate gate as one request | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/sci/eval.clj`, the test schema | +95 | −137 (R10 50, C1 87, conditional) | −42 | a member leaves one evaluation on the retaining branch; failure keeps native operands; the candidate gate records a whole run |
| **S4** | deletion's removal member, namespace entry, public forms as fork changes, writer-side decision (§3.2) | `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/my/program.clj` | +30 | ≈ −50 (`my/program.clj:510-575`, pin 3785970b9) | −20 | the §3.2 proofs |
| **S6** | accepted-change observation (§5 row 1) | `src/my/program.clj`, `src/seon/turn.clj` | +40 | 0 | +40 | a renderer replacement shows its schemas; ≤ 50 ms measured |
| **S7** | configuration (§4.4) | `src/seon/config.clj`, `src/seon/cluster/boot.clj`, `script/seon/operator.clj`, config schemas, nine reader files, `config/*.edn` | +75 | ≈ −30 | +45 | the §4.4 proof list |
| **D0** | delete `config/test.edn` after a verified no-reader check | file | 0 | −6 | −6 | HEAD loads |
| later | S0 read-once; R8 deletion merge; R9b; qualified definitions; durable result store and `get_value` (−137 if Q3 rules deletion); the completed-run recorder (≈ −130, root's call); `runtime_status`'s printer; registry-declared branch errors | — | unpriced | — | — | — |

**P3 is at the bound.** If Astra or root judges +105 too large for one cut, the priced split is P3a (root's run
without a branch, +70 / 0, the branch path temporarily kept on `sci-evaluation-form` and named as a retirement) then
P3b (the owned session agent, +35 / −185). The split carries one extra commit in which two SCI routes coexist.

**M1: three options** (AGENTS.md step 4; the simplest viable constraint first):

| Option | Slices | Guarantee | Added / deleted / net | Sacrifice |
|---|---|---|---|---|
| A. MCP becomes a view | P1 + P3 + P4 | every MCP evaluation is an agent run, recorded and printed once through the one path; the MCP-only printer, summary, window and forms are gone | +196 / −485 / **−289** | nothing lands from the REPL into files yet |
| **B. A plus a landable replacement (recommended)** | A + S5 | as A, and one existing-function replacement lands REPL → merge → one file, proven before exposure | +271 / −485 / **−214** | tests stay outside the path until P6 |
| C. B plus one path for tests | B + P6 | as B, and test members record through the receipt builders and print once | +366 / −622 / **−256** | depends on the conditional C1 credit; without it +366 / −535 / **−169** |

B is recommended: it delivers the owner's law for MCP and the dogfood landing, and it does not rest on C1's
conditional credit. Every option is net deletion; that does not excuse any machinery a slice's review finds
unnecessary.

**Open questions for the owner, priced (simplest viable first; recommendation marked).**

**Q1. Where does a JVM-mode evaluation run?** (Ruled: it is recorded as root's.)

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| (a) **Root's run on root's Flow graph, with the JVM evaluator (recommended; matches the ruling)** | one process for every recorded evaluation; errors stored; busy and exit are the agent's | P4 as priced | a long host call keeps root busy; root diagnoses a wedged root through `mode "host"` |
| (b) The session agent's run (`mcp/<session-id>`) on its own graph | isolation: a stuck host call blocks only that session | P4 plus arming a session agent on the cluster's own branch (+15) | the evaluation is the session agent's, not root's: the 8.6 ruling must change |
| (c) The prepl thread, recorded through the completed-run recorder | root's graph is never involved | P4 minus the evaluator key, plus +25 to make `record-evaluated-call` take the author | a second driver beside the run; still refused while root has an open run (`turn.clj:1497`); revives a test-only path |

**Q2. What is an SCI call without a branch?**

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| (a) Refused: SCI requires a branch | no outside write ever reaches the cluster's own branch through MCP | −10 in P3 | the orchestrator's quick SCI probes need a branch |
| (b) **Root's run on the cluster's branch (recommended)** | the orchestrator acts as root (AGENTS.md); gated, recorded, settled like any root turn | as priced in P3 | an outside caller holding root's MCP session writes as root on the live cluster |
| (c) A session agent on the cluster's own branch | per-session identity even on the cluster | +15 | a second armed agent on the live cluster per session |

**Q3. What happens to `get_value` and the MCP blob store?**

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| (a) Delete it; an oversized shown text elides at the floor with its requery form | one store, one printer | −137 | no drill-down into a value larger than the floor's budget until the cut entrance serves MCP |
| (b) **Keep it until the durable result reference is ruled (recommended)** | no loss of today's retrieval | 0 now | one MCP-only store survives this milestone |
| (c) Serve MCP retrieval from the one cut entrance now | one retrieval path for page and MCP | unpriced (the render plan's cut work) | delays M1 behind the render plan |

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
| 8.8 | new | **An MCP SCI session is a named, owned agent on a branch, read by the code reader:** explicit session id; custody selected on every call; completion by listening before submitting, then rechecking by run id; retirement disarms only the agents the session owns |
| 1.16 | new | **The REPL path is a composition of stage values.** A submission names its reader and evaluator as qualified symbols of program functions satisfying the defaults' contracts (the reply planner and the SCI evaluator); record and print are shared and not choosable; the run stores the evaluator, and each non-default evaluation records it (§2.0) |
| 1.17 | new | **Code is read as code.** An entrant submitting code (MCP, files, generated forms) uses the code reader, which evaluates every form the one reader reads; the reply planner is only for model replies (§0.4, §2.2) |
| 9.5 | note | The recorder never realizes a value in order to store it; place the bounded-prefix alternative beside "never realize" for the owner (§2.5) |
| 13.2 | extend | Add the typed environment between the overlays and the cluster facts; record the stack, environment decisions and default provenance atomically; an explicit apply replaces the stack, a start reuses it; re-apply on changed documents, schema defaults or initialization |
| 13.3 | new | **One effective-configuration reader** derives layers 1-4 for a named cluster (plus 5 for an optional agent), propagates read refusals before merging, and validates the final value; historical readers keep their database value |

## 8. Verification limits

- **Revision-4 probes** (default pid 37460, MCP session `design-one-pipeline`, all read-only JVM forms except one SCI
  `(+ 1 2)` on the shared context, which defines nothing):
  - evaluation and run counts before and after an SCI call: 108 and 42 both times (§0.1);
  - the latest stored agent evaluation's keys (§0.1);
  - JVM `(/ 1 0)`: the `exception-summary` answer and its transport frame; `seon.error/occurred-since` over the window
    answered `[]` (§0.2);
  - `turn/planned-sources` against `seon.sci.reader/read` on six texts (§0.4), 0.86 ms for the six-form text;
  - the database basis advanced by 19 transactions during the probes from other writers; nothing here attributes
    them, and the counts above are the evidence used.
- **Source reads at 19a29c683:** `mcp.clj` (whole evaluation, window and branch paths), `cluster.clj:241-658`,
  `resources/seon/operator/prepl.clj`, `agent.clj` (`turn-step`, `submit-source!`), `turn.clj` (`evaluation-facts`,
  `record-evaluated-*`, `evaluate-sources`, `preview-sources`, `restore-evaluations!`, the refresh call sites),
  `eval.clj` at HEAD via `git show` (`reader-context`, `one-event`, `shown-result` and its three callers,
  `evaluate-for-install`, `accept-candidate!`, `refuse-install`, `run-test`), `test.clj` (`bounded-result`,
  `member-result`, `run`), `clojure.main/repl` and `clojure.core.server/prepl`.
- **Landings and designs read:** S1 (both), the MCP bridge landing through R7 (171998c21), the read-sink landing, the
  task-context design; the review's P1-C, P1-D and P1-F.
- **Not run:** no write, submission, test, adoption, arming or new code. P3's and P4's end-to-end costs are estimates
  from `agent.clj:648`'s earlier measurement, not a repeated one.
- **Unverified, to be established by the owning slice:**
  - which writer creates a product agent row with a namespace assignment (P3 uses it for `mcp/<session-id>`;
    `seed-root-agent!`, `cluster.clj:3231`, is the one found);
  - whether root holds an issue on default, and so whether a submitted MCP run triggers `system-turn` (§0.9);
  - that `code-sources` with the agent context's reader bindings reads every form the JVM evaluator later re-reads;
  - the `loaded-code-mark` walk's cost per JVM evaluation;
  - the existing reader of a run's evaluations by run id, for the timeout requery;
  - every line estimate in §6.
- **This lane's delta.** Source and test: 0 / 0. Changed path: this file only.
