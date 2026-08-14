---
type: research
status: active
tags: [research, render, bootstrap, schema, performance]
---

# The generated opening after `help` — 2026-08-13 diagnosis

## Verdict

**Slow, not hung, and the dominant owner is not the Datahike pull.** Every
generated-opening derivation returns; on a 72.8 MB / 172,848-datom published
database at commit `30ccf1ff2` the post-`help` derivation returned in
**276,262 ms**. The time is spent rebuilding the whole Malli schema projection
from the database — once per `seon.config/effective` call, and
`seon.config/effective` is called ~2.5 times per render call because
`seon.render/request-profile` re-derives the render profile at every render
boundary instead of receiving it.

Supplying the single request key `:seon.render/profile` — which
`seon.render/walk` already derives once and carries (`src/seon/render.clj:817-827,861`)
— collapses the same derivation on the same database to **6,953 ms**, a 39.7×
reduction, with `render-call` falling from 269,126 ms to 70 ms across the same
99 calls.

This is the same class as
[the walk/history cluster-stop wedge](../../../seon/issues/walk-neighborhood-under-history-can-wedge-cluster-stop.md):
identical stack frames, one shared owner, one fix. It is a live instance of the
design law the repository already names — "Fetch-at-call-time is also the
recurring performance killer" (`CLAUDE.md`, §2.1).

## What each piece of evidence is

| Evidence | Mode |
|---|---|
| Source reading and `file:line` citations | Committed `30ccf1ff2` (`src/seon/bootstrap.clj`, `src/seon/render/walk.clj`, `src/seon/render.clj` were unmodified in the working tree when read) |
| First stack dump under the 180 s deadline | Working tree, with `bootstrap.clj`/`walk.clj`/`render.clj` clean at `30ccf1ff2` |
| The A/B measurement tables below | Pinned export of `30ccf1ff2` at `tmp/head-tree`, isolated from concurrent lane edits |

Two other lanes were editing `src/seon/render/value.clj`, `src/seon/schema.clj`,
`src/seon/db.clj`, `src/seon/render.clj`, and `src/seon/bootstrap.clj` during
this work; one mid-run edit broke compilation (`No such var:
schema.internal/derive-entity-id-attr`) and invalidated two runs. Every number
in the tables therefore comes from the pinned export, never from the shared
tree.

## Authorities read end to end

- [the non-returning post-help issue](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md)
- [the 24-second live-pull issue](../../../seon/issues/live-root-pull-of-189-members-takes-24-seconds.md)
- [the walk/history cluster-stop wedge issue](../../../seon/issues/walk-neighborhood-under-history-can-wedge-cluster-stop.md)
- [the 2026-08-13 live-pull attribution](live-pull-attribution-2026-08-13.md)
- `src/seon/bootstrap.clj`, `src/seon/render/walk.clj`, `src/seon/render.clj`,
  `src/seon/config.clj`, `src/seon/cluster/loop.clj`

## What the second pull actually is

The issue's "second live pull" is the **first** `seon.bootstrap/next-entry`
invocation. `seon.bootstrap/seed-tx` writes the ordinal-0 `(help)` source
directly at agent creation (`src/seon/bootstrap.clj:788-792`), and
`seon.cluster.loop/generate-turn` computes its ordinal from the count of
already-appended forms (`src/seon/cluster/loop.clj:1604-1612`). The loop
therefore never derives `(help)`; its first derivation runs against one
appended form and one settled receipt and returns the next entry — measured
here as `(dir my.run)`.

Its query is not one pull. One `next-entry` on this database performs:

- one receipts/forms join (`src/seon/bootstrap.clj:544-557`);
- one `walk/root-acquisition` at distance 3 — the schema-wide bidirectional
  selector (`src/seon/render/walk.clj:82-146`, executed at `:419-422`);
- `direct-candidates` — one `render/render-call` per acquisition member
  (`src/seon/bootstrap.clj:202-246`);
- `listing-candidates` → `walk/neighborhood` — a second `render/render-call`
  per member (`src/seon/bootstrap.clj:248-267`, `src/seon/render/walk.clj:566-590`);
