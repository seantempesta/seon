---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, analyzer, test, selection, gate, sci]
---

# A quoted symbol makes a host test gate a branch `defn`, and the gate runs that test in SCI

## Probe: what makes the reach fact (2026-09-26)

- **clj-kondo does not.** Linting
  `(defn quoter [] (resolve 'q.core/target) '(q.core/target 2) `target)` with `:analysis`, kondo's `:var-usages`
  holds `target` only for the syntax-quoted `` `target `` and for `#'target`. `'q.core/target` and
  `'(q.core/target 2)` are not var usages; they appear only in kondo's `:symbols` analysis.
- **Seon's analyzer does, deliberately.** `seon.fn.analyzer/attributed-usages` (`src/seon/fn/analyzer.clj:567`)
  promotes every qualified symbol in kondo's `:symbols` to a usage with `:reference true`. That becomes
  `:seon.fn/references`, declared as "source-observed function names without a resolved call shape … selection
  follows them conservatively" (`resources/seon/schemas/seon.fn.edn:70`; 7eeed900d, "Retain unresolved call shapes
  as references and widen test selection"). The reason is real: `(requiring-resolve 'ns/f)` is a call that kondo
  cannot see, and this codebase makes thousands of them.
- **The effect** (bridge pass at 10d6fdff5): `test/seon/dev/mcp_bridge_test.clj` quoted `'user/lane-mcp-probe` as
  data in an assertion. So the test "reached" `user/lane-mcp-probe`, and the hosted agent's `defn` of that name ran
  it in its entry gate. The test body ran in SCI and died on `ns-resolve 'seon.cluster` ("No namespace:
  seon.cluster found"), and the install was refused (`seon.test.accretion/install-refusal`). 39fbe0354 avoids the
  case by naming the probe freshly per run; the class remains.

## Is this the right reach fact for gating?

The over-approximation is sound as a selection policy. Running one extra test costs time, and missing a real
dynamic caller loses coverage. Two things are wrong around it:

1. **The executor, not the edge, is the defect.** The reached test is a `:core` test whose body is host-bound: it
   calls JVM-only `ns-resolve`/`requiring-resolve` over namespaces SCI does not hold. The law is that a branch
   interprets differing rows and affected callers in SCI, and refuses unsupported overrides by name instead of
   running other code. So the gate owed a named refusal ("this reached test is host-bound and cannot run against
   the branch's override"), or a host run. It ran the body in SCI and reported the resulting crash as the test's
   failure.
2. **One attribute carries two meanings.** A qualified symbol quoted as the argument of
   `resolve`/`requiring-resolve`/`ns-resolve`/`var`/`find-var` is a dynamic call: execution can reach the target.
   Any other quoted symbol is a data mention: the name appears, but execution does not follow it. Both are useful
   (deletion and break reports should see mentions), but only the first is an execution dependency.

## Model proposed (analyzer unchanged until ruled)

- Keep `:seon.fn/references` as mentions, for breaks and deletion.
- Accrete one new attribute for dynamic calls: symbols quoted as the direct argument of a resolving function, read
  from the same kondo node parse that `parsed-facts` already walks. Gating then follows `:seon.fn/calls` plus
  dynamic calls, and mentions only where no other edge exists and the answer would otherwise be "unknown coverage".
- Independently of the edge split, the gate's executor refuses by name a reached test that is host-bound when the
  gated change is a branch override, and never interprets it in SCI.

The owners are the analyzer (`seon.fn.analyzer`) for the edge, and the entry gate (`seon.test.accretion`, via
`seon.test`) for the executor.
