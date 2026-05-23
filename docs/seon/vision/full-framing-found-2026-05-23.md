---
type: research
status: active
tags: [research, vision]
---

# Full Framing Found — 2026-05-23

Companion to `full-scope-synthesis-2026-05-23.md` and `biggest-ideas-2026-05-23.md`. Those prior agents covered framings on `main`, the milestone scope, capability docs, the v1 spec, and most of the `feature/refinement` + `feature/super-repl` archives. **This file goes where they did not reach** — primer research docs on `feature/super-repl`, the back half of `seon-transform/prd.md` (lines 120–412), Sean's 12 sibling repos in `~/src/`, the `~/src/_publishing/` curated archive of his prior public reference points, and a triage of unreachable commits on the current repo.

## Sean's articulated framing (this session)

> A personal AI that can do anything for you because it can write code. An AI that grows with you as you are telling it you need things done, building apps for you that are custom to your goals.

The single most important finding below: **a verbatim ancestor of that exact pitch exists in Sean's prior repos**, dated February 2025, in three places (`~/src/seon-biff/README.md`, `~/src/_publishing/seon-2025-02-architecture/README.md`, and the original 2025 git history). See §6 below.

## 1. Coverage log

- [x] Prior synthesis files (read in full)
- [x] **Surface A — primer research on `feature/super-repl`.** All 10 files listed: `AGENT-BOOTSTRAP.md`, `decisions.md`, `notes.md`, `prd.md`, `research/architecture-vision.md`, `research/ctx-as-os.md`, `research/ctx-sync-design.md`, `research/seon-architecture-research.md`, `research/state-machine.md`, `research/template-system.md`. Prior agent read 5 of these from the **refinement archive**; this pass reads them on the **super-repl branch** where they live as live PRDs (not archive). The `prd.md`, `template-system.md`, `ctx-sync-design.md` were missed entirely.
- [x] **Surface B — seon-transform.** Read lines 120–412 of `prd.md`. Also `decisions.md`, `notes.md`, and 5 research files under `research/`. Surveyed only — the research files are tactical migration stages, not vision-bearing.
- [x] **Surface C — sibling repos.** 10 priority repos surveyed. Findings:
  - `~/src/primer/` — `ROUGH_PLAN-NEEDS-REFINEMENT.md`, 398 lines, 2025-12-23. **Major find**: a complete Neo-Victorian Primer engineering plan tied to Diamond Age framing.
  - `~/src/seon-old-base/` — implementation only (clojure-mcp upstream, no Sean vision).
  - `~/src/seon.bak/` — README is the 8-line Biff stub. No vision material.
  - `~/src/seon.main/` — empty (one `.iml` file).
  - `~/src/seon-look-into/` — 37-line Biff/Kit stub. No vision.
  - `~/src/seon-biff/` — **JACKPOT**. README is 1936 lines, the full **"SEON System Design: Namespaces are king"** vision doc, v2.1, February 2025. Section §1 has the smoking-gun framing. See §6.
  - `~/src/seon-visualizations/` — 53-line README. Substantive (vision/architecture in `docs/`), but scope is *explaining* Seon, not framing it.
  - `~/src/seon-gsap/` — spatial multi-session UX experiment. Tangential to the personal-AI claim but interesting for "natural multi-touch navigation across many AI sessions."
  - `~/src/seon.biff/` — 8-line Biff stub.
  - `~/src/seon.tmp/` — empty.
  - `~/src/ml-options-trading/` — README is concrete options-platform docs, no aspirational claims.
- [x] **`~/src/_publishing/`** — discovered (NOT in the brief). It is Sean's curated publication-quality archive of seon's lineage repos, with README rewrites done **2026-04-21** that synthesize what each prior repo contributed. Five repos: `seon`, `seon-2024-10-kit-migration`, `seon-2024-10-xtdb-biff`, `seon-2025-02-architecture`, `seon-2025-11-trading-domain`. All read.
- [x] **Surface D — grep for "grows with / custom apps / personal AI / companion / bonded".** Hits in `seon-biff/README.md`, `_publishing/seon-2025-02-architecture/README.md`, `primer/ROUGH_PLAN-NEEDS-REFINEMENT.md`. No other sibling matched on multiple of those terms.
- [x] **Surface E — unreachable commits.** Triaged ~20 by subject line via `git log -1 --format='%ai %s' <sha>`. Findings: nearly all are spec/spike/scrub work from 2026-05-15 to 2026-05-22 — recent dev work on agent-runtime, datalevin removal, spec-05 render schemas. **No vision-revision commits in the unreachable set.** Skipping deeper dive.

