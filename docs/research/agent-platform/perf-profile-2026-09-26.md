---
type: research
lane: perf-profile-opus
date: 2026-09-26
status: stopped at the owner's pause (nuke pending); ranked table current, remaining patches listed
---

# Per-operation allocation and CPU on default (2026-09-26)

What exists: one default JVM (G1, `-Xmx` 10.2 GB, idle ~1.5 GB live) died of OOM twice at ~10 GB under load. This profile
measures what each canonical agent operation ALLOCATES (the killer is churn, not retention) with JDK Flight Recorder
(`settings=profile`, stack depth 64, JDK 26.0.1) plus exact per-thread allocated bytes
(`ThreadMXBean/getThreadAllocatedBytes`) and Seon's own armed-call profile (`seon.profile/begin` / `explain`).
Scripts: `tmp/perf-profile-opus/{jfr.sh,agg.py,armcallers.py,callers.py}`; recordings `tmp/perf-profile-opus/jfr/*.jfr`
(`a-cold`, `a-eval1`, `a2`, `problems`, `qloop`, `hook`, `page`); per-recording reports `tmp/perf-profile-opus/*.txt`.

## Broken first

1. **An agent cannot define a function on a fresh MCP branch.** Every `defn` (with a complete contract) through MCP
   `eval_clj` SCI on a new branch refuses: `seon.sci.eval/evaluate-candidate refused argument 0 ... candidate-ctx nil
   (turn.clj:3319)`, plus `acquisition-refusal: Program row could not be installed`. Reproduced on three JVMs (pids 27723,
   36805; branches perf-profile-opus, perf-a2, perf-a3). The contract finding also says the refusal matches none of
   `evaluate-candidate`'s declared error alternatives. Routed by root to fix-agent-path-opus. Operations (a) and (b)
   were therefore profiled as far as the refusal (branch creation, acquisition, entry gate), and (b) `my.test/check` of a
   changed fn could not run at all.
2. **`bin/seon status` during boot throws** `ClassCastException Keyword -> Symbol` in `seon.instrument/replaced-roots`
   (instrument.clj:1035, via `mcp-runtime-observation`), diagnostic
   `data/clusters/default/logs/operator-status-20260926T103046.142.edn`. Not investigated (instrument.clj not mine).
3. **Measurements share the JVM.** Root's test runs and other lanes ran concurrently in several windows (Connection
   threads 24, 42–45 running `contracts_compile_test`, `seon.test.runner`). JVM-wide numbers are marked as such; the
   per-function numbers are single-thread `tm` measurements on my own prepl thread and are clean.
4. **JFR stack depth is 64** and cannot be raised after JFR initialized, so inclusive attribution for deep Clojure
   stacks is truncated; self sites and armed-caller attribution are exact, outer callers came from `seon.profile`.
5. Not done: (d) `prepare-merge!` (root-only, root graph was down, then the pause); (c) was measured once only on a busy JVM.

## Ranked table (largest first; "per call" = one call on my thread; "per op" = what one operation pays)

