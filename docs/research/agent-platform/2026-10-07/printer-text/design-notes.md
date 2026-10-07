---
title: Printer long text on both faces, design
type: research
status: proposed (gate 1, design only)
date: 2026-10-07
lane: printer-text
---

# Printer: long text on both faces (lane printer-text, design only)

One line: the value printer (`seon.render.value` selects, `seon.print` emits one fipp op stream as text and
hiccup) already selects long strings correctly (line-end fill, `subs` dig forms, HTML expander links when the unit
carries a route base). What is broken is three emission/splicing steps around it: the HTML face emits a string as its
escaped literal, a view of an unstored value supplies no route base, and the reply grammar re-prints rendered text
with `pr-str`. The fix is in those three places; the selection does not change.

Probed on default `ec5f13d2f` (restarted 11:57; default was `:faulted` with three open faults and five over-bound
warnings, all already tasked) as SCI agent `printer-text`, scratch namespace `scratch.printer-text`. No platform code
was loaded into SCI; prototypes are pure functions over `prepare`'s real output. JVM mode was used once for a
read-only census. At 21:18 `bin/seon status` reported "an exact-root JVM is alive but its endpoint is unavailable"
(default being restarted); default answered again at 12:19 UTC on `e80131ef1` and the two tasks in §7 were created then.

Scope (root, mid-lane): part 2 (source printing) dropped, because S1 v2 returns `{sym, source, :seon.render/ai
text}` and a value's own render text already prints raw at the root (§4 checks the nested case). Added: delete
`seon.render.value/diagnostic-line` (§3) and find why `(help)` arrives quoted (§5).

## 1. What exists and what is broken

| Step | Where | What it does |
|---|---|---|
| select | `seon.render.value/select-nodes`, `make-node`, `string-shown`, `fill-strings`/`fill-end` | a long string shows a head grown to the last line end inside the budget (agent profile 1,500 tokens = `tokens/estimate-chars` 4,800 chars) |
| cut | `cut-node` | elision node with prefix, omitted, next offset, continuation form (only when the root is a `result/…` handle), `::print/href` (only when the unit has `:seon.render.value/route-base`) |
| emit | `seon.print/leaf-ops` | `::string` → `string-literal` (quotes, `\"`, `\\` escaped; newlines kept since 7619b8dfd); `::elided` `:characters` → `elided-text` = literal prefix + `…+N` + form; hiccup = the same text in a span, a link when `::href` |
| page | `seon.render.web` `/data` handler (web.clj:3850-3870) | the only caller that passes `route-base` and `:seon.render.data/cursor`; stored blobs and entities only |
| reply | `seon.repl/response-entries` → `data-text` (repl.clj:178) | for a value with a declared renderer, `pr-str`s the shown text unless it reads as one form without comments |

Measured on root's real assembled context (`seon.cluster.prompt/prompt`, 155,809 chars now; 544 ms / 136k calls cold
in SCI):

