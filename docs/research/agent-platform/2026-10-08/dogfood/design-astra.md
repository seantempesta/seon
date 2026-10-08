---
title: "Independent design check: lanes working as Seon agents"
type: research
status: review
created: 2026-10-08
---

# Independent design check: lanes working as Seon agents

The loop cannot yet start as written, and completing the listed cards would not alone make its evidence sound.
The largest defect is the assignment bootstrap: root needs a failing proof visible to assignment, but the prescribed
route puts that proof through a gate requiring green. Next are disagreements about which branch supplies proofs,
what the tests executed, and what a commit actually contains.

Smallest composition: root prepares the task at its home and the red proof on the future worker's Datahike branch;
assignment names that branch and the reviewed proof definitions; SCI evaluates changes there; the existing runner
records positive evidence; write-back selects the claimed declarations; the gate checks the exact candidate against
that evidence. No second evaluator, test runner, task store or scheduler is needed. The proposals below are design
changes, not claims that these APIs are installed.

Scope: independent outside check by lane **dogfood-design-astra**. Read the assigned pilot, template, owner rulings,
agent-loop spec, RF-1 through RF-6, CS-01c1 through CS-01c5, F4a through F4d, TASK-CHECK, REF-1/REF-2 and the references
model. REF-2 arrived during this review in `76fef2897` and was read before finalization. Named cards were read as
supplied, including their pending revisions. No other lane's in-progress review or
landing notes were read. Committed implementation inspection used revision `1285b45b2f8b4abd0f2d733c1877b321a9f32b1d`;
the live JVM reported source revision `6a244149dee2708b47b06b1d65e7a3bbaef79450`. These are different evidence bases.

## Ranked findings

Rank is by the consequence for the whole loop. Each proof below is a proposed regression to add to its owning
card, not a test this documentation change claims to have run.

### 1. Assignment requires a red proof to pass a green gate

**Blocks every new behavior task.** The pilot's assignment refused `:no-proofs`. Committed `seon.task/assign!`
reads `task-edges` and `seon.test/verified?` only at the task home. The template requires landing the proof and
restarting before assignment. Landing a named failing test is refused; a current green proof is refused by
assignment. RF-2's `--accept-red-before` does not solve a newly introduced proof: it has no matching prior test on
main. Nor should an unrelated pre-existing red authorize a task's own failing proof.

**Change:** prepare the proof on the future agent branch before assignment. Keep the task and assignment at their
home, but have `seon.task/assign!` inspect the branch it is assigning and bind the reviewed proof identities,
definition digests and baseline evidence. Recheck the branch evidence and task revision at assignment's writer.
No red proof lands on main. The eventual commit carries task EDN, proof and implementation together when green;
task EDN needs the export described in finding 10. This also removes the per-task restart.

**Cards/rows:** S6-06a, F4a, RF-2, TASK-CHECK; template root steps 1–2. **Proof:** root creates a task, evaluates a
tagged failing deftest on a fresh worker branch, runs it red, assigns successfully, then the worker's first form
shows that task and proof. Main's program and test results remain unchanged.

### 2. The worker must already have run before its first assigned form

**Blocks the specified launch order.** The template obtains the branch from the lane's first `eval_clj`, but root
must assign before launch and the lane's first form must read its assigned context. Today a new MCP agent gets its
branch on first use; the pilot's first block consequently contained no task.

**Change:** root uses the existing agent/branch creation owners to prepare the future worker's identity, branch and
SCI context, then prepares and assigns the task there. Expose that setup through the ordinary REPL if the owners
cannot currently do it without an agent evaluation. Root's setup request is distinct from the worker's first
request. Release root's test-file claim before the worker claims it. Return the actual branch identity from setup,
not a guessed branch name.

**Cards/rows:** S6-06a, RF-6 row 4, template root steps 2–3, CS-01a. **Proof:** an agent with no prior evaluation
reads its assigned task, branch and proof source in its first reply. A repeated setup returns the same prepared
assignment or an explicit conflict, without creating a second branch.

### 3. An agent cannot author the proof its assignment depends on

