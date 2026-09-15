---
type: issue
status: open
severity: friction
tags: [issue, schema, wave/schema-audit]
---

# Projection-state contract invokes deref as a predicate

`seon.schema/call-with-projection-state` declares its first argument as
`[:fn clojure.core/deref]`. This dereferences the candidate instead of checking
whether it is a valid holder. A dereferenceable value's truthiness decides
acceptance; a delay or future can therefore perform work or wait during
validation. Malli's `-fn-schema` uses `-safe-pred` and catches explanation
exceptions (`reference-code/malli/src/malli/core.cljc:1761`), so this is not
a claim that a non-dereferenceable input throws through instrumentation.

The existing `:seon.sci.eval/projection-state` predicate is not a drop-in
replacement: it requires an environment-state holder, while the SCI owner
also constructs a plain atom containing `:seon.schema/projection` and calls
the same function. Tightening only the function metadata would reject that
production caller. The call-preparation state predicate checks `IAtom`, but
its declared meaning is a different state holder.

The schema-audit lane owns metadata in `seon.schema` and `seon.sci.eval`,
not their holder construction. Resolve the holder's actual shared contract
at that owner and test both a real cluster holder and the production SCI
acquisition holder before replacing the effectful predicate. Do not repair
this by declaring an arbitrary-value exemption.
