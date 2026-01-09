package com.ltp.peter.augtrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ltp.peter.augtrade.entity.BacktestTrade;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回测交易记录Mapper
 * 
 * @author Peter Wang
 */
@Mapper
public interface BacktestTradeMapper extends BaseMapper<BacktestTrade> {
}
