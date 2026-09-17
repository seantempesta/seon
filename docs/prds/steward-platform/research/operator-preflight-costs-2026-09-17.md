---
type: research
status: active
created: 2026-09-18
tags: [research, operator, clj-kondo, dev-cache, bounded-execution]
---

# Operator preflight costs, measured 2026-09-17

Every development adoption refused from commit `8e952be28` until this
measurement: one bare `5000` constant bounded the changed-source lint AND a
clj-kondo dependency-analysis population run inside it. The incident is
`data/operator/operations/init-preflight-83199.log` — `init phase=preflight
elapsed-ms=5319`, the deadline landing on the `bb … ensure-dependency-cache!`
subprocess with `:seon.operator.subprocess/deadline-ms 4933`.

All numbers below were taken on this checkout (M5 MacBook Pro, dirty tree of
11 files), warm unless stated.

## The lint the bound is actually for

| Work | Measured |
|---|---|
| `clj-kondo` with the hook's two configs, 11 changed files | 870 / 1,171 / 901 ms (3 runs) |
| the same, one file (`src/seon/render/ns.clj`) | 76-83 ms |
| the same, one file against an EMPTY cache directory | 199 ms |
| the two `git` enumerations (`diff --name-only`, `ls-files --others`) | 14 ms |

`source-preflight-bound-ms` is now a declared `def` with this basis at
`script/seon/fresh_operator.clj:244`, sized at 20,000 ms for the lint and the
enumerations alone.

## The population that was inside the bound

| Work | Before | After |
|---|---|---|
| `ensure-dependency-cache!`, cache CURRENT (whole `bb` process) | 440 ms | 90 ms |
| the same, called in the operator's own process | not possible (a `bb` subprocess per edit) | 44-68 ms |
| `ensure-dependency-cache!`, cache STALE | 10,770 ms | 11,440 ms |

Breakdown of the 440 ms current-path check before the change:

| Step | Measured |
|---|---|
| `clojure -Spath` | 28 ms |
| `input-digest` (hash every file on the resolved classpath) | 259 ms |
| `cache-contents` (SHA-256 of 3,486 files / 19 MB) | 241 ms |
| `git ls-files --stage -- reference-code` (the new key's only subprocess) | 14 ms |
| directory listing of the same 3,486 cache files by size | 43 ms |

## What changed

1. `script/seon/dev/dependency_digest.clj` owns the dependency-set identity
   both development caches key on. `dependency-set-digest` is `deps.edn` plus
   the `reference-code` submodule pins; `configuration-digest` adds this
   runtime's identity and is what `dev_cache.clj` keeps using, byte-identical
   to its previous inline value (verified: `259402bb512b7d51…` before and
   after). The split is load-bearing: the clj-kondo analysis is written by a
   native binary and read from babashka, a JVM test and the gate, and
   `java.vm.name` is `"Substrate VM"` under babashka against `"OpenJDK 64-Bit
   Server VM"` under the JDK — keying it on the runtime repopulates it for
   every process that reads it.
2. `seon.dev.clj-kondo/ensure-dependency-cache!` keys on
   `dependency-set-digest` and resolves no classpath while the key holds. Its
   recorded presence check is a directory listing by size, not a content
   digest of 19 MB.
3. The operator calls it IN ITS OWN PROCESS
   (`script/seon/fresh_operator.clj:388`) instead of spawning `bb`, outside
   the preflight bound, and subtracts its elapsed time from the preflight's.
4. `seon.dev.clj-kondo/population-bound-ms`
   (`script/seon/dev/clj_kondo.clj:14`) declares the population's own bound
   with the 10,770 ms basis.

## A second refusal on the same path

The preflight refused on clj-kondo's exit code, not on its findings:
clj-kondo answers `2` when it found WARNINGS, so any warned file produced
`Source preflight refused: ` — a refusal naming nothing. Observed live in
`data/operator/operations/init-preflight-96016.log`;
`clj-kondo … --lint src/seon/id.clj` exits 2 with 4 warnings and 0 errors on
this checkout. The refusal now reads the report's error findings, and any exit
outside `#{0 2}` is named as clj-kondo failing to lint at all.

## Live verification

`bin/seon init --dev default --changed src/seon/render/ns.clj
src/seon/render/walk.clj`, 2026-09-17 23:20:

```
● changed-source boot lint checked: 19 files in 1593 ms; dependency cache warmed in 9227 ms
● init phase=preflight elapsed-ms=10821
● changed-source boot lint checked: 0 files in 66 ms; dependency cache unchecked in 0 ms
● init phase=preflight elapsed-ms=67
✗ init phase=init failed; The JVM loaded a different dependency cache
```

The preflight passes; adoption then refuses at `phase=init` for an unrelated,
pre-existing reason recorded in
[the default JVM's dependency-class mismatch](../../../seon/issues/the-default-jvm-loaded-dependency-classes-the-current-cache-no-longer-selects.md).
`http://127.0.0.1:7994/ns/seon.id` therefore still measures
`3.865300 27533857` — 27.5 MB, unchanged, because the namespace-page fix is
not adopted.
