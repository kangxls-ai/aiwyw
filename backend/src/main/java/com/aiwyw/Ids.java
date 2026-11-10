package com.aiwyw;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.UUID;

/** 新业务ID以小写32进制无损表示UUID；数据库和对外接口兼容已有带横线的ID。 */
final class Ids {
  static final String REGEX = "(?:[0-9a-v]{26}|[0-9a-f-]{36})";

  private Ids() {}

  static String next() {
    UUID uuid = UUID.randomUUID();
    byte[] bytes = ByteBuffer.allocate(16)
        .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    String encoded = new BigInteger(1, bytes).toString(32);
    return "0".repeat(26 - encoded.length()) + encoded;
  }
}
