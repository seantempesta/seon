---
type: issue
status: open
severity: friction
tags: [issue, tooling, architecture, testing]
---

# Keep the old source tree off Babashka's default classpath

## Problem

The Clojure CLI default project isolates fresh `src/`, but `bb.edn` adds
`src-old/` to every Babashka invocation. Hooks, operator tasks, and ad hoc
Babashka probes can therefore resolve quarry namespaces without selecting an
old-system mode.

This makes the split depend on which launcher evaluated the same `require`.

## Evidence

- `bb.edn:1` declares `["script" "src" "src-old" "test"]` unconditionally.
- `deps.edn:1-5` declares only `["src"]` by default and places `src-old` behind
  the explicit `:writer` replacement paths.
- `bb -e "(require 'seon.time) (println :loaded)"` printed `:loaded`, resolving
  `src-old/seon/time.cljc`.
- `clojure -e "(require 'seon.time)"` failed because the namespace is absent
  from the fresh default classpath.
- No fresh `src/` or `test/` namespace directly requires `src-old`; the leak is
  solely project configuration.

This violates the 2026-07-27 ruling that the fresh tree is the default project
and the old system is disabled unless an explicitly old-facing entry point is
chosen.

## Owner

`bb.edn` plus the old operator/hook launchers that genuinely need quarry
source. Give those entry points an explicit old classpath instead of making it
ambient for every Babashka task.

## Acceptance

- Plain `bb` resolves fresh `src/`, fresh `test/`, and maintained tooling only.
- Requiring a quarry-only namespace fails under plain `bb` and plain
  `clojure`.
- Explicit old operator/test commands still resolve `src-old` through one
  named old-system entry.
- Hook and operator tests prove their intended classpath rather than passing
  because quarry source is ambient.
