// A store's ids (datahike.ids, seon.db/ids!): two 64-bit words in one shared page, the last
// entity id and the last transaction id issued, mapped MAP_SHARED from the file `ids` beside
// the store (or anonymously for a store in memory, shared with forked children), so every
// process writing the store issues each id once. Taking one is a single atomic fetch-add:
// 28-34 ns alone, 200-240 ns with 10 processes contending (claude_scratch/global-ids/MEASURED.md §6).
#include <stdint.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

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
