package com.aiwyw;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Redis只作公开资料缓存和前置限流；数据库仍是计量事实。连接/命令失败熔断，不吞掉业务429。 */
@Service
public class OptionalRedis {
  final Settings settings;
  final SecureStore crypto;
  RedisClient client;
  StatefulRedisConnection<String, String> connection;
  String signature = "";
  long retryAt;

  OptionalRedis(Settings settings, SecureStore crypto) {
    this.settings = settings;
    this.crypto = crypto;
  }

  private synchronized boolean ready() {
    if (!Boolean.parseBoolean(settings.value("redis.enabled"))) {
      close();
      return false;
    }
    String host = settings.value("redis.host"), password = settings.value("redis.password");
    int port = settings.integer("redis.port"),
        database = settings.integer("redis.database"),
        timeout = settings.integer("redis.timeout_ms");
    boolean tls = Boolean.parseBoolean(settings.value("redis.tls"));
    String next =
        crypto.digest(
            host + ":" + port + ":" + database + ":" + tls + ":" + timeout + ":" + password);
    if (!next.equals(signature)) {
      close();
      signature = next;
      retryAt = 0;
    }
    if (connection != null && connection.isOpen()) return true;
    if (System.currentTimeMillis() < retryAt) return false;
    try {
      var builder =
          RedisURI.Builder.redis(host, port)
              .withDatabase(database)
              .withSsl(tls)
              .withTimeout(Duration.ofMillis(timeout));
      if (!password.isBlank()) builder.withPassword(password.toCharArray());
      client = RedisClient.create(builder.build());
      client.setOptions(
          ClientOptions.builder()
              .autoReconnect(false)
              .socketOptions(
                  SocketOptions.builder().connectTimeout(Duration.ofMillis(timeout)).build())
              .build());
      client.setDefaultTimeout(Duration.ofMillis(timeout));
      connection = client.connect();
      return true;
    } catch (Exception unavailable) {
      failed();
      return false;
    }
  }

  private void failed() {
    close();
    retryAt = System.currentTimeMillis() + settings.integer("redis.retry_seconds") * 1000L;
  }

  public synchronized String get(String key) {
    if (!ready()) return null;
    try {
      return connection.sync().get("aiwyw:" + key);
    } catch (Exception e) {
      failed();
      return null;
    }
  }

  public synchronized void put(String key, String value) {
    if (!ready()) return;
    try {
      connection.sync().setex("aiwyw:" + key, 30, value);
    } catch (Exception e) {
      failed();
    }
  }

  public synchronized void remove(String key) {
    if (!ready()) return;
    try {
      connection.sync().del("aiwyw:" + key);
    } catch (Exception e) {
      failed();
    }
  }

  public synchronized void limit(String key, int maximum) {
    if (!ready()) return;
    try {
      Long count =
          connection
              .sync()
              .eval(
                  "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                      + " redis.call('EXPIRE',KEYS[1],120) end; return n",
                  ScriptOutputType.INTEGER,
                  new String[] {
                    "aiwyw:rate:" + crypto.digest(key) + ":" + (System.currentTimeMillis() / 60000)
                  });
      if (count > maximum) throw new Api.Failure(429, "API每分钟调用次数达到上限");
    } catch (Api.Failure business) {
      throw business;
    } catch (Exception e) {
      failed();
    }
  }

  public synchronized void check() {
    if (!Boolean.parseBoolean(settings.value("redis.enabled")))
      throw new Api.Failure(400, "请先在配置管理启用并填写Redis连接");
    retryAt = 0;
    if (!ready()) throw new Api.Failure(503, "Redis连接失败，请核对后台配置、网络和证书");
    try {
      if (!connection.sync().ping().equals("PONG")) throw new IllegalStateException();
    } catch (Exception e) {
      failed();
      throw new Api.Failure(503, "Redis检查失败");
    }
  }

  @jakarta.annotation.PreDestroy
  public synchronized void close() {
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception ignored) {
      }
      connection = null;
    }
    if (client != null) {
      try {
        client.shutdown(Duration.ZERO, Duration.ofMillis(200));
      } catch (Exception ignored) {
      }
      client = null;
    }
  }
}
