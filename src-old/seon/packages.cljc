(ns seon.packages
  "Define package-ledger data, manifests, and pure install plans."
  (:require
   [clojure.string :as str]
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [seon.schema :as schema]))

(schema/register! ::as [:symbol {:seon.db/identity true}])
(schema/register! ::npm [:string {:min 1}])
(schema/register! ::deps
                  [:map-of {:min 1 :max 1} :qualified-symbol :map])
(schema/register! :seon.packages.npm/name [:string {:min 1}])
(schema/register! :seon.packages.npm/range [:string {:min 1}])
(schema/register! :seon.packages.npm/resolved [:string {:min 1}])
(schema/register! :seon.packages.npm/integrity [:string {:min 1}])
(schema/register! :seon.packages.deps/lib :qualified-symbol)
(schema/register! :seon.packages.deps/coord [:string {:min 1}])
(schema/register! ::generation [:int {:min 0}])
(schema/register! ::package :seon.db/ref)
(schema/register! ::corpus-rows [:vector :map])
(schema/register! ::corpus-entity-ids [:vector :int])

(schema/register!
 ::install-request
 [:or
  [:map {:closed true}
   [::npm ::npm]
   [::as ::as]]
  [:map {:closed true}
   [::deps ::deps]
   [::as ::as]]])

(schema/register!
 ::ledger-row
 [:or
  [:map {:closed true}
   [::as ::as]
   [:seon.packages.npm/name :seon.packages.npm/name]
   [:seon.packages.npm/range :seon.packages.npm/range]
   [:seon.packages.npm/resolved
    {:optional true} :seon.packages.npm/resolved]
   [:seon.packages.npm/integrity
    {:optional true} :seon.packages.npm/integrity]
   [::generation {:optional true} ::generation]]
  [:map {:closed true}
   [::as ::as]
   [:seon.packages.deps/lib :seon.packages.deps/lib]
   [:seon.packages.deps/coord :seon.packages.deps/coord]
   [::generation {:optional true} ::generation]]])

(schema/register! ::rows [:vector ::ledger-row])
(schema/register! ::host
                  [:enum :seon.packages.host/bun
                   :seon.packages.host/jvm])
(schema/register! ::rule :qualified-keyword)
(schema/register!
 ::steering-error
 [:map {:closed true}
  [:seon/error
   [:map {:closed true}
    [:seon.error/kind [:= :user-input]]
    [:seon.error/message [:string {:min 1}]]
    [:seon.error/data
     [:map
      [::rule ::rule]]]]]])
(schema/register! ::converged? :boolean)
(schema/register! ::operation [:enum :install :update :remove])
(schema/register!
 ::tx-data
 [:vector
  [:or
   :map
   [:tuple [:= :db.fn/retractEntity]
    [:or :int [:tuple [:= ::as] ::as]]]]])
(schema/register!
 ::install-plan
 [:map {:closed true}
  [::converged? ::converged?]
  [::operation {:optional true} ::operation]
  [::tx-data ::tx-data]])
(schema/register!
 ::remove-request
 [:map {:closed true}
  [::as ::as]])
(schema/register!
 ::installed-row
 [:or
  [:map
   [::as ::as]
   [::npm ::npm]
   [:seon.packages.npm/resolved
    {:optional true} :seon.packages.npm/resolved]
   [:seon.packages.npm/integrity
    {:optional true} :seon.packages.npm/integrity]
   [::generation {:optional true} ::generation]]
  [:map
   [::as ::as]
   [::deps ::deps]
   [::generation {:optional true} ::generation]]])

(defn boundary-namespace?
  "True when `value` is a legal package boundary namespace."
  {:malli/schema [:=> [:cat :symbol] :boolean]}
  [value]
  (boolean
   (and (symbol? value)
        (nil? (namespace value))
        (re-matches
         #"seon\.packages\.[A-Za-z_][A-Za-z0-9_-]*(?:\.[A-Za-z_][A-Za-z0-9_-]*)*"
         (name value)))))

(defn js-wrapper-namespace?
  "True when `value` names a cluster-local JavaScript wrapper namespace."
  {:malli/schema [:=> [:cat :symbol] :boolean]}
  [value]
  (boolean
   (and (boundary-namespace? value)
        (str/starts-with? (name value) "seon.packages.js."))))

