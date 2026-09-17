---
type: research
status: design proposal; no production changes
created: 2026-09-17
tags: [research, steward, issue, contract, triage]
---

# Issue triage: function facts first, namespace stewards decide the work

## 1. Data model and recommendation

Keep **one detected issue per function**. The namespace steward decides priority,
coherent batches, worker budgets and escalation. Workers add contracts and tests,
observe real inputs and outputs, and author newly discovered problems as issue
entities. Completion requires positive test evidence for the accepted definition.
This applies the one-family ruling, generated identity and steward batching rules;
it does not introduce a task family or a central triage agent
(`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:151-184`,
`:186-212`; `docs/prds/steward-platform/plan/README.md:135-149`, `:197`).

The following table distinguishes existing facts from **proposed additions**.
Namespaces and current stewards remain derived; an observation about who made
a decision is different from current responsibility. Existing declarations are
in `resources/seon/schemas/seon.issue.edn:1-38` and
`resources/seon/schemas/seon.ns.edn:34-38`.

| Concern | Facts and decision |
|---|---|
| Finding identity | Existing `:seon.issue/id` = `(seon.issue/subject-id 'seon.issue.detect/private-function-without-contract [:seon.fn/sym F])`. The detector name is proposed; F is the installed identity value. No namespace, severity, source digest, worker or batch enters this identity. |
| Subject | Existing `:seon.issue/functions` contains the function ref. Follow `:seon.fn/ns` then `:seon.ns/steward` for responsibility. Do not copy that current owner onto each function issue. Existing `:seon.issue/namespaces` remains useful for an authored namespace-level finding, not necessary duplication for this detector. |
| Detector | Existing `:seon.issue/detector` refs its program function. Declare and admit the detector before generating issues. Its output is a subject with exactly one installed identity, plus title/problem. |
| Triage severity | Existing `:seon.issue/severity`: blocker for a demonstrated blocking defect, friction for the uncontracted read-consumer, cleanup only when the steward has evidence that lower urgency is appropriate. Missing contract alone does not prove a live blocker. |
| Order | **Propose `:seon.issue/position`, optional nonnegative integer**, lower first within the derived steward's queue; tie-break by issue id. It is the steward's authored judgment, not a rank recomputed and stored by a sorter. Presence records an ordering decision; absence means not yet ordered. No `triaged?` boolean. |
| Reason | Use the existing editable `:seon.issue/problem` for the evidence and rationale. The triage transaction records the existing user/process provenance; add no parallel decision log. Repeated identical judgment needs no write. |
| Budget | Existing `:seon.issue/budget`, total ordinary worker turns, not dollars and not HTTP attempts. Set at launch; a larger total resumes the same worker. Before launch it can carry the steward's proposed allowance, provided start validates the final allowance. |
| Worker assignment | Existing `:seon.issue/agent` names the worker only. Its indexed, listened assertion is the worker's first wake and budget anchor. Never put the steward here merely to wake it. |
| Batch | After set-valued start lands, common `:seon.issue/agent` derives batch membership; each issue has its own plan step and tests. No batch-kind stamp. Before then, three singleton starts are three workers. The namespace fork is a separate cluster relation. |
| Acceptance | Existing `:seon.issue/tests` refs real admitted deftests; `:seon.issue/resolved-tx` is written by verified settlement. An empty required set, missing subject, missing result or stale digest is not done. |

Identity is implemented at `src/seon/issue.clj:470-509`; the writer checks
installed identities and linkability. Worker creation, overlay and plan are
`src/seon/issue.clj:876-936`; resume is `:938-990`. Test verification and
settlement are `:822-867`, `src/seon/test.clj:1180-1201` and
`src/seon/plan.clj:714-723`. Transaction provenance is the existing rule at
`AGENTS.md:573-578`; exact historical source reads already return it
(`src/my/program.clj:333-344`). The new position key needs a registry search,
schema declaration, storage check and owning writer; it was absent in this
study's search of the issue schema. It is a scalar value, not a ref or component.

**Preserve the steward's decision on regeneration.** Current generation owns
severity on every run (`src/seon/issue.clj:504`, `:538-571`), so it would erase
manual triage. Proposed rule: generation supplies initial severity and prose;
triage owns later severity, position and budget. Retain function identity,
tests and assignment across reruns. Decide updates at the writer with the same
partial replacement mechanism already used at `src/seon/issue.clj:245-281`.
Historical triage is an as-of/history question, not another mutable mirror.

The per-function identity is a recommendation with a concrete alternative:

| Option | Guarantee, cost and give-up |
|---|---|
| **Per-function findings, grouped at launch — recommended** | Re-detection upserts the same finding; completion and reopening remain local to F; changing batches changes no finding identity. Reuses `subject-id`. Cost: more small issue rows and per-subject acceptance forms. |
| One issue per namespace | Fewer issue rows and a namespace-wide checklist. Cost: a growing required set and one budget/completion boundary for unrelated functions; every new missing contract reopens the aggregate. Gives up independent assignment and completion. Class rollups can instead be derived from detector + namespace, retaining fine-grained issues. |

This is a choice of granularity, not permission to use a numeric eid as identity.
The subject rule explicitly excludes eids, which cannot name an identity across
reforks (`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:151-159`).

## 2. The first detector and what its result proves

