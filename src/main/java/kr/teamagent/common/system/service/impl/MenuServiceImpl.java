/*************************************************************************
 * CLASS 명	: MenuServiceImpl
 * 작 업 자	: kimyh
 * 작 업 일	: 2017. 11. 23.
 * 기	능	: 메뉴 service
 * ---------------------------- 변 경 이 력 --------------------------------
 * 번호	작 업 자		작	업	일			변 경 내 용				비고
 * ----	---------	----------------	---------------------	-----------
 *	1	kimyh		2017. 11. 23.		최 초 작 업
 **************************************************************************/
package kr.teamagent.common.system.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.MenuVO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.SQLException;
import java.util.List;

@Service
public class MenuServiceImpl extends EgovAbstractServiceImpl {

	@Resource
	private MenuDAO menuDao;

	/**
	 * 권한 목록 조회
	 * @param	UserVO vo
	 * @return	List<MenuVO>
	 * @throws	Exception
	 */
	public List<MenuVO> selectList(UserVO vo) throws Exception
	{
		return menuDao.selectList(vo);
	}



	/**
	 * 권한 목록 조회
	 * @param	UserVO vo
	 * @return	List<MenuVO>
	 * @throws	Exception
	 */
	public String selectInitPgmId(UserVO vo) throws Exception {
		return menuDao.selectInitPgmId(vo);
	}

	/**
	 * 메뉴 접근 로그 등록
	 * @param	MenuVO dataVO
	 * @return	int
	 * @throws Exception
	 */
	public int insertMenuAccessLog(MenuVO dataVO) throws Exception {
		return menuDao.insertMenuAccessLog(dataVO);
	}


	/**
	 * 도움말 조회
	 * @param	MenuVO searchVO
	 * @return	List<MenuVO>
	 * @throws	SQLException
	 */
	public List<MenuVO> selectGuideCommentList(MenuVO searchVO) throws Exception {
		return menuDao.selectGuideCommentList(searchVO);
	}

	public List<EgovMap> selectMenuIconList() throws Exception {
		return menuDao.selectMenuIconList();
	}
}
