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

(def ^:private url-tlds
  #{"com" "org" "net" "io" "dev" "edu" "gov"})

(def ^:private meta-schema-namespaces
  #{"seon.db" "seon.flow" "seon.eval" "seon.merge"})

(def ^:private meta-schema-keys
  #{:seon.render/hiccup})

(defn- valid-dotted-ns? [^String s]
  (let [n (.length s)]
    (and (>= n 3)
         (<= n 120)
         (some? (str/index-of s "."))
         (boolean (re-matches #"[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+" s)))))

(defn- fence-open-spec [^String line]
  (let [n (.length line)
        p (loop [i 0]
            (if (and (< i n) (< i 4) (= \space (.charAt line i)))
              (recur (inc i))
              i))]
    (when (and (< p n) (<= p 3))
      (let [ch (.charAt line p)]
        (when (or (= ch \`) (= ch \~))
          (let [k (loop [j p] (if (and (< j n) (= ch (.charAt line j))) (recur (inc j)) j))
                flen (- k p)]
            (when (and (>= flen 3)
                       (or (= ch \~) (neg? (.indexOf line "`" (int k)))))
              [ch flen])))))))

(defn- fence-close? [^String line ch flen]
  (let [n (.length line)
        p (loop [i 0]
            (if (and (< i n) (< i 4) (= \space (.charAt line i)))
              (recur (inc i))
              i))]
    (when (and (< p n) (<= p 3) (= ch (.charAt line p)))
      (let [k (loop [j p] (if (and (< j n) (= ch (.charAt line j))) (recur (inc j)) j))]
        (and (>= (- k p) flen)
             (loop [j k]
               (or (= j n)
                   (let [c (.charAt line j)]
                     (and (or (= c \space) (= c \tab) (= c \return))
                          (recur (inc j)))))))))))

(defn- strip-fenced-blocks [^String text]
  (if-not (or (str/includes? text "```") (str/includes? text "~~~"))
    text
    (let [sb (StringBuilder.)
          n (.length text)]
      (loop [pos 0, open nil]
        (if (>= pos n)
          (.toString sb)
          (let [nl (.indexOf text "\n" (int pos))
                end (if (neg? nl) n nl)
                line (subs text pos end)]
            (if open
              (let [[ch flen] open]
                (.append sb "\n")
                (recur (inc end) (when-not (fence-close? line ch flen) open)))
              (if-let [spec (fence-open-spec line)]
                (do (.append sb "\n")
                    (recur (inc end) spec))
                (do (.append sb line)
                    (when-not (neg? nl) (.append sb "\n"))
                    (recur (inc end) nil))))))))))

(defn- find-backtick-closer [^String text from p-end width]
  (loop [p (int from)]
    (let [j (.indexOf text "`" p)]
      (when (and (not (neg? j)) (< j p-end))
        (let [k (loop [x j] (if (and (< x p-end) (= \` (.charAt text x))) (recur (inc x)) x))]
          (if (= width (- k j))
            [j k]
            (recur k)))))))

(defn- scan-inline-spans [^String text]
  (if-not (str/includes? text "`")
    {:spans [] :masked text}
    (let [n (.length text)
          sb (StringBuilder.)]
      (loop [i 0, para-end nil, dead #{}, spans (transient [])]
        (if (>= i n)
          {:spans (persistent! spans) :masked (.toString sb)}
          (let [j (.indexOf text "`" (int i))]
            (if (neg? j)
              (do (.append sb (subs text i n))
                  {:spans (persistent! spans) :masked (.toString sb)})
              (let [k (loop [x j] (if (and (< x n) (= \` (.charAt text x))) (recur (inc x)) x))
                    width (- k j)
                    crossed-para? (and para-end (>= j para-end))
                    p-end (if (or (nil? para-end) crossed-para?)
                            (let [pp (.indexOf text "\n\n" (int j))]
                              (if (neg? pp) n pp))
                            para-end)
                    dead' (if crossed-para? #{} dead)
                    found (when (and (< k p-end) (not (contains? dead' width)))
                            (find-backtick-closer text k p-end width))]
                (if found
                  (let [[c-start c-end] found]
                    (.append sb (subs text i j))
                    (.append sb "x")
                    (recur c-end p-end dead' (conj! spans (subs text k c-start))))
                  (do
                    (.append sb (subs text i k))
                    (recur k p-end (conj dead' width) spans)))))))))))

(defn- normalize-doc-target [^String raw allow-bare-md?]
  (let [s (-> raw str/trim (str/replace #"^<|>$" ""))]
    (when (and (seq s)
               (<= (count s) 240)
               (not (str/starts-with? s "#"))
               (not (str/starts-with? s "/"))
               (not (str/includes? s "://"))
               (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s))
               (not (str/includes? s "*"))
               (not (str/includes? s "\\"))
               (not (re-find #"[<>{}\s]" s)))
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

(defn- find-link-open-bracket [^String masked close-idx]
  (loop [b (dec close-idx), depth 1]
    (when (>= b 0)
      (let [c (.charAt masked b)]
        (cond
          (= c \]) (recur (dec b) (inc depth))
          (= c \[) (if (= depth 1) b (recur (dec b) (dec depth)))
          (= c \newline) nil
          :else (recur (dec b) depth))))))

(defn- scan-link-destination [^String masked d0 n]
  (if (and (< d0 n) (= \< (.charAt masked d0)))
    (let [gt (.indexOf masked ">" (int (inc d0)))]
      (if (neg? gt)
        [d0 false]
        [(inc gt) (boolean (re-find #"^\s*\)" (subs masked (inc gt) (min n (+ gt 160)))))]))
    (loop [p d0, pdepth 0]
      (if (>= p n)
        [p false]
        (let [c (.charAt masked p)]
          (cond
            (= c \\) (recur (+ p 2) pdepth)
            (= c \() (recur (inc p) (inc pdepth))
            (= c \)) (if (zero? pdepth) [p true] (recur (inc p) (dec pdepth)))
            (or (= c \space) (= c \tab) (= c \newline) (= c \return))
            [p (boolean (re-find #"^[^\n]*\)" (subs masked p (min n (+ p 160)))))]
            :else (recur (inc p) pdepth)))))))

(defn- extract-md-links [^String masked]
  (if-not (str/includes? masked "](")
    #{}
    (let [n (.length masked)]
      (loop [i 0, acc (transient #{})]
        (let [idx (.indexOf masked "](" (int i))]
          (if (neg? idx)
            (persistent! acc)
            (let [open-b (find-link-open-bracket masked idx)
                  image? (and open-b (pos? open-b) (= \! (.charAt masked (dec open-b))))
                  d0 (loop [p (+ idx 2)]
                       (if (and (< p n) (#{\space \tab} (.charAt masked p))) (recur (inc p)) p))
                  [dest-end valid?] (scan-link-destination masked d0 n)
                  norm (when (and open-b (not image?) valid? (< d0 dest-end))
                         (normalize-doc-target (subs masked d0 dest-end) true))]
              (recur (+ idx 2) (if norm (conj! acc norm) acc)))))))))

(defn- clean-span-token [^String raw]
  (let [n (.length raw)
        start (loop [i 0]
                (if (< i n)
                  (let [c (.charAt raw i)]
                    (if (or (= c \') (= c \@) (= c \^) (= c \~)
                            (and (= c \#) (< (inc i) n) (= \' (.charAt raw (inc i)))))
                      (recur (if (= c \#) (+ i 2) (inc i)))
                      i))
                  i))
        end (loop [j n]
              (if (> j start)
                (let [c (.charAt raw (dec j))]
                  (if (or (= c \.) (= c \,) (= c \;) (= c \:))
                    (recur (dec j))
                    j))
                j))]
    (when (< start end)
      (subs raw start end))))

(defn- valid-star-use? [^String tok]
  (if-not (str/includes? tok "*")
    true
    (when-let [slash (str/index-of tok "/")]
      (let [ns-part (subs tok 0 slash)
            nm-part (subs tok (inc slash))]
        (and (not (str/includes? ns-part "*"))
             (> (count nm-part) 2)
             (str/starts-with? nm-part "*")
             (str/ends-with? nm-part "*"))))))

(def ^:private sym-name-re
  #"(?:[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*|-[a-zA-Z*+!_?<>=][a-zA-Z0-9_*+!?\-<>=&%$]*)")

(defn- classify-token [^String tok]
  (when (and tok
             (<= (count tok) 200)
             (not (str/includes? tok "://"))
             (not (str/starts-with? tok "www."))
             (not (str/starts-with? tok "mailto:"))
             (not (str/includes? tok "\\"))
             (not (re-find #"<[a-zA-Z0-9._-]+>" tok))
             (valid-star-use? tok))
    (cond
      (and (not (str/starts-with? tok ":"))
           (str/includes? tok ".md"))
      (when-let [doc-id (normalize-doc-target tok false)]
        [:docs doc-id])

      (and (str/starts-with? tok ":")
           (not (str/starts-with? tok "::")))
      (let [body (subs tok 1)
            slash (str/index-of body "/")]
        (when (and slash (pos? slash) (< (inc slash) (count body))
                   (not (str/index-of body "/" (inc slash))))
          (let [ns-part (subs body 0 slash)
                nm-part (subs body (inc slash))]
            (when (and (valid-dotted-ns? ns-part)
                       (not (str/starts-with? ns-part "db."))
                       (re-matches sym-name-re nm-part))
              [:schemas (keyword ns-part nm-part)]))))

      (and (not (str/starts-with? tok ":"))
           (not (str/starts-with? tok "/"))
           (str/includes? tok "/"))
      (let [slash (str/index-of tok "/")]
        (when (and (pos? slash) (< (inc slash) (count tok))
                   (not (str/index-of tok "/" (inc slash))))
          (let [ns-part (subs tok 0 slash)
                nm-part (subs tok (inc slash))
                last-seg (some-> (str/last-index-of ns-part ".") (#(subs ns-part (inc %))))
                first-seg (subs ns-part 0 (or (str/index-of ns-part ".") (count ns-part)))]
            (when (and (valid-dotted-ns? ns-part)
                       (not (contains? url-tlds last-seg))
                       (not (contains? path-root-prefixes first-seg))
                       (re-matches sym-name-re nm-part))
              [:vars (symbol ns-part nm-part)]))))

      (and (not (str/starts-with? tok ":"))
           (not (str/includes? tok "/"))
           (valid-dotted-ns? tok))
      (let [ext (subs tok (inc (str/last-index-of tok ".")))]
        (when-not (contains? non-ns-extensions ext)
          [:nss (symbol tok)]))

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
    (let [stripped (strip-fenced-blocks text)
          {:keys [spans masked]} (scan-inline-spans stripped)
          link-docs (extract-md-links masked)]
      (reduce
       (fn [acc span]
         (if (str/blank? span)
           acc
           (reduce
            (fn [a raw]
              (if-let [tok (clean-span-token raw)]
                (if-let [[k v] (classify-token tok)]
                  (update a k conj v)
                  a)
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
            has-var? (contains? sch :seon.code.var/name)
            has-ns?  (contains? sch :seon.code.ns/name)
            has-sch? (contains? sch :seon.schema/key)
            step (fn [acc present? attr items]
                   (reduce
                    (fn [m item]
                      (let [eid (when present?
                                  (try (:e (first (d/datoms db :avet attr item)))
                                       (catch Throwable _ nil)))]
                        (if eid
                          (update m :refs conj eid)
                          (update m :unresolved conj item))))
                    acc
                    items))]
        (-> {:refs #{} :unresolved #{}}
            (step has-var? :seon.code.var/name vars)
            (step has-ns?  :seon.code.ns/name  nss)
            (step has-sch? :seon.schema/key   schemas))))))

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

(defn backfill-doc-refs-tx
  "Builds O(|docs|) tx-data (including stale retractions) linking all existing `:seon.code.doc/*`
   entities in `db` to their true `:seon.code.doc/references` entity IDs."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :any]]
   :cost/ticks 50000000}
  [db]
  (let [sch (when db (:schema db))]
    (if-not (and db
                 (contains? sch :seon.code.doc/id)
                 (contains? sch :seon.code.doc/references))
      []
      (let [var->eid (if (contains? sch :seon.code.var/name)
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
                       {})
            ns->eid  (if (contains? sch :seon.code.ns/name)
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name))
                       {})
            sch->eid (if (contains? sch :seon.schema/key)
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key))
                       {})
            eid->id      (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/id))
            eid->heading (if (contains? sch :seon.code.doc/heading)
                           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/heading))
                           {})
            eid->body    (if (contains? sch :seon.code.doc/body)
                           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/body))
                           {})
            eid->cur     (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) (:v dt)))
                                 {}
                                 (d/datoms db :aevt :seon.code.doc/references))]
        (vec
         (mapcat
          (fn [[eid did]]
            (let [txt (str (get eid->heading eid "") "\n" (get eid->body eid ""))
                  {:keys [vars nss schemas]} (parse-citations txt)
                  desired (into #{}
                                (concat (keep var->eid vars)
                                        (keep ns->eid nss)
                                        (keep sch->eid schemas)))
                  cur (get eid->cur eid #{})
                  rems (for [gone (sort (set/difference cur desired))]
                         [:db/retract [:seon.code.doc/id did] :seon.code.doc/references gone])
                  adds (set/difference desired cur)]
              (cond-> (vec rems)
                (seq adds) (conj {:seon.code.doc/id did
                                  :seon.code.doc/references adds}))))
          (sort-by val eid->id)))))))

(defn- seon-ns-name? [ns-s]
  (and (string? ns-s)
       (or (= ns-s "seon")
           (str/starts-with? ns-s "seon."))))

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
            live-vars (if (contains? sch :seon.code.var/name)
                        (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
                        #{})
            live-schemas (if (contains? sch :seon.schema/key)
                           (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
                           #{})
            live-tasks (if (contains? sch :seon.task/id)
                         (into #{} (map :v) (d/datoms db :aevt :seon.task/id))
                         #{})
            eid->id      (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/id))
            eid->path    (if (contains? sch :seon.code.doc/path)
                           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/path))
                           {})
            eid->heading (if (contains? sch :seon.code.doc/heading)
                           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/heading))
                           {})
            eid->body    (if (contains? sch :seon.code.doc/body)
                           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.doc/body))
                           {})]
        (->> eid->id
             (sort-by val)
             (keep (fn [[eid did]]
                     (let [heading (get eid->heading eid "")
                           txt (str heading "\n" (get eid->body eid ""))
                           {:keys [vars schemas]} (parse-citations txt)
                           missing-vars (->> vars
                                             (filter #(and (seon-ns-name? (namespace %))
                                                           (not (contains? live-vars %))))
                                             (sort-by str)
                                             vec)
                           missing-schemas (->> schemas
                                                (filter #(let [ns-s (namespace %)]
                                                           (and (seon-ns-name? ns-s)
                                                                (not (contains? meta-schema-namespaces ns-s))
                                                                (not (contains? meta-schema-keys %))
                                                                (not (contains? live-schemas %))
                                                                (not (contains? live-tasks %)))))
                                                (sort-by str)
                                                vec)]
                       (when (or (seq missing-vars) (seq missing-schemas))
                         {:doc-id did
                          :path (get eid->path eid "")
                          :heading heading
                          :missing-vars missing-vars
                          :missing-schemas missing-schemas
                          :class :stale-doc-citation
                          :severity :warning
                          :kind :static
                          :subject did
                          :branch br
                          :message (str "Doc section " did " cites missing "
                                        (str/join ", " (concat (map str missing-vars)
                                                               (map str missing-schemas))))}))))
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
