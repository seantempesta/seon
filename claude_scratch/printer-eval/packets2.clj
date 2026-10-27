;; Round 2's blind packets (task: printer-eval): every variant of a value at once, labelled P, Q, R
;; in an order that differs per scorer, each with its size in tokens (the printer's estimate), to
;; rank. Row 37's three variants (round 1's pairwise votes cycled) and row 38's one-line cells
;; (renders2.edn: :A today, :B select's words, :E prose's break with `…+N chars`).
;; Writes target/printer-eval/packets/rank*.md and key2.edn ({agent {slot [entry-id {label variant}]}}).
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/packets2.clj
(require '[seon.edn :as edn] '[clojure.string :as str] '[seon.print.select :as select])

(def entries (into {} (for [e (concat (edn/decode (slurp "notes/printer-eval/renders.edn")) (edn/decode (slurp "notes/printer-eval/renders2.edn")))] [(:id e) e])))
(def questions (edn/decode (slurp "notes/printer-eval/questions.edn")))
(def dir "target/printer-eval/packets")
(defn q [id] (or (:q (questions id)) (:q (questions (str/replace id "line2/" "prose/")))
                 "What does this cell tell you, and how much of the underlying text is NOT shown?"))

(def intro
  "You are scoring how well a Clojure agent system (Seon) shows values to the model that works in it,
and on the dashboard a person scans. Each section shows ONE value rendered several ways (P, Q, R). A
value too big for its token budget is cut: the printer leaves part out and (usually) says how much
and the exact Clojure form that reads the rest. `result/h3` is the REPL's handle for the evaluated
value; `(my.turn/evals)` is the turn's evaluations, in order. Each rendering's size in tokens is given:
a rendering larger than the others spends the reader's context on it.

For each section, rank the renderings best to worst for an agent (or, for a one-line cell, a person
scanning a dashboard: state first, then evidence) who must answer the question and act on it. Weigh:
can it answer the question; does it say what was left out and the exact form to read the rest;
anything misleading or repeated; information per token.
Reply with one JSON array and nothing else, one object per section:
[{\"section\": 1, \"ranking\": [\"Q\", \"P\", \"R\"], \"why\": \"one sentence\"}]")

(def orders [[0 1 2] [1 2 0] [2 0 1] [2 1 0] [0 2 1] [1 0 2]])
(def key2 (atom {}))

(defn packet! [agent ids shift]
  (let [slots (vec (for [[n id] (map-indexed vector ids)
                         :let [vs (vec (sort (keys (:variants (entries id)))))
                               order (nth orders (mod (+ n shift) (count orders)))
                               labels (zipmap ["P" "Q" "R"] (map vs order))]]
                     [(inc n) id labels]))]
    (swap! key2 assoc agent (into {} (for [[n id labels] slots] [n [id labels]])))
    (spit (str dir "/" agent ".md")
          (str intro "\n\n"
               (str/join "\n" (for [[n id labels] slots]
                                (str "## Section " n "\n\nQuestion: " (q id) "\n\n"
                                     (str/join "\n" (for [l ["P" "Q" "R"] :let [t (get-in entries [id :variants (labels l)])]]
                                                      (str "### " l " (≈" (select/estimate t) " tokens)\n```\n" t "\n```\n"))))))))))

(def ids37 (sort (filter #(str/starts-with? % "37/") (keys entries))))
(def ids38 (sort (filter #(str/starts-with? % "38/line2/") (keys entries))))
(doseq [i (range 3)] (packet! (str "rank37-" (inc i)) ids37 (* 2 i)))
(doseq [i (range 3)] (packet! (str "rank38-" (inc i)) ids38 (* 2 i)))
(spit (str dir "/key2.edn") (edn/encode @key2))
(doseq [[a s] (sort @key2)] (println a (count s) (count (slurp (str dir "/" a ".md")))))