(defn stamp-corpus-rows
  "Attach one installed package ledger ref to ordinary corpus rows."
  {:malli/schema
   [:=> [:catn [::as ::as] [::corpus-rows ::corpus-rows]] ::corpus-rows]}
  [as corpus-rows]
  (mapv #(assoc % ::package [::as as]) corpus-rows))

(defn- steering-error
  [rule message data]
  {:seon/error
   {:seon.error/kind :user-input
    :seon.error/message message
    :seon.error/data (assoc data ::rule rule)}})

(defn- npm-coordinate
  [spec]
  (let [separator (str/last-index-of spec "@")
        scoped? (str/starts-with? spec "@")
        slash (str/index-of spec "/")
        split? (and (some? separator)
                    (if scoped?
                      (and (some? slash) (> separator slash))
                      (pos? separator)))
        package-name (if split? (subs spec 0 separator) spec)
        package-range (if split? (subs spec (inc separator)) "latest")]
    (when (and (not (str/blank? package-name))
               (not (str/blank? package-range)))
      {:seon.packages.npm/name package-name
       :seon.packages.npm/range package-range})))

(declare canonical-edn)

(defn- request-coordinate
  [request]
  (if-let [spec (::npm request)]
    (npm-coordinate spec)
    (let [[lib coord] (first (::deps request))]
      {:seon.packages.deps/lib lib
       :seon.packages.deps/coord (canonical-edn coord)})))

(defn row->host
  "Package host selected by a ledger row's ecosystem attribute."
  {:malli/schema [:=> [:cat ::ledger-row] ::host]}
  [row]
  (if (contains? row :seon.packages.npm/name)
    :seon.packages.host/bun
    :seon.packages.host/jvm))

(defn validate-install
  "Validate one install request against the current package ledger."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::request :map]
          [::rows ::rows]]]
    [:or ::install-request ::steering-error]]}
  [{::keys [request rows]}]
  (cond
    (not (schema/valid-candidate-value? ::install-request request))
    (steering-error
     :seon.packages.rule/request-shape
     "Install exactly one npm or deps coordinate with :seon.packages/as."
     {::request request})

    (not (boundary-namespace? (::as request)))
    (steering-error
     :seon.packages.rule/illegal-as
     ":seon.packages/as must be dotted under the seon.packages. prefix."
     {::as (::as request)})

    (and (::npm request) (nil? (npm-coordinate (::npm request))))
    (steering-error
     :seon.packages.rule/npm-spec
     ":seon.packages/npm must contain a package name and nonblank range."
     {::npm (::npm request)})

    :else
    (let [as (::as request)
          coordinate (request-coordinate request)
          current (some #(when (= as (::as %)) %) rows)
          requested-host (if (::npm request)
                           :seon.packages.host/bun
                           :seon.packages.host/jvm)
          occupying
          (some
           (fn [row]
             (when (and (not= as (::as row))
                        (or (= (:seon.packages.npm/name coordinate)
                               (:seon.packages.npm/name row))
                            (= (:seon.packages.deps/lib coordinate)
                               (:seon.packages.deps/lib row))))
               row))
           rows)]
      (cond
        (and current (not= requested-host (row->host current)))
        (steering-error
         :seon.packages.rule/ecosystem-switch
         "An occupied :seon.packages/as cannot switch ecosystems; remove it first."
         {::as as ::existing-row current})

        occupying
        (steering-error
         :seon.packages.rule/coordinate-collision
         "The package coordinate is already mapped to another boundary namespace."
         {::as as ::occupying-as (::as occupying)})

        :else request))))

(defn- canonical-value
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[key nested]] [key (canonical-value nested)]))
          value)

    (set? value)
    (into (sorted-set-by #(compare (pr-str %1) (pr-str %2)))
          (map canonical-value)
          value)

    (vector? value) (mapv canonical-value value)
    (list? value) (apply list (map canonical-value value))
    :else value))

(defn- canonical-edn
  [value]
  (pr-str (canonical-value value)))

(defn- hex4
  [code]
  (let [hex #?(:clj (Integer/toHexString code)
               :cljs (.toString code 16))]
    (str (apply str (repeat (- 4 (count hex)) "0")) hex)))

(defn- json-string
  [value]
  (str
   "\""
   (apply str
          (map
           (fn [character]
             (case character
               \" "\\\""
               \\ "\\\\"
               \backspace "\\b"
               \formfeed "\\f"
               \newline "\\n"
               \return "\\r"
               \tab "\\t"
               (let [code #?(:clj (int character)
                             :cljs (.charCodeAt character 0))]
                 (if (< code 32)
                   (str "\\u" (hex4 code))
                   (str character)))))
           value))
   "\""))

