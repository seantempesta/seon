# A value on the page as structure, from the record's data (design for the owner's approval)

The owner, 2026-10-25: "What is the status of the UI? We can do more complex representations of values there." The model reads printed text, and that stays. The page (`/agent/{id}`, `/agent/{id}/debug`) can show the stored value itself, so this is a design for rendering an eval's value from its record's data, with no second printer. Nothing is built yet.

## What exists

- **The record.** An eval's record (`:seon.eval/record`, `seon.repl/record-details`) keeps the response with its value when the value is plain data (`seon.db/plain?`). Otherwise it keeps `{:printed :type}`, the response as it printed at the time.
- **The row.** The row's render (`seon.render.ui/evaluation`, line 450) shows `⇒` and then `(:text r)`, the printed text, as prose. So a value stored as data is drawn as its text: maps as `{:k v}`, a test's `:source` as an escaped string literal (`\"f\"`, its `;;` comments inside quotes), cut at "…+2868 chars in 54 more lines".
- **The printer's HTML.** The one printer already emits structural hiccup from the same operation stream as its text (`seon.render/print-html` → `seon.print.emit/ops-hiccup`). Each nested collection becomes a `<details data-seon-path>`; scalars are spans classed by kind (keyword, string, symbol, number). `seon.render/render` picks a value's declared render (`:seon.render/html` on its schema), else this printer.
- **Opening a unit.** `<details>` toggling already posts `/open` through Datastar (`seon.render.ui` line 574, `seon.web` line 148), and the render proc renders that unit when it is open. The render cache is keyed by reads, and a record never changes, so a row renders once.

## The proposal: render the value, not its text

**1. The row renders the stored value.**
- `evaluation`'s `⇒` becomes `(render/html value {:handle h :budget part-budget})`.
- The value is the record's `:value` (or `:values`, each form's). If the record holds `{:printed :type}`, it shows that text as today, with its type.
- The response's other keys (cost, handle, `:redefined`) stay where they are. `:redefined` is data and renders like any value.
- One function, the render the value's schema declares, else the printer's hiccup.

