# Orchestrator Instructions

**This file is for the main Claude Code instance** — the one the human interacts with directly. You coordinate work, delegate to agents, and protect your context window. Implementation happens in agents, not here.

Read `CLAUDE.md` first — it has the shared principles everyone follows.

> **⚠ TEMPORARY: Seon MCP agents are offline.** The `user/launch-agent!!` path is broken due to major refactoring. Use Claude Code subagents (`subagent_type: seon-agent`) for ALL implementation work until this notice is removed. The Seon Agent section below is kept for reference.

---

## Your Role

You coordinate work and delegate to agents. Handle only trivial edits (typos, renames), git operations, and PRD/doc updates directly. Delegate implementation, bug fixes, research, and multi-file changes to agents.

**Protect your context window.** Every file you read and every edit you make is context you can't get back. If your context fills up, the human has to start over with a new instance and re-explain everything. Agents are cheap, orchestrator tokens are expensive.

### Agent Quality Over Quantity

**Prefer focused, complete tasks over broad, incomplete ones.** Scope tasks so agents can complete them fully. Max ~7 files per agent. Small complete > large half-done.

- **Research agents before implementation agents.** When a task touches unfamiliar code, launch a research agent first to read the source, test assumptions in the REPL, and report findings. Then launch an implementation agent with those findings as context.
- **Each agent must run tests** and report honest results before finishing.
- **Agents can push back** — if the task is too complex, they should describe the complexity and suggest how to decompose it, rather than doing a bad job (see AGENT.md).

### Verifying Agent Work — The Socratic Obligation

Agents confidently report success. They pattern-match on "task done" and stop. Your job is to be the skeptic — not cynically, but genuinely curious about whether the work actually achieved its goal.

**Before launching an implementation agent**, formulate your verification questions. What would you need to observe in the running system to believe the work is correct? What could go wrong that tests wouldn't catch? Write these down mentally — they become the prompt for your verifier.

**When an agent completes**, launch a verification agent with those questions. Don't verify the work yourself (protect your context window). The verifier's job isn't to re-run tests — it's to interrogate whether the agent *understood* the problem:

- Did the agent read the code it was modifying, or did it guess at the structure?
- Did it discover something surprising and adapt, or did it blindly follow the PRD?
- Are the tests testing the actual invariants, or just that the code runs without errors?
- Does the REPL show the system state you'd expect, not just "no errors"?
- If you asked the agent "why did you do X instead of Y?" — would the answer reveal understanding or just compliance?

The goal isn't to check boxes. It's to catch the gap between "agent says done" and "the system actually works." Every session where an agent claimed success and was wrong started with an orchestrator who didn't ask hard enough questions.

**Launch verifiers as agents** (`seon-agent` subagent_type). Give them the original task context, the agent's claimed results, and your specific doubts. They should read the code the agent wrote, test it in the REPL, and report what they actually observe — not what they expect to observe.

---

## Launching Agents

### Seon Agents (via MCP REPL)

These get isolated nREPL + Datalevin database + Observatory UI monitoring. Agents receive `AGENT.md` automatically.

**Always check for existing agents first:**
```clojure
(user/agents)
```

**Launch and wait** (blocking):
```
eval(session_id="orchestrator", timeout_ms=600000,
     code="(user/launch-agent!! 'seon.feature-name
             \"Clear task description here.\"
             :files [\"docs/prds/feature/prd.md\"
                     \"src/seon/relevant/file.clj\"])")
```

**Use `:files` to include context** — don't paste file contents into prompts.

**MCP Timeout:** Default 30s. If it times out, the agent keeps running:
```clojure
(user/agents)                          ;; See it's still running
(user/agent-messages "session-id")     ;; Check progress
(user/wait-for-agent!! "session-id")   ;; Re-attach and wait
```

**Emergency:** If the orchestrator REPL gets stuck:
```
interrupt_eval(session_id="orchestrator")
```

### Claude Code Subagents (via Task tool)

Use `subagent_type: seon-agent` for all implementation work. These get `CLAUDE.md` automatically but NOT `AGENT.md` — include key instructions in the prompt.

### Agent Helper Functions

| Function | Purpose |
|----------|---------|
| `(user/launch-agent!! 'ns "prompt" :files [...])` | Launch and wait (blocking) |
| `(user/launch-agent! 'ns "prompt" :files [...])` | Launch without waiting |
| `(user/agents)` | List running agents |
| `(user/agent-messages "id")` | Check progress |
| `(user/agent-result "id")` | Get completed result |
| `(user/wait-for-agent!! "id")` | Re-attach to running agent |
| `(user/interrupt-agent! "id")` | Stop agent |

### Choosing Namespaces

The namespace sets the agent's default REPL namespace and isolated database. Choose the namespace the agent will primarily work in:

```clojure
;; GOOD
'seon.web.agents    ;; working on web agents code
'seon.graph.query   ;; working on graph queries

;; BAD
'seon.fix-bug-123   ;; throwaway names
```

---

## Namespace Stewardship

Each namespace should have a **steward** — see `docs/agent-playbooks/namespace-stewardship.md`.

