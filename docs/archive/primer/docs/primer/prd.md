# PRD: Primer Domain

**Status:** Paused (Stage 6 Complete, Stage 7 Partial)
**Created:** 2024-12-24
**Last Updated:** 2025-02-10

---

## Current State

### Summary

The Primer domain is a **working prototype** of an interactive storytelling system. It successfully demonstrates the core architecture: server-controlled state, SSE-driven UI updates, and a render registry pattern. The infrastructure is solid; what's missing is AI integration and richer templates.

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| Ctx atom (in-memory) | ✅ Working | Multi-session state management via `seon.primer.ctx` |
| Scene rendering | ✅ Working | `narrative/page` template with layered CSS |
| Action handling | ✅ Working | Button clicks trigger scene transitions |
| SSE auto-refresh | ✅ Working | Ctx changes push to browser automatically |
| Demo story | ✅ Working | 7-scene library exploration with branching |
| Debug UI | ✅ Working | `/primer/debug` shows ctx state |
| HTTP routes | ✅ Working | `/primer`, `/primer/action/:id`, `/primer/ctx` |
| Schema validation | ⚠️ Partial | Schemas defined but not enforced on updates |
| XTDB persistence | ⚠️ Broken | `checkpoint!` fails - primer node not initialized |
| Temporal queries | ❌ Blocked | Depends on XTDB persistence |
| AI integration | ❌ Not started | Vision documented in research/ |

### Code Stats

- **~650 lines** across 10 source files
- **No tests** (typical for early prototype)
- **5 research docs** with detailed architectural vision

### Files

```
src/seon/primer/
├── core.clj           # 8 lines - Domain capabilities (scaffold marker)
├── schema.clj         # 29 lines - Malli schemas for Action/Scene/Ctx
├── ctx.clj            # 187 lines - Multi-session ctx API + XTDB sync
├── render.clj         # 25 lines - Render registry pattern
├── render/scene.clj   # 36 lines - Scene renderer
├── styles.clj         # 77 lines - CSS for primer UI
├── html.clj           # 42 lines - Datastar page + SSE content
├── handlers.clj       # 58 lines - HTTP handlers
├── actions.clj        # 100 lines - Action handlers + demo story
└── debug.clj          # 136 lines - Debug UI and EDN export

```

### Integration Status

- **Routes:** Wired in `seon.web.routes` (lines 24-28, 46-50)
- **System:** Primer ctx initialized when `seon.primer` namespace loaded (system.clj:87-93)
- **XTDB:** `seon_primer` database exists but `ctx/checkpoint!` throws because primer-node is nil

---

## Vision

An interactive educational experience for children, inspired by Diamond Age's "Young Lady's Illustrated Primer."

**The Core Insight:** The AI doesn't "generate HTML" - it generates **state transitions**. Templates are pre-built. This gives:
1. Instant rendering (no waiting for AI to generate markup)
2. Deterministic replay (same state = same view)
3. Composable complexity (templates call templates)
4. Debuggable (inspect state at any point)

### Architecture Pattern: Ctx-as-OS

- **Ctx atom** holds all session state
- **Render registry** maps ctx keys to render functions
- **Templates** are pure functions: `(scene, ctx) → hiccup`
- **Actions** are pre-computed; runtime executes instantly
- **XTDB** provides temporal storage for checkpointing/replay

This pattern is fully documented in:
- `research/architecture-vision.md` - Core loop and scene structure
- `research/ctx-as-os.md` - The ctx atom as operating system
- `research/template-system.md` - Template vocabulary (10 core templates)
- `research/state-machine.md` - Transition and checkpoint patterns

---

## What's Broken: XTDB Persistence

The ctx system tries to use XTDB but `primer-node` is nil at runtime:

```clojure
;; In ctx.clj - the node never gets set
(defonce ^:private primer-node (atom nil))

;; checkpoint! fails because primer-node is nil
(ctx/checkpoint! "test-session")
;; => No implementation of method: :get-connection... for class: nil

```

