// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <cstdint>
#include <vector>
extern "C" {
uint64_t l4s_jtty_create();
void l4s_jtty_cancel(uint64_t);
void l4s_jtty_close(uint64_t);
int32_t l4s_jtty_frames(const char*);
int32_t l4s_jtty_generate(const char*,uint32_t,float,float*,size_t);
int32_t l4s_jtty_push(uint64_t,const int16_t*,size_t,float,float);
struct JttyRow { uint64_t id;double start;float frequency;float snr;int32_t kind;char text[128];char calls[128]; };
int32_t l4s_jtty_result(uint64_t,size_t,JttyRow*);
}
namespace {
jlong create(JNIEnv*,jclass) { return static_cast<jlong>(l4s_jtty_create()); }
void cancel(JNIEnv*,jclass,jlong id) { l4s_jtty_cancel(static_cast<uint64_t>(id)); }
void close(JNIEnv*,jclass,jlong id) { l4s_jtty_close(static_cast<uint64_t>(id)); }
jint frames(JNIEnv *e,jclass,jstring message) {
    if(!message) return -1;
    const char *text=e->GetStringUTFChars(message,nullptr);if(!text) return -1;
    int count=l4s_jtty_frames(text);e->ReleaseStringUTFChars(message,text);return count;
}
jfloatArray generate(JNIEnv *e,jclass,jstring message,jfloat hz,jint rate) {
    if(!message || (rate!=12000 && rate!=24000 && rate!=48000)) return nullptr;
    const char *text=e->GetStringUTFChars(message,nullptr);if(!text) return nullptr;
    int n=l4s_jtty_frames(text);
    if(n<=0) { e->ReleaseStringUTFChars(message,text);return nullptr; }
    std::vector<float> wave(static_cast<size_t>(n)*59*static_cast<size_t>(rate)*32/1000);
    int count=l4s_jtty_generate(text,rate,hz,wave.data(),wave.size());e->ReleaseStringUTFChars(message,text);
    if(count<0) return nullptr;
    auto out=e->NewFloatArray(count);if(out) e->SetFloatArrayRegion(out,0,count,wave.data());return out;
}
jobjectArray push(JNIEnv *e,jclass,jlong id,jshortArray samples,jfloat center,jfloat width) {
    if(!samples) return nullptr;
    int length=e->GetArrayLength(samples);if(length>12000) return nullptr;
    auto pcm=e->GetShortArrayElements(samples,nullptr);if(!pcm) return nullptr;
    int count=l4s_jtty_push(id,pcm,length,center,width);e->ReleaseShortArrayElements(samples,pcm,JNI_ABORT);
    if(count<0) return nullptr;
    auto type=e->FindClass("com/rtbishop/look4sat/core/ft4/JttyNativeMessage");
    auto ctor=e->GetMethodID(type,"<init>","(JDFFILjava/lang/String;Ljava/lang/String;)V");
    auto out=e->NewObjectArray(count,type,nullptr);
    for(int i=0;i<count;i++) {
        JttyRow row{};if(l4s_jtty_result(id,i,&row)!=0) return nullptr;
        auto text=e->NewStringUTF(row.text),calls=e->NewStringUTF(row.calls);
        auto item=e->NewObject(type,ctor,static_cast<jlong>(row.id),row.start,row.frequency,row.snr,row.kind,text,calls);
        e->SetObjectArrayElement(out,i,item);e->DeleteLocalRef(text);e->DeleteLocalRef(calls);e->DeleteLocalRef(item);
    }
    e->DeleteLocalRef(type);return out;
}
JNINativeMethod methods[]={
    {const_cast<char*>("create"),const_cast<char*>("()J"),reinterpret_cast<void*>(create)},
    {const_cast<char*>("cancel"),const_cast<char*>("(J)V"),reinterpret_cast<void*>(cancel)},
    {const_cast<char*>("close"),const_cast<char*>("(J)V"),reinterpret_cast<void*>(close)},
    {const_cast<char*>("frames"),const_cast<char*>("(Ljava/lang/String;)I"),reinterpret_cast<void*>(frames)},
    {const_cast<char*>("generate"),const_cast<char*>("(Ljava/lang/String;FI)[F"),reinterpret_cast<void*>(generate)},
    {const_cast<char*>("push"),const_cast<char*>("(J[SFF)[Lcom/rtbishop/look4sat/core/ft4/JttyNativeMessage;"),reinterpret_cast<void*>(push)}
};
}
bool register_jtty(JNIEnv *env) {
    auto type=env->FindClass("com/rtbishop/look4sat/core/ft4/JttyNativeBindings");
    if(!type) return false;
    bool ok=env->RegisterNatives(type,methods,sizeof(methods)/sizeof(methods[0]))==JNI_OK;
    env->DeleteLocalRef(type);return ok;
}