The raw census is **3,161 private / 16 contracted / 3,145 missing**, independently
reproduced on default at basis `536871355`; exact forms and MCP evidence are in
§8. The owner's more specific census is **352 uncontracted private functions
calling database reads**, from `docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:354-356`.
This study did not reproduce that read-only classification: its second probe
found **597** uncontracted private functions calling *any* function in
`seon.db`, including helpers and mutations. Do not relabel 597 as 352 or turn a
namespace membership test into a read/effect classifier.

The complete candidate query, independent of call-edge representation, is:

```clojure
[:find ?function ?symbol
 :where
 [?function :seon.fn/sym ?symbol]
 [?function :seon.fn/private? true]
 (not [?function :seon.fn/spec _])]
```

Then join source, namespace, file and defining-form facts to describe work.
Do not silently remove a candidate whose provenance is missing: report it as
not yet actionable. The census asks about all private rows; a production-first
launch additionally joins `:seon.fn/file → :seon.fn.file/relative-root "src"`.
Current public detectors already use that positive root fact
(`src/seon/issue/detect.clj:136-155`). Keep test-helper omissions visible for a
later batch. Incomplete specifications are a distinct detector; a present empty
or overly broad spec is not repaired merely because this absence query clears
(`resources/seon/schemas/seon.fn.edn:192`;
`docs/prds/steward-platform/plan/README.md:180`).

For prioritization, the **current ref-edge** query is:

```clojure
[:find ?symbol ?callee
 :in $ ?database-namespace
 :where
 [?function :seon.fn/sym ?symbol]
 [?function :seon.fn/private? true]
 (not [?function :seon.fn/spec _])
 [?function :seon.fn/calls ?target]
 [?target :seon.fn/sym ?callee]
 [?target :seon.fn/ns ?namespace]
 [?namespace :seon.ns/name ?database-namespace]]
```

After the ruled symbol-edge reset, replace the call join with
`[?function :seon.fn/calls ?callee] [?target :seon.fn/sym ?callee]`;
the rest is unchanged. Current storage is ref-valued calls and string-valued
function identity (`resources/seon/schemas/seon.fn.edn:36`, `:193-197`),
confirmed by §8. Target identities and calls are symbols by
`AGENTS.md:437-450`, `:488-497`. Use the installed grammar for a live query,
the new grammar after reset; no permanent dual storage or string coercion
layer is proposed.

For the 352-first policy, intersect that relation with the database owner's
**declared read operations**, if such an authoritative classification is
available at implementation time. This study found no declaration establishing
that every function in `seon.db` is a read: even the observed target set
contains `carry-connection-projection-state!` and `append-read-evidence!`.
Do not create a permanent hand-rostered list of read function names. For the
pilot, explicit owner/steward selection of three actual pull consumers is an
honest bounded choice; later automated read-first ordering needs a declared
read relation at the owner or a proved derivation from its existing declarations.
This is a prioritization gap, not a reason to lose the 3,145 findings
(`AGENTS.md:286-325`; `src/seon/db.clj:1701-1740`).

