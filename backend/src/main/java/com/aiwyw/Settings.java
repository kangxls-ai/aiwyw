package com.aiwyw;

import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 带元数据的配置中心。类型、范围、公开白名单与敏感标记都由服务端定义；整批保存采用一个事务和逐字段乐观锁。 */
@Service
public class Settings {
  public record Definition(
      String key,
      String group,
      String label,
      String type,
      String initial,
      boolean secret,
      boolean publicValue,
      String help,
      int min,
      int max,
      List<String> options) {}

  public record Entry(Definition definition, String value, boolean configured, long revision) {}

  public record Change(String key, String value, long revision, boolean clear) {}

  public static final List<Definition> DEFINITIONS = new ArrayList<>();

  static {
    bool("seo.allow_indexing", "SEO与内容", "允许搜索引擎索引", true, "控制robots和站点地图，不能保证搜索引擎收录或AI引用");
    number(
        "security.password_change_hour_limit",
        "账号与安全",
        "每账户每小时修改密码尝试上限",
        10,
        1,
        100,
        "校验失败也计入次数，防止在线猜测当前密码");
    bool(
        "security.mfa_required",
        "账号与安全",
        "启用双因素认证策略",
        true,
        "开启时管理员或财务任一角色必须验证；管理员可为应急关闭，已绑定密钥保留，恢复开启后重新要求验证");
    text("site.name", "网站信息", "网站名称", "AI万有网", true, "展示在网站标题和导航");
    text("site.domain", "网站信息", "主站域名", "www.aiwyw.com", true, "只填写域名，不含协议与路径");
    text("site.filing", "网站信息", "备案号", "", true, "备案完成后填写，当前留空");
    text("site.description", "网站信息", "网站介绍", "发现好用的工具，让每个想法都能落地。", true, "用于首页介绍与搜索摘要");
    text(
        "site.description_en",
        "网站信息",
        "英文介绍",
        "Useful tools for ideas worth building.",
        true,
        "英文首页与搜索摘要");
    option(
        "site.fallback_language", "网站信息", "默认回退语言", "en", true, "未匹配的浏览器语言", List.of("en", "zh"));
    // 首发商品旧展示键保留在数据库历史中；真实商品资料统一从应用管理维护，避免双写。
    number("wallet.points_per_yuan", "积分与交易", "每元普通积分", 10, 1, 100000, "普通充值永久有效；更改不影响历史订单快照");
    number("wallet.min_recharge_yuan", "积分与交易", "普通充值最低金额 元", 1, 1, 1000000, "普通充值仅支持整数元");
    number(
        "wallet.max_recharge_yuan", "积分与交易", "普通充值最高金额 元", 10000, 1, 1000000, "交易模块按最小值与最大值共同验证");
    number("wallet.large_payment_yuan", "积分与交易", "大额充值审核门槛", 200, 1, 1000000, "达到门槛由管理员审核，通过后系统入账");
    number(
        "wallet.order_timeout_minutes",
        "积分与交易",
        "未声明付款订单关闭时间 分钟",
        120,
        1,
        10080,
        "已声明付款转待核查，不自动关闭");
    number("wallet.refund_expired_days", "积分与交易", "已过期积分退款有效天数", 30, 1, 3650, "从退款到账时起算");
    number("wallet.visible_packages", "积分与交易", "前台最多展示优惠包档数", 5, 1, 5, "后台可保存多个档次");
    option(
        "wallet.deduction_order",
        "积分与交易",
        "积分扣减顺序",
        "EARLIEST_EXPIRY_FIRST",
        false,
        "已确认先最早到期，最后永久积分",
        List.of("EARLIEST_EXPIRY_FIRST"));
    option(
        "payment.mode",
        "充值收款",
        "收款模式",
        "SIMULATED",
        true,
        "当前只支持模拟充值，真实渠道接入验收后另行开放",
        List.of("SIMULATED"));
    image(
        "payment.mock_image",
        "充值收款",
        "模拟收款二维码",
        "/images/mock-payment.svg",
        true,
        "仅测试流程，不可用于真实转账");
    image("payment.alipay_image", "充值收款", "支付宝付款码", "", false, "可预先上传，上传不代表启用真实收款");
    image("payment.wechat_image", "充值收款", "微信付款码", "", false, "可预先上传，上传不代表启用真实收款");
    number("api.expiry_reminder_days", "API服务", "订阅到期提前提醒 天", 3, 1, 30, "此值为可编辑初始值，业务模块接入后生效");
    bool("api.overage_default", "API服务", "默认允许超额扣积分", false, "用户必须主动开启，当前初始值为关闭");
    bool("api.debug_billable", "API服务", "在线调试正常计费", true, "使用用户本人Key，成功调用正常计费");
    number("license.activate_hour_limit", "软件授权", "激活每来源每小时上限", 60, 1, 10000, "程序激活频控；来源以实际连接地址为准");
    number(
        "license.verify_hour_limit",
        "软件授权",
        "设备校验每小时上限",
        120,
        1,
        10000,
        "按来源和设备绑定计数；建议每分钟至多一次，收到429应退避");
    number("license.default_devices", "软件授权", "默认激活设备数", 1, 1, 1000, "可在商品级覆盖");
    number("support.retention_days", "客服与通知", "聊天默认保留天数", 180, 1, 3650, "到期清理需与附件及备份策略配合");
    number("support.image_limit_mb", "客服与通知", "聊天单张图片上限 MB", 10, 1, 50, "JPG、PNG、WebP；不是付款码上传限制");
    bool("support.public_review", "客服与通知", "公开留言先审核", true, "私密反馈始终不公开");
    bool("support.email_default", "客服与通知", "用户业务邮件默认开启", false, "验证和找回邮件不受业务邮件偏好限制");
    text("support.inbox", "客服与通知", "客服通知邮箱", "", false, "离线留言的邮件接收地址");
    bool("mail.enabled", "邮件服务", "启用邮件服务", false, "先填写服务商资料并测试后开启");
    text("mail.host", "邮件服务", "SMTP主机", "", false, "服务商SMTP地址");
    number("mail.port", "邮件服务", "SMTP端口", 587, 1, 65535, "按服务商说明设置");
    text("mail.username", "邮件服务", "SMTP账号", "", false, "用于邮件认证");
    secret("mail.password", "邮件服务", "SMTP密码或授权码", "仅加密保存，不返回原文");
    text("mail.from", "邮件服务", "发信邮箱", "", false, "须为服务商允许的发信地址");
    option(
        "mail.security",
        "邮件服务",
        "邮件连接安全方式",
        "STARTTLS",
        false,
        "不支持明文非加密SMTP",
        List.of("STARTTLS", "TLS"));
    option(
        "storage.provider",
        "文件存储",
        "存储类型",
        "LOCAL",
        false,
        "新上传按当前模式保存；历史文件保留原存储位置",
        List.of("LOCAL", "S3", "EXTERNAL"));
    text("storage.endpoint", "文件存储", "对象存储Endpoint", "", false, "只填写HTTPS地址");
    text("storage.region", "文件存储", "对象存储Region", "", false, "按服务商填写");
    text("storage.bucket", "文件存储", "对象存储Bucket", "", false, "不能公开存放私有商品文件");
    secret("storage.access_key", "文件存储", "Access Key", "仅加密保存");
    secret("storage.secret_key", "文件存储", "Secret Key", "仅加密保存");
    text("storage.local_directory", "文件存储", "本地存储子目录", "assets", false, "相对于服务数据目录，切换后旧文件仍按原目录读取");
    number(
        "storage.image_limit_mb",
        "文件存储",
        "图片大小上限 MB",
        10,
        1,
        50,
        "PNG、JPG、WebP；解码校验并重新编码，拒绝脚本和伪装文件");
    number("storage.file_limit_mb", "文件存储", "下载文件大小上限 MB", 256, 1, 1024, "文件流式保存，不将安装包整体读入内存");
    number("storage.download_minutes", "文件存储", "下载链接有效分钟", 5, 1, 60, "短期交付凭据；私有文件不能通过存储ID直接下载");
    bool("storage.path_style", "文件存储", "S3路径式访问", true, "S3兼容服务按需开启；Amazon S3可按服务要求关闭");
    secret("storage.session_token", "文件存储", "临时凭据Session Token", "可选，与Access Key和Secret Key配合使用");
    text(
        "account.link_origin",
        "账号与安全",
        "账户邮件链接站点地址",
        "",
        false,
        "留空使用主站HTTPS域名；本地验证可填http://127.0.0.1:3000，生产必须HTTPS");
    number("account.mail_hour_limit", "账号与安全", "账户邮件每小时请求上限", 5, 1, 30, "按来源与邮箱分别限制，持久化窗口");
    number("account.verify_minutes", "账号与安全", "邮箱验证链接有效分钟", 60, 5, 1440, "一次性链接；重发后旧链接失效");
    number("account.reset_minutes", "账号与安全", "密码重置链接有效分钟", 30, 5, 120, "重置后撤销全部会话");
    number("security.session_hours", "账号与安全", "后台登录有效小时数", 8, 1, 24, "下次创建登录会话时生效");
    number("security.login_attempts", "账号与安全", "15分钟内登录失败上限", 5, 3, 20, "按来源及账号限制，成功后清除");
    bool("release.approval_required", "发布与运行", "生产发布需管理员批准", true, "CI生产环境另设环境审批保护");
  }

