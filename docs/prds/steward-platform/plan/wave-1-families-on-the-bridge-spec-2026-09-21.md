---
type: plan
status: launch specifications; wait for bridge steps 1–2 and held-path release
created: 2026-09-21
tags: [plan, schema, malli, datahike, test, namespace-agents, deletion, config]
---

# Wave 1 — declare the remaining families on the bridge

**Guarantee:** these four assignments add declarations and strengthen the
existing writers and readers. They consume one sealed Malli generation and
one compiled storage bridge. No family acquires a compiler, registry, writer,
or independent validation path. Required evidence survives as evidence;
missing evidence is unknown; deletion has an explicit consequence.

These are verbatim launch assignments, including the common contract below
and the tables following each launch paragraph. They follow the shape of
[bridge step 3](bridge-step3-stamp-spec-2026-09-21.md). The launch prerequisite
is the coherent retirement/caller landing of **bridge steps 1–2**, per
[Malli-native bridge PRD §§2–3, §6(3)](malli-native-bridge-prd-2026-09-20.md),
not a dirty implementation or a progress note. Step 3 is not an additional
family prerequisite, but its held acquisition paths still require release.

Read end to end for this design: [namespace plan](namespace-agents-plan-2026-09-19.md)
(in particular §2 and rulings §§3/6/8), [audit A](../research/schema-audit-a-2026-09-19.md),
[audit B](../research/schema-audit-b-2026-09-19.md),
[audit C](../research/schema-audit-c-2026-09-19.md),
[config/plan 1d](../research/config-plan-family-1d-2026-09-20.md),
[error 1a](../research/error-family-1a-2026-09-19.md),
[results reuse](../research/results-reuse-everywhere-2026-09-20.md),
[wave 3a](wave-3a-task-family-spec-2026-09-21.md), and the step-3 specification.
AGENTS §§2–3 and lane rules 11–16 and the requested bridge sections were also
read. Current declarations, recorder/adoption seams, dependency sources and
[the working edge](unsettled.md)'s 2026-09-21 checkpoint were inspected.

The dated source baseline is `d6f5b7bf9` plus concurrent working-tree edits.
Line anchors below are inspection coordinates, not proof of installed facts.
Every resource path abbreviated as `schemas/X.edn` means
`resources/seon/schemas/X.edn`. Refresh coordinates and inventory at launch.

## Common launch contract — include with every assignment

> Work in /Users/sean/src/seon, branch steward-platform. Read your complete
> section, this common contract, the binding documents it names, AGENTS
> §§2–3 and lane rules 11–16, the current working edge, and the
> data-oriented-clojure, data-modeling, datahike, repl and clojure-testing
> skills before edits. Read the actual bridge step-1/2 landing notes and
> inspect their retained-schema APIs. Families supply ordinary canonical
> forms; use the supplied sealed registry's retained named schemas and
> compiled navigation. No family-local `m/schema`/`m/validator` compilation,
> raw-form storage walker, fallback registry, mutable registry or global
> cache. A predicate declared in forms is an ordinary contracted function,
> not permission for a second schema engine.
>
> You are not alone in the codebase. Preserve unrelated edits and adapt to
> landed work. Execute this bounded assignment directly, without delegation.
> Own the resources, consumers, tests and documentation explicitly named in
> your section; refresh the actual caller census before public retirement.
> Coordinate held paths through the orchestrator BEFORE editing. Never
> operate, resume, message or repair another lane's session. A resource and
> its loaded consumers land in one publication; no intermediate resource
> rename with old loaded writers. Every public retirement includes every
> caller in the same loadable slice. No compatibility namespace or alias
> kept to postpone a conversion.
>
> Begin implementation with the running default and the smallest read-only
> evidence, subject to the active publication pause. Report missing or
> degraded MCP tools immediately and record the exact boundary. Never
> restart, refork or stop default. Incompatible schemas are RESET NEEDED,
> recorded with the commit for the orchestrator's one batch. Only an
> assignment-authorized scratch cluster may provide isolated reset proof;
> clean it through the operator and remove its own exhaust before reporting.
> Distinguish fixture, hot-reloaded Var, fresh fork and in-place adoption
> evidence; browser paint requires observation.
>
> Iterate foreground, one JVM at a time, with
> `bin/test-fast --paths <all owned changed files> -- <named namespaces>`.
> No cold gates, `--all`, `--full`, worktrees, slot/silence overrides or
> overlapping JVMs. Foreign breakage does not authorize foreign repair:
> exclude foreign edits using the snapshot; if snapshot admission itself
> refuses, report its exact path and continue independent owned work. Do
> not claim a missing or unexecuted subject passed. The orchestrator owns
> `bin/test --paths … -- …` and `bin/test --platform` after landing.
>
> Canonical fixtures only: `with-database`, explicit projection/environment,
> `program-fn-row`, `apply-config!`, `seed-cluster!`, `transacted!`, real SCI,
> armed contracts, fixed render profiles, bounded exact-event awaits and
> scoped cleanup. Test map writes, expanded transaction functions, direct
> datom changes and incoming-ref sweeps against the FINAL report; a
> constructor-only check is insufficient. Verify refusal detail AND no
> committed mutation. Do not mock the database or hand-roster schema.
>
> Before a code commit, prove the changed namespaces load in the selected
> coherent tree, serially with other verification. Use path-limited commits
> (`git commit --only -- <explicit paths>`), never broad staging or resets.
> Record exact commands/tallies, changed attributes, reset membership,
> deleted mechanisms, unresolved boundaries and live proof in your named
> landing note. Update affected authority in the same slice; use existing
> issue notes for out-of-scope defects. Stop after the coherent assignment
> lands, or at a real new decision with exactly three priced options,
> simplest viable constraint first and recommended. Do not widen the design
> to solve a hypothetical problem.

### Held-path coordination — dated, refresh before launch

| Running lane | Files that must be released before a family edits them |
|---|---|
| `bridge-step2-walker` | `src/seon/schema.clj`, `src/seon/schema/internal.cljc`, `src/seon/schema/datahike.clj`, retiring `src/seon/schema/form.cljc`; compiled-walker callers `src/seon/db.clj`, `src/seon/render.clj`, `src/seon/render/value.clj`, `src/seon/render/walk.clj`, `src/seon/agent.clj`, `src/seon/issue.clj`; `test/seon/schema_test.clj`, `test/seon/schema/datahike_test.clj`, `test/seon/schema/datahike_parity.edn`, `test/seon/db_test.clj`, `test/seon/instrument_test.clj`, `test/seon/flow_test.clj`. |
| `publication-dissolution` | `src/seon/cluster/source.clj`, publication regions of `src/seon/cluster.clj`, `src/seon/fn.clj`, `src/seon/test/cache.clj`, `script/seon/fresh_operator.clj`, `bin/test`, `bin/seon-hook`, `schemas/seon.source.edn`, `schemas/seon.fn.finding.edn`, `test/seon/cluster/source_test.clj`, `test/seon/cluster/boot_test.clj`, `test/seon/cluster/publication_findings_test.clj` and its publication regressions. |
| `error-family-1a` | `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/instrument.clj`, `src/seon/sci/admit.clj`, `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`, `src/seon/db.clj`, `schemas/seon.error*.edn`, `schemas/seon.instrument*.edn`, `schemas/seon.db.edn`, `schemas/seon.db.write.edn`, `schemas/seon.sci.eval.edn`; error/instrument/db/SCI/schema tests and shared fixture consumers. At the final inspection `src/seon/turn.clj` and `schemas/seon.turn.edn` were also dirty: neither is free merely because an earlier ownership list omitted it. |

No row grants the designer ownership of these files. The future implementer
checks the orchestrator's release and current diff. Step 3/4/5, C4, 1b and 1c
also overlap schema/db/instrument owners; serialize those cuts. Do not launch
1e beside resource editing: its census is of the landed population. At most
three editing lanes; estimates below are lane time, not permission for overlap.

### Dependency ledger — common proof, no family substitutes

| Dependency mechanism | Read seam | First-party consumer and obligation |
|---|---|---|
| Malli accepts an already compiled schema without recompiling; properties/children/entries navigate compiled objects | `reference-code/malli/src/malli/core.cljc:2550`, `:2581`, `:2595`, `:2771`, `:2818` | Landed `src/seon/schema.clj` registry and `src/seon/schema/internal.cljc` navigation. Preserve names before dereference, entry optionality and inherited properties. |
| Incoming-ref sweep and component cascade | `reference-code/datahike/src/datahike/db/transaction.cljc:831`, `:998` | `src/seon/db.clj` final owning-value validation. Test surviving referrers and same-transaction repairs, not just the submitted root. |
| Final callback sees effective and attempted datoms; non-nil refuses before commit | `reference-code/datahike/src/datahike/db/transaction.cljc:1206` | Existing `write-report-validator` (`src/seon/db.clj:4104` at inspection). All relation predicates extend this seam. |
| Cardinality-many empty means no datoms; indexed values survive target deletion | Same transaction owner; AGENTS §3 | Optional many collections plus a positive observation transaction; never a required nonempty set to prove that an empty observation happened. |
| Pull has a default many limit | `reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323` | `seon.db` bounded complete entity reads, not wildcard pull as proof of complete children. Derived pulled schemas use the reader's actual selector. |

## 1b — test evidence family

**Guarantee:** a durable run states its actual authority and selected scope,
including an observed empty scope. One immutable member/report history answers
results, reuse and failure queries; legacy aggregates cannot contradict it.
Missing adoption observation is unknown, not an empty green selection.

### Launch verbatim on astra low — after test-system handover and bridge 1–2

> Implement **wave 1b only**, under the common launch contract above. Read
> audit C C1/C2/C6/C7/C8/C12/C13/C14 and R1, the namespace plan's 1b row,
> results-reuse-everywhere and error-family-1a end to end. The older audit's
> suggested extra run machinery is superseded by the recorded test system.
> Preserve fresh request IDs, selection scope, covered members, immutable
> claims/completion, snapshot provenance, confidence and reuse through the
> SAME selector, recorder and reader. Declare only missing evidence.
>
> Own `schemas/seon.test.edn`, `seon.test.run.edn`, `seon.test.member.edn`,
> `seon.test.failure.edn`, `seon.test.report.edn`, `seon.test.accretion.edn`,
> `seon.test.accretion.auto.edn` and necessary group/failure component
> declarations; `src/seon/test.clj`, `src/seon/test/runner.clj`,
> `src/seon/test/selection.clj`, `src/seon/test/accretion.clj`,
> `src/seon/render/test.clj`, the released adoption-recording seam in
> `src/seon/cluster.clj`, legacy result readers in `src/seon/fn.clj` and
> `src/seon/bootstrap.clj`, and the final-report relations in
> `src/seon/db.clj`. Own their discovered result callers, the regression
> files below, and `docs/prds/steward-platform/research/wave-1b-test-evidence-2026-09-21.md`.
> Do not touch publication/cache/launcher mechanics to redesign testing.
>
> Tighten the stored declarations in the table. Preserve zero-member runs;
> do not demand a cluster on a snapshot run or require a many attribute
> merely to encode an empty set. Declare adoption observation separately
> from its possibly empty changed identities/paths. Store observed program
> identities as identity values, not refs swept when the program changes.
>
> Consolidate failure storage at `:seon.test.failure/failure`: use the
> immutable content presently declared by `:seon.test.report/report`.
> Retain existing report identity keys and their identity derivation;
> retire the mutable aggregate and the duplicate stored map together with
> every writer/reader. There is exactly one stored failure shape at exit,
> not a fourth entity or a second recorder. Keep a raw clojure.test event
> input grammar only where it describes an actual different boundary.
>
> Extend final-report validation for completed evidence and accretion
> consistency. Test the raw writer as well as the ordinary recorder.
> Reuse 1a's declared test/run/runner/accretion facets; no observation-id,
> general error predicate, new error root, kind marker or copied facet.
> Report RESET NEEDED and the cold/live proofs owed. Stop at landing.

