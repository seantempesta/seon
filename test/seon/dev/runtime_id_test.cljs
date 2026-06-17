(ns seon.dev.runtime-id-test
  "The MCP runtime-addressing probe surface (mcp-agent-id-unification PRD
   2026-06-10). Pins the contract `bin/mcp-server-cljs` resolves against:

     - `hosted` answers the VECTOR of ids this process hosts — core
       agent ids AND `proc:<name>` infra ids share ONE roster.
     - resolution is MEMBERSHIP: `(some #{agent-id} (hosted))` — pinned
       here because the bb-side resolver can't be unit-tested from CLJS
       (proof 4 of P3.5/#31 unit-tests the membership logic instead of
       requiring a live :wire-node watcher).
     - host!/unhost! are idempotent; the two id populations are disjoint
       by construction (`seon.db/new-id!` never emits `:`).

   Tests snapshot + restore the defonce'd roster so the LIVE pod's
   hosted set (the booted agents) is never disturbed."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [seon.db :as db]
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

(deftest proc-grammar-disjoint-from-minted-ids
  ;; The `proc:` prefix is impossible to mint — `seon.db/new-id!` never
  ;; emits `:` — so the two id populations can never collide.
  (dotimes [_ 20]
    (let [id (db/new-id!)]
      (is (not (str/includes? id ":"))
          "new-id! never emits ':' — proc:<name> is disjoint by construction"))))
