(ns seon.dev.release
  "Content-addressed inventory for a relocatable Seon release directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.db.protocol :as database-protocol])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption]
           [java.security MessageDigest]))

(def current-version 1)

(def ^:private patched-bun-revision
  "d8ecf098572e2b8265b23e40c04efb4067e516cc")

(def ^:private execution-protocol-version 3)

(def ^:private babashka-version "1.12.218")

(def ^:private babashka-source-revision
  "0fb349c414e717800be775ba9cb77c95a9eb700d")

(def ^:private babashka-assets
  {["linux" "aarch64"]
   {:seon.dev.release/asset
    "babashka-1.12.218-linux-aarch64-static.tar.gz"
    :seon.dev.release/sha-256
    "e9e9190afb0dd33abbcd3aa6c1382184a88a5498800324719be3be6e1aa68302"}
   ["linux" "amd64"]
   {:seon.dev.release/asset
    "babashka-1.12.218-linux-amd64-static.tar.gz"
    :seon.dev.release/sha-256
    "7bd028cc794732ffde3da31ce4379840893c8e54f1046f92a8dfc4f4b3cddaf8"}
   ["macos" "aarch64"]
   {:seon.dev.release/asset "babashka-1.12.218-macos-aarch64.tar.gz"
    :seon.dev.release/sha-256
    "5bc992f39692b707403fc322e860fc82017da7de4a84a32267abb4d50a0c5f9d"}
   ["macos" "amd64"]
   {:seon.dev.release/asset "babashka-1.12.218-macos-amd64.tar.gz"
    :seon.dev.release/sha-256
    "2b7640a919b79406142b12c488ee83f7ba070c04b82bee8f74ad4eab074ddaeb"}})

(def ^:private package-members
  {:seon.release.member/bun "runtime/bun"
   :seon.release.member/writer "runtime/writer.jar"
   :seon.release.member/pod "runtime/pod.js"
   :seon.release.member/execution "runtime/execution.js"
   :seon.release.member/runtime-assets "runtime-root"
   :seon.release.member/program-source "runtime/program-sources.edn"
   :seon.release.member/babashka "runtime/bb"
   :seon.release.member/operator "runtime/operator.jar"
   :seon.release.member/launcher "bin/seon"
   :seon.release.member/config "config/system.edn"
   :seon.release.member/babashka-license
   "THIRD_PARTY_LICENSES/babashka-EPL-1.0.txt"
   :seon.release.member/node-modules "node_modules"
   :seon.release.member/package-json "package.json"
   :seon.release.member/bun-lock "bun.lock"
   :seon.release.member/license "LICENSE"})

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
   [:seon.dev.release/babashka-version :string]
   [:seon.dev.release/babashka-source-revision [:re #"[0-9a-f]{40}"]]
   [:seon.dev.release/babashka-asset :string]
   [:seon.dev.release/babashka-asset-sha-256 sha-256-schema]
   [:seon.dev.release/database-protocol-version :int]
   [:seon.dev.release/execution-protocol-version :int]
   [:seon.dev.release/bun-member :qualified-keyword]
   [:seon.dev.release/writer-member :qualified-keyword]
   [:seon.dev.release/pod-member :qualified-keyword]
   [:seon.dev.release/execution-member :qualified-keyword]
   [:seon.dev.release/runtime-assets-member :qualified-keyword]
   [:seon.dev.release/program-source-member :qualified-keyword]
   [:seon.dev.release/babashka-member :qualified-keyword]
   [:seon.dev.release/operator-member :qualified-keyword]
   [:seon.dev.release/launcher-member :qualified-keyword]
   [:seon.dev.release/config-member :qualified-keyword]
   [:seon.dev.release/babashka-license-member :qualified-keyword]])

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
   :seon.dev.release/program-source-member
   :seon.dev.release/babashka-member
   :seon.dev.release/operator-member
   :seon.dev.release/launcher-member
   :seon.dev.release/config-member
   :seon.dev.release/babashka-license-member])

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

(defn- copy-file! [source target]
  (when-not (fs/regular-file? source)
    (manifest-error "A release input file is missing."
                    {:seon.dev.release/path (str source)}))
  (fs/create-dirs (fs/parent target))
  (fs/copy source target {:replace-existing true})
  target)

(defn- copy-directory! [source target]
  (when-not (fs/directory? source)
    (manifest-error "A release input directory is missing."
                    {:seon.dev.release/path (str source)}))
  (fs/create-dirs (fs/parent target))
  (fs/copy-tree source target {:replace-existing true})
  target)

