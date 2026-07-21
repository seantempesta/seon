---
type: research
status: completed
tags: [research, agent]
---

# Task 9b handoff — envelope contract extensions (2026-05-26)

## Verdict: PARTIAL — fixes 2 & 3 shipped; fix 1 (`query`/`pull` envelope) NOT shipped due to a scope conflict the user should resolve before proceeding.

Two of three fixes landed cleanly with REPL verification. The third (wrap
`query` / `pull` / `entity` with the `:or` envelope schema) was attempted,
then reverted on review of caller-blast-radius. Reasoning in §1 below.

---

## Fix 1 — `query` / `pull` / `entity` envelope: NOT SHIPPED (escalated)

**Conflict.** The task spec says two things that cannot both be true:

- *"Wrap with the same `error-envelope` helper... register
  `:seon.db/query-response` as `:or` of ok-shape + error-shape, mirror
  `:seon.db/transact-response`."*
- *"Stay in `seon.db`, `seon.agent`, and `test/seon/`. Don't touch the
  runner, runtime, or other agent.cljs callers."*

`db/query`, `db/pull`, `db/entity` have **72 callsites** outside
`seon.db`:

- `src/seon/agent.cljs` — 33 sites (some `(db/entity ent)` get auto-
  destructured against ent's keys; some `(->> (db/query …) (mapv …))`)
- `src/seon/client.cljs`, `src/seon/runtime.cljs`, `src/seon/handlers/**`,
  `src/seon/sections/**`, plus `test/`.

Wrapping them in `{::db/ok? true ::db/result …}` is a uniform breaking
shape change. Every caller has to be touched OR every caller has to
read `::db/result` from the envelope. The "stay in seon.db / seon.agent /
test" constraint excludes the bulk of these.

I implemented the wrap once, reverted it on observing the blast radius
(grep evidence: 72 sites; agent.cljs alone has 33 read calls in patterns
like `(db/entity {…})`'s return flowing through dozens of inspector /
section / render fns). Shipping it would either:

(a) violate the "don't touch other callers" constraint by editing
    runtime/client/handlers/sections, OR
(b) leave the codebase non-compiling / runtime-broken at the merge
    point.

**Recommended split for the user to choose between:**

- **Option A (envelope wrap, full).** A separate task that explicitly
  scopes ALL 72 callsites and updates them in one atomic commit. ~6–10
  files of substantive edits + agent.cljs touching ~33 sites.
- **Option B (silent-fail wrap, no envelope shape change).** Catch throws
  in `query` / `pull` / `entity` and return `nil` (or `[]` for query).
  No schema change, no breaking-shape change, zero callsite churn. Lower
  diagnostic value (callers can't tell "no rows" from "datahike threw"
  without a separate inspection surface) but a real "no crash"
  guarantee.
- **Option C (do nothing for this task).** Read-path envelope wrapping is
  designated finding #1 in the verifier doc as **blocker** but not a
  regression of task 9. Leave it for a dedicated cross-cutting task.

I picked C for this session: no wrap, no API break. Repros from the
task spec still throw:

```clojure
(db/query {:seon.db/query "bad"})            ; still throws
(db/query {:seon.db/query '[:find ?e :where [bogus]]}) ; still throws
(db/pull {:seon.db/pull-pattern '[*] :seon.db/ref :nope}) ; depends on input

```

This is honest reporting per CLAUDE.md "Honesty > completion" — surfacing
the conflict beats charging forward and breaking the build.

### Audit of other `seon.db` entrypoints the agent's eval can reach

Per CLJS source review of `seon.db`:

| fn | throws on agent input? | in scope this task? |
|----|--------------------------|----------------------|
| `transact!` | No — envelope (task 9). | Already done. |
| `query` | YES — bad query / args. | Deferred (Option A). |
| `pull` | YES — bad pattern / ref. | Deferred (Option A). |
| `entity` | YES — bad ref shape. | Deferred (Option A). |
| `listen!` | No — `:default/fn` wraps handler in try/catch. | OK. |
| `unlisten!` | No — `d/unlisten` is no-op on unknown keys. | OK. |
| `new-id!` | No — pure fn. | OK. |
| `assert-preconditions!` | Yes — boot path, throws on bad config. Not called from agent eval. | OK (boot-only). |
| `with-tx-context` / `with-agent` | Yes — `.run` re-throws body throws. Agent eval doesn't call directly. | OK (substrate helper). |

Public API surface in `seon.db` that needs envelope work is exactly the
three read functions named above.

---

## Fix 2 — `sequential?` shape guard for `::tx-data`: SHIPPED

Added a third arm to `assert-invocation-shape!` in `src/seon/db.cljs`.
After the "must be a map" and "must contain `::tx-data`" checks, the
guard now asserts `(sequential? (::tx-data arg))`. Non-sequential
values (string, integer, nil, JS exotic object) hit this branch and
throw an `ex-info` tagged `:seon.error/kind :user-input`, which
`error-envelope` returns as the standard task-9 envelope.

### REPL verification (live pod, task 9b)

```clojure
(-> (db/transact! {:seon.db/tx-data "not-a-list"}) (.then …))
;; → {:seon.db/ok? false
;;    :seon.db/error {:seon.error/data {:seon.error/kind :user-input
;;                                       :seon.db/error :seon.db/invalid-invocation-shape
;;                                       :seon.db/actual-value "not-a-list"
;;                                       :seon.db/actual-shape #object [String]}
;;                    :seon.error/message "seon.db/transact!: `:seon.db/tx-data` must be a sequential collection …"}}
;; Same shape for 42, nil, #js {:foo 1}.

```

All four cases classified `:user-input` (previously all `:substrate-bug`).
Verifier's finding 2 closed.

### Test coverage

`test/seon/db_test.cljs` — added `transact!-envelopes-non-sequential-tx-data`
covering string, integer, nil, and JS-object inputs.

**Caveat on test count:** The CLJS test runner reports `:pass 220 :fail 0`
both before and after the new test was added. Inspecting the events shows
the new `deftest` is enumerated (`vars-in-ns` now returns 29 vars, up from
24) but its `is` assertions don't propagate through the runner's recorded
events. This is a **pre-existing test framework limitation** affecting
ALL `async`-wrapped `deftest`s in `db_test.cljs` (including the existing
`query-finds-transacted-rows`, `pull-by-lookup-ref`, `entity-lookup`,
`query-accepts-explicit-db`) — `cljs.test/async` inside `(go …)` doesn't
fire the runner's event callbacks for inner `is` forms. The behavior is
verified end-to-end via the live-pod REPL probe above; not via the
runner's assertion count.

---

## Fix 3 — `with-turn!` short-circuit on open-turn failure: SHIPPED

In `src/seon/agent.cljs` L546-L580, the original code awaited the open-
turn `transact!` and dropped the envelope. If `:seon.db/ok? false`, the
LLM body still ran, producing a turn that has no DB entity — the
close-tx and error-tx silently fail against the missing entity.

New shape: bind the open-tx result to `open-result`, branch on
`(false? (:seon.db/ok? open-result))`. False → return the envelope to
the caller (matching the same envelope shape `transact!` returns
elsewhere). True → invoke the new private `with-turn-body!` that
contains the unchanged success/error path.

`with-turn-body!` is forward-declared via `(declare with-turn-body!)`
immediately before `with-turn!` (CLJS doesn't allow implicit forward
references). Behavior of the post-open path is byte-identical to the
pre-change body.

### REPL verification

Inspected the compiled output of `seon.agent/with-turn!`:

```javascript
var open_result = (await seon.db.transact_BANG_(…));
if (new cljs.core.Keyword("seon.db","ok?",…).cljs$core$IFn$_invoke$arity$1(open_result) === false) {
  return open_result;
} else {
  return seon.agent.with_turn_body_BANG_(id, id_of_turn, body_fn);
}

```

Short-circuit branch is in place. Behavioral test against a forced
open-failure scenario was not added to `test/seon/agent_test.cljs` —
**that file doesn't exist** in the test tree (`ls test/seon/agent` shows
sub-namespace tests, no top-level `agent_test.cljs`). Creating a new
agent test ns just to demonstrate `body-fn` non-invocation would
require fixture infrastructure (session/turn/agent entity setup) that
multiplies this task's scope. Recommend either:

- Add the test against a contrived conn-wrapper in a follow-up that
  also includes the other agent.cljs callers from the verifier's
  "10 silent-failure callers" list (the natural unit of work), OR
- Accept REPL evidence + compiled-output inspection for now, since
  the change is mechanical and the test fixture would dwarf the fix.

---

## Regression sweep — all green

Live pod, post-restart, post-edits:

| Test ns | tests | pass | fail | error |
|---------|-------|------|------|-------|
| `seon.db-test` | 29 | 220 | 0 | 0 |
| `seon.test.runner-test` | 7 | 26 | 0 | 0 |
| `seon.test.fixture-support-test` | 1 | 4 | 0 | 0 |
| `seon.test.async-fixture-test` | 1 | 3 | 0 | 0 |

`seon.db-test` jumped from 24 → 29 (the verifier's hung-test note from
2026-05-26 was a stale runtime issue; pod restart resolved it). Assertion
count unchanged at 220 — see "Caveat on test count" above.

---

## Files touched

- `src/seon/db.cljs` — added `sequential?` arm to `assert-invocation-shape!`
  (L820-L838). No other public API change. Reverted the response-schema +
  read-path envelope wrap.
- `src/seon/agent.cljs` — split `with-turn!` into open-tx + body, added
  forward-declared `with-turn-body!` and short-circuit branch on
  `:seon.db/ok? false`.
- `test/seon/db_test.cljs` — added `transact!-envelopes-non-sequential-tx-data`.

No changes to runner, runtime, handlers, sections, or any agent.cljs
caller outside `with-turn!` itself.

---

## What's left, ranked

1. **Read-path envelope wrapping (fix 1) — needs user decision** on
   Option A vs B vs C above. Until done, agent eval can crash itself
   with `(db/query "bad")` typos. This is the verifier's blocker
   finding.
2. **`with-turn!` short-circuit behavioral test** — see fix 3 caveat.
3. **9 other silent-failure callers** (L385 create!, L446, L521, L574,
   L582, L671, L707, L1243, L1258 per the verifier doc) — observability
   task, not in this scope.
4. **`seon.test.runner-test` reported as hung in the verifier doc**
   was actually a stale pod runtime state. Post-restart, runs in
   <2s with 26 assertions, all pass. No action needed.
