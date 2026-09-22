---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, flow, errors, swallowed-error]
---

# Errors raised in a proc's stop transition are dropped on a closed error channel

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D2, source).** Flow's `stop` sends the
stop command and at once closes `error-chan` and `report-chan`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`,
core.async `dc35f3e`). Each proc handles `::flow/stop` afterwards on its own thread.
A throw there reaches the outer catch at `impl.clj:317-320`, whose `>!!` onto the
closed channel returns false: the error is gone. Every Seon stop transition does
fallible work: listener release, channel close, completion delivery
(`src/seon/schedule.clj:809-813`, `flow.clj:527`, `flow.clj:1017`,
`turn.clj` turn-step stop, `render/web.clj` render-step stop).

**Wanted.** The failure is committed on the proc's own thread before Flow's channel can
drop it: `var-process` wraps the transition arity and calls the one fault route
(audit §3.1). Regression: a stop transition that throws leaves a durable fault.

**Owner.** `src/seon/flow.clj` `var-process`, after one-error-route step 0. Audit §5 step 2.
