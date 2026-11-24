(ns seon.bench.helpers
  "Extracted child namespace of `seon.bench.cluster`."
  (:require [jolt.fs :as fs]))

(defn parse-listening-port
  "Extracts the bound localhost integer port from a Seon startup log line, or nil."
  {:malli/schema [:=> {:examples [[["SEON LISTENING http://127.0.0.1:54321 | Headless | Store /tmp/s"] 54321]
                                  [["Booting Seon..."] nil]
                                  [[""] nil]]}
                  [:cat :string] [:maybe :int]]
   :cost/ticks 250000
   :cost/bytes 2097152}
  [line]
  (when-let [[_ port-str] (re-find #"SEON LISTENING https?://[^:\s]+:(\d+)" line)]
    (parse-long port-str)))

(defn build-instance-cmd
  "Builds the CLI argv vector for spawning an isolated headless `target/seon` benchmark instance."
  {:malli/schema [:=> {:examples [[[{:bin-path "/app/target/seon"
                                     :store-path "/tmp/s1"
                                     :repo-dir "/app"
                                     :model-stub? true}]
                                   ["/app/target/seon"
                                    "--port" "0"
                                    "--headless"
                                    "--store" "/tmp/s1"
                                    "--repo" "/app"
                                    "--commit-git" "false"
                                    "--auto-switch-over" "false"
                                    "--model-stub"]]]}
                  [:cat :map] [:vector :string]]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [{:keys [bin-path port store-path seed-store repo-dir headless? commit-git? auto-switch-over?
           model-base-url model model-stub? max-os-procs max-test-workers]
    :or {port 0 headless? true commit-git? false auto-switch-over? false}}]
  (cond-> [(str (or bin-path "target/seon"))
           "--port" (str (long (or port 0)))]
    headless? (conj "--headless")
    store-path (conj "--store" (str store-path))
    seed-store (conj "--seed-store" (str seed-store))
    repo-dir (conj "--repo" (str repo-dir))
    (some? commit-git?) (conj "--commit-git" (if commit-git? "true" "false"))
    (some? auto-switch-over?) (conj "--auto-switch-over" (if auto-switch-over? "true" "false"))
    model-base-url (conj "--model-base-url" (str model-base-url))
    model (conj "--model" (str model))
    model-stub? (conj "--model-stub")
    max-os-procs (conj "--max-os-procs" (str (long max-os-procs)))
    max-test-workers (conj "--max-test-workers" (str (long max-test-workers)))))

(defn agent-settled?
  "Returns true when `agent-id` in a `/op {:op :status}` snapshot has executed at least one eval
   (or merged) and has no pending turns, unread messages, awaits, tests, or merges."
  {:malli/schema [:=> {:examples [[[{:agents [{:id :w/a :evals 2 :busy? false :unread-count 0
                                               :awaiting-count 0 :needs-turn? false :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  true]
                                 [[{:agents [{:id :w/a :evals 0 :busy? true :unread-count 1
                                               :awaiting-count 0 :needs-turn? true :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  false]
                                 [[{:agents []} :w/missing]
                                  false]]}
                  [:cat :map :keyword] :boolean]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [status-map agent-id]
  (boolean
   (when-let [ag (some #(when (= agent-id (:id %)) %) (:agents status-map))]
     (and (not (:busy? ag))
          (zero? (long (or (:unread-count ag) 0)))
          (zero? (long (or (:awaiting-count ag) 0)))
          (not (:needs-turn? ag))
          (not (:needs-test? ag))
          (not (:needs-merge? ag))
          (or (= :merged (:status ag))
              (pos? (long (or (:evals ag) 0))))))))

(defn ^:seon.flow/edge jolt-env-map
  "Builds the child-process env overlay map for isolated Jolt/Seon instances (`SEON_BINARY`, `JOLT_*_LIB`, plus `extra-env`)."
  {:malli/schema [:=> [:cat :any :any] [:map-of :string :string]]}
  [repo-dir extra-env]
  (let [lib-dir (fs/path (str (or repo-dir ".")) "target/lib")
        base-env (reduce (fn [acc [env-k base]]
                           (if (System/getenv env-k)
                             acc
                             (let [dylib (fs/path lib-dir (str base ".dylib"))
                                   so (fs/path lib-dir (str base ".so"))]
                               (cond
                                 (fs/regular-file? dylib) (assoc acc env-k (str (fs/absolutize dylib)))
                                 (fs/regular-file? so) (assoc acc env-k (str (fs/absolutize so)))
                                 :else acc))))
                         {"SEON_BINARY" "1"}
                         [["JOLT_LZ4_LIB" "liblz4"]
                          ["JOLT_ZSTD_LIB" "libzstd"]
                          ["JOLT_LMDB_LIB" "liblmdb"]])]
    (if (map? extra-env)
      (reduce-kv (fn [acc k v] (if (and k (some? v)) (assoc acc (str k) (str v)) acc))
                 base-env extra-env)
      base-env)))

(defn configure-jolt-env!
  "Populates `ProcessBuilder` env from `jolt-env-map`."
  {:malli/schema [:=> [:cat :any :any :any] :any], :seon.flow/edge true}
  [^ProcessBuilder pb repo-dir extra-env]
  (when (instance? ProcessBuilder pb)
    (let [env (.environment pb)]
      (doseq [[k v] (jolt-env-map repo-dir extra-env)]
        (.put env k v)))))