# Audit 2: what a Seon agent is given, ten requests (main 735daa3660, read-only)

Page sizes: system prompt 2.4k chars (all ten identical), page 3.5k to 9.6k chars. Reproductions: `repros.edn` shows true for nine; the tenth (collection-beside-a-long-string) shows false only because the harness compared `answers false (the value prints as ...)` with `"false"`. All ten premises hold, so P1 is clean. In every `:tests` status below, the audit store shows `:none` (no recorded result on main), so no test's status can be read.

## Verdicts

| # | request | 20 turns from the page? | fix site (file:line) |
|---|---|---|---|
| 1 | the-related-list-says-what-each-lead-left | likely | `seon.context/related-view` context.clj:266 (:sections at 281); tests context_test:961, :1078 |
| 2 | a-message-and-an-eval-of-one-id-both-print | likely | `seon.context/printed-run` context.clj:466 (`:parts` keyed by bare id ~490); tests context_test:77, context_cost_test:27 |
| 3 | the-help-docstring-says-what-it-does-now | yes | `my.agent/help` src/my/agent.clj:230 (doc line 236); tests repl_test:175, :241-254 |
| 4 | a-mixed-collection-shows-no-empty-members | likely, at risk | `select/fill-rows` select.clj:733, `fill-members` :710, `allot` :769; tests print_test:248, :256 (:278-283) |
| 5 | a-namespaced-map-indents-its-keys-past-its-opener | likely, at risk | `emit/emit-collection` emit.clj:267 (`nests 1`), `select/member-indent` select.clj:437; tests print_test:436, :76 |
| 6 | a-collection-beside-a-long-string-shows-its-first-part | no | `select/member-rank` :119, `grow-values` :650, `fill-members` :710 (none cited); test print_test:76 |
| 7 | a-size-just-under-a-megabyte-rolls-over | yes | `ui/bytes-str` render/ui.clj:57 (`fmt1` :49); no test exists (nearest render_test:519) |
| 8 | a-first-sentence-ends-at-its-question-mark | likely | `ref/first-sentence` ref.clj:420 (private, not shown); test ref_test:161 |
| 9 | a-cut-of-one-character-says-char | likely | `emit/cut-words` emit.clj:83-88, plus `cuts-of` regex print_test:23 |
| 10 | a-name-ending-in-a-star-is-not-cited-as-another | likely | `ref/trim-word` ref.clj:376 (private, not shown; `span-candidate` :331 already refuses `*`); tests ref_test:122-149 |

## Findings, by turns they cost

**F1. A long `:source` starves the cited var's `:tests` and `:callers` (P2, still open in effect).** Producer: `seon.render.ui/cited` (ui.clj:959) draws one map at one 600-token budget, and the printer gives a long string the room before a collection (this is request 6's own defect). Page, requests 4, 5, 6 (print-value) and 1 (load-tx):
`:tests {…27 (get-in (seon.render.ui/cited-of ...) 'seon.print/print-value) [:tests])}, :callers […10 (get-in ...)]`
print-value's 27 tests include the two that matter for request 4 (`rows-show-whole-then-one-cut`, `problem-rows`), and they are hidden. Cost: 1-2 turns on 4 requests, and the agent is not told which of 27 matters. Fix: draw source and tests as separate children (`[:pre source]` and a tests list), so one cannot starve the other. This is not the known 50-caller gap: the largest caller count on these ten pages is 13.

**F2. The key function's source prints blank (request 4).** Page: `"cites seon.print.select/fill-rows" ... {:source , :callers [seon.print.select/allot]}`. fill-rows is the named cause in the plan, and its source (about 3.2k chars) shows nothing and no cut marker, which breaks the page's own rule that a cut says what it left. Same producer as F1, with the same printer cause. Cost: 2-3 turns (the agent must run `source` on a private var). Fix: F1's separate child, plus a printer law that a code string shows at least its first line or says it was cut.

**F3. Tests are the cited var's direct callers only, so four requests show none.** Producer: `cited-of` (ui.clj:953, `group-by` over direct callers). `bytes-str`, `lead-sentence`, `cut-words` and `my.agent/help` show no `:tests` key, yet pinning tests exist: render_test:519 (`2.0 KB`), ref_test:161, print_test:23 and :205, repl_test:175/241. Request 9 has a hidden tripwire: `cuts-of` (print_test:23) parses the cut with `( chars(?: in ...)?)`, so after `…+1 char` the property tests (`lawful?`, `the-text-stays-inside-its-budget`) go red at a shrunk case. For request 7 there is no test at all, and the instruction ("beside the function's existing tests, in their namespace; `callers` lists them") gives no namespace. Cost: 2-4 turns each on 7, 8, 9, 3. Fix: list tests by reach (the digest reach `seon.program/unit-account` already computes, tests whose reach holds the var), not by direct call; and when none exist, the page names the nearest test namespace.