**Blocks proof preparation and repair.** Pilot frictions 3–5 report a resolver-dependent digest refusal for a
`deftest` in an existing namespace, missing test-support Vars, and SCI bindings that do not reach compiled calls.
The live check here independently found zero public Vars in `seon.test-support`. RF-4 merely feeds the same broken
entrance; writing the proof in a file conceals the gap rather than proving the agent loop.

**Change:** derive the declaration digest from the same source and namespace resolution inputs at admission and
the writer; expose the canonical harness's supported operations to the test's SCI context. Keep harness setup in
the runner's supplied execution, not in copied agent fixtures. Observe compiled work through the one test-visible
measure ruled by the owner, not a SCI-only binding around a host counter. No new reader or test-side evaluator.

**Cards/rows:** RF-4, RF-6 rows 6–7, F4a, RP admission/recording rows; pilot tasks
`seon.db/a-repl-deftest-in-a-file-backed-test-namespace-is-refused`,
`seon.sci.eval/test-support-has-no-vars-in-the-agent-context`, and
`seon.sci.eval/binding-a-host-dynamic-var-does-not-reach-host-code` (task proposals in the pilot).
**Proof:** author a tagged test in an existing file-backed namespace, run it with the real harness on its branch,
and read its stored assertion and measured compiled-call work through the agent entrance.

At finalization, `7f0458ffd` landed the namespace-resolver digest fix. This removes one source-level defect from
the initial snapshot; this review did not re-probe it after a reload. Harness availability and measured host work
remain distinct obligations. Do not assign a duplicate repair of the landed digest change.

### 4. Proof identity and proof freshness have three different authorities

**Can produce a false done or a permanently pending task.** Assignment reads home proofs; F4a unions home and
branch proof names; committed `seon.context/proofs` reads branch proof names only. Root can revise a task at home
without installing a new proof body on an existing branch. A union of names neither supplies that body nor tells
which version defines done. Deleting or weakening a tag on the branch can also make the displayed proof set smaller
than the assigned set. The stored task revision alone does not identify a separately edited deftest.

**Change:** use one existing task/assignment owner to resolve the reviewed proof set against a named branch.
Carry proof definition digests as assignment evidence; these are observations, not a second authored proof list.
Assignment, context, automatic testing and acceptance consume that same decision. A root proof revision advances
the assignment and reconciles the new definitions onto the worker branch, or reports a conflict. A worker's proof
edit is visible for root review and cannot silently shrink the acceptance requirement.

**Cards/rows:** S6-06a, CS-01b, CS-05b, F4a, F5, RF-2. **Proof:** root changes a proof after assignment; the next
block shows the new requirement and pending/conflicting evidence, never green evidence for the old proof. Test
deleting the only proof tag and changing its assertion while keeping its name.

### 5. Cost proofs need an observable contract; not every refactor has one

**Blocks cost tasks today and leaves pure restructuring undefined.** The owner has settled that measured cost may
be a proof. The template still launches cost/refactor work unassigned, and the testing skill still calls the matter
open. Merely naming an existing green behavior test does not establish that a refactor happened. A wall-clock
sample from a busy JVM also cannot distinguish the intended algorithmic improvement from concurrent load.

**Change:** implement the ruled measure as data from the same runner/execution owner, with explicit subject,
finite input and measurement extent. Prefer reads, executions or allocations where they express the requirement;
keep before/after timing evidence and the runner's bound. The proof must fail for the intended cost on the old
implementation, not because its harness is missing. Exclude setup and automatic checking from the measured subject;
include work on child threads when the subject owns it, or report that measurement as unavailable.

A purely structural refactor with no claimed observable cost still has no truthful red proof under the current
rule. Root must state its actual unmet requirement, or treat it as an implementation step inside a task with such
a requirement. Do not fabricate `(is false)` or quietly launch it unassigned.

**Cards/rows:** RF-6 row 7, F4a, S6-06a, owner's cost-measure ruling; template and testing skill. **Proof:** identical
values before and after, a read-count proof red before and green after for two bounded input sizes, observed for
both SCI and compiled bodies. Unavailable measurement is not zero.

### 6. Green reaching tests may execute the old compiled definition

