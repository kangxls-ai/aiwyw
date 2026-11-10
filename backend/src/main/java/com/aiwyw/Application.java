package com.aiwyw;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 应用启动入口。仅放进程必需默认值；运营配置统一由安装向导与配置中心维护，不向用户要求手改YAML。 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class Application {
  /** 启动Spring上下文；限制异常明文与上传体积，业务层必须继续做权限、内容和尺寸校验。 */
  public static void main(String[] args) {
    var app = new SpringApplication(Application.class);
    app.setDefaultProperties(
        Map.of(
            "server.port", "18080",
            "spring.servlet.multipart.max-file-size", "1024MB",
            "spring.servlet.multipart.max-request-size", "1025MB",
            "server.error.include-message", "never",
            "server.error.include-stacktrace", "never"));
    var context = app.run(args);
    // Windows 后台实例使用同目录内的 PID 请求优雅关机，无网络停机接口。
    if (Boolean.getBoolean("aiwyw.windows-control")) {
      var control = new Thread(() -> {
        var request = java.nio.file.Path.of("run", "stop.request");
        try {
          while (context.isActive()) {
            if (java.nio.file.Files.isRegularFile(request)
                && java.nio.file.Files.readString(request).trim()
                    .equals(Long.toString(ProcessHandle.current().pid()))) {
              java.nio.file.Files.deleteIfExists(request);
              context.close();
              return;
            }
            Thread.sleep(500);
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } catch (java.io.IOException failed) {
          org.slf4j.LoggerFactory.getLogger(Application.class).warn("Could not read Windows stop request");
        }
      }, "aiwyw-windows-control");
      control.setDaemon(true);
      control.start();
    }
    // 仅本地受管启动启用：私有stdin管道请求优雅退出，不开放网络停机接口。
    // 正式部署不传此JVM属性，行为与普通Spring Boot应用相同。
    if (Boolean.getBoolean("aiwyw.local-supervisor")) {
      Thread control =
          new Thread(
              () -> {
                try (var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                            System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    if ("AIWYW_STOP".equals(line)) break;
                  }
                } catch (java.io.IOException ignored) {
                  // 父进程异常退出、管道关闭同样关闭上下文，避免孤儿后端继续占用数据库。
                } finally {
                  context.close();
                }
              },
              "aiwyw-local-control");
      control.setDaemon(true);
      control.start();
    }
  }
}
