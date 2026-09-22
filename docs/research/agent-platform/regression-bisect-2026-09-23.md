---
type: research
status: investigation; no src/test/resources edits
created: 2026-09-23
tags: [agent-platform, performance, projection, datahike, bisect]
---

# Regression bisect: whole-program projection derivation inside transactions (2026-09-23)

Lane regression-bisect (investigation only; no src/test/resources edit). Asked:
first bad commit, profile, shared root cause, fix at the owner. Schedule rows
24i and 24j (`docs/research/agent-platform/fix-schedule-2026-09-23.md`); related
issues `docs/seon/issues/a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction.md`,
`docs/seon/issues/warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md`
and P4 in `docs/research/agent-platform/one-error-route-design-2026-09-23.md`.

## Answer

**First bad commit: `9b8c5b405`** ("Derive the projection as a memoized
function of the database value (1.4 commit 1)"). Its parent `da703089b` is good.
`ca9c40639` adds a second unmemoized path for as-of views.

`seon.db/carried-projection` memoizes only a value that has Datahike's committed
identity, and keys it by `:datahike.cache/connection-id` and `:generation`. Any
other value runs `schema/load-projection` again, which rebuilds the whole
program's Malli registry (3,350 forms). Those values are:

- a value inside a transaction, which Datahike gives no cache-context
  (`reference-code/datahike/src/datahike/core.cljc:136`; `writing.cljc:609`
  for the report's `:db-after`);
- an as-of view (the `as-of?` branch added by `ca9c40639`);
- the same commit reached through another connection (a new branch).

Every `seon.db` read of such a value forces `read-declarations`' projection
delay (`src/seon/db.clj:1318` at HEAD) through `pull-call`, `edn-encoded?` and
`query-find-attributes`. So the work per read grows with the whole program, not
with the transaction.

## 1. Bisect (one probe, one JVM per commit)

Probe: `tmp/regression-bisect/probe.clj`, run by `tmp/regression-bisect/run.sh
<commit>`. The runner takes a `git archive` snapshot with `reference-code`
linked and uses a `cp -c` clone of the realities store
(`tmp/realities-c5-root/data/store`, written at 15ffb4936). It calls
`seon.config/reconcile-call`, the transaction function of
`apply-compiled!`, with the same projection, desired rows and request in two
ways:

- on the committed value;
- on the in-transaction value (`d/with` over it, cache-context nil).

It counts whole-program derivations by wrapping `load-projection` /
`projection-from-database` in the probe's own JVM. Before `9b8c5b405`, the
connection stamped the value, so the probe stamps it the same way. Older
programs cannot compile this newer store's rows (`seon.db/identity-attribute-accumulator?`
has no callable). Their derivations therefore throw and return the stamp. The
count still decides the result.

| commit | reconcile-call, committed | reconcile-call, in-transaction | whole-program derivations in-tx | verdict |
|---|---:|---:|---:|---|
| bfe3445f8 | 165 ms | **219 ms** | 0 | good |
| da703089b (parent) | 161 ms | **234 ms** | 0 | good |
| **9b8c5b405** | 170 ms | **27,194 ms** | **97** | **bad** |
| 874918765 | 182 ms | 27,629 ms | 97 | bad |
| 15ffb4936 | 144 / 176 ms | 18,407 / 15,697 ms | 97 (0 threw) | bad |
| 15ffb4936 + patch below | 125 ms | **158 ms** | **0** | fixed |

Live confirmation in default (pid 70720, HEAD db.clj), on a disposable branch
`:rb-probe-r2` (created, then released and unlinked; not on the roster
afterwards):

- `config/apply-compiled!` for the new cluster "rb-probe-cluster":
  **21,867 ms** for 4 ops.
- Pure `reconcile-call`: committed value 121 ms, in-transaction value
  **16,328 ms**, with `reconcile/plan` taking 15,990 ms of it. One `db/pull`:
  1 ms on the committed value, 201 ms in the transaction.
- `carried-projection` on the in-transaction value: 156 / 153 / 169 ms per
  call. On the committed value: 0 ms.

Regression 2 (fixture) reproduced in default:

- `carried-projection` on a fresh branch at the same commit took
  **221 ms** and returned an object not identical to the parent's (forms equal,
  3,349).
- The keys differ only in `connection-id` (`cluster-default` vs `rb-probe-r2`)
  and `generation`.
- One `schema/load-projection` takes 156 ms.
- An as-of view at max-tx took 206 / 162 / 150 ms per call.

## 2. Profile

