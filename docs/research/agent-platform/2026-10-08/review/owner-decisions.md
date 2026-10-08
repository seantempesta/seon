---
type: decision
status: for owner
date: 2026-10-08
lane: decisions
---

# Owner decisions: the four redesigns and the landed work

This page reconciles the two redesign reviews (Opus `redo-review-opus-redo-review.md`, Astra
`redo-review-sol-redo-review.md`) and the two landed-work reviews (Opus §6 of its review, Astra
`landed-review-sol-landed-review.md`). The authority is `owner-rulings.md`, then CLAUDE.md. The ledger
(`docs/seon/schedule/agent-loop-ledger.md`) carries every patch named here as a row change. Abbreviations: TF
task-finish, RP pass-and-recording, MA merge-acceptance, EN entrants (all in `../redo/`).

## 1. Verdicts

Opus says patch on all four. Astra says patch on TF and RP and redo on MA and EN. **I recommend patching all four.**
Astra's redo blockers each have a concrete fix, and the two reviews reach the same fix. Neither review disputes the
core of either design: MA keeps S as the repair world, repair by `defn` on S, and integrity at accept; EN keeps one
fold and one RECORD owner. What Astra correctly rejects are specific mechanisms (an agent migration on the writer
thread, the `:no-new-red` rule, a stale same-pass check, deleted admission, no member evidence). Each is replaced
below. A redo would rewrite the parts both reviews accept.

**Agreed by both reviews** (written into the ledger rows as patches, not asked again):
- **Proofs run as a serial step.** After a pass that changed the program, an answer or data, the agent's own proc
  runs the proof-only request after RECORD and before the next submission or model call. There is no proof proc,
  no `{:running? :dirty?}` and no `:running` status, and the next block shows real results. The trigger is the
  pass's effect evidence (`answer!`, `transact!`, `register!` commit before settlement), not settlement tx-data.
