---
type: research
status: completed
tags: [research, agent, db, prd]
---

# Task 9 handoff — `db/transact!` envelope contract (2026-05-26)

## Scope shipped

Single atomic change in `src/seon/db.cljs` + `test/seon/db_test.cljs`. The
`seon.db/transact!` boundary now NEVER throws into the calling agent's
eval context. Every failure path — invocation-shape guard, unregistered
attr, Malli value validation, datahike commit explosion — returns
`{:seon.db/ok? false :seon.db/error <envelope>}`. Successes still return
`{:seon.db/ok? true :seon.db/tx-report …}`.

## Schema chosen

`:seon.db/transact-response` is now an `:or` of two precise shapes (no
loose `[:map [::ok? :boolean] [::tx-report {:optional true} :any] …]`):

```clojure
[:or
 [:map [::ok? [:= true]]  [::tx-report :any]]
 [:map [::ok? [:= false]] [::error ::error]]]
```

`:seon.db/error` is a new registered schema that mirrors the `seon.error/->map`
keys (`:seon.error/message`, `:seon.error/data`, `:seon.error/ex-data`,
`:seon.error/stack`, `:seon.error/cause`, `:seon.error/raw`, `:seon.error/truncated`).
Only `:seon.error/message` is required — the rest are optional because
`->map` only emits them when present in the source error.

## `:user-input` vs `:substrate-bug`

Per the user's instructions, every error path catches and tags
`:seon.error/kind` inside `:seon.error/data`:

- `:user-input` — caller-fault: bad invocation shape, unregistered attr,
  value fails its Malli schema. Tagged at the throw site (each
  `ex-info`'s `ex-data` includes `:seon.error/kind :user-input`); the
  flattened `:seon.error/data` carries it to the envelope.
- `:substrate-bug` — anything else (typically datahike-internal
  explosions, unbound `*conn*`). Default if no throw-site tag.

The tagging path is: throw → `seon.error/->map` flattens `ex-data` chain
into `:seon.error/data` (deepest wins) → `error-envelope` ensures
`:seon.error/kind` is present, defaulting `:substrate-bug` only when no
tag flowed through.

## Entrypoints audited

Only `transact!` got the envelope. Audit of the other public surfaces:

- `query`, `pull`, `entity` — read-only, take a db value. They can throw
  on bad query syntax / unresolvable refs, but reads do NOT carry the
  "crash the agent eval" risk because they're synchronous and the agent
  can wrap them. Out of scope per task instructions; flagged below.
- `listen!`, `unlisten!` — already safe-by-default (`listen!` wraps the
  handler in try/catch + Promise `.catch`). No change.
- `assert-preconditions!`, `with-tx-context`, `with-agent`, `new-id!` —
  not agent-eval-facing surfaces; called by `seon.client` boot.

**Recommendation for follow-up:** apply the same envelope treatment to
`query`/`pull`/`entity` if PRD work surfaces agent eval crashes from bad
query syntax. The pattern is now established; copy-paste.

## REPL evidence (all 4 verification steps)

```clojure
;; (1) Happy path
{:happy true}

;; (2) Validation failure → envelope
{:unreg [false :user-input :seon.db/unregistered-attrs]}

;; Bad value → envelope
{:bad-value [false :user-input :seon.db/invalid-value]}

;; (3) Bad invocation shape → envelope
{:bad-shape [false :user-input :seon.db/invalid-invocation-shape]}

;; Substrate-bug path (bogus conn forcing datahike to explode)
{:ok? false :kind :substrate-bug :msg "No protocol method IDeref.-deref…"}

;; Pod stays alive after failure
{:after-fail true}

;; (4) Test suite
{:type :summary :test 24 :pass 220 :fail 0 :error 0}
```

## Tests changed

- Removed: `transact!-throws-synchronously-on-unregistered-attr`,
  `transact!-throws-synchronously-on-bad-value` (they asserted the
  pre-envelope behavior).
- Added: `transact!-returns-envelope-on-unregistered-attr`,
  `transact!-returns-envelope-on-bad-value`,
  `transact!-returns-envelope-on-bad-invocation-shape`,
  `transact!-pod-stays-alive-after-bad-input`.

The new tests assert: `:ok? false`, no `:tx-report`, error message
present, `:seon.error/kind :user-input`, specific `:seon.db/error`
discriminator key, and (for the pod-stays-alive case) that a
follow-up successful transact works on the same conn after a rejection.

The `validate-attrs!`/`validate-values!` private helpers still throw
(they're internal building blocks); their test coverage is unchanged.

## Code smells / callers needing follow-up

**Every `seon.db/transact!` caller in `src/seon/agent.cljs` ignores the
return value.** Pre-change, malformed tx-data crashed the agent
eval (loud-fail). Post-change, it returns `{:ok? false ...}` silently
and the caller proceeds as if nothing happened. This is the intended
trade — the user explicitly chose "don't crash" over "loud fail" — but
the failure mode is now invisible.

Concrete sites (`src/seon/agent.cljs`):

- L385, L446, L521, L562, L574, L582, L671, L707, L1243, L1258 — all
  `(await (db/transact! …))` with the result discarded.

Recommended follow-up (separate task): install a tx-listener-shaped
inspector on the agent's conn that surfaces envelope failures via the
reactive-context render path (per CLAUDE.md "Reactive context — derived
by default"). Section function that queries some "recent envelope
failures" view; if no failures, no section renders. Self-healing.

Not fixing these in this commit per task scope ("Don't change non-`seon.db`
callers unless they currently catch exceptions and would now miss
envelopes" — none of them catch).

## Docstring updates

- The namespace docstring (line 39-45) and the section about the
  envelope (line 62-72) used to say "Validation errors throw
  synchronously". Updated to reflect the new always-envelope contract
  with the `:user-input` vs `:substrate-bug` discriminator.
- `transact!`'s own docstring rewritten to document the envelope as the
  only return shape, including both `:seon.error/kind` values.

## Files touched

- `/Users/sean/src/seon/src/seon/db.cljs` — schema tightening, throw-site
  tagging (`:seon.error/kind :user-input`), `error-envelope` helper, outer
  try/catch around `transact!` body, docstring updates.
- `/Users/sean/src/seon/test/seon/db_test.cljs` — 2 tests replaced + 2
  new envelope-contract tests added.
