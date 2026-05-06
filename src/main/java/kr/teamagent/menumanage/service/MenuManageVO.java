package kr.teamagent.menumanage.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MenuManageVO extends CommonVO {

    private static final long serialVersionUID = 1L;

    private String menuId;
    private String menuName;
    private String parnMenuId;
    private Integer menuLevel;
    private String menuPath;
    private String isLeaf;
    private String srcPath;
    private String icon;
    private Integer sortOrd;
    private String useYn;
    private String description;
    private String createDt;
    private String modifyDt;

    @Getter
    @Setter
    public static class UpdateMenuOrderVO extends MenuManageVO {
        private List<MenuOrderSortItem> items;
    }

    @Getter
    @Setter
    public static class MenuOrderSortItem {
        private String menuId;
        private Integer sortOrd;
    }
}
