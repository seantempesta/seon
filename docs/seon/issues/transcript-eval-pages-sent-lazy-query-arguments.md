---
type: issue
status: open
severity: blocker
tags: [issue, database, agent, cljs]
---

# Transcript eval pages sent lazy query arguments

## Evidence

On 2026-07-18, the relocated Bun package successfully evaluated scalar forms,
but every subsequent agent prompt rendered its transcript as a database
protocol failure: `Protocol requests contain only eager ordinary wire values.`
The agent could not observe the result and repeated the same form.

`seon.agent.ctx.transcript/acquire-eval-pages` passed each lazy sequence from
`partition-all` as a Datalog collection-binding argument. The remote database
protocol correctly rejects arbitrary sequential values because they may be
lazy or unbounded. Existing acquisition tests replaced `seon.db/execute-many`
and inspected page sizes without asserting that the complete request was an
ordinary wire value.

## Owner and acceptance

The transcript acquisition owner must materialize every eval page as a vector
before calling `seon.db/execute-many`. Its focused test must validate each
complete paged request with `seon.db.protocol/ordinary-wire-value?`. A real
agent in the source-free Bun package must see the prior scalar result, call
`complete`, and return the committed reply.
