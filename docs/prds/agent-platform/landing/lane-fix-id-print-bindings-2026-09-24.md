# Lane fix-id-print-bindings (2026-09-24)

`seon.id/id` (and `digest`, `evaluation` through it) hashed `pr-str` under the
caller's print bindings. The fix pins them in `seon.id/printed`. The identity
algorithm is unchanged. Evidence row: cross-cutting-astra-2026-09-24.md row 4, P1/P5.

## Broken or unresolved first

- **Not installed in the running JVM.** Default (pid 90471) runs committed
  archive `ce73846…` with hook publication off. `bin/test-check default --ns
  seon.id-test` reused the archive's 2 tests and ran 13 assertions (1634 ms); it
  did not see this diff. Proof is from a `git archive` snapshot (below). The fix
  reaches default on the next start from the checkout (root's call; no RESET needed).
- **The "default" value of `*print-namespace-maps*` is `true`, not the Var root
  `false`.** `clojure.main/with-bindings` (`reference-code/clojure/src/clj/clojure/main.clj:88`)
  sets it true for the REPL and for `-m`/`-e` mains, and bindings are conveyed to
  futures. Stored identities show this. Of 715 stored `:seon.test.report/id`,
  **715/715** recompute under `true`. Under `false` only the **433** whose
  position map is empty recompute, because the 282 non-empty ones print as `#:seon.test.failure{…}`.
  So the owner pins `true`. Pinning the root value would have changed 282 stored ids.
- Risk: an identity computed on a thread that did not convey the REPL bindings
  (e.g. a raw executor or go block) used `false`. Any such stored id with a
  single-namespace keyword map now recomputes differently. None was found in
  the families sampled. Other ns-map-sensitive producers are
  `test/runner.clj:710` (schema reach leaves over property maps) and
  `fn.clj:1395` (declaration digests over `select-keys` rows, which are not stored).
  They were not recomputed from storage.
- Out of scope, reported only: equal maps with different entry order still hash
  differently under default bindings, e.g. `{:a 1 :b 2}` gives `824d3980ce8d`
  and `{:b 2 :a 1}` gives `676db052b64c`. A 9-entry array-map and an equal hash-map
  also differ, and so do `[1 2]` vs `'(1 2)` and `1` vs `1N`. Two 20-key hash maps
  built in opposite orders matched. Callers that need order-independence still
  wrap in `sorted-map` (`error.clj:1496`, `issue.clj:547`, `cluster.clj:2062`).
- Probe incident: a scratch probe evaluated `id.clj`'s working-tree `ns` form in
  default. That replaced `seon.id`'s namespace docstring and changed no Vars. It
  was restored by evaluating the loaded archive's own `ns` form, and the archive doc was verified.

## Evidence (default, read-only, MCP JVM mode)

- Print vars that change `pr` output: `*print-length*`, `*print-level*`,
  `*print-meta*`, `*print-namespace-maps*`, `*print-dup*` and `*print-readably*`.
  The source is `core_print.clj:42-274` plus `pr-on` (`core.clj:3698`).
- Recomputed stored identities from their stored parts under the pinned
  bindings: test report ids 715/715; error occurrence ids 19/19; lint ids 893/895
  (the 2 misses are my owner-symbol reconstruction; they miss under both
  ns-map settings, so they are not print-binding related).
- Differential over 3,715 stored values (3,000 pulled program rows + 715
  report rows): pinned function vs old function on the REPL thread, equal
  **3715/3715**. Under hostile bindings (`*print-length* 1`, `*print-level* 1`,
  `*print-meta* true`, ns-maps false, `*print-readably* false`, `*print-dup* true`)
  the old function changed **3217/3715** ids; the pinned function changed **0/3715**.
- Snapshot proof (HEAD + diff via `git archive`): `seon.id-test` gives 3 tests,
  28 assertions, 0 failures, in 0.9 s. HEAD `id.clj` with the new test gives 10 failures, so the
  regression fails without the fix.

## Cost (armed JVM, µs per call, old → new)

small `["turn-1" 3]` 4.72 → 5.24 (+0.5 µs); namespaced 3-key map 5.85 → 6.25;
2,070-char program row 41.68 → 41.95. The largest relative cost is +11%, from
the fixed binding frame. That is under the 20% / 50 ms bar.

## hasch.core/edn-hash migration (reported, not implemented)

hasch 0.4.100 is installed (via datahike). It is order-independent for maps,
ignores print bindings, and treats `1` and `1N` alike. It distinguishes `[1 2]`
from `'(1 2)` and hashes `#inst`. It **throws** on `java.time.Instant` (no
`PHashCoercion`) and reads a `java.io.File`'s contents. Migrating would require
all of the following:
- a `PHashCoercion` extension or refusal for every type identities receive
  (Instant, records, Vars, and so on);
- a fixed truncation and hex encoding of its SHA-512 bytes;
- re-deriving every stored identity in one adoption: test reports, occurrences,
  lint, turn and evaluation ids, `result/e…` handles in shown text, reach,
  source and definition digests;
- converting the `sorted-map`-wrapping callers.

This is one algorithm switch, not piecemeal; it needs an owner decision.

## Changes

`src/seon/id.clj` +18/−6 (new private `printed`, docstrings);
`test/seon/id_test.clj` +13 (one class regression). Net +25 lines.
