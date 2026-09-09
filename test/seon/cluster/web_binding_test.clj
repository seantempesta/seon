(ns seon.cluster.web-binding-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(deftest every-web-binding-publishes-its-actual-address
  (support/with-database
   (fn [connection]
     (let [root (str "tmp/web-binding-" (random-uuid))
           paths (cluster/cluster-paths root "web-binding")
           file (io/file (:seon.boot/advertisement-file paths))
           pages (async/chan (async/sliding-buffer 1))
           render (async/chan (async/sliding-buffer 1))
           faults (async/chan (async/dropping-buffer 1))
           instance {:seon.boot/config {:seon.boot/root root}
                     :seon.boot/cluster-connection connection
                     :seon.boot/advertisement
                     {:seon.boot/cluster-name "web-binding"
                      :seon.boot/prepl-port 12345}
                     :seon.render.web/view
                     {:seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                      :seon.config.eval/time-limit-ms 5000
                      :seon.config/on-core-error :record
                      :seon.db.process/id "web-binding-process"
                      :seon.render.web/pages-mult (async/mult pages)
                      :seon.render.web/registration (atom {})
                      :seon.render.web/latest-packages (atom {})
                      :seon.render.web/render-channel render
                      :seon.render.web/fault-channel faults}}
           dials (assoc (config/defaults) :seon.config.web/port 0)]
       (.mkdirs (.getParentFile file))
       (try
         (with-open [first-server
                     (support/closeable
                      (#'cluster/serve! instance dials)
                      #(web/stop! (:seon.render.web/served %)))]
           (let [first-instance @first-server
                 first-port (get-in first-instance [:seon.render.web/served
                                                    :seon.render.web/port])]
             (is (pos-int? first-port))
             (is (= (:seon.boot/advertisement first-instance)
                    (edn/read-string (slurp file))))
             ;; Keep the first listener bound so the next bind must move.
             (with-open [second-server
                         (support/closeable
                          (#'cluster/serve! first-instance
                           (assoc dials :seon.config.web/port first-port))
                          #(web/stop! (:seon.render.web/served %)))]
               (let [second-instance @second-server
                     served (:seon.render.web/served second-instance)
                     advertised (edn/read-string (slurp file))]
                 (is (not= first-port (:seon.render.web/port served)))
                 (is (= first-port (:seon.render.web/wanted-port served)))
                 (is (= (select-keys served [:seon.render.web/url :seon.render.web/port])
                        (select-keys advertised [:seon.render.web/url :seon.render.web/port])))
                 (is (= 12345 (:seon.boot/prepl-port advertised)))
                 (is (= advertised (:seon.boot/advertisement second-instance)))))))
         (finally
           (doseq [channel [pages render faults]] (async/close! channel))
           (support/delete-recursively! root)))))))
