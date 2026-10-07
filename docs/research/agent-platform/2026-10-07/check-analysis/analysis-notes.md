# check-analysis: why the check JVM's fixtures read no dependency analysis

Cause. Not the check JVM's cache: its archive links `.clj-kondo/.cache` to a root cache seeded from default's
(`script/seon/operator.clj` `root-analysis-cache!`), the dependency-set key is identical (7ac1e9371289 on default and
on the HEAD archive) and every key directory holds `clojure.test.check.clojure-test` and `seon.db`. The same
`seon.fn-test` is red on default itself (410 pass / 2 fail / 8 error, `bin/test-check --ns seon.fn-test`).

Producer. `seon.fn.analyzer/analysis-cache-root` returns nil for a scratch fixture (by design: only checkout source
writes the shared cache), so `analyze` runs with `:cache false` and every namespace it does not define is
`::unanalyzed-namespaces`; `seon.fn/host-bound-callers` then refuses `seon.db/q`, `defspec`. The REPL batch got the
read-only join (`shared-dependency-analysis`, `with-dependency-analysis`) in 1e61a3d44; `analyzed-artifacts` (the
`program-entities` path) never did. Fix: join it there when the analysis owns no cache. Also two tests held stale
cache paths (`.clj-kondo/.cache/v1/...` instead of `analyzer/cache-directory`).

Before (check JVM, HEAD + docs): seon.fn-test pass 410 / fail 2 / error 8.
After: pass 494 / fail 1 / error 0 (first run also showed a 5.4 s vs 5 s bound timing under concurrent load).

Remaining, real and unrelated: `seon.fn-test/fixture-observations-survive-static-admission-and-the-fold`
(fn_test.clj:1694) - the runtime batch entity for a deftest carrying `^{:seon.test/fixture-observation ...}` has no
`:seon.test/fixture-observation`. Red on default and in the check on HEAD. Not filed as a durable task by this lane
(owner: seon.test / seon.batch lane).
