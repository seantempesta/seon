;;; p2 — is konserve a whole-value store, or does a write share structure?
;;; Run: clojure -M:dev -i tmp/durable-env/p2_konserve_amplification.clj
(require '[konserve.filestore :as fs]
         '[konserve.core :as k]
         '[clojure.java.io :as io])

(defn line [& xs] (println (apply str xs)))

(def root "tmp/durable-env/store-p2")
(defn rm-rf [p]
  (let [f (io/file p)]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))))
(rm-rf root)

(def store (fs/connect-fs-store root :opts {:sync? true}))

(defn du
  "Total bytes of the store directory."
  []
  (reduce + 0 (map #(.length ^java.io.File %)
                   (filter #(.isFile ^java.io.File %) (file-seq (io/file root))))))

(defn ms [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(line "\n=== A. one key, a 100 000-entry map: first write vs one-entry update ===")
(def big (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100000))))
(let [before (du)
      [t1 _] (ms #(k/assoc store :env big {:sync? true}))
      after1 (du)
      big2 (assoc big :k0 :changed)
      [t2 _] (ms #(k/assoc store :env big2 {:sync? true}))
      after2 (du)
      ;; the same one-entry change through the PATH api (-update-in)
      [t3 _] (ms #(k/assoc-in store [:env :k1] :changed {:sync? true}))
      after3 (du)]
  (line (format "  write #1 (fresh 100k map)          %8.1f ms   store grew %d bytes" t1 (- after1 before)))
  (line (format "  write #2 (whole map, ONE entry differs) %8.1f ms   store now %d bytes (delta %d)" t2 after2 (- after2 after1)))
  (line (format "  write #3 (k/assoc-in ONE path)     %8.1f ms   store now %d bytes (delta %d)" t3 after3 (- after3 after2)))
  (line "  => a one-entry change costs a FULL re-serialize + full blob rewrite."))

(line "\n=== B. scaling: cost of one-entry update vs map size ===")
(doseq [n [1000 10000 100000 400000]]
  (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range n)))
        kk (keyword (str "s" n))
        _ (k/assoc store kk m {:sync? true})
        ts (doall (for [i (range 3)]
                    (first (ms #(k/assoc-in store [kk :k0] i {:sync? true})))))]
    (line (format "  n=%7d   one-entry update-in: %s ms (median %.1f)"
                  n (pr-str (mapv #(Math/round ^double %) ts))
                  (nth (sort ts) 1)))))

(line "\n=== C. what do the serializers round-trip? ===")
(defn roundtrip [label v]
  (let [r (try
            (k/assoc store :rt v {:sync? true})
            (let [back (k/get store :rt nil {:sync? true})]
              {:ok true :equal (= v back) :class-in (str (class v)) :class-out (str (class back))
               :meta-in (meta v) :meta-out (meta back)})
            (catch Throwable t {:ok false :err (.getMessage t) :class (str (class t))}))]
    (line (format "  %-34s %s" label (pr-str r)))))

(roundtrip "plain map" {:a 1 :b [1 2 3]})
(roundtrip "sorted-map (default cmp)" (sorted-map :b 2 :a 1))
(roundtrip "sorted-set-by CUSTOM comparator" (sorted-set-by (fn [a b] (compare b a)) 1 2 3))
(roundtrip "map WITH metadata" (with-meta {:a 1} {:m 1}))
(roundtrip "lazy-seq" (map inc (range 5)))
(roundtrip "record-free deftype? (a fn)" (fn [x] x))
(roundtrip "java.time.Instant" (java.time.Instant/ofEpochMilli 0))
(roundtrip "ratio 1/3" (/ 1 3))
(roundtrip "BigInt" (bigint 12345678901234567890N))
(roundtrip "char" \a)
(roundtrip "an atom" (atom 1))

(line "\n=== D. bassoc/bget (the blob path) at 8 MB ===")
(let [octets (byte-array (* 8 1024 1024) (byte 42))
      [tw _] (ms #(k/bassoc store "blob8" octets {:sync? true}))
      [tr n] (ms #(k/bget store "blob8"
                          (fn [{:keys [input-stream]}]
                            (with-open [i input-stream] (alength (.readAllBytes i))))
                          {:sync? true}))]
  (line (format "  bassoc 8 MB: %.1f ms   bget 8 MB: %.1f ms (%s bytes)" tw tr n)))

(System/exit 0)
