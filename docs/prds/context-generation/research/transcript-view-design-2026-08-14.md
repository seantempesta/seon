---
type: research
status: complete
date: 2026-08-14
tags: [research, render, ui, context, testing, class/n1]
---

# Transcript view design — beautiful and honest

## The brief

The owner's intent, verbatim: "make the transcript view (debug view) look
beautiful but honest — syntax highlighting and spacing and clear turn numbers
WITHOUT it lying about what the agent is actually seeing."

Those two halves fight each other in exactly one place — the captured bytes —
and nowhere else. This document rules where the line falls, derives the turn
structure from facts, specifies the tokenizer, gives the Hiccup and the class
vocabulary the session-view lane implements from, and names the regression that
makes a lying view impossible to ship.

## Ground truth, read 2026-08-14

- The left pane already carries the exact stored bytes when a capture exists.
  `seon.render.web/latest-captured-prompt` selects the newest
  `:seon.context.capture/prompt` for the agent, and `debug-ai-html` puts it in
  one `[:pre …]` under `:seon.render.debug/prompt-kind :captured`
  (`src/seon/render/web.clj:515-541,576-595`). The historical half of
  `docs/seon/issues/debug-left-pane-is-not-the-exact-prompt.md` — missing
  header, `*print-namespace-maps*` drift, current-walk instead of the capture —
  is fixed for the captured case; `:prospective` and `:unavailable` remain
  separately labelled kinds and must stay labelled.
- Those bytes are provably the provider's. Attempt 4's 72,814-character
  request body parsed to one user message that was byte-for-byte capture
  `6c5dad44-…-context-536871046`, and Attempt 5's 34,955-byte prompt was
  reproduced to 3 tokens against DeepSeek's own count
  (`docs/prds/sci-execution-runtime/research/drive-1-observation-2026-08-14.md`).
  The capture is the artifact worth being honest about.
- The prompt's decomposition is already a fact. `history-contributions`
  prices "the exact retained entry segments whose concatenation is the prompt"
  and commits one row per position with `:seon.context.contribution/position`,
  `:seon.render.block/name`, a SHA-256 `:seon.context.contribution/hash` of the
  segment's exact UTF-8 bytes, and its token share
  (`src/seon/cluster/prompt.clj:151-180`; `src/seon/context.clj:136-152,209-212`;
  `resources/seon/schemas/seon.context.contribution.edn`).
- The print class vocabulary exists and is styled. `seon-print-root`,
  `-node`, `-summary`, `-content`, `-delimiter`, `-separator`, `-keyword`,
  `-string`, `-char`, `-number`, `-boolean`, `-nil`, `-symbol`, `-tag`,
  `-elision`, `-prune`, `-object`, `-projected`, plus the debug chrome
  `seon-debug`, `-grid`, `-pane`, `-pane-ai`, `-caption`, `-body`, `-unit`
  (`resources/public/css/input.css:102-195,1187-1290`).
- What is NOT derivable today: a per-turn timestamp or basis. Every history
  contribution records `:seon.render.block/name :walk` and no ref to the
  message, run, or receipt it came from (`src/seon/cluster/prompt.clj:171`).
  The capture's own `:seon.context.capture/basis-t` and id are derivable; a
  per-turn clock is not. The view shows what exists and says nothing else.

## (a) The honesty contract

**The view's text content inside the addressed verbatim element is
byte-identical to the stored capture.** Presentation is free everywhere else.

Permitted, because it adds no character inside that element:

- color, weight, background, borders, and font size;
- spacing BETWEEN turns — margin, padding, rules — never inserted whitespace;
- turn chrome (ordinal, capture id, basis, token share) rendered OUTSIDE the
  verbatim element;
- collapse affordances (`<details>`) around a turn, provided the collapsed
  content is present in the DOM and only visually hidden;
- a raw/verbatim toggle that swaps the highlighted spans for one naked
  `[:pre [:code …]]` carrying the same string.

