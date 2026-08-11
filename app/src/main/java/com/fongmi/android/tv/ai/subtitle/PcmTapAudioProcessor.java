package com.fongmi.android.tv.ai.subtitle;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.setting.PlayerSetting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
public final class PcmTapAudioProcessor extends BaseAudioProcessor {

    private static final float TARGET_LEVEL = 0.125f;
    private static final float GATE_POWER = 0.000004f;
    private static final float MIN_GAIN = 0.5f;
    private static final float MAX_GAIN = 4.0f;
    private static final float LIMIT = 0.98f;

    public interface Sink {
        void onPcm(float[] mono, int sampleRate);
    }

    private final Sink sink;
    private float loudnessPower = TARGET_LEVEL * TARGET_LEVEL;
    private float loudnessGain = 1.0f;
    private float powerStep = 1.0f;
    private float attackStep = 1.0f;
    private float releaseStep = 1.0f;

    public PcmTapAudioProcessor(Sink sink) {
        this.sink = sink;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw new AudioProcessor.UnhandledAudioFormatException(input);
        powerStep = smoothingStep(input.sampleRate, 10.0f);
        attackStep = smoothingStep(input.sampleRate, 1.0f);
        releaseStep = smoothingStep(input.sampleRate, 5.0f);
        return input;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int bytes = input.remaining();
        if (bytes == 0) return;
        int start = input.position();
        int channels = Math.max(1, inputAudioFormat.channelCount);
        int frames = bytes / (2 * channels);
        if (frames > 0 && sink != null) {
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
        applyEffects(input, start, frames, channels);
        ByteBuffer output = replaceOutputBuffer(bytes);
        output.put(input);
        output.flip();
    }

    private void applyEffects(ByteBuffer input, int start, int frames, int channels) {
        boolean loudness = PlayerSetting.isLoudnessNormalization();
        int mode = channels >= 2 ? PlayerSetting.getAudioChannelMode() : 0;
        if (!loudness && mode == 0) return;
        ByteOrder order = input.order();
        input.order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            int offset = start + frame * channels * 2;
            float left = input.getShort(offset) / 32768.0f;
            float right = channels < 2 ? left : input.getShort(offset + 2) / 32768.0f;
            if (mode == 1 && channels > 2) {
                float mixed = 0.0f;
                for (int channel = 0; channel < channels; channel++) mixed += input.getShort(offset + channel * 2) / 32768.0f;
                left = right = mixed / channels;
            } else if (mode == 2) left = right = (left + right) / 2.0f;
            else if (mode == 3) left = right;
            if (loudness) {
                float gain = updateLoudnessGain(left, right, channels);
                left *= gain;
                right *= gain;
            }
            input.putShort(offset, toPcm16(left));
            if (channels >= 2) input.putShort(offset + 2, toPcm16(right));
            if (mode != 0 && channels > 2) for (int channel = 2; channel < channels; channel++) input.putShort(offset + channel * 2, (short) 0);
        }
        input.order(order);
    }

    private float updateLoudnessGain(float left, float right, int channels) {
        float currentPower = channels < 2 ? left * left : (left * left + right * right) / 2.0f;
        if (currentPower >= GATE_POWER) {
            loudnessPower += (currentPower - loudnessPower) * powerStep;
            float targetGain = TARGET_LEVEL / (float) Math.sqrt(Math.max(loudnessPower, GATE_POWER));
            targetGain = Math.clamp(targetGain, MIN_GAIN, MAX_GAIN);
            float step = targetGain < loudnessGain ? attackStep : releaseStep;
            loudnessGain += (targetGain - loudnessGain) * step;
        }
        return loudnessGain;
    }

    private static short toPcm16(float sample) {
        sample = Math.clamp(sample, -LIMIT, LIMIT);
        return sample <= -1.0f ? Short.MIN_VALUE : (short) Math.round(sample * Short.MAX_VALUE);
    }

    private static float smoothingStep(int sampleRate, float seconds) {
        return 1.0f - (float) Math.exp(-1.0f / (Math.max(1, sampleRate) * seconds));
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        resetLoudness();
    }

    @Override
    protected void onReset() {
        resetLoudness();
    }

    private void resetLoudness() {
        loudnessPower = TARGET_LEVEL * TARGET_LEVEL;
        loudnessGain = 1.0f;
    }
}
