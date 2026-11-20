(ns seon.code.cst
  "Pure rewrite-clj CST structural matcher/splicer, unified diff, and AST-accurate unused :require analyzer/pruner."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

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

    (or (= tag :multi-line) (and (= tag :token) (str/starts-with? (n/string nd) "\""))) [:string (try (n/sexpr nd) (catch Throwable _ (n/string nd)))]
    :else [tag (n/string nd)])))

(defn parse-non-ws-nodes
  {:malli/schema [:=> [:cat :string] :map], :cost/ticks 500000, :cost/bytes 2621440}
  [s]
  (try
    (let [root (p/parse-string-all (str s))
          nodes (vec (remove n/whitespace-or-comment? (n/children root)))]
      {:ok? true :nodes nodes})
    (catch Throwable ex
      {:ok? false :error :invalid-clojure-syntax :message (or (.getMessage ex) (str ex))})))

(defn strip-common-indent [s]
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

(defn reindent-to-col [s col]
  (let [norm (strip-common-indent s)
        lines (str/split-lines norm)
        pad (apply str (repeat (max 0 (long col)) \space))]
    (if (<= (count lines) 1)
      norm
      (str/join "\n" (cons (first lines)
                           (map #(if (str/blank? %) "" (str pad %)) (rest lines)))))))

(defn prefix-line-and-col [^String prefix]
  (let [last-nl (.lastIndexOf prefix "\n")
        line (inc (count (filter #(= \newline %) prefix)))
        col (if (neg? last-nl) (count prefix) (- (count prefix) last-nl 1))]
    [line col]))

(defn loc-prefix-str [loc]
  (let [marked (z/replace loc (n/token-node ::__SEON_EDIT_SENTINEL__))
        full (z/root-string marked)
        idx (.indexOf ^String full (str ::__SEON_EDIT_SENTINEL__))]
    (if (neg? idx) "" (subs full 0 idx))))

(defn snippet-of [nd]
  (let [s (str/replace (str/trim (n/string nd)) #"\s+" " ")]
    (if (> (count s) 90) (str (subs s 0 90) "…") s)))

(defn- matching-candidate? [target-tag target-arity nd]
  (let [t (n/tag nd)]
    (case target-tag
      :deref  (or (= t :deref) (and (= t :list) (= 2 (count (remove n/whitespace-or-comment? (n/children nd))))))
      :quote  (or (= t :quote) (and (= t :list) (= 2 (count (remove n/whitespace-or-comment? (n/children nd))))))
      :string (or (= t :multi-line) (= t :token))
      (and (= t target-tag)
           (if (some? target-arity)
             (= target-arity (count (remove n/whitespace-or-comment? (n/children nd))))
             true)))))

(defn find-single-matches
  "Finds all structural occurrences of `target-key` in rewrite-clj zipper `z0` in O(N) time by
   pre-filtering tag & non-whitespace arity before computing recursive structural keys."
  {:malli/schema [:=> [:cat :any :any] [:vector :map]]}
  [z0 target-key]
  (let [target-tag (first target-key)
        target-arity (when (and (vector? (second target-key))
                                (not (#{:deref :quote :string :token} target-tag)))
                       (count (second target-key)))]
    (loop [loc z0, step 0, acc []]
      (if (z/end? loc)
        acc
        (let [nd (z/node loc)]
          (if (and (matching-candidate? target-tag target-arity nd)
                   (= target-key (node->structural-key nd)))
            (let [[line col] (prefix-line-and-col (loc-prefix-str loc))]
              (recur (z/next loc) (inc step)
                     (conj acc {:occurrence (inc (count acc))
                                :step step :line line :col col :snippet (snippet-of nd)})))
            (recur (z/next loc) (inc step) acc)))))))

(defn apply-single-match [z0 step new-str col]
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

(defn container-node? [nd]
  (contains? #{:list :vector :map :set :fn :forms} (n/tag nd)))

(defn find-slice-matches [z0 target-keys]
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

(defn apply-slice-match [z0 {:keys [step ri0 ri1 col]} new-str]
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
  "Produces a compact unified diff (`-` / `+` with 2 context lines) between `old-str` and `new-str`,
   normalizing trailing blank lines so single-line edits never emit whole-file diffs."
  {:malli/schema [:=> [:cat :string :string [:? :string]] :string]
   :cost/ticks 500000
   :cost/bytes 1048576}
  ([old-str new-str] (unified-diff old-str new-str "form"))
  ([old-str new-str label]
   (let [a (vec (str/split-lines (str/trimr (str (or old-str "")))))
         b (vec (str/split-lines (str/trimr (str (or new-str "")))))]
     (if (= a b)
       ""
       (let [na (count a), nb (count b)
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
         (str/join "\n" hunk))))))

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

(defn parse-require-spec [vec-nd]
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

(defn analyze-parsed-root
  {:malli/schema [:=> [:cat [:maybe :any]] [:vector :map]]}
  [root]
  (let [top-forms (vec (remove n/whitespace-or-comment? (when (and root (n/inner? root)) (n/children root))))
        ns-nd (first top-forms)
        {:keys [qual-ns unqual raw-toks]} (collect-body-refs (rest top-forms))
        req-list (when (and ns-nd (= :list (n/tag ns-nd))
                 (= "ns" (some-> (first (remove n/whitespace-or-comment? (n/children ns-nd))) n/string)))
        (first (filter #(and (= :list (n/tag %))
                             (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                       (n/children ns-nd))))
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
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :map]]}
  [file-src]
  (if (str/blank? (str file-src))
  []
  (if-let [root (try (p/parse-string-all file-src) (catch Throwable _ nil))]
    (analyze-parsed-root root)
    [])))

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

(defn strip-reader-meta
  "Recursively strips reader `:line`/`:column`/`:end-line`/`:end-column` metadata from Clojure data
   while keeping semantic metadata (`^:private`, `^String`, `^:seon.flow/edge`, etc.)."
  [x]
  (let [clean-m (fn [m] (not-empty (dissoc m :line :column :end-line :end-column)))
        remeta (fn [orig v]
                 (if (instance? clojure.lang.IObj v)
                   (if-let [cm (clean-m (meta orig))]
                     (with-meta v cm)
                     (with-meta v nil))
                   v))]
    (cond
      (map? x)    (remeta x (into (empty x) (map (fn [[k v]] [(strip-reader-meta k) (strip-reader-meta v)]) x)))
      (vector? x) (remeta x (mapv strip-reader-meta x))
      (set? x)    (remeta x (into (empty x) (map strip-reader-meta x)))
      (seq? x)    (remeta x (apply list (map strip-reader-meta x)))
      :else       (remeta x x))))

(defn add-require-src
  "Structurally adds one or more `:require` spec(s) (e.g. `'[seon.store :as store]` or a vector of specs)
   to an `(ns ...)` form string idempotently, preserving formatting and existing comments."
  [ns-src spec-or-specs]
  (let [specs (let [v (if (string? spec-or-specs) (read-string spec-or-specs) spec-or-specs)]
                (if (and (vector? v) (seq v) (every? #(or (vector? %) (symbol? %)) v))
                  v
                  [v]))
        norm-specs (mapv (fn [sp] (if (symbol? sp) [sp] sp)) specs)
        root (p/parse-string-all (str ns-src))
        z0 (z/edn root)
        req-loc (loop [loc z0]
                  (cond
                    (z/end? loc) nil
                    (and (= :list (n/tag (z/node loc)))
                         (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children (z/node loc)))) n/string)))
                    loc
                    :else (recur (z/next loc))))]
    (if req-loc
      (let [req-nd (z/node req-loc)
            existing-libs (into #{}
                                (keep (fn [ch]
                                        (cond
                                          (= :vector (n/tag ch))
                                          (some-> (first (remove n/whitespace-or-comment? (n/children ch))) n/string)
                                          (= :token (n/tag ch))
                                          (let [s (n/string ch)] (when-not (str/starts-with? s ":") s)))))
                                (rest (remove n/whitespace-or-comment? (n/children req-nd))))
            to-add (filterv (fn [sp] (not (contains? existing-libs (str (first sp))))) norm-specs)]
        (if (empty? to-add)
          {:ok? true :changed? false :src ns-src :added []}
          (let [[_ col] (prefix-line-and-col (loc-prefix-str req-loc))
                indent-str (apply str (repeat (+ col 10) \space))
                raw (vec (n/children req-nd))
                insert-nodes (mapcat (fn [sp]
                                       [(n/newline-node "\n")
                                        (n/spaces (+ col 10))
                                        (p/parse-string (pr-str sp))])
                                     to-add)
                ;; Keep any trailing whitespace/comment clean by inserting right after last non-ws child
                last-idx (last (keep-indexed (fn [i ch] (when-not (n/whitespace-or-comment? ch) i)) raw))
                updated (vec (concat (subvec raw 0 (inc last-idx))
                                     insert-nodes
                                     (subvec raw (inc last-idx))))
                z' (z/replace req-loc (n/replace-children req-nd updated))]
            {:ok? true :changed? true :src (z/root-string z')
             :added (mapv (comp str first) to-add)})))
      ;; No (:require ...) clause yet — append inside (ns ...)
      (let [ns-loc (loop [loc z0]
                     (cond
                       (z/end? loc) nil
                       (= :list (n/tag (z/node loc))) loc
                       :else (recur (z/next loc))))]
        (if-not ns-loc
          {:ok? false :error :ns-form-not-found}
          (let [ns-nd (z/node ns-loc)
                req-str (str "(:require " (str/join "\n            " (map pr-str norm-specs)) ")")
                raw (vec (n/children ns-nd))
                updated (vec (concat raw [(n/newline-node "\n") (n/spaces 2) (p/parse-string req-str)]))
                z' (z/replace ns-loc (n/replace-children ns-nd updated))]
            {:ok? true :changed? true :src (z/root-string z')
             :added (mapv (comp str first) norm-specs)}))))))

(defn set-defn-meta-src
  "Structurally inserts or merges `meta-map` into a `(defn ...)` / `(defn- ...)` attr-map,
   ALWAYS placed right after `name` + optional `docstring` (valid Clojure syntax) while preserving
   100% of original indentation, comments, `^Type` hints, and multi-arity formatting."
  [form-src meta-map]
  (if-not (and (map? meta-map) (seq meta-map))
    {:ok? false :error :invalid-meta-map :hint "Pass a non-empty map, e.g. {:malli/schema [...] :cost/ticks 500000}."}
    (let [root (p/parse-string-all (str form-src))
          z0 (z/edn root)
          fn-loc (loop [loc z0]
                   (cond
                     (z/end? loc) nil
                     (= :list (n/tag (z/node loc))) loc
                     :else (recur (z/next loc))))]
      (if-not fn-loc
        {:ok? false :error :not-a-defn-form}
        (let [fn-nd (z/node fn-loc)
              raw (vec (n/children fn-nd))
              non-ws (vec (keep-indexed (fn [i ch]
                                          (when-not (n/whitespace-or-comment? ch)
                                            {:i i :node ch :tag (n/tag ch)}))
                                        raw))
              op-s (some-> (first non-ws) :node n/string)]
          (if-not (#{"defn" "defn-"} op-s)
            {:ok? false :error :not-a-defn-form :op op-s}
            (let [str-node? (fn [{:keys [tag node]}]
                              (or (= tag :multi-line)
                                  (and (= tag :token) (str/starts-with? (n/string node) "\""))))
                  has-doc? (and (> (count non-ws) 3) (str-node? (nth non-ws 2)))
                  attr-slot (if has-doc? 3 2)
                  existing-attr (when (and (< attr-slot (count non-ws))
                                           (= :map (:tag (nth non-ws attr-slot))))
                                  (nth non-ws attr-slot))]
              (if existing-attr
                (let [old-map (try (read-string (n/string (:node existing-attr))) (catch Throwable _ {}))
                      merged (merge old-map meta-map)
                      new-map-nd (p/parse-string (binding [*print-namespace-maps* false] (pr-str merged)))
                      updated (assoc raw (:i existing-attr) new-map-nd)
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :merged :src (z/root-string z')})
                (let [anchor-idx (:i (nth non-ws (dec attr-slot)))
                      next-raw (subvec raw (inc anchor-idx))
                      starts-nl? (and (seq next-raw) (= :newline (n/tag (first next-raw))))
                      trimmed-rest (if starts-nl? next-raw (vec (drop-while n/whitespace? next-raw)))
                      map-str (binding [*print-namespace-maps* false] (pr-str meta-map))
                      ins (cond-> [(n/newline-node "\n") (n/spaces 2) (p/parse-string map-str)]
                            (not starts-nl?) (into [(n/newline-node "\n") (n/spaces 2)]))
                      updated (vec (concat (subvec raw 0 (inc anchor-idx)) ins trimmed-rest))
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :inserted :src (z/root-string z')})))))))))

(defn parse-ns-requires
  "Parses the `(:require ...)` specs directly from an `(ns ...)` form string."
  [ns-src]
  (let [root (p/parse-string-all (str ns-src))
        ns-nd (first (remove n/whitespace-or-comment? (n/children root)))
        req-list (first (filter #(and (= :list (n/tag %))
                                      (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                                (n/children ns-nd)))]
    (if req-list
      (vec (keep parse-require-spec
                 (rest (remove n/whitespace-or-comment? (n/children req-list)))))
      [])))