**Can accept an untested change.** RF-2 checks file/branch digests and green reaching tests. RF-3 correctly identifies
the hole: a compilable SCI definition may still be called through the unchanged host platform. Presence in a
static call graph or an armed call count does not identify the body that actually ran.

**Change:** take RF-3 option 1 as a hard dependency of branch acceptance. Record actual executed definition digests
on test evidence and require positive coverage of each changed executable body. Apply the evidence recursively
to the dependencies needed by the candidate. Use the existing isolated check for a body that only the compiled
platform can exercise; label that limitation in the context before the lane chooses its route.

Execution alone cannot prove a deletion, namespace require, alias change or declarative contract. Those need the
existing declaration validation plus tests of their surviving consumers; a deleted function cannot execute its
new digest. RF-3's proposed universal rule must distinguish these cases rather than make deletion impossible.

**Cards/rows:** RF-2, RF-3, RF-4. **Proof:** a green test that invokes only the old host body refuses branch evidence;
a test executing the edited SCI body permits it. A deletion with current green consumer evidence can land without
claiming execution of a deleted body.

### 7. File equality with the branch does not prove the candidate being committed

**Can land a program no test ran.** RF-2 analyzes each selected working-tree file against the branch, then compares
with main's live head. Git HEAD, live main and the worker branch need not contain the same program. A branch change
can depend on an edited helper in an omitted file; the selected file equals the branch while HEAD plus that file
still contains the old helper. A deletion disappears from the set of declarations analyzed from new bytes. A later
file edit can also occur between evidence collection and commit.

**Change:** pass the exact candidate tree and selected file content digests to the gate. Compare added, changed
and deleted identities, namespace resolution inputs and the candidate's dependency closure with the program named
by the test evidence. Refuse omitted changed dependencies and bytes changed after checking. Reuse current evidence
for unchanged inputs; do not require an identical whole-store commit. Bind the committed bytes to the checked tree
under the existing commit lock. Mixed task EDN/code paths must have a declared route: export/validate the task and
reuse code evidence, or explicitly run the existing isolated check, rather than silently pretending all are .clj.

**Cards/rows:** RF-1, RF-2, RF-3. **Proof:** selected callee plus omitted changed helper, removal from a file, moved
namespace alias, main advancing during the check, and bytes edited after the check all produce the appropriate
refusal or recheck. A harmless unrelated data write does not stale evidence.

### 8. Unknown evidence must not become red-before permission

**Can bypass proving.** RF-2 calls a test red-before when it is not verified on main with the same reach digest.
Not verified includes absent results, stale results, a runner error and actual failed assertions. Its card's
`proven?` conditions also do not explicitly require the assigned task's proofs independently of reaching tests.
A proof for an answer or cost may not be discovered by the changed function graph.

**Change:** require a current positive red observation at the baseline, with its definition/input evidence and
failure data, before offering red-before acceptance. Keep unknown, pending and errored recording distinct. The
gate always checks the reviewed task proofs as well as the selected change's reaching tests. Explicitly accepting
an unrelated baseline red never accepts a red task proof or a newly introduced regression. A red test rerun to green
with unchanged inputs needs investigation of determinism, not whichever result is convenient.

**Cards/rows:** RF-2, F4b, CS-01d, F5. **Proof:** missing and stale main results cannot be accepted as red-before;
a recorded baseline failure can be reported as such; a red assigned proof always prevents success.

### 9. Automatic testing has no complete progress rule

**Can stop indefinitely or finish too early.** F4c continues pending tests at the next eval request. But F5 may
wait once proofs are green and no test is red, even while reaching tests are pending. An MCP client may send no
next form. A repeatedly red proof can consume the step on each request and starve reaching tests if only green
results are reused. F4d says an unchanged program derives an empty queue, yet answer/data proofs can be pending
without a code difference. F4c also promises a hard wait bound while allowing a started host test to run to actual
exit; those are incompatible guarantees.

**Change:** completion requires current positive results for the full selected set, with zero unknown or pending
members. Retain current red results as observations until their actual inputs change; they remain blockers, not
success. Let the existing agent Flow proc continue outstanding test work without another model call or a dummy
MCP evaluation, while keeping submissions serial and resources owned until exit. Derive pending work from the
assignment and branch inputs after restart, including data and answers. Describe 10 s as an admission budget for
starting tests, not a promise that a noninterruptible member exits within 10 s. Do not add a scheduler.

