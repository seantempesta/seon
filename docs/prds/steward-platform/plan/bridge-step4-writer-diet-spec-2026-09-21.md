---
type: plan
status: launch specification; waits for step-3 landing and overlapping-path release
created: 2026-09-21
tags: [plan, schema, malli, datahike, bridge, measurement, dissolution]
---

# Bridge step 4 — remove duplicate native write checks

**Guarantee:** Datahike owns its installed native write constraints. Seon
checks the remaining logical constraints and the complete final owning value,
including swept survivors and program relations. Every removed check has an
observed native-refusal witness. Owner discovery remains selective and bounded;
an optimization cannot make a sparse write scan the component population.

This is the verbatim launch assignment for step 4 of the binding
[Malli-native bridge PRD](malli-native-bridge-prd-2026-09-20.md), §6 ruling 2,
following [step 2 — Compiled walker](bridge-step2-walker-spec-2026-09-21.md)
and [step 3 — Carry only; durable stamp](bridge-step3-stamp-spec-2026-09-21.md).
The binding [validator measurement](../research/step4-validator-measurement-2026-09-20.md)
provides the refusal inventory, overlap table, measurements and production
slice. Its bulk prototype is evidence, not the production algorithm.

## Launch verbatim on astra low, after step 3 lands

> Implement **step 4 only: remove duplicate native write checks** in
> /Users/sean/src/seon, branch steward-platform. Read this specification,
> the bridge PRD, step-2 and step-3 specs, validator measurement and
> Datahike deletion study end to end. Read the actual steps 1–3 landing
> notes, the current working edge, AGENTS §§2–5 and lane rules 11–16,
> and the data-oriented-clojure, datahike, repl and clojure-testing skills.
> Read the dependency ledger below against the pinned source. PRD §6(2)
> and AGENTS §3 govern; no ownership or deletion policy is being re-ruled.
>
> You are not alone in the codebase. Preserve unrelated edits and never
> revert another lane's work. Execute this bounded assignment directly,
> without further delegation. Launch only after the coherent **step 3 —
> Carry only; durable stamp** implementation lands, with steps **1 —
> Registry: compile once, seal, carry** and **2 — Compiled walker** already
> inherited. A launch spec or a dirty implementation is not that landing.
>
> Consume step 1's retained named schemas/function contracts, fixed-input
> construction registry, dependent-closure recompilation, unaffected-root
> reuse and live-Var predicate behavior. Consume step 2's compiled storage
> and entity navigation, component transaction grammar, native-schema
> equality and native Malli explanation paths. Consume step 3's stamped
> acquired database generation, explicit construction/restoration boundary,
> expected-base check and final candidate carried through the writer.
> Do not revive raw form walkers, ambient candidate arities, packaged/row
> fallbacks or per-datom generation reconstruction.
>
> **Own** src/seon/db.clj and its existing write-plan/refusal boundaries;
> test/seon/db_test.clj, test/seon/owned_value_test.clj and
> test/seon/reset_edges_test.clj for the regressions below; and one landing
> note, docs/prds/steward-platform/research/bridge-step4-writer-diet-2026-09-21.md.
> The per-file table defines the narrow conditional codec/resource and
> diagnostic-owner changes. Refresh every anchor after steps 2–3. Have
> the orchestrator coordinate/release held paths before editing; historical
> ownership is not a permanent hold. Never operate, resume, message or
> repair another lane's session. Do not absorb publication, error-family,
> result-reuse or relational-validator work.
>
> **First bounded implementation item: probe the indexed owner lookup.**
> Before production check removal, compare the existing public d/datoms
> AVET lookup with dbi/search [nil attribute child-id] in owners-of,
> using native attribute-ref normalization. Change no traversal, validation,
> bound, seed or diagnostic ordering in this comparison. Follow the exact
> three-run method and go/no-go below. This is an unmeasured candidate at
> launch, not a claimed speedup. A no-go retains the existing selective
> lookup and continues the independently proved scalar delegation; it does
> not authorize a bulk scan, threshold or new index.
>
> **Prove the native/final boundary before deleting checks.** Reproduce the
> 38-case inventory on the canonical armed fixture; all 22 native-admitted
> reports must compare against the unmodified final callback. Preserve all
> diagnostic data except the observation timestamp. Every delegated check
> additionally names the native source, actual installed descriptor,
> operation grammar and actual exception/refusal data that replace it.
> Assert unchanged basis on Seon rejection and explicit admitted reports
> on success. No absent callback, empty result or missing subject is green.
>
> Extend the existing write-attribute-plan with a conservative residual
> derived from the retained compiled logical schema AND the actual native
> descriptor/configuration. Native delegation requires :schema-flexibility
> :write and implication of the logical predicate by that installed native
> check. Attribute name/type resemblance or the storage bridge's first
> :and arm is not that proof. Partition plan reuse by every relevant input;
> one projection used with a different native schema must not reuse a
> previous delegation decision. Unknown/unproved shapes retain validation.
>
> Remove equivalent scalar work at submission and attempted-datom seams,
> and the proven unreachable native unknown-attribute branch. Retain nested
> ref/map/lookup traversal, logical constraints, codecs, tuple members and
> attempted assertions, including idempotent and later-overwritten values.
> Delegate cardinality's storage semantics without pretending two valid
> cardinality-one assertions should refuse. Required-many presence and
> logical collection constraints remain Seon's work.
>
> Keep write-entity-error's complete compiled root/child validators intact
> in this slice. Keep required members, cross-field predicates, before/after
> ownership discovery, full EAVT assembly, node bound, identity-less orphan,
> missing child, shared owner, cycle and target-schema checks. Keep agent
> retention, symbolic deletion refusal, renderer targets and prepared arity
> checks in their existing final-report order. Do not replace complete
> assembly with wildcard pull or drop the full arity query on sparse writes.
>
> Preserve the two observed native gaps precisely: a missing numeric
> **component** target refuses here, while an ordinary missing numeric
> **peer** currently passes both validators; an all-wrong heterogeneous
> tuple can pass Datahike, so its authored logical member check stays.
> Neither observation grants a new general peer-existence policy or a
> dependency repair. Native component/string schema declarations also
> pass the measured write path; do not silently add consistency policy.
>
> Normalize delegated native failures through the existing write/error
> owners after their error-family work lands. Preserve native :error and
> original evidence, supplied operation/attribute/value and justified
> path/fix information. Never revive :seon.error/kind or manufacture a
> source position for transaction-function output. The six legacy-kind
> assertions below belong to error-family-1a's expectation conversion;
> obtain their landed correction through the orchestrator, not by changing
> production to satisfy a retired tag. Continue independent probe/census
> work across that boundary; no check removal claims parity while it is red.
>
> **Measure the same three workloads before/after, three runs each.**
> Report all runs and column medians for population, one root/two children,
> and one sparse canonical agent namespace-ref write. Separate lookup-only
> and scalar-delegation deltas; a codec change, if included, is a separately
> measured followup. Record real constructor/validator/owner/row counts,
> attempted/effective datoms and actual refusal values. Preserve baseline
> source/input identities and scripts as reproducible evidence. No timed
> assertion in CI and no bound increase to conceal work.
>
> Use the complete canonical fixture, explicit acquired projection,
> extra-schema only for synthetic boundary declarations, program-fn-row
> and transacted! helpers, and the real SCI path where specified. Every
> new/changed function has its complete contract. Count actual seams; never
> substitute a hand-rostered database, mock validator or unarmed function.
>
> Before the coherent path-limited commit, require changed production
> namespaces in one foreground JVM. Iterate with bin/test-fast --paths
> <all owned changed paths> -- seon.db-test seon.owned-value-test
> seon.reset-edges-test, adding the named conditional suites below. Respect
> result reuse: report executed, unchanged and unavailable separately,
> with recorded program/input/basis evidence. Zero executed is valid only
> with matching durable green evidence; it cannot stand in for fresh
> measurement or a new witness. Never use cold bin/test, --all or --full,
> prepare a baseline, or invoke nested cold gates. The orchestrator owns
> cold --paths and --platform proof.
>
> One foreground JVM at a time; never background/overlap probes and tests.
> Use the repository slot and declared bounded event waits. No worktrees.
> Foreign shared-tree breakage uses the fast HEAD-plus-owned-paths snapshot;
> name its precise boundary and continue independent work. If snapshot
> admission refuses, report it and keep the unproved removal uncommitted.
> Never edit a foreign hunk to get a load. Do not stop/restart/refork default,
> re-enable the paused hook or absorb the step-3 reset. Verify the affected
> live write after coordinated adoption; name hot reload versus in-place
> adoption versus owned scratch fork. Report proof still owed explicitly.
>
> **No adaptive bulk strategy, tuned size threshold, durable owner index,
> new peer-existence rule, second writer, validation queue, global cache,
> Datahike fork change, residual whole-entity schema compiler, instrumentation
> replacement or native-schema migration.** No selector/pull redesign,
> affected-arity redesign, production regex or new rendering clip point.
> No step 5 work. No deletion of logical checks to hit a LOC estimate.
>
> **Deliver:** one coherent path-limited commit; complete changed-path list;
> refreshed native-authority/removal ledger; indexed-lookup go/no-go with
> raw runs and medians; all three workload deltas and counts; exact native
> refusals, admitted reports and diagnostic parity; canonical fast tallies;
> measured gross deletions/additions; exact foreign, cold/platform and
> live-proof boundaries in the landing note. Estimate **2–3 lane-days**
> for the conservative cut, excluding path/gate/reset queues and separate
> codec work. Stop when committed. At a genuine design decision requiring
> broader machinery, stop before production edits with exactly three priced
> options, simplest viable constraint first/recommended, each stating
> guarantee, cost and what is given up.

