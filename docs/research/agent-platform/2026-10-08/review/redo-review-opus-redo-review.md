---
type: research
status: review
date: 2026-10-08
lane: redo-review-opus
---

# Review of the four redesigns (task-finish, pass-and-recording, merge-acceptance, entrants)

Reviewer: a fresh Opus lane, independent of Astra. Authority: `review/owner-rulings.md`, then CLAUDE.md. I read the
four designs, the ledger, `agent-loop.md` and `merge.md` in full. Code is cited at HEAD `b5171f522`. Probes ran
through MCP SCI as agent `redo-review-opus` on its own branch, in scratch namespace `rro.scratch`. Nothing was defined
in default's JVM.

Abbreviations: **TF** `redo/task-finish.md`, **RP** `redo/pass-and-recording.md`, **MA** `redo/merge-acceptance.md`,
**EN** `redo/entrants.md`, **L** `schedule/agent-loop-ledger.md`, **AL** `specs/agent-loop.md`.

## 0. Verdicts

| Design | Verdict | Why, in one line |
|---|---|---|
| TF | **patch** | Sound shape. F2/F3 are superseded by EN-6/EN-7. The proof proc can be a serial step. "Question" can be derived from the call graph. The steps deletion collides with CS-01a/S1-06. |
| RP | **patch** | Sound shape. §1 misstates today's MCP recording owner, which inverts the RP-7 ↔ EN-2/EN-8 order. The `defn` budgets contradict AL §2. RP-6 and RP-11 are far over 15 minutes. |
| MA | **patch (heavy)** | `:no-new-red` keeps the defect it claims to fix. It runs agent code inside the writer. Merge custody on S makes `accept-merge!` land on S. The review proc misses proposals after a restart. The in-writer dangling check is redundant. |
| EN | **patch** | Right direction: one RECORD owner. The slice order runs backwards against RP-7. The `:test` origin no longer has rows. F2/F3 are duplicated under two descriptions. The ledger has not imported EN. |

No redesign needs a redo. All four agree on the core: one fold, one recording transaction, findings instead of
refusals, proofs only during work, and integrity at merge. The defects are local, but several sit on the critical path
(§2.1 and §2.2), so fix them before approving the ledger order.

Landed work (§6): most claims hold on default. Two broke the REPL law: PB-01's overlay keeps an earlier pass's callee
(F0), and lazy-observe replaces the value when a definition has no source position. Every pass kind measured is
over its budget except forgiveness and per-agent retention. Two unreasonable costs had no task, and I filed them:
the reach-declarations cache keyed per branch (187 MB at 0% hits; a first check costs 3.8 GB), and storability
answered by building 451 error maps per pass.

## 1. Rulings

| Ruling | TF | RP | MA | EN |
|---|---|---|---|---|
| Real-REPL serial law | holds (F0 fixes a live violation) | holds; same-pass reads open (§1.1) | holds (repair is ordinary `defn` on S) | holds; Q3 conflicts with L MS-11b (§1.1) |
| One transaction only records | holds | holds; RP-4 `register!` is an effect | migration inside the writer (§1.3) | holds |
| Branches never refuse | holds | holds (RP-1, RP-7 findings) | holds; integrity at accept | holds |
| Lightweight forgiveness | n/a | holds (≤ 3 ms / 8 MB) | n/a | n/a |
| Three-part context, block current | holds; steps clash (§2.5) | n/a | n/a | n/a |
| Proofs only during work | holds after F1 | n/a | n/a | holds |
| Reaching tests at merge | holds | n/a | `:no-new-red` unsafe (§1.2) | holds |
| Render functions return data | holds | holds | holds | holds |
| One value printer, errors included | holds | holds (repairs and diagnostics as data) | holds | holds (member values through the printer) |
| Clojure terms | `:task/status` is not a Seon key (§1.5) | holds | holds | holds |
| Lean values | holds | holds | diff value small; holds | holds |
| 16 GB, 20-40 MB per pass | holds (budget T) | **contradicts AL §2** (§1.4) | **no memory target at all** (§1.4) | holds |
| Root is an agent | n/a | n/a | custody S breaks accept (§2.3) | review pass origin `:outside` (§2.4) |
| Auto-accept | F5 proposes automatically | n/a | holds, but the criterion is unsafe | n/a |
| Bail | F7 holds | n/a | n/a | n/a |

### 1.1 Same-pass reads: three documents disagree

- RP §8.3 (`pass-and-recording.md:228`) recommends that "program tools read the live world".
- EN §6.3 (`entrants.md:147-150`) recommends that `my.test/check` keeps reading the committed program, because "a
  second world source" would be wrong.
- L MS-11b (`agent-loop-ledger.md:183`) wants `my.test/check` to read the live world "(the REPL law)", blocked on RP
  §8.3.

