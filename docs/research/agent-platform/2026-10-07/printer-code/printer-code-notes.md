---
title: printer-code, the printer-text v2 slice landed
type: research
status: landing note
date: 2026-10-07
lane: printer-code
---

# printer-code: printer-text v2, implemented

Implements [design v2](../printer-text/design-notes.md) (4665fae35) with root's rulings: diagnostic entries carry
their path inside `:seon.render.value/diagnostics`, and eval.clj:3566 is converted here. Root added two items
mid-lane: task `1b420c6cb408` (session `*print-length*` ignored) and task `d9178db45287` (`tuple-node`'s requery-id
contract). Path step: PRINT, at the edge of the one path, plus the human view of the context. Owners reused:
`seon.print` op stream with `text-op` hiccup overrides, `seon.render.value` selection and cut, `seon.repl` composer.
Deleted: `seon.repl/data-text`, `one-form?`, `seon.render.value/diagnostic-line`, `unavailable-line`, the `kept`
leaf cut and `:ceiling`, and `ends-in-comment?`'s use of the lexer.

## Broken first

- **`seon.render.web-test/debug-header-identifies-the-handed-program-and-projection` turns red with this slice**
  until s2a-code applies the patch below. The HTML face now shows a string's own characters (quotes are CSS), and
  the test reads the header label back with `edn/read-string`, so the source digest reads as an invalid number.
  The patch changes that assertion to compare the label as shown. Verified red on the candidate (run 3ebb07860dca)
  and green on HEAD (run ccbfce3d176f). web_test.clj is held by s2a-code.
- Pre-existing reds, identical on HEAD 5b0a3327c and on the candidate (classified by running HEAD alone):
  - `seon.repl-grammar-test/stored-help-keeps-its-renderer-and-bare-response` (:86-92, :112), task `892cda0fa38a`.
  - `seon.sci.render-source-test` (two tests, :54-71 and :91): filed `seon.sci.eval/render-source-tests-red-on-head`
    (`a6d856c50dc5`).
- Fixed in passing, both in my files: `seon.returned-error-test` was red on HEAD at :19/:49/:52 (stale cluster setup
  without `:seon.cluster/config`, and `(= shown response)` comparing the value to the reply map); now green, its
  `println` of a `pr-str` deleted. `seon.repl-grammar-test/prompt-precedes-exact-multiline-agent-input` was red on
  a stale byte count (128 vs 136) duplicating the equality above it; the count is deleted, the test is green.
- The view change and the CSS are not landed: s2a-code holds web.clj and both CSS files. Until the patch lands,
  the context view's cuts stay dead (no route base) and the HTML string has no quotes at all (CSS adds them).

## What changed

- `seon.print`: `string-literal` is public. A string's hiccup is its own characters in `seon-print-string` (text face
  unchanged). A character cut's hiccup is `[:span [:span.string prefix] marker]`, the marker carrying the same
  `…+N form` text as the text face, a link when `::href`, a title naming the refusal when there is no continuation,
  and a `seon-print-whole-text` link when `::text-href`. A raw (render text) cut prints its prefix as written, then
  `…+N` on a line of its own. `elision` carries `::raw?`.
- `seon.render.value`:
  - render text (a declared render's AI text, or a value's own `:seon.render/ai` string) is a `:kind :string`
    node with `:raw? true`: charged by characters (no quotes, `shown-width`), grown and filled to a line end like any
    string, cut by `cut-node` with `(seon.print/full result/…)` when the root is an IObj with a result handle, else
    the requery refusal. `:truncated?`, `:elisions` and `whole-characters` now count it.
  - diagnostics are entries `{:seon.render.data/path p :seon.render.value/failure f}`; text and HTML show the core's
    text of them once (`diagnostics-shown`), HTML wrapped in `seon-render-unavailable`. The structural retry now
    updates HTML too. The terminal `plain-projection` is `bounded-text` of value and entry, projection-free.
  - `path-url` carries `basis` and, for a string cut, a whole-text `&text=1` link (`::print/text-href`).
  - `leaf-width` measures a nested render's literal with `string-literal`, not `pr-str`.
  - task `1b420c6cb408`: the selection takes the session's `:seon.print/length` / `:seon.print/level` (from the
    unit's print options) as its member and depth bounds.
  - task `d9178db45287`: `tuple-node` takes `[:maybe :seon.print/requery-id]`.
