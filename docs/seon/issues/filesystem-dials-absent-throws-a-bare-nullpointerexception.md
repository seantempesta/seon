---
type: issue
status: open
severity: friction
tags: [config, filesystem, error-model]
---

# An absent filesystem dial throws a bare NullPointerException

## Observed

Measured on `default`, 2026-09-16, jvm mode:

```clojure
(#'seon.fs.jvm/read-complete {:my.fs/path "/etc/hosts" :my.fs/encoding :utf-8}
                             {:seon.config/cluster "default"})
;; throws java.lang.NullPointerException
;;   "Cannot invoke \"java.lang.Number.doubleValue()\" because \"x\" is null"
```

A cluster branch with no config row declares no `:seon.config.fs/*` dials.
`seon.fs.jvm` reads them positionally and compares a nil, so the first thing
that happens is an unclassified NullPointerException with no `ex-data`.

## Why it matters

Every caller above it is then lying by omission. Through the effect boundary
the NPE becomes `:seon.effect/handler-failed`, whose entire evidence is
`{:seon.fn/sym "my.fs/read"}`; `seon.edit.jvm/filesystem-refusal` correctly
declines to classify it and rethrows. An agent — or a lane reading a gate
block — is told the handler failed and nothing about which dial the cluster
never declared. §2.4: an unavailable observation is the typed unknown, never
absence, success, or silence, and a refusal names the layer, the member and
the expected shape.

This cost the effect-facts lane one gate cycle: two `seon.edit-test` reds whose
real cause was a fixture that never seeded a config row read as a failure of
the refusal conversion under test.

## Done when

`seon.fs.jvm` refuses with a typed value naming the missing dial (and the
cluster whose facts lack it) before it reads any path, and a regression covers
one capability called against a branch with no config row.
