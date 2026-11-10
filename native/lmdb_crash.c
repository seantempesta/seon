/* lmdb-crash DIR: the proof that an OS crash at any moment leaves an LMDB store kept with
 * MDB_SAFENOSYNC holding exactly its last synced commit or a later one, never a corrupt one
 * (docs/storage.md#durability-is-continuous-group-commit, docs/failure-points.md#lmdb-crash-safety).
 *
 * LMDB (reference-code/lmdb's mdb.c and midl.c) is compiled into this program with its writes and
 * flushes renamed (-Dpwrite=crash_pwrite -Dwritev=crash_writev -Dpwritev=crash_pwritev
 * -Dfcntl=crash_fcntl -DMDB_BOOT_ID=crash_boot_id), so every write it makes to the data file is
 * logged as (offset, bytes), and each barrier (F_BARRIERFSYNC) and flush (F_FULLFSYNC) as an event;
 * no flush reaches the disk, none is needed. The run: 40 commits of 60 puts and 20 deletes over 400
 * keys (1 value in 80 is 9-12 KB, on overflow pages), mdb_env_sync(env, 1) after every commit for
 * the first 20 and after every third after, as Seon's flusher calls it.
 *
 * For every crash point p (the first p events issued) and 8 samples, the image is the store after
 * its first sync, every write before the last flush before p, and of the writes after it: every
 * write before a chosen barrier at or after that flush, then each 4 KB sector written between that
 * barrier and the next one taking any one of its versions (none, the first, ..., the latest): any
 * subset of them, in any order, torn at 4 KB. A forked child then opens the image as the first
 * process after a reboot (another boot identity, MDB_NOLOCK) and checks: the txn it opens at is the
 * last synced one or one whose sync was under way (with MDB_SAFENOSYNC), or at least the last
 * synced one (without); every key and value equals that commit's, and nothing else is there; one
 * more commit works; and LMDB's page accounting holds (mdb_stat's: every page used once).
 * Prints LMDB-CRASH OK and exits 0 only when every image holds. Built against a LMDB without
 * MDB_SAFENOSYNC it runs plain MDB_NOSYNC and is red: the crash it proves against. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>

#ifndef MDB_SAFENOSYNC
#define MDB_SAFENOSYNC 0	/* a LMDB without it: plain MDB_NOSYNC, the red control */
#endif

/* ---- the recorder: LMDB's writes and flushes come here ---- */
typedef struct { char type; off_t off; size_t len; unsigned char *data; long tag; } ev;
static int rec_on; static ino_t rec_ino; static ev *evs; static int nev, cap;
static uint64_t boot = 1;	/* this run's boot; a recovering child's is another */

static void push(char type, off_t off, const void *b, size_t n, long tag) {
  if (nev == cap) { cap = cap ? cap * 2 : 1024; evs = realloc(evs, cap * sizeof *evs); }
  ev *e = &evs[nev++]; e->type = type; e->off = off; e->len = n; e->tag = tag; e->data = NULL;
  if (n) { e->data = malloc(n); memcpy(e->data, b, n); }
}
static void mark(char type, long tag) { if (rec_on) push(type, 0, NULL, 0, tag); }
static int ours(int fd) { struct stat s; return rec_on && !fstat(fd, &s) && s.st_ino == rec_ino; }

ssize_t crash_pwrite(int fd, const void *b, size_t n, off_t off) {
  if (ours(fd)) push('W', off, b, n, 0);
  return pwrite(fd, b, n, off);
}
static void logv(int fd, const struct iovec *iov, int cnt, off_t off) {
  if (!ours(fd)) return;
  size_t n = 0; int i; for (i = 0; i < cnt; i++) n += iov[i].iov_len;
  char *b = malloc(n), *p = b; for (i = 0; i < cnt; i++) { memcpy(p, iov[i].iov_base, iov[i].iov_len); p += iov[i].iov_len; }
  push('W', off, b, n, 0); free(b);
}
ssize_t crash_writev(int fd, const struct iovec *iov, int cnt) { logv(fd, iov, cnt, lseek(fd, 0, SEEK_CUR)); return writev(fd, iov, cnt); }
ssize_t crash_pwritev(int fd, const struct iovec *iov, int cnt, off_t off) { logv(fd, iov, cnt, off); return pwritev(fd, iov, cnt, off); }
int crash_fcntl(int fd, int cmd, ...) {
  va_list a;
#ifdef F_BARRIERFSYNC
  if (cmd == F_BARRIERFSYNC) { if (ours(fd)) push('B', 0, NULL, 0, 0); return 0; }
#endif
#ifdef F_FULLFSYNC
  if (cmd == F_FULLFSYNC) { if (ours(fd)) push('S', 0, NULL, 0, 0); return 0; }
#endif
  va_start(a, cmd);
  if (cmd == F_SETLK || cmd == F_SETLKW || cmd == F_GETLK) { void *lk = va_arg(a, void *); va_end(a); return fcntl(fd, cmd, lk); }
  int arg = va_arg(a, int); va_end(a);
  return fcntl(fd, cmd, arg);
}
int crash_boot_id(uint64_t b[2]) { b[0] = boot; b[1] = 0; return 1; }

