---
type: research
status: active
tags: [operator, adoption, bounded-execution, performance]
---

# Adoption progress and phase cost — 2026-09-17

Bounded assignment: preserve default pid 94566; do not restart, stop, reset,
or refork it. Foreign edits and sessions remain untouched. Read AGENTS.md
sections 0–7, the flow architecture skill and the named failure evidence.
The sibling reset/refork issue was read end to end. The 170704 ms baseline
log failed during reload; it is not successful adoption evidence.

## Dependency ledger

- Clojure `reference-code/clojure/src/clj/clojure/core/server.clj:194`:
  prepl emits structured output events while evaluation runs and one terminal
  return. A socket client's exit is not evaluation cancellation.
- `resources/seon/operator/state.clj`: the shared Babashka/JVM lifecycle lock
  owns the actual file descriptor and transition completion. Progress extends
  silence observation; it never releases custody.
- `reference-code/datahike/src/datahike/db/transaction.cljc:1206`:
  final-report validation sees expanded and effective transaction results.
  Performance changes must preserve this writer authority.
- `reference-code/datahike/src/datahike/writer.cljc:136`: one serial processing
  loop owns a connection's transactions.
- `src/seon/cluster.clj:2272`: adoption owns schema reconciliation, program
  reconciliation, reload, instrumentation, SCI acquisition and its final stamp.

## Measurements so far

| Observation | Milliseconds | Outcome |
|---|---:|---|
| Historical lifecycle hold, pid 94902 | 180027 | Refused own holder |
| Historical lifecycle hold, pid 97908 | 180047 | Refused own holder |
| Historical init, pid 58066 | 170704 | Reload failure, not convergence |
| Live effective silence configuration | 30000 | Read from default's database |

First iteration: `bin/test-fast --paths resources/seon/operator/state.clj
script/seon/fresh_operator.clj test/seon/adoption_margin_test.clj --
seon.adoption-margin-test`: 1 test, 7 assertions, zero failures/errors.
This is an armed fast iteration, not the orchestrator's cold/platform proof.

A full JSON thread dump and a 60-second JFR recording observed the pre-existing
publication queue on default. No interpretation of thread silence as successful
adoption is made. Final phase and literal-duration measurements follow below.
