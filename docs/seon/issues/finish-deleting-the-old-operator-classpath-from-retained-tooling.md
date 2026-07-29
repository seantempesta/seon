---
type: issue
status: open
severity: blocker
tags: [issue, tooling, operator, testing]
---

# Finish deleting the old operator classpath from retained tooling

## Problem

`bin/seon` no longer calls `seon.dev.cli`, but retained development tooling
still relies on the ambient quarry classpath that kept the old operator
loadable. Removing `src-old` from `bb.edn` now would disable the edit hook's
canonical linters, while the changed-test operator boundary already invokes an
unavailable old Babashka runner.

This is the remaining blocker to deleting `seon.dev.cli`, its old tests and
release packaging, and the ambient old Babashka path as one wave.

## Evidence

- `bb.edn:1` still declares `["script" "src" "src-old" "resources" "test"]`.
- Plain `bb -e "(require 'seon.time)"` still prints `:quarry-loaded`.
- `bin/seon-hook:246-299` dynamically loads quarry
  `seon.dev.markdown` and `seon.dev.docstring`.
- Real `PostToolUse` hook events for one Markdown file and
  `script/seon/fresh_operator.clj` returned `{"continue":true}` only after
  Babashka printed a DataStar dependency-description conflict; the hook log
  recorded `MARKDOWN_LINT_ERROR` and `DOCSTRING_LINT_ERROR`.
- Changed-test generation 1175 selected
  `seon.dev.fresh-operator-test` and `seon.dev.mcp-bridge-test`, then failed.
  `script/seon/dev/changed_test.clj:483-486` launched
  `bb -m seon.dev.test-runner`; that namespace exists only at
  `test-old/seon/dev/test_runner.clj`, and the selected fresh tests require the
  JVM/Datahike classpath.
- The correct direct gate passed: `bin/test seon.dev.fresh-operator-test
  seon.dev.mcp-bridge-test` ran 8 tests and 34 assertions with zero failures.
- Outside documentation, `seon.dev.cli` now has only three owners: its
  definition, `script/seon/dev/release.clj:984` packaging it as an uberjar
  main, and `test-old/seon/dev/cli_test.clj` requiring it.
- `bin/test-writer:17-44` and `bin/test-cljs:273-382` still use the ambient
  old artifact/config tooling. They are quarry gates; `bin/test` is the fresh
  correctness gate.

## Owner

The retained development-tooling boundary:

- move the Markdown and docstring linters to a maintained fresh/tooling
  classpath and make their hook load proof loud;
- make `seon.dev.changed-test` launch selected operator namespaces through
  `bin/test`;
- delete the old CLI test and release-packaging path with `seon.dev.cli`; and
- retire the writer/CLJS quarry launchers or give any deliberately retained
  quarry command one explicit old classpath.

## Acceptance

- Real Markdown and Clojure `PostToolUse` hook events execute their linters
  without a caught load error.
- Changed-test selection of both fresh operator namespaces passes through the
  JVM `bin/test` gate.
- `rg` outside documentation finds no `seon.dev.cli` definition, package main,
  test require, or launcher.
- Plain Babashka has no `src-old` path, and requiring `seon.time` fails.
- Any deliberately retained quarry gate names and supplies its old classpath
  explicitly; fresh hooks, operator commands, tests, and ad hoc Babashka
  probes cannot resolve quarry namespaces.
