/**
 * @Class Name	:	LoginServiceImpl.java
 * @Description	:	로그인 Service
 * @author	:	kimyh
 * @date	:	2017-11-17
 */
package kr.teamagent.common.security.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.exception.DuplicateUserIdException;
import kr.teamagent.common.security.service.AccessLoginVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.SessionUtil;

@Service
public class LoginServiceImpl extends EgovAbstractServiceImpl {
	@Resource
	LoginDAO loginDAO;

	@Autowired
	CommonServiceImpl commonServiceImpl;

	// 사용자 조회
	public UserVO selectUser(UserVO vo) throws Exception {
		EgovMap emap = new EgovMap();
		emap.put("paramCompId",vo.getCompId());
		vo.setDbId(commonServiceImpl.selectDbId(emap));
		return loginDAO.selectUser(vo);
	}
	/*
	// 권한 목록 조회
	public List<String> selectAdminGubunList(UserVO vo) throws Exception {
		return loginDAO.selectAdminGubunList(vo);
	}

	// 회사의 사용언어 목록 조회
	public List<LangVO> selectLangList(UserVO vo) throws Exception {
		return loginDAO.selectLangList(vo);
	}

	public String selectIsOnlyInsa(UserVO vo) throws Exception{
		return loginDAO.selectIsOnlyInsa(vo);
	}*/

	/**
	 * 사용자의 연도별 성과조직 목록 조회
	 * @param	UserVO vo
	 * @return	List<EgovMap>
	 * @throws	Exception
	 */
	/*public List<EgovMap> selectUserScDeptList(UserVO vo) throws Exception {
		return loginDAO.selectUserScDeptList(vo);
	}*/

	/**
	 * 서비스 사용여부 조회
	 * @param	UserVO vo
	 * @return	String
	 * @throws	Exception
	 */
	/*public String selectServiceUseYn(UserVO vo) throws Exception {
		return loginDAO.selectServiceUseYn(vo);
	}*/

	/**
	 * 인증시도 회수 조회
	 * @param	UserVO vo
	 * @return	String
	 * @throws	Exception
	 */
	/*public int selectAuthStatusCount(UserVO vo) throws Exception {
		return loginDAO.selectAuthStatusCount(vo);
	}*/

	/**
	 * 인증시도 회수 조회
	 * @param	UserVO vo
	 * @return	String
	 * @throws	Exception
	 */
	/*public int selectAuthOtpStatusCount(UserVO vo) throws Exception {
		return loginDAO.selectAuthOtpStatusCount(vo);
	}*/

	public int recordLoginFail(UserVO userVO, AccessLoginVO auditVO) throws Exception {
		int resultCnt = 0;
		resultCnt += loginDAO.updateLoginFailCnt(userVO);
		auditVO.setDbId(userVO.getDbId());
		resultCnt += loginDAO.insertAuditLogin(auditVO);
		return resultCnt;
	}

	public int recordLoginSuccess(UserVO userVO, AccessLoginVO auditVO) throws Exception {
		int resultCnt = 0;
		resultCnt += loginDAO.resetLoginFailCnt(userVO);
		auditVO.setDbId(userVO.getDbId());
		resultCnt += loginDAO.insertAuditLogin(auditVO);
		return resultCnt;
	}

	public int selectLoginFailCnt(UserVO vo) throws Exception {
		return loginDAO.selectLoginFailCnt(vo);
	}

	public int lockUser(UserVO vo) throws Exception {
		return loginDAO.lockUser(vo);
	}

	/**
	 * 회원가입 - 신규 사용자 등록
	 * @param user UserVO (userId, userNm, passwd(암호화됨), email, phone, orgId, compId, dbId, masterDbId 필수)
	 * @throws DuplicateUserIdException 이미 존재하는 userId인 경우
	 * @throws Exception
	 */
	public void signupUser(UserVO user) throws Exception {
		UserVO existing = loginDAO.selectUser(user);
		if (existing != null) {
			throw new DuplicateUserIdException("이미 존재하는 아이디입니다.");
		}
		int rows = loginDAO.insertUser(user);
		if (rows != 1) {
			throw new Exception("회원가입 처리에 실패했습니다.");
		}
	}


	/**
	 * OTP인증정보 조회
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	/*public EgovMap selectOTPAuth(UserVO vo) throws Exception {

		return loginDAO.selectOTPAuth(vo);
	}*/

	/**
	 * OTP인증정보 등록
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	/*public int insertOTPAuth(EgovMap vo) throws Exception {
		return loginDAO.insertOTPAuth(vo);
	}*/

	/**
	 * OTP인증정보 업데이트
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	/*public int updateOTPReqDt(EgovMap vo) throws Exception {
		return loginDAO.updateOTPReqDt(vo);
	}*/

	/**
	 * 사용 가능한 토큰여부
	 * @param vo
	 * @return
	 * @throws Exception
	 */
	public String selectAvailableTokenYn(AccessLoginVO vo) throws Exception {
		return  loginDAO.selectAvailableTokenYn(vo);
	}

	public EgovMap selectLoginProviderData(UserVO vo) throws Exception {
		EgovMap emap = new EgovMap();
		emap.put("paramCompId",vo.getCompId());
		vo.setDbId(vo.getMasterDbId());
		return  loginDAO.selectLoginProviderData(vo);
	}

	public EgovMap selectLoginSuccessHandlerData(UserVO vo) throws Exception {
		return  loginDAO.selectLoginSuccessHandlerData(vo);
	}

    public int selectCustomMenuCnt(UserVO userVO) throws Exception {
		return loginDAO.selectCustomMenuCnt(userVO);
    }
    public String selectAuth(UserVO userVO) throws Exception {
    	return loginDAO.selectAuth(userVO);
    }
    public String selectIpList(UserVO userVO) throws Exception {
    	return loginDAO.selectIpList(userVO);
    }
    public List<String> beforePwdList(UserVO vo) throws Exception {

    	String pwCnt = loginDAO.pwCnt(vo);
    	vo.setLimit(pwCnt);

		return loginDAO.beforePwdList(vo);
	}
    public String selectP(UserVO userVO) throws Exception {
    	return loginDAO.selectIpList(userVO);
    }
    public String selectPwCnt(UserVO userVO) throws Exception {
    	return loginDAO.pwCnt(userVO);
    }
    

	public int recordOtpResult(UserVO vo, String result, String otpStatus) throws Exception {
		AccessLoginVO auditVO = new AccessLoginVO();
		auditVO.setUserId(vo.getUserId());
		auditVO.setLoginTp("LOGIN");
		auditVO.setIpAddr(vo.getIp());
		auditVO.setResult(result);
		auditVO.setOtpStatus(otpStatus);
		auditVO.setFailCnt(0);
		auditVO.setIpStatus(SessionUtil.getAttribute("ipChkValue").toString());
		return recordLoginSuccess(vo, auditVO);
    }

	public int recordOtpResult(UserVO vo, String result, String otpStatus, int failCnt) throws Exception {
		AccessLoginVO auditVO = new AccessLoginVO();
		auditVO.setUserId(vo.getUserId());
		auditVO.setLoginTp("LOGIN");
		auditVO.setIpAddr(vo.getIp());
		auditVO.setResult(result);
		auditVO.setOtpStatus(otpStatus);
		auditVO.setFailCnt(failCnt);
		auditVO.setIpStatus(SessionUtil.getAttribute("ipChkValue").toString());
		return recordLoginSuccess(vo, auditVO);
    }

}
