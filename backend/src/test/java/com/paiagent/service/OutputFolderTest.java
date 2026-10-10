package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.MinioConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 产物的本地可见副本与每次执行的 result.json。 */
class OutputFolderTest {

    @TempDir
    Path tmp;

    @Test
    void savesArtifactsUnderTheirObjectNameAndCreatesTheFolder() throws Exception {
        Path root = tmp.resolve("outputs"); // 目录还不存在
        OutputFolder folder = new OutputFolder(root.toString());

        assertTrue(folder.enabled());
        Path saved = folder.saveBytes("audio/audio_1.wav", new byte[]{1, 2, 3});

        assertEquals(root.resolve("audio/audio_1.wav"), saved);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(saved));
        Path source = Files.write(tmp.resolve("src.bin"), new byte[]{9});
        assertTrue(Files.exists(folder.saveFile("videos/v.mp4", source)));
    }

    @Test
    void refusesPathsThatEscapeTheOutputFolder() {
        OutputFolder folder = new OutputFolder(tmp.resolve("outputs").toString());
        assertNull(folder.pathFor("../evil.txt"));
        assertNull(folder.pathFor("audio/../../evil.txt"));
        assertNull(folder.saveBytes("../evil.txt", new byte[]{1}));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    void blankDirectoryMeansDisabledAndNothingFailsWhenSaving() {
        OutputFolder folder = new OutputFolder("  ");
        assertFalse(folder.enabled());
        assertNull(folder.saveBytes("audio/a.wav", new byte[]{1}));
        assertNull(folder.executionDir(1L));
    }

    @Test
    void executionResultListsNodesAndMapsMinioUrlsToLocalFiles() throws Exception {
        OutputFolder folder = new OutputFolder(tmp.resolve("outputs").toString());
        folder.saveBytes("audio/audio_1.wav", new byte[]{1});
        MinioConfig minio = new MinioConfig();
        minio.setPublicUrl("http://localhost:9000");
        minio.setBucketName("paiagent");
        ExecutionResultExporter exporter = new ExecutionResultExporter();
        ReflectionTestUtils.setField(exporter, "outputFolder", folder);
        ReflectionTestUtils.setField(exporter, "minioConfig", minio);

        String nodes = "[{\"nodeId\":\"in\",\"nodeName\":\"入口\",\"status\":\"SUCCESS\",\"duration\":3,\"output\":\"{\\\"input\\\":\\\"hi\\\"}\"},"
                + "{\"nodeId\":\"tts\",\"nodeName\":\"配音\",\"status\":\"SUCCESS\",\"duration\":900,"
                + "\"output\":\"{\\\"audioUrl\\\":\\\"http://localhost:9000/paiagent/audio/audio_1.wav\\\",\\\"missing\\\":\\\"http://localhost:9000/paiagent/audio/none.wav\\\"}\"}]";
        Path file = exporter.export(42L, 7L, "SUCCESS", "hi", "{\"output\":\"done\"}", nodes, null, 1000);

        assertEquals(tmp.resolve("outputs/executions/exec-42/result.json"), file);
        JSONObject json = JSON.parseObject(Files.readString(file));
        assertEquals("SUCCESS", json.getString("status"));
        assertEquals("done", json.getJSONObject("output").getString("output"), "输出是对象，不是被转义的字符串");
        assertEquals(2, json.getJSONArray("nodes").size());
        var artifacts = json.getJSONArray("artifacts");
        assertEquals(2, artifacts.size());
        assertEquals(tmp.resolve("outputs/audio/audio_1.wav").toString(), artifacts.getJSONObject(0).getString("localPath"));
        assertNull(artifacts.getJSONObject(1).getString("localPath"), "文件不存在就不假装有本地路径");
        assertFalse(Files.readString(file).contains("apiKey"));
    }
}
