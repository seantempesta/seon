---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, process, config, testing]
---

# Operator waits used total-time and literal clocks

## Problem

Fresh cluster readiness used one 30-second total-operation deadline even
though the child already published every boot phase over its ready socket.
Legitimate progress did not renew the deadline, so a slow but healthy boot
could be abandoned. Prepl connection and response, operator probing,
exact-process shutdown, and detached-child adoption used four more private
clock constants or a Python literal instead of one declared decision.

## Evidence

Before the repair, `await-advertisement!` raced one readiness future against
process exit with `advertisement-wait-ms`; phase lines were printed inside the
future but did not affect its flat `.get` timeout. The detached launcher polled
an adoption file every 10 milliseconds against a hard-coded 30-second Python
deadline.

The other clocks represented these boundaries:

- `prepl-connect-ms` waited for foreign TCP connection establishment.
- `prepl-eval-ms` waited for the next prepl value or socket EOF.
- `operator-probe-ms` was the same prepl event boundary used for observation.
- `shutdown-grace-ms` waited for `ProcessHandle.onExit` after a signal.
- the detached Python gate waited for operator adoption before `exec`.

## Owner

`:seon.config.operator/event-silence-backstop-ms`, declared in
`resources/seon/schemas/seon.config.operator.edn` and decided with provenance
in `config/default.edn`, is the one last-resort operator silence backstop.
`seon.fresh-operator` owns each event boundary and names a firing as an error.

## Acceptance

- Boot phase, failure, EOF, exact-process exit, and READY are explicit events.
- Every phase event renews the silence interval; total boot time is unbounded.
- A silent phase fails loudly with its name, elapsed silence, error kind, and
  governing config attribute.
- TCP connection/response and exact-process exit retain only the same declared
  silence backstop.
- Detached adoption is a socket event, not filesystem polling.
- A slow simulated boot whose total duration exceeds one interval reaches
  READY, a silent simulation fails by name, and an isolated ordinary
  `bin/seon start default` reaches READY.

## Resolution

Resolved by the commit containing this note.

`script/seon/fresh_operator.clj:1805-1908` now funnels ready-socket messages
and exact process exit into one queue. `:phase` renews the `.poll` interval;
`:failure`, `:closed`, `:exit`, and `:ready` are hard events. A silent phase
returns `:seon.fresh-operator/boot-phase-silent` and prints the phase and
silence duration.

`script/seon/fresh_operator.clj:1628-1730` replaces the adoption file with a
loopback socket connection raced against exact child exit. The operator writes
the process claim before acknowledging that connection, so the detached child
cannot `exec` before its identity is durable.

`script/seon/fresh_operator.clj:1410-1475` uses the declared decision for TCP
connect and each prepl read; socket timeouts name either `prepl-connection` or
`prepl-response`. `script/seon/fresh_operator.clj:1738-1772` waits directly on
`ProcessHandle.onExit`; the backstop exists only to escalate an unresponsive
foreign process and reports loudly before doing so.

The recurring simulations passed four focused operator tests with 13
assertions. The slow case took more than 400 ms while making progress every
150 ms under a 250 ms silence decision. The silent case reported:

```text
! operator event silence backstop fired: cluster boot phase namespaces was silent for 250 ms
Cluster boot phase namespaces went silent for 250 ms.
```

An isolated ordinary start published all phases and ended with:

```text
● default boot: web
● default              http://127.0.0.1:59156  prepl=59149  log=.../seon.log
```
