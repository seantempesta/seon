---
title: Plan review (Opus), agent-loop plans against the 2026-10-07/08 owner rulings
type: research
status: complete
date: 2026-10-08
lane: plan-review-opus
---

# Plan review: what to redo, what contradicts, what to merge

Authority: `review/owner-rulings.md` (f3784a6b5) and CLAUDE.md. Read in full: `docs/seon/specs/agent-loop.md`,
`docs/seon/specs/merge.md`, `split/reconciled-plan.md`, `repl-audit/repl-plan.md`, `s5-admission/admission-design.md`,
`competency/competency-spec.md`, `printer-rules/printer-rules-opus-printer-rules.md`,
`agent-messages/messages-opus-messages.md`, `model-pass/profile.md`, `memory/agent-memory.md`, and the S1, S2b-1 and S3
design notes under `2026-10-07/`. HEAD `f3784a6b5`. Line numbers are at that HEAD. One live probe on default
(MCP SCI, agent `plan-review-opus-probe`, retired) confirmed one code defect (§6.1), filed as a task.

**Verdict.** Only one design needs a real redo: the proof-status and completion surface (AR-02's auto-propose, AR-04,
the Var render's reaching-test status, the three help designs and the session-end API). Everything else is a patch,
but the patches are overdue. The plans were revised by appending: each holds superseded text next to the ruling that
replaced it. Four documents specify the same slices under colliding ids (printer `S1`-`S20` reuse agent-loop's
`S1`-`S8`). And the REPL plan and the reconciled ledger contain a dependency cycle (P7 → P14 → S6 → S6-03 → P7).

## 1. Designs: REDO or PATCH

| Design | What is wrong | Ruling that breaks it | Verdict |
|---|---|---|---|
| **Completion and proof-status surface** (AR-02, AR-04, the Var render, the help entries, `my.task/done!` deletion) | (a) AR-02 makes the proof proc call `propose-merge!` when proofs go green (`reconciled-plan.md:595`; `agent-loop.md:378`). But the competency help tells the agent to "Run them, read your diff, then propose" (`competency-spec.md:101-103`), and T15 says question tasks have "no propose step" (`competency-spec.md:291`). An auto-proposer would open a merge task for an answer-only branch. `propose-merge!` would also re-run every proof the proc just ran (`merge.md:211`: 0.46-1.05 s). (b) AR-04 (`reconciled-plan.md:597`) makes the Var render name red reaching tests, and the Var render already offers `(my.test/check …)` with reaching-test status (`agent-loop.md:405-406`). That puts test status into the HISTORY on every `defn`. Reaching tests no longer run during work, so it would show `:pending` forever. (c) `my.task/done!` is scheduled for deletion as a "completion API" (`agent-loop.md:380`, `reconciled-plan.md:381,581`). It actually completes a **step** (`src/my/task.clj:163-175`), and the block shows "its ordered steps" (`agent-loop.md:286`). | "Only the task's proof tests run during work" (rulings:37); "status … never repeated in the history" (`agent-loop.md:553-554`, rulings:20,36); "a Seon agent should be able to do root's job" (rulings:38) | **REDO** one small design: who proposes (agent or proc), what question tasks do, delete AR-04 and the Var render's test-status/`check` offer (P10 already wants `#'ns/x`), and keep or fold the step API on purpose |
| S4 opening and input passes vs the current-state block | Already retired in substance: S4-05b is retired (`reconciled-plan.md:530`), Q2 and Q3 are rewritten, and spec §4.4 says no opening or input origin (`agent-loop.md:275-279,338`). The leftovers: Q3 keeps the retired input-pass recommendation "for its grounds" (`reconciled-plan.md:230-247`). S4-02b, CS-06 and S4-06 all delete `declared-sources`/`seed-tx` (`:526`, `:554`, `:531`; `turn.clj:1751` falls inside S4-06's `1686-2232`). S4-06 waits on CS-04, which waits on an unnamed "S5 settlement" (`:531`, `:552`), so the 405 MB context owner is chained behind S5/P8 for no file reason | rulings:21,36 | **PATCH**: delete Q3's old text; give `declared-sources`/`seed-tx` one deleting row; name CS-04's real dependency or drop it |
| S5-04 admission vs serial REPL semantics and P8 | Rewritten to the serial law (4c0f2f5b3), and P8 adopts it. Residue: K2 says "a contract that does not arm, refusing by name until redefined" (`admission-design.md:77`), which contradicts installed P4 (`agent-loop.md:257-258`). §1 keeps `changed-since-basis` and the "settle-then-`rebase`" refusal path as live refusals on the agent's branch (`:27`, `:59`). The status line says "two questions open" (`:4`), but 4.1 is answered by P8. 4.2 (same-pass program reads, `:103-107`) is still open, although the law ("each form seeing every earlier form's effects") decides it for program tools. The ledger it feeds is both "superseded" (`repl-plan.md:15`) and "as designed per admission-design.md §7" (`repl-plan.md:175`). | P8 (rulings:12), REPL law (rulings:9,31) | **PATCH**: delete K2; restrict `rebase` to Seon's own failure (§4.6); close 4.1 and 4.2; move the §7 rows into the one ledger as P8a-j |
| S6 cut rows vs chat wire formats and the DATA entrance | The rows store per-evaluation source and shown text, so formats A/B/C and DATA presentation can be rebuilt from history (S4-03). Nothing in S6 breaks them. What is wrong is duplication and order: S6-03 (`reconciled-plan.md:576`) **is** P7 (`repl-plan.md:174`), with the same deletions (`segments`, pre-segmented rows) and a different reader seam (`next-form` vs `parse-next+string`). S6-04b's "delete the intent commit" (`:578`) **is** P14 (`repl-plan.md:181`). P7 is "moved after P14" (`repl-plan.md:174`), P14 waits for "agent-loop cut" (`:181`), and the spec says "S6-03 after P7" (`agent-loop.md:501`): a cycle. The DATA read stage (`:data` mode, `agent-loop.md:198,206`) has no S6 or P row; competency C6 says "after P7" (`competency-spec.md:454`). | rulings:34 (entrances as read stages) | **PATCH**: one row "one cursor" (P7 = S6-03, `parse-next+string`) and one row "one transaction for model replies" (P14 = the intent part of S6-04b); add the `:data` read-stage row after it |
| S7 vs proofs-only auto-run and the status block | AR-01 was edited to proofs-only (5cbe1937b), and S7-02 is retired. The rest is broken as described in row 1. The resolving task's intent is stale: `resources/seon/tasks/seon.render.var.edn:6-9` still says "The pass schedules the reaching tests off the pass (AR-01)", and `a-deftest-reply-shows-only-its-var` (`:1-5`) asks for test status in the Var render. Owner-rulings line 20 still names "S7-03", which no ledger defines. | rulings:20,37 | AR-01 and AR-03 **PATCH** (stale task text); AR-02 and AR-04 are part of the row-1 **REDO** |
| Old prompt and context assembly vs the three-part context | S4-07b deletes it. The debug view is specified as "saved exchanges through the SAME pure function the proc uses to build its transcript" (`reconciled-plan.md:266-268`, `:533`). That is the history only. Round 10 wants "the agent's context exactly as sent, produced by the same assembly function" (rulings:6), which is `seon.ai/messages [wrapping prefix exchanges block]` (`agent-loop.md:343`). The view must show prefix + history + block through that function. | rulings:6,21 | **PATCH** Q4 and S4-07b |
| merge.md vs agent branches never refusing | Sound: integrity moves to the diff value (dangling, schema, conflict) and auto-accept criteria (`merge.md:73-80,122-141`); root is any merge-task holder (`:15-18`). Two gaps. (a) Nothing says who runs `review!` when a merge message arrives (`merge.md:127-129`). A wake is a model call, and with no provider configured "no model pass is admitted" (`agent-loop.md:424`), so default's provider-less root cannot auto-accept. It needs a declared proc (like the proof proc) or a schedule handler. (b) M8 landed (76daa8fd5) but is listed as open (`merge.md:187`). Slice ids are duplicated in competency §9. | rulings:12,13,38 | **PATCH**: name the policy runner's execution owner; mark M8 landed; competency references merge.md for M-rows |
| Model-pass budget text (`agent-loop.md` §3.1) | It says the 20-40 MB forecast is "4-8× over the allocation budget" and that three TARGET slices are required (`:121-122`). But the budget row is 20-40 MB (`:83`), and the reconciled plan **accepts** the writer floor and residual without slicing them (`reconciled-plan.md:624-628`), while the spec lists them as required TARGET slices (`:116,119`). The 4-8× sentence comes from the old < 5 MB target (`profile.md:5`). §3.1 also does not name PB-01..03, which exist. | rulings:15,17 | **PATCH** |

## 2. Contradictions (both sides quoted)

1. **Who proposes.** `agent-loop.md:378`: "on the agent's own branch the agent's branch is proposed (§4.10), which opens
   the merge task" (proc-driven), and `reconciled-plan.md:595`: "the proof proc's recording proposes the branch".
   Against: `competency-spec.md:101`: "Run them, read your diff, then propose; root merges." and `:19` "it proposes
   once its proofs pass". Against: `competency-spec.md:291`: question tasks have "no propose step". Also
   `agent-loop.md:52`: "Success is proofs green, then propose". CLAUDE.md "Derive work and completion" says "all green
   completes the task", while ruling 10 (`agent-loop.md:544`) says completion happens after merge acceptance.
2. **Session end has three designs.** `messages-opus-messages.md:246` `:end (my.agent/done {})` and `:241`
   `(my.task/done! {})`. Against: `competency-spec.md:104-110` (`my.task/complete!` with a reason, `my.turn/wait`)
   and `:121` "`my.agent/done` … slated for deletion". `agent-loop.md:380` deletes `my.task/done!` but never mentions
   `my.agent/done` (`src/my/agent.clj:23`, a `wait` alias). Today help itself says `(my.agent/done)` (`bootstrap.clj:63`)
   and the situation render says `my.turn/complete` (`cluster/agent.clj:404`).
3. **Help content has three owners.** S1-10 "help-value … teaching the landed same-line rule"
   (`reconciled-plan.md:514`): that rule was reversed by the approved REPL plan (rulings:11; `agent-loop.md:240-241`).
   Competency H1 (`competency-spec.md:451`) gives entries as `[{:seon.help/says …}]`. Messages S6
   (`messages-opus-messages.md:299-307`) gives a different single map `{:repl … :find … :end …}`. Help is now the
   **static prefix** (`agent-loop.md:39`, S4-02b), so its shape decides the cached prefix.
4. **Var render test status.** `agent-loop.md:553-554`: proof status reaches the agent "only through the current-state
   block, never repeated in the history". Against: AR-04 (`reconciled-plan.md:597`), printer S8 "a var render with 50
   pending tests prints `…N (drop k …)`" (`printer-rules…md:406-408`), and the task text in `seon.render.var.edn:1-9`.
5. **Same-pass check.** Admission 4.2 recommends that program tools read the live world for names defined earlier in
   the pass (`admission-design.md:103-107`). Against: messages S11 gives the same-pass `my.test/check` a better
   refusal type, `:seon.test/identity-unsettled` (`messages-opus-messages.md:344`). The REPL law makes the check work,
   not refuse better.
6. **Not-admitted diagnostics.** Messages S1 (`messages-opus-messages.md:262-269`) shrinks the "Not admitted to the
   program" diagnostic for a `[:maybe :int]` contract. Against: P8 (`repl-plan.md:175`, "the writer never refuses a
   REPL-valid change") and CLAUDE.md ("Preserve malformed authored contracts as data; never arm them"; "A missing
   function contract is never refused at the REPL"). On the agent's branch the producer (`seon.turn/not-admitted`,
   `turn.clj:3329-3341`, reached from the facts-refusal at `:3650-3662`) should disappear for contract-shape refusals,
   not be polished.
7. **Contract that fails to arm.** `admission-design.md:77` K2 "refusing by name until redefined" vs
   `agent-loop.md:257` "A malformed contract keeps the function callable" (installed P4, 0667ee448).
8. **Writer floor and residual.** `agent-loop.md:116,119` "TARGET new slice … required" (`:122` "The three TARGET
   slices are required") vs `reconciled-plan.md:624-628` "Accepted as measured, not sliced".
9. **Allocation line.** `agent-loop.md:54` "Over 1 s or 10 MB of Seon work is stop-and-fix" vs `:83` and ruling
   rulings:15,17 (20-40 MB transient fine, never GB). The 10 MB line and the §7.5 interim targets
   (`agent-loop.md:533-534`) predate the ruling.
10. **Competency run gate vs S4.** `competency-spec.md:412-413`: "Order: §0's cut → one A × REPL smoke run of T1 → S4
    and S6-06b → full matrix". But §0's cut cannot pass before S4: the second owner (405 MB) is removed only by
    S4-02b..S4-07b (`agent-loop.md:114`, `reconciled-plan.md:641`). `agent-loop.md:504` also says model runs wait
    for C0 while only "the full matrix waits for S4".
11. **Matrix size.** `agent-loop.md:504` "eight tasks … 3 × 2 × 8 × 3 repetitions" vs `competency-spec.md:367-368`
    "Six cells × 21 tasks × 3 repetitions = 378 runs".
12. **Wrapping adapter signature.** `agent-loop.md:343` `seon.ai/messages [wrapping prefix exchanges block]` vs
    `competency-spec.md:398` `seon.ai/messages [wrapping opening exchanges]`. `competency-spec.md:363` still defines
    `:chat` as "the opening and inputs as user messages".
13. **`doc`/`dir` shape.** S1-07a `directory-value [ctx authored] -> var-summaries` (`reconciled-plan.md:510`) vs
    messages S9 "`dir` returns sorted public symbols … `(dir my.task)` is a vector of symbols" in
    `src/seon/bootstrap.clj` (`messages-opus-messages.md:324-330`). S1-09 deletes those `seon.bootstrap` macros
    (`reconciled-plan.md:513`).
14. **Vocabulary.** The owner renamed "dig form" to "elision form" (rulings:25,42). Still "dig form":
    `agent-loop.md:260`, `messages-opus-messages.md:49,183,270-273`, CLAUDE.md Presentation (`:332-333`).
15. **P1 claim vs code.** The P1 commit (ded5e35de) says "the write observer's `:remove-ns`/`:unmap` already store the
    final state". `seon.turn.batch/removal-facts` (`src/seon/turn/batch.clj:493-511`) returns the deletion refusal when
    referrers survive. Live: the unmap answers `nil` with no diagnostic, the row stays and the next pass resolves the
    Var again (§6.1). `agent-loop.md:29` "Installed: the native veto is deleted (P1)" is true only for the SCI-side
    veto. The settlement-side veto remains.
16. **Status lines.** `agent-loop.md:228` "TARGET (P6, lane `repl-p6`)" and `:495` "In flight: P6 …, P11" vs landed
    c98ade404 (P6) and 666c767f7 (P11). `agent-loop.md:13-14` cites HEAD `81020da6a` while `:405` cites `666c767f7`.
    `repl-plan.md:4` "status: proposed; policy questions … await" vs the owner approval (rulings:11).
    `admission-design.md:4` "two questions open" vs P8. `competency-spec.md:4` "design only; no code" vs C1 landed
    (c6ae8875d). `agent-loop.md:132-138` memory "TARGET … numbers stay TARGET until measured" vs measured in
    `memory/agent-memory.md:5,30-40` (1.25 MB per idle agent; 3.87 GB heap and 7.09 GB RSS with 20 simultaneous passes).

## 3. Appended, not integrated (a reader can follow the stale part)

| Where | Stale text left beside the ruling |
|---|---|
| `reconciled-plan.md:25-28` | "S3-01 is in flight … Rows that need those files queue behind the two lanes" (S3-01 landed 2bf3e10db) |
| `reconciled-plan.md:127-162` (§1 R0/R1/R2) | X-01 (landed 6761a1fd8), I-Q4/10/14 (landed, `inventory/`), S5-03a-j (landed), S1-03/S1-04 (landed d11dbe2b6, 7876f594e) listed as ready |
| `reconciled-plan.md:230-247` | Q3's retired `(my.agent/inputs)` input-pass recommendation kept "for its grounds" |
| `reconciled-plan.md:305-313`, `:562` | Q7 and S5-04 "Not a lane. Root prices sub-items after Q7": superseded by P8 (rulings:11) |
| `reconciled-plan.md:487-496` | S5-03a-j rows still in the "Ready" ledger table |
| `reconciled-plan.md:514` | S1-10 teaches "the landed same-line rule" (reversed) |
| `reconciled-plan.md:565` | S5-07a-f "per Sol's table": the rows live only in `spec-split-sol-split.md`, outside the one ledger |
| `reconciled-plan.md:662-695` (§4) | disagreements 4-6 resolved since (Q2 folded, Q2b superseded, S3 enum fixed in `agent-loop.md:498`) but kept as open ambiguity |
| `repl-plan.md:174` vs `:183-185` | P7 "Moved after P14" written into the row; the Order paragraph still says "then P7, P8, P9, P12, P13, P14" |
| `repl-plan.md:15` vs `:175` | "supersedes the S5-04 slice ledger" next to P8 "as designed … per admission-design.md §7" |
| `repl-plan.md:93,95` | "S5-04b", "dig forms" in the target path |
| `agent-loop.md:113-119` | X-perf rows "TARGET new slice" although PB-01..03 exist |
| `agent-loop.md:121-122` | "4-8× over the allocation budget" (old < 5 MB budget) under the 20-40 MB row |
| `agent-loop.md:174-178` | "Same-pass admission is settled by the ten S5-03 case notes and the revised admission design" + "TARGET (P8)": two owners named for one rule |
| `agent-loop.md:504` | S8 "eight tasks … (3 × 2 × 8 × 3)" |
| `agent-loop.md:556-559` | "Open: Must-reads in the block (reconciled Q2b, superseded)": a superseded open item |
| `competency-spec.md:126-128` | "The task opening (agent-loop §4.4: `(help)`, the task's `doc`, its must-reads' `doc`)": the opening is deleted |
| `competency-spec.md:363,369,398,420` | `:chat` built from "the opening and inputs", "the same opening", `messages [wrapping opening exchanges]`, "~3k-token opening" cost basis |
| `competency-spec.md:28` | "The planned S4 slices plus the base-context slice (reconciled plan) own that cut" (PB-01..03 now named) |
| `messages-opus-messages.md:60,139,209,215,385-388` | the opening that evaluates `(help)` |
| `profile.md:5,11,102` | "misses <5 MB by 4-8×", "<5 MB complete pass" (pre-ruling budget) |
| `s1-lookups/design-notes.md:18-20,280-307` | path step served: "the opening (`(help)`, the task's `doc` …)"; help teaching fences and "whichever of S1 and S2b-1 lands second" |
| `s2b1-reader/design-notes.md:31-32` | "every form on a line that starts one runs" (reversed by P6) |
| `s3-prep/design-notes.md:18-20` | "S4 adds `:opening :input :test`" (only `:test` survives) |
| `resources/seon/tasks/seon.render.var.edn:1-9` | task intents for reaching tests scheduled per pass and test status in the Var render |
| `owner-rulings.md:20` | "Proofs auto-run after code-changing passes (S7-03)": the slice is AR-01 |

## 4. Duplication: the same slice in two or more plans

| Behavior | Specified in | Owner |
|---|---|---|
| One read at the live cursor; delete `segments`/pre-segmented rows | P7 (`repl-plan.md:174`), S6-03 (`reconciled-plan.md:576`), spec §4.3 TARGET | one ledger row (P7 = S6-03) |
| Model path's intent commit folded into settlement | P14 (`repl-plan.md:181`), S6-04b (`reconciled-plan.md:578`), spec §4.1 table (`agent-loop.md:186`) | one row |
| Delete opening / `declared-sources` / `seed-tx` | S4-02b, CS-06, S4-06 (`reconciled-plan.md:526,554,531`) | S4-02b deletes; CS-06 removed |
| Wire formats | CS-03a/b/c (`reconciled-plan.md:549-551`) and competency C5 (`competency-spec.md:453`, other proof name), CS-03b depending on C5 | CS-03a/b/c; C5 deleted |
| `(help)` | S1-10, competency H1, messages S6, S4-02b (as static prefix) | one row in the ledger, content decided once (§1 REDO) |
| `def` note deletion, Var as `#'ns/x` | P10 (`repl-plan.md:177`), messages S5 (`messages…md:294-298`) | P10 |
| `:out` elision on the live output | printer S3, messages S10, lane `out-fix` (in flight), P5's test asserting the db-pull form | printer S3 (out-fix) |
| Map/set cut continuations, no `seon.print/value-at` | printer S1+S2, messages S2 (+ its rule 7, which is a printer rule kept only in the messages doc) | printer S1/S2, rule 7 added to R6 |
| `doc`/`dir` shape | S1-04..S1-09, messages S9 | S1 rows; messages S9 deleted, its token budgets added as S1 proof assertions |
| Var render cutting test lists | printer S8, AR-04 | deleted by the §1 REDO |
| Merge slices M0-M13 | `merge.md` §7 and `competency-spec.md` §9 (M0-M6) | merge.md; competency references it |
| Contract-refusal messages vs errors cut | messages S8 ("must land with or after the errors cut's constructor") and the errors spec | errors spec |
| Printer census | `printer-rules-opus-printer-rules.md`, plus in-flight lanes `printer-audit` and `render-audit` (`bin/lane status`) writing two more printer docs in the same directory | one printer ledger section; fold the two in-flight audits into it |

**Id collision.** Printer slices are named `S1`-`S20` (`printer-rules…md:374-447`) and message slices `S1`-`S12`
(`messages…md:262-352`), both reusing the agent-loop ledger's S1-S8. "S4 rename dig form" and "S4 context" are
different work. All ids need a prefix (`PR-`, `MS-`) or a merge into one ledger.

## 5. Structure proposal

**Authoritative documents: three.**
1. `docs/seon/specs/agent-loop.md`: the owning spec. Laws, design, budgets, owner decisions. No slice ledger beyond a
   pointer, no "In flight", no HEAD-dated status.
2. `docs/seon/specs/merge.md`: the merge mechanism (already a spec). Competency and the ledger cite it.
3. **One ordered ledger**, `docs/seon/schedule/agent-loop-ledger.md` (new home; research directories are not for
   maintained plans), built from `reconciled-plan.md` §3. It absorbs the REPL plan §5 (P rows), admission §7 (as
   P8a-j), PB, CS, AR, the printer slices (`PR-`), the message slices (`MS-`, minus duplicates), the merge slices (by
   reference to merge.md ids) and the competency slices (`C`, `A`, `D`, `B`, `X`, `Q`). Each row has a landed or open
   state, with no prose history.

The competency spec stays as **design data** (tasks, proofs, scoring) under `docs/seon/specs/competency.md`. Its
slice table moves into the ledger.

**Delete as superseded research** (Git keeps them): `split/spec-split-codex-split.md`, `split/spec-split-sol-split.md`
(after S5-07a-f is copied in), `s5-admission/admission-design.md` §7 (after the move; the case notes stay as
evidence), `repl-audit/repl-plan.md` §5 (after the move; §1-§4 stay as evidence), the Q-answers in
`reconciled-plan.md` §2 that are folded into the spec, and the reconciled plan itself once the ledger exists.
`model-pass/profile.md` and `memory/agent-memory.md` stay as dated evidence, with their stale "<5 MB" framing noted at
the top.

**The single ordered list, from now to "competency suite running on DeepSeek with merges".** Arrows are hard
dependencies. Rows on different lines run in parallel when their files are disjoint.

1. **In flight:** PB-01 (`pb-01`), P7a repair notes (`repl-p7a`), out-fix (printer S3), printer and render audits.
2. **Pass budget (GB class, first):** PB-01 → PB-02 → PB-02b → PB-03.
3. **REPL law:** P8a..j (admission §7 rows, including the removal fix of §6.1) → P9; P10 (absorbs MS-S5) → PR-S1/S2
   (absorbs MS-S2) → PR-S4 rename → PR-S5..S6; MS-S3 arity/JVM messages; MS-S4 findings; MS-S7 test-result shape;
   **delete** the not-admitted path for contract shape on agent branches (replaces MS-S1).
4. **Lookups:** S1-06 → S1-07a → S1-07b; S1-08; → S1-09 → **help (one row: S1-10 + H1 + MS-S6, the static-prefix
   content, after the §1 REDO decides session end)** → S1-11.
5. **Context:** S4-01 → S4-02a → S4-02b (static prefix; deletes opening, `declared-sources`, `seed-tx`) → S4-03 →
   S4-04 → CS-01a → CS-01b → CS-01c (with S4-05a) → CS-02 → CS-03a → CS-04 → S4-06 → S4-07a → S4-07b (debug view
   through `seon.ai/messages`); CS-05 in parallel. **Reset point.**
6. **Recording:** S5-01; S5-02; S5-06; S5-05 → S5-07a..f.
7. **Cut:** S6-01 → S6-02a → S6-02b; **one cursor (P7 = S6-03)** → P12 → P13; S6-04a → **S6-04b including P14** →
   S6-05 → S6-06a → S6-06b (bail; session-end API per §1) → S6-07. **Reset point.**
8. **Proofs:** S7-01; AR-01 → AR-02 (per the §1 REDO) → AR-03. AR-04 deleted.
9. **Uncovered perf rows (new, §6):** test-runner one-test check ≤ 150 ms / 20 MB; process envelope (heap cap 4 GB,
   RSS ≤ 6 GB, mixed-pool burst); live-result retention policy.
10. **C0 gate:** `seon.pass-probe` model row inside the ordinary row (needs 2, 5, 6, 7 and the S6-04b commit count).
11. **Merge:** M0 → M7, M9 → M10 → M3 → M4 → M11 → M5 → M6 → M1 (with a named policy runner) → M2 → M12 → M13.
    These can run from today, in parallel with 3-8.
12. **Competency content (today, parallel):** C2, C3, A1, D1 → D2, D3 (after PR-S1), B1 (T9h), C4, Q1.
13. **After the merge rows:** X1-X4. After S6-06b: B1 (T9), Q2. CS-03b/c (formats B, C). The DATA read stage (after
    P7 = S6-03), then C6.
14. **C7** launch function (after C0) → **C8**: smoke T1 A × REPL on DeepSeek flash, then the full matrix.

## 6. What the rulings require that no plan covers

1. **The settlement-side removal veto.** Confirmed live and filed as
   `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone` (`resources/seon/tasks/seon.turn.batch.edn`).
   Admission §7 S5-04i covers it, but no P row names it: P1 is marked done and P8's row lists "S5-04a-h2 and j". Probe:
   `(defn pa [x] x) (defn pb [x] (pa x)) (ns-unmap *ns* 'pa)`. The unmap answers `nil`, there is no diagnostic,
   `[:seon.fn/sym scratch.prv/pa]` stays stored, and the next pass resolves `#'scratch.prv/pa`.
2. **Test-runner cost for auto-run proofs.** AR-01 sets a < 150 ms / < 20 MB one-test budget and records "today 769
   ms / 74,244 calls" (`reconciled-plan.md:594`). Messages measured 1,166 ms / 86,375 calls and merge prepare 1.7 s /
   175k calls (`merge.md:213`). No slice reduces it, but every code-changing model pass will trigger it. Existing tasks
   (`seon.repl/loading-source-arms-tens-of-thousands-of-calls`, `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls`)
   have no ledger row.
3. **16 GB machine.** "Heap ≤ 4 GB, RSS ≤ 6 GB" (rulings:18; `agent-loop.md:132`) has no slice. The memory lane
   measured 3.87 GB heap and 7.09 GB RSS for 20 simultaneous passes, with no heap cap (`agent-memory.md:9-10`). The
   mixed-pool burst task is "uncovered by these plans" (`:55,88`).
4. **Live result retention.** `seon.sci.eval/live-results-are-held-for-the-contexts-lifetime` (`agent-memory.md:89`)
   is required by "small RETAINED memory" (rulings:17) and has no slice or policy decision.
5. **Policy runner execution.** Auto-accept on the merge message needs an execution owner on root's graph (Flow proc
   or schedule handler) that runs with no provider (§1, merge row).
