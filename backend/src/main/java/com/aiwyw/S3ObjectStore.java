package com.aiwyw;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3兼容协议适配器。应用图片和下载文件都以私有对象保存，不要求Bucket公开。 下载只签发短时GET，签名URL不得写日志；不实现自制签名算法。
 * 使用显式凭据，不从主机的默认AWS角色或环境变量意外获取其他账户权限。
 */
public final class S3ObjectStore {
  private final Storage.Connection config;

  public S3ObjectStore(Storage.Connection config) {
    this.config = config;
  }

  private AwsCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(
        config.sessionToken().isBlank()
            ? AwsBasicCredentials.create(config.accessKey(), config.secretKey())
            : AwsSessionCredentials.create(
                config.accessKey(), config.secretKey(), config.sessionToken()));
  }

  private S3Configuration service() {
    return S3Configuration.builder()
        .pathStyleAccessEnabled(config.pathStyle())
        .chunkedEncodingEnabled(false)
        .build();
  }

  private S3Client client() {
    return S3Client.builder()
        .region(Region.of(config.region()))
        .endpointOverride(URI.create(config.endpoint()))
        .credentialsProvider(credentials())
        .serviceConfiguration(service())
        .requestChecksumCalculation(
            software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(
            software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(45)))
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(2)))
        .build();
  }

  public void check() {
    try (var client = client()) {
      client.headBucket(b -> b.bucket(config.bucket()));
    }
  }

  public void put(String key, Path file, String mime, String sha256) {
    try (var client = client()) {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(config.bucket())
              .key(key)
              .contentType(mime)
              .metadata(Map.of("aiwyw-sha256", sha256))
              .build(),
          RequestBody.fromFile(file));
    }
  }

  /** 仅在发布流程中复制已标记公开的图片。 */
  void copyTo(String key, Path target) {
    try (var client = client()) {
      client.getObject(b -> b.bucket(config.bucket()).key(key), target);
    }
  }

  public void delete(String key) {
    try (var client = client()) {
      client.deleteObject(b -> b.bucket(config.bucket()).key(key));
    }
  }

  public String download(
      String key, String disposition, String mime, int minutes, boolean publiclyCacheable) {
    try (var signer =
        S3Presigner.builder()
            .region(Region.of(config.region()))
            .endpointOverride(URI.create(config.endpoint()))
            .credentialsProvider(credentials())
            .serviceConfiguration(service())
            .build()) {
      var request =
          GetObjectRequest.builder()
              .bucket(config.bucket())
              .key(key)
              .responseContentDisposition(disposition)
              .responseContentType(mime)
              .responseCacheControl(
                  publiclyCacheable ? "public, max-age=86400, immutable" : "no-store")
              .build();
      return signer
          .presignGetObject(
              GetObjectPresignRequest.builder()
                  .signatureDuration(Duration.ofMinutes(minutes))
                  .getObjectRequest(request)
                  .build())
          .url()
          .toExternalForm();
    }
  }
}