| Face | ms | Output | Defect |
|---|---|---|---|
| AI, root `'result/x1` | 3.8 | 4,750 chars, 1,484 tokens, ends `"…+151151 (subs result/x1 4658)` | none beyond `\"` escapes (dig form present) |
| HTML, root `[:seon.agent/id "root"]` | 0.8 | 5,264 chars of HTML ending `&quot;…+151083</span>` | 66 backslashes (`\&quot;`); the whole literal is one `seon-print-elision` span; no link, no form, no reason |
| HTML of the composed map (s2a's view value) | 6.8 | 25,117 chars, 26 cuts, **0 links** | every cut dead |
| HTML, same value with `route-base` + cursor `{path [:seon.cluster.prompt/text] offset 4726}` | 2.9 | 5,247 chars, starts at char 4,726, three links (two breadcrumbs, next page `offset=9356`) | works today |

So task `e3d5afaaa903` (`seon.render.value/an-html-cut-of-an-unstored-value-offers-no-continuation`) is two defects:
(a) the HTML face shows a string's literal instead of its text; (b) the context view passes no route base, so the
existing expander never appears. The paging machinery (`select-nodes` offset branch, `data/at`, `path-url`,
breadcrumbs) is complete and needs no change.

## 2. The smallest composition

### 2.1 HTML strings show their text (BEHAVIOR CHANGE, `seon.print`)

`leaf-ops` gives each string-bearing op a hiccup override, the mechanism `::projected` and `::elided` already use
(`text-op` third arity), so text and hiccup stay one op stream and cannot disagree about a token:

- `::string`: text unchanged (`string-literal`); hiccup `[:span {:class "seon-print-string"} value]`.
- `::truncated-string`: hiccup `[:span {:class "seon-print-string"} (str value "…")]`.
- `::elided` with `:characters`: hiccup `[:span [:span {:class "seon-print-string"} prefix] marker]` where marker is
  the `…+N` link when `::href`, else `[:span {:class "seon-print-elision" :title refusal-or-form} "…+N"]` naming the
  continuation form or `::requery-refusal` (a dead cut says why; today it says nothing).

CSS (`resources/public/css/input.css` + built `output.css`): `.seon-print-string { white-space: pre-wrap }` and
`::before/::after { content: '"' }`, so a reader still sees a string boundary without escapes. The hiccup serializer
escapes `<>&"` for HTML; the browser shows the characters.

Prototype on the real context (hand-built hiccup equal to what the change emits): 5,249 chars of HTML (was 5,264),
backslashes 66 → 4 (the 4 are the text's own), shown text 1,476 tokens ending at a line end.

Not changed: the text face. A nested string stays a readable literal with its lines; the root text case is S1's
`:seon.render/ai` (§4).

### 2.2 Every cut of an unstored value continues (BEHAVIOR CHANGE, the view)

The view that shows an unstored value supplies what `/data` supplies: `:seon.render.value/route-base` (its own route)
and `:seon.render.data/cursor` from the request's `path`/`offset`, and navigates with `seon.render.data/at` before
`value/html`. For s2a's `sent-context-html` (web.clj, lane s2a-views) that is about four lines:

```clojure
(let [cursor (data/parse-cursor (get query "path") (get query "offset"))
      shown (dissoc composed :seon.db/db)
      found (data/at shown cursor)]
  (value/html {:seon.render/value (get found :seon.render.data/value found) :seon.db/db database
               :seon.render.value/root [::context agent-id]
               :seon.render.value/route-base (route/path ::route/agent-debug {:id agent-id} {:sent "true"})
               :seon.render.data/cursor cursor}))
```

Route name UNVERIFIED (s2a's design keeps `?sent=`); the s2a lane owns web.clj, so this goes to it as a patch or into
its slice. A click re-derives the value (prompt warm 3.7 ms, cold ~500 ms per s2a) and prints ~4.8 KB of text from
the offset. Paging all 156 KB is 33 pages at the one budget; see open question 1.

### 2.3 What disappears

- `seon.render.ns/function-ai`'s false docstring claim (`:source-lines` "the printer shows unescaped") and the
  split-lines workaround behind it. Three producers split text into vectors of line strings
  (`seon.render.ns/function-ai`, `seon.cluster.message` render `:seon.message/lines`, `seon.error` `:seon.render.ns/source-lines`
  at error.clj:2206). Since 7619b8dfd a multi-line literal already prints as its lines, so a vector of line literals
  costs two quotes and a separator per line and says less. Each should carry the string itself; owners named, not in
  this slice's files.
- `seon.repl/data-text` and `one-form?` (§5).
- `seon.render.value/diagnostic-line` and `plain-projection`'s second text (§3).

### 2.4 Which step of the one path it serves

Print (the edge) and the human view of it. No new owner: the op stream's hiccup override, the existing cursor/route
machinery and the value printer's core. Nothing is added beside the printer; one reply function is deleted.

## 3. `diagnostic-line` deleted (root's added item)

`diagnostic-line` (value.clj:996) builds `";; renderer failed at " (pr-str path) ", value printed as data: "
(bounded-text …)`: `pr-str` and the core's projection-free last resort used as a printer. Callers and what each
becomes:

| Caller | Today | After |
|---|---|---|
| `prepare`, failed render beside a value (value.clj:1582) | text = value text + `\n` + line; HTML `[:div.seon-render-unavailable line]` | text = value text + `\n` + `(core-text {:seon.render.value/diagnostics failures})`; HTML `(core-html …)` of the same map. The REPL path already shows `:seon.render.value/value-text` (eval.clj:3561) and delivers the failures as `:seon.cluster.eval/diagnostics`, so agents see no change |
| `prepare`'s catch, structural retry (value.clj:1626) | appends `line` to the structural projection's text | appends the same `core-text` of the failures |
| `plain-projection` (value.clj:1493) | `(bounded-text value 8192)` + `\n` + line | `(core-text value)` + `\n` + `(core-text {:seon.render.value/diagnostics [failure]})`; `core-text` is already total (its last resort is `bounded-text`, the one place the projection-free text stays, per root's core ruling) |

`:seon.render.value/diagnostics` is an existing key (the vector `prepare` returns), so no key is added. A Throwable in
it prints through `core-text`'s own chain handling. `:seon.render.value/value-text` then equals `:text` minus the
diagnostic; its consumer is `seon.sci.eval/shown` (eval.clj, lane s5a-reply), so the key stays.

Tests: `seon.render.value-test/a-failing-renderer-never-replaces-the-value` (value_test.clj:947-970) asserts the
`";; renderer failed at []"` prefix; it becomes an assertion on data: one entry in
`:seon.render.value/diagnostics` and the value's own entries in the text. `test/seon/sci/admit_test.clj:162` calls
`plain-projection` directly. `floor_test` keeps `bounded-text` (still the core's last resort).

Also seen, same family, not in this slice: `render-database-identity-ai` `pr-str`s the db name; `breadcrumbs`
`pr-str`s a path step as link label (value.clj:1483); `path-url` `pr-str`s the path into the URL (data
serialization read back by `parse-cursor`, arguably transport, root to rule). Covered by
`seon.print.value/printed-copies-stored-or-sent-instead-of-values`.

## 4. Root-only render text when nested (root's check)

Probe (`src` = a 4-line defn with a docstring holding `\"q\"`, a `;;` comment, `#()`, `@`, `'`):

| Form | Reply |
|---|---|
| `{:sym 'my/f :source src :seon.render/ai src}` | `:value (defn f …)` raw, comment and quotes as written. Holds. |
| `[{:sym 'my/f :source src :seon.render/ai src}]` | the map prints as data: `:source` and `:seon.render/ai` both as escaped literals (`\"Doc \\\"q\\\".\"`): **the source shows twice, escaped**. Breaks. |
| the same at root with 800 lines of render text | cut mid-line `(+ 1 2…+1666` with no dig form, and the reply reports `:seon.eval/value-tokens 1500` = shown tokens. Breaks. |

So root-only holds for `(source x)` at the root and breaks in two ways S1 should know:

1. Nested (`(map source xs)`, a `doc` map carrying a source): the value prints twice. S1 can carry the text once:
   `{:seon.fn/sym x :seon.render/ai text}` with no separate source key; nested it prints once, as a literal with lines.
2. A monster source (seon.render.value/prepare, 140 lines, 2,820 tokens) is cut by `make-node`'s `kept` at a
   character with no continuation, and `omitted-characters` does not count it. This is a general printer defect (any
   render text over budget), task `5d42a25f9a53`: cut at the last line end inside the ceiling (`fill-end`'s rule),
   continue with `(subs (:seon.render/ai result/e…) n)` when the text is the value's own `:seon.render/ai` string (the
   path is known), else name the requery refusal, and count the omitted characters. In `value.clj`, ~10 lines in
   `make-node` and `omitted-characters`. I recommend it in this slice because S1 depends on it.

## 5. Why `(help)` arrives quoted and the namespace pull as one escaped string (root's check; task `61e6f8134119`)

Cause: `seon.repl/data-text` (src/seon/repl.clj:178), not a re-read. `response-entries` passes the value's shown text
through `data-text` only when `:seon.eval/renderer` is set; `data-text` then `pr-str`s it unless it reads as exactly
one form with no comment token.

Evidence (agent printer-text's branch):

| Evaluation | `:seon.eval/renderer` | stored `:seon.eval/shown` | reply |
|---|---|---|---|
| `(help)` | `seon.bootstrap/render-help-ai` | raw multi-line prose | one quoted line with `\n` escapes (prose never reads as one form) |
| `(:seon.agent/namespace (pull …))` | `seon.render.ns/render-ai` | raw multi-line map, 4,594 chars | one quoted line; `read-string` of the shown text fails: "The map literal starting with …1 contains 1 form(s)" (the printer's own cut `{…1}`) |
| `{… :seon.render/ai src}` | nil (an inline key selects no declared producer) | raw | raw |

So the stored text is right; the reply converter re-prints it. It is a second printer (the printers inventory row
repl.clj:177 classed it "legitimate", which is wrong: it prints printer output again). Any rendered value with a cut
inside a map is turned into an escaped string, because the printer's cut grammar never reads in a map.

Change (BEHAVIOR CHANGE, src/seon/repl.clj, not s5a's files): `response-entries` uses the shown text verbatim for
`:value`, `:error` and `:diagnostics`, as it already does for unrendered values; delete `data-text` and `one-form?`
(no other caller). `reply-map` already ends a line whose last token is a comment, so a `;;` line inside the text
cannot swallow `, :result …`. Consequence: a reply holding prose or cuts does not read back as EDN, which is already
true of every unrendered value with a cut inside a map. `response`'s docstring ("reads back as data") is corrected in
the same commit. The other half of `61e6f8134119`, a namespace render that shows 51 agents and other agents'
evaluation history (13,913 value tokens for `{:seon.ns/name user}`), is `seon.render.walk/with-related`'s, outside this
slice; it stays on that task.

The other `pr-str`s in `response-entries` (`:out`, `:findings`, `:note`) are task
`seon.print.value/printed-copies-stored-or-sent-instead-of-values`. `:out` matters here: `(clojure.repl/source x)`
prints to out, and the reply shows it as one line with `\n` escapes. The slice can route `:out` through the value
printer (a string literal keeps its lines) in the same function; I list it as optional because that task wants the
output stored as a blob with a dig form, which is larger.

## 6. Costs before and after (same probes)

| Probe | Before | After (prototype) |
|---|---|---|
| HTML of the 155,809-char context, root string | 0.8 ms, 5,264 chars HTML, 66 `\`, no continuation | same selection (one more span per cut), ~5,249 chars, 4 `\`, link to `offset=4726` |
| HTML of the composed map (s2a view) | 6.8 ms, 25 KB, 26 dead cuts | same ms and size, 26 links |
| next page (cursor offset) | not reachable | 2.9 ms, 5.2 KB HTML |
| AI text of 5 real sources (7-140 lines) | 0.2-0.6 ms | unchanged (text face untouched) |
| reply of a rendered namespace pull | 1 line, 4,594 chars escaped (1,435 shown tokens) | the stored 4,594 chars verbatim, multi-line, fewer tokens (no `\"` `\n` escapes) |

Allocation: the per-thread counter reads 0 from SCI (as s2a found); process-wide `getTotalThreadAllocatedBytes`
gave 0.47 MB for the context's HTML, an upper bound. Compiled numbers need the installed change: re-run in JVM mode
after root's restart. Work is proportional to what is shown: the hiccup override reuses the node's value; nothing
re-walks the string.

Census (JVM, default, 163 ms): 5,641 stored function sources; 4,195 contain `"` or `\`; escaping costs 0.7% of their
tokens (1,692,186 raw vs 1,703,447 as literals); 5,511 (97.7%) fit the 1,500-token budget raw. Escapes are a fidelity
problem, not a size problem.

## 7. Findings and tasks

Created on default after its restart:

- `seon.render.value/render-text-past-the-budget-is-cut-mid-line-with-no-dig-form` (`5d42a25f9a53`, §4.2), proof
  `seon.render.value-test/a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form`.
- `seon.repl/the-reply-reprints-a-renders-shown-text-as-a-string` (`9245d42cfe6d`, §5), proof
  `seon.repl-test/a-rendered-value-with-cuts-is-spliced-verbatim`; or fold into `61e6f8134119` if root prefers one task.

Seen and linked, no new task: `:out` and findings `pr-str` (`seon.print.value/printed-copies-stored-or-sent-instead-of-values`);
`(dir …)` as an escaped string (`seon.repl/directory-render-returns-text-the-printer-clips-mid-form`, deleted by S1);
a missing-contract warning appeared on the second `defn measure` but not the first (`4fe2a5d2ba47`).

Seen, not tasked yet (outside printer scope, owners should file): a refused `seon.db/pull` reply carries 15 stack
frames per link plus a dig form for 35 more (Seon-generated bulk for an agent's typo); a contract refusal on
`seon.cluster.prompt/prompt` printed the whole offending request twice, once per problem.

My own mistakes, not tasks: pulling `:seon.task/status` (no such attribute); calling a nonexistent
`seon.render.hiccup/html`.

## 8. The code slice

Files:

- `src/seon/print.cljc`: `leaf-ops` hiccup overrides (§2.1), ~10 lines changed.
- `src/seon/render/value.clj`: delete `diagnostic-line`; convert its three callers (§3); render-text cut at line end
  with continuation and counted omission (§4.2), ~10 lines.
- `src/seon/repl.clj`: delete `data-text`, `one-form?`; verbatim splice (§5); docstring of `response`.
- `resources/public/css/input.css`, `output.css`: `.seon-print-string` rules (s2a has them claimed: coordinate).
- `src/seon/render/web.clj` `sent-context-html` cursor and route base (§2.2): s2a-views' file, by patch or in its slice.
- Tasks: `resources/seon/tasks/seon.render.value.edn`, `seon.repl.edn` close or link.

Reaching tests (name these to `bin/lane commit --test`):

- `seon.print-test` (hiccup of a string, a character cut with and without `::href`).
- `seon.render.value-test`: `a-failing-renderer-never-replaces-the-value` (converted to data), new
  `an-html-cut-of-a-long-string-shows-text-and-continues` (table: with route base → link at next offset; without →
  title naming the form or refusal), new render-text line-end cut test.
- `seon.render.floor-test` (core and `bounded-text` unchanged), `seon.sci.admit-test` (plain-projection).
- `seon.repl-test`, `seon.repl-grammar-test` (rendered value spliced verbatim; a reply whose text ends in a comment
  still closes), `seon.mcp-reply-test` (s5a holds it; read-only reach).
- Web: `seon.render.web-debug-test` / `web-context-test` for §2.2 (s2a's).

Contracts: every changed function keeps or gains its one-line contract; `leaf-ops` is contracted already.

## 9. Alternatives considered

- A raw "text block" face for root multi-line strings on the text face (my first prototype: tokens 0-3% lower, 20 → 2
  backslashes on `seon.print/elided-text`). Dropped by root's scope update: S1's `:seon.render/ai` covers source, and
  a raw root string inside `#:seon.repl{:value …}` needs a reply-grammar change.
- A larger HTML budget or a debug render profile for the context view: rejected for now; CLAUDE.md gives both consumers
  one selection. Open question 1.
- Keeping `data-text` for prose only: still a second print of printer output; refused.

## 10. Open questions for root

1. Paging 156 KB at 4.8 KB per page is 33 clicks. Accept (one selection for both faces), or should the HTML expander
   for a character cut also offer `seon.print/full` (the whole value, the existing explicit opt-in)?
2. After §5, a rendered prose reply no longer reads back as EDN. Accept, given unrendered cut maps already do not?
3. Fold §4.2 (render text cut) into this slice, as I recommend, or give it to S1?
4. `path-url`'s `pr-str` of the path into the URL: transport encoding (keep) or a printed copy (convert)?

## 11. Design check

Held against the real code: the selection and the paging were already right; the defects sit in emission (HTML
literal), a caller (no route base) and the reply (re-print). Wrong in my first reading: I took the escaped
namespace reply for a printer defect; the stored text is raw and `seon.repl/data-text` escapes it. Awkward: the
context view's continuation needs a change in a file another lane owns, and the CSS file is claimed by s2a.
Untraced: browser paint of the CSS quotes (not observed); compiled timings and per-thread allocation (SCI reports 0).

No restart or reset needed for this design. Scratch: SCI definitions in `scratch.printer-text` on agent
printer-text's branch only (disposable; root may retire the branch).
