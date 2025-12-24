# Primer Agent Bootstrap

**You have browser access. Your mission: Get the Primer UI working end-to-end.**

---

## Current State

- Server running at `http://localhost:8080`
- Primer page at `/primer` shows text but **buttons don't work**
- REPL available on port 7888

## The Problem

User sees: "Welcome, young explorer..." but clicking "Enter the Library" does nothing.

---

## Your Phases

### Phase 1: Diagnose (Browser + Network)

1. Open `http://localhost:8080/primer` in browser
2. Open DevTools → Network tab
3. Click the button
4. **Check:** Does a POST request fire? To what URL? What's the response?
5. Check Console for JS errors

**Report what you see before fixing anything.**

### Phase 2: Verify Backend (REPL)

```bash
clj-nrepl-eval -p 7888 "(require '[seon.primer.ctx :as ctx])"
clj-nrepl-eval -p 7888 "(ctx/get \"default\")"
```

Check if session exists. If nil:
```bash
clj-nrepl-eval -p 7888 "(require '[seon.primer.actions :as actions])"
clj-nrepl-eval -p 7888 "(actions/ensure-session! \"default\")"
```

Test action handler directly:
```bash
clj-nrepl-eval -p 7888 "(actions/handle-action \"default\" :enter-library)"
```

**Does ctx change? Check browser - did it update?**

### Phase 3: Fix the Issue

Based on diagnosis, likely issues:

1. **Button POST URL wrong** - Check `src/seon/primer/render/scene.clj`:
   ```clojure
   {:data-on-click (str "@post('/primer/action/" (name id) "')")}
   ```
   Should match route in `src/seon/web/routes.clj`

2. **Route not matching** - Check routes file for `/primer/action/:id` pattern

3. **Handler not updating ctx** - Check `src/seon/primer/handlers.clj` action-handler

4. **SSE not refreshing** - Check ctx watch is firing

### Phase 4: Build Scene via REPL

Once buttons work, demonstrate full control:

```clojure
;; Create a custom scene
(ctx/assoc! "default" :primer/current-scene
  {:scene/id "custom-1"
   :scene/template :narrative/page
   :scene/params {:text "This scene was created via REPL!"}
   :scene/actions [{:action/id :next
                    :action/label "Continue"}]})
```

**Verify:** Browser updates automatically via SSE.

### Phase 5: Verify XTDB Persistence

```clojure
;; Force checkpoint
(ctx/checkpoint! "default")

;; Check it persisted
(require '[xtdb.api :as xt])
(def primer-node (:seon.primer/xtdb-node integrant.repl.state/system))
(xt/entity (xt/db primer-node) "default")
```

**Verify:** Data is in XTDB.

### Phase 6: Time Travel Demo

```clojure
;; Make several scene changes
(ctx/assoc! "default" :primer/current-scene {:scene/id "v1" ...})
(ctx/checkpoint! "default")
(Thread/sleep 1000)

(ctx/assoc! "default" :primer/current-scene {:scene/id "v2" ...})
(ctx/checkpoint! "default")

;; Query history
(ctx/history "default")

;; Load old state
(ctx/load-at! "default" #inst "2024-12-24T08:00:00")
```

---

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/primer/ctx.clj` | Session management, XTDB sync |
| `src/seon/primer/render/scene.clj` | Renders scene → hiccup, button data-on-click |
| `src/seon/primer/handlers.clj` | HTTP handlers including action-handler |
| `src/seon/primer/actions.clj` | Action logic, demo scenes |
| `src/seon/web/routes.clj` | Route definitions |
| `src/seon/primer/styles.clj` | CSS (pointer-events for clickability) |

---

## Success Criteria

1. ✅ Click button → POST fires → ctx updates → browser refreshes
2. ✅ REPL scene changes appear in browser immediately
3. ✅ `ctx/checkpoint!` persists to XTDB
4. ✅ `ctx/history` shows checkpoints
5. ✅ Can build entire scene from REPL commands

---

## Commands Reference

```bash
# REPL eval
clj-nrepl-eval -p 7888 "(expression)"

# Reload code
clj-nrepl-eval -p 7888 "(reset)"

# Check system status
clj-nrepl-eval -p 7888 "(status)"
```

**Start with Phase 1. Report browser network/console findings first.**
