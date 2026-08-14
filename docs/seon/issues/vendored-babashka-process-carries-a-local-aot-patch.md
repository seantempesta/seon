---
type: issue
status: open
severity: cleanup
tags: [issue, runtime, dependency]
---

# Vendored babashka process carries a local AOT patch

## Problem

`reference-code/babashka-process` points at upstream
(`https://github.com/babashka/process`, submodule `ignore = dirty`) and
now carries a local uncommitted patch: the load-time
`(when (contains? (loaded-libs) 'clojure.pprint) (require 'babashka.process.pprint))`
convenience is guarded with `(not *compile-files*)`, because during
dev-cache AOT compilation it produced a deterministic cyclic load
(`babashka.process` → `babashka.process.pprint` → `babashka.process`)
the moment any earlier compiled namespace had loaded `clojure.pprint`
(first triggered 2026-08-14 by the grown dependency closure). A dirty
submodule survives locally but a fresh clone loses the patch and the
cache build breaks again.

## Owner

The vendoring pattern: fork to the owner's fork (the http-kit
precedent — a `seantempesta/` fork with the patch committed, deps.edn
noting "retire when upstream fixes") or upstream the guard. Until then
this note is the patch's durable record; the exact hunk is in the
session history at the 2026-08-14 dev-cache repair.

## Acceptance

The patch lives in a committed fork (or upstream release) referenced by
`.gitmodules`/deps.edn; a fresh clone builds the dev cache without
manual repair.
