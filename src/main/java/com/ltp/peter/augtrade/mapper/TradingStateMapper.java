package com.ltp.peter.augtrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ltp.peter.augtrade.entity.TradingState;
import org.apache.ibatis.annotations.Mapper;

/**
 * 交易状态持久化Mapper
 * 使用MyBatis-Plus的BaseMapper，无需编写SQL
 * 
 * @author Peter Wang
 */
@Mapper
public interface TradingStateMapper extends BaseMapper<TradingState> {
    // MyBatis-Plus自动提供CRUD方法：
    // - insert(TradingState entity)
    // - selectById(Integer id)
    // - selectOne(Wrapper<TradingState> queryWrapper)
    // - updateById(TradingState entity)
    // - deleteById(Integer id)
    // 等等...
}