**Cards/rows:** F4a–F4d, F5, CS-01b/CS-01d, EN-6. **Proof:** after the only user request, proofs green and one
reaching test pending cannot open a merge; continuation eventually runs it. A red first proof does not starve a
second test. Restart with an answer change but no code diff still derives the unfinished proof. A held host member
prevents overlapping work until its actual exit.

### 10. Task filing and task durability are outside the working loop

**Blocks assignment and friction reporting whenever files are held.** Runtime `seon.task/create!` stores a task but
does not export its EDN. A resource-wide claim can block all agents' filing. Tasks are operational data, excluded
from program merge, so `my.program/write-branch!` is not automatically their export. Requiring a red proof before
every defect can even be recorded makes a broken test-authoring entrance impossible to report through itself.

**Change:** separate recording a task from eligibility for assignment. `create!` records a defect with its evidence
at the task home; TASK-CHECK reports a missing proof without refusing the task. Root supplies the proof before
assignment. The task home's existing writer/write-back owner exports the same task identity to the claimed EDN
file, including conflict checks; a held file delays export, not recording. Re-ingestion is idempotent, and reset
cannot discard unexported tasks without preserving them. No task-specific side store or parallel issue index.

**Cards/rows:** TASK-CHECK, S6-06a, task write-back issue already in resources/seon/tasks/seon.task.edn, RF-2 mixed
paths. **Proof:** record a defect while its EDN file is held; it is queryable but unassignable without proofs;
export later, ingest it and observe one identity. Pilot EDN left in a landing note is a handoff, not a filed task.

### 11. Same-request program inspection sees a different program from evaluation

**Blocks normal REPL composition.** The pilot's definition executed, but `write-branch!` in the same request saw no
recorded declaration. RF-6 row 2 allows merely documenting that `diff` reads the recorded branch. The owner instead
requires later forms to see earlier definitions, and the spec explicitly requires a same-request test check to see
them. A generic "accepted merge changed no declaration" error is also false provenance.

**Change:** distinguish live namespace state from durable program facts in the contracts. Automatic testing already
belongs after RECORD and must test its exact result. For explicit `diff`/check during evaluation, derive from the
held SCI observations and namespace values through the same admission functions, without replay or a second
transaction. Durable file write-back may require the request's recorded commit; if called too early, return data
naming the unrecorded definitions and the operation to run in the next request. Root must state this narrow
write-back sequencing contract instead of weakening all REPL visibility.

**Cards/rows:** RF-1, RF-6 row 2, F4a, RP recording rows, agent-loop §3.1/§3.5. **Proof:** define/call/redefine/check
in one request tests the last definition. Early write-back explicitly names pending recording; the next request
writes that recorded definition exactly once.

### 12. Scratch namespaces and selection are braided with whole-branch validity

**Can poison an otherwise useful branch.** Pilot friction 8: an `in-ns` namespace retained by evaluation refs has
no program digest, and removing it did not unblock diff or even selected write-back. Banning scratch namespaces
contradicts the REPL law and prevents discovery work.

**Change:** the namespace producer distinguishes a live/observed namespace from an admitted program declaration
using declared schema facts. An evaluation's observed namespace name must not require a permanent executable
namespace entity. Compare the selected program entities and their necessary dependencies; diagnose an unrelated
invalid declaration separately. A relevant invalid dependency still blocks acceptance. Do not synthesize a digest
for a declaration that never existed or ignore all undigested program entities.

**Cards/rows:** RF-1, RF-6, RP-1w; pilot task
`my.program/an-in-ns-namespace-blocks-diff-and-write-branch`. **Proof:** create and remove scratch state, then diff
and write a selected valid declaration on the same branch; historical evaluations remain readable.

### 13. Write-back checks ownership after it has already written

**Can overwrite another lane and gives a stale base to a moving target.** The template says write every changed
declaration, then inspect `git diff --stat` for unclaimed files. RF-1 computes a three-way comparison but passes the
current cluster head as the file base. When main advances since the worker fork, selected branch bytes and current
checkout bases require conflict checks before installation, not a later inventory.

