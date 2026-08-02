---
type: issue
status: open
severity: blocker
tags: [issue, sci, database, runtime]
---

# Bind the agent's cluster connection for ambient `seon.db` reads and writes

## Problem

Owner intent (2026-08-02, verbatim): "The seon.db tools don't require a
conn to be passed — it assumes the current db, and it should be doing
that for that agent's cluster." And: "Agents should be able to transact
ANY attribute as long as it's valid and passes the schema validation."

The code half-implements that intent. `seon.db/q`, `pull`, and
`pull-many` default to the ambient `seon.db/*conn*`
(`src/seon/db.clj:10`, `:40-51`), but the ONLY site that ever binds it
is the render walk (`seon.render/call-with-walk-context`,
`src/seon/render.clj:106-109`). The run loop threads the branch
connection explicitly to its own call sites and never binds the ambient
custody around `seon.sci.eval/evaluate`, so inside an agent's
evaluation the blessed read facade fails.

Live proof (2026-08-02, cluster `default`, through the door —
`seon.sci.eval/evaluate` against the live cluster ctx with the
cluster's own caps):

- `(seon.db/q '[:find (count ?e) . :where [?e :seon.cluster.agent/id _]])`
  → `{:seon.error/kind :seon.db/missing-connection-binding, …}`.
- `seon.cluster.store/transact!` IS installed in the ctx, and an agent
  CAN reach a live connection today — but only by derefing a private
  var (`@@#'seon.cluster/running-instances` evaluates through the door
  and returns the instance map). Capability without a surface: the
  blessed path fails while the hack path works.

Schema validation on writes is already in place at the one door:
`:schema-flexibility :write` (`src/seon/cluster/store.clj:88,179`)
refuses undeclared attributes, and `transact!` returns refusals as
values. So "any declared attribute, schema-checked" is the reality the
moment the connection is ambient; nothing else gates it (the
persistence gate of ruling #30 remains the future control point and is
NOT this issue).

## Acceptance

- One mechanism establishes the agent's cluster connection as the
  ambient custody for the duration of each evaluation, at one seam (the
  loop's evaluate boundary or inside `evaluate` from a request field —
  whichever the owning namespace argues for; do not bind it in two
  places).
- Through the door, ambient `(seon.db/q …)` answers from the agent's
  cluster branch; a second cluster in the same JVM answers from ITS
  branch (no cross-cluster leak).
- Through the door, `(seon.cluster.store/transact! …)` with a declared
  domain attribute commits and is readable in the next evaluation;
  with an undeclared attribute it returns Datahike's own
  `:seon.db/rejected` value.
- The render walk's existing binding keeps working (it may become a
  caller of the same mechanism, never a second one).
- Regression: a door-mode test proving the ambient read, the committed
  write, and the schema refusal; the missing-binding error stays for
  genuinely unbound contexts.
