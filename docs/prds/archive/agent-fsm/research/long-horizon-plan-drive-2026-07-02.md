---
type: research
status: active
tags: [research, agent]
---

# Long-horizon plan-drive experiment — todo tree vs the need for explicit PLANS

Design-grounding for the owner's question: can a real agent sustain a
long-horizon task across many runs and a pod restart, staying grounded via
(a) durable plan items (`seon.agent.todo`), (b) the windowed transcript, and
(c) its purpose — without re-planning or flailing? And precisely where does
grounding fail?

## Method

One child agent (`Lky-2607021145`, purpose "build a small personal-finance
tracker for the human") was minted under `root` on the live default pod
(7890), driven with real DeepSeek turns over four message legs: (1) the
multi-step task, framed explicitly as spanning several sessions; (2) a
"keep going" nudge; (3) an intentional `bin/seon restart pod`, then "back
again — where were we?"; (4) the final probe question "what's my top
spending category?". Evidence was pulled from live `:seon.eval` rows,
`:seon.agent.message` rows, `seon.agent.todo/tree`, and pod logs — not
inferred.

Seeded ground truth (told to the agent once, in the leg-1 message): coffee
$5.50/dining, groceries $84.20/groceries, gas $42.10/transport, Netflix
$15.99/subscriptions, lunch $12.75/dining, hardware store $63.40/home, gym
$40.00/health, dinner $58.30/dining. Manual sum: dining $76.55, groceries
$84.20, transport $42.10, subscriptions $15.99, home $63.40, health $40.00
— grand total **$322.24**, top category **groceries**.

## Timeline (verbatim turn ledger)

| Leg | Turns | Wall time | Halt verb | What happened |
|---|---|---|---|---|
| 1 — task | 0–4 | ~2 min | `complete` | Laid a full nested plan **up front**, executed all 5 steps, self-caught and corrected an arithmetic error mid-run, delivered final answer |
| 2 — "keep going" | 5–9 | ~2 min (spans an unrelated concurrent hot-reload) | `complete` | Correctly recognized the plan was already done, asked what to add, then proactively planned + built two enhancements + stored knowledge facts |
| 3 — restart, "where were we?" | 10–18 | ~5.5 min | `wait` | Flailed: closed the address-todo before verifying anything, redefined the same broken tile fn **three times**, wrote several turns of unparsed sentence-shaped "forms" instead of `;` comments, eventually self-diagnosed the real bug and recovered |
| 4 — "top category?" | 19–23 | ~40s | `complete` | Answered correctly, then re-stated the same answer a second time before halting |

Full source, in order (leg 3, the restart leg, is the interesting one —
included verbatim):

```
11:52:54  (todo/done! {:seon.agent.todo/id "xye-2607021152"})   ; the auto-minted "where were we?" address-todo, closed WITHOUT first re-grounding
11:53:43  ""                                                     ; empty/prose-only turn
11:54:13  "(turn 11, loops 2-23) without ever actually responding to the human. The live
           tile is also broken because `!month-filter` is an atom that doesn't persist across..."
                                                                   ; self-diagnostic narration, itself malformed as a form
11:54:13  (ns my.agent.Lky-2607021145 (:require ...))            ; moved into its own personal namespace
11:54:22  (db/query {:seon.db/query '[:find (count ?e) . :where [?e :my.expense/id _]]})
                                                                   ; the ONE genuine grounding act: verified the 8 rows survived the restart
11:55:35  (def !month-filter (atom nil))                         ; fix attempt #1
11:55:41  (defn monthly-summary-tile ...)
11:55:57  (ns my.expense ...)
11:56:09  (def !month-filter (atom nil))                         ; fix attempt #2 — IDENTICAL to #1
11:56:16  (defn monthly-summary-tile ...)
11:56:21  (ns my.expense ...)
11:56:28  (def !month-filter (atom nil))                         ; fix attempt #3 — IDENTICAL to #1 and #2
11:56:33  (defn monthly-summary-tile ...)
11:56:40  (message/user "Welcome back! ... I fixed a bug where the tile crashed on session restart ...")
11:57:13  "(reorient them and confirm the tracker is alive)"     ; another unparsed sentence-as-form
11:57:14  "(8 expenses, $322.24 total, tile rendering with forms)"
11:57:24  "(I sent message EHN-2607021156)"
11:57:39  "(message EHN-2607021156)"
11:58:12  (wait "Tracker is live and stable — waiting for human's next instruction")
```

Root cause of the 3× identical fix: the restart hot-reload's replay left
`my.expense/!month-filter` (a bare `(def !x (atom …))`) undefined — logged
at boot as `log-replay-failure!: my.expense/!month-filter is not defined`
— and the live tile fell back to the unbounded render path with a
"could not run under SCI bounding" warning. This is the documented CLJS-pod
gotcha (`STATE ACROSS TURNS` in the system prompt: "A bare `(def x 42)`
does NOT survive being read back on a later turn"), and the agent's own
earlier code tripped it. The agent re-issued the exact same fix three times
across three turns without ever confirming turn-to-turn whether the
previous attempt had actually resolved the warning — each attempt was a
blind retry, not a verified retry.

## Where grounding held

- **Plan-laydown up front (leg 1): solid.** `seon.agent.todo/plan!` was
  called once with the full nested tree (parent "Build expense tracker" →
  4 named children, one further nested for schema-design substeps) before
  any implementation work started. This matches the intended behavior
  exactly.
- **Step-closing discipline: solid.** Every `todo/done!` call in the ledger
  corresponds to a real completed unit of work; no todo was marked done
  without the matching artifact existing (schema registered, expenses
  transacted, fn defined, tile wired).
- **Self-correction within a run: solid.** Leg 1 caught its own arithmetic
  error ($322.54 vs $322.24) unprompted and issued a correction message in
  the same run.
- **Data-level grounding after restart: present, but singular.** The ONE
  action that actually re-established state after the restart was a direct
  Datalog count query (`:my.expense/id`), not a todo read. The agent did
  not query `todo/tree` or `todo/list-open` at all during leg 3 — there
  was nothing to list (all todos were done and had already vanished from
  render), so it reached for the database instead. That worked, but it
  means the "todo tree" contributed **zero** grounding signal for
  resumption in this run; the raw store did the grounding.
- **Final answer: correct.** Verified against the seeded ground truth —
  groceries $84.20 top, $322.24 grand total, matches a manual sum exactly.

## Where grounding failed

1. **The todo tree renders only what's OPEN — it has no memory of what
   happened.** Per the system prompt: "Your open todos render every turn
   with their ids; once none remain, that section vanishes — your
   done-signal." Once a plan is complete, its trace disappears from
   context. There is no "recently completed" summary, no plan history, no
   render of the SHAPE of what was built. This is fine when the same run
   is still live (the transcript still has it), but it means a plan
   carries **no durable memory of its own completion** across a context
   reset — only "is there work I can see" (no) vs. "is there work done"
   (invisible).

2. **No position anchor anywhere in context.** A full `ctx-preview` dump
   (~230K chars / ~57K tokens) was searched for "you are here", "position",
   "anchor" — zero hits. The context has a live-tile section, a
   skills-catalog, a namespaces block, the todo block, and the transcript —
   but nothing that says "you are currently at step N of M, having just
   done X, about to do Y." The closest thing to a position signal is (a)
   the open-todo list (which is empty once done) and (b) the raw
   transcript tail (which decays: `::result-decay` clips eval bodies to
   200 tokens after a 5-turn offset, and `::turns-retained` defaults to
   8 turns of window). Neither is a stable, explicit "current position in
   the goal" — both are incidental byproducts of other mechanisms.

3. **Todo completion was recorded before verification, twice.** In leg 3
   the very first act was `(todo/done! {... "xye-..."})` closing the
   auto-minted "where were we?" address-todo — before any query,
   before reading the transcript, before checking anything. The
   done-signal fired on receipt of the message, not on having actually
   re-grounded. This matches a generic risk of todo-as-signal: "done" only
   means "I performed some closing action," not "I verified the claim I'm
   about to make is true."

4. **Blind repeated fix attempts — no verify-between-retries loop.** Three
   turns in a row re-issued the byte-identical `(def !month-filter (atom
   nil))` + tile redefinition, each as a full turn (an LLM round-trip), with
   no eval in between that checked "did the SCI-bounding warning clear?"
   The todo tree offered no help here because this ad hoc repair was never
   turned into a todo — it was reactive, off-plan work, invisible to the
   plan-completion signal entirely.

5. **Sentence-shaped non-forms leaking into the eval stream.** At least
   four turns in leg 3 wrote what is clearly internal reasoning wrapped in
   parens as if it were code — `(reorient them and confirm the tracker is
   alive)`, `(I sent message EHN-2607021156)`, `(8 expenses, $322.24 total,
   tile rendering with forms)` — the exact anti-pattern the `/repl` skill
   calls out ("Wrong shape: Let me look around first." vs `; ` prose). This
   is a known REPL-discipline failure mode, not new, but it appeared
   specifically during the disoriented restart-recovery window and nowhere
   else in the 23-turn run — suggesting disorientation degrades REPL
   discipline, not just task discipline.

6. **Redundant final answer.** After answering correctly at turn 19, the
   agent took another full turn (20) to restate the identical answer
   before finally halting at turn 23 (after two more near-empty turns) —
   a mild instance of the CLAUDE.md-documented failure "re-confirming a
   value you already computed... burns turns on a finished task," this
   time surfacing in the child under study rather than in a Claude Code
   session.

7. **The multi-session framing itself was not honored.** The task said
   "you won't finish in one sitting, and I may interrupt you" — explicit
   pacing language. The agent ignored the pacing hint and raced to
   `complete` in 4 turns / ~2 minutes. Nothing in the todo mechanism (or
   the context generally) represents "this is meant to span multiple
   sessions" as a constraint on execution speed — a plan tree has no slot
   for "don't rush this," so there was nothing to hold the agent back even
   though it had been told to expect interruption.

## What a plan needs that todo lacks (derived strictly from observations)

Candidates evaluated against what was actually seen:

- **Goal narrative — CONFIRMED needed.** The todo tree has titles only
  ("Design expense schema and register it") with no `why` — nothing
  survives to say "the overall goal is a usable expense tracker the human
  will keep using across sessions," which is exactly the framing that got
  lost when the agent treated "keep going" and "where were we" as
  isolated todo-closing events rather than checkpoints in one longer
  narrative.

- **Expected-outcome per step — CONFIRMED needed, directly implicated in
  finding #4.** Nothing records "after this fix, the tile should render
  without the SCI-bounding warning." Without a stated expected outcome per
  step, there is no failure signal to check against, so the same
  ineffective fix repeats. A plan step with `{:expected-outcome "..."}`
  would give the agent something concrete to re-verify before closing
  (or retrying) a step, instead of closing on "I performed an action."

- **Ordering — NOT clearly implicated.** The nested plan/children shape
  already carries ordering (parent→children, sequential in this run), and
  nothing in this drive showed mis-ordering. Not a priority finding here.

- **Current-position anchor — CONFIRMED needed, the single strongest
  finding.** Zero occurrences of any position signal in ~57K tokens of
  rendered context. The todo tree's "open items" is the closest proxy, and
  it is not adequate: it goes silent exactly when there's the MOST need
  for grounding (a completed plan, revisited after a gap) because
  completed work is invisible. A plan needs an explicit, persistent "you
  are here" line — e.g., "last completed: <step>, at <time>; overall
  status: <N/M steps done>; next: <step-or-none>" — rendered every turn
  regardless of open/closed state, so a post-restart agent reads its
  position instead of re-deriving it from a database count query (which
  worked here, but only by chance — it verified ROW EXISTENCE, not GOAL
  STATE).

- **A durable "recently completed" trace — NEW candidate, not in the
  original list but forced by the data.** The todo mechanism's
  vanish-on-done behavior is a *feature* for reducing clutter but a
  *liability* for resumption. A plan render should keep a short tail of
  recently-closed steps (with timestamps) rather than dropping them the
  instant nothing is open — this is what would have let leg 3 open with
  "you last touched this 5 minutes ago, having just shipped the
  enhancement leg — nothing is currently broken as far as your plan
  knows" instead of an unverified `todo/done!` followed by 8 turns of
  discovery.

- **A pacing/scope constraint — NEW candidate.** The task said "you won't
  finish in one sitting." A plan structure that can carry an explicit
  pacing note (or a per-leg budget) would give the agent a reason not to
  race a multi-session task to completion in one run — todo has no
  concept of this at all.

## Bottom line for design

The todo tree performed exactly as designed for **live, single-run**
execution: plan-laydown and step-closing were both clean and honest. It
provides **no** grounding value across a genuine context discontinuity
(restart, multi-session gap) once the plan is complete, because completion
means the plan's render vanishes. The single successful piece of
post-restart grounding in this whole experiment was an ad hoc raw-data
query the agent improvised, not anything the todo/plan mechanism surfaced.
The owner's instinct is confirmed by this run: todos need to evolve into
plans carrying (a) a goal narrative, (b) an expected outcome per step, and
critically (c) a persistent, always-rendered current-position anchor that
does not depend on anything still being open — plus, newly surfaced by
this data, (d) a short "recently completed" tail and (e) a way to encode
pacing/scope so a multi-session task isn't collapsed into one greedy run.