/* ---- the workload and its model ---- */
#define CHK(x) do { int _r = (x); if (_r) { printf("FAIL %s:%d %s: %s\n", __FILE__, __LINE__, #x, mdb_strerror(_r)); exit(2); } } while (0)
enum { NK = 400, MAXT = 128, MAXV = 12000, SECT = 4096, NSAMPLE = 8, COMMITS = 40 };
typedef struct { int len; uint32_t seed; } ent;
static ent model[NK], snaps[MAXT][NK];
static unsigned char vbuf[MAXV];
static uint64_t rs = 0x9E3779B97F4A7C15ULL;
static uint64_t rnd(void) { rs ^= rs << 13; rs ^= rs >> 7; rs ^= rs << 17; return rs; }
static void fillv(uint32_t seed, int len) { uint64_t s = seed * 2654435761ULL + 1; int i; for (i = 0; i < len; i++) { s = s * 6364136223846793005ULL + 1442695040888963407ULL; vbuf[i] = (unsigned char)(s >> 56); } }
static int vlen(void) { uint64_t r = rnd(); return (r % 80 == 0) ? 9000 + (int)(r % 3000) : 8 + (int)(r % 120); }

static unsigned flags(void) { return MDB_NOSUBDIR | MDB_NOTLS | MDB_NOSYNC | MDB_SAFENOSYNC; }
static void churn(MDB_env *e, int n, int m) {
  MDB_txn *t; MDB_dbi d; char k[16]; int j; CHK(mdb_txn_begin(e, NULL, 0, &t)); CHK(mdb_dbi_open(t, NULL, 0, &d));
  for (j = 0; j < n; j++) { int i = rnd() % NK, len = vlen(); uint32_t seed = (uint32_t)rnd(); snprintf(k, 16, "k%05d", i); fillv(seed, len);
    MDB_val K = {6, k}, V = {(size_t)len, vbuf}; CHK(mdb_put(t, d, &K, &V, 0)); model[i].len = len; model[i].seed = seed; }
  for (j = 0; j < m; j++) { int i = rnd() % NK; snprintf(k, 16, "k%05d", i); MDB_val K = {6, k}; int r = mdb_del(t, d, &K, NULL); if (r && r != MDB_NOTFOUND) CHK(r); model[i].len = 0; }
  CHK(mdb_txn_commit(t));
}
static size_t txnid_now(MDB_env *e) { MDB_envinfo i; CHK(mdb_env_info(e, &i)); return i.me_last_txnid; }
/* the flusher: Y announces the txn this sync makes steady; it is durable at the sync's last flush */
static void sync_now(MDB_env *e) { mark('Y', (long)txnid_now(e)); CHK(mdb_env_sync(e, 1)); }

