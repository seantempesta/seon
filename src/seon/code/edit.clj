(ns seon.code.edit
  "Whitespace-, tab-, newline-, and comment-insensitive structural Clojure editing (`rewrite-clj` CST),
   AST-accurate unused `:require` analysis/pruning, body grep, unified diffs, and in-turn branch test running."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.ctx :as ctx]
            [seon.fork.calls :refer [*agent-ctx*]]
            [seon.fork.worker-support :as wsup]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.code.cst :as cst]))

(defn node->structural-key [& args] (apply cst/node->structural-key args))

(defn parse-non-ws-nodes [& args] (apply cst/parse-non-ws-nodes args))

(defn patch-form-source [& args] (apply cst/patch-form-source args))

(defn unified-diff [& args] (apply cst/unified-diff args))

(defn collect-body-refs [& args] (apply cst/collect-body-refs args))

(defn analyze-ns-requires [& args] (apply cst/analyze-ns-requires args))

(defn prune-unused-requires-src [& args] (apply cst/prune-unused-requires-src args))

;; --- 4. Focused Top-Level Agent REPL Helper Functions ---

(defn- merged-ns-src [conn branch nsym]
  (let [bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        base-db (when (not= branch :main) (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))
        ns-forms-of (fn [db]
                      (->> (d/datoms db :avet :seon.code.form/ns nsym)
                           (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                           (sort-by #(or (:seon.code.form/order %) 0))))
        m-fs (ns-forms-of mdb)
        b-fs (ns-forms-of bdb)
        b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) b-fs)
        base-by-k (if base-db (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms-of base-db)) {})
        m-keys (into #{} (map :seon.code.form/key) m-fs)
        kept-m (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                       (let [bs (get b-by-k key)]
                         (cond
                           (and (nil? bs) (contains? base-by-k key)) nil
                           (and bs (not= bs (get base-by-k key))) bs
                           :else src)))
                     (if (seq m-fs) m-fs b-fs))
        added-b (when (seq m-fs)
                  (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                          (when-not (contains? m-keys key) src))
                        b-fs))]
    (str/join "\n\n" (concat kept-m added-b))))

