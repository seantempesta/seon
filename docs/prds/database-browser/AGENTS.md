---
type: orchestrator
status: active
tags: [orchestrator, prd, database, web]
---

# Database browser — working context

This PRD owns the bounded `/data` experience over the one Datahike database.
Read [[roadmap]], architecture UI/data-model/observability, the `data-oriented-
clojure`, `datahike`, `datastar-web-ui`, and `browser-automation` skills, and the
closest source authorities before research or code.

Begin with exact selected dependency versions and SHAs for Datahike indexes and
history, the Seon database protocol/replica, Hiccup/Datastar, and any cursor or
count primitive. Read their actual source in `reference-code/` and probe the
smallest index/history assumption through the live default REPL.

Use EAVT/AEVT/AVET cursors, one reactive unit/feed mechanism, stable entity and
transaction coordinates, and pay-for-open detail. Do not add offset Datalog,
whole-database scans, a browser-side writer, a second feed, stored projections,
or a database-browser-specific cache.

Research belongs in `research/`; current gaps, order, and proof belong in
[[roadmap]]. The database coordinate and reactive unit contracts must settle
before implementation.