### Exact declaration delta

R = required in the named stored shape; O = optional there. Event-dependent
requirements below are relations on the final owning value, not blanket R
on members that have not yet been claimed or completed. Cardinalities are
native Datahike cardinalities; a coded scalar is one even when logically a
structured value. Quoted sentences are the lifecycle/docstring addition.

| Attribute / current anchor | Target type, cardinality, presence and exact meaning |
|---|---|
| `:seon.test.run/{id,at,program-digest,basis-t,input-digest,policy,include-long?,branch,selection-tx}` — `schemas/seon.test.run.edn:1`, `:3`, `:4`, `:5`, `:26`, `:33`, `:34`, `:37`, `:38`; stored map `:98` | Keep current scalar types, one each; make all R in `/run`. `selection-tx` is R ref, refuse: "Transaction admitting the complete selection, including zero members. A surviving run requires this authority; deletion of its transaction refuses." Program/input digests and tested basis are observations, never identities or recomputed from a later publication. `include-long? false` is an explicitly asserted request decision. |
| `:seon.test.run/{cluster,tested-branch,published-base-digest,overlay-input-digest}` — `seon.test.run.edn:6`, `:7`, `:27`, `:28`, map `:101` | Retain types, one; conditional authority. In-process run requires a current cluster ref (refuse while run survives). Isolated snapshot requires tested branch and both snapshot digests; cluster may be absent. Every run still carries recording destination `/branch`. Do not invent a host discriminator: derive snapshot mode from the paired provenance fields; reject a half-pair. If a cluster link is supplied, require its target in the final value. Doc: "Execution authority: a live cluster, or the complete recorded snapshot provenance. Missing both is unknown evidence and refuses a new run." |
| `:seon.test.run/{members,exclusions}` — `seon.test.run.edn:30`, `:32` | Keep O set/component refs, many, cascade to `/member` and `/exclusion`; empty is valid. Doc: "Owned selection observations; parent deletion cascades. Selection-tx records that selection occurred even when there are no members." |
| `:seon.test.run/covered-by` — `seon.test.run.edn:31` | O set peer refs, many; preserve completed-run immutability. Doc: "Accepted prior member evidence. A covered target cannot be removed while a surviving run relies on it; retract dependent runs in the same transaction first." Final relation checks before/after membership so sweeping the LAST ref cannot turn coverage into an empty successful request. No new coverage count mirror. |
| `:seon.test.member/{symbol,reasons}` — `seon.test.member.edn:1`, `:3` | Keep R qualified-symbol and R nonempty reason set. Symbol is an observed value; test deletion does not delete recorded evidence. Existing closed reason enum stays. |
| `:seon.test.member/{worker,claim-tx,host,claimed-at}` — `seon.test.member.edn:6`–`:9` | O on an unclaimed member; all present together on a claim. Worker/claim-tx one ref; host current enum; claimed-at external observation instant. Doc on refs: "Required authority of an existing claim; removing the target while this member survives refuses. No claim may become unclaimed by sweep." Existing immutability/claim writer checks remain, including exact claim completion and PID/start-instant custody. |
| `:seon.test.member/{completed-tx,pass-count,fail-count,error-count,began?,ended?}` — `seon.test.member.edn:10`–`:15` | Keep one completion ref and nonnegative counts. Completion requires all three counts and BOTH boolean observations. No counts without completion. Keep booleans as explicit terminal reporter observations: "Whether this completed execution's reporter observed the event; false means observed missing, not never checked." Do NOT replace them with fabricated transaction times: clojure.test begin/end currently happen outside database transactions. Completed-tx doc: "Transaction accepting complete terminal evidence; a surviving completed member cannot lose this fact." Retraction and mutation are refused by the existing immutable evidence owner. |
| `:seon.test.member/terminated-tx` — `seon.test.member.edn:16` | O one ref; observed termination only, never inferred from timeout or completion. Doc: "Transaction observing termination. Absence is unobserved termination; a recorded termination cannot be retracted from a surviving immutable member." |
| `:seon.test.member/{failures,error}` — `seon.test.member.edn:17`, `:18` | O many failure refs, O one error ref; peer, not component (reports can be shared). Doc: "Observed terminal evidence; deleting a referenced report/error while a surviving completed member relies on it refuses." Counts and referenced failures must agree with recorded failing/error reports. Preserve a specific boundary error when normal completion failed. |
| NEW `:seon.test/adoption-observed-tx` at `seon.test.edn:285`–`:292` | `[:and {:description "Transaction observing the complete adoption delta, including empty identities and paths. Required authority; missing means adoption unobserved, never unchanged."} :seon.db/ref]`; one, R in `/adoption`, refuse. Record it on the adoption transaction as its own transaction ref through the ordinary transaction grammar. No extra adoption entity. |
| `:seon.test/adoption-identities` — `seon.test.edn:285` | O `[:set [:tuple :seon.program/identity-attribute [:or :symbol :qualified-keyword]]]`, many encoded identity-pair values, never Datahike refs. Keep the **[identity-attribute value] pair** returned by `seon.program/row-identity` (`src/seon/program.cljc:305`); a namespace symbol alone loses its family. The writer validates the value against the named identity attribute using the same supplied registry, and records only the declaration identities already selected by adoption. Doc: "Identity-attribute/value pairs observed in this adoption delta. Program deletion cannot erase the observation; adoption-observed-tx distinguishes an observed empty set." No eid or lookup resolution. These tuples resemble lookup refs syntactically but their declared storage is value data; round-trip proves they never resolve as refs. |
| `:seon.test/{adoption-cluster,adoption-inputs}` — `seon.test.edn:286`–`:292` | Keep R one cluster ref (refuse), O many path strings; observed-tx owns whether an empty delta was observed. Cluster does not prove the delta was inspected. `check-adoption` requires observed-tx; observed empty delta is a valid zero-change selection; no marker is typed unknown. This corrects the older plan shorthand that every empty set is unknown. |
| `:seon.test.failure/failure` — `seon.test.failure.edn:35`; `:seon.test.report/report` — `seon.test.report.edn:6` | Move the latter's complete immutable stored map definition to the former; remove `/report` stored map. Required keys stay `:seon.test.report/id`, `/symbol`, `:seon.test/failure-identity`, `:seon.test.failure/type`; preserve all optional evidence fields and exact content identity. `seon.test.report.edn:1`, `:4` retain only their scalar identity/name declarations, not another entity schema. Do not reinterpret old `:seon.test.failure/id` as immutable content identity. |
| Legacy `:seon.test.failure/{id,test,ordinal,file,first-run,last-run,seen-count,last-seen-at}` — `seon.test.failure.edn:2`, `:3`, `:5`, `:14`, `:18`–`:21` | Remove from stored aggregate and retire keys whose callers disappear. Observed file survives as existing `/reported-file` string (`:22`), not a ref; ordinal is raw event information if a caller needs it, not failure identity. First/last/count/time derive by joining members to their run/completion facts. Retain `/type`, evidence payload fields and signature; no re-encoding of blobs. |
| `:seon.test.failure/value`, `/report` — `seon.test.failure.edn:58`, `:23`; `/run/provenance` — `seon.test.run.edn:83`; `:seon.test/result` — `seon.test.edn:55` | Delete pulled-shape mirrors. Derive actual read projections from the one stored shape under each selector. The raw reporter input remains only if its contract is an actual event grammar, with its non-entity meaning stated. Do not alias raw input to stored failure and accidentally require report identity before recording. |
| Stored `:seon.test/{failures,pass-count,fail-count,error-count,run-basis-t,run-at,run,failing-assertions,failure-message}` — `seon.test.edn:27`–`:41`, map `:89`, `:117`–`:140` | Remove test-row result storage and `written-by` declarations with the legacy `record-latest-tx`/aggregate writer. Preserve compatible transient result keys used by callers. Latest result, verified status and failure messages join immutable run/member/report evidence. Do not delete advisory reach observations or test declarations. Remove inert `/failing-assertions` cardinality property with its stored use. |
| `:seon.test.accretion/gate-tests` — `seon.test.accretion.edn:63` | Delete inert `:seon.db/cardinality`; declare `[:set :seon.db/ref]` if the stored value is the unordered gate membership it currently represents; O many peer refs. Doc: "Current gate test relations; optional sweep removes a deleted test. Historical executed membership is recorded by test runs, not this relation." Change vector writers/consumers together. |
| `:seon.test.accretion/{case-count,executed-count,status,skip-reason,test-count,pass-count,fail-count,gate-test-count,gate-pass-count,gate-fail-count}` — `seon.test.accretion.edn:9`, `:10`, `:12`, `:58`–`:67`; `seon.test.accretion.auto.edn:7`, `:10`, `:29`, `:44` | Preserve scalar types and meaning. Final relation: `0 <= executed <= cases`; passed requires positive executed work and no failing evidence; failed requires failure evidence; skipped requires a reason and zero executed; reported pass+fail cannot exceed reported total, and complete gate reports equal it. Distinguish test counts from assertion counts; never compare different units. Derive aggregate result views where members are authoritative; keep actual measured generated-case counts. Auto child relation validated from its owner, not standalone partial maps. |

### Per-file conversion and required proofs

`src/seon/test.clj:1249` admits runs, `:1427` reads recorded results and
`:1920` owns adoption checking. `src/seon/cluster.clj:2482` already selects
identity pairs, but `:2645` currently transacts them as refs. Write them as
the declared values and read them directly into `:seon.test/changed`; remove
the pull-to-row-to-identity reconstruction. `src/seon/test/runner.clj:2381` claims,
`:2605` currently enumerates report attributes from raw forms, `:2629`
builds immutable report identities, `:2697` commits completion, `:2705`
starts the legacy latest writer. Replace the raw report `drop` with landed
compiled entry navigation. Preserve identity inputs and admission/claim
atomicity. Trace the actual callers of the aggregate writer before deletion.
The results-reuse note's old fn/bootstrap callers are a work list to verify,
not evidence those paths still need conversion after another landing.

