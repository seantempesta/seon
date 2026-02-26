(ns seon.ns.routes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.ctx :as ctx]
            [seon.getting-started :as gs]
            [seon.getting-started.render :as gs-render]
            [seon.ns.routes :as routes]
            [seon.web.reactive.actions :as actions]
            [seon.web.reactive.transform :as transform]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn- with-test-instance
  "Create a test ctx instance, run body, then destroy."
  [f]
  (let [instance-id "test-rt"]
    (ctx/create! {::ctx/instance-id instance-id
                  ::ctx/namespace 'seon.getting-started
                  ::ctx/initial-value (gs/step-3)
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})
    (try
      (f)
      (finally
        (ctx/destroy! {::ctx/instance-id instance-id})))))

(use-fixtures :each with-test-instance)

;;; ---------------------------------------------------------------------------
;;; Signal Re-Namespacing Tests
;;; ---------------------------------------------------------------------------

(deftest fn-schema-key-map-test
  (testing "extracts qualified keys from function schema"
    (let [v (resolve 'seon.getting-started/send-message!)
          km (#'routes/fn-schema-key-map v 'seon.getting-started)]
      (is (= :seon.ctx/user-input (get km "user-input"))
          "cross-namespace key preserved")
      (is (= :seon.reactive/ctx (get km "ctx"))
          "reactive ctx key preserved")))

  (testing "extracts same-namespace keys"
    (let [v (resolve 'seon.getting-started/add-workout!)
          km (#'routes/fn-schema-key-map v 'seon.getting-started)]
      (is (= :seon.getting-started/exercise (get km "exercise")))
      (is (= :seon.getting-started/sets (get km "sets")))
      (is (= :seon.getting-started/reps (get km "reps")))
      (is (= :seon.getting-started/weight (get km "weight")))))

  (testing "returns nil for function without schema"
    (is (nil? (#'routes/fn-schema-key-map (resolve 'clojure.core/identity) 'test)))))

;;; ---------------------------------------------------------------------------
;;; Function Call Handler Tests
;;; ---------------------------------------------------------------------------

(deftest function-call-handler-signals-test
  (testing "send-message! receives cross-namespace signal correctly"
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/send-message!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "send-message!"}
                   :query-string "instance=test-rt"
                   :body {"userInput" "test message"}}
          response (routes/function-call-handler request)
          ctx-val (ctx/get-value {::ctx/instance-id "test-rt"})]
      (is (= 200 (:status response)))
      ;; Step 3 has 4 pre-existing messages; ours is appended
      (is (= {:role :user :content "test message"}
             (last (:seon.ctx/messages ctx-val))))))

  (testing "add-workout! receives same-namespace signals correctly"
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/add-workout!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "add-workout!"}
                   :query-string "instance=test-rt"
                   :body {"exercise" "Pull-up" "sets" "4" "reps" "8" "weight" "10"}}
          response (routes/function-call-handler request)
          ctx-val (ctx/get-value {::ctx/instance-id "test-rt"})
          workouts (::gs/workouts ctx-val)]
      (is (= 200 (:status response)))
      ;; Step 3 has 5 default workouts + 1 new
      (is (= 6 (count workouts)))
      (is (= "Pull-up" (::gs/exercise (last workouts))))
      (is (= 4 (::gs/sets (last workouts)))))))

;;; ---------------------------------------------------------------------------
;;; Render + Transform Round Trip
;;; ---------------------------------------------------------------------------

(deftest render-transform-round-trip-test
  (testing "step-3 render -> transform produces Datastar attributes"
    (let [ctx (gs/step-3)
          result (gs-render/page-render {:seon.getting-started/*ctx* ctx})
          html (:seon.render/html result)
          transformed (transform/transform-hiccup
                       'seon.getting-started html "test-rt")
          nodes (tree-seq sequential? seq transformed)]
      ;; Has data-on:click for buttons
      (is (some #(and (vector? %) (map? (second %))
                      (some (fn [k] (str/starts-with? (name k) "data-on:"))
                            (keys (second %))))
                nodes)
          "transformed has data-on:click")
      ;; Has data-bind for inputs
      (is (some #(and (vector? %) (map? (second %))
                      (some (fn [k] (str/starts-with? (name k) "data-bind:"))
                            (keys (second %))))
                nodes)
          "transformed has data-bind")
      ;; Has data-signals on root
      (is (some #(and (vector? %) (map? (second %))
                      (:data-signals (second %)))
                nodes)
          "transformed has data-signals")
      ;; Action URLs include instance ID
      (is (some #(and (vector? %) (map? (second %))
                      (some (fn [[_ v]] (and (string? v)
                                             (str/includes? v "instance=test-rt")))
                            (second %)))
                nodes)
          "action URLs include instance ID"))))

;;; ---------------------------------------------------------------------------
;;; Signal Name Round Trip
;;; ---------------------------------------------------------------------------

(deftest signal-name-round-trip-test
  (testing "field name -> signal name -> re-namespace preserves identity"
    (let [field-key :seon.getting-started/exercise
          signal-name (name field-key)
          raw-signals (actions/extract-signals {signal-name "Squat"})
          action-fn (resolve 'seon.getting-started/add-workout!)
          key-map (#'routes/fn-schema-key-map action-fn 'seon.getting-started)
          re-namespaced (into {}
                              (map (fn [[k v]]
                                     (if (namespace k)
                                       [k v]
                                       [(or (get key-map (name k))
                                            (keyword "seon.getting-started" (name k)))
                                        v])))
                              raw-signals)]
      (is (= :seon.getting-started/exercise
             (ffirst re-namespaced))
          "exercise round-trips to same qualified key")))

  (testing "cross-namespace field round-trips correctly"
    (let [raw-signals (actions/extract-signals {"userInput" "hello"})
          action-fn (resolve 'seon.getting-started/send-message!)
          key-map (#'routes/fn-schema-key-map action-fn 'seon.getting-started)
          re-namespaced (into {}
                              (map (fn [[k v]]
                                     (if (namespace k)
                                       [k v]
                                       [(or (get key-map (name k))
                                            (keyword "seon.getting-started" (name k)))
                                        v])))
                              raw-signals)]
      (is (= :seon.ctx/user-input
             (ffirst re-namespaced))
          "cross-namespace key round-trips correctly"))))
