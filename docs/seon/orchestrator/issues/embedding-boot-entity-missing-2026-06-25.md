---
type: issue
status: active
tags: [issue, database, agent]
---

# Embedding boot noise — 232 `:entity-id/missing` errors on fresh seed

Branch `feature/agent-fsm`. On a clean-slate boot, `logs/wire-server.log`
carries 232 ERROR-level lines:

```
:error datahike.db.utils [146 8] Nothing found for entity id [:seon.fn/sym "seon.db/transact!"]
  data: {:error :entity-id/missing, :entity-id [:seon.fn/sym "seon.db/transact!"]}
```

one per seeded core fn (`seon.db/*`, `seon.agent.fs/*`, `seon.agent.todo/*`,
`seon.agent.message/*`, `my.kb.shared/*`, `seon.ai.*`, …). New default:
`SEON_EMBED=1` for the `default` cluster (`bin/seon:111`).

## TL;DR

Benign log noise, not a correctness bug. The embed write-path augmenter reads
each fn entity's *prior* embedding hash by lookup-ref **inside the same seed tx
that first creates the entity** — the entity doesn't exist yet, datahike logs an
`:error` then throws, and the embed code catches the throw (but not the log).
Every fn still gets embedded: **232 fns / 232 with `:seon.embed/source-hash` /
232 with `:seon/embedding`** — a clean 1:1 with the 232 errors. This is a
sibling of #E2 (same `augment-tx` lookup-ref mechanism), NOT #17 (the
`:seon.fn/sym` schema is installed — that's how the lookup-ref gets built at
all).

## Root cause — file:line + boot call path

The wire-server transact handler runs the embed-on-write augmenter on **every**
write, including the core seed:

1. `seon.server.wire/augment-tx` (`src/seon/server/wire.clj:207`, called at
   `:380` / `:410`) invokes the registered augmenter `@!tx-augmenter`.
2. The augmenter is `seon.embed/augment-tx-with-embeddings`
   (`src/seon/embed.clj:976`), installed via
   `wire/register-tx-augmenter!` at `src/seon/embed.clj:1258`.
3. For each seed item (a fn entity-MAP carrying `:seon.fn/sym` but **no
   `:db/id`**), `resolve-id-ref` (`src/seon/embed.clj:952-966`) returns the
   identity lookup-ref `[:seon.fn/sym "<ns>/<name>"]` (it finds `:seon.fn/sym`
   as `:db.unique/identity` in the live schema).
4. `current-hash-for` (`src/seon/embed.clj:968-974`) then calls
   `(d/pull db [:seon.embed/source-hash] [:seon.fn/sym "<ns>/<name>"])`.
5. The entity is being **created in this very tx** and does not exist in `db`
   yet, so datahike's `entid-strict`
   (`reference-code/datahike/src/datahike/db/utils.cljc:141-148`, the
   `[146 8]` in the log) logs `:error … Nothing found for entity id` (line 146)
   **and then raises** `:entity-id/missing`.
6. `current-hash-for`'s `(try … (catch Throwable _ nil))` swallows the **throw**
   — but the `:error` line was already written to the log. It returns `nil`, so
   `(not= hash nil)` is `true` and the entity is queued for embedding (correct
   outcome, noisy path).

So the 232 lines are exactly the seed fns passing through the write-path
augmenter on first creation.

It is **not** the boot backfill (`backfill!` / `drain-backfill!`,
`src/seon/embed.clj:1059`/`:1099`): those scan existing eids with
`[?e ?attr]` and `d/pull` by **numeric eid** — no lookup-ref, no such error.
The `WARN [seon.embed:378] deleted STALE orphan Proximum index store … (cluster-reset
orphan)` line is unrelated and already handled.

## Benign vs breaking — live verdict (real numbers)

BENIGN. Embedding ingest completes correctly. Live queries against the running
store (pod replica of the wire-server store):

