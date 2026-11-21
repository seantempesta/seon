(ns seon.cite
  "Atomic Markdown citations, Datahike `:seon.code.doc/references` linking, stale-doc checks, and rules."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]))

(def ^:private non-ns-exts
  #{"md" "clj" "cljc" "cljs" "edn" "py" "sh" "zsh" "json" "yaml" "yml" "toml"
    "xml" "html" "css" "js" "ts" "txt" "c" "h" "m" "ss" "dylib" "so" "a" "o"
    "db" "mdb" "lock" "log" "png" "svg" "jpg" "pdf" "com" "org" "net" "io"
    "dev" "edu" "gov" "master3"})

(def ^:private path-prefixes
  #{"bin" "src" "test" "docs" "resources" "native" "target" "bench"
    "reference-code" "runs" "tools"})

(def ^:private url-tlds #{"com" "org" "net" "io" "dev" "edu" "gov"})
(def ^:private meta-ns #{"seon.db" "seon.flow" "seon.eval" "seon.merge"})
(def ^:private dotted-ns-re #"[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+")
(def ^:private sym-name-re
  #"(?:[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*|-[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*)")

(defn- valid-dotted-ns? [^String s]
  (and (<= 3 (.length s) 120) (boolean (re-matches dotted-ns-re s))))

(defn- strip-fenced-blocks [^String text]
  (if-not (or (str/includes? text "```") (str/includes? text "~~~"))
    text
    (loop [ls (str/split text #"\n" -1), open nil, out []]
      (if-not (seq ls)
        (str/join "\n" out)
        (let [l (first ls) tr (str/triml l)]
          (if open
            (let [[ch flen] open
                  run (count (take-while #(= ch %) tr))]
              (recur (rest ls) (when-not (and (>= run flen) (str/blank? (subs tr run))) open) (conj out "")))
            (let [ch (cond (str/starts-with? tr "```") \` (str/starts-with? tr "~~~") \~)
                  flen (when ch (count (take-while #(= ch %) tr)))
                  open? (and flen (>= flen 3) (<= (- (count l) (count tr)) 3)
                             (or (= ch \~) (not (str/includes? (subs tr flen) "`"))))]
              (recur (rest ls) (when open? [ch flen]) (conj out (if open? "" l))))))))))

(defn- scan-inline-spans [^String text]
  (if-not (str/includes? text "`")
    {:spans [] :masked text}
    (let [n (.length text)
          sb (StringBuilder.)
          run-end (fn [p lim] (loop [x p] (if (and (< x lim) (= \` (.charAt text x))) (recur (inc x)) x)))
          find-closer (fn [from p-end w]
                        (loop [p (int from)]
                          (let [j (.indexOf text "`" p)]
                            (when (and (not (neg? j)) (< j p-end))
                              (let [k (run-end j p-end)] (if (= w (- k j)) [j k] (recur k)))))))]
      (loop [i 0, para-end nil, dead #{}, spans (transient [])]
        (if (>= i n)
          {:spans (persistent! spans) :masked (.toString sb)}
          (let [j (.indexOf text "`" (int i))]
            (if (neg? j)
              (do (.append sb (subs text i n)) {:spans (persistent! spans) :masked (.toString sb)})
              (let [k (run-end j n)
                    w (- k j)
                    crossed? (and para-end (>= j para-end))
                    p-end (if (or (nil? para-end) crossed?)
                            (let [pp (.indexOf text "\n\n" (int j))] (if (neg? pp) n pp))
                            para-end)
                    dead' (if crossed? #{} dead)
                    found (when (and (< k p-end) (not (contains? dead' w))) (find-closer k p-end w))]
                (if found
                  (let [[cs ce] found]
                    (.append sb (subs text i j)) (.append sb "x")
                    (recur ce p-end dead' (conj! spans (subs text k cs))))
                  (do (.append sb (subs text i k))
                      (recur k p-end (conj dead' w) spans)))))))))))

(defn- normalize-doc-target [^String raw allow-bare?]
  (let [s (-> raw str/trim (str/replace #"^<|>$" ""))]
    (when (and (seq s) (<= (count s) 240)
               (not (str/starts-with? s "#")) (not (str/starts-with? s "/"))
               (not (str/includes? s "://"))
               (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s))
               (not (re-find #"[*\\<>{}\s]" s)))
      (let [clean (str/replace s #"^(?:\.\./|\./)+" "")
            [before-h frag] (str/split clean #"#" 2)
            p (first (str/split before-h #"\?" 2))]
        (when (and (str/ends-with? p ".md") (> (count p) 3)
                   (not (str/ends-with? p "/.md")) (not (str/includes? p ":"))
                   (or allow-bare? (= p "AGENTS.md") (str/starts-with? p "docs/"))
                   (or (nil? frag) (not (str/blank? frag))))
          (if (seq frag) (str p "#" frag) p))))))

(defn- extract-md-links [^String masked]
  (if-not (str/includes? masked "](")
    #{}
    (into #{}
          (keep (fn [[_ angle bare]] (normalize-doc-target (or angle bare) true)))
          (re-seq #"(?<!!)\[[^\]\n]+\]\(\s*(?:<([^>\n]+)>|([^\s()\n]+))(?:\s+\"[^\"\n]*\")?\s*\)" masked))))

(defn- clean-token [^String raw]
  (let [s (-> raw
              (str/replace #"^(?:#'|['@^~])+" "")
              (str/replace #"[.,;:]+$" ""))]
    (when (seq s) s)))

(defn- valid-star? [^String tok]
  (or (not (str/includes? tok "*"))
      (when-let [slash (str/index-of tok "/")]
        (let [nm (subs tok (inc slash))]
          (and (not (str/includes? (subs tok 0 slash) "*"))
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
      (let [kw? (str/starts-with? tok ":")
            body (if kw? (subs tok 1) tok)
            slash (str/index-of body "/")]
        (when (and slash (pos? slash) (< (inc slash) (count body))
                   (not (str/starts-with? tok "::")) (not (str/starts-with? tok "/"))
                   (not (str/index-of body "/" (inc slash))))
          (let [ns-p (subs body 0 slash) nm-p (subs body (inc slash))]
            (when (and (valid-dotted-ns? ns-p) (re-matches sym-name-re nm-p))
              (if kw?
                (when-not (str/starts-with? ns-p "db.")
                  [:schemas (keyword ns-p nm-p)])
                (let [last-seg (subs ns-p (inc (str/last-index-of ns-p ".")))
                      first-seg (subs ns-p 0 (str/index-of ns-p "."))]
                  (when (and (not (contains? url-tlds last-seg))
                             (not (contains? path-prefixes first-seg)))
                    [:vars (symbol ns-p nm-p)])))))))

      (and (not (str/starts-with? tok ":")) (valid-dotted-ns? tok))
      (when-not (contains? non-ns-exts (subs tok (inc (str/last-index-of tok "."))))
        [:nss (symbol tok)])

      :else nil)))

(defn parse-citations
  "Pure extractor parsing backticked code spans and Markdown links from `text` into
   `{:vars #{sym ...} :nss #{sym ...} :schemas #{kw ...} :docs #{doc-id ...}}`."
  {:malli/schema [:=> {:examples [[["See `seon.code/eval-forms`, `seon.rules`, `:seon.task/id`, and [render](docs/render.md#sec)."]
                                    {:vars #{'seon.code/eval-forms}
                                     :nss #{'seon.rules}
                                     :schemas #{:seon.task/id}
                                     :docs #{"docs/render.md#sec"}}]]}
                  [:cat :string]
                  [:map
                   [:vars [:set :qualified-symbol]]
                   [:nss [:set :symbol]]
                   [:schemas [:set :qualified-keyword]]
                   [:docs [:set :string]]]]
   :cost/ticks 15000000
   :cost/bytes 16777216}
  [text]
  (if (str/blank? text)
    {:vars #{} :nss #{} :schemas #{} :docs #{}}
    (let [{:keys [spans masked]} (scan-inline-spans (strip-fenced-blocks text))]
      (reduce
       (fn [acc span]
         (if (str/blank? span)
           acc
           (reduce (fn [a raw]
                     (if-let [[k v] (some-> (clean-token raw) classify-token)]
                       (update a k conj v)
                       a))
                   acc
                   (str/split span #"[ \t\n\r()\[\]{}\",;]+"))))
       {:vars #{} :nss #{} :schemas #{} :docs (extract-md-links masked)}
       spans))))

(defn resolve-doc-refs
  "Resolves parsed citations in `text` against `db` via indexed `:avet` lookups on
   `:seon.code.var/name`, `:seon.code.ns/name`, and `:seon.schema/key`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :string]
                  [:map [:refs [:set :int]] [:unresolved [:set :any]]]]
   :cost/ticks 5000000}
  [db text]
  (let [{:keys [vars nss schemas]} (parse-citations (or text ""))]
    (if-not db
      {:refs #{} :unresolved (into #{} (concat vars nss schemas))}
      (let [sch (:schema db)
            step (fn [acc attr items]
                   (let [ok? (contains? sch attr)]
                     (reduce (fn [m item]
                               (if-let [eid (when ok? (try (:e (first (d/datoms db :avet attr item))) (catch Throwable _ nil)))]
                                 (update m :refs conj eid)
                                 (update m :unresolved conj item)))
                             acc items)))]
        (-> {:refs #{} :unresolved #{}}
            (step :seon.code.var/name vars)
            (step :seon.code.ns/name nss)
            (step :seon.schema/key schemas))))))

(defn enrich-doc-sections
  "Attaches resolved `:seon.code.doc/references` entity ID sets to parsed `:seon.code.doc/*`
   section maps when `db` carries `:seon.code.doc/references` in `(:schema db)`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:sequential :map]] [:vector :map]]
   :cost/ticks 10000000}
  [db sections]
  (if-not (and db (map? (:schema db)) (contains? (:schema db) :seon.code.doc/references))
    (vec sections)
    (mapv (fn [sec]
            (let [txt (str (:seon.code.doc/heading sec "") "\n" (:seon.code.doc/body sec ""))
                  {:keys [refs]} (resolve-doc-refs db txt)]
              (if (seq refs)
                (assoc sec :seon.code.doc/references refs)
                (dissoc sec :seon.code.doc/references))))
          sections)))

(defn- doc-entries [db]
  (let [sch (:schema db)
        scan (fn [a] (if (contains? sch a) (into {} (map (juxt :e :v)) (d/datoms db :aevt a)) {}))
        ids (scan :seon.code.doc/id)
        paths (scan :seon.code.doc/path)
        heads (scan :seon.code.doc/heading)
        bodies (scan :seon.code.doc/body)]
    (mapv (fn [[eid did]]
            {:eid eid :id did :path (get paths eid "")
             :heading (get heads eid "") :body (get bodies eid "")})
          (sort-by val ids))))

(defn backfill-doc-refs-tx
  "Builds O(|docs|) tx-data (including stale retractions) linking all existing `:seon.code.doc/*`
   entities in `db` to their true `:seon.code.doc/references` entity IDs."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :any]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db (contains? sch :seon.code.doc/id) (contains? sch :seon.code.doc/references))
      []
      (let [idx (fn [a] (if (contains? sch a) (into {} (map (juxt :v :e)) (d/datoms db :aevt a)) {}))
            v->e (idx :seon.code.var/name)
            n->e (idx :seon.code.ns/name)
            s->e (idx :seon.schema/key)
            cur-by-e (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) (:v dt)))
                             {} (d/datoms db :aevt :seon.code.doc/references))]
        (vec
         (mapcat
          (fn [{:keys [eid id heading body]}]
            (let [{:keys [vars nss schemas]} (parse-citations (str heading "\n" body))
                  desired (into #{} (concat (keep v->e vars) (keep n->e nss) (keep s->e schemas)))
                  cur (get cur-by-e eid #{})
                  rems (for [g (sort (set/difference cur desired))]
                         [:db/retract [:seon.code.doc/id id] :seon.code.doc/references g])
                  adds (set/difference desired cur)]
              (cond-> (vec rems)
                (seq adds) (conj {:seon.code.doc/id id :seon.code.doc/references adds}))))
          (doc-entries db)))))))

(defn- seon-ns? [ns-s]
  (and (string? ns-s) (or (= ns-s "seon") (str/starts-with? ns-s "seon."))))

(defn stale-doc-citations
  "Pure health/audit helper returning structured findings for any `:seon.code.doc/*` sections
   citing deleted or nonexistent `seon.*` vars or schemas."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db (contains? sch :seon.code.doc/id))
      []
      (let [br (get-in db [:config :branch] :main)
            vset (fn [a] (if (contains? sch a) (into #{} (map :v) (d/datoms db :aevt a)) #{}))
            live-vars (vset :seon.code.var/name)
            live-schemas (vset :seon.schema/key)
            live-tasks (vset :seon.task/id)]
        (->> (doc-entries db)
             (keep (fn [{:keys [id path heading body]}]
                     (let [{:keys [vars schemas]} (parse-citations (str heading "\n" body))
                           mv (->> vars (filter #(and (seon-ns? (namespace %)) (not (contains? live-vars %)))) (sort-by str) vec)
                           ms (->> schemas
                                   (filter #(let [ns-s (namespace %)]
                                              (and (seon-ns? ns-s) (not (contains? meta-ns ns-s))
                                                   (not= :seon.render/hiccup %)
                                                   (not (contains? live-schemas %))
                                                   (not (contains? live-tasks %)))))
                                   (sort-by str) vec)]
                       (when (or (seq mv) (seq ms))
                         {:doc-id id :path path :heading heading
                          :missing-vars mv :missing-schemas ms
                          :class :stale-doc-citation :severity :warning :kind :static
                          :subject id :branch br
                          :message (str "Doc section " id " cites missing "
                                        (str/join ", " (concat (map str mv) (map str ms))))}))))
             vec)))))

(defn cite-rules
  "Declarative `:seon.rule/*` rules surfacing docs linked via `:seon.code.doc/references` to active
   vars, namespaces, or schemas."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  [{:seon.rule/id :rule.expand/doc-by-ref
    :seon.rule/doc "Surface doc sections whose :seon.code.doc/references link directly to an active var, namespace, or schema."
    :seon.rule/category :expand
    :seon.rule/stratum 32
    :seon.rule/enabled? true
    :seon.rule/target :any
    :seon.rule/when '[[?e :ctx/kind ?k]
                      [:guard (or (= ?k :seon.code.var/entity)
                                  (= ?k :seon.code.ns/entity)
                                  (= ?k :seon.schema/entity))]
                      [:db :code [?d :seon.code.doc/references ?e]]
                      [:db :code [?d :seon.code.doc/id ?did]]
                      [:not [?d :ctx/kind :seon.code.doc/entity]]]
    :seon.rule/assert '[[?e :rel/cited-by-doc ?d]
                        [?d :ctx/kind :seon.code.doc/entity]
                        [?d :ctx/source :rule]
                        [?d :seon.code.doc/id ?did]
                        [?d :ctx/score 0.8]
                        [?d :view/tag :tag/doc-ref]
                        [?d :ctx/why "Doc section citing active entity"]]}])
