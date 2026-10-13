---
title: "Independent simplicity review of the lifecycle hybrid"
type: research
date: 2026-10-13
basis: "hybrid-lifecycle.md at 69f9c4ef9; default archive 371a754df; JVM-mode reads and pure derivations only"
status: "changes needed before implementation cards"
---

# Simplicity review

The useful core is **one merge decision, ordinary task declarations, and ordinary tests over explicit task inputs**.
Keep it. The hybrid does not yet deliver its stronger claim that these ten rules completely control lifecycle or that
validating `end-task-call` makes partial completion impossible. There are existing successful-completion paths outside
that function, and the proposed chain constructor and proof selection do not compose with their current owners.

Read: `CLAUDE.md`, [hybrid](hybrid-lifecycle.md), both [Opus](plan-lifecycle-opus.md) and
[Astra](plan-lifecycle-astra.md) inputs, [narrow check](narrow-lifecycle.md), and [dig](dig-step-lifecycle.md).
This is a review of revision **69f9c4ef9**, not a claim about an implementation of its proposed symbols.
Findings below distinguish live reproductions, source traces, and contradictions within the proposal.

## Ranked findings

### 1. High: successful completion has an owner outside the proposed exit validator

**Evidence.** `seon.task/ready-rules` calls a leaf done from an assignment's
`:seon.task/outcome :merged`; it does not require `:seon.task.attempt/end :done`.
`my.root/merge!` accepts an open assignment, including one with no end. It calls `seon.merge/merge!` →
`seon.merge/merge-onto` → `seon.merge/merge-tx`. The latter writes `:merged` for any source end other than
`:sent-back`, **including absence**. No call to `seon.turn/end-task-call` is on that path.
The facts-only arm of `merge-onto` also bypasses the source test gate; A5 recognizes the no-op test-policy issue,
but neither A5 nor C3 converts the independent successful-outcome writer to exit-contract validation.

Live reproduction P1 below reads Run 24's actual stored **pre-end** commit
`6acd908c-fb9b-5564-8ad6-3f2a307516f5`. Its attempt end is `nil`. Calling the real `merge-tx` as a pure derivation
returns `[:db/add 559601 :seon.task/outcome :merged]` (49 ms; request 160.7 ms, 8.3 MB evaluation/request allocation).
Nothing was transacted from that result. This demonstrates the bypassing decision, **not** a claim that Run 24's
bad file would pass the outer merge gate. The existing
`seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion` exercises the public
facts-merge path directly; successful completion is not exclusively an automatic end reaction today.

**Smaller change.** Make the merge's accepted candidate the authority for successful task completion, including its
effective task and exit schema. Both automatic and root-requested merges use that decision; readiness reads its one
successful outcome. A branch's earlier observation is a proposal, not a second durable assertion that success already
happened. If the existing branch `:done` end must remain, narrow its meaning explicitly and let the same exit-checking
function judge the actual merged task before `merge-tx` writes `:merged`. Do not add a root-only exemption or another
independent completion predicate. C3's proof must enter through both automatic completion and `my.root/merge!`, including
a task-only change, rather than only calling `end-task-call`.

Cancellation, cap, stall, send-back and execution errors legitimately end attempts without successful exit contracts.
The guarantee should be **no successful handoff with invalid data**, not “no step ever ends without passing its exit.”
Filesystem publication remains a separate effect: `publication-due?` stops deriving publication after `publish!` records
a refusal, whereas `leaf-done` already sees the merge. A task-map validator cannot make that effect atomic with a database
commit. Keep the recorded publication outcome visible and describe that boundary honestly.

### 2. High: the template proofs are selected by one reader and run by another

**Evidence.** C5 extends `seon.task/obligations` with a template's tagged tests. However:

- `seon.turn/task-proofs` reads **`seon.task/task-edges`**, not `obligations`.
- `seon.turn/step-tests` starts with that result. Although it also calls `obligations`, it uses only its kind and
  product, adding product tests only for `:prove`; it never takes `:seon.task/proofs` from that result.
