---
title: "Independent review: why chain run 2 stalled"
type: research
status: complete
created: 2026-10-10
---

# Independent review: why chain run 2 stalled

Seon takes a task and program entities, constructs a model context, evaluates the reply on an SCI branch, and
records its values and proofs. The useful Clojure composition is still functions over values, with a replaceable
evaluation stage. Requiring the model to reproduce a large definition, interpreting its explanatory examples as
commands, and calling a core reader repair an ordinary first task are unnecessary constraints on that composition.

**Verdict:** this was a poor first implementation task and an informative stress test. Context and editing friction
are real; so is a serious model failure to correct an explicitly identified typo. These runs do **not** establish
that SCI cannot redefine the target or that compiled callers ignored an accepted change. Every attempted replacement
of `scan` failed before admission. Do not turn the model's explanation of that failure into a platform diagnosis.

## Evidence and limits

Review by lane `astra-chain-friction`, 2026-10-10, read-only JVM forms through MCP as root on default. No provider
requests, branch writes, test executions on default, reloads, resets, or restarts were requested. MCP itself records
its read requests: the envelopes report one recording transaction, which is distinct from a transaction in a probe.
Default initially ran archive `e25afdf6e41019960589c28e3f24fbf00efcebcc`, pid 7763, start instant
`2026-10-10T09:18:29.917Z`. Health was `:faulted`: two open faults and 13 errored receipts, before this review.

Read AGENTS.md, plan §0/§2/§3, designed-task-examples §2, README-bench and the linked E1/E2 results, and
DYNAMIC-CONTEXT after its landing at `380290bba`. Read the complete
preserved exchanges for both branches, including reader errors and commentary, rather than only `model-evaluations`
(which excludes forms that did not evaluate). The second branch had acquired a further 15 calls by review time.

| Observation | First agent | Second agent, all calls available at review |
|---|---:|---:|
| Agent | `chain2-forgive-1` | `chain2-forgive-2` |
| Branch | `:agent-e84b11b8bd78` | `:agent-4e349b905cf7` |
| Observed branch basis | 536871095 | 536871171 |
| Model calls / evaluated forms | 15 / 61 | 30 / 77 |
| Grade's agent / platform errors | 7 / 0 | 25 / 1 |
| Proofs | 2 red, 1 green | 2 red, 1 green |
| Prompt / cached / completion tokens | 155,932 / 108,288 / 3,220 | 844,843 / 732,416 / 28,920 |
| First call open to last close | 31,563 ms | 187,631 ms |

The second agent's original 15-call interval ends at call entity 54159, closed transaction 536871109; calls 16–30
are subsequent work, not another independent trial. The assignment's four `defn`s in the original interval are
three unsuccessful `scan` definitions and one admitted scratch helper, `seon.sci.forgive-test/txt`.

**Reflection discrepancy:** neither branch's latest call was the requested retrospective. The first still ended
with the original file edit. The second ended with a handoff claiming a verified fix; its recorded incoming message
explicitly said, “Please don't work the task now. Answer in plain prose, no code”. Instead it spent another 15 calls
working. I requested the location of any separately stored reflections; no retrospective text is assumed here.
Plan §0 was revised during review to report this same reflection/resumption problem.

**Historical prompt limitation:** exchanges preserve the actual shown forms and replies. Exact historical
current-state prompts cannot presently be verified by the advertised replay. `model-context` at the second agent's
first recorded basis, 536871046, throws from `branch-cost` (details below). The system help was reconstructed with
its renderer, and a complete current-head prompt was read; that is not a claim that the head prompt is the original
wire request. Source inspection also shows that `held-task` reads the task home's current connection. A branch basis
alone therefore does not specify every input to the historical prompt.

## Ranking

Ranks are causal judgments about these observations, not estimated percentages. Several causes amplify each other.

