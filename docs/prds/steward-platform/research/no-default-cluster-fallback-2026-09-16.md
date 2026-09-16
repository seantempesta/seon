---
type: research
status: in progress; bounded implementation, protected sites deferred
created: 2026-09-16
tags: [config, schema, program-graph, wave/config-cluster-identity]
---

# Explicit cluster inputs and declared classifications

Read AGENTS.md sections 0–3 and 5–7, and both
[the workaround inventory](workaround-inventory-2026-09-16.md) and
[the program-facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
end to end, including inventory §1 and its ranked list and PRD S6.
This assignment permits fast iteration, forbids a cold gate and default
lifecycle operations, and stops each item at a concurrently held dependency.

## Dependency ledger and inherited state

- Configuration uses the existing `seon.config` compiler, `seon.schema.edn`
  composite builder, `seon.schema.form/attr-form-properties`, and the canonical
  `seon.test-support/with-database` fixture. Read the schema EDN loader and
  schema-form inspection owner end to end; inspected the compiler, its callers,
  and its existing tests before editing.
- Malli authored forms carry explicit properties; no new classifier or
  registry is introduced. Existing armed function contracts refuse omitted
  arguments and required map entries, naming the callable.
- The schedule proc already holds its cluster handle and passes an explicit
  execution context. The dependency's four lifecycle arities and argument
  carriage are documented in
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165`.
  No new running machinery is introduced.
- `bin/seon status` reported default PID 41413 alive. MCP runtime status
  answered but reported 95 failed tests, two error signatures, two stale Vars,
  and an unknown search-proc observation. These are inherited observations,
  not attributed causes. No restart or reset was performed.
- The one permitted read-only prepl evaluation queried packaged forms through
  `seon.schema.edn/packaged-forms` and
  `seon.schema.form/attr-form-properties`: 89 prefix-matched attributes,
  94 explicitly declared dials, **zero prefix-only attributes**, 50 ms.
  There will be no second prepl evaluation in this assignment.

## Class 2: config membership

`src/seon/schema/edn.clj` removes the namespace-prefix arm of `config-dial?`.
No resource lacks the property, so no resource change is needed for this class.
`test/seon/schema/edn_test.clj` now explicitly declares the existing synthetic
registered dial and adds
`config-dials-are-declared-independent-of-their-names`: nonempty old-union,
declared, and composite populations must agree; a prefixed nondial is excluded
and a declared dial in an unrelated namespace is included.

Verification: **16 tests, 52 assertions, zero failures/errors**, armed
contracts, snapshot HEAD `f992669f93afdec00c50edfa47d56db46822880e` plus these two
paths. Command: `bin/test-fast --paths src/seon/schema/edn.clj
test/seon/schema/edn_test.clj -- seon.schema.edn-test`. The fast entry point
internally uses `bin/test --fast` for its snapshot; no cold gate was requested.

Hook lint reported existing shadowed-local warnings. Markdown lint reports
pre-existing stale dependency gitlink citations in the historical AGENTS audit;
those historical files are outside this assignment.