Do not copy the public detector's exclusions without review: it removes macros,
shared forms and bodiless defining forms (`src/seon/issue/detect.clj:216-251`).
The new rule covers private functions; a shared span may require one coherent
worker batch, and a genuinely unsupported declaration needs a visible finding
at the declaration/arming owner. It must not disappear into an exception count.
A declared contract can still be unarmed: the current collector excludes
primitive IFn implementations (`src/seon/instrument.clj:687-696`;
Malli's exclusion is `reference-code/malli/src/malli/instrument.clj:15-26`).

### Completion is executable evidence

For each selected F, admit a real subject-specific deftest before launching its
contract worker, and attach it plus the current reaching behavioral tests.
The deftest uses the **accepted program under test**; representative domain data
comes from `seon.test-support/with-database` and canonical helpers, with real
SCI and armed contracts. It must not inspect an unrelated pristine source
fixture and certify that instead of the worker's accepted definition
(`AGENTS.md:782-835`; `src/seon/plan.clj:631-673`).

The test's assertions define these obligations:

1. F still exists, still has a source-bearing definition and has a complete
   contract accepted by the existing schema admission checker. Removing F or
   toggling privacy is not contract completion. Reuse the checker at
   `src/seon/schema/internal.cljc:119`; no string search for `:any`.
2. The actual callable acquired for this program is armed. For a host-backed
   declaration, wrapper identity/current declaration evidence is available at
   `src/seon/instrument.clj:36-47`, `:640-685`. For an agent definition,
   exercise the acquired interpreted function through the real SCI path
   (`src/seon/sci/eval.clj:667-684`). A bad argument must produce the expected
   boundary refusal before body work; a valid representative argument must
   produce the promised output. Metadata presence alone does not establish this.
3. For read-consumers, test real successful reads, empty/absent results where
   legal, pulled refs/components under the actual selector, and a genuine
   refused read through the existing owner. A refusal must not become an empty
   collection or a successful row. Errors remain values; do not force every
   domain schema to accept them (`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:364-373`;
   `AGENTS.md:529-544`).
4. The issue's required behavioral tests are positively green on current
   reach digests. Derive selection through `my.program/tests-reaching` /
   `seon.fn/gate-set`, retain earlier obligations when a change removes an
   edge, and add a meaningful behavior test if none reaches F. A generic
   acceptance test is not a substitute for behavior coverage
   (`src/my/program.clj:146-161`;
   `docs/prds/steward-platform/research/test-attribution-plan-2026-09-15.md:62`).

Do not make the acceptance deftest recursively run or verify itself. Its
structural/behavior assertions and the attached reaching tests are ordinary
tests; the issue owner's `tests-done-query` checks all results from outside
the test run (`src/seon/issue.clj:822-838`). Missing tests, zero assertions,
query errors and stale digests do not pass (`src/seon/test.clj:1180-1201`).

**A required correction before this launch:** `generate` currently writes
`resolved-tx` whenever a detector no longer yields a subject, even when the
issue has tests (`src/seon/issue.clj:574-580`). That would bypass the stronger
definition of done. For tested issues, generation must defer resolution to
test-based settlement and reopen when its obligation becomes unsatisfied;
it cannot unconditionally reopen every yielded tested issue either. Untested
detector-only issues may retain their existing semantics. Also attach the
acceptance test before start: current `create-tx` explicitly accepts a detector
without tests (`:894-899`). Do not globally remove that existing capability
to implement this narrower contract campaign.

The acceptance source must be genuinely admitted, not an identity-only test
row. The original issue already explains why a fabricated test identity is
wrong (`docs/seon/issues/generated-issues-carry-no-tests-so-start-refuses-them.md:17-19`).
For three subjects, three authored forms using one shared assertion helper are
enough; they need not create a second test framework or 3,145 immediate tests.

## 3. Steward triage, wake and launch

A steward is an ordinary agent attached through `:seon.ns/steward`. Create
the steward before workers: agent creation claims an unowned namespace for the
first agent and preserves an existing steward
(`src/seon/cluster/agent.clj:98-131`). D3's standing task is the namespace's
derived obligations and view, not a new eternal standing-issue entity; the
later issue-family ruling explicitly replaces checklists with detector issues
(`docs/prds/steward-platform/plan/README.md:197`;
`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:195-212`).

On a turn the steward reads all open issues for its namespace, current worker
assignments and results. It handles observed faults and reds first, then the
private read-consumer contract wave, remaining private contracts, and incomplete
outputs. It groups by actual file/span, shared schema and caller relationships;
functions sharing a definition form or a schema change belong to the same
worker. It records severity/order, supplies the budget, and calls the existing
launch owner. This is judgment over data, not a detector guessing a batch
(`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:368-376`;
`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:186-209`;
`src/seon/issue/detect.clj:87-103`).

The standing view includes the whole C1–C12 signal set, while this campaign
selects only the first contract class. The table below is the intended
translation of the roadmap, not a claim that all detectors have landed
(`docs/prds/steward-platform/plan/README.md:174-197`).

| Signal | Steward reads / required evidence |
|---|---|
| C1 red test | Test, run, failure and current verification facts; the actual failing test is required. |
| C2 no reaching test | Current gate derivation, with unresolved coverage explicit; require a real reaching behavior test. |
| C3 incomplete contract | Contract and admission findings; this study adds the private-spec-absence class and its arming proof. |
| C4 recurring error | Error signature, function, occurrences and regression links; an occurrence is evidence, not an automatic causal verdict. |
| C5 unresolved call | Caller and observed target symbol; target symbol-edge work is a prerequisite for honest missing-target detection. |
| C6 missing render pair | Schema declarations and both projection tests on the canonical fixture. |
| C7 unreadable/elided output | Exact shown text, rendering evidence and a reproducible render test; durable elision observations remain a roadmap gap. |
| C8 lint | Indexed findings linked to declarations; require finding gone plus affected tests green. |
| C9 issues | Existing issue entities, linked functions/tests/errors and derived namespace owner; legacy folder ingestion is not new authoring. |
| C10 duplicate behavior | Explicit human/agent judgment and both functions' tests; graph similarity alone is only a candidate. |
| C11 cross-namespace red | Exact test/run/program evidence, proven caller subject when available, both affected stewards; routing is discussed below. |
| C12 unanswered user message | Message sender/recipient/subject and relative answeredness; answer it through the same ordinary turn mechanism. |

**The existing listened attribute is for workers.**
`:seon.issue/agent` has `:seon.db/index true`,
`:seon.wake/listen true` and `:seon.wake/opens-turn? true`
(`resources/seon/schemas/seon.issue.edn:30-33`). Generation does not assign it.
Neither a reverse-ref namespace view nor the since-diff alone wakes an idle
steward. Runtime patterns can notify a proc
(`src/seon/cluster/wake.clj:402-440`), but durable turn eligibility still seeks
declared attributes whose value is the agent
(`src/seon/turn.clj:2954-3004`;
`docs/seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md:3-23`).

Three wake choices, with incremental engineering estimates (not measured work):

| Option | Guarantee / cost / give-up |
|---|---|
| **Use existing issue-subject messages for the pilot — recommended** | At the generation/attribution writer, add one ordinary message per affected steward per changed issue batch, from the supplied requesting agent, about a stable issue id (or an existing batch subject token). Body names issue/run identities; the steward reads their entities. Reuse message delivery, listened `:seon.message/to`, and inside-wake semantics. About 4–8 hours including idempotence and ordinary-turn proof; no new wake attribute. Gives up a compact transaction-only notification and pays message storage/rendering. |
| Directed refs on issue-event transactions | Propose `:seon.issue/stewards` as an indexed, listened, turn-opening, inside ref set on the existing generation/settlement transaction, not the issue's mutable current-owner field. Records who was addressed at that event. Resolve recipients at the writer; a new event gets a new transaction, an identical replay emits nothing. About 1–2 days with schema/publication, context derivation and re-open tests. Gives compact, structured event routing; adds schema work before the first run. It follows C11's `:seon.test/stewards` proposal. |
| Generalize runtime listens first | Express a steward's namespace-dependent issue interests through the listening redesign, then teach both notification and durable eligibility the same semantics. Roughly 2–4 days plus the owner's unresolved listening design. Gives general subscriptions; postpones this pilot and must solve repeated values, retractions, reassignment and replay. Current three-field patterns alone do not guarantee a turn. |

Existing delivery use is visible in `src/seon/issue.clj:1010-1021`;
message origin/subject and inside semantics are
`AGENTS.md:654-656`. C11's directed transaction proposal, including repeated
results and exact program evidence, is
`docs/prds/steward-platform/research/test-attribution-plan-2026-09-15.md:82-96`.
Listening redesign is explicitly parked at
`docs/prds/steward-platform/plan/README.md:115`. These are alternatives;
implement one routing path and replace it in place if the owner later changes
the choice. No notification daemon or paid polling.

For either directed route: register the listener before deriving backlog; a
lost channel wake is harmless because durable facts still show work. On a
new steward assignment, explicitly derive existing untriaged work and deliver
it once; waiting for the next issue insertion would strand the backlog. A
missing steward remains a visible unowned finding, with root as the designated
triage escalation recipient, not an implicit successful assignment. Do not
reassert `:seon.issue/agent` to wake the steward: its original datom anchors
worker budget and later identical values produce no datom
(`src/seon/cluster/wake.clj:54-69`;
`resources/seon/schemas/seon.issue.edn:30-37`).

**Launch today:** `seon.issue/start!` accepts one
`{:seon.issue/id I :seon.issue/budget 8}`, with connection supplied by call
preparation or explicitly. It writes worker, plan, assignment and opening
through `:db.fn/call` (`src/seon/issue.clj:1024-1036`). No `my.issue/start!`
exists in `src/my/issue.clj:1-34`; the steward can call the system owner,
because all functions are callable (`AGENTS.md:659`). No new facade is required.

**Batch launch target:** accrete `:seon.issue/ids` to that same owner, sort ids
for identity, validate every issue and the nonempty required test sets inside
one transaction, create one worker, and one ordered plan step per issue.
A singleton retains its existing worker identity. The issue set must come from
the steward's coherent selection, and an already assigned member refuses the
whole new assignment. Budget is one shared worker total; copying it to every
issue must not multiply the allowance. Update scalar assumptions in
`exhaust-tx` (`src/seon/issue.clj:996-1004`) and route exhaustion back to the
steward; today it sends to root (`:1015`). Set-valued launch is specified,
not implemented (`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:188-194`).

## 4. Sanity checking and cross-namespace findings

Before editing, a worker uses `my.program/breaks`, `callers`,
`tests-reaching` and, where useful, `reads-key` on one supplied database value.
The reads include spans, current gate selection and explicit unknowns; proposed
contract compatibility is advisory until the candidate gate executes
(`src/my/program.clj:131-200`, `:240-304`). A list of callers does not prove
which caller is wrong.

The worker's evidence record consists of the exact evaluation source, inputs,
shown output/refusal, program basis and test result identities. For live
reads, use explicit connection custody in JVM mode; for accepted agent code,
use the worker's real SCI context. Read complete envelopes, follow an elision's
requery rather than infer an omitted shape, and inspect the refusal as rendered
to the agent. Keep useful code as a regression, not just an observation in chat
(`AGENTS.md:709-738`, `:751-756`, `:697`).

When adding F's contract makes caller C fail:

- First distinguish an incorrect proposed contract from C passing the wrong
  shape, or F returning the wrong shape. Compare actual values, declaration
  guarantees and the failing assertion. Fix the contract if the contract is
  wrong; do not call every new red a caller defect.
- If C is proved wrong, author an issue with C as the repair subject, attach
  the failing test and run/error evidence, and link the originating issue.
  Its responsible steward derives from **C → namespace → steward**, even when
  F and C live in different namespaces. F's steward also sees the event so its
  original obligation cannot be silently abandoned.
- If attribution remains uncertain, preserve the red, candidate referrers,
  exact program/run evidence and explicit uncertainty. Notify the relevant
  stewards without asserting a causal verdict. The namespace defining a test
  is not automatically the production namespace whose caller is wrong.

This is C11's evidence/recipient separation
(`docs/prds/steward-platform/plan/README.md:188`;
`docs/prds/steward-platform/research/test-attribution-plan-2026-09-15.md:10`,
`:60-86`) and the verify-before-attribution rule (`AGENTS.md:1099-1101`).
The current C11 `:seon.test/stewards` key was absent from the schema/source
search in this study; treat it as proposed, not an available alert.

A worker can already author an ENTITY with `my.issue/add!`, for example:

```clojure
(my.issue/add!
 {:seon.issue/title "Caller C passes a pulled ref where F requires a scalar"
  :seon.issue/problem "Exact failing form, accepted definitions, expected/actual, and run identity."
  :seon.issue/severity :friction
  :seon.issue/functions #{[:seon.fn/sym C]}
  :seon.issue/tests #{[:seon.test/sym T]}})
```

C and T above are bound installed identities, not literal placeholder names.
Connection and author come through call preparation. The existing writer
checks author and test refs and derives identity from title + sorted function
refs (`src/my/issue.clj:13-24`; `src/seon/issue.clj:1038-1076`).
Use stable identity lookup refs, never fork-local numeric ids as authored
identity inputs. Read existing issues first; duplicate-at-writer refusal is
handled by reading the existing entity. For recurring machine-detected reds,
prefer `subject-id` over changing error prose as the deduplication key.

The authored API currently selects only title/problem/severity/functions/tests.
Although the schema declares `:seon.issue/errors`, `runs` and `issues`,
passing those extra keys does not persist them (`src/seon/issue.clj:1056-1059`;
`resources/seon/schemas/seon.issue.edn:14-26`). Accrete these existing links at
that writer for structured findings. Until then the problem can cite exact
evidence, but do not claim that a prose citation created the ref. This missing
write path belongs in the first triage slice, not a second issue family.

The database is the authoring authority; the folder is an export by ruling
(`docs/prds/steward-platform/plan/unsettled.md:80-84`). Current
`index-tx` and `adopt!` still import path-bearing legacy notes
(`src/seon/issue.clj:283-314`, `:765-820`); this is not proof of an export
implementation. Do not set `:seon.issue/path` on a newly authored issue until
an actual export exists, or let an old file overwrite new database triage.
Export/write-back is a later boundary; new worker findings need no file write.

## 5. Concurrency, fork custody and cost

For N active namespace batches with M workers each: **N batch clusters,
N stewards and N×M workers**. The workers in one batch share its Datahike
branch and have their own SCI contexts. A SCI fork is not a Datahike branch.
The roadmap says one cluster per namespace batch, not one per worker
(`docs/prds/steward-platform/plan/README.md:141-143`, `:200-208`);
SCI isolation is `reference-code/sci/src/sci/core.cljc:345-351`.
Parked agents need not make provider calls; the existing per-agent proc is
the only turn mechanism (`AGENTS.md:165-172`).

The steward assigns disjoint declaration identities to simultaneous workers.
Common schemas, shared form spans or coupled functions go to one worker.
A single Datahike writer serializes transactions but does not resolve semantic
edits to the same definition. Same-batch overlapping work must be refused or
serialized at assignment/acceptance; naming separate workers is not isolation.
Three workers may edit three different forms in one file because this phase
accepts definitions in the database, before later exact-span disk write-back
(`src/seon/issue/detect.clj:87-103`;
`src/seon/program.cljc:1040-1094`;
`docs/prds/steward-platform/plan/README.md:210-216`).

For E1–E5, retain the exact fork commit and store custody. Derive changed
program identities against that basis; compare current target values inside
the merge writer. If the same identity changed on both sides, refuse with both
definitions, re-fork from the new shared head and retry within a renewed,
explicit budget. Normalize cross-branch refs by stable identity. Merge only
accepted program definitions/schema/tests and the necessary evidence; do not
blindly copy agent graphs, runtime state or numeric refs from a fork
(`docs/prds/steward-platform/plan/README.md:204-208`;
`src/seon/program.cljc:1040-1094`).

Datahike already supplies branches and serialized merge commits, but it
explicitly makes the caller supply merged transaction data; it does not decide
Seon's conflict policy (`reference-code/datahike/src/datahike/versioning.cljc:212-274`,
`:734-748`). A changed-identity conflict test alone is insufficient for
cross-namespace semantics: run the affected gate on the **combined candidate
program**. Shared-head movement invalidates that proof and requires reacquisition;
tests never run inside a writer transaction. Final shared-branch verification
and cold/platform proof remain the orchestrator's obligations. E3/E4 need this
publication boundary before automatic merge is called reliable.

