---
type: research
status: complete
tags: [agent-platform, architecture, review]
---

# Architecture review — 2026-09-21

Read end to end: [the writer brief](../../prds/agent-platform/research/writer-brief-2026-09-21.md), every working-tree file under [architecture/](../../seon/architecture/README.md), including the sixteen decision records; [the earlier verification](../../prds/agent-platform/research/architecture-docs-verification-2026-09-21.md), [synthesis](../../prds/agent-platform/research/synthesis-2026-09-21.md), [durable goals and rulings](../../prds/agent-platform/research/durable-goals-and-rulings-2026-09-21.md), [reference-code audit](reference-code-usage-audit-2026-09-21.md), [plan README][plan], all seven specs [A1][a1], [A2][a2], [B1][b1], [B2][b2], [B3][b3], [B4][b4], [C1][c1], and AGENTS.md §1–§2. Review subject: the working-tree documents, not their previous committed versions.

Verification boundary: source citations checked against HEAD `209a6652a0220570c77b3d9aadd75c446ab6e9bc`; dependency citations checked against the checked-out gitlinks below. No JVM, operator, tests, live evaluations, or browser observations. No claim here verifies running behavior. Foreign edits in `src/seon/cluster.clj`, `src/seon/fn.clj`, `test/seon/cluster/publication_delta_test.clj`, and `test/seon/fn/publication_cache_test.clj` were preserved. Only this note was written.

Note validation: local links resolve and whitespace checking passes. The edit hook reports seven stale dependency citations outside this note, including `.agents/skills/datahike/references/fork-maintenance.md:36` and `docs/prds/steward-platform/plan/bridge-step4-writer-diet-spec-2026-09-21.md:556` citing the prior Datahike gitlink. Those foreign files and the other review notes appearing during this lane were left untouched; repository-wide citation lint is not green.

## Findings

P1 means correct before treating the pages as implementation instructions; P2 means improve navigation, precision, or length in the next pass.

