---
type: landing
status: landed-with-limits
lane: leak-fix-2 (Opus 5.5)
created: 2026-09-23
diagnosis: docs/research/agent-platform/datahike-memory-retention-2026-09-23.md (84d984be9)
---

# Released branch connections no longer pinned by Seon's SCI caches (2026-09-23)

**What the system and Datahike already do.** Datahike frees a branch connection's node cache
(`CachedStorage`, `reference-code/datahike/src/datahike/index/persistent_set.cljc:460-468`) as
soon as nothing reaches a database value of that connection; release is correct
(`connector.cljc:468-560`). Seen with `(lane-leak2.probe/sample)` and
`lane-dh-mem.walk/path-to` from each cache to a `CachedStorage` no registered connection owns
(`datahike.connections/*connections*`). **Smallest composition:** Seon's two memo caches keep
their benefit and hold data, not values: key by `[store-identity branch]`, cache no database
or loaded program in the base, and copy JVM Vars into SCI at their root.

## Per cache: benefit, before/after, seam

| cache | built for (`5f2aa93f4`, landing `lane-sci-program-revisions-2026-09-23.md`) | held before | now |
|---|---|---|---|
| `refusal-recording-cache` | record one program's acquisition refusals once per recording target, one transaction (the unmemoized path took > 3 min on a mismatch) | key `[program Connection]`; the released Connection's metadata carried the cluster world. Live 63253: 119 keys, all Connection objects | key `[program [store-identity branch]]` (upstream `datahike.store/store-identity`, upstream conn-id `[store-id branch]`); the cell keeps only the two recorded flags. After gate: 31 entries, 0 Connection keys, walk exhausted at 3,272 objects, 0 retired storages |
| `base-context-cache` | memoize base SCI context derivation: derive 687 ms vs hit 0.065 ms | snapshot held `:seon.db/db` and `::loaded-database` (a `commit-as-db` on the deriving branch's store), and SCI Var `seon.db/*read-database*` whose root was the deriving evaluation's thread binding | the cached base holds none of the three; `copy-base-ctx` supplies the caller's database and loaded program; `copy-host-var` binds the SCI copy to the JVM root. Hit 0.12-0.17 ms on 63253 |

Neither cache can be deleted outright: Datahike has no SCI context cache, and its query cache
does not know that refusals were recorded. The deletion candidate is the refusal cache: derive
"already recorded" from the branch's error facts by signature (then members inherit the
parent's record and write nothing). That needs `seon.error`'s signature at the call site; not
done here.

**Braids removed.** (1) A cache key complected identity with a live object (Connection plus
its metadata world); now it is data. (2) A cross-branch memo complected program identity with
one branch's database and loaded-program value. (3) SCI's `copy-var*` (`reference-code/sci/src/sci/core.cljc:137`,
`(new-var nm @clojure-var …)`) complected a Var's root with the copying thread's binding.
Each part now has one role: the key names, the base holds the program, the copy holds the caller's world.

## Changed paths

- `src/seon/sci/eval.clj`: `connection-identity`, `copy-host-var` (4 call sites), `derive-base-ctx`, `copy-base-ctx`, `base-ctx`, `record-refusals-once!`.
- `src/seon/cluster/store.clj`: `release-branch!` drops `:seon.sci.eval/projection-state` from a finally released Connection's metadata.
- `test/seon/cluster/branch_release_retention_test.clj` (new): three isolated acquire/read/evaluate/release cycles, `System/gc` ×3; weak references to each branch's `CachedStorage` are cleared; no refusal key holds a Connection; no cached base snapshot holds `:seon.db/db`/`::loaded-database`; no SCI Var root in a cached base is a `DB`.

Net src: +44 lines (+67 −23), mostly docstrings citing the seams. Test: +59 (new file).

## Evidence

Regression, default pid 63253:
- `ec2d8869b848`/`a400f9acb438` red on my own key typos (evaluation result key), weak-reference assertion already passing.
- stale pre-fix cells in the cache → `…:46` red "a memoized base context holds no database value" (the check detects the old shape).
- `74cc5a7f6b71` (after evicting stale cells): pass 9 / fail 0, 2,158 ms.
- pid 94821 `eae05005ca2d`: pass 9 / fail 0, 2,191 ms (store-identity key, before `copy-host-var`).
- pid 5070 (final source, loaded at boot): the Var-root assertion is unrun; every test request refuses (see Blocker). REPL unit probe of the final mechanism (0.7 ms):
  `(binding [seon.db/*read-database* db] (.getRawRoot (copy-host-var #'seon.db/*read-database* ns)))` → not a DB (JVM root nil); SCI's `copy-var*` under the same binding → a DB; a function Var copies identically; `(connection-identity conn)` → `[#uuid "224560ae-…" :cluster-default]`.

Stale-state eviction on 63253 (the reload keeps `defonce` caches): evicted 119 Connection-keyed refusal entries and 4 + 3 base cells through `clojure.core.cache.wrapped/evict` (1 ms). `jcmd GC.class_histogram` before → after eviction: `CachedStorage` 22 → 7, `Connection` 134 → 7, Datoms 17.0 M → 8.6 M, old gen 4,488 → 3,032 MB.

Heap gate, `bin/test-check default --ns seon.sci.eval-test` (60-74 members), `jcmd GC.class_histogram` (full GC) + `GC.heap_info`:

| JVM, source | point | Datom | DB | CachedStorage | Connection | old gen |
|---|---|---|---|---|---|---|
| 63253, key+db fix | before | 7.78 M | 77 | 6 | 7 | 2,416 MB |
| | after 1 / 2 / 3 | 10.17 / 11.58 / 10.17 M | 110 / 104 / 84 | 11 / 14 / 12 | 12 / 15 / 14 | 2,880 / 3,168 / 2,944 MB |
| 94821, + loaded-db strip, store key | baseline (boot) | 3.89 M | 15 | 2 | 2 | 1,120 MB |
| | after 1 / 2 / 3 | 5.91 / 6.27 / 6.74 M | 27 / 29 / 29 | 10 / 12 / 12 | 12 / 14 / 14 | 1,632 / 1,704 / 1,792 MB |

Storages plateau (10 → 12 → 12) instead of +1 a minute; old gen still grew +72/+88 MB per
cycle on 94821. The retained storages at 94821's plateau were traced: `base-context-cache` →
`sci.impl.opts.Ctx` → env → `sci.lang.Var seon.db/*read-database*` → retired `DB` (branches
`agent-f6fbca9e9cbc`, `agent-5c6f819cfe8c`). That is what `copy-host-var` removes; its gate
row is owed (Blocker). Gate runs 2-3 on 94821 refused at recording (`seon.test/green? refused
result`, test runner, not this lane).

Contracts compile against default's carried projection
(`seon.contracts-compile-test/check` over the three files): 0 findings, 149 ms.

## Timings over one second

| operation | ms | proportional to / why |
|---|---|---|
| `bin/seon init --dev default --changed src/seon/sci/eval.clj` | 21,706; 15,956 | `full-source-refresh!` 13.6 s, `analyze-rows` 10.6 s: adoption of one file re-analyzed the whole program. Over 10 s: a publication defect (not this lane's files) |
| `bin/seon init --dev … test file` | 2,371-4,968 | publication of one test namespace; same whole-program path |
| `bin/test-check --ns seon.sci.eval-test` | 48,946-121,222 | 60-74 members each acquiring an isolated branch; over 10 s, test-overhead lane's class |
| regression `--test` | 2,158-4,114 | three isolated acquisitions + 3 full GCs of a 1-4 GB heap (declared bound 15 s) |
| `lane-leak2.probe/sample` | 2,433-5,632 | diagnostic reflective walk capped at 3 M objects; never in the system path |

## Blocker (out of scope, reported)

Since `ac3d8b2fd` (08:58), `src/seon/fault.clj` requires `[seon.flow :as-alias flow]`; the
indexer records it in `:seon.ns/requires` (`seon.fault` row: `"seon.flow"` among requires, no
`:as-alias` fact), and `seon.flow` requires `seon.fault`. `acquire-program!` then refuses
"Program acquisition found a namespace binding cycle" (`seon.fault → seon.flow → seon.fault`),
so every `bin/test-check` request on pid 5070 refuses in 1.7-3 s. Owner: the namespace indexer
(an `:as-alias` require is not a load dependency) or `fault.clj`.

## Limits

- Parent-vs-self hot-path probe: no parent timing on the same JVM (no redefs in default); the
  base-ctx hit is 0.12-0.17 ms vs 0.065 ms recorded at introduction on another root.
- The final `copy-host-var` gate row and the Var-root assertion are unrun because of the Blocker.
- `seon.sci.eval-test` shows 18 reds (fixture write refusals, branch-write refusals, two
  duration overruns); none reads the changed members; no pre-change record exists (0 runs of
  that namespace before 14:37Z), so "unrelated" rests on the messages, not on a before/after.
- The program key in both caches still uses the fork's `:cache-context` revision basis
  (`revision-basis`, pre-existing); the coordinator ruled that basis fork-only. Re-keying it on
  upstream commit id is the next slice.

RESET NEEDED: no.
