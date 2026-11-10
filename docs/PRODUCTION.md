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

## 更新与回滚

- 先停后端，备份 MySQL、data/、security/ 和 config/，保留上一版发布包。加密配置的解密依赖同一份 master.key，已有站点需保留原值。
- 从旧的前后端双进程版本迁移时，将旧 backend/data/ 复制到 data/，将旧 master.key 和 staff-entry 移入同级 security/。在 config/application.properties 中填写原 MySQL 连接。旧版 bootstrap.enc 只供旧版本回滚，随原备份保存。
- 比较新旧 release.json 的 librariesSha256。相同可只换业务 JAR，前端资源有变化时同步更新 www/ 中的构建文件。依赖变更时成套更新 lib/。保留旧哈希资源直到旧页面请求过期。
- 重启 Spring Boot 后会重建公开快照。验证首页、应用、文章、专栏、用户登录、后台入口及 API。回滚业务包前先确认数据库迁移兼容性，必要时使用更新前备份恢复。
- config/ 和 security/ 应持久保存。发布包没有运行时数据、密钥、数据库密码或账户。

## 静态发布

Nginx 直接读取首页、应用详情、文章详情、专栏目录、公开图片、公开站点配置和导航文件。用户登录由静态前端启动，登录、搜索、下载授权、API 调用与后台操作仍调用 Spring Boot。内容上下架、栏目、专栏和公开配置更新会重建发布快照，后台「发布站点」可手动刷新。静态首页排名和卡片计数使用发布时的快照。快照切换中请求可能短暂返回 404 或 503，反向代理和 CDN 不宜缓存这些响应或公开 HTML。

公开发布文件由应用生成，www/_published/ 不作为后台手工编辑目录。静态发布失败时在后台核对业务保存结果，排查 run/server.log、目录权限和对象存储，再重新发布。Nginx 不提供 www/ 中的隐藏暂存目录或后台动态文件。

## 构建

    mvn -B -ntp -f backend/pom.xml clean verify -Pdeployment
    npm ci
    npm run build:static
    node scripts/package-production.mjs

输出在 .local/production/，含 release.json 和 SHA256SUMS。GitHub Actions 的 release 任务在构建和测试通过后归档正式包。旧站点尚处在模拟捐赠流程时，沿用原收款开关和配置。
