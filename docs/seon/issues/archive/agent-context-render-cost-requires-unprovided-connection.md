---
type: issue
status: resolved
severity: friction
tags: [issue, render, database, wave/live-drive-context]
---

# Carry the connection to agent-context render-cost recording

## Problem

The agent-only render-cost fence requires a database connection that the real
run-loop prompt request never supplies. A real provider-bound context render
therefore records zero `:seon.render.cost` facts.

## Evidence

Drive 1 Attempt 4 produced capture
`6c5dad44-e55a-4184-b4bb-0cf07a6b8764-context-536871046`, a provider attempt,
and 22,604 billed prompt tokens. A live query after settlement and after
reading both web pages still found:

```clojure
{:cost-entities 0, :cost-estimates 0}
```

The exact failed condition is `(:seon.db/connection request)`.
`src/seon/render.clj:704-710` transacts cost only when the render is new, has a
call ID, captured-call sink, run ID, **and connection**. The real prompt map in
`src/seon/cluster/loop.clj:1366-1378` supplies the held run ID, agent ID, caps,
SCI context, time limit, error policy, and context channel, but no connection.
`seon.cluster.prompt/acquire-within-budget` adds only the database value and
distance before the request reaches `context-pass`.

Commit `0e7c38cfc` correctly stopped web GETs from recording cost, but its
agent-context predicate is unconstructable from the production caller.

## Owner

The values-carry-their-world request from `seon.cluster.loop` through
`seon.cluster.prompt` to `seon.render/render-call`.

## Acceptance

- A real held-run context acquisition records cost for every newly invoked
  selected renderer.
- Ordinary agent, root, and debug GETs record no cost facts.
- The prompt caller and request schema explicitly carry the connection needed
  by the recording effect; no owner fetches it globally.
- One regression proves both halves using the same production request path.

## Resolution

Commit `aed781a3b` adds `:seon.db/connection` to the prompt request contract
and hands the held cluster connection from `seon.cluster.loop` through
`seon.cluster.prompt` into every agent-context render call. No render owner
fetches custody.

`seon.cluster.prompt-test/prompt-is-derived-append-only-repl-history` now
drives the production context channel and asserts that new render-cost facts
exist. `seon.render-coverage-test/only-agent-context-render-receipts-record-cost`
retains the other half: a web-like HTML render records no cost. Those focused
suites passed, including 15 tests and 199 assertions in the combined prompt
and render-coverage run before the stable-basis expectation was corrected,
then 10 prompt tests and 116 assertions after correction.

On fresh isolated root `tmp/context-fidelity-proof.Jq1MLw`, two live prompt
acquisitions recorded 130 render-cost facts. The ordinary HTML-read fence is
unchanged: the write still requires a held run ID and captured-call sink as
well as the newly carried connection.
