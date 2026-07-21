---
type: research
status: completed
tags: [research, agent]
---

# Task 9 verification — `db/transact!` envelope contract (2026-05-26)

## Verdict: YELLOW

The envelope contract holds for `transact!`. Two gaps prevent GREEN:

1. `db/query`, `db/pull`, and `db/entity` still throw synchronously — no
   envelope on any read path.
2. Non-sequential `::tx-data` values (JS objects, strings, numbers) bypass
   the `assert-invocation-shape!` guard and are misclassified as
   `:substrate-bug` instead of `:user-input`.

Neither is a regression introduced by this commit; both are contract gaps
relative to the "no crashes regardless of what the agent does" standard.

---

## Group A — can you still throw?

### A-1: JS exotic object as tx-data

**Probe:** `(db/transact! {:seon.db/tx-data #js {:foo "bar"} :seon.db/conn C})`

**Result:** `{:ok? false :kind :substrate-bug :msg "[object Object] is not ISeqable"}`

**Verdict:** Enveloped — PASS on contract. SMELL on classification.

`assert-invocation-shape!` only validates the outer arg map has a
`::tx-data` key; it does NOT check that the value of `::tx-data` is
sequential. A JS object passes the shape guard, then fails at
`extract-tx-attrs` (which tries `mapcat` over it). The error is caught by
the outer try/catch and tagged `:substrate-bug` because no throw-site tag
flows through. The correct kind is `:user-input` — this is a caller fault,
not a datahike internal.

### A-2: Large batch (500 entities)

**Probe:** `(db/transact! {:seon.db/tx-data (mapv ...) :seon.db/conn C})`

**Result:** `{:ok? true}` — succeeded.

**Verdict:** PASS. No OOM or stall at 500 entities.

### A-3a: Explicit keyword as conn value (`:not-a-conn`)

**Probe:** passes `resolve-conn` (keyword is truthy), then datahike explodes
dereffing it.

**Result:** `{:ok? false :kind :substrate-bug :msg "No protocol method
IDeref.-deref defined for type null: "}`

**Verdict:** Enveloped — PASS. `:substrate-bug` is correct here (the
caller broke the contract on `::conn`'s type, which is legitimately
ambiguous between user-error and misuse-of-internal-field).

### A-3b: `(atom nil)` as conn

**Result:** Same error, same classification. PASS.

### A-4: Re-entrant transact inside a tx-listener callback

**Probe:** handler calls `db/transact!` during a listener invocation.

**Result:** `{:trigger-ok? true :reentrant-ok? true}`

**Verdict:** PASS. JS single-threaded event loop means the inner transact
is queued and resolves cleanly. No deadlock, no crash.

### A-5: Malformed `[:db.fn/cas]` vector (wrong arity)

**Result:** `{:ok? false :kind :substrate-bug}`

**Verdict:** Enveloped — PASS. Datahike-internal throw caught at the
inner try/catch.

---

## Group B — other entrypoints

### B-1: `db/query` with non-seqable query string

**Probe:** `(db/query {:seon.db/query "not-a-valid-query" :seon.db/conn C})`

**Result:** `THROWS "Error: not-a-valid-query is not ISeqable"`

**Verdict:** CONTRACT GAP. Throw propagates directly to agent eval. The
handoff doc acknowledged this as out-of-scope; this probe confirms the gap
is real and reproducible.

### B-2: `db/query` with syntactically invalid Datalog

**Probe:** `'[:find ?e :where [bogus syntax here]]`

**Result:** `THROWS "Query for unknown vars: [?e]"`

**Verdict:** CONTRACT GAP — same surface, different throw path. Agent
writing a Datalog query with a typo crashes its eval context.

### B-3: `db/pull` with non-existent eid

**Result:** Returns `{:db/id 99999999}` — empty entity map, no throw.

**Verdict:** PASS. Datahike returns an empty entity for unknown eids.

### B-4: `db/entity` with non-existent ref

**Result:** Returns a live entity object (queryable, all keys nil). No throw.

**Verdict:** PASS.

### B-5: `db/listen!` handler that throws

**Probe:** Handler throws `ex-info`; outer transact continues.

**Result:** `{:throw-count 1 :tx-ok? true :pod-alive? true}`

**Verdict:** PASS. `listen!` wraps handler in `try/catch`; exception is
logged via `js/console.warn`, pod survives. Matches the spec-02 §2.5
contract.

---

## Group C — the 10 silent-failure callers

Three representative sites examined:

**L385 (`create!`):** `(await (db/transact! ...))` result discarded. If
transact fails (e.g. unregistered attr during a schema change), `create!`
returns `{:seon.agent/id id}` with the agent entity never in the DB. Caller
has no signal. **Fire-and-forget bug.**

**L562 (`with-turn!` turn-open):** Discards envelope. If the open-turn tx
fails, `body-fn` (the LLM call) still runs. The turn entity was never
created. The close-tx at L574 will also fail silently (no entity to upsert
against). The error-state-tx at L582 will also fail silently. Net effect:
LLM turn ran and produced output, but ZERO trace exists in the DB.
**This is the worst case — data loss with no error visible to the caller.**

Pre-envelope behavior: bad tx-data at L562 would have thrown; being outside
the `try` at L571, that throw would propagate to the caller. The envelope
change made this scenario LESS visible than before. Worth calling out even
though per the task scope "no callers catch exceptions and would miss
envelopes."

