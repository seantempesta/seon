---
type: issue
status: resolved
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
evaluation the ambient `seon.db` reads fail.

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

## Resolution evidence — 2026-08-02, awaiting orchestrator review

Implementation commit `643719904` chooses the evaluator request seam. The
closed `:seon.sci.eval/request` now accepts the existing optional
`:seon.store/branch-connection` shape, and `seon.sci.eval/evaluate` binds it to
`seon.db/*conn*` on the thread that performs the guarded evaluation. An absent
request connection preserves any already-bound render custody; when neither is
present, the binding remains nil and `seon.db` returns its existing
`:seon.db/missing-connection-binding` value.

The binding spans the complete guarded evaluation, not only the immediate
`sci/eval-form` call. `seon.sci.admit/admit` realizes lazy returned values before
disarm, so a narrower binding would let an agent return a lazy computation whose
later `seon.db/q` lost its cluster custody inside the same evaluation.

The source changed under the original evidence before this fix landed. Commit
`113e7e465` reduced the loop's approximately six historical call sites to one
`submit-evaluation!!` invocation and temporarily obtained database custody by
passing the connection through `seon.render/call-with-walk-context`. The fixed
loop now puts the connection in `evaluation-request`; its render context still
supplies agent identity and render caps but no longer establishes evaluation
database custody. The compute worker calls `evaluate` synchronously, so the
request binding encloses compiled first-party Vars invoked by SCI without
depending on executor binding propagation.

### Dependency ledger

- Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`: `binding` installs and
  restores thread-local Var bindings in `reference-code/clojure/src/clj/
  clojure/core.clj:1953-1985`.
- core.async `dc35f3e0d7bc`: the work launcher runs
  the submitted function on its worker and returns its synchronous value;
  Seon's boundary is `src/seon/flow.clj:479-521`.
- SCI `6de15683b752`: `eval-form` directly invokes
  the interpreter (`reference-code/sci/src/sci/core.cljc:401-408`), while
  Seon installs compiled first-party Vars directly at
  `src/seon/sci/eval.clj:907-931`.
- Datahike `256b714d97a0`: both tested connections use
  `:schema-flexibility :write`; `seon.cluster.store/transact!` retains the
  dependency's `:transact/schema` rejection as `:seon.db/rejected`.

### Recurring proof

`test/seon/sci/eval_test.clj` now constructs two canonical populated
connections and two acquired SCI contexts, then calls `evaluate` directly.
The test proves, in one JVM:

- ambient `seon.db/q` returns only `ambient-a` from connection A, only
  `ambient-b` from connection B, then `ambient-a` again;
- a through-door `seon.cluster.store/transact!` of declared
  `:seon.cluster.message/id` commits and the next evaluation reads
  `"ambient-message"`;
- an undeclared `:seon.sci.eval-test/undeclared` write returns
  `:seon.db/rejected` carrying Datahike's own `:transact/schema`; and
- requests without a connection return `:seon.db/missing-connection-binding`
  both before and after the bound evaluations, proving unwind.

The regression was red before implementation with six failures. The focused
post-fix gate passed 82 tests / 399 assertions / 0 failures / 0 errors across
`seon.sci.eval-test` and `seon.cluster.turn-test`. The existing direct
`public-walk-is-callable-through-an-agent-sci-eval` test remains green.

### Live proof

An isolated operator root at `tmp/ambient-connection-live-root` published
current source commit `6a6f5f22-bd18-51ba-8df5-83a54bd87894` and booted
clusters `ambient-fixed-a` and `ambient-fixed-b` in the same JVM, PID `88210`.
A direct through-door evaluation against each cluster's live ctx returned:

```clojure
{:a ["ambient-fixed-a"]
 :b ["ambient-fixed-b"]
 :write-error nil
 :written "ambient-live-message"
 :rejected-kind :seon.db/rejected
 :rejected-rule :transact/schema
 :unbound-kind :seon.db/missing-connection-binding
 :ambient-after nil}
```

`seon.cluster.store/transact!` retains an explicit connection parameter. The
door proof dereferences the now-publicly-bound Var as `@#'seon.db/*conn*`;
ambient `seon.db/q` needs no connection argument. The separate open issue
`host-bound-first-party-vars-break-in-value-position.md` owns why a compiled
host Var in bare SCI value position currently yields the Var rather than its
root.

### Render and remaining direct caller

`src/seon/render.clj` was intentionally untouched. Its binding remains the
render walk's owner for render calls outside evaluation. It should not become a
caller of `evaluate`: during an agent evaluation the loop supplies only render
identity/caps and the nested evaluator binding supplies the database; outside
evaluation there is no evaluator request to delegate to.

One non-loop direct caller remains outside this issue's owned paths:
`src/seon/bootstrap_drive.clj:141-155` has a branch connection but does not yet
put it in its direct evaluation request. Its current held-out grading functions
are pure over the supplied argument. If that surface is expected to grade
database-reading functions, it should pass the same optional request key; it
must not introduce another binding site.

## Closed — 2026-08-02, orchestrator-verified

Accepted after independent verification: commit `643719904` reviewed
(schema delta is exactly the optional `:seon.store/branch-connection`
request key), the regression re-run by the orchestrator (36 tests / 138
assertions / 0 failures in `seon.sci.eval-test`), and the live
two-cluster proof recorded above. The `bootstrap_drive.clj` residue and
the render-walk relationship transfer to the seon.db wave issue.
