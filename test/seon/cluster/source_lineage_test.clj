(ns seon.cluster.source-lineage-test
  "Publication preserves history and the expected branch head. Bulk tier: every
  test reaches the destructive published-root fixture, so it is not admissible
  in the platform tier (runner guard, 2026-09-21)."
 (:require
  [clojure.test :refer [deftest is testing]]
  [datahike.api :as d]
  [seon.cluster.registry :as registry]
  [seon.cluster.source :as source]
  [seon.cluster.store :as store]
  [seon.db :as db]
  [seon.fs :as fs]
  [seon.test.cache :as cache]
  [seon.cluster.source-test :as source-test]
  [seon.test-support :as test-support])
 ^{:line 17, :column 17}
 (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest
 ^#:seon.test{:fixture-observation "A database branch cannot isolate three physical-store publication heads and scratch-branch retirement."
              :long "Three marker publications and branch-history assertions on a private canonical store.", :long-ms 10000} publication-advances-one-branch-and-retires-scratch
 ((deref #'source-test/with-store)
  (fn
   [opened]
   (let
    [a
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-a)
      'seon.cluster.source-test/populate-from-data!
      #:seon.source.test{:marker "from-populate-request"})
     b
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-b))]
    (is
     (= :current-src (:seon.source/branch a) (:seon.source/branch b)))
    (is (= (deref #'source-test/digest-a) (:seon.source/digest a)))
    (is (= (deref #'source-test/digest-b) (:seon.source/digest b)))
    (is
     (=
      (cache/test-input-digest
       (cache/input-digests (fs/source-directory)))
      (:seon.source/test-input-digest
       (db/pull
        (source/database opened (:seon.source/commit-id b))
        [:seon.source/test-input-digest]
        [:seon.source/digest (deref #'source-test/digest-b)])))
     "Publication carries its observed external-input identity.")
    (is (not= (:seon.source/commit-id a) (:seon.source/commit-id b)))
    (is
     (=
      #{(:seon.source/commit-id a)}
      (d/parent-commit-ids
       (d/branch-as-db
        (:seon.store/connection-object opened)
        source/current-branch)))
     "published history follows prior current-src, not scratch")
    (is (= #{:db :current-src} (registry/roster opened)))
    (is (empty? ((deref #'source-test/scratch-branches) opened)))
    (is
     (=
      #:seon.source{:branch :current-src,
                    :commit-id (:seon.source/commit-id b)}
      (source/current opened)))
    (testing
     "a complete publication never trusts digest equality alone"
     (let
      [connection (store/open-branch! opened source/current-branch)]
      (try
       (test-support/transacted!
        connection
        [[:db/retract
          [:seon.source.test/marker (deref #'source-test/digest-b)]
          :seon.source.test/marker
          (deref #'source-test/digest-b)]
         #:seon.source.test{:marker "stale-row"}])
       (finally (d/release connection))))
     (let
      [again
       ((deref #'source-test/publish)
        opened
        (deref #'source-test/digest-b))
       current-db
       (d/branch-as-db
        (:seon.store/connection-object opened)
        source/current-branch)]
      (is (true? (:seon.source/built? again)))
      (is
       (= (deref #'source-test/digest-b) (:seon.source/digest again)))
      (is
       (not=
        (:seon.source/commit-id b)
        (:seon.source/commit-id again)))
      (is
       (=
        #{(deref #'source-test/digest-b)}
        (set
         (db/q
          '[:find
            [?marker ...]
            :where
            [_ :seon.source.test/marker ?marker]]
          current-db)))
       "complete population repairs stale rows under an equal digest")))))))

(deftest
 ^#:seon.test{:fixture-observation "A database branch cannot isolate the physical-store head against an upsert based on an older publication."}
 stale-incremental-upsert-preserves-the-newer-publication
 ((deref #'source-test/with-store)
  (fn
   [opened]
   (let
    [a
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-a))
     b
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-b))
     data
     ((deref #'source-test/refusal)
      (fn*
       []
       ((deref #'source-test/upsert)
        opened
        (:seon.source/commit-id a)
        (deref #'source-test/digest-c)
        [#:seon.source.test{:marker "stale"}])))]
    (is (= :stale-branch-head (:type data)))
    (is
     (=
      (:seon.source/commit-id b)
      (:seon.source/commit-id (source/current opened))))
    (let
     [connection (store/open-branch! opened source/current-branch)]
     (try
      (is
       (=
        #{(deref #'source-test/digest-b)}
        ((deref #'source-test/source-digests) connection)))
      (is
       (=
        #{(deref #'source-test/digest-b)}
        ((deref #'source-test/markers) connection)))
      (finally (d/release connection))))
    (is (empty? ((deref #'source-test/scratch-branches) opened)))))))

(deftest
 ^#:seon.test{:fixture-observation "A database branch cannot isolate competing physical-store publications held across a latch."
              :long "Three marker publications, one held on an explicit latch, exercise the stale branch-head decision.", :long-ms 10000} failed-and-stale-builds-preserve-the-published-head
 ((deref #'source-test/with-store)
  (fn
   [opened]
   (let
    [a
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-a))
     commit-a
     (:seon.source/commit-id a)]
    (is
     (=
      #:seon.cluster.source-test{:injected true}
      ((deref #'source-test/refusal)
       (fn*
        []
        ((deref #'source-test/publish)
         opened
         (deref #'source-test/digest-b)
         'seon.cluster.source-test/populate-fails!)))))
    (is (= commit-a (:seon.source/commit-id (source/current opened))))
    (is (empty? ((deref #'source-test/scratch-branches) opened)))
    (let
     [entered (CountDownLatch. 1) release (CountDownLatch. 1)]
     (reset! (deref #'source-test/blocked-entered) entered)
     (reset! (deref #'source-test/blocked-release) release)
     (let
      [stale
       (future
        ((deref #'source-test/refusal)
         (fn*
          []
          ((deref #'source-test/publish)
           opened
           (deref #'source-test/digest-b)
           'seon.cluster.source-test/populate-blocked!))))]
      (is
       (true?
        (test-support/await-event!
         entered
         "blocked source population entered")))
      (let
       [c
        (try
         ((deref #'source-test/publish)
          opened
          (deref #'source-test/digest-c))
         (finally (.countDown release)))
        stale-result
        (deref
         stale
         (.toMillis
          TimeUnit/SECONDS
          (* 10 test-support/event-backstop-seconds))
         :seon.cluster.source-test/stale-publication-timeout)]
       (when
        (= :seon.cluster.source-test/stale-publication-timeout stale-result)
        (future-cancel stale)
        (throw
         (ex-info
          "The stale source publication did not complete."
          #:seon.test-support{:event "stale source publication"})))
       (is
        (= :stale-branch-head (:type stale-result))
        (pr-str stale-result))
       (is
        (=
         (:seon.source/commit-id c)
         (:seon.source/commit-id (source/current opened))))
       (is
        (empty? ((deref #'source-test/scratch-branches) opened))))))))))

(deftest
 ^#:seon.test{:fixture-observation "A database branch cannot isolate two cluster branch heads pinned across a second physical-store publication."}
 existing-clusters-remain-on-their-chosen-source-commit
 ((deref #'source-test/with-store)
  (fn
   [opened]
   (let
    [a
     ((deref #'source-test/publish)
      opened
      (deref #'source-test/digest-a))]
    (registry/ensure-cluster!
     {:seon.store/store opened,
      :seon.boot/cluster-name "a",
      :seon.source/commit-id (:seon.source/commit-id a)})
    (let
     [b
      ((deref #'source-test/publish)
       opened
       (deref #'source-test/digest-b))]
     (registry/ensure-cluster!
      {:seon.store/store opened,
       :seon.boot/cluster-name "b",
       :seon.source/commit-id (:seon.source/commit-id b)})
     (doseq
      [[cluster expected]
       [["a" #{(deref #'source-test/digest-a)}]
        ["b" #{(deref #'source-test/digest-b)}]]]
      (let
       [connection
        (store/open-branch! opened (registry/cluster-branch cluster))]
       (try
        (is (= expected ((deref #'source-test/markers) connection)))
        (finally (d/release connection))))))))))
