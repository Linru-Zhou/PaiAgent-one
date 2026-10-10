package com.paiagent.engine.executor.impl;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Qwen-Audio 系列语音合成（qwen-audio-3.x-tts-flash）的纯函数辅助。
 *
 * 这个系列和 qwen3-tts-flash 不是同一套接口：
 *  - qwen3-tts-flash：HTTP，MultiModalConversation，音色是 Cherry / Serena 这类枚举，返回一个音频 URL；
 *  - qwen-audio-3.x-tts-flash：WebSocket，ttsv2.SpeechSynthesizer，音色是 "longanhuan_v3.1" 这类字符串 ID，
 *    直接返回音频字节。
 * 所以不能只改模型名，节点里要按模型名选不同的调用路径。这里放不依赖网络的部分，便于单元测试。
 */
final class QwenAudioTtsSupport {

    /** 单次合成的文本上限：官方没有给出明确限制，保守取值，超出的文本由节点按标点切片后依次合成再合并。 */
    static final int MAX_INPUT_LENGTH = 1000;
    static final int MAX_INPUT_BYTES = 3000;

    static final String WS_PATH = "/api-ws/v1/inference";

    private QwenAudioTtsSupport() {
    }

    static boolean isQwenAudioModel(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith("qwen-audio");
    }

    /** 默认音色：3.1 用多语种女声 longanhuan_v3.1（含中文普通话和方言）；3.0 系列的示例音色是 longanhuan_v3.6。 */
    static String defaultVoice(String model) {
        return model != null && model.contains("3.0") ? "longanhuan_v3.6" : "longanhuan_v3.1";
    }

    /**
     * 音色必须是该系列的字符串 ID（形如 xxx_v3.1）。节点配置里可能还留着 qwen3-tts 的 "Cherry" 之类音色，
     * 直接传过去服务端会报 InvalidParameter，这里换成默认音色。
     */
    static String resolveVoice(String model, String requested) {
        if (requested == null || requested.isBlank() || !requested.contains("_v")) {
            return defaultVoice(model);
        }
        return requested.trim();
    }

    /**
     * 把节点里填的"API 地址"整理成 WebSocket 地址。
     * 官方文档里北京地域的地址带业务空间 ID：wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference，
     * 这个值只能由用户自己填；为空返回 null，使用 SDK 默认地址；
     * 也兼容把 HTTP 形式的地址（https://dashscope.aliyuncs.com/api/v1 或 compatible-mode）转成对应的 wss 地址。
     */
    static String normalizeWebsocketUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        String url = apiUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("https://")) {
            url = "wss://" + url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            url = "ws://" + url.substring("http://".length());
        }
        if (url.contains("/api-ws/")) {
            return url;
        }
        url = url.replace("/compatible-mode/v1", "")
                .replace("/compatible-mode", "")
                .replace("/api/v1", "");
        return url + WS_PATH;
    }

    static SpeechSynthesisAudioFormat wavFormat(int sampleRate) {
        return switch (sampleRate) {
            case 8000 -> SpeechSynthesisAudioFormat.WAV_8000HZ_MONO_16BIT;
            case 16000 -> SpeechSynthesisAudioFormat.WAV_16000HZ_MONO_16BIT;
            case 22050 -> SpeechSynthesisAudioFormat.WAV_22050HZ_MONO_16BIT;
            case 44100 -> SpeechSynthesisAudioFormat.WAV_44100HZ_MONO_16BIT;
            case 48000 -> SpeechSynthesisAudioFormat.WAV_48000HZ_MONO_16BIT;
            default -> SpeechSynthesisAudioFormat.WAV_24000HZ_MONO_16BIT;
        };
    }

    static int sampleRateOf(int requested) {
        return switch (requested) {
            case 8000, 16000, 22050, 44100, 48000 -> requested;
            default -> 24000;
        };
    }

    /** 节点后面要把多个分片合并成一个 WAV；万一服务端返回的是不带头的裸 PCM，就自己补上 44 字节的 WAV 头。 */
    static byte[] ensureWav(byte[] audio, int sampleRate) {
        if (audio.length >= 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
            return audio;
        }
        int channels = 1;
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        ByteBuffer buffer = ByteBuffer.allocate(44 + audio.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + audio.length)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(sampleRate).putInt(byteRate)
                .putShort((short) (channels * bits / 8)).putShort((short) bits)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(audio.length).put(audio);
        return buffer.array();
    }
}
