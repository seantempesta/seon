(ns seon.model
  "The model, as one function of a provider value and a request: `complete` answers
  `:seon.model/completion` (the reply text, its calls of the one tool, `repl`, and its token
  usage) or the declared `:seon.model/provider-error`. Every request declares that one tool
  (`eval-tool`), so the model sees each evaluation's answer before its next call.

  Providers (`:seon.model/provider`, dispatched on :kind):
  - :none, no provider configured: every request answers the typed :seon.model/no-provider;
  - :scripted, a test fixture: the function named by :script answers the reply as data
    (:seon.model/script-reply: its text and its calls, each intent and code), as a provider's
    reply is read (it sees the task id, turn and messages, as a test double may);
  - :openai-compat, an OpenAI-compatible chat API (DeepSeek's: base URL plus
    /chat/completions) over jolt-lang/http-client's transport (BSD sockets and OpenSSL
    through jolt.ffi). The API key is read from the environment variable NAMED by
    :api-key-env; it is never logged, printed or stored.

  Root loads the HTTP transport and JSON before it forks anything, so every agent holds them
  compiled."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [seon.reader :as reader] [jolt.http.core :as http] [clojure.data.json :as json] [seon.config :as config] [seon.print.select :as select] [seon.lib :as lib]))

(defn- provider-error
  "The declared failure of a model call, as the one error map: the provider's HTTP 429 is
  :seon.model/rate-limited (the :model owner keeps the request and pauses, for its
  Retry-After when it gives one), any other failure :seon.model/provider-error. data: the
  status, the whole response body, the Retry-After in ms."
  {:malli/schema [:=> [:cat :string [:maybe :int] :map] :seon.model/failure]}
  [message status data]
  (contract/error (if (= 429 status) :seon.model/rate-limited :seon.model/provider-error) #'seon.model/provider-error message
                  (cond-> data status (assoc :status status))))

