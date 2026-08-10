---
type: issue
status: open
severity: blocker
tags: [issue, agent, bootstrap, error, context]
---

# Stop the bootstrap plan's deliberate failures from interrupting the run

## Problem

The shipped bootstrap teaches by failing twice on purpose. Both failures are
classified as CORE FAULTS, committed as durable error facts, escalated to
root as messages, and — per the message text the fault committer writes —
INTERRUPT the bootstrap run. The onboarding sequence meant to teach an agent
its contract rules is the thing that stops its first turn.

Measured on the live default cluster (pid 31570) on 2026-08-10: the three
core-namespace agents created that day (`seon.db`, `seon.fn`,
`seon.render`) have **zero context captures and zero AI attempts**. No
prompt was ever built for any of them.

Root pays a second cost forever: its transcript retains both deliberate
failures, so every future turn re-reads its own scripted onboarding
mistakes (~500 estimated tokens).

## Evidence

`resources/seon/bootstrap.edn:49-51` and `:67` ship the two failing forms:

```clojure
"(defn largest \"The row with the largest :amount.\"
   {:malli/schema [:=> [:cat [:sequential :any]] …]} …)"
"(largest)"
```

Live `seon.db/q` over `:seon.error/run`, default cluster, door mode:

```text
["bootstrap:seon.db"     :user-input  "seon.db/largest uses :any in an agent-authored contract. …"]
["bootstrap:seon.db"     :seon.instrument/contract-violated  "Wrong number of args (0) passed to: seon.db/largest"]
["bootstrap:seon.fn"     … same pair …]
["bootstrap:seon.render" … same pair …]
["bootstrap:root"        :user-input …]
["bootstrap:root"        :seon.sci.eval/evaluation-failed  "No such namespace: my.agents.root"]
```

The escalation, verbatim from root's captured `:seon.render/ai` context:

> Core fault `:seon.instrument/contract-violated` reached 3 occurrences in
> process 31570-1786191855600 (notification limit 3). Latest: Wrong number
> of args (0) passed to: seon.fn/largest **It interrupted run
> bootstrap:seon.fn.** Further occurrences remain in seon.problems but
> will not message you.

Cluster problem counts carry the residue: `:seon.problems/error-signatures 9`,
`:seon.problems/errored-receipts 9`, `:seon.problems/failed-runs 1`.

Capture/attempt census, same cluster:

```clojure
{:captures [["root" 8]], :attempts [["root" 8]]}
```

The two failures are also inconsistent with each other: root's wrong-arity
call reports `No such namespace: my.agents.root` while the other three
report `Wrong number of args (0)` — see
[a-wrong-arity-call-reports-a-missing-namespace](a-wrong-arity-call-reports-a-missing-namespace.md).

Full measurement:
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
finding 3.

## Owner

`resources/seon/bootstrap.edn` owns the one shipped plan; the fault
classification seam is the run loop's core-fault path
(`src/seon/cluster/loop.clj`) and the `:seon.config/on-core-error` dial.

## Implementation state — 2026-08-10

The design gate resolved locally at
`src/seon/cluster/loop.clj`'s `evaluation-terminal-data`. That function
settled the agent's flat evaluation error in its receipt and also passed the
same value through `error-tx`, duplicating the first error class into durable
core-fault facts. The duplicate recording is deleted; phase failures,
delivery refusals, model failures, and terminal transaction refusals retain
their existing durable error paths.

The real-tower regression in `test/seon/cluster/boot_test.clj` failed before
the repair with exactly two error facts bound to `bootstrap:root`. After the
repair the same fresh-cluster test renders the refusal before the corrected
definition, settles all 13 bootstrap receipts, closes the run, and finds zero
error facts bound to it. The direct regression passed, and
`bin/test --changed src/seon/cluster/loop.clj --changed
test/seon/cluster/boot_test.clj` passed 141 tests / 758 assertions / zero
failures / zero errors.

The same falsifier refuted this issue's interruption attribution: before the
repair the bootstrap also settled all 13 receipts and closed. The recurrence
notice was the lie. `seon.error/commit-tx` correctly treats throwable presence
as the interrupted-run condition for direct attribution, but `ai-prose`'s
`:recurring` arm calls every run-attributed recurrence a “Core fault” and says
“It interrupted run …” without checking that condition. Removing agent
evaluation errors from the fault family prevents this teaching path from
reaching that false notice. The three new agents' zero context captures are
therefore not explained by an interrupted bootstrap; the audit's separately
measured prompt-budget failure remains their proven blocker.

The operator live proof remains pending because the shared-tree publication
checkpoint was invalidated by foreign in-flight source changes. A first
`bin/seon init bt-scratch --force` completed branch publication but hit the
operator's 30-second prepl-response silence backstop. The retry failed at
`seon.cluster.source/publish!` contract validation: the generated
`:seon.fn.manifest/artifacts` contained a nil row with
`:seon.ns/name nil`. `bt-scratch` was stopped before the refork and was never
restarted. Do not archive this issue until a coherent current-source
publication permits the required live start/query/stop proof.

## Acceptance

A bootstrap form whose refusal is the lesson never becomes a core fault,
never interrupts its run, and never pages root. Either the plan declares
those forms as expected refusals whose results settle as ordinary values, or
the two failing forms are removed and the rules are stated in the `(help)`
text, which already states them correctly. A newly created agent reaches its
first recorded `:seon.context.capture` with no error facts bound to its
bootstrap run — one regression asserts exactly that for a freshly created
agent.
