package com.fongmi.android.tv.ai.subtitle;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
public final class PcmTapAudioProcessor extends BaseAudioProcessor {
    public interface Sink {
        void onPcm(float[] mono, int sampleRate);
    }

    private final Sink sink;

    public PcmTapAudioProcessor(Sink sink) {
        this.sink = sink;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw new AudioProcessor.UnhandledAudioFormatException(input);
        return input;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int bytes = input.remaining();
        if (bytes == 0) return;
        int start = input.position();
        int channels = Math.max(1, inputAudioFormat.channelCount);
        int frames = bytes / (2 * channels);
        if (frames > 0) {
            float[] mono = new float[frames];
            ByteOrder oldOrder = input.order();
            input.order(ByteOrder.LITTLE_ENDIAN);
            int offset = start;
            for (int frame = 0; frame < frames; frame++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += input.getShort(offset);
                    offset += 2;
                }
                mono[frame] = (sum / (float) channels) / 32768.0f;
            }
            input.order(oldOrder);
            sink.onPcm(mono, inputAudioFormat.sampleRate);
        }
        ByteBuffer output = replaceOutputBuffer(bytes);
        output.put(input);
        output.flip();
    }
}
