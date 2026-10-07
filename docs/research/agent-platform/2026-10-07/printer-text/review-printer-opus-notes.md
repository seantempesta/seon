---
title: Printer long text, design review (Opus)
type: research
status: review
date: 2026-10-07
lane: review-printer-opus
---

# Review of printer-text design (e0eca860a), Opus lane

Reviewed against HEAD e0eca860a and default (faulted; open faults all tasked: `seon.turn/show-row` is
`seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil`). JVM-mode probes ran only already
installed functions, on default's "Clojure Connection" platform thread, measured with per-thread
`getThreadAllocatedBytes`. No prototype code was loaded into default. Root's rulings Q1-Q4 are taken as given; two of
them need re-ruling on the evidence below.

## Verdict

Approve §2.1 (HTML string override), §2.2 (route base and cursor on the view) and §4.2 (render text cut at a line end)
with the changes below. §5 (delete `data-text`) is right as a deletion but needs a re-ruling: it reverses an open
task and its proof, which the design does not mention, and its `:error` half splices prose errors into the reply. §3
(delete `diagnostic-line`) loses the failure path and the lint class as written. Q1 as ruled has no mechanism for a
string and costs near 9 MB.

## Findings, by severity

### 1. HIGH: §5 reverses an open task and its proof, unlisted

`seon.repl/reply-map-is-not-edn-when-elided-or-rendered` (resources/seon/tasks/seon.repl.edn:1-5) says "the reply map
must read back as data for every value". `data-text` is that task's mechanism. Its proof
`seon.repl-test/the-reply-is-one-edn-map-whatever-the-value` (repl_test.clj:354-389) asserts at :375 "prose renders
are one string" through `one-map`, which requires the reply to read as one EDN map. Also red after §5:

- repl_test.clj:239-244 ("text that is not data is one readable string") for `:error`;
- returned_error_test.clj:62-66, which reads `:seon.repl/error` back as a string from a prose saved refusal.

Root's Q2 acceptance was made without this. The design must name the task, close or rewrite it in the same commit,
and convert these three assertions to data assertions (CLAUDE.md: tests assert data, not printed text).

`:error` and `:diagnostics` are a different case from `:value`. `data-text` quotes the prose that some producers still
put where an error map belongs: an unrun evaluation's message, or `seon.turn.batch` at batch.clj:624
(`(str "facts not proposed: " …)`). Spliced verbatim, these become `#:seon.repl{:error No such namespace:
missing.function, :ms 1}`: prose with its own colons and parentheses inside the map, which an agent will copy.
Recommendation:

- `:value`: verbatim, as designed (the measured win holds).
- `:error` / `:diagnostics`: fix the producers to give error maps (errors law: one flat map). Until then keep these two
  entries quoted with the printer's own `string-literal` (lines kept, no `\n` escapes). Never splice them verbatim.

Owner option, if EDN readability of `:value` must also hold: replace `pr-str` in `data-text` with the printer's
`string-literal`. That needs `seon.print/string-literal` to be public, and it is still the one printer. It keeps every
test above green and removes the `\n` escapes from `(help)`, but keeps the `\"` escapes. A rendered EDN namespace pull
would still arrive as one string. I lean towards verbatim `:value` plus string-literal for `:error`/`:diagnostics`.

### 2. HIGH: Q1 ("offer the whole value via `seon.print/full`") has no mechanism for a string, and costs ~9 MB

- `seon.print/full` returns a string unchanged (print.cljc:252-265; measured: `(meta (p/full "xxxx"))` → nil). A
  character cut is a cut of a string, so `(seon.print/full <the string>)` is a no-op. Only `full` of the enclosing
  map works, and that prints every other key whole too. For a root string (the design's `[:seon.agent/id "root"]`
  probe), nothing works.
- An HTML link runs no evaluation, so "offering full" needs a route parameter that the view turns into `p/full` on an
  IObj. The design does not specify it.
- Cost (JVM, map holding a 142,890-char multi-line string): `v/html` of `(p/full m)` took **48 ms and allocated
  8.9 MB**, giving 183 KB of hiccup. `v/text` of it took **128 ms and allocated 8.3 MB**. That is about 60 bytes per
  input character, so the owner's 10 MB stop line is crossed at about 170 KB, and root's context is 156 KB and growing.

