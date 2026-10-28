package com.rtbishop.look4sat.core.ft4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Runs the actual production JNI bridge and Kotlin DTOs on a host JVM, not an ART substitute. */
public final class Ft4JniHostTest {
    private static void check(boolean ok,String detail) { if(!ok) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        Ft4NativeBindings api=Ft4NativeBindings.INSTANCE;
        check("mfsk-core-rust".equals(api.nativeCapabilities()[6]),"Engine identity");
        check("".equals(api.nativeSelfTest()),"Native TX/RX self-test");
        byte[] data=Files.readAllBytes(Path.of(args[0]));ByteBuffer wav=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples=new float[90000];int at=12;
        while(at+8<=data.length) {
            int size=wav.getInt(at+4);
            if(wav.getInt(at)==0x61746164) { for(int i=0;i<size/2;i++) samples[i]=wav.getShort(at+8+i*2)/32768f;break; }
            at+=8+size+(size&1);
        }
        long handle=api.nativeCreate(0);
        Ft4NativeDecodeResult[] rows=api.nativeDecode(handle,samples,0,3,3,false,true,1500,"",new String[0],10000);
        Set<String> text=new HashSet<>();Set<Long> hashes=new HashSet<>();
        for(Ft4NativeDecodeResult row:rows) { text.add(row.getText());hashes.add(row.getMessageHash()); }
        check(text.contains("CQ RU N9OY EN43") && text.contains("N1TRK N4FKH 569 VA"),"Independent golden messages");
        check(hashes.size()>=14,"Distinct payload identities");
        check(api.nativeReset(handle),"Context reset");api.nativeCancel(handle);
        boolean cancelled=false;
        try { api.nativeDecode(handle,samples,0,3,3,false,true,1500,"",new String[0],10000); }
        catch(IllegalStateException expected) { cancelled=true; }
        check(cancelled,"Cancelled handle accepted a decode");
        check(api.nativeReset(handle),"Reset after cancellation");api.nativeDestroy(handle);api.nativeDestroy(handle);
        System.out.println("HOST_JNI_PASSED independent_rows="+rows.length+" native_self_test=true reset=true cancel=true double_close=true");
    }
}
