;; health-page-opus workbench (2026-09-28). A disposable REPL prototype, sent
;; form by form through MCP eval_clj (JVM mode, namespace scratch.health) on
;; default; nothing here is loaded into a program namespace.
(require '[seon.cache] '[seon.db] '[seon.id])
(import '[java.util.concurrent CompletableFuture TimeUnit TimeoutException]
        '[java.util.concurrent.atomic LongAdder]
        '[com.github.benmanes.caffeine.cache AsyncCache])

;; --- 1. non-blocking read over a scratch cache ------------------------------
(def scratch-cache (seon.cache/create 20000000))

(defn settle!
  "The body of seon.cache/lookup's miss branch, completing `mine`."
  [held kind k derive-fn weigh ^CompletableFuture mine]
  (let [c (#'seon.cache/kind-counters held kind) started (System/nanoTime)]
    (.increment ^LongAdder (:seon.cache/misses c))
    (try (let [v (derive-fn) w (+ 256 (long (weigh v)))]
           (.add ^LongAdder (:seon.cache/load-nanos c) (- (System/nanoTime) started))
           (.add ^LongAdder (:seon.cache/bytes c) w)
           (.complete mine {:seon.cache/value v :seon.cache/weight w}))
         (catch Throwable t
           (.remove (.asMap ^AsyncCache (:seon.cache/cache held)) [kind k] mine)
           (.complete mine {:seon.cache/thrown t :seon.cache/weight 0})))))

(defn peek!
  [held kind k weigh derive-fn deadline-ms on-complete]
  (let [^AsyncCache cache (:seon.cache/cache held)
        mine (CompletableFuture.)
        prior (or (.getIfPresent cache [kind k]) (.putIfAbsent (.asMap cache) [kind k] mine))
        ^CompletableFuture pending (or prior mine)]
    (when-not prior (Thread/startVirtualThread #(settle! held kind k derive-fn weigh mine)))
    (try (let [{:seon.cache/keys [value thrown]} (.get pending (long deadline-ms) TimeUnit/MILLISECONDS)]
           (if thrown (throw thrown) value))
         (catch TimeoutException _
           (when on-complete (.thenRun pending ^Runnable on-complete))
           {:seon.cache/pending kind :seon.cache/key-digest (seon.id/digest k)}))))

;; --- 2. a family's attribute revision key -----------------------------------
(defn read-attributes
  [captured]
  (let [attrs (map (comp :datahike.read/attributes :datahike.read/revision) (seon.db/read-evidence captured))]
    (if (some #{:all} attrs) :all (into (sorted-set) cat attrs))))

(defn revision-key
  [database attributes]
  (let [ctx (:cache-context database)]
    (when (and (:datahike.cache/committed? ctx) (set? attributes))
      [(:datahike.cache/connection-id ctx) (:datahike.cache/generation ctx)
       (select-keys (:datahike.cache/attribute-revisions ctx) attributes)])))

;; --- 3. load the working-tree files into scratch namespaces (never a program namespace) ---
;; Evaluated on default 2026-09-28 in namespace scratch.health. health-projection is
;; default's forms plus this tree's seon.{health,problems,cache,oversight,cluster.status}.edn,
;; built with seon.schema/build-projection (440 ms, valid).
(comment
  (defn rebuild! []
    (let [db (seon.db/db (seon.cluster.boot/connection "default"))
          proj (seon.db/carried-projection db)
          files ["seon.health.edn" "seon.problems.edn" "seon.cache.edn" "seon.oversight.edn" "seon.cluster.status.edn"]
          mine (apply merge (map #(clojure.edn/read-string (slurp (str "/Users/sean/src/seon/resources/seon/schemas/" %))) files))
          removed (remove (set (keys mine)) (filter #(#{"seon.problems" "seon.cache" "seon.oversight" "seon.cluster.status" "seon.health"} (namespace %)) (keys (:seon.schema.projection/forms proj))))
          forms (merge (apply dissoc (:seon.schema.projection/forms proj) removed) mine)]
      (def health-projection (seon.schema/build-projection forms))))
  (defn eval-file-as! [path scratch-ns replacements]
    (let [text (reduce (fn [t [a b]] (clojure.string/replace t a b)) (slurp path) replacements)
          reader (java.io.PushbackReader. (java.io.StringReader. text))
          forms (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof :read-cond :allow} reader)))
          [ns-form & body] forms]
      (remove-ns scratch-ns)
      (binding [*ns* *ns*] (eval ns-form) (doseq [f body] (eval f)))
      {:ns scratch-ns :forms (count body)}))
  ;; cache.clj → scratch.cache, cluster/status.clj → scratch.status, problems.clj → scratch.problems
  ;; ((schema.edn/load! {}) → nil), health.clj → scratch.health-code with those requires swapped and
  ;; (db/carried-projection database) → @(resolve 'scratch.health/health-projection).
  ((resolve 'scratch.health-code/report) {:seon.boot/cluster-name "default" :seon.health/deadline-ms 50
                                          :seon.health/wake #(println "woke")}))