**Change:** select identities and owning paths before writing; require they lie inside the lane's claims. Pass the
actual base used for each selected declaration and reuse the existing writer's exact-base and reindex checks.
Surface conflicts as data for root. Never restore or write an unrelated main-only change from an older branch.
A write-back caused by the REPL must not trigger RF-4 into re-evaluating those already recorded declarations.

**Cards/rows:** RF-1, RF-2, RF-4; template write-back step. **Proof:** one claimed and one unclaimed branch change
writes neither unclaimed bytes nor a partial mistaken selection; a disjoint main advance is preserved; a same-file
conflict refuses before installation. Repeating the accepted write-back makes no new evaluation.

### 14. The block omits the information needed to choose the next operation

**Blocks independent work even when task assignment succeeds.** This review reproduced the evaluation-world leak.
The specified sections also omit the worker's branch/base, task revision, execution route, pending continuation
and actionable diagnostics. Pilot errors hide offending declarations; map-argument world supply fails; a selected
namespace pull expands to a whole page. A small turns-left map is not sufficient context.

**Change:** repair the render invocation/supply producer so it passes only the declared value to the declared
render and returns that render's data. Add concise branch, assignment revision, proof/broken/pending counts and
runnable detail operations from their owners. Error renders retain the discriminant, subject and repair data.
Respect a requested attribute selection. Missing world members inside declared map inputs are supplied by the
same contract mechanism as positional inputs. Keep internals reachable through explicit diagnosis, not the block.

**Cards/rows:** CS-01a–CS-01d, CS-02, RF-6 rows 1/4/9, supply and error-render rows; pilot frictions 7/9/12/14.
**Proof:** the actual MCP block and model block expose the same actionable data; ordinary rendering contains no
connection or SCI context. A failed write-back names the offending declaration without requiring a blind search.

### 15. A current task can reference documents the running program cannot read

**Blocks context after ordinary root edits.** CS-01c5 deliberately reads docs from the loaded source archive, while
a task's current declaration is read at its home. Root can revise a card or add evidence after that JVM started.
The block then reports an unresolved path or reads an older instruction. Requiring a restart for every task/doc
edit recreates finding 1's operational bottleneck. Branch code, host fallback and dependency pins also have distinct
bases; an old host Var must not masquerade as a function deleted on the branch.

**Change:** pass the task/reference content basis explicitly to the existing resolver. Task docs use the accepted
source revision reviewed for that assignment; executable source uses the branch declaration; a library uses the
loaded dependency pin. Show the basis with the read result. Read immutable archived content without reloading the
JVM. Distinguish unavailable source from unresolved name. A deleted first-party branch name must not fall through
to a stale host definition. Root's task update must name accessible accepted content before advancing assignment.

**Cards/rows:** CS-01c1–CS-01c3/CS-01c5, REF-1, CS-05b, S1-06/S1-08. **Proof:** revise a task and referenced doc
without restarting; its next read returns that revision. Read a branch-edited source, a branch-deleted name and a
library source, each with the correct basis. Missing archive content is reported explicitly.

### 16. Reference resolution is not yet a closed, callable contract

**Leaves agent-visible dead ends after the cards land.** CS-01c2 explicitly offers `doc`/`source` before S1-06/S1-08
make them work. Its schema admits namespace symbols, while REF-1 narrows names back to qualified symbols/keywords
and drops simple dotted namespace spans. The proposed arglists schema admits vectors of symbols only, excluding
valid destructuring. A task symbol is neither a function nor a test and needs its own lookup. Unloaded library
names and Babashka-only owners have source even when the JVM has no Var. `find-var` on an unknown namespace can
throw instead of yielding an unresolved reference.

REF-1 also classifies a path by whether its first segment exists at the loaded root. A misspelled base can become
a symbol candidate; evidence strings need to remain paths. The section read returns text but no resolved links
from that text, so the agent must either reimplement relative-reference handling or discover a separate resolver.
REF-2 now supplies the hook card, but explicitly checks working-tree text against loaded-archive paths until a
restart, confirming finding 15. It also skips every research/issue Markdown file, even when such a file is a task's
required context, and proposes a late `requiring-resolve` to escape the task/reference namespace dependency.

