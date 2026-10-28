package com.rtbishop.look4sat.core.ft4;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Standalone framework instrumentation: no AndroidX runner, microphone or radio. */
public final class Ft4NativeInstrument extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void require(boolean condition,String detail) {
        if(!condition) throw new AssertionError(detail);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();long handle=0;
        try {
            Ft4NativeBindings api=Ft4NativeBindings.INSTANCE;
            String[] capability=api.nativeCapabilities();
            require(capability.length>=8 && "mfsk-core-rust".equals(capability[6]),"Wrong engine");
            result.putString("abi",capability[0]);result.putString("upstream",capability[5]);
            require("".equals(api.nativeSelfTest()),"TX/RX native self-test failed");
            byte[] bytes;
            try(InputStream input=getContext().getAssets().open("wsjtx-ft4.wav")) { bytes=input.readAllBytes(); }
            ByteBuffer wav=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] samples=new float[90000];int cursor=12;
            while(cursor+8<=bytes.length) {
                int size=wav.getInt(cursor+4);
                if(wav.getInt(cursor)==0x61746164) {
                    for(int i=0;i<size/2 && i<samples.length;i++) samples[i]=wav.getShort(cursor+8+i*2)/32768f;
                    break;
                }
                cursor+=8+size+(size&1);
            }
            handle=api.nativeCreate(0);require(handle!=0,"No decoder handle");
            long before=Debug.getNativeHeapAllocatedSize(),start=SystemClock.elapsedRealtimeNanos();
            Ft4NativeDecodeResult[] rows=api.nativeDecode(handle,samples,0,3,3,false,true,1500,"",new String[0],10000);
            result.putLong("decode_ms",(SystemClock.elapsedRealtimeNanos()-start)/1000000);
            result.putLong("native_heap_delta",Debug.getNativeHeapAllocatedSize()-before);
            Set<String> messages=new HashSet<>();Set<Long> hashes=new HashSet<>();
            for(Ft4NativeDecodeResult row:rows) { messages.add(row.getText());hashes.add(row.getMessageHash()); }
            require(messages.contains("CQ RU N9OY EN43"),"Golden CQ missing");
            require(messages.contains("N1TRK N4FKH 569 VA"),"Golden report missing");
            require(rows.length>=14 && hashes.size()>=14,"Missing or collapsed golden rows");
            result.putInt("golden_rows",rows.length);
            require(api.nativeReset(handle),"Reset failed");
            final long cancelled=handle;
            Thread work=new Thread(() -> {
                try { api.nativeDecode(cancelled,samples,0,3,3,false,true,1500,"",new String[0],10000); }
                catch(IllegalStateException expected) { /* Cancel/close can win the start race. */ }
            });
            work.start();Thread.sleep(2);api.nativeCancel(handle);api.nativeDestroy(handle);api.nativeDestroy(handle);handle=0;
            work.join(2000);require(!work.isAlive(),"Cancelled decoder did not stop");
            result.putString("rust_ft4_status","passed");finish(0,result);
        } catch(Throwable error) {
            if(handle!=0) Ft4NativeBindings.INSTANCE.nativeDestroy(handle);
            result.putString("rust_ft4_status","failed");result.putString("error",error.toString());finish(1,result);
        }
    }
}
