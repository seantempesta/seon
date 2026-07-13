(ns seon.server.store-test
  "Tests for seon.server.store config builder.

   The builder is pure — these tests only exercise the returned map
   shapes. No filesystem creation, no datahike.api/create-database
   calls. The session registry (Wave 2) is the layer that actually
   opens connections."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.server.store :as store]))

(deftest schemas-registered
  (testing "all store schemas are registered for downstream validation"
    (is (schema/registered? :seon.server.store/db-name))
    (is (schema/registered? :seon.server.store/backend))
    (is (schema/registered? :seon.server.store/path))
    (is (schema/registered? :seon.server.store/config-for-request))
    (is (schema/registered? :seon.server.store/config-for-response))))

(deftest config-for-memory
  (testing ":memory cfg shape"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/m
                                 :seon.server.store/backend :memory})]
      (is (= :memory (get-in cfg [:store :backend])))
      (is (uuid? (get-in cfg [:store :id])))
      (is (= "true" (str (:keep-history? cfg))))
      (is (= :write (:schema-flexibility cfg)))
      (is (= ":test/m" (:name cfg)))
      (is (not (contains? (:store cfg) :path)))
      (is (false? (d/database-exists? cfg))
          "fresh :memory cfg should not yet have a database"))))

(deftest config-for-memory-deterministic-id
  (testing "same db-name → same :store :id UUID across calls"
    (let [a (store/config-for {:seon.server.store/db-name :test/same
                               :seon.server.store/backend :memory})
          b (store/config-for {:seon.server.store/db-name :test/same
                               :seon.server.store/backend :memory})]
      (is (= (get-in a [:store :id]) (get-in b [:store :id]))))))

(deftest config-for-file
  (testing ":file cfg shape with default path"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/f
                                 :seon.server.store/backend :file})]
      (is (= :file (get-in cfg [:store :backend])))
      (is (= "data/sessions/f/store" (get-in cfg [:store :path])))
      (is (uuid? (get-in cfg [:store :id])))))
  (testing ":file cfg with explicit path override"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/f
                                 :seon.server.store/backend :file
                                 :seon.server.store/path "tmp/test-override"})]
      (is (= "tmp/test-override" (get-in cfg [:store :path])))))
  (testing "bare :file path is re-rooted under data/sessions/ (no repo-root pollution)"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/f
                                 :seon.server.store/backend :file
                                 :seon.server.store/path "Bh"})]
      (is (= "data/sessions/Bh/store" (get-in cfg [:store :path]))
          "a bare path with no directory component must not land in CWD")))
  (testing "absolute :file path passes through unchanged"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/f
                                 :seon.server.store/backend :file
                                 :seon.server.store/path "/tmp/abs-store"})]
      (is (= "/tmp/abs-store" (get-in cfg [:store :path]))))))

(deftest namespaced-db-name-yields-name-segment
  (testing "namespace portion of db-name is stripped for path segment"
    (let [cfg (store/config-for {:seon.server.store/db-name :seon.cluster/alice
                                 :seon.server.store/backend :file})]
      (is (= "data/sessions/alice/store"
             (get-in cfg [:store :path]))))))

(deftest config-for-is-pure
  (testing "no directories created merely by building a cfg"
    (let [cfg (store/config-for {:seon.server.store/db-name :test/pure-check
                                 :seon.server.store/backend :file
                                 :seon.server.store/path "tmp/store-test-pure/store"})
          parent (java.io.File. "tmp/store-test-pure")]
      ;; cfg built but directory NOT created
      (is (some? cfg))
      (is (not (.exists parent))
          "config-for must not create directories")
      ;; explicit helper does create
      (let [{:seon.server.store/keys [created?]}
            (store/ensure-parent-dir!
             {:seon.server.store/path "tmp/store-test-pure/store"})]
        (is (true? created?))
        (is (.exists parent)))
      ;; cleanup
      (when (.exists parent) (.delete parent)))))
