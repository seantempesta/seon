# Review of 7619b8dfd (value printer) — review-printer-opus

Fresh-context Opus review, independent of `review-printer-codex` (whose notes, committed as eabc94b59 while this
review ran, are cross-checked below).

## Broken first

1. **Charge overflow, fixed here (pre-existing, not caused by 7619b8dfd).** `fold-collapsed-tail` folded a
   sequence's collapsed trailing members into a tail cut assuming "the text only shrinks". It does not:
   `[…1 (get-in result/e… [0 1])]` (40 characters) became `…1 (drop 1 (get-in result/e… [0]))` (44). The text then
   exceeded its charge, `prepare` raised "The value printer's saved text exceeded its charge." and printed the
   whole value through the plain fallback, past the budget (388 characters at an 80-token budget). Repro:
   `[(0 [s88]) {:A s66-with-quotes :B {:A nil :B 0}}]` at 70-80 tokens; the existing task evidence
   `tmp/related_data/charge-overflow.edn` as `[3.3 value]` fails at every budget 600-3,000 tokens on the parent
   and on 7619b8dfd, and at none after the fix. This is `seon.render.value/nested-hiccup-exceeds-the-printers-charge`;
   the new law test is tagged with it.
2. **Escaped strings refused outright (regression in 7619b8dfd), fixed here.** `string-shown` compared the raw
   hidden length with the cut's cost, so 64 quotes / backslashes / tabs at 40 tokens returned
   `{:needed 130 :budget 128}` (no text) where the parent printed a 31-token cut. Confirmed in a compiled probe
   JVM; review-printer-codex found it first (`seon.render.value/string-admission-compares-raw-and-escaped-cost`).
   Their patch is applied: the escaped width is compared after the raw-length precheck, which bounds the scan.
3. **HTML face lost line breaks (regression in 7619b8dfd), fixed here.** The string literal now carries real
   newlines, but `.seon-print-string` had no `white-space` rule, so the browser collapsed a source into one line
   (before, it at least showed `\n`). Measured in the browser pane on a served copy of the hiccup: computed
   `white-space` `normal`, 1 client rect before; `pre-wrap`, multi-line after.
4. **Missing contract (7619b8dfd), fixed for `seon.print/leaf-ops`.** Not fixed for
   `seon.render.value-test/agent-unit`: that file is held by lane `task-tags`; patch below.
5. **The two pre-existing value-test reds are stale assumptions, not defects** (patch below, task
   `seon.render.value/renderer-tests-assume-retired-text-layout`, filed by codex).

## What was verified (compiled code, in a disposable probe JVM)

Method: `git archive` of `7619b8dfd^` and of HEAD into my scratchpad, each run as a plain `clojure.main` socket REPL
with the repository's dependency classpath, no store, no default connection. The parent/change comparison swapped
`seon.render.value` by `load-file` inside that JVM only. Deviation from the assignment's "own SCI branch": SCI
interprets the printer, so its timings and allocations say nothing about the compiled printer the agents run, and
`my.fs/read` reads the running archive, not the commit. Default's JVM was never given this code.

### 1. Generality — supported

Every new rule is decided by shape or cost: a string with a newline prints as lines; a string is whole when its cut
costs what it hides; every queued refinement is tried; leftover budget grows cut strings to a line end. No schema,
key, or kind branch was added. The budget dial is a profile value.

### 2. Correctness — supported after the fixes above

- Read-back: `"a\nb"`, trailing newline, `"\n"`, `"\n\n"`, empty line, tabs/quotes/backslashes, `\r\n`, NUL, U+2028,
  CJK, emoji, a literal `\\n`: all `edn/read-string` back equal.
- Continuations: for 120-line, 300-line, blank-line, escape-heavy and nested sources at 200-3,000 tokens, every
  string cut's prefix plus `(subs s next-offset)` equals the string; single-value sources cut at a line end.
