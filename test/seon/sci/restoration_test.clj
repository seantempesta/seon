(ns seon.sci.restoration-test
  "Restoration of a refused program change covers the execution that actually
  changed: an accepted candidate's recipient, a Var redefined then removed,
  and a restoration that cannot run leaves the execution unavailable."
  {:seon/tasks #{"132fee0467c8"}}
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(defn- request
  [ctx connection source]
  {:seon.cluster.eval/source source
   :seon.cluster.eval/ns [:seon.ns/name 'scratch.restoration]
   :seon.sci.eval/ctx ctx
   :seon.db/db (db/db connection)
   :seon.db/connection connection
   :seon.agent/id "root"
   :seon.sci.admit/caps (config/result-caps config/defaults)
   :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
   :seon.config/on-core-error :panic})

(deftest a-refused-batch-restores-the-context-that-accepted-a-candidate
  ;; The ordinary turn path: the definition evaluates once in the retained
  ;; context, the gate accepts it (arming its contract) before settlement, and
  ;; a settlement refusal calls each evaluation's restore
  ;; (`turn/restore-evaluations!`).
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value #(:seon.sci.admit/value (sci.eval/evaluate (request ctx connection %)))
           contract "{:malli/schema [:=> [:cat :int] :int]}"]
       (value (str "(defn base-f \"D.\" " contract " [x] (inc x))"))
       (value (str "(defn caller \"D.\" " contract " [x] (base-f x))"))
       (let [held (get-in @(:env ctx) [:namespaces 'scratch.restoration 'base-f])
             snapshot @(:seon.sci.kernel/program-snapshot ctx)
             candidate (sci.eval/evaluate
                        (request ctx connection
                                 (str "(defn base-f \"D.\" " contract " [x] (* 100 x))")))
             accepted (sci.eval/accept-candidate!
                       {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                        :seon.sci.eval/evaluation candidate})]
         (is (= 100 (value "(caller 1)")) "the accepted candidate reached the retained context")
         (is (nil? ((:seon.sci.eval/restore accepted))))
         (is (= 2 (value "(caller 1)")) "an affected caller runs the prior definition again")
         (is (identical? held (get-in @(:env ctx) [:namespaces 'scratch.restoration 'base-f])))
         (is (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx))
             "the install's program snapshot is restored too")
         (is (= :input (:seon.instrument/check (value "(base-f \"a\")")))
             "the prior contract is still armed"))))))

(deftest acceptance-that-throws-restores-the-evaluating-context
  ;; Astra e19adf392 finding 4: the evaluation's own restoration, captured
  ;; before the form ran, applies when installation throws.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value #(:seon.sci.admit/value (sci.eval/evaluate (request ctx connection %)))
           contract "{:malli/schema [:=> [:cat :int] :int]}"]
       (value (str "(defn base-f \"D.\" " contract " [x] (inc x))"))
       (value (str "(defn caller \"D.\" " contract " [x] (base-f x))"))
       (let [held (get-in @(:env ctx) [:namespaces 'scratch.restoration 'base-f])
             snapshot @(:seon.sci.kernel/program-snapshot ctx)
             installed @(:seon.sci.kernel/installed-functions ctx)
             candidate (sci.eval/evaluate
                        (request ctx connection
                                 (str "(defn base-f \"D.\" " contract " [x] (* 100 x))")))
             ;; An unreadable contract throws inside installation, after the
             ;; form's root was interned.
             unreadable (assoc-in candidate [:seon.program/row :seon.fn/spec] "[:=> [:cat")
             thrown (try (sci.eval/accept-candidate!
                          {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                           :seon.sci.eval/evaluation unreadable})
                         nil
                         (catch Throwable failure failure))]
         (is (some? thrown) "the failed acceptance throws its cause")
         (is (identical? held (get-in @(:env ctx) [:namespaces 'scratch.restoration 'base-f])))
         (is (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx)))
         (is (= installed @(:seon.sci.kernel/installed-functions ctx)))
         (is (= 2 (value "(caller 1)")) "the affected caller runs the prior definition")
         (is (= :input (:seon.instrument/check (value "(base-f \"a\")")))))))))

(deftest a-var-redefined-then-removed-gets-its-captured-root-back
  (let [ctx (assoc (sci/fork (sci/init {}))
                   :seon.sci.kernel/program-snapshot (atom {}))
        turn-interns @#'sci.eval/turn-interns]
    (sci/eval-string* ctx "(defn g [x] (inc x))")
    (let [namespaces (:namespaces @(:env ctx))
          before (turn-interns ctx)]
      (sci/eval-string* ctx "(do (defn g [x] (* 3 x)) (ns-unmap 'user 'g))")
      (#'sci.eval/apply-restoration!
       ctx (#'sci.eval/program-restoration namespaces (:namespaces @(:env ctx))
                                           before (turn-interns ctx) (constantly true)))
      (is (= 2 (sci/eval-string* ctx "(g 1)"))))))

(deftest a-restoration-that-cannot-run-leaves-the-execution-unavailable
  (support/with-database
   (fn [connection]
     (let [acquired (db/db connection)
           delivered (atom [])
           ctx (assoc (sci/init {})
                      :seon.sci.kernel/program-snapshot
                      (atom {:seon.sci.eval/loaded-database acquired
                             :seon.flow/commit-fault!
                             (fn [observation]
                               (swap! delivered conj (:seon.error/declared-schema observation))
                               [observation :seon.flow/committed])}))
           restore #(#'sci.eval/restore-or-unavailable! ctx %1 %2 nil)]
       (is (nil? (restore acquired (constantly []))) "nothing to restore changes nothing")
       (is (nil? (restore nil (constantly []))))
       (is (empty? @delivered))
       (let [planning (restore acquired #(throw (ex-info "planning broke" {})))]
         (is (= :restore-failed (:seon.sci.eval/unavailable planning)))
         (is (seq (:seon.error/chain planning)) "the whole cause is kept"))
       (let [both (#'sci.eval/restore-or-unavailable!
                   ctx acquired #(throw (ex-info "planning broke" {}))
                   (ex-info "refused" {:seon.error/member :malli/schema}))]
         (is (= ["Restoring a refused program change threw." "refused"]
                (mapv :seon.error/message (:seon.error/chain both)))
             "the restoration's failure and the refusal it was restoring are one chain")
         (is (= ["planning broke"]
                (mapv :seon.error/message
                      (get-in both [:seon.error/chain 0 :seon.error/data :seon.error/chain])))))
       (is (= :program-reacquired
              (:seon.sci.eval/unavailable (restore nil (constantly [{:seon.sci.eval/path '[user x]}])))))
       (is (= :program-reacquired
              (get-in @(:env ctx) [:seon.sci.eval/unavailable-execution :seon.sci.eval/unavailable]))
           "the execution names its unavailability for every later form")
       (is (= (repeat 3 :seon.sci.eval/execution-unavailable-error)
              @delivered)
           "each fault is delivered through the context's recorder")))))