- `run-proofs`, `step-due?`, `test-step`'s `proofs?`, and `first-proof-run-t` consume these selectors.
  With no directly tagged proof, `first-proof-run-t` treats the attempt transaction itself as “proved first.”
- `seon.context/proofs` independently reads `task-edges`, so the displayed proof list also omits the template's proof.

This is a source-reproduced caller mismatch, found with `rg -n 'task-proofs|task-edges|task/obligations'` and reading
the named functions. The proposed template machinery is not installed, so no fictitious runtime template test is claimed.
C5 changes `obligations` alone; A3 changes verdict recording, not these selectors. A fresh Survey with only its template
proof therefore has no specified path that actually runs that proof before judgment.

There is a second mismatch in the same owner: §2.3 says Implement inherits the subject's existing behavior proofs,
and §2.2 puts `product` on the subject. Installed `obligations` queries tags on the **step symbol**, pulls the step's
own product, and queries immediate successor tags. It does not read the effective subject or inherit its proofs.
C5's stated addition of template proofs does not implement that promise.

**Smaller change.** Make `obligations` the sole proof-selection function, with explicit subject/step/template inputs.
Use its result for running, staleness, first-proof detection, presentation and merge. Delete `task-proofs`' separate
selection and the duplicate proof query in `context/proofs`; do not introduce a second template runner. Keep C4's
explicit task input: it is necessary. Extend its proof through an actual first attempt with only a template proof,
then through Implement inheriting a subject proof. The expected behavior is one selected set everywhere.

### 3. High: `chain!` as five `create!` calls produces held children; create the whole value once

**Evidence.** C2 proposes `create!` in a loop, approximately five transactions, followed by linking children to the
subject. `seon.task/task-entity` initially supplies `:held`; `seon.task/task-delta` removes execution from a **new**
child only if the same proposed/stored population already contains its parent edge. Updating an existing declaration
does not retract execution: it is not a declaration member. `seon.task/execution` honors the child's explicit value
before its parent's.

Live P2: create the child declaration first, then derive the transaction attaching it to a parent. The child is
`:held`; the attachment transaction contains only the parent and edge, **no child execution retraction** (4 ms).
Live P3: pass the same parent and child declarations together to the same `task-delta`. Result: parent `:held`, child
with **no execution key** (1 ms). No writes. The existing
`seon.task-test/a-running-subject-readies-its-first-step-only` explicitly asserts that a child's own `:held` blocks it.

**Smaller change.** `chain!` constructs all clone declarations and the subject's children/needs, then submits **one**
population to the existing task writer (`adopt-tasks-tx` / `task-delta`, identities from the existing owner).
The owner already knows how to order lookup refs (`refs-last`) and validate the final tree. This also removes intermediate
half-created chains, five round trips, and any temptation to repair held children afterward. C2's red proof should use
one `:running` fact after construction and observe exactly the first child ready, as well as the existing round trip.

### 4. Medium: R1–R10 are a useful summary, not an exhaustive mechanical rule set

The following decisions survive every named deletion. These are source traces, not hypothetical extra failure modes.