- **A question task is derived from its proofs** (a task whose proofs call `my.task/answer`), with no new kind.
  `:seon.task/kind` is part of identity and names a subject (Q9 records Astra's preference for an explicit kind).
- **Auto-accept only when every test in the run on S is green** (`:all-green`). The head comparison is deleted, and
  unknown evidence never accepts. A red that was already red on the head holds the merge for root.
- **Migrations are computed outside the writer** from the head value S was prepared on, then transacted under
  `registry/head-guard-tx` (`src/seon/cluster/registry.clj:157`). If the head moved, prepare runs again (once) and
  the merge is then held. No agent code runs on the writer thread, and no row reaches the head untested.
- **Re-prepare instead of rebasing S.** When the head moves, prepare runs again from the agent's branch. Root's
  hand-repaired S is kept and stays reachable with ordinary `compare-branch`/`take!`. There is no automatic rebase.
- **Accept targets `:seon.task/target`**, fixed at proposal, never the custody branch. Every merge tool takes three
  distinct roles: repair branch S, target and task home. This lands before custody on S (MA-5b).

**Per design, the remaining patches:**

| Design | Patches |
|---|---|
| TF | F0 landed (`3168005b3`). F2/F3 retire into EN-7/EN-6. F4 becomes the serial step. The question schema and answer input (F6) land before outcome (F5). The answer digest enters member reuse evidence, and a changed answer source invalidates green (Astra D3). The status value is `:seon.task/status`, derived and never stored. Steps are struck from CS-01a, S1-06 and AL §3.4 before CS-01a lands (if Q10 is yes). |
| RP | Correct §1: MCP SCI records through `settle-batch!` (`turn.clj:5021`, `:3554`), not `settle`. EN-8a lands before RP-6. A writer-policy row (RP-1w) turns branch integrity checks (`db.clj:5283-5324`) into findings before RP-1. Findings become named variant maps. The failure table follows AL §3.6 and CLAUDE.md: a definite refusal records once more without program facts; an unknown outcome is never retried; a storage failure stops the graph. A FINISH stage delivers late diagnostics through the printer. RP-6 splits into a-d and RP-11 into a-c. `defn` budget: RP's 60/80 MB is an interim row; AL's < 30 MB is the end state. |
| MA | Migration as above (MA-8 splits into a-c, with no conditional design). `:all-green` replaces `:no-new-red`. `:schema-holds` completes, or answers a typed unknown that holds; the 200-entity sample is never integrity. The in-writer dangling re-check is dropped, because MA-9's program-moved guard already covers it. Accept's run is named: proofs ∪ tests reaching the final delta ∪ root's choice, and HEAD's `:changed` reaching request is kept for it. The review runs through root's ordinary path and derives open proposals at start before it listens. `:no-dangling` leaves the dial (it is integrity). MA-16 splits per owner, with MB columns. |
| EN | EN-8 splits: EN-8a moves MCP SCI onto `settle` (needs only EN-2); EN-8b moves the model path after RP-7/RP-10/RP-11. EN-2 no longer waits for RP-7. A member has no pass rows; its pass id, shown value and diagnostics ride its result row in the request transaction (Q4). Writer admission stays (Q5). The review pass gets its own entrant row (Q7). The same-pass check is resolved by Q2. EN is imported into the ledger, replacing S5-07a-f, S6-01, S6-02a/b, F2 and F3. |

## 2. Owner questions

Recommendation first, reason after the dash. ✱ = changes CLAUDE.md text.

**Pass and REPL**
1. ✱ Are Seon's own diagnostics (repairs, findings, error variants) stored as data? *Yes: type, operation, fixed
   message and references; copies of agent values stay shown text* — storing their printed text is a second printer.
2. Does a same-pass `my.test/check` test the new definition? *No: it reads the committed program, says so with its
   tested basis, and adds a `:stale` finding when the pass holds unrecorded definitions it reaches. Live tests use
   `clojure.test` in the agent's world, and proofs run after the pass. MS-11b retires* — one recorded world, and
   never silent. Astra dissents (D1): it would build the check from the live world.
3. How is a `defn` nested inside another form persisted? *As live-only, with a `:live-only` finding; the agent
   defines it at top level to persist it* — recording the enclosing form would replay its other effects on reload.

**Entrants and tests**

4. Where is a test member's evidence kept? *On its result row inside the request's one transaction (pass id,
   shown value, diagnostics); no member pass rows; delete the `:test` origin* — keeps the evidence without rows
   nobody reads.
5. Is writer admission of a test request kept? *Keep it (two transactions per request, +30-60 ms). Revisit only
   with a design that keeps its program, custody and replay checks* — replacing it with a roster ✱ would change
   "admission and retirement are writer-checked facts".
6. How are real external effects in a test handled? *Injected as values by default. A real-effect test runs only
   in a declared isolated JVM, and its receipt lives on the released overlay* — CLAUDE.md already says to substitute
   only external effects.
7. Who evaluates the merge policy, and as what? *Root's ordinary serialized agent path, as a schedule-handler
   entrant with its own origin. The held decision shows in root's current-state block, not its history* — no
   revived system turns, and history filtering stays correct.

**Finishing**

8. Approve proofs as a serial step after a changing pass (amends AL §1.7 and §4.5)? *Yes* — deletes the
   running/dirty state, and the next block shows real status; costs ≤ the proof time per changing pass.
9. Question or code task? *Derived from the proofs (they call `my.task/answer`)* — kind is identity. Astra
   prefers an explicit `:question` kind, to keep scratch helpers out of merges; the derived rule already does that.
10. Task steps? *Delete them, with their callers, before CS-01a lands* — proofs define done.
11. A newer green commit after proposing? *Same merge task with an immutable proposal revision. A review in flight
    keeps its revision and holds `#{:superseded}` if the commit moved. The agent goes to `:wait` at proposal, and
    its branch retires only after actual exit* — root reviews the latest, and repairs are never overwritten.
12. A code task whose program equals the head's? *Done at the home by the completion writer, which checks atomically
    that the head's program digest is still the tested one* — no empty merge, but never equality seen only on a
    worker (Astra D3).

**Merge**

13. Where is a migration named? *On the merge task, as an ordered vector of symbols* — running data changes is a
    merge decision.
14. How big may a migration be? *Bounded by measured scan, output and time at prepare; larger work becomes its own
    root task* — 10,000 output datoms bound neither a loop nor a scan.
15. Which row does S keep for a conflicted identity? *The head's row, marked unresolved* — neither side is accepted
    silently.

**Memory**

16. The live-result retention bound (MEM-03)? *The newest 64 handles; an older handle answers gone-value with its
    saved shown text* — ≤ 20 MB per idle agent.

**Already answered (dropped, with the authority):**
- Another writer's change: overwrite it with a finding — rulings lines 9 and 12.
- ✱ Required-ref removal on a branch: sweep it with a finding; integrity is checked at merge — ruling line 12. Root
  scopes "Required refs refuse invalidating deletion" to accepted programs.
- ✱ "The Var render only reads their status": status lives only in the block — rulings lines 20 and 35. Root
  rewrites the sentence.
- A model reply lost before RECORD: accepted — CLAUDE.md "Recover facts, not execution".
- DATA forms read at the cursor; delimiter repair in code mode — rulings lines 10, 31, 33 and 34.
- Delete the `uncovered` gate — CLAUDE.md "reaching-test counts are shown, not gated".
- `:seon.effect/pass` as the new name — CLAUDE.md "new names for changed semantics".
- `defn` budget and the 400 ms one-test interim: interims are allowed while they stay under GB and name their next
  deletion; end states stay AL's — rulings lines 17 and 45.

## 3. Landed-work defects and unreasonable costs

| Finding | Status (row ids are in the ledger) |
|---|---|
| Stale caller across passes: `[2 3]` where Clojure gives `[3 3]` (F0) | **fixed `3168005b3`**. Root must reload and repeat the probe. Its nine reds already red at HEAD are named only in `2026-10-07/stale-caller/` notes and need a task record |
| A def made through `eval` with no source position refused its origin, and the value was replaced | **fixed `78b5c04ac`**. Delivering an observer failure in the same reply: row added, LR-OBSERVE |
| Storability answered by building 451 error maps per pass (after-defn pass 117-190 MB, 127-137k calls) | **fixed `18e658348`**: 63-64 MB, 57k calls. `ce2f48b7b` proved the armed wrapper is not the cost |
| Reach-declarations cache keyed per branch: 187-205 MB at 0% hits; a first one-test check costs 2.9-3.8 GB | row added: LR-CACHE design (option 2 in `2026-10-07/pass-waste/` notes), then implementation |
| `defn` pass 213-276 MB, 7× its budget; no row fixed it | row added: PB-05 (`seon.schema/a-declaration-write-reloads-the-whole-projection`) |
| P6 reader: `#_ #_ 1 2 3` evaluates 2; bare `true`/`false`/`nil` become comments | row LR-READa/b; **in flight** (lane reader-fix holds `forgive.clj`) |
| P7a: a repair goes to the wrong same-line form and is stored as printed text | row LR-REPAIR, folded into RP-12; **in flight** (reader-fix) |
| test-stack erases frames on the original Throwable | covered by PR-04 (proof amended: frames unchanged, finite cyclic chain) |
| S1-05: `doc` hides a schema-read refusal and evaluates `(ns-publics …)` | row added: LR-DOC |
| Weak proofs (P0 last value only, P4 `not=`, P11 timing, printer-web nonempty, C1 any error) | row added: LR-PROOF |
| P4 diagnostic carries ~30 JVM frames (~1,000 tokens) | covered by RP-7 (admission deleted) and PR-04 |
| `my.agent/retire-branch!` refuses its own run with `admission-closed` | existing task `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`; row added: LR-RETIRE |
| Model pass 0.5-3.1 s, 0.7-1.0 GB, 7 commits | covered on the critical path (PB-02, S4, RP-10, EN-8b, RP-14, gate C0). The owner's "no world" ruling (line 23) means it must not wait behind these decisions |
| Reused one-test check 119-153 MB | covered by F1, EN-6, EN-7 |
| Ordinary MCP pass 52-129 MB; `acquire!` 13-39 ms on an unchanged pass | covered by PB-02b and S5-05 |
| 3,000 printed lines show ~1,500 tokens through a DB re-pull | PR-07, partly landed `9c88ae5b8`; no lane holds it now |
| Boot: 11.6 s between store and source, unexplained | row added: LR-BOOT (measure first) |
| `:seon.sci.eval/loaded-digests` cache: 50% hits, 60 MB | not diagnosed; LR-CACHE's design checks whether it has the same per-branch key |
| Heap max 11 GB | covered by MEM-01 |
