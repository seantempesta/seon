# Lane perf-renderer-map-sort — landing (2026-09-25)

`seon.render.value/value-node*` ordered a map's or set's whole membership with
`print/compare-values` before keeping `max-children`. It now selects the first
`max-children`+1 members in the same order through a bounded heap
(`ordered-head`, one pass, O(n log k)); ties keep iteration order, so the kept
members equal the stable full sort's prefix. HTML and values at or under the
bound still sort whole.

## Broken / not done

- Not installed in default: hook publication is paused; root adopts the commit.
- The issue's "well under 100 ms" for 1,000,000 entries is not met: 403 ms. The
  remaining cost is one armed `seon.print/compare-values` call per member
  (~0.35 us each through its contract wrapper, plus `(str (type v))` per call).
  Going lower needs a cheaper comparator in `seon.print` (not owned here).
- Out of scope, same file: `window` (`stable-entries`, value.clj:297) sorts the
  whole map/set by `pr-str` before paging; recorded in the issue.

## Evidence (default JVM `eval_clj`, scratch ns; after = the edited file loaded
into a throwaway namespace copy, its own fns unarmed, `compare-values` armed)

Default agent profile, max-children 32, integer maps, `value/prepare`:

| entries | before ms | before alloc | after ms | after alloc |
|---|---|---|---|---|
| 1,000 | 5.6 | 19 MB | 3.2 | 8.9 MB |
| 100,000 | 670 | 2.2 GB | 63 | 169 MB |
| 1,000,000 | 8,472 | 26.9 GB | 403 | 1.6 GB |

Before: `seon.print/compare-values` x20,192,660 = 7,263 ms across the batch.
Output text identical (old vs new `:seon.render.value/text`) for a 32-entry
mixed int/keyword map, a 62-entry int/keyword/string map, and the 1,000-entry
map. The 1M text names `:seon.print/omitted 999968`, bound-by max-children.
Heap selection equals `(take n (sort ...))` for n in 1, 2, 33, 100, 900 over a
map mixing ints, keywords, strings, collections, nil and 20 incomparable
`Object`s (ties), and for a 5,000-member set.

## Changes

- `src/seon/render/value.clj`: +29 / -5 (new contracted private `ordered-head`).
- `test/seon/render/value_test.clj`: +25, regression
  `a-map-beyond-the-child-bound-costs-the-bound-not-its-size`.
- clj-kondo: no new warnings (two shadowed-var warnings pre-exist at HEAD).

## Root runs

    bin/test-check default --test seon.render.value-test/a-map-beyond-the-child-bound-costs-the-bound-not-its-size
    bin/test-check default --ns seon.render.value-test
