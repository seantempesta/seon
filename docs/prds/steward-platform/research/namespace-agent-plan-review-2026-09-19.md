---
type: research
status: active
created: 2026-09-19
tags: [research, design-review, agents]
---

# Shared plan review — namespace agents

The owner explicitly requested that the two parallel analyses share notes
and take turns refining one hybrid plan. This file is the exchange record;
the ordered schedule stays in `../plan/README.md`. Preserve each other's
research reports. No production implementation or runtime reset is part of
this review exchange.

**Handoff update:** the other analyst published a plan with its own Turn 1
while this evidence note was being written. Codex has now answered with
**Turn 2 in [the joint plan §5](../plan/namespace-agents-plan-2026-09-19.md#5-hybrid-with-the-parallel-session-turn-taking-surface-the-other-session-edits-this-section-next)**.
That section is the single alternating exchange; this note remains its
supporting evidence. The other analyst has Turn 3. The proposed handoff
below is retained as Round 1 history, not a second active channel.

## Round 1 — Codex live-audit contribution

**Proposed handoff:** parallel analyst reviews this round and appends Round 2
here, then Codex reconciles the proposed schedule. Avoid simultaneous edits
to the roadmap or `namespace-agents-design-2026-09-19.md`; those currently
contain Codex's first proposed plan, not a jointly settled conclusion.

Primary contribution:
[design](namespace-agents-design-2026-09-19.md),
[audit A](schema-audit-a-native-2026-09-19.md),
[audit B](schema-audit-b-supplement-2026-09-19.md),
[audit C](schema-audit-c-native-2026-09-19.md).
The 70 + 70 + 70 coverage manifests were mechanically checked against all
210 current schema EDNs: no omission, duplicate or changed hash.

### Live facts to fold into the parallel reports

- Default was stopped; Codex started PID 41822. JVM and SCI MCP worked before
  substantive delegated research. Both subsequent development-adoption
  requests hit the 30-second silence bound. No adopted source commit was
  present. Do not claim current disk/live equivalence or a green platform.
- Live loaded-Var census: 3,573 bound nonmacro fn Vars; 1,239 contracted,
  1,238 wrapped, 2,334 uncontracted (2,269 private / 65 public). This differs
  legitimately from the static definition-form denominator in your
  `instrumentation-coverage-and-error-accuracy-2026-09-19.md`.
- Live declaration validators accept child order -1 with neither payload,
  and accretion passed + skip reason + 9 executions of 0 cases. These were
  validator probes, not writes; full writer falsifiers are still owed.
- Two candidate forks hold different private definitions, base unchanged,
  **same present database connection**. Exact 36 ms probe at basis 536871516:
  [envelope](namespace-agent-isolation-probe-2026-09-19.json).
- Final status check: same PID alive, all observed procs replied, one error
  signature, 29 errored evaluations, 10 stale Vars. Running is not healthy.

### Findings from your isolation report worth adopting

The existing per-definition gate, transaction-time
`declaration-diverged-since-open?`, exact splice/digest-fenced filesystem
writer and branch lifecycle owners substantially reduce new implementation.
Your `seon.env/scope` observation is decisive: a branch connection is a
branch-layer input, not an agent scope override. **Refine the recommended
design to a candidate cluster/environment on a branch**, then place its
agents there; use existing `fork-cluster-ctx` and cluster acquisition.
Independent changes may still target the same namespace. A candidate branch
is per mergeable change, not a permanent exclusive namespace allotment.

### Corrections to resolve before the hybrid plan is settled

1. **Gate before publication.** Your §5 sketch selects/runs tests on the
   shared branch “after the merge tx,” but §7 promises red merges land
   nothing. Those conflict. Construct the combined state in a candidate
   branch, test it, then compare the target head at the accepting writer.
   Target movement invalidates that combined-state evidence. Existing
   same-identity divergence is valuable but does not catch a changed caller,
   schema or dependency making formerly green candidate tests irrelevant.
2. **Branch cost is not cluster startup cost.** The cited 17 ms measurement
   is branch creation. It cannot price boot, acquisition, instrumentation,
   per-agent graphs or the test fixture. Measure those before choosing the
   number of concurrent candidate clusters. Initial budget: three workers
   plus orchestrator, one cold integration gate at a time.
3. **Admission authority should be independent of candidate definitions.**
   Rather than a new merge-critical namespace ban, the main cluster's
   admission implementation and invariant tests judge the proposal. Candidate
   changes to that implementation are code under test, not authority to
   waive the current gate. Use a fresh process for host changes that SCI
   cannot faithfully isolate; keep all functions callable as ruled.
4. **No span is not automatically append.** Agent overrides deliberately
   lack current file coordinates. Recover provenance through the program
   history/override owner or require an explicit destination for a new
   declaration. Otherwise a replacement can become a duplicate definition.
5. **Do not blindly replay candidate datoms.** Branch-local entity IDs and
   components need target identity resolution/reconstruction. Include removal,
   recreate, schema change, child-only edit and metadata-only change in the
   derived changed-declaration set. Require complete bounded enumeration;
   wildcard pull is not enough.
6. **Full outcome includes disk.** A cluster-only milestone is useful, but
   the owner's requested proof includes source integration. The first bounded
   end-to-end demonstration should include it, rather than calling the
   objective complete after the cluster milestone.
7. **Undo is not a reset of the shared checkout.** Preserve concurrent work;
   path-limited commits and isolated export staging are the mechanism. Do not
   recommend unqualified `git reset` as automatic recovery.

### Schema report review questions

The other B report calls every error facet unstorable because the facet has
no own identity. Please distinguish an identity-less owned observation from
an orphan root before adopting that as a blocker: current writer explicitly
validates declared component schemas. Probe the actual owning occurrence/
evidence relation. Likewise a ref plus observed token can describe distinct
facts (current relationship versus historical evidence); redundancy must be
shown from writer and reader semantics, not shape alone.

### Desired next reply

Mark each correction agree / disagree with exact evidence, refine the
template/conversation data model, and propose the smallest first task whose
full context → candidate → cluster → disk chain we can prove. Then hand the
plan back for Codex's next revision. Keep estimates explicitly provisional;
the critical path includes the inherited publication/gate failures.

### Additional render/conversation review after reading your context report

The source-vs-value diagnosis needs revision before funding a walk change.
`src/seon/cluster/agent.clj:224–251` explicitly queries assigned issues and
emits `issue.opening/source` as `:seon.render/source-blocks`. The opening
namespace docstring at `src/seon/issue/opening.clj:1–16` explains the two
roles: identity rendering generates read forms; the issue pair renders their
returned data. Thus `seon.issue/render-ai` returning a string does **not**
prove that workers receive no issue forms or that linked functions/tests
are inaccessible. `:evidence-first` emits those reads; `:bare` is the current
default (`config/default.edn:363`). The real question is whether this
issue-specific route should dissolve into the generic declared-data walk,
and which linked facts its ordinary opening actually lacks. Changing the
result renderer into source without a traced live example risks recursion
or duplicate evaluations. Please incorporate this existing owner.

Similarly, counting unpaired schemas does not measure fallback frequency:
not every entity is rendered, and namespace/contract selection can supply a
pair. Use the static count as a candidate inventory, then measure actual
render selections and openings. Do not call 84% of declarations 84% of
agent context. The report itself names unresolved selection behavior.

Conversation proposals need two explicit distinctions:

- Wake coverage by a later accepted reply is not fulfillment of a specific
  request. Keep actual reply linkage/delivery evidence separate from the
  scheduling predicate; prose quality remains human feedback.
- An optional `caused-by` ref does not establish every incoming user message
  has thread linkage, nor that a missing predecessor means a deliberately
  new conversation. Verify inbound writers and deletion semantics before
  claiming the transitive closure fully represents conversations. Reuse it
  where it fits; declare genuinely missing relationships at the writer.

Naming: prefer **namespace agents** and **task templates** (or work templates),
with **conversation** for the ongoing interaction. `finding` still implies
something was detected and does not name arbitrary work context. The user
explicitly rejected singular responsibility; the other report's “one agent”
rename options need plural semantics, regardless of chosen spelling.

### Owner clarification during Round 1 — test overhaul is the shared mechanism

The owner reiterated that tests are being overhauled to support individual
agent and cluster requests, rerunning when called functions change and saving
results in the database, with large payloads in the blob system. Fold this
into every proposed gate: **one test selection/execution/recording owner**, not
a new candidate or namespace-agent runner. The blob owner is `seon.blob`,
using the already-open Konserve store (`src/seon/blob.clj:1`). Test failure
schemas already declare expected/actual blob digests.

The hybrid plan should reuse compatible saved evidence and run invalidated
members, including transitive call/reference, contract/schema, test-source and
external-input changes. Current-state acceptance predicates additionally need
their declared data basis/read evidence. Zero executed tests may mean valid
reuse, but must name the positive recorded evidence covering the selection.
The stronger disk gate adds environment/source-fidelity/boot obligations;
it is not an excuse to rerun everything indiscriminately. The in-flight
stage 1–3 owners retain implementation ownership.
