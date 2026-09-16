# Adoption write volume: is one edit rewriting the whole program?

Dated 2026-09-17 (observations taken 2026-09-16 18:20–18:42 UTC, local 12:20–12:42).
Read-only lane; every code claim is a `file:line` opened for this note, every
number carries the command or query that produced it. The working tree has
uncommitted edits in `src/seon/issue.clj`, `src/seon/turn.clj` and others by a
concurrent lane; source citations are the working tree at the time of reading,
and the running JVM (pid 53320) serves the code it last adopted.

**Verdict in one line: the write volume is not proportional to the edit. Every
source edit today ran `full-source-refresh!` — a complete rebuild of the whole
program database (637,602 datoms) onto a fresh scratch branch, ≈ 970 konserve
objects ≈ 130 MB per attempt, twice per edit because of the single retry — and
none of it published, because adoption refuses on a source commit the collector
already reclaimed.**

---

## 1. What a development adoption writes, in order

Entry: `seon.cluster/refresh-source!` (`src/seon/cluster.clj:2355`). It takes the
root-store lock, then under `retrying-source-change`
(`src/seon/cluster.clj:625`) runs the publication and, when a development
cluster instance is named, the adoption.

### 1a. Publication (the `current-src` side)

`refresh-source!` branches on changed paths: with paths,
`incremental-source-refresh!` (`src/seon/cluster.clj:1998`); without, or when
the incremental guard fails, `full-source-refresh!` (`src/seon/cluster.clj:1967`).

The incremental guard is `src/seon/cluster.clj:2005-2011` and requires **all**
of: a valid cached manifest in `build/current-src.edn`
(`source-artifact-file`, `src/seon/cluster.clj:1866`), its
`:seon.fn.manifest/relative-roots` equal to the current roots, a published
head commit, the cached `:seon.source/commit-id` **equal to the current
`current-src` head**, and a map under `:seon.source/relative-file-digests`.
Anything else → `"complete publication: missing or stale artifact"` → full
rebuild.

- **Complete rebuild** — `full-source-refresh!` → `publish!`
  (`src/seon/cluster/source.clj:545`): mint a scratch branch
  `:building-source-<pid>-<start-ms>-<uuid>` (`scratch-branch`,
  `src/seon/cluster/source.clj:196`) with `registry/branch!` **from `:db`**
  (`src/seon/cluster/source.clj:557-559`), i.e. from the empty base. Transact
  the source schema, run the population (the whole static analysis of every
  first-party file), `index-issues!` (`src/seon/cluster/source.clj:535`),
  preserve prior test evidence (`result-preservation-tx`), then the activation
  seal (`activation-seal-tx`, `src/seon/cluster/source.clj:223`). Then
  `d/force-branch!` `current-src` onto that scratch db with the previous head
  as the declared parent (`src/seon/cluster/source.clj:610-613`), and
  `registry/retire-branch!` the scratch (`src/seon/cluster/source.clj:634`).
  **Every row of the program is transacted; the previous program database
  becomes ancestry.**
- **Incremental upsert** — `upsert!` (`src/seon/cluster/source.clj:661`):
  scratch branch **from `expected-commit`** (`:675`), assert only the changed
  file's scalar rows (`:718`), `index-issues!` again, then retract the digest
  entity and re-transact the whole activation closure (`:719-721`),
  `force-branch!` (`:721`), retire the scratch (`:733`).

Answering the four sub-questions for an **incremental** adoption of one changed
file: (a) yes, it creates a scratch branch, from the published head, not from
`:db`; (b) it transacts only the changed file's rows, plus the activation
closure, which is re-asserted whole every time; (c) `current-src`'s head is
replaced by `force-branch!` — a pointer move to an already-written commit, not
a re-transaction; (d) the cluster is then reconciled by the adoption below.

### 1b. Adoption (the `default` side)

`development-source-refresh!` (`src/seon/cluster.clj:2208`), in order:

