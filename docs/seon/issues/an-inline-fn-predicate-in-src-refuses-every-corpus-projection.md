---
type: issue
status: open
severity: blocker
tags: [issue, schema, test]
---

# An inline `[:fn]` predicate under `src/` refuses every corpus projection

## Problem

`seon.test.selection/basis-file` declares

```clojure
{:malli/schema [:=> [:cat [:string {:min 1}]]
                [:fn {:error/message "a file"} (partial instance? File)]]}
```

(`src/seon/test/selection.clj:181-183`). The predicate is not a registered
core predicate, so `seon.schema/bind-predicates` refuses the whole population:

```text
Predicate (clojure.core/partial clojure.core/instance? File) has no admitted
callable in the corpus projection.
```

Because the file lives under `src/`, that refusal reaches EVERY caller that
builds a corpus projection — which is every test that opens a cluster ctx.

## Evidence

Found 2026-08-07 22:2x by the render-proc stop-completion lane. The file
appeared at 22:22:17; `bin/test seon.render.web-test` was 38 tests / 1 failure
/ **0 errors** at 22:17 and became **all-red with this one exception** at 22:26,
with no intervening change to the namespace or its owners.
`bin/test seon.render-coverage-test`: 3 tests, 3 errors, all this exception.

The refusal itself is correct and loud — the gate is doing its job. The defect
is the declaration.

Second independent observation (tool-exercise lane, 2026-08-07 22:26–22:52):
the blast radius is wider than tests — it refuses **every publication**, so
`bin/seon init` cannot complete in ANY operator root while the declaration is
present:

```text
$ bin/seon --root tmp/tool-exercise-operator init
✗ Predicate (clojure.core/partial clojure.core/instance? File) has no admitted
  callable in the corpus projection.
{:seon.schema/error :seon.schema/unresolved-predicate, ...}
```

Consequence: a fresh isolated root cannot be booted at all (`bin/seon start`
refuses with `No 'current-src' branch is published; run 'bin/seon init'
first`), which blocked this lane's cluster for ~30 minutes. Reproduced at
22:26, 22:41, and 22:52.

## Owner

`src/seon/test/selection.clj` (the changed-test selector lane) —
register the predicate with `seon.schema/register-core-predicate!`, or declare
the return with an already-registered file shape.

## Acceptance

`bin/test seon.render-coverage-test` builds its corpus projection without this
exception.
