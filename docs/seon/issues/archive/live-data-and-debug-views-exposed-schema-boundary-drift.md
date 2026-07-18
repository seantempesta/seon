---
type: issue
status: resolved
severity: blocker
tags: [issue, web, database, schema]
---

# Restore the maintained data and debug views

## Problem

Real-browser verification found two source-boundary mismatches hidden by the
existing unit gates:

- `/data` passed `:seon.db/index-limit` to `seon.db/index-page`, whose public
  request and JVM Datahike index-page protocol both name the field
  `:seon.db/limit`.
- `/agent/root/debug` pulled `:seon.ai/agent-thinking`, but the six established
  per-agent AI configuration attributes were standalone Malli schemas rather
  than members of a map marked `:seon.db/entity`. Database initialization
  therefore had no declaration telling it to install those attributes.
- After that delta installed, the same live debug path exposed
  `:seon.agent.ctx/cache-breakpoint`. The existing agent entity schema omitted
  its context configuration attributes even though prompt acquisition and
  transcript rendering consume them from the agent entity.
- The repaired index request returned rows, but the view read
  `:seon.db/datoms` instead of the producer's established
  `:datahike.index-page/datoms` field.
- Once the schema and result fields matched, prompt acquisition reached its
  resource limits and exposed a mistaken assumption: two Datahike pull members
  used `max-results 1` as though it counted top-level entities. Datahike charges
  pull work per retained result-tree node, so ordinary configuration maps
  exceeded that bound.
- The now-complete prompt exposed the same declaration gap in the namespace
  block's established per-agent and per-block display fields. Its pull selector
  names `full-source`, `with-tests`, `current-full?`, and `current-tests?`, so
  those attributes must also be installed before an absent value is queried.
- Namespace acquisition then exposed the superseded pull request vocabulary
  still present across agent, lifecycle, message, run, startup, and web
  consumers. Those maps used `selector`/`eid`/`eids`; the one public
  `seon.db` API and its Datomic-shaped call sites use
  `pull-pattern`/`ref`/`refs`.
- With the public pull map restored, the namespace selector reached its stored
  require-edge components and showed they were not declared as stored entity
  data. `:seon.ns` now declares its component ref and the existing analyzer
  require-edge map is marked as the stored component entity it already is.
- The empty orphaned-agent queries then exposed a protocol constructor that
  omitted `:seon.db.protocol/arguments` for `[]`. The JVM query-member contract
  requires an arguments vector even when it is empty; both shared context query
  constructors now send that exact value.

## Acceptance

- The database view uses the public `seon.db/index-page` request fields and
  renders a bounded AEVT page.
- The existing `seon.ai/agent-config-pull-pattern` and the Datahike-installed
  agent configuration attributes are the same set.
- The agent entity declares the established context configuration attributes
  consumed by prompt and transcript rendering.
- The namespace block's agent/block configuration selector contains only
  Datahike-installed attributes.
- No maintained source or test uses the removed pull request keys.
- Namespace and require-edge entity schemas install every attribute used by
  the structural require-edge pull selector.
- Empty query members carry `:seon.db.protocol/arguments []`, and an empty
  orphaned-agent result omits the block instead of rendering a false failure.
- The data view consumes Datahike's `:datahike.index-page/datoms` result
  directly.
- Prompt acquisition uses bounded but realistic Datahike result-tree and
  response-weight limits; ordinary root prompt rendering completes.
- Restarting an existing database installs the missing schema delta without
  retransacting converged program or initial data.
- `/data` and `/agent/root/debug` render through their Datastar feeds without a
  visible error or browser console error.

## Evidence

- The in-app browser rendered `:malli.core/invalid-input` on `/data` and
  Datahike's `Bad entity attribute :seon.ai/agent-thinking` on the debug view.
- A live pod probe showed the Malli schema registered while the installed
  Datahike schema did not contain the attribute.
- Focused regressions currently pass for the AI entity declaration and the
  database view's index-page request.

## Resolution

Resolved across commits `7faafe9b` through `6eb7649c`. The public database
requests, installed entity schemas, namespace component schema, prompt pull
budgets, and Datahike result fields now agree with their maintained producers
and consumers. Child readiness now follows committed-program publication, and
instrumentation wraps every live CLJS Var in the child's compiled artifact.
The live pass also corrected the canvas block's render-result contract, the
compiled canvas renderer's selected-function call, and the instrumentation
coverage input to accept Datahike's native set of query tuples.

Focused proof includes 22 execution tests/87 assertions, 10 instrumentation
tests/110 assertions, 8 canvas/warnings tests/39 assertions, and 12 execution
runtime tests/61 assertions. The complete CLJS gate passes 1,078 tests and
4,789 assertions. After a converged supervised restart, `/data` renders 50
AEVT rows and `/agent/root/debug` renders a 108,060-character prompt with the
system boundary and root system canvas present, no instrumentation gap, no
failed render, and no Malli error. A bounded server-side client received the
root Datastar feed as HTTP 200, `text/event-stream`, gzip-encoded, and decoded
9,068 bytes before its intentional three-second timeout.
