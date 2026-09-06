---
type: issue
status: resolved
severity: friction
tags: [issue, sci, runtime, test, wave/context-fixes]
---

# Multiple supplied defaults remove the ordinary database-omitted call

## Problem

`(my.plan/plan "juniper")` fails with an arity error even though the database
supplier is installed and the caller explicitly provided the agent id. The
all-or-nothing planner derives only the declared two-argument call and the
zero-argument call once both argument types have supplied defaults.

The earlier explanation in this note, that the focused fixture omitted the
database supplied-default row, was falsified. Its added fixture row did not
repair the failure. Neither SCI call wrapping nor `:catn` shape indexing was
the cause.

## Evidence

On 2026-09-06, an MCP JVM evaluation against the immutable database value of
`lab-run-inspection` returned three admitted defaults, no supplier refusals,
and this `seon.call-preparation/plan-for` result for `"my.plan/plan"`:

```clojure
{:seon.call-preparation/slots
 [{:seon.fn.argument/index 0 :seon.call-preparation/key :seon.db/db}
  {:seon.fn.argument/index 1
   :seon.call-preparation/key :seon.cluster.agent/id}]
 :seon.call-preparation/by-supplied-count {0 :both-slots 2 :no-inserts}}
```

The abbreviated values describe the observed insertion lists. Supplied count
one was absent. `3ef28735a` introduced the current-agent supplier alongside
help situation; the planner's older all-or-nothing rule then removed the
ordinary database-omitted call.

Baseline `bin/test seon.call-preparation-test`, run `run.wN3SRC`, reproduced
four failures in the acquired-context regression, both pooled and in isolated
confirmation: missing count-one insertion, arity exception, absent expected
error value, and disagreement with the explicit database call. The default
row, exact target fingerprint, and hook callee identity assertions passed.

After the repair, `bin/test seon.call-preparation-test`, run `run.yS0Lnc`,
passed 16 tests and 146 assertions with zero failures and errors.

The fresh isolated root `tmp/juniper-context-live`, cluster `juniper-context`,
forked published digest
`b0d3b78a513941062d1bad0c1b7f5e4d47efc8555928a7bea58042f1b511c8b4`.
A generation-aware candidate fork of its actual Juniper context evaluated
all four ordinary call shapes under a 10-second SCI limit: explicit agent,
both omitted, explicit database, and both explicit. Every result identified
`"juniper"`, with no evaluation error or interruption; the complete MCP call
took 1251 ms. The
[retained probe](../../../prds/sci-execution-runtime/research/call_preparation_live_probe_2026_09_06.clj)
reproduces the same bounded evaluation. This proves SCI evaluation of the
published program, not model turn settlement or browser rendering.

## Owner

`src/seon/call_preparation.clj` matches a previously unsupported shorter call
against its ordered declared argument schemas, carrying compiled validators
on the existing per-arity plan. Only a unique placement supplies missing
values. It retains at most two paths per position/count pair, enough to prove
ambiguity without enumerating the subset space. No supplier runs while
alternatives are considered. The acquired schema projection travels on the
existing snapshot; canonical shape rows remain the contract authority.

The owner's explicit database-omission and caller-wins instruction amends the
older restriction in the runtime plan README. Both defaults remain installed.
Existing full arities and leading-database dispatch retain precedence.

## Acceptance

- The acquired-context regression reaches the same result with an omitted or
  explicit database while preserving an explicit agent id.
- A unique partial omission works when an explicit trailing value remains.
- Repeated supplied types refuse ambiguous placement before entering the body.
- Both defaults may still be omitted together in a scoped agent environment.
- A fresh published cluster demonstrates the ordinary call through SCI.
- No renderer database workaround, source rewrite, or host-metadata fallback.
