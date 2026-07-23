(ns seon.agent.fs.leaf
  "Implement the JVM filesystem leaf for the portable fs capability."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader :as tools.reader]
   [clojure.tools.reader.reader-types :as reader-types]
   [seon.agent.fs.core :as core]
   [seon.agent.fs.match :as match]
   [seon.code :as code])
  (:import
   (java.io File)
   (java.nio.charset Charset StandardCharsets)
   (java.nio.file Files LinkOption Path Paths StandardOpenOption)
   (java.nio.file.attribute FileTime)))

(def ^:private no-links (make-array LinkOption 0))
(def ^:private write-options
  (into-array StandardOpenOption
              [StandardOpenOption/CREATE
               StandardOpenOption/TRUNCATE_EXISTING
               StandardOpenOption/WRITE]))

(defonce ^:private !config
  (atom {:seon.agent.fs/allowed-roots
         (some->> (System/getenv "SEON_FS_ROOT")
                  (#(str/split % (re-pattern
                                  (java.util.regex.Pattern/quote
                                   File/pathSeparator))))
                  (remove str/blank?)
                  vec)
         :seon.agent.fs/read-only?
         (= "1" (System/getenv "SEON_FS_READ_ONLY"))}))

(defn- env-enabled?
  [name]
  (let [value (System/getenv name)]
    (boolean (and (not (str/blank? value)) (not= "0" value)))))

(defn- locked? [] (env-enabled? "SEON_FS_LOCK"))
(defn- read-only? [] (boolean (:seon.agent.fs/read-only? @!config)))
(defn- allowed-roots [] (vec (:seon.agent.fs/allowed-roots @!config)))

(defn- absolute-path
  [path]
  (some-> (Paths/get (str path) (make-array String 0))
          .toAbsolutePath
          .normalize))

(defn- under-root?
  [^Path path root]
  (try
    (.startsWith path ^Path (absolute-path root))
    (catch Throwable _ false)))

