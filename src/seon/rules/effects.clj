(ns seon.rules.effects
  "Pure cached boundary effect handlers for `seon.rules.context`:
   - Keeps the rule core 100% deterministic (never calls search/embeddings inside rules).
   - Executes `:ctx/need-effect` facts at the boundary, caches results per commit via
     `seon.store/lru-get!` (preserving `datahike.reads`), and injects ordinary EAV facts back."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.rules :as rules]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge effect-cache (store/lru-cache 64))
(defonce ^:private ^:seon.flow/edge boot-tx-cache (store/lru-cache 16))

(def ^:private domain-stop-words
  #{"seon" "code" "user" "summary" "entity" "file" "form" "data" "test" "line" "name" "plan" "list" "view"})

(defn baseline-import-tx
  "Returns the bulk bootstrap transaction id of `db` (cached with `datahike.reads` replay) so recency rules distinguish post-boot edits."
  {:malli/schema [:=> [:cat :seon.host/db] :int]
   :cost/ticks 2000000}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/hash))
    0
    (let [ck [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)]]
      (store/lru-get!
       boot-tx-cache 16 ck
       (fn []
         (let [tx-freqs (frequencies (map :tx (d/datoms db :aevt :seon.code.form/hash)))]
           (if (seq tx-freqs)
             (long (key (apply max-key val tx-freqs)))
             0)))))))

