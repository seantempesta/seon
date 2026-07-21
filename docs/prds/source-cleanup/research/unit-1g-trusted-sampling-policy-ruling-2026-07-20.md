---
type: research
status: complete
tags: [research, runtime, config, rendering]
---

# Unit 1G trusted sampling-policy ruling (2026-07-20)

## Decision

A serving peer must not treat the request's
`:seon.render.value/effective-limits` as authority. A syntactically valid
parent frame can otherwise forge larger limits and make the Bun child or JVM
host perform work forbidden by the database-owned cluster policy.

The smallest one-mechanism correction is to retain one immutable trusted
maximum-policy value **with each retained eval slot**, derived from the same
immutable database value and complete configuration that produced that eval.
Before accessing the raw retained value, descending a path, or invoking
`seon.render.value/drill-value`, each serving peer compares every requested
effective-limit component with that slot's trusted maximum. Equal or narrower
limits pass through byte-for-byte. A wider component is a bounded correlated
refusal; the peer never silently reclamps it.

This is not a new configuration cache. The retained policy is generation data
belonging to the existing process-local eval slot, just like its admitted live
value. Eviction, session close, or process retirement removes the value,
policy, and basis together.

No user decision is required. This follows the already-settled laws that the
configuration singleton is the hard maximum for one database value, an
operation may only narrow it, and the serving runtime independently repeats
admission before work.

## Exact policy and basis law

The trusted value has exactly the seven fields of
`:seon.render.value/effective-limits`:

- `:seon.config.render/value-max-path-segments`;
- `:seon.config.render/value-max-path-bytes`;
- `:seon.config.render/value-max-realized-items`;
- `:seon.config.render/value-max-depth`;
- `:seon.config.render/value-max-string`;
- `:seon.config.render/value-shape-sample`; and
- `:seon.render.value/page-size`, derived from the singleton's
  `:seon.config.render/value-max-items`.

The comparison is one portable pure predicate in `seon.render.value`, not one
implementation per host. It requires the exact closed effective-limit shape
and returns true only when every requested number is less than or equal to its
trusted counterpart. Existing request admission remains responsible for
positive integers, safe offset arithmetic, path grammar, and
`offset + page-size <= value-max-realized-items`.

The policy's basis is the invocation's explicit `:seon.db/db`, not a later
current head, process startup state, an environment value, or whichever
configuration was most recently observed. Configuration and eval input are
therefore one immutable operation generation. A config transaction after eval
A and before eval B affects B; A retains its original trusted maximum until
the ordinary retained-slot lifecycle removes it.

That behavior is required for basis consistency and byte identity. Making a
later configuration transaction retroactively revoke or widen already-created
eval slots would require a different product policy plus a committed-feed
design. Unit 1G does not introduce such a listener, mutable refresh cache, or
standing configuration census.

## Bun ownership

`src/seon/execution.cljs` already acquires the complete program and decoded
configuration together in `prepare-eval-program!` through one
`db/execute-many` request against the invocation's `:seon.db/db`.
`src/seon/config.cljs` already owns
`effective-value-drill-limits`; calling it with that complete configuration
and no operation override produces the trusted maximum without duplicating a
default or normalization rule.

The existing Bun retained-result owner in `src/seon/eval.cljs` (or the same
child state immediately adjacent to it if the slot API must remain private)
must associate these three facts atomically for every managed eval id:

- the admitted live value;
- the trusted maximum effective limits; and
- the immutable database value or its basis transaction used to derive them.

The sampling path in `src/seon/execution.cljs` first resolves only the slot's
trusted metadata. It compares the incoming request with that policy before
`eval/lookup-result`, before reading the raw `result/<id>` var, and before the
portable drill producer. The existing oldest-first cap prunes policy metadata
with the same eval id; it must not leave a policy-only or value-only slot.

The parent in `src/seon/execution/host.cljs` still performs its own admission.
That check reduces avoidable traffic but cannot replace the child check and
does not make request-carried effective limits trusted.

## JVM ownership

`src/seon/host/context.clj` already owns the retained writer session and
explicit-database protocol calls. Add the narrow configuration acquisition at
that owner: query the complete `:seon.config` singleton at the invocation's
supplied `:seon.db/db`, not at `resolve-head!`. The database singleton must
contain all seven source attributes needed by the effective-limit map.

The JVM must not invent fallback numbers. Defaults remain owned by
`seon.config` and are already reconciled into the complete database singleton.
Missing, malformed, partial, duplicate, or failed configuration acquisition
is a core failure before SCI eval and before live-value retention.

`src/seon/host.clj` passes that acquired trusted policy into the existing
`eval-batch-result` retention boundary. `retain-live-value!` stores one slot
entry containing admitted value, policy, and basis. `serve-value-sample!`
reads and validates the slot metadata before exposing its value to
`render.value/drill-value`.

This configuration acquisition is separate from the host's committed schema
projection only in meaning, not in database authority: both use the existing
writer protocol and explicit immutable database values. The schema projection
may refresh after committed program changes; an eval slot's trusted sampling
policy does not refresh because it describes that eval's own generation.

## Failure semantics

The following outcomes are distinct and fail closed:

- A closed request whose effective limits exceed the retained trusted maximum
  returns one bounded, correlated input/agent refusal. It performs zero raw
  value lookup, path descent, collection touch, or producer call. It does not
  retire the peer and does not silently change the requested limits.
