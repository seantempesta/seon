---
type: review
status: ready after edits; documentation and source inspection only
created: 2026-09-23
reviewer: gpt-6-astra (Codex)
reviewed-commit: ccd91154f84e00ad07598102f85bd42412c510d2
---

# Independent review of the D1 rewrite

**Verdict: ready after edits.** The rewrite preserves the owner's five named rulings and the substantive fixes from both recent reviews. Its stages are substantially easier to navigate. It is not yet the straightforward account the owner requested: the execution order doubles back, implementation instructions interrupt the explanation, and two important merge/lifecycle requirements need explicit wording. This is a documentation verdict, not approval of an implemented merge/export path.

The system already supplies branches, submission, test evidence and source writers. The smallest composition keeps one captured basis and one proposal through review, combined-program proof, guarded acceptance and recoverable export; no additional registry or evaluator is needed.

## Scope and comparison boundary

D1 citations below refer to the 637-line file at `ccd91154f`, [rewritten D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md). “Prior” means its 734-line parent version. I compared that version directly with `git show`, the [archived copy](d1-evidence-2026-09-23.md), the [Astra plan review](astra-plan-review-2026-09-23.md), and the [D1 entry-path review](astra-review-d1-2e-2026-09-23.md). I also checked the older [D1 review](review-d1-2026-09-21.md), the five ruling diffs, root and plan instructions, README, and relevant B1/B4 contracts and cited source seams.

The archive preserves the prior text modulo terminal-newline framing; removing terminal newlines yields exact equality. The active D1 bytes still matched `ccd91154f` when checked. Archival preservation is useful evidence, but an acceptance requirement left only in a historical document is not an active requirement.

Source observations distinguish targets from installed behavior. The checkout has foreign edits in `cluster.clj`, `fn.clj`, `test.clj`, instrumentation and dependencies; no loading, adoption, runtime health or performance is inferred from those edits. No JVM, runtime request, test gate, archive checkout, worktree, other lane session or push was used. This assignment is docs-only; a runtime test would not establish textual losslessness.

## Findings and concrete changes

### 1. P1 — Retain the original branch basis explicitly

**Weakened in the rewrite.** Prior §2a's “Fork basis” row required the registry to record the exact immutable shared commit on the candidate start request/row, required for candidates but not ordinary roots. D1:82–84 now says only “Capture the immutable basis once”; :320 and :333 assume B can later be recovered. Capturing an in-memory value does not state the retained provenance requirement, especially after disconnect or restart. The current MCP `branch-form` returns `:seon.cluster.registry/from` (`script/seon/dev/mcp.clj:570–576`); that return alone does not prove durable candidate-base custody.

**Replace D1:82–84 with:**

> Create from a selected branch head or default's loaded program. Record that exact immutable commit as the candidate's original base in its existing start/request facts, and retain it for later diff, merge and recovery. Require this basis for candidates, not ordinary root clusters. Loaded-code creation also supplies the cluster/configuration facts needed for test custody.

Add to O1a's proof: “After reacquisition, the candidate still names the original base even if the parent branch has advanced.” This restores a prior requirement without prescribing another provenance store.

### 2. P1 — State what happens to non-conflicting work when a conflict is found

**An inherited specification gap, not a new rewrite loss.** `AGENTS.md:253–258` requires non-conflicting rows to land on an intermediate branch while conflicts stay there for repair, so the remaining problem shrinks. D1:367–383 describes a conflict task followed by scratch creation and testing; it never says whether a conflicted proposal retains its non-conflicting work on that intermediate branch. Today's `prepare-merge!` returns the three-way conflict before creating scratch (`src/seon/cluster/source.clj:768–783`). That is source evidence of the missing composition, not permission to weaken the target.

**Replace acceptance steps 2–3 at D1:369–377 with this sequence, retaining the existing conflict identity details:**

> Create or retain the intermediate branch from captured H. Apply the non-conflicting proposal there through ordinary prepared write admission; retain the conflicting identities with their base, candidate and destination versions for repair. Do not move the shared head. Create the structural conflict task for existing root, and let the author/root repair that same intermediate proposal. Once conflicts are resolved, validate and test the complete combined program there. Final acceptance uses the complete tested proposal and H's original writer fence; it never accepts only an untested subset. If H moves, rebuild the combined proposal and proof against the new H.

This intermediate branch is S, not a second workspace mechanism. Add a regression with one conflicting and one disjoint change: the disjoint change survives intermediate repair, the shared branch remains unchanged, and the final gate covers both. Retain D1's refusal, deadline, evidence-lifetime and cleanup rules.

### 3. P1 — Put the explanation in execution order and define the values before using them

