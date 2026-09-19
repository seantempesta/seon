---
type: research
status: complete (bounded Malli repair; source-publication liveness remains)
created: 2026-09-20
tags: [schema, malli, performance, projection]
---

# Projection compilation stall

## Boundary and grounding

Read AGENTS.md §§0–5, the adoption-silence diagnosis, the turn-cluster
kind-sweep note, and the error-conversion PRD end to end. Applied the
`data-oriented-clojure`, `clojure-testing`, and `repl` skills. Default's
read-only MCP health observation identified PID 41822; no lifecycle command,
reload, adoption, or mutable evaluation was sent to default.

Reproduction worktree: `tmp/projection-compile-wt`, detached at
`1e38fbb418ae4330b87f285ed17ff163fb678e99`. Dependencies and `target` were
linked to the main checkout. After the orchestrator's interruption, copied
the committed repository-wide slot owner (`2cdb7ef8a`, `bin/_test-slot`)
into the disposable worktree so its launcher uses the same two slots.
No held production file was edited.

The prior note does not contain the exact rolled-back facet bytes or its
producer diff. This reproduction replaces the existing
`:seon.cluster.source/refused-error` declaration with these concrete bytes;
it does not claim to recover the omitted original diff:

```clojure
:seon.cluster.source/refused-error
[:and {:seon.db/attributes true
       :seon.render/ai seon.error/render-ai
       :seon.render/html seon.error/render-html}
 :seon.error/base
 [:map [:seon.cluster.source/refused :seon.cluster.source/refused]]]
```

Command (in the isolated worktree):

```sh
bin/test-fast --paths src/seon/cluster/source.clj resources/seon/schemas/seon.cluster.source.edn -- seon.cluster.source-test
```

## Dependency ledger

- Malli fork before: `3517a3cd9271b2083780ac7be1725493905bca2e`;
  `reference-code/malli/src/malli/core.cljc:1943` identifies a ref by
  registry scope and name; `:1985` uses that key in the dynamic recursion
  map to tie a validator knot. `:2500` constructs property registry scopes.
- `reference-code/malli/src/malli/registry.cljc:55`: composite enumeration
  merges its registries; lookup does not need enumeration.
- `reference-code/malli/src/malli/core.cljc:2627`: validator caching belongs
  to a compiled schema object, not to a registry identity. `schema` at
  `:2551` constructs fresh pointer objects for keyword lookups.
- `src/seon/schema.clj:1134`: candidate registry enumeration merges defaults
  and the complete forms population; `:3474` validates candidate values.
- `src/seon/schema.clj:1795`: projection construction already derives a
  canonical dependency graph and refuses canonical cycles before the fold.
  `assert-complete-contract!` carries a path-local visited set; build-scoped
  reference advisories are memoized. The fold is at `:1412`.

## Samples

Three samples of the SAME original JVM, PID 65183, obtained with
`jcmd 65183 Thread.print`, ten seconds apart:

| Local timestamp (2026-09-19) | Observed hot path |
|---|---|
| 16:59:28 | `candidate_registry._schemas(schema.clj:1147)` → composite `-schemas` → `malli.core/-identify-ref-schema(core.cljc:1949)` → ref/tuple/or validator construction |
| 16:59:38 | same enumeration and ref-validator path |
| 16:59:49 | same enumeration and ref-validator path; main CPU 85,223 ms, JVM elapsed 95.61 s |

The caller was `seon.instrument/compiled-wrapper` → `arm-var!` →
`seon.test.arm/initialize-contracts!`. Contract arming progressed from
packaged projection acquisition at 22:58:42.721Z to armed at 22:59:59.041Z
(76.320 s). This establishes sighting 1's expensive mechanism during the
sighting 2 command, before the test body.

The first test subsequently completed, and
`stale-incremental-upsert-preserves-the-newer-publication` began at
23:02:49.813Z. Three more samples ten seconds apart observed schema/Datahike
population work, not `fold-contract-validations` recursion. Thus the exact
reported fold stall has not been established by those samples.

I incorrectly overlapped the baseline, changed-fork run, schema run and raw
probe. The orchestrator terminated PIDs 65183, 67799, 68727 and 69167 for
machine pressure. Those interrupted runs supply stack observations only;
none supplies a passing tally or a trustworthy before/after performance
comparison. Subsequent runs are strictly serial, one owned JVM at a time.

## Serial measurements and hypotheses

One fresh JVM (72576), 3,215 canonical declarations with the reconstructed
facet. Warmed projection once, then measured old and new ref-scope functions
in the SAME JVM and population. Both calls ran real `build-projection`,
`fold-contract-validations`, candidate validation and Malli. The only
before/after substitution is the exact old ref identifier.

