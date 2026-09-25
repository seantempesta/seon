---
type: landing
status: current
created: 2026-09-24
scope: One render path slices 0a–1c landed (declared selection, child identity carriage), plus error.clj census fixes
---
# Lane render-dispatch-opus: one render path 0a, 0b, 1a, 1b (part) — 2026-09-24

Design: `docs/research/agent-platform/one-render-path-design-2026-09-25.md` §1, §6. Root ruling (follow-up): slice 1 split
into 1a/1b/1c; the ledger row was widened to error.clj, render/transcript.clj, program_test.clj, render/walk.clj,
render/web.clj and the six namespace-stage tests: `test/seon/render_simplification_test.clj`,
`test/seon/render_source_test.clj`, `test/seon/render/transcript_run_test.clj`, `test/seon/render/retained_test.clj`,
`test/my/turn_test.clj`, `test/seon/schema/datahike_parity.edn`. Only three fixtures exist
(`test/seon/render_simplification/fixture_{a,b,ambiguous}.clj`), not four.

## Tenth follow-up: inline faces restored (owner correction)

- `aa91ecb34` (+69/−11): selection `:explicit-value` → `:declared` → `:floor`; face value is a renderer symbol or rendered
  data; data reaches the face's floor emitter (`inline-rendered`), AI strings and HTML Hiccup as given; nested values in
  the floor follow the same order; face keys no longer void entity ownership; `:seon.render/inline` declared; enum and
  `{:min 3 :max 3}` bound; `:seon.render.selection.stage/value` and web.clj's debug read restored. `:explicit-request`
  stays deleted: no live caller (transcript's was converted in 1c).
- `82c63dbfb` class regression `an-inline-face-overrides-the-schema-default-for-just-that-face` (ai only, html only, both,
  data in place of a symbol). `a54278422` design §1 corrected in place.
- **Inline functions not implemented — stopped for a ruling.** (1) `seon.sci.kernel/invoke` (sci/kernel.clj:585-640)
  resolves and applies only a named Var (`:seon.fn/sym` → `ensure-function!` → `sci/resolve`); invoking a function
  value through the same arm/admission needs an invocation-request member for a value, in kernel.clj (not this row).
  (2) `producer-argument`/`render-argument` (render.clj:190-267) decide no custody by input keys today: every producer
  receives the whole merged unit including `:seon.db/db`. "Only when its input keys ask" is new behaviour for all
  renderers. (3) A live SCI fn value carries no source form, so "the keys its argument map destructures" is not
  recoverable from the value; only a declared schema (`:malli/schema` meta) is.

Reruns: `bin/test-check default --ns seon.render.entity-pairs-test`, `--ns seon.render-simplification-test`,
`--ns seon.render.web-test`, `--ns seon.render-source-test`, `--ns seon.contracts-install-test` (explicit
contract-error faces), and the lists above.

## Ninth follow-up

- `0336ec323` (ruling 2, value.clj granted for this only, now **released**): the floor's identity projection passes the
  projected value with `:seon.render.call/function` = the schema's `:seon.schema/identity-projection` symbol, so a
  nested database value renders `render-database-identity-ai` (probed on default: `seon.db/database-value-identity`'s
  declared return selects it). +11/−1, clipping unchanged.
- `57b3ddb7e` source-provenance refusal names `:seon.error/member :seon.render.value/root` and a host object by class
  (the E-constructor conversion 9bf22ecd9 had dropped the member and kept the whole unit; render-source :210/:212).
- `7593a1d89` render-call's output contract is `:seon.render/error-result` (the "expected nil, got a map" refusal at
  web.clj:1444; callers already branch on unknown/invalid-output/candidates).
- Still unattributed (my files): render-source preview reuse :108/:125 and authored-source :776–:781 (second presentation
  re-evaluates; plausibly the invocation cache's `::private-callable` identity changing across the first install —
  re-check after fix-harness-fallout-opus's eval.clj:785 change); walk distance :601 (distance 1 omits the required
  namespace); web coalesce :1790/:1794 and next-turn :2447.

## Eighth follow-up: recorded actuals (seon.test.runner/latest-results, read-only)

Fixed: `51af4a328` a top-level `:seon.render.walk/attribute` request selects that attribute's pair again (1c had
dropped it with the schema stage; recorded actual: page-settings got `render-value/render-ai` for
`seon.agent/render-settings-ai`). `bf0ae8fbe` web-test declared-unit order (recorded actual adds `:seon.agent/branch`
`:archived-tx`), render-source-test symbol `:seon.fn/sym` reads (string read refused, attribute is `:db.type/symbol`),
and the vary-meta projection test deleted (`seon.db/carried-projection` derives from rows; no stamp).

