# Seon — Shared Instructions

**Every Claude instance reads this file** — orchestrator, seon agents, and Claude Code subagents. Keep it universal. Role-specific instructions live in `ORCHESTRATOR.md` and `AGENT.md`.

## Current focus (2026-06-10) — ONE unified system

Active branch: **`feature/agent-runtime`**. **THE source of truth for goals,
status, and the work queue is the PRD:**
`docs/prds/agent-runtime/cljs-finish-clj-pivot-plan-2026-06-09.md` (rewritten
2026-06-10 as a fresh-read spec — read it before anything else).

The substrate is the **CLJS pod** (`src/seon/*.cljs`, long-running Node:
agent loop, bootstrap CLJS compiler, loopback HTTP+SSE inspector UI) backed by
the **central JVM datahike store** (the `wire-server` process; file-backed
datahike on `data/clusters/default/store`). The 2026-06-10 LANE-MERGE (unit
2.2e) moves the pod onto that store via DIS (Distributed Index Space): the JVM
is the sole writer (pod writes forward over a Unix socket), reads are local
lazy db values (memory ∝ working set). A **cluster** = one DB + an
orchestrator agent + N task agents; all coordination flows through the DB;
target is one Node process per agent.

Settled (no longer open choices — do not re-litigate): NO WASM; DIS replica
(proven by two committed regression harnesses); per-CLUSTER DBs; messaging =
from/to refs + hop-cap; the CLJS sandbox is NOT a security boundary (it
catches LLM hallucinations; isolation comes from process boundaries + the
wire capability surface).

**Hard rule:** seon is the substrate. Consumer-product code (specific UI,
vendor integrations, custom domain models) lives in downstream repos. No
consumer-specific references in `src/`, `docs/`, or `pod-host/`.

---

## Agent Model Policy

**Never use haiku for coding tasks.** Only use haiku for quick file reads or context gathering. All implementation, bug fixes, and verification that involves writing code must use opus 4.6 (default model).

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

All documentation lives in `docs/` — markdown files under version control. Use Read, Glob, and Grep to navigate.

- **Start here:** `docs/seon/_dashboard.md` — system map, milestones, protocols
- **What exists:** `docs/seon/components/` — one note per component (always current)
- **Patterns:** `docs/seon/concepts/` — patterns spanning components
- **What we're building:** `docs/seon/vision/` — thesis and desired capabilities
- **Active work:** `docs/seon/orchestrator/active.md` — pipeline and recovery
- **Issues:** `docs/seon/orchestrator/issues/` — one note per problem
- **PRD index:** `docs/seon/orchestrator/prds.md` — all PRDs with status
- **How it works:** `docs/seon/architecture/overview.md` — narrative guide
- **Decisions:** `docs/seon/architecture/decisions/` — settled ADRs
- **All PRDs:** `docs/prds/` — feature specifications
- **Conventions:** `docs/conventions.md` — API design patterns
- **Vision:** `docs/seon/vision/index.md` — project thesis

**After making code changes**, update the relevant component note to reflect new reality. See `docs/seon/_dashboard.md` for the full protocol.

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

The personal domains (trading, health, finance) are test cases, not the point. The real product is a codebase architecture where AI agents can own and evolve code responsibly - with contracts they can discover, history they can learn from, and isolation that prevents conflicts.

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

## Slow Is Fast

**Your default training rewards task completion. Override that instinct.** Charging forward and declaring victory is worse than pausing to verify. Three agents "fixing" the same bug is more expensive than one agent understanding the problem first.

### Don't be a dumbass

**Whenever you feel like you should re-create a second version of something when we are clearly trying to fix the original — stop and think: am I being a dumbass?**

If a generator, fn, schema, or namespace already exists and you're "fixing" it, the fix lives in the existing one. Creating a parallel `foo-v2`, `foo-new`, or new namespace to "house" the fix is almost always a dumbass move. It leaves two versions in the codebase, doubles the surface for the next bug, and the comments documenting why the duplicate exists will outlive everyone who knew the reason. Examples of this trap:

- "I'll put the new shape in a fresh ns to avoid the require cycle." → wrong; fix the cycle.
- "I'll make a v2 schema and migrate callers later." → wrong; bump the schema in place, fix the callers in the same patch.
- "I'll add `do-thing-new` and deprecate `do-thing`." → wrong; change `do-thing`'s implementation.

The whole repo is on a feature branch. Atomic refactors are the cheap option, not the expensive one.

### Before writing code:

1. **Observe the live system.** Query the REPL. Establish current state with actual data, not assumptions.
2. **Define what failure looks like.** If you can't articulate how you'd know your change is broken, you don't understand the problem well enough to fix it.
3. **Read the source.** Read the existing code you're modifying. Read library source in `reference-code/` (Datahike, Malli, Integrant, core.async — they're all there as git submodules). Agents that guess instead of reading produce confident, wrong answers. **Never unzip deployed packages** to inspect a dep — `reference-code/` has the same source already, checked out and grep-able.
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

