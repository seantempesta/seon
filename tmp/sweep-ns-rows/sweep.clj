;; Sweep hand-built namespace rows in test fixtures to their canonical writers.
;;
;; bb tmp/sweep-ns-rows/sweep.clj [--write] FILE...
;;
;; Every map literal whose first key is `:seon.ns/name` bound to a quoted symbol
;; is classified by its structural context (rewrite-clj zipper; no text regex):
;;
;;   :converted/namespace-row    a create in transaction data carrying only the
;;                               name, `(ns NAME)` source and :agent admission
;;                               -> (SUPPORT/namespace-row @CONN 'NAME)
;;   :converted/declaration-row  a create in transaction data carrying further
;;                               declared keys -> (PROGRAM/declaration-row
;;                               (DB/carried-projection @CONN) {...} :all SOURCE)
;;   :skipped/canonical          already an argument of declaration-row
;;   :skipped/existing           the namespace is already a row in default's world
;;                               (tmp/sweep-ns-rows/existing-namespaces.edn): an upsert
;;   :skipped/fixture-created    the same top-level form creates it through the agent
;;                               writer (agent-tx, agents-tx, creation-tx map): an upsert
;;   :skipped/not-transaction    not transaction data (expected value, pull, emission,
;;                               analyzer input, let binding)
;;   :exception/*                transaction data the script cannot rewrite safely
;;                               (no connection symbol, missing alias); hand-fix
;;
;; Without --write it reports only.
(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n]
         '[clojure.edn :as edn])

(def existing
  (set (map symbol (edn/read-string (slurp "tmp/sweep-ns-rows/existing-namespaces.edn")))))

(def transacting-heads #{"transacted!" "transact!" "admit!" "transact-fixture!"})
(def analyzer-heads #{"source-rows"})
(def pass-through-heads #{"into" "cond->" "concat" "conj" "vec"})

(defn head-name [zloc]
  (when (= :list (z/tag zloc))
    (let [head (z/down zloc)]
      (when (and head (= :token (z/tag head)) (symbol? (z/sexpr head)))
        (name (z/sexpr head))))))

(defn namespace-map? [zloc]
  (and (= :map (z/tag zloc))
       (let [k (z/down zloc)]
         (and k (= :seon.ns/name (z/sexpr k))
              (let [v (z/right k)]
                (or (and (= :quote (z/tag v))
                         (symbol? (second (z/sexpr v))))
                    ;; a local naming the namespace, resolved by `bound-name`
                    (and (= :token (z/tag v)) (symbol? (z/sexpr v)))))))))

(defn bound-name
  "The quoted namespace symbol an enclosing let/loop binds `local` to, else nil."
  [zloc local]
  (loop [loc (z/up zloc)]
    (when loc
      (or (when (#{"let" "loop"} (let [h (z/down loc)] (when (and h (= :token (z/tag h))) (str (z/sexpr h)))))
            (let [bindings (z/sexpr (z/right (z/down loc)))]
              (some (fn [[k v]] (when (and (= k local) (seq? v) (= 'quote (first v)) (symbol? (second v)))
                                  (second v)))
                    (partition 2 bindings))))
          (recur (z/up loc))))))

(defn map-key-of
  "The key whose value is `zloc` when its parent is a map, else nil."
  [zloc]
  (when (= :map (some-> zloc z/up z/tag))
    (let [left (z/left zloc)
          ;; values sit at odd positions: count siblings to the left
          position (count (take-while some? (iterate z/left zloc)))]
      (when (even? position) (z/sexpr left)))))

(defn let-bound-namespace-row
  "The database local when `child` is bound to a local named `namespace-row` in
   the let vector `parent`, after a `database` binding; else nil."
  [child parent]
  (when (= "let" (some-> parent z/up z/down z/sexpr str))
    (let [items (take-while some? (iterate z/right (z/down parent)))
          pairs (partition 2 items)
          at (some #(when (= (z/node (second %)) (z/node child)) (z/sexpr (first %))) pairs)]
      (when (and (= 'namespace-row at)
                 (some #(= 'database (z/sexpr (first %))) (take-while #(not= 'namespace-row (z/sexpr (first %))) pairs)))
        "database"))))

(defn transaction-context
  "Walk up from the map through transaction-data containers. Answers
   {:call zloc} at a transacting call, {:canonical true} under declaration-row,
   or {:not-transaction head} at the first other form."
  [zloc]
  (loop [child zloc parent (z/up zloc)]
    (cond
      (nil? parent) {:not-transaction :top}
      :else
      (case (z/tag parent)
        :vector (if-let [database (let-bound-namespace-row child parent)]
                  {:analyzer database}
                  (recur parent (z/up parent)))
        :map (if (#{:seon.agent/namespace :tx-data} (map-key-of child))
               (recur parent (z/up parent))
               {:not-transaction :map-value})
        :list (let [head (head-name parent)]
                (cond
                  (= "declaration-row" head) {:canonical true}
                  (transacting-heads head)
                  (if (= child (z/down parent))
                    {:not-transaction head}
                    {:call parent})
                  (pass-through-heads head) (recur parent (z/up parent))
                  ;; the analyzer reads the namespace row it is handed as a
                  ;; complete declaration: its database is the call's first argument
                  (analyzer-heads head)
                  {:analyzer (z/string (z/right (z/down parent)))}
                  :else {:not-transaction head}))
        {:not-transaction (z/tag parent)}))))

(defn aliases
  "alias by required namespace symbol, from the file's ns form."
  [root]
  (let [ns-form (z/sexpr (z/up (z/find-value root z/next 'ns)))
        requires (some #(when (and (seq? %) (= :require (first %))) (rest %)) ns-form)]
    (into {} (keep (fn [spec]
                     (when (vector? spec)
                       (let [[lib & opts] spec
                             opts (apply hash-map (if (odd? (count opts)) (butlast opts) opts))]
                         (when-let [a (:as opts)] [lib a])))))
          requires)))

(defn row-plan [m file-aliases conn]
  (let [nm (:seon.ns/name m)
        source (:seon.ns/source m)
        admission (get m :seon.schema.admission/source :agent)
        simple? (and (every? #{:seon.ns/name :seon.ns/source :seon.schema.admission/source ::expr} (keys m))
                     (or (nil? source) (= source (str "(ns " nm ")")))
                     (= :agent admission))
        support (get file-aliases 'seon.test-support)
        program (get file-aliases 'seon.program)
        dbalias (get file-aliases 'seon.db)]
    (cond
      (not (symbol? conn)) {:class :exception/no-connection-symbol}
      simple? (if support
                {:class :converted/namespace-row
                 :text (format "(%s/namespace-row @%s %s)" support conn (::expr m))}
                {:class :exception/no-test-support-alias})
      :else {:class :converted/declaration-row
             ;; a new alias must not be one the file already binds to another lib
             :program (or program (if ((set (vals file-aliases)) 'program) 'seon-program 'program))
             :db (or dbalias 'db) :admission admission
             :needs (cond-> {} (not program)
                            (assoc 'seon.program
                                   (if ((set (vals file-aliases)) 'program) 'seon-program 'program))
                            (not dbalias) (assoc 'seon.db 'db))})))

(defn without-admission-source
  "The map node without its :seon.schema.admission/source entry, which
   declaration-row stamps from its argument."
  [node]
  (let [kids (vec (n/children node))
        significant (vec (keep-indexed (fn [i k] (when-not (n/whitespace-or-comment? k) i)) kids))
        at (some (fn [[ki vi]] (when (= :seon.schema.admission/source (n/sexpr (kids ki))) [ki vi]))
                 (partition 2 significant))]
    (if-not at
      node
      (let [[ki vi] at
            ;; drop the key, the value and the whitespace that followed the value
            end (or (first (filter #(> % vi) significant)) (count kids))
            start (if (= end (count kids))
                    ;; last entry: drop the whitespace before the key instead
                    (or (some #(when (< % ki) (inc %)) (reverse significant)) ki)
                    ki)]
        (n/replace-children node (into (subvec kids 0 start) (subvec kids end)))))))

(defn with-namespace-source
  "The map node carrying `:seon.ns/source`, adding `(ns NAME)` after the name
   when the author gave none: declaration-row refuses a reader declaration
   without source, and namespace-row writes the same default."
  [node]
  (let [kids (vec (n/children node))
        sexpr (n/sexpr node)]
    (if (find sexpr :seon.ns/source)
      node
      (let [name-at (first (keep-indexed (fn [i k] (when (and (not (n/whitespace-or-comment? k))
                                                                (= :seon.ns/name (n/sexpr k))) i))
                                         kids))
            value-at (first (keep-indexed (fn [i k] (when (and (> i name-at) (not (n/whitespace-or-comment? k))) i))
                                          kids))
            value (n/sexpr (kids value-at))
            source (if (seq? value)
                     (n/string-node (str "(ns " (second value) ")"))
                     (z/node (z/of-string (format "(str \"(ns \" %s \")\")" value))))]
        (n/replace-children
         node
         (-> (subvec kids 0 (inc value-at))
             (into [(n/spaces 1) (n/keyword-node :seon.ns/source) (n/spaces 1)
                    source])
             (into (subvec kids (inc value-at)))))))))

(defn declaration-text [zloc {:keys [program db admission]} conn]
  (let [[_ column] (z/position zloc)
        pad (apply str (repeat column " "))
        map-text (-> (z/node zloc) without-admission-source with-namespace-source n/string
                     ;; the map moves one column right; so do its continuation lines
                     (.replace "\n" (str "\n ")))]
    (format "(%s/declaration-row\n%s(%s/carried-projection @%s)\n%s%s\n%s:all %s)"
            program pad db conn pad map-text pad admission)))

(defn past
  "The next location after `zloc` and its whole subtree."
  [zloc]
  (or (z/right zloc)
      (loop [up (z/up zloc)]
        (cond (nil? up) (z/of-node* (n/forms-node []))
              (z/right up) (z/right up)
              :else (recur (z/up up))))))

(defn required-aliases
  "Add `[lib :as alias]` to the ns :require for each lib the edits need but the
   file does not require; answer the root and the alias map."
  [root file-aliases libs]
  (reduce (fn [[root aliases] [lib alias]]
            (if (get aliases lib)
              [root aliases]
              (let [require-list (-> root (z/find-value z/next 'ns) z/up
                                     (z/find-value z/next :require) z/up)
                    root (-> require-list
                             (z/append-child* (n/newlines 1))
                             (z/append-child* (n/spaces 12))
                             (z/append-child (n/vector-node [(n/token-node lib) (n/spaces 1)
                                                             (n/keyword-node :as) (n/spaces 1)
                                                             (n/token-node alias)]))
                             z/root z/of-node)]
                [(z/of-node (z/root root) {:track-position? true}) (assoc aliases lib alias)])))
          [root file-aliases]
          libs))

(defn top-level [zloc]
  (loop [loc zloc]
    (let [up (z/up loc)]
      (if (or (nil? up) (nil? (z/up up))) loc (recur up)))))

(defn fixture-created
  "Namespaces the enclosing top-level form creates through the agent writer:
   agent-tx/agents-tx ids (my.agents.<id>, or agent-tx's explicit namespace)
   and creation-tx argument maps carrying both :seon.agent/id and :seon.ns/name."
  [zloc]
  (let [form (z/sexpr (top-level zloc))
        unquote* (fn [x] (if (and (seq? x) (= 'quote (first x))) (second x) x))]
    (set
     (mapcat
      (fn [x]
        (cond
          (and (seq? x) (symbol? (first x)) (= "agent-tx" (name (first x))))
          (let [[_ _ id nm] x]
            (cond (some? nm) [(unquote* nm)]
                  (string? id) [(symbol (str "my.agents." id))]))
          (and (seq? x) (symbol? (first x)) (= "agents-tx" (name (first x))))
          (let [[_ _ ids] x]
            (when (vector? ids) (map #(symbol (str "my.agents." %)) (filter string? ids))))
          (and (map? x) (:seon.agent/id x) (:seon.ns/name x))
          [(unquote* (:seon.ns/name x))]))
      (tree-seq coll? seq form)))))

(defn sweep [file write?]
  (let [root (z/of-string (slurp file) {:track-position? true})
        file-aliases (aliases root)
        report (atom [])]
    (loop [zloc root]
      (if (z/end? zloc)
        (let [needs (apply merge (map :needs @report))
              [root _] (required-aliases (z/of-node (z/root zloc)) file-aliases needs)
              out (z/root-string root)]
          (when write? (spit file out))
          @report)
        (if (namespace-map? zloc)
          (let [m (z/sexpr zloc)
                raw (:seon.ns/name m)
                ;; `expr` is how the rewritten call names the namespace
                [nm expr] (if (seq? raw)
                            [(second raw) (str "'" (second raw))]
                            [(bound-name zloc raw) (str raw)])
                m (assoc m :seon.ns/name nm ::expr expr)
                [line] (z/position zloc)
                ctx (transaction-context zloc)
                entry {:file file :line line :ns nm}
                plan (cond
                       (nil? nm) {:class :exception/unresolved-name :context raw}
                       (:analyzer ctx)
                       (let [support (get file-aliases 'seon.test-support)
                             only-name? (every? #{:seon.ns/name ::expr} (keys m))]
                         (if (and support only-name?)
                           {:class :converted/analyzer-namespace-row
                            :text (format "(%s/namespace-row %s %s)" support (:analyzer ctx) (::expr m))}
                           {:class :exception/analyzer-row-with-extras}))
                       (:canonical ctx) {:class :skipped/canonical}
                       (:not-transaction ctx) {:class :skipped/not-transaction
                                               :context (:not-transaction ctx)}
                       (existing nm) {:class :skipped/existing}
                       ((fixture-created zloc) nm) {:class :skipped/fixture-created}
                       :else (row-plan m file-aliases
                                       (z/sexpr (z/right (z/down (:call ctx))))))]
            (swap! report conj (merge entry (select-keys plan [:class :context :needs])))
            (case (:class plan)
              (:converted/namespace-row :converted/analyzer-namespace-row)
              (recur (past (z/replace zloc (z/node (z/of-string (:text plan))))))
              :converted/declaration-row
              (let [conn (z/sexpr (z/right (z/down (:call ctx))))
                    text (declaration-text zloc plan conn)]
                (recur (past (z/replace zloc (z/node (z/of-string text))))))
              (recur (z/next zloc))))
          (recur (z/next zloc)))))))

(when-not (= "--census" (first *command-line-args*))
(let [[flag & files] *command-line-args*
      write? (= "--write" flag)
      files (if write? files (cons flag files))
      entries (mapcat #(sweep % write?) files)]
  (doseq [e entries]
    (println (format "%-30s %s:%s %s%s%s" (:class e) (:file e) (:line e) (:ns e)
                     (if (:context e) (str " (" (:context e) ")") "")
                     (if (seq (:needs e)) (str " adds require " (pr-str (:needs e))) ""))))
  (println)
  (doseq [[k v] (sort-by (comp str key) (frequencies (map :class entries)))]
    (println k v))))

;; ---- census of hand-built fn/test rows (report only; bb sweep.clj --census FILE...)
;; A map carrying :seon.fn/sym or :seon.test/sym as transaction data is a
;; declaration row; :trivial when every key is one the analyzer derives from
;; source, so program-fn-row/program-row could own it; :carries-extras otherwise.
(def analyzer-derived
  #{:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists :seon.fn/private?
    :seon.test/sym :seon.test/ns :seon.test/source :seon.schema.admission/source})

(defn census [file]
  (loop [zloc (z/of-string (slurp file) {:track-position? true}) out []]
    (if (z/end? zloc)
      out
      (recur (z/next zloc)
             (if (and (= :map (z/tag zloc))
                      (let [ks (set (keep #(when (keyword? %) %) (map z/sexpr (take-nth 2 (take-while some? (iterate z/right (z/down zloc)))))))]
                        (or (ks :seon.fn/sym) (ks :seon.test/sym))))
               (let [ctx (transaction-context zloc)
                     ks (set (take-nth 2 (map z/sexpr (take-while some? (iterate z/right (z/down zloc))))))]
                 (if (:call ctx)
                   (conj out {:file file :line (first (z/position zloc))
                              :class (cond (or (ks :seon.program/definition-digest)
                                               (ks :seon.program/analyzed-source-digest)) :already-digested
                                           (every? analyzer-derived ks) :trivial
                                           :else :carries-extras)
                              :extras (sort (remove analyzer-derived ks))})
                   out))
               out)))))

(when (= "--census" (first *command-line-args*))
  (let [entries (mapcat census (rest *command-line-args*))]
    (doseq [e entries]
      (println (format "%-16s %s:%s %s" (:class e) (:file e) (:line e) (pr-str (:extras e)))))
    (println (frequencies (map :class entries)))
    (println "files:" (count (distinct (map :file entries))))))