| Rule / concern | Actual surviving control owner | Consequence / smaller correction |
|---|---|---|
| R1 / R10 | Proposed `chain!` is an explicit command; retrospective editing is work somebody must perform | Do not describe a template read as the reaction that edits it. Keep a root task; no new retrospective scheduler. |
| R2 / R3 | `seon.task/ready` leaves open attempts eligible; `seon.cluster.agent/plan-facts` splits them using `basis-valid?`; **`plan`**, not `next-actions`, creates `:attempt` and `:start-flow` | Correct the owner and state the admission inputs. `next-actions` has no `:attempt` arm. |
| Start / pause / stop | `plan` → `follow` chooses pause/resume/stop/cancel and applies `max-running`; `task/assign!` is also a direct assignment entrance | Keep one shared derivation of task execution and one assignment owner. Name both entrances; do not hide the policy in an effect function. |
| R4 | `turn/step` additionally requires no report refusal and `:seon.config.test/step?` not false; `prove-turn` is a separate first-proof entrance | Both entrances must acquire the same observation. The proposed trigger description alone is incomplete. |
| Model work / end | `next-agent-work`, `proved-unanswered?`, `opening-deferred?`, `continuing-reply?`, `turns-left`, `stalled?`, `task-end` | These still decide whether work opens or an attempt ends. They are not deleted by replacing `unmet`. Keep their datum dependencies explicit. |
| Process-local stop | `turn/step`'s `:seon.turn.loop/parked` and write-refusal count suppress wakes; `cluster.agent/fenced?` reads a closed mailbox retained in routing | Lifecycle is not exclusively database data today. The hybrid neither converts nor deletes these paths. Separate process-resource state from task decisions; do not claim the full conversion is done. |
| R7 | `assignment-actions`, `close!`, `failed-pass!`, `refused-merge-relaunch`, `relaunch-reason` | `next-actions` does not decide relaunch. Execution failure writes `:error` in the catch, while cap/stall relaunch is decided in `close!`. Have one pure end→action derivation read the end, work and dial; let effect handlers apply it. |
| Taskless agents | `taskless-agents`, `taskless-actions`, `agents-to-start`, and the final part of `run-pass!` | Schedule owners can start without a task assignment. “Everything is an agent with a task” remains a target unless these entrances receive tasks. Do not silently leave a parallel lifecycle. |
| R8 / R9 | `publication-due?`, `merge/publish!`, `task/ready-rules`, `task/done?` | Publication has waiting/refused outcomes; merge readiness is earlier. Name this separation rather than promising atomic publication. |

Reproduction search: `rg -n 'attempt|relaunch|publication-due|taskless|basis-valid|parked|fenced\?|task-end|next-agent-work'`
over the six review namespaces; then follow `run-pass!` → `plan-facts` / `plan` / `next-actions` / `apply-plan!`.
The table is about **decisions**, not a demand to inline all helpers into ten large functions. A mechanism can have
small acquisition and effect helpers while retaining one policy owner. The simpler design names those owners and
deletes duplicate policy; counting table rows cannot establish that.

### 5. Medium: the proposed task data has two incompatible producer contracts

**References.** Survey is told to write/rank schemas, keywords, functions, tests and docs in `:seon.task/references`.
That key is already a derived set of `:seon.program/edge-symbol` (qualified symbols), not a ranked heterogeneous list.
`seon.task/with-references` unconditionally replaces it from intent/instructions. Live P4 supplies
`#{seon.merge/merge!}` with intent citing `seon.turn/task-end`; the returned references contain **only**
`seon.turn/task-end` (part of the 5 ms census request). C6's wider input map will not change that producer.
The proposal itself acknowledges the derived key in §8 Q10 but still authors it in §2.1.

**Vectors.** `seon.task/declaration` converts **every sequential member to a set**, because its current collection
members are sets. Live P5 uses a local, unregistered Malli declaration schema containing exactly the proposed
goal/non-goal vector shapes. Before normalization it validates; after calling the real `declaration`, goals are a set
and non-goals `#{}`, and it fails that schema (1 ms). This is not a persistence proof; it isolates the incompatible
normalizer without changing the platform registry. It is enough to refute C1 as schema EDN alone.

**Smaller change.** Keep references derived by the existing resolver from the authored task content; Survey adds its
findings and cited evidence to that content. Do not give one key two authorities or build another reference resolver.
If ranked evidence is actually consumed, give that ordered authored value its own meaning; do not overload the set.
Make declaration normalization consult the declared schema once, converting pulled cardinality-many values only for
set members. Preserve actual vectors. Prove refine → declaration → write → pull → task-file round trip for the new
shapes in the same slice, rather than adding converters at every caller.

Also resolve “first example” versus `examples` as a set: no listed consumer needs firstness. Delete that wording
unless ordering has a demonstrated consumer; no ordinal attribute is necessary just to satisfy prose.

### 6. Medium: cloning freezes instructions but still follows live template proof membership

**Document reproduction.** §2.2 copies only step/instructions/exit and calls the template ref “provenance”; §4 makes
`obligations` follow that ref to the template's tagged tests on every read. Changing a template's proof tags therefore
changes the selected proofs of an existing clone, although §2.2 promises template edits affect only the next chain.
Likewise untagging a clone cannot remove a proof it inherits by a template lookup, despite the customization recipe.
This is a contradiction in the specified lookup paths, not a claim to have run a nonexistent template implementation.

