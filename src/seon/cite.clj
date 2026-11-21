(ns seon.cite
  "Atomic Markdown citations (`seon.cite`), live `:seon.code.doc/references` Datahike ref-graph
   linking, stale-citation auditing, and declarative expansion rules (`cite-rules`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]))

(def ^:private non-ns-extensions
  #{"md" "clj" "cljc" "cljs" "edn" "py" "sh" "zsh" "json" "yaml" "yml" "toml"
    "xml" "html" "css" "js" "ts" "txt" "c" "h" "m" "ss" "dylib" "so" "a" "o"
    "db" "mdb" "lock" "log" "png" "svg" "jpg" "pdf" "com" "org" "net" "io"
    "dev" "edu" "gov" "master3"})

(def ^:private path-root-prefixes
  #{"bin" "src" "test" "docs" "resources" "native" "target" "bench"
    "reference-code" "runs" "tools"})

(def ^:private url-tlds #{"com" "org" "net" "io" "dev" "edu" "gov"})
(def ^:private meta-schema-namespaces #{"seon.db" "seon.flow" "seon.eval" "seon.merge"})
(def ^:private meta-schema-keys #{:seon.render/hiccup})
(def ^:private sym-name-re
  #"(?:[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*|-[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*)")

(defn- valid-dotted-ns? [^String s]
  (and (<= 3 (.length s) 120)
       (some? (str/index-of s "."))
       (boolean (re-matches #"[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+" s))))

(defn- strip-fenced-blocks [^String text]
  (if-not (or (str/includes? text "```") (str/includes? text "~~~"))
    text
    (loop [lines (str/split text #"\n" -1), open nil, out []]
      (if-not (seq lines)
        (str/join "\n" out)
        (let [line (first lines)
              tr (str/triml line)]
          (if open
            (let [[ch flen] open
                  run (count (take-while #(= ch %) tr))
                  close? (and (>= run flen) (str/blank? (subs tr run)))]
              (recur (rest lines) (when-not close? open) (conj out "")))
            (let [ch (cond (str/starts-with? tr "```") \`
                           (str/starts-with? tr "~~~") \~
                           :else nil)
                  flen (when ch (count (take-while #(= ch %) tr)))
                  open? (and flen (>= flen 3)
                             (<= (- (count line) (count tr)) 3)
                             (or (= ch \~) (not (str/includes? (subs tr flen) "`"))))]
              (recur (rest lines) (when open? [ch flen]) (conj out (if open? "" line))))))))))

(defn- scan-inline-spans [^String text]
  (if-not (str/includes? text "`")
    {:spans [] :masked text}
    (let [n (.length text)
          sb (StringBuilder.)
          run-end (fn [p limit] (loop [x p] (if (and (< x limit) (= \` (.charAt text x))) (recur (inc x)) x)))
          find-closer (fn [from p-end w]
                        (loop [p (int from)]
                          (let [j (.indexOf text "`" p)]
                            (when (and (not (neg? j)) (< j p-end))
                              (let [k (run-end j p-end)]
                                (if (= w (- k j)) [j k] (recur k)))))))]
      (loop [i 0, para-end nil, dead #{}, spans (transient [])]
        (if (>= i n)
          {:spans (persistent! spans) :masked (.toString sb)}
          (let [j (.indexOf text "`" (int i))]
            (if (neg? j)
              (do (.append sb (subs text i n))
                  {:spans (persistent! spans) :masked (.toString sb)})
              (let [k (run-end j n)
                    w (- k j)
                    crossed? (and para-end (>= j para-end))
                    p-end (if (or (nil? para-end) crossed?)
                            (let [pp (.indexOf text "\n\n" (int j))] (if (neg? pp) n pp))
                            para-end)
                    dead' (if crossed? #{} dead)
                    found (when (and (< k p-end) (not (contains? dead' w)))
                            (find-closer k p-end w))]
                (if found
                  (let [[cs ce] found]
                    (.append sb (subs text i j))
                    (.append sb "x")
                    (recur ce p-end dead' (conj! spans (subs text k cs))))
                  (do (.append sb (subs text i k))
                      (recur k p-end (conj dead' w) spans)))))))))))

(defn- normalize-doc-target [^String raw allow-bare-md?]
  (let [s (-> raw str/trim (str/replace #"^<|>$" ""))]
    (when (and (seq s)
               (<= (count s) 240)
               (not (str/starts-with? s "#"))
               (not (str/starts-with? s "/"))
               (not (str/includes? s "://"))
               (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s))
               (not (re-find #"[*\\<>{}\s]" s)))
      (let [clean (str/replace s #"^(?:\.\./|\./)+" "")
            h (.indexOf clean "#")
            before-h (if (neg? h) clean (subs clean 0 h))
            frag (when-not (neg? h) (subs clean (inc h)))
            q (.indexOf before-h "?")
            p (if (neg? q) before-h (subs before-h 0 q))]
        (when (and (str/ends-with? p ".md")
                   (> (count p) 3)
                   (not (str/ends-with? p "/.md"))
                   (not (str/includes? p ":"))
                   (or allow-bare-md? (= p "AGENTS.md") (str/starts-with? p "docs/"))
                   (or (nil? frag) (not (str/blank? frag))))
          (if (seq frag) (str p "#" frag) p))))))

(defn- extract-md-links [^String masked]
  (if-not (str/includes? masked "](")
    #{}
    (let [n (.length masked)]
      (loop [i 0, acc (transient #{})]
        (let [idx (.indexOf masked "](" (int i))]
          (if (neg? idx)
            (persistent! acc)
            (let [open-b (loop [b (dec idx), depth 1]
                           (when (>= b 0)
                             (let [c (.charAt masked b)]
                               (case c
                                 \] (recur (dec b) (inc depth))
                                 \[ (if (= depth 1) b (recur (dec b) (dec depth)))
                                 \newline nil
                                 (recur (dec b) depth)))))
                  image? (and open-b (pos? open-b) (= \! (.charAt masked (dec open-b))))
                  d0 (loop [p (+ idx 2)] (if (and (< p n) (#{\space \tab} (.charAt masked p))) (recur (inc p)) p))
                  [dend ok?] (if (and (< d0 n) (= \< (.charAt masked d0)))
                               (let [gt (.indexOf masked ">" (int (inc d0)))]
                                 (if (neg? gt) [d0 false] [(inc gt) (boolean (re-find #"^\s*\)" (subs masked (inc gt) (min n (+ gt 160)))))]))
                               (loop [p d0, d 0]
                                 (if (>= p n)
                                   [p false]
                                   (let [c (.charAt masked p)]
                                     (cond
                                       (= c \\) (recur (+ p 2) d)
                                       (= c \() (recur (inc p) (inc d))
                                       (= c \)) (if (zero? d) [p true] (recur (inc p) (dec d)))
                                       (#{\space \tab \newline \return} c) [p (boolean (re-find #"^[^\n]*\)" (subs masked p (min n (+ p 160)))))]
                                       :else (recur (inc p) d))))))
                  norm (when (and open-b (not image?) ok? (< d0 dend))
                         (normalize-doc-target (subs masked d0 dend) true))]
              (recur (+ idx 2) (if norm (conj! acc norm) acc)))))))))

(defn- clean-span-token [^String raw]
  (let [n (.length raw)
        start (loop [i 0]
                (if (< i n)
                  (let [c (.charAt raw i)]
                    (cond
                      (and (= c \#) (< (inc i) n) (= \' (.charAt raw (inc i)))) (recur (+ i 2))
                      (#{\' \@ \^ \~} c) (recur (inc i))
                      :else i))
                  i))
        end (loop [j n]
              (if (and (> j start) (#{\. \, \; \:} (.charAt raw (dec j))))
                (recur (dec j))
                j))]
    (when (< start end) (subs raw start end))))

(defn- valid-star-use? [^String tok]
  (if-not (str/includes? tok "*")
    true
    (when-let [slash (str/index-of tok "/")]
      (let [nm (subs tok (inc slash))]
        (and (not (str/includes? (subs tok 0 slash) "*"))
             (> (count nm) 2) (str/starts-with? nm "*") (str/ends-with? nm "*"))))))

(defn- classify-token [^String tok]
  (when (and tok (<= (count tok) 200)
             (not (str/includes? tok "://"))
             (not (str/starts-with? tok "www."))
             (not (str/starts-with? tok "mailto:"))
             (not (str/includes? tok "\\"))
             (not (re-find #"<[a-zA-Z0-9._-]+>" tok))
             (valid-star-use? tok))
    (cond
      (and (not (str/starts-with? tok ":")) (str/includes? tok ".md"))
      (when-let [doc-id (normalize-doc-target tok false)] [:docs doc-id])

      (and (str/starts-with? tok ":") (not (str/starts-with? tok "::")))
      (let [body (subs tok 1)
            slash (str/index-of body "/")]
        (when (and slash (pos? slash) (< (inc slash) (count body)) (not (str/index-of body "/" (inc slash))))
          (let [ns-p (subs body 0 slash) nm-p (subs body (inc slash))]
            (when (and (valid-dotted-ns? ns-p) (not (str/starts-with? ns-p "db.")) (re-matches sym-name-re nm-p))
              [:schemas (keyword ns-p nm-p)]))))

      (and (not (str/starts-with? tok ":")) (not (str/starts-with? tok "/")) (str/includes? tok "/"))
      (let [slash (str/index-of tok "/")]
        (when (and (pos? slash) (< (inc slash) (count tok)) (not (str/index-of tok "/" (inc slash))))
          (let [ns-p (subs tok 0 slash)
                nm-p (subs tok (inc slash))
                last-seg (some-> (str/last-index-of ns-p ".") (#(subs ns-p (inc %))))
                first-seg (subs ns-p 0 (or (str/index-of ns-p ".") (count ns-p)))]
            (when (and (valid-dotted-ns? ns-p)
                       (not (contains? url-tlds last-seg))
                       (not (contains? path-root-prefixes first-seg))
                       (re-matches sym-name-re nm-p))
              [:vars (symbol ns-p nm-p)]))))

      (and (not (str/starts-with? tok ":")) (not (str/includes? tok "/")) (valid-dotted-ns? tok))
      (when-not (contains? non-ns-extensions (subs tok (inc (str/last-index-of tok "."))))
        [:nss (symbol tok)])

      :else nil)))

(defn parse-citations
  "Pure extractor parsing backticked code spans and Markdown links from `text` into
   `{:vars #{sym ...} :nss #{sym ...} :schemas #{kw ...} :docs #{doc-id ...}}`."
  {:malli/schema [:=> {:examples [[["See `seon.code/eval-forms`, `seon.rules`, `:seon.task/id`, and [render](docs/render.md#sec)."
                                    {:vars #{'seon.code/eval-forms}
                                     :nss #{'seon.rules}
                                     :schemas #{:seon.task/id}
                                     :docs #{"docs/render.md#sec"}}]]]}
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
    (let [{:keys [spans masked]} (scan-inline-spans (strip-fenced-blocks text))
          link-docs (extract-md-links masked)]
      (reduce
       (fn [acc span]
         (if (str/blank? span)
           acc
           (reduce (fn [a raw]
                     (if-let [[k v] (some-> (clean-span-token raw) classify-token)]
                       (update a k conj v)
                       a))
                   acc
                   (str/split span #"[ \t\n\r()\[\]{}\",;]+"))))
       {:vars #{} :nss #{} :schemas #{} :docs link-docs}
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
                   (let [present? (contains? sch attr)]
                     (reduce (fn [m item]
                               (if-let [eid (when present? (try (:e (first (d/datoms db :avet attr item))) (catch Throwable _ nil)))]
                                 (update m :refs conj eid)
                                 (update m :unresolved conj item)))
                             acc items)))]
        (-> {:refs #{} :unresolved #{}}
            (step :seon.code.var/name vars)
            (step :seon.code.ns/name  nss)
            (step :seon.schema/key   schemas))))))

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
        eid->id      (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/id))
        eid->path    (if (contains? sch :seon.code.doc/path)
                       (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/path)) {})
        eid->heading (if (contains? sch :seon.code.doc/heading)
                       (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/heading)) {})
        eid->body    (if (contains? sch :seon.code.doc/body)
                       (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/body)) {})]
    (mapv (fn [[eid did]]
            {:eid eid :id did
             :path (get eid->path eid "")
             :heading (get eid->heading eid "")
             :body (get eid->body eid "")})
          (sort-by val eid->id))))

(defn backfill-doc-refs-tx
  "Builds O(|docs|) tx-data (including stale retractions) linking all existing `:seon.code.doc/*`
   entities in `db` to their true `:seon.code.doc/references` entity IDs."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :any]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db (contains? sch :seon.code.doc/id) (contains? sch :seon.code.doc/references))
      []
      (let [var->eid (if (contains? sch :seon.code.var/name) (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name)) {})
            ns->eid  (if (contains? sch :seon.code.ns/name)  (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name))  {})
            sch->eid (if (contains? sch :seon.schema/key)    (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key))    {})
            eid->cur (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) (:v dt)))
                             {} (d/datoms db :aevt :seon.code.doc/references))]
        (vec
         (mapcat
          (fn [{:keys [eid id heading body]}]
            (let [{:keys [vars nss schemas]} (parse-citations (str heading "\n" body))
                  desired (into #{} (concat (keep var->eid vars) (keep ns->eid nss) (keep sch->eid schemas)))
                  cur (get eid->cur eid #{})
                  rems (for [gone (sort (set/difference cur desired))]
                         [:db/retract [:seon.code.doc/id id] :seon.code.doc/references gone])
                  adds (set/difference desired cur)]
              (cond-> (vec rems)
                (seq adds) (conj {:seon.code.doc/id id :seon.code.doc/references adds}))))
          (doc-entries db)))))))

(defn- seon-ns-name? [ns-s]
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
            live-vars    (if (contains? sch :seon.code.var/name) (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)) #{})
            live-schemas (if (contains? sch :seon.schema/key)    (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))    #{})
            live-tasks   (if (contains? sch :seon.task/id)       (into #{} (map :v) (d/datoms db :aevt :seon.task/id))       #{})]
        (->> (doc-entries db)
             (keep (fn [{:keys [id path heading body]}]
                     (let [{:keys [vars schemas]} (parse-citations (str heading "\n" body))
                           missing-vars (->> vars
                                             (filter #(and (seon-ns-name? (namespace %)) (not (contains? live-vars %))))
                                             (sort-by str) vec)
                           missing-schemas (->> schemas
                                                (filter #(let [ns-s (namespace %)]
                                                           (and (seon-ns-name? ns-s)
                                                                (not (contains? meta-schema-namespaces ns-s))
                                                                (not (contains? meta-schema-keys %))
                                                                (not (contains? live-schemas %))
                                                                (not (contains? live-tasks %)))))
                                                (sort-by str) vec)]
                       (when (or (seq missing-vars) (seq missing-schemas))
                         {:doc-id id :path path :heading heading
                          :missing-vars missing-vars :missing-schemas missing-schemas
                          :class :stale-doc-citation :severity :warning :kind :static
                          :subject id :branch br
                          :message (str "Doc section " id " cites missing "
                                        (str/join ", " (concat (map str missing-vars) (map str missing-schemas))))}))))
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
