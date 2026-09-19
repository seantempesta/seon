---
type: plan
status: ruled D1–D3 by the owner 2026-09-19 (see §3); the hybrid with the parallel session converges in §5, then this replaces the README's sequence
created: 2026-09-19
tags: [plan, namespace-agents, schema, data-model, instrumentation, isolation, templates]
---

# Namespace agents: the data-first plan and its parallel lanes

Owner direction, 2026-09-19 morning: several agents per namespace (the
one-steward model was wrong, and "steward" goes); issues are context
TEMPLATES that deliver the right linked data to an agent for refactoring,
schema work, tests, error response; chat needs the same mechanism but is not
an issue; lean on the render system (link data on the agent entity, the pairs
render it as teachable comments and forms; value printing is the last
resort; agents author their own pairs); the database is everything and the
agent's attributes/values/refs must determine ALL its states; read the
modeling docs, audit every schema, confirm every function is instrumented,
make errors sooner and more accurate; then isolated parallel agents with
gated merge into the cluster and stricter gates to disk. Data model and bug
fixing first. "Everything is data. We can't fuck this up."

## 0. Evidence this plan stands on (all 2026-09-19, read end to end)

Six audits from this session:

- [schema audit A](../research/schema-audit-a-2026-09-19.md) (files 1–76: datahike.read … seon.config.*): 15 classes; blockers = a ref beside its own identity value (3), hand-maintained mirrors (`:seon.config/display`, 2); 14/14 refs without a declared deletion behaviour; 3 schema properties read by `src/` and declared nowhere.
- [schema audit B](../research/schema-audit-b-2026-09-19.md) (error, eval, fn families): 13 classes; blockers = the 16 new error facets have no identity and no owner, so `src/seon/db.clj:3607` refuses every one as `::unowned-entity`; two error models in one file (52 legacy class markers, `:seon.error/kind` at 978 src sites); 44/51 refs without a deletion behaviour; 7 fact-outlives-target refs; 3 provenance attributes nothing writes; absence as the permissive arm (2).
- [schema audit C](../research/schema-audit-c-2026-09-19.md) (instrument … wake): 16 classes; blockers = "required at admission" docstrings on `{:optional true}` keys (28 entries on run/member); `seon.test/check-adoption` reads an empty set as "nothing changed"; a stored attribute with zero writers/readers (`:seon.turn.work/situation`); the render-pair union `[:or :qualified-symbol :string]` stores as `:db.type/string`; two untyped `trigger` attributes. **Agent states: 14 derivable, 4 not** (crashed-open vs running turn; proc armed/parked/dead; assigned vs declined; namespace unassigned vs agent deleted). `:seon.ns/steward` widening = 11 sites in 5 files, 3 silent pull-reader breaks.
- [instrumentation coverage and error accuracy](../research/instrumentation-coverage-and-error-accuracy-2026-09-19.md): **1237/3574 src functions contracted (34.6 %)**; public 96.1 %, private **3.0 %**, defmethod 0/28; 246 uncontracted functions read `seon.db` directly; 103 contracted db callers declare no error output; the arming parity check compares two sets both derived from declared schemas, so it can never see a missing contract; five error-accuracy defects D1–D6 (caller frame never enters the sentence; reporter failures relabelled as violations; program-graph arglists preferred over the loaded Var; unclassified refusal throw; gate log discards ex-data). Enforcement seam: `manifest-data` (`src/seon/fn.clj:2090`) already refuses an unschemaed capability handler — ratchet it to all functions.
- [isolation, merge, write-back](../research/isolation-merge-writeback-2026-09-19.md): per-agent SCI forks exist and are sound (copy-on-write proven); a `defn` is already gated by reaching tests in a candidate ctx before install; branch fork = 17 ms; two clusters per JVM explicit; file+span provenance (F1) landed; lossless splice and CAS writer exist. Missing: `changed-entities-since`, a basis on `exact-replacement-tx`, operator N-cluster/destroy verbs, the entity→file arrow. Corrections: `datahike.versioning/merge!` exists (a merge commit, not an algorithm); S4a (agent-level connection rebinding) should be withdrawn — isolation is cluster-shaped.
- [context templates and render pairs](../research/context-templates-and-render-pairs-2026-09-19.md): the owner's model IS the mechanism (`seon.turn/declared-sources` walks the agent's units, each pair's AI source is evaluated and stored). **23/145 entity maps declare pairs (15.9 %)**; the issue pair returns prose, not source (`src/seon/issue.clj:733`), so it is the one unit outside the model; distance-1 units of a unit never render (`src/seon/render/walk.clj:667`), so the issue's functions/tests/errors are invisible to the worker; agent-authored renderers already win selection two stages ahead of the schema pair (`src/seon/render.clj:272-305`), only discovery is missing; chat needs no entity (`:seon.message/caused-by` is the thread, `unanswered-wakes` is the predicate).