  private static void text(String k, String g, String l, String v, boolean pub, String h) {
    DEFINITIONS.add(new Definition(k, g, l, "text", v, false, pub, h, 0, 2000, List.of()));
  }

  private static void image(String k, String g, String l, String v, boolean pub, String h) {
    DEFINITIONS.add(new Definition(k, g, l, "image", v, false, pub, h, 0, 200, List.of()));
  }

  private static void number(String k, String g, String l, int v, int min, int max, String h) {
    DEFINITIONS.add(
        new Definition(k, g, l, "number", "" + v, false, false, h, min, max, List.of()));
  }

  private static void bool(String k, String g, String l, boolean v, String h) {
    DEFINITIONS.add(new Definition(k, g, l, "boolean", "" + v, false, false, h, 0, 0, List.of()));
  }

  private static void option(
      String k, String g, String l, String v, boolean pub, String h, List<String> opts) {
    DEFINITIONS.add(new Definition(k, g, l, "select", v, false, pub, h, 0, 0, opts));
  }

  private static void secret(String k, String g, String l, String h) {
    DEFINITIONS.add(new Definition(k, g, l, "secret", "", true, false, h, 0, 2000, List.of()));
  }

  private final JdbcTemplate jdbc;
  private final SecureStore crypto;
  private final TransactionTemplate transaction;

