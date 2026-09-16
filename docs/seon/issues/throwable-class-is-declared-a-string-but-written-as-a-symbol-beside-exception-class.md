---
type: issue
status: open
severity: cleanup
tags: [seon.error, schema, symbols]
opened: 2026-09-17
---

# `:seon.error/throwable-class` is declared a string but written as a symbol, beside `:seon.error/exception-class`

## Problem

`resources/seon/schemas/seon.error.edn` declares `:seon.error/throwable-class`
as `:string`, while `src/seon/error.clj:285` writes `(symbol class-name)`; and
`:seon.error/exception-class` is a second attribute for the same fact
(`docs/prds/steward-platform/research/symbols-everywhere-inventory-2026-09-17.md`
§1, item 7). Two attributes for one fact is the §2.5 second-mechanism
defect; the declared type disagreeing with the writer is a contract lie that
admission does not catch because the value validates as a symbol where a
string was promised only after read.

## Wanted

One attribute for the throwable's class, typed `:symbol`, written by one
seam; the other deleted with its readers moved — in the symbols-everywhere
slice.