JFR `settings=profile` ran on default during the 21.9 s apply. The recording
and sample export are deleted; the aggregators `tmp/regression-bisect/agg*.py`
remain.

- Of 8,622 samples carrying Seon frames, **6,659 (77%) are inside `seon.schema/load-projection`**.
- Writer thread `async-mixed-15`: 2,897 samples at `carried-projection`'s
  `:else` branch (db.clj line 1301 as loaded, `9b8c5b405`). Callers:
  `read-declarations` fn ← `pull-call` / `edn-encoded?` ←
  `query-find-attributes` / `encode-query-request` ← `reconcile/plan`
  (`src/seon/reconcile.cljc:299`) ← `config/reconcile-call`.
- The web render thread: 3,687 samples at the `as-of?` branch (line 1292 as
  loaded, `ca9c40639`), through `seon.render/request-projection`. This is a
  standing background cost in default whenever a page renders at an as-of
  basis.
- Inside the derivation, 92% is `seon.schema/projection-from-rows`
  (`src/seon/schema.clj:2599`) → `build-projection` (`schema.clj:1944`). That
  step collects predicate symbols, builds structural schemas, compiles forms
  and builds the registry for every declaration.

Why the work grows with the whole program: `load-projection`
(`schema.clj:2832`) rebuilds the registry from all 3,350 declaration rows. It
runs once per read in the transaction, and one small reconcile makes about 97
reads, so the cost is reads × program size.

## 3. One root cause for all five symptoms

All five are the same miss in `carried-projection`: values without committed
identity, and committed values reached through another connection.

1. **Config apply, 16.5–21.9 s.** Reads on the in-transaction value, about 97
   derivations. Measured above.
2. **Fixture p50 53 → 422 ms.** A new branch connection misses the
   connection-keyed memo and gets a new, non-identical object.
   `acquire-context!` (`src/seon/cluster/agent.clj:826-868`) reads it at once.
   The key members are the same at `9b8c5b405`. Measured at HEAD: 221 ms.