1. `previous-database` = the cluster's currently adopted commit, loaded with
   `source/database` (`src/seon/cluster.clj:2216-2217` →
   `src/seon/cluster/source.clj:165`). **This is where adoption dies today (§5).**
2. Schema declaration changes (`src/seon/cluster.clj:2225`).
3. Program reconciliation. Scalar path: transact only `:seon.source/upsert-rows`
   (`src/seon/cluster.clj:2242`). Otherwise `seon.fn/index!`
   (`src/seon/fn.clj:2439`) with `:seon.source/previous-database`, which reads
   **the whole published program** (`published-index-rows`) and hands
   `reconcile-tx-in` (`src/seon/fn.clj:2352`) the complete row set; that function
   pulls each row's current state and emits `program/exact-replacement-tx-in`
   (`src/seon/program.cljc:1027`) retractions only for rows that differ. The
   READ is whole-program; the transaction is a delta.
4. `seon.issue/adopt!` (`src/seon/issue.clj:739`, `src/seon/cluster.clj:2255`).
   **Not a delta — see §2c.**
5. Namespace reloads, SCI acquisition, JVM instrumentation.
6. The adoption record: `:seon.source/commit-id` plus
   `:seon.test/adoption-identities` / `-inputs` (`src/seon/cluster.clj:2338`).

So a healthy adoption is four transactions on `cluster-default`.

---

## 2. Measurements on `default` (read-only, pid 53320)

### 2a. Branch roster — no leaked scratch branch

`(d/branches @(seon.operator/connection "default"))` sampled 8 times over
50 s (18:37:54–18:38:34 Z): `["db" "current-src" "cluster-default"]` every
time. Two earlier samples (18:20, 18:22) each showed one
`building-source-53320-1789576625367-<uuid>` with a *different* uuid — a
publication in flight, retired before the next sample. **No leaked scratch
branch.** Scratch retirement (`retire-scratch!`, `src/seon/cluster/source.clj:271`)
is working.

### 2b. `current-src` — every commit is a complete rebuild

Walking the commit graph from the head with `d/commit-as-db` /
`d/parent-commit-ids`:

| commit | `:max-tx` | parent |
|---|---|---|
| `6aaae186-8fba` | 536870923 | `6aaae11a-5ecd` |
| `6aaae11a-5ecd` | 536870923 | `6aaae0bb-dbef` |
| `6aaae0bb-dbef` | 536870923 | `6aaadc9c-6a47` |
| `6aaadc9c-6a47` | — | **unloadable: the object is gone** |

`:max-tx` of the head is 536870923 = 11 transactions past tx0. A grouping of
`(d/datoms (d/history src) :eavt)` by transaction gives exactly 11
transactions and 538,569 history datoms on the head, the last being the
activation seal. That is the shape of a **fresh full publication from `:db`**,
not of an accumulating branch. Three successive commits with identical
`:max-tx` and chained parents = three successive complete rebuilds. Live
datoms on the head: **637,602**.

### 2c. `cluster-default` — 772,465 datoms in 463 transactions since the 16:35 Z reset

Per-transaction datom counts, from
`(reduce … (d/datoms (d/history (d/db conn)) :eavt))` grouped by transaction,
classified by dominant attribute:

| dominant attribute | transactions | datoms | share |
|---|---|---|---|
| `:seon.fn/call-arities` | 4 | 385,133 | 50 % (one of them, at 16:35:49 Z, is the fork: 384,450) |
| `:seon.issue/files` | 24 | 213,919 | 28 % |
| `:seon.test/reach` | 121 | 105,603 | 14 % |
| `:seon.fn.ast/type` | 8 | 33,451 | 4 % |
| everything else | 306 | ~34,000 | 4 % |

**The 24 `:seon.issue/*` transactions are one per adoption and are pure
churn.** Transaction 536871213 (18:08:08 Z), broken out by attribute and
`added?`:

