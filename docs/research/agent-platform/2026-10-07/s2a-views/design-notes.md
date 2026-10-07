---
type: design
status: v4, root-ruled (ledger option A; golden deleted; gaps 1-2 owned by S4; gap 3 by a printer lane). Code waits on transcript.clj (s5a-reply)
created: 2026-10-07
tags: [design, agent-loop, deletion, debug]
---

# S2a: one debug view of the agent's context (lane s2a-views)

**What exists.** The agent debug page (`/agent/<id>/debug`, `seon.render.web/debug-response`) shows the old prompt
assembly five ways: the turn session ("Context at turn N"), the outline, the ledger's "Full context as sent", the
"context as sent" panel and the walk graph page (`?n=`). Its default view is a turn ledger with a problem panel.
Each view either re-runs the assembly or reads the stored capture. The assembly is `seon.render/acquire-context!` →
`seon.render.web/derive-context!` → `seon.render.walk/history`, or `seon.cluster.prompt/prompt`. The ledger gets its
data through that assembly too, behind a `::transcript/ledger?` flag inside `derive-context!`.

**After (owner ruling, 2026-10-07).** "I want a debug view but it should be simpler as it's just showing the agents
context." The debug page keeps ONE view:

- what the model is (or would be) sent;
- produced by calling the same context function the turn calls;
- shown through the value printer's HTML face (CLAUDE.md: "the debug page, which only displays that assembly");
- computed only when the reader opens it.

Everything else on the page that shows the assembly or the turn history is deleted, including the ledger and its
problem panel (§3). Principal approach: deletion, plus converting the existing `sent-context-html` (which already
calls `seon.cluster.prompt/prompt` with no turn id) from a hand-made `[:pre]` to `seon.render.value/html`.

Happy-path check (lane-rules "One happy path"): this serves the **context** step. One producer of "what the model
sees" remains, the turn's own, and the page only displays it. The prompt path loses the ledger flag. Nothing new is
added: no namespace, schema, cache or helper.

Revision: HEAD `ec5f13d2f` / `2c36b20c2`. Default ran `0c2a12900` (identical `src/seon/render/*`), and was restarted
by root during this design. `file:line` references are HEAD's. v1 of this note (`2c36b20c2`) kept the ledger. This
revision replaces it in place.

## 1. Probe and measured cost (default, 2026-10-07)

`bin/seon status` and MCP `runtime_status`: default is `:faulted`, with 2 open faults linked to tasks `3d2fb1b41dc8`
and `seon.repl/response`'s 8. None of them is in S2a's files.

Probes ran two ways:

- installed functions in JVM mode, inside `let` forms, without defs or writes;
- installed functions called from SCI agents' own branches: `s2a-views` (`:agent-06f93e0d9c80`, retired) and
  `s2a-probe`, retired at the end.

Only root has evaluations: 113, on 89 turns.

**Today's debug page, over HTTP** (`curl`, `127.0.0.1:7994`; v1 measurements):

| Request | Time | Body |
|---|---|---|
| `/agent/root/debug` (ledger page), first request after any root write | 2.57 s (×2) | 117 KB |
| the same, warm | 0.71-0.83 s | 117 KB |
| `?turn=…` (session view) | 3.53 s | **1.23 MB** |
| `?turn=…&context=true` | 2.19 s | 1.21 MB |
| `?outline=true` | 1.70 s | 615 KB |
| `?turn=…&ledger=true` | 1.69 s cold, 0.70 s warm | 116 KB |
| `?sent=true` (the panel this design keeps) | 0.74 s cold, 16-21 ms warm | 159 KB |
| `?n=2` | 0.35 s | 164 KB |

Every root evaluation, including each MCP `eval_clj`, invalidates the retained history, so the next load pays the
cold cost again.

**The context function the view will call**, `seon.cluster.prompt/prompt` (prompt.clj:422). It is the Var the turn
calls (turn.clj:4507, via the `cluster-prompt-prompt` delay). Measured on root from agent `s2a-probe`'s SCI world,
with the connection and ctx supplied by schema name. Allocation is `getTotalThreadAllocatedBytes`, which is
process-wide, so these are upper bounds (other threads were idle but not stopped):

