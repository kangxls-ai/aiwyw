# 微云湾生产部署

发布包需要 Java 17、MySQL 和启用了 auth_request 模块的 Nginx。构建机使用 Node.js 和 Maven。运行机器只需 Nginx 与 Spring Boot。

## 目录

| 路径 | 用途 |
| --- | --- |
| aiwyw-server.jar | 业务代码及 Spring Boot 启动器 |
| lib/ | Maven 运行依赖，release.json 中的 librariesSha256 可用于比较版本 |
| config/application.properties | MySQL 连接、回环端口及数据目录，由运维填写 |
| security/ | 首次启动生成的 master.key、staff-entry 和首次安装码 |
| data/ | 上传文件和应用自己的发布快照 |
| www/ | 前端构建文件和 Java 发布到 _published/ 的公开快照 |
| run/ | 脚本进程号与日志 |
| nginx/ | TLS 站点示例、路由和代理片段 |

## 首次安装

1. 解包到部署目录，例如 /opt/aiwyw/current。为 MySQL 创建独立空库和专用账号，授予该库读写和建表权限，启用 TLS。
2. 填写 config/application.properties 中的 MySQL 地址、库名、账号和密码。此文件、security/、data/ 和 run/ 只授权给运行账号。Nginx 账号只需访问 www/，并可穿过部署根目录。
3. Windows 双击 start.cmd，或在命令行执行。Linux/macOS 执行 sh start.sh。查看 run/server.log 和 /api/install/status 确认应用就绪。Linux 也可使用 aiwyw.service 以专用账号运行。
4. 将 nginx/nginx.conf 放到 Nginx 的 http 上下文，将另两个片段放到 Nginx 配置目录。核对域名、证书、www 的绝对路径和 Spring Boot 回环端口。主 http 块加载 mime.types。执行 nginx -t 后重载。
5. 首次安装期间将 /install 和 /api/install 限制在运维 IP 或管理网络，从服务器上的 security/install.token 读取安装码。打开安装向导创建首个管理员。初始化成功后安装码自动删除。
6. 后台登录地址为域名、斜杠、security/staff-entry 文件的值、/login。站点只通过 HTTPS 对外提供。运营配置、邮箱、备案号和注册开关都在后台配置管理中维护。

Windows 停止用 stop.cmd，Linux/macOS 用 sh stop.sh。脚本校验本实例的进程归属并请求优雅停止。Nginx 独立维护。也可直接运行：

    java -Dloader.path=lib -jar aiwyw-server.jar --spring.config.additional-location=file:./config/

Spring Boot 只监听回环端口。只开放 Nginx 对外端口。后台入口经 Nginx 的内部子请求验证，后台 API 仍执行会话、菜单权限、双重认证和来源校验。保管入口地址和加密密钥，限制运行日志的读取权限。

## 上线核对

- 在后台执行「生产上线检查」。在目标主机测试实际邮件送达、账号找回、下载、API 代理和客服通知。若前方使用 CDN，按实际部署校验可信代理链和来源 IP。
- 首次安装使用空库。升级使用原生产库及匹配的主密钥。生产模式检测到 LOCAL_REVIEW_ 验收库标记会停止启动。
- MySQL 连接强制加密。要求验证数据库服务器身份时，需要按目标环境配置受信 CA 和连接策略。
- 真收款沿用原开关。上传收款码不会自动开放真实收款。

## QQ邮箱测试

在“配置管理 → 邮件服务”点击QQ方案，可填入连接参数；不会自动替换已有授权码或开启发信开关。账号填写完整邮箱地址，发信地址与账号一致，密码使用QQ邮箱生成的授权码，不能使用QQ登录密码。先保存，再测试。

| 协议 | 主机        | 端口与加密                                 | 本系统用途                    |
| ---- | ----------- | ------------------------------------------ | ----------------------------- |
| SMTP | smtp.qq.com | 465 SSL/TLS（配置值TLS）；备用587 STARTTLS | 发送网站验证、找回密码等邮件  |
| POP3 | pop.qq.com  | 995 SSL/TLS                                | 仅连接和认证，不读取/删除邮件 |
| IMAP | imap.qq.com | 993 SSL/TLS                                | 仅连接和认证，不打开文件夹    |

