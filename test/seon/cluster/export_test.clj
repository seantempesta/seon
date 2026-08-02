(ns seon.cluster.export-test
  "Sealed acceptance for the export path — clone plus re-identify (B2).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing the seon.cluster.export stubs
  ONLY — schemas and tests are byte-sealed; friction is reported, never
  resolved by weakening.

  Live against the `:file` backend under project-local tmp/. The
  acceptance fact is not \"a directory appeared\": the export is OPENED
  through `seon.cluster.store/open-store!`, its data is read back, its
  store id is proven distinct from the source's, and a NON-`:db` branch
  is connected — the case a `:db`-only re-identify silently fails
  (probed: `tmp/b2-draft-probe/head_config_probe.clj` connected `:db`
  and refused `:ancestor-x` with `:store-identity-mismatch`). The
  source store is proven untouched, because an export that mutates what
  it copies is a backup that eats the original.

  These tests create their extra branch with `datahike.api/branch!`
  directly. That is deliberate: the export suite must fail for export's
  reasons only, never for the registry's. Production code still routes
  every branch operation through `seon.cluster.registry`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.cluster.export :as export]
            [seon.cluster.store :as store]
            [seon.schema]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; Scaffolding
;;; ---------------------------------------------------------------------------

(def ^:private probe-schema
  [{:db/ident :seon.export.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(def ^:private other-branch :cluster-carried)

(defn- markers [connection]
  (set (d/q '[:find [?marker ...]
              :where [_ :seon.export.test/marker ?marker]]
            @connection)))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch Exception failure
      (ex-data failure))))

(defn- store-id [dir]
  (get-in (store/datahike-configuration dir) [:store :id]))

(defn- with-populated-store
  "Call `body` with an open store carrying `:db` rows and one more branch."
  [body]
  (let [root (str "tmp/export-test/" (random-uuid))
        dir (str root "/source/store")]
    (.mkdirs (.getParentFile (io/file dir)))
    (let [opened (store/open-store! {:seon.store/dir dir})]
      (try
        (d/transact (:seon.store/connection opened) probe-schema)
        (d/transact (:seon.store/connection opened)
                    {:tx-data [{:seon.export.test/marker "on-main"}]})
        (d/branch! (:seon.store/connection opened) :db other-branch)
        (let [connection (store/open-branch! opened other-branch)]
          (try
            (d/transact connection
                        {:tx-data [{:seon.export.test/marker "on-branch"}]})
            (finally
              (d/release connection))))
        (body {:root root :store opened})
        (finally
          (store/release-store! opened)
          (test-support/delete-recursively! root))))))

;;; ---------------------------------------------------------------------------
;;; export!
;;; ---------------------------------------------------------------------------

