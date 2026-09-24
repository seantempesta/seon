---
type: landing
status: F6, F7, F8 committed code only (owner load rule); unadopted; one-time kondo fill needed
lane: review-f6-f7-f8
created: 2026-09-24
design: docs/research/agent-platform/review-landings-2026-09-24.md F6, F7, F8
---

# Lane review-f6-f7-f8 (2026-09-24)

Findings F6, F7, F8 of `docs/research/agent-platform/review-landings-2026-09-24.md`,
all landed. For F7, root ruled option 1 after this lane's first stop.

## Broken or open first

- **RESET NEEDED? No, but a ONE-TIME FILL IS NEEDED before the next publication.** With
  `a9449b7c3` adopted, any call into a namespace SCI does not interpret and clj-kondo
  never analyzed refuses publication by name. The checkout cache holds no dependency
  entries today, so publication of `src/` will refuse until root runs, from the checkout
  root, in a quiet window:
  `clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs --config '{:output {:canonical-paths true}}'`
  (`seon.fn/dependency-analysis-command`). It is not timed here; core.async's jar alone
  took 227 ms, and a rerun of a skip-marked jar took 3 ms. Record the clock row.
- **AGENTS.md:306's command is stale (a high-priority defect; root's file).** With
  `--skip-lint`, clj-kondo only copies configs and writes no analysis
  (`clj_kondo/core.clj:126-129`; sequential `process-files` analyzes nothing under
  skip-lint without analysis, `impl/core.clj:468`). Probe: `:dependencies true :skip-lint true`
  on the core.async jar wrote 0 cache entries in 4.7 ms, including with `:parallel`.
  Without canonical paths, a jar entry is named by its bare entry name
  (`impl/core.clj:289-291`). The analyzer's stale check reads that as `::source-absent`
  and deletes it (observed). The corrected command is the one above. The same stale
  command appears in `docs/seon/issues/kondo-does-not-resolve-datalog-parser-generated-variable-constructor.md:13`.
- **Possible new refusals (not run):** a fixture analysis with its own cache that calls a
  dependency or other non-interpreted namespace now refuses instead of silently reading
  the JVM. The runtime (stdin) analysis runs with the cache disabled, so an agent form
  calling a non-interpreted dependency now refuses too. That is correct by the rule, but
  it is a behaviour change.
- I reloaded `seon.fn.analyzer` in default for probes; that drops its instrumentation
  wrappers until re-armed. `seon.fn` was not reloaded.
- The F6 prose assertion ("the unknown is shown by name") still reads the whole
  `problems/ai-prose`, and could elide under many inherited unknowns (fix-problems-prose-bound).

## F6: commit `15539cd68`

`test/seon/test/interrupted_request_test.clj`: both assertions now intersect the failed and
unknown lists with the fixture's own `#{finished red interrupted}`. They assert `#{red}`
failed and `#{interrupted}` alone unknown. The per-symbol checks are kept: without S0, an
open member that is listed as failed, or not listed as unknown, still fails the test.
Net +7 lines.

## F8: commit `e66b50448`

- **Cause (REPL):** clj-kondo attributes the `:rename` keyword inside a top-level `require`
  call to the requiring namespace. The keyword row carried `:from scratch.f8` at row 2,
  col 39. `with-namespace-clauses` took renames only by position inside the `ns` form, so
  for the counterexample `clause-renames` was `[]` and the refer `union` was kept with no
  local `join`.
  Separately, a `:rename` inside the `ns` form carries the *previous* namespace as `:from`
  (probe: `:from user`), not "no `:from`". The docstring is corrected.
- **Fix:** `parsed-facts` (the existing kondo node parse) also returns `::require-forms`,
  the spans of `require`/`clojure.core/require` call lists. A bare `:rename` keyword inside
  one of those spans joins its `:from` namespace's `::clause-renames`.
  `seon.fn/namespace-context` then issues the existing `:seon.fn/index-refused` refusal;
  its message now says "A require's :rename". A body `(:rename m)` is still data.
  `with-namespace-clauses` gained its missing contract.
