---
type: prd
status: draft
tags: [prd, agent, web]
---

# Demo script — Friday 2026-06-12 (board P24/P9)

Run-through happens THURSDAY on the finished refactor; this is the script +
dry-run checklist. Demo bar source: PRD §1
(`cljs-finish-clj-pivot-plan-2026-06-09.md`). Closer decided by the user
(2026-06-10, from phone): **fresh-session store/retrieve competence** — the
agent writes code to store user data, the pod restarts on camera, and the
SAME resumed agent retrieves it. NOT tiles.

Every beat carries a receipt: a named run, gym scenario, or live-proven
commit that shows the behavior already happened. Nothing in this script is
hoped-for.

## 1. The arc (15–20 min)

| # | Beat | What it proves | Time |
|---|---|---|---|
| a | Mission control + the resumed agent | Durability — its conversation from days prior is right there | 2–3 min |
| b | Live codebase question | Consult-first + verified answers with provenance | 4–5 min |
| c | **CLOSER: "track my workouts" → restart pod on camera → same agent retrieves** | Arbitrary-request competence; the store IS the memory | 6–8 min |
| d | Kicker: the store IS the system | Agent's own instructions are data — edit one, next turn reflects it | 2–3 min |

Narrative spine: *this agent remembers (a), this agent knows (b), this agent
builds its own memory and survives death (c), and even its own mind is just
data in the store (d).*

## 2. Beat-by-beat

### Beat (a) — meet the resumed agent (2–3 min)

- **Action:** open `/agents` mission control. Live ticking stats. Click into
  the primary agent. Scroll the chat: the seeded prior-day conversation is
  visible, turn separators and all.
- **Say:** "This agent has been alive for days. Nothing here is a fresh
  context window — this is its actual history, in its database."
- **Receipt:** be0cf28 live proof — restart×2 → identical roster, zero
  accumulation; the resumed agent SEES its pre-restart conversation
  (DeepSeek confirmed it in-band). Mission control browser-verified after
  `cluster reset` (PRD §7 item 11).
- **Fallback:** none needed — this beat is static rendering of durable
  data. If the page is slow/stale, hard-reload (render is ~0.5s
  post-cache-fix). Stale pre-flip tabs now get a clean 404, but close them
  beforehand anyway (checklist §3).

### Beat (b) — live codebase question (4–5 min)

- **Exact input (type into the chat bar):**

  > What does seon.db/transact! give me back when a write fails? Does it
  > throw? Show me what I should check for.

  This is gym **S-10 verbatim** — the rubric and expected answer are
  already encoded, so we know what "right" looks like.
- **Expected behavior:** `● thinking` (~1.5s, thinking OFF); first eval
  consults stored knowledge (`:kb.finding/*` datalog) per the #26
  consult-before-research instruction; finds nothing (or finds the
  rehearsal finding — both demo well, see fallback); greps/reads the
  source; ideally PROVES the answer empirically (transacts a bad attr live,
  shows the `{:seon.db/ok? false :seon.db/error …}` envelope — S-10's bonus
  predicate); stores a `:kb.finding/*` row with claim/path/line/confidence;
  replies with file:line provenance.
- **Say:** point at the knowledge card when it lands. "It just wrote that
  down. The next agent — or this one tomorrow — starts from this row
  instead of redoing the search."
- **Receipts:** run 7 (grep@16s → findings per convention → correct answer
  @95s, `research/e2e-demo-findings-2026-06-08.md`); gym S-12 (run-8 pass
  bar) GREEN 2× paid sweeps; #26 consult-first + store-proactively shipped
  44a42df (verifier-falsified, live-proven).
