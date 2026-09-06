---
type: research
status: complete
tags: [research, context, render, sci]
---

# Durable producing-source integration — 2026-09-06

## Finding

Real producing source should enter Seon's existing durable run path. The reply
parser, run transaction functions, per-agent episode, turn fork, evaluator,
`result/eN` bindings, and receipt settlement already implement the requested
semantics. The debug page should query and render those stored forms and results;
it should not adapt the renderer kernel into a second source evaluator.

The parser alone does not execute source or create a context.
`seon.cluster.reply/sources` turns one text string into ordered, exact source
strings through the one SCI reader, preserving comments beside forms and
parse-time namespaces (`src/seon/cluster/reply.clj:343-403`). The surrounding
run and loop owners perform execution and storage.

## Existing end-to-end seam

For system-authored source that must not make a paid model call, the existing
path is:

1. Call `seon.cluster.reply/sources` with the submitted text, assigned starting
   namespace, and the existing maximum-source bound. A flat unreadable or
   no-forms value ends the request before a run is opened.
2. Hand those exact parsed sources to `seon.cluster.run/system-run-tx`. It opens,
   claims, and plans one system-authored run using the ordinary open, claim, and
   plan transaction functions (`src/seon/cluster/run.clj:727-775`). `plan-call`
   stores every source, ordinal, namespace, and `:system` author on ordinary run
   form entities (`src/seon/cluster/run.clj:620-725`).
3. Wake the target agent's existing episode mailbox. The next
   `next-agent-work` sees the held run with a plan digest and derives `:resume`,
   not `:call`, so no provider request occurs (`src/seon/cluster/work.clj:500-575`).
4. The ordinary turn owner forks the supplied cluster context once for that
   agent and restores its definitions (`src/seon/sci/eval.clj:1722-1785`). It
   evaluates the frozen ordered sources. Each form goes through
   `seon.sci.eval/evaluate`, and each admitted value is bound as `result/eN` for
   subsequent forms (`src/seon/cluster/loop.clj:1686-1755`).
5. The same receipt start/settle transaction functions store the admitted
   result or error, namespace, read evidence, timing, and definition effects.
   When the planned suffix is terminal, the loop derives `:close`; it never
   enters the model-call situation.

This is already used for source authored by the system. Root supervision builds
ordered source rows and calls `system-run-tx` without a model reply
(`src/seon/bootstrap.clj:690-738`). Curation proof uses the same transaction and
ordinary execution semantics on an isolated branch
(`src/seon/cluster/curate.clj:160-215`). The generated opening is a related
incremental path: `append-generated-tx` atomically adds one system-authored form
and starts its receipt, then `resume-turn` evaluates it before another form may
be appended (`src/seon/cluster/run.clj:778-852` and
`src/seon/cluster/loop.clj:1910-2004`). A fixed submitted source list should use
`system-run-tx`; it does not need the generated-prefix transitions.

## Guarantee

This route gives the strongest available guarantee without new machinery:

- the displayed source is the exact durable source parsed and executed;
- comments and forms use the same reader and classification as an ordinary
  reply;
- the source executes once in one normal agent turn fork, in order;
- later forms name earlier admitted values through the existing `result/eN`;
- the existing time limit, admission caps, call preparation, database custody,
  read evidence, definition restoration, and flat failures remain in force;
- forms and results survive process loss as ordinary run, form, and evaluation
  facts;
- a system-authored planned run resumes and closes without a paid provider call.

Private symbol behavior is unchanged. The turn starts in the agent's assigned
namespace, and each parsed source carries its namespace in effect. A private Var
is accessible only under SCI's ordinary namespace rules. Definition restoration
is isolated by `sci/fork`: writes and restored roots belong to the turn fork and
do not mutate the cluster's program-only base. Settlement decides which
definitions accrete afterward.

The guarantee is not that an acquisition query shown elsewhere ran. If a form
uses `result/e0`, the prior form producing `result/e0` must be in the same
durable run. If the page shows an already acquired renderer argument separately,
it must label that value and database basis as acquisition evidence, not executed
source. To make acquisition replayable, put its real query form first in the
same source list and let the renderer form consume its stored `result/eN` value.

## Cost evidence and rejected kernel path