| Call | ms | MB | Result |
|---|---|---|---|
| no turn id ("would be sent"), first call | 513 | n/a (per-thread counter read 0 from SCI) | 148,210 chars, 122 contributions, ≈ 42,876 tokens |
| no turn id, after unrelated writes on the probe branch | 432 | ≈ 741 | same |
| no turn id, warm | 3.7 | — | same |
| with a turn id (what that turn saw), three cold turns | 606-840 | ≈ 1,026-1,234 | ≈ 147 KB each |
| with a turn id, warm | 4.6-5.1 | ≈ 11.6 | — |

The hot callers were `seon.schema.datahike/query-variable?` (153,711 calls in 2.1 s), `db/database-value?` (34k) and
`projection-cache-value` (15k).

**The value printer's HTML face of the assembly** (`seon.render.value/html`, agent render profile, token budget
1,500):

| Value shown | ms | MB | HTML |
|---|---|---|---|
| the composed map (text, contributions, budget report) | 7.5 | ≈ 10 | 25 KB |
| the text alone | 1.0 | ≈ 0.7 | 5.3 KB, ending in `…+143484` |

The text-alone cut offers no way to continue in HTML, and the text is shown as an escaped string literal (`\&quot;`).

**Ordinary page after S2a**: `transcript/render-agent-header` for root took 13 ms and ≈ 28 MB (process-wide), with
932 bytes of HTML. The ledger page today: 0.7-2.6 s.

**Tasks filed on default** (ids verified by query):

- `seon.render.value/an-html-cut-of-an-unstored-value-offers-no-continuation` (`e3d5afaaa903`): the cut above.
- `seon.repl/entity-emission-allocates-megabytes-per-evaluation` (`82b1b5c78c1d`): 200 ms and 345 MB for 113
  evaluations. It is still live after S2a, through history renders.
- `seon.render.transcript/turn-kind-reads-each-call-turns-opening-database` (`a9a7bb6e6f29`; 609 ms, 1,144 MB) and
  `seon.render.transcript/ledger-effects-allocates-per-turn-interval` (`ec70b6017334`; 30 ms, 186 MB). Both
  **dissolve**: S2a deletes the producing functions. Close both with the landing commit.

**Other finding.** `(doc seon.task/create!)` printed every `:map` entry's key schema twice. That is the S1 lookups
lane's `doc`; it is reported here, with no task filed, to avoid duplicating S1.

## 2. The one view

```clojure
; web.clj: sent-context-html (3391), converted in place; name and ?sent= route kept (root ruling 2)
(defn- sent-context-html
  "The agent's context as its next model call is sent it, through the value printer's HTML face."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.agent/id] :seon.render/hiccup]}
  [{connection :seon.store/connection-object :as service} agent-id]
  (let [database (db/db connection)
        composed ((requiring-resolve 'seon.cluster.prompt/prompt) database
                  (debug-turn-request database connection agent-id (:seon.sci.admit/caps service) (dissoc service :seon.turn/id)))]
    [:div {:id (block/surface-id (keyword "graph-sent" agent-id))}
     (value/html {:seon.render/value (dissoc composed :seon.db/db) :seon.db/db database
                  :seon.render.value/root [::context agent-id]})]))
```

The conversion from `sent-context-html` makes three changes:

- `tokens/estimate`, the counts line and the `[:pre]` go;
- the refusal branch it already had becomes the only branch;
- the `requiring-resolve` and the `debug-turn-request` call are today's, unchanged.

**What it shows.** The composed value: `:seon.cluster.prompt/text`, the ordered `:seon.context/contributions` and
the `:seon.ai.tokens/budget-report`. That is exactly what the turn hands `seon.ai/complete` as `:seon.ai/prompt`. The
provider request has one user message carrying that text; `seon.ai/request-body` (ai.clj:574) adds a system message
only when `:seon.ai/system` is set, and the turn sets none today. A refusal shows as the refusal value, through the
same face.

**When it runs.** The page renders a `<details>` like today's Record panel. Opening it fetches
`/agent/<id>/debug?sent=true` (Datastar), which renders `sent-context-html`. The ordinary page load never calls
the assembly.

