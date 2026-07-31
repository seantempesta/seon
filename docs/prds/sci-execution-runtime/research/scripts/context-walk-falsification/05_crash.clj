(in-ns 'falsify.harness)
;;; ATTACK 3 — break the three claimed bounds and the totality claim.

(def probe-schema
  [{:db/ident :falsify/id :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :falsify/blob :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :falsify/link :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}])

(defn timed [label f]
  (let [t0 (System/nanoTime)
        r (try (f) (catch Throwable e {:THREW (str (class e) ": " (.getMessage e))}))]
    (println (format "%-34s %8.0f ms  %s" label (/ (- (System/nanoTime) t0) 1e6) (pr-str r)))
    r))

(with-db
  (fn [conn]
    (tx! conn probe-schema)

    (println "\n=== 3a. a 5 MB string attribute (caps max-string = 4096) ===")
    (tx! conn [{:falsify/id "blob" :falsify/blob (apply str (repeat 5000000 "x"))}])
    (timed "walk d0 over 5MB entity"
           (fn [] (let [t (or (walk/prose (walk-node @conn [:falsify/id "blob"] 0)) "")]
                    {:chars (count t) :tokens (tokens/estimate t)
                     :bounded? (< (count t) 100000)})))

    (println "\n=== 3b. reverse fan-out of 500 (max-collection 64) ===")
    (tx! conn [{:falsify/id "hub"}])
    (tx! conn (into [] (for [i (range 500)]
                         {:falsify/id (str "spoke" i)
                          :falsify/link [[:falsify/id "hub"]]})))
    (timed "walk d1 over 500-fan-in hub"
           (fn [] (let [n (walk-node @conn [:falsify/id "hub"] 1)
                        t (or (walk/prose n) "")]
                    {:neighbours (count (:seon.render.walk/neighbours n))
                     :chars (count t)
                     :elision-marker? (boolean (re-find #"(?i)elid|truncat|more" t))})))

    (println "\n=== 3c. a ref CYCLE (a<->b<->c<->a) at depth 8 ===")
    (tx! conn [{:db/id "a" :falsify/id "cyc-a" :falsify/link ["b"]}
               {:db/id "b" :falsify/id "cyc-b" :falsify/link ["c"]}
               {:db/id "c" :falsify/id "cyc-c" :falsify/link ["a"]}])
    (doseq [d [3 6 8 10]]
      (timed (str "cycle walk d" d)
             (fn [] (let [n (walk-node @conn [:falsify/id "cyc-a"] d)]
                      {:nodes (count (nodes n))
                       :elided (count (filter #(= :seon.render.walk/elided
                                                  (:seon.error/kind (:seon.error/value %)))
                                              (nodes n)))}))))

    (println "\n=== 3d. a dense CLIQUE — 12 entities each linking all others, d6 ===")
    (tx! conn (into [] (for [i (range 12)] {:db/id (str "k" i) :falsify/id (str "clq" i)
                                            :falsify/link (mapv #(str "k" %) (remove #{i} (range 12)))})))
    (doseq [d [2 3 4 5]]
      (timed (str "clique walk d" d)
             (fn [] (let [n (walk-node @conn [:falsify/id "clq0"] d)
                          all (nodes n)]
                      {:nodes (count all)
                       :distinct (count (distinct (map :seon.render.walk/lookup all)))
                       :elided (count (filter #(= :seon.render.walk/elided
                                                  (:seon.error/kind (:seon.error/value %))) all))}))))

    (println "\n=== 3e. exotic values through the floor ===")
    (tx! conn [{:falsify/id "uni"
                :falsify/blob (str "emoji 💥 rtl ‮evil‬ nul<" (char 0) "> "
                                   "combining é́́́ surrogate-half \uD800")}])
    (timed "unicode entity d0"
           (fn [] (let [t (or (walk/prose (walk-node @conn [:falsify/id "uni"] 0)) "")]
                    {:chars (count t) :head (subs t 0 (min 90 (count t)))})))

    (println "\n=== 3f. a renderer that THROWS / LOOPS (totality claim) ===")
    (println "throwing projection:"
             (pr-str (walk/prose (walk-node @conn [:falsify/id "uni"] 0))))
    (let [n (walk/neighborhood {:seon.db/db @conn
                                :seon.render.walk/lookup [:falsify/id "uni"]
                                :seon.render/kind :seon.render/ai
                                :seon.render/floor 'falsify.harness/boom
                                :seon.render/distance 0
                                :seon.sci.admit/caps caps})]
      (println "floor = a var that does not exist ->" (pr-str (:seon.error/value n))))
    (println "\n=== 3g. lookup that does not resolve ==="
             (pr-str (:seon.error/value (walk-node @conn [:falsify/id "nope"] 1))))))
