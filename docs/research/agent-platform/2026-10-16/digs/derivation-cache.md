# Derivation keys: dig (Haiku, read-only)

Status: evidence only. No decisions, no source edits. Probes: `(seon.cache/status)` on default JVM (jvm mode, root), 2026-10-16. Counts are a snapshot, not a benchmark.

## 1. Every `seon.cache` kind, its key, and what the key is

About 80 kinds appear in `(seon.cache/status)`. Weights are calibrated estimates (`src/seon/cache.clj`, Known issues), not retained sizes. Key classes: C = content digest or immutable value; K = commit id, connection, or branch scope; O = other.

| Kind | Key class | Key (source) |
|---|---|---|
| `seon.db/behavior-digest` | C | definition entity (`src/seon/db.clj:5671`, weight constant 256) |
| `seon.db/projection` / `value-projection` / `projection-alias` | C+K | canonical projection key; `projection-cache-key` reads Datahike attribute revisions (`src/seon/db.clj:1591`) |
| `seon.db/builder-digest` | K | commit id (`src/seon/db.clj:1840`, `1895`) |
| `seon.db/request-view` | K | `[commit-id, max-tx, digest]` (`src/seon/db.clj:351`) |
| `seon.sci.eval/program-context` | C | `program-key`, then base key (`src/seon/sci/eval.clj:4079`, `4131`) |
| `seon.sci.eval/loaded-digests` | K | `[store-identity, commit-id]` (`src/seon/sci/eval.clj:1423`) |
| `seon.sci.eval/kept-value` | K | `value-key` of the database row (`src/seon/sci/eval.clj:1028`) |
| `seon.render` `::invocation` | K | `[branch-scope, selected, output, hashes of program, projection, input]` (`src/seon/render.clj:952`) |
| `seon.render` `::call-reach` | C | selected + content (`src/seon/render.clj:869`) |
| `seon.cluster.source/publication` (block-cost) | K + C | main commit, branch changed digests, proof names, task, checkout root (see section 4) |
| `seon.cluster.source/merge-base` | K | commit ids (0 hits / 20 misses at snapshot) |
| `seon.cluster.source/commit-graph-record` | K | commit (97% hit) |
| `seon.cluster.source/file-derivation-key` | C | file bytes digest (92% hit) |
| `seon.cluster.source/file-schema-keys` | C | file content (0 hits / 584 misses at snapshot) |
| `seon.test.runner/reach-node` | C | definition digest (40% hit, 7,462 evictions in merge-cost run) |
| `seon.test.runner/reach-attribute` / `-head` | K | attribute revision head (27% hit) |
| `seon.test.runner/reach-index-by-revision` | K | revision; 0 hits / 11 misses, 16% of bytes at snapshot |
| `seon.test.runner/result` | K | `[test, reach, inputs]` (`src/seon/test.clj:2630`) |
| `seon.schema/projection`, `declaration-projection`, `schema.edn/packaged-population` | C | forms + function contracts + options (`src/seon/schema.clj:2431`) |
| `seon.program/authored-shapes`, `source-resolver-names`, `program-attributes` | C | `declaration-digest` / source |
| `seon.health/derived`, `untested`, `problems` | O | `lookup-within` by committed value and deadline (`src/seon/health.clj:421`, `441`); derived has 0 hits / 25 misses |
| `seon.ref/document` | C | document content (81% hit) |
| `seon.fn.analyzer/parsed-facts` | C | source text |
| `seon.config/effective` | K | configuration value (91% hit) |
| `seon.cluster.agent/admission-end`, `branch-end` | K | branch / agent identity |

Counter-reading: the weigher gives `behavior-digest` 256 bytes, but a miss costs about 0.6 MB allocation (merge-cost, `docs/research/agent-platform/2026-10-15/merge-cost.md`). Evictions fall on cheap-to-keep entries while 100 MB whole-program maps stay.

## 2. Read evidence

