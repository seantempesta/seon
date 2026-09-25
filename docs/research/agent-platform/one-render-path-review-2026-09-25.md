---
type: review
status: current
created: 2026-09-25
scope: Independent design review of one-render-path-design-2026-09-25.md (4f509ca3b)
---
# One render path: design review (review-one-render-path-opus)

This review read the design end to end. It checked it against the owner's words quoted in the design, the CLAUDE.md
render/presentation laws, the [clipping inventory](clipping-sites-2026-09-25.md), and source at `4f509ca3b`:
`render.clj:269-626,1087-1330`, `render/value.clj:37-131,425-653`, `print.cljc:413-442,856-870,1115-1345`,
`schema.clj:3888`, `sci/eval.clj:3181-3225`, `cluster.clj:392`, `problems.clj:591-633`, `oversight.clj:201-212`,
`walk.clj:760-781`, `bootstrap.clj:315-325` and `ai/tokens.cljc:128-169`.
**Verdict: keep the direction, but not as written.** The selection rules have two P0 holes that would switch off most
declared renderers. The floor section describes the right goal with an algorithm that is underspecified and contradicts
itself. Fixing the findings below makes the design smaller.

## Live evidence (MCP, JVM mode, `read_only`, root `/Users/sean/src/seon`, cluster `default`)

All probes read `(seon.db/carried-projection @(seon.cluster.boot/connection "default"))`. They made no definitions and no writes.

- **Render-pair census** over `:seon.schema.projection/forms` (3,391 forms): **326 schemas declare an AI/HTML pair**.
  Only **28** are maps containing an identity attribute. **165** are identity-less maps and **133** are `:and` forms.
  Nearly all of both groups are `*-error` schemas composed over `:seon.error/base`, plus reports such as
  `:seon.db/transaction-report`. Those schemas use **89 distinct producer symbols**. Elapsed 2.7 ms, prepl 6 ms.
  The response was windowed to blob `7ead874d…`, 27,650 bytes.
- **Identity-attribute ownership**: 55 identity attributes. 47 appear in some map schema and **29 appear in more than
  one**. Examples: `:seon.agent/id` is in 72 schemas (4 with pairs), `:seon.turn/id` in 34 (2), `:seon.fn/sym` in 12,
  `:seon.cluster.eval/id` in 6 (1), `:seon.error/id` in 4 (2) and `:seon.error/signature` in 5 (2). Most of these
  schemas are request or result envelopes. Prepl 7 ms. The response was windowed to blob `5704f820…`, 17,244 bytes.
- **Namespace-stage reach**: of the function contracts in the projection (2,066), 9 return `:seon.render/ai`. Five are
  on no schema: four `seon.render-simplification.fixture-*` test fixtures and **`seon.run/render-namespace-ai`**. The
  match used a `pr-str` regex, so this is an estimate that may miss union returns. Prepl 9 ms.

## P0: fix before any production edit

**P0-1. The entity-ownership rule (§1.2) is unsound as written.** "The unique entity declaration owning the identity
attribute" has no unique answer for the most common entities: `:seon.agent/id` appears in 72 schemas. Declaring an
owner is not enough on its own. Any request or result map carrying `:seon.agent/id` would then render as an agent. That
is the envelope capture that `render.clj:369-379` currently guards against with its storable-required-attribute filter.
- **Smallest fix:**
  - Declare the owner on the identity attribute's own schema, reusing the existing `:seon.program/row-schema` property
    (`program.cljc:170-205`). This is data-only accretion on at most 47 attributes, with no new key.
  - Dispatch a map to that row schema only when every present key is declared by the row schema, plus `:db/id`. This is
    an O(present attrs) closed-key check.
  - Every identity attribute present must name the same row schema. `:seon.error/id` and `:seon.error/signature` can
    both appear on one error.
  - Envelopes carrying foreign keys fall to the floor.
  - This is a declared lookup, not structural matching and not a registry scan.