**Change:** keep one typed resolver; preserve native symbol/keyword/path inputs, namespace names, task identities
and actual arglists. Use `find-ns`/`ns-resolve` for loaded Vars and existing source analysis for source-only names,
labeling them non-callable rather than inventing installation. Offer only operations proven through the agent
entrance. Return reference candidates/resolutions with section reads, using that document's base, without recursive
prefetch. In REF-2, keep historical citation drift separate from a required document that cannot be read now;
do not hide the latter behind its front-matter type. Let the hook dispatch task files to the task owner, which
already calls the resolver, rather than introducing the reverse namespace dependency. Unresolved future names
remain problems, not admission refusals. Test dependency anchor generation before relying on its semantics, as
REF-1 already requires.

**Cards/rows:** CS-01c1–CS-01c5, REF-1/REF-2, TASK-CHECK, S1-06/S1-08. **Proof:** one table covers a task, namespace,
destructured function, schema, absent namespace, source-only owner, missing path base, relative link and heading;
every offered operation is then evaluated through MCP and returns its declared data or explicit unavailable result.

### 17. TASK-CHECK's invalidation rule misses changes outside task text

**Can leave a task falsely ready.** TASK-CHECK recomputes only when a task declaration changes. Removing its last
proof tag, deleting a referenced subject or revising a linked heading changes no task text. It also defines
"subjects gone" as none of the must-reads resolving; reading dependencies is not a declaration of the task's
subjects. Its card and ledger disagree on whether all-green open tasks are covered.

**Change:** derive problems from the task plus the actual proof/reference inputs, using existing dependency evidence
and `seon.cache`; unchanged task text alone is insufficient. Use declared subject relations for deleted-subject
claims, or report unresolved must-reads without calling the task obsolete. Separate proofless, pending, current red,
current green and missing reference observations. The block and ingestion call the same owner; rendering stays
read-only. The edit hook's check of newly changed definitions uses the same proposed program basis, not stale main.

**Cards/rows:** TASK-CHECK, REF-1/REF-2, CS-01b/CS-01c1, S6-06a. **Proof:** without touching task EDN, remove its
proof tag, delete a must-read and rename a heading; the next task observation changes correctly. An unrelated edit
reuses the derived value. A task citing a surviving library is not declared healthy merely because that name exists.

### 18. RF-4's file-to-REPL translation misses namespace changes and deletions

**Makes file fallback disagree with REPL work.** RF-4 sends changed top-level definition spans preceded by `in-ns`.
It does not describe added requires, alias retargets, renamed/deleted definitions, macros or dependency order.
`in-ns` does not apply an `ns` declaration. Replaying arbitrary top-level forms can repeat effects. The hook's
"no root: lint only" output cannot count as execution evidence.

**Change:** the normal lane edits through the REPL; its write-back is already proven and needs no replay. If file
editing remains a supported entrance, use existing analysis/rewrite-clj declaration deltas and the namespace owner
to apply additions, removals and changed resolution inputs through the one path, in valid dependency order. Admit
only declarations the entrance can account for; send unsupported host changes to the existing isolated check.
Treat hook unavailability as missing evidence, never successful adoption. RF-5's deletion is useful but does not
solve this translation.

**Cards/rows:** RF-4, RF-5, RF-3, RP-8. **Proof:** edit a require and a function using its alias together, rename a
function with its callers, delete a definition, and write back an unchanged branch; the resulting declarations
match the file with no duplicate effect or evaluation.

### 19. The template's timeout recovery can repeat effects

**Can corrupt an otherwise successful run.** The template says MCP gives up at 32 s and to re-run. The spec says a
started submission owns execution until actual exit and is then recorded. A timeout does not say whether a form,
test or write-back ran. The pilot also reports 15–20 s ordinary checks and a 207 s commit; these are failures to
meet the intended operating loop, not acceptable defaults taught to agents.

**Change:** a timeout returns the submitted request identity and an existing status/result read operation. Observe
that request's actual exit and durable result before deciding whether another request is needed. Never replay an
unknown write to recover its reply. Surface the machinery timing, test-body timing, pending work and responsible
armed functions separately. Keep pre-existing slow tests named by var in evidence, without assuming they are
permitted to overlap a new attempt.