Forbidden inside the verbatim element, without exception:

- re-wrapping, re-indenting, trimming, or normalizing whitespace;
- inserting a prompt marker, ellipsis, ordinal, gutter number, separator rule,
  or "…" of any kind;
- deleting, reordering, or de-duplicating any character — including the
  duplicated snapshots and superseded task text that made Attempt 4 ugly. The
  view's job is to make that duplication VISIBLE, not to hide it;
- pretty-printing a value, changing `{:db/id 747}` to `#:db{:id 747}`, or
  re-rendering the segment from live facts instead of showing the stored bytes.

The rule that makes this mechanically checkable: **the captured content lives
in exactly one addressed element per turn, and no chrome lives inside it.**
That element carries a stable id derived from the capture id and the position.
Today's pane violates it in one small way — the `:seon.debug-context-status`
span sits inside the same `[:section]` as the `[:pre]`
(`src/seon/render/web.clj:589-595`) — so a text extraction of the pane returns
`"captured" + prompt`. Moving the status out of the verbatim element is part of
this work.

Wrapping is presentation and stays: `white-space: pre-wrap` shows every stored
character while letting a long line fold. `pre-wrap` folds display lines
without changing text content, so it survives the falsifier; `white-space:
normal` would collapse runs of spaces in the DOM's rendered text and is banned.

## (b) Turn boundaries derive from facts

No regex, no "split on a blank line", no scanning for `=> `. The ordered
contribution rows of the capture ARE the segmentation: positions
`0..n-1`, whose segments concatenate — exactly, no join separator, per
`src/seon/cluster/prompt.clj:151-180` — to the prompt.

One fact is missing to make the split a pure derivation: the row stores the
segment's hash and tokens but not its length. **Accrete
`:seon.context.contribution/characters` (`[:int {:min 0}]`, optional) onto
`:seon.context.contribution/contribution`.** `history-contributions` already
computes the running `next-characters` and drops it; recording
`(count segment)` is a one-line addition at the one seam that has the value,
and it is accretion, not breakage — a new optional key, no existing key
changed. Then:

```clojure
;; segmentation, derived and self-falsifying — no text heuristic anywhere
(let [rows   (sort-by :seon.context.contribution/position contributions)
      starts (reductions + 0 (map :seon.context.contribution/characters rows))]
  (map (fn [row start]
         (let [text (subs prompt start (+ start (:seon.context.contribution/characters row)))]
           (when (= (seon.context/contribution-hash text)
                    (:seon.context.contribution/hash row))
             {:seon.render.transcript/ordinal (:seon.context.contribution/position row)
              :seon.render.transcript/text text})))
       rows starts))
```

The stored hash is the check, and it is the reason this is safe: a segmentation
that does not reproduce every stored hash is WRONG, and the view must then show
the capture as ONE unsegmented verbatim block with a visible note naming the
mismatch. **A failed alignment never becomes a guessed boundary** — that is the
project's recurring failure class (absence read as health) in transcript
clothing.

Until that attribute lands, the same rule holds with the segmentation
unavailable: one verbatim block, turn chrome limited to what the capture itself
asserts (id, `basis-t`, character count), and a visible statement that
per-turn structure is not yet derivable. Do not ship a heuristic splitter as a
stopgap; it would be a lie that renders beautifully.

Ordinals are `:seon.context.contribution/position` — the fact, not a counter
the view invents, so a re-render at a new basis cannot renumber a turn. Token
share per turn is `:seon.context.contribution/tokens`, already stored, and it
is the honest answer to "why is this prompt so big" that Attempt 4 needed.
A per-turn timestamp is **[TARGET]**: it needs an accretive
`:seon.context.contribution/about` ref to the message/receipt the segment came
from. Until then the header shows the capture's basis, not a fabricated
per-turn clock.

## (c) Syntax highlighting is tokenization for color only