Options for root:
- (a) Drop Q1 and keep paging; it costs 0 extra.
- (b) A "whole text" link that serves the string's own characters as `text/plain`. A string's text is itself, as
  `full`'s docstring says of `println`, so this prints nothing.
- (c) Keep Q1 with a declared byte bound, refusing past it.

I recommend (b) or (a).

### 3. MEDIUM: §3 conversion loses the failure path and the lint placeholder

- `diagnostic-line` names the failing path (`;; renderer failed at [:a 0 :b], …`, measured). The design's
  `(core-text {:seon.render.value/diagnostics (mapv :diagnostic failed)})` carries no path, because `failed` nodes
  hold `:path` and the map drops it. Carry it as data, e.g. each failure with its `:seon.render.data/path`.
- What improves: `core-text` shows the ex-data that `bounded-text` hid (`:data ...`). It measured 360 chars against
  161 chars for the line, all of it information.
- The HTML replacement `(core-html …)` emits `[:pre.seon-data-panel.seon-core-value]` and drops
  `seon-render-unavailable`. render.clj:1115-1119 documents that class as "the ONE placeholder class `seon.render.lint`
  counts", so a page full of render failures would stop being a lint finding. Wrap it:
  `[:div {:class "seon-render-unavailable"} (core-html …)]`.
- Missed in the inventory: `unavailable-line` (value.clj:276-282) is the same prose plus `core-text` shape, beside
  `text`/`html`. It is not a second printer, but it is the sibling to convert the same way so both diagnostics are data.
  The "renderer unavailable" placeholders at web.clj:427, walk.clj:770 and render.clj:1122 are fixed text, not printers.
- No second printer remains after the conversion: `bounded-text` stays only as `core-text`'s last resort, as the
  design says. `:seon.render.value/value-text` stays (eval.clj:3562 reads it).

### 4. MEDIUM: the reply composer's lexer is the larger cost; new task

The design relies on `reply-map`'s `ends-in-comment?` after the deletion. That function runs `syntax-tokens` over the
whole text of every entry, and twice for the last one. Measured (JVM, per thread):

| Probe | Time | Allocation |
|---|---|---|
| `data-text`, 2,560-char prose | 0.12 ms | 15 KB |
| `data-text`, 2,983-char EDN map | 1.0 ms | 1.4 MB |
| `ends-in-comment?`, the same map | 0.84 ms | 1.3 MB |
| `ends-in-comment?`, 108,891 chars | **73.7 ms** | **96 MB** |
| `response`, 3 KB map, unrendered | 1.0 ms | 1.3 MB |
| `response`, 3 KB map, rendered | 1.9 ms | 2.8 MB |

So deleting `data-text` halves the rendered reply's cost, which the design should claim, with these numbers. The
remainder grows with text size: a `seon.print/full` reply of 100 KB costs about 100 MB. Filed
`seon.repl/reply-map-lexes-every-entry-to-find-a-trailing-comment` (`fb9d415bff49`). It is not in this slice's
files unless root adds it, but §5 lands on top of it.

### 5. MEDIUM: §2.2 pages a value that changes between clicks

The view re-derives the context on each click. s2a's own notes (s2a-views design :182-184) say root's history changes
with "every MCP `eval_clj` or turn", so `offset=4726` on the next click indexes a different text. The `/data` route
pages immutable blobs, but this one does not. The continuation link must carry the basis the page was derived at
(commit id or answered-t), and the view must derive as of it. Otherwise pages skip or repeat lines silently.

### 6. MEDIUM: §4.2's "else name the requery refusal" strands declared-render text

For text from a declared renderer (`(help)`, the namespace render, any `render-*-ai`), no path to the text exists, so
the design gives a refusal. Yet `prepare` already honours `seon.print/full` on the value (value.clj:1557-1563 lifts
`:ceiling`), so for an IObj root `(seon.print/full result/e…)` is a working dig form. Use it before refusing. Also
note that the `(subs (:seon.render/ai r) n)` continuation prints its page as a root string literal (escaped, filled
at line ends), not as raw render text. The face changes between pages; this is acceptable, but say so.

