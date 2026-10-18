(ns my.program-mutation-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [my.program :as program]
            [seon.batch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.test-support :as support]
            [seon.turn.world :as world]
            [seon.program :as seon-program]))

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
           refused (program/unmap-subject! context 'seon.turn/open?)]
       (is (= 'seon.turn/open? (:seon.program/blocked-subject refused)))
       (is (seq (get-in refused [:seon.error/data :seon.program/callers])))
       (is (= (db/basis-t database) (db/basis-t (db/db connection))))
       (is (= before @(:env fork)))
       (is (= base-before @(:env ctx)))
       (let [decisions (support/effective-config)
             result (support/evaluate
                     {:seon.sci.eval/ctx fork :seon.db/db database
                      :seon.db/connection connection
                      :seon.cluster.eval/source "(my.program/ns-unmap! 'seon.turn/open?)"
                      :seon.sci.admit/caps (config/result-caps decisions)
                      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms decisions)})]
         (is (= 'seon.turn/open? (get-in result [:seon.sci.admit/value :seon.program/blocked-subject]))))
       (support/transacted! connection [(support/program-fn-entity (db/db connection) (symbol "my.program" "disposable") "(defn disposable [] 42)")])
       (doseq [target [ctx fork]]
         (sci/eval-string* target "(do (in-ns 'my.program) (defn disposable [] 42))"))
       ;; the unmap is an eval request's form: the request records the removal it observes
       (support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "program-mutation-fixture"
                                      :seon.sci.eval/ctx fork :seon.batch/sink (seon.batch/sink)
                                      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                      :seon.agent/id "program-mutation"})
                        'user "(my.program/ns-unmap! 'my.program/disposable)")
       (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym (symbol "my.program" "disposable")])))
       (is (nil? (sci/resolve fork (symbol "my.program" "disposable"))))
       (is (nil? (sci/resolve ctx (symbol "my.program" "disposable"))))))))

