---
type: reference
status: measured; fact-finding and design lane, no source edits
created: 2026-09-23
tags: [agent-platform, caches, invalidation, datahike, revisions, read-paths, seconds-not-minutes]
---

# Invalidation census: derived values thrown away by unrelated writes (2026-09-23)

Lane `invalidation-census`. This is fact-finding and design only. No src, test or
resources file was edited.

The owner's instruction, 2026-09-23: "Identify all shit like this 'That row is the
~1 MB transaction that forces the next eval and page load to re-arm.' and we need
to find elegant solutions to get around this. We need the happy path to be fast and
to accumulate data that is reused."

**The class has two parts.**

1. A derived value is keyed by a coarse identity: commit id, `basis-t`, the whole
   `:cache-context`, a database value inside a key, or "the head moved". It is then
   recomputed when an unrelated write lands.
2. A read, render, probe or eval path writes. That write invalidates its own derived
   state and everyone else's.

Siblings, cited and not re-measured:

- [runtime audit](slow-assumptions-audit-runtime-2026-09-23.md), rows 2, 3, 5 and 7;
- [tests audit](slow-assumptions-audit-tests-2026-09-23.md), row 8;
- [from-zero boot cost](from-zero-boot-cost-2026-09-23.md), rows 3 and 7.

Lane `sci-program-revisions` owns the SCI re-acquisition fix (runtime audit row 2).
This note cites that fix and does not duplicate it. It does report one defect that
the fix cannot see (C1 below).

## Subject and boundary

- **Probes:** `default` PID 43581, started 2026-09-22T21:19:47Z. They used MCP
  `eval_clj` in JVM mode against the explicit root. Every write went to two branches
  I created from default's head, `:census-probe-1` and `:census-probe-2`. Both were
  released and retired at 21:41Z; the roster check afterwards found none left.
- **Writes to default's branch (my fault).** Two of my probe results went over the
  MCP window (8,464 B and 6,843 B). Each one transacted a `:seon.dev.mcp.artifact/*`
  row on `:cluster-default`, even though both were declared `read_only`. That is row
  W1 below happening to me. Every later probe kept its result under the window.
- **Default went down during the lane.** At 21:42Z `default` stopped answering
  (`boot/readiness` threw `find not supported on type: java.lang.Object`, `boot.clj:281`).
  Another lane's uncommitted `src/seon/cluster/boot.clj` edit has mtime 21:40:40Z, just
  before the failures. PID 43581 then exited and PID 64314 started at 21:42:43Z. None of
  my probes touched boot state. The one query I had not yet run (`d/q` against
  `seon.db/q`) is therefore cited from the runtime audit's T17.
- **Source.** Read at the working tree on `refactor/agent-platform`
  (HEAD `7a767af4e`) with other lanes' uncommitted hunks present. That includes the
  `sci-program-revisions` hunk in `src/seon/sci/eval.clj`. Line numbers are for that
  tree.
- **Datahike fork:** `reference-code/datahike` at `fbd1ad2d`.
- **Census method.** Two scripted `rg` passes over `src/`:
  - caches: `core.cache`, `lookup-or-miss`, `memoize`, `delay`, atoms;
  - keys: `commit-id`, `:cache-context`, `attribute-revisions`,
    `conservative-revision`, `basis-t`, `max-tx`, `identical?`.

  The passes found 155 and 389 hits. Of the 155 cache hits, 54 are
  `(delay (requiring-resolve …))` Var handles or constant delays, which hold no
  derived state. The rows below are the hits that do hold derived state, plus every
  `transact!` reached from a read, render, probe or eval path.

## What the dependency already gives (the seam)

- **Revisions per attribute.** Datahike advances an attribute's revision only when a
  commit writes that attribute
  (`reference-code/datahike/src/datahike/query.cljc:2568-2589`).
  - The changed attributes come from the report's actual datoms
    (`writing.cljc:587-602`, called at `writer.cljc:241-251`). A redundant assertion
    advances nothing. Probe P6: re-asserting an existing `:seon.ns/name` produced no
    new revision.
  - A schema-attribute or unknown commit advances the
    `:datahike.cache/conservative-revision` instead.
  - `:db/txInstant` is excluded from the changed set.
- **The comparison is O(read attributes).** Datahike's own query cache carries a
  result across commits with `source-context-unchanged?` (`query.cljc:2963-2975`).
  - Probe P7: building the 188-attribute program key took 0.15 ms.
