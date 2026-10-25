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

## Second printers and clipping

`seon.render-test/one-printer-and-no-cut-before-it` holds `pr-str` and the like, `subs`, `take`, `take-last` and `drop-last` in display namespaces to the list in [render](../docs/render.md#one-printer). Grepping what it says it does not cover (`subvec`, `nthrest`, `split-at`, `partition-all`, `.substring`, `str` of a value, `format "%s"`, `clip`, `ellipsis`, `line-clamp`, `text-overflow`) found no second printer in src. `seon.context/kept`'s `subvec` chooses which history parts are sent, and it counts the ones it leaves out. The one second cut found was the page's eval body (above), and it is fixed.

## Findings left as tasks or notes

- **A namespaced map's members sit left of its opener:** `#:seon.task{:kind :work,` puts its later keys under the `#` (every agent's context shows its task this way), and `#error {:via` does the same. Fixing it needs the selection's charge to know about the lift; filed as `seon.print/a-prefixed-collection-indents-its-members-past-its-opener`.
- **Wasted budget:** the charge is an upper bound, so problems printed 4,709 of 6,144 tokens (the cut sits where the next row's charge, not its text, overran). Reading less conservatively is the selection's design, and out of scope here.
- **Order past `:sort-bound`:** a hash map of more than 64 keys shows in hash order (`:k170 :k230 …`), one entry per line. Sorting beyond the bound is v1's rule (its own seq order); packing scalar entries several to a line would save tokens.
- **What `seon_agents` shows first:** its 95 shown tasks are mostly held tasks with no runs, in alphabetical order. Putting running tasks and agents first is the render's choice (`seon.server/agents-answer`), not the printer's.
- **The page's one-line summaries** (`:as :inline`) cut with no form, their whole in the title; a stream row's cell is that by design. The error render on the page (`render/html` of `:ex`) gets no handle, so its cut names no form.
- **Each level's whole attempt re-walks the shown part:** printing 73 rows costs about four walks of them (13.2 MB). This is the selection's existing cost, measured in `seon.print-cost-test/printing-rows-costs-what-it-shows`.
