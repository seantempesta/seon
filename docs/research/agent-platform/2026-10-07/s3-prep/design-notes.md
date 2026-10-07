---
type: design
status: gate 1 (design only; no src/test edits)
created: 2026-10-07
lane: s3-prep
---

# S3: in-place preparations (lane s3-prep, 2026-10-07)

What exists: every entrant already mints its pass id before evaluation and derives evaluation ids as pass id +
position (`seon.id/evaluation`). Ordering, origin, opening and delivery facts do not exist. Data flow: entrant →
`receipt-row` (one row producer) → settlement `db.fn/call` (`receipt-settle-batch-call`, `record-evaluated-call`).
Principal approach: two new evaluation attributes written at those two producers, ordinals derived inside the
existing writer calls from an index (no counter), and one narrow ordered read in `seon.db`. Nothing reads the new
facts until S4 except tests.

Line numbers are HEAD `ec5f13d2f` (= default's `0c2a12900` for `src/`); `src/seon/turn.clj` and others carry
uncommitted s5a-reply edits in the shared tree, so citations were read from `git show HEAD:…`.

## 0. Broken things first

1. **JVM-mode MCP drops a form that fails to read, and every form after it, silently.** `(+ 1 1)` /
   `(vector ::nope.alias/k)` / `(+ 2 2)` in JVM mode answers only `2`; SCI mode answers `2`, the read error, `4`.
   A variant answered only "The run was evaluated but not recorded." (`:seon.batch/tx-count 0`). Task created:
   `seon.turn.batch/a-jvm-read-error-drops-the-form-and-every-later-form` (`e333444a896c`). It cost this lane four
   probe rounds before I saw it.
2. **A pulled agent namespace prints as one escaped string of a whole namespace render** (10,680 value tokens,
   other agents' evaluations inside). Task created: `seon.render/a-pulled-agent-namespace-prints-as-one-escaped-string`
   (`61e6f8134119`).
3. Default is `:faulted` (pid 75294, restarted by root at 11:57 during this lane): open faults
   `seon.turn.world/record-fault` (task `3d2fb1b41dc8`), `seon.turn/show-row` → `shown-failure` nil (existing
   `7f5dfb6ad7a2`), `seon.repl/entity-emission` (8 linked tasks); 411 platform threads after boot.
4. **r-record §2 is wrong that `:seon.eval/{value-tokens shown-tokens}` have no reader.** `seon.repl/findings`
   validates the stored emission against `:seon.config.eval/bounds` (`resources/seon/schemas/seon.config.eval.edn:14-18`),
   which reads `value-tokens` and `shown-tokens` back to produce `:large-value`. A key-name `rg` cannot see a
   schema-driven reader. They cannot be deleted as a free offset in S3.
5. **The data-modeling skill is stale against the approved spec.** `.agents/skills/data-modeling/SKILL.md` says
   "Do not add an author-kind stamp … System turns store generated evaluations in the same family"; spec §4.4 rules
   an origin enum. S3's landing corrects that paragraph (high priority per CLAUDE.md "a stale skill is a defect").
6. **File ownership collision.** Every S3 code file except `seon.db`/schemas is held by lane s5a-reply
   (`bin/lane status`: `src/seon/turn.clj`, `src/seon/turn/batch.clj`, `src/seon/mcp.clj`,
   `src/seon/cluster/agent.clj`, `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.agent.edn`,
   `resources/seon/schemas/seon.turn.edn`). S3 code starts after s5a-reply releases them, or root splits hunks by
   patch. This is a scheduling dependency, not a design one.

## 1. What exists (traced)

### 1.1 Pass ids and evaluation ids: already true

| Entrant | Pass id minted before eval | Where |
|---|---|---|
| MCP SCI (`evaluate-source!` → `submit-source!`) | `(id/id)` before the request is offered | `src/seon/cluster/agent.clj:754` |
| MCP JVM (`jvm-run`) | `(id/id)` before `evaluate-batch` | `src/seon/mcp.clj:262` |
| model turn | `next-id` at `open-turn`, before the provider call | `src/seon/turn.clj:519`, `:4320` |
| system refresh | `next-id` | `turn.clj:2063` |
| test effect attempt | `"attempt-<id>-turn"` | `src/seon/test.clj:184` |

Evaluation id = `(seon.id/evaluation pass-id position)` (`src/seon/id.clj:278`) via `seon.turn/receipt-identity`
(`turn.clj:534`); effects already use the same identity (`src/seon/effect.clj:225`). **S3 changes nothing here.**
`next-id` is a count query over the agent's turns (O(turns)); its two callers are deleted in S4/S6.

### 1.2 Origin today

No origin fact. `:seon.cluster.eval/author` (`:agent`/`:system`) is written by `receipt-row` (`turn.clj:904`),
the one row producer, from the request each caller builds:

| Caller | author today | origin S3 writes |
|---|---|---|
| `plan-call` via `plan-tx-for-author` (`turn.clj:490`, `:642`), model replies | `:agent` | `:model` |
| `system-run-plan` → `source-rows` (`turn.clj:721`, `:604`), every submission: MCP SCI, `virtual-turn!` (web/fixtures), `script/seon/dev/settlement_probe.clj` | `:system` | `:outside` |
| `record-evaluated-call` (`turn.clj:1296`) for MCP JVM (`jvm-request`, `turn/batch.clj:890` → `evaluation-record` `:611`) | `:system` (stamped at the writer) | `:outside` (from the evaluation record) |
| `record-evaluated-call` for the refresh (`record-evaluated-tx`) and `append-generated-call` (`turn.clj:798`, the `:generate` seed) | `:system` | none: producers deleted in S4 |

`:seon.eval/origin` is a different, older fact ("the entity whose declaration generated this evaluation", written
only by the refresh; `resources/seon/schemas/seon.eval.edn:1`); it dies with the refresh in S4. The new key is
`:seon.cluster.eval/origin`, absent from the live schema (reviews' probes).

Author readers in `src/`: `latest-evaluations` (`turn.clj:1815`, refresh), the disposition check (`:3368`,
current run's last form `:agent`), `generated-read-fault` gate (`:4985`, refresh). Tests: 25 sites in 9 files
(`rg cluster.eval/author test`).

### 1.3 Order today

`seon.eval/of-agent` sorts by `[run :seon.turn/opened-tx, ordinal]` through `run → :seon.turn/agent`. Neither
`:seon.cluster.eval/run` nor `:seon.turn/agent` is avet-indexed (live schema: no `:db/index`; Datahike indexes avet
only for `:db/index`, `datahike/src/datahike/db/utils.cljc:40-42`), so the join scans every evaluation on the
branch, inherited ones included.

### 1.4 Writers where an ordinal can be assigned

Every recorded evaluation of the SCI and model paths goes through `receipt-settle-batch-call` (`turn.clj:963`):
the batch settlement (`settle-batch!` `:3541`) and the refusal settlement (`settle-batch-refusal!` `:3851`, `:3855`).
The single-form arms use `receipt-settle-tx` (`:3424`, `:3679`), the same `receipt-settle-call` wrapped singly.
The JVM entrant and the refresh record through `record-evaluated-call` (`:1296`), itself a `db.fn/call`. Datahike
hands each `:db.fn/call` the in-flight value after the ops before it
(`datahike/src/datahike/db/transaction.cljc:887`, `:1172`, `in-transaction-db` `:1055`), and the writer serializes
transactions, so an ordinal derived there is race-free across unserialized JVM and SCI passes.

### 1.5 Assignment and revision

`assign!` writes `{:seon.task/assignment (id/id [handle agent-id branch]) :seon.task/revision (task-revision …)}` at
the task home (`src/seon/task.clj:426-453`, HEAD). Revision = `id/digest 64` of the declaration (`:208-216`). An
unchanged reassignment keeps the id; a changed declaration changes the revision. The pair is therefore exactly the
spec's opening key.

### 1.6 Input identities

Messages, errors and test runs each have a string identity (`:seon.message/id`, `:seon.error/id`,
`:seon.test.run/id`). Nothing delivers input as a pass today: the refresh reads the inbox. Producer arrives in S4
(messages, errors) and S7 (proofs).

## 2. Measured: the ordered history read

Population: agent `s3-hist`, 206 evaluations (205 MCP SCI passes, each a ~2 KB shown string; built through
`seon.mcp/call`), on its own branch `:agent-6568a525c62a`. Text = source + shown: 1 → 11 B, 10 → 18.3 KB,
200 → 405 KB. Allocation is `getThreadAllocatedBytes` of the evaluating thread; JVM-mode `let` forms, reads only.
The proposed index was simulated with `d/with` on the branch value (an in-memory `[agent-id ordinal]` tuple with
`:db/index`); everything else read the stored value.

| Read (warm unless marked) | N=1 | N=10 | N=200 |
|---|---|---|---|
| today's narrow: index query + `d/pull-many` (r-context's shape) | 0.98 ms / 881 KB | 1.1 ms / 953 KB | 2.6 ms / 2.7 MB |
| r-context, root's 1,387-row branch, same shape | | | 8 ms / 17 MB |
| `seon.db/pull-many` (Seon wrapper), 3 attrs | | | 3.4-3.9 ms / 7.8 MB |
| `seon.db/datoms :eavt e` per entity (wrapper) | | | 4.2 ms / 5.8 MB (first: 19.8 ms / 12 MB) |
| **proposed seek**: `d/index-range` on the tuple (with-db) | 0.05 ms / 1 KB | 0.005 ms / 2 KB | 0.014 ms / 7 KB |
| **proposed fetch**: one raw `d/datoms :eavt e` walk per entity, stored value | 0.01 ms / 22 KB | 0.03 ms / 79-81 KB | 0.6-0.7 ms / 1.3 MB (3.2× text) |
| alternative fetch: `seek-datoms :aevt` per attribute over the eid span | 0.2 ms / 394 KB | 0.24 ms / 398 KB | 0.2-0.8 ms / 425-467 KB (1.05-1.15×) |
| writer: last ordinal by `d/rseek-datoms` avet (with-db) | 42 µs / 1 KB; absent agent 18 µs | | |
| **cold, first read of the branch after the JVM restart**: index query (loads those entities' eavt leaves) | | | 3.6 ms / 4.0 MB |
| cold: aevt ranges | | | 6.4 ms / 13.4 MB |
| cold: eavt walk after the eavt leaves were loaded | | | 0.7 ms / 1.3 MB |

Reading:
- The wrapper, not Datahike, is most of r-context's 17 MB: `seon.db/pull-many` is 6× raw pull at 200, and the
  raw index join pays for every inherited evaluation on the branch.
- **Warm**, the proposed read is 0.7 ms / 1.3 MB at 200: 3.2× the text, against the append row's ≤ 3×. The eavt
  walk is chosen over aevt ranges although aevt is 1.1× warm: aevt costs 13.4 MB cold, has a ~400 KB floor, scans
  other agents' rows inside the eid span on a shared branch (root's agents on default), and needs one seek per
  attribute; one eavt run per entity carries every field S4 needs (source, shown, comment, error, diagnostics,
  output, ns) at no extra cost.
- **Cold**, the read costs about 5 MB at 200 (4.0 MB leaf decoding + 1.3 MB walk; ~13× text). That is Datahike
  decoding (LZ4 + Fressian) the index leaves that hold the 2 KB strings, once, into its own node cache: work
  proportional to the shown text, done once per JVM per branch. Below that needs history text outside the index
  (it already goes to blobs above the blob threshold), not worth it. Proposed budget for the §3 "cold history
  acquisition" row: at 200 exchanges < 10 ms and < 4× text warm, < 15× text on a first read after restart,
  reported separately as acquisition. UNVERIFIED: the cold seek on a stored tuple index (no stored attribute
  yet); expected 1-2 leaves for 200 tuples.
- 1 and 10 exchanges are far under every bound (≤ 0.04 ms, ≤ 81 KB).

The append row (< 10 ms, ≤ 3× appended text) is S4's proc-held transcript and is not exercised by S3.

## 3. Duplicates and library seams

- No second id scheme: `seon.id/evaluation` and the pass id stay. No counter beside the index: the last ordinal is
  `d/rseek-datoms` on the avet index the reader needs anyway (a `:seon.agent/last-ordinal` counter would be a
  mirror of that max that must never drift).
- One row producer (`receipt-row`) and two writer calls already exist; S3 adds data to them, no new writer.
- Library seams used: Datahike heterogeneous tuples with `:db/index` (bridge: `src/seon/schema/datahike.clj:147-150`,
  `:199`, `:203`), `index-range`, `rseek-datoms`, `datoms :eavt`, in-transaction values for `:db.fn/call`.
  Composite `:db/tupleAttrs` is not bridged and not needed.

## 4. The smallest composition

### 4.1 Schema (`resources/seon/schemas/seon.cluster.eval.edn`, adopted on a live-store branch)

```clojure
:origin [:enum {:description "Which entrant's pass recorded this evaluation: :opening, :input and :model are the
                 agent's own context; :outside (MCP SCI and JVM) and :test are not shown to it."}
         :opening :input :model :outside :test]
:agent-ordinal [:tuple {:seon.db/index true :description "The agent and its evaluation's position in the agent's
                 history, assigned by the writer at settlement."} :seon.agent/id [:int {:min 1}]]
```

Both optional on `:seon.cluster.eval/receipt` (old rows keep neither; S4's reset makes origin required).
`:seon.cluster.eval/author` stays declared, optional, for old rows until S4 deletes it with its readers.

### 4.2 Origin replaces author at the producers (BEHAVIOR CHANGE: author stops being written)

- `receipt-row` writes `:seon.cluster.eval/origin` from the request instead of `author`; `plan-call`'s contract
  takes origin (`:model`); `system-run-plan` passes `:outside`; `evaluation-record` adds `:outside`;
  `record-evaluated-call` stops stamping `:system` and keeps the record's origin (absent for the refresh).
- The three `src` readers read origin: `:agent` ⇔ `:model`, `:system` ⇔ anything else including absent. On old rows
  this misreads only model rows as system, which none of the three conditions can then match (they need
  `:seon.repl/changes` shown, a `:generate` run, or the current run). Tests asserting author convert in the same
  commit (25 sites in 9 files, `datahike_parity.edn` included).
- Why replace rather than add: an added attribute costs one more aevt index path per commit (~4 KB store,
  r-record §4) and the assignment says "replacing". The smaller alternative (write both, delete author in S4)
  is listed in §5.

### 4.3 Writer-assigned ordinals

One private pure helper in `seon.turn` (contracted):

```clojure
(defn- agent-ordinals-tx
  "Assertions numbering `evaluation-ids` after `agent-id`'s last ordinal in `db`, in order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:vector :seon.cluster.eval/id]] :seon.store/transaction-data]}
  [db agent-id evaluation-ids] …)
```

It reads the last ordinal through `seon.db` (a new `seon.db/last-agent-ordinal`, the rseek above) and returns
`{:seon.cluster.eval/id id :seon.cluster.eval/agent-ordinal [agent-id n]}` upserts. Called at the end of
`receipt-settle-batch-call` (batch and refusal arms), of `receipt-settle-call` when called singly through
`receipt-settle-tx`, and of `record-evaluated-call`. Every recorded evaluation gets one; started-but-never-settled
rows (interrupted intent rows, `recover-tx`) get none, so S4's history holds exactly the recorded exchanges.
Cost: one rseek per pass (~42 µs, 1 KB) plus one datom per evaluation.

### 4.4 The narrow ordered read (in `seon.db`, the only `datahike.api` owner)

```clojure
(defn agent-exchanges
  "The agent's recorded evaluations whose origin is in `origins`, in agent-ordinal order, as plain maps."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:set :seon.cluster.eval/origin]] [:vector :seon.cluster.eval/exchange]]}
  [db agent-id origins] …)
```

`index-range` on `:seon.cluster.eval/agent-ordinal` from `[agent-id 1]`, then one raw `d/datoms :eavt e` per entity
keeping `id ordinal origin source shown comment error diagnostics output ns ending-ns`; namespace refs resolved once
per distinct ns. No read evidence (its only consumer, the refresh, is deleted in S4; this read runs at proc start,
not inside an agent evaluation). `:seon.cluster.eval/exchange` is registered in the same schema file. S4 calls it
with `#{:opening :input :model}`, the same predicate its append uses.

### 4.5 Opening fact and delivery identities: no producer until S4

The spec row puts them in S3, but no entrant today runs an opening or input pass. The choice is root's (§5,
options B1/B2). Recommended (B1): S3 lands only the schema and the writer-side checks that S4's passes will need,
reached by tests through the submission entrance:

- `:seon.agent/opening [:tuple :seon.task/assignment :seon.task/revision]`, cardinality one on the agent, written by
  the opening pass's settlement in the same transaction as its evaluations (on the agent's branch). An opening is
  due when the agent's current `[assignment revision]` (read at the task home) differs from it. A new assignment
  id or a changed declaration differs; an unchanged reassignment does not; a crash before the settlement leaves it
  unchanged, so the opening reruns. History answers earlier openings. Agent information lives on the agent; the
  alternative is an indexed tuple on the pass row (§5).
- `:seon.cluster.eval/delivered [:set {:seon.db/index true} [:tuple :qualified-keyword :string]]`: lookup-shaped
  identities (`[:seon.message/id "…"]`), set on an `:input` evaluation from what the input form actually read. The
  writer refuses an identity already delivered to this agent (avet lookup of the tuple joined to the agent's
  ordinals) and a branch-local identity (a message on the agent's branch) that does not exist in the in-flight
  value. Task-home items carry `:seon.cluster.eval/input-basis` `[:tuple :keyword :uuid]` (home branch, commit id);
  existence at the home is the producer's read at that basis, not re-read by the agent-branch writer.
- The submission request (`:seon.agent/source-submission-request`) gains optional `:seon.cluster.eval/origin`,
  `:seon.agent/opening` and per-form delivered identities, so S4's opening and input passes are ordinary
  submissions through the proc and the fold (spec §4.4) and MCP passes `:outside` explicitly.

What disappears: author writes (§4.2). What is added: two evaluation attributes, one agent attribute (B1), one
helper, one `seon.db` read, request keys. What it lets S4 delete: `of-agent` on the per-pass path, the run/opened-tx
order join, the `:system` conflation.

### 4.6 The one happy path

Serves RECORD (origin, ordinal and delivered facts in the pass's one transaction) and context acquisition (the one
ordered read at proc start). The owners already exist (`receipt-row`, the two writer calls, `seon.db`); the
library seam is Datahike's indexed tuple. It lets S4 delete the refresh's history derivation and `of-agent` +
`history-selector` from the model path.

## 5. Alternatives (priced)

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A1 (rec.)** origin replaces author now | one origin fact per new row | 25 test sites converted; s5a-held files | old-row author readers read origin (§4.2) |
| A2 add origin beside author; delete author in S4 | no test churn now | +1 attribute path per commit (~4 KB store, ~3%) | worsens S0 store bytes, which blocks landing under the budget rule unless root waives |
| **ordinal from the index (rec.)** | no mirror | one rseek per pass | none found |
| `:seon.agent/last-ordinal` counter (as assigned) | O(1) read | +1 agent datom and one more attribute path per pass; a drift-checkable mirror | the spec names it; I recommend amending the spec wording to "agent ordinals assigned at the writer" |
| eavt walk (rec.) vs aevt ranges | all fields, one index | 3.2× text warm vs 1.1× | 13 MB cold and span scans for aevt |
| **B1 (rec.)** opening/delivery schema + writer checks + request keys in S3, tested through submissions | S4 is code on adopted schema | ~1 test file, ~60 src lines with no production producer until S4 | facts with no consumer for one slice |
| B2 move opening and delivery to S4 with their producers | no dead path | S4 grows; its schema adopted at its reset | spec row amended |
| opening fact on the pass row instead of the agent | fact sits literally beside the evaluations | needs an indexed tuple and a query | agent information off the agent |

## 6. Risks and the tests that reach them

| Risk | Test (new, one behavior each, asserting data) |
|---|---|
| an entrant writes the wrong or no origin | table over MCP SCI, MCP JVM and a model pass (fixture provider): each pass's evaluations carry `:outside`/`:outside`/`:model` |
| ordinals collide or skip across unserialized JVM and SCI passes, or a refused settlement drops them | two SCI passes, one JVM pass and one refused settlement on one agent: ordinals are 1..k contiguous in commit order |
| the read returns other agents' rows, outside rows or wrong order | `seon.db/agent-exchanges` with `#{:model}` after mixed passes returns only the agent's model rows in ordinal order |
| tuple index not bridged or not adopted | `bin/lane commit … --test`'s publish-and-adopt of the schema on a live-store branch; `contracts_compile_test` |
| opening key (B1): unchanged reassignment reopens, or a revised task does not | table: same assignment+revision → not due; new revision → due; new assignment → due |
| delivery (B1): a restart re-delivers, or an unread item is marked | second input pass with the same identity is refused by name; an identity absent from the branch is refused |
| author conversion breaks a refresh or disposition path | existing `seon.turn-test`, `seon.cluster.turn-test`, `seon.turn-loop-test` author-asserting tests, converted |

Existing tests reaching the changed writers: `seon.cluster.evaluate-source-test`, `seon.turn-test`,
`seon.cluster.turn-test`, `seon.mcp-entrance-test` (they exist; which fit the 120 s check is §9).

## 7. Expected S0 numbers after

Ordinary pass (MCP SCI/JVM, 55/41 ms, 76/54 MB, 1 commit): +0.05-0.1 ms and < 0.1 MB for the rseek and the extra
datoms; author → origin is attribute-neutral; agent-ordinal adds one aevt and one avet path: about +8 KB store per
commit (~6%). That worsens store bytes, which the budget rule says blocks the slice; root must rule (accept as the
price of the history index, or require an offset). Option A2 or the counter would add more. Model pass: same
deltas, inside today's 7 commits. History acquisition rows: §2 (new rows, not in S0 today).

## 8. Claims the slice will need (proof before landing)

1. Every new evaluation carries exactly one origin from its entrant; old rows unchanged.
2. Ordinals are contiguous per agent in writer order, for all settlement arms.
3. `seon.db/agent-exchanges` at 1/10/200 matches §2 on a stored tuple index (cold and warm), added to S0.
4. The schema publishes and adopts on a live-store branch (the check's archive) with zero lint findings.
5. (B1) Opening due-ness and delivery refusals as in §6.

## 9. UNVERIFIED

- The stored (not `d/with`) cost of the tuple seek, cold and warm.
- Whether `seon.db`'s transact validation accepts a lookup-shaped tuple inside `:set` with `:seon.db/index` (bridge
  read says yes; not transacted).
- Where errors "delivered to this agent" live (agent branch or task home) and the exact input-form read; S4 owns it.
- Per-commit store delta of the new attributes (estimated from r-record's ~4 KB per attribute path, not measured).
- Which test namespaces fit the 120 s commit check.

## 10. Design check

- Held against the code: pass ids and evaluation ids already satisfy the spec; S3 is smaller than the row reads.
  `receipt-row` is the single place origin belongs; both settlement writers are `db.fn/call`s on in-flight values.
- Wrong in the assignment as written: the `:seon.agent/last-ordinal` counter duplicates the index S4 needs; the
  opening and delivery facts have no S3 producer.
- Awkward: r-context's 17 MB is mostly the `seon.db` wrapper and the unindexed join, so the fix is the index and a
  raw read in `seon.db`; cold acquisition is Datahike leaf decoding and cannot reach 3× without moving text out of
  the index.

## 11. Disposable resources and limits

- Agent `s3-hist` (branch `:agent-6568a525c62a`, 206 evaluations) is retired: `my.agent/retire-branch!` first refused ("still has a connection in this process", existing task `7116117e6aaf` class), then answered `:my.agent/retired? true` after `seon.cluster.host/retire!` released the hosted connection; `s3-prep` is
  the lane's own agent. My agent's namespace was moved to `scratch.s3w1`/`s3w2` and restored to `user` (tx
  536871351); `scratch.s3w1`, `scratch.s3w2` namespace rows remain on my branch.
- JVM-mode host Vars defined in `user`/`s3prep.measure` before root's restart are gone with that JVM; after root's
  reminder, JVM mode was used only for single `let` reads.
- Single runs; cold numbers are one first read after one restart.
- RESET NEEDED: no.
