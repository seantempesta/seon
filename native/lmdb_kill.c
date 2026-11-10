/* lmdb-kill DIR: the proof that a dead process never leaves an LMDB env locked or its readers held
 * (docs/failure-points.md#lmdb-locks). Each case works on a store of its own under DIR.
 * 1. this process holds a store open; a child SIGKILLs itself inside a write txn; then a new process
 *    and this one each write: both commit and the killed write is gone (with LMDB's POSIX-semaphore
 *    locks, macOS's default, both block forever on the dead child's writer lock);
 * 2. a process died holding the reader table lock (its lock word left set, its byte lock given back
 *    by the kernel, as a SIGKILL leaves them): the next reader repairs the table and reads;
 * 3. fork while the writer lock is held (root forks agents with its store open): the child holds
 *    nothing, opens the store itself and commits after the parent; then the parent is SIGKILLed
 *    inside a write txn, and the child and a third process both commit;
 * 4. readers of killed processes, more of them than the reader table has slots, while this process
 *    holds the store: every open and every read txn succeeds (never MDB_READERS_FULL);
 * 5. a killed process's read txn does not keep freed pages from reuse: 40 commits rewriting one
 *    48 KB value grow the file by at most a few pages, not by the 3 pages a commit it pins;
 * 6. a process of a store kept with MDB_SAFENOSYNC SIGKILLed with 3 commits made since its last
 *    sync: the next opener (exclusive, the same OS boot) reads all of them: a kill loses nothing;
 * 7. a writer that forked a child that forked a grandchild (root, fork server, agent), killed inside
 *    its write txn while both live and open nothing: the writer lock is free at once and the next
 *    write commits while they still live (a child closes the lock descriptions it inherited);
 * 8. the same with a descendant made by posix_spawn (/bin/sleep): no fork handler runs; the exec
 *    closes the descriptions (opened close-on-exec);
 * 9. a thread holds the writer lock while another forks: the child, where that thread does not exist,
 *    opens the store and writes after the parent's commit, and the parent writes on;
 * a. (10) a child of fork() begins a read txn and a write txn on the env it inherited: each fails with
 *    MDB_ENV_FORKED, and the reader table holds no slot of the parent's pid the child made (a reader
 *    the child registered under the parent's pid is cleared when the parent dies: wrong reads); the
 *    child closes the inherited env, opens its own, reads and writes; the parent reads and writes on.
 * CASES, default 123456789a, names the cases to run. Prints LMDB-KILL OK and exits 0 only when every case
 * run holds. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <pthread.h>
#include <spawn.h>

#ifndef MDB_SAFENOSYNC
#define MDB_SAFENOSYNC 0	/* a LMDB without it */
#endif
#ifndef MDB_ENV_FORKED
#define MDB_ENV_FORKED (-30767)	/* a LMDB without it: the case shows what it does instead */
#endif

static MDB_env *open_env(const char *p, unsigned maxreaders) {
  MDB_env *e; int r = mdb_env_create(&e);
  if (!r) r = mdb_env_set_mapsize(e, 1 << 26);
  if (!r && maxreaders) r = mdb_env_set_maxreaders(e, maxreaders);
  if (!r) r = mdb_env_open(e, p, MDB_NOSUBDIR | MDB_NOTLS | MDB_NOSYNC, 0664);
  if (r) { printf("env_open %s: %s\nLMDB-KILL FAILED\n", p, mdb_strerror(r)); exit(1); }
  return e;
}

/* put key k = v (n bytes); die: SIGKILL this process inside the txn instead of committing */
static int putv(MDB_env *e, const char *k, const void *v, size_t n, int die) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V = {n, (void *)v};
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  if (!r) r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (r) { mdb_txn_abort(t); return r; }
  if (die) { printf("  %d: inside a write txn, SIGKILL\n", getpid()); kill(getpid(), SIGKILL); }
  return mdb_txn_commit(t);
}
static int put(MDB_env *e, const char *k, int die) { return putv(e, k, "v", 1, die); }

static int has(MDB_env *e, const char *k) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V;
  if (mdb_txn_begin(e, NULL, MDB_RDONLY, &t)) return 0;
  int r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_get(t, d, &K, &V);
  mdb_txn_abort(t); return r == 0;
}

