---
type: issue
status: resolved
tags:
  - issue
  - runtime
---

# Portable namespace metadata parser dropped reader conditionals

The namespace-source boundary parsed source with the EDN reader. Portable
`.cljc` namespaces containing `#?` reader conditionals therefore fell through
the fail-soft path, losing their namespace documentation and require edges.

Resolved on 2026-07-23 by using the maintained tools reader with reader
conditionals enabled and the compiling platform selected. A focused regression
proves that CLJS selects the CLJS require branch while retaining the shared
namespace docstring.