**Owner budget proposal for the first experiment:** N=1, M=3, worker budget
W=8 each, steward allowance S=4 ordinary turns across triage and review.
That is four paid agent sessions and at most **28 budgeted ordinary turns**,
before any expressly added resumption. General bound per batch wave:
`N × (S + M×W)`; for N=4, M=3 the proposal is 16 sessions / 112 turns.
Provider attempts and billed tokens are measured separately. Current issue
budgets bind workers; a campaign-wide allowance covering stewards, new issues,
reforks and resumptions is **missing**, not supplied by this arithmetic
(`resources/seon/schemas/seon.issue.edn:30-37`;
`src/seon/issue.clj:938-990`).

For planning, start with the owner's configured inexpensive ordinary-work
model and disabled thinking; the checked-in choice is `deepseek-flash`.
This is repository policy, not a fresh market comparison or a claim that its
tariff remains current (`config/default.edn:361-380`, `:397-402`;
`AGENTS.md:1042-1046`). Model escalation is a steward request against the
remaining campaign allowance, justified by a specific unresolved refusal or
design decision, never an automatic fleet-wide switch.

**Illustrative price, using only the repository's dated price assumptions:**
assume 20,000 uncached input tokens + 4,000 output tokens per successful turn,
no backup/retry charges, and the stored off-peak rates of $0.15 / $0.60 per
million (`config/default.edn:558-573`). Then:

| Scope | Calculation | Illustrative cost |
|---|---|---:|
| One turn | 0.020×$0.15 + 0.004×$0.60 | $0.0054 |
| Three workers + one steward | 28×$0.0054 | $0.1512 |
| Four namespace batches | 112×$0.0054 | $0.6048 |
| 3,145 singleton workers, eight turns each | 25,160×$0.0054, excluding stewards and repair work | $135.864 |

These are sensitivity calculations, **not a quote, expected measured spend or
a spending cap**. Doubling that assumed tariff doubles the figures. Cached
input reduces them; larger contexts, reasoning/output, retries, backup,
new findings and re-forks change them. General accounting is
`Σattempt ((input−cached)×input-rate + cached×cache-rate + output×output-rate)/1e6`,
using the attempt's effective provider/model and applicable tariff. Usage
fields already exist (`resources/seon/schemas/seon.ai.usage.edn:1-4`).
The configured retry count is two, backup is enabled, and backoff applies only
to conclusively unpaid failures with no backup; therefore multiplying every
turn by three as a billed-cost estimate would also be wrong
(`config/default.edn:433-447`, `:472-484`).

The owner's effective cost controls should be: maximum active namespace
batches, workers per batch, the total turn allowance (including stewards and
resumption), and the existing per-agent model/output settings. Charge a worker
budget **once per worker**, not per issue in its set. Before each launch or
increase, the authority derives outstanding allocations and spent work and
refuses exceeding the campaign allowance; stewards may propose an increase
but cannot create fresh budgets indefinitely by opening issues. This is a
proposed admission invariant, not an existing aggregate cap. A hard dollar
cap additionally needs reservation at the existing provider request seam and
reconciliation of actual usage, including uncertain transmitted outcomes.
Defer that work for the bounded pilot rather than label a turn cap dollars.