`my.test/check` is a program tool, so RP's rule and EN's rule contradict each other. **Resolution (recommended):**
- The REPL law concerns Clojure: `clojure.test/test-var` and `run-tests` already run in the agent's live SCI world
  (S7-01's adapter, `agent-run-test-var`).
- `my.test/check` is Seon's recorded-proof request. It reads the committed program by design, and help says so.
- RP §8.3 narrows to `doc`, `source`, `dir` and the `my.program` lookups.
- MS-11b is retired.

This needs no second world source and leaves no REPL-valid form broken.

### 1.2 `:no-new-red` still reads silence as health (MA)

`merge-acceptance.md:101` says the criterion holds when "every reaching member of the run is green on S, or red on
both S and the head. A member that is unknown on the head counts as red (Astra §11)." A member that is red on S and
unknown on the head therefore counts as "red on both", and the criterion **holds**. A red reaching test with no head
evidence is excused, which is exactly what Astra §11 flagged (`review-astra-plan-review.md:177`: "absence/unknown on H
is not positive evidence").

During work only proofs run (ruling line 37), so the head rarely has evidence for a reaching member. Under this rule,
`:no-new-red` is almost always vacuous.

**Fix (simplest):** replace `:no-new-red` with `:all-green`: every member of the run on S is green. Delete the head
comparison. A red that already existed on the head holds the merge for root, who may accept by hand (§3 of MA
already allows that). This is cheaper because nothing is read on the head, and it never accepts on missing evidence.

### 1.3 Agent code inside the writer (MA §1)

`merge-acceptance.md:32-34` says: "At accept, it runs **inside the writer**, in the transaction function that
carries the replacement." The migration is an agent-written function, possibly interpreted from S's program. That
conflicts with two laws:
- CLAUDE.md "Bound admission and await actual exit": SCI interrupts interpreted code, not host calls, so isolate
  what cannot be terminated.
- The one writer serializes the store's transactions, so a 10,000-datom migration on the writer thread stalls every
  writer.

**Simpler alternative:** compute the migration's tx-data outside the writer from the head value read at accept. Then
transact it guarded by `registry/head-guard-tx` (`src/seon/cluster/registry.clj:157`, installed), so the transaction
refuses if the head moved since it was read. On a refusal, `review!` re-reads once, then holds `#{:head-moved}`. The
rows written after prepare (T12) are still migrated, because they are read at accept, not at prepare. This uses an
installed guard instead of a new writer stage.

MA-8's "expand-then-contract if the probe needs it" leaves a design decision to the implementer. The source answers
most of it: Datahike updates the schema per datom inside one transaction. `with-datom` calls `update-schema`
(`reference-code/datahike/src/datahike/db/transaction.cljc:466`), and `validate-datom` reads the in-progress db's
schema (`:46-52`). So with `:schema-flexibility :write` (`src/seon/db.clj:140`), an attribute installed earlier in
the same tx-data can be asserted later in it. *Still uncertain until MA-8's probe runs*, but the conditional branch
should be dropped from the slice unless the probe refutes this.

### 1.4 Budgets

- RP's targets for a `defn` pass are ≤ 60 MB and for five `defn`s ≤ 80 MB (`pass-and-recording.md:179-180`). AL §2
  (`agent-loop.md:80`) and L budget O say a definition pass is **< 30 MB**. One of the two must change. Recommend
  keeping AL's < 30 MB as the end state, with RP's numbers as an interim row named as such.
- MA §6 states wall-time targets only. Prepare makes 175k calls today (`merge.md:205`), but no row has an allocation
  number or target. CLAUDE.md "never GB per operation" needs one. Add MB columns to MA §6 and to MA-16's proof.
- EN and TF agree on the one-proof budget (< 150 ms / < 20 MB). TF Q4's 400 ms interim is acceptable only because
  F1 is a stated step toward it.

### 1.5 Smaller rule points

- TF's block uses `:task/status` (`task-finish.md:127`). CLAUDE.md requires fully namespaced Seon keys: use
  `:seon.task/status`, which does not exist as a stored attribute today (checked in `seon.task.edn`), so the name is
  free for a derived value.
- RP says the refused recording makes "one second attempt" (`pass-and-recording.md:91-93`). AL §3.6
  (`agent-loop.md:309`) has three levels: retry without program facts, then the call row and the error alone, then
  storage failure. Align RP-7 with AL.
- RP's findings `[:live-only sym]` and `[:defined-outside-this-form sym]` (`:103-104`) have no stated consumer or
  meaning. CLAUDE.md says "Every stored fact names its consumer or is deleted". Define them or drop them.

## 2. Fit between the designs

### 2.1 The MCP recording owner, and an inverted order (RP × EN): verified at HEAD

RP §1 says MCP SCI records through `settle` (`turn/batch.clj:729`) with `rebase`/`revalidate`
(`pass-and-recording.md:44-45`). **Contradicted at HEAD.** The model and MCP SCI requests drop RECORD with
`(dissoc :seon.batch/settle)` (`src/seon/turn.clj:5021`) and record through `settle-batch!` (`turn.clj:3554`). That
function calls `turn.batch/program-facts-call` (`:3601`) and, on a refusal, `turn.batch/revalidate` and `not-admitted`
(`:3654-3662`). EN §1 is right.

Consequence: RP-1, RP-6 and RP-7 prove through MCP SCI, but they change `settle` in `turn/batch.clj`, which MCP SCI
does not run until EN-8. RP-7 would therefore have to edit `settle-batch!` too, and EN-8 then deletes that work. EN
orders RP-7 → EN-2 → EN-8 (`entrants.md:123,129,132`), which is backwards.

**Fix:**
- Split EN-8. EN-8a moves MCP SCI onto `settle`, depends only on EN-2, and lands **before RP-6**.
- EN-8b moves the model path after RP-10/RP-11.
- Drop EN-2's dependency on RP-7.

Then every RP row edits one RECORD owner. RP §1 lines 40-45 should be corrected in place.

### 2.2 Duplicate rows under two descriptions (TF × EN × L)

| Row | TF says | EN says |
|---|---|---|
| F2 = EN-7 | "`clean-request-handle` writes the member overlay as one branch transaction" (`task-finish.md:163`) | "overlay digest in the checkpoint key" (a cache; `entrants.md:128`) |
| F3 = EN-6 | "member recording in one transaction (4 → 1)", `runner.clj` only, deps F2 (`:164`) | the whole request in one transaction, held fact past the bound, process roster, admission deleted; deps EN-4, F1 (`:127`) |
| F4 deps | F3, S6-05, S7-01 (L:266) | EN-6 (`entrants.md:136`) |

These are different mechanisms. Recommend EN's versions, which delete more (the admission transaction, the attempt
turn), and retire TF's F2/F3 descriptions. The ledger has not yet imported EN. L §1 (`:64-65`) still says "S5-07 and
S6-02 undecided", and RP-14 still depends on "S6-01, S6-02b" (`:125`). Root imports EN-0..EN-9 and rewrites those
three dependencies.

### 2.3 Merge custody on S makes accept land on S (MA × installed `accept-merge!`): verified at HEAD

MA-5b: "When that task is its current assignment, its evaluations read and write S" (`merge-acceptance.md:56-57`).
The installed `my.program/accept-merge!` docstring says: "The merge lands on the branch my context holds: root's is
its cluster" (`src/my/program.clj:556-557`). It accepts into `context`'s connection. Under MA-5b, root's context is
S, so accept would merge S into S.

MA never names the target. **Fix:** accept's target is the merge task's `:seon.task/target`, which MA-14 already
introduces. It is set at proposal from the assignment's cluster head. Accept never targets the custody branch.

A second effect of custody: while root holds merge task m1 as current, the review proc's `review!` for m2 runs in
root's world with custody S1. `review!` takes `m` explicitly, so it must ignore custody for prepare and accept. State
this in MA-12.

### 2.4 Origins and entrants

- EN's test member has origin `:test` but "none of its own" rows (`entrants.md:54`). With no member rows, no row
  carries `:test` any more: delete the origin, or name the row that carries it. AL `:251` and `:154` ("1 member
  pass") need correcting in place.
- The review proc's pass is labelled `:outside` (`entrants.md:64`). It is neither an outside agent nor MCP. CLAUDE.md
  lists "schedule handlers" among the entrants: give the proc-driven evaluation that entrant's row in EN's table and
  decide its origin (question 9 below), rather than mislabel it.

### 2.5 Steps (TF × L × AL)

TF F8 deletes steps (recommended). But CS-01a, which is `ready` and on critical path step 4, renders "Task (current
declaration, steps)" (`agent-loop-ledger.md:230`). S1-06 renders a task's "current step" (`:206`), and AL §3.4 lists
"ordered steps" (`agent-loop.md:223`). If F8 is approved, strike steps from CS-01a, S1-06 and AL now, before CS-01a
lands, or the block is built and then cut.

### 2.6 Proposal lifecycle (TF × MA)

- TF F5 replaces the open proposal when a newer green commit arrives (`task-finish.md:81`). MA keeps S per merge task
  as `:seon.task/candidate`, and the review proc reacts to each new proposal row (`merge-acceptance.md:112-116`).
  Neither says whether a replacement is the same merge task (S must be re-prepared) or a new one (the old S retires).
  It also leaves a race open: the policy may accept commit C1 while the agent is still writing C2 on the branch that
  accept retires (MA-15).
- **Recommend:**
  - The same merge task, with its proposal row updated.
  - A review in flight compares the proposal's commit at accept and holds `#{:superseded}` if it moved.
  - Accept retires the proposer's branch only after the agent's actual exit, and the agent's disposition becomes
    `:wait` at proposal. The block already offers `my.turn/wait`.

### 2.7 Crash semantics

| Design | Crash before its write | Gap |
|---|---|---|
| RP | reply lost; wake re-asked; effects stay | none |
| TF | proc re-signals at start | none |
| EN | request rederived; member overlay gone | effect receipts of members live only in memory, by design (EN Q5) |
| MA | — | **the review proc `d/listen`s for new proposal rows** (`merge-acceptance.md:113`): proposals that arrived while it was down are never reviewed. CLAUDE.md "Listen before deriving work": at start, derive the open unreviewed proposals from the home, then listen. |

### 2.8 Effect attributes

EN-1, RP §4 and L S6-01 agree on `:seon.effect/pass` plus `:seon.effect/form-ordinal`, with the old names retired.
RP-15a's recovery scans receipts "whose pass id has no recorded evaluation". Under EN, test-member passes never have
rows, but their receipts live only on the in-memory overlay store (`src/seon/test.clj:1875-1881`,
`store/open-overlay!`, verified), so boot never sees them. Consistent. Say so in RP-15a, so nobody adds member rows
to "fix" the scan.

### 2.9 The merge gate's test runs

EN says the gate's run rows are test data and do not trip `program-written-since?` ("verify in MA-9"). The function
reads `program-digest-read-attributes`, which are the program attributes plus the identity attributes plus
`:seon.source/digest` (`src/seon/test/runner.clj:1180-1201`). *Still uncertain:* whether recording a member result
writes an identity attribute such as `:seon.test/sym` through an upsert. MA-9's proof must record a run on S and then
assert `(false? (program-written-since? S basis))`.

### 2.10 Acyclicity and slice size

With §2.1's split, the combined order is acyclic:
- F0 → PB-02 · EN-0/EN-1/EN-3
- EN-2 → EN-8a → RP-1..RP-9 → RP-10 → RP-11 → RP-7 → EN-8b → RP-12/13/16/17 · EN-4 → EN-5 · F1 → EN-6, EN-7
- RP-14 (EN-1, EN-5, EN-8b) → RP-15a → S6-05 → F4 (EN-6, S7-01) → F5 (MA rows) → F6 → F9 · MA serial in M

Without the split, RP-7's proofs cannot pass on the path they name before EN-8 lands, while EN-8 waits on RP-7: an
effective cycle.

Slices that are not ~15 minutes, or leave decisions open:

| Slice | Problem | Split |
|---|---|---|
| RP-6 | four former slices (h2, d, e, f) plus two deletions | (a) end stage derive → fold → record; (b) delete `apply-pending` and the stages; (c) value-def retire; (d) unjoined writes as findings |
| RP-11 | the cursor for two entrants, plus deleting ~12 functions | (a) MCP cursor; (b) model cursor; (c) deletions with their last callers |
| MA-8 | probe + attribute + prepare run + writer run + conditional design | (a) attribute and naming; (b) run on S at prepare; (c) the guarded accept of §1.3 |
| MA-16 | "profile and fix the owners": open-ended | one row per owner the profile names |
| EN-6 | one transaction + held fact + process roster + admission deletion | (a) one transaction; (b) roster and held fact |
| F4 | the proc + the graph table + restart | smaller under §3.1 |

Proofs that assert no new data: F2 and EN-7 name an existing, already-green test plus a budget row. F3 is a budget
row only. RP-0 and MA-16 are probes. Each needs one data assertion, for example EN-6's "transactions carrying the run
id = 1".

## 3. Simplicity

1. **The proof proc becomes a serial step in the agent's proc (TF F4).** The agent's proc already serializes passes.
   Run the proof request in that proc after a pass's reply is delivered and before the next submission or model call,
   under the test bound. This deletes:
   - `{:running? :dirty?}`, `:running` status, and overlap by construction;
   - the self-signal on each assignment (keep one at proc start for crash recovery);
   - most of AR-03's wake path (a red found in the same step after a `wait` pass wakes the agent directly).

   It also fixes a real defect of the async design. A model pass continues at once, so the next call's block usually
   shows `:running`, and the model acts blind for a whole provider round trip. Serially, the next block shows the
   result. Cost: the next pass waits up to the proof time (target 150 ms). It stays "off the pass" for the reply's
   latency, so AL §1.7 holds.
2. **Derive "question task" from the call graph, not a kind (TF F6).** `:seon.task/kind` is part of the task's
   identity aspect `[ns name kind]` and is documented as a *subject* kind (`resources/seon/schemas/seon.task.edn:8-9`),
   so `:question` mixes purpose into identity. CLAUDE.md says "Never author what the call graph already derives": a
   task whose proofs call `my.task/answer` is a question task. That is derived from the proofs' analysis, with no
   schema change, and it still keeps the agent's scratch helpers from being proposed. (An even lighter alternative,
   `(def answer …)` read by the proof with reach-digest invalidation for free, contradicts the owner-approved
   `answer!` ruling, line 19. Mentioned only.)
