---
type: issue
status: open
severity: friction
tags: [issue, program-graph, analyzer, rows]
---

# A computed def in seon.ai.tokens has no program row

## Problem

`seon.ai.tokens/shipped-calibration` (`src/seon/ai/tokens.cljc:62-64`) is a
docstring-carrying `def` whose initializer is a call:
`(prior-calibration shipped-chars-per-token)`. `seon.fn/function-definition?`
(`src/seon/fn.clj:260-272`) says computed defs are program declarations. Yet the fresh
default has no `:seon.fn/sym` row for it. Its 10 siblings in the same file, including
the literal defs `shipped-chars-per-token` and `shipped-prior-sample-count`, all have
rows. Callers (`src/seon/render/transcript.clj:1564`, `src/seon/cluster/prompt.clj:134`)
therefore name a Var the program graph does not hold. Their call edges dangle, deletion
obligations cannot see it, and `seon.ai.tokens/estimate`'s docstring link
`[[shipped-calibration]]` cannot resolve.

## Evidence

- Fresh default (pid 39484, 2026-09-25): `(datahike.api/datoms db :avet :seon.fn/sym
  'seon.ai.tokens/shipped-calibration)` is empty. The five siblings checked are present.
- On the previous store, 56 first-party callee names had no row (references-as-refs
  design §0), so this may be one member of a larger class. Not yet classified.
- An attempt to analyze the one file through `seon.fn.analyzer/analyze` with a private
  cache refused twice with "Source changed during analysis; clj-kondo cache entries
  changed again." The private cache had written `v1/cljc/user.transit.json`. Not
  diagnosed; the cache was deleted.

## Next step

Analyze `src/seon/ai/tokens.cljc` with the production cache and check whether
clj-kondo's var definition for `shipped-calibration` carries `:defined-by
clojure.core/def` under `.cljc`. Then classify the 56 names by cause.

## Owner

`seon.fn` analyzer and row producer (`var-row`).
