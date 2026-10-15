;; konserve-read-probes.clj -- lane konserve-read, 2026-10-15. Measurement forms behind konserve-read-fix.md.
;; Run in a throwaway JVM on an APFS clone of data/store (cp -c -R, then cp -cRn), never default:
;;   before: cd <datahike HEAD worktree> && clojure -M:test -i <this file's sections saved as scripts>
;;   after:  the same with -Sdeps '{:deps {org.replikativ/konserve {:local/root "reference-code/konserve"}}}'
;; The clone's commit graph was backfilled once (datahike.versioning/backfill-commit-graph!) before the end-to-end runs.

;; ---- open the clone, measurement helpers
(require '[datahike.api :as d] '[konserve.core :as k])
(def clone "/Users/sean/src/seon/tmp/konserve-read/store")
(defn cfg [branch]
  {:attribute-refs? false :branch branch :crypto-hash? false :fuse-index-roots? true
   :index :datahike.index/persistent-set :index-config {:branching-factor 128 :diff-buf-size 32}
   :keep-history? true :schema-flexibility :write :search-cache-size 10000
   :store-cache-bytes 512000000 :store-cache-size 1000
   :store {:backend :file :path clone :id #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"
           :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
   :writer {:backend :self}})
(def tm (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean tm (.getId (Thread/currentThread))))
(defn measure [f]
  (let [a0 (alloc) t0 (System/nanoTime) r (f)]
    {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)
     :mb (/ (Math/round (/ (- (alloc) a0) 1e4)) 100.0) :result r}))
(defn per-op-bytes [n f] (dotimes [_ 20] (f)) (let [a0 (alloc)] (dotimes [_ n] (f)) (/ (- (alloc) a0) (double n))))

;; ---- per-read allocation (bench-read)
(def main (d/connect (cfg :cluster-default)))
(def ks (:store @main))
(def db @main)
(def cid (get-in db [:meta :datahike/commit-id]))
(def root (.-_root (:eavt db)))
(def branch-addr (first (.addresses root)))
(def branch-node (k/get ks branch-addr nil {:sync? true}))
(def leaf-addrs (vec (take 8 (.addresses branch-node))))
(defn disk-bytes [key] (.length (java.io.File. (str clone "/" (konserve.impl.defaults/key->store-key key)))))
(defn row [label key]
  (let [g #(k/get ks key nil {:sync? true})]
    {:case label :disk-bytes (disk-bytes key)
     :get-bytes-per-read (Math/round (per-op-bytes 200 g))
     :get-meta-bytes-per-read (Math/round (per-op-bytes 200 #(k/get-meta ks key nil {:sync? true})))
     :us-per-read (let [t0 (System/nanoTime)] (dotimes [_ 200] (g)) (/ (Math/round (/ (- (System/nanoTime) t0) 2000.0)) 100.0))}))
(def rows
  (concat [(row :tiny-commit-graph-record [:datahike/commit-graph cid])
           (row :branch-node branch-addr)]
          (map-indexed (fn [i a] (row (keyword (str "leaf-node-" i)) a)) leaf-addrs)
          [(row :commit-record cid)]))
(doseq [r rows] (prn r))
(prn {:key->store-key-bytes (Math/round (per-op-bytes 1000 #(konserve.impl.defaults/key->store-key [:datahike/commit-graph cid])))})
(prn {:leaf-mean (Math/round (double (/ (reduce + (map :get-bytes-per-read (filter #(re-find #"leaf" (name (:case %))) rows))) 8)))})
(System/exit 0)

;; ---- end to end (bench-e2e)
(def main (d/connect (cfg :cluster-default)))
(def head (d/commit-as-db @main #uuid "6acfdaa8-9993-510f-b3a4-bc1fe5746efe"))
(defn stats [] @(:stats (.-_storage ^org.replikativ.persistent_sorted_set.PersistentSortedSet (:eavt head))))
(defn step [label f]
  (let [s0 (stats) m (measure f) s1 (stats)]
    (prn (merge {:step label :ms (:ms m) :mb (:mb m)} (select-keys (merge-with - s1 s0) [:reads :accessed])))
    (:result m)))
(def n (step :cold-scan-count-eavt #(count (d/datoms head :eavt))))
(prn {:eavt-datoms n})
(def sample (step :warm-scan-sample
                  #(let [k (quot n 5087)]
                     (->> (d/datoms head :eavt)
                          (remove (fn [dt] (or (= "db" (namespace (:a dt))) (= :db/txInstant (:a dt)))))
                          (take-nth k) (take 5087) vec))))
(def tx (mapv (fn [dt] [:db/retract (:e dt) (:a dt) (:v dt)]) sample))
(prn {:retractions (count tx)})
(step :merge-base-cold #(d/merge-base main :chain-f1f6dc8e6652 :cluster-default))
(dotimes [_ 3] (step :merge-base-warm #(d/merge-base main :chain-f1f6dc8e6652 :cluster-default)))
(dotimes [_ 6] (step :d-with-retractions #(count (:tx-data (d/with head {:tx-data tx})))))
(System/exit 0)
