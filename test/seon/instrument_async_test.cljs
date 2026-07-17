(ns seon.instrument-async-test
  "Resolved-value contracts across every ClojureScript async fn shape."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [goog.object :as gobj]
    [seon.db :as db]
    [seon.error]
    [seon.instrument :as instrument]
    [seon.schema :as schema]))

(defn ^:async async-fixed [x]
  (if (neg? x) "invalid" x))

(defn ^:async async-variadic [x & xs]
  (if (neg? x) ["invalid"] (into [x] xs)))

(defn ^:async async-multi
  ([x] x)
  ([x y] (if (neg? y) "invalid" [x y])))

(defn ^:async async-multi-variadic
  ([x] [x])
  ([x y & more]
   (if (neg? y) ["invalid"] (into [x y] more))))

(defn ^:async async-inject-multi
  ([request]
   [(:seon.agent/id request)])
  ([request x]
   [(:seon.agent/id request) x]))

(defn ^:async async-guarded [x]
  (if (neg? x) 0 x))

(defn ^:async async-reject-inner [error]
  (throw error))

(defn ^:async async-reject-outer [error]
  (await (async-reject-inner error)))

(def ^:private fixed-sym
  'seon.instrument-async-test/async-fixed)
(def ^:private variadic-sym
  'seon.instrument-async-test/async-variadic)
(def ^:private multi-sym
  'seon.instrument-async-test/async-multi)
(def ^:private multi-variadic-sym
  'seon.instrument-async-test/async-multi-variadic)
(def ^:private inject-sym
  'seon.instrument-async-test/async-inject-multi)
(def ^:private guarded-sym
  'seon.instrument-async-test/async-guarded)
(def ^:private reject-inner-sym
  'seon.instrument-async-test/async-reject-inner)
(def ^:private reject-outer-sym
  'seon.instrument-async-test/async-reject-outer)

(def ^:private fixed-form
  [:=> [:cat :int] :int])
(def ^:private variadic-form
  [:=> [:cat :int [:* :int]] [:vector :int]])
(def ^:private multi-form
  [:function
   [:=> [:cat :int] :int]
   [:=> [:cat :int :int] [:tuple :int :int]]])
(def ^:private multi-variadic-form
  [:function
   [:=> [:cat :int] [:vector :int]]
   [:=> [:cat :int :int [:* :int]] [:vector :int]]])
(def ^:private request-form
  [:map
   [:seon.agent/id {:optional true} :string]])
(def ^:private inject-form
  [:function
   [:=> [:cat request-form] [:tuple :string]]
   [:=> [:cat request-form :int] [:tuple :string :int]]])
(def ^:private guarded-form
  [:=> [:cat :int] :int
   [:fn (fn [[args value]] (= (first args) value))]])
(def ^:private reject-form
  [:=> [:cat :any] :any])

(def ^:private targets
  [{::instrument/sym fixed-sym ::instrument/schema-form fixed-form}
   {::instrument/sym variadic-sym ::instrument/schema-form variadic-form}
   {::instrument/sym multi-sym ::instrument/schema-form multi-form}
   {::instrument/sym multi-variadic-sym
    ::instrument/schema-form multi-variadic-form}
   {::instrument/sym inject-sym ::instrument/schema-form inject-form}
   {::instrument/sym guarded-sym ::instrument/schema-form guarded-form}
   {::instrument/sym reject-inner-sym ::instrument/schema-form reject-form}
   {::instrument/sym reject-outer-sym ::instrument/schema-form reject-form}])

(def ^:private target-syms
  (into #{} (map ::instrument/sym) targets))

(defn- live-fn [sym]
  (instrument/find-js-var (symbol (namespace sym)) (symbol (name sym))))

(defn- fixed-accessor [sym arity]
  (gobj/get (live-fn sym)
            (str "cljs$core$IFn$_invoke$arity$" arity)))

(defn- variadic-accessor [sym]
  (gobj/get (live-fn sym) "cljs$core$IFn$_invoke$arity$variadic"))

(defn- variadic-max-bridge? [sym]
  (let [f (live-fn sym)
        max-fixed (gobj/get f "cljs$lang$maxFixedArity")
        accessor (when (some? max-fixed)
                   (gobj/get
                     f
                     (str "cljs$core$IFn$_invoke$arity$" max-fixed)))]
    (and accessor
         (true? (gobj/get accessor "seon$instrument$variadicMaxBridge")))))

(defn- instrumented-marker? [sym]
  (true? (gobj/get (live-fn sym) "malli$instrument$instrumented?")))

(defn- trapped? [thunk]
  (try (thunk) false (catch :default _ true)))

(defn- assert-resolves [promise expected label]
  (.then promise
         (fn [actual]
           (is (= expected actual) label)
           actual)))

(defn- assert-rejects [thunk expected-type label]
  (try
    (.then (js/Promise.resolve (thunk))
           (fn [value]
             (is false (str label " unexpectedly resolved to " (pr-str value))))
           (fn [error]
             (is (= (str expected-type) (ex-message error)) label)
             error))
    (catch :default error
      (is (= (str expected-type) (ex-message error)) label)
      (js/Promise.resolve error))))

(defn- with-record-census [thunk]
  (let [error-ns (js/goog.getObjectByName "seon.error" js/goog.global)
        property (munge "record!")
        original (gobj/get error-ns property)
        calls (atom [])]
    (gobj/set
      error-ns property
      (fn [{raw :seon.error/raw :as request}]
        (swap! calls conj request)
        (when (some? raw)
          (gobj/set raw "seon$error$recorded" true))
        request))
    (let [result
          (try (js/Promise.resolve (thunk))
               (catch :default error (js/Promise.reject error)))]
      (-> result
          (.then (fn [value] {::value value ::calls @calls})
                 (fn [error] {::error error ::calls @calls}))
          (.finally (fn [] (gobj/set error-ns property original)))))))

(defn- assert-recorded-rejection [thunk expected-type label]
  (-> (with-record-census thunk)
      (.then
        (fn [{::keys [calls] caught ::error}]
          (is (= (str expected-type) (ex-message caught)) label)
          (is (= 1 (count calls)) (str label " records exactly once"))
          caught))))

(deftest async-shapes-validate-resolved-values-and-survive-refresh
  (async done
    (let [original-accessors
          {::multi-1 (fixed-accessor multi-sym 1)
           ::multi-2 (fixed-accessor multi-sym 2)
           ::variadic-min (fixed-accessor variadic-sym 1)
           ::multi-variadic-1 (fixed-accessor multi-variadic-sym 1)
           ::multi-variadic-min (fixed-accessor multi-variadic-sym 2)
           ::multi-variadic-rest (variadic-accessor multi-variadic-sym)}
          finish!
          (fn []
            (instrument/instrument-delta!
              {::instrument/changed-syms target-syms
               ::instrument/targets []})
            (is (identical? (::multi-1 original-accessors)
                            (fixed-accessor multi-sym 1)))
            (is (identical? (::multi-2 original-accessors)
                            (fixed-accessor multi-sym 2)))
            (is (identical? (::variadic-min original-accessors)
                            (fixed-accessor variadic-sym 1))
                "pure-variadic minimum bridge is removed")
            (is (identical? (::multi-variadic-1 original-accessors)
                            (fixed-accessor multi-variadic-sym 1)))
            (is (identical? (::multi-variadic-min original-accessors)
                            (fixed-accessor multi-variadic-sym 2))
                "multi+variadic minimum bridge is removed")
            (is (identical? (::multi-variadic-rest original-accessors)
                            (variadic-accessor multi-variadic-sym)))
            (doseq [sym target-syms]
              (is (false? (instrumented-marker? sym))
                  (str sym " has no stale Malli instrumentation marker")))
            (is (false? (boolean (variadic-max-bridge? variadic-sym)))
                "pure-variadic bridge is absent after removal")
            (is (false? (boolean (variadic-max-bridge? multi-variadic-sym)))
                "multi+variadic bridge is absent after removal")
            (let [readded (instrument/instrument-targets! targets)]
              (is (true? (::instrument/ok? readded))
                  "the fully removed target set is eligible for re-add")
              (is (variadic-max-bridge? variadic-sym))
              (is (variadic-max-bridge? multi-variadic-sym))
              (instrument/instrument-delta!
                {::instrument/changed-syms target-syms
                 ::instrument/targets []})))]
      (try
        (let [initial (instrument/instrument-targets! targets)]
          (is (true? (::instrument/ok? initial)))
          (is (= target-syms (::instrument/accepted-syms initial)))
          (is (= [] (::instrument/rejected initial)))
          (doseq [sym target-syms]
            (is (true? (instrument/async-fn? (live-fn sym)))
                (str sym " remains detectably async after Malli surgery")))
          (is (variadic-max-bridge? variadic-sym)
              "pure variadic receives its exact-minimum bridge")
          (is (variadic-max-bridge? multi-variadic-sym)
              "multi+variadic receives its exact-minimum bridge")
          (-> (assert-resolves (async-fixed 7) 7 "fixed direct")
              (.then (fn [_]
                       (assert-resolves (apply async-fixed [8]) 8
                                        "fixed apply")))
              (.then (fn [_]
                       (assert-resolves (async-variadic 1) [1]
                                        "pure variadic exact-minimum direct")))
              (.then (fn [_]
                       (assert-resolves (async-variadic 1 2 3) [1 2 3]
                                        "pure variadic above-minimum direct")))
              (.then (fn [_]
                       (assert-resolves (apply async-variadic [1 2 3 4])
                                        [1 2 3 4] "pure variadic apply")))
              (.then (fn [_]
                       (assert-resolves (async-multi 1) 1
                                        "multi fixed arity one")))
              (.then (fn [_]
                       (assert-resolves (apply async-multi [1 2]) [1 2]
                                        "multi fixed arity two apply")))
              (.then (fn [_]
                       (assert-resolves (async-multi-variadic 1) [1]
                                        "multi+variadic fixed")))
              (.then (fn [_]
                       (assert-resolves (async-multi-variadic 1 2) [1 2]
                                        "multi+variadic exact-minimum direct")))
              (.then (fn [_]
                       (assert-resolves
                         (apply async-multi-variadic [1 2 3 4])
                         [1 2 3 4] "multi+variadic apply")))
              (.then (fn [_]
                       (testing "input validation stays synchronous"
                         (is (trapped? #(async-fixed "wrong")))
                         (is (trapped? #(async-multi 1 "wrong")))
                         (is (trapped? #(async-multi-variadic 1 "wrong" 3))))))
              (.then (fn [_]
                       (assert-rejects #(async-variadic "wrong")
                                       :malli.core/invalid-input
                                       "pure variadic minimum input")))
              (.then (fn [_]
                       (assert-rejects #(async-multi-variadic 1 "wrong")
                                       :malli.core/invalid-input
                                       "multi+variadic minimum input")))
              (.then (fn [_]
                       (assert-recorded-rejection
                         #(async-fixed -1) :malli.core/invalid-output
                         "fixed resolved output")))
              (.then (fn [_]
                       (assert-recorded-rejection
                         #(async-variadic -1) :malli.core/invalid-output
                         "variadic exact-minimum resolved output")))
              (.then (fn [_]
                       (assert-recorded-rejection
                         #(async-multi 1 -1) :malli.core/invalid-output
                         "multi resolved output")))
              (.then (fn [_]
                       (assert-recorded-rejection
                         #(async-multi-variadic 1 -1)
                         :malli.core/invalid-output
                         "multi+variadic exact-minimum output")))
              (.then (fn [_]
                       (assert-resolves (async-guarded 6) 6
                                        "default-scope guard passes")))
              (.then
                (fn [_]
                  (-> (with-record-census #(async-guarded -1))
                      (.then
                        (fn [{::keys [calls] caught ::error}]
                          (is (= ":malli.core/invalid-guard"
                                 (ex-message caught))
                              "guard sees args and resolved value")
                          (is (= 1 (count calls))
                              "an invalid resolved guard records once"))))))
              (.then
                (fn [_]
                  (db/without-agent
                    (fn []
                      (db/with-agent
                        "INJECTtest0001"
                        (fn []
                          (assert-resolves (async-inject-multi {} 9)
                                           ["INJECTtest0001" 9]
                                           "multi-arity injection")))))))
              (.then
                (fn [_]
                  (db/without-agent
                    (fn []
                      (db/with-agent
                        "INJECTtest0001"
                        (fn []
                          (assert-resolves
                            (async-inject-multi {:seon.agent/id "OTHERagent0002"})
                            ["OTHERagent0002"] "explicit injection wins")))))))
              (.then
                (fn [_]
                  (let [error (js/Error. "nested rejection")]
                    (-> (with-record-census #(async-reject-outer error))
                        (.then
                          (fn [{::keys [calls] caught ::error}]
                            (is (identical? error caught)
                                "the original rejection reason propagates")
                            (is (= 1 (count calls))
                                "nested async wrappers record one fault")))))))
              (.then
                (fn [_]
                  (-> (with-record-census #(async-fixed -1))
                      (.then
                        (fn [{::keys [calls] caught ::error}]
                          (is (= ":malli.core/invalid-output"
                                 (ex-message caught)))
                          (is (= 1 (count calls))
                              "a resolved-output failure records once"))))))
              (.then
                (fn [_]
                  (let [refreshed
                        (instrument/instrument-delta!
                          {::instrument/changed-syms target-syms
                           ::instrument/targets targets})]
                    (is (true? (::instrument/ok? refreshed)))
                    (is (= (count target-syms)
                           (::instrument/n-instrumented refreshed)))
                    (doseq [sym target-syms]
                      (is (true? (instrument/async-fn? (live-fn sym))))))))
              (.then
                (fn [_]
                  (let [projection
                        (assoc (schema/build-projection {})
                               :seon.schema.projection/function-contracts
                               (into {}
                                     (map (juxt ::instrument/sym
                                                ::instrument/schema-form))
                                     targets))
                        reconciled
                        (instrument/reconcile-projection!
                          {::instrument/old-projection projection
                           ::instrument/new-projection projection})]
                    (is (true? (::instrument/ok? reconciled)))
                    (is (= [] (::instrument/verification-gaps reconciled))))))
              (.then (fn [_]
                       (assert-resolves (async-multi 3 4) [3 4]
                                        "reconciled wrapper remains live")))
              (.then (fn [_] (finish!) (done)))
              (.catch (fn [error]
                        (finish!)
                        (is false (str "async instrumentation matrix threw: "
                                       (or (.-stack error) error)))
                        (done)))))
        (catch :default error
          (finish!)
          (is false (str "async instrumentation setup threw: "
                         (or (.-stack error) error)))
          (done))))))