/* fork: open p, put k, exit 0 when it committed */
static int child_put(const char *p, const char *k, const char *who) {
  pid_t c = fork();
  if (!c) { int r = put(open_env(p, 0), k, 0); printf("  %s: %s\n", who, r ? mdb_strerror(r) : "committed"); _exit(r ? 1 : 0); }
  int st; waitpid(c, &st, 0);
  return WIFEXITED(st) && !WEXITSTATUS(st);
}

static void path(char *out, size_t n, const char *dir, const char *name) { snprintf(out, n, "%s/%s", dir, name); }

static int killed_writer(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "writer");
  printf("1. a writer SIGKILLed inside a txn while this process holds the store\n");
  MDB_env *e = open_env(p, 0);
  int r0 = put(e, "before", 0), st;
  pid_t k = fork();
  if (!k) { put(open_env(p, 0), "killed", 1); _exit(0); }
  waitpid(k, &st, 0);
  printf("  child: signaled %d, signal %d\n", WIFSIGNALED(st), WTERMSIG(st));
  int w = child_put(p, "after", "a new process writes");
  int r1 = put(e, "holder", 0);
  printf("  the holder writes: %s\n", r1 ? mdb_strerror(r1) : "committed");
  int ok = !r0 && WIFSIGNALED(st) && w && !r1 && has(e, "after") && has(e, "holder") && !has(e, "killed");
  mdb_env_close(e);
  return ok;
}

/* The reader table lock's "held" word (mtb_rlocked), at byte 20 of the lock file: after the magic,
 * the format, the last txnid and the reader count (mdb.c's MDB_txbody, MDB_USE_FCNTL). */
enum { RLOCKED_AT = 20 };

static int dead_reader_lock(const char *dir) {
  char p[512], l[600]; path(p, sizeof p, dir, "rlock"); snprintf(l, sizeof l, "%s-lock", p);
  printf("2. a process died holding the reader table lock\n");
  MDB_env *e = open_env(p, 0);
  int ok = !put(e, "k", 0);
  int fd = open(l, O_RDWR);
  unsigned char *m = fd < 0 ? MAP_FAILED : mmap(NULL, 64, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (m == MAP_FAILED) { printf("  map %s failed\n", l); return 0; }
  int was = *(int *)(m + RLOCKED_AT);
  *(int *)(m + RLOCKED_AT) = 1;	/* what a holder SIGKILLed between the lock and the unlock leaves */
  pid_t c = fork();
  if (!c) { int h = has(open_env(p, 0), "k"); printf("  the next reader: %s\n", h ? "read the store" : "failed"); _exit(h ? 0 : 1); }
  int st; waitpid(c, &st, 0);
  int now = *(int *)(m + RLOCKED_AT);
  printf("  the word before %d, after the repair %d\n", was, now);
  munmap(m, 64); close(fd);
  ok = ok && was == 0 && now == 0 && WIFEXITED(st) && !WEXITSTATUS(st) && has(e, "k");
  mdb_env_close(e);
  return ok;
}

static int fork_held(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "fork");
  printf("3. fork while the writer lock is held\n");
  int cp[2], ct[2], tc[2]; char b;
  if (pipe(cp) || pipe(ct) || pipe(tc)) return 0;
  pid_t parent = fork();
  if (!parent) {	/* holds the writer lock across a fork */
    MDB_env *e = open_env(p, 0); MDB_txn *t; MDB_dbi d; MDB_val K = {6, "parent"}, V = {1, "v"};
    if (mdb_txn_begin(e, NULL, 0, &t) || mdb_dbi_open(t, NULL, 0, &d) || mdb_put(t, d, &K, &V, 0)) _exit(1);
    pid_t c = fork();
    if (!c) {	/* the parent's env is not the child's: it opens the store itself */
      int r = put(open_env(p, 0), "child", 0);	/* waits for the parent's commit */
      printf("  the child commits after the parent: %s\n", r ? mdb_strerror(r) : "committed");
      if (write(cp[1], "c", 1) != 1 || read(tc[0], &b, 1) != 1) _exit(1);
      r = r ? r : put(open_env(p, 0), "child2", 0);	/* after the parent died inside a write txn */
      printf("  the child writes after the parent's death: %s\n", r ? mdb_strerror(r) : "committed");
      b = r ? 'n' : 'y';
      _exit(write(ct[1], &b, 1) == 1 && !r ? 0 : 1);
    }
    close(cp[1]);
    usleep(100000);	/* the child waits on the writer lock now */
    int r = mdb_txn_commit(t);
    printf("  the parent commits: %s\n", r ? mdb_strerror(r) : "committed");
    if (r || read(cp[0], &b, 1) != 1) _exit(1);
    put(e, "dying", 1);
    _exit(1);
  }
  close(cp[0]); close(cp[1]); close(ct[1]); close(tc[0]);	/* a child that dies early ends this process's read */
  int st; waitpid(parent, &st, 0);
  printf("  the parent: signaled %d\n", WIFSIGNALED(st));
  int childok = write(tc[1], "g", 1) == 1 && read(ct[0], &b, 1) == 1 && b == 'y';
  int third = child_put(p, "third", "a third process writes");
  close(ct[0]); close(tc[1]);
  MDB_env *e = open_env(p, 0);
  int ok = childok && WIFSIGNALED(st) && third && has(e, "parent") && has(e, "child") && has(e, "child2") &&
           has(e, "third") && !has(e, "dying");
  mdb_env_close(e);
  return ok;
}

