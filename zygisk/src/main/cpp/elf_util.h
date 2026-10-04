#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <string_view>
#include <vector>

// Symbol lookup for a library already loaded in this process. Besides .dynsym and .symtab it
// reads the xz-compressed .gnu_debugdata (MiniDebugInfo), where Android keeps local symbols that
// LSPlant needs (e.g. art::GetMethodShorty) and that neither dlsym nor Dobby can see.
class ElfImg {
public:
    explicit ElfImg(std::string_view name);
    ~ElfImg();

    ElfImg(const ElfImg &) = delete;
    ElfImg &operator=(const ElfImg &) = delete;

    bool valid() const { return bias_ != 0 && !symbols_.empty(); }
    size_t symbol_count() const { return symbols_.size(); }

    void *symbol(std::string_view name) const;
    // First symbol (in name order) that starts with prefix.
    void *symbol_prefix(std::string_view prefix) const;

private:
    void scan(const uint8_t *data, size_t size, bool allow_debugdata);

    uintptr_t bias_ = 0;
    void *file_ = nullptr;
    size_t file_size_ = 0;
    std::vector<uint8_t> debugdata_;
    // Names point into file_ or debugdata_, which live as long as this object.
    std::map<std::string_view, uintptr_t> symbols_;
};