(defn- resolve-form-target [conn branch target]
  (let [s (str target)
        bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        [nsym fkey] (cond
                      (str/ends-with? s "#ns") [(symbol (subs s 0 (- (count s) 3))) s]
                      (str/includes? s "/")
                      (let [sym (symbol s)
                            fk (or (:seon.code.var/form (d/pull bdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   (:seon.code.var/form (d/pull mdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   s)]
                        [(symbol (namespace sym)) fk])
                      :else [(symbol s) (str s "#ns")])
        path (or (:seon.code.ns/path (d/pull bdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (:seon.code.ns/path (d/pull mdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (code/ns->default-path nsym))
        f-ent (let [b (d/pull bdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])]
                (if (:seon.code.form/src b) b
                    (d/pull mdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])))]
    {:ns nsym :key fkey :path path
     :src (:seon.code.form/src f-ent)
     :order (:seon.code.form/order f-ent)
     :lead (:seon.code.form/lead f-ent)}))

(defn- single-form-upsert-tx
  "O(1-form) delta persistence for non-`#ns` form edits — avoids re-parsing the entire `.clj` file."
  [db nsym fkey path order lead new-fsrc]
  (let [ns-src (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))
        local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (code/ns-vars db nsym))
        reparsed (parse/read-clj path (str (or ns-src (str "(ns " nsym ")")) "\n" new-fsrc) local-defs)
        kept-forms (mapv (fn [f]
                           (cond-> f
                             (= (:seon.code.form/key f) fkey)
                             (assoc :seon.code.form/order (or order 1)
                                    :seon.code.form/lead (or lead "\n\n"))))
                         (rest (:forms reparsed)))
        kept-vars (:vars reparsed)
        stale-var-ops (vec
                       (mapcat (fn [v]
                                 (when-let [eid (:e (first (d/datoms db :avet :seon.code.var/name (:seon.code.var/name v))))]
                                   (mapcat (fn [a]
                                             (let [old-s (into #{} (map :v) (d/datoms db :eavt eid a))
                                                   new-s (set (get v a #{}))]
                                               (for [gone (remove new-s old-s)]
                                                 [:db/retract eid a gone])))
                                           [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords])))
                               kept-vars))]
    (vec (concat stale-var-ops kept-forms kept-vars))))

(defn do-edit-fn!
  ([target old-str new-str] (do-edit-fn! target old-str new-str {}))
  ([target old-str new-str {:keys [occurrence dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [old-s (if (string? old-str) old-str (binding [*print-meta* true] (pr-str old-str)))
               new-s (if (string? new-str) new-str (binding [*print-meta* true] (pr-str new-str)))
               res (patch-form-source src old-s new-s {:occurrence occurrence})]
           (if-not (:ok? res)
             (assoc res :target target :key key)
             (let [new-fsrc (:src res)
                   udiff (unified-diff src new-fsrc key)]
               (if dry-run?
                 {:ok? true :dry-run? true :target target :key key :line (:line res) :diff udiff}
                 (let [tx (if (str/ends-with? key "#ns")
                            (let [file-src (merged-ns-src conn branch ns)
                                  idx (.indexOf ^String file-src ^String src)]
                              (when-not (neg? idx)
                                (code/file-import-tx @conn path
                                                     (str (subs file-src 0 idx) new-fsrc (subs file-src (+ idx (count src))))
                                                     false)))
                            (single-form-upsert-tx @conn ns key path order lead new-fsrc))]
                   (if-not tx
                     {:ok? false :error :source-sync-mismatch :target target :key key}
                     (let [r (d/transact conn {:tx-data tx})
                           eval-err (when (and eval? (not (str/ends-with? key "#ns")))
                                      (try
                                        (when-not (find-ns ns) (require ns))
                                        (binding [*ns* (the-ns ns)]
                                          (eval (read-string new-fsrc)))
                                        nil
                                        (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                       (ctx/refresh-active! (:db-after r) (:tx-data r))
                       (let [defonce? (str/starts-with? (str/triml new-fsrc) "(defonce ")]
                       (cond-> {:ok? (nil? eval-err)
                                :target target :key key :path path :line (:line res)
                                :tx (:max-tx (:db-after r)) :diff udiff}
                         eval-err (assoc :eval-error eval-err)
                         defonce? (assoc :note "defonce keeps its existing runtime binding; use (reset! ...) or (alter-var-root ...) if testing a new initial value live.")))))))))))))))

(defn do-grep-src
  ([pattern] (do-grep-src pattern {}))
  ([pattern {:keys [ns limit] :or {limit 30}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [bdb @conn
           ns-pref (when ns (name ns))
           re? (instance? java.util.regex.Pattern pattern)
           q-low (when-not re? (str/lower-case (str pattern)))
           match-str? (fn [^String s]
                        (if re?
                          (boolean (re-find pattern s))
                          (str/includes? (str/lower-case s) q-low)))
           hits (volatile! [])]
       (if ns-pref
         (let [matching-nss (->> (d/datoms bdb :aevt :seon.code.ns/name)
                                 (map :v)
                                 (filter (fn [n]
                                           (let [s (name n)]
                                             (or (= s ns-pref) (str/starts-with? s (str ns-pref ".")))))))
               cand-eids (into [] (mapcat #(map :e (d/datoms bdb :avet :seon.code.form/ns %))) matching-nss)]
           (doseq [eid cand-eids
                   :while (< (count @hits) limit)
                   :let [src (:v (first (d/datoms bdb :eavt eid :seon.code.form/src)))]
                   :when (and src (match-str? src))
                   :let [fk (:v (first (d/datoms bdb :eavt eid :seon.code.form/key)))]]
             (doseq [[i line] (map-indexed vector (str/split-lines src))
                     :while (< (count @hits) limit)
                     :when (match-str? line)]
               (let [t (str/trim line)]
                 (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
         (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                 :while (< (count @hits) limit)
                 :let [src (str (:v dt))]
                 :when (match-str? src)
                 :let [fk (:v (first (d/datoms bdb :eavt (:e dt) :seon.code.form/key)))]]
           (doseq [[i line] (map-indexed vector (str/split-lines src))
                   :while (< (count @hits) limit)
                   :when (match-str? line)]
             (let [t (str/trim line)]
               (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
       @hits))))

(defn do-unused-requires [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (analyze-ns-requires fsrc)]
          {:ok? true
           :ns s
           :unused (vec (map :lib (remove :keep? analysis)))
           :kept (vec (map :lib (filter :keep? analysis)))
           :detail analysis})))))

(defn do-prune-requires!
  ([nsym] (do-prune-requires! nsym {}))
  ([nsym {:keys [dry-run?]}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [path]} (resolve-form-target conn branch s)
           fsrc (merged-ns-src conn branch s)]
       (if (str/blank? fsrc)
         {:ok? false :error :ns-not-found :ns s}
         (let [res (prune-unused-requires-src fsrc)
               udiff (unified-diff fsrc (:src res) path)]
           (cond
             (not (:changed? res)) (assoc res :ns s :path path :diff "")
             dry-run? (assoc res :ns s :path path :dry-run? true :diff udiff)
             :else
             (let [tx (code/file-import-tx @conn path (:src res) false)
                   r (d/transact conn {:tx-data tx})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :ns s :path path :changed? true
                :removed (:removed res) :kept (:kept res)
                :tx (:max-tx (:db-after r)) :diff udiff}))))))))

(defn do-run-tests!
  ([] (do-run-tests! nil))
  ([targets]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [t0 (System/nanoTime)
           bdb @conn
           base-cid (when (not= branch :main)
                      (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           ops (when base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)))
           _ (when (seq (:upserts ops))
               (wsup/apply-form-overlay! bdb {:upserts (:upserts ops) :retracted-keys (:retracted-keys ops)}))
           target-list (cond
                         (nil? targets) nil
                         (or (vector? targets) (set? targets) (seq? targets)) (vec targets)
                         :else [targets])
           test-syms
           (if (seq target-list)
             (->> target-list
                  (mapcat (fn [x]
                            (let [s (if (string? x) (symbol x) x)]
                              (cond
                                (namespace s) [s]
                                (str/ends-with? (name s) "-test")
                                (->> (d/datoms bdb :avet :seon.code.var/ns s)
                                     (keep #(let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test?] (:e %))]
                                              (when (:seon.code.var/test? v) (:seon.code.var/name v)))))
                                :else (code/reaching-tests bdb s)))))
                  distinct sort vec)
             (let [changed-nss (distinct (keep :seon.code.form/ns (:upserts ops)))]
               (->> changed-nss
                    (mapcat #(code/reaching-tests bdb %))
                    distinct sort vec)))
           results (mapv #(wsup/run-single-test-var % (str "local:" %)) test-syms)
           failures (filterv #(not (:seon.test/pass? %)) results)
           ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
       {:ok? (empty? failures)
        :ran (count results)
        :passed (- (count results) (count failures))
        :failed (count failures)
        :ms ms
        :failures (mapv #(select-keys % [:seon.test/var :seon.test/report :seon.test/ms]) failures)
        :results (mapv #(select-keys % [:seon.test/var :seon.test/pass? :seon.test/ms]) results)}))))

(defn do-diff
  ([] (do-diff nil))
  ([target]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           base-db (when base-cid (d/commit-as-db conn base-cid))
           ops (if base-cid
                 (code/changed-form-ops conn base-cid (d/commit-id bdb))
                 {:upserts [] :retracted-keys #{} :schema-keys #{}})
           errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {}))
           upserts (if target
                     (let [k (str target)]
                       (filterv #(= k (:seon.code.form/key %)) (:upserts ops)))
                     (:upserts ops))
           diffs (into {}
                       (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                               (let [old-s (when base-db
                                             (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))]
                                 [key (unified-diff (or old-s "") (or src "") key)])))
                       (take 12 upserts))]
       {:branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-errors errs
        :diffs diffs}))))

(defn install-edit-helpers!
  "Interns `edit-fn!`, `grep-src`, `run-tests!`, `unused-requires`, `prune-requires!`, and unified `diff` into `user`."
  [u]
  (intern u 'edit-fn! do-edit-fn!)
  (intern u 'grep-src do-grep-src)
  (intern u 'unused-requires do-unused-requires)
  (intern u 'prune-requires! do-prune-requires!)
  (intern u 'run-tests! do-run-tests!)
  (intern u 'diff do-diff))
