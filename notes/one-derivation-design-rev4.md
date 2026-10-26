# One derivation, revision 4: the request from the db, a simple REPL, the wire, the hiccup

Agent view, for root and an independent review before any code (AGENTS §2). It keeps revision 3's five functions (`notes/one-derivation-joint-design.md`): `(ctx db e)`, `(view ctx)`, `(html part)`, `(ai part)`, `(diff prev next)`. Slices 1 and 2 are built (jolt/ctx-atom; jolt/view-parts, reported ready). It replaces revision 3's request assembly, its wire and its "told" changes with the owner's rulings of 2026-10-26:
- the request is a pure function of the db at the session's turn points;
- the REPL only evaluates and records;
- the tool answer carries an eval's ripple effects;
- outside changes are inbox entries;
- enrichments are renders, and their hiccup is designed with care.

Line numbers are main's at 51c641288's base.

## 1. The spine: the request for turn n is a function of the db

```
request(n) = system(t0) ++ start(t0) ++ Σ i<n [ assistant(i) ++ tool(i) ++ world(i) ] ++ world(n)
  start(t0)  = (ai part) of each part of (view (ctx db@t0 agent)), in view order
  assistant(i) = turn i's prose and eval calls, from its turn record
  tool(i)    = (ai part) of each eval turn i made, its record as of its own t
  world(i)   = the inbox entries turn i took (§5), each rendered as of its t
```
- **t0 < t1 < … < tn are the session's turn points**, each turn's basis t. Its record already holds them.
- **Every term is read as of its t.** No later transaction changes an earlier term, so the prefix is byte-stable by construction.
- **Nothing sent is stored as text.** `:seon.turn/sent` keeps each part's key, mode (whole, line, entry) and t. The debug view re-derives the text from them, and in doing so tests reproducibility.
- **Renders resolve as of t.** The program is in the db with its history: each form's source and digest, from `seon.program` and the source import. The render a part is drawn with at t is the one whose form the program db held at t.
  - The live request draws with the running process's render, which is that same form.
  - A re-derivation (the debug view, a resume) loads the form's version as of t into a scratch namespace once, memoised by its content key. That key is the canonical EDN of the form's digest and the value, `seon.derive/encode` on jolt/one-content-keyed-derivation, and this is the use that branch was waiting for.
  - So a later redefinition never changes an old turn's bytes.
- **The history cut and the folds are a pure rule over that sequence:** `kept` (context.clj:525), and `printed-run`'s folds and `:same-as` (context.clj:454) moved into the assembly. They apply to the rendered terms, never inside a render.
- **Cost:**
  - The live request holds its previous request's terms in process: a cache keyed by content, not stored. So a turn costs its new terms.
  - A re-derivation from the db (the debug view, a resume) pays the session once.
- **Proof** (seon.context-test, slice 4b): re-derive turn n-1's request from the db, then turn n's. The first must be a byte-for-byte prefix of the second, including when a render was redefined between them.

## 2. The REPL only evaluates and records

An eval is code in, and its record out: value or values, `*out*`, `*err*`, the error that stopped it, its cost, and the transaction it made (its t). Nothing else runs inside an eval. Everything that enriches what is shown is a render over facts (§3), drawn when its part is drawn and memoised by value.

**Today's response and record keys** (`seon.repl/response`, repl.clj:333; `record-details`, repl.clj:376):

| key | today made by | under this design |
|---|---|---|
| `:handle :value :values :out :err :ex` | the eval | **stays**: what it answered |
| `:ms :ticks :bytes`, `:fns` | the profiler around the eval | **stays**: its cost, one line in the answer |
| `:recorded` | `wrap-record`'s account (repl.clj:234) | **stays**: what it defined, which is its transaction |
| `:redefined` | `program/meaning-changes` inside the eval (repl.clj:233) | **goes to the eval part's render**, which reads the eval's transaction datoms |
| `:cites` | `ref/references` over the prose at record time (repl.clj:381, 403) | **goes to the render**: the references are parsed when the eval part is drawn, memoised by value |
| `:line` | `print/line` at record time (repl.clj:396) | **goes to the render**: the `:inline` face |
| `:seen` (agent state) | `print/seen` after each eval (repl.clj:458) | **goes to the assembly**: folds are its rule (§1) |
| `:same-as` | `printed-run` (context.clj:465-503) | **already the assembly's**; it stays there |

