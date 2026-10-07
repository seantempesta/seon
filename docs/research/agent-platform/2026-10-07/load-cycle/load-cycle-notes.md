# load-cycle: not reproduced

Task `seon.config/contract-check-require-enters-config-flow-load-cycle` (evidence:
`s1-lookups/review-s1-review-codex-notes.md` line 252): requiring `seon.contracts-compile-test` in an archive of
`87e6991a2` failed through `config.clj:595` (`defaults`) -> predicate `seon.flow/proc-launcher?` -> `seon.fault:182`
`No such var: config/effective`.

## Measured (fresh JVMs from `git archive`, reference-code symlinked, `git init` so the checker's git calls work)

| Probe | Result |
|---|---|
| HEAD archive, `clojure -A:test`, require `seon.contracts-compile-test` | loads |
| `87e6991a2` archive (the reported commit), same | loads |
| working tree (s5a-reply edits present), same | loads |
| HEAD archive, each of the 100 `src/seon/**` namespaces required alone, 6 in parallel | 100 of 100 load |
| `seon.config` alone: loaded libs afterwards | `seon.flow` and `seon.fault` absent |
| `seon.config/settings-projection` closure, HEAD and `87e6991a2` | 92 declarations, no `seon.flow/*` predicate |

## Mechanism that would produce the reported error

`config.clj` `defaults` runs at namespace load. `settings-projection` deliberately takes only the declarations
reachable from `:seon.config/effective` and `:seon.config/manifest`, so no predicate namespace loads. The reported
trace needs that closure to reach `seon.flow/proc-launcher?` (declared in `resources/seon/schemas/seon.flow.edn`):
`schema/resolve-predicate` then `requiring-resolve`s `seon.flow`, which requires `seon.fault`, which compiles
`config/effective` before `seon.config` defines it. Neither archive's closure reaches it, so the failing run had a
different schema input (an unclean resource set) or a different entry order that I could not recover.

## Not fixed

No cause found in the time box; no edit made. A guard would belong in `settings-projection` (refuse a closure
reaching a registered-predicate namespace, by name) once a failing input is captured. The error text and the exact
JVM command of the failing run are needed.
