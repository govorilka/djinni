// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from foo_duplicate_file_creation.djinni

#pragma once

#include <string>
#include <utility>

namespace testsuite {

/**
 * this file is using mixed-case as in past this was causing
 * duplicate files creation error
 */
struct FooRecord final {
    std::string a;

    FooRecord(std::string a_)
    : a(std::move(a_))
    {}
};

}  // namespace testsuite