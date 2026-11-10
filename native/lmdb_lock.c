/* lmdb-lock DIR: the proof that LMDB's locks are the kernel's record locks on the lock file, which use
 * no system-wide table and end with their process and their file (docs/failure-points.md#lmdb-locks).
 * Each case works on stores of its own under DIR.
 * 1. the library makes no semaphore: it imports none of semget, semop, semctl, sem_open (nm -u of the
 *    loaded liblmdb), so no store, open, death or deletion can leave one;
 * 2. 16 processes open one store at once 200 times each, adding one to a count under the writer lock:
 *    no open fails and no addition is lost;
 * 3. 8 threads of one process, over 2 envs of one store, add 200 times each under the writer lock: no
 *    addition is lost (the envs share the process's lock file and its mutexes);
 * 4. one process opens and writes 50 stores at once: none fails;
 * 5. a process killed inside a write txn: the next process opens the store, writes and closes it, and
 *    then no byte of the lock file is locked (byte 0, the reader and writer lock bytes);
 * 6. one process has env A open and opens and closes env B of the same store: another process still
 *    sees its locks (byte 0, its pid's byte, the writer lock while A writes), and A's read txn keeps
 *    its reader slot;
 * 7. a store whose last user was SIGKILLed, then deleted: nothing of it is left (its directory is
 *    gone; case 1 says nothing outside it existed);
 * 8. a lock file of another lock format (a build of another layout): an open is refused with
 *    MDB_VERSION_MISMATCH while a process holds the store, and succeeds once the format is its own;
 * 9. two processes, each committing on one thread and beginning read txns on another, a long read
 *    txn open in each: no begin or commit fails (no EDEADLK), and each long reader keeps its slot.
 * CASES, default 123456789, names the cases to run. Prints LMDB-LOCK OK and exits 0 only when every
 * case run holds. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>

/* mdb.c's MDB_FCNTL_LOCKS: the reader table lock's byte, the writer lock's the next */
enum { LOCKS = 0x40000000, FORMAT_AT = 4 };

static int open_env(const char *dir, MDB_env **e) {
  int r = mdb_env_create(e);
  if (!r) r = mdb_env_set_mapsize(*e, 1 << 26);
  if (!r) r = mdb_env_open(*e, dir, MDB_NOTLS | MDB_NOSYNC, 0664);
  return r;
}

static void sub(char *out, size_t n, const char *base, const char *name) { snprintf(out, n, "%s/%s", base, name); mkdir(out, 0755); }

static int put(MDB_env *e, const char *k) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V = {1, "v"};
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (!r) r = mdb_txn_commit(t); else mdb_txn_abort(t);
  return r;
}

/* add one to the count under key "n", in a write txn (under the writer lock) */
static int add(MDB_env *e) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {1, "n"}, V; unsigned n = 0;
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r && !mdb_get(t, d, &K, &V)) memcpy(&n, V.mv_data, sizeof n);
  n++; V.mv_size = sizeof n; V.mv_data = &n;
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (!r) r = mdb_txn_commit(t); else mdb_txn_abort(t);
  return r;
}

static unsigned count(MDB_env *e) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {1, "n"}, V; unsigned n = 0;
  if (mdb_txn_begin(e, NULL, MDB_RDONLY, &t)) return 0;
  if (!mdb_dbi_open(t, NULL, 0, &d) && !mdb_get(t, d, &K, &V)) memcpy(&n, V.mv_data, sizeof n);
  mdb_txn_abort(t);
  return n;
}

/* fork: open DIR, write KEY and close it; the child exits 0 when it committed */
static int child_open_put(const char *dir, const char *key, const char *who) {
  pid_t p = fork();
  if (!p) {
    MDB_env *e; int r = open_env(dir, &e);
    if (!r) r = put(e, key);
    printf("  %s: %s\n", who, r ? mdb_strerror(r) : "opened and committed");
    if (!r) mdb_env_close(e);
    _exit(r ? 1 : 0);
  }
  int st; waitpid(p, &st, 0);
  return WIFEXITED(st) && !WEXITSTATUS(st);
}

