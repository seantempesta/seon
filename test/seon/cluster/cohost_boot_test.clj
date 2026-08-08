(ns ^{:seon.test/platform
       "Moving part: two sovereign clusters cohosted in one JVM."}
    seon.cluster.cohost-boot-test
  "Two sovereign clusters in ONE JVM, with instrumentation live.

  The class this kills: a value read back out of the database violates the
  contract its own producer declares, and nothing observes it because the
  only boot that ever runs instrumented is the SECOND one. `bin/seon start`
  on a fresh JVM applies instrumentation AFTER `start!` returns
  (`script/seon/fresh_operator.clj:1389`); adding a cluster to a running JVM
  refreshes instrumentation BEFORE `start!`
  (`script/seon/fresh_operator.clj:1430`), under the FIRST cluster's
  projection state. So the co-hosted second boot is the first boot in the
  system's life to have its own contracts checked, and
  `seon.cluster/require-activation!` refused there while booting cleanly in
  its own root
  ([issue](../../../docs/seon/issues/a-cohosted-second-cluster-cannot-boot.md)).

  This namespace reproduces that exact ordering — cluster A boots, the
  operator's instrumentation is applied under A's projection state, cluster B
  boots into the same JVM — and asserts what the issue's acceptance criteria
  name: both clusters reach a live boot, each holds its OWN projection state,
  each validates its own activation closure, and ordinary evaluation completes
  in each cluster's own sci ctx.

  Filesystem fixtures live under the project-local `tmp/`, never a system
  temp directory."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.cluster :as cluster]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:dynamic *published-root* nil)

(defn- published-root
  []
  (or @*published-root*
      (let [root (str "tmp/cohost-boot-test/" (random-uuid))]
        (.mkdirs (io/file root))
        (cluster/refresh-source! root)
        (reset! *published-root* root)
        root)))

(defn- with-published-root
  [body]
  (binding [*published-root* (atom nil)]
    (try
      (body)
      (finally
        (when-let [root @*published-root*]
          (test-support/delete-recursively! root))))))

(use-fixtures :once with-published-root)

(defn- projection-state-of
  [instance]
  (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance)))

(defn- apply-instrumentation-under
  "Apply instrumentation exactly as the operator's add path does: under one
  already-running cluster's projection state, for the whole process."
  [instance]
  (schema/call-with-projection-state
   (projection-state-of instance)
   (fn []
     (instrument/apply! {:seon.config/on-core-error :panic
                         :seon.sci.admit/caps caps}))))

(defn- evaluate-in
  [instance source]
  (sci.eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name 'user]
    :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 5000
    :seon.config/on-core-error :panic}))

(deftest ^{:seon.test/long
           "Boots two real clusters in this JVM with instrumentation live."}
  a-second-cluster-boots-under-the-first-cluster-s-instrumentation
  (let [root (published-root)
        instances (atom [])]
    (try
      (let [a (cluster/start! {:seon.boot/cluster-name "cohost-a"
                               :seon.boot/root root})
            _ (swap! instances conj a)
            applied (apply-instrumentation-under a)
            ;; The failure this regression exists for happened HERE: with
            ;; wrappers installed, cluster B's own boot refused at
            ;; `require-activation!`. A throw out of `start!` is the
            ;; reproduction, so it is not caught — a red test names it.
            b (cluster/start! {:seon.boot/cluster-name "cohost-b"
                               :seon.boot/root root})
            _ (swap! instances conj b)]
        (testing "instrumentation is genuinely live, not a vacuous pass"
          (is (pos? (:seon.instrument/instrumented applied))
              "zero instrumented vars would make every assertion below empty")
          (is (pos? (count (instrument/instrumented)))
              "the wrappers survive the second cluster's boot"))

        (testing "both clusters completed the boot tower"
          (is (nat-int? (:seon.boot/ready-ms a)))
          (is (nat-int? (:seon.boot/ready-ms b)))
          (is (not= (get-in a [:seon.boot/advertisement :seon.boot/prepl-port])
                    (get-in b [:seon.boot/advertisement :seon.boot/prepl-port]))
              "distinct live coordinates"))

        (testing "each cluster holds its own projection state"
          (is (some? (projection-state-of a)))
          (is (some? (projection-state-of b)))
          (is (not (identical? (projection-state-of a)
                               (projection-state-of b)))
              "a shared projection is the defect, not the design"))

        (testing "each cluster's activation closure satisfies its declaration"
          ;; The closure is the value the co-hosted boot refused. It is read
          ;; back through a pull projection, which is where the declared sets
          ;; were being lost, so it is asserted under EACH cluster's own
          ;; projection state rather than whichever one happens to be current.
          (doseq [[label instance] [["cohost-a" a] ["cohost-b" b]]]
            (let [closure (cluster/require-activation!
                           @(:seon.boot/cluster-connection instance))]
              (is (schema/call-with-projection-state
                   (projection-state-of instance)
                   (fn []
                     (schema/valid-candidate-value?
                      :seon.activation/closure closure)))
                  (str label "'s stored closure validates against "
                       ":seon.activation/closure"))
              (is (every? set?
                          ((juxt :seon.activation/schema-keys
                                 :seon.activation/required-attributes
                                 :seon.activation/config-defaults
                                 :seon.activation/config-required
                                 :seon.activation/executable-symbols)
                           closure))
                  (str label "'s closure carries the declared SETS, not the "
                       "vectors a pull projects")))))

        (testing "ordinary evaluation completes in each cluster's own ctx"
          (doseq [[label instance source expected]
                  [["cohost-a" a "(+ 20260806 1)" "20260807"]
                   ["cohost-b" b "(+ 20260807 1)" "20260808"]]]
            (let [result (evaluate-in instance source)]
              (is (nil? (:seon.cluster.eval/error result))
                  (str label " evaluated without an error value"))
              (is (= expected (str (:seon.sci.admit/value result)))
                  (str label " returned its own computed value"))))))
      (finally
        (instrument/remove!)
        (doseq [instance @instances]
          (try (cluster/stop! instance) (catch Throwable _ nil)))))))
