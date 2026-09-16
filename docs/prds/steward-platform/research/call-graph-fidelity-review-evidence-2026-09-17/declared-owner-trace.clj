;; Evaluate this function against the database supplied inside the existing
;; declared-function-values-contribute-edges-without-arities regression,
;; after its reconcile transaction. It creates no fixture or runtime state.
(require 'seon.db 'seon.fn 'clojure.set)
(fn [database]
  (let [target "sample.call-declarations/handler"
        historical (seon.db/q
                    '[:find ?caller ?target :where
                      [?declaration :seon.fn/reference-to :seon.fn/sym]
                      [?declaration :seon.schema/key ?attribute]
                      [?holder ?attribute ?target]
                      [?target :seon.fn/sym]
                      [?caller :seon.fn/keywords ?attribute]] database)
        files (seon.db/q
               '[:find ?test ?target :where
                 [?file :seon.fn/unresolved-references ?symbol]
                 [?target :seon.fn/sym ?symbol]
                 [?test :seon.fn/file ?file]
                 [?test :seon.test/sym]] database)
        incoming (fn [edges]
                   (reduce (fn [m [caller target]]
                             (update m target (fnil conj #{}) caller)) {} edges))
        old-incoming (incoming historical)
        file-incoming (incoming files)
        before (#'seon.fn/gate-set-in database old-incoming file-incoming target)
        after (seon.fn/gate-set database target)
        names (into {} (seon.db/q '[:find ?e ?symbol :where
                                   (or [?e :seon.fn/sym ?symbol]
                                       [?e :seon.test/sym ?symbol])] database))
        tests (into {} (seon.db/q '[:find ?e ?symbol :where
                                   [?e :seon.test/sym ?symbol]] database))
        subjects (incoming (seon.db/q '[:find ?test ?target :where
                                       [?test :seon.test/subject ?target]] database))
        edges (concat
               (for [attribute [:seon.fn/calls :seon.fn/references]
                     datom (seon.db/datoms database :avet attribute)]
                 {:from (:e datom) :to (:v datom) :relation attribute})
               (for [[caller target] historical]
                 {:from caller :to target :relation :declared-reference})
               (for [[caller target] files]
                 {:from caller :to target :relation :file-unresolved-reference}))
        by-target (group-by :to edges)
        target-id (:db/id (seon.db/pull database [:db/id] [:seon.fn/sym target]))
        named-path (fn [path]
                     (mapv #(-> % (update :from names) (update :to names))
                           (reverse path)))
        paths (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [target-id []])
                     seen #{} paths {}]
                (if-let [[entity path] (peek queue)]
                  (if (seen entity)
                    (recur (pop queue) seen paths)
                    (let [paths (cond-> paths
                                  (tests entity) (assoc (tests entity) (named-path path)))
                          paths (reduce (fn [m test]
                                          (assoc m (tests test)
                                                 (named-path
                                                  (conj path {:from test :to entity
                                                              :relation :seon.test/subject}))))
                                        paths (get subjects entity))]
                      (recur (into (pop queue)
                                   (map (fn [edge] [(:from edge) (conj path edge)])
                                        (sort-by (juxt :relation :from) (get by-target entity))))
                             (conj seen entity) paths)))
                  paths))
        extras (clojure.set/difference (set before) (set after))
        extra-paths (into (sorted-map) (select-keys paths extras))
        path-edges (vec (sort-by pr-str (set (mapcat val extra-paths))))
        edge-indices (zipmap path-edges (range))]
    {:target target :before-count (count before) :after after
     :without-declared (#'seon.fn/gate-set-in database {} file-incoming target)
     :without-file (#'seon.fn/gate-set-in database old-incoming {} target)
     :unattributed (clojure.set/difference extras (set (keys paths)))
     :edges path-edges
     :extra-test-paths (update-vals extra-paths #(mapv edge-indices %))}))