| Rank | Cause | Evidence strength | Smallest useful response |
|---|---|---|---|
| 1 | Working context hides the task/source and editing requires whole-definition reproduction | Direct in both runs; source intervention enabled attempts but did not yield success | Whole task and current failure; whole source on request; an edit of one declaration through the existing evaluator |
| 2 | Model fails to read and correct explicit feedback | Direct, especially the second run | Smaller task; accurate local error context; require an admitted definition before discussing stale callers |
| 3 | Task choice combines reader semantics, three defects, and incompatible-looking old expectations | Direct discovery by the second agent; structural mismatch with the stated ordinary-task pilot | Resolve policy in the chain's specification stage; use an ordinary small owner for the first implementation |
| 4 | Reply protocol executes explanation as code and gives conflicting instructions for reflection | Direct fragments, markup, no-form error, and continued work | Explicit code/prose intent at the entrance; a question must not also demand implementation forms |
| 5 | Familiar operations and actual capabilities diverge | Direct source, test, reload and write-back replies | Local fixes and runnable operations that name the real argument shape and effect |
| 6 | Interpreted branch proof is not compiled platform acceptance | Real design constraint; not the cause of rejected `scan` definitions | Keep SCI for behavioral probes; route compiled platform proof through the existing candidate JVM path |
| 7 | Prompt replay and grade categories are insufficient for diagnosis | Direct review-time failure; E2 already demonstrated misclassification | Preserve all prompt dependencies or the exact bounded prompt units; classify errors by producer |

### 1. The working context and edit unit

First run, call 1, result `e4997524377e0`:

```clojure
(my.program/source 'seon.sci.forgive/scan)
;; :seon.repl/finding :large-value
;; :seon.eval/value-tokens 3533
;; :seon.eval/shown-tokens 1989
;; :seon.repl/fix
;; "A value this big usually means a query or function returns more than it should: select what you need."
;; source ends at the stray-closer branch:
;; (do (.append out text (int copied) (int i))
;; …+4943 (seon.print/full result/e4997524377e0)
```

The omitted part contains the edit site. The continuation existed: the model did not follow it. It instead asked
for nonexistent Vars `seon.sci.forgive/code-line!` and `seon.sci.forgive/repair!`, both local closures inside `scan`.
Those calls answered “No program declaration names that symbol.” This is shared causation: poor default source
presentation plus failure to use the offered escape. “It could not see the function whole” is too strong; it was
not shown whole and the model failed to retrieve the rest correctly.

Calls 4–9 tried `clojure.repl/source`, then `slurp` and line selections. `clojure.repl/source` itself produced a
`:large-output` warning telling it to return a value. The agent was warned both for returning source and printing
source. Call 9's 1,476-token selection was warned even though all 1,476 tokens were shown. This is advice against
the work the task actually requires.

The second run's call 1, result `eab546ea1b1d2`, shows the entire definition without that cut. It subsequently
attempts three whole `scan` replacements in the original interval. That is evidence the source change helped
reach editing, not a controlled measurement of its success rate.

The preserved definition is **178 lines**, about **3,509 estimated source tokens** (3,533 including the first
render), rather than the roughly 250 lines in the assignment. It contains a scanner, repair accounting, prefix
tracking, string handling, line/fence classification, and multiple mutable local accumulators. A small change
requires reproducing all of them. The model introduced `@balanced?` while reproducing an unrelated return line.

The current-head prompt still cuts the task mid-sentence:

```text
... a quote before an inserted closer falls outside ev"…+431
```

It also shows `(my.program/source …1)`, `(my.program/callers …1)` and cut proof failures, with no runnable
continuation at those cuts. The second agent's own call 25 commentary specifically mentions the “+430 truncation”.
Thus fixing source alone does not fix the task block. The hidden intent includes the already-designed decision
that one repair carries both `:seon.batch/dropped` and `:seon.batch/inserted`; the agent spends calls rediscovering it.
First-run attempts to retrieve the task compound the problem:

```clojure
(:seon.task/intent (seon.context/current-state))         ; nil, 54 ms, 99.2 MB
(:seon.task/intent (:task (seon.context/current-state))) ; nil, 54 ms, 98.7 MB
```

