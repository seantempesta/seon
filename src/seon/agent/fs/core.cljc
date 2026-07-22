(ns seon.agent.fs.core
  "Pure filesystem capability request and response policy."
  (:require
   [clojure.string :as str]
   [seon.content-hash :as content-hash]))

(defn error
  "Return the flat filesystem failure envelope."
  ([operation message] (error operation message nil))
  ([operation message data]
   (cond-> {:seon.agent.fs/ok? false
            :seon.error/message message
            :seon.error/kind :user-input
            :seon.error/data (cond-> {:seon.agent.fs/op operation}
                               (seq data) (merge data))}
     (nil? data) identity)))

(defn path-error
  "Return a flat filesystem failure for `path`."
  ([operation path message] (path-error operation path message nil))
  ([operation path message data]
   (assoc (error operation message data) :seon.agent.fs/path path)))

(defn home-response
  "Interpret platform home-directory environment values as public data."
  [home user-profile]
  (if-let [path (some #(when-not (str/blank? %) %) [home user-profile])]
    path
    (error :home-dir
           (str "no home directory is configured; set the governing host "
                "configuration key :seon.config/fs-home-dir (or provide "
                "HOME/USERPROFILE before the pod starts).")
           {:seon.config/key :seon.config/fs-home-dir})))

(defn file-sha
  "Return the shared UTF-8 SHA-256 content identity."
  [content]
  (content-hash/sha-256 content))

(defn page-lines
  "Return an honest one-based line window over `content`."
  [content from-line max-lines]
  (let [lines (str/split content #"\n" -1)
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        total (count lines)
        from (max 1 (or from-line 1))
        start (min (dec from) total)
        end (if max-lines
              (min total (+ start (max 0 max-lines)))
              total)]
    {:seon.agent.fs/content (str/join "\n" (subvec lines start end))
     :seon.agent.fs/from-line from
     :seon.agent.fs/lines-returned (- end start)
     :seon.agent.fs/total-lines total}))

(defn write-decision
  "Return the policy decision for a filesystem write request."
  [{:seon.agent.fs/keys [path read-only? in-scope?]}]
  (cond
    read-only? (path-error :write path
                           "filesystem is read-only (:seon.agent.fs/read-only? true)")
    (not in-scope?) (path-error :write path "path is outside the configured allowlist")
    :else {:seon.agent.fs/ok? true}))
