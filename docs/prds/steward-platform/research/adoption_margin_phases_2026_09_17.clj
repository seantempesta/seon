(require '[clojure.edn :as edn] '[clojure.string :as str])

;; Usage: bb this-file.clj OUTPUT.edn LOG...
(let [[output & paths] *command-line-args*
      prefix "● current-src: "
      timing-prefix "publication-phase "
      observations
      (mapv
       (fn [path]
         (let [text (slurp path)
               lines (str/split-lines text)
               events
               (if (str/ends-with? path ".edn")
                 (:seon.fresh-operator/progress (edn/read-string text))
                 (keep (fn [line]
                         (cond
                           (str/starts-with? line (str prefix "#:seon.source"))
                           (edn/read-string (subs line (count prefix)))
                           (str/starts-with? line timing-prefix)
                           (let [[phase _ ms]
                                 (edn/read-string
                                  (str "[" (subs line (count timing-prefix)) "]"))]
                             {:seon.source/completed-phase phase
                              :seon.source/elapsed-ms ms})))
                       lines))]
           {:seon.adoption-margin/source path
            :seon.adoption-margin/phases
            (filterv #(not (str/starts-with? (:seon.source/completed-phase %) "WARNING "))
                     events)
            :seon.adoption-margin/terminal-lines
            (filterv #(or (str/starts-with? % "complete-program-publication-ms ")
                          (str/starts-with? % "● init phase=init elapsed-ms=")
                          (str/starts-with? % "● init phase=lifecycle elapsed-ms=")
                          (str/starts-with? % "Ran ")
                          (str/includes? % " failures, ")
                          (str/starts-with? % "FAIL in ")
                          (str/starts-with? % "expected: ")
                          (str/starts-with? % "actual: ")
                          (str/starts-with? % "progressing-holder-completed-ms ")
                          (str/starts-with? % "stalled-holder-refused-ms "))
                     lines)}))
       paths)]
  (spit output (str (pr-str observations) "\n")))
