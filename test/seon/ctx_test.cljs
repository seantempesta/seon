(ns seon.ctx-test
  "Pure context formatting after coordinate-pinned acquisition."
  (:require
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]))

(deftest selected-blocks-are-ordinary-data
  (let [entity {:seon.agent/ctx
                [{:seon.agent.ctx/name :late
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "late"}
                 {:seon.agent.ctx/name :early
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "early"}]}
        blocks (ctx/selected-agent-blocks entity nil)]
    (is (= [:early :late] (mapv :seon.agent.ctx/name blocks)))
    (is (every? #(not (contains? % :seon.db/db)) blocks))))

(deftest acquired-context-formats-without-a-database
  (let [entity {:seon.agent.ctx/cache-breakpoint 10
                :seon.agent/ctx
                [{:seon.agent.ctx/name :stable
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "stable"}
                 {:seon.agent.ctx/name :volatile
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "volatile"}]}
        rendered (ctx/rendered-context-from-entity
                   {:seon.agent/entity entity})
        text (:seon.render/text rendered)]
    (is (str/includes? text "stable"))
    (is (str/includes? text ctx/stable-boundary))
    (is (str/includes? text "volatile"))
    (is (= [:stable :volatile]
           (mapv :seon.agent.ctx/name
                 (:seon.agent.ctx/rendered-blocks rendered))))))

(deftest profile-selects-and-overrides-stored-blocks
  (let [entity {:seon.agent/ctx
                [{:seon.agent.ctx/name :one
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "stored"}
                 {:seon.agent.ctx/name :two
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "two"}]}
        profile [{:seon.agent.ctx/name :one
                  :seon.agent.ctx/priority 5
                  :seon.render/ai "profile"}]
        rendered (ctx/rendered-context-from-entity
                   {:seon.agent/entity entity
                    :seon.agent.ctx/profile profile})]
    (is (str/includes? (:seon.render/text rendered) "profile"))
    (is (not (str/includes? (:seon.render/text rendered) "two")))
    (is (not (str/includes? (:seon.render/text rendered)
                            ctx/stable-boundary)))))

(deftest split-context-without-boundary-is-all-volatile
  (is (= {:seon.render/stable-text ""
          :seon.render/volatile-text "hello"}
         (ctx/split-context "hello"))))

(deftest chain-keys-diverge-after-first-change
  (let [a [{:seon.render/text "a"} {:seon.render/text "b"}]
        b [{:seon.render/text "a"} {:seon.render/text "c"}]
        ka (:seon.agent.ctx/chain-hashes
             (ctx/block-chain-keys {:seon.agent.ctx/blocks a
                                    :seon.agent/id "agent"}))
        kb (:seon.agent.ctx/chain-hashes
             (ctx/block-chain-keys {:seon.agent.ctx/blocks b
                                    :seon.agent/id "agent"}))]
    (is (= (first ka) (first kb)))
    (is (not= (second ka) (second kb)))))

(deftest system-text-has-no-local-database-injection
  (is (not (str/includes? ctx/system-text "db/*conn*")))
  (is (not (str/includes? ctx/system-text ":seon.db/db"))))