**Smaller change.** Copy the selected proof-symbol set at the same time as the instructions and exit. The existing
`:seon.task/proofs` schema can describe it; declare its authored role on the clone. `template` then really is provenance,
and `obligations` need not chase a live template for policy. A later change to a test's implementation still follows
ordinary program identity/reach rules; this freezes selection, not the entire program. One ordinary set is cheaper
than a template version registry or a new live-composition mechanism. C2's “template edit changes no clone” proof must
include a proof-membership edit and root removing an inherited proof.

### 7. Medium: A3 and A4 are one behavioral cut, not independent loadable completion changes

**Source reproduction.** A3 replaces `record-broken!` with the new refusal recording. Until A4, `context/step-observation`
still pulls `:seon.agent/broken` / `:seon.agent/new-red`, and `step-status` still judges those old facts. The source writer
and reader are directly connected. Merely leaving their old schema members installed does not keep those facts current.
The phase's proposed proof only asserts the new fact exists, not that the still-installed completion reader uses it.

**Smaller change.** Commit new observation production, its consumers, and old-policy deletion as one behavioral slice.
Extraction A1/A2 may precede it with unchanged semantics; B remains independent. Do not dual-write two verdicts just
to preserve an artificial phase boundary. Add the end-to-end completion/merge disagreement case to that slice's proof.

## Machinery worth retaining, and machinery not justified yet

Keep the owner's five named stages, ordinary task refs, cloned instruction text, exit-schema names, and C4's explicit
test input with task content in reuse identity. These have distinct consumers. A new enum member per stage need not
mean a new lifecycle `case`; Prove's designed-red product is the existing semantic exception that must remain explicit.

Do not reserve a persisted `expected-cost` attribute while §3a says its shape is undecided and nothing reads it.
Leave it as a design placeholder until the producer and measured-cost proof consume it together. The same caution
applies to counting `problem`, `goals` and `non-goals` as proof of a good design: Malli proves their shapes, not their
substance. Keep the shape proof and the actual behavior proof distinct.

Design's universal stub rule is heavier than the stated Run 26 input needs: §8 Q9 explicitly asks for a defect with
“no interface work.” Existing `seon.edit/single-form` and its callers are real implementations, not missing symbols.
The smaller Design outcome can name and validate an **existing** interface; only genuinely new declarations need
unimplemented bodies. This was already expressible in the Astra input's Design row. Do not replace an existing body
with a throwing stub just to manufacture a stage transition, or require a generator for every opaque host schema when
the supplied acceptance samples already exercise the contract. This is a simplification of the proposed work, not a
new runtime refusal class.

## Expected cost: plausible static work, incomplete verdict and phase budgets

Live P6 repeats `replacement` → checked `db/with` → `proven-files` → contracts/dangling against Run 24, two identities:

| Call | Wall ms | Thread allocation MB (decimal) | Result |
|---|---:|---:|---|
| First | 188.87 | 174.86 | `:not-equivalent`, `src/seon/edit.clj` |
| Second | 107.77 | 123.17 | same |
| Third | 88.65 | 123.16 | same |

The enclosing request was 444.6 ms / 427.6 MB. These numbers support a **hundreds-of-ms static acquisition**, not a
152–213 ms complete verdict. This probe excludes branch comparison, lint after a passing file proof, test execution,
baseline red acquisition, recording the verdict, and durable merge/publication. `accept-merge!` actually calls
`seon.test/fork-point-red` for reds, so the “test half over recorded results” is not always only a vector scan.
The narrow check's 7/39 count is a floor, as that document says; stale proofs and main-head movement add triggers.

