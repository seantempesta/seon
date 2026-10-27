;; The blind scores unblinded (task: printer-eval): each scorer's answers (scores/<packet>.json,
;; collected by collect.py) joined to the key (notes/printer-eval/key.edn) and graded
;; against questions.edn. Accuracy: the read form exactly (spaces aside) and the count left out,
;; per variant; :fact checked by hand from the answers printed under --facts. Preference: wins,
;; ties and mean rubric scores per variant pair, both orders pooled.
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/tally.clj [--facts]
(require '[seon.edn :as edn] '[clojure.data.json :as json] '[clojure.string :as str] '[clojure.java.io :as io])

(def key-map (edn/decode (slurp "notes/printer-eval/key.edn")))
(def questions (edn/decode (slurp "notes/printer-eval/questions.edn")))
(defn scores [packet] (let [f (io/file (str "notes/printer-eval/scores/" packet ".json"))] (when (.exists f) (json/read-str (slurp f)))))
(defn squash [s] (some-> s (str/replace #"\s+" " ") str/trim))

(defn form-ok? [id answer]
  (let [want (get-in questions [id :key :form])]
    (or (nil? want)
        (some #(= (squash %) (squash (get answer "read_form"))) (str/split want #" or ")))))

(defn n-ok? [id answer]
  (let [want (get-in questions [id :key :n])
        text (str (get answer "answer") " " (get answer "left_out"))]
    (or (nil? want) (some #(str/includes? text %) (re-seq #"\d+" (str want))))))

(def acc
  (for [[packet slots] key-map :when (str/starts-with? packet "acc")
        a (scores packet)
        :let [[id k] (get slots (long (get a "section")))]]
    {:packet packet :id id :variant k :form? (boolean (form-ok? id a)) :n? (boolean (n-ok? id a)) :confident? (get a "confident") :answer a}))

(println "== accuracy: per candidate and variant, answers with the exact form / the count left out / confident")
(doseq [[[c k] rows] (sort (group-by (fn [r] [(subs (:id r) 0 2) (:variant r)]) acc))]
  (println c k (count rows) "answers;" "form" (count (filter :form? rows)) "count" (count (filter :n? rows)) "confident" (count (filter :confident? rows))))
(println "\n== wrong answers")
(doseq [r acc :when (not (and (:form? r) (:n? r)))]
  (println (:packet r) (:id r) (:variant r) (if (:form? r) "" "FORM") (if (:n? r) "" "COUNT") "|" (get-in r [:answer "read_form"]) "|" (get-in r [:answer "left_out"])))

(when (some #{"--facts"} *command-line-args*)
  (println "\n== answers, for the facts")
  (doseq [r (sort-by (juxt :id :variant) acc)]
    (println (:id r) (:variant r) "|" (get-in r [:answer "answer"]))))

(def pref
  (for [[packet slots] key-map :when (str/starts-with? packet "pref")
        p (scores packet)
        :let [[id x y] (get slots (long (get p "pair")))
              better (get p "better")]]
    {:id id :pair (vec (sort [x y]))
     :winner (case better "X" x "Y" y nil)
     :rubric {x (get p "x") y (get p "y")} :why (get p "why")}))

(println "\n== preference: per pair of variants, wins (ties) and mean rubric (answer left_out misleading concise)")
(doseq [[[c pair] rows] (sort (group-by (fn [r] [(subs (:id r) 0 2) (:pair r)]) pref))]
  (let [mean (fn [k crit] (let [xs (keep #(get-in % [:rubric k crit]) rows)] (when (seq xs) (/ (Math/round (* 10.0 (/ (reduce + xs) (count xs)))) 10.0))))]
    (println c pair (count rows) "pairs;"
             (str/join ", " (for [k pair] (str (name k) " " (count (filter #(= k (:winner %)) rows)))))
             "ties" (count (filter (comp nil? :winner) rows)) "|"
             (str/join " ; " (for [k pair] (str (name k) " " (str/join " " (for [crit ["answer" "left_out" "misleading" "concise"]] (mean k crit)))))))))
(println "\n== preference by entry")
(doseq [r (sort-by :id pref)] (println (:id r) (:pair r) "->" (some-> (:winner r) name) "|" (:why r)))
