(ns seon.agent.message.portable-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [seon.agent.message :as message]
            [seon.agent.message.leaf :as leaf]))

(deftest portable-message-contract
  (testing "agent-facing entries preserve effects and frozen arities"
    (is (= :idempotent (:seon.capability/effect (meta #'message/user))))
    (is (= :idempotent (:seon.capability/effect (meta #'message/agent))))
    (is (= '([content]) (:arglists (meta #'message/user))))
    (is (= '([to-id content]) (:arglists (meta #'message/agent)))))
  (testing "participant and hop policy is portable"
    (is (true? (message/waking-inbound?
                {:seon.agent.message/from {:db/id 2}} 1)))
    (is (false? (message/waking-inbound?
                 {:seon.agent.message/from {:db/id 1}} 1)))
    (binding [message/*leaf* {::leaf/hop-cap 3}]
      (is (true? (message/hop-live? {:seon.agent.message/hops 2})))
      (is (false? (message/hop-live? {:seon.agent.message/hops 3}))))))
