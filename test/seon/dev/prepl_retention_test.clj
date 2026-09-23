(ns seon.dev.prepl-retention-test
  "A prepl session keeps no evaluation result once the result is returned, and\n  answers every form exactly once even when its reply cannot be printed."
  (:require [clojure.core.server :as server]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- session-lines
  "Send `forms` to one session of `seon.operator.prepl/io-prepl` started with
  `valf` and return each form's :ret event, read as EDN."
  [valf forms]
  (let [server-name (str "prepl-reply-" (random-uuid))
        listener (server/start-server
                  {:name server-name :accept 'seon.operator.prepl/io-prepl
                   :args [:cluster-name server-name :valf valf]
                   :address "127.0.0.1" :port 0})]
    (try
      (with-open [socket (java.net.Socket. "127.0.0.1" (.getLocalPort listener))
                  writer (io/writer socket)
                  reader (java.io.PushbackReader. (io/reader socket))]
        (.setSoTimeout socket 4000)
        (doseq [form forms] (.write writer (str (pr-str form) "\n")))
        (.flush writer)
        (loop [rets []]
          (if (= (count forms) (count rets))
            rets
            (let [event (edn/read reader)]
              (recur (cond-> rets (= :ret (:tag event)) (conj event)))))))
      (finally
        (server/stop-server server-name)))))

(deftest a-reply-that-cannot-be-printed-is-a-declared-error-and-the-session-answers-on
  (let [throwing (fn [value _] (if (= :next value) (pr-str value) (throw (IllegalStateException. "cannot show"))))
        [failed thrown answered] (session-lines throwing ['(+ 1 2)
                                                     '(throw (ex-info "outer" {:k #'clojure.core/map}))
                                                     :next])
        failed-val (edn/read-string (:val failed))
        thrown-val (edn/read-string (:val thrown))]
    (is (true? (:exception failed)) "a value that cannot be shown is reported, as io-prepl does")
    (is (= :print-eval-result (:clojure.error/phase failed-val)))
    (is (str/includes? (:seon.error/message failed-val) "cannot show"))
    (is (str/includes? (:seon.error/message thrown-val) "\"outer\"")
        "the evaluation's own failure is shown beside the printer's")
    (is (= ":next" (:val answered)) "the session answers the next form exactly once")))
