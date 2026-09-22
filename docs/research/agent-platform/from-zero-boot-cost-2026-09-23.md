---
type: reference
status: measured; fact-finding lane, no source edits
created: 2026-09-23
tags: [agent-platform, boot, reset, indexing, performance, audit, seconds-not-minutes]
---

# From-zero boot cost: where 170 seconds go (2026-09-23)

Lane `from-zero-boot-cost`, fact-finding only; no source edits. This is also the
**boot, reset and indexing** section of the owner's audit of slow assumptions
(2026-09-23: "by design most ops should be sub second"). For each phase the question
is why it is not sub-second, and what can be deleted.

## Summary

- **75% of a from-zero boot is one quadratic loop in Seon's write validator.** The
  whole program lands as ONE Datahike transaction of 441,788 datoms. Datahike runs
  Seon's `:datahike/validate-report` callback on the writer thread
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1206,1224,1276`).
  `seon.db/write-owned-values-error` then, **for each owning root**, rescans the
  whole `:tx-data` and walks each datom's owning ancestors
  (`src/seon/db.clj:3163-3172` at HEAD `bfe3445f8`). That is roughly 6.5k roots ×
  442k datoms. Measured: about 96 s of the 170 s, and 127 s for the whole
  validation. The loop only decides whether to run the arity check
  (`db.clj:3482`), and on a from-zero publication the answer is always yes.
- **Every start, warm or cold, compiles every dependency from source.** Warm and cold
  alike spend 17–19 s before `seon.cluster.boot/start!` is entered, and
  `:seon.boot/ready-ms` does not count that time (`boot.clj:223,255`). The repository
  already builds a dependency class cache (`dev_cache.clj`,
  `target/dev-dependency-classes/`). `bin/test` uses it; `bin/seon start` does not
  (`script/seon/operator.clj:307-309`). Putting the cache on the classpath measured
  16.8 s → 7.9 s.
- **Reset destroys what it then rebuilds.** `bin/seon reset --force` deletes the whole
  store (`script/seon/operator.clj:350-353` → `src/seon/cluster/store.clj:461-473`)
  and pays the 170 s re-index. A fresh cluster forked from the existing `current-src`
  branch in the live JVM measured **4,256 ms**.
- **A schema-resource change does not need a from-zero boot. The installed path
  adopts it incrementally.** On an open store, adding an attribute published in
  7,064 ms and adopted onto the cluster branch in 1,798 ms. Retiring a writerless
  attribute published and adopted in 7,119 ms with no refusal. Retirement leaves the
  Datahike ident installed (see Q1).

## Method

- Subject: a frozen `git archive` of committed HEAD `bfe3445f827a7d59ee94bac9c04988b36aea1587`
  in `tmp/fzb-source`, with `reference-code` symlinked to the checkout (submodule
  gitlinks at HEAD: datahike `41c79c1a7`, clojure `b18d3adc5`). No other lane's
  working-tree hunk was included. Root: fresh empty `tmp/fzb-root`, cluster `fzb`.
- Profiler: JDK Flight Recorder, injected without a source edit. The launcher passes
  its environment to the child (`script/seon/operator.clj:252-267,315`), so
  `JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=…/cold-%p.jfr,settings=profile
  -Xlog:gc:file=…"` reached the boot JVM. The recording was dumped with
  `jcmd <pid> JFR.dump` after readiness. The dump holds 14,706 `jdk.ExecutionSample`
  events. They were bucketed per second and per thread by the innermost matching Seon
  or dependency frame (`tmp/fzb-jfr/agg.py`, `phase.py`, `win.py`).
- Cross-check: a `jstack` loop over the main thread every ~1.6 s
  (`tmp/fzb-jfr/main-samples.txt`), plus one full `jstack` during the long phase
  (`tmp/fzb-jfr/full-1.txt`).
- Load: 18-core M5. `uptime` was 11.30 at start and 10.63 at readiness. At 95 s the
  validating writer thread had used 45.9 s of CPU over the ~47 s it had been
  validating, so it was about 98% on-CPU and not starved. The dominant phase is
  single-threaded CPU work, so load did not distort the ranking. The run was not
  repeated. The 170 s here matches the recorded 89–181 s spread.
