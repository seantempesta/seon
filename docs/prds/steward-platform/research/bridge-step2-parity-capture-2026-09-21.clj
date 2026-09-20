;; Evaluate in the owned scratch JVM. Git supplies the historical oracle;
;; this evidence script maintains no duplicate walker implementation.
(require '[clojure.java.shell :as sh] '[clojure.string :as str]
         '[seon.schema :as schema] '[seon.schema.datahike :as bridge]
         '[seon.db :as db] '[seon.operator :as operator])
(let [historical (fn [path]
                   (let [{:keys [exit out err]}
                         (sh/sh "git" "show" (str "22a1a0567:" path))]
                     (assert (zero? exit) err) out))]
  (load-string (str/replace-first (historical "src/seon/schema/form.cljc")
                                  "(ns seon.schema.form" "(ns bridge.step2-baseline-form"))
  (load-string
   (-> (historical "src/seon/schema/datahike.clj")
       (str/replace-first "(ns seon.schema.datahike" "(ns bridge.step2-baseline")
       (str/replace "[seon.schema.form :as schema.form]" "")
       (str/replace "schema.form/" "bridge.step2-baseline-form/"))))
(let [projection (db/carried-projection (db/db (operator/connection "s2")))
      forms (:seon.schema.projection/forms projection)
      old (fn [sym & args] (apply (ns-resolve 'bridge.step2-baseline sym) args))
      attributes (old 'database-attributes-in projection)
      native (old 'malli->datahike-schema-in projection attributes)
      captured {:seon.bridge.parity/baseline-commit "22a1a0567"
                :seon.bridge.parity/forms forms
                :seon.bridge.parity/input-digest
                (schema/sha-256 [(.getBytes (schema/canonical-data-string forms) "UTF-8")])
                :seon.bridge.parity/core-attributes
                (into [] (filter #(old 'storable-attribute-in? projection %))
                      ((ns-resolve 'bridge.step2-baseline-form 'database-attributes) forms))
                :seon.bridge.parity/property-attributes
                (into (sorted-set) (filter #(old 'storable-attribute-in? projection %))
                      ((ns-resolve 'bridge.step2-baseline-form 'property-attributes) forms))
                :seon.bridge.parity/attributes attributes
                :seon.bridge.parity/native native}
      compiled (bridge/database-attributes-in projection)]
  (assert (seq forms))
  (assert (= attributes compiled))
  (assert (= native (bridge/malli->datahike-schema-in projection compiled)))
  (spit "tmp/bridge-step2-current-parity.edn" (pr-str captured))
  {:schemas (count forms) :attributes (count attributes)
   :native (count native) :input (:seon.bridge.parity/input-digest captured)
   :mismatches 0})
