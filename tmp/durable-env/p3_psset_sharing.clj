;;; p3 — does persistent-sorted-set's durable mode give REAL structural
;;; sharing over konserve? Build a big sorted set, flush, change one entry,
;;; flush again, and count nodes written + bytes added.
;;; This is the substrate Datahike's indexes use
;;; (reference-code/datahike/src/datahike/index/persistent_set.cljc:388-455).
;;; Run: clojure -M:dev -i tmp/durable-env/p3_psset_sharing.clj
(require '[konserve.filestore :as fs]
         '[konserve.core :as k]
         '[org.replikativ.persistent-sorted-set :as psset]
         '[clojure.java.io :as io])
(import '[org.replikativ.persistent_sorted_set PersistentSortedSet IStorage ANode Leaf Branch])

(defn line [& xs] (println (apply str xs)))
(defn ms [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(def root "tmp/durable-env/store-p3")
(defn rm-rf [p]
  (let [f (io/file p)]
    (when (.exists f) (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))))
(rm-rf root)

(def store (fs/connect-fs-store root :opts {:sync? true}))
(defn du [] (reduce + 0 (map #(.length ^java.io.File %)
                             (filter #(.isFile ^java.io.File %) (file-seq (io/file root))))))
(defn nfiles [] (count (filter #(.isFile ^java.io.File %) (file-seq (io/file root)))))

;;; A minimal content-addressed IStorage over konserve — the same shape as
;;; Datahike's CachedStorage, minus the LRU/freelist bookkeeping.
(def stats (atom {:writes 0 :reads 0}))
(def settings-holder (atom nil))
(def storage
  (reify IStorage
    (store [_ node]
      (swap! stats update :writes inc)
      (let [leaf? (instance? Leaf node)
            payload (if leaf?
                      {:level 0 :ks (vec (.keys ^ANode node))}
                      {:level (.level ^ANode node)
                       :ks (vec (.keys ^ANode node))
                       :addresses (vec (.addresses ^Branch node))})
            address (str "n-" (hash payload))]
        (k/assoc store address payload {:sync? true})
        address))
    (accessed [_ _])
    (restore [_ address]
      (swap! stats update :reads inc)
      (let [{:keys [level ks addresses]} (k/get store address nil {:sync? true})]
        (ANode/restore (long level) ks addresses @settings-holder)))
    (markFreed [_ _])))

(line "\n=== A. build a 100 000-key durable sorted set and flush it ===")
(def empty-pset (psset/sorted-set* {:comparator compare :storage storage :branching-factor 512}))
(reset! settings-holder (.-_settings ^PersistentSortedSet empty-pset))
(def pset
  (into empty-pset
        (map (fn [i] [(str "my.agent" (mod i 50)) (str "name" i) i]) (range 100000))))

(def base
  (let [b0 (du) f0 (nfiles) w0 (:writes @stats)
        [t addr] (ms #(psset/store pset))
        b1 (du) f1 (nfiles) w1 (:writes @stats)]
    (line (format "  flush #1: %.1f ms   nodes stored=%d   files=%d   bytes=%d   root=%s"
                  t (- w1 w0) (- f1 f0) (- b1 b0) addr))
    [b1 f1 w1]))

(line "\n=== B. conj ONE element and flush again — how much is rewritten? ===")
(def pset2 (conj pset ["my.agent0" "zzz-new" 999999]))
(def base2
  (let [[b1 f1 w1] base
        [t addr] (ms #(psset/store pset2))
        b2 (du) f2 (nfiles) w2 (:writes @stats)]
    (line (format "  flush #2: %.1f ms   nodes stored=%d   NEW files=%d   bytes added=%d   root=%s"
                  t (- w2 w1) (- f2 f1) (- b2 b1) addr))
    (line (format "  => %.3f%% of the tree's bytes rewritten for a one-key change"
                  (* 100.0 (/ (double (- b2 b1)) (double b1)))))
    [b2 f2 w2]))

(line "\n=== C. ten more single-key changes, each flushed ===")
(let [[b2 _f2 w2] base2]
  (loop [i 0 ps pset2 pb b2 pw w2 acc []]
    (if (= i 10)
      (do (line "  per-change [ms nodes bytes]: " (pr-str acc))
          (line (format "  MEDIAN bytes/change: %d   MEDIAN nodes/change: %d"
                        (nth (sort (map #(nth % 2) acc)) 5)
                        (nth (sort (map second acc)) 5))))
      (let [ps' (conj ps [(str "my.agent" (mod i 50)) (str "extra" i) (+ 1000000 i)])
            [t _] (ms #(psset/store ps'))
            b (du) w (:writes @stats)]
        (recur (inc i) ps' b w (conj acc [(Math/round ^double t) (- w pw) (- b pb)]))))))

(line "\n=== D. lazy restore from the root address in a fresh set ===")
(let [addr (psset/store pset2)
      restored (psset/restore-by compare addr storage {:branching-factor 512})
      r0 (:reads @stats)
      [t hit] (ms #(contains? restored ["my.agent0" "zzz-new" 999999]))
      r1 (:reads @stats)]
  (line (format "  lookup one key in a freshly restored tree: %.2f ms, found=%s, node reads=%d"
                t hit (- r1 r0))))

(line "\n=== E. control: the SAME data as ONE konserve value, one-key change ===")
(let [m (into {} (map (fn [i] [[(str "my.agent" (mod i 50)) (str "name" i)] i]) (range 100000)))
      [tw _] (ms #(k/assoc store :whole m {:sync? true}))
      bw (du)
      [tu _] (ms #(k/assoc-in store [:whole ["my.agent0" "name0"]] :changed {:sync? true}))
      bu (du)]
  (line (format "  whole-value write: %.1f ms" tw))
  (line (format "  ONE-key change:    %.1f ms, %d bytes added (a full rewrite)" tu (- bu bw))))

(System/exit 0)
