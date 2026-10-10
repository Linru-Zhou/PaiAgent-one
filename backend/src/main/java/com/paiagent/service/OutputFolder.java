package com.paiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 产物的本地可见文件夹。
 *
 * 音频 / 图片 / 视频会上传到 MinIO，但 MinIO 把对象存成自己的内部格式
 * （每个对象是一个目录，里面是 xl.meta 和分片文件，另有隐藏的 .minio.sys），在 Finder 里根本没法直接打开。
 * 所以每个产物上传的同时，再原样存一份到这个普通文件夹里，目录结构与 MinIO 的对象名一致（audio/xxx.wav 等），
 * 每次执行的结果还会写成 executions/exec-<编号>/result.json。
 *
 * 由 paiagent.output.dir（环境变量 PAIAGENT_OUTPUT_DIR）配置；留空则关闭。本地副本是"尽力而为"：
 * 保存失败只记日志，不影响工作流本身。
 */
@Slf4j
@Component
public class OutputFolder {

    private final Path root;

    public OutputFolder(@Value("${paiagent.output.dir:}") String dir) {
        Path resolved = null;
        if (dir != null && !dir.isBlank()) {
            try {
                resolved = Path.of(dir.trim()).toAbsolutePath().normalize();
                Files.createDirectories(resolved);
                log.info("产物输出目录（可见文件夹）: {}", resolved);
            } catch (Exception e) {
                log.warn("无法创建产物输出目录 {}，本地副本功能关闭: {}", dir, e.getMessage());
                resolved = null;
            }
        }
        this.root = resolved;
    }

    public boolean enabled() {
        return root != null;
    }

    public Path root() {
        return root;
    }

    /** 对象名（如 audio/xxx.wav）对应的本地路径；拒绝跳出输出目录的写法（../）。 */
    public Path pathFor(String objectName) {
        if (root == null || objectName == null || objectName.isBlank()) {
            return null;
        }
        Path target = root.resolve(objectName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            log.warn("拒绝保存到输出目录之外的路径: {}", objectName);
            return null;
        }
        return target;
    }

    public Path executionDir(Long executionId) {
        return executionId == null ? null : pathFor("executions/exec-" + executionId);
    }

    /** @return 保存后的路径；关闭或失败返回 null */
    public Path saveBytes(String objectName, byte[] data) {
        Path target = pathFor(objectName);
        if (target == null) {
            return null;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            log.info("产物已保存到本地: {}", target);
            return target;
        } catch (IOException e) {
            log.warn("保存产物到本地失败 {}: {}", target, e.getMessage());
            return null;
        }
    }

    public Path saveFile(String objectName, Path source) {
        Path target = pathFor(objectName);
        if (target == null) {
            return null;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("产物已保存到本地: {}", target);
            return target;
        } catch (IOException e) {
            log.warn("保存产物到本地失败 {}: {}", target, e.getMessage());
            return null;
        }
    }
}
