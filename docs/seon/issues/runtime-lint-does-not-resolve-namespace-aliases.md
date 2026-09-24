---
type: issue
status: resolved
severity: friction
tags: [issue, sci, wave/future-runtime-lint]
---

# Resolve namespace aliases before selecting runtime lint stubs

## Problem

Runtime reply analysis selects database function stubs by the namespace
qualifiers found in candidate source. A call through an alias such as
`foo/bar` contributes `foo`, while indexed function identities carry the
canonical namespace such as `seon.foo`. The filter can therefore omit the
available stub and report a false unresolved namespace or var.

## Evidence

Gemini's 2026-07-30 batched review identified the mismatch in
`seon.fn.analyzer/analyze-forms`: `referenced-program-namespaces` derives raw
qualifiers from source, then compares them with canonical namespaces extracted
from `:seon.fn/sym`. The namespace row already carries the alias table, but
that table is not applied at this selection boundary.

The REPL example below falsifies the described failure on the current owner.

## Owner

`seon.fn/runtime-analysis-batch` (the deleted `seon.fn.analyzer/analyze-forms`
moved there; `seon.fn/analyze-forms` calls it). Stubs are no longer selected
by source qualifiers: the prelude holds only the calling namespace's own
functions, kondo resolves aliases through the synthesized `ns` form built from
the namespace row, and `resolvable-runtime-function-rows` checks the canonical
symbols against the database.

## Verification (2026-09-24, lane fix-fn-correctness)

Default JVM (pid 46768, started 16:02:59Z), JVM mode,
`(#'seon.fn/runtime-analysis-batch db [{:namespace-name 'seon.fn :form-source
"(defn probe-x [] (analyzer/program-prelude []) (analyzer/no-such-fn-xyz 1) (nosuch/thing))"}])`:

- `analyzer/program-prelude` is a usage `:to seon.fn.analyzer` with alias
  `analyzer`, and the function rows carry the canonical
  `seon.fn.analyzer/program-prelude`.
- Findings: `[]`: no false unresolved namespace or var.
- The unresolved `analyzer/no-such-fn-xyz` and `nosuch/thing` contribute no
  row and no finding (unchanged behaviour: stdin analysis runs without the
  dependency cache).

Residue, not this issue's failure: `analyzer/referenced-program-namespaces`
still returns raw qualifiers, so the synthesized form carries a bogus
`(:require [analyzer] ...)`. It produced no finding in the probe.

## Acceptance

- A focused runtime-analysis example requiring `[seon.foo :as foo]` and
  calling `foo/bar` receives the canonical `seon.foo/bar` stub.
- The same example produces no unresolved namespace or var finding.
- Direct canonical calls and genuinely unresolved aliases retain their current
  behavior.
