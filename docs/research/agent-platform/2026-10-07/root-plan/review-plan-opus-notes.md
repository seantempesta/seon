---
type: review
status: draft
created: 2026-10-07
lane: review-plan-opus
---

# Review of plan v2 (lane review-plan-opus, 2026-10-07)

Fresh-context Opus review of `live-agents-plan-notes.md` (commit `100f7a13c`). I read `lane-rules.md`, the plan, the
brief and all five research notes end to end. I spot-checked source at HEAD and probed default (after root's 08:54Z
reset). I take the §1 rulings as given and check whether the plan serves them. No src, test or plan file was edited.

Probe state: `bin/seon status` OK, default `:degraded`. Top over-bound warnings are
`seon.schema/call-with-projection-state` (max 3,943 s), `seon.batch/evaluate-batch` (max 94.9 s) and
`acquire-program!` (mean 1.25 s, 34 calls). `seon.mcp/call` averages 1.7 s over 335 calls, with a max of 94.9 s.
These are existing tasks; I add nothing to them.

Disposable agents: `review-plan-opus` (`:agent-c0b508320a7f`) and `review-plan-opus-b` (`:agent-eb1fbe64f24f`). Root
retired both (`:my.agent/retired? true`, 213 ms). JVM probes interned nothing.

## Findings, ranked by severity

### 1. HIGH: "ANALYZE once" sits before EVAL in §4.1, but the analysis consumes what evaluation observed

`seon.turn.batch/analyze-form` (turn/batch.clj:224-236) passes three things into
`seon.fn/runtime-analysis-batch` (fn.clj:944):

- the form's namespace after earlier forms ran;
- the Vars the batch has bound (`:seon.fn/declared`);
- the macro expansions observed during evaluation (`:seon.batch/expansions`, used as clj-kondo hooks).

`runtime-analysis-batch` already accepts an ordered vector of requests and analyzes them in ONE run, with one
namespace form per run of consecutive same-namespace requests (fn.clj:961-985).

So the one analysis must come after EVAL, over the batch of forms with their observed namespaces and expansions. As
drawn (READ → ANALYZE → EVAL), it would analyze `in-ns`-shifted and macro-generated definitions wrongly.

Measured cost of the existing batch function (JVM, default, warm):

| forms | ms | allocated |
|---|---|---|
| 1 | 6.2 | 4.5 MB |
| 10 | 6.0 | 5.1 MB |
| 40 | 6.7 | 7.8 MB |
| 1 (cold) | 23 | 33 MB |

One batched run is therefore cheap in time. But it alone uses the whole 5 MB allocation budget (finding 4). Today it
runs for every form, because the `result/…` binding counts as a write (r-read §4). The one run should be skipped when
the batch observed no definition write.

### 2. HIGH: S4 has no rule for when the model is called again

S4 deletes `next-agent-work`, `continuing-reply?` and the situations dispatch (D2). S5 later deletes
`my.agent/done` and adds "proof tests green". Between S4 and S5, nothing in the plan says whether a closed pass
re-wakes the model or idles. r-turn §6 names this constraint: "deleting `my.agent/done`/dispositions needs another
stop signal or the cap alone ends every episode". The plan never states the replacement.

Required text: S4 names the continuation rule. For example: after a pass, the agent wakes itself again iff the pass
evaluated at least one form and the calls-per-outside-wake bound has room. S5 then adds "and its proof tests are not
all green".

### 3. HIGH: deleting the open-run fence removes the only serialization between JVM-mode MCP and the agent's pass

- MCP SCI runs and model passes are serialized by the agent's proc (`step`, r-turn §6).
- JVM-mode runs are not. `jvm-run` (mcp.clj:254) runs on the MCP thread and is fenced only by
  `record-evaluated-call` refusing `::agent-already-running` while the agent has an open run (turn.clj:1337,
  r-record §3).
- With one transaction per pass, no run is ever open, so that fence is gone.
- §4.1's record writes agent facts (`current-ns`, `answered basis`, `ordinal`) "from the turn's start value". Two
  concurrent passes would compute the same ordinal and overwrite each other's `current-ns`.

Required text: either every entrant, JVM mode included, submits through the agent's one proc, or the agent facts and
ordinals are derived at the writer on head (a `:db.fn/call`, as `program-facts-call` does today). The latter follows
the law "Decide at the authority". The plan's "ONE transaction from the turn's start value" also contradicts how
program facts are made today: they are derived inside the writer on head, with `changed-since-basis` push-back. Keep
that.

