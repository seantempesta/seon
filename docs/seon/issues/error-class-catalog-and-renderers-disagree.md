---
type: issue
status: open
severity: friction
tags: [issue, error, schema, render, testing]
---

# Reconcile the error-class catalog with declared schemas and renderers

## Problem

The error-class census and the installed declarations disagree in two ways:
one class exists only in the installed set, and the transaction-refusal class
uses specialist renderers where the census requires the shared error faces.

## Evidence

The bare 2026-08-05 gate failed
`seon.error-class-schema-test/catalog-class-schemas-are-complete-and-declared`
at `test/seon/error_class_schema_test.clj:115,122,124`:

- actual declarations additionally contain
  `:seon.config/missing-effective-error`;
- `:seon.db/transaction-refused-error` declares
  `seon.db/render-rejection-ai`, not expected `seon.error/render-ai`;
- the same class declares `seon.db/render-rejection-html`, not expected
  `seon.error/render-html`.

The focused run at pre-rename commit `401fd300e` printed the same enormous
unordered set diff and the same two renderer mismatches. The failure is
pre-existing; the multi-kilobyte set rendering is also poor diagnostic output.

## Owner

The declared error-class registry and the one query-derived census in
`test/seon/error_class_schema_test.clj`.

## Acceptance

The expected and installed class identities agree by query, and every class's
declared producer follows one explicit rule that accommodates intentional
specialists. A failure reports sorted missing/extra identities and renderer
differences, not two complete unordered catalogs.