- Budget: generative law (vectors, lists, maps, 0-60-line strings with quotes, tabs, `\r`, CJK; budgets 60-1,500):
  on 7619b8dfd it fails within 3 trials (the charge overflow above); after the fix 1,000 trials x 3 seeds and a
  wider 1,000-trial run with sets and pairs pass: no diagnostic, text within budget, exact cuts, read-back when uncut.
- "Long line after multi-line string overflows width" (source-display finding): **refuted.** Fipp measures a
  multi-line literal as one token of its full length, which is at least its last line, so its column is an upper
  bound and following text underfills (`b" :k1 … :k15` = 68 of 72 columns). No task; codex filed
  `seon.print/multiline-literals-hide-line-breaks-from-layout` for the underfill.

### 3. Cost — no super-linear work; constant in input size

Compiled, warm, per-thread `getThreadAllocatedBytes`, median of 3 (ms are 1-6 ms everywhere, noise-level):

| value (agent profile, 1,500 tokens) | parent MB | 7619b8dfd MB | after fixes MB |
|---|---:|---:|---:|
| 10k-key map | 2.86 | 2.90 | 2.91 |
| 10k-key map of 40-char strings | 4.11 | 3.59 | 3.60 |
| `(vec (range 1e6))` | 2.08 | 2.09 | 2.10 |
| 1e5 `[string n]` pairs | 9.64 | 4.67 | 4.66 |
| 1e5 strings | 3.99 | 2.48 | 2.48 |
| 10k-deep map / vector | 1.39 / 1.07 | 1.41 / 1.08 | 1.43 / 1.09 |
| 50 MB one-line string | 0.57 | 0.67 | 0.67 |
| 50 MB of lines | 0.57 | 0.67 | 0.67 |
| `(range)` | 1.67 | 1.68 | 1.70 |
| `[0..31]` | 1.62 | 1.63 | 1.63 |
| 50 eleven-line sources | 8.45 | 10.3 | 11.0 |

`fill-end` walks at most the remaining budget's characters and `lastIndexOf` starts at the growth end; the removed
freeze only re-tries refinements of admitted nodes. Allocation of 1.6-12 MB to print at most 4,800 characters is
the open class `seon.render.value/printing-thirty-two-numbers-allocates-megabytes`. Armed-call counts need the Seon
runtime and were not measured here (codex measured the per-character armed `escaped-width` call; fixed above).

### 4. Readability — good on ordinary values, three ugly shapes left

Printed (1,500 tokens): a 46-line source (972 tokens, whole, as lines), `my.program/source` of a 113-line and a
140-line function (one cut each at a line end, dig form exact), a task pull (169), two open-fault maps (1,154, one
`…12` cut), a test-result map (133), 30 qualified keywords (254, two per row), nested maps with a set, list, uuid
(40), `seon.health/report` (1,229, 15 cuts). Still ugly:

- Many long strings at one level each get a cut (50 sources: 31 cut at 64 characters with a 45-character form
  each), and collapsed maps cost more than they hide (`{…8 (get-in result/e… [:seon.health/cache
  :seon.cache/kinds :seon.cluster/file-derivation-key])}`). Task `seon.render.value/cuts-cost-more-than-they-hide-across-siblings`.
- A multi-line literal's later lines start at column 0, and a map value that is a long source goes to its own line
  (fipp sees one wide token). Inherent to an exact literal; codex's task covers the layout part.
- `my.program/source` repeats the docstring (`:doc` beside `:source`): `my.program/source-repeats-the-docstring`.
- `java.util.HashMap$Node` prints `#object[java.util.HashMap/Node 0x…]`: `seon.render.value/short-strings-in-pairs-are-cut` (open).
- A lazy seq that throws: chunked `(map f (range 10))` prints `()` (Clojure leaves the chunked LazySeq empty after the
  first throw, which the printer's renderer check triggers) beside a "renderer failed" line with no renderer;
  `{:a throwing-seq}` prints the whole map as `#unprintable[PersistentArrayMap ExceptionInfo]`. Same on parent.
  `seon.render.value/a-lazy-seq-that-throws-shows-as-empty`, `seon.render.value/failed-selection-escapes-the-profile-budget`.
- A cut under a list member offers `(subs (get-in result/e… [0]) 127)`; `get-in` does not index a list, so the dig
  form reads nil and throws. Task `seon.print/a-dig-form-through-a-list-index-reads-nil` (the law test therefore
  checks cut continuations with `seon.print/value-at`, which steps lists).

### 5. Tests

- 7619b8dfd's two new tests are at the right place and test one behavior each (the ordinary-values table, the
  line-end fill). Its dated comment belongs in notes; `agent-unit` lacks a contract.
