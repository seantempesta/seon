(ns seon.config
  "The one config system (DECISIONS, Only documented config). Every
  limit, bound and default of the platform is a key in one aero file,
  resources/seon/config/default.edn, documented there beside its value (unit, provenance,
  what happens at the bound) and validated by its schema (resources/seon/schemas/
  seon.config*.edn, the `:seon.config/config` map). This namespace is its loader and the
  only place the process environment is read:

  - the checkout (`SEON_CHECKOUT`, which bin/env exports for the binary, else `JOLT_PWD`,
    which `jolt run` callers set), where the file and the checkout-relative paths live;
  - the profile (`SEON_PROFILE`, default `:default`), handed to aero's `#profile`;
  - overrides: aero's `#env` in the file itself; the command line's `--config FILE` and
    `--set :ns/key=edn` (every mode of the binary; taken out of the mode's args). Later layers
    win, key by key; `sources` names the layer of each key. A checkout's `config/local.edn`
    (gitignored; its ports, and the standing root's source) is no implicit layer: bin/root
    and bin/ports pass it with --config, so a check's or a test's process reads only what its
    command line gives it.

  The file is read once, when this namespace loads (root's boot: every process of the
  platform is root or forked from it, so agents and the fork server hold the same value and
  read no environment). A value resolving to nil (an unset `#env` with no fallback) is
  absent; the result must satisfy `:seon.config/config` (a closed map: an unknown key, an
  invalid value or a missing required key refuses boot with `:seon.config/refused`). Root
  writes the resolved config as facts on main's root entity (`facts`), platform records a
  merge never carries, where agents, the dashboard and the context read it.

  A foundational namespace, below seon.contract (which reads the instrument config key here): its
  functions carry contracts in their metadata and are armed at boot."
  (:refer-clojure :exclude [resolve])
  (:require [seon.edn :as sedn] [aero.core :as aero] [malli.core :as m] [malli.registry :as mr] [clojure.edn :as edn]
            [clojure.java.io :as io] [clojure.string :as str] [datahike.api :as d]))

;; ---- where: the checkout and the profile (the loader's own two environment reads) ----------

(defn checkout
  "The checkout this process runs from: SEON_CHECKOUT (bin/env exports it; the binary runs
  from target/), else JOLT_PWD (a `jolt run` from the checkout), or the error naming both.
  Not JOLT_PWD for the binary: Jolt resolves a child process's relative path against it."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [d (first (remove str/blank? [(System/getenv "SEON_CHECKOUT") (System/getenv "JOLT_PWD")]))]
    (if d
      d
      (let [msg "neither SEON_CHECKOUT nor JOLT_PWD is set: the checkout the config file and its paths are read from (bin/env exports SEON_CHECKOUT)"]
        (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/checkout
                             :seon.error/message msg}))))))

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

;; ---- the schemas: read once, when this namespace compiles --------------------------------

(def schemas-dir
  "Where the platform's schema resources live, relative to the checkout: the files the
  build compiles in, and the schema resources seon.source imports and writes back."
  "resources/seon/schemas")

(defmacro ^:private baked-schema-forms
  "Every named schema form of the platform: the maps in resources/seon/schemas/*.edn, read
  when this namespace compiles (the build host has the directory; a built binary carries the
  forms, and bakes no resource files of this project's own paths)."
  []
  (let [dir (io/file (checkout) schemas-dir)]
    (list 'quote
          (reduce merge {} (for [f (sort-by #(.getName %) (file-seq dir))
                                 :when (str/ends-with? (.getName f) ".edn")]
                             (edn/read-string (slurp f)))))))

(def schema-forms
  "Every named schema form of the platform, by name: the config keys' among them, and the
  one baked map seon.contract/forms is."
  (baked-schema-forms))

(def ^:private registry
  (mr/composite-registry (m/default-schemas) (mr/simple-registry schema-forms)))

(def config-schema "The compiled `:seon.config/config`." (m/deref (m/schema :seon.config/config {:registry registry})))

(defn keys-declared
  "Every key the config may hold, in declaration order."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  [] (mapv first (m/entries config-schema)))

;; ---- resolving -------------------------------------------------------------------------------

(defn invalid
  "The thrown error for a config value m that does not satisfy :seon.config/config: one
  problem per refused key (unknown, invalid, missing), each naming the key."
  {:malli/schema [:=> [:cat :map] :seon.config/refused]}
  [m]
  (let [ps (vec (for [{:keys [path in type value]} (:errors (m/explain config-schema m))]
                  {:key (or (first in) (first path))
                   :problem (case type :malli.core/extra-key :unknown-key :malli.core/missing-key :missing-key :invalid)
                   :value (sedn/encode value)}))]
    {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/resolved
     :seon.error/message (str "the config does not satisfy :seon.config/config: "
                              (str/join ", " (for [{:keys [key problem]} ps] (str key " " (name problem)))))
     :seon.config/problems ps}))

(defn- refuse!
  {:malli/schema [:=> [:cat :qualified-symbol :string] :nil]}
  [op msg] (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation op :seon.error/message msg})))

