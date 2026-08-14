---
type: research
status: current
tags: [research, render, context, evidence]
---

# The real thing — root's live context, walked step by step

Captured 2026-08-14 evening from the RUNNING `default` cluster
(pid 99029, basis t 536872641) via `GET /agent/root/debug` — the exact
`:seon.render/ai` bytes the render proc derived. This is the worked
example for the PRD's pipeline: what the pull acquired, what order the
walk fixed, which renderer each piece resolved, and where today's
output violates the rulings. Raw capture retained in the session
scratchpad; every excerpt below is verbatim.

## The observed order (the walk's actual output)

The context is one REPL session: every entry is
`my.agents.root=> (form)` followed by the rendered value. In order:

1. **Who am I.** `(db/pull db '[*] [:seon.cluster/name "default"])` →
   "Cluster default. Configuration default; 1 shared instruction and 9
   toolkit namespaces." Then the config entity → model, eval limit,
   executor sizes.
2. **The rules.** `(db/pull db '[*] [:seon.cluster.instruction/id
   :getting-started])` → the full instruction text (reply grammar,
   `defn`+`:malli/schema` = permanent, `my.message/send`).
3. **What exists — names BEFORE use.** One `(dir 'ns)` per toolkit
   namespace (`my.note`, `my.fs`, `my.message`, `my.shell`, `my.web`,
   `my.plan`, `my.edit`, `my.background`), then the agent's own
   namespace, then `seon.bootstrap` and `seon.db`. Each dir lists the
   ns form, then every public fn row (`:seon.fn/sym`, `/spec`,
   `/doc`), then schema rows — the define-before-use discipline live:
   the agent has seen every name before anything is called.
4. **What happened.** Live material in arrival order: the open run,
   the bootstrap task message read back, then messages / error facts /
   maintenance receipts, requests, and fires, newest nearest the next
   turn.

So the ordering question has a concrete answer in production: identity
→ rules → vocabulary (dir before doc) → events by arrival. The
form+value façade is REAL — every entry is a call the agent could
re-run.

## What each entry proves about selection

- The cluster and config pulls render through their DECLARED faces
  (`seon.cluster/render-ai`, `seon.config/render-ai`) — chain rung 3.
- The instruction renders its stored text verbatim — the one place
  prose is legal (instruction entity).
- `(dir …)` output is the FLOOR printing a vector of program-graph
  rows — data, with a real elision value at the tail carrying
  `omitted/total/next-offset/requery-id [:seon.ns/name my.note]` —
  the requery discipline working.
- Run/message/error pulls render through the narrating faces the
  register kills.

## The defects, live in this one capture

1. **Run-name substitution (filed; confirmed live).** THREE different
   run ids pulled — `bootstrap:root`,
   `801541da-8f77-…`, `25c88c19-…` — and every one renders "Run
   f5305f54-3428-47c7-80bc-58801138e9a0, opened …". The narrating run
   face prints the WRONG RUN's identity. A data face could not make
   this mistake invisible; the sentence hides it perfectly (rip-out
   #1/#3).
2. **The fit-terminal chop, live (rip-out #15/#8).** `(dir
   'my.background)` renders as ONE QUOTED STRING cut mid-character:
   `"[(ns my.background …` … `"… 1641 more characters of 3279;
   requery by [:seon.render.call/id …]`. The whole rendered output was
   pr-str'd and character-chopped — the double-fit defect exactly as
   audited.
3. **Elision values print as raw EDN soup.** The honest elision node
   appears INLINE as its full map —
   `{:seon.print/face :seon.print/elided, :seon.print/omitted 25, …}`
   — ~7 lines of namespaced keys where ruling 33 wants one compact
   shape marker. The facts are right; the face is missing.
4. **Broken round-trip: unquoted strings inside printed maps.** The
   maintenance receipt/request maps print string values BARE —
   `:seon.maintenance.receipt/handler Restart the JVM to remove …` —
   no quotes, so the printed map does not read back. (Likely a nested
   `/ai` fragment spliced raw into the floor's map print; verify at
   the bytes during the printer wave.)
5. **The second map face, live (rip-out #4).** `schedule.fire` rows
   render as `nominal-at: #inst …, task: {…}` — colon-suffixed, no
   braces, namespaces stripped: unqueryable pseudo-EDN.
6. **Repetition owns the context.** The hourly `process-census`
   failure appears as message + error + receipt + request + fire,
   re-pulled for EVERY hourly fire (~9 rounds captured), each request
   embedding the agent's ENTIRE opening instruction text again
   (`:seon.maintenance.request/agent You are agent root …` — stored
   duplicated instruction bytes inside request rows; a data-model
   defect, not a render one). Consequence visible IN the same
   context: two `:seon.cluster.prompt/budget-exceeded` errors — 41577
   and 63558 estimated tokens against the 32768 budget — runs refused
   because the context is stuffed with duplicated failure evidence.
   This is the concrete case ruling 37 anticipates: the fix is
   MOVING/COALESCING the data (one fact family for a recurring
   failure; instruction text by ref, never embedded), not clipping.

## What this example settles for the design

- The five-stage story and the façade are not aspiration — they run
  today; the rot is in specific faces and the printer's cut paths,
  precisely where the register points.
- The ordering rules are observable and correct; the PRD's diagram
  now includes them as stages.
- The naming question (what to call one walked piece) stays OPEN by
  the owner's instruction until this data is understood; what the
  data shows is: each piece is one (form, value) pair derived from
  one pull edge — the entry IS a record of a call.
