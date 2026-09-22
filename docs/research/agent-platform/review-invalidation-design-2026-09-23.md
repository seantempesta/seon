---
type: reference
status: reviewed; source-only evidence, runtime unavailable
created: 2026-09-23
tags: [agent-platform, invalidation, design-review]
---

# Invalidation design review — 2026-09-23

**Verdict: reject option 2 as specified; retain its goal, not its universal capture/cache API.**
Owner: “We need the happy path to be fast and to accumulate data that is reused”.
Smallest correct design: Datahike owns read currency; pure constructors memoize complete explicit inputs; receipts do not mutate the program graph; wildcard semantics remain intact.

## Evidence boundary

Read AGENTS.md end to end first. Reviewed census commit `fee8bbbe7` (working copy byte-identical), history, plan and dependency source.
Seon citations below refer to the inspected shared working tree, initially HEAD `b86a4b706`, including foreign in-flight edits; they are not claims about an adopted JVM.
`DH/` below means `reference-code/datahike/src/datahike/`; fork pin `fbd1ad2d10fb1261ef7092737a02801537c16e70` (`deps.edn:25-26` selects the local fork).
Static review only: `bin/seon status` refused “No live exact-root JVM” (0.195 s); MCP `runtime_status` returned no clusters (0.334 s).
One request, `bin/test-check --root /Users/sean/src/seon review-invalidation --ns seon.datahike-fork-test`, refused “No live cluster advertisement” (0.041 s); no branch/test was executed.
This is an unavailable runtime boundary, not a test pass or an attributed source failure. No source edits, boot, gate, reload, snapshot JVM or other lane's session was attempted. All measured shell operations before commit were below 1 s; no performance or memory savings are newly measured here. Document creation plus automatic lint took 4.2 s (tool total; internal phase timings unavailable). Lint reported missing frontmatter (fixed here) and foreign stale dependency citations (outside this one-file assignment).

## Ranked findings

**1. BLOCKER — dependency capture is incomplete, including an intended caller.**
`src/seon/db.clj:510-522` captures only calls that append to Seon's sink. Projection construction directly uses `d/datoms` at `src/seon/schema.clj:2539-2568`; wrapping `load-projection` does not capture those reads. Its existing `projection-ranges` already drives both loading and attribute selection (`:2788-2817`): deleting it as a “hand list” discards a single source of truth.
`seon.db/entity` is an eager wildcard pull and is captured (`src/seon/db.clj:2388-2411`); **raw** `d/entity` navigation reads `dbi/search` lazily (`DH/impl/entity.cljc:165-184`), outside that sink. Raw `index-range` directly calls `dbi/index-range` (`DH/api/impl.cljc:295-296`). Seon's `datoms` captures an attribute plan, while `index-page` captures `:all` (`src/seon/db.clj:2438-2484,2512-2543`). These are different coverage guarantees.
Query plans traverse rule inputs and find-pulls (`DH/query.cljc:2767-2904`), but this is syntactic dependency analysis, not observation of every executed read. Unknown function clauses return `{}` (`:2706-2741`), so a query joining `:a/id` and invoking a database-reading function can omit that function's other attributes. Fix at Datahike, not in a second Seon parser.
Rules also need proof: recursion stops at `[rule-name source]`, ignoring changed argument bindings (`DH/query.cljc:2767-2787`). For rules `[(r ?e ?a) [?e ?a _]]` and `[(r ?e ?a) (r ?e :b/value)]`, calling `(r ?e :a/id)` can omit `:b/value` from the plan. This is a source-derived counterexample, not an executed probe.
Explicit pull plans include lookup-ref attributes and widen wildcard/component expansion (`DH/pull_api.cljc:107-186`). Do not generalize that coverage to arbitrary host calls, cached child derivations, deferred work, multiple databases, mutable closure inputs or effects. Nested cache hits must carry dependencies even when no leaf read executes. An empty captured vector is not proof of purity: today's currency function uses `every?` (`src/seon/db.clj:1116-1147`).

**2. BLOCKER — definition digests are not complete derivation keys.**
The connection-local premise is correct: opening installs a new connection/generation context (`DH/connector.cljc:375-382`); materializing another commit uses its commit as conservative revision (`DH/versioning.cljc:69-100`). Never compare absent revision maps across connections as equal.
`src/seon/program.cljc:358-389` includes supplied resolver context and retained semantic members such as `:seon.fn/spec`, but explicitly excludes calls, references, keywords, writes, call arities, host-bound facts and admission provenance. `src/seon/fn.clj:1000-1001` supplies resolver context; the one-argument digest API permits none. A row digest does not hash the changing definitions of referenced schemas/macros/callees.
Those exclusions matter: projection construction reads admission provenance (`src/seon/schema.clj:2556-2568,2593-2602`); SCI reads `:seon.fn/host-bound?` (`src/seon/sci/eval.clj:891-901`). A function-digest map plus schema digests therefore does not establish projection/context equality. Namespace bindings, actual dependency inputs, configuration, constructor/analyzer versions and loaded host behavior must participate wherever read.
Use each constructor's complete immutable input value as its key, or a canonical digest of that value. Include deletions and absence, not merely present digests. Do not enlarge the declaration digest into a universal stamp. Equal program content cannot justify sharing connection-bound handles, numeric entity identities across independently built roots, mutable SCI worlds or private objects; share immutable ingredients and fork/bind custody separately.
The blob claim also conflates **input key** with **output digest**: `src/seon/blob.clj:216-231,321-329` addresses payload bytes, not derivation inputs. Durable reuse needs a versioned input-key → output-digest record, roots retained through `with-publication!` (`:299-319`), and shared/reachable backing storage across roots. Store bulky serializable products there; never live result objects, compiled closures or SCI contexts. Keep cache publication outside read dependencies. Option 2 explicitly defers this, so it supplies no restart-reuse guarantee.