(defn resolve-in
  "Setting k in config c (its layer sources srcs) for ctx {:task m :model id}: the task's or
  agent's own settings (a :seon.task/config map), then the model's entry (default
  :seon.config.ai/model), then that model's provider's, then c's own key. Answers {:value
  :source}, :source one of [:task], [:model id], [:provider id], [:config layer]. A key set
  nowhere is a defect of the caller: the error names it (absent is never nil)."
  {:malli/schema [:=> [:cat :map :map :qualified-keyword :seon.config/context] :seon.config/resolved]}
  [c srcs k {:keys [task model]}]
  (let [m (or model (:seon.config.ai/model c))
        me (get-in c [:seon.config.ai/models m])
        p (:seon.ai.model/provider me)
        pe (get-in c [:seon.config.ai/providers p])]
    (cond
      (contains? task k) {:value (get task k) :source [:task]}
      (contains? me k) {:value (get me k) :source [:model m]}
      (contains? pe k) {:value (get pe k) :source [:provider p]}
      (contains? c k) {:value (get c k) :source [:config (get srcs k "resources/seon/config/default.edn")]}
      :else (refuse! 'seon.config/resolve (str k " is set nowhere: not by the task, the model " m ", its provider "
                                               p " or the config (declared keys: seon.config/keys-declared)")))))

(def ^:private reasoning-pair (m/schema :seon.config.ai/reasoning-pair {:registry registry}))

