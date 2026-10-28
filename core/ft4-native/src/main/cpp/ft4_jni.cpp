#include <jni.h>
#include "rust_ft4.h"
#include <algorithm>
#include <string>
#include <vector>
#ifndef FT4_ANDROID_ABI
#define FT4_ANDROID_ABI "host-x86_64"
#endif
#ifndef FT4_UPSTREAM_SHA
#define FT4_UPSTREAM_SHA "707ebed2fb2290279e16ac79146c7a14df8b793b"
#endif

namespace {
class UtfChars final {
public:
    UtfChars(JNIEnv *e, jstring s): env(e), value(s), chars(s ? e->GetStringUTFChars(s,nullptr):nullptr) {}
    ~UtfChars() { if(chars) env->ReleaseStringUTFChars(value,chars); }
    const char *get() const { return chars ? chars : ""; }
private:
    JNIEnv *env; jstring value; const char *chars;
};
void fail(JNIEnv *env, const char *message) {
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if(type) { env->ThrowNew(type,message);env->DeleteLocalRef(type); }
}
jobjectArray native_capabilities(JNIEnv *env,jclass) {
    const char *values[]={FT4_ANDROID_ABI,"1","1","1","",FT4_UPSTREAM_SHA,"mfsk-core-rust","0.14.0"};
    jclass string=env->FindClass("java/lang/String");
    jobjectArray out=env->NewObjectArray(8,string,nullptr);
    for(int i=0;i<8;i++) { jstring s=env->NewStringUTF(values[i]);env->SetObjectArrayElement(out,i,s);env->DeleteLocalRef(s); }
    env->DeleteLocalRef(string);return out;
}
jlong native_create(JNIEnv *,jclass,jlong) { return static_cast<jlong>(l4s_ft4_create()); }
void native_destroy(JNIEnv *,jclass,jlong id) { l4s_ft4_close(static_cast<uint64_t>(id)); }
void native_cancel(JNIEnv *,jclass,jlong id) { l4s_ft4_cancel(static_cast<uint64_t>(id)); }
jboolean native_reset(JNIEnv *,jclass,jlong id) { return l4s_ft4_reset(static_cast<uint64_t>(id))==0; }
jboolean native_budget(JNIEnv *,jclass,jlong id) { return l4s_ft4_budget_exhausted(static_cast<uint64_t>(id)); }
jobjectArray native_decode(JNIEnv *env,jclass,jlong id,jfloatArray samples,jlong utc,jint depth,jint rounds,
    jboolean,jboolean wideband,jint frequency,jstring my_call,jobjectArray hints,jlong budget) {
    jclass type=env->FindClass("com/rtbishop/look4sat/core/ft4/Ft4NativeDecodeResult");
    if(!samples || env->GetArrayLength(samples)!=90000) { fail(env,"FT4 requires 90000 samples");return nullptr; }
    UtfChars own(env,my_call);
    jstring other=hints && env->GetArrayLength(hints)>0 ? static_cast<jstring>(env->GetObjectArrayElement(hints,0)) : nullptr;
    int count;
    {
        UtfChars theirs(env,other);
        float *pcm=env->GetFloatArrayElements(samples,nullptr);
        if(!pcm) return nullptr;
        count=l4s_ft4_decode(static_cast<uint64_t>(id),pcm,90000,utc/7500,
            static_cast<uint32_t>(depth),static_cast<size_t>(rounds),wideband,static_cast<float>(frequency),
            own.get(),theirs.get(),static_cast<uint64_t>(std::clamp<jlong>(budget,1,10000)));
        env->ReleaseFloatArrayElements(samples,pcm,JNI_ABORT);
    }
    if(other) env->DeleteLocalRef(other);
    if(count<0) { fail(env,count==-3 ? "FT4 decode cancelled" : "FT4 decoder handle, input or state is invalid");return nullptr; }
    jmethodID ctor=env->GetMethodID(type,"<init>","(JIFFLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V");
    jobjectArray out=env->NewObjectArray(count,type,nullptr);
    for(int i=0;i<count;i++) {
        RustFt4Row row{};
        if(l4s_ft4_result(static_cast<uint64_t>(id),static_cast<size_t>(i),&row)!=0) { fail(env,"FT4 result expired");return nullptr; }
        jstring text=env->NewStringUTF(row.text),source=env->NewStringUTF(row.source),target=env->NewStringUTF(row.target),detail=env->NewStringUTF(row.detail);
        jobject item=env->NewObject(type,ctor,utc,row.snr,row.dt,row.frequency,text,source,target,detail,static_cast<jlong>(row.hash));
        env->SetObjectArrayElement(out,i,item);
        env->DeleteLocalRef(item);env->DeleteLocalRef(text);env->DeleteLocalRef(source);env->DeleteLocalRef(target);env->DeleteLocalRef(detail);
    }
    env->DeleteLocalRef(type);return out;
}
jstring native_validate(JNIEnv *env,jclass,jstring message) {
    UtfChars text(env,message);char canonical[128]{};
    if(l4s_ft4_validate(text.get(),canonical,sizeof(canonical))!=0) return nullptr;
    return env->NewStringUTF(canonical);
}
jfloatArray native_generate(JNIEnv *env,jclass,jstring message,jfloat frequency,jint rate) {
    if(rate!=12000 && rate!=24000 && rate!=48000) { fail(env,"Unsupported FT4 sample rate");return nullptr; }
    UtfChars text(env,message);std::vector<float> wave(static_cast<size_t>(rate)*504/100);
    int count=l4s_ft4_generate(text.get(),static_cast<uint32_t>(rate),frequency,wave.data(),wave.size());
    if(count<0) return nullptr;
    jfloatArray out=env->NewFloatArray(count);env->SetFloatArrayRegion(out,0,count,wave.data());return out;
}
jstring native_self_test(JNIEnv *env,jclass) {
    char normalized[128]{};
    if(l4s_ft4_validate("CQ K1ABC FN42",normalized,sizeof(normalized))!=0) return env->NewStringUTF("FT4 packing failed");
    std::vector<float> wave(60480),slot(90000,0);
    if(l4s_ft4_generate(normalized,12000,1500,wave.data(),wave.size())!=60480) return env->NewStringUTF("FT4 waveform failed");
    std::copy(wave.begin(),wave.end(),slot.begin()+6000);
    uint64_t id=l4s_ft4_create();
    int count=l4s_ft4_decode(id,slot.data(),slot.size(),0,3,3,true,1500,"K1ABC","",5000);
    bool found=false;
    for(int i=0;i<count;i++) { RustFt4Row row{};if(l4s_ft4_result(id,i,&row)==0 && std::string(row.text)==normalized) found=true; }
    l4s_ft4_close(id);return env->NewStringUTF(found ? "" : "FT4 RX self-test failed");
}
JNINativeMethod methods[]={
    {const_cast<char*>("nativeCapabilities"),const_cast<char*>("()[Ljava/lang/String;"),reinterpret_cast<void*>(native_capabilities)},
    {const_cast<char*>("nativeCreate"),const_cast<char*>("(J)J"),reinterpret_cast<void*>(native_create)},
    {const_cast<char*>("nativeDestroy"),const_cast<char*>("(J)V"),reinterpret_cast<void*>(native_destroy)},
    {const_cast<char*>("nativeCancel"),const_cast<char*>("(J)V"),reinterpret_cast<void*>(native_cancel)},
    {const_cast<char*>("nativeReset"),const_cast<char*>("(J)Z"),reinterpret_cast<void*>(native_reset)},
    {const_cast<char*>("nativeBudgetExhausted"),const_cast<char*>("(J)Z"),reinterpret_cast<void*>(native_budget)},
    {const_cast<char*>("nativeDecode"),const_cast<char*>("(J[FJIIZZILjava/lang/String;[Ljava/lang/String;J)[Lcom/rtbishop/look4sat/core/ft4/Ft4NativeDecodeResult;"),reinterpret_cast<void*>(native_decode)},
    {const_cast<char*>("nativeValidate"),const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),reinterpret_cast<void*>(native_validate)},
    {const_cast<char*>("nativeGenerate"),const_cast<char*>("(Ljava/lang/String;FI)[F"),reinterpret_cast<void*>(native_generate)},
    {const_cast<char*>("nativeSelfTest"),const_cast<char*>("()Ljava/lang/String;"),reinterpret_cast<void*>(native_self_test)}
};
}
JNIEXPORT jint JNI_OnLoad(JavaVM *vm,void*) {
    JNIEnv *env=nullptr;if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK) return JNI_ERR;
    jclass type=env->FindClass("com/rtbishop/look4sat/core/ft4/Ft4NativeBindings");
    if(!type || env->RegisterNatives(type,methods,sizeof(methods)/sizeof(methods[0]))!=JNI_OK) return JNI_ERR;
    env->DeleteLocalRef(type);return JNI_VERSION_1_6;
}