- Evidence files (kept, disposable): `tmp/fzb-jfr/` (`cold-dump.jfr` 22 MB,
  `warm-dump.jfr`, timelines, GC logs).

Exact commands:

```sh
git archive HEAD | tar -x -C tmp/fzb-source   # then empty submodule dirs removed and reference-code symlinked
JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=$PWD/tmp/fzb-jfr/cold-%p.jfr,settings=profile,dumponexit=true -Xlog:gc:file=$PWD/tmp/fzb-jfr/gc-cold-%p.log" \
  tmp/fzb-source/bin/seon --root tmp/fzb-root start fzb        # exit 0
jcmd 15573 JFR.dump filename=$PWD/tmp/fzb-jfr/cold-dump.jfr
jfr print --events jdk.ExecutionSample --stack-depth 400 tmp/fzb-jfr/cold-dump.jfr > tmp/fzb-jfr/samples.txt
tmp/fzb-source/bin/seon --root tmp/fzb-root down               # warm: same root
JAVA_TOOL_OPTIONS=… tmp/fzb-source/bin/seon --root tmp/fzb-root start fzb
tmp/fzb-source/bin/seon --root tmp/fzb-root start fzb2         # new cluster, same live JVM and store
```

## TIMINGS (own probes)

| probe | result | load |
|---|---|---|
| from-zero `start fzb`, frozen HEAD `bfe3445f8` | exit 0; **ready-ms 170,338**; wall 191.2 s; PID 15573 | 11.30 → 10.63 |
| warm restart, same root (`down`, `start fzb`) | exit 0; **ready-ms 11,092**; wall 28.7 s; PID 19207 | 9.98 |
| new cluster on the open store (`start fzb2`, same JVM) | **ready-ms 4,256**; wall 4.4 s | 8.54 |
| `clojure -M:dev:test -e "(require 'seon.cluster.boot)"`, source only | 16.85 s | 10.21 |
| same, with `target/dev-dependency-classes/24de0fa5…` first on `:paths` | **7.93 s** | 10.21 |
| incremental schema **add** (`refresh-source!` of one resource, no cluster) | 7,064 ms; attribute installed on `current-src` | ~9 |
| adoption of that add onto cluster `fzb` | 1,798 ms; attribute installed on the cluster branch | ~9 |
| incremental schema **retire** (resource restored; publish and adopt) | 7,119 ms; no refusal; schemas 3,297 → 3,296; ident retained | ~9 |
| store census after from-zero (`current-src`) | 454,013 datoms, 39,030 entities, 9 transactions (one of **441,788** datoms, then 8,185, 3,904); 1,213 attributes, 136 component attributes; 4,432 fns, 2,082 tests, 414 ns, 708 files, 374 issues; read in 587 ms | — |
| from-zero store size | 166 MB | — |
| GC during cold boot | 213 pauses, 1,196 ms total; peak heap 998 MB; RSS after 4,545,008 KiB | — |

## Cold phase breakdown (from-zero, JFR wall seconds from recording start)

Recording start is 20:46:45.49Z. The launcher started at 20:46:44.11Z, and
`start!` began at 20:47:04.8Z (readiness 20:49:55.1Z minus 170,338 ms).

