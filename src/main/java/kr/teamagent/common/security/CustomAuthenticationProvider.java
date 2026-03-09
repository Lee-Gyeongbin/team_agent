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

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
            String authToken = CommonUtil.nullToBlank(request.getParameter("ssoId"));
            String baseUrl = CommonUtil.nullToBlank(request.getParameter("baseUrl"));
            log.info("=====> ssoId: " + authToken+ ", baseUrl: " + baseUrl);
            String accessLoginInType = "password";
            String accessStatus = "S";

            String userType = String.valueOf(SessionUtil.getAttribute("userType"));
            String masterDbId = null;

            boolean isFormLogin = CommonUtil.isEmpty(authToken) ? true : false;

            /*
             * [주석처리] selectAuth - COM_ADMIN 테이블 조회
             * UserVO tmpVO = new UserVO();
             * tmpVO.setUserId(userId);
             * String auth = loginService.selectAuth(tmpVO);
             */

            //SSO 로그인
            if (!isFormLogin) {
                accessLoginInType = "sso";
                BufferedReader in = null;
                HttpURLConnection con = null;
                int responseCode =0;
                try {
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

                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    InputSource is = new InputSource(new StringReader(content.toString()));
                    Document document = builder.parse(is);
                    NodeList nodeList = document.getElementsByTagName("sabun");

                    userId = nodeList.item(0).getTextContent();
                    if(userId.startsWith("01")) {
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
            SessionUtil.setAttribute("accessError", null);
            SessionUtil.setAttribute("accessErrorCnt", null);
            EgovMap loginProviderData = loginService.selectLoginProviderData(userVO);

            /*
             * [주석처리] 인증 시도 횟수 - COM_ACCESS_AUTH 테이블 없음
             * int authStatusCount = Integer.valueOf(String.valueOf(loginProviderData.get("authStatusCount")));
             * if(authStatusCount >= Integer.parseInt(PropertyUtil.getProperty("auth.accessCount"))) {
             *     SessionUtil.setAttribute("accessError", "accessDenied");
             *     return null;
             * }
             */

            UserVO user = (UserVO)loginProviderData.get("userVO");
            if(user == null){
                SessionUtil.setAttribute("accessError", "noUser");
                return null;
            }

            if(!"Y".equals(user.getUseYn())) {
                SessionUtil.setAttribute("accessError", "inActiveUser");
                return null;
            }
            if(isFormLogin) {
                if(!passwordEncoder.matches(password, user.getPasswd())){
                    SessionUtil.setAttribute("accessError", "passwordFail");
                    /*
                     * [주석처리] 실패 기록 - COM_ACCESS_AUTH 테이블 없음
                     * AccessLoginVO accessLoginVO = new AccessLoginVO();
                     * accessLoginVO.setCompId(compId);
                     * accessLoginVO.setUserId(userId);
                     * accessLoginVO.setInType(accessLoginInType);
                     * accessLoginVO.setClientIp(CommonUtil.getUserIP(request));
                     * accessLoginVO.setStatus(accessStatus);
                     * accessLoginVO.setFailCount(String.valueOf(authStatusCount));
                     * loginService.insertAccessCertificationFailData(accessLoginVO);
                     */
                    return null;
                }
            }else{
                user.setAuthMethod("token");
            }

            // 메인화면 진입 시 로그인 init 여부 확인
            SessionUtil.setAttribute("loginInitChk", "Y");

            /*로그인 일시 저장 하거나 관리자가 사용자 화면 접근 시 자기 session 에 저장*/
            //SessionUtil.setAttribute("compId", CommonUtil.nullToBlank(user.getCompId()));
            //SessionUtil.setAttribute("userId", CommonUtil.nullToBlank(user.getUserId()));
            //SessionUtil.setAttribute("connectionId", CommonUtil.nullToBlank(user.getConnectionId()));

            /*
             * [주석처리] 권한 목록 - COM_ADMIN, V_COM_CODE 테이블 조회
             * List<String> adminGubunList = (List<String>)loginProviderData.get("adminGubunList");
             * user.setAdminGubunList(adminGubunList);
             * for(String admingubun : adminGubunList) { grantedAuthList.add(new SimpleGrantedAuthority(admingubun)); }
             */
            ArrayList<GrantedAuthority> grantedAuthList = new ArrayList<GrantedAuthority>();
            grantedAuthList.add(new SimpleGrantedAuthority("ROLE_USER"));

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