D1:266–305 refers to S, H, merge delta, proposal and combined execution before Stage 5 defines them. Stage 6 precedes file export, then :411–412 explains that convergence actually follows export. The owner has to reconstruct the sequence that the rewrite was meant to explain. “Root's explicit accept or `my.task/merge!`” (:322) also leaves the distinction between requesting a merge and the required named acceptance less clear than README §7's root/owner authority.

**Replace the stage overview with this short explanation:**

> The orchestrator acts as root. The agent starts from the namespace context and edits a named branch. Each submission receives entry validation and affected-test feedback. Root reviews its diff and either sends it back or requests acceptance. Preparation captures the branch's original base B, its proposed head C and the destination head H. It builds an intermediate combined program S from H plus the proposal, retaining conflicts for repair. The mandatory gate tests S. Root's acceptance installs exactly that proposal only if H is still current, producing accepted commit M. The source owner then stages and verifies M's file changes, installs and commits the complete file set, and reloads/arms/records it before affected execution resumes. There is no second approval or push.

Then order the detailed sections: branch/context → submit → feedback → root review and prepare → gate and accept → file export/recovery → JVM convergence. Keep the distinction between entry refusal, red feedback, conflict, stale-head refusal, export refusal and failed convergence. No runtime guarantee needs to change.

**Replace D1:322 with:**

> `my.task/merge!` requests preparation; shared acceptance requires the named root/owner accept through the same gate. Green candidate completion never supplies that acceptance.

If that API already combines both roles for an authorized root caller, say so explicitly. Do not imply that any agent's request alone grants root's acceptance.

### 4. P2 — Restore the small requirements compressed out of active text

These are narrower losses than the main rulings; restore them in their owning paragraphs rather than paste the old notes back.

| Prior requirement | Rewrite disposition | Concrete change |
|---|---|---|
| §2e: the same declared diagnostics serve REPL, root diff and merge; subject is a qualified symbol, check is one of the four dial keys, basis is a commit id | D1:230–239 lists the keys and warning text, but drops those constraints and explicit reuse across surfaces | After :239 add: “Subject is a qualified function symbol, check is one of the four dial keys and basis is an immutable commit id. REPL feedback, root's diff and merge refusal use these same declared diagnostics.” |
| Old slice 6: preserve any unsuperseded candidate evaluation while replacing the fixed gate | O2b and O1c retain the writer fence and atomic conversion, but do not explicitly preserve surviving candidate evaluation | Replace O2b's deletion cell with: “Superseded pre-install gate work only; preserve surviving candidate evaluation and the writer-owned conflict-basis check.” |
| §7: provisional aggregate targets of ≤355 replacement Seon source, ≤40 schema, ≤40 fork and ≤300 added test lines; charge moved code once | Only historical caps survive in the archive. D1:537–539 supplies per-slice caps and :605 says historical caps are not proof; neither states whether the aggregate target is retained or repriced | Add: “The prior aggregate targets remain provisional accounting targets pending a disjoint revised ledger: source 355, schemas 40, fork 40, test additions 300. Charge moved code once. If revised scope exceeds them, report the responsible seam and cost rather than claim the old target met.” Alternatively explicitly reprice them; do not silently treat per-slice caps as the same budget. |
| §2e O2c: installation plus installation verification <1 s combined; O3b: changed-delta review <1 s excluding bodies | General R17 survives, but the build table loses these specific operation targets | Add to O2c/O3b respectively: “install + installation check <1 s combined” and “diff/review overhead <1 s excluding selected test bodies.” Keep end-to-end submission/export targets too. |

The old deletion estimates and historical timings can remain solely in evidence. The prior “RESET NEEDED” schema procedure and instruction to add an already-present fork guard were correctly removed; they are not lost requirements to restore.

### 5. P2 — Repair incoming section references and remove duplicate navigation

The rewrite updates two Markdown anchors, but README, B1 and B4 still repeatedly direct implementers to “D1 §2e”, “§2c” and “§2d”, sections which no longer exist. Examples: README:226, B1:117,179,199,392,437 and B4:115. These are mostly stale pointers, not substantive disagreements; they still make the intended authority harder to follow.

Replace references by the actual destination, for example:

- “D1 §2e O1” → “D1 Stages 1–3, O1a–d”.
- “D1 §2c merge” → “D1 Stages 4–5, combined-program gate and acceptance”.
- “D1 §2d write-back” → “D1 Stage 7, file export and recovery”.
- “D1 §2e” beside load/arm/record → “D1 Stage 6, JVM convergence”.

After the chronological reorder in finding 3, use linked descriptive section names rather than another round of fragile numbers. Keep historical citations such as the title of the §2e review unchanged. These corrections belong to the respective file holders; this review edits none of those files.

## Losslessness ledger

### Owner rulings