- `seon.db/read-evidence-current?` (`src/seon/db.clj:1416`): first compares each retained attribute revision (`dependency-revision`); if they differ it runs `index-evidence-current` (history scan with `d/since`, `index-pattern-change`, `src/seon/db.clj:1347`); if still unknown and the evidence carries `:seon.db/read-request`, it replays the read (`replay-read`, `:1254`) and compares `stable-value`.
- Callers: `src/seon/render.clj:986` (`reusable-invocation`), `:1531`, `:1582`; `src/seon/render/walk.clj:396`; `src/seon/render/web.clj:1462`, `:1562`, `:1907`.
- Not used by `seon.cache` lookups directly: the publication key, `seon.db/behavior-digest`, `seon.test.runner/result` and `seon.health/*` key on content or commit without read evidence. So the content-validity proof exists for renders and not for publication.

## 3. Datahike change tracking usable as keys

Reference pin per `reference-code/datahike` (not re-pinned here).

- Commit identity: `datahike.db/committed-value-identity` (`reference-code/datahike/src/datahike/db.cljc:398`) returns `{connection-id, generation, commit-id}` for committed raw DBs, nil for speculative values. Commit id: `datahike.versioning/commit-id` (`reference-code/datahike/src/datahike/versioning.cljc:468`), read by `datahike.api.impl/commit-id` (`reference-code/datahike/src/datahike/api/impl.cljc:377`). Process-local; commit ids do not change for unrelated writes to other branches.
- Diff: `datahike.db/datoms-diff` (`reference-code/datahike/src/datahike/db.cljc:258`) skips shared index subtrees; wrapped as `seon.db/datoms-diff` (`src/seon/db.clj:460`). Work follows difference, not size.
- Per-attribute revisions: `datahike.query/advance-query-cache-context` (`query.cljc:2568`) advances one revision per changed attribute and a conservative one on schema change. Seon's projection key already uses this (`src/seon/db.clj:1591`).
- History: `datahike.api.impl/since` (`:148`) and `history` (`:185`).
- Index root hash / merkle root of a value: not found by grep for `:root-hash` or `merkle` in `reference-code/datahike/src` (no hits). Treat as unavailable until checked against a concrete seam.

## 4. Block-cost: where time goes and what key was missing

Source: `docs/research/agent-platform/2026-10-16/block-cost/report.md` (commit d4585c8cd).

- Warm `seon.context/current-state`: first block 977 ms / 627 MB (controlled A), 90k calls, 3 kondo runs, 1 preview `seon.db/with`; second block 190 ms / 124 MB, 0 kondo runs. Same branch commit, different main commits, identical main program-content digest: miss on each new main commit.
- Self-time leaders in the 872 ms step probe: `seon.fn.analyzer/invoke-kondo` (3 calls, 272 ms), `seon.db/with` (159 ms), `seon.cluster.source/committed-bytes` (94 ms), `seon.fn.analyzer/analyze` (48 ms).
- Missing key: the publication key uses main commit, but the body also reads steward ownership (`seon.cluster.source/agent-owned`), current proof results (`merge-seeds`), file bytes at checkout HEAD (`committed-bytes`), analyzer config and dependency analysis. The report states no complete content key was established, and that `program-content-digest` alone is wrong. It suggests caching file derivations by their actual immutable inputs (bytes, selected declarations, analyzer config, resolved deps).
- Reference resolution measured small (23 ms) in the warm observation.

## 5. Prior design docs and decisions

