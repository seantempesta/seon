---
type: issue
status: open
severity: friction
tags: [issue, render, web, performance, class/n9, wave/namespace-page-performance]
---

# Attribute the seven-second core namespace-page derivation

## Problem

Core namespace pages take several seconds even after the declaration bridge
cost is absent. The 2026-08-10 route walk tentatively attributed their
12-18-second latency to the same declaration-population fallback that stalled
`/data`; a direct before/after measurement refutes that attribution.

## Evidence

Cluster `db-decode-scratch`, current loaded source after `f098bbdc7`, one
read-only request:

```text
GET /ns/seon.db  200  908444 bytes  7.647 s
declaration fallbacks: total=1, seon.db=1, seon.schema.datahike=0
```

Before the bridge change, the same lane measured the page at 9.499 s on the
scratch cluster and 7.383 s on `default`, with only 8 and 1 declaration
fallbacks respectively. The fix moved `/data` from 7-8 seconds to about 130 ms
but did not materially move this page. Declaration resolution therefore
cannot explain the namespace-page floor.

The earlier full route walk remains the broader symptom evidence:
[ui-truth-2026-08-10.md](../../prds/sci-execution-runtime/research/ui-truth-2026-08-10.md).

## Owner

The namespace-page derivation under `seon.render`. Start with a measured cost
profile of the one route operation; do not reopen `seon.db` decoding or the
declaration bridge without a new fallback-count falsifier.

## Acceptance

- Attribute the wall time to named operations with measurements that account
  for most of the observed total.
- Make a repeat request at an unchanged basis serve in the same order of
  magnitude as the 19 ms agent namespace page, or record the ruled reason it
  cannot.
- Add one recurring route-level cost proof for the identified class.

## Measured partial resolution, 2026-08-10

Implementation commit `1a2dabeba` derives the agent render profile once from
the render pass's immutable database value and carries it through every page
unit. Previously, each unit independently called `config/effective`; those
reads became retained render-call evidence and were replayed at the next
basis.

The route profiler forced the retained package for `seon.db` one basis stale,
then measured the actual HTTP request through `render-pass`, the neighborhood
walk, and HTML projection. A representative 6.327-second pre-fix pass broke
down as follows (nested totals overlap):

| Seam | Calls | Cumulative time |
| --- | ---: | ---: |
| namespace neighborhood | 1 | 6.27 s |
| `render/render-call` | 178 | 3.20 s |
| retained read-evidence validation | 178 | 3.16 s |
| `render/request-profile` | 334 | 1.32 s |
| `config/effective` | 111 | 1.28 s |
| `db/pull` | 3,858 | 1.91-2.26 s |
| `db/datoms` | 21,560 | 1.03-1.24 s |
| program-graph queries | 7 | 17-49 ms |

Blob reads and serialization were below the material seams. Program-graph
queries were also negligible, and the declaration-fallback attribution stays
refuted.

After the change, the same instrumented stale-basis render made one
`config/effective` call in 8.8 ms. The 178 retained read-evidence checks fell
to 35.8 ms, the 178 render calls to 84.2 ms, and the whole neighborhood to
3.279 seconds. The remaining dominant cost is the walk's per-node database
work: 3,859 pulls and 21,560 datom reads. That is a separate unsettled class,
so this issue remains open.

Raw live HTTP timings on scratch cluster `ns-perf-scratch` were:

| Namespace page | Before, stale-basis derivation | After, stale-basis derivation | After, unchanged-basis reuse |
| --- | ---: | ---: | ---: |
| `/ns/seon.db` | 8.218 s | 3.168 s | 16.5 ms |
| `/ns/seon.fn` | 3.467 s | 0.943 s | 17.0 ms |

The recurring count proof is
`test/seon/render/web_performance_test.clj`: two registered pages may ask for
the profile repeatedly, while one render pass is permitted exactly one
effective-config derivation. Its focused run passed 1 test and 4 assertions.
The changed-files gate ran 114 tests and 684 assertions; this regression
passed, but the gate ended red on the existing
`the-message-appears-on-the-page-wire-test` assertion and
`thinking-stream-morphs-into-the-settled-session-transcript` completion
backstop. Their cause was not attributed in this lane.
