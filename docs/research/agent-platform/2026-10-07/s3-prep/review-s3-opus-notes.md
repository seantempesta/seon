---
type: review
status: complete
created: 2026-10-07
lane: review-s3-opus
reviews: docs/research/agent-platform/2026-10-07/s3-prep/design-notes.md
---

# Review of S3 (in-place preparations), fresh-context Opus

Read against root's decisions: (1) origin replaces author now, (2) ordinals derived at the writer from the indexed
`[agent-id ordinal]` tuple with no counter, (3) B2: opening and delivery identities move to S4, (4) the store cost is
accepted. Citations are HEAD `30bff7ae7` (`git show HEAD:…`). The s5a-reply working tree rewrites some of the cited
lines.

Verdict: **the design holds once two writer-correctness defects are fixed (H1, H2) and decision (3) is applied to
everything in S3 that has no producer (M2).** The owners are right: `receipt-row`, the two settlement writer calls,
`seon.db`, and Datahike's indexed tuple. The stored-index seek is as cheap as claimed when warm. Cold, it costs more
than "1-2 leaves" (M3).

## Measurements: the tuple seek on a STORED index (the §9 unknown)

Setup: agent `review-s3-opus` in SCI mode. I created branch `:s3rev-tuple` of the live store with
`my.agent/create-branch!` at basis 536871363, which inherits default's full avet. On it I transacted the raw
attribute `{:db/valueType :db.type/tuple :db/tupleTypes [:db.type/string :db.type/long] :db/index true}` and wrote:

- 20 other agents × 200 tuples, in 20 commits;
- 200 single commits of agent `"target"` (each with 2 KB of text);
- 200 single commits of agent `"zz-late"`.

The store's index config is `{:branching-factor 128, :diff-buf-size 32}`, and the avet root is a level-2 Branch with
31 children.

Allocation is `getCurrentThreadAllocatedBytes` measured on a platform thread started from SCI. SCI's own thread is
virtual and answers -1 (existing task `seon.batch/allocation-is-unmeasured-on-a-virtual-thread`, `ae0364590441`).

For cold reads, I gave the index a fresh `CachedStorage` with no node-cache key: a bare copy of the root Branch with
its keys and addresses but no children, so every node below the root is restored from konserve. A cold read is
`(datahike.index.persistent-set/create-storage (dissoc store di/node-cache-key) config)`. Re-connecting is not cold:
the node cache is shared by every connection to one physical store (`persistent_set.cljc:485-492`).

| Read | N=1 | N=10 | N=200 |
|---|---|---|---|
| warm `d/index-range` `[aid 1]..[aid n]`, stored value (mean of 500) | 4.7 µs / 7.7 KB | 6.0 µs / 7.7 KB | 8.8 µs / 11.8 KB |
| warm last ordinal (`rseek-datoms` + checks) | 3.2 µs / 4.3 KB | | |
| **cold** seek, nodes restored from konserve | 0.28 ms / 0.72 MB (2 node reads) | 0.21 ms / 0.72 MB (2) | 0.38-0.68 ms / 1.47 MB (4), 3 runs |
| **cold** last ordinal | 0.25-0.29 ms / 0.80 MB (2 node reads); absent agent 0.26 ms / 0.77 MB | | |
| one cold node restore over default's avet (median of 31 L1 / 40 leaves) | L1 0.11 ms / 418 KB; leaf 0.08 ms / 338 KB (64 datoms) | | |

- The warm numbers confirm §2's `d/with` simulation (0.014 ms / 7 KB at 200).
- The cold seek at 200 is 4 node restores (≈1.5 MB), not "1-2 leaves".
- The bare copy drops the root's diff buffer: the cold seek counted 199 of 200 because one datom of the last commit
  sits in the root Branch's buffer. On a real read the root comes from the commit, so this is the flushed-node cost
  only.

Store, as a proxy: across 40 commits each, the tuple raised node writes per commit from 5.85-5.95 to 6.95 (+1.05
nodes, `CachedStorage` `:writes`). Bytes per node were not measured, so §7's "+8 KB" remains an estimate.

The branch is retired (`:my.agent/retired? true`) and both connections are released.

## Findings, ranked

