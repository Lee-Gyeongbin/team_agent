/*************************************************************************
* CLASS 명	: CodeVO
* 작 업 자	: kimyh
* 작 업 일	: 2017년 11월 21일
* 기	능	: 공통코드 VO
* ---------------------------- 변 경 이 력 --------------------------------
* 번호	작 업 자	작	업	일			변 경 내 용				비고
* ----	---------	-----------------	---------------------	--------
*	1	kimyh		2017년 11월 21일		최 초 작 업
**************************************************************************/
package kr.teamagent.common.util.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CodeVO extends CommonVO {
	private static final long serialVersionUID = -1338921532226087060L;
	
	//코드그룹
	private String codeGrpId;
	private List<String> codeGrpIds;
	private String codeGrpNm;
	private List<String> codeGrpNms;
	private String codeGrpNmsStr;

	private String findCodeGrpId;

	//코드
	private String codeId;
	private List<String> codeIds;
	//코드명
	private String codeNm;
	private List<String> codeNms;
	private String codeNmsStr;
	private String codeDefId;
	private List<String> codeDefIds;
	private String codeDefNm;

	private String cnt;
	
	//기준년도
	private List<String> yearYns;

	//정렬순서
	private String sortOrder;
	private List<String> sortOrders;

	//비고
	private String content;

	//기타1
	private String etc1;
	private List<String> etc1s;

	//기타2
	private String etc2;

	private String useYn;
	private List<String> useYns;
	
	private String yearYn;
	private String checkIds;

	private String newCompId;
	
	private String createDt;

	private String deleteDt;

	private String[] multiLang_lang;
	private String[] multiLang_codeGrpNm;
	private String[] multiLang_codeNm;
	
	private String langNm;
	
	private String targetCompId;
	private String dbId;
	private String evalCycleCd;
}