/* Is byte AT of file L locked by another process (F_GETLK, as a writer would ask)? Who holds it in *pid */
static int locked(const char *l, off_t at, pid_t *pid) {
  int fd = open(l, O_RDWR); struct flock f;
  if (fd < 0) return -1;
  memset(&f, 0, sizeof f); f.l_type = F_WRLCK; f.l_whence = SEEK_SET; f.l_start = at; f.l_len = 1;
  int r = fcntl(fd, F_GETLK, &f);
  close(fd);	/* this process holds no lock on L: closing drops nothing */
  if (pid) *pid = f.l_pid;
  return r ? -1 : f.l_type != F_UNLCK;
}

static int no_semaphores(void) {
  Dl_info i; char cmd[1200], line[256]; int n = 0, found = 0;
  printf("1. the library makes no semaphore\n");
  if (!dladdr((void *)mdb_env_open, &i) || !i.dli_fname) { printf("  liblmdb not found\n"); return 0; }
  snprintf(cmd, sizeof cmd, "nm -u '%s'", i.dli_fname);
  FILE *p = popen(cmd, "r");
  if (!p) return 0;
  while (fgets(line, sizeof line, p)) {
    n++;
    if (!strncmp(line, "_semget", 7) || !strncmp(line, "_semop", 6) || !strncmp(line, "_semctl", 7) || !strncmp(line, "_sem_open", 9)) { found++; printf("  imports %s", line); }
  }
  int st = pclose(p);
  printf("  %s: %d imports, %d of them semaphores\n", i.dli_fname, n, found);
  return st == 0 && n > 0 && found == 0;
}

static int race(const char *base, int n, int loops) {
  char d[512]; int bar[2], i, bad = 0, fds[64][2];
  sub(d, sizeof d, base, "race");
  if (pipe(bar)) return 0;
  for (i = 0; i < n; i++) {
    if (pipe(fds[i])) return 0;
    if (!fork()) {
      char c; int f = 0, j; close(bar[1]); if (read(bar[0], &c, 1) < 0) _exit(1);
      for (j = 0; j < loops; j++) { MDB_env *e; if (open_env(d, &e) || add(e)) f++; mdb_env_close(e); }
      if (write(fds[i][1], &f, sizeof f) < 0) _exit(1);
      _exit(0);
    }
    close(fds[i][1]);
  }
  close(bar[0]); close(bar[1]);
  for (i = 0; i < n; i++) { int f = 0; if (read(fds[i][0], &f, sizeof f) != sizeof f) f = loops; bad += f; close(fds[i][0]); }
  while (wait(NULL) > 0) ;
  MDB_env *e; unsigned got = 0;
  if (!open_env(d, &e)) { got = count(e); mdb_env_close(e); }
  printf("2. %d processes open one store %d times each and add one: %d of %d failed, the count %u\n", n, loops, bad, n * loops, got);
  return bad == 0 && got == (unsigned)(n * loops);
}

enum { THREADS = 8, ADDS = 200 };
static MDB_env *tenv[2];
static void *adder(void *a) {
  long i = (long)a, j, f = 0;
  for (j = 0; j < ADDS; j++) if (add(tenv[i % 2])) f++;
  return (void *)f;
}

static int threads(const char *base) {
  char d[512]; long i, bad = 0; pthread_t t[THREADS];
  sub(d, sizeof d, base, "threads");
  if (open_env(d, &tenv[0]) || open_env(d, &tenv[1])) { printf("3. open failed\n"); return 0; }
  for (i = 0; i < THREADS; i++) pthread_create(&t[i], NULL, adder, (void *)i);
  for (i = 0; i < THREADS; i++) { void *f; pthread_join(t[i], &f); bad += (long)f; }
  unsigned got = count(tenv[0]);
  mdb_env_close(tenv[1]); mdb_env_close(tenv[0]);
  printf("3. %d threads over 2 envs of one store add %d times each: %ld failed, the count %u\n", THREADS, ADDS, bad, got);
  return bad == 0 && got == THREADS * ADDS;
}

