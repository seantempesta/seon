---
type: research
status: active
tags: [research, agent, dev]
---

# Docstring doc-lint (enforcement half of compact-cards)

## TL;DR

- New WARN-ONLY doc-lint `seon.dev.docstring` (`src/seon/dev/docstring.clj`),
  sibling to `seon.dev.markdown`, plus `test/seon/dev/docstring_test.clj`
  (37 assertions, green via `clj -M:test`).
- Checks a PUBLIC `defn`'s docstring FIRST LINE: (a) present, (b) <= 78 chars,
  (c) ends in terminal punctuation (`.`/`?`/`!`). Pure syntactic parse via
  rewrite-clj — no eval, tolerant of `.cljs` `#js`/reader-conditionals.
- Run across `src/`: **202 files, 1195 public fns, 600 findings** — of which
  **560 lack terminal punctuation**, 28 missing docstring, 12 too-long. The
  560 matches the spec's "~560 fns need work" estimate exactly.
- NOT wired into `.claude/seon-hook.edn` yet (another lane is live on the
  tree). Wiring is documented below, gated behind a config flag, warn-only.

## Where it lives

`src/seon/dev/docstring.clj` — the `seon.dev.*` linting family is all `.clj`
(the dev hook runs in the Seon JVM via nREPL; `seon.dev.markdown`,
`seon.dev.lint`, `seon.dev.repair`, `seon.dev.review` are all `.clj`). This
lint is their sibling and matches `seon.dev.markdown`'s shape exactly: pure
analysis, namespaced findings, a `format-*` fn for hook feedback.

It does NOT require `seon.agent.ctx.namespaces` (that ns is `.cljs`,
unreachable from the JVM linter). The two structural ns-skips
(`*-test`, `*.internal`) are reimplemented as private one-liners
(`test-ns?`, `hidden-ns?`) mirroring `test-ns-name?`/`hidden-ns-name?`.

## The rule as implemented

For every PUBLIC `defn`, take line 1 of the docstring
(`(str/trimr (first (str/split-lines doc)))`) and warn on the first of:

| Rule | Condition |
|------|-----------|
| `:missing-docstring` | no docstring string literal after the fn name |
| `:blank-first-line` | line 1 is blank (docstring starts with a newline) |
| `:first-line-too-long` | `(count line1)` > 78 (72 is ideal; 78 is the hard cap) |
| `:no-terminal-punctuation` | last char of line1 not in `#{. ? !}` |

Checks are ordered (first-match wins) so a fn reports ONE finding, not four.

The spec's rule (d) "a complete thought" is deliberately NOT mechanized — it
is not decidable from source, and the "Enforcement" section only lists
missing / >78 / no-terminal-punctuation. The terminal-punctuation check is the
practical proxy: the dominant real defect (81% of the corpus) is a mid-sentence
hard-wrap (`"…schema keywords. Used by"`), which reads broken in a card AND
lacks terminal punctuation — so rule (c) catches it.

## Edge-case decisions

- **Private fns skipped**: `defn-` and `^:private`/`{:private true}` names are
  never linted (cards only show public verbs). Detected via `(:private (meta
  name-sym))` after rewrite-clj `sexpr`.
- **Non-`defn` skipped**: `def`, `defmacro`, `defmethod`, `deftest`, comments,
  reader-tagged forms — only `(defn …)` heads are inspected.
- **Docstring position**: the docstring is the string literal IMMEDIATELY after
  the fn name (canonical `(defn name docstring? attr-map? …)` order). A fn whose
  first post-name form is an attr-map or an arglist reads as *no docstring*
  (`:missing-docstring`), which is correct — an attr-map-first fn has none.
- **Multi-arity**: handled — the arglist being a list-of-lists changes nothing;
  the docstring (if any) still sits right after the name.
- **`.cljs` robustness**: parsing is rewrite-clj (syntactic node tree, never
  evals). `#js {…}`, `#?(:cljs …)`, `js/Foo`, namespaced maps all parse fine;
  `n/sexpr` is guarded so an exotic tagged literal on the name/docstring node
  degrades to nil rather than throwing. An unparseable file → skipped clean.
- **`*-test` / `*.internal` namespaces**: skipped wholesale (`::skipped? true`),
  matching the render's own filter.
