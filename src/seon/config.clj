(ns seon.config
  "The one config system (owner, 2026-10-21: only documented config schemas stay). Every
  limit, bound, dial and default of the platform is a key in one aero file,
  resources/seon/config/default.edn, documented there beside its value (unit, provenance,
  what happens at the bound) and validated by its schema (resources/seon/schemas/
  seon.config*.edn, the `:seon.config/config` map). This namespace is its loader and the
  only place the process environment is read:

  - the checkout (`JOLT_PWD`: bin/env exports it for the binary, `jolt run` callers set it),
    where the file and the checkout-relative paths live;
  - the profile (`SEON_PROFILE`, default `:default`), handed to aero's `#profile`;
  - every override, through aero's `#env` in the file itself, so the file is the one list of
    what can be set.

  The file is read once, when this namespace loads (root's boot: every process of the
  platform is root or forked from it, so agents and the fork server hold the same value and
  read no environment). A value resolving to nil (an unset `#env` with no fallback) is
  absent; the result must satisfy `:seon.config/config` (a closed map: an unknown key, an
  invalid value or a missing required key refuses boot with `:seon.config/refused`). Root
  writes the resolved config as facts on main's root entity (`facts`), platform records a
  merge never carries, where agents, the dashboard and the context read it.

  A foundational namespace, below seon.contract (which reads the guarantees dial here): its
  functions carry contracts in their metadata and are armed at boot."
  (:require [aero.core :as aero] [malli.core :as m] [malli.registry :as mr] [clojure.edn :as edn]
            [clojure.java.io :as io] [clojure.string :as str] [datahike.api :as d]))

;; ---- where: the checkout and the profile (the loader's own two environment reads) ----------

(defn checkout
  "The checkout this process runs from (JOLT_PWD), or the refusal naming it."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [d (System/getenv "JOLT_PWD")]
    (if (str/blank? d)
      (throw (ex-info "JOLT_PWD is not set: the checkout the config file and its paths are read from (bin/env exports it)"
                      {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/checkout
                       :seon.error/message "JOLT_PWD is not set: the checkout the config file and its paths are read from (bin/env exports it)"}))
      d)))

(defn profile
  "The aero profile this process reads the file under: SEON_PROFILE as a keyword, else :default."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  (let [p (System/getenv "SEON_PROFILE")] (if (str/blank? p) :default (keyword p))))

(defn path
  "The config file in checkout dir."
  {:malli/schema [:=> [:cat :string] :string]}
  [dir] (str dir "/resources/seon/config/default.edn"))

;; ---- machine-derived values: resolved at load, never shipped as a literal --------------------

(defmethod aero/reader 'seon/checkout
  ;; #seon/checkout "rel/path": rel/path under the checkout this process runs from
  [opts _tag value]
  (str (::checkout opts) "/" value))

;; ---- the schemas: seon.config*.edn, read when this namespace compiles (as seon.contract does) --

