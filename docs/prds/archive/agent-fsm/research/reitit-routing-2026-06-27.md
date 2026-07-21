---
type: research
status: active
tags: [research, web, reference]
---

# reitit for Seon routing — investigation (2026-06-27)

**Verdict: ADOPT `reitit.ring`.** Data-driven, `.cljc` (runs in the Node pod),
collapses the three hand-rolled dispatchers into one DB-fed route table. reitit
is the MATCHER; the DB route datoms stay the source of truth; `call.cljs`'s
capability gate stays the security boundary. reitit replaces the *fragile* part
(path→handler dispatch), not the *secure* part.

## Availability + CLJS

- NOT vendored in `reference-code/` (owner misremembered). The jar is already
  resolved transitively via datahike (`metosin/reitit-* 0.10.1` in `~/.m2`); a
  source checkout exists at `~/src-backup/reitit/`. seon's deps don't declare it.
  Adopt = add `metosin/reitit-ring {:mvn/version "0.10.1"}`. **Vendor it into
  `reference-code/` for the deep-dives** (the convention).
- `reitit.core/ring/middleware/impl/trie/coercion` are all `.cljc` — trie
  matcher, path-params, reverse routing, middleware chain all on the cljs path.
  **One caveat:** `reitit.ring`'s `create-resource-handler`/`create-file-handler`
  are `#?(:clj)`-only (JVM ring.util) → keep `serve.cljs`'s `node:fs`
  `serve-static!` for static files. Everything else in `reitit.ring` is host-neutral.

## What it gives us over the hand-rolled cond

- Path-params parsed once (today: `re-matches` twice in tile.cljs:1538-1568; a
  bespoke `complete-path->agent-id`).
- Auto precedence (static > wildcard > catch-all) + **build-time conflict
  detection** (overlapping paths / dup names THROW — the `cond` silently shadows).
- **Reverse routing** (URL from route-name + params) — agent pages link to their
  own routes by name.
- A real **per-route/subtree middleware chain** (the auth seam).
- Open route data — `:seon.render/html` (handler symbol) + `:seon.route/owner`
  ride as opaque route-data keys (`group-keys` only special-cases HTTP methods).

## DB route datoms → reitit (reitit as engine, DB as source of truth)

```clojure
;; row: {:seon.route/method :get :seon.route/pattern "/agent/{id}/dash"
;;       :seon.route/name :agent/dash :seon.render/html 'my.agent.abc/dash-page
;;       :seon.route/owner [:seon.agent/id "abc"]}
(defn db->routes [db]
  (for [r (route-rows db)]
    [(:seon.route/pattern r)
     {(:seon.route/method r) {:name (:seon.route/name r)
                              :handler (resolve-handler r)        ; closure
                              :seon.render/html (:seon.render/html r)
                              :seon.route/owner (:seon.route/owner r)}}]))
(defn build-router [db] (reitit.ring/router (db->routes db) {:data {:middleware []}}))
```

- Handler resolution reuses the two existing mechanisms unchanged: **core** → direct
  call; **agent-authored** (`my.*`) → `call.cljs`'s gate (`capability-check`
  resolves the owning agent from the symbol's ns → granted-`:seon.fn` check →
  SCI-bounded `invoke!`). So routes-as-data is a *third* instance of a pattern the
  codebase already runs twice (`/call`, `resolve-view`).
- **Rebuild-on-change**: the router is a pure derived value of the route datoms —
  memoize on the route-rows' max-tx, or `reloading-ring-handler` per request
  (building a small router is microseconds). No stored router state.

## Auth-later via middleware (the seam — the reason to take reitit)

```clojure
(defn wrap-authz [handler]
  (fn [request]
    (let [owner (-> request ::r/match :data :seon.route/owner)]
      (if (authorized? request owner) (handler request) {:status 403}))))
;; later: (reitit.ring/router routes {:data {:middleware [wrap-authn wrap-authz]}})
```

- Plugs in whole-app / per-subtree / per-route; parent `:middleware` meta-merges
  into children; **zero handler edits** (handler never knows auth exists). All
  `.cljc`. Folds the existing inlined `same-origin?` CSRF guard
  (serve.cljs:610-615) into one POST-subtree middleware.

## Agent routes + `/call`

Two layers — reitit replaces the front door, NOT the gate. `/call` stays as
`?fn=<sym>` namespace-as-route (the security boundary) = one reitit entry. An
agent "app" = real reitit routes whose `:seon.render/html` is a `my.agent.<id>/…`
symbol, dispatched by reitit then authorized by `call.cljs`'s gate. Prefer a
route-per-pattern over a `/{*path}` catch-all (reverse routing + cross-agent
conflict detection).

## Integration cost in the Node pod (small)

`serve.cljs/handler` shrinks to: *(get-or-build router from `@db/*conn*`, memoized
on route-tx) → adapt the `node:http` req to a Ring map (`read-body` exists,
serve.cljs:208-220) → call the ring-handler → write the response*. Needs a
**~20-line Node↔Ring adapter** (the pod is raw `node:http`, not JVM Ring;
reitit.ring's 3-arity `[req respond raise]` async path fits the Promise handlers).
The GET/POST `cond`, the inspector/tile `route?`+`handle!` regex dispatchers,
`complete-path->agent-id`, and the inlined CSRF guard all become route entries +
one middleware. Static-roots is the only hand-rolled survivor.

## Open for the deep-dives (once reitit is set up)

- **Nested layouts via nested route data** (owner's bet) — does reitit's nested
  route tree + reverse-routing express nested layout composition cleanly?
- The `db->routes` projection + rebuild-on-change shape.
- The capability middleware (owner-scoped reads).
- Reverse-routing for agent app-links.
