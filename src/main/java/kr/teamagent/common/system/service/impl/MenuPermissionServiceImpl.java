package kr.teamagent.common.system.service.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import kr.teamagent.common.CommonVO;
import kr.teamagent.common.util.SessionUtil;

/** 로그인 사용자의 메뉴별 권한(조회·기능) 조회 — 세션에 한 번 적재하고 재로그인 때 다시 읽는다 */
@Service
public class MenuPermissionServiceImpl extends EgovAbstractServiceImpl {

    private static final String SESSION_PERM = "menuPerm";
    private static final String SESSION_PERM_USER = "menuPermUserId";

    @Autowired
    private CommonDAO commonDAO;

    /** 메뉴ID → 권한. 행이 없는 메뉴는 권한 없음 */
    public Map<String, CommonVO.MenuPermVO> getPermissionMap() throws Exception {
        String userId = SessionUtil.getUserId();
        if (userId == null || userId.isEmpty()) {
            return Collections.emptyMap();
        }
        RequestAttributes attrs = RequestContextHolder.currentRequestAttributes();
        @SuppressWarnings("unchecked")
        Map<String, CommonVO.MenuPermVO> cached = (Map<String, CommonVO.MenuPermVO>) attrs.getAttribute(SESSION_PERM, RequestAttributes.SCOPE_SESSION);
        if (cached != null && userId.equals(attrs.getAttribute(SESSION_PERM_USER, RequestAttributes.SCOPE_SESSION))) {
            return cached;
        }
        Map<String, CommonVO.MenuPermVO> loaded = new LinkedHashMap<>();
        for (CommonVO.MenuPermVO perm : commonDAO.selectUserMenuPermList(userId)) {
            loaded.put(perm.getMenuId(), perm);
        }
        attrs.setAttribute(SESSION_PERM, loaded, RequestAttributes.SCOPE_SESSION);
        attrs.setAttribute(SESSION_PERM_USER, userId, RequestAttributes.SCOPE_SESSION);
        return loaded;
    }

    /** 화면 경로 기준 조회 권한 */
    public boolean canRead(String srcPath) throws Exception {
        CommonVO.MenuPermVO perm = findBySrcPath(srcPath);
        return perm != null && "Y".equals(perm.getReadYn());
    }

    /** 화면 경로 기준 기능(등록·수정·삭제·엑셀) 권한 */
    public boolean canFunc(String srcPath) throws Exception {
        CommonVO.MenuPermVO perm = findBySrcPath(srcPath);
        return perm != null && "Y".equals(perm.getFuncYn());
    }

    private CommonVO.MenuPermVO findBySrcPath(String srcPath) throws Exception {
        for (CommonVO.MenuPermVO perm : getPermissionMap().values()) {
            if (srcPath.equals(perm.getSrcPath())) {
                return perm;
            }
        }
        return null;
    }
}