| Authority checked as a Git diff | Active home in rewritten D1 | Verdict |
|---|---|---|
| `a29faf5a6`: branch-named interface, create/list/unlink; persistent REPL; automatic affected tests; merge checks; same inside/outside path | 75–119, 123–191, 195–218, 256–305 | Preserved. Definition-warns default is legitimately superseded by the later configurable strict ruling. |
| `38863a0c8`: orchestrator as root; source diff with warnings/results; accept/send-back; gated merge and write-back/commit | 310–331, 447–465, 488–515, 633–637 | Preserved. Put orchestrator-as-root at first use, not only in the final settled-choices paragraph. Clarify request versus acceptance as in finding 3. |
| `215c32d9a`: ordinary branch configuration; warn/gate including TDD; merge always checks | 133–179, 263–301 | Preserved. Valid contracts stay armed; malformed authored contracts are not manufactured or wrapped with stale contracts. |
| `5ec049cfd`: same namespace context; outside agents use it and record routed quality feedback | 195–209, 241–253, O1d | Preserved, including immutable basis/contribution evidence instead of a new version counter. |
| `eb709fcb8`: start strict, schema first and reaching test first through existing reach analysis | 134–149, 168–173, 263–267, 300–301 | Preserved: all four defaults are explicitly `:gate`. The changed-only merge reading is still an open proposal; the active whole-program rule has not been silently narrowed. |

### The two recent reviews

| Review requirement | Active home | Assessment |
|---|---|---|
| Astra plan #1: complete obligation, not green subset; exact H/C/S; failing-subset regression | Stage 4; 324–327, 376–383; O3a | Preserved. Explicit disconnected task and long members, modified S, stale H and read-only positive selection all remain. |
| Astra plan #2: exclusive convergence, failed reload unavailable, old branch indirect calls, replaced wrapper | 408–443; O4b | Preserved; no database fence is misrepresented as a JVM transaction. |
| Astra plan #3: no early file exceptions; whole-file holds, staged set, partial-install recovery, platform proof | 447–527 | Preserved and extended to process restart. |
| Astra plan #11: narrow loop first, lifecycle identity/exit, end-to-end overhead, defer expansion | 132–191, 203–207, 529–607 | Preserved; specific cost/accounting compression noted in finding 4. |
| Astra plan #12, D1-related authority corrections | Target language, source guard correction, incremental schema adoption, no second approval | Correctly incorporated. Other plan-wide storage/error/fork findings remain at their owners; losslessness does not require copying them into D1. |
| D1 entry review #1: content-valid selection, explicit task/long eligibility, separate tested and recording values | 269–305, O3a | Preserved and aligned with B4's O3a row. |
| Entry review #2: warned malformed lifecycle through reacquisition/test fork, remove stale wrapper, repair | 175–183, O1c; strict/warn proof at 581–582 | Preserved. Generic core faults are not converted to warnings. |
| Entry review #3: closure is not installation/feedback completion; retractions; retry identity | 139–142, 185–191, 200–218, 251–253 | Preserved. |
| Entry review #4: inherited readiness and required test additions before claiming TDD repair | 559–566, 621–623 | Preserved; whole-program repair remains a real dependency, not a suite fallback or hidden exemption. |
| Entry review #5: restart reconciliation before loading mixed files | 488–515, O4b | Preserved; bootstrap/source owners and separately authorized new-process proof remain named. |
| Entry review #6: reconcile authorities, O0 verify/adopt, scope/cost/order | 435–437, 521–527, 531–566, 613–637 | Substantive rulings preserved; stale section pointers and compressed targets need findings 4–5. |
| Entry review #7: exact prompt composition inputs and equality | 208–209, 241–248, O1d | Preserved. |

The older D1 review's surviving behavior classes also remain: task-only execution and addressing (Stage 1); net digest comparison, ancestry, complete symbolic identity/ref/component transfer and colliding eids (Stage 5); prepared validation, current static reach plus explicit task tests, immutable combined execution, stale-head refusal, conflict identity and evidence retention (Stages 4–5); canonical file analysis, provenance, additions/deletions, no old-JVM fallback proof, per-file fences and interrupted export (Stage 7); measured visited work, exact forms, line accounting and surviving regressions (§9). Finding 1 restores the explicit recorded-base requirement. The old candidate-as-cluster, reset-for-schema and isolated-checkout wording was legitimately superseded by later branch-plus-handle, incremental adoption and captured staging rulings; do not resurrect it.

## Readability: what to keep, move and cut

The “What the agent/root sees” paragraphs and the recovery table at 493–501 answer the owner's question directly. Entry dials and test outcomes are understandable when read together. Stage 5 still has the densest implementation prose, and §9 turns back into a long list of notes.