3. **Fault transaction ~1.1 s inside the transaction vs 26 ms outside.**
   `error.clj` reads on the in-transaction value; about 7 derivations at
   ~160 ms. Each derivation also returns a new projection object. Every
   `schema/projection-cache-value` entry keyed by that object then misses too
   (error.clj:1397-1407, the lane's "not identical"). Inferred from the
   mechanism; not re-measured here.
4. **Warm-restart hang, 164 ms per small transaction.** That issue already
   names `carried-projection` → `load-projection` on values without committed
   identity (issue lines 42-47). Same branch. Not reproduced: a plain
   `transact!` on the probe branch took 26–40 ms, because its report validation
   read no decoded attribute.
5. **Five-file publication, 43.8 s transaction.** The coordinator added this
   case. The transaction is one `:db.fn/call` (`src/seon/fn.clj:3589`) that runs
   `reconcile-tx-in` on the in-transaction value. It still does `(vary-meta
   database assoc :seon.schema/projection projection)`. Since `9b8c5b405`
   nothing reads that stamp once declaration rows exist, so it is dead. Every
   read then derives: 5,634 `carried-projection` calls, 41,046 ms inclusive, a
   mix of hits and derivations. A one-file leaf publication makes 173–178 calls
   (1.3–1.6 s). Inferred from the code path and the lane's log
   (`tmp/publication-work/leaf3.log`); not re-measured here.

## 4. Fix at the owner (patch)

`tmp/regression-bisect/carried-projection-content-key.patch`, against HEAD
`src/seon/db.clj` (`git apply --check` passes). It keys the memo by what
`load-projection` reads, per README §7 and the AGENTS "No stamps" rule, with no
stamp and no second cache. Everything goes in the one existing core.cache LRU,
raised from 8 to 32 entries.

- **`declaration-content-key`** is each attribute in
  `schema/projection-attributes` with its datoms and their transactions. The
  datoms are sorted with Datahike's own `datahike.datom/cmp-datoms-aevt-quick`
  because an as-of view returns the same set in a different order (measured:
  sets equal, vectors not). Cost in default over 30,387 datoms:
  - build 0.9–1.5 ms, hash 0.5–0.9 ms, equality 0.4 ms;
  - sorting input that is already in order adds nothing (1.42 vs 1.49 ms);
  - 17–21 ms on an as-of view.

  This compares with 150–220 ms for one derivation.
- **Identity tier.** `content-projection` looks up the value object first
  (`ValueKey`, `identical?` and identity hash), then the content key. A
  transaction function's hundred reads on one object therefore compute the
  content key once.
- **`carried-projection`** keeps the revision key for committed values. On a
  miss it goes to `content-projection` instead of deriving, so a new branch at
  an equal commit gets the identical object. The `as-of?` and `:else` branches
  call `content-projection`.
- **Correctness kept:**
  - An as-of view keys by its own datoms, so a view older than a declaration
    change still derives the older population (ca9c40639).
  - An in-transaction value's content includes every earlier operation of its
    transaction, so ordered declarations still see them (9b8c5b405).
  - No construction metadata is read while declaration rows exist.

Proof in the patched 15ffb4936 snapshot (`out-15ffb4936-fix.edn` against
`out-15ffb4936.edn`):

| probe | unpatched | patched |
|---|---:|---:|
| in-transaction reconcile | 15,697 ms, 97 derivations | 158 ms, 0 derivations |
| new branch at equal commit | 248 ms, not identical | 68 ms cold, identical |
| in-transaction value | not identical to committed | identical to committed |
| repeat read in a transaction | 161 ms | 1 ms |
| as-of at max-tx | 260 / 188 ms, not identical | 86 ms cold then 2 ms, identical |

**Relation to writer-cost's working-tree hunk.** The lane's
`src/seon/db.clj` hunk, seen 2026-09-22 22:1x, has the same design with an
extra `[::commit id]` tier. Its content key does not sort. As a result, every
as-of view derives the whole program once per distinct as-of content instead of
hitting the origin's projection. Take the sort from this patch.

**What the holder should run:**

1. Apply the patch or merge its sort.
2. Run `test/seon/schema/projection_writer_test.clj`. It covers as-of before a
   declaration change, ordering and rollback, and the stale stamp on the writer
   path.
3. Run `test/seon/test/fixture_timing_test.clj`. The branch must be
   `identical?` to its parent, p50 ≤ 100 ms.
4. Run `tmp/regression-bisect/run.sh <snapshot>` (store clone recreated with
   `cp -c`). Expect 0 in-transaction derivations.
5. Re-time `config/apply-compiled!` on a fresh branch in default (expect
   ≈ 313 ms, as at bfe3445f8).
6. Re-time the publication leaf probe (`docs/prds/agent-platform/landing/lane-publication-work-leaf-2026-09-23.clj`).
7. Measure memory. Each content entry holds about 60k references (datoms plus
   transactions), and value entries hold whole database values, bounded at 32.

**Out of scope for db.clj:** the dead stamp at `src/seon/fn.clj:3589`
(`vary-meta … :seon.schema/projection`) belongs to the fn.clj holder. It should
be deleted with the fix; it is an AGENTS "No stamps" sighting.

## Timings (operations over 1 s)

| operation | wall | breakdown / note |
|---|---:|---|
| default `apply-compiled!` on a probe branch | 21,867 ms | reconcile transaction; 77% of samples in load-projection. **Defect (>10 s)**, schedule 24i |
| default pure `reconcile-call`, in-transaction | 16,328 ms | `reconcile/plan` 15,990 ms |
| probe JVM 15ffb4936 | 38–40 s | seon load 12.9–14.1 s, cold first derivation 4.8–5.7 s, in-tx reconcile 15.7–18.4 s |
| probe JVM 9b8c5b405 / 874918765 | 54 / 51 s | load 16.6 / 14.9 s; in-tx reconcile 27.2 / 27.6 s |
| probe JVM bfe3445f8 / da703089b | 23 / 26 s | load 15.0 / 16.6 s; in-tx reconcile 0.22 / 0.23 s |
| probe JVM 15ffb4936 + patch | 16 s | load 10.0 s; in-tx reconcile 0.16 s |

Every probe JVM spends 10–17 s loading `seon.config` and its closure before any
work. That is a defect over 10 s, not a priming cost. Cache: dependencies came
from `~/.m2` and `reference-code` sources, while Seon's own source was compiled
cold in each snapshot, so there was no class-cache hit for first-party code.
This lane's commit boundary is this file alone, so it is not filed as an issue
here.

## Verification boundary

- Measured live in default at HEAD `db.clj`: regression 1, regression 2, the
  as-of cost and the JFR attribution.
- Bisected in offline snapshots over a cloned store with the probe's own
  wrapper. The pre-`9b8c5b405` stamp was supplied by the probe, as the old
  connection boundary did.
- Not re-measured: the fault transaction, the restart hang and the publication
  transaction. They are attributed by mechanism and the lanes' logs.
- The patch is not run against the canonical tests. The holder runs them.
- No reset is needed. The probe branch is unlinked, and the user-namespace
  probe vars in default were unmapped.
