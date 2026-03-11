# Primer Agent Bootstrap

**Status:** Stages 1-7 Complete, Browser Verified

---

## Current State (Working)

- Server running at `http://localhost:8080`
- Primer page at `/primer` - **fully functional**
- REPL available on port 7888
- All success criteria verified

### What's Working

1. ✅ Button clicks → POST fires → ctx updates → browser refreshes via SSE
2. ✅ REPL scene changes appear in browser immediately
3. ✅ `ctx/checkpoint!` persists to XTDB
4. ✅ `ctx/history` shows checkpoints
5. ✅ `ctx/load-at!` restores historical state (time travel)

---

## Previous Issue (FIXED)

**Problem:** Buttons weren't firing POST requests.

**Root cause:** Datastar uses `data-on:click` (colon) not `data-on-click` (hyphen).

**Fix:** Changed `src/seon/primer/render/scene.clj:7` from:

```clojure
{:data-on-click ...}  ; WRONG

```
to:

```clojure
{:data-on:click ...}  ; CORRECT

```

**Lesson learned:** Always invoke `datastar-web-ui` skill before debugging Datastar UI issues.

---

## Quick Verification (For New Sessions)

```bash
# 1. Start server (if not running)
./bin/run

# 2. Verify system health
clj-nrepl-eval -p 7888 "(status)"

# 3. Open browser to http://localhost:8080/primer
# 4. Click "Enter the Library" - should navigate to next scene

# 5. Test REPL control
clj-nrepl-eval -p 7888 "(seon.primer.ctx/assoc! \"default\" :primer/current-scene {:scene/id \"test\" :scene/template :narrative/page :scene/params {:text \"Hello from REPL\"} :scene/actions []})"
# Browser should update automatically

```

---

## Next Steps (Stage 8+)

The Primer foundation is working. Possible next directions:

### Option A: Richer Scene Templates

- Add more template types beyond `:narrative/page`
- Image backgrounds, multiple choice layouts
- Character portraits, speech bubbles

### Option B: Agent-Driven Content

- Connect LLM to generate story content
- Agent writes to ctx, UI renders automatically
- Spec-constrained generation

### Option C: Voice Integration

- Gemini Live API for voice interaction
- Read story aloud, accept voice commands

### Option D: Child Profiles

- Multi-user session management
- Progress tracking per child
- Adaptive difficulty

---

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/primer/ctx.clj` | Multi-session ctx API, XTDB sync |
| `src/seon/primer/render/scene.clj` | Scene renderer, `data-on:click` buttons |
| `src/seon/primer/handlers.clj` | HTTP handlers |
| `src/seon/primer/actions.clj` | Action logic, demo scenes |
| `src/seon/primer/styles.clj` | CSS styles |
| `.claude/skills/datastar-web-ui/SKILL.md` | **Invoke this for Datastar work!** |

---

## Commands Reference

```bash
# REPL eval
clj-nrepl-eval -p 7888 "(expression)"

# Reload code
clj-nrepl-eval -p 7888 "(reset)"

# Check system status
clj-nrepl-eval -p 7888 "(status)"

# Create/update scene
clj-nrepl-eval -p 7888 "(seon.primer.ctx/assoc! \"default\" :primer/current-scene {...})"

# Checkpoint to XTDB
clj-nrepl-eval -p 7888 "(seon.primer.ctx/checkpoint! \"default\")"

# View history
clj-nrepl-eval -p 7888 "(seon.primer.ctx/history \"default\")"

```
