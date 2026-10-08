---
title: "From an agent branch to persisted, running code"
type: research
status: proposed; installed probes distinguished from design
created: 2026-10-08
lane: merge-path-sol
---

# From an agent branch to persisted, running code

What exists is a declaration merge in Datahike and a separate, proven file writer. The smallest composition keeps
`seon.program/three-way`, exact declaration replacement and the file proof, and makes root's merge task carry the
change through Git and adoption. Knowing which declarations changed makes the semantic comparison small; it does
not make a multi-file install, Git commit and JVM reload one atomic transaction.

Recommendation: root reviews a kept merge branch, records which new tests survive, proves its final declarations
and file tree, commits the Datahike merge, writes those exact files and commits them, then reloads or joins root's
restart batch. Persist each completed effect on the same merge request. The agent's next context shows both the
merge and which committed program is actually loaded. A successful database merge alone is not running code.

## 1. Blockers, in order

| Rank | Blocker | Existing work to finish or extend |
|---|---|---|
| 1 | Acceptance closes the merge task before file installation, Git commit or adoption. There is no durable continuation through those effects; a new namespace has no write-back destination. | Extend the one merge operation in MA-11, its target/home recovery in MA-14a and TX-6; retain the file proof from RF-1. Detail the persistence additions in §7 rather than inventing another merge path. |
| 2 | Root cannot review another request through ordinary `diff`, keep a conflicted merge branch, or record the new-test decision. Acceptance gates proofs and coverage, allowing other red tests. | MA-4, MA-3, MA-5a/b, MA-10, MA-11; add test retention to MA-11's review, under owner-rulings §5. MA-12 must stop for root when that decision is missing. |
| 3 | The new archive is not the running JVM's classpath. Cached AOT classes can make `require :reload` load old code. The SCI base holds copied function roots. | RED-5 before PB-03; the archive adoption option in hostbound-opus decisions 4/5. Use restart batches until that adoption route is proven. |
| 4 | An agent's green branch can prove the wrong body: new functions have a merge arming gap, schemas cannot enter the comparison, and compiled platform execution is outside branch interpretation. | MA-0/1/2, RF-3 and the hostbound note's isolated proof at merge; MA-6/7 for merged integrity. |
| 5 | Ordered norms in one transaction do not fit the installed norm runner unchanged. Planning them all against one database also loses sequential meaning. | MA-8a/b/c and FK-DH, with the exact gaps in §6. MA-9 must keep root's repairs while incorporating a moved target. |
| 6 | Small results still cost large allocations. Two focused tests passed but their request allocated 3.88 GB. Diff scopes digest reads but still walks history and materializes ancestry. | MA-16a, then rows per measured owner; existing `seon.test/a-one-test-proof-check-allocates-a-gigabyte`, `seon.db/the-checked-writer-is-slow-on-a-fresh-branch`, and DIAL-8. No guessed new performance target. |

Authority: docs/research/agent-platform/2026-10-08/review/owner-rulings.md (all, especially §5), AGENTS.md,
docs/seon/specs/merge.md, and docs/seon/schedule/agent-loop-ledger.md §3.10. The earlier
docs/research/agent-platform/2026-10-08/redo/merge-acceptance.md is useful history, but its proofs-only acceptance,
`no-new-red`, set of conversions, in-writer agent functions and rebase are superseded. Current targets are every
selected test green, ordered norms, no agent code on the writer thread and merging the moved target into the kept
merge branch. The MA-8c ledger/FK-DH card still disagree about where norm code runs (§6).

This is independent research. merge-path-opus.md was not read. No production source, task resources, cards or issue
index were edited.

## 2. What exists today, step by step

Source inspected at checkout HEAD `1cfa1dd3540394d3299e6843efc2d9e7f767d474`; subsequent unrelated commits may advance
HEAD. The running archive was `e3b7bae3a4ab7b8dedb1e43a90fd1410b138e4db`. The requested `24e8cc2df` adds
`my.program/write-branch!` by composing the existing comparison and writer; it creates no Git or reload step.

