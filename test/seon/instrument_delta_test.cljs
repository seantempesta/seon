(ns seon.instrument-delta-test
  "Behavioral proof for exact boot and incremental instrumentation data."
  (:require
    [cljs.test :refer [deftest is testing]]
    [goog.object :as gobj]
    [malli.core :as m]
    [seon.agent.loop :as agent-loop]
    [seon.client :as client]
    [seon.db :as db]
    [seon.instrument :as instrument]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]))

(defn probe-a [x] x)
(defn probe-b [x] x)
(defn probe-multi
  ([x] x)
  ([x y] [x y]))
(defn probe-variadic
  ([x] [x])
  ([x y & more] (into [x y] more)))
(defn probe-pure-variadic
  [& args]
  (if (= 1 (count args)) (first args) (vec args)))

(def ^:private a-sym 'seon.instrument-delta-test/probe-a)
(def ^:private b-sym 'seon.instrument-delta-test/probe-b)
(def ^:private multi-sym 'seon.instrument-delta-test/probe-multi)
(def ^:private variadic-sym 'seon.instrument-delta-test/probe-variadic)
(def ^:private pure-variadic-sym
  'seon.instrument-delta-test/probe-pure-variadic)
(def ^:private target-syms #{a-sym b-sym})
(def ^:private multi-target-syms #{multi-sym variadic-sym})

(def ^:private multi-form
  [:function
   [:=> [:cat :int] :int]
   [:=> [:cat :int :int] [:tuple :int :int]]])

(def ^:private variadic-form
  [:function
   [:=> [:cat :int] [:vector :int]]
   [:=> [:cat :int :int [:* :int]] [:vector :int]]])

(def ^:private pure-variadic-form
  [:function
   [:=> [:cat :int] :int]
   [:=> [:cat :int :int [:* :int]] [:vector :int]]])

(defn- target [sym schema-form]
  {::instrument/sym sym ::instrument/schema-form schema-form})

(defn- trapped? [thunk]
  (try
    (thunk)
    false
    (catch :default _ true)))

(defn- live-fn [sym]
  (instrument/find-js-var (symbol (namespace sym)) (symbol (name sym))))

(defn- set-live-fn! [sym f]
  (let [ns-object (js/goog.getObjectByName
                    (cljs.core/munge (namespace sym)) js/goog.global)]
    (gobj/set ns-object (cljs.core/munge (name sym)) f)))

(defn- fixed-accessor [sym arity]
  (gobj/get (live-fn sym)
            (str "cljs$core$IFn$_invoke$arity$" arity)))

(defn- variadic-accessor [sym]
  (gobj/get (live-fn sym) "cljs$core$IFn$_invoke$arity$variadic"))

(deftest node-reload-selection-retains-the-replaced-namespaces
  (let [reloaded-namespaces (deref #'client/shadow-reloaded-namespaces)
        message
        {:info
         {:compiled #{"compiled.cljs" "never.cljs"}
          :sources [{:ns 'example.compiled :resource-id "compiled.cljs"}
                    {:ns 'example.always :resource-id "unchanged.cljs"}
                    {:ns 'example.never :resource-id "never.cljs"}
                    {:ns 'example.untouched :resource-id "untouched.cljs"}]}
         :reload-info
         {:always-load #{'example.always}
          :never-load #{'example.never}}}]
    (is (= #{'example.compiled 'example.always}
           (reloaded-namespaces message))
        "instrument exactly the namespaces the Shadow Node client reloaded")))

(deftest shadow-publication-closes-before-build-and-does-not-rearm-on-failure
  (let [!effects (atom [])]
    (with-redefs [db/attached? (constantly true)
                  admission/begin-publication!
                  (fn [] (swap! !effects conj :close) true)
                  admission/mark-unavailable!
                  (fn [_] (swap! !effects conj :failed) true)
                  admission/publish-committed!
                  (fn []
                    (swap! !effects conj :publish)
                    {::admission/published? false})
                  agent-loop/install-ticker!
                  (fn [] (swap! !effects conj :ticker))]
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-failure})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (is (= [:close :failed] @!effects)
          "a failed generation never publishes or rearms autonomous work"))))

(deftest exact-data-and-delta-refresh-only-affected-wrappers
  (let [function-schemas-before (m/function-schemas :cljs)
        initial [(target a-sym [:=> [:cat :int] :int])
                 (target b-sym [:=> [:cat :int] :int])]]
    (try
      (testing "cold instrumentation uses the complete supplied set"
        (let [result (instrument/instrument-targets! initial)]
          (when (:seon.instrument/enabled? result)
            (is (= target-syms (:seon.instrument/accepted-syms result)))
            (is (= 2 (:seon.instrument/n-instrumented result)))
            (is (= [] (:seon.instrument/rejected result)))
            (is (= 1 (probe-a 1)))
            (is (trapped? #(probe-a "wrong")))
            (is (trapped? #(probe-b "wrong"))))))

      (when (instrument/enabled?)
        (let [a-before (live-fn a-sym)
              b-before (live-fn b-sym)
              a-original (gobj/get a-before "malli$instrument$original")]
          (testing "one changed contract is refreshed without touching peers"
            (is (some? a-original))
            (is (nil? (gobj/get a-original "malli$instrument$original"))
                "wrapper depth is exactly one")
            (let [result
                  (instrument/instrument-delta!
                    {::instrument/changed-syms #{a-sym}
                     ::instrument/targets
                     [(target a-sym [:=> [:cat :string] :string])]})]
              (is (= 1 (:seon.instrument/n-unstrumented result)))
              (is (= 1 (:seon.instrument/n-instrumented result)))
              (is (= "ok" (probe-a "ok")))
              (is (trapped? #(probe-a 1)))
              (is (identical? b-before (live-fn b-sym))
                  "unaffected wrapper identity is stable")
              (is (not (identical? a-before (live-fn a-sym))))))

          (testing "spec removal restores the original function"
            (instrument/instrument-delta!
              {::instrument/changed-syms #{a-sym}
               ::instrument/targets []})
            (is (= :unspecced (probe-a :unspecced)))
            (is (nil? (gobj/get (live-fn a-sym)
                                "malli$instrument$original"))))))

      (testing "explicit data never becomes a second process-global registry"
        (is (= function-schemas-before (m/function-schemas :cljs))))
      (finally
        (instrument/instrument-delta!
          {::instrument/changed-syms target-syms
           ::instrument/targets []})))))

(deftest unresolved-target-is-data-not-a-partial-wrapper
  (try
    (instrument/instrument-targets!
      [(target a-sym [:=> [:cat :int] :int])])
    (let [old-wrapper (live-fn a-sym)
          result
          (instrument/instrument-delta!
            {::instrument/changed-syms #{a-sym}
             ::instrument/targets
             [(target a-sym [:=> [:cat :int] :missing/schema])]})]
      (is (false? (::instrument/ok? result)))
      (is (= #{} (:seon.instrument/accepted-syms result)))
      (is (= 0 (::instrument/n-unstrumented result)))
      (is (= [{:seon.instrument/sym a-sym
               :seon.instrument/reason
               :seon.instrument/unresolvable-schema}]
             (:seon.instrument/rejected result)))
      (when (instrument/enabled?)
        (is (identical? old-wrapper (live-fn a-sym))
            "a rejected generation leaves the prior wrapper untouched")
        (is (= 1 (probe-a 1)))
        (is (trapped? #(probe-a "wrong")))))
    (finally
      (instrument/instrument-delta!
        {::instrument/changed-syms #{a-sym}
         ::instrument/targets []}))))

(deftest arity-mismatch-rejects-before-cold-or-delta-mutation
  (let [incomplete-form [:=> [:cat :int] :int]
        instrumented? (atom false)]
    (try
      (testing "one bad cold candidate prevents every Malli mutation"
        (let [a-before (live-fn a-sym)
              multi-before (live-fn multi-sym)
              multi-arity-1-before (fixed-accessor multi-sym 1)
              multi-arity-2-before (fixed-accessor multi-sym 2)
              result
              (instrument/instrument-targets!
                [(target a-sym [:=> [:cat :int] :int])
                 (target multi-sym incomplete-form)])]
          (when (instrument/enabled?)
            (is (false? (::instrument/ok? result)))
            (is (= 0 (::instrument/n-instrumented result)))
            (is (= ::instrument/arity-mismatch
                   (-> result ::instrument/rejected first
                       ::instrument/reason)))
            (is (identical? a-before (live-fn a-sym)))
            (is (identical? multi-before (live-fn multi-sym)))
            (is (identical? multi-arity-1-before
                            (fixed-accessor multi-sym 1)))
            (is (identical? multi-arity-2-before
                            (fixed-accessor multi-sym 2))))))

      (testing "incomplete fixed and variadic replacements preserve wrappers"
        (let [initial
              (instrument/instrument-targets!
                [(target multi-sym multi-form)
                 (target variadic-sym variadic-form)])]
          (when (instrument/enabled?)
            (is (true? (::instrument/ok? initial)))
            (reset! instrumented? true)
            (let [multi-before (live-fn multi-sym)
                  multi-arity-1-before (fixed-accessor multi-sym 1)
                  multi-arity-2-before (fixed-accessor multi-sym 2)
                  variadic-before (live-fn variadic-sym)
                  variadic-arity-1-before (fixed-accessor variadic-sym 1)
                  variadic-before* (variadic-accessor variadic-sym)
                  result
                  (instrument/instrument-delta!
                    {::instrument/changed-syms #{multi-sym variadic-sym}
                     ::instrument/targets
                     [(target multi-sym incomplete-form)
                      (target variadic-sym incomplete-form)]})]
              (is (false? (::instrument/ok? result)))
              (is (= 0 (::instrument/n-unstrumented result)))
              (is (= 0 (::instrument/n-instrumented result)))
              (is (= #{multi-sym variadic-sym}
                     (into #{} (map ::instrument/sym)
                           (::instrument/rejected result))))
              (is (every? #(= ::instrument/arity-mismatch
                              (::instrument/reason %))
                          (::instrument/rejected result)))
              (is (identical? multi-before (live-fn multi-sym)))
              (is (identical? multi-arity-1-before
                              (fixed-accessor multi-sym 1)))
              (is (identical? multi-arity-2-before
                              (fixed-accessor multi-sym 2)))
              (is (identical? variadic-before (live-fn variadic-sym)))
              (is (identical? variadic-arity-1-before
                              (fixed-accessor variadic-sym 1)))
              (is (identical? variadic-before*
                              (variadic-accessor variadic-sym)))
              (is (= [1 2] (probe-multi 1 2)))
              (is (= [1 2 3] (probe-variadic 1 2 3)))
              (is (trapped? #(probe-multi "wrong")))
              (is (trapped? #(probe-variadic "wrong")))))))
      (finally
        (when @instrumented?
          (instrument/instrument-delta!
            {::instrument/changed-syms multi-target-syms
             ::instrument/targets []}))))))

(deftest complete-fixed-and-variadic-contracts-survive-reinstrumentation
  (when (instrument/enabled?)
    (let [initial
          (instrument/instrument-targets!
            [(target multi-sym multi-form)
             (target variadic-sym variadic-form)])]
      (is (true? (::instrument/ok? initial)))
      (when (::instrument/ok? initial)
        (try
          (dotimes [_ 3]
            (let [result
                  (instrument/instrument-delta!
                    {::instrument/changed-syms multi-target-syms
                     ::instrument/targets
                     [(target multi-sym multi-form)
                      (target variadic-sym variadic-form)]})]
              (is (true? (::instrument/ok? result)))
              (is (= 2 (::instrument/n-unstrumented result)))
              (is (= 2 (::instrument/n-instrumented result)))
              (is (fn? (fixed-accessor multi-sym 1)))
              (is (fn? (fixed-accessor multi-sym 2)))
              (is (fn? (fixed-accessor variadic-sym 1)))
              (is (fn? (variadic-accessor variadic-sym)))
              (is (= 1 (probe-multi 1)))
              (is (= [1 2] (probe-multi 1 2)))
              (is (= [1] (probe-variadic 1)))
              (is (= [1 2 3 4] (probe-variadic 1 2 3 4)))
              (is (trapped? #(probe-multi "wrong")))
              (is (trapped? #(probe-variadic "wrong")))))
          (finally
            (instrument/instrument-delta!
              {::instrument/changed-syms multi-target-syms
               ::instrument/targets []})))))))

(deftest pure-variadic-implementation-may-have-a-stricter-public-contract
  (when (instrument/enabled?)
    (let [result
          (instrument/instrument-targets!
            [(target pure-variadic-sym pure-variadic-form)])]
      (try
        (is (true? (::instrument/ok? result)))
        (is (= 1 (::instrument/n-instrumented result)))
        (is (= 7 (probe-pure-variadic 7)))
        (is (= [1 2 3] (probe-pure-variadic 1 2 3)))
        (is (trapped? #(probe-pure-variadic)))
        (is (trapped? #(probe-pure-variadic "wrong")))
        (finally
          (instrument/instrument-delta!
            {::instrument/changed-syms #{pure-variadic-sym}
             ::instrument/targets []}))))))

(deftest cold-instrumentation-wraps-live-vars-and-reports-absent-vars
  (when (instrument/enabled?)
    (try
      (let [missing-sym 'instrumenttest.contract/not-compiled
            result
            (instrument/instrument-targets!
              [(target a-sym [:=> [:cat :int] :int])
               (target missing-sym [:=> [:cat :int] :int])])]
        (is (true? (::instrument/ok? result)))
        (is (= 1 (::instrument/n-instrumented result)))
        (is (= [{::instrument/sym missing-sym
                 ::instrument/reason ::instrument/no-var}]
               (::instrument/rejected result)))
        (is (trapped? #(probe-a "wrong"))))
      (finally
        (instrument/instrument-delta!
          {::instrument/changed-syms #{a-sym}
           ::instrument/targets []})))))

(deftest schema-change-refreshes-only-transitive-function-dependents
  (let [a-form [:=> [:cat :instrumenttest.contract/root]
                :instrumenttest.contract/root]
        b-form [:=> [:cat :int] :int]
        function-contracts {a-sym a-form b-sym b-form}
        old-projection
        (schema/build-projection
          {:instrumenttest.contract/leaf :int
           :instrumenttest.contract/root
           [:vector :instrumenttest.contract/leaf]}
          function-contracts)
        new-projection
        (schema/build-projection
          {:instrumenttest.contract/leaf :string
           :instrumenttest.contract/root
           [:vector :instrumenttest.contract/leaf]}
          function-contracts)
        registry (:seon.schema.projection/registry old-projection)]
    (try
      (instrument/instrument-targets!
        [{::instrument/sym a-sym ::instrument/schema-form a-form
          ::instrument/registry registry}
         {::instrument/sym b-sym ::instrument/schema-form b-form
          ::instrument/registry registry}])
      (when (instrument/enabled?)
        (let [a-before (live-fn a-sym)
              b-before (live-fn b-sym)
              result
              (instrument/instrument-projection-delta!
                {::instrument/old-projection old-projection
                 ::instrument/new-projection new-projection
                 ::instrument/changed-schema-keys
                 #{:instrumenttest.contract/leaf}
                 ::instrument/changed-syms #{}})]
          (is (true? (::instrument/ok? result)))
          (is (= 1 (::instrument/n-dependent result)))
          (is (= 1 (::instrument/n-instrumented result)))
          (is (not (identical? a-before (live-fn a-sym))))
          (is (identical? b-before (live-fn b-sym)))
          (is (= ["new"] (probe-a ["new"])))
          (is (trapped? #(probe-a [1])))))
      (finally
        (instrument/instrument-delta!
          {::instrument/changed-syms target-syms
           ::instrument/targets []})))))