- **The revision map is process-local.**
  - A connection opens with no revision map at all (`connector.cljc:374-381`).
  - A value materialized at another commit gets that commit id as its conservative
    revision (`versioning.cljc:69-99`, `attached-cache-context`).
  - Every key also carries `:datahike.cache/connection-id` and `generation`.
  - So revision keys carry reuse across the commits of **one connection**. They
    carry nothing across branches, restarts or roots. Probe P9: a new branch at a
    commit whose projection was already memoized re-derived it in 269 ms.
- **Datahike derives the read set.**
  - For a query: `query-dependency-plan` / `query-attribute-dependencies`
    (`query.cljc:2877`, `:2935`).
  - For a pull: `pull-dependency-plan` (`pull_api.cljc:162`).
  - A wildcard `[*]` answers `:all` (`pull_api.cljc:120`). That answer is sound,
    because Datahike has no entity schema.
- **Commit ids are durable.** A commit id names one immutable root, shared by every
  branch opened at it (`db.cljc:385`; `writing.cljc:363` `create-commit-id`).
- **Content addressing already exists in Seon.**
  - Blobs are staged by content digest (`src/seon/blob.clj:222`, `:299`).
  - Every program row carries `:seon.program/definition-digest`.
  - Each file carries `:seon.fn.file/digest`.
  - Probe P8: the `{sym → definition-digest}` map of all 4,816 function rows is one
    5.5 ms query, and hashing it takes 0.21 ms.

## How often unrelated writes land (probe P4/P5)

These counts cover default's own branch from boot (21:19:47Z) to 21:34Z, about 14.5
minutes: **151 transactions**, about 10 per minute, with one idle agent and lanes
probing.

| class | tx | share |
|---|---|---|
| data only (turns, receipts, AI attempts, context captures, read evidence, messages) | 116 | 77% |
| MCP artifact rows (`:seon.dev.mcp.artifact/*`) | 8 | 5% |
| touch a program attribute (`seon.program/program-attributes`, 188 attributes) | 27 | 18% |
| … of which only receipts writing `:seon.fn/calls` | **20** | 13% |
| … of which real program rows (fn / schema / ns) | 7 | 4.6% |

So a key on commit id misses about 10 times a minute. A key on program-attribute
revisions would miss 27 times in 151, and **20 of those 27 are receipts**. That is
defect C1.

## Census A — derived values and their keys

Columns:

- **Reads:** what the derivation actually depends on.
- **Recompute:** measured wall time (the P# names this lane's probe; T# and R# name
  the sibling audits), or an estimate.
- **Trigger:** how often an unrelated write forces a recompute.
- **Cost × freq:** the ranking figure.

