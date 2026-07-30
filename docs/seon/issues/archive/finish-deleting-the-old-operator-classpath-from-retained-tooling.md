---
type: issue
status: fixed
severity: blocker
tags: [issue, tooling, operator, testing]
---

# Finish deleting the old operator classpath from retained tooling

## Resolution

The retained Babashka classpath contains only `script`, fresh `src`,
`resources`, and fresh `test`. The two legitimate edit-hook linters now live
under `script/seon/dev`; they no longer load the application schema registry or
the old namespace-rendering policy merely to perform syntax checks.

Changed-test operator selections now invoke `bin/test`, the same JVM gate as
all fresh tests. The unavailable `seon.dev.test-runner` path is gone. The old
`seon.dev.cli`, its test, the release builders that packaged it, and the
disabled writer/CLJS quarry launchers and release tests were deleted together.
The retained release namespace only reads and verifies old release manifests
for old consumers that still mention them.

## Evidence

- `bb --config bb.edn --deps-root . -e "(require 'seon.dev.markdown
  'seon.dev.docstring 'seon.dev.changed-test)"` succeeded.
- The same plain Babashka command requiring `seon.time` failed with `Could not
  locate seon/time`, proving the quarry is absent.
- Real Markdown and Clojure `PostToolUse` events returned `continue: true`
  without `MARKDOWN_LINT_ERROR`, `DOCSTRING_LINT_ERROR`, or a caught load
  failure.
- `seon.dev.changed-test-test` proves from the fresh JVM gate that operator
  selections launch `/checkout/bin/test` with the selected namespaces.
- `rg` outside documentation finds no `seon.dev.cli` definition, packaged
  main, test require, or launcher.

## Acceptance

- [x] Real Markdown and Clojure hook events load and execute their linters.
- [x] Changed operator tests use `bin/test`.
- [x] No old CLI definition, packaged main, test, or launcher remains.
- [x] Plain Babashka cannot resolve `src-old`.
- [x] Disabled quarry gates are deleted rather than given an escape hatch.