static int many(const char *base) {
  enum { MAX = 50 }; MDB_env *e[MAX]; char d[600]; int i, r = 0;
  char dir[512]; sub(dir, sizeof dir, base, "many");
  for (i = 0; i < MAX && !r; i++) {
    snprintf(d, sizeof d, "%s/%d", dir, i); mkdir(d, 0755);
    r = open_env(d, &e[i]);
    if (!r) r = put(e[i], "k");
  }
  int n = r ? i - 1 : i;
  printf("4. one process opens and writes %d stores at once: %d, %s\n", MAX, n, r ? mdb_strerror(r) : "none failed");
  while (n--) mdb_env_close(e[n]);
  return !r;
}

static int crashed(const char *base) {
  char d[512], l[600];
  sub(d, sizeof d, base, "crashed");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("5. a process killed inside a write txn, then the next opens, writes and closes\n");
  pid_t p = fork();
  if (!p) {
    MDB_env *e; MDB_txn *t;
    if (open_env(d, &e) || mdb_txn_begin(e, NULL, 0, &t)) _exit(1);
    kill(getpid(), SIGKILL);
  }
  int st; waitpid(p, &st, 0);
  int ok = WIFSIGNALED(st) && child_open_put(d, "after", "the next process opens, writes and closes");
  int b0 = locked(l, 0, NULL), br = locked(l, LOCKS, NULL), bw = locked(l, LOCKS + 1, NULL);
  printf("  after: byte 0 %s, the reader lock %s, the writer lock %s\n", b0 ? "locked" : "free", br ? "locked" : "free", bw ? "locked" : "free");
  return ok && !b0 && !br && !bw;
}

/* mdb_reader_list's callback: one line a slot; ctx[0] the pid looked for, ctx[1] set when seen */
static int reader_line(const char *msg, void *ctx) {
  int pid, *c = ctx;
  if (sscanf(msg, " %d", &pid) == 1 && pid == c[0]) c[1] = 1;
  return 0;
}
static int has_reader(MDB_env *e, pid_t pid) {
  int c[2] = { (int)pid, 0 };
  return mdb_reader_list(e, reader_line, c) >= 0 && c[1];
}

static int twice(const char *base) {
  char d[512], l[600]; int go[2], back[2]; char b;
  sub(d, sizeof d, base, "twice");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("6. one process opens env A, opens and closes env B of the same store\n");
  if (pipe(go) || pipe(back)) return 0;
  pid_t p = fork();
  if (!p) {
    MDB_env *a, *bb; MDB_txn *rt, *wt; int ok;
    close(go[1]); close(back[0]);
    if (open_env(d, &a) || put(a, "k") || mdb_txn_begin(a, NULL, MDB_RDONLY, &rt)) _exit(1);
    if (open_env(d, &bb)) _exit(1);
    mdb_env_close(bb);
    ok = has_reader(a, getpid());
    printf("  after B's close, A's read txn %s its reader slot\n", ok ? "keeps" : "LOST");
    b = ok ? '1' : '0';
    if (write(back[1], &b, 1) != 1 || read(go[0], &b, 1) != 1) _exit(1);
    if (mdb_txn_begin(a, NULL, 0, &wt)) _exit(1);
    if (write(back[1], "w", 1) != 1 || read(go[0], &b, 1) != 1) _exit(1);
    mdb_txn_abort(wt); mdb_txn_abort(rt); mdb_env_close(a);
    _exit(0);
  }
  close(go[0]); close(back[1]);	/* a child that dies early ends this process's reads */
  pid_t h0 = 0, hp = 0, hw = 0;
  int slot = read(back[0], &b, 1) == 1 && b == '1';
  int b0 = locked(l, 0, &h0), bp = locked(l, p, &hp);
  if (write(go[1], "g", 1) != 1 || read(back[0], &b, 1) != 1) return 0;
  int bw = locked(l, LOCKS + 1, &hw);
  if (write(go[1], "g", 1) != 1) return 0;
  close(go[1]); close(back[0]);
  int st; waitpid(p, &st, 0);
  printf("  another process sees: byte 0 %s, its pid's byte %s, the writer lock while A writes %s\n",
         b0 == 1 && h0 == p ? "held" : "FREE", bp == 1 && hp == p ? "held" : "FREE", bw == 1 ? "held" : "FREE");
  /* the writer lock is an open file description's: F_GETLK names no pid for it (hw is -1) */
  (void)hw;
  return slot && b0 == 1 && h0 == p && bp == 1 && hp == p && bw == 1 && WIFEXITED(st) && !WEXITSTATUS(st);
}

