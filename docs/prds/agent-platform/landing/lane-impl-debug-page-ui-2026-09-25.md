---
type: landing
lane: impl-debug-page-ui-opus
status: "Agent context-graph page readable: grouped by level, collapsible nodes, AI|HTML faces side by side, counts and N selector; two page-breaking defects fixed (walk contract 500, unscrollable page) and the agent HTML face renders."
---

# The agent debug page, made readable

`/agent/{id}/debug?n=0..3` renders `seon.render.walk/graph` from the agent entity. This change keeps that data
flow and the one render path (`render/render-call` per face; the floor printer is the only clipper) and changes
only the page's layout, labels and stylesheet. It also fixes three producers that stopped the page from working.

## Broken things first

1. **Every page returned 500 after root adopted 6ab6f5fb.** The error was `seon.render.walk/joined refused return
   value at [0 2]`. The `:identity` family found `[?e ?a ?t]` over identity attributes, so `?t` was the identity
   value (for example `[32817 :seon.agent/id "root"]`). The contract says `:seon.render.walk/edges`, whose third
   element is an entity id. This never fired before because the S1 lane's `require :reload` had removed the wrapper.
   - **Fix** (`walk.clj:914`, root granted the file): `'[[?e ?a _] [(identity ?e) ?t]]`. `owning-schemas` reads only
     `[t a _]`.
   - **REPL proof:** the query returns `#{[32822 :seon.turn/id 32822] [32817 :seon.agent/id 32817]}` in 0.27 ms warm.
   - **Commit:** e5866b7a9.
2. **The old page could not scroll.** Its `seon-debug` class inherits the namespace-debug rules
   (`.seon-body:has(.seon-debug) {height:100vh; overflow:hidden}`). Measured in Chrome: `scrollTop` stayed at 0, and
   3,654 px of content was clipped to the 855 px viewport. The page now uses its own `seon-graph-page` root, so the
   document scrolls.
3. **The shell refused the page after adoption.** The error was `seon.render.web/shell refused … feed-url`. The page
   now passes `:seon.render.web/feed-url`. Because `::session?` is true, no feed subscription is emitted.
4. **The agent's HTML face was "renderer unavailable" (`:reason :refused`).** `seon.cluster.agent/render-identity-html`
   calls `seon.cluster.status/agents` with the unit, and that function requires `:seon.db/connection`. The page's
   render request did not carry it.
   - **Fix:** the request carries `:seon.db/connection`, which follows the "pass the world explicitly" law.
   - **Proof:** at the REPL, the call renders in 7 ms when the connection is passed and refuses without it.
   - **Pair defect:** the renderer's contract is `[:=> [:cat :seon.render/unit] …]`, so the requirement is invisible.
     It should declare the connection, so that defaults-by-schema-name can supply it (table row 1).
5. **Adoption cost.** `bin/seon init --dev default --changed src/seon/render/web.clj` took 2.4–14.2 s (most runs
   3.4–8.4 s). The profile lists `seon.cluster/refresh-source!` ×3 and `full-source-refresh!`, which is whole-program
   work for a one-file change. This is a platform cost, not this change; it needs root to file an issue.

## What changed

