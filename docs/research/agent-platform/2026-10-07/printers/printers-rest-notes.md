# printers-rest — notes (lane printers-rest, 2026-10-07)

Classes: sort-by-print (28), ui-print (32), log (30), cli-print (4), from print-sites.tsv.

## sort-by-print

- `seon.program/edn-round-trip-symbol?` (33.2M calls / 27.0 s on default, health 2026-10-07): the
  pr-str + edn/read-string round trip is replaced by a check of the symbol's text against
  clojure's EdnReader symbol grammar (`readToken`, `read`, `interpretToken`, `matchSymbol` and its
  pattern) plus the first-slash split of `Symbol/intern`. JVM probe: 0 disagreements with the
  reader over 1,000,026 generated symbols from an alphabet of every delimiting character
  (whitespace, `,`, macros, `@`~`, `:`, `/`, `#`, `+`/`-`, Unicode digit, U+0085); 0.26 µs per
  ordinary symbol unarmed against 1.7 µs for the round trip (probe
  tmp/printers-rest/edn-symbol-probe.clj, disposable). Regression:
  `seon.program-test/edn-round-trip-symbol-agrees-with-the-edn-reader` (edge table + 3,000
  generated, fixed seed, oracle = the EDN reader).
- Every `(sort-by pr-str ...)` / pr-str comparator now orders by `seon.print/compare-values`
  (the existing structural order): fn.clj 11, program.cljc 1 (map-binding-entries), cluster.clj 1,
  config.clj 1, db.clj 1, reconcile.cljc 1, test.clj 1, turn.clj 1, render/web.clj 4.
  Orders change once (identity vectors, manifest identity lists, binding-entry component order);
  they are deterministic, never printed.
- `seon.schema.datahike/storage-compare` orders by `compare-values`; only a tie (leaves the
  structural order cannot tell apart, e.g. tagged literals) falls back to printed forms, because
  a sorted set would otherwise merge distinct members. Stored canonical strings re-order once.