### H1. `rseek-datoms` crosses attribute and agent boundaries; a naive last-ordinal corrupts or throws in the writer

`d/rseek-datoms db :avet attr [aid Long/MAX_VALUE]` returns the nearest datom at or below that point in the whole
avet. It does not stop at `attr` or at `aid`. Measured on the stored branch:

- `"target"` → `[:s3rev/agent-ordinal ["target" 200]]` (correct).
- An absent agent `"zzz"` that sorts after every other agent → `["zz-late" 200]`. A naive helper starts `"zzz"` at
  **201**, silently.
- An absent agent `"absent"` that sorts before every other agent → `[:db/ident :seon.wake/opens-turn?]`, a datom of
  **another attribute**. A naive `(first v)` threw `Don't know how to create ISeq from: clojure.lang.Keyword` in my
  probe. Inside `receipt-settle-batch-call` that exception refuses the settlement of an agent's **first** recorded
  pass.

§2's "absent agent 18 µs" row does not say whether the result was checked. §4.3 does not state the check.

Required changes:

- The helper accepts the datom only when `(= attr (:a d))` and `(= agent-id (first (:v d)))`. Otherwise the ordinal
  starts at 1.
- The §6 ordinal test needs agent ids that sort both before and after every other agent's on the branch.
- Consider `:seon.db/unique true` (`:db.unique/value`, which also implies avet) instead of `:seon.db/index true`.
  A duplicate ordinal then refuses loudly at the writer rather than persisting. This is the "contract at the
  producer" for this bug class. Tuple uniqueness was not probed.

`index-range` with `[aid 1]..[aid n]` bounds was exact: 200 for target, 200 for each other agent, 0 for absent
agents.

### H2. §4.3 assigns ordinals inside `receipt-settle-call` "when called singly", which duplicates them in a batch

`receipt-settle-batch-call` (`turn.clj:963-973`) maps `receipt-settle-call` over all requests with **one** in-flight
value, so a last-ordinal read inside `receipt-settle-call` would give every form of a batch the same `n+1`. The
distinction "called singly" is not visible inside the function.

Assign exactly once per writer call, over the ordered evaluation ids:

- In `receipt-settle-batch-call` after its `mapcat`.
- In the single arm by its tx builder (`receipt-settle-tx`), or as one appended
  `[:db.fn/call #'agent-ordinals-call agent-id ids]` op. Datahike processes a call's returned ops before the
  following ops (`transaction.cljc:1312`, `:1345`: `(concat new-entities entities)`), and the next call reads the
  in-flight value (`:1172`, `in-transaction-db` `:1055`). A later op therefore sees earlier assignments.
- `record-evaluated-call`: only in its write branch. Its idempotent re-record branch returns `[]` (`:1348-1351`) and
  must stay `[]`.

The batch call also needs the agent id: one pull of run → `:seon.turn/agent` → `:seon.agent/id`. §4.3's signature
takes `agent-id` but does not say where the batch call gets it.

### M1. A missed author site: the duplicate-recording comparison

`recorded-evaluation` (`turn.clj:1243-1257`) selects `:seon.cluster.eval/author` for `record-evaluated-call`'s
identical-content check (`stored-record-content`, `:1349`). §1.2 lists three readers and misses this one. Under
decision (1):

- It selects `:seon.cluster.eval/origin`.
- It must **not** select `agent-ordinal`. The ordinal is writer-assigned, so a re-record request never carries it.

Other sites the conversion touches beyond §4.2's list:

- the contracts at `:654-655` (`plan-call`), `:896-897` (`receipt-start-tx`) and `:941-942` (`receipt-start-call`);
- `append-generated-call`'s `:system` (`:834`);
- the description of `:seon.cluster.eval/author` in `seon.cluster.eval.edn`;
- stale text that must be corrected in the same commit: `docs/seon/schedule/README.md:509` ("author -> origin (loop
  S1/S5)") and `docs/seon/issues/scratch-root-has-agent-errors-without-attempt-facts.md`;
- the data-modeling skill paragraph (§0.5, confirmed at `SKILL.md:182`).

The test count is confirmed as 25 sites in 9 files (`git grep -c`).