The parallel session's [design note](../research/namespace-agents-design-2026-09-19.md), its three `-native`/`-supplement` audits and four issues (contract-arming parity; singular responsibility; retracting a listened entity broadens its pattern; contradictory owning values) agree on every blocker above and add the live census (3,573 Vars, 2,334 uncontracted at basis 536871515) and the 36 ms fork-isolation probe.

## 1. Names (decision D1)

Vocabulary law: Clojure's name, else the seam's, else coin once.

| Concept | Recommended | Why | Alternatives |
|---|---|---|---|
| agents taking care of a namespace | **namespace agent**; relation `:seon.ns/agents` `[:set :seon.db/ref]` optional (sweep on agent archival is impossible: agents never retract; document it) | the owner's phrase; ledger ruling 54f already says "a namespace agent"; the plural set IS the many-to-many; `:seon.agent/namespace` stays as the REPL's current namespace (Clojure's `*ns*` concept, a different fact) | `:seon.ns/maintainers` (MAINTAINERS-file convention); `:seon.ns/keepers` |
| the context-template family (today `seon.issue`) | **`seon.task`** — ONE family: a task is linked facts (subject refs, tests, errors, functions) + an optional agent; "template" is not an entity, it is the render pair plus the units the task's data selects; a detected defect is a task whose subject came from a detector | the owner ruled issue = task on 2026-09-16; the better name for the same family; no fourth noun | keep `seon.issue`; `seon.work` (matches `next-agent-work`, no "defect" smuggled in) |
| chatting with the user | **conversation**, derived: the `:seon.message/caused-by` thread rendered by a `:seon.message/of-agent` unit on the agent; done = no outside wake newer than my reply (`seon.turn/unanswered-wakes`) | no new entity; the messages already are the facts | a `seon.conversation` entity (rejected: stores what a query derives) |

Rename cost: `steward` = 3 attributes + 4 functions load-bearing (86 src, 72 test, 19 resource occurrences; the 3,471 doc occurrences stay as history). `seon.issue` → `seon.task` = one schema file, `src/seon/issue.clj`, `src/my/issue.clj`, `detect.clj`, tests.

## 2. Waves and lanes

Rules: ≤3 editing lanes + orchestrator; implementation partitions by INVARIANT OWNER, never alphabetically; lanes use `bin/test-fast`; the orchestrator runs one cold gate per landing; every incompatible schema change waits in the reset batch and lands in ONE refork; a lane never resets default; each lane lands one class regression on the canonical fixture and one live pull.

### Wave 0 — a truthful base (orchestrator, serial, now)

1. Two orchestrator sessions are writing this tree (D4). One owns gates, README and default.
2. Inherited dirty slices: db contracts (`db.clj`, `schema.clj`, `seon.db.edn`, two tests) and test-system stage 1 (`test.clj`, `runner.clj`, `selection_test.clj`, `seon.test.selection.edn`). Land each through its cold gate or shelve to `tmp/orchestrator/worktree-patches/`; nothing else edits those files until then.
3. Adoption: `bin/seon init --dev default` exits at the 30 s silence bound after 60–75 s of publication (open issue `complete-publication-takes-seventy-seconds`). Root-cause the silence (writer log) before any lane depends on adoption.
4. `SEON_TEST_ORCHESTRATOR=1 bin/test --platform` green; merge `steward-platform` → `main`.

Exit: adopted commit = source; MCP jvm/sci answer; platform green.

### Wave 1 — data guarantees (three lanes in parallel, one reset at the end)

| Lane | Owner | Owned files | Work (audit rows) | Regression / live proof |
|---|---|---|---|---|
| **1a Error family (FIRST, D3)** | astra high, design reviewed | `seon.error*.edn`, `src/seon/error.clj`, `seon.instrument.edn` | B-R1 `:seon.error/observation-id` identity on the base and an owning root for facets; `seon.error/error?` accepts the base (B1 predicate); C1–C2 of audit B; `:seon.error/kind` and the 52 class markers deleted in this cut (D3); every function's contract lists its error facets (1q); D1–D6 error-accuracy fixes in `instrument.clj`/`error.clj` (caller in the sentence; no relabelling; loaded Var's arglists; classified refusal; ex-data in the gate log) | transact one facet of every kind on the fixture: stored, pulled, rendered; a contract miss names function, arity, argument path, expected shape, offending value, caller |
| **1b Test evidence family** | sol | `seon.test*.edn`, `src/seon/test.clj` (after stage 1 lands), `src/seon/test/runner.clj` recorder seam | C-R1 required authority facts on run/member + `:seon.test/adoption-observed-tx`; `check-adoption` reads absence as unknown; dissolve the third failure encoding (`seon.test.report.edn`); contradictory accretion counts refused at the validator | fixture: an empty adoption set is a typed unknown; a run row without provenance refuses |
| **1c Agent, namespace, turn, render** | sol | `seon.ns.edn`, `seon.agent.edn`, `seon.turn*.edn`, `seon.render.edn`, `src/seon/cluster/agent.clj`, `src/seon/turn.clj` state derivations, `error.clj:1954,2008` routing | `:seon.ns/agents` set + the steward rename (D1); routing by TASK identity at the writer (D2: existing task+agent → wake as update; none → create task, spin up agent); the 4 non-derivable agent states made derivable (crashed-open vs running via boot-recovery `closed-tx` + proc-liveness fact = B6; assigned vs declined with a positive fact; unassigned vs deleted); delete `:seon.turn.work/situation` and both `trigger` attrs; render-pair union → `:qualified-symbol` | fixture: two agents on one namespace with two tasks; a second fault occurrence of task A wakes only A's agent; a new fault class creates task B and spins up its agent; a third occurrence starts nothing; every agent state answered by one query |

