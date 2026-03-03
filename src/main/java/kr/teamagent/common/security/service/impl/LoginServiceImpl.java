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
import kr.teamagent.common.security.service.AccessLoginVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.SessionUtil;

@Service
public class LoginServiceImpl extends EgovAbstractServiceImpl {
	@Resource
	LoginDAO loginDAO;

	@Autowired
	CommonServiceImpl commonServiceImpl;

    @Autowired
    LoginServiceImpl loginService;

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

	public int insertAccessCertificationFailData(AccessLoginVO vo) throws Exception {
		int resultCnt = 0;
		int count = Integer.parseInt(CommonUtil.nvl(vo.getFailCount(),"0"));

		if(count == 1) {
			resultCnt += loginDAO.insertAccessCertificationStatusData(vo);
		}else {
			resultCnt += loginDAO.updateAccessCertificationStatusData(vo);
		}

		resultCnt += loginDAO.insertAccessCertificationLogData(vo);

		return  resultCnt;
	}

	public int insertAccessCertificationSuccessData(AccessLoginVO vo) throws Exception {
		int resultCnt = 0;
		resultCnt += loginDAO.deleteAccessCertificationStatusData(vo);
		resultCnt += loginDAO.insertAccessCertificationLogData(vo);
		return  resultCnt;
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
    

	/**
	 * 로그인이력 최근 로그인성공 항목을 조회하여 OTP 인증상태, Ip Check 상태 업데이트
	 * @param	UserVO vo
	 * @return	int
	 * @throws	Exception
	 */
    public int insertAccessCertificationOtpData(UserVO vo, String loginStatus, String status) throws Exception {
		AccessLoginVO accessLoginVO = new AccessLoginVO();
		accessLoginVO.setCompId(vo.getCompId());
		accessLoginVO.setUserId(vo.getUserId());
		accessLoginVO.setInType("password");					// loginType sso/password;
		accessLoginVO.setClientIp(vo.getIp());
		accessLoginVO.setStatus(loginStatus);
		accessLoginVO.setOtpStatus(status);
		accessLoginVO.setFailCount("0");
		accessLoginVO.setIpStatus(SessionUtil.getAttribute("ipChkValue").toString());
		return loginService.insertAccessCertificationSuccessData(accessLoginVO);
    }
	/**
	 * 로그인이력 최근 로그인성공 항목을 조회하여 OTP 인증상태, Ip Check 상태 업데이트
	 * @param	UserVO vo
	 * @return	int
	 * @throws	Exception
	 */
    public int insertAccessCertificationOtpData(UserVO vo, String loginStatus, String status, int failCount) throws Exception {
		AccessLoginVO accessLoginVO = new AccessLoginVO();
		accessLoginVO.setCompId(vo.getCompId());
		accessLoginVO.setUserId(vo.getUserId());
		accessLoginVO.setInType("password");					// loginType sso/password;
		accessLoginVO.setClientIp(vo.getIp());
		accessLoginVO.setStatus(loginStatus);
		accessLoginVO.setOtpStatus(status);
    	accessLoginVO.setFailCount(failCount + "");
		accessLoginVO.setIpStatus(SessionUtil.getAttribute("ipChkValue").toString());
		return loginService.insertAccessCertificationSuccessData(accessLoginVO);
    }

}
