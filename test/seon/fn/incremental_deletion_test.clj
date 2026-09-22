(ns seon.fn.incremental-deletion-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.test-support :as support]))

;; An invoker's declared targets come from the declaration population, not
;; its own file: `<prefix>.caller/run` invokes `:sample.invoke/handler`, and a
;; declaration names its target under that attribute. The caller's file stays
;; byte-identical throughout, so an incremental publication selects only
;; `callee.clj`; every edge change on the caller must come from its owner.
;;
;; Each run names its own namespaces: this file is itself program source, so a
;; literal sample symbol here would be a stored reference in the published
;; fixture database and the deletion guard would rightly refuse its removal.
(defn- sources
  "Sample sources under `prefix`, keyed by file name."
  [prefix]
  {:callee (str "(ns " prefix ".callee)\n(defn produce [] 1)\n(defn kept [] 2)\n")
   :callee-without-produce (str "(ns " prefix ".callee)\n(defn kept [] 2)\n")
   :callee-with-replacement (str "(ns " prefix ".callee)\n(defn replacement [] 3)\n(defn kept [] 2)\n")
   :callee-kept-edited (str "(ns " prefix ".callee)\n(defn produce [] 1)\n(defn kept [] 5)\n")
   :caller (str "(ns " prefix ".caller)\n(defn run {:seon.fn/invokes #{:sample.invoke/handler}} [] nil)\n")
   :literal (str "(ns " prefix ".literal (:require [" prefix ".callee]))\n(defn run [] (" prefix ".callee/produce))\n")
   :other (str "(ns " prefix ".other)\n(defn stays [] 4)\n")})

(defn- declaring
  "`projection` whose population names `targets` under the invoked attribute."
  [projection targets]
  (assoc-in projection [:seon.schema.projection/forms :sample.invoke/declaration]
            (into [:and] (map (fn [target] [:map {:sample.invoke/handler target}])) targets)))

(defn- calls-of
  [database sym]
  ;; A pulled cardinality-many value is a vector; `contains?` needs the set.
  (set (:seon.fn/calls (db/pull database [:seon.fn/calls] [:seon.fn/sym sym]))))

(defn- defined?
  [database sym]
  (some? (:db/id (db/pull database [:db/id] [:seon.fn/sym sym]))))

