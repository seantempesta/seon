---
type: research
status: complete
tags: [research, prd, database, flow]
---

# Aggregate `execute-many` result bound — 2026-07-16

## Decision

Add one required outer `:datahike.resource/max-result-weight` to every wire
`execute-many` request. Keep the existing optional per-member bounds. The outer
value limits the complete composed response; a member value limits that one
database operation. The CLJS facade supplies a measured default before sending,
so ordinary callers do not tune transport budgets.

Accept completed member results against the outer budget in vector-position
order, never worker-completion order. Reserve the weight of the response
envelope and one small fixed error result for every member at admission. As
contiguous positions complete, replace each reserved error with its actual
validated member response and charge only the exact structural-weight
difference. If a position cannot fit, retain earlier positions, return the
fixed bounded error at that position and every later position, stop admitting
members, cancel queued/cooperatively cancelable work, and drain already-running
uncancelable work before releasing the one database value.

This keeps parallel member execution and deterministic results. It does not add
a second cache, result language, serializer, or scheduler.

## Dependency ledger

- Datahike `a53158582dd2d8ba12e8bfc0843125d246b573c6`:
  `resource.cljc:95-168` owns scalar weight, bounded eager traversal, and the
  existing `:datahike.resource/max-result-weight` error semantics.
- Seon protocol v6 at `e9a9a793`:
  `protocol.cljc:583-600` owns the closed 1–64 member request;
  `protocol.cljc:843-862` owns ordered member and outer response shapes; and
  `ordinary-wire-value?` owns the recursive host-value rejection.
- Seon writer at `e9a9a793`:
  `writer.clj:2354-2401` progressively admits only one bounded member window,
  `2408-2446` constructs the final ordered response, and `2455-2487` records
  out-of-order physical completions before refilling.
- Native delivery at `a991307d`:
  `transport/uds.clj:162-170` retains the exact 4 MiB encoded-frame fence, and
  `331-352` separately reserves exact framed output bytes after encoding.

## Why the obvious variants are weaker

### Charge in completion order

The same request could retain different member positions depending on thread
timing. That breaks retry determinism and makes performance scheduling visible
as data semantics.

### Sum declared member maxima before execution

This is safe only by rejecting useful groups whose actual results are small.
It also forces every schema/index/pull member to predict its size and wastes the
parallel window. Per-member maxima remain useful computation limits, but their
sum is not the actual composed response.

### Encode only after all members finish

The existing exact frame fence prevents an oversized socket write but pays all
database work and retains every result before discovering failure. It cannot
stop later admission and gives no bounded partial outcome.

### Return one outer failure

Discarding completed siblings violates the settled non-fail-fast contract and
throws away useful work. A fixed per-member error preserves vector positions
and the successful prefix without inventing member IDs.

## Smallest dependency seam

Expose Datahike's existing bounded structural-weight traversal through its
ordinary host API instead of copying that algorithm into Seon. It should accept
an eager value plus remaining weight and return its exact weight or nil when it
cannot certify the value within the bound. Seon first applies the canonical
ordinary-wire predicate, then asks Datahike for weight. The wire field already
uses the Datahike resource name, so this is the existing concept rather than a
parallel protocol estimate.

The reservation is computed from ordinary response data:

1. construct the final outer response with the fixed bounded error in all
   positions;
2. certify that minimum legal response at request admission;
3. retain its weight and the weight of one placeholder at each position;
4. store physical completions at their existing vector positions;
5. advance only across the contiguous completed positions, replacing one
   placeholder and applying the structural-weight difference; and
6. retain the exact Transit frame fence because structural weight cannot prove
   encoded bytes for every scalar representation.

The member window already bounds results that finish beyond a slow earlier
position. Individual member resource limits and fair executor capacity remain
the compute/memory bounds; the outer value is composition and delivery policy.

## Resilience behavior

- A too-small outer value rejects before database acquisition or member work.
- A real member error is validated and weighed like a success. If its message
  cannot fit, the fixed bounded result-limit error replaces it.
- Aggregate exhaustion stops refill. Queued members are removed; running
  queries detach through the existing caller identity; running pull/index work
  drains truthfully.
- The writer releases the shared historical database value only after every
  physical job and Datahike logical query caller is terminal.
- Disconnect and explicit cancellation keep their existing target-request
  semantics; aggregate exhaustion does not claim rollback or introduce another
  cancellation identity.
- Final Transit encoding and session byte admission remain independent. A
  slow or exact-byte-oversized session still affects only that session.

## Acceptance evidence

- Inverted member completion yields byte-equivalent position results across at
  least 100 repeated schedules.
- A result at position 2 exhausts the bound: positions 0–1 remain successful,
  position 2 and every later position contain the fixed error, no later member
  is admitted after the decision, and all already-running work drains.
- One slow position with a full later completion window stays within the
  existing member-window and per-member resource bounds.
- Cached query hits, cold query owners, Datahike single-flight joiners, pull,
  pull-many, schema, and index-page members all charge the same ordinary member
  response contract.
- A value that is below structural weight but above 4 MiB encoded bytes still
  fails at the exact frame fence without leaking output reservations.
- Cancellation, database release, response-delivery failure, and aggregate
  exhaustion leave zero writer requests, executor jobs, Datahike query callers,
  database values, response slots, and output bytes.