**Cards/rows:** RF-6 rows 6/8, F4c/F4d, MCP bound/actual-exit rows, template prove step. **Proof:** expire the caller
while a form is running; recover one recorded result and one effect, with no second execution. A queued submission
cancelled before start has distinct data and no effect.

### 20. Branch success, Git commit and shared acceptance are conflated

**Leaves the last mile unspecified.** The product loop requests root's Datahike merge; the lane loop writes directly
to the shared checkout, commits and expects root to reload. F5 may request a merge and wait as soon as proofs pass,
before the outside lane has exported files. A root merge can retire the branch while RF-2 still needs its evidence.
A `git log --grep RF-2` match proves neither installation nor that an uncommitted `bin/lane` is the shipped gate.

**Change:** state the outside lane's acceptance sequence explicitly. Green branch evidence makes a proposal ready;
root acceptance, exact write-back, Git commit and installed reload each have their own recorded outcome, and one
owner coordinates them. Retain the tested branch until all consumers have captured durable evidence and the merge
request is closed. A branch-evidence commit proves its candidate; it does not assert that default runs it. Probe the
installed gate/API, not a commit message. The operator's shell remains an outside adapter over these decisions;
a product agent does not need a second shell-based workflow.

**Cards/rows:** RF-1/RF-2, F5, MA-11/MA-15, SR-BRANCH; template commit step. **Proof:** root acceptance racing with
lane export cannot delete required evidence; restart shows the exact accepted source; failure before export leaves
a recoverable proposal. No branch is called accepted merely because its local tests passed.

Cleanup here also reproduced the existing task
`my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`: the worker's own retirement request returned
only `{:seon.error/message "run transition refused: admission-closed"}`. Root then retired that same owned branch
through `my.agent/retire-branch!`, which answered `:my.agent/retired? true` in 45 ms, 810 calls. The product loop
needs its ordinary retirement owner to finish this without a manual outside-agent rescue.

## Proposed complete loop

1. Root creates the task through the home writer, records its accepted doc references, prepares the future worker's
   branch, and authors/runs its tagged proof there. A real red assertion establishes the unmet requirement. Root
   assigns that branch and reviewed proof set, hands over the claimed files, and exports task EDN through its owner.
2. The worker's first `(seon.context/current-state)` answers the task, branch, reviewed proofs and their status,
   references and runnable reads. It probes and changes namespace definitions in SCI; definitions and errors are
   recorded by the ordinary path.
3. After RECORD the runner checks proofs and reaching tests against those definitions and observed inputs. The Flow
   proc continues bounded outstanding work without asking the model to emit filler. Missing, red and pending evidence
   prevent success; test failures are data.
4. The worker selects the claimed declarations for write-back in a subsequent recorded request. Exact file bases,
   required namespace changes and candidate dependency closure are checked before installation. Root's acceptance
   sequence preserves the branch until the evidence, export and commit are complete.
5. `bin/lane commit --branch-evidence` checks the exact candidate bytes and current positive execution/validation
   evidence. Platform changes use the existing isolated check. Root reloads accepted committed inputs and repeats
   the installed probe. Only then is the installed result claimed; the closed branch can retire.
6. Every context gap is recorded as a task immediately, even when its proof/export must wait. Root supplies the red
   proof before assigning its repair. This is ordinary task preparation, not a launch exception.

This replaces the red-on-main bootstrap, unassigned cost work, manual test polling, whole-branch write-back and
blind timeout retry. It adds no alternative evaluator or store. Ordinary work follows the changed declarations,
their required dependencies, the selected tests and the references actually read.

## Evidence and verification limits

Both `bin/seon status` and MCP `runtime_status` answered; default was **degraded**, with 14 over-bound warnings,
25 errored receipts, one unowned namespace and one cache-discipline warning. The status reported live heap
1,614,807,040 bytes under a 4,294,967,296-byte cap. These are shared observations, not memory attributable to this
review. Over-bound subjects included `seon.sci.eval/call-detecting-program-change` (59,675 ms maximum) and
`seon.batch/evaluate-batch` (59,725 ms maximum); the response already associated these with task handles. No restart,
load of uncommitted source, provider call or foreign-lane operation was performed.