- **Probe after (JVM, analyzer reloaded):** top-level rename gives `[{:row 2 :col 39}]`.
  An ns-form rename gives `[{:row 1 :col 54}]`, unchanged. A body keyword gives `[]`.
  `namespace-context` on the counterexample returns the refusal and names that entry.
- **Cost:** `parsed-facts` on `src/seon/fn.clj` (3,742 lines, 15,287 nodes) takes
  30.3–33.6 ms after the change. The added filter pass, timed alone, takes 2.3–4.7 ms
  (about 8–15%). That is under the 20% / 50 ms threshold, and each file is parsed once
  per analysis.
- **Regression:** `seon.fn-test/a-top-level-require-rename-is-refused-by-name`, using the
  exact counterexample. It asserts the declared refusal schema and that the named entry is
  on row 2. It would fail before the fix, because `namespace-context` returned a context.
- Net +38 lines across 3 files.

## F7: commit `a9449b7c3` (root ruled option 1)

- `seon.fn.analyzer/analyze` returns `::unanalyzed-namespaces`: the targets of var usages
  that this run does not define and that have no cache file or built-in resource. They
  are looked up in kondo's own order (`impl/cache.clj:23-31`; `:clj` then `:cljc`,
  `:185-187`). `invoke-kondo` returns its `::cache-root`.
- `host-bound-callers` no longer calls `host-macro?` (deleted). A call (a usage with an
  arity) into a non-interpreted namespace is host-bound when kondo marks it `:macro`. If
  its namespace is unanalyzed, the declared `:seon.fn/index-refused` refusal names
  `::unresolved-targets` and the fill command.
- Canonical cache entries survive the check: after the command's options wrote core.async
  into a scratch cache, two `analyze` runs left `clojure.core.async.transit.json` in
  place. The writer, not the check, owned the bug; the stale-check docstring now says so.
- Probes (analyzer reloaded): with the filled cache, `go-loop` is `:macro true` and
  `::unanalyzed-namespaces` is `#{}`; without a cache it is `#{clojure.core.async}`, and
  `clojure.string` (built-in) is never unanalyzed. The `seon.fn` side was not executed
  live (not reloaded; see above).
- Regression: `a-dependency-macro-sci-cannot-expand-is-host-bound` first asserts the
  refusal (targets `<!`, `chan`, `go-loop`, and the message carrying the command). It then
  runs the same fill into the fixture's own cache and asserts looping true, channel and
  plain false. The loaded JVM plays no part: core.async is loaded throughout, yet the
  unanalyzed run refuses.
- Also in this commit: `namespace-context-reads-clauses-from-the-publication-analysis`
  now expects the top-level `clojure.data` require and its `data` alias.
- Net source about +42 lines (fn.clj about +18, analyzer about +24); tests +20.

## Verification limits and what root runs

Per the load rule, no test requests were run. The analyzer namespace was reloaded once in
default (`require :reload`, the owned namespace) for the probes. `seon.fn` was not reloaded,
so the refusal message change is unpublished there. clj-kondo lint of all touched files:
0 errors (only warnings that were already there).

Root runs, serially:
- `bin/test-check default --test seon.test.interrupted-request-test/one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable`
- `bin/test-check default --test seon.fn-test/a-top-level-require-rename-is-refused-by-name`
- `bin/test-check default --test seon.fn.analyzer-test/namespace-clauses-are-every-require-clj-kondo-attributes-to-the-namespace`
- `bin/test-check default --test seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`
  (fixed in `a9449b7c3`)
- `bin/test-check default --test seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound` (it lints core.async into its fixture cache)
- After the fill: one publication of `src/` (e.g. `bin/seon init` or the next adoption), expecting no `::unresolved-targets` refusal

Commits: `15539cd68` (F6), `e66b50448` (F8), `a9449b7c3` (F7 and the stale clause test), plus this note. Released paths:
`test/seon/test/interrupted_request_test.clj`, `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`,
`test/seon/fn_test.clj`, `test/seon/fn/analyzer_test.clj` (not edited), and this landing note.
