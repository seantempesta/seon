---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, reset, schema, query]
---

# Historical function values do not identify an error entity

Fast 25's canonical error regression returned four rows for one error:
the signature and three occurrence entities. G3 consolidates historical
function attribution on `:seon.instrument/fn`; occurrences already retain
that evidence. Selecting a row by this attribute alone therefore does not
identify an error signature.

Both steward fault queries now require `:seon.error/signature` before joining
the observed function symbol to the current function's namespace. The canonical
`seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`
regression checks one signature, all six observed occurrences, and the steward's
rendered history. Fast 26 passes this functional regression; its sole failure is the separate
query-cost ratio. No cold proof is claimed. The implementation is isolated on
`reset-batch` and is not yet integrated into `steward-platform`.

No kind discriminator or duplicate function attribute is introduced. The same
principle keeps historical effect handler observations out of live declaration
deletion obligations: the declaration must also carry its function identity.
