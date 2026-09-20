---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [issue, program, error-model, sci, contracts]
---

# Program protocol refusals have no substantive facet

The sci-program sweep applied D12: only a complete declared facet makes a
returned value an error. The existing `my.program/read-result` producer
(`src/my/program.clj:20`) catches a real read failure and returns the base,
diagnostic evidence and a boolean `:seon.program/read-refused`. That value
satisfies zero facets. `my.program/supplied-context` (`:350`) similarly returns
the base and `:seon.program/declaration-refused` when the execution context is
absent, with no substantive facet member. Its missing-context observation is
not the pure declaration owner's identity-attribute observation.

The committed [probe](../../prds/steward-platform/research/sci-program-shared-program-facets-2026-09-21.clj)
calls the actual producers and uses the complete packaged declarations:

```clojure
{:sci-program/read-facets #{}
 :sci-program/context-facets #{}
 :sci-program/declaration-facets #{:seon.program/declaration-refused-error}
 :sci-program/read-member sample/f
 :sci-program/context-member :my.program/context
 :sci-program/declaration-members #{:seon.fn/sym}}
```

This is unarmed source/schema evidence, not canonical test execution. No
database or default lifecycle was involved. The raw read failure is supplied
to the existing callback boundary; no production function is mocked.

The producer at `src/my/program.clj:392` and native interception at `:584`
also retain the shared declaration boolean. Their substantive observations
are respectively live referrers and the attempted native mutation. One
identity-set facet cannot truthfully describe all these different observations.
`seon.program.edn` still has two class declarations owned by this protocol:
read-refused-error and not-found-error. The not-found producer already has
the subject; promoting its schema alone would still leave its generic output
contract and pass-through callers unconverted.

PRD §6 applies at `seon.sci.eval/shown-result`: it must distinguish genuine
program failures, but the protocol's facet members do not express them.
Do not weaken D12 or stamp a replacement boolean. The remedy is coherent
producer, declaration, consumer and contract conversion in `src/my/program.clj`
and its tests, outside the bounded sci-program ownership. The
[landing note](../../prds/steward-platform/research/kind-sweep-sci-program-2026-09-21.md)
prices three continuation options. Acceptance requires complete facets for
real read/context/mutation refusals and real-SCI recognition of them, while
malformed arbitrary maps continue to render as ordinary data.
