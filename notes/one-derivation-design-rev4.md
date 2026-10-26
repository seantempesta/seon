# One derivation, revision 4: the request from the db, a simple REPL, the wire, the hiccup

Agent view. Revision 4.1, approved by root on 2026-10-26 with its rulings on (a) and (c) folded in. It folds in the independent review (`notes/one-derivation-design-rev4-review.md`: approve with changes). For root's approval before any code (AGENTS §2). It keeps revision 3's five functions (`notes/one-derivation-joint-design.md`): `(ctx db e)`, `(view ctx)`, `(html part)`, `(ai part)`, `(diff prev next)`. Slices 1 and 2 are built (jolt/ctx-atom; jolt/view-parts, reported ready). It replaces revision 3's request assembly, its wire and its "told" changes with the owner's rulings of 2026-10-26:
- the request is a pure function of the db at the session's turn points;
- the REPL only evaluates and records;
- the tool answer carries an eval's ripple effects;
- outside changes are inbox entries;
- enrichments are renders, and their hiccup is designed with care.

Line numbers are main's at 51c641288's base.

## 1. The spine: the request for turn n is a function of the db and the session's config
```
request(n) = system(t0) ++ start(t0) ++ Σ i<n [ assistant(i) ++ tool(i) ++ world(i) ] ++ world(n)
  start(t0)  = (ai part) of each part of (view (ctx db@t0 agent)), in view order
  assistant(i) = turn i's prose and eval calls, from its turn record
  tool(i)    = (ai part) of each eval turn i made, as of that eval's t
  world(i)   = the entries turn i took and ctx's diff since turn i-1 (§5), as of turn i's t
```
- **The inputs are the db at the turn points, and the config in force.** Every term is read from the db as of its t, and the request is rebuilt from the db each turn as today (turn.clj:270, its basis turn.clj:198-208).
  - A turn point is recorded as a commit uuid (facts.edn:438). The request reads its t from that commit, and `:seon.turn/sent` records it.
  - Config is a file, not the db (config.clj:82), and renders read it (context.clj:155, 180, 221, 636; repl.clj:401). It is named here as an input: a config change is a change of input, and it shows as drift (below).