**2. The printer's hiccup fold learns these shapes.** All of them are made in `seon.print.emit`'s hiccup fold over the nodes `seon.print.select` builds, so the text output is unchanged.
- **A map:** key/value rows, a two-column grid. Keys are right-aligned and muted; values sit in their own cells. Nested maps nest as indented grids.
- **A vector of same-keyed maps:** a `<table>`, with the keys as a header row (in the first map's order) and one row per map.
  - It must be at least 2 maps sharing at least 2 keys. The test is select's: it already walks every entry.
  - Long tables cut by rows, and the cut row names `(get-in result/eN [i ...])` as the text cut does.
- **Code:** a `Code` value (marking below). Shown verbatim in `<pre><code class="language-clojure">`, never as an escaped literal, highlighted (open question 1).
- **A docstring or prose:** a `Prose` value (a docstring, a plan, a lead). Shown as a paragraph with its line breaks kept, unquoted.
- **Hiccup the agent made:** a `Hiccup` value, rendered as HTML through an allowlist of elements, with `class` the only attribute. `script`, `style`, `on*` attributes and `href` that isn't relative are all dropped. `(seon.screen/show)`'s `:page` is one; today it prints as nested vectors.
- **A handle:** a symbol in the `result` namespace (`result/e12`) is a link to that eval's row (an `id` per row, `#eval-e12`). The printer's own cut forms, `(get-in result/e43 [...])`, link the same way.

**3. Long or deep values collapse, and opening one fetches its path.**
- Where the printer cuts under the part budget, the hiccup has a closed `<details>`:
  - its summary is the cut's text ("…+2868 chars in 54 more lines", or "12 more entries");
  - its `data-seon-path` is the cut's path.
- Opening it posts `/open` with the unit and path. The render proc renders `(get-in value path)` with the same render at the part budget and patches that unit through Datastar. That is the same value the printer's cut form, `(get-in result/eN path)`, reads.
- No second printer and no client-side value: the browser holds only what was rendered.
- An opened path renders once (keyed by record and path) and costs that path.

**4. The debug page stays the request as sent.** `/agent/{id}/debug` shows what the model was sent, which is text, and that is its job. Each eval part there gets a link to its row on the agent page, where the value renders as structure. So the debug page changes only by those links.

## The marking: one, read by both renders (agreed with the printer session)

Code is marked by a type in the value, never inferred, and never by a key's name.
- `(seon.print/code s)` makes a `seon.print.select/Code`, a deftype holding the text. It is equal to another Code of the same text, and its `str` is the text. This is on `jolt/printer-everywhere`, 35ffc7e5d, not merged yet.
- Its producers today: a red proof's `:source` (`seon.context/turn-state`), a failure's `:assert` and `:got` (`seon.context/failure`), `seon.docs/resolve-ref`'s `:source` at code depth, and `seon.docs/cited-code`.
- The printer prints it verbatim, and its hiccup is `[:code {:class "seon-print-code"} text]` (face `:seon.print/code`). The page's fold reads the same face, so both renders read one marking.

Two more kinds follow the same pattern, a producer wrapping what it means:
- **Prose**, for docstrings, plans and leads: printed unquoted, shown as a paragraph.
- **Hiccup**, for `seon.screen/show`'s page and any page an agent builds: rendered through the allowlist.

Each is a deftype beside Code, with its own face in the fold. A string no producer wrapped prints as a string.

**What it needs from the record.** A Code is no plain EDN, so a value holding one is stored as `{:printed :type}` today. The page could then only show its text, which is exactly the case to fix. The record must keep the type:
- `seon.edn` gains a tagged literal per kind (`#seon/code "..."`, and the same for prose and hiccup), read back as the type;
- `seon.db/plain?` admits them.

That is three reader tags, no attribute and no config key. It is a format, so it needs root's gate. The alternative is no new tags, with the page rendering a stored text form, but that loses code as code for every record, so I don't recommend it.

## Mock: a1's turn 29 on the related-sections root (http://127.0.0.1:8261/agent/a1)

Today the screen's `(my.task/status)` row reads:

```
⇒ {:handle result/e157, :ms 2.557, ... :value {:turn "29 of 30", :proofs [{:test seon.context-test/a-proof-with-no-result-reads-unknown, :status :fail, :source "(clojure.test/deftest a-proof-with-no-result-reads-unknown\n  ;; task seon.context/... \"f\" ... …+2868 chars in 54 more lines
```

Rendered from its data:

```
(my.task/status)                                       2.6 ms · 2.4 MB · its own turn
 turn     29 of 30
 proofs   ┌──────────────────────────────────────────────┬────────┬──────────────────────┐
          │ test                                         │ status │ source               │
          ├──────────────────────────────────────────────┼────────┼──────────────────────┤
          │ seon.context-test/a-proof-with-no-…unknown   │ ● fail │ ▸ 58 lines of code   │
          └──────────────────────────────────────────────┴────────┴──────────────────────┘
          ▾ source (opened: fetches [:value :proofs 0 :source])
            (clojure.test/deftest a-proof-with-no-result-reads-unknown
              ;; task seon.context/an-unrun-proof-is-unknown-in-the-context: a proof with no result never
              (let [db (db-with-program)
                    db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "f" ...
            (verbatim, highlighted; comments as comments, strings unescaped)
 tests    this-turn 1 · at-judge 3 · when-asked 0
```

`(seon.screen/show)` beside it renders its `:page` as the small page it is (the agent list, the run), not as `[[:aside [:ol [:li "●" "a3" "busy"] ...`. `(my.task/related)`'s `:nearest` lines render as text rows, and its `:read` and `:more` forms as code.

## Cost and the check

- **Cost.** A record renders once: the render cache is keyed by reads, and a record never changes. An opened path renders once and costs that path. Cost proofs:
  - a row costs its value's nodes up to the part budget, flat in the conversation's length;
  - an opened path costs that subtree.
- **The check is the browser.** A subagent opens a real agent page and compares each kind against the record's data:
  - a map and a table;
  - code with comments;
  - a docstring;
  - the screen's hiccup;
  - a cut opened to its path;
  - a handle link.

## src estimate

| piece | where | lines |
|---|---|---|
| map rows, the table, code, text, handle links in the hiccup fold | `seon.print.emit` | ~90 |
| table detection; the Prose and Hiccup types beside Code | `seon.print.select` | ~40 |
| the row renders the value and links handles | `seon.render.ui/evaluation` | ~15 |
| `#seon/code`, `#seon/prose`, `#seon/hiccup` read and written; `plain?` admits them | `seon.edn`, `seon.db` | ~25 |
| an open path renders that path | `seon.render.proc`, `seon.web` `/open` | ~30 |
| hiccup allowlist | `seon.render.ui` | ~25 |
| producers wrap what they mean (a docstring in a view, a plan, the screen's page) | `seon.docs`, `seon.context`, `seon.screen` | ~10 |
| total | | **~220 src lines**, plus ~50 lines of CSS and the highlighter |

It deletes `evaluation`'s prose path for data values, which keeps only its use for `{:printed}` records.

## Open questions for the owner

1. **Highlighting.**
   - The simplest is highlight.js's Clojure grammar, vendored into `web/` (one file, about 40 KB, no server work).
   - The other is a server-side tokenizer over the reader's tokens. That's more code, and it must keep comments verbatim, which the reader drops.
   - Recommendation: highlight.js.
2. **Agent hiccup as HTML.** The proposed allowlist is `div span p ul ol li table tr td th pre code h1-h4 section aside main strong em a`, with `class` only and a relative `href`. Is that tight enough?
3. **The three reader tags** (`#seon/code`, `#seon/prose`, `#seon/hiccup`): approve them, so a record keeps code as code? This builds on Code, from `jolt/printer-everywhere`, so that branch merges first.
