---
type: research
status: active
tags: [test, publication, path-identity]
---

# Cloned-base path identity — protected-file boundary

Dated 2026-09-17. Bounded implementation assignment; stopped before production
edits under its explicit protected-file stop rule. Neither requested
implementation commit is complete.

## Evidence and dependency boundary

Read the assigned issue, the peer's complete note from `86cda05b6`, and
`tmp/orchestrator/wave2/repl-rule.txt` end to end. The issue's measured
baseline remains 2,048 absolute digest keys, 337 discarded file analyses,
then a complete analysis. No new analysis-count experiment was run.

`bin/seon status` and MCP runtime status observed default alive, PID 30138,
prepl 56009. A read-only MCP JVM probe read `build/current-src.edn`:

```clojure
{:manifest-roots ["/Users/sean/src/seon/src"
                  "/Users/sean/src/seon/test"]
 :file-count 332
 :sample ["/Users/sean/src/seon/test/seon/fixtures/run7_token_observations.edn"
          "aa85932dc6b0517922a63df9e96451f9ad91a81f6a028853547ccd4cece2382d"]}
```

The same probe's `seon.fn/tests-reaching` query used a dereferenced explicit
connection without carrying its projection and returned
`:seon.schema/missing-projection`. It selected no tests and proves no reach.
Future selection must use `seon.db/db` with the appropriate projection.

`seon.fn/artifact` currently emits canonical absolute file identity;
`seon.fn/build-manifest` emits canonical absolute manifest roots.
`seon.cluster.source/snapshot` emits canonical absolute digest-map keys.
The new relative identity must replace the old identity key under the
assignment's schema-breakage rule. That requires these protected consumers:

* `src/seon/effect.clj:286`, `seon.effect/write-back-adds`:
  `(db/pull database [:db/id] [:seon.fn.file/path path])` resolves effect
  write-back provenance. It must resolve the new relative identity with
  the publication's root; keeping the old lookup silently loses provenance.
* `src/seon/test.clj:32`, `seon.test/failure-text`:
  `(get-in failure [:seon.test.failure/file :seon.fn.file/path])` must read
  the replacement key or the displayed source site disappears.

These are explicitly protected by the assignment even though neither file
had an uncommitted edit when inspected. No other lane was contacted or
modified. Existing edits in `src/seon/instrument.clj`,
`test/seon/instrument_test.clj`, `test/my/test_test.clj`, and
`test/seon/test_support.clj` were preserved.

## Verification and remaining work

No functions redefined, adoption attempted, tests run, test JVM launched,
scratch root created, or default lifecycle operation performed. There are
no before/after counts beyond the cited baseline, no passing regression,
and no implementation commit to queue for the cold gate. RESET NEEDED is
anticipated for the requested identity replacement, but no schema changed.

Resume requires assignment of the two protected consumer hunks. Then land
the interim root-mismatch guard and canonical count regression first;
land root-relative identity and clone no-op/one-file regressions second.
The cold boot-test proof remains the orchestrator's responsibility.

Files touched by this boundary report: this landing note and
`docs/seon/issues/a-cloned-published-base-names-a-checkout-that-is-gone.md`.