| Canonical regression owner | Required positive and negative examples |
|---|---|
| `test/seon/test_test.clj`, `test/seon/test/selection_test.clj` | No adoption marker → specific unknown; marker plus zero identities/paths → observed zero-change selection; marker plus deleted program identity still names the changed value. Live and snapshot run shapes accepted with their respective complete authority. Missing/half provenance refuses. Fully covered and excluded-only requests are real recorded requests with zero new execution. |
| `test/seon/test/runner_test.clj`, `test/seon/test_runner_test.clj` | Unclaimed, claimed, completed and termination-observed members are distinguishable. Partial claim, counts without completion, completion missing either reporter observation, report deletion, covered-member sweep and evidence mutation refuse atomically. Explicit false begin/end produces non-green evidence rather than an absent result. |
| Same recorder tests plus `test/seon/db_test.clj` | Same test/signature in two runs points to one immutable report; different content at the same identity refuses. Delete a program test and retain its historical result symbol/evidence. Raw datom writes and tx-function expansion cannot bypass these guarantees. |
| `test/seon/test/accretion_test.clj` | Owner-attached auto/group/failure components accept consistent positive work and explicit skips; contradictory counts/status/failure combinations refuse via the final writer, including partial child updates. No vacuous all-empty assertion. |
| Existing real SCI and both-host regressions | Run the same declared test through both actual custody paths; every requested member has recorded evidence or explicit unavailable status. A repeat request can execute zero with complete unchanged confidence. No second runner or fake host shim. |

**RESET NEEDED:** required run authority; adoption value/ref conversion and
new observation requirement; aggregate failure/test-row retirement; any
native cardinality change to gate-tests. New scalar properties alone do not
justify a separate reset. List precise native before/after and changed
stored keys in the landing note. **Estimate: 2–3 lane-days, astra low**;
this is recorder/authority consolidation, not a mechanical sol sweep.

Fast namespaces: `seon.test-test`, `seon.test.selection-test`,
`seon.test.runner-test`, `seon.test-runner-test`, `seon.test.accretion-test`,
`seon.db-test`, plus actual converted reader namespaces. Orchestrator cold
proof uses the identical owned overlay and platform tier. After the batch
reset, observe one real adoption delta and one run/member/report join on the
live cluster; identify the adopted publication. **Must not:** rebuild Track A,
add coverage/count mirrors, duplicate error facets, require a live cluster
on snapshot evidence, migrate old data, or treat a timer firing as termination.

## 1c — agent, namespace, turn and render declarations

**Guarantee:** responsibility is plural and separate from work routing; states
are derived from facts and identified observations, with explicit unknown
where current liveness has not been observed. Renderer names store as symbols;
rendered values use output fields. No stored dispatch hint decides a turn.

### Launch verbatim on astra low — after 1a, D2 prerequisite and held-path release

> Implement **wave 1c only**, under the common launch contract. Read audit
> A R3, audit B C4 (listener/renderer observation rows), audit C C3/C4/C5,
> C8/C10/C15, R2/R3 and §3 state rows 5/6/11/15, and namespace-plan D1/D2.
> Read the wave-3a spec end to end. Load seon-flow-architecture before
> touching lifecycle observation and datastar-web-ui before web consumers.
>
> **1c is the sole declaration/rename owner of `:seon.ns/agents`.** Own its
> plural assignment and responsibility readers, `my.agent` surface, and
> namespace-unassigned query. Wave 3a consumes the landed relation; its
> fallback permission to declare it is not used under this launch order.
> **3a owns D2 trigger→task→agent**, the `seon.task` rename, atomic
> task identity/start/update writer, and routing away from legacy steward
> delivery. 1c must not implement another task writer or broadcast to all
> namespace agents. D2's complete behavior proof remains on 3a, not claimed
> by the schema rename alone. **The orchestrator first lands 3a's bounded
> task-family/D2 routing prerequisite, excluding its fallback D1 rename.**
> That slice stops routing through the singular namespace relation while
> existing responsibility/context reads can still use the old declaration.
> Then 1c converts that declaration and all remaining readers together;
> 3a's plural opening/integration proof consumes the result. Include this
> partition in the 3a launch text. Do not edit its spec instead of making
> your own caller cut coherent, and do not retire the singular key while
> `error/commit-call` still uses it to choose a recipient.
>
> Own `schemas/seon.ns.edn`, `seon.agent.edn`, `my.agent.edn`,
> `seon.runtime.edn`, `seon.turn.edn`, `seon.turn.work.edn`,
> `seon.message.edn`, `seon.listen.edn`, `seon.render.edn`, `seon.eval.edn`,
> relevant request/result contracts in render/turn/evaluation resources;
> `src/seon/cluster/agent.clj`, `src/seon/agent.clj`, `src/my/agent.clj`,
> `src/seon/turn.clj`, `src/seon/cluster/message.clj`, `src/my/message.clj`,
> `src/seon/cluster/wake.clj`, responsibility-only seams in
> `src/seon/error.clj` and `src/seon/problems.clj`, `src/seon/render.clj`,
> `src/seon/render/ns.clj`, `src/seon/render/transcript.clj`,
> `src/seon/render/web.clj`, `src/seon/repl.clj`, `src/seon/bootstrap.clj`,
> `src/seon/bootstrap_drive.clj`, `src/seon/eval/drive.clj`, released
> render-pair indexing in `src/seon/fn.clj`/`src/seon/schema.clj`, and
> final listener/agent-retention relations in `src/seon/db.clj`. Include
> existing issue/start callers only to remove retired turn arguments, not
> to build D2. Retire old declarations with every discovered caller.
>
> Own the regressions below and
> `docs/prds/steward-platform/research/wave-1c-agent-namespace-turn-render-2026-09-21.md`.
> Keep transaction provenance on transactions. Obtain process identity
> from the opening/observation transaction, never copy a process ref onto
> a turn. Add only the lifecycle observations below at existing lifecycle
> transitions, not a heartbeat, new graph, monitor or scheduler. A stored
> last observation does not prove a process is alive now.
>
> Do not turn the transient `:seon.turn.work/situation` dispatch enum into
> a retirement of its callers: remove it from the stored turn shape only.
> Delete the two polymorphic stored trigger attributes and all arguments
> which only carry them. Preserve message trigger tokens where they mean
> a specific message, and preserve handled claims and wake basis semantics.
> Close render-pair declarations to qualified symbols with every output
> caller converted. Add no clipping. Record RESET NEEDED; stop at landing.

### Exact declaration delta and state contract

| Attribute / current anchor | Target, deletion behavior and docstring |
|---|---|
| Retire `:seon.ns/steward` — `schemas/seon.ns.edn:27`, `:34`; NEW `:seon.ns/agents` | `[:set {:description "Agents responsible for this namespace. Optional peer refs sweep on physical target deletion; ordinary agent removal is archival, which retains identity and this relation. Responsibility never routes a trigger; task identity does."} :seon.db/ref]`; O many, no identity/component/listen stamp. Replace writer metadata with the plural assignment owner. Agent retention below makes historical deletion distinguishable without a membership tombstone. |
| Retire `:my.agent/steward` — `my.agent.edn:3`, `:7`; NEW `:my.agent/agents` | `[:set :seon.agent/id]`; O transient value set, no stored copy. Doc: "Current namespace responsibility identities derived from seon.ns/agents; empty means currently unassigned." Rename `steward-of`→`agents-of`, `steward-call`→`assign-namespace-call`; assignment adds/removes requested memberships, never overwrites other agents. Empty returns a set, not an arbitrary first agent. |
| `:seon.agent/namespace` — `seon.agent.edn:84`; `/archived-tx` — `:1`; `/runtime` — `:2` | Keep O one peer namespace ref (sweep), O archive transaction ref and O runtime component (cascade). Doc namespace: "Current REPL namespace, independent of responsibility; deleting the namespace sweeps this optional relation." Agents archive and retain identity. Ordinary transaction deletion of a surviving agent identity refuses; branch/reset disposal remains operator custody. Archive event cannot disappear from a retained archived agent by target sweep. Do not make archival stop or disarm a graph. |
| `:seon.runtime/agent` — `seon.runtime.edn:1` | R one ref; remove `:seon.db/identity true`; retain indexed backlink only if queried. Doc: "Required agent owning this runtime through seon.agent/runtime. Parent deletion cascades the component; a surviving runtime requires its agent. This backlink is not an identity." Create/retrieve component through parent ownership, never upsert a runtime by backlink. |
| NEW `:seon.runtime/proc-phase`, `/proc-observed-tx` at `seon.runtime.edn:9` | O together on runtime. Phase `[:enum :armed :parked :stopped :failed]`, one keyword; transaction one ref. Doc phase: "Last phase observed by this agent graph's lifecycle owner; a closed lifecycle observation, not a claim of current liveness or an entity kind." Doc tx: "Transaction recording the phase; its seon.db/process identifies the process generation. Missing observation is unknown. A retained observation cannot lose its transaction." Final value requires both or neither and a resolvable process generation on an observation transaction. No new component or identity. |
| `:seon.turn/opened-tx`, `/closed-tx` — `seon.turn.edn:159`, `:160` | Keep one refs, opened R, closed O; no open? flag. Doc opened: "Opening transaction, carrying process provenance; required while the turn survives." Doc closed: "Settlement or boot-recovery transaction. Absence means open, not currently running; a recorded closure cannot be swept from a surviving turn." Refuse historical-event loss; compaction may remove the whole turn. |
| `:seon.message/assignment` — `seon.message.edn:34`; NEW `/assignment-disposition` at `/message`, `:79` | Preserve O one evaluation identity value. Add `[:enum {:description "Protocol action explicitly authored for this assignment: assigned or declined. Direction and reason text do not determine it; this is a bounded action, not a message kind."} :assigned :declined]`, one keyword, present iff assignment. Both values survive evaluation deletion. Request/decline writers provide it; malformed partial pairs refuse. Reason remains independent optional evidence. |
| `:seon.turn.work/situation` stored entry — `seon.turn.edn:27` | Delete only this stored entry. Keep `seon.turn.work.edn:57` `/next` dispatch and the final `/situation` enum as transient derivation. No new stored attribute replaces it. |
| `:seon.turn/trigger` — `seon.turn.edn:11`, `:45`, `:151`, `:158`; `:seon.runtime/trigger` — `seon.runtime.edn:7`, `:15`; `seon.agent.edn:137` | Retire declarations, stored entries, trigger-only arguments/writes/readers in one slice. Derive wake evidence through listened datoms and answering basis; `/handled` remains its distinct settlement claim. No generic trigger replacement attribute. |
| `:seon.listen/entity` — `seon.listen.edn:2`, `:7` | Keep O one peer ref, with final report refusal on losing an existing constraint while its pattern survives. Doc: "Optional authored entity constraint; omission at creation is an intentional wildcard. Deleting the target cannot turn a surviving constrained pattern into a wildcard: remove the pattern in the same transaction or supply its repaired constraint." This is conditional refuse, not a global change to required. |
| `:seon.render/ai`, `/html`, `/form` — `seon.render.edn:19`, `:170`, `:161` | All declaration properties become `:qualified-symbol`, one native symbol. Doc: "Declared render function for this output; produced values belong to rendered output fields." `/form` still names a currently callable form producer; closing its property type does not authorize deleting the live form stage. Move its old expression/entry/entries union to NEW transient `:seon.render/form-output`. `/rendered` (`:179`) becomes `[:or :string :seon.render/hiccup :seon.render/form-output]`. No stored output under pair keys. |
| `:seon.render/failure` — `seon.render.edn:151`; result uses of pair keys | Replace output entries with NEW `:seon.render/ai-output :string`, `/html-output :seon.render/hiccup` (transient, one logical value each), retaining db tx-data. Use `/rendered` for a single selected output. Open explicit-value render requests use these output fields; declaration fields remain names. Convert Hiccup/text/form result constructors, contracts and consumers together. No `(str sym)`/EDN round-trip for renderer names. |
| `:seon.eval/renderer-fn` — `seon.eval.edn:3`, `:30`, siblings in `seon.cluster.eval.edn` | Retire ref and ref→symbol round trips; use existing `/renderer` qualified-symbol (`seon.eval.edn:2`). Doc: "Observed renderer name; survives program deletion. Saved shown text is emitted unchanged without invoking it." Preserve historical source/shown text and origin tokens. |

