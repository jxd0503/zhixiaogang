# 智小港（zhixiaogang）

校园智慧服务平台，面向 **学生 / 教师 / 管理员** 三端，覆盖课表查询、空教室查询与预约、调课与课程互换、通知触达、AI 排课辅助、知识库问答与后台管理等校园服务场景。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21 |
| 后端框架 | Spring Boot 3.2.5、Spring MVC |
| 持久层 | MyBatis-Plus、MySQL 8 |
| 页面渲染 | Thymeleaf |
| 前端 | 原生 HTML / CSS / JavaScript |
| 构建工具 | Maven |
| AI 能力 | 兼容 OpenAI 风格接口的大模型服务（可选，未配置时自动降级） |

## 目录结构

```text
zhixiaogang/
├── pom.xml
├── src/main/java/com/zhixiaogang/
│   ├── ZhixiaogangApplication.java   # 启动类
│   ├── config/                       # 拦截器、异常处理、数据库初始化、定时任务
│   ├── controller/                   # 三端页面路由与 REST 接口
│   ├── entity/                       # 数据实体
│   ├── mapper/                       # MyBatis-Plus Mapper
│   ├── service/                      # 业务服务（课堂、预约、调课、通知、AI 排课等）
│   └── util/                         # 口令、加密、周次解析等工具
├── src/main/resources/
│   ├── application.yml               # 数据源等配置（凭据全部走环境变量）
│   ├── schema.sql / data.sql         # 建表脚本与虚构演示数据
│   ├── mapper/                       # MyBatis XML
│   ├── static/                       # 三端 CSS / JavaScript
│   └── templates/                    # 登录页与三端主页模板
└── src/test/java/com/zhixiaogang/    # 单元测试
```

## 三端功能概览

### 学生端
- 账号注册与登录、个人资料维护
- 课程表查询、班级课表与入班申请
- 空教室按日期 / 节次 / 教学楼查询与预约
- 调课申请、通知消息查看
- AI 校园问答（课表、教室、规章制度等意图）

### 教师端
- 教师登录、任教班级与课程查询
- 班级课表、空教室查询
- 调课申请与审批、课程互换
- 排课要求提交（AI 不可用时支持按原文提交，由管理员后续处理）
- 通知消息、AI 助手

### 管理端
- 用户 / 班级 / 教室 / 课程管理
- 教室预约审核、调课审核与课程互换审批
- AI 服务商与密钥配置（密钥加密存储）
- 排课要求处理、通知发布、操作日志审计
- 学期配置与仪表盘统计

## 本地启动

### 1. 准备环境
- JDK 21
- Maven 3.9+
- MySQL 8（本地或远程均可）

### 2. 配置数据库（使用环境变量，不要把口令写进仓库）

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_URL` | JDBC 连接串 | `jdbc:mysql://localhost:3306/zhixiaogang?...` |
| `DB_USERNAME` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库口令 | 空 |
| `CORS_ALLOWED_ORIGINS` | 跨域白名单，逗号分隔；留空表示仅同源 | 空 |
| `AI_KEY_SECRET` | API Key 加密密钥（可选） | 未配置时自动生成到 `config/ai-key.secret` |

PowerShell 示例：

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/zhixiaogang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='你的本地数据库口令'
mvn spring-boot:run
```

也可以先打包再运行：

```powershell
mvn clean package
java -jar target/zhixiaogang-1.0.0.jar --server.port=8080
```

启动后访问 `http://localhost:8080`（学生端）、`/teacher/login`（教师端）、`/admin/login`（管理端）。

> 首次连接空库时会自动建表并导入 `data.sql` 演示数据；已有数据的库不会重复初始化。

### 3. 默认账号说明

| 角色 | 账号 | 初始口令 |
| --- | --- | --- |
| 管理员 | `admin` | 首次启动随机生成，仅在控制台打印一次 |
| 演示学生 | `2024001`、`2024002`、`2024003`、`2024007` | 首次启动随机生成，仅在控制台打印一次 |
| 演示教师 | `t001`、`t002` | 首次启动随机生成，仅在控制台打印一次 |

首次启动后请查看控制台输出获取随机口令，并尽快登录修改。仓库中**不包含任何固定口令或真实凭据**。

## 数据与隐私

- `data.sql` 中的姓名、学号/工号、手机号与邮箱均为**虚构演示数据**，手机号为占位号段，邮箱使用 `example.com` 保留域。
- 数据库连接、AI 服务商密钥等通过环境变量或管理端配置，不写入仓库。
- 运行日志、上传附件、本地 IDE 配置与加密密钥文件已通过 `.gitignore` 排除。
