---
title: "Patches from lane agent-hangs for files other lanes hold"
type: research
status: patches (lint-clean on HEAD f70e48517 copies; `git apply --check` clean on the working tree at d50a30d21; NOT run: the files are held)
basis: "HEAD d50a30d21. `test/seon/test_support.clj` is held by lane fixture-hour; `resources/seon/tasks/seon.test.edn` by lane restart-safe. `src/seon/test/runner.clj` is unclaimed but its only new consumer is the held test_support, so the two travel together."
---

## For root

1. **Event bound = the awaiting test's declared bound.** `seon.test-support/await-event!` waits 20 s
   (`event-backstop-seconds`) by default while a test body is bounded at 5,000 ms (or its `:seon.test/long-ms`). A
   missing event therefore cannot fail inside the bound: the body outlives it, `seon.test/bounded-result` reports it
   unfinished, the request stops and the central round rebuilds its root. The patch makes the default wait the
   declared bound of the test whose body runs on the thread (`clojure.test/*testing-vars*`), read through one owner,
   `seon.test.runner/declared-limit-ms`, extracted from `duration-failures` (EQUIVALENT there). Outside a body (REPL
   fixtures, a provider's handler thread) the 20 s constant stays. Explicit fourth-argument bounds are unchanged.
   Apply both diffs in one commit (fixture-hour, or root after it releases), proven by
   `bin/lane commit … --test seon.test.runner-test --test seon.test-support-test` plus one hang member, e.g.
   `--test seon.cluster.agent-test/hot-reload-var-test`, which should then end red at about 5 s instead of 20 s.
2. **Remaining bound needs the runner.** A per-await bound still lets two late waits overrun. The honest relation
   is the member's remaining body bound, which only `seon.test/run` knows (it starts the body thread). Task entry
   for `resources/seon/tasks/seon.test.edn` below (held by restart-safe); its proof is to be written first.

## Patch 1: `src/seon/test/runner.clj`

```diff
--- a/src/seon/test/runner.clj
+++ b/src/seon/test/runner.clj
@@ -328,18 +328,28 @@
          :expected '(pos? assertion-count)
          :actual assertion-count}))))
 
+(defn declared-limit-ms
+  "The body bound `test-var` declares: its `:seon.test/long-ms` when it also names its `:seon.test/long` reason,
+  never below `ordinary`, the packaged `:seon.test/time-limit-ms` by default."
+  {:malli/schema [:function [:=> [:cat :seon.test/var] :seon.test/time-limit-ms]
+                  [:=> [:cat :seon.test/var :seon.test/time-limit-ms] :seon.test/time-limit-ms]]}
+  ([test-var]
+   (declared-limit-ms test-var (get-in (schema.edn/packaged-forms) [:seon.test/time-limit-ms 1 :default])))
+  ([test-var ordinary]
+   (let [metadata (meta test-var)
+         declaration (program/test-markers metadata (meta (:ns metadata)))
+         reason (:seon.test/long declaration)
+         allowance (:seon.test/long-ms declaration)]
+     (if (and (string? reason) (not (str/blank? reason))
+              (integer? allowance) (pos? allowance))
+       (max ordinary allowance) ordinary))))
+
 (defn- duration-failures
   "Turn an observed body overrun into ordinary, durably recorded assertion evidence."
   {:malli/schema [:=> [:cat :seon.test.runner/report-options :seon.test/var :seon.test/elapsed-ms :seon.test/time-limit-ms]
                   [:vector :seon.test/duration-failure]]}
   [_options test-var elapsed ordinary]
-  (let [metadata (meta test-var)
-        declaration (program/test-markers metadata (meta (:ns metadata)))
-        reason (:seon.test/long declaration)
-        allowance (:seon.test/long-ms declaration)
-        limit (if (and (string? reason) (not (str/blank? reason))
-                       (integer? allowance) (pos? allowance))
-                (max ordinary allowance) ordinary)]
+  (let [limit (declared-limit-ms test-var ordinary)]
     (if (> elapsed limit)
       [{:type :fail :var test-var
         :message (str "Test " (var-symbol test-var) " exceeded its declared duration: "
```

## Patch 2: `test/seon/test_support.clj`

```diff
--- a/test/seon/test_support.clj
+++ b/test/seon/test_support.clj
@@ -44,7 +44,8 @@
 (set! *warn-on-reflection* true)
 
 (def event-backstop-seconds
-  "The loud clock around test events whose publishers are observable."
+  "The loud clock around test events awaited outside any test body (a REPL fixture, a provider's handler thread);
+  inside a body, `await-event!` waits at most that test's own declared bound (`event-bound-ms`)."
   20)
 
 (defn effective-config
@@ -555,14 +556,23 @@
                 (or (db/carried-projection database)
                     (schema/projection-from-database database)))))))))
 
+(defn event-bound-ms
+  "How long a test event may be awaited on this thread: the declared body bound of the test whose body runs here
+  (`seon.test.runner/declared-limit-ms`), since an event missing past it already fails that test; outside a body,
+  `event-backstop-seconds`."
+  {:malli/schema [:=> [:cat] [:int {:min 1}]]}
+  []
+  (if-let [test-var (first test/*testing-vars*)]
+    ((requiring-resolve 'seon.test.runner/declared-limit-ms) test-var)
+    (.toMillis TimeUnit/SECONDS event-backstop-seconds)))
+
 (defn await-event!
-  "Await one channel, latch, future, or watched reference with a loud backstop.
-  The fourth argument supplies the observation bound in milliseconds."
+  "Await one channel, latch, future, or watched reference with a loud backstop: `event-bound-ms`, or the fourth
+  argument's observation bound in milliseconds."
   ([event-source event]
    (await-event! event-source event (constantly true)))
   ([event-source event accept?]
-   (await-event! event-source event accept?
-                 (.toMillis TimeUnit/SECONDS event-backstop-seconds)))
+   (await-event! event-source event accept? (event-bound-ms)))
   ([event-source event accept? timeout-ms]
    (cond
      (instance? clojure.lang.IRef event-source)
```

## Task entry for `resources/seon/tasks/seon.test.edn`

```clojure
 #:seon.task{:name "an-awaited-test-event-is-bounded-by-the-members-remaining-bound"
             :kind :function
             :intent "A test body that awaits an event its fixture never produces must fail inside its own declared bound and exit, so `seon.test/run` continues with the next member. `seon.test/bounded-result` starts the body thread and knows the member's deadline, but binds only `seon.test/*member*`; `seon.test-support/await-event!` can bound a wait by the declared bound (patch in agent-hangs-patches.md) but not by what remains of it, so two late waits still overrun and the member is reported unfinished. Done when `seon.test/*member*` (or a binding beside it) carries the body's deadline, `seon.test-support/event-bound-ms` answers the time remaining to it, and a deftest that awaits a never-counted-down latch after spending most of its bound ends terminated and red within its bound. Proof: a new `seon.test-test` deftest (write it first) naming this task."
             :must-read #{seon.test/bounded-result seon.test/*member* seon.test-support/await-event! seon.test.runner/declared-limit-ms}
             :evidence #{"docs/research/agent-platform/2026-10-12/agent-hangs.md"}}
```