3. **Merge: drop the in-writer dangling re-check (MA-6/MA-9).** MA-9 already refuses any program write on the head
   since prepare's basis. With the program unchanged, dangling callers on the final value equal those computed on S at
   prepare, because data writes do not add callers. Keep only the in-writer `:schema-holds` re-check, since new data
   rows can violate it.
4. **Merge: rebase is just prepare on S (MA §2).** No new mechanism is needed. When the head moved, the policy
   re-prepares from the agent's branch: it has no repairs to keep. Only root's hand-repaired S needs the rebase, and
   that is `compare-branch` with S's fork as base, an installed call. Keep it as a documented use, not a slice.
5. **Delete `my.test/check` from help (TF F9).** Proofs run themselves, and the live-world test is `clojure.test`.
   One fewer recorded-run verb in the static prefix.
6. **Delete the `:test` origin (EN)** when member rows go (§2.4).
7. **MA's migration** uses the head guard instead of a writer stage (§1.3).

## 4. Verified at HEAD

| Claim | Result | Evidence |
|---|---|---|
| TF §0.1 stale caller | **supported, reproduced** | pass 1 `(defn g2 [] 1) (defn h2 [] (g2)) (defn g2 [] 2) [(h2) (g2)]` → `[2 2]`; pass 2 `(defn g2 [] 3) [(h2) (g2)]` → **`[2 3]`** (Clojure: `[3 3]`), agent `redo-review-opus` on default. The `stale-caller` lane holds `src/seon/sci/eval.clj` uncommitted. F0 must land before PB-02 rewrites the same overlay (group C), or PB-02's reverse-closure install should be the fix. |
| RP §1 unmap undone | **supported, reproduced** | pass 1 `(defn pa …) (defn pb [] (pa)) (ns-unmap *ns* 'pa) (resolve 'pa)` → `nil`; next pass `(resolve 'pa)` → the Var again; the unmap's reply carried **no diagnostic** |
| RP §1 MCP records via `settle` | **contradicted** | `turn.clj:5021` dissocs `:seon.batch/settle`; `settle-batch!` `turn.clj:3554`, `revalidate` call `:3654` |
| EN §1 model/MCP via `settle-batch!` | supported | same lines |
| MA-5b custody S compatible with accept | **contradicted** | `src/my/program.clj:556-557` |
| MA §3 `:no-new-red` fixes Astra §11 | **contradicted** | `merge-acceptance.md:101` vs `review-astra-plan-review.md:177` |
| MA `head-guard-tx` exists | supported | `registry.clj:157` |
| MA-8 same-transaction attribute install | **likely supported, unprobed** | Datahike `transaction.cljc:46-52, 466`; `:schema-flexibility :write` at `db.clj:140` |
| TF `select` adds `(keys pending)` | supported | `src/seon/test.clj:882` |
| TF `propose-merge!` asks `:named` with identities | supported | `src/my/program.clj:449` |
| EN member branches are an in-memory overlay | supported | `test.clj:1875-1881` |
| `program-written-since?` ignores test rows | **uncertain** | `runner.clj:1180-1201`; needs MA-9's assertion |
| TF help teaches `(my.agent/done)` and steps | supported | `src/seon/bootstrap.clj:58, 63` |

