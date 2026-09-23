---
type: research
status: diagnosis-complete
created: 2026-09-23
tags: [agent-platform, rendering, tests]
---

# Value-test reds: diagnosis and smallest fix design

The system already carries a profile into `value/prepare`, which composes the structural walk with `print/fit`; the REPL probe below shows that an explicit string bound works. The smallest improvement is to repair stale test inputs and assertions, preserving that composition and the independent diagnostic floor.

**Finding: these nine reds do not establish an E1 renderer regression.** Eight have stale fixture/expectation causes; the producer-precedence case also exposes a real failure in contract-refusal construction. No evidence supports rolling back E1/E2 or rebuilding clipping in `value.clj`. No source or test was edited.

## Evidence boundary

- Assignment evidence: `tmp/orchestrator/proof-g1g3.txt`, run `2b1bc760ff8e`: 49 executed, 11 reused, 266 passes, 15 failures, 12 errors, 36,403 ms. Those are the **whole request** counts, not nine-test counts. `8a8a241e71e4` did not execute most of these members and proves no earlier green.
- `bin/seon status`: pid **32489**, start instant **2026-09-23T16:54:18.064Z**. The loaded source root is `/Users/sean/src/seon/data/source/8085db7046233fb96bce843309dc207973341a87`, also returned by `(seon.fs/source-directory)`. Do not describe it as the current checkout or as verified `574d3b5c7`.
- Checkout inspected initially: `1581142d6c00320e899ddca52fbf1309ba3d501d`, branch `refactor/agent-platform`. Line references below are checkout references at this investigation; the tree has unrelated in-flight edits, including instrumentation. Loaded-stack lines are identified separately.
- MCP `runtime_status` and `eval_clj` available. Runtime reports turn-proc ping **unknown**, hook publication off, and existing failed-run/error counts. This is a reachable JVM, not a health pass. No adoption, reload, reset, restart, test gate, provider call, or branch mutation was performed.
- All probes use JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`, namespace `diagnosis.value-reds-20260923`, private session `value-reds-diagnosis`, `read_only true`, timeout 5,000 ms. No default Var was replaced. The MCP transport itself blob-stored two large diagnostic responses; read-only evaluation is not a promise that this installed transport writes nothing.
- Existing issue authorities: [symbol fixtures](../../seon/issues/value-floor-fixtures-still-hand-strings-to-symbol-typed-attributes.md), [missing string elision](../../seon/issues/the-agent-profile-no-longer-cuts-an-oversized-rendered-string.md), and [search contract canonicalization](../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md). The first two already record these failure classes on 2026-09-17. The missing-elision note left the cause open; the profile measurements here answer it.
- No canonical test rerun: there is no implementation in this slice, existing red receipts remain valid evidence, and the producer test currently uses prohibited global `with-redefs`. The read-only probes below establish causes without replaying that mutation. No new green is claimed.

## Suspect commits

The requested `git log --since=2026-09-23 -p -- src/seon/render/value.clj src/seon/print.cljc src/seon/sci/admit.clj` initially returned no entries with its implicit time boundary. Repeating with explicit midnight (`--since='2026-09-23 00:00:00 -0600'`) returned 260 diff lines. Those patches, the named commits, and earlier blame/history were inspected.

| Suspect | Verdict |
|---|---|
| E1 `0d6eef939` | Adds `datum`, `bounded-text`, cause rendering and `floor`; changes only the ordinary renderer's exception-node message from `ex-message` to `floor` (`src/seon/render/value.clj:547–563`). It does not replace the successful string/map path or remove `print/fit`. The missing cuts are whole successful values, not projection-failure nodes. Refuted as their cause. |
| E2 `380647a56` | Changes prepl output failure handling and client unreadable-result evidence. It does not change these test bodies, profiles, transaction normalization, or renderer selection. Stored assertion values and direct probes agree; no E2 attribution. |
| cut-l1 `e228ecaf8` | Deletes unused namespace-budget, transcript-history and walk helpers. `dir` here uses `seon.repl/render-directory-ai`, still present at `src/seon/repl.clj:32`. None of the nine asserts a deleted helper. |
| cut-l1 `fff6f5f0f` | Deletes `enrich-elisions`, `restorable-node` and its unused opaque-face set. `prepare` still calls `print/fit` (`value.clj:612–614`); `fit-node` still calls `fit-text` (`print.cljc:1289`). No surviving caller in these tests requires restoration. |
| G2 `eb9969f7b` | Deletes `print-node-edn`, a direct `canonical-edn` wrapper, and converts the artifact test caller at `value_test.clj:459`. That member is not one of the nine. Harmless for these failures. |

No parent namespace was loaded: the earlier issue evidence, exact patches, and successful existing-bound probe refute the proposed E1 cause without introducing another loaded implementation.

## Per-red verdicts

The three questions are applied to each row: **D** = tests deleted machinery; **R** = carries a retired assumption; **W** = tests wanted behavior of a surviving seam. No row warrants deleting its entire behavior regression.

| Red (`seon.render.value-test/…`) | Cause and commit/source evidence | D / R / W and smallest correction |
|---|---|---|
| `an-oversized-string-shows-its-prefix-not-only-a-count` | `ead3a7df43` raised the agent budget from 1,024 to **15,000** estimated tokens (`config/default.edn:100`). Test `value_test.clj:807–844` still assumes 6,000 characters exceed the default. Profile has no `max-string-length`; `print/fit` derives its string limit from the token budget (`print.cljc:1323–1330`). Actual whole string costs **1,875** estimated tokens. `cut` is nil, and numeric assertions then throw; no renderer NPE occurred. | D no; R yes (default-size assumption); W yes (prefix/coordinates/requery). Give the regression a fixed explicit bound, e.g. `max-string-length 128`, and assert the bound is exceeded before inspecting the cut. Existing owner works; do not add string clipping to `value-node*`. |
| `a-pulled-function-row-is-its-attributes-not-steering-prose` | Stale lookup `[:seon.fn/sym "seon.db/q"]` at `value_test.clj:190` dates to `59c7d78c45`. Program-edge/symbol conversion `0e8f7d3236` makes that value invalid. Recorded `actual` is two read-refusal maps with different `:seon.error/at`, not two function rows. Error rendering correctly selects an error pair. | D no; R yes (string symbol); W yes (bare function attributes). Use the symbol in both direct lookup and generated SCI source; **quote the lookup vector in executable source**, not just its selector. Positively assert the returned `:seon.fn/sym`, not `(seq raw)` which accepts refusals. |
| `an-explicit-pull-keeps-its-nested-shape-in-shown-text` | Manual agent map at `value_test.clj:729` predates `1ada78050`, which made `:seon.agent/branch` required (`resources/seon/schemas/seon.agent.edn:50`). The proof explicitly refuses missing branch at entity 62718; later pull sees no created agent. | D no; R yes (incomplete hand-built agent); W yes (nested pull shape). Compose `agent/creation-tx` (`src/seon/cluster/agent.clj:185`) with the explicit `"shape"` cluster and namespace, then steward relation; use `support/transacted!` and verify the subject before evaluation. Do not relax the schema. |
| `background-poll-keeps-identity-while-payloads-grow` | `d7b3930ff4` converted setup to `support/agent-tx` (`value_test.clj:228`). That helper from `090601ae1a` requires exactly one cluster (`test_support.clj:697–718`); the branch fixture inherits default and the test adds `"poll-render"`. Failure is before polling/rendering. It also retains string `:seon.effect/owner` at line 233, already documented in the symbol-fixture issue. | D no; R yes (one-cluster inference and string edge); W yes (identity survives payload growth). Pass known cluster/name directly to existing `creation-tx`, fix symbol edge, surface transaction refusal, supply an explicit profile that actually cuts the chosen payloads. Do not add another context/fixture mechanism. |
| `declared-producers-still-have-absolute-precedence` | Test `value_test.clj:361–379` redefines `matching-shapes-in` to return an **unregistered** `:fixture/declared-producer`. Since `0f5f849bd1`, specificity reads the compiled registry schema (`render.clj:368–374`); lookup returns nil, violating `internal/entity-entries`' schema contract (`schema/internal.cljc:71`). Its refusal then fails in `canonical-definition` on the raw callable `malli.core/schema?` (`schema.clj:643–647`). The normalized-form path was introduced by `c6db6b3586` (`fn/schema_shape.clj:75–81`, `instrument.clj:630`). Read-only nil-schema probe reproduces this exact secondary exception. | D no; R yes (a fake shape row stands for an installed declaration); W yes (producer precedence **and** honest contract refusal). Replace global redefinition with a real canonical fixture schema declaring the pair. Separately repair refusal normalization at the contract owner; see design below. Never make `value.clj` catch this and pretend selection succeeded. |
| `default-entity-map-renders-refs-as-installed-identities` | Test constructs `:seon.ns/requires [7904]` (`value_test.clj:714–719`), but `0e8f7d3236` changed requires to a set of **symbols**, not refs (`resources/seon/schemas/seon.ns.edn:34`). Live schema confirms `:db.type/symbol`. `attribute-value` only resolves installed refs (`value.clj:419–425`), correctly leaving this synthetic value alone. | D no; R yes (retired edge type); W yes (actual refs render identities). Retain reference coverage using an installed non-component ref with a positively established target; separately assert symbol requirements remain symbols. Do not convert symbol attributes to ref lookups. |
| `dir-of-a-large-namespace-shows-members-and-how-to-continue` | Same default-budget change `ead3a7df43`. Actual shown text in proof line 97 is **47,254** characters, **14,766** estimated tokens, under 15,000. `render-directory-ai` emits a declared column/row projection (`repl.clj:32–42`, introduced by `8df86358ba`); its 66 rows are inside one projected text node. Structural `max-children 32` does not mean 32 rows of that text. | D no; R yes (size and projection assumptions); W yes (bounded discovery). Use a deterministic lower profile budget, establish overflow, then assert prefix/coordinates/requery. For executable structural-path coverage, exercise the structural result separately. Do not infer missing rows from the number 66 or reintroduce deleted namespace budgets. |
| `explicit-structural-results-retain-attributes-through-real-evaluation` | Same singleton-cluster setup failure: scripted `d7b3930ff4` call at `value_test.clj:108` after seeding `"render-results"`; helper `090601ae1a`. Latent invalid strings at lines 115 and 133 would refuse next. The setup failure establishes nothing about structural rendering. | D no; R yes (fixture selection and symbol spelling); W yes (structural attributes). Use explicit existing agent creation, symbol lookups quoted in SCI source, refusal-checking writes and fixed profile. Require cuts only for cases whose values actually exceed that profile; small config/function rows are not inherently over budget. |
| `transacted-preserves-error-entities-as-data` | `a86e93e21` added exact whole-map equality at `value_test.clj:941`. The shape-only arity has converted every non-ref sequence to a set since `f609acb711` (`value.clj:150–162`). The actual blob shows only `:seon.error/expected`, `:seon.error/offending`, and `:seon.error/problems` differ: vector → set. Both contract-validation/operation assertions pass. The database-aware arity preserves the same refusal exactly in the probe. | D no; R yes (shape-only conversion is identity); W yes (error maps are ordinary data, scalar vectors retain order with schema authority). Move exact-preservation assertion to the database-aware arity; keep both arities' declared-map acceptance checks. Do not add an error-shape bypass, special-case keys, or silently change every one-argument caller's sequence semantics. |

## Smallest fix design and review boundary

**Cost before code:** test repair adds no runtime work. Existing rendering depends on the admitted structural window, ordering work for maps/sets, text admitted by the profile, and fitting iterations—not the store or entire program. `transacted` depends on entity attributes plus members of declared many/ref attributes. Target: retain those costs, no new per-call scan, cache, registry, renderer, or total printer. Fixture construction uses the cluster already named by the test, not a query guessing it from all clusters.

1. One test-file conversion: explicit profile limits; quoted symbol lookup refs; explicit `agent/creation-tx` with the test's known cluster; canonical declarations for producer precedence; real installed refs for ref rendering; schema-aware preservation assertion. Reuse canonical helpers, script repeated symbol/caller conversions, then inspect the generated SCI forms. Preserve each surviving behavior above. **No production `value.clj` fix is supported by these nine receipts.**
2. Keep the diagnostic floor untouched. `value/prepare` → `print/fit` remains the presentation boundary; HTML remains complete. E1's `datum`/`bounded-text`/`floor` continue providing projection-independent diagnostic output. Do not route successful values through diagnostic text, add a second clipping pass in prepl/repl/web, or replace diagnostic output with unbounded `pr-str`.
3. Separate surviving production defect: a contract refusal must not require recanonicalizing a compiled schema in a way that loses its named predicate. The exact failing data is `malli.core/schema?`, not an anonymous application predicate. `boundary-refusal` already holds the compiled contract and projection; carry/use its admitted authored schema or already retained normalized identity. Where normalization needs predicate names, use the supplied registry/declared predicate identity, including the existing named `malli.core/schema?`, rather than scanning global Vars or inventing a fallback string. Malli `m/form` (`reference-code/malli/src/malli/core.cljc:2579`) supplies form data; `m/validator` (`:2632`) caches validators, and `m/validate` (`:2639`) delegates to it. `fn.schema-shape/authored-form` already calls `m/form`; the lost callable identity is at **our durability conversion**, not proof that Malli lacks schema inspection. Verify the retained authored form/identity source before selecting the exact change. Preserve the original invalid-input diagnostic and whole cause if normalization itself fails.
4. The nil-registry case remains invalid input. A real fixture schema fixes the test; fixing refusal construction ensures other invalid inputs produce the right declared diagnostic. Do not weaken `entity-entries` to accept nil or teach rendering about `:fixture/declared-producer`.

**Review decision:** the fixture corrections are straightforward conversions at existing seams, but this is **not one obvious production fix at value.clj**. The contract-refusal repair needs independent review of its concrete authored-form/predicate-identity path before implementation. This diagnosis is not that review and authorizes no implementation. No child lane was launched from this bounded assignment.

Braids removed: configurable defaults with regression thresholds; inherited fixture contents with intended cluster selection; synthetic match rows with claimed registry installation; schema-free normalization with exact data preservation. No new braid is needed. Rendering, fixture admission and refusal reporting retain their separate roles and fail at their own boundaries.

Dependency baseline: committed Malli gitlink `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`. The Malli checkout is concurrently dirty; this is source-seam inspection, not an independent proof of its runtime revision. Upstream `schema`, `form`, `validator`, `validate` are the inspected APIs; no fork-specific guarantee is needed. Inputs are the held compiled schema and registry; recomputation belongs to changed declarations, not every refused call. Do not add another schema walker or cache.

## Read-only reproductions

All forms below ran with the MCP settings given above; local bindings only. Values are summaries of full inspected envelopes, not canonical test passes.

### Successful string rendering versus a forced cut

```clojure
(let [start (System/nanoTime)
      profile (seon.render/agent-render-profile seon.config/defaults)
      raw (apply str (repeat 3000 "ab"))
      unit {:seon.render/value raw :seon.render/profile profile
            :seon.repl/handle 'result/e0123456789ab
            :seon.render.call/id [:diagnosis/value]
            :seon.sci.admit/caps (seon.config/result-caps seon.config/defaults)}
      shown (seon.render.value/render-ai unit)]
  {:source (seon.fs/source-directory) :profile profile
   :shown-length (count shown)
   :parsed-type (str (type (clojure.edn/read-string shown)))
   :prefix (subs shown 0 (min 100 (count shown)))
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **0.853709 ms**, prepl event **18 ms**. Profile: budget 15000, depth 8, children 32, multiline, no string-length key. Shown length 6002; parses as String; starts with quoted `abab`.

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      profile (seon.render/agent-render-profile seon.config/defaults)
      directory (seon.sci.eval/directory-value database 'seon.turn true)
      show (fn [v p]
             (seon.render.value/render-ai
              {:seon.render/value v :seon.render/profile p
               :seon.repl/handle 'result/e0123456789ab
               :seon.render.call/id [:diagnosis/value]
               :seon.sci.admit/caps (seon.config/result-caps seon.config/defaults)}))
      full (show directory profile)
      small (show (apply str (repeat 3000 "ab"))
                  (assoc profile :seon.render.profile/max-string-length 128))]
  {:requires-schema (get-in database [:schema :seon.ns/requires])
   :directory-keys (keys directory)
   :directory-functions (count (:functions directory))
   :directory-chars (count full)
   :directory-tokens (seon.ai.tokens/estimate full)
   :directory-cut? (clojure.string/includes? full ":seon.print/omitted")
   :bounded-string (dissoc (clojure.edn/read-string small) :seon.print/prefix)
   :bounded-prefix-length (count (:seon.print/prefix (clojure.edn/read-string small)))
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **456.766458 ms**, prepl **459 ms**. Requires schema is symbol/many/indexed. Forced string cut: prefix 128, omitted 5872, total 6000, next-offset 128, path `[]`, bound `max-string-length`, requery `(seon.print/value-at result/e0123456789ab [])`. Direct structural directory has a cut (19,701 chars / 6,156 tokens); this probe deliberately has no SCI context, so it is **not** evidence of declared directory-pair dispatch.

### The declared directory projection fits the current budget

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      directory (seon.sci.eval/directory-value database 'seon.turn true)
      text (seon.repl/render-directory-ai directory)
      profile (seon.render/agent-render-profile seon.config/defaults)]
  {:directory-chars (count text)
   :directory-tokens (seon.ai.tokens/estimate text)
   :budget (:seon.render.profile/token-budget profile)
   :directory-rows (count (:seon.repl/rows (clojure.edn/read-string text)))
   :functions (count (:functions directory))
   :string-tokens (seon.ai.tokens/estimate (pr-str (apply str (repeat 3000 "ab"))))
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **247.286917 ms**, prepl **249 ms**. 47,254 chars, 14,766 tokens, 66 rows/functions, budget 15,000; string 1,875 tokens. The preceding attempt had an extra closing parenthesis and was rejected by MCP as `invalid-form` before evaluation; the corrected form above supplied this evidence.

### Missing declared schema and the exact error-entity difference

```clojure
(let [start (System/nanoTime)
      projection (seon.db/carried-projection
                  @(seon.cluster.boot/connection "default"))
      compiled (malli.registry/schema
                (:seon.schema.projection/registry projection)
                :fixture/declared-producer)]
  {:registry-value compiled
   :diagnostic (try (seon.schema.internal/entity-entries compiled)
                    (catch Throwable t (Throwable->map t)))
   :ms (/ (- (System/nanoTime) start) 1e6)})
```

Body **0.698333 ms**, prepl **2 ms**. Registry value nil; `ExceptionInfo`, `:seon.schema/noncanonical-definition :seon.schema/unnamed-callable`, offending callable `malli.core/schema?`. Full envelope retained as MCP artifact `0891c94292ecdd3e8dad021365f102d3f146eae13521367dc8039646922a5fc6` (37,934 bytes). A subsequent compact stack probe confirms `boundary-refusal` → `normalized-form` → `authored-form` → `canonical-definition`; no source replacement was used.

The existing assertion blob `8b68c4fef2238a2d45863c8bb842593b78bbf12f409aac98aacc4604ab0da667` was read with `seon.blob/get` and EDN-parsed as `(not (= left right))`. Only expected/offending/problems differ, each PersistentVector → PersistentHashSet. Blob-read/difference body **1.019167 ms**, prepl **3 ms**.

```clojure
(let [connection (seon.cluster.boot/connection "default")
      data (clojure.edn/read-string
             (seon.blob/get connection
               "8b68c4fef2238a2d45863c8bb842593b78bbf12f409aac98aacc4604ab0da667"))
      refusal (second (second data))]
  {:database-arity-preserves? (= refusal (seon.render.value/transacted refusal @connection))
   :shape-arity-preserves? (= refusal (seon.render.value/transacted refusal))})
```

This preservation subform ran combined with the compact missing-schema stack probe: body **1.032208 ms**, prepl **2 ms**. Results **true / false**, respectively. Combined response artifact `3694586fa18696f52145eff639af5a6da02c93c676be7a1f6b3effae92311ae5` (8,106 bytes).

## Follow-through and verification limits

After review and implementation, submit **one** named `seon.test/run` request through `bin/test-check CLUSTER --ns seon.render.value-test` on the implementation's cluster branch. Include the contract-refusal regression and `seon.render.floor-test` in the same request when that production owner changes. Establish installed/adopted identities and armed entry first; the orchestrator owns adoption and any cold/platform proof. Do not treat a doc commit as publication.

Retain checks for prefix, exact omitted/offset/total, executable requery, actual installed ref identity, complete nested pull shape, producer precedence, scalar vector preservation, and floor behavior over nil messages, hostile objects, cause cycles and bounded collections. Unchanged floor source is not a fresh totality proof; this investigation did not rerun its hostile-value tests or prove termination for arbitrary blocking user accessors/lazy realization. HTML/browser paint also not tested.

Timing: this lane's probes and shell operations were sub-second (the largest timed probe was 459 ms prepl; status shell 199 ms). Cache hits/misses and allocation deltas were not exposed by these read-only probes; no cache was created or cleared. Historical request **36,403 ms** is an over-ten-second defect, not excused as cold/priming: status attributes it to `seon.test/run`; finer exclusive timing cannot be inferred from overlapping inclusive counters. The orchestrator owns its existing timing issue/landing, and the shared ledger prohibits lanes appending shared issue notes. This lane neither reran nor claimed to fix that cost.

Changed/released path: `docs/research/agent-platform/value-test-reds-2026-09-23.md` only. Net src **0**, test **0** lines. Documentation-only commit; its ID is reported in the final handoff (not self-embedded). No push. **RESET NEEDED: no.** Remaining work is the reviewed fixture conversion and separately reviewed refusal-owner repair, then the one installed verification request.