static int deleted(const char *base) {
  char d[512], cmd[600]; struct stat s;
  sub(d, sizeof d, base, "deleted");
  printf("7. a store whose last user was SIGKILLed, then deleted\n");
  pid_t p = fork();
  if (!p) { MDB_env *e; MDB_txn *t; if (open_env(d, &e) || put(e, "k") || mdb_txn_begin(e, NULL, 0, &t)) _exit(1); kill(getpid(), SIGKILL); }
  int st; waitpid(p, &st, 0);
  snprintf(cmd, sizeof cmd, "rm -rf '%s'", d);
  int rm = system(cmd);
  int gone = stat(d, &s) != 0 && errno == ENOENT;
  printf("  killed %s; its directory %s\n", WIFSIGNALED(st) ? "yes" : "no", gone ? "gone, and nothing else was made" : "LEFT");
  return WIFSIGNALED(st) && rm == 0 && gone;
}

static int format(const char *base) {
  char d[512], l[600]; MDB_env *e;
  sub(d, sizeof d, base, "format");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("8. a lock file of another lock format, while a process holds the store\n");
  if (open_env(d, &e) || put(e, "k")) return 0;
  int fd = open(l, O_RDWR);
  unsigned char *m = fd < 0 ? MAP_FAILED : mmap(NULL, 64, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (m == MAP_FAILED) return 0;
  uint32_t own = *(uint32_t *)(m + FORMAT_AT);
  *(uint32_t *)(m + FORMAT_AT) = own ^ 1;	/* another build's format word */
  pid_t p = fork();
  if (!p) { MDB_env *c; int r = open_env(d, &c); printf("  an open: %s\n", r ? mdb_strerror(r) : "opened"); _exit(r == MDB_VERSION_MISMATCH ? 0 : 1); }
  int st; waitpid(p, &st, 0);
  *(uint32_t *)(m + FORMAT_AT) = own;
  munmap(m, 64); close(fd);
  int ok = WIFEXITED(st) && !WEXITSTATUS(st) && child_open_put(d, "k2", "its own format again: an open");
  mdb_env_close(e);
  return ok;
}

/* 9: two processes, each a writer thread committing and a reader thread beginning and ending read
 * txns, while one long read txn per process stays open: no begin or commit fails (POSIX record locks,
 * one owner a process, answered EDEADLK when one process's writer held the writer lock while its
 * reader waited for the reader table lock, beside another doing the reverse), and each long reader
 * keeps its slot at its txn. */
enum { ROUNDS = 2000 };
static MDB_env *penv;
static int perr;
static void *p_writer(void *a) {
  int i; char k[16];
  for (i = 0; i < ROUNDS; i++) {
    MDB_txn *t; MDB_dbi d; MDB_val K, V = {4, "vvvv"};
    if (mdb_txn_begin(penv, NULL, 0, &t)) { __sync_fetch_and_add(&perr, 1); continue; }
    snprintf(k, sizeof k, "k%d", i % 100); K.mv_size = strlen(k); K.mv_data = k;
    if (mdb_dbi_open(t, NULL, 0, &d) || mdb_put(t, d, &K, &V, 0) || mdb_txn_commit(t)) __sync_fetch_and_add(&perr, 1);
  }
  return NULL;
}
static void *p_reader(void *a) {
  int i;
  for (i = 0; i < ROUNDS * 4; i++) {
    MDB_txn *t;
    if (mdb_txn_begin(penv, NULL, MDB_RDONLY, &t)) { __sync_fetch_and_add(&perr, 1); continue; }
    mdb_txn_abort(t);
  }
  return NULL;
}
static int pair_slot(const char *msg, void *ctx) {
  long *c = ctx; int pid; size_t tx; unsigned long th;
  if (sscanf(msg, " %d %lx %zu", &pid, &th, &tx) == 3 && pid == (int)c[0] && tx == (size_t)c[1]) c[2] = 1;
  return 0;
}
static int threads_in_two(const char *base) {
  char d[512]; int i, ok = 1, st;
  sub(d, sizeof d, base, "threads-in-two");
  printf("9. two processes, each committing on one thread and reading on another, %d rounds, a long reader in each\n", ROUNDS);
  { MDB_env *e; if (open_env(d, &e) || put(e, "k")) return 0; mdb_env_close(e); }
  for (i = 0; i < 2; i++) if (!fork()) {
    pthread_t w, r; MDB_txn *lt; long c[3] = {getpid(), 0, 0};
    if (mdb_env_create(&penv) || mdb_env_set_mapsize(penv, 1L << 30) || mdb_env_open(penv, d, MDB_NOTLS | MDB_NOSYNC, 0664) ||
        mdb_txn_begin(penv, NULL, MDB_RDONLY, &lt)) _exit(2);
    c[1] = (long)mdb_txn_id(lt);
    pthread_create(&w, NULL, p_writer, NULL); pthread_create(&r, NULL, p_reader, NULL);
    pthread_join(w, NULL); pthread_join(r, NULL);
    mdb_reader_list(penv, pair_slot, c);
    printf("  process %d: %d failed begins or commits; its long reader %s its slot\n", i, perr, c[2] ? "keeps" : "LOST");
    fflush(stdout);
    _exit(perr || !c[2] ? 1 : 0);
  }
  while (wait(&st) > 0) ok = ok && WIFEXITED(st) && !WEXITSTATUS(st);
  return ok;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  signal(SIGPIPE, SIG_IGN);	/* a write to a dead child is an error, not this process's death */
  if (argc < 2 || argc > 3) { printf("usage: lmdb-lock DIR [CASES]\n"); return 2; }
  const char *only = argc == 3 ? argv[2] : "123456789";	/* the cases to run, by number */
  mkdir(argv[1], 0755);
  int c1 = strchr(only, '1') ? no_semaphores() : 1, c2 = strchr(only, '2') ? race(argv[1], 16, 200) : 1,
      c3 = strchr(only, '3') ? threads(argv[1]) : 1, c4 = strchr(only, '4') ? many(argv[1]) : 1,
      c5 = strchr(only, '5') ? crashed(argv[1]) : 1, c6 = strchr(only, '6') ? twice(argv[1]) : 1,
      c7 = strchr(only, '7') ? deleted(argv[1]) : 1, c8 = strchr(only, '8') ? format(argv[1]) : 1,
      c9 = strchr(only, '9') ? threads_in_two(argv[1]) : 1;
  printf("cases: %d %d %d %d %d %d %d %d %d\n", c1, c2, c3, c4, c5, c6, c7, c8, c9);
  int ok = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9;
  printf(ok ? "LMDB-LOCK OK\n" : "LMDB-LOCK FAILED\n");
  return ok ? 0 : 1;
}