- **The guarantee: a byte prefix within a session, except at the history cut's jumps.**
  - Within one process the code is fixed, so each term rendered as of its t renders the same bytes every time.
  - The live request also holds its previous request's terms in an in-process content cache. A turn costs its new terms.
  - The history cut (`kept`, context.clj:527) jumps once when its budget fills, by design. At that turn the prefix changes, once (revision 3's point 8, measured: cache breaks 23 → 1).
- **Across a process (a resume, a new generation, the debug view): drift is detected, not prevented.**
  - `:seon.turn/sent` keeps each term's key, mode (whole, line, entry), t and **the digest of its bytes as sent**. It never keeps the text.
  - Each turn also records the **digests of its inputs**: the config in force (already an input digest, `seon.program`'s test inputs) and the code that drew it (the printer's and the render's definitions, by their content digests in the program db). A drifted term then names its cause: the config, printer code, or agent code (root's approval, 2026-10-26: close the gap at its causes, not with a guard).
  - A re-derivation renders each term with today's code and compares digests. Where they differ (a render, the printer or config changed), the debug view shows the term as re-rendered and says it drifted.
  - A resumed session's first request is a new starting page (its prefix starts again, a deliberate break, as at a stage boundary).
  - There is no loading of old code: running an old render against today's printer would not give the old bytes anyway, and root would run old agent-written code. The review's finding, and the reason the earlier "renders resolve as of t" is withdrawn.
- **Cost:**
  - Live: the new terms of a turn.
  - A re-derivation renders the session's terms once, and each eval's ripple (§3) reads two views, at its t-1 and its t.
- **Proofs** (seon.context-test, slice 4b):
  - Within one process, re-deriving turn n-1's request from the db is a byte prefix of turn n's, outside the cut's jumps.
  - Across a render redefinition, the re-derived term's digest differs from the recorded one, and the debug view says it drifted.

## 2. The REPL only evaluates and records

An eval is code in, and its record out: value or values, `*out*`, `*err*`, the error that stopped it, its cost, and the transaction it made (its t). Nothing else runs inside an eval. Everything that enriches what is shown is a render over facts (§3), drawn when its part is drawn and memoised by value.

**Today's response and record keys** (`seon.repl/response`, repl.clj:331-356; `record-details`, repl.clj:376-410; the line numbers are the review's, at view-parts' head):

| key | today made by | under this design |
|---|---|---|
| `:handle :value :values :out :err :ex` | the eval | **stays**: what it answered |
| `:ms :ticks :bytes`, `:fns` | the profiler around the eval | **stays**: its cost, one line in the answer |
| `:recorded` | `wrap-record`'s account (repl.clj:234) | **stays**: what it defined, which is its transaction |
| `:redefined` | `program/meaning-changes` inside the eval, before its transaction applies (repl.clj:232) | **goes to the eval part's render**: it reads the eval's transaction datoms (each carries `:seon.tx/eval`, agent.clj:161) and the program db as of t-1 |
| `:cites` | `ref/references` over the prose at record time (repl.clj:389) | **goes to the render**: the references are parsed when the eval part is drawn, against the db as of t-1, memoised by value |
| `:line` | `print/line` at record time (repl.clj:401) | **goes to the render**: the `:inline` face |
| `:printed`, `:type` | a value that is no plain data, printed at record time (repl.clj:393-398) | **stays**: such a value cannot be stored as data, so its print is its record (named as the one text a record keeps) |
| `:contract`, `:warnings` | computed per form, then **dropped by `response`** (repl.clj:331-356) | **a defect**: the uncontracted-definition warning never reaches the agent; filed as its own task |
| `:seen` (agent state) | `print/seen` after each eval (repl.clj:458) | **goes to the assembly**: folds are its rule (§1) |
| `:same-as` | `printed-run` (context.clj:465-503) | **already the assembly's**; it stays there |

**What still runs inside an eval, and stays:** the evaluation's own mechanics. These are `wrap-instrument` (contracts on what it defines), `wrap-caught` (the error fold), `keep-source!`, the namespace-metadata fix, the `result/eN` intern (how a handle is read) and `with-branch-schemas`. None of them enriches what is shown.

**Outside callers** (`seon_eval`, which reads `:text` at once): the server draws the eval part, so they get the ripple too.

**Effects that move to renders**, with file:line:
- **The eval's ripple effects:** what it redefined, the sections citing what it redefined, and what parts re-rendered. Today this is `:redefined` (repl.clj:233). It becomes the eval part's render over its tx datoms and the program db as of its t.
- **Cited references:** today `ref/references` at record time (repl.clj:381). They become the render of the eval's prose and of the task's plan.
- **The story, related sections and similar functions** (`run-state`, context.clj:280; `related`, context.clj:187; `similar`, context.clj:143): **derived members of ctx as of t** (§7a, the review's recommendation). ctx's build and patch compute them from the db, including the vectors they read (`related!` already keys on `vectors-t`). Renders stay pure in the part's value, the memo stays valid, and the stored facts `:seon.run/story` and `:seon.task/related` (slice 1) go.

## 3. The wire: one meaning per role (the API's four roles, ours to fill)

| role | carries |
|---|---|
| system | the rules: the help page, pinned per session |
| assistant | the agent's prose and its eval calls |
| tool | the REPL's answer to one call: the eval part's `ai` face, with value or values, out, err and the error, then its **ripple**: what it redefined (each contract or docstring change), the sections that cite what it redefined, the keys of the parts its transaction changed, and every ctx path it changed. Then its cost on one line. The ripple is read at that eval's t (each eval commits before the next, turn.clj:210-218), and it leaves out the eval's own conversation part. |
| user | the world, one message per turn, in fixed sections in a fixed order: `[from <sender>]`, each message taken (a person's or root's words); `[changes]`, ctx's data diff of every other entry taken (§5), as paths and new values; `[page]`, the parts whose rendered text changed, by key. An empty section is left out; "nothing" when all are empty. |

**Who caused a change decides where it is told, once:** a change caused by the agent's own eval is in that eval's tool answer, and anything else is in the world message. Never both.

**One recorded eval's tool answer, with its ripple** (a1, `(defn top-bar …)` redefined, as the render would print it):
```
result/e14 ⇒ #'seon.render.ui/top-bar
redefined: seon.render.ui/top-bar  docstring changed · contract [:=> [:cat :any] :any] → [:=> [:cat :any :boolean] :any]
cited by: docs/render.md#the-page
parts changed: [:task] (its write set cites top-bar)
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

## 5. The world message: entries taken, and ctx's diff (slice 6′, the agent loop's)
- **One rule, no exception.** The world message of turn i holds:
  - the **entries** turn i took: words addressed to the agent, from a person (`:message`), from root at a stage boundary (`:stage`), or from the judge when it sends the task back (`:verdict`: red, with the done rule's reasons; root's ruling, 2026-10-26);
  - ctx's **diff since turn i-1** (`seon.ctx/patch`'s `:changed` paths and their values, read as of turn i's t), less what the turn's own evals caused (told in their tool answers, §3). That covers proof results, a verdict, main moving, and anything else that changed in ctx.
  - Every change reaches the agent exactly once, by the same differ.
- **An entry carries facts, never a stored diff.** It is a message fact: `:seon.message/kind` (`:message`, `:stage`, `:verdict`), `:from` (a ref, which needs a store erase at root's moment), `:body` (required, as today). Its diff is computed when it is taken, by the patch, as for everything else. A green verdict that ends the run and main moving are not entries: they are facts that reach ctx, so they arrive in the diff. A red verdict that sends the task back is an entry: the agent must hear why its done was refused, or a parked agent stays stuck (root, 2026-10-26).
- **`take!`** takes the entries at the turn's start (invariant 1) and writes each on the branch in the taking turn, upserted by id, so the conversation orders it by that t.
- **Wake:** a parked session wakes on an entry: `:message`, `:stage` or a red `:verdict`. Main counts the waking entries, and so does `:seon.run/taken`, with a kind filter in both wake counts (reconcile.clj:114-119 and 714-723), like with like. A green verdict and main moving wake nothing.
- **The stage boundary** is today a request (reconcile.clj:832). Under this design it becomes a `:stage` entry, a fact, placed after the earlier stages' entries (invariant 2).
- **Agreed with the agent loop (2026-10-26):**
  - the diff is the patch's `:changed` paths with their values after the producer's transaction, with no second differ;
  - taken counts waking entries;
  - merges are written on the branch in the merge commit.

## 6. Slices, revised
1. **ctx-atom** (built): ctx over any entity, `:born`, `:changed`, the story and related as facts (but see §7a).
2. **agent view** (reported ready): view, parts, faces, memo; the agent's page drawn by the agent; the screen's page print deleted.
3. **related-sections:** `(diff prev next)`, the page's patches by part key.
4. **6′, agent loop:** `:message` and `:stage` entries (§5); take! writes them; the kind filter in both wake counts; `:from` as a ref; the stage boundary as an entry.
5. **4b, related-sections and agent view:** the request as §1's function, with `:seon.turn/sent` keeping each term's key, mode, t and digest. `kept`, `printed-run` and `as-sent` move into the assembly; `seon.judge` and `seon_context` move onto it; `as-diff` goes. Its proofs are §1's. Measured live by live-agents.
6. **4c, agent view:** the debug view re-derives each turn's request from `:seon.turn/sent`, and shows drift by digest.
7. **The simple REPL, agent view:** `:redefined`, `:cites`, `:line` and `:seen` out of `seon.repl` into the eval part's render (as of t-1) and the assembly (§2); the tool answer's ripple (§3); the server draws the eval part for outside callers. Also: the story, related and similar as derived members of ctx, with ctx-atom.
8. **Slice 5, agent view:** every other page off the walk.

## 7. Open, for root
- **(a) Byte stability across processes** (approved: drift detected and shown, each turn's input digests naming its cause). The owner asked for the request to be byte-stable from the db's t. Within a session it is, by construction, except at the cut's jumps. Across a resume or a new generation, today's code renders, and drift is detected by digest and shown, not prevented. Loading old code cannot make it stable (§1). The owner should know this is the honest form of the guarantee.
- **(b)** Ruled (root, 2026-10-26): a red verdict that sends the task back is a `:verdict` entry and wakes; a green one ends the run and wakes nothing.
- **(c)** The dropped `:contract`/`:warnings` (§2), filed as a task.
