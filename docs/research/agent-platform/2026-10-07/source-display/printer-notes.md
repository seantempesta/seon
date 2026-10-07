# Value printer: small values whole, multi-line strings as lines, sources fit (lane source-display, 2026-10-07)

Scope after root's scope change: only the general value printer (`seon.render.value`, `seon.print`) and the agent
budget dial. The context producers (`seon.agent.loop`) and the context examples were not edited; the only commit there
is 52fc6a8d7, root's `installed-42e569d9d/` files committed as the "before" record.

## Broken first

- Two `seon.render.value-test` reds are red on HEAD 52fc6a8d7 too (`bin/seon check --test seon.render.value-test`,
  run 1bbbb02c9cef): `a-value-s-own-inline-function-renders-it` (expects one line, the value now lays out over three)
  and `dir-of-a-large-namespace-is-its-declared-renderer-text` (its renderer answers a vector where the test expects
  text; `seon.ai.tokens/estimate` refuses a vector). Not caused by this slice; not fixed here.
- The change is proven in throwaway JVMs (`bin/seon check`) and in a scratch SCI copy on agent `source-display`'s
  branch. It is **not installed**: default needs root's restart on the new HEAD.

## What was wrong (probed on default 42e569d9d, agent profile, 1,000 tokens = 3,200 characters)

| value | before |
|---|---|
| 32 pairs `["seon.profile/some-function-name-N-with-a-longer-tail" N000]` | rows 0-14 whole, then every row `["seon.profile/some-function-name-"…+21 (subs (get-in result/x1 [28 0]) 32) …1 (drop 1 (get-in result/x1 [28]))]`: 31 cuts, 848 tokens, each cut longer than what it hid |
| a 60-line source (2,118 shown of 3,214 chars) | one escaped line `"(defn- normalized-index-entity\n  \"Compare …`, cut at 2,048 characters with 1,166 left: the budget was a third unspent |
| a 367-line source | cut at 2,048 characters, 660 tokens of a 1,000-token budget |
| `seon.health/report` through the core | digests `"c69d9cfeb5a781aa50136efa358cc9cc"…+32`, cut with no dig form while the budget allowed more |
| `(vec (range 1e6))` | `:value-tokens 527366` |

Causes, in `seon.render.value/select-nodes`:

1. Every string was born at a 32-character chunk, even when its cut text (`…+N (subs (get-in result/… [path]) 32)`,
   40-60 characters) cost more than the 10-40 characters it hid. A pair holding such a string was not a tuple, so it
   was admitted member by member and its number could be cut too.
2. After the first budget refusal at a level, nothing deeper was tried (`:refused-level`). An intermediate selection
   is expensive (collapsed members and first chunks each carry a cut), so the freeze kept those cuts while the
   finished text was far under budget.
3. A string grows by doubling chunks; the chunk past the budget was refused whole, leaving up to half the budget
   unspent (2,048 of 3,200).
4. A string literal escaped its newlines, so source, docstrings and Markdown printed as one line of `\n`.

The `(vec (range 1e6))` estimate is **not** a walk: `omitted-characters` multiplies the omitted count by the mean
shown member width, from the selected nodes only. Measured on default (JVM, `prepare`, warm): 1e3, 1e6 and 1e7
element vectors each take 1.6-1.9 ms and 2.07-2.12 MB, constant in the value's size. The 527,366 is an estimate of
a value that really is about 2M tokens; no change needed. (2 MB allocated to print 32 numbers is itself heavy; see
the open items.)

## The change (four general rules; no schema or value-kind branch)

- **BEHAVIOR CHANGE** `seon.print/string-literal`: a string prints as a readable literal whose newlines stay line
  breaks (quotes, backslashes and tabs stay escaped). It reads back to the same string (`edn/read-string`).
  `seon.render.value/escaped-width` charges a newline as one character. Decided by shape: any string with a newline.
- **BEHAVIOR CHANGE** `seon.render.value/string-shown`: a string is admitted whole when its cut at the first chunk
  would cost at least the characters it hides (the rule `complete-chunk` already applied later, now at admission);
  `tuple-node` uses the same test for string members instead of the fixed 32-character bound, so a pair or frame
  with such a string is one whole tuple.
- **BEHAVIOR CHANGE** the `:refused-level` freeze is gone (`budget-refused?` deleted): every queued refinement is
  tried in level order and admitted when it fits, so a refusal at one level no longer strands cheaper work.
- **BEHAVIOR CHANGE** `fill-strings`: after selection, the budget left is spent on cut strings, shallowest and
  earliest first, each grown to the end of its last whole line that fits (`fill-end`), with the cut's dig form
  continuing exactly there.
- **BEHAVIOR CHANGE** `config/default.edn`: `:seon.config.render.agent/token-budget` 1,000 -> 1,500. Share of
  default's 5,585 function sources whose literal fits whole: 95.1% at 1,000, 96.9% at 1,250, 97.7% at 1,500, 98.0% at
  1,600, 98.8% at 2,000. 1,500 keeps a cut for the largest ~2% (over ~90 lines), each with its dig form.

## After (scratch SCI copy of the committed source, same values, budget still 1,000 there)