## First bounded item — selective indexed discovery, measured go/no-go

The candidate replaces only `src/seon/db.clj:3585` inside `owners-of`.
`reference-code/datahike/src/datahike/db/interface.cljc:77–78` forwards
`dbi/search` to the database's search context. The existing native
`retract-entity` at `reference-code/datahike/src/datahike/db/transaction.cljc:998–1015`
already normalizes each ref attribute at `:1008–1010` and searches
`[nil a e]` at `:1011`. Reuse that idiom inside the existing DB owner:

```clojure
(let [a (if (db.utils/attr-has-ref? database attribute)
          (dbi/-ref-for database attribute)
          attribute)]
  (dbi/search database [nil a child-id]))
```

The production implementation uses the landed compiled component plan and
current local names; this is the native lookup shape, not a new public API.
`db/search.cljc:138–157` selects AVET for indexed attribute/value, AEVT
filtering for an unindexed pair; `:182–204` checks indexing and performs
the current-index lookup. Ref attributes are indexed by
`db/utils.cljc:307–313`. `db.cljc:234–254` contrasts contextual search with
public datoms' two `components->pattern` normalizations. Search still
validates its pattern (`db/search.cljc:28–70,165–174`) and may use native
memoization (`:19–26`); it is not a promise of zero validation or no cache.