Health during the probes was `:faulted` (6 open faults, 37 errored receipts, 12 stale Vars). The worst entry is
`seon.schema/call-with-projection-state` at a mean of 5.86 s and a max of 202,688 ms over 194 calls; it already has
tasks `c4ece4f4eec9`/`d27179bab996`. `my.program/accept-merge!`'s retire fault is G8/MA-15. All of these were already
open. The defn reply still carries `#:seon.render.var{:contract :missing … :example …}` (about 40 tokens per `defn`):
that is the P10/MS row. I found no new code root cause. The design defects above are recorded here, the lane's
output. The str-built accept refusal at `source.clj:1010-1013` is already tracked as printer-audit A074.

My probe defined `rro.scratch/{g2,h2,pb}` on the `redo-review-opus` agent branch only.

## 5. Owner questions (one deduplicated list; recommendation first; ✱ = changes CLAUDE.md)

**Pass and REPL**
1. **Another writer changed a definition after the pass read it** (RP 1). *Keep the agent's final state with
   `[:overwrote …]`.* The REPL holds that state; merge checks digests.
2. ✱ **A removal whose referrer holds a required ref** (RP 2). *Sweep it with `[:swept …]` on agent branches;
   CLAUDE.md "Required refs refuse invalidating deletion" gains "at merge".* Branches never refuse.
