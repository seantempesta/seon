(ns seon.test.bootstrap
  "Namespace bootstrap POC.
  Proves: seed + schema with :default/fn = self-wiring system.
  Entry order in the :map = dependency order.
  One decode call bootstraps everything from a minimal seed.

  Phase 2: Transparent injection via decode-based instrumentation,
  interesting domain functions with emergent data flow, data-driven
  function routing with feedback loops."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.transform :as mt]
            [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Part 1: Dependent Default Transformer
;;
;; Standard Malli default-value-transformer calls (f) with no args.
;; We need (f acc) so later entries see earlier resolved values.
;; ---------------------------------------------------------------------------

(defn dependent-default-transformer
  "Like mt/default-value-transformer but :default/fn receives the
  accumulating map, so later entries can depend on earlier ones.
  Entry order in the [:map ...] = resolution order."
  []
  (mt/transformer
   {:decoders
    {:map
     {:compile
      (fn [schema _]
        (let [entries (m/children schema)
              default-fns (into []
                            (keep (fn [[k {:keys [optional] :as props} v]]
                              (let [dfn (or (some-> props :default/fn m/eval)
                                            (some-> (m/properties v) :default/fn m/eval))
                                    dval (or (some-> props (find :default))
                                             (some-> (m/properties v) (find :default)))]
                                (cond
                                  dfn  [k :fn dfn]
                                  dval [k :val (val dval)]
                                  :else nil))))
                            entries)]
          (when (seq default-fns)
            (fn [x]
              (if (map? x)
                (reduce (fn [acc [k kind v]]
                          (if (contains? acc k)
                            acc
                            (case kind
                              :fn  (assoc acc k (v acc))
                              :val (assoc acc k v))))
                        x default-fns)
                x)))))}}}))

;; ---------------------------------------------------------------------------
;; Part 2: Schemas — all :: (namespace-local)
;; ---------------------------------------------------------------------------

;; Infrastructure
(schema/register! ::ns-key :keyword)
(schema/register! ::dir :string)
(schema/register! ::resume? :boolean)
(schema/register! ::conn [:fn d/conn?])
(schema/register! ::closed :boolean)
(schema/register! ::persisted :boolean)
(schema/register! ::wired :boolean)
(schema/register! ::volume :double)
(schema/register! ::sets :int)

;; Domain (workout tracker as POC domain)
(schema/register! ::exercise :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::workout-set
  [:map [::exercise ::exercise] [::weight ::weight] [::reps ::reps]])
(schema/register! ::workouts [:vector ::workout-set])
(schema/register! ::screen [:enum :home :active :history])
(schema/register! ::ctx
  [:map [::screen ::screen] [::workouts ::workouts]])

;; ---------------------------------------------------------------------------
;; Part 3: Infrastructure Functions — map-in/map-out, fully specced
;; ---------------------------------------------------------------------------

(defn create-conn!
  "Create a local Datalevin connection for this namespace's storage."
  {:malli/schema [:=> [:cat [:map [::dir ::dir]]] [:map [::conn ::conn]]]}
  [{::keys [dir]}]
  {::conn (d/get-conn dir)})

(defn close-conn!
  "Close a Datalevin connection."
  {:malli/schema [:=> [:cat [:map [::conn ::conn]]] [:map [::closed :boolean]]]}
  [{::keys [conn]}]
  (d/close conn)
  {::closed true})

(defn persist-ctx!
  "Persist serializable ctx data to Datalevin, keyed by ns-key."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::ctx ::ctx]]]
                      [:map [::persisted :boolean]]]}
  [{::keys [conn ns-key ctx]}]
  (let [safe (into {} (filter (fn [[_ v]]
                                (try (edn/read-string (pr-str v)) true
                                     (catch Exception _ false)))
                              ctx))]
    (d/transact! conn [{:seon.ctx/namespace (pr-str ns-key)
                        :seon.ctx/data (pr-str safe)}])
    {::persisted true}))

(defn restore-ctx
  "Restore ctx from Datalevin for a given ns-key. Returns {::ctx ...} or {}."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]]]
                      [:map [::ctx {:optional true} ::ctx]]]}
  [{::keys [conn ns-key]}]
  (let [stored (d/q '[:find ?data .
                       :in $ ?ns
                       :where [?e :seon.ctx/namespace ?ns]
                              [?e :seon.ctx/data ?data]]
                     (d/db conn) (pr-str ns-key))]
    (if stored
      {::ctx (edn/read-string stored)}
      {})))

(defn init-ctx!
  "Init ctx: if resume? and data exists in Datalevin, merge over defaults."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key]
                                  [::resume? ::resume?]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [conn ns-key resume?] :as m}]
  (let [defaults {::screen :home ::workouts []}
        persisted (when resume? (::ctx (restore-ctx m)))]
    {::ctx (merge defaults persisted)}))

