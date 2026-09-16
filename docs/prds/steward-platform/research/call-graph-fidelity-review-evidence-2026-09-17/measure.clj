;; Run with load-file inside a future in default's existing JVM, never a new JVM.
;; The historical function is read from its commit for a policy comparison;
;; nothing replaces a production Var. Both policies receive the same value.
(require 'clojure.java.shell)
(let [database (seon.db/db (seon.operator/connection "default"))
      old-source (:out (clojure.java.shell/sh "git" "show" "b1508dc8a:src/seon/fn.clj"))
      old-gate
      (binding [*ns* (the-ns 'seon.fn)]
        (with-open [reader (java.io.PushbackReader. (java.io.StringReader. old-source))]
          (loop []
            (let [form (read {:eof nil} reader)]
              (cond
                (nil? form) (throw (ex-info "Historical gate-set not found" {}))
                (and (seq? form) (= 'gate-set (second form)))
                (eval (cons 'fn (drop-while #(not (vector? %)) (drop 2 form))))
                :else (recur))))))
      symbols ["seon.id/digest" "seon.print/emit" "seon.fs.jvm/read" "seon.db/q"
               "seon.fn/build-manifest" "seon.fn/source-rows"
               "seon.fn.analyzer/analyze" "seon.program/canonical-row"
               "seon.effect/request!" "seon.test.selection/reaching-tests"]
      measure
      (fn [db operation]
        (mapv (fn [symbol]
                (let [start (System/nanoTime) selected (operation db symbol)]
                  {:symbol symbol :count (count selected)
                   :digest (seon.id/digest 64 selected)
                   :ms (/ (- (System/nanoTime) start) 1e6)})) symbols))]
  (seon.schema/call-with-projection
   (seon.db/carried-projection database)
   (fn []
     (let [rows (#'seon.fn/desired-rows {:seon.fn/roots ["src" "test"]} (constantly nil))
           tx (seon.fn/reconcile-tx database rows [])
           ;; Project only graph and identity datoms from the owner's transaction.
           ;; This avoids claiming that unrelated schema changes have been adopted.
           attributes (into (set (seon.db/identity-attributes database))
                            [:seon.fn/calls :seon.fn/references :seon.fn/call-arities
                             :seon.fn/unresolved-references :seon.fn/file])
           graph-tx (into []
                          (keep (fn [operation]
                                  (if (map? operation)
                                    (let [row (select-keys operation (conj attributes :db/id))]
                                      (when (< 1 (count row)) row))
                                    (when (attributes (nth operation 2 nil)) operation)))) tx)
           projected (:db-after (datahike.api/with database graph-tx))]
       {:basis (seon.db/basis-t database)
        :held-before (measure database old-gate)
        :held-after (measure database seon.fn/gate-set)
        :projected-before (measure projected old-gate)
        :projected-after (measure projected seon.fn/gate-set)}))))