## 6. Priced implementation sequence and slice-one live proof

These are incremental slices of roadmap A/C/D/E/F, not a replacement project
schedule. Estimates are engineering effort, excluding foreign reset/gate waits
and whatever defects the contracts uncover. No implementation or paid run is
authorized or performed by this study.

| Slice | Existing / missing / estimated work | Exit evidence |
|---|---|---|
| **First: one namespace, three workers** | Existing identity/generation, single-issue start, plan settlement, authored issues, program reads. Missing private detector; positive acceptance deftests; generator/settlement ownership fix; triage order and severity preservation; namespace issue view; chosen steward wake; structured authored evidence links; campaign launch allowance. Roughly **2–4 engineer-days**, after the private-arming and clean-base prerequisites. Use three singleton starts, so set-valued launch is not on this path. | The exact pilot below; no claim of fork/merge or disk persistence. |
| **Then: coherent multi-issue workers and C11** | Accrete set-valued start, per-issue steps, one shared worker budget, exhaustion back to responsible steward, tested red attribution and repeat-event routing. Roughly **1–3 additional days**. | One batch sharing a worker; duplicate starts refuse; cross-namespace caller red reaches both responsible engineers without duplicate repair workers. |
| **Then: concurrent forked namespace batches** | E1–E5 still need batch custody, changed-identity projection, conflict-aware exact replacement, candidate gate and re-fork integration. Roughly **3–5 additional days**. | Two namespace branches, clean combined gate, deliberate same-identity conflict/refusal, bounded re-fork and successful merge. |
| **Finally: export and disk write-back** | Existing file/span and exact definition facts; F2/F3 need assembly, conditional file effect, round-trip index and commit. Roughly **1–2 additional days**, after accepted definitions prove reliable. | Re-index reproduces merged definitions and tests; orchestrator gates and commits exact owned paths. |

The code inventory behind those estimates is `src/seon/issue.clj:470-613`,
`:876-1111`, `src/seon/plan.clj:623-723`, `src/my/program.clj:131-304`;
the missing target operations are roadmap
`docs/prds/steward-platform/plan/README.md:188-208`, `:210-216`.
Do not rebuild existing start, completion or identity because the roadmap's
older A rows still call them `my.task`: the later one-family ruling supersedes
that spelling (`docs/prds/steward-platform/plan/namespace-data-model-2026-09-16.md:526-532`).

The first live proof is deliberately the approved **same-branch** interim
before E, not a claim that fork isolation already works
(`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md:139`).
If the owner requires isolation before any paid edit, price the E slice ahead
of this proof; it adds the stated 3–5 days. This study does not operate a cluster.

1. The orchestrator supplies the clean, adopted base after the reset sequence
   recorded in `docs/prds/steward-platform/plan/unsettled.md:2844-2846`.
   Prove private arming on that same program; this study's census is not that
   proof. Gate the new machinery on canonical fixtures first, with virtual
   replies through ordinary agent procs and bounded event waits
   (`AGENTS.md:782-835`, `:941-948`).
2. Use **seon.render.web**, the owner's first-steward choice
   (`docs/prds/steward-platform/plan/unsettled.md:80`). This study observed
   118 missing private contracts there. Query at launch again. Three current
   pull-consumer candidates are `acquire-debug-data`,
   `assigned-agent-namespace`, and `data-response` (qualified names in §8).
   They are candidates, not a causal diagnosis or a preapproved edit set:
   check file/span, shared schemas, current arming work and reaching tests;
   choose three disjoint real functions if those overlap.
3. Admit three acceptance deftests and attach each required reaching set;
   create one steward before starting workers. Run the detector twice:
   same three selected issue identities, no duplicate work or repeat wake.
   Capture the namespace opening showing subjects, severity/order and actual
   done tests. No fabricated tasks.
4. Deliver the selected wake through the ordinary mechanism. Observe the
   steward's ordinary turn calling `seon.issue/start!` three times with
   budget 8; observe three worker agents/plans/openings in facts, their
   `:seon.issue/agent` datoms and the steward's retained ownership. Do not
   substitute direct host starts for the claim “the steward launched them.”
5. Workers read actual inputs/outputs, submit contracted definitions through
   normal SCI admission, and run the real tests. Record each accepted
   function's source/spec, admission provenance, definition identity and
   transaction; demonstrate the acquired callable's arming. “Landed” here
   means **three accepted database definitions plus their tests**. A host
   `defn`, proposed source string or private SCI def is not this result.