State queries must report **last observed** and **currently verified**
separately. The existing opening transaction supplies process provenance.
A process-local check without an observation yields typed unknown, not dead
or alive. A known exited generation with an open turn is interrupted/crashed
pending recovery; boot closes it without resumption. A live generation plus
its graph's recorded armed/parked phase and current owner observation answers
that phase; an unavailable owner yields unknown. Record phase transitions at
existing arming, parking, stopping and fault-settlement events, carrying their
process and generation; piggyback on existing transaction boundaries where
available. No new per-turn polling or periodic liveness writes. A late event
from an older generation must not overwrite a newer observation.

For audit C §3 row 15: current namespace + no agents means currently
unassigned; history shows previous membership. A missing namespace is a
missing namespace, never an empty current assignment. Agents are retained by
archival; refuse ordinary agent identity retraction at the final writer so
it cannot silently erase responsibility. History distinguishes an intentional
unassignment from never assigned. Do not store a never-assigned flag.

### Required regressions and verification

| Owner | Proof |
|---|---|
| `test/seon/cluster/agent_test.clj`, `test/my/agent_test.clj` where present | Two retained agents share one namespace; plural assignment/retraction preserves the other member; current REPL namespace differs; archived member remains identifiable; physical identity retraction refuses, whole branch disposal is outside domain writes; missing namespace and unassigned namespace differ. |
| `test/seon/turn_test.clj`, `test/seon/cluster/turn_test.clj` | Recorded armed/parked/stopped/failed phases, absent observation, exited old process, live parked graph and delayed old-generation event have distinct honest answers. Boot recovery closes an open old turn without replay. Observe an actual graph, not a synthetic liveness boolean. Existing turn transaction-count regressions remain valid; explain any necessary lifecycle transaction separately. |
| `test/seon/cluster/message_test.clj` | Assignment and decline are explicit actions with the same value token; reverse direction alone cannot change action. Delete assigned evaluation and retain disposition/token. Half-pair direct writes refuse. Handling does not retract routing or decide answering. |
| `test/seon/cluster/wake_test.clj`, `test/seon/db_test.clj` | Delete listened entity alone → refusal; delete pattern and entity together → accepted; repair constraint in same final value → accepted; originally wildcard → still valid. Include implicit ref sweep, not only explicit pattern update. |
| Existing render/schema/repl tests | AI/HTML/form property datoms have native symbol type; explicit output values still render. Deleted renderer leaves old shown text byte-identical. Stored turns lack situation/trigger; transient next-work still advances every arm. No additional elision and no HTML clipping. |

**D2 integration handoff to 3a:** two namespace agents with distinct tasks;
second occurrence of A updates only A's task agent; new class creates B;
third occurrence starts nothing. 1c provides plural responsibility and the
listener invariant; 3a owns this routing proof. Its prerequisite landing
must remove namespace-based recipient selection before 1c retires the old
relation. At inspection `src/seon/error.clj:1496` (`steward`) feeds
`commit-call` at `:1537`; this is a concrete dependency, not just vocabulary.
Do not temporarily select the first plural agent, broadcast, add a fallback
alias, or return success with no recipient. 3a's final plural/context proof
is owed until both cuts land. This serial partition changes launch order,
not ownership: 1c declares D1 once; 3a declares tasks and implements D2 once.

**RESET NEEDED:** namespace ref→many rename, runtime identity removal,
render pair native string/EDN→symbol, output-key split, retired triggers and
renderer ref, new lifecycle/disposition required-pair relations. **Estimate:
3–4 lane-days, astra low.** Fast namespaces: `seon.cluster.agent-test`,
`seon.turn-test`, `seon.cluster.turn-test`, `seon.cluster.message-test`,
`seon.cluster.wake-test`, `seon.db-test`, `seon.schema-test`, and actual
render/repl caller tests. After orchestrator cold/platform and reset, observe
plural responsibility, an actual parked graph, missing observation and
rendered old history on default; inspect the page separately.

**Must not:** route by namespace membership, build D2 twice, add process
fields to turns, add a state taxonomy to agents, fake liveness with a fresh
stamp, restart default, change compaction/recovery semantics, delete the
transient next-work enum, or remove live form producers without their callers.

## C4 option 1 — declared properties, strict admission

**Guarantee:** every owned qualified schema property is declared and validated
at the one schema admission owner. Storable defaults really produce datoms.
Unknown properties cannot be silently dropped. Malli's genuine compile-time
properties retain their dependency-defined meaning.

### Launch verbatim on astra low — after 1a/1b and schema-owner release

> Implement **C4 option 1 only**, under the common launch contract. Read
> audit A C4 and config-plan-family-1d end to end, especially its six-property
> census and falsified `:any` default. Read 1a's final retired marker list.
> Owner selected extending the declaration cut, not a new property reader
> registry or a three-property-only partial fix.
>
> Own `schemas/seon.db.edn`, `seon.error.edn`, `seon.issue.edn`,
> `seon.schema.admission.edn`, `seon.config.edn`, NEW `seon.shell.edn`,
> `seon.render.edn`, remaining property use sites identified by the supplied
> population, `src/seon/schema.clj`, `src/seon/schema/datahike.clj` only at
> its existing property projection seam, and configuration/property
> consumers in `src/seon/config.clj`, `src/seon/schema/edn.clj`,
> `src/seon/agent.clj` as actually required. Own canonical schema/config
> regressions and
> `docs/prds/steward-platform/research/wave-1-c4-properties-2026-09-21.md`.
> Release with bridge, 1a, 1c and publication before editing. If 3a has
> already renamed issue, apply the cites declaration once to task; do not
> restore issue or declare both copies. Preferred order is C4 before 3a,
> whose mechanical rename includes the cites property.
>
> Admit properties using the same supplied candidate generation, including
> a new property and its first use in one publication. Reject an undeclared
> property and a declared property's invalid value with declaration key,
> property key, expected shape, offending value and source/location.
> Use 1a's schema admission/refusal facets; do not create a second error
> taxonomy. Validation precedes storage filtering; an unknown property is
> never classified as harmless because it produced no native attribute.
>
> Flip `canonical-rows-carry-arbitrary-namespaced-properties`: registered
> arbitrary properties work; unregistered arbitrary properties refuse.
> Delete the inert cardinality hint and retired class/refusal markers
> rather than admitting fake properties to make stale forms pass. Declare
> the surviving meaningful properties in their namespace owners below.
> No `:any`, nullable default, hard-coded exception list, compile path,
> regex classifier or mutable property registry. Stop after landing.

### Exact property declarations and six-property disposition

| Declaration / current anchor | Form/cardinality and docstring; action |
|---|---|
| NEW `:seon.config/default` — `schemas/seon.config.edn:3`; uses `seon.config.db.edn:1`, `:2` | `[:or {:description "Non-nil scalar default declared by a configuration dial. The dial's own schema must also accept it; false, zero and an empty string are values. Absence means no declared default."} :string :boolean :int :double :keyword :symbol :inst :uuid]`. Logical scalar, native one via existing mixed-union codec. No identity/ref/deletion behavior: value. Current defaults 250000 and 600000 fit; arbitrary maps/functions/nil do not. Wider defaults need an explicit later accretion, not an exemption to any. |
| NEW `:seon.shell/environment` — uses `seon.config.shell.edn:6`, `:14`, `:20` | `[:string {:min 1 :description "Environment variable whose value supplies this declared shell setting at configuration construction; the name is persisted, not the environment value or credentials."}]`; native one string, optional property, value. Declare in `schemas/seon.shell.edn`, not `my.shell` or a consumer-local registry. |
| NEW `:seon.render/derived` — use `seon.error.edn:217`, `seon.cluster.status.edn:1` | `[:boolean {:description "This declared render relation derives its value from the supplied database instead of a stored forward attribute."}]`; one boolean, optional property, value. Preserve the existing meaning/readers; not an entity classification or second renderer registry. |
| `:seon.db/cardinality` — uses `seon.test.edn:37`, `seon.test.accretion.edn:63` | **Delete uses**, consumed by 1b where applicable; do not declare. Compiled Malli collection shape owns cardinality. `schemas/seon.db.edn:31`, `:32`, `:44` already declare append-only-after, retraction-authority and component-schema; retain their qualified-keyword declarations, validate their property values and document their actual owners. No duplicate facets or native value-type knobs. |
| `:seon.error/class`, `:seon.error/refusal` — uses `seon.config.edn:48`, legacy error maps; owner `seon.error.edn:65`, `:97` | **Retired by 1a/D3/D12**, not new property declarations. Assert no remaining uses in admitted forms after 1a. `:seon.error/refusal-shape` already is a qualified-keyword at `:97`; preserve only if its actual landed consumer remains. C4 owns the error resource's surviving properties, not permission to resurrect a marker. The six-item census is historical evidence; it is not six mandatory new keys. |
| NEW `:seon.issue/cites` — `seon.issue.edn:8`, `:11`, `:14`, `:16`, `:18`, `:21`, `:23`, `:25` | `[:vector {:min 1 :description "Ordered identity attributes through which the citation owner resolves supplied tokens. These are attribute names, not refs to schema rows; target deletion cannot erase this declaration."} :qualified-keyword]`; optional property, one EDN-coded vector (preserve order), value. 3a renames it to task/cites with its owner/readers; never two registries. |
| NEW `:seon.schema.admission/exemption` — owner `seon.schema.admission.edn:1`; uses e.g. `seon.render.edn:208`, `:215` | `[:enum {:description "Explicit admission justification for a genuinely polymorphic boundary; it never makes the boundary's runtime values database-storable."} :seon.schema.admission/polymorphic-boundary]`; optional one keyword value. Accept only the actual admitted exemption vocabulary; no catch-all keyword arm. |
| NEW `:seon.schema.admission/reason` — same owner/use anchors | `[:string {:min 1 :description "Concrete justification accompanying an admission exemption on this boundary; inherited exemptions do not excuse unrelated member schemas."}]`; optional one string value. Require exemption/reason together at that property-bearing schema; blank or orphan justification refuses. |

