(ns tmp.floor-printer.judge
  "Judge the value printer over the corpus: tokens, essence and cut quality.
  Disposable probe."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.render :as render]
            [seon.config :as config]
            [seon.print :as print]))

(def handle 'result/e0a1b2c3d4e5f)

(defn profile
  "The agent profile default applies."
  [database]
  (render/agent-render-profile (config/effective database "default")))

(defn- nodes [tree]
  (tree-seq map? (fn [n] (concat (::print/items n) (mapcat #(if (vector? %) % [%]) (::print/entries n))
                                 (when (= ::print/throwable (::print/face n)) [(::print/value n)])))
            tree))

(defn- essence-key? [k]
  (and (keyword? k) (namespace k)
       (contains? #{"id" "name" "sym" "message" "key" "ident"} (name k))))

(defn judge
  "Print `value` with `prepare` (a var) and score the text."
  [prepare database value output]
  (let [t0 (System/nanoTime)
        p (prepare {:seon.render/value value :seon.render/profile (profile database)
                    :seon.repl/handle handle :seon.db/db database
                    :seon.render.call/id [:seon.cluster.eval/source "probe"]} output)
        ms (/ (- (System/nanoTime) t0) 1e6)
        text (or (:seon.render.value/text p) (pr-str p))
        tree (:seon.render.value/tree p)
        reached (when tree (nodes tree))
        cuts (filter #(= ::print/elided (::print/face %)) reached)
        root-map? (and (seon.render.value/keyword-lookup? value) (seq value))
        root-keys (when (and root-map? tree)
                    (set (keep #(when (vector? %) (::print/value (first %))) (::print/entries tree))))
        essence (when root-map? (filter essence-key? (keys value)))
        biggest (reduce max 0 (map #(count (str (::print/value %))) (filter #(= ::print/string (::print/face %)) reached)))]
    {:chars (count text)
     :tokens (tokens/estimate text)
     :ms ms
     :cuts (count cuts)
     :cuts-without-dig (count (remove ::print/continuation cuts))
     :top-keys (when root-map? [(count root-keys) (count value)])
     :essence-missing (vec (remove #(contains? root-keys %) essence))
     :biggest-string-share (if (pos? (count text)) (double (/ biggest (count text))) 0.0)
     :text text
     :html-chars (when (= output :seon.render/html) (count (pr-str (:seon.render.value/html p))))}))

(defn badness
  "Higher is worse: missing top-level keys, missing essence keys, one string
  owning the text, cuts without a dig form, and tokens."
  [{:keys [top-keys essence-missing biggest-string-share cuts-without-dig tokens]}]
  (+ (if top-keys (* 40 (- (min 32 (second top-keys)) (first top-keys))) 0)
     (* 60 (count essence-missing))
     (if (> biggest-string-share 0.4) (* 100 biggest-string-share) 0)
     (* 30 cuts-without-dig)
     (/ tokens 20.0)))

(defn run
  "Judge every corpus entry; answers the rows with scores."
  [prepare database corpus output]
  (mapv (fn [{:keys [label category value]}]
          (let [j (try (judge prepare database value output)
                       (catch Throwable t {:error (str (class t) ": " (ex-message t)) :tokens 0 :chars 0}))]
            (assoc j :label label :category category :badness (if (:error j) 1000 (badness j)))))
        corpus))

(defn summary
  "Totals and the worst n."
  [rows n]
  {:count (count rows)
   :tokens (reduce + (map :tokens rows))
   :max-tokens (reduce max (map :tokens rows))
   :missing-top-keys (reduce + (keep #(when-let [[s t] (:top-keys %)] (- (min 32 t) s)) rows))
   :essence-missing (reduce + (map (comp count :essence-missing) rows))
   :cuts (reduce + (keep :cuts rows))
   :cuts-without-dig (reduce + (keep :cuts-without-dig rows))
   :string-dominated (count (filter #(> (:biggest-string-share % 0) 0.4) rows))
   :errors (count (filter :error rows))
   :max-ms (reduce max 0 (keep :ms rows))
   :worst (mapv #(select-keys % [:label :badness :tokens :top-keys :essence-missing :cuts-without-dig])
                (take n (sort-by (comp - :badness) rows)))})

(defn excerpt
  "The first n characters of a row's text, one line per line."
  [row n]
  (str/join "\n" (take 40 (str/split-lines (subs (:text row) 0 (min n (count (:text row))))))))
