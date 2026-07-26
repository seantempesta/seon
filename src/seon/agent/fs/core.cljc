(ns seon.agent.fs.core
  "Pure filesystem capability request and response policy."
  (:require
   [clojure.string :as str]
   [seon.agent.fs.match]
   [seon.code]
   [seon.content-hash :as content-hash]
   [seon.schema :as schema]))

(schema/register! :seon.agent.fs/path :string)
(schema/register! :seon.agent.fs/encoding :string)
(schema/register! :seon.agent.fs/content :seon.code/value)
(schema/register! :seon.agent.fs/ok? :boolean)
(schema/register! :seon.agent.fs/error :string)
(schema/register! :seon.agent.fs/denial [:enum :allowlist])
(schema/register! :seon.agent.fs/entries [:vector :string])
(schema/register! :seon.agent.fs/dir? :boolean)
(schema/register! :seon.agent.fs/file? :boolean)
(schema/register! :seon.agent.fs/mtime :inst)
(schema/register! :seon.agent.fs/allowed-roots [:vector :string])
(schema/register! :seon.agent.fs/read-only? :boolean)
(schema/register! :seon.agent.fs/locked? :boolean)
(schema/register! :seon.agent.fs/from-line :int)
(schema/register! :seon.agent.fs/max-lines :int)
(schema/register! :seon.agent.fs/lines-returned :int)
(schema/register! :seon.agent.fs/total-lines :int)
(schema/register! :seon.agent.fs/file-sha :string)
(schema/register! :seon.agent.fs/to-line :int)
(schema/register! :seon.agent.fs/old-string :string)
(schema/register! :seon.agent.fs/new-string :string)
(schema/register! :seon.agent.fs/lines-replaced :int)
(schema/register! :seon.agent.fs/lines-inserted :int)
(schema/register! :seon.agent.fs/context :string)
(schema/register! :seon.agent.fs/context-from-line :int)
(schema/register! :seon.agent.fs/match-ext :string)
(schema/register! :seon.agent.fs/glob :string)
(schema/register! :seon.agent.fs/skip-hidden :boolean)
(schema/register! :seon.agent.fs/max-results :int)
(schema/register! :seon.agent.fs/total-found :int)
(schema/register! :seon.agent.fs/truncated? :boolean)
(schema/register! :seon.agent.fs/hint :string)
(schema/register! :seon.agent.fs/sort [:enum :name :mtime])
(schema/register! :seon.agent.fs/range-after
                  :seon.agent.fs.match/range)
(schema/register! :seon.agent.fs/lines-added :int)
(schema/register! :seon.agent.fs/lines-removed :int)
(schema/register! :seon.agent.fs/excerpt :string)
(schema/register! :seon.agent.fs/normalizations
                  :seon.agent.fs.match/normalizations)
(schema/register! :seon.agent.fs/after-line :int)
(schema/register! :seon.agent.fs/before-line :int)
(schema/register! :seon.agent.fs/all? :seon.agent.fs.match/all?)

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
