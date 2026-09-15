---
type: issue
status: open
severity: friction
tags: [issue, test, sci, wave/agent-context]
---

# SCI evaluation tests still expect retired storage

A broader diagnostic run on 2026-09-09, after the reader/empty-directory
change, exposed failures outside that change in `seon.sci.eval-test`:

- `success-evaluation-assembles-every-optional-projection` expects retired
  result-edn and saved defs; the owner now retains live values and shown text.
- `unmap-row-carries-the-exact-forked-namespace-state` expects saved namespace state.
- `an-unbound-var-remains-structured-after-production-admission` expects a
  serialized opaque marker instead of the retained live object.
- `acquisition-uses-the-effective-config-projection-when-instrumented` manually
  restores callable roots but does not restore the instrumentation registry.
- `one-unloadable-row-cannot-prevent-cold-acquisition` does not establish that
  its authored setup rows were accepted; no valid row is acquired.
- `acquisition-binds-loaded-first-party-compiled-vars` fails at cluster population.

The exact log and total are in the context-blocks landing note. These are
observations, not a claim that all failures share one cause. The reply-reader
lane leaves this namespace unchanged. Its canonical documentation and full
loop regressions verify the changed behavior. Also update the legacy empty-dir
expectation from nil to the ruled empty data value when repairing this namespace.

Acceptance: canonical fixtures, successful checked setup, instrumentation
preserved by the standard helper, and assertions of current §14–§15 semantics.
Do not restore retired storage mechanisms to make old assertions pass.

## Re-observed by run4-blockers, 2026-09-15

The HEAD-plus-owned-reader-paths fast run at `6785c980c` again failed
`success-evaluation-assembles-every-optional-projection`,
`unmap-row-carries-the-exact-forked-namespace-state`,
`one-unloadable-row-cannot-prevent-cold-acquisition`, and
`an-unbound-var-remains-structured-after-production-admission` at the same
retired-storage/fixture boundaries above. The new reader regression is
isolated in `test/seon/run4_reader_test.clj`; `eval_test.clj` remains unchanged.