(deftest ns-unmap-takes-clojure-cores-shapes-and-takes-its-context-from-the-world
  (doseq [[form target] [["(my.program/ns-unmap! 'my.unmap.shape 'helper)" 'helper]
                         ["(my.program/ns-unmap! 'my.unmap.shape/helper)" 'helper]]]
    (support/with-database
     (fn [connection]
       (support/seed-cluster! connection "program-unmap-shape-fixture")
       (let [ctx (support/fork-cluster-ctx connection)
             fork (sci/fork ctx)
             sym (symbol "my.unmap.shape" (name target))]
         (support/transacted!
          connection
          [(seon-program/declaration-row
            (db/carried-projection @connection)
            {:seon.ns/name 'my.unmap.shape :seon.ns/source "(ns my.unmap.shape)"}
            :all :agent)])
         (support/transacted! connection [(support/program-fn-entity (db/db connection) sym "(defn helper [] 1)")])
         (doseq [target-ctx [ctx fork]]
           (sci/eval-string* target-ctx "(do (in-ns 'my.unmap.shape) (defn helper [] 1))"))
         (support/define! (world/world {:seon.db/connection connection :seon.boot/cluster-name "program-unmap-shape-fixture"
                                        :seon.sci.eval/ctx fork :seon.batch/sink (seon.batch/sink)
                                        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
                                        :seon.agent/id "program-unmap-shape"})
                          'user form)
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym sym])) form)
         (is (nil? (sci/resolve fork sym)) form))))))

(deftest namespace-and-alias-retraction-write-before-native-mutation
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-namespace-fixture")
     (support/transacted!
      connection
      [(seon-program/declaration-row
        (db/carried-projection @connection)
        {:seon.ns/name 'my.disposable.program :seon.ns/source "(ns my.disposable.program)"
         :seon.ns/aliases #{{:seon.ns.alias/local 'text
                             :seon.ns.alias/target-ns 'clojure.string}}}
        :all :agent)])
     ;; The entities digest with the namespace's stored aliases, as the writer does.
     (support/transacted!
      connection
      [(support/program-fn-entity (db/db connection) (symbol "my.disposable.program" "target") "(defn target [] 42)")
       (support/program-fn-entity (db/db connection) (symbol "my.disposable.program" "caller") "(defn caller [] (target))"
                               {:seon.fn/calls #{(symbol "my.disposable.program" "target")}})])
     (let [base (support/fork-cluster-ctx connection)
           fork (sci/fork base)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx base :seon.db/connection connection}]
       (doseq [ctx [base fork]]
         (sci/eval-string* ctx "(do (in-ns 'my.disposable.program)
                                     (alias 'text 'clojure.string)
                                     (defn target [] 42)
                                     (defn caller [] (target)))"))
       (let [result (program/ns-unalias! context 'my.disposable.program 'text)]
         (is (not (:seon.error/at result)))
         (is (empty? (db/q (db/db connection)
                          '[:find [?alias ...] :where
                            [?ns :seon.ns/name my.disposable.program]
                            [?ns :seon.ns/aliases ?alias]])))
         (doseq [ctx [base fork]]
           (is (nil? (get-in @(:env ctx) [:namespaces 'my.disposable.program :aliases 'text])))))
       (let [result (program/remove-ns! context 'my.disposable.program)]
         (is (not (:seon.error/at result)))
         (is (= #{'my.disposable.program (symbol "my.disposable.program" "target") (symbol "my.disposable.program" "caller")}
                (:seon.program/affected result)))
         (doseq [ctx [base fork]]
           (is (nil? (get-in @(:env ctx) [:namespaces 'my.disposable.program]))))
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.ns/name 'my.disposable.program])))
         (is (nil? (db/pull (db/db connection) [:db/id] [:seon.fn/sym (symbol "my.disposable.program" "target")]))))))))

(deftest ns-unmap-of-a-one-function-namespace-computes-the-deletion-report-once
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-unmap-once-fixture")
     (let [target (symbol "my.unmap.once" "lone")
           ctx (support/fork-cluster-ctx connection)
           fork (sci/fork ctx)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx ctx :seon.db/connection connection}]
       (support/transacted!
        connection
        [(seon-program/declaration-row
          (db/carried-projection @connection)
          {:seon.ns/name 'my.unmap.once :seon.ns/source "(ns my.unmap.once)"}
          :all :agent)])
       (support/transacted! connection [(support/program-fn-entity (db/db connection) target "(defn lone [] 1)")])
       (doseq [target-ctx [ctx fork]]
         (sci/eval-string* target-ctx "(do (in-ns 'my.unmap.once) (defn lone [] 1))"))
       (let [measured (profile/measure #(program/unmap-subject! context target))
             removed (:seon.profile/value measured)]
         (is (= #{target} (:seon.program/affected removed)))
         ;; One subject owns no other declaration: one referrers read.
         (is (= 1 (profile/calls-of measured 'my.program/referrers))
             "ns-unmap! of a one-function namespace reads the subject's referrers once"))))))

(deftest ns-unalias-of-an-empty-namespace-computes-the-deletion-report-once
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-unalias-once-fixture")
     (let [ctx (support/fork-cluster-ctx connection)
           fork (sci/fork ctx)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx ctx :seon.db/connection connection}]
       (support/transacted!
        connection
        [(seon-program/declaration-row
          (db/carried-projection @connection)
          {:seon.ns/name 'my.unalias.once :seon.ns/source "(ns my.unalias.once)"
           :seon.ns/aliases #{{:seon.ns.alias/local 'text :seon.ns.alias/target-ns 'clojure.string}}}
          :all :agent)])
       (doseq [target-ctx [ctx fork]]
         (sci/eval-string* target-ctx "(do (in-ns 'my.unalias.once) (alias 'text 'clojure.string))"))
       (let [measured (profile/measure #(program/ns-unalias! context 'my.unalias.once 'text))
             removed (:seon.profile/value measured)]
         (is (= #{'my.unalias.once} (:seon.program/affected removed)))
         ;; The namespace owns no declaration: one referrers read, in the writer.
         (is (= 1 (profile/calls-of measured 'my.program/referrers))
             "ns-unalias! of a namespace reads its referrers once"))))))

(deftest ns-unmap-of-one-definition-costs-its-referrers-not-the-program
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "program-unmap-cost-fixture")
     (let [target (symbol "my.unmap.cost" "lone")
           ctx (support/fork-cluster-ctx connection)
           fork (sci/fork ctx)
           context {:seon.sci.eval/ctx fork :my.program/base-ctx ctx :seon.db/connection connection}]
       (support/transacted!
        connection
        [(seon-program/declaration-row
          (db/carried-projection @connection)
          {:seon.ns/name 'my.unmap.cost :seon.ns/source "(ns my.unmap.cost)"}
          :all :agent)])
       (support/transacted! connection [(support/program-fn-entity (db/db connection) target "(defn lone [] 1)")])
       (doseq [target-ctx [ctx fork]]
         (sci/eval-string* target-ctx "(do (in-ns 'my.unmap.cost) (defn lone [] 1))"))
       ;; warm: the first call of the JVM pays class loading and query compilation
       (program/breaks {:seon.db/db (db/db connection) :seon.program/subject target})
       (let [measured (profile/measure #(program/unmap-subject! context target))]
         (is (= #{target} (:seon.program/affected (:seon.profile/value measured))))
         ;; one definition's facts and its referrers' indexed reads, never the program's gate walk
         (is (< (:seon.profile/alloc-bytes measured) (* 20 1024 1024))
             "unmapping a definition no one calls allocates under 20 MB")
         (is (zero? (profile/calls-of measured 'seon.fn/gating-tests))
             "a retraction reads the referrers that block it, not the tests that gate it"))))))
