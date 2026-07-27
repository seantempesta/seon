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

## Triage 2026-07-27

- **OPEN-CURRENT.** `bb.edn:1` still places `src-old` on plain Babashka's
  ambient classpath, whereas `deps.edn:1-5,75-84` keeps the quarry behind the
  explicit old-system alias.

## Bounded fix-lane findings 2026-07-27

Inventory covered the root `bb.edn` task, first-party `bb` launchers under
`bin/`, `.codex/hooks.json`, and Babashka calls in `script/`. Maintained
operator helpers load from `script/`; the edit hook is a standalone script;
the operator runner and operator tests are quarry consumers.

The root-only candidate removed `src-old/` from the default paths and gave
`operator-test` task-local `src-old/` and `test-old/` paths. Its focused proof
passed:

- `printf '{}\n' | bin/seon-hook` returns `{"continue":true}`.
- `bb operator-test seon.dev.issues-test` passes 2 tests and 6 assertions.
- `bb -e "(require 'seon.time)"` exits 1 because the quarry-only namespace is
  absent from the default classpath.

However, `bin/seon --help` then exits 1: `bin/seon` directly invokes
`bb --config bb.edn --deps-root ... -m seon.dev.cli`, whose script dependency
chain still requires quarry-only `seon.config.resolve` and other old operator
namespaces. The direct operator-test subprocess commands in
`script/seon/dev/cli.clj` and `script/seon/dev/changed_test.clj` likewise bypass
the task-local opt-in. A correct fix therefore requires changing old-facing
launchers to select one explicit old Babashka entry, outside this lane's
`bb.edn`-and-issue ownership. The breaking root-only candidate was reverted.

There is also a separate proof seam: the no-selector operator runner discovers
`test/seon/dev` and reports zero tests after operator tests moved to
`test-old/`. That split work is already specified in
`docs/prds/sci-execution-runtime/research/src-split-audit-2026-07-26.md`.
