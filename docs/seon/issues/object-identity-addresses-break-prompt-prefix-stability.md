---
type: issue
status: open
severity: blocker
tags: [issue, render, context, ai]
---

# Object identity addresses in agent context break prompt-prefix stability

## Problem

`seon.print` emits a host object as `#object[<class> <address> <rep>]`
(`src/seon/print.cljc:515`, from the `:seon.print/object` face's `::address`).
The address is a JVM identity hash: it differs between processes and between
two structurally identical evaluations. Every agent's very first bootstrap form
after `(help)` is `(in-ns 'my.agents.<id>)`, whose value is a
`sci.lang.Namespace` object — so **every agent's context begins with a token
that changes on every boot**.

DeepSeek's context caching keys on an exact byte prefix. A varying token this
early in the prompt means the cacheable prefix ends almost immediately, which is
the mechanism the append-only history ordering exists to protect. The address
carries no information an agent can use: two namespaces with the same name are
the same namespace, and the rep already names it.

## Evidence

Both observed in the minimum-context ablation FLOOR drive
(`tmp/ablation/drive-roots/floor-01`, observer account
`tmp/ablation/observer/floor.edn`):

- the rendered agent context (`tmp/ablation/observer/floor-prompt-0.txt`)
  contains
  `#object[sci.lang.Namespace 0x454fde80 "my.agents.w1-history-proof-5"]`;
- the same cluster's own root-agent bootstrap receipt for `(in-ns 'my.agents.root)`
  recorded `:seon.print/address "0x3075cf37"` — a different address in the same
  process for the same operation shape.

Because that drive injected a frozen prompt string, the experiment itself could
not observe the resulting cache behavior; the hazard is to production prompts.

## Acceptance

- A namespace value renders by name in both projections, with no identity
  address, so two boots of the same cluster produce byte-identical prefixes.
- The `:seon.print/object` face stops emitting `::address` into any
  agent-facing or page projection (keep it in forensic capture if a diagnostic
  needs it, where prefix stability does not apply).
- One regression proves the class dead: rendering the same value twice in two
  processes yields identical bytes for every face reachable from
  `:seon.render/ai`.

## Owner

Render/print owner, alongside the
[self-generating-context PRD](../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md)
prefix-stability work.
