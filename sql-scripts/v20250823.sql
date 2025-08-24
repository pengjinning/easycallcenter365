INSERT INTO `sys_config` VALUES ('13', 'default logo', 'sys.logo', '/img/logo.png', 'Y', 'admin', '2025-08-19 13:57:40', '', NULL, 'default logo');
ALTER TABLE sys_user ADD COLUMN logo VARCHAR(255) COMMENT 'user\'s logo';
ALTER TABLE cc_ext_num MODIFY COLUMN user_code VARCHAR(32) COMMENT 'User ID ';

INSERT INTO `cc_params` VALUES ('82', '连接大模型的最大尝试次数', 'llm-max-try', '1', 'sys', '0');
INSERT INTO `cc_params` VALUES ('83', '连接大模型的超时时间; 毫秒', 'llm-conn-timeout', '3500', 'sys', '0');
INSERT INTO `cc_params` VALUES ('84', '连接大模型的失败提示语', 'llm-max-try-fail-tips', '抱歉刚才信号不好，我没有听清楚，您能在说一遍吗?', 'sys', '0');

update cc_params set param_value='true' where param_code='fs_call_asr_enabled';

UPDATE sys_config SET config_value = 'v20250823' WHERE config_key = 'sys.version';


