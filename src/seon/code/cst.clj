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
  {:malli/schema [:function [:=> [:cat :any] :any]]}
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
          raw (vec (n/children root))
          nodes (vec (remove n/whitespace-or-comment? raw))]
      {:ok? true :nodes nodes :raw-children raw})
    (catch Throwable ex
      {:ok? false :error :invalid-clojure-syntax :message (or (.getMessage ex) (str ex))})))

(defn strip-common-indent
  {:malli/schema [:=> [:cat :string] :string], :cost/ticks 3000000, :cost/bytes 3000000}
  [s]
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

(defn reindent-to-col
  {:malli/schema [:=> [:cat :string :int] :string], :cost/ticks 3000000, :cost/bytes 3000000}
  [s col]
  (let [norm (strip-common-indent s)
        lines (str/split-lines norm)
        pad (apply str (repeat (max 0 (long col)) \space))]
    (if (<= (count lines) 1)
      norm
      (str/join "\n" (cons (first lines)
                           (map #(if (str/blank? %) "" (str pad %)) (rest lines)))))))

(defn prefix-line-and-col
  {:malli/schema [:=> [:cat :string] [:tuple :int :int]], :cost/ticks 3000000, :cost/bytes 3000000}
  [^String prefix]
  (let [last-nl (.lastIndexOf prefix "\n")
        line (inc (count (filter #(= \newline %) prefix)))
        col (if (neg? last-nl) (count prefix) (- (count prefix) last-nl 1))]
    [line col]))

(defn loc-prefix-str
  {:malli/schema [:function [:=> [:cat :any] :string]]}
  [loc]
  (let [marked (z/replace loc (n/token-node ::__SEON_EDIT_SENTINEL__))
        full (z/root-string marked)
        idx (.indexOf ^String full (str ::__SEON_EDIT_SENTINEL__))]
    (if (neg? idx) "" (subs full 0 idx))))

(defn snippet-of
  {:malli/schema [:function [:=> [:cat :any] :string]]}
  [nd]
  (let [s (str/replace (str/trim (n/string nd)) #"\s+" " ")]
    (if (> (count s) 90) (str (subs s 0 90) "…") s)))

(defn- target-hint [target-key]
  (let [tag (first target-key)
        v (second target-key)
        inner-vec? (and (vector? v) (not (#{:deref :quote :string :token} tag)))
        arity (when inner-vec? (count v))
        head-tok (when (and inner-vec? (pos? (count v)))
                   (let [f0 (first v)]
                     (when (and (vector? f0) (= :token (first f0)))
                       (second f0))))]
    [tag arity head-tok]))

(defn- matching-candidate? [target-tag target-arity target-head nd]
  (let [t (n/tag nd)]
    (case target-tag
      :deref  (or (= t :deref)
                  (and (= t :list)
                       (= 2 (reduce (fn [^long acc ch] (if (n/whitespace-or-comment? ch) acc (inc acc)))
                                    0 (n/children nd)))))
      :quote  (or (= t :quote)
                  (and (= t :list)
                       (= 2 (reduce (fn [^long acc ch] (if (n/whitespace-or-comment? ch) acc (inc acc)))
                                    0 (n/children nd)))))
      :string (or (= t :multi-line) (= t :token))
      :token  (and (= t :token)
                   (or (nil? target-head) (= target-head (n/string nd))))
      (and (= t target-tag)
           (if (some? target-arity)
             (loop [xs (seq (n/children nd)), cnt 0]
               (if-not xs
                 (= cnt target-arity)
                 (let [ch (first xs)]
                   (if (n/whitespace-or-comment? ch)
                     (recur (next xs) cnt)
                     (let [ncnt (inc cnt)]
                       (cond
                         (> ncnt target-arity) false
                         (and (= ncnt 1) (some? target-head)
                              (not (and (= :token (n/tag ch)) (= target-head (n/string ch)))))
                         false
                         :else (recur (next xs) ncnt)))))))
             true)))))

(defn- find-single-matches [z0 target-key]
  (let [[target-tag target-arity target-head]
        (if (= :token (first target-key))
          [:token nil (second target-key)]
          (target-hint target-key))]
    (loop [loc z0, step 0, acc []]
      (if (z/end? loc)
        acc
        (let [nd (z/node loc)]
          (if (and (matching-candidate? target-tag target-arity target-head nd)
                   (= target-key (node->structural-key nd)))
            (let [[line col] (prefix-line-and-col (loc-prefix-str loc))]
              (recur (z/next loc) (inc step)
                     (conj acc {:occurrence (inc (count acc))
                                :step step :loc loc :line line :col col :snippet (snippet-of nd)})))
            (recur (z/next loc) (inc step) acc)))))))

(defn- apply-single-match-zip [z0 chosen new-str]
  (let [col (:col chosen)
        reind (reindent-to-col new-str col)
        parsed (parse-non-ws-nodes reind)]
    (if-not (:ok? parsed)
      parsed
      (let [loc (or (:loc chosen) (nth (iterate z/next z0) (:step chosen)))
            new-children (:raw-children parsed)
            z' (cond
                 (empty? (:nodes parsed)) (z/remove loc)
                 (= 1 (count (:nodes parsed))) (z/replace loc (first (:nodes parsed)))
                 :else
                 (let [first-loc (z/replace loc (first new-children))]
                   (reduce (fn [cur child] (z/insert-right* cur child))
                           first-loc
                           (reverse (rest new-children)))))
            root (when z' (z/root z'))]
        {:ok? true :z (when root (z/of-node root)) :root root}))))

(defn apply-single-match
  {:malli/schema [:function [:=> [:cat :any :int :string :int] :map]]}
  [z0 step new-str col]
  (let [r (apply-single-match-zip z0 {:step step :col col} new-str)]
    (if (:ok? r) {:ok? true :src (if-let [rt (:root r)] (n/string rt) "")} r)))

(defn container-node?
  {:malli/schema [:function [:=> [:cat :any] :boolean]]}
  [nd]
  (contains? #{:list :vector :map :set :fn :forms} (n/tag nd)))

(defn find-slice-matches
  {:malli/schema [:function [:=> [:cat :any [:vector :any]] [:vector :map]]]}
  [z0 target-keys]
  (let [k (count target-keys)
        hints (mapv (fn [tk]
                      (if (= :token (first tk))
                        [:token nil (second tk)]
                        (target-hint tk)))
                    target-keys)
        k0 (first target-keys)
        [t0-tag t0-arity t0-head] (first hints)]
    (loop [loc z0, step 0, acc []]
      (if (z/end? loc)
        acc
        (let [nd (z/node loc)
              hits (when (and (container-node? nd)
                              (some (fn [ch]
                                      (and (not (n/whitespace-or-comment? ch))
                                           (matching-candidate? t0-tag t0-arity t0-head ch)))
                                    (n/children nd)))
                     (let [raw (vec (n/children nd))
                           non-ws (vec (keep-indexed (fn [i ch]
                                                       (when-not (n/whitespace-or-comment? ch)
                                                         {:raw-i i :node ch}))
                                                     raw))
                           m (count non-ws)]
                       (when (>= m k)
                         (for [j (range 0 (inc (- m k)))
                               :let [n0 (:node (nth non-ws j))]
                               :when (and (matching-candidate? t0-tag t0-arity t0-head n0)
                                          (= k0 (node->structural-key n0))
                                          (loop [off 1]
                                            (if (= off k)
                                              true
                                              (let [tk (nth target-keys off)
                                                    [tt ta th] (nth hints off)
                                                    nj (:node (nth non-ws (+ j off)))]
                                                (if (and (matching-candidate? tt ta th nj)
                                                         (= tk (node->structural-key nj)))
                                                  (recur (inc off))
                                                  false)))))]
                           (let [ri0 (:raw-i (nth non-ws j))
                                 ri1 (:raw-i (nth non-ws (+ j k -1)))
                                 before-str (apply str (map n/string (subvec raw 0 ri0)))
                                 open-tok (case (n/tag nd) :list "(" :vector "[" :map "{" :set "#{" :fn "#(" "")
                                 [line col] (prefix-line-and-col (str (loc-prefix-str loc) open-tok before-str))
                                 snip (let [s (str/replace (apply str (map n/string (subvec raw ri0 (inc ri1)))) #"\s+" " ")]
                                        (if (> (count s) 90) (str (subs s 0 90) "…") s))]
                             {:step step :loc loc :ri0 ri0 :ri1 ri1 :line line :col col :snippet snip})))))]
          (recur (z/next loc) (inc step)
                 (reduce (fn [a h] (conj a (assoc h :occurrence (inc (count a))))) acc hits)))))))

(defn- apply-slice-match-zip [z0 {:keys [step loc ri0 ri1 col]} new-str]
  (let [reind (reindent-to-col new-str col)
        parsed (parse-non-ws-nodes reind)]
    (if-not (:ok? parsed)
      parsed
      (let [tloc (or loc (nth (iterate z/next z0) step))
            nd (z/node tloc)
            raw (vec (n/children nd))
            new-children (:raw-children parsed)
            trimmed-new (let [c1 (drop-while n/whitespace? new-children)
                              c2 (reverse (drop-while n/whitespace? (reverse c1)))]
                          (vec c2))
            updated (vec (concat (subvec raw 0 ri0) trimmed-new (subvec raw (inc ri1))))
            z' (z/replace tloc (n/replace-children nd updated))
            root (z/root z')]
        {:ok? true :z (z/of-node root) :root root}))))

(defn apply-slice-match
  {:malli/schema [:function [:=> [:cat :any :map :string] :map]]}
  [z0 match new-str]
  (let [r (apply-slice-match-zip z0 match new-str)]
    (if (:ok? r) {:ok? true :src (n/string (:root r))} r)))

(defn patch-form-source-batch
  "Structurally applies a sequence of `[old-str new-str]` edits to `form-src` in ONE zipper parse
   (avoiding `2*K` full `rewrite-clj` parses and redundant post-verify re-parses)."
  {:malli/schema [:function
                  [:=> [:cat :string [:vector [:tuple :string :string]]] :map]
                  [:=> [:cat :string [:vector [:tuple :string :string]] [:maybe :map]] :map]]}
  ([form-src pairs] (patch-form-source-batch form-src pairs nil))
  ([form-src pairs {:keys [occurrence]}]
   (if (empty? pairs)
     {:ok? true :src form-src :line 1 :applied 0}
     (let [z-init (try {:ok? true :z (z/of-string form-src)}
                       (catch Throwable ex
                         {:ok? false :error :invalid-clojure-syntax :stage :parse-form
                          :message (or (.getMessage ex) (str ex))}))]
       (if-not (:ok? z-init)
         z-init
         (let [res (reduce
                    (fn [{:keys [z first-line applied]} [old-str new-str]]
                      (if (nil? z)
                        (reduced {:ok? false :error :no-match :applied applied
                                  :hint "Form became empty after prior deletion."})
                        (let [parsed-old (parse-non-ws-nodes old-str)]
                          (if-not (:ok? parsed-old)
                            (reduced (assoc parsed-old :stage :parse-old :applied applied))
                            (let [old-nodes (:nodes parsed-old)]
                              (if (empty? old-nodes)
                                (reduced {:ok? false :error :empty-old-subform :applied applied})
                                (let [single? (= 1 (count old-nodes))
                                      matches (if single?
                                                (find-single-matches z (node->structural-key (first old-nodes)))
                                                (find-slice-matches z (mapv node->structural-key old-nodes)))]
                                  (cond
                                    (empty? matches)
                                    (reduced {:ok? false :error :no-match :applied applied
                                              :hint "Subform not found structurally (whitespace/comments ignored). Check (user/source 'ns/fn)."})

                                    (and (> (count matches) 1) (nil? occurrence))
                                    (reduced {:ok? false :error :ambiguous-match :count (count matches) :applied applied
                                              :matches (mapv #(select-keys % [:occurrence :line :snippet]) matches)
                                              :hint "Pass {:occurrence N} or include enclosing parent form."})

                                    (and occurrence (or (< occurrence 1) (> occurrence (count matches))))
                                    (reduced {:ok? false :error :occurrence-out-of-range :occurrence occurrence :count (count matches) :applied applied})

                                    :else
                                    (let [chosen (nth matches (dec (or occurrence 1)))
                                          step-res (if single?
                                                     (apply-single-match-zip z chosen new-str)
                                                     (apply-slice-match-zip z chosen new-str))]
                                      (if-not (:ok? step-res)
                                        (reduced (assoc step-res :applied applied))
                                        {:ok? true
                                         :z (:z step-res)
                                         :root (:root step-res)
                                         :first-line (or first-line (:line chosen))
                                         :occurrences (count matches)
                                         :applied (inc applied)}))))))))))
                    {:ok? true :z (:z z-init) :root (z/root (:z z-init)) :first-line nil :applied 0}
                    pairs)]
           (if-not (:ok? res)
             res
             {:ok? true
              :src (if-let [rt (:root res)] (n/string rt) "")
              :line (:first-line res)
              :occurrences (:occurrences res)
              :applied (:applied res)})))))))

(defn patch-form-source
  "Structurally patches `form-src` by replacing `old-str` with `new-str`, ignoring spaces, tabs,
   newlines, commas, and inline comments during matching while preserving all surrounding
   formatting & comments losslessly. Supports both single-form and contiguous sibling-slice edits."
  {:malli/schema [:function [:=> [:cat :string :string :string [:? :map]] :map]]}
  [form-src old-str new-str & [opts]]
  (patch-form-source-batch form-src [[old-str new-str]] opts))

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