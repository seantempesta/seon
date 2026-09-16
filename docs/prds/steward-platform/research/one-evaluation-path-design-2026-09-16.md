---
type: design
status: proposed (orchestrator, 2026-09-16 22:45Z) — owner ruled "one path for all parsing and eval code"; awaiting review before a lane
tags: [design, turn, evaluation, seon.turn, seon.sci.eval, seon.sci.reader, datahike]
---

# One evaluation path: pure values in the middle, one writer at the edge

Grounding read in full: `evaluation-write-path-and-retired-identities-2026-09-16.md`
(§1: six minting writer calls, four terminal writers, two families;
§2: the vanished turn is a fixture artefact), `src/seon/turn.clj` at the
functions it names, `src/seon/sci/eval.clj:2206` (`evaluate`),
`src/seon/sci/reader.clj` through its callers at `src/seon/turn.clj:1987`,
`:2431`, `:3097`. Owner (2026-09-16): "I want one path for all parsing and
eval code and if our code isn't configurable enough then we should refactor
it so that the tests can all just use it. If that means making it so there
are more pure functions and we only write to the db on the edges do it."

## 1. What is one today and what is two

| Stage | Today | One? |
|---|---|---|
| **Read** (text → forms) | `seon.sci.reader/read` is the one reader; the turn calls it at three sites with different options (`:2431` passes `:defer-auto-resolve? true`, the others do not) | one function, three option sets |
| **Evaluate** (form → result, shown text, out, error, read evidence) | `seon.sci.eval/evaluate` (`:2206`) for agent forms; the system turn's generated reads go through the same evaluator (turn PRD §15) | one |
| **Row** (result → evaluation entity map) | `receipt-row` (`turn.clj:891`) for the fenced family; `refresh-call` builds its row inline (`:795`); `record-evaluated-call` builds rows from a prepared request (`:1570`) | **three constructors** |
| **Write** (rows → database) | fenced family: `open-call` → `plan-call`/`receipt-start-call` (mint, no terminal fact) → `receipt-settle-call` (terminal facts, settle-once fence) → `close-call`; one-commit family: `record-evaluated-call` writes turn + rows + terminal facts with none of those fences and a content-comparison idempotence (`stored-record-content`, `:1544`); side writers `recover-call` (`:1803`) and a bare `[:db/add … read-basis-transaction]` (`:2280`) | **two families + two side writers** |

The system turn (`system-turn`, `:2189`) is the one caller of the one-commit
family (`:2331`). It exists for cost: a system turn is synchronous and short,
and writing open + N evaluations + close as one commit was cheaper than four
commits (turn-bookkeeping-cost note). That optimisation is why there are two
families.

## 2. The design

**Principle.** Reading and evaluating are pure with respect to the database:
they take a database VALUE and the agent's SCI context and return evaluation
VALUES. Exactly one function turns evaluation values into transaction data,
and exactly one transaction function per transition admits it at the writer.
"System" versus "agent" is provenance on the value, never a different path.

### 2.1 The evaluation value

One map, produced by `evaluate` and consumed by the row constructor, already
the shape the evaluation entity stores: source text, namespace, shown text,
out, error, read evidence, duration, fn-entries, allocated bytes, outcome. The
system turn produces the same value for a generated read; the only extra key
is the origin ref (`:seon.eval/origin`) that says which entity's render
generated it. No key is added for "system": the turn PRD derives system from
"has a reply and no provider attempt", and that stays.

### 2.2 One row constructor

`receipt-row` becomes THE constructor: `(evaluation-row turn-eid ordinal value)`
returning the entity map for one evaluation, terminal facts included when the
value carries them. `refresh-call`'s inline map and `record-evaluated-call`'s
prepared rows are deleted; both callers call this. Pure; unit-testable on
values with no database.

### 2.3 One writer family, composed differently by the two callers

Datahike applies `[:db.fn/call f …]` operations in order against the
mid-transaction database (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`),
so the fenced calls COMPOSE inside one transaction: a later call sees the rows
an earlier call in the same transaction minted. That is the whole trick.

- **Agent turn** (long-running, may be interrupted): commit 1 `open-call` +
  `plan-call` (mint running rows); one commit per settled evaluation
  `receipt-settle-call`; last commit `close-call`. Unchanged.
- **System turn** (synchronous): ONE commit whose tx-data is
  `[open-call, plan-call rows, receipt-settle-call for each ordinal, close-call]`
  — the same four transaction functions, the same fences, one commit. The
  content-comparison idempotence is deleted: the `(turn, ordinal)` identity
  fence already refuses a second mint of an ordinal, which is the idempotence
  the system turn needs.

`record-evaluated-call` / `record-evaluated-tx` are deleted. `recover-call`
stays (it is boot's one transition: mark non-terminal evaluations of open
turns interrupted — a settlement with a typed terminal fact, so it can be
expressed as `receipt-settle-call` with `interrupted-at`, which makes it the
same function; verify at implementation). The bare `[:db/add …
read-basis-transaction]` at `:2280` becomes a declared transition of the same
writer (an unchanged re-read advances the evaluation's read basis) or is
folded into the settle call; never a bare datom next to a fenced writer.

### 2.4 One reader call

The three `reader/read` call sites pass the same option map derived from the
namespace context; `:defer-auto-resolve?` is either always right or never
right for a turn — the lane finds which and there is one call site.

### 2.5 Tests use the same path

Because read and evaluate are pure over a database value, a test of the
system turn's opening, of the since-diff re-read, or of an agent evaluation
calls `evaluate` on the canonical fixture's database value and asserts on the
returned VALUE; only tests of the writer transact, and they transact the same
composed tx-data the system turn commits. No test needs `record-evaluated-tx`
or a hand-built row.

## 3. What we delete

`record-evaluated-call`, `record-evaluated-tx`, `stored-record-content`, the
inline row in `refresh-call`, the bare `:db/add` at `:2280`, two of three
`reader/read` option sets, and every test that reached those directly.

## 4. Guarantee, cost, loss

*Guarantee:* every evaluation entity in the system is minted and settled by
the same two transaction functions under the same fences; a settled
evaluation can never be settled twice by any caller; the system turn keeps
its one-commit cost. *Cost:* one astra slice on `seon.turn` (the writer
composition and deletions), with the S7 and S11 tests as the regression set
plus one new test asserting the system turn's single commit contains exactly
the four call shapes. *Loss:* nothing durable; strictly less mechanism.

## 5. Open question for the owner

Boot recovery's `recover-call` writes `interrupted-at` on evaluations that
were never settled. Under the single family that is a settlement with the
typed terminal fact `interrupted-at`. Confirm that reading: "interrupted" is
an outcome, recorded by the settle call, not a third kind of write.
