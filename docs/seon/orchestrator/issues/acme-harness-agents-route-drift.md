---
type: issue
status: open
tags: [issue, web]
---

# acme `/agents` doc drift (FIXED) — uncovered: acme pod serves NO db-seeded routes (OPEN regression)

Found by the post-merge acme smoke, 2026-07-02. Verified read-only against
the live acme pod (7980) + wire REPL (7981), 2026-07-02 ~18:45Z.

## Part 1 — doc drift: FIXED

`/agents` was intentionally removed in the db->routes reitit cutover.
`bin/seon:312` documents it in the pod `ready_check`: "(Was `/agents`,
removed by the db->routes routing cutover → 404)" — the supervisor now
probes `/`. There is no `/agents` route datom and no static-supplement
entry (`src/seon/web/router.cljs`); an unmatched path hits the reitit
`not-found` default → `302 Location: /` (router.cljs `not-found`, #28).

Observed: `curl -i 127.0.0.1:7980/agents` → `HTTP/1.1 302 Found`,
`Location: /` (NOT the 200-empty the smoke reported).

`docs/seon/components/acme-harness.md` updated (both cites): inspection =
`/` (root dashboard), `/agent/<id>`, `/agent/<id>/feed`, `/data` (datom
browser, verified 200); readiness poll = `curl -fsS -o /dev/null
127.0.0.1:7980/` (mirrors `ready_check`; passes on 200 and 302).

## Part 2 — LIVE REGRESSION (open): acme pod serves only the static supplement

The acme pod's cached router holds ONLY the static-supplement routes —
every db-seeded core route (`/`, `/agent/{id}`, feeds) falls to `not-found`
and 302-loops.

Evidence, all observed 2026-07-02 ~18:45Z:

- **Route rows exist and are complete** in the acme store (wire REPL 7981,
  pull `[*]` on `[?e :seon.route/pattern]`):
  `/` → `seon.web.datastar/serve-root!` (:get),
  `/agent/{id}` → `serve-agent-page!` (:get),
  `/agent/{id}/call` → `seon.web.reactive.call/handle!` (:post, same-origin),
  `/agent/{id}/feed` → `open-agent-feed!` (:get). (4 rows — no
  `/world`/`/world/feed`, vs the "six core routes" in router.cljs's ns doc.)
- **Live responses:** `GET /` → `302 Location: /` (an INFINITE redirect
  loop — `curl -L` exhausts 50 redirects); `GET /agent/root` → 302 → `/`.
  Neither matches — they fall to the router's `not-found` default.
- **Static-supplement routes work:** `/data` 200 (7550b), `/css/output.css`
  200, `/sse` streams, `POST /solve` actively serving (inspect-ai drives in
  `logs/acme/pod.log`). So the server + router are alive; only the
  `db->routes` projection is absent from the cached ring-handler.
- **Healthy comparison:** the default pod (7890) serves `GET /` → 200
  (2091b) from the same source tree. Acme bundle `out-acme/client/main.js`
  built Jul 2 14:00; one src commit since (ce903dbf 14:41, instrument —
  unlikely related).
- **Boot log** (`logs/acme/pod.log`): "router installed {:supplement 16}"
  at 18:01:06.649, BEFORE the cluster conn opened (18:01:07.102) — that's
  the documented load-time `install!` (supplement-only). `serve/start!`
  is supposed to re-run `router/rebuild!` post-seed (serve.cljs:897,
  server listening 18:01:22) but the resulting router evidently projected
  no route datoms.

Hypothesis (unverified): `rebuild!` reads the pod's LOCAL replica
(`(some-> db/*conn* deref)`, router.cljs). If the boot-seed's route tx had
not yet been applied to the replica when `start!` ran `rebuild!`, the
cached router stays supplement-only forever — there is NO route
tx-listener yet (router.cljs docstring: "when Core wires a route
tx-listener"). A boot race would also explain why the default pod is fine.
`bin/seon`'s `ready_check` cannot catch this: `curl -f /` passes on the
302 the broken router emits.

## Acceptance criteria (remaining)

- Root-cause why the acme pod's post-seed `rebuild!` projected zero routes
  (replica lag at `start!` time vs something acme-specific), and fix in
  the ONE mechanism (e.g. wire the route tx-listener, or gate `start!`'s
  `rebuild!` on the seeded routes being visible in the replica).
- Consider a readiness probe that distinguishes "router serving core
  routes" from "not-found 302" (the current `-f` probe passes on both).
- Note: `src/seon/web/serve.cljs` has UNCOMMITTED edits by another agent
  right now — coordinate before touching it.
