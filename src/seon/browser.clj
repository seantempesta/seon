(ns seon.browser
  "The `:browser` Flow proc (`:io` workload) — Native Embedded Browser (`target/seon-browser`,
   macOS `WKWebView` via `native/browser/seon_browser.m`) for live ephemeral viewport snapshots:
   - Runs an off-screen GPU-backed `WKWebView` pointed directly at the standing Seon server
     (`http://127.0.0.1:<port>/`) with 0 Dock icon, 0 focus steal, and 0 Chrome/Keychain popups.
   - Captures real rendered JPEG screenshots (~20-60 ms, ~40-70 KB) + live DOM layout telemetry
     (viewport dims, horizontal overflow, SVG bounding rects, theme, active view) on demand before
     each agent turn.
   - Emits screenshots ONLY to `:agents` (for ephemeral end-of-context injection — replaced every
     turn, NEVER stored in Datahike history) and `:web` (for live preview inside Debug View)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.store :as store]))

(defn default-binary-path
  "Returns executable path to `target/seon-browser` (or `SEON_BROWSER_BIN`) if present, else nil."
  []
  (let [env-bin (System/getenv "SEON_BROWSER_BIN")
        candidates (remove str/blank?
                           [env-bin
                            (str store/checkout-root "/target/seon-browser")
                            (str (System/getProperty "user.dir") "/target/seon-browser")])]
    (some (fn [p]
            (let [f (io/file p)]
              (when (and (.isFile f) (.canExecute f))
                (.getAbsolutePath f))))
          candidates)))

(defn ^:seon.flow/edge start-browser-process!
  "Starts `seon-browser --headless` pointing at `url` and pumps JSON-line responses into `resp-ch`."
  [binary-path url {:keys [width height] :or {width 1280 height 820}} resp-ch]
  (let [pb (ProcessBuilder. ^java.util.List
                            [binary-path
                             "--headless"
                             "--url" (str url)
                             "--width" (str width)
                             "--height" (str height)])
        proc (.start pb)
        stdin-w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream proc) "UTF-8"))
        stdout-r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc) "UTF-8"))
        send-lock (Object.)
        send! (fn [cmd-map]
                (try
                  (locking send-lock
                    (.write stdin-w (str (json/write-str cmd-map) "\n"))
                    (.flush stdin-w))
                  true
                  (catch Throwable _ false)))
        stop! (fn []
                (try (send! {:op "quit"}) (catch Throwable _))
                (try (.close stdin-w) (catch Throwable _))
                (try (.destroy proc) (catch Throwable _)))]
    (a/thread
      (try
        (loop []
          (when-let [line (.readLine stdout-r)]
            (when-not (str/blank? line)
              (when-let [parsed (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
                (a/put! resp-ch parsed)))
            (recur)))
        (catch Throwable _ nil)
        (finally
          (try (.close stdout-r) (catch Throwable _))
          (a/put! resp-ch {:event "exited"}))))
    {:proc proc
     :binary binary-path
     :url url
     :send! send!
     :stop! stop!}))

(defn- normalize-snapshot [msg]
  (when (and (:ok msg) (not (str/blank? (str (:base64 msg)))))
    {:route (or (get-in msg [:dom :url]) "/")
     :width (:width msg 1280)
     :height (:height msg 820)
     :mime (or (:mime msg) "image/jpeg")
     :bytes (:bytes msg)
     :ms (:ms msg)
     :base64 (:base64 msg)
     :dom (or (:dom msg) {})
     :captured-at (System/currentTimeMillis)}))