| Step | Owner and actual behavior | Evidence and limitation |
|---|---|---|
| Agent change | SCI records declarations on the agent branch; ordinary effects and request recording are distinct writes. | A new contracted function called successfully: 3 ms definition, 0 ms call. Default's program was unchanged. |
| Request | `my.program/propose-merge!` reads the assigned task at its home, runs its proofs, verifies them, calls `seon.task/propose!`, then separately sends root a message. It does not take ordered norms or an explicit target. | This lane has no assigned product task: refused unassigned in 4 ms. Successful historical request: 0.46–1.05 s in merge.md §8; not remeasured here. |
| Request list | `my.program/proposals` queries the supplied database, not unconditionally the task home. An older reviewer branch can miss requests. | This lane saw `[]` in 4 ms, 2.4 MB, 190 calls. This does not prove the home's request list was empty. |
| Compare | `seon.cluster.source/compare-branch` reads the source's current commit, materializes its merge base with the target, gets changed source identities and three scoped digest maps, then calls `seon.program/three-way`. | Two added identities: 46.284 ms in a JVM read. The request's recorded source commit is not the comparison argument: a newer source tip can change what preparation tests. |
| Root review | `my.program/diff` and `my.program/declaration-diff` compare the viewer's own branch with its cluster. They do not take another request. There is no installed test-retention decision or review operation. | Own diff works. Installed `my.program/merge!`, `my.program/checkout!`, and `my.program/request-merge!` were absent. |
| Test merge | `seon.cluster.source/prepare-merge!` derives replacement tx-data, optionally proves files using `seon.db/with`, then uses `seon.cluster/candidate-gate!` to create a disposable merge branch and run reaching tests/proofs. Conflicts return before that branch exists. Reds can leave an unnamed branch to recover manually. | Historical: 1.7 s warm with one proof, 2.8 s with three tests; first 8.7 s. This lane did not prepare or accept a real request. New-function arming and test-only namespace defects remain tasks in MA-0/1. |
| Accept | `seon.cluster.source/accept-merge!` checks the named run, green proofs, unchanged tested program, contracts, coverage and admitted task references. `declarations-held-tx` checks only carried identities' expected target digests. `seon.db/transact!` with `:parents` uses Datahike's merge writer. | No all-reds gate, general dangling-caller check, complete schema compatibility or norms. An unrelated new caller on the target can invalidate the tested merge without changing a guarded row. MA-6/7/9/10/11 own these gaps. Historical acceptance: 1.78 s; red-proof refusal: 135 ms. |
| Close and cleanup | `my.program/accept-merge!` merges onto its context's connection, separately records the task accepted, then submits detached branch retirement. | No explicit request target; home can report accepted while the merge landed on a reviewer scratch branch. Hosted source retirement can refuse. Existing MA-14a, MA-15, TX-6. |
| Files | `my.program/write-back!` calls `seon.cluster.source/write-back!`. `proven-files` groups identities by their before-state file; `proven-file` checks file digest/spans and reindexes proposed text. `install!` uses a sibling file and atomic rename **per file**. | Unchanged single-file proof: 66.501 ms. Existing-file branch write-back test passed. New namespace write-back refused in 44 ms because no file was recorded. Partial multi-file installs are explicitly possible. |
| Git | No Git commit in either write-back function. Files remain checkout changes. `bin/lane commit` is the installed outside-lane commit/gate; it is not a product agent's integrated merge operation. | This note uses that gate. No product merge→Git timing was measured because that step does not exist. |
| Persisted file facts | Write-back answers file digests and changed paths; its docstring leaves publication to the publication owner. The target's old file spans/digests are not refreshed by the rename itself. | Until publication/reindexing advances file provenance, a second write-back can use obsolete file bases. Definition digest deliberately excludes positions/provenance, so this update need not change program meaning. |
| JVM adoption | `seon.cluster/load-development-definitions!` uses ordered `require :reload`, but `verify-development-sources!` checks the loaded source directory. Default boot pins an immutable archive. | Loading another Git commit's bytes needs an archive-aware adoption route or `bin/seon start --head`. Neither write-back function calls one. |
| Next context | The next evaluation reacquires the branch's program; historical results retain their shown text. A platform change requires loaded roots and the shared SCI base to advance. | `seon.context/current-state` showed this lane's turns, no assigned task. There is no proven merge→Git→loaded-commit context presentation here. MA-12/current-state consumers need those facts. |

