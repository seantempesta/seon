# Native storage and compiled code in the store: design and measurements (2026-10-22)

Worktree session store-native, THE PLAN step A2 ("one store holds the whole system"; `../notes/DECISIONS.md`, "One store holds the whole system"). The owner (2026-10-22): "the better storage system (native storage and see if we can save the compiled code so we can return to any point in time without having to re-run statements (I'm less certain about this, but storing native compiled values seems like a win)".

**Bottom line.** Compiled code in the store would buy little today, because almost nothing compiles at run time. An agent's fork inherits main's compiled program copy-on-write (0.68 ms p50). A root boot loads the binary, and builds already reuse three content-keyed caches on disk. What does compile is catch-up and point-in-time loading: the forms a binary lacks, at 1.6–5 ms each. A stored unit would replace about 3.6 ms of that with about 0.01–0.05 ms, but contract arming (1–2 ms a form) runs either way. Units cost 3–15× their source in the store, every Jolt or Chez pin bump strands them, and each Jolt unit holds absolute paths today. So: **don't store units now.** Keep the fork server's replica (replay is cheap, and the replica is what keeps the program compiled). Base the agent's `:base` on the store at its merge base, which also fixes a staleness bug. Point in time from definitions already works, and the line of what can't be restored is drawn below. The fingerprint per commit needs one Jolt `def-var!`, which waits for root's go.

Research behind it: the side-session notes `store-native-compiled-units.md` and `store-native-fork-base.md` (two agents, read-only, cited). Every number below I measured myself unless it is marked *recorded*.

## Setup

- This worktree's own root (`bin/root start`, ports 8350/8351) on a fresh store: main has 41,696 datoms (114,447 across `:eavt`, `:aevt` and `:avet`), 1,290 live forms, max-tx 536870924, 2 branches. The store is 6.4 MB after creation and 7.4 MB after the measurements (zstd, values out of line, history kept).
- The build was main's `target/seon` (229 MB) at `e117b9b`, plus `target/jolt/jolt -A:test` for cold processes. The machine is an M1 with 16 GB. Everything ran with `nice -n 15`, n ≤ 100, one experiment at a time.
- Probe scripts lived in the session scratchpad and are not kept. Their forms are summarized under each number so that each can be run again at the REPL.

## Q1. Compiled units in the store, keyed by content

**Where compiled artifacts live today** (agent, cited in `store-native-compiled-units.md`): three caches keyed by content, under the main tree's `target/cache`, shared by every checkout (`bin/env`):

| cache | key | entries | bytes |
|---|---|---|---|
| build units | `bld-unit-key` (build.ss:3624): the unit's text and the building runtime | 3,379 files | 2.0 GB |
| runtime | the runtime's own build (build.ss:3233) | 13 | 225 MB |
| AOT (`JOLT_CACHE_DIR`) | `<jolt-version>-<runtime fingerprint>[-tr]/v1/<ns>-<source key>.so` (loader.ss:903, :2361) | 18,789 `.so` in 3 generations | 8.1 GB |

Measured in the AOT cache's current generation: Seon's own namespaces have 9,190 `.so` there (3.0 GB, one per source version). The newest of each of the 55 namespaces totals 15.2 MB, against 995 KB of source in `src` (15×). `seon.lib` is 13.4 KB of source and 428 KB compiled, 139 KB under zstd-3. Its unit holds 127 absolute `/Users/...` paths, because `compile-file` bakes the `.scm` path into source info (loader.ss:1831).

**What a Chez compiled unit looks like as a value** (raw Chez 10.4.1, `target/chez/.../scheme --script`, 100 synthetic defines of about 450 B source each):

| | per form |
|---|---|
| `eval` (compile and run) | 0.27 ms |
| `compile-to-port` to a bytevector, then `load-compiled-from-port` from it | 0.01 ms |
| unit size, with inspector info / without / zstd-3 | 2,630 B / 1,705 B / 1,933 B |
| deterministic | 100 of 100 identical, within a process and across two processes |

So a unit is a plain bytevector, loadable from the store's bytes with no file in between (Jolt already does this for embedded stdlib fasls, loader.ss:2210). It is about 4–6× the source in raw Chez, and 15× (32× for `seon.lib`) in Jolt's AOT units today.

