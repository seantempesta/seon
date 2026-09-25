---
type: review
status: complete — implement after fixes
created: 2026-09-25
reviewer: Astra, independent review under AGENTS.md step 4
design-pin: 52e252f743940f95e20e3b4f3a8afa3b096d39e4
requirements-pin: 19e9fe5f4
verdict: implement after fixes
scope: source and design review only; no implementation or runtime verification
---

# Function face and canonical authoring: independent review

**Verdict: implement after fixes.** Keep the declared function render pair, stored contract references, a lossless declaration-head transform, and normalization before merge tests. The blocking defect is the claimed reindexing seam: `published-index-rows` reads existing compiled rows, so it cannot establish the new source's contract, analysis or digest. Context membership and inline-function preparation also need corrections before their slices are implementable.

The existing flow is source → analyzed program row → declared render pair, with candidate rows tested before acceptance. The smallest composition improves those owners: Malli supplies contract structure, rewrite-clj preserves source boundaries, the existing analyzer produces replacement rows, and the existing gate tests those exact rows.

## Evidence and limits

Reviewed [the first-pass design](function-face-and-canonical-authoring-2026-09-25.md), [language requirements](language-requirements-2026-09-24.md), [query/render building block](query-render-building-block-2026-09-25.md), AGENTS.md, and the named source owners and their relevant callers. Source citations below are at `52e252f74`; the five named source files are unchanged from `19e9fe5f4`. Dependency pins: Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`, rewrite-clj `60782e501aaf312cb90c9ff0bee05d5da5125563`.

`derived-contracts-2026-09-25.md` was absent when reviewed. Requirement 1.12's missing policy is therefore an explicit dependency, not reviewed behavior. The design reports a faulted JVM and an older installed renderer; that is its historical observation, not a status independently verified here. Per assignment, no code evaluation, tests, `bin/seon`, restart, or adoption was run. Runtime timings, allocation and memory are unmeasured in this review. The reported 297/369 census and timings are attributed evidence; the document gives a method and aggregates but not the exact census form and complete result needed to reproduce those numbers independently.

| Claim | Finding |
|---|---|
| Today's face has no contract/doc/source | Confirmed for **AI** (`src/seon/render/ns.clj:744–774`). HTML already renders the supplied row's doc and spec (`:776–787`); it lacks source. Narrow the statement. |
| Structural equality cannot choose identity | Supported independently of the census. `resources/seon/schemas/seon.store.edn:3,11–12` declares path, dir and lock-file with the identical form. Malli `util/equals` (`util.cljc:8–12`) tests forms, not domain identity. |
| `shown-subjects` determines already-in-context | Refuted as proposed: historical rendering, current schema content and selected prompt membership are different facts (P1-1). |
| Canonicalization belongs before tests | Supported by `program/definition-digest` (`src/seon/program.cljc:375–409`), which includes source. The claimed reindex implementation is false (P0-1). |
| One pure rule source plus the existing JVM check | Supported in principle. `seon.edit` is deliberately free of schema-loading/JVM dependencies; the hook already requests a live contract check and reports unavailable (`bin/seon-hook:559–620`). Keep semantic verification on the JVM side. |
| Speculative settlement plus one commit solves inline rendering | Datahike supplies the immutable operation; consistency, call preparation and identity coverage remain unproved (P1-3/P1-4). |

## P0-1 — The merge route does not reindex canonical source

**Design §2.4, lines 272–278, and F4.** `replacement` at `src/seon/cluster/source.clj:739–745` calls `fn/published-index-rows`, then `fn/reconcile-tx`. The former's implementation (`src/seon/fn.clj:3564–3635`) reads entities and converts refs into portable rows. It does not parse changed source, invoke clj-kondo, compile contracts, or recompute definition digests. The design's statement that merge already re-derives rows is false.

Changing only `:seon.fn/source` in exported rows could leave the old spec, bindings, references and digest attached to different bytes. Merely recomputing the digest would still leave stale analysis. A gate passing on such rows is not proof of the proposed canonical declaration.

**Smallest fix:** retain the placement before candidate tests, but explicitly route rewritten declarations through the existing analysis/publication owner with their resolver context and candidate projection. `seon.fn/analyze-forms` (`:1110–1169`) is an existing batch seam to compose, not itself a promise that every required row field is regenerated. Name the complete producer chain, including effective contract, contract facts and digest; replace candidate rows atomically before testing. Do not build a second indexer in `seon.edit` or `cluster/source`.

The old and canonical source digests are expected to differ. Equality must hold between **canonical analyzed row, tested candidate, accepted row and reindexed file**, not between authored and canonical source digests. Reword requirement 1.14's digest clause accordingly when the owner rules on it. A mandatory proof changes only head notation and demonstrates a new digest, fresh matching analysis and tests on that digest; stale pre-rewrite evidence must not qualify. Reprice F4 after naming this path.

## P1-1 — “Ever shown” is not “already in context”

**Design §1.3, lines 151–167; F5.** `prompt/select` explicitly drops the oldest whole units (`src/seon/cluster/prompt.clj:295–333`). Counterexample: E1 displays schema K, E2 suppresses K because E1 exists, then selection retains E2 and drops E1. The retained prompt has no K definition. An elision or `(doc K)` handle preserves retrieval, not the owner's requested automatic display when absent.

There are two additional counterexamples. K can change under the same lookup identity, and a schema child can be cut before its complete definition appears. A subject lookup alone proves neither current content nor complete display. Separate evaluations in the same uncommitted batch also cannot discover each other's shown subjects with the proposed database query.

**Smallest fix:** initially deduplicate only inside the current render operation, using explicit carried observations of complete schema content. Show the definition again across evaluations. This preserves exact saved text and needs no historical-membership attribute. Cross-evaluation suppression must wait until the prompt owner can guarantee retention of the supporting unit, with content identity and complete-display evidence; otherwise it needs a fresh ordinary read when that dependency is absent. Do not re-render old shown bytes. The proposed `shown-subjects` may describe historical observations, but must not be named or used as the known-context set.

F5 can therefore be omitted from the first cut, saving its estimated 20 lines and avoiding a new persistence contract. Prove retained-definition absence, schema revision, a cut child, compaction, and same-batch ordering before introducing suppression across evaluations.

## P1-2 — Head equality omits metadata and schema properties

**Design H1–H4 and §2.2.** Malli's `-function-info` reports input/output/guard and arity bounds, but does not report the function schema's properties (`reference-code/malli/src/malli/core.cljc:2186–2201`). `:function` also owns properties separately (`:2250–2258,2281–2288`). Reconstructing only `[:=> input output guard]`, or stripping a single-arity wrapper, can lose properties while all proposed equality checks pass. `:->` deliberately forwards its non-guard properties (`:2376–2385`); the normalizer must preserve them too. This concerns descriptions, generators and other declared consumers, not just validator acceptance.

Clojure also merges name metadata, leading attributes and a trailing multi-arity attribute map in a defined order (`reference-code/clojure/src/clj/clojure/core.clj:294–336`). Equal body bytes and call edges do not establish equal effective metadata. Conflicting `:malli/schema` declarations, type hints, `:inline`, `:arglists` and custom metadata cannot be resolved by sorting or moving one node without preserving that order.

**Smallest fix:** preserve all non-target nodes, effective Var metadata and schema properties. Compare normalized complete contract structure, including wrapper/arity properties and registry scope; use `m/-function-schema-arities` followed by `m/-function-info` for arity information, as `program/contract-facts` actually does (`:1080–1109`). Refuse a wrapper elimination when its properties cannot be transferred equivalently. Never infer equivalence from equal validators or just input/output forms. Keep `:catn` labels and variadic structure.

Keep the schema-free syntax operation in `seon.edit`; put registry-dependent normalization and verification beside the existing contract/analyzer owner. H3 must preserve Malli built-in predicate schema symbols: `fn/qualify-schema-symbols` explicitly warns that blindly qualifying `ifn?` can make it unresolved (`src/seon/fn.clj:195–218`). Prove conflicting metadata locations, property-bearing wrappers, guards, reader conditionals and discarded head nodes, plus idempotence. Unsupported transformations should be explicit findings, not a claimed canonical success. No general semantic-equivalence engine is needed.

## P1-3 — The speculative database must reach the whole preparation path

**Design §5, lines 348–359.** The cited `:1523–1527` is the hook's database acquisition, not `plan-for`. `plan-for` already takes an explicit database (`src/seon/call_preparation.clj:989–1020`). The hook dereferences the environment's connection (`:1523–1527`) and checks `prepared-symbols` before calling it (`:1538–1546`). Thus one `or` join in `plan-for` cannot make a new evaluation-owned closure preparable.

Other gates also require `:seon.fn/sym`: prepared-symbol queries (`:338–395`), `contract-transaction`'s AVET lookup (`:722–743`), and arity/argument queries (`:745–871`). `callee-identity` currently derives a symbol only from `:ns`/`:name` metadata (`:1327–1340`). Furthermore, `current-snapshot` accepts a held snapshot whenever its basis-t is at least the supplied value's (`:705–715`), and `plan` uses basis-t cache fences (`:1170–1218`). Distinct speculative values can share a transaction number. Changing the hook's database input without changing this assumption risks reusing another value's plans.

**Smallest fix:** carry one immutable render database through snapshot selection, identity lookup, plan derivation and suppliers. Resolve the callable's declared identity to one entity once, then reuse the existing queries over that entity; do not scatter two-identity joins or add another preparation subsystem. Use invocation-local preparation state for speculative values until content-valid reuse is demonstrated. `snapshot` already distinguishes speculative from committed values (`:623–647`); compose that seam instead of feeding speculative state into the live connection's monotonic cache.

The new row identity also needs an actual closure-to-identity mapping at the evaluation owner. A durable name does not serialize captured values or make a closure recoverable after restart. Prove first render, caller-supplied defaults winning, two speculative values at equal t, and unchanged preparation of ordinary functions. These are prerequisites, not one extra join's implementation detail.

## P1-4 — One transaction gives atomic visibility, not equality with the preview

**Design §5, lines 335–353.** Datahike's documented `db-with` is `(:db-after (with db tx-data))` (`api/specification.cljc:483–493`; `api/impl.cljc:134–143`). This supports a pure preview. It does not reserve the connection head or guarantee that later application uses the same database.

`settle-batch!` currently constructs multiple receipts and transaction-function calls before committing (`src/seon/turn.clj:3543–3598`). Another writer may advance the branch between preview and commit; the same transaction function may then derive different facts, and a render can have used an obsolete contract/projection. An allocation-stable generated function name does not address that race. Nor does `db-with` alone provide Seon's final validation or prove that arbitrary transaction functions are effect-free.

**Smallest fix:** retain the single-transaction settlement, render from a held, validated candidate value, and use the existing `:datahike/expected-basis-t` writer fence (`src/seon/db.clj:4736–4738`) for the final transaction. On conflict, use the existing refusal path; never rerun the evaluation or replay effects. State which already-produced settlement data is safe to preview, and keep rendering outside transaction callbacks. Historical read evidence must identify the held basis and newly settled subjects without treating speculative t as a committed historical database.

Price the actual batch operation, including validation, rendering, snapshot/plan construction and staged evidence. The source documents that a speculative preparation snapshot can derive the whole program (`call_preparation.clj:649–658`); “one extra small index application” is not a complete cost model. A race proof must show either the matching settlement and shown bytes commit together or a named refusal settles through the existing owner.

## P1-5 — A data identity is sound, but partition isolation is not automatic

**Design §5, lines 355–363.** Agree with a distinct identity attribute and evaluation-owned components, with reusable contract facts. However, `program/source-family?` tests row-schema plus source-attribute properties, not the entity's partition (`src/seon/program.cljc:18–36`). Copying those properties from `:seon.fn/sym` onto the new identity would enroll it in program identities despite a `:seon.data` entity declaration.

Also, program attribute membership is a union across program entity schemas (`program.cljc:38–68`). Shared `:seon.fn/*` fields therefore remain program attributes even when attached to a data row. `published-index-rows` uses that membership when traversing references (`fn.clj:3588–3605`); a partition label alone does not prove that data rows can never be copied through a reachable ref. The specifically flagged `contract-findings` query is safe from this leakage as written: it joins `:seon.fn/sym` for both identities and specs (`fn.clj:1874–1881`).

**Smallest fix:** declare the data identity without program-source-family membership, and audit export reachability as well as top-level identity selection. Reuse `program/contract-facts`' explicit function-symbol input; its `with-contract-facts` adapter currently hardcodes `:seon.fn/sym` (`program.cljc:1117–1148`) and needs an owning adapter, not a temporary program identity written to the database. Require proof that merge/write-back exports no evaluation-owned rows, evaluation deletion cascades through owned arities/bindings, and shared schema-shape targets survive. Keep the generated closure name a value for diagnostics and resolution.

## P1-6 — Requirement 1.12 is not yet a complete contract policy

**Design §4/H8.** One effective stored contract and no duplication of derived input source are good choices. Malli's destructuring parser supplies the mechanics, but `::required-keys` is a policy switch defaulting to false (`reference-code/malli/src/malli/destructure.cljc:83–110,125–162`). Turning it on for every destructured key does not decide what `:or` defaults, explicit optional keys, unregistered keys or an authored request-schema identity mean. The reported lack of form-equal explicit inputs is evidence against automatic migration, not evidence that existing contracts are wrong.

**Smallest fix:** allow the face to show the current complete effective contract independently. Hold derived authoring until its owner specifies explicit-versus-derived precedence, optionality/default semantics, output/guard authoring and per-part provenance. No inferred `:any` output or silent replacement of an authored named request contract. Demonstrate the same effective contract and provenance through admission, canonicalization, reload and write-back. H8 cannot erase the only authored output declaration. This review does not invent a second derivation policy while that design is absent.

## P2 findings — Correct the claims and reduce the first cut

**P2-1: Literal grep is still unmet (design §1.5).** The example defines `(defn definition-digest ...)`, not a literal qualified symbol at its definition (`program.cljc:391`). Schema resources use namespaced maps; `#:seon.fn{:root ...}` does not contain literal `:seon.fn/root` (`resources/seon/schemas/seon.fn.edn:1–15`). Head qualification leaves body aliases and those schema files untouched. A namespace-to-path convention helps navigation but does not satisfy 1.9's “definition and every referrer.” Show the exact existing definition/source handle and filename when known; do not advertise a grep guarantee that depends on changes in other owners. Root must either retain literal-id coverage as a named migration proof or explicitly relax 1.9 to namespace-scoped navigation. Do not silently add a second identity declaration to every function. Also correct the sample pull: the building-block's existing `:seon.db/pull-arguments` is an argument vector, not the `{selector,eid}` map shown at design lines 80–82 (`src/seon/db.clj:962` replay owner).

**P2-2: F2's deletion credit includes surviving behavior.** `read-edn` is still used by `stored-arglists` and `signature-form` (`render/ns.clj:261–273`); schema-definition helpers feed compact schema/error rendering (`:460–486`); registration text feeds the full namespace face (`:526–528`). Deleting lines 121–253 is not a pure closure replacement. Keep or convert every caller and count only retired definitions. Add arity refs to the acquisition selector, currently missing them (`:29–36`). Do not book moving `first-doc-line` as deletion credit. Reprice after P0-1 and the speculative-data work; the +212/−180 total excludes significant obligations.

**P2-3: Counts and advice are not free.** An exact reaching-tests count still requires computing reach unless an existing valid result answers it. A query work bound does not make an incomplete count exact. The smallest face shows the expansion handle without the count; display a count only with current complete evidence. Likewise, build structural-match advice once per carried projection, inspect only genuine schema positions with Malli, and preserve candidate ambiguity. A raw walk of every vector subform can include literal data under `:=`, generator options or properties. Do not turn a false match into authoring advice. Keep the face's work proportional to the selected function and actually displayed schemas; record warm time, allocation and memory before accepting the 20 ms target.

## Hybrid ruling proposed for 1.14

Adopt Opus's **A: advice, never automatic identity substitution**, with two changes. First, describe the relationship as “same structural form at this projection,” not “the same schema.” Identity changes can affect **today's supplied defaults**, because preparation checks declared schema names (`call_preparation.clj:358–386`), as well as future schema evolution. Even a unique in-namespace match is not proof of author intent. Second, show ambiguity honestly: summarize candidates with a followable expansion handle rather than concealing every match with more than one possible name. Namespace reach may prioritize advice; it must not manufacture identity.

Suggested owner-approved wording for 1.14:

> Accepted declarations use one canonical notation for contract and metadata. Structurally matching named schemas are shown as candidates; a name is substituted only when the author explicitly chooses that identity. Canonicalization preserves effective metadata, contract properties and body bytes. The canonical analyzed declaration is tested, accepted and written back with matching digests; its stored source is shown exactly in context.

This preserves both owner intentions: clear standard contracts in the face, and better persisted source teaching the same authoring conventions. It separates a mechanical notation change from an authored semantic choice. The requirements file remains unchanged by this review; root must rule on the revision. Option B would make root review each semantic identity choice, adding work to every merge. Option C has no defensible automatic tie-break and should not be implemented.

Before acceptance, show current stored source as current and a differing canonical head as a **proposal**. “Will be stored as” overpromises when verification, owner acceptance or the missing derivation policy can still refuse it. After acceptance, render the exact stored canonical source. The standard contract table can already be stable for both versions without pretending that a preview is stored evidence.

## Acceptance boundary

Implement the face and conservative syntax normalization after the fixes above are integrated into the owning design. Keep cross-evaluation suppression out of the first slice; hold automatic derived authoring and inline settlement until their named prerequisites are reviewed. Preserve one render path, one analyzer, one preparation owner and one tested merge route. No new registry, formatter hierarchy, retry loop or shadow evaluator is warranted.

Root's eventual proof must cover canonical-source/digest convergence; property and metadata preservation; named-schema/default behavior; actual prompt membership; first inline render on a speculative value; a competing settlement write; and exclusion/cascade of evaluation-owned functions. These are requested proofs, **not tests run or passes claimed here**. Then measure the same function face and merge operation before/after, including speculative snapshot cost. The reported faulted installation is a separate verification boundary, not a reason to stop this source review.

This review changes only this document: source delta 0, test delta 0. No hooks were disabled, no runtime resources were acquired, and no unrelated files were edited. The assigned review path is released after its path-only commit; the orchestrator owns the ledger update.
