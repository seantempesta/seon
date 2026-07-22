---
type: issue
status: closed
tags: [issue, agent, architecture]
---

# Host preflight candidate ranking crashes on unresolved symbols; base parity gap underneath

## Corrected root cause (2026-07-22, supersedes the first write of this note)

The original title blamed instrument rejection. An independent lane
falsified that: host-tier malli instrumentation WORKS — a registered
wrong-arg call (`seon.ai.tokens/estimate-chars "bad"`) returns the
structural `malli-instrument-input` envelope per-eval and a
good/bad/good batch continues correctly.

The actual crash: for a QUALIFIED UNRESOLVED symbol,
`seon.host.preflight/candidate-names` (preflight.clj:152) returns a
`PersistentHashSet`, `preflight!` (preflight.clj:243) hands it to
`seon.repl.parse.repair/rank-candidates` (repair.cljc:111) whose
`distinct` path does `nth` → `nth not supported on this type:
PersistentHashSet`, escaping as an invocation-level error that closes
the run `:error`. With the host-tier dial ON, any turn whose reply
mentions an unresolved qualified symbol dies.

## Live evidence (dial ON, default cluster, HEAD 47e13c47)

`(seon.db/entity 1 2 3)`, `(my.blob/get "wrong-shape")`,
`(seon.db/entity :not-a-db "x")` all → the nth crash at invocation
level (empty results). Probe agent AND root turns closed `:error`.
Dial restored OFF the same hour; converged; root 200.

## Why those probes were unresolved at all — the SECOND finding

`seon.db` and `my.blob` are not resolvable agent surface on the host
base (host log: base-loaded=166/172 base-failed=6 base-excluded=112).
Whether via base loading or capability registration, the W5 cutover
requires the full agent-facing surface (`my.*`, `seon.db`, …) resolved
on the host tier — otherwise every agent's ordinary database/blob call
becomes an unresolved-symbol preflight miss. This is a DESIGN-owned
parity unit, sequenced into W5-0's retirement preflight; it is NOT part
of the crash fix.

## CLOSED (2026-07-22)

Fixed `16a040e6` (candidate-names returns a sorted vector; regressions:
red-before/green-after suggestion ordering, good/unresolved/good
containment, instrument-parity envelope; writer 375/2839). LIVE-PROVEN
the same day under the dial: unresolved symbol and wrong-typed call
both return per-eval error values, good/bad/good batch = 2/error/4, and
a real DeepSeek drive ran 8 turns all `:done` where pre-fix turn 1
died. The base-parity finding moved to its own issue:
`docs/seon/issues/host-base-agent-surface-parity.md` (q34, W5-0).

## Fix lane (historical)

Sol lane (resumed thread `019f8a26-f97c…`): candidate-names returns an
ordered vector at the producer; rank-candidates validation strengthened
to reject non-sequential input loudly; regressions (unresolved-symbol
suggestion red→green, good/unresolved/good batch containment,
instrument-parity envelope).

## Acceptance

- Unresolved qualified symbol on the live host tier → per-eval error
  value with did-you-mean suggestions; batch continues; run survives.
- Registered wrong-arg call → per-eval structural envelope (regression
  encoded).
- W5-0 preflight drive includes BOTH scenarios plus the base-parity
  census (which agent-facing namespaces resolve on the host base).