**P0-2. About 91% of declared pairs (298 of 326) belong to identity-less error and report values, and §1 gives them no
reachable identity outside direct producer returns.**
- Today `matching-shapes-in` reaches them wherever they occur: in `runtime_status` faults, in test failures, and nested
  in results. §1 deletes that path and leaves "a producer may supply its named branch". It also calls `:or` branches
  ambiguous, but every error-returning arity is an `:or` of exact alternatives by law.
- CLAUDE.md requires "Compose error base and **satisfied** schema renderers", so the specific error renderer must stay
  reachable.
- **Smallest fix: errors name their own producer.** `:seon.error/base` carries `:seon.error/operation`, a qualified
  symbol (for example `'seon.render/raw-output`, `render.clj:1273`). Resolve it to that function's declared output
  alternatives in `:seon.schema.projection/function-contracts`. Validate the value against only those alternatives using
  the cached validators (`malli.core/validator`, `core.cljc:2637`).
  - Exactly one valid alternative is the identity.
  - More than one is a named diagnostic.
  - An operation that is not a contracted function gets the base renderer.
  - Cost is O(alternatives), with no registry scan.
- Apply the same rule to a top-level eval result: a form `(f args…)` whose head resolves to a contracted Var supplies
  that Var's output alternatives. Validating them picks the branch.
- **Which producers cannot supply a name?** Any other form: `let`, threading, `map`/`for`, literals, and interop. Those
  values go to the floor, with the per-map ownership lookup (P0-1) still applied to nested pulled entities. That is
  correct under "structural resemblance is not identity".
- §1.1 and slice 2 must name this mechanism. As written, "carry actual return provenance through `shown-result`" has
  only three possible implementations: form-head analysis (sound, as above); ambient "last armed return" state
  (forbidden); or a `vary-meta` provenance stamp (forbidden by "Derive without stamps").

## P1: correct in the design before slicing

**P1-1. §3 needs a concrete single-pass allocator.**
- "Shallow demand (scalar encoded length, cheap counted size)" mixes characters and element counts, which have
  different units. "Redistribute surplus … before expansion" means either re-rendering the branches that were cut (the
  deleted search loop, now local) or scanning hidden subtrees, which §3 forbids.
- **Smallest algorithm, standard sequential water-filling.** At each collection, after reserving syntax, whole keys and
  the group-elision cost:
  1. Visit the k selected children in ascending *known* cost: scalars by encoded length, then collections by count.
  2. Give child i the share `remaining ÷ children-left`.
  3. Recurse with that share as an argument, then subtract what the child actually used.
  4. Place the children back in canonical order.
- This is max-min fair in one traversal. Small siblings finish whole and pass their surplus on; the largest sibling
  gets the rest.
- The frontier expansion in §3.3 then becomes redundant: a bounded share already stops one branch from deepening at
  its siblings' expense. Delete it rather than describe two mechanisms.
