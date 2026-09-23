---
type: review
status: independent source review; ready after listed fixes
created: 2026-09-23
reviewer: gpt-6-astra (Codex)
scope: D1 section 2e
---

# Independent review of D1 §2e

The system already has branch custody, a durable source entrance, declaration comparison,
test selection and prompt composition. Carry their immutable inputs through one
submission → feedback → explicit acceptance → export path; fix those owners rather
than introduce another evaluator or evidence service.

**Verdict: ready after listed fixes.** The owner’s direction is faithfully represented,
but the exact acceptance recipe is not executable as written, and the first-loop
prerequisites need correction. No P0 finding in the revised design; the P1 findings
below block their dependent slices. This is not an implementation approval.

**Review boundary.** Cold review on `refactor/agent-platform`, observed HEAD
`2d3033ffcc11f7e7a32918e85c737b754cb79deb`. Read the two instruction authorities,
plan README, D1 in full, B1 §3b, the prior review, requested source seams, and the
diffs of `d0ae87f19`, `015ff3afb`, `a29faf5a6`, `38863a0c8`, `215c32d9a`,
`5ec049cfd`. I did not author the reviewed section. References below are checkout
line numbers at review time. `src/seon/test.clj`, `src/seon/fn.clj` and other files
have unrelated in-flight edits; the decisive `select` reuse condition was also
checked in committed HEAD. No JVM, tests, `bin/seon`, runtime calls, push, or changes
to another lane’s files. Source inspection establishes neither installed behavior
nor measured performance. Src/test line delta: **0/0**.

The document write plus automatic Markdown check took 1.7 s; the hook checked
repository citations and reported 45 issues, including stale dependency pins in
unrelated historical landing notes. Those files are outside this assignment and
remain untouched. This is not a repository-wide Markdown-lint pass. The excess
tooling time is repository-check work, not a measured Seon operation or a runtime
performance justification.

Abbreviations: **D1**, **B1**, and **README** refer respectively to
`docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md`,
`docs/prds/agent-platform/plan/lane-b1-one-publication-path.md`, and that directory’s
`README.md`. The earlier review is
`docs/research/agent-platform/astra-plan-review-2026-09-23.md`.

## Owner rulings and reuse

| Ruling | Assessment of §2e |
|---|---|
| Branch-named MCP; create/list/delete | Matches at D1:268–277. A custody agent stays internal; unlink uses the registry. Existing `branch-form` at `script/seon/dev/mcp.clj:545–581` is the extension point. Acquisition is not graph startup (`src/seon/cluster/agent.clj:782–791`); O1a must include §2a’s one-agent arming proof. |
| Same REPL entrance and persistence as inside agents | Matches at D1:279–297. `submit-source!` is the right seam (`src/seon/cluster/agent.clj:606–624,663–698`). The current MCP arm only calls `evaluate` (`script/seon/dev/mcp.clj:513–527`); its deletion is correctly named. |
| Affected tests per change, no whole-system fallback | Matches the intent at D1:357–368. `my.test/check` already sends a named changed request (`src/my/test.clj:18–26`). Findings 1 and 3 qualify the concrete selection/trigger mechanics. |
| Configurable warn/gate, including TDD; merge always gates | Matches the four explicit defaults and branch configuration at D1:311–355,387–393. Test-first is explicitly existence-before-definition, not a claim of red-before-green history. Findings 2 and 4 concern making that ruled behavior possible. |
| Orchestrator is root: diff, accept/send back, merge, write-back on green | Matches at D1:370–396,473–478. Green never auto-accepts. `changed-identities` and `three-way` are reused; `prepare-merge!` and `accept-merge!` remain the owners. |
| Namespace context from the one context system; feedback as data | Matches at D1:299–309. Ordinary task/issue feedback is appropriate. The exact composition inputs need the small clarification in finding 7. |

The principal simplification is already present: MCP transports requests; the agent
path owns evaluation, checks, persistence and feedback. New public compositions
`my.program/diff` and `my.program/context` need no new registry. Diff should compose
`program/changed-identities`, scoped `digest-map` and `three-way`
(`src/seon/program.cljc:578–650`), as prepare already does
(`src/seon/cluster/source.clj:759–764`). Context should call the existing prompt
owner, not concatenate separately selected render output.

