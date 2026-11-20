(ns seon.bench.helpers
  "Extracted child namespace of `seon.bench.cluster`."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.bench.proxy :as proxy]
            [seon.model :as model]
            [seon.store :refer [rm-rf!]]))

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

(defn configure-jolt-env!
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [^ProcessBuilder pb repo-dir extra-env]
  (let [env (.environment pb)
        lib-dir (io/file repo-dir "target/lib")]
    (.put env "SEON_BINARY" "1")
    (doseq [[env-k base] [["JOLT_LZ4_LIB" "liblz4"]
                          ["JOLT_ZSTD_LIB" "libzstd"]
                          ["JOLT_LMDB_LIB" "liblmdb"]]]
      (when-not (.get env env-k)
        (let [dylib (io/file lib-dir (str base ".dylib"))
              so (io/file lib-dir (str base ".so"))]
          (cond
            (.isFile dylib) (.put env env-k (.getAbsolutePath dylib))
            (.isFile so) (.put env env-k (.getAbsolutePath so))))))
    (doseq [[k v] extra-env]
      (when (and k (some? v))
        (.put env (str k) (str v))))))
