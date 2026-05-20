# Seon — Shared Instructions

**Every Claude instance reads this file** — orchestrator, seon agents, and Claude Code subagents. Keep it universal. Role-specific instructions live in `ORCHESTRATOR.md` and `AGENT.md`.

## Agent Model Policy

**Never use haiku for coding tasks.** Only use haiku for quick file reads or context gathering. All implementation, bug fixes, and verification that involves writing code must use opus 4.6 (default model).

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

- **Datalevin** - Embedded Datalog database on LMDB. EAV datoms, Datomic-compatible queries, ACID transactions.
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

### Before writing code:

1. **Observe the live system.** Query the REPL. Establish current state with actual data, not assumptions.
2. **Define what failure looks like.** If you can't articulate how you'd know your change is broken, you don't understand the problem well enough to fix it.
3. **Read the source.** Read the existing code you're modifying. Read library source in `reference-code/` (Datalevin, Malli, Integrant, core.async — they're all there). Agents that guess instead of reading produce confident, wrong answers.
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
(user/ask "Explain Datalog pull patterns in Datalevin")
```

### After writing code:

6. **Verify in the REPL, not just with tests.** Tests passing is necessary but not sufficient. Query the live system and confirm the actual state matches your intent.
7. **Falsify, don't confirm.** Don't ask "does my change work?" Ask "how would I know if my change is broken?" Then check for that.

**The REPL is the oracle.** Not the code, not the tests, not the docs. The running system tells you the truth. Every claim should be verifiable with a REPL expression.

**Honesty is paramount.** It is far worse to hide remaining work than to report it. Never mark a task as "done" if there are known issues. Report what's actually working, what's broken, and what's left.

### Report Code Smells

As you work, you will encounter inconsistencies, type mismatches, coercions that shouldn't exist, schemas that don't match reality, or patterns that violate our conventions. **Do not silently work around them.**

- **If you fully understand the issue and the fix is within your task scope**, fix it and explain what you found and why you changed it.
- **If you don't fully understand the issue**, or the fix touches code outside your task, **report it clearly** in your response. Include: the file and line, what looks wrong, what you think it should be, and why you're not sure. The orchestrator will launch a focused agent to investigate.
- **Assume every smell is a bug** until proven otherwise. If a schema says `:db.type/string` but callers pass symbols, flag it.
- **Report type mismatches instead of coercing around them.** If data doesn't fit a schema, the schema or the caller is wrong — fix the root cause.

This is how we build a consistent system. Every agent that reports a smell makes the codebase better for the next agent.

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
│   ├── datalevin/            ; Datalevin source (read when stuck)
│   ├── malli/                ; Malli source
│   ├── integrant/            ; Integrant source
│   └── core.async/           ; core.async + flow source
└── docs/
    ├── prds/                 ; Feature specifications
    └── reference/            ; Technical reference docs
```

### Database Access

`seon.db` is the **sole database API**. Only `src/seon/db/` and `src/seon/db/datalevin/` touch `datalevin.core` directly. Everything else uses `db/transact!`, `db/query`, `db/pull-by-name`, etc. with db-name keywords (`:seon`, `:seon.runtime`, or namespace keywords). Reader and writer flow processes serialize all access. Tests bind `db/*direct-mode*` to bypass the flow. See `docs/conventions.md` "Database Access" for patterns.

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

`seon.schema` is the one exception — promoted to `.cljc` 2026-05-16 because the file was 100% platform-portable. Other promotions wait until both sides converge on the spec §3 map-in/map-out + `*conn*` shape.


---

## Data Rules

All data flowing through Seon must be safe at every boundary: Malli validation, core.async channels, Nippy serialization, Datalevin transact/pull.

**Maps with namespaced keywords. Every key. No exceptions.** This is the load-bearing rule the rest of the system depends on:

- **Every public function** takes one map and returns one map. Every key in both maps is fully namespaced (`:seon.runtime/status`, never `:status`). Both the request and response map are themselves named Malli schemas (`::foo-request`, `::foo-response`) registered via `seon.schema/register!`. The `:malli/schema` metadata on the fn points at them.
- **Every datom persisted to the DB** uses a fully-namespaced attribute keyword whose Malli schema is registered. `seon.db/transact!` enforces this at the boundary — unregistered or unspec'd attrs throw before the tx reaches the DB.
- **Every map handed to a callback** (tx-listener handlers, trigger handlers, flow step-fns, async channel envelopes) — fully namespaced. The reason: a single Datalog query should be able to join function specs to the data those functions operate on. `:tx-data` carries no information about which fn owns it; `:seon.db/tx-data` does.
- **Specificity, not single keywords.** Bare keywords (`:status`, `:ok`, `:tx-data`, `:e`, `:a`, `:v`) are banned in any seon-authored map. If a key feels too generic to namespace, namespace it anyway — that's a signal the schema isn't precise enough yet.