1. Capture the landed steps 1–3 baseline and all source/pin/input identities.
   Use one bounded, repository-slot-holding foreground run with the same
   canonical contract arming as `bin/test-fast`. The research's finite
   1,200-second driver is the reference external process bound, not a new
   production deadline. Declare long work through the harness's existing
   duration metadata. On timeout preserve phase/counters and a full thread
   dump including virtual threads; do not silently retry or raise the bound.
2. Establish native/final parity and complete owner-edge equality before
   timing. For every reached `[phase,eid]`, compare fully realized edge
   vectors from both lookups, including ordering and empty seeks. Preserve
   before/after DB values, component-attribute iteration, caches, seeds,
   charge points and returned attribute identity. Exercise ordinary keyword
   attributes and the dependency's `:attribute-refs?` normalization mode
   using the canonical population through its existing constructor; no
   miniature schema stand-in. An unavailable mode is reported, not inferred.
3. Run baseline three times then lookup-only candidate three times in the
   same JVM for each workload below. Restore/re-arm any scoped instrumented
   roots using the canonical preservation fixture. Assert the replacement
   actually ran: candidate reverse calls are counted, public reverse AVET
   calls at this seam are zero, and no bulk reverse map is built. Retain
   EAVT/row/owner/entity counts and identical admitted/refused outcomes.
4. **Go** only with complete edge/diagnostic/atomicity parity, unchanged
   bounded traversal, lower median population owner-discovery cost and no
   increased median owner-discovery, final-validation or caller cost for
   either sparse workload. This is a measured selection criterion, not a
   numeric runtime threshold. Record all trial spread and host contention.
   Mixed/noisy or incomplete evidence is **no-go for this replacement**;
   keep `d/datoms` and continue the independently justified native-check
   cut. Do not switch algorithms by write size or claim parity proves speed.

After this decision, compare the selected lookup baseline with the same
lookup plus scalar delegation. This isolates the checks' contribution.
The historical combined prototype's population gain does not price either
change separately. A further strategy requires another owner decision.

## Dependency ledger — native authority and the retained final contract

The research's **38 cases = 16 native refusals + 22 admitted reports**.
The initial 34 direct cases included 12 refusals; four schema/identity
probes brought the total to 38. All 22 admitted reports matched baseline
and corrected prototype diagnostics after removing only `:seon.error/at`.
This is a finite measured inventory, not universal equivalence. Its
**11 tests / 103 assertions = 97 pass, 6 fail, 0 errors** in both modes is
explicitly non-green. Do not copy the unsuccessful first rewrite's timings:
it replaced zero lookup forms and was excluded by the research.

| Authority/check | Exact native witness or retained obligation |
|---|---|
| Unknown installed user attribute | `db/utils.cljc:177–192`; observed `{:error :transact/schema, :attribute :probe/unknown, :context [:db/add … :probe/unknown 1]}`. Preserve system/meta/schema exceptions and the absent-lookup retraction no-op. Prove operation paths before removing either unknown branch. |
| Native scalar and nil | `db/transaction.cljc:33–52,786–796`, native specs `schema.cljc:11–35`. Wrong long `"bad"` and `[1 2]` return `:transact/schema` with attribute/value/schema; nil returns `:transact/syntax` with value/context. Capture full actual values in the new evidence, not these abbreviated table renderings. |
| Cardinality | `db/transaction.cljc:718–777,786–811`: one replaces; many expands members. Two successive one assertions admit with final value 2; many scalar 3 admits; empty optional many emits no datom. These are positive semantic witnesses, not invented cardinality refusals. Required presence/min/max/order/relations remain logical obligations. |
| Identity and unique | `db/transaction.cljc:26–32,641–716`: upsert admits at existing eid; explicit conflicting identity produces `:transact/upsert` with entity/assertion; unique value conflict produces `:transact/unique` with attribute/datom. No additional uniqueness validator. |
| Lookup ref versus numeric ref | `db/utils.cljc:109–148`: missing lookup gives `{:error :entity-id/missing, :entity-id [:seon.agent/id "absent"]}`. Numeric `99999999` returns itself without existence query. Owned child existence remains ours; ordinary peer existence is not added. |
| Tuple gap | `db/transaction.cljc:1017–1043`: heterogeneous wrong count or mixed-validity members produce `:transact/syntax` with tx-data, but all-invalid booleans compare equal. Native-only `["bad" "bad"]` admits; an invalid authored `:seon.fn/call-arities` tuple must still refuse with `:seon.db/invalid-value`. Keep all logical tuple member checks. |
| Native schema admission | Research witnesses: incomplete map, reserved `:db/probe`, invalid cardinality and unsupported long→string change produce `:transact/schema`; direct composite tuple writes produce `:transact/syntax`. Transaction schema path `db/transaction.cljc:924–947` is not empty-db constructor validation. A string-valued component declaration admits on this write path; no new check here. |
| Attempted assertions and atomic final refusal | `db/transaction.cljc:1206–1216,1257–1276`: callback receives attempted plus effective data and final DB; any non-nil return aborts with `{:error :transaction/validation-rejected, :datahike/validation-refusal refusal}`. Keep attempted idempotent/overwritten logical values and transaction-function output. Native rejection before callback must be positively observed as an exception, never counted as an absent successful report. |
| Required members and deletion dial | Native sweep/cascade at `db/transaction.cljc:813–819,831–836,998–1015` makes swept survivors part of effective tx-data. Seon validates them at final state: required ref refuses, optional ref sweeps, coordinated repair/deletion succeeds. A fully deleted entity is skipped, a surviving incomplete entity is not. |
| Complete component value | Seon retains both-phase ancestor discovery, component targets as seeds, full EAVT row assembly, single ownership, target schema, no orphan/cycle/missing child and declared positive work bound. Pull's 1,000 limit cannot prove completeness (`pull_api.cljc:16,315,323`). |
| Lifecycle and program relations | Keep agent-identity retraction refusal; surviving symbolic function/namespace/schema referrers; renderer function existence; prepared arity consistency. Datahike stores symbols without knowing these relations. Their final-state order is unchanged. |