The actual path is `[:seon.context/task :seon.task/intent]`. A task-centered UI should not require discovering that
path just to read the assignment.

**Fix:** context change in `seon.context/current-state-ai` and source rendering in `my.program/source`. Put the
whole short intent, complete current counterexample, current namespace, accepted change status and next runnable
operation first. Keep names for unchanged green proofs; show their source on request. Derive less before printing;
do not ask the generic printer to decide which half of the task can disappear.

**Editing design:** permit an edit to the held declaration's source, checked against its definition digest, then
evaluate the resulting complete declaration through the existing entrance. Reuse `rewrite-clj.zip/replace` and the
existing admission/write-back owners; no second evaluator or ad hoc regex patcher. Work is O(the changed declaration)
plus its existing proofs. The model supplies a local replacement; it need not regenerate 178 lines. This replaces
the copying burden and the temptation to mutate a filesystem archive. Splitting `scan` by real responsibilities is
also useful, but making a refactor a prerequisite to every three-line repair would repeat the same mistake.

### 2. The model really did fail to use correct feedback

Second run, calls 12, 14, 15; results `e64a773301469`, `efa7a7fb43900`, `edb913fbe31a7`. All three authored bodies
bind `balanced`, then end with the wrong local name. The exact decisive fragments are:

```clojure
balanced (volatile! true)
;; first two return-map tails:
::balanced? @balanced?
;; third return-map tail, after changing the keyword:
:seon.sci.forgive/balanced? @balanced?
```

Each definition receives:

```clojure
{:seon.error/offending balanced?
 :seon.error/message "Unable to resolve symbol: balanced?"}
```

The correct local is `@balanced`. Changing `::balanced?` to a qualified keyword cannot fix an unresolved local
symbol. Nevertheless, the next replies blame the schema keyword, SCI's namespace, and eventually compiled caller
retention. Call 24 repeats the same local typo (result `ed443bd602b2b`). Call 27 submits this actual body:

```clojure
(defn- scan
  {:malli/schema [:=> [:cat :string :boolean :boolean]
                  [:map [:seon.batch/text :string] [:seon.batch/spans :seon.batch/spans]
                   [:seon.batch/repairs :seon.batch/repairs] [:seon.sci.forgive/balanced? :boolean]]]}
  [^String text reply? repair?]
  ...)
;; "Unable to resolve symbol: ..." — e8a5953f20510
*ns*
;; #namespace[seon.sci.forgive] — e65d879639cd0
```

Even that direct namespace observation does not dislodge the wrong diagnosis. `(my.program/diff)` at calls 23 and
29 returns `:changed []`, with only the scratch `seon.sci.forgive-test/txt` added. `scan`'s branch digest equals
main's: `c1cb13b9190f344a37525ef159b3f96cd05c2be697f0a647e31ef8c3d0b7591c`. No replacement took effect.

The final handoff says “My REPL redefinitions read cleanly (the printed value was correct) but forgive kept the old
compiled scan”. The recorded replies contradict this. The claimed verification must not be accepted as evidence.
The proposed dropped-closer edit also takes `:at` after appending the space, so its repair points beyond the space;
the earlier body recorded the drop twice. These are model implementation errors, independent of SCI.

**Fix:** task/model fit first. A compact error showing the failing source expression, in-scope local `balanced`, and
“definition not admitted; previous definition remains” would help at `seon.sci.eval`/`seon.repl`, but the current
error already identifies the offending symbol. Do not invent automatic typo correction. For the next controlled
comparison, hold the task and tools fixed and compare models only after making one small edit possible. Two runs
cannot estimate general model capability; they can refute the claim that these particular failures were solely
caused by the platform.

### 3. The task and proofs leave a design decision to the implementer

The new proof's source, read by both agents, expects:

```clojure
"a]a"     "a a"     [[0 1 1 1] [2 3 1 3]]
"(f a]b)" "(f a b)" [[0 7 1 1]]
"('"      "()'"     [[0 2 1 1] [2 3 1 3]]
"(\\"     "()\\"    [[0 2 1 1] [2 3 1 3]]
```