**3. HIGH — stop receipt call-edge writes; renaming is only a conditional fallback.**
Verified producer: non-program analysis emits `:seon.fn/calls` (`src/seon/fn.clj:1002-1007`); settlement attaches it to the receipt (`src/seon/turn.clj:922-958`), then emits relation assertions (`:1178-1186,1641-1642`). Program attribute selection is schema-derived (`src/seon/program.cljc:29-53`); Datahike revisions advance per changed attribute, not entity (`DH/query.cljc:2568-2589`). Separation would stop this particular invalidation.
But the smaller fix is to stop persisting those form-local static edges. The program graph is declaration analysis; receipts are execution observations. Static form analysis is not evidence that a call actually executed. Named reach ignores receipt identities (`src/seon/fn.clj:1543-1568`); the generic “Callers” query does currently expose them (`src/seon/render/ns.clj:867-871`). Removing them intentionally corrects that query's meaning.
The `src/` call-attribute search found no receipt-specific consumer requiring this duplicate graph. Keep analysis needed for actual declarations and admission. If a concrete receipt inspection requirement is retained, declare receipt-specific diagnostic calls and convert that consumer; never feed them into program invalidation or test reach. A schema-overlap refusal alone is insufficient with open maps: enforce any partition restriction at the actual write owner, including extra attributes.

**4. HIGH — wildcard narrowing can silently lose data or freshness.**
Datahike correctly returns `:all` for `[*]` (`DH/pull_api.cljc:120-143`). Replacing the selector by one entity schema's attributes drops allowed extra members; replacing only the dependency set returns stale extra members after they change. Closing every entity map to enable this optimization contradicts AGENTS.md's open-map contract.
Prefer explicit selectors only where the caller actually requests a projection. Preserve true wildcard reads. Finer wildcard evidence must cover the complete entity range, lookup identity, schema changes, component closure and newly added/removed attributes/entities, including empty results. A root EAVT check alone misses changes on component children. Implement any such evidence in Datahike's pull owner; retain `:all` until that proof exists.

**5. MEDIUM — one comparator is not automatically one cache.**
Datahike already stores query results and promotes compatible entries (`DH/query.cljc:2951-3025`): inputs include query arguments and source identities; promotion checks conservative and selected attribute revisions. Reimplementing these rules in a Seon LRU is a second invalidation mechanism even if both use `clojure.core.cache`.
Expose/reuse the dependency's complete compatibility predicate, including connection/generation and temporal identity; `source-context-unchanged?` alone omits compatibility checked by its caller. Revision comparison costs O(read attributes); a miss's work is the owning query/constructor, not necessarily the whole program. Attribute plans conservatively overapproximate: another entity's write to a selected attribute still invalidates. “Exactly when what it actually read changed” is too strong.
Memoizing an expensive pure transformation of a query result is legitimate distinct work, provided the query result remains stored once in Datahike and transformed products once in their existing owner. Do not cache the same query answer again, or stamp a database/transaction with the product.

## Smallest correct design and fix order

1. Stop receipt → program-edge assertions, retaining declaration analysis; verify ordinary receipt settlement advances zero program-attribute revisions. This removes work instead of adding a schema/cache.
2. Fix dependency-plan blind spots in Datahike before expanding their use. Prove custom database functions, recursive rule bindings, lookup refs, pulls, raw navigation/index access, negative/empty reads and multiple/temporal sources. Unsupported capture is unknown/refused, never an empty “valid” read set.
3. Keep the existing pure owners and shared range definitions. Use Datahike's compatibility/currency authority to retain their input values across unrelated writes; compare revisions before expensive history work (`src/seon/db.clj:1121-1127`). Correct page/preparation/program keys in place and eliminate invalidating read-side writes. No arbitrary-`f` capture API is needed.
4. Memoize each pure constructor on its complete explicit inputs; accumulate content-addressed immutable products in the existing owner, with derivation-version identity. This permits program-equal branches to reuse work without sharing custody. For cross-root/restart reuse, persist only serializable products through the existing blob owner and a complete input mapping; do not claim this for live JVM objects.
5. Preserve wildcards; specialize deliberate projections at their callers. Only add finer dependency tracking after measuring remaining wildcard cost and proving complete output/freshness. Count misses, transformed rows, retained bytes and wall time; a new branch must reuse valid products; price input discovery separately from product reconstruction, and reuse exact immutable input evidence where available.

This preserves the achievable guarantee: unrelated **tracked inputs** do not reconstruct a derivation; complete equal inputs reuse its product across eligible scopes; unknown evidence never proves freshness. It does not promise exact datom-level invalidation from attribute revisions, universal automatic capture, or durable reuse from an LRU.
Required acceptance cases include cache-hit composition, same-input new branch, restart retrieval, resolver/contract/host-bound-only changes, wildcard extra/component changes, and receipt settlement. Existing issue classes include `docs/seon/issues/a-file-digest-does-not-identify-complete-caller-analysis.md` and `docs/seon/issues/root-page-warm-read-evidence-replay-exceeds-300ms.md`; this review extends the census, not their implementation status.
