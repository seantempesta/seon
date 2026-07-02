---
type: research
status: completed
tags: [research, agent, ui, dashboard]
---

# Canvas drive validation — canvas-first SAFE, my.ui/my.tile prominence REGRESSED

Hermetic gym drive of the two `:ui`-competency canvas scenarios
(`canvas-budget-breakdown` + `canvas-goal-board`) on the real DeepSeek adapter,
`{:seon.gym/allow-paid? true}`, scratch `:memory` conns — no live-pod touch, no
Core-file edits. Two goals: (1) validate the canvas-first work (does the agent
drive its canvas now?), and (2) produce the my.ui/my.tile PROMINENCE EVIDENCE
Core asked for before adding them to `canonical-full-my-ns`.

Run: `SEON_AI_PROVIDER=deepseek bin/gym --paid=canvas-budget,canvas-goal`.
Build compiled from `111e4d47` (HEAD at run start); the gym stamps a per-run
working-tree sha (cards: budget `7b465a5c`, goal `1e5965f4` — peers committed
on the shared tree mid-run). Durable cards:
`tmp/gym-paid-card-canvas-budget-7d03eea5-….edn`,
`tmp/gym-paid-card-canvas-goal-7f1218eb-….edn`.

## TL;DR — the two verdicts

1. **Canvas-first: SAFE.** `canvas-updated?` rose from the Phase-A all-false
   baseline to **TRUE on BOTH** scenarios. Both agents defined a tile fn and
   wired it onto `:seon.render.live-tile/content` as their primary surface. The
   canvas-PRIMARY live-tile block (`fe83c76c`) lands even on a weak model.
2. **my.ui/my.tile prominence: REGRESSED (worse than expected).** Both agents
   composed `my.ui` / `my.tile` **0×** and **hand-rolled raw `[:div]…[:table]`
   hiccup** with guessed CSS classes. The premise was "my.ui/my.tile are
   signature-trimmed (worked example elided)" — the reality from the prompt
   blobs is sharper: **`my.ui` and `my.tile` do not appear in the rendered
   namespaces AT ALL** (not even signature-trimmed). This is the drive-evidence
   → route to Core: this is exactly peer finding **#72** (`7b465a5c`,
   supersedes #70). Add `:my.ui` (+ `:my.tile`) to `canonical-full-my-ns`.

## The numbers

| scenario | canvas-updated? | toolkit-calls {my.data my.ui my.tile} | eval-error-rate | driven turns | pass? | judge |
|---|---|---|---|---|---|---|
| canvas-budget-breakdown | **true** | **{15, 0, 0}** | 0.0 | 3 | true | **fail** (prose totals wrong, canvas correct) |
| canvas-goal-board | **true** | **{0, 0, 0}** | 0.0 | 9 | true | **pass** (named all 3 goals + status) |

Mechanical axes both green: `:drives-canvas true`, `:replies-honestly true`,
`:terminates true` (+ `:models-work-directed true` on goal-board).

## Verdict 1 — canvas-first SAFE (rose from the false baseline)

