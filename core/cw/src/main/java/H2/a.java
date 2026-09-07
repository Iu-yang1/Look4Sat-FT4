package H2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Morse Expert 原始 8 kHz 音频分帧器。
 * 麦克风采集由应用级 SharedAudioHub 统一负责，本类只保留原解码核心需要的 85 样本分帧。
 */
public final class a {
    public final int f;
    public final AtomicBoolean f598i;
    public final float[] f600k;
    public b f601l;
    private int buffered;

    public a() {
        this.f = b.f604C;
        this.f598i = new AtomicBoolean(false);
        this.f600k = new float[this.f];
    }

    public synchronized void a(float[] samples) {
        if (!this.f598i.get() || samples == null || samples.length == 0) {
            return;
        }
        int sourceOffset = 0;
        while (sourceOffset < samples.length) {
            int copyCount = Math.min(this.f - this.buffered, samples.length - sourceOffset);
            System.arraycopy(samples, sourceOffset, this.f600k, this.buffered, copyCount);
            sourceOffset += copyCount;
            this.buffered += copyCount;
            if (this.buffered == this.f) {
                b decoder = this.f601l;
                if (decoder != null) {
                    decoder.a(this.f600k);
                }
                this.buffered = 0;
            }
        }
    }

    public synchronized void b() {
        this.buffered = 0;
    }
}