  public Settings(JdbcTemplate jdbc, SecureStore crypto, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.crypto = crypto;
    transaction = new TransactionTemplate(manager);
  }

  @jakarta.annotation.PostConstruct
  public void initialize() {
    seed(jdbc);
  }

  /** 只补缺失配置，不覆盖运营者已保存值；敏感初值也按密文存储。 */
  public void seed(JdbcTemplate db) {
    for (var d : DEFINITIONS)
      if (db.queryForObject(
              "SELECT COUNT(*) FROM system_config WHERE config_key=?", Integer.class, d.key())
          == 0)
        db.update(
            "INSERT INTO system_config(config_key,config_value,revision,updated_at)"
                + " VALUES(?,?,0,?)",
            d.key(),
            d.secret() ? crypto.encrypt(d.initial()) : d.initial(),
            System.currentTimeMillis());
  }

  /** 未知键直接拒绝，避免借助通用配置接口写入未授权选项。 */
  public Definition definition(String key) {
    return DEFINITIONS.stream()
        .filter(x -> x.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new Api.Failure(400, "未知配置项"));
  }

  /** 内部取值方法会解密敏感配置；不可直接将返回结果序列化给浏览器。 */
  public String value(String key) {
    var d = definition(key);
    var rows =
        jdbc.queryForList(
            "SELECT config_value FROM system_config WHERE config_key=?", String.class, key);
    return rows.isEmpty() ? d.initial() : (d.secret() ? crypto.decrypt(rows.get(0)) : rows.get(0));
  }

