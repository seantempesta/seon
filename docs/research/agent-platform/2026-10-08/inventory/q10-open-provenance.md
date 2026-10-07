# I-Q10: the open / recover / effect-provenance caller closure

Inventory for Q10 (S6-01, S6-02b, S6-04). Notes only, no code. Line numbers are `git show HEAD` (cd8a481c2). The
working tree's `src/seon/turn.clj` and `src/seon/db.clj` carry other lanes' uncommitted edits (s3-01, defn-cost2) that
shift lines by about 3; the plan's `:697`/`:1473` are working-tree numbers for HEAD `:694`/`:1505`.

## 0. What the "run open" is, in one paragraph

Today a model pass writes a run row (`:seon.turn/id`) first, then rows that ref it (evaluations via
`:seon.cluster.eval/run`, effects via `:seon.effect/run`). Four mechanisms exist so a ref never dangles:
`open-tx`/`open-call` (turn.clj:694, :385) as its own transaction; `system-run-tx` (:755) for a submitted run;
`db/*open*` (db.clj:184) prepending the open to the first durable write a form makes; `settle-batch!` carrying it
(`with-submission`, turn.clj:3509). `recover-tx` (:1505) closes runs after a crash. All of these exist only because the
run is a ref target.

## 1. `db/*open*` closure (the carrier)

| Site | Role |
|---|---|
| `db.clj:184` | Definition. Private dynamic Var, doc: transaction data opening the evaluation's run |
| `db.clj:458` | `call-with-custody` rebinds it, kept only when the connection is the bound `*conn*` |
| `db.clj:3620` | The one reader: `(when *receipt* *open*)` is `into`-ed first in the stamped tx-data. Applies to every `transact!` the form makes |
| `sci/eval.clj:4027` | The one binder: `#'db/*open* (when receipt (:seon.db/pending-open request))`. `receipt` is `[:seon.cluster.eval/id (id/evaluation run-id ordinal)]` (:3985) |
| `turn.clj:4968` | The one producer of the request key: `:seon.db/pending-open` = `(:seon.db/tx-data (:seon.turn.loop/submission cluster))` |
| `resources/seon/schemas/seon.db.edn:259` | `:seon.db/pending-open` schema (named `submission-open-tx`) |
| `turn.clj:5984` | Builds `:seon.turn.loop/submission {:seon.db/tx-data (submission-open-tx run-request) ...}` for a submission whose staged-blob path did not already open it |
| `turn.clj:5953` | Alternative: opens the run in its own commit with the blob publication (`opened`) |
| `turn.clj:3509-3530` | `with-submission` prefixes the same open to the settlement, or `system-run-if-admitted-call` on the refusal arm |
| `turn.clj:767-776` | `submission-open-tx` = `absent-call [::id id] [[system-run-call request]]` |

Consequence of the design: the open rides ONLY submitted runs (an MCP-submitted source pass). A model-opened run
(`open-turn`, :4313) is a separate durable write before the paid call, so `*open*` is nil there.

The sibling `db/*receipt*` (db.clj:180) is NOT in the plan's list but travels with `*open*`: every transaction during an
evaluation is stamped `:seon.db/receipt` = lookup ref to the not-yet-recorded evaluation (db.clj:3619, schema
`seon.db.edn:258`, a `:seon.db/ref`, indexed). It dangles exactly like the effect's eval ref. See finding F1.

## 2. `open-tx` / `open-call` closure (production)

`open-tx` (turn.clj:694) = `[[:db.fn/call #'open-call request]]`. `open-call` (:385) is the writer.

| Caller (HEAD) | Via |
|---|---|
| `turn.clj:850` `generated-run-tx` | `open-tx` (situation `:generate`, zero forms) |
| `turn.clj:725` `system-run-plan` | calls `open-call` directly (feeds `system-run-call` :750, `system-run-tx` :755, `submission-open-tx` :767, `system-run-if-admitted-call` :775) |
| `turn.clj:4313` `open-turn` (private, `:open` situation) | `[[:db.fn/call #'open-call open-request]]`, one commit before the provider call; answered by `next-id` |
| `bootstrap.clj:308` | `turn/system-run-tx` for the root agent's wake sources (reads `already-open?`) |
| `bootstrap.clj:335` `seed-tx` -> `turn/generated-run-tx` | seeds the bootstrap run of a new agent |
| `cluster.clj:3112` | `bootstrap/seed-tx` in agent ensure (`ensure-entity!` path) |
| `cluster/agent.clj:764` | `submit-source!` builds `:seon.agent/run-request` (system-run request), offered to the armed graph |
| `turn.clj:5953/5984` | the pass loop's use of `submission-open-tx` (section 1) |
| `test.clj:181` | `attempt-writers :turn` = `seon.turn/open-tx` (section 5) |

