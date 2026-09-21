---
type: research
status: blocked
created: 2026-09-23
tags: [schema-shape, class/stored-derived, wave/publication-velocity]
---

# Authored schema shapes — protected reader boundary

Read end to end: the owning issue
`docs/seon/issues/the-index-cache-retains-4gb-of-expanded-schema-shape-forms.md`,
`src/seon/fn/schema_shape.clj`, and
`resources/seon/schemas/seon.schema.shape.edn`; also read AGENTS.md §2.2
and “SECONDS, NOT MINUTES” and the requested reader ranges.

## Required protected hunks

`src/seon/instrument.clj:714–718` computes an explanation's expected-shape
fingerprint from only `(:seon.schema.shape/form (normalized-form ...))`.
`src/seon/instrument.clj:726–730` repeats that operation for the complete
contract's expected-shape identity. Both discard everything except the
normalized form before calling the one-argument fingerprint function.

The current fingerprint implementation (`src/seon/fn/schema_shape.clj:144–150`)
hashes only its argument's canonical data string. With authored references
preserved, changing a referenced definition leaves that argument unchanged.
For example, authored `:example/value` remains `:example/value` whether its
registry definition is `:string` or `:int`. These diagnostic identities must
use the same dependency-aware fingerprint operation as stored shape rows.
The two protected call sites must therefore retain and supply the reference
identity inputs instead of selecting and hashing only the authored form.
No ambient registry or additional cache is an acceptable workaround.

The requested call-preparation reader is present at
`src/seon/call_preparation.clj:588–613`: its query selects only the form
string, then constructs a form-only row for `row-form`. It will need the
owned-path change to reconstruct structural children from the database.

## Verification boundary

Stopped at the assignment's explicit protected-hunk boundary. No production
files changed; neither protected file was edited, and `default` was not
operated. No scratch root, JVM, tests, or measurements were started. The
issue's 65 MB total and 1,395,278-character maximum are historical evidence,
not measurements from this assignment. Before/after scratch-root numbers,
size regressions, HEAD-load proof, and implementation commit remain owed.
This note is not a landed implementation or a performance claim.

RESET NEEDED when the new stored shape representation lands. No reset was
performed. Resume requires the two protected instrumentation hunks to be
released or changed by their owning lane in coordination with this slice.
