(ns seon.sock
  "The single-threaded side of the transport (session F2, from session T1's t1.sock): the fork
  server's socket to root and each agent's, read and written by the one thread that owns
  them, with no pumps. Root's side of an agent's socket is a jolt.async.remote end (session
  RC1), which speaks the same frames:
  - Frames are [u32 len][body], the body jfasl (konserve.jolt.fasl); a length word of
    0xFFFFFFFF is the end frame (the peer closed its writer), read as EOF.
  - A conn is a buffered reader over one fd. It reads with recvmsg, so a descriptor sent
    with a frame (SCM_RIGHTS) is kept in the conn's queue for the handler of that frame.
  - Why no pumps here: these processes fork (the fork server its agents; an agent its test
    children and clones), and a process that forks must never have started Chez's
    parallel-collector sweeper threads, which start at the first collection that finds
    another thread (RC1: a child of such a process hangs in its first multi-threaded
    collection). docs/failure-points.md, \"Physics\".
  - The socket's buffer is the bounded channel: a full buffer makes the sender wait."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [jolt.ffi :as ffi] [seon.lib :as lib] [konserve.jolt.fasl :as codec] [seon.config :as config] [jolt.io-poller :as poller] [jolt.socket.native :as native] [clojure.string :as str]))

(ffi/load-library)

(lib/defc c-socketpair "socketpair" [:int :int :int :pointer] :int)
(lib/defc c-fcntl "fcntl" [:int :int :varargs :int] :int)
(lib/defc c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)
(lib/defc c-memmove "memmove" [:pointer :pointer :size_t] :pointer)
(lib/defc c-sendmsg "sendmsg" [:int :pointer :int] :ssize_t {:blocking true :capture-native-error true})
(lib/defc c-recvmsg "recvmsg" [:int :pointer :int] :ssize_t {:blocking true})
(lib/defc c-recvmsg-nb "recvmsg" [:int :pointer :int] :ssize_t)
(lib/defc c-recv-nb "recv" [:int :pointer :size_t :int] :ssize_t)

(defmacro ^:private with-mem
  "Allocate n bytes for body, freed after it (jolt.ffi's caller-owned form)."
  [[p n] & body] `(let [~p (ffi/alloc ~n)] (try ~@body (finally (ffi/free ~p)))))

(def ^:private AF-UNIX 1)
(def ^:private SOCK-STREAM 1)
(def ^:private SOL-SOCKET 0xffff)
(def ^:private SCM-RIGHTS 1)
(def ^:private SO-SNDBUF 0x1001)
(def ^:private SO-RCVBUF 0x1002)
(def ^:private SO-NOSIGPIPE 0x1022)
(def ^:private F-GETFD 1)
(def ^:private FD-CLOEXEC 1)
(def ^:private F-SETFD 2)
(def ^:private FD-CLOFORK 2)
(def ^:private MSG-DONTWAIT 0x80)
(def ^:private MSG-PEEK 0x2)
(def ^:private F-GETFL 3)
(def ^:private F-SETFL 4)
(def ^:private F-SETOWN 6)
(def ^:private O-ASYNC 0x40)


(def BUF
  "Each socket's send and receive buffer, bytes (:seon.config.agent/sock-buf-bytes)."
  (config/value :seon.config.agent/sock-buf-bytes))

(defn- set-int-opt! {:malli/schema [:=> [:cat :seon.lib/fd :int :int] :int]}
  [fd opt v]
  (with-mem [b 4] (ffi/write b :int v) (c-setsockopt fd SOL-SOCKET opt b 4)))

(defn socketpair
  "[a b]: a connected AF_UNIX stream pair with BUF buffers, SO_NOSIGPIPE (a write to a
  dead peer answers EPIPE, not a signal) and FD_CLOEXEC on both ends: a command this process
  spawns (an exec) never holds them, so a forked child's write end closing is its peer's end of
  file (a test child's pipe read as held while a command spawned beside it kept its write end:
  task seon.agent/a-test-child-that-dies-says-why). A fork without exec keeps them, as its own
  fds decide (clofork!, run-tests-forked's fds)."
  {:malli/schema [:=> [:cat] :seon.lib/pipe]}
  []
  (with-mem [sv 8]
    (when-not (zero? (c-socketpair AF-UNIX SOCK-STREAM 0 sv)) (throw (ex-info "socketpair failed" {:errno (ffi/errno)})))
    (let [a (ffi/read sv :int 0) b (ffi/read sv :int 4)]
      (doseq [fd [a b]] (set-int-opt! fd SO-NOSIGPIPE 1) (set-int-opt! fd SO-SNDBUF BUF) (set-int-opt! fd SO-RCVBUF BUF)
        (c-fcntl fd F-SETFD (bit-or FD-CLOEXEC (c-fcntl fd F-GETFD 0))))
      [a b])))

(defn clofork!
  "Close fd in every child this process forks from now on."
  {:malli/schema [:=> [:cat :seon.lib/fd] :int]}
  [fd] (c-fcntl fd F-SETFD (bit-or FD-CLOFORK (c-fcntl fd F-GETFD 0))))

(defn signal-at-input!
  "fd raises SIGIO in this process when input arrives on it or its peer closes it (O_ASYNC,
  the signal owned by this process). nil, or :seon.sock/unwatched when fcntl refuses (the
  failure point's edge: docs/failure-points.md)."
  {:malli/schema [:=> [:cat :seon.lib/fd] [:maybe :seon.error/base]]}
  [fd]
  (when-not (and (not (neg? (c-fcntl fd F-SETOWN (lib/c-getpid))))
                 (not (neg? (c-fcntl fd F-SETFL (bit-or O-ASYNC (c-fcntl fd F-GETFL 0))))))
    (contract/error :seon.sock/unwatched #'seon.sock/signal-at-input!
                    (str "fd " fd " cannot raise SIGIO (fcntl errno " (ffi/errno) "): this process ends only when it next reads its socket")
                    {:errno (ffi/errno)})))

(defn peer-gone?
  "Has fd's peer closed it, with nothing left to read before the EOF? A non-blocking peek."
  {:malli/schema [:=> [:cat :seon.lib/fd] :boolean]}
  [fd] (with-mem [b 1] (zero? (c-recv-nb fd b 1 (bit-or MSG-PEEK MSG-DONTWAIT)))))

(defn close! {:malli/schema [:=> [:cat :seon.lib/fd] :int]}
  [fd] (lib/c-close fd))


;; ---- a buffered conn ----------------------------------------------------------
;; st: long-array [buf cap lo hi]; fds: descriptors received with frames, in order

(def ^:private CTL
  "Control buffer bytes per recvmsg: room for many descriptors (several fork requests can
  arrive in one read; a short buffer would truncate them, and the kernel closes those)."
  1024)

(defn conn
  "A buffered reader over fd. Its buffer starts at 4 KB and grows to the largest frame
  (root holds one per agent)."
  {:malli/schema [:=> [:cat :seon.lib/fd] :seon.sock/conn]}
  [fd] {:fd fd :st (long-array [(ffi/address (ffi/alloc 4096)) 4096 0 0]) :fds (volatile! [])
        :msg (ffi/alloc 48) :iov (ffi/alloc 16) :ctl (ffi/alloc CTL)})

(defn- received-fds
  "Every descriptor in a recvmsg's control data (cmsghdr: len u32, level i32, type i32,
  then the fds; headers 4-byte aligned on darwin), in order."
  {:malli/schema [:=> [:cat :seon.ffi/address :int] [:vector :seon.lib/fd]]}
  [ctl n]
  (loop [off 0 acc []]
    (if (> (+ off 12) n)
      acc
      (let [len (ffi/read (+ ctl off) :uint32)]
        (if (< len 12)
          acc
          (recur (+ off (bit-and (+ len 3) -4))
                 (if (and (= SOL-SOCKET (ffi/read (+ ctl off) :int 4)) (= SCM-RIGHTS (ffi/read (+ ctl off) :int 8)))
                   (into acc (for [i (range (quot (- len 12) 4))] (ffi/read (+ ctl off 12 (* 4 i)) :int)))
                   acc)))))))


(defn- ensure-room!
  "Make room for `need` bytes from lo: compact, then grow the buffer if needed."
  {:malli/schema [:=> [:cat :seon.sock/conn :int] :any]}
  [{:keys [^longs st]} need]
  (let [buf (aget st 0) cap (aget st 1) lo (aget st 2) hi (aget st 3) live (- hi lo)]
    (when (pos? lo)
      (when (pos? live) (c-memmove buf (+ buf lo) live))
      (aset st 2 0) (aset st 3 live))
    (when (> need cap)
      (let [ncap (loop [c cap] (if (< c need) (recur (* 2 c)) c))
            nb (ffi/address (ffi/alloc ncap))]
        (when (pos? live) (c-memmove nb buf live))
        (ffi/free buf)
        (aset st 0 nb) (aset st 1 ncap)))))

(def ^:private END
  "The length word of an end frame (jolt.async.remote's: its writer closed)."
  0xFFFFFFFF)

(defn- take-frame
  "The next whole frame's bytes from the conn's buffer, :end (the peer's end frame), or nil."
  {:malli/schema [:=> [:cat :seon.sock/conn] [:maybe [:or :seon.host/bytes [:= :end]]]]}
  [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (when (>= avail 4)
      (let [buf (aget st 0) n (ffi/read (+ buf lo) :uint32)]
        (if (= n END)
          (do (aset st 2 (+ lo 4)) :end)
          (when (>= avail (+ 4 n))
          (let [b (ffi/read-array (+ buf lo 4) n)]
            (aset st 2 (+ lo 4 n))
            b)))))))

(defn- wanted {:malli/schema [:=> [:cat :seon.sock/conn] :int]}
  [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (if (< avail 4) 4 (let [n (ffi/read (+ (aget st 0) lo) :uint32)] (if (= n END) 4 (+ 4 n))))))

(defn- fill!
  "One recvmsg into the conn's buffer (a descriptor that came with it joins :fds).
  Answers bytes read, :eof, or :again (non-blocking with nothing there, or EINTR)."
  {:malli/schema [:function [:=> [:cat :seon.sock/conn :boolean] [:or :int [:enum :eof :again]]] [:=> [:cat :seon.sock/conn :boolean :int] [:or :int [:enum :eof :again]]]]}
  ([c blocking?] (fill! c blocking? (wanted c)))
  ([{:keys [fd ^longs st msg iov ctl fds] :as c} blocking? need]
    (when (> (+ (aget st 2) need) (aget st 1)) (ensure-room! c need))
    (when (= (aget st 3) (aget st 1)) (ensure-room! c (max need (inc (- (aget st 3) (aget st 2))))))
    (let [hi (aget st 3) room (- (aget st 1) hi)]
      (ffi/write iov :uint64 (+ (aget st 0) hi) 0) (ffi/write iov :uint64 room 8)
      (ffi/write msg :uint64 0 0) (ffi/write msg :uint32 0 8)
      (ffi/write msg :uint64 (ffi/address iov) 16) (ffi/write msg :int 1 24)
      (ffi/write msg :uint64 (ffi/address ctl) 32) (ffi/write msg :uint32 CTL 40) (ffi/write msg :int 0 44)
      (ffi/write ctl :uint32 0 0)
      (let [r (if blocking? (c-recvmsg fd msg 0) (c-recvmsg-nb fd msg MSG-DONTWAIT))]
        (when (pos? r)
          (let [n (ffi/read msg :uint32 40)]
            (when (>= n 12) (vswap! fds into (received-fds ctl n)))))
        (cond (pos? r) (do (aset st 3 (+ hi r)) r)
              (zero? r) :eof
              :else :again)))))


(defn recv!
  "The next frame's value, or :eof. EINTR (a signal) retries. On a main carrier
  (jolt.host/main-carrier-run!: the agent's fibers on its one thread), a wait for input
  parks this fiber on the socket's readiness, so the process's other fibers run meanwhile;
  elsewhere the recvmsg blocks."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [c]
  (let [park? (and (jolt.host/main-carrier?) (jolt.host/fiber?))]
    (loop []
      (if-let [b (take-frame c)]
        (if (= :end b) :eof (codec/decode b))
        (let [r (fill! c (not park?))]
          (cond (= :eof r) :eof
                (and park? (= :again r)) (do (poller/wait-ready (:fd c) :read) (recur))
                :else (recur)))))))

(defn close-pending-fds!
  "Close every descriptor received on c and not yet taken (a forked child does this: they
  belong to its parent's later frames)."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [{:keys [fds]}] (run! lib/c-close @fds) (vreset! fds []))

(defn take-fd!
  "The oldest descriptor received with a frame on c (the one its handler is owed)."
  {:malli/schema [:=> [:cat :seon.sock/conn] [:maybe :seon.lib/fd]]}
  [{:keys [fds]}] (let [fd (first @fds)] (vswap! fds #(vec (rest %))) fd))

(defn next-frame!
  "Non-blocking: the next whole frame's value, :none when no whole frame is there yet,
  or :eof when the peer is gone (after its last whole frame)."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [c]
  (loop []
    (if-let [b (take-frame c)]
      (if (= :end b) :eof (codec/decode b))
      (case (fill! c false)
        :eof :eof
        :again :none
        (recur)))))

;; ---- writing ----------------------------------------------------------------

(declare write-all!)

(defn- write-result
  "One write's or sendmsg's [r errno], off of n bytes of the frame already gone: [:sent r], :again
  (EINTR; EAGAIN and ENOBUFS once fd can take bytes again) or :epipe (EPIPE, ECONNRESET: the peer
  is gone); any other errno throws :seon.sock/write-failed. The wait is poll POLLOUT
  (jolt.socket.native/poll-one), a blocking call: no fiber writes into the middle of this one's
  frame, and nothing else in the process runs meanwhile (parking is task
  seon.sock/a-send-parks-like-a-receive); a kernel short of buffers with the socket already writable
  retries at once, for as long as it stays short. docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data."
  {:malli/schema [:=> [:cat :seon.lib/fd [:tuple :int :int] :int :int] [:or [:tuple [:= :sent] :int] [:enum :again :epipe]]]}
  [fd [r e] off n]
  (cond (>= r 0) [:sent r]
        (native/eintr? e) :again
        (or (native/eagain? e) (native/enobufs? e)) (do (native/poll-one fd native/pollout -1) :again)
        (or (= e native/epipe) (= e native/econnreset)) :epipe
        :else (let [x (contract/error :seon.sock/write-failed #'seon.sock/write-all!
                                      (str "the kernel refused a write to fd " fd " (errno " e ") after " off " of " n " bytes")
                                      {:errno e :fd fd :sent off :of n})]
                (throw (ex-info (:seon.error/message x) x)))))

(defn- write-all!
  "Write the n bytes at p to fd whole, from off (a partial sendmsg's rest), going on from where a
  short write stopped: true, or :epipe (the peer is gone); `write-result` classifies a failed write.
  docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd :seon.ffi/address :int] :seon.sock/sent]
                  [:=> [:cat :seon.lib/fd :seon.ffi/address :int :int] :seon.sock/sent]]}
  ([fd p n] (write-all! fd p n 0))
  ([fd p n off]
   (if (= off n)
     true
     (let [w (write-result fd (lib/c-write fd (+ p off) (- n off)) off n)]
       (case w
         :again (recur fd p n off)
         :epipe :epipe
         (recur fd p n (+ off (second w))))))))

(defn frame
  "v encoded once, as the bytes of one frame (to send to several peers)."
  {:malli/schema [:=> [:cat :any] :seon.host/bytes]}
  ^bytes [v]
  (let [^bytes b (codec/encode v) n (alength b) f (byte-array (+ 4 n))]
    (aset f 0 (unchecked-byte n)) (aset f 1 (unchecked-byte (bit-shift-right n 8)))
    (aset f 2 (unchecked-byte (bit-shift-right n 16))) (aset f 3 (unchecked-byte (bit-shift-right n 24)))
    (System/arraycopy b 0 f 4 n)
    f))

(defn- read-fully!
  "Fill the first n bytes of buf from the stream in: true, or false when it ended first."
  {:malli/schema [:=> [:cat :any :seon.host/bytes :int] :boolean]}
  [^java.io.InputStream in ^bytes buf n]
  (loop [off 0]
    (if (= off n)
      true
      (let [r (.read in buf off (- n off))]
        (if (neg? r) false (recur (+ off r)))))))

(defn recv-stream!
  "The next frame's value from the java.io.InputStream in (the pipe of an exec'd child: its
  stdout here, our own stdin there; no descriptor of ours, so `conn` does not read it), or :eof at
  the stream's end, in the middle of a frame or at an end frame. Blocks: the caller's one read.
  The embedding helper's transport: docs/vector.md#the-model."
  {:malli/schema [:=> [:cat :any] :any]}
  [in]
  (let [h (byte-array 4)]
    (if-not (read-fully! in h 4)
      :eof
      (let [n (bit-or (bit-and 255 (aget h 0)) (bit-shift-left (bit-and 255 (aget h 1)) 8)
                      (bit-shift-left (bit-and 255 (aget h 2)) 16) (bit-shift-left (bit-and 255 (aget h 3)) 24))]
        (if (= (bit-and n 0xFFFFFFFF) END)
          :eof
          (let [b (byte-array n)]
            (if (read-fully! in b n) (codec/decode b) :eof)))))))

(defn write-line!
  "Write s and a newline to fd whole (a pipe or stderr; EINTR retried). Answers true, or
  :epipe (the reader is gone)."
  {:malli/schema [:=> [:cat :seon.lib/fd :any] :seon.sock/sent]}
  [fd s]
  (let [^bytes b (.getBytes (str s "\n") "UTF-8") n (alength b)]
    (with-mem [p n] (ffi/write-array p b 0 n) (write-all! fd p n))))

(defn line-reader
  "A fn of no arguments that answers the complete lines that arrived on fd (a vector, maybe
  empty) or :eof. It reads what is there (MSG_DONTWAIT) and, when nothing is, parks until fd
  is readable (poller/wait-ready: a fiber's park, so on a main carrier the process's other
  fibers run while it waits; a thread's wait elsewhere), then answers [] and the caller asks
  again: an agent waits on its test child for the whole test run (seon.agent/run-tests-forked).
  With wait!, that is what it calls when nothing is there (seon.agent/read-to-exit: fd's input or
  its writer's exit, whichever comes first)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd] :seon.host/fn] [:=> [:cat :seon.lib/fd :seon.host/fn] :seon.host/fn]]}
  ([fd] (line-reader fd #(poller/wait-ready fd :read)))
  ([fd wait!]
  (let [cap 1048576 buf (ffi/alloc cap) pending (volatile! (byte-array 0))]
    (fn []
      (let [r (c-recv-nb fd buf cap MSG-DONTWAIT)]
        (cond
          (neg? r) (do (wait!) [])
          (zero? r) :eof
          :else
          (let [more (ffi/read-array buf r) ^bytes p @pending q (byte-array (+ (alength p) r))]
            (System/arraycopy p 0 q 0 (alength p))
            (System/arraycopy more 0 q (alength p) r)
            (let [nl (loop [k (dec (alength q))] (cond (< k 0) -1 (= 10 (aget q k)) k :else (recur (dec k))))]
              (if (neg? nl)
                (do (vreset! pending q) [])
                (do (vreset! pending (java.util.Arrays/copyOfRange q (inc nl) (alength q)))
                    (vec (remove str/blank? (str/split-lines (String. (java.util.Arrays/copyOfRange q 0 nl) "UTF-8"))))))))))))))

(defn send-frame!
  "Write frame bytes f to fd whole; with pass-fd, the descriptor travels with them
  (SCM_RIGHTS). Answers true or :epipe (the peer is gone); a refused write throws (`write-all!`)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd :seon.host/bytes] :seon.sock/sent] [:=> [:cat :seon.lib/fd :seon.host/bytes [:maybe :seon.lib/fd]] :seon.sock/sent]]}
  ([fd ^bytes f] (send-frame! fd f nil))
  ([fd ^bytes f pass-fd]
   (let [n (alength f)]
    (with-mem [p (max 1 n)]
     (ffi/write-array p f 0 n)
     (if-not pass-fd
       (write-all! fd p n)
       (with-mem [m 80]
        (let [iov (+ m 48) ctl (+ m 64)]
         (ffi/write iov :uint64 (ffi/address p) 0) (ffi/write iov :uint64 n 8)
         (ffi/write ctl :uint32 16 0) (ffi/write ctl :int SOL-SOCKET 4) (ffi/write ctl :int SCM-RIGHTS 8) (ffi/write ctl :int pass-fd 12)
         (ffi/write m :uint64 0 0) (ffi/write m :uint32 0 8)
         (ffi/write m :uint64 (ffi/address iov) 16) (ffi/write m :int 1 24)
         (ffi/write m :uint64 (ffi/address ctl) 32) (ffi/write m :uint32 16 40) (ffi/write m :int 0 44)
         ;; the descriptor goes with the first bytes the kernel takes; the rest follows as plain writes
         (let [w (loop [] (let [w (write-result fd (c-sendmsg fd m 0) 0 n)] (if (= :again w) (recur) w)))]
           (if (= :epipe w) w (write-all! fd p n (second w)))))))))))

(defn send!
  "Encode v and write it as one frame to fd (with pass-fd, a descriptor goes with it)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd :any] :seon.sock/sent] [:=> [:cat :seon.lib/fd :any [:maybe :seon.lib/fd]] :seon.sock/sent]]}
  ([fd v] (send-frame! fd (frame v) nil))
  ([fd v pass-fd] (send-frame! fd (frame v) pass-fd)))