6. **Debug view of the three-part context** (rulings:6 with :21): no row renders prefix + history + block through the
   assembly function (§1).
7. **"Root must find it easy; a Seon agent can do root's job"** (rulings:38). Merge.md covers the functions. No
   competency task makes a **model** agent act as root on a held proposal (T10-T13 score the agent, and root's repair
   is "outside the agent's score", `competency-spec.md:241-242`). Add one root-role task in which a model agent holds
   the merge task for a held T10 proposal.
8. **Errors through the one value printer as flat maps** (rulings:27): printer R3 and R12 and messages S8 depend on
   the errors cut's constructor (`messages…md:323`). The errors-cut slices are in no ledger reviewed here. The ledger
   must cite them, or "Errors are printed by the one value printer" has no landing date.
9. **Render functions strip garbage first and return data** (rulings:40): printer R10 states it, and printer S8/S9
   convert today's offenders. But no row audits the render functions the current-state block and the merge diff will
   add (CS-02, M3 AI renders) against R10. Add R10 to those rows' proofs.
10. **`:seon.cluster.eval/delivered` placement.** CS-04 stores it "on that pass's call row" (`agent-loop.md:324-325`).
    The call row is the `:seon.turn/*` entity (`reconciled-plan.md:198-199`), while Q3's retained text says "on that
    evaluation" (`:242-243`). An attribute in `seon.cluster.eval` on a turn entity breaks "one schema per identity".
    Decide the attribute's namespace before CS-04.