| value | before | after |
|---|---|---|
| 32 name/number pairs | 848 tokens, 31 cuts | 642 tokens, 0 cuts, every row whole |
| 30 identifiers nested 3 deep | 460, 0 cuts | 460, 0 cuts |
| 9 finding maps with paths | 402, 0 cuts | 421, 0 cuts |
| `my.program/source` of `seon.agent.loop/task-context` (map: source, doc, contract) | 993, 1 cut | 1,221, 0 cuts (see note) |
| 30-line source | 454, 0 cuts, one escaped line | 445, 0 cuts, as lines |
| 60-line source | 661, cut at 2,048 chars, 1,166 left | 990, cut after a whole line, 73 chars left (`"…+73 (subs result/x1 3141)`); whole at 1,500 |
| 100-line source | 662, 1 cut | 986, 1 cut at a line end |
| 367-line monster | 660, 1 cut | 997, 1 cut at a line end |
| `(vec (range 1e6))` | 36, 1 cut | 36, 1 cut |
| `seon.health/report` (core budget) | 879, 27 cuts | 882, 29 cuts (breadth first; budget spent) |

Note: the `my.program/source` map printed past 1,000 tokens in the scratch copy because the scratch namespace's
armed contracts differ from the installed ones (its `held-order` contract could not arm in SCI and was redefined
unarmed); the throwaway-JVM tests below assert the charge.

Text, before and after:

```
;; a docstring-bearing map
{:sym my.ns/f, :doc "Return the thing.\n\n  ## Examples\n  (f 1) ;=> 2"}
{:sym my.ns/f, :doc "Return the thing.

  ## Examples
  (f 1) ;=> 2"}

;; the tail of the 32 pairs
 ["seon.profile/some-function-name-"…+21 (subs (get-in result/x1 [29 0]) 32)
  …1 (drop 1 (get-in result/x1 [29]))]
 …2 (drop 30 result/x1)]
;; after
 ["seon.profile/some-function-name-30-with-a-longer-tail" 30000]
 ["seon.profile/some-function-name-31-with-a-longer-tail" 31000]]

;; the head and tail of a 60-line source at 1,000 tokens
"(defn- normalized-index-entity\n  \"Compare stored refs by identity and … \"…+1166 (subs result/x1 2048)
;; after
"(defn- normalized-index-entity
  \"Compare stored refs by identity and anonymous components by their values;
…
                :else value))]
"…+73 (subs result/x1 3141)
```

## Cost

Interpreted in SCI (both versions loaded as scratch namespaces, 3 runs each, warm): pairs 18 -> 11 ms, findings
17 -> 15, health 21 -> 25, nested ids 10 -> 7, sources 2-4 -> 2-8 ms (the fill walks the shown characters once).
Compiled timings need the installed change: **after root's restart**, re-run the probe above with
`seon.render.value/prepare` in JVM mode. Work is proportional to what is shown: `fill-end` walks at most the
remaining budget's characters, and the unfrozen queue holds only refinements of admitted nodes.

## Tests (printed text only in the value printer's own tests)

- `seon.render.value-test/ordinary-values-print-whole-at-the-agent-budget` (table: pairs, nested identifiers,
  finding maps, a forty-line source; no cut, and the text reads back as the value).
- `seon.render.value-test/a-long-string-fills-the-budget-and-is-cut-at-a-line-end`.
- `strings-are-quoted-and-their-cut-path-is-associative` and `caller-print-bindings-do-not-change-string-bytes`
  now assert the literal reads back and keeps its line break.

These prove `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk` and the string half of
`seon.render.value/short-strings-in-pairs-are-cut`. Not done: that task's `java.util.HashMap$Node` printing as
`#object` (a `Map.Entry` should print as its pair), and `seon.render.value/an-uncut-string-node-carries-no-total`.

## Design check

- Held: the selection already had the "a cut costs less than it hides" rule (`complete-chunk`, `complete-tail`);
  applying it at admission and dropping the freeze reused it instead of adding a rule. The fill pass is the one new
  step, and it is general (any cut string, ordered by level).
- Awkward: the literal now differs from `pr-str` for strings with newlines; anything comparing printed text to
  `pr-str` (two tests here) had to change. HTML: the same literal goes into the hiccup face; browser paint was not
  observed.
- Not done: fipp measures a multi-line string as one wide token, so the line after it may lay out wider than the
  width; the charge still bounds the text.

## Findings recorded here (outside this slice)

- Evaluating a `defn` read without position metadata in SCI (`(eval (read r))` in a scratch namespace) fails with
  `seon.sci.arm/write-event refused its result at [:seon.batch/origin :line]` (`seon.batch/def-origin` requires a
  line): an output-contract refusal where the evaluation should arm a def with no origin.
- `my.fs/read` reads the running archive (`data/source/<HEAD>`), not the working tree; reading the 93 KB
  `value.clj` in 8 KB windows took 1,057 ms and 16.6k armed calls.
- Loading ~160 forms through `load-string` in an agent's SCI context: 1.7 s and 54k armed calls (`database-value?`
  10k).
- 2 MB allocated to `prepare` `[0 … 31]` (JVM, warm), independent of the value's size: the per-admission
  `settle`/`assoc-in` map churn.
- `my.program/source` repeats the docstring (inside `:source` and as `:doc`).