- Added (printer's own tests): `seon.print-test/a-string-literal-keeps-its-line-breaks-and-reads-back` (table);
  `seon.render.value-law-test/the-text-stays-inside-its-charge-and-budget` (the minimal repro plus a bounded
  150-trial law, seed fixed; red on 7619b8dfd within 3 trials, green after);
  `seon.render.value-law-test/escaped-strings-stay-printable-under-a-small-budget` (red on 7619b8dfd: refusal).
- `a-value-s-own-inline-function-renders-it`: **stale layout assumption.** It asserts the text starts with
  `{:a {:seon.render/ai #object[`; the compiled test fn's long class name makes the map break across lines. The
  behavior (no re-dispatch: the nested fn stays an object node) still holds.
- `dir-of-a-large-namespace-is-its-declared-renderer-text`: **stale assumption.** `seon.repl/render-directory-ai`
  now returns a vector (renders return data), and the test calls `tokens/estimate` and `subs` on it.

Patch for `test/seon/render/value_test.clj` (held by `task-tags`; root ruled this patch is the one to apply; **not run** here):

```diff
@@ (deftest dir-of-a-large-namespace-is-its-declared-renderer-text
-       (is (> (tokens/estimate rendered) (:seon.render.profile/token-budget profile))
-           "the directory exceeds the raw budget")
-       (is (str/starts-with? shown (subs rendered 0 500)))
-       (is (re-find #"…\+\d+$" shown))
-       (is (<= (count shown) (+ (tokens/estimate-chars (:seon.render.profile/token-budget profile)) 16)))
+       (is (vector? rendered) "the renderer returns data")
+       (is (str/starts-with? shown "[{:sym seon.turn/") "the data prints through the value printer")
+       (is (<= (tokens/estimate shown) (:seon.render.profile/token-budget profile)))
        (is (= 'seon.repl/render-directory-ai (:seon.eval/renderer result)))
@@ (deftest a-value-s-own-inline-function-renders-it
-       (is (str/starts-with? (text {:a {:seon.render/ai (fn [_] "inner")}}) "{:a {:seon.render/ai #object["))
+       (let [nested (text {:a {:seon.render/ai (fn [_] "inner")}})]
+         (is (not (str/includes? nested "inner")) "a nested render is not invoked")
+         (is (str/starts-with? (str/replace nested #"\s+" " ") "{:a {:seon.render/ai #object[")))
@@ (defn- agent-unit
   "`raw` under the shipped agent profile, with a result handle."
+  {:malli/schema [:=> [:cat :seon.schema/value] :map]}
   [raw]
```

(The codex patch for the same two tests asserts `:seon.eval/elisions` and `:seon.eval/shown-tokens`; I did not
verify those keys exist on the evaluate result, so the patch above asserts the shown text instead. Root picks one
and runs it.)

### 6. The source-display findings — all recorded

| finding | task |
|---|---|
| `defn` without line metadata refused at `[:seon.batch/origin :line]` | `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin` (codex); the live fault row is `seon.sci.arm` "faults of write-event" (`db92135a9301`, signature `3cabe38a…`) |
| `my.fs/read` reads the running archive, 93 KB in 1,057 ms / 16.6k calls | `my.fs/archive-file-reads-have-no-explicit-source-world` (codex), related `my.fs/fs-read-ignores-limit-and-has-no-line-addressing` |
| `load-string` ~160 forms 1.7 s / 54k calls | `seon.sci.eval/loading-source-arms-tens-of-thousands-of-calls` (codex) |
| `[0..31]` allocates 2 MB (measured 1.63 MB compiled here) | `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` (codex) |
| `my.program/source` repeats the docstring | `my.program/source-repeats-the-docstring` (codex) |
| `HashMap$Node` prints `#object` | `seon.render.value/short-strings-in-pairs-are-cut` (existing, open) |
| long line after multi-line string overflows width | refuted (section 2); layout underfill in `seon.print/multiline-literals-hide-line-breaks-from-layout` (codex) |

New tasks created by this review on default (`seon.task/create!`, agent `review-printer-opus`):
`seon.print/a-dig-form-through-a-list-index-reads-nil` (`c8f5629a24d5`),
`seon.render.value/cuts-cost-more-than-they-hide-across-siblings` (`9dac37c43a49`),
`seon.render.web/the-built-stylesheet-is-stale-against-its-source` (`824ba1aed27e`: `bin/css` on HEAD rewrites
`output.css` with 112 unrelated added lines, so this slice hand-adds its one rule to both stylesheets).

## The change in this slice

- `seon.render.value/fold-collapsed-tail`: **BEHAVIOR CHANGE** — folds only when the tail cut plus separator costs
  no more than the folded members and the old cut; regression: the law test.
- `seon.render.value/string-shown`: **BEHAVIOR CHANGE** (codex's patch) — compares the escaped width of the hidden
  characters after the raw precheck; contract gains the string argument. Regression: escaped-strings test.
- `seon.render.value/fill-end`: **EQUIVALENT** (codex's patch) — the per-character width is computed inline (same
  newline/escape rule) instead of an armed `escaped-width` call per character.
- `seon.print/leaf-ops`: contract and docstring added (no behavior change).
- `resources/public/css/{input,output}.css`: `.seon-print-string { white-space: pre-wrap; }`.
- `config/default.edn`: the budget comment no longer says "1,000 is tuned" beside 1,500.

## Design check

The design held: selection, charge and fipp emission compose as before; each fix is at its producer (the fold, the
admission cost, the stylesheet). Awkward: the fold rule was a stated invariant ("the text only shrinks") nobody
checked; it is now decided by the same cost comparison the rest of selection uses. The charge's protection (raise,
then print the plain fallback past the budget) is itself a second, unbudgeted printer path; that is
`seon.render.value/failed-selection-escapes-the-profile-budget`, not patched here.

## Limits

- Armed-call counts and SCI-mode behavior of the fixed code were not measured; default runs whatever root installs.
- Browser paint was checked on a served static copy of the hiccup with the committed stylesheet, not on the live
  web page.
- Default was replaced twice by others during this review. At 06:45Z it came up as pid 12491 with source root
  `42e569d9d` (status `:seon.operator/source-root`), so neither 7619b8dfd nor these fixes are installed there.
- **Commit blocked by a foreign boundary.** `bin/lane commit` refused: the check's publication, run in default's
  JVM, calls the installed (42e569d9d) `seon.fn/task-links`, which refuses HEAD's quoted task tags
  (`[:seon.task/tasks (quote my.edit/file-edit-tools-bypass-write-back)]`, expected a 12-character handle;
  `operator-check-20261007T154903.809.edn`). HEAD's own `task-links` reads quoted symbols. Until default runs
  HEAD (task-tags, 0daf87818/178aa24e0), no lane's check could pass. Root restarted default on HEAD f478bdee1;
  the commit was then re-run through the lane check.
- Evidence instead of the check: a disposable JVM on `git archive HEAD` plus this diff ran
  `seon.render.value-law-test` (2 tests, 10 assertions, green, 1.1 s for six vars),
  `seon.print-test/a-string-literal-keeps-its-line-breaks-and-reads-back`,
  `terminal-emission-is-total-for-an-unknown-or-absent-face` and `one-cut-face-and-held-order`: green.
  `p-total-generated-grammar-emits-and-readable-faces-round-trip` could not run there (`:seon.print/node` is not
  registered outside the Seon runner); the `seon.render.value-test` vars need the store and were not run.
  Unavailable evidence is not a pass: root's central run decides.