**What "would be sent" means.** The call passes no turn id: acquisition reads every stored evaluation and appends
the turn frame, as the next turn's opening would. One caveat, until S4: a real provider turn first runs the system
turn's refresh (`open-turn`), which can add re-read evaluations. So the view equals the next prompt only when that
refresh adds nothing. S4 deletes the refresh, and the caveat goes with it. Optional, not recommended: `?turn=X`
passes that turn's id and shows what X was sent. That is one assoc, but it is a second view mode.

**At S4: the one-line change.** S4 replaces the turn's context function (spec §5 S4: "the prompt seam"; loop-pass's
seam named it `seon.agent.loop/context`, UNVERIFIED as the final name). S4 must convert every caller in the same
commit (CLAUDE.md "Change one loadable slice"), so `sent-context-html`'s `requiring-resolve` symbol changes to the new
Var, together with the turn's. The view then shows S4's value (opening, inputs, the agent's forms and results)
through the same `value/html`. No other line of the view changes.

**Exactness and the printer's cut.** Root's ruling: print the assembled map (text plus contributions) through the
value printer's HTML face, and fix any bad display of a long string in the printer generally, never by a
schema-specific branch. The fix is task `e3d5afaaa903`; it applies to every long unstored string, in HTML continuation
and in string display. Measured today: The agent render profile (1,500 tokens) cuts root's 43k-token context. Shown as
the composed map, the budget spreads over text, contributions and budget report, and the essence is visible (25 KB).
But no cut can be continued in HTML today (task `e3d5afaaa903`), so the reader cannot reach every byte. Options:

| Option | Exactness | Cost | Notes |
|---|---|---|---|
| (a) Accept the cut until `e3d5afaaa903` lands | partial | 0 lines | **recommended**: the owner's ruling names the printer's face, and the fix belongs in the printer |
| (b) Show the composed map's contributions vector | partial (budget per unit) | 0 lines | no gain over (a) |
| (c) A debug render profile with a larger budget | full up to the new budget | a new profile or dial | a second budget beside the one profile; rejected unless the owner wants it |

**Cache (owner question 3).** Before S4 the view adds no cache. It relies on the assembly's own retention:
`derive-context!`'s `::ai-calls` entry in `render/shared-cache`, keyed by `[lookup turn-id selector]` and reused while
its read evidence is current. That gives the 3.7-5 ms warm numbers above.

That retention is an atom beside the ctx, not a `seon.cache` kind, so the ruling's "cached through `seon.cache` by
what it read" is **not met before S4**. Adding a `seon.cache` kind now would mean rewriting `derive-context!`'s
retention, which is the path S4 deletes. Keying a new cache by commit id is ruled out by CLAUDE.md ("a commit id
identifies a database value, not an excuse to recompute"). The requirement is met by S4's context, whose units
render through `seon.cache` by content digest (CLAUDE.md "Presentation"). **Root ruling (v4):** accepted until S4,
which owns it; slated code is not patched.

**Budget (owner question 3).** Opening the view is over budget today:

- cold: 0.43-0.84 s and up to about 0.7-1.2 GB (process-wide upper bound);
- warm: 4-5 ms and about 12 MB.

Two reconciling notes:

- **Opus's 55 ms is not a contradiction.** The Opus review measured `?sent=true` at 55 ms "cold" and 8 ms warm. Its
  first request found the retained history still current: no root write had landed since the last acquisition. My
  cold numbers follow a change to root's history, which every MCP `eval_clj` or turn makes. The page's first `?sent=`
  in this design measured 0.74 s over HTTP. Both cases are real: 55 ms when nothing changed, about 0.4-0.8 s after a
  write.
- **Root's ruling quoted 55 ms / 8 ms as the cost**; the post-write number above is the one that hits a debugging
  owner.

The cold cost is not the view's: every provider turn pays it in the prompt, and S4 deletes that assembly. S2a's view
makes it visible on demand, never on page load. Stop-and-fix belongs to S4's context, which must measure the view's
open cost as one of its S0 rows; the target is under 100 ms and tens of MB.

## 3. The ledger (owner question 1)

### Root rulings (v4)

- **Ledger.** Option (A): delete the ledger and its problem panel. Tasks `a9a7bb6e6f29` (`turn-kind`) and
  `ec70b6017334` (`ledger-effects`) close as deleted when the code lands. The (B)/(C) specification below is kept as
  the record of what was considered.
- **Golden test.** `web_test/next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake` is deleted
  with its subject.
- **Gap 1 (the cost of opening the view).** Opening it costs 0.4-0.8 s, and up to about 1.2 GB, after any write.
  - Accepted only until S4. The cost is paid when someone opens the view, never on page load.
  - S4 deletes that assembly, and slated code is not patched.
  - **S4 must bring opening this view under budget and prove it with a measured S0 row**: the view's open, cold
    after a write and warm, in ms and MB.
- **Gap 2 (no `seon.cache` before S4).** The same treatment applies: accepted until S4, and S4 owns it.
- **Gap 3 (the printer's HTML cut).** A separate printer lane owns `e3d5afaaa903`, and S2a shows the composed map as
  it prints today.

What the ledger adds beyond the context view:

| Ledger part | Where it lives after S2a | S4 context |
|---|---|---|
| the agent's reply text and evaluated results per turn | the context view (forms and shown results are the assembly's units) | yes |
| effects (messages sent, definitions installed) | the agent page's message and program renders | — |
| provider attempts, tokens, billing | the attempt pair `seon.ai/attempt-html` (`seon.ai.edn:280`) wherever an attempt is shown; billing cost is not in the context | — |
| problems: errors, fabricated responses, repeated forms, empty replies, faults delivered, turns opened by faults | errors are in the view's results. Faults are in `runtime_status` and `seon.problems`. Fabricated responses and empty replies are reply-reader cases that S2b-1 and S6 retire | — |
| problems: churn, prefix, stale-but-unchanged, directory | prompt and system-turn diagnostics; S4 deletes their mechanisms | — |
| budget "N/M turns used" | the episode cap, which S6 deletes | — |
| the stalled header line | `render-agent-header` (`session-stall`), **kept** | — |

**Options:**

| Option | Kept | Cost on root | Lines |
|---|---|---|---|
| (A) **Delete the ledger** | header + context view + Record | page ≈ 13 ms / ≈ 28 MB | −1,067 transcript |
| (B) Thin list: one row per turn with reply intent, results count and kind | about 60 lines (`turn-entities`, `turn-kind` from own facts, `results-summary`) | ≈ 50-100 ms (UNVERIFIED) | ≈ −1,000 |
| (C) v1's converted ledger, with root's rulings 1, 3 and 4 applied | ≈ 600 lines | `ledger-effects` (≈ 186 MB) plus about 4 turn bodies' emissions; `turn-kind` and churn ≈ 0 | ≈ −500 |

**Root ruling (v4): (A).** The ledger and its problem panel are deleted; the page is the header, the context view and
Record. Reasoning: After S4 the context view is the agent's history ("the agent's forms with their real results"), so
a turn list duplicates it, and S6 deletes turns as entities. Every ledger number tied to the old machinery (turn
budget, churn, prefix, refresh) goes with it. (A) also deletes the two over-budget producers (`turn-kind`,
`ledger-effects`) instead of fixing them.

**How root's rulings apply.** Rulings 1, 3 and 4 (and the Opus review's findings 1, 3 and 4) are corrections to the
ledger.

- **Under (A) they dissolve.** `turn-kind`, `churn-problem` and `ledger-turn-body` are deleted.
  - Task `a9a7bb6e6f29` closes as dissolved. The Opus review measured `turn-kind` at 1,165 ms / 2,365 MB over 96 turns,
    about 47 ms / 95 MB per `:call` turn (the earlier figure here was 29 ms / 54 MB), plus `churn-problem`'s
    1,002 ms / 1,900 MB recomputation.
  - So (A) removes about 2 s of page cost. It is not a fix of it.
- **Under (B) or (C) they apply exactly as ruled.** If root or the owner keeps a ledger:
  1. **Label.** Every attempt-less `:call` turn is labelled "No provider attempt", from its own facts: no
     `turn/opening-db` and no dial read. This replaces option (b)'s "Virtual", whose premise the review disproved: MCP
     submissions are attempt-less `:call` turns (24 of 25 on root), and `call-turn`'s `fail!` paths close
     provider-enabled turns without an attempt. It becomes origin-derived after S3. The "Virtual" assertion lives only
     in the deleted session test.
  2. **Churn.** `churn-problem` reads the `::kind` already computed. Its caller is added to task `a9a7bb6e6f29`'s
     intent; root amends it, since assigned lanes don't edit runtime task facts they did not create through the writer.
  3. **Emissions.** `::emission` (`repl/entity-emission`) is built only inside `ledger-turn-body`, for the turn shown,
     never for all evaluations. Rules and the strip use `::label`, `::outcome` and the source only.
  4. **Ledger data.** `ledger-data` reads `seon.eval/of-agent` with the explicit selector (no read evidence). The
     review measured it at 24 ms / 41 MB cold and 5 ms / 10 MB warm. It returns the same evaluation set as today's
     acquisition (review finding 5: EQUIVALENT in membership).

## 4. What is deleted (Vars, callers, tests)

### 4.1 `src/seon/render/transcript.clj`: 59 Vars, 1,058 def lines (script `scratchpad/s2a-views/rmdefs.py`)

v1's 20 view Vars (448 lines) are joined by the ledger and its problem panel. Every one has no surviving caller
outside this set; checked with `chk.py` on the cut file.

| Group (HEAD lines) | Vars |
|---|---|
| session and outline (v1) | `attempt-html`, `session-url`, `outline-url`, `session-origin`, `render-session-loading`, `render-session`, `outline-signal`, `outline-turn-kind`, `outline-calibration`, `outline-renderer`, `outline-unit`, `outline-everything`, `render-outline` |
| ledger acquisition and context (v1) | `ledger-acquisition`, `ledger-evaluations`, `ledger-context-id`, `render-ledger-context` |
| captured prefix (v1) | `prefix-subject`, `render-captured-prefix`, `prefix-problem` |
| ledger body (new in v2) | `turn-kind`, `readable-shown`, `attempt-usage`, `nav-click`, `changed-paths`, `reread-summary`, `emission-label`, `ledger-url`, `ledger-body-id`, `colour-source`, `ledger-heading`, `ledger-emissions`, `reply-intent`, `results-summary`, `ledger-effects`, `turn-effects`, `emission-byte-count`, `ledger-entities`, `acquire-ledger-data` (public; only caller web `derive-context!` 2522), `ledger-turn-body`, `render-ledger-turn` (public; web `debug-turn-response`), `ledger-strip`, `turn-story`, `render-ledger` (public; web `debug-response`) |
| problem panel (new in v2) | `finding`, `evaluation-match`, `error-problem`, `fabricated-problem`, `empty-reply-problem`, `repeated-problem`, `churn-problem`, `directory-problem`, `fault-problems`, `session-budget`, `session-problems`, `problem-links`, `budget-html`, `problem-summary`, `problems-html` |
| `session-id` | its last callers are the deleted views |

**Converted:** `session-header` loses the "As the model saw it / Turn ledger" toggle (it used `session-url`,
`nav-click` and `::raw?`). It keeps identity, state (`session-stall`), "agent | debug", "Latest", the actions toolbar
and the message form. `render-agent-header`, `turn-entities`, `debug-turn-selector`, `session-stall`, `turn-reply`
and the runtime renders stay.

**Orphaned requires go:** `seon.ai.tokens`, `seon.env`, `seon.render`, `malli.registry`, `clojure.edn`, and the
`PushbackReader`/`StringReader` import. Each has 0 surviving uses on the cut file. Lint confirms.

### 4.2 `src/seon/render/web.clj`: 12 Vars deleted, 331 def lines; 1 converted

| Var (line) | Lines | Only caller |
|---|---|---|
| `debug-turn-response` 3188 | 24 | `debug-response` `?turn=` (all its views were session or ledger) |
| `debug-outline-response` 3213 | 15 | `?outline=` |
| `graph-node-name`, `inclusion-paths`, `edge-kind`, `node-attributes-html`, `context-graph-model`, `graph-legend-html`, `graph-debug-href`, `graph-node-label` (3229-3389) | 154 | `graph-debug-html` |
| `graph-debug-html` 3407 | 138 | `?n=` |

**Converted:**

- `sent-context-html` (3391) is kept with its `?sent=` route and converted (§2); it moves from the graph page's lazy panel to the agent page.
- `debug-response` (3546), agent branch: the page is `render-agent-header` (with `session-controls`), the context
  `<details>` and the Record `<details>`. Branches go: `?outline=`, `?turn=`, `?n=`,
  `?prompt=`.
- `debug-response`, `:else` (subject inspection): `prompt-section` (`render-session-loading`) goes. The debug grid
  keeps `debug-experiment-html`.
- `debug-query` / `debug-query-strings`: `::prompt-preview?` goes.
- `shell`: `::graph?` goes. Cytoscape and `seon-graph.js` load for `viewer-namespace` only, because the entity graph
  still uses them.
- `derive-context!`: the `::transcript/ledger?` branch (2520-2527) goes. The prompt path no longer carries debug
  display work.

**Unchanged in S2a**: `session-controls`, `change-context`, `context-action-response` and `render/acquire-context!`.
They are the controls of live mechanisms (the system turn runs before each provider turn until S4), and S4 converts
them with the assembly. The shared helpers the graph used (`forward-attribute`, `many-attribute?`, `projection-form`,
`scalar-label`, `json-script-text`, `render.walk/graph` via `walk/related`) keep their other callers.

### 4.3 Resources and docs

- `resources/seon/schemas/seon.render.transcript.edn`: delete `:prefix-subject` (a value schema carrying the
  `render-captured-prefix` pair; no stored data). Prove it by publication and adoption on a branch of the live store.
- `resources/public/css/input.css`: about 147 lines name only the 93 classes no surviving source emits (census
  `scratchpad/s2a-views/deadclasses2.txt`: `seon-outline-*`, `seon-ledger-*`, `seon-problem-*`, `seon-graph-page` and
  its node/face classes, `seon-session-rereads`/`-raw`/`-budget`/…). Mixed selectors are edited by hand.
  `output.css` is regenerated by `bin/css`.
- **False current claims**, corrected in the same commit:
  - `.agents/skills/datastar-web-ui/SKILL.md:66-68`: "The debug page uses the turn ledger and the acquired session
    component; entity inspection shares that session component" becomes "The debug page shows the agent's context
    through the turn's own context function, on demand, via the value printer's HTML face."
  - `docs/seon/specs/render-dedup.md` (draft): §1, §2 D3/D4/D5 and §3 items 4, 5 and 7 describe the deleted views and
    the ledger. Revise them in place.
  - `resources/seon/tasks/seon.render.web.edn`, `the-render-cache-keeps-every-debug-view`: re-point `:must-read` from
    `graph-debug-html` to `seon.render/render-call`, and say what remains (`::invocations`).
  - `resources/seon/tasks/seon.render.edn`, `landing-reds-2-render-bow-outs`: re-point from `render-session` to
    `seon.repl/entity-emission`, and strike the dissolved "web-debug strip" and `render-session` clauses.
  - `resources/seon/tasks/seon.render.transcript.edn` (`a9a7bb6e6f29`, `ec70b6017334`): close as dissolved by
    deletion. These are runtime tasks; root persists or closes them per the reset manifest.

### 4.4 Tests

**Deleted** (behavior retired with its mechanism, clojure-testing §3 rule 1):

| Test | Notes |
|---|---|
| `web_debug_test`: the three outline deftests + `outline-fixture`, `outline-nodes`; the three graph deftests + `graph-model` (and the `clojure.data.json` require) | v1 |
| `web_debug_test`: `passive-ledger-never-reconstructs-directory`, `ledger-derives-shared-data-once` | both use Var redefinition. Under (A) both go with the ledger. Under (B)/(C), `passive-ledger…` converts from `with-redefs` to `support/armed-calls` (test_support.clj:722); `sci.eval/directory-value` and `repl/render-directory-ai` are armed (Opus review finding 5) |
| `rereads_panel_test` (whole file) | its behavior is the ledger's stale-but-unchanged rule. `rereads_test`'s helpers stay for S4 |
| `transcript_test/a-refused-render-profile-is-passed-on-never-used-as-the-prefix-profile` | |
| the golden `web_test/next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake` | its declared behavior is the session view's colourised reader and raw toggle. **Root ruling (v4): deleted with its subject.** Its system-turn assertions are S4's |

**Converted:**

- **`web_debug_test/turn-details-use-the-loop-opening-and-exact-segments`.** Keep the `render/acquire-context!` and
  `web/derive-context!` assertions: the opening-basis rule, "later must not enter earlier prompt", and every reply
  turn excludes its own evaluations. Those are the assembly's behavior, S4's to retire. Delete every
  `render-session`, `render-ledger*`, `session-problems`, `prefix-problem` and budget assertion and the seeds only
  they read. That is most of the 323 lines; about 90 remain.
- **`web_debug_test/blocks-use-the-values-schema-documentation`**: drop the `emission-label` assertion.
- **`web_context_test/selected-session-defers-prompt-acquisition`** becomes the slice's **class regression**, counted
  with `support/armed-calls` on `seon.cluster.prompt/prompt` and `seon.render/acquire-context!` (both armed, with
  profile cells on default; Opus review finding 5):
  - the ordinary `/agent/root/debug` load makes **zero** calls (today one, for the ledger);
  - `/agent/root/debug?sent=true` makes **one**;
  - the body answered for `?sent=true` contains the value printer's panel (`seon-data-panel`), asserted by
    selector, not text.

  The inspection page's "Session" assertions go.
- **`web_test`**:
  - `inspecting-the-page-writes-no-run-evaluation-or-fault-facts` (916) fetches `/agent/root/debug` and
    `?sent=true` (datastar) in its loop. It still asserts no datom is written: opening the view must write nothing.
  - `each-agent-has-an-isolated-debug-route` (2010) and `debug-algorithm-carries-the-render-evaluation-inputs` (2347)
    drop `?prompt=true`.
  - The element id `#surface-debug-session_2f_<id>` becomes the context panel's `#surface-graph-sent_2f_<id>` (today's id, kept).
  - The three-action assertion is unchanged (the header toolbar stays).