**Fixture renderers run the compiled root, not the test's SCI redefinition** (render-simplification generic-renderer
`"A:…"` for `"7919:…"`, live-Var `"B:"` for `"B2:"`, shared-invocation `"A:…"` without `":two"`; retained :117/:131 and
adoption counts 0). Evidence: `install-function-from-database!` (`src/seon/sci/eval.clj:767-787`) binds the compiled Var
for "a definition the loaded program holds unchanged", once per context, at the first `ensure-function!`. My vehicle
change made these fixtures' first install happen at the first render, after the test's `sci/eval-form` redefinition,
so the compiled root replaces it. Owner of that file is fix-harness-fallout-opus; question for root: is a context's
private SCI redefinition of a program function supposed to survive its first install? If yes, eval.clj:785 should skip
the JVM root when the context already holds a private definition; if no, these tests change their vehicle to a row
source change (`replace-renderer!`), which retained-test already uses and which also fails (:117, :131), so the probe
is needed first.

**Database identity nested under an unregistered key** (nested-values :482: the floor prints the identity map; expected
"basis transaction"). The floor's identity projection (`render/value.clj:448-455`,
`schema/identity-only-projection-in`, schema.clj:4031-4042) calls `seon.db/database-value-identity`, whose declared
return `:seon.db/database-value-identity` pairs `render-database-identity-ai`. Rule 2 for that node: value.clj passes the
projection function as `:seon.render.call/function` with the projected value (about 4 lines in value.clj plus the
function symbol in the descriptor). Needs value.clj (released) and a ruling that a floor-invoked projection is producer
evidence.

Others from the actuals, not this lane's commits: render-source preview reuse (:108/:125, authored-source :776/:777/:781:
the second presentation re-evaluates; needs the invocation-cache evidence diff), render-source :210/:212 (the anonymous
`(Object.)` source refusal now lacks `:seon.render.value/root` and prints `#object`), render-call's "expected nil, got a
map" at web.clj:1444, web coalesce :1790/:1794 (6 vs < 6), next-turn :2447 (a retraction write refused), distance :601
(walk at distance 1 omits the required namespace). turn-loop :777 terminal `nil` and :630 `[[] [] [] [] [] []]` are
recorded; escalation needs `(:seon.error/agent fact)`, `escalation-present` and `opening` from `commit-call`
(error.clj:1443-1455) in that fixture.

## Seventh follow-up: rerun (tmp/orchestrator/verify-render-fix-results-2026-09-25.txt, 58 reds)