```
[:seon.issue/files false]      2011
[:seon.issue/keys false]       1981
[:seon.issue/functions false]  1776
[:seon.issue/namespaces false] 1502
[:seon.issue/tests false]       312
[:seon.issue/members false]     116
[:db/txInstant true]              1
```

7,698 retractions, one assertion. The same ~7,270-datom retraction appears at
17:19:35, 17:22:11, 17:41:34, 17:42:07, 17:52:54, 17:56:44, 17:58:39,
18:00:50, 18:02:40, 18:04:38, 18:06:23 and 18:08:08 Z — **every adoption,
identical size**, while the live cross-reference population is only 7,294
datoms (`:seon.issue/files` 2015, `/keys` 1991, `/functions` 1784,
`/namespaces` 1504). History totals: 14,572 assertions against 166,808
retractions for those four attributes.

The cause is a vocabulary mismatch in the adoption pre-read.
`seon.issue/adopt!` (`src/seon/issue.clj:739`) pulls each published issue with
`identity-row` (`src/seon/issue.clj:695`), whose `citation-pattern` names every
ref **by its identity attribute**. `adopt-tx` (`src/seon/issue.clj:698`) then
converts those to `[identity-attribute value]` lookup refs, while `prior` comes
from `(db/pull database '[*] …)`, whose refs are `{:db/id N}`. `replacement-tx`'s
`normalized` (`src/seon/issue.clj:247-252`) maps a map to `(get % :db/id %)` —
so it compares a set of lookup-ref vectors against a set of entity ids. **They
can never be equal**, so every cardinality-many ref attribute of every issue is
declared changed on every adoption and emits `[:db/retract eid attribute]`
(`src/seon/issue.clj:258`). ~7,270 datoms per edit that encode nothing.

### 2d. Datoms per adoption, three recent ones

Because the incremental path is disabled (§3) the program half varies with the
edit, but the constant term does not:

| adoption (Z) | program reconciliation | issue churn | total on `default` |
|---|---|---|---|
| 18:02:05 / 18:02:40 | 2,685 | 7,706 | 10,391 |
| 18:04:03 / 18:04:38 | 2,643 | 7,699 | 10,342 |
| 18:05:48 / 18:06:23 | 3,400 | 7,699 | 11,099 |

On `current-src` the corresponding number is the whole program: 538,569
transacted datoms per rebuild.

### 2e. What is writing to `default` right now

Sampling transactions after 536871268 (18:32–18:38 Z): a `:seon.test/reach`
transaction of 772–1,374 datoms **every 10–20 seconds**, interleaved with
3-datom `:seon.dev.mcp.artifact/digest` writes. These are test-result
recordings on the cluster connection, from `seon.test/run`
(`src/seon/test.clj:370`) and the issue/task loop
(`src/seon/plan.clj:666`), not from publication: 121 transactions, 105,603
datoms.

---

## 3. Amplification: datoms → konserve objects

Measured object size distribution of `data/store` (4,032 `.ksv` files at
18:36 Z): median 67,339 B, p90 232,804 B, max 3,641,429 B, 1,299 files above
100 KB. Mean ≈ 132 KB. That is the branching factor showing up on disk: a
persistent-set node holding up to 4096 datoms is a ~130 KB konserve object, and
**touching one datom in a node rewrites the whole node.**

Per complete rebuild, predicted: 637,602 live datoms ÷ 4096 ≈ 156 leaves per
index, six indexes (eavt/aevt/avet + the three temporal ones, `keep-history?
true`) ≈ **936 objects ≈ 125 MB**, plus inner nodes and the commit object.

Observed, from `find data/store -type f -exec stat -f "%Sm %z" -t "%H:%M"`
bucketed by minute (local = Z − 6 h):