- **`turn_test`**: delete the two `render-ledger-turn` assertion blocks (around 614-624 and 714-721). Their system-turn
  and `of-agent` assertions stay.
- **`run6_stall_test`**: keep the `render-agent-header` "stalled: …" assertions and `turns-left`/`next-agent-work`;
  delete the `session-problems`/`problems-html` snapshot parts.
- **Unchanged**: `web_context_test/context-and-page-do-not-demand-the-render-proc`, because it tests
  `acquire-context!` (S4's).

## 5. Duplicates, library seams, size

**Duplicates removed:**

- five displays of one assembly become one;
- a ledger that re-derived the history the context already holds;
- a problem panel duplicating `seon.problems` and `runtime_status`;
- two graph pages become one (the entity graph `debug-graph-html` stays);
- the ledger's work inside the prompt path's cache.

**Library seams:** none new. The view calls the turn's Var and `seon.render.value/html`.

**Lines (estimate, `wc -l` delta):**

| Area | Delta | Breakdown |
|---|---|---|
| transcript.clj | ≈ −1,070 | 2,332 → ≈ 1,265 |
| web.clj | ≈ −390 | −331 defs, ≈ −50 in `debug-response`, −7 in `derive-context!`, −6 in `debug-query*`, −2 in `shell`, −5 in the view; ≈ +6 for the `<details>` |
| schema | −6 | |
| input.css | ≈ −147 | |
| **src total** | **≈ −1,460 Clojure, ≈ −1,610 with CSS** | v1 was −870 |
| tests | ≈ −1,000 | the outline, graph and two ledger deftests (≈ 400), `rereads_panel_test` (≈ 120), most of turn-details (≈ 230), the golden (73), run6 and turn_test blocks (≈ 40), the transcript_test deftest (21); UNVERIFIED until cut |

**Alternatives considered:**

1. v1 (keep a converted ledger): ≈ −870 lines, a page at ≈ 300 ms / 650 MB. Superseded by the ruling.
2. Walk-delete's saved patch (`scratchpad/walk-delete/slice-b1-debug-views.patch`): it applies cleanly to HEAD's index
   (−1,207/+137). It keeps a ledger and still assembles through the ctx projection, so it is closer to (C). Reused:
   nothing beyond the deletion list.
3. A new viewer of S4's context written now: rejected. S4 owns that function, and the view must call it, not
   anticipate it.

## 6. Risks, tests that reach them, claims

**Risks:**

1. **File ownership.** `src/seon/render/transcript.clj` is claimed by **s5a-reply**, with uncommitted hunks at 333-760
   (`fault-values`, `with-errors`, `held-entry`, `branch-errors`). S2a's hunks start at 1061 and are disjoint, but one
   file has one owner, so S2a code waits, or root takes a patch. `web.clj`, the resources, the CSS and the named tests
   are unclaimed. `turn_test.clj` and `run6_stall_test.clj` are not in s5a-reply's list (checked with
   `bin/lane status`).