  /** 仅用于注册为整数的配置；小数金额和积分不得通过此方法截断。 */
  public int integer(String key) {
    return Integer.parseInt(value(key));
  }

  /** 后台敏感值恒返回空字符串及configured标记，保留revision供冲突检测。 */
  public List<Entry> list() {
    return DEFINITIONS.stream()
        .map(
            d -> {
              var rows =
                  jdbc.queryForList(
                      "SELECT config_value,revision FROM system_config WHERE config_key=?",
                      d.key());
              if (rows.isEmpty())
                return new Entry(d, d.secret() ? "" : d.initial(), !d.initial().isBlank(), 0);
              var r = rows.get(0);
              String v =
                  d.secret()
                      ? crypto.decrypt((String) r.get("config_value"))
                      : (String) r.get("config_value");
              return new Entry(
                  d, d.secret() ? "" : v, !v.isBlank(), ((Number) r.get("revision")).longValue());
            })
        .toList();
  }

  /** 仅返回publicValue白名单，不因请求者登录而扩展公开字段。 */
  public Map<String, String> publicValues() {
    Map<String, String> out = new LinkedHashMap<>();
    DEFINITIONS.stream()
        .filter(Definition::publicValue)
        .forEach(d -> out.put(d.key(), value(d.key())));
    return out;
  }

  /** 先校验整批输入，随后事务内比较revision并更新；任一冲突回滚所有修改。敏感值留空表示保持，clear才表示删除。 */
  public void save(List<Change> changes, String actor) {
    if (changes == null || changes.isEmpty() || changes.size() > 100)
      throw new Api.Failure(400, "请选择需要保存的配置项");
    Set<String> keys = new HashSet<>();
    for (var c : changes) {
      if (c == null || !keys.add(c.key())) throw new Api.Failure(400, "配置项不能重复");
      validate(definition(c.key()), c.value(), c.clear());
    }
    transaction.executeWithoutResult(
        status -> {
          // 两个边界字段共用行锁，避免并发只改上限/下限产生写偏差。
          boolean bounds =
              keys.contains("wallet.min_recharge_yuan")
                  || keys.contains("wallet.max_recharge_yuan");
          if (bounds)
            jdbc.queryForList(
                "SELECT config_key FROM system_config WHERE config_key IN"
                    + " ('wallet.min_recharge_yuan','wallet.max_recharge_yuan') ORDER BY config_key"
                    + " FOR UPDATE");
          for (var c : changes) {
            var d = definition(c.key());
            String v = c.clear() ? "" : c.value();
            if (d.secret() && !c.clear() && (v == null || v.isBlank())) continue;
            int updated =
                jdbc.update(
                    "UPDATE system_config SET config_value=?,revision=revision+1,updated_at=? WHERE"
                        + " config_key=? AND revision=?",
                    d.secret() ? crypto.encrypt(v) : v,
                    System.currentTimeMillis(),
                    c.key(),
                    c.revision());
            if (updated != 1) throw new Api.Failure(409, "配置已被其他管理员修改，请重新加载后保存");
            jdbc.update(
                "INSERT INTO config_audit(id,user_id,config_key,revision,action_name,created_at)"
                    + " VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                actor,
                c.key(),
                c.revision() + 1,
                c.clear() ? "CLEAR" : "UPDATE",
                System.currentTimeMillis());
          }
          if (bounds && integer("wallet.min_recharge_yuan") > integer("wallet.max_recharge_yuan"))
            throw new Api.Failure(400, "最低充值金额不能超过最高充值金额");
        });
  }