/* ---- a recovering child: open the image after a reboot and check it ---- */
static int check(const char *path, size_t lo, size_t hi, size_t pending) {
  MDB_env *e; MDB_txn *t; MDB_dbi d; MDB_cursor *c; MDB_val K, V; int r, bad = 0, seen = 0, i;
  boot = 2;
  CHK(mdb_env_create(&e)); CHK(mdb_env_set_mapsize(e, 64u << 20));
  CHK(mdb_env_open(e, path, flags() | MDB_NOLOCK, 0664));	/* NOLOCK: the first opener, no lock file */
  CHK(mdb_txn_begin(e, NULL, MDB_RDONLY, &t)); size_t tx = mdb_txn_id(t);
  if (MDB_SAFENOSYNC ? !(tx == lo || (pending && tx == pending)) : (tx < lo || tx > hi)) {
    printf("    opened at txn %zu; the last synced %zu, its sync under way %zu, the last committed %zu\n", tx, lo, pending, hi);
    return 3;
  }
  const ent *m = snaps[tx]; static unsigned char got[NK]; memset(got, 0, NK);
  CHK(mdb_dbi_open(t, NULL, 0, &d)); CHK(mdb_cursor_open(t, d, &c));
  while ((r = mdb_cursor_get(c, &K, &V, MDB_NEXT)) == 0) {
    seen++; char kb[6] = {0}; if (K.mv_size == 6) memcpy(kb, (char *)K.mv_data + 1, 5);
    i = (K.mv_size == 6 && ((char *)K.mv_data)[0] == 'k') ? atoi(kb) : -1;
    if (i < 0 || i >= NK || !m[i].len) { bad++; continue; }
    got[i] = 1; fillv(m[i].seed, m[i].len); if ((int)V.mv_size != m[i].len || memcmp(V.mv_data, vbuf, V.mv_size)) bad++;
  }
  if (r != MDB_NOTFOUND) { printf("    the walk at txn %zu: %s\n", tx, mdb_strerror(r)); return 2; }
  for (i = 0; i < NK; i++) if (m[i].len && !got[i]) bad++;
  mdb_cursor_close(c); mdb_txn_abort(t);
  if (bad) { printf("    txn %zu: %d keys wrong or missing (%d read)\n", tx, bad, seen); return 1; }
  memcpy(model, m, sizeof model); churn(e, 50, 20);	/* a commit on the recovered store */
  MDB_stat sm, sf; MDB_envinfo inf; size_t ids = 0;
  CHK(mdb_txn_begin(e, NULL, MDB_RDONLY, &t)); CHK(mdb_stat(t, 0, &sf)); CHK(mdb_stat(t, d, &sm)); CHK(mdb_env_info(e, &inf));
  CHK(mdb_cursor_open(t, 0, &c)); while ((r = mdb_cursor_get(c, &K, &V, MDB_NEXT)) == 0) ids += *(size_t *)V.mv_data;
  size_t used = sm.ms_branch_pages + sm.ms_leaf_pages + sm.ms_overflow_pages + sf.ms_branch_pages + sf.ms_leaf_pages + sf.ms_overflow_pages + ids + 2;
  if (r != MDB_NOTFOUND || used != inf.me_last_pgno + 1) { printf("    after a commit: %zu pages accounted, %zu in use (%s)\n", used, inf.me_last_pgno + 1, mdb_strerror(r)); return 4; }
  return 0;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  if (argc != 2) { printf("usage: lmdb-crash DIR\n"); return 2; }
  char path[512], ipath[512]; int i, c, p, j;
  mkdir(argv[1], 0755);
  snprintf(path, 512, "%s/data.mdb", argv[1]); snprintf(ipath, 512, "%s/image.mdb", argv[1]);
  unlink(path); unlink(ipath);
  { char l[600]; snprintf(l, 600, "%s-lock", path); unlink(l); }
  printf("%s: %d commits, a sync after each of the first %d and after every third after\n",
         MDB_SAFENOSYNC ? "MDB_SAFENOSYNC" : "MDB_NOSYNC (this LMDB has no MDB_SAFENOSYNC)", COMMITS, COMMITS / 2);
  /* the store as its first sync left it: the durable base */
  MDB_env *e; CHK(mdb_env_create(&e)); CHK(mdb_env_set_mapsize(e, 64u << 20)); CHK(mdb_env_open(e, path, flags(), 0664));
  int fd = open(path, O_RDWR); struct stat sb; fstat(fd, &sb); rec_ino = sb.st_ino;
  churn(e, NK, 0); memcpy(snaps[txnid_now(e)], model, sizeof model);
  CHK(mdb_env_sync(e, 1));
  size_t base_tx = txnid_now(e);
  fstat(fd, &sb); size_t fsz = sb.st_size; unsigned char *base = calloc(1, 64u << 20);
  if (pread(fd, base, fsz, 0) != (ssize_t)fsz) return 2;
  /* the run, recorded */
  rec_on = 1;
  for (c = 0; c < COMMITS; c++) {
    churn(e, 60, 20); size_t tx = txnid_now(e); if (tx >= MAXT) return 2; memcpy(snaps[tx], model, sizeof model); mark('C', (long)tx);
    if (c < COMMITS / 2 || c % 3 == 2) sync_now(e);
  }
  rec_on = 0; mdb_env_close(e); close(fd);
  size_t ext = fsz; int nw = 0;
  for (i = 0; i < nev; i++) if (evs[i].type == 'W') { nw++; if ((size_t)evs[i].off + evs[i].len > ext) ext = evs[i].off + evs[i].len; }
  ext = (ext + SECT - 1) / SECT * SECT;
  printf("%d events (%d writes, %d barriers, %d flushes), the image %zu KB\n", nev, nw,
         ({ int n = 0; for (i = 0; i < nev; i++) n += evs[i].type == 'B'; n; }), ({ int n = 0; for (i = 0; i < nev; i++) n += evs[i].type == 'S'; n; }), ext >> 10);
  /* every crash point */
  unsigned char *img = calloc(1, ext), *prev = calloc(1, ext), *pre_s = calloc(1, ext); memcpy(pre_s, base, fsz);
  int fdi = open(ipath, O_RDWR | O_CREAT | O_TRUNC, 0664); if (ftruncate(fdi, ext)) return 2;
  size_t nsect = ext / SECT; int *cnt = calloc(nsect, sizeof(int)), *pick = calloc(nsect, sizeof(int)), *seen = calloc(nsect, sizeof(int));
  long images = 0, fails = 0, failpts = 0; int s = -1;	/* s: the last flush before p; pre_s: base + the writes before it */
  for (p = 0; p <= nev; p++) {
    if (p > 0 && evs[p - 1].type == 'S') { for (i = s + 1; i < p - 1; i++) if (evs[i].type == 'W') memcpy(pre_s + evs[i].off, evs[i].data, evs[i].len); s = p - 1; }
    /* what the events before p made durable, and a sync under way */
    size_t durable = base_tx, pending = 0, hi = base_tx, ann = 0;
    for (i = 0; i < p; i++) {
      if (evs[i].type == 'Y') ann = (size_t)evs[i].tag;
      if (evs[i].type == 'S' && ann && i <= s) { durable = ann; ann = 0; }
      if (evs[i].type == 'C') hi = (size_t)evs[i].tag;
    }
    hi++;	/* the commit under way may have written its meta */
    pending = ann;
    int cand[64], nc = 0, pfail = 0; cand[nc++] = s + 1;
    for (i = s + 1; i < p && nc < 64; i++) if (evs[i].type == 'B') cand[nc++] = i + 1;
    for (j = 0; j < NSAMPLE; j++) {
      int b = j == 0 ? cand[0] : j == 1 ? cand[nc - 1] : cand[rnd() % nc];
      int b2 = p; for (i = b; i < p; i++) if (evs[i].type == 'B') { b2 = i; break; }
      memcpy(img, pre_s, ext);
      for (i = s + 1; i < b; i++) if (evs[i].type == 'W') memcpy(img + evs[i].off, evs[i].data, evs[i].len);
      memset(cnt, 0, nsect * sizeof(int)); memset(seen, 0, nsect * sizeof(int));
      for (i = b; i < b2; i++) if (evs[i].type == 'W') { size_t q; for (q = evs[i].off / SECT; q * SECT < evs[i].off + evs[i].len; q++) cnt[q]++; }
      size_t q;
      for (q = 0; q < nsect; q++) pick[q] = !cnt[q] ? 0 : j == 0 ? 0 : j == 1 ? cnt[q] : (j == 2 ? (int)(rnd() % 2) * cnt[q] : (int)(rnd() % (cnt[q] + 1)));
      for (i = b; i < b2; i++) if (evs[i].type == 'W') {
        const ev *x = &evs[i];
        for (q = x->off / SECT; q * SECT < x->off + x->len; q++) {
          if (++seen[q] != pick[q]) continue;
          size_t lo = q * SECT > (size_t)x->off ? q * SECT : (size_t)x->off, up = (q + 1) * SECT < x->off + x->len ? (q + 1) * SECT : x->off + x->len;
          memcpy(img + lo, x->data + (lo - x->off), up - lo);
        }
      }
      for (q = 0; q < nsect; q++) if (memcmp(img + q * SECT, prev + q * SECT, SECT)) { if (pwrite(fdi, img + q * SECT, SECT, q * SECT) != SECT) return 2; memcpy(prev + q * SECT, img + q * SECT, SECT); }
      images++;
      pid_t pid = fork();
      if (!pid) _exit(check(ipath, durable, hi, pending));
      int st; waitpid(pid, &st, 0);
      int rc = WIFEXITED(st) ? WEXITSTATUS(st) : 100 + WTERMSIG(st);
      if (rc) {
        fails++;
        if (!pfail && failpts < 6) printf("  crash point %d (after %c, the last flush at %d, sample %d, barrier %d..%d): %s %d\n", p, p ? evs[p - 1].type : '-', s, j, b, b2, rc >= 100 ? "killed by signal" : "exit", rc >= 100 ? rc - 100 : rc);
        pfail = 1;
      }
      if (pread(fdi, prev, ext, 0) != (ssize_t)ext) return 2;	/* the child's recovery and commit wrote it */
    }
    failpts += pfail;
  }
  close(fdi); unlink(ipath); unlink(path);
  { char l[600]; snprintf(l, 600, "%s-lock", path); unlink(l); }
  printf("%ld images at %d crash points: %ld failed, at %ld crash points\n", images, nev + 1, fails, failpts);
  printf(fails ? "LMDB-CRASH FAILED\n" : "LMDB-CRASH OK\n");
  return fails ? 1 : 0;
}
