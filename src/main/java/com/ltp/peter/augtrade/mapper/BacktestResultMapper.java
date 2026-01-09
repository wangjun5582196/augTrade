package com.ltp.peter.augtrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ltp.peter.augtrade.entity.BacktestResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回测结果Mapper
 * 
 * @author Peter Wang
 */
@Mapper
public interface BacktestResultMapper extends BaseMapper<BacktestResult> {
}