**Effects that move to renders**, with file:line:
- **The eval's ripple effects:** what it redefined, the sections citing what it redefined, and what parts re-rendered. Today this is `:redefined` (repl.clj:233). It becomes the eval part's render over its tx datoms and the program db as of its t.
- **Cited references:** today `ref/references` at record time (repl.clj:381). They become the render of the eval's prose and of the task's plan.
- **Related sections:** today `seon.context/related`, run in the turn's observe (context.clj:187; turn.clj's embed-written!). Slice 1 writes the result as a fact, `:seon.task/related`. **Ruling needed (§7a)**: keep that fact, or make related a render over the vector index as of t, memoised by content.
- **Similar functions** (context.clj:143) and **the story's pulls** (`run-state`, context.clj:280): the same question. Slice 1 writes the story as `:seon.run/story`.

## 3. The wire: one meaning per role (the API's four roles, ours to fill)

| role | carries |
|---|---|
| system | the rules: the help page, pinned per session |
| assistant | the agent's prose and its eval calls |
| tool | the REPL's answer to one call: the eval part's `ai` face, with value or values, out, err and the error, then its **ripple**: what it redefined (each contract or docstring change), the sections that cite what it redefined, the parts that re-rendered and their new text, and every ctx path its transaction changed. Then its cost on one line. |
| user | the world, one message per turn, in fixed sections in a fixed order: `[from <sender>]`, each message taken (a person's or root's words); `[changes]`, ctx's data diff of every other entry taken (§5), as paths and new values; `[page]`, the parts whose rendered text changed, by key. An empty section is left out; "nothing" when all are empty. |

**One recorded eval's tool answer, with its ripple** (a1, `(defn top-bar …)` redefined, as the render would print it):
```
result/e14 ⇒ #'seon.render.ui/top-bar
redefined: seon.render.ui/top-bar  docstring changed · contract [:=> [:cat :any] :any] → [:=> [:cat :any :boolean] :any]
cited by: docs/render.md#the-page
re-rendered: [:task] (its write set cites top-bar)
ctx: [:evals "e14"] added
0.84 ms · 101,699 ticks · 603 KB
```

**One turn's world message:**
```
[from owner] check the proofs before you merge
[changes]
  [:proofs seon.render-test/a-view-is-its-ctxs-parts :status] :fail
  [:proofs seon.render-test/a-view-is-its-ctxs-parts :failure] {:assert (= [...] ks) :got [...]}
  [:merges "catch-up/7"] {:from :main :from-t 536871602 :applied 3}
[page]
  [:proofs] 1 proof: 1 fail
```

## 4. The hiccup renders draw: a small vocabulary

Renders now carry the enrichments, so their hiccup is a vocabulary, not each render's own markup. The page draws it. The agent reads each part's `ai` face, the printer's text of the part's value or its `:seon.render/ai`, and that text follows the same vocabulary, so the two read alike.

| element | hiccup | text (what the agent reads) |
|---|---|---|
| a part | `[:section.part [:h2.kicker key] …]` | `[key]` then its body |
| a reference | `[:a.ref {:href "/doc/…"} "docs/render.md#the-page"]` | `docs/render.md#the-page` |
| a list of references | `[:ul.refs [:li ref " — " lead] …]` | one per line: `ref — lead` |
| a status | `[:span.badge.fail "fail"]` | `:fail` |
| code | `[:pre.code [:code.language-clojure s]]` | the code, verbatim |
| prose | `[:p.prose s]` | the prose |
| a change | `[:div.change [:code path] " → " value]` | `path value` |
| a ripple | `[:section.ripple [:h3 what] [:ul …]]` | `what: item · item` |

- **Classes are the stylesheet's semantic ones** (input.css `@layer components`), never utilities in a render.
- **A render returns these elements and values.** The printer draws a value inside them as its structure, under the part's budget, with cuts that say what they left.