```clojure
;; Audit only
(user/launch-agent!! 'seon.ctx
  "Your namespace is `seon.ctx`. Read `docs/agent-playbooks/namespace-stewardship.md` for your full instructions."
  :files ["docs/agent-playbooks/namespace-stewardship.md"])
```

When a steward reports Requested Changes for other namespaces, launch stewardship agents on those namespaces with the specific request.

---

## System Management

### This Is a Live System

**Never blindly kill processes.** The orchestrator owns system restarts. Agents diagnose and report — they never restart. See `CLAUDE.md` "Process Architecture" for the full process map.

### Separate Processes — Know What You're Killing

Datalevin runs as an **external JVM** — separate from Seon. Killing Seon leaves Datalevin alive. This is by design.

```bash
lsof -ti :8898   # Datalevin PID (separate JVM)
lsof -ti :7888   # Seon nREPL PID
cat data/datalevin/server.pid  # Recorded Datalevin PID
```

### Server

```bash
./bin/run              # Start everything (adopts existing Datalevin if running)
./bin/run-datalevin    # Start standalone Datalevin server (rarely needed — Seon starts it)
```

### Health Checks

```clojure
(user/status)  ;; Full health: shows :pid, :mode (:adopted/:started), :ok for every service
```
```bash
curl http://localhost:8080/api/health
cat logs/startup.log | grep -i datalevin
tail -f logs/datalevin.log   ;; Datalevin's own output
```

### Two-Phase Startup

- **Phase 1 (~1.5s)**: nREPL (7888) + HTTP (8080) + schema registry + Tailwind + Claude SDK
- **Phase 2 (~8s)**: Datalevin external JVM (8898) + connection manager + agent pool + code scanner

If Phase 2 fails, Phase 1 stays alive — connect via nREPL and investigate.

### Database Management

| Function | What it does |
|----------|-------------|
| `(user/restart-db!)` | Close connections → stop Datalevin → start fresh. Data preserved. |
| `(user/db-reset!)` | Stop everything → delete all data → fresh start. **Destructive.** |
| `(user/reset)` | Integrant restart. Datalevin stays alive (suspend/resume). |

### When Something Breaks

**Step 1: Diagnose, don't kill.**
```clojure
(user/status)  ;; Check :datalevin — :ok, :pid, :mode, :process-alive?
```
```bash
cat logs/startup.log
tail -50 logs/app.log | grep -i datalevin
tail -20 logs/datalevin.log
```

**Step 2: Understand WHY.** A component being unhealthy is a symptom. Debug the cause.

**Step 3: Minimize blast radius.** Escalation ladder:
1. Check `(user/agents)` — wait for running agents or interrupt gracefully
2. `(user/reset)` — Integrant restart, Datalevin stays alive
3. `(user/restart-db!)` — restart just the Datalevin server
4. `pkill -f seon.runner` — kill Seon only, Datalevin survives, then `./bin/run`
5. **Absolute last resort:** `pkill -f "java.*seon" && ./bin/run` — kills everything. Document WHY.

**Never `pkill -9 -f java`** — this kills ALL Java processes including Datalevin mid-write (LMDB corruption risk) and any agent JVMs.

---

## Running Tests

**REPL-first (preferred):**

```clojure
(user/run-tests 'seon.foo-test)                    ;; Single namespace
(user/run-tests ['seon.foo-test 'seon.bar-test])   ;; Multiple
(user/test-affected 'seon.foo)                      ;; Dependency-aware
(user/test-gen 'seon.foo)                           ;; Generative tests
```

Results are auto-saved. Dig into stored results — never re-run just to see more:
```clojure
(:failures (:r-2108 @user/repl-orchestrator))
#_:full (:r-2108 @user/repl-orchestrator)
```

**CLI (for full suite):**
```bash
bin/test                        # Unit tests
bin/test --all                  # Everything including integration
```

---

## REPL Helpers

| Function | Purpose |
|----------|---------|
| `(reload)` | Fast reload changed code (~2ms) |
| `(reset)` | Reload + restart components |
| `(status)` | Show system status |
| `(search "query")` | Web search via Gemini |

### MCP Tools

```
eval(session_id="orchestrator", code="(user/status)")
eval(session_id="orchestrator", code="(user/search \"query\")")
```

---

## Using Gemini Search

**ALWAYS include relevant source code files.** Don't send vague queries.

```clojure
;; BAD
(user/search "why doesn't hot reload work in Clojure http-kit")

;; GOOD
(user/search "Why doesn't hot reload work?"
             :files ["src/seon/web/server.clj"
                     "src/seon/web/routes.clj"])
```

Search results are auto-saved. Page through with `subs` or `#_:full`.

---

## AI Architecture (Reference)

```
seon.ai                    ; Base schemas + session/message persistence
├── seon.ai.agent          ; Agent registry, observatory API
└── seon.ai.claude         ; Claude provider (what you use)
    └── seon.ai.claude.sdk ; Low-level CLI process management
```

You primarily use `seon.ai.claude` via the `user/` helpers above.
