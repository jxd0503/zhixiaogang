package com.zhixiaogang;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 应用启动入口：启动 Spring Boot 校园智能服务。 */
@SpringBootApplication
@MapperScan("com.zhixiaogang.mapper")
public class ZhixiaogangApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhixiaogangApplication.class, args);
    }   
}
