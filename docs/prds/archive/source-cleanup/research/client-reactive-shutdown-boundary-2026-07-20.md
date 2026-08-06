---
type: research
status: complete
tags: [research, database, architecture]
---

# Client reactive advertisement and shutdown boundary (2026-07-20)

This report grounds the remaining Stage 4–5 client advertisement and reactive
shutdown fold against source at `748410dd`. It refines the earlier
[[bespoke-reactive-sweep-2026-07-20]] recommendation in one load-bearing way:
the observed value cannot be only the resumable agent-id vector. Agent
membership is unchanged when `:seon.agent.runtime/wake?` or
`:seon.agent.run/paused-at` changes, but those transactions currently re-arm
the affected process runtime. An ids-only value would be equality-suppressed by
`seon.reactive` and silently lose that lifecycle effect.

The implementation remains dependency-ready only after the Stage-4 reactive
router cut. That cut establishes the one process-lifetime registration pattern
and makes global shutdown release cover the router as well as the client
advertisement.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract used here |
|---|---|---|
| Seon reactive owner | `src/seon/reactive.cljs` at `748410dd` | `observe!` installs an all-attributes interest before the first computation, replaces it with captured read evidence, runs only one computation at a time, retains only the newest pending database value, suppresses equal values, catches Promise-returning notifier failures, and releases the last consumer through `unobserve!`. `close!` atomically removes every registration before clearing timers and awaiting every `db/unlisten!`. |
| Seon database client | `src/seon/db.cljs` at `748410dd` | `with-read-evidence` captures Datahike plans from reads over the supplied immutable database value. `listen!` sends those plans to the writer and keys replacement/release by the public interest key. |
| Datahike | `reference-code/datahike` at `6f2569087ed31f53e751e7535ef4bf2527912046` | `query.cljc` returns `:datahike.read/dependency-plan`; `pull_api.cljc` derives selector plans; `core.cljc` makes a repeated listener key replace its callback and `unlisten!` remove it. Seon transports the maintained dependency plan rather than parsing query syntax itself. |
| ClojureScript | runtime `1.12.145`; vendored source `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77` | Native `^:async` functions return Promises and `await` is valid only in their async body. Reactive notifier callbacks may return a Promise; `notify-one!` attaches a rejection logger without awaiting it. |
| Proximum database values | `reference-code/proximum` at `9846d3e79e1aee48474bc876d3d563d7137209c6` | The computation receives one immutable database value with branch/basis identity; no connection, ambient reread, or generic coordinate map belongs in the projection. |
| Current membership projection | `src/seon/derive.cljs`, `src/seon/agent.cljs` | `resumable-agent-ids-query` selects born agents (`:seon.eval/home-requires`) without `:seon.agent/terminated-at`, including idle, running, and paused agents. `agent/resumable-agent-ids!` accepts an explicit `:seon.db/db`. |
| Current process-runtime inverse | `src/seon/agent/runtime.cljs`, `src/seon/agent/loop.cljs` | `agent-runtime/resume!` replaces the stable wake listener/input, applies `wake?`, and re-drives committed work; a terminated entity causes `unhost!`. `unhost-all!` is the shutdown inverse. |
| Current client lifecycle | `src/seon/client.cljs` | `runtime-advertisement` is synchronous over cached ids. Cold start and hot reload await `acquire-resumable-agent-ids!` before explicitly resuming every id. `drain-runtime-owners!` stops wake/ticker/hosts, detaches advertisement, detaches admission, then closes the database session. |
| HTTP and feed inverse | `src/seon/web/serve.cljs`, `src/seon/web/datastar.cljs` | Full stop closes every feed, whose socket inverse unobserves its consumer, then detaches the router and stops Bun. Operator quiesce deliberately leaves HTTP alive but still closes the database session after draining runtime owners. |

First-party proof already exercising the idioms:

- `test/seon/reactive_test.cljs` proves first delivery, equal suppression,
  newest-pending replacement, exact interest replacement, final-consumer
  release, and bounded measurements.
- `test/seon/web/serve_test.cljs` proves the request-scoped
  `/agents/run` registration releases in `finally`.
- `test/seon/client_advertisement_test.cljs` proves the behavior that the
  bespoke owner currently protects: one attach, initial-listen race closure,
  stale-result refusal, projection error cleanup, and lifecycle-fact resume.
- `test/seon/client_initialization_test.cljs` proves reload publication occurs
  before explicit rehost and that rehost finishes before ticker/heartbeat.

## Current landed and gap state

The generic mechanism is landed and exercised. The `/agents/run` polling fold
is landed at `6f157a3a`. The client and router have not yet folded:

- `src/seon/client.cljs:344-539` still owns
  `refresh-runtime-advertisement!`, four literal datom patterns, two entity-to-
  id queries, `reconcile-agent-runtimes!`, an owner token, an attach Promise,
  a latest-database equality fence, and explicit `db/listen!`/`unlisten!`.
- `src/seon/web/router.cljs` still owns its direct listener/desired/accepted
  database settle loop. This is why the router cut remains the prerequisite.
