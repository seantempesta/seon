(ns seon.source
  "Source text as the program: a file read form by form with its exact text, and the
  compiler's account of each form taken by analysis, without running it (K1: analysis
  needs the namespace and its macros, nothing else). The same accounts an agent's REPL
  records (seon.program/account), so a namespace imported from a file and one written at a
  REPL are the same facts.

  `read-forms` keeps every character: each form's text, the whitespace and comments before
  it (:lead), and what follows the last form (:tail), so the forms written back in their
  order are the file, byte for byte.

  A schema resource (an .edn file under resources/seon/schemas) is program too: each entry
  is a `:schema/key` entity with its form as EDN text, and the file's text as imported is
  the base its write-back edits, entry by entry, with rewrite-clj (v1's `schema-file-text`):
  every other byte as it was."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.program :as program] [seon.db :as pdb] [seon.reader :as reader] [seon.config :as config] [datahike.api :as d] [clojure.string :as str]
            [rewrite-clj.zip :as z] [rewrite-clj.node :as node] [rewrite-clj.parser :as parser] [jolt.analyzer] [jolt.host] [seon.edn :as sedn]))

(defn- lead-end
  "Where the first form starts in text from i to end: past whitespace, commas and ; comments."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String text i end]
  (loop [i i]
    (if (>= i end)
      end
      (let [c (.charAt text i)]
        (cond (or (Character/isWhitespace c) (= c \,)) (recur (inc i))
              (= c \;) (let [j (.indexOf text "\n" (int i))] (recur (if (or (neg? j) (> j end)) end (inc j))))
              :else i)))))

