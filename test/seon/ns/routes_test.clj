(ns seon.ns.routes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.ctx :as ctx]
            [seon.getting-started :as gs]
            [seon.getting-started.render :as gs-render]
            [seon.ns.routes :as routes]
            [seon.web.reactive.encoding :as encoding]
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
;;; Signal Encoding Round Trip Tests
;;; ---------------------------------------------------------------------------

(deftest signal-encoding-round-trip-test
  (testing "qualified keyword encodes and decodes back to itself"
    (doseq [kw [:seon.getting-started/exercise
                :seon.ctx/user-input
                :seon.health.workout/sets]]
      (let [path (encoding/encode-keyword kw)
            segments (str/split path #"\.")
            nested (reduce (fn [acc seg] {seg acc})
                           "test-value"
                           (reverse segments))
            decoded (encoding/decode-signals nested)]
        (is (= kw (ffirst decoded))
            (str "round-trip failed for: " kw)))))

  (testing "cross-namespace signals decode correctly from nested JSON"
    (is (= {:seon.getting-started/exercise "Pull-up"
            :seon.ctx/user-input "hello"}
           (encoding/decode-signals
            {"seon" {"gettingStarted" {"exercise" "Pull-up"}
                     "ctx" {"userInput" "hello"}}})))))

;;; ---------------------------------------------------------------------------
;;; Function Call Handler Tests
;;; ---------------------------------------------------------------------------

(deftest function-call-handler-signals-test
  (testing "send-message! receives cross-namespace signal via encoding"
    ;; Simulate what Datastar sends: nested JSON from dot-notation signals
    ;; seon.ctx.userInput -> {"seon": {"ctx": {"userInput": "test message"}}}
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/send-message!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "send-message!"}
                   :query-string "instance=test-rt"
                   :body {"seon" {"ctx" {"userInput" "test message"}}}}
          response (routes/function-call-handler request)
          ctx-val (ctx/get-value {::ctx/instance-id "test-rt"})]
      (is (= 200 (:status response)))
      ;; Step 3 has 4 pre-existing messages; ours is appended
      (is (= {:role :user :content "test message"}
             (last (:seon.ctx/messages ctx-val))))))

  (testing "add-workout! receives same-namespace signals via encoding"
    ;; seon.gettingStarted.exercise -> {"seon": {"gettingStarted": {"exercise": "Pull-up", ...}}}
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/add-workout!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "add-workout!"}
                   :query-string "instance=test-rt"
                   :body {"seon" {"gettingStarted" {"exercise" "Pull-up"
                                                    "sets" "4"
                                                    "reps" "8"
                                                    "weight" "10"}}}}
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
;;; Validation Tests
;;; ---------------------------------------------------------------------------

(deftest validation-rejects-bad-input-test
  (testing "function not found returns 404"
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/nonexistent-fn!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "nonexistent-fn!"}
                   :query-string "instance=test-rt"
                   :body {}}
          response (routes/function-call-handler request)]
      (is (= 404 (:status response))))))
