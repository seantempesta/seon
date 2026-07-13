(ns seon.dev.runtime-id-test
  "The MCP runtime-addressing probe surface (mcp-agent-id-unification PRD
   2026-06-10; cluster-qualified per registry C27). Pins the contract
   `bin/mcp-server-cljs` resolves against:

     - `advertisement` is THE probe envelope: the cluster this process
       declared (`cluster!`) + the VECTOR of ids it hosts — core agent
       ids AND `proc:<name>` infra ids share ONE roster.
     - resolution is `parse-id` + `select-runtime` — the ns is CLJC so
       the bb resolver LOADS these exact fns; these tests ARE the
       resolver's unit tests (no mirrored logic). The rule: 0 matches →
       :none, 1 → :match, 2+ → :ambiguous — NEVER an arbitrary pick
       (every cluster hosts a \"root\").
     - host!/unhost!/cluster! are idempotent; the id populations are
       disjoint by construction (persistent id schemas reject `:`, cluster
       names reject `/`).

   Tests snapshot + restore the defonce'd roster + cluster so the LIVE
   pod's hosted set (the booted agents) is never disturbed."
  (:require
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [seon.db.id]
    [seon.dev.runtime-id :as runtime-id]))

(defn- with-clean-roster
  "Run `f` against an emptied roster, restoring the prior hosted set
   after — the pod under test may be LIVE and hosting real agents."
  [f]
  (let [before (runtime-id/hosted)]
    (try
      (doseq [id before] (runtime-id/unhost! id))
      (f)
      (finally
        (doseq [id (runtime-id/hosted)] (runtime-id/unhost! id))
        (doseq [id before] (runtime-id/host! id))))))

(deftest host-unhost-hosted-roundtrip
  (with-clean-roster
    (fn []
      (testing "empty roster answers []"
        (is (= [] (runtime-id/hosted))))
      (testing "host! registers; idempotent; sorted vector out"
        (runtime-id/host! "bbb-2606101200")
        (runtime-id/host! "aaa-2606101200")
        (runtime-id/host! "aaa-2606101200")
        (is (= ["aaa-2606101200" "bbb-2606101200"] (runtime-id/hosted))))
      (testing "unhost! removes; idempotent"
        (runtime-id/unhost! "aaa-2606101200")
        (runtime-id/unhost! "aaa-2606101200")
        (is (= ["bbb-2606101200"] (runtime-id/hosted))))
      (testing "blank ids are refused"
        (runtime-id/host! "")
        (is (= ["bbb-2606101200"] (runtime-id/hosted)))))))

