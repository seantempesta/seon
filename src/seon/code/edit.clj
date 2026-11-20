(ns seon.code.edit
  "Whitespace-, tab-, newline-, and comment-insensitive structural Clojure editing (`rewrite-clj` CST),
   AST-accurate unused `:require` analysis/pruning, body grep, unified diffs, and in-turn branch test running."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.ctx :as ctx]
            [seon.fork.calls :refer [*agent-ctx*]]
            [seon.fork.worker-support :as wsup]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn node->structural-key
  "Returns a whitespace-, tab-, newline-, comma-, and comment-insensitive structural key
   for any rewrite-clj CST node. Works across 100% of Clojure syntax (`::alias/kw`, `#(...)`,
   `^:meta`, `#_discard`, regexes) without calling `z/sexpr`."
  [nd]
  (let [tag (n/tag nd)]
  (cond
    (= tag :deref)
    [:deref (node->structural-key (first (remove n/whitespace-or-comment? (n/children nd))))]

    (= tag :quote)
    [:quote (node->structural-key (first (remove n/whitespace-or-comment? (n/children nd))))]

    (= tag :list)
    (let [kids (mapv node->structural-key (remove n/whitespace-or-comment? (n/children nd)))]
      (cond
        (and (= 2 (count kids))
             (= :token (first (first kids)))
             (#{"deref" "clojure.core/deref"} (second (first kids))))
        [:deref (second kids)]

        (and (= 2 (count kids))
             (= :token (first (first kids)))
             (= "quote" (second (first kids))))
        [:quote (second kids)]

        :else [:list kids]))

    (n/inner? nd)
    [tag (mapv node->structural-key (remove n/whitespace-or-comment? (n/children nd)))]

    :else [tag (n/string nd)])))

(defn parse-non-ws-nodes [s]
  (try
    (let [root (p/parse-string-all (str s))
          nodes (vec (remove n/whitespace-or-comment? (n/children root)))]
      {:ok? true :nodes nodes})
    (catch Throwable ex
      {:ok? false :error :invalid-clojure-syntax :message (or (.getMessage ex) (str ex))})))

(defn- strip-common-indent [s]
  (let [lines (str/split-lines (str s))]
    (if (<= (count lines) 1)
      (str/trim (str s))
      (let [rest-nonblank (remove str/blank? (rest lines))
            min-ind (if (seq rest-nonblank)
                      (reduce min (map #(count (re-find #"^[ \t]*" %)) rest-nonblank))
                      0)]
        (str/join "\n"
                  (cons (str/triml (first lines))
                        (map #(if (str/blank? %) "" (subs % (min min-ind (count %)))) (rest lines))))))))

(defn- reindent-to-col [s col]
  (let [norm (strip-common-indent s)
        lines (str/split-lines norm)
        pad (apply str (repeat (max 0 (long col)) \space))]
    (if (<= (count lines) 1)
      norm
      (str/join "\n" (cons (first lines)
                           (map #(if (str/blank? %) "" (str pad %)) (rest lines)))))))

(defn- prefix-line-and-col [^String prefix]
  (let [last-nl (.lastIndexOf prefix "\n")
        line (inc (count (filter #(= \newline %) prefix)))
        col (if (neg? last-nl) (count prefix) (- (count prefix) last-nl 1))]
    [line col]))

(defn- loc-prefix-str [loc]
  (let [marked (z/replace loc (n/token-node ::__SEON_EDIT_SENTINEL__))
        full (z/root-string marked)
        idx (.indexOf ^String full (str ::__SEON_EDIT_SENTINEL__))]
    (if (neg? idx) "" (subs full 0 idx))))

(defn- snippet-of [nd]
  (let [s (str/replace (str/trim (n/string nd)) #"\s+" " ")]
    (if (> (count s) 90) (str (subs s 0 90) "…") s)))

(defn- find-single-matches [z0 target-key]
  (loop [loc z0, step 0, acc []]
    (if (z/end? loc)
      acc
      (let [nd (z/node loc)]
        (if (= target-key (node->structural-key nd))
          (let [[line col] (prefix-line-and-col (loc-prefix-str loc))]
            (recur (z/next loc) (inc step)
                   (conj acc {:occurrence (inc (count acc))
                              :step step :line line :col col :snippet (snippet-of nd)})))
          (recur (z/next loc) (inc step) acc))))))

(defn- apply-single-match [z0 step new-str col]
  (let [reind (reindent-to-col new-str col)
        parsed (parse-non-ws-nodes reind)]
    (if-not (:ok? parsed)
      parsed
      (let [loc (nth (iterate z/next z0) step)
            new-children (vec (n/children (p/parse-string-all reind)))
            z' (cond
                 (empty? (:nodes parsed)) (z/remove loc)
                 (= 1 (count (:nodes parsed))) (z/replace loc (first (:nodes parsed)))
                 :else
                 (let [first-loc (z/replace loc (first new-children))]
                   (reduce (fn [cur child] (z/insert-right* cur child))
                           first-loc
                           (reverse (rest new-children)))))]
        {:ok? true :src (z/root-string z')}))))

(defn- container-node? [nd]
  (contains? #{:list :vector :map :set :fn :forms} (n/tag nd)))

(defn- find-slice-matches [z0 target-keys]
  (let [k (count target-keys)]
    (loop [loc z0, step 0, acc []]
      (if (z/end? loc)
        acc
        (let [nd (z/node loc)
              hits (when (container-node? nd)
                     (let [raw (vec (n/children nd))
                           non-ws (vec (keep-indexed (fn [i ch]
                                                       (when-not (n/whitespace-or-comment? ch)
                                                         {:raw-i i :key (node->structural-key ch)}))
                                                     raw))
                           m (count non-ws)]
                       (when (>= m k)
                         (for [j (range 0 (inc (- m k)))
                               :when (= target-keys (mapv :key (subvec non-ws j (+ j k))))]
                           (let [ri0 (:raw-i (nth non-ws j))
                                 ri1 (:raw-i (nth non-ws (+ j k -1)))
                                 before-str (apply str (map n/string (subvec raw 0 ri0)))
                                 open-tok (case (n/tag nd) :list "(" :vector "[" :map "{" :set "#{" :fn "#(" "")
                                 [line col] (prefix-line-and-col (str (loc-prefix-str loc) open-tok before-str))
                                 snip (let [s (str/replace (apply str (map n/string (subvec raw ri0 (inc ri1)))) #"\s+" " ")]
                                        (if (> (count s) 90) (str (subs s 0 90) "…") s))]
                             {:step step :ri0 ri0 :ri1 ri1 :line line :col col :snippet snip})))))]
          (recur (z/next loc) (inc step)
                 (reduce (fn [a h] (conj a (assoc h :occurrence (inc (count a))))) acc hits)))))))

(defn- apply-slice-match [z0 {:keys [step ri0 ri1 col]} new-str]
  (let [reind (reindent-to-col new-str col)
        parsed (parse-non-ws-nodes reind)]
    (if-not (:ok? parsed)
      parsed
      (let [loc (nth (iterate z/next z0) step)
            nd (z/node loc)
            raw (vec (n/children nd))
            new-children (vec (n/children (p/parse-string-all reind)))
            trimmed-new (let [c1 (drop-while n/whitespace? new-children)
                              c2 (reverse (drop-while n/whitespace? (reverse c1)))]
                          (vec c2))
            updated (vec (concat (subvec raw 0 ri0) trimmed-new (subvec raw (inc ri1))))]
        {:ok? true :src (z/root-string (z/replace loc (n/replace-children nd updated)))}))))

(defn patch-form-source
  "Structurally patches `form-src` by replacing `old-str` with `new-str`, ignoring spaces, tabs,
   newlines, commas, and inline comments during matching while preserving all surrounding
   formatting & comments losslessly. Supports both single-form and contiguous sibling-slice edits
   (e.g. map entries, `let` bindings, `cond` branches)."
  [form-src old-str new-str & [{:keys [occurrence]}]]
  (let [parsed-old (parse-non-ws-nodes old-str)]
    (if-not (:ok? parsed-old)
      (assoc parsed-old :stage :parse-old)
      (let [old-nodes (:nodes parsed-old)]
        (if (empty? old-nodes)
          {:ok? false :error :empty-old-subform}
          (let [z0 (z/of-string form-src)
                single? (= 1 (count old-nodes))
                matches (if single?
                          (find-single-matches z0 (node->structural-key (first old-nodes)))
                          (find-slice-matches z0 (mapv node->structural-key old-nodes)))]
            (cond
              (empty? matches)
              {:ok? false :error :no-match
               :hint "Subform not found structurally (whitespace/comments ignored). Check (user/source 'ns/fn)."}

              (and (> (count matches) 1) (nil? occurrence))
              {:ok? false :error :ambiguous-match :count (count matches)
               :matches (mapv #(select-keys % [:occurrence :line :snippet]) matches)
               :hint "Pass {:occurrence N} or include enclosing parent form."}

              (and occurrence (or (< occurrence 1) (> occurrence (count matches))))
              {:ok? false :error :occurrence-out-of-range :occurrence occurrence :count (count matches)}

              :else
              (let [chosen (nth matches (dec (or occurrence 1)))
                    res (if single?
                          (apply-single-match z0 (:step chosen) new-str (:col chosen))
                          (apply-slice-match z0 chosen new-str))]
                (if-not (:ok? res)
                  res
                  (let [chk (parse-non-ws-nodes (:src res))]
                    (if (:ok? chk)
                      {:ok? true :src (:src res) :line (:line chosen) :occurrences (count matches)}
                      (assoc chk :stage :verify-result))))))))))))

(defn unified-diff
  "Produces a compact unified diff (`-` / `+` with context lines) between `old-str` and `new-str`."
  ([old-str new-str] (unified-diff old-str new-str "form"))
  ([old-str new-str label]
   (if (= old-str new-str)
     ""
     (let [a (vec (str/split-lines (str (or old-str ""))))
           b (vec (str/split-lines (str (or new-str ""))))
           na (count a), nb (count b)
           ;; Trim common prefix & suffix for O(|delta|^2) LCS
           p (loop [i 0] (if (and (< i na) (< i nb) (= (nth a i) (nth b i))) (recur (inc i)) i))
           s (loop [i 0] (if (and (< (+ p i) na) (< (+ p i) nb) (= (nth a (- na 1 i)) (nth b (- nb 1 i)))) (recur (inc i)) i))
           a-mid (subvec a p (- na s))
           b-mid (subvec b p (- nb s))
           ctx-pre (subvec a (max 0 (- p 2)) p)
           ctx-post (subvec a (- na s) (min na (+ (- na s) 2)))
           hunk (concat [(str "--- a/" label) (str "+++ b/" label)
                         (str "@@ -" (inc (max 0 (- p 2))) "," (+ (count ctx-pre) (count a-mid) (count ctx-post))
                              " +" (inc (max 0 (- p 2))) "," (+ (count ctx-pre) (count b-mid) (count ctx-post)) " @@")]
                        (map #(str " " %) ctx-pre)
                        (map #(str "-" %) a-mid)
                        (map #(str "+" %) b-mid)
                        (map #(str " " %) ctx-post))]
       (str/join "\n" hunk)))))

(defn collect-body-refs [body-nodes]
  (let [qual-ns (volatile! #{})
        unqual  (volatile! #{})
        raw-toks (volatile! #{})
        walk (fn walk [nd]
               (let [tag (n/tag nd)]
                 (cond
                   (or (n/whitespace-or-comment? nd) (= tag :multi-line) (= tag :regex)) nil
                   (= tag :token)
                   (let [s (n/string nd)]
                     (when-not (str/starts-with? s "\"")
                       (vswap! raw-toks conj s)
                       (cond
                         (str/starts-with? s "::")
                         (let [rest-s (subs s 2), idx (.indexOf ^String rest-s "/")]
                           (when (pos? idx) (vswap! qual-ns conj (subs rest-s 0 idx))))
                         (str/starts-with? s ":") nil
                         :else
                         (let [idx (.indexOf ^String s "/")]
                           (if (pos? idx)
                             (vswap! qual-ns conj (subs s 0 idx))
                             (vswap! unqual conj s))))))
                   (= tag :keyword)
                   (let [s (n/string nd)]
                     (when (str/starts-with? s "::")
                       (let [rest-s (subs s 2), idx (.indexOf ^String rest-s "/")]
                         (when (pos? idx) (vswap! qual-ns conj (subs rest-s 0 idx))))))
                   (n/inner? nd)
                   (doseq [ch (n/children nd)] (walk ch)))))]
    (doseq [nd body-nodes]
      (walk (if (string? nd) (p/parse-string-all nd) nd)))
    {:qual-ns @qual-ns :unqual @unqual :raw-toks @raw-toks}))

(defn- parse-require-spec [vec-nd]
  (when (= :vector (n/tag vec-nd))
    (let [elems (vec (remove n/whitespace-or-comment? (n/children vec-nd)))
          lib (some-> (first elems) n/string)]
      (when lib
        (loop [xs (rest elems), alias-s nil, refers nil, refer-all? false]
          (if (empty? xs)
            {:lib lib :alias alias-s :refers refers :refer-all? refer-all?}
            (let [k (n/string (first xs)), v (second xs)]
              (cond
                (= k ":as") (recur (nnext xs) (some-> v n/string) refers refer-all?)
                (= k ":refer")
                (if (= ":all" (some-> v n/string))
                  (recur (nnext xs) alias-s refers true)
                  (let [rs (when (and v (= :vector (n/tag v)))
                             (mapv n/string (remove n/whitespace-or-comment? (n/children v))))]
                    (recur (nnext xs) alias-s rs refer-all?)))
                :else (recur (nnext xs) alias-s refers refer-all?)))))))))

(defn- analyze-parsed-root [root]
  (let [top-forms (vec (remove n/whitespace-or-comment? (n/children root)))
        ns-nd (first top-forms)
        {:keys [qual-ns unqual raw-toks]} (collect-body-refs (rest top-forms))
        req-list (first (filter #(and (= :list (n/tag %))
                                      (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                                (n/children ns-nd)))
        specs (when req-list
                (keep parse-require-spec (rest (remove n/whitespace-or-comment? (n/children req-list)))))]
    (mapv (fn [{:keys [lib alias refers refer-all?] :as sp}]
            (let [bare? (and (nil? alias) (nil? refers) (not refer-all?))
                  alias-used? (boolean (and alias (contains? qual-ns alias)))
                  lib-used? (boolean (or (contains? qual-ns lib)
                                         (some #(str/starts-with? % (str lib ".")) raw-toks)))
                  used-refers (when (seq refers) (filterv #(contains? unqual %) refers))
                  unused-refers (when (seq refers) (filterv #(not (contains? unqual %)) refers))
                  keep? (or bare? refer-all? alias-used? lib-used? (seq used-refers))]
              (assoc sp :keep? (boolean keep?) :alias-used? alias-used? :lib-used? lib-used?
                        :used-refers used-refers :unused-refers unused-refers)))
          specs)))

(defn analyze-ns-requires
  "Parses a complete `.clj` namespace file once with `rewrite-clj` and returns per-require usage facts."
  [file-src]
  (analyze-parsed-root (p/parse-string-all file-src)))

(defn prune-unused-requires-src
  "Single-pass CST pruning of genuinely unused `:require` specs from `file-src`."
  [file-src]
  (let [root (p/parse-string-all file-src)
        analysis (analyze-parsed-root root)
        drop-libs (into #{} (comp (remove :keep?) (map :lib)) analysis)
        kept-libs (mapv :lib (filter :keep? analysis))]
    (if (empty? drop-libs)
      {:ok? true :changed? false :src file-src :removed [] :kept kept-libs}
      (let [z0 (z/edn root)
            req-loc (loop [loc z0]
                      (cond
                        (z/end? loc) nil
                        (and (= :list (n/tag (z/node loc)))
                             (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children (z/node loc)))) n/string)))
                        loc
                        :else (recur (z/next loc))))]
        (if-not req-loc
          {:ok? true :changed? false :src file-src :removed [] :kept kept-libs}
          (let [req-nd (z/node req-loc)
                kept-spec-nodes
                (keep (fn [ch]
                        (when (= :vector (n/tag ch))
                          (let [lib (some-> (first (remove n/whitespace-or-comment? (n/children ch))) n/string)]
                            (when-not (contains? drop-libs lib) ch))))
                      (n/children req-nd))
                new-req-str (if (empty? kept-spec-nodes)
                              ""
                              (str "(:require "
                                   (str/join "\n            " (map n/string kept-spec-nodes))
                                   ")"))
                z' (if (str/blank? new-req-str)
                     (z/remove req-loc)
                     (z/replace req-loc (p/parse-string new-req-str)))]
            {:ok? true :changed? true :src (z/root-string z')
             :removed (vec (sort drop-libs)) :kept kept-libs}))))))

;; --- 4. Focused Top-Level Agent REPL Helper Functions ---

(defn- merged-ns-src [conn branch nsym]
  (let [bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        base-db (when (not= branch :main) (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))
        ns-forms-of (fn [db]
                      (->> (d/datoms db :avet :seon.code.form/ns nsym)
                           (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                           (sort-by #(or (:seon.code.form/order %) 0))))
        m-fs (ns-forms-of mdb)
        b-fs (ns-forms-of bdb)
        b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) b-fs)
        base-by-k (if base-db (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms-of base-db)) {})
        m-keys (into #{} (map :seon.code.form/key) m-fs)
        kept-m (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                       (let [bs (get b-by-k key)]
                         (cond
                           (and (nil? bs) (contains? base-by-k key)) nil
                           (and bs (not= bs (get base-by-k key))) bs
                           :else src)))
                     (if (seq m-fs) m-fs b-fs))
        added-b (when (seq m-fs)
                  (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                          (when-not (contains? m-keys key) src))
                        b-fs))]
    (str/join "\n\n" (concat kept-m added-b))))

(defn- resolve-form-target [conn branch target]
  (let [s (str target)
        bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        [nsym fkey] (cond
                      (str/ends-with? s "#ns") [(symbol (subs s 0 (- (count s) 3))) s]
                      (str/includes? s "/")
                      (let [sym (symbol s)
                            fk (or (:seon.code.var/form (d/pull bdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   (:seon.code.var/form (d/pull mdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   s)]
                        [(symbol (namespace sym)) fk])
                      :else [(symbol s) (str s "#ns")])
        path (or (:seon.code.ns/path (d/pull bdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (:seon.code.ns/path (d/pull mdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (code/ns->default-path nsym))
        f-ent (let [b (d/pull bdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])]
                (if (:seon.code.form/src b) b
                    (d/pull mdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])))]
    {:ns nsym :key fkey :path path
     :src (:seon.code.form/src f-ent)
     :order (:seon.code.form/order f-ent)
     :lead (:seon.code.form/lead f-ent)}))

(defn- single-form-upsert-tx
  "O(1-form) delta persistence for non-`#ns` form edits — avoids re-parsing the entire `.clj` file."
  [db nsym fkey path order lead new-fsrc]
  (let [ns-src (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))
        local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (code/ns-vars db nsym))
        reparsed (parse/read-clj path (str (or ns-src (str "(ns " nsym ")")) "\n" new-fsrc) local-defs)
        kept-forms (mapv (fn [f]
                           (cond-> f
                             (= (:seon.code.form/key f) fkey)
                             (assoc :seon.code.form/order (or order 1)
                                    :seon.code.form/lead (or lead "\n\n"))))
                         (rest (:forms reparsed)))
        kept-vars (:vars reparsed)
        stale-var-ops (vec
                       (mapcat (fn [v]
                                 (when-let [eid (:e (first (d/datoms db :avet :seon.code.var/name (:seon.code.var/name v))))]
                                   (mapcat (fn [a]
                                             (let [old-s (into #{} (map :v) (d/datoms db :eavt eid a))
                                                   new-s (set (get v a #{}))]
                                               (for [gone (remove new-s old-s)]
                                                 [:db/retract eid a gone])))
                                           [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords])))
                               kept-vars))]
    (vec (concat stale-var-ops kept-forms kept-vars))))

(defn do-edit-fn!
  ([target old-str new-str] (do-edit-fn! target old-str new-str {}))
  ([target old-str new-str {:keys [occurrence dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [old-s (if (string? old-str) old-str (binding [*print-meta* true] (pr-str old-str)))
               new-s (if (string? new-str) new-str (binding [*print-meta* true] (pr-str new-str)))
               res (patch-form-source src old-s new-s {:occurrence occurrence})]
           (if-not (:ok? res)
             (assoc res :target target :key key)
             (let [new-fsrc (:src res)
                   udiff (unified-diff src new-fsrc key)]
               (if dry-run?
                 {:ok? true :dry-run? true :target target :key key :line (:line res) :diff udiff}
                 (let [tx (if (str/ends-with? key "#ns")
                            (let [file-src (merged-ns-src conn branch ns)
                                  idx (.indexOf ^String file-src ^String src)]
                              (when-not (neg? idx)
                                (code/file-import-tx @conn path
                                                     (str (subs file-src 0 idx) new-fsrc (subs file-src (+ idx (count src))))
                                                     false)))
                            (single-form-upsert-tx @conn ns key path order lead new-fsrc))]
                   (if-not tx
                     {:ok? false :error :source-sync-mismatch :target target :key key}
                     (let [r (d/transact conn {:tx-data tx})
                           eval-err (when (and eval? (not (str/ends-with? key "#ns")))
                                      (try
                                        (when-not (find-ns ns) (require ns))
                                        (binding [*ns* (the-ns ns)]
                                          (eval (read-string new-fsrc)))
                                        nil
                                        (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                       (ctx/refresh-active! (:db-after r) (:tx-data r))
                       (let [defonce? (str/starts-with? (str/triml new-fsrc) "(defonce ")]
                       (cond-> {:ok? (nil? eval-err)
                                :target target :key key :path path :line (:line res)
                                :tx (:max-tx (:db-after r)) :diff udiff}
                         eval-err (assoc :eval-error eval-err)
                         defonce? (assoc :note "defonce keeps its existing runtime binding; use (reset! ...) or (alter-var-root ...) if testing a new initial value live.")))))))))))))))

(defn do-grep-src
  ([pattern] (do-grep-src pattern {}))
  ([pattern {:keys [ns limit] :or {limit 30}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [bdb @conn
           ns-pref (when ns (name ns))
           re? (instance? java.util.regex.Pattern pattern)
           q-low (when-not re? (str/lower-case (str pattern)))
           match-str? (fn [^String s]
                        (if re?
                          (boolean (re-find pattern s))
                          (str/includes? (str/lower-case s) q-low)))
           hits (volatile! [])]
       (if ns-pref
         (let [matching-nss (->> (d/datoms bdb :aevt :seon.code.ns/name)
                                 (map :v)
                                 (filter (fn [n]
                                           (let [s (name n)]
                                             (or (= s ns-pref) (str/starts-with? s (str ns-pref ".")))))))
               cand-eids (into [] (mapcat #(map :e (d/datoms bdb :avet :seon.code.form/ns %))) matching-nss)]
           (doseq [eid cand-eids
                   :while (< (count @hits) limit)
                   :let [src (:v (first (d/datoms bdb :eavt eid :seon.code.form/src)))]
                   :when (and src (match-str? src))
                   :let [fk (:v (first (d/datoms bdb :eavt eid :seon.code.form/key)))]]
             (doseq [[i line] (map-indexed vector (str/split-lines src))
                     :while (< (count @hits) limit)
                     :when (match-str? line)]
               (let [t (str/trim line)]
                 (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
         (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                 :while (< (count @hits) limit)
                 :let [src (str (:v dt))]
                 :when (match-str? src)
                 :let [fk (:v (first (d/datoms bdb :eavt (:e dt) :seon.code.form/key)))]]
           (doseq [[i line] (map-indexed vector (str/split-lines src))
                   :while (< (count @hits) limit)
                   :when (match-str? line)]
             (let [t (str/trim line)]
               (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
       @hits))))

(defn do-unused-requires [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (analyze-ns-requires fsrc)]
          {:ok? true
           :ns s
           :unused (vec (map :lib (remove :keep? analysis)))
           :kept (vec (map :lib (filter :keep? analysis)))
           :detail analysis})))))

(defn do-prune-requires!
  ([nsym] (do-prune-requires! nsym {}))
  ([nsym {:keys [dry-run?]}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [path]} (resolve-form-target conn branch s)
           fsrc (merged-ns-src conn branch s)]
       (if (str/blank? fsrc)
         {:ok? false :error :ns-not-found :ns s}
         (let [res (prune-unused-requires-src fsrc)
               udiff (unified-diff fsrc (:src res) path)]
           (cond
             (not (:changed? res)) (assoc res :ns s :path path :diff "")
             dry-run? (assoc res :ns s :path path :dry-run? true :diff udiff)
             :else
             (let [tx (code/file-import-tx @conn path (:src res) false)
                   r (d/transact conn {:tx-data tx})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :ns s :path path :changed? true
                :removed (:removed res) :kept (:kept res)
                :tx (:max-tx (:db-after r)) :diff udiff}))))))))

(defn do-run-tests!
  ([] (do-run-tests! nil))
  ([targets]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [t0 (System/nanoTime)
           bdb @conn
           base-cid (when (not= branch :main)
                      (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           ops (when base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)))
           _ (when (seq (:upserts ops))
               (wsup/apply-form-overlay! bdb {:upserts (:upserts ops) :retracted-keys (:retracted-keys ops)}))
           target-list (cond
                         (nil? targets) nil
                         (or (vector? targets) (set? targets) (seq? targets)) (vec targets)
                         :else [targets])
           test-syms
           (if (seq target-list)
             (->> target-list
                  (mapcat (fn [x]
                            (let [s (if (string? x) (symbol x) x)]
                              (cond
                                (namespace s) [s]
                                (str/ends-with? (name s) "-test")
                                (->> (d/datoms bdb :avet :seon.code.var/ns s)
                                     (keep #(let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test?] (:e %))]
                                              (when (:seon.code.var/test? v) (:seon.code.var/name v)))))
                                :else (code/reaching-tests bdb s)))))
                  distinct sort vec)
             (let [changed-nss (distinct (keep :seon.code.form/ns (:upserts ops)))]
               (->> changed-nss
                    (mapcat #(code/reaching-tests bdb %))
                    distinct sort vec)))
           results (mapv #(wsup/run-single-test-var % (str "local:" %)) test-syms)
           failures (filterv #(not (:seon.test/pass? %)) results)
           ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
       {:ok? (empty? failures)
        :ran (count results)
        :passed (- (count results) (count failures))
        :failed (count failures)
        :ms ms
        :failures (mapv #(select-keys % [:seon.test/var :seon.test/report :seon.test/ms]) failures)
        :results (mapv #(select-keys % [:seon.test/var :seon.test/pass? :seon.test/ms]) results)}))))

(defn do-diff
  ([] (do-diff nil))
  ([target]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           base-db (when base-cid (d/commit-as-db conn base-cid))
           ops (if base-cid
                 (code/changed-form-ops conn base-cid (d/commit-id bdb))
                 {:upserts [] :retracted-keys #{} :schema-keys #{}})
           errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {}))
           upserts (if target
                     (let [k (str target)]
                       (filterv #(= k (:seon.code.form/key %)) (:upserts ops)))
                     (:upserts ops))
           diffs (into {}
                       (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                               (let [old-s (when base-db
                                             (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))]
                                 [key (unified-diff (or old-s "") (or src "") key)])))
                       (take 12 upserts))]
       {:branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-errors errs
        :diffs diffs}))))

(defn install-edit-helpers!
  "Interns `edit-fn!`, `grep-src`, `run-tests!`, `unused-requires`, `prune-requires!`, and unified `diff` into `user`."
  [u]
  (intern u 'edit-fn! do-edit-fn!)
  (intern u 'grep-src do-grep-src)
  (intern u 'unused-requires do-unused-requires)
  (intern u 'prune-requires! do-prune-requires!)
  (intern u 'run-tests! do-run-tests!)
  (intern u 'diff do-diff))