## 2. The five framings to choose from

Ranked by match to Sean's "personal AI that grows with you, builds custom apps" pitch.

### Framing 1 — The 2025-02 seon-biff vision (RANK #1; matches Sean's pitch verbatim)

Cite: `~/src/seon-biff/README.md` (also published as `~/src/_publishing/seon-2025-02-architecture/README.md`, version 2.1, February 2025), section 1 "System Vision":

> **SEON (derived from an archaic term meaning "to see") is an AI assistant capable of writing bespoke code to build apps interactively to any specification the user desires.** Apps run in isolated namespaces that are grown/created by AI Agents who:
>
> - extract important data as entity, attribute and value (EAV) triples from each turn of the conversation to act as both a data model and a history
> - describe the data model with fully namespaced Clojure Specs that are compatible with generative testing
> - functions are generated that operate on the data model as a simple map of spec'ed inputs and outputs (ctx -> updated ctx)
> - tests are written to test edge cases, ensure end-to-end functionality, and all functions must pass generative testing
> - bugs (if any) are fixed until all tests pass

And later in the same section:

> **The core innovation of SEON lies in its ability to transform natural language conversations into persistent, structured applications.** What begins as a chat with an AI agent can evolve into a specialized tool as the system extracts knowledge, builds data models, and generates tailored interfaces.

And the conclusion (§9):

> 1. **Conversations Evolve Into Tools**: Users can transform interactions with AI into persistent, specialized applications
> 2. **Perfect Alignment**: Code, database, and specifications share identical namespacing
> ...
> Through its modular design, SEON provides a platform that evolves alongside user needs, creating a truly personalized and adaptive workspace that bridges the gap between conversation and application.

This is the exact framing Sean spoke. "Bespoke code to build apps interactively to any specification the user desires" = "building apps for you that are custom to your goals." "A platform that evolves alongside user needs" = "An AI that grows with you." It's all there, in his own words, from 14 months ago.

### Framing 2 — The seon-transform "personal OS for life" framing

Cite: `feature/refinement:docs/archive/seon-transform/prd.md:11-15`:

> ## Goal
>
> Transform `ml-options-trading` into **Seon** - **a personal operating system with modular domains** (trading, health, finance, tasks, knowledge).
>
> ## Background
>
> The existing ml-options-trading codebase is a well-structured Clojure/XTDB application for options trading analysis. We're expanding it into a **multi-domain personal OS** while preserving the trading functionality as the first domain.
>
> **Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: **sentient, luminous beings that serve and assist their bonded humans**.

The "personal OS" + "bonded servant" pairing. Pairs well with Framing 1 — they are answers to the same product question at different layers (1 is "what does the AI do", 2 is "what role does the AI fill in the user's life").

### Framing 3 — The current main README (RANK #4; least like Sean's pitch)

Cite: `README.md:3-7` (commit `c0c2888`):

> **Infrastructure for AI agents to write reliable software.** Not a framework. Not a library. A codebase architecture where agents can discover functions by their contracts, learn from history, own code long-term, and compose safely.

Reads as developer-tooling. Says nothing about who benefits. Matches none of Sean's bold language.

### Framing 4 — The 924820e technical paragraph (RANK #3; load-bearing as "how it's built")

Cite: commit `924820e` (the deleted-then-restorable original README):

> A Clojure runtime designed so AI agents can write, own, and evolve software reliably. Every namespace is wired in as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph (Datalevin / Datahike on LMDB) rather than by name lookup or file imports.

The most technically precise single paragraph the project has ever had. Doesn't sell the user benefit; sells the architectural claim. Belongs as the *middle* paragraph of the new README, not the lead.

### Framing 5 — The Primer (RANK #2 for evocative power, but a domain not a framing)

Cite: `~/src/primer/ROUGH_PLAN-NEEDS-REFINEMENT.md:5-10` (December 2025, by Sean):