| query | count |
|-------|-------|
| `[?e :seon.fn/sym]` (total fns) | 232 |
| `[?e :seon.fn/sym][?e :seon.embed/source-hash]` | 232 |
| `[?e :seon.fn/sym][?e :seon/embedding]` | 232 |
| `[?e :seon.embed/source-hash]` (all kinds) | 232 |

100% coverage — every seeded fn carries both the source-hash and the embedding
vector. Independent live evidence in `logs/wire-server.log`: the Proximum HNSW
index is populated (`proximum.internal.HnswInsert -- Insert DONE nodeId=…` up to
nodeId 231+), i.e. the boot backfill embedded the corpus and inserted every
vector into the index. The 232 errors are pure log noise: `:error`-level lines
that are immediately caught, with zero effect on ingest.

## Tie to #17 and #E2

- **NOT #17 (eager schema install).** #17 is "lazy schema install → lookup-refs
  throw because the *attr* isn't installed yet". Here the `:seon.fn/sym` schema
  IS installed — `resolve-id-ref` only produced the lookup-ref *because* it
  found `:seon.fn/sym` as `:db.unique/identity` in the live schema
  (`embed.clj:962-965`). The throw is `:entity-id/missing` (the *entity* row
  doesn't exist yet), not `:entity-id/syntax` or a missing-attr error. #17 would
  not be cured by the fix below, and this is not cured by #17's eager install.

- **Sibling of #E2 (augment-tx anchor-loss, #21).** Same function
  (`augment-tx-with-embeddings`), same lookup-ref-into-the-same-tx mechanism.
  #E2 is the *write* side (the partial assertion `{:db/id id-ref :seon/embedding
  v :seon.embed/source-hash hash}` at `embed.clj:1013-1018` can drop the
  `:seon.fn/sym` anchor); this is the *read* side (`current-hash-for` pulling the
  prior hash by a lookup-ref that can't resolve mid-seed). Both are facets of
  "augment-tx resolves an entity by lookup-ref in the same tx that creates it."
  A proper #E2 fix should fold this in.

## Recommendation

Two paths; they are not exclusive.

### (a) Surgical noise fix — resolve lookup-refs silently (the real fix)

In `current-hash-for` (`src/seon/embed.clj:968-974`), stop calling `d/pull`
directly on a lookup-ref. When `id-ref` is a lookup-ref `[a v]`, resolve the eid
with a query first — `(d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v)`
— which returns `nil` **silently** (no `entid-strict`, no `:error` log) when the
entity doesn't exist, then `d/pull` by the numeric eid (or short-circuit to
`nil`). ~5 lines, no behavior change (still embeds the entity), and it removes
all 232 lines. This is the fix to land **when embeddings work resumes** — it
belongs in the same patch as #E2 since both touch the augment-tx lookup-ref path.
Trade-off: it edits the embed write-path, which currently carries open P1 bugs
(#E1 Vertex multi-text invalid, #E2 anchor-loss, #E3 10k cap), so it shouldn't be
a one-off poke while that subsystem is paused.

### (b) Default `SEON_EMBED` OFF until the embed wiring is fixed (recommended NOW)

Flip the default at `bin/seon:111` from `1` to empty
(`export SEON_EMBED="${SEON_EMBED:-}"`). One-line env-default change, zero code
risk, instantly clean boot. The whole feature is already a clean off-by-default
gate (`embed-feature-enabled?`, `embed.clj:153-161`): with `SEON_EMBED` unset the
augmenter is a pass-through, no index is declared, `backfill!` no-ops — so the
232 errors vanish and so does the whole HNSW-insert DEBUG flood. Trade-off: loses
semantic retrieval at boot (per-turn predictive retrieval + the boot backfill).
Given embeddings is deprioritized vs the context/loop regressions, the embed
write-path has multiple open P1 bugs, and the owner wants a clean boot, this is
the lower-risk immediate move.

**Lean:** do (b) now for the clean boot, and land (a) folded into the #E2 fix
when embeddings work resumes — the noise will return the instant `SEON_EMBED` is
re-enabled, so (a) is the durable cure.
