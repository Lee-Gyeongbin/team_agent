package kr.teamagent.common.util.service;

import java.util.List;

import kr.teamagent.common.CommonVO;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FileVO extends CommonVO {
    private static final long serialVersionUID = 1L;

    private String docId;

    /** TB_DOC_FILE.PK */
    private String docFileId;
    /** 선택 삭제용 TB_DOC_FILE.PK 목록 */
    private List<String> docFileIdList;
    /** TB_DOC_FILE.FILE_ORD */
    private Integer fileOrd;

    /** NCP 객체 삭제 등, docId 다건 요청용 */
    private List<String> docIdList;
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

    /** tb_chat_file 채팅 시 첨부파일 */
    // 저장 파일명
    private String storeFileName;
    // 저장 경로
    private String storeFilePath;
    // 스토리지 파일 존재 여부
    private String fileExistYn;
    // 삭제 예정일시
    private String expireDt;
    // 스토리지 파일 삭제일시
    private String fileDelDt;
}