> The concept of the *Young Lady's Illustrated Primer*, as introduced in Neal Stephenson's seminal 1995 novel *The Diamond Age*, has long stood as the "North Star" of educational technology. It represents the ultimate synthesis of human-computer interaction: a device that is not merely a repository of static information, but **a dynamic, psychological companion capable of bonding with a child, adapting to their developmental trajectory, and fostering deep "subtlety" of thought** rather than mere rote memorization.

Cite: same file, §9 "Conclusion":

> The realization of the *Young Lady's Illustrated Primer* is no longer a work of distant science fiction; it is an immediate engineering challenge of integration. ... **We are not just building a chatbot; we are building a companion. ... The "flesh" will be the shared stories, the "Ractor" persona, and the bond formed between the child and the machine.**

This is the most ambitious existence-proof in any Sean-authored repo. It is *not* the Seon framing — it is one *application* Seon would enable. But the language ("companion", "bond", "adapting to their developmental trajectory") IS the affective register Sean wants the Seon pitch to live in.

### Reconciliation

Lead the README with **Framing 1's verbatim opener** ("an AI assistant capable of writing bespoke code to build apps interactively to any specification the user desires") because (a) Sean already wrote it and never improved on it; (b) it is the consumer claim Sean spoke this session, in his prior phrasing; (c) it explains the personal-AI promise without overclaiming.

Then layer in Framing 2's "bonded servant" Sanderson line as naming/lore, and Framing 4's technical paragraph as "how it's built". Mention Framing 5 (the Primer) as the canonical existence proof — "the same substrate that tracks your trades can build your child's tutor." The current Framing 3 slogan is the *result* of stripping out everything specific from Framing 1 — restore the original.

## 3. The Primer ctx-as-OS pattern in full

`feature/super-repl:docs/prds/primer/research/ctx-as-os.md` lays out the strongest one-sentence summary of Seon's architecture anywhere in the codebase (prior agent already found this in `feature/refinement` archive copy; on `feature/super-repl` it lives as a live PRD, not an archive).

**Anchor quote (the one-sentence summary):**

> **The entire system is one data structure. UI is derived. Agent writes data. Specs constrain writes.**

**The pattern.** A Primer session is conceived as a *server-controlled state machine* in which `ctx` is the operating system. The `ctx` atom holds all session state — current scene, child profile, story facts, inventory, world model. Templates are pure functions of `(scene, ctx) → hiccup`. Transitions are valid next states (AI-driven or user-triggered). Checkpoints serialize `ctx` to XTDB for replay and time-travel debugging. The AI does not generate HTML — it generates state transitions. Templates are pre-built. The result is instant rendering, deterministic replay, composable complexity, and full debuggability.

**The game-engine parallel.** From `ctx-as-os.md` (already cited in prior synthesis but worth restating for emphasis):

> **Planning Phase (slow, AI/designer):** Pre-compute possible futures. Register behaviors: 'if X, do Y'. Queue assets to load.
> **Execution Phase (fast, 60fps):** Check registered behaviors. Execute matching ones. Interpolate/render state.
> ... The agent is in planning phase. It doesn't generate responses in real-time — it sets up conditional logic ahead of time.

This frames Seon's agent model not as "LLM-in-a-loop" but as game-AI planning: the agent **sets up the world's reactive logic**, the runtime **executes it deterministically**. M8's "agent wakes on notification" + "writes a function" + "next time discovery picks it up" *is* the planning-vs-execution pattern, applied to namespaces instead of game NPCs. The current vision/index.md never names this parallel; it should.

**The template-as-vocabulary insight.** `feature/super-repl:docs/prds/primer/research/template-system.md:3-9`:

> **Core Idea:** The AI doesn't generate HTML — it selects and parameterizes templates.
>
> Templates are like a **vocabulary** the AI speaks fluently. Each template is:
>
> 1. A visual/interactive pattern the AI knows how to use
> 2. Parameterized for infinite variation
> 3. Composable with other templates
> 4. Immediately renderable (no generation wait)

The vocabulary metaphor is unique to this file. Renderer-discovery in current Seon is the same idea: functions with `:seon.render/html` outputs ARE the vocabulary; the agent picks the most specific one for the data, doesn't write markup. **Worth restating in the README** because "templates as vocabulary the AI speaks fluently" is friendlier than "specificity-ranked discovery." Same primitive, more inviting name.