- `admitted-intent` — one further `walk/root-acquisition` **per ready plan
  subject** plus a `direct-candidates` pass over each
  (`src/seon/bootstrap.clj:409-468`, `:324-336`);
- `root-candidate` — one `db/pull '[*]` plus a render call
  (`src/seon/bootstrap.clj:524-537`).

None of these carries `:seon.render/profile`, and the request the live loop
builds does not contain it either (`src/seon/cluster/loop.clj:1616-1625`).

## Where the time goes

`seon.render/render-call` reaches `target-profile` → `request-profile`
(`src/seon/render.clj:83-98`, `:63-81`). With no `:seon.render/profile` on the
request, `request-profile` runs a cluster-name query and then
`seon.config/effective` (`src/seon/render.clj:77-78`).
`seon.config/effective` unconditionally calls
`schema/projection-from-database` (`src/seon/config.clj:530-534`), which passes
no reusable projection, so the canonical fingerprint can never match and
`build-projection` recompiles every schema through Malli every time.

`render-argument` is invoked from both `producer-argument`
(`src/seon/render.clj:143`) and `render-invocation-argument`
(`src/seon/render.clj:245-255`), so one `render-call` produces roughly 2.5
`config/effective` calls.

The first stack dump caught this exactly. Under a 180 s deadline in the
working tree, the derivation thread was `RUNNABLE` at:

```text
malli.registry$fast_registry (registry.cljc:18)
seon.schema.internal$assert_compilable_schema_BANG_ (internal.cljc:285)
seon.schema$build_projection (schema.clj:1664)
seon.schema$projection_from_rows (schema.clj:2398)
seon.schema$projection_from_database (schema.clj:2423)
seon.config$effective (config.clj:530)
seon.render$request_profile (render.clj:78)
seon.render$target_profile (render.clj:85)
seon.render$render_argument (render.clj:124)
seon.render$producer_argument (render.clj:143)
seon.render$render_invocation_argument (render.clj:255)
seon.render$call_static_evidence (render.clj:289)
seon.render$render_call (render.clj:570)
seon.render.walk$neighborhood (walk.clj:590,566,546)
seon.bootstrap$listing_candidates (bootstrap.clj:264)
seon.bootstrap$pull_result (bootstrap.clj:503)
seon.bootstrap$next_entry (bootstrap.clj:558)
```

A second, independent consumer of the same missing extent is `seon.db`'s
`read-declarations`, which prefers a handed projection and rebuilds only when
none is bound (`src/seon/db.clj:497-501`). Because `next-entry` establishes no
projection extent of its own — only `root-acquisition` and `neighborhood` bind
one, and only around themselves (`src/seon/render/walk.clj:409-411`, `:546-548`)
— the reads in `next-entry`, `pull-result`, `admitted-intent`, `root-candidate`,
and `my.plan/ready-subjects` each rebuild it too.

## The controlled A/B measurement

Both arms are the same probe, the same pinned source, the same freshly
published database, and differ in exactly one request key.

Conditions: pinned export of `30ccf1ff2`; store 72,828,295 bytes; 172,848 EAVT
datoms; basis transaction 536870928; `-Xmx8g`; OpenJDK 26.0.1, macOS
`aarch64`, 18 processors. `ready-subject-count` was 0 in every state, so
`admitted-intent` performed no extra acquisitions in either arm.

### Wall time per derivation

| Derivation | No carried profile | Carried profile | Ratio |
|---|---:|---:|---:|
| `derive-0` (rows 0 → `(help)`; not on the live path) | 281,238 ms | 8,773 ms | 32.1× |
| `derive-1` (**the live post-`help` call** → `(dir my.run)`) | 276,262 ms | 6,953 ms | 39.7× |
| `derive-2` | 323,519 ms | 8,283 ms | 39.1× |
| `derive-3` | not run | 7,212 ms | — |

Every arm returned. No derivation deadlocked, parked, or grew without bound
across settled entries.

### `derive-1` decomposition