Both agents drove the canvas, unprompted (nothing in the scenario says "use a
tile"; the standing live-tile block + `ui-live-tiles` skill teach the medium):

**budget** — defined `expense-tile` (re-deriving tile fn) and wired it:

```clojure
(db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id (db/current-agent-id)
     :seon.render.live-tile/content 'my.agent.YnV-2606282145/expense-tile}]})
;=> {:seon.db/ok? true … :seon.db/added 7 …}
```

**goal-board** — designed a `:my.goal/*` schema (`id`/`title`/`status`
enum/`note`), seeded the three goals, defined `goal-board` (queries the goals,
renders a status table), wired it as a fn symbol. Planning shape too: it used
`todo/plan!` with a `schema → seed/tile → verify` dependency tree.

Both set a fn SYMBOL (not a literal hiccup snapshot) — the re-derive-don't-store
doctrine landed. `:drives-canvas` PRESENT on both post-run stores. **Headline
gap closed.**

Bonus signal FOR canvas-first: on budget the judge FAILED because the agent's
PROSE reply said "groceries $155 … transport $77" (wrong), while its
`my.data`-derived TILE shows the correct $136 / $106 / $79. The toolkit-derived
canvas was MORE accurate than the hand-narrated prose — and the judge only sees
the reply text, not the canvas (the known `judge-ctx` flag below). Canvas-first
is the honest medium precisely because the prose is where the model fabricates.

## Verdict 2 — my.ui/my.tile prominence REGRESSED → route to Core

Both agents HAND-ROLLED their tiles. They never called a single `my.ui` or
`my.tile` verb, despite `my.ui` having exactly the helpers the task needed
(`table`, `section`, `kv-table`, `status-line`, `badge`).

**budget hand-roll** (raw table, manual Tailwind):

```clojure
{:seon.render/hiccup
 [:div {:class "flex flex-col gap-3 p-4"}
  [:h2 {:class "text-lg text-text-50"} "Expenses by Category"]
  [:table {:class "w-full text-sm"} …
   [:th {:class "w-[140px]"}] …]]}
```

**goal-board hand-roll** (raw table + hand-invented badge classes):

```clojure
status->badge {:not-started [:span {:class "seon-badge seon-badge--muted"} "○ not started"] …}
{:seon.render/hiccup
 [:div {:class "seon-tile"}
  [:h2 "Q3 Goals"]
  [:table [:thead [:tr [:th "Goal"] [:th "Status"] [:th "Note"]]]
   [:tbody (map goal-row goals)]]]}
```

This is not just a stylistic miss: the guessed classes (`text-text-50`,
`w-[140px]`, `seon-badge--muted`) are NOT in the runtime safelist, so the
hand-rolled tiles risk INVISIBLE content — exactly the failure `my.ui`'s
safelisted dual-render helpers exist to prevent. `my.ui/badge` +
`my.ui/table` would have emitted real classes.

### Why they couldn't compose it — they never saw it

The agent saw `my.ui`/`my.tile` only as a one-line NAME-DROP in the live-tile
block ("`my.ui` — dual-render status-line / kv-table / section …"). Reading the
persisted prompt blobs, the rendered `:namespaces` body contains:

- `my.data` — FULL (it is in `canonical-full-my-ns`) → composed **15×** on
  budget. The contrast proves the mechanism: a toolkit ns that renders full
  gets USED; one that doesn't, doesn't.
- `my.kb` FULL; `my.kb.author` / `my.kb.finding` / `my.kb.shared` /
  `my.kb.source` / `my.skills` / `my.expense` as `(signatures)` blocks.
- **`my.ui` and `my.tile`: ABSENT.** Not full, not signature — not rendered.

So this is NOT the task's premise ("signature-trimmed, worked example elided").
my.ui/my.tile produce NO block at all. Signature rendering itself works (the
`my.kb.*` sig blocks render fine), so the cause is specific: **my.ui/my.tile
have no indexed `:seon.fn` rows** → the signature render has nothing to emit →
the block is dropped. This corroborates peer finding **#72** (`7b465a5c`:
"toolkit not reachable — client.cljs doesn't index it; my.data/my.ui/my.tile
with ZERO indexed fns").

### Precise routing to Core

`my.data` renders full ONLY because it is in `canonical-full-my-ns` — full
render reads the stored `:seon.ns/source` file text (stored for every `my.*` via
`full-source-ns?`), which BYPASSES the missing-fn-index gap. So the same fix
works for the static/interactive toolkit:

- **Add `:my.ui` and `:my.tile` to `canonical-full-my-ns`** (`namespaces.cljs`,
  currently `#{:my.kb :my.data}`). They will then render FULL from their stored
  source regardless of whether fn-indexing is fixed — the immediate, proven
  lever (same recursion that fixed `my.data` in `c8f064e6`). The
  `canonical-full-my-ns` docstring already names this as the trigger
  ("extend to my.ui/my.tile if their drives show the same named-but-not-composed
  regression") — this drive is that evidence.
- The deeper #72 fix (index the toolkit's fns so even signature render works)
  is Core's call; not required for the prominence win, but it's the root cause
  of the ABSENT-not-trimmed observation.

## Flags to Core (out of this lane)

- **judge-ctx can't see the canvas** (re-confirmed, budget). The `:llm-judge`
  grades only the agent's REPLY text (`agent-reply-text`), so a correct CANVAS +
  wrong PROSE scores judge-fail. To grade the canvas, `judge-ctx`
  (`test/seon/gym/driver.cljs`) must append the resolved tile ai-render
  (`render/render-agent-tile` → `:seon.render/ai`). Did NOT edit the driver
  (concurrent owner). Until then the canvas-budget judge-fail is a judge blind
  spot, not an agent error.
- **`todo/plan!` `:after`/`:ref` friction** (goal-board): the agent burned 3
  eval rounds because `:after` labels must match a sibling's `:ref` and the
  error didn't make the agent add `:ref`s until the 4th try. Orthogonal to the
  canvas axis (eval-error-rate still 0.0 — these are envelope `ok? false`
  values, not failed evals), but a discoverability rough edge in the plan! API.

## Suite note

`bin/gym` ran the full free CLJS suite alongside the paid drives: 794 tests,
3599 assertions, 4 failures + 1 error — these are the pre-existing gym-harness
self-test reds (`gymtest-attr-fork`, prompt-blob-range, `deliberately-broken`
envelope) that the harness asserts as honest reds, unrelated to this drive.
Exit 0.