| minute (local) | objects | MB |
|---|---|---|
| 12:29 | 557 | 78.4 |
| 12:30 | 177 | 22.3 |
| 12:31 | 18 | 3.8 |
| 12:32 | 201 | 31.2 |
| **12:33** | **966** | **141.3** |
| 12:34 | 99 | 13.1 |
| **12:35** | **910** | **128.5** |
| 12:36 | 113 | 19.3 |
| **12:37** | **989** | **131.8** |
| 12:39 | 174 | 19.4 |
| 12:40 | 471 | 42.3 |

966 / 910 / 989 objects against a prediction of ~936: the three marked minutes
are each **one complete program rebuild**. They line up exactly with the hook
log: publication `cdb2847f` was admitted at 18:33:38 Z, refused at 18:35:08 Z,
retried, and refused again at 18:37:38 Z — two rebuilds for one two-file edit,
with a third rebuild for the neighbouring publication.

Store size measured twice: **533 MB at 18:36 Z (4,032 objects) → 753 MB at
18:42 Z (5,674 objects)**. 220 MB in six minutes = **37 MB/min ≈ 2.2 GB/h**,
and ~270 objects/min against batch C's ~100 keys/min.

**Attribution of the observed rate, largest term first:**

1. **Complete program rebuilds — ≈ 130 MB each, ~76 % of the bytes.** In the
   12:29–12:41 window, four rebuild minutes account for 3,422 of ~4,500
   objects and 480 of ~633 MB. Nothing else in the system writes 900 objects
   in a minute.
2. Cluster transactions (test-result recordings, issue churn, program
   reconciliation) — the remaining minutes, 13–42 MB each, ~20 %.
3. Agents' turns (`:seon.cluster.eval/read-evidence`, 28 transactions, 4,805
   datoms) — under 1 %.

The owner's "2.75 GB of 2.97 GB unreachable" is exactly term 1: each rebuild
orphans the previous complete program database, and a rebuild that fails in
adoption orphans its scratch immediately.

---

## 4. Verdict and options

**Not proportional, and the disproportion is not subtle**: a one-line edit
today writes a complete copy of the program database — 538,569 datoms, ~950
konserve objects, ~130 MB — and does it twice, because
`retrying-source-change` (`src/seon/cluster.clj:625`) retries the failure once.
The function that does it is `full-source-refresh!` (`src/seon/cluster.clj:1967`)
→ `publish!` (`src/seon/cluster/source.clj:545`), branching from `:db` at
`src/seon/cluster/source.clj:557-559`.

It runs because of two independent defects, both of which must be fixed for
the write volume to become proportional:

- **The cached artifact is stale and in the previous format.**
  `build/current-src.edn` is dated **Sep 15 18:31**, holds
  `:seon.source/commit-id 6aa9e37a-…` (yesterday's head), carries
  `:seon.source/file-digests` and has **no** `:seon.source/relative-file-digests`
  and no `:seon.fn.manifest/relative-roots` — three of the five conditions the
  incremental guard (`src/seon/cluster.clj:2005-2011`) demands. Hence 19 of
  today's 33 complete rebuilds report `"missing or stale artifact"`
  (`grep -o "complete publication: [^|]*" logs/hook-debug.log | sort | uniq -c`;
  65 publications today, 33 complete, 4 incremental scalar).
- **Adoption refuses on a collected basis commit, so the artifact is never
  refreshed.** `logs/current-source-failure.log` (18:37 Z):
  `"the adopted source commit is unavailable"` for
  `6aaad49e-a55e-52ad-981d-4df826c4bb8f` — the cluster's own
  `:seon.source/commit-id` — raised at
  `src/seon/cluster/source.clj:81` from `src/seon/cluster.clj:2216-2217`. That
  commit was reclaimed by the collector; the commit graph walk in §2b confirms
  the ancestry is already truncated. Already filed by a peer as
  `docs/seon/issues/development-adoption-refuses-an-unavailable-source-basis.md`.
  The loop is self-feeding: rebuild → 130 MB → adoption refuses → artifact not
  rewritten → next edit rebuilds → store grows → collector runs → more basis
  commits vanish.

### Options, simplest first

**Option A — make the adoption basis unavailability a fallback, not a refusal
(one expression).** At `src/seon/cluster.clj:2216-2217`, when the adopted
commit is gone, fall back to `(db/db connection)` as `previous-database`
exactly as the no-prior-commit branch already does. *Guarantee:* adoption
completes, the artifact is rewritten, the incremental path re-arms on the next
edit; the fallback only costs one whole-program reconciliation read, and the
transaction remains a delta. *Cost:* ~10 lines plus a regression. *Give up:*
the precise since-diff basis for that one adoption — the reconciliation still
computes the delta against the live cluster, so nothing is mis-stated.

**Option B — derive the incremental precondition instead of remembering it.**
The artifact is a hand-maintained mirror of state the store already holds: the
head commit's own `:seon.source/digest` and file digests are *in the published
database* (`current-publication`, `src/seon/cluster.clj:1907-1922`, already
reads it). Gate the incremental path on that derived digest and keep
`build/current-src.edn` purely as a manifest cache, versioned by shape so an
old-format file is re-derived rather than treated as "stale head". *Guarantee:*
a moved head (including one moved by a test recording, §5) can never force a
complete rebuild. *Cost:* half a day; touches `full-source-refresh!`,
`incremental-source-refresh!` and the artifact reader. *Give up:* nothing —
this is derive-or-die applied to a mirror that measurably went stale in a day.

**Option C — stop the per-adoption issue rewrite.** In
`seon.issue/adopt-tx` (`src/seon/issue.clj:698`) compare like with like:
resolve the prior row through the same `citation-pattern` projection
`identity-row` uses, or resolve the desired lookup refs to entity ids before
`replacement-tx`. *Guarantee:* an adoption that changes no issue note writes
zero issue datoms, removing a fixed ~7,270 datoms per edit (28 % of
`cluster-default`'s history). *Cost:* a few lines plus one regression. *Give
up:* nothing. Independent of A and B; worth doing regardless.

**Also worth one line each** (not required for proportionality): make
`record-results-at-head!` (`src/seon/cluster/source.clj:467`) skip
`force-branch!` when `commit-results!` wrote nothing — today it mints a new
`current-src` commit with an unchanged `:max-tx`, which under the current guard
invalidates the artifact and forces the next edit into a complete rebuild; and
give `activation-seal-tx` (`src/seon/cluster/source.clj:223`) a same-digest
short-circuit instead of retract-and-reassert per upsert.

### Acceptance regression

Counted from the transaction log, never wall time. On a cluster with an
adopted basis, take the branch's `:max-tx` and history datom counts before and
after one **one-line, non-structural** edit to an existing function body, and
assert:

- `current-src`: one new commit whose `:max-tx` is the previous head's plus at
  most two, and fewer than **2,000** history datoms added (today: 538,569);
- `cluster-default`: fewer than **500** datoms added across the adoption's
  transactions, of which **zero** carry a `:seon.issue/*` attribute (today:
  ~10,400, of which 7,700 are issue retractions);
- the publication reports `"incremental scalar publication"`, not
  `"complete publication"`.

The check must fail loudly when its subject is absent — an adoption that did
not happen must not read as "zero datoms written, healthy".

---

## 5. One line each

- **Leaked branch:** none. Eight samples over 50 s show only
  `db`, `current-src`, `cluster-default`; the two `building-source-53320-…`
  branches seen earlier were live publications, each gone by the next sample.
- **Publication writes to `default`:** none beyond the adoption's own four
  transactions (declarations, issue adopt, program reconciliation, adoption
  record). The basis advance a peer saw is the root agent's own work:
  `:seon.cluster.eval/read-evidence` (28 transactions, 4,805 datoms) and the
  cluster-side test recordings from `src/seon/test.clj:370` and
  `src/seon/plan.clj:666` (121 transactions, 105,603 datoms), which run on the
  cluster connection independently of publication.