### M2. Apply decision (3) to everything in S3 with no producer or consumer

Root ruled "no schema without a writer". By the same rule:

- **Enum members** `:opening`, `:input` and `:test` have no writer in S3. Today the test harness's attempt agent
  opens a turn (`test.clj:184-200`) and records no evaluations. S3 declares `[:enum :model :outside]`, and S4 or S7
  add the rest by accretion.
- **`seon.db/agent-exchanges` and `:seon.cluster.eval/exchange` (§4.4)** have no consumer until S4's proc start.
  They are a read with no caller, against "a feature no agent turn needs is refused". Land them with S4's
  acquisition and keep §2's numbers as its budget evidence. Claim §8.3 then becomes an S4 claim.
- **The ordinal itself.** Its only S3 reader is a test. Spec §6 makes S4 a reset point, so S3-era ordinals are
  discarded. Keeping the writer in S3 is defensible because it shrinks S4's diff and is reviewed with the other
  writer change. **Question for the owner:** is that worth an attribute whose rows are reset before any production
  read?

### M3. The proposed cold budget does not hold once the seek's measured cold cost is added

§2's proposed budget is "< 15× text on a first read after restart" (200 × 2 KB = 405 KB, so ≤ 6.1 MB). The design's
own cold rows are 4.0 MB of eavt leaf loading plus a 1.3 MB walk. The measured cold seek adds 1.47 MB, which totals
about 6.8 MB, or about 17× text. Either:

- set the S4 row from the measured sum; or
- measure the composed read once, cold, when it lands (S4 per M2).

Each cold node restore costs about 340-420 KB of allocation for ≤128 datoms. That is Datahike/konserve decoding and
the dominant cold cost. It deserves its own entry beside existing task `a-small-raw-commit-allocates-a-hundred-megabytes`
(`cd6c2900565a`) if S4 cannot meet its row.

### M4. The new attributes omit the family's `:seon.wake/context-inert true`

Every `:seon.cluster.eval/*` attribute carries `:seon.wake/context-inert true`. Its readers are:

- `wake/inert-attributes` (`cluster/wake.clj:144-152`);
- `walk/root-selector` (`render/walk.clj:141-145`);
- the refresh's generated-read check (`turn.clj:1961-2012`).

All three are deleted in S4 (walk-delete, refresh). Until then, origin and agent-ordinal are the only evaluation
attributes treated as context-bearing. Declare the property for consistency, or state why it is not needed.

### M5. File and hunk overlap with s5a-reply is real, not only a scheduling note

`bin/lane status` shows s5a-reply holding `src/seon/turn.clj`, `turn/batch.clj`, `mcp.clj`, `render/transcript.clj`,
`test/seon/cluster/turn_test.clj` (5 author sites) and `test/seon/schema/datahike_parity.edn` (1 author site). Its
working-tree diff rewrites exactly S3's seams:

- the batch settle arm (`(into (receipt-settle-batch-tx receipts))`);
- the single-ordinal arm (`receipt-tx (when settled (receipt-settle-tx settled))`);
- `evaluation-record` (moved).

S3's code must be written against s5a's landed shapes, and its `file:line` references will be stale.

S2a's ledger notes say the virtual/provider classification "becomes origin-derived after S3"
(`s2a-views/design-notes.md:250`). That is a consumer of origin in the page, and it needs `:outside` and `:model`
only, so it is consistent with M2's two-member enum.

### Low

- L1. The citation `transaction.cljc:887` is `flush-tuples`. The claim (in-flight value per `:db.fn/call`) is
  supported by `:1055` and `:1172`, plus the queue order at `:1312`/`:1345`.
- L2. avet holds `:db/unique` attributes as well as `:db/index` ones (`db/utils.cljc:40-42` reads the attrs-by
  `:db/index` set, which includes unique attributes). This is harmless to the argument.
- L3. S3's proof claims (§8) must measure allocation on a platform thread. In SCI mode the evaluation thread is
  virtual and answers -1. Note also that `seon.sci.kernel/allocated-bytes` (`kernel.clj:47-50`) does not guard -1,
  so `(- -1 -1)` reports **0 bytes** for SCI evaluations rather than unknown. That is the same class as task
  `ae0364590441`; link it there.