An immutable live probe on root-owned `lab-browser-0906` proved that an opaque
acquired plan item can be bound as `result/e0` on a fresh turn fork and consumed
by real source `(my.plan/render-item-html result/e0)`. It returned expected
Hiccup, recorded one SCI function entrance, captured reads, and left the base
context without a `result` namespace.

A warm five-sample comparison on entity `32011` measured current direct
`kernel/invoke` against fresh `fork-for-turn` + bind + parse + `evaluate`:

| sample | direct invocation | normal source evaluation |
|---:|---:|---:|
| 1 | 0.166 ms | 8.332 ms |
| 2 | 0.110 ms | 6.694 ms |
| 3 | 0.082 ms | 6.340 ms |
| 4 | 0.079 ms | 6.256 ms |
| 5 | 0.064 ms | 6.547 ms |

All values were equal and successful. The direct calls recorded zero reads for
this renderer; each turn-fork sample recorded three definition-restoration
reads. These measurements reject a fork and parse per recursive renderer leaf.
They do not argue against the normal run path: a durable source episode pays one
fork for its complete ordered form list, the existing turn boundary.

Adapting `seon.sci.kernel/invoke` to parse renderer calls would duplicate the
normal source path and still would not create stored form/result facts. It is no
longer recommended.

## Smallest lab integration

The callable transaction seam is `seon.cluster.run/system-run-tx`; the callable
parser is `seon.cluster.reply/sources`. There is not currently one public
function combining parse, run creation, transaction, and wake delivery. The
smallest production accretion is a thin submission function at the existing
cluster agent/run boundary:

1. accept agent id, source text, and the supplied cluster environment;
2. parse with `reply/sources` under the assigned namespace and existing source
   cap;
3. create a unique run id and digest the returned ordered sources using the
   existing plan-digest calculation;
4. transact `run/system-run-tx` with the current process identity;
5. offer one payload-free wake to the target agent's existing mailbox, or the
   existing armer path when the agent is not armed;
6. return the run id, then let the debug UI derive forms and results from facts
   as transaction wakes arrive.

The transaction alone does not wake the agent. `seon.cluster.wake/route!`
deliberately routes new message/effect recipient refs and new agent identities;
run and evaluation attributes are disjoint from turn wakes
(`src/seon/cluster/wake.clj:34-52`). The thin function must therefore live where
the supplied cluster handle exposes both the connection and agent routing map.
It composes the existing episode path; it adds no evaluator, context, registry,
or state machine.

The lab should initially refuse when the target agent already has an open run;
`system-run-tx` already enforces that ownership fence. Queuing behind an active
run would require a new durable scheduling decision and is outside this slice.
The UI should show pending, running, and terminal state by querying the returned
run's facts and should render no invented synchronous result.

## Paused implementation audit — 2026-09-06

The paused working-tree implementation follows the seam above. Its
`submit-source!` parses through the shared reply preparation, transacts one
`system-run-tx`, and only then offers a payload-free wake
(`src/seon/cluster/agent.clj:550-634`). `system-run-tx` now creates the open,
claim, system-authored plan, and every pending evaluation in one transaction
(`src/seon/cluster/run.clj:757-805`). The normal work derivation therefore sees
the plan digest and selects `:resume`, never the provider `:call` branch
(`src/seon/cluster/work.clj:531-567`). `resume-turn` evaluates the ordered forms
in one SCI turn fork, installs each `result/eN` binding for the next form, and
settles the batch through the existing transaction functions
(`src/seon/cluster/loop.clj:1645-1855`). No new evaluator or run state machine
is present.

Two facts remain to prove or repair before this becomes a general submission
surface:

1. **Unarmed delivery is unproved, not a confirmed defect.** The current test
   arms its target before submission (`test/seon/cluster/agent_test.clj:343-425`).
   When no armed mailbox exists, `submit-source!` offers to the existing armer
   channel. The armer derives unarmed agents from facts, calls the single
   `arm!` owner, and `arm!` primes the new sliding-one mailbox
   (`src/seon/cluster/agent.clj:709-769,918-934`). An offer is deliberately
   unacknowledged, but that alone is not a defect: these are level-triggered
   “look” signals and coalescing is free. A focused positive test still needs
   to submit to an unarmed existing agent, observe that it becomes armed, and
   await the returned run's terminal stored results under the existing
   backstop. Do not add a second caller that arms agents directly; the armer is
   the existing authority.
