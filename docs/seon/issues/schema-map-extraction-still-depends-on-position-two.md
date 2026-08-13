---
type: issue
status: open
severity: friction
tags: [issue, schema, class/n13, wave/schema-form-extraction]
---

# Extract Malli map entries by shape, not position

## Problem

The config-test extraction fix stopped assuming that a Malli map's properties
map is always present. One production schema consumer still drops the first two
forms positionally. For a valid `[:map entry ...]` without properties, it drops
the first real entry and under-declares the loop's write attributes.

## Evidence

- Commit `744ed9ef1` changed the config test to select vector entries.
- `src/seon/cluster/loop.clj:201-216` still applies `(drop 2
  (schema/schema-definition entity))` and then `map first`, with no map guard.
- An independent comparison across the six current entity definitions produced
  `{:actual-count 58, :honest-count 58, :missing (), :extra ()}` only because
  all six current forms contain a properties map. Removing that optional map
  from a valid form makes the positional extraction lose its first entry.

## Owner

`seon.cluster.loop/loop-write-attributes`, with one shared Malli map-entry
extraction idiom if more consumers emerge.

## Acceptance

All `src/` and `test/` schema-form extraction selects actual map-entry vectors
after `:map`; none uses fixed `drop`/`nth` offsets unless it first proves the
optional properties-map position. A regression covers both Malli forms, with
and without properties, and yields the same complete attribute set.
