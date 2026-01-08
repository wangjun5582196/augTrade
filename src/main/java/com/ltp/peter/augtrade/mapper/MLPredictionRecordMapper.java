package com.ltp.peter.augtrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ltp.peter.augtrade.entity.MLPredictionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * ML预测记录Mapper
 * 
 * @author Peter Wang
 */
@Mapper
public interface MLPredictionRecordMapper extends BaseMapper<MLPredictionRecord> {
    // 使用MyBatis-Plus的方式，不需要写SQL
    // 所有查询在Service层通过LambdaQueryWrapper实现
}
