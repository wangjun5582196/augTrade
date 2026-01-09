package com.ltp.peter.augtrade.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 回测页面控制器
 * 
 * @author Peter Wang
 */
@Controller
public class BacktestPageController {
    
    /**
     * 回测页面
     */
    @GetMapping("/backtest")
    public String backtest() {
        return "forward:/backtest.html";
    }
}