static int readers_full(const char *dir) {
  enum { SLOTS = 8, KILLED = 20 };
  char p[512]; path(p, sizeof p, dir, "readers");
  printf("4. %d readers of killed processes in turn, the reader table %d slots, this process holding the store\n", KILLED, SLOTS);
  MDB_env *e = open_env(p, SLOTS);
  int ok = !put(e, "k", 0), i, bad = 0;
  for (i = 0; i < KILLED; i++) {
    pid_t c = fork();
    if (!c) {
      MDB_env *ce = open_env(p, SLOTS); MDB_txn *t;
      int r = mdb_txn_begin(ce, NULL, MDB_RDONLY, &t);
      if (r) { printf("  reader %d: %s\n", i, mdb_strerror(r)); _exit(1); }
      kill(getpid(), SIGKILL);
    }
    int st; waitpid(c, &st, 0);
    if (!WIFSIGNALED(st)) bad++;
  }
  int h = has(e, "k");
  printf("  readers that failed to begin: %d; this process reads after: %s\n", bad, h ? "yes" : "no");
  mdb_env_close(e);
  return ok && !bad && h;
}

static size_t last_pgno(MDB_env *e) { MDB_envinfo i; mdb_env_info(e, &i); return i.me_last_pgno; }

static int dead_reader_pins(const char *dir) {
  enum { COMMITS = 40, VAL = 48 * 1024 };
  char p[512]; path(p, sizeof p, dir, "pins");
  static char v[VAL];
  printf("5. a killed process's read txn, then %d commits each rewriting one %d KB value\n", COMMITS, VAL / 1024);
  MDB_env *e = open_env(p, 0);
  int ok = !putv(e, "big", v, VAL, 0), i;
  for (i = 0; i < 4; i++) { v[0] = (char)i; ok = ok && !putv(e, "big", v, VAL, 0); }	/* settle the freelist */
  pid_t c = fork();
  if (!c) { MDB_env *ce = open_env(p, 0); MDB_txn *t; if (mdb_txn_begin(ce, NULL, MDB_RDONLY, &t)) _exit(1); kill(getpid(), SIGKILL); }
  int st; waitpid(c, &st, 0);
  size_t before = last_pgno(e);
  for (i = 0; i < COMMITS; i++) { v[0] = (char)(i + 9); ok = ok && !putv(e, "big", v, VAL, 0); }
  size_t grew = last_pgno(e) - before;
  printf("  the file grew %zu pages over %d commits\n", grew, COMMITS);
  mdb_env_close(e);
  return ok && WIFSIGNALED(st) && grew <= 12;
}

