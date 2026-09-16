---
type: issue
status: open
severity: friction
tags: [issue, mcp, runtime]
---

# Default component probe timed out after development adoption

Turn-test-reds, 2026-09-16 04:50 UTC: the final read-only MCP runtime-status
probe returned health and Flow unknown with `Read timed out` after 30 seconds.
It selected default PID 53378, start 04:10:35Z, PREPL 61867. No cause or
adoption convergence is inferred. This lane never stopped, reforked, or
restarted default; its completed isolated in-process test proofs do not
establish default health. See the
[continuation landing](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md).

Attempt/evaluation facts, 2026-09-16: default changed from PID 69622 to 7595
(start 01:36:02Z, PREPL 51919). After loading the canonical test namespace,
the MCP JVM call to `seon.test/run` for
`seon.data-shapes-test/attempt-usage-is-queryable` timed out at 20,000 ms.
Its completion is unknown and it was not repeated. Runtime status then
reported health/Flow unknown with `Read timed out`; operator status reported
the process alive. No default lifecycle operation or fallback transport was
used. See the [lane landing](../../prds/steward-platform/research/attempt-and-eval-facts-2026-09-16.md).

P1, 2026-09-15: a later repeat of the empty-domain-transaction report probe
in JVM session `p1-ambient-state` timed out at 20,000 ms on default PID 69622 /
PREPL 55914. Its outcome is unknown and it was not retried. The earlier
completed report probe verified both carried values and successful root-id
reads. Runtime status afterward returned `ClassCastException` from
`clojure.lang.RT/dissoc`: MapEntry cannot be cast to IPersistentMap. This
establishes unavailable health observation, not an adoption cause. No default
lifecycle operation or alternate transport was used. See
[the P1 landing](../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).

Refusal-grammar, 2026-09-15: default PID 69622 / PREPL 55914 initially
answered runtime status with all three plumbing procs replying. The read-only
JVM reader/cause-chain probe returned in 1,016 ms. Loading the saved expanded
probe in session `refusal-grammar` then timed out at 20,000 ms; completion is
unknown. No transaction, shared SCI evaluation, or lifecycle operation was
requested. This does not establish an adoption cause. See
[the refusal-grammar landing](../../prds/context-generation/research/refusal-grammar-2026-09-15.md).

Supplied-keys lane, 2026-09-15: default PID 23729 / PREPL 54412 initially
answered runtime status and JVM arithmetic. The run-9 shown-text query
returned in 10,574 ms. A subsequent documentation query timed out at
20,000 ms; the evidence capture load-file in session `supplied-keys` timed
out at 60,000 ms. Its output file was absent immediately afterward;
completion remains unknown. `logs/current-source-failure.log` also said
`Publication did not finish within its declared bound.` No cause is
inferred and no default lifecycle operation was performed.

Follow-up in the same lane: JVM arithmetic still returned in 1 ms. With
the database projection explicitly handed through
`seon.schema/call-with-projection`, the documentation probe returned in
11,269 ms and the final audit capture in 12,519 ms. The new supplied-key
projection and refusal text were observed. This proves those JVM paths;
it does not establish full adoption convergence or explain the timeouts.

Core-functions follow-up, 2026-09-14: `runtime_status` again reported health
and Flow unknown with `Read timed out` for default PID 23557 / PREPL 49971.
The read-only retained SCI referral probe returned in 2 ms. No lifecycle
operation occurred; the small probe proves access, not Flow health.

Debug-turns, 2026-09-14: default PID 23557 / PREPL 49971 returned
health/Flow unknown with `Read timed out`. The subsequent MCP JVM query
returned a live connection and 187 turn identities in 7292 ms. A selected
Juniper turn pull also answered. No lifecycle operation occurred; this
establishes JVM query availability, not Flow health.

Dir-own-fns, 2026-09-10: default PID 23557 / PREPL 49971 again returned
health/Flow unknown with `Read timed out`. JVM `(+ 1 1)` returned 2 in
0 ms and `(boolean (seon.operator/connection "default"))` returned true
in 1 ms. The required two-function `seon.db/pull` probe timed out at
20,000 ms in two separate MCP sessions; supplying a database-derived
schema projection also timed out at 20,000 ms. No cause or row contents
are inferred from those timeouts. No default lifecycle operation occurred.

REPL display, 2026-09-10: initial MCP status selected default PID 23557,
PREPL 49971, and returned health/Flow unknown with `Read timed out`.
The subsequent JVM `(+ 1 2)` returned 3 in 1 ms; the direct value-renderer
probe returned in 1041 ms. No cause or Flow health is inferred. Default
was not restarted, reforked, or stopped.

The display lane's later hook publication `52e6bf93-61eb-4bf9-9d5a-9d5271651f27`
returned operator exit 124; `logs/current-source-failure.log` said
`Publication did not finish within its declared bound.` Explicit adoptions
also reached instrumentation but refused when source changed during the
operation. These are observed publication boundaries, not a diagnosis of
the initial status timeout. The live pages and direct JVM probes continued
to answer; final adoption evidence is in the display landing note.

Context-nits, 2026-09-09: initial MCP status selected default PID 83040,
PREPL 60374, but health and Flow returned unknown with `Read timed out`.
JVM evaluation returned 3 from `(+ 1 2)` in 0 ms, and the runtime/notes
pull returned in 1128 ms. No cause or Flow health is inferred.

Data lane, 2026-09-09: MCP status selected default PID 92059, PREPL 53086,
but health and Flow returned `unknown` with `Read timed out`. A subsequent
JVM `(+ 1 1)` returned 2 in 1 ms; the immutable plan/schema query returned
in 3 ms. No cause or Flow health is inferred. Default was not restarted.

