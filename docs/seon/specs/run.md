---
type: spec
status: proposed
created: 2026-09-28
tags: [spec, run, one-path]
---
# seon.run — the run fold

Area 1 of the shrink: the one REPL path for one run of forms (read -> evaluate -> record -> print). Today ~12.2k lines
(`src/seon/batch.clj` 739, `src/seon/turn/batch.clj` 931, `src/seon/turn.clj` 6201, `src/seon/sci/eval.clj` 4221;
only part of the last two is the run). Budget for the rewrite: ~2k for the whole area. This spec covers the core,
the fold, and names what it calls. Every claim below was read at HEAD `f0d96f147` plus the working tree; anything
not read is marked UNVERIFIED.

## 1. What exists

### 1.1 The fold (`seon.batch/evaluate-batch`, batch.clj:666)

One `loop` (batch.clj:687) over `form-step` (566), inside the entrant's `:seon.batch/around` (SCI namespace binding,
`seon.sci.batch/around` sci/batch.clj:42), then one `:seon.batch/settle` call, then `record-faults` (630).
The whole thing is a map `:seon.batch/batch` threaded through `guard` (167: any throw becomes one diagnostic on the
batch, never propagates). The request carries 14 stage values (namespace doc, batch.clj:1-33): around, read-state,
read-next, evaluate, observe, ns-name, derive, facts, apply, fold, settle, show, publish, fault, plus `within`.

Per form (`form-step`), in order:

| # | stage | fn (file:line) | takes -> returns |
|---|-------|----------------|------------------|
| 1 | ns-before | `:ns-name` stage (`@namespace-cursor` turn.clj entrant) | request -> symbol |
| 2 | read | `read-step` batch.clj:199 -> `read-row` turn.clj:4827 (turn) / `sci.batch/read-next` sci/batch.clj:27 / `jvm-read-next` turn/batch.clj:842 | read-state -> `:seon.batch/read-step` (form, start, end, next read-state, repairs; or eof / unread-from / read-error / refused) |
| 3 | evaluate + first print | `evaluate-step` batch.clj:321 | see 1.2 |
| 4 | place | `place-step` 472 | ns-after |
| 5 | publish | `publish-step` 424 -> `publish-row` turn.clj:4917 (turn entrant only) | entry -> `{entry stop?}`: disposition rule, handle bind, may REPLACE the entry and REPRINT (shown-failure at turn.clj:4949) |
| 6 | freeze | `freeze-step` 447 | reads, executed set, call-profile, evidence :complete/:pending, owned count; adds the form sink into the batch sink |
| 7 | derive | `derive-step` 522 -> `derive-form` turn/batch.clj:301 | observed writes -> `:seon.batch/change` (entities, removals, schema-keys, namespaces, private, notes) |
| 8 | facts | `form-facts` turn/batch.clj:497 | change on working -> tx data or refusal |
| 9 | apply | `apply-pending` 480 -> `apply-facts` turn/batch.clj:513 (`db/with`) + `fold-change` 528 | tx on working -> next working + program fold, or facts-error |
| 10 | reshow | `reshow-step` 504 | reprint the entry over the new working (only if it changed the program) |

Steps 7-9 run for form N in the same `form-step` (the docstring of `apply-pending` says so); `:seon.batch/pending`
exists only between derive and apply inside one step. (The task brief said apply happens "at the next form"; at HEAD
it does not: `form-step` batch.clj:583-587 calls derive, apply, reshow in order. The defect is the double print,
section 3.)

