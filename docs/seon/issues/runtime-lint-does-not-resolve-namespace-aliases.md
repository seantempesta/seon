---
type: issue
status: open
severity: friction
tags: [issue, tooling, sci]
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

This issue remains evidence from source review until a focused REPL example
falsifies or confirms it; it did not block the edit-hook reliability change.

## Owner

`seon.fn.analyzer/analyze-forms`, using the namespace row's indexed aliases as
the one resolution authority.

## Acceptance

- A focused runtime-analysis example requiring `[seon.foo :as foo]` and
  calling `foo/bar` receives the canonical `seon.foo/bar` stub.
- The same example produces no unresolved namespace or var finding.
- Direct canonical calls and genuinely unresolved aliases retain their current
  behavior.
