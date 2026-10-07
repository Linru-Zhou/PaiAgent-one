package com.paiagent.security;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * workflow.flow_data 是 JSON 列：只加密其中的 apiKey 字段值，整体仍是合法 JSON。
 * 写入时加密、读取时解密，引擎/控制器读到的 Workflow.flowData 始终是明文。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(value = JdbcType.VARCHAR, includeNullJdbcType = true)
public class EncryptedFlowDataTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, SecretCodec.instance().encryptFlowData(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return SecretCodec.instance().decryptFlowData(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return SecretCodec.instance().decryptFlowData(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return SecretCodec.instance().decryptFlowData(cs.getString(columnIndex));
    }
}
