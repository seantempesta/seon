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

**Done (root, 2026-10-25, under the owner's ruling; an existing key, a new value):** `:seon.config.render.agent/token-budget` is now 16,000, and an evaluation's body on the page prints at that same budget: one budget for an eval's value wherever it is shown. No page key was added.
- Main's problems now read whole through their render (14,218 tokens; at 6,144 they printed 6,034 and one cut). a1's 177 rows read whole too (6,301).
- On a1's page, with 19 evals at 16,000: rendering every evaluation component (`seon.render.ui/evaluation` → `seon.web.html/html`) costs 25.8M ticks, 103 MB allocated and 68 ms, for about 143 KB of HTML. The largest body is the raw problems report, 43.8 KB, rendered in 11.8M ticks, 42.7 MB and 31 ms; the 177 rows take 24 KB. The page's shell is 10.9 KB and its parts arrive on the feed.
- The charge is an upper bound: the printed text runs 20–25% under its budget (`seon_agents` printed 11,918 of 16,000). That is the selection's design, recorded here, not changed.
- The cost proofs state their own input budget (6,144, `seon.print-cost-test/shown-budget`), so a proof does not move with the config key.

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

## What is left out of bin/prove: 240 on main, 250 here

bin/prove leaves out the tests that reach a process of their own (`test-runner/plan-of` `:left`, from `seon.program/graph-index`'s `:process?`), green or not. This branch's 10 more were `seon.reconcile-test` ×8, `seon.procs-test/reconciler` and `seon.problems-test/a-defect-in-the-reconciler-does-not-feed-itself`. The path a reach walk finds: `seon.reconcile/reconciler-proc` → `seon.reconcile/advance` → `seon.turn/work!` → `seon.turn/observe` → `seon.agent/prove!` → `seon.agent/await-reply!` → `seon.agent/handle!` → `seon.agent/run-tests-forked`, which uses `seon.lib/fork!`.
- The edge `advance` → `work!` is advance's quoted `'seon.turn/work!` (the code it sends an agent to evaluate; `seon.program/quoted-vars`).
- Main's checkout lacks that edge. Its kept program store refreshed tree after tree (`test-runner/program-db`, `seon.source/refresh-tx`), and its facts for `advance` came from an analysis made when the quoted name's namespace did not exist.
- Imported whole into an empty store, main (8fbe2ec20) has the edge too, and its process tests are 216, the same set name by name as this branch's (9 of them in `seon.reconcile-test`).
- So this branch adds no process test, and nothing in its diff reaches the edge. Main's 240 is the stale store's number, and a whole import of main leaves out 250 as well. The 4 fewer keys are the same 10: keys are the candidates less what is left out (739 − 250 = 489; main on a fresh store, 699 + 37 − 250 = 486).
- Left out ≤ 240 would need a correct edge removed. Filed instead: `seon.program/a-trees-program-is-the-same-however-its-store-reached-it`.
- The 10 tests ran green by name: `bin/test seon.reconcile-test seon.procs-test seon.problems-test`, with six more namespaces (TEST OK, 111 run, 161 reused).

## Code prints as code (owner, 2026-10-25)

Owner, 2026-10-25: "If we reference a function we should return the quote escaped forms so agents see more clojure code as is normally seen in files or the repl". Source text was a string, so the printer printed it as a string literal: every `"` escaped, a map of a proof read as data around one long escaped string. Now it is code, marked at its producer (```seon.print/code```: plain data, `{:seon.print/code text}`, which a record stores as `:db.type/data` and reads back equal; root, 2026-10-25, ruled one representation live and stored, after a deftype would have needed a Datahike serializer change), and the printer prints it verbatim at its place: comments and quotes as written, every later line indented to where it stands, cut by the one string cutter with `(subs (:seon.print/code (get-in result/e3 [:source])) n)`. The printer never guesses code from a string's text.
- **Producers marked:** a red proof's `:source` (`seon.context/turn-state`); an assertion as written and what it got (`seon.context/failure`); a cited var's `:source` at `:code` depth (`seon.docs/resolve-ref`); the screen's cited vars and namespaces (`seon.docs/cited-code`).
- **Before**, `(my.task/status)` on the related-sections root (port 8261): `:source "(clojure.test/deftest a-proof-with-no-result-reads-unknown` then the body, with every quote written `\"`: `(task/create-tx db [{:seon.task/ns 'demo :seon.task/name \"f\"`.
- **After** (`seon.context-test/a-red-proofs-source-reads-as-its-file`): `:source (deftest f-test` then the body as the file holds it, `;; f doubles: the plan's "exact" case`, `"two doubled"))`.
- **Cost:** `seon.print-cost-test/printing-code-costs-what-it-shows`. At 1,000 lines, code costs 576 KB and 350k ticks in all; a string's escaping prices the same characters at 13.5 MB. Per line, the line breaks are found three times, once each time the selection makes a map's child. Any long string in a map pays that; making `:breaks` lazy per node would make it once.
- **Stored:** `seon.context-test/code-is-plain-data-that-round-trips` (a record holding code reads back equal and prints verbatim).
- **Docstrings** already print as Clojure prints them in a defn: a string literal whose line breaks stay line breaks.

## The screen's cited section (owner: "This is not good output")

The producer was `seon.render.ui/cited`: `[:pre (print/text view part-budget)]`, an EDN string printed before the screen's printer printed it again, escaped, under a 256-token budget (40 `{…2}` lines on main). It now holds `seon.docs/cited-code`'s values as data in the hiccup, printed once.

**Before** (a cited namespace):
```
[:pre
 "#:seon.ref{:address \"seon.context\",
 :heading \"seon.context\",
 :problem-kind :no-lead,
 :depth :doc,
 :below
   [#:seon.ref{:address \"seon.context/ai-messages\", :heading \"ai-messages\"}
    #:seon.ref{:address \"seon.context/as-sent\", :heading \"as-sent\"}
    #:seon.ref{:address \"seon.context/callees\", :heading \"callees\"}
    {…2}
    {…2}   ;; 40 of them
```
**After:**
```
(ns seon.context)
;; ai-messages as-sent callees cost current-state eval-messages failure
;; fold-of folded help-page help-text kept last-reply message-messages
;; ... turn-state
```
**Before** (a cited var, `:doc` depth): `{:symbol seon.context/turn-state, :doc "The turn sections of task t's current state as data, …" (one escaped string), :arglists ([db t {:keys [results reds … …9]}]), :contract [...]}`.
**After:**
```
(defn turn-state
  "The turn sections of task t's current state as data, measured again each turn from
  observed: ...
  left. The messages delivered are the conversation's (`conversation`), not the block's."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds turn max-turns unmerged truncated? reasons test-run-errors source repairs will-merge tests attention]}]
  …)  ; the body: (source seon.context/turn-state)
```
(`seon.reader/definition-head`: one edamame read with end locations; multi-arity `([a] …)` per arity; a `def` has no head and shows its source.) Internal keys (`:problem-kind`, `:depth`, `:heading`, `:address`) do not reach the model. A namespace's missing lead is no longer said at all; the wiki's `:leadless-section` problem says it once.

## A screen eval's :ms :ticks :bytes

The model does not need them: the screen's forms are root's, run each turn, and their cost is root's to act on, not the agent's. The page needs them, and reads them from the record (`seon.render.ui/cost-line`). `seon.context/eval-messages` leaves them out of a screen eval's message. An agent's own evals keep theirs, since an agent can read its own code's cost.

## Tests that take the tree digest

The chain: `seon.contract/registry` → `seon.config/schema-forms` (every schema baked into one literal, its `:seon.render/*` properties naming every render) → `seon.render.ui/agent` → `seon.docs/cited` → `seon.ref/file-view` (a tree reader). So every contracted call reached every render, and through them the tree. `schema-forms` is now in ```seon.program/names-only```, and a names-only form gives the reach walk no edges (`tree-readers` and `env-readers` were names-only for `uses?` only). A check still reaches the schemas it checks, through its contract's own schema rows; the baked schemas are `:env`. Measured on fresh program stores (an incrementally refreshed store can hold stale facts: task `seon.program/a-trees-program-is-the-same-however-its-store-reached-it`):

| tree | tests | take the tree digest | process tests (left out) |
|---|---|---|---|
| main 761c1502e | 708 | 682 | 210 |
| this branch (main merged) | 715 | 255 | 198 |

## Second printers and clipping

`seon.render-test/one-printer-and-no-cut-before-it` holds `pr-str` and the like, `subs`, `take`, `take-last` and `drop-last` in display namespaces to the list in [render](../docs/render.md#one-printer). Grepping what it says it does not cover (`subvec`, `nthrest`, `split-at`, `partition-all`, `.substring`, `str` of a value, `format "%s"`, `clip`, `ellipsis`, `line-clamp`, `text-overflow`) found no second printer in src. `seon.context/kept`'s `subvec` chooses which history parts are sent, and it counts the ones it leaves out. The one second cut found was the page's eval body (above), and it is fixed.

## Findings left as tasks or notes

- **A namespaced map's members sit left of its opener:** `#:seon.task{:kind :work,` puts its later keys under the `#` (every agent's context shows its task this way), and `#error {:via` does the same. Fixing it needs the selection's charge to know about the lift; filed as `seon.print/a-prefixed-collection-indents-its-members-past-its-opener`.
- **Wasted budget:** the charge is an upper bound, so problems printed 4,709 of 6,144 tokens (the cut sits where the next row's charge, not its text, overran). Reading less conservatively is the selection's design, and out of scope here.
- **Order past `:sort-bound`:** a hash map of more than 64 keys shows in hash order (`:k170 :k230 …`), one entry per line. Sorting beyond the bound is v1's rule (its own seq order); packing scalar entries several to a line would save tokens.
- **What `seon_agents` shows first:** its 95 shown tasks are mostly held tasks with no runs, in alphabetical order. Putting running tasks and agents first is the render's choice (`seon.server/agents-answer`), not the printer's.
- **The page's one-line summaries** (`:as :inline`) cut with no form, their whole in the title; a stream row's cell is that by design. The error render on the page (`render/html` of `:ex`) gets no handle, so its cut names no form.
- **Trying each level whole re-walks the shown part:** printing 73 rows costs about four walks of them (13.2 MB). This is the selection's existing cost, measured in `seon.print-cost-test/printing-rows-costs-what-it-shows`.