(defmacro ^:private baked-schema-forms
  "The config schema forms (resources/seon/schemas/seon.config*.edn), read when this
  namespace compiles: a built binary carries them."
  []
  (let [dir (io/file (checkout) "resources/seon/schemas")]
    (list 'quote
          (reduce merge {} (for [f (sort-by #(.getName %) (file-seq dir))
                                 :let [n (.getName f)]
                                 :when (and (str/starts-with? n "seon.config") (str/ends-with? n ".edn"))]
                             (edn/read-string (slurp f)))))))

(def schema-forms "The config keys' schema forms, by name." (baked-schema-forms))

(def ^:private registry
  (mr/composite-registry (m/default-schemas) (mr/simple-registry schema-forms)))

(def config-schema "The compiled `:seon.config/config`." (m/deref (m/schema :seon.config/config {:registry registry})))

(defn keys-declared
  "Every key the config may hold, in declaration order."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  [] (mapv first (m/entries config-schema)))

;; ---- resolving -------------------------------------------------------------------------------

(defn refusal
  "The thrown refusal for a config value m that does not satisfy :seon.config/config: one
  problem per refused key (unknown, invalid, missing), each naming the key."
  {:malli/schema [:=> [:cat :map] :seon.config/refused]}
  [m]
  (let [ps (vec (for [{:keys [path in type value]} (:errors (m/explain config-schema m))]
                  {:key (or (first in) (first path))
                   :problem (case type :malli.core/extra-key :unknown-key :malli.core/missing-key :missing-key :invalid)
                   :value (pr-str value)}))]
    {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/resolved
     :seon.error/message (str "the config does not satisfy :seon.config/config: "
                              (str/join ", " (for [{:keys [key problem]} ps] (str key " " (name problem)))))
     :seon.config/problems ps}))

(defn resolved
  "Raw (what aero read) as the config: nil values dropped (absent), then validated against
  :seon.config/config. Answers the map, or throws the refusal."
  {:malli/schema [:=> [:cat :map] :seon.config/config]}
  [raw]
  (let [m (into {} (remove (comp nil? val)) raw)]
    (if (m/validate config-schema m)
      m
      (let [r (refusal m)] (throw (ex-info (:seon.error/message r) r))))))

(defn read-file
  "The config file at p read with aero under profile, the checkout dir resolving
  #seon/checkout: resolved and validated (or the refusal, thrown)."
  {:malli/schema [:=> [:cat :string :keyword :string] :seon.config/config]}
  [p prof dir]
  (resolved (aero/read-config p {:profile prof ::checkout dir})))

(def config
  "This process's config: the checkout's file, read once at load under its profile."
  (let [dir (checkout)] (read-file (path dir) (profile) dir)))

(defn value
  "Key k of the config. A key the schema does not declare, or an optional key that is
  absent, is a defect of the caller: the refusal names it (absent is never nil)."
  {:malli/schema [:=> [:cat :qualified-keyword] :any]}
  [k]
  (let [v (get config k ::absent)]
    (if (= ::absent v)
      (throw (ex-info (str k " is not in the config (declared keys: seon.config/keys-declared)")
                      {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/value
                       :seon.error/message (str k " is not in the config")}))
      v)))

;; ---- the model provider, as the config states it --------------------------------------------

(defn provider
  "The model provider config c names (:seon.config.ai/*): the scripted stand-in, or the
  OpenAI-compatible endpoint with the NAME of its key's environment variable (never the key)."
  {:malli/schema [:=> [:cat :seon.config/config] :seon.model/provider]}
  [c]
  (case (:seon.config.ai/provider c)
    :scripted {:kind :scripted :script (:seon.config.ai/script c)}
    :openai-compat (cond-> {:kind :openai-compat :base-url (:seon.config.ai/base-url c) :model (:seon.config.ai/model c)
                            :api-key-env (:seon.config.ai/api-key-variable c) :max-tokens (:seon.config.ai/max-tokens c)}
                     (:seon.config.ai/thinking c) (assoc :thinking (:seon.config.ai/thinking c))
                     (:seon.config.ai/reasoning-effort c) (assoc :reasoning-effort (:seon.config.ai/reasoning-effort c)))))

(defn with-provider
  "Config c stating provider p (a provider handed to root's start! in place of the file's):
  the facts root writes say what the run really used."
  {:malli/schema [:=> [:cat :seon.config/config :seon.model/provider] :seon.config/config]}
  [c p]
  (let [ai {:seon.config.ai/provider (:kind p) :seon.config.ai/script (:script p) :seon.config.ai/base-url (:base-url p)
            :seon.config.ai/model (:model p) :seon.config.ai/api-key-variable (:api-key-env p)
            :seon.config.ai/max-tokens (:max-tokens p)
            :seon.config.ai/thinking (:thinking p) :seon.config.ai/reasoning-effort (:reasoning-effort p)}]
    (into (dissoc c :seon.config.ai/thinking :seon.config.ai/reasoning-effort) (filter (comp some? val)) ai)))

;; ---- the facts on main ------------------------------------------------------------------------

(defn facts
  "Config c as the facts of main's root entity, written with root's boot fact (platform
  records: never carried by a merge in either direction)."
  {:malli/schema [:=> [:cat :seon.config/config] :map]}
  [c] (assoc c :seon.root/id :root))

(defn secret
  "The value of the environment variable named var-name (a credential, e.g. the model's key,
  named by :seon.config.ai/api-key-variable): read at its one use, never stored, never
  printed. nil when unset."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [var-name] (System/getenv var-name))

(defn from-db
  "The config root wrote on db (main, or any branch made from it since): every declared key
  the root entity holds. What agents, the dashboard and the context read."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :map]]}
  [db]
  (when-let [e (d/q '[:find ?e . :where [?e :seon.root/id :root]] db)]
    (let [r (d/pull db (keys-declared) e)] (when (seq r) r))))
