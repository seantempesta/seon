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
namespace's whole require closure. The first fix left that invariant without
a regression. It recurred at `6f80d1a4d` with Edamame, blocking batch 121's
explicit preparation as well as its gates (`tmp/orchestrator/gate-results/batch-121.log`).

The selector now reads source forms with Clojure's own non-evaluating reader;
there is no parser dependency to repeat in the tool alias. The canonical
`seon.test-runner-test/dependency-tool-loads-selection` regression launches
the actual `clojure -T:dev-cache` tool classpath, loads the selector and
enumerates its input digests. Future require/classpath drift fails this
recurring platform regression instead of relying on a test-classpath load.
Verification is recorded in
[the guardrails landing note](../../prds/steward-platform/research/lane-guardrails-2026-09-17.md).
