# Block cost: publication repeats after unrelated main writes

Status: diagnosis reproduced; implementation and parent/change proof remain outstanding.
No performance fix or production patch is claimed by this report.

## Observation

Default was running archive `816a6deb2d930f6b263b55ee9cb7f843c8fc3c43`, PID 63155,
started at 2026-10-16T01:13:28.468Z. `bin/seon status` reported ready, while MCP
`runtime_status` reported existing faults: 28 open faults, 380 errored receipts,
and 19 agent-error signatures. Neither tool was missing. No lifecycle operation,
source reload, provider call, or production definition change was made.

Root's first-after-restart observation, supplied with the assignment, was 12,798 ms,
671,547 calls and 15,931 MB allocated. This lane did **not** reproduce that cold
observation. The JVM had already been used, and restarting or clearing its shared
cache would violate the assignment. The following are warm-JVM observations,
with publication-cache misses distinguished from hits. They are not before/after
measurements of an implementation.

The exact entrance is `seon.context/current-state` on branch
`:chain-b7ab51e090d5`, agent `attempt/cec65a8d91f7`. The accompanying
[probe](probe.clj) measures that entrance with `seon.profile/measure`.
Allocation below is calling-thread allocation, in decimal MB, not retained heap.
Calls and read counters are JVM-wide and can include concurrent work.

| Observation | ms | thread MB | calls | reads | kondo runs | preview `db/with` |
|---|---:|---:|---:|---:|---:|---:|
| First lane call, branch acquisition included | 1,289.44 | 842.324 | 150,913 | 2,672 | 3 | 1 |
| Next request, first block | 1,193.60 | 655.364 | 90,083 | 2,665 | 3 | 1 |
| Same request, second block | 217.49 | 142.256 | 39,200 | 1,487 | 0 | 0 |
| Controlled request A, first block | 976.69 | 627.352 | 90,131 | 2,665 | 3 | 1 |
| Controlled request A, second block | 189.90 | 124.430 | 39,200 | 1,487 | 0 | 0 |
| Controlled request B, first block | 535.61 | 517.563 | 90,147 | 2,665 | 3 | 1 |
| Controlled request B, second block | 109.35 | 130.021 | 39,200 | 1,487 | 0 | 0 |

A and B held exactly the same branch commit:
`6ad0ddf4-438d-5f7c-976a-243c4ea9fcaf`.
Their main commits differed:
`6ad17b14-8b03-5fb0-a4a9-f2712dc29f1e` and
`6ad17b50-5f98-5d25-99ea-40988803eb50`.
Their main program-content digest was identical:
`f89f72cbc651d7fa31634fd3fd43b9813319172d963beeeb07a7905dc49ea99b`.
MCP recording itself advances main between requests. Thus a repeat in the same
request materially understates the recurring cost observed across requests.
A's miss/hit ratio was 5.14 in time and 5.04 in allocation; B's was 4.90 and 3.98.
These observations do not explain all of root's cold 10x+ gap.

## Producer and data flow

`seon.context/current-state` → `seon.context/task-sections` →
`seon.context/step-verdict` → `seon.context/step-observation` →
`seon.cluster.source/publication`.

Publication's cache key is main's **commit**, branch's changed definition digests,
proof names, task symbol and checkout root. The body computes agent ownership,
merge seeds and reach, replacement transaction data, a preview through
`seon.db/with`, candidate files through `seon.cluster.source/proven-files`, and
lint through `seon.cluster.source/lint-errors`.
Each unrelated main commit misses this cache. The unchanged branch therefore
rebuilds and analyzes candidate source for the next displayed block.

A separated measurement found:

| Entrance | ms | thread MB | calls |
|---|---:|---:|---:|
| `seon.ref/references`, held plan on attempt-start | 23.13 | 6.523 | 2,830 |
| `seon.context/step-verdict` | 871.69 | 485.848 | 58,629 |
| Whole block after that verdict warmed publication | 252.37 | 138.889 | 39,200 |