;; ---------------------------------------------------------------------------
;; Part 4: System Schema — seed + this = full wiring
;; Entry order = dependency order. No explicit init function.
;; ---------------------------------------------------------------------------

(schema/register! ::system
  [:map
   [::ns-key ::ns-key]
   [::dir {:default/fn (fn [m] (str "tmp/" (name (::ns-key m))))} ::dir]
   [::resume? {:default false} ::resume?]
   [::conn {:default/fn (fn [m] (::conn (create-conn! m)))} ::conn]
   [::ctx {:default/fn (fn [m] (::ctx (init-ctx! m)))} ::ctx]])

(def seed {::ns-key ::bootstrap})

(schema/register! ::bootstrap-request
  [:map [::seed {:optional true} [:map [::ns-key ::ns-key]]]])

(defn bootstrap!
  "Bootstrap a system from a seed map."
  {:malli/schema [:=> [:cat ::bootstrap-request] ::system]}
  [{:keys [seed] :or {seed seed}}]
  (m/decode ::system seed (dependent-default-transformer)))

;; ---------------------------------------------------------------------------
;; Part 5: Domain Functions — pure data-in/data-out
;; ---------------------------------------------------------------------------

(defn total-volume
  "Calculate total training volume from ctx. Includes set count."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]] [:map [::volume :double] [::sets :int]]]}
  [{::keys [ctx]}]
  {::volume (->> (::workouts ctx)
                 (reduce (fn [acc w] (+ acc (* (::weight w) (::reps w)))) 0.0))
   ::sets (count (::workouts ctx))})

(defn add-workout!
  "Add a workout set to ctx. Returns updated ctx."
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx] [::exercise ::exercise]
                                  [::weight ::weight] [::reps ::reps]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [ctx exercise weight reps]}]
  {::ctx (update ctx ::workouts conj
                 {::exercise exercise ::weight weight ::reps reps})})

;; ---------------------------------------------------------------------------
;; Part 6: Atom workspace — defonce survives reload, watch persists
;; ---------------------------------------------------------------------------

(defonce *state (atom nil))

(defn wire-atom!
  "Wire the atom to a live system — reset with ctx, add persist watch."
  {:malli/schema [:=> [:cat [:map [::conn ::conn] [::ns-key ::ns-key] [::ctx ::ctx]]]
                      [:map [::wired :boolean]]]}
  [{::keys [conn ns-key ctx]}]
  (reset! *state ctx)
  (remove-watch *state ::persist)
  (add-watch *state ::persist
    (fn [_ _ old new]
      (when (not= old new)
        (persist-ctx! {::conn conn ::ns-key ns-key ::ctx new}))))
  {::wired true})

;; ---------------------------------------------------------------------------
;; Part 7: Transparent Injection via Instrumentation
;;
;; Wrap function vars so callers don't need to provide injectable keys.
;; The wrapper reads :malli/schema, decodes input with defaults from *state,
;; calls the original, and applies ctx updates back to the atom.
;; ---------------------------------------------------------------------------

(defonce ^:private *originals (atom {}))

(defn- extract-input-schema-form
  "Extract the input map schema form from a function's :malli/schema.
   [:=> [:cat INPUT] OUTPUT] -> INPUT form"
  [fn-var]
  (let [schema-form (-> fn-var meta :malli/schema)]
    (when (and (vector? schema-form) (= :=> (first schema-form)))
      (let [cat-form (second schema-form)]
        (when (and (vector? cat-form) (= :cat (first cat-form)))
          (second cat-form))))))

(defn- add-ctx-default
  "Given an input schema form like [:map [::ctx ::ctx] ...],
   return a new form where ::ctx entries get :default/fn that reads @*state."
  [schema-form]
  (if (and (vector? schema-form) (= :map (first schema-form)))
    (into [:map]
          (map (fn [entry]
                 (if (and (vector? entry) (= ::ctx (first entry)))
                   ;; Add :default/fn to the ctx entry props
                   (let [[k & rest-entry] entry
                         has-props? (map? (first rest-entry))
                         props (if has-props? (first rest-entry) {})
                         schema-ref (if has-props? (second rest-entry) (first rest-entry))]
                     [k (assoc props :default/fn (fn [_] @*state)) schema-ref])
                   entry)))
          (rest schema-form))
    schema-form))

(defn instrument-with-decode!
  "Wrap a function var so callers don't need to provide ::ctx.
   The wrapper decodes input (filling ::ctx from *state), calls the original,
   and if the result contains ::ctx, updates *state. Returns result sans ::ctx."
  [{::keys [fn-var]}]
  (let [original @fn-var
        input-form (extract-input-schema-form fn-var)
        injectable-form (add-ctx-default input-form)]
    (swap! *originals assoc fn-var original)
    (alter-var-root fn-var
      (fn [_]
        (fn [args]
          (let [decoded (m/decode injectable-form args (dependent-default-transformer))
                result (original decoded)]
            (when-let [new-ctx (::ctx result)]
              (reset! *state new-ctx))
            (dissoc result ::ctx)))))))

