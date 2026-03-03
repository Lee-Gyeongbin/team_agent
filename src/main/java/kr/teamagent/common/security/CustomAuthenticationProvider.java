package kr.teamagent.common.security;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.egovframe.rte.psl.dataaccess.util.EgovMap;
import kr.teamagent.common.security.service.AccessLoginVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;

/*import org.springframework.security.core.session.SessionRegistry;*/

@Component
public class CustomAuthenticationProvider implements AuthenticationProvider {
    public final Logger log = LoggerFactory.getLogger(this.getClass());

    // IP 체크여부 체크
    private final String IP_CHK_TRUE = "001";

    @Autowired
    LoginServiceImpl loginService;

    @Autowired
    StandardPasswordEncoder passwordEncoder;

    //@Autowired
    //private RoutingDataSource routingDataSource;

    @Autowired
     private CommonServiceImpl commonService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        log.debug("\n\n#### CustomAuthenticationProvider.authenticate ###");
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            String compId = request.getParameter("compId");
            String userId = CommonUtil.nullToBlank(request.getParameter("userId"));
            String password = CommonUtil.nullToBlank(request.getParameter("password"));
            String authToken = CommonUtil.nullToBlank(request.getParameter("ssoId")); //nice ssoId
            String baseUrl = CommonUtil.nullToBlank(request.getParameter("baseUrl")); //Tocken url
            log.info("=====> ssoId: " + authToken+ ", baseUrl: " + baseUrl);
            String accessLoginInType = "password"; // loginType sso/password
            String accessStatus = "S"; // accessType 공통코드-523:  S:성공, F:실패, L:실패/잠금


            String userType = String.valueOf(SessionUtil.getAttribute("userType"));
            String masterDbId = null;

            boolean isFormLogin = CommonUtil.isEmpty(authToken) ? true : false;
//			log.debug("isFormLogin : " + isFormLogin);

            //전체관리자 IP확인 후 팅겨 낸다.


            UserVO tmpVO = new UserVO();
            tmpVO.setUserId(userId);
            String auth = loginService.selectAuth(tmpVO);

            //System.out.println("==============================");
            //System.out.println(auth);
            //System.out.println(!CommonUtil.isEmpty(auth));

//            if(!CommonUtil.isEmpty(auth)) {
//                java.net.InetAddress myPC   =   java.net.InetAddress.getLocalHost();
//                //System.out.println("==============================");
//                //System.out.println(myPC);
//                String allowIp = loginService.selectIpList(tmpVO);
//                //System.out.println("==============================");
//                //System.out.println(allowIp);
//                if(!allowIp.equals(myPC.getHostAddress()) && !CommonUtil.isEmpty(allowIp) && !allowIp.equals("*")) {
//                    //System.out.println("==============================");
//                    SessionUtil.setAttribute("accessError", "accessAdminDenied");
//                    return null;
//                }
//            }

            //SSO 로그인
            if (!isFormLogin) {
                accessLoginInType = "sso";
                BufferedReader in = null;
                HttpURLConnection con = null;
                int responseCode =0;
                try {
                    // 콜백 데이터(xml)에서 sabun을 base64 암호화하지 않는 것으로 확인. jsp에서 토큰정보를 가져오지 않음.
                    StringBuilder content = new StringBuilder();

                    URL url = new URL(baseUrl);
                    con = (HttpURLConnection)url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    con.setDoOutput(true);

                    DataOutputStream out = new DataOutputStream(con.getOutputStream());
                    out.writeBytes("ssoId="+authToken);
                    out.flush();
                    out.close();

                    responseCode = con.getResponseCode();

                    in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                    String inputLine;

                    while ((inputLine = in.readLine()) != null) {
                        content.append(inputLine);
                    }

                    // XML 데이터를 파싱하여 <sabun> 값을 추출
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    InputSource is = new InputSource(new StringReader(content.toString()));
                    Document document = builder.parse(is);
                    NodeList nodeList = document.getElementsByTagName("sabun");

                    userId = nodeList.item(0).getTextContent();
                    if(userId.startsWith("01")) {// 회사코드 제거
                        userId = userId.substring(2);
                    }

                }catch(MalformedURLException e) {
                    e.printStackTrace();
                    log.error("MalformedURLException error : "+e.getCause());
                    return null;
                }catch(UnknownHostException e) {
                    e.printStackTrace();
                    log.error("UnknownHostException error : "+e.getCause());
                    return null;
                }catch(SocketTimeoutException e) {
                    e.printStackTrace();
                    log.error("SocketTimeoutException error : "+e.getCause());
                    return null;
                }catch(IOException e) {
                    e.printStackTrace();
                    log.error("IOException error : "+e.getCause());
                    return null;
                }catch(Exception e) {
                    e.printStackTrace();
                    log.error("Exception error : "+e.getCause());
                    return null;
                }finally {
                    if(in != null) {
                        in.close();
                    }
                    if(con != null) {
                        con.disconnect();
                    }
                    SessionUtil.setAttribute("userId", userId);
                    log.info("=====> sso responseCode: " + responseCode);
                }
            }
            UserVO userVO = new UserVO();
            userVO.setUserId(userId);
            userVO.setIp(CommonUtil.getUserIP(request));

