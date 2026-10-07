---
type: notes
status: review (gate 1, Opus half of the dual review)
created: 2026-10-07
lane: review-s5a-opus
reviews: docs/research/agent-platform/2026-10-07/s5a-reply/design-notes.md at 1517e2f35
---

# Review of s5a-reply (Opus)

Verdict: **revise, then go.** Both deletions are the right cuts and serve the one path (FINISH and RECORD). Path (b)
is accurate and ready. Path (a) does not yet hold its central claim, that "the stored receipt is the held receipt":
the held settle request is missing a field the reply reads. It also misses one terminal arm and mis-names the JVM
input. Each fix is small, and none adds machinery.

## Findings, ranked

### 1. HIGH: the held SCI receipt has no source, so the reply loses `:note` on every `def` (the equality claim is false)

- `seon.turn/evaluation-facts` (src/seon/turn.clj:98-195) builds the `:seon.cluster.eval/settle-request`. It never
  sets `:seon.cluster.eval/source` or `:seon.cluster.eval/comment`. The stored receipt gets them from the run's open
  rows (`receipt-row` / `system-run-tx`), and `receipt-settle-call` (turn.clj:1424) only adds terminal facts to that
  row.
- `seon.repl/response-entries` reads the source for `:seon.repl/note` (`def-note`, src/seon/repl.clj:147-168, used
  at :238).
- Live today (SCI, agent `review-s5a-opus`): `(def x 1)` answers with `:note "x lives only in your SCI context…"`.
  The held `held-responses` as designed (receipt + id + `{:seon.ns/name}`) would drop it. The planned regression
  ("ordinary, erroring, faulted") has no `def` and would not catch this.
- Fix: carry the source from the admitted form, as `candidate-history`'s in-memory arm already does
  (src/seon/render/transcript.clj:566-574). `resume-turn`'s `gated` items hold `:seon.turn.loop/admitted-form`.

### 2. HIGH: it adds a third receipt-to-entry assembler; reuse the one that exists

`candidate-history` already turns in-memory evaluations into `receipt-entry` values (source, id via
`turn/receipt-identity`, ordinal, `{:seon.ns/name}`, comment, print length/level). The proposed `held-responses` "beside
it" would be a second copy of that assembly over a different input. Make one function, (admitted form, receipt or
evaluation, run id, ordinal) → entity, and use it in both arms. `held-responses` then becomes
`(repl/response (repl/entity-emission entity))` without `:seon.db/db`. That keeps one converter, which is the
property `history-entries`' own comment ("ONE GENERATOR") guards.

### 3. MEDIUM: the refused-settlement arm attaches no basis through `settled`/`applied`

Design step 1 says the receipts are "set where `applied`/`settled` attach the basis". The refused-but-recorded arm,
src/seon/turn.clj:5205-5207 (`(cond-> (report :error (count gated) refusal) (:db-after outcome) (assoc
:seon.turn.loop/basis …))`), uses neither helper. Without its own assoc, that arm answers no receipts. Its reply
would then be the refusal alone, where today it is every form's shown text plus the refusal. Name that arm, and add
it to the regression table, with a refusal forced by a refused program fact (`not-admitted` / revalidate path, turn.clj:3580-3593).

### 4. MEDIUM: the refused-refusal case is stated wrongly

Risk 1 says a refused refusal answers "the refusal arm's `receipts`". In that case nothing committed, so no receipts
were written (`settle-batch-refusal!`, turn.clj:3853 onward). Also, `evaluate-source!`'s first `cond` branch
(src/seon/cluster/agent.clj:826-831) answers the refusal alone whenever the basis has no run, which is always true for
a submission whose open rode the refused commit. Either:
- keep today's behaviour (refusal only) and say so; or
- deliberately return the evaluated values beside the refusal (the path is total). That is a BEHAVIOR CHANGE, and the
  reply then shows texts that were never stored, which breaks "held = stored". Pick one and write it down.

### 5. MEDIUM: JVM input is named wrongly; `evaluation-record` re-derives rather than holds

- `seon.batch/evaluate-batch` merges `(:seon.batch/settled commit)` into the batch's entries and then removes it from
  the commit (src/seon/batch.clj:747-748: `(settled-into …)`, `(assoc :seon.batch/commit (dissoc commit
  :seon.batch/settled))`). `jvm-run` therefore has no `(:seon.batch/settled commit)`. The input is `(:seon.batch/entries batch)`,
  whose facts errors are already merged.
- `turn.batch/evaluation-record` (src/seon/turn/batch.clj:611-629) stamps a fresh `(java.util.Date.)` on each call.
  Calling it again is a second derivation, not "the receipt `record-evaluated-call` wrote". This is harmless for the
  response, because `:at` is not printed. Either word it as a re-derivation or have `record-facts` hand back the
  records it built.

### 6. MEDIUM: references the deletion misses (each would dangle or turn red)

- `resources/seon/tasks/seon.turn.edn:99`: task `the-unbound-check-walks-the-live-value-without-a-bound` has
  `:must-read #{… seon.problems/form-problem …}`. It becomes an unresolved reference, so convert it in the same
  commit. (Its intent text, "runs twice per form (problems/form-problem and evaluation-facts)", also goes stale.)
- `test/seon/schema/datahike_parity.edn` contains the `:seon.problems/form-problem` schema form in its baseline.
  Retiring the schema changes that baseline; name its test.
