---
type: research
status: complete
tags: [research, config, rendering, web]
---

# Value-drill cap default ruling (2026-07-20)

## Decision

Ship these three independent defaults in the existing
`:seon.config/render` policy:

| Attribute | Default | Unit and meaning |
|---|---:|---|
| `:seon.config.render/value-max-path-segments` | 32 | Decoded path elements in one drill request. |
| `:seon.config.render/value-max-path-bytes` | 4096 | UTF-8 bytes of the raw percent-encoded `path` query value, measured before URL decoding or EDN reading. |
| `:seon.config.render/value-max-realized-items` | 1024 | Maximum admitted `offset + page-size` for one selected collection. |

These are maxima, not three projections of one underlying number. An accepted
request with resolved page size `n` still obeys:

```text
offset + n <= 1024
items touched <= offset + n + 1
items retained <= n
```

The extra item is only the honest elided-tail sentinel. With the shipped
`value-max-items` page size of 8, the policy exposes at most 128 successive
pages and the largest accepted page may touch 1025 items. Page-size narrowing
does not raise the total; operation overrides may narrow, never widen, any of
the three maxima.

No benchmark is required to choose these structural defaults. A benchmark
cannot prove the cost of realizing an arbitrary lazy element, so this ruling
does not claim a latency bound. The existing execution deadline and child
isolation remain the time/fault boundary; the new total is a cardinality and
amplification bound. Production observations may justify later tuning through
the same manifest policy without changing the contract.

## Grounding

| Evidence | Selected source | Constraint on the ruling |
|---|---|---|
| Drill-budget contract | [[value-drill-budget-config-boundary-2026-07-20]] at Seon `3d5943dba2db5ae7dc4a8bd58fe3b897239b7835` | Requires three positive integer policies, parent/child parity, pre-lookup admission, checked `offset + n`, and head-plus-one work. It deliberately leaves only the numbers to this owner ruling. |
| Shipped render policy | `config/system.edn:117-149`, `src/seon/config.cljs:100-117,848-872,1139-1174`, and `test/seon/config_test.cljs:455-477` at Seon `e7cc6f941e941d3fdb76dab2e511650a42fc60ca` | One page currently retains 8 items, one displayed string is clipped at 80 characters, and manifest-absent accessors must use byte-identical literal fallbacks. The three new values join this policy but do not reuse these differently denominated caps. |
| Orchard paging | `reference-code/orchard/src/orchard/inspect.clj:44,96-141` at `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | Orchard's page size is 32 and its `drop` plus `take (inc page-size)` establishes truthful paging. Seon keeps the head-plus-one law but adds the missing total-offset ceiling. |
| Execution/database framing | `src/seon/db/protocol.cljc:98-103` and `src/seon/execution.cljs:21-27` | The wire ceiling is 4 MiB with 64 KiB reserved for an execution result envelope. A 4096-byte URL-path budget stays orders of magnitude below that last-resort frame ceiling; frame capacity does not authorize collection work. |
| Browser migration | [[universal-data-browser-ui-migration-boundary-2026-07-20]] | Paging is an explicit click behind bounded initial markup, and normal transcript morphs do not materialize live results. Therefore 8-item pages and a finite 128-page horizon preserve the intended exploratory interaction without making one request unbounded. |
| Path codec ruling | [[projected-map-key-drill-boundary-2026-07-20]] | Only round-trip-safe scalar path components receive controls. The byte cap is still independent because legal bounded strings and keywords can have radically different percent-encoded sizes. |

The concrete URL-encoding falsifier used Bun's `encodeURIComponent`, EDN
vector syntax, and UTF-8 byte measurement. Thirty-two representative keyword
segments encode to 409 bytes; thirty-two 80-character ASCII string segments
encode to 2851 bytes; thirty-two 80-character CJK string segments encode to
23331 bytes. Thus 4096 is not a disguised segment limit: it admits ordinary
deep paths while independently rejecting percent-encoding and Unicode byte
amplification before parsing. The 32-segment limit likewise remains useful for
many short keys even though the same request is far below 4096 bytes.

## Why these numbers

### Thirty-two path segments

Thirty-two is more than ten times the current automatic render depth of 3, so
explicit exploration is not accidentally constrained to the initial tree.
It is also a small fixed upper bound on EDN elements, equality checks, and
`get-in` descent in both processes. Raising the byte cap cannot create a path
with more than 32 descent operations; shortening keys cannot bypass it.

The falsifier is a 32-element short-key path, which must be admitted, followed
by the same path with a 33rd element, which must fail before selection,
descent, child send, or realization.

### 4096 encoded path bytes

4096 leaves substantial room for the route, agent id, selector, offset, and
future ordinary query fields while keeping the attacker-controlled path itself
small. It admits 32 ordinary 80-character ASCII keys under the current string
display cap, but intentionally does not promise that every combination of
maximum-width Unicode keys and maximum depth is addressable. Those are
independent safety dimensions, and non-addressability must be shown honestly
by omitting or refusing the drill rather than silently clipping a path.

The falsifier measures the raw percent-encoded query value with a UTF-8 byte
counter: 4096 bytes is admitted and 4097 bytes is refused before URL decode or
EDN read, regardless of how few elements either value would decode to.

### 1024 total realized items

At the shipped page size of 8, 1024 supports 128 deliberate page actions. That
is already far beyond a plausible manual scan while remaining four orders of
magnitude below the hostile million-entry case that motivated the work-bound
law. It makes late-page cost finite without confusing retained page size with
skipped realization. It also avoids borrowing the database query result cap
of 16384: database result nodes and live collection elements have different
producers and costs.

The falsifier uses a counter-bearing infinite sequence. At offset 1016 and
page size 8, exactly 8 values may be retained and at most 1025 total source
items touched, including the sentinel. Offset 1017 with the same page size is
rejected with the counter still zero. Checked arithmetic rejects unsafe or
overflowed offsets before either case reaches the sequence.

## Acceptance consequences

Implementation must extend the existing manifest, singleton, accessors, and
pure effective-limit normalizer with these exact fallback values. Tests must
prove all of the following rather than merely asserting the constants:

- absent manifest and shipped manifest resolve identically;
- a selected manifest may replace each maximum independently;
- operation limits only narrow each resolved maximum;
- boundary-minus-one, boundary, and boundary-plus-one cases are measured in
  the correct unit;
- path-segment refusal and encoded-byte refusal occur before EDN/descent work;
- `offset + n > 1024`, unsafe integers, and overflow perform zero selection,
  child-send, lookup, and realization work; and
- parent and child accept and reject the same closed request while the child
  independently enforces `offset + n + 1` as the maximum touch count.

These defaults settle numeric policy only. They do not close
[[../../../seon/issues/value-drill-has-no-total-work-bounds]]; closure still
requires the focused configuration, sampler, protocol, route, and instrumented
work-bound proofs named by the budget report.
