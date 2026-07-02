package kr.teamagent.common.util;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.sl.usermodel.TextParagraph;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * PLANNER 에이전트 — PT(PPTX) 파일 생성 유틸.
 * LLM이 반환한 슬라이드 JSON을 Apache POI XMLSlideShow로 변환한다.
 *
 * <p>입력 JSON 구조:
 * <pre>
 * {
 *   "title": "발표 제목",
 *   "slides": [
 *     { "title": "슬라이드 제목", "content": ["항목1", "항목2"], "notes": "발표자 노트" },
 *     ...
 *   ]
 * }
 * </pre>
 */
public class PlannerPptxUtil {

    // 슬라이드 크기 (와이드스크린 16:9, EMU 단위)
    private static final int SLIDE_WIDTH_EMU  = 9144000;   // 254mm
    private static final int SLIDE_HEIGHT_EMU = 5143500;   // 142.875mm

    // 색상 팔레트
    private static final Color COLOR_PRIMARY     = new Color(0x2D, 0x5B, 0xE3);  // #2D5BE3 (파랑)
    private static final Color COLOR_WHITE       = Color.WHITE;
    private static final Color COLOR_DARK        = new Color(0x1E, 0x29, 0x3B);  // #1E293B (진한 네이비)
    private static final Color COLOR_MUTED       = new Color(0x64, 0x74, 0x8B);  // #64748B (회색)
    private static final Color COLOR_LIGHT_BG    = new Color(0xF8, 0xFA, 0xFC);  // #F8FAFC (연한 배경)
    private static final Color COLOR_ACCENT_BAR  = new Color(0x0E, 0xA5, 0xE9);  // #0EA5E9 (하이라이트)

    private PlannerPptxUtil() {}

    /**
     * 슬라이드 데이터 → PPTX 바이트 배열.
     *
     * @param title      발표 전체 제목
     * @param slidesList 슬라이드 목록 (각 항목: title·content·notes)
     * @return PPTX 파일 바이트
     */
    @SuppressWarnings("unchecked")
    public static byte[] buildPptx(String title, List<Map<String, Object>> slidesList) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            pptx.setPageSize(new Dimension(SLIDE_WIDTH_EMU / Units.EMU_PER_POINT,
                                           SLIDE_HEIGHT_EMU / Units.EMU_PER_POINT));

            // ① 표지 슬라이드
            addTitleSlide(pptx, title);

            // ② 콘텐츠 슬라이드
            if (slidesList != null) {
                for (Map<String, Object> slide : slidesList) {
                    String slideTitle   = getString(slide, "title", "");
                    Object contentRaw   = slide.get("content");
                    String notes        = getString(slide, "notes", "");

                    List<String> bullets = java.util.Collections.emptyList();
                    if (contentRaw instanceof List) {
                        bullets = (List<String>) contentRaw;
                    }
                    addContentSlide(pptx, slideTitle, bullets, notes);
                }
            }

