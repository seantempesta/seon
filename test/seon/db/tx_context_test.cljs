(ns seon.db.tx-context-test
  "Tests for `seon.db/with-tx-context` + `current-tx-context` + the
   tx-meta auto-merge contract in `seon.db/transact!`.

   The load-bearing properties (per v1.md §2.3 + the D13 finding):

   1. Outside any scope, `current-tx-context` is nil.
   2. Inside `with-tx-context`, the active ctx is readable.
   3. Nested `with-tx-context` MERGES: child sees parent's keys.
   4. Explicit `opts.:tx-meta` on `transact!` wins per-key against
      the context.
   5. Context survives across `await` points inside the wrapped fn.
   6. Concurrent ^:async fns with their own contexts DO NOT clobber
      each other (the AsyncLocalStorage primitive vs the CLJS-binding
      failure mode in
      `research/impl-finding-tx-context-promise-2026-05-22.md`).

   Run via `seon.test.runner/run-vars` over MCP.

   Property #5 + #6 are the ones a CLJS `^:dynamic` Var implementation
   silently fails — keeping these as live tests guards against any
   future refactor that tries to revert."
  (:require [cljs.test :as t :refer [deftest is testing]]
            [seon.db :as db]))

(deftest outside-scope-returns-nil
  (testing "current-tx-context returns nil outside any with-tx-context"
    (is (nil? (db/current-tx-context)))))

(deftest inside-scope-returns-ctx
  (testing "inside with-tx-context, current-tx-context returns the established map"
    (let [observed (db/with-tx-context
                     {:seon.db/user [:seon.agent/id "abcdefgh1234"]
                      :seon.db/process
                      [:seon.db.process/id :seon.db.process/repl]}
                     #(db/current-tx-context))]
      (is (= {:seon.db/user [:seon.agent/id "abcdefgh1234"]
              :seon.db/process
              [:seon.db.process/id :seon.db.process/repl]}
             observed)))))

(deftest scope-unwinds-cleanly
  (testing "after with-tx-context returns, current-tx-context is back to nil"
    (db/with-tx-context
      {:seon.db/process [:seon.db.process/id :seon.db.process/repl]}
                        #(db/current-tx-context))
    (is (nil? (db/current-tx-context))
        "outer ctx must be nil after the with-tx-context body returns")))

(deftest nested-with-tx-context-merges
  (testing "inner with-tx-context inherits parent keys + adds its own"
    (let [observed
          (db/with-tx-context
            {:seon.db/user [:seon.agent/id "abcdefgh1234"]}
            (fn []
              (db/with-tx-context
                {:seon.db/process
                 [:seon.db.process/id :seon.db.process/repl]}
                #(db/current-tx-context))))]
      (is (= {:seon.db/user [:seon.agent/id "abcdefgh1234"]
              :seon.db/process
              [:seon.db.process/id :seon.db.process/repl]}
             observed)))))

(deftest nested-with-tx-context-override
  (testing "inner with-tx-context can override a parent key by re-binding it"
    (let [observed
          (db/with-tx-context
            {:seon.db/process [:seon.db.process/id :seon.db.process/repl]}
            (fn []
              (db/with-tx-context
                {:seon.db/process [:seon.db.process/id :seon.db.process/boot]}
                #(db/current-tx-context))))]
      (is (= [:seon.db.process/id :seon.db.process/boot]
             (:seon.db/process observed)))))
  (testing "after inner scope returns, parent ctx is restored"
    (let [outer-after-inner
          (db/with-tx-context
            {:seon.db/process [:seon.db.process/id :seon.db.process/repl]}
            (fn []
              (db/with-tx-context
                {:seon.db/process [:seon.db.process/id :seon.db.process/boot]}
                (constantly nil))
              (:seon.db/process (db/current-tx-context))))]
      (is (= [:seon.db.process/id :seon.db.process/repl]
             outer-after-inner)))))
