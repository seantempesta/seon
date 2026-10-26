(ns seon.watch
  "Root watches what its loaders read (docs/health.md#every-writer-root-loads-what-changed): every
  writer, a Claude session, a Seon agent, the owner's editor or git, changes files, and the
  OS reports it. target/watch (native/watch.c, FSEvents) is an OS child printing one record
  per change, its kind letter then the absolute path, ended by NUL; this namespace starts it
  and reads it on one edge thread (the blocking read AGENTS §5 allows), handing each batch,
  what the stream holds when a read returns, to its caller (my.root/watch!). No clock
  decides and nothing polls: a batch is what arrived."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.config :as cfg]))

(defn record
  "Record bytes (a kind letter then the path, NUL already taken off) as [kind path]: kind :path
  (a path changed), :rescan (the subtree at path must be read again: FSEvents coalesced or
  dropped events under it), :root (a watched root moved or was removed), :ready (the stream
  started: a change from here on is reported)."
  {:malli/schema [:=> [:cat bytes?] [:tuple [:enum :path :rescan :root :ready] :string]]}
  [bs]
  [({\p :path \s :rescan \r :root \w :ready} (char (aget bs 0)))
   (String. bs 1 (dec (alength bs)) "UTF-8")])

(defn- read-record!
  "The next record of in as its bytes (without its NUL), or nil at the stream's end. Blocks:
  the edge thread's one read."
  {:malli/schema [:=> [:cat :any] [:maybe bytes?]]}
  [in]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond (neg? b) nil
              (zero? b) (.toByteArray out)
              :else (do (.write out b) (recur)))))))

(defn- read-batch!
  "The records of in up to the first that blocks: one blocking read, then every record the
  stream already holds (its available bytes end on a record's NUL: the child writes whole
  records). nil at the stream's end."
  {:malli/schema [:=> [:cat :any] [:maybe [:vector [:tuple :keyword :string]]]]}
  [in]
  (when-let [r (read-record! in)]
    (loop [rs [(record r)]]
      (if (pos? (.available in))
        (if-let [r (read-record! in)] (recur (conj rs (record r))) rs)
        rs))))

(defn start!
  "Start target/watch over roots (absolute directories) and its reading edge thread. Each
  batch of [kind path] records goes to (on-batch batch); when the stream ends without stop!,
  (on-exit e) gets :seon.watch/died, the child's exit status and stderr in it. Answers the
  watcher {:process :ready} once the stream has started (its :ready record), or the error map
  :seon.watch/died when the child could not start or ended before it was ready."
  {:malli/schema [:=> [:cat [:sequential :string] :seon.host/fn :seon.host/fn] :any]}
  [roots on-batch on-exit]
  (let [bin (str (cfg/checkout) "/target/watch")
        died (fn [p why]
               (contract/error :seon.watch/died #'seon.watch/start! why
                               {:bin bin :roots (vec roots)
                                :exit (when p (.waitFor p))
                                :stderr (when p (slurp (.getErrorStream p)))}))
        p (try (.start (ProcessBuilder. ^java.util.List (into [bin] roots)))
               (catch java.io.IOException e (died nil (str bin " did not start: " (.getMessage e)))))]
    (if (contract/error? p)
      p
      (let [in (java.io.BufferedInputStream. (.getInputStream p))
            ready (some-> (read-record! in) record)
            stopped (promise)]
        (if (not= :ready (first ready))
          (died p (str bin " ended before its stream started"))
          {:process p :stopped stopped
           :thread (doto (Thread. ^Runnable
                                  (fn []
                                    (loop []
                                      (if-let [b (read-batch! in)]
                                        ;; a throw in the batch's handler ends the watch as a death, recorded by on-exit
                                        ;; (the failure point docs/failure-points.md names): never a silent thread end
                                        (if-let [t (try (on-batch b) nil (catch Throwable t t))]
                                          (do (.destroy p)
                                              (on-exit (died p (str "its batch handler threw: " (.getMessage ^Throwable t) ": root no longer watches what its loaders read"))))
                                          (recur))
                                        (when-not (realized? stopped)
                                          (on-exit (died p (str bin " ended: root no longer watches what its loaders read")))))))
                                  "seon.watch")
                     (.setDaemon true)
                     (.start))})))))

(defn stop!
  "End watcher w: its child is destroyed, and its edge thread reads the stream's end and
  ends without reporting a death. Answers once it has."
  {:malli/schema [:=> [:cat :map] :any]}
  [w] (deliver (:stopped w) true) (.destroy (:process w)) (.join (:thread w)))
