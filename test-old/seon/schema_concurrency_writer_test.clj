(ns seon.schema-concurrency-writer-test
  "Concurrent schema registration and failed-eval isolation proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]))

(defn- await! [p]
  (let [value (deref p 5000 ::timed-out)]
    (when (= ::timed-out value)
      (throw (ex-info "Timed out waiting for schema concurrency proof." {})))
    value))

(defn- register-successfully! [k form]
  (let [delta (schema/begin-registration-delta)]
    (schema/call-with-registration-delta
      delta
      #(schema/register! k form))
    (schema/commit-registration-delta! delta)))

(deftest a-failed-eval-preserves-a-concurrent-disjoint-registration
  (let [state-before (schema/snapshot-state)
        key-a :schema.concurrent/a
        key-b :schema.concurrent/b
        a-registered (promise)
        restore-a (promise)]
    (try
      (let [failed-a
            (future
              (let [delta (schema/begin-registration-delta)]
                (schema/call-with-registration-delta
                  delta
                  #(do
                     (schema/register! key-a :string)
                     (deliver a-registered true)
                     (await! restore-a)))
                (schema/restore! delta)))]
        (await! a-registered)
        (register-successfully! key-b :int)
        (deliver restore-a true)
        (await! failed-a)
        (is (not (schema/registered? key-a)))
        (is (= :int (schema/schema-definition key-b))))
      (finally
        (deliver restore-a true)
        (schema/restore-state! state-before)))))

(deftest a-failed-eval-removes-new-keys-and-restores-redefinitions
  (let [state-before (schema/snapshot-state)
        new-key :schema.concurrent/new
        redefined-key :schema.concurrent/redefined]
    (try
      (schema/register! redefined-key :int)
      (let [delta (schema/begin-registration-delta)]
        (schema/call-with-registration-delta
          delta
          #(do
             (schema/register! new-key :string)
             (schema/register! redefined-key :string)))
        (schema/restore! delta))
      (is (not (schema/registered? new-key)))
      (is (= :int (schema/schema-definition redefined-key)))
      (finally
        (schema/restore-state! state-before)))))

(deftest a-failed-eval-cannot-overwrite-a-same-key-success
  (let [state-before (schema/snapshot-state)
        k :schema.concurrent/shared
        a-registered (promise)
        restore-a (promise)
        successful-form [:string {:min 2}]]
    (try
      (schema/register! k :int)
      (let [failed-a
            (future
              (let [delta (schema/begin-registration-delta)]
                (schema/call-with-registration-delta
                  delta
                  #(do
                     (schema/register! k :string)
                     (deliver a-registered true)
                     (await! restore-a)))
                (schema/restore! delta)))]
        (await! a-registered)
        (register-successfully! k successful-form)
        (deliver restore-a true)
        (await! failed-a)
        (is (= successful-form (schema/schema-definition k))))
      (finally
        (deliver restore-a true)
        (schema/restore-state! state-before)))))

(deftest disjoint-registration-bodies-make-progress-concurrently
  (let [state-before (schema/snapshot-state)
        entered-a (promise)
        entered-b (promise)
        release (promise)
        run-registration
        (fn [k entered]
          (future
            (let [delta (schema/begin-registration-delta)]
              (schema/call-with-registration-delta
                delta
                #(do
                   (schema/register! k :string)
                   (deliver entered true)
                   (await! release)))
              (schema/commit-registration-delta! delta))))]
    (try
      (let [registration-a
            (run-registration :schema.concurrent/progress-a entered-a)
            registration-b
            (run-registration :schema.concurrent/progress-b entered-b)]
        (await! entered-a)
        (await! entered-b)
        (deliver release true)
        (testing "both isolated eval bodies complete without a global lock"
          (is (= #{:schema.concurrent/progress-a}
                 (await! registration-a)))
          (is (= #{:schema.concurrent/progress-b}
                 (await! registration-b))))
        (is (schema/registered? :schema.concurrent/progress-a))
        (is (schema/registered? :schema.concurrent/progress-b)))
      (finally
        (deliver release true)
        (schema/restore-state! state-before)))))
