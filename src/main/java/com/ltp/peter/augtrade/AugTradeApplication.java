package com.ltp.peter.augtrade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 黄金短线量化交易平台主应用
 * 
 * @author Peter Wang
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.ltp.peter.augtrade.mapper")
public class AugTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AugTradeApplication.class, args);
        System.out.println("=================================");
        System.out.println("黄金短线量化交易平台启动成功！");
        System.out.println("=================================");
    }
}
