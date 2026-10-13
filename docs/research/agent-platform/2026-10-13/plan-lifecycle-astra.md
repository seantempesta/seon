---
title: "Independent plan: one merge decision and proven task handoffs"
model: gpt-6-astra
date: 2026-10-13
status: proposed; independent; no implementation
---

# One merge decision and proven task handoffs

The existing path is task → agent branch → automatic tests → completion → merge preparation → acceptance → publication. Move completion's decision into the merge owner and make each stage produce the task data its successor consumes. Keep Datahike branches, SCI forks, the test runner and root's `db<->flow`; replace the competing completion rules, not those mechanisms.

Authority: the round brief's October 13 template ruling supersedes “no templates” and the Design deferral in [hybrid-chains](../2026-10-12/hybrid-chains.md). Read with [the dig](dig-step-lifecycle.md), [audit](audit-night.md), [history](chain-steps-history.md), [TASK-CHAIN §3b](../../../seon/schedule/cards/TASK-CHAIN.md#3b-stages-advance-by-proofs-one-system-end-to-end), [plan §0](../../../seon/plan.md) and Runs 16–24. The other lifecycle plan was not read. Proposed names below are interfaces to implement, not claims about installed functions.

## 1. The judge owns the candidate, not just the boolean

**Propose `seon.cluster.source/merge-verdict` as the single decision**, over a named `:seon.source/merge-observation`: held target database, three-way candidate, task obligations, static findings and current recorded test evidence. It returns accepted or the existing error/finding data naming what remains. `seon.context/step-verdict` becomes its presentation; `seon.cluster.source/prepare-merge!` acquires its inputs; `seon.cluster.source/accept-merge!` installs exactly its accepted candidate. Neither context nor acceptance restates a clause.

The candidate is **main's current head plus the branch's delta since its merge base**, including namespace and task changes. Keep `seon.cluster.source/compare-branch`, `seon.cluster.source/replacement` and `seon.program/three-way`. The merge base identifies the proposal; it is never a substitute for the target against which the proposal is judged. Testing the branch alone cannot establish this law: an unchanged dependency may have changed on main.

Split acquisition, not policy:

- **Static acquisition:** three-way comparison and selection closure; `seon.db/with` of the actual replacement; `seon.cluster.source/contract-gaps`; `seon.task/candidate-dangling`; `seon.cluster.source/proven-files` and `seon.cluster.source/lint-errors` over those exact candidate bytes. Require the publication root on the automatic chain entrance. A missing root cannot mean that completion silently skips publication checks. `:not-equivalent` must reach the same observation as `:not-analyzable`.
- **Test acquisition:** `seon.test/run` selects obligations plus current reaching tests in that merged candidate's SCI context. Reuse only results whose definition, evaluator and read/input evidence match. Candidate reds needing comparison run against the held target using the same evaluator, recording on the worker's test branch, never main. `seon.test.runner/designed-red?` remains the sole executed-red predicate. Green handoff proofs, complete product membership, coverage and accepted baseline reds are all decided by the one judge. Unrun, stale, assertionless or unfinished evidence cannot satisfy an obligation.
- **Scheduling:** acquire after the initial proof pass and once after each settled eval request that changes an input; pass that observation to the reply, completion and next-work derivation. A context render performs no test or file proof. Root supplies an immutable target value through the execution inputs, never its writable connection. Candidate test branches remain ordinary owned test resources. `prepare-merge!` uses the same acquisition path and content-valid evidence; it does not blindly run a second complete gate.

Cache the static derivation through `seon.cache`, keyed by changed declaration content, the target values read, namespace/schema inputs, source-file base digests, analyzer/configuration identity and the judge's definition. File bytes are inputs: database read evidence alone cannot validate a file proof. Re-read the affected file bases at publication. Test evidence retains the runner's own content keys. Cost is proportional to changed files, reached definitions and invalidated test inputs; no cache keyed solely by transaction or branch head. `candidate-dangling` currently scans broader task relations: retain correctness first and measure that owner before claiming delta-proportional implementation.

**A head can move between verdict and acceptance.** “Done iff accepted” is exact for the same held target/candidate/inputs, not a promise about a future head. Make root's accepted merge transaction the durable successful end, rather than closing the worker before attempting it. A positive branch observation is the proposal root's existing listen route consumes; it pauses further model work while root handles that proposal. Root reacquires against current main. Changed inputs produce the next observation on the same assignment; they do not produce an `:error` end and another model attempt. Use Datahike's existing `:datahike/expected-basis-t` on the short final write to establish that the held target is still current. A changed basis returns work to the ordinary listened derivation, without a retry loop; unchanged content reuses the evidence. This also covers changes outside the written identities that a proof read. The present touched-declaration comparison alone is insufficient for that case.