Properties are declaration metadata, not optional fields on every domain
entity. Ordinary entity maps remain open. This refusal applies to metadata
positions, including compiled child and entry properties, not arbitrary extra
keys in user data. Preserve known Malli/dependency properties using their
actual property schemas (`m/properties-schema` and generator/property owners),
not a roster of tolerated unknown names. Candidate-registry declarations
provide Seon's property vocabulary. A dependency compile-time property may
be declared yet non-storable; do not silently discard an unknown Seon
property under that exception. No namespace-prefix regex classifies policy.

At inspection `src/seon/schema.clj:3477` owns canonical rows and `:3524`
merges projected properties; `src/seon/schema/datahike.clj:431` owns
`storable-properties-in`. Put strict admission before that storage projection
in the one schema owner, sharing it with all publication/candidate entrances.
Consume the bridge's compiled property navigation; never restore
`schema.form/namespaced-properties` after step 2 retires it. Warm reads
validate/project from the carried generation and must not compile defaults.

### Regression and reset contract

`test/seon/schema_test.clj:928` is the named regression to flip. Split its
assertions into registered synthetic property round-trip, unknown refusal,
and genuinely dependency-declared compile-time property preservation. Use
canonical fixture plus `extra-schema`, not its old hand-rostered population.
Test root, nested schema and entry properties, same-publication declaration,
invalid value, absent property, false/zero/empty-string defaults, and rejection
of nil/function/map defaults. Query actual datoms and pull decoded values;
`storable? true` alone is insufficient. Check each dial's default against the
dial schema (a string default on an int dial refuses). Verify the entire
canonical population admits; the strict scan finding zero declarations is
failure, not success. Remove a declaration while keeping a use and require
the full diagnostic. Do not weaken 1a facet admission to make this pass.

Run `seon.schema-test`, `seon.schema.datahike-test`, `seon.config-test`,
`seon.schema.edn-test` with the owned overlay. Orchestrator cold/platform and
one post-reset property query prove live adoption separately. **RESET NEEDED:**
new native property attributes and any previous stored property type changes
are included in the existing batch; removed inert metadata alone is not a
native type change. Exact default/cites codec native types are taken from the
landed bridge parity result and recorded, never guessed from the logical union.
**Estimate: 1–2 lane-days, astra low.** Must not do C4 options 2/3, hide unknowns,
reintroduce class markers, redo config/plan 1d, or turn arbitrary objects into
persisted defaults.

## 1e — deletion-dial sweep

**Guarantee:** every installed reference has a deliberate owner-entry
required/optional choice and a docstring stating its deletion consequence.
Observations that must survive their named subject store values. The gate
finds a newly undocumented ref through the compiled population, including
aliases and components; it never passes by inspecting an empty population.

### Launch verbatim on sol low — after the family cuts above land

> Implement **wave 1e only**, under the common launch contract. Read audit
> A C8, audit B C3/C4 and audit C C9 (also C10/C12 where they affect these
> refs), AGENTS §3 and the data-modeling guide's deletion section. The
> namespace plan's **118** is the dated undocumented-ref census:
> A 14 descriptions lacking a lifecycle choice + B 44 and C 60
> undocumented mentions, not the total current installed ref count and not a
> quota. Reconcile those rows with the landed compiled population and
> every row in the dated inventory below. Preserve already-correct family
> decisions; do not redeclare 1a's facets or overwrite 1b/1c/3a semantics.
>
> Own the resource declarations listed below after release, their actual
> changed writer/reader consumers, the existing schema/datahike canonical
> tests and final-report relation tests, and
> `docs/prds/steward-platform/research/wave-1e-deletion-dials-2026-09-21.md`.
> Mechanical docstrings are sol-low work; the value conversions below are
> explicitly priced and specified, not authority to invent more lifecycle
> mechanisms. If a refreshed writer proves a listed policy false, bring
> three priced options before changing that policy; finish independent rows.
>
> Keep required peer refs required (refuse); keep intentionally optional
> peers optional (sweep); components cascade through their declared child
> schema, with their own entry requiredness preserved. A pending notification
> moves to its durable sibling in one settlement transaction. Name all five
> behaviors in the landing evidence. Do not add a deletion-policy property,
> central policy registry, name heuristic, timestamp tombstone, synthetic
> component identity or per-family writer.
>
> For each row, add the stated lifecycle sentence to its meaningful existing
> description; do not erase its domain explanation. Verify actual owner
> entries using compiled navigation, including inherited facets. Refresh
> retired/new rows, list their landing owner, and derive the new count.
> Requests and read-only projections containing ref grammar are not stored
> attributes merely because their schemas mention `seon.db/ref`.
>
> Add the recurring checker at the existing canonical schema regression
> owner: query compiled installed ref declarations and their owner entries,
> assert a nonempty population, require a nonblank effective description,
> and report qualified key, resource/declaration and owner entry for each
> miss. Check every alias occurrence's effective properties, not an arbitrary
> four-line source window. The checker does not pretend prose proves the
> chosen behavior: final-writer regressions prove the five classes below.
>
> Make the explicitly named value/caller conversions below if the family
> owner has not already done them. Public retirement and all callers land
> together. No mutation of another lane's in-flight code. Record the native
> type/requiredness changes as RESET NEEDED; documentation-only rows do not
> need a reset. Stop after the coherent sweep lands.

### Decisions beyond documentation

| Current ref / anchor | Exact final policy and consumer ownership |
|---|---|
| `:seon.error/run` — `schemas/seon.error.edn:25` | Replace with NEW `:seon.error/turn-id`, O one non-identity alias of `:seon.turn/id`, value. "Observed turn identity; compaction cannot erase fault provenance." Convert `src/seon/error.clj`, `src/seon/problems.clj` and their readers after 1a releases; no lookup ref or duplicated live-turn link. |
| `:seon.error.occurrence/{turn,evaluation,proc-fn}` — `seon.error.occurrence.edn:8`, `:49`, `:9` | NEW `/turn-id` alias turn/id, `/evaluation-id` alias cluster.eval/id, `/proc-function :qualified-symbol`, all O one values. "Observed identity/name; target deletion does not erase this occurrence's provenance." Consume equivalent landed 1a values if already present; never declare siblings twice. Convert occurrence recorder and render/pull selectors in `error.clj`, `problems.clj`, `render/*` atomically. |
| `:seon.eval/renderer-fn` — `seon.eval.edn:3` | 1c retires it for existing `/renderer`; 1e verifies absence, no second edit. |
| `:seon.schedule.task/function` — `seon.schedule.task.edn:3` | NEW `:seon.schedule.task/function-symbol :qualified-symbol`, R one value, indexed if queried. "Declared function name to execute; deletion preserves this obligation and execution reports an unresolved name." Convert `src/seon/schedule.clj`, maintenance handlers/readers and `src/seon/fn.clj` reference-to readers; remove `/function` and the now-unused `:seon.fn/reference-to` property only after all callers are gone. Program deletion must still report named obligations through the existing program relation mechanism; never silently turn a missing function into success. |
| `:seon.maintenance.{request,receipt}/handler` — `seon.maintenance.request.edn:7`, `seon.maintenance.receipt.edn:7` | NEW `/handler-symbol :qualified-symbol` in each owner; R one value, not identity. "Observed handler name for this execution; survives function deletion." Convert `src/seon/maintenance.clj` and handler execution/pull/read contracts; schedule's current executable name and historical execution name remain distinct facts. |
| `:seon.effect/notify` → `/to` — `seon.effect.edn` (inventory below) | Keep O one refs; **pending** is notify, durable delivery is to, settlement retracts notify and asserts to atomically. Required `/owner` protects retained agent identity; to is retained while effect evidence survives. Doc notify: "Pending recipient; settlement moves this edge to to in the same transaction. Target deletion must not lose a pending delivery." Final relation refuses swept pending recipient unless owning request is removed/settled in that transaction. No extra delivery path. |
| `:seon.listen/entity`, `:seon.ns/agents`, test claim/completion/covered evidence | Consume 1c/1b policies above; do not reclassify conditional refuses as ordinary sweeps. Agent archival is not target deletion. |
| `:seon.issue/{functions,tests,errors,keys,namespaces,runs,issues,members,files}` | Retain wave-3a's deliberate topic relations (optional peer sweep; files cascade; tests guarded by existing retention/creator authority). Audit B C4's proposal to convert every citation ref is not adopted by 3a. `/unresolved` already preserves unresolved tokens; do not create a duplicate historical citation family. If 3a has landed, apply docs to task names only. |

No broadened conversion is implied for `:seon.fn/{ns,file}`, shape refs,
message recipients or maintenance task/fire refs: they state real relations
and retain the requiredness in their owning shape. Transient aliases do not
install new attributes. In particular the historical plan-needs ref was
already replaced by identity strings in 1d; do not require a nonempty ref set
or undo missing-prerequisite blocking.

### Required canonical regression matrix

| Behavior | Positive proof | Refusal / survival proof |
|---|---|---|
| Cascade | Real agent→runtime→turn / declared error observation children disappear with the owning root | Shared child, cycle, missing child, ownerless child and exhausted validation bound refuse through existing final owner checks; no child identity merely for tests. |
| Sweep | Optional topic/namespace link loses only its target relation | Surviving entity validates; unrelated facts remain; history still shows old relationship. Do not use a coverage/listener/event ref as the permissive example. |
| Refuse | Required parent/peer target may be removed WITH its referrer or repaired in the same transaction | Target-only `retractEntity` and equivalent explicit datom changes refuse atomically. Include last-member sweep for conditional evidence retention. |
| Value | Delete program function/compact a turn after recording its observation | Symbol/identity and shown text remain exact; execution of a now unresolved scheduled name returns the declared error. |
| Pending→durable sibling | Real effect settlement moves notify→to exactly once and wakes through the existing listened attribute | Deleting pending target cannot erase delivery silently; failed settlement leaves the pending fact and no false delivered result. |

