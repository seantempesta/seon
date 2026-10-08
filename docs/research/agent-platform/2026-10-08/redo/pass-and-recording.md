---
title: The pass and its recording (redo of admission/P8 and the cursor-intent cut)
type: research
status: design, for owner review
date: 2026-10-08
lane: redo-pass
---

# The pass and its recording

Replaces, as one acyclic sequence: P7, P8 (with admission-design §7 S5-04a-j), P9, P12, P13, P14, S6-03 and S6-04a/b.
Authority: `review/owner-rulings.md` lines 9, 11-12, 31-33, then CLAUDE.md. Both reviews read in full. Code cited at HEAD
`aeb692d87`; default runs source `9c88ae5b8` (it does not yet hold P7a, P11 or PB-01).

**In one paragraph.** Each entrance hands the pass a text and a read stage. The pass reads one form at the live cursor,
lets SCI evaluate it, prints it while observation is open, and captures every Var write as a value at the moment it
happens. Nothing per form touches the database. After the last form: one clj-kondo analysis, a per-form derive from the
captured values, a fold by identity to the final state, and ONE recording transaction holding the call row, the
evaluation rows and the final program. The recording refuses nothing a real REPL would accept. What the program now
lacks (dangling callers, an overwritten digest, an alias retarget) becomes findings, stored as data and delivered beside
the value. Integrity is judged at root's merge (`merge.md`).

## 1. What exists (measured on my agent, MCP SCI, `redo-pass-probe`, JVM driver timing `seon.mcp/call`)

| Pass | Wall | All-thread allocation | Commits |
|---|---|---|---|
| `(+ 1 1)` warm | 43-48 ms | 55-63 MB | 1 |
| first MCP pass in the driver | 729 ms | **2,068 MB** | 1 |
| one-line `defn` (fresh name), warm | 126-131 ms | 159 MB (first two: 217-345 ms, 468-620 MB) | 1 |
| five `defn`s in one pass | 243-256 ms | 355-370 MB | 1 |
| `(defn same-f …) (same-f 1)` redefinition | 87-134 ms | 128-202 MB | 1 |
| `(ns-unmap *ns* 'pa)` with caller `pb` | 100 ms | 171 MB | 1; **next pass `(resolve 'pa)` answers the Var** |
| lazy seq whose print defines 3 Vars | 60 ms | 83 MB | 1 |
| model pass (fixture provider), from the model-pass profile | 375-675 ms | 473-928 MB | 7 per wake |

The 2 GB first pass is the PB-01 class: a second base is built for a new caller. PB-01 (`48191071f`) is committed but
is not installed on default. It needs root's restart and a re-run of this row; it is not my slice. The unmap row
reproduces `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone`.

**Today's MCP path** (`seon.mcp/sci-run` → `seon.cluster.agent/submit-source-in-projection`, `agent.clj:691`):
`forgive/segments` pre-segments the text into sources. The run request holds those sources (its `reply-size` is
`(count (pr-str sources))`, `agent.clj:774`). `seon.turn/read-row` (`turn.clj:4741`) reads each source a second time.
`seon.batch/derive-step` and `apply-pending` (`batch.clj:513-585`) analyze each form, build its facts and `db/with`
them before the next form. `settle` (`turn/batch.clj:729`) commits once; on a writer refusal it runs `rebase` →
`revalidate` (`:696-727`), which re-derives every form and strips the facts of each refused form.

**Today's model path** (`turn.clj:4513-4570`): `planned-sources` segments the reply. `stage-reply!` plus `plan-tx`
commit an intent of result-less evaluation rows. `resume-turn` reads them back (`fold-evaluations`, `:4288`) and the
settlement commits a second time. `open-turn` (`:4365`) commits before the provider call, the attempt recording
commits (`:4278`), and boot runs `recover-tx` (`:1511`) over open runs.

**Refusals no REPL makes** (all deleted below):
- `removal-facts` (`turn/batch.clj:493-510`) returns `my.program`'s deletion refusal. `derive-step` turns it into a
  facts-error, the row stays and acquisition reinstalls the Var.
- `changed-since-basis` (`:563`) refuses a definition another writer changed.
- `apply-facts` / `revalidate` strip facts form by form.
- `seon.turn/not-admitted` (`turn.clj:3329`) answers the stripped form as "Not admitted".
- `entry-evaluation` stamps `"facts not proposed: …"` on a successful value (`turn/batch.clj:637`).

**Printed text stored as data:**
- `:seon.cluster.eval/repairs` is a `:string`. `seon.turn/repair-text` prints the repairs (`turn.clj:3019`, schema
  `seon.cluster.eval.edn:48`).