| # | phase (owner) | JFR t (s) | ms | proportional to | why not sub-second |
|---|---|---|---|---|---|
| 0 | bb launcher + `clojure` CLI classpath (a tools.deps JVM, PID 15580, because the fresh archive has no `.cpcache`) | −1.4–0 | ~1,400 | deps graph, once per checkout | cold archive only; warm checkouts reuse `.cpcache` |
| 1 | JVM start + `(require 'seon.cluster.boot)`: Clojure compiles every dependency and first-party namespace from source (`script/seon/operator.clj:290`) | 0–19.3 | **19,300** | whole dependency closure + whole program | no class cache on the `start` classpath (argv `operator.clj:307-309`, `-M:dev:test` only) |
| 2 | `start!` → config compile, store acquire (store dir created) | 19.3–20.0 | ~700 | — | — |
| 3 | source capture + test-input digest (`cluster.clj:1690-1705`) | 20–21 | ~1,000 | every input file | whole-tree hash; needed once |
| 4 | clj-kondo analysis + `seon.fn` row derivation, all 708 files (`cluster.clj:1715-1722` → `fn.clj:2358`, `fn/analyzer.clj:261-289`) | 21–39 | **~18,000** | **whole program** | a from-zero store holds no prior file digests, so every file is analyzed before readiness |
| 5 | `populate-source!` before the big write: schema population, instruction rows, schema-shape assert (`fn/schema_shape.clj:461`), compile index tx (`fn.clj:2947`) (`cluster.clj:1380-1460`) | 39–47.5 | **~8,500** | whole program + whole schema | schema-shape family (plan 1.4b deletes it); one whole-program transaction compiled |
| 6 | **the program-rows transaction: Datahike writer runs Seon's report validator** (async-mixed-5, ~79 samples/s, 10,006 samples) | 47.5–173 | **~125,500** | **whole program²** | see below |
| 6a | ↳ per-root rescan of `:tx-data` with ancestor walks (`db.clj:3163-3172`) | | ~96,000 (7,595 samples) | roots × tx-datoms | exists only to set `changed-identity-attributes` for the arity gate (`db.clj:3174,3482`) |
| 6b | ↳ `owners-of` AVET lookups: entity × 136 component attributes, before and after (`db.clj:3084-3096,3112-3114`) | | ~15,000 (1,216) | entities × component attributes | no reverse-component index is used; every component attribute is probed per entity |
| 6c | ↳ Datahike index insertion, retention check (`retention-report-check`), other write-report checks | | ~5,500 + 4,700 + 3,100 | datoms | report-scoped would be the change, not the program |
| 7 | issue index transaction (8,185 datoms) + publication head | 173–175 | ~2,000 | all 374 notes | complete build indexes every note (existing issue `issue-indexing-at-publication-costs-13-seconds.md`) |
| 8 | `source-base!`: `projection-from-database` + `sci.eval/cluster-ctx` over `current-src` (`cluster.clj:1518-1553`) | 175–179 | ~4,000 | whole schema + program | projection rebuilt from datoms instead of carried |
| 9 | `registry/ensure-cluster!` (branch pointer), `accrete-schema-population!` on the cluster branch, config apply (`boot.clj:131-176`) | 179–181.5 | ~2,500 | whole schema (1,213 attrs compared) | "a converged reopen issues no transaction" (`cluster.clj:1321`), yet it still derives every declaration diff |
| 10 | `arm-agents!`: `load-core-namespaces!` `require`s every `:core` namespace, **test namespaces included**, into the JVM, then installs each as SCI host namespaces (`sci/eval.clj` `load-core-namespaces!` at HEAD, `install-first-party-namespaces!`) | 181.5–189 | **~7,500** | whole program (414 ns) | source compile of ~400 namespaces, most not needed by the root agent's first turn |
| 11 | projection/`installed-attribute-declarations`, serve, readiness | 189–189.6 | ~600 | — | — |
| | **ready-ms (2–11)** | | **170,338** | | |

## Warm restart breakdown (same root, `current-src` present)

`refresh-source!` is skipped because `(source/current store)` answers
(`boot.clj:118-119`). The phases:

| phase | ms | note |
|---|---|---|
| JVM + `require seon.cluster.boot` (source compile) | **~17,200** | outside ready-ms; the same as cold phase 1 |
| store open, `projection-from-database`, `accrete-schema-population!` diff over every declaration | ~3,800 | whole schema, even though nothing changed |
| config apply, fork ctx | ~1,000 | |
| `load-core-namespaces!` + SCI host namespace install | ~5,000 | whole program |
| projection / installed attribute declarations, serve | ~1,300 | |
| **ready-ms** | **11,092** | the "~5.5 s" figure was recorded at lower load; wall is 28.7 s |

