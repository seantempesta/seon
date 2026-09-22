(ns seon.dev.issues
  "Query the database issue index and its citation refusals."
  (:refer-clojure :exclude [run!]))

(defn query-form
  "Build the read-only issue query for the operator's existing live transport."
  [arguments]
  (let [arguments (vec arguments)
        class-tag (when (and (= 2 (count arguments)) (= "--class" (first arguments)))
                    (second arguments))]
    (when-not (or (empty? arguments) (= ["--check"] arguments) class-tag)
      (throw (ex-info "Usage: bin/issues-index [--check | --class class/<id>]" {})))
    (list 'do
          '(require 'seon.issue)
          (list 'seon.issue/report
                (cond-> {:seon.db/db '(seon.db/db (seon.cluster.boot/connection "default"))
                         :seon.issue/root "."}
                  class-tag (assoc :seon.issue/class class-tag))))))

(defn exit-code
  "Nonzero means the index has refusals or the live database was unavailable."
  {:malli/schema [:=> [:cat :seon.schema/value] [:enum 0 1]]}
  [result]
  (if (and (map? result) (number? (:seon.issue/count result))
           (empty? (:seon.issue/refusals result)))
    0 1))

(defn run!
  "Query the running operator; print every refusal for --check."
  [root arguments]
  (let [response ((requiring-resolve 'seon.operator/live-root-value!) root (pr-str (query-form arguments)))
        result (:seon.operator/value response)]
    (if (= ["--check"] (vec arguments))
      (prn (dissoc result :seon.issue/entities))
      (if (= "--class" (first arguments))
        (doseq [entity (:seon.issue/entities result) :when (= :open (:seon.issue/status entity))]
          (println (:seon.issue/path entity)))
        (doseq [entity (:seon.issue/entities result)]
          (prn (select-keys entity [:seon.issue/id :seon.issue/title :seon.issue/severity :seon.issue/path])))))
    (when-not (:seon.operator/live-process? response)
      (binding [*out* *err*] (println "Issue inspection requires a running operator.")))
    (exit-code result)))
