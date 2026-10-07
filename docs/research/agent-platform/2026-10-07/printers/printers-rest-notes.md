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
- Count correction: commit 695766017 removed 22 sort-by-print sites (fn 11, program 3, datahike 2,
  cluster, config, db, reconcile, test, turn); render.web's 4 land with the page slice (26 of 28).

## ui-print (27 of 32 removed, 1 kept, 4 handed off)

- Pages show values through the value printer's HTML face (`seon.render.value/render-html` with a
  `:seon.render.value/root` address), text bodies (422/500) through its text face
  (`render-ai`); a printer refusal shows its message. render.web (16): debug values, header
  snapshot/digest/fingerprint/path, node attribute table, render pair, declared schema, sent
  context, 422/500 bodies; 503 bodies answer the error's own message. health (5), render.schema
  (2), cluster.status (1), render.var (1), render.walk (1); repl's refusal shows its message.
- Graph labels (render.web graph-model, context-graph-model chips) show a scalar identity's own
  text (`str` of a string/ident/number/uuid), else the node's name; nothing is printed or clipped
  (the graph's CSS lays out long labels).
- Kept: render.web node-attributes-html `(root-href (pr-str [a v]))` encodes a lookup ref of a
  symbol into the walk URL (an address, read back by the route), not a shown value.
- Duplication to collapse: `value-html`/`value-text` (render.web) and `shown-html`/`shown-text`
  (health, render.schema, cluster.status; inline in render.var, render.walk) are the same
  three-line guard over `render.value/render-html`/`render-ai`, because those answer a refusal map
  where a page needs hiccup. The owner is `seon.render.value` (held by deep-review): one total HTML
  and text entrance there deletes every copy.

## log (6 of 30 removed; the rest kept or handed off)

- Removed: boot memory-pressure line prints the refusal's fixed message only (3 rows);
  render.web unreportable-page-result names the page's agent id instead of printing the key
  (2 rows); render.web's unrecorded request fault goes through the printer's text face (1).
- Kept, no value printed (fixed text + scalars/ids): cluster warn-low-space! and serve! (4),
  db projection-fallback (2), sci.eval agent-test-ns `Testing <ns>` header on the test's own
  output (1). Turning log-only warnings into delivered faults is a separate design item (task in
  resources/seon/tasks/seon.fault.edn), not a printer.
- OPEN DECISION: fault print-unrecorded! (2) is the emergency edge where no store holds the fault;
  it prints through `seon.print.value` (the projection-free printer core) because
  `seon.render.value` needs the projection and config defaults, which may be what failed. If
  bounded-text goes (second-clipper class), this edge needs a ruling: what an unrecorded fault
  writes (fixed sentence + throwable classes only, or the printer core's bounded text).

## cli-print (1 of 4 removed)

- script/seon/dev/harness_costs.clj measure returns its result (the REPL shows it) instead of prn.

## Handed off (held files, never edited here)

- seon.test.runner (reach-review): reach-canonical 778-779 sort-by-print (sorted-map-by/sorted-set-by
  over pr-str → `seon.print/compare-values`, keep a printed tie-break for incomparable leaves as
  storage-compare does); report-error!/report-event! 301-322 log (5).
- printers-errors: seon.error reply-html 2253/2282 and elision-html 2571 (ui-print 3), problems
  html-report 581 (ui-print 1), schema warn-classpath-fallback! 1132-1142 (log 3, fixed text),
  schema.admission -main 509 (cli-print), script/seon/operator.clj headline 1834 and -main 1884
  (cli-print 2) and -main 1888 (log 2).
- heap-retained: cluster.store datahike-log! 468-478 (log 3).
- error-frames: fs log-deletion! 205 (log 2): the whole deletion report printed into a warn line.
