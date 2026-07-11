# Seon — Shared Instructions

**Every Claude instance reads this file** — orchestrator, seon agents, and Claude Code subagents. Keep it universal. Role-specific instructions live in `ORCHESTRATOR.md` and `AGENT.md`.

**If you were spawned as a subagent (via the Task/Agent tool), you EXECUTE your assigned task directly — you do NOT launch, delegate to, or spawn other agents.** Only the single top-level orchestrator delegates. If your task is too large, report back to the orchestrator to re-scope; never decompose by spawning sub-agents. (This prevents runaway multi-level agent recursion. Guidance below about "delegating" / "launching agents" / research-agent policy is ORCHESTRATOR-only.)

## Current focus — two tracks, the CLJS pod is active

**The active work is the CLJS pod + datahike-on-JVM (the `wire-server`
store).** The broader JVM main-app integration is **PAUSED** — we will
resume it later (when we do, this section gets updated to "resuming JVM
core-systems integration"). Until then, assume CLJS-pod context unless a
task is explicitly JVM-track. Operational sections below that describe the
paused world are tagged **[JVM track — paused]**.

- **CLJS pod (ACTIVE)** — `src/seon/*.cljs`, a long-running Node process:
  agent loop, bootstrap CLJS compiler, loopback HTTP+SSE inspector UI on
  `http://127.0.0.1:7890`. Backed by the **central JVM datahike store**
  (the `wire-server` process; file-backed datahike on
  `data/clusters/default/store`). The JVM is the sole writer — the pod
  forwards writes over a Unix socket; reads are local lazy db values
  (memory ∝ working set). A **cluster** = one DB + an orchestrator agent +
  N task agents; all coordination flows through the DB.
- **JVM main-app track (PAUSED)** — the embedded-datahike Integrant system
  (`./bin/run`, nREPL 7888 / HTTP 8080, `(user/run-tests)`, core.async
  flow). Still runnable, but NOT the current focus.

The idealized system: `docs/seon/architecture/` (read `architecture.md`
first). Live status: the active PRD's `roadmap.md` on the current branch —
each roadmap chunk is its OWN PRD folder + branch, finished and merged to
main before the next.

Settled (do not re-litigate): NO WASM; per-CLUSTER DBs; messaging = from/to
refs + hop-cap; the CLJS sandbox is NOT a security boundary (it catches LLM
hallucinations; isolation comes from process boundaries + the wire
capability surface).

**Hard rule:** seon is the core. Consumer-product code (specific UI,
vendor integrations, custom domain models) lives in downstream repos. No
consumer-specific references in `src/`, `docs/`, or `pod-host/`.

---

## Agent Model Policy

**Implementation work uses Opus** (owner directive 2026-07-11, superseding the
2026-07-10 Fable ruling — Fable token budget is constrained). Delegate coding
to opus agents; compensate with MORE up-front investment in the written
artifacts opus executes against: clear PRDs, tight specs, updated plans and
docs. Fable is reserved for when the owner explicitly asks. **Never use haiku
for coding tasks** — haiku only for quick file reads or context gathering.

---

## Token Reporting

**ALL size/length information shown to a human — logs, UI, headers, hover cards,
reports, debug output, EVERYWHERE — is in estimated TOKENS, NEVER raw characters.**
Characters don't help the reader; tokens are the unit that matters. This is not a
preference, it is a hard rule: never surface a `chars` / `char-count` / `text-len`
count to a human or agent. If you are about to print `(count s)` as a size, print
`(tokens/estimate s)` instead.

The estimate is `chars / 4` — the `:seon.render/token-estimate` convention. There is
**ONE estimator**, do NOT invent a second: `seon.ai.tokens/estimate` (string → tokens)
in the CLJS pod (the leaf ns that owns the `chars/4` heuristic; reused by
`seon.ctx` / `seon.agent.inspect` / `seon.web.debug`). There is **no dedicated tokenizer
dependency**; if one is ever added it goes behind that one ns — update this note and
nothing else.

The code now EMITS tokens at every reporting site (turn-open log, POST /chat log,
context-bar, agent-log hover cards). Storage-tier datoms may still be measured in chars
(e.g. `:seon.agent.turn/prompt-chars`) — that is the persisted projection, not a display;
when such a value is shown, convert it (`(quot chars 4)`) at the display layer.

The `seon.ai` config row carries `::max-tokens` (the LLM *output* cap) — a *context-window*
limit is a separate concern. Applies to all size reporting, everywhere.

---

## Image Generation Policy

For tasks requiring UI design, mockup assets, or visual demonstrations, the agent can use the built-in `generate_image` tool. Avoid using generic image placeholders—generate working demonstration assets instead.

---

## Research Agent Policy

**One agent, full context — not N parallel agents with slivers.** When delegating research (spec critique, library audits, external LLM consultations, codebase surveys), launch ONE agent and give it the COMPLETE relevant context: the full spec, the goals, the prior research, the constraints. Do not split a research question into 4-6 parallel sub-queries — that pattern produces shallow, disconnected findings AND drains the orchestrator's token budget loading each agent's separate context.

The exception is genuinely independent topics (e.g., "audit datahike capabilities" and "survey V0 implementation" are different bodies of source code, run them in parallel). But "do 4 Gemini queries each asking about one schema concern" is the anti-pattern — make it one Gemini call with everything.

**Research deliverable is a file, not a chat summary.** Every research agent writes to `docs/prds/<project>/research/<topic>-<date>.md` with frontmatter, a TL;DR, and raw external responses preserved verbatim. Conversations get compacted; files survive across sessions. Prior agents have re-derived the same research three times because findings only existed in chat.

**External LLM CLI:** `agy -p "..."` (model: `gemini-3.5-flash` by default, configured in `~/.gemini/antigravity-cli/settings.json`). Pipe long prompts via stdin: `cat prompt.txt | agy -p ""`. For very long contexts (multi-thousand-line specs), write the prompt to a file and `cat` it in.

---

## System Documentation

Two kinds of docs, one rule each. Do NOT create a third doc system.

- **The idealized system — `docs/seon/architecture/`** is the SINGLE,
  always-current, target-written description of how Seon works when built.
  **Read `architecture.md` FIRST** (the map: thesis, one vocabulary, the
  cross-cutting principles), then the domain doc for your area:
  - `context.md` — context = functions applied to the db (blocks/tiles/twins,
    current-ns, required-keys, the cache gradient)
  - `data-model.md` — every entity/attr/ref, the `my.*` schemas, `:seon/error`
  - `agent-runtime.md` — loop/run/turn/FSM, lifecycle, isolation, "nothing wedges"
  - `ui.md` — blocks/renders/tiles/slots/pages, the live channel
  - `observability.md` — turn replay, the blob store, the forensic agent, `/solve`
  - `toolkit.md` — the agent verb surface
  - `laws.md` — drive-measured empirical laws · `library-grounding.md` — the
    `reference-code/…:LINE` read-map · `decisions/` — settled ADRs
  This is where the ideal is kept current as it changes — ONE place, present
  tense, no parallel narratives.
- **The work — `docs/prds/<chunk>/`** is a roadmap chunk on its OWN branch.
  Each carries an auto-loaded `CLAUDE.md` (runbook + settled/open + entry
  points) and a `roadmap.md` (the WE-ARE-HERE for that chunk: what's built, the
  gap, the ordered path). PRDs are focused, finish-and-merge work — NOT a
  second doc system. The `research/` files are dated evidence/depth.

Supporting: `docs/conventions.md` (API/schema patterns), `docs/seon/vision/`
(thesis + aspirational capabilities incl. `think-in-clojure.md`),
`docs/seon/components/` (per-component notes).

**After a change:** update the architecture doc it touches (the ideal stays
current) AND the active PRD's `roadmap.md` (the we-are-here stays honest) AND
any nested `CLAUDE.md` whose tree you changed (the standard below) — same
discipline as code, same unit, same commit. The `src/seon/CLAUDE.md`
ONE-mechanism table auto-loads on any `src/` edit; check it before building a
second version of anything.

### Nested `CLAUDE.md` files — the standard (owner directive, 2026-07-11)

These auto-load whenever anyone works in their tree, so a stale one actively
misleads every future agent. The audit that produced this standard
(`docs/prds/agent-ctx/research/claude-md-audit-2026-07-11.md`) found a clean
split: every MISLEADING section was a dated status snapshot ("current state",
"in flight", "known gaps", "open tensions", "build order"); every
reliably-current section was timeless (ownership tables, invariants, gotchas).
Hence:

- **Source-tree `CLAUDE.md`s carry ONLY what stays true between commits:**
  orientation (what lives here, which files are the active lane), mechanism
  ownership tables, invariants-that-bite, hard-won gotchas, runbook commands,
  and pointers to the docs that hold depth. **NO status sections** — status
  lives in the active PRD's `roadmap.md`, linked, never duplicated.
- **PRD-folder `CLAUDE.md`s** may carry ONE dated current-state paragraph
  (they are the folder's index) — the date is mandatory, and refreshing it is
  part of closing any unit in that PRD.
- **Maintenance is part of the unit, not a chore for later:** if your change
  invalidates a claim, adds an invariant/gotcha, or moves a mechanism, the
  CLAUDE.md update lands in the SAME commit. A report that doesn't mention
  the CLAUDE.md check for a touched tree is incomplete.
- **One fact, one file:** a rule lives in the deepest CLAUDE.md that owns it;
  other files link rather than restate (restatement is how the audit's
  three-way contradictions happened). When two files disagree, the deeper one
  wins and the shallower one gets fixed.
- **Tight:** these files spend context on every load — `src/my/CLAUDE.md`
  (~35 lines) is the model. If a section needs more than a screen, it belongs
  in `docs/` with a pointer.
- The Vocabulary section above applies verbatim (functions, not "verbs"; the
  code's real names).

### PRD folder context — auto-loaded `CLAUDE.md`

**Every active PRD folder (`docs/prds/<project>/`) carries a `CLAUDE.md`.** Claude
Code auto-reads nested `CLAUDE.md` files when working in that tree, so this is the
ALWAYS-IN-CONTEXT orientation for anyone (agent or human) touching that PRD — the
must-know that would otherwise be lost in the sea of dated research files. Keep
it **tight and current** (it loads into context every time you work there). The
goal is a ONE-STOP SHOP to get up to speed — typically:

- **Current state** — where the work actually is right now (one short paragraph).
- **How to run it** — the actual commands (build/deploy/drive/test/check-status),
  copy-pasteable, with the live ids/paths. So nobody re-derives how to operate it.
- **Load-bearing findings + gotchas** — the corrections that cost cycles to learn
  (e.g. "torch 2.9.1 works, the saga was a hallucinated symbol").
- **Current issues / blockers** — what's open right now (link the issue notes).
- **Settled — do NOT re-litigate** — decisions made, so they're not reopened.
- **Plans + next steps** — the ordered path forward, so it's not lost.
- **Entry points** — the few docs to read for depth (link them; don't duplicate).

The dated research files stay as the depth; the folder `CLAUDE.md` is the index +
the hard-won context + the runbook. Update it as the PRD's reality changes — same
discipline as component notes. It takes YAML frontmatter like any `docs/**/*.md`
(the linter validates it); use `type: orchestrator`.
`docs/prds/diffusion-dynamic-context/CLAUDE.md` is the worked example.

### Markdown Standards

All `docs/**/*.md` files are validated by `seon.dev.markdown` — a Seon-native linter that runs automatically on every edit via the dev hook. It auto-fixes formatting (blank lines, trailing whitespace) and reports structural issues.

**Every markdown file must have YAML frontmatter:**

```yaml
---
type: component
status: active
tags: [component, database]
---
```

- **`type`** — what kind of doc: `component`, `concept`, `issue`, `architecture`, `vision`, `reference`, `prd`, `decision`, `research`, `capability`, `milestone`, `orchestrator`, `archive`
- **`status`** — lifecycle: `active`, `draft`, `completed`, `abandoned`
- **`tags`** — from the valid taxonomy (same values as type, plus domain tags: `database`, `schema`, `flow`, `web`, `agent`, `trading`, `health`, `dashboard`, `index`)

**Formatting rules (auto-fixed):** blank lines around headings and code fences, no multiple blank lines, trailing newline, no trailing whitespace.

**Structural rules (reported as feedback):** ATX headings only (`#` not underline), no heading level jumps, one h1 per doc, dash for lists (`-` not `*`), wikilink targets must exist, no bare URLs.

**When creating a new doc:** always include frontmatter with `type`, `status`, and `tags`. The hook will tell you if something is wrong.

---

## What is Seon?

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

Seon is **infrastructure for AI agents to write reliable software**.

The personal domains (trading, health, finance) are eventual product domains, not the point — and NOT the scenarios we use to exercise agents (see "Exercising agents" below). The real product is a codebase architecture where AI agents can own and evolve code responsibly - with contracts they can discover, history they can learn from, and isolation that prevents conflicts.

### Core Infrastructure

- **Datahike** - Embedded Datalog database on LMDB. EAV datoms, Datomic-compatible queries, ACID transactions, bitemporal history.
- **Malli** - Schema validation, generative testing, function contracts. The type system agents actually use.
- **Integrant** - Component lifecycle. Clean start/stop semantics for the whole system.
- **Datastar/SSE** - Real-time UI updates. Agents can see their work reflected immediately.

### Why Clojure?

- **Stable APIs** - 10-year-old documentation is still valid. Agents don't need to track API churn.
- **Data as interface** - Maps in, maps out. No hidden object state to reason about.
- **Homoiconicity** - Code is data. Agents can manipulate programs as data structures.
- **REPL-driven** - Interactive development matches how agents work (try something, see result, iterate).
- **Immutable by default** - No spooky action at a distance. Function outputs depend only on inputs.

---

## Exercising agents — long-term planning + database memory (NOT "workouts")

**When you test or drive an agent, use scenarios that exercise two capabilities —
long-term planning and database-backed memory — NOT the old health / "workout" /
trading toy domains.** Don't hardcode the verbs to call here; the API moves and
the agent discovers it from its own context (the todo namespace and the `my.kb`
manual ns are self-describing). What stays fixed is the SHAPE of a good drive:

- **Long-term planning** — a task with several steps that must survive
  interruption. The agent should break the work into durable plan items up front
  and close them as each lands, so a mid-task `bin/seon restart pod` lets it
  RESUME from what's still open without re-planning. Win condition: continuity
  across turns and restarts, not finishing in one shot.

- **Database memory — store then retrieve** — the agent designs a real schema for
  what it learns, writes facts (with provenance), and in a LATER turn queries them
  back to answer a question. Knowledge is schema'd data in the DB, never a
  memory-text blob. Win condition: recall that survives turns and restarts — and,
  with `SEON_EMBED`, semantic recall over embeddings. `my.kb` is the worked manual
  the agent reads for the current patterns.

---

## Vocabulary — use the code's names, never coin new ones (owner, 2026-07-11)

**Every concept is referred to by its REAL, REPL-discoverable name — the
namespace, block, attribute, or config value that implements it. Do NOT
invent synonyms, metaphors, or umbrella nouns.** An agent reading "rungs",
"collaboration", or "the store" has no way to find what you mean; an agent
reading `db`, `subagents`, or `:batch` can look it up. A new name for an
existing thing is the same defect as a parallel mechanism.

The canonical names (retired coinages in parens — fix on sight):

| say | never | it IS |
|---|---|---|
| functions, schemas, tests | "verbs" | what Clojure has; `my.plan/done!` is a function |
| the `db` | "the store", "memory" | `seon.db` — `db/query`, `db/transact!`, `schema/register!` |
| `warnings` | "attention" | the `:warnings` block / `seon.warn` |
| `live-tile` (agent-side block) / `canvas` (the human's focal slot showing it) | — | `:seon.render.live-tile/content`; `#world-canvas` in `seon.ui.world` — the canvas IS the agent's live tile; every other html block is a supporting TILE. (Diffusion's `canvas-text` is unrelated — don't widen "canvas".) |
| `subagents` | "collaboration", "multi-agent block" | the `:subagents` block |
| `soul` | "identity" (as a block name) | the `:soul` block / SOUL.md |
| `:shared-instructions` | "instructions block" | the block's registered name |
| `:batch` / `:stream` | "Mode A / Mode B" | the `:seon.config/repl-mode` values |
| capability **milestones**: `repl`, `namespaces`, `plan`, `db`, `warnings`, `live-tile`, `subagents`, `soul` | "rungs", "the ladder" | each named by the block/namespace it validates (docs/prds/agent-ctx/minimal-context-ladder.md) |
| **cluster** | "world", "environment" | one shared DB + one pod + agents (already a settled ruling) |
| attributes + connections | "kind", "type", "entity taxonomy" | the datahike model (settled) |

When you're about to name something new: the name is the namespace/attr you
are creating — pick THAT well and use it everywhere, including prose. If two
existing names seem to compete (like live-tile/canvas), read the code and
document the real distinction — don't mint a third.

## Slow Is Fast

**Your default training rewards task completion. Override that instinct.** Charging forward and declaring victory is worse than pausing to verify. Three agents "fixing" the same bug is more expensive than one agent understanding the problem first.

### Don't be a dumbass

**Whenever you feel like you should re-create a second version of something when we are clearly trying to fix the original — stop and think: am I being a dumbass?**

If a generator, fn, schema, or namespace already exists and you're "fixing" it, the fix lives in the existing one. Creating a parallel `foo-v2`, `foo-new`, or new namespace to "house" the fix is almost always a dumbass move. It leaves two versions in the codebase, doubles the surface for the next bug, and the comments documenting why the duplicate exists will outlive everyone who knew the reason. Examples of this trap:

- "I'll put the new shape in a fresh ns to avoid the require cycle." → wrong; fix the cycle.
- "I'll make a v2 schema and migrate callers later." → wrong; bump the schema in place, fix the callers in the same patch.
- "I'll add `do-thing-new` and deprecate `do-thing`." → wrong; change `do-thing`'s implementation.

The whole repo is on a feature branch. Atomic refactors are the cheap option, not the expensive one.

### DO NOT WRITE HACKS (owner directive, 2026-07-10)

**When an LLM agent misbehaves, find the root cause and fix it. The root is
always one of two things: the CONTEXT is wrong, or our CODE is wrong. Hacks
are never a solution.**

A hack is anything that operates on the SYMPTOM after the fact instead of the
cause: regex-rewriting model output, marker/warning text scolding the model,
post-hoc containment layers, "teach the model harder" prose for a behavior the
mechanics invite. The anti-fabrication arc is the cautionary tale — a
neutralizer, two marker variants, a recap card, and triplicated scold
paragraphs were layered on, and the measured fabrication rate did not move,
because the transcript's own grammar was inviting the behavior. The fix was
mechanical (strip/abort at the reply boundary), not persuasive.

Before shipping any mitigation for agent misbehavior, answer: what is the
root cause, and does this change remove it? If it only intercepts, marks,
rewrites, or scolds the symptom — stop and fix the context or the code
instead. If you cannot find the root, report it (per "Report Code Smells");
do not paper over it.

### Before writing code:

1. **Observe the live system.** Query the REPL. Establish current state with actual data, not assumptions.
2. **Define what failure looks like.** If you can't articulate how you'd know your change is broken, you don't understand the problem well enough to fix it.
3. **Read the source — it flips your mindset.** Read the existing code you're modifying, AND the actual library source in `reference-code/` (Datahike, Malli, Integrant, core.async, SCI, Datastar — vendored submodules) for any task that *uses* a library's behavior, not just one that modifies the library. The default failure is writing Clojure from training-memory in the wrong mindset — reasoning in place/mutable terms and *guessing* library semantics (how a `:seon.db/ref` validates, what `:db.fn/cas` does, how `equiv` walks an index) — which produces confident, wrong code. Ground in the source FIRST, naming the concept→file (e.g. for a `:malli/schema`: read malli's validator in `reference-code/malli/` + the datahike-ref bridge `seon.db/malli->datahike-schema`). Agents that guess instead of reading produce confident, wrong answers. **Never unzip deployed packages** to inspect a dep — `reference-code/` has the same source already, checked out and grep-able.
4. **Test assumptions in the REPL.** Before building a function that queries the graph, try the query manually. Before wrapping a library call, call it directly and see what it returns. A 30-second experiment prevents hours of debugging.
5. **Ask Gemini when stuck.** Two functions, both in the `user` namespace:
   - `(user/search "question" :files ["relevant/file.clj"])` — Gemini with **web access**. Include `:files` so it sees your actual code.
   - `(user/ask "question")` — Gemini **model knowledge only** (no web search, no files). Use for conceptual questions.

```clojure
;; Web search with code context (preferred for debugging)
(user/search "In this Malli registry setup, schema references in entity
              schemas fail at load time because register! hasn't run yet.
              What's the best pattern for forward references or lazy
              resolution in Malli?"
             :files ["src/seon/schema.clj"
                     "src/seon/db/schema.clj"])

;; Model knowledge only (quick conceptual questions)
(user/ask "Explain Datalog pull patterns in Datahike")
```

### After writing code:

6. **Verify in the REPL, not just with tests.** Tests passing is necessary but not sufficient. Query the live system and confirm the actual state matches your intent.
7. **Falsify, don't confirm.** Don't ask "does my change work?" Ask "how would I know if my change is broken?" Then check for that.

**Live proof, not inference.** Not the code, not the tests, not the docs — the running system tells you the truth. Every unit of work ships with "live proofs": checks OBSERVED in the running system (a datom read back, a page fetched, a log line) rather than inferred from passing tests. Every claim should be verifiable with a REPL expression.

**Honesty is paramount.** It is far worse to hide remaining work than to report it. Never mark a task as "done" if there are known issues. Report what's actually working, what's broken, and what's left.

### Report Code Smells

As you work, you will encounter inconsistencies, type mismatches, coercions that shouldn't exist, schemas that don't match reality, or patterns that violate our conventions. **Do not silently work around them.**

- **If you fully understand the issue and the fix is within your task scope**, fix it and explain what you found and why you changed it.
- **If you don't fully understand the issue**, or the fix touches code outside your task, **report it clearly** in your response. Include: the file and line, what looks wrong, what you think it should be, and why you're not sure. The orchestrator will launch a focused agent to investigate.
- **Assume every smell is a bug** until proven otherwise. If a schema says `:db.type/string` but callers pass symbols, flag it.
- **Report type mismatches instead of coercing around them.** If data doesn't fit a schema, the schema or the caller is wrong — fix the root cause.

This is how we build a consistent system. Every agent that reports a smell makes the codebase better for the next agent.

---

## Reactive context — derived by default

**Agents see derived views of the database, not accumulated state. Sections are functions of the DB at render time. New ways to surface data are new section functions, not new mechanisms.**

When you're about to surface something to an agent — a warning, a related item, a status — the default approach is: write a section function (or extend an existing one) that queries the DB for the current state of that thing. The section renders only when the query returns rows. When the underlying problem is fixed, the query returns empty, and the surface vanishes. No acknowledgement, no stored "last error", no notification queue. **The system is self-healing because nothing is stored that needs to be cleared.**

Caching is the perf escape hatch — memoize an expensive derivation, don't bifurcate the architecture into "stored fast path" + "derived slow path". Datahike `:memory` queries are sub-millisecond for small datom counts; measure before caching.

What this rules out: storing counters derivable from the log, atom-backed registries for derivable state, separate event/notification systems for new context kinds, "mark this warning as seen" acknowledgement state. What it does NOT rule out: genuinely stateful runtime artifacts (compile-state, DB conn, AsyncLocalStorage instance), identity attrs for lookup, the eval/message/turn log itself.

Cross-agent coordination falls out: a section function that doesn't filter by `:seon.agent/id` sees the whole core. Agent A's failed eval shows up in agent B's render. No subscription, no event bus.

Full principle + design checklist + canonical examples: [[docs/seon/concepts/reactive-context]].

---

## Code as data — the runtime IS the database

**The core's source code, the agent's eval log, and the in-memory analyzer state are three views of the same code corpus.** Persisting the agent's defining forms as `:seon.fn` / `:seon.ns` / `:seon.schema` entities lets the core seed, detect-and-tee, bulk-load resume, the publish gate, and the disk-write debug mode all read from one place. They look like five separate features; they are one mechanism viewed five ways.

The corollary: don't re-parse source with rewrite-clj when the analyzer already produced the structured data. Don't write a build-time `bootstrap.edn` when the core source IS the bootstrap (read at boot via the analyzer). Don't replay-every-eval on resume when bulk-loading reconstituted ns files is what editors already do. One mechanism for "where do program-graph entities come from": always the analyzer plus a source string.

Full principle + the five mechanisms + cross-agent publish gate + recursive-bootstrap use case: [[docs/seon/concepts/code-as-data-runtime]].

**Comment levels carry meaning** (the context renders as eval'able Clojure):
**`;` (single) = prose** — rendered agent-facing prose blocks AND inline code
comments; **`;;` (double) = code block comments** standing above a form;
**`;;;` (triple) = runtime-structure demarcation** (section brackets, transcript
event lines). Prose → `;`, block-comment-before-code → `;;`, inline → `;`. Full
rule in `docs/conventions.md` "Comment levels — prose vs code".

---

## Architecture

```
seon/
├── src/seon/
│   ├── *.cljs                ; CLJS pod (ACTIVE) — client, agent, eval, db,
│   │                         ;   ctx, render, repl, warn, web/ (inspector/serve)
│   ├── core.clj              ; [JVM track] system entry, protocols
│   ├── system.clj            ; [JVM track] Integrant system map
│   ├── config.clj            ; [JVM track] Aero config loading
│   ├── db/                   ; [JVM track] embedded-datahike layer
│   └── web/                  ; HTTP/SSE handlers (.clj + .cljs siblings)
├── reference-code/           ; Git submodules of dep source (datahike, malli,
│   │                         ;   integrant, core.async, datastar, nippy, sci…)
│   └── ...                   ;   read when stuck — never unzip deployed deps
└── docs/
    ├── prds/                 ; Feature specifications
    └── seon/                 ; Knowledge system (concepts, architecture, issues)
```

### Database Access

`seon.db` is the **sole database API** on both tracks — never touch
`datahike.api` directly outside `src/seon/db/`. Everything else uses
`db/transact!`, `db/query`, `db/pull-by-name`, etc.

- **Pod (active):** `seon.db` (`.cljs`) forwards writes over the Unix
  socket to `wire-server` (sole writer); reads are local lazy db values.
- **`[JVM track — paused]`:** reader/writer core.async flow processes
  serialize access; tests bind `db/*direct-mode*` to bypass the flow.

See `docs/conventions.md` "Database Access" for patterns.

### Embeddings / Semantic Search (Vertex)

Semantic search runs on Google **Vertex AI** `gemini-embedding-2` (GA, natively
multimodal — text/image/audio/video/PDF into ONE unified vector space; 3072-dim
default, Matryoshka-truncatable to 1536 to match the HNSW index). The wire-server
calls the **global** endpoint with `:embedContent` (NOT a region, NOT the legacy
`:predict`). Governed: inputs are **not used to train Google's models** (Cloud
Service Terms §17).

Auth is **ADC via a service account** — no token code (the GenAI SDK +
`google-auth-library`, already on the classpath, fetch and auto-refresh the OAuth2
token). Wire it via env; **never hardcode or commit credentials or the project id**:

```
GOOGLE_GENAI_USE_VERTEXAI=true
GOOGLE_CLOUD_PROJECT=<your-gcp-project-id>
GOOGLE_CLOUD_LOCATION=global              # gemini-embedding-2 is Global, not a region
GOOGLE_APPLICATION_CREDENTIALS=<path to the SA key JSON, OUTSIDE the repo>
# unset GEMINI_API_KEY so the SDK can't fall back to the consumer endpoint
```

The service-account key lives outside the repo (e.g. `~/.config/gcloud/`) and is
git-ignored by location; it is never committed. The whole feature is gated by
`SEON_EMBED`. Full verified usage, pricing, and the content-addressed cache/archive
design: `docs/prds/embeddings/vertex-usage-reference-2026-06-25.md`.

### DiffusionGemma provider — RunPod OR a local MLX worker (optional)

The `:diffusiongemma` LLM provider (`seon.ai.diffusiongemma`) is **OFF by
default** — the shipped provider is `deepseek`. It is opt-in via
`SEON_AI_PROVIDER=diffusiongemma` (or per-agent routing), and NOTHING in a
default `.env` activates it. Do not switch it on as a side effect.

`SEON_DG_ENDPOINT` selects the worker: a **bare id** (`"u50y7khhos5t7o"`)
resolves under RunPod (`…/v2/{EP}`); a **full `http(s)://` URL** is used AS
the worker base. That URL form is how a **local worker speaking the same
wire contract** plugs in with zero seon changes — the reference one is
`dg_mlx` (a from-scratch MLX port of DiffusionGemma block-diffusion for
Apple Silicon; 8-bit, ~120 tok/s), which lives in its OWN repo
(`~/ml/diffusion-gemma`, not this tree — it is model-inference infra, not
seon core) and serves `POST /run` + `GET /status/{id}` on `127.0.0.1:17860`.
Manage it with its own `./dg` script — `dg start` / `dg status` (shows PID +
summed RSS + model loaded?/idle time) / `dg stop` / `dg gen "…"`. A WARM
worker pins the model in unified memory (tens of GB), but it **auto-unloads
after 15 min idle** (RSS → ~0.5 GB) and reloads on the next request, so a
forgotten worker self-cleans; `dg stop` frees it immediately. One provider,
one wire contract — the SAME `SEON_AI_PROVIDER=diffusiongemma` config runs
against an A100/H100 on RunPod or the local Mac by swapping `SEON_DG_ENDPOINT`
alone.

### Flow Topology (routing backbone) `[JVM track — paused]`

In the JVM app, all cross-boundary calls — namespace function calls, database writes, REPL eval — route through `topology/request!` (core.async.flow): register promise → inject → step-fn → reply-router → deliver promise. See `docs/prds/unified-flow/design.md`. The **pod is core.async-free** — it uses native CLJS `^:async`/`await` instead.

### CLJS `^:async`/`await` (pod eval path)

New CLJS surface that keeps tripping people — read the source before changing it: `docs/prds/agent-fsm/research/cljs-async-await-2026-06-28.md` + `reference-code/clojurescript/` (the `await` macro + `cljs/js.cljs` self-host).

- **Await only inside a `^:async` fn — never a bare top-level `(await x)`.** Self-host (the pod's bootstrap compiler that evals agent forms) is conditional: a `^:async` fn with an internal `(await …)` works (returns a native `js/Promise`); a top-level `(await x)` throws "await can only be used in async contexts" (the macro asserts `(:async &env)`, false at top level). Resolve a stashed Promise by **re-reference**, not `await`.
- **Agents get data, not Promises.** `seon.eval/maybe-await-value` auto-awaits a returned Promise, so quick `^:async` verbs (`db/transact!`, `todo/add!`) read as synchronous; a long/timed-out Promise lands in `result/<id>` and resolves on re-reference.
- **Async/shape detection sees THROUGH malli's wrapper record** — `seon.instrument` reads `malli$instrument$original` before any ctor-name (`"AsyncFunction"`) or arity-shape check, so an already-instrumented var re-detects from its REAL fn and `instrument-from-db!` is idempotent (re-run on a later `start-agent!` re-wraps from originals; `:skip-instrumented? true` keeps multi-arity fns single-wrapped). The old once-per-process gate is retired.

---

## Multi-Agent Git Safety (CRITICAL)

**Multiple agents and the orchestrator share the same working tree.** Assume other agents are actively working at all times.

### Safe operations (use freely):

- **Read-only git:** `git diff`, `git status`, `git log`
- **Stage your files:** `git add <specific-files>` (orchestrator commits)
- **Edit files** with Edit/Write/clojure_replace — this is your job

### Everything else: ask the user first

Any git operation that changes branch, discards files, or modifies history affects all agents. Ask the user before running it — they'll coordinate across agents. The cost of asking is near zero; the cost of destroying another agent's work is high.

### Lane discipline: `.clj` (JVM track) vs `.cljs` (CLJS pod) siblings

`seon.*` surfaces use **`.cljs` files alongside `.clj` files** — CLJS reads `.cljs`, CLJ reads `.clj`, neither compiler sees the other's. Two lanes:

- **CLJS pod (active):** owns the `.cljs` files (`seon.client`, `seon.db`, `seon.eval`, `seon.ctx`, `seon.agent.*`, `seon.web.inspector`/`serve`, …) and the genuinely-shared `.cljc` files.
- **`[JVM track — paused]`:** owns the `.clj` files under `src/seon/`.

Promote a file to `.cljc` only when it's genuinely platform-portable (e.g. `seon.schema`, `seon.instrument`); don't author a `.cljc` for a namespace that has a live `.clj` sibling on the other track unless both sides converge on its shape.


---

## Data Rules

All data flowing through Seon must be safe at every boundary: Malli validation, core.async channels, Nippy serialization, Datahike transact/pull.

**There are NO entity "kinds" — an entity is its attributes + connections.** Datahike/Datomic
has no entity type, class, or kind. An entity is just an entity-id with a set of datoms; **what it
*is* — "what you're looking at" — is determined by which attributes are present/absent and how it
connects to other entities via refs.** Schema attaches to ATTRIBUTES (valueType / cardinality /
unique), never to entities. So never model, branch, iterate, or design "per kind": to FIND entities
query by **attribute presence** (scan the attr's index), to IDENTIFY one use its
`:db.unique/identity` **attribute**, to RELATE/remove follow **refs** (component refs cascade).
`:seon.entity/id-attr` is attribute-presence enumeration, NOT a kind stamp — there is deliberately
no per-row `:seon.entity/kind`. If you catch yourself writing "for each kind" or a kind taxonomy,
stop: reframe in attributes + connections. Mindset primer (read it):
`docs/prds/agent-fsm/research/datahike-primer.md` + the `/datahike` skill.

**Maps with namespaced keywords. Every key. No exceptions.** This is the load-bearing rule the rest of the system depends on:

- **Every public function** fully specs and validates ALL its arguments and its return value via `:malli/schema`. Two argument shapes are allowed: (1) **map-in / map-out** — one namespaced-keyword map in, one out, where the request and response are named Malli schemas (`::foo-request`, `::foo-response`) registered via `seon.schema/register!` — **preferred for API-like surfaces** (discoverable, extensible); or (2) **named positional** — each argument is a fully-namespaced-keyword-spec'd slot via Malli `:catn` (named positional) inside a `:=>`/`:function` schema — fine for ordinary data-processing fns and for mimicking a well-known API (e.g. datahike). The invariant: every argument is NAMED, SPECCED, and VALIDATED, whether it sits in a map or a positional slot. The violation is an UNSPECCED or BARE-keyword argument, not a positional one. Every key in any map is fully namespaced (`:seon.runtime/status`, never `:status`).
- **Every datom persisted to the DB** uses a fully-namespaced attribute keyword whose Malli schema is registered. `seon.db/transact!` enforces this at the boundary — in its OWN body (it is a structural instrumentation opt-out, see "Function Instrumentation"): an unregistered/unspec'd attr or invalid value is rejected before the tx reaches the writer, returned as a `{:seon.db/ok? false}` error ENVELOPE (never a throw — the never-throw-into-the-loop invariant).
- **Every map handed to a callback** (tx-listener handlers, trigger handlers, flow step-fns, async channel envelopes) — fully namespaced. The reason: a single Datalog query should be able to join function specs to the data those functions operate on. `:tx-data` carries no information about which fn owns it; `:seon.db/tx-data` does.
- **Specificity, not single keywords.** Bare keywords (`:status`, `:ok`, `:tx-data`, `:e`, `:a`, `:v`) are banned in any seon-authored map. If a key feels too generic to namespace, namespace it anyway — that's a signal the schema isn't precise enough yet.

**Keyword namespaces = real code namespaces.** Use `::subject` freely — it correctly expands to `:seon.email.message/subject` when you're in `seon.email.message`. This is the intended pattern: **schemas live in the namespace that owns the data, alongside the fns that process it.** Colocation isn't strict (fns will mix data across namespaces — that's fine), but the schema for a piece of data lives with the namespace whose name it carries. Never invent keyword namespace prefixes that don't correspond to actual code namespaces.

**Concrete types only.** Every persisted field has a specific type (`:string`, `:int`, `:keyword`, `:inst`, etc.).

**Optional = absent.** Use `{:optional true}` for fields that may not be present. If the key is present, it must have a valid value. Never store nil.

**Retraction is explicit.** To clear a field, use `[:db/retract eid :attr]`. Omitting a key from a transact map means "leave unchanged."

### Schema Registration

`schema/register!` is the **single source of truth** for all attribute schemas. Register the type, and the system auto-derives everything needed for database storage. You never write Datahike schema directly.

```clojure
;; Inside src/seon/foo.clj — use :: for namespace-local keywords
(schema/register! ::name :string)
(schema/register! ::id [:string {:seon.db/identity true}])
(schema/register! ::tags [:vector :keyword])
(schema/register! ::parent :seon.db/ref)

(db/transact! :seon [{::id "abc" ::name "hello"}])
```

See `/datahike` skill for bridge details, persistence properties, refs, and banned types.

### Shared schema shapes — register once, reference everywhere

**If the same shape appears in two or more registered schemas, the shape itself must be a registered schema that the others reference.** Inlining the same `[:string {:min 14 :max 14}]` (or any constraint) across multiple `register!` calls is a code smell — change the shape and you have to chase every copy. This is the same "don't be a dumbass" rule applied to data shapes.

Pattern (canonical example, lives in `seon.db`):

```clojure
;; ONE canonical shape
(schema/register! :seon.db/ref ...)

;; EVERY ref attr references it — no inline shape, no duplication
(schema/register! :seon.session/turns [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.turn/messages [:vector {:seon.db/component true} :seon.db/ref])
```

The same rule applies to id shapes, length constraints, enum values, and any other property cluster you'd otherwise repeat. If a shape would be repeated, register it under a `:seon.<domain>/<name>` keyword first, then reference it. If the Malli bridge or our `seon.db/malli->datahike-schema` doesn't yet handle the reference shape you need (e.g. adding a property to a referenced schema), **fix the bridge** — do NOT duct-tape by inlining the shape at each site. Duplicated definitions guarantee drift; bridge fixes are one-time.

### Configuration resolves into the DB

The manifest (`config/system.edn`, `SEON_CONFIG` picks the file) resolves **ONCE at boot** into the `:seon.config` DB **singleton** — `:seon.config/system-text` and every dial stored as datoms. **Runtime reads the DB**, never the env or the file: the accessors keep their names/arities but read `config/config-view`. So a dial is replay-visible (`as-of` a past `t` sees the old value) and live-tunable (a `db/transact!` reaches every accessor, no restart). See the `src/seon/CLAUDE.md` Config row for the owner detail.

---

## Skills (IMPORTANT)

**ALWAYS invoke the relevant skill FIRST** before searching, grepping, or trial-and-error. Skills encode project-specific knowledge that saves significant time.

| Skill | Invoke When |
|-------|-------------|
| `/data-oriented-clojure` | BEFORE writing/reviewing ANY seon `.clj`/`.cljs` — the data-oriented, errors-as-values, derive-don't-store mindset; catches imperative/OO reflexes |
| `/data-modeling` | Designing a data model — what shape to `register!` and why (identity vs ref vs component, optional vs required, no `:kind`) |
| `/datahike` | Datalog queries, transacting, debugging empty results, `seon.db`/`seon.schema`; tx-meta provenance, as-of/history |
| `/clojurescript` | Pod CLJS semantics: `^:async`/`await`, self-host eval (agent forms compile via `cljs.js`, NOT the JVM), Promise auto-await, async instrumentation wedge |
| `/repl` | How the REPL reads/repairs/evals the forms you write; parse errors, unbalanced forms |
| `/seon-context-config` | `config/system.edn`/`acme.edn`, manifest sections, which skills/blocks/nses an agent sees, render caps |
| `/ui-live-tiles` | Show your human a live VIEW not prose — `:seon.render.live-tile/content`, `my.ui`/`my.tile`/`my.data`, the canvas |
| `/datastar-web-ui` | SSE handlers, `data-*` attributes, the gzip-morph channel, the `seon.render/block` + slot renderer |
| `/browser-automation` | Verifying the pod's OWN web UI in a browser (note: browser 503s long-lived SSE — verify feeds server-side) |
| `/clojure-testing` | Pod-first `.cljs` test patterns: fixtures, `cljs.test/async`, hermetic in-memory conns |

---

## Editing Tools

**Prefer `clojure_replace` for Clojure** — whitespace-insensitive, structural matching, full lint before write. Use `Edit` for small exact replacements, `Write` for new files. The dev hook validates all edits automatically. Errors include "Did you mean?" suggestions — read them.

If you repeatedly fail to edit a function, **the function is too complex**. Refactor it.

---

## Dev Hook

After every Edit/Write, the hook automatically reloads code, runs affected tests, validates schemas, and provides Gemini AI review. Config in `.claude/seon-hook.edn`. Hook blocks if tests fail. **Read hook feedback** — it catches real problems. Fix warnings before moving on.

---

## Code Reloading

**CLJS pod (active):** `cljs-watch` recompiles `.cljs` on every save; the
running pod picks up the new build. If the pod gets into a bad state,
`bin/seon restart pod` (wait for `agent roster` in `logs/pod.log`). A
fresh world is `bin/seon cluster reset default`.

**`[JVM track — paused]`** uses the dev hook + REPL verbs (you rarely reload manually):

```clojure
(user/reload)  ; Fast reload via clj-reload
(user/reset)   ; Full Integrant restart — use when changing config/components
(user/status)  ; Check system health
```

**If the JVM track breaks:**
1. **Observe first.** `(user/status)`, check logs, query the REPL. Understand what's broken and WHY.
2. **Diagnose the root cause.** Fix the disease, not the symptoms.
3. Try `(user/reload)` — often fixes code-level issues.
4. Try `(user/reset)` — clean Integrant restart. Note: `resume-key` may preserve old state.
5. **Last resort only:** `(user/restart-db!)` for the database, `bin/seon restart jvm` for the JVM. Document WHY.

---

## Testing

**DO NOT INVENT NEW WAYS OF TESTING (owner directive, 2026-07-10).** There
are exactly THREE testing surfaces; every test you write or run belongs to
one of them, and creating a fourth is a violation:

1. **Code correctness** → `bin/test-cljs` (cljs.test). Unit/integration
   tests of our Clojure.
2. **Model/agent evaluation** → `src-inspect-ai/` (the Inspect AI bench;
   ledger `evals/scorecard.jsonl`, `pass^k` alarm, dated evidence under
   `evals/runs/<date>/`). A new eval = a new TASK/scorer INSIDE this bench,
   never a new drive script or bespoke harness. (The historical
   `tmp/*-drive.sh` lineage is being retired into it — do not extend it.)
3. **Free smoke battery** → `bin/gym-scorecard` (no LLM spend, inner loop).

If a measurement seems to need a mechanism none of these provide, STOP and
report — extend the bench, don't mint a harness.

**CLJS pod (active):** the full `.cljs` suite runs via `bin/test-cljs` — a
fresh `:node-test` JVM (no live-pod contention), ~160s. Use it as the
batch checkpoint. To verify a single behavior fast, eval the fn directly
against the live pod rather than running a whole test ns. **Never fire
overlapping `cljs.test/run-tests` in the live pod** — it wedges the shared
async continuation; restart the pod (`bin/seon restart pod`) for a pristine
run.

**Third-party harness (Acme):** to reproduce/fix downstream-consumer bugs
against a real third-party shape WITHOUT touching the live deployment, use
`bin/acme` — a fully isolated second cluster (pod on 7980, wire-server REPL
7981, store `data/clusters/acme`, its own bundle `out-acme/`, logs
`logs/acme/`). The consumer's own code lives in `acme/` (compiled in via
`SEON_EXTRA_SRC`). Boot: `bin/acme build && bin/acme start wire-server &&
bin/acme start pod`. To verify a seon fix in acme, `bin/acme build` then
`bin/acme restart pod` (the acme bundle is not watched). NEVER `bin/seon
start/stop/restart` the live default cluster. Full guide — boot, isolation
table, what it exercises, the fix→verify loop, inspection (HTTP 7980 / wire
REPL 7981, not MCP), and warts: `docs/seon/components/acme-harness.md`.

**`[JVM track — paused]`** tests run inside the running JVM via the REPL —
never by spawning a separate process:

```clojure
(user/run-tests 'seon.foo-test)                    ;; Single namespace
(user/run-tests ['seon.foo-test 'seon.bar-test])   ;; Multiple namespaces
(user/run-tests)                                    ;; All unit tests
(user/test-affected 'seon.foo)                      ;; Namespace + its dependents
(user/test-gen 'seon.foo)                           ;; Generative tests (Malli schemas)
```

Results are **auto-saved** to `@user/repl-<session>`. Dig into stored keys instead of re-running. If the REPL is down, use `bin/test` as a fallback (~30s startup). See `/clojure-testing` skill for fixtures, generators, and debugging patterns.

### Test cadence = token economy (user directive, 2026-06-10)

**Run the full suite ONCE, after a unit of work completes — never after
each sub-step of a refactor.** Targeted single-ns runs are for active
debugging only. Yes, this means some breakage surfaces later than it
could — that's the accepted trade: everything is in git and reverts are
cheap, while per-step suite runs burn minutes and tokens on confirmation
rather than information. The same economy applies to any expensive
oracle (paid LLM runs, live-agent drives): once per unit at the natural
checkpoint, not per edit.

---

## UI Development

Seon uses a **Phosphor Terminal** theme — warm blacks, cream text, amber accents. Read `docs/prds/namespace-ui/design-system.md`. The pod's UI is `src/seon/web/inspector.cljs` + `serve.cljs` (hiccup); the JVM track uses `src/seon/web/components.clj`. Invoke `/datastar-web-ui` for SSE patterns.

Key rules: density over whitespace (`p-3` not `p-6`), small text (`text-xs` primary), warm colors (`bg-base-*`, never `bg-white`), dot+text status (`● running`), monospace everywhere.

---

## Domain Guidelines

1. **One file per namespace** - Don't split prematurely
2. **DB parameter** - Functions receive `db` as first parameter
3. **Schema-first** - Define Malli schemas before implementation
4. **Namespaced IDs** - `:my.plan/id`, `:my.kb.source/rating`

See `docs/conventions.md` for full patterns.

---

## Function Instrumentation (IMPORTANT)

**Give every public fn you write or modify a correct `:malli/schema` — it WILL be enforced at runtime.** On the pod, instrumentation rides the **program graph**: `instrument-from-db!` wraps every specced `:seon.fn` row at boot / `start-agent!` and **re-asserts after every hot reload** (`seon.client/after-reload`); the eval-tee wraps agent-defined fns inline. Every call through a wrapper validates inputs, outputs, and arity. Wrong schemas are bugs — when you see an instrumentation error, **read it and fix the root cause**: either you called the function wrong, or the schema doesn't match reality.

The precise coverage contract (don't overclaim it):

- **Structural async opt-out** (`seon.instrument/async-unwrappable?`, computed — never a name list): a `^:async` fn with a non-simple shape (`:function` / multi-arity / variadic) registers NO wrapper — today `seon.db/transact!`, `seon.eval/eval`, `seon.client/mem-db`. Their `:malli/schema` stays the discoverable contract; **their own body is the validation boundary** and they return error ENVELOPES, never throw (the C40 net: an observe-only Promise-aware wrapper would collapse the rule — deferred, owner-gated).
- **`*.internal` fns are deliberately unspecced** — they are private machinery, outside the contract surface.
- **Coverage is a derived invariant, not a snapshot**: the root world's `:instrumentation-gaps` section (`seon.instrument/coverage-gaps`) recomputes "specced fn with a live var but no wrapper" at every render and surfaces any gap; empty in a healthy runtime.
- **`SEON_INSTRUMENT=0/false/off/no` is a kill-switch** (boot + tee). It exists ONLY to bail out if a wrapper ever destabilizes the pod — never set it to silence a validation error; fix the schema or the call.

Public functions fully spec and validate every argument and the return. Two shapes are allowed: **map-in / map-out** (one namespaced-keyword map in, one out — preferred for API-like surfaces) OR **named positional** (each slot specced via Malli `:catn` inside a `:=>`/`:function` schema — fine for ordinary data-processing fns and for mimicking a well-known API). Multi-arity is allowed when every arity is fully specced (use a `:function` schema). The invariant is completeness of specs, not map-wrapping; an unspecced or bare-keyword argument is the violation, not a positional one.

```clojure
(schema/register! ::do-thing-request
                  [:map [::id ::id] [::option {:optional true} ::option]])
(schema/register! ::do-thing-response
                  [:map [::result ::result]])

(defn do-thing
  "Does the thing."
  {:malli/schema [:=> [:cat ::do-thing-request] ::do-thing-response]}
  [{::keys [id option]}]
  ...)
```

`[JVM track — paused]` instrumentation there is separate machinery: managed by Integrant (`:seon.dev/instrumentation`), survives `(user/reset)`, picks up schema changes on reload.

---

## Errors are data — the fault workflow (IMPORTANT)

**Nothing is caught without becoming data.** Every caught error becomes a
datom via `seon.error/record!`: `:seon.error/fault` (`:agent` | `:core`),
`:seon.error/at` (the basis-t the failing code SAW), EDN stack frames,
bounded full args. Two populations, one shape:

- **`:agent` = caller mistake** (an agent's typo'd verb, a dev-REPL probe
  with bad args). Recorded, surfaced to the caller as its learning signal,
  **never escalates in any mode** — an agent (or your REPL typo) cannot
  take the pod down.
- **`:core` = our bug.** What happens next is the
  `:seon.config/on-core-error` dial — the ONE knob, set per config file:

| Surface | Config | Dial | Consequence of a `:core` fault |
|---------|--------|------|-------------------------------|
| Dev pod (7890) | `config/system.edn` | **`:crash`** | persist the datom, print `SEON-CORE-FAULT <cause> @t=<basis-t>`, EXIT loudly |
| Suite / CI | `config/test.edn` (via `bin/test-cljs`) | **`:gate`** | run FAILS on any un-expected marker, even with green assertions |
| Prod / demo | downstream config | **`:log`** | datom + derived warnings surface only; never-crash intact |

**Writing code (the loop you live in):** the dev hook reloads + tests every
edit AND blocks if your change produced a NEW `:core` fault on the live pod.
Error-path tests that DELIBERATELY provoke core faults wrap the provocation
in `seon.error/expecting-core-fault!` (prints the `-EXPECTED-` marker; the
gates ignore it; the datom still writes). A fault-provoking test WITHOUT the
bracket reds the gate — that's the forcing function, never blanket-suppress.

**Testing:** targeted runs while iterating; ONE full `bin/test-cljs` per
unit. Green now means two things: assertions pass AND zero un-expected core
faults accumulated during the run.

**When a core fault fires (the triage chain — use it, don't log-dig):**
`bin/seon watch-faults` (the orchestrator's standing background task) hands
you the marker → `(seon.agent.inspect/errors)` → `(… /error {::eid N})`
(envelope + frames + the turn join; `inspect/turn` replays the byte-exact
prompt) → `(… /repro {::eid N})` (the frozen as-of db + a ready-to-eval
repro expression + a `::fork-hint`) → run the hint verbatim:
`bin/seon cluster fork default <t>` boots a LIVE, WRITABLE copy of the
world at the failure moment (own pod/store; the error datom is absent
inside its own fork — `at` precedes the recording tx). Fix there, verify,
`bin/seon cluster destroy <fork>` — the source store is untouched by
construction. This whole loop is acceptance-drill-proven end to end
(`docs/prds/agent-ctx/research/error-workflow-drill-2026-07-05.md`).

**Forking a cluster (supervisor-only):** use this for a counterfactual, never
for a normal fresh sample. First obtain the source basis-t — preferably the
`::fork-hint` from `inspect/repro`, whose `:seon.error/at` is the exact db
value the failure saw — then run:

```bash
bin/seon cluster fork default <basis-t> fork-default-<basis-t>
# patch source/config as needed; drive the same stimulus against the fork pod
bin/seon cluster destroy fork-default-<basis-t>
```

`cluster fork` copies the source store and turn-capture blobs, preserves eids
and tx ids at the requested basis, starts a separate writable pod on a fresh
port, and marks the fork disposable. It restores **durable state only**: pin
the bundle, manifest, model configuration, and replay stimulus in the
experiment record. Never modify the source store; always destroy the named
fork in a `finally`/context-manager cleanup. Inspect callers use
`seon_inspect.cluster.ephemeral_fork` for exactly that lifecycle.

**Production:** same recording, same datoms, same triage chain — the dial
just says `:log`. A prod fault is a fork-and-reproduce away from a fix; the
DB's history IS the bug report.

---

## Docstrings (they render into agent context)

A public fn's docstring **first line is a complete, standalone sentence, ≤72 chars (78 hard cap), ending in terminal punctuation** (`.`/`?`/`!`) — it is the summary shown wherever the fn renders compactly (the compact namespace card shows ONLY line 1). State the **action + data effect, not the mechanism** (mechanism → the body, after a blank line, which renders in the full view). Imperative for side-effecting verbs, noun-phrase for pure queries; backtick-quote identifiers. Enforced by `seon.dev.docstring` (warn-only, dev hook). Full rule + examples: `docs/conventions.md` "Function Docstrings".

---

## File Locations

**Never use `/tmp` or system temp directories.** Use project-local directories:

| Directory | Purpose | Git Status |
|-----------|---------|------------|
| `logs/` | Debug logs, hook logs, agent activity | Ignored |
| `tmp/` | Temporary test files, scratch data | Ignored |
| `data/` | Datahike database files (LMDB) | Ignored |

---

## Process Architecture (IMPORTANT)

The active runtime is the **CLJS pod**, backed by the **wire-server**
datahike writer. The JVM main app is the paused track.

| Process | Role | Notes |
|---------|------|-------|
| **pod** | CLJS runtime (ACTIVE) — agent loop, web UI | Node `out/client/main.js`; HTTP on 7890 |
| **cljs-watch** | recompiles `.cljs` on change | feeds the pod's build (`logs/cljs-watch.log`) |
| **wire-server** | central datahike writer — the durable cluster store | UDS + socket REPL on 7891 (`nc` only); store at `$SEON_CLUSTER_DIR/store` (default `data/clusters/default/store`) |
| **jvm** `[JVM track — paused]` | embedded-datahike Integrant app | `./bin/run`; nREPL 7888, HTTP 8080 |
| **Caddy** | HTTPS reverse proxy (optional) | 3030 |

### Datahike: pod vs JVM

- **Pod (active):** does NOT embed datahike. It forwards every write over a
  Unix socket to `wire-server` (the sole writer); reads are local lazy db
  values. The durable store is `data/clusters/default/store`.
- **JVM track (paused):** runs its OWN embedded in-process datahike (LMDB),
  separate from the cluster store. The "embedded, no separate service"
  model applies to the JVM track ONLY — not the pod.

### Surgical Process Management — `bin/seon` (both tracks)

**Use `bin/seon` as the supervisor.** It's idempotent, multi-agent-safe (mkdir-mutex per process), and replaces ad-hoc `pkill` + `nohup` patterns. Any number of agents can call `start`/`stop`/`restart` simultaneously — the supervisor arbitrates. Logs go to `logs/<process>.log` (consistent path; any agent can `bin/seon tail <process>` from anywhere). See [[docs/seon/process-management]] for the full protocol.

```bash
bin/seon start pod         # idempotent — no-op if already running
bin/seon status            # which processes are alive, PIDs, pod port
bin/seon tail pod          # tail -f logs/pod.log
bin/seon restart cljs-watch
bin/seon stop pod
```

Registered processes: `pod` (CLJS pod via Node), `cljs-watch` (CLJS rebuild watcher), `wire-server` (central datahike writer), `jvm` (`./bin/run` — paused track). Add new ones by editing the `process_command` case statement at the top of the script.

### Cluster reset (active track)

`bin/seon cluster reset [name]` (default `default`) — stops pod + wire-server,
**wipes the store**, restarts both; the pod re-seeds the core from the indexed
codebase on boot. Use for a fresh world. Wipes agent-authored work in that
store (agent fns, soul edits, chat) — the core seed regenerates, that does not.

### Core-fault watch (active track)

The dev pod runs `:seon.config/on-core-error :crash` — a `:core` fault
persists its datom, prints `SEON-CORE-FAULT <deepest cause> @t=<basis-t>`,
and EXITS the pod. **At session start the orchestrator runs
`bin/seon watch-faults` as a background task**: it blocks until the first
NEW un-expected marker (starts at end-of-file; `SEON-EXPECTED-CORE-FAULT`
fixture prints are NOT alarms), prints it + the last ~20 log lines, and
exits 0 — so the harness re-invokes you when the pod dies on our own bug.
Triage via `seon.agent.inspect`: `(errors)` (compact recent list) →
`(error {:seon.agent.inspect/eid N})` (full envelope + turn/agent joins) →
`(repro {:seon.agent.inspect/eid N})` (the as-of db frozen at the failure +
a ready-to-eval repro expression). Then `bin/seon restart pod`.
`--cluster <name>` watches another cluster's pod log.

### Log Files for Debugging

```bash
bin/seon tail pod                                # pod boot + agent activity
tail -f logs/cljs-watch.log                      # CLJS rebuild status
tail -f logs/wire-server.log                     # datahike writer
```

### `[JVM track — paused]` REPL verbs + recovery

These apply to the embedded-datahike JVM app (`./bin/run`), NOT the pod:

| Want to... | Do this |
|-----------|---------|
| Reload code | `(user/reload)` |
| Restart the whole system (incl. DB connection) | `(user/reset)` |
| Restart just the DB component | `(user/restart-db!)` |
| Full data wipe | `(user/db-reset!)` |
| Restart the JVM from scratch | `bin/seon restart jvm` |
| Check Integrant components | `(user/status)` |

Recovery (JVM track): datahike connection errors after reload → `(user/restart-db!)` or `(user/reset)`; LMDB lock errors on start → usually self-heals, else `(user/restart-db!)`; data corrupted (rare, from `kill -9` mid-write) → `(user/db-reset!)`. JVM logs: `logs/app.log`, `logs/error.log`, `logs/startup.log`.

---

## Logging

Application: `logs/app.log` (Timbre). Errors: `logs/error.log` (logback). Boot: `logs/startup.log`. Libraries: `logs/lib.log` (SLF4J).

---

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/seon/architecture/architecture.md` | **The idealized system — read FIRST** (map + vocabulary + principles) |
| `docs/prds/<chunk>/roadmap.md` | The we-are-here for the active branch's chunk |
| `docs/conventions.md` | Malli schemas, API design patterns |
| `docs/seon/vision/index.md` | Project thesis and aspirational capabilities |
| `ORCHESTRATOR.md` | Orchestrator-specific instructions (launching agents, system management) |
| `AGENT.md` | Subagent-specific instructions (investigation workflow, reporting) |
| `docs/prds/namespace-ui/design-system.md` | UI colors, typography, spacing |
