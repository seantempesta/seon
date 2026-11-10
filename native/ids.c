// A store's ids (datahike.ids, seon.db/ids!): two 64-bit words in one shared page, the last
// entity id and the last transaction id issued, mapped MAP_SHARED from the file `ids` beside
// the store (or anonymously for a store in memory, shared with forked children), so every
// process writing the store issues each id once. Taking one is a single atomic fetch-add:
// 28-34 ns alone, 200-240 ns with 10 processes contending (claude_scratch/global-ids/MEASURED.md §6).
//
// The page, word by word (uint64_t):
//   0  the last entity id issued
//   1  the last transaction id issued
//   2  the stamp: the OS boot identity, first word
//   3  the stamp: the OS boot identity, second word
//   4  the stamp: st_dev of the ids file
//   5  the stamp: st_ino of the ids file
//   6  the stamp: st_dev of the store's data file (an LMDB store's data.mdb: seon.db/data-file)
//   7  the stamp: st_ino of the store's data file
//   8  the stamp's mark, SEON_IDS_MARK when words 2-7 are whole; written last (seon_ids_stamp)
// The rest of the page is zero.
//
// The stamp says when words 0-1 can be trusted to be past every id the store holds
// (docs/storage.md#ids): every process writing the store takes its ids here, so the page is
// behind the store only when it was lost or made apart from it. A process killed loses nothing:
// both files' pages stay in the OS page cache, and this page is the truth. The page is behind
// when (a) it is new or a copy (a store's data.mdb cloned without its ids file, or the ids file
// copied: a copy is another inode), (b) the data file beside it was replaced (another store's
// copy renamed over it, or a clone into a directory that kept its ids file: another inode, as is
// a compacted copy renamed into place, seon.db/compact-if-due!), or (c) the OS lost dirty pages
// (power lost, the OS crashed: the LMDB env runs MDB_NOSYNC, and this page is never msync'd),
// after which the OS has booted again. So the stamp is (boot identity, the ids file's dev and
// ino, the data file's dev and ino), and a page whose stamp is not the current one is moved past
// every branch head's ids, then stamped (seon.db/ids!). Not caught: a data file overwritten in
// place (cp onto an existing file keeps its inode), or a volume that lost dirty pages without a
// boot (forced off, unplugged).
//
// The boot identity: on macOS sysctl kern.boottime, both tv_sec and tv_usec (words 2, 3); on
// Linux /proc/sys/kernel/random/boot_id, a random uuid per boot, its 32 hex digits as two words.
// Where it cannot be read (another OS, the sysctl or the file refused), seon_ids_trusted answers 0
// always and seon_ids_stamp leaves the mark clear: a page is never trusted, so every open reads
// the heads. A stamp is never a constant: it holds what was read, or no mark.
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <sys/types.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#endif

// "SEONIDS2": words 2-7 are a whole stamp (a page stamped before the data file's words has
// "SEONIDS1" in word 6 and no mark in word 8, so it is moved once). A new page is all zero, so it never has the mark, and
// a file made again at a reused inode number is never trusted for its inode alone.
#define SEON_IDS_MARK 0x53454f4e49445332ULL

// The page, or 0 (errno says why). path empty: an anonymous page.
uint64_t *seon_ids_open(const char *path) {
  void *p;
  if (path && path[0]) {
    int fd = open(path, O_RDWR | O_CREAT, 0644);
    if (fd < 0) return 0;
    if (ftruncate(fd, 4096) != 0) { close(fd); return 0; }
    p = mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
  } else {
    p = mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANON, -1, 0);
  }
  return p == MAP_FAILED ? 0 : (uint64_t *)p;
}

// A fresh id of slot (0: entity ids, 1: transaction ids): one past the last issued.
uint64_t seon_ids_take(uint64_t *p, int slot) { return __atomic_add_fetch(&p[slot], 1, __ATOMIC_SEQ_CST); }

