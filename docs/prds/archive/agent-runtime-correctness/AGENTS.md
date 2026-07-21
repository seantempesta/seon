---
type: orchestrator
status: active
tags: [orchestrator, prd, agent, flow]
---

# Agent runtime correctness — working context

This PRD owns the boundary from one model reply through parsing, ordered eval,
durable evidence, plan coordination, retry, interruption, and process
containment. Read [[roadmap]], architecture agent-runtime/observability/context,
and the closest runtime source authorities before research or code.

Use `data-oriented-clojure` before the plan, `clojurescript` for self-host and
Promise behavior, `clojure-testing` for every async regression, and `datahike`
for persisted runs/turns/evals/plans/errors. Begin with exact dependency source
and versions/SHAs for ClojureScript self-host/analyzer, Malli instrumentation,
Node process/worker/abort facilities, provider SDK cancellation, and every
planning/runtime mechanism in scope. Probe assumptions in the live default
REPL before editing.

Raw model replies are evidence and are never regex-rewritten to look correct.
Every complete parsed form is attempted in order; only real executions produce
results. Agent mistakes become `:seon/error` values and never wedge the loop.
Core faults follow the one error policy and admission/readiness boundary.

Strengthen the existing turn/eval/plan/retry/result mechanisms in place. Do not
add a second planner, reply sanitizer, eval runner, result registry, retry loop,
compatibility namespace, or mutable lifecycle authority. Hard containment must
be measured at a real process boundary and must preserve the database receipt/
recovery contract.

Research belongs in `research/`; current source/gap/order and graduation proof
belong in [[roadmap]]. Paid model calls and ACME coordination are outside the
initial source audit.