- A malformed outer protocol frame remains an execution-protocol fault under
  the existing peer-settlement rules.
- A retained eval entry with missing or malformed policy metadata is a
  bounded core/unavailable failure and performs zero value work.
- An unavailable, evicted, prior-process, or retired slot keeps Unit 1G's
  honest unavailable/recompute result. Persisted `result-edn` never becomes a
  substitute live value.
- A JVM configuration read or validation failure refuses the invocation before
  SCI execution and retention. It never falls back to source defaults,
  environment variables, startup arguments, or a previous eval's policy.

Failures remain ordinary closed values. No exception escapes into an agent
loop, and no rejected request can be converted into success by reclamping.

## Dependency ledger and maintained call sites

| Mechanism | Maintained source | Contract consumed |
|---|---|---|
| Configuration authority | `src/seon/config.cljs:1177-1238`; [[value-drill-budget-config-boundary-2026-07-20]] | The decoded singleton is the maximum for one database value; `effective-value-drill-limits` monotonically narrows optional operation policy and owns all defaults. |
| Public drill shapes and work | `src/seon/render/value.cljc:124-161,1199-1257,1631-1692` | One closed effective-limit map, one total request admission predicate, and one drill producer own both runtimes. The new componentwise policy predicate belongs here. |
| Bun configuration acquisition | `src/seon/execution.cljs:704-735`; `src/seon/execution/runtime.cljs:576-605` | Program rows and complete configuration are acquired against one invocation database value before eval. |
| Bun retained values | `src/seon/eval.cljs:1056-1150,1520-1570,4470-4545`; `src/seon/execution.cljs:917-973` | Managed eval ids already have a bounded process-local value lifecycle. Policy and basis extend that same slot rather than creating another cache. |
| Parent transport | `src/seon/execution/host.cljs:850-1010`; [[unit-1g-value-sampling-transport-implementation-readiness-2026-07-20]] | Parent admission and correlation remain mandatory but are not serving-runtime authority. |
| JVM database boundary | `src/seon/host/context.clj:179-317,1032-1110`; `src/seon/host.clj:482-610` | One retained writer session can query an explicit immutable database value; eval recording and live-value retention already meet at one host boundary. |
| JVM retained values | `src/seon/host.clj:396-421,248-286` | The bounded oldest-first session map and value-sample handler are the one slot and serving mechanisms to strengthen. |
| Datahike database values | `reference-code/datahike/src/datahike/db.cljc`; `docs/seon/architecture/data-model.md` | Reads over one immutable database value are basis-consistent; a later head must not be silently substituted for an operation input. |
| Malli closed schemas | `reference-code/malli/src/malli/core.cljc:1223-1310,2635-2641` | Registered closed shapes validate the complete policy and reject missing or unknown fields; the comparison does not recreate schema validation by hand. |

Selected maintained versions remain Datahike `0.7.1635` and Malli `0.20.0`
from the repository dependency ledger. No dependency addition is required.

## Acceptance tests

1. For each of the seven effective-limit fields, send a Transit-valid sample
   request that is one greater than the retained trusted maximum. On Bun and
   JVM, assert one correlated bounded refusal and zero raw-value lookup,
   producer calls, path descent, and collection touches.
2. Send equal limits and independently narrowed limits. Assert the serving
   peer passes the exact request map unchanged to the one producer and the
   result satisfies that same request's bounds.
3. Produce eval A at database/config basis A, commit a changed policy, then
   produce eval B at basis B. Assert A and B retain and enforce their own
   policies without cross-generation replacement.
4. Evict an eval and close a session. Assert value, policy, and basis disappear
   together and a later sample returns the existing honest unavailable result.
5. In Bun tests, assert program rows and configuration came from the same
   `execute-many` database and that no sample-frame field becomes trusted
   policy.
6. In JVM writer tests, assert the config query names the invocation's
   `:seon.db/db`, not current head. Missing, partial, malformed, duplicate, or
   failed singleton acquisition must prevent SCI eval and retention.
7. Bypass the parent helper and inject a forged but otherwise valid sample
   frame into each peer. This must fail identically, proving the independent
   serving-runtime belt rather than only the parent check.
8. Retain a counter-backed million-entry or infinite value behind the eval id.
   A rejected widening request must leave its counter at zero; an admitted
   request remains within Unit 1F's `offset + page-size + 1` touch law.

Expected focused owners are `test/seon/execution_test.cljs`,
`test/seon/execution/host_test.cljs`,
`test/seon/host_conformance_writer_test.clj`, and the existing focused
host-context writer test when direct explicit-database acquisition needs its
lower-level falsifier.

## Rejected alternatives

- Trusting request-carried effective limits defeats the independent serving
  belt and makes the work-bound claim false under forged IPC.
- Reclamping at the peer returns different bytes for what purports to be the
  same admitted request and hides a broken caller.
- A process-startup policy becomes stale after config apply and gives every
  eval the wrong database basis.
- Querying current head during sampling substitutes another operation
  generation and breaks per-eval basis consistency.
- Copying defaults into JVM code creates a second configuration authority.
- A new ambient value, atom, configuration listener, or per-peer cache
  duplicates the existing singleton and retained-slot mechanisms.