The residual decision is deliberately conservative. Follow retained aliases
in their captured scope; inspect the **whole** logical node, never the
storage-selected first conjunct. Keep every unproved `:and`, property-bearing
refinement, literal/enum, qualified symbol/keyword, predicate, collection,
tuple, ref and EDN logical validator. Broaden delegation only with a source
implication argument and native-refusal witness for that exact shape.
Storage/display properties alone are not evidence of stronger or weaker
validation. An unrecognized property must not disappear by assumption.

The native descriptor and write configuration belong to the database doing
the work. Final plans consume step 3's final candidate and installed schema;
submission cannot authorize omission of final logical checks. Plans created
for the same projection but different descriptors or `:read` flexibility
must retain the stronger validation where native enforcement is unavailable.
Neither an absent plan nor missing acquired generation means no checks.

Native delegation changes the enforcing layer, so distinguish two proofs:
retained Seon diagnostics compare completely, excluding only `:at`; delegated
diagnostics preserve the native classification and are checked against the
landed error contract, with a recorded old→new field mapping. This is not
permission to drop member/value/path/fix information or claim identical error
bytes where the native authority now supplies a different cause. Use native
context plus original submission when it identifies a location unambiguously;
otherwise report the available native/expanded location honestly.

Malli explanation `:in` and `:path` remain native and unchanged. Assertions
use violated member, offending value and `:in`, not a literal schema `:path`
vector. No explanation-schema transformation or path translation layer.

## Per-file implementation instructions — research table applied to HEAD

Census HEAD **3267839b0d6b665e784acc280745475b7e3220f9**, observed during
the 2026-09-20 session using the requested 2026-09-21 work-item date.
Step 1 landed at **dc1efaf3c**. Results-reuse landed through **7d27503b0**;
its recording/selection behavior is inherited, not work for this lane.
Steps 2–3 will move these anchors again. The source table was checked
against committed HEAD bytes; all five core source/test files below matched
their working copies at the census. No runtime proof was attempted here.

Every row of the research's “Exact production slice and gates” is included:

| Path / research → HEAD anchor | Required instruction and boundary |
|---|---|
| `src/seon/db.clj`, `write-attribute-plan` **3431 → 3441** | Extend this existing plan with native implication/residual selection from the retained compiled schema and actual installed descriptor/config. At `:3910` the outer plan table already keys by installed schema, but the inner cache at `:3446` currently keys only attribute/many; fix that reuse boundary when adding descriptor-sensitive decisions. Preserve decoder and collection normalization. Do not add a second validator cache; Malli retains the plain validator. |
| Same, `write-attribute-error` **3325 → 3335**, `write-error` **3419 → 3429**, `write-map-error` **3388 → 3398**, `write-ref-error` **3288 → 3298** | Remove only native-proved redundant scalar/unknown decisions. Keep nested reference/lookup/map traversal, logical checks, map/add grammar and justified missing-key suggestions. Move native diagnostic adaptation to the existing refusal path, not a pre-read that independently refuses the same native constraint. Preserve non-write-mode protection unless its impossibility is proved by admission. |
| Same, `write-report-error` **3890 → 3900**, attempted loop **3910 → 3920**, final arity call **3935 → 3945** | Keep attempted assertions plus effective retractions, union of before/after identity attributes and final database/candidate. Remove only proven native predicates and unreachable native unknown test; missing plan/generation is not success. Preserve agent→deletion→ownership→renderer→arity order at `:3937–3944`. |
| Same, `write-owned-values-error` **3513 → 3523**, `owners-of` **3567 → 3577**, `charge!` **3552 → 3562** | First perform the lookup-only probe. If go, replace the reverse request at `:3585` with normalized dbi/search; retain component iteration/evidence order, phase/eid caches, EAVT expansion, before/after seeds/targets, every charge and every refusal. No bulk map or persistent mirror. If no-go, leave this lookup unchanged. |
| `src/seon/schema/datahike.clj`, `decode-attribute-value-in` **610 → 610 (unchanged)**; DB plan **3431 → 3441** | **Separate codec followup, conditional scope:** the existing plan has already selected EDN decoding, but the closure re-enters edn-encoded-attr-in? per value. Reuse the selected decoder at its existing bridge owner if separately measured and small; keep storage-readers, storage-not-string, malformed-edn, noncanonical-edn, logical validation and round-trip behavior. Do not duplicate the decoder in db or claim the research measured its speedup. Otherwise retain it and record the deferred followup. |
| `src/seon/db.clj`, `write-entity-error` **3475 → 3485** | Retain the complete root/child validator and required-member/cross-field checks. Preserve identities before retraction and authored explanation/value paths. A residual whole-entity schema transformation is a later cut, not an obligation or a deletion credit here. `write-entity-value` **3452 → 3462** remains complete EAVT assembly. |
| Same, `arity-mismatches-with` **3685 → 3695**, `declared-arity-bounds` **3658 → 3668** | **Separate followup, read-only algorithm here:** whole-program call/bounds query stays, preparation still runs for candidates. An affected set must eventually include declaration/default/preparation changes; sparse data writes are not permission to skip it. Measure its cost independently and hand it to the relational-validator owner. |
| `test/seon/db_test.clj:1806,1877,1918,1945,1996`; `test/seon/owned_value_test.clj:38,57,78,90`; `test/seon/reset_edges_test.clj:20,45` | All eleven research test anchors are **unchanged** at this HEAD. Preserve their class coverage and add the missing native-delegation/gap/refinement/attempted/descriptor witnesses below. Consume the error family's expectation conversion rather than weakening causes or atomicity. |