11. **Lean core values, 2000-token raw-return bound** (rulings added in e9e3b3253 and 68f66cee7, after the plans): no
    plan profiles a function's raw return size, raises a finding over the configured bound, or declares bulk-data
    functions. The messages census measures shown tokens, not raw returns. A new ledger row is needed, owned beside
    the profile (`seon.profile`), with a declared-bulk attribute on the function's contract.
12. **Display-budget override** (`seon.print/*budget*` settable and bindable, `:seon.print/budget` metadata, a ceiling
    tied to the model's window, a finding for a large override): absent from printer R1-R14. R13 ("nothing else sets
    them") must name it. The current-state block's fixed 2,000-token budget (`agent-loop.md:317`) should read the same
    key, so there is one budget mechanism.

## Verification limits

- Code claims were checked by reading HEAD (`src/my/task.clj:163-175`, `src/my/agent.clj:23-34`,
  `src/seon/turn/batch.clj:493-511`, `src/seon/turn.clj:3329-3341,3650-3662,5043`, `src/seon/bootstrap.clj:58-63`,
  `src/seon/cluster/agent.clj:404`). One MCP SCI probe (four short passes, 0-3 ms each) on default, as a disposable
  agent. Its own-branch retirement answered the known bare `admission-closed` error (existing task in
  `resources/seon/tasks/my.agent.edn:7`).
- Not run: any test, any model pass, the in-flight `pb-01`/`repl-p7a`/`out-fix` diffs (not read). Astra's
  independent review was not read.