6. Observe automatic settlement after positive current-digest results for all
   required tests; regenerate issues and verify no early closure/reopening.
   Capture a real invalid-input refusal in both agent shown text and the
   debug surface. Verify a repeated no-change pass makes no extra paid turn.
   If a contract exposes a caller defect, record the new issue entity and
   routed recipient; do not force the target of three completions by weakening
   a contract or dropping the red.
7. Report ordinary turns, provider attempts, model, input/cached/output tokens,
   cost under recorded assumptions, elapsed time, issue/test/run/definition
   identities, and any budget exhaustion. The orchestrator supplies the
   cold affected gate plus platform proof. Worker iteration is
   `bin/test-fast --paths <owned files> -- <namespaces>`; neither an
   in-process green nor this design note claims that cold proof
   (`AGENTS.md:862-927`).

The recurring class regressions should cover detector idempotence and
reappearance; absent subject/provenance; preserved triage; no closure on merely
present spec; real private arming; stale/empty/removed test obligations;
writer-atomic double launch; inside wake without budget refill; re-open and
backlog delivery; proven cross-namespace attribution versus unknown; and budget
admission for simultaneous steward decisions. Use one coherent regression per
failure class, positive observed subjects, canonical helpers and exact bounded
events—not a new fleet of mocked launch tests
(`AGENTS.md:120-125`, `:782-835`, `:941-944`).

## 7. Grounding, archaeology and verification boundary

Named document authorities:
[AGENTS.md](../../../../AGENTS.md),
[docs/prds/steward-platform/plan/README.md](../plan/README.md),
[docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md](../plan/issue-family-spec-2026-09-16.md),
[docs/prds/steward-platform/plan/unsettled.md](../plan/unsettled.md),
and [docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md](../plan/program-facts-are-the-runtime-prd-2026-09-17.md).

Read end to end: AGENTS.md §§0–7 (including vocabulary); the steward roadmap
README; issue-family spec; `src/seon/issue.clj`;
`src/seon/issue/detect.clj`; `src/my/program.clj`; and the namespace data
model referenced by the roadmap. Read the exact **16:35Z** entry at
`docs/prds/steward-platform/plan/unsettled.md:2833-2852` and program-facts
PRD **§1j**, `:352-376`. Older counts, `my.task`, identity tombstones and
folder-first authoring in the historical specs are not current design
authority; the later rulings and inspected code are distinguished above.

Archaeology used `git log -- src/seon/issue.clj src/seon/issue/detect.clj`
and the surviving implementation, including `627a24047` (issue adoption
citations), `761408a17` (refused input is not an ordinary row),
`8242ec533` (defining-form facts) and `a2e16338b` (program reads).
Source anchors were inspected against HEAD
`247bb115bdac7e6a0fe55b27a6c00f52b898da4d`; concurrent work may move lines.

