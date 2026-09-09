---
type: defect
status: resolved
severity: friction
tags: [testing, runner, classpath, class/concurrency]
---

# Concurrent classpath builds corrupt the Maven model cache

Observed 2026-09-08 with five lanes gating at once: `bin/test` failed before
any test with

```
Error building classpath. class java.util.HashMap$Node cannot be cast to
class java.util.HashMap$TreeNode … at
org.apache.maven.model.validation.DefaultModelValidator.validateId
```

a `HashMap` corrupted by concurrent mutation inside tools.deps' Maven model
resolution — several `clojure` launches computing the same classpath at
once against one `~/.m2` / `.cpcache`. A retry passed. `bin/test`'s
`:dev-cache` guards the compiled-class cache but not classpath computation.

## Fix

Compute the classpath once per checkout digest under the same lock the dev
cache holds (`clojure -Spath` written beside the cache), and launch every
worker with `-Scp` from that file; a second concurrent invocation waits on
the lock instead of racing. The pool-sizing change (`90170c3c8`) reduces
the exposure; it does not remove the race.


## Resolution — 2026-09-08

`45e5c6c56` admits the test classpath once per snapshot digest under the
existing development-cache lock; preparation, coordinator, and workers
receive it through `-Scp`. `f2e3bcb34` keys dependency classes only on
dependency configuration, submodule pins, and the JDK, excluding first-party
source and checkout locations. Source edits therefore select new publication
digests while reusing dependency classes. The isolated runner gate at
`90d381603` passed 41 tests / 259 assertions with zero failures or errors,
including two real concurrent nested gates and cache identity/reuse checks.
Phase and contention measurements are recorded in the
[runner base cache landing note](../../prds/context-generation/research/runner-base-cache-landing-2026-09-08.md).
