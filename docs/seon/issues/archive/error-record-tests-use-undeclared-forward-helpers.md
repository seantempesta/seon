---
type: issue
status: resolved
tags: [test, tooling, issue]
severity: cleanup
---

# Error record tests use undeclared forward helpers

## Problem

The canonical test build emits four undeclared-var warnings because two new
error-settlement tests call `with-captured-errors` and `captured-errors` before
their later definitions without declaring those forward references.

## Evidence

The frozen default rebuild at commit `fd941024` completed the `:test` build
with warnings at `test/seon/error_record_test.cljs:235,247,260,274`. The same
namespace already declares its other forward helper references.

## Owner

`test/seon/error_record_test.cljs` owns the fixture helpers and their forward
declarations.

## Acceptance

- The two helper symbols resolve without compiler warnings.
- The focused error-record tests pass.
- The canonical test build emits none of these four warnings.

## Resolution

Commit `ab6831a8` declares the two existing forward fixture helpers alongside
the namespace's other forward references. The focused selector compiles with
zero warnings and passes 19 tests / 86 assertions.