| Dependency and pinned gitlink | Read seam | Existing first-party consumer / design implication |
|---|---|---|
| Datahike `73afe78271a289861da236c5ac3457e64349653f` | `reference-code/datahike/src/datahike/db/transaction.cljc:1153-1154`; `core.cljc:200-218`; `writer.cljc:401-416`; `versioning.cljc:212-274,734-748` | `src/seon/issue.clj:1034`, `src/seon/cluster/wake.clj:402-440`: writer decides against its database; listeners are connection-local and non-replaying; merge receives caller-supplied data. No new writer or polling loop. |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | `reference-code/sci/src/sci/core.cljc:331-351` | `src/seon/sci/eval.clj:667-684`, `src/seon/plan.clj:637-673`: real contexts and interpreted wrapping, distinct from branch isolation. |
| Malli `3517a3cd9271b2083780ac7be1725493905bca2e` | `reference-code/malli/src/malli/instrument.clj:15-33` | `src/seon/instrument.clj:593-613,687-696`: declaring and arming are separate claims; primitive exclusions must remain visible. |
| core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165-202` | `src/seon/cluster/wake.clj:15-33`, `AGENTS.md:165-172`: reuse per-agent graphs, explicit workloads and nonblocking wake delivery. |

**Study verification:** exactly two read-only MCP JVM evaluations on default,
explicit `seon.operator/connection "default"`, no definitions, transaction
calls, provider calls, tests, agent launches or lifecycle operations. Runtime
status answered, PID 94566, three plumbing pings replied, and reported
4 evaluation errors / 16 failed tests. This is not a healthy-suite assertion.
The initial working tree was clean; later foreign edits appeared in
`AGENTS.md`, `.agents/skills/data-oriented-clojure/SKILL.md` and
`test/seon/instrument_test.clj`. They were neither edited nor attributed as a
cause. No load failure required a worktree. The one owned file is this note.

The first MCP projection was explicitly elided: 106 of 138 database target
entries omitted under its child bound, with requery and artifact digest
`616e00c5baf83b109157f39bcaf0a9ce649339835449e5aa2f7edcc0f1b7c30a`
(size 21,405 bytes). Census scalars and stewardship rows were visible. The
second probe reduced its own result to a small complete count/sample. No
claim relies on the omitted target entries. MCP projection artifact creation
is tool behavior; the submitted forms were read-only.

Existing findings searched: the open runtime-listen eligibility issue and
the historical generated-issue-without-tests note cited above. New design
gaps recorded **in this single requested note** are generator-versus-test
completion ownership, triage severity replacement, missing structured authored
evidence inputs, absent steward delivery, batch scalar assumptions and aggregate
budget admission. They are proposed implementation work, not fixes claimed here.
The named cold/platform proofs and three-worker live proof remain future work.

File-local validation used the existing Babashka-loadable
`seon.dev.markdown/validate-file`: `:valid? true`, `:violations []`.
Whitespace validation is `git diff --check`; no runtime test is warranted
for this documentation-only deliverable.

The Markdown edit hook reported 32 repository pin-citation errors, including
old gitlinks in
[docs/prds/context-generation/research/agents-md-audit-2026-09-15.md](../../context-generation/research/agents-md-audit-2026-09-15.md):226-235.
That is a foreign documentation verification boundary, not a failed contract
test or evidence against this design. No foreign file was repaired. The hook
also queued this documentation edit for its ordinary publication; this study
did not request publication, verify adoption or spend a third MCP evaluation.

## 8. Exact read-only probe forms and measured results

Both calls used root `/Users/sean/src/seon`, cluster `default`, mode `jvm`,
`read_only: true`, timeout 30,000 ms. No SCI mutation or test execution.
These embedded forms preserve the reproducible study script without a second
artifact.

### Census (MCP evaluation 103 ms; basis 536871355)

```clojure
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      private-count (seon.db/q database '[:find (count ?f) . :where [?f :seon.fn/private? true]])
      contracted (seon.db/q database '[:find (count ?f) . :where [?f :seon.fn/private? true] [?f :seon.fn/spec _]])
      missing (seon.db/q database '[:find ?sym ?name :where [?f :seon.fn/private? true] [?f :seon.fn/sym ?sym] [?f :seon.fn/ns ?ns] [?ns :seon.ns/name ?name] (not [?f :seon.fn/spec _])])
      db-name (seon.db/q database '[:find ?name . :where [?ns :seon.ns/name ?name] [?f :seon.fn/ns ?ns] [?f :seon.fn/sym "seon.db/q"]])
      db-calls (seon.db/q database '[:find ?sym ?callee :in $ ?db-name :where [?f :seon.fn/private? true] [?f :seon.fn/sym ?sym] (not [?f :seon.fn/spec _]) [?f :seon.fn/calls ?g] [?g :seon.fn/sym ?callee] [?g :seon.fn/ns ?ns] [?ns :seon.ns/name ?db-name]] db-name)]
  {:study/basis-t (seon.db/basis-t database)
   :study/private private-count
   :study/contracted contracted
   :study/missing (if (:seon.error/kind missing) missing (count missing))
   :study/top-namespaces (if (:seon.error/kind missing) missing (take 8 (sort-by (comp - val) (frequencies (map second missing)))))
   :study/db-call-targets (if (:seon.error/kind db-calls) db-calls (into (sorted-map) (frequencies (map second db-calls))))
   :study/ns-stewards (seon.db/q database '[:find ?name ?id :where [?ns :seon.ns/name ?name] [?ns :seon.ns/steward ?agent] [?agent :seon.agent/id ?id]])
   :study/installed (mapv #(seon.db/pull database [:db/ident :db/valueType :db/index] [:db/ident %]) [:seon.fn/sym :seon.fn/calls :seon.issue/agent])
   :study/source (seon.db/q database '[:find ?id :where [_ :seon.source/commit-id ?id]])})
```

Visible result: private 3161; contracted 16; missing 3145; stewardship
`my.agents.root → root`, `my.agents.juniper → juniper`.
Top missing-contract namespaces: seon.test.runner 155; seon.db 135;
seon.render.web 118; seon.turn 107; seon.render.transcript 94; seon.fn 87;
seon.cluster 85; seon.schema 70.
Installed function identity: string; calls: ref; issue agent: indexed ref.
Source commit fact: `6aaba963-228a-5f26-aacc-6dcb4535c7b5`.
This does not compare the cluster's source fact to current-src or prove adoption
freshness; it is a census of the named live database value.

### Broad database callers and bounded pilot sample (157 ms; basis 536871365)

```clojure
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      calls (seon.db/q database
              '[:find ?sym ?callee :where
                [?f :seon.fn/private? true] [?f :seon.fn/sym ?sym]
                (not [?f :seon.fn/spec _])
                [?f :seon.fn/calls ?g] [?g :seon.fn/sym ?callee]
                [?g :seon.fn/ns ?ns] [?ns :seon.ns/name seon.db]])
      candidates (seon.db/q database
                   '[:find [?sym ...] :where
                     [?f :seon.fn/private? true] [?f :seon.fn/sym ?sym]
                     (not [?f :seon.fn/spec _])
                     [?f :seon.fn/ns ?ns] [?ns :seon.ns/name seon.render.web]
                     [?f :seon.fn/calls ?g] [?g :seon.fn/sym "seon.db/pull"]])]
  {:study/basis-t (seon.db/basis-t database)
   :study/all-db-callers (if (:seon.error/kind calls) calls (count (set (map first calls))))
   :study/pull-candidates (if (:seon.error/kind candidates) candidates (vec (take 3 (sort candidates))))})
```

Complete result: `:study/all-db-callers 597`;
`:study/pull-candidates ["seon.render.web/acquire-debug-data"
"seon.render.web/assigned-agent-namespace" "seon.render.web/data-response"]`.
The two timings are single MCP evaluation durations, not isolated query
benchmarks or performance guarantees.
