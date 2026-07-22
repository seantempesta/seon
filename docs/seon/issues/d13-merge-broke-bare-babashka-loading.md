---
type: issue
status: open
tags: [issue, agent, cljs]
---

# D13 repair merge broke the bare-babashka loadability the candidates half had

## Observed (2026-07-22)

`bin/oracle-server` failed to load after D13 (05a13dd4):
`seon.diffusion.grammar` requires `seon.repl.parse.repair` (the merged
namespace), which requires `parinferish.core` and `seon.schema` (malli).
The pre-merge `seon.repair.candidates` half was deliberately dependency-free
so babashka could load it straight from source — grammar.cljc's docstring
and `seon.repl.cljs`'s comment ("must stay loadable by bare babashka
(bin/oracle-server) — no malli") still document that now-broken property.
No gate covers the bb tooling surface, so nothing caught it.

## Repaired in-repo (same day)

`bb.edn` now carries `parinferish/parinferish 0.8.0` (malli was already
there), so any bb invocation that resolves `bb.edn` loads fine —
`bin/oracle-server`'s requires and `bin/test-parser` (47/379) both green.

## Residual

A DEPLOYED bare oracle-server (the "cwd-independent for the deployed
image" comment) without `bb.edn` resolution would still break. Either
(a) accept bb.edn as the loading contract and update the two stale
"no malli / pure mechanics" docstrings, or (b) if bare-source loading is
a real deployment requirement, the merged repair namespace needs its
candidate-ranking half to stay dependency-light (reopens the D13
one-namespace ruling — owner call). Diffusion is dev-only (D12), so (a)
is likely sufficient.

## Owner and when

Diffusion tooling surface; fold into W9's parinferish audit note (W3c2
pinned parinferish into :host — same dependency, same audit). Decide
(a)/(b) there; until then bb.edn is the contract.
