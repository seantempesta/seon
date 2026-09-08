---
type: research
status: active
tags: [research, schema, runtime, test]
---

# Cluster-scoped registry, 2026-09-08

## Outcome and JVM ownership

Host Vars belong to the loaded JVM program. `apply!` reads their authored
metadata, arms each unwrapped root once, and leaves existing wrapper identities
alone. A reload produces a new root which the same seam arms. `remove!` cannot
remove JVM-owned wrappers. A cluster's `:record` request cannot disable host
validation; the existing interpreted-function policy remains local.

At invocation, the wrapper takes the explicit request/environment projection,
or the projection carried by the calling SCI context. The cluster's function
contract compiles with that projection's registry and lives in its existing
projection-local cache. Calls without cluster custody use an immutable packaged
boot declaration projection, never Malli's mutable default registry. Boot report
caps remain JVM policy; this does not claim independently loaded host programs
or atomic hot reload. Nested projection carriers now select the innermost value.

## Census and dependency ledger

| Source seam | Final disposition |
|---|---|
| schema `seon-registry`, `relink-registry!` | Deleted global facade and `mr/set-default-registry!` write |
| schema.edn `load!` | No global relinking |
| instrument `mi/-collect!` | Deleted global contract collection; loaded Var metadata supplies declarations |
| instrument `mi/instrument!`, `mi/unstrument!` | Replaced with identity-preserving JVM wrappers; no cluster removal |
| schema `!ambient-shape-projection` | Deleted; shape inspection takes the handed projection |
| schema `!database-projections` | Deleted LRU; caller supplies the reusable prior projection |
| schema declaration compilation | Carries explicit `:registry` compile options |
| schedule `declared-maintenance-request-values` | One remaining implicit `m/schema`; exact protected-owner hunk below |
| test runner `m/function-schemas` | Read-only global-drift observation, not contract lookup |
| schema `!fallback-counts` | Process diagnostic counts only; no cluster state |

The source census covered Malli schema/function-schema compilation, validation,
explanation, collection and default-registry writes. Remaining compilation in
schema/internal, program, call-preparation, render/ns and test/accretion has an
explicit registry or receives an already compiled schema. `m/default-schemas`
is the fixed built-in population; `mr/var-registry` resolves loaded schema Vars.
Neither selects a cluster. No production Seon writer remains for Malli's global
function-schema registry or default registry in the owned implementation.

Dependencies read: `reference-code/malli/src/malli/instrument.clj` (`-schema`,
`-f->original`, collection and root alteration), `reference-code/malli/src/malli/core.cljc`
(`-instrument`, function-schema registration), and the registry protocols.
The existing first-party pattern is `schema/projection-cache-value`: derived
validators are held by the projection that owns their declarations.

## Regression and measured evidence

`test/seon/registry_isolation_test.clj` creates two canonical database fixtures,
seeds their clusters, records different schema/function-contract facts, forks
real SCI contexts, and advances each context's own projection. It verifies
private declaration visibility, actual SCI execution, integer versus string
contracts on the same host Var, invalid-input rejection, nested carrier
precedence, unchanged wrappers across both arm/remove calls, and unchanged
Malli global function schemas. Cleanup restores entering callable roots directly;
production cluster removal is deliberately not a fixture teardown mechanism.

Earlier landed slices:

- `b49c400e3`: shape holder deletion, fast and isolated **20 tests / 202 assertions** green.
- `371a50dba`: database LRU deletion, fast and isolated **20 / 200** green.

First complete isolated regression: **43 / 318**, zero failures/errors, but
worker drift exposed 28 newly armed test Vars. The regression cleanup was fixed.
First platform: **80 / 443**, two failures, zero errors. One exposed wrapper
frames in missing-projection diagnostics and is fixed in schema. The other is
the protected test-support expectation described below. Final isolated gate: **46 tests / 328 assertions**, zero failures/errors and
no worker-global drift; base preparation **188302 ms**, coordinator/tests
**157 seconds**. All seven gated source/test files matched the main checkout
byte for byte. Corrected declaration-population fast loop: **3 / 10** green. Expanded fast testing additionally exposed a pre-existing
cold-read assumption in the owned declaration-population test: shipped print
defaults are already a delay. The test now permits its first resource acquisition
and requires zero subsequent reads, while config operations still require one
complete acquisition.

Live default JVM PID 36758, after explicit REPL reload and removal of the old
loaded registry facade, returned in **4473 ms**:

```clojure
{:same-wrappers true, :left-schema-absent-in-right true,
 :left-rejects-string true, :global-schema-lookup-refuses true,
 :right "right", :global-function-schemas 0, :right-rejects-int true,
 :wrapped 912, :left 7}
```

