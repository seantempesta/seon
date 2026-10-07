---
type: design
status: gate 1, v2 (root's rulings on both reviews applied; design only, no src/test edits)
created: 2026-10-07
lane: s3-prep
---

# S3: origin replaces author (lane s3-prep, 2026-10-07)

What exists: one row producer, `seon.turn/receipt-row`, stamps every evaluation with `:seon.cluster.eval/author`
(`:agent` or `:system`) from the request its caller builds. The two settlement writer calls, `receipt-settle-batch-call`
and `record-evaluated-call`, commit those rows. Principal approach: the same producer writes
`:seon.cluster.eval/origin [:enum :model :outside]` instead, every author reader and writer converts in one commit,
and nothing else changes. S3 is that conversion alone.

Root's rulings on the two reviews (`review-s3-opus-notes.md`, `review-s3-codex-notes.md`) shrink v1:
- only the enum values S3 writes (`:model :outside`); S4 adds `:opening :input :test` with their writers;
- agent ordinals and the ordered history read move to S4 with their first reader (S4 is a reset point, so S3-era
  ordinals would be discarded); §6 records their specification for S4;
- the opening fact and input delivery identities move to S4 with their producers (no schema without a writer);
- the "unindexed refs" claim is corrected (§1.3);
- the data-modeling skill paragraph is corrected in the S3 implementation commit (§3.4).

Citations are HEAD `9c07dfb58` (s5a-reply landed as `7b5535cf6`), read with `git show HEAD:…`. The working tree holds
other lanes' uncommitted edits to `render/transcript.clj`, `render/web.clj` and `fn.clj`, none of them S3's.

## 0. Broken things first

1. **JVM-mode MCP drops a form that fails to read, and every form after it, silently.** `(+ 1 1)` /
   `(vector ::nope.alias/k)` / `(+ 2 2)` in JVM mode answers only `2`; SCI mode answers `2`, the read error, `4`.
   A variant answered only "The run was evaluated but not recorded." (`:seon.batch/tx-count 0`). Task created:
   `seon.turn.batch/a-jvm-read-error-drops-the-form-and-every-later-form` (`e333444a896c`); Opus reproduced it.
2. **A pulled agent namespace prints as one escaped string of a whole namespace render** (10,680 value tokens).
   Task created: `seon.render/a-pulled-agent-namespace-prints-as-one-escaped-string` (`61e6f8134119`). The
   printer-text design (`e0eca860a`) names `seon.repl/data-text`'s re-print as the cause.
3. Default is `:faulted`: open faults `seon.turn.world/record-fault` (`3d2fb1b41dc8`), `seon.turn/show-row` →
   `shown-failure` nil (`7f5dfb6ad7a2`), `seon.repl/entity-emission`; `seon.schema/call-with-projection-state`
   max 419 s (`c4ece4f4eec9`, `d27179bab996`, from the reviews' status reads).
4. **r-record §2 is wrong that `:seon.eval/{value-tokens shown-tokens}` have no reader.** `seon.repl/findings`
   validates the stored emission against `:seon.config.eval/bounds` (`resources/seon/schemas/seon.config.eval.edn:14-18`),
   which produces `:large-value` from them. A key-name `rg` cannot see a schema-driven reader.
5. **The data-modeling skill contradicts the approved spec** (`.agents/skills/data-modeling/SKILL.md:180-183`: "Do
   not add an author-kind stamp"). Corrected in the S3 commit (§3.4).
6. Allocation in SCI mode is unmeasured: the evaluation thread is virtual and answers -1, and
   `seon.sci.kernel/allocated-bytes` turns `(- -1 -1)` into 0 bytes rather than unknown (Opus L3). Same class as
   existing task `seon.batch/allocation-is-unmeasured-on-a-virtual-thread` (`ae0364590441`); root to append the
   kernel case to that task's intent.

## 1. What exists (traced at HEAD)

### 1.1 Pass ids and evaluation ids: already true, unchanged by S3

| Entrant | Pass id minted before eval | Where |
|---|---|---|
| MCP SCI (`evaluate-source!` → `submit-source!`) | `(id/id)` | `src/seon/cluster/agent.clj:754` |
| MCP JVM (`jvm-run`) | `(id/id)` | `src/seon/mcp.clj:274` |
| model turn | `next-id` at `open-turn` (count-based; deleted with `open-turn`) | `src/seon/turn.clj` `next-id` |

Evaluation id = `(seon.id/evaluation pass-id position)` (`src/seon/id.clj:278`) via `seon.turn/receipt-identity`.
Test members record no evaluations today: `seon.test/attempt-request-context` (`src/seon/test.clj:184`) opens an
attempt turn for effect-reaching members only, and `seon.turn.batch/test-request` has no production caller. `:test`
is therefore not an S3 value.

### 1.2 Every author writer and reader (`git grep cluster.eval/author HEAD -- src resources test`)

Writers (all through `receipt-row`, `turn.clj:897`, writing at `:916`, or stamped beside it):

| Site | Today | S3 |
|---|---|---|
| `plan-tx-for-author` `:485`, called `:505` with `:agent` (model reply freeze) → `plan-call` `:635` (contract `:647-648`, destructure `:655`) | `:agent` | origin `:model`; rename to `plan-tx` body, contract key `:seon.cluster.eval/origin` |
| `system-run-plan` `:714` → `source-rows` `:597` (`:623`): every submission (MCP SCI, `virtual-turn!`, `script/seon/dev/settlement_probe.clj`) | `:system` | origin `:outside` |
| `record-evaluated-call` `:1289`, stamping `:1316`: MCP JVM via `jvm-request` (`turn/batch.clj:915`, rows built by `evaluation-record` `:635`); also the refresh's `record-evaluated-tx` | `:system` | stop stamping; `evaluation-record` adds `:outside`; refresh rows carry no origin |
| `append-generated-call` `:791` (`:827`), the `:generate` bootstrap seed | `:system` | no origin (producer deleted in S4) |
| contracts of `receipt-start-tx` `:877` (`:889-890`) and `receipt-start-call` `:919` (`:934-935`) | author key | origin key |

Readers:

| Site | Reads | S3 |
|---|---|---|
| `recorded-evaluation` `:1236` (`:1245`): the duplicate-recording comparison of `record-evaluated-call` (`stored-record-content` `:1262`) | author | selects origin (missed by v1; both reviews) |
| `latest-evaluations` `:1787` (`:1808`), refresh | `(= :system author)` | `(not= :model origin)` |
| disposition check `:3338` | `[?form author :agent]` | `[?form origin :model]` |
| `generated-read-fault` gate `:4976-4977`, refresh | `(= :system author)` | `(not= :model origin)` |

Behavior on old rows (no origin): the three `src` readers treat them as not-model. That flips only old `:agent` rows,
which none of the three can match (they need a `:seon.repl/changes` shown value, a `:generate` run, or the current
run's last form, which is always new). `recorded-evaluation` compares only new requests against their own stored
rows. S4's reset removes old rows.

Tests: 25 sites in 9 files (`bootstrap_test` 1, `cluster/turn_test` 5, `context_selection_test` 2, `loop_proof_test`
1, `render/transcript_run_test` 3, `schema/datahike_parity.edn` 1, `turn_loop_test` 2, `turn_test` 9, `turn_work_test`
1). Conversion is not a blind `:system` → `:outside`: rows from `append-generated-call` and the refresh get no
origin, so `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix` (`turn_test.clj:1019` region)
asserts absence there.

### 1.3 Order today (corrected)

v1 said neither `:seon.cluster.eval/run` nor `:seon.turn/agent` is avet-indexed. **Wrong.** Datahike derives
`:db/index` for every ref (`reference-code/datahike/src/datahike/db/utils.cljc:308`: `:db.type/ref [:db.type/ref
:db/index]`), and `indexing?` reads that reverse schema (`:40-42`). Astra verified both attributes in
`(:rschema db) :db/index` on default with avet datoms (`result/ec734e6941f7a`). `seon.eval/of-agent` orders by the
assertion transaction of `:seon.turn/id`, then turn id and form ordinal (`src/seon/eval.clj:51-71`). The
attribution of r-context's 17 MB to a whole-branch scan is withdrawn; the measured numbers in §6 stand as
measurements, not a causal split.

## 2. What disappears, what changes

- Disappears: every author write, the author key in three contracts (`plan-call`, `receipt-start-tx`, `receipt-start-call`), the `:system` conflation of MCP runs with
  generated reads.
- Changes: `receipt-row` writes origin; four readers; 25 test sites; `seon.cluster.eval.edn`.
- Added: one attribute. No function, no read, no request key.
- Stays: `:seon.cluster.eval/author` declared and optional on `:seon.cluster.eval/receipt` until S4's reset, so old
  rows still validate on write paths that touch them; S4 deletes the declaration with the refresh.
- Serves RECORD on the one happy path (the fact S4's appended transcript and restart read filter on). Owner: the
  existing row producer. Lets S4 delete the author conflation and filter history by one fact.

## 3. The change

### 3.1 Schema (`resources/seon/schemas/seon.cluster.eval.edn`)

```clojure
:origin [:enum {:seon.wake/context-inert true
                :description "Which entrant's pass recorded this evaluation: :model for the agent's own reply,
                              :outside for MCP SCI and JVM runs."}
         :model :outside]
```

Optional on `:seon.cluster.eval/receipt`. `:seon.wake/context-inert true` like every `:seon.cluster.eval/*` key:
`wake/inert-attributes` (`src/seon/cluster/wake.clj:144-152`) feeds `render.walk`'s root selector and the refresh's
generated-read check; without it origin alone would count as context-bearing until those readers die in S4. The
`author` description is updated to "Retired: replaced by `:seon.cluster.eval/origin`; old rows only."

### 3.2 Producers and readers

As §1.2's tables. `plan-tx-for-author` folds into `plan-tx` (its only caller passes a constant). `evaluation-record`
gains `:seon.cluster.eval/origin :outside`; `record-evaluated-call` keeps the record's origin (absent for the refresh)
instead of stamping. Every changed function keeps or tightens its contract on one line with the registry key.

### 3.3 Cost

Origin replaces author: one keyword datom per evaluation either way, one attribute path either way. Expected S0 delta
on every row: zero within noise (no new read, no new attribute path per commit). Measured before/after on the S0
MCP SCI, MCP JVM and model rows at landing, on the same probe.

### 3.4 Same commit: correct stale text

- `.agents/skills/data-modeling/SKILL.md:180-183` becomes: "Each evaluation names its entrant in
  `:seon.cluster.eval/origin` (`:model`, `:outside`; S4 adds `:opening`, `:input`, `:test` with their passes). The
  agent's history is its `:opening`, `:input` and `:model` evaluations; never derive origin from reply presence or
  provider attempts."
- For root (agents never edit the schedule): `docs/seon/schedule/README.md:509` says "author -> origin (loop S1/S5)";
  it lands in S3. `docs/seon/issues/scratch-root-has-agent-errors-without-attempt-facts.md` cites stored `author
  :agent` rows as dated evidence; it stays as written.

## 4. Risks and the tests that reach them

| Risk | Test |
|---|---|
| an entrant writes the wrong or no origin | new `seon.mcp-entrance-test/entrant-origin-is-recorded-on-each-evaluation`: table over MCP SCI (`:outside`), MCP JVM (`:outside`), a model pass through the fixture provider (`:model`), asserting the stored fact |
| an identical JVM re-record now conflicts, or a conflicting one is admitted | existing `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`; `recorded-evaluation`'s comparison includes origin |
| refresh/generated paths change behavior | existing `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix` (origin absent), `seon.turn-test/receipt-transitions-preserve-one-terminal-outcome`, `seon.turn-test/recovery-preserves-terminal-receipts-exactly` |
| refusal settlements drop origin | existing `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, `seon.turn-test/a-refused-refusal-settlement-is-answered-as-a-value` |
| the disposition check misses model forms | existing `seon.cluster.turn-test` author-asserting tests, converted |
| schema not adopted | `bin/lane commit … --test` publishes and adopts on a live-store branch; `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection` |

## 5. Claims for landing

1. Every MCP SCI, MCP JVM and model evaluation recorded after S3 carries exactly one origin; refresh and generated
   rows carry none (temporary, deleted in S4); old rows unchanged.
2. No author datom is written after S3.
3. The schema publishes and adopts on a live-store branch with zero lint and contract findings.
4. S0 rows unchanged within noise.

## 6. Specification handed to S4 (ordinals and the history read)

Recorded here for S4's design; none of it is S3 code.

**Fact.** `:seon.cluster.eval/agent-ordinal [:tuple {:seon.db/unique true …} :seon.agent/id [:int {:min 1}]]`,
context-inert. Prefer `:seon.db/unique` (`:db.unique/value`, which implies avet) over `:seon.db/index`, so a
duplicate ordinal refuses at the writer (Opus H1); verify tuple uniqueness on a branch first (not probed). No
`:seon.agent/last-ordinal` counter: the maximum is read from the index. Spec §4.9 wording: "agent ordinals assigned
at the writer".

**Last ordinal (writer).** `(d/rseek-datoms db :avet attr [agent-id Long/MAX_VALUE])`, first datom accepted only when
`(= attr (:a d))` and `(= agent-id (first (:v d)))`; otherwise 0. Unchecked, the seek returns another agent's tuple
(absent `"zzz"` → `["zz-late" 200]`, would start at 201) or another attribute's datom (absent `"absent"` →
`[:db/ident …]`, threw inside the writer) — both reviewers measured it on a stored index. Lives in `seon.db`.

**Assignment, once per writer call.** In `receipt-settle-batch-call` after its `mapcat`, over the batch's ordered
evaluation ids (the inner `receipt-settle-call`s share one database value, so per-call assignment duplicates); the
single-receipt arm delegates with a singleton vector. In `record-evaluated-call`, only in its write branch; its
identical-replay branch returns `[]` and `recorded-evaluation` never compares the ordinal. The batch call reads the
agent id once (run → `:seon.turn/agent` → `:seon.agent/id`). A `:db.fn/call` sees ops spliced before it
(`datahike/src/datahike/db/transaction.cljc:1055-1062`, `:1172`, queue order `:1312`/`:1345`); the serialized writer
needs no lock or retry. If S4's append needs the ordinal, it comes back through the settlement result, never a reread.

**Read.** `(d/index-range db {:attrid attr :start [agent-id 1] :end [agent-id Long/MAX_VALUE]})`, then the exchange
projection. The projection is the data the agent saw, not a hand list: reuse `seon.repl/cost-attributes`
(`src/seon/repl.clj:316`) and the stored-to-emission owner, including faults, call counts, repeated calls,
durations, value class, output, diagnostics and findings, or consume exactly the finished exchange S4 chooses
(Astra). No second pull decoder in `seon.db`. It also needs S4's assignment boundary (agent + origin alone spans
earlier assignments). Proof: restart acquisition equals the appended transcript over ordinary values, errors,
diagnostics, faults, output and findings.

**Tests for S4.** Ordinal numbering table with agents sorting before and after every other agent on the branch, a
multi-form batch, two passes in writer order across SCI and JVM, a refused settlement, an identical replay and a
conflicting replay; asserting committed numbering, never timing.

**Measured evidence for S4's budget.**

| Read, 200 exchanges of ~2 KB shown (405 KB text) | ms | allocated |
|---|---|---|
| warm seek, stored tuple index (Opus, mean of 500) | 0.009 | 11.8 KB |
| warm last ordinal, stored (Opus) | 0.003 | 4.3 KB |
| cold seek, private node cache (Opus; Astra 0.35 ms / 1.46 MB) | 0.38-0.68 | 1.47 MB (4 node reads) |
| cold last ordinal (both) | 0.24-0.29 | 0.74-0.80 MB (2 node reads) |
| warm fetch, one raw `d/datoms :eavt e` walk per entity (mine) | 0.6-0.7 | 1.3 MB (3.2× text) |
| cold fetch, first read of the branch after a JVM restart (mine: index query 4.0 MB + walk 1.3 MB) | 4.3 | 5.3 MB |
| **composed cold** (seek + fetch, Opus M3) | ~5 | **~6.8 MB (~17× text)** |
| today's narrow read, same agent (raw query + `d/pull-many`, warm) | 2.6 | 2.7 MB |
| through `seon.db/pull-many` (Seon wrapper), 3 attrs, warm | 3.4-3.9 | 7.8 MB |

S4 sets its cold-acquisition budget from the composed read measured once, cold, on the projection it lands (not from
these partial rows). Each cold node restore is ~340-420 KB of Datahike/konserve decoding; if S4 misses its row,
that cost goes beside task `cd6c2900565a` (`a-small-raw-commit-allocates-a-hundred-megabytes`).

## 7. Design check

- Held: `receipt-row` is the single producer; the conversion touches four readers and three contracts in one file
  plus one batch line and one schema line. Smaller than the assignment's row, by root's no-producer rule.
- Wrong in v1, corrected: the ref-index claim (§1.3); ordinals assigned per inner call (duplicates); an unchecked
  rseek; a missed reader (`recorded-evaluation`); consumerless facts and a hand-listed history projection.
- Awkward: refresh and generated rows stay without origin until S4; the readers' `not= :model` reads absence as
  not-model, which is correct for every surviving consumer (§1.2).

## 8. Disposable resources and limits

- Agent `s3-hist` (branch `:agent-6568a525c62a`) retired: `my.agent/retire-branch!` first refused ("still has a
  connection in this process", the `7116117e6aaf` class), then answered `:my.agent/retired? true` after
  `seon.cluster.host/retire!` released the hosted connection. `s3-prep` is the lane's own agent; its branch keeps
  `scratch.s3w1`/`scratch.s3w2` namespace rows, and its assigned namespace was restored to `user`.
- My cold numbers are one first read after one restart; the reviewers' cold numbers are private node caches with a
  warm OS cache. Store bytes per node were not measured.
- RESET NEEDED: no.