(defn npm-manifest
  "Byte-stable package.json derived from complete npm ledger rows."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::rows ::rows]
          [:seon.config.packages/trusted-lifecycle-scripts
           [:or [:= :all] [:set :string]]]]]
    :string]}
  [{::keys [rows]
    trusted :seon.config.packages/trusted-lifecycle-scripts}]
  (let [npm-rows (filter #(= :seon.packages.host/bun (row->host %)) rows)
        dependencies
        (sort-by first
                 (map (juxt :seon.packages.npm/name
                            :seon.packages.npm/range)
                      npm-rows))
        installed-names (into #{} (map first) dependencies)
        trusted-names (sort (if (= :all trusted) installed-names trusted))
        object
        (fn [entries]
          (str "{" (str/join "," (map (fn [[key value]]
                                         (str (json-string key) ":"
                                              (json-string value)))
                                       entries)) "}"))]
    (str "{\"dependencies\":" (object dependencies)
         ",\"trustedDependencies\":["
         (str/join "," (map json-string trusted-names)) "]}\n")))

(defn deps-manifest
  "Byte-stable deps.edn derived from complete deps ledger rows."
  {:malli/schema
   [:=> [:cat [:map {:closed true} [::rows ::rows]]] :string]}
  [{::keys [rows]}]
  (let [deps
        (into (sorted-map-by #(compare (str %1) (str %2)))
              (keep
               (fn [row]
                 (when (= :seon.packages.host/jvm (row->host row))
                   [(:seon.packages.deps/lib row)
                    (edn/read-string (:seon.packages.deps/coord row))])))
              rows)]
    (str (canonical-edn {:deps deps}) "\n")))

(defn installed
  "Derived installed-package view from current package ledger rows."
  {:malli/schema [:=> [:cat ::rows] [:vector ::installed-row]]}
  [rows]
  (->> rows
       (map
        (fn [row]
          (let [common (select-keys row [::as ::generation])]
            (if (= :seon.packages.host/bun (row->host row))
              (merge
               common
               {::npm (str (:seon.packages.npm/name row) "@"
                           (:seon.packages.npm/range row))}
               (select-keys row [:seon.packages.npm/resolved
                                 :seon.packages.npm/integrity]))
              (assoc common ::deps
                     {(:seon.packages.deps/lib row)
                      (edn/read-string
                       (:seon.packages.deps/coord row))})))))
       (sort-by (comp str ::as))
       vec))

(defn plan-install
  "Plan ledger tx-data for one admitted package install request."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::request :map]
          [::rows ::rows]
          [::corpus-rows {:optional true} ::corpus-rows]
          [:seon.config.packages/policy
           {:optional true} [:enum :closed :allowlist :open]]
          [:seon.config.packages/allowlist
           {:optional true} [:set [:or :string :qualified-symbol]]]
          [:seon.config.packages/max-rows
           {:optional true} [:int {:min 1}]]]]
    [:or ::install-plan ::steering-error]]}
  [{::keys [request rows corpus-rows]
    policy :seon.config.packages/policy
    allowlist :seon.config.packages/allowlist
    max-rows :seon.config.packages/max-rows}]
  (let [policy (or policy :open)
        allowlist (or allowlist #{})
        max-rows (or max-rows 256)
        validated (validate-install {::request request ::rows rows})]
    (cond
      (:seon/error validated) validated

      (= :closed policy)
      (steering-error
       :seon.packages.rule/policy-closed
       "Package installation is closed by :seon.config.packages/policy."
       {::config-key :seon.config.packages/policy})

      :else
      (let [coordinate (request-coordinate request)
            admitted-value (or (:seon.packages.npm/name coordinate)
                               (:seon.packages.deps/lib coordinate))
            current (some #(when (= (::as request) (::as %)) %) rows)
            row (assoc coordinate ::as (::as request))]
        (cond
          (and (= :allowlist policy)
               (not (contains? allowlist admitted-value)))
          (steering-error
           :seon.packages.rule/not-allowlisted
           "The package coordinate is absent from :seon.config.packages/allowlist."
           {::config-key :seon.config.packages/allowlist})

          (and (nil? current) (>= (count rows) max-rows))
          (steering-error
           :seon.packages.rule/max-rows
           "The package ledger reached :seon.config.packages/max-rows."
           {::config-key :seon.config.packages/max-rows})

          (and (= (select-keys current (keys row)) row)
               (empty? corpus-rows))
          {::converged? true ::tx-data []}

          :else
          {::converged? false
           ::operation (if current :update :install)
           ::tx-data (into [row]
                           (stamp-corpus-rows (::as request)
                                              (or corpus-rows [])))})))))

(defn plan-remove
  "Plan ledger tx-data that removes one boundary namespace."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::request ::remove-request]
          [::rows ::rows]
          [::corpus-entity-ids {:optional true} ::corpus-entity-ids]]]
    [:or ::install-plan ::steering-error]]}
  [{::keys [request rows corpus-entity-ids]}]
  (let [as (::as request)]
    (cond
      (not (boundary-namespace? as))
      (steering-error
       :seon.packages.rule/illegal-as
       ":seon.packages/as must be dotted under the seon.packages. prefix."
       {::as as})

      (not-any? #(= as (::as %)) rows)
      {::converged? true ::tx-data []}

      :else
      {::converged? false
       ::operation :remove
       ::tx-data (into (mapv (fn [entity-id]
                               [:db.fn/retractEntity entity-id])
                             (or corpus-entity-ids []))
                       [[:db.fn/retractEntity [::as as]]])})))