The temporary probe namespace was removed. After the default cluster restarted,
the retained [probe script](cluster-scoped-registry-live-probe-2026-09-08.clj)
passed again in **7796 ms**, on fresh JVM **45036**, with **910** observed wrappers
and the same booleans and values. All four retired registry/cache Vars were
absent and global function-schema count remained zero (2 ms read). The final script
bytes, including explicit requires, repeated that result in **8711 ms**. This is a hot-reloaded JVM proof,
not successful development adoption. CLI adoption twice failed at
`script/seon/dev/clj_kondo.clj:3:3` because Babashka could not load `seon.id`;
the existing `hook-babashka-cannot-load-seon-id.md` owns that separate failure.
The initial live snapshot had 912 wrappers and 98 global schema namespaces.

## Protected test fixture migration

The platform's remaining obsolete expectation is
`test/seon/test_support_test.clj:74`: after production `instrument/remove!`,
it asserts zero wrappers. The fixture in `test/seon/test_support.clj:631` also
uses production removal as teardown. These files have concurrent owner edits
and were not modified. Their migration is concrete: fixture teardown directly
unwraps current roots with Malli `mi/-f->original`, then restores entering roots
and the saved function-schema atom. Its regression should deliberately unwrap
one entering root inside the fixture, assert that change, throw, then verify
exact restoration. It must not assert that a cluster can remove all wrappers.
The owned instrumentation and isolation tests already use direct root restoration.

## Schedule caller handoff to turn-cut

The remaining implicit runtime Malli lookup is in the concurrently edited
`src/seon/schedule.clj`. The orchestrator requested the hunk here, without
editing turn-cut's file. This obtains one projection from the immutable
database already handed to execution-context and passes it to the lookup:

```diff
             [seon.operator.runtime :as operator.runtime]
+            [seon.schema :as schema]
             [seon.schema.edn :as schema.edn])
@@
 (defn- declared-maintenance-request-values
-  [effective]
+  [projection effective]
   (select-keys
    effective
    (m/explicit-keys
-    (m/deref (m/schema :seon.maintenance.request/value)))))
+    (m/deref
+     (m/schema :seon.maintenance.request/value
+               {:registry (:seon.schema.projection/registry projection)})))))
@@
 (defn- execution-context
   [database cluster]
-  (let [cluster-name (:seon.cluster/name cluster)
+  (let [projection (schema/projection-from-database database)
+        cluster-name (:seon.cluster/name cluster)
@@
-    (merge (declared-maintenance-request-values effective)
+    (merge (declared-maintenance-request-values projection effective)
```

This is a handoff, not an applied or verified schedule change. The default
Malli registry no longer supplies Seon declarations in a fresh JVM after the
registry change; this caller must carry its registry before that path runs.


## Session history and verification method

The initial source-span fixture failure was recorded in `4b56920b1`; the
orchestrator superseded that stop and requested the snapshot workaround.
The implementation gates use `tmp/registry-wt` at `82d8ce7cd`, overlaying only
owned files and linking the maintained dependency sources. No foreign session
or protected source was changed. Main checkout gates blocked on a shared
dependency-cache lock were terminated and reaped before independent retries.

AGENTS.md was supplied end to end and read from disk. PRD §10, roadmap entry
and working edge, concurrency landing and its instrumentation issue, plus the
data-modeling, datahike, data-oriented Clojure, REPL and testing skills informed
the change. The initial note's claim that no implementation landed is superseded.

## Exact gate invocation

From the isolated snapshot worktree, with the main owned bytes copied in:

```sh
bin/test --paths src/seon/schema.clj src/seon/schema/edn.clj src/seon/instrument.clj test/seon/schema_test.clj test/seon/instrument_test.clj test/seon/registry_isolation_test.clj test/seon/schema/declaration_population_test.clj -- seon.instrument-test seon.schema-test seon.registry-isolation-test seon.schema.declaration-population-test
```

The platform invocation used the same first six paths with `--platform`.
No `--all` or `--full` gate was run. Kondo over the owned source, tests and
retained probe reported **0 errors**; existing warnings were retained.

Touched implementation: `src/seon/schema.clj`, `src/seon/schema/edn.clj`,
`src/seon/instrument.clj`; tests: `test/seon/schema_test.clj`,
`test/seon/schema/declaration_population_test.clj`, `test/seon/instrument_test.clj`,
`test/seon/registry_isolation_test.clj`. Documentation: this note and the live
probe; archived instrumentation ownership issue; separate open adoption and
protected-fixture follow-ups. The initial source-span issue observation was
already committed in the first research slice.

The final isolated gate exited 0 and removed its successful run root. All
owned command sessions were reaped; the lane snapshot and scratch outputs were
removed before reporting. The main repository dependency tree was preserved.