            pptx.write(out);
            return out.toByteArray();
        }
    }

    // ─── 표지 슬라이드 ──────────────────────────────────────────────────────────
    private static void addTitleSlide(XMLSlideShow pptx, String title) {
        XSLFSlide slide = pptx.createSlide();

        int W = SLIDE_WIDTH_EMU  / Units.EMU_PER_POINT;
        int H = SLIDE_HEIGHT_EMU / Units.EMU_PER_POINT;

        // 배경 (진한 네이비)
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        bg.setAnchor(new Rectangle(0, 0, W, H));
        bg.setFillColor(COLOR_DARK);
        bg.setLineColor(COLOR_DARK);

        // 하단 강조 바
        XSLFAutoShape bar = slide.createAutoShape();
        bar.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        bar.setAnchor(new Rectangle(0, H - 6, W, 6));
        bar.setFillColor(COLOR_PRIMARY);
        bar.setLineColor(COLOR_PRIMARY);

        // 제목 텍스트
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(60, H / 3, W - 120, H / 3));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        titlePara.setTextAlign(TextParagraph.TextAlign.LEFT);
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(CommonUtil.nullToBlank(title));
        titleRun.setFontSize(36.0);
        titleRun.setBold(true);
        titleRun.setFontColor(COLOR_WHITE);
        titleRun.setFontFamily("Malgun Gothic");

        // 부제 (Powered by AI)
        XSLFTextBox subBox = slide.createTextBox();
        subBox.setAnchor(new Rectangle(60, H / 3 + 80, W - 120, 40));
        XSLFTextParagraph subPara = subBox.addNewTextParagraph();
        XSLFTextRun subRun = subPara.addNewTextRun();
        subRun.setText("Powered by AI");
        subRun.setFontSize(14.0);
        subRun.setFontColor(COLOR_MUTED);
        subRun.setFontFamily("Malgun Gothic");
    }

    // ─── 콘텐츠 슬라이드 ────────────────────────────────────────────────────────
    private static void addContentSlide(XMLSlideShow pptx, String title,
                                        List<String> bullets, String notes) {
        XSLFSlide slide = pptx.createSlide();

        int W = SLIDE_WIDTH_EMU  / Units.EMU_PER_POINT;
        int H = SLIDE_HEIGHT_EMU / Units.EMU_PER_POINT;

        // 흰 배경
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        bg.setAnchor(new Rectangle(0, 0, W, H));
        bg.setFillColor(COLOR_LIGHT_BG);
        bg.setLineColor(COLOR_LIGHT_BG);

        // 상단 헤더 바 (파랑)
        XSLFAutoShape header = slide.createAutoShape();
        header.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        header.setAnchor(new Rectangle(0, 0, W, 70));
        header.setFillColor(COLOR_PRIMARY);
        header.setLineColor(COLOR_PRIMARY);

        // 슬라이드 제목 (헤더 위)
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(24, 12, W - 48, 46));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        titlePara.setTextAlign(TextParagraph.TextAlign.LEFT);
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(CommonUtil.nullToBlank(title));
        titleRun.setFontSize(20.0);
        titleRun.setBold(true);
        titleRun.setFontColor(COLOR_WHITE);
        titleRun.setFontFamily("Malgun Gothic");

        // 본문 영역 (흰 카드)
        XSLFAutoShape card = slide.createAutoShape();
        card.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        card.setAnchor(new Rectangle(24, 86, W - 48, H - 110));
        card.setFillColor(Color.WHITE);
        card.setLineColor(new Color(0xE2, 0xE8, 0xF0));

        // 불릿 텍스트
        XSLFTextBox contentBox = slide.createTextBox();
        contentBox.setAnchor(new Rectangle(40, 102, W - 80, H - 140));

        if (bullets == null || bullets.isEmpty()) {
            XSLFTextParagraph p = contentBox.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText("내용을 입력하세요.");
            r.setFontSize(14.0);
            r.setFontColor(COLOR_MUTED);
            r.setFontFamily("Malgun Gothic");
        } else {
            boolean first = true;
            for (String bullet : bullets) {
                if (bullet == null) continue;
                XSLFTextParagraph p = first ? contentBox.getTextParagraphs().get(0)
                                            : contentBox.addNewTextParagraph();
                first = false;
                p.setBullet(true);
                p.setBulletCharacter("•");
                p.setIndent(10.0);
                p.setSpaceBefore(6.0);
                XSLFTextRun r = p.addNewTextRun();
                r.setText(bullet.trim());
                r.setFontSize(15.0);
                r.setFontColor(COLOR_DARK);
                r.setFontFamily("Malgun Gothic");
            }
        }

        // 발표자 노트 추가
        if (CommonUtil.isNotEmpty(notes)) {
            XSLFNotes notesSlide = pptx.getNotesSlide(slide);
            if (notesSlide != null) {
                for (XSLFShape shape : notesSlide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape ts = (XSLFTextShape) shape;
                        if (!ts.getTextParagraphs().isEmpty()) {
                            // 기존 placeholder를 사용
                            XSLFTextParagraph np = ts.addNewTextParagraph();
                            XSLFTextRun nr = np.addNewTextRun();
                            nr.setText(notes);
                            nr.setFontSize(12.0);
                            nr.setFontFamily("Malgun Gothic");
                            break;
                        }
                    }
                }
            }
        }
    }

    // ─── 헬퍼 ───────────────────────────────────────────────────────────────────
    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultVal : s;
    }
}
