---
type: issue
status: resolved
severity: friction
tags: [issue, cluster, flow, agent, boot]
---

# Boot arms agents twice, and primes before the listener

## Problem

Two things do one job, and their order contradicts the contract the same
function's docstring states.

`arm-agents!` runs its own derive-and-arm over every agent in facts, which is
exactly what `armer-step` derives one line later under the boot prime. That is a
second mechanism for arming.

Worse, the duplicate runs BEFORE the routing listener is registered, and each
`arm!` ends with a mailbox prime. A message committed between an agent's prime
pass and `wake/route!` is therefore never delivered: the listener does not exist
yet, and nothing re-primes afterwards — `armer-step` arms only UNARMED agents,
and `arm!` returns the existing entry without priming. The agent parks until
some unrelated wake arrives.

The docstring already names the correct order and the code does the opposite.

## Evidence

`src/seon/cluster.clj:725-732` states the contract:

> ORDER IS THE CONTRACT. […] the armer prime comes LAST, after the listener, so
> an agent created between the prime's derivation and the listener's
> registration cannot exist.

`src/seon/cluster.clj:814-819` arms and primes every agent:

```clojure
(doseq [agent-id (sort (d/q '[:find [?id ...]
                              :where [_ :seon.cluster.agent/id ?id]]
                            @connection))]
  (cluster.agent/arm! {...}))
```

`src/seon/cluster.clj:822-829` registers `wake/route!` only afterwards, and
`src/seon/cluster.clj:832` offers the boot prime last.

`src/seon/cluster/agent.clj:415-427` (`armer-step`) is the same derivation:

```clojure
unarmed (remove #(contains? (::armed @routing) %) agents)
```

`src/seon/cluster/agent.clj:313` — `(or (armed routing agent-id) …)` — is why a
second arm never re-primes, and `src/seon/cluster/agent.clj:349` is the prime
that has already fired by then.

## Owner

`seon.cluster/arm-agents!` together with `seon.cluster.agent/armer-step`.

## Acceptance

- One derivation arms agents. Boot obtains its synchronous, published readiness
  by CALLING the armer's derivation, not by restating its query.
- Nothing primes a mailbox before `wake/route!` is registered; the order in code
  matches the order the docstring states, or the docstring changes to match a
  deliberately different and equally safe order.
- A regression commits a message for an existing agent during the arming window
  and asserts the agent answers it without any later unrelated wake.

## Resolution

Resolved in the commit that archives this note. `arm-agents!` now registers
`wake/route!` before synchronously invoking `cluster.agent/armer-step`'s one
derive-and-arm path; the duplicate agent query and asynchronous boot prime are
gone. The live-boot regression waits for the root mailbox's arm prime, commits
a message before `arm!` returns, and observes the model call plus the run's
exact `:seon.db/trigger` without issuing another wake.
