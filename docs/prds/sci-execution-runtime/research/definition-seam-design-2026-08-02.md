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

## Candidate contexts: test before committing — with one trap

Owner direction, same afternoon: "we can spin up any sci context we
need … run old tests on function candidates and only commit them to the
main context if they pass." This is better than accept-and-report where
it works, because it never lets a broken definition into the shared
world at all, and it does not contradict ruling #30 — nothing is rolled
back if nothing was installed.

THE TRAP, measured 2026-08-02 with a throwaway `sci/init` context:
`sci/fork` isolates only HALF of what it looks like it isolates.

```clojure
;; in the fork: (defn f [] :CHANGED-IN-FORK) and (def fresh :FORK-ONLY)
{:original-sees-redefinition :CHANGED-IN-FORK   ; LEAKED
 :fork-sees-redefinition     :CHANGED-IN-FORK
 :original-sees-new-name     "ABSENT: Unable to resolve symbol: probe/fresh"}
```

A brand-new name stays in the fork; A REDEFINITION OF AN EXISTING NAME
LEAKS INTO THE PARENT, because `fork` copies the env atom
(`reference-code/sci/src/sci/core.cljc:326-331`) while `bindRoot`
mutates the shared Var object in place — the behavior ruling #32
already recorded for the env atom, here confirmed to cross a fork
boundary.

Redefinition is precisely the case the accretion design targets, so
**a fork must never be used as the candidate context.** A candidate
context has to be BUILT, not forked: `cluster-ctx` already composes
exactly that (`build-base-ctx` → `acquire!` → `install-session-image!`,
`src/seon/sci/eval.clj:1211-1234`). Cost is the cold-acquire path that
was removed from the per-turn hot path earlier in this program — order
of a few hundred milliseconds, which is acceptable per DEFINITION event
and unacceptable per call. Measure it before sealing.

Consequence for sequencing: candidate-test-then-install becomes viable
for definitions, and accept-and-report remains the fallback for cases a
candidate context cannot cover (anything whose test genuinely needs the
committed world). Both keep ruling #30 intact.

## Contexts are per cluster, not per agent

Same conversation, the owner asked whether agent contexts must now be
stored separately, wanting Agent A to build functions that Agent B can
then simply call after asking for them by message.

No new storage: that collaboration is what the CURRENT design already
provides, and it is why the SCI context is per cluster. One live
context per cluster (ruling #27) plus every agent may call every
function in its cluster's program graph (ruling #20) means A's new
function is immediately callable by B — same context, same program.
The message exchange ("can you build X" → "go for it, I added these")
is ordinary agent messaging; nothing in the runtime needs to change to
support it.

The word "context" is doing two jobs here and the collision is the real
hazard:

- the SCI CONTEXT is the evaluation environment and program — PER
  CLUSTER, shared by every agent in it, and the thing candidate
  contexts are copies of;
- an AGENT'S CONTEXT is what is RENDERED into its prompt — per agent,
  derived from database facts by the walk, and it NEVER gates execution
  (ruling #20's standing clause).

"Agent-specific context assembled on the fly" is the second one, and it
is already derived per agent on every turn. Keeping these two senses
apart in speech and in code is what prevents someone building per-agent
interpreter contexts, which would break exactly the sharing the owner
asked for.

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