- `docs/research/agent-platform/cache-central-design-2026-09-26.md`: one Caffeine `AsyncCache` behind `seon.cache`, keys name content, values only evicted; Datahike keeps its own caches. Found 13 process-level core.cache instances, two `memoize`s, a per-branch render atom, and an unbounded `memoize` in `seon.render.value/throwable-safe` (task 4e5a30acb8a3). `CLAUDE.md` wording must be amended with the owner (it still says `clojure.core.cache`).
- `docs/research/agent-platform/2026-10-15/merge-cost.md` (commit 960d09707): cache full at 511.8 of 512 MB; reach-index-by-revision 94 MB with 0 counted hits; the cold reach graph dominates merge-seeds (3.4 s, 5.3 GB cold, 0.56 GB warm).
- Alignment dig: commit `95fe3ba86` (inventory of 58 rows) and hybrid `31c9bdf8e` ("the commit is the point in time, datoms-diff the one change primitive"). Narrow check `1c9323f09` reports datoms-diff throws NPE on null slots. Hybrid text is in `docs/research/agent-platform/2026-10-15/alignment-dig.md`, read only at the heading level here.
- `docs/research/agent-platform/2026-10-16/printer-design/hybrid.md` and the render narrow check `8b5d14abe` decided: render cache keyed by content + options + read evidence, branch scope dropped (P1c). Not re-read here; cited from the task brief.
- Note: the commit-id claim "the commit is the one point in time" is from the brief; `alignment-dig.md` exists in the tree at `2026-10-15`, not `2026-10-16`.

## 6. Memoize and atom-held derived state in src

- `memoize` calls in src (9 real calls; a tenth grep hit at `src/seon/sci/eval.clj:1908` is a comment): `src/seon/fn.clj:3585`, `:3760`; `src/seon/test.clj:1592`; `src/seon/db.clj:4599`, `:5740`; `src/seon/call_preparation.clj:476` (`memoized-snapshot`); `src/seon/test/runner.clj:2121`; `src/seon/fn/analyzer.clj:267` (`manifest-roots`). The cache-central design (2026-09-26) names an unbounded `memoize` at `seon.render.value/throwable-safe` (`render/value.clj:424`); it is NOT present in the current tree (no `(memoize` hit in `src/seon/render/value.clj`), so treat that finding as stale until rechecked.
- `(atom ` in src: 57 sites. Derived-state holders: `src/seon/render.clj:1717`, `:1723` (per-branch render cache atom in projection state); `src/seon/sci/eval.clj:1246` (`loaded-identity` defonce); `src/seon/schema.clj:1018` (fallback counters, a justified counter); many per-evaluation capture atoms (`render/web.clj`, `render/walk.clj`, `test/runner.clj:441`) which are per-call accumulation, allowed as folds in principle.
- Counts are by grep; not classified per site beyond the above.

## Five facts most relevant to one general rule for derivation keys

1. Keys are mixed. About half of `seon.cache` kinds key by content (digests, definition entities, file bytes); the rest key by commit id, connection, or branch scope (`seon.render` `::invocation`, `seon.db/builder-digest`, `seon.db/request-view`, `seon.sci.eval/loaded-digests`, publication). A commit key is correct only when the derivation reads nothing outside that commit.
2. The one complete content-validity proof is read evidence (`seon.db/read-evidence-current?`), and it is used only by render caches. Publication, reach and health caches have no equivalent and rely on hand-chosen keys.
3. Datahike gives commit identity (`datahike.db/committed-value-identity`), an attribute-revision change tracker (`datahike.query/advance-query-cache-context`) and `datahike.db/datoms-diff` that skips shared subtrees. No root hash or merkle root was found, so content keys over whole values must come from Seon digests.
4. The block-cost miss is a key-completeness problem: publication reads more than main's commit (ownership, proof results, checkout HEAD bytes, analyzer config). A rule that keys by "the commit" fails, and "by changed digests" misses some inputs. Caching per file derivation by its own immutable inputs was the suggested direction, not measured.
5. Eviction and weighing defeat sharing in practice: status at snapshot shows 0 hits on `seon.db/behavior-digest` (1 hit / 3,064 misses), `seon.cluster.source/merge-base` (0/20), `seon.test.runner/reach-index-by-revision` (0/11, 16% of bytes), while 256-byte entries are evicted in favour of 100 MB maps. Weights do not reflect recompute cost, so the byte budget does not protect the expensive derivations.
