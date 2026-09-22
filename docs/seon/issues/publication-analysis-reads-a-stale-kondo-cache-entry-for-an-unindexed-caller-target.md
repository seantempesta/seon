---
type: issue
status: closed
severity: blocking
created: 2026-09-22
tags: [issue, publication, analysis, tooling]
---

# Publication analysis reads a stale clj-kondo cache entry for an unindexed callee

Seen 2026-09-22 20:42Z by lane three-way-comparison. The scratch-root boot
`bin/seon --root tmp/three-way-boot-1790109730 reset --force` exited 1 after
40.92 s wall with:

```
Static program analysis found blocking errors.
/Users/sean/src/seon/test/seon/dev/hook_test.clj:92:21 :invalid-arity
seon.operator/prepl-value! is called with 5 args but expects 2, 3 or 4
```

`script/seon/operator.clj:118` has declared a 5-arity `prepl-value!` since
`324d41507` (2026-09-22 14:03 -0600). The shared cache entry
`.clj-kondo/.cache/v1/clj/seon.operator.transit.json` was last written at
09:44, before that commit. Publication analyzes `test/` but not `script/`,
so clj-kondo resolved the callee from that stale entry.
`discard-obsolete-cache-entries!` (`src/seon/fn/analyzer.clj:224`) drops an
entry only when its source path moved or no longer exists. It never checks
whether the source bytes changed. `script` is a declared checkout root
(`deps.edn:142`, test alias `:extra-paths`), so the entry counts as
checkout-owned and nothing refreshes it.

Running `clj-kondo --lint script/seon/operator.clj` rewrote the entry (4,816
bytes, 14:43). The next boot got past analysis, and then failed on a separate
working-tree schema refusal.

Wanted: an analysis that trusts a cache entry for checkout-owned source
refuses or re-analyzes when that source's current bytes differ from the bytes
the entry was built from. A caller's arity is then never checked against an
older definition. Add one regression: change a `script/` callee's arity and
publish only its `test/` caller.

## Closed 2026-09-23 by ea9a4e3e9

`seon.fn.analyzer/analyze` now checks only the cache entries of namespaces the analysis used (the ones clj-kondo loads, `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:127-144`). An entry is stale if its source changed after it was written (mtime), is gone, is stdin, moved to another file, or is a foreign in-checkout copy. Stale entries are rebuilt from the current bytes and the analysis runs once more; if entries are still stale after that, it refuses and names them. Counts are returned as `::cache {::examined ::stale ::rebuilt}`. The regression (`test/seon/cluster/publication_inputs_test.clj`) fails on HEAD's analyzer (4 failures) and passes with the fix. Limit: mtime, not content. Changed bytes with an older mtime are not detected; content keys need a clj-kondo fork change (its cache is keyed by namespace name).
