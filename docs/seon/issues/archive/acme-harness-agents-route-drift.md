---
type: issue
status: resolved
severity: friction
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

## Verdict — post-planner-fix verification (2026-07-02, fresh world on da257d38)

Verified on a fresh acme world (`bin/acme cluster reset` after the datahike
fork bump `41c1b9b2` → `da257d38`, minimal-overrides `config/acme.edn`):

- **Routes serve.** `GET /` → 200, `GET /agent/root` → 200, `/agents` → 302
  (expected). Held across the fresh boot AND a subsequent pod restart that
  resumed root from the existing store. 4 route rows confirmed via the wire
  REPL.
- **The planner fix is live on acme** — proven on the wire-server with the
  planner forced on (`binding [datahike.query/*force-legacy* false]`): the
  previously-failing clause order (`[?e :seon.route/pattern "/"]
  [?e :seon.route/name _ ?tx] [?tx :db/txInstant ?at]`) returns the correct
  row, and the `pattern+name` join returns all 4 rows.
- **BUT the 302 regression was NOT planner-rooted.** `db->routes` was (and
  is) a SINGLE-clause query — `[:find [(pull ?e [...]) ...] :where
  [?e :seon.route/pattern]]` — and the collect-field bug
  ([[archive/datahike-query-clause-order-empty-results]]) only affects multi-clause
  probe joins (a merge-clause probe var outside the v slot). A one-clause
  scan has no probe, so the planner bug cannot have emptied the router
  projection. Also, the failing bundle (built Jul 2 14:00) already ran
  `e6d196d5`; the ONLY datahike delta to the working world is the planner
  collect fix + changelog. What the planner bug DOES explain is the
  **diagnostic** `#{}` on the route JOIN despite 4 rows — it poisoned the
  investigation, not the serving.
- **Standing explanation: the boot race** (rebuild! at `start!` reading a
  replica that didn't yet carry the route rows) remains the best hypothesis
  for the original failure — un-falsified but NOT reproduced (2/2 boots on
  the fresh world served routes; the failing boot's log was truncated by
  restart, so it cannot be re-examined). There is still NO route
  tx-listener; `rebuild!` runs once per `start!`.

## Acceptance criteria (remaining)

- Root-cause why the acme pod's post-seed `rebuild!` projected zero routes
  (replica lag at `start!` time vs something acme-specific), and fix in
  the ONE mechanism (e.g. wire the route tx-listener, or gate `start!`'s
  `rebuild!` on the seeded routes being visible in the replica).
- Consider a readiness probe that distinguishes "router serving core
  routes" from "not-found 302" (the current `-f` probe passes on both).
- Note: `src/seon/web/serve.cljs` has UNCOMMITTED edits by another agent
  right now — coordinate before touching it.

## Resolution

Resolved in the generic mechanism without touching ACME. `seon.web.router`
attaches one stable database listener and reconciles from post-commit route
facts, eliminating the seed/rebuild race described above. The behavioral
`route-facts-update-the-live-router-without-explicit-rebuild` proof passed in
the focused four-namespace checkpoint on 2026-07-14 (29 tests/169 assertions
total).