Additional seams needed to make that table executable:

| Conditional path / HEAD anchor | Instruction |
|---|---|
| `src/seon/db.clj:3194` rejected-value; `:3255` invalid-write; `:4121,4230–4264` transact-call/native catch | Native translation stays here and through the existing error owner. Receive the original submission only for evidence; the native writer remains the decider. Preserve uniqueness evidence, flat typed refusal and unknown-outcome distinction. Refresh these after 1a; the current legacy-kind constructor is not a target grammar. |
| `src/seon/error.clj:320`; `src/seon/error/refusal.clj:37,74`; `test/seon/error_test.clj` | Error-family-1a owns the shared raw diagnostic/extraction contract. Prefer its landed implementation without editing these paths. Only a necessary native-evidence adaptation is in conditional scope after orchestrator release, with its existing regression extended; no broad error-family cleanup. |
| `resources/seon/schemas/seon.db.edn` | Only existing in-memory plan/function contracts if the landed complete contracts need the descriptor/config inputs. No stored attribute, population identity or native storage change. Coordinate the held resource and consumer in one publication. |
| `test/seon/schema/datahike_test.clj` | Conditional codec followup requires existing codec regressions here; otherwise read-only. Step 2 currently overlaps this path. |
| `AGENTS.md`, `.agents/skills/datahike/SKILL.md`, bridge PRD | Only source-backed current claims invalidated by the landed cut change with it. Historical research remains historical. Working edge and issue index stay orchestrator-owned. |

Supplemental final-report owners also moved **+10** from the research:
write-agent-retraction-error 3850→3860; write-deletion-error 3873→3883;
removed-definition-error 3779→3789; write-render-target-error 3746→3756;
write-report-validator 3952→3962. The latter still acquires the node limit
from the final DB (or explicit construction default), outside the measured
write-report-error body. These line moves are observed, not an attribution
of all intervening changes to the step-1 commit.

## Canonical parity regressions and the six error-family assertions

Extend existing class regressions. The complete baseline is captured after
steps 1–3 and error-family corrections land, **before removing any check**.
For each removal retain a ledger row: exact old check/span, compiled logical
input, installed native descriptor/config, operation grammar, surviving
native file:line, actual native refusal map, Seon normalized refusal and
unchanged basis. A check without that witness stays. Counterexamples that
native admits must still reach and exercise the retained final callback.

