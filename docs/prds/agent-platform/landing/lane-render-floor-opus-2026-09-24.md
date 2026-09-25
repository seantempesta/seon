---
type: landing
lane: render-floor-opus
created: 2026-09-24
scope: one-render-path design §3, slices 2 and 3
status: "STOPPED at the size bound; slice 2 allocator not landed; slice 3 blocked on it"
---
# Lane render-floor-opus: slices 2 and 3

## Broken or not done (first)

- **Slice 2 did not land.** The single-pass allocator does not fit the 100-added-line bound. A working draft is
  283 physical lines: about 180 lines of new logic and contracts, plus about 60 lines moved out of `value-node*`
  (leaf nodes, identity/selected dispatch, failure node). It replaces about 140 lines in `value.clj` and allows
  about 310 more to be deleted from `print.cljc` (`fit`, `fit-node`, `fit-children`, `fit-entry`, `fit-text`,
  `projected-text`, `structural-elision`, `elision-node`, `requery-fields`, `preserve-requery`,
  `structural-faces`, the emit-node re-sort). Net is roughly −170 source lines. There are also about 40 test
  lines to convert: four `print/fit` tests in `print_test.clj`, `canonical-order-belongs-to-print-nodes`, and the new
  class regression. Per the lane rule I stopped before editing production source. The draft is
  `tmp/render-floor-opus/alloc.txt` (disposable evidence, not committed).
- **Slice 3 is blocked.** It folds the Throwable/`bounded-text` floor into this allocator, which does not exist yet.
- **The draft probe found two algorithm problems in design §3.** They need a root ruling before any version lands:
  1. *Prefix feasibility is not monotone.* The group elision disappears when every child is shown. So
     "largest affordable prefix, stop at the first failure" rejects `{:a (vec (range 1e6)) :b {:c 1} :d "x"}` at
     B = 100. It collapses the whole map into one elision, although all three children fit without a group
     elision (about 275 of 320 characters). The current floor shows all three. The k = n case must be tested on
     its own terms.
  2. *"Equal shares cover the minima" is too conservative when minima differ.* With `k × max(minimum)`, one
     child whose minimum is a 250-character elision excludes cheap siblings. The rule that guarantees minima is:
     reserve each remaining child's minimum and split only the surplus, `share_i = min_i + (R − Σ remaining
     minima) ÷ left`. The admission test then becomes `Σ minima ≤ available`. Cheapest-first order and passing
     surplus forward are unchanged.
- **The elision face costs about 250 characters (about 80 tokens) per cut.** At B ≤ ~130 tokens a nested
  three-branch value can only show one elision, whatever the allocator does. The draft collapses to one root
  elision at B = 128. The current floor printed 178 tokens against a budget of 128, so it exceeded B. "Part of
  every branch" at small B needs design §3's compact retrieval legend. No slice currently owns that legend.
- **Kept, not deleted, in the draft:** the `max-children`, `max-depth` and `max-string-length` readers. Slice 10
  owns "all readers" of those dials. Removing them here would rewrite about ten `value_test` assertions that
  name those bounds. Canonical map order in the draft is `compare-values` for live values and key text for node
  maps; entity-identity-first ordering is not implemented.

## Landed

- `4362492a0` — `src/seon/print.cljc`: delete the dead `enrich-node` and `enrich-entry` functions (−60 lines, +0).
  They are private and reference only each other; `rg` over `src/` and `test/` finds no other caller.
  Kondo on the file gives 0 errors and the same 11 warnings as HEAD. The namespace was not reloaded into
  default (lane load rule; root adopts).

## Evidence (MCP, JVM mode, root `/Users/sean/src/seon`, cluster `default`, PID 67823)

`runtime_status`: alive, **faulted** before this lane (15 open faults, 331 failed tests; none touch these files).

**Before (HEAD `67645d512`, installed `prepare`):**

| Probe | B (tokens) | ms | alloc KB | Output chars / tokens | Shown |
|---|---|---|---|---|---|
| a/b/c, each a 500-char string at depth 2 | 128 | 1.15 | 4,039 | 570 / 178 (**over B**) | `:sample/a` only as elision, others group-elided |
| `{:a (vec (range 1e6)) :b {:c 1} :d "x"}` | 100 | 0.58 | 1,970 | 319 / 99 | `:a [0..7 …]`, `:b`, `:d` |
| 200 maps × (500-char body + 100-vec), plus deep `:y` and `:z` | 400 | 3.73 | 14,778 | 1,236 / 386 | only `[:x 0 :body]` prefix; `:y` and `:z` lost |
| same | 15,000 (default) | 15.9 | 65,174 | 29,408 / 9,190 | — |

**Draft (scratch namespace `tmp.render-floor-opus`, loaded with `load-string`, then removed; contracts not armed):**

| Probe | B | ms | alloc KB | chars charged / emitted | Shown |
|---|---|---|---|---|---|
| a/b/c | 128 | 0.21 | 465 | 298 / 298 (within 409) | one root elision (elision-size floor) |
| a/b/c | 400 | 2.22 | 2,194 | 1,280 / 1,273 (within 1,280) | **every branch with a 25–26-char prefix** |
| huge + tiny | 100 | 0.20 | 395 | 298 / 298 | one root elision (**regression**, problem 1) |
| nested | 400 | 2.35 | 4,936 | 1,122 / 1,122 | `[:x 0]` elided, `:y` deep string whole, `:z 7` |
| nested | 15,000 | 23.5 | 59,733 | 29,408 / 29,408 | — (+48% ms against 15.9; 8% less allocation) |

Charged characters were never below emitted characters, so emitter-owned charging held on every probe. Namespace
maps (`#:sample{…}`) print shorter than the charge; the difference is the 7 characters at a/b/c B = 400. The
default-B case is slower (+7.6 ms) because every candidate child is priced at share 0 (one elision emission per
candidate) before the prefix is chosen. That regression exceeds 20% and would need fixing before commit.

