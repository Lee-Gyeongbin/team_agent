package kr.teamagent.common.dao;

import javax.annotation.Resource;

import org.apache.ibatis.session.SqlSessionFactory;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;

/**
 * TODO 추후 시연 완료 후 삭제
 * CMB DB(cmb_public 스키마) 전용 추상 DAO.
 * EgovComAbstractDAO와 동일한 API를 사용하며, SqlSessionFactory만 cmb.sqlSession으로 주입한다.
 * CMB DB를 사용하는 DAO는 이 클래스를 상속한다.
 */
public abstract class CmbComAbstractDAO extends EgovComAbstractDAO {

	@Resource(name = "cmb.sqlSession")
	@Override
	public void setSqlSessionFactory(SqlSessionFactory sqlSession) {
		super.setSqlSessionFactory(sqlSession);
	}
}