- `:seon.cluster.eval/diagnostics` is "as the floor printed them".

## 2. The pass (target)

```
entrance → {:seon.batch/text t :seon.batch/mode (:reply | :code | :data) :seon.batch/start-ns n}
per form, at the live cursor:
  READ    reply: one forgiveness scan once per text (prose → comments, delimiter repair with
          {:seon.batch/line n :seon.batch/inserted ")"} notes); then sci/parse-next at the cursor,
          options {:features #{:clj} :end-location true}; the span is the stored source.
          code: the same without prose classification. data: §3.
  EVAL    sci/eval-form in the agent's fork, in the current namespace; nothing refused
  PRINT   value printer once, observation still open (lazy writes join this form, b5ff51e4c)
  CAPTURE each root :bind/:unmap/:remove-ns write → :seon.batch/write {sym var kind fn? meta
          resolver-row arm-error}; register! commits through the world writer like transact!
after the last form:
  ANALYZE one seon.fn/runtime-analysis-batch: one request per form with fn/test root writes,
          each under its captured resolver row; skipped when none
  DERIVE  per form from captured values only (seon.turn.batch/derive-form, supplied analysis)
  FOLD    fold-change by identity: last definition wins, value def over a fn retires its row,
          removals in order → final state + findings
  RECORD  ONE [:db.fn/call] at the writer on the current head: call row (pass id, reply text or
          blob digest, start ns, provider attempt facts), evaluation rows (pass id + ordinal, minted
          before eval; ordinals at the writer), the final program, findings and repairs as data
```

**What the recording may and may not refuse.** It checks the shape of each value it writes against that value's
schema (types, its own required members). It does not check program integrity between entities: callers, contract
refs, render pairs or test subjects. That is merge's job. A malformed authored contract is stored as data (P4). If
the writer still refuses the transaction (a Seon defect or a storage failure), the pass makes one second attempt:
call row plus evaluation rows plus the refusal as a recorded Seon error, without program facts. Either way the
values stay delivered. `rebase` and `revalidate` are deleted.

**Findings** (`:seon.batch/findings`, Seon-produced, fixed shape, stored on the evaluation of the form that caused
them as `[:set [:tuple :keyword :symbol :symbol]]`):
- `[:dangling removed referrer]`
- `[:swept removed referrer]`: a required-ref referrer, such as a render pair, retracted with what it named
  (question 2)
- `[:overwrote sym other-writer-digest]` (question 1)
- `[:alias-retargeted defined-sym alias]`
- `[:live-only sym]`
- `[:defined-outside-this-form sym]`

Repairs become `[:set [:tuple pos-int? [:enum :inserted :dropped] :string]]` on the form that owns the line.
Diagnostics become components holding `:seon.error/type`, `:seon.error/operation`, `:seon.error/message`, with
references for large members (question 4). The value printer prints all of them at the edge; nothing stores their
text.

**Same-pass reads.** `doc`, `source`, `my.program/*` and `remove-ns!` answer from the live SCI world for names this
pass defined. A plain `(seon.db/pull … [:seon.fn/sym f])` reads the committed head. Program entities are the
recording, not the form's effect (question 3). `transact!` and `register!` writes are effects, so later forms see
them.

**Source fidelity under a retarget** (Astra §1). Each definition is analyzed under the namespace row it was written
under, so its edges are what its Var does. Its digest uses the final row the recording stores. A definition whose used
aliases differ between the two rows gets `[:alias-retargeted sym alias]`, because a reload would change its meaning.
The check compares alias maps restricted to the alias names the analysis says it used (O(aliases used)).

## 3. Entrances as read stages (the DATA reader, which Astra flagged)

| Mode | Text | Rules |
|---|---|---|
| `:reply` (model) | the reply | forgiveness scan, then the cursor; prose-only answers a call row with the no-form refusal and no evaluation |
| `:code` (MCP, tests, generated) | as is | the cursor only; a lone fence line outside forms is skipped; no form answers `nil` (P12) |
| `:data` (model, DATA entrance) | one EDN map `{:ns sym :forms [f …]}` | below |

The DATA read is the same cursor, scoped to the `:forms` vector:
1. One structural scan finds the top-level map, the `:ns` and `:forms` keys, and the span of each element of the
   `:forms` vector. It is the existing `forgive/scan` in `:code` mode on the vector's body, so the cost is linear.
