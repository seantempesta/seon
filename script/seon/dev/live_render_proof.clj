;; F2 LIVE PROOF — the render pipeline on a REAL cluster boot.
;;
;; This is the reset-boundary proof no fixture can give: a real
;; `seon.cluster/start!` (config facts, seeded root agent, the cluster
;; graph with its armer AND render procs, the routing listener), a real
;; http-kit server, a real browser-shaped SSE socket, and a real commit.
;;
;; It runs in its OWN JVM against its OWN store root, so it never opens
;; the live default cluster's store — two JVMs on one store is the thing
;; that once destroyed 40/40 commits.
;;
;; Usage: clojure -M:dev script/seon/dev/live_render_proof.clj
;; (`script` is not on the :dev classpath, so it is loaded as a file;
;; the ns form is here for tooling, not for -m.)
(ns seon.dev.live-render-proof
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster :as cluster])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(def root "data/f2live")

(defn patches [text] (count (re-seq #"event: datastar-patch-elements" text)))

(defn read-through!
  "Block until `expected` complete patch events have arrived, or fail
  loudly. The backstop is a bug report, never the primary path."
  [stream expected label]
  (let [reader (future
                 (let [out (StringBuilder.)]
                   (loop []
                     (let [b (.read stream)]
                       (when (neg? b)
                         (throw (ex-info "feed closed early"
                                         {:label label
                                          :got (patches (.toString out))})))
                       (.append out (char b))
                       (let [text (.toString out)]
                         (if (and (= expected (patches text))
                                  (str/ends-with? text "\n\n"))
                           text
                           (recur)))))))
        result (deref reader 15000 ::backstop)]
    (when (= ::backstop result)
      (throw (ex-info "LIVE PROOF BACKSTOP FIRED — no morph arrived"
                      {:label label :expected expected})))
    result))

(defn -main []
  (let [instance (cluster/start! {:seon.boot/cluster-name "f2live"
                                 :seon.boot/root root})
        url (get-in instance [:seon.render.web/served :seon.render.web/url])
        connection (:seon.boot/cluster-connection instance)
        client (.build (HttpClient/newBuilder))
        open (fn [path]
               (.body (.send client
                             (.build (.GET (HttpRequest/newBuilder
                                            (URI/create (str url path)))))
                             (HttpResponse$BodyHandlers/ofInputStream))))]
    (println "LIVE cluster f2live at" url)
    (try
      (let [tab-a (open "/feed/root")
            tab-b (open "/feed/root")]
        (try
          ;; 1. THE INITIAL PAINT: every block, at its own id, derived
          ;;    from current facts by the tab itself.
          (let [initial (read-through! tab-a 6 :initial-a)]
            (println "PROOF 1 initial paint blocks:" (patches initial))
            (println "PROOF 1 surface ids:"
                     (sort (distinct (map second
                                          (re-seq #"id=\"(surface-[a-z]+)\""
                                                  initial))))))
          (read-through! tab-b 6 :initial-b)
          (println "PROOF 2 second tab painted independently")

          ;; 2. A REAL COMMIT: route! offers ONE render wake, the proc
          ;;    derives ONE page for the cluster, the mult fans it, and
          ;;    each tab diffs and patches only what changed.
          (d/transact connection [{:seon.cluster.agent/id "live-probe"}])
          (let [morph-a (read-through! tab-a 1 :morph-a)
                morph-b (read-through! tab-b 1 :morph-b)]
            (println "PROOF 3 morph after commit, tab A blocks:"
                     (patches morph-a))
            (println "PROOF 3 tab A carries the agents block:"
                     (str/includes? morph-a "surface-agents"))
            (println "PROOF 3 tab A names the new agent:"
                     (str/includes? morph-a "live-probe"))
            (println "PROOF 4 tab B got byte-identical bytes:"
                     (= morph-a morph-b))
            (println "PROOF 5 untouched blocks stayed off the wire:"
                     (not (str/includes? morph-a "surface-header"))))
          (finally (.close tab-a) (.close tab-b))))

      ;; 3. RECONNECT = REPAINT: a fresh tab derives the CURRENT page
      ;;    from facts; nothing was stored to replay.
      (let [tab-c (open "/feed/root")]
        (try
          (let [repaint (read-through! tab-c 6 :reconnect)]
            (println "PROOF 6 reconnect repainted blocks:" (patches repaint))
            (println "PROOF 6 at the CURRENT basis (sees live-probe):"
                     (str/includes? repaint "live-probe")))
          (finally (.close tab-c))))

      ;; 4. ZERO STREAMING DATOMS CAN EXIST: the attribute family is gone
      ;;    from the registry, so no partial row is representable.
      (println "PROOF 7 stream attributes installed:"
               (d/q '[:find (count ?a) . :where [?a :db/ident ?ident]
                      [(namespace ?ident) ?ns]
                      [(= ?ns "seon.ai.stream")]]
                    @connection))
      (finally
        (cluster/stop! instance)
        (println "LIVE cluster stopped")))))

(-main)
