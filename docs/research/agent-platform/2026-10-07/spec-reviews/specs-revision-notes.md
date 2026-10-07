# Spec revision notes — errors.md and agent-loop.md (2026-10-07, HEAD 33fb994f2)

Inputs read end to end: both specs, CLAUDE.md, the data-oriented-clojure and clojure-testing skills,
docs/seon/architecture/reference-code.md, both design reviews (astra, sol61), tmp/errors/s1-s2-notes.md,
tmp/agent-loop/s1-notes.md, tmp/agent-loop/map.md, both S1 commit reviews. No JVM probes were needed: every claim
below was checked in source (file:line at 33fb994f2). No commits, no code edits, no tests.

## Verified against source (reviewer claims accepted)

| Claim | Evidence |
|---|---|
| `Throwable->map` follows `.getCause` with no seen-set, ignores suppressed | reference-code/clojure/src/clj/clojure/core_print.clj:484-501 |
| Seon already has a cycle-safe walk; root-frame with outward fallback | src/seon/error/refusal.clj:181-190, 192-206 |
| `id/id` defaults to 12 chars; signature schema needs 64 | src/seon/id.clj:95-119; resources/seon/schemas/seon.error.edn:268 |
| `:db.fn/call` gets the in-transaction db | reference-code/datahike/src/datahike/db/transaction.cljc:1172-1173 |
| Malli's five report kinds and their data (guard = [args value], schema-at-call = arm + exception) | reference-code/malli/src/malli/core.cljc:2210-2240 |
| instrument.clj already special-cases schema-at-call and the SCI interrupt | src/seon/instrument.clj:855-900 |
| `parent-access` refuses then would continue to `.relativize`; `read-window` catches every Throwable | src/seon/fs/jvm.clj:104-115, 488-503 |
| `db/q` returns refusals from several branches and appends evidence in its catch | src/seon/db.clj:2784-2844 |
| `ai/complete` never reads no-provider; the guard lives only in the old loop | `grep no-provider src/seon/ai.clj` empty; src/seon/turn.clj:4547-4569 |
| schedule firings are listened but do not open a call | resources/seon/schemas/seon.schedule.fire.edn (`:seon.wake/opens-turn? false`); src/seon/cluster/wake.clj:156-174 |
| `agent-wake-datoms` is ordered by entity within attribute; `outside-wake-t` takes a max from 0 | src/seon/cluster/wake.clj:285-312; src/seon/turn.clj:2627-2661 |
| `turn.batch/reply` takes a batch; maps diagnostics to message strings | src/seon/turn/batch.clj:770-794 |
| `db/transact!` can answer outcome-unknown | src/seon/db.clj:5637, 5696-5702, 5920 |
| `health/derived` keys by name + revisions of attributes read inside | src/seon/health.clj:73-134 |
| `prompt/select` takes history-unit maps, keeps the newest unit even over budget | src/seon/cluster/prompt.clj:302-334 |
| `repl/entity-emission` resolves ns/renderer refs and runs a turn query | src/seon/repl.clj:494-531 |
| `step` answers escaped failures and closes the reply in finally | src/seon/turn.clj:6013-6036 |
| `disarm!` joins under the dial being deleted; accepts a caller deadline; closes queued replies | src/seon/cluster/agent.clj:1540-1580 |
| `system-turn` calls `open-for-agent` in its writer admission | src/seon/turn.clj:2232-2244 |
| fault window is temporal, on the last receipt | src/seon/turn.clj:3443-3475 |
| two settlement writers today: `settle-batch!` (product) and `turn.batch/settle` (JVM/test stage) | src/seon/turn.clj:3507, 3614, 4961; src/seon/turn/batch.clj:683, 827, 907, 928 |
| `world-reference` derives evaluation id from turn id + ordinal | src/seon/error/refusal.clj:640-655 |
| `:seon.eval/origin` already exists (string) | resources/seon/schemas/seon.eval.edn:1 |
| S1 stale type after prepare's conversion | src/seon/error.clj:686-696 |
| S1 missed metadata reader/stamps | test/seon/error/declared_identity_test.clj:14; test/seon/cluster/turn_test.clj:1468,1830,2529,2574; test/seon/turn_loop_test.clj:321 |
| Task API: proofs = deftests naming the task; `:unproven`/`:no-proofs` refusals exist; propose-merge! runs and checks proofs | src/seon/task.clj:240-256; resources/seon/schemas/seon.task.edn (`:refused`); src/my/program.clj:416-475 |
| one task per producer, aspect `[ns "faults of <name>" :function]` | src/seon/error.clj:1270-1289 |

## Changes — errors.md

- Header: S1 state recorded (type key installed, metadata stamp gone); data flow names the validating constructor,
  the task-home fault row and per-occurrence attachment.
