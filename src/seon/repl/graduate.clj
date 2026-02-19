(ns seon.repl.graduate
  "Namespace graduation: assembles Datalevin-stored forms into a .clj file,
   writes to disk, and optionally git commits."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [seon.repl.super :as super]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

(schema/register! ::namespace
                  [:string {:min 1 :description "Namespace to graduate"}])

(schema/register! ::git-commit?
                  [:boolean {:description "Whether to git commit after writing"}])

(schema/register! ::file-content
                  [:string {:description "Generated file content"}])

(schema/register! ::file-path
                  [:string {:description "Target file path on disk"}])

(schema/register! ::form-count
                  [:int {:min 0 :description "Number of forms in the file"}])

(schema/register! ::git-committed?
                  [:boolean {:description "Whether a git commit was created"}])

(schema/register! ::base-path
                  [:string {:description "Base directory for output (default \"src\")"}])

;;; ---------------------------------------------------------------------------
;;; Namespace → File Path
;;; ---------------------------------------------------------------------------

(defn ns->file-path
  "Convert a namespace string to a file path.

   Request keys:
     ::namespace - Required. Namespace string
     ::target    - Optional. :cljs, :cljc, or :clj (default)
     ::base-path - Optional. Base directory (default \"src\")

   Returns file path string.

   Example:
     (ns->file-path {::namespace \"seon.trading.signals\"})
     ;; => \"src/seon/trading/signals.clj\""
  [{::keys [namespace target base-path]}]
  (let [path (-> namespace
                 (str/replace "." "/")
                 (str/replace "-" "_"))
        ext (case target :cljs ".cljs" :cljc ".cljc" ".clj")
        base (or base-path "src")]
    (str base "/" path ext)))

;;; ---------------------------------------------------------------------------
;;; Form Sorting
;;; ---------------------------------------------------------------------------

(defn- form-sort-key
  "Return a sort priority for a form entity. Lower = earlier in file."
  [form]
  (case (:form/type form)
    :ns      0
    :require 1
    :def     2
    :defn    3
    4))

;;; ---------------------------------------------------------------------------
;;; File Assembly
;;; ---------------------------------------------------------------------------

(defn- assemble-content
  "Assemble form entities into a proper Clojure file string."
  [ns-str forms]
  (let [sorted (sort-by form-sort-key forms)
        has-ns? (some #(= :ns (:form/type %)) sorted)
        ns-form (when-not has-ns?
                  (str "(ns " ns-str ")"))
        sources (cond->> (mapv :form/source sorted)
                  (not has-ns?) (into [ns-form]))]
    (str/join "\n\n" sources)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn preview
  "Generate file content without writing to disk.

   Request keys:
     ::conn      - Required. Datalevin connection
     ::namespace - Required. Namespace string to graduate
     ::target    - Optional. :clj, :cljs, :cljc (default :clj)
     ::base-path - Optional. Base directory (default \"src\")

   Response keys:
     ::file-content - Generated file string
     ::file-path    - Target file path
     ::form-count   - Number of forms

   Example:
     (preview {::conn conn ::namespace \"seon.trading.signals\"})"
  [{::keys [conn namespace target base-path] :as req}]
  (let [forms (super/current-forms {::super/conn conn ::super/namespace namespace})
        content (assemble-content namespace forms)
        path (ns->file-path {::namespace namespace
                             ::target (or target :clj)
                             ::base-path base-path})]
    {::file-content content
     ::file-path path
     ::form-count (count forms)}))

(defn graduate!
  "Write graduated namespace to disk.

   Request keys:
     ::conn        - Required. Datalevin connection
     ::namespace   - Required. Namespace string to graduate
     ::git-commit? - Optional. Create git commit (default true)
     ::target      - Optional. :clj, :cljs, :cljc (default :clj)
     ::base-path   - Optional. Base directory (default \"src\")

   Response keys:
     ::file-path      - Written file path
     ::form-count     - Number of forms
     ::git-committed? - Whether git commit was created

   Example:
     (graduate! {::conn conn ::namespace \"seon.trading.signals\"})"
  [{::keys [conn namespace git-commit? target base-path] :as req}]
  (let [git? (if (some? git-commit?) git-commit? true)
        {::keys [file-content file-path form-count]} (preview req)
        file (io/file file-path)]
    ;; Ensure parent directories
    (io/make-parents file)
    ;; Write file
    (spit file file-content)
    (log/info "Graduated namespace" {:namespace namespace :path file-path :forms form-count})
    ;; Git commit
    (let [committed? (when git?
                       (try
                         (let [{:keys [exit]} (shell/sh "git" "add" file-path)
                               commit-msg (str "feat: graduate " namespace " from Super REPL")]
                           (when (zero? exit)
                             (let [{:keys [exit]} (shell/sh "git" "commit" "-m" commit-msg)]
                               (zero? exit))))
                         (catch Exception e
                           (log/warn "Git commit failed" {:error (.getMessage e)})
                           false)))]
      ;; Best-effort reload
      (try
        (require (symbol namespace) :reload)
        (catch Exception e
          (log/warn "Reload failed" {:error (.getMessage e)})))
      {::file-path file-path
       ::form-count form-count
       ::git-committed? (boolean committed?)})))
