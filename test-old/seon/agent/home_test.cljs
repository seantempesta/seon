(ns seon.agent.home-test
  "Behavioral coverage for the one agent home-namespace data owner."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [async deftest is]]
    [malli.core :as m]
    [seon.agent.home :as home]
    [seon.db :as db]))

(deftest home-namespace-is-a-deterministic-id-projection
  (is (= 'my.agent.lantern-copper-falcon
         (home/home-ns "lantern-copper-falcon")))
  (is (= (home/home-ns "lantern-copper-falcon")
         (home/home-ns "lantern-copper-falcon"))))

(deftest starting-namespace-prefers-the-database-ref
  (is (= 'my.tax
         (home/starting-ns
          "lantern-copper-falcon"
          {:seon.agent/namespace {:seon.ns/name 'my.tax}})))
  (is (= 'my.agent.lantern-copper-falcon
         (home/starting-ns "lantern-copper-falcon" nil))))

(deftest current-namespace-follows-the-newest-database-fact
  (let [agent {:seon.agent/namespace {:seon.ns/name 'my.assigned}}
        evaluated ['my.evaluated (js/Date. 1000) 50]
        assigned ['my.assigned 60]]
    (is (= 'my.assigned
           (home/current-ns "agent" agent evaluated assigned))
        "a later namespace assignment selects the next turn")
    (is (= 'my.evaluated
           (home/current-ns "agent" agent evaluated ['my.assigned 40]))
        "a later successful eval preserves normal namespace movement")
    (is (= 'my.assigned
           (home/current-ns "agent" agent nil assigned)))
    (is (= 'my.agent.agent
           (home/current-ns "agent" nil nil nil)))))

(deftest require-spec-contract-is-owned-and-structural
  (is (m/validate :seon.agent.home/require-specs
                  '[[seon.db :as db]
                    [seon.agent.lifecycle :refer [wait complete]]]))
  (is (not (m/validate :seon.agent.home/require-specs '[[seon.db]]))
      "a bare namespace is not a valid home require spec"))

(deftest home-ns-form-renders-the-supplied-requires
  (let [specs '[[seon.db :as db]
                [seon.agent.lifecycle :refer [wait]]]
        form  (reader/read-string (home/home-ns-form 'my.agent.probe specs))]
    (is (= 'ns (first form)))
    (is (= 'my.agent.probe (second form)))
    (is (= (cons :require specs) (nth form 2)))))

(defn- current-home-functions []
  [db/db db/installed-schema db/entity])

(defn- restore-home-functions!
  [[db-fn installed-schema-fn entity-fn]]
  (set! db/db db-fn)
  (set! db/installed-schema installed-schema-fn)
  (set! db/entity entity-fn))

(deftest home-requires-precedence-uses-one-database-value
  (async done
    (let [database {:db-name "home-test"
                    :t 7
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id (random-uuid)}
          calls (atom [])]
      (let [originals (current-home-functions)]
        (set! db/db
              (fn
                ([]
                 (swap! calls conj [:db])
                 (js/Promise.resolve database))
                ([_request]
                 (js/Promise.resolve database))))
        (set! db/installed-schema
              (fn
                ([] (js/Promise.resolve {}))
                ([received]
                 (swap! calls conj [:installed-schema received])
                 (js/Promise.resolve
                  {:seon.eval/home-requires {:db/valueType :db.type/string}}))))
        (set! db/entity
              (fn
                ([_request] (js/Promise.resolve nil))
                ([received ref]
                 (swap! calls conj [:entity received ref])
                 (js/Promise.resolve
                  {:seon.eval/home-requires
                   '[[seon.db :as persisted-db]]}))))
        (-> (home/home-requires-for database "probe")
            (.then
             (fn [requires]
               (is (= '[[seon.db :as persisted-db]] requires)
                   "persisted data retains precedence over configuration")
               (is (= [[:installed-schema database]
                       [:entity database [:seon.agent/id "probe"]]]
                      @calls)
                   "the supplied database value is reused without reacquiring head")))
            (.catch (fn [error]
                      (is false (str "home require acquisition rejected: " error))))
            (.finally (fn [] (restore-home-functions! originals)))
            (.then (fn [_] (done)))
            (.catch (fn [error]
                      (is false (str "home require cleanup rejected: " error))
                      (done))))))))

(deftest home-requires-falls-from-absent-persisted-data-to-canonical-data
  (async done
    (let [database {:db-name "home-test"
                    :t 7
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id (random-uuid)}]
      (let [originals (current-home-functions)]
        (set! db/db
              (fn
                ([] (js/Promise.resolve database))
                ([_request] (js/Promise.resolve database))))
        (set! db/installed-schema
              (fn
                ([] (js/Promise.resolve {}))
                ([_database] (js/Promise.resolve {}))))
        (-> (home/home-requires-for "probe")
            (.then
             (fn [requires]
               (is (= home/home-ns-require-specs requires)
                   "canonical data follows absent persisted requires")))
            (.catch (fn [error]
                      (is false (str "home require fallback rejected: " error))))
            (.finally (fn [] (restore-home-functions! originals)))
            (.then (fn [_] (done)))
            (.catch (fn [error]
                      (is false (str "home require cleanup rejected: " error))
                      (done))))))))

(deftest installed-error-attribute-is-schema-data-not-an-error-value
  (async done
    (let [database {:db-name "home-test"
                    :t 7
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id (random-uuid)}
          originals (current-home-functions)]
      (set! db/installed-schema
            (fn
              ([] (js/Promise.resolve {}))
              ([_database]
               (js/Promise.resolve
                {:seon.error/message
                 {:db/ident :seon.error/message
                  :db/valueType :db.type/string}}))))
      (-> (home/home-requires-for database "probe")
          (.then
           (fn [requires]
             (is (= home/home-ns-require-specs requires)
                 "an installed attribute named :seon.error/message is not an error")))
          (.catch (fn [error]
                    (is false (str "schema acquisition rejected: " error))))
          (.finally (fn [] (restore-home-functions! originals)))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "schema acquisition cleanup rejected: " error))
                    (done)))))))

(deftest home-requires-propagates-database-errors
  (async done
    (let [error {:seon.error/message "database unavailable"}
          originals (current-home-functions)]
      (set! db/db
            (fn
              ([] (js/Promise.resolve error))
              ([_request] (js/Promise.resolve error))))
      (-> (home/home-requires-for "probe")
          (.then (fn [result] (is (= error result))))
          (.catch (fn [failure]
                    (is false (str "home require error rejected: " failure))))
          (.finally (fn [] (restore-home-functions! originals)))
          (.then (fn [_] (done)))
          (.catch (fn [failure]
                    (is false (str "home require cleanup rejected: " failure))
                    (done)))))))
