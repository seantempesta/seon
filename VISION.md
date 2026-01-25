# Seon Vision

## The Thesis

AI agents will write most software. The question isn't *if* but *how well*.

Current approaches are broken: agents bolt onto codebases designed for humans, hallucinate interfaces, have no memory, step on each other, and ship broken code. The result is increasingly fragmented codebases with mounting technical debt.

**Seon is infrastructure for AI agents to write reliable software.**

Not a framework. Not a library. A complete codebase architecture where agents can:
- Discover functions by their contracts (not hallucinate them)
- Learn from history (not repeat mistakes)
- Own code long-term (not just complete tasks)
- Compose safely (not break each other's work)

The personal domains (trading, health, finance) are test cases. The infrastructure is the product.

---

## Why This Might Work

### The Right Language

Clojure isn't a limitation - it's a requirement:

| Property | Why It Matters for Agents |
|----------|---------------------------|
| **Stable APIs** | 10-year-old docs still valid. No API churn to track. |
| **Data-oriented** | Maps in, maps out. No hidden object state. |
| **Homoiconic** | Code is data. Agents can manipulate programs as data structures. |
| **REPL-driven** | Interactive development matches agent workflow: try, see, iterate. |
| **Immutable default** | Outputs depend only on inputs. No spooky action at a distance. |

McCarthy designed Lisp for AI. Maybe the killer app was always agents writing Lisp.

### The Right Database

XTDB provides bitemporal history:
- **Valid time**: When was this fact true in the world?
- **Transaction time**: When did we record this fact?

Agents can query: "What did this function return last week?" "How has this data evolved?" "What changed between working and broken?"

Most databases can't answer these questions. For agents learning from experience, they're essential.

### The Right Contracts

Malli schemas with fully namespaced keys create machine-readable contracts:

```clojure
;; Every function advertises its interface
{:malli/schema [:=> [:cat ::analyze-request] ::analyze-response]}

;; Every key is globally unique and queryable
:seon.trading/position  ; Not just :position
:seon.health/metric     ; No ambiguity
```

"What functions accept `:seon.trading/position`?" becomes a database query, not a hallucination.

---

## The Architecture

### Layer 1: Contracts & Discovery

**What exists now:**
- Malli schema registry with namespaced keys
- Schema introspection (`schemas-in-namespace`, `registered?`)
- Function schemas via `:malli/schema` metadata

**What's next:**
- **Function index** - Query functions by input/output schemas
- **Composition hints** - "These functions chain together"
- **Usage examples** - Auto-generated from test cases

**Success state:** Agent asks "how do I calculate a trading signal?" → system returns relevant functions with signatures, examples, and composition patterns.

### Layer 2: Agent Isolation

**What exists now:**
- Each agent gets isolated nREPL (own port, own REPL state)
- Each agent gets isolated XTDB database
- Each agent gets isolated log files
- Registry tracks running agents
- Health checks detect orphaned resources

**What's next:**
- **Namespace ownership model** - Declare which agent owns which namespace
- **Cross-agent communication** - Via schemas and database, not shared state
- **Conflict detection** - Alert when agents touch the same code

**Success state:** Multiple agents work in parallel on different namespaces without interference. Ownership is explicit and enforced.

### Layer 3: Verification

**What exists now:**
- Dev hooks trigger on every Edit/Write
- Automatic code reload into running system
- Affected namespace tests run automatically
- Generative testing via Malli schemas
- AI review (Gemini) for style/correctness
- Hooks block on test failure

**What's next:**
- **Semantic diff** - Did behavior change, not just syntax?
- **Regression detection** - Compare outputs before/after
- **Review learning** - Track which reviews caught real issues

**Success state:** Agents can move fast because verification is automatic. Bad changes never land.

### Layer 4: Observability

**What exists now:**
- Observatory UI shows running agents
- Agent logs with tool calls, results, errors
- Health endpoint with component status
- SSE-based live updates

**What's next (namespace-ui):**
- **Namespace introspection** - View any namespace's functions, vars, atoms
- **Schema browser** - Navigate all registered schemas with cross-references
- **Data viewer** - Expand/collapse nested structures
- **Live atom updates** - REPL change → browser update in <100ms

**Success state:** You can see the entire system state at a glance. Agents can too.

### Layer 5: Dynamic Context (The Cockpit)

**What exists now:**
- Static context in CLAUDE.md, AGENT.md
- Message history grows until summarized

**What's next:**
- **Live system status** - Health, running agents, recent errors always visible
- **Function typeahead** - As agent types, show matching functions with docs
- **Relevant context injection** - System surfaces what agent needs, not everything
- **Sliding window** - Recent messages + live dashboard, not growing scroll

**Success state:** Agent context is a cockpit with instruments, not a growing scroll of text. Information flows in based on what's relevant now.

### Layer 6: Learning from History

**What exists now:**
- All agent messages persisted to XTDB
- Temporal queries available
- Session metadata (cost, duration, status)

**What's next:**
- **Session replay** - Re-run agent sessions to understand decisions
- **Pattern extraction** - "When agents do X, Y usually follows"
- **Mistake tracking** - "This approach failed 3 times before"
- **Cross-namespace analytics** - "Function X is called by 5 namespaces"

**Success state:** Agents get smarter over time. The system learns which approaches work.

### Layer 7: Long-term Ownership

**What exists now:**
- Agents complete tasks and exit
- No persistent agent identity

**What's next:**
- **Persistent agents** - Agent assigned to `seon.trading.signals` long-term
- **Ownership handoff** - Graceful transfer when agent context expires
- **Evolution tracking** - "This namespace has been modified 47 times by 3 agents"
- **Proactive maintenance** - Agents notice issues and fix them unprompted

**Success state:** Namespaces have stewards. Code evolves based on usage. Agents maintain, not just build.

---

## Progress

### Done ✓

| Component | Description |
|-----------|-------------|
| Agent orchestration | Launch, monitor, interrupt agents via REPL |
| Resource isolation | Isolated nREPL, XTDB, logs per agent |
| Dev hooks | Tests + AI review on every edit |
| Observatory UI | Watch agent progress, view logs |
| Health system | Component checks, orphan cleanup |
| Schema registry | Malli schemas queryable at runtime |
| Message persistence | All messages saved to XTDB |
| SSE infrastructure | Real-time UI updates |

### In Progress

| Component | PRD | Status |
|-----------|-----|--------|
| Namespace UI vision | [`namespace-ui`](docs/prds/namespace-ui/prd.md) | Vision complete |
| Observatory polish | [`observatory-polish`](docs/prds/observatory-polish/prd.md) | Active |
| Agent robustness | [`stability-improvements`](docs/prds/stability-improvements/prd.md) | Done |

### Next Up

| Component | PRD | Purpose |
|-----------|-----|---------|
| Data viewer | [`data-viewer`](docs/prds/data-viewer/prd.md) | Expand/collapse nested data |
| Schema browser | [`schema-viewer`](docs/prds/schema-viewer/prd.md) | Navigate schemas with cross-refs |
| Live updates | [`live-updates`](docs/prds/live-updates/prd.md) | REPL → browser <100ms |
| Dashboard | [`dashboard-polish`](docs/prds/dashboard-polish/prd.md) | Information-dense system view |
| Custom renderers | [`custom-renderers`](docs/prds/custom-renderers/prd.md) | Domain-specific UI |

### Future (No PRD Yet)

| Component | Purpose |
|-----------|---------|
| Function index | Query functions by input/output schema |
| Dynamic cockpit | Live context instead of growing scroll |
| Session replay | Learn from agent history |
| Namespace ownership | Persistent agent assignment |
| Cross-agent coordination | Safe parallel work |

---

## Validation Criteria

How do we know this works?

### Near-term (3 months)
- [ ] Agent completes multi-phase PRD without human intervention
- [ ] Function discovery: agent finds composable functions via schema query
- [ ] Zero resource leaks over 24-hour agent marathon

### Medium-term (6 months)
- [ ] Agent maintains a namespace for 30+ days, evolving based on usage
- [ ] Non-developer gives problem → agents build working solution
- [ ] System suggests improvements based on usage patterns

### Long-term (12 months)
- [ ] Multiple agents collaborate on cross-cutting feature
- [ ] Agent notices regression and fixes it proactively
- [ ] New domain added with minimal human guidance

---

## Why Not Just Use [X]?

### Why not just use Cursor/Copilot/etc?

They bolt onto existing codebases. No contracts, no history, no isolation. They're autocomplete, not ownership.

### Why not Python/TypeScript?

- **Python**: Dynamic, but mutable-by-default. No built-in spec system. Ecosystem churn.
- **TypeScript**: Types help, but object-oriented heritage. Build complexity. Node ecosystem churn.
- **Clojure**: Immutable, data-oriented, stable, REPL-native. The language is designed for what we're doing.

### Why not a hosted solution?

Local-first means:
- Your data stays yours
- No API rate limits
- Works offline
- Full control over the runtime

### Why build the infrastructure instead of domains?

The infrastructure IS the product. Without schema discovery, temporal history, and verified isolation, agents just create more technical debt. The domains prove the infrastructure works.

---

## The Bet

This is a bet that:

1. AI agents will write most code within 5 years
2. Current approaches (bolt-on assistants) won't scale
3. Purpose-built infrastructure dramatically improves agent reliability
4. Clojure's properties are uniquely suited to this problem
5. The investment in infrastructure pays off as agents get more capable

If wrong: interesting Clojure project with good architecture.
If right: the foundation for how software gets built.

---

## Related Documents

| Document | Purpose |
|----------|---------|
| `CLAUDE.md` | Operational instructions + condensed vision |
| `CONVENTIONS.md` | Code patterns that enable agent discoverability |
| `docs/prds/namespace-ui/prd.md` | UI/observability vision |
| `docs/prds/namespace-ui/design-system.md` | Visual design philosophy |