(defn- transport-error
  "The connection failed or died (connect refused or timed out, a reset, EOF mid-reply):
  :seon.model/transport, transient. The :model owner answers it to the asker once (seon.fx's outcome
  re-queues only :seon.model/rate-limited), and the agent's run ends :error; nothing retries it
  (docs/model.md#failures)."
  {:malli/schema [:=> [:cat :string :map] :seon.model/failure]}
  [message data]
  (contract/error :seon.model/transport #'seon.model/transport-error (str "transport: " message) data))

(defn- scripted
  "The reply of the function :script names (data: :seon.config.ai/script), loaded by name: a
  test fixture (seon.fixtures, in test/) that a scripted root reads from the checkout's source
  (seon.main/checkout-roots!). One this process cannot find is :seon.model/no-script."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] [:or :seon.model/completion :seon.model/no-script :seon.model/script-invalid]]}
  [{:keys [script]} request]
  (let [t0 (profile/now-ns)
        f (when (or (find-ns (symbol (namespace script))) (jolt.host/ns-source (namespace script)))
            (requiring-resolve script))]
    (if-not f
      (contract/error :seon.model/no-script #'seon.model/scripted
                      (str "the scripted provider's " script " is not on this process's source roots") {:script script})
      ;; a script answers a model's reply as data, as the provider's reply is read (docs/prompt.md):
      ;; {:text :calls [{:intent :code}]}, each call given its id here; no :calls is a message
      ;; that ends the turn
      (let [out (f request)
            s (contract/schema :seon.model/script-reply)]
        (if-not (contract/valid? s out)
          (contract/error :seon.model/script-invalid #'seon.model/scripted
                          (str "the scripted model " script " answered no :seon.model/script-reply") {:script script :seon.error/problems (contract/problems s out)})
          (let [{:keys [text calls]} out]
            ;; a reply's text is a string, empty when it gives none, as a provider's reply is read (reply-of)
            ;; no call: no :tool-calls, as a provider's reply is read
            (cond-> {:text (or text "")
                     :prompt-tokens (reduce + (map (comp select/estimate :content) (:messages request)))
                     :completion-tokens (select/estimate (apply str text (map :code calls))) :estimated true :ms (profile/ms-since t0)}
              (seq calls) (assoc :tool-calls (vec (map-indexed (fn [i c] (assoc c :id (str "call-" (:turn request) "-" i) :seon.model/at [i 0])) calls))))))))))

(defn- body-text
  "A read response's whole body as text: its buffered bytes, or every piece its pull yields
  until the server ends it."
  {:malli/schema [:=> [:cat :map] :string]}
  [r]
  (String. ^bytes (if-let [pull (:body-pull r)]
                    (http/concat-bas (loop [acc []] (if-let [b (pull)] (recur (conj acc b)) acc)))
                    (or (:body r) (byte-array 0)))
           "UTF-8"))

(defn- http-post
  "POST body (a JSON string) to url with headers and read the reply until the server ends
  it: {:status :body :headers} (body text). With stream? and a 200, the reply is not read:
  {:status :headers :pull}, pull answering the body's next piece of bytes, nil at its end, or the
  :seon.model/transport error when the socket died (the connection closed at either), for the
  worker's edge reader (docs/flow.md#effects-one-owner-and-its-workers-per-kind). No clock
  bounds the connect or the reply (a reasoning model streams for minutes): the OS's own
  connect failure or the socket's death ends it. The interop edge, the named failure point
  seon.model/http-post: a connect or socket failure is :seon.model/transport."
  {:malli/schema [:=> [:cat :string [:vector [:tuple :string :string]] :string :boolean]
                  [:or [:map [:status :int] [:body :string] [:headers :any]] [:map [:status :int] [:pull :any] [:close :any] [:headers :any]] :seon.model/failure]]}
  [url headers body stream?]
  (let [u (try (http/parse-url url) (catch Throwable e e))]
    (if (instance? Throwable u)
      (provider-error (str "the provider's URL does not parse: " url) nil {})
      (try
        (let [s (http/connect-stream (http/tget u :host) (http/effective-port u) (= "https" (http/tget u :protocol)) false nil nil)]
          (let [r (try (http/s-write s (http/build-request "POST" u headers body))
                       (http/read-response s nil "POST" nil true true)
                       (catch Throwable e (http/s-close s) (throw e)))]
            (if (and stream? (= 200 (:status r)))
              (let [pull (or (:body-pull r) (let [b (volatile! (:body r))] (fn [] (let [x @b] (vreset! b nil) x))))
                    open (volatile! true)
                    ;; once, whoever ends the stream: the reader at its end, an error, or when nobody reads on
                    close (fn [] (when @open (vreset! open false) (http/s-close s)))]
                {:status 200 :headers (:header-pairs r) :close close
                 :pull (fn []
                         (let [x (try (pull) (catch Throwable e (transport-error (step/error-msg e) {:url url})))]
                           (when (or (nil? x) (contract/error? x)) (close))
                           x))})
              (try {:status (:status r) :headers (:header-pairs r) :body (body-text r)}
                   (finally (http/s-close s))))))
        (catch Throwable e (transport-error (step/error-msg e) {:url url}))))))

(def tool-name
  "The name of the model's one tool: repl, which is no Clojure function, so a model does not write its code as (eval '(...)) inside it
  (Point's ruling 2026-11-11, from the ladder's rung 1). See docs/model.md#one-tool-repl."
  "repl")

(def eval-tool
  "The model's one tool (v1's): a call is one eval, its intent and its code, read as reply text is; a reply's message is its text, and each of its evals is a call of its own. See docs/model.md#one-tool-repl."
  {:type "function"
   ;; the response keys are those an agent reads in its tool message and at its handle; :text (the message itself), :record
   ;; (the id of the kept record) and :fns (the costliest functions, drawn on the page) are in :seon.eval/response and are not
   ;; shown to the agent, so they are left out here and in docs/prompt.md
   :function {:name tool-name
              :description "Run code at your REPL: Clojure exactly as you would type it. Your system message says how the calls run and what comes back."
              ;; in the order a model fills them: it thinks before the code
              :parameters {:type "object"
                           :properties (array-map :intent {:type "string" :description "What this eval is about to do. A sentence is fine."}
                                                  :code {:type "string" :description "Clojure to evaluate."})
                           :required ["intent" "code"]
                           :additionalProperties false}}})

(defn- wire-message
  "A conversation message as the chat API reads it: a reply's text as its content and each of its evals as a call of its own (its id, its intent and code as its JSON arguments), and its reasoning as reasoning_content (DeepSeek's thinking mode with tools needs every earlier reply's reasoning back, a 400 otherwise); a
  tool result named by its call's id. See docs/model.md#one-tool-repl."
  {:malli/schema [:=> [:cat :seon.model/message] :map]}
  [{:keys [role content tool-calls tool-call-id reasoning]}]
  (cond-> {:role role :content content}
    reasoning (assoc :reasoning_content reasoning)
    (seq tool-calls) (assoc :tool_calls (mapv (fn [{:keys [id code intent]}]
                                                {:id id :type "function"
                                                 :function {:name tool-name
                                                            ;; in the tool's order
                                                            :arguments (json/write-str (cond-> (array-map) intent (assoc :intent intent) true (assoc :code code)))}})
                                              tool-calls))
    tool-call-id (assoc :tool_call_id tool-call-id)))

(defn wire-groups
  "The provider-neutral messages msgs as the wire holds them, by index: for each wire message in order, the
  indices of msgs it is made of. A reply's assistant messages (those naming one :reply) fold into one, the first
  message's text and reasoning then each message's evals in order: an assistant message joins the newest assistant message
  only when it names the same :reply and only tool messages lie between. One without :reply stands alone, and so does each
  tool message (each call has its own result). The one fold: `wire-messages` makes its messages from these groups,
  and the debug page labels them by it. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat [:vector :seon.model/message]] [:vector [:vector nat-int?]]]}
  [msgs]
  (first
    (reduce (fn [[acc open] [j m]]
              (let [r (:reply m)
                    ;; the newest assistant group, open while only tool messages came after it
                    i (when (and open (= "assistant" (:role m)) r) (when (= r (:reply (nth msgs (first (nth acc open))))) open))]
                (cond i [(update acc i conj j) open]
                      (= "assistant" (:role m)) [(conj acc [j]) (count acc)]
                      (= "tool" (:role m)) [(conj acc [j]) open]
                      :else [(conj acc [j]) nil])))
            [[] nil] (map-indexed vector msgs))))