**Root cause:** The initialization in `system.clj:87-93` only runs when `seon.primer` is in the namespace list, but the primer namespace isn't being auto-loaded. The XTDB connection setup works differently than the code expects (uses SQL connections now, not separate nodes).

**To fix:** Align ctx.clj with current `seon.db.node` patterns and ensure primer database is created on demand.

---

## Demo Story: Library Adventure

The implementation includes a working 7-scene branching story:

1. **Welcome** - "The ancient library awaits..."
2. **Library Entrance** - Three bookcases: Blue, Amber, Silver
3. **Blue Knowledge** - Logic and mathematics
4. **Amber Wisdom** - Stories and human experience
5. **Silver Mystery** - Infinite possibilities
6. **Deeper** - A narrow passage appears
7. **Heart** - The open book with blank pages

Navigation: Enter → Choose shelf → Continue → Enter passage/Return → Begin again

---

## Success Criteria (Original)

1. ✅ Primer runs as a Seon domain with its own namespace
2. ✅ Ctx atom drives UI rendering via Datastar SSE
3. ✅ Scene with pre-computed actions renders and responds instantly
4. ⚠️ Agent can update ctx (validated against specs) - **validation not enforced**
5. ❌ Sessions checkpoint to XTDB, can be replayed - **broken**

---

## Next Steps (If Continuing)

### Priority 1: Fix XTDB Persistence

- Align `ctx.clj` with `seon.db.node` patterns
- Use existing connection-manager instead of separate primer-node
- Test checkpoint/load-at/history work

### Priority 2: Enforce Schema Validation

- Wrap ctx mutations with Malli validation
- Add clear error messages for invalid updates

### Priority 3: Add Second Template

- Implement `narrative/choice` template (multiple buttons)
- Update demo story to use it at branch points

### Priority 4: AI Integration

- Connect Claude for dynamic responses
- Implement the "planning phase" pattern from ctx-as-os.md
- Add voice input (Gemini Live API?)

---

## Open Questions

1. **Visual layer** - CSS positioning vs Canvas vs Three.js?
2. **Voice integration** - Gemini Live API or local VAD + transcription?
3. **Image generation** - Pipeline and caching?
4. **Child profiles** - Multi-user support?

---

## Decisions Made

See `decisions.md` for architectural decisions:
1. Separate XTDB node for primer (isolation, independent schema)
2. Ctx atom with Malli validation (catch agent errors immediately)
3. HTML/CSS for initial UI (fastest to prototype, can upgrade later)
4. Pre-computed actions in scene (planning phase pattern)

---

## Stage History

### Stage 1-4: Completed

- Namespace skeleton
- Schema definitions
- Ctx atom with session management
- HTTP routes and handlers

### Stage 5: Completed

- Action handling
- Scene transitions
- Demo story content

### Stage 6: Completed

- SSE auto-refresh via ctx watch
- Layered CSS rendering
- Debug UI

### Stage 7: Partial

- Multi-session ctx API ✅
- Background auto-sync ✅
- XTDB persistence ❌ (broken - node not initialized)
- Temporal queries ❌ (blocked)

---

## Value Assessment

**Is this code worth keeping?** Yes.

1. **The architecture is sound.** The ctx-as-OS pattern, render registry, and SSE integration demonstrate how to build agent-driven interactive UIs.

2. **The research is valuable.** The 5 research documents capture deep thinking about template systems, state machines, and AI integration patterns.

3. **The demo works.** A non-technical person can experience a working interactive story at `/primer`.

4. **Easy to resume.** The broken XTDB integration is a localized issue. Fixing it doesn't require rethinking the architecture.

**This is a paused prototype, not an abandoned experiment.** The approach was validated; development simply moved to higher-priority infrastructure work (agent orchestration, dev hooks, etc.).

---

## Research Documents

For deep architectural thinking, see the research/ folder:
- `architecture-vision.md` - Core loop and scene structure
- `template-system.md` - Template vocabulary (10 core templates defined)
- `state-machine.md` - Transition and checkpoint patterns
- `ctx-as-os.md` - The ctx atom as operating system
- `seon-architecture-research.md` - Existing Seon patterns
