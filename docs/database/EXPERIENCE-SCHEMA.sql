-- 结构审阅参考，不在在线库重复执行；ExperienceSchema按字段是否存在执行幂等升级。
-- 升级前成套备份MySQL、主密钥、加密引导连接、存储和静态文件。
ALTER TABLE recharge_order ADD COLUMN payment_memo VARCHAR(4) NULL
  COMMENT '创建时随机四位付款备注，可重复，非支付凭证；旧订单为空';
ALTER TABLE api_definition ADD COLUMN request_method VARCHAR(8) NOT NULL DEFAULT 'POST'
  COMMENT '真实上游请求方式GET或POST；平台统一POST入口不变';
ALTER TABLE api_definition ADD COLUMN headers_enc LONGTEXT NULL
  COMMENT '自定义上游请求头JSON的AES-GCM密文；不返回原文，不转发用户Cookie或平台Key';

-- payment_memo是保留前导零的字符串，不增加唯一索引；不据此自动入账或更新旧订单。
-- headers_enc必须由SecureStore加密写入；不能人工填明文，不能脱离master.key恢复。
-- V011_API_NAV一次性迁移API导航到home_section，mode='API'；不占六个应用区域，显示时固定末尾。
