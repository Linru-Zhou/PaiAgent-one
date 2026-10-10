package com.paiagent.engine.executor.impl;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Qwen-Audio（qwen-audio-3.x-tts-flash）接入的无网络部分。
 * 真正的合成需要百炼的 API Key，这里没有覆盖；见 README/文档里的"需要人工验证"说明。
 */
class QwenAudioTtsSupportTest {

    @Test
    void recognisesTheQwenAudioFamilyButNotQwen3Tts() {
        assertTrue(QwenAudioTtsSupport.isQwenAudioModel("qwen-audio-3.1-tts-flash"));
        assertTrue(QwenAudioTtsSupport.isQwenAudioModel("Qwen-Audio-3.0-TTS-Flash"));
        assertFalse(QwenAudioTtsSupport.isQwenAudioModel("qwen3-tts-flash"));
        assertFalse(QwenAudioTtsSupport.isQwenAudioModel("stepaudio-2.5-tts"));
        assertFalse(QwenAudioTtsSupport.isQwenAudioModel(null));
    }

    @Test
    void legacyQwen3VoicesAreReplacedByTheModelsDefaultVoice() {
        assertEquals("longanhuan_v3.1", QwenAudioTtsSupport.resolveVoice("qwen-audio-3.1-tts-flash", "Cherry"));
        assertEquals("longanhuan_v3.1", QwenAudioTtsSupport.resolveVoice("qwen-audio-3.1-tts-flash", ""));
        assertEquals("longanhuan_v3.6", QwenAudioTtsSupport.resolveVoice("qwen-audio-3.0-tts-flash", null));
        assertEquals("Emily_v3.1", QwenAudioTtsSupport.resolveVoice("qwen-audio-3.1-tts-flash", " Emily_v3.1 "), "音色 ID 区分大小写，必须原样保留");
    }

    @Test
    void normalizesTheApiAddressToAWebsocketUrl() {
        assertNull(QwenAudioTtsSupport.normalizeWebsocketUrl(null));
        assertNull(QwenAudioTtsSupport.normalizeWebsocketUrl("  "));
        // 官方文档里北京地域带业务空间 ID 的写法：原样保留
        assertEquals("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                QwenAudioTtsSupport.normalizeWebsocketUrl("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference/"));
        // 只写了域名：补上路径
        assertEquals("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                QwenAudioTtsSupport.normalizeWebsocketUrl("wss://ws-123.cn-beijing.maas.aliyuncs.com"));
        // 兼容 HTTP 形式的 DashScope 地址
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                QwenAudioTtsSupport.normalizeWebsocketUrl("https://dashscope.aliyuncs.com/api/v1"));
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                QwenAudioTtsSupport.normalizeWebsocketUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference",
                QwenAudioTtsSupport.normalizeWebsocketUrl("https://dashscope-intl.aliyuncs.com/"));
    }

    @Test
    void mapsSampleRatesToWavFormatsAndFallsBackTo24k() {
        assertEquals(SpeechSynthesisAudioFormat.WAV_24000HZ_MONO_16BIT, QwenAudioTtsSupport.wavFormat(24000));
        assertEquals(SpeechSynthesisAudioFormat.WAV_16000HZ_MONO_16BIT, QwenAudioTtsSupport.wavFormat(16000));
        assertEquals(SpeechSynthesisAudioFormat.WAV_24000HZ_MONO_16BIT, QwenAudioTtsSupport.wavFormat(12345));
        assertEquals(24000, QwenAudioTtsSupport.sampleRateOf(12345));
        assertEquals(44100, QwenAudioTtsSupport.sampleRateOf(44100));
    }

    @Test
    void barePcmGetsAWavHeaderAndRealWavIsLeftAlone() {
        byte[] pcm = new byte[]{1, 2, 3, 4};
        byte[] wav = QwenAudioTtsSupport.ensureWav(pcm, 24000);

        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(24000, header.getInt(24), "采样率");
        assertEquals(pcm.length, header.getInt(40), "data 块长度");
        assertSame(wav, QwenAudioTtsSupport.ensureWav(wav, 24000), "已经是 WAV 的不再包装");
    }

    @Test
    void ttsNodeUsesTheQwenAudioDefaultsInsteadOfQwen3Ones() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "qwen");
        data.put("model", "qwen-audio-3.1-tts-flash");
        data.put("apiKey", "test-key");
        data.put("apiUrl", "https://dashscope.aliyuncs.com/api/v1");
        data.put("voice", "Cherry"); // 前端面板默认给 qwen 填的就是 Cherry

        Object config = ReflectionTestUtils.invokeMethod(executor, "resolveConfig", data);

        assertEquals("qwen-audio-3.1-tts-flash", ReflectionTestUtils.invokeMethod(config, "model"), "含 tts 的模型名不应被换回 qwen3-tts-flash");
        assertEquals("longanhuan_v3.1", ReflectionTestUtils.invokeMethod(config, "voice"));
        assertEquals(QwenAudioTtsSupport.MAX_INPUT_LENGTH, (int) ReflectionTestUtils.invokeMethod(config, "maxInputLength"));

        data.put("model", "qwen3-tts-flash");
        Object legacy = ReflectionTestUtils.invokeMethod(executor, "resolveConfig", data);
        assertEquals("Cherry", ReflectionTestUtils.invokeMethod(legacy, "voice"), "qwen3-tts-flash 的行为不变");
        assertEquals(400, (int) ReflectionTestUtils.invokeMethod(legacy, "maxInputLength"));
    }
}
