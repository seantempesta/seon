---
title: "Card SR-H Live results live in the one cache"
type: reference
status: active
created: 2026-10-08
---

# SR-H Live results live in the one cache

Ledger row SR-H (group C; replaces MEM-03, AT-3's `::result-objects` part). Owner: live results in `seon.cache` as
an LRU under its byte budget, no count; stop reinventing (Caffeine's own W-TinyLFU). Spec: agent-loop.md §3.4
"History" (an evicted handle answers gone-value data with its saved shown text). A change to the one REPL path:
**Opus lane**.

## Files
`src/seon/cache.clj`, `resources/seon/schemas/seon.cache.edn`, `src/seon/sci/eval.clj`, `src/seon/render/walk.clj`,
`src/seon/repl.clj`, `test/seon/cache_test.clj`, `test/seon/repl_differential_p2_test.clj`.

## Functions
- ADD `seon.cache/put!`
  `{:malli/schema [:function [:=> [:cat :qualified-keyword :seon.schema/value [:int {:min 0}] :seon.schema/value] :nil]
                              [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value [:int {:min 0}] :seon.schema/value] :nil]]}`
  `[kind content-key weight value]`: `(.put (.asMap cache) [kind content-key] (CompletableFuture/completedFuture {::value value ::weight (+ entry-bytes weight)}))`
  and the kind's `::bytes` counter, as `settle!` does (`src/seon/cache.clj:131-160`). A value already computed by
  evaluation is not a derivation, so `lookup` does not fit.
- ADD `seon.cache/present`
  `{:malli/schema [:function [:=> [:cat :qualified-keyword :seon.schema/value] [:or [:map [:seon.cache/value :seon.schema/value]] :seon.cache/absent]]
                              [:=> [:cat :seon.cache/cache :qualified-keyword :seon.schema/value] [:or [:map [:seon.cache/value :seon.schema/value]] :seon.cache/absent]]]}`
  `[kind content-key]`: `getIfPresent`; a completed entry answers `{:seon.cache/value v}` (nil live results are
  values too), else `{:seon.cache/absent kind}`. Never derives, never blocks on a pending entry.
- SCHEMA `seon.cache.edn`: `:seon.cache/value` (`:seon.schema/value`), `:seon.cache/absent [:map [:seon.cache/absent :seon.cache/kind]]`.
- CHANGE `seon.sci.eval/bind-result!` (`src/seon/sci/eval.clj:579-598`): replace the `::result-objects` swap with
  `(seon.cache/put! :seon.repl/result evaluation-id weight value)`, weight `(* 4 value-tokens)` from the evaluation's
  `:seon.eval/value-tokens` (`seon.eval.edn:41`), or `(* 4 shown-tokens)` when absent; nothing walks the value. The
  arity gains the weight: `[ctx handle value weight]`, contract `[:=> [:cat :seon.sci.eval/ctx :qualified-symbol <value exemption as today> [:int {:min 0}]] :qualified-symbol]`;
  convert every caller (grep `bind-result!`, including `::bind-result!` at `:3203`).
  The `result` namespace intern stays only for handles a pass's forms name: before a pass, intern from the cache the
  `result/e…` symbols its read forms mention; a miss interns the unstored marker
  (`{:seon.eval/unstored :live-only …}` as `bind-settled-results!` builds it, `:3035-3049`, plus the saved shown text).
- DELETE `::result-objects` (`:3067` in `fork-for-turn`) and its readers: `render/walk.clj:812` and `repl.clj:607`
  read `(seon.cache/present :seon.repl/result evaluation-id)` instead.

## Data
```edn
[:seon.repl/result "a1b2c3d4e5f6"] -> {:seon.cache/value <live value> :seon.cache/weight 4256}
(seon.cache/present :seon.repl/result "gone00000000") ;; => {:seon.cache/absent :seon.repl/result}
```

## Proof
`seon.cache-test/results-evict-under-budget` (new): `(seon.cache/create 1000000)`, 20 `put!`s of 100 KB weight;
`(count (seon.cache/retained c :seon.repl/result))` ≤ 10 and every evicted key's `present` answers absent.
`seon.repl-differential-p2-test/handle-reads-cached-value` (new): through `seon.mcp/call` SCI, evaluate
`(vec (range 5))`, then `result/e<id>` in a second call returns `[0 1 2 3 4]`; after `(seon.cache/evict! :seon.repl/result id)`
the same handle answers a map with `:seon.eval/unstored` and the saved shown text.

## Other tests reaching it
`seon.cache-test`, `seon.repl-differential-p2-test`, `seon.sci.eval-test`, `seon.mcp-entrance-test`
(`a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`), `seon.render.walk` continuation tests.

## Cost
M: retained heap per idle agent follows the values it holds, under the one byte budget (512 MB default ruled).
A put is O(1); a pass interns only the handles its forms name.

## Deleted
`::result-objects` atom and its two readers' deref paths (~15 lines). AT-3 folds the other per-fork atoms later.

## Commit
`bin/lane commit <lane> -m "SR-H: live results live in the one cache" --test seon.cache-test --test seon.repl-differential-p2-test --test seon.sci.eval-test --test seon.mcp-entrance-test src/seon/cache.clj resources/seon/schemas/seon.cache.edn src/seon/sci/eval.clj src/seon/render/walk.clj src/seon/repl.clj test/seon/cache_test.clj test/seon/repl_differential_p2_test.clj`

## Dependencies
PB-02b (same file, group C order). `test/seon/sci/eval_test.clj` is held by `led-storage` at the time of writing.

## Reset
None (live values never survive a restart anyway).