After the loop: `settle` (turn/batch.clj:683 for MCP, JVM and test entrants; `seon.turn/settle-batch!` turn.clj:3555
for product turns, which build `:seon.turn.loop/evaluated-batch` from `evaluate-sources` turn.clj:5011-5143 with
`:seon.batch/settle` dissoc'ed at 5100), `settled-into` 643 merges the answer back, `record-faults` 630 records each
diagnostic and each `produced-failures` (601) through `world/record-fault`.

### 1.2 `evaluate-step` (batch.clj:321-422), the busiest function

Builds a per-form world (`form-world` 287: form call sink, read sink atom, owned-work AtomicLong), a
ConcurrentLinkedQueue of writes (`on-write`, refuses a write after the scope closed, 336-340), and a `run` fn that
(a) calls the evaluate stage, (b) records the outcome in a volatile, (c) times it, (d) prints it via
`print-into` (batch.clj:372) INSIDE the observation scope. The observe stage (`sci.observe/observe`
sci/observe.clj:33) wraps `run` in SCI's write/expansion observers and arms each bound Var at its write
(`arm/arm!`). After the scope: entry assembled (refused / read-error / printed / value / error), second `print-into`
if the first did not run (398), entry published, writes split by `write?` (269) into good and bad.
A form is thus: evaluated once, PRINTED inside the scope, and the writes drained after.

### 1.3 Stage owners outside the fold

- Evaluate: `evaluate-row` turn.clj:4864 -> `seon.sci.eval/evaluate` sci/eval.clj:3833 (arm, bind custody, evaluate,
  admit, record); `jvm-request` evaluates with host `eval` (turn/batch.clj:900); `test-request` calls the body (926).
- Show: THREE implementations of one contract `(request entry) -> :seon.batch/printed`: `show-row` turn.clj:4892
  (turn, adds render head function), `show` turn/batch.clj:734 (SCI code entrants), `jvm-show` turn/batch.clj:860.
  All end in `sci.eval/shown-result` (sci/eval.clj:3517) or `shown-failure` (3604).
- Settle: TWO implementations: `turn.batch/settle` (683; record-facts + program-facts, one `writer` call, one rebase
  retry via `revalidate` 650) and `seon.turn/settle-batch!` (3555; product turns: per-form terminal data, blobs,
  issue tests, measurement, fault windows, plan settle-call, its own `revalidate` retry at 3666, `commit` under
  `phase`). Both call `turn.batch/revalidate` and `program-facts`; both add `measurement/significant-tx`.
- Entrant request builders (turn/batch.clj): `sci-stages` 800, `program-request` 817, `turn-request` 830 (no callers
  found by rg; UNVERIFIED that none is loaded dynamically), `code-request` 836, `jvm-request` 888, `test-request` 912.
- Recording shape: `record-facts` (632) emits ONE `[:db.fn/call record-call {...}]` holding turn, sources, and one
  `evaluation-record` (612) per entry, for MCP/JVM/test; `settle-batch!` emits its own per-form terminal tx instead.

### 1.4 Public entry points and every caller (rg, src and test, 2026-09-28)

- `seon.batch/evaluate-batch`: `turn.clj:5100` (`evaluate-sources`), `mcp.clj:267` (`jvm-run`),
  `test/seon/test_support.clj:417` (`evaluate`); direct in tests: `error/attribution_test.clj:213`,
  `turn_test.clj:2269,2402,2420,2437`, `fn_test.clj:518,1682`.
- `seon.turn/evaluate-sources` (turn.clj:5010): `turn.clj:2118` (write path), `2131` (preview path), `5265`
  (run start), `preview-sources` 5145 (web `render/web.clj:1437`, plus tests). Tests: `turn_loop_test.clj:215`,
  `transcript_test.clj:1152`, `cluster/turn_test.clj:2540`, `cluster/evaluate_sources_test.clj:100,265`,
  `cluster/host_test.clj:536`, `turn_test.clj:180,200,207,233,2376`.
- `turn.batch/code-request`: only `test_support.clj:346,418`; SCI MCP `eval_clj` does NOT use it (it goes through a
  Seon agent's turn; UNVERIFIED which function `mcp/sci-run` calls: read before cutting).
- `turn.batch/reply` 770: `mcp.clj:277` only. `turn.batch/read-only?` 761: no caller found in src (UNVERIFIED).
- `turn.batch/revalidate`: turn.clj:3666, `turn_test.clj:2279`.

### 1.5 Measured

- Root, live JVM, `seon.mcp/call` jvm mode (reported by root, not re-run here): `(+ 1 2)` 86-111 ms; one `defn`
  557-773 ms; two `defn`s 935-1187 ms, so ~450 ms per extra defining form. The research spec
  (`docs/research/agent-platform/one-repl-path-spec-2026-09-27.md:520`) attributes 0.8-1.2 s per defining form to
  the validated speculative apply (`validate-write-report` over a speculative projection, 53 `speculative-projection`
  calls per `with`). Verified here: `apply-facts` (turn/batch.clj:513) calls `seon.db/with` (db.clj:5726), which runs
  `write-error` + `prepared-write` (the writer's validation); no unvalidated `with` exists in `seon.db`.
- MCP `eval_clj` SCI mode (this lane, one sample): `(+ 1 2)` `:ms 0`; `(defn probe-x ...)` in a scratch ns `:ms 5`,
  `:calls 334`; its call `:ms 0`, `:calls 1`. The reply `:ms` excludes the validated apply's wall time
  (UNVERIFIED which stage it stops at); the 334 armed calls are the number to explain.
- A settlement was refused "Complete component validation refused: validation-node-limit; bound 250000" with many
  `:seon.profile.measurement` rows in the tx (root): whole-value component validation (db.clj:4105) scales with
  profile flush volume, so measurement rows must not ride the validated settlement (see Q6).

## 2. Behaviors the rewrite must keep

Ids are `file::deftest`. No `test/seon/batch_test.clj` exists: the fold is tested only through turns. 9 of 27 are UNCOVERED.

1. Total: a stage throw is a diagnostic beside the value. `turn_test::a-settlement-that-throws-still-shows-each-evaluated-value`, `::a-refused-settlement-keeps-each-evaluated-value`; per-stage guards: UNCOVERED.
2. Read once, evaluate once, in the namespace the previous form left. `cluster/turn_test::evaluation-follows-the-readers-parse-time-namespace`, `::reply-reading-follows-evaluated-alias-and-dynamic-require-state`; `turn_loop_test::committed-ending-namespace-seeds-a-resumed-fold`.
3. A later form sees an earlier form's change. `turn_test::a-call-omitting-its-database-sees-the-forms-own-write`.
4. A form whose facts are refused keeps its value, proposes nothing. `turn_test::a-form-whose-facts-are-refused-says-it-was-not-admitted`, `::a-plain-failing-call-never-carries-the-not-admitted-diagnostic`.
5. One settlement; a definition or schema key changed underneath refuses at the writer. `turn_test::a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`, `::a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer`, `::removing-a-namespace-no-declaration-names-retracts-nothing`.
6. A refused (refusal) settlement is a value; the turn closes. `turn_test::a-refused-refusal-settlement-is-answered-as-a-value`, `turn_loop_test::a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again`.
7. A red form does not stop the fold. `cluster/turn_test::a-red-form-routes-to-its-namespace-owner-and-the-fold-continues`.
8. An unreadable row is a settled, never-evaluated form. `cluster/turn_test::an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence`, `run4_reader_test::run4-reader-errors-name-the-reply-and-do-not-imply-retained-input`; span-out-of-bounds diagnostic (batch.clj:212) and `:read-stage-failed` (577): UNCOVERED.
9. A form's read evidence is only what it read. `turn_test::a-forms-read-evidence-is-only-what-the-form-read`, `::generated-read-evidence-rejects-turn-activity`, `::a-turn-dependent-generated-form-keeps-its-value-beside-the-fault`.
10. A definition's failure is a fault naming the producer digest visible when it ran. `error/attribution_test::a-batch-failure-names-the-definition-visible-when-it-ran`, `::a-definitions-failure-in-an-evaluation-is-its-fault-and-task`.
11. A disposition ends the reply; an early one is refused, value kept. `turn_loop_test::a-disposition-is-read-only-when-it-really-is-one`, `::a-clean-last-form-without-a-disposition-is-loud-terminal-evidence`, `cluster/turn_test::a-waiting-disposition-frees-the-agent-and-keeps-its-note`.
12. A replaced result is shown by the one print owner. `turn_test::a-replaced-result-is-shown-by-the-one-print-owner`.
13. One render profile per run. `cluster/evaluate_sources_test::one-turn-derives-the-render-profile-exactly-once`.
14. Explicit basis retained; preview settles nothing, binds no handle. `cluster/evaluate_sources_test::ordered-evaluation-retains-one-explicit-basis-without-publication`; preview: `cluster/prompt_test`, `render_simplification_test`.
15. Result handle bound once per form. `mcp_entrance_test::a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`.
16. MCP reply = what the next context reads. `mcp_entrance_test::an-sci-call-answers-what-the-agents-next-context-reads`; `reply`'s key set as a unit (turn/batch.clj:770): UNCOVERED.
17. A run that changed loaded code signals pages. `mcp_entrance_test::only-a-run-that-changed-loaded-code-signals-pages`.
18. Evidence :pending while owned work is in flight; the value is published without waiting (batch.clj:447). Fold level UNCOVERED.
19. A write after the observation closed is refused to its writer (batch.clj:336). UNCOVERED.
20. Malformed observed writes are dropped with one diagnostic (`write?` 269). UNCOVERED.
21. Per-form call sink joins the batch sink. `profile_test` (deftest UNVERIFIED); the >100-call `repeated` threshold (115): UNCOVERED.
22. An `around` failure keeps the last published batch (batch.clj:701). UNCOVERED.
23. Settlement durable before the run answers; folded fault repeats ride it. `turn_test::a-refused-turn-write-is-bounded-and-commits-exactly-one-fault`.
24. An untrusted execution keeps its value and proposes no facts (`:seon.batch/facts-refused`, batch.clj:524). UNCOVERED (no test found setting `:seon.sci.eval/untrusted`).
25. Measurement rides an existing commit; its failure is a diagnostic. `profile/measurement_test` (line 82 shape).
26. Test-agent bodies record through the same settle (`test-request`, 912): owner test UNVERIFIED.
27. Namespace effects (in-ns, alias, import) are namespace facts. `cluster/turn_test::import-only-ns-unmap-installs-exactly-after-its-context-commit`, `::import-addition-is-ordinary-data-and-reacquires-exactly`.
New (required by D8): a test obtains a form's derived declaration row from the fold entry's change. Today RED: the readers `(:seon.program/row evaluation)` at `issue_settlement_test.clj:32`, `test_test.clj:54`, `test_reaching_test.clj:261` since `04e329e21`; the `:seon.test/rows` flag (f0d96f147) was reverted (98d836c57) because the row stayed nil.

## 3. Known defects the rewrite must fix

D1. **The defn reply is printed before the form's change exists.** `print-into` runs inside `run` (batch.clj:372) before `derive-step`, so the Var render sees no entity (fresh defn) or the OLD entity (redefinition: stale digest, contract). `reshow-step` (504) prints again after apply, so every program-changing form prints twice. The print cannot simply move outside the observation scope: printing realizes lazy values and a `defn` made while realizing one must be observed (evaluate-step docstring; no test found).
D2. **The validated apply costs ~450 ms per defining form** (1.5): `apply-pending` calls `apply-facts` -> `seon.db/with`, the writer's full validation, per form so the next form and the reshow read a checked value. Goal is <100 ms per run; this is the #1 fold cost. The writer validates the same tx again at settlement (`db/with` at 513 and `writer` at turn/batch.clj:700).
D3. **A settlement refused by `validation-node-limit`** when measurement rows ride it (1.5): measurement tx must not enter component validation of the run.
D4. **`publish-row` reprints refusals itself** (turn.clj:4949): a third print path, and it builds `unrun-evaluation` again beside the fold's.
D5. **Three show stages, two settle stages** (1.3): one contract each, repeated caps/profile plumbing.
D6. **Per-form request rebuilding**: `:seon.batch/working` and `:seon.batch/world` are re-assoc'ed onto the request for every stage (batch.clj:342, 434, 514) instead of being arguments.
D7. **Scratch keys on the run value**: `:step`, `:form-writes`, `:form-world`, `:pending`, `:done`, `:stop` are per-form locals stored on the whole batch, then dissoc'ed (577, 592, 701).
D8. **`code-request` runs derive with no writes**: no stage sets `:seon.batch/observe` except `evaluate-sources` (turn.clj:5093); `program-request`/`code-request` (turn/batch.clj:817,836) set derive but not observe, so under `test-support/evaluate` the write queue is empty and derive proposes no entity (consistent with the reverted flag's nil row; the flag's `dissoc :observe` was a no-op). UNVERIFIED by probe; the fix is a fold that observes whenever it derives.
D9. **Stages are present by key**: "an absent stage is a stage not run" (batch.clj:26): a key typo silently disables a stage and the result does not say which ran.
D10. **Two error shapes**: `diagnostic` (154) and hand-built maps (212, 415, 453) vs `refusal/diagnostic` (559). One shape (CLAUDE.md).
D11. **Undeclared error alternatives**: `evaluate-batch` is `[:cat request] -> batch`; facts errors ride an entry key with no per-stage union.

## 4. Target design

`(run request) -> result`: one `reduce` over forms with immutable locals, then one settlement. No stage plumbing keys,
no reshow, no per-form request rebuild.

```
per form: step    (read-form request rs)                  ; :seon.run/read
          out     (evaluate-form request step working)    ; value XOR error + writes + evidence, observer scope closes on return
          change  (derive request working step out)       ; :seon.run/change | nil
          working (extend working change)                 ; UNVALIDATED speculative value, see below
          shown   (show request working step out)         ; ONE print, against working
          entry   (publish request step out shown)        ; disposition may replace out and shown
after:    (settle request entries program working)        ; ONE transaction; validation happens here once
```

**How the print sees the form's own change without ~450 ms per form (D1, D2).** Working is built by an unvalidated
apply of the form's facts (a new `seon.db` function beside `with`, owned by the db lane: `prepared-write` without
`write-error`/final-report validation; UNVERIFIED that Datahike accepts it, probe first). Validation runs once, in
the settlement, as today's writer does; a form the head refuses is attributed by `revalidate` (turn/batch.clj:650,
already the OQ1 mechanism) and loses its change, keeping its value. Sacrifice: a later form may read an earlier
form's change that settlement then refuses (its own reads are recorded as evidence of the value it saw). Lazy
realization (D1): the observer scope stays open across ONE bounded realization walk (the value printer's own walk,
no second print), then closes; writes after that are late (item 19). Q1 prices the alternatives.
Tests read a row from `(:seon.run/change entry)`, never a mode flag.

Stage contracts (every function of the rewritten fold; private ones included, owner ruling). Each is a
`{:malli/schema ...}` in the defn attr-map, counted inside the line targets. Error alternatives are named union
members returned as one flat `:seon.error/base` extension (never thrown):

| fn | input -> output | error alternatives returned |
|----|-----------------|-----------------------------|
| `run` | `:seon.run/request` -> `:seon.run/result` | none: diagnostics in `:seon.run/diagnostics` |
| `read-form` | request, `:seon.run/read-state` -> `:seon.run/read` | `:seon.run/read-refused` (unreadable, span outside text, source over bound, refused response) |
| `evaluate-form` | request, read, working -> `:seon.run/outcome` | `:seon.run/evaluation-failed` (also for a stage throw) |
| `observe` (stage) | request, working, on-write, thunk -> outcome | `:seon.run/late-write` (to the writer) |
| `keep-writes` | `[:vector :any-write]` -> `{good bad}` | none (bad counted as one diagnostic) |
| `derive` | request, working, read, outcome -> `:seon.run/change` | `:seon.run/derive-refused` |
| `facts` | request, working, change -> tx | `:seon.program/mutation-refused-error` |
| `extend` | working, tx -> working | `:seon.run/facts-refused` (encoding failure only; validation is at settle) |
| `show` | request, working, entry -> `:seon.run/printed` | `:seon.run/show-failed` (minimal text + diagnostic) |
| `publish` | request, read, outcome, printed -> `:seon.run/entry` | `:seon.run/disposition-refused` (the entry's error, value kept) |
| `freeze` | form world -> `:seon.run/evidence` | none |
| `fold-change` | program, change -> program | none |
| `settle` | request, entries, program, working -> `:seon.run/settled` | `:seon.run/changed-underneath`, `:seon.run/writer-refused`, `:seon.run/storage-failed`, `:seon.run/measurement-failed` |
| `revalidate` | request, head, entries -> `[working program entries]` | per-entry `:seon.run/facts-refused` |
| `produced-failures`, `record-faults` | entries/batch -> diagnostics/faults | `:seon.run/fault-not-recorded` |
| `reply` | result -> `:seon.run/reply` | none |

18 functions, ~2 contract lines each (~40 lines) inside the fold target. Schemas: `:seon.run/read`,
`-outcome`, `-change` (today's `:seon.batch/change`, unchanged), `-entry` (one value per form, never mutated in the
vector), `-printed`, `-evidence`, `-settled`, `-result` (entries, program, working, commit, diagnostics, faults,
unread, timings, and `:seon.run/stages-ran`, D9). **Uncontracted private fns in the fold today: none by
`:malli/schema` presence** (scripted check of all 90 `defn`/`defn-` in batch.clj, turn/batch.clj and the run part of
turn.clj 4819-5143; whether private Vars are armed is UNVERIFIED). The swallowed-error sites are the unannotated
closures inside `evaluate-step` (`on-write`, `run`, `measure!`) and `settle` (`attempt`, `commit!`, `settled`,
`first-try`), which carry no contract: the rewrite makes each a named top-level function above.
`write?` and the three `sink?`/`owned?`/`stamped-clock?` predicates use `:any` under a declared polymorphic-boundary
exemption (batch.clj:74-90, 269).

Disappears: `reshow-step`, `apply-pending`, `:pending`, `place-step`, `mark`, the batch scratch keys (D7);
`publish-step`'s replacement protocol (publish is a pure function); `turn.batch/show`, `jvm-show`, `show-row` (one
`show`; the render head is a world member absent for JVM entrants); `turn.batch/settle` vs `settle-batch!` (one
settle, the product turn's issue tests, fault windows, terminal receipts and blobs are `:seon.run/settle-extras`
transaction functions passed as data, Q2); measurement rows leave the validated tx (D3); `turn-request` and
`read-only?` if rg still finds no caller at cut time; the `:seon.test/rows` idea.

Public entry points, converted in one commit: `seon.batch/evaluate-batch` -> `seon.run/run` (callers 1.4:
turn.clj:5100, mcp.clj:267, test_support.clj:417, six direct test sites); `turn/evaluate-sources` and
`turn/preview-sources` keep names and schemas (callers turn.clj:2118, 2131, 5265, render/web.clj:1437 and ~15 test
sites); `turn.batch/{jvm,code,test}-request` become three thin world + stage maps; `turn.batch/reply` stays.
Stages stay values (CLAUDE.md "one pipeline"); a request declares `:seon.run/stages` and the result reports which ran.

## 5. Targets

- Lines including the ~40 contract lines: fold 200 (from batch.clj 739); show 60 (from ~150 over three); publish 60;
  one settle 250 (from ~500 over two, estimated from extents, not counted); derive/facts/extend/fold-change ~450,
  shrunk separately. This spec's slice: ~1.5k -> ~1k; the 2k area budget also needs the rest of turn.clj and eval.clj.
- Wall ms through `seon.mcp/call`: `(+ 1 2)` from 86-111 ms to <=30 ms; one defn from 557-773 ms to <=100 ms; each
  extra defining form from ~450 ms to <=10 ms (no validation in the loop); 20-form reply <100 ms plus one
  settlement. Measure parent and change on the same probe; fix regressions over 20% or 50 ms.
- Armed calls: 334 for one `defn` (1.5) to a profile-named count; one fewer `show` per program-changing form.
- Prints per form: exactly 1 (asserted on data: count of `show` calls per entry). Passes over earlier entries: 0.

## 6. Open questions for root

Q1. **Lazy realization vs the one print (D1).** (a) today: print inside the scope, reprint after a change (two prints,
stale first); (b) print once after extend; a `defn` made while printing is a late write refused with a diagnostic
(simplest; changes the lazy-definition edge, no test exists for it); (c) a bounded realization walk inside the scope,
then one print (adds a realizer). Recommend (c) if realization is a real agent pattern, else (b).
Q2. **Validate at settlement only (D2)?** (a) unvalidated extend + settle-time `revalidate`, ~10 ms per form, later
forms may read a change settlement refuses; (b) today, ~450 ms per form; (c) validate only the form's own row
(Malli on the entity, not the speculative whole value), UNVERIFIED feasible. Recommend (a), and a new `seon.db`
function is a db-lane file: who owns it?
Q3. **Fold the product turn's `settle-batch!` (turn.clj:3555, ~300 lines) into the one settle?** Largest deletion,
touches turn.clj held by other lanes. Recommend one settle after a ledger check; else unify `revalidate` first.
Q4. Rename `:seon.batch/*` to `:seon.run/*`? A rename is a schema publication; keeping "batch" keeps a retired word.
Q5. Confirm `mcp/sci-run`, `turn-request`, `read-only?` dead or live before the cut (1.4, UNVERIFIED). Q6. Measurement rows (D3): move them to their own transaction after the settlement (violates "measurements ride a
commit that happens anyway", item 25), or exempt them from component validation in the writer?