Cross-agent coordination falls out: a section function that doesn't filter by `:seon.agent/id` sees the whole substrate. Agent A's failed eval shows up in agent B's render. No subscription, no event bus.

Full principle + design checklist + canonical examples: [[docs/seon/concepts/reactive-context]].

---

## Code as data — the runtime IS the database

**The substrate's source code, the agent's eval log, and the in-memory analyzer state are three views of the same code corpus.** Persisting the agent's defining forms as `:seon.fn` / `:seon.ns` / `:seon.schema` entities lets the substrate seed, detect-and-tee, bulk-load resume, the publish gate, and the disk-write debug mode all read from one place. They look like five separate features; they are one mechanism viewed five ways.

The corollary: don't re-parse source with rewrite-clj when the analyzer already produced the structured data. Don't write a build-time `bootstrap.edn` when the substrate source IS the bootstrap (read at boot via the analyzer). Don't replay-every-eval on resume when bulk-loading reconstituted ns files is what editors already do. One mechanism for "where do program-graph entities come from": always the analyzer plus a source string.

Full principle + the five mechanisms + cross-agent publish gate + recursive-bootstrap use case: [[docs/seon/concepts/code-as-data-runtime]].

---

## Architecture

```
seon/
├── src/seon/
│   ├── core.clj              ; System entry, protocols
│   ├── config.clj            ; Aero config loading
│   ├── system.clj            ; Integrant system map
│   ├── db/                   ; Database layer
│   ├── domains/              ; Domain modules (trading, health, etc.)
│   └── web/                  ; HTTP server, SSE, handlers
├── reference-code/           ; Git submodules of dependency source
│   ├── datahike/             ; Datahike source (read when stuck)
│   ├── malli/                ; Malli source
│   ├── integrant/            ; Integrant source
│   ├── core.async/           ; core.async + flow source
│   └── ...                   ; others (datastar, nippy, sci, etc.)
└── docs/
    ├── prds/                 ; Feature specifications
    └── reference/            ; Technical reference docs
```

### Database Access

`seon.db` is the **sole database API**. Only `src/seon/db/` and `src/seon/db/datahike/` touch `datahike.api` directly. Everything else uses `db/transact!`, `db/query`, `db/pull-by-name`, etc. with db-name keywords (`:seon`, `:seon.runtime`, or namespace keywords). Reader and writer flow processes serialize all access. Tests bind `db/*direct-mode*` to bypass the flow. See `docs/conventions.md` "Database Access" for patterns.

### Flow Topology (routing backbone)

All cross-boundary calls — namespace function calls, database writes, REPL eval — route through `topology/request!` (core.async.flow). One pattern: register promise → inject → step-fn → reply-router → deliver promise. See `docs/prds/unified-flow/design.md`.

---

## Multi-Agent Git Safety (CRITICAL)

**Multiple agents and the orchestrator share the same working tree.** Assume other agents are actively working at all times.

### Safe operations (use freely):

- **Read-only git:** `git diff`, `git status`, `git log`
- **Stage your files:** `git add <specific-files>` (orchestrator commits)
- **Edit files** with Edit/Write/clojure_replace — this is your job

### Everything else: ask the user first

Any git operation that changes branch, discards files, or modifies history affects all agents. Ask the user before running it — they'll coordinate across agents. The cost of asking is near zero; the cost of destroying another agent's work is high.

### Lane discipline: `.clj` (JVM) vs `.cljs` (CLJS pod) siblings

As of 2026-05-16, for spec-01 `seon.*` API surfaces the V0 substrate uses **`.cljs` files alongside the existing `.clj` files**, not `.cljc` (yet). Both compilers cleanly pick their own — CLJS reads `.cljs`, CLJ reads `.clj`, neither sees the other's. Two lanes:

- **JVM seat** (Phase M / R / T datahike-migration work) — owns all existing `.clj` files under `src/seon/`. Don't author `.cljc` for namespaces that have a `.clj` sibling; the `.cljc` migration is a deliberate Stage 2/3 step in the convergence plan.
- **V0 CLJS pod seat** — owns the new `.cljs` siblings (`seon.client.cljs`, the pending `seon.db.cljs`, `seon.trigger.cljs`, etc.) and the genuinely-shared `.cljc` files under `src/seon/agent/`. Doesn't touch the existing `.clj` files.

`seon.schema` is the one exception — promoted to `.cljc` 2026-05-16 because the file was 100% platform-portable. Other promotions wait until both sides converge on the spec §3 map-in/map-out + `*conn*` shape (positional-with-`:catn` is also permitted per the 2026-06-08 rule change).