            if(userType.equals("ADMIN")) {
                compId = PropertyUtil.getProperty("Master.CompId");
                masterDbId = PropertyUtil.getProperty("Globals.Master.db");
            }else {
                compId = PropertyUtil.getProperty("User.CompId");
                masterDbId = PropertyUtil.getProperty("Globals.User.db");
            }
//			log.debug("isFormLogin : " + isFormLogin + " /compId: " + compId  + " /userType: " +  userType + " /masterDbId: " + masterDbId);

            userVO.setCompId(compId);
            userVO.setMasterDbId(masterDbId);
            userVO.setTargetDbId(masterDbId);

            /*
             * 로그아웃 안한상태에서 로그인으로 다른회사 id 로 로그인하면 기존 compId 세션이 남아있어  로그인 불가상태가 됨.
             * compId 세션을 null로 초기화 하고, compId aop에서는 세션에 compId 가 값이 있을때만 set 해주도록 수정함.
             */
            SessionUtil.setAttribute("compId", null);
            SessionUtil.setAttribute("connectionId", null);
            //-----------로그인 실패 횟수 체크-----------//
            SessionUtil.setAttribute("accessError", null);
            SessionUtil.setAttribute("accessErrorCnt", null);
            EgovMap loginProviderData = loginService.selectLoginProviderData(userVO);
            int authStatusCount = Integer.valueOf(String.valueOf(loginProviderData.get("authStatusCount")));
            if(authStatusCount >= Integer.parseInt(PropertyUtil.getProperty("auth.accessCount"))) {
                SessionUtil.setAttribute("accessError", "accessDenied");

                return null;
            }
            UserVO user = (UserVO)loginProviderData.get("userVO");
            if(user == null){
                SessionUtil.setAttribute("accessError", "noUser");
                return null;
            }

            if(!"Y".equals(user.getBeingYn())) {
                SessionUtil.setAttribute("accessError", "inActiveUser");
                return null;
            }
            //로그인
            if(isFormLogin) {
                /*
                 * 일반 로그인
                 * 1. 계정이 존재하고 비밀번호가 맞으면 로그인 처리
                 */
                if(!passwordEncoder.matches(password, user.getPasswd())){
                    //비밀번호 인증실패 프로세스
                    ++authStatusCount;
                    if(String.valueOf(authStatusCount).equals(PropertyUtil.getProperty("auth.accessCount"))) {
                        SessionUtil.setAttribute("accessError", "accessDenied");
                        accessStatus = "L";
                    }else {
                        SessionUtil.setAttribute("accessError", "passwordFail");
                        SessionUtil.setAttribute("accessErrorCnt", authStatusCount);
                        accessStatus = "F";
                    }

                    /*if(CommonUtil.isProdServer()){*/
                        AccessLoginVO accessLoginVO = new AccessLoginVO();
                        accessLoginVO.setCompId(compId);
                        accessLoginVO.setUserId(userId);
                        accessLoginVO.setInType(accessLoginInType);
                        accessLoginVO.setClientIp(CommonUtil.getUserIP(request));
                        accessLoginVO.setStatus(accessStatus);
                        accessLoginVO.setFailCount(String.valueOf(authStatusCount));
                        loginService.insertAccessCertificationFailData(accessLoginVO);
                    /*}*/
                    return null;
                }
            }else{
                user.setAuthMethod("token");
                // 메인화면 진입 시 로그인 init 여부 확인
//				SessionUtil.setAttribute("loginInitChk", "N");
            }

