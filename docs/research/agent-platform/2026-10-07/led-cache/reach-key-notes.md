---
type: research
status: landed
date: 2026-10-08
lane: led-cache
---

# led-cache: the reach-declarations key (LR-CACHEa) and the selection read loop

What exists: `seon.test.runner/reach-declarations` reads ten attributes (`seon.db/datoms :aevt`, one read
each) into symbol-to-entity, entity-to-digest and entity-to-facts maps, cached in the `seon.cache` kind
`::reach-declarations` under `reach-revision-key`: `[connection-id generation conservative-revision
(reach attribute revisions)]`. Datahike's revisions are only comparable inside one connection
generation (`reference-code/datahike/src/datahike/query.cljc:3040` `source-context-unchanged?`, pinned
`e5453d96`), so every agent branch, test world and member derives again.

## 1. Where root's 20k-read loop was

It is not `reach-declarations` (ten reads per miss). Root's counts (20,042 `seon.db/datoms`, 20,042
`seon.fn/read-or-throw!`) match `seon.fn/call-reach`: `my.test/check` ignores `:seon.test/named` (it is
no member of `:my.test/check-request`) and checks `seon.test/changes`, every declaration differing from
the published commit. With thousands of changed identities, selection seeked `:avet` once per seed for
`:seon.fn/calls`, once per frontier name for `:seon.test/subject`, and `seon.fn/declared-callers` once
per seed for each of five indexed declared-edge attributes holding at most 310 datoms. Seven reads per
changed declaration.

Reproduced on default at 953efe25d, JVM mode, read-only:
`(seon.fn/gate-sets {:seon.db/db db :seon.fn/seeds <5,672 function symbols>})`: 39,713 reads,
595,936 armed calls, 632 ms, 1.19 GB.

Task: `seon.fn/call-reach-seeks-once-per-changed-declaration` (runtime f302605dcf67, copied to
`resources/seon/tasks/seon.fn.edn`).

### The fix (landed in this lane)

Each declared-edge attribute and the subject attribute is read once by an `:aevt` walk filtered by the
targets; the caller seeks stay per target (proportional to the change). EQUIVALENT: the same filter
`(values (:v datom))` applied to the same attribute's datoms; only the index path differs.

Measured costs on default (953efe25d) that decided it:

| read | cost |
|---|---|
| walk of all eight declared-edge attributes | 0.25 ms, 461 KB |
| one value seek on the five indexed ones + three unindexed walks (the old 1-seed path) | 0.11 ms, 123 KB |
| `:seon.fn/calls` walk (105,799 datoms) | 24-28 ms, 40 MB |
| `:seon.fn/calls` seek per target | ~8 µs, ~12 KB each (1,000: 8 ms/12 MB; 5,672: 44 ms/69 MB) |

The calls walk only beats seeks past about 3,000 targets, so calls keep their seeks: one per changed
declaration, not seven. A single-seed selection pays +0.14 ms, +340 KB for the walks.

Paired throwaway JVMs (`first-check-probe.clj`, `bin/seon check … --include-long`), `gate-sets` over every
function symbol of the throwaway program (5,670 seeds, measured twice, warm row second):

| | reads | ms (cold / warm) | allocated (cold / warm) |
|---|---:|---:|---:|
| parent 953efe25d | 39,705 | 919 / 636 | 2.17 GB / 1.37 GB |
| change | 5,691 | 803 / 442 | 1.67 GB / 0.86 GB |

