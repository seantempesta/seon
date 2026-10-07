---
title: Printer long text on both faces, design
type: research
status: proposed v2 (gate 1, design only; revised for the Opus and Astra reviews and root's rulings 1-8)
date: 2026-10-07
lane: printer-text
---

# Printer: long text on both faces (lane printer-text, design only)

One line: the value printer (`seon.render.value` selects, `seon.print` emits one fipp op stream as text and hiccup)
already selects ordinary long strings well: it fills to a line end, gives `subs` dig forms, and links in HTML when the
unit carries a route base. Five things around it are broken:

- the HTML face emits a string as its escaped literal;
- a view of an unstored value supplies no route base or basis;
- the reply composer prints rendered text again with `pr-str`;
- a declared render's text is cut inside a leaf, mid-line, with no dig form and no count;
- the diagnostic beside a failed render is a second printer.

Each fix is at its owner. The selection changes in one place only: the render-text cut joins the ordinary string cut
(§4).

Evidence: default `ec5f13d2f`, then `e80131ef1` after root's restart at 12:18 UTC. Both were `:faulted` with open
faults that are all tasked. Probes ran as SCI agent `printer-text` in scratch namespace `scratch.printer-text`. No
platform code was loaded, and prototypes are pure functions over `prepare`'s real output. JVM mode was used for one
read-only census and for task creation. Reviews: [Opus](review-printer-opus-notes.md) and
[Astra](review-printer-codex-notes.md). Every finding of theirs is taken below, by section.

Path step served: PRINT, the edge of the one path, plus the human view of the agent's context. No owner is added. The
slice reuses the op stream's hiccup override, the existing cursor and route machinery, `text-response`, and the
value printer's core. It deletes `seon.repl/data-text`, `one-form?`, `seon.render.value/diagnostic-line`, the leaf
`kept` cut in `make-node`, and `ends-in-comment?`'s use of the lexer.

## 1. What exists and what is broken

| Step | Where | What it does |
|---|---|---|
| select | `seon.render.value/select-nodes`, `make-node`, `string-shown`, `fill-strings`/`fill-end` | a long string shows a head grown into the budget (agent profile 1,500 tokens = 4,800 chars); `fill-end` backs off to the last line end in the growth, else the character end (forward progress) |
| declared text | `make-node` (value.clj:1034-1049) | a render's AI text over the ceiling is shortened inside a `:leaf` (`kept`): mid-line, no continuation; `more?` is false for leaves, so `:truncated?`, `:elisions` and `omitted-characters` report no cut |
| cut | `cut-node` | elision with prefix, omitted, next offset, continuation (only with a `result/…` root), `::print/href` (only with `:seon.render.value/route-base`) |
| emit | `seon.print/leaf-ops` | `::string` → `string-literal` (escapes `"` and `\`, keeps newlines); a character cut → literal prefix + `…+N` + form; hiccup = the same text in a span |
| page | web.clj `/data` (HEAD :3850, s2a working tree :3473-3501) | the only route-base and cursor supplier; stored blobs and entities only |
| reply | `seon.repl/response-entries` → `data-text` (repl.clj:178) | `pr-str`s a rendered value's shown text, a failure's text and diagnostics unless the text reads as one form without a comment; `reply-map` lexes every entry (`ends-in-comment?`) |

Measured on root's assembled context (`seon.cluster.prompt/prompt`, 155,809 chars):

| Face | ms | Output | Defect |
|---|---|---|---|
| AI, root `'result/x1` | 3.8 | 4,750 chars, 1,484 tokens, ends `"…+151151 (subs result/x1 4658)` | `\"` escapes only |
| HTML, root `[:seon.agent/id "root"]` | 0.8 | 5,264 chars of HTML ending `&quot;…+151083</span>` | 66 backslashes; the whole literal is one `seon-print-elision` span; no link, form or reason |
| HTML of the composed map (s2a's view value) | 6.8 | 25,117 chars, 26 cuts, 0 links | every cut dead |
| HTML with route base + cursor `{path [:seon.cluster.prompt/text] offset 4726}` | 2.9 | 5,247 chars from char 4,726, a next link `offset=9356` | works today |

Astra's compiled measurement (platform thread, exact): `prepare` of 14 K, 140 K and 1.4 M-character strings costs
1.6-2.1 ms, 671 KB and 293 armed calls each, constant in input size. These are snapshot and prototype numbers; the
installed after-numbers are re-run with the same probe (Astra's §6 form) after the slice lands.

## 2. HTML: strings show their text, and every cut continues

### 2.1 String hiccup (BEHAVIOR CHANGE, `seon.print/leaf-ops`)

Each string-bearing op gets a hiccup override via the `text-op` third arity, which `::projected` and `::elided`
already use. Text and hiccup stay one op stream with the same token boundaries:

- `::string`: the text is unchanged (`(or (::text node) (string-literal value))`). The hiccup is
  `[:span {:class "seon-print-string"} s]`, where `s` is the node's own value: `::text` when present, so both faces
  come from one source.
- `::truncated-string`: hiccup `[:span {:class "seon-print-string"} (str value "…")]`.
- `::elided` with `:characters`: hiccup `[:span [:span {:class "seon-print-string"} prefix] marker]`. The marker is
  the `…+N` link when `::href`. Otherwise it is `[:span {:class "seon-print-elision" :title t} "…+N"]`, where `t` is
  the continuation form or the `::requery-refusal`, so a dead cut says why.

CSS lives in `resources/public/css/input.css` and the built `output.css`, both claimed by s2a-code. `.seon-print-string`
gets `white-space: pre-wrap` and `::before/::after { content: '"' }`. Hiccup already escapes the HTML-special
characters (hiccup.clj:227-257).

The comment at print.cljc:396-399 ("cannot disagree about a token") is revised in the same commit. The faces share
tokens, not spelling.

Prototype on the real context: 5,249 chars of HTML. Backslashes drop from 66 to 4, and the 4 are the text's own. It
shows 1,476 tokens and ends at a line end.

### 2.2 The whole string as text/plain (ruling 2)

`seon.print/full` cannot mark a string: the review measured `(identical? "abc" (seon.print/full "abc"))` → true.
Printing a whole 143 K string through the printer costs 48 ms and 8.9 MB in HTML and 128 ms and 8.3 MB as text. So
paging stays, and a character cut also offers a second link: "whole text". It points to the same route with `&text=1`
and answers `text-response 200` with the string at the cursor's path, as `text/plain; charset=utf-8`. No printing
step runs: the string is its own text.

Cost on the 155,809-char context, from SCI:

| Step | ms | MB |
|---|---|---|
| re-derive the context, warm | 4.6 | 10.5 |
| re-derive the context, cold (after any write) | 577-590 | 788-897 (process-wide, upper bound) |
| encode the string as UTF-8 (156,035 bytes) | 0.52 | 0.67 |

The serving step is 0.5 ms and 0.67 MB, proportional to the bytes sent. The cold derivation is over the owner's
1 s / 10 MB line on allocation. That cost belongs to `seon.cluster.prompt/prompt` (s2a measured the same; S4 replaces
the assembly). It is paid by any click on this view, and is not added by the link.

Only a cut whose path reaches a string gets the link. A non-string value at that path answers the existing 422
refusal. `seon.render.data/at` must resolve the cursor first: the Opus review filed `8f8ea88a5bea`, because an
unresolved cursor prints the root as `{}`.

### 2.3 Pages carry their basis (ruling 3)

A view of a re-derived value adds `basis=<t>` to every page link (`path-url` adds it when the unit carries
`:seon.render.value/basis`). The handler derives at `(seon.db/as-of (seon.db/db conn) t)` whenever the link has a
basis, the first page included, so every page of one sequence comes from the same view.

Measured: two as-of derivations at the same t gave identical text. A head value at t and an as-of view at t
did **not**. The 155,809- and 155,808-char texts first differed at char 25,186, where two `[sym calls ms]` rows with
equal counts (`seon.profile/accumulator? 2712` and `seon.profile/histogram? 2712`) swapped. The tie comes from
`seon.repl/response-entries`' `:seon.repl/repeated` ordering, `(sort-by (comp - second) repeated)`, which breaks ties
by the stored collection's iteration order.

Fix at the producer (repl.clj, this slice): `(sort-by (juxt (comp - second) first) repeated)`. With that change a
head-derived first page and as-of later pages agree. Deriving every page as-of is kept as well, because it does not
depend on every renderer being deterministic.

Cost: as-of derivation is cold every click: 680-784 ms and 1.06-1.17 GB process-wide. That is the same producer cost
as §2.2, and it is why the whole-text link is the recommended way to read all of it.

### 2.4 The view change (ruling 6: a patch to s2a-code, or after it)

`sent-context-html` (web.clj, owned by s2a-code) passes four things to `value/html`:

- `:seon.render.data/cursor` from the request's `path`/`offset`, carried from the existing handler's query (its
  current signature does not take it);
- `:seon.render.value/route-base` from `(route/path ::route/agent-debug {:id agent-id} {:sent "true"})`, a route that
  exists at route.clj:15-16;
- `:seon.render.value/basis`, the request's `basis` or the head's `basis-t`;
- the value resolved with `seon.render.data/at`, using the as-of database when `basis` is given.

It also handles `text=1` with `text-response`. That is about eight lines. They go to s2a-code as a patch after the
printer slice lands. The printer side, `path-url` carrying `basis` and `text`, is in this slice.

## 3. `diagnostic-line` deleted, terminal fallback acyclic (ruling 5)

`diagnostic-line` (value.clj:996) prints `";; renderer failed at " (pr-str path) …(bounded-text failure)`, which makes
it a second printer. After the change:

**The failure keeps its path as data.** `prepare` returns `:seon.render.value/diagnostics` as entries
`{:seon.render.data/path p :seon.render.value/failure f}`. The failure is the Throwable or error map as today. This
is a new registered shape, `:seon.render.value/diagnostic`, in `seon.render.value.edn`.

The first consumer is `seon.sci.eval/shown` (eval.clj:3566 `into failures`), owned by s5a-reply. It must read
`:seon.render.value/failure`, a one-word change handed over as a patch. Alternatively, `:diagnostics` keeps its
vector of failures and a sibling `:seon.render.value/failure-paths` is added; root picks.

**Callers and what each becomes.**

| Caller | After |
|---|---|
| `prepare`, a failed render beside a value (:1582) | text = value text + `\n` + `d`, where `d = (core-text {:seon.render.value/diagnostics entries})`, computed once; HTML `[:div {:class "seon-render-unavailable"} [:pre {:class "seon-data-panel seon-core-value"} d]]`, which keeps the class `seon.render.lint` counts (render.clj:1115-1119) |
| `prepare`'s catch, a successful structural retry (:1626-1641) | the same `d` appended to text, and the same wrapped `d` appended to the projection's HTML (today it updates only text and diagnostics) |
| `plain-projection` (:1486), the terminal | `(bounded-text value 8192)` + `\n` + `(bounded-text entries)`, both projection-free; HTML the same text in the wrapped div |
| `unavailable-line` (:271), beside `text`/`html` | `d` of `{:seon.render.value/diagnostics [{:seon.render.value/failure refused}]}` through `core-text`; HTML in the same wrapped div |

**The one terminal is `seon.render.value/bounded-text`.** It calls only `datum` (clojure.core data, no projection)
and `pr-str` under fixed bindings, and never reaches `prepare`. The call graph after the change:

```
text/html → render-ai → prepare(non-structural) → core-text → render-ai → prepare(structural)
prepare(structural) → catch → plain-projection → bounded-text      (terminal; calls nothing in seon.render)
core-text → catch → bounded-text / throwable-text / opaque          (terminal)
```

It is acyclic for three reasons:

1. `plain-projection` no longer reaches `core-text`, so Astra's P1 cycle disappears.
2. `prepare` with `structural?` runs no declared render, so it never produces a render failure and never calls
   `core-text` for one.
3. `core-text` always calls `prepare` structurally (value.clj:261).

So `core-text` nests at most once below a non-structural `prepare`.

Proof test: `seon.render.value-test/a-terminal-structural-failure-does-not-reenter-selection`. It feeds a value
whose realization always throws (a lazy seq) through `text`, and asserts that the result is a string with one
diagnostic entry, which shows the call depth is bounded. No counter and no dynamic Var are added.

Tests:

- `a-failing-renderer-never-replaces-the-value` (value_test.clj:947-970) becomes a data assertion: one diagnostic
  entry whose `:seon.render.data/path` is `[]`, plus the value's entries in the text.
- `seon.sci.admit-test` (admit_test.clj:162) calls `plain-projection`; its assertion on the text's head stays.
- `floor_test` is unchanged.

As Astra notes, a Throwable nested in the diagnostic map prints through `classify`/`throwable-value` as
`#error {:via …}`, not as `core-text`'s root chain. That matches today's line, which carries no frames either.

## 4. Declared-render text is an ordinary string cut (ruling 4)

`make-node` stops shortening declared AI text inside a leaf. Text a declared render returns, and a value's own
`:seon.render/ai` string, become a `:kind :string` node with `:raw? true`. That one node state drives the charge,
`more?`, the fill, `cut-node`, `omitted-characters`, `:truncated?`, `:elisions` and both faces:

- **Width:** a raw node's `:shown-width` counts characters, not escaped widths: `escaped-width` takes the flag. Its
  `flat-width` adds no quotes.
- **Growth:** the same chunks, then `fill-strings` to the last line end. `fill-string` already shrinks room by what the
  regrown cut costs, so the whole continuation is reserved before the line end is chosen. A first line longer than
  the budget falls back to the character end, which keeps forward progress.
- **Emission:** a whole raw node is `::print/projected` as today, raw at the root. A cut raw node is an `::elided`
  carrying `::print/raw? true`. `elided-text` prints its prefix raw, then a newline, then `…+N`, then the form. HTML
  uses §2.1's hiccup.
- **Dig form:** `(seon.print/full result/e…)` for an IObj root; `prepare` already lifts the ceiling for it
  (value.clj:1557-1563). When the root is not an IObj (a Var's render) or has no handle, there is no dig form and the
  cut carries the requery refusal.
- **Removed:** `kept`, `:ceiling` and their comment.

Rows to test, as one table in `a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form`:

- a value's own `:seon.render/ai` string over the budget;
- a declared renderer's text over the budget;
- a first line longer than the budget;
- CRLF line ends;
- `"` and `\` inside the text;
- HTML-special characters;
- `:seon.render/ai` holding a symbol (renders through its producer, no `subs`) versus holding a string.

Each row asserts the data: `:truncated?`, `:elisions`, the `omitted-characters` contribution, the continuation form,
and `prepare`'s value tokens greater than its shown tokens.

Root-only render text, checked nested (root's earlier question), from the first version:

- `{:sym 'my/f :source src :seon.render/ai src}` prints raw at the root, with comments and quotes as written.
- Nested in a vector, the map prints as data, and the source appears twice as escaped literals.

S1 v3 already returns `{sym, :seon.render/ai text}` with no separate `:source` key, so nested it prints once, as a
literal with its lines.

## 5. The reply composer (ruling 1, ruling 7, the `(help)` cause)

**Cause of `(help)` and the namespace pull arriving quoted** (task `61e6f8134119`): `seon.repl/data-text`, not a
re-read.

| Evaluation | `:seon.eval/renderer` | stored `:seon.eval/shown` | reply |
|---|---|---|---|
| `(help)` | `seon.bootstrap/render-help-ai` | raw prose | one quoted line with `\n` escapes |
| `(:seon.agent/namespace (pull …))` | `seon.render.ns/render-ai` | raw map, 4,594 chars | one quoted line; the shown text does not read ("The map literal starting with …1 contains 1 form(s)") |
| `{… :seon.render/ai src}` | nil | raw | raw (`data-text` runs only for a declared renderer) |

**Change (BEHAVIOR CHANGE, repl.clj):**

- `:value`: the shown text is spliced verbatim, as for unrendered values.
- `:error`: when the evaluation has shown text (the printer's output of its error value), it is spliced verbatim.
  Otherwise the prose `:seon.cluster.eval/error` string (declared `:string`) is quoted with the printer's own
  `seon.print/string-literal`, which this slice makes public. That keeps line breaks and escapes only `"` and `\`.
- `:diagnostics`: always the printer's text (`floor-text` of diagnostics, eval.clj:3593), spliced verbatim.
- Delete `data-text` and `one-form?`. `read-shown` and `shown-value` stay: turn refresh and `repl/text` use them, and
  walk-delete/S4 retire them.

The discriminator is where the text came from (the shown text or the declared error string), never parsing.

Producers that put prose where an error map belongs, each to emit an error map (the `:error` quoting goes when they
do):

- `seon.turn.batch` batch.clj:624: `(str "facts not proposed: " message)`;
- `seon.turn` turn.clj:3281-3285, the refused-facts receipt: `"Not admitted to the program: …"`;
- `seon.sci.eval/failure-text` eval.clj:3403, used at :3668 and :3744: an evaluation's failure as a declared string.

**Task `seon.repl/reply-map-is-not-edn-when-elided-or-rendered`** (resources/seon/tasks/seon.repl.edn:1) is rewritten
in the same commit. The new intent: the reply's keys stay data (`:result`, `:ms`, `:calls`, `:findings` and a prose
`:error` read back), and `:value` is the value printer's text, which is EDN except where the printer cuts inside a
collection (`{…1}`, `"…"…+N`) or a render returns prose. Its proof becomes the converted deftest below. Task
`9245d42cfe6d` (`the-reply-reprints-a-renders-shown-text-as-a-string`) closes with the commit.

**The three red assertions, converted to the new rule:**

| Test | Today | After |
|---|---|---|
| repl_test.clj:375, `the-reply-is-one-edn-map-whatever-the-value`, prose render | `(:seon.repl/value (one-map …))` is the prose as one string | the response holds the shown text unchanged as the `:value` entry's text; the `:result` handle and `:ms 12` entries after it read as data (asserted on the entries `response-entries` returns, not by reading the whole reply) |
| repl_test.clj:241-244, prose saved as shown text | `:error` reads back as the prose string | a failure without shown text: `:error` reads back as the prose string, through `string-literal`, line break kept. A failure with shown text: that text is spliced unchanged |
| returned_error_test.clj:62-66 | `:seon.repl/error` read back equals the saved shown prose | the response's `:error` entry is the saved shown text unchanged (`response-entries`), and its `:result` stays readable |

The EDN-render row (repl_test.clj:379-389) is unchanged and green. S1 deletes it after this slice.

**Ruling 7, folded in.** `ends-in-comment?` lexes every entry. The review measured 73.7 ms and 96 MB on 108,891
chars, and it runs twice for the last entry (task `fb9d415bff49`). Since a line comment runs to its line end, a text
ends in a comment only if its last line contains `;`. The replacement:

```clojure
(defn- ends-in-comment?
  "True when `text`'s last line could hold a line comment, so the reply must end that line before the next key."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String text]
  (not= -1 (.indexOf text ";" (inc (.lastIndexOf text "\n")))))
```

A `;` inside a string on the last line gives a false positive, which only costs an extra newline, never a swallowed
key. Measured in SCI, interpreted, on 108,891 chars: 7.8 µs per call, with 0.58 MB process-wide over 1,000 calls.
The test case `a comment-ended text still leaves the map readable` covers it, plus a row with `;` inside a final
string. `syntax-tokens` stays for colouring. Task `fb9d415bff49` closes with the commit.

**Ruling 8, the help warning** (`2d24c2ed98f1`): not in this slice's files. `:large-value` fires on
`:seon.eval/value-tokens` > 1000 (resources/seon/schemas/seon.config.eval.edn:14-19), and `(help)`'s text is 1,105
tokens of its own prose. The fix belongs to the bound (judge only cut values: value tokens above shown tokens) or to
S1's shorter help. Left on its task.

Remaining `pr-str`s in the reply owner (`:findings`, `:interrupted`, `:out`, `:note`, repl.clj:410, `source-text`'s
`pprint`) and in value.clj (node-id's `root-description` :466, breadcrumbs :1483, `render-database-identity-ai`) stay
on `seon.print.value/printed-copies-stored-or-sent-instead-of-values`. `path-url`'s EDN path encoding is transport
(root ruled Q4).

## 6. Costs

| Probe | Before | After |
|---|---|---|
| HTML of the context string | 0.8 ms, 5,264 chars, 66 `\`, no continuation | ~5,249 chars, 4 `\`, next-page and whole-text links |
| HTML of the composed map | 6.8 ms, 25 KB, 26 dead cuts | same selection, 26 live cuts |
| next page (as-of basis) | not reachable | 680-784 ms, ~1.1 GB process-wide (the derivation's; the print is 2.9 ms) |
| whole text, text/plain | not reachable | 0.5 ms and 0.67 MB to serve + derivation (4.6 ms warm, ~0.6 s cold) |
| reply of a rendered value, 3 KB | 1.9 ms, 2.8 MB (Opus, JVM) | `data-text` gone, about the unrendered 1.0 ms / 1.3 MB, less the lexer |
| `ends-in-comment?`, 109 KB entry | 73.7 ms, 96 MB | ~8 µs, no allocation that grows with the text |
| render text over budget (4,950 chars) | cut mid-line, no dig form, value tokens = shown tokens 1,499 | cut at a line end, `(seon.print/full result/…)`, value tokens about 1,550 |
| ordinary string `prepare` | 1.6-2.1 ms, 671 KB, 293 calls (Astra) | the same; re-run the same probe after landing |

Census of 5,641 stored sources: escaping costs 0.7% of their tokens, so escapes are a fidelity problem, not a size
problem.

## 7. Tasks

- Created: `seon.render.value/render-text-past-the-budget-is-cut-mid-line-with-no-dig-form` (`5d42a25f9a53`, §4) and
  `seon.repl/the-reply-reprints-a-renders-shown-text-as-a-string` (`9245d42cfe6d`, §5).
- Filed by the Opus review and taken here: `fb9d415bff49` (§5). Linked: `8f8ea88a5bea` (§2.2) and `2d24c2ed98f1`
  (§5, not here).
- Linked: `seon.print.value/printed-copies-stored-or-sent-instead-of-values`, `61e6f8134119`, and
  `seon.repl/reply-map-is-not-edn-when-elided-or-rendered` (rewritten in §5).
- Seen, not yet tasked:
  - `seon.cluster.prompt/prompt`'s cold cost (≈0.6 s, ≈0.8-1.2 GB process-wide) on every click of the view, which
    is s2a/S4's;
  - a refused `seon.db/pull` reply carries 15 frames per link;
  - a contract refusal printed the offending request twice;
  - a `defn` with an anonymous `[:fn ifn?]` contract is refused as "No reader function for tag object" with no hint
    that anonymous contract functions are not allowed.

  I file the first and the last on default with this commit's landing note, if root wants them on me.
- My mistakes, not tasks: `:seon.task/status`, `seon.render.hiccup/html`, the `[:fn ifn?]` probe contract.

## 8. The code slice

Ordering (ruling 6): this slice lands before S1. S1 edits repl.clj and repl_test.clj:379-389 afterwards. web.clj
goes to s2a-code as the §2.4 patch.

Files:

- `src/seon/print.cljc`:
  - `leaf-ops` hiccup overrides (§2.1);
  - raw character cut in `elided-text` (§4);
  - `string-literal` made public;
  - the :396 comment.
- `src/seon/render/value.clj`:
  - delete `diagnostic-line`;
  - path-carrying diagnostics and the four callers (§3);
  - raw string node and cut for declared text, `kept` removed (§4);
  - `path-url` carries `basis` and `text` (§2.2-2.3).
- `resources/seon/schemas/seon.render.value.edn`: `:seon.render.value/diagnostic`, and `:seon.render.value/basis` on
  the unit. Published and adopted on a branch.
- `resources/seon/schemas/seon.print.edn`: `:seon.print/raw?` on the elided and string node kinds.
- `src/seon/repl.clj`:
  - delete `data-text` and `one-form?`;
  - `:error` quoting by source (§5);
  - `ends-in-comment?` without the lexer;
  - the `:repeated` tie-break;
  - `response`'s docstring.
- `resources/seon/tasks/seon.repl.edn`: rewrite `reply-map-is-not-edn-when-elided-or-rendered`, close `9245d42cfe6d`
  and `fb9d415bff49`.
- `resources/seon/tasks/seon.render.value.edn`: close `5d42a25f9a53`.
- Patches to others:
  - s5a-reply: eval.clj:3566 reads the diagnostic entry (§3);
  - s2a-code: web.clj view (§2.4) and CSS (§2.1).

Reaching tests (for `bin/lane commit --test`):

- `seon.print-test`:
  - `a-string-literal-keeps-its-line-breaks-and-reads-back`;
  - `one-cut-face-and-held-order`;
  - `p-total-generated-grammar-emits-and-readable-faces-round-trip`;
  - `p-tee-generated-grammar-cannot-disagree`, converted: one selection with the same tokens, and each string's
    hiccup is its original characters;
  - new: a string's hiccup, and a character cut with and without `::href`.
- `seon.render.value-test`:
  - `a-failing-renderer-never-replaces-the-value` (converted);
  - new `an-html-cut-of-a-long-string-shows-text-and-continues`: a table of with and without route base, with basis,
    and a whole-text link only on string cuts;
  - new `a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form` (the §4 table);
  - new `a-terminal-structural-failure-does-not-reenter-selection`.
- `seon.render.floor-test`: the four core tests, unchanged.
- `seon.sci.admit-test`: `plain-projection`.
- `seon.repl-test`:
  - `the-reply-is-one-edn-map-whatever-the-value` (converted);
  - the response-grammar deftest at :230-244 (converted);
  - `an-error-in-the-printers-grammar-is-a-map-not-a-string`;
  - `colouring-preserves-every-character-including-malformed-source`;
  - new `a-rendered-value-with-cuts-is-spliced-verbatim` and a `;`-in-final-string row.
- `seon.returned-error-test`: converted.
- `seon.repl-grammar-test`.

S2a owns the route-click proof. S5a owns the parity between held and saved replies. Browser paint of the CSS quotes,
and copying a selection, are observed separately.

## 9. Alternatives considered

- **A raw text block for root multi-line strings on the text face.** Dropped: S1's `:seon.render/ai` covers source,
  and §4 makes that text a real cut.
- **`seon.print/full` for strings, or a whole-value page.** Rejected by ruling 2: no mechanism for strings, and
  8-9 MB per 143 K.
- **Keeping `data-text` and swapping its `pr-str` for `string-literal`.** It still prints printer output a second
  time and keeps the rendered namespace pull as one string. Rejected by ruling 1 for `:value`.
- **Unconditional newline between reply entries (Astra).** Also correct and simpler in code, but it changes every
  reply's layout. The last-line test keeps the one-line layout at the same O(last line) cost.

## 10. Open questions for root

1. §3: should the diagnostic entries move into `:seon.render.value/diagnostics` (and s5a converts eval.clj:3566 by
   patch), or stay beside it as `:failure-paths`?
2. §2.3: is the cold as-of derivation per page click (≈0.7 s, ≈1.1 GB process-wide) acceptable until S4 replaces the
   assembly, given that the whole-text link reads everything in one derivation?

## 11. Design check

- Held: emission overrides, verbatim splicing and deleting `data-text` are smaller than what they replace.
- Wrong in v1, found by the reviews:
  - converting `plain-projection` to `core-text` would have made the terminal recursive;
  - the diagnostic lost its path and its lint class;
  - `seon.print/full` cannot reach a string;
  - the render-text cut must be a real cut node, or flags, accounting and HTML disagree;
  - deleting `data-text` reversed an open task without saying so.
- Found by this revision's probe: a derived text is not a function of its basis while ties sort by iteration order.
  The producer is fixed (repl.clj `:repeated`), and pages derive as-of.
- Untraced:
  - browser paint of the CSS;
  - compiled after-numbers (SCI reports per-thread allocation as 0; Astra's platform-thread form is the re-run
    probe);
  - whether any other renderer in the context sorts ties by iteration order. As-of paging covers that case.

No restart or reset is needed for this design. Scratch exists only in `scratch.printer-text` on agent printer-text's
branch, which is disposable.