2. **Namespace reassignment exposes a real unchecked race in the paused
   composition.** Reassignment is explicitly supported as an ordinary
   cardinality-one transaction
   (`test/seon/cluster/agent_namespace_test.clj:31-49`). `submit-source!` reads
   the assigned namespace before parsing and hands it to `system-run-tx`
   (`src/seon/cluster/agent.clj:564-613`). The transaction functions correctly
   re-decide open-run custody: a concurrent run causes `open-call` to refuse
   the entire transaction. They do not validate that the requested starting
   namespace still equals the agent's current namespace. `plan-call` derives
   the current assignment but intentionally prefers a supplied starting
   namespace (`src/seon/cluster/run.clj:661-675`). A concurrent reassignment can
   therefore commit source parsed for the former namespace. The minimum repair
   is a compare-at-authority check inside the same transaction, against the
   namespace used for parsing. A post-commit query or silent switch would not
   repair the attribution.

The no-provider terminal path is structurally complete but its focused proof
should remain explicit. The paused test already expects two stored evaluation
results and no `:seon.ai.attempt` rows. It should also assert `closed-at` and,
for a queued message or schedule trigger, that the source run neither claims
nor answers that trigger. A held planned run legitimately runs first; after it
closes, the ordinary self-wake derives the still-unanswered trigger. These are
regressions over existing mechanisms, not a reason to add a source queue or a
second scheduling path.

This audit supports resuming the slice. A controlled MCP experiment against an
already armed agent whose namespace is held stable can use the paused seam and
inspect the returned run through the existing transcript renderers. General
adoption waits on the namespace authority check and the unarmed positive proof.

## Integration proof — 2026-09-06

Commits `671657d51` and `423d46b1c` complete the one-path integration. The
first adds `submit-source!`, starts evaluation rows atomically with the
system-authored run, and compares the parse namespace with the agent's current
assignment inside Datahike's transaction function. The second makes model
replies and submitted source share `planned-sources` and `run/plan-digest`.

The focused regression command was:

```bash
clojure -M:dev:test -e "(require 'clojure.test 'seon.cluster.run-test 'seon.cluster.agent-test) (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)] (clojure.test/test-vars [#'seon.cluster.run-test/system-run-refuses-a-concurrent-namespace-reassignment #'seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run]) (let [c @clojure.test/*report-counters*] (prn {:focused-test-counters c}) (shutdown-agents) (System/exit (if (zero? (+ (:fail c) (:error c))) 0 1))))"
```

It exited zero with `{:test 2, :pass 83, :fail 0, :error 0}`. The source test
begins with an unarmed agent, lets the existing armer own delivery, evaluates
through `seon.sci.eval/evaluate`, observes the closed run, and finds no provider
attempt. The namespace test puts reassignment before `system-run-tx` in one
transaction; `plan-call` reads that transaction's current database value,
refuses `:starting-namespace-changed`, and rolls the entire transaction back.

The live proof used a disposable operator root and ordinary boot:

```bash
mkdir -p tmp/source-submission-live-2026-09-06
bin/seon --root tmp/source-submission-live-2026-09-06 init
bin/seon --root tmp/source-submission-live-2026-09-06 start source-proof
```

Through that cluster's JVM REPL, the proof selected its running instance and
called `seon.cluster.agent/submit-source!` for `root` with this exact text:

```clojure
; Read one value.
(+ 1 1)
; Consume the preceding result.
(identity result/e0)
```

The call returned run id
`source:51132364-af2c-4ae6-880d-91794c95fd89`. A database query by that id
returned `closed-at` `2026-09-06T20:49:03Z`, the two exact commented sources at
ordinals 0 and 1, and these stored evaluation results:

```clojure
[{:ordinal 0,
  :result "#:seon.print{:face :seon.print/number, :value 2}"}
 {:ordinal 1,
  :result "#:seon.print{:face :seon.print/number, :value 2}"}]
```

The same query returned `:provider-attempts []`. The stored second value proves
that the ordinary turn installed `result/e0` before evaluating the second form;
the closed run and empty attempt set prove that submitted system source settled
without entering the provider path. An initial inspection requested the
nonexistent attribute `:seon.cluster.run/disposition` and returned the expected
typed `:seon.db/invalid-read`; the corrected pull above uses only declared run
attributes.
