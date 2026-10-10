// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <libusb.h>
#include <libuac.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <deque>
#include <mutex>
#include <memory>
#include <thread>
#include <vector>
#include <condition_variable>
#include <stdexcept>
#include <algorithm>
#include <string>

namespace {
struct Session {
    int file=-1;
    uint32_t rate=48000;
    uint16_t channels=1;
    std::shared_ptr<uac::uac_context> ctx;
    std::shared_ptr<uac::uac_device_handle> dev;
    std::shared_ptr<uac::uac_stream_handle> stream;
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<std::vector<uint8_t>> queue;
    bool done=false;
    std::thread writer;
    std::atomic<uint64_t> bytes{0}, dropped{0};
    std::atomic<uint32_t> peak{0};
    std::atomic<bool> diskError{false};
    ~Session() { if(file>=0) close(file); }
};
std::mutex gMutex;
std::unique_ptr<Session> current;
void w16(uint8_t* b,uint16_t x) { b[0]=x&255; b[1]=(x>>8)&255; }
void w32(uint8_t* b,uint32_t x) { for(int i=0;i<4;++i)b[i]=(x>>(8*i))&255; }
void header(uint8_t* h,const Session& s,uint32_t len) {
    memset(h,0,44);
    memcpy(h,"RIFF",4); w32(h+4,36+len); memcpy(h+8,"WAVEfmt ",8);
    w32(h+16,16); w16(h+20,1); w16(h+22,s.channels);
    w32(h+24,s.rate); w32(h+28,s.rate*s.channels*2);
    w16(h+32,s.channels*2); w16(h+34,16);
    memcpy(h+36,"data",4); w32(h+40,len);
}
bool writeAll(int fd,const uint8_t* p,size_t n) {
    while(n) {
        ssize_t r=write(fd,p,n);
        if(r<0 && errno==EINTR)continue;
        if(r<=0)return false;
        p+=r;n-=static_cast<size_t>(r);
    }
    return true;
}
void enqueue(Session& s,uint8_t* pcm,unsigned len) noexcept {
    if(!pcm||!len||s.diskError.load())return;
    if(len % (s.channels*2u)) {++s.dropped;return;}
    try {
        uint32_t peak=0;
        for(unsigned i=0;i+1<len;i+=2) {
            int16_t v=static_cast<int16_t>(static_cast<uint16_t>(pcm[i])|
                                         (static_cast<uint16_t>(pcm[i+1])<<8));
            peak=std::max(peak,static_cast<uint32_t>(std::abs(static_cast<int>(v))));
        }
        peak=static_cast<uint32_t>(static_cast<uint64_t>(peak)*1000000/32768);
        uint32_t old=s.peak.load();
        while(old<peak&&!s.peak.compare_exchange_weak(old,peak)){}
        std::lock_guard<std::mutex> l(s.mutex);
        if(s.done||s.queue.size()>=256) {++s.dropped;return;}
        s.queue.emplace_back(pcm,pcm+len);
        s.ready.notify_one();
    } catch(...) {++s.dropped;}
}
void writeLoop(Session* s) {
    for(;;) {
        std::vector<uint8_t> data;
        {
            std::unique_lock<std::mutex> l(s->mutex);
            s->ready.wait(l,[&]{return s->done||!s->queue.empty();});
            if(s->queue.empty()&&s->done)return;
            data=std::move(s->queue.front());
            s->queue.pop_front();
        }
        if(s->bytes.load()+data.size()>UINT32_MAX-36 ||
           !writeAll(s->file,data.data(),data.size())) {
            s->diskError=true;
            ++s->dropped;
            std::lock_guard<std::mutex> l(s->mutex);
            s->queue.clear();
            return;
        }
        s->bytes+=data.size();
    }
}
void shutdown(Session& s) {
    s.stream.reset();
    { std::lock_guard<std::mutex> l(s.mutex);s.done=true; }
    s.ready.notify_all();
    if(s.writer.joinable())s.writer.join();
    s.dev.reset();
    s.ctx.reset();
}
jstring reply(JNIEnv* e,const std::string& s) {
    return s.empty()?nullptr:e->NewStringUTF(s.c_str());
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_iu_radio_usbrecorder_NativeRecorder_start(JNIEnv* env,jobject,jint usb,jint wav,jint sampleRate) {
    std::lock_guard<std::mutex> lock(gMutex);
    if(current) { if(wav>=0)close(wav);return reply(env,"Recording already active"); }
    auto s=std::make_unique<Session>();s->file=wav;
    try {
        if(usb<0||wav<0||sampleRate!=48000)throw std::runtime_error("Invalid FD or sample rate");
        if(libusb_set_option(nullptr,LIBUSB_OPTION_NO_DEVICE_DISCOVERY)!=LIBUSB_SUCCESS)
            throw std::runtime_error("NO_DEVICE_DISCOVERY unsupported");
        s->ctx=uac::uac_context::create();
        int copied=dup(usb);
        if(copied<0)throw std::runtime_error("dup USB FD failed");
        try {s->dev=s->ctx->wrap(copied);}
        catch(...) {close(copied);throw;}
        auto dev=s->dev->get_device();
        auto routes=dev->query_audio_routes(uac::UAC_TERMINAL_ANY,uac::UAC_TERMINAL_USB_STREAMING);
        if(routes.empty())throw std::runtime_error("No UAC1 microphone stream route");
        const auto& iface=dev->get_stream_interface(routes.front().get());
        auto config=iface.query_config_uncompressed(uac::UAC_FORMAT_DATA_PCM,1,sampleRate);
        if(!config)throw std::runtime_error("No 48kHz mono UAC1 PCM configuration");
        if(config->bBitResolution!=16||config->bSubframeSize!=2)
            throw std::runtime_error("Only 16-bit signed PCM supported");
        s->channels=config->bChannelCount;
        if(lseek(wav,0,SEEK_SET)<0)throw std::runtime_error("WAV output must support seek");
        uint8_t h[44];header(h,*s,0);
        if(!writeAll(wav,h,44))throw std::runtime_error("Cannot write WAV header");
        s->writer=std::thread(writeLoop,s.get());
        s->stream=s->dev->start_streaming(iface,*config,
            [p=s.get()](uint8_t* data,unsigned count){enqueue(*p,data,count);},4);
        current=std::move(s);
        return nullptr;
    } catch(const std::exception& ex) {
        std::string error=ex.what();
        try{shutdown(*s);}catch(...){}
        return reply(env,error);
    } catch(...) {
        try{shutdown(*s);}catch(...){}
        return reply(env,"Unknown native UAC error");
    }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_iu_radio_usbrecorder_NativeRecorder_snapshot(JNIEnv* env,jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    jlong v[4]={};
    if(current) {
        v[0]=current->bytes.load();v[1]=current->dropped.load();
        v[2]=current->peak.exchange(0);
        v[3]=current->diskError.load()?-1:
          current->stream?current->stream->check_streaming_error():0;
    }
    jlongArray out=env->NewLongArray(4);
    if(out)env->SetLongArrayRegion(out,0,4,v);
    return out;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_iu_radio_usbrecorder_NativeRecorder_stop(JNIEnv* env,jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if(!current)return nullptr;
    auto s=std::move(current);
    try {
        shutdown(*s);
        if(s->diskError)return reply(env,"PCM write failed");
        if(s->bytes.load()>UINT32_MAX-36)return reply(env,"WAV exceeds RIFF size limit");
        uint8_t h[44];header(h,*s,static_cast<uint32_t>(s->bytes.load()));
        if(pwrite(s->file,h,44,0)!=44)return reply(env,"WAV header finalize failed");
        return nullptr;
    } catch(const std::exception& ex) {return reply(env,ex.what());}
    catch(...) {return reply(env,"Unknown WAV finalize error");}
}