(defn- remove-symbolic-links! [root]
  (doseq [file (reverse (file-seq (io/file (str root))))
          :when (symbolic-link? file)]
    (fs/delete file))
  root)

(defn- runtime-identity [bun-version babashka-asset]
  {:seon.dev.release/bun-version bun-version
   :seon.dev.release/bun-revision patched-bun-revision
   :seon.dev.release/babashka-version babashka-version
   :seon.dev.release/babashka-source-revision babashka-source-revision
   :seon.dev.release/babashka-asset
   (:seon.dev.release/asset babashka-asset)
   :seon.dev.release/babashka-asset-sha-256
   (:seon.dev.release/sha-256 babashka-asset)
   :seon.dev.release/database-protocol-version database-protocol/current-version
   :seon.dev.release/execution-protocol-version execution-protocol-version
   :seon.dev.release/bun-member :seon.release.member/bun
   :seon.dev.release/writer-member :seon.release.member/writer
   :seon.dev.release/pod-member :seon.release.member/pod
   :seon.dev.release/execution-member :seon.release.member/execution
   :seon.dev.release/runtime-assets-member
   :seon.release.member/runtime-assets
   :seon.dev.release/program-source-member
   :seon.release.member/program-source
   :seon.dev.release/babashka-member :seon.release.member/babashka
   :seon.dev.release/operator-member :seon.release.member/operator
   :seon.dev.release/launcher-member :seon.release.member/launcher
   :seon.dev.release/config-member :seon.release.member/config
   :seon.dev.release/babashka-license-member
   :seon.release.member/babashka-license})

(defn assemble-package!
  "Assemble and verify one source-free release from built runtime inputs."
  {:malli/schema
   [:=>
    [:cat [:map {:closed true}
           [::package-root :string]
           [::bun :string]
           [::bun-version :string]
           [::writer :string]
           [::pod :string]
           [::execution :string]
           [::bootstrap :string]
           [::public-assets :string]
           [::program-source :string]
           [::babashka :string]
           [::babashka-asset :map]
           [::operator :string]
           [::launcher :string]
           [::config :string]
           [::babashka-license :string]
           [::node-modules :string]
           [::package-json :string]
           [::bun-lock :string]
           [::license :string]]]
    release-manifest-schema]}
  [{::keys [package-root bun bun-version writer pod execution bootstrap
            public-assets program-source babashka babashka-asset operator
            launcher config babashka-license node-modules package-json bun-lock
            license]}]
  (let [root (fs/path package-root)
        runtime (fs/path root "runtime")
        runtime-root (fs/path root "runtime-root")]
    (when (fs/exists? root)
      (manifest-error "The release staging directory already exists."
                      {::package-root (str root)}))
    (fs/create-dirs runtime)
    (copy-file! bun (fs/path runtime "bun"))
    (.setExecutable (io/file (str (fs/path runtime "bun"))) true false)
    (copy-file! writer (fs/path runtime "writer.jar"))
    (copy-file! pod (fs/path runtime "pod.js"))
    (copy-file! execution (fs/path runtime "execution.js"))
    (copy-file! program-source (fs/path runtime "program-sources.edn"))
    (copy-file! babashka (fs/path runtime "bb"))
    (.setExecutable (io/file (str (fs/path runtime "bb"))) true false)
    (copy-file! operator (fs/path runtime "operator.jar"))
    (copy-file! launcher (fs/path root "bin/seon"))
    (.setExecutable (io/file (str (fs/path root "bin/seon"))) true false)
    (copy-file! config (fs/path root "config/system.edn"))
    (copy-file! babashka-license
                (fs/path root "THIRD_PARTY_LICENSES/babashka-EPL-1.0.txt"))
    (copy-directory! bootstrap (fs/path runtime-root "out/bootstrap"))
    (copy-directory! public-assets
                     (fs/path runtime-root "resources/public"))
    (copy-directory! node-modules (fs/path root "node_modules"))
    (remove-symbolic-links! (fs/path root "node_modules"))
    (let [command-links (fs/path root "node_modules/.bin")]
      (when (fs/exists? command-links)
        (fs/delete-tree command-links {:force true})))
    (copy-file! package-json (fs/path root "package.json"))
    (copy-file! bun-lock (fs/path root "bun.lock"))
    (copy-file! license (fs/path root "LICENSE"))
    (let [manifest (create-manifest (str root) package-members
                                    (runtime-identity bun-version
                                                      babashka-asset))
          manifest-path (fs/path root "release.edn")]
      (spit (str manifest-path) (str (pr-str manifest) "\n"))
      (read-manifest! (str manifest-path)))))