2. `:ns`, read by SCI from its token span, is the start namespace.
3. Each `:forms` element is read by `sci/parse-next` from its span **when it is due**, after the previous element
   ran. So `::alias/k`, tags installed by `set! *data-readers*` and `#?` behave as in code mode. A string element is a
   string literal, as at the REPL.
4. If the reply is not a map, lacks `:forms`, has a non-vector `:forms`, or carries keys other than `:ns` and
   `:forms`, the read fails once (one call row, one read error, no evaluation).
5. An element that fails to read is its own error, and reading continues at the next element.
6. The reply is the vector of envelope maps the MCP reply already uses (`#:seon.repl{:result :ms :value …}`), one per
   element.

No second parser and no pre-read values. Forgiveness is not applied in `:data` mode.

## 4. Crash and recovery

The pass writes nothing durable of its own before RECORD. A crash before the commit loses:
- the evaluation rows, the call row, the reply text and the answered basis;
- for a model pass, the provider's reply (the wake stays unanswered, so the model is asked again).

A crash does not lose any durable effect a form already made: the agent's `transact!`, a `register!` commit, or an
effect receipt keyed on pass id + ordinal (S6-01). The work stays due; an effect repeats unless its owner is
idempotent. Boot recovery shrinks to `effect/interruption-stamps` over receipts whose pass id has no recorded
evaluation. `recover-tx`, `recover-call`, the open/close families and `:seon.boot/recovered-runs` are deleted. Nothing
is resumed, and no live value is serialized.

## 5. Deleted

- **Reading:** `forgive/segments` and its helpers (`owned`, `comment-of`, `no-value?`), `turn/segmented-sources`,
  `planned-sources`, `code-sources`, `cluster.agent/segmenter`, `turn/read-row`, `turn-rows-state`,
  `fold-evaluations`, `fold-source`, `repair-text`, the `pr-str` reply-size.
- **Intent and open:** `stage-reply!`/`plan-tx`/`plan-call`/`source-rows`, `open-turn`'s commit,
  `submission-open-tx`, `db/*open*`, `:seon.db/pending-open`, `db/*receipt*`.
- **Admission:** `seon.batch/apply-pending` and the `:seon.batch/apply`/`:facts` stages, `turn.batch/apply-facts`,
  `revalidate`, `rebase`, `changed-since-basis`, `analyze-form`, `captured-locals`' second kondo run, the facts-error
  stamp, `turn/not-admitted`, and `removal-facts`' refusal branch.
- **Declaration machinery:** `seon.sci.reader` `declaration-facts` and its recovery helpers (P13).

Estimate: about 1,100 source lines out, about 150 in.

## 6. Cost per operation (targets; each slice reports the probe row)

