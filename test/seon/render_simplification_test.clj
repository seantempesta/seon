(ns seon.render-simplification-test
  "Behavioral gates for ruling #50's minimal render model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(def ^:private fixture-a 'seon.render-simplification.fixture-a)
(def ^:private fixture-b 'seon.render-simplification.fixture-b)
(def ^:private fixture-ambiguous
  'seon.render-simplification.fixture-ambiguous)

(defn- target-call
  [namespace-name function-name request]
  (if-let [function (ns-resolve namespace-name function-name)]
    (function request)
    ::not-landed))

(defn- render-request
  [database ctx owning-namespace rendered-value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/namespace owning-namespace
   :seon.render/value rendered-value
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic})

(defn- render-ai
  [request]
  (target-call 'seon.render 'render-ai request))

(defn- render-html
  [request]
  (target-call 'seon.render 'render-html request))

(defn- flat-units
  [neighborhood]
  neighborhood)

(deftest floor-totality-uses-one-prepared-value
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)]
       (doseq [rendered-value [7 [1 2 3] {:open/declared 1 :open/extra 2}]]
         (let [request (render-request database ctx nil rendered-value)
               floor-unit {:seon.render/value rendered-value
                           :seon.sci.admit/caps caps}]
           (is (= (value/render-ai floor-unit) (render-ai request)))
           (is (= (value/render-html floor-unit) (render-html request)))
           (is (not= :seon.render/missing-declaration
                     (:seon.error/kind (render-ai request))))))))))