Publication remains a separate durable outcome: preflight cannot guarantee future filesystem I/O. A publication failure after acceptance stays visible on that accepted assignment and goes to root; it must not rerun Implement or pretend the merge did not happen. Run 24's namespace defect still needs its producer repaired: earlier visibility of the refusal does not repair namespace derivation.

**Delete in the conversion:** `seon.context/unmet`'s rules, `seon.context/designed-red?`, `seon.context/reds`' fork-point policy, completion's `seon.context/base-results` dependency, `seon.turn/record-broken!`'s independent judgment, `seon.cluster.agent/fixable-refusals`, `seon.cluster.agent/refused-merge-relaunch` and the corresponding `seon.cluster.agent/failed-pass!` arm. Render the judge's findings directly. Keep capped/stalled continuation, `seon.cluster.agent/relaunch-reason`, merge-base writing and branch resource cleanup.

Remove the live `:satisfied` completion/end path in the same slice as its context, turn, close, readiness and deletion callers. An empty program delta is ordinary accepted work **only when the same nonempty obligations are positively met on current main**; merge its assignment/task facts and close through normal `:merged` handling. Preserve historical `:satisfied` values as historical data while converting their current readiness interpretation once. Do not infer completion from an empty diff. The smaller alternative—deliver every refusal and relaunch—removes the class list but preserves false completion and wasted attempts; reject it for this goal.

## 2. Templates are ordinary task declarations

Build **three worker templates now: Design, Prove, Implement**, in `resources/seon/tasks/seon.step.edn`. Mine remains the existing query/root filing activity. Land is root's existing merge/publication work after **each** worker step, not another agent that merges an already merged Implement branch. Retrospective is initially a root task with ordinary proofs after publication; do not build a miner or retrospective scheduler in this cut.

Add `:seon.task/template`, a peer ref to a template task, and `:design` to `:seon.task/step`; retain `:prove` and `:implement`. Templates contain instructions and own tagged handoff deftests, remain held, and have no per-subject children or execution history. No stage-type registry, template language or separate next-stage graph: actual children and `:seon.task/needs` still express the chain.

`seon.task/effective` merges template defaults, root-to-leaf subject content, then the step's overrides. Identity, needs, children, execution and attempts are the instance's, never copied from the template. Instructions overwrite. Keep the existing intent string's replacement semantics for callers; add `:seon.task/refinements` as ordered contributions to the goal, with the prior contributions preserved by `my.task/refine!`. The effective view carries the original goal plus contributions in order. Do not silently change an existing API from replacement to append. References are the derived union of names in that accumulated goal, decisions and the **selected** instructions, via `seon.ref/names`; no authored reference list and no previous stage's instructions leaking into the next. Content still flows down children, never along needs: the predecessor writes the common subject on its own branch and root merges it before the successor forks main.

Add a named `:seon.task/handoff` value on the subject for the actual inputs being handed over: reproduction cases and basis, interface declarations/samples, required product test symbols and observed baseline result references, replacement identities, and each stage's explicit decision with reason. Give those members named schemas at `seon.task/refine!`'s producer; declare their consumers in the handoff proofs. A decision records judgment; a proof compares its claims with the actual candidate, rather than passing because prose exists. No copies of test reports or call graphs.

For example, rewrite the **existing** `seon.turn/a-green-proof-completes-the-task` subject as follows (proposed EDN; preserve its current evidence links in the full intent). Its four currently tagged regressions remain the subject's behavior proofs, inherited by Implement; they need not be renamed or copied.

```clojure
[#:seon.task{:name a-green-proof-completes-the-task
  :intent "`seon.turn/task-end` completes only from the shared merge decision;
           preserve the four regressions cited by this task and diagnose their stored failures."
  :children #{[:seon.task/sym seon.turn/design-green-completion]
              [:seon.task/sym seon.turn/prove-green-completion]
              [:seon.task/sym seon.turn/implement-green-completion]}}
 #:seon.task{:name design-green-completion
  :template [:seon.task/sym seon.step/design]}
 #:seon.task{:name prove-green-completion
  :template [:seon.task/sym seon.step/prove]
  :needs #{[:seon.task/sym seon.turn/design-green-completion]}}
 #:seon.task{:name implement-green-completion
  :template [:seon.task/sym seon.step/implement]
  :needs #{[:seon.task/sym seon.turn/prove-green-completion]}}]
```

