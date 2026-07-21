---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database, tooling]
---

# Make branch-qualified eval_cljs database reads resolve

## Problem

A cluster-qualified `eval_cljs` reaches a healthy retained-branch pod, but an
ordinary `(await (seon.db/db))` never resolves. The MCP timeout returns an
"async value still pending" failure while the same branch pod remains ready
and serves its database-derived web UI over HTTP and identity SSE.

This blocks direct REPL proof against an isolated retained branch. It does not
block the branch writer, branch HTTP rendering, default-cluster `eval_cljs`, or
retained-branch cleanup.

## Evidence

The behavior reproduced on 2026-07-21 across two fresh branches and fresh MCP
sessions at frozen source `3d5fa23f`:

- `default-adv-22d861e1/root` stayed pending at `(await (seon.db/db))` for a
  60-second MCP timeout. Its branch was ready at port 62292, and identity SSE
  returned a 42,428-byte `app-view` frame with a database-derived transcript.
- `default-adv-1251e825/root`, with explicit `cluster: "default"` and fresh
  session `adv2-acquire-1251b`, stayed pending for a 90-second timeout at the
  same read. Its writer had already committed 85 allocated branch entities and
  a later usage update at branch basis 536875961.
- Both branches inherited default basis 536875959. Both closed cleanly after
  the failure, and default remained ready and unchanged at basis 536875959.
- A third HTTP-only branch at port 63881 independently served an identity SSE
  `app-view` after equivalent branch writes, confirming that retained-branch
  database acquisition and rendering work outside this MCP evaluation path.

The timeout happens before an instrumented transcript acquisition installs
temporary wrappers around `seon.db/execute-many` or `seon.db/pull-many`. The
observed mismatch is therefore the branch-qualified MCP evaluation's database
read context, not transcript pagination.

## Owner

The cluster-qualified CLJS evaluation and runtime-session resolution boundary
in `script/seon/dev/mcp.clj`, together with the branch pod database context
selected for code evaluated through that boundary. The fix should strengthen
that one path rather than add a branch-only database API or timeout fallback.

## Acceptance

- Open two independently named retained branches from one ready default
  cluster and commit one branch-local fact to each.
- In fresh sessions, evaluate `(await (seon.db/db))` through each exact
  `<cluster>/<agent>` handle. Each call resolves before the MCP timeout and
  returns that branch's current immutable database value, not default's value
  or the other branch's value.
- A subsequent branch-local query observes only its own committed fact.
- Default and bare-agent evaluation retain their current behavior, and an
  ambiguous bare agent still fails rather than selecting a branch.
- Closing both branches leaves no retained intent, session, route, or process;
  default's basis and readiness remain unchanged.

## Resolution

Current source does not reproduce the recorded hang. Two simultaneous retained
branches, `default-brfix/root` and `default-brfixb/root`, each resolved the exact
top-level `(await (seon.db/db))` form in about 0.5 seconds and returned its own
database name at basis 536875961. A same-session sequence separately proved
branch attachment, ordinary Promise awaiting, database awaiting, and manual
cross-eval `globalThis` settlement. After both exact branches closed, their
intent and process records were absent and default returned the identical
database name, basis transaction, and commit ID.

The static audit established that a selectable runtime has already awaited its
database during advertisement, and that the MCP owner carries the selected
Shadow port and nREPL session through every async-bridge evaluation. A focused
regression now freezes that invariant across the initial eval, wrapper, pending
poll, resolved poll, and fetch. A second regression proves an ambiguous bare
agent never reaches nREPL. The complete operator gate passes 292 tests and
1,634 assertions.

The earlier transient cannot be attributed to a surviving source defect, so no
database timeout workaround or second branch read path was added.
