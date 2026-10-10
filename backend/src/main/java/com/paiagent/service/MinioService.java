package com.paiagent.service;

import com.paiagent.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
public class MinioService {
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private MinioConfig minioConfig;

    /** 产物的本地可见副本（见 OutputFolder）；测试里可能不存在。 */
    @Autowired(required = false)
    private OutputFolder outputFolder;
    
    /**
     * 确保 bucket 存在
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .build()
        );
        
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .build()
            );
            log.info("创建 MinIO bucket: {}", minioConfig.getBucketName());
        }
    }
    
    /**
     * 上传文件到 MinIO
     * @param objectName 对象名称 (如: audio/xxx.wav)
     * @param inputStream 文件流
     * @param contentType 文件类型
     * @return 文件的公共访问 URL
     */
    public String uploadFile(String objectName, InputStream inputStream, String contentType, long size) throws Exception {
        ensureBucketExists();
        
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
                        .stream(inputStream, size, -1)
                        .contentType(contentType)
                        .build()
        );
        
        log.info("文件上传成功到 MinIO: {}", objectName);
        
        // 返回公共访问 URL
        return minioConfig.getPublicUrl() + "/" + minioConfig.getBucketName() + "/" + objectName;
    }
    
    /**
     * 从 URL 下载文件并上传到 MinIO
     * @param fileUrl 文件 URL
     * @param objectName 对象名称
     * @param contentType 文件类型
     * @return MinIO 公共 URL
     */
    public String uploadFromUrl(String fileUrl, String objectName, String contentType) throws Exception {
        log.info("从 URL 下载文件: {}", fileUrl);

        // 先完整下载到临时文件：既能存一份本地可见副本，也能拿到准确的大小再上传
        // （原来为了取大小要再请求一次 URL，而且大小未知时 putObject 会失败）
        Path temp = Files.createTempFile("paiagent-artifact-", ".tmp");
        try {
            try (InputStream inputStream = new URL(fileUrl).openStream()) {
                Files.copy(inputStream, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (outputFolder != null) {
                outputFolder.saveFile(objectName, temp);
            }
            try (InputStream inputStream = Files.newInputStream(temp)) {
                return uploadFile(objectName, inputStream, contentType, Files.size(temp));
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 从字节数组上传到 MinIO
     * @param data 字节数组
     * @param objectName 对象名称
     * @param contentType 文件类型
     * @return MinIO 公共 URL
     */
    public String uploadFromBytes(byte[] data, String objectName, String contentType) throws Exception {
        log.info("从字节数组上传文件到 MinIO: {}, 大小: {} bytes", objectName, data.length);
        if (outputFolder != null) {
            outputFolder.saveBytes(objectName, data); // 先存本地副本：即使 MinIO 不可用，产物也不会丢
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            return uploadFile(objectName, inputStream, contentType, data.length);
        }
    }
}