2. **Hidden callers.** Quoted symbols and `requiring-resolve` escape clj-kondo. The deleted names' only quoted
   references are the schema pair and the task files (§4.3). Re-grep `src test resources .agents docs/seon` before
   commit.
3. **Schema resource change** (`prefix-subject`): publication and adoption on a live-store branch.
4. **The view's cold cost** (§2) exceeds the budget on open. It is the assembly's cost, recorded for S4. Root accepts
   it for the interval, or orders S4 first.
5. **Open browser tabs** holding `?prompt=`, `?outline=`, `?turn=` or `?n=` fall through to the plain page.
   Verify browser paint separately.

**Tests that reach the change** (for `bin/lane commit --test`):

- `seon.render.web-debug-test`;
- `seon.render.web-context-test`;
- `seon.render.transcript-test`;
- `seon.render.web-test`, the three converted members, named;
- `seon.turn-test`, the two converted members;
- `seon.run6-stall-test`.

**Expected S0 numbers after:** unchanged. No stage of the model pass or the MCP pass changes, and the
`derive-context!` edit removes a branch the prompt never takes. Verify with `seon.pass-probe/run-mcp` and `run-model`
on the parent and the change. The debug page gets its own before/after rows:

- page load: 0.7-2.6 s today, target about 15 ms;
- view open: cold and warm, as §1.

