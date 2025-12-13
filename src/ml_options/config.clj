(ns ml-options.config
  "Centralized system configuration loading.

  Loads system.edn with Aero and handles profile-specific configuration."
  (:require
   [clojure.java.io :as io]
   [aero.core :as aero]
   [integrant.core :as ig]))

;; Register Integrant ref readers with Aero
;; This allows #ig/ref and #ig/refset tags in system.edn to be parsed by Aero
(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defmethod aero/reader 'ig/refset
  [_ _ value]
  (ig/refset value))

(def ^:const system-filename "system.edn")

(defn system-config
  "Load system configuration from system.edn.

  Args:
    options - Map with :profile key (:dev, :test, :prod)"
  [options]
  (let [config-file (io/resource system-filename)]
    (when-not config-file
      (throw (ex-info "system.edn not found in resources" {:filename system-filename})))
    (aero/read-config config-file options)))