Second-run call 3 reads `seon.sci.forgive-test/an-unbalanced-text-names-each-repair` (result `ea6b34e36b825`):

```clojure
:code "(+ 1 2))" [{:seon.batch/line 1 :seon.batch/at 7 :seon.batch/dropped ")"}]
```

Call 8 reads `each-segment-carries-the-repairs-from-its-start-to-the-next` (`e9ab3ded6e4b3`):

```clojure
"(+ 1 2))"          [[[1 ")" nil]]]
"(inc 1))) (inc 2)" [[[1 ")" nil] [1 ")" nil]] []]
```

The research recommends replacing a dropped closer with a space and recording both keys. The old exact repair
assertions require no insertion in these cases. The new examples and old examples are not logically impossible
together: conditional insertion could satisfy them. But that conditional policy is an additional design decision,
not the research's unconditional three-line fix. The agent explicitly notices this and invents “insert only if a
non-whitespace character follows”. This preserves some old expectations by making the algorithm more complicated.
That is precisely the pressure to force the new requirement into the old design that the owner asked us to examine.

**Fix:** a specification/proof change before implementation. Choose the meaning: consistently replace strays with
spaces and convert the old repair assertions, or explicitly specify the narrower token-separation rule with cases
for consecutive strays, existing whitespace and end-of-input. Keep the reverse-repair property. Clarify that a span
may contain invalid reader input (`'` or `\`): the desired result is a visible reader error, not a fabricated valid
form or a silently lost suffix. The model wrongly speculates that a lone quote reads as `(quote nil)`.

The seeded property and reverse-repair model are valuable. Their three discovered defects are not an argument to
remove them. They are an argument that this is scanner design work, not an elementary implementation exercise.

### 4. Natural explanation and executable code share an ambiguous channel

First-run calls 12–14 produce three evaluated `</invoke>` forms, each answered:

```clojure
{:seon.error/offending </invoke>
 :seon.error/message "Unable to resolve symbol: </invoke>"}
```

Second-run call 7 quotes a local implementation while explaining it:

```clojure
(at-code! o line)
;; "Unable to resolve symbol: at-code!" — ee63fbdb7212f
(when (and @form (zero? (.length open))
           (zero? (aset marks 0 (dec (aget marks 0))))) ...complete...)
;; "Unable to resolve symbol: form" — eef13c2265265
```

Call 10 similarly executes the body of `unrepair` without its bindings and gets “Unable to resolve symbol:
inserted”. The prose preceding these forms says “Let me re-read” or “Let me check”, not that the locals are available
at top level. These errors inflate the grade's agent-error count, as E2 H1 already warned.

Conversely, call 4's prose-only reasoning receives `:seon.cluster.reply/no-forms-error`, “Your reply had no form;
only comments/prose. Send a form.” Reconstructed system help says “Send forms; prose goes in ;; comments”. Root's
later reflection message asks for “plain prose, no code”. The task and proof loop still make implementation the
primary objective. The available continuation works the task instead; there is no reflection answer to evaluate.
This is evidence of a control problem, although exact presentation of that incoming message is unavailable at its
historical basis, so it does not prove the model saw and deliberately ignored a full reflection instruction.

**Local fix:** `literal-line?` must not classify closing tool markup as a qualified symbol; the working tree's
`ececefad5` landing addresses that class, after these runs. **Design fix:** provide an explicit executable-code
field or clearly delimited execution request, with explanatory prose carried separately. The same read → evaluate
→ record → print path can consume that value. A reflection/question interaction must allow a prose answer without
the implementation task forcing another code submission. Adding an ever larger list of Markdown/XML exceptions
does not remove the underlying ambiguity.

### 5. The REPL exposes familiar names with surprising effects

**File edit versus branch edit.** First-run call 15's exact effect-bearing tail is:

```clojure
(spit "src/seon/sci/forgive.clj" new-src)
(println "edited:" (not= src new-src))
;; :out "edited: true\n", :value nil — e4939538e691e
(require 'seon.sci.forgive :reload)
;; :value nil — e029dc40176bd
(forgive "a]a" :code)
;; #:seon.batch{:text "aa", :spans [[0 1 1 1] [1 2 1 2]],
;;              :repairs [#:seon.batch{:line 1 :at 1 :dropped "]"}]}
```

`new-src` is the held file text with the stray-closer branch string replaced; the history contains both strings.
The live reply proves the file write reported success while behavior remained unchanged. The archive destination
and root restoration are corroborated by plan §0, not a write performed by this reviewer. The agent's choice is
wrong for branch programming, but understandable in an environment where `slurp`, `spit` and `require :reload`
look familiar and all return success. Read-only archives prevent corruption; they do not supply the missing edit.

**Tests.** Second-run call 9:

```clojure
(clojure.test/test-vars [#'seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart])
;; "Unable to resolve var: seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart"
;; result e134811122cd6
```

The source is available but the test Var is not available in this context. The proof list should offer its actual
`my.test/check` request. Teaching “ordinary Clojure at a REPL” without exposing this distinction creates predictable
wrong calls. It does not follow that the automatic test step is broken: the recorded proofs remain correctly red,
and no accepted `scan` candidate reaches a successful behavioral comparison.

**Arguments and error ownership.** First-run `(my.program/callers seon.sci.forgive/scan)` passes a function where
the owner requires a map containing `:seon.program/subject`; the contract correctly refuses it, but its generated
example includes a database object and random symbol `tV`. Second-run call 28 guesses an identity attribute:

```clojure
(my.program/write-branch!
  {:seon.fn/root "/Users/sean/src/seon"
   :seon.program/identities #{[:seon.program/sym 'seon.sci.forgive/scan]}})
;; seon.cluster.source/held-digests refused its result ... expected a digest, got a map
;; nested cause: "seon.db/q cannot read uninstalled attribute :seon.program/sym."
;; result e4a50ea0dd471
```

The guessed attribute is a model error; propagating a read refusal into a digest result is a producer error.
**Fix locally:** `seon.cluster.source/held-digests` propagates/refuses that read at its owner; public operation
examples use an actual subject and supplied custody. Return the precise runnable repair form, not random schema
examples. Make the branch-definition state and file-write state explicit at the REPL edge. These fixes require
neither abandoning Datahike nor another programming interface.

### 6. The platform boundary is real, but these runs did not test a successful crossing

Live branch reads show both `seon.sci.forgive/scan` and `seon.sci.forgive/forgive` have
`:seon.fn/host-bound? false`. **Compiled today is not the same as non-interpretable.** The installed
`seon.sci.eval/interpret-affected!` promotes affected interpretable callers. `seon.turn/platform-proof` uses the
host-bound fact to offer `my.test/check-platform`; it is not a general statement that every reader change has had
compiled end-to-end acceptance.

There are two distinct questions: does the candidate transform the input correctly on its branch, and does the
compiled reader installed at the entrance exhibit the same behavior? SCI can help answer the first. A branch
definition deliberately cannot replace the shared compiled entrance that reads its next reply. A compiled candidate
proof is needed for the second. That is a useful isolation boundary, not a defect to defeat by changing default's
Vars. The platform card and existing `my.test/check-platform` already acknowledge the need for another execution
engine under the same evidence interface.

The earlier designed-task research measured the same scanner at 6.4 KB allocation per character interpreted versus
23 bytes compiled, about 280×. That is prior measured evidence, not a measurement repeated here. These runs never
admitted a modified scanner, so their failures cannot be attributed to its interpreted execution cost or a test
timeout. Comparing interpreted cost to compiled cost would still be invalid for a successful candidate.

**Design recommendation:** retain cheap branches and SCI for ordinary functions and small behavioral probes. For
this reader task, use a source edit plus an isolated compiled candidate through the existing test/evidence owners;
show pending compiled proof explicitly. Do not require a cheap model to reconstruct an interpreter-sensitive,
178-line platform definition as the entrance qualification test for the product. “One REPL path” should mean one
recording and evidence contract, with evaluator stages as values, as AGENTS.md itself permits. It should not mean
one engine or whole-definition text generation for every kind of change.

### 7. Observation can mislead the reviewer as well as the agent

This exact read of the first model basis fails (request construction is in the appendix):

```clojure
(seon.context/model-context
  (seon.db/as-of branch-database 536871046) request "chain2-forgive-2")
;; :seon.instrument/fn seon.batch/sample
;; :seon.instrument/check :output
;; "seon.batch/sample refused its result at [1]: expected :seon.fn.cost/sample
;;  (an integer at least 1), got nil. ... Called from seon.context/branch-cost ..."
```

The same class recurs at basis 536871112. The sample function admits a sample once its digest is present, then reads
all sample members; the cost attributes are individually `:seon.db/no-history? true`. This is consistent with an
incomplete historical tuple, not proof of every possible cause. More fundamentally, reconstructing with today's
renderer and current task home is not reproducing an old provider request. Replaying a basis is insufficient when
some inputs are mutable, unhistoried, or supplied from a different head.

**Local fix:** an incomplete historical cost observation becomes an explicit unavailable observation without
preventing the task context from rendering. **Design choice:** retain the exact bounded prompt units sent, or retain
every dependency needed to reproduce them, including renderer identity, effective settings and task-home basis.
Do not claim exact replay until a round-trip check proves it. This does not require storing full program dumps.

The grade's error categories are also not causal categories. E2 counted prose-reader failures and contracted-defn
to deftest admission failures as agent errors. Here the wrong identity is agent-authored, but the downstream
`held-digests` contract failure belongs to Seon. A repeated signature is a useful triage signal, not proof by itself
that a shared model mistake is a platform defect. Inspect the producer and offending form.

## What to change first, and the better first task

The shortest path is not a larger context framework or a better instructed scanner rewrite. First make the current
task and latest actionable failure readable; allow a small edit to a declaration; make question versus execution
intent explicit. Then run a task whose implementation and complete local context fit comfortably in one reply.

For a first **chain acceptance**, use a bounded extension of the existing ordinary `comp.text/slug` fixture:
**`comp.text/slug-removes-edge-hyphens`** (proposed, not ingested or claimed green). Define the policy before launch:
trim whitespace, lowercase, turn each whitespace run into one hyphen, remove leading/trailing hyphens, retain
interior punctuation. Example proof rows: `"  --Hello   WORLD--  " → "hello-world"`, `"---" → ""`,
`" a-b " → "a-b"`, and `"" → ""`. Keep its string-to-string contract. Add a small idempotence property only if
the designer verifies that law for this policy. The existing bench fixture handles case/whitespace but preserves
edge hyphens, so this is a real behavioral change to a small ordinary definition, not an empty green assignment.
Confirm red on the installed fixture before launch; this review did not install or run it.

That pilot tests the desired chain mechanics: specification, red proof, source read, one small redefinition,
automatic proof, write-back and reviewed merge. It does not claim to validate platform self-modification. After
it passes, take a small real product owner with the same properties, then this scanner as a separate platform
acceptance. Do not generalize success on `slug` to `scan`, as E1/E2's small tasks were implicitly generalized here.

E1 supports typed names: T2 improves from 1/5 to 4/5 green and T3 from 2/5 to 5/5. E2 supports a modest chat-format
preference, not a cure: 35/40 versus 36/40 green, 122 calls each, and most of the error-count difference comes from
one prose-parsing outlier. Keep typed tasks and native chat. Neither result establishes competence at scanner
semantics, interpreted platform proof, or recovery from contradictory instructions.

The five chain stages are useful responsibilities. Making five separate agents mandatory even for this small
pilot has not been justified by these data. For a local change, a single design/proof handoff may be sufficient;
measure whether separate stages improve the resulting interface rather than treating their count as an invariant.
The handoff must settle repair policy and execution route, not just attach names to the same unresolved question.

## Assessment of DYNAMIC-CONTEXT

The card landed during this review and was read end to end at `380290bba`. Its strongest changes are showing the
current namespace, showing the current declaration once, and notifying root when work ends. Those address observed
repetition and silence. Its estimated prompt reductions are estimates, not measured improvements in task success.
The second run already had whole source and still failed; deduplicating that source does not correct the local-name
mistake, settle the repair policy, or provide a small edit operation.

**Correct the causal account before implementing from it.** The card attributes all five failed definitions to
“the source's `::balanced?` mangled in transit”. The stored forms show four definitions with the model-authored
`@balanced?` typo and a fifth with a literal `...` body. No transit corruption is established. Its “250-line” count
also differs from the preserved 178-line definition. It names `:seon.fn/definition-digest`, but the installed
function schema declares `:seon.program/definition-digest`. These are evidence and schema corrections, not grounds
to reject the useful context work.

**Preserve failed attempts as failed attempts.** A current-definition working set answers “what is installed”. It
does not answer “what did I just try, and where did it fail”. In this run, replacing the submitted definition with
its head and displaying the unchanged installed body could hide the exact `@balanced?` that needs correction.
Keep the latest rejected source, or its precise offending span with retrieval, beside its diagnostic; only
collapse successfully admitted obsolete bodies. A failed-definition case belongs in the card's proofs, alongside
its successful redefinition case. Use the admitted declaration identity, not merely a nonempty set of executed
dependencies, to decide that a definition changed.

**A union of everything ever read is not necessarily the right current context.** It is smaller than replaying
duplicates, but grows with exploration and may retain irrelevant source. Show task references, current edits and
unresolved failures first; keep earlier reads discoverable. Verify the complete task and counterexample survive
the working-set render. Do not declare history limits moot solely because repeated source disappears: ordinary
values, submitted forms and unique reads still grow. Measure both selection cost and successful task completion.

**An automatically returned final context is a status answer, not the requested reflection.** It can show what
the agent did; it cannot answer why the model chose those actions. Closing implementation admission is sensible.
If root asks for a reflection, explicitly report that no model reflection was obtained or use the card's proposed
bounded answer-only call under a separate request intent. Do not present a status rendering as the agent's answer
to an arbitrary question. The card correctly leaves answer-only execution for follow-up work; that means it does
not yet resolve the reflection failure observed here. One task per agent may be an owner policy, but these runs
show reopening the same task, not a second assignment: do not claim that restriction fixes their causal problem.

The proposed probing help line is useful only if it says what the lexical restriction actually is: locals require
their bindings. “A function's body is one form” is inaccurate for a multi-form Clojure body, and a quoted example
can still be misclassified as executable input. A help line complements an unambiguous execution channel; it does
not replace one. Whole definitions can remain the evaluator's unit while a local source edit is the model's unit.

## Read-only reproduction and evidence addresses

The first branch has no open process connection; it is not missing from storage:

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))]
  (mapv (fn [branch] [branch (some? (seon.db/branch-connection main branch))])
        [:agent-e84b11b8bd78 :agent-4e349b905cf7]))
;; [[:agent-e84b11b8bd78 false] [:agent-4e349b905cf7 true]]
```

Read both without opening or reviving an agent:

```clojure
(require 'seon.competency)
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      database (seon.db/branch-database main :agent-e84b11b8bd78)]
  (seon.print/full (@#'seon.context/exchanges database "chain2-forgive-1")))
;; substitute :agent-4e349b905cf7 and "chain2-forgive-2" for the second
```

`seon.competency/model-calls` provides ordered call entities and attempt identities. Pull each attempt's
`:seon.ai.attempt/basis-t` and each call's `:seon.turn/reply` (or read its `:seon.turn/reply-blob` through
`seon.blob/get` with default's explicit connection). `model-evaluations` provides evaluated sources; `exchanges`
additionally preserves read errors and the actual shown replies. Result handles cited above are evidence addresses,
not a claim that live result objects survive restart.

Prompt reconstruction attempted through the actual owner:

```clojure
(let [instance (@#'seon.cluster.boot/selected-instance "default")
      cluster (:seon.turn.loop/cluster instance)
      main (seon.db/db (seon.cluster.boot/connection "default"))
      head (seon.db/branch-database main :agent-4e349b905cf7)
      database (seon.db/as-of head 536871046)
      request {:seon.boot/cluster-name "default"
               :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
               :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)}]
  (seon.print/full
   (dissoc (seon.context/model-context database request "chain2-forgive-2")
           :seon.context/exchanges)))
```

On `head` it succeeds and shows the cut prompt quoted above: 303 ms evaluation, 368.2 MB measured evaluation
allocation; complete request 376.2 ms and 396.5 MB entrance allocation. Historical attempts fail in 188–302 ms;
the second reports 535.5 MB evaluation allocation. These costs are read/assembly findings, not model thinking time.
The whole-exchange reads took 89 and 110 ms at the form, 169 and 196.3 ms per request. The grade read took 218 ms,
353 MB evaluation allocation. Shared JVM-window allocation includes unrelated work and is not assigned to these
forms. No performance improvement was implemented or claimed.

Dependency seams inspected at review HEAD: SCI `41194667cc6b4d52d0d723d66d754d064f9c0d5f`,
Datahike `cb3bce514507ecc96d36b6957ad10f6e892f51cf`, rewrite-clj
`60782e501aaf312cb90c9ff0bee05d5da5125563`, Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`.
`sci.core/fork` owns interpreted context isolation; it does not promise compiled JVM Var replacement.
`datahike.versioning/branch-as-db` and `commit-as-db`, through `seon.db/branch-database`, own the read-only branch
snapshot. `rewrite-clj.zip/replace` owns structural source replacement; Seon must still own admission and evidence.
`clojure.main/repl` takes read/eval/print/caught functions, supporting the distinction between one interface and one
evaluation engine. No new dependency is proposed.

## Findings for root and landing verification

This assignment authorizes only this report; it does not ingest tasks or edit the issue index. Existing homes are
`seon.sci.forgive/forgiving-forgiven-text-changes-nothing`,
`seon.sci.forgive/tool-markup-in-a-reply-is-prose`, the source-whole/archive-readonly work named in plan §0, and
the E1/E2 test-visibility and prose-reader findings. Root should attach the evidence here to those homes.
Additional bounded findings needing an owner are: incomplete historical cost samples break prompt replay
(`seon.batch/sample`, `seon.context/branch-cost`); `seon.cluster.source/held-digests` passes an invalid-read map as
a digest; context rendering cuts the task and runnable operations; reflection requests remain implementation work.
The replay guarantee needs a design decision covering both no-history costs and current task-home reads.

The REPL/datahike skills still describe retired system-turn behavior and contain hand-written source line claims;
those were treated as guidance to inspect, not evidence of installed behavior. No skill or code was edited.
Reviewer mistakes (two guessed pull attributes and an initially incomplete render request) were corrected; their
refusals are not counted as defects in these chain runs.

Validation: `clj-kondo --lint src script test --cache false --parallel` completed initially in 5,597 ms with
0 errors and 1,465 warnings, and after the foreign tree changed in 5,599 ms with **0 errors and 1,468 warnings**
(exit 2 for warnings). This is not a claim of zero findings. The report passed the whitespace check. Only this Markdown report is in the
slice; source/test change is 0 lines and no executable test reaches it. The requested documentation-only
`bin/lane commit` path does not publish or boot a JVM. Default and foreign work were left alone. During the review,
foreign edits included the operator/archive and reply-markup lanes, then `src/seon/sci/eval.clj`,
`src/seon/sci/lookup.clj`, `test/seon/sci/library_aliases_test.clj` and their task resource. At final inspection,
other lanes were changing cluster/message/problem owners, their schemas and tests, and `config/default.edn`.
Those moving paths are outside this report's verification boundary. DYNAMIC-CONTEXT was available at final
inspection and is assessed above. No other lane's files were edited or staged.