---

## Data Rules

All data flowing through Seon must be safe at every boundary: Malli validation, core.async channels, Nippy serialization, Datahike transact/pull.

**Maps with namespaced keywords. Every key. No exceptions.** This is the load-bearing rule the rest of the system depends on:

- **Every public function** fully specs and validates ALL its arguments and its return value via `:malli/schema`. Two argument shapes are allowed: (1) **map-in / map-out** — one namespaced-keyword map in, one out, where the request and response are named Malli schemas (`::foo-request`, `::foo-response`) registered via `seon.schema/register!` — **preferred for API-like surfaces** (discoverable, extensible); or (2) **named positional** — each argument is a fully-namespaced-keyword-spec'd slot via Malli `:catn` (named positional) inside a `:=>`/`:function` schema — fine for ordinary data-processing fns and for mimicking a well-known API (e.g. datahike). The invariant: every argument is NAMED, SPECCED, and VALIDATED, whether it sits in a map or a positional slot. The violation is an UNSPECCED or BARE-keyword argument, not a positional one. Every key in any map is fully namespaced (`:seon.runtime/status`, never `:status`).
- **Every datom persisted to the DB** uses a fully-namespaced attribute keyword whose Malli schema is registered. `seon.db/transact!` enforces this at the boundary — unregistered or unspec'd attrs throw before the tx reaches the DB.
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

---

## Skills (IMPORTANT)

**ALWAYS invoke the relevant skill FIRST** before searching, grepping, or trial-and-error. Skills encode project-specific knowledge that saves significant time.

| Skill | Invoke When |
|-------|-------------|
| `/datahike` | Writing Datalog queries, transacting data, debugging empty results, working with `d/q` against the Datahike-backed `seon.db` |
| `/datastar-web-ui` | SSE handlers, `data-*` attributes, streaming responses |
| `/browser-automation` | Testing UI in browser, debugging frontend issues |
| `/clojure-testing` | Test patterns: mocking, generators, fixtures, debugging failures |

---

## Editing Tools

**Prefer `clojure_replace` for Clojure** — whitespace-insensitive, structural matching, full lint before write. Use `Edit` for small exact replacements, `Write` for new files. The dev hook validates all edits automatically. Errors include "Did you mean?" suggestions — read them.

If you repeatedly fail to edit a function, **the function is too complex**. Refactor it.

---

## Dev Hook

After every Edit/Write, the hook automatically reloads code, runs affected tests, validates schemas, and provides Gemini AI review. Config in `.claude/seon-hook.edn`. Hook blocks if tests fail. **Read hook feedback** — it catches real problems. Fix warnings before moving on.

---

## Code Reloading

The dev hook handles reloading automatically. You rarely need to reload manually.

```clojure
(user/reload)  ; Fast reload via clj-reload
(user/reset)   ; Full Integrant restart — use when changing config/components
(user/status)  ; Check system health
```

Use `(user/reload)` or `(user/reset)` for reloading — they handle cleanup properly.

**If something breaks:**
1. **Observe first.** `(user/status)`, check logs, query the REPL. Understand what's broken and WHY.
2. **Diagnose the root cause.** Fix the disease, not the symptoms.
3. Try `(user/reload)` — often fixes code-level issues.
4. Try `(user/reset)` — clean Integrant restart. Note: `resume-key` may preserve old state.
5. **Last resort only:** `(user/restart-db!)` for the database, `pkill -f seon.runner` for the Seon JVM. Document WHY.

---

## Testing

Tests run inside the running JVM via the REPL — never by spawning a separate process.

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

Seon uses a **Phosphor Terminal** theme — warm blacks, cream text, amber accents. Read `docs/prds/namespace-ui/design-system.md` and use `src/seon/web/components.clj`. Invoke `/datastar-web-ui` for SSE patterns.

Key rules: density over whitespace (`p-3` not `p-6`), small text (`text-xs` primary), warm colors (`bg-base-*`, never `bg-white`), dot+text status (`● running`), monospace everywhere.

---

## Domain Guidelines

1. **One file per namespace** - Don't split prematurely
2. **DB parameter** - Functions receive `db` as first parameter
3. **Schema-first** - Define Malli schemas before implementation
4. **Namespaced IDs** - `:seon.trading/position`, `:seon.health/workout`

See `docs/conventions.md` for full patterns.

---

## Function Instrumentation (IMPORTANT)

All public functions with `:malli/schema` metadata are **instrumented at runtime**. Every call is validated — inputs, outputs, and arity. There is no "off" mode.

**Every public function you write or modify MUST have a correct `:malli/schema`.** Wrong schemas are bugs — instrumentation will throw at runtime. When you see an instrumentation error, **read it and fix the root cause**: either you called the function wrong, or the schema doesn't match reality.

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