## Q1: can the installed publication path adopt a schema-resource change incrementally? Yes.

Seam: `refresh-source!` with the changed path (`cluster.clj:2124`). This is the same
call the hook sends (`bin/seon-hook:1500`). Classification marks the path
`:schema-resource` (`cluster.clj:1745`). `populate-source!` then runs only the schema
owner (`cluster.clj:1419-1426`), which is `accrete-schema-population!` →
`declaration-changes` (`cluster.clj:1032-1073`, accretive per property via
`accretive-property-change?`). Probe: the optional member
`:seon.cluster.instruction/probe-note` was added to the scratch copy of
`resources/seon/schemas/seon.cluster.instruction.edn`, then removed again.

| step | ms | phases (ms from request) |
|---|---|---|
| add, publish to `current-src` | 7,064 | source build 71→1,973 (capture, test-input digest, one-file analysis); schema population 2,041→4,753; contract rows 4,939; development reconciliation 4,946→6,977 (callers of 3 files); branch head 7,018 |
| add, adopt onto cluster `fzb` | 1,798 | changed declarations 650; reconciliation 732→1,541; JVM instrumentation 1,703; adoption record 1,742 |
| retire (no writers), publish + adopt | 7,119 | publish ends 6,138; adoption 6,291→7,118; contract schemas 3,297 → 3,296 |

Limits and defects:
- **Retirement never retracts the Datahike ident.** `declaration-changes` iterates
  only the declared attributes (`cluster.clj:1070-1073`). After the retire, the
  attribute was still installed on both `current-src` and the cluster branch. An
  incrementally evolved store therefore diverges from a from-zero one. This is the
  gap plan 1.3e names: a retraction path, refused while writers survive.
- **An incompatible declaration change refuses** (`cluster.clj:1051-1066`), and its
  message names refork or export/import. Per AGENTS.md, different semantics need a new
  key, so that refusal is correct and is not a reason to reset.
- Even a one-resource change costs 7 s, not sub-second. About 1.9 s is the
  whole-tree capture and test-input digest. About 2.7 s is diffing all 1,213
  declarations and schema rows. About 2 s is the development reconciliation.
- A standalone attribute that no entity schema references (for example
  `:seon.ai.http/*`) is not a database attribute (`schema/canonical-database-attributes`).
  Publishing it changes nothing in Datahike, which is correct.

## Reset (`bin/seon reset --force`)

What it does: `down`, then `start default` with `:seon.store/destroy? true`
(`script/seon/operator.clj:350-353`). The store directory is recursively deleted
under the sibling lock (`src/seon/cluster/store.clj:461-473`), followed by the
from-zero boot above.

| destroyed | does the store already hold it, or is it derivable? | rebuild cost |
|---|---|---|
| `current-src` program rows (441,788 datoms), schema declarations, per-file digests | **yes**: they are a function of the files, and the store holds the last publication with its per-file digests (`cluster.clj:1699-1705` compares them) | phases 3–7, ~155 s |
| every cluster branch: agents, turns, evaluations, messages, errors (**data**) | this is what a reset is usually for | — |
| the clusters' program rows | forked from `current-src` by a pointer (`registry/ensure-cluster!`, `d/branch!`) | 4,256 ms measured (`start fzb2`) |
| blobs, konserve ancestry (the 245 GB class) | retention GC is the tool (plan 1.3c: `:datahike.gc/sweep-opts`, konserve `sweep!`) | — |

The assumption behind reset is that "fresh data" requires "fresh store". That is
false: data lives on the cluster branch, and the program lives on `current-src`.
`registry/retire-branch!` plus a fresh `ensure-cluster!` fork gives a data-fresh
cluster in about 4 s, keeping the program rows. Reset remains legitimate only for
store corruption or a deliberate store-format change.

## Audit rows (boot, reset and indexing), ranked by time wasted per day