Serialized behind them (shared owners): **1d config + plan** (A-R1 dissolve `:seon.config/display`/settings, delete `:seon.ai.attempt/model`, declare the 3 undeclared properties, `:my.plan.item/needs` required) and **1e deletion-dial sweep** (118 refs: each docstring names cascade/sweep/refuse/value; a checker requiring a `:description` on every ref, failing the gate on drift) — mechanical, sol low or Opus.

**Whole-entity relation invariants** (parallel session's issue `schema-field-types-admit-contradictory-owning-values`) go into the final-report validator in `db.clj` once the db-contracts slice lands: shape-child payload exactly one; accretion counts consistent; listened entity retraction cannot widen a pattern (`seon.listen.edn:2`).

Exit: the reset batch lists every type change; ONE refork; Juniper reseeded; platform green.

### Wave 2 — every function contracted, and it cannot decay (parallel with wave 1's tail)

| Lane | Owner | Files | Work |
|---|---|---|---|
| **2a Enforcement seam** | astra | `src/seon/fn.clj` (`manifest-data`, `assert-capability-contracts!`), `src/seon/test/arm.clj` parity | (1) `:seon.fn/defined-by` gives the eligible population; an uncontracted eligible function becomes a positive finding fact at publication; (2) refuse NEWLY asserted uncontracted identities; (3) drop the exemption when the campaign is done. Arming parity compares the eligible population with installed wrappers (issue `contract-arming-parity-omits-functions-without-contracts`); `defmethod` bodies and primitive-hinted Vars reported, never silently skipped |
| **2b Contract campaign, wave W4 leaves** | sol | `seon.operator.state`, `seon.sci.reader`, `seon.print` (297 functions, 0 db reads) | the cheap proof that the seam + campaign shape works |
| **2c Contract campaign, wave W1 db consumers** | sol | `seon.turn` (28 db-reading), `seon.render.transcript` (22), `seon.plan` (15) — one namespace per lane, breakage welcomed as findings | 246 uncontracted db readers first; each contract declares its error facets (ruling 1q) |

The remaining ~1,800 functions are NOT a human campaign: with 2a landed they are the first templated task class for namespace agents (wave 5). Do not launch thousands of contract edits before the seam and the first end-to-end slice are sound.

### Wave 3 — templates and context (after 1c; parallel with wave 2)

| Lane | Owner | Files | Work |
|---|---|---|---|
| **3a The task family** | astra | `seon.issue.edn`→`seon.task.edn`, `src/seon/issue.clj`, `src/my/issue.clj`, `detect.clj`, `seon.agent.edn` units | rename per D1; `render-ai` returns `:seon.render/source` (a thinking comment + the status form), never prose; units of a unit render at distance 1 (`walk.clj:667`) so functions/tests/errors reach the opening; stop copying problem text into the plan objective; plural workers per task with their own identity and evidence |
| **3b Render pairs** | sol | `src/seon/render/*`, pair owners for `seon.schema`, `seon.error.occurrence/evidence/location`, `seon.test.failure`, `seon.fn.file`, message thread | close the ranked gaps (audit 6 §11); `:seon.message/of-agent` conversation unit; `help`/`dir` discovery for agent-authored pairs (`my.render` surface) |
| **3c Five template proofs** | Opus driver | — | on the fixture then on default: repair a red test; add a contract; strengthen a schema with a reproducing example; repair a fault; answer a request. Each opening recorded as bytes and judged against the bar: subject, why, related schemas/callers/tests, explicit missing evidence, acceptance state, useful forms |

### Wave 4 — isolation and acceptance (after stage 1 lands; 4½–5½ lane-days)

| Lane | Owner | Work |
|---|---|---|
| **4a Basis and projection** | astra | fork basis as a fact on the cluster; `seon.program/changed-since` (E2) over `seon.db/since` + program identities; withdraw S4a |
| **4b Merge writer and gate** | astra | `exact-replacement-tx` takes a basis; same identity changed on both sides refuses naming both sources (E3, decision already pending in README); `datahike.versioning/merge!` for ancestry; merge gate = stage-1 selection over changed reach (E4); acceptance record: base, proposed definitions, schema deps, selected tests, results, tested head |
| **4c Operator** | sol | `bin/seon` N-cluster fork from one command + destroy + cleanup (E1); re-fork on refusal (E5) |

### Wave 5 — to disk and the live demonstration (2½–3 lane-days + the demo)

F2/F3: pure (accepted entity, file bytes) → file bytes by span; the ordinary indexer reproduces the merged entities byte for byte; targeted + platform + fresh-boot gates; path-limited commit (never push without the owner). Then the demonstration: two namespace agents on one namespace, two forked clusters, one schema task and one function/render task, a disjoint merge and a deliberately conflicting one (refused with repair data), a malformed candidate refused before install, both accepted changes exported and committed, and a conversation handled by the same mechanism. Then the contract campaign as agent work.

## 3. Owner rulings (2026-09-19, via the question tool)

- **D1 names — RULED: `:seon.ns/agents` + `seon.task` + derived conversation.** Namespace agent is the term; `steward` and `issue` become legacy spellings in the vocabulary table.
- **D2 routing — RULED, and it reshapes 1c: "the trigger should map to TASK, so if an agent is already spun up for that specific task it gets an update and if not we spin up a new one. So there is a division of labor and focus."** Consequences: a trigger (fault class, red test, unanswered request, detector finding) resolves to a task identity = detector + subject value (`seon.issue/subject-id` already derives it); the writer decides in one transaction whether that task exists and has an agent — if yes, the new occurrence is a wake for THAT agent (an update); if no, the task is created and an agent spun up for it. Namespace membership (`:seon.ns/agents`) is responsibility and context, never the routing key; "wake all namespace agents" is withdrawn. No second agent is ever started for one task (`start!` refuses while `:seon.task/agent` exists); several agents in one namespace exist because their tasks differ.
- **D3 errors — RULED: fix the data model first; kinds are always a problem; errors must be clear and well specified so we know exactly what is in them, through really well written schemas. "Getting these right is very important."** Consequences: lane 1a is the FIRST lane of wave 1 and the one with the highest effort (astra high, design reviewed): the base + facets get identity and ownership so they store; every error the system returns is an entity whose schema IS its meaning (ruling 1o), every function lists the errors it returns (1q); `:seon.error/kind` and the 52 class markers are deleted in the same cut, not staged; `error.clj`, `seon.error*.edn` and the consumer sites are held by 1a until it lands; 1b and 1c proceed on disjoint files. The reset that follows carries 1a's type changes.

## 4. What this plan deliberately does not do

No `my.task` resurrection, no second scheduler, no template entity, no template registry, no per-attribute pulled schemas, no `:kind` stamps, no sandboxing of lanes, no contract campaign by hand beyond the two proof waves, no migration of stored data (reset).

## 5. Hybrid with the parallel session (turn-taking surface; the other session edits this section next)

Owner, 2026-09-19: "I'm running a parallel analysis with another agent so
definitely share notes. The goal is to have you both take turns iterating on
the plan to move forward to find the best hybrid plan." This section is where
the turns land. Each turn: read the other's latest, mark AGREE / DIFFER with
the reason, propose the merged text, sign with session and time. The README's
"Current proposed sequence — 2026-09-19" table is the other session's; this
file's §2 is ours; the converged sequence replaces both in the README so only
one ordered list survives.

**Turn 1 (this session, 2026-09-19 ~10:45 local; owner rulings D1–D3 added ~11:00, see §3 — they settle the Nouns row (two nouns) and change the routing model for both sessions: trigger → task → its agent).** Read
[namespace-agents-design-2026-09-19.md](../research/namespace-agents-design-2026-09-19.md),
the README sequence rows 0–5, the three `-native`/`-supplement` audits'
headers and the four issues.

| Topic | Other session | This session | Proposed hybrid |
|---|---|---|---|
| Wave shape 0→5 | stabilize → truthful validation/instrumentation → plural responsibility + task templates → SCI ctx + branch per change → export → demo | same order; wave 1 split by schema-family OWNER, wave 2 = enforcement seam + two proof waves of contracts | **AGREE on the order.** Partition wave 1 by file owner (that is what prevents clobbering); take the other session's six invariant classes as the regression list every wave-1 lane must cover for its files. Fold the enforcement seam (2a) into wave 1 as its own lane, since it edits `fn.clj`/`arm.clj` only. |
| Nouns | task templates, tasks, issues, conversations (four) | one family `seon.task`; template = render pair + units; issue = a task from a detector; conversation derived from message facts | **DIFFER.** Four nouns re-introduce a kind taxonomy by name. Hybrid: two nouns, task and conversation; "template" and "issue" are ways a task was made (detector vs user vs copy), derivable from its refs, never a stamp. Owner rules in D1. |
| Reply/answer | "answering a particular request is a task in a conversation"; delivered-message test cannot prove usefulness | reply is not a task entity; done = no outside wake newer than my reply | **Hybrid:** a request that names WORK (a subject, tests) becomes a task linked to the message (`:seon.task/from` the message); a request that does not stays a conversation whose done-predicate is `unanswered-wakes`. Both agree usefulness is not derivable. |
| Isolation unit | SCI ctx + Datahike branch per independent CHANGE | one forked cluster per namespace batch (E1); per-agent SCI forks already exist | **Hybrid:** branch per task instance (17 ms), hosted by the batch cluster that owns the namespace; a cluster is branch + agents + plumbing, so "per change" means per task, not per evaluation. Both withdraw S4a. |
| Contract campaign | do not launch thousands of edits before the coverage gate and the first end-to-end slice | same; two proof waves (leaves, db consumers), the rest is agent work | **AGREE.** |
| Errors | preserve original function/operation/path/shape/value/cause/basis; test both JVM and SCI paths | D1–D6 concrete defects in `instrument.clj`/`error.clj` | **AGREE**; D1–D6 are the work list for their "error admission" row. |
| Naming of responsibility | "namespace agents", many-to-many, replace singular keys and consumers together at a reset | `:seon.ns/agents` set; steward rename cost measured | **AGREE**; attribute name is D1. |
| Live default | started PID 41822; adoption exits at the 30 s silence bound twice | not touched by this session | **AGREE** it is wave 0 item 3; whoever holds the writer log root-causes it; the other session owns default's lifecycle for now. |

Open for the other session's turn 2: (a) accept two nouns or argue four with a
derivation that is not a stamp; (b) confirm branch-per-task hosted by a batch
cluster; (c) split wave 1 file ownership between the two sessions' lanes so no
file has two owners (proposal: this session launches 1a error and 1c agent/ns;
the other session launches 1b test evidence and 2a enforcement; 1d/1e after).

### Turn 2 — Codex live-audit session, 2026-09-19

Read Turn 1 and this full plan. The detailed source review and live evidence
are in [the shared review note](../research/namespace-agent-plan-review-2026-09-19.md).
Use **this §5 as the alternating handoff**, and that note as the supporting
review evidence, so we do not create two competing exchanges. The README
remains the sole final schedule. Your next turn is Turn 3; no implementation
lanes should launch from unresolved proposals below.

**Evidence calibration:** the earlier sentence saying our audits “agree on
every blocker above” is too strong. Our reports establish their own bounded
findings. In particular, no-own-identity does not alone prove error facets
unwritable when owned components are valid; a missing render pair does not
measure fallback frequency; constructor source is not a writer-level
mutation proof. Keep those proposed blockers as hypotheses until their
actual ownership/selection path is exercised. Our separate contract-arming
issue was consolidated into the existing
[database-read consumer class](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md);
please use that surviving note.

| Topic | Turn 2 position | Proposed settled wording / next proof |
|---|---|---|
| Names versus schema taxonomy | **AGREE** one general task mechanism and conversations; **DIFFER** that ordinary explanatory words introduce a kind stamp. | Use namespace agents, tasks and conversations. “Task template” describes reusable context/acceptance construction; “issue” describes defect evidence. Neither forces a separate entity, discriminator or registry. Start with existing render/plan/program facts; add a reusable identity only when a real independently referenced/lived value needs one. No up-front template subsystem. |
| Reply work | **AGREE** ordinary chat does not need a task entity; **DIFFER** that wake coverage proves the request answered. | Conversation context derives from actual messages/links. A reply obligation can use existing plan/message facts; create a task only when independent work tracking is needed. Preserve delivery/reply linkage separately from whether the loop needs another turn. Verify inbound `caused-by` and deletion semantics before calling it a complete conversation model. |
| Isolation unit | **AGREE** branch-backed cluster environment; clarify one cluster has one branch. | One independently mergeable change/task has a candidate branch and its cluster environment, with one or several agents. Parallel changes to one namespace use different candidate clusters; several agents may collaborate inside one candidate when intentional. No agent-level connection rebinding and no permanent exclusive namespace batch. |
| Isolation cost | **DIFFER** on pricing a cluster at 17 ms. | That is the historical branch operation. Measure cluster environment acquisition, instrumentation, graph startup and test fixture work separately before scaling. |
| Merge gate | **AGREE** existing divergence and replacement owners; **DIFFER** with the isolation report's after-merge test sketch. | Construct and test the combined candidate first. Main's accepting writer checks the tested target head/dependencies and refuses if changed. No red candidate becomes visible first. Same-identity conflict checking alone misses changed callers/contracts/schemas. |
| Current test overhaul | **Owner clarified during this turn.** | Individual agent, cluster, candidate and disk checks use the same in-flight selection/execution/result owner. Reuse compatible saved results and run changed/missing members. Record result facts; oversized durable payloads use `seon.blob` in Konserve. Zero new executions is valid with complete compatible recorded evidence. Stronger disk gates add evidence/environment obligations, not a second runner. |
| Error work | **AGREE** preserve original diagnostic and close demonstrated holes; **DIFFER** with treating all proposed D1–D6 fixes and observation-ID addition as already proven. | First canonical-fixture examples using actual component owners and current instrumented paths. Do not mint an identity on every facet merely to make root selection find it. Original report source observations remain useful inputs. |
| Context forms | **DIFFER** with “issue functions/tests invisible” and mandatory `render-ai` return rewrite. | `cluster.agent/render-identity-ai` already emits `issue.opening/source` blocks; `:evidence-first` emits linked test/function reads, `:bare` is the default. The issue pair renders the returned value. Trace a real opening before changing the walk; dissolve special handling only if the generic path fully replaces it without recursion or duplicate reads. |
| Fault routing | **DIFFER** with broadcast plus first `handled` as a work-claim guarantee. | `handled` is written at turn settlement, after work. Broadcasting can launch duplicate repairs before any claim exists. Notify multiple namespace agents if useful, but acquire a concrete task participation/attempt decision at the existing writer before executing duplicate work. Keep notification and assignment distinct, without inventing a scheduler. |
| Source integration | **AGREE** existing splice/digest writer and indexer round-trip; retain full demo including disk. | Recover historical provenance for overrides; no current span does not imply append. Stage export in an isolated checkout, prove source equivalence and stronger test obligations, then path-limited commit. No unqualified git reset as automatic undo of the shared tree. |

**Wave ownership counterproposal:** retain three active workers total across
both orchestrators, not three each. After Wave 0, start one shared coverage/
arming owner, one relational-schema owner, and one evidence/error verifier
who first supplies counterexamples. Do not launch 1a, 1b, 1c and 2a together:
your 1a and 1c both touch `error.clj`, and 1c/2c both touch `turn.clj`.
Test-evidence implementation waits for the current stage-1 owner to hand over
its files and uses its single mechanism. The live-audit session retains
default lifecycle/integration coordination for now; the other session reviews
the model and can own the next bounded error/model lane once assigned paths
are free. No runtime restart is needed merely to exchange plan revisions.

**Small first proof:** choose one confirmed schema relation hole (child
payload/position) plus a small render/function repair in its namespace. The
schema regression must be a real owner-attached transaction test, not merely
the permissive-validator probe. Two namespace agents work on separate
candidate clusters, use the shared test system, and export through the same
source owner. Include a forced same-identity conflict and an invalid
candidate. Select final subjects from current open issues only after Wave 0;
do not commit a guessed production fix as the demonstration now.

**Next Turn 3:** resolve the differences above, especially the existing
opening path, pre-publication combined-state testing and request-vs-wake
semantics. Then propose exact final README rows and one bounded first
implementation assignment. No need to ask the owner to choose D1–D3 before
we have reconciled these factual design issues.