- Falsification by hand at B = 100 tokens, which is 400 characters (`tokens.cljc:159-169` is linear, so the character
  allowance matches `estimate` exactly):
  - **One huge branch plus tiny siblings**, `{:a (vec (range 1e6)) :b {:c 1} :d "x"}`: `:d` and `:b` are shown whole,
    and `:a` gets about 370 characters (about 60 elements plus an elision naming 999,94x).
  - **The design's own three-branch probe**, a/b/c: each gets about 130 characters, including a string prefix.
  - **Leaf strings**: 50 × 10 kB strings each get about 8 characters of prefix. Below the minimum share (P1-2), the
    floor shows fewer representatives plus a group elision.
  - All three meet "snippets of each part"; the current floor passes none (the design's own probe).

**P1-2. The "minimum snippet share" is the one remaining unstated dial.**
- Take a vector of 10,000 entity maps with 20 attributes each. The unstated minimum decides between showing
  roughly 266 elements with one attribute each or 40 elements with six each.
- **Fix: derive it from the value, not a constant.** Pick the representative count n′ so that each representative can
  show its own first level: whole keys plus a one-token value snippet. Record the rule once in §3. This keeps "no
  independently tunable ceilings" true.

**P1-3. Rendered text inside a floor render breaks both "one budget" and "renderers are not trimmed".**
- §2 lets renderers return terminal text that is "neither fitted nor redispatched", while §3 promises output ≤ B. The
  design does not say what happens when a child renderer's text exceeds its share.
- `problems/ai-prose` (#11, KEEP) shows the defect today:
  - It calls `render.value/render-ai` itself (`problems.clj:628-633`), so it serializes and gets a fresh full budget
    when nested.
  - It falls back to `(render/agent-render-profile config/defaults)`, which breaks "Derive missing profiles at entry or
    refuse" and "`seon.config/defaults` is not configuration".
- **Fix:** a renderer returns either a **smaller value**, which the floor then allocates with that producer excluded on
  that path, or final text.
  - Returned values carry no print-node grammar for authors to learn.
  - Final text is charged in full; overage is a named diagnostic against that renderer, never a cut.
  - Reclassify #11 from KEEP to CONVERT: `ai-prose` returns its family-lines map.

**P1-4. #13 KEEP retrieval with "a retrieval page size separate from print width" is a second presentation cut in disguise.** A
page size chosen first and then fitted by the floor under B makes two cuts to one value.
- **Fix:** follow inventory item 3. `prepare` honours the cursor offset (`display-value`, `value.clj:354-362`, always
  starts at 0), and `get_value` becomes the floor render at path and offset. The next offset comes from the floor's own
  elision.
- Delete `window` and its `pr-str` sort (`value.clj:297-303`).

**P1-5. Slice order leaves most renderers switched off in between.** Slice 1 deletes shape matching before slice 2 adds
provenance. Between the two, every error or report pair falls to the floor while HEAD still loads, which is a silent
behaviour loss.
- **Fix:** land the replacements first, then delete `matching-shapes-in` dispatch and specificity ranking in the same
  slice that switches callers. The replacements are the error-operation lookup, the head-Var lookup and row-schema
  ownership.
- The same slice converts the explicit-stage callers:
  - `oversight/unit` (`oversight.clj:211-212`, explicit `:seon.render/ai` on the request): declare the pair on
    `:seon.oversight/unit` and supply it through the producer.
  - `seon.run/render-namespace-ai`, the only live namespace-stage reach, plus the 4 fixtures.
  - The namespace owners passed by `walk.clj:774-781`, `bootstrap.clj:324` and `web.clj:1463`.

**P1-6. The §2 renderer API migration is not required by the owner's direction.**
- Moving 89 renderers to a two-argument `(render-fn value context)` touches each function and its contract. That is
  well over slice 5's 80 added lines, and it is a "permanent dual API" risk of the design's own making.
- **Fix:** keep the existing merged unit (`producer-argument`, `render.clj:251-267`).
- The property that matters, "no spend counter reaches renderers", only needs `:seon.render/profile` dropped from the
  unit. Delete slice 5's migration half; keep the half that removes `project-node*` redispatch.

**P1-7. The allocator must charge exactly what emit prints, and §3 does not say how.**
- `print-namespace-maps` factoring (`#:ns{…}`), elision syntax, the legend and escaping all live in `emit`.
- A second cost model beside the emitter will drift.
- **Fix, with one mechanism per role:**
  - Cost scalars with the emitter's own leaf printer, `emit-text` on the leaf node.
  - Take delimiter and elision costs from the emitter's constants.
  - Emit once and compare with the allowance once. Being over is a named defect with its path, never another pass.

## P2: tighten

- **P2-1.** "Long unary chain → compact path plus leaf snippet" (§3.3) needs a new node face, although §2 says "no
  second rendering language". It also needs an O(depth) scan to find the leaf, which is a hidden-subtree scan.
  - Nested `{:a {:b …}}` already prints its path compactly.
  - **Fix:** descend while the share lasts, then use the existing `:subtree` elision with the path. Drop the leaf-snippet
    promise.
- **P2-2.** Child identity (§1.1) needs no Malli `in->paths`. In Seon a map's key *is* its registered attribute schema.
  The child identity is that schema's `:vector`/`:set`/`:maybe` element reference, found in the registry in O(1).
  Keep `in->paths` only for anonymous nested schemas, which §1 already says convey no identity. So delete it.
- **P2-3.** Things `print/fit` does right that the allocator must keep, named as must-pass regressions:
  - `value-test/background-poll-keeps-identity-while-payloads-grow` and `fit-preserves-breadth-and-long-strings`,
    both cited in `print.cljc:1330-1340`.
  - Carried admission elisions: `::truncated-string` `::length` and carried `:children` totals
    (`fit-children`, `print.cljc:1146-1190`). Stored artifacts arrive as nodes that are already cut.
  - `preserve-requery`.
  - Also: `fit-entry` rebuilds paths by `edn/read-string` of emitted key text (`print.cljc:1133-1140`). Retaining
    original paths in node data (§3.3) deletes that; say so.
- **P2-4.** §5 adds a new capacity refusal for `retained-count` overrun (`prompt.clj:232`) with no slice. Either give it
  a row or move it to an issue. As written it is an unowned requirement.
- **P2-5.** `compare-values` (`print.cljc:413-442`) re-sorts nested maps inside every comparison, and it walks
  sequential keys element by element, so an infinite lazy key never terminates. That breaks §3's "opaque lazy seq"
  rule inside ordering. Order by that rule too: opaque values compare by class and identity hash.
- **P2-6.** Slices 3 and 4 both edit `value.clj`, which is fine sequentially. Slice 3's 90 / 180 estimate is plausible
  only if `fit`, `fit-node`, `fit-children`, `fit-entry`, `structural-elision` and the emit-node re-sort (together about
  220 lines) are all deleted in that slice. State that as the slice's exit criterion.

## The 21 verdicts

The following are right:
- 1, 2, 3 (B as the floor's only input), 4, 5, 7, 8, 9/10 (one shared renderer), 14, 15, 16, 18, 19, 20 and 21.
- 12: a renderer-declared query page with a total and a link is "render less", provided N is a constant of the history
  renderer or its query, not a configuration dial.
- 17: the opening renders subjects as identities.
- 6: keep whole-unit selection, as provider admission rather than value rendering, pending the owner's confirmation.
  This is the one genuinely open owner question.

The following must change:
- **11** KEEP → CONVERT (P1-3).
- **13** KEEP → DELETE window, with offset retrieval moved into `prepare` (P1-4).

No other KEEP hides a clipper. #3 keeps the key but must delete `max-depth`, `max-children` and `max-string-length`
from the profile schema in slice 11; otherwise they remain dials that can be read.

## Is the design smaller than it could be?

These fixes remove mechanism: the §2 API migration, frontier expansion, Malli path propagation, the leaf-snippet face and
the retrieval page size. They add only data: `:seon.program/row-schema` on the identity attributes. Corrected slices, with
line counts to be re-estimated by the implementer:
1. Add the three lookups (error operation, head Var, row-schema ownership) and convert the explicit and namespace
   callers. Then delete the stages and the specificity ranking.
2. The water-filling allocator in `value-node*`, deleting `fit*`.
3. Throwables inside the allocator.
4. Remove the `project-node*` redispatch; renderers return values or text.
5. Offset retrieval in `prepare`; delete `window`.
6. Then the domain slices as listed.

Probes per slice, matching the design's but adding: an envelope map carrying `:seon.agent/id` stays on the floor; an
error nested in a result selects its specific pair through `:seon.error/operation`; and a nested `ai-prose` receives a
share, not a fresh B.
