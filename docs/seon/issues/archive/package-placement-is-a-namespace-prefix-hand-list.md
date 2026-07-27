---
type: issue
status: superseded
severity: friction
tags: [issue, architecture, runtime]
---

# Package placement is a namespace-prefix hand list

## Problem

`plan-execution` decides which tier may serve a package-backed call by
string-matching the terminal symbol's namespace prefix. Two literal
prefixes (`seon.packages.js.` → `:bun`, `seon.packages.jvm.` → `:jvm`)
are the entire rule. This is the name-based classification the repo law
forbids ("Classification rules are COMPUTED, never name-based"): the
placement fact is available from the artifact/package inventory
(`:seon.execution.inventory/exports-by-tier`, already threaded into the
same function), so a package's tier should be derived from what each
tier actually exports, not from how an agent happened to name a
namespace.

The rule also fails open in the wrong direction for the on-demand
package work: a package required at runtime into a per-cluster
`data/clusters/<name>/packages/` tree does not necessarily carry either
prefix, so its calls fall through to the generic `(not= :pure effect)`
branch and are placed by binding availability alone.

## Evidence

`src/seon/program/plan.cljc:194-197`:

```clojure
package-tier (cond
               (str/starts-with? target "seon.packages.js.") :bun
               (str/starts-with? target "seon.packages.jvm.") :jvm
               :else nil)

```

`target` is `(::edge/terminal-symbol terminal)`, a string. The same
function already receives `:seon.execution/tier-inventories`, whose
`:seon.execution.inventory/exports-by-tier` is a
`[:map-of :seon.execution/tier [:set :string]]` — the computed fact this
prefix test is standing in for.

## Owner

`src/seon/program/plan.cljc` (`terminal-tiers`) plus whatever publishes
`:seon.execution.inventory/exports-by-tier` for a cluster's installed
packages.

## Acceptance

- `terminal-tiers` derives a terminal's serving tiers only from inventory
  facts; no literal namespace-prefix set remains in the function.
- A package installed into a cluster under a namespace carrying neither
  legacy prefix plans onto exactly the tier(s) whose inventory exports it.
- A package exported by no tier plans as `:missing-capability-binding`
  with steering, not as an unrestricted pure terminal.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
