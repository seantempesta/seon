---
type: issue
status: open
severity: friction
tags: [issue, render, performance, wave/namespace-page-performance, wave/render-acquisition-performance]
---

# The root page's first paint after a reset exceeds forty seconds

## Observation — 2026-09-20, default pid 24777 right after `bin/seon reset --force`

`GET /` did not answer within a 30 s probe, then a 10 s probe; `GET /data`
answered 200 in 0.45 s at the same moment. A virtual-thread dump
(`jcmd Thread.dump_to_file`) showed the page thread RUNNABLE inside
`seon.render.walk/neighborhood` → `seon.schema.form/schema-properties` under
the contract wrappers (`seon.instrument/arm-var!` at `instrument.clj:885`,
hash-map creation per call). Once warm, `GET /` answers in 0.27 s. The first
derivation after a fresh store therefore costs >40 s of schema-property
lookups, the "fast by default — slow is a bug" class: a per-item
`schema-properties` under arming, re-resolved per call instead of read from
the carried projection (bridge PRD step 1: compile once, carry; AGENTS.md
§2.1 fetch-at-call-time).

## Fix

Measure the cold path once bridge step 1 lands (retained compiled schemas
and Malli caches); if the walk still resolves properties per item, the walk
takes them from the carried projection's compiled schema once per render.
Owner: the step-1 lane's acceptance measurement, then a namespace-page
performance slice.