Use `test/seon/schema_test.clj`, `test/seon/schema/datahike_test.clj`,
`test/seon/db_test.clj`, `test/seon/error_test.clj`, `test/seon/schedule_test.clj`,
`test/seon/maintenance_test.clj` and the existing effect settlement regression
owner. Add a synthetic undocumented ref via canonical `extra-schema` and
prove the checker names it; an empty catalog must fail independently. Alias,
`:and`, component and non-stored request examples prevent roster/regex checks.
The five class proofs can reuse existing green canonical regressions where
they assert exactly these guarantees; do not write one test per docstring.

**Estimate: 1–2 lane-days for documentation/checker plus 1–2 for the listed
value conversions and caller proofs; sol low.** Release error/turn/db/schema
owners first; no concurrent resource sweep. **RESET NEEDED:** ref→symbol/value
retirements and changed conditional requiredness only; list the old and new
keys with native types. Orchestrator runs named cold overlay plus platform
and observes one post-reset required deletion refusal and one retained value
on the live cluster. Must not tune timeouts, migrate data, modify 1a facet
identity, introduce any production regex, or treat 118 as a fixed assertion.

### Dated per-reference inventory and docstring instructions

The following is a source-reader inventory of direct ref declarations,
including new facet children since the audits. It is deliberately broader
than the historical 118 undocumented mentions. It is not a production roster
and does not replace the launch-time compiled installed-attribute query.
Every row includes its declaration anchor, logical cardinality, observed
owner-entry optionality and the lifecycle sentence to add. **Family deltas
above override the old shape shown here**; a retired row is accounted for by
its retirement, never recreated. The R/O column describes stored maps where
explicitly marked; entries inherited by a facet must be resolved by the
compiled checker at launch. Unattached declarations stay unattached; a
resource declaration alone is not authorization to install it as a root.

`R` = required; `O` = optional; `R/O` = owner-dependent (preserve each entry).
`T` = no explicit stored owner found by the source read: verify provenance,
then document as transient/unattached unless the compiled population proves
a stored owner. `one`/`many` is logical ref cardinality; vectors representing
owned refs use the bridge's component/cardinality rules, not an inert property.
Descriptions for conditional-event refs are governed by 1b/1c, not blanket
optional sweep. Each component sentence is followed by its declared child
schema in the existing property; do not add an identity or new child family.