| # | where (file:line) | key today | reads | recompute | trigger by unrelated write | cost × freq | state |
|---|---|---|---|---|---|---|---|
| A1 | `src/seon/sci/eval.clj:2356-2372` `acquired-database?` → `acquire!` `:2380-2412` (base-ctx rebuild) | **default (running `394b58f09`): commit id.** Working tree: `program-identity` `:2333-2353`, which keys on the revisions of `acquisition-attributes` `:2311-2327`, memoized by commit id | program partition + projection range + dial attributes + `:seon.config/cluster` + `:seon.source/commit-id` | 372–1,242 ms and up to 3.58 GB (T5–T8) | commit key: every commit (~10/min; every agent form, because `receipt-start-call` runs at `turn.clj:861`). Revision key: **still every receipt settlement that carries `:seon.fn/calls`** (C1) | about 0.4–1.2 s × every form | fix in flight (`sci-program-revisions`). **C1 defeats it for agent forms** |
| A2 | `src/seon/render/web.clj:2166-2199` `derive-page!` | `program = [@program-snapshot, source-generation]`, and the snapshot map **contains `:seon.db/db`** (probe P10: keys `:functions :namespaces :seon.db/db …`) | retained render calls + page package | full page 614–1,151 ms at an unchanged head (P11); 899–1,074 ms after a commit (T10/T11) | `changed-code?` is true whenever `acquire!` swaps a new `:seon.db/db` into the snapshot (`eval.clj:2388`, every acquisition). That throws away `::calls` and `::fragments`, so **every page re-derives every block** | about 1 s × (GET + SSE pass per watched tab per commit; the render proc did 826 passes by boot + 20 min) | **new finding** |
| A3 | `src/seon/db.clj:1119-1146` `read-evidence-current?` | per read: `index-evidence-current` runs **first** (`:1090-1106`, a `d/since (d/history db) basis` scan per read). The O(attrs) revision compare runs second. Replay runs third | each retained read's dependency plan | 130 of 193 samples of a page (T11); 0.7 ms per 435 reads by revision alone (README §7) | every page derivation and every turn-start refresh (`turn.clj:1972`), at a cost proportional to **all churn since each read's basis** | about 0.6–0.9 s × each page / turn | **order inversion; new finding** |
| A4 | same, for wildcard pulls: `db.clj:891-899` `dependency-revision`, where `:all` becomes the commit id | `(= :all attributes)` makes the revision **the commit id** | `[* …]` pulls; Datahike answers `:all` (`pull_api.cljc:120`) | a replay per read | **every commit**. Probe P12: 4,953 of 16,543 stored read evidences (30%) are `:all`, and 4,615 of them are `:pull` with `[*]` | the replay share of A3 | **new finding** |
| A5 | `src/seon/call_preparation.clj:1019-1036` `current-snapshot` + `adopt` `:531` | `checked-through-t` = **basis-t**; plans keep the row basis `newest-row-transaction` (`:296-318`, a history `max ?tx` query) | `row-attributes` + schema + suppliers | **160 ms** after one unrelated commit, then 3.9 ms (P9); **1,483 ms** on a new branch (P10) | every commit (first prepared call after it) | 160 ms × ~10/min; 1.5 s × every test / candidate branch | **new finding** |
| A6 | `src/seon/test/runner.clj:925-990` `program-digest` | no memo; the function diffs every entity touched since the source seal | `:seon.source/digest` seal + a since-diff of **all** entities since the seal, filtered to program identities | **325–421 ms** at 162 tx since the seal (P13); it grows with churn | every call: `test.clj:744`, `:954`, `:1194-1196` (per test request whose commit differs) | 0.4 s × test request, growing | **new finding** |
| A7 | `src/seon/db.clj:1227-1251` `projection-cache-key` → `carried-projection` `:1283-1301` | revisions of `schema/projection-attributes` (`schema.clj:2798`), + conservative + connection/generation (`9b8c5b405`, `ca9c40639`) | declaration rows | 137–269 ms (T1, P10) | none within a connection (P9: 0.04 ms after an unrelated write). **Misses on every new branch** (connection id) and at restart. The `as-of` arm (`:1293-1294`) **derives unmemoized** on every call | 269 ms × branch; as-of × per turn render (estimated) | fixed in-connection; the cross-branch and as-of arms remain |
| A8 | `src/seon/test/runner.clj:786-857` `reach-refresh` / `reach-cache` | one basis per projection cache; incremental by a since-diff over `reach-attributes` | `:seon.fn/calls` etc. | unmeasured; proportional to changes since the last basis | every basis change (empty diff → cheap) | low | good shape; the reads include C1's receipt `:seon.fn/calls` datoms |
| A9 | `src/seon/test.clj:1194-1196` `stale` / `same-commit?` | commit id equality, else A6 twice | as A6 | 2 × A6 | every commit | 0.8 s × test staleness check | follows A6 |
| A10 | `src/seon/env.clj:119-130` `advance-projection!` | monotonic basis-t | projection | cheap (it swaps a held value) | every commit | negligible | KEEP |
| A11 | `src/seon/schema.clj:381-412` `projection-cache-value` (per-projection atom) | projection object identity | whatever each caller derives | per caller | a new projection object, i.e. per A7 miss (per branch) | inherits A7 | KEEP; follows A7 |
| A12 | `src/seon/cluster.clj:1095` `declared-attributes`, `:1166` via A11 | projection | projection | small | as A11 | low | KEEP |
| A13 | `src/seon/fn/analyzer.clj:205` `memoize` of manifest roots; `render/lint.clj:101` `void-tag?`; `fn.clj:3134,3257`, `test/runner.clj:1349` local `memoize` | constant, or scoped to one operation | — | — | never | none | KEEP |
| A14 | clj-kondo cache walk (runtime audit row 8), `fn/analyzer.clj:224-287` | file moved or vanished | every cache entry | 313–626 ms | every publication | owned by runtime row 8 | cite |
| A15 | test-input digest over 3,415 files, `cluster.clj:1752` (runtime row 6) | none | every input file | 180–204 ms | every publication | owned by runtime row 6 | cite |
| A16 | schema diff on branch open (boot row 7), `cluster.clj:1315-1370` | none | every declaration | 2.5–3.8 s | every branch open | owned by boot row 7 | cite |