| # | cost | where (file:line) | measured | status |
|---|---|---|---|---|
| 1 | **Armed contract validation** is 25% of allocation and 33% of CPU in a cold agent eval (4.7 GB self of 19 GB, 245/750 samples); 18% / 16% on the debug page | `seon.instrument/arm-var!` wrapper, instrument.clj:753 `(apply boot-wrapper arguments)`; heaviest callers `schema/canonical-reference-graph:740` (→ armed `compilable-form`, output `:seon.schema/value` walk) 1.45 GB, `schema/projection-registry:571` 0.86 GB, `db/q:2482` 0.44 GB, `render.value/settle:633`, `render.value/extras:586`, `error.refusal/chain:83` | a2.jfr, page.jfr (`armcallers.py`) | open — the owner question is which hot internal fns stay armed; #2 removes the largest caller |
| 2 | **`schema/build-projection` rebuilt for identical input**: 386 ms / 571 MB per call; `declaration-projection` 336 ms / 410 MB; called 10–13× in one branch create/eval window (4.0 GB = 21% of op a's allocation, 30% of CPU) | schema.clj `build-projection`, `declaration-projection`; callers fn.clj:2784 `analysis-projection` ×5–6, `call-with-projection` ×19–23 | tm on packaged forms (3,489) | **fixed 39960bb15**: core.cache LRU by input + resolved predicate Vars; hit 2.5 ms / 6 MB (150× / 95×) |
| 3 | **An override interpreted every function that MENTIONS it**: acquisition's `reverse-closure` walked `:seon.fn/references`, file unresolved refs and subjects over a whole-program identity index (the OOM thread's `gate-sets-in`). A leaf override pulled 530 functions into SCI through one quoted mention | fn.clj `reverse-closure` → `gate-sets-in` (union mode), called from sci/eval.clj:2725 `acquire-program!` | leaf 530 fns, 66 ms / 153 MB; `call-reach` 568 fns, 54 ms; `seon.db/q` 2,061 fns, 91 ms / 280 MB | **fixed 39960bb15**: call reach to fixed point, one bound query per level: leaf 1 fn, 1.5 ms / 5 MB; mid 154 fns, 6.7 ms; hub 1,264 fns, 67 ms / 193 MB. Tests = `call-reach` |
| 4 | **Every MCP call ran a full runtime observation** just to label the endpoint (every advertised state was eligible anyway): `readiness` → `problems/problems` → per-agent `deferred-triggers` | script/seon/dev/mcp.clj `discovery-rows` ← `read-clj-endpoint`; seon.cluster `mcp-runtime-observation` 180 ms / 585 MB; `problems` 150 ms / 502 MB (deferred-agents 108 ms / 363 MB, failed-tests 37 ms / 102 MB, error-signatures 7 ms) | tm, problems.jfr | **fixed (uncommitted at pause, see below)**: eval/branch endpoint reads advertisements only (bb 41 ms, no JVM round trip); `runtime_status` keeps health |
| 5 | **`seon.db/q` wrapper = 36× Datahike's time, 76× its allocation**: 107 µs / 472 KB vs `d/q` 3 µs / 6 KB for a 2-clause query | db.clj: `query-patterns`/`parsed-nodes` tree-seq walked ≥5× per call (1916, 1947, 1978, 2002, 2057), `query-input-values` ≥4× (1887 merge-with set/union per element), `db.utils/db?` on the query vector ≥4× (2402, 2412, 2443, 2446, 92), armed `schema-database` | qloop.jfr (5,000 calls, 2.36 GB) | patch outline → root/mem-idle (db.clj held): compute patterns + input values once per call; #6 removes the db? share |
| 6 | **`datahike.db.utils/db?` on a non-database** (every seon.db/q, pull and guard asks it of query vectors): 6.6 µs and **42 KB per call** (`satisfies?` walks the class's supers ×3); on a db 0.03 µs | reference-code/datahike/src/datahike/db/utils.cljc:101 | tm ×100,000 | **patch** `tmp/perf-profile-opus/patches/datahike-db-instance.patch` (JVM: `instance?` on the three protocol interfaces; all five DB types verified inline-implementing on default) |
| 7 | **`seon.db/pull-many` on evaluation entities = 40× Datahike**: 49 eval rows 231 ms / 1,018 MB vs `d/pull-many` 5.9 ms / 21 MB (functions, tests, agents, ns: 1.0–1.7×) | db.clj decode path; page.jfr shows `schema.datahike/canonical-print-string:356/362` 3.6 GB, `storage-compare`, `reader-round-trips?` — the EDN codec re-prints stored values to compare | tm per entity kind | open → db.clj/schema.datahike owner. walk.clj's caller needs the decoded values, so calling Datahike there would be wrong; the cost is the codec |
| 8 | **`db/stored-call-reach` per render call** 2.2 GB in one debug-page window (db.clj:1471–1472) | db.clj | page.jfr | open → db.clj owner (memoize per commit + call identity) |
| 9 | **Branch create alone** 3.8 GB / 1.15 s JVM-wide; first agent eval on a new branch 11.2 GB / 3.6 s; second 3.4 GB / 1.0 s (all failing at item Broken-1; JVM-wide, concurrent work included) | profile growths: build-projection ×10, `fn/analysis-projection` ×5, `call-with-custody`, `transact!` ×12 | snap deltas | #2 and #3 cut the largest shares; re-measure after the nuke |
| 10 | Debug page `/agent/root/debug?n=2`: 3.13 s cold, 1.74 s warm (312 KB) | render.value settle/extras/select-nodes, canonical-print-string, stored-call-reach | curl + page.jfr | open (#1, #7, #8) |
| 11 | `wake/unindexed-listened-attributes` rebuilt the whole schema map (`d/schema`) ~25× per `problems` call: 2 MB each, ~50 MB per problems | cluster/wake.clj:208 | tm | **fixed (uncommitted at pause)**: `dbi/-attrs-by db :db/index`, 0 ms / 0 MB, same answer on default |
| 12 | Call-preparation hook: 4.8 µs armed / 3.9 µs unarmed, **14 KB per SCI Var call**, 77% of it inside `db/db`'s armed internals (`resolve-database-value`) | call_preparation.clj:1146 `(mi/-f->original db/db)` still calls armed callees | hook.jfr (100,000 calls) | open: small per call, but per SCI call; the fix is in db.clj (unarmed resolve on the hook path) |
| 13 | Boot `start --head` (2b4a836fd): ready 36.0 s; store→branch layer **32.1 s** (prepl 0.38, store 0.54, branch 32.64, connection 32.65, config 33.43, ctx 34.92, graph 36.00, web 36.01 s) | `:seon.boot/layers` | readiness value | open → boot owner (inv-boot-operator: development-source-refresh / adopt-rows) |
| 14 | Idle churn ~60 MB/s with no lane work (status polls, fault recording while the flow was stopped) | `mcp-runtime-observation`, `seon.fault/record!` | 10 s window | #4 removes the per-call share |

Refuted suspects: `seon.schema/value` and `:seon.schema/arguments` are `:any`-based, so the hook's own contract is cheap
(armed vs unarmed differ by 0.9 µs); `d/schema` itself is 0.7 ms (its cost is call count); `seon.db/pull-many` is not 30×
in general — only on entities whose values go through the EDN codec (#7).

## Commits and patches

- **39960bb15** (src/seon/fn.clj, src/seon/schema.clj, test/seon/fn_test.clj): #2 and #3. Contracts-compile check over
  both files: 0 findings. Regression in `seon.fn-test/value-uses-are-calls-and-quoted-symbols-select-nothing`; root
  applied `tmp/perf-profile-opus/schema_test.patch` (`equal-inputs-answer-the-built-projection`). Net: fn.clj +76/−29
  (gate-sets-in loses its union mode), schema.clj +98/−55 (mostly the build body moved into `projection-build`).
- **Second commit** (src/seon/cluster/wake.clj, script/seon/dev/mcp.clj, this document): #11 and #4. Contracts-compile
  check over wake.clj: 0 findings. The MCP bridge running this session already answers `cluster-state "advertised"`
  for evaluations (live). Root should run `bin/test-check default --ns seon.dev.mcp-bridge-test`; the discovery tests
  keep `discovery-rows` (health) unchanged, and `read-clj-endpoint`'s missing/ambiguous cases read the same rows.
- `tmp/perf-profile-opus/patches/datahike-db-instance.patch` — #6, for the datahike fork owner.

## What remains (ranked)

1. Decide which internal functions stay armed on hot paths (#1). The measured shape: a wrapper costs ~1–5 µs and several
   KB per call, and hot internal helpers (`compilable-form` per form, `entity-maps` ×85k per build, `db/q` guards,
   render `settle`) multiply it. Owner question, not a patch.
2. db.clj `q` (#5), `pull-many` codec (#7), `stored-call-reach` (#8), hook's `db/db` (#12) — db.clj/schema.datahike owners.
3. Fold #2's two LRU memos (threshold 8, code constants) into mem-idle-opus's single weighted cache with a config dial
   (owner rulings 783b81fbe and today's "one centralized caching system").
4. `runtime_status` still observes twice (discovery health, then the observation); derive the state from the one
   observation. `problems/deferred-agents` (108 ms / 363 MB, 44 agents × `deferred-triggers`) is the status cost.
5. Re-run op (a)–(e) after the nuke with the agent-path fix: the numbers above for branch/eval include failing paths.