Dependency pins checked against both gitlinks and checked-out HEADs: Datahike `86c3301a00de8c49b2690083b2b1e69677bf0c8d`,
clj-reload `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa`, Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`,
rewrite-clj `60782e501aaf312cb90c9ff0bee05d5da5125563`, Malli `418df4417827b66846b799b5e87267a344dffc29`.
`datahike.versioning/merge!` serializes supplied tx-data and records supplied parents; it does not compute a semantic
diff. Seon's actual caller is `seon.db/transact-call` → `datahike.api/merge-db!` when `:parents` is present.

## 3. Efficient diffs and patches

### Declaration comparison

Keep the database value as the semantic diff: identity → base/ours/theirs digest, with absence an explicit state.
`seon.program/three-way` does one fold over the union of supplied identities. Equal ours/theirs is unchanged,
including equal additions and deletions; theirs=base means ours changed; ours=base means theirs changed; otherwise
the identity is unmerged. A rename is deletion plus addition; no guessed rename heuristic is required for correctness.
Root may inspect the old/new bodies to understand it.

Compare the **pinned request commit**, the merge base and the **explicit target**, then include target-only changes
as behind. Today's `compare-branch` scopes to changed source identities, so its changed-on-head section cannot show
all target-only changes. It does not currently supply the full diff promised by merge.md §2. A source update changes
the request in place; an in-flight review of earlier bytes stops as superseded. Store/root's reviewer branch and the
task home are separate explicit inputs, not inferred from whichever branch root holds.

Costs follow:

- ancestry visited to find the merge base, plus source/target changed identities;
- stored digest lookups for those identities; no pulling every function body and no digesting the program again;
- affected callers and tests, including target callers of deleted functions and references whose resolver meaning
  changed; schema checks follow the target values using changed schema keys, and must finish or answer unknown;
- test bodies and their actual dependencies; unchanged positive evidence can be reused only under the ordinary
  current-evidence rules, including runner and input identity.

This is the target algorithm, not a claim that current history reads are change-proportional.
`seon.program/changed-identities` queries a since-filtered history range of definition digests. Its own docstring
says Datahike has no transaction-prefix index, and its scope excludes schemas. `seon.cluster.source/merge-base`
materializes each visited commit just to read its parents. MA-16a should measure these independently; use a maintained
Datahike seam for commit metadata or change iteration if the profile proves the need. No second Seon ancestry store
or change journal. DIAL-8 removes the hardcoded comparison/page bounds; a bound refusal stays unknown, never empty.

### File patches

A declaration patch and a Git diff do different jobs. Proposed data, **not an installed schema/API**:

```clojure
{:identity [:seon.fn/sym app/f]
 :base-digest "…" :ours-digest "…" :theirs-digest "…"
 :file "src/app.clj" :file-base-digest "…"
 :span [start-byte stop-byte] :source "(defn f …)"}
