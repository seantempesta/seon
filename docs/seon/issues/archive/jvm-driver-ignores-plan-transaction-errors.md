---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database, schema]
---

# Refuse execution when the run plan is not durable

## Problem

The JVM driver submits the execution-plan transaction and ignores its returned
error value. It then evaluates the forms even when no plan digest or form rows
were committed. This violates the durable-facts runtime contract and makes the
successful turn look like a six-transaction path when only five transactions
actually committed.

## Evidence

`seon.agent.driver/process-message!` calls `transact!` for
`plan-tx-data` and discards the return value before entering its eval loop
(`src/seon/agent/driver.clj:461-471`).

On the fresh `agentload0726` database at release
`596b6c1d43bd76cbf925ea288bc402d3c393cdab9fc9bc06e3309c0e91a3ca0a`,
none of these registered source attributes was installed:

```clojure
#{:seon.agent.run/plan-digest
  :seon.agent.run/forms
  :seon.agent.run.form/id
  :seon.agent.run.form/run
  :seon.agent.run.form/ordinal
  :seon.agent.run.form/source}
```

Run `m5bng2aq847g` nevertheless evaluated
`(seon.agent.lifecycle/complete "RUNG1_OK")`, closed `:completed`, and
published the reply. Pulling the run showed no plan attributes. Across the
requested `1 + 5 + 10 + 25` rung turns, the basis transaction advanced by
exactly five successful transactions per turn, not six.

## Owner

`seon.agent.driver` owns the ordered durable plan and must interpret the
`seon.db` error value. Initialization-page/schema acquisition owns making the
driver's registered attributes available in a fresh database.

## Acceptance

- A fresh reset installs the run-plan and run-form schema before the driver can
  accept work.
- The driver evaluates no form unless the complete ordered plan transaction
  returns a native transaction report rather than a flat `:seon/error`.
- A forced plan-transaction rejection terminalizes the turn/run as a flat
  visible error, publishes no success reply, and leaves no open run.
- A successful real turn has durable plan digest/form rows and the expected
  transaction boundary.

## Resolution

Resolved by `c03ff91eb`.

The plan and run-form schemas now live in portable `seon.agent.run.core`, so
fresh initialization pages install them before the JVM driver accepts work.
`process-message!` interprets the plan transaction result: a flat error closes
the turn/run, returns that error, and never calls SCI. The regression forces
the exact rejected-plan case and proves zero evaluations plus the returned
flat error.

The corrected falsifier ran before the production fix and failed in the
expected direction: the evaluation counter advanced and the returned value was
the eval result instead of the writer's flat plan error. The same focused test
passes after `c03ff91eb`.

Focused proof is green: the driver/cold-schema/HTTP checkpoint passed 18 tests
and 86 assertions; the cold CLJS bootstrap proof passed 1 test and 2
assertions. Fresh `turnmeasure0726a` then installed every plan/timing
attribute, committed plan transaction `536870936`, evaluated its one durable
form, and closed `:completed` without turn or eval errors. The corrected
conditioned waterfall is recorded in
[[../../../prds/sci-execution-runtime/research/measurements-2026-07-25#18-corrected-self-attributing-turn]].