| Existing class / test owner | Required proof |
|---|---|
| `seon.db-test/all-transaction-grammars-validate-the-resulting-entity` (`:1806`) | Map, add, nested map/ref and nested tx-function output; incomplete create refuses; sparse upsert validates merged row; invalid logical assertion followed by repair still refuses atomically; valid composed final row succeeds. Add a native-wrong value followed by repair and positively observe the native refusal. |
| `seon.db-test/a-create-is-validated-complete-while-an-upsert-validates-the-merged-row` (`:1877`) | Missing required member names the entity/key and commits nothing; complete deletion skips the now-empty row. Required empty-many still refuses; optional empty-many emits no datom and remains admitted. |
| `seon.db-test/required-program-relations-name-the-surviving-referrer` (`:1918`) | Deleting namespace/function sweeps a required peer relation; final refusal identifies :seon.fn/ns or :seon.schedule.task/function and surviving referrer. Add optional-ref sweep counterpart and both orders of coordinated repair/removal. Presence is not numeric target existence. |
| `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state` (`:1945`) | Callee bounds changed under existing caller refuse with exact caller/callee/count/declared/prepared bounds; same-transaction caller repair succeeds. Keep preparation/default-related existing cases selected from the graph; no narrowed-query shortcut. |
| `seon.db-test/render-declarations-require-their-final-function-row` (`:1996`) | Missing renderer function refuses; coordinated creation succeeds; function deletion alone refuses; deleting the declaration and function together succeeds. Preserve exact declaration evidence. |
| `seon.owned-value-test/owned-values-validate-child-only-writes-and-before-owners` (`:38`) | Child-only required-value retraction; unlink leaving child orphan; old/new owners on reparenting; link+child deletion in either order. Preserve before-owner reach and transaction atomicity. |
| `seon.owned-value-test/incomplete-unowned-cyclic-and-shared-components-refuse` (`:57`) | Identity-less orphan, missing numeric component, cycle and multiple owners each refuse with exact cause/owner/attribute evidence. Add missing component-schema case. Native admission of these writes is demonstrated before the Seon callback rejects. |
| `seon.owned-value-test/complete-values-include-the-1001st-child` (`:78`); `the-projection-carries-the-declared-work-bound` (`:90`) | The 1,001st child is actually present and checked; limit 1,000 refuses with limit/visited evidence. Missing/nonpositive bound is a typed refusal; preserve reached-node charge semantics. No bulk unrelated-edge scan outside the bound. |
| `seon.reset-edges-test/named-edges-refuse-deletion-until-the-final-callers-are-repaired` (`:20`); `removing-a-name-from-a-surviving-entity-refuses-its-callers` (`:45`) | Real indexed target has nonzero callers; full deletion, identity retraction and rename refuse with complete final surviving symbolic referrers and unchanged basis. Repair callers in the same transaction succeeds. Preserve current namespace/schema and historical-reach cases in the full namespace too. |
| Native delegation and residual, in `seon.db-test` | A valid scalar write executes zero redundant scalar predicates at the delegated seam; native-wrong value produces actual native refusal. Refined :and, min/max, enum, qualified names, custom predicate and EDN shape still refuse appropriately. Retain full whole-entity validators: their calls are not counted as eliminated attempted-scalar calls. |
| Native gaps, in DB/owned tests | Missing numeric peer is admitted and stored; the same absent numeric owned target refuses. All-wrong heterogeneous tuple is native-admitted but rejected under its authored Malli declaration. Do not assert that the native-only attribute without an authored declaration magically gains a Seon tuple policy. |
| Agent retention, existing DB regression | Native application admits agent identity retraction; Seon's final callback refuses with cause :seon.agent/id and the archive instruction. Identity rename/retraction commits nothing. Keep this lifecycle rule independent of the symbolic program-deletion check. |
| Attempted/descriptor/candidate coverage, in `seon.db-test` | Logical invalid-then-repaired and idempotent invalid assertion remain checked; raw native setup of the latter is explicit and independently observed, never hidden fixture corruption. Test warm plan reuse across different installed descriptors/config and generations. Cover a declaration/native update within one transaction so the final candidate/descriptor, not a cached pre-write decision, governs. |
| Codec behavior, existing DB/bridge tests | Canonical round-trip; malformed, noncanonical, wrong storage type and decoded logical invalid values retain refusal. If codec work is deferred these remain preservation tests, not a speedup claim. |

The [deletion study](../research/datahike-deletion-and-the-program-graph-2026-09-16.md)
supplies additional functional assertions, not a new benchmark: cascade
removes both parent and child; history retains child evidence; as-of returns
the earlier child; a reasserted deleted identity gets another eid; an empty
many component value emits no relationship datom. Count parent/child removal
separately for its **six assertions per mode**. Also preserve symbolic value
edges and positive analysis-digest evidence: no tombstone, fake identity or
empty-set “analyzed” marker. The study's historical options/retired-tx prose
do not override AGENTS §3's current retraction ruling. Its 5,000-function
reach timings are unrelated to this parity gate and are not rerun here.

**Six failures are six invocations of one stale assertion, not six tests.**
All point to `test/seon/owned_value_test.clj:34`, the helper
`refuses-without-change`, which expects `:seon.error/kind :seon.db/invalid-write`.
The observed ownership diagnostic already has `:seon.db/transaction-refused
true`, cause/evidence and unchanged basis. The exact members are:

| Test / invocation at current HEAD | Expected retained cause | Conversion owner |
|---|---|---|
| owned-values-validate-child-only-writes-and-before-owners, unlink at `:45` | :seon.db/unowned-entity | error-family-1a |
| incomplete-unowned-cyclic-and-shared-components-refuse, standalone row at `:61` | :seon.db/unowned-entity | error-family-1a |
| Same test, numeric child 99999999 at `:64` | :seon.db/missing-component | error-family-1a |
| Same test, cycle at `:67–69` | :seon.db/component-cycle | error-family-1a |
| Same test, shared child at `:72–75` | :seon.db/multiple-component-owners | error-family-1a |
| the-projection-carries-the-declared-work-bound, 1,001 children at `:98–99` | :seon.db/validation-node-limit | error-family-1a |

The required-child and 1,001st-child missing-member cases take the ordinary
entity diagnostic path and were not these six legacy-kind failures. Have
1a/the orchestrator supply the helper's declared-facet assertion conversion,
retaining atomicity and specific causes; do not relax it to “some map”.
Step 4 owns rerunning and reporting the now-current tests. If additional
kind assertions become stale after 1a, record that exact boundary and obtain
the same coordinated conversion; the historical count is not a test roster.

## Before/after measurement contract