- **Fallbacks:**
  - Agent re-derives instead of consulting (run 7's one miss): the answer
    is still correct and watchable — narrate the finding it STORES, then
    ask a cheap related follow-up ("where exactly is that envelope built?")
    to show consultation on camera (S-32's isolated probe, green at cheap
    tier).
  - LLM call fails: the turn closes `:error` and a visible "⚠ LLM call
    failed" chat message appears — **fail-loud is itself demoable** ("the
    system never pretends"). Re-send the message; a fresh user message
    resets the hop chain.
- **Timing note:** run 7's full pipeline was 95s for agent #1, 70s for
  agent #2 — budget 2 min of watching, fill with narration of the
  transcript panel (evals, results, the finding card).

### Beat (c) — THE CLOSER: store, die, retrieve (6–8 min)

- **Exact input 1:**

  > Track my workouts for me. Today I did 45 minutes of deadlifts and some
  > bench press. Monday I ran 5k in 28 minutes.

- **Expected behavior:** agent designs `my.workout/*` schemas live
  (`register!` with multi-segment namespaced attrs — the register! gate
  refuses single-segment, fe2b026), transacts the workout rows, ideally
  writes a small query fn + test in its `my.*` home, opens a
  `seon.agent.todo` item if anything is unfinished, replies confirming
  what it stored.
- **Say:** "Nobody wrote a workout feature. It designed the schema, the
  storage, and the retrieval code in the last sixty seconds — and all of
  it is data in its database."
- **Action 2 — ON CAMERA:** terminal visible →

  ```bash
  bin/seon restart pod

  ```

  Narrate while it boots (~time it Thursday): "The agent process is dead.
  Its memory is not — schemas, code, conversation, and data are datoms in
  the cluster store. The JVM writer never blinked."
- **Action 3:** reload the agent page. Same agent, same conversation
  (resume-don't-mint), its open todo renders. **Exact input 2:**

  > What did I do this week?

- **Expected:** first eval is a datalog query over `my.workout/*` rows
  (consult-first — the data is in ITS schema catalog now); answer
  summarizes the deadlifts/bench/5k from the store, no re-asking.
- **Receipts:** gym **S-21** (log workout against existing schema, no
  fork) GREEN — top-4 green incl. S-01/S-12/S-32/S-21; replay fix 42a18d6
  — resume corpus replays **6/6/0 including redefines** (ns rows first +
  retry pass, red→green regression test); durable resume be0cf28;
  `seon.agent.todo` shipped as the store/retrieve exemplar (12a20fd);
  cross-agent schema reuse proven back in run 6.
- **Fallbacks:**
  - **Schema design goes sideways** (bare keywords, weird shapes): the
    register!/transact! gates refuse with guiding error envelopes — the
    agent self-corrects in the next eval (observed across runs 6–8;
    errors-are-values is the recovery mechanism). If it flounders >2
    turns, nudge: "use `my.workout/*` attributes."
  - **Replay hiccup at boot** (the §4 swallowed-analyzer edge, fixed for
    the known corpus but the agent writes NOVEL code): the DATA datoms are
    durable regardless of whether a fn replays — "what did I do this
    week?" only needs datalog over stored rows, not a replayed fn. The
    retrieval answer survives a partial replay.
  - **Catastrophic store problem post-restart:** restore the Thursday
    store snapshot (checklist §3) and replay the beat — this is why we
    snapshot.
- **Thinking mode:** OFF by default (~1.5s replies). Consider
  `set-thinking!` high for input 1 only (schema design quality) —
  tradeoff in §3.

### Beat (d) — kicker: the store IS the system (2–3 min)

- **Action:** open the inspector / agent context panel. Expand the
  `:instructions` section — these are `my.kb.instruction` entities, not a
  prompt file. Then, in a REPL/inspector eval, transact an edit to one
  instruction (e.g. append a sentence like "Always answer in haiku" to a
  low-stakes one — pick the exact edit Thursday). Send any small message;
  the next turn's context reflects the edit. Retract/restore.
- **Say:** "Its own operating instructions are rows in the same database
  it stores your workouts in. Query them, edit them, and the next turn is
  different. There is no hidden prompt — the store is the whole system."
- **Receipt:** 951dedb live-proven: edit → next render shows it → restore;
  re-seed idempotent, same 4 eids across restarts.
- **Fallback:** if the live transact feels risky on the day, do the
  read-only half (show the instruction entities + the rendered section)
  and show the PRE-VERIFIED diff from Thursday's rehearsal. The read-only
  version still lands the "instructions are data" point.

## 3. Pre-demo checklist (Thursday)

Ordered — later steps depend on earlier ones.

1. **Full dress rehearsal on the finished refactor:** `bin/seon restart
   all` (dependency-ordered, socket-gated — 2db5667), then run beats
   a→d end-to-end exactly as scripted. Time each beat, especially the
   on-camera pod restart in (c).
2. **Cluster reset AFTER rehearsal:** `bin/seon cluster reset default`
   (wipes store only, re-seeds idempotently; first real run verified: 1
   agent / 106 fns / 3154 datoms, mission control matched). The closer
   must be a FRESH design — if `my.workout/*` survives from rehearsal,
   Friday's agent reuses instead of designing live.
3. **Seed the prior-day conversation (Thursday evening, post-reset):**
   have a real short exchange with the agent — 2–3 repo questions (NOT
   workouts). This is beat (a)'s durability exhibit AND beat (b)'s
   optional pre-stored finding. Verify it renders after one
   `bin/seon restart pod`.
4. **Snapshot the seeded store:** `cp -r data/clusters/default/store
   tmp/demo-store-backup-2026-06-11/` (with all processes stopped).
   Friday-morning restore path if anything corrupts.
5. **Thinking mode decision:** default OFF (~1.5s — keeps the demo
   moving). Rehearse the closer's schema-design turn BOTH ways Thursday;
   flip `set-thinking!` high for that one turn ONLY if OFF produces a
   visibly sloppy schema. Tradeoff: design quality vs. dead air on
   camera. Pre-stage the `set-thinking!` eval in a REPL so the flip is
   one keystroke.
6. **Browser tabs:** exactly three — `/agents`, the demo agent's page,
   the inspector. Close ALL stale tabs (dead-agent 404 guard exists, but
   don't demo it by accident). Pin autoscroll on the agent page.
7. **Terminal staged:** one window, big font, `bin/seon restart pod`
   pre-typed in history; second window tailing `bin/seon tail pod` for
   the boot narration.
8. **MCP hygiene:** GC the stale cljs sessions / reset the broken
   `default` session (PRD §7 item 13) — and DO NOT MCP-require anything
   into the live pod during the demo (the malli-registry hot-require
   corruption incident). The pod is hands-off except via the web UI and
   the staged kicker transact.
9. **Kicker dry-run:** pick the exact instruction entity + the exact edit
   transact + the restore retract; verify the next-turn render flip;
   write both forms in a scratch file to paste from.
10. **If a turn errors on the day:** the ⚠ chat message is fail-loud
    working as designed — narrate it as a feature ("it never pretends a
    call succeeded"), then re-send. A fresh user message resets the hop
    chain. Do not debug live; the snapshot is the escape hatch.

## 4. Risks

| Risk | Beat | Likelihood | Mitigation |
|---|---|---|---|
| Replay edge on the on-camera restart — agent's NOVEL `my.*` code hits a path the 42a18d6 retry pass doesn't cover | c | Low (6/6/0 incl. redefines, but the corpus is fixed; live code is novel) | Retrieval answer needs only datalog over durable datoms, not replayed fns; rehearse the exact closer Thursday; store snapshot restore as last resort |
| Consult-first doesn't fire — agent #2-style re-derivation instead of querying stored rows | b, c | Medium (S-12/S-32 green, but it's probabilistic LLM behavior) | Answer is still correct + watchable (run 7); follow-up question gives a second consultation chance; narrate the stored finding either way |
| `clojure.string/*` inside datalog `:where` predicates — agent inlines string fns in a query and the pod's query engine errors | b, c | Medium (agents love `includes?` filters) | The error is a legible value in the transcript; agents self-correct to filter-after-query (observed recovery pattern). Verify the failure mode + recovery once in Thursday's dry-run so the narration is ready |
| Turns-cap exhaustion (default 20) — a flailing turn chain burns to the cap | b, c | Low (thinking OFF = fast turns; turn-pressure escalation in the prompt) | Fresh user message starts a new chain; nudge prompts from §2 fallbacks cut flailing early |
| LLM/API failure mid-beat | all | Low | ⚠ fail-loud message → narrate as a feature → re-send; DeepSeek spend unlimited, no quota cliff |
| Schema-design quality with thinking OFF — sloppy `my.workout` shape on camera | c | Medium | Gates refuse bad shapes with guiding envelopes (self-correction is itself a good 20 seconds of demo); §3 item 5 decides the thinking flip from rehearsal evidence |
| Hot-require malli-registry corruption | all | Near-zero if checklist §3.8 is followed | Nobody touches MCP/require on the live pod; `bin/seon restart pod` clears it if it somehow happens |

## 5. Open questions for the user

1. **Audience + register:** board-level (P24/P9) — how technical? The
   script narrates datoms/schemas; if non-technical, beats (b)/(d) need a
   plainer voiceover (same actions, different words).
2. **Length:** 15 vs 20 min — at 15, trim beat (d) to read-only kicker
   (~1 min) and cap beat (b) at one question.
3. **Gym scorecards as the credibility slide?** One slide — "19 scripted
   scenarios, top-4 demo-critical green, 2× paid sweeps" — turns "it
   worked on stage" into "it works repeatedly." Recommend yes, shown
   between (b) and (c).
4. **Kicker live-transact vs read-only** (§2d fallback) — decide after
   Thursday's dry-run, or pre-decide read-only for safety?
5. **Who drives:** user types while narrating, or a second person types?
   The script assumes one driver-narrator.