| item | finding | evidence | proposed change | priority |
|---|---|---|---|---|
| 1 · implementer | None of the fourteen sections has a direct link to its implementation spec; bare “lane B2” and abbreviated dependency filenames require another search. §0 lacks its own data flow and reading list. | architecture §0–§13; README promises a uniform ending. | Append the per-section reading lines below; use full dependency paths. Link D1 as pending, not as four already sufficient B specs. | P1 |
| 2 · publication | §5 combines a transaction report, further caller lint, and “one open transaction.” B1 explicitly removes the unpublished branch and selects callers before its one transaction. The goals note explicitly rules the opposite ordering. | architecture:192–228; B1 §2a steps 6–10 and explanation; goals §3, “transaction report is the seam for caller lint.” | State the disagreement; retain the invariant and make the publication order an explicit decision. Do not silently override an owner ruling with a spec or describe the hybrid as an algorithm. | P1 |
| 3 · no-change cost | “Nothing changed compares two commit ids” conflates adoption with detecting source changes. An identical file request must hash its supplied paths; a pathless request hashes the recorded paths. | architecture §5; B1 §2a steps 2–3, 11; §6 pathless probe. | Separate source observation, publication, and adoption costs. | P1 |
| 4 · flow | §3 moves the paid turn to `:compute`; B2 keeps `:io` and changes flow's timeout support. A Future timeout does not cancel the transform. | B2 §2b:140–152; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:257–261`. | Name the pending core.async change and the execution deadlines; do not claim the proc timeout alone stops execution. | P1 |
| 5 · SCI | “Fork once,” intern every base difference, and an SCI fork patch disagree with B2's replacement fork on base changes and withdrawn patch. B2's authorship-based JVM binding also disagrees with the digest ruling. | agent-runtime §1; reference-code final paragraph; B2 §2a; B1 §2g consumers; goals R4/1s and prohibited-pattern row; AGENTS §1 sovereign clusters. | Separate agreed reuse from unsettled base-change and binding decisions. A shared JVM Var following every reload cannot by itself preserve an older cluster's program. | P1 |
| 6 · projection/arming | “Built once per publication” and “for each contracted Var” do not establish change-proportional work. Lazy compilation does not itself provide dependency invalidation. | architecture §7–§8; A1 projection replacement/parent closure; B1 §2a step 13. | Describe changed schema keys plus reverse references, retained compiled nodes, and the reloaded namespace input to arming. Distinguish selection work from wrappers actually replaced. | P1 |
| 7 · refusal | Returning an error from Malli's `:report` does not refuse execution. The next expression still applies the function. | architecture:344–345; `reference-code/malli/src/malli/core.cljc:2207–2221`. | Say the report must exit validation and the Seon boundary converts that failure to its flat value. | P1 |
| 8 · errors | “Blob complete” contradicts B3's declared datoms plus shown text/result handle. The goals note itself contains both a later complete-blob ruling and a ban on result blobs. | architecture §9; B3 §2a; goals:277, 376. | Flag the storage decision explicitly; do not make either conflicting statement an unqualified current guarantee. | P1 |
| 9 · tasks | “Proportional to findings” omits the cost of discovering those findings; a detector can scan the program while returning zero. | architecture §10; B3 §2b detector and settlement queries. | Distinguish full detection from indexed routing and single-subject settlement. State unknown discovery cost honestly. | P2 |
| 10 · tests | §11 carries forward `check` → `run-owned` and static selection as the target. B4 removes both names for one `run`; replacing static reach with observed reach is still an owner decision. | architecture §11; B4 §2, §5–§6, §8; plan §7. | Name the one target entry point, correct recording custody, and mark reach policy unresolved. Past observed absence cannot establish that changed code is unreachable. | P1 |
| 11 · profiling | Periodic flush, another transaction, and asserted 3.5% armed overhead disagree with C1. The ~40 ns result is a microprobe, not the implemented armed path. | architecture §12; C1 §1–§2, §5; plan C1 row. | Flush in turn close, no new timer; retain measured scope. Do not promise work proportional to touched cells while C1 iterates its registry. | P1 |
| 12 · profiling inference | Aggregate callee totals plus static call edges cannot determine a caller's self time: a callee can have other callers, recur, or run concurrently. | architecture §12 “cost of every chain”; C1 §2/§5 and plan C1 self-time subtraction. | Promise inclusive count/total/max; label self-time/call-chain attribution unresolved. This is a spec defect too, not a prose fix. | P1 |
| 13 · shared digest | B1 supplies `:seon.program/definition-digest` over source plus aliases; C1 describes `:seon.fn/digest` and source-only identity. | B1 §2g; C1 §2 and dependency row. | One definition digest supplied by B1, named identically in C1/B4; no second derivation. | P1 |
| 14 · merge | §13 implies the merge/write-back plan is supplied by B1–B4 and says run tests “whose reach changed.” A test can reach a changed function without its reach set changing. | architecture §13; plan §4 D1, spec pending. | Link D1; say tests reaching changed definitions on the proposed combined program. Include conflict/basis checks and the no-test refusal. | P1 |
| 15 · current spans | “No form span is stored” is false. | architecture:178, 540; HEAD `resources/seon/schemas/seon.fn.edn:9,123`; `src/seon/fn.clj:207,605,628,662`. | Keep “no gated write-back”; state the existing half-open UTF-8 spans. | P1 |
| 16 · wrong turn owner | `open-run-tx-call` is a settlement helper, not the one-open-turn admission function. | agent-runtime §2; HEAD `turn.clj:395` `open-call`, `:465` `open-run-tx-call`. | Cite `open-call`; current already-open admission returns empty transaction data, not a typed refusal. | P1 |
| 17 · browser cost | “Never to history” is false when the whole view contains history. Rendering every tab on every signal also omits B2's read-evidence skip. | ui §2; B2 §2c; hyperlith `impl/datastar.clj:162` sends rendering to a CPU pool. | Separate signal/read-evidence checks from changed-view rendering, including the history displayed. Do not imply all rendering runs on the tab's waiting thread. | P1 |
| 18 · pull/reads | UI carries the current 1,000-member default into target prose. The dependency currency predicate also refuses `:all`, so not every read gets an attribute-only revision check. | ui §3; A2 §2; Datahike `pull_api.cljc:16,315`, `query.cljc:2963–2976`. | State current cut, target complete default/resource refusal, and conservative whole-database dependency behavior separately. | P1 |
| 19 · modeling guide | Calls/reach remain marked future ref-to-symbol work; identity-less component validation remains described as missing; §5 preserves `:seon.error/kind`, retired AST rows and error EDN encoding. §9's disclaimer cannot correct contradictory instructions earlier. | guide §2.1, §2.5, §5.1–§5.3; HEAD schema `seon.fn.edn:31`, `seon.test.edn:2`, `db.clj:3659–3801`; A2/B3. | Replace stale status paragraphs; retain the native Datahike versus Seon guarantee distinction. Move migration inventories into their specs. | P1 |
| 20 · dependency citations | Flow timeout lines point to `:signal-select`; the prepl line is inside `prepl`, not the `io-prepl` entry. `:cache false` disables disk-cache use, not all name resolution. `commit-id` reads a supplied database value, not a branch head from storage. | reference-code tables; corrected source blocks below. | Fix the exact reading blocks and qualify their guarantees; add the pending core.async change and remove the withdrawn SCI change. | P1 |
| 21 · marking | Target prose precedes its label; readers are told every function is contracted, whole-view SSE exists, and merge/write-back happens before discovering those are targets. §3 also says Datahike replaces a database value inside the environment. | architecture §3, §8, §13; agent-runtime §1; ui §2; README marking rule. | Label the target before describing it. Database values are immutable; the connection advances and the caller carries the selected value. | P1 |
| 22 · length | Six main files are exactly 2,037 lines: 1,185 plus the 852-line guide. Counts, incident histories, deletion inventories, laws and vocabulary repeat specs/AGENTS; dead `data-model.md` links and “when the note lands” survive. | `wc -l`; guide:50,229,796–806; README final two sections; architecture Current blocks; decision records. | Keep mechanism, reason, data flow and reading links. Move numbers and deletion lists to specs; remove obsolete guide inventories and dated decision records from current architecture at the clean write. | P2 |
| 23 · interrupt guarantee | “Every fn body” omits the SCI/host boundary. The interrupt hook does not run automatically inside exposed host functions. | reference-code SCI row; `reference-code/sci/doc/interrupt.md`, host-function examples. | Qualify the hook as interpreted execution; do not use it as proof that arbitrary JVM work stops at the deadline. | P1 |

