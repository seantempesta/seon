(ns seon.client-initialization-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.client :as client]
   [seon.db :as db]
   [seon.launch :as launch]))

(def ^:private digest
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- descriptor []
  {::launch/runtime {::launch/execution-digest digest}})

(defn- with-program-builders
  [core schemas body]
  (let [original-core client/index-core!
        original-schemas client/index-schemas]
    (set! client/index-core! (fn [] core))
    (set! client/index-schemas (fn [] schemas))
    (try
      (body)
      (finally
        (set! client/index-core! original-core)
        (set! client/index-schemas original-schemas)))))

(deftest initialization-is-one-deterministic-complete-value
  (let [namespace-row
        {:seon.ns/name :example.core
         :seon.ns/source "(ns example.core)"}
        function-row
        {:seon.fn/sym "example.core/identity"
         :seon.fn/ns [:seon.ns/name :example.core]
         :seon.fn/source "(defn identity [value] value)"
         :seon.fn/spec "[:=> [:cat :example/id] :example/id]"
         :seon.fn/created-at (js/Date. 1)}
        schema-row
        {:seon.schema/key :example/id
         :seon.schema/form ":int"
         :seon.schema/created-at (js/Date. 2)}
        build (deref #'client/database-initialization)
        forward
        (with-program-builders
          [function-row namespace-row]
          [schema-row]
          #(build (descriptor)))
        reverse
        (with-program-builders
          [namespace-row function-row]
          [schema-row]
          #(build (descriptor)))]
    (is (= forward reverse))
    (is (= digest (:seon.execution/artifact-digest forward)))
    (is (= [:seon.ns/name :seon.fn/sym :seon.schema/key]
           (mapv (fn [row]
                   (cond
                     (:seon.ns/name row) :seon.ns/name
                     (:seon.fn/sym row) :seon.fn/sym
                     (:seon.schema/key row) :seon.schema/key))
                 (:seon.db/program forward))))
    (is (not-any? #(or (contains? % :seon.fn/created-at)
                       (contains? % :seon.schema/created-at))
                  (:seon.db/program forward)))
    (is (= [{:seon.user/id "user"}
            {:my.kb.shared/id "shared"}]
           (:seon.db/initial-data forward)))))

(deftest invalid-complete-program-fails-before-session-open
  (async done
    (let [original-descriptor launch/process-launch-descriptor
          original-open db/open-session!
          original-core client/index-core!
          original-schemas client/index-schemas
          opened? (atom false)]
      (set! launch/process-launch-descriptor (descriptor))
      (set! client/index-core!
            (fn []
              [{:seon.ns/name :example.core
                :seon.ns/source "(ns example.core)"}
               {:seon.fn/sym "example.core/broken"
                :seon.fn/ns [:seon.ns/name :example.core]
                :seon.fn/source "(defn broken [value] value)"
                :seon.fn/spec
                "[:=> [:cat :example/missing] :example/missing]"}]))
      (set! client/index-schemas
            (fn []
              [{:seon.schema/key :example/id :seon.schema/form ":int"}]))
      (set! db/open-session!
            (fn [_]
              (reset! opened? true)
              (js/Promise.resolve {})))
      (-> (client/open-database-session!
           {:seon.client/initialize? true})
          (.then
           (fn [_]
             (is false "invalid complete projection was admitted")))
          (.catch
           (fn [_]
             (testing "the full schema and function projection is validated first"
               (is (false? @opened?)))))
          (.finally
           (fn []
             (set! launch/process-launch-descriptor original-descriptor)
             (set! client/index-core! original-core)
             (set! client/index-schemas original-schemas)
             (set! db/open-session! original-open)
             (done)))))))
