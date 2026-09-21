---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, dependencies, reference-code]
---

# Read the dependency at its owning seam

`reference-code/` makes dependency semantics inspectable. Before adding a mechanism,
read the dependency implementation and the first-party caller that uses it. A
behavior observed in a small probe is not the dependency's complete guarantee.
The [implementation plans](../../prds/agent-platform/plan/README.md) retain exact
probe forms, historical revisions and proof gates; this guide identifies the seams.

A Git submodule checkout, the gitlink recorded by Seon and the code actually loaded
in a JVM can differ. Derive them separately: `git ls-tree HEAD reference-code/`,
`git -C reference-code/<dependency> rev-parse HEAD`, `git submodule status`, and the
resolved dependency/classpath source used by the host. `deps.edn` supplies direct
and transitive resolution. Absence of a local/root override does not mean a
library is design-only: Clojure, core.async and konserve can execute from artifacts.
No fixed repository count or maintained keep/delete roster replaces those reads.

## The mechanisms Seon composes

| Dependency seam | Guarantee and limit | First-party consumer / specification |
|---|---|---|
| `reference-code/datahike/src/datahike/versioning.cljc:212-277` | Branch creation shares immutable indexes and updates branch/roster state; it is not complete cluster startup | `src/seon/cluster/registry.clj:178`; [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md), [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md) |
| `reference-code/datahike/src/datahike/versioning.cljc:323-367,457-490,550-585` | Guarded head movement needs exclusive custody; immutable commit materialization needs release; whole-store copying can tear under concurrent writes | Source publication and B4 independent-store fixtures |
| `reference-code/datahike/src/datahike/versioning.cljc:734-748`; `reference-code/datahike/src/datahike/writing.cljc:860-889` | Merge takes caller-supplied content and lineage; current path does not supply Seon's expected-basis/validator contract | D1's maintained-fork merge composition |
| `reference-code/datahike/src/datahike/db/transaction.cljc:998-1015,1206-1226` | Incoming ref sweep and final-report validation, including attempted writes; a non-nil validator result rejects | `src/seon/db.clj:4050`; [A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md) |
| `reference-code/datahike/src/datahike/query.cljc:2568-2590` | Revision-based read currency; current transaction-time exclusion needs the specified correction | `src/seon/db.clj:1102`; A2 |
| `reference-code/datahike/src/datahike/datom.cljc:325-359`; `reference-code/datahike/src/datahike/pull_api.cljc:16,315,323` | Index ordering must support admitted values; many-pull defaults to 1,000 without a marker | Schema bridge and complete reads; A2 |
| `reference-code/datahike/src/datahike/gc.cljc:22-81,144-167` | Reachable parents/heads and cutoff govern collection; retraction or branch deletion is not all-snapshot erasure | Store maintenance and D1 evidence retention |
| `reference-code/malli/src/malli/core.cljc:2193-2218,2626-2648` | Compiled schemas retain derived operations; instrumentation receives child contracts; report-return does not itself stop entry | `src/seon/instrument.clj`; [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) |
| `reference-code/malli/src/malli/registry.cljc:17-30` | Fast registry copies a HashMap; simple registry retains persistent data | Projection construction; count copied work as well as compilation |
| `reference-code/malli/src/malli/error.cljc:44-172,288-306,374-390` | Declared error messages and structured humanization | B3 error prose composed from schema evidence |
| `reference-code/sci/src/sci/core.cljc:260-276,345-350`; `reference-code/sci/src/sci/impl/utils.cljc:362-379` | Intern, fork and copy-on-write Var update; shared JVM roots and mutable objects remain outside this isolation | `src/seon/sci/eval.clj:841,1100,2218`; [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) |
| `reference-code/sci/doc/interrupt.md:52,63-87` | Interpreted function/loop interruption does not stop host calls | Evaluation and B4 termination |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,271-320` | Future creation and serial proc loop; timed get is not cancellation/exit | Agent completion/permit ownership; B2 |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155,265-286` | Ping returns responders; compute timeout belongs to compute transforms | Boot readiness and admitted workloads |
| `reference-code/clj-kondo/src/clj_kondo/core.clj:242-270` | Cache processing precedes final findings; disabling disk cache does not erase in-run namespace resolution | `src/seon/fn/analyzer.clj`; B1 rejected/overlapping analysis proofs |
| `reference-code/clojure/src/clj/clojure/test.clj:710-737`; `reference-code/clojure/src/clj/clojure/core/server.clj:275` | Canonical test fixture dispatch and io-prepl framing | B4 run authority and B1 reachable tooling |
| `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`; `reference-code/http-kit/src/org/httpkit/server.clj:321-326` | Subscribe-before-first-render and compute dispatch; drain-or-close completion bounds pending bytes | `src/seon/render/web.clj:2704-2757`; B2 UI |

Konserve owns durable key/blob storage and reclamation. Read the selected backend
before relying on copy, enumeration, fsync or size behavior; a stored digest proves
only what the staging and write owner actually verified. The current store/registry
and blob owners stay the first-party integration points. Do not add a storage API
merely because an older review once requested one.

## Maintained changes and verification

Dependency changes land at the existing owner with public API, implementation,
regressions and caller conversion together. Push the maintained fork and record its
revision before a Seon gitlink depends on it. Verify both the changed boundary and
Seon's ordinary path; a dependency-only test does not prove loaded integration.

The active plans require specific proofs, not every historical fork proposal:
A2's currency/comparator/complete-read work; D1's merge guard/validator composition;
B1's publication/cache use; B2's existing SCI and Flow behavior. B2 requires no new
Flow timeout hook, and generation equality alone does not justify a new SCI privacy
mechanism. A filename-existence cache cannot replace captured input identity.

Dependency retirement follows recomputed code/document usage, resolved runtime use
and preservation of local fork changes. Research and older PRDs may still be the
only evidence for a claim. This revision neither declares them deleted nor treats
an unused direct dependency entry as proof its repository can be removed.