## Citation verification

Used `git grep -n -F` at the fixed HEAD above, then read the cited source blocks with `git show`. Sample: 61 distinct first-party locations (71 citation occurrences), including more than forty Current citations. Matching a declaration was not treated as proof of its accompanying claim; items 15–16 are examples that falsified the prose.

| HEAD file | Checked lines, representative Current blocks |
|---|---|
| `src/seon/cluster/store.clj`, `bootstrap.clj`, `cluster/registry.clj` | 306; 887; 178 |
| `src/seon/cluster/agent.clj`, `cluster/wake.clj`, `flow.clj`, `env.clj` | 469,497; 92; 1122; 81 |
| `src/seon/db.clj`, `schema.clj`, `instrument.clj` | 1102,1219,4578; 2788; 844,860,927 |
| `src/seon/turn.clj` | 465,1704,2103,2286,2980; additionally 395 to correct the opening citation |
| `src/seon/sci/eval.clj` | 695,1565,2184,2218,3181 |
| `src/seon/error.clj`, `error/refusal.clj`, `issue.clj`, `id.clj` | 200,1683; 37; 554; 55 |
| `src/seon/test.clj`, `test/runner.clj`, `program.cljc`, `src/my/program.clj` | 1901; 3145; 1042; 278 |
| `src/seon/repl.clj`, `eval.clj`, `render/walk.clj`, `render.clj` | 213,256,447; 9; 979; 278 |
| `src/seon/render/block.clj`, `render/route.clj`, `render/ns.clj`, `render/web.clj` | 61; 5; 793,895; 1849,3217 |
| `resources/seon/schemas/seon.fn.edn`, `seon.ns.edn`, `seon.eval.edn`, `seon.cluster.eval.edn` | 179; 28; 31; 62 |

The two explicitly working-tree citations are not HEAD declaration starts: `cluster.clj:2174` points inside `refresh-source!` (HEAD starts at 2158); `fn.clj:3314` points inside `index!` (HEAD starts at 3289). Preserve the distinction rather than relabeling these as HEAD evidence. The documents' old `215447c46` verification statement must be dated evidence, not a moving HEAD claim.