Transact-feedback, 2026-09-09: the initial MCP `runtime_status` selected
default PID 37586, PREPL 61094, and reported health/flow `unknown` with
`Read timed out`. Descriptor-only `bin/seon status` reported that process
alive. No cause is inferred and no default lifecycle operation was performed;
the assigned scratch root supplies this lane's live verification.

Observed by record-render on 2026-09-08. MCP JVM mode on default accepted
`(+ 1 1)`, but a later component probe timed out after 15000 ms. It called
`seon.operator/connection`, transacted agent `record-render-components`,
then called `my.plan/add!`, `current`, and `steps`. The requested step id was
`record-render-components/one`. Completion of those writes is unknown;
do not repeat them as if a timeout proved absence.

The immediately preceding adoption reached JVM instrumentation and then
refused because source changed during adoption. That sequence is evidence,
not proof of the timeout's cause. Concurrent isolated test confirmation
workers were also running. No fallback transport was used.

See the [landing evidence](../../prds/context-generation/research/record-render-landing-2026-09-08.md).

Turn-cut observed the same boundary at 20:52 UTC on 2026-09-08:
`runtime_status` with no arguments selected default, pid 36758, but returned
runtime health and flow as `unknown` with `Read timed out`. The publication
log independently said `Publication did not finish within its declared bound.`
Earlier ordinary virtual turns had completed, including a contracted function
installed by one agent and called by another. Those completed observations do
not establish health after this timeout. No fallback transport was used.

Components lane, 2026-09-08: initial `runtime_status` selected default PID
91455, PREPL 65479, and returned health/flow `unknown` with `Read timed out`.
A subsequent MCP JVM `(+ 1 1)` returned 2 in 3 ms. The process is reachable;
that arithmetic result does not establish Flow health. No default lifecycle
operation was performed.

Components checkpoint `e96001a7c`: development adoption reached JVM
instrumentation and returned `:seon.instrument/registration-failed`, with
Malli reporting `:malli.core/invalid-schema` for `:seon.render/cache`.
The subsequent default debug navigation exceeded Playwright's 30-second
bound. Page-feed then landed `985a830b5`, including the cache schema
alias. No default restart or refork was attempted; final source adoption
is the next authorized convergence boundary. Scoped component gates and
scratch adoption remain independent of this failure.

Final components verification after `8b48a7c47`: default adoption completed
at `6aa09fd1-1bbd-52ab-a9ae-a4a290e2cad6`. Juniper reseeding returned in
96 ms; a following record query returned in 268 ms, and Playwright loaded
and captured the debug page successfully. The component checkpoint's
cache-schema failure is not the final observed state. This does not claim
that every earlier timeout reported by other owners has been resolved.

## Message-render follow-up, 2026-09-08

After default adoption of components commit `03d3bfb1b` reported convergence
(source `6aa0cf6a-730f-5ba3-960e-f00e731bc9f6`), the JVM call to
`seon.cluster.message/render-inbox-ai` returned the new per-message source,
but the browser retained the old single inbox form and old HTML without reply
expressions. A SCI-mode MCP call of `(seon.cluster.message/render-inbox-ai [])`
timed out at 10,000 ms. JVM-mode MCP still answered. This does not establish
whether acquisition or retained rendering is the cause; default was not
restarted or reforked. See the components landing note for the cache probe.

A raw-data probe found `:seon.render/cache` on the cluster's existing projection
state. Resetting that disposable atom to `{}` returned in 2 ms; no database
facts or process lifecycle were changed. The next default screenshot showed
eight `my.message/read` results and both root-addressed reply expressions.
Thus retained rendering hid the adopted code. The precise missing invalidation
edge is not established. Calls through `seon.render/shared-cache` timed out
both with and without projection binding, while reading and resetting the
same cache atom directly answered immediately. These accessor/SCI timeouts
remain a distinct unresolved observation, not proof the cache reset hung.

## Re-verified at HEAD (2026-09-15)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source; concurrent working-tree edits excluded).

MCP `runtime_status` with root `/Users/sean/src/seon`, cluster `default` returned PID 23729, health `unknown`, Flow `unknown`, error `Read timed out`. The supported JVM probe `(+ 1 1)` in session `triage-b` then returned 2 in 2 ms. `bin/seon status` reported the same PID alive. This confirms unavailable health observation, not an adoption cause or a blocked agent. Severity is friction; the original component-write timeout was not repeated because its completion is unknown.

surface: other

## Slice C triage observation — 2026-09-15

Read-only `mcp__seon__runtime_status` for root `/Users/sean/src/seon`,
cluster `default`, reported PID 23729 / PREPL 54412 alive but runtime health
and Flow unknown with `Read timed out`. Subsequent MCP JVM `(+ 1 1)`
returned 2 in 2 ms. This proves access only; no cause, Flow health, or exact
source convergence is inferred. No lifecycle operation was performed. The
[dated slice C landing](../../prds/context-generation/research/issue-triage-C-2026-09-15.md)
records the remaining verification boundary and the owner's prohibition on new JVMs.

Doc/dir cost verification, 2026-09-15: runtime_status again returned unknown
health and Flow with Read timed out for PID 23729. Read-only MCP JVM allocation
probes completed successfully. This re-observes the health endpoint boundary;
it does not identify its cause. No lifecycle operation was performed.

## P1 final observation — 2026-09-15

`b80f78a7c` fixes the raw database producer that made error-signatures iterate a missing-projection error into MapEntries. Default runtime observation now returns `observed`, two agents and three error-signature groups (41 ms combined read probe). The separate explicit publication attempt ended with `lock-hold-timeout`, 900000 ms, and did not establish complete source adoption. Default was not restarted. Final HTTP probes were 1.526276 and 1.806096 seconds, both with zero new projection-fallback warnings.