| Region | No carried profile | Carried profile |
|---|---|---|
| `seon.render/render-call` | 99 calls, 269,126 ms | 99 calls, 70 ms |
| `seon.config/effective` | 246 calls, 209,256 ms | **0 calls** |
| `schema/projection-from-database` (inclusive) | 1,278 calls, 542,410 ms | 12 calls, 4,671 ms |
| `seon.db/q` | 501 calls, 61,876 ms | 9 calls, 1,963 ms |
| `seon.db/pull` | 248 calls, 5,215 ms | 2 calls, 4,910 ms |
| `walk/root-acquisition` | 1 call, 4,632 ms | 1 call, 4,522 ms |
| `datahike.pull-api/pull-spec` | 495 calls, 4,478 ms | 3 calls, 4,322 ms |
| `walk/neighborhood` | 1 call, 91,252 ms | 1 call, 14 ms |
| `bootstrap/direct-candidates` | 1 call, 175,369 ms | 1 call, 59 ms |
| `bootstrap/root-candidate` | 1 call, 2,942 ms | 1 call, 394 ms |
| `bootstrap/admitted-intent` | 1 call, 875 ms | 1 call, 796 ms |
| `walk/ordered-episode` | 1 call, 3 ms | 1 call, 3 ms |

Rows nest and must not be summed; `projection-from-database` exceeds wall time
because its calls are counted inclusively at several depths.

Per-call: 2,718 ms per render call without the profile, 0.71 ms with it —
about 3,800×. `config/effective` alone averages 851 ms per call.

### What this does to the prior attribution

`datahike.pull-api/pull-spec` costs **4.3–5.4 seconds in both arms**. It is
therefore 1.6% of the faithful post-`help` derivation and 62% of the fixed one.
The [prior attribution report](live-pull-attribution-2026-08-13.md) named
`pull-pattern-frame` × `pull-attr` as "the dominant measured cost"; that
conclusion holds only *after* the projection storm is removed. Its 1.77M
`pull-pattern-frame` calls remain a real second-order cost and its proposed
Datahike-side frame optimization remains the right eventual owner of the
residual ~5 s — but it is not what makes the opening fail to return.

This report does not reconcile with that report's 88 ms figure for 37 render
calls; the two probes' cluster and config fixtures differ and the prior probe
did not instrument `seon.config/effective`. The contrast above does not depend
on that reconciliation: it is internally controlled, one changed request key,
same probe, same database.

## Is it the same class as the walk/history wedge?

**Yes.** `seon.render.walk/history` calls `neighborhood` twice — once for
`:seon.render/form` and once for `:seon.render/ai`
(`src/seon/render/walk.clj:915,917`) — and neither call carries a profile, so
each pays the full storm. The wedge issue's retained thread dump shows the
render virtual thread at `render-call` → `walk/neighborhood`
(`walk.clj:590,566,546`) → `walk/history` (`walk.clj:915,895`); the deadline
dump above shows the identical `walk.clj:590,566,546` frames beneath
`listing-candidates`. One uncarried `neighborhood` measured 91,252 ms here;
`history` performs two of them on a cluster database, which places it directly
at the suite's 300-second liveness backstop. The wedge is very probably this
defect observed through the test runner rather than a distinct completion-event
bug — worth one confirming re-run of that focused gate after the fix, since the
backstop dump alone cannot distinguish "slow" from "parked".

Confidence in the root-cause attribution: **high** (controlled A/B, matching
stack). Confidence that the same fix closes the walk/history wedge:
**moderate** — the mechanism matches and the timing matches, but the wedge was
not re-measured here.

## Ranked fix options

### 1 — Prefer the handed projection in `seon.config/effective` (recommended)

`seon.db/read-declarations` already encodes the correct shape: use
`schema/handed-projection` when the operation supplied one, rebuild only when
it did not (`src/seon/db.clj:497-501`). `seon.config/effective` ignores that
and always rebuilds (`src/seon/config.clj:530-534`). Making it prefer the
handed projection, and establishing exactly one
`schema/call-with-projection` extent at the derivation boundary
(`bootstrap/next-entry`, or the turn in `cluster/loop`), removes **both**
measured cost centres — the 209 s of `config/effective` and the 1,278
`db`-read rebuilds — for every caller, including `walk/history`, without any
caller needing to remember a key.

