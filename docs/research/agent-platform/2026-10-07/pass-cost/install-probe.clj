(do
  (require 'clojure.java.io 'clojure.walk 'clojure.core.cache.wrapped 'seon.sci.eval 'seon.schema 'seon.instrument 'seon.cluster.boot)
  (let [stage (System/getProperty "seon.pass-cost.stage" "parent")
        parent "/tmp/seon-pass-cost-session/pass-cost/root/data/source/5b0a3327ca7782279d11d714712f1b59727a756c"
        checkout "/Users/sean/src/seon"
        selections {'seon.sci.kernel '[cache-program! cache-function! public-functions-in]
                    'seon.config '[registration-defaults]
                    'seon.db '[query-patterns]
                    'seon.turn '[terminal-agent-ordinal evaluation-terminal-data settle-batch! settle! evaluation-entity-id]
                    'seon.turn.batch '[jvm-show jvm-request]
                    'seon.pass-probe '[written storage]}
        changed? (fn [space]
                   (or (= space 'seon.pass-probe) (= stage "lookup") (= stage "jvm")
                       (and (= stage "turn") (not= space 'seon.turn.batch))
                       (and (= stage "all") (not (#{'seon.turn 'seon.turn.batch} space)))
                       (and (#{"kernel" "config"} stage) (= space 'seon.sci.kernel))
                       (and (= stage "config") (= space 'seon.config))))]
    (doseq [[space names] selections]
      (let [path (case space seon.sci.kernel "src/seon/sci/kernel.clj"
                            seon.config "src/seon/config.clj" seon.db "src/seon/db.clj"
                            seon.turn "src/seon/turn.clj" seon.turn.batch "src/seon/turn/batch.clj"
                            seon.pass-probe "test/seon/pass_probe.clj")
            selected (set names)]
        (with-open [reader (java.io.PushbackReader.
                           (clojure.java.io/reader (clojure.java.io/file (if (changed? space) checkout parent) path)))]
          (binding [*ns* (find-ns space)]
            (when (= space 'seon.turn.batch)
              (require '[seon.config :as config] '[seon.render :as render]))
            (loop []
              (let [form (read {:eof ::end} reader)]
                (when-not (= ::end form)
                  (when (and (seq? form) (#{'defn 'defn-} (first form)) (selected (second form)))
                    (eval form))
                  (recur))))))))
    (let [symbols (vec (for [[space names] selections n names
                            :let [sym (symbol (str space) (name n))]
                            :when (find-var sym)] sym))
          projection (reduce
                      (fn [p sym]
                        (if-let [contract (:malli/schema (meta (find-var sym)))]
                          (seon.schema/projection-with-function-contract
                           p sym (clojure.walk/postwalk #(if (identical? % delay?) 'clojure.core/delay? %) contract) {}) p))
                      (seon.schema/projection-from-database @(seon.cluster.boot/connection "default")) symbols)
          armed (seon.instrument/apply!
                 {:seon.schema/projection projection
                  :seon.instrument/changed-identities (set (map #(vector :seon.fn/sym %) symbols))})
          cache @#'seon.sci.eval/base-context-cache]
      (doseq [k (keys @cache)] (clojure.core.cache.wrapped/evict cache k))
      armed)))