| Phase | Assessment and smaller cost model |
|---|---|
| A1–A2 | Extraction is plausible; ~70 added lines is an estimate, not evidence. Keep the candidate and evidence inputs explicit. Branch-test evidence and tests on merged S are different inputs even when the predicate is shared; D2 deliberately retains the second gate. |
| A3–A4 | Charge acquisition **plus** any baseline tests and the fact transaction once per changed input. Context saving is plausible. One behavioral cut avoids temporary dual judgments. A whole-head `judged-against` comparison can retrigger after unrelated recorded runs; measure this frequency before calling the total ~1.4 s. |
| A5 | 135 ms was a **refused** acceptance path, not a measured successful no-op merge. A successful no-op still proves obligations and closes/records assignment facts. No supported end-to-end estimate yet. |
| A6 | Zero model calls does not mean zero work: fork/seed/start, prove, verdict, end and merge still run. Count and measure the whole relaunch, not just `prove-turn`. |
| B1–B2 | A single derivation replacing append is appropriately small; cost follows the namespace's require facts/source and one pull. The history-order regression is the right proof. |
| C1 / C6 | More than EDN/input widening: F5 requires declaration, storage and reference-owner compatibility. Measure the complete refinement and round trip; the stated line counts omit this work. |
| C2 | Replace five ~100 ms writes with one transaction proportional to the five declarations and refs (F3). No live durable-write benchmark was run here. |
| C3 | The validation CPU estimate is plausible. A compiled **existing** task declaration validator ran 1,001 validations in 0.14 ms / 96,120 allocated bytes. This is neither the proposed exit schema nor its entire end transaction. Include effective-task acquisition, registry acquisition and the writer in the end-to-end number. |
| C4–C5 | Binding the task and making evidence task-specific is real runner work. F2 adds the missing selectors; five behavioral helpers are not credibly priced by “~30 code” without their bodies. Survey re-evaluation and Design generation have costs determined by those actual inputs, not by validating a small map. |
| C7 | A real full chain includes five merge gates and publications, plus model/test work. It is not covered by “the rest sub-100 ms.” Record per-stage costs as planned. |

The six named namespaces do total **12,684 lines**. That is not the amount of lifecycle control: `turn` also owns the
evaluation pipeline, and `source` indexing/publication. A line census alone cannot justify either a large rewrite or
a claimed ~35-line net simplification. The useful budget is the end-to-end path and its repeated work.

## Reproduction forms and verification

All live forms used MCP `eval_clj` with `mode=jvm`, `agent=root`, `cluster=default`, explicit connection
`(seon.cluster.boot/connection "default")`. No root SCI, provider call, restart, reset, branch creation or mutation of
another lane's work. JVM requests record their own ordinary observations; “no writes” above means no proposed task,
merge or program transaction was committed. Materialized branch/commit values were released in `finally`.