(defn- run! [root environment & command]
  (let [result (process/shell {:dir (str root)
                               :env environment
                               :out :inherit
                               :err :inherit
                               :continue true
                               :cmd (vec command)})]
    (when-not (zero? (:exit result))
      (manifest-error "A release build command failed."
                      {::command (vec command) ::exit (:exit result)}))
    result))

(defn- inspect-bun! [root environment bun]
  (let [result (process/shell
                {:dir (str root) :env environment :out :string :err :string
                 :continue true
                 :cmd [bun "-e"
                       "process.stdout.write(JSON.stringify({version:Bun.version,revision:Bun.revision}))"]})
        identity (when (zero? (:exit result))
                   (json/parse-string (:out result) true))]
    (when-not (= patched-bun-revision (:revision identity))
      (manifest-error "The release requires the maintained patched Bun revision."
                      {::bun bun ::reported-identity identity
                       ::required-revision patched-bun-revision}))
    identity))

(defn- production-package-json! [source target]
  (let [value (json/parse-string (slurp (str source)) true)
        release-value (-> value
                          (assoc :license "AGPL-3.0-only")
                          (dissoc :devDependencies :scripts :directories
                                  :main :test))]
    (spit (str target) (str (json/generate-string release-value {:pretty true})
                            "\n"))))

(defn- raw-file-sha-256 [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (update-file! digest path)
    (bytes->hex (.digest digest))))

(defn- host-platform []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        architecture (str/lower-case (System/getProperty "os.arch"))
        os (cond
             (str/includes? os-name "mac") "macos"
             (str/includes? os-name "linux") "linux"
             :else nil)
        architecture (cond
                       (contains? #{"aarch64" "arm64"} architecture)
                       "aarch64"
                       (contains? #{"amd64" "x86_64"} architecture)
                       "amd64"
                       :else nil)]
    (or (get babashka-assets [os architecture])
        (manifest-error
         "This host has no maintained Babashka release asset."
         {:seon.dev.release/os os-name
          :seon.dev.release/architecture
          (System/getProperty "os.arch")}))))

(defn- download-babashka! [build-root environment]
  (let [{:seon.dev.release/keys [asset sha-256] :as identity}
        (host-platform)
        archive (fs/path build-root asset)
        extracted (fs/path build-root "babashka")
        url (str "https://github.com/babashka/babashka/releases/download/v"
                 babashka-version "/" asset)]
    (with-open [input (io/input-stream url)
                output (io/output-stream (str archive))]
      (io/copy input output))
    (when-not (= sha-256 (raw-file-sha-256 archive))
      (manifest-error "The Babashka release asset digest does not match."
                      {:seon.dev.release/asset asset
                       :seon.dev.release/required-sha-256 sha-256
                       :seon.dev.release/actual-sha-256
                       (raw-file-sha-256 archive)}))
    (fs/create-dirs extracted)
    (run! build-root environment "tar" "-xzf" (str archive)
          "-C" (str extracted))
    (let [executable (fs/path extracted "bb")
          result (process/shell {:continue true :out :string :err :string
                                 :cmd [(str executable) "--version"]})]
      (when-not (and (zero? (:exit result))
                     (= (str "babashka v" babashka-version)
                        (str/trim (:out result))))
        (manifest-error "The extracted Babashka identity does not match."
                        {:seon.dev.release/asset asset
                         :seon.dev.release/reported-version
                         (str/trim (str (:out result) (:err result)))}))
      {:seon.dev.release/executable (str executable)
       :seon.dev.release/identity identity})))

(defn- operator-source? [path]
  (let [name (str (fs/file-name path))]
    (and (fs/regular-file? path)
         (not= "mcp.clj" name)
         (or (str/ends-with? name ".clj")
             (str/ends-with? name ".cljc")))))

(defn- stage-operator-source! [root target]
  (doseq [source-root [(fs/path root "script") (fs/path root "src")]
          source (file-seq (io/file (str source-root)))
          :when (operator-source? source)]
    (let [relative (.relativize (.toPath (io/file (str source-root)))
                                (.toPath (io/file (str source))))]
      (copy-file! source (fs/path target (str relative)))))
  target)

