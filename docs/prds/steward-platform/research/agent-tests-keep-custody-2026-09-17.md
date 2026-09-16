---
type: research
status: complete
created: 2026-09-17
tags: [research, steward, test, custody, agent]
---

# An agent's own tests keep its cluster's custody — the connection is a value

Lane: agent-test-custody, 2026-09-17, branch `steward-platform`.
Issue: [an-agents-own-test-loses-its-clusters-custody](../../../seon/issues/an-agents-own-test-loses-its-clusters-custody.md).

## What was wrong

`seon.test.runner/run-var!` ran every test Var inside
`seon.db/call-without-custody`. That seam has exactly two callers with
opposite needs:

- `seon.test/run` from a HOST REPL — the run is nobody's cluster work, and a
  body that elides `seon.db` must refuse loudly (the 2026-09-17 write storm);
- `my.test/run` inside an AGENT's evaluation — the run IS that cluster's own
  work, and the elided arity is the documented affordance (AGENTS §3).

`run-var!` could not tell them apart because it was deciding from what the
thread happened to carry: `bound-fn` in `seon.test/bounded-result` copies the
creating thread's bindings, so before the strip the agent's test silently used
whichever cluster was in scope, and after the strip it had none. Both are the
same defect — a seam deciding custody by reading ambient state.

## The change

The custody is now a VALUE the caller hands down, next to the provenance the
runner already carries. Nothing on this path re-reads a dynamic var to decide
whose work a run is.

- `seon.db/call-with-custody` (`src/seon/db.clj:259`) — ONE scope: bind
  exactly the handed `:seon.db/connection` (`:seon.db/custody-request`), and
  clear `*read-database*`. `call-without-custody` is now literally
  `(call-with-custody {} f)`: absence is one of its two ordinary answers.
- `seon.test.runner/run-var!` (`src/seon/test/runner.clj:561`) — second
  arity takes that request; the one-argument arity hands none, which keeps
  the `bin/test` worker and `seon.sci.eval/run-candidate-test!` unchanged.
- `seon.test/run` options gained optional `:seon.db/connection`
  (`resources/seon/schemas/seon.test.edn:160`), passed straight through
  `bounded-result` to `run-var!`. The `connection` ARGUMENT remains where the
  result facts are committed and never decides the body's custody.
- `seon.test/run-owned` (`src/seon/test.clj:397`) — the agent's entry: call
  preparation supplies its evaluation's connection, and it hands that same
  connection down as the body's custody.
- `my.test/run` (`src/my/test.clj:36`) expands to `run-owned`.

## Evidence

### Live, on the adopted `default` (pid 88182), SCI evaluation mode

The two callers now differ exactly as intended. An agent-defined `deftest`
whose body uses the elided `(seon.db/db)`:

```clojure
(do (deftest agent-custody-probe (is (int? (seon.db/basis-t (seon.db/db)))))
    (seon.test/run-owned {:seon.test/var (resolve 'user/agent-custody-probe)}))
;; => #:seon.test{:sym "user/agent-custody-probe" :pass-count 1
;;                :fail-count 0 :error-count 0 :run-basis-t 536871219}

(seon.test/run (resolve 'user/agent-custody-probe))
;; => #:seon.test{:pass-count 0 :fail-count 1 :run-basis-t 536871222
;;    :failure-message "expected: (int? (seon.db/basis-t (seon.db/db)))
;;     actual: (not (int? {:seon.db/missing-connection-binding true …}))"}
```

The agent's own run reaches its cluster; the same Var run through `run`,
which hands no custody, refuses loudly and names `seon.db/*conn*`. Both wrote
their ordinary result facts to `default` (run rows 56799 and 56897).

### In-process test runs (pid 88182, one at a time on a daemon thread)

`(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection "default")
{:seon.test.run/provenance (seon.test.runner/provenance db) :seon.test/remaining-ms 100000})`,
each namespace reloaded first through `seon.test`'s own loader. The shared
fixture base was already realized when this lane started.

| test | result |
|---|---|
| `seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody` | 3 passes, 0 failures. Its one error is this JVM's drift detector reporting the two roots my `:reload` of `seon.test-support` re-instrumented — an artifact of reloading in a live cluster, not an assertion. |
| `my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity` | 1 pass, 3 failures, all one cause: `Unable to resolve symbol: seon.test/run-owned`, at SCI ANALYSIS phase, inside the fixture's SCI base ctx. The passing assertion is the one before it — the agent's `deftest` was admitted. |

## Verification boundary

`seon.test-support/database-base` was realized in this JVM BEFORE the edit
(checked at lane start), so the fixture's SCI base ctx predates `run-owned`
and cannot resolve it. Every proof that runs an agent evaluation through that
fixture is therefore unavailable in process here, and this lane did not
rebuild the shared base (the poisoning rule) or restart `default`:

- `my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity`
  is proven only to the point named above;
- `seon.loop-proof-test/virtual-loop-end-to-end`'s `order-total` assertion —
  the issue's original reproduction — was NOT re-run: it drives the same
  fixture ctx and would fail on the same stale symbol. Its other five
  failures remain the separately filed
  `virtual-loop-fixture-submission-can-race-an-armed-turn`.

A cold `bin/test` worker builds its base after this commit and has
`run-owned`; the gate request names both namespaces. The live SCI-mode
evidence above is what stands in for them here, and it exercises the same
`my.test/run` → `run-owned` → `run-var!` path.

Also foreign at this moment: `test/seon/test_support.clj` carries another
lane's 263 uncommitted insertions (it lints clean at the time of writing) and
`src/seon/sci/eval.clj` is that lane's too. Neither was edited here.

## Named, not touched

`src/seon/sci/eval.clj:2686` (`run-candidate-test!`) calls the one-argument
`run-var!`, so an agent's ACCRETION GATE tests — the `deftest`s evaluated in a
candidate ctx before a definition is installed — still run with no custody and
will refuse an elided `seon.db` call exactly as `my.test/run` did. That
request already carries `:seon.db/connection` (`evaluate-candidate`'s
`connection` binding), so the repair is one hunk:
`(test.runner/run-var! test-var {:seon.db/connection connection})` with
`connection` threaded into `run-candidate-test!`. The file is another lane's
this turn, so this lane named it instead of editing it.
