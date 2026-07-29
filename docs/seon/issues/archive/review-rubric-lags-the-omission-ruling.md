---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, review]
---

# The review hook's rubric lags the omission ruling

## Problem

The Gemini review hook flags `[:maybe X]` in function RETURN contracts as a
rubric violation ("the nil door"). The 2026-07-28 presence-not-kinds ruling
explicitly ALLOWS `[:maybe]` in in-memory function return contracts (stored
attributes stay nil-free; the bridge forces absence there). Reviews citing the
stale rubric produce false violations — e.g. `seon.cluster.loop/disposition`
`[:maybe :my.run/value]` flagged 2026-07-29 (tmp/reviews/20260729T121916.115Z.md)
is correct under the ruling.

## Owner

The review rubric/prompt used by the edit hook (bin/seon-hook Gemini prompt).
The owner ruled 2026-07-29: skills/rubrics that do not match the system design
are HIGH PRIORITY to update.

## Acceptance

The hook's rubric states the omission ruling exactly (allowed: `[:maybe]` in
in-memory return contracts; banned: stored nil, `[:maybe]` on stored
attributes), and a re-review of loop.cljc produces no `[:maybe]` false flag.

## Evidence forwarded to the compute-door lane (real finds, same review)

- `stop-error-fanout!` can block forever on `completion` if the fault graph
  already stopped (the unbounded-wait class the lane is fixing).
- `execute-work!`'s Throwable arm does a blocking `>!!` onto `error`.
- `CountedDroppingBuffer` wraps an unsynchronized LinkedList read from other
  threads via `count`/`datafy`.
- Six public flow fns lack `:malli/schema`.

## Second false positive (2026-07-29 afternoon)

The review flagged `(d/transact connection {:tx-data [...]})` in
test_support.clj as a correctness bug ("expects a vector... not a map").
REPL-falsified: datahike.api/transact accepts BOTH the arg-map and raw
vector forms — probe returned {:map-form true, :vec-form true}
(tmp/reviews/20260729T140011.539Z.md). The rubric needs Datahike's own
transact arities, not Datomic-client folklore.

## Resolution

Resolved by `4d248e8f6`. The Gemini prompt in `bin/seon-hook` now places two
source-grounded rulings ahead of the assembled skill and convention text:

- "`[:maybe]` is allowed in in-memory function RETURN contracts (stored
  attributes stay nil-free — the bridge forces absence there)." The prompt
  explicitly forbids flagging an in-memory return merely for `[:maybe]` while
  retaining the ban on stored nil and `[:maybe]` stored attributes.
- `datahike.api/transact` accepts both the argument-map form containing
  `:tx-data` and the raw vector or sequence form. The prompt explicitly
  rejects the false claim that the argument map is entity transaction data.

## Proof

- `reference-code/datahike/src/datahike/api/impl.cljc:30-48` is the dependency
  implementation for both accepted transaction forms. A focused JVM probe
  returned `{:map-form true, :vec-form true}`.
- A focused re-review of `src/seon/cluster/loop.cljc` with the corrected rubric
  classified its two in-memory `[:maybe]` return contracts as compliant. A
  re-review of `loop.cljc` now produces no `[:maybe]` flag.
- A focused re-review of `test/seon/test_support.clj` classified its
  `{:tx-data [...]}` call as compliant and produced no transact-arity flag.
- `printf '{}' | bin/seon-hook` returned `{"continue":true}`, proving the
  edited hook remains executable.
