---
type: issue
status: resolved
severity: cleanup
tags: [issue, test, tooling, architecture]
---

# Test runner bypassed ClojureScript namespace resolution

## Problem

`seon.test.runner` resolved test functions, fixture vars, and namespace
members through three direct `goog.getObjectByName` calls. The maintained
runtime resolver uses `cljs.core/find-ns-obj`, which covers both development
globals and namespace objects kept in module scope by simple optimization.
The runner's direct global lookup was a second resolution idiom and could
diverge by artifact flavor.

## Resolution

All three runner paths now resolve the namespace through
`cljs.core/find-ns-obj` and read the member from that one live namespace
object. Selector semantics, Malli-wrapper unwrapping, deftest metadata,
fixture ordering, and missing-namespace behavior are unchanged. No registry
or copied namespace table was introduced.

The runner cannot require `seon.eval/lookup-value` directly because
`seon.eval` already requires `seon.test.runner`; using the same underlying
ClojureScript namespace owner preserves the one mechanism without creating a
cycle.

## Proof

`bin/test-cljs --test=seon.test.runner-test
--test=seon.test.fixture-support-test --test=seon.test.runner-timeout-test`
passes 17 tests and 59 assertions with zero failures, errors, or compiler
warnings. The gates cover individual test resolution, namespace enumeration,
missing namespaces, synchronous and asynchronous fixture resolution, and
runner timeouts.