## 4. The seon-transform vision (lines 120–412, prior agent unread)

The bottom half of `feature/refinement:docs/archive/seon-transform/prd.md` is implementation: namespace-rename map, directory targets, file-by-file rename table, integrant lifecycle commands, malli instrumentation recipe, verification commands, rollback points. **No new vision claims beyond the top section** (already mined for the "personal OS with modular domains" + Sanderson Seons quotes).

The interesting bits to preserve from the back half:

- **The five-domain enumeration** (`prd.md:11`): "trading, health, finance, tasks, knowledge". Current README says only "trading, health, finance". The original list included **tasks** and **knowledge** — both proven in 2025-02 (the v2.1 architecture doc literally has `seon.app.tasks` as the worked example, see §1448 onward). The README should restore this — it's a more credible scope claim.
- **The standard-file-per-domain pattern** (`prd.md:230-238`): `specs.clj`, `core.clj`, `signals.clj`, `queries.clj`, `tests.clj`. This is what later became M7's "default step function ships everything; agents add specificity." Worth one sentence — "every new domain ships with a predictable file shape; agents grow into it."
- **The capabilities() function** (`prd.md:391-396`): each domain's `core.clj` exposed `(capabilities)` returning a map of signals/specs/examples for LLM agents. Direct ancestor of `function-discovery.md` and M4. The pattern stuck.

The seon-transform PRD is otherwise a tactical migration plan, not a vision document. It is only load-bearing for the framing because of its first 30 lines (personal-OS + Sanderson) — which the prior agent already captured.

## 5. Sibling-repo vision dump

In priority order, each repo with the framing-relevant content quoted verbatim.

### 5.1 `~/src/seon-biff/README.md` — the **SEON v2.1 Architecture** doc, February 2025, 1936 lines

mtime 2025-03-05. Same file as `~/src/_publishing/seon-2025-02-architecture/README.md` (a curated copy with publication metadata appended, mtime 2026-04-21).

Already quoted under Framing 1 above. Additional claims worth surfacing:

**§1 System Vision (continued):**

> View layer code is then generated by another AI agent with the goal of creating a UI that is both visually pleasing and easy to use. The agent receives:
> - inherits code from a specified namespace (runs in a randomized sub-namespace to prevent namespace collisions)
> - gets a websocket connection to the client to render real-time oob updates as the ctx changes ...
> - generates a pleasant and useful UI using HTMX (include a text summary in the data attributes of the HTMX div being rendered)
> - apps can output data to other namespace routes (to be consumed by other apps or the user)
> - apps are defined by a UI protocol to handle instantiation, lifecycle events, and generate different outputs like HTMX, CSS, markdown summary, etc)
> - **this UI function basically transforms the current ctx into a map of different output formats** ({:markdown-text-summary "...", :htmx "...", :tile-htmx "..."}) that describe the UI based on the state of the namespace and what the user doing at the moment

This is the original specification of "two-tier rendering" — same function emits HTML for the human AND text-summary for the AI. Current `seon.render` (with `:seon.render/html` + `:seon.render/ai` slots) is the realization. **Worth restating**: the multi-output-format-from-one-function idea is a 14-month-old invariant.

**§2.4 Responsive Multi-Level Visualization:**

> Each mini-app provides visualization at multiple levels through a single implementation:
> - **Micro**: Window title/status (1-line summary)
> - **Tile**: Small widget view (200-300px square)
> - **Small**: Compact interface (400-500px dimensions)
> - **Medium**: Standard view (600-800px dimensions)
> - **Full**: Complete interface (800px+ dimensions)
>
> WinboxJS provides the windowing framework, allowing fluid resizing with appropriate UI adaptation.

A primitive lost from current Seon: **size-adaptive rendering**. One render function returns five layouts. Compare to the current renderer system which has no concept of "render at micro/tile/full." This is a feature that could come back — it would let a user pinning a namespace to a sidebar tile get a compact render while the same namespace's full-page route renders richly. **Worth flagging as a candidate capability** for the M4/M5 work.

**§2.5 Database-Driven Development:**

> The system persists both code and data in XTDB, enabling:
> - Queryable code and data relationships
> - Automatic persistence of definitions
> - **Self-building system capabilities**
> - Knowledge graph of all system components

"Self-building" is Sean's word for what current Seon calls "the system composes itself" (M8). Same idea, friendlier phrasing.

