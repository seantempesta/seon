(ns seon.dev.release
  "Content-addressed inventory for a relocatable Seon release directory."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption]
           [java.security MessageDigest]))

(def current-version 1)

(def ^:private sha-256-schema [:re #"[0-9a-f]{64}"])

(def release-member-schema
  [:map {:closed true}
   [:seon.dev.release/member :qualified-keyword]
   [:seon.dev.release/path :string]
   [:seon.dev.release/sha-256 sha-256-schema]])

(def release-identity-schema
  [:map {:closed true}
   [:seon.dev.release/bun-version :string]
   [:seon.dev.release/bun-revision [:re #"[0-9a-f]{40}"]]
   [:seon.dev.release/database-protocol-version :int]
   [:seon.dev.release/execution-protocol-version :int]
   [:seon.dev.release/bun-member :qualified-keyword]
   [:seon.dev.release/writer-member :qualified-keyword]
   [:seon.dev.release/pod-member :qualified-keyword]
   [:seon.dev.release/execution-member :qualified-keyword]
   [:seon.dev.release/runtime-assets-member :qualified-keyword]
   [:seon.dev.release/program-source-member :qualified-keyword]])

(def release-manifest-schema
  [:map {:closed true}
   [:seon.dev.release/version [:= current-version]]
   [:seon.dev.release/identity release-identity-schema]
   [:seon.dev.release/members [:vector release-member-schema]]
   [:seon.dev.release/application-sha-256 sha-256-schema]])

(def ^:private required-member-identity-keys
  [:seon.dev.release/bun-member
   :seon.dev.release/writer-member
   :seon.dev.release/pod-member
   :seon.dev.release/execution-member
   :seon.dev.release/runtime-assets-member
   :seon.dev.release/program-source-member])

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- update-text! [^MessageDigest digest value]
  (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
  (.update digest (byte 0)))

(defn- update-file! [^MessageDigest digest path]
  (with-open [stream (io/input-stream (str path))]
    (let [buffer (byte-array 65536)]
      (loop []
        (let [read-count (.read stream buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur)))))))

(defn- normalized-relative-path [path]
  (when (and (string? path)
             (not (str/blank? path))
             (not (str/includes? path "\\"))
             (not (str/starts-with? path "/"))
             (not (re-find #"^[A-Za-z]:" path)))
    (let [segments (str/split path #"/" -1)]
      (when (and (every? #(not (contains? #{"" "." ".."} %)) segments)
                 (= path (str/join "/" segments)))
        path))))

(defn- manifest-error [message data]
  (throw (ex-info message (assoc data :seon.dev.release/error true))))

(defn- canonical-members [members]
  (->> members
       (sort-by (juxt (comp str :seon.dev.release/member)
                      :seon.dev.release/path))
       vec))

(defn- application-sha-256 [identity members]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (update-text! digest current-version)
    (doseq [[key value] (sort-by (comp str key) identity)]
      (update-text! digest key)
      (update-text! digest value))
    (doseq [{:seon.dev.release/keys [member path sha-256]}
            (canonical-members members)]
      (update-text! digest member)
      (update-text! digest path)
      (update-text! digest sha-256))
    (bytes->hex (.digest digest))))

(defn validate-manifest!
  "Validate the closed, relocatable release manifest as ordinary data."
  {:malli/schema [:=> [:cat :map] release-manifest-schema]}
  [manifest]
  (when-not (m/validate release-manifest-schema manifest)
    (manifest-error
     "The release manifest is invalid."
     {:seon.dev.release/explanation
      (mapv #(select-keys % [:path :in :type])
            (:errors (m/explain release-manifest-schema manifest)))}))
  (let [members (:seon.dev.release/members manifest)
        names (map :seon.dev.release/member members)
        paths (map :seon.dev.release/path members)
        identity (:seon.dev.release/identity manifest)
        declared-members (set names)
        required-members (map identity required-member-identity-keys)]
    (when-not (= members (canonical-members members))
      (manifest-error "Release members are not in canonical order." {}))
    (when-not (= (count names) (count (distinct names)))
      (manifest-error "Release member names must be unique." {}))
    (when-not (= (count paths) (count (distinct paths)))
      (manifest-error "Release member paths must be unique." {}))
    (when-not (every? declared-members required-members)
      (manifest-error "A required runtime member is not declared." {}))
    (doseq [path paths]
      (when-not (normalized-relative-path path)
        (manifest-error
         "A release member path is not a normalized relative path."
         {:seon.dev.release/path path})))
    (when-not (= (:seon.dev.release/application-sha-256 manifest)
                 (application-sha-256 identity members))
      (manifest-error "The release application digest does not match." {})))
  manifest)

(defn- symbolic-link? [path]
  (Files/isSymbolicLink (.toPath (io/file (str path)))))

(defn- ensure-package-root! [package-root]
  (let [root (fs/absolutize (fs/path package-root))]
    (when (or (not (fs/directory? root)) (symbolic-link? root))
      (manifest-error
       "The release package root must be a real directory."
       {:seon.dev.release/package-root (str root)}))
    (fs/canonicalize root)))

(defn- member-path! [root relative-path]
  (let [segments (str/split relative-path #"/")
        candidates (rest (reductions fs/path root segments))]
    (doseq [candidate candidates]
      (when (symbolic-link? candidate)
        (manifest-error
         "A release member path contains a symbolic link."
         {:seon.dev.release/path relative-path})))
    (let [path (last candidates)]
      (when-not (Files/exists (.toPath (io/file (str path)))
                              (make-array LinkOption 0))
        (manifest-error "A release member is missing."
                        {:seon.dev.release/path relative-path}))
      path)))

(defn- relative-entry [root path]
  (-> (.relativize (.toPath (io/file (str root)))
                   (.toPath (io/file (str path))))
      str
      (str/replace java.io.File/separator "/")))

(defn- tree-entries! [member-root]
  (->> (file-seq (io/file (str member-root)))
       rest
       (map (fn [file]
              (let [path (fs/path file)]
                (when (symbolic-link? path)
                  (manifest-error
                   "A release member contains a symbolic link."
                   {:seon.dev.release/path
                    (relative-entry member-root path)}))
                path)))
       (sort-by #(relative-entry member-root %))))

(defn- member-sha-256 [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (cond
      (fs/regular-file? path)
      (do (update-text! digest :file)
          (update-file! digest path))

      (fs/directory? path)
      (do
        (update-text! digest :directory)
        (doseq [entry (tree-entries! path)]
          (let [relative (relative-entry path entry)]
            (cond
              (fs/directory? entry)
              (do (update-text! digest :directory)
                  (update-text! digest relative))

              (fs/regular-file? entry)
              (do (update-text! digest :file)
                  (update-text! digest relative)
                  (update-file! digest entry))

              :else
              (manifest-error
               "A release member contains an unsupported filesystem entry."
               {:seon.dev.release/path relative})))))

      :else
      (manifest-error "A release member is not a regular file or directory."
                      {:seon.dev.release/path (str path)}))
    (bytes->hex (.digest digest))))

(defn- verify-complete-inventory! [root members]
  (let [member-paths (mapv :seon.dev.release/path members)
        directory-paths
        (into #{}
              (filter #(fs/directory? (fs/path root %)))
              member-paths)
        declared-or-covered?
        (fn [relative]
          (or (= "release.edn" relative)
              (some #(or (= relative %)
                         (str/starts-with? relative (str % "/"))
                         (str/starts-with? % (str relative "/")))
                    directory-paths)
              (some #(or (= relative %)
                         (str/starts-with? % (str relative "/")))
                    member-paths)))]
    (doseq [path (rest (file-seq (io/file (str root))))
            :let [relative (relative-entry root path)]]
      (when (symbolic-link? path)
        (manifest-error "A release package contains a symbolic link."
                        {:seon.dev.release/path relative}))
      (when-not (declared-or-covered? relative)
        (manifest-error "A release package entry is not declared."
                        {:seon.dev.release/path relative}))))
  members)

(defn create-manifest
  "Create a deterministic manifest from named package members."
  {:malli/schema
   [:=> [:cat :string [:map-of :qualified-keyword :string]
         release-identity-schema]
    release-manifest-schema]}
  [package-root members identity]
  (doseq [[member path] members]
    (when-not (and (qualified-keyword? member)
                   (normalized-relative-path path))
      (manifest-error "A release member declaration is invalid."
                      {:seon.dev.release/member member
                       :seon.dev.release/path path})))
  (when-not (every? (set (keys members))
                    (map identity required-member-identity-keys))
    (manifest-error "A required runtime member is not declared." {}))
  (let [root (ensure-package-root! package-root)
        entries (canonical-members
                 (mapv (fn [[member path]]
                         {:seon.dev.release/member member
                          :seon.dev.release/path path
                          :seon.dev.release/sha-256
                          (member-sha-256 (member-path! root path))})
                       members))]
    (verify-complete-inventory! root entries)
    (validate-manifest!
     {:seon.dev.release/version current-version
      :seon.dev.release/identity identity
      :seon.dev.release/members entries
      :seon.dev.release/application-sha-256
      (application-sha-256 identity entries)})))

(defn verify-package!
  "Verify every immutable member before admitting a release directory."
  {:malli/schema [:=> [:cat :string :map] release-manifest-schema]}
  [package-root manifest]
  (validate-manifest! manifest)
  (let [root (ensure-package-root! package-root)]
    (verify-complete-inventory! root (:seon.dev.release/members manifest))
    (doseq [{:seon.dev.release/keys [path sha-256]}
            (:seon.dev.release/members manifest)]
      (when-not (= sha-256 (member-sha-256 (member-path! root path)))
        (manifest-error "A release member digest does not match."
                        {:seon.dev.release/path path}))))
  manifest)

(defn package-path
  "Resolve one verified release member beneath its package root."
  {:malli/schema [:=> [:cat :string :map :qualified-keyword] :string]}
  [package-root manifest member]
  (verify-package! package-root manifest)
  (let [relative-path
        (some (fn [entry]
                (when (= member (:seon.dev.release/member entry))
                  (:seon.dev.release/path entry)))
              (:seon.dev.release/members manifest))]
    (when-not relative-path
      (manifest-error "The requested release member is not declared."
                      {:seon.dev.release/member member}))
    (str (member-path! (ensure-package-root! package-root) relative-path))))

(defn read-manifest!
  "Read and verify a manifest relative to its containing package root."
  {:malli/schema [:=> [:cat :string] release-manifest-schema]}
  [manifest-path]
  (let [manifest (edn/read-string (slurp manifest-path))]
    ;; Manifest data and lexical paths are validated before its containing
    ;; directory becomes the authority for any member resolution.
    (validate-manifest! manifest)
    (verify-package! (str (fs/parent (fs/absolutize manifest-path))) manifest)))
