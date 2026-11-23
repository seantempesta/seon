(ns seon.ctx.ground
  "Pure commit-cached situational grounding, lexical fallback, delegation-tree overlap,
   scope fix matching, and live `seon.vector` 4th-signal cosine scoring for `seon.ctx`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.links :refer [db-cache-sig db-schema]]
            [seon.store :as store]
            [seon.vector :as vec]))

(defonce ^:private ^:seon.flow/edge effect-cache (store/lru-cache 64))
(defonce ^:private ^:seon.flow/edge boot-tx-cache (store/lru-cache 16))

(def ^:private domain-stop-words
  #{"seon" "code" "user" "summary" "entity" "file" "form" "data" "test" "line" "name" "plan" "list" "view"})

(defn resolve-lookup-eid
  "Resolves a `[attr val]` Datahike lookup ref against `branch-db` first, falling back to `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] [:maybe :int]]
   :cost/ticks 200000}
  [main-db branch-db ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[a v] ref]
      (or (when (and branch-db (contains? (db-schema branch-db) a))
            (:e (first (d/datoms branch-db :avet a v))))
          (when (and main-db (not= main-db branch-db) (contains? (db-schema main-db) a))
            (:e (first (d/datoms main-db :avet a v))))))))

(defn baseline-import-tx
  "Returns the bulk bootstrap transaction id of `db` (cached with `datahike.reads` replay)."
  {:malli/schema [:=> [:cat :seon.host/db] :int]
   :cost/ticks 2000000}
  [db]
  (if-not (and db (contains? (db-schema db) :seon.code.form/hash))
    0
    (let [ck [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)]]
      (store/lru-get! boot-tx-cache 16 ck
                      (fn []
                        (let [tx-freqs (frequencies (map :tx (d/datoms db :aevt :seon.code.form/hash)))]
                          (if (seq tx-freqs) (long (key (apply max-key val tx-freqs))) 0)))))))