**What compiles at run time, and what it costs** (root's REPL, through the server):

| measurement | result |
|---|---|
| 100 tiny `defn`s, `eval` | 30.6 ms (0.31 ms each) |
| 22 real `seon.context` defns: armed (`seon.contract/defn`) / plain `clojure.core/defn` | 126.5 ms then 100.8 ms (4.6–5.7 ms each) / 78.2 ms (3.6 ms each) |
| `load-forms!` of 67 `seon.lib` / 14 `seon.print` / 26 `seon.context` forms | 108.6 / 24.2 / 130.5 ms (1.6 / 1.7 / 5.0 ms a form) |
| `load!` of the 67 `seon.lib` forms *with their dependents* (macro expansion sites) | 2,367 ms |
| `macroexpand-all` of the 26 `seon.context` forms | 5.4 ms (read: 2.7 ms for 100) |
| `seon.program/live-forms` on main | 184 ms warm in root; 261–318 ms cold |

Of a real form's 1.6–5 ms, about 3.6 ms is Jolt's analysis and emit plus Chez's compile. That is the part a stored unit would replace. The remaining 1–2 ms is contract arming, which builds validators at definition and would run at load anyway.

**Who pays the compile today:**
- An agent's fork pays nothing: it inherits the fork server's compiled program copy-on-write. Recorded: fork-ms p50 0.68 ms; 100 agents ready in 591 ms.
- A root boot loads, it doesn't compile. Measured: `bin/root start` reaches listening in 5.7 s on a new store (create, seed, build main from source) and in 3.2–3.6 s on resume. Recorded: a check step's root boot takes 293–513 ms, and `target/seon` starts in 0.8 s. The only compile is `seon.program/load-all!`, for live forms the binary lacks (catch-up after merges that weren't rebuilt), at the 1.6–5 ms above.
- The fork server pays at each main commit that changes the program (`program/load-changes!`), once for all later agents.
- A build pays, and is already content-cached.

**With and without units, estimated from the measured parts:**
- A boot catching up on 100 merged forms: about 160–500 ms now, about 100–200 ms with units.
- An agent's fork: 0.68 ms either way.
- A branch at a far-back commit C with 500 differing forms: about 0.8–2.5 s now, about 0.5–1 s with units.

**What units would cost:**
- **Store growth:** about 4 KB zstd per form version in raw Chez, and about 37 KB raw / 12 KB zstd per form in Jolt's units today (15.2 MB over 1,290 forms). The whole store is 7 MB, so one compiled program is about 2× the store, and every changed form adds a unit.
- **Invalidation:** any Jolt or Chez pin bump strands every unit (they are valid only under the runtime that compiled them, build.ss:2991). The AOT cache shows the cost: a new generation of up to 6.7 GB.
- **Path independence:** the absolute paths have to go first, and that is a Jolt change.
- **A second format:** `DECISIONS.md` (lines 35, 466) records "raw Chez fasl broke across builds … Compiled code is not stored", the reason values are jfasl.

**Recommendation, simplest first:**
1. **Don't store units now** (simplest; nothing to build). What compiles at run time is the catch-up and point-in-time path, under a second per 100–500 forms. Its larger cost is macro fan-out (2.4 s for one namespace's dependents), and that is `seon.program/dependents`' precision, not compile speed.
2. If point-in-time loading becomes hot, store per-form Chez units under `(digest of the form's text, what it reaches, runtime fingerprint)` as values out of line. That needs Jolt to emit one form to a bytevector with no absolute paths (a `jolt/<topic>` branch), plus Q4's fingerprint. It is a cache, so it obeys AGENTS §5 "content-keyed reuse is allowed for units measured as expensive": measure first that loading is the bottleneck.
3. Don't move the AOT cache into the store. It is 8.1 GB of mostly superseded versions, and it lives below the runtime.

## Q2. The fork server's base: replay against reading from the store

**Today** (agent, cited in `store-native-fork-base.md`): the fork server's replica starts from root's preloaded main and applies each main commit's resolved datoms (`seon.db/report->tx`, `report->tx-meta`) with `d/with`, then `program/load-changes!` (`seon.fork-server/server-step` `:tx`). It never replays forms or evaluations. An agent inherits the replica as `db0`/`:base`, then opens the store itself (`seon.db/after-fork!`, about 10 ms *recorded*) and works from its branch.

**Measured** (root's REPL; cold numbers from a new `jolt` process on this store):

| | ms |
|---|---|
| replay one 20-datom commit (`d/with` of resolved ops + tx-meta) | 0.68–1.22 |
| `d/commit-as-db` at main's head, lazy | 0.67–0.89 |
| one query on it, cold (a var's source) | 34–37 |
| read the whole db (all three indexes, 114k datoms), cold process | 316–398 |
| the same in root, first / again | 300 / 31 |
| `live-forms` on it, cold | 261–318 |

**The trade-off:** reading main at C from the store costs 0.7–0.9 ms up front, but each agent then decodes the nodes it touches in its own heap, up to 0.3–0.4 s and the whole db's heap per agent. The replica decodes once, and every agent shares it copy-on-write. Replay costs about 1 ms per commit, once, in one process. The replica also does the compile (`load-changes!`), so agents inherit the program compiled; a base read from the store would still need the program loaded at C. So replay is not the cost DESIGN-tasks §11 #3 suspected. The real problems are two: `:base` goes stale, and the alignment is unchecked.

**Found:**
- **`:base` goes stale.** `seon.agent/merge-main!` never updates `:base`, so after a merge-main the agent still picks its tests (`test-vars`/`changed-vars`) against the main it was forked from.
- **The alignment holds but is unchecked.** The writer is serial and sends each `:tx` before the `:fork` on one FIFO (`seon.writer`, `:spawn`), so the replica is at main's head when the agent's branch is made from it. But the fork request carries no commit id.

**Recommendation, simplest first:**
1. **Keep the replica.**
2. **Make `:base` the store's value at the merge base,** `seon.db/branch-base` (lazy, 0.7–0.9 ms), read when tests are chosen. This fixes the staleness with no new mechanism.
3. **Put the replica's commit id on `:fork`,** and have the agent check that its branch's parent is that commit. A mismatch becomes an error fact, not a guard (the writer's FIFO is the dependency).
4. Replace the replica only if the fork server's memory becomes the bound. Recorded: 60 → 296 MB with test/ loaded.

## Q3. Point in time without re-running statements

**Already true:** a branch's program is rebuilt from its definitions. `load-all!`, `load-changes!` and `load-diff!` read `:form/src` of live forms. Evaluations are records only (`:seon.cluster.eval/*`, read by renders and checks), and nothing re-runs them (`seon.harvest`: nothing an agent evaluated is evaluated again). So "return to commit C" is `d/commit-as-db` at C (0.7–0.9 ms), then `load-diff!` from the fork server's image to C (each differing form at 1.6–5 ms, about 1–2 ms with units). A process forked from the image and moved to C needs no replay.

**What restores from definitions, by kind** (main's 1,290 live forms, by head):
- **Code:** 994 `defn`/`defn-`, 47 `defc` / `defchecked` / `lib/defc`, 8 `defmacro`, 1 `defmethod`, 11 `declare`, 58 `ns`. Exactly restored.
- **`def`s and `defonce`s, 150:**
  - 87 literal data: exact.
  - 56 computed from other definitions (`(sd/declarations contract/registry)` and the like): re-run from the definition, deterministic. That re-runs a definition, not an evaluation.
  - 7 host cells (`atom`, `delay`, `volatile!`): restore *empty*, as at boot; their contents were run-time state.
- **Can't be restored from the store at all:**
  - `ffi/load-library` (3) and other host objects: the LMDB env and connections, sockets, Flow procs, channels, threads, the id lease, the fork server itself. All are re-made at boot.
  - What an agent's REPL created outside definitions: scratch namespaces, vars made by an evaluation rather than a definition, atom contents. These are carried only by `clone!`, which forks the live process.

**On "storing native compiled values":**
- Values are already stored natively: plain data as jfasl (Plain values, `docs/storage.md`).
- Compiled values (closures) can't be stored by the runtime: `jolt.image/dump-world!` skips every var whose root is a function, because "code does not travel".
- A host cell's plain-data contents could be stored as a value, but that is a snapshot of run-time state. It would make "point in time" mean "the process as it was" rather than "the program as defined", which the ruling on definitions over evaluations rejects for main.

**Recommendation:** nothing to build for main or branches; the property holds. The one thing to build, when wanted, is Q2's alignment (a fork at exactly C), plus a `my.*` function to open a REPL at commit C: commit-as-db, then `load-diff!` in a fork. Agent state beyond definitions stays with `clone!`. The owner's doubt is right as far as what can't be restored goes; no stored artifact closes that gap.

## Q4. Each commit's tx-meta names its runtime fingerprint

**What the fingerprint is:**
- Jolt keys every compiled unit on `<jolt-version>-<runtime fingerprint>[-tr]` (loader.ss:900-906), for example `v0.8.23-65-gfc5d2f82-A2987A-B3CB6DE-2114AF.F047542A.111D29.20697D44-tr`.
- The runtime fingerprint is baked into a binary over its whole emitted image plus `petite.boot` and `scheme.boot` (build.ss:2982-3022), or hashed from `host/chez/*.ss` in an unbuilt checkout (loader.ss:857). A binary's image includes the deps compiled into it.
- From Clojure only `(jolt.host/jolt-version)` ("v0.8.23-65-gfc5d2f82") and `(jolt.image/runtime-version)` ("chez-1041") are reachable. Neither tells two dirty builds apart (loader.ss:818-821 says why).

**Design:**
- **Jolt change, root's go first:** one `def-var!` in `host/chez/loader.ss`, `jolt.host/runtime-fingerprint` answering `(aot-generation-dir)`'s key `(string-append (jolt-version-string) "-" (aot-runtime-fingerprint) (aot-trace-tag))`, on a `jolt/runtime-fingerprint` branch.
- **Seon:** a string attribute `:seon.tx/runtime` that each transaction's tx-meta carries: the writer's `transact-batch!` arg maps, `seon.agent/transact!`'s `dw/apply!`, and `seon.db/open!`'s seed and install. The fork server already copies main's tx-meta, so its replica carries it too.
- **Cost:** one datom of about 90 B per transaction, inline (under the 1,024 B out-of-line threshold).
- **Proof:** a transaction through the writer and one through an agent each have `:seon.tx/runtime` equal to the running fingerprint, and the fork server's replica shows the same.

I did not land it, because the honest value needs the Jolt `def-var!`. A version string standing in for it would claim to pin a runtime it can't tell apart.

## The store-identity fix (merged on `jolt/store-native`, `31c9bf5`)

- **The fix:** `seon.db/store-cfg` reads the id the store records (`seon.db/stored-id`: the `:db` head's config through Datahike's `read-head`). It mints `(hasch/uuid path)` only for a store it is about to make. Proof `seon.db-test/a-moved-store-opens`: red with "Store identity mismatch" for both a move and a copy; green now; all 15 `seon.db-test` tests green.
- **Cost:** 0.3–0.6 ms once the process has the env open. A process's first touch of a store pays 20–170 ms cold either way: `d/connect` alone paid 22–170 ms first, and `store-cfg` paid 31–71 ms when it came first.
- **Also fixed:** `open!` leaked one connection reference when it seeded a store on `:db`.
- **Filed:** `seon.db/deref-keeps-the-connections-config`. With the `:sync` writer, `datahike.connector/deref-conn` rebuilds the db with the *stored* `:config`, so `(:config @conn)` names the path a store was made at. Nothing in `src` reads it. The fix is one line in our Datahike.

## Asked of root

1. A go (or not) for the Jolt `def-var!` `jolt.host/runtime-fingerprint` (Q4), then the Seon side.
2. A go for Q2's two small changes: `:base` from `branch-base`, and the commit id on `:fork`, checked.
3. A ruling on Q1: no units now (recommended), or the per-form unit cache in the store (Jolt work first: emit to a bytevector, no absolute paths).
4. A go for the Datahike line in `deref-keeps-the-connections-config`.

## Verification (this branch, 2026-10-22)

- Full `bin/test`: 335 tests; the reds are 4 open tasks' proofs that predate the branch: `seon.root-test/a-build-without-whole-program-inference-reads-with-tools-reader` (jolt.ns), `seon.procs-test/the-flush-is-continuous-group-commit` (seon.writer), `seon.source-test/a-restart-catches-up-and-a-new-generation-resumes` (seon.source), `seon.schema-test/a-registration-counts-the-stored-values-it-refuses` (my.schema).
- `bin/check 100`: everything OK but `census-3` and `orphan` (both predate the branch) and `e2e-loop`. The loop ran on a stale `target/seon` (main's `e117b9b`): its merge deleted `seon.db/stored-id`, a form the binary lacked. Rebuilt from HEAD (`bin/build seon`, 38 s), `bin/check e2e loop` passes. The deletion is filed as `seon.source/a-merge-writes-back-only-what-it-changed`.
