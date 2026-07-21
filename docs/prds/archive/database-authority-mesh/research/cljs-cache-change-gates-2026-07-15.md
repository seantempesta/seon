---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# CLJS gates for Datahike cache changes — 2026-07-15

## Result

Datahike commit `0b65221586a20182639f2dd7984ca203238ea9f7` changes the
portable `DB` record and query-cache implementation, but Seon still resolves
Datahike commit `9ada755087228e10cfb179fa5779ce227a6ed220` in both root
`:deps` and the `:writer` alias. Running Seon's current tests would therefore
not test the candidate. No compile or test was run under a false coordinate.

The shortest maintained CLJS compile-and-behavior gate after the dependency
coordinate is deliberately advanced is:

```bash
bin/test-cljs --test=seon.db-test
```

That command uses Seon's locked, isolated `:test` Shadow build and compiles the
real `seon.db` dependency graph against Datahike. It must run only during a
coordinated source freeze because the canonical watcher owns `out/test/test.js`;
`bin/test-cljs` correctly acquires `tmp/test-cljs.lock`, but it still publishes
the shared canonical test artifact.

## Candidate change surface

The candidate adds `cache-context` as the final field of
`datahike.db/DB` in `reference-code/datahike/src/datahike/db.cljc:307`, plus
portable `committed-cache-identity` and `clear-cache-context`. It changes the
query-cache atom from a weighted LRU value to
`{:lru weighted-lru :generations {...}}` in
`reference-code/datahike/src/datahike/query.cljc:2376-2662` and adds generation
admission, fenced close, metrics, exact committed keys, and generation-safe
propagation.

The rest of the CLJC attachment path is load-bearing:

- `connector.cljc` attaches/open generations and committed context;
- `writer.cljc` publishes the durable child context;
- `writing.cljc` and `core.cljc` clear speculative context;
- `connections.cljc` and `connector.cljc` close the generation on release; and
- `lru.cljc` adds portable removal/inspection operations used by the new state.

Changing only `db.cljc` and `query.cljc` is not a valid test setup.

## Constructor and record-field audit

Seon has no direct `->DB` or `map->DB` call. Datahike's own construction sites
in `db.cljc:924,976` use `map->DB`, so adding a final record field does not
create a positional-arity break there. Seon treats real DB values through
Datahike APIs/interfaces and opaque-value rendering. The compile risk is
therefore generated CLJS record shape/protocol emission and the complete CLJC
call graph, not a first-party positional constructor.

The focused `seon.db-test` graph imports `datahike.api`, `datahike.connector`,
`datahike.db/AsOfDB`, DB interfaces, indexes, and entities. Compiling that graph
is the smallest maintained Seon check that catches missing record fields,
constructor arity, renamed query-cache representation assumptions, and CLJS
protocol emission errors.

## Existing maintained gates

### Datahike CLJS compilation

Datahike's own isolated Node build is:

```bash
cd reference-code/datahike
clj -M:cljs -m shadow.cljs.devtools.cli compile node-test
node target/out/node-test.js
```

Its artifacts stay under `reference-code/datahike/target`, so it does not race
Seon's Shadow output. However, the runner
`test/datahike/test/nodejs_test.cljs` requires weighted-LRU tests but does not
require `datahike.test.query-cache-test`. It compiles the changed DB/query graph
through normal API use and exercises connect/transact/query/as-of/history/
release/reconnect, but it does not presently execute the new cache-identity and
release-fence assertions.

### Datahike cache semantics

The direct candidate tests live in
`reference-code/datahike/test/datahike/test/query_cache_test.cljc`. The three
new proofs are reader-conditional CLJ only:

- committed identity excludes speculative, as-of, and history values;
- final release fences a late cache put and reconnect changes generation; and
- a batched writer invalidates the union of request attributes.

The portable invalidation tests later in that namespace can run on CLJS, but
the maintained Node runner does not include the namespace. Therefore a green
Datahike `node-test` is necessary compile/API evidence, not sufficient CLJS
cache-semantic evidence.