## Census B — writes on read, render, probe and eval paths

| # | where | path | what it writes | attributes touched | does it invalidate a program derivation? | cost × freq |
|---|---|---|---|---|---|---|
| W1 | `src/seon/cluster.clj:461-485` (MCP windowed result) | MCP `eval_clj`, including `read_only` | a blob plus `{:seon.dev.mcp.artifact/id d :seon.dev.mcp.artifact/digest d}` | artifact only (P9: exactly those 2 revisions advanced) | Revision keys: **no.** Commit keys (A1 on default, A4–A6, A9): **yes**. About +984 KB of store per tx (T14) | 8 of 151 tx; each re-arms A1 on default (0.4–1.2 s) and A2 |
| W2 | `src/seon/turn.clj:861`, `:885-911` `receipt-start-call` (and `:785` generated append) | each agent form | the started receipt (`:seon.cluster.eval/*`). The generated append also re-asserts `:seon.ns/name` (redundant, so no datom) | `:seon.cluster.eval/*` | **no** under revision keys; **yes** under commit keys | every form |
| W3 | **C1**: `src/seon/fn.clj:1001-1006` (the `analyze-form` non-program arm) → `turn.clj:922-963` `analyze-settlement` | each receipt settlement | **`:seon.fn/calls` on the receipt entity** | **a program attribute** (`seon.fn/calls` is in the 188) | **yes, even under revision keys.** P5: 20 of 27 "program" transactions are receipts, and `:seon.fn/calls` is the only program attribute receipts carry | defeats A1's fix; also dirties A8 |
| W4 | `src/seon/sci/eval.clj:1737-1795` `record-acquisition-refusals!` | inside `acquire!` (eval path) | error rows for acquisition refusals | `:seon.error/*` | no (data), but the commit moves | once per acquisition that has refusals |
| W5 | `src/seon/render/web.clj:3078-3105` `ensure-namespace-owner!` (reached from GET `:3328`) | page GET | an agent row for an unowned namespace | `:seon.agent/*` | no; first visit only | low |
| W6 | `src/seon/cluster/agent.clj:829-835` `acquire-context!` | context acquisition (create) | `:seon.agent/branch` | agent | no | once per agent |
| W7 | `src/seon/effect.clj:604`, `:623`, `:859` | each effect | open receipt, then settle | `:seon.effect/*` | no | 2 tx per effect: durable recovery facts, KEEP |
| W8 | `src/seon/config.clj:697` `apply-compiled!` | config apply | reconcile | `:seon.config/*`, a **dial attribute** of A1 | yes by design, **only when not converged** (`:692-695` short-circuits) | KEEP |
| W9 | `src/seon/instrument.clj:670` / `flow` `commit-fault!` | armed contract failure during eval | fault occurrence | `:seon.error/*` | no | per fault |

## The general mechanism

It has one principle and two tiers. Each part is named from the dependency.

### 1. A derived value's key is its read set's revisions: memo by evidence

Seon already captures every read's Datahike dependency plan. `seon.db` binds
`*read-evidence-sink*` (`db.clj:160`, `:512`), and each captured read retains
`:datahike.read/dependency-plan`, which comes from `query-dependency-plan` /
`pull-dependency-plan`. So a derivation does not *declare* its reads by a hand list.
It declares them **by performing them.**

The rule, as a function in `seon.db`, the one owner:

```
(derive-with-evidence cache database f)
  first call : bind *read-evidence-sink*, run (f database), keep {result, evidence}
  later call : for each evidence entry, compare its attributes' revisions and the
               conservative revision (Datahike's source-context-unchanged?,
               query.cljc:2963); all equal → the result; else run f again
```

- The storage is a `clojure.core.cache` LRU, the same one used by
  `datahike.schema-cache` and the A7 memo. The bound is declared as data.
- Lookup is O(read attributes), about 0.15 ms for 188 attributes (P7).
- An `:all` read, an unknown read or a missing identity never reports current
  (`dependency-plan-attributes` widens to `:all`, `query.cljc:2906-2933`).
  Unknown stays unknown.

