(ns seon.test-fixture
  "Standard reproducible Datahike test fixture (`seed = 42` by default):
   - Gives every Datahike test an isolated, deterministic LMDB store (or branch) pre-seeded
     with Seon's full schema, default Aero config, and seeded Malli schemas.
   - Deterministic datom generation (`seeded-entities`) ensures tests never rely on ambient
     `:main` mutations and Agent A never invalidates Agent B's test cache."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [malli.generator :as mg]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- rm-rf! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn seed-core-schemas!
  "Loads `resources/seon/schemas.edn` into `conn` if present."
  [conn]
  (let [f (io/file store/checkout-root "resources/seon/schemas.edn")]
    (when (.isFile f)
      (let [entries (edn/read-string (slurp f))
            validated (schema/validate-schema-batch! @conn entries)]
        (when (seq validated)
          (d/transact conn {:tx-data validated}))))))

(defn call-with-test-store
  "Creates an isolated reproducible store (`4 MB` cache), seeds core schemas, runs `(f {:conn :cfg :dir})`,
   and cleans up deterministically."
  [f]
  (let [dir (str "/tmp/seon-fixture-" (System/nanoTime))
        st (store/open! dir {:cache-bytes (* 4 1024 1024)})]
    (try
      (seed-core-schemas! (:conn st))
      (f (assoc st :dir dir))
      (finally
        (store/close! st)
        (rm-rf! (io/file dir))))))

(defmacro with-test-store
  "Standard reproducible Datahike store macro for `deftest`s."
  [[binding-sym] & body]
  `(call-with-test-store (fn [~binding-sym] ~@body)))

(defn generate-samples
  "Deterministically generates `n` values matching `malli-schema` from `db` using `seed` (default `42`)."
  ([db malli-schema n] (generate-samples db malli-schema n 42))
  ([db malli-schema n seed]
   (let [{:keys [registry]} (schema/registry-for-db db)]
     (mg/generate [:vector {:min n :max n} malli-schema]
                  {:registry registry :seed seed :size 6}))))
