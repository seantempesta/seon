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
