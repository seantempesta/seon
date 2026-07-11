---
type: research
status: active
tags: [research, database, agent]
---

# datahike recursive-rule truncation — root cause + fix state (2026-07-11)

## TL;DR

The plan-tile unit's "depth>1 broken on the pod" finding is TWO bugs in the
fork's planner-based `execute-recursive-rule` (`src/datahike/query/execute.cljc`),
and the second one is **not CLJS-only** — the JVM planner path has it too,
masked in production by `*force-legacy*` defaulting to `true` on CLJ. The pod
(planner always on) hits both.

Live repro (default pod, `seon.client/mem-db`, 4-node chain n1←n2←n3←n4,
rules `[(descendant ?a ?d) [?d :parent ?a]] [(descendant ?a ?d) [?m :parent ?a] (descendant ?m ?d)]`):

- Both-vars-free closure → only the 3 depth-1 pairs (expected 6).
- 20-node chain → 85 pairs (expected 190) — truncated at exactly depth 5.
- Ground call-arg `(descendant <root-eid> ?x)` → **throws**
  `demand_set.size is not a function`.

## Bug 1 — CLJS: JVM-only optimizations not platform-gated (FIXED, uncommitted)

- `delta-driven-expand` and `magic-base-scan` are `#?(:cljs nil)`, but
  `use-delta-driven?` (execute.cljc ~3181) and `compute-magic-info` activation
  did not exclude CLJS. The moment a simple binary transitive-closure rule's
  delta dropped below 16 tuples, CLJS took the shortcut, got `nil` → empty
  rec-rel → **fixpoint silently terminated**. The 20-chain depth-5 cap is the
  delta shrinking 19→18→17→16, then 15 < 16 kills the loop.
- The magic-set demand machinery called Java `.size` on a `js/Set` (size is a
  property there) → the TypeError on any ground-call-arg recursive query.

Fix applied (uncommitted in `reference-code/datahike`): `use-delta-driven?`
→ `#?(:cljs false :clj (and …))`; `magic-info` → `#?(:clj … :cljs nil)`;
`extract-demand-values` size reads made platform-correct. CLJS takes the
plain semi-naive fixpoint (correct, unoptimized). Regression tests:
`test/datahike/test/cljs_recursive_rule_test.cljc` (wired into
`nodejs_test.cljs`) — proven RED pre-fix (3 depth-1 pairs / TypeError / 85),
GREEN post-fix (`bb node-cljs-test`: 15 tests, 70 assertions, 0F/0E).

## Bug 2 — BOTH platforms: the optimizations assume ONE recursion direction (OPEN)

With the planner on (`*force-legacy* false` on CLJ; always on CLJS), the same
3 tests fail on the JVM — 85-pair truncation and `#{}` for the ground query —
**pre-existing before any of today's edits** (verified by stash+run).

Analysis (verify in the fix unit): `delta-driven-expand`'s docstring says it
handles `[?x :attr ?t] (rule ?t ?y)` — given delta `(t, y)`, reverse-AVET
lookup of `?x` where `x.attr = t`, emit `(x, y)`. `my.plan`'s rule is the
transposed-but-equivalent form `(descendant ?a ?d) :- [?m :attr ?a] ∧
(descendant ?m ?d)` — the correct expansion is a FORWARD lookup (m's attr
value), so the reverse lookup emits already-seen shallower pairs → dedup
empties the delta → early termination with the same iteration arithmetic as
Bug 1 (hence the identical 85). `magic-base-scan` similarly EAVT-scans the
demanded entity's OWN attr datoms (its parent) instead of AVET-scanning
entities pointing AT it — for the descendant direction the demand-driven base
returns nothing (`#{}`).

The guards (`rec-has-db-pattern?`, `rec-shape-simple?`) check op TYPES only,
never the join topology/direction — a structurally-computed direction check
(or direction-aware lookups) is the root fix. `base-scan-attr` derivation:
`src/datahike/query/lower.cljc` ~431-455.

## Production impact

- `my.plan` roll-ups on the pod print "0 of 0 steps done" for any tree deeper
  than 1; the frontier mislists undrained roots. The seon suite was blind —
  every test tree is depth-1 (seon-side depth-2 test is part of the unit).
- JVM legacy engine (production default) is correct — "JVM correct on the
  same store" in the original finding is the legacy engine, not the planner.
- `plan-block-html` carries a documented `build-forest` deviation from
  `rollup`/`ready-leaves`/`anchor` — re-unify after the fix lands (suspected
  workaround-shaped divergence).

## Fix-state checklist

- [x] CLJS platform-gating + regression tests (uncommitted, fork tree)
- [ ] CLJ planner direction fix + both-rule-forms coverage
- [ ] Fork CHANGELOG + commit + push (seantempesta/datahike, sync-upstream)
- [ ] seon deps.edn sha bump + reference-code/datahike submodule bump (same
      commit — deps.edn:85 rule), rebuild, live pod proof
- [ ] seon-side my.plan depth-2 test (the suite-blindness gap)
- [ ] plan-block-html build-forest re-unification
- [ ] acme: `bin/acme build` + coordination.md flag (typeahead lane restarts
      at a safe bench boundary)