The highlighter wraps existing characters in spans. It never emits a character
of its own, never reorders, and never elides.

- **Server-side.** One Clojure text tokenizer, in the transcript owner
  (`seon.render.transcript`), returning Hiccup. No client-side highlighter: the
  page is server-rendered and block-morphed, and a browser-side rewrite of the
  DOM's text is precisely the mechanism that could make the DOM disagree with
  the capture.
- **One character scan, no regular expression.** A regex in production code
  requires the owner's permission (AGENTS.md §2.2), and the automaton is small:
  string literals with `\` escapes, character literals (`\(` opens nothing),
  `;` comments to end of line, `( [ {` and their closers, keywords, numbers,
  `nil`/`true`/`false`, everything else a symbol. `seon.render.lint/balance`
  already implements exactly this automaton for judging; the renderer keeps its
  own, deliberately: a judge that shared the subject's scanner could not catch
  the subject's scanner bug.
- **Reuse the existing class vocabulary**, so one stylesheet dresses printed
  values and captured text alike: `seon-print-delimiter`, `-separator`,
  `-keyword`, `-string`, `-char`, `-number`, `-boolean`, `-nil`, `-symbol`,
  `-tag`. No new color tokens; the Phosphor palette at
  `resources/public/css/input.css:84-99` is the whole palette.
- **The tokenizer's own falsifier:** for every input `text`,
  `(= text (seon.render.lint/text-content (highlight text)))`. That is a
  property test over generated Clojure-ish strings AND over every stored
  capture the fixture can reach.
- **Prose is not Clojure.** A captured history entry is a REPL transcript:
  prompt line, form, printed value, sometimes prose. The tokenizer classifies
  what it can and leaves everything else in an unclassed span; it must never
  refuse or drop a byte it cannot classify. `seon-print-symbol` (cream, the
  body color) is the neutral fallback, so unclassified text reads as ordinary
  text rather than as an error.

## (d) The falsifier: the view cannot lie and pass

One regression, owned by the session-view lane, in
`test/seon/render/web_test.clj` (or the transcript test namespace it belongs
to):

```clojure
(deftest debug-left-pane-is-byte-identical-to-the-stored-capture
  ;; seed one run and one :seon.context.capture/prompt with awkward bytes:
  ;; doubled spaces, a trailing newline, a "quoted ( paren", a tab, and a
  ;; segment that is prose rather than Clojure
  (let [rendered (debug-ai-hiccup {:seon.db/db db :seon.cluster.agent/id "scout"})
        verbatim (lint/element-with-id
                  {:seon.render.lint/hiccup rendered
                   :seon.render.lint/id (capture-element-id capture-id)})]
    (is (= prompt (lint/text-content verbatim)))
    (is (empty? (:seon.render.lint/findings
                 (lint/check {:seon.render.lint/hiccup rendered
                              :seon.render.lint/required-regions
                              #{(capture-element-id capture-id)}}))))))
```

Three properties make it a real gate:

1. `seon.render.lint/text-content` mirrors the serializer's node semantics
   exactly, including void-element elision, so it measures what a reader sees.
2. `element-with-id` REFUSES when the addressed element is absent, so a view
   that stops rendering the capture fails loudly instead of comparing an empty
   string against nothing.
3. The `check` call in the same assertion catches the ugly-but-truthful
   failures — a placeholder where a turn should be, a fence cut mid-form, a
   duplicated block, an empty required region.

Add the tokenizer property (`text = text-content ∘ highlight`) and one
segmentation regression asserting that a capture whose contribution hashes do
not align renders ONE block plus the mismatch note, never guessed boundaries.

## The Hiccup, one turn

Chrome outside, bytes inside, one addressed element:

```clojure
[:details {:id "surface-capture-6c5dad44-536871046-17"   ; stable, derived
           :class "seon-turn"
           :data-capture "6c5dad44-…-context-536871046"
           :data-position "17"
           :open true}
 [:summary {:class "seon-turn-header"}
  [:span {:class "seon-turn-ordinal"} "17"]              ; the stored position
  [:span {:class "seon-turn-block"} ":walk"]             ; :seon.render.block/name
  [:span {:class "seon-turn-tokens"} "1,204 tokens"]     ; the stored share
  [:span {:class "seon-turn-basis"} "t=536871046"]]      ; the capture's basis
 [:pre {:id "surface-capture-6c5dad44-536871046-17-text" ; THE verbatim element
        :class "seon-turn-body seon-print-root"}
  [:code {:class "seon-turn-source"}
   ;; spans wrap the existing characters; concatenation is the segment
   [:span {:class "seon-print-symbol"} "my.agents.drive-one-agent-attempt-5"]
   [:span {:class "seon-print-separator"} "=> "]
   [:span {:class "seon-print-delimiter"} "("]
   [:span {:class "seon-print-symbol"} "db/pull"]
   [:span {:class "seon-print-separator"} " "]
   [:span {:class "seon-print-symbol"} "db"]
   [:span {:class "seon-print-separator"} " "]
   [:span {:class "seon-print-keyword"} ":seon.cluster/name"]
   [:span {:class "seon-print-delimiter"} ")"]
   "\n"
   [:span {:class "seon-print-keyword"} ":seon.cluster/name"]
   [:span {:class "seon-print-separator"} " "]
   [:span {:class "seon-print-string"} "\"default\""]
   "\n"]]]
```

The pane that holds them, with the status moved OUT of the verbatim element and
the raw toggle as a Datastar signal — no round trip, no second render path:

```clojure
[:section {:id "debug-ai-drive-one-agent-attempt-5"
           :class "seon-debug-body seon-debug-body-ai"
           :data-signals__ifmissing "{verbatim:false}"}
 [:header {:class "seon-capture-header"}
  [:span {:class "seon-debug-context-status"} "captured"]   ; :captured/:prospective/:unavailable
  [:span {:class "seon-capture-id"} "6c5dad44-…-536871046"]
  [:span {:class "seon-capture-size"} "34,955 characters · 40 entries"]
  [:label {:class "seon-capture-toggle"}
   [:input {:type "checkbox" :data-bind "verbatim"}] "raw"]]
 [:div {:class "seon-capture-turns" :data-show "!$verbatim"}
  turn-0 turn-1 turn-2]                                     ; the elements above
 [:pre {:id "surface-capture-6c5dad44-536871046-raw"
        :class "seon-capture-raw" :data-show "$verbatim"}
  [:code prompt]]]                                          ; the naked bytes
```

Both branches are in the DOM and both are byte-honest, so the falsifier checks
either address. `data-show` toggles visibility only.

## CSS class vocabulary

New, to add beside the existing debug block in
`resources/public/css/input.css`:

| class | role |
|---|---|
| `seon-capture-header` | the pane's chrome row; flex, baseline, `gap: 0.6rem` |
| `seon-capture-id` | capture identity; `text-2xs`, `--color-text-400` |
| `seon-capture-size` | characters and entry count; `text-2xs`, `--color-text-500` |
| `seon-capture-toggle` | the raw/highlighted checkbox label |
| `seon-capture-turns` | the turn column; `display: flex; flex-direction: column; gap: 0.75rem` — the ONLY between-turn spacing |
| `seon-capture-raw` | the naked-bytes branch; `white-space: pre-wrap` |
| `seon-turn` | one turn container; left border `--color-base-700`, no padding inside the body |
| `seon-turn-header` | the `summary` row; `--color-text-400`, `text-2xs`, `cursor: pointer` |
| `seon-turn-ordinal` | the stored position; tabular, `--color-signal` |
| `seon-turn-block` | the contribution's block name; `--color-text-500` |
| `seon-turn-tokens` | the stored token share; `--color-text-500` |
| `seon-turn-basis` | the capture's basis; `--color-text-600` |
| `seon-turn-body` | THE verbatim element; `white-space: pre-wrap; overflow-wrap: anywhere; tab-size: 8` |
| `seon-turn-source` | the `code` inside it; inherits the mono font |
| `seon-capture-unaligned` | the visible note when contribution hashes do not align |

Reused unchanged: `seon-debug`, `seon-debug-grid`, `seon-debug-pane`,
`seon-debug-pane-ai`, `seon-debug-caption`, `seon-debug-body`,
`seon-debug-body-ai`, `seon-debug-context-status`, and the whole
`seon-print-*` token palette.

`tab-size: 8` is presentation of a character that IS present; it changes no
text content. No `text-transform`, no `::before`/`::after` content, and no
`content:` property anywhere inside `seon-turn-body` — generated content is
invisible to a DOM text comparison and is therefore the one CSS feature that
could make the page lie past the falsifier. **Ban `content:` inside the
verbatim element** and state it in the stylesheet comment.

## What the session-view lane implements

1. Move the status span out of the verbatim element; give the capture element a
   derived stable id.
2. Add `seon.render.transcript/highlight` — one scan, no regex, spans over the
   existing characters — plus its `text = text-content ∘ highlight` property.
3. Accrete `:seon.context.contribution/characters` and record it in
   `history-contributions`; derive segmentation with the stored hash as the
   check; unaligned means one block plus a visible note.
4. Render turn chrome from the stored position, block name, and token share;
   no invented ordinals, no fabricated timestamps.
5. Add the raw toggle as a Datastar signal over two in-DOM branches.
6. Land the byte-identity regression above, and add
   `seon.render.lint/check` to the debug page's own test so a placeholder or a
   truncated fence in the debug surface fails a gate rather than a screenshot.

## Live evidence for the judge, 2026-08-14

The lint tool was proven against the preserved drive specimen: isolated root
`tmp/drive-1-root`, cluster `default`, PID 69568, web `http://127.0.0.1:55156`,
read-only. The root namespace page's Hiccup was extracted by calling
`seon.render.walk/neighborhood` at distance 2 for agent `root` — the exact
request `seon.render.web/page-result` builds — and each unit's
`:seon.render/output` collected. No message, transaction, door evaluation, or
lifecycle command was issued.

```clojure
(lint/check {:seon.render.lint/hiccup page
             :seon.render.lint/required-regions #{"surface-transcript"}})
```

```text
units: 133
counts: #:seon.render.lint{:renderer-unavailable 67, :empty-region 1}
nodes: 801 characters: 58520
floors: #:seon.render.lint{:duplicate-node-floor 16, :soup-character-floor 240}
findings: 68

:seon.render.lint/empty-region -> 1
   #:seon.render.lint{:defect :seon.render.lint/empty-region, :path [],
                      :detail #:seon.render.lint{:region "surface-transcript",
                                                 :region-absent true}}

:seon.render.lint/renderer-unavailable -> 67
   #:seon.render.lint{:defect :seon.render.lint/renderer-unavailable, :path [3],
                      :detail #:seon.render.lint{:tag "div",
                                                 :classes #{"seon-render-unavailable"},
                                                 :excerpt "renderer unavailable"}}
```

67 is exactly the placeholder count in the served page
(`curl http://127.0.0.1:55156/ | grep -c seon-render-unavailable` → 67), so the
data judge and the byte surface agree. The 68th finding is the loud absence:
the required region was never rendered at all.

## Related

- `docs/seon/issues/debug-left-pane-is-not-the-exact-prompt.md` — the honesty
  defect this design closes.
- `docs/seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md` —
  entries are source followed by value; the highlighter must not reintroduce a
  comment frame or annotation as presentation.
- `docs/seon/issues/namespace-page-repeats-renderer-unavailable.md` — the
  placeholder class the lint tool now counts.
- `src/seon/render/lint.clj` — the browser-free judge and the falsifier's
  extractor.
