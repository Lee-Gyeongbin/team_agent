/**
 * @Class Name	:	LoginDAO.java
 * @Description	:	로그인 DAO
 * @author	:	kimyh
 * @date	:	2017-11-17
 */
package kr.teamagent.common.security.service.impl;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.security.service.AccessLoginVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.LangVO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LoginDAO extends EgovComAbstractDAO {
	// 사용자 조회
	public UserVO selectUser(UserVO vo) throws Exception {
		return (UserVO)selectOne("login.selectUser", vo);
	}

	// 권한 목록 조회
	public List<String> selectAdminGubunList(UserVO vo) throws Exception {
		return selectList("login.selectAdminGubunList", vo);
	}

	// 회사의 사용언어 목록 조회
	public List<LangVO> selectLangList(UserVO vo) throws Exception {
		return selectList("login.selectLangList", vo);
	}

	public String selectIsOnlyInsa(UserVO vo) throws Exception {
		return selectOne("login.selectIsOnlyInsa", vo);
	}

	/**
	 * 사용자의 연도별 성과조직 목록 조회
	 * @param	UserVO vo
	 * @return	List<EgovMap>
	 * @throws	Exception
	 */
	public List<EgovMap> selectUserScDeptList(UserVO vo) throws Exception {
		return selectList("login.selectUserScDeptList", vo);
	}

	/**
	 * 서비스 사용여부 조회
	 * @param	UserVO vo
	 * @return	String
	 * @throws	Exception
	 */
	public String selectServiceUseYn(UserVO vo) throws Exception {
		return selectOne("login.selectServiceUseYn", vo);
	}

	/**
	 * 인증시도 횟수 조회
	 * @param	UserVO vo
	 * @return	String
	 * @throws	Exception
	 */
	public int selectAuthStatusCount(UserVO vo) throws Exception {
		return selectOne("login.selectAuthStatusCount", vo);
	}

	public int selectAuthOtpStatusCount(UserVO vo) throws Exception {
		return selectOne("login.selectAuthOtpStatusCount", vo);
	}

	public int insertAccessCertificationStatusData(AccessLoginVO vo) throws Exception {
		return insert("login.insertAccessCertificationStatusData", vo);
	}

	public int updateAccessCertificationStatusData(AccessLoginVO vo) throws Exception {
		return update("login.updateAccessCertificationStatusData", vo);
	}

	public int deleteAccessCertificationStatusData(AccessLoginVO vo) throws Exception {
		return delete("login.deleteAccessCertificationStatusData", vo);
	}

	public int insertAccessCertificationLogData(AccessLoginVO vo) throws Exception {
		return delete("login.insertAccessCertificationLogData", vo);
	}


	/**
	 * OTP인증정보 조회
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	public EgovMap selectOTPAuth(UserVO vo) throws Exception {
		return selectOne("login.selectOTPAuth", vo);
	}

	/**
	 * OTP인증정보 등록
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	public int insertOTPAuth(EgovMap vo) throws Exception {
		return insert("login.insertOTPAuth", vo);
	}

	/**
	 * OTP인증정보 인증요청일시 업데이트
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	public int updateOTPReqDt(EgovMap vo) throws Exception {
		return insert("login.updateOTPReqDt", vo);
	}

	public String selectAvailableTokenYn(AccessLoginVO vo) throws Exception {
		return selectOne("login.selectAvailableTokenYn", vo);
	}

	public EgovMap selectLoginProviderData(UserVO vo) throws Exception {
		return selectOne("login.selectLoginProviderData", vo);
	}

	public EgovMap selectLoginSuccessHandlerData(UserVO vo) throws Exception {
		return selectOne("login.selectLoginSuccessHandlerData", vo);
	}

    public int selectCustomMenuCnt(UserVO userVO) throws Exception {
		return selectOne("login.selectCustomMenuCnt", userVO);
    }
    public String selectAuth(UserVO userVO) throws Exception {
    	return selectOne("login.selectAuth", userVO);
    }
    public String selectIpList(UserVO userVO) throws Exception {
    	return selectOne("login.selectIpList", userVO);
    }
    public List<String> beforePwdList(UserVO userVO) throws Exception {
    	return selectList("login.beforePwdList", userVO);
    }
    public String pwCnt(UserVO userVO) throws Exception {
    	return selectOne("login.pwCnt", userVO);
    }
}