(defn strip-template-footer
  "Strips generic template instructions appended after `---` or standard Seon template boilerplate."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]
   :cost/ticks 50000}
  [plan]
  (if-not (string? plan)
    ""
    (let [s1 (first (str/split plan #"\r?\n\s*---+\s*(?:\r?\n|$)" 2))
          s2 (first (str/split (or s1 "") #"(?m)(?:^|\r?\n)\s*1\.\s+(?:\*\*Discover first:\*\*|Pull connected entities from `@seon\.ctx/ctx`|Inspect `<task-chain>`)" 2))]
      (str/trim (or s2 "")))))

(defn extract-tokens
  "Tokenizes prose/code into normalized symbol/keyword candidates."
  {:malli/schema [:=> [:cat [:maybe :string]] [:set :string]]
   :cost/ticks 200000}
  [s]
  (into #{} (map #(str/replace % #"^['`(\[\" ]+|[\])\"',.: ]+$" ""))
        (re-seq #"[a-zA-Z0-9_.'`:/-]+" (str s))))

(defn ground-task-text
  "Grounds task title + custom plan (stripped of template boilerplate) against Datahike's indexed namespaces, vars, and schemas."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :string] [:maybe :string]] :map]
   :cost/ticks 6000000}
  [db title plan]
  (let [custom-plan (strip-template-footer plan)
        txt (str (or title "") " " custom-plan)
        toks (extract-tokens txt)
        dh (db-schema db)
        ns-map (if (dh :seon.code.ns/name) (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name)) {})
        var-map (if (dh :seon.code.var/name) (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name)) {})
        sch-map (if (dh :seon.schema/key) (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key)) {})
        hit-nss (vec (keep (fn [[nsym eid]] (when (and (str/includes? (name nsym) ".") (contains? toks (name nsym))) [nsym eid])) ns-map))
        exact-vars (keep (fn [[vsym eid]] (when (contains? toks (str vsym)) [2 vsym eid])) var-map)
        hyphen-vars (keep (fn [[vsym eid]]
                            (let [vn (name vsym)]
                              (when (and (str/includes? vn "-") (>= (count vn) 8)
                                         (contains? toks vn) (not (contains? toks (str vsym))))
                                [1 vsym eid])))
                          var-map)
        hit-vars (->> (concat exact-vars hyphen-vars) (sort-by (fn [[sc vsym _]] [(- sc) (str vsym)]))
                      (mapv (fn [[_ vsym eid]] [vsym eid])) (take 8) vec)
        words (into #{} (map (fn [w] (let [lw (str/lower-case w)]
                                       (if (and (> (count lw) 4) (str/ends-with? lw "s")) (subs lw 0 (dec (count lw))) lw))))
                    (re-seq #"[a-zA-Z]{4,}" txt))
        scored-schemas
        (keep (fn [[sk eid]]
                (when-let [ns-s (namespace sk)]
                  (let [kw-str (str sk) ns-parts (str/split ns-s #"\.") last-seg (peek ns-parts)
                        exact? (contains? toks kw-str)
                        wild? (and (= "entity" (name sk)) (or (contains? toks (str ":" ns-s "/*")) (contains? toks (str ":" ns-s))))
                        seg-hits (if (= "entity" (name sk)) (filterv #(and (not (domain-stop-words %)) (words %)) ns-parts) [])
                        score (+ (if exact? 12 0) (if wild? 10 0) (* 2 (count seg-hits))
                                 (if (and (seq seg-hits) (= last-seg (peek seg-hits))) 3 0))]
                    (when (pos? score) [score (count ns-parts) sk eid]))))
              sch-map)]
    {:custom-plan-len (count custom-plan) :hit-nss hit-nss :hit-vars hit-vars
     :hit-schemas (->> scored-schemas (sort-by (fn [[sc segs sk _]] [(- sc) segs (str sk)]))
                       (take 4) (mapv (fn [[_ _ sk eid]] [sk eid])))}))

(defn fast-search-vars
  "Fast flat-AEVT lexical fallback across non-test vars for underspecified tasks when vector embeddings are cold."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :map]]
   :cost/ticks 8000000}
  [db query k]
  (let [q (str/lower-case (str query))
        stops #{"the" "and" "for" "with" "from" "that" "this" "why" "how" "into" "are" "fix" "add" "use" "make" "can" "not" "all" "our" "when" "does"}
        q-terms (vec (distinct (remove stops (re-seq #"[a-z]{3,}" q))))
        dh (db-schema db)]
    (if (or (empty? q-terms) (not (dh :seon.code.var/name)))
      []
      (let [test-eids (if (dh :seon.code.var/test?) (reduce #(if (:v %2) (conj %1 (:e %2)) %1) #{} (d/datoms db :aevt :seon.code.var/test?)) #{})
            doc-by-e (if (dh :seon.code.var/doc) (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt :seon.code.var/doc)) {})
            form-tx-by-key (if (and (dh :seon.code.form/hash) (dh :seon.code.form/key))
                             (let [k-by-e (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt :seon.code.form/key))]
                               (reduce #(if-let [fk (get k-by-e (:e %2))] (assoc %1 fk (:tx %2)) %1) {} (d/datoms db :aevt :seon.code.form/hash)))
                             {})
            vform-by-e (if (dh :seon.code.var/form) (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt :seon.code.var/form)) {})
            n-terms (double (count q-terms))]
        (->> (d/datoms db :aevt :seon.code.var/name)
             (keep (fn [dt]
                     (let [e (:e dt)]
                       (when-not (test-eids e)
                         (let [vsym (:v dt) sym-s (str/lower-case (str vsym)) doc-s (str/lower-case (str (get doc-by-e e "")))
                               sh (count (filter #(str/includes? sym-s %) q-terms))
                               dh-n (count (filter #(and (not (str/includes? sym-s %)) (str/includes? doc-s %)) q-terms))
                               sc (/ (+ (* 2.0 sh) (* 1.0 dh-n)) (* 2.0 n-terms))]
                           (when (> sc 0.0)
                             {:e e :sym vsym
                              :tx (max (long (:tx dt)) (long (get form-tx-by-key (get vform-by-e e) 0)))
                              :score (/ (Math/round (* sc 1000.0)) 1000.0)}))))))
             (sort-by (juxt (comp - :score) (comp - :tx) #(str (:sym %)))) (take k) vec)))))

(defn collect-tree-discoveries
  "Collects namespaces, vars, schemas, and curated refs discovered across child calls & subtasks in the delegation tree."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :keyword] [:maybe :qualified-keyword]] [:vector :map]]
   :cost/ticks 8000000}
  [main-db code-db agent-id task-id]
  (let [mdh (db-schema main-db)
        peer-calls (if (and agent-id (mdh :seon.agent.call/caller))
                     (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                          (map #(d/pull main-db '[:seon.agent.call/callee :seon.agent.call/result :seon.agent.call/status] (:e %)))
                          (filter #(= :resolved (:seon.agent.call/status %))) vec)
                     [])
        subtask-ids (when (and task-id (mdh :seon.task/needs))
                      (:seon.task/needs (d/pull main-db '[:seon.task/needs] [:seon.task/id task-id])))
        peers (concat (map #(hash-map :peer (:seon.agent.call/callee %) :text (pr-str (:seon.agent.call/result %))) peer-calls)
                      (for [stid subtask-ids
                            :let [t (d/pull main-db '[:seon.task/owner :seon.task/summary :seon.task/curated] [:seon.task/id stid])]
                            :when (:seon.task/owner t)]
                        {:peer (:seon.task/owner t) :text (str (:seon.task/summary t)) :curated (:seon.task/curated t)}))]
    (if (empty? peers)
      []
      (let [cdh (db-schema code-db)
            ns-map (if (cdh :seon.code.ns/name) (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.ns/name)) {})
            var-map (if (cdh :seon.code.var/name) (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.code.var/name)) {})
            sch-map (if (cdh :seon.schema/key) (into {} (map (juxt :v :e)) (d/datoms code-db :aevt :seon.schema/key)) {})]
        (vec (mapcat (fn [{:keys [peer text curated]}]
                       (let [toks (extract-tokens text)]
                         (concat
                          (for [[nsym eid] ns-map :when (and (str/includes? (name nsym) ".") (toks (name nsym)))] {:peer peer :e eid})
                          (for [[vsym eid] var-map :when (toks (str vsym))] {:peer peer :e eid})
                          (for [[sk eid] sch-map :when (toks (str sk))] {:peer peer :e eid})
                          (for [ref curated :when (and (vector? ref) (= 2 (count ref)))
                                :let [e (resolve-lookup-eid main-db code-db ref)] :when e]
                            {:peer peer :e e :curated? true}))))
                     peers))))))

(defn attach-semantic-scores
  "Enriches `candidates` with live 4th `:semantic` cosine scores (< 1 ms, zero worker IPC)
   against the agent's embedded task/scope/pin anchor vector when stored vectors exist."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :map] [:vector :map]] [:vector :map]]
   :cost/ticks 15000000}
  [main-db _branch-db ag candidates]
  (if (or (nil? main-db) (empty? candidates) (not ((db-schema main-db) :seon.vector/of)))
    candidates
    (let [tid (:seon.agent/task ag) sc (:seon.agent/scope ag) p0 (first (:seon.agent/pinned ag))
          qv (or (when tid (vec/vector-of main-db [:seon.task/id tid]))
                 (when sc (vec/vector-of main-db [:seon.code.ns/name sc]))
                 (when (vector? p0) (vec/vector-of main-db p0)))]
      (if (empty? qv)
        candidates
        (let [eids (vec (distinct (filter integer? (map :entity candidates))))
              smap (if (seq eids) (vec/semantic-scores-for main-db qv eids) {})]
          (if (empty? smap)
            candidates
            (mapv #(if-let [s (get smap (:entity %))] (assoc % :semantic (double s)) %) candidates)))))))

(defn situational-candidates
  "Derives commit-cached situational candidates for an agent: task grounding (`ground-task-text`),
   broad parent scope demotion, underspecified lexical/recency search, task-curated refs, and delegation-tree overlap."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map] :map]
   :cost/ticks 15000000}
  [main-db code-db task-db ag]
  (let [tid (:seon.agent/task ag)
        aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)
        t-pull '[:seon.task/id :seon.task/title :seon.task/plan :seon.task/tests :seon.task/template? :seon.task/curated]
        t-ent (when tid (or (when (and task-db ((db-schema task-db) :seon.task/id))
                              (let [p (d/pull task-db t-pull [:seon.task/id tid])] (when (:seon.task/id p) p)))
                            (when ((db-schema main-db) :seon.task/id)
                              (let [p (d/pull main-db t-pull [:seon.task/id tid])] (when (:seon.task/id p) p)))))
        cid (db-cache-sig code-db) mcid (db-cache-sig main-db)
        run-g #(ground-task-text code-db (:seon.task/title t-ent) (:seon.task/plan t-ent))
        g (when (and t-ent (not (:seon.task/template? t-ent)))
            (if cid (store/lru-get! effect-cache 64 [:ground cid (:seon.task/title t-ent) (:seon.task/plan t-ent)] run-g) (run-g)))
        scope-eid (when (and scope ((db-schema code-db) :seon.code.ns/name))
                    (:e (first (d/datoms code-db :avet :seon.code.ns/name scope))))
        demote-scope? (boolean (and scope-eid (some #(not= scope-eid (second %)) (:hit-nss g))))
        g-items (concat
                 (for [[_ eid] (:hit-nss g)]
                   {:entity eid :priority 95 :zone :static-prefix :tags #{:tag/mentioned}
                    :rules [:seed/task-grounding] :params {:seon.ui/rich-ns? true}})
                 (for [[_ eid] (:hit-vars g)]
                   {:entity eid :priority 94 :zone :static-prefix :tags #{:tag/mentioned}
                    :rules [:seed/task-grounding] :params {:seon.ui/show-callers? true :seon.ui/expand-section :source}})
                 (for [[_ eid] (:hit-schemas g)]
                   {:entity eid :priority 88 :zone :static-prefix :tags #{:tag/mentioned}
                    :rules [:seed/task-grounding]}))
        underspec? (and g (< (long (:custom-plan-len g 0)) 80)
                        (empty? (:seon.task/tests t-ent)) (empty? (:hit-nss g)) (empty? (:hit-vars g)))
        boot-tx (when underspec? (baseline-import-tx code-db))
        run-s #(fast-search-vars code-db (str (:seon.task/title t-ent)) 6)
        s-hits (when underspec?
                 (if cid (store/lru-get! effect-cache 64 [:search cid (:seon.task/title t-ent) 6] run-s) (run-s)))
        s-items (for [{:keys [e tx score]} s-hits
                      :let [recent? (and boot-tx (> (long tx) (long boot-tx)) (>= (double score) 0.25))]]
                  {:entity e :tx tx :ctx.match/score score
                   :priority (if recent? 91 74) :zone (if recent? :ephemeral-tail :workspace)
                   :tags (if recent? #{:tag/recent :tag/search} #{:tag/search}) :rules [:seed/task-search]
                   :params (if recent? {:seon.ui/expand-section :source :show-diff? true} {})})
        t-cur (for [ref (:seon.task/curated t-ent) :let [e (resolve-lookup-eid main-db code-db ref)] :when e]
                {:entity e :priority 94 :zone :static-prefix :tags #{:tag/curated} :rules [:seed/curated]})
        has-peers? (boolean (or (and aid ((db-schema main-db) :seon.agent.call/caller)
                                     (first (d/datoms main-db :avet :seon.agent.call/caller aid)))
                                (and tid ((db-schema main-db) :seon.task/needs)
                                     (seq (:seon.task/needs (d/pull main-db '[:seon.task/needs] [:seon.task/id tid]))))))
        run-t #(collect-tree-discoveries main-db code-db aid tid)
        tree-raw (when has-peers?
                   (if (and mcid cid) (store/lru-get! effect-cache 64 [:tree mcid cid aid tid] run-t) (run-t)))
        peer-cnt (reduce (fn [m {:keys [e peer]}] (update m e (fnil conj #{}) peer)) {} tree-raw)
        o-items (for [[e ps] peer-cnt :when (>= (count ps) 2)]
                  {:entity e :priority 94 :zone :static-prefix :tags #{:tag/tree-overlap}
                   :rules [:seed/tree-overlap] :params {:seon.ui/expand-section :source :show-source? true :seon.ui/show-callers? true}})]
    {:extra (vec (concat g-items s-items t-cur o-items))
     :demote-eid (when demote-scope? scope-eid)}))

(def ^:private broad-stop-words
  #{"seon" "code" "user" "summary" "entity" "file" "form" "data" "test" "line" "name" "plan" "list" "view"
    "agent" "task" "branch" "main" "rule" "rules" "schema" "error" "errors" "render" "context" "live" "demo"
    "with" "from" "that" "this" "into" "over" "more" "when" "where" "what" "your" "only" "also" "just" "each" "make" "work" "system" "status" "health" "wire" "surface" "graph" "driven" "interactive" "perspective"})

(defn fix-matches-scope?
  "True when a `:seon.fix/*` record matches a narrower dotted namespace `scope`, or (on broad `'seon`/`nil` scope)
   matches an explicit error-class or >= 2 distinctive tokens in optional `ctx-text` (active task/error context)."
  {:malli/schema [:function
                  [:=> [:cat :any :any :any :any] :boolean]
                  [:=> [:cat :any :any :any :any :any] :boolean]]
   :cost/ticks 100000}
  ([scope error-class change-summary regression-test]
   (fix-matches-scope? scope error-class change-summary regression-test nil))
  ([scope error-class change-summary regression-test ctx-text]
   (let [sn (when (symbol? scope) (name scope))
         hay (str error-class " " change-summary " " regression-test)]
     (if (and sn (not= sn "seon") (str/includes? sn "."))
       (str/includes? hay sn)
       (if (or (nil? ctx-text) (str/blank? (str ctx-text)))
         false
         (let [ctx-lc (str/lower-case (str ctx-text))
               hay-lc (str/lower-case hay)
               ec-hit? (when (keyword? error-class)
                         (or (str/includes? ctx-lc (str/lower-case (str error-class)))
                             (let [en (str/lower-case (name error-class))]
                               (and (>= (count en) 5) (str/includes? ctx-lc en)))))]
           (if ec-hit?
             true
             (let [c-toks (->> (re-seq #"[a-z][a-z0-9_-]{4,}" ctx-lc)
                               (remove broad-stop-words)
                               distinct)]
               (>= (count (filter #(str/includes? hay-lc %) c-toks)) 2)))))))))

(defn- latest-test-run [db br]
  (let [dh (db-schema db)]
    (when (and db br (dh :seon.test.run/branch) (dh :seon.test.run/pass?))
      (when-let [e (:e (first (d/datoms db :avet :seon.test.run/branch br)))]
        (when-let [pdt (first (d/datoms db :eavt e :seon.test.run/pass?))]
          {:db db :e e :pass? (true? (:v pdt)) :tx (long (:tx pdt 0))})))))

(defn failing-test-digests
  "Returns failing `:seon.test/digest` strings from the latest `:seon.test.run/*` for `agent-id`'s branch (or `:main`).
   Uses O(|failed-digests|) indexed `:avet` lookups instead of scanning historical `:seon.test/pass? false` rows."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:vector :string]]
   :cost/ticks 2000000}
  [main-db branch-db agent-id]
  (let [bdh (db-schema branch-db) mdh (db-schema main-db)
        ag-br (fn [db dh]
                (when (and agent-id db (dh :seon.agent/id) (dh :seon.agent/branch))
                  (when-let [ae (:e (first (d/datoms db :avet :seon.agent/id agent-id)))]
                    (:v (first (d/datoms db :eavt ae :seon.agent/branch))))))
        br (or (ag-br branch-db bdh) (ag-br main-db mdh) (get-in branch-db [:config :branch]) :main)
        pick (fn [b]
               (let [r1 (latest-test-run branch-db b)
                     r2 (when (and main-db (not (identical? main-db branch-db))) (latest-test-run main-db b))]
                 (cond (and r1 r2) (if (>= (:tx r1) (:tx r2)) r1 r2) :else (or r1 r2))))
        run (or (pick br) (when (not= br :main) (pick :main)))]
    (if run
      (if (:pass? run)
        []
        (let [rdb (:db run)]
          (if ((db-schema rdb) :seon.test.run/failed-digests)
            (vec (distinct (map :v (d/datoms rdb :eavt (:e run) :seon.test.run/failed-digests))))
            [])))
      (if (and branch-db (bdh :seon.test/pass?) (bdh :seon.test/var-ref) (bdh :seon.test/digest)
               (nil? (when (bdh :seon.test.run/branch) (first (d/datoms branch-db :aevt :seon.test.run/branch))))
               (nil? (when (and main-db (mdh :seon.test.run/branch)) (first (d/datoms main-db :aevt :seon.test.run/branch)))))
        (vec (distinct
              (keep (fn [dt]
                      (when (false? (:v dt))
                        (let [e (:e dt) tx (long (:tx dt 0))
                              vr (:v (first (d/datoms branch-db :eavt e :seon.test/var-ref)))
                              dg (:v (first (d/datoms branch-db :eavt e :seon.test/digest)))
                              cleared? (when vr
                                         (some (fn [vdt]
                                                 (when-let [pdt (first (d/datoms branch-db :eavt (:e vdt) :seon.test/pass?))]
                                                   (and (true? (:v pdt)) (>= (long (:tx pdt 0)) tx))))
                                               (d/datoms branch-db :avet :seon.test/var-ref vr)))]
                          (when (and vr dg (not cleared?)) dg))))
                    (d/datoms branch-db :aevt :seon.test/pass?))))
        []))))

(defn error-referenced-eids
  "Resolves up to 4 referenced var, schema, or namespace EIDs mentioned in `error-text` / `code-text` via indexed `:avet` lookups."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :string] [:maybe :string]] [:vector :int]]
   :cost/ticks 1500000}
  [db error-text code-text]
  (let [dh (db-schema db)
        txt (str (or error-text "") " " (or code-text ""))]
    (if (or (nil? db) (str/blank? txt))
      []
      (let [var-eids (when (dh :seon.code.var/name)
                       (keep (fn [s]
                               (when-not (str/starts-with? s ":")
                                 (let [idx (.indexOf ^String s "/")]
                                   (when (pos? idx)
                                     (:e (first (d/datoms db :avet :seon.code.var/name (symbol (subs s 0 idx) (subs s (inc idx))))))))))
                             (distinct (re-seq #"(?<![a-zA-Z0-9_.:/-])[a-zA-Z][a-zA-Z0-9_.-]*/[a-zA-Z0-9_?!*+-]+" txt))))
            sch-eids (when (dh :seon.schema/key)
                       (keep (fn [s]
                               (let [k (subs s 1) idx (.indexOf ^String k "/")]
                                 (when (pos? idx)
                                   (:e (first (d/datoms db :avet :seon.schema/key (keyword (subs k 0 idx) (subs k (inc idx)))))))))
                             (distinct (re-seq #"(?<![a-zA-Z0-9_.-]):[a-zA-Z][a-zA-Z0-9_.-]*/[a-zA-Z0-9_?!*+-]+" txt))))
            ns-eids (when (dh :seon.code.ns/name)
                      (keep (fn [s]
                              (:e (first (d/datoms db :avet :seon.code.ns/name (symbol s)))))
                            (distinct (re-seq #"(?<![a-zA-Z0-9_.:/-])[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z0-9_-]+)+" txt))))]
        (->> (concat var-eids sch-eids ns-eids) distinct (take 4) vec)))))

(defn ^:seon.flow/edge branch-form-drift
  "Computes O(|branch-edits|) edited form keys, hashes, and true concurrent `:main` drift via `d/merge-base`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:sequential :int]] :map]
   :cost/ticks 5000000}
  [main-db branch-db edited-eids]
  (let [pairs (vec (keep (fn [e]
                           (when-let [fk (:v (first (d/datoms branch-db :eavt e :seon.code.form/key)))]
                             [fk (:v (first (d/datoms branch-db :eavt e :seon.code.form/hash)))]))
                         edited-eids))
        hashes (into {} pairs)
        edited (mapv first pairs)
        mc (when (instance? datahike.db.DB main-db) (d/commit-id main-db))
        bc (when (instance? datahike.db.DB branch-db) (d/commit-id branch-db))
        fhash (fn [db fk]
                (when (and db ((db-schema db) :seon.code.form/key))
                  (when-let [e (:e (first (d/datoms db :avet :seon.code.form/key fk)))]
                    (:v (first (d/datoms db :eavt e :seon.code.form/hash))))))
        drifted (if (or (empty? pairs) (nil? main-db) (nil? mc) (nil? bc) (= mc bc))
                  []
                  (let [base-cid (try (d/merge-base branch-db mc bc) (catch Throwable _ nil))]
                    (if (and base-cid (not= base-cid mc))
                      (if-let [base-db (try (d/commit-as-db branch-db base-cid) (catch Throwable _ nil))]
                        (vec (keep (fn [[fk th]]
                                     (let [bh (fhash base-db fk) mh (fhash main-db fk)]
                                       (when (and (not= mh bh) (not= mh th)) fk)))
                                   pairs))
                        [])
                      [])))]
    {:edited edited :hashes hashes :drifted drifted}))