Opened all 82 numeric citation occurrences in reference-code.md, including inherited `:line` entries, and SCI's unnumbered interrupt document. Checked all twenty repositories with `git -C reference-code/<name> log -1` and compared the gitlinks. All twenty pins match the table:

| Repositories | Verified commits |
|---|---|
| datahike · sci · malli · clj-kondo · http-kit | `006e634a` · `fcbd8862` · `606083c5` · `57252e07` · `238a85c` |
| datastar-clojure · core.async.flow-monitor · editscript · edamame · rewrite-clj | `1cef624` · `fbff842` · `b493ccf` · `63373df` · `60782e5` |
| babashka-process · babashka · core.async · konserve · clojure | `16a84e0` · `0fb349c4` · `dc35f3e` · `07377c2` · `b18d3adc` |
| hyperlith · clj-reload · kaocha · clojurescript · langchain4clj | `b08a8e8` · `61c6fa7` · `8846f91` · `946d75f` · `889f9e6` |

Minor reading-range corrections: `impl/dispatch.clj:98` starts `executor-for` (97 is blank); `clojure/test.clj:326` starts the reporting `defmulti` (325 is blank); kaocha `api.clj:35–37` contains the whole reporter macro. The twelve/eight split means locally linked checkouts versus reference checkouts, not runtime versus non-runtime libraries: Clojure, core.async and konserve still execute in Seon.

## Exact replacement text

These are replacements for the named paragraphs, not instructions to copy this review into architecture. Existing accurate Current evidence can remain, shortened to the owning entry point. Pending decisions below deliberately remain pending: prose cannot reconcile incompatible authorities by assertion.

### README: replace the marking introduction and repeated census/vocabulary paragraphs

> These pages describe the target and identify its current implementation separately. Each numbered mechanism section gives its reason, data flow, current boundary, and direct links to its implementation spec and dependency source. A target label precedes behavior not yet implemented. Current citations name their verified commit; working-tree citations are marked separately. The plan owns implementation order, deletion inventories and measurements; AGENTS.md owns the laws and vocabulary. Historical decisions are read through Git.

### architecture §0–§13: append the corresponding “Implementation” line

Each row below is the exact content of that section's reading line; expand the dependency filenames already in the section to the full blocks indicated here. The PRD links are the implementation detail; reference-code.md remains the shared dependency index.

