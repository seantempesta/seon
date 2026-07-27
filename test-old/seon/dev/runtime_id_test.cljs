(ns seon.dev.runtime-id-test
  "The pure MCP runtime-addressing contract shared by the pod and Babashka.

   Advertisements normalize database-derived agent ids. Resolution is
   `parse-id` + `select-runtime`: zero matches, one match, or an explicit
   ambiguity. There is no process-global agent membership state to isolate in
   tests."
  (:require
    [cljs.test :refer [deftest is testing]]
    [seon.dev.runtime-id :as runtime-id]))

(deftest database-derived-advertisement-normalization
  (is (= #:seon.dev.runtime-id
         {:cluster "default"
          :ids ["copper-lantern-falcon" "root"]}
         (runtime-id/advertisement
          #:seon.dev.runtime-id
          {:cluster "default"
           :ids ["root" "copper-lantern-falcon" "root"]}))))

(deftest dir->cluster-name-basename-rule
  (is (= "default" (runtime-id/dir->cluster-name "data/clusters/default")))
  (is (= "gsm1"    (runtime-id/dir->cluster-name "data/clusters/gsm1/")))
  (is (= "acme"    (runtime-id/dir->cluster-name "acme")))
  (is (= "default" (runtime-id/dir->cluster-name ""))))

(deftest parse-id-grammar
  (testing "bare agent id"
    (is (= #:seon.dev.runtime-id{:id "root"} (runtime-id/parse-id "root"))))
  (testing "cluster-qualified — splits on the FIRST slash"
    (is (= #:seon.dev.runtime-id{:cluster "default" :id "root"}
           (runtime-id/parse-id "default/root")))))

(deftest select-runtime-decision-rule
  ;; The C27 topology: several pods on ONE build, each hosting a "root".
  (let [default-pod #:seon.dev.runtime-id{:cluster "default"
                                          :ids ["iCg-2606101519" "root"]}
        bench-pod   #:seon.dev.runtime-id{:cluster "gsm1" :ids ["root"]}
        select (fn [agent-id cands]
                 (runtime-id/select-runtime
                   (assoc (runtime-id/parse-id agent-id)
                          :seon.dev.runtime-id/candidates (vec cands))))]
    (testing "unique bare id → :match (bare ids keep working when unambiguous)"
      (let [res (select "iCg-2606101519" [default-pod bench-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= default-pod (:seon.dev.runtime-id/runtime res)))))
    (testing "bare id hosted by several runtimes → :ambiguous, all candidates listed"
      (let [res (select "root" [default-pod bench-pod])]
        (is (= :ambiguous (:seon.dev.runtime-id/resolution res)))
        (is (= [default-pod bench-pod]
               (:seon.dev.runtime-id/runtimes res)))))
    (testing "cluster-qualified id → pins exactly the named cluster's runtime"
      (let [res (select "gsm1/root" [default-pod bench-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= bench-pod (:seon.dev.runtime-id/runtime res)))))
    (testing "unhosted id → :none"
      (is (= :none (:seon.dev.runtime-id/resolution
                     (select "zzz-2606101599" [default-pod bench-pod])))))
    (testing "single pod runtime → bare root still resolves (default stays trivially addressable)"
      (let [res (select "root" [default-pod])]
        (is (= :match (:seon.dev.runtime-id/resolution res)))
        (is (= default-pod (:seon.dev.runtime-id/runtime res)))))))
