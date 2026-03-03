# Unified Flow System — Implementation Plan

## Context

Seon's flow topology exists as code but **is never started**. There's no Integrant component for it. `build-topology!` and `request!` work in tests but nothing in the running system uses them. Meanwhile, `seon.db/transact!` uses a separate standalone writer flow, the super-repl uses direct `pool/nrepl-eval!`, and various atoms manage state that should be flow process state.

This plan makes flow the backbone of the running system — started early, always available, reliable enough that wiring new things in later (reports, diagnostics, cross-runtime routing) is trivial.

**The one pattern**: `register promise → inject → step-fn → reply-router → deliver promise`. Everything crosses boundaries this way.

**Two-tier flow architecture** (from research — see `architecture-diagrams.md` sections 6 and 13):
- **Infrastructure flow** (boot once, rarely rebuilt): writer + reply-router + event-sink + error-sink
- **Per-namespace flows** (created lazily, independently): one flow per namespace, sends replies to infrastructure via `flow/inject`
- **Code updates** to existing namespaces: hot-swap via var indirection (`#'step-fn`). Zero disruption.
- **Adding a namespace**: create a new flow. No disruption to existing flows.
- **Full rebuild**: only for infrastructure changes. Tested but rare.

**Functions return natural data.** Their `:malli/schema` is the contract. Error handling, diagnostics, and reporting conventions will emerge from using the system — we'll add `seon.report` and metadata conventions once we understand what we actually need.

---

## Phase 0: Doc Alignment (orchestrator-direct)

**Goal**: No agent reads stale/conflicting docs.

**Socratic questions**: After updates, does any doc contradict unified-flow/design.md? Are there references to "Super REPL" that should say "seon.repl"?

### Files to update:
1. **`docs/prds/super-repl/prd.md`** — Header noting evolution. Rename "Super REPL" → "seon.repl".
2. **`docs/prds/super-repl/flow-buildout.md`** — Header: executed and completed.
3. **`docs/prds/super-repl/flow-viz-plan.md`** — Header: see unified-flow/design.md.
4. **`VISION.md`** — Update "In Progress" to reference unified-flow.
5. **`docs/prds/unified-flow/design.md`** — Add "Redundant Systems" section. Brief cross-runtime note.
6. **`CLAUDE.md`** — Brief note: "All cross-boundary calls use `topology/request!`."

---

## Phase 1: Infrastructure Flow as Integrant Component (1 agent, ~3 files)

**Goal**: The infrastructure flow (writer + reply-router + sinks) starts at boot as an Integrant component. Namespace flows are NOT part of this — they're created lazily later.

### Changes:
1. **`resources/system.edn`** — Add `:seon.flow/infrastructure` component:
   ```edn
   :seon.flow/infrastructure
   {:connection-manager #ig/ref :seon.db.datalevin/connections}
   ```
2. **`src/seon/system.clj`** — `init-key` for `:seon.flow/infrastructure`:
   - Creates flow with: writer-step + reply-router + event-sink + error-sink
   - Starts + resumes it
   - Returns `{::flow ::chans ::flow-id}`
   - `halt-key` stops the flow
3. **`src/seon/flow/topology.clj`** — New fn `build-infrastructure!` (just the stable processes). Separate from `build-topology!` which wires namespace processes.

**Socratic questions before launching**:
- Can the infrastructure flow run with ZERO namespace flows? (Yes — writer + sinks are self-contained)
- Does `flow/ping-proc :seon.flow/writer` work after boot?
- What happens on `(user/reset)` — suspend/resume or halt/init?

**Socratic questions for verification**:
- `(flow/ping infra-flow)` shows writer + router + sinks all `:running`?
- Writer accepts a `flow/inject`'d write request and delivers via reply-router?

---

## Phase 2: Writer in Topology (1 agent, ~4 files)

**Goal**: Writer is a process in the main topology. `seon.db/transact!` routes through it.

**Depends on**: Phase 1 (topology component exists)

### Redundant systems to remove:
| Current | Replacement |
|---------|-------------|
| `writer/create-writer-flow` | Writer process in main topology |
| `writer/write-reply-step` | Topology's reply-router |
| `writer/inject-tx!` | `topology/request!` |
| `seon.db/writers` atom | Writer is topology process |
| `seon.db/write-stats` atom | Writer step-fn state via `flow/ping` |
| `seon.db/transact!` future+timeout | `topology/request!` → `:seon.flow/writer` |
| `pause-writes!`, `resume-writes!`, etc. | `flow/pause-proc`, `flow/resume-proc` |

### Changes:
1. **`seon.db.datalevin.writer/db-writer-step`** — Rewrite: handles ALL databases (keyed by db-name in request). Port IDs use unified `:seon.flow.in/request` / `:seon.flow.out/reply` convention. Delete `write-reply-step`, `create-writer-flow`, `inject-tx!`.
2. **`seon.flow.topology/build-topology!`** — Wire writer process. Its reply output → reply-router.
3. **`seon.db/transact!`** — Route through `topology/request!`. If topology not running, throw (not fallback).
4. **`seon.db`** — Delete: `writers` atom, `write-stats` atom, `all-conns`, `pause-writes!`, `resume-writes!`, `shutdown-writers!`, `remove-writer!`, `writer-status`, `stats`.

**Socratic questions before launching**:
- Does the scanner's background `ingest-namespace!` call `transact!`? If so, does it run after topology starts?
- Does `runtime/mark-crashed!` in `:seon/runtime-db` init call `transact!`? (Yes — need boot order fix)
- What about `runtime/register!` → `persist-instance!`?

