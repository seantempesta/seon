;; The blind scorers' packets (task: printer-eval): renders.edn's renderings, unlabelled, each with
;; its question (questions.edn). Writes target/printer-eval/packets/<agent>.md and the key that
;; unblinds them, target/printer-eval/packets/key.edn ({agent {slot [entry-id variant]}}).
;; Accuracy packets show one rendering of a value (an agent never sees two of one value);
;; preference packets show a pair, X and Y, in an order fixed by a hash of the pair.
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/packets.clj
(require '[seon.edn :as edn] '[clojure.string :as str] '[clojure.java.shell :as sh])

(def entries (into {} (for [e (edn/decode (slurp "notes/printer-eval/renders.edn"))] [(:id e) e])))
(def questions (edn/decode (slurp "notes/printer-eval/questions.edn")))
(def dir "target/printer-eval/packets")
(sh/sh "mkdir" "-p" dir)

(def ids37 (sort (filter #(str/starts-with? % "37/") (keys questions))))
(def ids38 (sort (filter #(str/starts-with? % "38/") (keys questions))))
(defn variants [id] (vec (sort (keys (:variants (entries id))))))
(defn text [id k] (get-in entries [id :variants k]))

(def intro
  "You are scoring how well a Clojure agent system (Seon) shows values to the model that works in it.
Each section below is one REPL answer, value or context text exactly as the model reads it. A value
too big for its token budget is cut: the printer leaves part out and (usually) says how much and the
exact Clojure form that reads the rest. `result/h3` is the REPL's handle for the evaluated value;
`(my.turn/evals)` is the turn's evaluations, in order.

Answer each question ONLY from that section's text. Do not guess what is not shown: if the answer is
not in the text, say \"not shown\". Copy forms exactly as the text gives them.")

(def acc-reply
  "Reply with one JSON array and nothing else, one object per section:
[{\"section\": 1, \"answer\": \"...\", \"left_out\": \"what was left out and how much, or null\",
  \"read_form\": \"the exact form that reads what was left out, or null\", \"confident\": true}]")

(def pref-reply
  "For each pair, X and Y are two renderings of the same value. Score each 1-5 on:
- answer: how well it lets you answer the question;
- left_out: how clearly it says what was left out and the exact form to read the rest;
- misleading: 5 = nothing misleading or duplicated, 1 = misleading or repeats itself;
- concise: information per token.
For lines that a person scans on a dashboard, also weigh: is the state first, then the evidence?
Then pick the better one for an agent that must act on it.
Reply with one JSON array and nothing else, one object per pair:
[{\"pair\": 1, \"answer_x\": \"...\", \"answer_y\": \"...\",
  \"x\": {\"answer\": 4, \"left_out\": 5, \"misleading\": 5, \"concise\": 3},
  \"y\": {\"answer\": 4, \"left_out\": 3, \"misleading\": 5, \"concise\": 4},
  \"better\": \"X\" | \"Y\" | \"tie\", \"why\": \"one sentence\"}]")

(defn section [n id k]
  (str "## Section " n "\n\nQuestion: " (:q (questions id)) "\n\n```\n" (text id k) "\n```\n"))

(defn pair-section [n id kx ky]
  (str "## Pair " n "\n\nQuestion: " (:q (questions id)) "\n\n### X\n```\n" (text id kx) "\n```\n\n### Y\n```\n" (text id ky) "\n```\n"))

(def key-map (atom {}))

(defn accuracy!
  "Agents name-1..: agent r answers each id with variant (i + r) mod count; ids in order."
  [name ids reps]
  (doseq [rep (range reps)
          r (range (count (variants (first ids))))
          :let [agent (str name "-" (inc (+ r (* rep (count (variants (first ids)))))))
                order (if (even? rep) ids (reverse ids))
                slots (vec (map-indexed (fn [n id] (let [vs (variants id)] [(inc n) id (nth vs (mod (+ n r) (count vs)))])) order))]]
    (swap! key-map assoc agent (into {} (for [[n id k] slots] [n [id k]])))
    (spit (str dir "/" agent ".md") (str intro "\n\n" acc-reply "\n\n" (str/join "\n" (for [[n id k] slots] (section n id k)))))))

(defn preference!
  "Each agent gets one pair per id: pairs [a b] of the id's variants, dealt round-robin; X/Y by hash."
  [name ids agents both-orders?]
  (let [pairs (for [id ids
                    [a b] (let [vs (variants id)] (for [i (range (count vs)) j (range (inc i) (count vs))] [(vs i) (vs j)]))
                    o (if both-orders? [0 1] [(mod (hash (str id a b)) 2)])]
                [id (if (zero? o) [a b] [b a])])
        dealt (group-by first (map-indexed (fn [i p] [(mod i agents) p]) pairs))]
    (doseq [[g ps] dealt
            :let [agent (str name "-" (inc g))
                  slots (vec (map-indexed (fn [n [_ [id [x y]]]] [(inc n) id x y]) ps))]]
      (swap! key-map assoc agent (into {} (for [[n id x y] slots] [n [id x y]])))
      (spit (str dir "/" agent ".md") (str intro "\n\n" pref-reply "\n\n" (str/join "\n" (for [[n id x y] slots] (pair-section n id x y))))))))

(accuracy! "acc37" ids37 2)
(accuracy! "acc38" ids38 2)
(preference! "pref37" ids37 3 false)
(preference! "pref38" ids38 2 true)
(spit (str dir "/key.edn") (edn/encode @key-map))
(doseq [[a slots] (sort @key-map)] (println a (count slots) (count (slurp (str dir "/" a ".md")))))