The research's corrected same-JVM comparison used **455,227 attempted and
effective datoms per population**, 170,313 owner calls, 52,215 EAVT reads,
111,264 row-cache calls and 37,088 entity checks in each mode. Lookup counts
include cache hits, not that many physical seeks. These are dated evidence,
not expected constants for the later steps 1–3 population.

| Historical median, milliseconds | Baseline | Bulk prototype | Interpretation |
|---|---:|---:|---|
| Population application including final validator | 36,111.857 | 15,710.665 | Combined prototype, 2.30×; not scalar-only gain. |
| Population final validator | 29,009.398 | 9,842.995 | Retained logical entity validation. |
| Population owner discovery | 24,374.047 | 3,821.030 | Bulk scan benefit on this population only. |
| Population entity checks | 1,040.702 | 1,046.960 | Essentially unchanged. |
| Population memory commit completion | 9.477 | 7.587 | Separate from application; not file-store commit. |
| Small owning write caller / validator / owner | 130.594 / 118.191 / 2.654 | 244.260 / 226.387 / 22.576 | Sparse write slowed; no universal bulk scan. |
| Agent write caller / validator / owner | 131.607 / 117.913 / 1.048 | 156.682 / 144.058 / 19.572 | Sparse write slowed. |
| Small / agent arity query | 114.106 / 115.067 | 136.742 / 122.655 | Whole-program cost remains; do not attribute it to owner lookup. |

At landing publish **all three raw runs and the median of each column**
for baseline, lookup-only candidate and final conservative diet. Use the same
cached manifest and native/logical input population for each comparison;
manifest construction is outside the application timer. Initial population
uses the canonical `:fresh-store?` path and real populate-source! builder.
Writing to the fixture's already-populated branch is not initial publication.

The small case creates one identified root and two identity-less owned
children; each mode gets its own canonical branch and three actual writes.
The agent case creates the canonical agent before timing, then changes its
namespace ref through lookup refs to existing `seon.db`, `seon.id` and
`seon.turn` namespace rows. It is a representative sparse write, not a full
Juniper turn or paid provider run. Record first-use behavior; exclude refused
setup/incorrect input timings with the reason, as the research did for its
initial symbol supplied to a ref-valued namespace.

Measure caller wall, application including validator, memory commit channel
completion, final validator, owner discovery, traversal/assembly residual,
entity checks, arity derivation and application outside final validation.
The residual is owned-value time minus owner lookup minus entity checks;
identify it as subtraction. Do not add inclusive row/EAVT/ancestor timers
to their parents or expect independently computed medians to sum.

Count attempted/effective datoms, reached phase/eids, component attributes,
empty/nonempty physical reverse requests, returned owner edges, owner cache
hits, row/EAVT reads, entity checks and delegated/residual validations.
Count Malli schema/validator/projection-validator APIs and the real generation
constructor/loader seams inside final validation. The research observed
32,876 / 2,980 / 1,490 API calls and zero calls to its three declaration
construction seams; API calls are not synonymous with new compilations.
Use the post-step-3 owners, not zero counts of retired function names.
Record any separately included decoder classification/count delta.

Record Git commit, exact source SHA-256, dependency pins, canonical input
identity/population stamp, native schema/config and workload sizes. Same-JVM
sequential baseline→candidate trials preserve the research method but retain
warm-up, GC, instrumentation and host-contention limitations. Fresh store
population values differ per run; logical input identity must not. Keep
pre-comparison warm-up observations separately. No mixing the research's
earlier 455,127-datom JVM or historical 74,366 operations/1,304,168 attempted
datoms with the new sample. No full-publication-under-30-seconds target.

Research scratch scripts under `tmp/probe/step4/` were intentionally not
committed and may no longer exist. Read them if present; never treat their
absence as missing production proof. Preserve the repeatable measurement
entry in the existing test owner and its exact invocation/results in the
landing note. A scratch source rewrite must assert its exact replacement
count and preserve `seon.db` reader namespace; zero replacement is a failed
probe. Recurring parity witnesses stay in the canonical tests. Restore all
instrumented roots, close resources and remove owned disposable roots after
their processes exit. Never sweep another gate's roots.

## Gates, live proof and held-path coordination

Before commit, expand this command only for production owners actually changed:

```bash
clojure -M -e "(require 'seon.db 'seon.schema.datahike)"
bin/test-fast --paths <all owned changed paths> -- seon.db-test seon.owned-value-test seon.reset-edges-test
```

Include `seon.schema.datahike-test` for codec/bridge changes, `seon.error-test`
for a released shared diagnostic adaptation, and current graph-selected
affected callers. The fresh parity/measurement work must execute; named
suite members may legitimately reuse recorded greens after results-reuse.
Record request identity, actual executed/unchanged/unavailable counts and
program/input/tested-basis confidence. The research tally is not inherited
green evidence. Recording failure is not a successful durable verdict.

The orchestrator owes `bin/test --paths <same slice> -- seon.db-test
seon.owned-value-test seon.reset-edges-test` plus conditional suites and
`bin/test --platform`. No lane invokes those commands or prepares a missing
published base. Coordinate post-step-3 reset/adoption separately. Step 4
changes no stored schema and claims no additional RESET NEEDED by default;
an unexpected native schema delta is a failed premise, not a reason to reset
and conceal it.