| Declaration anchor | Cardinality; stored entry | Lifecycle sentence / disposition |
|---|---|---|
| `:my.background/authored-form` — `schemas/my.background.edn:30` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.edit/edit-observation` — `schemas/my.edit.edn:77` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.fs/io-observation` — `schemas/my.fs.edn:166` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.message/error-request` — `schemas/my.message.edn:80` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.note/about` — `schemas/my.note.edn:7` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:my.note/agent` — `schemas/my.note.edn:2` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:my.note/error-about` — `schemas/my.note.edn:41` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:my.plan/agent` — `schemas/my.plan.edn:1` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:my.plan/constraint-observation` — `schemas/my.plan.edn:270` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.plan/current-step` — `schemas/my.plan.edn:41` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:my.plan/error-request` — `schemas/my.plan.edn:285` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.plan/intent-subjects` — `schemas/my.plan.edn:205` | many; T | No stored entry; retain the transient vector grammar only. No database deletion dial is installed by this key. |
| `:my.plan/parent-step` — `schemas/my.plan.edn:53` | one; T | Optional request ref to an existing parent step; not stored. Component ownership is written through plan/steps or item/steps, which cascade. |
| `:my.plan/steps` — `schemas/my.plan.edn:35` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:my.plan.item/agent` — `schemas/my.plan.item.edn:15` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:my.plan.item/completed-tx` — `schemas/my.plan.item.edn:33` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:my.plan.item/steps` — `schemas/my.plan.item.edn:54` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:my.plan.item/subject` — `schemas/my.plan.item.edn:46` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:my.shell/error-command` — `schemas/my.shell.edn:56` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.shell/execution-observation` — `schemas/my.shell.edn:62` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.turn/error-proposal` — `schemas/my.turn.edn:49` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:my.web/error-request` — `schemas/my.web.edn:176` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:seon.activation/lookup-refs` — `schemas/seon.activation.edn:22` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.agent/archived-tx` — `schemas/seon.agent.edn:1` | one; O | Optional until archival; refuse loss of archived-event authority on a retained agent (1c). |
| `:seon.agent/failure` — `schemas/seon.agent.edn:175` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:seon.agent/namespace` — `schemas/seon.agent.edn:84` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.agent/namespace-ref` — `schemas/seon.agent.edn:119` | one; T | Required transient situation result ref; not stored. Derive from current agent namespace; missing target is an unavailable situation, not a fabricated ref. |
| `:seon.agent/open-run-ref` — `schemas/seon.agent.edn:121` | one; T | Optional transient situation result ref; not stored. Derive from open-turn facts; turn deletion changes the next query. |
| `:seon.agent/plan` — `schemas/seon.agent.edn:156` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.agent/runtime` — `schemas/seon.agent.edn:2` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.agent/settings` — `schemas/seon.agent.edn:159` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.agent.graph/control-observation` — `schemas/seon.agent.graph.edn:4` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.ai/request-observation` — `schemas/seon.ai.edn:503` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.ai.attempt/error` — `schemas/seon.ai.attempt.edn:14` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.ai.attempt/failover-from` — `schemas/seon.ai.attempt.edn:1` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.ai.attempt/settings` — `schemas/seon.ai.attempt.edn:11` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.ai.attempt/truncation` — `schemas/seon.ai.attempt.edn:10` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.ai.model/deepseek-off-peak-windows` — `schemas/seon.ai.model.edn:25` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.ai.model/provider` — `schemas/seon.ai.model.edn:4` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.bootstrap/prefix-observation` — `schemas/seon.bootstrap.edn:38` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.call-preparation/schema` — `schemas/seon.call-preparation.edn:28` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.call-preparation/supplier` — `schemas/seon.call-preparation.edn:33` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.cluster/config` — `schemas/seon.cluster.edn:10` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.cluster/failure` — `schemas/seon.cluster.edn:30` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:seon.cluster/instructions` — `schemas/seon.cluster.edn:12` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.cluster/toolkit` — `schemas/seon.cluster.edn:14` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.cluster.eval/ns` — `schemas/seon.cluster.eval.edn:22` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.cluster.eval/read-evidence` — `schemas/seon.cluster.eval.edn:4` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.cluster.eval/run` — `schemas/seon.cluster.eval.edn:23` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.cluster.prompt/derivation-observation` — `schemas/seon.cluster.prompt.edn:41` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.cluster.registry/registry-observation` — `schemas/seon.cluster.registry.edn:81` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.cluster.reply/authored-reply` — `schemas/seon.cluster.reply.edn:21` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.cluster.source/publication-observation` — `schemas/seon.cluster.source.edn:31` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.cluster.status/concern` — `schemas/seon.cluster.status.edn:4` | one; T | Derived render relation, not a stored forward ref. Recompute from supplied facts; no stored deletion dial. |
| `:seon.cluster.store/acquisition-observation` — `schemas/seon.cluster.store.edn:16` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.cluster.wake/offer-observation` — `schemas/seon.cluster.wake.edn:54` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.config/agent` — `schemas/seon.config.edn:1` | one; T | Unattached legacy backlink after 1d: generated agent-overlay has no such entry (`src/seon/schema/edn.clj:87`). Do not re-add it; settings ownership is agent/settings cascade. |
| `:seon.context.capture/contributions` — `schemas/seon.context.capture.edn:31` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.context.capture/run` — `schemas/seon.context.capture.edn:37` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.context.contribution/agent` — `schemas/seon.context.contribution.edn:26` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.context.contribution/evaluations` — `schemas/seon.context.contribution.edn:27` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.db/process` — `schemas/seon.db.edn:245` | one; T | Optional transaction metadata peer ref; sweep makes process provenance unavailable. Event authority relations in 1b/1c refuse loss where required; never copy provenance onto domain entities. |
| `:seon.db/receipt` — `schemas/seon.db.edn:246` | one; T | Optional legacy transaction-to-evaluation relation; sweep on compaction. Retain only while the current turn reader at `src/seon/turn.clj:1990` consumes it; no durable result object is implied. |
| `:seon.db/user` — `schemas/seon.db.edn:317` | one; T | Optional transaction metadata peer ref; sweep leaves author unknown. Existing creator/retraction authority must not accept a missing author as permission. |
| `:seon.db.availability/failed-observation` — `schemas/seon.db.availability.edn:17` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.db.read/target` — `schemas/seon.db.read.edn:19` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.db.read.target/arguments` — `schemas/seon.db.read.target.edn:4` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.db.read.target/entity-projection` — `schemas/seon.db.read.target.edn:43` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.db.read.target/index-request` — `schemas/seon.db.read.target.edn:52` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.db.read.target/query` — `schemas/seon.db.read.target.edn:58` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.db.read.target/selector` — `schemas/seon.db.read.target.edn:64` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.db.write/attempt` — `schemas/seon.db.write.edn:17` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.db.write.attempt/operations` — `schemas/seon.db.write.attempt.edn:40` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.db.write.attempt/rejection` — `schemas/seon.db.write.attempt.edn:46` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.dev.mcp/request-observation` — `schemas/seon.dev.mcp.edn:47` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.effect/eval` — `schemas/seon.effect.edn:2` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.effect/execution-observation` — `schemas/seon.effect.edn:185` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.effect/file` — `schemas/seon.effect.edn:8` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.effect/notify` — `schemas/seon.effect.edn:126` | one; O | Pending recipient moves to durable to at settlement; losing a pending target refuses (1e). |
| `:seon.effect/owner` — `schemas/seon.effect.edn:59` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.effect/program` — `schemas/seon.effect.edn:19` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.effect/run` — `schemas/seon.effect.edn:131` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.effect/to` — `schemas/seon.effect.edn:154` | one; O | Durable settlement recipient; pending notify moves here atomically; retained agent identity prevents loss. |
| `:seon.env/member-expectation` — `schemas/seon.env.edn:113` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.error/agent` — `schemas/seon.error.edn:244` | one; T | Optional peer in the recorded fault value; sweep on physical deletion, with 1c agent-retention rule preventing ordinary identity loss. Do not make attribution mandatory for agentless faults. |
| `:seon.error/basis` — `schemas/seon.error.edn:293` | one; R/O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R/O entry and declared child schema. |
| `:seon.error/cause` — `schemas/seon.error.edn:299` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error/evidence-items` — `schemas/seon.error.edn:302` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error/issue` — `schemas/seon.error.edn:262` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error/location` — `schemas/seon.error.edn:323` | one; R/O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R/O entry and declared child schema. |
| `:seon.error/occurrences` — `schemas/seon.error.edn:55` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error/of-steward` — `schemas/seon.error.edn:213` | one; T | Derived responsibility relation, never stored. 3a retires the legacy spelling; 1c supplies plural responsibility facts. No new ref attribute. |
| `:seon.error/offending-projection` — `schemas/seon.error.edn:332` | one; R/O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R/O entry and declared child schema. |
| `:seon.error/ref` — `schemas/seon.error.edn:197` | one; T | Required ref grammar in the transient recording result; not a stored owner entry. Persist the existing root/occurrence relations, not this return envelope. |
| `:seon.error/regressions` — `schemas/seon.error.edn:261` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error/resolved-tx` — `schemas/seon.error.edn:263` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error/run` — `schemas/seon.error.edn:25` | one; T | Retire for turn-id value; observation survives compaction (1e decision). |
| `:seon.error/steward` — `schemas/seon.error.edn:212` | one; T | Retire with 3a task routing; do not copy plural responsibility or a task recipient onto the fault. |
| `:seon.error.disposition/evidence` — `schemas/seon.error.disposition.edn:7` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.error.evidence/projection` — `schemas/seon.error.evidence.edn:23` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error.location/omission` — `schemas/seon.error.location.edn:23` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error.location/segments` — `schemas/seon.error.location.edn:29` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error.location.segment/key` — `schemas/seon.error.location.segment.edn:11` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.error.occurrence/agent` — `schemas/seon.error.occurrence.edn:7` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error.occurrence/cluster` — `schemas/seon.error.occurrence.edn:46` | one; T | Unattached legacy declaration in the inspected occurrence map; no new stored entry. Consume 1a provenance through its declared observation/basis owners. |
| `:seon.error.occurrence/data-blob` — `schemas/seon.error.occurrence.edn:10` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.error.occurrence/evaluation` — `schemas/seon.error.occurrence.edn:49` | one; T | Retire for evaluation-id value; observation survives compaction (1e decision). |
| `:seon.error.occurrence/proc-fn` — `schemas/seon.error.occurrence.edn:9` | one; O | Retire for proc-function symbol value; observation survives program deletion (1e decision). |
| `:seon.error.occurrence/process` — `schemas/seon.error.occurrence.edn:6` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.error.occurrence/turn` — `schemas/seon.error.occurrence.edn:8` | one; O | Retire for turn-id value; observation survives compaction (1e decision). |
| `:seon.error.projection/missing-member` — `schemas/seon.error.projection.edn:29` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.error.projection/omission` — `schemas/seon.error.projection.edn:35` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.eval/renderer-fn` — `schemas/seon.eval.edn:3` | one; O | Retire; existing renderer symbol carries the observation (1c). |
| `:seon.eval.drive/driver-observation` — `schemas/seon.eval.drive.edn:62` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.failure/fault` — `schemas/seon.failure.edn:11` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.failure/requested-tx` — `schemas/seon.failure.edn:14` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.failure/stopped-tx` — `schemas/seon.failure.edn:17` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.flow/control-observation` — `schemas/seon.flow.edn:209` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.flow/error-graph` — `schemas/seon.flow.edn:224` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn/arities` — `schemas/seon.fn.edn:30` | many; T | Optional component set in fn/fn under its outer :and attributes declaration; cascade to arity rows. Source census T misses that inherited flag; policy is O/cascade. |
| `:seon.fn/error-subject` — `schemas/seon.fn.edn:232` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn/file` — `schemas/seon.fn.edn:8` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.fn/ns` — `schemas/seon.fn.edn:171` | one; T | Required peer in fn/fn under its outer :and attributes declaration; refuse target loss while function survives. Source census T misses that inherited flag; policy is R/refuse. |
| `:seon.fn.argument/binding` — `schemas/seon.fn.argument.edn:4` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn.argument/rest-element-schema` — `schemas/seon.fn.argument.edn:7` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.fn.argument/rest-tail-schema` — `schemas/seon.fn.argument.edn:6` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.fn.argument/schema` — `schemas/seon.fn.argument.edn:5` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.fn.arity/arguments` — `schemas/seon.fn.arity.edn:5` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.fn.arity/guard-schema` — `schemas/seon.fn.arity.edn:9` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.fn.arity/input-schema` — `schemas/seon.fn.arity.edn:3` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.fn.arity/return-schema` — `schemas/seon.fn.arity.edn:8` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.fn.binding/binding-projection` — `schemas/seon.fn.binding.edn:26` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn.binding/children` — `schemas/seon.fn.binding.edn:4` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.fn.binding/entries` — `schemas/seon.fn.binding.edn:6` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.fn.binding/source-location` — `schemas/seon.fn.binding.edn:42` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn.binding.child/binding` — `schemas/seon.fn.binding.child.edn:3` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.fn.binding.entry/binding` — `schemas/seon.fn.binding.entry.edn:4` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument/declared-arities` — `schemas/seon.instrument.edn:99` | many; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument/explanations` — `schemas/seon.instrument.edn:117` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument/registration-observation` — `schemas/seon.instrument.edn:139` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument/returned-error` — `schemas/seon.instrument.edn:145` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument.explanation/actual` — `schemas/seon.instrument.explanation.edn:4` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.instrument.explanation/humanized` — `schemas/seon.instrument.explanation.edn:45` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.instrument.explanation/schema-location` — `schemas/seon.instrument.explanation.edn:57` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument.explanation/value-location` — `schemas/seon.instrument.explanation.edn:63` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument.explanations/items` — `schemas/seon.instrument.explanations.edn:23` | many; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.instrument.explanations/omission` — `schemas/seon.instrument.explanations.edn:29` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.instrument.humanized/messages` — `schemas/seon.instrument.humanized.edn:24` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.instrument.humanized/omission` — `schemas/seon.instrument.humanized.edn:30` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.instrument.humanized.message/location` — `schemas/seon.instrument.humanized.message.edn:14` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.issue/agent` — `schemas/seon.issue.edn:30` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/budget-exhausted-tx` — `schemas/seon.issue.edn:37` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/created-by` — `schemas/seon.issue.edn:34` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/detector` — `schemas/seon.issue.edn:35` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/errors` — `schemas/seon.issue.edn:14` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/files` — `schemas/seon.issue.edn:20` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.issue/functions` — `schemas/seon.issue.edn:8` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/issues` — `schemas/seon.issue.edn:25` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/keys` — `schemas/seon.issue.edn:16` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/members` — `schemas/seon.issue.edn:29` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/namespaces` — `schemas/seon.issue.edn:18` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/resolved-tx` — `schemas/seon.issue.edn:38` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/runs` — `schemas/seon.issue.edn:23` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.issue/tests` — `schemas/seon.issue.edn:10` | many; O | Optional test peers before assignment; existing retention/creator authority refuses prohibited loss (3a). |
| `:seon.issue.citation/file` — `schemas/seon.issue.citation.edn:4` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.lint/file` — `schemas/seon.lint.edn:5` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.lint/fn` — `schemas/seon.lint.edn:4` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.listen/entity` — `schemas/seon.listen.edn:2` | one; O | Optional authored constraint; refuse sweep widening a surviving pattern (1c). |
| `:seon.maintenance.receipt/error` — `schemas/seon.maintenance.receipt.edn:17` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.maintenance.receipt/fire` — `schemas/seon.maintenance.receipt.edn:3` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.maintenance.receipt/handler` — `schemas/seon.maintenance.receipt.edn:7` | one; R | Retire for required handler-symbol value; preserve observed name (1e). |
| `:seon.maintenance.receipt/request` — `schemas/seon.maintenance.receipt.edn:9` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.maintenance.receipt/result` — `schemas/seon.maintenance.receipt.edn:15` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.receipt/task` — `schemas/seon.maintenance.receipt.edn:5` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.maintenance.request/agent` — `schemas/seon.maintenance.request.edn:9` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.maintenance.request/fire` — `schemas/seon.maintenance.request.edn:5` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.maintenance.request/handler` — `schemas/seon.maintenance.request.edn:7` | one; R | Retire for required handler-symbol value; preserve observed name (1e). |
| `:seon.maintenance.request/task` — `schemas/seon.maintenance.request.edn:3` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.maintenance.result/cluster-cleanup-collection` — `schemas/seon.maintenance.result.edn:41` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.maintenance.result/collect-branches` — `schemas/seon.maintenance.result.edn:43` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-claim-errors` — `schemas/seon.maintenance.result.edn:25` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-dead` — `schemas/seon.maintenance.result.edn:11` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-processes` — `schemas/seon.maintenance.result.edn:7` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-roots` — `schemas/seon.maintenance.result.edn:3` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-unclaimed` — `schemas/seon.maintenance.result.edn:20` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/process-census-unresponsive` — `schemas/seon.maintenance.result.edn:15` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/reap-census` — `schemas/seon.maintenance.result.edn:30` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.maintenance.result/reap-refused` — `schemas/seon.maintenance.result.edn:36` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/reap-roots` — `schemas/seon.maintenance.result.edn:34` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.maintenance.result/reap-stopped-processes` — `schemas/seon.maintenance.result.edn:32` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.message/caused-by` — `schemas/seon.message.edn:103` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.message/error-request` — `schemas/seon.message.edn:178` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.message/from` — `schemas/seon.message.edn:109` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.message/to` — `schemas/seon.message.edn:19` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.ns/aliases` — `schemas/seon.ns.edn:2` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.ns/imports` — `schemas/seon.ns.edn:4` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.ns/refers` — `schemas/seon.ns.edn:31` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.ns/steward` — `schemas/seon.ns.edn:34` | one; O | Retire; ns/agents is O many responsibility refs, archival retains targets (1c). |
| `:seon.operator/operation-observation` — `schemas/seon.operator.edn:143` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.problems/detector-observation` — `schemas/seon.problems.edn:134` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.program/error-subject` — `schemas/seon.program.edn:270` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.program/source-observation` — `schemas/seon.program.edn:276` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.reconcile/constraint-observation` — `schemas/seon.reconcile.edn:36` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.reconcile/requested-identity` — `schemas/seon.reconcile.edn:53` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render/input-projection` — `schemas/seon.render.edn:240` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.data/error-root` — `schemas/seon.render.data.edn:104` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.data/requested-location` — `schemas/seon.render.data.edn:110` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.unknown/call-projection` — `schemas/seon.render.unknown.edn:4` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:seon.render.unknown/refusal-projection` — `schemas/seon.render.unknown.edn:16` | one; T | Cascade when attached through its declared component owner. No stored entry was found by this source read; do not add a root or identity. |
| `:seon.render.value/error-window` — `schemas/seon.render.value.edn:69` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.value/projection-observation` — `schemas/seon.render.value.edn:75` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.walk/error-subject` — `schemas/seon.render.walk.edn:113` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.walk/step-observation` — `schemas/seon.render.walk.edn:119` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.web/error-request` — `schemas/seon.render.web.edn:166` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.render.web/owner-observation` — `schemas/seon.render.web.edn:172` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.runtime/agent` — `schemas/seon.runtime.edn:1` | one; R | Required owning-agent backlink; refuse loss while runtime survives; no identity (1c). |
| `:seon.runtime/listens` — `schemas/seon.runtime.edn:8` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.runtime/trigger` — `schemas/seon.runtime.edn:7` | one; O | Retire with all callers (1c). |
| `:seon.runtime/turns` — `schemas/seon.runtime.edn:3` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.schedule/error-task` — `schemas/seon.schedule.edn:67` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schedule/settlement-observation` — `schemas/seon.schedule.edn:73` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schedule.fire/agent` — `schemas/seon.schedule.fire.edn:7` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.schedule.fire/task` — `schemas/seon.schedule.fire.edn:3` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.schedule.task/function` — `schemas/seon.schedule.task.edn:3` | one; R | Retire for required function-symbol value; unresolved execution is explicit (1e). |
| `:seon.schedule.task/owner` — `schemas/seon.schedule.task.edn:2` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.schedule.task/schedule` — `schemas/seon.schedule.task.edn:4` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.schema/declaration-expectation` — `schemas/seon.schema.edn:181` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schema/error-declaration` — `schemas/seon.schema.edn:197` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schema/ns` — `schemas/seon.schema.edn:82` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.schema/shape` — `schemas/seon.schema.edn:81` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.schema.datahike/declared-form` — `schemas/seon.schema.datahike.edn:23` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schema.shape/children` — `schemas/seon.schema.shape.edn:8` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.schema.shape/entries` — `schemas/seon.schema.shape.edn:10` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.schema.shape/error-form` — `schemas/seon.schema.shape.edn:50` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schema.shape/shape-expectation` — `schemas/seon.schema.shape.edn:56` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.schema.shape.child/schema` — `schemas/seon.schema.shape.child.edn:3` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.schema.shape.entry/schema` — `schemas/seon.schema.shape.entry.edn:4` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.sci.admit/projection-observation` — `schemas/seon.sci.admit.edn:97` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.sci.eval/acquisition-observation` — `schemas/seon.sci.eval.edn:310` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.sci.eval/source-projection` — `schemas/seon.sci.eval.edn:331` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.sci.kernel/guard-observation` — `schemas/seon.sci.kernel.edn:37` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.sci.reader/reader-location` — `schemas/seon.sci.reader.edn:35` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.sci.reader/source-projection` — `schemas/seon.sci.reader.edn:41` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.search/index-observation` — `schemas/seon.search.edn:71` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.source/activation-closure` — `schemas/seon.source.edn:76` | one; T | Required component where the publication activation result declares it (`src/seon/cluster.clj:1995`); cascade from source owner. Consume publication/step-3 retirement if landed; never recreate a removed closure. |
| `:seon.source/loaded-producers` — `schemas/seon.source.edn:28` | many; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.test/adoption-cluster` — `schemas/seon.test.edn:287` | one; R | Required adoption authority; target deletion refuses while observation survives (1b). |
| `:seon.test/adoption-identities` — `schemas/seon.test.edn:285` | many; O | Replace refs with observed identity values, O many; positive observed-tx distinguishes empty (1b). |
| `:seon.test/execution-observation` — `schemas/seon.test.edn:307` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.test/failures` — `schemas/seon.test.edn:41` | many; O | Retire test-row aggregate storage; member/report evidence is authority (1b). |
| `:seon.test/ns` — `schemas/seon.test.edn:1` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.test/run` — `schemas/seon.test.edn:34` | one; O | Retire test-row last-run storage; derive from recorded members (1b). |
| `:seon.test.accretion/error-arguments` — `schemas/seon.test.accretion.edn:274` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.accretion/error-auto-check` — `schemas/seon.test.accretion.edn:280` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.accretion/error-groups` — `schemas/seon.test.accretion.edn:289` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.accretion/gate-tests` — `schemas/seon.test.accretion.edn:63` | many; O | Optional set of current test peers; sweep target deletion; historical execution is run/member evidence (1b). |
| `:seon.test.accretion.auto/failure` — `schemas/seon.test.accretion.auto.edn:32` | one; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.accretion.failure/evidence` — `schemas/seon.test.accretion.failure.edn:17` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.test.accretion.group/failures` — `schemas/seon.test.accretion.group.edn:23` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.failure/file` — `schemas/seon.test.failure.edn:14` | one; O | Retire aggregate; reported-file is the observed path value (1b). |
| `:seon.test.failure/first-run` — `schemas/seon.test.failure.edn:18` | one; R | Retire aggregate; derive earliest linked member run (1b). |
| `:seon.test.failure/last-run` — `schemas/seon.test.failure.edn:19` | one; R | Retire aggregate; derive latest linked member run (1b). |
| `:seon.test.failure/test` — `schemas/seon.test.failure.edn:3` | one; R | Retire aggregate; report/symbol already stores the observed value (1b). |
| `:seon.test.member/claim-tx` — `schemas/seon.test.member.edn:8` | one; O | Optional before claim; required for claimed member; immutable authority, refuse loss (1b). |
| `:seon.test.member/completed-tx` — `schemas/seon.test.member.edn:10` | one; O | Optional before completion; terminal counts and observations require it; refuse loss (1b). |
| `:seon.test.member/error` — `schemas/seon.test.member.edn:18` | one; O | Optional boundary-error evidence; refuse losing recorded evidence while completed member survives (1b). |
| `:seon.test.member/failures` — `schemas/seon.test.member.edn:17` | many; O | Optional many immutable report peers; refuse losing evidence while completed member survives (1b). |
| `:seon.test.member/terminated-tx` — `schemas/seon.test.member.edn:16` | one; O | Optional positive termination observation; missing is unknown; immutable once observed (1b). |
| `:seon.test.member/worker` — `schemas/seon.test.member.edn:6` | one; O | Optional before claim; required with claim, host and claimed-at; refuse authority loss (1b). |
| `:seon.test.run/cluster` — `schemas/seon.test.run.edn:28` | one; O | Required live authority or absent with complete snapshot provenance; refuse losing supplied authority (1b). |
| `:seon.test.run/covered-by` — `schemas/seon.test.run.edn:31` | many; O | Optional many; refuse loss of accepted evidence while dependent run survives (1b). |
| `:seon.test.run/error-request` — `schemas/seon.test.run.edn:133` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.test.run/exclusions` — `schemas/seon.test.run.edn:32` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.run/members` — `schemas/seon.test.run.edn:30` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.test.run/selection-tx` — `schemas/seon.test.run.edn:33` | one; O | Required positive selection observation; refuse losing authority (1b). |
| `:seon.test.runner/error-selection` — `schemas/seon.test.runner.edn:96` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.test.runner/worker-observation` — `schemas/seon.test.runner.edn:102` | one; R | Cascade: the owning value owns this child; parent retraction destroys it. Preserve R entry and declared child schema. |
| `:seon.turn/agent` — `schemas/seon.turn.edn:51` | one; R | Refuse: a surviving owner requires this target; remove or repair the referrer in the same transaction. |
| `:seon.turn/attempts` — `schemas/seon.turn.edn:130` | many; O | Cascade: the owning value owns this child; parent retraction destroys it. Preserve O entry and declared child schema. |
| `:seon.turn/closed-tx` — `schemas/seon.turn.edn:159` | one; O | Optional until closure; refuse loss of recorded closure while turn survives (1c). |
| `:seon.turn/handled` — `schemas/seon.turn.edn:44` | many; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.turn/opened-tx` — `schemas/seon.turn.edn:160` | one; R | Required opening authority, refuse target deletion while turn survives (1c). |
| `:seon.turn/starting-ns` — `schemas/seon.turn.edn:99` | one; O | Sweep: this optional peer relation is removed with its target; the remaining owning value remains valid. |
| `:seon.turn/trigger` — `schemas/seon.turn.edn:45` | one; O | Retire with all callers (1c). |

