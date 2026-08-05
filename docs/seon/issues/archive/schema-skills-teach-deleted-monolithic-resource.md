---
type: issue
status: resolved
severity: friction
tags: [issue, skills, schema, documentation]
---

# Teach the live split schema registry in schema skills

## Problem

Four curated schema and database skills directed agents to the deleted
monolithic `resources/seon/schema.edn` instead of the merged declarations under
`resources/seon/schemas/`.

## Resolution

Commit `0130b305c` updates `data-modeling`, `data-oriented-clojure`, `datahike`,
and `clojure-testing` to the split registry, current runtime owners, and the
current/target program-state boundary. It also updates the Datahike pin,
identity-only admission, open-map and authored-contract rules, F11 test edges,
and the current output and REPL faces. Commit `f44e025ac` restores canonical
skill frontmatter.

An independent post-change pass read all eight edited skill/reference files,
verified their cited current-source claims, and ran the skill validator over
all six skill packages successfully. No blocking inaccurate or stale claim
remained in the changed packages.
