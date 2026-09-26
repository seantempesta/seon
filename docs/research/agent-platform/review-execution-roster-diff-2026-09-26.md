---
type: review
status: complete
date: 2026-09-26
reviewer: gpt-6-astra
source-pin: 5bed888d02c300ca3dc798109687f2b88471a436
verdict: REVISE
blocking-findings: 2
---

# Execution roster slice: independent diff review

Merging context membership into the existing execution roster removes one mutable authority. Keeping one roster for dynamically admitted live resources is consistent with the owner's revised law. The implementation nevertheless exposes the parent capability through that roster and detaches resources before all of their owners have exited.

## Scope and evidence

Read `CLAUDE.md`, `system-state-opinion-2026-09-26.md` and `atom-audit-2026-09-26.md`, then the actual `30e6e8f75` diff across source, schemas and callers. The design and commit explanation are the available rationale for this slice; no dedicated installed landing for `30e6e8f75` was found. Checked its final source/callers at `5bed888d0`. The later host changes are reviewed separately, not credited as proof of this slice.

Line references below are at that pinned source. This review ran no JVM, tests, release operation or live acquisition. The design's census is historical and its whole-heap observation is not an allocation measurement for the change.

## Findings

### E1 — blocking: an isolated agent's supplied context now reaches its parent's capabilities

**Location:** `src/seon/cluster/agent.clj:625`, `:1070`; `src/my/program.clj:420`.

**Failure scenario:** the child roster stores the parent roster directly under `:seon.agent/parent`. Acquisition places that child roster in the SCI environment. `my.program/supplied-context` returns it as part of the declared `:my.program/context` argument. An agent function receiving that supplied context can dereference the child, follow the parent, and read the parent's acquired contexts or armed handles, including their connection and SCI context. It can then pass a parent connection to callable database operations. Before this cut, isolated acquisition deliberately allocated a separate context-state without the parent handles; the diff deletes that separation and adds a direct parent edge.

This is not an objection to an atom or to an agent having its *own* resources. It is an object graph that grants foreign custody. Hiding a function from the prompt would not fix it: all program functions remain callable.

**Smallest fix:** retain parent membership/retirement bookkeeping only in the lifecycle owner's held management value, outside the agent-visible supplied context and SCI environment. Supply the agent only the resources/capabilities it already owns. Where an agent needs its branch/mode, carry that immutable fact rather than the process resource roster. Preserve a single management roster; do not create a second authority mirror.

### E2 — blocking: release detaches a subtree without retiring descendants or acquisition claims

**Location:** `src/seon/cluster/agent.clj:872`, `:901`, `:908`, `:930`.

**Failure scenario:** execution A acquires isolated execution B through its own handle; B is now in A's `:children`. Releasing A stops only A's `:armed` graphs, releases A's connection/branch, and removes A's roster from its parent. It never visits B. B's graph/connection can remain live while its roster is no longer reachable from the process's hierarchy. Similarly, `acquire-once!` does not check `:released?`; a pending claim remains in the detached roster and can settle after release. The docstring's “a release wins” condition no longer follows from the code: release removes the parent's child entry, not the reservation `settle!` checks inside that child.

**Smallest fix:** make retirement close both graph and context admission, await in-flight acquisition settlement, and release owned descendants through their existing release owners before detaching the roster. A failed descendant join must retain the reachable management handle for retry/diagnosis. Borrowed resources must retain their existing ownership distinction. A single released-state transition and the existing claims suffice; no second scheduler or cleanup queue is needed.

## Dependency use, simplicity and laws

The separation to keep is facts in Datahike, contexts in SCI, running graph state in Flow, and dynamic resource membership in one execution roster. The code should store references to live objects there, not duplicate their mutable state. The diff largely does that and deletes `context-state` readers/schema rather than leaving two registries.

SCI's fork replaces only `:env` with a generation-bearing copy (`reference-code/sci/src/sci/core.cljc:345`, pin `49ce5cb9`). It does not deep-copy or attenuate arbitrary host capabilities reachable through environment values. E1 therefore cannot be delegated to SCI's Var isolation. Datahike's `active-connection` explicitly does not acquire an owning reference (`reference-code/datahike/src/datahike/connections.cljc:5`), while reference release is an explicit transition (`:11`, pin `f3a3cb593`); dropping a roster entry is neither closing nor joining the referenced resource.

Moving listener registration into armer initialization uses a real Flow seam: `step args` runs during start (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:263`, pin `dc35f3e0d`). Removing it on quiescence/stop puts it nearer its lifecycle owner. Flow's stop operation (`:174`) still does not recursively discover Seon's child executions or await arbitrary host exits. Those are roster ownership responsibilities.

Malli's compiled validators (`reference-code/malli/src/malli/core.cljc:2637`, pin `befcd302a`) validate carried shapes; an atom-valued routing schema cannot establish absence of foreign references or completion of retirement. Keep error outcomes flat and retain the whole cause when a child release fails; detachment in cleanup must not erase the diagnostic owner.

## Size, costs and proof limits

For `30e6e8f75`: source **+106/−89 (net +17)**; schema **+7/−15 (net −8)**; tests **+30/−24 (net +6)**. Production plus schema grows nine lines, against the opinion's smaller net-deletion sketch. The eliminated context-state owner is a valid simplification; the new parent/children lifecycle must still be justified and completed at its boundary.

The opinion reports a 2,471 MB whole-heap observation and explicitly lacks attributable byte sizes. It supplies no parent/change measurement of this committed acquisition/release implementation. Current timing, allocation, live status visibility, adoption and arming remain unverified here. Root needs positive tests of agent-visible capability confinement, nested release, release racing acquisition, and failed-join retention. A successful shallow release does not establish the hierarchy's lifetime guarantee.

**REVISE**
