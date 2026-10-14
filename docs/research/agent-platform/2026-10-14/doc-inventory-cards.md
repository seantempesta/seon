---
title: "Doc inventory: plan 1-8, schedule, architecture"
type: research
status: inventory
created: 2026-10-14
---

# Doc inventory: plan §1–8, schedule/, architecture/ (lane inv-cards)

Read-only inventory, 2026-10-14, against TASK-CHAIN (READY, f7ce8d41f) and plan §0 (4e70ff8c7). Code claims checked
with rg against `src/`, `test/`, `resources/`. "Installed until S<n>" means the code is today's and TASK-CHAIN §6/§7
changes it at that slice; it is not a stale claim.

## Totals

| Scope | Files | live | needs edits | superseded (delete or pointer) | done / historical |
|---|---|---|---|---|---|
| `docs/seon/plan.md` §1–8 | 1 (judged by sections) | §4, §5, §6, §8 | §1, §2.3, §3, §7 | none whole | §4, §5 |
| `schedule/README.md` | 1 | 0 | 0 | 1 (replace with pointer) | 0 |
| `schedule/agent-loop-ledger.md` | 1 | 0 | 0 | 1 (order superseded; keep §2 Landed as history) | §2 |
| `schedule/triage.tsv` | 1 | 0 | 0 | 1 (2026-10-07 triage, superseded with README) | 0 |
| `schedule/cards/*` | 220 entries (8 READMEs, TASK-CHAIN, 211 cards) | TASK-CHAIN (authority) | 6 | 1 (TASK-CHECK) + the cards listed below | 14 landed per ledger §2 |
| `architecture/` (not README, vocabulary, data-modeling-guide, contracts-guide) | 6 md + 16 decisions | 9 (see below) | 5 | 0 whole; 8 ADRs marked superseded already | 16 ADRs (8 implemented/superseded) |

Architecture scope: architecture.md, agent-runtime.md, clusters-branches-contexts.md, reference-code.md, ui.md and decisions/ (16 ADRs). architecture/README.md, vocabulary.md, data-modeling-guide.md and contracts-guide.md belong to the other lane.
`clusters-branches-contexts.md`, `reference-code.md`, `ui.md`, plus `decisions/` (16 ADRs), `architecture/README.md`

## 1. TASK-CHAIN (the authority, judged for internal consistency)

Verdict: **live, authority**. Checks run:

- `:seon.task/needs` appears on 2 task files with 3 links (§1 says 17 files / 19 links): **stale count in §1**.
  Fix: §1 line "The 17 task-file entries carrying `needs` (19 links…)" → recount with rg and correct the numbers.
- `:seon.config.run/relaunches`, `:seon.wake/starts`: present in code (3 and 9 files), consistent with §1/§6 deletions.
- `seon.task/chain!` exists in `src/seon/task.clj:739` with the installed shape `(declaration templates customizations)`,
  not the §2.1 target; §6 deletes it at S1c. Installed until S1c, not stale.
- `my.task/refine!` (`src/my/task.clj:45`), `seon.task/obligations` (`src/seon/task.clj:521`), `seon.step.edn`
  (resources), `seon/tasks` (24 src files) are installed until S1d-2 / S2c / S4a. Not stale.
- `my.edit/text` is in `src/` (S1d-1 landed, 776a04249). `seon.task/template` and `my.task/update!` are absent
  (target, §1/§3a). `done-refusals` is absent from src (target, §2.4). Consistent with §0.
- `seon.task/close!` (`src/seon/task.clj:1019`) and `my.root/close!` (`src/my/root.clj:117`) both exist. Not in
  TASK-CHAIN at all: §6 should say which survives (open question for root).
- §4 Design "`seon.step.design/handoff` refuses … `:problem-changed`" and §4 Prove predicates: target, consistent.

## 2. plan.md §1–8

Verdicts by section. Quoted claims under 25 words.

