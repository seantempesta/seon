---
title: "Patch for test/seon/test_support.clj (held by lane fixture-hour): a running-agent test cluster names a disposable checkout"
type: research
status: proposed by the goal lane, 2026-10-12, after M9 (05d3eb2ed) and M8a. Not applied: the file is held.
---

# A test cluster names a disposable checkout

## Why

M9 and M8a resolve the checkout a code merge is proven against and published into from the cluster's
`:seon.config.fs/working-root` (`seon.merge/checkout`). A test member runs on a branch of main, so a
`support/with-running-agent` cluster (its cluster name is the executing member's, `"default"` on default's
JVM) inherits main's dial: `/Users/sean/src/seon`, the live checkout (verified 2026-10-12 by a JVM read as
root). After M8a, a test whose agent ends green with a merge request and gets a db<->flow pass merges
through the gate and publishes into that checkout. Today every such fixture writes into a `tq.*` namespace
with no file in the checkout, so `prepare-merge!`'s file proof refuses (`:one-file`) before acceptance;
nothing is written. The hazard is a future fixture that changes a declaration of a real namespace.
`seon.cluster.merge-test` sets the dial to its own temporary checkout (`checkout-dial!`); the chain worlds
(`"tq-chain"`) resolve `"."` to the JVM's archive, which `seon.fs/checkout-root?` refuses.

## Change (the owner of the test cluster's world)

In `seon.test-support/with-running-agent`, after the cluster handle exists and before `body` runs:

```clojure
;; a test cluster publishes code merges into its own disposable checkout, never the live one
(let [checkout (temp-root "running-agent-checkout")]
  (apply-config! connection cluster-name {:seon.config.fs/working-root checkout})
  ...
  (finally (delete-root! checkout)))
```

A test that needs a git checkout with files (`seon.cluster.merge-test`) keeps applying its own. The
directory is not a git repository, so a publication there refuses at `commit!` and is recorded as
`:seon.task/refusal`, which the test sees as data. Proof: the existing `seon.cluster.merge-test` and
`seon.turn-continue-test` members reaching `with-running-agent`; no new deftest (a fixture's dial).
