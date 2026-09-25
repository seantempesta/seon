---
type: report
status: measured
created: 2026-09-25
tags: [agent-platform, db, effect, render]
---

# Lane delete-closure-walks-opus — landing (2026-09-25)

One owner now answers "forward reach of a root over the call graph": `seon.db/call-reach`
walks any callee function (a map, stored rows or a proposed overlay) from a root, and
`seon.db/stored-call-reach` supplies `database`'s stored `:seon.fn/calls` through two Datahike
index lookups per reached symbol. Three of the four copies now call it and their own walks are
deleted.

## Broken or open first

1. **`builder-digest` is about 0.5 ms slower (+25%, about 1.95 → 2.5 ms median of 31, 244 reached
   symbols).** The walk now returns symbols, so the digest pass repeats one AVET lookup per
   symbol. The absolute cost stays under 3 ms. Only uncommitted values pay it, because
   `loaded-builder` memoizes committed values by commit. I measured one alternative, a raw
   `d/pull-many` pass, and it was slower (3.4 ms). **Root rules** whether 0.5 ms passes the
   20% rule.
2. **`fn/reaches?` has not been converted (fn.clj is held by another lane).** It is the fourth
   copy. It walks an authored overlay, not stored rows, so the conversion is one line:
   `(contains? (db/call-reach (comp :seon.fn/calls rows-by-symbol) root) target)`.
   It replaces `reaches?`, `src/seon/fn.clj:2335-2350` (16 lines), and deletes the function.
3. **A fifth copy exists: `src/seon/test/runner.clj:839-849`** (another lane holds it, and the
   file has uncommitted edits). It walks calls, references and the test subject over in-memory
   rows. The conversion is `(db/call-reach #(let [row (get rows (get symbols %))] (concat (:seon.fn/calls row) (:seon.fn/references row) (some-> (:seon.test/subject row) vector))) test-symbol)`.
   Root should give it to the runner owner.
4. **Behaviour changes that the equivalence checks do not cover:**
   - `render-program-evidence` used to return nil when `pull-many` refused. It now throws through
     the owner's contract, so its output schema is `:map`.
   - `capabilities` no longer records per-depth `pull-many` read evidence. It records only the
     capability-owner query.
   - `stored-call-reach` records no read evidence, which matches the old `builder-digest`.
   - None of the three callers reads that evidence for its value.
5. **Added lines exceed the stop at about 40:** 47 source lines added against 45 deleted. The owner
   is 24 of those, mostly the docstrings that cite Datahike.

## Why no library walk

- **Pull recursion** needs refs. Datahike treats each followed value as an entity id
  (`reference-code/datahike/src/datahike/pull_api.cljc:340-344` → `recurse-attr` at :275-289,
  rev 4157916b). `:seon.fn/calls` holds symbols (`:seon.program/edge-symbol`), so recursion
  does not apply.
- **The recursive rule** derives the whole relation first, which took 3.3 s
  (lane-fix-cost-capabilities-2026-09-25.md).
- **`tree-seq`** has no visited set, so it never ends on cycles.
- **What remains** is eight lines over `datahike.api/datoms`, AVET then EAVT.

## Equivalence evidence (JVM, scratch ns `delete-closure-walks-scratch`, default db)

Method: the committed defns were read from the files and evaluated in the scratch namespace.
`db/stored-call-reach` was substituted with the scratch copy. No Var in default was redefined.
Every result below was compared with the loaded old Var on the same db value.

| root | reached | stored-call-reach ms | render old/new equal (nil + synthetic snapshot) | capabilities equal |
|---|---|---|---|---|
| `clojure.core/inc` (leaf, no row) | 1 | 0.03 | true / true | true (#{}) |
| `seon.turn/turn` (deep) | 2276 | 11–37 (load noise; old render walk 23–42) | true / true | true (#{}) |
| `seon.schema/load-projection` | 244 | 0.8–2.1 | true / true | true |
| `seon.schema/schema-accepts-schema?` (self-call cycle) | 19 | 0.05–0.14 | true / true | true |
| `no.such/fn` (absent) | 1 | 0.005–0.015 | true / true | true (#{}) |
| `my.fs/read` (capability owner) | 712 | 2.8–9 | true / true | true (#{my.fs/read}) |

- `builder-digest`: new equals old on the default db, with a median of 2.5 ms new against
  1.95 ms old.
- `capabilities` on `seon.turn/turn`: median 10.6 ms new against 29.5 ms old. On `my.fs/read`:
  2.9 ms new against 7.3 ms old.
- Load noise: root's test runs were active in the same JVM during these measurements.
- `call-reach` on overlay maps: `'{a/x [a/y] a/y [a/x a/z]}` from `a/x` → `#{a/x a/y a/z}`,
  and `{}` from `a/absent` → `#{a/absent}`. These are now the regression
  `seon.db-test/call-reach-includes-the-root-ends-cycles-and-treats-absent-symbols-as-leaves`.

## Commits and lines

| commit | path | +/− |
|---|---|---|
| 01f5d0020 | src/seon/db.clj (owner + builder-digest), test/seon/db_test.clj | +33/−11, +4 |
| dea61e080 | src/seon/effect.clj `capabilities` | +8/−17 |
| d3c0d630e | src/seon/render.clj `render-program-evidence` | +6/−17 |

- Deleted walks: `builder-digest` loop (11 lines), the `capabilities` pull-many loop (17 lines)
  and the `render-program-evidence` pull-many loop (17 lines).
- Net source: +2. Net with the test: +6. After the `reaches?` and runner conversions, about −25.

## Root runs (serially, quiet window, after adopting the three commits)

```
bin/test-check default --test seon.db-test/call-reach-includes-the-root-ends-cycles-and-treats-absent-symbols-as-leaves
bin/test-check default --test seon.db-test/a-changed-projection-builder-misses-the-projection-cache
bin/test-check default --test seon.db-test/a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program
bin/test-check default --test seon.effect-test/capability-reachability-follows-the-roots-reach
bin/test-check default --ns seon.test.accretion-test
bin/test-check default --ns seon.render.retained-test
bin/test-check default --ns seon.render.call-test
```

Probe after adoption:
`(let [db (seon.db/db (seon.cluster.boot/connection "default"))] (time (count (seon.db/stored-call-reach db 'seon.turn/turn))))`.
Expect 2276 reached in about 11–30 ms.

## Verification limits

- The lane ran no tests, per the load rule.
- clj-kondo found 0 new errors on the touched files. The one db.clj error (`parser.type/->Variable`)
  is an older stale dependency-cache entry.
- On real data, no function reaches a capability other than itself. Transitive capability reach
  is therefore covered only by the constructed-world regression in effect_test.
- Publication is paused, so nothing was loaded into default.

RESET NEEDED: no.