(deftest an-export-is-an-independent-openable-store
  (with-populated-store
    (fn [{:keys [root store]}]
      (let [parent (str root "/export")
            path (export/export! {:seon.store/store store
                                  :seon.export/parent-dir parent})]
        (is (string? path))
        (is (.isDirectory (io/file path)))
        (is (= (.getCanonicalPath (io/file parent "store")) path)
            "the export lands under its final name, never a temp name")
        (is (empty? (filter #(str/starts-with? (.getName ^java.io.File %)
                                                          ".store.")
                            (.listFiles (io/file parent))))
            "and no temp directory is left behind")
        (testing "it carries its OWN identity, not the source's"
          (is (not= (store-id (:seon.store/dir store)) (store-id path))))
        (let [exported (store/open-store! {:seon.store/dir path})]
          (try
            (is (false? (:seon.store/created? exported))
                "the export opens as an existing store — it was not recreated")
            (is (= #{"on-main"} (markers (:seon.store/connection exported))))
            (testing "EVERY branch head was re-identified, not only :db"
              (is (contains? (d/branches (:seon.store/connection exported))
                             other-branch))
              (let [connection (store/open-branch! exported other-branch)]
                (try
                  (is (= #{"on-main" "on-branch"} (markers connection)))
                  (finally
                    (d/release connection)))))
            (testing "the export is writable — a copy, not a read-only image"
              (d/transact (:seon.store/connection exported)
                          {:tx-data [{:seon.export.test/marker "post-export"}]})
              (is (= #{"on-main" "post-export"}
                     (markers (:seon.store/connection exported)))))
            (finally
              (store/release-store! exported))))
        (testing "the source is untouched by its own export"
          (is (= #{"on-main"} (markers (:seon.store/connection store))))
          (is (contains? (d/branches (:seon.store/connection store))
                         other-branch)))))))

(deftest an-export-never-overwrites-a-store
  (with-populated-store
    (fn [{:keys [root store]}]
      (let [parent (str root "/export")]
        (export/export! {:seon.store/store store
                         :seon.export/parent-dir parent})
        (is (= :seon.cluster.export/export-exists
               (:seon.cluster.export/rule
                (refusal #(export/export!
                           {:seon.store/store store
                            :seon.export/parent-dir parent})))))))))

(deftest failed-export-cleanup-never-follows-a-symlink
  (with-populated-store
    (fn [{:keys [root store]}]
      (let [parent (str root "/failed-export")
            outside (io/file root "outside-export-temp")
            sentinel (io/file outside "must-survive.txt")
            temporary (atom nil)
            copy-store-var
            (ns-resolve 'seon.cluster.export 'copy-store!)]
        (.mkdirs outside)
        (spit sentinel "do not delete me")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"injected copy failure"
             (with-redefs-fn
               {copy-store-var
                (fn [_store target]
                  (reset! temporary target)
                  (.mkdirs ^java.io.File target)
                  (java.nio.file.Files/createSymbolicLink
                   (.toPath (io/file target "linked-elsewhere"))
                   (.toAbsolutePath (.toPath outside))
                   (make-array java.nio.file.attribute.FileAttribute 0))
                  (throw (ex-info "injected copy failure" {})))}
               #(export/export!
                 {:seon.store/store store
                  :seon.export/parent-dir parent}))))
        (is (.exists sentinel)
            "failed-export cleanup deletes the link, not its target")
        (is (not (.exists ^java.io.File @temporary))
            "the failed export's temporary tree is removed")))))

;;; ---------------------------------------------------------------------------
;;; reidentify!
;;; ---------------------------------------------------------------------------

(deftest reidentify-is-idempotent-on-a-store-that-already-fits-its-path
  (with-populated-store
    (fn [{:keys [root store]}]
      (let [parent (str root "/export")
            path (export/export! {:seon.store/store store
                                  :seon.export/parent-dir parent})]
        (is (= path (export/reidentify! path)))
        (is (= path (export/reidentify! path)) "twice changes nothing")
        (let [exported (store/open-store! {:seon.store/dir path})]
          (try
            (is (= #{"on-main"} (markers (:seon.store/connection exported))))
            (finally
              (store/release-store! exported))))))))

(deftest reidentify-refuses-what-is-not-a-complete-store
  (let [root (str "tmp/export-test/" (random-uuid))]
    (try
      (testing "a directory with no :db head is not a store"
        (let [empty-dir (str root "/empty")]
          (.mkdirs (io/file empty-dir))
          (is (= :seon.cluster.export/no-branch-head
                 (:seon.cluster.export/rule
                  (refusal #(export/reidentify! empty-dir)))))))
      (testing "a store killed mid-genesis is never carried into an export"
        (let [dir (str root "/half/store")]
          (.mkdirs (.getParentFile (io/file dir)))
          (let [opened (store/open-store! {:seon.store/dir dir})]
            (d/transact (:seon.store/connection opened) probe-schema)
            (store/release-store! opened))
          ;; the first-create kill window, manufactured exactly as B1's
          ;; own suite manufactures it
          (let [konserve (filestore/connect-fs-store dir :opts {:sync? true})]
            (k/dissoc konserve :branches {:sync? true}))
          (is (= :seon.cluster.export/genesis-incomplete
                 (:seon.cluster.export/rule
                  (refusal #(export/reidentify! dir)))))))
      (finally
        (test-support/delete-recursively! root)))))