- **Page** (`graph-debug-html`, plus the new `graph-node-label` in `web.clj`):
  - **Sticky header:** the title, N=0–3 segmented links (the current one marked `aria-current`), and one counts
    line. For example: `12 nodes (level 0: 1, level 1: 6, level 2: 5) · 15 skipped · 0 cuts · 0 back-references`.
    Below that are a legend and open-all / close-all buttons.
  - **Levels:** one section per level, headed by its node count and kinds. Within a level, nodes stay in graph
    order, and each shows its global position `#k`, so definitions-before-uses is still readable.
  - **Nodes:** each node is a `<details>`, open at levels 0–1 and closed deeper. The summary gives the position, the
    owning schema, and what names the node:
    - its identity value, taken from `db/identity-attributes`, not guessed from names;
    - or, for a ref identity, `:my.plan/agent → e32817`;
    - or `via :seon.ns/refers` for identity-less owned rows.

    The summary also gives the entity id, and the AI face's size in characters with an estimated token count
    (`seon.ai.tokens/estimate`).
  - **Faces:** the AI face is a `pre` that wraps and keeps its line breaks. The HTML face is rendered as HTML. They
    sit in two columns, stacking into one below 62rem.
  - **Skipped** is a table of owning schema → count ("reached, not shown: the owning schema declares no render
    pair"). It replaces the collapsed floor map `map 3 items, depth 0`. **Cuts** render through their own face, or
    say "None" explicitly.
- **Stylesheet:** 94 lines in `resources/public/css/input.css` under "the agent context graph", using existing
  tokens only. `output.css` is rebuilt with `bin/css` (188 ms). There are no inline styles now; the page had two
  before.
- **Duplicates collapsed:** the page's inline `white-space` style is replaced by the stylesheet, the skipped map no
  longer goes through a second face call, and the page no longer shares the `seon-debug` class. No other page
  computes this layout.

## Evidence

**Screenshots** are in `tmp/impl-debug-page-ui/`:

- Before, rendered from the HTML captured before this change: `before-n0-w1440.png`, `before-n2-w1440.png`,
  `before-n2-w1000.png`, `before-n3-w1440.png`. These show raw refer maps, one-character-wide node labels, and no
  scrolling live.
- After: `after-n{0,1,2,3}-w1440.png`, `after-n2-w1000.png`, `after-n3-w1000.png` (full length), `after-n2-w800.png`
  (single column) and `after-n0-w1000.png`.
- The live check used the owner's Chrome in a new tab: the page scrolls, open-all and close-all toggle all 12 nodes,
  `scrollWidth` 1707 is at most `innerWidth` 1722 (no horizontal scroll), and the refer rows render
  `deftest ← clojure.test/deftest`.

**Page time** over HTTP (curl, three runs each; default pid 39484):

| n | before (ms) | after (ms) |
|---:|---|---|
| 0 | 440 | 34–38 |
| 1 | 580 | 302–334 |
| 2 | 640 | 371–384 |
| 3 | 690 | 385–404 |

Most of the drop comes from item 4: the refused agent face was expensive. The walk alone at n=3 takes 48–75 ms.

**Lines.**

| file | added | removed | net |
|---|---:|---:|---:|
| `web.clj` | 73 | 19 | +54 |
| `walk.clj` | 2 | 1 | +1 |
| `input.css` | 94 | 0 | +94 |
| `web_debug_test.clj` | 12 | 8 | +4 |

`output.css` is generated (+212). Clojure source is +55 net, within the budget. The CSS is presentation-only and
has no Clojure counterpart to delete.

## Root runs (lanes run no tests)

```
bin/test-check default --test seon.render.web-debug-test/the-graph-debug-page-shows-both-faces-in-graph-order-at-every-n
bin/test-check default --ns seon.render.walk-graph-test
```

The web case now asserts four things:

- one `<details>` per graph node, grouped by level and in graph order within each level;
- two faces per node;
- a 200 response;
- no `seon.render.unknown` anywhere on the page, which is the regression for items 1 and 4. `walk/graph` runs armed
  at each n, so it also covers the regression root asked for at n=2.

## Node kinds on the page (n=0..3, agent root) and their render pairs

This is the render-pairs slice's input. "As a node" means: does the pair render the entity as one node in a graph,
not as a whole page?

| kind (owning schema) | seen at | AI/HTML pair | good as a node? | what it should show |
|---|---|---|---|---|
| `:seon.agent/agent` | L0 | yes | AI: yes (the opening's identity forms). HTML: yes after fix 4, but for root it embeds the whole fleet table (`cluster.status/agents`), a page section | Identity card only. The fleet table belongs to the fleet page, and the contract should declare `:seon.db/connection` |
| `:seon.ns/ns` | L1 | yes | AI: yes (`dir` form). HTML: page-shaped (requires list, prose, a source disclosure). For a `seon.*` namespace it dumps the whole namespace page (owner) | Name, steward, counts of definitions/requires/refers, and a link to `/ns/{name}` for the page |
| `:seon.ns.refer/binding` | L2 | yes | Yes: `deftest ← clojure.test/deftest` and a `doc` form | As is |
| `:my.plan/entity` | L1 | yes | Yes (objective, step, counts) | As is. The summary names it through its ref identity `:my.plan/agent → e…` |
| `:seon.runtime/entity` | L1 | yes | AI: yes (pull form). HTML: duplicates the trigger message card, which is also its own node | Trigger as a link or id, plus idle/listening. Drop the embedded message card, since the message is a node |
| `:seon.message/message` | L1 | yes | Yes (card; AI face is a `my.message/read` form) | As is |
| `:seon.turn/turn` | L1 | yes | AI: the whole turn transcript (6,003 chars, about 1,875 tokens, for the first turn), which is what the model reads, so it is honest. HTML: repeats that transcript. Its header `dl` was unstyled (styled on this page). It emits `<section id="surface-transcript">`, a session-page morph id, so two turns give duplicate ids | Header (opened, trigger, evaluations, reply) plus the transcript. It should not emit a page morph id when rendered as a node |
| `:seon.cluster.eval/receipt` | skipped ×8 | **none** | n/a | One line: form, then value or error. Reached from turns; today only the turn transcript shows them |
| `:seon.schedule.task/task` | skipped ×3 | **none** | n/a | Id, schedule, next fire, last result |
| `:seon.render.walk/unowned` | skipped ×4 | no owning schema | n/a | The walk reports no schema; the owner decides whether these targets should be modelled |

Declared owning schemas **without a pair** that other graphs reach (from `graph-structure` on default) include:

- `:seon.task/task`
- `:seon.schema/schema`
- `:seon.test.run/run`
- `:seon.test.failure/failure`
- `:seon.error.occurrence/occurrence`
- `:seon.fn.arity/row` (joined through by design)
- `:seon.lint/finding`
- `:seon.schedule/schedule`

There are 61 in all. Pairs exist for 23, including `:seon.fn/fn`, `:seon.test/test`, `:seon.issue/issue` and
`:seon.error/error`. On this store, root's graph reaches none of those.

**Answer to "is it that we have almost no render functions?"** Partly.

- Every kind on root's page has a pair, and the unreadability was mostly layout: no scrolling, a table with a
  one-character label column, and floor maps where refer rows were not yet adopted.
- Two pairs are page-shaped rather than node-shaped: the namespace pair, and the agent pair for root.
- Two duplicate content that is already its own node: the runtime pair (the message card) and the turn pair (the
  transcript in both faces).
- Tasks, schemas, receipts, test runs and error occurrences have no pair, so any task-rooted graph will show them
  only as skipped counts.

I edited no pair: each pair's file belongs to another owner, and none of the fixes is a one-liner.

## Limits

- The page walks from an agent only, and default has one agent (root). Task-rooted graphs of 400–2,000 nodes were
  not rendered. At about 25 ms per node for faces, they would take seconds.
- In the owner's Chrome the window would not resize below the screen width (`innerWidth` stayed 1722), so the
  1,000 px and 800 px views come from headless Chrome captures of the live endpoint.
- Tests were not run (lane load rule); the commands are above.
