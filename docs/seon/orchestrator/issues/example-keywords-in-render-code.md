---
type: issue
status: open
tags: [issue, schema, jvm-track, paused]
---
# render/code.clj uses invented :seon.foo/* keywords as live values

## Problem

`src/seon/render/code.clj` uses `:seon.foo/x` and `:seon.foo/y` as live runtime values in `::rc/available-keys` (line ~19) and `render-fn-card` (line ~81). These are example/demo data but they're real keyword values in running code, not just docstrings.

`seon.foo` is not a real namespace. These should either use keywords from a real namespace or be removed if the example code is dead.

## File Refs

- `src/seon/render/code.clj` — lines ~19, ~81

## Severity

cleanup

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