static int killed_after_sync(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "steady");
  printf("6. a process of a store kept with MDB_SAFENOSYNC commits 3 times after its last sync and is SIGKILLed\n");
  pid_t c = fork();
  if (!c) {
    MDB_env *e; int r = mdb_env_create(&e);
    if (!r) r = mdb_env_set_mapsize(e, 1 << 26);
    if (!r) r = mdb_env_open(e, p, MDB_NOSUBDIR | MDB_NOTLS | MDB_NOSYNC | MDB_SAFENOSYNC, 0664);
    if (r || put(e, "synced", 0) || mdb_env_sync(e, 1) || put(e, "a", 0) || put(e, "b", 0)) _exit(1);
    put(e, "c", 0); kill(getpid(), SIGKILL);
  }
  int st; waitpid(c, &st, 0);
  MDB_env *e = open_env(p, 0);	/* the first opener after it, in the same boot */
  int ok = WIFSIGNALED(st) && has(e, "synced") && has(e, "a") && has(e, "b") && has(e, "c");
  printf("  the next opener reads every commit: %s\n", ok ? "yes" : "NO");
  mdb_env_close(e);
  return ok;
}

/* Is the writer lock's byte of lock file l held by anyone (F_GETLK sees description locks too)? */
static int writer_held(const char *l) {
  int fd = open(l, O_RDWR); struct flock f;
  if (fd < 0) return -1;
  memset(&f, 0, sizeof f); f.l_type = F_WRLCK; f.l_whence = SEEK_SET; f.l_start = 0x40000000 + 1; f.l_len = 1;
  int r = fcntl(fd, F_GETLK, &f);
  close(fd);	/* this process holds no lock on l */
  return r ? -1 : f.l_type != F_UNLCK;
}

/* The writer, killed inside its write txn after it made descendants that outlive it: by fork (a child
 * that forks a grandchild, as root -> fork server -> agent) or by posix_spawn of /bin/sleep. Neither
 * descendant opens anything. Right after the kill, while both still live, the writer lock is free and
 * this process begins a write txn and commits; the killed write is gone. */
static int killed_with_descendants(const char *dir, int spawned) {
  char p[512], l[600]; int ready[2], hold[2]; pid_t ds[2] = {0, 0};
  path(p, sizeof p, dir, spawned ? "spawned" : "descendants"); snprintf(l, sizeof l, "%s-lock", p);
  printf("%d. a writer that %s, SIGKILLed inside its write txn\n", spawned ? 8 : 7,
         spawned ? "spawned /bin/sleep (posix_spawn: no fork handler, the exec closes)" : "forked a child that forked a grandchild");
  if (pipe(ready) || pipe(hold)) return 0;
  pid_t w = fork();
  if (!w) {
    MDB_env *e = open_env(p, 0); MDB_txn *t; MDB_dbi d; MDB_val K = {6, "killed"}, V = {1, "v"};
    if (mdb_txn_begin(e, NULL, 0, &t) || mdb_dbi_open(t, NULL, 0, &d) || mdb_put(t, d, &K, &V, 0)) _exit(1);
    pid_t got[2] = {0, 0};
    if (spawned) {
      char *argv[] = {"/bin/sleep", "100000", NULL}; extern char **environ;
      if (posix_spawn(&got[0], "/bin/sleep", NULL, NULL, argv, environ)) _exit(1);
    } else {
      int cp[2]; char b; if (pipe(cp)) _exit(1);
      got[0] = fork();
      if (!got[0]) {	/* the child: makes the grandchild, then waits to be told */
        pid_t g = fork();
        if (!g) { close(hold[1]); if (read(hold[0], &b, 1) < 0) {} _exit(0); }
        if (write(cp[1], &g, sizeof g) != sizeof g) _exit(1);
        close(hold[1]); if (read(hold[0], &b, 1) < 0) {} _exit(0);
      }
      if (read(cp[0], &got[1], sizeof got[1]) != sizeof got[1]) _exit(1);
    }
    if (write(ready[1], got, sizeof got) != sizeof got) _exit(1);
    for (;;) pause();	/* holding the writer lock until killed */
  }
  int ok = read(ready[0], ds, sizeof ds) == sizeof ds;
  kill(w, SIGKILL);
  int st; waitpid(w, &st, 0);
  int alive = kill(ds[0], 0) == 0 && (spawned || kill(ds[1], 0) == 0);
  int held = writer_held(l);
  int put_ok = !held && !put(open_env(p, 0), "after", 0);
  int still = kill(ds[0], 0) == 0 && (spawned || kill(ds[1], 0) == 0);	/* the bound: they lived through it */
  printf("  descendants alive %s; the writer lock %s; a write now %s, the descendants still alive %s\n",
         alive ? "yes" : "no", held == 0 ? "free" : held == 1 ? "HELD" : "?", put_ok ? "commits" : "FAILED", still ? "yes" : "no");
  MDB_env *e = open_env(p, 0);
  ok = ok && WIFSIGNALED(st) && alive && held == 0 && put_ok && still && has(e, "after") && !has(e, "killed");
  mdb_env_close(e);
  close(hold[1]); close(hold[0]);	/* the forked descendants end */
  if (spawned) kill(ds[0], SIGKILL);
  if (spawned) waitpid(ds[0], NULL, 0);
  close(ready[0]); close(ready[1]);
  return ok;
}
static int killed_with_children(const char *dir) { return killed_with_descendants(dir, 0); }
static int killed_with_spawned(const char *dir) { return killed_with_descendants(dir, 1); }