| Work | Before | After |
|---|---:|---:|
| Full projection wall time | 1,095.672 ms | 722.961 ms |
| Projection build entries (delegating arity + implementation) | 2 | 2 |
| Fold calls / inclusive time | 1 / 24.252 ms | 1 / 12.194 ms |
| Full-population merges during projection | 0 | 0 |
| 100 candidate validations wall time | 644.447 ms | 22.735 ms |
| Candidate registry constructions | 100 | 100 |
| Candidate registry construction inclusive time | 7.905 ms | 2.806 ms |
| Registry enumerations / full-population merges in those validations | 2,000 / 2,000 | 0 / 0 |
| Registry enumeration inclusive time | 393.921 ms | 0 ms |

(a) **Confirmed.** Per-ref scope identification enumerates, rebuilds and
hashes the complete registry. Keeping the resolving registry removes that
work, while retaining the same recursion-knot mechanism.

(b) **Fresh construction confirmed; proposed cache inference falsified.**
Two `m/validator` calls using the SAME candidate registry still return
different validator objects. Registry identity memoization alone does not
activate Malli's schema-object cache. Its measured construction cost is
small here and the expensive enumeration disappears at its caller. No
process-global identity cache or population-retaining memoization was added.

(c) **Not reproduced.** The complete facet population passes canonical
cycle admission; the fold runs once in milliseconds. The base has no union
back-edge to every facet. No seen-set rewrite or validation weakening is
justified by these observations. Projection wall time includes other work
and warm-JIT effects; the reliable structural improvement is elimination
of registry enumeration during ref validator construction.

## Repair and verification

Fork branch `seon-ref-scope`, commit
`606083c5c5b388e84d169c7080af33ed3ec242ae`. Ref scope retains the actual
`:registry` option or default registry. Raw map registries are retained
rather than freshly wrapped by `m/-registry`, preserving termination for
recursive external map registries. Property registry instances keep nested
shadowing distinct. No validator predicates, accepted values or timeout
bounds change. No `src/seon/schema.clj` edit is needed for this measured fix.

Fork regression: 1 test, 8 assertions, 0 failures/errors. Exercises recursive
raw-map and Registry-object scopes, invalid nested values, property-registry
shadowing, and zero enumeration. The Seon regression uses the canonical
database fixture, adds one base-extending facet to its complete forms,
awaits projection completion under the existing 20-second event backstop,
and checks positive and negative facet validation plus zero enumeration.

Serial armed schema run, PID 73169: **33 tests, 3,494 assertions, 24
failures, 1 error**, exit 1. The new regression passes all six assertions:
projection 2,113.487 ms, registry merges 0, within the existing 20-second
backstop. The canonical fixture populated before that timed section.

Verification boundary: 14 failures in
`canonical-reference-values-use-the-pull-collection-grammar`, 3 in
`a-refused-projection-source-never-yields-a-projection-with-no-forms`,
7 in `render-declarations-require-a-contract-that-accepts-their-shape`, and
1 error in `pulled-forms-derive-from-the-entity-schema-and-selector`.
The returned diagnostic explicitly reports that `seon.error/diagnostic`
refuses its input for missing required `:seon.error/at` (also layer and
operation); `src/seon/schema.clj:2796` calls the constructor with only the
old diagnostic fields. Render coherence similarly passes the legacy map
instead of base fields. These are the held producer/contract sites owned
by error-family-1a; no edits were made there. This tally is not a green
schema namespace claim. The large nested assertion output is itself
unreadable; the actionable boundary is the constructor's three absent
required members, preserved here rather than copying its repeated dump.

Serial source reproduction, PID 76139, uses the same command and reconstructed
facet. First test completed in 172.548 s. The named stale-publication test
started at 23:16:50.279Z. Three `jcmd` samples at local 17:17:09, 17:17:19,
and 17:17:29 observed source analysis, schema admission, and schema-shape encoding; no
`fold-contract-validations` frame. The middle sample is specifically
`build-projection:1861` → `schema.internal/assert-compilable-schema!:365`
→ `mr/fast-registry` → `HashMap.putAll`. That helper rebuilds a registry
from the population even with supplied compile options (`internal.cljc:362–366`).
This is a separate, unmeasured population-copy cost, outside the assigned
files; no claim that it caused the full stall is made. It belongs to the
existing `docs/seon/issues/class-local-updates-recompute-global-projections.md`
class. **Exit 124** at the existing 320-second reporter-silence watchdog
(23:22:12.917Z); only the first test completed. Later samples showed
`seon.fn/unresolved-callers` through Datahike query/cache operations, then
`seon.db/write-attribute-error` through armed predicates while admitting
transaction values. None of those samples contains the reported recurring
fold expansion. The source-publication liveness boundary remains, and no
green source namespace tally is claimed. No deadline was raised. This lane
repairs the measured Malli enumeration cost, not every cost of two complete
source populations inside that test. At the watchdog itself the main thread
waited on the transaction promise and `async-mixed-20` ran
`seon.db/write-owned-values-error/owners-of` (`db.clj:3583`) through
Datahike index slices. This matches the already-open writer-cost boundary in
[the publication write-bound issue](../../../seon/issues/the-thirty-second-write-bound-fails-program-publication-under-load.md);
its owner is held.

