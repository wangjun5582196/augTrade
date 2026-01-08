package com.ltp.peter.augtrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ltp.peter.augtrade.entity.Position;
import org.apache.ibatis.annotations.Mapper;

/**
 * 持仓信息Mapper
 * 使用MyBatis-Plus方式，不使用明文SQL
 * 
 * @author Peter Wang
 */
@Mapper
public interface PositionMapper extends BaseMapper<Position> {
    // 继承BaseMapper后，基本的CRUD方法已经自动提供
    // 复杂查询通过Service层使用QueryWrapper实现
}
