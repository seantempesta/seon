(ns seon.fn.incremental-deletion-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.schema :as schema]
            [seon.test-support :as support]))

;; An invoker's declared targets come from the declaration population, not
;; its own file: `sample.caller/run` invokes `:sample.invoke/handler`, and a
;; declaration names `sample.callee/produce` under that attribute. Removing the
;; callee and its declaration leaves `caller.clj` byte-identical, so an
;; incremental publication selects only `callee.clj`.
(def ^:private callee-source
  "(ns sample.callee)\n(defn produce [] 1)\n(defn kept [] 2)\n")

(def ^:private caller-source
  "(ns sample.caller)\n(defn run {:seon.fn/invokes #{:sample.invoke/handler}} [] nil)\n")

(def ^:private literal-source
  "(ns sample.literal (:require [sample.callee]))\n(defn run [] (sample.callee/produce))\n")

(defn- with-declaration
  [projection]
  (assoc-in projection [:seon.schema.projection/forms :sample.invoke/declaration]
            [:map {:sample.invoke/handler 'sample.callee/produce}]))

(defn- calls-of
  [database sym]
  (:seon.fn/calls (db/pull database [:seon.fn/calls] [:seon.fn/sym sym])))

(defn- delete-callee!
  "Publish the sample, remove `produce`, publish `callee.clj` incrementally."
  [connection root files]
  (let [request {:seon.fn/root (.getCanonicalPath root) :seon.fn/roots ["src"]}
        projection (db/carried-projection (db/db connection))]
    (doseq [[file source] files]
      (spit (doto (io/file root "src/sample" file) io/make-parents) source))
    (support/transacted! connection
                         (seon.fn/analyze-rows
                          (assoc request :seon.schema/projection (with-declaration projection))))
    (let [before (db/db connection)
          _ (is (contains? (calls-of before 'sample.caller/run) 'sample.callee/produce)
                "The declared invocation is a stored edge of the unchanged caller.")
          _ (spit (io/file root "src/sample/callee.clj") "(ns sample.callee)\n(defn kept [] 2)\n")
          selected (assoc request :seon.source/previous-database before
                                  :seon.schema/projection projection
                                  :seon.fn/changed-paths #{"src/sample/callee.clj"})]
      (support/refusal-data
       #(let [analyzed (seon.fn/analyze-rows selected)]
          (schema/call-with-projection
           projection
           (fn [] (seon.fn/index! (assoc selected :seon.db/connection connection
                                                 :seon.program/rows analyzed)))))))))

(deftest ^{:seon.test/long "Two sample-root analyses plus one incremental reconciliation on a fixture branch, as in selected-rows-reconcile-without-a-manifest."
           :seon.test/long-ms 15000}
  a-deletion-recomputes-its-unchanged-callers-edges
  (testing "a caller whose edge derived from the removed declaration commits without it"
    (let [root (io/file "tmp" (str "incremental-deletion-" (id/id)))]
      (try
        (support/with-database
         (fn [connection]
           (let [result (delete-callee! connection root {"callee.clj" callee-source
                                                         "caller.clj" caller-source})
                 after (db/db connection)]
             (is (= support/committed result) (pr-str result))
             (is (nil? (db/pull after [:seon.fn/sym] [:seon.fn/sym 'sample.callee/produce])))
             (is (not (contains? (calls-of after 'sample.caller/run) 'sample.callee/produce))
                 "The caller's edge names no deleted identity."))))
        (finally (support/delete-recursively! root)))))
  (testing "a caller whose source still calls the removed definition refuses, naming it"
    (let [root (io/file "tmp" (str "incremental-deletion-" (id/id)))]
      (try
        (support/with-database
         (fn [connection]
           (let [refusal (delete-callee! connection root {"callee.clj" callee-source
                                                          "caller.clj" caller-source
                                                          "literal.clj" literal-source})]
             (is (not= support/committed refusal))
             (is (re-find #"sample\.callee/produce" (pr-str refusal)) (pr-str refusal))
             (is (some? (db/pull (db/db connection) [:seon.fn/sym]
                                 [:seon.fn/sym 'sample.callee/produce]))
                 "The refused population leaves the published program in place."))))
        (finally (support/delete-recursively! root))))))
