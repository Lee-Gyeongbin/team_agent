/*************************************************************************
* CLASS 명	: LangVO
* 작 업 자	: kimyh
* 작 업 일	: 2017. 11. 27.
* 기	능	: 언어 VO
* ---------------------------- 변 경 이 력 --------------------------------
* 번호	작 업 자		작	업	일			변 경 내 용				비고
* ----	---------	----------------	---------------------	-----------
*	1	kimyh		2017. 11. 27.		최 초 작 업
**************************************************************************/

package kr.teamagent.common.system.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class LangVO extends CommonVO {
	private static final long serialVersionUID = -2759512363912012167L;
	private String lang;
	private String langNm;
}