Guarantee: no render or read inside one operation recompiles the projection.
Cost: one seam in `config.clj` plus one binding at the turn boundary; needs a
regression that fails when an operation rebuilds the projection more than once.
Gives up: nothing measured — the projection is already immutable per database
value.

### 2 — Carry `:seon.render/profile` through the generated opening

Do in `bootstrap/pull-result` and `cluster.loop/generate-turn` exactly what
`seon.render/walk` already does: derive the profile once and put it on the
request (`src/seon/render.clj:817-827,861`). This is the change measured above:
276 s → 7 s. Smallest possible diff, immediately available, and it obeys "values
carry their world" literally.

Cost: it is caller-side. `walk/history` forgot it, `listing-candidates` forgot
it, and the next caller will forget it too — the same defect will return under
a different name. Best taken **together with** option 1, not instead of it.

### 3 — Derive the profile at most once per `render-call`

`render-call` currently reaches `request-profile` through both
`producer-argument` and `render-invocation-argument`, costing ~2.5
`config/effective` calls per render (246 for 99 renders). Threading the
already-computed profile down that chain removes the 2.5× multiplier.

Cost: touches the render argument construction path. Value: real but secondary
— it turns 246 rebuilds into 99, which is still 84 s. Do it only after 1.

**Not the fix:** bounding the pull pattern's depth, batching membership pulls,
or a Datahike-side frame optimization. Those address the residual ~5 s that
both arms pay identically; none of them touches the 270 s.

## Residual after the fix

At 7 s per generated entry the opening is still two orders of magnitude above
the 19.6 ms cold / 1.9 ms warm price the self-generating-context PRD assumed,
and the remaining owner is the Datahike pull the prior report attributed. The
one-generator/one-entry restructuring it recommends (acquire once per
generation invocation, carry the immutable result forward) remains correct and
would remove the repeated 4.5 s acquisition; it should be sequenced after this
fix, not instead of it.

## Reproduction

The probe is [`tmp/live-pull/after_help_probe.clj`](../../../../tmp/live-pull/after_help_probe.clj).
It seeds the run through the live `cluster/ensure-entity!` path (which commits
`bootstrap/seed-tx`), then advances the opening exactly as
`cluster.loop/generate-turn` does — derive, append, settle, derive — with each
derivation executed on its own thread under a declared deadline so a
non-return is reported as a stack dump rather than an unbounded wait.

```sh
clojure -J-Xmx8g -M:dev:test -i tmp/live-pull/after_help_probe.clj \
  -m after-help-probe tmp/live-pull/after-help-a 3 900

clojure -J-Xmx8g -M:dev:test -i tmp/live-pull/after_help_probe.clj \
  -m after-help-probe tmp/live-pull/after-help-b 4 900 carried-profile
```

To pin against concurrent lane edits, export the commit and symlink the
vendored dependencies:

```sh
mkdir -p tmp/head-tree
git archive 30ccf1ff2 src test resources config script deps.edn | tar -x -C tmp/head-tree
git show 30ccf1ff2:dev_cache.clj > tmp/head-tree/dev_cache.clj
git show 30ccf1ff2:build.clj > tmp/head-tree/build.clj
ln -s "$(pwd)/reference-code" tmp/head-tree/reference-code
```

Absolute timings are hardware- and graph-dependent. The evidence is the
controlled ratio and the call counts, not an elapsed-time threshold.

## Unmeasured amplifiers

- `ready-subject-count` was 0 throughout. A live agent with ready plan items
  adds one full `root-acquisition` plus a `direct-candidates` pass per subject
  (`src/seon/bootstrap.clj:409-468`), multiplying whatever the per-derivation
  cost is.
- The historical 27-second non-return was observed on a 1.22 GiB database,
  roughly 17× this probe's store. Per-render `config/effective` scales with
  the schema and program-graph row count, so that observation is consistent
  with this mechanism without this report claiming a size law.