**Keyword namespaces = real code namespaces.** Use `::subject` freely — it correctly expands to `:seon.email.message/subject` when you're in `seon.email.message`. This is the intended pattern: **schemas live in the namespace that owns the data, alongside the fns that process it.** Colocation isn't strict (fns will mix data across namespaces — that's fine), but the schema for a piece of data lives with the namespace whose name it carries. Never invent keyword namespace prefixes that don't correspond to actual code namespaces.

**Concrete types only.** Every persisted field has a specific type (`:string`, `:int`, `:keyword`, `:inst`, etc.).

**Optional = absent.** Use `{:optional true}` for fields that may not be present. If the key is present, it must have a valid value. Never store nil.

**Retraction is explicit.** To clear a field, use `[:db/retract eid :attr]`. Omitting a key from a transact map means "leave unchanged."

### Schema Registration

`schema/register!` is the **single source of truth** for all attribute schemas. Register the type, and the system auto-derives everything needed for database storage. You never write Datalevin schema directly.

```clojure
;; Inside src/seon/foo.clj — use :: for namespace-local keywords
(schema/register! ::name :string)
(schema/register! ::id [:string {:seon.db/identity true}])
(schema/register! ::tags [:vector :keyword])
(schema/register! ::parent :seon.db/ref)

(db/transact! :seon [{::id "abc" ::name "hello"}])
```

See `/datalevin` skill for bridge details, persistence properties, refs, and banned types.

---

## Skills (IMPORTANT)

**ALWAYS invoke the relevant skill FIRST** before searching, grepping, or trial-and-error. Skills encode project-specific knowledge that saves significant time.

| Skill | Invoke When |
|-------|-------------|
| `/datalevin` | Writing Datalog queries, transacting data, debugging empty results, working with `d/q` |
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
5. **Last resort only:** `(user/restart-db!)` for Datalevin, `pkill -f seon.runner` for the Seon JVM. Document WHY.

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

Public functions follow **map in, map out**. One map argument, one map return. No multi-arity on public functions.

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
| `data/` | Datalevin database files | Ignored |

---

## Process Architecture (IMPORTANT)

Seon runs as **multiple separate JVM processes**. They are independent — killing one does NOT require killing others.

| Process | Port | PID File | What It Does |
|---------|------|----------|-------------|
| **Datalevin** | 8898 | `data/datalevin/server.pid` | Database server (LMDB). Survives Seon restarts. |
| **Seon** | 7888 (nREPL), 8080 (HTTP) | — | Main app: orchestrator, web UI, agents |
| **Caddy** | 3030 | — | HTTPS reverse proxy (optional) |
| **Agent JVMs** | 7900-7902 | — | Isolated agent nREPL processes |

### Why This Matters

Datalevin runs as an **external JVM process**, not embedded in Seon. This means:
- **Killing Seon does NOT kill Datalevin.** Data is safe.
- **Restarting Seon adopts the existing Datalevin** — no data loss, no LMDB corruption.
- **`(user/reset)` keeps Datalevin alive** via Integrant suspend/resume.
- **Agent JVMs connect to Datalevin over TCP** — they're unaffected by Seon restarts.

### How to Check What's Running

```clojure
(user/status)  ;; Shows all services with :mode (:started/:adopted), :pid, :ok
```

```bash
lsof -ti :8898   # Datalevin PID
lsof -ti :7888   # Seon nREPL PID
cat data/datalevin/server.pid  # Recorded Datalevin PID
```

### Surgical Process Management

Each process is independent — only target the specific one you need to restart.

| Want to... | Do this |
|-----------|---------|
| Restart Seon only | `pkill -f seon.runner` |
| Restart Datalevin | `(user/restart-db!)` |
| Full data wipe | `(user/db-reset!)` |
| Clean restart of everything | `(halt)`, wait 3s, `(go)` |

After killing Seon: just `./bin/run` — it adopts the still-running Datalevin.

### Recovery Procedures

| Symptom | Diagnosis | Fix |
|---------|-----------|-----|
| "Connection refused" on :8898 | Datalevin died | `(user/restart-db!)` or `(user/reset)` (auto-starts new one) |
| Seon JVM died but Datalevin alive | Normal — Datalevin survives | `./bin/run` (adopts existing) |
| LMDB lock errors on start | Stale locks from previous crash | `(user/restart-db!)` — Datalevin manages its own locks |
| Everything dead | Both processes killed | `./bin/run` (starts both fresh) |
| Data corrupted | Rare — only from `kill -9` on Datalevin mid-write | `(user/db-reset!)` for clean slate |

### Log Files for Debugging

```bash
tail -f logs/datalevin.log          # Datalevin process output (starts, stops, client connects)
tail -f logs/app.log | grep -i datalevin  # Seon-side lifecycle (adopt, start, stop, health)
cat logs/startup.log | grep -i datalevin  # Boot sequence
cat data/datalevin/server.pid       # Current Datalevin PID
```

---

## Logging

Application: `logs/app.log` (Timbre). Database: `logs/datalevin.log`. Errors: `logs/error.log` (logback). Boot: `logs/startup.log`. Libraries: `logs/lib.log` (SLF4J).

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
