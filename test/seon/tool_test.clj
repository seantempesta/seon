(ns seon.tool-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.code.parse :as parse]
            [seon.tool :as tool]))

(defn ^:seon/tool demo-pure-tool
  "Demo pure tool for metadata auto-discovery test."
  {:seon/tool 'demo-add-one
   :seon.tool/category :test
   :malli/schema [:=> [:cat :int] :int]
   :cost/ticks 10000}
  [n]
  (inc n))

(defn demo-ctx-tool
  "Demo context-injected tool."
  {:seon/tool 'demo-ctx-echo
   :seon.tool/ctx? true
   :seon.tool/category :test
   :malli/schema [:=> [:cat :any :keyword] :map]}
  [ctx k]
  {:agent (:agent-id ctx) :key k})

(deftest tool-discovery-parse-and-install-test
  (testing "seon.code.parse indexes :seon.code.var/tool? sparsely when :seon/tool is present"
    (let [p (parse/read-clj "src/seon/demo.clj"
                            "(ns seon.demo)\n(defn f {:seon/tool true :malli/schema [:=> [:cat :int] :int]} [x] x)\n(defn g [x] x)\n")
          by-n (into {} (map (juxt :seon.code.var/name identity)) (:vars p))]
      (is (true? (:seon.code.var/tool? (get by-n 'seon.demo/f))))
      (is (not (contains? (get by-n 'seon.demo/g) :seon.code.var/tool?)))))

  (testing "install-discovered-tools! auto-interns ^:seon/tool vars with live delegation & ctx injection"
    (let [tmp-ns (create-ns 'seon.tool-test-user-sandbox)
          installed (tool/install-discovered-tools! tmp-ns nil (fn [] {:agent-id :agent/sandbox}) nil)]
      (try
        (is (some #{'demo-add-one} installed))
        (is (some #{'demo-ctx-echo} installed))
        (let [v1 (ns-resolve tmp-ns 'demo-add-one)
              v2 (ns-resolve tmp-ns 'demo-ctx-echo)]
          (is (= 42 (@v1 41)))
          (is (= "Demo pure tool for metadata auto-discovery test." (:doc (meta v1))))
          (is (= :test (:seon.tool/category (meta v1))))
          (is (= {:agent :agent/sandbox :key :ok} (@v2 :ok))))
        (finally
          (remove-ns 'seon.tool-test-user-sandbox))))))
