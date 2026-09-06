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