## Options for root (the reviewed design is silent on a >100-line slice 2)

1. **Simplest viable constraint: a live-values-only allocator (about 120 added).** Print nodes (admitted
   objects, producer nodes, carried elisions) are leaves charged whole, except that strings and projected text
   are cut. Node-structural allocation is deferred to slice 4, when producers return values. Sacrifice: inside a
   structural producer node, carried `:children` totals survive only if the node is not cut; the must-pass
   print_test `fit` regressions are rewritten through `prepare`.
2. **Recommended: land the full draft with the two corrections above (about 180 added, about 350 deleted, net
   about −170) as one slice.** Root explicitly waives the bound, with an independent review of the diff. This keeps
   node and carried-elision allocation, deletes every `fit*` function, and meets the exit criterion. Cost: the
   largest single diff in the design, and the default-B timing regression must be fixed first (price the
   minimum from the child's shallow shape, not a share-0 allocation).
3. **Split into 2a/2b (each ≤100 added).** 2a: the node allocator in `print.cljc` replaces `fit`'s search, and the
   live walk keeps a bounded node counter. 2b: allocation moves into the live walk, and the counter and re-sort
   are deleted. Sacrifice: two cuts to one value until 2b lands, which is the state §3 exists to end; both slices
   are needed before the owner's behavior appears.

## Root runs (after a ruling; nothing to run for `4362492a0` beyond load)

- `bin/test-check default --ns seon.print-test` (proves the load and emission are unchanged after the dead-code deletion)

For a future slice 2 landing:
- `bin/test-check default --ns seon.render.value-test`
- `bin/test-check default --ns seon.print-test`
- `bin/test-check default --ns seon.render.floor-test`
- `bin/test-check default --test seon.render.value-test/background-poll-keeps-identity-while-payloads-grow`

## Out-of-scope finding

- `src/seon/error.clj:346` emits an admitted source node with `print/emit-text`. It relies on the emit-node
  re-sort that §3 deletes for canonical map order. Once that re-sort goes, this site prints admission (iteration)
  order, so equal array-maps built in a different order print different bytes. This caller must route through
  the floor in the same cut. No issue file was created: `docs/seon/issues/` is not on this lane's ownership row.

## Size

Committed: 0 added, 60 deleted (source). Tests: none changed. No test requests, init or extra JVMs were run.
