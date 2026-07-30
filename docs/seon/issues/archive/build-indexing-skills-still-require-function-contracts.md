---
type: issue
status: resolved
severity: blocker
tags: [issue, program-graph, documentation]
---

# Build indexing skills still require function contracts

## Resolution

The `data-oriented-clojure`, `datahike`, and `data-modeling` skills now state
the two admission domains independently. Static build indexing records every
first-party function and test definition, including private and uncontracted
helpers. Runtime publication remains selective and admits agent-authored
functions only with a complete contract. Each skill cites the current
clj-kondo builder and runtime publication owner.