- L4. The bridge was not probed: `[:tuple {:seon.db/index true} :seon.agent/id [:int {:min 1}]]` → `:db/tupleTypes`.
  `attribute-declaration` cannot be driven from SCI with a SCI-built registry: the host `m/schema?` refuses it and it
  answers "no registered schema". The commit check's publish-and-adopt remains the proof, as §6 says. The analogous
  stored tuple `:seon.cluster.eval/executed` resolves a registry reference inside a tuple today.

## Claims checked

| Claim | Verdict | Evidence |
|---|---|---|
| Pass ids minted before eval; evaluation id = pass id + position | supported | `mcp.clj:262` `(id/id)`; `turn.clj:534` `receipt-identity` |
| author written by `receipt-row` from caller requests (`:agent` plan, `:system` submissions/JVM/generated) | supported, but readers incomplete | `:493`, `:630`, `:834`, `:923`, `:1323`; missed reader `:1252` (M1) |
| three `src` author readers | contradicted (four) | `:1815`, `:3368`, `:4985` + `recorded-evaluation` `:1252` |
| 25 test sites / 9 files | supported | `git grep -c` at HEAD |
| `run`/`turn/agent` not avet-indexed | supported | schema has no `:db/index`; `transaction.cljc:445-462` |
| `:db.fn/call` reads the in-flight value; serialized writer makes ordinals race-free | supported | `transaction.cljc:1055`, `:1172`, `:1312`, `:1345` |
| one assignment point per writer call | contradicted as written (H2) | `receipt-settle-batch-call` `:963-973` |
| rseek gives the last ordinal | contradicted without bounds checks (H1) | stored-branch probe above |
| stored warm seek ≈ `d/with` numbers | supported | table above |
| cold seek 1-2 leaves | contradicted: 2 node reads at 1/10, 4 at 200 | table above |
| `value-tokens`/`shown-tokens` have a schema-driven reader | supported | `seon.config.eval.edn:14-18`, `repl.clj:305` |
| JVM-mode MCP drops a read-failing form and every later one | supported (reproduced: answers only `2`) | task `e333444a896c` |
| `:seon.eval/origin` written only by the refresh | supported | `turn.clj:2161-2162`; read by `render/transcript.clj:1464` |

## Concrete simplifications

1. Two-member origin enum now (`:model :outside`). Accrete the rest with their producers.
2. No `agent-exchanges`/`exchange` in S3 (M2). S3 is then: two attributes, one contracted pure helper, the author→origin
   conversion, and two tests (origin per entrant; contiguous ordinals across SCI, JVM and a refusal settlement,
   including agents sorting first and last).
3. Declare the tuple `:seon.db/unique` so the duplicate class refuses at the writer (verify on a branch first).

## Questions for the owner

1. Should the ordinal writer land in S3 when S4's reset discards its rows and its first reader is S4 (M2)?
2. Should the cold-acquisition budget be set from the measured composed read in S4, rather than from §2's partial
   numbers (M3)?

## Tasks

- Created `seon.fn.analyzer/default-holds-no-analysis-of-datahike-and-malli` (`1151f56b1676`). On default, every
  SCI `defn` calling `datahike.api`, `datahike.datom`, `datahike.index.persistent-set`, `malli.core` or
  `malli.registry` returns `:seon.fn/dependency-analysis-missing-error` and is not admitted. It recurred on 9 of my
  forms.
- Linked: `e333444a896c` (JVM read error drops forms, reproduced), `ae0364590441` (virtual-thread allocation; add
  the kernel `-1 - -1 = 0` case), `cd6c2900565a` (commit allocation).
- Default health at review start: `:faulted`, 3 open faults, `seon.schema/call-with-projection-state` mean 16.7 s /
  max 419 s (tasks `c4ece4f4eec9`, `d27179bab996`). RESET NEEDED: no.

## Design check

The design's owners and seams survived the code. The two defects are in how the helper is called and in the
boundary semantics of a Datahike seek it had not used before. Both are local fixes, not a wrong design. My cold
measurement copies the root without its diff buffer, so it counts flushed nodes only and excludes the root commit
read. Store bytes per node were not measured.