This one function replaces these hand-written keys:

- A5's basis-t;
- A6's since-the-seal diff;
- A2's program vector;
- A1's `acquisition-attributes`, which is a hand-built union today: the derivation's
  own reads are the set;
- A7's `schema/projection-attributes`, where the loader's reads are the set and the
  constant stays only as documentation.

A3 is the same mechanism applied to stored evidence. Compare revisions first, then
run the index check (the since-diff) only when a revision differs. Compute one
since-diff per `(database, basis)` and share it across all reads, never one per read.

### 2. Program-reading derivations never read churn attributes

Datahike revisions are per attribute, never per entity. So one attribute shared by
program rows and by receipts makes every receipt a "program change". That is C1.

The data-guide law already requires one declared schema per identity. The fix is
**attribute separation** at the producer: receipts store their form-local call edges
under a receipt attribute (for example `:seon.cluster.eval/calls`) and never under
`:seon.fn/calls`.

The general check derives from facts that already exist:

- the program partition (`seon.program/program-attributes`, `program.cljc:29`);
- the entity schemas that declare each attribute.

The check: **an attribute in the program partition may not appear in a
non-program entity schema.** Today that is `seon.fn.edn:157` against
`seon.test.edn:108` and the receipt path. It is a query, not a list, and it should
refuse at publication.

### 3. Wildcard pulls read their entity schema's declared attributes

Of stored evidence, 30% is `:all` (A4), almost all of it `[*]` pulls. Seon has what
Datahike lacks: the entity schema of the pulled identity. Its declared attributes are
the pull's read set. A pull on `[:seon.ns/name x]` with `[*]` reads the `:seon.ns`
entity schema's attributes, plus the component closure.

Two things must be handled for this to be sound:

- Maps are open, so an undeclared extra attribute on the entity would be invisible.
  The narrowing is sound only if the writer refuses undeclared attributes on that
  entity, or if the read set adds one EAVT range check on the entity id (Datahike's
  index-pattern check, `db.clj:1090-1106`, already has that shape).
- It belongs in the fork beside `pull_api.cljc:120` as an optional declared-attributes
  argument, or in `seon.db` before the evidence is recorded. The owner of the
  pull-evidence seam picks which.

### 4. Accumulate across branches, restarts and roots: content keys

Revision keys die with the connection (see the seam). Reuse across branches is what
tests and candidates need: each one is a new branch at an unchanged program. Today a
test branch at default's head pays about 1,752 ms before its first prepared call
(P10: 269 ms projection plus 1,483 ms call preparation), and SCI base-ctx comes on top
of that.

The second tier maps the revision key to a **content key**:

- The content key is the digest of the read set's content, where Seon has one. For
  the program: `{sym → :seon.program/definition-digest}` plus the schema-row digests.
  That is 5.5 ms of query and 0.21 ms of hash (P8), paid once per connection per
  program change.
- Derived values are held **in the JVM** under the content key: projection,
  call-preparation snapshot, SCI base-ctx (whose program-identity cache already keys
  by commit id, `eval.clj:2340-2344`, so the step is small). A new branch at an equal
  program then costs one 6 ms lookup, not 1.75 s. Many branches in one JVM share one
  derivation.
- **Serializable** derived data (analysis facts, rendered HTML fragments, shown text,
  test outcomes) goes into the blob tier under the content key. Blobs are already
  content-addressed (`blob.clj:222`). It is then reused across restarts and roots.
  - kondo analysis already works this way per file digest.
  - Test results already work this way per program digest, which A6 computes
    expensively.
  - The page fragment cache (A2) is the next candidate: key = (render-pair definition
    digest, the evidence content digest).

This is not a stamp. Each value stays a memoized *function* of the database value
(AGENTS.md "No stamps"), and the key is computed from the value's own facts.

### 5. Read paths do not write, or write only churn attributes

- **W1.** A `read_only` MCP result keeps its windowed value in the prepl session,
  keyed by its content digest. The session already retains `*1`/`*2`. The durable row
  is written only when the caller asks to keep it (runtime row 3; the durability
  ruling is still owed).
- **W2 and W4–W7** stay. They write only data attributes, and under §1 no program
  derivation reads data attributes. They stop costing anything once no consumer keys
  on commit id.
- **W3** is §2.

## Three options

### Option 1 (simplest viable): fix each key in place, no new owner

Changes:

- **(a) C1:** give receipts their own calls attribute, and make the partition check a
  publication refusal.
- **(b) A3:** swap the order in `read-evidence-current?`, and share one since-diff per
  basis.
- **(c) A2:** take `:seon.db/db` out of the page key; use `program-identity` plus
  `source-generation`.
- **(d) A5:** key call preparation on its row attributes' revisions instead of
  basis-t.
- **(e) A6:** memoize `program-digest` by program-attribute revisions.
- **(f) W1:** keep `read_only` results in the session.

Guarantee: correctness is unchanged. Every new key is a subset comparison that
Datahike's own cache uses. `:all` and unknown still recompute.

Cost: about 6 small edits in 6 owners:

- `turn.clj`/`fn.clj` plus a schema resource for (a), incremental proof per the
  2026-09-23 ruling;
- `db.clj` for (b);
- `render/web.clj` for (c);
- `call_preparation.clj` for (d);
- `test/runner.clj` for (e);
- `cluster.clj`/`script/seon/dev/mcp.clj` for (f).

Each is 5–40 lines.

What it gives up: reuse across branches, restarts and roots. Wildcard pulls stay
`:all`. Six keys stay hand-shaped, so a seventh coarse key can appear later.

Retires: C1/W3, A2, A3, A5, A6 (in-connection), A9, W1, and A1's residual.

### Option 2 (recommended): Option 1's (a), (b) and (f), plus one owner, `seon.db/derive-with-evidence`, plus content keys

Changes:

- Option 1's (a), (b) and (f).
- §1's function in `seon.db`. A2, A5, A6, A7 and A1's identity become calls to it;
  their hand keys are deleted.
- §3's wildcard narrowing, with the soundness choice ruled.
- §4's content tier for program-derived JVM values: projection, call preparation,
  base-ctx.

Guarantee:

- A derived value is recomputed exactly when an attribute it actually read changed,
  or when a schema or unknown change moved the conservative revision.
- Program-equal branches share derivations in the JVM.
- Unknown is never current.

Cost:

- one `seon.db` function, about 60 lines, with one class regression: "an unrelated
  write keeps every derived value; a write to a read attribute re-derives it";
- five callers converted in their own slices;
- the wildcard change in the fork or `seon.db`, about 30 lines;
- the content key, about 20 lines, reusing the P8 query.

What it gives up:

- A first derivation pays about 5–10% more to capture evidence (an estimate, not
  measured).
- The blob-tier half of §4 (reuse across restarts) is left for later.

Retires: everything Option 1 retires, plus A4, A7's cross-branch and as-of arms, and
A11 through A7. It turns the ~1.75 s per test or candidate branch into one lookup.

### Option 3 (largest): Option 2, plus churn on its own database

Changes: turns, receipts, AI attempts, context captures, artifacts and read evidence
(116 of 151 transactions) are written to a per-cluster data branch. The program
branch then moves only on program change. A commit id becomes a valid program key
again, and every commit-keyed consumer is correct as is.

Guarantee: the program head moves at 4.6% of today's rate.

Cost:

- It loses single-transaction atomicity between a receipt and the `defn` it
  transacts. That is AGENTS.md "a `defn` it transacts is there for every agent", and
  settlement writes both.
- Refs cross databases. That is hours of cross-owner work: turn, effect, render and
  recovery.

What it gives up: the one-branch cluster model ("a cluster is one Datahike branch").
**Not recommended.** §2's attribute separation gets the same effect for derivations
at a small fraction of the cost.

## Fix order by cost × frequency

| order | row | why first |
|---|---|---|
| 1 | **C1 / W3** (receipt `:seon.fn/calls`) | Without it, A1's in-flight fix still re-acquires on every settled agent form: 0.4–1.2 s × every form. It is one producer attribute plus a partition refusal. It has to land with or before `sci-program-revisions` adoption. |
| 2 | **A2** page key holds `:seon.db/db` | About 1 s on every GET and on every SSE pass per commit, with all blocks re-derived. A few lines. |
| 3 | **A3** revision compare before the since-diff | 60–80% of page and turn-start currency time. Reordering 3 branches in one function. |
| 4 | **A5** call preparation on basis-t | 160 ms × every commit on the first prepared call; 1.5 s per new branch. |
| 5 | **W1** read-only MCP artifact rows | 5% of transactions and about 1 MB each; the durability ruling is owed (runtime row 3). |
| 6 | **A6 / A9** `program-digest` | 0.3–0.4 s per test request, growing with churn since the seal. |
| 7 | **A4** wildcard pulls | 30% of retained reads replay on every commit; needs the soundness ruling (§3). |
| 8 | **A7 cross-branch + as-of**, §4 content tier | ~1.75 s per test or candidate branch; pays off once B4 runs tests as branches. |

