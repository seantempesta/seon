---
type: research
status: active
tags: [research, sci, testing]
---

# The one definition seam — custody, contracts, and accretion

Owner direction, 2026-08-02 afternoon: "centralize the overrides I
forgot about instrumenting"; "automatically run tests after functions
are defined or redefined"; "every function is validating all inputs and
outputs"; "during a redefine I want to make sure the old tests run on
the new definition — I want to encourage accretion for agents so they
don't break their own contracts." Acknowledged as future work — this
note records the design so the seam being built now can accommodate it.

## The unifying observation

There is exactly ONE place where a function's value enters a cluster's
SCI context, and it already rewrites that value: `install-contract!`
resolves the context's var and calls `sci.vars/bindRoot` with
`seon.instrument/wrap-interpreted`, applying the committed Malli
contract at `:scope #{:input :output}`
(`src/seon/sci/eval.clj:785-793`, `src/seon/instrument.clj:243-268`).
Host vars get the same treatment through `seon.instrument/apply!`
(388 instrumented vars observed live on `default`, dial `:panic`).

So "redefine functions during injection" is not a new capability to
invent — it is the existing seam. Everything the owner listed belongs
to that one seam rather than to three new mechanisms:

1. **contract instrumentation** — input/output validation (BUILT);
2. **cluster custody** — the per-cluster connection a database function
   carries (ruling #41, design in flight);
3. **definition-time test execution** — running the affected tests when
   a definition lands (FUTURE, this note).

Centralizing means these compose at one call site with one order of
operations, not that a second wrapper stacks on the first. Whatever the
custody design turns out to be, it must land at THIS seam.

## Which functions need custody is already a query

Ruling #33's parsed contract facts make selection computed rather than
enumerated, which is what keeps this out of hand-list territory. Live
counts on `default`, 2026-08-02:

```clojure
;; 9 functions declare a branch connection in some arity's input
[?a :seon.fn.arity/input-refs ?s] [?s :seon.schema/key :seon.store/branch-connection]
;; 42 declare a database value
[?a :seon.fn.arity/input-refs ?s] [?s :seon.schema/key :seon.db/database-value]
```

No roster is maintained; the system already knows.

## Accretion falls out of the data model — except for one missing edge

The valuable half is free. Tests are durable facts
(`:seon.test/sym`, `:seon.test/ns`, `:seon.test/source` — 689 rows on
`default`), and redefining a function does not retract them. So the old
tests are still present when a new definition lands: running "the old
tests against the new definition" needs no versioning, no snapshot, and
no special mechanism. It is simply running the tests that exist.

The missing half is the edge. `:seon.test` rows carry ONLY those three
attributes — verified by enumerating every attribute present on test
entities. Functions carry `:seon.fn/calls`; **tests carry no call
graph**, so "which tests exercise this function" is NOT derivable
today. That single derived edge is the whole prerequisite:

- give test rows the same `calls` edge function rows already have,
  produced by the one clj-kondo analysis that already indexes `test/`
  (never a second analyzer, never a naming convention like
  `foo` → `foo-test`, which would be a name-prefix rule and is
  forbidden);
- then the affected set is one query over the call graph, the same
  reachability derivation `:seon.fn/calls` already serves for workload
  classification.

## The sequencing question, with a recommendation

Running tests when a definition lands must not contradict ruling #30:
the session is a faithful REPL and a def that an eval made STAYS LIVE
even when its terminal transaction is refused — a real REPL never rolls
back a def. That rules out a synchronous gate that refuses the
definition on test failure.

Recommended: **accept-and-report**, matching the existing redefinition
protocol (accept, warn, message the owner — 2026-07-31 ruling, cited by
ruling #38). The definition lands; the affected tests run; results
become durable facts; failures reach the agent through its ordinary
rendered context and reach the owner as a message. Accretion is
ENCOURAGED by making breakage immediately visible and attributable,
which is what the owner asked for, rather than by refusing the write.

Open questions for the owner when this wave is scheduled:

- **Where do the tests run?** They are agent code and need the guarded
  door (time limit, caps). Candidate: a flow proc on `:compute` per
  cluster, so a slow suite never blocks the turn. `seon.test.runner/run!`
  is the existing runner and should not be duplicated.
- **What bounds the affected set?** Transitive reachability could pull
  in a large suite on a core redefinition. A depth bound is a magic
  number and therefore suspect; deriving from the changed function's
  own reverse-call closure is the honest version, with the count
  reported rather than silently truncated.
- **What if the agent deletes the tests?** Retracting a test is a
  different persistence decision from adding one, and belongs to ruling
  #30's persistence gate rather than here. Worth naming so the gate's
  design accounts for it.
- **Do host-var contracts and interpreted contracts stay one
  mechanism?** They share `seon.instrument` today; custody injection
  must not fork that.