(defn uninstrument!
  "Restore all instrumented functions to their originals."
  []
  (doseq [[v orig] @*originals]
    (alter-var-root v (constantly orig)))
  (reset! *originals {}))

;; ---------------------------------------------------------------------------
;; Part 8: Tests
;; ---------------------------------------------------------------------------

(deftest dependent-default-transformer-test
  (testing "Later entries see earlier resolved values"
    (let [s [:map
             [:a {:default 1} :int]
             [:b {:default/fn '(fn [m] (inc (:a m)))} :int]
             [:c {:default/fn '(fn [m] (+ (:a m) (:b m)))} :int]]
          result (m/decode s {} (dependent-default-transformer))]
      (is (= 1 (:a result)))
      (is (= 2 (:b result)))
      (is (= 3 (:c result))))))

(deftest transparent-injection-test
  (let [system (bootstrap! {})]
    (try
      (wire-atom! system)
      (instrument-with-decode! {::fn-var #'total-volume})
      (instrument-with-decode! {::fn-var #'add-workout!})

      (testing "Call total-volume with empty map — ctx injected from *state"
        (let [r (total-volume {})]
          (is (= 0.0 (::volume r)))
          (is (= 0 (::sets r)))))

      (testing "add-workout! with just data keys — ctx injected, state updated"
        (add-workout! {::exercise "Squat" ::weight 100.0 ::reps 5})
        (is (= 1 (count (::workouts @*state))) "State should be updated"))

      (testing "total-volume sees updated state"
        (let [r (total-volume {})]
          (is (= 500.0 (::volume r)))
          (is (= 1 (::sets r)))))

      (testing "Multiple workouts accumulate"
        (add-workout! {::exercise "Bench" ::weight 60.0 ::reps 8})
        (let [r (total-volume {})]
          (is (= 980.0 (::volume r)))
          (is (= 2 (::sets r)))))

      (finally
        (uninstrument!)
        (remove-watch *state ::persist)
        (close-conn! system)))))

(deftest fresh-bootstrap-test
  (let [system (bootstrap! {})]
    (try
      (testing "System bootstrapped from minimal seed"
        (is (d/conn? (::conn system)))
        (is (= :home (-> system ::ctx ::screen)))
        (is (= [] (-> system ::ctx ::workouts))))

      (testing "Domain functions work with bootstrapped system"
        (let [r (total-volume {::ctx (::ctx system)})]
          (is (= 0.0 (::volume r)))))

      (testing "Add workout and check volume"
        (let [r (add-workout! {::ctx (::ctx system)
                               ::exercise "Squat"
                               ::weight 100.0
                               ::reps 5})
              v (total-volume r)]
          (is (= 500.0 (::volume v)))
          (is (= 1 (count (-> r ::ctx ::workouts))))))

      (finally (close-conn! system)))))

(deftest persist-and-resume-test
  (let [dir "tmp/bootstrap-resume-test"
        sys1 (bootstrap! {:seed {::ns-key ::bootstrap ::dir dir}})]
    (try
      (testing "Persist ctx"
        (let [updated-ctx (::ctx (add-workout! {::ctx (::ctx sys1)
                                                ::exercise "Squat"
                                                ::weight 100.0
                                                ::reps 5}))]
          (persist-ctx! {::conn (::conn sys1)
                         ::ns-key (::ns-key sys1)
                         ::ctx updated-ctx})))
      (close-conn! sys1)

      (testing "Resume restores from Datalevin"
        (let [sys2 (bootstrap! {:seed {::ns-key ::bootstrap
                                       ::dir dir
                                       ::resume? true}})]
          (try
            (is (= 1 (count (-> sys2 ::ctx ::workouts))))
            (is (= "Squat" (-> sys2 ::ctx ::workouts first ::exercise)))
            (finally (close-conn! sys2)))))
      (catch Exception e
        (throw e)))))

(deftest atom-wire-test
  (let [system (bootstrap! {})]
    (try
      (testing "Wire atom and mutate"
        (wire-atom! system)
        (is (= :home (::screen @*state)))

        ;; Simulate agent modifying state
        (swap! *state update ::workouts conj
               {::exercise "Deadlift" ::weight 180.0 ::reps 3})

        ;; Watch fires synchronously on swap! thread — no sleep needed
        (let [{::keys [ctx]} (restore-ctx system)]
          (is (some? ctx) "State should be persisted by watch")
          (is (= 1 (count (::workouts ctx))))))
      (finally
        (remove-watch *state ::persist)
        (close-conn! system)))))