The remaining 442 ms / 0.86 GB is `pull-many` of thousands of entities: `seon.fn/identity-names` (twice),
the `requested` pull in `declared-callers` and the final `[:seon.test/sym]` pull. Raw
`datahike.api/pull-many` of 5,672 entities with `[:db/id :seon.fn/sym :seon.test/sym]` costs 498 ms and
161 MB (seon.db's wrapper adds 40 MB), about 28 KB and 90 µs per entity, against a 3.9 ms, 3.8 MB walk of
`:seon.fn/sym` and `:seon.test/sym`. That per-entity pull cost is Datahike's and is recorded below as a
finding; replacing identity-names with walks would cost a one-seed check about 2 ms and 3 MB, so it is not
in this slice.

Regression: `seon.test-reaching-test/selection-reads-each-declared-edge-attribute-once` counts the reads
`gate-sets` captures in `seon.db/*read-evidence-sink*` for 1 and 20 uncalled seeds: the difference is 19
(one caller seek per further seed). On the parent it is 7 × 19.

## 2. What a one-test check costs today (throwaway JVM, HEAD 953efe25d)

`first-check-probe.clj` defines `sq`/`sq-test` on a fresh agent, checks it (cold), again (warm), then on a
second fresh agent branch:

| pass | wall ms | allocated | armed calls | reach-digests |
|---|---:|---:|---:|---|
| cold, fresh JVM | 686-924 | 1.34-1.36 GB | 192k | 2 calls, 387 ms |
| warm, same agent | 114-138 | 146-163 MB | 57k | 1 call, 0 ms |
| second branch | 368-474 | 536-545 MB | 190k | 2 calls, 80 ms |

On default, `reach-declarations` itself derives in 86-92 ms, 126 MB, 18,630 armed calls (8,146 symbols,
12,063 digests, 3,403 schema forms, 8,142 call rows); a one-test `reach-digests` miss is 110 ms, 172 MB.
Each check derives it twice: once on the agent's value at selection (`seon.test/run`) and once on the
tested world's value at recording (`seon.test.runner/commit-results!`), a request branch over an
in-memory overlay whose connection is new per request. The rest of a branch check's 540 MB is outside
reach (the pass's context acquisition after a definition, the recording writes): top calls are
`seon.db/transaction-report-datom?` (~50k) and `seon.schema/projection-cache-value` (~29k). The
interim target (first check on a new branch under 400 ms and well under 100 MB) is not reachable by the
reach key alone.

## 3. The key: design options (LR-CACHEa), stopped for a ruling

Requirement: an exact content identity of the ten reach attributes' datoms (entity ids included: the
cached value maps symbols to entities), equal on two branches of one program, changed by any reach
write, unchanged by an unrelated write, and computable without reading the attributes.

What the pinned dependencies offer, verified:

- Datahike's committed value carries `:cache-context` (`db.cljc:394-460`). A connection opens with
  `{connection-id generation commit-id}` and no revisions (`connector.cljc:383-386`); each commit
  advances the revisions of the attributes its transactions touched to that commit's id
  (`writer.cljc:250-262`, `query.cljc:2614`); schema or unknown changes advance the conservative
  revision and keep the old attribute revisions (`db.cljc:423-442`). A fresh agent branch on default
  shows exactly this: no conservative revision, revisions only for what it wrote; the cluster shows a
  conservative revision from adoption.
- `d/since` is a filter predicate over the full index (`db.cljc:729-735`), so a delta read costs a full
  attribute read: no cheap changed-range API (as pass-waste found).
- Index nodes (`org.replikativ.persistent_sorted_set` 0.4.137) carry addresses (`Branch.addresses`,
  `walkAddresses`), but branches buffer pending operations (`Branch$BufState`, `depositInto`), so a
  subtree's address does not alone identify the content its ancestors' buffers will apply.

Options, simplest first:

1. **Content digest by scan.** Read the ten attributes as today (one walk each), digest their datoms, and
   key the derived value, `::reach-graph` and `::reach-index` by that digest. Guarantee: exact, entity ids
   included. Cost: every new branch still pays the read (~90 ms, ~126 MB on default) plus a hash of ~45k
   datoms; memory is shared (one retained value per distinct program instead of one per branch, which
   fixes the 187-205 MB retained at 0% hits). Sacrifice: no time or allocation win per check.
2. **Recommended: cross-connection revisions.** Make every revision a commit id whose meaning holds
   across connections, then canonicalize in Seon:
   - Datahike fork (two lines, `connector.cljc` connect and `db.cljc` `advance-cache-context`): a
     connection opens with `:datahike.cache/conservative-revision` = its opening commit, and an unsafe
     advance clears the attribute revisions. Then every committed value satisfies: an attribute's datoms
     equal its datoms at its revision's commit, or at the conservative commit when it has none. Datahike's
     own cache compares only within a connection generation, so its behaviour is unchanged.
   - Seon: one `seon.cache` kind keyed by commit id holding that commit's canonical map (attribute →
     commit whose content it equals), put when a value is keyed and when a branch is created from a
     source value. A value's canonical map is its revisions over the canonical map of its conservative
     commit (or that commit itself). `reach-revision-key` becomes the canonical entries of the reach
     attributes: no connection id, no generation.
   Guarantee: exact by induction on commits (each entry names a commit whose content the attribute
   equals). Cost: O(attributes) map work per keyed value, no reads; a branch of an unchanged program is
   a hit; the recording world (a new overlay commit per request) still misses unless its reach is carried
   from selection. Sacrifice: a Datahike submodule change on the connect path (boot-critical, proven by
   publication and adoption), the branch owner in `seon.cluster.registry` putting the source's map, and
   the same key then wanted by `seon.sci.eval/loaded-digests`, `seon.render/call-reach-key`,
   `seon.call-preparation` and `seon.db`'s projection key, which all key by connection today.
3. **Structural index identity.** A Datahike/persistent-sorted-set seam answering, for one attribute
   range, the addresses of the covering subtrees plus the buffered operations their ancestors hold for
   that range. Guarantee: exact by node immutability. Cost: depth × fanout node visits per key, no
   datom reads. Sacrifice: dependency internals (buffered branches) in a fork, the largest change.

Option 2 is the row's own "seeded from the loaded commit and advanced from changed declarations" in
Datahike's terms, with the advance done by Datahike's writer instead of a second Seon tracker. It needs
owner approval for the submodule change; nothing of options 1-3 is implemented here.

### The `:seon.sci.eval/loaded-digests` 50% hit rate

`seon.sci.eval/overlay-plan` calls `loaded-digests` twice per acquisition: on the loaded program value
(keyed `[store commit]`, a hit after the first) and on the agent's current value (`held`), whose commit id
changes with every pass's settlement, so it misses every time and reads every function digest again.
One hit and one miss per acquisition: 50%. Within one connection, keying `held` by the revisions of
`:seon.fn/sym` and `:seon.program/definition-digest` instead of the commit id would make unrelated
passes hit; across branches it needs option 2's key.

## 4. Findings

- `my.test/check` silently ignores `:seon.test/named`: the request map is open, and the check falls back
  to every changed declaration. Root's one-test probe ran that walk. Belongs to `my.test`; not filed as a
  task here because the request schema's openness is a contract decision (accretion) for its owner.
- Datahike `pull-many` costs ~90 µs and ~28 KB per entity for a three-attribute selector (5,672
  entities: 498 ms, 161 MB raw). Every selection of a large change pays it three to four times.
- A check derives reach twice (selection and recording) on different values of one program.

## Design check

The ledger row framed the 20k loop as reach-declarations' miss; the data put it in `seon.fn/call-reach`.
The read fix held against the code: the filter is unchanged, and the walk/seek choice follows the
measured attribute sizes rather than a threshold. The key design did not hold as a runner-only change:
no installed seam gives a cross-connection identity without either a scan (option 1) or a dependency
change (options 2 and 3), so LR-CACHEb is stopped with these options.

Which step of the one happy path it serves: the agent's `my.test/check` after a change. It deletes seven
reads per changed declaration down to one; it adds no mechanism.