| Section | Implementation and dependency blocks to hold in context |
|---|---|
| §0 | **Implementation:** [plan](../../prds/agent-platform/plan/README.md) §4; [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) §2. **Reference code:** `reference-code/malli/src/malli/core.cljc:268–277` and `reference-code/datahike/src/datahike/versioning.cljc:212–277`. |
| §1 | **Implementation:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2d; [A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md). **Reference code:** `reference-code/datahike/src/datahike/writer.cljc:42–119`; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82–116`. |
| §2 | **Implementation:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2b/§2d; [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) §2c. **Reference code:** `reference-code/clojure/src/clj/clojure/core/server.clj:275`; `reference-code/datahike/src/datahike/versioning.cljc:212–277`. |
| §3 | **Implementation:** [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) §2b; [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) §2c. **Reference code:** `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:265–288`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243–261`. |
| §4 | **Implementation:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2g. **Reference code:** `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:87–115`. |
| §5 | **Implementation:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2a, subject to the publication-order decision below. **Reference code:** `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:23–171`; `reference-code/datahike/src/datahike/writer.cljc:385–409`. |
| §6 | **Implementation:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2b/§2d; [A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md). **Reference code:** `reference-code/datahike/src/datahike/versioning.cljc:212–277,550`; `reference-code/datahike/src/datahike/gc.cljc:83`; `reference-code/konserve/src/konserve/gc.cljc:8`. |
| §7 | **Implementation:** [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md). **Reference code:** `reference-code/malli/src/malli/registry.cljc:11–22,97`; `reference-code/malli/src/malli/core.cljc:268,345`. |
| §8 | **Implementation:** [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) §2; [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2a step 13. **Reference code:** `reference-code/malli/src/malli/core.cljc:2193–2296,3118–3139`; `reference-code/sci/src/sci/impl/utils.cljc:362–379`. |
| §9 | **Implementation:** [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) §2a, subject to the storage decision below. **Reference code:** `reference-code/malli/src/malli/core.cljc:996,2659`; `reference-code/malli/src/malli/error.cljc:44,288,374`. |
| §10 | **Implementation:** [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) §2b. **Reference code:** `reference-code/datahike/src/datahike/db/transaction.cljc:641–715,1153` (identity upsert and transaction functions). |
| §11 | **Implementation:** [B4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md) §2/§6/§8. **Reference code:** `reference-code/clojure/src/clj/clojure/test.clj:326,710`; `reference-code/kaocha/src/kaocha/type/var.clj:30–63`. |
| §12 | **Implementation:** [C1](../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md) §2/§3/§5; [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) §2g for the digest. **Reference code:** C1 §3's OpenJDK `LongAdder`/`LongAccumulator` blocks; `reference-code/datahike/src/datahike/db/transaction.cljc:1153`. |
| §13 | **Implementation:** [plan](../../prds/agent-platform/plan/README.md) §4 D1; its detailed spec is pending. **Reference code:** `reference-code/datahike/src/datahike/versioning.cljc:212,734`; `reference-code/sci/src/sci/core.cljc:345–350`; `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:34–42`. |

### architecture: replace the following mechanism/data-flow paragraphs

§0, add after the explanation of unnecessary work:

> **Data flow.** On an edit, changed file bytes become changed declaration facts. Those facts select dependent schemas, callers and namespaces; the resulting values travel into publication and adoption. Unchanged definitions keep their compiled schemas and wrappers. Initial construction visits the program once; later work follows the changed declarations and their required dependents. The lane specs name the dependency operations that replace each whole-program reconstruction.

§3, replace Data flow and the target launcher sentence:

> **Target data flow.** Boot constructs an environment and hands it to running code. Database values remain immutable; the connection supplies the newer value carried into the next operation. A wake signals an agent's graph, which derives work from that agent's facts. B2 keeps the paid turn on `:io` and extends flow's existing timeout support to that workload. A proc timeout reports a late transform; it does not cancel it. SCI and provider operations retain their execution deadlines. The core.async change is pending.

§4 and §13, replace the negative span claims:

> **Current.** Function rows already carry file references and optional half-open UTF-8 form spans (`resources/seon/schemas/seon.fn.edn:9,123`; `src/seon/fn.clj:605–662`). Their existence does not establish the target gated merge and write-back path. B1 supplies a definition digest over source and namespace aliases; D1 owns accepted write-back and its indexing round trip.

§5, replace the opening and Data flow:

> **Target.** One request in the hosting JVM observes changed paths, derives declaration differences and affected callers, publishes admitted facts, then adopts the affected namespaces and wrappers. Source observation hashes supplied paths; a pathless request must inspect the recorded path set. An unchanged adoption compares commit IDs. Linting costs changed files plus the namespaces resolved from clj-kondo's cache and affected caller files; it is not a whole-program analysis.
>
> **Publication order is unresolved.** The goals note rules publication on an unpublished branch, caller selection from its report, findings, then head movement. B1 §2a instead derives callers from an immutable published value and prospective differences, then commits declarations and findings once on `current-src`. Both must preserve an unchanged published head on refusal. These are different algorithms; there is no open transaction that returns a final report and then accepts more caller findings. Resolve this difference before implementing §5.

§6, replace the final asymptotic assertion in Data flow:

> Forking an existing published database changes a branch pointer; it does not index source or copy the store. Reset from zero constructs the complete program. First base-context construction also visits its program; a complete view visits the data it displays. These full computations must not recur for an unrelated edit.

§7–§8, replace Data flow and the Malli report instruction:

> **Target data flow.** A1 carries compiled schemas and function contracts with their immutable projection. A schema change selects its reverse-reference closure and retains unaffected compiled nodes. B1 hands arming the reloaded namespace set; selection visits their interns, while wrapper replacement occurs only for changed contracts or referenced definitions. Initial arming visits the initial program. Calls use the acquired validator without rebuilding a projection or searching their arguments for one.
>
> Malli's `:report` callback must exit failed validation; returning an error value from it does not prevent the function body from running. The Seon boundary converts that validation failure into the declared flat error. See `reference-code/malli/src/malli/core.cljc:2207–2221`.

§9, replace Data flow:

> **Target data flow under B3.** The constructor returns a flat error. Recording derives its identity from declared evidence, renders the offending value through the value renderer, and stores the declared error datoms plus occurrence shown text and a result handle. The live object remains in the SCI context. Classification and rendering cost depend on the schemas examined and value rendered. **Storage decision pending:** B3 removes the complete error blob, while the goals note also carries a complete-blob ruling; reconcile that instruction before committing the storage shape.

§10, replace Data flow:

> **Target data flow.** A detector queries facts and returns subjects. The writer resolves each subject's task identity and either wakes its existing agent or creates the task and agent. Settlement checks that subject's detector or cited tests at the writer's database value. Routing and settlement need not scan all tasks. A full detector still costs the facts its query visits, even when it finds nothing; incremental detection must name the changed inputs it consumes.

§11, replace the target selection/recording description:

> **Target data flow.** B4 consolidates requests into `seon.test/run`; the shell and agent entry points call it. A request selects against its supplied program and recorded green basis, executes selected members serially on a branch and SCI fork, and records each member's evidence on the hosting cluster. Platform tests use their declared isolated host. **Selection decision pending:** B4 proposes observed reach after the first run because the static graph is saturated; the owner has not yet replaced the stored-call-graph rule. Unknown reach must remain explicit. A test omitted from an earlier observed path is not thereby proven irrelevant to changed code.

§12, replace opening and Data flow; remove the periodic-flush/schedule paragraph:

> **Target data flow.** Arming supplies cells for a function identity and B1's definition digest; calls add count, inclusive elapsed time and maximum duration under their connection custody. C1 drains the branch's cells into the existing turn-close transaction, not a periodic flush proc. The branch is where the facts are written, not another stored profile attribute. Host work remains host evidence. The small sampling probe measured roughly 40 ns additional work; total armed overhead and flush traversal cost still require their declared probes. Static call edges plus aggregate callee totals do not establish self time or the cost of a particular call chain.

§13, replace Data flow and final lane attribution:

> **Target data flow.** A task receives a branch from a specified commit and an SCI fork. Changed definitions since that basis form the proposed replacement. The merge gate tests the proposed combined program, including tests reaching changed definitions, and positively refuses a changed identity with no test coverage. Acceptance checks the tested shared head and identity conflicts; conflicts become tasks for root. Accepted definitions are written through their file spans and checked by the ordinary indexer. Merge cost includes conflict checks and the selected tests, not merely changed entity count. D1 in the plan owns this integration; its detailed spec is pending.

### agent-runtime: replace §1's target and §2's opening citation

> **Target boundary.** Reuse an agent's context while its acquired program is unchanged and arm program definitions at the base. B2 proposes a new `sci/fork` plus reinterning private Vars only when the base changes; the goals note instead describes interning base differences into the retained fork. Resolve that difference explicitly. B2 withdraws the proposed SCI fork patch. The binding rule remains definition-digest equality, not authorship: a cluster may use loaded behavior only when it represents that cluster's definition. B2's live JVM Var proposal must also preserve older ordinary clusters across another cluster's adoption.
>
> **Current opening.** `src/seon/turn.clj:395` `open-call` makes the one-open-turn decision inside the writer and returns empty transaction data when a turn is already open. `:465` `open-run-tx-call` conditionally emits settlement data for a still-open turn; it is not the opening operation.

### ui: replace §2's first/data-flow paragraphs and §3's pull example

> **Target delivery.** Each tab taps the refresh mult through a dropping buffer and retains its last database value and read evidence. A signal prompts a currency check; unchanged reads skip rendering. Changed reads produce the complete current view, streamed through the HTTP owner and morphed by Datastar. A new tab renders on connection. Waiting and CPU rendering are distinct work; hyperlith's renderer uses its CPU pool. Work is per-tab currency checks plus rendering and transmitting the changed views, including whatever history those views contain. No separate revisioned package or retained delta is needed.
>
> **Pull boundary.** The current Datahike default silently limits cardinality-many pulls to 1,000. A2 changes the default to complete results and requires resource-bound failures to be explicit. A deliberate query-work window names its bound and continuation; HTML adds no presentation clipping.

### reference-code: exact corrected row text and fork paragraph

> `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:265–288` documents `:compute-timeout-ms`; `flow/impl.clj:257–261` implements it for `:compute`. Its Future wait reports timeout without cancellation. B2 proposes extending the same mechanism to the turn's `:io` workload.
>
> `reference-code/clojure/src/clj/clojure/core/server.clj:275` is `io-prepl`; `:228` is inside the underlying `prepl` loop. Open both when changing the transport.
>
> `reference-code/sci/doc/interrupt.md`: `:interrupt-fn` runs at interpreted function and `loop/recur` body entries. Exposed host functions do not automatically consult it. The evaluation deadline therefore does not establish interruption of arbitrary JVM work.
>
> `reference-code/clj-kondo/src/clj_kondo/core.clj:67–107,143,242–262`: `:cache false` disables cache use; it does not disable resolution among definitions analyzed in the invocation. Cached dependency resolution requires the intended cache directory.
>
> `reference-code/datahike/src/datahike/versioning.cljc:457–461` reads the commit ID of the supplied database value. A branch-head lookup is a different operation; A2 proposes exporting `branch-commit-id`. `query.cljc:2963–2976` compares attribute revisions only for a non-`:all` dependency set; `:all` requires the conservative behavior specified by A2.
>
> Fork changes are targets owned by their lane specs: A2 owns Datahike and konserve, B1 owns clj-kondo, A1 owns Malli, and B2 owns core.async timeout support. B2 withdraws the SCI generation patch because arming at the base is a Seon change using the existing SCI operations. Read the lane's current fork section rather than the earlier synthesis as an implementation checklist.

### data-modeling-guide: replace stale status paragraphs; remove obsolete inventories

> **Current value edges (§2.1).** Calls and recorded reach already store indexed qualified-symbol values (`resources/seon/schemas/seon.fn.edn:31`; `seon.test.edn:2`). They survive deletion of the named declaration; deletion admission separately checks surviving referrers. Keep the reverse-traversal index when changing an edge's representation.
>
> **Current component validation (§2.5).** Datahike cascades component deletion but does not establish unique ownership. Seon's writer validates complete owning values, including identity-less children declared with `:seon.db/component-schema`, and discovers owners before and after edits (`src/seon/db.clj:3659–3801`). A2 owns any narrowing of that validation. Do not describe the already implemented ownership check as pending work.
>
> **Classification (§5.1).** An entity is selected by its attributes and relations. Keep a bounded enum only when the declaration describes a genuine state or dependency grammar. There is no permanent keep-list: B3 deletes `:seon.error/kind`, and the retired `seon.fn.ast` family is not a current example.
>
> **Stored values (§5.3).** A2 owns replacing EDN codecs with admitted native values; B3 owns the error occurrence's shown text and result handle. Do not preserve `:seon.error/data-edn` by renaming it. The conflicting complete-blob instruction remains an explicit storage decision in architecture §9.

Delete the superseded migration inventories and “when the note lands” checklist; link A2/B3 for implementation status. Replace dead `data-model.md` links with the owning current section. Retain the dependency-specific guidance on refs versus values, required refs, component ownership, tuples, absent collection events, history and purge. Those distinctions justify this guide; repeated ruling histories and lists of old defects do not.

## Verdict

The rewrite explains the central simplification well: stop reconstructing facts and compiled values that the dependencies already maintain, and make later work follow actual changes. It is not ready to serve as the sole implementation guide: several passages revive mechanisms their specs remove, a few Current claims are false, and the specs themselves disagree on publication order, SCI binding and context updates, error storage, reach selection and profiling inference. Correct those boundaries, add the fourteen direct reading lines, and shorten the directory by removing duplicate evidence and migration history rather than removing the explanation of why the smaller algorithm works.

[plan]: ../../prds/agent-platform/plan/README.md
[a1]: ../../prds/agent-platform/plan/lane-a1-projection-carried.md
[a2]: ../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md
[b1]: ../../prds/agent-platform/plan/lane-b1-one-publication-path.md
[b2]: ../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md
[b3]: ../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md
[b4]: ../../prds/agent-platform/plan/lane-b4-tests-in-process.md
[c1]: ../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md
