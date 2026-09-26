---
type: issue
status: open
created: 2026-09-26
severity: blocker
tags: [issue, memory, test-runner, heap]
---

# Default ran out of heap while a timed-out test member stayed live

## Sighting (2026-09-26 14:20Z, default at the commit before 5bed888d0)

Root ran test requests one at a time (`tmp/orchestrator/verify-1420.txt`). A
dogfood lane ran tests through MCP at the same time (`word-frequencies` FAIL in
`seon.log`).

1. `seon.call-preparation-test`: passed in 46.8 s.
2. `seon.cluster.host-test`: `a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster`
   did not exit within its 110.6 s remainder. The result said "thread 1543 remains
   live and keeps its branch until it exits" (`src/seon/test.clj:303-309`).
3. `seon.cluster.mcp-test` got no PREPL output within the bound (14:20:31Z).
   So did `seon.cluster.registry-test` (14:23:01Z).
4. `seon.log:105636-105639`: `OutOfMemoryError: Java heap space`, a
   15,996,101,972-byte dump, and the JVM ended (`-XX:+ExitOnOutOfMemoryError`).

The dump is lost. `-XX:HeapDumpPath=data/heap` was relative, and the JVM's
working directory was the program archive `data/source/<sha>/`. That archive
has no `data/heap` directory, so the JVM wrote a file with that name, and
`start --head` pruned the archive. This part is fixed in c2b757cb3: the
operator passes `<root>/data/heap` as an absolute path at launch.

## What the live JVM shows (pid 7000, 5bed888d0, idle)

- `GC.class_histogram` at 14:3xZ: 1.36–1.45 GB live of a 10.2 GB maximum.
  The largest classes are `Object[]` 326–363 MB, `byte[]` 309–320 MB and
  `datahike.datom.Datom` 200–207 MB (5.0–5.2 M instances). There are 5
  `CachedStorage`, 5 `LocalWriter`, 24 `datahike.db.DB` and 20 `sci.impl.opts.Ctx`.
  The raw samples are in `tmp/platform-boot-heap-opus/histo-{0,1}.txt`.
- No test request has run since the restart, so there is no before/after
  pair yet.

About 8.5 GB was allocated and retained within minutes of step 2. That is a
runaway allocation or retention during the requests. It is not a slow leak.

## The retention path to verify first

A member body that outlives its bound stays alive on purpose
(`src/seon/test.clj:287-309`). The `FutureTask` closes over the member's
custody: the branch connection with its Datahike `CachedStorage` node cache
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:461-468`,
bounded by node count and not by bytes; see
`the-index-cache-retains-4gb-of-expanded-schema-shape-forms.md`), the seeds
and the SCI context. `watch-exit!` joins the thread and releases the member
only after it exits.

Within one request, `seon.test/run` stops after an unfinished member
(`src/seon/test.clj:2419-2424`, `:member-unfinished`). Nothing stops the next
request, though. mcp-test and registry-test each built a new world while
thread 1543 still held its world. AGENTS.md says "prevent overlap until exit",
and here the process overlaps work across requests.

The stuck body itself (`test/seon/cluster/host_test.clj`, `propose-merge!` →
`seon.test/changes`, `src/seon/test.clj:1769`) reads
`seon.cluster.source/database` (`src/seon/cluster/source.clj:291-302`). Each
call rebuilds `schema/projection-from-database` for the base commit. Nothing
memoizes that in the function itself.

## Protocol (root runs it; no dump needed)

```
PID=$(bin/seon status | grep -o ':seon.boot/pid [0-9]*' | head -1 | cut -d' ' -f2)
tmp/platform-boot-heap-opus/heap-watch.sh $PID host 60 5 &
bin/test-check default --test seon.cluster.host-test/a-hosted-agent-proposes-a-merge-and-root-receives-it-on-the-cluster
# then, still sampling, one ordinary request:
bin/test-check default --ns seon.cluster.registry-test
```

Compare `watch-host/histo-*.txt` against `tmp/platform-boot-heap-opus/histo-1.txt`.
If `used` in `watch-host/heap.txt` rises past 6 GB, take
`jcmd $PID GC.heap_dump -gz=1 <root>/data/heap/host-member.hprof.gz` before
the JVM dies. This needs root's OK (it pauses the JVM). The dump would name
the retainer.

## Acceptance

- The retainer is named from a histogram pair or a dump, with the owning path
  given as `file:line`.
- A member that does not exit cannot make later requests exhaust the heap.
  Either admission refuses while an unfinished member holds its world, or that
  world's retained size is bounded. One regression covers this.
