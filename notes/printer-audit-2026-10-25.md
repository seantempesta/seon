# Printer audit, 2026-10-25

Owner, 2026-10-25: "make sure the value printer is working good everywhere". Every surface below was printed from a real value of a real run: `my.root/problems` and `seon_agents` on the standing root (port 7888, read through `seon.edn/encode` and printed in the worktree), and an outside agent a1 attached on this worktree's root (port 8290) working `seon.print/a-first-entry-that-fits-is-shown`. "main" is `seon.print.select` as of 888a728e2 on the same input.

## Surfaces

| Surface | Value | What it printed (main) | What it prints now | Cause | Fix |
|---|---|---|---|---|---|
| eval response, `seon_eval` root | `(my.root/problems sys)`, 151 KB of EDN | 5,416 tokens, 133 cuts, 132 lines `{…13 (get-in result/e1 [... :rows N])}` | 4,709 tokens, 2 cuts, no placeholder; every class shows; :stale-section 9 rows whole then `…168 (drop 9 (get-in result/e1 [:value :classes :stale-section :rows]))` | `fill-members` placed every member at its least before any grew; a row's least is a cut that shows nothing | `seon.print.select/fill-rows`: rows whole in order, one cut |
| eval response, an agent's `result/eN` | 177 maps of 4 keys (a1's e7) | 177 placeholder lines | 138 rows whole, `…39 (drop 138 (get-in result/e7 [:value]))` | same | same |
| deep nesting | `:evidence` maps 7 levels down in problems | later keys flush with the `{` (read as the parent's) | one column inside the opener at every depth | `:seon.config.render/nest-depth` 6 stopped indentation; the charge already counts it | key deleted |
| keyword map of row-valued entries | problems' `:classes` | (breadth) | breadth kept | a first fill-rows draft treated it as rows and cut 25 classes | a keyword key names an attribute: not a row |
| `seon_agents` (MCP) | 676 tasks | 123,016 characters, 2,986 lines, whole (`:seon.print/full`): no model can read it | 15,380 characters, one cut `…673 (drop 95 (get-in (seon.server/agents-answer sys) [:tasks]))` | an MCP answer that is not an eval printed whole, "nothing could re-ask it" | the op names its `:again` form, its handle |
| `/agent/{id}` eval body | a1's e7 | the reply's printed text cut again as prose at the part budget: 7 rows, the 7th cut mid-row, `…+15006 chars in 528 more lines` and no form (the whole in the cut's title) | the record printed at the part budget under its handle | a second cut after the printer (`seon.render.ui/evaluation`'s `prose` of `:text`) | `seon.print/record-text` takes a budget; the page prints the record |
| model request (`seon_context`) | a1's block | reads well | same | | the namespaced task map: see findings |
| screen evals | `(seon.screen/show)`, `(my.task/status)`, `(my.branch/changes)` | the page hiccup in full, an error map, `{}` | same | | none |
| errors | `(throw (ex-info "boom" {:rows (range 500)}))` | the error map with :data whole (fits) | same | | none |
| long string | 400-line `:out` | 17 lines then `"…+7194 chars in 383 more lines (subs (get-in result/e1 [:out]) 296)` | same | | none |
| vector of long strings | 50 × 200 chars | each `""…+N` at its least, shares | 2 whole, `…48 (drop 2 result/e1)` | strings are rows | fill-rows |
| table (vector of vectors) | 200 × `[i name i² :ok]` | breadth | 21 rows whole, one cut | | fill-rows |
| set of maps | 100 maps | breadth | 16 whole, one cut | | fill-rows |
| host objects | File, ns, var, class, fn, atom, ex-info | `#object[java.io.File 0x…]`, `#namespace[user]`, `#'clojure.core/map`, `#error {:via [...]}` | same | | none |
| `{:printed :type}` record | a host value recorded | its printed text, as recorded | same | | none |
| nil versus missing | `{:a nil :b [nil nil] :c #{nil}}` | exact | same | | none |
| infinite lazy seq | `(range)` | 31 members, `…? (drop 31 (get-in result/e1 [:xs]))` | same | | none |
| `bin/` and check lines | `seon.main/shown` | the printer's whole text by design | same | | none |

## Owner, 2026-10-25: generous budgets; no junk made, none rendered

Owner, 2026-10-25: "And that it has a generous context budget. Id rather us focus on not generating crap and then not rendering crap." Truncation is the last resort.

### (1) The budgets

The budgets are config keys in `resources/seon/config/default.edn`. `:seon.config.render.agent/token-budget` is **6,144** tokens (a reply's value; v1's value). `:seon.config.render/part-budget` is **256** (a value inside another; since this branch, also an evaluation's body on the page). `:seon.config.render/context-budget` is 16,000, `:seon.config.render/history-budget` 32,000, `:seon.config.render/value-budget` 600 and `:seon.config.render/root-context-budget` 700. The sizer is `:seon.config.ai/chars-per-token-prior` 3.2.

Printed tokens and cuts by budget, measured on this branch (whole means `:seon.print/full`):

| value | whole | 256 | 1024 | 2048 | 6144 | 12000 | 16000 |
|---|---|---|---|---|---|---|---|
| problems, raw rows (no schema meta) | 66,256 | 145, 2 cuts | 644, 4 | 1,475, 3 | 4,709, 2 | 9,582, 2 | 12,641, 2 |
| problems, by its render (`seon.render.ai/report-data`) | 14,218 | 213, 2 | 959, 1 | 1,958, 1 | 6,034, 1 | 11,922, 1 | 14,218, whole |
| `seon_agents`, main | 41,434 | 148, 1 | 698, 1 | 1,480, 1 | 4,564, 1 | 8,890, 1 | 11,918, 1 |
| `seon_agents`, no empty keys (this branch) | 30,897 | 190, 1 | 880, 1 | 1,777, 1 | 5,332, 1 | 10,490, 1 | 14,001, 1 |
| 177 small rows (a1's e7) | 6,301 | 196, 1 | 794, 1 | 1,606, 1 | 4,914, 1 | whole | whole |

**Proposed, for root's ruling (not changed on this branch):**
- Raise `:seon.config.render.agent/token-budget` from 6,144 to 16,000, equal to the context budget. Main's problems would then read whole through their render (14,218 tokens; 6,034 and one cut today), and so would a1's 177 rows (6,301; 4,914 and a cut today).
- What it costs: a reply can be 2.6× today's. The history budget (32,000) then holds two such replies whole, and it collapses older ones to their handle and one line (`seon.context/kept`), so a turn's request stays within the ~20–55k its config entry states.
- Raise `:seon.config.render/part-budget` for the page's evaluation body to 2,048: about 30 small rows instead of 4 (1,606 tokens against 196). Better as a key of its own (`:seon.config.render/page-eval-budget`) than raising the part budget for refused arguments too. A new key, so it waits for root's gate.
- The charge is an upper bound: the printed text runs 20–25% under the budget (`seon_agents` printed 11,918 of 16,000). That is the selection's design, recorded here, not changed.

### (2) Junk made upstream, and its producer

| junk | producer | here |
|---|---|---|
| a problem row's empty keys (`:producer nil :at-ms nil :proofs [] :answered-by []` on every derived row); `:evidence` repeating the row (`:example`, `:signature`, `:count` and `:agents` twice on an error row; `:name` and `:section` on a dead citation): 13 keys a row, raw | `seon.problems/derived-row`, `seon.problems/error-row`, `seon.problems/dead-citations` (:seon.problems/row requires the keys) | task `seon.problems/a-problem-row-holds-what-it-knows` (the gates read rows, so the schema change is its own task) |
| 676 tasks in `seon_agents`, each `:done false, :blocked-by #{}, :runs []` | `seon.server/task-summaries` | **fixed**: a key only when it says something; 41,434 → 30,897 tokens whole |
| `seon_agents` lists every held task, alphabetically, before anything live | `seon.server/agents-answer` | task `seon.server/seon-agents-answers-what-is-live` |
| each row under a class repeats `:class` | `seon.render.ai/report-data` | **fixed**: the row is under its class |
| 25 empty classes `{:n 0, :rows []}` | `seon.problems/report` (the full map is the value's shape) | already left out by its render (`seon.render.ai/ordered`); raw, they are the report's schema |

Root's 144-line case was the raw rows, a report value printed without its schema's metadata (its render had dropped them to `{:why ..}`). On this branch the raw rows print whole until the budget, then one cut.

### (3) Junk not rendered

- Elision placeholders: no row prints as `{…N (get-in ..)}` (`seon.print.select/fill-rows`, above). One placeholder case was a defect of the first draft and was found by this audit. `fill-rows` reserved a cut without its form's path, so the report through its render printed 347 of 6,144 tokens at 13 of the budgets between 2,000 and 9,000, its 177 rows one placeholder (`seon.print-test/rows-reserve-the-cut-they-print`, red on that commit). Fixed.
- A path repeated per row: gone with the placeholders; a cut names its path once.
- Empty and redundant keys: the renders already leave nil and empty values out (`seon.render.ai/ordered`); the producers above now leave out what they repeat. The printer itself prints every key it is given: nil versus missing is data, and the producer decides it.
- A collection of rows with one member of another kind still falls back to breadth, which can place placeholders: task `seon.print/breadth-shows-no-member-that-shows-nothing`.

## Second printers and clipping

`seon.render-test/one-printer-and-no-cut-before-it` holds `pr-str` and the like, `subs`, `take`, `take-last` and `drop-last` in display namespaces to the list in [render](../docs/render.md#one-printer). Grepping what it says it does not cover (`subvec`, `nthrest`, `split-at`, `partition-all`, `.substring`, `str` of a value, `format "%s"`, `clip`, `ellipsis`, `line-clamp`, `text-overflow`) found no second printer in src. `seon.context/kept`'s `subvec` chooses which history parts are sent, and it counts the ones it leaves out. The one second cut found was the page's eval body (above), and it is fixed.

## Findings left as tasks or notes

- **A namespaced map's members sit left of its opener:** `#:seon.task{:kind :work,` puts its later keys under the `#` (every agent's context shows its task this way), and `#error {:via` does the same. Fixing it needs the selection's charge to know about the lift; filed as `seon.print/a-prefixed-collection-indents-its-members-past-its-opener`.
- **Wasted budget:** the charge is an upper bound, so problems printed 4,709 of 6,144 tokens (the cut sits where the next row's charge, not its text, overran). Reading less conservatively is the selection's design, and out of scope here.
- **Order past `:sort-bound`:** a hash map of more than 64 keys shows in hash order (`:k170 :k230 …`), one entry per line. Sorting beyond the bound is v1's rule (its own seq order); packing scalar entries several to a line would save tokens.
- **What `seon_agents` shows first:** its 95 shown tasks are mostly held tasks with no runs, in alphabetical order. Putting running tasks and agents first is the render's choice (`seon.server/agents-answer`), not the printer's.
- **The page's one-line summaries** (`:as :inline`) cut with no form, their whole in the title; a stream row's cell is that by design. The error render on the page (`render/html` of `:ex`) gets no handle, so its cut names no form.
- **Trying each level whole re-walks the shown part:** printing 73 rows costs about four walks of them (13.2 MB). This is the selection's existing cost, measured in `seon.print-cost-test/printing-rows-costs-what-it-shows`.
