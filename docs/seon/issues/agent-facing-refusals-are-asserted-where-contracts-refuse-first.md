---
type: defect
status: open
severity: friction
tags: [issue, test, agent]
---

# Agent-facing refusals are asserted where the contract refuses first

Found 2026-09-07 by `storage-bound-repair-2` once `bin/test` began arming the
same contract instrumentation a live cluster arms (`07394e485`).

`my.message-test/a-bad-argument-is-an-error-value-never-a-throw` calls
`my.message/send` and `my.message/decline` DIRECTLY with values their declared
input contracts forbid, and asserts the function's own flat refusal. Under
armed contracts the contract refuses first: the call does not run, and the
caller gets `:seon.instrument/contract-violated` — which is the ruled
behaviour (AGENTS §2.4: "a function whose declared contract fails does not
run"). The agent still never sees a throw, because the SCI kernel converts it
into a flat value on the agent's own path; it is the HOST-level assertion that
is unreachable under the dial every development cluster runs.

Two things were wrong in that file and only one is fixed:

- **Fixed** (`storage-bound-repair-2`): a stray closing paren ended the
  `deftest` early, so three `doseq` blocks of assertions were TOP-LEVEL forms
  that ran at namespace load. Under armed contracts they threw during
  compilation, so `seon.sci.eval/install-first-party-namespaces!` could not
  load the namespace and EVERY fixture acquiring a SCI ctx in a worker that
  had not already loaded it died with
  `:seon.sci.eval/namespace-unloadable`. Assertions outside a `deftest` are
  not coverage either way.
- **Open**: the assertions themselves. They must exercise the boundary the
  agent actually calls — a form through the SCI kernel — or assert the typed
  contract refusal as the value it is.

## To do

Decide the shape once for every agent-facing refusal test (there is more than
one): drive the call through the kernel so the flat value is the real one an
agent reads, and keep the direct-call test only where the declared contract
admits the input and the function's own typed refusal is the subject.