QQ服务开启与授权码参考 [QQ邮箱帮助](https://help.mail.qq.com/detail/106/985)、[华为官方操作说明](https://consumer.huawei.com/cn/support/content/zh-cn16108643/)；服务器参数参考 [腾讯云企业集成服务官方文档](https://main.qcloudimg.com/raw/document/product/pdf/1270_46586_cn.pdf)。协议服务必须分别在邮箱网页允许；部分账号受服务商策略限制时，以实际诊断结果为准。

诊断显示 `AUTH`、`TLS`、`DNS`、`TIMEOUT`、`CONNECT` 或 `DELIVERY` 和处理建议，不返回原始异常、密码或验证令牌。TLS证书检查始终开启。SMTP连接测试不会发信；“发送测试邮件到我的邮箱”只投递给当前管理员，成功表示SMTP已接收，不代表最终入箱。

自动投递失败保留加密正文并逐步退避重试，最长每小时一次。配置页显示等待数量与失败分类；成功后清除错误分类和队列正文。不要反复点击注册制造大量重试邮件。若授权码变更或服务商风控，先完成连接诊断，再实际查收测试邮件，最后启用自动邮件服务。

原“邮件设置”为空占位，与配置中心重复，已移除；旧入口跳转到邮件服务。旧的独立 `email` 菜单授权删除，不将它自动升级成能修改全部配置的 `settings` 权限。已有管理员配置权限保持不变。

## 更新与回滚

- 先停后端，备份 MySQL、data/、security/ 和 config/，保留上一版发布包。加密配置的解密依赖同一份 master.key，已有站点需保留原值。
- 从旧的前后端双进程版本迁移时，将旧 backend/data/ 复制到 data/，将旧 master.key 和 staff-entry 移入同级 security/。在 config/application.properties 中填写原 MySQL 连接。旧版 bootstrap.enc 只供旧版本回滚，随原备份保存。
- 比较新旧 release.json 的 librariesSha256。相同可只换业务 JAR，前端资源有变化时同步更新 www/ 中的构建文件。依赖变更时成套更新 lib/。保留旧哈希资源直到旧页面请求过期。
- 重启 Spring Boot 后会重建公开快照。验证首页、应用、文章、专栏、用户登录、后台入口及 API。回滚业务包前先确认数据库迁移兼容性，必要时使用更新前备份恢复。
- config/ 和 security/ 应持久保存。发布包没有运行时数据、密钥、数据库密码或账户。

## 静态发布

Nginx 直接读取首页、应用详情、文章详情、专栏目录、公开图片、公开站点配置和导航文件。用户登录由静态前端启动，登录、搜索、下载授权、API 调用与后台操作仍调用 Spring Boot。内容上下架、栏目、专栏和公开配置更新会重建发布快照，后台「发布站点」可手动刷新。静态首页排名和卡片计数使用发布时的快照。快照切换中请求可能短暂返回 404 或 503，反向代理和 CDN 不宜缓存这些响应或公开 HTML。

公开发布文件由应用生成，www/_published/ 不作为后台手工编辑目录。静态发布失败时在后台核对业务保存结果，排查 run/server.log、目录权限和对象存储，再重新发布。Nginx 不提供 www/ 中的隐藏暂存目录或后台动态文件。

文章和应用的每次发布在发布表的 web_directory 中记录北京时间年月日及发布 ID。当前公开文件位于 www/_published/pages/，Nginx 通过按地址前两位分片的 routes/ 入口读取。专栏目录按创建日期归档。更新、下架和删除通过整批切换替换旧公开文件，原有历史发布记录及审核快照保留。升级时先更新 Nginx 的 aiwyw-routes.conf，再启动新后端。新规则也可读取上一版平铺文件。

新业务对象使用 26 位小写编码，历史 UUID 继续可读。发布到旧版服务器前应先核对回滚版本能否识别升级后新增的编号和文件目录，并使用成套备份制定回滚方案。

## 构建

    mvn -B -ntp -f backend/pom.xml clean verify -Pdeployment
    npm ci
    npm run build:static
    node scripts/package-production.mjs

输出在 .local/production/，含 release.json 和 SHA256SUMS。GitHub Actions 的 release 任务在构建和测试通过后归档正式包。旧站点尚处在模拟捐赠流程时，沿用原收款开关和配置。
