---
type: reference
status: active
tags: [evaluation, testing]
---

# Evaluation goals

Each child directory defines one Seon-authored evaluation goal: its objective
message, ordinary `clojure.test` namespace, optional test.check properties,
and optional advisory-judge criterion. Goal discovery walks this directory;
there is no maintained goal registry.

These namespaces grade agent episodes through Inspect AI. They are not part of
the repository's `bin/test` correctness suite.