            // 사용자 IP 체크여부 검사
            if ( StringUtils.isNotEmpty(user.getIpChk()) && IP_CHK_TRUE.equals(user.getIpChk()) ) {
                String clientIp = CommonUtil.getUserIP(request);
                String userRegisteredIp = user.getIpAddress();
                
                // 원래 코드 (서버 IP와 사용자 등록 IP 비교)
                // java.net.InetAddress myPC   =   java.net.InetAddress.getLocalHost();
                // if( StringUtils.isEmpty(user.getIpAddress()) || !myPC.getHostAddress().equals(user.getIpAddress()) ) {
                
                if( StringUtils.isEmpty(userRegisteredIp) || StringUtils.isEmpty(clientIp) ) {
                    SessionUtil.setAttribute("accessError", "ipChkFail");
                    return null;
                }
                
                // "/" 구분자로 여러 IP를 받을 수 있도록 처리
                // 등록된 IP 목록을 "/" 구분자로 분리하여 각 IP와 비교
                String[] registeredIpList = userRegisteredIp.split("/");
                boolean ipMatch = false;
                
                for(String registeredIp : registeredIpList) {
                    String trimmedIp = registeredIp.trim();
                    if(StringUtils.isNotEmpty(trimmedIp) && clientIp.equals(trimmedIp)) {
                        ipMatch = true;
                        break;
                    }
                }

				AccessLoginVO accessLoginVO = new AccessLoginVO();
				
                if(!ipMatch) {
                    SessionUtil.setAttribute("accessError", "ipChkFail");
//                    accessStatus = "F";
                    
                    // IP 체크 실패 등록
					accessLoginVO.setCompId(compId);
					accessLoginVO.setUserId(userId);
					accessLoginVO.setInType(accessLoginInType);
					accessLoginVO.setClientIp(CommonUtil.getUserIP(request));
					accessLoginVO.setStatus(accessStatus);
					accessLoginVO.setIpStatus("F");
					loginService.insertAccessCertificationSuccessData(accessLoginVO);
					
                    return null;
                } else {
                    SessionUtil.setAttribute("ipChkValue", "S");
                }
            } else {
                SessionUtil.setAttribute("ipChkValue", "E");
            }


            /*
             * 해당 고객사의 데이터소스가 있는 확인
             */
            /*int dataSourceResult = routingDataSource.checkDataSource(user.getConnectionId());
            if (dataSourceResult==-1){
                return null;
            }*/
            // 메인화면 진입 시 로그인 init 여부 확인
            SessionUtil.setAttribute("loginInitChk", "Y");

            /*로그인 일시 저장 하거나 관리자가 사용자 화면 접근 시 자기 session 에 저장*/
            //SessionUtil.setAttribute("compId", CommonUtil.nullToBlank(user.getCompId()));
            //SessionUtil.setAttribute("userId", CommonUtil.nullToBlank(user.getUserId()));
            //SessionUtil.setAttribute("connectionId", CommonUtil.nullToBlank(user.getConnectionId()));

            // 권한 목록
            List<String> adminGubunList = (List<String>)loginProviderData.get("adminGubunList");
            user.setAdminGubunList(adminGubunList);
            ArrayList<GrantedAuthority> grantedAuthList = new ArrayList<GrantedAuthority>();
            for(String admingubun : adminGubunList) {
                grantedAuthList.add(new SimpleGrantedAuthority(admingubun));
            }

//            /* 비밀번호 인증성공 프로세스*/
//            AccessLoginVO accessLoginVO = new AccessLoginVO();
//            accessLoginVO.setCompId(compId);
//            accessLoginVO.setUserId(userId);
//            accessLoginVO.setInType(accessLoginInType);
//            accessLoginVO.setClientIp(CommonUtil.getUserIP(request));
//            accessLoginVO.setStatus(accessStatus);
//            accessLoginVO.setFailCount("0");
//            loginService.insertAccessCertificationSuccessData(accessLoginVO);

            return new UsernamePasswordAuthenticationToken(user, user.getUserId(), grantedAuthList);
        } catch (SQLException sqe) {
            log.error("error : "+sqe.getCause());
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("error : "+e.getCause());
            return null;
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return true;
    }

}
