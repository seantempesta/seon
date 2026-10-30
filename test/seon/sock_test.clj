(ns seon.sock-test
  "seon.sock's frames between two processes' ends: what a send answers and what the peer reads."
  (:require [clojure.test :refer [deftest is]] [seon.sock :as sock] [seon.lib :as lib]))

;; what the reader of a slow peer waits for: a frame from c, or nil once sent is realized and none came
(defn- read-while-sending [c sent]
  (loop []
    (let [m (sock/next-frame! c)]
      (cond (not= :none m) m
            (realized? sent) (let [m (sock/next-frame! c)] (when (not= :none m) m))
            :else (recur)))))

(deftest a-frame-larger-than-the-socket-buffer-reaches-a-slow-live-peer-whole
  ;; task seon.sock/a-frame-is-written-whole (docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data):
  ;; agent a2's 3 MB :tests answer failed whole in the kernel and seon.sock/write-all! read the errno as
  ;; :epipe, so root never heard it and its api held the ask forever. A write the kernel cannot take now
  ;; (EAGAIN on a non-blocking end, ENOBUFS) waits until the socket can take it, and the frame arrives
  ;; whole. The writer's end is non-blocking here so the full buffer answers EAGAIN, as a busy kernel answers
  ;; ENOBUFS; the peer reads only what is there (next-frame!), so it is slower than the writer.
  (let [[a b] (sock/socketpair)
        _ (sock/c-fcntl a 4 (bit-or 4 (sock/c-fcntl a 3 0)))
        v {:op :tests :id 24 :mem-mb 1312.4
           :result {:pass 9 :fail 0 :error 51 :ms 61234.5
                    :reports (vec (for [i (range 51)]
                                    {:var (str "#'seon.reconcile-test/a-test-" i) :type :error :exit {:signal 9}
                                     :message "jolt: out of memory: the heap ceiling of 2139350976 bytes was exceeded"
                                     :trace (apply str (repeat 1200 "gc-enforce-ceiling! enable-interrupts seon.root/start!/start!\n"))}))}}
        frame-bytes (alength (sock/frame v))
        sent (promise)
        w (doto (Thread. #(deliver sent (sock/send! a v))) (.setDaemon true) (.start))
        c (sock/conn b)
        got (read-while-sending c sent)]
    (.join w)
    (lib/c-close a) (lib/c-close b)
    (is (> frame-bytes (* 2 sock/BUF)) "the frame is larger than the socket's buffers")
    (is (= true @sent) "the send answers true: the peer is alive")
    (is (= v got) "the peer reads the whole value")))

(deftest a-frame-larger-than-the-socket-buffer-carries-its-descriptor-whole
  ;; task seon.sock/a-frame-is-written-whole: send-frame! with a descriptor sends it with the first
  ;; bytes (sendmsg) and the rest as plain writes from where that stopped; a full buffer waits, as
  ;; for a frame alone. The descriptor that arrives still reaches its socket's peer.
  (let [[a b] (sock/socketpair)
        [x y] (sock/socketpair)
        _ (sock/c-fcntl a 4 (bit-or 4 (sock/c-fcntl a 3 0)))
        v {:op :clone :k 7 :agent :a3 :db-t 536871125
           :branch-facts (vec (for [i (range 3000)] {:e (+ 1000 i) :a :seon.task/plan :v (apply str (repeat 900 "p"))}))}
        sent (promise)
        w (doto (Thread. #(deliver sent (sock/send! a v x))) (.setDaemon true) (.start))
        c (sock/conn b)
        got (read-while-sending c sent)
        _ (.join w)
        x2 (sock/take-fd! c)]
    (is (> (alength (sock/frame v)) (* 2 sock/BUF)) "the frame is larger than the socket's buffers")
    (is (= true @sent))
    (is (= v got) "the peer reads the whole value")
    (is (some? x2) "the descriptor came with it")
    (when x2
      (sock/send! x2 {:op :ping})
      (is (= {:op :ping} (sock/recv! (sock/conn y))) "the descriptor that arrived reaches its socket's peer")
      (lib/c-close x2))
    (run! lib/c-close [a b x y])))

(deftest a-write-the-kernel-refuses-names-its-errno
  ;; task seon.sock/a-frame-is-written-whole: a write that fails for any reason but a gone peer
  ;; throws a typed error carrying the kernel's errno, never answers :epipe. Writing to -1, no
  ;; descriptor at all (a closed one's number may already be another file's), is EBADF (9).
  (let [r (try (sock/send! -1 {:op :hello}) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :seon.sock/write-failed (:seon.error/type r)) (str "answered " r))
    (is (= 9 (:errno r)))))

(deftest a-write-to-a-gone-peer-is-epipe
  ;; task seon.sock/a-frame-is-written-whole: EPIPE (the peer closed) is :epipe, the one answer that
  ;; means the peer is gone.
  (let [[a b] (sock/socketpair)]
    (lib/c-close b)
    (is (= :epipe (sock/send! a {:op :hello})))
    (lib/c-close a)))

(deftest a-spawned-command-holds-no-socketpair-end
  ;; task seon.agent/a-test-child-that-dies-says-why: a test child's pipe was read as held (:seon.test/pipe-held)
  ;; because a process other than the child kept its write end; both ends are close-on-exec (FD_CLOEXEC,
  ;; fcntl F_GETFD bit 1), so a command the process spawns while a pair is open holds neither, and a
  ;; child's exit is its peer's end of file
  (let [[a b] (sock/socketpair)
        cloexec? (fn [fd] (= 1 (bit-and 1 (#'seon.sock/c-fcntl fd 1 0))))]
    (try
      (is (cloexec? a) "the first end closes at exec")
      (is (cloexec? b) "and the second")
      (finally (sock/close! a) (sock/close! b)))))
