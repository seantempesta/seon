---
type: issue
status: open
tags: [issue, web]
---

# acme-harness doc cites `/agents` as the inspection probe — route returns empty

Severity: cleanup (doc drift misleads the next operator). Found by the
post-merge acme smoke, 2026-07-02.

## Symptom

`curl http://127.0.0.1:7980/agents` returns HTTP 200 with an **empty body**
(root `/` returns a real page). `docs/seon/components/acme-harness.md:161,176`
still cites `/agents` as the inspection/readiness probe.

## Likely cause

The route moved or was renamed in the agent-fsm reitit routing work
(hierarchical `:seon.route/*` datoms), and the harness doc wasn't updated.

## Acceptance criteria

- Determine the current inspection/readiness route (read `seon.web.router` /
  the `:seon.route/*` datoms on a live pod).
- Update `acme-harness.md` to the real route, OR restore `/agents` if its
  emptiness is itself a regression (then this becomes a tooling-lane defect).