Instrumentation is managed by Integrant (`:seon.dev/instrumentation`), survives `(user/reset)`, and picks up schema changes automatically on reload.

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

Datahike is **embedded** in the Seon JVM (in-process LMDB) — there is no separate database service to start, stop, or adopt. The Seon JVM and the database are the same process. Agent JVMs are independent processes that talk to Seon, not to a separate database.

| Process | Port | What It Does |
|---------|------|-------------|
| **Seon JVM** | 7888 (nREPL), 8080 (HTTP) | Main app — orchestrator, web UI, agents, and embedded Datahike (LMDB) |
| **Caddy** | 3030 | HTTPS reverse proxy (optional) |
| **wire-server** | UDS + 7891 (socket REPL, `nc` only) | Central datahike writer — the durable cluster store (`data/clusters/default/store`) |

### Why This Matters

- **The database lives inside Seon.** Killing the Seon JVM stops the database. Restarting Seon starts a fresh Datahike connection against the on-disk LMDB store — data persists across restarts because LMDB is on disk, not because a separate process survives.
- **`(user/reset)` restarts the Datahike connection** via Integrant alongside the rest of the system. No suspend/resume gymnastics needed; the LMDB files on disk are the source of truth.
- **Agent JVMs do not connect to the database directly.** They talk to Seon (over its REPL / message channels); Seon owns the sole connection.

### How to Check What's Running

```clojure
(user/status)  ;; Shows all Integrant components with :mode (:started/:adopted), :ok
```

```bash
lsof -ti :7888   # Seon nREPL PID
lsof -ti :8080   # Seon HTTP PID
ls data/datahike/  # On-disk LMDB store (no PID file — embedded)
```

### Surgical Process Management

**Use `bin/seon` as the supervisor.** It's idempotent, multi-agent-safe (mkdir-mutex per process), and replaces ad-hoc `pkill` + `nohup` patterns. Any number of agents can call `start`/`stop`/`restart` simultaneously — the supervisor arbitrates. Logs go to `logs/<process>.log` (consistent path; any agent can `bin/seon tail <process>` from anywhere). See [[docs/seon/process-management]] for the full protocol.

```bash
bin/seon start pod         # idempotent — no-op if already running
bin/seon status            # which processes are alive, PIDs, pod port
bin/seon tail pod          # tail -f logs/pod.log
bin/seon restart cljs-watch
bin/seon stop pod
```

Registered processes: `pod` (CLJS pod via Node), `cljs-watch` (CLJS rebuild watcher), `jvm` (`./bin/run`), `wire-server` (central datahike writer). Add new ones by editing the `process_command` case statement at the top of the script.

For inside-the-JVM REPL operations, the JVM REPL still has its own verbs:

| Want to... | Do this |
|-----------|---------|
| Reload code | `(user/reload)` |
| Restart the whole system (incl. DB connection) | `(user/reset)` |
| Restart just the DB component | `(user/restart-db!)` |
| Full data wipe | `(user/db-reset!)` |
| Restart Seon JVM from scratch | `bin/seon restart jvm` (was `pkill -f seon.runner` then `./bin/run`) |

### Recovery Procedures

| Symptom | Diagnosis | Fix |
|---------|-----------|-----|
| Datahike connection errors after reload | Stale connection or schema change | `(user/restart-db!)` or `(user/reset)` |
| LMDB lock errors on start | Previous JVM was killed mid-write | Usually self-heals on restart; if not, `(user/restart-db!)` |
| Seon JVM dead | Crashed or killed | `./bin/run` — fresh start against existing LMDB store |
| Data corrupted | Rare — usually from `kill -9` mid-write | `(user/db-reset!)` for clean slate |

### Log Files for Debugging

```bash
tail -f logs/app.log | grep -iE 'datahike|db'  # DB lifecycle inside Seon
cat logs/startup.log | grep -iE 'datahike|db'  # Boot sequence
```

---

## Logging

Application: `logs/app.log` (Timbre). Errors: `logs/error.log` (logback). Boot: `logs/startup.log`. Libraries: `logs/lib.log` (SLF4J).

---

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/seon/vision/index.md` | Project thesis and aspirational capabilities |
| `docs/conventions.md` | Malli schemas, API design patterns |
| `ORCHESTRATOR.md` | Orchestrator-specific instructions (launching agents, system management) |
| `AGENT.md` | Subagent-specific instructions (investigation workflow, reporting) |
| `docs/seon/orchestrator/issues/` | Open problems — one note per issue |
| `docs/seon/reference/datastar-quick-reference.md` | Web UI attributes |
| `docs/prds/namespace-ui/design-system.md` | UI colors, typography, spacing |