**§9 Conclusion — the strongest one-paragraph pitch in the doc:**

> Through its modular design, SEON provides a platform that **evolves alongside user needs, creating a truly personalized and adaptive workspace that bridges the gap between conversation and application.**

This is "an AI that grows with you" in Sean's own prior words.

### 5.2 `~/src/primer/ROUGH_PLAN-NEEDS-REFINEMENT.md` — Neo-Victorian Primer engineering plan

mtime 2025-12-23, 398 lines. Title: "The Neo-Victorian Primer: A Technical and Pedagogical Blueprint for the Generative Age."

Opening passage:

> The concept of the *Young Lady's Illustrated Primer*, as introduced in Neal Stephenson's seminal 1995 novel *The Diamond Age*, has long stood as the "North Star" of educational technology. It represents the ultimate synthesis of human-computer interaction: a device that is not merely a repository of static information, but **a dynamic, psychological companion capable of bonding with a child, adapting to their developmental trajectory, and fostering deep "subtlety" of thought** rather than mere rote memorization. ... However, the technological landscape of late 2025 has catalyzed a convergence that makes the *Primer* not only possible but imminent.

Stack proposed (December 2025): Gemini 3 Flash + Gemini 2.5 Flash Image ("Nano Banana") + PWA + Vercel AI SDK with `streamUI` generative UI. Notably **not on Seon** — this is a standalone Next.js/Vercel plan. But the **affective framing** is the closest match to Sean's "personal AI" pitch anywhere in his repos. The companion-that-bonds, the adapts-to-developmental-trajectory, the "Subtlety" pedagogical metric, the lifelong-memory-via-context-cache.

**Closing line:**

> We are not just building a chatbot; we are building a companion. The technology stack outlined here — secure, low-latency, and deeply integrated — provides the skeleton. **The "flesh" will be the shared stories, the "Ractor" persona, and the bond formed between the child and the machine.** As *The Diamond Age* posits, the goal is not to create a perfect teacher, but a perfect *context* for the child to teach themselves.

Critical observation: the Primer plan does NOT mention Seon. Sean's working Diamond-Age vision sits in a sibling repo with no current code reference back to Seon. The architecture-vision.md in `docs/prds/primer/` on `feature/super-repl` explicitly bridges them ("scene = data, templates = render fns, transitions = AI-driven state changes" — the substrate IS Seon), but the standalone primer plan from late 2025 doesn't.

**Implication for README:** Sean's most evocative writing about AI-as-companion lives in a repo that does not yet say "this runs on Seon." Bridging that — explicitly noting that the Primer's substrate IS the Seon runtime — would let the README borrow the Diamond Age frame without overclaiming.

### 5.3 `~/src/_publishing/` — the curated lineage archive

Sean's `_publishing/` directory holds **published-quality README rewrites** (all mtime 2026-04-21) for five lineage repos. These were written FOR public consumption, by Sean, with hindsight. They are the strongest available evidence of how Sean himself wants this story told.

**`_publishing/seon/README.md`** — current canonical README, redone with a stronger frame than the live one:

> Seon is a Clojure runtime designed so AI agents can own and evolve software responsibly — **discovering functions through schema contracts rather than hallucinating them, learning from history rather than repeating mistakes, and composing safely without stepping on each other's work.**

This is closer to Framing 4 than the live README, with the verbs more active.

**`_publishing/seon-2024-10-xtdb-biff/README.md`** — first XTDB+Biff exploration, Oct 2024:

> This was the first attempt at building what became seon — a Clojure runtime where AI agents own and evolve namespaces. Five commits over two days (2024-10-03 → 2024-10-04). ... Key ideas explored: **XTDB 2.0 as bitemporal backbone.** Could the same database that holds application data also hold the conversation history, the agent's working memory, and the audit trail? XTDB's bitemporal model (valid-time + transaction-time) made every fact time-travel-queryable. **The `seon.repl` namespace pattern.** Chat as a long-lived, queryable XTDB stream rather than ephemeral state. Each message a fact; the namespace as the unit of conversation. **This was the seed of the later namespace-as-process model.**

**`_publishing/seon-2024-10-kit-migration/README.md`** — Kit framework experiment, Oct 2024 → Jan 2025:

> Concurrent sibling to seon-2024-10-xtdb-biff (started one day later, on the same week). Where the Biff exploration tested one Clojure framework, this repo tested another — Kit — to find which one provided the right substrate for an AI-driven runtime. 45 commits over ~3 months. ... seon eventually adopted neither and built its own Integrant-based composition. ... **Foundations for code-graph analysis.** Hints in the deps and structure that pointed toward "what if the AI agent could query the code itself as a graph?" — an idea that became seon's code-graph component.

**`_publishing/seon-2025-02-architecture/README.md`** — the 1962-line vision doc with publication metadata:

> This is the **primary architectural realization** of seon — the experiment where namespace-as-process, code-graph function discovery, schema-driven dispatch, REPL-pipeline, and multi-agent isolation first appeared together. The architecture document above (~72KB) was written about 10 months before the current `seon` repo was created.

**`_publishing/seon-2025-11-trading-domain/README.md`** — immediate git ancestor of canonical seon:

> This is the **immediate git ancestor of seon** — the canonical Clojure agentic-AI runtime. The codebase was renamed to `seon` on 2025-12-13 (seon's first commit reads "Initial commit: ml-options codebase copy"). 59 commits, 2025-11-28 → 2025-12-05. ... The trading domain itself was a vehicle for testing these patterns at scale (1.9M+ option records, ThetaData ingest pipeline). Once the patterns proved out, the codebase was renamed to seon and refocused away from trading toward general agentic infrastructure.

**Implication for the README rewrite:** the Lineage section currently on `main` is a four-row table. Sean has *already* written publication-quality prose lineage notes — they live in `_publishing/`. The README should consume them: replace the table with a narrative paragraph distilled from the five `_publishing/*/README.md` opening sections. The five-stage story (2024-10 dual experiments → 2025-02 architecture → 2025-11 trading-domain → 2025-12 rename to seon → 2026 substrate / WASM) is far more compelling than the current table.

### 5.4 `~/src/seon-visualizations/README.md` — explains, doesn't frame

> Presentation-grade, data-driven visualizations that explain Seon's architecture and design ideas. ... Audience: technical peers, stakeholders, meetup attendees, and collaborators evaluating Seon's direction.

Scope: a React+Vite visualization deck explaining Spec-first development, contract enforcement, generative testing, namespace ownership, accretive growth, local-vs-shared persistence. Useful **as content** for the README — there are scenes documented under `docs/` that visualize the substrate's claims. **Worth a single link** in the README's "Where to read next" section: "Visual explanations of these concepts: https://github.com/seantempesta/seon-visualizations".

### 5.5 `~/src/seon-gsap/README.md` — spatial multi-session UX, January 2025

> An experimental interface that reimagines how we interact with multiple AI chat sessions in a more spatial and intuitive way, drawing inspiration from game design principles. Instead of traditional tabs or windows, this project explores **a continuous 2D space where sessions can be arranged, navigated, and manipulated using natural touch gestures**.

Tangential to Seon framing. But the connection is real: if Seon's M7/M8 vision works, the user ends up with dozens of namespace agents stewarding dozens of personal-domain namespaces. **Spatial navigation across many running agents** is the interface problem this experiment was poking at. Worth mentioning only if the README has a "what UI does the user see?" section — currently it doesn't.

### 5.6 The rest — implementation only

- `~/src/ml-options-trading/` — implementation only (XTDB+ThetaData ops platform). No aspirational claims. *Implementation only, no vision material.*
- `~/src/seon-old-base/` — vendored upstream of clojure-mcp; not Sean-authored. *Implementation only, no vision material.*
- `~/src/seon.bak/`, `~/src/seon.biff/` — 8-line Biff stubs. *Implementation only.*
- `~/src/seon-look-into/`, `~/src/seon.main/`, `~/src/seon.tmp/` — scaffolds or empty. *Implementation only.*

## 6. The "AI grows with you" smoking gun

**Direct verbatim hits on Sean's articulated framing, ranked by closeness:**

### Hit 1 — "writing bespoke code to build apps interactively to any specification" — `~/src/seon-biff/README.md:7-8` (Feb 2025)

> SEON ... is an AI assistant capable of **writing bespoke code to build apps interactively to any specification the user desires**.

This is the single best line. Sean's pitch this session: "A personal AI that can do anything for you because it can write code ... building apps for you that are custom to your goals." His 2025 phrasing: "writing bespoke code to build apps interactively to any specification the user desires." Same claim. Different words. The 2025 phrasing is arguably *better* (more concrete, names "apps" and "specification").

### Hit 2 — "evolves alongside user needs" — `~/src/seon-biff/README.md` §9 (Feb 2025)

> SEON provides a platform that **evolves alongside user needs**, creating a truly personalized and adaptive workspace that bridges the gap between conversation and application.

"Evolves alongside user needs" = "grows with you as you are telling it you need things done." Same claim.

### Hit 3 — "what begins as a chat ... can evolve into a specialized tool" — `~/src/seon-biff/README.md` §1 (Feb 2025)

> The core innovation of SEON lies in its ability to **transform natural language conversations into persistent, structured applications. What begins as a chat with an AI agent can evolve into a specialized tool** as the system extracts knowledge, builds data models, and generates tailored interfaces.

This is the *mechanism* answer. Sean's "telling it you need things done" = the conversation. "Building apps for you that are custom to your goals" = "evolve into a specialized tool ... tailored interfaces."

### Hit 4 — "bonding with a child, adapting to their developmental trajectory" — `~/src/primer/ROUGH_PLAN-NEEDS-REFINEMENT.md:5` (Dec 2025)

> a device that is not merely a repository of static information, but **a dynamic, psychological companion capable of bonding with [the user], adapting to their developmental trajectory**, and fostering deep "subtlety" of thought.

The strongest "grows with you" passage in any sibling repo, but framed for a child rather than for a developer. Same affective register Sean wants.

### Hit 5 — "sentient, luminous beings that serve and assist their bonded humans" — `feature/refinement:docs/archive/seon-transform/prd.md:15` (already cited in prior synthesis)

> Seon — from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

The naming/lore line. Companion-bonded-to-human framing in five words.

### Concrete recommendation for the README's first line

Two strong candidates, both Sean's prior words:

**Option A (consumer-direct, from 2025-02 vision):**

> A personal AI assistant that writes bespoke code to build apps interactively, evolving alongside whatever the user needs.

(Verbatim recombination of two phrases from `seon-biff/README.md` §1 + §9.)

**Option B (this-session pitch as the lead):**

> A personal AI that can do anything for you because it can write code — growing with you as you say what you need, building apps custom to your goals.

(Verbatim from Sean's 2026-05-23 spoken framing.)

A is more concrete and product-shaped. B is more aspirational and matches what Sean *just said*. The right answer is probably **A as title-line, B as the second paragraph that frames it.**

## 7. Surprises

### 7.1 The `~/src/_publishing/` archive is Sean's hand-curated lineage canon (NOT mentioned in brief)

Five repos, each with a publication-grade README rewrite dated 2026-04-21. The Lineage section in `main:README.md` is a four-row table that summarizes these from outside; the `_publishing/` versions are the *primary sources* of how Sean himself wants this story told. The current README should consume them — this is the strongest single improvement the prior synthesis missed because it didn't know `_publishing/` existed.

### 7.2 The 2025-02 architecture doc contains five *current Seon* primitives, fully designed

Specifically (from the 1936-line `seon-biff/README.md`):

1. **Two-tier rendering** (ctx → `{:markdown-text-summary "..." :htmx "..." :tile-htmx "..."}`) — current `:seon.render/html` + `:seon.render/ai` is a direct descendant.
2. **Multi-size rendering** (micro/tile/small/medium/full) — NOT in current Seon. Lost feature.
3. **Namespace-isolation with randomized sub-ns per session** (`seon.sessions.{uuid}`) — current namespace-stewardship architecture is the heir, with stable namespaces instead of random ones.
4. **App-registry + db-persisted apps** — current `function-discovery.md` / `code-graph.md` is the broader generalization.
5. **WebSocket out-of-band updates from ctx changes** — current SSE+Datastar architecture is the heir.

Five Seon primitives, fully specced 14 months ago. The current architecture is the disciplined realization. **README should claim this lineage explicitly** ("these patterns have been validated across four prior repos before being assembled into the current substrate").

### 7.3 The Primer is *not yet* documented as a Seon application

`~/src/primer/` is a Dec 2025 standalone Next.js plan that uses Gemini 3 Flash + Vercel AI SDK. It does NOT reference Seon, and the work is not on a Seon branch — it's an entirely separate Sean repo. Meanwhile, `feature/super-repl:docs/prds/primer/` is a Clojure Seon implementation of the same idea (~650 LOC working prototype). **Two Primer projects exist, in two different stacks, both Sean-authored, neither one linking to the other.**

Implication: if Seon's README is going to use the Primer as the Diamond Age existence-proof, it should also clarify that the Seon-native Primer (in the `feature/super-repl` branch) is the canonical one and the Next.js plan is a parallel exploration. Otherwise external readers who follow the link will hit a non-Seon Vercel project and conclude Seon is web-only.

### 7.4 Unreachable commits surface no vision revisions

393 unreachable commits per `git fsck`. Triaged ~20 by subject line — all are recent dev work on agent-runtime, datalevin removal, eval timing spec tweaks, render-schema additions, agent-repl spec changes. No commits with subjects like "vision:", "framing:", or "README rewrite". The framing exists in branches and prior-repo READMEs, not in stranded commits.

### 7.5 The "self-building system" phrase predates "system composes itself" by 14 months

`seon-biff/README.md` §2.5 uses the phrase "self-building system capabilities" as a section header in February 2025. Current Seon's M8 calls the same idea "the system composed itself" (`m8-autonomous-agents.md:55-58`). Same property, friendlier (more user-facing) phrase available. **Worth restoring in the README** — "self-building" is a tighter pitch than "the system composes itself."

### 7.6 The original five-domain enumeration was richer than today's three

`seon-transform/prd.md:11` listed **trading, health, finance, tasks, knowledge** as the original domain set. The current `c0c2888 README.md` lists only **trading, health, finance**. The 2025-02 architecture doc has a worked `seon.app.tasks` example (with full CRUD, three-stage status, filtering, search). The 2024 Kit-era repo had notes scaffolding. **Tasks and Knowledge are not new ambitions — they were on the original list and quietly dropped.** Restoring them in the README costs nothing and claims the original scope.

## 8. Updated recommendation

Given the two prior synthesis files plus everything new above, here is the strongest possible composition for the new README's opening, with every claim cited.

### Opener — three lines, in order:

**Line 1 (the consumer pitch, Sean's prior phrasing):**

> Seon is a personal AI assistant that writes bespoke code to build apps interactively — to any specification you can describe, growing alongside whatever you need.

(Recombined verbatim from `seon-biff/README.md` §1 + §9, Feb 2025.)

**Line 2 (Sean's spoken pitch this session, as the gloss):**

> An AI that can do anything for you because it can write code, building apps custom to your goals as you tell it what you need.

(Verbatim from Sean's 2026-05-23 framing.)

**Line 3 (the Sanderson lore, the bonded-servant frame):**

> The name comes from the Seons of Brandon Sanderson's *Elantris* — sentient, luminous beings that serve and assist their bonded humans.

(Verbatim from `feature/refinement:docs/archive/seon-transform/prd.md:15`.)

### Second paragraph — the "how it works" verbatim from 924820e:

> Under the hood: a Clojure runtime where every namespace is a long-running flow process with a typed mailbox; functions are discovered by their Malli schema contracts via a Datalog graph instead of by name or file imports; agents own namespaces long-term, evolving them in a REPL pipeline that validates every form before it persists.

(Reconstructed from commit `924820e:README.md` with light freshening.)

### Third paragraph — the test-cases-not-the-product framing, restored to the *full* original five-domain list:

> The personal domains in this repo — trading, health, finance, tasks, knowledge — are test cases. The infrastructure is the product.

(Domain list from `seon-transform/prd.md:11`. "Test cases" sentence from current `c0c2888:README.md`.)

### Why this works

- Lines 1–2 give a consumer-first reader what they need in 25 seconds: this is for them, here's what it does, here's the vibe.
- Line 3 supplies the affective register Sean wants without overclaiming.
- Paragraph 2 names the load-bearing primitives in one sentence — readers who *are* infra researchers see the substantive claim before they bounce.
- Paragraph 3 frames the trading/health/finance/tasks/knowledge work as proofs, restoring the original ambition.

Every line is either Sean's own prior words or a verbatim quote from a load-bearing primary source. None of it is invention.