(defn- invalid-pairs
  "Each model of config m whose thinking and reasoning replay (as the resolver reads them for
  it, task -> model -> provider -> config) do not satisfy :seon.config.ai/reasoning-pair: its
  key and m/explain's errors."
  {:malli/schema [:=> [:cat :map] [:vector :seon.config/problem]]}
  [m]
  (vec (for [id (sort (keys (:seon.config.ai/models m)))
             :let [me (get-in m [:seon.config.ai/models id])
                   layers [me (get-in m [:seon.config.ai/providers (:seon.ai.model/provider me)]) m]
                   ;; a key no layer sets is absent (thinking: the provider's default)
                   pair (into {} (for [k [:seon.config.ai/thinking :seon.config.ai/replay-reasoning]
                                       :when (some #(contains? % k) layers)]
                                   [k (:value (resolve-in m {} k {:model id}))]))
                   e (m/explain reasoning-pair pair)]
             :when e]
         {:key [:seon.config.ai/models id] :problem :invalid
          :value (str (sedn/encode pair) ": " (:description (m/properties reasoning-pair)) " " (sedn/encode (mapv #(select-keys % [:path :value]) (:errors e))))})))

(defn resolved
  "Raw (what aero read) as the config: nil values dropped (absent), then validated against
  :seon.config/config, and each model's thinking with its reasoning replay against
  :seon.config.ai/reasoning-pair (invalid-pairs). Answers the map, or throws the error."
  {:malli/schema [:=> [:cat :map] :seon.config/config]}
  [raw]
  (let [m (into {} (remove (comp nil? val)) raw)]
    (if-not (m/validate config-schema m)
      (let [r (invalid m)] (throw (ex-info (:seon.error/message r) r)))
      (let [ps (invalid-pairs m)]
        (if (empty? ps)
          m
          (let [msg (str "the config does not satisfy :seon.config.ai/reasoning-pair: " (str/join ", " (map (comp pr-str :key) ps)))]
            (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation 'seon.config/resolved
                                 :seon.error/message msg :seon.config/problems ps}))))))))

;; ---- the layers: the shipped file, the checkout's own file, the command line ------------------
;; Owner: "Hand out different edn config files that each agent can load and control
;; separately or do command line overrides." Later layers win, key by key; the merged map is
;; validated once, and every key remembers the layer it came from (`sources`).

(defn argv
  "This process's command line, as *command-line-args* holds it while namespaces load (a
  `jolt run`, and a built binary since Jolt's jolt/argv): the one place the loader reads it,
  so --config and --set apply to every mode."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  [] (vec *command-line-args*))

(defn parse-set
  "--set's argument `:ns/key=edn` as [key value], or the error naming it."
  {:malli/schema [:=> [:cat :string] [:tuple :qualified-keyword :any]]}
  [s]
  (let [i (str/index-of s "=")
        k (when i (try (edn/read-string (subs s 0 i)) (catch Exception _ nil)))
        v (when i (try [(edn/read-string (subs s (inc i)))] (catch Exception _ nil)))]
    (if (and (qualified-keyword? k) v)
      [k (first v)]
      (refuse! 'seon.config/parse-set (str "--set " s ": expected :ns/key=<edn value>")))))

(defn overrides
  "The command line's config overrides, in order, and the rest of it:
  {:layers [[source map-or-path] ...] :rest [arg ...]}. `--config FILE` is a layer read
  from FILE; `--set :k=v` one key."
  {:malli/schema [:=> [:cat [:sequential :string]] [:map [:layers vector?] [:rest [:vector :string]]]]}
  [args]
  (loop [[a b & more :as as] (seq args) acc {:layers [] :rest []}]
    (cond
      (empty? as) acc
      (and (= "--config" a) b) (recur more (update acc :layers conj [(str "--config " b) b]))
      (and (= "--set" a) b) (recur more (update acc :layers conj ["--set" (into {} [(parse-set b)])]))
      (#{"--config" "--set"} a) (refuse! 'seon.config/overrides (str a " needs an argument"))
      :else (recur (rest as) (update acc :rest conj a)))))

(defn without-overrides
  "args with --config and --set (and their arguments) taken out: what a mode reads."
  {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]}
  [args] (:rest (overrides args)))

(defn- read-layer
  "One file layer read with aero under prof, #seon/checkout resolving under dir; nil values
  are not stated (absent at this layer)."
  {:malli/schema [:=> [:cat :string :keyword :string] :map]}
  [p prof dir]
  (into {} (remove (comp nil? val)) (aero/read-config p {:profile prof ::checkout dir})))

(defn layered
  "The config of the layers [[source map] ...] (later wins, key by key), validated once:
  {:config :sources}, :sources naming the layer each key came from."
  {:malli/schema [:=> [:cat [:sequential [:tuple :string :map]]] [:map [:config :seon.config/config] [:sources :seon.config/sources]]]}
  [layers]
  (let [m (reduce (fn [m [_ l]] (merge m l)) {} layers)
        c (resolved m)]
    {:config c
     :sources (into {} (for [[src l] layers [k v] l :when (some? v)] [k src]))}))

(defn load-layers
  "The layers this process reads: the shipped file, then the command line's --config files
  and --set keys, in order. The checkout's config/local.edn is a layer only when a
  --config names it (bin/root, bin/ports): read implicitly, it gave a check's root in the
  main tree the standing root's source tree (task seon.check/a-checks-roots-take-explicit-config)."
  {:malli/schema [:=> [:cat :string :keyword [:sequential :string]] [:vector [:tuple :string :map]]]}
  [dir prof args]
  (into [["resources/seon/config/default.edn" (read-layer (path dir) prof dir)]]
        (for [[src x] (:layers (overrides args))]
          [src (if (string? x) (read-layer x prof dir) x)])))

(def ^:private loaded
  (let [dir (checkout)] (layered (load-layers dir (profile) (argv)))))

(def config
  "This process's config: every layer, read once at load (root's boot; agents are forked
  holding it)."
  (:config loaded))

(def sources
  "Which layer each key of the config came from."
  (:sources loaded))

;; ---- reading a setting: the one resolver ------------------------------------------------------
;; Owner: settings are per model, per provider and per task; "a principle and not just
;; a hack": every reader resolves through `resolve-in` (task -> model -> its provider -> the
;; config's own key), and the server shows each value with where it came from.

(defn resolve
  "Setting k for ctx ({:task m :model id}; default {}) in this process's config: {:value
  :source} (resolve-in)."
  {:malli/schema [:function [:=> [:cat :qualified-keyword] :seon.config/resolved]
                  [:=> [:cat :qualified-keyword :seon.config/context] :seon.config/resolved]]}
  ([k] (resolve k {}))
  ([k ctx] (resolve-in config sources k ctx)))

(defn value
  "Setting k's value for ctx (resolve), the read every reader makes."
  {:malli/schema [:function [:=> [:cat :qualified-keyword] :any] [:=> [:cat :qualified-keyword :seon.config/context] :any]]}
  ([k] (value k {}))
  ([k ctx] (:value (resolve k ctx))))

;; ---- the model provider, as the config states it --------------------------------------------

(defn provider
  "The model provider config c names (:seon.config.ai/*, resolved for its model): none, the
  scripted provider (a test fixture), or the OpenAI-compatible endpoint with the NAME of its key's
  environment variable (never the key)."
  {:malli/schema [:=> [:cat :seon.config/config] :seon.model/provider]}
  [c]
  (let [v #(:value (resolve-in c {} % {}))]
    (case (:seon.config.ai/provider c)
      :none {:kind :none}
      :scripted {:kind :scripted :script (:seon.config.ai/script c)}
      :openai-compat (cond-> {:kind :openai-compat :base-url (v :seon.config.ai/base-url) :model (:seon.config.ai/model c)
                              :api-key-env (v :seon.config.ai/api-key-variable) :max-tokens (v :seon.config.ai/max-tokens)
                              :replay-reasoning (v :seon.config.ai/replay-reasoning)}
                       (:seon.config.ai/thinking c) (assoc :thinking (:seon.config.ai/thinking c))
                       (:seon.config.ai/reasoning-effort c) (assoc :reasoning-effort (:seon.config.ai/reasoning-effort c))))))

(defn with-provider
  "Config c stating provider p (a provider handed to root's start! in place of the file's):
  the facts root writes say what the run really used (an endpoint p names is its model's
  provider's)."
  {:malli/schema [:=> [:cat :seon.config/config :seon.model/provider] :seon.config/config]}
  [c p]
  (let [top {:seon.config.ai/provider (:kind p) :seon.config.ai/script (:script p) :seon.config.ai/model (:model p)
             :seon.config.ai/max-tokens (:max-tokens p)
             :seon.config.ai/thinking (:thinking p) :seon.config.ai/reasoning-effort (:reasoning-effort p)}
        c (into c (filter (comp some? val)) top)
        prov (get-in c [:seon.config.ai/models (:seon.config.ai/model c) :seon.ai.model/provider])
        wire (into {} (filter (comp some? val)) {:seon.config.ai/base-url (:base-url p) :seon.config.ai/api-key-variable (:api-key-env p)})]
    (if (and prov (seq wire)) (update-in c [:seon.config.ai/providers prov] merge wire) c)))

;; ---- the facts on main ------------------------------------------------------------------------

(defn facts
  "Config c and its sources as facts on main (platform records: never carried by a merge in
  either direction): main's root entity holding every key (each collection-valued key, the
  manifest among them, as data), and an entity per provider and per model
  (:seon.ai.model/provider-id, :seon.ai.model/id)."
  {:malli/schema [:=> [:cat :seon.config/config :seon.config/sources] [:vector :map]]}
  [c srcs]
  (into [(assoc c :seon.root/id :root :seon.config/sources srcs)]
        (concat (for [[id p] (:seon.config.ai/providers c)]
                  (-> (select-keys p [:seon.config.ai/base-url :seon.config.ai/api-key-variable :seon.ai.model/output-token-wire-key])
                      (assoc :seon.ai.model/provider-id id)))
                (for [[id m] (:seon.config.ai/models c)]
                  (-> (dissoc m :seon.config.ai/max-in-flight :seon.config.ai/max-tokens) (assoc :seon.ai.model/id id))))))

(defn secret
  "The value of the environment variable named var-name (a credential, e.g. the model's key,
  named by :seon.config.ai/api-key-variable): read at its one use, never stored, never
  printed. nil when unset."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [var-name] (System/getenv var-name))

(defn from-db
  "The config root wrote on db (main, or any branch made from it since): every declared key
  the root entity holds, each as its schema declares it. What agents, the dashboard and the
  context read. A [:set T] key is a cardinality-many attribute (seon.schema.datahike/
  declaration), which a pull answers as a vector: it is read back as the set it was (the
  first set-valued key, :seon.config.source/process-vars, came back a vector, so the config
  in force and main's copy of it differed)."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :map]]}
  [db]
  (let [schema (d/schema db)]
    (not-empty (into {} (for [[k v] (d/pull db (keys-declared) [:seon.root/id :root])]
                          [k (if (= :db.cardinality/many (get-in schema [k :db/cardinality])) (set v) v)])))))
