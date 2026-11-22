package com.zhixiaogang;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.sql.Connection;
import java.sql.DriverManager;

/** 应用启动入口：启动 Spring Boot 校园智能服务。 */
@SpringBootApplication
@MapperScan("com.zhixiaogang.mapper")
public class ZhixiaogangApplication {

    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://localhost:3306/zhixiaogang?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true";

    public static void main(String[] args) {
        verifyDatabaseOrExit(args);
        SpringApplication.run(ZhixiaogangApplication.class, args);
    }

    /**
     * 启动前预检数据库连接：失败时输出中文指引并退出，避免用户只看到异常堆栈。
     * 配置优先级：命令行参数 --spring.datasource.* > 环境变量 DB_* > application.yml 默认值。
     */
    private static void verifyDatabaseOrExit(String[] args) {
        String url = resolve(args, "spring.datasource.url", "DB_URL", DEFAULT_DB_URL);
        String username = resolve(args, "spring.datasource.username", "DB_USERNAME", "root");
        String password = resolve(args, "spring.datasource.password", "DB_PASSWORD", "");
        try (Connection ignored = DriverManager.getConnection(url, username, password)) {
            // 连接成功，继续启动
        } catch (Exception e) {
            System.err.println();
            System.err.println("============================================================");
            System.err.println(" 智小港启动失败：无法连接数据库");
            System.err.println("------------------------------------------------------------");
            System.err.println(" 当前连接：" + url.replaceAll("(?i)(password=)[^&]*", "$1***"));
            System.err.println(" 失败原因：" + rootMessage(e));
            System.err.println();
            System.err.println(" 请按以下方式检查：");
            System.err.println("   1) MySQL 服务已启动，端口可访问；");
            System.err.println("   2) 设置环境变量后重新启动：");
            System.err.println("        DB_URL=jdbc:mysql://主机:3306/zhixiaogang"
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
            System.err.println("        DB_USERNAME=数据库用户名");
            System.err.println("        DB_PASSWORD=数据库口令");
            System.err.println("   3) 或在本程序目录的 config/application.yml 中配置 spring.datasource.*；");
            System.err.println("   4) 数据库账号需具备建库/建表权限（首次启动会自动初始化表结构与演示数据）。");
            System.err.println("============================================================");
            System.err.println();
            System.exit(1);
        }
    }

    private static String resolve(String[] args, String key, String envName, String fallback) {
        String prefix = "--" + key + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        String env = System.getenv(envName);
        return env == null || env.isBlank() ? fallback : env;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
