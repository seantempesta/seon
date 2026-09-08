---
type: issue
status: resolved
severity: friction
tags: [issue, test, agent, contract]
---

# Agent-facing refusals are asserted where the contract refuses first

Found 2026-09-07 by `storage-bound-repair-2` once `bin/test` began arming the
same contract instrumentation a live cluster arms (`07394e485`). Resolved
2026-09-08 by `instrumented-gate-backlog`
([landing note](../../prds/context-generation/research/instrumented-gate-backlog-landing-2026-09-08.md)).

A test called an agent-facing function DIRECTLY with a value its declared
input contract forbids, and asserted the function's own flat refusal. Under
armed contracts the contract refuses first: the call does not run, and the
caller gets `:seon.instrument/contract-violated` — which is the ruled
behaviour (AGENTS §2.4: "a function whose declared contract fails does not
run"). The agent still never sees a throw, because the SCI kernel converts it
into a flat value on the agent's own path; it was the HOST-level assertion
that was unreachable under the dial every development cluster runs.

## The shape, decided once

It is decided by WHO the refusal is for.

**Agent-facing (`my.*`): drive the form through the kernel.** An agent never
invokes a Var — its reply is read into forms and each one crosses
`seon.sci.eval/evaluate`, which is total by construction. So the claim under
test ("a bad argument comes back as something the agent can read, never a
throw") is a claim about THAT boundary, and
`seon.test-support/agent-value` is the one helper that exercises it:

```clojure
(support/agent-value ctx "(my.run/complete 123)")
;; => the flat :seon.error value the agent reads
```

A direct-call test is kept only where the declared contract ADMITS the input
and the function's own typed refusal is the subject — for `my.run` and
`my.message` that is a blank but non-empty string, since every one of their
string members is `[:string {:min 1}]`.

**System-side: assert the typed contract refusal as the value it is.**
`test-support/refusal-data` plus `:seon.instrument/contract-violated` and
`:seon.error/diagnostic-operation` names the same function at the same
crossing. In `seon.call-preparation-test` this turned out to be a STRONGER
proof than what it replaced: the diagnostic's offending argument is the
caller's own value, unreplaced, observed one frame before the body could have
computed a boolean about it.

**A function that IS the arm reproduces boot's state.**
`seon.instrument/apply!` is called by boot on a JVM with no contracts
installed, and that is the only state in which its own
`:seon.instrument/invalid-mode` refusal is reachable. Its test calls
`instrument/remove!` first (the suite's snapshot/restore fixture puts the
worker's wrappers back), then arms and asserts the contract's refusal too.

## Where it landed

- `my.run-test/a-contract-forbidden-argument-is-a-value-the-agent-reads`
- `my.message-test/a-contract-forbidden-argument-is-a-value-the-agent-reads`
- `seon.db-test/malformed-reads-return-flat-errors` (split: the query vector
  arm keeps `seon.db`'s own refusal)
- `seon.error-test/a-missing-recurrence-limit-refuses-at-the-declared-contract`
- `seon.operator-test/cluster-address-admission-is-derived-from-schema-and-filesystem`
- `seon.call-preparation-test/a-compiled-first-party-call-is-prepared`
- `seon.instrument-test/an-invalid-core-error-mode-is-an-evidence-complete-value`
- the nine platform members `storage-bound-repair-2` fixed in `e46df126a`

`seon.test-support/agent-value` is the durable mechanism; a new agent-facing
refusal test uses it rather than restating this decision.
