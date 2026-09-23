---
type: issue
status: closed
closed: 2026-09-23 (2ff62cc0c: submodule points at seantempesta/process seon-aot-guard e83ec5c; retire when upstream fixes)
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

## 2026-09-23: committed inputs cannot build the class cache

Every committed-inputs program (the nuke's and `bin/seon start --head`'s archive
under `<root>/data/source/<sha>`) places `reference-code/babashka-process` as an
archive of its pin, because the checkout carries this uncommitted patch. So
`clojure -T:dev-cache ensure-cache` run in such an archive refuses after 57.4 s:
`Cyclic load dependency: [ /babashka/process/pprint ]->/babashka/process->[ /babashka/process/pprint ]`
at `babashka/process.cljc:717` (lane move-to-head,
`tmp/mth-evidence/fill-cache.log`). Every JVM launched from committed inputs
therefore misses the dependency class cache and compiles its dependencies from
source (33–52 s before boot entry, measured under load 13–17).