// Every id of slot issued from now on is past id.
void seon_ids_reach(uint64_t *p, int slot, uint64_t id) {
  uint64_t c = __atomic_load_n(&p[slot], __ATOMIC_SEQ_CST);
  while (c < id && !__atomic_compare_exchange_n(&p[slot], &c, id, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {}
}

// This boot's identity in b: 1, or 0 when it cannot be read (see the top of the file).
static int boot_identity(uint64_t b[2]) {
#if defined(__APPLE__)
  struct timeval tv;
  size_t len = sizeof tv;
  int mib[2] = {CTL_KERN, KERN_BOOTTIME};
  if (sysctl(mib, 2, &tv, &len, 0, 0) != 0 || len != sizeof tv || tv.tv_sec == 0) return 0;
  b[0] = (uint64_t)tv.tv_sec;
  b[1] = (uint64_t)tv.tv_usec;
  return 1;
#elif defined(__linux__)
  char s[64];
  int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY);
  if (fd < 0) return 0;
  ssize_t n = read(fd, s, sizeof s);
  close(fd);
  int digits = 0;
  b[0] = b[1] = 0;
  for (ssize_t i = 0; i < n && digits < 32; i++) {
    char c = s[i];
    uint64_t v;
    if (c >= '0' && c <= '9') v = (uint64_t)(c - '0');
    else if (c >= 'a' && c <= 'f') v = (uint64_t)(c - 'a' + 10);
    else if (c >= 'A' && c <= 'F') v = (uint64_t)(c - 'A' + 10);
    else continue;
    b[digits / 16] = (b[digits / 16] << 4) | v;
    digits++;
  }
  return digits == 32;
#else
  (void)b;
  return 0;
#endif
}

// 1 when the page's stamp is whole and is this boot's identity, the ids file at path's device
// and inode and the data file at data's: its words 0-1 are past every id the store holds. 0
// otherwise, and always for an anonymous page (path empty), a data file that cannot be read, or a
// boot identity that cannot be read.
int seon_ids_trusted(uint64_t *p, const char *path, const char *data) {
  uint64_t b[2];
  struct stat st, sd;
  if (!path || !path[0] || !data || !boot_identity(b) || stat(path, &st) != 0 || stat(data, &sd) != 0) return 0;
  if (__atomic_load_n(&p[8], __ATOMIC_ACQUIRE) != SEON_IDS_MARK) return 0;
  return __atomic_load_n(&p[2], __ATOMIC_RELAXED) == b[0] && __atomic_load_n(&p[3], __ATOMIC_RELAXED) == b[1] &&
         __atomic_load_n(&p[4], __ATOMIC_RELAXED) == (uint64_t)st.st_dev &&
         __atomic_load_n(&p[5], __ATOMIC_RELAXED) == (uint64_t)st.st_ino &&
         __atomic_load_n(&p[6], __ATOMIC_RELAXED) == (uint64_t)sd.st_dev &&
         __atomic_load_n(&p[7], __ATOMIC_RELAXED) == (uint64_t)sd.st_ino;
}

// Write this boot's stamp for the ids file at path and the data file at data, the mark last. Call
// it only once words 0-1 have been moved past every branch head's ids: a process killed between
// that move and this leaves the page unmarked, and the next open moves it again. Two processes
// opening at once after a boot both move it (seon_ids_reach is a monotonic max) and both write the
// same stamp: either order is safe, and a reader that sees the mark cleared meanwhile moves the
// page itself. An anonymous page, a data file that cannot be read, or a boot identity that cannot
// be read is left with no mark.
void seon_ids_stamp(uint64_t *p, const char *path, const char *data) {
  uint64_t b[2];
  struct stat st, sd;
  __atomic_store_n(&p[8], 0, __ATOMIC_SEQ_CST);
  if (!path || !path[0] || !data || !boot_identity(b) || stat(path, &st) != 0 || stat(data, &sd) != 0) return;
  __atomic_store_n(&p[2], b[0], __ATOMIC_RELAXED);
  __atomic_store_n(&p[3], b[1], __ATOMIC_RELAXED);
  __atomic_store_n(&p[4], (uint64_t)st.st_dev, __ATOMIC_RELAXED);
  __atomic_store_n(&p[5], (uint64_t)st.st_ino, __ATOMIC_RELAXED);
  __atomic_store_n(&p[6], (uint64_t)sd.st_dev, __ATOMIC_RELAXED);
  __atomic_store_n(&p[7], (uint64_t)sd.st_ino, __ATOMIC_RELAXED);
  __atomic_store_n(&p[8], SEON_IDS_MARK, __ATOMIC_RELEASE);
}
