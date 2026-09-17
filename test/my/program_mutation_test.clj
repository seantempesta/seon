(ns my.program-mutation-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [my.program :as program]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest retraction-refuses-before-context-mutation-and-removes-clean-declarations
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-mutation-fixture")
     (let [ctx (support/fork-cluster-ctx connection)
           fork (sci/fork ctx)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx ctx :seon.db/connection connection}
           database (db/db connection)
           before @(:env fork)
           base-before @(:env ctx)
           refused (program/ns-unmap! context 'seon.turn/open?)]
       (is (= :seon.program/declaration-refused (:seon.error/kind refused)))
       (is (= 5 (count (get-in refused [:seon.error/data :seon.program/callers]))))
       (is (= 5 (count (get-in refused [:seon.program/plan :seon.program/issues]))))
       (is (= (db/basis-t database) (db/basis-t (db/db connection))))
       (is (= before @(:env fork)))
       (is (= base-before @(:env ctx)))
       (let [decisions (support/effective-config)
             result (evaluation/evaluate
                     {:seon.sci.eval/ctx fork :seon.db/db database
                      :seon.db/connection connection
                      :seon.cluster.eval/source "(my.program/ns-unmap! 'seon.turn/open?)"
                      :seon.sci.admit/caps (config/result-caps decisions)
                      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)
                      :seon.config/on-core-error :panic})]
         (is (= :seon.program/declaration-refused
                (get-in result [:seon.sci.admit/value :seon.error/kind]))
             (pr-str (select-keys result [:seon.cluster.eval/error :seon.eval/shown]))))
       (support/transacted! connection [(assoc (support/program-fn-row 'my.program/disposable)
                                              :seon.fn/source "(defn disposable [] 42)")])
       (doseq [target [ctx fork]]
         (sci/eval-string* target "(do (in-ns 'my.program) (defn disposable [] 42))"))
       (let [removed (program/ns-unmap! context 'my.program/disposable)]
         (is (not (:seon.error/kind removed)) (pr-str removed))
         (is (= #{'my.program/disposable} (:seon.program/affected removed)))
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym "my.program/disposable"])))
         (is (nil? (sci/resolve fork 'my.program/disposable)))
         (is (nil? (sci/resolve ctx 'my.program/disposable))))))))

(deftest native-program-mutations-refuse-at-the-installed-hook
  ; Red until the exact sci/eval.clj hook hunk in the landing note lands.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-hook-fixture")
     (let [ctx (support/fork-cluster-ctx connection)
           decisions (support/effective-config)
           caps (config/result-caps decisions)]
       (doseq [[source expected]
               [["(remove-ns 'seon.turn)" 'my.program/remove-ns!]
                ["(ns-unalias 'seon.turn 'db)" 'my.program/ns-unalias!]
                ["(alter-var-root #'seon.turn/open? identity)" 'my.program/define!]
                ["(intern 'seon.turn 'open? (fn [_] false))" 'my.program/define!]]]
         (let [fork (sci/fork ctx)
               before @(:env fork)
               basis (db/basis-t (db/db connection))
               result (evaluation/evaluate
                       {:seon.sci.eval/ctx fork :seon.db/db (db/db connection)
                        :seon.db/connection connection :seon.cluster.eval/source source
                        :seon.sci.admit/caps caps
                        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)
                        :seon.config/on-core-error :panic})
               value (:seon.sci.admit/value result)]
           (is (= :seon.program/declaration-refused (:seon.error/kind value)) (pr-str result))
           (is (= expected (get-in value [:seon.error/data :seon.error/diagnostic-expected])))
           (is (= basis (db/basis-t (db/db connection))))
           (is (true? (= (get-in before [:namespaces 'seon.turn])
                         (get-in @(:env fork) [:namespaces 'seon.turn]))))))))))

(deftest namespace-and-alias-retraction-write-before-native-mutation
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-namespace-fixture")
     (support/transacted!
      connection
      [{:seon.ns/name 'my.disposable.program
        :seon.schema.admission/source :agent
        :seon.ns/aliases #{{:seon.ns.alias/local 'text
                            :seon.ns.alias/target-ns 'clojure.string}}}
       (support/program-fn-row 'my.disposable.program/target)
       (assoc (support/program-fn-row 'my.disposable.program/caller)
              :seon.fn/calls #{[:seon.fn/sym "my.disposable.program/target"]})])
     (let [base (support/fork-cluster-ctx connection)
           fork (sci/fork base)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx base :seon.db/connection connection}]
       (doseq [ctx [base fork]]
         (sci/eval-string* ctx "(do (in-ns 'my.disposable.program)
                                     (alias 'text 'clojure.string)
                                     (defn target [] 42)
                                     (defn caller [] (target)))"))
       (let [result (program/ns-unalias! context 'my.disposable.program 'text)]
         (is (not (:seon.error/kind result)) (pr-str result))
         (is (empty? (db/q (db/db connection)
                          '[:find [?alias ...] :where
                            [?ns :seon.ns/name my.disposable.program]
                            [?ns :seon.ns/aliases ?alias]])))
         (doseq [ctx [base fork]]
           (is (nil? (get-in @(:env ctx) [:namespaces 'my.disposable.program :aliases 'text])))))
       (let [result (program/remove-ns! context 'my.disposable.program)]
         (is (not (:seon.error/kind result)) (pr-str result))
         (is (= '#{my.disposable.program my.disposable.program/target my.disposable.program/caller}
                (:seon.program/affected result)))
         (doseq [ctx [base fork]]
           (is (nil? (get-in @(:env ctx) [:namespaces 'my.disposable.program]))))
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.ns/name 'my.disposable.program])))
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym "my.disposable.program/target"]))))))))