(deftest nested-values-render-their-declared-faces
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           report (binding [db/*conn* connection]
                    (db/transact! []))
           rendered (render-ai
                     (render-request database ctx nil
                                     {:probe/database database
                                      :probe/report report}))]
       (is (str/includes? rendered "database"))
       (is (str/includes? rendered "basis transaction"))
       (is (str/includes? rendered "Committed transaction"))
       (is (not (str/includes? rendered "#datahike.db.DB")))))))

(deftest owning-namespace-alone-selects-across-a-walk
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           rendered-value {:seon.ns/name fixture-b}
           request (render-request database ctx fixture-b rendered-value)]
       (is (= (str "B:" fixture-b) (render-ai request)))
       (is (= (str "A:" fixture-b)
              (render-ai (assoc request :seon.render/namespace fixture-a))))
       (let [walked (walk/neighborhood
                     {:seon.db/db database
                      :seon.sci.eval/ctx ctx
                      :seon.render.walk/lookup [:seon.ns/name fixture-b]
                      :seon.render/output :seon.render/ai
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms 2000
                      :seon.config/on-core-error :panic
                      :seon.render/distance 0})
             root-unit (first walked)]
         (is (vector? walked))
         (is (= (str "B:" fixture-b) (:seon.render/output root-unit)))
         (is (not-any? #(contains? root-unit %)
                       [:seon.render/unit
                        :seon.render/kind
                        :seon.render/projection
                        :seon.render/would-fall-to-floor?])))
       (db/transact! connection
                     [[:db.fn/retractEntity
                       [:seon.fn/sym
                        "seon.render-simplification.fixture-b/namespace-ai"]]])
       (let [without-owner-function
             (render-ai (assoc request :seon.db/db @connection))]
         (is (not (str/starts-with? (str without-owner-function) "A:")))
         (is (not= :seon.render/missing-declaration
                   (:seon.error/kind without-owner-function))))))))

(deftest overlapping-contracts-refuse-loudly-and-deterministically
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           result (render-ai
                   (render-request database ctx fixture-ambiguous
                                   {:seon.ns/name fixture-ambiguous}))
           walked (walk/neighborhood
                   {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.render.walk/lookup
                    [:seon.ns/name fixture-ambiguous]
                    :seon.render/output :seon.render/ai
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.render/distance 0})
           expected #{"seon.render-simplification.fixture-ambiguous/first-ai"
                      "seon.render-simplification.fixture-ambiguous/second-ai"}]
       (is (= :seon.render/ambiguous (:seon.error/kind result)))
       (is (= expected
              (set (:seon.render/candidates (:seon.error/data result)))))
       (is (= (sort expected)
              (:seon.render/candidates (:seon.error/data result))))
       (is (= :seon.render/ambiguous
              (get-in (first walked)
                      [:seon.error/value :seon.error/kind])))
       (is (= expected
              (set (get-in (first walked)
                           [:seon.error/value
                            :seon.error/data
                            :seon.render/candidates]))))))))

(deftest renderer-invocation-is-sci-only-and-live-var-backed
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           request (render-request database ctx fixture-b
                                   {:seon.ns/name fixture-b})]
       (with-redefs [clojure.core/requiring-resolve
                     (fn [& _]
                       (throw (ex-info "compiled resolver trap" {})))]
         (is (= (str "B:" fixture-b) (render-ai request))))
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (str "B2:" (:seon.ns/name value)))))
       (is (= (str "B2:" fixture-b) (render-ai request)))
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [_value]
             [:div (seon.render.hiccup/raw
                    "<script>raw-agent-bytes</script>")])))
       (let [raw-result
             (render-html
              (assoc (render-request database ctx fixture-b {:fixture/value 1})
                     :seon.render/html
                     'seon.render-simplification.fixture-b/namespace-ai))]
         (is (hiccup/hiccup? raw-result))
         (is (not
              (hiccup/raw?
               (get-in raw-result
                       [1]))))
         (is (str/includes? (hiccup/->string raw-result)
                            "&lt;script&gt;raw-agent-bytes&lt;/script&gt;"))
         (is (not (str/includes? (hiccup/->string raw-result)
                                 "<script>raw-agent-bytes</script>"))))
       (let [evaluation
             (eval/evaluate
              {:seon.cluster.run.form/source
               (str "(defn live-html "
                    "{:malli/schema [:=> [:cat :map] :seon.render/html]} "
                    "[_value] [:article {:class \"live-html\"} \"live\"])")
               :seon.cluster.run.form/ns [:seon.ns/name fixture-a]
               :seon.sci.eval/ctx ctx
               :seon.sci.admit/caps caps
               :seon.sci.eval/time-limit-ms 2000
               :seon.config/on-core-error :panic})
             row (:seon.program/row evaluation)]
         (db/transact! connection [(dissoc row :seon.sci.eval/evaluated?)])
         (eval/install-row!
          {:seon.sci.eval/ctx ctx
           :seon.db/db @connection
           :seon.program/row row})
         (is (some #{'seon.render-simplification.fixture-a/live-html}
                   (kernel/public-functions-in ctx fixture-a))
             "the terminal install publishes a new renderer candidate")
         (is (= [:article {:class "live-html"} "live"]
                (render-html
                 (render-request @connection ctx fixture-a
                                 {:seon.ns/name fixture-a})))
             "an ordinary durable defn auto-wires onto the next render"))))))

(deftest cold-context-reacquires-the-same-row
  (support/with-database
   (fn [connection]
     (let [database @connection
           request (fn [ctx]
                     (render-request database ctx fixture-b
                                     {:seon.ns/name fixture-b}))]
       (is (= (str "B:" fixture-b)
              (render-ai (request (eval/cluster-ctx database connection)))))
       (is (= (str "B:" fixture-b)
              (render-ai (request (eval/cluster-ctx database connection)))))))))

(deftest distance-spends-only-real-ref-hops-and-caps-win
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [[:db/add [:seon.ns/name fixture-a]
        :seon.ns/requires [:seon.ns/name fixture-b]]])
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           request {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.render.walk/lookup [:seon.ns/name fixture-a]
                    :seon.render/output :seon.render/ai
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
           at-zero (flat-units (walk/neighborhood
                                (assoc request :seon.render/distance 0)))
           at-one (flat-units (walk/neighborhood
                               (assoc request :seon.render/distance 1)))
           capped (flat-units
                   (walk/neighborhood
                    (assoc request
                           :seon.render/distance 4
                           :seon.sci.admit/caps
                           (assoc caps
                                  :seon.config.eval.result/max-nodes 1))))]
       (is (= #{[:seon.ns/name fixture-a]}
              (set (map :seon.render.walk/lookup at-zero))))
       (is (contains? (set (map :seon.render.walk/lookup at-one))
                      [:seon.ns/name fixture-b]))
       (is (<= (count (remove :seon.error/value capped)) 1))))))

(deftest old-slot-and-ref-markers-are-inert-renderer-output
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (eval/cluster-ctx database connection)
           expected [:section {:data-slot "inert"}
                     [:span {:data-ref "[:db/id 7]"} "also inert"]]
           result
           (render-html
            (assoc (render-request database ctx fixture-b {:fixture/value 1})
                   :seon.render/html
                   'seon.render-simplification.fixture-b/holes-html))]
       (is (= expected result))
       (is (hiccup/hiccup? result))))))

(deftest settled-package-is-reused-by-every-join
  (let [keyframe "<article id=\"one\">one</article>"
        keyframe-bytes (.getBytes keyframe "UTF-8")
        package {:seon.render.package/revision 7
                 :seon.render.package/base-revision 6
                 :seon.render.package/basis-transaction 1
                 :seon.render.package/streaming? false
                 :seon.render.package/keyframe {"one" keyframe}
                 :seon.render.package/keyframe-bytes keyframe-bytes
                 :seon.render.package/keyframe-size (alength keyframe-bytes)
                 :seon.render.package/delta {"one" keyframe}
                 :seon.render.package/delta-bytes keyframe-bytes
                 :seon.render.package/delta-size (alength keyframe-bytes)}]
    (with-redefs [hiccup/->string (fn [& _]
                                   (throw (ex-info "serialized on join" {})))]
      (is (identical? package
                      (target-call 'seon.render.web 'join-package package)))
      (is (identical? package
                      (target-call 'seon.render.web 'join-package package))))))

(deftest broken-renderer-is-private-to-browser-and-loud-to-owner
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "render-failure")
     (db/transact!
      connection
      (agent/creation-tx
       {:seon.cluster.agent/id "owner-b"
        :seon.cluster/name "render-failure"
        :seon.ns/name fixture-b}))
     (let [failure {:seon.error/kind :render.test/broken
                    :seon.error/message "secret stack and renderer symbol"
                    :seon.error/data
                    {:seon.fn/sym
                     "seon.render-simplification.fixture-b/holes-html"}}
           outcome
           (target-call
            'seon.render 'renderer-failure
            {:seon.db/db @connection
             :seon.render/namespace fixture-b
             :seon.error/value failure})]
       (db/transact! connection (:seon.db/tx-data outcome))
       (let [html (:seon.render/html outcome)
             browser (str html)
             messages
             (db/q '[:find [?content ...]
                    :in $ ?owner
                    :where
                    [?agent :seon.cluster.agent/id ?owner]
                    [?message :seon.cluster.message/to ?agent]
                    [?message :seon.cluster.message/content ?content]]
                  @connection "owner-b")]
         (is (str/includes? browser "unavailable"))
         (is (not (str/includes? browser "secret stack")))
         (is (not (str/includes? browser "holes-html")))
         (is (= 1 (count messages)))
         (is (boolean
              (some #(str/includes? % "renderer") messages))))))))