- `seon.sci.eval`: `shown-result` supplies the agent fork's print session (`::print-session`) as the unit's print
  options when the request has none; the `shown` consumer reads `:seon.render.value/failure` from each entry.
- `seon.repl`: `:value`, a shown `:error` and `:diagnostics` are spliced as shown; an unshown failure's prose
  `:seon.cluster.eval/error` is quoted with `seon.print/string-literal`. `ends-in-comment?` looks for `;` on the last
  line only. `:repeated` breaks ties by symbol.
- Schemas: `:seon.render.value/diagnostic`, `/failure`, `/basis`; `:seon.print/raw?`, `/text-href`.
- Task file: `seon.repl/reply-map-is-not-edn-when-elided-or-rendered` rewritten to the new rule.

## Patch for s2a-code

[s2a-view-and-css.patch](s2a-view-and-css.patch): `sent-context` derives the context at the query's `basis` (else
the head's basis-t) with `db/as-of`, resolves the cursor with `seon.render.data/at`, and `sent-context-html` passes
cursor, route base (`::route/agent-debug` with `sent=true`) and basis; `text=1` answers the string at the path as
`text/plain` (422 when the path is not a string). CSS: quotes as `::before/::after` on `.seon-print-string`,
`pre-wrap` on `.seon-print-projected`, `.seon-print-whole-text` coloured like a cut. output.css needs the CSS build.
The web_test assertion above is in the same patch. The view's cold re-derivation per click (about 0.7 s, about
1.1 GB process-wide) is accepted until S4, which owns it.

## Measurements

Before, on default (pid 13771, 25bb94be0, same bytes as HEAD for these files), JVM mode, armed, platform thread,
`getThreadAllocatedBytes`, three samples after one warmup. Input: root's context at basis 536871452, 186,516 chars
(saved with the composed map to the lane scratchpad).

| Probe | ms | allocated | armed calls | out chars |
|---|---|---|---|---|
| HTML of the context string | 2.3-3.0 | 484 KB | 317 | 5,264 |
| AI text of the context string | 1.4-2.7 | 900 KB | 337 | 4,750 |
| HTML of the composed map | 10.6-18.7 | 10.3 MB | 14,597 | 25,117 |
| `(help)` reply (`response`, rendered) | 0.11-0.13 | 244 KB | 20 | 3,636 |
| reply composer, 108,891-char EDN entry | 1.5-7.7 | 7.4 MB | 16 | 108,937 |
| reply composer, 108,891-char dense vector | 41-43 | 96 MB | 16 | 108,937 |
| 100-form SCI MCP pass (`seon.mcp/call`) | 443-487 warm (965 first) | 13-14 MB thread; 560 MB process-wide | | 8,621 |

After: the change cannot load into default (no reset by lanes), so parent and change are compared in plain
unarmed JVMs on the same inputs (`clojure -Sdeps` with each tree's src and resources, 20 warmups, median of 10,
platform thread); three runs each, ranges shown. Armed call counts are not available there.

| Probe | parent ms | change ms | parent alloc | change alloc |
|---|---|---|---|---|
| HTML of the context string | 0.64-1.74 | 0.98-1.69 | 384-588 KB | 425 KB |
| AI text of the context string | 1.21-1.90 | 1.40-2.17 | 755-763 KB | 791-812 KB |
| HTML of the composed map | 4.0-4.9 | 4.2-5.7 | 6.24-6.33 MB | 6.27-6.37 MB |
| `(help)` reply | 0.29-0.36 | 0.01 | 242 KB | 40 KB |
| reply composer, 109 KB EDN | 0.70-0.78 | 0.22-0.26 | 7.4 MB | 2.1 MB |
| reply composer, 109 KB dense | 35.4-36.9 | 0.19-0.22 | 95 MB | 1.0 MB |