**L707 (`run-agentic-loop!` cap-hit):** Transacts a system message informing
the user the turn cap was hit. If this fails, the user sees nothing and the
cap-hit event is invisible. Lower severity than L562 but still a silent
miss.

**Recommendation:** The 10 callers fall into two buckets:
- **Must check** (L385, L521, L562, L574, L582, L671): state transitions and
  entity creation. Failure here = diverged DB / runtime state.
- **Should check** (L446, L707, L1243, L1258): writes that produce visible
  side-effects; silence = missing user-visible content.

The handoff doc's suggestion (reactive-context section that surfaces envelope
failures via tx-listener) is the right architecture — it's self-healing and
doesn't require plumbing envelope checks into every caller.

---

## Group D — `:user-input` / `:substrate-bug` split

### D-1: `"not-a-list"` as `::tx-data` value

**Result:** `{:ok? false :kind :substrate-bug :msg "Bad transaction data
\"not-a-list\", expected sequential collection"}`

**Verdict:** MISCLASSIFICATION. A string as tx-data is unambiguously a
caller fault. The shape guard checks the outer map key presence but not the
value type. Datahike's own validation catches it first and the outer catch
defaults to `:substrate-bug`.

### D-2: Integer `42` as `::tx-data` value

**Result:** `{:kind :substrate-bug}` — same pattern. MISCLASSIFICATION.

### D-3: `:seon.db/ref` to non-existent eid — passes Malli, fails at datahike

**Result:** `{:ok? false :kind :substrate-bug}`

**Verdict:** ACCEPTABLE. A ref to eid 99999 passes Malli (integers satisfy
`:seon.db/ref`) and is only rejected at write time. Whether to check ref
existence before writing would require a read; `:substrate-bug` is
defensible (datahike's constraint rejected it, not our gate).

---

## Group E — regression sweep

| Test namespace | tests | pass | fail | error |
|----------------|-------|------|------|-------|
| `seon.db-test` | 24 | 220 | 0 | 0 |
| `seon.test.fixture-support-test` | 1 | 4 | 0 | 0 |
| `seon.test.async-fixture-test` | 1 | 3 | 0 | 0 |
| `seon.test.runner-test` | — | — | — | — |

`seon.test.runner-test` did not resolve within the probe timeout. This is a
pre-existing issue: `run-ns!` requires `*conn*` to be bound (it persists
results via `db/transact!`), which is not the case in the bare REPL context.
This hang predates task 9 and is unrelated to the envelope change. The three
tests that could be isolated (`vars-in-ns-discovers-probe-tests`,
`vars-in-ns-returns-empty-for-unknown-ns`) ran and passed.

Both envelope shapes (`ok? true` and `ok? false`) validate against the
`::transact-response` Malli schema — confirmed via `(m/validate
:seon.db/transact-response r)` on live returns.

---

## Findings

**1. `db/query` and `db/pull` throw on agent-authored bad input (BLOCKER for
"no crashes regardless of what the agent does")**

Severity: **blocker** relative to the personal-AI platform guarantee, but
**not a regression** from task 9 (acknowledged in the handoff).

Repro:

```clojure
(db/query {:seon.db/query "bad"})   ; throws synchronously
(db/query {:seon.db/query '[:find ?e :where [bogus]]})  ; throws

```

Next step: Task 10 prerequisite or immediate follow-up task wrapping
`query`/`pull`/`entity` with the same `error-envelope` pattern.

**2. Non-sequential `::tx-data` misclassified as `:substrate-bug` (SMELL)**

Severity: **smell** — the contract is upheld (it envelopes), but the
discriminator is wrong. An agent reading the kind to decide whether to retry
or escalate would make the wrong decision.

Repro: `(db/transact! {:seon.db/tx-data "oops"})` → `:kind :substrate-bug`.

Fix: add a `(sequential? tx-data)` check in `assert-invocation-shape!` or
`validate-values!`, tag with `:user-input`.

**3. `with-turn!` (L562) discards open-turn envelope — LLM runs without DB
trace (SMELL / latent data-loss)**

Severity: **smell** — requires a schema/conn problem at L562 to manifest in
practice. But the envelope change made it less loud than pre-envelope
behavior (used to throw past the try block).

The 10 caller sites flagged in the handoff are real; the worst case is L562.
Recommend the reactive-context failure surface be the follow-up task.

**4. `seon.test.runner-test` hangs in REPL context (NIL)**

Pre-existing; not introduced by task 9. Unrelated to envelope contract.

---

## Recommendations

**Do not block on task 9 for task 10 (multi-agent fixture).** The core
contract (`transact!` never throws) is sound and all 220 assertions pass.

**Create two follow-up tasks:**

- **Task 9a (small):** Extend the `assert-invocation-shape!` guard to check
  `(sequential? tx-data)` and tag with `:user-input`. Fixes finding 2.

- **Task 9b (medium):** Apply the same `error-envelope` wrapper to
  `db/query`, `db/pull`, and `db/entity`. The pattern is established; it's
  mechanical copy-paste plus 3–4 new test assertions per fn. Required before
  the platform can claim "no crashes regardless of agent input." Finding 1.

The reactive-context section for surfacing envelope failures (finding 3) can
wait for a later task — it's an observability improvement, not a
correctness fix.
