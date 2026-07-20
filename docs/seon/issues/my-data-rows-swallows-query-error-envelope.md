---
type: issue
status: open
severity: friction
tags: [issue, agent, database]
---

# my.data/rows swallows the query error envelope

## Problem

`src/my/data.cljs:51-57` never checks the query result for the error
envelope: `(vec <error-map>)` turns a failure into a vector of MapEntries
returned as an `ok? true` result, so an agent sees garbage rows instead of
the error value.

## Evidence

Found by the envelope conformance audit
([[../../prds/source-cleanup/research/envelope-symbol-conformance-2026-07-20]]
§A). The other `my.*` surfaces check the envelope before shaping results.

## Acceptance

A failing query returns the standard error value; a regression test covers
the failure path.