| Operation | Today | Target |
|---|---|---|
| reply scan + reads, 28.6 KB | 10.5 ms / 4 MB (S2b-1) | ≤ 3 ms / ≤ 8 MB, read once |
| capture per root bind | — | 2.3 µs / 3.1 KB |
| analysis, N defining forms | N calls: 13 / 62.6 / 232.5 ms at N = 1 / 5 / 20 | one call: 11.5 / 13.9 / 16.3 ms; 0 with no fn root |
| per-form `db/with` | 64 ms mean per following form | 0 |
| recording, trivial pass | 43-48 ms / 55-63 MB whole pass | the writer floor (13-14 MB, 21-30 ms, accepted) + rows; pass ≤ 40 MB, < 60 ms |
| `defn` pass | 126-131 ms / 159 MB | < 120 ms, ≤ 60 MB |
| five-`defn` pass | 243-256 ms / 355-370 MB | < 150 ms, ≤ 80 MB |
| model pass (Seon's part) | 7 commits, 375-675 ms | 1 commit; the same as an MCP pass of the same forms + block |

Where the `defn` pass's 159 MB goes is not decomposed here. RP-0 measures that before A-rows are ranked.

## 7. Slices (one acyclic order; ~15 min each)

Proofs go through `seon.mcp/call` (`eval_clj`, fresh agent per row). Each asserts data with a later call, for example
`(= expected result/e…)` → `true` or `(seon.db/pull …)`, in the style of `seon.repl-differential-p3-test`. Model-path
rows use the fixture provider through the turn proc, the real model entrance. Budget row on every slice: the §1 MCP
rows must not regress over 20% or 50 ms.

| Id (replaces) | Change | Files | Contract | Proof (new unless marked) | Deps |
|---|---|---|---|---|---|
| RP-0 | Measure the owners of the `defn` and five-`defn` passes: allocation per armed fn | `test/seon/pass_probe.clj` | — | the probe's `:defn` row with owners | — |
| RP-1 (S5-04i, task above) | `removal-facts` returns retractions plus `[:dangling …]`/`[:swept …]` findings; never the refusal | `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`, test | `[:=> [:cat db sym] [:map [::tx tx-data] [::findings …]]]` | `seon.repl-differential-p8-test/a-core-unmap-with-a-surviving-caller-stays-unmapped`: next pass `(resolve 'pa)` → `nil`, row absent, finding present | — |
| RP-2 (S5-04a) | analysis batch partitions per namespace row | `src/seon/fn.clj`, `test/seon/fn_test.clj` | unchanged | `seon.fn-test/a-batch-analyzes-each-request-under-its-own-namespace-row` | — |
| RP-3 (S5-04b) | write capture adds meta + resolver row | `src/seon/sci/arm.clj`, `src/seon/sci/observe.clj`, `seon.batch.edn`, test | `:seon.batch/write` gains `:seon.batch/meta`, `:seon.batch/resolver` | `…-p8-test/redefinition-keeps-each-calls-value-and-stores-the-last` | RP-2 |
| RP-4 (S5-04g) | `register!` in an evaluation commits through the world writer; arming reads the connection | `src/seon/schema.clj`, `src/seon/sci/arm.clj`, `seon.schema.edn`, test | world-member supplied writer | `…-p8-test/register-then-transact-then-read-in-one-pass` | RP-3 |
| RP-5 (S5-04h1, j) | `derive-form` takes the one supplied analysis; cross-ns slices and locals in the same call; `analyze-form` and the second kondo run deleted | `src/seon/turn/batch.clj`, `src/seon/fn/analyzer.clj`, test | `derive-form [request state analysis entry writes]` | `…-p8-test/nested-and-cross-namespace-definitions-analyze-once` | RP-3 |
| RP-6 (S5-04h2, d, e, f) | end stage: derive → fold → record; delete `apply-pending`, the apply/facts stages and the facts-error stamp; value-def retire; unjoined writes as findings | `src/seon/batch.clj`, `src/seon/turn/batch.clj`, `seon.batch.edn`, test | `:seon.batch/request` loses `:apply`, `:facts` | `…-p8-test/a-successful-form-is-never-marked-failed`, `…/a-value-def-over-a-function-retires-its-row` | RP-1, RP-4, RP-5 |
| RP-7 (settle) | delete `changed-since-basis`/`revalidate`/`rebase`/`not-admitted`; `[:overwrote …]`; writer-refusal second attempt without program facts | `src/seon/turn/batch.clj`, `src/seon/turn.clj` (`not-admitted`), test | `settle` answers `:seon.batch/commit` with findings | `…-p8-test/another-writers-change-is-overwritten-with-a-finding`, `…/a-refused-recording-keeps-values-and-rows` | RP-6 |
| RP-8 (S5-04c, Astra) | digest under the final row, edges under the captured row; `[:alias-retargeted …]` | `src/seon/turn/batch.clj`, test | — | `…-p8-test/an-alias-retarget-keeps-both-calls-and-notes-the-reload-change` (incl. reacquire and repeat both calls) | RP-6 |
| RP-9 (P9) | nested closure `defn` recorded with its enclosing top-level form | `src/seon/turn/batch.clj`, test | — | `…-p8-test/a-closure-defn-is-a-program-entity` | RP-6 |
| RP-10 (P14, S6-04b part) | model reply → in-memory submission, as MCP's; delete `stage-reply!`/`plan-tx`/`plan-call`/`source-rows`; reply staged and published around the settlement | `src/seon/turn.clj`, `test/seon/cluster/turn_test.clj` | `resume-turn` takes the submission | `seon.cluster.turn-test/a-model-reply-pass-commits-its-evaluations-once` (commit count from the log) | — |
| RP-11 (P7, S6-03) | submission = text + mode; live cursor (`sci.batch/read-next`) for model and MCP; repairs attach per entry from the read step; delete the reading list of §5 | `src/seon/turn.clj`, `src/seon/cluster/agent.clj`, `src/seon/sci/forgive.clj`, `test/seon/repl_differential_p7_test.clj` (marked) | `:seon.agent/run-request` holds `:seon.batch/text`, no `:seon.turn/sources` | `seon.repl-differential-p7-test/an-alias-required-by-one-form-resolves-in-the-next`, the P6/P7a rows | RP-10 |
| RP-12 (P7a fix) | repairs, findings and diagnostics stored as data; publish and adopt the schema on a branch of the live store | `seon.cluster.eval.edn`, `src/seon/turn.clj`, `src/seon/turn/batch.clj`, test | §2 tuple and component shapes | `seon.repl-differential-p7-test/repairs-ride-beside-the-form-they-repaired` (marked; pulls the tuple set) | RP-11, RP-7 |
| RP-13 (P12) | code input with no form answers `nil` | `src/seon/turn.clj`, test | — | `…-p8-test/a-discarded-form-answers-nil` | RP-11 |
| RP-14 (S6-04a) | run open and attempt facts ride the one settlement; delete `open-turn`'s commit, `submission-open-tx`, `*open*`, `pending-open`, `*receipt*` | `src/seon/turn.clj`, `src/seon/db.clj`, `src/seon/sci/eval.clj`, `seon.db.edn`, `test/seon/db_test.clj` | — | `seon.db-test/a-transaction-stamp-carries-no-ref-to-an-unrecorded-evaluation`; the model row asserts 1 commit per pass | RP-10, S6-01, S6-02b (outside) |
| RP-15a (S6-04b) | boot recovery = `effect/interruption-stamps` by pass id; delete `recover-tx`/`recover-call`/close families, `:seon.boot/recovered-runs` | `src/seon/turn.clj`, `src/seon/cluster/boot.clj`, `seon.boot.edn`, test | — | `seon.cluster.turn-test/a-crash-before-record-keeps-only-durable-effects` | RP-14 |
| RP-15b-d | the 24 `turn/open-tx` fixture files convert to the pass entrance (three groups of 8, per I-Q10) | listed in I-Q10 | — | each group's existing tests green | RP-15a |
| RP-16 (DATA) | `:data` read stage (§3) and the `:seon.config.ai/entrance` dial | `src/seon/sci/forgive.clj`, `src/seon/sci/batch.clj`, `seon.batch.edn`, test | mode enum gains `:data` | `…-p8-test/data-forms-read-at-the-cursor` (alias then `::a/k` element; malformed map; bad element continues) | RP-11 |
| RP-17a-b (P13) | convert the eight `seon.sci.reader/read` callers, then delete the declaration machinery | the eight caller files, `src/seon/sci/reader.cljc`, test | — | `seon.sci.reader-test/stored-source-reads-as-it-was-evaluated` | RP-11, RP-6 |

The cycle P7 → P14 → S6 → S6-03 → P7 disappears. The model path already has the MCP submission path, which keeps no
intent rows, so RP-10 needs nothing first. The cursor (RP-11) then changes only the submission's shape. The open and
recovery deletions (RP-14, RP-15) come last, after the effect provenance (S6-01) they need. A rows and the RP-10/11
rows touch disjoint files except `turn/batch.clj` (A only) and `turn.clj` (RP-7's `not-admitted`; serialize RP-7
after RP-11).

## 8. Owner questions (recommendation first)

1. **Another writer changed a definition on the agent's branch after the pass read it.** *Recommend:* the recording
   stores the agent's final state (its REPL holds it) with `[:overwrote …]`. History keeps the other version, and merge
   checks digests at its own writer. Alternative: push back, which refuses a REPL-valid change.
2. **Removal whose referrer holds a required ref** (a render pair naming the removed renderer). *Recommend:* sweep the
   referrer with `[:swept …]`, because the agent's world is broken the same way. This contradicts CLAUDE.md "Required
   refs refuse invalidating deletion" on agent branches; that sentence would gain "(at merge)".
3. **Same-pass program reads.** *Recommend:* program tools read the live world; plain database reads see the head.
4. **Diagnostics as data.** CLAUDE.md says a stored copy is "shown text plus ids". *Recommend:* Seon's own fixed-shape
   members (type, operation, fixed message, references) are stored as data. Copies of agent values stay shown text.
   CLAUDE.md is amended in that section.
5. **A model reply lost by a crash before RECORD.** *Recommend:* accept it (the reply is not a durable effect, and
   the wake is asked again). The alternative is a pre-eval blob commit, a second transaction.
6. **DATA forms read at the cursor inside `:forms`** (§3), not as pre-read values. *Recommend yes.*
7. **Nested closure `defn`.** *Recommend:* record it with its enclosing top-level form as source (RP-9).

## Limits

- Design only: no source changed.
- Default runs `9c88ae5b8`, which is older than P7a, P11 and PB-01. HEAD's numbers for §1 need root's restart and a
  re-run of the driver above.
- Allocation is all-thread in the shared JVM, single runs, warm except where noted.
- The model-pass row is the profile's, not re-run (no paid or fixture model pass was made).
- Health was `:faulted` before the probes (pre-existing open faults).