The review's first form used SCI, agent `dogfood-design-astra`, cluster `default`:

```clojure
(seon.context/current-state)
```

Reply: result `result/ead4c483ef7a6`, `:ms 1`, `:calls 732`. Its intended value was just
`{:seon.context/turns {:my.agent/turns-left 100}}`; the shown value also contained
`:seon.db/connection`, `:seon.db/db`, `:seon.sci.eval/ctx`, admission caps, render profile and
`:seon.render/value`. About 650–750 estimated tokens, mostly machinery. There was no assigned task; this review
was handed over outside Seon's task system. That limitation is not proof of a successfully assigned first block.

Second form and its complete value:

```clojure
{:task (my.task/open)
 :test-support-publics (count (ns-publics 'seon.test-support))
 :section-installed? (boolean (ns-resolve 'my.fs 'section))
 :write-branch-installed? (boolean (ns-resolve 'my.program 'write-branch!))}
;; :ms 7, :calls 251, result/ec6563d579dd1
{:task [] :test-support-publics 0
 :section-installed? false :write-branch-installed? true}
```

This confirms lookup presence/absence in this SCI context, not write-back correctness or a regression test run.
Pilot behavior not reprobed here remains **pilot evidence**. New failure scenarios above are **design
counterexamples** requiring the proposed regressions, not claims of additional observed failures.

Dependency seams read locally, at the review's gitlinks:

| Library revision | Function and derived source location | Guarantee used; inputs and recomputation | Cost claim |
| --- | --- | --- | --- |
| Datahike `86c3301a00de8c49b2690083b2b1e69677bf0c8d` | `datahike.versioning/branch!`; versioning.cljc line 212 | Branch from an existing branch or commit; reuse indexes; new branch only at preparation | No store copy; I/O not measured here |
| SCI `010c86df4668b57bc16e50bce7a5a3f93e4d01ff` | `sci.core/fork`; core.cljc line 334 | New/redefined Vars are private to the fork; context is the input | No second evaluator; time not measured here |
| Malli `418df4417827b66846b799b5e87267a344dffc29` | `malli.core/validator`; core.cljc line 2672 | Validator cached on the compiled schema; changed schema yields the new contract | Compile/reuse library validator, no bespoke shape checker |
| Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` | `clojure.repl/source-fn`; repl.clj line 147 | Loaded Var metadata and classpath source; absent source returns nil | One source read; does not prove branch source or pin identity |

Source guarantees do not establish installation. The first-party seams inspected were `seon.task/assign!`,
`seon.task/create!`, `seon.context/proofs`, `seon.context/current-state`, `my.program/write-branch!` and its comparison
owner, plus `bin/lane`'s candidate, lint and documentation-only paths. No broad suite or new behavior test is
appropriate to this one Markdown report: no program input changes, and no test reaches its text. The proposed
regressions belong to the implementation cards above.

Foreign boundary: dirty claimed runtime files, schemas, tests and `bin/lane` were present throughout the review.
They were neither edited nor treated as installed. The report's lint candidate uses committed HEAD's program files,
which are exactly this documentation slice's program files; other lanes' working-tree edits are excluded.

Final static check: `clj-kondo --lint src script test --cache false --parallel` on an archive of
`8bfd1c3aa5c5eb6b12840723b651ef79a0da71cc` completed in 5,884 ms: **0 errors, 1,476 warnings** (exit 2 for warnings).
The subsequent HEAD at `f171cb644` had no changes to those lint inputs. Whitespace check passed. This slice changes
one Markdown file; source/test lines are **+0/-0**, and reaching tests/red-before test exceptions are **none**.
The documentation-only `bin/lane commit` route applies; no branch-evidence claim is made for prose.

Cleanup verification: root read `(contains? (:my.agent/roster (my.agent/branches)) :agent-90cd12d0f183)` and got
`false` (0 ms, 81 calls). The two read-only root probes during retirement each took 1 ms. No probe branch remains.
The earlier self-retirement error is preserved above under its existing task rather than counted as a successful
self-retirement. Temporary lint archives are disposable; their results are recorded here.
