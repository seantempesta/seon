---
type: issue
status: open
severity: friction
tags: [issue, sci, schema, agent, wave/agent-context]
---

# Candidate context shares parent program metadata and projection state

## Evidence — 2026-09-05

Source audit at `221b67960d78c745a638b0fcf8fa06f575c0c2a7` while the
turn-batch work was in flight. This is an unverified-live finding: no real
candidate was invoked against a shared cluster, and no assertion is made
about the unfinished turn-batch implementation.

A subsequent non-mutating MCP probe confirmed the three present state
references remain identical after SCI fork in the live default ctx, while
SCI :env is distinct (basis 536871006). It did not execute candidate
installation, so the complete corruption path remains source-grounded rather
than reproduced live. Script and exact observations:
[context ownership probe](../../prds/context-generation/research/scripts/design-lab-context-ownership-2026-09-05.clj).

The historical issue
[one-program-graph-is-shared-across-clusters](archive/one-program-graph-is-shared-across-clusters.md)
records the same mechanism at the sibling-cluster boundary, repaired there.
The current candidate path has a distinct remaining boundary:

1. `src/seon/sci/eval.clj:1537` (`fork-for-turn`) calls `sci/fork` and
   restores the selected agent's defs. It does not replace the Seon context's
   metadata or environment-state references.
2. SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`,
   `reference-code/sci/src/sci/core.cljc:345`, replaces only the context's
   `:env` atom. Other context entries remain identical references. SCI Var
   copy-on-write therefore does not imply isolation of arbitrary Seon atoms.
3. `src/seon/sci/eval.clj:2235` (`fork-candidate-ctx`) returns that fork;
   `evaluate-candidate` at line 2298 evaluates a function and subsequently
   calls `install-candidate-function!` on this original candidate ctx.
4. `install-candidate-function!`, line 2246, calls `kernel/cache-function!`,
   `kernel/mark-installed!`, and `advance-context-projection!`.
   `src/seon/sci/kernel.clj:117` swaps the `::program-snapshot` atom;
   line 154 swaps `::installed-functions`. `src/seon/sci/eval.clj:565`
   passes the carried environment-state atom to `env/advance-projection!`;
   `src/seon/env.clj:109` swaps it when the supplied basis is not older.
5. `evaluate`, `src/seon/sci/eval.clj:1961`, does create a scoped local
   environment-state reference for its evaluation. That local ctx is not
   the ctx subsequently passed to candidate installation, so this does not
   establish parent isolation for that later operation.

Consequently candidate installation can change the parent's cached function
source, installed-function membership, and schema projection even when its
SCI Var remains unchanged. This threatens the claim that a preview is never
promoted: later parent discovery, contract selection or lazy installation can
observe candidate metadata without a committed definition.

## Existing coverage and missing proof

`test/seon/test/accretion_test.clj:211` exercises `evaluate-candidate`, both
gate outcomes and the candidate function's changed behavior. Its parent
assertions establish byte-identical SCI Var roots and original behavior, but
do not assert unchanged parent program snapshot, installed-function set or
environment/projection.

`fork-cluster-ctx`, `src/seon/sci/eval.clj:1654`, explicitly derives fresh
metadata atoms and receiving projection state for sovereign clusters. This
is evidence of the known boundary, not a prescription to construct another
cluster or introduce a separate lab runtime.

## Owner and acceptance

The existing agent/candidate context owners in `seon.sci.eval` own the fix.
Reconcile it with the in-flight turn-batch implementation before selecting a
mechanism. A synthetic canonical-fixture regression must invoke a real
candidate and assert its changed source/contract while independently proving
the parent's metadata, projection, Var state and subsequent invocation are
unchanged. Include a rejected candidate and a different return contract so
unchanged Var behavior alone cannot make the test pass.

The design-lab preview should reuse that verified agent path. This finding
does not authorize a separate SCI setup or a second publication mechanism.