Readers of "is a run open" (they die with the run entity, or are rewritten onto the agent): `open-for-agent`
(turn.clj:369) at `turn.clj:407, 2253, 2333, 5509, 5679`, `bootstrap.clj:119`, `cluster/agent.clj:1396`
(`open-turn!`, 1534, 1446). `open?` (turn.clj:199, agent.clj:50) and `current-run` (:309).

Tests (`turn/open-tx`): 24 files, 47 lines. Production-shaped users: `test/seon/turn_test.clj` (11 sites),
`data_shapes_test.clj` (7), `cluster/agent_test.clj` (4), `turn_loop_test.clj` (3), `db_test.clj` (3),
`settlement_receipt_provenance_test.clj` (also uses `#'turn/submission-open-tx` and `seon.db/*open*` at :58-64: the
only test of the `*open*` carrier), `effect_test.clj:859`, and one-site files: `program_test.clj:1095`,
`receipt_set_attributes_test.clj:27`, `context_capture_history_test.clj:20`, `schema_usage_guard_test.clj:372`,
`turn_disposition_test.clj:28`, `receipt_write_carrier_test.clj:32`, `loop_proof_test.clj:212`, `ai_test.clj:481`,
`transact_feedback_test.clj:21`, `concurrency_streams_test.clj:108`, `render_source_test.clj:74`, `no_provider_test.clj:91`,
`run6_stall_test.clj:43`, `cluster/evaluate_sources_test.clj:142`, `cluster/turn_test.clj:1664`, `turn_work_test.clj:75,150`.
Each is a fixture that opens a run so later rows can ref it: they convert to the pass entrance
(`seon.turn/system-run-tx` replacement) or to pass-id values, in the same commit as S6-04.

## 3. `recover-tx` / `recover-call` closure

`recover-tx` (turn.clj:1505) -> `recover-call` (:1514): finds the run, `interrupt-stamps` (:353, evaluations of the run
that have no terminal fact) plus `effect/interruption-stamps` (effect.clj:385, effects of the run with no result), and
asserts `::closed-tx`.

| Caller | Purpose |
|---|---|
| `cluster.clj:2938` `recover-runs!` (calls at :2967-2972) | THE BOOT RECOVERY CALLER. Queries every `:seon.turn/id` with no `closed-tx` (:2951), `mapcat`s `recover-tx`, one `db/transact!`. Also `schedule/recover-tx` (schedule.clj:472, a different family: maintenance receipts, keeps) |
| `cluster/agent.clj:1453` `record-interruption!` | `disarm!` closes the open turn of a stopped graph (called at :1535 after `cancel-turn-backstop!`) |
| `turn.clj:5479` `close-escaped-run!` | closes the run when a Throwable escapes `turn` (called at :3897 and :5559) |
| `turn.clj:2349` (docstring) | names `recover-tx` |
| `sci/eval.clj:94` (docstring) | names `recover-tx` |
| `boot.clj:553` | surfaces `:seon.boot/recovered-runs` in the boot report |

Tests: `turn_test.clj:1322, 1546, 1719, 1750` (with `recover-call` at :1735; `turn_test.clj:590` and
`loop_proof_test.clj:764,774` call `#'cluster/recover-runs!` and assert `:seon.boot/recovered-runs`), `cluster/turn_test.clj:2144`
(`recover-cut-run!`), `cluster/agent_test.clj:1716`, `effect_test.clj:875`
(`recovery-marks-open-receipts-interrupted-without-refiring`), `cluster_test.clj:30` (asserts the zero-report shape).
Total: 4 files call `turn/recover-tx`, 7 lines.

What stays when the run entity goes: a crash before settlement loses only that pass's bookkeeping (CLAUDE.md
"Recover facts"); an open effect receipt with no result still needs its interrupted stamp. So recovery shrinks to
`effect/interruption-stamps`, keyed on the pass id value instead of the run eid, and `recover-runs!` loses its open-run
query. `:seon.boot/recovered-runs` / `recovery-operations` become effect and agent counts (the boot report schema
changes).

## 4. Effect provenance producers and readers

Writers of `:seon.effect/run` and `:seon.effect/eval`:

| Site | Detail |
|---|---|
| `effect.clj:849-866` `request*` | builds `open-request`: `:seon.effect/id` = `(id/digest 12 [::id turn-id ordinal effect-ordinal])` (already a function of pass id + positions), `:seon.effect/run` = `[:seon.turn/id id]` lookup ref (:857), `:seon.effect/form-ordinal` (:862). The `id` input is `*request-context*` `:seon.turn/id` / `:seon.cluster.eval/ordinal` |
| `effect.clj:227-248` `open-call` | the writer; calls `evaluation-eid` (:210), adds `:seon.effect/eval` |
| `effect.clj:210-225` `evaluation-eid` | pulls the run by `:seon.effect/run`, then `[:seon.cluster.eval/id (id/evaluation turn-id ordinal)]`; returns nil when the evaluation is not recorded |
| `sci/eval.clj:4029-4042` | binds `effect/*request-context*` from the evaluation request (`run-id`, `form-ordinal`, `agent-id`) |
| `test.clj:184-216` | builds a second `*request-context*` for test attempts (section 5) |
| `schemas/seon.effect.edn:102,212-213,223` | `:seon.effect/eval` (optional ref, indexed), `:seon.effect/run` (required, ref), `:seon.effect/form-ordinal` (required). The `:eval` description already says run + form-ordinal "retire with that reader (issue effect-run-and-form-ordinal-duplicate-the-evaluation-ref)" |

Readers of the run ref:

| Site | Use |
|---|---|
| `effect.clj:60-66,79,115` (`receipt-identities`, `render-ai`, `render-html`) | resolves the run id through `ref-attribute` for the "run X, form N" line. Becomes the string value |
| `effect.clj:405` `interruption-stamps` | `[?receipt :seon.effect/run ?run]` joined on the run eid |
| `turn.clj:1905-1906` | `[?effect :seon.effect/run ?turn] [?effect :seon.effect/form-ordinal ?ordinal]` inside the "may a kept evaluation be reused" predicate (nil check) |
| `test/seon/effect_test.clj:1029-1039` | pulls `:seon.effect/eval` -> evaluation -> run -> agent: "agent, turn, evaluation and effect are one walk". The only reader of `:seon.effect/eval` besides the writer |
| `test/seon/web/jvm_test.clj:262`, `test/seon/shell/jvm_test.clj:422` | joins `?receipt :seon.effect/run ?run` on `:seon.turn/id` |
| `test/seon/background_blob_test.clj:153-165`, `render_coverage_test.clj:100-124,306`, `background_test.clj:20,23`, `my/background_test.clj:71,74`, `render/value_test.clj:100,103,227,230`, `effect_test.clj:866,869` | fixtures writing receipts with `[:seon.turn/id id]` |
| `effect_test.clj:662` | asserts `form-ordinal` 3 |

Tests: `:seon.effect/run` 9 files / 15 lines; `:seon.effect/eval` 2 files / 4 lines.

Observation that decides the key choice: the effect id is already `digest(turn-id, form-ordinal, effect-ordinal)`, so
the receipt already carries pass id and position as VALUES inside its identity. The ref adds only (a) a join target for
recovery and renders and (b) a `:seon.turn/id` lookup the writer resolves. Nothing needs the ref once the value is a
stored attribute: `:seon.effect/run` (the pass id string, same name, new schema type `:seon.turn/id` instead of
`:seon.db/ref`) could stay as the attribute name. Q10's ROOT recommendation says "stores the pass id as a value (today's
`:seon.turn/id` string)"; the new-name-for-changed-semantics law ("Grow by accretion: new names for changed semantics")
means changing a ref to a string under the same name needs a decision for S6-01. See open item O1.

`:seon.effect/eval`: for a model pass the evaluation rows are frozen before evaluation (`plan-call`, turn.clj:635), so
the ref resolves today. Under the one-RECORD design the evaluation does not exist at effect time, so `evaluation-eid`
would return nil for every in-pass effect: the ref becomes dead on arrival, which confirms deleting it (Q10).
Readers that want the evaluation query it by `id/evaluation(pass-id, ordinal)` after RECORD.

## 5. Test attempt writers (`test.clj:179-216`)

- `attempt-writers` (:179): `seon.cluster.agent/creation-tx`, `seon.turn/open-tx`, and the Var `#'seon.effect/*request-context*`.
- `attempt-request-context` (:184) writes, in ONE `db/transact!` on the member branch: a throwaway agent
  `attempt-<id>` plus an open turn `attempt-<id>-turn`, then builds the request context `{:seon.agent/id,
  :seon.turn/id, :seon.cluster.eval/ordinal 0, :seon.env/environment (scoped), :seon.effect/counter (atom -1) ...}`.
  The comment says each of these writes costs 350-440 ms on a member branch.
- Its one caller is `bounded-result` (:257-265), only when `(:seon.test/effect-attempt? custody)`; the flag is set from
  `effect-reach` (`runner/tests-reaching ... 'seon.effect/request*`, :2420-2425) and passed through `:2006` and `:2076`.
  `with-bindings {(:context @attempt-writers) context}` binds `*request-context*` for the body.
- Only `test/seon/test/effect_attempt_test.clj` exercises this path
  (`an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt`, bound 30000 ms); its second test
  expects `:seon.effect/missing-context-member = :seon.turn/id` (effect.clj:742) when no attempt context exists.