That reference probe is the held plan's references, not an assertion that every
part of the final must-read rendering was measured. Nevertheless it falsifies
reference resolution as the dominant cost in this particular warm observation.
The result does not establish the cold reference cost.

In the 871.69 ms step probe, inclusive attribution must not be summed with its
children. Self-time leaders were `seon.fn.analyzer/invoke-kondo` (3 calls,
272 ms), `seon.db/with` (1, 159 ms),
`seon.cluster.source/committed-bytes` (2, 94 ms), and
`seon.fn.analyzer/analyze` (3, 48 ms).
The work follows candidate-file analysis and preview construction on each new
main commit, rather than changed source inputs. Five reference descriptions
and a handful of proof statuses do not require this repeated analysis.

The warm-hit path still made 1,487 reads. Its profile included
`seon.test.runner/reach-closure`, `seon.test.runner/reach-names`,
`seon.cluster.source/merge-list`, and query evidence construction.
`seon.context/branch-changes` recomputes the merge selection that publication
also computes on a miss. This is secondary to repeated publication; it is not
proven to be the source of the restart-sized gap.

## Why no cache-key substitution was submitted

Replacing main's commit with `seon.test.runner/program-content-digest` alone is
not a correct fix. The current computation also reads:

- namespace stewardship and existence of steward agents on the target
  (`seon.cluster.source/agent-owned`);
- current proof results used by `seon.cluster.source/merge-seeds`;
- file content at checkout Git HEAD (`seon.cluster.source/committed-bytes`);
- analyzer configuration and dependency analysis.

The existing key also omits some of these changes, notably checkout HEAD and
new result evidence when the changed definition digests stay fixed. This is a
source dependency audit finding, not a reproduced stale-verdict bug. It should
not be converted into a new refusal or a guard.

The smallest direction is to keep current selection at its owner and cache
expensive file derivations by their actual immutable inputs. The file bytes,
selected declarations, analyzer configuration and resolved dependency facts must
participate. Reusing an entire publication verdict under a guessed subset of
these dependencies is not justified. `seon.cache/lookup` already owns bounded
content caching; no new cache or freshness registry is needed.

A correct change must prove both reuse after unrelated evaluation recording and
recomputation after changed proof results, relevant declarations, stewardship,
checkout source or analyzer inputs. The end-to-end assertion must measure the
parent and candidate `seon.context/current-state` call in the same isolated run,
with equal returned block data for unchanged inputs. Cold and warm require
separate observations. The probe here is evidence for that proof, not its
replacement. No invented timing threshold is proposed.

## Ownership, validation and limits

At entry, `chain-reds` held `src/seon/cluster/source.clj`; `ref-at-tools` held
`src/seon/ref.clj` and related callers. The only claim taken was this report's
directory. At the later ownership check, chain-reds had released its claims;
there is no continuing collision claimed as a blocker. No other lane's files,
sessions or agents were changed or messaged.

The investigation stopped at a design gap: a safe complete content key for the
publication derivation was not established. No speculative patch was written,
no implementation was installed, and the requested performance fix remains open.
There is no parent/change gain, cold rerun, retained-memory measurement or green
runtime-test claim. No foreign code failure prevented these probes.

`clj-kondo --lint src script test --cache false`: 10,903 ms, **0 errors**,
1,800 warnings, exit 2 for warnings. The check was against the shared working
tree; no warning was treated as a new finding from this documentation-only slice.
The report and probe change no gate input, so they need no runtime test selection.
No source or test lines changed.

Dependency pins inspected: Datahike
`88d05cf830d5d39eddf8e22be815889da7448bd6`, Malli
`dd13ff81139159778edb0a67b491eccc0baa991d`, clj-kondo
`eb245d8260736ca463cda85219b6fc3ae73699c3`.
Malli's `malli.core/-instrument` owns argument and result validation; it was not
modified or disabled for these probes. Existing `seon.cache/lookup` retains
values under a configured byte budget and avoids retaining thrown derivations.