- `reactive/close!` still has no caller in `src/`.
- `drain-runtime-owners!` detaches only the advertisement interest before
  `db/close-session!`; a quiesce leaves router/feed registrations to be torn
  down transitively by session closure rather than by their owner.
- `test/seon/client_advertisement_test.cljs` asserts the bespoke listener
  representation, so most of that fixture must be replaced rather than
  preserved after the cut.

The owning issue remains
[[../../../seon/issues/bespoke-reactive-loops-outside-seon-reactive]]. The
route and shutdown portions cannot be closed from the already-landed run-poll
proof.

## Correct observed value

One registration owns both the synchronous MCP advertisement projection and
the process-runtime lifecycle signal:

```clojure
{:seon.client.runtime-advertisement/resumable-agent-ids ["root" "task-a"]
 :seon.client.runtime-advertisement/runtime-controls
 [["root" {:seon.agent.runtime/wake? true
            :seon.agent/run
            [{:seon.agent.run/id "run-1"
              :seon.agent.run/paused-at #inst "..."}]}]
  ["task-a" {}]]}
```

The exact stored shape may use a namespaced private schema, but it must obey
these semantics:

1. `resumable-agent-ids` is the existing sorted born-and-nonterminated
   projection. `runtime-advertisement` publishes only this vector.
2. `runtime-controls` is a deterministic, ordinary-data projection whose
   value and read evidence change for every lifecycle fact the removed
   listener handles: agent birth/id, termination, `wake?`, the agent→run
   connection, and `paused-at`. It does not store a derived kind or state.
3. The computation runs every read inside one `db/with-read-evidence` over the
   database value supplied by `seon.reactive`. It returns exactly
   `{::db/value projection ::db/read-evidence evidence}` and never calls
   `db/db` to freshness-check its own input.
4. A first delivery populates the synchronous advertisement cache before
   `observe!` returns. Later equal projections cause no notifier effect.
5. A changed control projection reconciles process runtimes. Removed ids are
   explicitly unhosted; added or control-changed ids are resumed. Re-resume is
   allowed only because `agent-runtime/resume!` is the existing idempotent
   replacement owner. The implementation must not resume the entire unchanged
   fleet merely because one row changed unless a focused cost and ordering
   proof shows that is bounded and safe.

The previous ids-only sketch fails item 2. A `wake? true→false` transaction or
a pause/unpause transaction leaves the id vector byte-identical, so
`finish-evaluation!` increments `::equal-notifications-suppressed` and never
calls the notifier. The current direct listener does call
`reconcile-agent-runtimes!` for both attributes. An implementation that merely
copies the earlier sketch is therefore a regression.

The notifier's process effect also needs explicit ordering. `seon.reactive`
serializes computations but intentionally does not await notifier Promises.
Do not recreate a second newest-pending queue in `seon.client`. Either:

- make each changed-id reconciliation independently safe against a newer
  termination/control value through the existing runtime owner and prove the
  adversarial completion order; or
- strengthen the one generic reactive owner with a narrowly schema'd awaited
  effect contract, if route and other consumers demonstrate that this is the
  common missing semantic.

The first option is the smaller expected cut, but it is a proof obligation,
not an assumption. A stale resume that finishes after a newer termination and
reinstalls a wake input is unacceptable.

## One-mechanism implementation boundary

After the reactive router cut, one client-owned commit changes only the
existing owners and their focused tests:

1. Add the closed private advertisement/control projection schema and one
   pure computation in the current client/agent projection owner. Reuse
   `agent/resumable-agent-ids!`; add only the control projection needed to
   preserve the four current trigger families.
2. Replace `attach-runtime-advertisement!` with one
   `reactive/observe!` registration using stable registration and consumer
   keys. Concurrent calls naturally share the registration's one ready
   Promise. `acquire-resumable-agent-ids!` returns the first delivered ids or
   its standard error value.
3. Keep `runtime-advertisement` synchronous over the last delivered ids so MCP
   discovery performs no database I/O.
4. Route later control changes through one notifier into
   `agent-runtime/resume!`/`unhost!`. Preserve the existing cold-start and
   reload ordering: publication completes before the explicit full rehost,
   and initial observer delivery must not create an extra pre-publication
   resume pass.
5. Delete, in the same commit, `refresh-runtime-advertisement!`,
   `runtime-agent-datom-patterns`, both event entity-id queries and their
   helpers, `runtime-advertisement-event!`, the owner/interest/refresh/attach
   atom keys, and every direct client `db/listen!`/`db/unlisten!` call.
6. Replace `detach-runtime-advertisement!` with ordinary consumer release only
   where a partial lifecycle still needs it. The complete runtime inverse uses
   `reactive/close!`; it must not enumerate registrations itself.
7. In `drain-runtime-owners!`, await `reactive/close!` after execution hosts
   and runtime inputs are stopped and before admission detach and
   `db/close-session!`. This location covers both full stop and operator
   quiesce. In full stop, `web.serve/stop!` has already closed feed sockets and
   detached the router. In quiesce, HTTP intentionally remains available for
   the terminal operator response, but every database-backed registration is
   released before its session disappears.

