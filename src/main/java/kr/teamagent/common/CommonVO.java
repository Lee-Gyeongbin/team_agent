package kr.teamagent.common;
import egovframework.com.cmm.ComDefaultVO;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.regex.Pattern;

@Setter@Getter
@JsonIgnoreProperties({
	"findYear",		"findMon",
	"_csrf",			"layout",		"keys", "templateDbId", "templateCompId", "masterDbId", "targetDbId",
	"modifyDt",			"deleteDt",

	"searchCondition", "searchKeyword", "searchUseYn", "pageIndex", "pageUnit",
	"pageSize",	"firstIndex",	"lastIndex",	"recordCountPerPage",	"searchKeywordFrom",
	"searchKeywordTo"
})
public class CommonVO extends ComDefaultVO {
	private static final long serialVersionUID = 924024280774492177L;

	private String dbId;
	private String paramDbId;
	private String templateDbId;
	private String templateCompId;
	private String masterDbId;
	private String targetDbId;
	private String compId = "cmb";
	private String compNm;
	private String currentCompId;
	private String paramCompId;
	private String userId;
	private String userNm;
	private String lang;
	private String compLang;
	private String year;
	private String mon;
	private String useYn;
	private String isNew = "N";

	private String findYear;
	private String findMon;

	private String _csrf;
	private String layout = "simple";
	private List<String> keys;
	private String atchFileId;
	private String sortOrder;
	private String pgmId;

	private String userAuth;

	private String appNm;

	private String loginUserId;

	private List<CommonVO> dbList;

	public int getStartRow(){
		int page = 1;
		int rows = 20;
		return (page-1)*rows+1;
	}

	public int getEndRow(){
		int page = 1;
		int rows = 20;
		return page*rows;
	}

	public void setLayout(String layout) {
		this.layout = layout;
	}

	public String getLayout() {
		if("main".equals(layout) ||
				"popup".equals(layout) ||
				"simple".equals(layout)
				){
		return layout;
		}
		return "main";
	}

	public String getYear() {
		if(year != null){
			if(Pattern.matches("^[0-9]{4}$", year)){
				return year;
			}else{
				return null;
			}
		}else{
			return year;
		}
	}
	public void setYear(String year) {
		this.year = year;
	}

	public String getFindYear() {
		if(findYear != null){
			if(Pattern.matches("^[0-9]{4}$", findYear)){
				return findYear;
			}else{
				return null;
			}
		}else{
			return findYear;
		}
	}
	public void setFindYear(String findYear) {
		this.findYear = findYear;
	}
}