P1, successful-outcome producer (no transaction):

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      branch (seon.db/branch-database main :attempt-8667f9088300)
      row (seon.db/pull branch [:seon.task.attempt/commit]
                        [:seon.task/assignment "8667f9088300"])
      before-end (seon.db/commit-database main (:seon.task.attempt/commit row))]
  (try
    (let [a (seon.db/pull main [:db/id :seon.task/merge-base]
                          [:seon.task/assignment "8667f9088300"])
          tx (seon.merge/merge-tx main (:db/id a) (seon.db/commit-id before-end)
                                (seon.db/carried-projection main) (:seon.task/merge-base a))]
      {:source-end (seon.db/pull before-end [:seon.task.attempt/end]
                                 [:seon.task/assignment "8667f9088300"])
       :outcome-ops (filterv #(and (vector? %) (= :seon.task/outcome (nth % 2 nil))) tx)})
    (finally (seon.cluster.source/release! [branch before-end]))))
```

P2/P3, single-child then parent versus one population (pure writer derivation):

```clojure
(let [p (seon.db/carried-projection (seon.db/db (seon.cluster.boot/connection "default")))
      path "resources/seon/tasks/lifecycle-review.edn"
      child (first (seon.task/file-declarations p path
                     "[#:seon.task{:name \"child\" :intent \"Child.\"}]"))
      first-tx (#'seon.task/task-delta p {} {(:seon.task/id child) child} {})
      stored (assoc (first first-tx) :db/id 123456)
      parent (first (seon.task/file-declarations p path
                      "[#:seon.task{:name \"parent\" :intent \"Parent.\" :children #{[:seon.task/sym lifecycle-review/child]}}]"))
      both {(:seon.task/id child) child (:seon.task/id parent) parent}]
  {:first-child-execution (:seon.task/execution stored)
   :attach (#'seon.task/task-delta p {(:seon.task/id child) stored} both
              {(:seon.task/id child) (seon.task/declaration p stored)})
   :together (#'seon.task/task-delta p {} both {})})
```

P4, existing reference producer:

```clojure
(#'seon.task/with-references
 {:seon.task/intent "Inspect `seon.turn/task-end`."
  :seon.task/references #{'seon.merge/merge!}})
;; references => #{seon.turn/task-end}
```

P5, proposed vector shapes passed through the actual declaration normalizer; local schemas only:

```clojure
(let [p (seon.db/carried-projection (seon.db/db (seon.cluster.boot/connection "default")))
      s (malli.core/schema
         [:map [:seon.task/ns :symbol] [:seon.task/name :string]
          [:seon.task/goals [:vector :string]] [:seon.task/non-goals [:vector :string]]]
         {:registry (:seon.schema.projection/registry p)})
      local (-> p (dissoc :seon.schema.projection/compiled)
                (assoc :seon.schema.projection/registry
                       (malli.registry/composite-registry {:seon.task/declaration s}
                         (:seon.schema.projection/registry p))))
      v {:seon.task/ns 'lifecycle-review :seon.task/name "subject"
         :seon.task/goals ["First" "Second"] :seon.task/non-goals []}
      out (seon.task/declaration local v)]
  {:before-valid ((malli.core/validator s) v)
   :after out :after-valid ((malli.core/validator s) out)})
```

P6, cost and known file refusal; hold `main`, `branch` as in P1, release afterward, repeat this measured call three times:

```clojure
(let [ids #{[:seon.fn/sym 'seon.edit/single-form] [:seon.ns/name 'seon.edit]}]
  (seon.profile/measure
   (fn []
     (let [tx (#'seon.cluster.source/replacement branch main ids)
           candidate (seon.db/with main {:tx-data tx})
           proof (#'seon.cluster.source/proven-files main candidate ids "/Users/sean/src/seon")]
       {:file-proof (select-keys proof [:seon.error/type :seon.source/write-back-refused :seon.source/path])
        :gaps (count (seon.cluster.source/contract-gaps candidate ids))
        :dangling (seon.task/candidate-dangling candidate main ids)}))))
```

Validator measurement: acquire `(seon.schema/projection-validator p :seon.task/declaration)` once and
`(seon.task/task-declaration main "6569d0dc9a1e")` once; measure `(fn [] (dotimes [_ 1000] (v value)) (v value))`.
Malli `dd13ff81`, `malli.core/validator`, caches on the compiled schema; `malli.registry/composite-registry` composes
the local probe registry without changing global state. Datahike `c9e57c6f`; `seon.db/with` uses `prepared-write` and
the final-report validation path. Neither seam is a new lifecycle mechanism.

**Verification limits and foreign boundary.** Both status entrances reported default `:faulted`: one open fault,
11 errored receipts, two agent-error signatures, no missing boot layers. Default ran archive `371a754df`; `git diff`
from that commit to `69f9c4ef9` over the six reviewed source files was empty, so those owner bodies match the live
probes. The untracked dig was an input, not edited. Other dirty/untracked paths and the http-kit submodule were left
alone. The two malformed preliminary probes (symbol instead of the installed string task name, then a local
declaration schema missing ns/name) were corrected; their refusals are not product findings.

Full `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,645 warnings, 7,568 ms**.
No source/test changes and no new tests. The requested commit gate is named narrowly with
`seon.task-test/a-running-subject-readies-its-first-step-only` and
`seon.task-test/a-steps-effective-task-is-its-subjects-merged-with-its-own`; they check the installed task foundations,
not implementation of this hybrid. The commit gate's recorded result is authoritative. Broad turn reds and the
existing Run 24 fault are outside this document's ownership; they are neither fixed nor claimed green here.