- **72 vs 78**: the WARN threshold is 78 (the hard cap from the "Enforcement"
  section), not 72. 72 stays the documented ideal in the docstrings; the lint
  only fires past the hard cap so the corpus sweep isn't drowned in near-misses.

## Public API (all `:malli/schema`'d, instrumentation-verified)

```clojure
(check-source {::source "…" ::ns-name "seon.foo"?})  ; => {::clean? ::skipped? ::findings}
(check-file   {::file-path "src/seon/foo.clj"})       ; reads + check-source
(format-findings {::findings [...] ::max-length 800}) ; => {::formatted "WARN …"}
(scan {::file-paths ["src/seon/a.clj" …]})            ; audit: {::file-count ::fn-count
                                                      ;         ::finding-count ::by-rule ::findings}
```

All four response shapes were validated against the registered malli schemas
with the process-global registry (`m/validate` = true for each), so they pass
runtime instrumentation when loaded live.

## Corpus audit (validates the ~560 estimate — proves it works on real code)

`(scan)` over every `src/**/*.clj[cs]`:

```
files linted 202   public fns 1195   findings 600
by-rule {:no-terminal-punctuation 560, :missing-docstring 28, :first-line-too-long 12}
```

Sample findings:

```
no-terminal-punctuation  debug.cljs  L65  set-override!: docstring line 1 lacks terminal punctuation (. ? !)
no-terminal-punctuation  debug.cljs  L75  enabled?:      docstring line 1 lacks terminal punctuation (. ? !)
missing-docstring        log.cljs    L159 warn-console!: public fn has no docstring
missing-docstring        log.cljs    L162 info-console!: public fn has no docstring
first-line-too-long      html.clj    L50  nav-bar:       docstring line 1 is 87 chars (cap 78)
first-line-too-long      sse.clj     L414 shutdown-sse!: docstring line 1 is 81 chars (cap 78)
```

560 no-terminal-punctuation is the same order as the spec's live-cluster audit
(560 fns need work). The count is higher-scope here (1195 public fns across BOTH
tracks vs the 671 runtime-indexed CLJS fns) but the dominant-defect ratio holds.

## How it wires into the dev hook (WARN-ONLY — owner enables later)

The Clojure PostToolUse pipeline in `seon.dev.hook.clj` (~L630) already has a
NON-BLOCKING feedback stage: compliance pushes onto the `feedback` atom without
returning a `block-response`. The docstring lint plugs in the same way, right
after the compliance stage:

```clojure
;; after stage-compliance's (swap! feedback conj …), before unit tests:
(when (get-in config [:docstring-lint :enabled])
  (let [res (docstring/check-file {::docstring/file-path file-path})]
    (when-not (::docstring/clean? res)
      (swap! feedback conj
             (::docstring/formatted
              (docstring/format-findings
               {::docstring/findings (::docstring/findings res)
                ::docstring/max-length 800}))))))
```

Key points that keep it warn-only and safe:

- It ONLY `swap!`s onto `feedback` — it NEVER calls `block-response`, so an edit
  always proceeds. (Contrast: repair/reload/unit-tests can return a block.)
- It runs on the ALREADY-EDITED file (`check-file` re-slurps), same as
  compliance — it reports on the new content.
- Add `(:require … [seon.dev.docstring :as docstring])` to hook.clj.
- Config gate in `.claude/seon-hook.edn` (NOT added yet):

  ```clojure
  :docstring-lint {:enabled true}   ; default false / absent until owner flips it
  ```

### Recommendation on hook wiring

**Safe to wire warn-only, but leave DISABLED in the shared
`.claude/seon-hook.edn` until the active lane pauses.** The integration cannot
block (it only appends feedback), so it will not wedge the tree. The only
downside is a new warning stream on every `.clj`/`.cljs` edit mid-build, which
the task flagged as disruptive to the parallel lane. So: land the code + the
gate wiring, keep the flag `false`, and let the owner flip
`:docstring-lint {:enabled true}` once the corpus sweep is the active work
(the sweep wants exactly this feedback loop). Until then `scan` is available for
one-shot audits without touching the hook.

## Files

- `src/seon/dev/docstring.clj` — the lint (public API above).
- `test/seon/dev/docstring_test.clj` — 12 tests / 37 assertions, green.
