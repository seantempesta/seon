(ns seon.fork.container
  "Optional container execution bridge helpers (`user/sh!`, `user/fs-read`, `user/fs-write`,
       `user/fs-patch`) installed into an agent's REPL only when `SEON_EXEC_BRIDGE_URL` is set
       (e.g. Harbor Docker benchmark trials running host-side Seon against an isolated container)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [seon.model.wire :as wire]))

(defn clip-output
  "Clips long shell/file output to `max-chars` (preserving head and tail) so agents never\n       blow context windows or allocation budgets on noisy build logs."
  {:malli/schema [:=> [:cat :string :int] :string],
   :cost/ticks 500000,
   :cost/bytes 2097152}
  [s max-chars]
  (let [txt (str s) lim (long max-chars) n (count txt)]
    (cond
      (<= lim 0) ""
      (<= n lim) txt
      (< lim 80) (subs txt 0 lim)
      :else (let [head-n (quot (* lim 2) 3)
                  tail-n (max 0 (- lim head-n 48))
                  omitted (- n head-n tail-n)]
              (str
                (subs txt 0 head-n)
                "\n... ["
                omitted
                " chars omitted] ...\n"
                (subs txt (- n tail-n)))))))

(defn- bridge-url []
  (let [u (some-> (System/getenv "SEON_EXEC_BRIDGE_URL") str/trim)]
    (when (and
            u
            (or
              (str/starts-with? u "http://")
              (str/starts-with? u "https://")))
      (str/replace u #"/+$" ""))))

(defn- bridge-post! [endpoint payload]
  (if-let [base (bridge-url)]
    (try
      (let [resp (wire/http-post!
                   (str base endpoint)
                   [["Content-Type" "application/json"]]
                   (json/write-str payload))
            body (str (:body resp))]
        (if (= 200 (:status resp))
          (try
            (json/read-str body :key-fn keyword)
            (catch
              Throwable
              ex
              {:ok? false,
               :error :bad-json,
               :message (or (.getMessage ex) (str ex)),
               :body (clip-output body 400)}))
          {:ok? false,
           :error :http-error,
           :status (:status resp),
           :body (clip-output body 400)}))
      (catch
        Throwable
        ex
        {:ok? false,
         :error :bridge-transport,
         :message (or (.getMessage ex) (str ex))}))
    {:ok? false,
     :error :no-exec-bridge,
     :message "SEON_EXEC_BRIDGE_URL is not configured."}))

(defn ^{:seon.flow/edge true} sh!
  "Executes shell command `cmd` inside the bridged Linux container and returns `{:exit :out :err}`.\n       Optional opts map: `{:cwd \"/app\" :timeout-sec 60 :max-chars 24000}`."
  {:malli/schema
   [:function
    [:=> [:cat :string] :map]
    [:=> [:cat :string :map] :map]],
   :seon.eval/budget-override {:ticks 500000000, :bytes 268435456}}
  ([cmd] (sh! cmd {}))
  ([cmd opts]
    (let [lim (long (or (:max-chars opts) 24000))
          payload (cond->
                    {:command (str cmd),
                     :timeout_sec (long (or (:timeout-sec opts) 60))}
                    (:cwd opts)
                    (assoc :cwd (str (:cwd opts))))
          res (bridge-post! "/exec" payload)]
      (if (contains? res :exit)
        {:exit (long (:exit res)),
         :out (clip-output (str (or (:out res) "")) lim),
         :err (clip-output (str (or (:err res) "")) (min lim 8000))}
        res))))

(defn ^{:seon.flow/edge true} fs-read
  "Reads a text file inside the bridged container. Optional `{:lines [start end] :max-chars 24000}`."
  {:malli/schema
   [:function
    [:=> [:cat :string] :map]
    [:=> [:cat :string :map] :map]]}
  ([path] (fs-read path {}))
  ([path opts]
    (let [lim (long (or (:max-chars opts) 24000))
          [s-line e-line] (when (sequential? (:lines opts))
                            (:lines opts))
          payload (cond->
                    {:path (str path)}
                    (integer? s-line)
                    (assoc :start_line (long s-line))
                    (integer? e-line)
                    (assoc :end_line (long e-line)))
          res (bridge-post! "/read" payload)]
      (if (:ok res)
        {:ok? true,
         :path (str path),
         :lines (long (or (:lines res) 0)),
         :content (clip-output (str (or (:content res) "")) lim)}
        (assoc res :ok? false :path (str path))))))

(defn ^{:seon.flow/edge true} fs-write
  "Creates or overwrites a text file (and parent dirs) inside the bridged container."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [path content]
  (let [res (bridge-post!
              "/write"
              {:path (str path), :content (str content)})]
    (if (:ok res)
      {:ok? true,
       :path (str path),
       :bytes (long (or (:bytes res) (count (str content))))}
      (assoc res :ok? false :path (str path)))))

(defn ^{:seon.flow/edge true} fs-patch
  "Surgically replaces `old-str` with `new-str` inside a bridged container file (exact match)."
  {:malli/schema
   [:function
    [:=> [:cat :string :string :string] :map]
    [:=> [:cat :string :string :string :map] :map]]}
  ([path old-str new-str] (fs-patch path old-str new-str {}))
  ([path old-str new-str opts]
    (let [res (bridge-post!
                "/patch"
                {:path (str path),
                 :old (str old-str),
                 :new (str new-str),
                 :replace_all (boolean (:all? opts))})]
      (if (:ok res)
        {:ok? true,
         :path (str path),
         :replacements (long (or (:replacements res) 1))}
        (assoc res :ok? false :path (str path))))))

(defn ^{:seon.flow/edge true} install-container-helpers!
  "Installs `user/sh!`, `user/fs-read`, `user/fs-write`, `user/fs-patch` into namespace `u`\n       when `SEON_EXEC_BRIDGE_URL` is configured."
  {:malli/schema [:=> [:cat :any] :any]}
  [u]
  (when (bridge-url)
    (doseq [[sym v] [['sh! #'sh!]
                     ['fs-read #'fs-read]
                     ['fs-write #'fs-write]
                     ['fs-patch #'fs-patch]]]
      (let [iv (intern u sym @v)
            m (select-keys
                (meta v)
                [:arglists :doc :seon.eval/budget-override])]
        (when (seq m) (alter-meta! iv merge m))))))
