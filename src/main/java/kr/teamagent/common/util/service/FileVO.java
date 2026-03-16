package kr.teamagent.common.util.service;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FileVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String docId;
    private String docTitle;
    private String categoryId;
    private String author;
    private String secLvl;
    private String content;
    private String fileName;
    private String filePath;
    private String fileSize;
    private String fileType;
    private String keywords;
    private String refUrl;
    private String useYn;
    private String ragUseCnt;
    private String createDt;
    private String modifyDt;
}
