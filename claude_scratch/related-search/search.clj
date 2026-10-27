;; Related search, compared offline (root's brief, 2026-10-25): for each merged task in truth.edn (its
;; plan, and what its merge changed), rank functions, wiki sections, schema keys and tasks from the
;; plan text alone by each method, and score recall@5 and @10 and the tokens the top k lines cost.
;; Run in an agent (its db is main's, its :embed is root's): (load-file ".../search.clj") (run-all).
(require '[datahike.api :as d] '[clojure.string :as str] '[clojure.set :as set] '[seon.agent :as agent]
         '[seon.vector :as vector] '[seon.ref :as ref] '[seon.docs :as docs] '[seon.print.select :as select])

(def dir "/private/tmp/claude-501/-Users-sean-src-seon-next/89e4bd39-d696-48b6-9c09-34666b1c7927/scratchpad/search")
(def kinds [:fns :sections :schemas :tasks])

(defn embed [texts]
  (let [r (agent/fx! :embed {:texts (vec texts)} {})] (or (:vectors (:value r)) (throw (ex-info "embed failed" r)))))

;; ---- the corpus, once ---------------------------------------------------------------------
(defn lower [x] (assoc x :lower (str/lower-case (:text x))))

(defn corpus [db]
  (let [vars (for [[n f src] (d/q '[:find ?n ?f ?src :where [?v :var/name ?n] [?v :var/form ?f] [?f :form/src ?src]] db)
                   :when (not (str/ends-with? (namespace n) "-test"))]
               {:name (str n) :sym n :e f :text src :doc (:var/doc (d/entity db [:var/name n]))})
        secs (for [[a e] (d/q '[:find ?a ?e :where [?e :seon.section/address ?a]] db)]
               (let [x (d/entity db e)] {:name a :e e :text (str (:seon.section/heading x) "\n" (:seon.section/text x)) :lead (:seon.section/lead x)
                                         :names (set (map str (:seon.section/names x))) :refs (set (keep :seon.section/address (:seon.section/references x)))}))
        schemas (for [[k form] (d/q '[:find ?k ?f :where [?e :schema/key ?k] [?e :schema/form ?f]] db)] {:name (str k) :text (str k " " form)})
        tasks (for [[id plan e] (d/q '[:find ?id ?p ?e :where [?e :seon.task/id ?id] [?e :seon.task/plan ?p]] db)]
                {:name id :e e :text plan :refs (set (map str (:seon.task/references (d/entity db e))))})
        svs (vec (mapcat embed (partition-all 64 (map #(str vector/document-prompt (:text %)) schemas))))]
    {:fns (mapv lower vars) :sections (mapv lower secs) :schemas (mapv #(lower (assoc %1 :v %2)) schemas svs) :tasks (mapv lower tasks)
     :by-name (into {} (for [k kinds x (case k :fns vars :sections secs :schemas schemas :tasks tasks)] [[k (:name x)] x]))}))

(defn line [k x]
  (case k
    :fns (str (:name x) (some->> (:doc x) ref/lead-sentence (str ": ")))
    :sections (str (:name x) (some->> (:lead x) ref/lead-sentence (str ": ")))
    :schemas (:name x)
    :tasks (str (:name x) ": " (ref/lead-sentence (:text x)))))

;; ---- methods: each answers {kind [name ...]} best first ---------------------------------------
(defn cosine [a b] (reduce + (map * a b)))

(defn by-embedding [db c qv self]
  (let [all Long/MAX_VALUE
        names (fn [of f] (into [] (comp (map first) (keep f) (distinct)) (vector/near db qv 400 {:of of :shortlist all})))]
    {:fns (names :form/id #(some-> (d/entity db %) :var/_form first :var/name str ((fn [n] (when-not (str/includes? n "-test/") n)))))
     :sections (names :seon.section/address #(:seon.section/address (d/entity db %)))
     :tasks (vec (remove #{self} (names :seon.task/id #(:seon.task/id (d/entity db %)))))
     :schemas (mapv :name (sort-by #(- (cosine qv (:v %))) (:schemas c)))}))

(defn kw-in [text] (set (re-seq #":[\w.\-]+/[\w.\-?!*]+" text)))

(defn by-graph [db c plan self]
  (let [cites (ref/references db plan)
        names (set (keep :seon.ref/name cites))
        seed-vars (filter #(get-in c [:by-name [:fns (str %)]]) (filter qualified-symbol? names))
        seed-secs (for [x cites :when (and (some-> (:seon.ref/path x) str (str/ends-with? ".md")) (= :view (docs/citation-kind db x)))
                        :let [a (:seon.ref/address (docs/cited-view db x))] :when (and a (get-in c [:by-name [:sections a]]))] a)
        seed-tasks (filter #(and (string? %) (get-in c [:by-name [:tasks %]])) (map str names))
        hop (fn [v] (into (set (map str (docs/callers db v)))
                          (map str (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db v))))
        rank (fn [seeds more] (vec (distinct (concat seeds (map key (sort-by (comp - val) (frequencies more)))))))
        sv (set (map str seed-vars))
        secs (:sections c)]
    {:fns (rank (map str seed-vars) (concat (mapcat hop seed-vars)
                                            (mapcat #(:names (get-in c [:by-name [:sections %]])) seed-secs)))
     :sections (rank seed-secs (concat (for [s secs :when (seq (set/intersection sv (:names s)))] (:name s))
                                       (mapcat #(:refs (get-in c [:by-name [:sections %]])) seed-secs)
                                       (for [s secs :when (some (set seed-secs) (:refs s))] (:name s))))
     :schemas (rank (filter #(get-in c [:by-name [:schemas %]]) (kw-in plan))
                    (filter #(get-in c [:by-name [:schemas %]]) (mapcat #(kw-in (:text (get-in c [:by-name [:fns %]]))) sv)))
     :tasks (vec (remove #{self} (rank seed-tasks (for [t (:tasks c) :when (seq (set/intersection sv (:refs t)))] (:name t)))))}))

(def stop #{"which" "their" "there" "these" "those" "where" "while" "about" "after" "before" "every" "other" "should" "would" "could" "being" "since" "until" "under" "today" "plain" "never" "always" "first" "again" "still" "makes" "shows" "names" "reads" "nothing"})

(defn by-grep [c plan self]
  (let [toks (into #{} (comp (map str/lower-case) (filter #(>= (count %) 5)) (remove stop)) (re-seq #"[\w\-]+" plan))
        score (fn [x] (count (filter #(str/includes? (:lower x) %) toks)))
        rank (fn [xs] (mapv peek (sort (for [x xs :let [n (score x)] :when (pos? n)] [(- n) (:name x)]))))]
    (-> (into {} (for [k kinds] [k (rank (get c k))])) (update :tasks #(vec (remove #{self} %))))))

(defn rrf [& lists] (seon.vector/rrf (vec lists)))

(defn methods-of [db c {:keys [plan task]}]
  (let [qv (first (embed [(str vector/query-prompt plan)]))
        e (by-embedding db c qv task) g (by-graph db c plan task) t (by-grep c plan task)]
    {:embedding e :graph g :grep t
     :graph+embedding (into {} (for [k kinds] [k (rrf (g k) (e k))]))
     :seeds-then-embedding (into {} (for [k kinds] [k (vec (distinct (concat (take 3 (g k)) (e k))))]))
     :all-three (into {} (for [k kinds] [k (rrf (g k) (e k) (t k))]))}))

;; ---- scoring --------------------------------------------------------------------------------
(defn run-all [c from to]
  (let [db (agent/db)
        truth (subvec (clojure.edn/read-string (slurp (str dir "/truth.edn"))) from to)
        rows (vec (for [t truth
                        :let [want (into {} (for [k kinds] [k (set (filter #(get-in c [:by-name [k %]]) (get t k)))]))
                              dropped (into {} (for [k kinds] [k (- (count (get t k)) (count (want k)))]))
                              ms (methods-of db c t)]]
                    {:task (:task t) :plan-at (:plan-at t) :want (update-vals want count) :dropped dropped
                     :scores (into {} (for [[m r] ms k kinds :when (seq (want k)) n [5 10]]
                                        [[m k n] {:recall (/ (count (filter (want k) (take n (r k)))) (double (count (want k))))
                                                  :tokens (select/estimate (str/join "\n" (map #(line k (get-in c [:by-name [k %]])) (take n (r k)))))}]))}))]
    (spit (str dir "/results-" from ".edn") (pr-str rows))
    (count rows)))