Design must resolve whether these are owner defects or retired fixture assumptions before Prove changes anything. “Use existing red proofs unchanged” is a legitimate evidenced Prove decision; requiring newly named tests would manufacture work. Template revisions are read at the held basis and enter proof input identity. They must not silently reuse a result from the previous template content.

## 3. Handoff proofs are green tests over real inputs

Extend `seon.task/obligations` to select the template's tagged proof plus instance proofs; Implement also inherits the subject's behavior proofs. The test request carries an explicit named handoff input containing subject/step identity and the held task revision/database. The ordinary runner supplies it through schema-named bindings; include that input in result reuse. **This input plumbing is required work**, not an installed capability claimed by this plan. A plain deftest calls its contracted assertion helper with that supplied value; it never asks which task its test agent holds. No second runner or per-instance generated test source.

| Template/proof (proposed deftest) | Data that must be true before acceptance |
|---|---|
| `seon.step/design`, `seon.task-test/design-leaves-an-executable-interface` | Reproduction/acceptance cases are nonempty; references resolve; actual contracts compile with `malli.core/schema`; samples validate with `malli.core/validator`; declared consumers/replacements match the design decision; Prove's required case/product description is present. Existing bodies remain intact. For genuinely new symbols, contracted stubs have reaching green design tests asserting the declared unimplemented result. |
| `seon.step/prove`, `seon.task-test/prove-leaves-complete-observed-proofs` | Declared cases match observed cases; every required product test exists, resolves to the subject/Implement obligation and has baseline evidence at the stated pre-change program; expected failing data matches the reports; the “reuse/change” decision names existing tests. Product tests are distinct from this green handoff proof. No test-name invention when existing reds suffice. |
| `seon.step/implement`, `seon.task-test/implement-leaves-a-publishable-change` | Product proofs are current green; named replacements are gone with consumers converted; declared measurements and actual changed identities support the decision. Static acceptance and reaching-test policy remain solely the merge judge's responsibility, not copied into this deftest. |
| Root publication, then retrospective | Existing merge/write-back tests prove accepted declarations = written declarations and the git commit is recorded. Retrospective compares observed friction with follow-up tasks carrying red proofs, instruction changes or an explicit reasoned `:none`. No proof depends on its own future merge. |

These tests inspect the **candidate handoff before merge**. Calling `seon.task/ready` after a fabricated merge is circular as a completion proof. Separately, integration proves that accepting the handoff makes the actual successor ready and gives it exactly these inputs. Missing handoff evidence keeps the same worker working, with its failed assertions shown.

## 4. Loadable phases and Run 25

Each row is a serial slice at shared owners; the red tests named are proposed additions/cases, not claimed green. Contracts, schemas, callers, context/help and obsolete tests convert in that slice. Root batches restart and broad tests.

| Phase | Red proof and conversion boundary |
|---|---|
| **L1: source owns one verdict** | Add `seon.cluster.merge-test/completion-and-merge-read-one-decision`, table cases for contract gap, lint, file non-equivalence, missing proof, uncovered function, dangling task, designed error-red and newly broken reach. Extract acquisition/decision from prepare/accept; convert their callers and context together. Keep current lifecycle until the observation is usable. |
| **L2: one candidate, one successful end** | Add `seon.cluster.db-flow-test/a-target-change-is-judged-before-the-attempt-ends`: move a read dependency on main between observation and acceptance; no premature successor, no new attempt, correct current verdict. Add empty-delta/partial-product rows to the existing satisfied-product chain test and convert it to normal merged closure. Delete the copied rules, refused-merge relaunch and live satisfied path together. Keep cap/stall regressions. Measure the whole request against parent, including acquisition and cached context reads; shared static work must execute once per changed input. |
| **L3: template/handoff data and proof inputs** | Extend `seon.task-test/a-steps-effective-task-is-its-subjects-merged-with-its-own`: refinements survive, instructions overwrite, references derive, two instances of one template cannot reuse each other's green result. Add `seon.task-test/a-partial-handoff-keeps-its-successor-unready` through refinement → proof → merge → ready. Publish schemas and consumers together; round-trip sparse task EDN through write-back. Introduce the three templates and their handoff tests together, not empty template shells. |
| **L4: real chain** | Extend `seon.cluster.db-flow-test/a-prove-steps-red-product-merges-into-main` through Design and a durable refinement, using injected replies first. Each stage decision must reach the successor and task file. Update the owning plan's stale template/phase sections in the implementation commit. |