- §1 map: added `:seon.error/digest`, `:seon.fault/recorded`; `world` references pass id + position (ruling 2), not the
  turn; at/layer stay until the cut, with their load-bearing readers named (lane notes, Astra E1, Sol E1).
- §1 variations: `[:map :my.fs/path]` naked key; success alternatives must not accept an error map (Astra E2) [U].
- §1 cause chain: replaced `Throwable->map`-on-the-throwable with one cycle-safe cause+suppressed walk in native link
  shape, `:suppressed-by`, new `:seon.error/link` schema with every reader; root-frame and its helpers kept (Astra E4,
  Sol E3).
- §1 references: split into live value / world references / durable evidence / display bound; corrected the false claim
  that `references` bounds size; projection covers problems and chain data (Astra E6, Sol E7).
- §2 constructor: validates against the named variant with Malli's cached explainer for both carriers; malformed →
  `:seon.error/malformed-error`; literal path when the registry fails (ruling 1, Astra E2, Sol E2).
- §2 rule: deep helpers `fail!`, tool catches once and answers only its own `:multi` (Astra E7, Sol E6); table rows 2-3
  rewritten (db/q branches, fs helper); rows 4-6 merged.
- §2 typing producers: per-failure-site variants, 120 literals and 52 throwable sites included; base never a producer
  type (lane notes item 4, Astra E1, Sol E1).
- §3 boundary: outermost declared error wins (deliberate translation); `frame-function` takes a class symbol (Sol E3);
  entrant table → one paragraph; loop pass added.
- §4: `error?` total (Astra, Sol).
- §5: five-kind adapter, blame caller/callee with armed digest captured at the report, schema-at-call keeps its
  exception as cause and is never re-explained, interrupt rethrown, reporter failure not relabeled, no pr-str; example
  message shortened (Astra E3, Sol E4).
- §6 faults: rewritten per ruling 4 — row at the task home, 64-char signature, count/first-blob/latest-digest/latest-at,
  opening creates-or-links the producer task and sends one message in the same writer tx, every occurrence attached to
  its evaluation/call (replaces fault window), resolution = producer changed AND green run since, no digest-only
  resolution (Astra E5, Sol E5).
- §7 slices: S1b repair added; P1 type producers, P2 accrete schemas, P3 unwired pure functions, one cut, then
  S-reads / S-renders / S-delete; References line per slice from the guide's table; size table → prose.
- §8 tests: T3 now the five-kind table; added T8-T12; kept-test list from both reviews.
- §9 decisions: rewritten with rulings 1, 4, 6, 7 and the cross-spec order; Q-E1 pending.

## Changes — agent-loop.md

- Header: shared settlement owner, pass id minted before evaluation, completion as a task fact.
- §1: evaluation identity (pass id, position) before evaluation; `:seon.cluster.eval/ordinal` keeps its meaning
  (position); agent-wide order is the new `agent-ordinal` at the writer (ruling 2; "new names for changed semantics");
  added `pass`, `fault-signatures`; call gains `fault-signatures`.
- §1 work: opening wakes only open calls; anchor = maximum outside listened-wake tx, default 0, never first in AVET
  order; no-provider admission (Sol L6/L10, Astra L5). Disposition enum kept, meaning changed (§4).
- §2 pass: acquired SCI context and pass passed explicitly; provider call only when admitted; reply from the SETTLED
  batch; try/catch/finally answers or closes every request (Astra L1/L2, Sol L1/L5); context failure is a counted call.
- §2.1: `settle-pass!` = settle-batch! generalized in place and the `:seon.batch/settle` stage for JVM/test (ruling 3);
  duty list incl. complete rows, ns/read-evidence rows, blobs around the commit, installation after; committed /
  definitely refused (retry once on a fresh head, no re-evaluation) / unknown (no retry, graph stops); effects wording
  (Astra L3, Sol L2/L3).
- §2.2: three dials; terminal reply and interrupt+join under the caller's deadline kept; host-code limitation stated.
- §3: one total budget, mandatory units priced first, unsendable-context error; units are history-unit maps; cache keys
  include answered-t, profile digest, loaded identity; immutable projection with ns name / renderer symbol; honest cost
  incl. sort; exact-text reconstruction limits (Astra L7/L8, Sol L7/L8).
- §4 NEW: completion through the task system (ruling 5): `my.task/complete!`, `seon.task/proven` extracted from
  propose-merge!, completion row with listened `:seon.task/notify`, disposition `:working :wait :completed`,
  `my.turn/complete` retired.
- §5: kept code keeps its dependency closure (`open-for-agent`, `append-generated-*`, author/situation keys) (Sol L9);
  drive has a caller (see rejections).
- §6 slices: P0 schema, P1 settlement owner, P2 identity, P3 calls+calibration, P4 loop namespace + one paid pass, P5
  completion, ONE cut (+ reset), then deletion; References line per slice.