  /** 所有类型与安全固定项在服务端校验，绕过前端控件也不能写危险值。 */
  private void validate(Definition d, String v, boolean clear) {
    if (clear) {
      if (!d.secret()) throw new Api.Failure(400, "只有敏感配置支持明确清除");
      return;
    }
    if (v == null || v.length() > 2000) throw new Api.Failure(400, d.label() + "格式不正确");
    switch (d.type()) {
      case "number" -> {
        try {
          int n = Integer.parseInt(v);
          if (n < d.min() || n > d.max()) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw new Api.Failure(400, d.label() + "超出允许范围");
        }
      }
      case "boolean" -> {
        if (!List.of("true", "false").contains(v)) throw new Api.Failure(400, "请选择开关状态");
      }
      case "select" -> {
        if (!d.options().contains(v)) throw new Api.Failure(400, d.label() + "暂不支持此选项");
      }
      case "image" -> {
        if (!v.isEmpty()
            && !v.equals("/images/mock-payment.svg")
            && !v.matches("/api/public/assets/[a-f0-9-]{36}\\.png"))
          throw new Api.Failure(400, "请使用图片上传功能");
      }
      default -> {}
    }
    if (List.of("site.name", "site.description", "product.markdo.title").contains(d.key())
        && v.isBlank()) throw new Api.Failure(400, d.label() + "不能为空");
    if (d.key().equals("site.domain")
        && !v.matches(
            "(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}"))
      throw new Api.Failure(400, "请输入有效域名，不含协议或路径");
    if (List.of("security.admin_mfa", "security.finance_mfa", "release.approval_required")
            .contains(d.key())
        && !v.equals("true")) throw new Api.Failure(400, "此项为已确认的安全要求，不能关闭");
    if (d.key().equals("api.overage_default") && v.equals("true"))
      throw new Api.Failure(400, "超额扣积分必须由用户主动开启，不能默认开启");
    if (d.key().equals("api.debug_billable") && !v.equals("true"))
      throw new Api.Failure(400, "在线调试按已确认规则正常计费");
    if (d.key().equals("storage.local_directory")
        && (!v.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*") || v.length() > 120))
      throw new Api.Failure(400, "本地子目录仅支持英文、数字、连字符、下划线和斜线");
    if (d.key().equals("account.link_origin") && !v.isBlank()) {
      try {
        URI u = URI.create(v);
        if (u.getHost() == null
            || u.getUserInfo() != null
            || u.getQuery() != null
            || u.getFragment() != null
            || !("https".equals(u.getScheme())
                || ("http".equals(u.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(u.getHost())))
            || !(u.getPath().isEmpty() || u.getPath().equals("/")))
          throw new IllegalArgumentException();
      } catch (Exception bad) {
        throw new Api.Failure(400, "邮件链接地址需为HTTPS站点地址，本机可使用localhost或127.0.0.1的HTTP地址");
      }
    }
    if (d.key().equals("storage.endpoint") && !v.isBlank()) {
      try {
        URI u = URI.create(v);
        if (!"https".equals(u.getScheme())
            || u.getHost() == null
            || u.getUserInfo() != null
            || u.getQuery() != null
            || u.getFragment() != null) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new Api.Failure(400, "Endpoint必须是有效HTTPS地址");
      }
    }
    if (List.of("mail.from", "support.inbox").contains(d.key())
        && !v.isBlank()
        && !v.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new Api.Failure(400, "邮箱格式不正确");
  }
}
