(ns seon.cite
  "Atomic Markdown citations, Datahike `:seon.code.doc/references` linking, stale checks, and rules."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]))

(def ^:private url-tlds #{"com" "org" "net" "io" "dev" "edu" "gov"})
(def ^:private non-ns-exts
  (into url-tlds #{"md" "clj" "cljc" "cljs" "edn" "py" "sh" "zsh" "json" "yaml" "yml" "toml"
                   "xml" "html" "css" "js" "ts" "txt" "c" "h" "m" "ss" "dylib" "so" "a" "o"
                   "db" "mdb" "lock" "log" "png" "svg" "jpg" "pdf" "master3"}))
(def ^:private path-prefixes
  #{"bin" "src" "test" "docs" "resources" "native" "target" "bench" "reference-code" "runs" "tools"})
(def ^:private meta-ns #{"seon.db" "seon.flow" "seon.eval" "seon.merge"})
(def ^:private dotted-ns-re #"[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+")
(def ^:private sym-name-re
  #"(?:[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*|-[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*)")

(defn- valid-ns? [^String s]
  (and (<= 3 (.length s) 120) (boolean (re-matches dotted-ns-re s))))

(defn- strip-fenced-blocks [^String text]
  (if-not (or (str/includes? text "```") (str/includes? text "~~~"))
    text
    (loop [ls (str/split text #"\n" -1), open nil, out []]
      (if-not (seq ls)
        (str/join "\n" out)
        (let [l (first ls), tr (str/triml l)]
          (if open
            (let [[ch flen] open, run (count (take-while #(= ch %) tr))]
              (recur (rest ls) (when-not (and (>= run flen) (str/blank? (subs tr run))) open) (conj out "")))
            (let [ch (cond (str/starts-with? tr "```") \` (str/starts-with? tr "~~~") \~)
                  flen (when ch (count (take-while #(= ch %) tr)))
                  open? (and flen (>= flen 3) (<= (- (count l) (count tr)) 3)
                             (or (= ch \~) (not (str/includes? (subs tr flen) "`"))))]
              (recur (rest ls) (when open? [ch flen]) (conj out (if open? "" l))))))))))

(defn- scan-inline-spans [^String text]
  (if-not (str/includes? text "`")
    {:spans [] :masked text}
    (let [n (.length text), sb (StringBuilder.)
          run-end (fn [p lim] (loop [x p] (if (and (< x lim) (= \` (.charAt text x))) (recur (inc x)) x)))
          closer (fn [from pe w]
                   (loop [p (int from)]
                     (let [j (.indexOf text "`" p)]
                       (when (and (not (neg? j)) (< j pe))
                         (let [k (run-end j pe)] (if (= w (- k j)) [j k] (recur k)))))))]
      (loop [i 0, pe nil, dead #{}, spans (transient [])]
        (let [j (when (< i n) (.indexOf text "`" (int i)))]
          (if (or (nil? j) (neg? j))
            (do (when (< i n) (.append sb (subs text i n)))
                {:spans (persistent! spans) :masked (.toString sb)})
            (let [k (run-end j n), w (- k j), crossed? (and pe (>= j pe))
                  pe' (if (or (nil? pe) crossed?) (let [pp (.indexOf text "\n\n" (int j))] (if (neg? pp) n pp)) pe)
                  dead' (if crossed? #{} dead)
                  found (when (and (< k pe') (not (contains? dead' w))) (closer k pe' w))]
              (if found
                (let [[cs ce] found]
                  (.append sb (subs text i j)) (.append sb "x")
                  (recur ce pe' dead' (conj! spans (subs text k cs))))
                (do (.append sb (subs text i k))
                    (recur k pe' (conj dead' w) spans))))))))))

(defn- normalize-doc-target [^String raw allow-bare?]
  (let [s (-> raw str/trim (str/replace #"^<|>$" ""))]
    (when (and (seq s) (<= (count s) 240)
               (not (re-find #"^(?:[#/]|[a-zA-Z][a-zA-Z0-9+.-]*:)" s))
               (not (str/includes? s "://"))
               (not (re-find #"[*\\<>{}\s]" s)))
      (let [clean (str/replace s #"^(?:\.\./|\./)+" "")
            [bh frag] (str/split clean #"#" 2)
            p (first (str/split bh #"\?" 2))]
        (when (and (str/ends-with? p ".md") (> (count p) 3)
                   (not (str/ends-with? p "/.md")) (not (str/includes? p ":"))
                   (or allow-bare? (= p "AGENTS.md") (str/starts-with? p "docs/"))
                   (or (nil? frag) (not (str/blank? frag))))
          (if (seq frag) (str p "#" frag) p))))))

(defn- extract-md-links [^String masked]
  (if-not (str/includes? masked "](")
    #{}
    (into #{} (keep (fn [[_ a b]] (normalize-doc-target (or a b) true)))
          (re-seq #"(?<!!)\[[^\]\n]+\]\(\s*(?:<([^>\n]+)>|([^\s()\n]+))(?:\s+\"[^\"\n]*\")?\s*\)" masked))))

(defn- valid-star? [^String tok]
  (or (not (str/includes? tok "*"))
      (when-let [sl (str/index-of tok "/")]
        (let [nm (subs tok (inc sl))]
          (and (not (str/includes? (subs tok 0 sl) "*"))
               (> (count nm) 2) (str/starts-with? nm "*") (str/ends-with? nm "*"))))))

(defn- classify-token [^String tok]
  (when (and tok (<= (count tok) 200)
             (not (str/includes? tok "://")) (not (str/starts-with? tok "www."))
             (not (str/starts-with? tok "mailto:")) (not (str/includes? tok "\\"))
             (not (re-find #"<[a-zA-Z0-9._-]+>" tok)) (valid-star? tok))
    (cond
      (and (not (str/starts-with? tok ":")) (str/includes? tok ".md"))
      (when-let [doc-id (normalize-doc-target tok false)] [:docs doc-id])

      (str/includes? tok "/")
      (let [kw? (str/starts-with? tok ":"), body (if kw? (subs tok 1) tok), sl (str/index-of body "/")]
        (when (and sl (pos? sl) (< (inc sl) (count body))
                   (not (str/starts-with? tok "::")) (not (str/starts-with? tok "/"))
                   (not (str/index-of body "/" (inc sl))))
          (let [ns-p (subs body 0 sl), nm-p (subs body (inc sl))]
            (when (and (valid-ns? ns-p) (re-matches sym-name-re nm-p))
              (if kw?
                (when-not (str/starts-with? ns-p "db.") [:schemas (keyword ns-p nm-p)])
                (when-not (or (contains? url-tlds (subs ns-p (inc (str/last-index-of ns-p "."))))
                              (contains? path-prefixes (subs ns-p 0 (str/index-of ns-p "."))))
                  [:vars (symbol ns-p nm-p)]))))))

      (and (not (str/starts-with? tok ":")) (valid-ns? tok))
      (when-not (contains? non-ns-exts (subs tok (inc (str/last-index-of tok "."))))
        [:nss (symbol tok)]))))

(defn parse-citations
  "Parses backticked spans and Markdown links into `{:vars :nss :schemas :docs}`."
  {:malli/schema [:=> {:examples [[["See `seon.code/eval-forms`, `seon.rules`, `:seon.task/id`, and [render](docs/render.md#sec)."]
                                    {:vars #{'seon.code/eval-forms} :nss #{'seon.rules}
                                     :schemas #{:seon.task/id} :docs #{"docs/render.md#sec"}}]]}
                  [:cat :string]
                  [:map [:vars [:set :qualified-symbol]] [:nss [:set :symbol]]
                   [:schemas [:set :qualified-keyword]] [:docs [:set :string]]]]
   :cost/ticks 5000000
   :cost/bytes 8388608}
  [text]
  (if (str/blank? text)
    {:vars #{} :nss #{} :schemas #{} :docs #{}}
    (let [{:keys [spans masked]} (scan-inline-spans (strip-fenced-blocks text))]
      (reduce
       (fn [acc span]
         (if (str/blank? span)
           acc
           (reduce (fn [a raw]
                     (let [tok (-> raw (str/replace #"^(?:#'|['@^~])+" "") (str/replace #"[.,;:]+$" ""))]
                       (if-let [[k v] (when (seq tok) (classify-token tok))] (update a k conj v) a)))
                   acc (str/split span #"[ \t\n\r()\[\]{}\",;]+"))))
       {:vars #{} :nss #{} :schemas #{} :docs (extract-md-links masked)}
       spans))))

(defn resolve-doc-refs
  "Resolves parsed citations in `text` via indexed `:avet` lookups into `{:refs :unresolved}`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :string]
                  [:map [:refs [:set :int]] [:unresolved [:set :any]]]]
   :cost/ticks 5000000}
  [db text]
  (let [{:keys [vars nss schemas]} (parse-citations (or text ""))]
    (if-not db
      {:refs #{} :unresolved (into #{} (concat vars nss schemas))}
      (let [sch (:schema db)
            step (fn [acc attr xs]
                   (let [ok? (contains? sch attr)]
                     (reduce (fn [m x]
                               (if-let [e (when ok? (try (:e (first (d/datoms db :avet attr x))) (catch Throwable _ nil)))]
                                 (update m :refs conj e)
                                 (update m :unresolved conj x)))
                             acc xs)))]
        (-> {:refs #{} :unresolved #{}}
            (step :seon.code.var/name vars)
            (step :seon.code.ns/name nss)
            (step :seon.schema/key schemas))))))

(defn enrich-doc-sections
  "Attaches resolved `:seon.code.doc/references` to parsed `:seon.code.doc/*` section maps."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:sequential :map]] [:vector :map]]
   :cost/ticks 10000000}
  [db sections]
  (if-not (and db (map? (:schema db)) (contains? (:schema db) :seon.code.doc/references))
    (vec sections)
    (mapv (fn [sec]
            (let [{:keys [refs]} (resolve-doc-refs db (str (:seon.code.doc/heading sec "") "\n" (:seon.code.doc/body sec "")))]
              (if (seq refs) (assoc sec :seon.code.doc/references refs) (dissoc sec :seon.code.doc/references))))
          sections)))

(defn- doc-entries [db]
  (let [sch (:schema db)
        scan (fn [a] (if (contains? sch a) (into {} (map (juxt :e :v)) (d/datoms db :aevt a)) {}))
        ids (scan :seon.code.doc/id), ps (scan :seon.code.doc/path)
        hs (scan :seon.code.doc/heading), bs (scan :seon.code.doc/body)]
    (mapv (fn [[e did]] {:eid e :id did :path (get ps e "") :heading (get hs e "") :body (get bs e "")})
          (sort-by val ids))))

(defn backfill-doc-refs-tx
  "Builds O(|docs|) tx-data (with stale retractions) linking all `:seon.code.doc/*` entities in `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :any]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db (contains? sch :seon.code.doc/id) (contains? sch :seon.code.doc/references))
      []
      (let [idx (fn [a] (if (contains? sch a) (into {} (map (juxt :v :e)) (d/datoms db :aevt a)) {}))
            v->e (idx :seon.code.var/name), n->e (idx :seon.code.ns/name), s->e (idx :seon.schema/key)
            cur-by-e (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) (:v dt))) {} (d/datoms db :aevt :seon.code.doc/references))]
        (vec
         (mapcat
          (fn [{:keys [eid id heading body]}]
            (let [{:keys [vars nss schemas]} (parse-citations (str heading "\n" body))
                  desired (into #{} (concat (keep v->e vars) (keep n->e nss) (keep s->e schemas)))
                  cur (get cur-by-e eid #{})
                  rems (for [g (sort (set/difference cur desired))] [:db/retract [:seon.code.doc/id id] :seon.code.doc/references g])
                  adds (set/difference desired cur)]
              (cond-> (vec rems) (seq adds) (conj {:seon.code.doc/id id :seon.code.doc/references adds}))))
          (doc-entries db)))))))

(defn stale-doc-citations
  "Returns doc sections citing deleted or nonexistent `seon.*` vars or schemas."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db (contains? sch :seon.code.doc/id))
      []
      (let [br (get-in db [:config :branch] :main)
            vset (fn [a] (if (contains? sch a) (into #{} (map :v) (d/datoms db :aevt a)) #{}))
            lvars (vset :seon.code.var/name), lschs (vset :seon.schema/key), ltasks (vset :seon.task/id)
            seon? #(and (string? %) (or (= % "seon") (str/starts-with? % "seon.")))]
        (->> (doc-entries db)
             (keep (fn [{:keys [id path heading body]}]
                     (let [{:keys [vars schemas]} (parse-citations (str heading "\n" body))
                           mv (->> vars (filter #(and (seon? (namespace %)) (not (contains? lvars %)))) (sort-by str) vec)
                           ms (->> schemas (filter #(let [n (namespace %)]
                                                      (and (seon? n) (not (contains? meta-ns n)) (not= :seon.render/hiccup %)
                                                           (not (contains? lschs %)) (not (contains? ltasks %)))))
                                   (sort-by str) vec)]
                       (when (or (seq mv) (seq ms))
                         {:doc-id id :path path :heading heading :missing-vars mv :missing-schemas ms
                          :class :stale-doc-citation :severity :warning :kind :static :subject id :branch br
                          :message (str "Stale citations in " id ": " (str/join ", " (concat (map str mv) (map str ms))))}))))
             vec)))))

(defn cite-rules
  "Declarative `:seon.rule/*` rules surfacing docs linked via `:seon.code.doc/references`."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  [{:seon.rule/id :rule.expand/doc-by-ref
    :seon.rule/doc "Surface doc sections linking via :seon.code.doc/references to active entities."
    :seon.rule/category :expand
    :seon.rule/stratum 32
    :seon.rule/enabled? true
    :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind ?k]
                      [:guard (or (= ?k :seon.code.var/entity) (= ?k :seon.code.ns/entity) (= ?k :seon.schema/entity))]
                      [:db :code [?d :seon.code.doc/references ?e]]
                      [:db :code [?d :seon.code.doc/id ?did]]
                      [:not [?d :ctx/kind :seon.code.doc/entity]]]
    :seon.rule/assert '[[?e :rel/cited-by-doc ?d]
                        [?d :ctx/kind :seon.code.doc/entity]
                        [?d :ctx/source :rule]
                        [?d :seon.code.doc/id ?did]
                        [?d :ctx/score 0.8]
                        [?d :view/tag :tag/doc-ref]
                        [?d :ctx/why "Cited by doc"]]}])