The route fold must not add a second global close call. The one client runtime
inverse owns process shutdown; Datastar continues to own per-socket
`unobserve!`, router continues to own a partial detach while the runtime is
live, and `reactive/close!` is the final idempotent sweep.

## Lifecycle and error contract

- The observed projection and every failure are ordinary values. A database
  or projection failure is the standard closed `:seon/error` value delivered
  by the registration; it is cached only long enough for
  `acquire-resumable-agent-ids!` to refuse startup/rehost through the existing
  lifecycle failure path. It is never inferred merely from an arbitrary
  `:seon.error/message` collision after the Stage-5 result-union cut.
- Runtime reconciliation failures are logged once through the existing
  reactive consumer rejection path and leave the registration live for a
  later database value. Core startup/rehost callers retain their existing
  refusal behavior; agent mistakes never throw into an agent loop.
- `unobserve!` and `close!` are idempotent. A close before first delivery
  resolves the ready wait with the existing `:canceled` error value.
- `reactive/close!` must finish before `db/close-session!`. A refused unlisten
  makes the lifecycle inverse fail and retain `cleanup-required`; it must not
  be converted into a successful shutdown.
- A retry from `cleanup-required` may call `close!` again and receive nil; no
  stale advertisement atom key or registration is reconstructed during the
  retry.

## Focused falsifiers

Replace representation assertions with behavior and ownership assertions:

1. **First value and single registration.** Two concurrent acquisition calls
   install one reactive registration and one writer interest, both receive the
   same first sorted id vector, and `runtime-advertisement` performs zero reads.
2. **Exact evidence.** The cold `:all` interest is replaced by captured query
   evidence covering id/birth/termination/wake/run/paused controls. An
   unrelated committed attribute causes zero recomputation.
3. **Newest database.** Delay computation for T1, deliver T2, and prove T1
   cannot overwrite the cache; use reactive measurements rather than a client
   freshness reread.
4. **Wake transition with unchanged membership.** Commit `wake? true→false`
   for an existing id. The id vector remains equal, the full control projection
   changes, and exactly that runtime is reconciled/unhosted as required.
5. **Pause transition with unchanged membership.** Add and retract
   `paused-at`; each committed value reaches reconciliation even though the
   advertisement ids do not change.
6. **Termination race.** Hold an older resume Promise, commit termination,
   release completions in both orders, and prove no wake input remains for the
   terminated id.
7. **Initial and reload ordering.** Initial delivery only establishes the
   cache; cold start and reload each perform one explicit full rehost after
   publication, with no pre-publication or duplicate resume.
8. **Error recovery.** Refuse initial interest and initial projection in turn;
   acquisition returns/refuses through the standard error value, leaves no
   consumer, and a corrected retry installs exactly one registration.
9. **Shutdown ordering.** Stub every inverse and assert:
   ticker/wake/drain/unhost/child-stop → `reactive/close!` → admission detach →
   `db/close-session!`. Make close reject once and prove the session stays open,
   phase is `cleanup-required`, and the corrected retry succeeds.
10. **Ownership sweep.** `rg` finds no client `db/listen!`, `db/unlisten!`,
    advertisement owner/refresh/attaching key, lifecycle datom-pattern literal,
    or source caller of `reactive/close!` outside the one runtime inverse.

Run the focused selectors for `seon.client-advertisement-test`,
`seon.client-initialization-test`, `seon.reactive-test`, and the owning runtime
lifecycle tests. Preserve the existing zero-warning requirement.

## Frozen live falsifiers

At one recorded source commit and process generation, after the Stage-4 route
cut:

1. Cold `bin/seon up`; status reaches ready. Record
   `reactive/measurements` with exactly one client advertisement registration
   in addition to the expected router/feed registrations.
2. Use a durable task agent. Restart the client and prove its existing open or
   paused run resumes from database facts; no duplicate run or wake listener is
   created.
3. Toggle the agent's `wake?` policy and pause/resume its run while keeping the
   same id. Observe the process runtime change and unchanged MCP advertisement
   membership. This is the decisive falsifier for the ids-only defect.
4. Hot reload without changing the projection. The full-fleet resume count
   does not increase beyond the one explicit rehost pass, and the reactive
   measurement records an equal notification suppression rather than a second
   lifecycle effect.
5. Transact an unrelated attribute. Advertisement evaluation and notification
   counts do not advance.
6. Open an agent SSE feed, then run the operator quiesce. The terminal response
   completes; `reactive/measurements` reaches zero registrations/consumers/
   timers before the database session closes; the feed closes or becomes
   terminal rather than hanging on a dead interest.
7. Start again, then perform full stop. Logs show feed close and router release
   before the one reactive close, followed by database-session close. A second
   stop is an ordinary successful no-op.

The issue closes only when the route fold, advertisement fold, and shutdown
call are all committed and these focused plus frozen live proofs are linked
from the source-cleanup roadmap. The final program gate still requires the
three frozen suites and live default/ACME graduation; this unit does not claim
those broader gates by itself.