### 7. LOW: file and sequencing collisions

- S1 (s1-lookups design :324) deletes `render-directory-ai` from repl.clj and its case at repl_test.clj:379-389. That
  case is inside the same deftest §5 must rewrite. One lane holds repl.clj and repl_test.clj at a time: sequence S1
  after printer-text, or fold the edit in.
- S1 :385 already assumes printer-text owns "root render text raw in the reply, cut at a line end"; that is consistent
  with Q3.
- web.clj:3850-3870 is HEAD's numbering. s2a's working tree has moved the `/data` handler to :3473-3485, and the CSS
  file is claimed by s2a-code (`bin/lane status`), as the design says.

### 8. LOW: details in §2.1

- print.cljc:396-399 says text and hiccup "cannot disagree about a token". After §2.1 they share token boundaries, but
  a string's hiccup is its raw text. Revise that comment in the same commit.
- `::string`'s text uses `(or (::text node) (string-literal value))`. The hiccup override should come from the same
  source (`::text` when present), or a cut key name shows differently on the two faces.
- CSS `::before/::after` quotes are not copied with a selection, which suits a human. Paint is unobserved.

### 9. LOW: verified claims

- `make-node`'s `kept` cut (value.clj:1037-1046) is mid-character, has no continuation, and is uncounted by
  `omitted-characters` (value.clj:1453-1474, which counts only `:string`/`:name`/collections). This holds.
- `(help)` arrives as one escaped line (my agent, SCI, 47 ms, 434 calls). This holds. The same reply carried
  `:large-value` (1,105 tokens) with the fix "select what you need": Seon's own help is judged misuse. Filed
  `seon.repl/help-draws-a-large-value-finding-on-its-own-text` (`2d24c2ed98f1`).
- HTML selection allocation does not grow with string size: `v/html` / `v/text` on 8.6 KB through 581 KB strings
  allocate a constant 656 / 639 KB (1.2 ms warm). This matches the design's "proportional to what is shown". The
  constant 650 KB for about 6 KB of output is worth a later profile.
- Passing a cursor without first resolving it with `data/at` (which the design's snippet does) silently prints the root
  as `{}` "map 2 items" (576 chars, 4.2 MB). Filed `seon.render.value/an-unnavigated-cursor-prints-the-root-as-an-empty-map`
  (`8f8ea88a5bea`).

## Simplifications

- With finding 3 applied, the text face's diagnostic can leave `:seon.render.value/text` entirely. Diagnostics are
  already returned as `:seon.render.value/diagnostics` and delivered by eval.clj's `:seon.cluster.eval/diagnostics`.
  `:value-text` then equals `:text`, and both the key and eval.clj:3562's `or` disappear. The HTML face shows the
  wrapped `core-html`. Root should check the text-sink consumers (health.clj:689, repl.clj:624) first.
- §5: delete `one-form?` with `data-text`, as designed. With finding 1, the only remaining quoting is one call to the
  printer's `string-literal`, for `:error` and `:diagnostics` only.

## Questions for root

1. Re-rule Q2, given that it closes or rewrites `seon.repl/reply-map-is-not-edn-when-elided-or-rendered`. Should
   `:error`/`:diagnostics` stay quoted until their producers emit maps?
2. Q1: choose (a) paging only, (b) a raw text link, or (c) full with a bound.
3. Should the §2.2 continuation carry the derivation basis (my recommendation), or does s2a's view become stored?

## Tasks filed (default, `seon.task/create!`)

- `seon.repl/reply-map-lexes-every-entry-to-find-a-trailing-comment` (`fb9d415bff49`)
- `seon.repl/help-draws-a-large-value-finding-on-its-own-text` (`2d24c2ed98f1`)
- `seon.render.value/an-unnavigated-cursor-prints-the-root-as-an-empty-map` (`8f8ea88a5bea`)

No restart or reset needed. My mistake: two probe `def`s (`user/mx`, `user/measure`) were made in default's JVM in
JVM mode, against the no-intern rule; both are `ns-unmap`ped and resolve to nil. Later probes used `let` only.