**Example 1, a task's cited references** (from the story's write set):
```clojure
[:section.part [:h2.kicker "task cites"]
 [:ul.refs [:li [:a.ref {:href "/doc/seon.render.ui/top-bar"} "seon.render.ui/top-bar"] " — The bar above every page"]
           [:li [:a.ref {:href "/doc/docs/render.md#the-page"} "docs/render.md#the-page"] " — Every page is the walk of one entity…"]]]
```
```
[task cites]
seon.render.ui/top-bar — The bar above every page
docs/render.md#the-page — Every page is the walk of one entity…
```

**Example 2, an eval's ripple** (the tool answer above, as the page draws it):
```clojure
[:section.ripple [:h3 "redefined"]
 [:ul [:li [:code "seon.render.ui/top-bar"] " docstring changed · contract " [:code "[:=> [:cat :any] :any]"] " → " [:code "[:=> [:cat :any :boolean] :any]"]]]]
```
```
redefined: seon.render.ui/top-bar docstring changed · contract [:=> [:cat :any] :any] → [:=> [:cat :any :boolean] :any]
```

## 5. Outside changes are inbox entries (slice 6′, the agent loop's): answers to its four questions

- **One rule:** every change the agent did not make in an eval of its own arrives as an inbox entry, taken by `take!` at the turn's start (before anything else: invariant 1), written on the branch by the taking turn (upserted by id), and ordered in the conversation by that t. `:seon.run/taken` counts entries.
- **(a) The diff's shape:** ctx's paths and their new values, `{path new}`, with removals as `{path :removed}`. Old values are not sent: the agent read them, and they sit above in its prefix. A message's diff is its own conversation entry, `{[:messages id] {:from :body}}`.
- **(b) An entry's render:** declared on its schema like any value (`:seon.render/html`; the text by the printer, or `:seon.render/ai`). The world message's sections (§3) are the entries' `ai` faces grouped by kind.
- **(c) Namespace:** widen `:seon.message/*` with `:seon.message/kind` (`:message :stage :verdict :main`) and `:seon.message/diff`. A message is the entry kind it already is. There is no second namespace; `:seon.message/from` becomes a ref, which needs a store erase, at root's moment.
- **(d) Producers:**
  - root's `message!` (`:message`);
  - `seon.reconcile/stage-boundary` (`:stage`, whose diff is the new task's ctx keys, placed after the earlier stages' entries: invariant 2);
  - the judge on main (`:verdict`);
  - `merge-main!`'s catch-up (`:main`).
  - The turn's own test run is not an entry: its results are the turn's observation on the branch, already in ctx, and they reach the agent as `[changes]` paths in the next world message by the same diff.
- **Wake:** a parked session wakes on `:message` and `:stage` only. A verdict holds or releases the task through root, and main moving accumulates until the next turn. My recommendation: a red verdict does not wake. The owner rules.

## 6. Slices, revised
1. **ctx-atom** (built): ctx over any entity, `:born`, `:changed`, the story and related as facts (but see §7a).
2. **agent view** (reported ready): view, parts, faces, memo; the agent's page drawn by the agent; the screen's page print deleted.
3. **related-sections:** `(diff prev next)`, the page's patches by part key.
4. **6′, agent loop:** inbox entries (§5); take! writes them; `:from` as a ref.
5. **4b, related-sections and agent view:** the request as §1's function. `kept`, `printed-run` and `as-sent` move into the assembly; `seon.judge` and `seon_context` move onto it; `as-diff` goes. Its proof: re-derivation equals the prefix, across a redefinition. Measured live by live-agents.
6. **4c, agent view:** the debug view re-derives each turn's request from `:seon.turn/sent`'s keys and t.
7. **The simple REPL, agent view:** `:redefined`, `:cites`, `:line` and `:seen` out of `seon.repl` into the eval part's render and the assembly (§2); the tool answer's ripple (§3).
8. **Slice 5, agent view:** every other page off the walk.

## 7. Open, for root
- **(a) The story, related and similar: facts or renders?** Slice 1 writes `:seon.run/story` and `:seon.task/related` as facts. The owner's latest ruling makes such lookups renders, memoised by value. My recommendation is renders read as of the part's t, memoised by content key, with the facts deleted. A fact here is a second stored copy of a derivation, and a render as of t is already byte-stable (§1).
- **(b)** Whether a red verdict wakes a parked session (§5).
- **(c)** The independent review of this revision, before any code.