**F4. The real fix site is not cited in 4 requests (P3, partly open).** The plan cites only what it names with a qualified symbol, and the related search is by meaning. Missing from the page: `seon.ref/trim-word` (10), `seon.ref/first-sentence` (8; only listed by address in related), `seon.print.select/member-rank` and `allot` (6; the plan writes "member-rank" unqualified), and the later half of `emit-collection` and `printed-run`, where the fix lies (page: `…+1415 chars in 25 more lines`, `…+2540 chars in 31 more lines`; the cut falls just before emit.clj:267 and before printed-run's `(assoc-in c [:parts id]` lines). Cost: 3-6 turns for 10 and 6, 1-2 each for 8, 2, 5. Fix at the producer, the plan text: name the cause the way requests 4 and 5 do (`Its cause: seon.ref/trim-word strips a trailing *`); and the cited-source budget should be raised for a var the plan names as the cause. Request 6's page cites only `print-value` (a wrapper) plus `seon.print.select/show` in related.

**F5. The related part is addresses and names, not content (P4/P3, still open) and it contradicts request 1.** Producer: `seon.render.ui/related` (ui.clj:988) draws the raw `context/related` map; `related-view` (the summary lines and the `:read`/`:more` forms) is reached only by the cut's form `(my.task/related)`. Page: `{:sections ["docs/render.md#no-clipping" ... ] :functions [seon.render.ui/alloc-span ...]}`, 680-920 chars on every page, mostly irrelevant (`seon.root/warm!`, `:seon.cost/too-few-sizes`, `seon.program/meaning-changes`, `decisions.md#the-models-context-is-the-standard-chat-format...`). Request 1 says "the related sections list on an agent's page shows the first sentence of each section's lead"; the page's list shows no sentence, so an agent that fixes `related-view` changes nothing on the page. Also request 1 never says what format "how much of the lead it left" takes (contract `[:vector :string]`). Cost: 1-3 turns on 1, about 250 tokens of noise on all ten. Fix: draw `related` through `related-view` (the fix request 1 asks for, so do it by hand first, or word request 1 as the design it needs), and drop the list for a request whose plan already cites its sites.

**F6. Request 3's text is wrong in one word.** Plan: "The docstring of my.agent/help ends by telling what an anchor answered before this change". The sentence is mid-docstring (agent.clj:236), followed by the namespace sentence. Harmless (1 turn). Also, to redefine `help` the agent needs the last 312 chars (cut at `(subs ... 1644)`), and a test for a docstring is a placebo by AGENTS section 5; the plan should name the proof (`(not (re-find ... ))` in repl_test beside :175).

**F7. The plan is shown twice on requests 4 and 5.** Producer: `ui/prose` (ui.clj:80) cuts the plan at 600 tokens and puts the whole text in a `:title` attribute, which the model also receives. Page: `"...seon.print.select/fill-rows shows rows " nil [:span {:class "seon-print-elision", :title "Printing many like maps ...` Size: 1.2k chars (request 4) and 1.3k (request 5) duplicated, and the cause sentence sits behind `…+168 chars`. Fix: the model's render of the plan is whole, with no `:title` copy (the instruction is the task and may not be cut).

**F8. Empty parts, still.** `"ctx-keys" [:div {:class "ctx-keys", :id "ctx-keys"} ()]`, a bare `()` after the plan, and `{:to-prove {}}` on every page (about 110 chars, same defect class as the old empty run part). Fix: the producers return nil for an empty value.

## Instructions (task text and system prompt)

- Order is clear and matches P6/P7: reproduce, `fail!` if right, then stop reading, write the deftest, register, run red, redefine, merge. Good.
- Gaps: "run it red" names no form (only `(my.task/status)` in the system prompt, `(my.agent/prove)` is not mentioned and runs the judge's wide set). Where the proof goes is clear only when the var has direct tests (F3). It does not say to run the plan's reproduction before reading the cited source, but the source is already on the page, so that costs nothing.
- The `@ctx` paragraph (about 330 chars: `swap! ctx assoc :seon.ctx/pattern`, `(my.agent/help "ctx")`) is not used by any of the ten requests. Cut it from the first-turn prompt, keep it in `(my.agent/help "ctx")`.
- The prompt says the page holds "your proofs and their status, the done rule's reasons, your run"; none is on the first page. Say "when it has any".
- No contradiction between page and instructions apart from F5 and F6.

## Noise, sizes

| item | chars | per |
|---|---|---|
| related part (F5) | 680-920 | every page |
| duplicate plan in `:title` (F7) | 1,200-1,300 | requests 4, 5 |
| print-value source (wrapper, not the fix site) | about 1,700 | requests 4, 5, 6 |
| load-tx source, tests, callers (setup of the reproduction; the cited set still follows it) | about 2,000 | request 1 |
| `@ctx` paragraph | about 330 | every page |
| empty parts (F8) | about 110 | every page |

## Verdict: NOT CLEAN

Request 6 is "no" (the fix sites are uncited, the allotment redesign is the printer's core, and the page shows the defect in action), and F1, F3, F4 and F5 each cost more than a few turns on more than one request.

Fixes ranked by turns saved (all ten requests counted):
1. F4, name the cause in the plans (10, 8, 6) and show the fix half of long cited source: about 12 turns across 5 requests; the cheapest, one edit of `seon.fixtures/requests`.
2. F1 + F2, draw the cited source and the tests as separate children (ui.clj:959): about 8 turns across 5 requests.
3. F3, tests by reach: about 8 turns across 4 requests, and it removes the cuts-of tripwire surprise.
4. F5, related through `related-view` or dropped: about 4 turns plus 700 chars on every page.
5. F7, F6, F8, the `@ctx` paragraph: about 1 turn each, 3k chars on two requests.
