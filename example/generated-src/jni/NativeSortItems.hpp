// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from example.djinni

#pragma once

#include "SortItems.hpp"
#include "djinni_support.hpp"

namespace djinni_generated {

class NativeSortItems final : ::djinni::JniInterface<::textsort::SortItems, NativeSortItems> {
public:
    using CppType = std::shared_ptr<::textsort::SortItems>;
    using CppOptType = std::shared_ptr<::textsort::SortItems>;
    using JniType = jobject;

    using Boxed = NativeSortItems;

    ~NativeSortItems();

    static CppType toCpp(JNIEnv* jniEnv, JniType j) { return ::djinni::JniClass<NativeSortItems>::get()._fromJava(jniEnv, j); }
    static ::djinni::LocalRef<JniType> fromCppOpt(JNIEnv* jniEnv, const CppOptType& c) { return {jniEnv, ::djinni::JniClass<NativeSortItems>::get()._toJava(jniEnv, c)}; }
    static ::djinni::LocalRef<JniType> fromCpp(JNIEnv* jniEnv, const CppType& c) { return fromCppOpt(jniEnv, c); }

private:
    NativeSortItems();
    friend ::djinni::JniClass<NativeSortItems>;
    friend ::djinni::JniInterface<::textsort::SortItems, NativeSortItems>;

};

}  // namespace djinni_generated
