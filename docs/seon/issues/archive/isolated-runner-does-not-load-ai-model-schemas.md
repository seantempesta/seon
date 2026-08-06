---
type: issue
status: resolved
severity: blocker
tags: [issue, config, schema, test]
---

# Isolated test startup does not load the database model schemas

## Problem

An isolated test runner refused the shipped default configuration before
loading the selected tests:

```text
Configuration refused: unknown-initialization-attribute.
{:seon.config/key :seon.ai.model/provider-id}
```

A fresh JVM requiring only `seon.config` and `seon.schema` registered 16
bootstrap schemas and omitted `:seon.ai.model/provider-id`.

## Root cause

This was a separate load-order defect, not the maintenance population defect.
The maintenance failure began with a complete packaged registry but omitted
attributes from its computed Datahike projection. The isolated runner never
loaded that packaged registry: `seon.config` consumed schema-backed defaults
without requiring and invoking the one `seon.schema.edn` population owner.

Commit `00bf9feea` makes `seon.config` load the packaged EDN population before
compiling defaults. It adds no runner-specific namespace list.

## Proof

The class regression starts a genuinely fresh `clojure -M:dev` subprocess,
requires only `seon.config` and `seon.schema`, verifies
`:seon.ai.model/provider-id` is registered, and calls
`seon.config/defaults`. It passed in the focused 41-test, 229-assertion gate.

`bin/test seon.dev.markdown-test` now loads and passes 26 tests and 350
assertions with zero failures or errors.

## Acceptance

- A fresh JVM can call `seon.config/defaults` without selection-dependent
  namespace loading.
- The isolated Markdown test runner loads and passes.
- Packaged schema loading remains one declared mechanism with no runner
  allowlist.
