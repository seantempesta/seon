---
type: issue
status: resolved
severity: blocker
tags: [issue, program-graph, schema-admission, call-preparation]
---

# Source call arity is not the prepared invocation arity

The Tier 1 proposal in the unbreakable-connections inventory compares the
analyzer's source argument count with the callee's declared JVM/Malli arities.
That is insufficient for agent-authored definitions: SCI's call preparation
supplies omitted arguments from declared defaults.

Read-only default probe on 2026-09-17, 1,728 ms, complete unwindowed envelope:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      snapshot (seon.call-preparation/snapshot
                database (seon.db/carried-projection database))
      plan (seon.call-preparation/plan-for database snapshot "my.message/inbox")
      source "(defn tier1-probe [] (my.message/inbox))"
      row {:seon.fn/sym "my.message/tier1-probe"
           :seon.fn/ns [:seon.ns/name 'my.message]
           :seon.fn/source source :seon.fn/arglists "([])"
           :seon.fn/private? false :seon.schema.admission/source :agent}
      analyzed (second (seon.fn/analyze-form
                        database source [:seon.ns/name 'my.message] row))]
  {:declared (mapv #(select-keys % [:seon.fn.arity/min
                                  :seon.fn.arity/argument-count])
                   (:seon.call-preparation/arities plan))
   :accepted-source-counts
   (vec (sort (keys (:seon.call-preparation/by-supplied-count plan))))
   :recorded-call-arities (:seon.fn/call-arities analyzed)})
```

Exact value:

```clojure
{:accepted-source-counts [0 1]
 :declared [{:seon.fn.arity/argument-count 1 :seon.fn.arity/min 1}]
 :recorded-call-arities #{["my.message/inbox" 0]}}
```

The probe analyzed a form; it did not evaluate or persist that definition.
The real SCI precedent is
`test/seon/sci/shown_text_test.clj`, which evaluates `(my.message/inbox)`
in a scoped agent context and asserts an empty inbox result, with no error.
The public example in `src/my/message.clj:14` teaches the zero-argument call.

`src/seon/fn.clj/call-arities-by-caller` copies `::analyzer/arity` directly.
`src/seon/call_preparation.clj/plan-for` derives omitted-argument call shapes
from the same database. Its `prepare` also admits partial omissions by the
actual argument values; a source count alone cannot establish their unique
placement. No second implementation of that preparation algorithm belongs
in the write validator.

## Owner decision (2026-09-17)

Option 1 is accepted. The implementation derives count compatibility from
the existing plans in `seon.call-preparation`; placement remains there at
invocation. The Tier 1 canonical run passed the analyzer-produced inbox
source-count regression, the true mismatch refusal, the same-transaction
arity repair, and all 16 existing preparation tests including real SCI partial
placement. The former raw-count candidate is superseded. Evidence:
[prepared admission landing note](../../prds/context-generation/research/reset-tier1-prepared-admission-2026-09-17.md).

### Reviewed options

1. **Preparation-aware admission (recommended).** Reuse preparation's
   declared plans to prove a source count can reach a declared callee arity;
   retain value-dependent ambiguity/type refusal at invocation. Cost: the
   call-preparation and database owners plus canonical SCI regressions for
   all-default, partial and ambiguous calls; additional final-database plan
   queries per relevant callee. No new stored fact. The guarantee is count
   compatibility, not successful execution for arbitrary argument values.
2. **Check core callers only, explicitly incomplete for agent callers.**
   Cost: provenance-scoped query and coverage reporting plus regressions.
   Protects the currently compiled-source population; does not deliver the
   requested agent-definition invariant. No silent omission of coverage.
3. **Defer enforcement; keep the report advisory.** Cost: no preparation
   integration in this publication. Required-ref and render-target checks can
   land independently, but the arity writer invariant remains open.

Do not publish the strict raw-count candidate: it would reject a supported
agent definition even though the indexed core census reports zero mismatches.
Tracked in [the reset batch](../../prds/steward-platform/plan/reset-batch-2026-09-17.md).
