---
type: issue
status: open
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
  returns `:seon.db/ok? true`.
- A forced plan-transaction rejection terminalizes the turn/run as a flat
  visible error, publishes no success reply, and leaves no open run.
- A successful real turn has durable plan digest/form rows and the expected
  transaction boundary.