| Passage | Problem | Smaller owner-facing treatment |
|---|---|---|
| 155–173 | Resource path, EDN grammar, default function, generator, Malli line, reverse-index functions and missing producer proof in one run | Keep the four checks, per-submission branch config, legal built-ins and test-first behavior. Move source navigation/schema implementation details into the evidence note's implementation map. |
| 230–239 | Seven namespaced diagnostic keys interrupt the feedback explanation | Keep the reply table and one example warning/refusal. Link the exact diagnostic contract; retain its requirements and shared-use rule. |
| 333–357 | “Bind before history identity join”, `since(history)`, `canonical-row`, eids, tempids and pull limits read as implementation notes | Keep net-change table and the rule “transfer declarations by stable identity with complete references; never copy branch-local numeric IDs.” Keep ancestry/completeness refusal and one cost sentence. Move query recipes and source citations. |
| 386–401 | Fork API, writer callback, parents, alternative transaction and GC cutoff packed into the main merge story | Keep tested-head check, immutable lineage and retrieval after cleanup. Put fork pin/API details and rejected alternative in the implementation map. Explain “parents” once as the commits retained as merge ancestry. |
| 541–557 | Fourteen dense rows repeat proof requirements and introduce O codes as another numbering system | Keep order, owner, prerequisite and deletion/cap. Link each row to its single authoritative stage acceptance checklist; move line-by-line navigation and historical measured defects. |
| 568–611 | Completion checklist repeats most stage proofs plus repository-wide working laws | Keep D1-specific two-branch demonstration and exact evidence fields. Link the shared verification rules instead of repeating them. Do not remove the unusual conflict, retention or interruption cases. |

Define “custody” as the connection/context supplied to an execution, “arming” as installing contract checks, “reaching tests” as tests connected through current call/reference dependencies, and “convergence” as loaded code and armed contracts matching the accepted files. “Leaf overhead”, “closure”, “basis-t” and “recording authority” currently assume specialist vocabulary; either define them at first use or reserve them for the implementation map.

A roughly 400–450-line active spec is a reasonable editing target, not a measured guarantee: about 60–80 lines can move to the implementation/evidence map, about 40–60 can be removed by deduplicating requirements/build proofs, and another 30–50 by linking shared working rules. Keep every unique acceptance condition active, or link it explicitly as a normative contract. Do not simply move live requirements into a document labelled “historical; not an implementation authority.” R1–R17 may remain a compact checklist, but repeating their full text under every stage and again in the completion list buys little clarity.

## Correctness against authorities and source

Apart from finding 2's intermediate-conflict gap, I found no new substantive contradiction with README §7, root instructions, B1's convergence/export contract or B4's execution/evidence contract. The rewrite correctly preserves ordinary `require :reload`, no lane self-adoption, strict default with experimental warnings, unconditional merge checks, incremental schema adoption and platform isolation only where required. The open merge-scope proposal is explicitly not current permission. It still needs an owner ruling or an actual inherited-readiness proof before a narrow real merge can succeed.

Source spot-checks support the chosen seams and the stated remaining work:

- `submit-source!` (`src/seon/cluster/agent.clj:607–627`) explicitly uses the ordinary durable run path. `prompt` (`src/seon/cluster/prompt.clj:418–446`) consumes both a database and agent request; namespace rendering alone would not establish equality.
- `prepare-merge!` and `accept-merge!` (`src/seon/cluster/source.clj:736–848`) still show replacement-only admission and the green-subset acceptance defect. D1 correctly describes O3a and wider admission as work to do, not installed behavior.
- `seon.test/select` (`src/seon/test.clj:838–868`) still has the reached-seed reuse veto. B4:88 and D1:278–288 agree on fixing the existing evidence owner rather than adding a selector.
- The maintained Datahike pin at the reviewed commit is `c79cd03a44427ac1734d917c7484c3e529c77716`; `versioning.cljc:738–774` has the arg-map merge API and `writing.cljc:864–896` delegates to the guarded transaction. The rewrite correctly replaces the stale “add this API” instruction with verification. This is a fork seam, not an upstream guarantee; Seon's final-report validator and retained parent/result evidence still require proof.
- SCI and Malli pins are `fcbd8862800e638dc0f8f5521111f999279cbcd2` and `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`. Neither a forked context nor successful schema compilation alone proves the full execution path. D1 retains the relevant callable and wrapper lifecycle tests.

These are source inspections, not new timings or passing runtime tests. The implementation must still demonstrate the stated behavior through its owned branch and the one test authority.

## Delivery boundary

Only this review note is changed. Net source/test lines: **0/0**. The prior archive comparison and unchanged reviewed-file check passed with the newline qualification above. Path-scoped whitespace verification is the applicable documentation check; no application test or repository-wide Markdown-lint pass is claimed. Foreign in-flight source/dependency work was neither altered nor used as a reason to stop.
