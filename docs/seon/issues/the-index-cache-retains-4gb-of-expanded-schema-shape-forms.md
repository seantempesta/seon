---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [memory, datahike, schema-shape, publication, class/stored-derived, wave/publication-velocity]
---

# The index cache retains 4 GB of expanded schema shape forms

## Sighting (2026-09-23, `default` pid 68550 at `03bbf7eb0`)

`default` sat at 15.3 GB resident with 4.5 GB live after a forced GC
(`MaxRAMPercentage=12.5` of 128 GB). A live heap dump (2.5 s, 5.3 GB)
analyzed by Eclipse Memory Analyzer's leak-suspects report gives one
dominator:

```
datahike.connector.Connection            4,019,255,536 bytes (84.16 %)
 └ datahike.db.DB :eavt
   └ datahike.index.persistent_set.CachedStorage
     └ clojure.core.cache.LRUCache          4,019,247,888
        byte[]   852,296 objects   3,813,551,616   (== 852,296 java.lang.String)
        Datom  2,501,819 objects     100,072,760
```

The database itself is small: 522,400 datoms, current == history, and
every string value in it totals 81 MB. Of that, `:seon.schema.shape/form`
is 65 MB across 4,346 rows (average 15 KB; the largest three are
1,395,278, 969,686 and 679,992 characters); 1,201 string datoms exceed
4 KB. The cache holds ~47 copies' worth of that content.

## The two seams

1. **Datahike bounds its node cache by COUNT, not bytes.**
   `reference-code/datahike/src/datahike/index/persistent_set.cljc:463`
   builds `(cache/lru-cache-factory {} :threshold (:store-cache-size config))`
   with the default `*default-store-cache-size* 1000`
   (`reference-code/datahike/src/datahike/config.cljc:24`). Each cached
   node deserializes its own copies of its datoms' values, so with
   megabyte string values a 1,000-node cache is multi-gigabyte, and every
   historical node version of a rewritten leaf is a fresh copy until it
   ages out. A tuned constant is not the fix (AGENTS.md §"Prefer
   dissolution"); the values are.
2. **We store the fully expanded schema form on every shape row, and
   again on every child row.** `src/seon/fn/schema_shape.clj:76-104`
   (`expand-schema-form`) inlines every registry reference recursively;
   `normalized-form` (`:119`) expands the authored form; `encode-form`
   (`:274`) writes `(pr-str form)` of the WHOLE expanded subtree onto the
   row while ALSO writing the subtree as content-addressed child/entry rows
   (`:seon.schema.shape/entries`, `/children`). The form string is a
   mirror of the child rows, the expansion is a mirror of the registry's
   own schema facts, and the size is O(depth × subtree) per root. This is
   the stored-derived class (§2.2 "derive or die", "no double caching").
   It is also a share of every complete publication's transaction volume
   and of `seon.instrument`'s per-wrapper reads
   (`src/seon/instrument.clj:715`, `:727`) and call preparation's
   (`src/seon/call_preparation.clj:597`).

## Fix shape

A shape row stores the AUTHORED form with references left as the
qualified keywords the source wrote (registry entries are themselves
`:seon.schema` facts, so expansion is a query), and no `pr-str` of a
subtree that its child rows already carry. The fingerprint hashes the
authored form plus the fingerprints of the referenced shapes, so identity
still changes when a referenced schema changes. Readers that need the
expansion (`instrument.clj:715`, `call_preparation.clj:597`) derive it
from the rows through the compiled registry, once per database value.
Expected: shape strings from 65 MB to well under 1 MB, the cache to tens
of MB, and a smaller publication transaction. A regression asserts the
largest `:seon.schema.shape/form` in the canonical fixture is under a
few KB and that no row's form contains an inlined `:map` that a sibling
entry row already stores. Reset required (stored shape rows change).

Landing: after slice 4, by the lane owning `src/seon/fn/`.