### Seon CLJS behavior

`test/seon/db_test.cljs` is the maintained first-party database gate. Its fresh
memory connections cover schema installation, transaction envelopes, Datalog,
pull/entity/index/history/temporal behavior, and the exact `seon.db` wrappers
used by the pod. It does not inspect Datahike's private cache atom, which is
correct ownership, but it must gain or consume public Datahike fixtures for the
new committed/speculative/release laws before this change is fully proved in
CLJS.

Broader consumers that construct fresh Datahike CLJS connections include
`my.kb-test`, `my.data-test`, `my.blob-test`, `my.ns-test`, `my.plan-test`, and
eval/render tests. They belong in the final complete checkpoint, not the first
falsifier.

## Ordered gate

1. Advance both Datahike coordinates in `deps.edn` to the exact reviewed SHA.
   Confirm `clj -Spath` contains that checkout/commit and no stale Git cache.
2. In the Datahike checkout, compile and run `node-test`. This catches portable
   record, connector, writer, LRU, and query compilation/API regressions.
3. Add the query-cache namespace to Datahike's maintained Node runner or create
   an explicit Shadow namespace selection through its existing build. Port the
   three new CLJ-only fixtures to honest `^:async`/`await` CLJS setup rather than
   inventing a second runner.
4. During a Seon source freeze, run
   `bin/test-cljs --test=seon.db-test`. Read
   `tmp/test-cljs-latest.report.edn` and its full log.
5. Run the relevant complete `bin/test-cljs` checkpoint only after the focused
   Datahike and Seon gates are green.

Do not use `bin/test-cljs --no-build` for this change: the purpose is to compile
the new dependency graph, and the fingerprint should reject an old bundle
anyway. Do not compile Shadow manually into `out/test`; that bypasses the
runner's lock, fingerprint, report, and cleanup.

## Required CLJS semantic fixtures

The retained Datahike CLJS fixture must prove:

- a connected committed raw `DB` has a three-part exact cache identity;
- `d/with`, `d/as-of`, `d/since`, `d/history`, filtered, and detached values
  have no committed cache identity and do not create cache snapshots;
- two queries over one committed value reuse a completed result;
- a committed child propagates only dependency-safe completed results;
- release removes exactly the current generation and a late completion cannot
  reinsert it;
- reconnect assigns another generation and accepts new cache results;
- stale release cannot evict the reconnect generation;
- cache size/weight setters preserve generation admission while clearing only
  completed entries as specified; and
- metrics return bounded counts without results or DB values.

Because CLJS has one event loop, the late-put race should use controlled Promise
turns around an exposed test seam or pure state transition. It must not spin or
pretend synchronous `d/q` is awaitable.

## Stop rules

Stop and return to the Datahike owner if:

- the resolved dependency is not exactly the reviewed candidate SHA;
- a direct or generated positional `DB` constructor exists outside the audited
  Datahike owner;
- CLJS needs a different cache state shape or semantic law than CLJ;
- a temporal/speculative value receives committed identity;
- release and reconnect cannot be tested without private atom mutation;
- the test requires sleeps, a live Seon pod, or lifecycle commands;
- the Datahike Node runner stays green while omitting the cache namespace and is
  presented as semantic proof; or
- any compile attempts to write Seon's watcher-owned `out/client`, `out/test`,
  or Shadow cache outside `bin/test-cljs`' coordinated bracket.

## Current evidence and remaining gap

Read-only source audit confirms the candidate is internally cross-file, Seon
has no direct positional DB constructor, and the maintained focused Seon gate is
`seon.db-test`. No candidate CLJS compilation has yet occurred because the
workspace dependency still selects the prior SHA. The earliest unsettled proof
is therefore an isolated Datahike Node compile at the candidate SHA followed by
execution of the cache namespace in CLJS; Seon's focused gate follows only
after the dependency coordinate is intentionally advanced.