(defn- build-operator! [root build-root environment babashka]
  (let [source-root (fs/path build-root "operator-source")
        config-path (fs/path build-root "operator-bb.edn")
        output (fs/path build-root "operator.jar")
        dependency-config (edn/read-string (slurp (str (fs/path root "bb.edn"))))]
    (stage-operator-source! root source-root)
    (spit (str config-path)
          (str (pr-str {:paths ["operator-source"]
                        :deps (dissoc (:deps dependency-config)
                                      'nrepl/bencode)}) "\n"))
    (run! root environment babashka "--config" (str config-path)
          "uberjar" (str output) "-m" "seon.dev.cli")
    output))

(defn- write-launcher! [path]
  (fs/create-dirs (fs/parent path))
  (spit (str path)
        (str "#!/bin/sh\n"
             "set -eu\n"
             "SEON_ROOT=$(CDPATH= cd \"$(dirname \"$0\")/..\" && pwd)\n"
             "exec \"$SEON_ROOT/runtime/bb\" --jar "
             "\"$SEON_ROOT/runtime/operator.jar\" --seon-root "
             "\"$SEON_ROOT\" \"$@\"\n"))
  (.setExecutable (io/file (str path)) true false)
  path)

(defn build-package!
  "Build and atomically publish one relocatable source-free release."
  {:malli/schema
   [:=>
    [:cat [:map {:closed true}
           [::root :string]
           [::package-root :string]
           [::environment {:optional true} [:map-of :string :string]]]]
    release-manifest-schema]}
  [{::keys [root package-root environment]}]
  (let [root (fs/canonicalize (fs/path root))
        target (fs/absolutize (fs/path package-root))
        environment (or environment (into {} (System/getenv)))
        bun (str (fs/path root "reference-code/bun/build/release/bun"))
        {:keys [version]} (inspect-bun! root environment bun)
        build-root (fs/path root "tmp/release-package-build" (str (random-uuid)))
        stage (fs/path (fs/parent target)
                       (str "." (fs/file-name target) "." (random-uuid) ".tmp"))
        closure (fs/path build-root "closure")
        release-programs
        {:seon.dev.artifact/release-cache-root
         (str (fs/path build-root "shadow-cache"))
         :seon.dev.artifact/release-client-output
         (str (fs/path build-root "pod.js"))
         :seon.dev.artifact/release-execution-output
         (str (fs/path build-root "execution.js"))
         :seon.dev.artifact/release-program-source-output
         (str (fs/path build-root "program-sources.edn"))}
        config {:seon.dev.config/root (str root)
                :seon.dev.config/environment environment}]
    (when (fs/exists? target)
      (manifest-error "The release target already exists."
                      {::package-root (str target)}))
    (try
      (doseq [directory [build-root closure (fs/parent target)]]
        (fs/create-dirs directory))
      (let [{:seon.dev.release/keys [executable identity]}
            (download-babashka! build-root environment)
            operator (build-operator! root build-root environment executable)
            launcher (write-launcher! (fs/path build-root "bin/seon"))]
        (run! root environment "clojure" "-X:deps" "prep" ":aliases"
              "[:writer :cljs]")
        (run! root environment "clojure" "-T:build" "writer-uber")
        (run! root environment "clojure" "-M:cljs" "compile" "bootstrap")
        (run! root environment (str (fs/path root "bin/fix-bootstrap-macros")))
        (run! root environment bun "run" "--bun" "css:build")
        ((requiring-resolve 'seon.dev.artifact/build-release-programs!)
         config release-programs)
        (copy-file! (fs/path root "package.json")
                    (fs/path closure "package.json"))
        (copy-file! (fs/path root "bun.lock") (fs/path closure "bun.lock"))
        (run! closure environment bun "install" "--production"
              "--frozen-lockfile")
        (production-package-json! (fs/path root "package.json")
                                  (fs/path closure "package.json"))
        (assemble-package!
         {::package-root (str stage)
        ::bun bun
        ::bun-version version
        ::writer (str (fs/path root
                               "target/seon-database-server-standalone.jar"))
        ::pod (:seon.dev.artifact/release-client-output release-programs)
        ::execution (:seon.dev.artifact/release-execution-output
                     release-programs)
        ::bootstrap (str (fs/path root "out/bootstrap"))
        ::public-assets (str (fs/path root "resources/public"))
        ::program-source
        (:seon.dev.artifact/release-program-source-output release-programs)
        ::babashka executable
        ::babashka-asset identity
        ::operator (str operator)
        ::launcher (str launcher)
        ::config (str (fs/path root "config/system.edn"))
        ::babashka-license
        (str (fs/path root "reference-code/babashka/LICENSE"))
        ::node-modules (str (fs/path closure "node_modules"))
        ::package-json (str (fs/path closure "package.json"))
        ::bun-lock (str (fs/path closure "bun.lock"))
          ::license (str (fs/path root "LICENSE"))}))
      (fs/move stage target {:atomic-move true})
      (read-manifest! (str (fs/path target "release.edn")))
      (finally
        (when (fs/exists? stage) (fs/delete-tree stage {:force true}))
        (when (fs/exists? build-root)
          (fs/delete-tree build-root {:force true}))))))
