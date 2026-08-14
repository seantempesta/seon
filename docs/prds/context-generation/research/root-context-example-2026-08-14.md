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

## Redone under ruling 39 — three agents, one mechanism, namespace root

Every edge below is an INSTALLED schema ref today (verified:
`:seon.ns/requires` set-of-refs; `:seon.fn/ns` and `:seon.test/ns` /
`:seon.test/subject` reverse-reach a namespace's functions and tests;
`:seon.cluster.agent/namespace` reverse-reaches the agent record,
whose refs reach the run, messages, and plan). Ruling 39 needs no new
schema — only the root: `[:seon.ns/name <the namespace>]`. Ruling 40
sharpens the identity: AGENTS ARE NAMESPACES — the separate agent
entity is old-model plumbing on its way to dissolving into the
namespace entity, so where these walkthroughs say "through the agent
record," read "the namespace's own agentic facts." No manual
membership anywhere; each context below is exactly what the generated
selector discovers from that one root, rendered in pull-tree order.

### 1. Root — namespace `my.agents.root`

The pull discovers: the ns entity — its form
`(ns my.agents.root (:require my.message my.run seon.bootstrap
seon.db))` renders FIRST (define-before-use starts here) → the four
REQUIRED namespaces through `:seon.ns/requires`, each dir'd (their
fn/schema rows — including `my.message/send`, `my.run/complete`, the
`seon.db` read family) → root's own indexed functions (reverse
`:seon.fn/ns`; today none — the "No indexed members" row) → the OWNER
AGENT via reverse `:seon.cluster.agent/namespace` → through the agent:
its open run, its messages (`bootstrap-task:root`, the maintenance
notices), its plan, its routed errors, by arrival. What is GONE versus
the live capture: the cluster entity's config lines, the
instruction-set dump, and the five never-required toolkit namespaces
(`my.note`, `my.fs`, `my.shell`, `my.web`, `my.edit`, `my.plan`) —
reachable tomorrow by root simply requiring them, which then shows in
its ns form: the context explains itself.

### 2. A temp agent — namespace `my.agents.k3f9`

"New chat" creates the agent and its namespace; the user never picks a
name. The pull discovers: the tiny ns form with the shipped default
requires (whatever creation writes — say `my.message my.run`) → those
two namespaces dir'd → no indexed members → the agent (reverse ref) →
ONE message (the human's first chat line) and the open run. That IS
the whole context: a fresh chat is small BY DERIVATION, not by
curation — nothing was budgeted, staged, or configured. As the agent
requires more namespaces and defines contracted functions, its context
grows through the same two edges (`requires`, reverse `fn/ns`), and
root migrating it to a real namespace later is just re-rooting the
same pull.

### 3. A maintainer — an agent takes over `my.note` for the system

Root assigns `my.note` to an agent (the agent entity's `/namespace`
ref repoints — one transaction). The same pull, rooted at
`[:seon.ns/name my.note]`, now discovers: the ns form and its requires
(`clojure.string`, `seon.db`, `seon.render.value`, `schema.edn`) → ALL
of `my.note`'s indexed functions with contracts and docs (reverse
`fn/ns`: `add!`, `forget!`, `notes`, the six render faces) → its
schema rows (`:my.note/id` identity, the error classes) → its TESTS
via reverse `:seon.test/ns` and per-function `:seon.test/subject` →
its DEPENDENTS at the next distance through reverse `:seon.fn/calls`
(who calls `my.note/notes`) → the owner agent → incoming
change-request messages, its run, its plan. That is the owner's
working set from context.md ("current source, dependents, tests,
incoming change requests") — derived entirely from installed refs, no
ownership manifest, no role configuration. The maintainer and the temp
agent differ ONLY in which namespace the pull is rooted at.

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