**Run 25:** first replay Runs 23/24's partial product and namespace self/duplicate-require inputs with injected replies. They must stay on the same working attempt with the exact source finding; the namespace producer must be fixed before claiming the real chain finishes. Then root launches one fresh, small filed defect through all three templates with one running fact and no hand acts. Record first-call inputs, verdict acquisitions per eval, static/test wall time and allocation, calls, decisions, merged identities, git commits and deleted branches. Test a concurrent unrelated main write and a relevant dependency change deterministically before the paid run; do not add a paid concurrency experiment to this docs assignment.

## Evidence and verification limits

Default ran archive `371a754dfbc2dcaefb20e2db309023889db92474`, pid 99642; both status entrances reported `:faulted` with one open fault and one agent-error signature, no missing boot layers. Live census: six explicit `:prove` tasks; Implement is the default. Run 24 arrived during research and was read: it adds the observed `:not-equivalent` case, not a speculative refusal class. No provider call, restart or foreign branch mutation was made.

Own SCI agent `plan-lifecycle-astra`, branch `:agent-a2f5c739968e`, first read `(seon.context/current-state)` (8 ms/8.6 MB; request 167.4 ms). Defined only `seon.lifecycle-probe/identity-value`, contract `[:=> [:cat :int] :int]`, body `[x] x`. Its request cost 340.4 ms. Static probe below ran as read-only JVM/root over that owned branch: JVM mode as a non-root agent is actually refused (“JVM mode is root's”), despite the tool description's broader wording.

```clojure
;; Values held outside each seon.profile/measure call:
;; main = (seon.db/db (seon.cluster.boot/connection "default"))
;; own = (seon.db/db (seon.db/branch-connection main :agent-a2f5c739968e))
;; ids = #{[:seon.fn/sym 'seon.lifecycle-probe/identity-value]
;;         [:seon.ns/name 'seon.lifecycle-probe]}
(seon.profile/measure
 (fn []
   (let [tx (#'seon.cluster.source/replacement own main ids)
         merged (seon.db/with main {:tx-data tx})
         files (#'seon.cluster.source/proven-files main merged ids "/Users/sean/src/seon")]
     {:paths (mapv :seon.source/path files)
      :lint (seon.cluster.source/lint-errors "/Users/sean/src/seon" files)
      :contracts (seon.cluster.source/contract-gaps merged ids)
      :dangling (seon.task/candidate-dangling merged main ids)})))
```

Three calls: **73.56/37.13/35.60 ms**, **100.10/39.31/38.69 MB thread allocation**; path `src/seon/lifecycle_probe.clj`, lint/contracts `[]`, dangling `{}`. Existing unchanged `seon.edit/single-form?` file proof + lint/contracts/dangling: **143.69/141.61/111.36 ms**, **125.32/101.23/100.91 MB**. These are static-owner costs, **not** full merge/verdict costs: comparison, test execution, evidence coverage and cache-hit behavior are unmeasured. Global profile ranks include concurrent work and are not attributed to this lane.

Read-only replay against Run 24's retained branch `:attempt-8667f9088300`, replacing `seon.edit/single-form` plus its namespace into held main, reproduced `:seon.source/write-back-error`, `:not-equivalent`, `src/seon/edit.clj`, all 21 identities offending: **102.16/66.83 ms**, **162.07/132.18 MB**. File proof failed before lint/tests. This independently establishes that the static owner can expose the real failure before completion. Materialized values were released. After own admission closed and its connection/contexts were absent, root retired only the owned probe branch; branch and retained base were verified absent.

Library seams inspected at HEAD gitlinks: Datahike `c9e57c6f` (`datahike.versioning/commit-as-db`, `datahike.versioning/merge!` and its writer basis option); SCI `eb3290d5` (`sci.core/fork`, isolated generations); Malli `dd13ff81` (`malli.core/-instrument`, contracts compiled with the supplied schema options). No replacement mechanism is proposed for them.

Docs-only verification: `clj-kondo --lint src script test --cache false --parallel` completed in 5,944 ms with **0 errors, 1,645 warnings**. No executable input changes or reaching tests in this commit, hence no throwaway JVM test selection; the explicit docs-only lane path applies. Existing turn reds are reported in the linked audit/triage, not rerun or claimed fixed. Foreign boundary: default's Run 24 namespace/publication defect and existing tree warnings remain outside this document's ownership. No other lane's file or session was changed.
