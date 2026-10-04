#include "elf_util.h"

#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <string>

#include "xz.h"

namespace {

struct FindLibrary {
    std::string_view name;
    uintptr_t bias = 0;
    std::string path;
};

int find_library(dl_phdr_info *info, size_t, void *data) {
    auto *find = static_cast<FindLibrary *>(data);
    if (!info->dlpi_name) return 0;
    std::string_view path = info->dlpi_name;
    if (path.size() <= find->name.size() || !path.ends_with(find->name) ||
        path[path.size() - find->name.size() - 1] != '/') {
        return 0;
    }
    find->bias = info->dlpi_addr;
    find->path = path;
    return 1;
}

bool xz_decompress(const uint8_t *in, size_t in_size, std::vector<uint8_t> &out) {
    xz_crc32_init();
    xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1u << 26);
    if (!dec) return false;

    out.resize(in_size * 4 + 4096);
    xz_buf buf{};
    buf.in = in;
    buf.in_size = in_size;
    xz_ret ret;
    do {
        if (buf.out_pos == out.size()) out.resize(out.size() * 2);
        buf.out = out.data();
        buf.out_size = out.size();
        ret = xz_dec_run(dec, &buf);
    } while (ret == XZ_OK);
    xz_dec_end(dec);

    out.resize(buf.out_pos);
    return ret == XZ_STREAM_END;
}

template<typename T>
bool in_bounds(size_t size, ElfW(Off) offset, size_t count = 1) {
    return offset <= size && count <= (size - offset) / sizeof(T);
}

}  // namespace

ElfImg::ElfImg(std::string_view name) {
    FindLibrary find{name, 0, {}};
    dl_iterate_phdr(find_library, &find);
    if (!find.bias) return;

    int fd = open(find.path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st{};
    if (fstat(fd, &st) == 0 && st.st_size > 0) {
        void *map = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (map != MAP_FAILED) {
            file_ = map;
            file_size_ = st.st_size;
        }
    }
    close(fd);
    if (!file_) return;

    bias_ = find.bias;
    scan(static_cast<const uint8_t *>(file_), file_size_, true);
}

ElfImg::~ElfImg() {
    if (file_) munmap(file_, file_size_);
}

void ElfImg::scan(const uint8_t *data, size_t size, bool allow_debugdata) {
    if (!in_bounds<ElfW(Ehdr)>(size, 0)) return;
    auto *ehdr = reinterpret_cast<const ElfW(Ehdr) *>(data);
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        !in_bounds<ElfW(Shdr)>(size, ehdr->e_shoff, ehdr->e_shnum) ||
        ehdr->e_shstrndx >= ehdr->e_shnum) {
        return;
    }
    auto *shdr = reinterpret_cast<const ElfW(Shdr) *>(data + ehdr->e_shoff);
    const ElfW(Shdr) &shstrtab = shdr[ehdr->e_shstrndx];
    if (!in_bounds<char>(size, shstrtab.sh_offset, shstrtab.sh_size)) return;
    auto *shstr = reinterpret_cast<const char *>(data + shstrtab.sh_offset);

    for (int i = 0; i < ehdr->e_shnum; i++) {
        const ElfW(Shdr) &sec = shdr[i];
        if (sec.sh_type == SHT_SYMTAB || sec.sh_type == SHT_DYNSYM) {
            if (sec.sh_link >= ehdr->e_shnum) continue;
            const ElfW(Shdr) &strtab = shdr[sec.sh_link];
            size_t count = sec.sh_size / sizeof(ElfW(Sym));
            if (!in_bounds<ElfW(Sym)>(size, sec.sh_offset, count) ||
                !in_bounds<char>(size, strtab.sh_offset, strtab.sh_size)) {
                continue;
            }
            auto *syms = reinterpret_cast<const ElfW(Sym) *>(data + sec.sh_offset);
            auto *strs = reinterpret_cast<const char *>(data + strtab.sh_offset);
            for (size_t j = 0; j < count; j++) {
                const ElfW(Sym) &sym = syms[j];
                auto type = sym.st_info & 0xf;
                if (sym.st_value == 0 || sym.st_shndx == SHN_UNDEF ||
                    (type != STT_FUNC && type != STT_OBJECT) || sym.st_name >= strtab.sh_size) {
                    continue;
                }
                const char *str = strs + sym.st_name;
                size_t len = strnlen(str, strtab.sh_size - sym.st_name);
                symbols_.emplace(std::string_view(str, len), sym.st_value);
            }
        } else if (allow_debugdata && sec.sh_name < shstrtab.sh_size &&
                   strcmp(shstr + sec.sh_name, ".gnu_debugdata") == 0 &&
                   in_bounds<uint8_t>(size, sec.sh_offset, sec.sh_size)) {
            if (xz_decompress(data + sec.sh_offset, sec.sh_size, debugdata_)) {
                scan(debugdata_.data(), debugdata_.size(), false);
            }
        }
    }
}

void *ElfImg::symbol(std::string_view name) const {
    auto it = symbols_.find(name);
    return it == symbols_.end() ? nullptr : reinterpret_cast<void *>(bias_ + it->second);
}

void *ElfImg::symbol_prefix(std::string_view prefix) const {
    auto it = symbols_.lower_bound(prefix);
    if (it == symbols_.end() || !it->first.starts_with(prefix)) return nullptr;
    return reinterpret_cast<void *>(bias_ + it->second);
}
