---
type: research
status: complete
created: 2026-09-20
tags: [datahike, malli, validator, measurement, bridge]
---

# Step 4: write validation, ownership traversal, and the native boundary

Research-only lane. No production edits, cluster operations, worktrees, or cold gates.
**Result:** owner discovery dominates the population. The corrected bulk prototype
reduces median application from **36,111.857 to 15,710.665 ms**, but slows small
writes. All compared final-refusal values match; both regression modes retain the
same six existing diagnostic-kind failures. This is measured parity with a named
non-green gate boundary, not permission to remove checks indiscriminately.
The owner's section 6.2 ruling is the target: trust Datahike's native write checks;
retain final-state requiredness, complete ownership, deletion refusal and relational
invariants. Removing a Malli scalar check is sound only where its accepted set is
implied by the installed native declaration. A minimum, enum, qualified symbol,
predicate, EDN codec or tuple member constraint is not merely a native scalar type.

## Authority and method

Read AGENTS.md §§1–4 and lane rules 11–16; the Malli-native bridge PRD end to end
(and re-read ruled §6 on the orchestrator's followup); the bridge dissolution review
end to end; writer-hang root-cause note end to end; data-modeling guide §§1–3;
and the Datahike skill end to end. Also read the deletion study end to end
when selecting its current functional cases. Applied the data-oriented Clojure, REPL and
canonical testing skills. Authorities:
[bridge PRD](docs/prds/steward-platform/plan/malli-native-bridge-prd-2026-09-20.md),
[bridge dissolution review](docs/prds/steward-platform/research/bridge-dissolution-review-2026-09-20.md),
[writer hang study](docs/prds/steward-platform/research/writer-hang-root-cause-2026-09-18.md),
[data-modeling guide](docs/seon/architecture/data-modeling-guide.md),
[deletion study](docs/prds/steward-platform/research/datahike-deletion-and-the-program-graph-2026-09-16.md),
and [Datahike skill](.agents/skills/datahike/SKILL.md).

The historical 74,366 **operations** and 1,304,168
**attempted datoms** came from different runs: neither is the current sample size.
The historical 34.38% validator share and 1.96% entity-validator share are not
new measurements.

The finite scratch driver and its scripts are under `tmp/probe/step4/` (not
committed by instruction). `audit-run.sh` acquires the repository's existing test slot,
executes one JVM in the foreground, has a 1,200-second process bound, and releases
the slot on exit. `audit-driver.clj` calls the same `seon.test.arm/initialize-contracts!`
as the fast harness. `audit-setup.clj` uses `seon.test-support/with-database` with the
explicit fresh-store observation: measuring initial publication cannot be proved
by writing to an already populated branch. That fixture calls the existing
`cluster/populate-source!` builder over `test-support/source-manifest` in a memory
store, never a cluster. Manifest construction is outside the measured transaction.
Ordinary refusal/component tests use the canonical branched fixture.

Dependency ledger: `reference-code/datahike/src/datahike/schema.cljc` owns native
scalar specs; `db/utils.cljc` owns attribute and entity-id resolution;
`db/transaction.cljc` owns operation processing, attempted/effective datoms,
components and final report validation. `src/seon/db.clj` installs the callback;
`test/seon/test_support.clj:338` invokes the production population builder and
`:1028` supplies the canonical fixture. `test/seon/owned_value_test.clj:11` adds
synthetic owned declarations to the complete packaged population; it is not a
hand-rostered substitute for it.

## Datahike refusal inventory

Paths abbreviated **T** = `reference-code/datahike/src/datahike/db/transaction.cljc`,
**U** = `reference-code/datahike/src/datahike/db/utils.cljc`, **S** =
`reference-code/datahike/src/datahike/schema.cljc`. Line numbers are for the inspected
working dependency, not the older writer note. All `src/seon/db.clj` line citations
refer to the measured bytes at commit `1b328d243` (hash below); a concurrent
uncommitted edit shifted the checkout after the JVM loaded them.

| Check | Native write authority and behavior | Seon consequence |
|---|---|---|
| Unknown attribute | U:177–193, `validate-attr-ident`; `:write` refuses an attribute absent from installed schema, except declared system attribute families. A retraction whose lookup-ref subject is absent can no-op before attribute validation (T:1060). | Duplicated unknown-attribute test can delegate; diagnostics still need adaptation. |
| Scalar native value type | S:11–35; T:33–52, `validate-val`; `:transact/schema`. | Delegate equivalent native scalar predicates. Malli refinements remain. |
| Nil | T:35–37 refuses `:transact/syntax` even independently of `:write`. | Delegate native nil refusal; omission/requiredness remains ours. |
| Cardinality one | T:786–812 replaces the previous value; it does **not** refuse two successive valid assertions. A vector supplied to a scalar long fails its value type. | Cardinality is native storage semantics, not a universal “multiple values” error. |
| Cardinality many | T:718 / :739, `maybe-wrap-multival` / `explode` expands members; scalar syntax is legal; an empty collection emits no datoms. | Final required-many presence and min/max member counts remain ours. |
| Missing numeric peer ref | U:110–147 returns a numeric id without looking for datoms; T:793–796 resolves it. | No native foreign-key guarantee. Existing owned-child existence checks must stay; ordinary peer existence is a separate policy question. |
| Missing lookup ref | U:140–147, `entid-strict`; `:entity-id/missing`. | Native resolution already refuses absent lookup identities. |
| Unique value conflict | T:26–32, `validate-datom`; `:transact/unique`. | Native, not an additional whole-entity uniqueness validator. |
| Schema declaration/update | T:924–947 refuses incomplete schema maps, reserved `db` identifiers and unsupported schema changes. T:91 also protects system schema entities and unsupported index changes. | Native schema admission; not final entity requiredness. |
| Unique identity | T:641, `upsert-eid` resolves existing identity; conflicting explicit identities can refuse. | Upsert itself is admission, not a conflict. |
| Component declaration | **Admits** a transaction declaring `:db/isComponent true` with `:db.type/string`. T:91 `update-schema` does not call the component/ref consistency check. `db.cljc:828` has that check, but `empty-db` :910–912 selects it on the `:read` construction branch; the dependency component test uses that constructor. | Both native and current Seon final validation admit this schema-only transaction. Do not cite the constructor test as write-path enforcement. |
| Component lifecycle | T:831 / :998, `retract-components` and `retract-entity` cascade outgoing components and sweep incoming refs. | Native lifecycle; required-ref refusal is ours after the sweep. |
| Component completeness, exclusivity, cycles | Native cascade/expansion does not enforce these ownership invariants. | Keep full before/after ownership validation. |
| Homogeneous tuple | T:1017, `check-tuple`: at most eight members, uniform JVM member type, first member conforms to declared type. | Native restrictions are narrower than “any vector”; retain logical constraints beyond them. |
| Heterogeneous tuple | T:1017, `check-tuple`: correct count; mixed valid/invalid member outcomes refuse, but **all-invalid outcomes compare equal and pass**. | Keep Malli tuple member validation. Do not drop it on the strength of `:db.type/tuple`. |
| Composite tuple | T:1017, `check-tuple` refuses direct writes to derived tuple attributes. | Native maintenance, not Seon ownership validation. |
| Required keys, relational predicates | Not automatically enforced by `:schema-flexibility :write`. Explicit `:db/ensure` can check declared attrs/preds during operation processing; it is not an automatic final report check for all swept rows. | Keep final entity/relational checks. |
| Surviving symbolic program referrers | Symbols are ordinary values; native deletion cannot infer their meaning. | Keep deletion refusal over final program facts. |

Dependency regression grounding: `reference-code/datahike/test/datahike/test/schema_test.cljc:172`
(`testing-type`), `:244` (cardinality), `:389` (schema updates);
`components_test.cljc:16` and `:62` (component declarations, expansion/cascade);
`tuples_test.cljc:33` (homogeneous constraints, heterogeneous count and **mixed**
type mismatch). That tuple regression does not test the all-invalid case.

## Every Seon final-report check and the overlap

These are source responsibilities, not a claim that every native diagnostic has
identical wording to Seon's wrapper. Source owner is `src/seon/db.clj` throughout.

| Owner | Check | Classification |
|---|---|---|
| `write-error` :3419; `write-map-error` :3388; `write-attribute-error` :3325; `write-ref-error` :3288 | Caller-side literal/nested submission validation, unknown attributes, lookup syntax normalization, authored logical constraints. Does not see all native transaction-function output. | Equivalent native scalar/unknown checks duplicated; logical refinements ours-only. This is **before**, not inside, final validation. |
| `write-report-error` :3890, attempted-datom loop :3910 | Checks added **attempted** datoms, including idempotent assertions and values overwritten by later operations. Decodes encoded values before logical validation. | Native scalar/unknown checks duplicated; tuple, enum, min/max, predicate and codec constraints ours-only. Checking only final effective values loses current refusal behavior. |
| `write-agent-retraction-error` :3850 | Any effective retraction of an agent identity refuses; archive instead. | Ours-only lifecycle invariant. |
| `write-deletion-error` :3873 → `removed-definition-error` :3779 | Removed function/namespace/schema identities with surviving final symbolic referrers refuse. Handles required program edges and same-transaction repairs. | Ours-only; Datahike cannot interpret symbol-valued edges. |
| `write-owned-values-error` :3513; `owners-of` :3567 | Discover ancestors from **before and after**, seed component targets as well as touched subjects; refuse multiple component owners. | Ours-only reach/ownership. |
| Same owner, `charge!` :3552 | Declared node-work bound must exist and be positive; exceeding it refuses. | Ours-only bounded proof. |
| Same owner, postorder traversal :3600 | Complete child expansion; declared child schema must exist; missing children, cycles and multiply owned children refuse. | Ours-only. Native components provide cascade, not these guarantees. |
| Same owner, root check :3644 | Nonempty identity-less unowned roots refuse; a fully deleted row is skipped. | Ours-only. |
| `write-entity-error` :3475 | Whole resulting root and owned-child schemas; include identities from before retraction; normalize cardinality-many collections; required members, logical scalar/collection constraints and declared cross-field predicates. | Native leaves duplicated within complete validators; requiredness and stronger predicates ours-only. Preserve exact failure paths. |
| `write-render-target-error` :3746 | Declared render symbols must have final function rows; called when schema form/key or function identity changes. | Ours-only relational invariant. |
| `arity-mismatches-with` :3685; `declared-arity-bounds` :3658; final call :3935 | Query all source call counts and declared arities, compute preparation only for candidates, refuse remaining mismatches. Runs on every nonempty affected set. | Ours-only relational invariant. “Affected arity derivation” is currently a whole-program query, not a narrowed dependency set. |
| `write-report-validator` :3952 | Acquire configured validation-node limit from **final database**, else bootstrap projection default. | Ours-only policy acquisition, outside the timed `write-report-error` body. |

The expansion already caches one EAVT reconstruction per `[phase, eid]`:
`write-entity-value` :3452 and local `row` :3558. The expensive structural question
is owner discovery: for each uncached entity/phase, `owners-of` seeks AVET for
**every installed component attribute**. There is no native VAET index
(`reference-code/datahike/src/datahike/db.cljc`, index fields; U:307 indexes refs
by AVET). “One indexed seek per touched owner” accurately describes outgoing EAVT
expansion; it cannot promise a single reverse-owner lookup without a new index.

## Measurements, prototype and parity

Three fully instrumented baseline population runs verify that **owner discovery,
not entity validation, dominates this sample**. The median final validator took
39,246.375 ms, including 33,454.349 ms in owner lookup and 1,235.672 ms in
entity checking. Timings are wall time on a contended host, with the instrumentation
below; this earlier JVM is context, not the final baseline/prototype comparison below.

| Earlier JVM 16656 baseline (ms) | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Transaction application, including validator | 47,585.351 | 47,872.989 | 39,662.413 | 47,585.351 |
| Memory-store commit completion | 17.849 | 11.815 | 13.047 | 13.047 |
| Final validator | 39,246.375 | 39,464.038 | 32,076.015 | 39,246.375 |
| Owner discovery | 33,454.349 | 33,727.977 | 27,275.451 | 33,454.349 |
| Owned traversal/assembly residual | 2,483.741 | 2,115.042 | 1,680.775 | 2,115.042 |
| Entity validator calls | 1,218.236 | 1,493.203 | 1,235.672 | 1,235.672 |
| Application outside final validator | 8,338.976 | 8,408.950 | 7,586.397 | 8,338.976 |
| Arity derivation/check | 982.416 | 807.997 | 779.499 | 807.997 |
| Malli `schema` API, inclusive | 157.527 | 163.236 | 114.579 | 157.527 |
| Malli `validator` API, inclusive | 10.055 | 9.370 | 7.162 | 9.370 |
| Projection validator acquisition, inclusive | 97.718 | 88.615 | 64.547 | 88.615 |

All three runs made 32,876 Malli schema API calls, 2,980 validator API calls and
1,490 projection-validator acquisitions. **Zero** calls to the instrumented
`declaration-projection`, `build-projection` and `assert-compilable-schema!` seams
occurred inside final validation. This supports the registry fix's intended
removal of whole-declaration reconstruction from this path; it does not assert
that no compilation occurs elsewhere. Nested API timings must not be added to
entity or traversal times. Column medians also need not sum.

A separate initial population, before all comparison timers were installed,
took 29,710.964 ms application / 21,312.795 ms final validation / 7.836 ms memory
commit. It is excluded from the three-run medians. The older file-store 7.3-second
commit is a different storage experiment and cannot be compared to these memory
commit milliseconds.

### Final same-JVM population comparison

JVM **34655**, baseline three times followed by prototype three times. Every run
applied **455,227 attempted and effective datoms** from the same cached publication
manifest. Each mode made 170,313 owner-lookup calls, 52,215 EAVT entity reads,
111,264 row-cache calls and 37,088 entity checks. Each prototype run built exactly
two reverse maps, one for each immutable before/after database. No refusal occurred.

| Population (ms; baseline / prototype) | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Application including validator | 41,017.733 / 16,289.564 | 36,111.857 / 14,944.013 | 33,266.340 / 15,710.665 | 36,111.857 / 15,710.665 |
| Memory commit completion | 11.048 / 7.326 | 9.477 / 7.587 | 8.592 / 14.463 | 9.477 / 7.587 |
| Final validator | 33,120.699 / 9,842.995 | 29,009.398 / 9,348.999 | 26,268.890 / 10,168.380 | 29,009.398 / 9,842.995 |
| Owner discovery | 27,875.952 / 4,020.378 | 24,374.047 / 3,676.714 | 22,277.826 / 3,821.030 | 24,374.047 / 3,821.030 |
| Traversal/assembly residual | 2,101.214 / 1,597.766 | 1,681.981 / 1,393.808 | 1,344.363 / 1,832.102 | 1,681.981 / 1,597.766 |
| Entity checks | 1,040.702 / 1,081.228 | 1,062.842 / 968.106 | 963.432 / 1,046.960 | 1,040.702 / 1,046.960 |
| Application outside final validator | 7,897.034 / 6,446.570 | 7,102.459 / 5,595.013 | 6,997.450 / 5,542.286 | 7,102.459 / 5,595.013 |
| Arity check | 749.623 / 676.327 | 712.583 / 646.218 | 736.635 / 799.242 | 736.635 / 676.327 |
| Owner-index build (included in owner) | 0.000 / 9.537 | 0.000 / 9.371 | 0.000 / 9.136 | 0.000 / 9.371 |

Median application improves **2.30× (56.49% less wall time)** and validation
improves **2.95× (66.07% less)**. Owner discovery falls **84.32%**. Entity checking
is essentially unchanged: the prototype deliberately retains complete logical
entity validators. The measured combined delta is not an isolated estimate of
the scalar-delegation benefit. Runs were sequential, not randomized; warm-up,
GC, host contention and instrumentation remain limitations.

| Inclusive schema API work (ms; baseline / prototype) | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| `malli.core/schema` | 193.963 / 123.820 | 143.579 / 95.904 | 106.018 / 157.324 | 143.579 / 123.820 |
| `malli.core/validator` | 13.285 / 8.098 | 9.544 / 6.299 | 6.098 / 11.645 | 9.544 / 8.098 |
| `seon.schema/projection-validator` | 111.803 / 69.823 | 83.807 / 54.255 | 58.109 / 90.514 | 83.807 / 69.823 |

Both modes made the same 32,876 / 2,980 / 1,490 calls respectively. Again there
were **zero** calls inside final validation to the three instrumented declaration
construction seams. Those zero counts are not a claim about work outside validation.

### Observed refusal values (canonical fixture)

The native column below comes from `d/with` on the fixture's immutable database,
with a nil-returning report observer; the Seon column uses the real final-report
callback on the same database/operations. Submission checks were also evaluated
separately. Native refusal values are the actual exception `:error`; the Seon
value is `:seon.error/data :seon.error/diagnostic-cause` unless indicated.
`admit` means transaction application returned a report, not “we saw no error log.”

| Probe | Native result | Seon final result |
|---|---|---|
| Unknown `:probe/unknown` | `{:error :transact/schema, :attribute :probe/unknown, :context [:db/add … :probe/unknown 1]}` | Same native refusal before callback |
| Long attribute assigned `"bad"` | `{:error :transact/schema, :value "bad", :attribute :seon.owned-value-test/value, :schema …}` | Same native refusal |
| Long attribute assigned `[1 2]` | `:transact/schema`, value `[1 2]` | Same native refusal |
| Nil value | `{:error :transact/syntax, :value nil, :context [:db/add … :seon.owned-value-test/value nil]}` | Same native refusal |
| Two successive cardinality-one values | admit; final value 2 | admit |
| Cardinality-many scalar `3` / empty set | admit / admit with no many datom | admit / admit (attribute optional) |
| Missing numeric peer `:seon.ns/steward 99999999` | admit | **admit** |
| Missing lookup `[:seon.agent/id "absent"]` | `{:error :entity-id/missing, :entity-id [:seon.agent/id "absent"]}` | Same native refusal |
| Two owners of unique value `"same"` | `{:error :transact/unique, :attribute :probe/unique, :datom #datahike/Datom […]}` | Same native refusal |
| Existing identity upsert | admit at existing eid | admit |
| Explicit eid conflicts with another existing identity | `:transact/upsert`, `:entity {:db/id 38117, :seon.owned-value-test/id "identity-b"}`, `:assertion [38118 :seon.owned-value-test/id "identity-b"]` | Same native refusal |
| Schema map missing cardinality | `:transact/schema`, `:entity {:db/ident :probe/incomplete, :db/valueType :db.type/string, :db/id nil}` | Same native refusal |
| Schema ident in reserved `db` namespace | `:transact/schema`, offending declaration `:db/probe` | Same native refusal |
| Change existing long attribute to string | `:transact/schema`, `:invalid-updates {:db/valueType [:db.type/long :db.type/string]}` | Same native refusal |
| Component declared with string value type | **admit**, all declaration datoms emitted | **admit** |
| Invalid `:db/cardinality :db.cardinality/nope` | `{:error :transact/schema, :value :db.cardinality/nope, :attribute :db/cardinality, :schema nil}` | Same native refusal |
| Direct derived composite tuple write | `{:error :transact/syntax, :tx-data [:db/add … :probe/composite ["a" "b"]]}` | Same native refusal |
| Unknown-attribute retraction on absent lookup subject | admit as no-op | admit |
| Component relation without declared child schema | admit | `:seon.db/missing-component-schema` |
| Heterogeneous tuple `[1 :x :y]`, expected 2 | `:transact/syntax`, `:tx-data [:db/add … :probe/tuple [1 :x :y]]` | Same native refusal |
| Heterogeneous tuple `[1 2]`, expected long/keyword | `:transact/syntax`, mixed validity | Same native refusal |
| Heterogeneous tuple `["bad" "bad"]` | **admit**, tuple datom present | admit for synthetic native-only attribute with no authored Malli form |
| Invalid canonical `:seon.fn/call-arities` tuple | admit | `:seon.db/invalid-value` on `:seon.fn/call-arities`; attempted-assertion check fires |
| Homogeneous long tuple of strings / nine members | `:transact/syntax` / `:transact/syntax` | Same native refusal |
| Schedule missing zone-id | admit | `:malli.core/missing-key`, `:seon.schedule/zone-id` |
| Schedule zone-id empty string | admit | `:seon.db/invalid-value` |
| Nonempty unowned identity-less row | admit | `:seon.db/unowned-entity` |
| Numeric missing **component** | admit | `:seon.db/missing-component` |
| Shared component | admit | `:seon.db/multiple-component-owners` |
| Component cycle | admit | `:seon.db/component-cycle` |
| Child required-value retraction | admit | `:malli.core/missing-key` |
| Unlink surviving component | admit | `:seon.db/unowned-entity` |
| Retract complete owning tree | admit; component datoms retracted | admit |
| Delete agent identity | admit | cause `:seon.agent/id`, “Archive the agent instead…” |
| Delete `seon.turn/open?` with surviving symbolic callers | admit | cause `:seon.program/referrer`, complete surviving-referrer data |

Both admitted missing-peer values and refused missing-child values were exercised.
Calling both “keep our missing-ref check” would invent a peer guarantee that the
current validator does not have. Preserve the owned-child check. A new universal
peer-existence policy is outside this optimization and would need an owner ruling.

### Corrected-prototype parity and current regression boundary

JVM **34655** ran the corrected prototype and the baseline against **34 direct
probe cases**: 12 were refused by Datahike before the callback; all 22 native-admitted
reports produced equal baseline/prototype diagnostic values after removing only
`:seon.error/at`. Both actual admissions and actual refusals are included. This is
concrete case parity, not a proof over every possible transaction. Four additional
native schema/identity probes were refused identically before the callback,
bringing the native inventory to **38 cases** (22 admitted, 16 refused).

The deletion study's current functional counterparts also passed in both modes:
cascade removes parent and child; history retains child facts; as-of recovers the
child before deletion; reasserting the deleted identity allocates another eid;
and an empty cardinality-many component value emits no relationship datoms.
These are six explicit assertions per mode, outside the 103-assertion test tally.

The corrected prototype then executed the same **11 tests / 103 assertions** as
the baseline: **97 passed, six failed, zero errors in each mode**. It preserved
all observed refusal causes and unchanged-basis assertions. The six existing
legacy-kind assertions still prevent claiming a green suite.


The first baseline and initial control labelled prototype each executed **11 tests / 103 assertions**:
**97 passed, six failed, zero errors**. The initial control did not replace owner lookup (see harness correction below); it is not the corrected prototype proof. The six failures are all the assertion
`(= :seon.db/invalid-write (:seon.error/kind result))` at
`test/seon/owned_value_test.clj:34`. These returned ownership diagnostics have
`:seon.db/transaction-refused true`, the expected cause, and no legacy kind key.
The separate unchanged-basis and cause assertions passed. Do not report this as a
green regression suite. The real callback, outside the transformed ownership
function, also produced that shape in the native-vs-Seon refusal table.

The compared tests are the two symbolic-deletion tests at
`test/seon/reset_edges_test.clj:20,45`; the transaction-grammar, complete-create,
required-program-ref, arity-component and renderer-target tests at
`test/seon/db_test.clj:1806,1877,1918,1945,1996`; and owned child-only/before-owner,
orphan/cycle/shared/missing-child, 1,001st-child and node-bound tests at
`test/seon/owned_value_test.clj:38,57,78,90`. These preserve the deletion study's
sweep/cascade/value-edge distinction using current canonical facts. They do not
rerun that older study's independent synthetic 5,000-function reach benchmark.

The report wrapper also captured the actual final refusal values for the
relational and work-bound regression rows (`report-refusals.edn`). Reaching that
callback with a completed report verifies native operation application for these
cases; the Seon callback supplies the refusal:

| Regression row | Native stage | Actual final refusal data |
|---|---|---|
| Required program ref swept on target deletion | Completed report | `:seon.db/attribute :seon.fn/ns` or `:seon.schedule.task/function`, surviving referrer identity retained |
| Caller uses one argument after callee is changed to require two | Completed report | cause `:seon.fn/arity-mismatches`; caller `sample.arity/caller`, callee `sample.arity/target`, call arity 1, declared/prepared bounds `{:seon.fn.arity/min 2, :seon.fn.arity/max 2}` |
| Renderer symbol has no final function row | Completed report | cause `:seon.render/function`; declaration `{:seon.schema/key :seon.db-test/render-target, :seon.render/property :seon.render/ai, :seon.render/function sample.render/ai}` |
| 1,001 children under node limit 1,000 | Completed report | cause `:seon.db/validation-node-limit`, `:seon.db/validation-limit 1000`, `:seon.db/visited 1000` |

### Small owning-value write

Three transactions each create one identified root with two identity-less owned
children, through `seon.db/transact!` on the canonical populated fixture. Each
mode uses its own fixture branch; trial one includes its first-use plan work.

| Small write (ms; baseline / prototype) | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Caller wall time | 341.126 / 348.687 | 130.594 / 178.820 | 129.251 / 244.260 | 130.594 / 244.260 |
| Application | 216.706 / 240.851 | 119.065 / 163.702 | 119.644 / 228.792 | 119.644 / 228.792 |
| Final validator | 213.510 / 238.873 | 117.701 / 161.989 | 118.191 / 226.387 | 118.191 / 226.387 |
| Owner discovery | 3.394 / 20.941 | 2.465 / 22.576 | 2.654 / 31.461 | 2.654 / 22.576 |
| Traversal/assembly residual | 1.521 / 1.196 | 0.924 / 1.587 | 0.852 / 1.222 | 0.924 / 1.222 |
| Entity checks | 0.225 / 0.262 | 0.083 / 0.107 | 0.074 / 0.108 | 0.083 / 0.108 |
| Arity check | 121.733 / 118.282 | 113.645 / 136.742 | 114.106 / 192.643 | 114.106 / 136.742 |

The prototype made two owner-index builds per write, costing 20.318 / 21.751 /
30.517 ms (median 21.751), **included** in owner discovery above. Each write made
14 owner-lookup calls, four entity-value reads and three entity checks. The
whole-program arity query dominates the small baseline; bulk reverse indexing
adds work. This falsifies selecting the bulk strategy unconditionally.

### Representative agent write

A canonical fixture agent named `juniper-step4` is created before timing. Each
trial changes its namespace ref to an existing canonical namespace (`seon.db`,
`seon.id`, `seon.turn`), using an identity lookup ref. This is a representative
single-agent sparse write, not a claim to measure a complete Juniper turn,
provider request or message settlement. Every measured write returned `:db-after`.

| Agent write (ms; baseline / prototype) | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Caller wall time | 141.010 / 148.695 | 131.607 / 159.774 | 125.708 / 156.682 | 131.607 / 156.682 |
| Application | 121.356 / 137.536 | 120.909 / 147.658 | 114.913 / 145.463 | 120.909 / 145.463 |
| Final validator | 120.094 / 136.154 | 117.913 / 145.670 | 112.955 / 144.058 | 117.913 / 144.058 |
| Owner discovery | 0.908 / 16.710 | 1.048 / 19.998 | 1.052 / 19.572 | 1.048 / 19.572 |
| Traversal/assembly residual | 1.140 / 1.114 | 0.956 / 1.019 | 1.240 / 0.940 | 1.140 / 1.019 |
| Entity checks | 0.057 / 0.056 | 0.058 / 0.106 | 0.168 / 0.073 | 0.058 / 0.073 |
| Arity check | 117.312 / 117.363 | 115.067 / 123.530 | 109.900 / 122.655 | 115.067 / 122.655 |
| Application outside validator | 1.262 / 1.382 | 2.996 / 1.987 | 1.958 / 1.405 | 1.958 / 1.405 |

The first attempted agent probe supplied a symbol to the now-ref-valued namespace
attribute and was refused before application. Those six refused timings are
**excluded**. The corrected lookup-ref trials above were completed in the same
JVM. The bulk index adds about 19.3 ms here, while the whole-program arity check
still dominates.

### Timing definitions and instrumentation limits

`transact-tx-data` wall time includes operation application, retention transaction
functions, finalization and the final validator. Subtracting the validator gives
**application outside final validation**, not pure Datahike CPU. The timed
`writing/commit!` channel completion is separate; these are **memory-store**
commits. Caller-side indexing, submission validation, encoding, projection
acquisition and fixture SCI construction are outside both timers.

Inside validation, `owners-of` measures the whole local owner-lookup function,
including its cache hits and realized AVET seeks. `write-entity-error` measures
whole-entity checking. The owned traversal/assembly residual is
`write-owned-values-error − owners-of − write-entity-error`; it includes EAVT row
reconstruction, postorder assembly, root selection and bound bookkeeping, and is
explicitly a subtraction rather than a separately timed loop. `row` and
`write-entity-value` are nested explanatory timers. `owning-ancestors` includes
owner lookup and must not be added to it.

Malli `schema` and `validator` calls, projection-validator acquisition and the
three declaration-building seams are counted/timed only inside final validation.
These are inclusive call costs, not disjoint compile CPU: an `m/schema` call can
return an existing schema; nested calls overlap. They test whether repeated
whole-registry work remains a plausible dominant cause after `4806aad03`, without
mislabeling every Malli API call a fresh compile. Timers and scratch function
re-evaluation add overhead; the pre-comparison population is kept separately.
There is no claim that these times equal uninstrumented production throughput.

Loaded source census: `src/seon/db.clj` SHA-256
`425899894f95cd2b3ff0abf061f0e822706c9f08d06fd4ef243a6f0d30bb4d89`;
`src/seon/schema.clj` `d7fd9bab1a881a4713c01bb2712aacc13dbec9be69a0d286bff35672efc3da7c`;
Datahike transaction source
`bba08204593026b49a8f282d691cd196bc9e8e53d69fbde76c51d3bd31b98626`.
The followup JVM loaded the same `db.clj` and dependency bytes; its startup
`schema.clj` hash was
`b722542b6bd958754fab87de7b3cfa4283f92f23e72c9b467f90d1e8ad6f8506`.
Its cached working-tree publication manifest emitted **455,227** attempted and
effective datoms for each comparison run. The earlier JVM's **455,127**-datom
population is separately labelled; comparisons do not mix their medians.
Concurrent edits to `src/seon/db.clj`, `src/seon/schema.clj` and render resources
were not reloaded into the scratch JVM, reverted, or edited by this lane.

Dependency commits: Datahike `e11845bac78e1241bca0766ddc07d978bd63d74a`,
Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`. HEAD and unrelated files moved
concurrently. The measured population repeatedly reported **455,127 attempted and
effective datoms**, **37,087 entity-check calls**, **52,215 entity-value reads**,
and **170,307 owner-lookup calls**. Lookup calls include cache hits: this is not
an assertion of 170,307 physical seeks. The publisher submitted one outer
transaction-function operation; that is not the older “74k flattened operations”
unit.

### What the prototype changes

The scratch prototype retains the original ownership algorithm and its bound,
seeds, before/after reach, postorder expansion and refusal constructors. It replaces
repeated `[attribute, child-id]` AVET requests with a **transaction-local** map
built from one AEVT traversal per installed component attribute per database
value. It preserves original component iteration and per-attribute datom order,
so shared-owner evidence keeps its ordering. This is an experimental bulk strategy,
not a second durable owner table. The map belongs to that callback's exact before
and after values and is discarded afterward.

It also delegates only unrefined equivalent scalar plans to native validation;
logical predicates, enums, required members, collections, tuples and refs stay.
The corrected prototype additionally removes the unreachable final
unknown-attribute branch and skips equivalent attempted scalar predicates even
when the existing attribute-plan cache is warm. Whole-root/child validators still
contain their native scalar leaves: this is a conservative prototype, not a claim
that the full residual-schema transformation is implemented.

The prototype does **not** create a value-first Datahike index. Complete outgoing
expansion continues to do one cached EAVT reconstruction per entity/phase, as the
current code already does. Bulk reverse discovery scans component datoms outside
the touched set; its small-write cost must be measured before selecting it for
production.

### Sampled hot frames

Full `jcmd Thread.dump_to_file -format=json` dumps, including virtual threads,
were taken from the measured JVM **16656**; no cluster JVM was sampled.
`tmp/probe/step4/sample-2.json` catches `async-mixed-1` inside
`write_owned_values_error/owners_of` → `datahike.api.impl/datoms` →
`datahike.db/contextual-datoms` → `components->pattern` → `resolve-datom` →
`validate-attr-ident` → `schema-attr?` → `clojure.spec.alpha/valid?`.
This is repeated lookup-argument validation on the writer, not entity Malli
validation. `sample-6.json` and `sample-8.json` cover repeated population work;
`sample-1.json`, `sample-3.json`, `sample-4.json` and `sample-5.json` separately
show caller/fixture construction (Clojure printing, tree traversal and compilation).
The followup `audit-sample-4.json` catches prototype writer `async-mixed-41`
in `write-entity-value` → its decode closure → `decode-attribute-value-in` →
`edn-encoded-attr-in?` → `form->datahike-value-type-in` → exception stack capture
for an unmappable union member (`src/seon/schema/datahike.clj:347–368,610`).
That is residual codec work, not evidence of whole-registry compilation. The
attribute plan has already selected the codec but its per-value decoder asks
again; retaining codecs in the diet does not require repeating that classification.
`audit-sample-1.json` through `audit-sample-3.json` document followup fixture/caller
work separately. Those caller samples must not be attributed to the final validator. A stack sample
names active work; it is not a phase-duration measurement.

### Harness correction

The first ownership rewrite matched a literal `%`, but Clojure's reader had
already replaced that anonymous-function argument with a generated symbol. It
therefore changed **zero** lookup forms. The labelled prototype's 32.67-second
owner time, unchanged AVET stack (`sample-9.json`) and absent index-build counter
falsified the harness. Those timings are **excluded** from prototype results.
JVM 16656 was terminated and reaped after preserving the three fully instrumented
baseline trials; no foreign JVM was signalled. The corrected rewrite matches the
read form's operation/index/entity argument and carries its actual attribute
symbol forward. It asserts **exactly one replacement**, and the callback must
report **two owner-index builds** for the population's before/after values.

The source reader also binds `seon.db` while reading the owner's form, preserving
all auto-resolved diagnostic keywords. Direct diagnostic parity excludes only
`:seon.error/at`, a new observation timestamp; it does not discard message,
attribute, path, offending value, causes, owner evidence or bound evidence.

The native-equivalence classifier resolves **Malli aliases**, not the storage
form. `resolve-datahike-form-in` (`src/seon/schema/datahike.clj:82`) deliberately
takes the first `:and` child for storage and can erase logical conjuncts. It is
therefore not a proof that an attribute's entire validator is native-equivalent.
The corrected prototype conservatively keeps every `:and`, property-bearing scalar,
collection, tuple, ref and qualified-name constraint. Production should derive
this residual using the retained compiled schema, not repeat the storage mapping.

## Exact production slice and gates

No production change is authorized by a timing alone. The measured prototype is
an in-process experiment; the following is the bounded implementation input.

| File / function | Required change and retained guarantee |
|---|---|
| `src/seon/db.clj` — `write-attribute-plan` :3431 | Carry a native-equivalence/residual validator in the existing plan, derived from the compiled logical schema **and installed native descriptor**. Delegate only when `:schema-flexibility :write` and the actual native value type/cardinality imply the logical leaf. Do not use storage-form stripping as the proof. Preserve codecs and all non-native constraints. |
| Same — `write-attribute-error` :3325 / `write-error` :3419 | Remove duplicated successful scalar validation after that proof, keeping traversal of nested ref submissions and logical refinements. Translate native refusal data at the existing refusal owner if the diagnostic contract must remain identical; do not silently lose attribute/path/fix information. |
| Same — `write-report-error` :3890 | Keep attempted assertions, effective retractions and the final database. Skip only native-proven scalar predicates and the unreachable native unknown-attribute test. Keep agent-identity retention, deletion, complete ownership, renderer and arity checks in their existing order. |
| Same — `write-owned-values-error` :3513 | Keep cached EAVT expansion, before/after seeds, targets, node bound, cycle/shared/missing/unowned checks and diagnostic order. Replace only owner acquisition after selecting a strategy that does not turn every small write into a full component-population scan. No persistent owner mirror. |
| `src/seon/schema/datahike.clj` — `decode-attribute-value-in` :610; `src/seon/db.clj` — `write-attribute-plan` :3431 | Separate codec followup: let the existing plan retain the selected decoder, avoiding per-value `edn-encoded-attr-in?` classification. Preserve the same reader, storage-type refusal and round-trip checks. The stack is observed; a speedup for this change is not measured here. |
| `src/seon/db.clj` — `write-entity-error` :3475 | Retain required-member and whole-value predicates. A later compiled residual-schema reduction may remove native leaves inside these maps; this prototype deliberately does not claim that reduction. Explain failures against the authored schema, preserving paths and values. |
| Same — `arity-mismatches-with` :3685 | Separate followup: derive the affected dependency set from all inputs to prepared arity, including declaration/default changes. The measured full-program query remains in this prototype; do not quietly drop it from a “small write” fast path. |
| `test/seon/db_test.clj`, `test/seon/owned_value_test.clj`, `test/seon/reset_edges_test.clj` | Preserve the listed deletion/component cases; add a positive native-delegation witness, all-invalid heterogeneous tuple, absent numeric component target, refined `:and` scalar, invalid-then-repaired and idempotent invalid assertions, and mismatched installed-descriptor evidence. Current diagnostics must be compared as declared data, not only by legacy kind. |

The existing regression classes already cover coordinated repair/retraction,
required-ref sweeps, child-only edits, old/new owners on reparenting, shared/cyclic
components, absent subjects, the 1,001st child, node-bound refusal, caller deletion,
renderer targets and prepared arities. Counterpart tests must affirm native
admission/refusal **and** unchanged basis on Seon rejection; absence of a callback
or empty datoms cannot mean success. Raw native tests should continue using the
canonical fixture, with synthetic declarations added only for the boundary under
study.

The lane owes no cold gate: the orchestrator owns `bin/test --paths … --
seon.db-test seon.owned-value-test seon.reset-edges-test` and platform proof,
followed by its publication/adoption observation. This research neither touches
`default` nor claims a live adoption result. The six observed legacy-kind failures
are recorded here under the assignment's single-note restriction; no issue index
or foreign lane was edited.


## Recommendation, remaining decisions, and retained evidence

**Do not ship the bulk owner scan as the universal writer implementation.** Its
population gain is real in this experiment; its small-write cost and scanning of
untouched components are also real. The existing node bound charges reached
nodes, not every unrelated edge scanned while constructing this experimental
map. Diagnostic parity does not by itself prove the same bounded-work guarantee.

The next bounded implementation should preserve selective indexed discovery for
small writes. Before introducing an adaptive bulk strategy, probe replacing the
public `d/datoms` reverse lookup with Datahike's existing indexed `dbi/search`
form `[nil attribute child-id]`, preserving native attribute-ref normalization:
native `retract-entity` already uses it
(T:1008–1012), and `db/search.cljc:167–218` selects the AVET strategy without the
public datoms argument-normalization path sampled above. **That alternative is
source-grounded, not measured here**; no speedup or parity is claimed for it.
It would preserve the existing touched-node traversal and avoid a new durable
index or a tuned size threshold.

The owner has already decided native type/cardinality/unknown-attribute authority.
No new deletion or ownership semantic ruling is needed. Two limits must not be
silently turned into new policy during implementation: ordinary missing numeric
peer targets currently pass both validators, and inconsistent native component
schema declarations also pass this transaction path. Preserve the existing
owned-child and tuple checks. A new general peer-existence rule or dependency
schema-consistency fix is a separate scope decision.

The implementation gate still needs resolution of the six existing legacy-kind
assertions. This lane neither changed the expected diagnostic contract nor
changed production to make the tally green. Native-delegation diagnostic
translation and installed-descriptor equivalence also remain implementation
obligations; the conservative scratch prototype is not a complete residual-Malli
schema compiler.

Retained local evidence (all under `tmp/probe/step4/`, intentionally **not
committed**):

| Files | Purpose |
|---|---|
| `audit-run.sh`, `audit-driver.clj` | One foreground JVM, repository slot, finite 1,200-second bound, canonical contract arming; exited 0 and released the slot. |
| `audit-setup.clj`, `audit-replenish.clj` | Instrumentation, asserted source rewrite, three baseline/three prototype population runs and final followup dispatch. |
| `audit-refusals.clj`, `audit-tests.clj` | Native-vs-final probe inventory and the named existing regressions. |
| `audit-small.clj`, `audit-agent-fixed.clj` | Small writes, corrected agent writes, four extra native probes and deletion-study observations. |
| `audit.log`, `populations-final.edn`, `refusals-final.edn`, `report-refusals.edn` | Raw current results, exact diagnostics, counts, phase times and failure evidence. |
| `summarize-final.py`, `summary-final.json` | Recomputable millisecond conversion and three-run medians; excludes invalid agent shapes. |
| `session.log`, `source-sha256.txt` | Earlier baseline evidence, excluded no-op control and original source census. |
| `sample-1.json` through `sample-6.json`, `sample-8.json`, `sample-9.json`; `audit-sample-1.json` through `audit-sample-4.json` | Full virtual-thread-aware dumps of the lane's JVMs only, distinguished above by phase. |

Reproduce with `bash tmp/probe/step4/audit-run.sh`, then
`python3 tmp/probe/step4/summarize-final.py`; source drift must be compared with
the recorded hashes/population size before interpreting a new run. The final
run waited for the repository slot rather than bypassing the two-JVM bound.
The lane's JVMs are gone; unused scratch scripts and the stale PID file were
removed. Only this research note is committed. No worktree, cluster store,
foreign session, production file, or default JVM was operated.
