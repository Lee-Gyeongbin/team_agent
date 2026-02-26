package kr.teamagent.common.secure.service;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.springframework.stereotype.Component;

/**
 * MYBATIS 암호화컬럼 타입핸들러 추상클래스
 * 컬럼별로 암복호화를 적용할 것인지를 설정하게 하기 위해서
 * 컬럼별로 각각의클래스에서 추상클래스를 상속받아 암복호화여부를 각각 설정한다.
 * @author LG
 */
@Component
public abstract class AbstractCipherTypeHandler implements TypeHandler<String> {

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.apache.ibatis.type.TypeHandler#setParameter(java.sql.PreparedStatement,
	 * int, java.lang.Object, org.apache.ibatis.type.JdbcType)
	 */
	@Override
	public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
		// 암호화 여부 확인
		if (isCipher()) {
			parameter = encode(parameter);
		}
		ps.setString(i, parameter);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.apache.ibatis.type.TypeHandler#getResult(java.sql.ResultSet,
	 * java.lang.String)
	 */
	@Override
	public String getResult(ResultSet rs, String columnName) throws SQLException {


		String value = rs.getString(columnName);

		// 암호화 여부 확인
		if (isCipher()) {
			value = decode(value);
		}
		return value;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.apache.ibatis.type.TypeHandler#getResult(java.sql.ResultSet, int)
	 */
	@Override
	public String getResult(ResultSet rs, int columnIndex) throws SQLException {
		String value = rs.getString(columnIndex);

		// 암호화 여부 확인
		if (isCipher()) {
			value = decode(value);
		}
		return value;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.apache.ibatis.type.TypeHandler#getResult(java.sql.CallableStatement,
	 * int)
	 */
	@Override
	public String getResult(CallableStatement cs, int columnIndex) throws SQLException {
		String value = cs.getString(columnIndex);

		// 암호화 여부 확인
		if (isCipher()) {
			value = decode(value);
		}
		return value;
	}

	/**
	 * 암호화 여부
	 *
	 * @return 암호화 여부
	 */
	protected abstract boolean isCipher();

	/**
	 * 암호화
	 *
	 * @param value 변환 문자
	 * @return 암호화된 문자
	 */
	protected String encode(String value) {
		String result="";
		try
		{
			result = SecureService.encryptStr(value);
		} catch (Exception e) {
			result="encrpyt error!";
		}
		return result;
	}

	/**
	 * 복호화
	 *
	 * @param value 변환 문자
	 * @return 복호화된 문자
	 */
	public static String decode(String value) {
		String result="";
		try
		{
			result = SecureService.decryptStr(value);
		} catch (Exception e) {
			result="decrpyt error!";
		}
		return result;
	}
}
