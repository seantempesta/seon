# PB-03a: what a reload costs the shared base (lane impl-pb03a, 2026-10-08)

Probes: `pb-03a-probe.clj` (p1 four parts, p2 profile inside, p3 reload and re-copy). Throwaway root started with
`bin/seon --root tmp/pb03a/root start default` at HEAD `e7f120672`, driven through `seon.operator/prepl-value!`,
stopped with `down` afterwards. Default's JVM was never touched. MB is thread-allocated, not retained.

## Rebuild today (`derive-base-ctx`, 5,674 functions; warm, runs 2 and 3; run 1 adds 74 ms / 173 MB of class loading)
| Part | ms | MB allocated |
|---|---|---|
| `load-core-namespaces!` | 1 | 0 |
| `schema/projection-from-database` | 0 | 0 (cached) |
| `build-base-ctx` | 2-3 | 2 |
| `acquire-program!` | 134-139 | 244 |
| whole `derive-base-ctx` | 120-153 | 246-248 |

Retained weight of the cached base: 11.6 MB (`seon.cache` kind row). Inside `acquire-program!` (p2, 115 ms): six
whole-program `db/q` take 38 ms, one `pull-many` over every namespace row 47 ms, `function-digests` 4 ms; the 4,064
`copy-host-var` and 4,022 `mark-installed!` calls take 1-2 ms each in total. On an adoption, with no overrides, the
rebuild's time and allocation are whole-program Datalog; binding the Vars is nearly free.

## What a reload does (p3: `load-file` of the changed source, the same compile `require :reload` does)
| Namespace | rows | reload ms | reload MB | re-copy its Vars on a fork ms |
|---|---|---|---|---|
| `my.task` | 10 | 30 | 7 | 0 |
| `seon.id` | 13 | 354 | 20 | 0 |
| `seon.task` | 52 | 406 | 30 | 3 |
| `seon.fn` | 160 | 1,613 | 175 | 1 |

`sci/fork` of the base: under 1 ms. The reload itself is the dominant cost of an adoption, and is paid with or
without the base.

## Facts for part b
- **The base goes stale on reload.** `copy-host-var` copies the Var's root value (`sci/copy-var*`, `eval.clj:236`), so
  after a reload the base still holds the old function objects: `base-stale-after-reload` is true for all four
  namespaces. A reload therefore requires either a new base or re-copying the reloaded namespaces' Vars.
- **Advancing touches** only: the reloaded namespaces' Vars (`install-first-party-namespaces!`, `eval.clj:1740`
  shape: `sci/add-namespace!` of re-copied interns, plus `kernel/mark-installed!`), the program snapshot's
  installed-function entries for functions added or removed in them, and `::acquisition`/`::program-namespaces`
  (generation and namespace state). Cost measured: 0-3 ms, ~0 MB, versus 120-150 ms / 246 MB.
- **A reloaded namespace can add or remove interns**; take the set from the JVM namespace intersected with the new
  loaded value's rows, as `install-first-party-namespaces!` does, not from the previous base.
- **Misses are per adoption, not per pass.** The base key is `[store identity, loaded commit]`, which changes only
  when the loaded program moves (PB-01: 0 misses across passes). Each adoption, and the first pass after boot, is one
  miss. The repository took 344 commits in the last day, but root batches restarts, so adoptions per hour are far
  fewer; the throwaway root cannot measure adoption frequency and this note does not claim a rate.
- **Finding, out of scope.** In a JVM started with program classes (`:seon.operator/program-classes? true`), the
  class files are dated 2100 (`target/dev-program-classes/*/seon/task__init.class`), so `(require ns :reload)` loads
  the class and replaces nothing: it took 0.3-1 ms and `root-replaced?` was false. Part b's proof (and any adoption
  test) must reload from source, or it will measure nothing. Whether live development adoption (`cluster.clj:1924`)
  recompiles in such a JVM is not established by this probe; part b must check it first.

## Recommendation for part b
Advance in place is worth it: the rebuild is 120-150 ms and 246 MB once per adoption, advance is about 3 ms, against
a reload that costs 30-1,600 ms regardless. The saving is modest in time but removes a 246 MB allocation spike and
keeps one base object. Do it as the card says (`advance-base`: `sci/fork` previous, re-copy the reloaded namespaces'
Vars, re-run `mark-installed!`, refresh `::program-namespaces`; evict the previous key), with these conditions:
1. Fall back to `derive-base-ctx` when the previous base is absent, when the reloaded set is empty or unknown, or when
   the loaded value changed rows outside the reloaded namespaces (an entity-only adoption), because only reloaded
   Vars are re-copied.
2. The proof must reload from source (see the finding above) and compare the advanced base's Var roots with
   `derive-base-ctx` of the same loaded value, namespace by namespace: that equality is the test of the second path.
3. Do not start part b before the program-classes question is answered: if adoption does not recompile in such a
   JVM, the stale-base problem only appears when it does.
