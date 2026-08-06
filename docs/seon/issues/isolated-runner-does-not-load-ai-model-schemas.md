---
type: issue
status: open
severity: blocker
tags: [issue, config, schema, test]
---

# Isolated test startup does not load the database model schemas

## Problem

An isolated test runner can refuse the shipped default configuration before
loading the selected tests. Observed on 2026-08-06 with:

```text
bin/test seon.dev.markdown-test
Configuration refused: unknown-initialization-attribute.
{:seon.config/key :seon.ai.model/provider-id}
```

The same checkout's focused `seon.fn-test` gate succeeds, so test selection
currently changes whether startup has the complete schema population.

## Evidence

A fresh load-only JVM reproduced the missing declaration directly:

```clojure
(require 'seon.config 'seon.schema)
(contains? (seon.schema/registered-schemas)
           :seon.ai.model/provider-id)
;; => false

(count (seon.schema/registered-schemas))
;; => 16

(seon.config/defaults)
;; throws :seon.config/unknown-initialization-attribute
```

The shipped value is present in `config/default.edn`, and its declaration is
present in `resources/seon/schemas/seon.ai.model.edn`. Attribution is not yet
proven; this note records the load boundary without assigning a cause.

## Acceptance

- A fresh JVM can call `seon.config/defaults` without first requiring a
  selection-dependent application namespace.
- `bin/test seon.dev.markdown-test` reaches and passes its tests.
- Schema loading remains one declared mechanism rather than a test-runner
  allowlist.
