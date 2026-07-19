---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, cljs]
---

# Namespace reassignment was shadowed by eval history

## Problem

The first live `set-namespace!` transaction correctly changed
`:seon.agent/namespace`, but the agent's next turn still started in its old
namespace. Prompt acquisition selected the latest successful
`:seon.eval/ns` whenever any eval history existed, treating the assigned
namespace only as a first-turn fallback. A warm execution child therefore
could not observe a later database assignment.

## Resolution

`seon.agent.home/current-ns` is the one selection rule. It compares the
transaction carrying `:seon.agent/namespace` with the transaction carrying the
latest successful eval. A newer assignment selects the next turn; after that
turn records a successful eval, ordinary `in-ns` movement again wins. The
namespace and transcript prompt acquisitions use the same rule. No current
namespace flag, eval-history rewrite, or process-local invalidation was added.

## Acceptance

- Reassigning an agent changes only its namespace ref and creates an absent
  namespace declaration in the same transaction.
- The next turn starts in the newly assigned namespace, including with a warm
  execution child.
- Later successful evals preserve ordinary namespace movement.
- The agent ID, messages, runs, turns, plans, and program entities remain
  attached to the same agent.