/* A thread of this process holds the writer lock (inside a write txn) while another thread forks: in
 * the child only the forking thread exists, and the writer mutex it inherited is locked by a thread it
 * does not have. The child opens the env and writes (after the parent's commit: the writer lock is the
 * parent's until then), with its own mutexes and descriptions; the parent commits as before. */
static MDB_env *fenv; static int fgo[2], fdone[2];
static void *holder(void *a) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {6, "parent"}, V = {1, "v"}; char b = 0;
  int r = mdb_txn_begin(fenv, NULL, 0, &t);
  if (!r) r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  b = r ? 'n' : 'y';
  if (write(fdone[1], &b, 1) != 1 || read(fgo[0], &b, 1) != 1) return NULL;
  r = r ? r : mdb_txn_commit(t);
  b = r ? 'n' : 'y';
  if (write(fdone[1], &b, 1) != 1) return NULL;
  return NULL;
}
static int fork_beside_holder(const char *dir) {
  char p[512]; pthread_t th; char b; int cr[2];
  path(p, sizeof p, dir, "threadfork");
  printf("9. a thread holds the writer lock while another thread forks\n");
  if (pipe(fgo) || pipe(fdone) || pipe(cr)) return 0;
  fenv = open_env(p, 0);
  pthread_create(&th, NULL, holder, NULL);
  int held = read(fdone[0], &b, 1) == 1 && b == 'y';
  pid_t c = fork();
  if (!c) {
    MDB_env *ce = open_env(p, 0);
    int r = put(ce, "child", 0);	/* waits for the parent's commit, then commits */
    b = r ? 'n' : 'y';
    if (write(cr[1], &b, 1) != 1) _exit(1);
    _exit(r ? 1 : 0);
  }
  close(cr[1]);
  if (write(fgo[1], "g", 1) != 1) return 0;	/* the parent commits after the fork */
  int pc = read(fdone[0], &b, 1) == 1 && b == 'y';
  pthread_join(th, NULL);
  int cc = read(cr[0], &b, 1) == 1 && b == 'y';
  int st; waitpid(c, &st, 0);
  int again = !put(fenv, "again", 0);
  printf("  the parent's write %s; the child's %s; the parent writes again: %s\n",
         pc ? "commits" : "FAILED", cc ? "commits" : "FAILED", again ? "yes" : "NO");
  int ok = held && pc && cc && again && has(fenv, "parent") && has(fenv, "child");
  mdb_env_close(fenv);
  close(cr[0]); close(fgo[0]); close(fgo[1]); close(fdone[0]); close(fdone[1]);
  return ok;
}

/* mdb_reader_list's lines are "pid thread txnid"; count those of pid ctx[0] into ctx[1] */
static int count_pid(const char *line, void *ctx) {
  int pid, *c = ctx;
  if (sscanf(line, "%d", &pid) == 1 && pid == c[0]) c[1]++;
  return 0;
}
static int slots_of(MDB_env *e, int pid) { int c[2] = {pid, 0}; mdb_reader_list(e, count_pid, c); return c[1]; }

