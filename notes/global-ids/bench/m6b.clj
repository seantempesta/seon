;; the counter's cost per allocation: an mmapped word's atomic fetch-add, or an LMDB counter key in its own write txn
(require '[jolt.ffi :as ffi] '[konserve.jolt.lmdb-ffi :as l])
(ffi/load-library "/private/tmp/claude-501/-Users-sean-src-seon-jolt-next--claude-worktrees-repo-overview-prioritize-5dd84e/67f1a8e8-cf1a-4463-8c52-5459bfdbad20/scratchpad/m/libcounter.dylib")
(ffi/defcfn counter-open "seon_counter_open" [:string] :pointer)
(ffi/defcfn counter-add "seon_counter_add" [:pointer :uint64] :uint64)
(let [[mode n out] *command-line-args* n (parse-long n)
      _ (while (not (.exists (java.io.File. "/Users/sean/src/seon-jolt/next-global-ids/target/m/go"))) (Thread/sleep 5))]
  (case mode
    "mmap" (let [p (counter-open "/Users/sean/src/seon-jolt/next-global-ids/target/m/counter")
                 t0 (System/nanoTime)
                 ids (loop [i 0 acc (transient [])] (if (< i n) (recur (inc i) (conj! acc (counter-add p 1))) (persistent! acc)))
                 ns (- (System/nanoTime) t0)]
             (spit out (pr-str (if (> n 200000) [(first ids) (last ids) (count (distinct ids))] ids)))
             (println "mmap" n "allocations" (format "%.0f ns/alloc" (/ (double ns) n))))
    "lmdb" (let [db (l/open-env "/Users/sean/src/seon-jolt/next-global-ids/target/m/ctr-lmdb" {:flags l/MDB_NOSYNC})
                 k "counter"
                 t0 (System/nanoTime)
                 ids (loop [i 0 acc (transient [])]
                       (if (< i n)
                         (recur (inc i) (conj! acc (l/write-txn db (fn [txn]
                                                                     (let [b (l/get-bytes db txn k)
                                                                           c (if b (parse-long (String. ^bytes b)) 0)]
                                                                       (l/put-bytes! db txn k (.getBytes (str (inc c))))
                                                                       c)))))
                         (persistent! acc)))
                 ns (- (System/nanoTime) t0)]
             (spit out (pr-str (if (> n 200000) [(first ids) (last ids) (count (distinct ids))] ids)))
             (println "lmdb" n "allocations" (format "%.1f us/alloc" (/ (double ns) n 1000))))))