Frequencies are the orchestrator's recorded habit (landing notes 2026-09-21..23: a
from-zero boot per schema commit, per lane proof and per RESET batch; six to eight
recorded today in `from-zero-boot-takes-minutes.md` and landing notes). They are
estimates, not a measured log.

| # | operation (who, how often) | measured wall | work ∝ | assumption | true? (file:line) | verdict | cost after | fix size / owner |
|---|---|---|---|---|---|---|---|---|
| 1 | Whole-program write validation (every from-zero boot, every reset, every complete publication; ~8/day) | ~125 s per boot (this run) | program² | "Deciding whether call arities need rechecking needs a per-root rescan of the whole transaction." | **False.** The owning walk already has every affected entity and its root (`db.clj:3112-3114`); the per-root `:tx-data` rescan (`:3163-3172`) recomputes it. A single pass grouping `:tx-data` by root gives the same `changed-identity-attributes`. A from-zero publication (empty `:db-before`) changes every root. The validator is Datahike's hook (`transaction.cljc:1206-1276`) | **REPLACE** the rescan with one grouping pass; **REPLACE** per-entity × 136-attribute `owners-of` probes with the component datoms in the report plus the ref index for pre-existing owners | ~10–15 s (datom-linear, 6b/6c remain) → with report-scoped validation (A2) a few s | `src/seon/db.clj` ≈ 20–40 lines, one bounded regression; plan §4 cut 2 "A2 … validator narrowing / complete validation proportional to the report" |
| 2 | JVM namespace compile from source (every `bin/seon start`, warm or cold, every lane scratch boot; ~15/day) | 17–19 s | whole closure | "`start` must compile dependencies from source." | **False.** Clojure loads an up-to-date class in preference to source (`reference-code/clojure/src/jvm/clojure/lang/RT.java:432-449`, class chosen when newer than the `.clj`). `dev_cache.clj` already builds `target/dev-dependency-classes/<digest>`, and `bin/test` links it (`bin/test:959-966`). `start`'s argv omits it (`script/seon/operator.clj:307-309`) | **REPLACE**: put the selected dependency class cache on the `start` classpath (first-party stays source) | 16.8 → 7.9 s measured | `script/seon/operator.clj` ≈ 5–10 lines + the cache selection read; B1b operator owner |
| 3 | Reset for fresh data (orchestrator RESET batches, lanes' "RESET NEEDED"; several/day) | 170–191 s | whole program | "A fresh cluster requires deleting the store." | **False.** Data is per cluster branch; program rows on `current-src` survive; forking is a pointer (`registry/ensure-cluster!`) | **REPLACE** with retire-branch + fork from `current-src`; **KEEP** destroy only for store corruption or a format change | 4.3 s measured | operator `reset` ≈ 10–20 lines; B1b |
| 4 | Scratch-root from-zero boot as schema-commit proof (lanes, per schema commit) | 90–199 s recorded | whole program | "A schema change must be proven from zero." | **False** (owner 2026-09-23; plan 1.3e superseded). The incremental path adopts an add and a writerless retire (Q1) | **DELETE** the ritual; prove on a branch of a live store | 7–9 s | docs/specs only; retraction path is 1.3e |
| 5 | clj-kondo analysis of all 708 files before readiness (from-zero, reset) | ~18 s | whole program | "Readiness requires the complete program index." | **Partly true.** The root agent's context needs program rows, but not before the REPL (open at t≈0.2 s) or the store. Analysis is per-file and content-addressed by the stored digests (`cluster.clj:1699-1705`) | **KEEP** once per store lifetime; with row 3 fixed it runs only on a truly new store. Parallel per-file analysis is an option, not measured | 18 s, rarely | — |
| 6 | `load-core-namespaces!` + SCI host install at arm (every boot and cluster start) | 5–7.5 s | whole program (414 ns, tests included) | "Every `:core` namespace, tests included, must be loaded before the first turn." | **Unverified here.** It loads test namespaces the root agent never calls. Plan B2 "acquire once / fork retained" (`sci/fork`) covers the per-cluster share; the JVM share falls with row 2's class cache | **REPLACE** (defer test-namespace load to test requests; share the installed base ctx across clusters) | ~1–2 s est. | `src/seon/sci/eval.clj`; B2 cut 4 |
| 7 | `accrete-schema-population!` diff on every branch open (every boot and cluster start, and every schema publication) | 2.5–3.8 s | whole schema (1,213 attrs) | "Every branch open must diff every declaration." | **False** when the packaged-forms digest equals the branch's recorded one. "Unchanged adoption compares commit ids" (AGENTS.md); the source digest is stored (`:seon.source/digest`) | **REPLACE** with a digest comparison; diff only on change | <100 ms est. | `src/seon/cluster.clj:1315-1370` ≈ 10 lines; A1 |
| 8 | `projection-from-database` in `source-base!` and again at cluster open (every boot) | ~4 s cold, ~1.3 s warm | whole schema | "The projection must be rebuilt from datoms." | **False** in-process: plan 1.4 "the projection is a read; carried" | **REPLACE** (1.4 / A1-3) | ms | scheduled 1.4 |
| 9 | Complete issue index at publication (from-zero, reset) | ~2 s | all notes | known | see `issue-indexing-at-publication-costs-13-seconds.md` | as that issue | — | existing issue |
| 10 | Schema-resource incremental publication (every schema edit) | 7.1 s | whole tree (capture) + whole schema (diff) | "Publishing one resource needs a whole-tree test-input digest and a full declaration diff." | **False** for the diff (row 7). The whole-tree digest is 1.9 s of a one-file change | **REPLACE** (diff only the changed resource's declarations) | ~1 s est. | B1 1.2b |

## Three ranked fix options

1. **Delete the per-root rescan in `write-owned-values-error` (simplest viable;
   RECOMMENDED first).** One grouping pass over `:tx-data` by root replaces
   `db.clj:3163-3172`, and `owners-of` stops probing all 136 component attributes per
   entity.
   - Expected saving: about 96–110 s of the 170 s. A from-zero boot drops to roughly
     60 s ready, bounded by analysis (18 s), populate (8.5 s) and datom-linear
     validation.
   - Guarantee kept: the same refusals (cycle, multiple owners, missing component,
     unowned entity) and the same arity gate.
   - Cost: ≈ 20–40 lines in `src/seon/db.clj`, plus one regression asserting that a
     program-sized transaction validates within a declared bound. A2 owns it (cut 2,
     "validator narrowing").
   - Gives up: nothing semantic. It speeds up every large write, not only boot.
2. **Replace reset's store deletion with retire-branch + fork from `current-src`, and
   put the dependency class cache on `start`'s classpath.**
   - Expected saving: a data reset goes from 170–191 s to about 4 s (measured). Every
     start saves about 9 s of JVM compile (measured 16.8 → 7.9 s), so a warm restart
     wall is about 20 s instead of 29 s.
   - Guarantee kept: a fresh data world. Program rows stay equal to the files, because
     `current-src` is compared by per-file digest at the next publication.
   - Cost: operator `reset` plus argv, ≈ 20–30 lines in `script/seon/operator.clj`.
     B1b owns it.
   - Gives up: reset no longer clears store-level ancestry. That becomes the retention
     GC's job (plan 1.3c), and store destruction stays for corruption only.
3. **Make readiness independent of whole-program derivation.**
   - Three pieces:
     - carry the projection (1.4) instead of rebuilding it;
     - skip `accrete-schema-population!` when the packaged-forms digest matches the
       branch's recorded one;
     - defer test-namespace loading from arm to test requests.
   - Expected saving: warm ready from about 11 s to about 2–3 s; cold phases 8–10 save
     about 12 s.
   - Guarantee kept: every changed declaration is still diffed and every agent-called
     namespace is still loaded.
   - Cost: 3 slices across `cluster.clj`, `sci/eval.clj` and the 1.4 work. Owners are
     A1 and B2 (cut 4), so this spans owners and takes the longest.
   - Gives up: the first test request pays its namespace load.

Together, options 1 and 2 remove the minutes. Option 3 is what makes warm operations
approach the sub-second design.