- `src/seon/sci/eval.clj:3722`: the `unrun-evaluation` docstring names `seon.problems/form-problem`.
- `resources/seon/tasks/seon.db.edn:152`: a task's evidence lists `form-problem` among its query sites. Correct it.
- `src/seon/mcp.clj:9`: the namespace docstring names `seon.cluster.agent/run-observation`.

### 7. LOW: fault errors must be pulled on the settlement's `db-after`, with the same root

The settlement commit includes `seon.fault/folded-tx`, which changes occurrence counts in the same transaction
(turn.clj:3557-3561). `branch-errors` today pulls `latest-fact` and `resolution` on the basis. The extracted function
must use the basis too, not the pre-commit value the window was read on. For the `:seon.repl/errors` text to be
byte-equal, `floor-text` also needs the same `:seon.render.value/root`. Today that is `entry-root`, the receipt's
`[:seon.cluster.eval/id …]` identity.

### 8. LOW: the new schema members need publication proof, not only the retirements

`:seon.turn.loop/receipts` on `:seon.turn.loop/turn-report` and the new member on
`:seon.agent/source-submission-result` are schema-resource changes. Under the publication rule they need adoption on
a branch of the live store, just like the retirements risk 5 names.

### 9. LOW (cross-design): `src/seon/turn.clj` is contended

- The design edits `turn.clj` heavily (`settle-batch!`, `evaluation-terminal-data`, `resume-turn`,
  `evaluation-facts`, the delays at :67-70).
- `stopped-wip/loop-pass.patch` edits the same delay block (hunk `@@ -60,10 +60,8 @@`) and `resume-turn`'s
  neighbourhood (`@@ -4489,92`).
- walk-delete slice B2 also waits on `turn.clj` (`evaluate-sources`, `receipt-read-evidence-tx`).
- Root should order these: s5a first is fine, since it deletes the `problems` delays that loop-pass's hunk merely
  keeps. The loop-pass patch then needs a rebase.
- s0-probe also holds `src/seon/eval/drive.clj`. The design keeps `run-receipts`, so there is no conflict there.

## Claims checked

| Claim | Status | Evidence |
|---|---|---|
| JVM replies after the agent's first run drop `:ms` | SUPPORTED | Live: the first JVM `(+ 1 1)` answered `:ms 1`; the second run answered without `:ms`. My agent's turns: SCI submissions have `:seon.turn.work/situation :call` (excluded by `entity-emission`'s `changed?` query, repl.clj:527-541); JVM `record-evaluated-call` runs have no situation (turn.clj:1296-1360), so they match. |
| `form-problem`'s error fallback in `evaluation-facts` is EQUIVALENT to remove | SUPPORTED | `resume-artifact?` (turn.clj:2403-2422) is true when interrupted, so `form-problem` (problems.clj:216-258) returns nil, and otherwise its error is the evaluation's own. |
| `planner-scoped-attempt?` and `problem-id` have no other production callers | SUPPORTED | Only `problems.clj:236,250-251`; tests in `problem_routing_test`, `resume_artifact_routing_test`, `cluster/turn_test`, `gen/loop_test`. |
| Nothing else consumes `run-observation` | SUPPORTED | Callers: `seon.mcp/observed` plus the two named test files. `virtual-turn!` (turn.clj:2284) uses `evaluate-source!` but not the reread. |
| "response bytes are identical for SCI" | CONTRADICTED for `def` forms (finding 1); otherwise supported: the renderer symbol, measures (`cost-attributes`: duration, allocated bytes, tokens, replaced, call count, value class), ns, ending-ns, print length/level and diagnostics are all on the settle request | evaluation-facts :98-195; repl.clj:316-330 |
| Every terminal arm answers its receipts | INCOMPLETE | Finding 3 (turn.clj:5205) and finding 4 (refused refusal). |
| JVM holds `(:seon.batch/settled commit)` | CONTRADICTED | Finding 5. |
| No faults ride JVM records | SUPPORTED | `record-evaluated-call` writes no `:seon.cluster.eval/faults`. |

## Simplifications

1. One receipt-to-entity function shared by the in-memory arm and the held reply (finding 2), instead of a new
   `held-responses` beside `candidate-history`.
2. JVM: print from the `batch` entries `jvm-run` already holds. There is no need to make `evaluation-record` public
   unless the regression compares against it.
3. The regression table should be one table over the SCI pass with these rows: ordinary, `def` (note), renderer
   value, `in-ns` (`:ns`), `(set! *print-length* …)`, error, not-admitted (refused facts), completed disposition,
   faulted, refused settlement. Plus the JVM `:ms` row. Each row asserts the held response equals
   `history-entries`' `:seon.render.history/printed-value` on the basis (except the deliberate JVM `:ms` row).

## Question for the owner

After this slice, the MCP reply for a JVM run shows `:ms`, while the agent's own next context, reread through
`history-entries`, still marks that same run ";; changed since your last turn" without `:ms`. CLAUDE.md says the
MCP reply and the next context read "the same saved observation". Two options:
- The producer fix is one fact: `record-evaluated-call` writes `:seon.turn.work/situation :call` for MCP JVM runs, as
  SCI submissions do. The divergence then disappears for both readers.
- If page "Add to context" saves through the same writer and should stay marked, the situation has to come from the
  JVM MCP request instead.

Which runs should count as "changed since your last turn"?
