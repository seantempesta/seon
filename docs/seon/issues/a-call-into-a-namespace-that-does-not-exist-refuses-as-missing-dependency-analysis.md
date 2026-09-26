---
type: issue
status: open
severity: platform
created: 2026-09-26
tags: [issue, fn, analysis, error, agent-platform]
---

# A call into a namespace that does not exist refuses as missing dependency analysis

Found by lane fix-eval-schema-reds-opus (default pid 94554, snapshot f240fd666) while
classifying `seon.sci.eval-test/non-evaluable-agent-source-reports-its-jvm-fallback`.

- **Observed.** An agent override `(defn valid? [length id] (s3.missing/function length id))`,
  analyzed through `test-support/program-fn-row`, throws from `seon.fn/unresolved-macro-refusal`
  (src/seon/fn.clj:1001-1018): "Cannot classify host binding: clj-kondo holds no analysis of
  s3.missing. Fill the checkout's cache from this JVM's classpath at its REPL:
  (seon.fn.analyzer/analyze-dependencies!)", with `:seon.fn/dependency-analysis-missing true`
  and declared schema `:seon.fn/dependency-analysis-missing-error`.
- **Why it is wrong.** `s3.missing` is on no classpath entry and in no loaded JVM namespace,
  so no cache fill can ever analyze it. The error says the platform's dependency cache is
  stale, and its declared schema makes it a core fault that `seon.turn` stores and delivers to
  root, when it is the author's mistake: a call to a namespace that does not exist.
- **Producer.** `seon.fn.analyzer/unanalyzed-namespaces` (src/seon/fn/analyzer.clj:430-444)
  treats every var-usage target without a kondo cache file or built-in resource as
  unanalyzed. `host-bound-callers` (src/seon/fn.clj:1028-1044) then refuses all of them
  through `unresolved-macro-refusal`. Introduced by a9449b7c3 (F7, 2026-09-24).
- **Smallest fix at the owner.** Split the unanalyzed targets by whether their namespace
  exists: a namespace with neither a loaded JVM namespace nor a classpath resource
  (`.clj`/`.cljc`) is an unresolved namespace, returned to the author as a declared agent
  mistake that names the target. Only an existing namespace without analysis stays the
  platform refusal.
- **Regression.** The source above is refused as the author's mistake naming
  `s3.missing/function`, and `(seon.schema/register! ::x :int)` with an existing but
  unanalyzed namespace still refuses as missing analysis.
- The eval test now uses an unresolved local (`missing-function`) to reach the JVM
  fallback it covers (landing fix-eval-schema-reds-2026-09-26).
