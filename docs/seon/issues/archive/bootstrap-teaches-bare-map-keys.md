---
type: issue
status: superseded
severity: friction
tags: [issue, agent, schema]
---

# Teach namespaced data in the bootstrap contract example

## Problem

The canonical bootstrap teaches agents to declare and process maps with bare
`:label` and `:amount` keys, directly contradicting the repository's fully
namespaced data rule.

## Evidence

- `resources/seon/bootstrap.edn:19-22` presents the sequence as instruction in
  honest durable contracts.
- `resources/seon/bootstrap.edn:45-51` defines the first `largest` schema and
  implementation with bare keys.
- `resources/seon/bootstrap.edn:53-63` corrects closedness but preserves the
  same bare keys in the schema, implementation, and example input.

## Owner

The one shipped bootstrap plan in `resources/seon/bootstrap.edn`.

## Acceptance

The example uses one coherent namespaced row shape and demonstrates shared
schema reuse rather than teaching an inline bare-key record. A bootstrap
regression parses its source and refuses bare map keys in agent examples.

## Closure — 2026-08-13

`resources/seon/bootstrap.edn` is deleted; openings are generated from live facts (ruling 24), so no authored teaching rows exist to carry bare keys.
