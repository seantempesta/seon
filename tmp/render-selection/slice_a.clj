;; Slice A proof at default's JVM REPL, read-only against the live store:
;;   ((eval (read-string (slurp "/Users/sean/src/seon/tmp/render-selection/slice_a.clj"))) "<candidate root>")
;; The candidate's new definitions (bridge lift, writer check, convergence,
;; override rule) are evaluated into the scratch namespace
;; tmp.render-selection.slice-a, never into a program namespace. Writes are
;; speculative: seon.db/with (the ARMED installed writer) over default's head,
;; then the candidate writer check applied to the same reports.
(fn [root]
  (let [_ (remove-ns 'tmp.render-selection.slice-a)
        scratch (create-ns 'tmp.render-selection.slice-a)
        _ (binding [*ns* scratch]
            (refer-clojure)
            (doseq [[a n] '[[m malli.core] [mr malli.registry] [d datahike.api] [dbi datahike.db.interface]
                            [db seon.db] [schema seon.schema] [schema.datahike tmp.render-selection.slice-a]
                            [render.value seon.render.value] [internal seon.schema.internal]]]
              (alias a n)))
        read-defs (fn [file names]
                    (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (slurp (str root "/" file))))]
                      (loop [out {}]
                        (let [f (binding [*ns* scratch] (read {:eof ::eof :read-cond :allow} rdr))]
                          (if (= ::eof f) out
                              (recur (if (and (seq? f) (#{'defn 'defn-} (first f)) ((set names) (second f)))
                                       (assoc out (second f) f) out)))))))
        install (fn [file names]
                  (let [defs (read-defs file names)]
                    (doseq [n names] (binding [*ns* scratch] (eval (get defs n))))))
        ;; storable-properties-in calls storable-attribute-in? from its own ns.
        _ (binding [*ns* scratch] (refer 'seon.schema.datahike :only '[storable-attribute-in?]))
        _ (install "src/seon/schema/datahike.clj" '[chain-properties declared-reference storable-properties-in])
        _ (binding [*ns* scratch] (eval '(def diagnostic (deref (resolve 'seon.db/diagnostic)))))
        _ (install "src/seon/db.clj" '[render-face? render-declaration-touched? write-render-target-error])
        _ (install "src/seon/render.clj" '[explicit-value])
        v (fn [s] @(ns-resolve scratch s))
        ms (fn [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
        refused? (fn [x] (and (map? x) (contains? x :seon.error/message)))
        conn (seon.cluster.boot/connection "default")
        db @conn
        ;; The candidate's packaged population.
        dir (java.io.File. (str root "/resources/seon/schemas"))
        candidate-forms (seon.schema.edn/derive-config-forms
                         (reduce (fn [acc f] (merge acc (clojure.edn/read-string (slurp f)))) {}
                                 (sort-by str (filter #(.endsWith (.getName ^java.io.File %) ".edn") (file-seq dir)))))
        ;; The live store's own declarations with only the candidate's render
        ;; declarations swapped in, so rows and the writer's projection agree
        ;; as they do when one publication lands both.
        forms' (merge (:seon.schema.projection/forms (seon.schema/projection-from-database db))
                      (select-keys candidate-forms [:seon.render/ai :seon.render/html :seon.render/form
                                                    :seon.render/read-form :seon.render/rendered :seon.render/ai-data]))
        t0 (System/nanoTime)
        projection' (seon.schema/declaration-projection forms')
        projection-ms (ms t0)
        faces [:seon.render/ai :seon.render/html :seon.render/form]
        lift (v 'storable-properties-in)
        t1 (System/nanoTime)
        lifted (into {} (keep (fn [k] (let [p (select-keys (lift projection' k) faces)] (when (seq p) [k p]))))
                     (filter keyword? (keys forms')))
        lift-ms (ms t1)
        ;; Effective pairs agree with the projection's own lookup, per face.
        registry' (:seon.schema.projection/registry projection')
        own-lookup (fn [k face] (when-let [c (malli.registry/schema registry' k)]
                                  (let [p (get (or (seon.schema.internal/entity-properties c) (malli.core/properties c)) face)]
                                    (when (qualified-symbol? p) p))))
        disagree (for [k (filter keyword? (keys forms')) face faces
                       :let [stored (get-in lifted [k face :seon.fn/sym]) own (own-lookup k face)]
                       :when (not= stored own)]
                   {:k k :face face :effective stored :entity-properties own})
        ;; Step A: retype the three faces in place and write the declaration rows.
        decls (into {} (map (fn [a] [a (seon.schema.datahike/malli->datahike-attr-in projection' a)])) faces)
        decl-rows (seon.schema/canonical-schema-entities projection' (select-keys forms' (conj faces :seon.render/read-form)))
        change (seon.cluster/attribute-change-tx db faces decls)
        t2 (System/nanoTime)
        report-a (try (datahike.api/with db {:tx-data (into change decl-rows)}) (catch Throwable t {:seon.error/message (ex-message t)}))
        db-a (seon.db/with db {:tx-data (into change decl-rows)})
        step-a-ms (ms t2)
        check (v 'write-render-target-error)
        touched? (v 'render-declaration-touched?)
        ;; Step B: every row the candidate bridge lifts a face onto. A face
        ;; naming a function the LIVE program lacks (source newer than the
        ;; running archive) is set aside and counted: in a real publication its
        ;; function lands in the same transaction as the row.
        live? (fn [sym] (seq (datahike.api/datoms db :avet :seon.fn/sym sym)))
        absent (into (sorted-set) (for [[_ p] lifted [_ {sym :seon.fn/sym}] p :when (not (live? sym))] sym))
        installed-row? (fn [k] (seq (datahike.api/datoms db :avet :seon.schema/key k)))
        rows-b (into [] (keep (fn [[k p]] (let [p (into {} (filter (fn [[_ {sym :seon.fn/sym}]] (live? sym))) p)]
                                          (when (and (seq p) (installed-row? k)) (assoc p :seon.schema/key k)))))
                     lifted)
        t3 (System/nanoTime)
        db-b (when-not (refused? db-a) (seon.db/with db-a {:tx-data rows-b}))
        step-b-ms (ms t3)
        report-b (when-not (refused? db-a) (datahike.api/with db-a {:tx-data rows-b}))
        after (when (and db-b (not (refused? db-b))) db-b)
        t4 (System/nanoTime)
        check-b (when report-b (let [pb (seon.db/carried-projection (:db-after report-b))] (when (touched? pb report-b) (check pb report-b))))
        check-b-ms (ms t4)
        unresolved (when after (for [a faces d (datahike.api/datoms after :aevt a)
                                     :when (empty? (datahike.api/datoms after :eavt (:v d) :seon.program/definition-digest))] d))
        f (fn [s] (:e (first (datahike.api/datoms after :avet :seon.fn/sym s))))
        t5 (System/nanoTime)
        users (when after (count (datahike.api/datoms after :avet :seon.render/ai (f 'seon.error/render-ai))))
        users-ms (ms t5)
        ;; Removal: the swept refs are the report; the candidate check refuses by name.
        report-delete (when after (datahike.api/with after {:tx-data [[:db/retractEntity (f 'seon.error/render-ai)]]}))
        t6 (System/nanoTime)
        check-delete (when report-delete (check (seon.db/carried-projection (:db-after report-delete)) report-delete))
        check-delete-ms (ms t6)
        ;; A declaration naming no function: the stub has no definition digest.
        report-dangling (when after (datahike.api/with after {:tx-data [{:seon.schema/key :seon.error/base :seon.render/ai {:seon.fn/sym 'no.such/render-ai}}]}))
        check-dangling (when report-dangling (check (seon.db/carried-projection (:db-after report-dangling)) report-dangling))
        ;; An unrelated write touches no declaration.
        report-doc (when after (datahike.api/with after {:tx-data [[:db/add (f 'seon.id/id) :seon.fn/doc "probe"]]}))
        t7 (System/nanoTime)
        doc-touched? (when report-doc (touched? (seon.db/carried-projection (:db-after report-doc)) report-doc))
        doc-ms (ms t7)
        ;; HEAD's whole-population check, for the cost it replaces.
        t8 (System/nanoTime)
        _ (#'seon.db/write-render-target-error db)
        head-full-ms (ms t8)
        t9 (System/nanoTime)
        dc (when after (#'seon.fn/declared-callers identity after))
        dc-ms (ms t9)
        ;; Override rule over a pulled schema entity.
        explicit (v 'explicit-value)
        pulled (when after (seon.db/pull after '[*] [:seon.schema/key :seon.error/base]))]
    {:projection-ms projection-ms
     :lift {:schemas-with-a-face (count lifted) :ms lift-ms
            :by-face (frequencies (mapcat keys (vals lifted)))
            :disagreements-with-entity-properties (count disagree)
            :disagree-sample (take 6 disagree)}
     :step-a {:ms step-a-ms :refusal (when (refused? db-a) (:seon.error/message db-a))
              :candidate-check (when-not (:seon.error/message report-a) (let [pa (seon.db/carried-projection (:db-after report-a))] (when (touched? pa report-a) (check pa report-a))))}
     :functions-the-live-program-lacks absent
     :step-b {:rows (count rows-b) :ms step-b-ms :refusal (when (refused? db-b) (:seon.error/message db-b))
              :candidate-check check-b :candidate-check-ms check-b-ms}
     :installed (when after (update-vals (select-keys (:schema (seon.db/schema-database after)) faces) :db/valueType))
     :ref-datoms (when after (into {} (map (fn [a] [a (count (datahike.api/datoms after :aevt a))])) faces))
     :unresolved (count unresolved)
     :schemas-rendering-with-error-render-ai {:count users :ms users-ms}
     :delete-render-fn {:refused? (refused? check-delete) :declarations (count (get-in check-delete [:seon.error/data :seon.render/declarations])) :ms check-delete-ms
                        :names-base? (boolean (some #(= :seon.error/base (:seon.schema/key %)) (get-in check-delete [:seon.error/data :seon.render/declarations])))}
     :dangling {:refused? (refused? check-dangling) :declarations (get-in check-dangling [:seon.error/data :seon.render/declarations])}
     :doc-write {:touched? doc-touched? :ms doc-ms}
     :head-whole-population-check-ms head-full-ms
     :declared-callers {:targets (count dc) :ms dc-ms}
     :override {:pulled-ai (:seon.render/ai pulled)
                :head-rule (some-> (when (map? pulled) (find pulled :seon.render/ai)) val)
                :candidate-rule (explicit pulled :seon.render/ai)
                :symbol-override (explicit {:seon.render/ai 'x/y} :seon.render/ai)
                :data-override (explicit {:seon.render/html [:p "x"]} :seon.render/html)}}))