(defn- republish!
  "Publish `files` declaring `before`; rewrite callee.clj, republish it declaring `after`.

  Symbols in `before`/`after` and file names are relative to `prefix`.
  `extra-rows`, a function of the published database, appends rows to the
  incremental population's supplied rows."
  [connection root prefix {:keys [files before after callee extra-rows]}]
  (let [request {:seon.fn/root (.getCanonicalPath root) :seon.fn/roots ["src"]}
        projection (db/carried-projection (db/db connection))
        directory (str "src/" prefix)
        qualify (fn [names] (mapv #(symbol (str prefix ".callee") %) names))]
    (doseq [[file source] files]
      (spit (doto (io/file root directory file) io/make-parents) source))
    (support/transacted! connection
                         (seon.fn/analyze-rows
                          (assoc request :seon.schema/projection
                                 (declaring projection (qualify before)))))
    (let [published (db/db connection)
          _ (spit (io/file root directory "callee.clj") callee)
          selected (assoc request :seon.source/previous-database published
                                  :seon.schema/projection (declaring projection (qualify after))
                                  :seon.fn/changed-paths #{(str directory "/callee.clj")})
          result (support/refusal-data
                  #(let [analyzed (into (seon.fn/analyze-rows selected)
                                        (when extra-rows (extra-rows published directory)))]
                     (schema/call-with-projection
                      projection
                      (fn [] (seon.fn/index! (assoc selected :seon.db/connection connection
                                                            :seon.program/rows analyzed))))))]
      {:published published :result result :after (db/db connection)})))

(defn- in-sample
  "Call `f` with a fixture connection, a scratch root and a fresh namespace prefix."
  [f]
  (let [prefix (str "s" (id/id))
        root (io/file "tmp" (str "incremental-deletion-" prefix))]
    (try
      (support/with-database
       (fn [connection]
         (f connection root prefix (sources prefix) #(symbol (str prefix "." %1) %2))))
      (finally (support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Two sample-root analyses plus one incremental reconciliation on a fixture branch, as in selected-rows-reconcile-without-a-manifest."
           :seon.test/long-ms 15000}
  a-deletion-recomputes-its-unchanged-callers-edges
  (testing "a caller whose edge derived from the removed declaration commits without it"
    (in-sample
     (fn [connection root prefix source sym]
       (let [{:keys [published result after]}
             (republish! connection root prefix
                         {:files {"callee.clj" (:callee source) "caller.clj" (:caller source)}
                          :before ["produce"] :after []
                          :callee (:callee-without-produce source)})]
         (is (contains? (calls-of published (sym "caller" "run")) (sym "callee" "produce"))
             "The declared invocation is a stored edge of the unchanged caller.")
         (is (= support/committed result) (pr-str result))
         (is (not (defined? after (sym "callee" "produce"))))
         (is (not (contains? (calls-of after (sym "caller" "run")) (sym "callee" "produce")))
             "The caller's edge names no deleted identity.")))))
  (testing "a caller whose source still calls the removed definition refuses, naming it"
    (in-sample
     (fn [connection root prefix source sym]
       (let [{:keys [result after]}
             (republish! connection root prefix
                         {:files {"callee.clj" (:callee source) "caller.clj" (:caller source)
                                  "literal.clj" (:literal source)}
                          :before ["produce"] :after []
                          :callee (:callee-without-produce source)})]
         (is (not= support/committed result))
         (is (str/includes? (pr-str result) (str (sym "callee" "produce"))) (pr-str result))
         (is (defined? after (sym "callee" "produce"))
             "The refused population leaves the published program in place."))))))

(deftest ^{:seon.test/long "Two sample-root analyses plus one incremental reconciliation on a fixture branch."
           :seon.test/long-ms 15000}
  a-replacement-under-a-declared-invocation-keeps-its-new-edge
  (in-sample
   (fn [connection root prefix source sym]
     (let [{:keys [result after]}
           (republish! connection root prefix
                       {:files {"callee.clj" (:callee source) "caller.clj" (:caller source)}
                        :before ["produce"] :after ["replacement"]
                        :callee (:callee-with-replacement source)})]
       (is (= support/committed result) (pr-str result))
       (is (= #{(sym "callee" "replacement")} (calls-of after (sym "caller" "run")))
           "The re-analyzed caller knows the definition its own population introduced.")))))

(deftest ^{:seon.test/long "Two sample-root analyses plus one incremental reconciliation on a fixture branch."
           :seon.test/long-ms 15000}
  a-declaration-adding-a-target-reanalyzes-its-invokers
  (in-sample
   (fn [connection root prefix source sym]
     (let [{:keys [published result after]}
           (republish! connection root prefix
                       {:files {"callee.clj" (:callee source) "caller.clj" (:caller source)}
                        :before ["produce"] :after ["produce" "kept"]
                        :callee (:callee source)})]
       (is (= #{(sym "callee" "produce")} (calls-of published (sym "caller" "run"))))
       (is (= support/committed result) (pr-str result))
       (is (= #{(sym "callee" "produce") (sym "callee" "kept")} (calls-of after (sym "caller" "run")))
           "Selection reaches the invoker through its new declared edge.")))))

(deftest ^{:seon.test/long "Two sample-root analyses plus one incremental reconciliation on a fixture branch."
           :seon.test/long-ms 15000}
  a-supplied-file-row-never-widens-the-reconciliation
  (in-sample
   (fn [connection root prefix source sym]
     (let [{:keys [published result after]}
           (republish! connection root prefix
                       {:files {"callee.clj" (:callee source) "caller.clj" (:caller source)
                                "other.clj" (:other source)}
                        :before ["produce"] :after ["produce"]
                        :callee (:callee-kept-edited source)
                        ;; other.clj's complete stored file row, and nothing
                        ;; of its declarations.
                        :extra-rows (fn [database directory]
                                      [(dissoc (db/pull database '[*]
                                                        [:seon.fn.file/relative-path
                                                         (str directory "/other.clj")])
                                               :db/id)])})]
       (is (defined? published (sym "other" "stays")))
       (is (= support/committed result) (pr-str result))
       (is (defined? after (sym "other" "stays"))
           "A file row alone is no analysis of the file's declarations.")))))

(deftest source-rows-keep-declared-targets-outside-the-submitted-form
  (support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           namespace-name (symbol (str "s" (id/id) ".submitted"))
           namespace-row (program/declaration-row
                          projection
                          {:seon.ns/name namespace-name
                           :seon.ns/source (str "(ns " namespace-name ")")} :all :agent)
           _ (support/transacted! connection [namespace-row])
           database (db/db connection)
           producer (first (sort (keep (fn [[_ form]]
                                         (let [target (when (vector? form)
                                                        (get (second form) :seon.render/ai))]
                                           (when (and (qualified-symbol? target)
                                                      (defined? database target))
                                             target)))
                                       (:seon.schema.projection/forms projection))))
           row (some #(when (= (symbol (str namespace-name) "run") (:seon.fn/sym %)) %)
                     (seon.fn/source-rows
                      database (program/shapes-in projection) namespace-row
                      "(defn run {:seon.fn/invokes #{:seon.render/ai}} [] nil)"
                      (set (keys (:seon.schema.projection/forms projection)))))]
       (is (qualified-symbol? producer) "the fixture program declares a render producer")
       (is (contains? (set (:seon.fn/calls row)) producer)
           "A submitted invoker reaches the population's producers, not only its own batch's.")))))