3. **Same-pass reads and `my.test/check`** (RP 3, EN 3, L MS-11b). *Lookups (`doc`, `source`, `dir`, `my.program`)
   read the live world; `my.test/check` reads the committed program; same-pass tests use `clojure.test` in the live
   world; retire MS-11b.* The REPL law is about Clojure, and there is one world source for recorded proofs.
4. ✱ **Seon's own diagnostics stored as data** (RP 4). *Yes: fixed-shape type, operation, fixed message and
   references; copies of agent values stay shown text.* They are already data, and storing their text is a second
   printer.
5. **A model reply lost by a crash before RECORD** (RP 5). *Accept it.* A pre-eval blob commit is a second
   transaction for a rare path.
6. **DATA forms read at the cursor inside `:forms`** (RP 6). *Yes.* One reader, and `::alias` works.
7. **Nested closure `defn`** (RP 7). *Record it with its enclosing top-level form.*
8. **Delimiter repair in code mode** (AL open). *Yes, with the same repair notes.* It is the same scan, and MCP
   agents benefit too.

**Entrants and tests**

9. **Who evaluates the merge policy, and as what** (MA 2, origin of EN's review pass). *Through root's one path, as a
   schedule-handler entrant with its own row in EN's table, not labelled `:outside`.* CLAUDE.md lists schedule
   handlers as entrants, and a mislabelled origin corrupts history filtering.
10. **Member pass rows** (EN 1). *None; delete the `:test` origin; correct AL §3.2 and §3.4.* Nobody reads them.
11. **A pre-execution admission transaction per request** (EN 2). *No; use a process roster of live bodies.* One
    transaction per request.
12. **Effect attribute name** (EN 4). *`:seon.effect/pass` plus `:seon.effect/form-ordinal`.* A new name for new
    semantics.
13. **External effects in a test keep receipts only in the released overlay** (EN 5). *Accept it.* The body asserts
    its own evidence.

**Finishing**

14. **How proofs are scheduled** (new). *As a serial step in the agent's proc after each changing pass, not a
    concurrent proof proc.* It deletes the running/dirty state and `:running`, and the next block shows real status.
15. **Question vs code task** (TF 1). *Derive it: a task whose proofs call `my.task/answer` is a question task (not a
    new kind, not branch contents).* Kind is identity and subject; the call graph already knows.
16. **Steps** (TF 2). *Delete them, and strike steps from CS-01a, S1-06 and AL §3.4 before CS-01a lands.* Proofs
    define done.
17. **A newer green commit after proposing** (TF 3, and the accept race). *Same merge task with the row updated; a
    review in flight holds `#{:superseded}` if the commit moved; the agent goes to `:wait` at proposal; its branch
    retires after actual exit.* Root always reviews the latest, and nothing retires a working branch.
18. **The one-test budget interim** (TF 4). *400 ms after F1, 150 ms after EN-6/EN-7.* The floor of 60-100 ms is
    measured.
19. ✱ **CLAUDE.md "the Var render only reads their status"** (TF 5). *Rewrite it: "the task's proofs run after the
    pass; their status shows only in the current-state block".*

**Merge**

20. **Where a migration is named** (MA 1). *On the merge task.* Running data changes is a merge decision.
21. **Where the migration runs at accept** (new, replaces MA's writer stage). *Outside the writer, from the head read
    at accept, transacted under `head-guard-tx`; re-read once on a moved head, then hold.* No agent code on the writer
    thread.
22. **Migration size** (MA 3). *10,000 datoms per accept; larger migrations become their own root task.*
23. **The row S keeps for a conflicted identity** (MA 4). *The head's row, marked unresolved.*
24. **Delete the `uncovered` gate** (MA 5). *Delete it.* CLAUDE.md "shown, not gated".
25. **The head moved after prepare** (MA 6). *The policy re-prepares once, then holds `#{:head-moved}`; root's
    repaired S uses `compare-branch` with S's fork as base.* No new rebase mechanism.
26. **The auto-accept test criterion** (new, replaces `:no-new-red`). *`:all-green`: every member of the run on S is
    green; a red that already existed on the head holds for root.* Unknown evidence never accepts.
27. **The accept target under merge custody** (new). *`:seon.task/target`, fixed at proposal; never the custody
    branch.* Otherwise accept lands on S.

**Memory**

28. **The live-result retention bound** (L MEM-03). *The newest 64 handles; older ones answer gone-value with their
    shown text.* ≤ 20 MB per idle agent.
29. **The `defn`-pass budget** (RP §6 vs AL §2). *Keep AL's < 30 MB as the end state; RP's 60/80 MB is an interim
    row.* The ruling says 20-40 MB per pass.

## 6. Landed work since 2026-10-07 (owner widening: review the work, measure, stop the unreasonable)

Default runs `4b4caf142`, which holds every commit below (`bin/seon status` source root). All measurements were
single runs on default through `seon.pass-probe` and MCP. They used fresh probe agents (`s0-*`, `rro-*`) and the
fixture provider, so no paid call was made. Allocation counts every thread, so it is an upper bound on a shared JVM.
The owner's rule decides each verdict: a cost proportional to the work asked is reasonable; a cost proportional to
something else (the program, the store, a branch count) or GB-class is unreasonable.

### 6.1 Measurements

| Operation | Measured | Verdict | Likely producer (evidence) | Ledger row |
|---|---|---|---|---|
| MCP SCI ordinary pass (2 KB string) | 44-83 ms, **52-129 MB**, 8-11k armed calls, 1 commit | **unreasonable** (budget 20-40 MB for a form that allocates 2 KB) | `seon.turn/fault-window` and `seon.error/observation-selector` 22 ms; `seon.db/with-declarations` 123 calls, 43 ms; `seon.sci.eval/acquire!` 13-39 ms on a pass that changed nothing; storability refusals (below) | S5-05 (fault window), PB-02b (acquire), new task (storability) |
| MCP SCI `defn` pass | 150-171 ms, **213-236 MB**, 32k calls | **unreasonable** (< 30 MB) | O(program) projection acquisition and writer report validation (defn-cost3 notes; tasks `seon.schema/a-declaration-write-reloads-the-whole-projection`, `seon.db/every-commit-validates-its-write-report-three-ways`) | **none fixes it.** RP-0 only measures, and PB-02 covers acquisition. A row is needed. |
| the pass after a `defn`, `(pf 41)` | 87-112 ms, 117-119 MB, **127-129k armed calls** | **unreasonable** (one call to a one-line function) | `settle-batch!` 54-58 ms; `attribute-declaration` 8,149 calls and **451 `refusal/diagnostic`** per pass (`src/seon/schema/datahike.clj:155-180` builds a full error with a `str` message as control flow); `projection-cache-value` 438 calls, 37 ms | S5-02 (measurement), EN-8; **new task `seon.schema.datahike/an-ordinary-pass-builds-hundreds-of-storability-refusals`** |
| model pass, fixture provider (provider 0.2-0.5 ms) | **480-3,106 ms, 687-1,012 MB, 7 commits**, 106-272k calls | **unreasonable** (the owner: "no world") | `open-turn`/`system-turn` 1.2-1.5 s, `cluster.prompt/prompt` 943 ms, `db/transact!` 1,155 ms in the `defn` pass; slower than the profile's 507-886 ms | S4-02b..S4-07b3, RP-10, RP-14, PB-02; C0 is the gate |
| first one-test check on a new agent | **1,183 ms, 3,805 MB**, 313k calls, 8 attempts; `clean-request-handle` 561 ms | **unreasonable** (GB class) | `:seon.test.runner/reach-declarations` cache: **0 hits, 11 misses, 187 MB (37% of the cache budget)**; the key is the connection id `[store branch]` plus its generation (`runner.clj:791-801`, verified), so every branch re-reads the whole program | F1-F3 and EN-6/7 do not cover the key; **new task `seon.test.runner/reach-declarations-misses-on-every-branch`** |
| the same check, member reused | 89-94 ms, **119-153 MB**, 41-44k calls | **unreasonable** (budget < 20 MB) | the pass overhead above plus `select` | F1, EN-6 |
| reply forgiveness, 28.6 KB | 2.5-4.3 ms, 0.7-1.1 MB | reasonable | — | — |
| retained heap per idle agent (5 agents, 3 passes each, after full GC) | **4.2 MB** | reasonable (≤ 20 MB) | — | — |
| cluster base context | 38 hits / 2 misses, 23 MB | reasonable (PB-01 holds) | — | — |
| `:seon.sci.eval/loaded-digests` cache | 40 hits / 42 misses, 60 MB, 2.6 s of load | *uncertain*: a 50% hit rate for a per-commit derivation suggests a per-branch key like the one above | not diagnosed | none |
| process | live heap 745-944 MB; heap max 11 GB; cache 390-400 of 512 MB | heap max unreasonable on 16 GB | `MaxRAMPercentage` | MEM-01 |
| 3,000 `println` lines | about 530 lines shown (about 1,500 tokens); elision form `(subs (:seon.cluster.eval/output (seon.db/pull …)) 4660)` | **unreasonable** (R ≤ 260 tokens; ruling line 25: no DB re-pull while the live value exists) | P5's output budget | PR-07 (in flight, out-fix) |

### 6.2 Each landed item

| Item (commit) | Does what it claims? | Rulings | Fragility, second printer, clipping | Measured? |
|---|---|---|---|---|
| P0 differential table (`c816e3fde`) | yes | yes | — | n/a |
| P1 native veto deleted (`ded5e35de`) | **yes**: `(intern *ns* 'iv 5)` then `(alter-var-root #'iv inc)` → 5, 6 | the settlement-side unmap veto remains: reproduced, `(resolve 'pa)` comes back next pass with no diagnostic | — | RP-1 |
| P2 SCI reader policy (`e21fc4674`) | **yes**: `#?(:clj … :cljs …)` → `:clj-picked` | yes | — | — |
| P3 namespace kept after a throw (`f9dc29814`) | **yes**: `(in-ns 'rro.other) (throw …) *ns*` → `rro.other` | yes | — | — |
| P4 malformed contract stays callable (`0667ee448`) | **partly**: `(bad-contract 7)` → 7, still callable next pass. But the diagnostic says "Not admitted to the program … exists in your SCI context only" and carries about 30 JVM frames (about 1,000 tokens of Seon's own bulk) | violates "preserve malformed contracts as data" (no program row) and "Seon never generates bulk" | Seon-generated stack bulk | RP-7 (admission deleted), PR-04 (compact chain) |
| P5 output through the printer budget (`d78473124`) | the budget applies, but 3,000 lines still show about 1,500 tokens | elision form is not clojure.core on the live value | — | PR-07 in flight |
| P6 one linear scan (`c98ade404`) | **yes**: 2.5-4.3 ms / ≤ 1.1 MB per 28.6 KB | yes | `subs` only for source spans (legitimate) | measured above |
| P7a repair notes (`7569761aa`) | yes | stores repairs as a printed `:string` (`seon.cluster.eval.edn:48`), against "store data, not printed text" | printed copy stored | RP-12 |
| P11 print options leave the base (`666c767f7`) | consistent: base 38 hits / 2 misses | yes | — | — |
| PB-01 shared base (`6191eace4`, `48191071f`) | **yes** for the base. But the overlay keeps an earlier pass's callee (F0, reproduced `[2 3]`), and `acquire!` still costs 13-39 ms on an unchanged pass | REPL law broken (F0) | overlay advance is fragile until PB-02/F0 | F0, PB-02, PB-02b |
| M8 namespace row kept (`76daa8fd5`) | not re-probed; its regression is named | yes | — | — |
| Printer fixes (`131082e0d`, `a423b8b29`, `13b850d30`, out-fix `9c88ae5b8`) | `pr-str` added only inside `seon.print`'s own `literal`; `subs` cuts inside the printer | yes | `a423b8b29` puts `(pr-str path)` into a page URL: transport, not identity, so acceptable but worth one look under PR-24 | PR rows |
| S1-01..S1-05 lookups (`f50ef6059` … `28c141d56`) | **yes**: `(doc map)` answers data `{:summary :body :arglists}` | `:example ""` is an empty value where absence should be no key | — | L rows |
| S3-01 origin (`2bf3e10db`) | not probed | yes | — | — |
| S2b-1 reader rules (`84a3c5e4d`) | Parinfer deleted; one stepper | yes | — | via P6 |
| redef-arm (`b391c0ef9`, `ca00cfd7e`) | not re-probed | contract still crosses `pr-str` → `edn/read-string` on the arming path (task in `seon.sci.arm.edn`) | a second print/read round trip | existing task, no ledger row |
| lazy-observe (`b5ff51e4c`) | **contradicted for a definition without source position.** `(def lz (map (fn [i] (eval (list 'def (symbol (str "lzv" i)) i))) (range 2)))` then `lz` shows **`()`** with a `seon.sci.arm/write-event` contract error; only `lzv0` exists. `(eval (list 'def 'ev1 1))` answers the error instead of `#'ev1`, although the def happened. Clojure shows `(#'…/lzv0 #'…/lzv1)`. | **REPL law and "the path is total" both broken**: the value is replaced | `write-event`'s output contract requires a positive line (`arm.clj:62`); `eval`'d forms have none | existing task `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin`, **no ledger row**: add one in §3.2, group C, ready |
| reader-pos (`e24fda839`) | yes (its regression) | yes | — | — |
| cost lanes (`e9002e761`, `94affe518`, defn-cost2/3 `97faccb88` `4455e4917` `b20762d6b` `bd63ce073` `4c28e748f`, publish-cost) | defn-cost3 recorded `defn` 147 MB / 157 ms, ordinary 39 MB / 47 ms, after-defn 74 MB / 92 ms on a throwaway root; **not reproduced at that size on default** (213-236, 52-129, 117-119 MB); net −71 lines | yes | five cost commits have no number in their messages (the numbers live in `2026-10-07/defn-cost3/`) | measured; the gap is the shared JVM or the larger store, *uncertain* |
| 37d11f6a0 acquire before fork | yes, but it adds `acquire!` on every pass (claimed ~109 µs unchanged; measured 13-39 ms in the after-defn and ordinary passes) | — | per-pass cost | PB-02b |
| doc-fix | no commit by that name in the last 150; the out-fix lane holds uncommitted `render/value.clj` work | — | — | — |

### 6.3 What to stop now (unreasonable, not covered by a ready row)

1. **The reach-declarations cache keyed per branch.** It holds 187 MB at 0% hits, and a first check costs 3.8 GB.
   New task `seon.test.runner/reach-declarations-misses-on-every-branch`. Recommend a ready ledger row in group E
   ahead of F1, because F1-F3 cannot reach the budget while every branch re-reads the program.
2. **Storability answered by building error maps.** That is 451 diagnostics and 8k declarations per ordinary pass.
   New task `seon.schema.datahike/an-ordinary-pass-builds-hundreds-of-storability-refusals`. Recommend a ready row
   (group E/F).
3. **The `defn` pass at 7× its budget.** The projection reload has tasks but no row. RP-0 should become
   "measure, then fix the top owner", or a PB-row should name `a-declaration-write-reloads-the-whole-projection`.
4. **`eval`'d definitions replace the value** (the REPL law). An existing task exists with no row. Add it to §3.2
   (group C, alongside F0).
5. The model pass, the 3,000-line output and the stack bulk are unreasonable but covered (S4/RP/PB rows, PR-07,
   PR-04). Keep C0 as the gate.