```

The declaration identifies the reviewed change. The file digest and byte span locate its old text in one immutable
file base; replacement source is the reviewed declaration's source. A deletion has no replacement source; an
addition has an explicit destination and insertion position. Apply all edits against that same base in order, then
prove the entire resulting file's declaration identities/digests. Source pairs are read on demand. This is the
existing `spliced`/`proven-file` composition, extended to explicit namespace spans, new files and deleted files.
The namespace form currently has no stored span: `proven-file` finds its unique text occurrence. Use the ordinary
analyzer/rewrite-clj's locations rather than expanding that string search into a code parser.

Git hunks are generated **after** the file proof for human review/history and transport. Their line context neither
proves declaration identity nor authorizes fuzzy application against newer bytes. If another owner changed the file,
reindex that actual base, redo the three-way comparison and prove a new patch. Do not silently apply stale offsets.
Two agents adding separate functions to one existing namespace merge cleanly unless the namespace declaration itself
also conflicts. A changed namespace/alias can change other declarations' effective meanings: the producer must expose
those changed digests, not treat the namespace as harmless formatting.

Byte copying and analysis follow the **touched files' lengths**, not only changed declarations. Namespace compilation
follows touched namespaces and required dependents. Norms follow converted data. These are necessary exceptions to
"changed declarations only"; eliminating whole-program work does not eliminate reading an entire changed file or
running tests its change reaches.

### One authority at each moment

| Moment | Authority | Derived representation and required check |
|---|---|---|
| Authoring | agent branch's declaration entities | files are untouched; source commit pins what is offered |
| Review/repair | kept merge branch's final declarations | file tree is derived and reindexed; keep/fold/drop changes participate in its digest/test evidence |
| Datahike accepted, files/Git pending | target's durable merge commit and persisted request intent | exact proved file outputs are recoverable; boot must not silently overwrite pending declarations with the older archive |
| File install, Git pending | same durable accepted commit | file outputs are staged derived bytes; partial installation is visible and resumable, never a completed landing |
| Git committed | immutable Git tree whose declarations were proved equal | publication updates file links, spans and digests without changing those declarations; record the Datahike↔Git correspondence |
| Adopted | explicit loaded identity = proved archive + loaded declaration identity | JVM roots, contracts, configuration and shared SCI base agree before readiness is published |

There is no cross-system atomic commit. Before advancing the target, keep durable inputs sufficient to resume the
file/Git work: explicit Git parent, target/source/merge commits, selected identities, file bases and proved outputs
or content references. Seon's existing request is the owner; no new coordinator store. Each effect checks its
precondition and recognizes its already-completed output. A crash after Git commit but before its database record
is recovered by inspecting the recorded parent and exact tree/merge-request reference, not by committing twice.

Serialize root landings that write the same files. Use exact owned paths; never include another lane's dirty file.
The current pre-install digest recheck followed by rename is not a filesystem compare-and-swap: cooperative file
ownership matters. Preserve an unexpected file and stop. Prepare/reindex **all** touched files before installation;
atomic rename still does not make their set atomic. Keep accepted commit/proof inputs retained until persistence
and adoption are evidenced, even if a branch's lifecycle ends. Dropping the evaluation pointer must not drop the only
record of the accepted source before the file becomes its record.

## 4. Root's review, including tests to keep

Root should receive the task and inspect it in one evaluation per question. The initial diff shows counts and
identity/digest entries, unmerged/behind, dangling callers, schema compatibility, current test results, host proof
gaps, ordered norms and persistence/adoption state. It offers the next ordinary forms. It does not eagerly print the
program or every test source. Another evaluation reads a declaration's three sources or a test's source/results.
The value printer owns the one display budget; the result handle holds the complete value.

Target interaction, **forms below are design, not installed**:

```clojure
(my.program/diff {:seon.merge/request request})
(my.program/merge! {:seon.merge/request request :seon.merge/no-commit true})
(my.program/diff {:seon.merge/request request} 'app/f)
(my.program/checkout! {:seon.merge/request request
                       :seon.merge/side :theirs
                       :seon.program/identities #{[:seon.fn/sym app/f]}})
;; With the merge branch checked out, repair by ordinary in-ns/defn/ns-unmap!.
;; Root records the test decision, with its supporting existing test named.
(my.program/merge! {:seon.merge/request request})
```

The review branch exists even after a conflict/red; root's assignment selects it. The request's target remains fixed.
Main/home queries for requests use the home explicitly, regardless of root's checked-out branch. `merge!` resumes
the same branch after repair; a moved target merges into it, preserving resolutions. MA-4/3/5/14a already cover these
behaviors; do not build a second review evaluator.

Owner-rulings §5 adds a concrete review decision for **each added deftest**:

| Decision | Root records | Effect on the merge branch |
|---|---|---|
| Keep | identity/digest, behavior class and why no existing test covers it | keep the new test |
| Fold | added identity/digest, existing table-test identity, behavior/case being added | add the case to the existing test and remove the duplicate deftest |
| Drop | added identity/digest, existing test that already proves the same behavior, reason | remove the duplicate, retaining the existing proof |

This is a human/agent judgment about behavior, not an assertion-count or source-similarity algorithm. Show related
tests as discovery help; root reads their actual assertions. Prefer tagging an existing failing test or adding a
table row from the beginning. Task count is never a reason to keep a new deftest.

Record the decision on the request against the reviewed digests. A changed test invalidates its decision. Applying
fold/drop changes the program: rerun the **final** selection and regenerate file patches. Keep the source branch's
proof run as historical evidence, but it cannot certify the rewritten final test. If a task's only success test is
dropped, transfer the task tag to the existing/folded success test and run it; never leave a proofless task or silently
waive its proof. The target's admitted task/reference checks still apply.

Automatic merge cannot invent this decision. Simplest policy: a request adding a deftest stops for root until the
decisions are recorded; requests adding no new tests can continue under the existing automatic-merge policy. Root
may later approve a standing retention policy, but behavior equivalence is not presently computable from a digest.

The merge run selects proofs ∪ tests reaching the final merged change ∪ root's chosen tests, as merge.md §4 states.
Every selected member needs current positive green evidence; pending, unknown, a recording failure or a red stops
the merge. "Every test green" must not secretly mean only proofs, or become a whole-suite rerun per declaration.
A known main red, marked at the actual merge base with evidence, stops automatic merge for root. Root can fix it,
delete a test whose behavior truly disappeared or arrange its owning work; no root exception turns that red into
green. If the intended rule is literally every installed test's current evidence, rather than every member of the
specified merge run, the spec must say so and name how unrelated unknowns are acquired (§6 options).

## 5. How persisted code reaches the JVM and the next context

Today `require :reload` resolves the old archive through the classpath. Writing the checkout does not change that
classpath. Clojure's `clojure.lang.RT/load` compares class/source resources and can choose the newer `__init.class`.
PB-03a measured classes dated 2100 and reloads of 0.3–1 ms with no root replacement. This lane's JVM probe found
`seon/id__init.class` in `target/dev-program-classes/3b9f8dc7d2459d516778385e2ac4b948b70f918ecd651dadaf136e1a287848dc/`.
Unlike the earlier hostbound study, AOT classes are present on this default. Presence proves exposure to RED-5,
not that a changed-source adoption failed here: no shared JVM reload was attempted.

The dependency seam is `clj-reload.util/ns-load-file`: it calls `clojure.lang.Compiler/load` on supplied source text,
bypassing RT's class/source selection. `clj-reload.core/ns-unload` removes the namespace before reload; that changes
Var identity and is hazardous for retained Flow transforms, listeners and base bindings. Do not simply invoke full
clj-reload and assume retained holders are safe. Compose its ordering/explicit-source mechanisms, preserve existing
Vars for ordinary function-body replacement, and prove removals and dependents. The installed Seon reload owner
already distinguishes Var-indirected functions from definitions compiled into callers.

| Change | Route and proof | Cost and sacrifice |
|---|---|---|
| Ordinary function bodies, docstrings and safe namespace additions/removals | Proposed: load the proved **new archive** through explicit-source `Compiler/load`, in dependency order, then re-arm changed contracts and advance the shared SCI base; preserve private agent definitions at idle boundaries. | Work follows changed namespaces and necessary dependents. PB-03a source compilation: 30–1,613 ms/7–175 MB per namespace; base recopy 0–3 ms. Those are another lane's measurements, not this lane's adoption timings. |
| Macros, inline definitions or captured function values | Reload affected compiled dependents as well; only select in-place adoption when those holders and lifecycle consequences are proven. | The closure can be large. A body-only reload does not replace functions embedded in callers or values captured in `defonce`. |
| Types/protocol/class identity, dependency pins, classpath, boot/lifecycle machinery or unproven reload effects | Root's committed-archive restart batch, after isolated proof. | Historical hostbound restart: 43.5 s total, private SCI values lost. This lane did not restart default; current downtime is unmeasured. |
| Schema/config/task resources | Rehearse publication/adoption on a disposable live-store branch through the same owners; restart when the boot path consumes them or in-place acquisition is not proven. | Norm/schema validity and consumers must be proven together. A source-body reload does not publish resource changes. |

Before recording loaded identity, verify **behavior** from a changed function, the actual source/archive and armed
digest. A list of namespaces said to be reloaded is not proof. Mark affected execution unavailable if loading or
arming fails; preserve the evaluated value with the diagnostic. No restoring old Var roots after concurrent work.
Restart is the available route until RED-5 and archive-aware adoption are proven.

PB-03a also measured a base rebuild at 120–153 ms/246–248 MB versus namespace recopy at 0–3 ms. The base copies
function root values, so a successful JVM reload can still leave SCI calling the old object. PB-03 advances the
base by changed namespaces; the agent's next acquisition brings those changes into its retained private context.
Loaded-program identity at evaluation start/end protects work overlapping adoption. Refresh only reads whose
content evidence changed; historical shown values remain historical. A restart says private values are gone.

The source agent's current-state block should distinguish: database merge committed; Git commit/tree recorded;
adoption pending/in root's batch; loaded archive and declaration identity; adoption failure if present. Derive these
from the request and adoption facts, not a second status store or a message's wording. A close/message wakes the
agent, while its next context reads the current facts. An agent waiting for persistence must not mistake its own
branch already evaluating a new function for the shared platform running it.

## 6. Square pegs and owner choices

These are choices still needing agreement, not requests for this research lane to implement production changes.
Already-ruled choices (git vocabulary, root review, ordered norms, green merge, incremental base) are retained.

| Question | Options with guarantee, cost and sacrifice | Recommendation |
|---|---|---|
| Datahike/Git/files cannot commit atomically | **A:** database first with durable continuation on the request; permits current root semantics, costs recovery at each file/Git boundary. **B:** stage/prove the Git tree first and record its identity in the guarded database merge, then advance the Git ref; less byte-recovery ambiguity, but an unreachable staged Git object must be retained and norms/data cannot be rolled back by deleting it. **C:** require files/Git first as authoring authority; simpler Git workflow, sacrifices the ruled REPL authoring model. | A, with all files proved before the database merge and accepted inputs retained. Do not promise atomicity. B is a useful later Git-object staging optimization after A's recovery contract exists. |
| New namespace has no file, schemas have no function span | **A:** the namespace/schema owner declares an explicit destination and resource form; root confirms it during review, creates a proved new file and indexes it. **B:** root chooses destinations manually for each such request; minimal implementation, poor smoothness. **C:** infer filenames from namespace conventions everywhere; fewer prompts, but ambiguity for tests/resources and multiple source roots. | A; B is the smallest interim behavior. Do not impose a second convention-based source resolver. |
| Ordered norms and one guarded merge vs installed Datahike norms | **A:** extend the Datahike fork seam to fold pure value→tx-data norms over successively simulated values outside the writer, return one tx-data vector, then fence its starting target at commit. Needs library tests and complete validation; no agent code on writer. **B:** one transaction per norm, calling installed `ensure-norms!`; preserves current library mechanics but exposes partial conversion/program states and violates one merge transaction. **C:** compute norms on the writer thread; consistent data by serialization, but arbitrary agent work blocks it and conflicts with the current spec. | A. Revise MA-8c/FK-DH wording before implementation. |
| Multiple norm records and norm identity | **A:** maintained fork supports several norm markers in one transaction and immutable version identities tied to source digests; costs a native representation/schema change and consumer tests. **B:** one norm per merge; cheapest reuse, sacrifices the owner's ordered-vector ruling. **C:** concatenate installed markers unchanged; cheapest code, loses all but the final marker on a cardinality-one transaction entity and admits symbol/keyword mismatch. | A is the exact library gap; stop and resolve it in FK-DH, not a separate Seon norm store. |
| Reload granularity | **A:** keep restart batches for everything; available and fresh-class semantics, costs downtime/private values. **B:** explicit-source in-place adoption for proven reloadable changes, restart the rest; costs RED-5, archive provenance, holder/Var tests and PB-03, preserves ordinary private state. **C:** full clj-reload unload/load; library handles deletion, but retained Vars/Flow/base holders require restart/rebuild of those holders. | B as the direction; A until its proof exists. No current in-place success is claimed. |
| Every test on the branch vs incremental merge selection | **A:** use merge.md's selected run, all members green, and stop automatic merging for known main reds; cost follows dependencies, requires complete selection and positive evidence. **B:** demand current evidence for every installed test; maximal census, but unknown unrelated evidence blocks merges and many tests run after any shared dependency changes. | A matches the existing specified selection. If owner means B, revise MA-11's selection/evidence contract explicitly; never quietly relax greens. |

Two exact norm seam problems are visible without a live merge: `datahike.norm.norm/transact-norms` transacts each norm
separately and calls a function with a **connection**, while the target norm is pure database value→tx-data.
`ensure-norm-attribute!` declares `:tx/norm` keyword/cardinality-one. The proposed FK-DH card's concatenation of
several `{:tx/norm ...}` markers into one transaction cannot retain them all on that transaction entity unchanged.
Its proposed fold also passes the original database to each function, so a second norm cannot see the first's output.
`datahike.norm.norm/pending-norm-tx-data` was absent both from checked-out source and the installed namespace inventory.
Do not claim that seam exists or implement it locally in Seon. Pure norms may be recomputed on rehearsal/target
movement; "once" means one committed installation marker per norm identity, not one function invocation ever.

## 7. Rows/cards implied, without another schedule

Root owns the ledger. These are amendments to existing rows or proposed bounded follow-ups, not edits to its index.

| Existing owner | Required completion/detail | Proof to use or add when production work is assigned |
|---|---|---|
| MA-4 + GV-6 | Diff pins the request's source/target and exposes base/ours/theirs plus target-only behind; one declaration lookup does not recompute the full diff. | Existing named MA-4 proof; add a row for a target-only change and a superseded request. |
| MA-3/5a/5b/14a | Keep merge branch, explicit target/home, ordinary REPL resolution and moved-target preservation. | Existing row proofs; root's checked-out branch must not select the destination. |
| MA-11 + owner §5 | Add keep/fold/drop decisions to the reviewed request, apply them to the merge branch, require current greens for the resulting test identities/digests; root's isolated run proves bodies the branch could not execute. | Extend green/red table with duplicate deftest folded into an existing test and new behavior kept. Use the existing hostbound isolated-run proposal, not another platform classifier. |
| MA-12 + CS-01d | Missing test-retention decision or red-at-base stops automatic merge for root; source agent reads persisted/Git/loaded facts. | Extend automatic-merge and current-state tests, including unknown evidence. |
| MA-8a/b/c + FK-DH | Sequential value fold outside writer, native multi-norm markers and immutable norm identity, starting-tip guard. | Existing ordered norm proofs plus fork rows for norm two reading norm one's result, both markers retained, replay contributes nothing and changed norm identity is explicit. |
| MA-9 + TX-6 + MA-14a | A stale target/source never accepts earlier review evidence; target commit/home recovery is idempotent. | Existing moved-target and transaction-count proofs; preserve repaired definitions after target update. |
| MA-11 persistence follow-up, using RF-1's writer | Durable file/Git continuation; per-file base and exact output recognition; update file provenance after reindexing; new/deleted file support at its namespace/schema owner. | Extend write-back entrance tests with two sequential landings and new-file cases; add one root merge recovery behavior table for interruption after a rename, after Git commit and before adoption. These are new behavior classes, not new tests per interruption point. |
| RED-5 + PB-03; hostbound archive adoption | Source compilation bypasses stale AOT selection, preserves intended Var identity, arms changed definitions, advances base/private contexts, otherwise restart batch. | Existing RED-5 and PB-03 proofs, with a new archive path and changed function behavior; add a private-definition preservation row. |
| MA-16a + existing allocation tasks + DIAL-8 | Separate comparison, patch proof, branch/test setup, writer, Git and adoption timing/allocation. Measure no-change and one-/many-declaration cases; read only changed content where the dependency provides it. | Reuse the merge cost probe and existing allocation task; work counts distinguish reached data from whole-program work. Do not add arbitrary timing ceilings or a second profile store. |

## 8. Reproducible installed evidence and landing limits

All MCP calls explicitly used agent `merge-path-sol`, cluster `default`. SCI used its own branch
`:agent-07ca30cc0d5f`; JVM probes passed `(seon.cluster.boot/connection "default")` and read only default.
No real request was accepted, no default reload/restart was attempted, and no working source file was written by a
probe. The focused write-back test owned its disposable fixture files/branches and cleaned them in its `finally`.

Initial `bin/seon status` and MCP `runtime_status`: default faulted, 6 open faults, 51 errored recordings; heap live
635,662,224 bytes, max 4,294,967,296; cache about 235 MB/512 MB. Existing memory-pressure, render-observation and
recording failures prevent calling default healthy. Readiness was 15,122 ms. This is an observation of readiness,
not a measured restart/downtime. Runtime health names `seon.sci.kernel/acquire-arm`, `seon.render/unknown` and
`seon.turn.world/record-fault`; their errors predate the lane. The current-state renderer returned acquisition
plumbing around its small value. These are producer findings, not acceptable review output.

Exact SCI forms, in separate requests where indicated:

```clojure
(my.program/diff)                         ; first: 147 ms, 343.6 MB, 766 calls; empty
(my.program/diff)                         ; later: 45 ms, 71.4 MB, 791 calls; empty
(my.program/proposals)                    ; 4 ms, 2.4 MB, 190 calls; []
(my.program/declaration-diff 'seon.id/evaluation) ; 26 ms, 33.0 MB, 918 calls; equal source pair
(my.agent/identity)                       ; id "merge-path-sol"
(my.agent/branch)                         ; branch above, mode :live
(seon.context/current-state (seon.db/db) "merge-path-sol") ; 7 ms, 4.5 MB, 704 calls
(ns merge-path-sol.probe)
(defn plus-one "One greater than x." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
(plus-one 4)                              ; 5
;; Next request: source is committed before comparison.
(my.program/diff)                         ; 309 ms, 175.6 MB, 4,748 calls; two additions
(my.program/declaration-diff 'merge-path-sol.probe/plus-one) ; 48 ms, 42.3 MB, 928 calls
(my.program/write-branch! {:seon.fn/root "/Users/sean/src/seon/tmp/merge-path-sol"})
;; 44 ms, 46.4 MB, 1,259 calls; "Each change needs its source and one file."
(my.program/propose-merge! {})             ; 4 ms, 1.0 MB, 346 calls; unassigned
;; Two later unchanged diffs: 30/27 ms, 74.7/67.4 MB, 760 calls each; same two additions.
(my.test/check {:seon.test/changed
                ['seon.program-test/three-way-classifies-every-base-branch-head-state
                 'my.program-test/write-branch!-writes-my-defn-into-its-file]})
```

Diff entries: namespace digest `e51eb5a2e6efb41cc9b300d7328e3667b297b53b9e030bca0cb82c6de405cd52`, function digest
`7702298836d3e763eced29bbbc0ae262ccea3f413a35a0fa746a0d2a92ce8ba7`. Direct JVM comparison returned source
commit `6ac759d7-9f79-5066-ae73-1e3478fdcff8`, target `6ac759f8-5f6f-551c-8ee4-32ad7af52623`, merge base
`6ac75953-3004-5e0e-ba4c-7e48ac871b81`; both identities added, no conflict. Exact JVM form:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection)
      instance (get @@(find-var 'seon.operator.runtime/running-instances) "default")
      started (System/nanoTime)
      compared (seon.cluster.source/compare-branch
                 (:seon.store/store instance) :agent-07ca30cc0d5f database)]
  (try
    {:ms (/ (- (System/nanoTime) started) 1000000.0)
     :changed (:changed compared) :three (:three compared)}
    (finally (seon.cluster.source/release! [(:base compared) (:branch compared)]))))
```

Other JVM read probes:

```clojure
;; Each let used connection/database as above; timings surround the named call.
(seon.program/changed-identities database database {:seon.program/max-datoms 10000})
;; 10.221 ms, #{}; same commit still goes through history today.
(#'seon.cluster.source/proven-file database database
  #{[:seon.fn/sym 'seon.id/evaluation]} (seon.fs/source-directory))
;; 66.501 ms, proof for src/seon/id.clj, one selected row; no installation.
```

The initial installed-symbol probe used `find-var` on an unloaded norm namespace and returned
`IllegalArgumentException "No such namespace: datahike.norm.norm"` in 3 ms. It was corrected to guard with `find-ns`
and `ns-resolve`; absent target symbols were then confirmed, without requiring anything into default. That initial
error is this probe's error, not proof of a broken norm function.

Pure three-way conflict probe: base/branch/target digest maps for identity `[:seon.fn/sym app/f]`, with 64 repetitions
of a/b/c respectively, returned exactly that conflicted identity and all three digests in 1 ms/0.1 MB/1 call. The
existing exhaustive state-table test provides the meaningful broader proof.

Live test run `bf6d88ebc66e`: 2 executed, 0 reused, passed true, 130 assertions, no failures/errors.
Write-back body 1,810.144 ms, acquisition 16.875 ms; three-way body 26.867 ms, acquisition 16.108 ms. Total request
3,779.569 ms, envelope 3,780 ms/3,884.6 MB/168,106 calls. The raw result was estimated 4,187 tokens and shown at
1,302. That is a slow/large-allocation finding, not a reasonable cost for this work. The request reported
`seon.db/with-declarations` 1,238 ms/636 calls and `seon.schema/contract-references` 470 ms/3,471 calls; repeated
projection/schema work is evidence for the existing allocation task, not a proven sole cause. Member profile
growths are inclusive and explicitly include concurrent work; they are not isolated allocation attribution.
No repeated large check was run to make a better-looking number.

Automatic replies repeatedly showed `{:seon.test/passed? false :seon.turn.loop/red []}`. That summary is not positive
proof of the tests running or passing. The explicit run's completed members are the proof used here; the summary
friction is already recorded in the hostbound study and F4/current-state work.

Verification: `clj-kondo --lint src script test --cache false` completed in 7,743 ms with **0 errors, 1,484 warnings**
(exit 2 for warnings). Zero findings is not claimed. Shared uncommitted SCI/test/turn/web and schema changes were
foreign boundaries; they are excluded by the lane gate's HEAD-plus-owned-path archive. This lane owns only this
Markdown note, adds no source/tests, and has no production-reaching tests. For the requested throwaway gate, name
only the two already-passing vars above, never their entire namespaces. Historical reds in
`seon.cluster.write-back-test`: `a-merge-writes-each-change-into-its-own-file`,
`a-require-added-at-the-repl-merges-and-writes-back-its-ns-form`, and
`an-agent-replacement-merges-and-writes-back-its-exact-span`; all fail in prepare before write-back, under existing
`seon.cluster.source/three-write-back-merge-tests-red-in-prepare-merge`. Commit `b110fc1ba` also reports six other
program-test reds on unchanged HEAD (run `8f29f625f816`); their identities/current status were not available in this
lane's focused run and are not asserted green. Root owns broad-tier triage. No test or hook exemption was changed.

The requested lane commit with both exact test vars refused **before tests**: its live-store rehearsal compared
committed HEAD with default's loaded dependencies and answered `:seon.cluster.source/restart-needed`, naming
`deps.edn` and `reference-code/sci`. Diagnostic: data/clusters/default/logs/operator-check-20261008T180739.269.edn;
archive 1,647 ms, rehearsal 865 ms, total 2,512 ms. Nothing was committed. This is the foreign dependency boundary,
not a red in this note or either named test. Default was not restarted. A documentation-only path should not need
publication or dependency adoption (AGENTS.md, gate inputs); `bin/lane` already skips the program check for Markdown
with no test selectors. The explicit selectors take its program-check route despite there being no production
input. Separate isolated proof can establish the two source claims without widening this note's program inputs.

Cleanup request on the lane's branch:

```clojure
(in-ns 'user)
(my.program/remove-ns! 'merge-path-sol.probe)
(my.agent/retire-branch! {:seon.store/branch :agent-07ca30cc0d5f})
```

Removal answered the two affected identities, 1,510 ms/1,509.7 MB/7,196 calls, with static-graph unknowns shown;
`seon.db/with-declarations` contributed 301 ms/152 calls in its envelope. This extends the existing namespace-breaks
cost finding `my.program/breaks-on-a-namespace-subject-takes-five-seconds`, rather than opening another defect class.
Retirement answered pending (`:my.agent/retiring? true`) in 51 ms/38.5 MB/942 calls; physical deletion is not claimed
from that answer. The owned isolated operator root booted committed `3ad952e07aa802a336562e26d3fb165a67f256db`,
PID 5205, ready in 59,464 ms. That full boot is verification overhead, not a merge/adoption timing.

The isolated check then refused `No live exact-root JVM`; the advertised PID 5205 no longer existed when inspected
with `ps -p 5205` and the operator status also reported no JVM. Why that process exited is not established by its
log. Diagnostic: tmp/merge-path-sol/operator-root/data/clusters/default/logs/operator-check-20261008T181120.427.edn.
No isolated tests ran. The two named tests' positive evidence is the live run above, not this failed check.
The final commit uses the installed documentation-only `bin/lane commit` path: this Markdown changes no program
input and has no reaching production test. No executable, gate, hook, test or dependency was changed to obtain it.
The attempted `--test` gate failure remains a verification limit. Net source/test lines: 0/0. The owned disposable
root can be removed once exact process inspection confirms it has no live holder; pending default-branch retirement
is left to its installed lifecycle/root rather than forced through an unverified deletion.