Fixed: `7ea24ee67` (the analyze-forms refusal: the retained-call regression's fn rows used string `:seon.fn/sym`; probed,
the symbol form validates and `declaration-row` digests it; web-test's debug trigger agent via `agent-tx`);
`47d582c10` entity-pairs `:90` (census d class 5 UPDATE: `seon.render.test/render-ai` now emits
`recorded-result`/`changed-since-green`/`host`, render/test.clj:40-62).

**CFG (census d class 9) — producer found, file not mine:** `src/seon/sci/eval.clj:173-183` `projection-state` builds
every cluster context's environment with the literal `{:seon.boot/cluster-name "current-src"}`, so
`seon.render/request-profile` (render.clj:143-156) asks `config/effective` for a cluster the database does not hold
(`:available ["default"]`). render.clj reads the name correctly and refuses by name. Fix at the producer: the context's
environment names its caller's cluster (`cluster-ctx` callers pass it into `projection-state`). Needs lines 173-183 and
the `cluster-ctx` arities of sci/eval.clj; request root sequencing with fix-harness-fallout-opus.

**ERRREC (class 7) and the two turn-loop members — need a probe I cannot run under PROBE SAFETY/LOAD:** escalation is
decided in `commit-call` (error.clj:1443-1455): steward, else interrupted-and-attributed agent, else escalate only when
the fact has no agent (`76e4e0ab6`, owner ruling). `a-refused-phase…` expects `["supervisor"]` at the limit; the answer
depends on `opening`, `escalation-present` and `(:seon.error/agent fact)` in that fixture. Asking root for the
`refuse-phase!` prepared fact (`(:seon.error/fact prepared)` keys) and the `:777` terminal value's `actual` text.

**Need `actual` text (truncated in the results file)** for: render-simplification :482 :539 :605 :714 :776 :805 :891;
retained :211 and equal-committed; render-source :125 :212 and `render-call refused return value … expected nil, got a
map` (web.clj:1444: the source-call path's contract expects nil; which render returned a map); page-settings :36;
transcript :1284; web :1790 :651 :2447; value :405 :557 :797 :976; error :76 :156 :1265; program indexed-and-evaluated.

Not mine: value_test.clj :102 shows `seon.plan/render-plan-ai` output printed by the floor as a data map (the plan
renderer or its value); `test/seon/render/value_test.clj`, `test/seon/render/transcript_test.clj`,
`test/seon/error_test.clj` complete-component (`:seon.error-test/manifest` missing component schema) and oversight
(`[:seon.config/cluster "unconfigured"]`, config family).

## Sixth follow-up: root's verification (tmp/orchestrator/verify-render-1c-results-2026-09-25.txt, 105 reds)

Fixed at the producer (one commit per class):
- `bf84c299b` **class 1 (≥23 + most web/simplification/value/transcript/source errors):** `:seon.render/selection`
  declared `[:vector {:min 5 :max 5} …]` stages; since 409fcf269/44dc30ae6 a selection has two. Probed on default:
  a two-stage selection's only explanation was that bound. Now `{:min 2 :max 2}`.
- `0b6cc87e6` **class 2 (definition-digest half, 16):** my declared-subject vehicle, the nested-report attribute and the
  ambiguity schemas were hand-built schema maps; now `program/declaration-row` (probed: returns the digest).
- `229860f78` retained-test's seed agent via `support/agent-tx` (was a hand-built agent map refused for
  `:seon.agent/branch`); the standalone arity test warms the projection's arity holder before its `m/validator` trap
  (the old test warmed it through `selection`).

Not caused by this lane's commits (evidence), for root to assign:
- `:seon.agent/branch` refusals in `test/seon/sci/eval_test.clj` (other than :2221) and `test/seon/render/web_test.clj`:
  hand-built agent maps; the writer's `write-entity-schemas` (`db.clj:3162`) keys on required identity attributes, not
  `:seon.program/row-schema`.
- help-test: its cluster fixture write is refused at `:seon.cluster/config` (required), so `evaluation/of-agent` answers a
  no-such-agent error map and `(first …)` hands `repl/render-ai` a map entry (the "lookup-ref vector").
  `test/seon/help_test.clj` / config owner. oversight-test "Nothing found for entity id [:seon.config/cluster …]" is the
  same config family.
- `seon.db/pull` nil entity id at `test/seon/sci/eval_test.clj:1598`.
- schema-test's 20 (`test/seon/schema_test.clj` :446 recursion-selector refusal shape, :981 render-contract refusal data,
  :1176/:1288/:1320/:1471/:1505/:1646): synthetic-form schema.clj behaviour; none reads the render dispatch. The
  schema.clj:3366 nested-selector target change (0a) is not among the reported lines.
- turn-loop :630 and :777: 375a56048 is adopted (value carries the distinguishing members); the remaining assertions are
  the escalation vector and the terminal verdict map. I need their `actual` values to attribute them.

