package com.rtbishop.look4sat.core.ft4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exercises production JNI and Kotlin DTOs on a host JVM; this is not an ART/device test. */
public final class JttyJniHostTest {
    private static void check(boolean ok,String detail) { if(!ok) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        JttyNativeBindings api=JttyNativeBindings.INSTANCE;
        check(api.frames("WB9XYZ 599 123")==2,"Message packing");
        check(api.frames("中文")==-1 && api.frames("A".repeat(81))==-1,"Invalid text");
        for(int rate:new int[]{12000,24000,48000}) {
            float[] wave=api.generate("WB9XYZ 599 123",1500,rate);
            check(wave!=null && wave.length==rate*3776/1000,"Waveform length");
        }
        byte[] data=Files.readAllBytes(Path.of(args[0]));ByteBuffer wav=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short[] pcm=null;int at=12;
        while(at+8<=data.length) {
            int n=wav.getInt(at+4);
            if(wav.getInt(at)==0x61746164) { pcm=new short[n/2];for(int i=0;i<pcm.length;i++) pcm[i]=wav.getShort(at+8+i*2);break; }
            at+=8+n+(n&1);
        }
        check(pcm!=null,"WAV data");long id=api.create();check(id!=0,"Create");boolean found=false;
        for(int i=0;i<pcm.length;i+=512) {
            JttyNativeMessage[] rows=api.push(id,Arrays.copyOfRange(pcm,i,Math.min(pcm.length,i+512)),1500,50);
            check(rows!=null,"Push");
            for(JttyNativeMessage row:rows) if(row.getText().equals("WB9XYZ 599 123") && row.getKind()==1) {
                check(Math.abs(row.getFrequencyHz()-1500)<5,"Frequency");found=true;
            }
        }
        check(found,"Independent WSJT-X two-frame message");api.cancel(id);check(api.push(id,new short[512],1500,50)==null,"Cancel");api.close(id);api.close(id);
        System.out.println("JTTY_HOST_JNI_PASSED independent_RX=true TX_rates=3 cancel=true double_close=true");
    }
}