Rows 1–4 are file-disjoint (`fn.clj`/`turn.clj`, `render/web.clj`, `db.clj`,
`call_preparation.clj`), so they can run as parallel lanes. Row 1 touches a schema
resource and is proven incrementally on a branch (2026-09-23 ruling).

## Other observations

- **Swallowed catch.** `db.clj:1145` has `(catch Throwable _ false)` inside the replay
  arm of `read-evidence-current?`. A replay that throws reads as "changed", and the
  cause is lost. The owner's 2026-09-23 rule requires the cause to be surfaced. It is
  not fixed here (no src edits).
- **Unowned syntax error.** During this lane `bin/seon-hook` reported an unreadable
  `test/seon/sci/branch_execution_test.clj:173:83` (unmatched bracket). It is another
  lane's in-progress file, not mine.
- **Slow boot.** Default's readiness was `ready-ms 97,588` (runtime_status). That is
  over the ten-second rule. It is already the boot-cost lane's subject
  (`docs/seon/issues/from-zero-boot-takes-minutes.md`), so it is not filed again.

## TIMINGS (this lane; JVM mode on default PID 43581 unless noted)

| # | operation | result |
|---|---|---|
| P1 | `runtime_status`; loaded Vars | `program-identity` absent (default predates the lane hunk); `projection-cache-key` present; 118 revisions in the head context |
| P4 | history scan of attributes per tx since boot (151 tx) | 322.9 ms; classes as tabled (windowed result: 1 artifact tx on default) |
| P5 | the 27 program-attribute txs classified by entity | 3,076 ms; 20 receipt-only, all through `:seon.fn/calls` |
| P6 | redundant `:seon.ns/name` assertion on `:census-probe-1` | 49.4 ms tx; no revision advanced; conservative unchanged |
| P7 | building the program revision key (188 attributes) | 0.15 ms |
| P8 | `{sym → definition-digest}` query (4,816 rows) / hash | 5.51 ms / 0.21 ms |
| P9 | `:census-probe-1`: unrelated artifact tx, then projection and call preparation | tx 69.0 ms; only the 2 artifact revisions advanced; projection 0.04 ms (hit); **call preparation 160.2 ms**, then 3.9 ms |
| P10 | `:census-probe-2` at the same commit: branch / open / projection / call preparation | 28.9 / 8.8 / **269.2 ms** (cold, same program) / **1,483.4 ms** |
| P10b | program-snapshot keys | includes `:seon.db/db`, which is not the current value |
| P11 | `curl` GET `/agent/root` ×2, `/ns/my.agents.root` | **1,151 / 667 / 614 ms** (32,476 B) |
| P12 | stored read evidence, share with `:all` | 16,543 total; 4,953 `:all`; 4,615 `:pull` `[*]`; 80 `:q`; 8 `:pull-many` (1,872 ms) |
| P13 | `test.runner/program-digest` ×2 | **420.8 / 324.8 ms**; 162 tx since the seal |
| P14 | release + retire both probe branches | 16.8 ms; roster clean |

Over 1 s: P5 (a probe's own history scan, not a system operation), P10's 1,483 ms call
preparation (a defect, row A5), P11's 1,151 ms GET (row A2), P12 (a probe scan).
Nothing I ran exceeded 10 s. Caches: P9 and P10 are the hit/miss evidence. The
projection memo hit within its connection and missed on a new branch; call
preparation missed on every commit and every branch.

## Limits

- The A1 per-form re-acquisition under C1 is inferred. P5 shows the receipt writes a
  program attribute, and the lane hunk keys on those attributes, but the hunk is not
  loaded on default, so the effect was not run.
- A2's `changed-code?` was established by reading code and seeing `:seon.db/db` in the
  live snapshot. A page derivation was not instrumented.
- The evidence-capture overhead in Option 2 is estimated, not measured.
- A8's cost was not measured.
- The `d/q` against `seon.db/q` re-measurement (T17) was lost when default went down
  at 21:42Z.
