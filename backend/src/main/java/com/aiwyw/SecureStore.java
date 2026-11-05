package com.aiwyw;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 持久密钥与密码学工具。master.key是恢复数据库凭据、敏感配置和MFA的根；必须与数据库成套备份，丢失后不能随机重建来恢复旧密文。 */
@Component
public class SecureStore {
  private final SecureRandom random = new SecureRandom();
  private final byte[] key;
  public final Path root;

  public SecureStore(@Value("${aiwyw.data-dir:./data}") String directory) throws Exception {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
    if (Files.getFileStore(root).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    else {
      var acl = Files.getFileAttributeView(root, AclFileAttributeView.class);
      if (acl != null) {
        var user =
            root.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        acl.setAcl(
            List.of(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(user)
                    .setFlags(
                        java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                        java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
      }
    }
    Path keyFile = root.resolve("master.key");
    if (!Files.exists(keyFile)) {
      byte[] generated = new byte[32];
      random.nextBytes(generated);
      try {
        Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW);
        protect(keyFile);
      } finally {
        Arrays.fill(generated, (byte) 0);
      }
    }
    key = Files.readAllBytes(keyFile);
    if (key.length != 32) throw new IllegalStateException("Invalid master key");
  }

  /** 将文件权限限制为服务账户；Windows使用ACL，POSIX使用0600。 */
  public void protect(Path file) throws Exception {
    if (Files.getFileStore(file).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    else {
      var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
      if (view != null) {
        var principal =
            file.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        view.setAcl(
            List.of(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(principal)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
      }
    }
  }

  /** 生成32字节密码学随机令牌；只在必要响应或HttpOnly Cookie中返回原文。 */
  public String token() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** 从主密钥派生本地H2密码；更换主密钥也会改变本地库连接密码。 */
  public String localPassword() {
    return digest(Base64.getEncoder().encodeToString(key) + ":local-db");
  }

  /** SHA256用于高熵随机令牌摘要，不用于直接保存用户密码。 */
  public String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 每次独立随机96位IV，AES-GCM同时保护机密性与完整性；不能复用IV。 */
  public String encrypt(String text) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] data = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(ByteBuffer.allocate(iv.length + data.length).put(iv).put(data).array());
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  /** 密文校验失败应停止当前操作，不回退明文或空值以免错误覆盖。 */
  public String decrypt(String text) {
    try {
      byte[] b = Base64.getDecoder().decode(text);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(b, 12)));
      return new String(c.doFinal(b, 12, b.length - 12), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }

  /** 独立随机盐和PBKDF2-SHA256迭代派生；数据库不保存可解密密码。 */
  public String password(String password) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return "210000:"
        + Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt));
  }

  /** 清除密码派生中间规范；算法变更必须兼容旧密码格式并设计迁移。 */
  private byte[] derive(String password, byte[] salt) {
    var spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  /** 使用常量时间比较摘要，解析失败视为验证不通过。 */
  public boolean verifyPassword(String password, String encoded) {
    try {
      String[] p = encoded.split(":");
      return MessageDigest.isEqual(
          derive(password, Base64.getDecoder().decode(p[1])), Base64.getDecoder().decode(p[2]));
    } catch (Exception e) {
      return false;
    }
  }

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  /** 生成验证器共享密钥；只有绑定阶段授权页面可显示。 */
  public String newTotpSecret() {
    byte[] b = new byte[20];
    random.nextBytes(b);
    StringBuilder s = new StringBuilder();
    int bits = 0, buffer = 0;
    for (byte x : b) {
      buffer = (buffer << 8) | (x & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        s.append(ALPHABET.charAt((buffer >> bits) & 31));
      }
    }
    return s.toString();
  }

  /** 按30秒步长生成6位TOTP；只用于核验，不能将验证码写日志。 */
  public String totp(String secret, long step) {
    try {
      byte[] keyBytes = new byte[secret.length() * 5 / 8];
      int buffer = 0, bits = 0, n = 0;
      for (char ch : secret.toCharArray()) {
        int x = ALPHABET.indexOf(ch);
        if (x < 0) throw new IllegalArgumentException();
        buffer = (buffer << 5) | x;
        bits += 5;
        if (bits >= 8) {
          bits -= 8;
          keyBytes[n++] = (byte) (buffer >> bits);
        }
      }
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(keyBytes, "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int o = h[h.length - 1] & 15;
      int code =
          ((h[o] & 127) << 24)
              | ((h[o + 1] & 255) << 16)
              | ((h[o + 2] & 255) << 8)
              | (h[o + 3] & 255);
      return String.format(Locale.ROOT, "%06d", code % 1000000);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 允许前后一个时间窗口，但必须严格大于最近已使用步长以阻止重放。 */
  public long validTotpStep(String secret, String code, long lastStep) {
    if (code == null || !code.matches("\\d{6}")) return -1;
    long now = System.currentTimeMillis() / 30000;
    for (long step = now - 1; step <= now + 1; step++)
      if (step > lastStep
          && MessageDigest.isEqual(
              totp(secret, step).getBytes(StandardCharsets.US_ASCII),
              code.getBytes(StandardCharsets.US_ASCII))) return step;
    return -1;
  }
}