(deftest membership-resolution-agent-and-proc-ids
  (with-clean-roster
    (fn []
      ;; The pod topology: one runtime hosting N agents; an infra runtime
      ;; hosting its proc:<name>. The bb resolver's match is
      ;; `(some #(= agent-id %) hosted)` — mirror it exactly.
      (runtime-id/host! "iCg-2606101519")
      (runtime-id/host! "Kpx-2606101522")
      (runtime-id/host! "proc:wire")
      (let [hosted (runtime-id/hosted)
            match? (fn [agent-id] (boolean (some #(= agent-id %) hosted)))]
        (testing "every hosted agent id resolves to this runtime"
          (is (match? "iCg-2606101519"))
          (is (match? "Kpx-2606101522")))
        (testing "proc:<name> infra ids resolve through the SAME roster"
          (is (match? "proc:wire")))
        (testing "unhosted ids do not match"
          (is (not (match? "zzz-2606101599")))
          (is (not (match? "proc:replica"))))))))

(deftest cluster-declaration-and-advertisement
  (with-clean-roster
    (fn []
      (let [before (:seon.dev.runtime-id/cluster (runtime-id/advertisement))]
        (try
          (testing "advertisement carries the hosted ids"
            (runtime-id/host! "aaa-2606101200")
            (is (= ["aaa-2606101200"]
                   (:seon.dev.runtime-id/ids (runtime-id/advertisement)))))
          (testing "cluster! declares; blank refused; advertisement carries it"
            (runtime-id/cluster! "c27test")
            (runtime-id/cluster! "")
            (is (= "c27test"
                   (:seon.dev.runtime-id/cluster (runtime-id/advertisement)))))
          (finally
            ;; restore the pod's real cluster (the live pod declared it at
            ;; boot; a bare test process defaults to "default" — harmless).
            (runtime-id/cluster! (or before "default"))))))))

(deftest dir->cluster-name-basename-rule
  (is (= "default" (runtime-id/dir->cluster-name "data/clusters/default")))
  (is (= "gsm1"    (runtime-id/dir->cluster-name "data/clusters/gsm1/")))
  (is (= "acme"    (runtime-id/dir->cluster-name "acme")))
  (is (= "default" (runtime-id/dir->cluster-name ""))))

(deftest parse-id-grammar
  (testing "bare ids — agent and proc"
    (is (= #:seon.dev.runtime-id{:id "root"} (runtime-id/parse-id "root")))
    (is (= #:seon.dev.runtime-id{:id "proc:wire"} (runtime-id/parse-id "proc:wire"))))
  (testing "cluster-qualified — splits on the FIRST slash"
    (is (= #:seon.dev.runtime-id{:cluster "default" :id "root"}
           (runtime-id/parse-id "default/root")))
    (is (= #:seon.dev.runtime-id{:cluster "acme" :id "proc:wire"}
           (runtime-id/parse-id "acme/proc:wire")))))

(deftest select-runtime-decision-rule
  ;; The C27 topology: several pods on ONE build, each hosting a "root".
  (let [default-pod #:seon.dev.runtime-id{:cluster "default"
                                          :ids ["iCg-2606101519" "root"]}
        bench-pod   #:seon.dev.runtime-id{:cluster "gsm1" :ids ["root"]}
        legacy-pod  #:seon.dev.runtime-id{:ids ["root"]} ; pre-C27 bundle
        select (fn [agent-id cands]
                 (runtime-id/select-runtime
                   (assoc (runtime-id/parse-id agent-id)
                          :seon.dev.runtime-id/candidates (vec cands))))]
    (testing "unique bare id → :match (bare ids keep working when unambiguous)"
      (let [res (select "iCg-2606101519" [default-pod bench-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= default-pod (:seon.dev.runtime-id/runtime res)))))
    (testing "bare id hosted by several runtimes → :ambiguous, all candidates listed"
      (let [res (select "root" [default-pod bench-pod legacy-pod])]
        (is (= :ambiguous (:seon.dev.runtime-id/resolution res)))
        (is (= [default-pod bench-pod legacy-pod]
               (:seon.dev.runtime-id/runtimes res)))))
    (testing "cluster-qualified id → pins exactly the named cluster's runtime"
      (let [res (select "gsm1/root" [default-pod bench-pod legacy-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= bench-pod (:seon.dev.runtime-id/runtime res)))))
    (testing "qualified id never matches a runtime with NO advertised cluster"
      (is (= :none (:seon.dev.runtime-id/resolution
                     (select "nope/root" [legacy-pod])))))
    (testing "unhosted id → :none"
      (is (= :none (:seon.dev.runtime-id/resolution
                     (select "zzz-2606101599" [default-pod bench-pod])))))
    (testing "single pod runtime → bare root still resolves (default stays trivially addressable)"
      (let [res (select "root" [default-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= default-pod (:seon.dev.runtime-id/runtime res)))))))

(deftest proc-grammar-disjoint-from-persistent-id-schemas
  (testing "every accepted persistent representation excludes the proc separator"
    (is (m/validate :seon.db.id/agent-value "lantern-copper-falcon"))
    (is (m/validate :seon.db.id/agent-value "root"))
    (is (m/validate :seon.db.id/compact-value "evalfixture1")))
  (testing "proc ids are rejected by both generated identity schemas"
    (is (not (m/validate :seon.db.id/agent-value "proc:wire")))
    (is (not (m/validate :seon.db.id/compact-value "proc:wire")))))
