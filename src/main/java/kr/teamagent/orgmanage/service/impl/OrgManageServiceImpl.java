package kr.teamagent.orgmanage.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.orgmanage.service.OrgManageVO;

@Service
public class OrgManageServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private OrgManageDAO orgManageDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private FileServiceImpl fileService;

    /**
     * 조직 목록 조회
     * @param searchVO
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectOrgList(OrgManageVO searchVO) throws Exception {
        return orgManageDAO.selectOrgList(searchVO);
    }

    /**
     * 사용자 목록 조회 (동일 ORG_ID)
     * @param orgId
     * @return list
     * @throws Exception
     */
    public List<OrgManageVO> selectUserList(String orgId) throws Exception {
        List<OrgManageVO> userList = orgManageDAO.selectUserList(orgId);
        for (OrgManageVO user : userList) {
            String profileImgPath = user.getProfileImgPath();
            if (CommonUtil.isEmpty(profileImgPath)) {
                user.setProfileImgUrl("");
                continue;
            }
            try {
                Map<String, Object> raw = fileService.createViewPresignedUrlForStorageObject(toFileVO(profileImgPath));
                user.setProfileImgUrl(extractImageUrl(raw));
            } catch (Exception e) {
                user.setProfileImgUrl("");
            }
        }
        return userList;
    }

    private static FileVO toFileVO(String profileImgPath) {
        FileVO fileVO = new FileVO();
        String trimmedPath = profileImgPath.trim();
        fileVO.setFilePath(trimmedPath);
        int slashIndex = trimmedPath.lastIndexOf('/');
        fileVO.setFileName(slashIndex >= 0 ? trimmedPath.substring(slashIndex + 1) : trimmedPath);
        return fileVO;
    }

    private static String extractImageUrl(Map<String, Object> raw) {
        if (raw == null) {
            return "";
        }
        Object viewType = raw.get("viewType");
        if (viewType == null || !"IMAGE".equals(String.valueOf(viewType))) {
            return "";
        }
        Object url = raw.get("url");
        return url == null ? "" : String.valueOf(url);
    }

    /**
     * 조직 등록
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int insertOrg(OrgManageVO orgManageVO) throws Exception {
        orgManageVO.setOrgId(keyGenerate.generateTableKey("ORG", "TB_ORG", "ORG_ID", 3));
        applyOrgHierarchyValues(orgManageVO);

        if (orgManageVO.getUseYn() == null || orgManageVO.getUseYn().trim().isEmpty()) {
            orgManageVO.setUseYn("Y");
        }
        return orgManageDAO.insertOrg(orgManageVO);
    }

    /**
     * 조직 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int updateOrg(OrgManageVO orgManageVO) throws Exception {
        applyOrgHierarchyValues(orgManageVO);
        return orgManageDAO.updateOrg(orgManageVO);
    }

    /**
     * 조직 정렬순서 수정
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateOrgSortOrder(OrgManageVO orgManageVO) throws Exception {
        if (orgManageVO == null || CommonUtil.isEmpty(orgManageVO.getOrgId())) {
            return 0;
        }

        if (CommonUtil.isEmpty(orgManageVO.getSortOrder())) {
            return 0;
        }

        OrgManageVO currentOrg = orgManageDAO.selectOrgByOrgId(orgManageVO.getOrgId());
        if (currentOrg == null) {
            return 0;
        }
        String currentParentOrgId = normalizeParentOrgId(currentOrg.getParentOrgId());

        return reindexSiblingSortOrder(currentParentOrgId, orgManageVO.getOrgId(), orgManageVO.getSortOrder());
    }

    /**
     * 조직 삭제
     * @param orgManageVO
     * @return
     * @throws Exception
     */
    public int deleteOrg(OrgManageVO orgManageVO) throws Exception {
        return orgManageDAO.deleteOrg(orgManageVO);
    }

    /**
     * 조직 계층 값 적용
     * @param orgManageVO
     * @throws Exception
     */
    private void applyParentOrgAndLevel(OrgManageVO orgManageVO) throws Exception {
        String parentOrgId = normalizeParentOrgId(orgManageVO.getParentOrgId());
        orgManageVO.setParentOrgId(parentOrgId);

        int orgLevel = 1;
        if (parentOrgId != null) {
            OrgManageVO parentOrg = orgManageDAO.selectOrgByOrgId(parentOrgId);
            String parentOrgLevel = parentOrg != null ? parentOrg.getOrgLevel() : null;
            int parentLevel = 0;
            if (parentOrgLevel != null && !parentOrgLevel.trim().isEmpty()) {
                parentLevel = Integer.parseInt(parentOrgLevel);
            }
            orgLevel = parentLevel + 1;
        }
        orgManageVO.setOrgLevel(String.valueOf(orgLevel));
    }

    /**
     * 신규 조직 등록 시 계층 값 적용
     * @param orgManageVO
     * @throws Exception
     */
    private void applyOrgHierarchyValues(OrgManageVO orgManageVO) throws Exception {
        applyParentOrgAndLevel(orgManageVO);
        String parentOrgId = orgManageVO.getParentOrgId();
        int maxSortOrder;
        if (parentOrgId != null) {
            maxSortOrder = orgManageDAO.selectMaxSortOrderByParentOrgId(parentOrgId);
        } else {
            maxSortOrder = orgManageDAO.selectMaxSortOrderByTopLevel();
        }
        orgManageVO.setSortOrder(String.valueOf(maxSortOrder + 1));
    }

    /**
     * 부모 조직 ID 정규화
     * @param parentOrgId
     * @return
     */
    private String normalizeParentOrgId(String parentOrgId) {
        if (parentOrgId == null) {
            return null;
        }
        String trimmedParentOrgId = parentOrgId.trim();
        return trimmedParentOrgId.isEmpty() ? null : trimmedParentOrgId;
    }

    /**
     * 동일 부모 조직 내 정렬순서 재조정.
     * {@code sortOrder}는 동일 부모 형제 중 1부터 시작하는 목표 순번으로 해석한다.
     *
     * @param parentOrgId
     * @param movedOrgId 이동 대상 ORG_ID
     * @param desiredOneBasedSortOrder 목표 순번 (1..형제수)
     * @return
     * @throws Exception
     */
    private int reindexSiblingSortOrder(String parentOrgId, String movedOrgId, String desiredOneBasedSortOrder)
            throws Exception {
        List<OrgManageVO> siblingList = orgManageDAO.selectParentOrgId(parentOrgId);
        if (siblingList == null || siblingList.isEmpty()) {
            return 0;
        }
        List<OrgManageVO> ordered;
        if (!CommonUtil.isEmpty(movedOrgId) && !CommonUtil.isEmpty(desiredOneBasedSortOrder)) {
            ordered = orderSiblingsForMove(siblingList, movedOrgId, desiredOneBasedSortOrder);
        } else {
            ordered = new ArrayList<>(siblingList);
        }
        List<OrgManageVO.OrgSortOrderItem> items = new ArrayList<>();
        int sortOrder = 1;
        for (OrgManageVO sibling : ordered) {
            OrgManageVO.OrgSortOrderItem item = new OrgManageVO.OrgSortOrderItem();
            item.setOrgId(sibling.getOrgId());
            item.setSortOrder(String.valueOf(sortOrder++));
            items.add(item);
        }
        OrgManageVO batch = new OrgManageVO();
        batch.setParentOrgId(parentOrgId);
        batch.setItems(items);
        return orgManageDAO.updateSiblingSortOrderBatch(batch);
    }

    /**
     * 현재 DB 정렬 순서를 유지한 채 {@code movedOrgId}만 목표 순번 위치로 옮긴 목록을 만든다.
     * 목표 순번은 1-based이며 형제 수 범위로 보정한다.
     */
    private static List<OrgManageVO> orderSiblingsForMove(List<OrgManageVO> siblingList, String movedOrgId,
            String desiredOneBasedSortOrder) {
        int n = siblingList.size();
        int targetPos;
        try {
            targetPos = Integer.parseInt(desiredOneBasedSortOrder.trim());
        } catch (NumberFormatException e) {
            return new ArrayList<>(siblingList);
        }
        if (targetPos < 1) {
            targetPos = 1;
        }
        if (targetPos > n) {
            targetPos = n;
        }
        OrgManageVO moved = null;
        List<OrgManageVO> rest = new ArrayList<>();
        for (OrgManageVO s : siblingList) {
            if (Objects.equals(movedOrgId, s.getOrgId())) {
                moved = s;
            } else {
                rest.add(s);
            }
        }
        if (moved == null) {
            return new ArrayList<>(siblingList);
        }
        int insertIdx = targetPos - 1;
        if (insertIdx > rest.size()) {
            insertIdx = rest.size();
        }
        if (insertIdx < 0) {
            insertIdx = 0;
        }
        rest.add(insertIdx, moved);
        return rest;
    }
}