**Claims needed:**

- `src/seon/render/transcript.clj` (after s5a-reply) and `src/seon/render/web.clj`;
- `resources/seon/schemas/seon.render.transcript.edn`;
- `resources/public/css/input.css` and `output.css`;
- `resources/seon/tasks/seon.render.edn` and `seon.render.web.edn`;
- `.agents/skills/datastar-web-ui/SKILL.md` and `docs/seon/specs/render-dedup.md`;
- `test/seon/render/{web_debug_test,web_context_test,transcript_test,web_test}.clj`;
- `test/seon/{turn_test,run6_stall_test,rereads_panel_test}.clj`.

## 7. Design check

The ruling simplified the design: the one view already exists as `sent-context-html`, and it already calls the turn's
Var. Problems that remain:

- **The printer's HTML cut of a long string cannot be continued and shows an escaped literal.** The view therefore
  shows the essence, not every byte, until task `e3d5afaaa903` lands. Root ruling: a separate printer lane owns that
  task; S2a takes option (a).
- **`seon.cache` caching of the view is not possible before S4** without rewriting a path S4 deletes. Root ruling:
  S4 owns it.
- **The view's open cost is the old assembly's** (0.4-0.8 s cold after a write; 55 ms when nothing changed). It stays
  over budget until S4.
- **Root's rulings 1, 3 and 4 (and Opus findings 1, 3 and 4) correct the ledger**, which this revision recommends
  deleting. They are written into §3 as the spec for (B)/(C), so whichever the owner picks is fully specified.
  - The Opus review's finding 2 (keep `sent-context-html`) was already this revision's §2.
  - Name and route are now kept as root ruled.

**Verified since v2** (Opus review finding 5): the directory functions, `prompt/prompt` and `acquire-context!` are
armed, so `support/armed-calls` can count them.

**UNVERIFIED:**

- the final name of S4's context Var;
- the test line counts after the cut;
- precise per-thread allocation for the prompt call (only a process-wide upper bound was available from SCI);
- browser paint.

**RESET NEEDED:** none.
