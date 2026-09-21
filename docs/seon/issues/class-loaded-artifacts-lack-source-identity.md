---
type: issue
status: open
severity: blocker
tags: [issue, operator, runtime, class/n3, class-kill, wave/class-kill-queue]
---

# Give every loaded artifact enforced source identity

## Problem

A running JVM, analysis cache, or live-publication reload can serve code whose
source generation differs from the tree or published program generation
without a typed refusal. Hand-maintained reload sets make a mixed generation
constructible whenever an owner or dependency moves.

## Evidence

Current open members carry `class/n3` and are derived with
`bin/issues-index --class class/n3`.

## Owner

The source-publication generation, namespace acquisition/reload boundary, and
language-specific analysis-cache constructors.

## Acceptance

- Every loaded namespace and analysis artifact carries the digest/generation
  of the source and dependency closure that produced it.
- Reload and invalidation derive transitive owners from recorded facts; no
  namespace reload roster is accepted as input.
- A stale or mixed generation is refused before execution with both expected
  and loaded identities, and a recurring live-JVM proof moves an owner without
  editing a reload list.

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.cluster.source-test`, `seon.dev.source-instrumentation-test`). Audited HEAD `7e35df213:src/seon/cluster.clj:1956-1999` derives reload order, reloads Vars, acquires SCI, arms contracts, then records the accepted source commit. Thus the old claim of no source identity is too broad, but a failed multi-namespace reload may precede the marker update; the source is not an atomic Var swap. The concrete residual owner is `partial-hot-reload-produces-mixed-code-with-no-warning.md`; this slice's handle-shape note is another lifecycle boundary. Need controlled mid-reload failure and old/new callable evidence on a disposable cluster. No such mutation was performed on default. Keep blocker; neither old prose nor the live health timeout confirms mixed code at audited HEAD.

surface: adoption-publication

## Folded members — 2026-09-21

All four open members are archived (`status: superseded`, `superseded-by` this
note); full evidence stays at `archive/<name>.md`. This note's re-verification
above names `partial-hot-reload-produces-mixed-code-with-no-warning.md` as the
class's concrete residual owner — its evidence is folded below so the class
keeps a concrete subject after the move.

| Member (in `archive/`) | Claim | Current file:line |
|---|---|---|
| `live-publication-has-a-hand-maintained-predicate-owner-reload.md` | Live publication reloads a selected namespace set, so moving a load-time `register-core-predicate!` call leaves a running JVM with the new schema declaration and no predicate registration (`7661c0214`: reloading `seon.schema.edn` then refused `:seon.db/connection` as unregistered) | `script/seon/fresh_operator.clj:2680` (`init-form`) |
| `partial-hot-reload-produces-mixed-code-with-no-warning.md` | A live JVM runs mixed old and new code with no typed refusal | see paragraph below |
| `publication-reload-hand-lists-namespaces-and-misses-dependencies.md` | The reload set is hand-listed, so a changed dependency outside it stays stale and fails with a No-such-var error naming the CALLER, not the stale dependency (2026-08-08: `No such var: env/scope`; `No such var: form/widen-component-children`) | `script/seon/fresh_operator.clj:2680` |
| `stale-language-specific-kondo-cache-blocks-correct-code.md` | `.clj-kondo/.cache/v1/{clj,cljc,cljs}/<ns>.transit.json` keeps per-LANGUAGE arities, so a stale entry reports `invalid-arity` for correct source and names the callee, never the cache; one occurrence errored all 8 tests of an unrelated namespace | `.clj-kondo/.cache/v1/`, `seon.fn/build-manifest` blocking-error refusal |

**Folded evidence — the residual owner.** From
`partial-hot-reload-produces-mixed-code-with-no-warning.md`: on 2026-09-17 two
independent lanes observed default returning `:seon.config/missing-effective`
for 68 required configuration keys through MCP's result projection, with basis
`536871223` showing the new member schema present and
`seon.test.runner/admit-run` not loaded, while hook publication
`d6a16add-83d1-4a80-ac42-7df04240d14a` separately reported a source-change
refusal during analysis. Neither observation establishes a cause, and both are
read-only; they are the live shape of this class — a JVM serving a mixed
generation with nothing refusing it. Landing notes:
[lane guardrails](../../prds/steward-platform/research/lane-guardrails-2026-09-17.md),
[test-system stage 2](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).
Adoption at `src/seon/cluster.clj` now reloads, arms and only then records the
accepted commit, so the broad "no source identity" claim is too strong; the
residual is that a failed multi-namespace reload can precede the marker update
and is not an atomic Var swap.