New data requirements are the four dials, declared warning/refusal shapes, context
feedback members and request-to-run identity. They are extensions to existing owners,
not reasons for a settings store, warning ledger or MCP dedup cache. Malli compilation
already exists at `reference-code/malli/src/malli/core.cljc:2555–2577`, called through
`src/seon/schema.clj:3690–3707`; compilation consumes a form and supplied registry.
Recompute for changed declarations/schema dependencies, not each invocation. HEAD’s
Malli pin is `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d` (checkout dirty); Datahike
`c79cd03a44427ac1734d917c7484c3e529c77716`, SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`. This review proposes no dependency patch.

## Findings and concrete text changes

### 1. P1 — `select` with the merge delta cannot mean “nothing left to run” today

**Evidence.** D1:386 prescribes a read-only `seon.test/select` on the tested commit,
with the merge delta as `:seon.test/changed`, and requires an empty remaining set.
`src/seon/test.clj:708–729` puts those explicit changes into `reached`; :850–858
requires `(not (reached test-symbol))` before reusing green. A reached required test
therefore remains executable work even after its current evidence is green. This
condition exists in committed HEAD, not just the dirty lane’s work.

The request is also underspecified: absent named identities/namespaces, policy
defaults to `:incremental` (:610–614); a missing baseline can select the entire
population (:698,730–732). Delta alone omits disconnected explicit task tests.
For `:named` changed-only requests, long tests can be removed by eligibility
(:788–789) without appearing in `long-excluded` (:799–808 requires `named?`).
Thus “no exclusions” alone is not a completeness check. Finally, select reads
evidence in its supplied DB; the immutable pre-test S does not contain later run
facts, whereas D1 §2c:168–175 also permits a separate recording authority.

**Replace the recipe at D1:386 with:**

> O3a first makes the existing B4 selector’s current-evidence semantics serve
> acceptance. A named request includes the merge delta, explicit task identities,
> cluster custody and all required eligibility (including declared-long members).
> Selection derives the required set through the existing graph owner and compares
> each member’s content/input evidence; an explicit changed seed does not invalidate
> evidence that already tested that exact content. No required member disappears
> through eligibility filtering. The evidence-bearing value names immutable tested S
> and the same proposal; it is not silently substituted for S’s execution program.
> A refusal is not an empty selection. Only a complete result with every required
> member positively covered and no remaining/excluded member permits acceptance.

Keep that change in `seon.test/select`/its existing evidence owner; do not rebuild
the dependency walk in D1. Retain D1’s failing-subset regression and add a positive
case: the full required set is green, acceptance selection executes/writes nothing.
Include an explicit disconnected task test, a long reaching test and absent evidence.
This extends the earlier review’s finding 1 rather than repeating the old subset bug:
the revision states the right invariant but its newly mandated API recipe defeats it.

### 2. P1 — Warned malformed contracts need one policy across acquisition as well as installation

**Evidence.** D1:345–355 correctly says to preserve malformed contract source without
arming it and to change producer and installer together. It does not say how a fresh
context subsequently acquires those rows. `definition-row` compiles a supplied spec
(`src/seon/sci/eval.clj:448–470`); batch installation adds it to the projection
(:1261–1266); interpreted installation arms any present spec (:1040–1046).
Fresh acquisition uses that same installer and records failed installations
(:2071–2099); an interpretation failure can unmap the function (:1052–1054).
Changing only initial acceptance/transfer can therefore leave a warned definition
callable in its submitting context but unavailable to the next context or test fork.

**Add to O1c:**

> Warn policy separates stored authored contract data from compilable contract
> input at the existing declaration/projection/install owners. The same derivation
> governs initial installation, reacquisition and B4 test forks: a warned function
> remains callable without manufacturing a contract or compiling its malformed
> form. Replacing a valid contract with an invalid one must not retain the old
> wrapper. Repair restores the real wrapper everywhere. Gate refusal precedes
> evaluation and leaves both program rows and callable roots unchanged.

Require one lifecycle regression covering submission, next call, fresh branch-context
acquisition, test fork, repair and merge refusal. Keep compilation errors about the
authored form as declared agent diagnostics; failures inside valid core execution or
recording remain core faults with complete cause and delivery under `AGENTS.md:337–361`.
Do not turn a broad catch around acquisition into a warning conversion. Split O1c
preparation into schema/config declaration and the atomic producer/consumer conversion
if its ≤100-added-source-line estimate cannot hold; do not implement an MCP exception.

### 3. P1 — Durable turn closure is earlier than installation and automatic feedback

**Evidence.** D1:288–297 reconciles durable run/closed-turn facts and requires actual
termination before release, which is a substantial correction. But it does not bind
successful reply completion to the post-settlement work added by O1c.
`src/seon/turn.clj:4970–4997` settles first, then installs, then reports closed/released.
The closed-turn transaction can be observed before installation or the new test request
finishes. A failed install must not look like a successful completed submission.

Additionally, “changed batch” cannot mean only the `installations` vector at
`turn.clj:4976–4989`: `my.program/ns-unmap!` delegates to a writer and immediately
unmaps through custody (`src/my/program.clj:517–567`). Its program change is not
necessarily a new definition row in that vector. D1:285 and :362 promise deletion
feedback, despite deferring wider deletion export.

**Add to O1b/O1c:**

> Closed-run facts prove durable settlement, not installation or test completion.
> Successful submit completion joins the existing owned execution’s actual exit,
> observed installation result and the automatic test request’s terminal evidence.
> Reconciliation reports settled-but-installation/feedback-unconfirmed explicitly;
> it never replays source. A post-settlement core failure follows the ordinary fault
> route. Collect changed identities from the ordinary program writer reports for
> the submission, including direct retractions, and coalesce them once before the
> named test request; never infer changes only from returned definition rows.

Prove disconnect immediately after settlement, an installation failure, and deletion
through `my.program/ns-unmap!`. Listener-before-submit, same-request retry, unknown
outcome on disconnect and release-after-exit are already correctly required; retain
them. At `system-run-call` (`src/seon/turn.clj:712–741`), bind request identity to the
branch incarnation and frozen source/namespace. Same identity with different input
refuses; concurrent identical retries share the admitted run. The existing writer is
the extension point, not a transport cache.

### 4. P1 — The first-loop order defers prerequisites its unconditional gate requires

**Evidence.** D1:387–393 requires every function in the combined program to have a
valid contract and test; missing inherited proof blocks merge. This matches the owner
ruling and must not be quietly reduced to changed functions. Yet README:371–376 records
433 functions without reaching tests, and D1:454–458 postpones additions/deletions until
after the loop. D1:393’s own repair requires adding a test before resubmitting a
function. Current `prepare-merge!` explicitly refuses additions
(`src/seon/cluster/source.clj:772–773`). A size-limited replacement exporter does not
remove that test-addition prerequisite. The dated census is not proof of current gaps,
but the plan supplies no positive current evidence that they are closed either.

**Replace the blanket deferral with:**

> Before the two-branch demonstration, query current inherited contract/coverage
> readiness and name the owner of every missing prerequisite. The unconditional
> combined-program gate stays intact. New reaching tests needed to repair entry or
> TDD warnings are required program work, not optional wider export. Demonstrate
> their branch admission and acceptance before claiming that repair path works.
> Function/schema/new-namespace export can remain deferred. If the narrow exporter
> cannot persist the needed test additions, the first exported demonstration must
> select an already-covered replacement and explicitly record that limitation;
> it does not demonstrate the full TDD repair loop.

O3a remains useful before this population is ready: prove its predicate on canonical
fixtures and let the real merge honestly refuse. Assign baseline repair to the existing
namespace-agent contract/coverage work, rather than add a hidden grandfathering flag
or make the selector run the whole suite. This is a dependency correction, not a
request to weaken the owner’s gate.

### 5. P1 — Partial-install recovery lacks an explicit process-restart admission boundary

**Evidence.** D1:406–418 gives a sound per-file reconciliation rule and keeps reload
closed during a partial install. D1 §2d:245–251 derives recovery from staged/current
bytes and Git/publication state. Neither passage specifies what holds that exclusion
after the JVM or exporter process dies before the complete file-set commit. Ordinary
in-memory admission closure and whole-file lane holds do not survive process death.
The earlier review’s file-set issue is addressed for a live integration; this is the
remaining interruption case, not a request for atomic multi-file rename.

**Add to O4b:**

> Retain the accepted M, expected source base and complete original/desired file set
> through the existing source/Git staging authority before the first live file move.
> On process restart, the existing bootstrap/publication owner reconciles that pending
> accepted export before it loads or admits evaluation against the affected checkout.
> Missing reconstruction evidence leaves affected execution unavailable and the host
> REPL reachable. Do not start from partially installed files or replay definitions’
> effects. Recovery resumes the same export/commit, not a second completion registry.

Name the bootstrap/publication owner in the O4b file assignment. Its proof must stop
after the first of two file installs and resume in a new process, under the separately
authorized platform boundary. Keep narrower caught-I/O-failure recovery in-process.
The existing source owner remains the owner; no D1 journal service is justified.

### 6. P2 — Reconcile active authorities and the cost ledger before handing off slices

**Evidence and concrete changes:**

| Evidence | Required text change |
|---|---|
| D1:461–468 says root instructions still prescribe a shared candidate; `AGENTS.md:257–260` already prescribes named branches. README:402 still has the old shared-candidate clause, while :413 has the new path. | Delete the false “still says” claim. Replace README:402’s clause in place with the named-branch rule; do not leave another superseding appendix. |
| D1:398–404 correctly requires exclusive JVM convergence, but README:413 still says guards only. B1:390 still describes direct SCI evaluation, although B1:424 explicitly supersedes it. README:364–365 has unconditional arming versus D1’s ruled branch warning exception. | Rewrite those owning rows: distinguish atomic DB acceptance from exclusive load/arm/record, make host-eval JVM-only, and scope the warn exception to experimental branch definitions. No new owner decision is needed. |
| D1:477–478 settles automatic path-limited write-back for this path; D1:632–638 still calls that an open owner choice. | Scope the old choice to work outside §2e, or remove it. Never add a second approval step to this path. |
| D1:437 orders O0 as an unfixed producer defect. Working-tree `src/seon/fn.clj:262–267` already suppresses the `:as-alias` load edge, in another lane’s dirty file. | Change O0 to verify/adopt the owner’s correction, with its commit and acquisition evidence still owed. Do not launch a competing edit. |
| D1:442–446 assigns acquisition, installation scan and acceptance costs, but the cited evidence also records prepare at 3,837 ms, including a 3,003 ms run (`docs/research/agent-platform/outside-agents-process-2026-09-23.md:36–37`). | Put those numbers in O3a/B4’s row. Separate selection, admission, bodies, recording and release; assign non-body overhead <1 s and changed/reached work, preserve numeric body bounds and report any >1 s operation with its reason. O1d context and O4a analysis need explicit phase targets too, within the end-to-end leaf budget. |

The O1/O2/O3/O4 decomposition names deletions and mostly reasonable owner boundaries;
line caps are estimates, not proof of small loadable slices. O2b must wait for O3 just
as O1c does (D1:456 says so; make that dependency explicit in the critical-path row).
O4b cannot silently absorb missing B1 convergence or bootstrap machinery within its
100-line estimate. Reprice that owner seam before code if the existing boundary does
not supply it. Broad selected bodies are an honest exception to a universal sub-second
promise; repeated whole-program overhead is not.

### 7. P2 — Name the exact context composition rather than leave its identity implicit

**Evidence.** D1:299–309 promises exactly the context a Seon agent receives from a
branch execution value and namespace. `src/seon/context.clj:15–18` identifies the
prompt owner; the actual `seon.cluster.prompt/prompt` takes a database plus an
agent request (`src/seon/cluster/prompt.clj:419–435`). Its selection uses agent identity,
budget, calibration and profile (:296–320,360–417). The namespace renderer alone
returns source, and changes its output when an assigned agent exists
(`src/seon/render/ns.clj:795–826`). Namespace alone does not identify the same prompt.

**Add to O1d:**

> Use the branch’s internal custody agent and ordinary prompt request, with explicit
> namespace, immutable database, selection inputs and render profile. Delegate to
> `seon.cluster.prompt/prompt` and its existing render/acquisition path. Return that
> rendered-context value and its basis/contribution evidence; feedback references
> those inputs. If namespace assignment needs changing, use its ordinary writer
> before context derivation, not a hidden mutation in the context read.

Prove equality with the inside-agent result under identical inputs. Do not build a
parallel context out of `render-ai` plus handwritten instructions, or add a new
context-version counter when the existing basis and contribution hashes suffice.

## First implementation slice

**O3a at the existing test-selection/evidence owner, after its current file holder
releases it.** First reproduce the selector behavior with one already-green changed
function and two reaching tests. Make content-valid evidence reusable for the complete
named merge obligation, including explicit task/long tests; then replace accept’s
hand-built subset check with that owner. Preserve H’s writer fence and immutable
proposal binding. Delete the superseded `uncovered` loop in the same loadable slice.
Do not activate warned definitions until this acceptance proof passes.

O0 is a verification dependency on another lane’s already-visible correction, not
the first new implementation. Independent O1a/O1b preparation can proceed when its
files are free. After the fixes above, the small path remains the right path: one
branch handle, one source run, one affected-test request, one explicit accept, one
controlled export. The full proof remains owed; this review supplies text corrections
and source evidence only.
