(ns seon.store.config
  "Extracted child namespace of `seon.store`."
  (:require [aero.core :as aero]
            [datahike.api :as d]
            [datahike.config]
            [datahike.connections]
            [datahike.ids]
            [datahike.store]
            [datahike.writing]
            [konserve.jolt.fasl-store]
            [konserve.jolt.store]
            [konserve.store]
            [datahike.index.interface :as di]))

(def default-config-values
  "Default operational values for `:seon.config/root` (overridden by `resources/seon/config/default.edn`)."
  {:seon.config/eval-ticks               100000000
   :seon.config/eval-bytes               268435456
   :seon.config/gen-samples              100
   :seon.config/gen-seed                 42
   :seon.config/max-os-procs             6
   :seon.config/max-test-workers         4
   :seon.config/max-model-slots          4
   :seon.config/child-cache-mb           32
   
   :seon.config/store-map-bytes          1099511627776
   :seon.config/gc-keep-merged-branches  16
   :seon.config/gc-history-hours         168
   :seon.config/review-every-turns       0
   :seon.config/unwritten-review-turns   8
   :seon.config/semantic-min-score       0.45
   :seon.config/auto-sync-git?           true
   :seon.config/auto-merge?              true
   :seon.config/auto-switch-over?        true
   :seon.config/fast-test-ms             150
   :seon.config/ai-model                 "deepseek-flash"
   :seon.config/ai-base-url              "https://api.deepseek.com"
   :seon.config/ai-api-key-env           "DEEPSEEK_API_KEY"
   :seon.config/ai-thinking              :enabled
   :seon.config/ai-reasoning-effort      :low
   :seon.config/max-tool-turns           32
   :seon.config/viewport-screenshot?     true
   
   :seon.config/eval-out-chars           10000})

(defn read-aero-config-tx
  "Reads an Aero EDN config file once at bootstrap and returns the `:seon.config/root` entity map.
   When `db` is supplied, filters attributes to those installed in `(:schema db)`."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat [:maybe :seon.host/db] :any] :map]]}
  ([file-or-text] (read-aero-config-tx nil file-or-text))
  ([db file-or-text]
   (let [raw (if (instance? java.io.File file-or-text)
               (when (.isFile ^java.io.File file-or-text)
                 (aero/read-config file-or-text))
               (when (string? file-or-text)
                 (aero/read-config (java.io.StringReader. file-or-text))))
         merged (merge default-config-values
                       (select-keys raw (keys default-config-values)))
         extras (into {}
                      (map (fn [[k v]] [k (str v)]))
                      (apply dissoc raw (keys default-config-values)))
         ent (cond-> (assoc merged :seon.config/key :seon.config/root)
               (seq extras) (assoc :seon.config/extra extras))]
     (if (and db (map? (:schema db)))
       (select-keys ent (keys (:schema db)))
       ent))))

(defn config-map
  "Returns `{:effective map :overrides #{...}}` for `agent-id` (or `:seon.config/root` when nil)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db [:maybe :keyword]] :map]]}
  ([db] (config-map db nil))
  ([db agent-id]
   (let [ks (vec (keys default-config-values))
         root-m (merge default-config-values
                       (when (and db (first (d/datoms db :avet :seon.config/key :seon.config/root)))
                         (dissoc (d/pull db ks [:seon.config/key :seon.config/root]) :db/id)))
         agent-m (when (and db agent-id (first (d/datoms db :avet :seon.agent/id agent-id)))
                   (dissoc (d/pull db ks [:seon.agent/id agent-id]) :db/id))]
     {:effective (merge root-m agent-m)
      :overrides (set (keys agent-m))})))

(def ^:dynamic *capturing-agent-eval-reads?*
  "Bound true ONLY inside `seon.code/eval-single-form` while evaluating an agent expression;
   allows internal runtime plumbing to opt out of agent touch capture without affecting UI/rule `reads/with-reads` reactivity."
  false)

(defn without-agent-eval-reads
  "Invokes `f` with `di/*reads*` nil when `*capturing-agent-eval-reads?*` is true."
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] :any]
   :cost/ticks 100000}
  [f]
  (if *capturing-agent-eval-reads?*
    (binding [di/*reads* nil] (f))
    (f)))

(defn config-val
  "Reads config key `k` from `db`, checking `agent-id`'s entity first (if given) and falling back
   to `:seon.config/key :seon.config/root`, then `default-config-values`. 1 indexed pull (~20 µs)!"
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword] :any]
                  [:=> [:cat :seon.host/db [:or :keyword :nil] :qualified-keyword] :any]]}
  ([db k] (config-val db nil k))
  ([db agent-id k]
   (without-agent-eval-reads
   (fn []
     (let [agent-v (when (and db agent-id (first (d/datoms db :avet :seon.agent/id agent-id)))
                     (get (d/pull db [k] [:seon.agent/id agent-id]) k))]
       (if (some? agent-v)
         agent-v
         (let [root-v (when (and db (first (d/datoms db :avet :seon.config/key :seon.config/root)))
                        (get (d/pull db [k] [:seon.config/key :seon.config/root]) k))]
           (if (some? root-v)
             root-v
             (get default-config-values k)))))))))