(defn wire-messages
  "The provider-neutral messages (seon.ctx.wire/request) in the OpenAI-compatible shape
  (DeepSeek's), the one provider format function: each wire message from its group (`wire-groups`), a
  reply as one assistant message whose text is its message and whose calls are its evals, then one tool message for each, its result with its handle; reasoning as
  reasoning_content, left out only when replay is false (:seon.config.ai/replay-reasoning; a config
  with thinking on refuses that at load). A message's :reply never reaches the wire. See docs/model.md#one-tool-repl."
  {:malli/schema [:=> [:cat [:maybe :boolean] [:vector :seon.model/message]] [:vector :map]]}
  [replay msgs]
  (mapv (fn [[i & more]]
          (wire-message (cond-> (reduce (fn [m j] (update m :tool-calls (fnil into []) (:tool-calls (nth msgs j)))) (dissoc (nth msgs i) :reply) more)
                          (false? replay) (dissoc :reasoning))))
        (wire-groups msgs)))

(defn request-body
  "The chat-completions request (data, before JSON) for request under an :openai-compat
  provider, streamed with its usage: its model, the messages, the one tool `eval-tool` the
  model may call or not (tool_choice auto: a reply with no call is a message to the user, and pauses the agent; DeepSeek
  refuses `required` with thinking on), its :max-tokens, earlier turns' reasoning as
  returned unless :replay-reasoning is false, and its temperature and DeepSeek's thinking mode and
  effort only when the provider sets them (absent: the provider's own default)."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request] :map]}
  [{:keys [model max-tokens temperature thinking reasoning-effort replay-reasoning]} request]
  (cond-> {:model model
           ;; thinking on, DeepSeek refuses any assistant message without reasoning_content: an eval Seon ran for the agent has none, so it says so
           :messages (cond->> (wire-messages replay-reasoning (:messages request))
                       (= :enabled thinking) (mapv #(if (and (= "assistant" (:role %)) (not (contains? % :reasoning_content))) (assoc % :reasoning_content "") %)))
           :tools [eval-tool] :tool_choice "auto" :max_tokens max-tokens
           ;; streamed: the reply arrives as it is made, so no clock has to guess its length
           :stream true :stream_options {:include_usage true}}
    temperature (assoc :temperature temperature)
    thinking (assoc :thinking {:type (name thinking)})
    reasoning-effort (assoc :reasoning_effort (name reasoning-effort))))

(defn- eval-call
  "One eval e as read ({\"intent\" \"code\"}) under id: {:id :code} with its :intent when it is a string; an eval whose code is missing or not a string is that eval's :problem, shape saying what is right, and :code holds what came, for the record. See docs/model.md#one-tool-repl."
  {:malli/schema [:=> [:cat :any :string :string] [:map [:id :string] [:code :string] [:intent {:optional true} :string] [:problem {:optional true} :string]]]}
  [e id shape]
  (let [code (when (map? e) (get e "code"))]
    (cond-> {:id id}
      (string? code) (assoc :code code)
      (not (string? code)) (assoc :code (json/write-str e) :problem (str "this eval was not run: its code is missing or not a string. " shape))
      (and (map? e) (string? (get e "intent"))) (assoc :intent (get e "intent")))))

(defn call-evals
  "An outside agent's reply, its arguments j as read ({\"message-md\" \"evals\"}), as its evals, each {:id :code :intent}, the first carrying the message: no evals is a reply of its message alone (none are run, and the agent waits, as it does for a reply of plain text), evals that are not a list one eval's :problem, what is wrong, so nothing is
  run, and an eval with no code as a string is that eval's. (A model's reply is its text and a call for each eval: `reply-of`.) See docs/server.md#ops."
  {:malli/schema [:=> [:cat :any :string] [:vector [:map [:id :string] [:code :string] [:intent {:optional true} :string] [:message-md {:optional true} :string] [:problem {:optional true} :string]]]]}
  [j id]
  (let [shape "A call is {\"message-md\": <string>, \"evals\": [{\"intent\": <string>, \"code\": <string>}, ...]}."
        es (when (map? j) (get j "evals"))
        msg (when (map? j) (get j "message-md"))
        stray (when (map? j) (sort (remove #{"message-md" "evals"} (keys j))))]
    (cond
      ;; no evals: a reply of its message alone, unless the call names something else (an old shape's code and intent are not run silently)
      (and (or (nil? es) (and (sequential? es) (empty? es))) (empty? stray)) []
      (or (nil? es) (and (sequential? es) (empty? es)))
      (cond-> [{:id id :code (json/write-str j) :problem (str "this call was not run: evals is missing, and the call names " (str/join ", " stray) " beside message-md. " shape)}]
        (string? msg) (assoc-in [0 :message-md] msg))
      (not (sequential? es))
      (cond-> [{:id id :code (json/write-str j) :problem (str "this call was not run: evals is not a list. " shape)}]
        (string? msg) (assoc-in [0 :message-md] msg))
      :else
      (vec (map-indexed
            (fn [i e]
              (cond-> (eval-call e (if (zero? i) id (str id "#" i)) shape)
                (and (zero? i) (string? msg)) (assoc :message-md msg)))
            es)))))

(def ^:private call-shape
  "What a model's call of the tool is, said in the answer to one that is not."
  "A call of eval is {\"intent\": <string>, \"code\": <string>}; write your message as the text of your reply and make one call for each eval.")

(defn- eval-of
  "A provider's tool call as the eval it is ({:id :code :intent}), from its tool name, its JSON arguments and its id; another tool's name or arguments cut off or not JSON are its :problem, :code holding what came. See docs/model.md#one-tool-repl."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string] :string] :map]}
  [tool arguments id]
  (let [a (or arguments "")
        j (try (json/read-str a) (catch Throwable _ nil))
        problem (cond (and tool (not= tool-name tool)) (str "there is one tool, " tool-name "; " tool " is not a tool here")
                      (not (map? j)) "the arguments are cut off or are not JSON")]
    (if problem
      {:id id :code a :problem (str "this call was not run: " problem ". " call-shape)}
      (eval-call j id call-shape))))

(def ^:private markup
  "What DeepSeek's own tool-call markup holds, its fullwidth bars and name, when its call comes back as text (deepseek-run4: the
  text ends in a closing parameter tag, and no opening one came with it, so no call can be read out of it)."
  "｜DSML｜")

(defn- text-as-call
  "A reply with no call whose text ends in the provider's tool-call markup as the one eval that says so: it meant a call, so it isn't a reply
  that parks the agent, and what it held is no call to run. Its usage is recorded as any turn's. See docs/model.md#one-tool-repl."
  {:malli/schema [:=> [:cat [:maybe :string] [:vector :map]] [:vector :map]]}
  [text calls]
  (if (and (empty? calls) text (str/includes? text markup))
    [{:id "text-as-call" :code text
      :problem (str "your call came back as text, with the tool-call markup in it, so nothing ran. Send it again as a call of your repl tool. " call-shape)}]
    calls))

(defn reply-of
  "A 200 reply (its chat-completion JSON as data) as the completion: its text, its eval calls
  ({:id :code}, in order), what it cost (prompt, completion, reasoning, cache-hit and cache-miss tokens)
  and ms; or the declared failure: no content and no call, or an empty content beside
  non-empty reasoning and no call (:seon.model/reasoning-ate-reply: thinking spent the
  budget of max-tokens)."
  {:malli/schema [:=> [:cat :map number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [j ms max-tokens]
  (let [msg (get-in j [:choices 0 :message])
        ;; each call is one eval and carries its place in the reply, [k 0]: its index (the provider's :index, else its position) and its
        ;; position within the call, which names its parts of the stream (<call>/<k>/eval-0/...)
        evals (vec (map-indexed (fn [pos c] (assoc (eval-of (get-in c [:function :name]) (get-in c [:function :arguments]) (str (:id c)))
                                                   :seon.model/at [(or (:index c) pos) 0]))
                                (:tool_calls msg)))
        ;; the reply's message is its text
        text (or (:content msg) (when (seq evals) ""))
        calls (text-as-call text evals)
        reasoning (:reasoning_content msg)
        u (:usage j)
        rt (get-in u [:completion_tokens_details :reasoning_tokens])
        cached (or (:prompt_cache_hit_tokens u) (get-in u [:prompt_tokens_details :cached_tokens]))
        ;; the reply stopped at max-tokens: its text is cut (kept; the forgiving reader repairs it)
        cut? (= "length" (get-in j [:choices 0 :finish_reason]))]
    (cond
      (and (str/blank? text) (empty? calls) (string? reasoning) (not (str/blank? reasoning)))
      (contract/error :seon.model/reasoning-ate-reply #'seon.model/reply-of
                      (str "the reply is empty: reasoning took " (or rt (count reasoning)) (if rt " tokens" " characters")
                           " of a " max-tokens "-token budget")
                      (cond-> {:reasoning-chars (count reasoning)}
                        rt (assoc :reasoning-tokens (long rt))
                        (:completion_tokens u) (assoc :completion-tokens (long (:completion_tokens u)))
                        max-tokens (assoc :max-tokens max-tokens)))
      (string? text)
      ;; a count the provider did not send is absent, never 0: nobody measured it
      (cond-> {:text text :ms ms}
        (:prompt_tokens u) (assoc :prompt-tokens (long (:prompt_tokens u)))
        (:completion_tokens u) (assoc :completion-tokens (long (:completion_tokens u)))
        (seq calls) (assoc :tool-calls calls)
        (get-in j [:choices 0 :finish_reason]) (assoc :finish-reason (str (get-in j [:choices 0 :finish_reason])))
        (string? (:model j)) (assoc :model (:model j))
        (and (string? reasoning) (not (str/blank? reasoning))) (assoc :reasoning reasoning)
        rt (assoc :reasoning-tokens (long rt))
        ;; DeepSeek's names, then the OpenAI-compatible cached count
        cached (assoc :cache-hit-tokens (long cached))
        (:prompt_cache_miss_tokens u) (assoc :cache-miss-tokens (long (:prompt_cache_miss_tokens u)))
        cut? (assoc :truncated? true))
      :else (provider-error "the reply carries no message content" 200 {}))))

(defn json-feed
  "A tool call's arguments, one flat JSON object of strings, fed to a reader a fragment at a time: answers [state' events] for the
  next fragment frag, an event [key :open] when a string value starts, [key text] for the decoded text it holds so far in this fragment
  (never a key's), and [key :end] when it closes. The state (nil to start) is the mode (between strings, in a key, in a value), the
  key waiting for its value, the key being read and any escape or unicode escape cut short at the fragment's end. What streams is display
  only: the committed reply is read from the whole arguments (`eval-of`). See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat [:maybe :map] :string] [:tuple :map [:vector :any]]]}
  [st frag]
  (let [n (count frag)
        {:keys [mode k kbuf esc hi] :or {mode :out kbuf [] hi nil}} st
        esc-char {\n "\n" \t "\t" \r "\r" \b "\b" \f "\f"}]
    (loop [i 0 mode mode k k kbuf kbuf esc esc hi hi sb [] ev []]
      (if (= i n)
        [{:mode mode :k k :kbuf kbuf :esc esc :hi hi}
         (if (and (= mode :val) (seq sb)) (conj ev [k (apply str sb)]) ev)]
        (let [c (nth frag i)
              i' (inc i)]
          (if (= mode :out)
            ;; a quote opens the key when no key is waiting for its value, else the value; an object's other characters are no string
            (if (= c \")
              (if k
                (recur i' :val k kbuf nil nil [] (conj ev [k :open]))
                (recur i' :key nil [] nil nil sb ev))
              (recur i' mode (if (= c \,) nil k) kbuf esc hi sb ev))
            ;; inside a string, a key or a value: one decoded piece or none
            (let [[done? piece esc' hi']
                  (cond
                    (string? esc) (let [e (str esc c)]
                                    (if (< (count e) 4)
                                      [false nil e hi]
                                      (let [cp (Integer/parseInt e 16)]
                                        (cond (<= 0xD800 cp 0xDBFF) [false nil nil cp]
                                              (and hi (<= 0xDC00 cp 0xDFFF)) [false (str (char (+ 0x10000 (bit-shift-left (- hi 0xD800) 10) (- cp 0xDC00)))) nil nil]
                                              (<= 0xD800 cp 0xDFFF) [false "\uFFFD" nil nil]
                                              :else [false (str (char cp)) nil nil]))))
                    (= esc :bs) (if (= (str c) "u") [false nil "" hi] [false (get esc-char c (str c)) nil hi])
                    (= c \\) [false nil :bs hi]
                    (= c \") [true nil nil hi]
                    :else [false (str c) nil hi])]
              (cond
                done? (if (= mode :key)
                        (recur i' :out (apply str kbuf) [] nil nil sb ev)
                        (recur i' :out nil kbuf nil nil [] (cond-> ev (seq sb) (conj [k (apply str sb)]) true (conj [k :end]))))
                (= mode :key) (recur i' mode k (cond-> kbuf piece (conj piece)) esc' hi' sb ev)
                :else (recur i' mode k kbuf esc' hi' (cond-> sb piece (conj piece)) ev)))))))))

(defn sse-init
  "The state a streamed reply's lines fold into (`sse-step`): the reply's pieces so far, and, when call (the model call's id)
  is given, what has been sent as stream messages for agent (offsets by id, ids still open). See
  docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :map]}
  [call agent]
  {:call call :agent agent :content [] :reasoning [] :calls (sorted-map) :finish nil :usage nil :model nil :data? false :raw []
   :texts {} :open [] :parts {}})

(defn- stream-id
  "The stream id of a string in tool call k's arguments, [id part], or nil for a string no one shows live: the eval's intent and code, under <call>/<k>/eval-0/. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :string :int :string] [:maybe [:tuple :string :string]]]}
  [call k field]
  (when (#{"intent" "code"} field) [(str call "/" k "/eval-0/" field) field]))

(defn- say
  "st with a stream message appended to msgs for id, part part of call (:in, :part, as data, so no reader splits an id): text (sent as the part's whole text so far, the vector of its pieces), :open (id starts, empty) or
  :end. Answers [st' msgs'] (st, msgs when the state streams nothing). See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat [:tuple :map [:vector :map]] :string :string [:or :string [:enum :open :end]]] [:tuple :map [:vector :map]]]}
  [[st msgs] id part what]
  (if-not (:call st)
    [st msgs]
    (let [text (get-in st [:texts id] [])
          open? (some #{id} (:open st))
          st (cond-> st (not open?) (-> (update :open conj id) (assoc-in [:parts id] part)))
          base {:stream id :in (:call st) :part part :agent (:agent st)}]
      (cond (= :end what) [(update st :open #(filterv (complement #{id}) %)) (conj msgs (assoc base :end true))]
            (= :open what) [st msgs]
            :else (let [text (conj text what)] [(assoc-in st [:texts id] text) (conj msgs (assoc base :text text))])))))

(defn- add-delta
  "st with a chunk's delta folded in: its content, reasoning and each tool call's pieces (id and name kept, arguments appended
  and read by `json-feed`), the messages those made. Answers [st' msgs]. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st delta]
  (let [call (:call st)
        piece (fn [acc k id]
                (if-let [x (get delta k)]
                  (let [[st msgs] (update acc 0 update (if (= k :content) :content :reasoning) conj x)]
                    (if (and call (seq x)) (say [st msgs] (str call "/" id) id x) [st msgs]))
                  acc))
        acc (-> [st []] (piece :content "content") (piece :reasoning_content "reasoning"))]
    (reduce (fn [[st msgs] {:keys [index id function]}]
              (let [c (get-in st [:calls index] {:function {:arguments []}})
                    c (cond-> c id (assoc :id id) (:name function) (assoc-in [:function :name] (:name function)))
                    frag (:arguments function)
                    [jst evs] (if (and frag call) (json-feed (:json c) frag) [(:json c) []])
                    c (cond-> (assoc c :json jst) frag (update-in [:function :arguments] conj frag))
                    st (assoc-in st [:calls index] c)]
                (reduce (fn [acc [field what]]
                          (if-let [[sid part] (stream-id call index field)] (say acc sid part what) acc))
                        [st msgs] evs)))
            acc (:tool_calls delta))))

(defn sse-step
  "One line of a streamed reply (server-sent events: data: lines, each a chat-completion chunk, ending data: [DONE]) folded
  into st (`sse-init`): [st' messages], the stream messages that line made ({:stream id :in call :part p :agent a :text pieces}, pieces the part's whole text so far as the vector of its pieces, shared, so a message costs O(1) and a dropped one loses nothing, then
  the same with :end true and no text when a tool call's string closes), none for a state with no call. Blank lines, comments
  and other fields are skipped; a line that is none of them is kept, for a reply that is one JSON document. The one parser of
  `sse-reply` and of the streamed call (`complete`). See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :string] [:tuple :map [:vector :map]]]}
  [st line]
  (let [l (str/trim line)]
    (cond
      (or (str/blank? l) (str/starts-with? l ":") (re-find #"^(event|id|retry):" l)) [st []]
      (str/starts-with? l "data:")
      (let [d (str/trim (subs l 5))]
        (if (= "[DONE]" d)
          [st []]
          (let [j (json/read-str d :key-fn keyword)
                [st msgs] (add-delta (assoc st :data? true) (or (get-in j [:choices 0 :delta]) {}))]
            [(cond-> st
               (get-in j [:choices 0 :finish_reason]) (assoc :finish (get-in j [:choices 0 :finish_reason]))
               (:usage j) (assoc :usage (:usage j))
               (and (:model j) (nil? (:model st))) (assoc :model (:model j)))
             msgs])))
      :else [(update st :raw conj line) []])))

(defn sse-close
  "The stream messages that end every id st still has open: a call's end, normal or in error. See `sse-step`."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [st]
  (mapv (fn [id] {:stream id :in (:call st) :part (get-in st [:parts id]) :agent (:agent st) :end true}) (:open st)))

(defn- sse-json
  "The chat-completion JSON (as data) the lines folded into st make: what `sse-reply` answers."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [msg (let [calls (:calls st)]
              (cond-> {}
                (seq calls) (assoc :tool_calls (mapv #(-> % (dissoc :json) (update-in [:function :arguments] (fn [fs] (apply str fs)))) (vals calls)))
                (seq (:content st)) (assoc :content (apply str (:content st)))
                (seq (:reasoning st)) (assoc :reasoning_content (apply str (:reasoning st)))))]
    (cond-> {:choices [{:message msg :finish_reason (:finish st)}]}
      (:usage st) (assoc :usage (:usage st))
      (:model st) (assoc :model (:model st)))))

(defn sse-reply
  "A streamed reply's text (server-sent events) folded into the one reply it streams: the content and reasoning
  concatenated in order, each tool call's pieces joined by its index (its id and name kept,
  its arguments appended), the last finish_reason, the usage the final chunk carries. Its lines go through `sse-step`."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (sse-json (reduce (fn [st line] (first (sse-step st line))) (sse-init nil nil) (str/split-lines text))))

(defn stream-reply
  "The completion (or the declared failure) a streamed call's folded state makes, in ms, as `parse-reply` makes it of the
  whole body: its data lines read as `sse-reply` does, or, when the server sent none, its lines as the one JSON document."
  {:malli/schema [:=> [:cat :map number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [st ms max-tokens]
  (reply-of (if (and (not (:data? st)) (seq (:raw st)))
              (json/read-str (str/join "\n" (:raw st)) :key-fn keyword)
              (sse-json st))
            ms max-tokens))

(defn parse-reply
  "A 200 reply's body text as the completion: a stream of server-sent events, or one
  chat-completion JSON document."
  {:malli/schema [:=> [:cat :string number? [:maybe :int]] [:or :seon.model/completion :seon.model/failure]]}
  [body ms max-tokens]
  (reply-of (if (str/starts-with? (str/triml body) "data:") (sse-reply body) (json/read-str body :key-fn keyword))
            ms max-tokens))

(defn- retry-after-ms
  "The response's Retry-After (seconds) in ms, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [header-pairs]
  (some-> (http/header-ci header-pairs "retry-after") str/trim parse-long (* 1000)))

(defn- openai-compat
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure
                   [:map [:stream [:map [:pull :any] [:shut :any] [:init :map] [:step :any] [:final :any] [:close :any]]]]]]}
  [{:keys [base-url api-key-env max-tokens] :as provider} request]
  (let [key (config/secret api-key-env)]
    (if (str/blank? key)
      (provider-error (str "the environment variable " api-key-env " is not set") nil {})
      (let [t0 (profile/now-ns)
            ;; a request that names its model call (:call) is read as it arrives: the reply is not read here, its pieces are the
            ;; caller's (docs/flow.md#effects-one-owner-and-its-workers-per-kind)
            r (http-post (str (str/replace base-url #"/+$" "") "/chat/completions")
                         [["Authorization" (str "Bearer " key)] ["Content-Type" "application/json"] ["Accept" "text/event-stream"]]
                         (json/write-str (request-body provider request))
                         (some? (:call request)))
            ms (profile/ms-since t0)]
        (cond
          (contract/error? r) r
          (not= 200 (:status r)) (provider-error (str "HTTP " (:status r)) (:status r)
                                                 (cond-> {:body (:body r)}
                                                   (retry-after-ms (:headers r)) (assoc :retry-after-ms (retry-after-ms (:headers r)))))
          (:pull r) {:stream {:pull (:pull r) :shut (:close r) :init (sse-init (:call request) (:agent request)) :step sse-step :close sse-close
                              :final (fn [st] (stream-reply st (profile/ms-since t0) max-tokens))}}
          :else (parse-reply (:body r) ms max-tokens))))))

(defn complete
  "The model's reply to request under provider. A request that names its model call (:call, with the
  asking :agent) is answered at the start of its reply, as {:stream {:pull :shut :init :step :close :final}} (:shut closes the connection, once): the
  reply's body is the caller's to read (seon.fx/worker-proc), :step folding each line into the state (and
  answering the stream messages it made), :final the completion of the last state, :close the messages that
  end the ids still open."
  {:malli/schema [:=> [:cat :seon.model/provider :seon.model/request]
                  [:or :seon.model/completion :seon.model/failure
                   [:map [:stream [:map [:pull :any] [:shut :any] [:init :map] [:step :any] [:final :any] [:close :any]]]]]]}
  [provider request]
  (case (:kind provider)
    :none (contract/error :seon.model/no-provider #'seon.model/complete
                          "no model provider is configured: start with --profile deepseek (a paid model) or --profile scripted (a test fixture), or --set :seon.config.ai/provider=..." {})
    :scripted (scripted provider request)
    :openai-compat (openai-compat provider request)))

(defn cost
  "What a turn's usage cost in USD at model's configured prices (:seon.config.ai/models, the
  off-peak rates; the provider charges twice at peak): its uncached prompt tokens at the input
  rate, its cached ones at the cached rate, the rest of its tokens (the completion) at the
  output rate. nil when unknown: a model with no prices in config, or a usage that does not
  split its prompt into cache hits and misses (the price depends on it)."
  {:malli/schema [:=> [:cat [:maybe :string] [:map [:seon.model-call/tokens {:optional true} :int]
                                              [:seon.model-call/cache-hit-tokens {:optional true} :int]
                                              [:seon.model-call/cache-miss-tokens {:optional true} :int]]]
                  [:maybe number?]]}
  [model {:seon.model-call/keys [tokens cache-hit-tokens cache-miss-tokens]}]
  (let [m (get (config/value :seon.config.ai/models) model)]
    (when (and m tokens cache-hit-tokens cache-miss-tokens)
      (/ (+ (* cache-miss-tokens (:seon.ai.model/input-usd-per-mtok m))
            (* cache-hit-tokens (:seon.ai.model/cached-input-usd-per-mtok m))
            (* (- tokens cache-hit-tokens cache-miss-tokens) (:seon.ai.model/output-usd-per-mtok m)))
         1e6))))
