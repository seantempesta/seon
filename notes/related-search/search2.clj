;; Related search, widened (root, after the owner, 2026-10-25): every schema kind a result kind
;; (Datahike attributes, config keys, the other registry entries: records, tasks, values), data <->
;; function edges, the neighbours shown with an item, renders, and other agents' work. Loaded after
;; search.clj in an agent; truth2.edn adds the config column and each merge's subject.
(require '[datahike.api :as d] '[clojure.string :as str] '[clojure.set :as set] '[seon.agent :as agent]
         '[seon.vector :as vector] '[seon.ref :as ref] '[seon.docs :as docs] '[seon.print.select :as select])

(defn kw-of [text] (into #{} (map #(keyword (subs % 1))) (re-seq #":[\w.\-]+/[\w.\-?!*]*[\w?!*]" text)))

(defn corpus2
  "search.clj's corpus with the schema keys split by kind and each key's text embedded once, and
  the edges the proposed :form/keywords would hold (the qualified keywords each function's text
  names), both ways."
  [db c]
  (let [attrs (set (filter qualified-keyword? (keys (d/schema db))))
        kind-of (fn [k] (cond (str/starts-with? (namespace k) "seon.config") :config (attrs k) :attr :else :record))
        keys* (mapv #(assoc % :key (keyword (subs (:name %) 1))) (:schemas c))
        by-kind (group-by (comp kind-of :key) keys*)
        fn-kws (into {} (for [f (:fns c)] [(:name f) (set (filter (set (map :key keys*)) (kw-of (:text f))))]))
        kw-fns (reduce-kv (fn [m f ks] (reduce #(update %1 %2 (fnil conj #{}) f) m ks)) {} fn-kws)
        render? (fn [f] (str/starts-with? (:name f) "seon.render"))]
    (assoc c :attr (vec (:attr by-kind)) :config (vec (:config by-kind)) :record (vec (:record by-kind))
           :fn-kws fn-kws :kw-fns kw-fns :kind-of kind-of :renders (set (map :name (filter render? (:fns c))))
           :render-edges (count (filter #(re-find #":seon\.render/(ai|html)" (:text %)) (:schemas c))))))

(def kinds2 [:fns :sections :attr :config :record :tasks])

(defn by-frequency [xs] (mapv peek (sort (map (fn [[x n]] [(- n) (str x) x]) (frequencies xs)))))


(defn rank-keys [c kind qv graph-kws]
  (let [xs (get c kind)
        emb (mapv :name (sort-by #(- (cosine qv (:v %))) xs))
        in (set (map :name xs))
        g (vec (filter in (map str graph-kws)))]
    {:embedding emb :graph g :graph+embedding (rrf g emb)}))

(defn methods2 [db c {:keys [plan task]}]
  (let [qv (first (embed [(str vector/query-prompt plan)]))
        e (by-embedding db c qv task) g (by-graph db c plan task)
        cited-fns (filter #(get-in c [:by-name [:fns %]]) (map str (filter qualified-symbol? (keep :seon.ref/name (ref/references db plan)))))
        graph-kws (by-frequency (concat (kw-of plan) (mapcat #(get (:fn-kws c) %) cited-fns)))
        base {:fns {:embedding (:fns e) :graph (:fns g) :graph+embedding (rrf (:fns g) (:fns e))}
              :sections {:embedding (:sections e) :graph (:sections g) :graph+embedding (rrf (:sections g) (:sections e))}
              :tasks {:embedding (:tasks e) :graph (:tasks g) :graph+embedding (rrf (:tasks g) (:tasks e))}}]
    (merge base (into {} (for [k [:attr :config :record]] [k (rank-keys c k qv graph-kws)])))))


(defn neighbours
  "What the edges add to a shown top 5: each shown function's schema keys of kind, and each shown
  key's functions, best first by how many shown items reach it."
  [c shown-fns shown-keys kind]
  {:keys (by-frequency (for [f shown-fns k (get (:fn-kws c) f) :when (= kind ((:kind-of c) k))] (str k)))
   :fns (by-frequency (for [k shown-keys f (get (:kw-fns c) (keyword (subs k 1)))] f))})

(defn score [want xs n] (when (seq want) (/ (count (filter want (take n xs))) (double (count want)))))

(defn run2 [c from to]
  (let [db (agent/db)
        truth (subvec (clojure.edn/read-string (slurp (str dir "/truth2.edn"))) from to)]
    (spit (str dir "/results2-" from ".edn")
          (pr-str
            (vec (for [t truth
                       :let [kw-truth (into #{} (map #(keyword (subs % 1))) (concat (:schemas t) (:config t)))
                             want {:fns (set (filter #(get-in c [:by-name [:fns %]]) (:fns t)))
                                   :sections (set (filter #(get-in c [:by-name [:sections %]]) (:sections t)))
                                   :tasks (set (filter #(get-in c [:by-name [:tasks %]]) (:tasks t)))
                                   :attr (set (map str (filter #(= :attr ((:kind-of c) %)) kw-truth)))
                                   :config (set (map str (filter #(= :config ((:kind-of c) %)) kw-truth)))
                                   :record (set (map str (filter #(and (= :record ((:kind-of c) %)) (get-in c [:by-name [:schemas (str %)]])) kw-truth)))}
                             ms (methods2 db c t)
                             top-fns (take 5 (get-in ms [:fns :graph+embedding]))
                             tok (fn [k xs] (select/estimate (str/join "\n" (for [x xs] (if (#{:fns :sections :tasks} k) (line k (get-in c [:by-name [k x]])) x)))))]]
                   {:task (:task t) :plan-at (:plan-at t) :want (update-vals want count)
                    :scores (into {} (for [k kinds2 :when (seq (want k)) [m xs] (get ms k) n [5 10]]
                                       [[m k n] {:recall (score (want k) xs n) :tokens (tok k (take n xs))}]))
                    ;; the neighbours of a shown top 5 against the next five of the same list
                    :neighbours (into {} (for [k [:attr :config :record] :when (seq (want k))
                                               :let [top-keys (take 5 (get-in ms [k :graph+embedding]))
                                                     nb (:keys (neighbours c top-fns top-keys k))
                                                     more (take 5 (drop 5 (get-in ms [k :graph+embedding])))]]
                                           [k {:fused5 (score (want k) top-keys 5)
                                               :fused5+next5 (score (want k) (concat top-keys more) 10)
                                               :fused5+fn-neighbours (score (want k) (distinct (concat top-keys nb)) 1000)
                                               :neighbour-count (count nb) :neighbour-tokens (tok k nb)}]))
                    :fn-neighbours (when (seq (want :fns))
                                     (let [keys5 (mapcat #(take 5 (get-in ms [% :graph+embedding])) [:attr :config :record])
                                           nb (remove (set top-fns) (:fns (neighbours c top-fns keys5 :attr)))
                                           nb5 (take 5 nb)]
                                       {:fused5 (score (want :fns) top-fns 5)
                                        :fused5+next5 (score (want :fns) (take 10 (get-in ms [:fns :graph+embedding])) 10)
                                        :fused5+key-neighbours5 (score (want :fns) (concat top-fns nb5) 10)
                                        :neighbour-tokens (tok :fns nb5)}))}))))
    to))

(defn pairs
  "Other agents' work: for each merged task whose merge touched a var or section another's did,
  the others ranked by their plan's vector and by their producer text (the plan, the merge's
  subject, the vars and sections it changed), nearest the task's plan; recall@3 of the partners."
  []
  (let [truth (clojure.edn/read-string (slurp (str dir "/truth2.edn")))
        touched (into {} (for [t truth] [(:task t) (set (concat (:fns t) (:sections t)))]))
        partners (into {} (for [t truth :let [ps (set (for [u truth :when (and (not= (:task u) (:task t)) (seq (set/intersection (touched (:task t)) (touched (:task u)))))] (:task u)))] :when (seq ps)] [(:task t) ps]))
        plan-v (zipmap (map :task truth) (embed (map #(str vector/query-prompt (:plan %)) truth)))
        doc-plan (zipmap (map :task truth) (embed (map #(str vector/document-prompt (:plan %)) truth)))
        doc-prod (zipmap (map :task truth) (embed (map #(str vector/document-prompt (:plan %) "\n" (:subject %) "\n" (str/join " " (concat (:fns %) (:sections %)))) truth)))
        rank (fn [vs t] (map first (sort-by (comp - second) (for [[u v] vs :when (not= u t)] [u (cosine (plan-v t) v)]))))
        r (fn [vs n] (let [xs (for [[t ps] partners] (score ps (rank vs t) n))] (/ (reduce + xs) (count xs))))]
    {:tasks (count truth) :with-partners (count partners) :mean-partners (/ (reduce + (map count (vals partners))) (double (count partners)))
     :plan {:r3 (r doc-plan 3) :r5 (r doc-plan 5)} :producer {:r3 (r doc-prod 3) :r5 (r doc-prod 5)}}))