(defn browser-proc
  ([] {:params {:port "Standing HTTP port (0 disables automatic headless browser launch)"
                :binary-path "Optional path to seon-browser binary"
                :width "Viewport width (default 1280)"
                :height "Viewport height (default 820)"}
       :ins {:req "On-demand snapshot/navigate/eval requests from `:agents` or `:server`"}
       :outs {:agents-res "Snapshot replies `{:req-id :agent-id :snapshot}` back to `:agents`"
              :web-snap "Latest live viewport snapshot `{:snapshot}` for `:web` Debug View"}
       :workload :io
       :doc "Native embedded macOS WKWebView host providing live ephemeral viewport screenshots & DOM telemetry."
       :ping-map-fn (fn [st]
                      {:enabled? (boolean (:handle st))
                       :ready? (boolean (:ready? st))
                       :snaps (:snap-count st 0)
                       :pending (count (:pending st))
                       :last-ms (get-in st [:last-snap :ms])
                       :last-bytes (get-in st [:last-snap :bytes])})})
  ([{:keys [port binary-path width height]}]
   (let [ipc-ch (a/chan 32)
         timer-ch (a/chan 32)
         w (or width 1280)
         h (or height 820)
         bin (when (and port (pos? (long port)))
               (or binary-path (default-binary-path)))
         url (when bin (str "http://127.0.0.1:" port "/"))
         handle (when bin
                  (try (start-browser-process! bin url {:width w :height h} ipc-ch)
                       (catch Throwable _ nil)))]
     {:port port
      :url url
      :width w
      :height h
      :handle handle
      :ready? false
      :cmd-seq 0
      :pending {}
      :snap-count 0
      :last-snap nil
      ::flow/in-ports {:ipc ipc-ch :timer timer-ch}}))
  ([st transition]
   (when (= transition ::flow/stop)
     (when-let [stop! (get-in st [:handle :stop!])]
       (stop!)))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :req
       (let [{:keys [op req-id agent-id reply-ch wait-ms]} msg
             handle (:handle st)]
         (cond
           (nil? handle)
           (do (when reply-ch
                 (a/put! reply-ch {:ok? false :error :browser-unavailable :snapshot (:last-snap st)})
                 (a/close! reply-ch))
               [st (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot nil}]})])

           (= op :snapshot)
           (let [cmd-id (inc (:cmd-seq st))
                 cmd {:id cmd-id
                      :op "snapshot"
                      :width (or (:width msg) (:width st) 1280)
                      :height (or (:height msg) (:height st) 820)
                      :max_width (or (:max-width msg) 1024)
                      :quality (or (:quality msg) 0.74)}
                 timer-ch (get-in st [::flow/in-ports :timer])
                 send! (:send! handle)
                 delay-ms (long (or wait-ms 0))]
             ;; Fire after optional short settle delay (lets Datastar SSE paint latest Canvas patch)
             (if (pos? delay-ms)
               (a/go
                 (a/<! (a/timeout delay-ms))
                 (when-not (send! cmd)
                   (a/put! timer-ch {:ev :timeout :id cmd-id})))
               (when-not (send! cmd)
                 (a/put! timer-ch {:ev :timeout :id cmd-id})))
             ;; Safety deadline so an agent turn NEVER stalls if WebKit is slow
             (a/go
               (a/<! (a/timeout (+ delay-ms 2200)))
               (a/put! timer-ch {:ev :timeout :id cmd-id}))
             [(-> st
                  (assoc :cmd-seq cmd-id)
                  (assoc-in [:pending cmd-id] {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :snapshot}))
              nil])

           (= op :navigate)
           (let [cmd-id (inc (:cmd-seq st))
                 timer-ch (get-in st [::flow/in-ports :timer])]
             ((:send! handle) {:id cmd-id :op "navigate" :url (or (:url msg) (:url st))})
             (a/go (a/<! (a/timeout 8500)) (a/put! timer-ch {:ev :timeout :id cmd-id}))
             [(-> st
                  (assoc :cmd-seq cmd-id)
                  (assoc-in [:pending cmd-id] {:req-id req-id :agent-id agent-id :reply-ch reply-ch :op :navigate}))
              nil])

           :else
           [st nil]))

       :ipc
       (cond
         (= "ready" (:event msg))
         [(assoc st :ready? true)
          {::flow/report [(str "[:browser] native WKWebView ready (" (:mode msg) " " (:width msg) "x" (:height msg) ")")]}]

         (= "exited" (:event msg))
         (let [pend (:pending st)
               outs (keep (fn [[_ {:keys [req-id agent-id reply-ch]}]]
                            (when reply-ch (a/put! reply-ch {:ok? false :error :browser-exited}) (a/close! reply-ch))
                            (when req-id {:req-id req-id :agent-id agent-id :snapshot nil}))
                          pend)]
           [(assoc st :handle nil :ready? false :pending {})
            (when (seq outs) {:agents-res (vec outs)})])

         (contains? (:pending st) (:id msg))
         (let [cmd-id (:id msg)
               {:keys [req-id agent-id reply-ch op]} (get-in st [:pending cmd-id])
               snap (when (= op :snapshot) (normalize-snapshot msg))
               st' (cond-> (update st :pending dissoc cmd-id)
                     snap (-> (assoc :last-snap snap) (update :snap-count inc)))]
           (when reply-ch
             (a/put! reply-ch (if (= op :snapshot)
                                {:ok? (boolean snap) :snapshot snap :error (:error msg)}
                                {:ok? (boolean (:ok msg)) :raw msg}))
             (a/close! reply-ch))
           [st'
            (cond-> {}
              req-id (assoc :agents-res [{:req-id req-id :agent-id agent-id :snapshot snap}])
              snap   (assoc :web-snap [{:snapshot snap}]))])

         :else
         [st nil])

       :timer
       (let [cmd-id (:id msg)]
         (if-let [{:keys [req-id agent-id reply-ch]} (get-in st [:pending cmd-id])]
           (do (when reply-ch
                 (a/put! reply-ch {:ok? false :error :snapshot-timeout :snapshot (:last-snap st)})
                 (a/close! reply-ch))
               [(update st :pending dissoc cmd-id)
                (when req-id {:agents-res [{:req-id req-id :agent-id agent-id :snapshot (:last-snap st)}]})])
           [st nil]))

       [st nil]))))
