(ns seon.ns.routes-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.ctx :as ctx]
            [seon.getting-started :as gs]
            [seon.getting-started.render :as gs-render]
            [seon.ns.routes :as routes]
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
;;; Form Data Round Trip Tests
;;; ---------------------------------------------------------------------------

(deftest form-data-round-trip-test
  (testing "qualified keywords survive form POST round trip"
    ;; Simulates what Datastar sends with contentType:'form'
    ;; Form field name=":seon.getting-started/exercise" value="Pull-up"
    ;; arrives as URL-encoded: %3Aseon.getting-started%2Fexercise=Pull-up
    ;; routes/parse-form-body decodes to {:seon.getting-started/exercise "Pull-up"}
    (let [form-body ":seon.getting-started/exercise=Pull-up&:seon.getting-started/sets=4"]
      (is (= {:seon.getting-started/exercise "Pull-up"
              :seon.getting-started/sets "4"}
             (#'routes/parse-form-body form-body))))))

;;; ---------------------------------------------------------------------------
;;; Function Call Handler Tests
;;; ---------------------------------------------------------------------------

(deftest function-call-handler-signals-test
  (testing "add-workout! receives form-encoded qualified keywords"
    ;; Simulate Datastar @post with contentType:'form'
    (let [request {:request-method :post
                   :uri "/ns/seon.getting-started/add-workout!"
                   :path-params {:namespace "seon.getting-started"
                                 :function "add-workout!"}
                   :query-string "instance=test-rt"
                   :headers {"content-type" "application/x-www-form-urlencoded"}
                   :body (str ":seon.getting-started/exercise=Pull-up"
                              "&:seon.getting-started/sets=4"
                              "&:seon.getting-started/reps=8"
                              "&:seon.getting-started/weight=10")}
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
      ;; Form fields have name attributes with qualified keywords
      (is (some #(and (vector? %) (map? (second %))
                      (let [n (:name (second %))]
                        (and (string? n) (str/starts-with? n ":"))))
                nodes)
          "form fields have qualified keyword names")
      ;; Action URLs include instance ID
      (is (some #(and (vector? %) (map? (second %))
                      (some (fn [[_ v]] (and (string? v)
                                             (str/includes? v "instance=test-rt")))
                            (second %)))
                nodes)
          "action URLs include instance ID"))))

;;; ---------------------------------------------------------------------------
;;; GET Function Call Tests
;;; ---------------------------------------------------------------------------

(deftest function-get-handler-test
  (testing "GET zero-arg function returns EDN result"
    (let [request {:request-method :get
                   :uri "/ns/seon.getting-started/step-3"
                   :path-params {:namespace "seon.getting-started"
                                 :function "step-3"}
                   :query-string ""}
          response (routes/function-get-handler request)]
      (is (= 200 (:status response)))
      (is (str/includes? (get-in response [:headers "Content-Type"]) "edn"))
      (let [result (edn/read-string (:body response))]
        (is (= 3 (:seon.getting-started/current-step result))))))

  (testing "GET nonexistent function returns 404"
    (let [request {:request-method :get
                   :uri "/ns/seon.getting-started/no-such-fn"
                   :path-params {:namespace "seon.getting-started"
                                 :function "no-such-fn"}
                   :query-string ""}
          response (routes/function-get-handler request)]
      (is (= 404 (:status response))))))

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
                   :body ""}
          response (routes/function-call-handler request)]
      (is (= 404 (:status response))))))