(defn read-forms
  "text read form by form in namespace nsym (its ns form switches the namespace the rest is
  read in, as loading does): {:forms [{:form :src :lead :ns}] :tail}. Each form's :src is
  its exact text (from where the reader starts it to where the reader stops after it), its
  :lead the text between the previous form and it, and :tail the text after the last. With
  step, each form's map is step's answer for it, taken before the next form is read (as
  loading evaluates a form before reading the next), so a form that sets up its namespace's
  aliases is in effect for the forms after it (`::alias/k`)."
  {:malli/schema [:function [:=> [:cat :symbol :string] [:map [:forms [:vector :map]] [:tail :string]]]
                  [:=> [:cat :symbol :string :seon.host/fn] [:map [:forms [:vector :map]] [:tail :string]]]]}
  ([nsym text] (read-forms nsym text identity))
  ([nsym ^String text step]
   (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
         starts (reader/line-starts text)
         off (fn [line col] (+ (starts (dec line)) (dec col)))]
     (loop [acc [] prev 0 nsym nsym]
       (let [form (binding [*ns* (or (find-ns nsym) *ns*)] (read {:eof ::eof} r))]
         (if (= ::eof form)
           {:forms acc :tail (subs text prev)}
           (let [end (off (.getLineNumber r) (.getColumnNumber r))
                 start (lead-end text prev end)
                 nsym' (if (and (seq? form) (= 'ns (first form)) (symbol? (second form))) (second form) nsym)]
             (recur (conj acc (step {:form form :src (subs text start end) :lead (subs text prev start) :ns nsym'}))
                    end nsym'))))))))

(defn- head-var
  "The var form's head names in nsx, or nil."
  {:malli/schema [:=> [:cat :any :seon.host/namespace] [:maybe :seon.host/var]]}
  [form nsx]
  (when (and (seq? form) (symbol? (first form)))
    (let [v (ns-resolve nsx (first form))] (when (var? v) v))))

(defn- analyze
  "form's analyzed node in namespace nsym, without running it."
  {:malli/schema [:=> [:cat :symbol :any] :map]}
  [nsym form]
  (let [nsx (program/ensure-ns! nsym)]
    (binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns (str nsym)) form))))

(defn analyze-form
  "The compiler's account units of form in namespace nsym, analyzed without running it. A
  form that sets up its namespace's environment is run first (the ns form, a defmacro whose
  macro is not defined yet), since what follows it is analyzed against that environment
  (K1). The ns form always runs, as loading the file would run it: its requires and aliases
  are the file's, whatever the namespace held before (a binary's own namespace, one of
  test/'s that main's program loaded with its old ns form, or one another namespace's
  require marked loaded), and running it again on the namespace it set up changes nothing.
  Answers {:units [...] :ns-form? b}."
  {:malli/schema [:=> [:cat :symbol :any] :map]}
  [nsym form]
  (let [nsx (program/ensure-ns! nsym)
        hv (head-var form nsx)
        ns-form? (= #'clojure.core/ns hv)
        macro? (= #'clojure.core/defmacro hv)]
    (when (or ns-form?
              (and macro? (not (some-> (ns-resolve nsx (second form)) bound?))))
      (binding [*ns* nsx] (eval form)))
    {:units [(program/unit-account (analyze nsym form))] :ns-form? ns-form?}))

(defn accounts
  "The program accounts of text's forms in namespace nsym, in file order: each form's
  account with its exact text, lead and order (its index), and a form that is none of the
  program's (a comment block, a top-level call) as a text form, so the file is whole.
  Answers {:accounts [...] :tail}."
  {:malli/schema [:=> [:cat :symbol :string] [:map [:accounts [:vector :map]] [:tail :string]]]}
  [nsym text]
  (let [{:keys [forms tail]} (read-forms nsym text
                                         (fn [{:keys [form ns] :as f}]
                                           (merge f (analyze-form (if (and (seq? form) (= 'ns (first form))) nsym ns) form))))
        seen (volatile! {})]
    {:tail tail
     :accounts (vec (map-indexed
                      (fn [i {:keys [form src lead ns units ns-form?]}]
                        (let [k (get (vswap! seen update [ns src] (fnil inc -1)) [ns src])
                              base {:units units :src src :form form :ns ns :ns-after ns :ns-form? ns-form? :ns-meta (when ns-form? (meta (second form))) :before nil :lead lead :order i :k k}]
                          (or (program/account base)
                              {:id (str ns "/text-" i) :src src :ns ns :lead lead :order i})))
                      forms))}))

(def ^:private defining-heads
  "The heads of the top-level forms that define a var by their second element."
  '#{def defn defn- defmacro defmulti defonce defprotocol defchecked deftest})

(defn defining-ids
  "The form id of the form defining each var in text, the file of namespace nsym: {qualified var
  symbol form-id}, the id seon.program/account gives the form (the namespace and the form's exact
  text, and its occurrence among the same text), but found by the form's head and name alone,
  with no analysis and nothing run: what a file at an old commit would have recorded, read without
  loading it. A head is known by its name whatever its namespace (clojure.core/defn, seon.contract/defn);
  a form defined by a macro of another name is not found."
  {:malli/schema [:=> [:cat :symbol :string] [:map-of :qualified-symbol :string]]}
  [nsym text]
  (let [seen (volatile! {})]
    (into {} (keep (fn [{:keys [form src ns]}]
                     (let [k (get (vswap! seen update [ns src] (fnil inc -1)) [ns src])]
                       (when (and (seq? form) (symbol? (first form)) (contains? defining-heads (symbol (name (first form)))) (symbol? (second form)))
                         [(symbol (str ns) (name (second form))) (program/form-id ns src k)]))))
          (:forms (read-forms nsym text)))))

(defn text-tx
  "The facts of text's forms as namespace nsym's program on db (seon.program/account-tx per
  form, in file order), with the namespace's file (relative path, or nil) and the text after
  its last form. A seed, an import and a test fixture are this."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :string [:maybe :string]] :seon.db/tx-data]}
  [db nsym text file]
  (let [{:keys [accounts tail]} (accounts nsym text)
        tx (vec (concat (mapcat #(program/account-tx db % (:order %)) accounts)
                        (when file (for [a accounts] {:db/id (str "form " (:id a)) :form/source true}))))]
    (cond-> tx
      ;; a file's tail is kept even when empty: no newline at its end is part of the file
      (or file (seq tail)) (conj (cond-> {:db/id (str "ns " nsym) :ns/name nsym :ns/tail tail}
                                   file (assoc :ns/file file))))))

(defn file-ns
  "The namespace a file's text declares: the name of its first form when that is an ns
  form, or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :symbol]]}
  [text]
  (let [form (binding [*ns* (the-ns 'user)] (read {:eof nil} (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))))]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form))) (second form))))

(defn- file-requires
  "The namespaces a file's ns form requires, from the compiler's analysis of it (not run)."
  {:malli/schema [:=> [:cat :symbol :string] [:set :symbol]]}
  [nsym text]
  (let [form (binding [*ns* (the-ns 'user)] (read {:eof nil} (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))))]
    (:requires (program/unit-account (analyze nsym form)))))

(defn schema-resource?
  "Is the relative path a schema resource: an .edn file under seon.config/schemas-dir?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path] (and (str/starts-with? path (str config/schemas-dir "/")) (str/ends-with? path ".edn")))

(defn schema-tx
  "The facts of one schema resource's text at path: the file with its text (write-back's
  base), and each entry as a :schema/key entity with its form, its file, and :schema/source
  (the binary holds it)."
  {:malli/schema [:=> [:cat :string :string] :seon.db/tx-data]}
  [path text]
  (into [{:schema.file/path path :schema.file/text text}]
        (for [[k v] (sort-by (comp str key) (sedn/decode text))]
          {:schema/key k :schema/form (binding [*print-namespace-maps* false] (sedn/encode v)) :schema/file path :schema/source true})))

(defn load-order
  "The Clojure files ({relative path text}) with an ns form, as [ns path text] in the order loading
  them takes: each after the files it requires, ties by path (the order import-tx analyzes them in,
  so a namespace the process never loaded is set up before a file that requires it)."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:vector [:tuple :symbol :string :string]]]}
  [files]
  (let [by-ns (into {} (for [[path text] files :when (str/ends-with? path ".clj") :let [nsym (file-ns text)] :when nsym] [nsym [path text]]))
        reqs (into {} (for [[nsym [_ text]] by-ns] [nsym (file-requires nsym text)]))]
    (vec (for [nsym (program/topo (vec (keys by-ns)) #(reqs %) #(first (by-ns %)))
               :let [[path text] (by-ns nsym)]]
           [nsym path text]))))

(defn import-tx
  "The facts of files ({relative path text}) as program on db: each namespace's file's forms
  with their exact text and the compiler's account (text-tx), its namespace carrying the
  file, and each schema resource's entries (schema-tx). Files are analyzed in the order
  loading them would take, each after the files it requires (ties by path), so a namespace
  the process never loaded is set up before a file that requires it. Any other file (no ns
  form, not a schema resource) is left out."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.db/tx-data]}
  [db files]
  (vec (concat (for [[nsym path text] (load-order files)
                     op (text-tx db nsym text path)]
                 op)
               (for [[path text] (sort-by key files) :when (schema-resource? path) op (schema-tx path text)] op))))

(defn source-files
  "The files db holds as program: each imported namespace's file (:ns/file) and each schema
  resource (imported, or named by an entry's :schema/file), as sorted relative paths."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :string]]}
  [db]
  (vec (sort (into #{} (concat (map :v (d/datoms db :aevt :ns/file))
                               (map :v (d/datoms db :aevt :schema.file/path))
                               (map :v (d/datoms db :aevt :schema/file)))))))

(def ^:private identities
  "The attributes that name a program entity (seon.facts.edn)."
  [:form/id :var/name :ns/name :method/key :impl/key :schema/key :schema.file/path])

(defn- read-nss
  "Each namespace of files ({relative path text}) read and analyzed in load order: {nsym
  {:accounts :tail :file}} (accounts, with its file's relative path)."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :symbol :map]]}
  [files]
  (into {} (for [[nsym path text] (load-order files)] [nsym (assoc (accounts nsym text) :file path)])))

(defn- ns-tx
  "The facts that make namespace nsym's program on db its file's (read, as read-nss answers
  it): each form's account less what db holds (seon.program/account-diff-tx), a form db
  lacks marked as the binary's (:form/source), and the namespace's tail and file when they
  changed."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :map] :seon.db/tx-data]}
  [db nsym {:keys [accounts tail file]}]
  (let [x (some->> (pdb/eid db :ns/name nsym) (d/entity db))]
    (vec (concat (mapcat #(program/account-diff-tx db %) accounts)
                 (for [a accounts :when (not (pdb/eid db :form/id (:id a)))] {:db/id (program/form-tid (:id a)) :form/source true})
                 (when (or (not= tail (:ns/tail x)) (not= file (:ns/file x)))
                   [{:db/id (program/ns-tid nsym) :ns/name nsym :ns/tail tail :ns/file file}])))))

(defn- made
  "The program entities the accounts make, by identity: each form, its namespace, the vars
  it defines and declares, and its methods and extensions."
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :any]]}
  [accts]
  (into #{} (mapcat (fn [{:keys [id ns defs declares methods impls]}]
                      (concat [[:form/id id] [:ns/name ns]]
                              (for [{v :var} defs] [:var/name v])
                              (for [v declares] [:var/name v])
                              (for [{:keys [multi dispatch]} methods] [:method/key (program/method-key multi dispatch)])
                              (for [{:keys [protocol type]} impls] [:impl/key (program/impl-key protocol type)]))))
        accts))

(defn- schema-paths-tx
  "The facts that make schema resources paths db's as files ({relative path text}) hold
  them: each file's entries (schema-tx; an entry db holds that no binary does keeps its
  :schema/source as it is), and every entry or file of them no file makes retracted."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] [:set :string]] :seon.db/tx-data]}
  [db files paths]
  (let [ents (concat (for [p paths dt (d/datoms db :avet :schema/file p)] (:e dt)) (keep #(pdb/eid db :schema.file/path %) paths))
        bare (into {} (for [e ents :let [x (d/entity db e)] :when (and (:schema/key x) (not (:schema/source x)))]
                        [(:schema/key x) (:schema/form x)]))
        new (vec (for [p (sort paths) :let [t (get files p)] :when t
                       op (schema-tx p t)]
                   (if (and (:schema/key op) (= (:schema/form op) (bare (:schema/key op)))) (dissoc op :schema/source) op)))
        mk (set (for [op new k [:schema/key :schema.file/path] :let [v (get op k)] :when (some? v)] [k v]))]
    (into (vec (for [e (distinct ents) :let [x (d/entity db e)
                                             k (if (:schema/key x) [:schema/key (:schema/key x)] [:schema.file/path (:schema.file/path x)])]
                     :when (not (mk k))]
                 [:db/retractEntity e]))
          new)))

(defn- program-tx
  "The facts that make db's program the namespaces read ({nsym {:accounts :tail :file}},
  nil for one whose file is gone) and the schema resources paths as files hold them: each
  namespace's forms less what db holds (ns-tx), and every program entity of them that no
  account makes any more retracted (a removed function's form and var, a removed file's
  namespace). Everything else on db is untouched. Cost: the namespaces' forms, not the
  program's."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :symbol [:maybe :map]] [:map-of :string [:maybe :string]] [:set :string]] :seon.db/tx-data]}
  [db read files paths]
  (let [nss (set (keep #(pdb/eid db :ns/name %) (keys read)))
        names (set (keys read))
        forms (set (for [e nss dt (d/datoms db :avet :form/ns e)] (:e dt)))
        owned (concat nss forms
                      (for [f forms :let [x (d/entity db f)] d (concat (:form/defines x) (:form/declares x))
                            :when (if-let [v (:var/name d)] (names (symbol (namespace v))) (not (:ns/name d)))]
                        (:db/id d)))
        mk (made (mapcat :accounts (vals read)))
        new (into [] (mapcat (fn [[nsym r]] (when r (ns-tx db nsym r)))) (sort-by key read))]
    (vec (distinct (concat (for [e (distinct owned)
                                 :let [x (d/entity db e) k (some #(when-some [v (get x %)] [% v]) identities)]
                                 :when (not (mk k))]
                             [:db/retractEntity e])
                           (schema-paths-tx db files paths)
                           new)))))

(defn replace-tx
  "The facts that make db's program the files' ({relative path text}), extending import-tx:
  every file's namespace read again (read-nss) and written where it differs from db
  (program-tx), the program of a namespace db holds a file of that no file holds any more
  retracted (a removed file's namespace), and every schema resource made the files'. Data,
  and the program of a namespace no file holds, are untouched. On a db holding no import,
  this is import-tx's program."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.db/tx-data]}
  [db files]
  (let [read (read-nss files)
        gone (for [dt (d/datoms db :aevt :ns/file) :let [n (:ns/name (d/entity db (:e dt)))] :when (not (contains? read n))] [n nil])]
    (program-tx db (into read gone) files
                (into (set (filter schema-resource? (keys files)))
                      (concat (map :v (d/datoms db :aevt :schema/file)) (map :v (d/datoms db :aevt :schema.file/path)))))))

(defn file-text
  "Namespace nsym's file as db holds it: its live forms in the order the image loads them
  (seon.program/load-order), each after its lead (a form recorded at a REPL has none: a
  blank line, or nothing before the first), then the text after its last form (a newline
  when it has none). So a helper recorded at a REPL after the function that now calls it is
  written before that function, and the file loads fresh as the image ran (step E run #10's
  merge wrote form-of after summary-of, its user); a file already in that order is written
  as it is, byte for byte."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :string]}
  [db nsym]
  (let [ne (pdb/eid db :ns/name nsym)
        fs (program/load-order (for [dt (d/datoms db :avet :form/ns ne) :when (program/live? db (:e dt))] (program/form-row db (:e dt))))]
    (str (apply str (map-indexed (fn [i f] (str (or (:lead f) (if (zero? i) "" "\n\n")) (:src f))) fs))
         (or (:ns/tail (d/entity db ne)) "\n"))))

(defn- interface
  "What a namespace's dependents read of it, from its forms ({:src :ns-form? :inline?
  :defs [[var kind]]}): each definition's name and kind, and the text of its ns form and of
  each form whose expansion or inlining is the dependents' (a macro, protocol, type or
  inline function). A body edit leaves it as it was."
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :any]]}
  [forms]
  (into #{} (mapcat (fn [{:keys [src ns-form? inline? defs]}]
                      (cond-> (set defs)
                        (or ns-form? inline? (some (comp #{:macro :protocol :type} second) defs)) (conj src))))
        forms))

(defn- db-interface
  "Namespace nsym's interface as db holds it: its live forms'."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:set :any]]}
  [db nsym]
  (let [ne (pdb/eid db :ns/name nsym) nf (some-> ne (->> (d/entity db)) :ns/form :db/id)]
    (interface (for [dt (when ne (d/datoms db :avet :form/ns ne)) :let [e (:e dt) x (d/entity db e)] :when (program/live? db e)]
                 {:src (:form/src x) :ns-form? (= e nf) :inline? (seq (:form/inline-defs x))
                  :defs (for [d (:form/defines x) :when (and (:var/name d) (= e (:db/id (:var/form d))))] [(:var/name d) (:var/kind d)])}))))

(defn- read-interface
  "A namespace's interface as read (read-nss's answer, or nil: its file is gone)."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :any]]}
  [r]
  (interface (for [a (:accounts r)]
               {:src (:src a) :ns-form? (:ns-form? a) :inline? (seq (:inline-defs a)) :defs (for [d (:defs a)] [(:var d) (:kind d)])})))

(defn- required-by
  "The namespaces whose forms require nsym on db (their :form/requires)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:set :symbol]]}
  [db nsym]
  (into #{} (keep #(when (= nsym (:v %)) (some-> (d/entity db (:e %)) :form/ns :ns/name))) (d/datoms db :aevt :form/requires)))

(defn refresh-tx
  "The facts that bring db's program up to date with the files that changed ({relative path
  text}, a removed file's text nil), and only those: each changed file's namespace read
  again, and, when what its dependents read of it changed (its interface: names and kinds,
  macros, protocols, types, the ns form), every namespace requiring it, read again from the
  file db holds (file-text), and so on through theirs; each changed schema resource. Each
  form is written only where it differs from db (program-tx). The same program as replace-tx
  of the whole tree, at the cost of what changed: a body edit reads one file and writes its
  changed forms, whatever the program's size or the file's dependents (task
  seon.source/an-import-reads-only-changed-files)."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]]] :seon.db/tx-data]}
  [db changed]
  (let [ns-of-path (fn [p] (some #(when (= p (:v %)) (:ns/name (d/entity db (:e %)))) (d/datoms db :aevt :ns/file)))
        texts (into {} (filter val) changed)
        gone (into {} (for [[p t] changed :when (and (nil? t) (str/ends-with? p ".clj")) :let [n (ns-of-path p)] :when n] [n nil]))
        read (loop [round texts check (keys gone) read gone]
               (if (and (empty? round) (empty? check))
                 read
                 (let [now (read-nss round)
                       read (into read now)
                       moved (filter #(not= (db-interface db %) (read-interface (read %))) (concat (keys now) check))]
                   (recur (into {} (for [n moved d (required-by db n) :when (not= d n)
                                         :let [p (:ns/file (d/entity db [:ns/name d]))] :when p]
                                     [p (or (texts p) (file-text db d))]))
                          [] read))))]
    (program-tx db read changed (set (filter schema-resource? (keys changed))))))

;; ---- a schema resource, entry by entry (v1 seon.cluster.source: same-value?, relaid,
;; schema-entry-edit, schema-file-text; rewrite-clj keeps every node it is not told to change)

(defn- same-value?
  "Whether the nodes a and b read as the same value (a node with no value, such as a comment,
  is never the same)."
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [a b]
  (and (node/sexpr-able? a) (node/sexpr-able? b) (= (node/sexpr a) (node/sexpr b))))

(defn- relaid
  "value (a parsed value node) in the layout of old, the value it replaces: a part reading as
  before keeps its node, and a collection of the same kind keeps its whitespace and comments
  between items, recursively, an added item taking the last separator; any other part is
  value's. Reads as value."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [old value]
  (cond
    (same-value? old value) old
    (and (= (node/tag old) (node/tag value)) (#{:vector :list :map :set} (node/tag value)))
    (let [children (vec (node/children old))
          at (vec (keep-indexed (fn [i n] (when-not (node/whitespace-or-comment? n) i)) children))
          items (vec (remove node/whitespace-or-comment? (node/children value)))
          gaps (mapv (fn [[a b]] (subvec children (inc a) b)) (partition 2 1 at))
          gap #(get gaps % (or (peek gaps) [(node/spaces 1)]))]
      (if (empty? at)
        value
        (node/replace-children
         old
         (-> (subvec children 0 (first at))
             (into (mapcat (fn [i item]
                             (cons (if (< i (count at)) (relaid (children (at i)) item) item)
                                   (when (< i (dec (count items))) (gap i))))
                           (range) items))
             (into (subvec children (inc (peek at))))))))
    :else value))

(defn- schema-entry-edit
  "children (a schema resource map node's children) with k's entry set to form (EDN text):
  its value replaced in the old value's layout (relaid), its entry and the whitespace before
  it removed when form is nil, or, for a new k, an entry inserted before the first greater key
  when the keys are sorted, else appended, separated as the file's last two entries are.
  prefix is the map's namespace qualifier."
  {:malli/schema [:=> [:cat [:vector :any] [:maybe :string] :qualified-keyword [:maybe :string]] [:vector :any]]}
  [children prefix k form]
  (let [significant (vec (keep-indexed (fn [i n] (when-not (node/whitespace-or-comment? n) i)) children))
        keys-at (vec (take-nth 2 significant))
        key-of #(node/sexpr (nth children %))
        at (some #(when (= k (key-of %)) %) keys-at)
        after-key #(some (fn [i] (when (> i %) i)) significant)
        value-node #(parser/parse-string form)
        entry #(vector (node/keyword-node (if (= prefix (namespace k)) (keyword (name k)) k)) (node/spaces 1) (value-node))
        ;; the gap before the last entry, from its last comment on (a comment ends its line)
        separator (if (< 1 (count keys-at))
                    (let [gap (subvec children (inc (after-key (peek (pop keys-at)))) (peek keys-at))
                          c (last (keep-indexed (fn [i n] (when (node/comment? n) i)) gap))]
                      (if c (into [(node/newlines 1)] (subvec gap (inc c))) gap))
                    [(node/spaces 1)])
        splice (fn [from to nodes] (into (into (subvec children 0 from) nodes) (subvec children to)))]
    (cond
      (and at form) (assoc children (after-key at) (relaid (nth children (after-key at)) (value-node)))
      at (let [before (last (filter #(< % at) significant))
               value (after-key at)]
           (if before
             (splice (inc before) (inc value) [])
             (splice at (or (after-key value) (count children)) [])))
      (nil? form) children
      (empty? keys-at) (splice 0 0 (entry))
      :else (let [ks (map key-of keys-at)
                  greater (when (= ks (sort ks)) (some #(when (pos? (compare (key-of %) k)) %) keys-at))]
              (if greater
                (splice greater greater (into (entry) separator))
                (let [end (inc (last significant))] (splice end end (into separator (entry)))))))))

(defn schema-file-text
  "text, one schema resource's EDN map (plain or namespaced), with each [k form] of edits
  applied (schema-entry-edit; a nil form removes k); every other node keeps its text."
  {:malli/schema [:=> [:cat :string [:sequential [:tuple :qualified-keyword [:maybe :string]]]] :string]}
  [text edits]
  (let [outer (z/find (z/of-string text) z/right #(#{:map :namespaced-map} (z/tag %)))
        [m prefix] (if (= :namespaced-map (z/tag outer))
                     [(z/find (z/down outer) z/right #(= :map (z/tag %))) (:prefix (z/node (z/down outer)))]
                     [outer nil])
        children (reduce (fn [children [k form]] (schema-entry-edit children prefix k form))
                         (vec (node/children (z/node m))) edits)]
    (z/root-string (z/replace m (node/replace-children (z/node m) children)))))

(defn schema-resource-text
  "The schema resource at path as db holds it: its imported text (an empty map for a new
  file) with every entry db holds for it whose form differs from the text's set, every key
  the text holds that db no longer does removed, and new keys added (schema-file-text)."
  {:malli/schema [:=> [:cat :seon.host/db :string] :string]}
  [db path]
  (let [fe (pdb/eid db :schema.file/path path)
        base (if fe (:schema.file/text (d/entity db fe)) "{}\n")
        held (sedn/decode base)
        now (into {} (for [dt (d/datoms db :avet :schema/file path) :let [x (d/entity db (:e dt))]]
                       [(:schema/key x) (:schema/form x)]))
        edits (concat (for [[k form] (sort-by (comp str key) now)
                            :when (not= (sedn/decode form) (get held k ::absent))]
                        [k form])
                      (for [k (sort-by str (keys held)) :when (not (contains? now k))] [k nil]))]
    (if (seq edits) (schema-file-text base (vec edits)) base)))

(defn- ns-of-file
  "The namespace db imported from the file at path, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :symbol]]}
  [db path] (d/q '[:find ?n . :in $ ?p :where [?e :ns/file ?p] [?e :ns/name ?n]] db path))

(defn files
  "{relative path text} of the files at paths that db holds as program: a namespace's file
  (file-text) or a schema resource (schema-resource-text)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:map-of :string :string]]}
  [db paths]
  (into (sorted-map)
        (for [p paths
              :let [text (if (schema-resource? p)
                           (schema-resource-text db p)
                           (some->> (ns-of-file db p) (file-text db)))]
              :when text]
          [p text])))

(defn changed-files
  "The files a merge's changes (seon.merge/three-way's :ours) touch, as db (after them) holds
  them: the file of each namespace whose forms they add, change or remove, and the resource of
  each schema entry they change; sorted relative paths."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:vector :string]]}
  [db changes]
  (vec (sort (distinct (for [[[k] [a b]] changes
                             :let [p (case k
                                       :form (:ns/file (d/entity db [:ns/name (:ns (or b a))]))
                                       :schema (:file (or b a))
                                       nil)]
                             :when p]
                         p)))))

(defn commit-message
  "The merges commit's message, from the facts of what merged: each member's task and
  run."
  {:malli/schema [:=> [:cat [:sequential [:map [:task :any] [:run :string]]]] :string]}
  [members]
  (str "Seon merge: " (str/join ", " (map (comp str :task) members)) "\n\n"
       (str/join "\n" (for [{:keys [task run]} members] (str "run " run " (task " task ")"))) "\n"))