Output sizes after: context HTML 5,281 chars (was 5,264; backslashes are now only the text's own), composed map
HTML 26,765 (was 25,117: every cut shows its continuation form, and string cuts link the whole text when a route
exists), help reply 3,609 (was 3,636: the prose spliced, not escaped). The 100-form SCI pass and the armed numbers
need default restarted on this commit: **RESTART NEEDED (root's batch)**, then re-run
[default-probe.clj](default-probe.clj) (JVM mode) and the 100-form `seon.mcp/call` form; the plain A/B probe is
[plain-probe.clj](plain-probe.clj). Both read their inputs (the saved context, composed map and help text) from the
lane scratchpad paths they name.

The time differences on the printer probes are within the noise of three runs on a loaded machine (other lanes'
throwaway JVMs); allocation for the context AI text rose about 40 KB (5%), the rest is flat. The composer's
dense-entry cost fell from 36 ms and 95 MB to 0.2 ms and 1 MB.

## Tests

`bin/seon check` candidate runs (HEAD plus this slice): `seon.render.value-test`, `seon.print-test`,
`seon.repl-test`, `seon.render.floor-test`, `seon.sci.admit-test`, `seon.returned-error-test`,
`seon.render.transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it`: 804 pass, 0 fail (run
5b722d18aa3a). Wider reaching set (26 namespaces, two 120 s runs split by namespace, HEAD vs candidate): the red sets
are equal except the web_test header test above.

New: `a-string-shows-its-characters-in-html-and-each-cut-continues` (print),
`a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form`,
`an-html-cut-of-a-long-string-shows-text-and-continues`, `a-session-print-length-bounds-the-shown-members`,
`a-terminal-structural-failure-does-not-reenter-selection` (value), `a-rendered-value-with-cuts-is-spliced-verbatim`
(repl). Converted: `p-tee-generated-grammar-cannot-disagree` and `large-values-have-bounded-work-in-both-faces`
(a string's HTML is its characters), `a-failing-renderer-never-replaces-the-value`, the poison and core floor
diagnostics (data, path `[]`), the reply tests (spliced text asserted on `response-entries`).

The design named a symbol-producer row for render text; the row uses an inline producer function instead (no
shipped renderer returns long text for a constructible value).

## Tasks

- Done by this slice (runtime tasks, close after root's restart and the proofs above): `9245d42cfe6d`,
  `fb9d415bff49`, `5d42a25f9a53`, `1b420c6cb408`, `d9178db45287`; `87e2aa8555ae`
  (`seon.repl/a-selected-render-arrives-as-an-escaped-string`, proof `a-rendered-value-with-cuts-is-spliced-verbatim`)
  and `61e6f8134119` by the same change. `e3d5afaaa903`
  (`an-html-cut-of-an-unstored-value-offers-no-continuation`) closes with the s2a patch.
- Filed: `a6d856c50dc5` (render-source tests red on HEAD).
- Linked: `0d22b08e34c2` (`printing-thirty-two-numbers-allocates-megabytes`) for the composed map's armed HTML at
  10.3 MB and 14,597 armed calls per print on default, over the 10 MB line; `892cda0fa38a`; `8f8ea88a5bea` (the
  patch resolves the cursor before printing).
- Observed: an MCP run's per-form replies arrive as adjacent text items with no separator (`}#:seon.repl{`); they
  still read as data. A contract refusal of `seon.cluster.prompt/prompt` printed the whole offending request,
  including the SCI context's environment (`seon.print.value/printed-copies-stored-or-sent-instead-of-values`).

## Design check

- Held: emission overrides on the existing op stream, verbatim splicing, the raw string node and the path-carrying
  diagnostics all fit the real code without a second mechanism.
- Changed in implementation: a character cut's HTML shows the same `…+N form` label as the text face (the design
  had only `…+N` with the form in a title). The faces then share every token except a string's spelling, which
  keeps the P-TEE law meaningful, and the title is needed only for a refusal.
- Awkward: `ends-in-comment?`'s `;` check has false positives on every pr-str'd `:findings` entry whose fix text
  contains `;`, so slow replies now end the findings line with a newline. One test read only the reply's last
  line; it now reads the reply map.
- Render text paging is `(seon.print/full result/…)`, not a page: a page of render text would need its own
  retrieval source.
- Untraced: browser paint of the CSS and the view route click (s2a owns it), and armed after-numbers.
