package com.aiwyw;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 统一错误边界。业务拒绝保留状态码，未知错误对外脱敏，不将数据库语句、凭据或堆栈返回浏览器。 */
@RestControllerAdvice
public class Api {
  public static class Failure extends RuntimeException {
    public final int status;

    public Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  @ExceptionHandler(Failure.class)
  /** 已知业务错误返回可读说明；不得把敏感资料拼进异常消息。 */
  public ResponseEntity<?> business(Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  /** 绑定与JSON格式错误统一为400，避免框架异常泄漏请求内容。 */
  public ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "输入格式不正确，请检查表单"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  /** 图片上传超出容器上限时返回413，业务入口仍须另做内容校验。 */
  public ResponseEntity<?> large(Exception e) {
    return ResponseEntity.status(413).body(Map.of("message", "上传内容超过系统请求上限"));
  }

  @ExceptionHandler(Exception.class)
  /** 只记录异常类别；调查需结合审计与环境日志，不直接打印可能含密码的异常消息。 */
  public ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Api.class)
        .error("Request failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.internalServerError().body(Map.of("message", "操作未完成，请稍后重试；未保存的修改请保留"));
  }
}