| Heading | Claim (quoted) | TASK-CHAIN says instead | Stale-target or installed-until |
|---|---|---|---|
| §1 The goal | "merge after review, retrospect" | MVP is Design → Prove → Implement; merge is root's once per chain (§2.6); no Retrospective stage (§0 of TASK-CHAIN, §4) | stale-target |
| §1 The goal | "Tasks … retire themselves when their proofs are green on main" | Not in TASK-CHAIN. Done is the attempt outcome (§1 attempt, §2.4). No code: `auto-retire` absent from src/test. | stale-target unless root keeps it; decide and write it in TASK-CHAIN or delete |
| §2.3 Red proofs | "`seon.task/assign!` runs the proofs … refuses all-green or unobserved proofs" | assign! is installed (`src/seon/task.clj:881`); target is proofs field and the done rule (§2.4, §6 obligations) | installed until S2c/S3a |
| §2.3 Red proofs | "155 of 783 tasks carry a tagged proof" | a dated measurement; tags deleted at S2c-2 (§6) | installed until S2c-2 (mark as dated) |
| §3 The method | "Mine (a query) → Design … → Merge (root's merge-request task) → Retrospective" | §0 MVP; §2.6 chain merges once; no merge-request task (merge is root's `db<->flow` gate) | stale-target |
| §3 The method | "Each stage is a step task of one subject … the handoff is the subject task at main, edited by `my.task/refine!`" | §1: no subject, no steps; §2.1 chain of linked tasks; §3a `my.task/update!` replaces `refine!` | stale-target |
| §3 The method | "every stage records an explicit decision" | not in TASK-CHAIN §4 (predicates and handoffs refuse, they do not record decisions) | stale-target (or add to TASK-CHAIN) |
| §3 The method | "The first experiment is two steps, Design+Prove → Implement" | §0/§7: Design → Prove → Implement, three chained tasks, S5 Run 26 | stale-target (history) |
| §7 Tasks | "2026-10-10 Tasks are entities upserted … authored = name, intent, must-read; derived = proofs, interface, schemas, status." | §1: `plan`, `instructions`, `proofs` owned; `must-read` deleted; proofs authored, not derived | stale-target, unmarked superseded ruling |
| §7 Tasks | "2026-10-11 The chain is TASK-CHAIN … every stage records an explicit decision, `:unchanged` with a reason" | §4 has no decision record; the owner rulings in TASK-CHAIN header are binding | stale-target |
| §7 Tasks | "2026-10-11 Auto-retire: a task whose proofs are green on main retires itself" | not in TASK-CHAIN (see §1 above) | stale-target unless kept |
| §7 Tasks | "2026-10-11 Vocabulary (Q16): agents have `my.task/current`, `refine!`, `send-back!`" | `refine!` → `update!` (§3a); `send-back!` kept (§2.7) | stale-target (`refine!`) |
| §7 Runtime | "Progress is derived from facts … steps, needs, attempt, end" | §6 deletes steps and needs; §1 attempt and chain fact | stale-target |
| §7 Runtime | "Order is `needs` only, parallel by default … the plan is rendered from needs" | §1: order is `:seon.task/next` (§2.1); needs deleted (§6) | stale-target |
| §7 Runtime | "A stage is a template that is itself a task entry (`seon.step/survey`, `define`, `design`, `prove`, `implement` in `resources/seon/tasks/seon.step.edn`), cloned into each chain's steps by `seon.task/chain!`" | §1: three template files, one per stage; §2.1 `chain!` knows nothing about templates; §6 deletes survey/define and `seon.step.edn` | stale-target (`seon.step.edn` installed until S4a) |
| §7 Runtime | "whose tagged deftest is the step's handoff proof (owner, 2026-10-13…)" | §0 of TASK-CHAIN: proofs are a field, tags deleted; owner rulings 10-13/14 supersede | stale-target |
| §7 Merge | "2026-10-11 (Q8, round 3): … a merge request is a task assigned to root" | §2.6 root merges a chain once; `my.root/merge-requests` installed until S3b | installed until S3b; the wording is stale |
| §7 Merge | "2026-10-11 One close owner `seon.task/close!` decides a merge request" | two close owners exist: `seon.task/close!` (`src/seon/task.clj:1019`) and `my.root/close!` (`src/my/root.clj:117`) | installed state contradicts the ruling; file a task |
| §7 Process | "2026-10-10 Roles: Claude designs; DeepSeek-flash implements non-platform; Opus implements platform" | §0 "Models" (2026-10-14 owner ruling) | stale-target, unmarked |
| §7 Budgets | "2026-10-09 Budgets with four meters (calls, CPU, allocation, storage)" | budget is turns: `seon.turn/turns-left` (TASK-CHAIN §2.7) | needs a sentence on turns |

Live and correct: §0 (judged as baseline), §2 (all five essentials), §4 Done, §5 Superseded, §6 Deferred, §7 Laws,
Proofs, Measurement, Data, and the Merge bullet "2026-10-10 Auto-merge on green is the target", §8.
§7 needs a superseded marker on the unmarked bullets above (plan's own header says superseded ones are marked).

## 3. schedule/README.md (514 lines, 2026-10-07 task tracker)

Verdict: **superseded whole → replace with a pointer** to plan §0 and TASK-CHAIN. Its counts (347 tasks, phases 0–9,
buckets A–E) describe the 2026-10-07 triage; `triage.tsv` (347 rows) is the same data. Its process rules (dual review,
bin/lane) are still in CLAUDE.md. Nothing in the README is a current claim; its "Goals" (Live DeepSeek task at Phase 4,
"a task launches only with named tests") are superseded by TASK-CHAIN and plan §0. Its referrers: 2 files.

## 4. schedule/agent-loop-ledger.md (1247 lines, 2026-10-08)

Verdict: **§1–§4 order superseded; §2 Landed is a done historical record**. Plan §0 says the order is TASK-CHAIN
§7 now. The ledger still says "active" and its §5 critical path names the copies design. Action: replace the file
with a pointer, or move §2 into `docs/research/agent-platform/` as history. Its 12 referrers (script vocabulary,
test/ref_test.clj, two resources, specs/merge.md, specs/agent-loop.md, architecture/README.md, cards) must be
repointed in the same commit. Use of §2 for the card verdicts below.

## 5. schedule/triage.tsv (347 rows)

Verdict: **superseded whole** with schedule/README.md (same 2026-10-07 triage). Two referrers. Delete with the README
or keep as a dated record under research.

## 6. schedule/cards (220 entries)

Method: status lines, last commit (`git log`), landed status from ledger §2, and grep for superseded terms
(`intent`, `needs`, `children`, `effective`, `obligation`, `:seon/tasks`, `refine!`, `relaunch`, `send-back`, `step`).
Most cards were last touched on 2026-10-08 and still say `status: active` or `ready`; the card status lines are not
maintained. "Step" in most cards means the test step (`seon.turn/test-step`), not the task chain, so it is not a hit.

### 6a. Task-model cards that need edits or a supersession marker

| Card | Verdict | Claim (quoted, under 25 words) | Target / action |
|---|---|---|---|
| TASK-CHECK | superseded whole (delete or pointer) | "Per task: pull `[:seon.task/sym :seon.task/intent :seon.task/must-read :seon.task/evidence]`" | intent/must-read/evidence and tag queries deleted (TASK-CHAIN §1, §6); check defers to the done rule (§2.4) |
| BEHAVIOR-SPEC | needs edits | "`:seon.task/entry` is a closed map (name, kind, intent, must-read, evidence); proofs are deftests tagged `:seon/tasks`" | TASK-CHAIN §1 members and §6 tags; the card's Tasks section |
| BEHAVIOR-SPEC | needs edits | "`seon.contracts/task` generates contract tasks whose proof is a row" | §6 generated-task consumers are deleted in tag removal; re-check |
| DYNAMIC-CONTEXT | needs edits (stages 4–7 stay deferred, plan §6) | "missing in this task: intent, must-read, proofs or context?" | TASK-CHAIN §1 (`plan`, `instructions`, `proofs`); §3a |
| ROOT-AGENT | needs edits, superseded in part | "names the task in `:seon/tasks`; otherwise the writer refuses `:uncovered`" | tags gone (§6); merge request and `my.root/merge-requests` (§2.6, installed until S3b); `end-task!`/landing wording |
| REF-1 | done historical (landed: `seon.ref/references` at `src/seon/ref.clj:515`) | "a task intent is …" and `:seon.task/intent` at 128 | rename to `:seon.task/plan` (S1a); card should be marked landed |
| S6-06a | done historical (ledger §2 landed) | "`(deftest ^{:seon/tasks #{'tq.a/prove}} proves …)`"; `my.task/plan!`/`step!` | tags and step verbs gone; mark superseded by TASK-CHAIN §1 proofs field |
| TX-0 | needs edits (one example) | "`^{:seon/tasks #{'seon.cluster.host/a-hosted-forms-write-names-its-evaluation-receipt}}`" | tags deleted (§6); the example uses a removed mechanism |
| TX-11 / CX-11 | needs edits (status "after TX-0" stays until TX-0 lands) | ten or more hits of the terms | re-check against TASK-CHAIN after TX-0 |
| SR-S | needs edits (28 hits) | high-count card; mostly `intent`/`needs` wording | review at next touch; grep before use |
| RP-6a, RP-5, RP-10, RP-16 | live (batch/recording, not tasks) | "`seon.batch/form-step`", "`derive-step`" | installed until their RP slices; not task-chain |
| README-root-tasks, README-agent-entity, README-context | needs review | 6, 5, 6 hits | check `needs`/`steps` wording against §1/§6 |

### 6b. Landed per ledger §2 (done historical; mark status)

CS-01b, CS-01c1, CS-01c2, CS-01c4, CS-01c5, CX-1, CX-2, CX-3, CX-6, CX-7, CX-8, CX-9, CX-10, DEPS-1 ("landed by root
in the restart batch", status line says so), F4a, F4b, F4c, PB-07a, PB-15, PB-16, REF-1, RF-1, RF-2, RF-4, RF-5,
S4-05a, S6-06a, SP-3, UL-2, UL-8a, CO-4, SCI-1, SCI-3, RP-1, RP-11a, RP-12. Ledger landing is itself a claim: each was
not checked against code. Status lines still read active or ready.

### 6c. Retired or superseded by their own status line

- SR-T: `status: retired`.
- COST-REG: slices 2–3 "superseded by FEEDBACK-LOOP" (its own text); slice 1 landed.
- DEPS-1: landed.
- CX-5, CX-8, CX-9: their text names retired slices (S4-04, S4-07a) and "deletion".

### 6d. Live, not task-model (no task-chain claims found; status not re-verified)

Cards with zero or only test-step hits: AE-1..7, A1, AT-8, CC-0..8, CO-0..12 (except CO-4, CO-12 notes), CS-01d,
CS-02, CX-4, CX-12, CX-13, DH-4a, DIAL-2..18 (dials), EN-0..9, F6, F7, FEEDBACK-LOOP (live design of test-step cost;
its "step" is the test step), FK-ASYNC, FK-DH, FK-KONDO, FK-MALLI, FK-SCI, PB-03, PB-04, PB-07b, PB-10, PB-12, PB-14,
PB-17, PLATFORM-STEP, README-audits, README-bench, README-commit-cost, README-recording, RP-* (not landed),
RT-1..14, S4-01, S5-05, S6-05, S6-07a, S6-07b, SCI-2, SCI-4, SCI-5, SP-1a..c, SP-2, SP-4, SR-C, SR-E, SR-ENUMa,
SR-F, SR-H, SR-I, SR-L, SR-P, SR-PERMIT, SR-R0, SR-STATUS, TASK-CHECK (see 6a), TX-1..8, UL-3a/b, UL-4..8, UL-10,
UL-11, USER-SURFACES, VEC-10, RF-3, RF-6, REF-2, CC-*, RP-14 (touched 2026-10-12).
Not read beyond status, last commit and term grep; verdict is "live as far as grep shows", not verified card by card.

## 7. architecture/ (not README, vocabulary, data-modeling-guide, contracts-guide)

| File | Verdict | Stale claim (quoted, under 25 words) | Heading / anchor | Target or installed |
|---|---|---|---|---|
| architecture.md | needs edits | "A parent requires a nonempty complete child set." | §7 Errors and tasks (near "Repair completion") | stale-target: TASK-CHAIN §1 (no children) |
| architecture.md | needs edits | "Repair completion requires every declared test obligation and any subject-local detector obligation." | §7 Errors and tasks | stale-target: TASK-CHAIN §2.4 done rule and §1 `proofs` |
| architecture.md | needs edits | "Parent/needs deletion cannot silently erase an obligation." | §7 Errors and tasks | stale-target: §6 deletes `needs`/`children` |
| architecture.md | needs edits (file:line citations) | 23 `path:N` citations in the file | whole file | CLAUDE.md: living docs cite by symbol, never `path:N`. Not a task-chain claim |
| agent-runtime.md | needs edits | "Repair tasks expose test/detector obligations each turn" | §5 Tasks, source branches and recovery | stale-target: TASK-CHAIN §2.3 (turn reads its proofs field), §4 |
| agent-runtime.md | needs edits | "Start validates its done condition and assignment" | §5 | stale-target: TASK-CHAIN §2.2 (start is an attempt row on the chain branch) |
| agent-runtime.md | needs edits (7 `path:N`) | file:line citations | whole file | CLAUDE.md law |
| clusters-branches-contexts.md | needs edits | "A finished agent's branch is deleted once its task is done or given back and its merge request is closed" | point 9 "Delete the branch, then GC" | TASK-CHAIN §2.6: chain branch retired after publication (installed until S3b) |
| reference-code.md | needs edits (32 `path:N` citations) | rows cite `datahike/src/…cljc:323-367` etc. | Errors, REPL path, Agent loop rows | CLAUDE.md: cite by symbol. Not task-chain |
| ui.md | live except one line | "Task completion, unavailable test evidence, budget exhaustion" | §3 Routes and task context | completion is the attempt outcome (TASK-CHAIN §1); the sentence is fine in target terms |
| decisions/ (16) | live or historical | 8 already marked superseded or implemented; 005, 010, 011, 012, 016 active | per file | no task-chain claims; 016 (per-agent scheduling) mentions steps only in passing |

clusters-branches-contexts.md, agent-runtime.md §4, ui.md: no TASK-CHAIN claims beyond the two above. Not read in
full: ui.md §1, §2, §4; agent-runtime.md §1–§4; decisions 005, 010–012, 016 (grep only).

## 8. Actions for root (ordered)

1. Fix plan.md §7 by marking the unmarked superseded bullets and rewording §1, §2.3, §3, §7 Runtime and Merge (table above).
2. Recount TASK-CHAIN §1 `needs` figures (2 files, 3 links now) and decide whether `seon.task/close!` or `my.root/close!` is the close owner, and whether auto-retire survives.
3. Replace schedule/README.md and triage.tsv with pointers (or move under research); replace agent-loop-ledger.md order with a pointer and keep §2 as history; repoint its 12 referrers in the same commit.
4. Mark TASK-CHECK superseded; mark REF-1, S6-06a, landed cards as done; update the card status lines from ledger §2.
5. Architecture: fix the `obligation`/`child set` text in architecture.md §7 and agent-runtime.md §5 to the proofs field and the done rule.
6. Separate, not task-chain: 60+ `path:N` citations in architecture.md, agent-runtime.md, reference-code.md break the "cite by symbol" law; file a task.

Totals: plan.md 1 file (4 sections need edits, 4 live); schedule 3 index files plus 220 cards (1 superseded card, 6 need edits,
about 25 landed or retired, rest live or unverified); architecture 5 files plus 16 ADRs (4 need edits, 0 superseded whole).
