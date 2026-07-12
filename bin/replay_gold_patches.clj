;; bin/replay_gold_patches.clj — the T2 gold-patch replay SCORER.
;;
;; Driven by `bin/replay-gold-patches`, which does the data prep (pull the
;; SWE-bench Verified dev-slice rows from the local HF cache + shallow-clone
;; each repo at its base_commit into tmp/t2-gold/repos/). This file is the
;; PURE-cascade replay: for every gold-patch hunk it derives a find/replace
;; pair from the unified diff and drives `seon.agent.fs.match/decide` — the
;; SAME deterministic matcher the pod's anchored-edit verbs use — then scores
;; the decision against a SEPARATE `git apply` oracle.
;;
;; Falsification target (spec §T2): WRONG = 0. A WRONG is a hunk the cascade
;; APPLIED but to content that disagrees with the git-apply post-image — a
;; wrong-place mutation. If one ever appears we capture it verbatim; we never
;; tune the harness or the cascade to hide it (that is a separate unit's fix).
;;
;; Four passes per hunk:
;;   1. real anchor (context+deleted, near = the true region) → apply/score.
;;   2. same find, NO near window — does a full-context anchor stay unique?
;;   3. minimal anchor (deleted lines only, no near) — ambiguity honesty.
;;   4. single-line anchor (no near), ambiguity by WHOLE-LINE equality — the
;;      direct falsification of "never mutate at a guessed location".
;;
;; Run:  clojure -M:test bin/replay_gold_patches.clj <out-dir>

(require '[clojure.string :as str]
         '[clojure.java.shell :as sh]
         '[clojure.java.io :as io]
         '[clojure.data.json :as json]
         '[seon.agent.fs.match :as m])

(def out-dir (or (first *command-line-args*)
                 (throw (ex-info "usage: replay_gold_patches.clj <out-dir>" {}))))
(def repos-root "tmp/t2-gold/repos")
(def instances (json/read-str (slurp "tmp/t2-gold/instances.json") :key-fn keyword))

;; ---------------------------------------------------------------------------
;; Line plumbing — MUST mirror seon.agent.fs.match/content-lines so the
;; oracle's intermediate contents are byte-comparable with the cascade's.
;; ---------------------------------------------------------------------------

(defn split-lines* [content]
  (if (= "" content)
    []
    (let [ls (str/split content #"\n" -1)]
      (if (= "" (peek ls)) (pop ls) ls))))

(defn join-lines* [lines trailing?]
  (cond-> (str/join "\n" lines)
    (and trailing? (seq lines)) (str "\n")))

;; ---------------------------------------------------------------------------
;; Unified-diff parsing → per-file hunks. The hunk body extent is bounded by
;; the header's old/new line budget (NOT by prefix-guessing) so a patch's
;; trailing newline can't leak a phantom blank context line into the anchor.
;; ---------------------------------------------------------------------------

(defn parse-hunk-header [line]
  (let [mt (re-find #"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@" line)]
    (when mt
      (let [[_ os oc ns nc] mt]
        {:old-start (parse-long os)
         :old-count (if oc (parse-long oc) 1)
         :new-start (parse-long ns)
         :new-count (if nc (parse-long nc) 1)}))))

(defn parse-patch [patch]
  (let [lines (str/split patch #"\n" -1)]
    (loop [ls lines file nil files [] hunk nil]
      (let [push (fn [fs hk] (cond-> fs hk (update (dec (count fs)) update :hunks conj (dissoc hk :old-rem :new-rem))))]
        (if (empty? ls)
          (push files hunk)
          (let [l (first ls)]
            (if (and hunk (zero? (:old-rem hunk)) (zero? (:new-rem hunk)))
              ;; budget spent — close the hunk and re-dispatch the current line.
              (recur ls file (push files hunk) nil)
              (cond
                (str/starts-with? l "diff --git")
                (recur (next ls) nil (push files hunk) nil)
                (str/starts-with? l "+++ ")
                (let [path (-> l (subs 4) (str/replace #"^b/" ""))]
                  (recur (next ls) path (conj (push files hunk) {:file path :hunks []}) nil))
                (str/starts-with? l "@@")
                (let [hd (parse-hunk-header l)]
                  (recur (next ls) file (push files hunk)
                         (assoc hd :body [] :old-rem (:old-count hd) :new-rem (:new-count hd))))
                hunk
                (cond
                  (str/starts-with? l "\\") (recur (next ls) file files hunk)
                  (str/starts-with? l "+") (recur (next ls) file files (-> hunk (update :body conj [:add (subs l 1)]) (update :new-rem dec)))
                  (str/starts-with? l "-") (recur (next ls) file files (-> hunk (update :body conj [:del (subs l 1)]) (update :old-rem dec)))
                  ;; " " context, OR a bare "" line while budget REMAINS (a
                  ;; blank context line some tools emit without the space). The
                  ;; trailing-newline split artifact is already consumed by the
                  ;; budget-spent branch above, so a "" here is genuine context.
                  (or (= "" l) (str/starts-with? l " "))
                  (recur (next ls) file files (-> hunk (update :body conj [:ctx (if (= "" l) "" (subs l 1))]) (update :old-rem dec) (update :new-rem dec)))
                  :else (recur (next ls) file files hunk))
                :else (recur (next ls) file files hunk)))))))))

(defn hunk-find-replace [{:keys [body]}]
  {:find-lines    (->> body (keep (fn [[t x]] (when (#{:ctx :del} t) x))) vec)
   :replace-lines (->> body (keep (fn [[t x]] (when (#{:ctx :add} t) x))) vec)})

;; ---------------------------------------------------------------------------
;; git-apply oracle — the SEPARATE ground truth for post-image content.
;; ---------------------------------------------------------------------------

(defn git-apply-gold [repo-dir patch file-paths]
  (let [pf (java.io.File/createTempFile "gold" ".patch")]
    (try
      (spit pf patch)
      (let [chk (sh/sh "git" "-C" repo-dir "apply" "--check" (.getPath pf))]
        (when-not (zero? (:exit chk))
          (throw (ex-info "git apply --check failed" {:repo repo-dir :err (:err chk)}))))
      (let [ap (sh/sh "git" "-C" repo-dir "apply" (.getPath pf))]
        (when-not (zero? (:exit ap))
          (throw (ex-info "git apply failed" {:repo repo-dir :err (:err ap)}))))
      (into {} (map (fn [p] [p (slurp (io/file repo-dir p))])) file-paths)
      (finally
        ;; always restore the worktree + drop the temp patch, even on throw.
        (sh/sh "git" "-C" repo-dir "checkout" "--" ".")
        (sh/sh "git" "-C" repo-dir "clean" "-fdq")
        (.delete pf)))))

;; ---------------------------------------------------------------------------
;; Per-hunk scoring.
;; ---------------------------------------------------------------------------

(defn classify [decision]
  (if (= :apply (:seon.agent.fs.match/action decision))
    (case (:seon.agent.fs.match/stage decision)
      :seon.agent.fs.match/exact           :exact
      :seon.agent.fs.match/exact-near      :near-rescue
      :seon.agent.fs.match/normalized      :norm-rescue
      :seon.agent.fs.match/normalized-near :norm-rescue
      :applied-unknown-stage)
    :refused))

(defn score-file [instance-id file pre hunks]
  (let [trailing? (str/ends-with? pre "\n")]
    (loop [hs (sort-by :old-start hunks)
           running (vec (split-lines* pre))
           delta 0
           results []]
      (if (empty? hs)
        {:hunks results :final (join-lines* running trailing?)}
        (let [h (first hs)
              {:keys [find-lines replace-lines]} (hunk-find-replace h)
              old-count (count find-lines)
              rstart (+ (:old-start h) delta)
              baseline (join-lines* running trailing?)
              region (when (and (pos? old-count)
                                (<= 1 rstart)
                                (<= (+ (dec rstart) old-count) (count running)))
                       (subvec running (dec rstart) (+ (dec rstart) old-count)))
              anchor-ok? (= region find-lines)
              running' (if (and region anchor-ok?)
                         (-> (subvec running 0 (dec rstart))
                             (into replace-lines)
                             (into (subvec running (+ (dec rstart) old-count))))
                         running)
              oracle-content (join-lines* running' trailing?)
              find (str/join "\n" find-lines)
              replace (str/join "\n" replace-lines)
              near [rstart (+ rstart (max 0 (dec old-count)))]
              scorable? (and (pos? (count find)) anchor-ok?)
              decision (when scorable?
                         (m/decide {:seon.agent.fs.match/content baseline
                                    :seon.agent.fs.match/find find
                                    :seon.agent.fs.match/expected-count 1
                                    :seon.agent.fs.match/near near
                                    :seon.agent.fs.match/replace replace}))
              cls (cond
                    (not (pos? (count find))) :no-anchor
                    (not anchor-ok?)          :anchor-mismatch
                    :else                     (classify decision))
              applied? (and decision (= :apply (:seon.agent.fs.match/action decision)))
              new-content (when applied? (:seon.agent.fs.match/new-content decision))
              wrong? (and applied? (not= new-content oracle-content))
              ;; PASS 2 — same full-context find, NO near window.
              d2 (when scorable?
                   (m/decide {:seon.agent.fs.match/content baseline
                              :seon.agent.fs.match/find find
                              :seon.agent.fs.match/expected-count 1}))
              occ (when scorable?
                    (count (re-seq (re-pattern (java.util.regex.Pattern/quote find)) baseline)))
              multi? (and occ (> occ 1))
              refused2? (and d2 (= :fail (:seon.agent.fs.match/action d2)))
              ;; PASS 3 — minimal anchor: JUST the deleted lines, no context,
              ;; no near. Does the cascade REFUSE when the bare anchor is
              ;; non-unique instead of guessing a location?
              del-lines (->> (:body h) (keep (fn [[t x]] (when (= :del t) x))) vec)
              min-find (when (seq del-lines) (str/join "\n" del-lines))
              d3 (when (and scorable? min-find (pos? (count min-find)))
                   (m/decide {:seon.agent.fs.match/content baseline
                              :seon.agent.fs.match/find min-find
                              :seon.agent.fs.match/expected-count 1}))
              min-occ (when (and min-find (pos? (count min-find)))
                        (count (re-seq (re-pattern (java.util.regex.Pattern/quote min-find)) baseline)))
              min-multi? (and min-occ (> min-occ 1))
              min-refused? (and d3 (= :fail (:seon.agent.fs.match/action d3)))
              min-applied? (and d3 (= :apply (:seon.agent.fs.match/action d3)))
              ;; PASS 4 — single-line anchor: one line alone, no near. A legal
              ;; but under-specified anchor an agent might pick. Ambiguity here
              ;; is measured by WHOLE-LINE equality (what the cascade's stage-3
              ;; line normalization actually resolves on), NOT substring count:
              ;; a line can appear as an OFFSET substring inside a
              ;; deeper-indented line yet be whole-line-unique, in which case
              ;; the cascade correctly applies at the unique line — that is
              ;; disambiguation, not a guess. `guessed` = applied while the
              ;; whole-line count is >1 (the real falsification: must be 0).
              c-lines* running
              line-eq (fn [ln] (count (filter #(= % ln) c-lines*)))
              cand-lines (filter #(pos? (count %)) find-lines)
              one-find (or (some (fn [ln] (when (> (line-eq ln) 1) ln)) cand-lines)
                           (first del-lines) (first find-lines))
              d4 (when (and scorable? one-find (pos? (count one-find)))
                   (m/decide {:seon.agent.fs.match/content baseline
                              :seon.agent.fs.match/find one-find
                              :seon.agent.fs.match/expected-count 1}))
              one-line-eq (when (and one-find (pos? (count one-find))) (line-eq one-find))
              one-substr (when (and one-find (pos? (count one-find)))
                           (count (re-seq (re-pattern (java.util.regex.Pattern/quote one-find)) baseline)))
              one-multi? (and one-line-eq (> one-line-eq 1))
              one-substr-only? (and one-line-eq (= one-line-eq 1) one-substr (> one-substr 1))
              one-refused? (and d4 (= :fail (:seon.agent.fs.match/action d4)))
              one-applied? (and d4 (= :apply (:seon.agent.fs.match/action d4)))]
          (recur (next hs)
                 running'
                 ;; only advance the offset when the hunk ACTUALLY applied to
                 ;; running' — an unanchored hunk leaves running unchanged.
                 (+ delta (if (and region anchor-ok?) (- (count replace-lines) old-count) 0))
                 (conj results
                       {:instance instance-id :file file
                        :old-start (:old-start h) :rstart rstart
                        :old-count old-count :new-count (count replace-lines)
                        :class cls :wrong? wrong?
                        :occ occ :multi? multi?
                        :pass2-refused refused2?
                        :pass2-action (when d2 (:seon.agent.fs.match/action d2))
                        :min-anchor-occ min-occ :min-anchor-multi min-multi?
                        :min-anchor-refused min-refused? :min-anchor-applied min-applied?
                        :one-line-eq one-line-eq :one-line-substr one-substr
                        :one-line-multi one-multi? :one-line-substr-only one-substr-only?
                        :one-line-refused one-refused? :one-line-applied one-applied?
                        :one-line-range (when one-applied? (:seon.agent.fs.match/ranges d4))
                        :one-find one-find
                        :decision-stage (when decision (:seon.agent.fs.match/stage decision))
                        :decision-action (when decision (:seon.agent.fs.match/action decision))
                        :find find :replace replace
                        :near near
                        :cascade-new new-content :oracle-content oracle-content})))))))

(defn run-instance [{:keys [instance_id repo base_commit patch]}]
  (let [dir (str repos-root "/" instance_id)]
    (if-not (.exists (io/file dir ".git"))
      {:instance instance_id :skipped "no checkout"}
      (try
        (let [files  (parse-patch patch)
              files  (filterv #(and (:file %) (not= "/dev/null" (:file %))
                                    (seq (:hunks %))) files)
              paths  (mapv :file files)
              present (filterv #(.exists (io/file dir %)) paths)
              gold   (git-apply-gold dir patch present)
              per    (vec (for [{:keys [file hunks]} files
                                :when (.exists (io/file dir file))]
                            (let [pre (slurp (io/file dir file))
                                  r   (score-file instance_id file pre hunks)
                                  gold-file (get gold file)
                                  oracle-ok? (= (:final r) gold-file)]
                              (assoc r :file file :oracle-cross-check oracle-ok?))))
              missing-files (filterv #(not (.exists (io/file dir %))) paths)]
          {:instance instance_id :files per :missing-files missing-files})
        (catch Exception e
          {:instance instance_id :error (.getMessage e) :data (ex-data e)})))))

(def results (mapv run-instance instances))

;; ---------------------------------------------------------------------------
;; Aggregate + report.
;; ---------------------------------------------------------------------------

(def all-hunks (mapcat (fn [r] (mapcat :hunks (:files r))) results))
(def scored (filter #(#{:exact :near-rescue :norm-rescue :refused} (:class %)) all-hunks))
(def n (count scored))
(defn pct [k] (if (zero? n) 0.0 (* 100.0 (/ (count (filter #(= k (:class %)) scored)) (double n)))))
;; the HARD GATE runs over EVERY hunk (incl. any :applied-unknown-stage that
;; the `scored` classification filter would drop) so a wrong-place mutation
;; can never bypass the gate by landing in an unexpected stage.
(def wrong (filter :wrong? all-hunks))
(def cross-fails (for [r results, f (:files r) :when (false? (:oracle-cross-check f))] [(:instance r) (:file f)]))
(def multi-hunks (filter :multi? scored))
(def multi-refused (filter #(and (:multi? %) (:pass2-refused %)) scored))
(def min-multi (filter :min-anchor-multi scored))
(def min-multi-refused (filter #(and (:min-anchor-multi %) (:min-anchor-refused %)) scored))
(def min-multi-guessed (filter #(and (:min-anchor-multi %) (:min-anchor-applied %)) scored))
(def one-multi (filter :one-line-multi scored))
(def one-multi-refused (filter #(and (:one-line-multi %) (:one-line-refused %)) scored))
(def one-multi-guessed (filter #(and (:one-line-multi %) (:one-line-applied %)) scored))
(def one-substr-only (filter :one-line-substr-only scored))
(def one-substr-only-applied (filter #(and (:one-line-substr-only %) (:one-line-applied %)) scored))
(defn frac [a b] (if (zero? b) "n/a" (format "%.0f%%" (* 100.0 (/ (double a) b)))))

(io/make-parents (str out-dir "/detail.json"))
(spit (str out-dir "/detail.json") (json/write-str all-hunks))

(def report
  (str
    "=== T2 GOLD-PATCH REPLAY ===\n"
    "instances: " (count instances)
    "  ran: " (count (remove #(or (:skipped %) (:error %)) results))
    "  errored/skipped: " (count (filter #(or (:skipped %) (:error %)) results)) "\n"
    "scored hunks (n): " n "\n"
    (format "  exact:       %3d  (%.1f%%)\n" (count (filter #(= :exact (:class %)) scored)) (pct :exact))
    (format "  near-rescue: %3d  (%.1f%%)\n" (count (filter #(= :near-rescue (:class %)) scored)) (pct :near-rescue))
    (format "  norm-rescue: %3d  (%.1f%%)\n" (count (filter #(= :norm-rescue (:class %)) scored)) (pct :norm-rescue))
    (format "  refused:     %3d  (%.1f%%)\n" (count (filter #(= :refused (:class %)) scored)) (pct :refused))
    "  WRONG:       " (count wrong) "  <<< HARD GATE (must be 0)\n"
    "oracle cross-check fails: " (count cross-fails) " " (pr-str (vec cross-fails)) "\n"
    "PASS 2 (no near, full-context find): multi-match hunks: " (count multi-hunks)
    "  correctly refused: " (count multi-refused)
    " (" (frac (count multi-refused) (count multi-hunks)) ")\n"
    "PASS 3 (minimal anchor = deleted lines only, no near): "
    "hunks-with-deletions: " (count (filter :min-anchor-occ scored))
    "  ambiguous (>1 match): " (count min-multi)
    "  correctly refused: " (count min-multi-refused)
    " (" (frac (count min-multi-refused) (count min-multi)) ")"
    "  guessed-a-location: " (count min-multi-guessed) " <<< must be 0\n"
    "PASS 4 (single-line anchor, no near): whole-line-ambiguous (>1): " (count one-multi)
    "  correctly refused: " (count one-multi-refused)
    " (" (frac (count one-multi-refused) (count one-multi)) ")"
    "  guessed-a-location: " (count one-multi-guessed) " <<< must be 0\n"
    "  substring-ambiguous-but-line-unique: " (count one-substr-only)
    "  correctly disambiguated+applied: " (count one-substr-only-applied) "\n"))

(println report)
(spit (str out-dir "/summary.txt") report)

(when (seq wrong)
  (println "\n!!! WRONG HUNKS (verbatim) !!!")
  (doseq [w wrong]
    (println "----" (:instance w) (:file w) "@" (:old-start w))
    (println "FIND:\n" (:find w))
    (println "REPLACE:\n" (:replace w))
    (println "CASCADE-NEW:\n" (:cascade-new w))
    (println "ORACLE:\n" (:oracle-content w))))

(spit (str out-dir "/per-instance.edn")
      (with-out-str
        (doseq [r results]
          (println (:instance r)
                   (if (:error r) (str "ERROR " (:error r))
                       (str "hunks=" (count (mapcat :hunks (:files r)))
                            " files=" (count (:files r))
                            (when (seq (:missing-files r)) (str " missing=" (:missing-files r)))))))))
(shutdown-agents)