Source-reader tally at this design boundary: **284 direct ref declarations** (123 R, 133 O, 3 R/O, 25 T). These are grammar declarations, not a measured installed-attribute total.

The reproducible no-JVM census script is embedded here so the evidence does not depend on an uncommitted scratch file. Run with native Babashka; its JSON output supplies the keys, forms-derived cardinality and explicit owner entries for the dated table. Line anchors are the declaration key in the named resource. The implementation checker must use the landed compiled bridge, not port this source-reader census into production.

```clojure
(require '[clojure.edn :as e] '[clojure.java.io :as io] '[cheshire.core :as j])
(let [files (sort-by str (filter #(.endsWith (str %) ".edn") (file-seq (io/file "resources/seon/schemas"))))
      rows (for [f files [k v] (e/read-string (slurp f))] {:file (str f) :key k :form v})
      owners (for [{o :key f :form} rows n (tree-seq coll? seq f)
                   :when (and (vector? n) (= :map (first n)) (map? (second n)) (:seon.db/attributes (second n)))
                   entry (drop 2 n) :when (vector? entry)]
               {:owner o :key (first entry) :optional (boolean (and (map? (second entry)) (:optional (second entry))))})]
 (println (j/generate-string
 (for [{:keys [file key form]} (sort-by :key rows)
       :when (and (some #{:seon.db/ref} (tree-seq coll? seq form))
                  (not (some #{:map :map-of :multi :or} (tree-seq coll? seq form))))
       :let [props (apply merge (filter map? (tree-seq coll? seq form)))]]
  {:file file :key (str key) :many (boolean (some #{:set :vector} (tree-seq coll? seq form)))
   :component (boolean (:seon.db/component props)) :child (str (:seon.db/component-schema props))
   :owners (vec (filter #(= key (:key %)) owners))}))))
```

## Design-lane verification boundary

This document is the only file written by the design lane. No source, test or schema resource was edited; no JVM, test gate, cluster command, worktree, MCP evaluation or foreign session operation was launched. Native Babashka read EDN for the inventory; source/dependency inspection and Git supplied the remaining evidence. No installed schema, runtime liveness, adoption or browser behavior is claimed verified. The future lanes own those explicit proofs. Foreign dirty paths are recorded above as coordination boundaries, not attributed as causes of a failure.

The current audits and later landings are reconciled explicitly: no new 1a facet identities or general predicate; no second test recorder; observed-empty adoption differs from never-observed adoption; D1 belongs only to 1c and D2 only to 3a; 118 is a dated undocumented-ref count; C4 removes obsolete metadata rather than blessing it. Cold and live proofs remain the orchestrator's integration obligations after implementation.