- §7 tests: T1 corrected (inside vs outside, schedule, max anchor); T8 now "effects survive"; added T9-T16; kept list.
- §8 decisions: rulings 2, 3, 5, 6, 7 recorded; sequencing with errors; Q-L1 pending.

## Reviewer findings rejected or modified, with evidence

1. Sol (loop, "Delete verified unused machinery"): "`seon.eval.drive` has no first-party caller". FALSE:
   src/seon/cluster/agent.clj:849-850 `(requiring-resolve 'seon.eval.drive/run-receipts)`, called at :870; tests
   test/seon/sci/render_source_test.clj:123 and test/seon/turn_loop_test.clj:759 (Astra). Spec converts the agent.clj
   caller and leaves drive as a deletion candidate.
2. Sol E2: add `[:seon.error/type [:= <variant>]]` to every variant. REJECTED: the constructor validates against the
   schema the type itself names, and `:multi` dispatches on the type, so no path validates a map against a variant other
   than the one its type names; 204 extra literals would restate the key (CLAUDE.md "no hypothetical restrictions").
3. Astra E1 / Sol E5: "specify adoption/migration of existing fault identities; errors has no reset ruling".
   SUPERSEDED by owner ruling 6: no migration, default reset once at the loop cut; old fault attributes stay installed
   and unread until then.
4. Sol L4: keep a folded-repeat drain through the shared owner until errors S6. NOT NEEDED: ruling 4 deletes folding,
   and the revised order lands the errors cut (carried receipts) before the loop cut, so the old loop keeps its window
   and drain unchanged until the errors cut replaces them in the one settlement owner.
5. Astra L9: carry an explicitly selected qualifying request into completion delivery. SUPERSEDED by ruling 5:
   completion is a task fact announced by the wake listener.
6. Sol L5: "derive finite await/stop allowances from existing input/form bounds". MODIFIED: stop joins under the
   caller's existing deadline (agent.clj:1540-1546 already accepts `:seon.await/deadline-nanos`); no MCP await allowance
   is derived, because host calls have no finite bound (sci/doc/interrupt.md:52,63-87) and a derived await would be a
   fourth dial (ruling 7). The limitation is stated instead.
7. Sol L7: key history units by "content plus profile content". MODIFIED: a content digest needs a pull per unit per
   pass (O(N) reads, defeating the cache). Key = [eval-id profile-digest loaded-identity] over an immutable declared
   projection that excludes mutable members (pending code evidence), with ns name / renderer symbol pulled at the miss.
8. Astra E5 (current `resolution` resolves on digest change alone when no task exists): accepted; the new rule never
   resolves without a green run, which raises Q-E2.
9. Astra L4 "turn.batch/reply converts diagnostics to strings": accepted as an errors-cut item (reply carries maps with
   receipts), not a loop change.
10. Lane note 3c "fault windows/folded faults not named": resolved by ruling 4 (per-occurrence attachment), not kept.

## New owner questions

**Q-E1 Where fault rows live.** Ruling 4 wants the task created in the same writer transaction as the opening, but
tasks live at the task home (`seon.task/home`, root's branch, task.clj:296-310) while faults happen on agent branches,
and the branch-to-cluster copy is gone.
- A (recommended): record the row at the task home; evaluations/calls on the branch carry the signature as a value
  (`fault-signatures`). One transaction creates row, task and message; no copy; root sees every fault in one place.
  Cost: the recorder writes to a second connection when the failure is on a branch.
- B: record on the failing branch with a branch-local task; root reads branches to find faults. Cost: tasks never ride
  merges (7.13), so branch faults stay invisible to the queue (`my.task/open`).
- C: row on the branch, task at the home in a second transaction. Breaks "same transaction".

**Q-E2 Deleted producer.** Resolution needs a green run of the task's tests; when the producer and its tests are
deleted, the task can never close.
- A (recommended): a deleted producer whose task has no remaining proofs resolves (the failure site no longer exists).
- B: it stays open until root closes it by hand.
- C: forbid deleting a producer whose fault task is open (merge refuses).

**Q-L1 Name of the evaluation origin enum.** `:seon.eval/origin` (string, the generating entity, seon.eval.edn:1) is
copied onto evaluations by `evaluation-facts`; the ruled `:seon.cluster.eval/origin` enum would sit beside it.
- A: keep `:seon.cluster.eval/origin` (different namespace, as ruled).
- B (recommended): name the enum `:seon.cluster.eval/entrant` (CLAUDE.md's word for model / outside / test paths).
- C: keep the name and retire `:seon.eval/origin` with the kept system-turn code.

**Q-L2 Completion for branch agents.** `my.task/complete!` records a proven completion; `my.program/propose-merge!`
already runs the same proofs and messages root.
- A (recommended): separate: complete! records and notifies; merge proposal stays root's flow.
- B: on a branch, complete! also proposes the merge (one call, but couples task completion to merge).