(defn- in-scope?
  [path]
  (let [absolute (absolute-path path)
        roots (allowed-roots)]
    (and absolute (seq roots) (some #(under-root? absolute %) roots))))

(defn- denied
  [path reason]
  {:seon.agent.fs/ok? false
   :seon.agent.fs/path path
   :seon.agent.fs/error reason})

(defn- scope-denied
  [path]
  (assoc
   (denied path
           (if (empty? (allowed-roots))
             (str "seon.agent.fs has no allowed-roots configured "
                  "(default-deny). Call (seon.agent.fs/configure! "
                  "{:seon.agent.fs/allowed-roots [...]}) or set SEON_FS_ROOT.")
             (str "path outside allowed-roots " (pr-str (allowed-roots)))))
   :seon.agent.fs/denial :allowlist))

(defn- caught
  [path throwable]
  (denied path (or (ex-message throwable) (str throwable))))

(defn- charset
  [encoding]
  (Charset/forName (or encoding "utf-8")))

(defn- read-text
  [path encoding]
  (String. (Files/readAllBytes (absolute-path path)) (charset encoding)))

(defn- write-text!
  [path content encoding]
  (Files/write (absolute-path path)
               (.getBytes ^String content (charset encoding))
               write-options)
  nil)

(def ^:private clojure-source-suffixes
  #{".clj" ".cljs" ".cljc" ".edn" ".bb"})

(defn- syntax-error
  [path content]
  (when (some #(str/ends-with? path %) clojure-source-suffixes)
    (try
      (let [reader (reader-types/indexing-push-back-reader content)]
        (loop []
          (when-not (= ::eof
                       (tools.reader/read
                        {:eof ::eof :read-cond :allow :features #{:clj}}
                        reader))
            (recur))))
      nil
      (catch Throwable throwable
        (str "refused malformed Clojure source; prior file unchanged: "
             (ex-message throwable))))))

(defn configure!
  "Replace the active JVM filesystem grant."
  [updates]
  (if (locked?)
    {:seon.agent.fs/ok? false
     :seon.agent.fs/locked? true
     :seon.agent.fs/error
     (str "grants are locked by the host (SEON_FS_LOCK); read them with "
          "(seon.agent.fs/grants)")}
    (let [next (swap! !config merge
                      (select-keys updates
                                   [:seon.agent.fs/allowed-roots
                                    :seon.agent.fs/read-only?]))]
      (assoc next :seon.agent.fs/ok? true))))

(defn grants
  "Return the exact JVM filesystem grant."
  []
  {:seon.agent.fs/allowed-roots (allowed-roots)
   :seon.agent.fs/read-only? (read-only?)
   :seon.agent.fs/locked? (locked?)})

(defn read-file
  "Read a file through the JVM filesystem leaf."
  [{:seon.agent.fs/keys [path encoding from-line max-lines]}]
  (if-not (in-scope? path)
    (scope-denied path)
    (try
      (let [content (read-text path encoding)
            base {:seon.agent.fs/ok? true
                  :seon.agent.fs/path path
                  :seon.agent.fs/file-sha (core/file-sha content)}]
        (if (or from-line max-lines)
          (merge base (core/page-lines content from-line max-lines))
          (assoc base :seon.agent.fs/content content)))
      (catch Throwable throwable (caught path throwable)))))

(defn write-file
  "Write a file through the JVM filesystem leaf."
  [{:seon.agent.fs/keys [path content encoding]}]
  (cond
    (read-only?) (denied path "filesystem is read-only (:seon.agent.fs/read-only? true)")
    (not (in-scope? path)) (scope-denied path)
    :else
    (try
      (let [text (code/text content)]
        (if-let [error (syntax-error path text)]
          (denied path error)
          (do (write-text! path text encoding)
              {:seon.agent.fs/ok? true :seon.agent.fs/path path})))
      (catch Throwable throwable (caught path throwable)))))

(defn- content-lines
  [content]
  (let [lines (str/split content #"\n" -1)]
    (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)))

(defn- edit-result
  [path content new-content from replaced inserted]
  (let [lines (content-lines new-content)
        to (max from (dec (+ from inserted)))]
    (write-text! path new-content "utf-8")
    (merge
     {:seon.agent.fs/ok? true
      :seon.agent.fs/path path
      :seon.agent.fs/from-line from
      :seon.agent.fs/lines-replaced replaced
      :seon.agent.fs/lines-inserted inserted
      :seon.agent.fs/total-lines (count lines)}
     {:seon.agent.fs/context (match/preview lines [from to])
      :seon.agent.fs/context-from-line (max 1 (- from 3))
      :seon.agent.fs/truncated? false})))

(defn edit-file
  "Apply the frozen child line-range or exact-match edit contract."
  [{:seon.agent.fs/keys
    [path from-line to-line content old-string new-string encoding]}]
  (cond
    (read-only?) (denied path "filesystem is read-only (:seon.agent.fs/read-only? true)")
    (not (in-scope? path)) (scope-denied path)
    :else
    (try
      (let [original (read-text path encoding)
            lines (content-lines original)]
        (cond
          (and from-line to-line (some? content))
          (if (or (< from-line 1) (< to-line from-line) (> to-line (count lines)))
            (denied path "line range is outside the file")
            (let [replacement (if (= "" (code/text content))
                                []
                                (content-lines (code/text content)))
                  result-lines (into (into (subvec lines 0 (dec from-line))
                                           replacement)
                                     (subvec lines to-line))
                  result (str/join "\n" result-lines)]
              (edit-result path original result from-line
                           (inc (- to-line from-line)) (count replacement))))

          (and (some? old-string) (some? new-string))
          (let [positions (loop [from 0 positions []]
                            (if-let [index (str/index-of original old-string from)]
                              (recur (+ index (count old-string))
                                     (conj positions index))
                              positions))]
            (if-not (= 1 (count positions))
              (denied path (str "old-string is "
                                (if (empty? positions) "not found"
                                    (str "AMBIGUOUS (" (count positions) " matches)"))))
              (let [index (first positions)
                    result (str (subs original 0 index) new-string
                                (subs original (+ index (count old-string))))
                    line (inc (count (re-seq #"\n" (subs original 0 index))))]
                (edit-result path original result line
                             (inc (count (re-seq #"\n" old-string)))
                             (inc (count (re-seq #"\n" new-string)))))))

          :else
          (denied path
                  (str "no edit given — pass from-line/to-line/content "
                       "(line range) or old-string/new-string (exact match)"))))
      (catch Throwable throwable (caught path throwable)))))

(defn list-dir
  "List one directory through the JVM filesystem leaf."
  [{:seon.agent.fs/keys [path]}]
  (if-not (in-scope? path)
    (scope-denied path)
    (try
      {:seon.agent.fs/ok? true
       :seon.agent.fs/path path
       :seon.agent.fs/entries
       (->> (.listFiles (io/file path)) (map #(.getName ^File %)) vec)}
      (catch Throwable throwable (caught path throwable)))))

(defn stat
  "Read path metadata through the JVM filesystem leaf."
  [{:seon.agent.fs/keys [path]}]
  (if-not (in-scope? path)
    (scope-denied path)
    (try
      (let [file (io/file path)]
        (if-not (.exists file)
          (denied path (str "path does not exist: " path))
          {:seon.agent.fs/ok? true
           :seon.agent.fs/path path
           :seon.agent.fs/dir? (.isDirectory file)
           :seon.agent.fs/file? (.isFile file)
           :seon.agent.fs/mtime (java.util.Date. (.lastModified file))}))
      (catch Throwable throwable (caught path throwable)))))

(defn file-exists?
  "Return whether the JVM leaf can stat a path."
  [request]
  (true? (:seon.agent.fs/ok? (stat request))))

(defn home-dir
  "Return the JVM home directory or the shared steering error."
  []
  (core/home-response (System/getenv "HOME") (System/getenv "USERPROFILE")))

(defn- glob-pattern
  [glob]
  (when glob
    (.getPathMatcher (java.nio.file.FileSystems/getDefault)
                     (str "glob:" glob))))

(defn walk-dir
  "Walk one granted directory through the JVM filesystem leaf."
  [{:seon.agent.fs/keys [path match-ext glob skip-hidden sort max-results]
    :or {skip-hidden true max-results 5000}}]
  (if-not (in-scope? path)
    (scope-denied path)
    (try
      (let [root (absolute-path path)
            matcher (glob-pattern glob)
            paths (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
                    (->> (.iterator stream)
                         iterator-seq
                         (filter #(Files/isRegularFile ^Path % no-links))
                         (remove #(and skip-hidden
                                       (some (fn [part]
                                               (str/starts-with? (str part) "."))
                                             (iterator-seq (.iterator ^Path (.relativize root %))))))
                         (filter #(or (nil? match-ext)
                                      (str/ends-with? (str %) match-ext)))
                         (filter #(or (nil? matcher)
                                      (.matches matcher (.relativize root ^Path %))
                                      (.matches matcher (.getFileName ^Path %))))
                         (map str)
                         (take (inc max-results))
                         vec))
            truncated? (> (count paths) max-results)
            selected (vec (take max-results paths))
            selected (if (= :mtime sort)
                       (->> selected
                            (sort-by #(- (.toMillis
                                         ^FileTime
                                         (Files/getLastModifiedTime
                                          (absolute-path %) no-links))))
                            vec)
                       selected)]
        (cond-> {:seon.agent.fs/ok? true
                 :seon.agent.fs/path path
                 :seon.agent.fs/entries selected
                 :seon.agent.fs/total-found (count selected)
                 :seon.agent.fs/truncated? truncated?}
          truncated?
          (assoc :seon.agent.fs/hint
                 (str "hit the " max-results "-result cap — the walk STOPPED "
                      "here, so :seon.agent.fs/total-found is not a true total."))))
      (catch Throwable throwable (caught path throwable)))))

(def default-view-lines 100)

(defn view
  "Return the JVM leaf's bounded line-numbered edit view."
  [{:seon.agent.fs/keys [path from-line max-lines encoding]}]
  (let [response (read-file {:seon.agent.fs/path path
                             :seon.agent.fs/encoding (or encoding "utf-8")})]
    (if-not (:seon.agent.fs/ok? response)
      response
      (let [content (:seon.agent.fs/content response)
            lines (content-lines content)
            total (count lines)
            from (max 1 (or from-line 1))
            start (min (dec from) total)
            end (min total (+ start (max 0 (or max-lines default-view-lines))))]
        {:seon.agent.fs/ok? true
         :seon.agent.fs/path path
         :seon.agent.fs/file-sha (:seon.agent.fs/file-sha response)
         :seon.agent.fs/from-line from
         :seon.agent.fs/lines-returned (- end start)
         :seon.agent.fs/total-lines total
         :seon.agent.fs/content
         (match/number-lines (subvec lines start end) from)}))))

(defn- anchored-error
  [path message & [data]]
  (cond-> {:seon.agent.fs/ok? false
           :seon.agent.fs/path path
           :seon.error/message message}
    (seq data) (assoc :seon.error/data data)))

(defn replace!
  "Apply one deterministic anchored replacement through the JVM leaf."
  [{:seon.agent.fs/keys
    [path find replace expected-count all? near file-sha encoding]}]
  (cond
    (read-only?) (anchored-error path "filesystem is read-only (:seon.agent.fs/read-only? true)")
    (not (in-scope? path)) (anchored-error path (:seon.agent.fs/error (scope-denied path)))
    :else
    (try
      (let [content (read-text path encoding)
            actual (core/file-sha content)]
        (if (and file-sha (not= file-sha actual))
          (assoc (anchored-error path "file changed since your read"
                                 {:seon.agent.fs/file-sha actual})
                 :seon.agent.fs/file-sha actual)
          (let [decision
                (match/decide
                 (cond-> {:seon.agent.fs.match/content content
                          :seon.agent.fs.match/find (code/text find)
                          :seon.agent.fs.match/replace (code/text replace)}
                   expected-count
                   (assoc :seon.agent.fs.match/expected-count expected-count)
                   all? (assoc :seon.agent.fs.match/all? all?)
                   near (assoc :seon.agent.fs.match/near near)))]
            (if-not (= :apply (:seon.agent.fs.match/action decision))
              (anchored-error path (:seon.agent.fs.match/message decision)
                              {:seon.agent.fs.match/reason
                               (:seon.agent.fs.match/reason decision)
                               :seon.agent.fs.match/candidates
                               (:seon.agent.fs.match/candidates decision)})
              (let [new-content (:seon.agent.fs.match/new-content decision)
                    range (:seon.agent.fs.match/range-after decision)]
                (if-let [error (syntax-error path new-content)]
                  (anchored-error path error)
                  (do
                    (write-text! path new-content encoding)
                    (cond->
                     {:seon.agent.fs/ok? true
                      :seon.agent.fs/path path
                      :seon.agent.fs/file-sha (core/file-sha new-content)
                      :seon.agent.fs/range-after range
                      :seon.agent.fs/lines-added
                      (:seon.agent.fs.match/lines-added decision)
                      :seon.agent.fs/lines-removed
                      (:seon.agent.fs.match/lines-removed decision)
                      :seon.agent.fs/excerpt
                      (match/preview (content-lines new-content) range)}
                      (seq (:seon.agent.fs.match/normalizations decision))
                      (assoc :seon.agent.fs/normalizations
                             (:seon.agent.fs.match/normalizations decision))))))))))
      (catch Throwable throwable
        (anchored-error path (or (ex-message throwable) (str throwable)))))))

(defn insert!
  "Insert content at one line anchor through the JVM leaf."
  [{:seon.agent.fs/keys [path content after-line before-line encoding]}]
  (cond
    (read-only?) (anchored-error path "filesystem is read-only (:seon.agent.fs/read-only? true)")
    (not (in-scope? path)) (anchored-error path (:seon.agent.fs/error (scope-denied path)))
    (= (some? after-line) (some? before-line))
    (anchored-error path
                    (str "pass EXACTLY ONE of :seon.agent.fs/after-line or "
                         ":seon.agent.fs/before-line"))
    :else
    (try
      (let [original (read-text path encoding)
            lines (content-lines original)
            index (if (some? after-line) after-line (dec before-line))
            insertion (content-lines (code/text content))]
        (if (or (< index 0) (> index (count lines)))
          (anchored-error path "line anchor is outside the file"
                          {:seon.agent.fs/total-lines (count lines)})
          (let [new-lines (into (into (subvec lines 0 index) insertion)
                                (subvec lines index))
                new-content (cond-> (str/join "\n" new-lines)
                              (str/ends-with? original "\n") (str "\n"))
                range [(inc index) (+ index (count insertion))]]
            (if-let [error (syntax-error path new-content)]
              (anchored-error path error)
              (do
                (write-text! path new-content encoding)
                {:seon.agent.fs/ok? true
                 :seon.agent.fs/path path
                 :seon.agent.fs/file-sha (core/file-sha new-content)
                 :seon.agent.fs/range-after range
                 :seon.agent.fs/lines-added (count insertion)
                 :seon.agent.fs/lines-removed 0
                 :seon.agent.fs/excerpt (match/preview new-lines range)})))))
      (catch Throwable throwable
        (anchored-error path (or (ex-message throwable) (str throwable)))))))

(def public-functions
  {'configure! configure!
   'grants grants
   'read-file read-file
   'write-file write-file
   'edit-file edit-file
   'list-dir list-dir
   'stat stat
   'file-exists? file-exists?
   'home-dir home-dir
   'walk-dir walk-dir
   'view view
   'replace! replace!
   'insert! insert!})
