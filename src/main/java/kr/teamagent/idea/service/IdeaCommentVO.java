package kr.teamagent.idea.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IdeaCommentVO {
    private String commentId;
    private String ideaId;
    private String parentId;
    private String content;
    private String createId;
    private String createNm;
    private String createDt;
    private String modifyId;
    private int depth;
    private int sortOrd;
    private boolean richText;
}
