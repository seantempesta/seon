---
type: issue
status: resolved
severity: blocker
created: 2026-09-16
tags: [testing, gate, classpath, dependencies, platform]
---

# The dev-cache tool classpath omitted the selector's own dependencies

## Problem

`dev-cache/ensure-cache` derives the gate's input digests from the one
selector by `load-file`-ing `src/seon/test/selection.clj` into the
`-T:dev-cache` tool JVM (`dev_cache.clj`, `test-inputs`). That tool alias
declared only `io.github.clojure/tools.build`, resting on a comment claiming
the selector was a "pure namespace".

Commit `6df6967b8` ("Derive widening from program graph coverage") gave the
selector a `babashka.process` require, to enumerate inputs with
`git ls-files`. Nothing failed at that commit's own gate, because the
selector loads correctly on the `:test` classpath. Every subsequent
`bin/test` refused at its `dependency-cache-and-classpath` phase:

```text
Execution error (FileNotFoundException) at seon.test.selection/eval595$loading
Could not locate babashka/process__init.class, babashka/process.clj or
babashka/process.cljc on classpath.
```

Observed at HEAD `0c7711e59`; four queued `bin/test` invocations across lanes
sat at `phase=snapshot` with no `dependency-cache-and-classpath` line
recorded in their `tmp/test-runs/*/test-run.txt`.

## Why it matters

The one correctness gate was unrunnable tree-wide, and the refusal names a
class file rather than the alias that owes it, so the cause reads as a
corrupt cache rather than a missing declaration.

## Resolution

`deps.edn`'s `:dev-cache` alias now carries `babashka/process`, and
`dev_cache.clj`'s comment no longer claims the loaded namespace is
dependency-free (`f33e9c05a`). deps.edn bytes are part of
`dependency-configuration-digest`, so the next gate rebuilt the immutable
dependency closure once: **57,424 ms**, 372 namespaces.

## The class, not the instance

A tool classpath that load-files a first-party namespace inherits that
namespace's whole require closure, and nothing checks the two agree. The
durable form of this check is a regression that loads
`seon.test.selection` on the `:dev-cache` basis alone; this note is filed
rather than that regression being written here, because the gate's own
`dependency-cache-and-classpath` phase is the surface that should report it
by name — see
[test preparation costs](../../prds/steward-platform/research/test-preparation-costs-2026-09-16.md).