What deletion needs (S6-02b): `*request-context*` needs only a pass id value, the ordinal, an environment, a counter and
a cluster name. The agent and turn rows exist only to give the lookup ref `[:seon.turn/id id]` a target. Dropping them
removes `attempt-writers :agent/:turn`, one transaction per attempt, and the agent entity creation. Two reads of the
attempt agent remain: `effect.clj:872` sets `:seon.effect/notify [:seon.agent/id ...]` when `background?`, a real ref to
a living agent; and `env/scope` takes `:seon.agent/id`. The test pins neither: see open item O2.

## 6. Other holders of "the run" the closure touches (named so S6-04 sizes them; not Q10's decision)

- `:seon.cluster.eval/run` (evaluation -> run ref, required): 33 src sites (`turn.clj` 22, `bootstrap.clj` 5,
  `problems.clj` 2, `sci/eval.clj` 2, `eval.clj` 2, `repl.clj`, `status.clj`, `drive.clj`, `render/walk.clj`) and 29 test
  files. This is the larger part of the run family; S3 / S6-04 own it.
- Boot's `bootstrap.clj:119` opening situation and `bootstrap.clj:211-278` (rendered run queries) read it.
- `:seon.turn/*` attributes on the run (`opened-tx`, `closed-tx`, `starting-ns`, `trigger`, `situation`, `reply`,
  `attempts`, `disposition`): read by `render/transcript.clj` (many sites), `cluster/status.clj:120-157`, `eval/drive.clj`,
  `render/walk.clj:1100-1113`, `render.clj:1740`. Out of scope here; listed because deleting the open deletes their
  writer.

## 7. Findings

- F1 (not in the plan): `db/*receipt*` / `:seon.db/receipt` (db.clj:180, :3619; `seon.db.edn:258`) is a ref on every
  transaction stamp to an evaluation entity that, under one-RECORD, does not exist yet. It travels together with `*open*`
  (both are bound at `sci/eval.clj:4026-4027` and read at `db.clj:3619-3620`), so deleting `*open*` leaves a dangling
  ref stamp. S6-04 or its own row must convert it to pass id + ordinal values or delete it. 
  `resources/seon/tasks/seon.db.edn` mentions the stamp only as evidence in an unrelated tx-report task; no task owns converting it.
- F2: `submission-open-tx` rides two different ways (`opened` blob-commit at turn.clj:5953, or the pending-open at
  :5984/:4968, or `with-submission` at :3509), so three writers of the same open. One entrance after S6-04.
- F3: `recover-runs!` reports `:seon.boot/recovered-runs` of open runs; with no run entity this field and its schema key
  (`resources/seon/schemas/seon.boot.edn`, `cluster/boot.clj:553`, `cluster_test.clj:30`, `turn_test.clj:590`,
  `loop_proof_test.clj:764-774`) change meaning. Boot report is a public shape.
- F4: the run family cannot be deleted by `open-tx` alone (the plan's own warning): `*open*`, `open-call`,
  `system-run-*`, `submission-open-tx`, `generated-run-tx`, `open-for-agent` readers, `recover-*`, `close-escaped-run!`
  and the 24 fixture files are all one closure.

## 8. Order this implies

1. S6-01 (effect, group E): `request*`, `open-call`, `evaluation-eid`, the schema, `receipt-identities`, renders,
   `interruption-stamps`, and `turn.clj:1905`. The last is a `turn.clj` edit, so it waits for `s3-01` (the lane
   currently holding `turn.clj`) or is passed to it as a patch. The rest is `effect.clj` +
   `seon.effect.edn` + `effect_test.clj` only. Fixtures that write `:seon.effect/run` as a lookup ref (8 test files above)
   convert in the same commit.
2. S6-02b: `test.clj` attempt writers and flag, after S6-01.
3. S6-04: everything in sections 1-3 plus F1, F3.

## 9. Open items for root (decisions the plan leaves)

- O1: attribute name for the pass id on the effect: keep `:seon.effect/run` with string type (changed semantics under an
  old name, breaks the accretion law and needs the old-data story: DB data is disposable per owner, so a reset
  suffices) or a new name (`:seon.effect/pass`). The Q10 ROOT text says only "stores the pass id as a value".
- O2: attempt context and `:seon.effect/notify`: a test attempt that reaches a `background?` effect would need a living
  agent for the notify ref. No test does today. Recommendation: S6-02b stores no notify for an attempt context and
  refuses a background request there by name, unless root wants the throwaway agent kept.

## 10. Design check

The Q10 recommendation held against the code: the effect id and form ordinal already hold the pass id and position as
values, `evaluation-eid` is the only place the evaluation is derived, and only one test reads `:seon.effect/eval`. What
was wrong: the plan's closure list omitted `db/*receipt*` (F1) and the three-way open writers (F2), and `turn.clj:1905`
(effect reuse check) is a production reader the plan did not name. No design change; no code was run.
