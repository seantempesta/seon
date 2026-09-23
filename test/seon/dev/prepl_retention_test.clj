(ns seon.dev.prepl-retention-test
  "A prepl session keeps no evaluation result once the result is returned."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def result-reference (atom nil))

(defn- collected-after-return?
  "Evaluate `form` in one session of the prepl entry `accept`, read its
  :ret line, and with the session still open report whether the value the
  form placed in `result-reference` is collected within three System/gc."
  [accept form]
  (let [server-name (str "prepl-retention-" (random-uuid))
        listener (server/start-server
                  {:name server-name :accept accept
                   :args [:cluster-name server-name]
                   :address "127.0.0.1" :port 0})]
    (try
      (with-open [socket (java.net.Socket. "127.0.0.1" (.getLocalPort listener))
                  writer (io/writer socket)
                  reader (io/reader socket)]
        (.setSoTimeout socket 4000)
        (.write writer (str (pr-str form) "\n"))
        (.flush writer)
        (is (some? (.readLine reader)) "the session answers with its :ret")
        (loop [collections 0]
          (if (and (some? (.get ^java.lang.ref.WeakReference @result-reference))
                   (< collections 3))
            (do (System/gc) (recur (inc collections)))
            (nil? (.get ^java.lang.ref.WeakReference @result-reference)))))
      (finally
        (server/stop-server server-name)))))

(deftest a-returned-value-is-collectible-with-no-further-evaluation
  ;; Clojure's prepl `set!`s *1/*2/*3 (and *e on a throw) to each result;
  ;; a 64 MB value once lived until three more evaluations in its session.
  (is (collected-after-return?
       'seon.operator.prepl/io-prepl
       `(let [value# (byte-array (* 8 1024 1024))]
          (reset! result-reference (java.lang.ref.WeakReference. value#))
          value#))
      "*1 does not keep the returned value")
  (is (collected-after-return?
       'seon.operator.prepl/io-prepl
       `(let [value# (byte-array (* 8 1024 1024))]
          (reset! result-reference (java.lang.ref.WeakReference. value#))
          (throw (ex-info "retained?" {:value value#}))))
      "*e does not keep the thrown value"))
