---
type: research
status: awaiting owner decision; implementation not started
created: 2026-09-20
tags: [schema, malli, bridge, projection]
---

# Step 1: explanation parity decision before implementation

The prescribed live probe found different public explanation paths. Direct
retained-root explanation does not preserve either existing surface's exact
schema paths. No production source or test was changed, and no public arity
was retired. This is the explicit decision stop, not a completed step-1 landing.

## Authority and inherited state

Read the binding [Malli bridge PRD](../plan/malli-native-bridge-prd-2026-09-20.md)
and [accepted review](bridge-dissolution-review-2026-09-20.md) end to end;
AGENTS sections 1–5 and lane rules 11–16; all five requested skills
(data-oriented-clojure, data-modeling, datahike, repl, clojure-testing);
and [step 3's Candidate and holder semantics](step3-carried-projection-2026-09-20.md#candidate-and-holder-semantics).
Read the context-generation roadmap entry and the steward-platform roadmap.

Branch: `steward-platform`. Observed HEAD:
`718a18e8ff74a35d5293fbfdacdcaadfc9899ced`. Shared-tree foreign edits were
preserved. `default` pid 24777 was alive; the hook's `:current-source` was
already disabled. No restart, adoption, stop, worktree, or new cluster.

`runtime_status` returned `:seon.dev.mcp/projection-failed` instead of health
evidence. Recorded the observation on the existing
[MCP issue](../../../seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md).
Read-only JVM evaluation returning string data succeeded. The running
projection contained 3,228 forms, and `mr/schema` for `:seon.agent/id` did
not return a Malli Schema. This is live pre-change evidence, not a canonical
fixture measurement or adoption-freshness proof.

## Dependency ledger

| Seam | Source | Consequence |
|---|---|---|
| Lazy provider, fast registry, lookup | `reference-code/malli/src/malli/registry.cljc:17`, `:81`, `:97` | Fixed-input provider can memoize compiled roots; full serial realization precedes the fast snapshot. |
| Schema-owned cache | `reference-code/malli/src/malli/core.cljc:345`, `:2626`, `:2642` | Reuse schemas; public explainer wrappers can differ while their internal product is cached. |
| Captured reference scope | `reference-code/malli/src/malli/core.cljc:1943`, `:1968` | Replacement must recompile dependent roots; overlays cannot rebind them. |
| Named pointer versus retained object | `reference-code/malli/src/malli/core.cljc:2550` | Named lookup through `m/schema` adds a pointer; supplying an existing Schema preserves it. |
| Recursive dereference | `reference-code/malli/src/malli/core.cljc:2834` | Reconstructs walked nodes and removes named wrappers, while leaving explicit `:ref` nodes. |
| Current projection explainer | `src/seon/schema.clj:3637` | Uses recursive dereference before acquiring the explainer. |
| Current candidate explainer | `src/seon/schema.clj:3527` | Explains a keyword against a newly built registry, retaining an outer named pointer. |
| Admission copy | `src/seon/schema/internal.cljc:337` | Ignores the supplied registry when constructing its full-population copy. |

## Exact live probe and result

Evaluated once as one read-only MCP JVM form in `default`; elapsed reported
by the tool: 5 ms. It creates only local values and changes no definitions or
database facts. These synthetic forms probe Malli explanation semantics;
they are explicitly not a substitute for the canonical armed fixture.

```clojure
(try
  (let [forms {:probe/leaf :int
               :probe/base [:map [:probe/x :probe/leaf]]
               :probe/child [:and :probe/base [:map [:probe/y :string]]]}
        p (seon.schema/declaration-projection forms)
        r (:seon.schema.projection/registry p)
        value {:probe/x "bad" :probe/y 1}
        summarize (fn [e]
                    (mapv #(select-keys % [:path :in :value :type])
                          (:errors e)))]
    (pr-str
     {:projection
      (summarize ((seon.schema/projection-explainer p :probe/child) value))
      :candidate
      (summarize (seon.schema/explain-candidate-value forms :probe/child value))
      :retained
      (summarize (malli.core/explain (malli.registry/schema r :probe/child)
                                    value))}))
  (catch Throwable e (str (.getMessage e))))
```

Exact returned data:

```clojure
{:projection [{:path [0 :probe/x], :in [:probe/x], :value "bad"}
              {:path [1 :probe/y], :in [:probe/y], :value 1}]
 :candidate [{:path [0 0 0 :probe/x 0], :in [:probe/x], :value "bad"}
             {:path [0 1 :probe/y], :in [:probe/y], :value 1}]
 :retained [{:path [0 0 :probe/x 0], :in [:probe/x], :value "bad"}
            {:path [1 :probe/y], :in [:probe/y], :value 1}]}
```

Value paths and invalid values agree. Schema paths differ on both existing
surfaces. Removing the recursive reconstruction and routing both surfaces
directly to the retained root cannot preserve these bytes simultaneously.
No finding about local recursion or humanized explanation parity is claimed.

## Owner decision: exactly three options

Costs below are incremental engineering estimates beyond the PRD's step-1
estimate, not measured execution times.

1. **Use native retained-root schema paths (recommended; simplest).**
   Guarantee: one retained root and Malli-owned validation/explanation caches;
   preserve value paths, offending values, and validation semantics. Cost:
   approximately 0.5–1 day for the explanation-consumer census and canonical
   regressions. Give up exact old `:path` and explanation-root compatibility
   on both public surfaces. This requires an explicit exception to the PRD's
   explanation-path parity requirement before implementation.
2. **Preserve each surface through acquired derived explanation schemas.**
   Guarantee: aim to retain the current dereferenced projection and named
   candidate explanation grammars, acquired once per generation, with Malli
   owning each derived Schema's caches. Cost: approximately 1–2 additional
   days plus retained derived objects; canonical local-ref and full-envelope
   parity must establish the guarantee. Give up the requirement that both
   explanation APIs call the single original retained root directly.
3. **Translate explanation paths at the existing boundary.**
   Guarantee: retain one compilation root and derive mappings for the two
   public grammars, verified against old behavior including local refs.
   Cost: approximately 2–3 additional days for path/schema-envelope mapping
   and regression coverage. Give up the intended dissolution of explanation
   interpretation; Seon owns a mapping Malli currently performs structurally.

The stop follows the PRD section 7 instruction, “Delete per-use recursive
reconstruction only with explanation-path parity,” and the assignment's
explicit stop at a genuine decision. The accepted review likewise says to
expose the discrepancy rather than silently change the public shape.

## Refreshed caller and coordination boundary

Search: `rg -n 'valid-candidate-value\?|explain-candidate-value|candidate-validator|candidate-explainer' src test`.
219 matching lines across 45 paths, including definitions and comments;
these are dated lexical counts, not a call count. The path set matches the
PRD's 44-path value-API inventory plus `test/seon/fn_test.clj` for the
acquisition helper. No removal or conversion has begun.

Explicitly held production caller paths remain:

- `src/seon/cluster.clj` (also observed dirty)
- `src/seon/cluster/prompt.clj`
- `src/seon/turn.clj`

Held matching tests from the launch assignment:

- `test/seon/cluster/armed_test.clj`
- `test/seon/cluster/boot_test.clj`
- `test/seon/cluster/cohost_boot_test.clj`
- `test/seon/cluster/message_test.clj`
- `test/seon/cluster/problem_routing_test.clj`
- `test/seon/cluster/store_test.clj`
- `test/seon/turn_test.clj`
- `test/seon/turn_work_test.clj`
- `test/seon/test/accretion_test.clj`

No held file or foreign session was edited or operated. Refresh ownership
before the final caller conversion; no arity-removal commit may precede it.

## Verification and complete changed paths

Changed paths only:

- `docs/prds/steward-platform/research/bridge-step1-registry-2026-09-20.md`
- `docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`

Canonical compiler/provider/copy/allocation counts: **not measured** before
the decision stop. After counts: **not applicable**, no implementation.
Fast tally: **not run**, no production/test changes. No cold gate ran.
Generation isolation, dependency replacement, complete canonical realization,
missing-reference refusal, local-ref shadowing, conjunction requiredness,
component acceptance and fresh/derived isolation remain implementation work.
The live probe above establishes the explanation discrepancy only.

The prescribed production namespace load passed (exit 0):

```sh
clojure -M -e "(require 'seon.schema 'seon.schema.internal 'seon.config 'seon.maintenance 'seon.cluster 'seon.turn 'seon.render 'seon.cluster.prompt)"
```

This loaded the current shared-tree source; it is not an isolated gate proof.
Cold named/platform proof and live proof of the eventual implementation remain
owed to the orchestrator. No hot reload, new fork or development adoption was
performed; all live observations exercised the pre-existing JVM definitions.
