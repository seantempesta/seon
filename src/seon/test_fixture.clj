(ns seon.test-fixture
  "Standard reproducible Datahike test fixture (`seed = 42` by default):
   - Gives every Datahike test an isolated, deterministic LMDB store (`4 MB` cache) pre-seeded
     automatically via `seon.store/open!` with Seon's Malli-derived Datahike schema, co-located
     `:seon.schema/*` entities, and `:seon.config/root`.
   - Deterministic datom/value generation (`generate-samples`) ensures Branch A never invalidates Branch B."
  (:require [clojure.java.io :as io]
            [malli.generator :as mg]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- rm-rf! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn call-with-test-store
  "Creates an isolated reproducible store (`4 MB` cache), runs `(f {:conn :cfg :dir})`, and cleans up."
  [f]
  (let [dir (str "/tmp/seon-fixture-" (System/nanoTime))
        st (store/open! dir {:cache-bytes (* 4 1024 1024)})]
    (try
      (f (assoc st :dir dir))
      (finally
        (store/close! st)
        (rm-rf! (io/file dir))))))

(defmacro with-test-store
  [[binding-sym] & body]
  `(call-with-test-store (fn [~binding-sym] ~@body)))

(defn generate-samples
  "Deterministically generates `n` values matching `malli-schema` from `db` using `seed` (default `42`)."
  ([db malli-schema n] (generate-samples db malli-schema n 42))
  ([db malli-schema n seed]
   (let [{:keys [registry]} (schema/registry-for-db db)]
     (mg/generate [:vector {:min n :max n} malli-schema]
                  {:registry registry :seed seed :size 6}))))