Needs the `actual` text before I can attribute: program-test `indexed-and-evaluated-declarations-are-the-same-entities`;
error-test :74 and :154 (possibly 3d460e2eb: stored fault-field text now follows the value's own entry order);
render-source-test `anonymous-values-refuse-to-forge-source-provenance` (:210 in the adopted file).

Reruns (serially): `bin/test-check default --ns seon.render.entity-pairs-test`, `--ns seon.render-simplification-test`,
`--ns seon.render.retained-test`, `--ns seon.render-source-test`, `--ns seon.render.web-test`,
`--ns seon.render.page-settings-test`, `--ns seon.render.transcript-test`, `--ns seon.render.value-test`,
`--ns seon.sci.eval-test`, `--ns seon.error-test`, `--ns seon.oversight-test`, `--ns seon.program-test`,
`--test seon.turn-loop-test/a-refused-phase-escalates-once-per-signature-and-never-to-itself`,
`--test seon.turn-loop-test/a-clean-last-form-without-a-disposition-is-loud-terminal-evidence`.

## Fifth follow-up: contract finding and remaining fallout

- `e7c4deb4c` (ruling: follow the design): an error whose contracted operation's declared alternatives it satisfies
  none of selects `seon.error/render-undeclared-ai`/`-html` — the base renderer's content plus a contract finding naming
  the operation. Uncontracted operations compose the base pair; satisfied alternatives render their pair. Regression in
  `entity_pairs_test` (`declared-identity-and-producer-lookups-name-their-owners`).
- `17a075c84`: selection-stage enum is `[:enum :declared :floor]`; `:seon.render.selection.stage/value` and web.clj's
  read of it deleted; `eval_test` armed-render regression uses declared subjects over two contracted renderer rows;
  `page_settings_test` adds `:seon.render.walk/attribute :seon.agent/settings`; `web_test` stage data renamed.

## Fourth follow-up: carriage and 1c landed

- `4bb8f07df` `render/value.clj:470` hands `project-node` `(assoc unit :seon.render.data/path path)` (+3/−1). Selection
  input only; traversal, limits and clipping unchanged; `render-argument` still excludes the path from invocation
  arguments and cache evidence. **value.clj released** to the floor redesign lane.
- `44dc30ae6` 1c (+84/−243): selection is `[:declared :floor]`. Deleted explicit-value/explicit-request/schema stages,
  schema-producer(s), specificity, `transaction-shape`, project-node's `(get value output)`; cost facts name the owning
  row schema. Errors with no satisfied, paired alternative compose the `:seon.error/base` pair. **Deviation for ruling:**
  the design's "contracted operation with no satisfied alternative → named contract diagnostic" is the base pair here.
  Transcript prefix uses `prefix-subject` (rule 2). `matching-shapes-in` stays for diagnostics (walk.clj:101, schema).
- Selection enum keeps `:explicit-value :explicit-request :namespace :schema` because `test/seon/render/web_test.clj`
  (692, 747, 795) builds synthetic stage data with them; trim with that file's owner.
- **Expected reds outside this row:** `test/seon/sci/eval_test.clj:2221` (explicit request producer; needs a declared
  subject); `test/seon/render/page_settings_test.clj:27` (root-rendered settings component has no identity; add
  `:seon.render.walk/attribute :seon.agent/settings`); any test asserting a nested pair under an unregistered key, or an
  explicit `:seon.render/ai`/`:html` request producer (render_coverage_test, mcp_test, turn_loop_test, ns_test,
  value_test, schema tests use pairs in schema declarations, which still work).
- **Pair reach census (per rule) not re-run** after adoption under PROBE SAFETY (no unrun code in default). The
  committed-shape script is `tmp/render-dispatch-opus/pair-census.clj` (read-only; rule 1 = error, 2 = named return,
  3 = row schema, attr = component/element/named attribute). Pre-adoption run (357 paired): 142 reached by no rule, of
  which the generic `seon.error/render-ai` errors are now served by the base pair; the non-generic unreached included
  `:seon.agent/plan`, `:seon.agent/settings`, `:seon.cluster.status/concern`, `:seon.eval/entity`, `:seon.message/to`,
  `:seon.problems/missing-model`, `:seon.repl/directory`, `:seon.runtime/turns`, `:seon.turn/agent`,
  `:seon.render.data/snapshot`, `seon.error/index-refusal-prose` (12) and `mcp-prose` (3) errors whose operations do not
  declare them, and `:seon.error/agent`/`:of-steward`; ns alias/import/refer bindings are reached by attribute
  (component schema; probed for import). Root run: MCP `eval_clj` jvm `(pr-str (load-string (slurp
  "/Users/sean/src/seon/tmp/render-dispatch-opus/pair-census.clj")))` after review.

## Third follow-up (blockers ruled: my.turn page deleted, vehicles rewritten, child identity required)

Commits: `12b1fdb21` cluster_test flow key (class 12, second member); `5a07f31e3` my.turn namespace page deleted
(`:my.turn/namespace-unit`, both `render-namespace-ai`; `usage-form` serves only `:my.turn/usage-unit`; the opening no
longer renders my.turn's row as `(dir 'my.turn)` + walkthrough); `6d84e265e` + `dea66e2aa` fixture renderers reached by a
declared subject (`seon.render-simplification-test/declare-subject!`: a schema row whose pair names the renderers and a
contracted producer returning it, both through `support/transacted!`/`program-fn-row`; retained reuse counts `selection`
calls; live-Var and raw-Hiccup on the pair; declared ambiguity); `409fcf269` namespace stage deleted (walk keeps its
owner for renderer-failure); `375a56048` census c class 10 (issue
`docs/seon/issues/error-recording-drops-declared-distinguishing-members.md`, confirmed): `recording`'s value now also
carries the members its declared schema adds to `:seon.error/base` (probed: the phase-failed value validates
`:seon.turn.loop/phase-failed-error` with them, not without); `105926712` attribute-element identity in render.clj
(component schema, named ref/`:maybe`, entity attribute, collection element), inert until the floor carries the node path.

**bounded-text follow-up: no readable order-independent canonical form exists.** `seon.id/printed` (`id.clj:29-38`) is
fixed-binding `pr-str`, and its own docstring (`id.clj:40-45`) says equal maps printed in different entry order differ.
`seon.schema/canonical-data-string` (`schema.clj:835-896`) is order-independent but a framed digest encoding
(`k10::a/b…`), not text an agent reads. hasch 0.4.100 (deps.edn:44) produces hashes, not text. Stored field text stays
`admit/canonical-edn`; signatures never read it.

**Waiting:** the value.clj carriage (one line at `render/value.clj:470`: hand `project-node` the node's
`:seon.render.data/path`) waits for render-floor-opus's slice-2 commit; 1c (explicit-value, explicit-request and schema
stages, specificity and pulled/transacted matching deleted; errors compose the base pair when no alternative is
satisfied; transcript's prefix subject; test updates) is drafted in the tree and lands after it.

## Second follow-up (root rulings: namespace pages deleted, MCP JVM floor-only, eval.clj transferred)

- **`3d460e2eb` error.clj `bounded-text`** stores fault-field text as `admit/canonical-edn` of the admitted value, not
  `print/emit-text` (+4/−5; `seon.print` require dropped). Probed: bytes follow the value's own entry order —
  `{:b 2 :a 1}` prints `{:b 2, :a 1}` where emit printed `{:a 1, :b 2}`, and `#{3 1 2}` prints in hash order. Equal values
  built in different insertion orders therefore store different text; the D13 signature (`error.clj:150-175`) never
  reads these fields, so signatures are unaffected. floor lane's slice 2 is unblocked.
- **`386fe724d` test fallout**: `error_test.clj:715,735`, `oversight_test.clj:164-165`, `contracts_fixture.clj:53` fixed.
- **`50c9d6a5c` census c class 12**: probed both members. `error.clj`'s declaration validator is correct in both; the
  producers were retired test shapes. (1) error-test `schema-refusals-are-admitted-at-the-recorder`:
  `schema/projection-from-database` of a map now meets `seon.db/carried-projection`'s input contract, so its refusal is
  an instrument contract error missing `:seon.schema/refused-value`/`:expected-value`; the test now records
  `schema/refuse-projection-source`'s genuine refusal (probed: validates, `prepare` succeeds). The unreachable
  `refuse-projection-source` path under `projection-from-database` is a schema.clj observation for its owner.
  (2) **cluster-test `a-core-fault-commits-with-the-dial-its-cluster-config-carries` (`test/seon/cluster_test.clj:127`,
  not in this row)** puts the throwable at `:seon.flow/ex`; `error/throwable` reads flow's `:clojure.core.async.flow/ex`
  (`error.clj:90-100`, as `fault.clj:206` and `boot.clj:355` supply it). Probed: `:seon.flow/ex` → "The supplied error
  declaration does not validate its observation."; `:clojure.core.async.flow/ex` → prepares. One-key test fix for its owner.
- MCP JVM mode: nothing was planned in cluster.clj beyond the landing's option text; no edit made.

**Stopped again before the rest of 1b and 1c** (no production edits for them):
1. Deleting the `my.turn` page needs `src/my/turn.clj` (`render-namespace-ai` delegates to `seon.run/render-namespace-ai`,
   whose contract names `:my.turn/namespace-unit`) and deleting that schema form from `resources/seon/schemas/my.turn.edn`;
   neither is in this row.
2. The namespace stage is the vehicle of behavior tests that are not about namespaces: retained-call reuse counts
   `kernel/public-functions-in` discoveries (`render_simplification_test.clj:348-490`, `render/retained_test.clj:40-70`),
   live-Var invocation (`:627-690`), and ambiguity (`:588-626`). Converting them needs a declared vehicle (a supplied
   projection with a fixture schema + function contract, which interacts with `call-cache-evidence`'s ctx projection)
   and test runs to get right; blind, under the load rule, it would trade one known state for unknown reds.
3. 1c's schema-stage deletion drops structural pairs for nested values: `render_simplification_test.clj`
   `nested-values-render-their-declared-faces` (behavior established by `cecfaf428`) asserts a transaction report nested
   under an unregistered key renders its declared face. About 30 identity-less non-error pairs are reached only this way
   today (for example `:seon.db/transaction-report`, `:seon.print/elision`, `:seon.problems/problems`, `:seon.help/help`,
   `:seon.ns.alias/binding` components, `:seon.cluster.status/concern`, `:seon.agent/settings`, `:seon.test/result`).
   Keeping component/child pairs needs the design's attribute-element identity inside the floor walk
   (`render/value.clj:470`, render-floor-opus), and the remaining root entrances need producer evidence one by one.
   Needs a ruling: accept those losses as the design states ("Missing declared pair means floor"), or carry child
   identity through value.clj first.

## Broken or unproven first

- **Expected REDs in files outside this row** (one-line fixes each; owners or root):
  - `test/seon/contracts_fixture.clj:53` asserts the `:schema` stage selects a refusal's renderer. After 1a the `:declared`
    stage selects it first when the refusal's operation is contracted. Fix: accept `#{:declared :schema}`.
  - `test/seon/error_test.clj:715` asserts `(qualified-symbol? (:seon.render/ai notice))`, and `:735` asserts the notice names
    `instrumentation-prose`. After 1b the notice carries no renderer; `:seon.error/notice` declares `seon.error/notice-prose`.
    Fix: drop `:715`; at `:735` assert `(= (error/instrumentation-prose notice) (error/notice-prose notice))`.
  - `test/seon/oversight_test.clj:164-165` assert the unit's explicit `:seon.render/ai`/`:seon.render/html`. After 1b the
    unit carries `:seon.render.call/function seon.oversight/fleet-value`. Fix: assert that key.
- **1b remainder and 1c are stopped** on two genuinely new decisions (options below). 1c cannot delete the namespace stage
  before the namespace-page ruling, nor the schema stage before transcript's subject is converted (converting it while
  structural matching lives would let any map holding `:seon.turn/id` + `:seon.agent/id` select the prefix renderer).
- **Nothing is adopted or tested.** LOAD RULE: no reload into default, no test request. All schema declarations await
  publication and adoption.
- **schema.clj:3366 nested-selector targets need a root schema-test run.** `selector-target-schema` (`schema.clj:3366-3379`)
  reads `:seon.program/row-schema` from a sub-selector's identity attributes; after 0a a nested `{:ref [:seon.agent/id …]}`
  derives against `:seon.agent/agent`, where a wildcard nested selector previously refused `:seon.schema/unknown-pull-target`.
- **Base pair side effect (1a).** `:seon.error/base` now declares `seon.error/render-ai`/`render-html` (root ruling). Until the
  schema stage is deleted, structural matching also sees base; an error map whose specific schema does not match now renders
  through `seon.error/render-ai` instead of the value floor, and a specific schema tying base on specificity would be an
  ambiguity. Unproven either way; root's render tests below cover it.
- RESET NEEDED: no.

## Commits

| Commit | Slice | Paths | Lines |
|---|---|---|---|
| `9b4a7522f` | 0a | `src/seon/program.cljc`, `src/seon/render.clj`, 36 `resources/seon/schemas/*.edn` | +92 / −48; 39 EDN lines edited in place, net +44 |
| `05dc32444` | 0b | `src/seon/render.clj` | +62 / −0 |
| `1b7c0493c` | regression | `test/seon/render/entity_pairs_test.clj` | +27 / −0 |
| `3784764c1` | 0 | `test/seon/program_test.clj` (family = row schema + source attribute) | +4 / −3 |
| `8ebb23736` | 1a | `src/seon/render.clj`, `seon.error.edn` (base pair), `seon.render.call.edn` (`:seon.render.call/function`), `seon.render.edn` (`:declared` stage name) | +46 / −6 |
| `3fce22c5e` | 1a | `test/seon/render_simplification_test.clj` (stage vectors include `:declared`) | +5 / −5 |
| `2e42564f5` | 1b | `src/seon/oversight.clj`, `src/seon/error.clj`, `seon.oversight.edn`, `seon.error.edn` | +29 / −21 |
| `959f14142` | 1b | `src/seon/sci/eval.clj` (shown-result carriage), `src/seon/render.clj` (`head-function` public) | +8 / −2 |

Net: **+273 / −85 = +188 lines** (39 of the additions are in-place EDN edits) across the eight code/test commits; nothing is deleted yet (1c deletes ~260).

## What landed

- **0a.** A disposable scripted sweep (one line per attribute, refused on ambiguity; its file/line/owner table is exactly the 39 lines of `git show 9b4a7522f -- resources`)
  added `:seon.program/row-schema <owner>` to 39 data identity attributes. Each owner is the attribute's only
  partition-declaring entity schema. For `:seon.agent/id`, the owner is `:seon.agent/agent` rather than `:my.plan/agent-state`.
  Two attributes were skipped because no entity holds them: `:seon.config/agent` and `:seon.source/digest`.
  `seon.program/source-family?` now defines a program family as row-schema **and** source-attribute. It is used by
  `identity-attributes`, `shapes-in` and `declaration-families`. `seon.program.edn` updates the property description.
  In `seon.render`, `entity-owners` is cached once per projection (identity → row, row → entry keys plus `:db/id`), and
  `entity-owner` returns the single row schema that names every identity present and owns every key present.
- **0b.** In `seon.render`:
  - `output-alternatives` is cached per `[projection function]`. It uses Malli `-function-schema-arities` and `-function-info`
    output, flattened through `:or`/`:maybe`/`:and` and named unions, without `:seon.error/base`.
  - `satisfied-alternatives` validates a value with `schema/projection-validator` (Malli's cached `m/validator`).
  - `error-alternatives` reads the value's own `:seon.error/operation`.
  - `head-function` resolves a top-level call's head with `sci/find-ns`, `sci/binding [sci/ns …]`, `sci/resolve` and
    `sci/var->symbol` to a contracted Var that is not a macro.

- **1a.** `declared-producers` (render.clj): an error's own operation's satisfied alternatives, else the root call's
  `:seon.render.call/function` returns (only when the node is `identical?` to the request's root value), else the entity
  owner; pairs are read through `internal/entity-properties`, so `[:and :seon.error/base [:map …]]` finds its own pair or
  the base pair. The `:declared` stage runs before `:schema` in `selection`; nested `declared-producer` asks it first. No
  match falls through; nothing is removed.
- **1b (part).** `oversight/unit` supplies `:seon.render.call/function seon.oversight/fleet-value`; `:seon.oversight/fleet`
  and `:seon.oversight/unavailable` declare `ai-story`/`html-table`, whose input became `:seon.render/unit` (the old
  `:seon.oversight/unit` input made publication refuse the pair: probed). `error/notice` stops stamping `:seon.render/ai`;
  `:seon.error/notice` declares new `seon.error/notice-prose`, which makes notice's former arm choice. The successful
  evaluation in `sci/eval.clj` resolves its form's head and carries it into `shown-result`; `prepare` hands that unit to
  `project-node` with the root value, so no `render/value.clj` edit was needed (not "after floor lane").

## Evidence

All MCP calls used JVM mode, root `/Users/sean/src/seon`, cluster `default` (PID 67823, faulted, 15 open faults) and `read_only`.
The probes ran in throwaway namespaces `render-dispatch-opus.probe-0a` and `render-dispatch-opus.probe-0b`;
disposable probe files were removed after the run. No default Var was redefined.

**Census.** 48 identity attributes. Only the 6 program families carried row-schema before this change. Each data identity has
exactly one entity schema that declares a partition, except `:seon.agent/id` (2) and `:seon.fn/sym` (2, already declared). 261 ms.

**0a probe.** The 39 changed forms were overlaid onto the carried projection with `projection-with-schema` (760 ms, probe only).
Results:
- 45 identity rows, 43 owned row schemas; deriving the owners took **1.13 ms**, once per projection.
- Program families are unchanged: `#{:seon.schema/key :seon.fn/sym :seon.ns/name :seon.lint/id :seon.test/sym :seon.fn.file/relative-path}`.
- Owner results:

| Value | Owner |
|---|---|
| Envelope `{:seon.agent/id :seon.turn/id}` | nil |
| Agent id plus a foreign key | nil |
| Partial agent | `:seon.agent/agent` |
| Live pulled root agent, 0 foreign keys | `:seon.agent/agent` |
| Error id plus signature | `:seon.error/error` |
| `{:db/id 1}` | nil |
| `{}` | nil |
| Function row | `:seon.fn/fn` |

**0b probe.** Run on the projection without its cache holder, so the probe wrote nothing into the shared cache. 12 lookups took **1.0 ms**
in total, uncached. Results:

| Lookup | Result |
|---|---|
| `ambiguity`'s own operation | `[:seon.render/ambiguous-error]` |
| Uncontracted operation | nil |
| Contracted operation, no satisfied alternative | `[]` |
| `seon.fs.jvm/flat-error` | 16 alternatives |
| `(seon.render/selection {})` | `seon.render/selection` |
| `(schema/projection-validator …)` in `seon.render` | `seon.schema/projection-validator` |
| `let`, `->`, literal, interop, missing namespace | nil |

**Reach census (0b).**
- Of 326 paired schemas, 131 are named returns of some contracted function.
- 254 of the 326 pairs name the generic `seon.error/render-ai`. `:seon.error/base` declares no pair.
- The non-generic error producers reachable only through operation lookup are `seon.error/index-refusal-prose` (12),
  `mcp-prose` (5), `render-faults-ai` (2) and `seon.background/render-ai` (3).

**1a/1b probes** (scratch namespaces, projection overlays without the shared cache holder):
- `declared-producers`: live root agent → `[seon.cluster.agent/render-identity-ai]` (html `render-identity-html`), the same as
  today's `schema-producers`; ambiguity error → `[seon.error/render-ai]`, same as today; envelope, attribute-scoped request,
  no projection and uncontracted error → `[]`. 7 lookups 3.7–5.2 ms uncached.
- Overlaying `:seon.error/base` with its pair and `:seon.render.call/function` publishes (94 ms). The fleet pair publishes
  once `ai-story`/`html-table` take `:seon.render/unit`; then fleet → `ai-story`/`html-table`, unavailable → `ai-story`,
  and a nested fleet (not the root) → `[]`.

**Not measured.** Hot-path render timing, because dispatch is unchanged, and allocation.

## Stopped: 1b remainder and 1c need two rulings

**Decision 1: namespace-owned pages.** `walk.clj:774`, `bootstrap.clj:324` and `web.clj:1463` pass `:seon.render/namespace`
so a namespace can render its own `:seon.ns/ns` row (`my.turn/namespace-unit` = `[:seon.ns/name [:= my.turn]]` with
`my.turn/render-namespace-ai` → `seon.run/render-namespace-ai`, plus `my.turn/usage-form` for `:seon.render/form`). Under
rule 3 that row is owned by `:seon.ns/ns`, whose pair would capture it.
1. **Simplest (recommended): delete namespace-specific pages.** `my.turn`'s page renders through the `:seon.ns/ns` pair; its
   lifecycle prose moves to the namespace docstring; delete `render-namespace-ai` (run.clj, my/turn.clj), the three fixtures
   and their tests with the namespace stage. Guarantee: one ns page renderer. Cost ~−120 lines. Sacrifice: the curated
   my.turn page and usage form.
2. **The ns pair delegates by declaration.** The `:seon.ns/ns` renderer looks up a function the namespace declares on its
   row (a new optional attribute, e.g. `:seon.ns/page`), resolved and contract-checked. Cost ~+40/−100. Sacrifice: a
   renderer symbol stored as data, which the design disallows.
3. **Keep the namespace stage for `:seon.ns/ns` rows only.** Cost ~+5/−80. Sacrifice: a second selection path survives.

**Decision 2: MCP head Var (`cluster.clj` mcp-project).** MCP JVM-mode `prepare` carries no `:seon.sci.eval/ctx` or database
(`cluster.clj:466-471`), so `project-node` never runs (`render/value.clj:470`): no declared renderer applies to any MCP value
today, and a head Var would change nothing. The form also never reaches `mcp-project`, and a JVM-mode head is a JVM Var, not
an SCI one.
1. **Simplest (recommended): MCP JVM mode stays floor-only**; the design line for MCP is removed; MCP SCI mode already goes
   through `shown-result`. Cost 0. Sacrifice: JVM-mode results never use declared pairs.
2. Give MCP's `prepare` the cluster ctx and database, and resolve the JVM form's head with `clojure.core/resolve`, passing the
   form from `mcp-valf`. Cost ~+20 in cluster.clj plus the MCP bridge caller (script/seon/dev/mcp.clj, not in this row).
   Sacrifice: SCI producers run on every MCP JVM read (time-limited, but new cost and failure surface).
3. Only the ctx/database half: nested errors and entities get declared pairs in MCP; no head Var. Cost ~+6.

**Ready once ruled (1c, estimated ≤100 added):** transcript.clj:2126 gets a contracted `prefix-subject` producer and
declared pair in place of its explicit request (lands with the schema-stage deletion); delete the explicit-value,
explicit-request, namespace (per ruling) and schema stages, specificity ranking, pulled/transacted matching and
`cost-shape-key`'s matching; keep `matching-shapes-in` for diagnostics; update the six tests.

## Out of scope, recorded elsewhere

- The MCP exception face is replaced by `seon.cluster/first-seon-frame refused trace at [3 2]: expected a string, got nil`.
  It reproduced here from a `load-string` NPE. Already recorded as D4 in
  `docs/research/agent-platform/repl-investigation-2026-09-23.md:109` (fix E5 in `error-floor-investigation-2026-09-23.md:210`).
- MCP results were clipped at 32 children (`:seon.render.profile/max-children`), for example a 48-row census. That is design
  audit site 2/13, owned by render-floor-opus.

## Root runs (serially, after adopting the commits)

0. Also: `bin/test-check default --ns seon.sci.eval-test`, `--ns seon.render.page-settings-test`, `--ns seon.render.web-test`, `--ns seon.render-source-test`, `--ns seon.render.retained-test`; the pair census script above.

1. `bin/test-check default --ns seon.render.entity-pairs-test`
2. `bin/test-check default --ns seon.program-test`
3. `bin/test-check default --test seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops`
4. `bin/test-check default --ns seon.schema-test` (schema.clj:3366 nested-selector targets)
5. `bin/test-check default --ns seon.schema.datahike-test`
6. `bin/test-check default --ns seon.render-simplification-test`
7. `bin/test-check default --ns seon.oversight-test` (expect `:164-165` RED until fixed)
8. `bin/test-check default --ns seon.error-test` (expect `:715`, `:735` RED until fixed)
9. `bin/test-check default --ns seon.render.web-test`
10. `bin/test-check default --ns seon.sci.eval-test` (shown-result head carriage)
11. `bin/test-check default --changed seon.program/shapes-in`, `--changed seon.program/declaration-families`,
    `--changed seon.render/selection`, `--changed seon.error/notice`, `--changed seon.oversight/unit`

Held paths: this lane's row stays held for 1b remainder/1c after the two rulings.