(defn strip-template-footer
  "Strips generic template instructions appended after `\n---\n` so task grounding matches only task-specific intent."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]
   :cost/ticks 50000}
  [plan]
  (if (string? plan)
    (str/trim (first (str/split plan #"\n---\n" 2)))
    ""))

(defn extract-tokens
  "Tokenizes prose/code into normalized symbol/keyword candidates (stripping surrounding punctuation)."
  {:malli/schema [:=> [:cat [:maybe :string]] [:set :string]]
   :cost/ticks 200000}
  [s]
  (into #{} (map #(str/replace % #"^['`(\[\" ]+|[\])\"',.: ]+$" ""))
        (re-seq #"[a-zA-Z0-9_.'`:/-]+" (str s))))

(defn ground-task-text
  "Grounds task title + custom plan against Datahike's indexed namespaces, vars, and schemas."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :string] [:maybe :string]] :map]
   :cost/ticks 5000000}
  [db title plan]
  (let [custom-plan (strip-template-footer plan)
        txt (str (or title "") " " custom-plan)
        toks (extract-tokens txt)
        ns-map (if (contains? (:schema db) :seon.code.ns/name)
                 (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name))
                 {})
        var-map (if (contains? (:schema db) :seon.code.var/name)
                  (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
                  {})
        sch-map (if (contains? (:schema db) :seon.schema/key)
                  (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key))
                  {})
        hit-nss (vec (keep (fn [[nsym eid]]
                             (when (and (str/includes? (name nsym) ".")
                                        (contains? toks (name nsym)))
                               [nsym eid]))
                           ns-map))
        hit-vars (vec (take 8 (keep (fn [[vsym eid]]
                                      (when (contains? toks (str vsym))
                                        [vsym eid]))
                                    var-map)))
        words (into #{} (map (fn [w]
                               (let [lw (str/lower-case w)]
                                 (if (and (> (count lw) 4) (str/ends-with? lw "s"))
                                   (subs lw 0 (dec (count lw)))
                                   lw))))
                    (re-seq #"[a-zA-Z]{4,}" txt))
        hit-schemas (vec (take 4 (keep (fn [[sk eid]]
                                         (let [kw-str (str sk)
                                               ns-parts (str/split (namespace sk) #"\.")]
                                           (cond
                                             (contains? toks kw-str) [sk eid]
                                             (and (= "entity" (name sk))
                                                  (some #(and (not (contains? domain-stop-words %))
                                                              (contains? words %))
                                                        ns-parts))
                                             [sk eid])))
                                       sch-map)))]
    {:custom-plan-len (count custom-plan)
     :hit-nss hit-nss
     :hit-vars hit-vars
     :hit-schemas hit-schemas}))

(defn fast-search-vars
  "Fast flat-AEVT relevance scorer across non-test vars (0 per-var pulls, ~10 ms). Same `:ctx.match/*`
   boundary contract that `:vectors` embeddings plug into."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :map]]
   :cost/ticks 8000000}
  [db query k]
  (let [q (str/lower-case (str query))
        stops #{"the" "and" "for" "with" "from" "that" "this" "why" "how" "into" "are" "fix" "add" "use" "make" "can" "not" "all" "our" "when" "does"}
        q-terms (vec (distinct (remove stops (re-seq #"[a-z]{3,}" q))))]
    (if (or (empty? q-terms) (not (contains? (:schema db) :seon.code.var/name)))
      []
      (let [test-eids (if (contains? (:schema db) :seon.code.var/test?)
                        (reduce (fn [s dt] (if (:v dt) (conj s (:e dt)) s)) #{} (d/datoms db :aevt :seon.code.var/test?))
                        #{})
            doc-by-e  (if (contains? (:schema db) :seon.code.var/doc)
                        (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/doc))
                        {})
            form-tx-by-key (if (and (contains? (:schema db) :seon.code.form/hash)
                                    (contains? (:schema db) :seon.code.form/key))
                             (let [k-by-e (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                               (reduce (fn [m dt] (if-let [fk (get k-by-e (:e dt))] (assoc m fk (:tx dt)) m))
                                       {} (d/datoms db :aevt :seon.code.form/hash)))
                             {})
            vform-by-e (if (contains? (:schema db) :seon.code.var/form)
                         (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/form))
                         {})
            n-terms (double (count q-terms))]
        (->> (d/datoms db :aevt :seon.code.var/name)
             (keep (fn [dt]
                     (let [e (:e dt)]
                       (when-not (contains? test-eids e)
                         (let [vsym (:v dt)
                               sym-s (str/lower-case (str vsym))
                               doc-s (str/lower-case (str (get doc-by-e e "")))
                               sym-hits (count (filter #(str/includes? sym-s %) q-terms))
                               doc-hits (count (filter #(and (not (str/includes? sym-s %))
                                                             (str/includes? doc-s %)) q-terms))
                               score (/ (+ (* 2.0 sym-hits) (* 1.0 doc-hits)) (* 2.0 n-terms))]
                           (when (> score 0.0)
                             {:e e :sym vsym
                              :tx (max (long (:tx dt)) (long (get form-tx-by-key (get vform-by-e e) 0)))
                              :score (/ (Math/round (* score 1000.0)) 1000.0)}))))))
             (sort-by (juxt (comp - :score) (comp - :tx) #(str (:sym %))))
             (take k)
             vec)))))

(defn- peer-sources-for [main-db agent-id task-id]
  (let [peer-calls (if (and agent-id (contains? (:schema main-db) :seon.agent.call/caller))
                     (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                          (map #(d/pull main-db '[:seon.agent.call/callee :seon.agent.call/result :seon.agent.call/status] (:e %)))
                          (filter #(= :resolved (:seon.agent.call/status %)))
                          vec)
                     [])
        subtask-ids (when (and task-id (contains? (:schema main-db) :seon.task/needs))
                      (:seon.task/needs (d/pull main-db '[:seon.task/needs] [:seon.task/id task-id])))
        subtask-peers (for [stid subtask-ids
                            :let [t (d/pull main-db '[:seon.task/owner :seon.task/summary :seon.task/curated] [:seon.task/id stid])]
                            :when (:seon.task/owner t)]
                        {:peer (:seon.task/owner t)
                         :text (str (:seon.task/summary t))
                         :curated (:seon.task/curated t)})]
    (concat
     (map (fn [c] {:peer (:seon.agent.call/callee c) :text (pr-str (:seon.agent.call/result c))}) peer-calls)
     subtask-peers)))

(defn collect-tree-discoveries
  "Collects namespaces, vars, schemas, and curated refs discovered across child calls and subtasks in the delegation tree."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword] [:maybe :qualified-keyword]] [:vector :map]]
   :cost/ticks 8000000}
  [main-db code-db agent-id task-id]
  (let [ns-map (if (contains? (:schema code-db) :seon.code.ns/name)
                 (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.ns/name))
                 {})
        var-map (if (contains? (:schema code-db) :seon.code.var/name)
                  (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.var/name))
                  {})
        sch-map (if (contains? (:schema code-db) :seon.schema/key)
                  (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.schema/key))
                  {})]
    (vec
     (mapcat
      (fn [{:keys [peer text curated]}]
        (let [toks (extract-tokens text)
              ns-hits (for [[nsym eid] ns-map
                            :when (and (str/includes? (name nsym) ".") (contains? toks (name nsym)))]
                        {:peer peer :e eid :kind :seon.code.ns/entity :attr :seon.code.ns/name :val nsym})
              var-hits (for [[vsym eid] var-map :when (contains? toks (str vsym))]
                         {:peer peer :e eid :kind :seon.code.var/entity :attr :seon.code.var/name :val vsym})
              sch-hits (for [[sk eid] sch-map :when (contains? toks (str sk))]
                         {:peer peer :e eid :kind :seon.schema/entity :attr :seon.schema/key :val sk})
              cur-hits (for [ref curated
                             :when (and (vector? ref) (= 2 (count ref)))
                             :let [[a v] ref
                                   e (:e (first (d/datoms code-db :avet a v)))
                                   kind (case a
                                          :seon.code.var/name :seon.code.var/entity
                                          :seon.code.ns/name :seon.code.ns/entity
                                          :seon.schema/key :seon.schema/entity
                                          :seon.code.doc/id :seon.code.doc/entity
                                          :seon.task/id :seon.task/entity
                                          nil)]
                             :when (and e kind)]
                         {:peer peer :e e :kind kind :attr a :val v :curated? true})]
          (concat ns-hits var-hits sch-hits cur-hits)))
      (peer-sources-for main-db agent-id task-id)))))

(defn- apply-ground-effect [w code-db cid src-e eff]
  (if (contains? (:facts w) [src-e :ctx/grounded? true])
    w
    (let [{:keys [custom-plan-len hit-nss hit-vars hit-schemas]}
          (store/lru-get! effect-cache 64 [:ground cid (:title eff) (:plan eff)]
                          #(ground-task-text code-db (:title eff) (:plan eff)))
          prov {:rule :effect/ground :stratum 20}
          w1 (-> w
                 (rules/add-fact [src-e :ctx/grounded? true] prov)
                 (rules/add-fact [src-e :ctx/custom-plan-len custom-plan-len] prov))]
      (as-> w1 w'
        (reduce (fn [acc [nsym eid]]
                  (rules/add-facts acc [[src-e :rel/mentions-ns eid] [eid :ctx/kind :seon.code.ns/entity] [eid :seon.code.ns/name nsym]] prov))
                w' hit-nss)
        (reduce (fn [acc [vsym eid]]
                  (rules/add-facts acc [[src-e :rel/mentions-var eid] [eid :ctx/kind :seon.code.var/entity] [eid :seon.code.var/name vsym]] prov))
                w' hit-vars)
        (reduce (fn [acc [sk eid]]
                  (rules/add-facts acc [[src-e :rel/mentions-schema eid] [eid :ctx/kind :seon.schema/entity] [eid :seon.schema/key sk]] prov))
                w' hit-schemas)))))

(defn- apply-search-effect [w code-db cid src-e eff]
  (if (contains? (:facts w) [src-e :ctx/searched? true])
    w
    (let [hits (store/lru-get! effect-cache 64 [:search cid (:query eff) (:k eff 6)]
                               #(fast-search-vars code-db (:query eff) (long (or (:k eff) 6))))
          prov {:rule :effect/search :stratum 22}
          w1 (rules/add-fact w [src-e :ctx/searched? true] prov)]
      (reduce (fn [acc {:keys [e sym tx score]}]
                (rules/add-facts acc
                                 [[e :ctx/kind :seon.code.var/entity]
                                  [e :seon.code.var/name sym]
                                  [e :ctx/source :search]
                                  [e :ctx.match/via :search]
                                  [e :ctx.match/score score]
                                  [e :ctx/score (min 0.88 (+ 0.55 (* 0.35 score)))]
                                  [e :ctx/modified-tx tx]
                                  [e :ctx/why (str "Relevance search match (score " score ")")]]
                                 prov))
              w1 hits))))

(defn- apply-tree-effect [w main-db code-db cid src-e eff]
  (if (contains? (:facts w) [src-e :ctx/tree-collected? true])
    w
    (let [mcid (try (d/commit-id main-db) (catch Throwable _ (:max-tx main-db)))
          hits (store/lru-get! effect-cache 64 [:tree mcid cid (:agent eff) (:task eff)]
                               #(collect-tree-discoveries main-db code-db (:agent eff) (:task eff)))
          prov {:rule :effect/tree :stratum 20}
          w1 (rules/add-fact w [src-e :ctx/tree-collected? true] prov)]
      (reduce (fn [acc {:keys [peer e kind attr val curated?]}]
                (if (and e kind)
                  (cond-> (rules/add-facts acc [[peer :rel/tree-discovered e] [e :ctx/kind kind] [e attr val]] prov)
                    curated? (rules/add-fact [e :rel/curated true] prov))
                  acc))
              w1 hits))))

(defn execute-boundary-effects
  "Executes any pending `:ctx/need-effect` facts in `wm` at the deterministic boundary, injecting result EAV facts."
  {:malli/schema [:=> [:cat :map :map] :map]
   :cost/ticks 15000000}
  [wm dbs]
  (let [code-db (:code dbs)
        main-db (:main dbs)
        cid [(get-in code-db [:config :store :id]) (try (d/commit-id code-db) (catch Throwable _ (:max-tx code-db)))]
        reqs (for [[e a v] (:facts wm) :when (= a :ctx/need-effect)] [e v])]
    (reduce (fn [w [src-e eff]]
              (case (:op eff)
                :ground/task-text         (apply-ground-effect w code-db cid src-e eff)
                :search/relevant          (apply-search-effect w code-db cid src-e eff)
                :collect/tree-discoveries (apply-tree-effect w main-db code-db cid src-e eff)
                w))
            wm
            reqs)))