static int inherited_env(const char *dir) {
  char p[512]; int up[2], go[2], rs[3] = {-1, -1, -1}; char b;
  path(p, sizeof p, dir, "inherited");
  printf("10. a child of fork() begins a read txn and a write txn on the env it inherited\n");
  if (pipe(up) || pipe(go)) return 0;
  MDB_env *e = open_env(p, 0);
  int ok = !put(e, "k", 0);
  int before = slots_of(e, getpid());
  pid_t c = fork();
  if (!c) {
    MDB_txn *rt = NULL, *wt = NULL;
    rs[0] = mdb_txn_begin(e, NULL, MDB_RDONLY, &rt);	/* kept open while the parent counts */
    rs[1] = mdb_txn_begin(e, NULL, 0, &wt);
    if (!rs[1]) mdb_txn_abort(wt);
    if (write(up[1], rs, 2 * sizeof *rs) != 2 * sizeof *rs || read(go[0], &b, 1) != 1) _exit(1);
    if (!rs[0]) mdb_txn_abort(rt);
    mdb_env_close(e);	/* the inherited env: no lock of the parent's is touched */
    MDB_env *own = open_env(p, 0);
    rs[2] = has(own, "k") ? put(own, "child", 0) : MDB_NOTFOUND;
    mdb_env_close(own);
    _exit(write(up[1], &rs[2], sizeof rs[2]) == sizeof rs[2] && !rs[2] ? 0 : 1);
  }
  int got = read(up[0], rs, 2 * sizeof *rs) == 2 * sizeof *rs;
  int during = slots_of(e, getpid());	/* the child's read txn is open now */
  if (write(go[1], "g", 1) != 1) got = 0;
  got = got && read(up[0], &rs[2], sizeof rs[2]) == sizeof rs[2];
  int st; waitpid(c, &st, 0);
  int pr = put(e, "parent", 0), after = slots_of(e, getpid());
  printf("  the child's read txn: %s; its write txn: %s\n", rs[0] ? mdb_strerror(rs[0]) : "BEGUN",
         rs[1] ? mdb_strerror(rs[1]) : "BEGUN");
  printf("  slots of this process's pid: %d before, %d while the child's read was open, %d after\n", before, during, after);
  printf("  the child through its own env: %s; this process writes on: %s\n",
         rs[2] ? mdb_strerror(rs[2]) : "read and committed", pr ? mdb_strerror(pr) : "committed");
  ok = ok && got && rs[0] == MDB_ENV_FORKED && rs[1] == MDB_ENV_FORKED && before == 0 && during == 0 &&
       after == 0 && !rs[2] && WIFEXITED(st) && !WEXITSTATUS(st) && !pr && has(e, "child") && has(e, "parent");
  mdb_env_close(e);
  close(up[0]); close(up[1]); close(go[0]); close(go[1]);
  return ok;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  signal(SIGPIPE, SIG_IGN);	/* a write to a dead child is an error, not this process's death */
  if (argc < 2 || argc > 3) { printf("usage: lmdb-kill DIR [CASES]\n"); return 2; }
  const char *only = argc == 3 ? argv[2] : "123456789a";	/* the cases to run, by number */
  mkdir(argv[1], 0755);
#define RUN(n, f) (strchr(only, n) ? f(argv[1]) : 1)
  int c1 = RUN('1', killed_writer), c2 = RUN('2', dead_reader_lock), c3 = RUN('3', fork_held),
      c4 = RUN('4', readers_full), c5 = RUN('5', dead_reader_pins), c6 = RUN('6', killed_after_sync),
      c7 = RUN('7', killed_with_children), c8 = RUN('8', killed_with_spawned), c9 = RUN('9', fork_beside_holder),
      c10 = RUN('a', inherited_env);
  printf("cases: %d %d %d %d %d %d %d %d %d %d\n", c1, c2, c3, c4, c5, c6, c7, c8, c9, c10);
  int ok = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9 && c10;
  printf(ok ? "LMDB-KILL OK\n" : "LMDB-KILL FAILED\n");
  return ok ? 0 : 1;
}