For live proof use the acquired generation after coordinated adoption:
perform one valid sparse write and one expected logical refusal on disposable
facts, observe committed value or unchanged basis respectively, and retain
the exact native refusal through the same public transact! path. Name the
cluster/source/population identities and whether this exercised hot-reloaded
Vars, in-place adoption or a scratch fork. Use qualified MCP custody and
report tool unavailability immediately. If the proof changes a surfaced
agent value, observe its page separately. No default restart or paid run.

At the census, **1a** held error/refusal, instrument and SCI files plus
`seon.db.edn`/`seon.sci.eval.edn` and error tests; **step 2** was active on
compiled navigation, including the DB/bridge/test overlap; publication and
render work also changed files while this document was read. By launch,
**step 3** must have released DB/bridge acquisition and candidate propagation.
Coordinate these intersections through the orchestrator. The results-reuse
landing frees its former paths but does not erase recorded foreign proof
boundaries. Consult current status, not this dated list, before any edit.

## Measured deletion budget and estimate

Physical inclusive LOC at the census HEAD, including comments/contracts and
separating blanks; these spans do not overlap. This is a review surface,
not permission to delete a complete mixed-purpose function.

| Current owner span | LOC | Disposition |
|---|---:|---|
| `src/seon/db.clj:3335–3381` write-attribute-error | 47 | Remove native-proved branches only; logical/nested grammar remains. Unknown branch itself is `:3350–3354`, 5 lines; scalar validation/failure branch is `:3376–3380`, 5 mixed lines. |
| `src/seon/db.clj:3398–3440` write-map-error + write-error | 43 | Preserve traversal and diagnostic context; simplify only after native-error adaptation retains evidence. Neither entire function is declared dead. |
| `src/seon/db.clj:3441–3461` write-attribute-plan | 21 | Replace repeated scalar validation with a smaller retained residual decision; descriptor/config-aware plan may add code. |
| `src/seon/db.clj:3577–3588` owners-of | 12 | Conditional lookup substitution; no entire owner algorithm deletion. Reverse public request is one line, `:3585`. |
| `src/seon/db.clj:3900–3961` write-report-error | 62 | Retain final orchestration. Attempted loop `:3920–3936` is 17 mixed lines; duplicated native unknown predicate `:3926–3927` is 2 lines inside it. |
| **Core nonoverlapping review surface** | **185** | 47 + 43 + 21 + 12 + 62; largely retained policy, not 185 net deletions. |
| `src/seon/schema/datahike.clj:610–627` decoder | 18 | Conditional followup; move/reuse selected decoding, retain behavior. Not core deletion credit. |

Explicitly retained and excluded from the budget: complete entity check
`:3485–3522` (**38 LOC**), full owning-value owner `:3523–3667`
(**145 LOC**, including the 12-line lookup above), and arity-mismatches-with
`:3695–3741` (**47 LOC**). No overlapping double count. The whole 4,462-line
db.clj and 635-line bridge are not disposal targets. Committed source SHA-256:
DB `07048b22533b981c0d9924cda4b81c663da5e350b3dd778ac10a292e9f554c5e`;
bridge `e4291b3dc28dd8076e3541303f0212b82f3be79d4d5c582dc7a36d5615e925f8`.

PRD §4's **80–180 gross deletions / 40–120 additions, 2–3 lane-days,
3–6 files** remains a planning range, not measured guaranteed savings.
Core ownership is one production owner, three regression files
and one landing note; codec/shared diagnostic/contract changes add coordinated
paths. The conservative cut may delete less than that range because complete
entity validation stays. Report actual `git diff --numstat`, retained/moved
behavior and the surviving authority for each deletion. Do not invent a
whole-entity residual compiler to meet a deletion number. Price the optional
codec followup separately at **0.5–1 lane-day**; affected-arity work is excluded.

## Design-lane verification boundary

Read both prior launch specs, the binding validator measurement, the bridge
PRD and the deletion study **end to end**; read AGENTS §3 and applicable lane
rules, the roadmap entry, current working-edge blocks, Datahike and
data-oriented-clojure skills and cited dependency/source seams. Also read
the canonical testing skill for the future gate contract. Dependency pins
verified at HEAD:

| Dependency | Gitlink |
|---|---|
| `reference-code/datahike` | `e11845bac78e1241bca0766ddc07d978bd63d74a` |
| `reference-code/malli` | `606083c5c5b388e84d169c7080af33ed3ec242ae` |

Malli's retained validator/explainer cache is at
`reference-code/malli/src/malli/core.cljc:345–361,2626–2657`, and retained
Registry lookup at `reference-code/malli/src/malli/registry.cljc:97–104`.
These dependency mechanisms are consumed, not reimplemented by the plan.

Only this document is authored. No source/test/resource edits, JVM launches,
runtime evaluations, cluster operations, test gates or worktrees. The
specific no-JVM/no-worktree restriction governs over the generic fallback
paragraph. During a read command the shell hook reported foreign syntax
errors in `src/seon/fn.clj:2391–2450` (if arity and mismatched delimiters).
This lane made no source writes or loads, did not attribute a runtime cause,
and continued read-only inspection without repairing those bytes.

Document links, committed-source anchors, arithmetic and whitespace are this
design's verification. Lookup speed/parity, native witnesses, canonical green
regressions, cold/platform proof and adopted live behavior remain the named
implementation/orchestrator obligations. Commit only this document path and
stop.
