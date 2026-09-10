---
type: issue
status: open
severity: friction
tags: [issue, operator, tooling, wave/publication-velocity]
---

# Incremental publication refuses a test row without usage

On 2026-09-10, default PID 23557 rejected the display lane's
`bin/seon init --dev default --changed src/seon/repl.clj src/seon/render/value.clj src/seon/render/web.clj`
after reporting `incremental scalar publication: 4 paths; reasons=()`.

The exact diagnostic was `Attribute :seon.test/usage expected :boolean, got
:seon.error/unknown.`, path `[0 :seon.test/usage]`, cause
`:malli.core/missing-key`. The rejecting shape was
`[:map {:seon.db/attributes true :seon.render/form my.turn/usage-form}
[:seon.test/sym :seon.test/sym] [:seon.test/usage [:= true]]]`.
The enclosing error was `:seon.cluster.source/incremental-source-refused`,
expected source commit `6aa31165-c246-57ca-9f24-20ca37b9653d`.

Complete publication is the existing operational fallback. The display lane
did not change publication, validation, or another lane's files, and did not
restart default. Its landing note records the fallback's outcome.

Verify the incremental test-row projection and matching of the usage-specific
shape on the real source fixture. A normal test wording change must publish
without inventing a usage declaration or weakening database admission.
Related, previously resolved function-row case:
[missing function provenance](archive/incremental-publication-refuses-missing-function-provenance.md).
