(ns seon.schema-split
  "Mechanically split and verify Seon's schema declaration resources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private separator
  ";; ===========================================================================")

(def ^:private section-resources
  {"ADMIT" "seon.sci.admit.edn"
   "AGENT" "seon.cluster.agent.edn"
   "AI" "seon.ai.edn"
   "BLOCK" "seon.render.block.edn"
   "BOOT" "seon.boot.edn"
   "CONFIG" "seon.config.edn"
   "CONTEXT" "seon.context.edn"
   "DATA" "seon.render.data.edn"
   "DISPOSITIONS" "my.run.edn"
   "ERROR" "seon.error.edn"
   "EVAL" "seon.sci.eval.edn"
   "EFFECT" "seon.effect.edn"
   "EXPORT" "seon.export.edn"
   "FLOW" "seon.flow.edn"
   "INSTRUCTION" "seon.cluster.instruction.edn"
   "INSTRUMENT" "seon.instrument.edn"
   "LOOP" "seon.cluster.loop.edn"
   "MESSAGE" "seon.cluster.message.edn"
   "PRINT" "seon.print.edn"
   "PROBLEMS" "seon.problems.edn"
   "PROCESS" "seon.cluster.process.edn"
   "PROGRAM" "seon.fn.edn"
   "PROMPT" "seon.cluster.prompt.edn"
   "PROVENANCE" "seon.db.edn"
   "RECONCILE" "seon.reconcile.edn"
   "REGISTRY" "seon.cluster.registry.edn"
   "RENDER" "seon.render.edn"
   "RENDER_VALUE" "seon.render.value.edn"
   "REPLY" "seon.cluster.reply.edn"
   "RUN" "seon.cluster.run.edn"
   "SOURCE" "seon.source.edn"
   "STORE" "seon.store.edn"
   "TEST" "seon.test.edn"
   "WAKE" "seon.cluster.wake.edn"
   "WALK" "seon.render.walk.edn"
   "WEB" "seon.render.web.edn"
   "WORK" "seon.cluster.work.edn"})

(defn- section-start?
  [lines index]
  (and (= separator (get lines index))
       (some? (get lines (+ index 1)))
       (= separator (get lines (+ index 2)))
       (str/starts-with? (get lines (+ index 1)) ";; ")))

(defn- section-name
  [lines index]
  (subs (get lines (+ index 1)) 3))

(defn- section-starts
  [lines]
  (into []
        (keep (fn [index]
                (when (section-start? lines index)
                  [index (section-name lines index)])))
        (range (count lines))))

(defn- section-texts
  [text]
  (let [lines (vec (str/split-lines text))
        starts (section-starts lines)]
    (when (empty? starts)
      (throw (ex-info "Schema monolith has no named sections." {})))
    (into {}
          (map-indexed
           (fn [section-index [start section]]
             (let [next-start (or (first (get starts (inc section-index)))
                                  (count lines))
                   body (subvec lines (+ start 3) next-start)
                   body (if (= "}" (peek body)) (pop body) body)]
               [section (str "{" (when (seq body) "\n")
                          (str/join "\n" body) "}\n")]))
           starts))))

(defn- read-map
  [file]
  (let [value (edn/read-string (slurp file))]
    (when-not (map? value)
      (throw (ex-info "Schema resource must contain one map."
                      {:file (str file)})))
    value))

(defn- split-files
  [directory]
  (->> (.listFiles (io/file directory))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))))

(defn- merge-files
  [files]
  (reduce
   (fn [{:keys [forms files-by-key]} file]
     (reduce-kv
      (fn [state registry-key form]
        (when-let [first-file (get files-by-key registry-key)]
          (throw
           (ex-info (str "Duplicate schema declaration " registry-key " in "
                         first-file " and " file ".")
                    {:key registry-key
                     :files [(str first-file) (str file)]})))
        {:forms (assoc (:forms state) registry-key form)
         :files-by-key (assoc (:files-by-key state) registry-key (str file))})
      {:forms forms :files-by-key files-by-key}
      (read-map file)))
   {:forms {} :files-by-key {}}
   files))

(defn- write-split!
  [text directory]
  (let [sections (section-texts text)
        unknown (sort (remove section-resources (keys sections)))
        missing (sort (remove sections (keys section-resources)))]
    (when (seq unknown)
      (throw (ex-info "Schema monolith has unmapped sections."
                      {:sections unknown})))
    (when (seq missing)
      (println "Absent optional sections:" (str/join ", " missing)))
    (.mkdirs (io/file directory))
    (doseq [[section resource] section-resources
            :let [text (get sections section)]
            :when text]
      (spit (io/file directory resource) text))
    (println "Wrote" (count sections) "schema domain resources.")))

(defn- check-forms!
  [monolith directory]
  (let [files (split-files directory)
        merged (:forms (merge-files files))]
    (when-not (= monolith merged)
      (throw
       (ex-info "Split schema declarations differ from the monolith."
                {:monolith-keys (count monolith)
                 :split-keys (count merged)
                 :missing (sort (remove merged (keys monolith)))
                 :extra (sort (remove monolith (keys merged)))
                 :changed (sort (filter #(and (contains? merged %)
                                              (not= (get monolith %)
                                                    (get merged %)))
                                        (keys monolith)))})))
    (println "Schema declaration equality:"
             (count monolith) "keys across" (count files) "files.")))

(defn- check!
  [monolith-file directory]
  (check-forms! (read-map monolith-file) directory))

(defn- check-stdin!
  [directory]
  (let [monolith (edn/read-string (slurp *in*))]
    (when-not (map? monolith)
      (throw (ex-info "Schema monolith must contain one map." {})))
    (check-forms! monolith directory)))

(defn -main
  "Write or verify the mechanically split schema resources."
  [& [operation first-path second-path]]
  (case operation
    "write-stdin" (write-split! (slurp *in*) first-path)
    "check-stdin" (check-stdin! first-path)
    "check" (check! first-path second-path)
    (throw (ex-info "Use write-stdin DIRECTORY, check-stdin DIRECTORY, or check MONOLITH DIRECTORY."
                    {:operation operation}))))