The final regression retains the canonical fixture's full function-contract
population when building the extended projection. Final serial fast run on
`721b110b841b0cbe00ba28637a46b992bd787394` (PID 81490): **33 tests, 3,495
assertions, 24 failures, 1 error**, exit 1; the same four pre-existing
constructor-boundary test names account for all failures/errors. The new
regression passes all seven assertions, with **1,365 function contracts,
1,492.778 ms projection time, and zero ref-validator registry merges**.
This snapshot was created only after PID 76139 exited and the original
reproduction worktree was removed. The armed run loaded the real program,
not a schema roster. No whole-namespace green or live-default proof is claimed.

## Landing boundary

Main-repository changed paths are exactly the Malli gitlink,
`test/seon/schema_test.clj`, and this note. The fork changes
`src/malli/core.cljc` and `test/malli/ref_scope_test.clj`. No Seon production
source, held producer/contract site, or schema resource change lands.
Scratch worktrees and probe files are removed before reporting. No owned
JVM remains after the serial runs; the previously interrupted runs were
confirmed absent before serial measurement resumed.

The post-commit HEAD load check uses a fresh detached worktree and
`clojure -M:test` to require `seon.schema-test`, `seon.instrument-test`,
and `seon.cluster.source-test`. This is a load check, not another gate. At committed HEAD `4806aad03`,
it exited 0 and printed `:head-load-ok`. The detached worktree, task-owned
sample directory and all task-owned probe logs were then removed; only the
committed evidence and regressions remain. This final documentation update
changes no program bytes from that successful load.

Cold proof owed to the orchestrator after the gitlink lands:

```sh
bin/test --paths test/seon/schema_test.clj -- seon.schema-test seon.instrument-test seon.cluster.source-test
bin/test --platform
```

The fork is selected by the committed gitlink; the path parser does not
accept `reference-code` overlays. Default adoption/restart proof is also
owed; this lane deliberately leaves default untouched.

## Reproducible serial probe

Run the following from the isolated worktree after declaring the facet.
The script was executed from `tmp/projection-measure.clj`; its exact source
is retained here so cleanup does not erase the evidence procedure.

```clojure
(require '[seon.schema :as s] '[seon.schema.edn :as edn]
         '[malli.core :as m] '[malli.registry :as mr])
(def forms (edn/packaged-forms))
(def population-merges (atom 0))
(def original-merge merge)
(def counts (atom {}))
(defn measured [v f]
  (fn [& args]
    (let [start (System/nanoTime)]
      (swap! counts update-in [(str v) :calls] (fnil inc 0))
      (try (apply f args)
           (finally (swap! counts update-in [(str v) :ms] (fnil + 0.0)
                           (/ (- (System/nanoTime) start) 1e6)))))))
(def original-identify @(ns-resolve 'malli.core '-identify-ref-schema))
(def old-identify (fn [schema] {:scope (-> schema m/-options m/-registry mr/-schemas)
                               :name (m/-ref schema)}))
(def value {:seon.error/at (java.util.Date.) :seon.error/layer :probe/layer
            :seon.error/operation 'probe/check :seon.cluster.source/refused :probe/rule})
(println :pid (.pid (java.lang.ProcessHandle/current)) :forms (count forms)) (flush)
(s/build-projection forms)
(doseq [[label identify] [[:before old-identify] [:after original-identify]]]
  (reset! counts {}) (reset! population-merges 0)
  (with-redefs-fn
    (assoc (into {} (map (fn [v] [v (measured v @v)]))
                 [#'s/build-projection #'s/fold-contract-validations #'s/candidate-registry #'mr/-schemas])
           (ns-resolve 'malli.core '-identify-ref-schema) identify
           #'clojure.core/merge
           (fn [& maps]
             (when (some #(and (map? %) (contains? % :seon.error/base)) maps)
               (swap! population-merges inc))
             (apply original-merge maps)))
    (fn []
      (let [start (System/nanoTime)]
        (s/build-projection forms)
        (println label :projection-ms (/ (- (System/nanoTime) start) 1e6)
                 :population-merges @population-merges :counts @counts) (flush))
      (reset! counts {}) (reset! population-merges 0)
      (let [start (System/nanoTime)]
        (dotimes [_ 100] (s/valid-candidate-value? forms :seon.cluster.source/refused-error value))
        (println label :candidate-100-ms (/ (- (System/nanoTime) start) 1e6)
                 :population-merges @population-merges :counts @counts) (flush)))))
(let [r (#'s/candidate-registry forms)
      a (m/validator :seon.cluster.source/refused-error {:registry r})
      b (m/validator :seon.cluster.source/refused-error {:registry r})]
  (println :same-registry-identical-validators (identical? a b)))
(shutdown-agents)
```