**Socratic questions for verification**:
- `(flow/ping-proc topology :seon.flow/writer)` returns meaningful state?
- `seon.ctx/persist!` still works? (calls `transact!` via requiring-resolve)
- `seon.flow.trace` still writes? (writes trace events)
- Full test suite passes?

---

## Phase 3: Boot Order Fix (1 agent, ~2 files)

**Goal**: Integrant components that write to Datalevin depend on `:seon.flow/topology`.

**Depends on**: Phase 2 (transact! goes through topology)

### Analysis from explore:
- `:seon/runtime-db` init calls `mark-crashed!` → `transact!`. Needs topology.
- `:seon.graph/scanner` background future calls `ingest-namespace!` → `transact!`. Needs topology.
- `:seon.orchestrator/sessions` doesn't write during init.

### Changes:
1. **`resources/system.edn`** — `:seon/runtime-db` depends on `:seon.flow/topology`. `:seon.graph/scanner` depends on `:seon.flow/topology`.
2. **`src/seon/system.clj`** — Adjust init-key for `:seon/runtime-db` to accept topology ref (even if it doesn't use it directly — the dependency ensures ordering).

**Socratic questions**: Does this create a circular dependency? (topology needs runtime-db for snapshots, runtime-db needs topology for writes). If yes, split: topology init doesn't snapshot, just builds. Snapshot is a separate operation.

**Socratic questions for verification**:
- Clean `(user/reset)` from scratch — does everything boot?
- `./bin/run` cold start — no errors in logs?

---

## Phase 4: Bare Keyword Cleanup (1 agent, ~4 files)

**Goal**: Every data key in flow/db/ctx is fully namespaced.

### Targets:
| File | Bare Keywords | New Namespace |
|------|--------------|---------------|
| `seon.ctx` | 13 internal registry keys | `::ctx/` |
| `seon.flow.topology` | `:cycle-detected`, `:cycles`, `:cycle-descriptions` | `::topology/` |

### Bug fix:
- **`seon.flow.harness.bridge`** lines 227-233: Transition signals auto-namespace wrong. Fix to full literal `:clojure.core.async.flow/*` keywords.

**Socratic questions**: Are there callers outside these files that destructure old bare keys? Grep before renaming.

---

## Phase 5: Rename Super REPL → `seon.repl` (1 agent, ~4 files)

**Goal**: `seon.repl.super` → `seon.repl`. Clean name.

1. Rename namespace + file
2. Update all requires
3. Rename test file
4. Update PRD docs

---

## Phase 6: Wire `seon.repl` Through Topology (1 agent, ~3 files)

**Goal**: REPL eval routes through flow topology instead of direct `pool/nrepl-eval!`.

**Depends on**: Phase 1 (topology running) + Phase 5 (renamed)

1. **`seon.repl/eval-form!`** — Use `topology/request!` for routing
2. **`bin/mcp-server`** — Pool sessions route through `seon.repl/eval-form!`
3. **`seon.flow/agent_runner.clj`** — Namespace name (not port) for Datalevin URI

**Socratic questions**: Can we eval a defn through topology → stored in Datalevin → graph updated? Can another agent discover it?

---

## Execution Order

```
Phase 0 (doc alignment)           ← orchestrator direct
    ↓
Phase 1 (Integrant topology)      ← 1 agent, ~3 files, foundation
    ↓
Phase 2 (writer in topology)      ← 1 agent, ~4 files, biggest change
    ↓
Phase 3 (boot order)              ← 1 agent, ~2 files, may merge with Phase 2
    ↓
Phase 4 (bare keyword cleanup)    ← 1 agent, ~4 files, mechanical
    ↓
Phase 5 (rename seon.repl)        ← 1 agent, ~4 files, simple
    ↓
Phase 6 (repl through topology)   ← 1 agent, ~3 files, integration
```

Each phase commits before next. Each agent runs full test suite. **After each phase**: formulate verification questions, launch verifier if complex.

---

## Key Question: Dynamic Topology

core.async.flow does NOT support adding processes to a running flow. `create-flow` takes a fixed process map. To add a namespace process later (when an agent claims a namespace), we must either:

**Option A**: Stop → rebuild → start the flow (brief interruption)
**Option B**: Create a new flow per namespace (multiple flows, one topology concept)
**Option C**: Pre-allocate process slots in the flow

This is a critical design question for Phase 1. The agent should research this in the flow source.

---

## Key Files

| File | Role |
|------|------|
| `docs/prds/unified-flow/design.md` | Architecture source of truth |
| `resources/system.edn` | Integrant component config |
| `src/seon/system.clj` | Component init/halt methods |
| `src/seon/flow/topology.clj` | Flow wiring, `request!`, `build-topology!` |
| `src/seon/flow/harness.clj` | Namespace step, JVM lifecycle |
| `src/seon/flow/harness/bridge.clj` | Agent JVM bridge |
| `src/seon/flow/msg.clj` | Message envelope schemas |
| `src/seon/db.clj` | Database API |
| `src/seon/db/datalevin/writer.clj` | Writer step-fn |
| `src/seon/ctx.clj` | Ctx atom management |
| `src/seon/runtime.clj` | Runtime state, flow handles |

## Verification (after all phases)

1. `bin/test` — all tests pass
2. `./bin/run` cold start — topology running, writer process active
3. REPL: `(flow/ping-proc topology :seon.flow/writer)` — shows stats
4. REPL: `(seon.db/transact! conn [{:test/key "value"}])` — goes through topology
5. REPL: `seon.repl` eval → routes through topology → stored in Datalevin
6. No bare keywords in flow/db/ctx data maps
7. No doc contradicts unified-flow/design.md
