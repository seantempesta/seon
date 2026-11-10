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
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.program :as program] [seon.db :as pdb] [seon.reader :as reader] [seon.config :as config] [jolt.scheme] [clojure.java.io] [datahike.api :as d] [clojure.string :as str] [clojure.set :as set]
            [rewrite-clj.zip :as z] [rewrite-clj.node :as node] [rewrite-clj.parser :as parser] [jolt.analyzer] [jolt.host] [seon.edn :as sedn] [clojure.edn :as edn]))

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
                 start (reader/lead-end text prev end)
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
  "form's analyzed node in namespace nsym, without running it. private?, when given, is the analysis env's :private? function
  (jolt.host/ctx-for-ns): asked a var's namespace and name (strings) before the var's own :private meta, it answers :private, :public or nil
  (the meta answers). See docs/source.md#a-var-is-private-as-the-tree-analyzed-holds-it."
  {:malli/schema [:=> [:cat :symbol :any [:maybe :seon.host/fn]] :map]}
  [nsym form private?]
  (let [nsx (program/ensure-ns! nsym)]
    (binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns (str nsym) (if private? {:private? private?} {})) form))))

(def ^:private defining-heads
  "The heads of the top-level forms that define a var by their second element."
  '#{def defn defn- defmacro defmulti defonce defprotocol defchecked deftest})

(defn analyze-form
  "The compiler's account units of form in namespace nsym, analyzed without running it. A
  form that sets up its namespace's environment is run first (the ns form, a defmacro whose
  macro is not defined yet), since what follows it is analyzed against that environment
  (K1). The ns form always runs, as loading the file would run it: its requires and aliases
  are the file's, whatever the namespace held before (a binary's own namespace, one of
  test/'s that main's program loaded with its old ns form, or one another namespace's
  require marked loaded), and running it again on the namespace it set up changes nothing.
  Analysis never changes a var of the image: whether a var another namespace defines is public, when a file names it, is asked
  of private? (the :private? of the analysis env, see `tree-private`), the privacy of the tree being analyzed, never read off
  the image's var, which holds whichever tree was imported or run last (root's main held test-runner/tree-branch private while the
  tree made it public, and every import of main failed; head and base analyzed in one process left each other's
  privacy). Answers {:units [...] :ns-form? b}. See docs/source.md#a-var-is-private-as-the-tree-analyzed-holds-it."
  {:malli/schema [:function [:=> [:cat :symbol :any] :map] [:=> [:cat :symbol :any [:maybe :seon.host/fn]] :map]]}
  ([nsym form] (analyze-form nsym form nil))
  ([nsym form private?]
  (let [nsx (program/ensure-ns! nsym)
        hv (head-var form nsx)
        ns-form? (= #'clojure.core/ns hv)
        macro? (= #'clojure.core/defmacro hv)]
    ;; an alias the ns form points at another namespace (another tree's form, across a rename) is cleared first,
    ;; since Jolt refuses it while the old alias stands; every other alias is left as the live namespace has it
    (when ns-form?
      (let [wanted (into {} (for [spec (mapcat rest (filter #(and (seq? %) (= :require (first %))) (rest form)))
                                  :when (vector? spec) :let [[target & opts] spec as (:as (apply hash-map (rest spec)))] :when as]
                              [as target]))]
        (doseq [[a target] (ns-aliases nsx) :when (and (contains? wanted a) (not= (wanted a) (ns-name target)))]
          (ns-unalias nsx a))))
    (when (or ns-form?
              (and macro? (not (some-> (ns-resolve nsx (second form)) bound?))))
      (binding [*ns* nsx] (eval form)))
    {:units [(program/unit-account (analyze nsym form private?))] :ns-form? ns-form?})))

(defn- alias-held-not-declared
  "An alias form names that namespace nsx holds and its file's ns form, ns-form, doesn't declare, or nil: what only this process would resolve. A full name, a class's static and quoted data are no alias. See docs/source.md#main-s-program-follows-the-tree."
  {:malli/schema [:=> [:cat :any :any :any] [:maybe :symbol]]}
  [nsx ns-form form]
  (let [declared (into #{} (for [c (filter seq? ns-form) :when (= :require (first c)) spec (rest c) :when (vector? spec)
                                 :let [as (:as (apply hash-map (rest spec)))] :when as] as))
        held (set (keys (ns-aliases nsx)))
        walk (fn walk [x]
               (cond (and (seq? x) (= 'quote (first x))) nil
                     (symbol? x) (when-let [n (namespace x)] (let [a (symbol n)] (when (and (held a) (not (declared a))) a)))
                     (coll? x) (some walk x)))]
    (walk form)))

(defn accounts
  "The program accounts of text's forms in namespace nsym, in file order: each form's
  account with its exact text, lead and order (its index), and a form that is none of the
  program's (a comment block, a top-level call) as a text form, so the file is whole. private?, the tree's
  (`tree-private`), answers a named var's privacy to the analysis. Answers {:accounts [...] :tail}."
  {:malli/schema [:function [:=> [:cat :symbol :string] [:map [:accounts [:vector :map]] [:tail :string]]]
                  [:=> [:cat :symbol :string [:maybe :seon.host/fn]] [:map [:accounts [:vector :map]] [:tail :string]]]]}
  ([nsym text] (accounts nsym text nil))
  ([nsym text private?]
  (let [ns-form (volatile! nil)
        {:keys [forms tail]} (read-forms nsym text
                                         (fn [{:keys [form ns] :as f}]
                                           (let [n (if (and (seq? form) (= 'ns (first form))) nsym ns)]
                                             (when (and (seq? form) (= 'ns (first form))) (vreset! ns-form form))
                                             (when-let [a (and @ns-form (find-ns n) (alias-held-not-declared (find-ns n) @ns-form form))]
                                               (throw (ex-info (str "the form names alias " a " and this file's ns form doesn't require it: it resolves only in a process that already holds it") {:ns n :alias a})))
                                             (merge f (analyze-form n form private?)))))
        seen (volatile! {})]
    {:tail tail
     :accounts (vec (map-indexed
                      (fn [i {:keys [form src lead ns units ns-form?]}]
                        (let [k (get (vswap! seen update [ns src] (fnil inc -1)) [ns src])
                              base {:units units :src src :form form :ns ns :ns-after ns :ns-form? ns-form? :ns-meta (when ns-form? (meta (second form))) :before nil :lead lead :order i :k k}]
                          (or (program/account base)
                              {:id (str ns "/text-" i) :src src :ns ns :lead lead :order i})))
                      forms))})))

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

(defn first-head
  "The name (a string) of the head of the form text (one form, as a recorded form's :form/src), read in namespace nsym, or nil when
  that form is not a list headed by a symbol: what kind of definition a recorded form's source is
  (`defn`, `defn-`, `defmacro`, whatever its namespace: seon.contract/defn is `defn`), told by the
  reader and never by the text's first characters: seon.reader's, which reads `::alias/kw` without
  the namespace loaded (read-string in a process that never loaded nsym could not resolve
  `::flow/in-ports`, and bin/check health failed on any changed var holding one). See
  docs/health.md#a-function-that-cites-no-section."
  {:malli/schema [:=> [:cat :symbol :string] [:maybe :string]]}
  [nsym text]
  (let [f (first (reader/read-all text nsym))]
    (when (and (seq? f) (symbol? (first f))) (name (first f)))))

(defn mentions?
  "Whether a form of text (the file of namespace nsym) holds the symbol sym, unqualified, as a symbol
  anywhere in it: found by the reader, so a name in a string, a comment or inside a longer symbol is
  not a mention. What seon.doc/baseline-reads asks of an old file's forms."
  {:malli/schema [:=> [:cat :symbol :string :symbol] :boolean]}
  [nsym text sym]
  (boolean (some #(and (symbol? %) (nil? (namespace %)) (= sym %))
                 (mapcat #(tree-seq coll? seq (:form %)) (:forms (read-forms nsym text))))))

(defn defining-meanings
  "What a section citing each var defined in text (the file of namespace nsym) describes of it, as
  written: {qualified var symbol {:doc :contract}}, its docstring (a string followed by more of the
  form, so a def of a string value has none) and its :malli/schema as read, each only when present
  (the contract carries the arity, so no arglists); found by the form's head and name, as
  defining-ids finds them, read without loading. The same reading of an old file at a commit and of a
  var's form today, so the two compare (seon.doc/baseline-reads; DECISIONS Dogfood
  rulings: a section is stale when a cited var's contract or docstring changed)."
  {:malli/schema [:=> [:cat :symbol :string] [:map-of :qualified-symbol :map]]}
  [nsym text]
  (into {} (keep (fn [{:keys [form ns]}]
                   (when (and (seq? form) (symbol? (first form)) (contains? defining-heads (symbol (name (first form)))) (symbol? (second form)))
                     (let [more (nnext form)
                           doc (when (and (string? (first more)) (next more)) (first more))
                           more (if doc (rest more) more)
                           attr (when (and (map? (first more)) (next more)) (first more))]
                       [(symbol (str ns) (name (second form)))
                        (cond-> {}
                          (or doc (:doc attr)) (assoc :doc (or doc (:doc attr)))
                          (:malli/schema attr) (assoc :contract (:malli/schema attr)))]))))
        (:forms (read-forms nsym text))))

(defn text-tx
  "The facts of text's forms as namespace nsym's program on db (seon.program/account-tx per
  form, in file order), with the namespace's file (relative path, or nil) and the text after
  its last form. A seed, an import and a test fixture are this."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :string [:maybe :string]] :seon.db/tx-data]}
  [db nsym text file]
  (let [{:keys [accounts tail]} (accounts nsym text nil)
        tx (vec (concat (mapcat #(program/account-tx db (assoc %1 :order %2) %2) accounts (program/stable-places (vec (repeat (count accounts) nil))))
                        (when file (for [a accounts] {:db/id (str "form " (:id a)) :form/source true}))))]
    (cond-> tx
      ;; a file's tail is kept even when empty: no newline at its end is part of the file
      (or file (seq tail)) (conj (cond-> {:db/id (str "ns " nsym) :ns/name nsym :ns/tail tail}
                                   file (assoc :ns/file file))))))

(defn ns-text-adding
  "The recorded text old of a namespace's ns form, as new, an ns form evaluated at a REPL, leaves that namespace: Clojure's ns adds, so old with the libraries new requires that it doesn't, new's other clauses it lacks, and new's docstring, every other byte kept. old itself when new adds nothing. See docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :string :any] :string]}
  [old new]
  (let [o (read-string old)
        have (program/ns-requires o)
        clauses (filter seq? new)
        reqs (for [c clauses :when (= :require (first c)) spec (rest c)
                   :let [lib (if (sequential? spec) (first spec) spec)] :when (not (have lib))] spec)
        others (remove #(or (= :require (first %)) (some #{%} o)) clauses)
        doc (let [x (nth new 2 nil)] (when (and (string? x) (not= x (nth o 2 nil))) x))
        with-doc (fn [f] (if-not doc f
                           (let [nm (-> f z/down z/right) nx (z/right nm)]
                             (z/up (if (and nx (string? (z/sexpr nx))) (z/replace nx doc) (z/insert-right nm doc))))))
        with-reqs (fn [f] (if-not (seq reqs) f
                            (if-let [r (z/find (z/down f) z/right #(and (z/list? %) (= :require (z/sexpr (z/down %)))))]
                              (z/up (reduce z/append-child r reqs))
                              (z/append-child f (list* :require reqs)))))]
    (if (and (empty? reqs) (empty? others) (not doc))
      old
      (z/root-string (reduce z/append-child (-> (z/of-string old) with-doc with-reqs) others)))))

(defn- ns-form
  "A file's first form when it is an ns form naming its namespace, read once (not run), or nil."
  {:malli/schema [:=> [:cat :string] [:maybe [:sequential :any]]]}
  [text]
  (let [form (binding [*ns* (the-ns 'user)] (read {:eof nil} (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))))]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form))) form)))

(defn file-ns
  "The namespace a file's text declares: the name of its first form when that is an ns
  form, or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :symbol]]}
  [text] (second (ns-form text)))

(defn schema-tx
  "The facts of one schema resource's text at path: the file with its text (write-back's
  base), and each entry as a :schema/key entity with its form and its file."
  {:malli/schema [:=> [:cat :string :string] :seon.db/tx-data]}
  [path text]
  (into [{:file/path path :file/text text}]
        (for [[k v] (sort-by (comp str key) (sedn/decode text))]
          {:schema/key k :schema/form (binding [*print-namespace-maps* false] (sedn/encode v)) :schema/file path})))

(defn- lead-comment
  "The lines of text's comment before its first other line, each without its #, the #! line left out: a script's own words on itself. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [text]
  (let [ls (take-while #(str/starts-with? % "#") (str/split-lines text))
        ls (if (some-> (first ls) (str/starts-with? "#!")) (rest ls) ls)]
    (mapv #(str/trim (subs % 1)) ls)))

(defn- script-tx
  "The facts of the script at path holding text: the file, and what its lead comment says of it, for reading (docs/source.md#a-program-file)."
  {:malli/schema [:=> [:cat :string :string] :seon.db/tx-data]}
  [path text]
  (let [ls (lead-comment text)]
    [{:file/path path :file/text text :seon.script/says (str/join "\n" ls)
      :seon.script/usage (str/join "\n" (filter #(str/starts-with? % path) ls))}]))

(defn- held-text
  "The text the file at path holds on db: a file of one entry, as a db holds it (docs/source.md#a-program-file)."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]}
  [db path] (:file/text (d/entity db [:file/path path])))

(declare schema-resource-text config-text config-tx)

(def ^:private file-kinds
  "What a kind of file (seon.config/file-kind) differs by, as data: :tx, the facts of its text, and :text, its text as a db holds it. Nothing else in the import, the merge or the write-back
  asks a file's kind. See docs/source.md#a-program-file."
  {:schema {:tx #'schema-tx :text #'schema-resource-text}
   :config {:tx #'config-tx :text #'config-text}
   :script {:tx #'script-tx :text #'held-text}})

(defn modes-tx
  "The facts of modes ({relative path git mode}, the files as the import read them), only where they differ from db. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.db/tx-data]}
  [db modes]
  (vec (for [[p m] (sort modes) :when (not= m (:file/mode (d/entity db [:file/path p])))] {:file/path p :file/mode m})))

(defn modes
  "{relative path git mode} of the files at paths whose mode db holds, what a write-back commits with its text. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:map-of :string :string]]}
  [db paths]
  (into {} (for [p paths :let [m (:file/mode (d/entity db [:file/path p]))] :when m] [p m])))

(defn file-tx
  "The facts of the file at path, of a kind the allow-list names, holding text (file-kinds). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string :string] :seon.db/tx-data]}
  [path text] ((:tx (file-kinds (config/file-kind path))) path text))

(defn load-order
  "The Clojure files ({relative path text}) with an ns form, as [ns path text] in the order loading
  them takes: each after the files it requires, ties by path (the order import-tx analyzes them in,
  so a namespace the process never loaded is set up before a file that requires it)."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:vector [:tuple :symbol :string :string]]]}
  [files]
  (let [forms (into {} (for [[path text] files :when (str/ends-with? path ".clj") :let [f (ns-form text)] :when f] [(second f) [path text f]]))
        by-ns (update-vals forms pop)
        ;; the namespaces each ns form requires, from the compiler's analysis of it (not run)
        reqs (update-vals forms (fn [[_ _ f]] (:requires (program/unit-account (analyze (second f) f nil)))))]
    (vec (for [nsym (program/topo (vec (keys by-ns)) #(reqs %) #(first (by-ns %)))
               :let [[path text] (by-ns nsym)]]
           [nsym path text]))))

(defn import-tx
  "The facts of files ({relative path text}) as program on db: each namespace's file's forms
  with their exact text and the compiler's account (text-tx), its namespace carrying the
  file, and each file the allow-list names (file-tx). Files are analyzed in the order
  loading them would take, each after the files it requires (ties by path), so a namespace
  the process never loaded is set up before a file that requires it. Any other file (no ns
  form, not on the allow-list) is left out."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.db/tx-data]}
  [db files]
  (vec (concat (for [[nsym path text] (load-order files)
                     op (text-tx db nsym text path)]
                 op)
               (for [[path text] (sort-by key files) :when (config/file-kind path) op (file-tx path text)] op))))

(defn source-files
  "The files db holds as program: each imported namespace's file (:ns/file) and each schema
  resource (imported, or named by an entry's :schema/file), as sorted relative paths."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :string]]}
  [db]
  (vec (sort (into #{} (concat (map :v (d/datoms db :aevt :ns/file))
                               (map :v (d/datoms db :aevt :file/path))
                               (map :v (d/datoms db :aevt :schema/file))
                               (map :v (d/datoms db :aevt :config/file)))))))

(def ^:private identities
  "The attributes that name a program entity (seon.facts.edn)."
  [:form/id :var/name :ns/name :method/key :impl/key :schema/key :config/key :file/path])

(def ^:private mark-loaded!
  "Jolt's loader's mark (ldr-mark-loaded!, by namespace name): a require of the namespace answers it loaded."
  (delay (jolt.scheme/proc "ldr-mark-loaded!")))

(defn- in-checkout?
  "Whether the file of namespace nsym, at relative path p (as its db or its tree records it), exists in the
  checkout: a namespace whose file the checkout lacks (another tree's, e.g. one since renamed) no require
  of this process can load from disk."
  {:malli/schema [:=> [:cat :symbol [:maybe :string]] :boolean]}
  [nsym p]
  (boolean (and p (.exists (java.io.File. (str (config/checkout) "/" p))))))

(defn- text-privacy
  "{var-symbol private?} of the vars namespace nsym's text defines at its top level (def, defn, defn-, defonce, defmacro, defmulti, defprotocol,
  deftest): private when the form is a defn- or its name carries ^:private. Read, not analyzed."
  {:malli/schema [:=> [:cat :symbol :string] [:map-of :symbol :boolean]]}
  [nsym text]
  (into {} (for [{:keys [form]} (:forms (read-forms nsym text))
                 :when (and (seq? form) (symbol? (first form)) (symbol? (second form)) (contains? defining-heads (symbol (name (first form)))))]
             [(second form) (boolean (or (= "defn-" (name (first form))) (:private (meta (second form)))))])))

(defn tree-private
  "The :private? function (the analysis env's, jolt.host/ctx-for-ns) for analyzing files ({relative path text}), the changed files of a tree,
  over db, the tree's program as far as it holds it: asked a var's namespace and name (strings) it answers :private or :public by how the
  tree defines it (a namespace the files define: its forms' own defn- / ^:private; another namespace the db holds the var of: its :var/meta),
  and nil for a namespace the tree does not define (clojure.core, a library), which the image's var answers, since the tree cannot change it.
  Never reads or changes the image's var. See docs/source.md#a-var-is-private-as-the-tree-analyzed-holds-it."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] :seon.host/fn]}
  [db files]
  (let [texts (into {} (for [[p t] files :when (str/ends-with? p ".clj") :let [f (ns-form t)] :when f] [(second f) t]))
        read (volatile! {})]
    (fn [vns vname]
      (let [nsym (symbol vns)]
        (if-let [t (get texts nsym)]
          (let [m (or (get @read nsym) (get (vswap! read assoc nsym (text-privacy nsym t)) nsym))]
            (when-some [p (get m (symbol vname))] (if p :private :public)))
          (when-let [e (d/entity db [:var/name (symbol vns vname)])]
            (if (contains? (set (:var/meta e)) :private) :private :public)))))))

(defn- read-nss
  "Each namespace of files ({relative path text}) read and analyzed in load order: {nsym
  {:accounts :tail :file}} (accounts, with its file's relative path). A namespace the files define
  whose file the checkout lacks (another tree's, e.g. one since renamed: in-checkout?) is marked loaded
  once analyzed, so a later file's require of it answers from the analysis, never a load from disk that
  cannot find it; one whose file the checkout has is never marked (a real require must still load it). tree, every file
  the same import has read (files among them, refresh-tx's earlier rounds too), is what a named var's privacy is asked of
  (`tree-private`), never only this round's files. See docs/source.md#main-s-program-follows-the-tree."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string] [:map-of :string :string]] [:map-of :symbol :map]]}
  [db files tree]
  (let [private? (tree-private db tree)]
    (reduce (fn [m [nsym path text]]
              (let [a (assoc (accounts nsym text private?) :file path)]
                (when-not (in-checkout? nsym path) (@mark-loaded! (str nsym)))
                (assoc m nsym a)))
            {} (load-order files))))

(defn- ns-tx
  "The facts that make namespace nsym's program on db its file's (read, as read-nss answers
  it): each form's account less what db holds (seon.program/account-diff-tx), a form db
  lacks marked as the binary's (:form/source), and the namespace's tail and file when they
  changed."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :map] :seon.db/tx-data]}
  [db nsym {:keys [accounts tail file]}]
  (let [ne (pdb/eid db :ns/name nsym)
        x (some->> ne (d/entity db))
        ;; {form id, its place}: one read of the namespace's forms
        held (when ne (into {} (for [dt (d/datoms db :avet :form/ns ne) :let [f (d/entity db (:e dt))]] [(:form/id f) (:form/order f)])))
        ;; a form keeps its place while the file keeps its order, so an edit writes what it changed
        places (program/stable-places (mapv #(get held (:id %)) accounts))]
    (vec (concat (mapcat #(program/account-diff-tx db (assoc %1 :order %2)) accounts places)
                 (for [a accounts :when (not (contains? held (:id a)))] {:db/id (program/form-tid (:id a)) :form/source true})
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

(defn- file-paths-tx
  "The facts that make the files at paths db's as files ({relative path text}) hold
  them: each file's facts (file-tx), and every entry or file of them no file makes retracted."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] [:set :string]] :seon.db/tx-data]}
  [db files paths]
  (let [ents (concat (for [p paths a [:schema/file :config/file] dt (d/datoms db :avet a p)] (:e dt)) (keep #(pdb/eid db :file/path %) paths))
        new (vec (for [p (sort paths) :let [t (get files p)] :when t
                       op (file-tx p t)]
                   op))
        names [:schema/key :config/key :file/path]
        mk (set (for [op new k names :let [v (get op k)] :when (some? v)] [k v]))]
    (into (vec (for [e (distinct ents) :let [x (d/entity db e)
                                             k (some #(when-some [v (get x %)] [% v]) names)]
                     :when (not (mk k))]
                 [:db/retractEntity e]))
          new)))

(defn edit-tx
  "The facts that make the file at path, of a kind the allow-list names, hold text on db: its entries made the text's, each one no longer in it retracted (file-paths-tx). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :seon.host/db :string :string] :seon.db/tx-data]}
  [db path text] (file-paths-tx db {path text} #{path}))

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
                           (file-paths-tx db files paths)
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
  (let [read (read-nss db files files)
        gone (for [dt (d/datoms db :aevt :ns/file) :let [n (:ns/name (d/entity db (:e dt)))] :when (not (contains? read n))] [n nil])]
    (program-tx db (into read gone) files
                (into (set (filter config/file-kind (keys files)))
                      (concat (map :v (d/datoms db :aevt :schema/file)) (map :v (d/datoms db :aevt :config/file)) (map :v (d/datoms db :aevt :file/path)))))))

(defn file-text
  "Namespace nsym's file as db holds it: its live forms in the order the image loads them
  (seon.program/load-order), each after its lead (a form recorded at a REPL has none, or only its comment: a
  blank line, or nothing before the first, and the comment on its own line), then the text after its last form (a newline
  when it has none). So a helper recorded at a REPL after the function that now calls it is
  written before that function, and the file loads fresh as the image ran (step E run #10's
  merge wrote form-of after summary-of, its user); a file already in that order is written
  as it is, byte for byte."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :string]}
  [db nsym]
  (let [ne (pdb/eid db :ns/name nsym)
        fs (program/load-order (for [dt (d/datoms db :avet :form/ns ne) :when (program/live? db (:e dt))] (program/form-row db (:e dt))))]
    (str (apply str (map-indexed (fn [i f] (let [sep (if (zero? i) "" "\n\n") lead (:lead f)]
                                                  ;; a file's lead is what stood between its forms, ending at the form; a REPL's is its comment alone, which needs its line
                                                  (str (cond (nil? lead) sep (or (= "" lead) (re-find #"\s\z" lead)) lead :else (str sep lead "\n")) (:src f)))) fs))
         (or (:ns/tail (d/entity db ne)) "\n"))))

(defn- interface
  "What a namespace's dependents read of it, from its forms ({:src :inline? :defs [[var meta]]}):
  each definition's name and meta, and the text of each form whose expansion or inlining is the
  dependents' (a macro, protocol, type or inline function). A body edit leaves it as it was, and
  so does the ns form: a dependent resolves the names a namespace defines, never its requires."
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :any]]}
  [forms]
  (into #{} (mapcat (fn [{:keys [src inline? defs]}]
                      (cond-> (set defs)
                        (or inline? (some (comp #{:macro :protocol :type} second) defs)) (conj src))))
        forms))

(defn- db-interface
  "Namespace nsym's interface as db holds it: its live forms'."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:set :any]]}
  [db nsym]
  (let [ne (pdb/eid db :ns/name nsym)]
    (interface (for [dt (when ne (d/datoms db :avet :form/ns ne)) :let [e (:e dt) x (d/entity db e)] :when (program/live? db e)]
                 {:src (:form/src x) :inline? (seq (:form/inline-defs x))
                  :defs (for [d (:form/defines x) :when (and (:var/name d) (= e (:db/id (:var/form d))))] [(:var/name d) (set (:var/meta d))])}))))

(defn- read-interface
  "A namespace's interface as read (read-nss's answer, or nil: its file is gone)."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :any]]}
  [r]
  (interface (for [a (:accounts r)]
               {:src (:src a) :inline? (seq (:inline-defs a)) :defs (for [d (:defs a)] [(:var d) (set (:meta d))])})))

(defn required-by
  "The namespaces whose forms require nsym on db (their :form/requires)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:set :symbol]]}
  [db nsym]
  (into #{} (keep #(when (= nsym (:v %)) (some-> (d/entity db (:e %)) :form/ns :ns/name))) (d/datoms db :aevt :form/requires)))

(defn requires
  "The namespaces namespace nsym's forms require on db (their :form/requires), nsym left out."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:set :symbol]]}
  [db nsym]
  (disj (set (d/q '[:find [?r ...] :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?f :form/requires ?r]] db nsym)) nsym))

(defn- dependents
  "The namespaces that need reading again because namespace nsym lost lost (interface entries): those
  requiring it when a macro, protocol, type or inline function's text changed (the text is entry
  :src, a string: what it expands to is theirs), else those among them with a form that references
  a name nsym lost (a form's :form/refs)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol [:set :any]] [:set :symbol]]}
  [db nsym lost]
  (let [requirers (required-by db nsym)]
    (if (some string? lost)
      requirers
      (into #{} (comp (mapcat (fn [[v _]] (d/datoms db :avet :form/refs v)))
                      (keep #(some-> (d/entity db (:e %)) :form/ns :ns/name))
                      (filter requirers))
            lost))))

(defn required-files
  "Files ({relative path text}) with the files db holds of what they require, and so on, for each namespace loaded? says this process hasn't: analyzing a file runs its ns form, and a
  namespace no source root reaches (test/'s, in a root) is set up only by reading it first, as a whole tree's read does. Their text is db's, so reading them writes nothing. See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string] ifn?] [:map-of :string :string]]}
  [db files loaded?]
  (let [libs (fn [f] (for [c (rest f) :when (and (seq? c) (= :require (first c))) x (rest c)] (if (sequential? x) (first x) x)))]
    (loop [out files todo (vec (for [[p t] files :when (str/ends-with? p ".clj")] t))]
      (if-let [t (peek todo)]
        (let [more (into {} (for [r (some-> (ns-form t) libs) :when (and (symbol? r) (not (loaded? r)))
                                  :let [p (:ns/file (d/entity db [:ns/name r]))] :when (and p (not (contains? out p)))]
                              [p (file-text db r)]))]
          (recur (into out more) (into (pop todo) (vals more))))
        out))))

(defn refresh-tx
  "The facts that bring db's program up to date with the files that changed ({relative path
  text}, a removed file's text nil), and only those: each changed file's namespace read
  again, and, when what its dependents read of it was lost (its interface: names and kinds, macros,
  protocols, types), every namespace requiring it that references a name lost (or any, for a macro,
  protocol, type or inline function's text), read again from the file db holds (file-text), and so on through theirs; each changed schema resource. Each
  form is written only where it differs from db (program-tx). The same program as replace-tx
  of the whole tree, at the cost of what changed: a body edit reads one file and writes its
  changed forms, whatever the program's size or the file's dependents (task
  seon.source/an-import-reads-only-changed-files). A dependent whose file the change removes is never
  read again."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]]] :seon.db/tx-data]}
  [db changed]
  (let [ns-of-path (fn [p] (some #(when (= p (:v %)) (:ns/name (d/entity db (:e %)))) (d/datoms db :aevt :ns/file)))
        texts (into {} (filter val) changed)
        gone (into {} (for [[p t] changed :when (and (nil? t) (str/ends-with? p ".clj")) :let [n (ns-of-path p)] :when n] [n nil]))
        read (loop [round texts check (keys gone) read gone tree {}]
               (if (and (empty? round) (empty? check))
                 read
                 (let [files (required-files db round #(contains? (loaded-libs) %))
                       ;; a later round's privacy is the tree's as every round read it: a dependent read again names a var an
                       ;; earlier round's file made public, which db still holds as private; a file read before keeps the text
                       ;; it was read with (required-files adds db's text of a namespace this process has not loaded)
                       tree (merge files tree)
                       now (read-nss db files tree)
                       read (into read now)
                       ;; what the namespace had that it no longer has: a name removed or of another kind, a
                       ;; macro, protocol, type or inline function changed (what was added breaks no form)
                       lost (into {} (for [n (concat (keys now) check) :let [l (set/difference (db-interface db n) (read-interface (read n)))] :when (seq l)] [n l]))]
                   ;; a dependent whose file the change removes is gone, never read again from db's text
                   ;; (the old text of a removed file requires what the change removed with it)
                   (recur (into {} (for [[n l] lost d (dependents db n l) :when (not= d n)
                                         :let [p (:ns/file (d/entity db [:ns/name d]))] :when (and p (not (and (contains? changed p) (nil? (changed p)))))]
                                     [p (or (texts p) (file-text db d))]))
                          [] read tree))))]
    (program-tx db read changed (set (filter config/file-kind (keys changed))))))

(defn tx-of
  "The facts that bring db's program up to date with files ({relative path text}): refresh-tx of them
  when changed? (they are the files that changed), else replace-tx of them (they are the tree). A
  file the running image cannot analyze (a form names a var the image holds as private and the
  import does not make public, or a var it lacks)
  answers :seon.source/unanalyzable, its message the analyzer's, never a throw: root's watcher goes on
  and the next batch tries again (docs/failure-points.md)."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] :boolean] [:or :seon.db/tx-data :map]]}
  [db files changed?]
  (try ((if changed? refresh-tx replace-tx) db files)
       (catch Throwable e
         (contract/error :seon.source/unanalyzable #'seon.source/tx-of (step/error-msg e) {:files (vec (sort (keys files)))}))))

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

(defn- read-raw
  "EDN text read as the file holds it: a tag aero resolves stays a tagged literal, so two texts compare as written. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string] :any]}
  [text] (edn/read-string {:default tagged-literal} text))

(defn- entries-file-text
  "The file of entries at path as db holds it, its entries named by key-attr, form-attr and file-attr: its imported text (an empty map for a new
  file) with every entry db holds for it whose form differs from the text's set, every key
  the text holds that db no longer does removed, and new keys added (schema-file-text)."
  {:malli/schema [:=> [:cat :seon.host/db :string :keyword :keyword :keyword] :string]}
  [db path key-attr form-attr file-attr]
  (let [fe (pdb/eid db :file/path path)
        base (if fe (:file/text (d/entity db fe)) "{}\n")
        held (read-raw base)
        now (into {} (for [dt (d/datoms db :avet file-attr path) :let [x (d/entity db (:e dt))]]
                       [(get x key-attr) (get x form-attr)]))
        edits (concat (for [[k form] (sort-by (comp str key) now)
                            :when (not= (read-raw form) (get held k ::absent))]
                        [k form])
                      (for [k (sort-by str (keys held)) :when (not (contains? now k))] [k nil]))]
    (if (seq edits) (schema-file-text base (vec edits)) base)))

(defn schema-resource-text
  "The schema resource at path as db holds it (entries-file-text)."
  {:malli/schema [:=> [:cat :seon.host/db :string] :string]}
  [db path] (entries-file-text db path :schema/key :schema/form :schema/file))

(defn config-text
  "The config file at path as db holds it (entries-file-text). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :seon.host/db :string] :string]}
  [db path] (entries-file-text db path :config/key :config/form :config/file))

(defn config-tx
  "The facts of the config file's text at path: the file with its text (write-back's base), and each entry as a :config/key entity with its form, the value's exact text with its tags unread (seon.config/of-entries reads it), and its file. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string :string] :seon.db/tx-data]}
  [path text]
  (let [items (remove node/whitespace-or-comment? (node/children (z/node (z/find (z/of-string text) z/right #(= :map (z/tag %))))))]
    (into [{:file/path path :file/text text}]
          (for [[k v] (sort-by (comp str first) (partition 2 items))]
            {:config/key (node/sexpr k) :config/form (node/string v) :config/file path}))))

(defn- ns-of-file
  "The namespace db imported from the file at path, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :symbol]]}
  [db path] (d/q '[:find ?n . :in $ ?p :where [?e :ns/file ?p] [?e :ns/name ?n]] db path))

(defn files
  "{relative path text} of the files at paths that db holds as program: a namespace's file
  (file-text) or a file the allow-list names, by its kind's :text (file-kinds)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] [:map-of :string :string]]}
  [db paths]
  (into (sorted-map)
        (for [p paths
              :let [kind (config/file-kind p)
                    text (if kind
                           ((:text (file-kinds kind)) db p)
                           (some->> (ns-of-file db p) (file-text db)))]
              :when text]
          [p text])))

(defn ns-file
  "The file namespace n is written in, as db holds it (its :ns/file), or nil: a namespace made at
  the REPL and never written to a file."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:maybe :string]]}
  [db n] (:ns/file (d/entity db [:ns/name n])))

(defn changed-files
  "The files a merge's changes (seon.merge/three-way's :ours) touch, as db (after them) holds
  them: the file of each namespace whose forms they add, change or remove, the resource of
  each schema entry they change, each script they change, and the page of each doc they change (its address's path,
  drawn by seon.doc/files); sorted relative paths."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:vector :string]]}
  [db changes]
  (vec (sort (distinct (for [[[k x] [a b]] changes
                             :let [p (case k
                                       :form (ns-file db (:ns (or b a)))
                                       (:schema :config :script) (:file (or b a))
                                       :section (first (str/split x #"#" 2))
                                       nil)]
                             :when p]
                         p)))))

(defn namespace-path
  "The file namespace n takes when it has none, by its name as Clojure's load path reads it: a test namespace's under test/, any other's under src/. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [n]
  (str (if (str/ends-with? (str n) "-test") "test/" "src/")
       (-> (str n) (str/replace "-" "_") (str/replace "." "/"))
       ".clj"))

(defn unfiled-forms
  "The definitions of the forms a merge's changes (seon.merge/three-way's :ours) add, change
  or remove whose namespace has no :ns/file on db (after them), by namespace: source a
  write-back cannot put in any file (changed-files leaves them out)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:map-of :symbol [:vector :seon.merge/def-key]]]}
  [db changes]
  (reduce (fn [m f] (update m (:ns f) (fnil into []) (:defines f)))
          (sorted-map)
          (for [[[k] [a b]] changes
                :let [f (or b a)]
                :when (and (= :form k) (nil? (ns-file db (:ns f))))]
            f)))

(defn commit-message
  "The merges commit's message, from the facts of what merged: each member's task."
  {:malli/schema [:=> [:cat [:sequential [:map [:task :any]]]] :string]}
  [members]
  (str "Seon merge: " (str/join ", " (map (comp str :task) members)) "\n\n"
       (str/join "\n" (for [{:keys [task]} members] (str "task " task))) "\n"))