### 4. HIGH: the §3 budgets are not reachable as written; the plan should say which stage still blows each one

| Budget | Evidence | Verdict |
|---|---|---|
| alloc < 5 MB | One warm kondo run is 4.5-7.8 MB (finding 1). A raw Datahike `d/with` of a 26-datom tx is 0.84 MB and 0.35 ms, so Datahike in memory is not the problem. An SCI `(+ 1 2)` pass is still ≈ 173 MB whole-JVM (my window: SCI pass plus one JVM pass = 231 MB, minus 58 MB for a JVM pass alone), and a JVM-mode pass ≈ 58 MB. Nobody has attributed the remaining ~170 MB per stage (fault-window, run-observation, settle, reply build). `seon.profile/begin` alone is ~3.5 MB per mark (r-turn finding 6). | unreachable until the S0 probe attributes per thread and per stage; kondo must be skipped when nothing is defined |
| context read N=200 < 5 MB | Datahike pull allocates ~45 KB per entity, 9-14 MB at N=200 (r-context §2.2). Concatenating 183 K chars of history is itself 8.7 MB. | unreachable by pull; either name a datom-level read (`d/datoms`) and measure it, or make the budget proportional to history size |
| store < 50 KB | The floor is 2 × 26.7 KB roots (36%) **plus** ~99 KB of index nodes (64%), scaling ~4 KB per distinct attribute (r-record §4). | unreachable even with §6.3(b); see finding 9 |
| Seon < 200 ms | MCP `(+ 1 2)` settle is 96 ms today, of which fault-window is 34 ms and transact! 59 ms. A test run is 5.8-6.0 s (root's fresh number: one `my.test/check` of a 55 ms test took 6.0 s and 113k calls). | reachable for plain passes only after fault-window and run-observation go; **S5 makes the 6 s test run per pass** (finding 5) |

§3's "Today" column for context read ("8 ms, 17 MB (narrow read)") is not today's path. Today's path is `of-agent`
plus `entity-emission`: 0.4-0.5 s and 0.8-0.95 GB for root, and 180-220 ms and 370 MB for text building
(r-context §2.2-2.3). Put the real number there and the narrow read under "target".

### 5. HIGH: S5 puts the test runner (6 s, 113k calls per check) on every code-changing pass, and no slice owns its cost

The plan's stop-and-fix list has no entry for test-run cost. The existing task is
`seon.test/one-test-run-costs-seconds-around-a-147-ms-body`. S5 as written turns a 6 s, ~6 GB (r-record §1f)
operation into a per-pass cost.

Add to §5: S5 is gated on the test run of one 55 ms test being under 1 s and 10 MB, as its own stop-and-fix slice.
Otherwise S5 must state that proof runs are the agent's own work, outside the budget, and the owner must accept
that.

### 6. MEDIUM-HIGH: crash and replay behavior is unstated, and the plan reverses two recorded owner rulings silently

Three rulings and laws are affected:

- the capture-before-call ruling (2026-07-28);
- the "ONE INTENT COMMIT" design (r-turn §6);
- CLAUDE.md "a crash before settlement … leaves the database unchanged and the work still due".

With one record transaction at the end:

- **Paid call lost.** A crash or refusal after the provider replies loses the attempt row. `spent-runs` counts
  `seon.turn/attempts`, so a deterministic settlement refusal re-runs a paid call on every wake, unbounded.
- **Agent writes replayed.** Agent writes commit mid-pass (§1.5). A crash after one and before the record leaves
  those writes with no pass that answers the wake. The wake stays unanswered, and the model replays the writes (its
  effects too).
  - Today `*open*` (db.clj:184, bound at sci/eval.clj:3999-4000) makes the first agent write carry the run open, so
    the wake is answered and boot's `recover-tx` closes the run.
  - The plan says neither whether `*open*` survives nor what replaces it.

Required: a §6 decision with options.

- (a) Keep `*open*`: the first agent write carries the pass row and answered basis. One record commit in the normal
  case; recover-close stays.
- (b) Accept replay of agent writes after a crash, and say so in CLAUDE.md's recovery law.
- Either way, the refusal arm must still commit the attempt (a second commit on failure only) so the calls bound
  holds.

### 7. MEDIUM: S3 depends on the origin enum, which arrives in S4

S3 "the opening as origin `:opening`" and "exclude outside runs" both need the origin fact. Today opening forms and
outside MCP runs are both `:author :system` (r-context §2.4). The plan introduces the origin enum only in S4.

Move "origin enum on the evaluation" into S3. It is an accreting attribute, so no reset is needed. In S3 every
current writer (`settle-batch!`, `record-evaluated-call`, `test.runner/record-tx`) sets it.

### 8. MEDIUM: §4.1 omits which writer survives and what it carries

"The existing one-commit MCP path … becomes the one owner" picks the `seon.turn` settlement. That is 49 Vars and
1,365 lines (r-delete D4): the refusal arm, deliveries, fault windows, problem assignments. One of those duties,
problem routing, is the producer of open task `5e7b36bfd02d` (an agent's own form errors mailed to another agent).
The other writer, `turn.batch/settle`, is 110 lines.

The plan should list the one writer's duties explicitly and delete the rest. My keep list:

- run row;
- evaluation rows;
- program facts derived at the writer on head;
- staged blobs;
- folded faults;
- the attempt row.

My delete list:

- problem-assignment deliveries;
- the fault-window query (34 ms per settle);
- measurement upserts on the turn transaction.

§5's "profile measurement upserts: S4 writes them only when significant, or they move off the turn transaction" is
not a decision. They are already written only when "significant" (`significant-tx`), yet they are 51-78% of
datoms. Moving them off the turn transaction adds a commit, against principle 5. Decide: store only over-bound
observations, which is all `seon.health` regressions read, or keep them in memory.

Also unaccounted for in "one machinery transaction":

- faults: `seon.fault/fault!` writes up to 2 commits, the agent branch plus default's copy (r-record §1g);
- test evidence: `seon.test.runner/record-tx`, a different entity kind on the agent branch;
- test members' own branch commits.

State which of these are allowed extra commits.

### 9. MEDIUM: §6.3 store-bytes options are incomplete, and §6.2 interacts with them

- Option (b), one smaller root, removes at most ~27-53 KB of a ~152 KB commit. Index nodes are 64% and scale with
  distinct attributes: 27 attributes ≈ 150 KB, a 72-attribute defn ≈ 700 KB (r-record §4).
- Add option (c): fewer distinct attributes per pass record. This is Seon's own lever. Candidates are the written but
  never read `seon.eval/{elisions shown-tokens value-tokens}` and `seon.db.transaction/{hot repeated ms calls}`, plus
  the measurement attributes and the refresh-only evidence (r-record §2).
- §6.2 option 1 (child counts in branch nodes) adds about 1 KB per branch node before LZ4 (dh-fix notes "Store each
  child's count"). A commit rewrites ~10 level-1 branches, so it adds ~10 KB per commit. The owner should see 6.2
  and 6.3 together.
- State an honest store target: ~1 root plus ~4 KB per attribute.

### 10. MEDIUM: factual corrections in §2 and §5

- §2 per-commit floor: "mostly Datahike's 26.7 KB index-root record written twice" is wrong. The roots are 36% of
  152 KB and index nodes 64% (r-record §4).
- §5 labels are swapped: S3 deletes D1 **part c** (prompt, capture, history assembly, 852 lines) and S4 deletes
  D1 **part d** (stored read evidence, ~60 lines) (r-delete). S3's "~850" is c's number.
- §4.1 cites "r-turn §6" for the one-commit submission path. It is r-turn §4 and §5.5.
- §2 refresh datoms: "300-408" leaves out the 202-253 r-record measured. Write 202-408.
- §1.3 "`result/<id>` is the live value": only within the evaluating context's life. After a fresh fork, restart or
  reset, every historical handle binds a `:live-only` marker (`bind-settled-results!`, eval.clj:2786;
  r-context §2.5). Dig forms in stored shown text then answer the marker. State that.
- §4.2 "single-read stage already exists": true, but its `sci.batch/read-next` (sci/batch.clj:23-27) calls
  `forgive/next-form` in `:reply` mode, whose `fixed` Parinfer delay (forgive.clj:216) is the cubic cost r-read §4
  measured. Adopting `turn-request` is safe only after `forgive-fix` fixes `next-form`, not just `segments`. Make S4
  depend on P0's forgive-fix covering `next-form` (edamame option normalization included).
- §2 "MCP SCI `(+ 1 2)` is already ONE commit": only when its forms write nothing. A form that calls `transact!`
  makes 2 commits, and the first carries the run open (r-record §1c).

### 11. MEDIUM: P0 should also fix the producers its probes depend on

- **Measurement probe cost.** `seon.profile/begin` costs 18.6k armed calls and ~3.5 MB per mark (task
  `561d2f7ee288`). Every S0 number carries it, and it is 70% of the allocation budget. Fix it in P0, or S0 cannot
  measure the budget.
- **Top open fault.** The refused-imitation entry has no error value, so `show-row` → `shown-failure` nil
  (batch.clj:390, turn.clj:4741; tasks `7f5dfb6ad7a2`, `fe8f23d6095c`). It is a small producer fix, and it is what
  §1.7 "imitated results are flagged" relies on.
- **Leftover branches after the reset.** The reset did not clear them. `(my.agent/branches)` still lists ~16 roster
  branches with no agent entity on default, among them r-turn-a `:agent-cfd70b5547f7`, r-context
  `:agent-aeeb04b9c485`, lp-add-2 `:agent-4512788dda3b` and `:agent-f3c728c39a5f`. Root's message said pre-reset
  probe branches are gone, but the roster disagrees. P0's "retire leftover probe branches" is still needed, and
  either the reset or the roster has a defect worth a task (UNVERIFIED which).
- P0's stale `user.sgprobe/fix` row and §6.1 are moot after the reset (root's message). Delete both.

### 12. LOW-MEDIUM: open semantics for the JVM and test entrants

§4.1 says EVAL is "SCI in the agent's world". By CLAUDE.md the evaluator is a stage value: JVM mode supplies host
evaluation, and today it has no derive stage, so no program facts. State that:

- JVM mode records evaluation rows only (no analysis, no program facts);
- test entrants record on the test branch, with `:test` origin, unlinked afterwards.

"Outside runs never enter an agent's history" then follows from origin, not from branch placement.

### 13. LOW-MEDIUM: the DATA presentation as text is a hand-built printer

§4.3 DATA: "each exchange as a map holding its stored strings, concatenated, never re-printed as one value". Building
`{:ns … :form … :shown …}` text by concatenating strings is a printer of map syntax outside the value printer, which
the one-printer law refuses. REPL concatenation is a transcript of stored text and is fine. Name how DATA is
produced inside the law: the value printer with history-specific profile settings (whole, per the owner), or a
render whose output the printer prints. Otherwise drop DATA from S6.

### 14. LOW: unbounded history versus the provider window

"History unclipped for now" is a ruling. The plan should still name the failure when the context exceeds the
model's window. Root's history alone is ~1,387 exchanges, ≈ 1.25 M chars at r-context's 181 K chars per 200. The
pass must return a typed error value at the provider boundary, not a provider 400 surfacing as a fault.

### 15. LOW: there is a gap between S2 and S3

S2 deletes the generated opening, and S3 adds the opening forms. In between, a model agent gets no help and no task
doc. Say that no model passes run between S2 and S3, or fold the opening's first form into S2.

## Slice feasibility (question 2)

- **P0.**
  - Fine, minus the moot reset item.
  - The Datahike gitlink bump is boot-critical. Prove it per CLAUDE.md, by publish and adopt on a live-store
    branch.
  - dh-fix says "no reset needed" for the bump. 6.2's format change is a separate later item.
- **S0.** Feasible with the HTTP fixture. Overlay cleanup needs hand retraction: `settings!` cannot remove a key, and
  `no-auth` is `[:= true]` (r-turn §1). The probe must measure per-thread allocation, not whole-JVM.
- **S1.** Feasible and independent.
  - The new `(help)` must not teach the S4/S5 protocol while turns-left and done still drive the loop.
  - Either rewrite help twice (S1 and S4), or land only the lookups in S1 and the help text with S4.
- **S2.** Feasible. `bootstrap/seed-tx` must stop seeding `:generate` in the same commit (r-delete D1b). The D1a
  ledger must read `of-agent` with an explicit selector.
- **S3.** Needs the origin enum moved in (finding 7). D1c retires context and capture schema, so it is a reset
  point; resets are now free per root.
- **S4.** Too big for one review: −2,500 to −3,400 lines plus a new pass. Split it into two landings that each keep
  HEAD booting and MCP working:
  - **S4a.** One owner writer, origin and ordinal at the writer, JVM and test entrants converted, faults folded. The
    situations dispatch is still alive.
  - **S4b.** Delete run machinery, permits, backstop, episodes, D5 and the run open. This needs the continuation
    rule (finding 2) and the crash decision (finding 6).

  r-delete's constraint stands: D5 cannot precede the end of per-form source rows at open.
- **R.** Moot as a single event. Each schema-retiring slice resets when it lands.
- **S5.** Blocked on test-run cost (finding 5).

## Simplicity (question 3)

- **Delete instead of change:**
  - the fault-window query;
  - problem-assignment deliveries;
  - measurement upserts on the pass;
  - the six run-opening writers down to one (r-delete "Other dead" 1);
  - the five history readers down to one narrow read (ibid. 2);
  - `run-observation`'s zip of two readers (cluster/agent.clj:852): the reply is the pass's own value, not a
    re-read of what it just settled;
  - `seon.agent.loop` (230 lines, test-only; r-delete).
- **Does the one-of-each invariant hold for all entrants?**
  - Read: yes, with the one-read stage, once `next-form` is fixed.
  - kondo analysis: yes, after EVAL and skipped when nothing is defined.
  - Batched query: unspecified for JVM mode.
  - Machinery transaction: not for faults (2 commits) or test evidence. Definitions are inside it, and blobs are
    files, not commits.
  - Effects: the agent's own writes are outside it by ruling. Messages sent by Seon on the agent's behalf (problem
    assignments) should be deleted, not kept.

## Concrete plan-text changes

1. §4.1 diagram: READ → EVAL → ANALYZE (one `runtime-analysis-batch` over the forms with observed ns, declared Vars
   and expansions; skipped when no definition write) → RECORD (derived at the writer on head: program facts,
   ordinal, current-ns, answered basis) → PRINT. Replace "from the turn's start value" with "validated and derived
   at the writer on head".
2. §4.1: "Every entrant submits through the agent's one proc", or the writer-derived alternative (finding 3).
3. §4.1: list the one writer's duties and its deletions (finding 8). Decide measurements: "store only over-bound
   observations".
4. §5 S3: add "origin enum (accreting)". §5 S4: split into S4a and S4b. Add the continuation rule to S4b.
5. §5 labels: S3 is D1c, S4 is D1d. P0 adds the `profile/begin` fix, the show-row nil producer fix and branch
   retirement. Drop the stale-row item.
6. §5: a new stop-and-fix entry for the test-run cost (6.0 s for a 55 ms test), gating S5.
7. §3: "Today" for the context read is 0.4-0.5 s and 0.8-0.95 GB (`of-agent`). Mark the alloc and store budgets as
   unreachable today, with the stage that blows each (finding 4).
8. §2: correct the floor attribution (36% roots, 64% index nodes) and `result/<id>`'s lifetime.
9. §6: delete decision 1 (moot). Add:
   - the crash/replay decision (finding 6);
   - option (c) to decision 3, with 6.2's per-commit cost noted;
   - "DATA text inside the one-printer law, or drop DATA" (finding 13);
   - "reverse the capture-before-call and intent-commit rulings", stated explicitly.

## Questions

1. Does the owner accept that a crash after an agent write and before the record replays the model's writes (6b),
   or should the first agent write keep carrying the pass open (6a)?
2. Does the JVM entrant move onto the agent's proc, or do the ordinal and current-ns become writer-derived?
3. Are proof-test runs in S5 Seon work (inside the budget) or agent work (outside it)?
4. What is the continuation rule after a pass between S4 and S5?
5. The reset left roster branches with no agent entity. Is that intended (storage retained for GC), or a reset
   defect?

## Design check

The plan's direction holds against the code. The single-read stage, batched kondo analysis and the one-commit
submission path all exist and are reusable.

What was wrong is ordering and omission:

- analysis must follow evaluation;
- origin must precede S3;
- the one-commit design removes the only JVM-mode fence and the crash answeredness that `*open*` provides;
- the budgets need per-stage attribution before they can be claimed.

Verification limits:

- Allocation windows are whole-JVM across MCP calls, so they are upper bounds.
- I did not re-run a model turn.
- I did not reproduce the pre-reset 1.28 s JVM re-acquisition: on my fresh agent, a JVM call after a `defn` cost
  `acquire-context!` 19 ms and no `acquire-program!`.

No RESET NEEDED.
