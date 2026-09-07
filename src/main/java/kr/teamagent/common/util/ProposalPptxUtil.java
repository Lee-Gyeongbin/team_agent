package kr.teamagent.common.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * PROPOSAL 에이전트 — 코드 기반 PPTX 렌더링 유틸.
 * layoutType에 따라 5종 레이아웃을 렌더링한다.
 *
 * <p>지원 layoutType:
 * <ul>
 *   <li>cover            — 표지/도입부 슬라이드</li>
 *   <li>toc              — 목차 (번호 배지 + 섹션명 + 설명)</li>
 *   <li>section_divider  — 섹션 간지 (baseColor 풀블리드 + 대형 섹션번호 + 제목)</li>
 *   <li>keyword_list     — 개요·요약 (키워드 pill 태그 + 항목 리스트)</li>
 *   <li>process_cards    — 단계/전환/비교 (가로 카드 2~4개 + 화살표)</li>
 *   <li>grid_cards       — 전략·역량·체크리스트 (2×N 그리드 카드)</li>
 *   <li>infographic      — 3구역 구조 (헤더존 코드 렌더링 + 본문존 인포그래픽 이미지 1장 + 선택적 푸터존)</li>
 * </ul>
 *
 * <p>모든 색상은 slideDesign.bgColor / baseColor / accentColor 에서 파생한다 (하드코딩 없음).
 *
 * <p>입력 JSON 구조:
 * <pre>
 * {
 *   "title": "제안서 전체 제목",
 *   "slideDesign": { "bgColor": "#FFFFFF", "baseColor": "#1B2559", "accentColor": "#6C63F6" },
 *   "slides": [
 *     {
 *       "title": "슬라이드 제목",
 *       "subtitle": "서브제목 (선택)",
 *       "headline": "핵심 메시지 한 문장",
 *       "keywords": ["키워드1", "키워드2"],
 *       "content": ["항목1", "항목2"],
 *       "notes": "발표자 노트",
 *       "layoutType": "cover|keyword_list|process_cards|grid_cards|infographic"
 *     }
 *   ]
 * }
 * </pre>
 */
public class ProposalPptxUtil {

    private static final Logger logger = LoggerFactory.getLogger(ProposalPptxUtil.class);

    // ─── 슬라이드 크기 (16:9 와이드스크린, pt 단위) ────────────────────────────────
    private static final int SLIDE_W = 9144000 / Units.EMU_PER_POINT;  // 720pt
    private static final int SLIDE_H = 5143500 / Units.EMU_PER_POINT;  // ~405pt

    // ─── 레이아웃 공통 상수 ────────────────────────────────────────────────────────
    private static final int    MARGIN   = 43;                     // 좌우 여백 (≈0.6in)
    private static final int    CONT_W   = SLIDE_W - MARGIN * 2;  // 634pt
    private static final String FONT     = "Malgun Gothic";

    // ─── infographic 3구역 고정 높이 ──────────────────────────────────────────────
    /** 헤더존 고정 높이 (pt). title + subtitle + 구분선. 텍스트 길이와 무관하게 본문존 y가 밀리지 않도록 상수 고정. */
    private static final int INFOGRAPHIC_HEADER_H = 62;
    /** 푸터존 고정 높이 (pt). headline 배너. subtitle이 이미 있으면 생략. */
    private static final int INFOGRAPHIC_FOOTER_H = 32;

    // ─── 고정 텍스트 색상 (배경색과 독립) ────────────────────────────────────────
    private static final Color WHITE     = Color.WHITE;
    private static final Color GRAY_TEXT = new Color(0x5A, 0x60, 0x72);
    private static final Color DARK_TEXT = new Color(0x1B, 0x25, 0x59);

    private ProposalPptxUtil() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // 이미지 기반 제안서 빌드 — PageInfo DTO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 렌더링 이미지 기반 빌드용 페이지 단위 입력 데이터.
     * 헤더·푸터 구성에 필요한 메타데이터를 함께 담는다.
     */
    public static class PageInfo {
        /** NCP에서 다운로드한 슬라이드 렌더링 이미지 bytes (null 허용 → 플레이스홀더 표시) */
        public final byte[] imageBytes;
        /** 챕터 번호 ("Ⅱ" 또는 SECTION_NO) — {chapter_no} 치환용 */
        public final String chapterRoman;
        /** 소목차/대목차 제목 — {chapter_title} 치환용 */
        public final String sectionTitle;
        /** 페이지 라벨 ("Ⅱ-1" 등) */
        public final String pageLabel;
        /** 사업명 (헤더 우측) */
        public final String projectNm;
        /** 발주기관명 (푸터 좌측) */
        public final String orgNm;
        /** 제안사명 (푸터 우측) */
        public final String submitterNm;
        /** LAYOUT_TYPE_CD: "001"=cover, "002"=section_divider, 그 외 일반 슬라이드 */
        public final String layoutTypeCd;
        /** 간지 하위 목차 리스트 ("1.1. xxx\\n1.2. yyy") — section_divider 오버레이용 */
        public final String subTocList;

        // 기존 생성자 (layoutTypeCd = null → 헤더/푸터 적용)
        public PageInfo(byte[] imageBytes, String chapterRoman, String sectionTitle,
                        String pageLabel, String projectNm, String orgNm, String submitterNm) {
            this(imageBytes, chapterRoman, sectionTitle, pageLabel, projectNm, orgNm, submitterNm, null, null);
        }

        // layoutTypeCd 포함
        public PageInfo(byte[] imageBytes, String chapterRoman, String sectionTitle,
                        String pageLabel, String projectNm, String orgNm, String submitterNm,
                        String layoutTypeCd) {
            this(imageBytes, chapterRoman, sectionTitle, pageLabel, projectNm, orgNm, submitterNm, layoutTypeCd, null);
        }

        // layoutTypeCd + subTocList 포함 (간지 오버레이)
        public PageInfo(byte[] imageBytes, String chapterRoman, String sectionTitle,
                        String pageLabel, String projectNm, String orgNm, String submitterNm,
                        String layoutTypeCd, String subTocList) {
            this.imageBytes   = imageBytes;
            this.chapterRoman = chapterRoman != null ? chapterRoman : "Ⅰ";
            this.sectionTitle = sectionTitle != null ? sectionTitle : "";
            this.pageLabel    = pageLabel    != null ? pageLabel    : "";
            this.projectNm    = projectNm   != null ? projectNm    : "";
            this.orgNm        = orgNm       != null ? orgNm        : "";
            this.submitterNm  = submitterNm != null ? submitterNm  : "";
            this.layoutTypeCd = layoutTypeCd;
            this.subTocList   = subTocList  != null ? subTocList   : "";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 공개 API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 제안서 슬라이드 → PPTX 바이트 (infographicImageMap 없는 오버로드).
     */
    public static byte[] buildPptx(
            String title,
            List<Map<String, Object>> slides,
            String bgColor, String baseColor, String accentColor) throws IOException {
        return buildPptx(title, slides, bgColor, baseColor, accentColor, Collections.emptyMap());
    }

    /**
     * 제안서 슬라이드 → PPTX 바이트.
     *
     * @param title                제안서 전체 제목
     * @param slides               슬라이드 목록 (각 슬라이드에 layoutType 포함)
     * @param bgColor              배경색 hex (#FFFFFF)
     * @param baseColor            기본색 hex (#1B2559)
     * @param accentColor          강조색 hex (#6C63F6)
     * @param infographicImageMap  슬라이드 인덱스 → base64 인포그래픽 이미지
     *                             (infographic layoutType 슬라이드에서 본문존에 삽입)
     */
    @SuppressWarnings("unchecked")
    public static byte[] buildPptx(
            String title,
            List<Map<String, Object>> slides,
            String bgColor, String baseColor, String accentColor,
            Map<Integer, String> infographicImageMap) throws IOException {

        Color cBg     = parseHex(bgColor,     new Color(0xF7, 0xF8, 0xFC));
        Color cBase   = parseHex(baseColor,   new Color(0x1B, 0x25, 0x59));
        Color cAccent = parseHex(accentColor, new Color(0x6C, 0x63, 0xF6));

        // 파생 색상 (모두 cBase / cAccent 에서 계산 — 하드코딩 없음)
        Color cIce       = tint(cAccent, 0.88f);   // 연한 강조 (배지 배경)
        Color cGridCard  = lighten(cBase, 30);      // grid 카드 배경
        Color cGridBadge = darken(cBase, 0.20f);    // grid 아이콘 원
        Color cSubAccent = tint(cAccent, 0.45f);    // grid 서브제목 (accentColor 밝은 톤)

        try (XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            pptx.setPageSize(new Dimension(SLIDE_W, SLIDE_H));

            // 웨이브 장식 이미지 — cover + keyword_list 슬라이드에서 공유 (한 번만 생성)
            byte[] waveBytes = ProposalIconUtil.generateWave(baseColor, accentColor);
            XSLFPictureData wavePd = pptx.addPicture(waveBytes, XSLFPictureData.PictureType.PNG);

            if (slides == null || slides.isEmpty()) {
                addCoverSlide(pptx, wavePd, title, Collections.emptyList(),
                        Collections.emptyList(), "", "", cBg, cBase, cAccent, cIce);
            } else {
                for (int slideIdx = 0; slideIdx < slides.size(); slideIdx++) {
                    Map<String, Object> slide = slides.get(slideIdx);
                    String layout   = str(slide, "layoutType", "keyword_list").toLowerCase().trim();
                    String sTitle   = str(slide, "title",      "");
                    String subtitle = str(slide, "subtitle",   "");
                    String headline = str(slide, "headline",   "");
                    List<String> kw = toStringList(slide.get("keywords"));
                    List<String> ct = toStringList(slide.get("content"));
                    String notes    = str(slide, "notes",      "");

                    switch (layout) {
                        case "cover":
                            addCoverSlide(pptx, wavePd, sTitle, kw, ct,
                                    subtitle, headline, cBg, cBase, cAccent, cIce);
                            break;
                        case "toc":
                            addTocSlide(pptx, wavePd, sTitle, kw, ct,
                                    cBg, cBase, cAccent, cIce);
                            break;
                        case "section_divider":
                            addSectionDividerSlide(pptx, sTitle, subtitle, headline,
                                    cBase, cAccent);
                            break;
                        case "process_cards":
                            addProcessCardsSlide(pptx, sTitle, subtitle, headline, kw, ct,
                                    notes, cBg, cBase, cAccent, cIce);
                            break;
                        case "grid_cards":
                            addGridCardsSlide(pptx, sTitle, subtitle, headline, kw, ct,
                                    notes, cBase, cAccent, cGridCard, cGridBadge, cSubAccent);
                            break;
                        case "infographic": {
                            String b64 = infographicImageMap != null
                                    ? infographicImageMap.get(slideIdx) : null;
                            if (b64 != null && !b64.isEmpty()) {
                                addInfographicSlide(pptx, sTitle, subtitle, headline,
                                        notes, b64, cBg, cBase, cAccent);
                            } else {
                                // 이미지 생성 실패 → keyword_list 폴백
                                addKeywordListSlide(pptx, wavePd, sTitle, subtitle, headline, kw, ct,
                                        notes, cBg, cBase, cAccent, cIce);
                            }
                            break;
                        }
                        default: // keyword_list
                            addKeywordListSlide(pptx, wavePd, sTitle, subtitle, headline, kw, ct,
                                    notes, cBg, cBase, cAccent, cIce);
                            break;
                    }
                }
            }

            pptx.write(out);
            return out.toByteArray();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 이미지 기반 제안서 빌드 (Step F 출력용)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 렌더링 이미지 기반 제안서 PPTX 생성.
     *
     * <p>페이지 구조:
     * <pre>
     * ┌─────────────────────────────────────────┐ y=0
     * │ 헤더 행1 (HDR_LINE_H): Chapter Ⅱ │ 사업명  │  작은 텍스트 행
     * │ 헤더 행2 (BAR_H):  ██ 소목차 제목 ████  │  baseColor 바
     * ├─────────────────────────────────────────┤ y=HEADER_H
     * │                                         │
     * │     RENDERED_IMAGE (letterbox)          │  본문
     * │                                         │
     * ├─────────────────────────────────────────┤ y=slideH-FOOTER_H
     * │ 발주기관   │     Ⅱ-1     │    제안사명  │  푸터
     * └─────────────────────────────────────────┘ y=slideH
     * </pre>
     *
     * @param pages       슬라이드별 PageInfo 목록
     * @param docSize     "a4" (595×842pt) | "43" (720×540pt) | "169" (720×405pt)
     * @param bgColor     배경색 hex
     * @param baseColor   기본색 hex (헤더 바·챕터 배지)
     * @param accentColor 강조색 hex
     */
    public static byte[] buildProposalDocWithImages(
            List<PageInfo> pages,
            String docSize,
            String bgColor, String baseColor, String accentColor) throws IOException {

        // ── 페이지 크기 결정 ─────────────────────────────────────────────────
        final int slideW, slideH;
        if ("a4".equalsIgnoreCase(docSize)) {
            slideW = 595; slideH = 842;   // A4 portrait
        } else if ("43".equals(docSize)) {
            slideW = 720; slideH = 540;   // 4:3
        } else {
            slideW = 720; slideH = 405;   // 16:9 (기본)
        }

        // ── 색상 파싱 ────────────────────────────────────────────────────────
        Color cBg     = parseHex(bgColor,     new Color(0xFF, 0xFF, 0xFF));
        Color cBase   = parseHex(baseColor,   new Color(0x5B, 0x4F, 0xE9));
        Color cAccent = parseHex(accentColor, new Color(0xE0, 0x8A, 0x2C));
        Color cFooter = new Color(0xF8, 0xF9, 0xFA);

        // ── 레이아웃 상수 (A4 842pt 기준, 비율 스케일 적용) ─────────────────
        double scale     = slideH / 842.0;
        int HDR_LINE_H   = Math.max(18, (int) (22 * scale));  // 챕터·사업명 행
        int BAR_H        = Math.max(30, (int) (46 * scale));  // 소목차 타이틀 바
        int HEADER_H     = HDR_LINE_H + BAR_H;
        int FOOTER_H     = Math.max(20, (int) (28 * scale));  // 푸터 높이
        int IMAGE_Y      = HEADER_H;
        int IMAGE_H      = slideH - HEADER_H - FOOTER_H;
        int FOOTER_Y     = slideH - FOOTER_H;
        int MARGIN       = 30;
        int CONT_W       = slideW - MARGIN * 2;

        double fsChapterLabel = 6.5 * scale + 3;   // "Chapter" 소문자
        double fsBadge        = 9.5 * scale + 3;   // 로마숫자 배지
        double fsProjectNm    = 6.5 * scale + 3;   // 사업명
        double fsBarTitle     = 14.0 * scale + 4;  // 소목차 타이틀
        double fsFooter       = 6.5 * scale + 3;   // 푸터 텍스트

        try (XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            pptx.setPageSize(new Dimension(slideW, slideH));

            for (PageInfo page : pages) {
                XSLFSlide slide = pptx.createSlide();

                // ── 배경 전체 채움 ──────────────────────────────────────────
                addRect(slide, 0, 0, slideW, slideH, cBg);

                // ── 헤더 행1: Chapter 라벨 + 로마숫자 배지 + 사업명 ──────────
                // "Chapter" 소문자 레이블
                text(slide, "Chapter", MARGIN, 3, 44, HDR_LINE_H - 3,
                        fsChapterLabel, false, false, GRAY_TEXT, null);
                // 로마숫자 배지 (baseColor 사각형)
                int BADGE_W = Math.max(20, (int)(26 * scale));
                addRect(slide, MARGIN + 46, 2, BADGE_W, HDR_LINE_H - 3, cBase);
                text(slide, page.chapterRoman, MARGIN + 46, 2, BADGE_W, HDR_LINE_H - 3,
                        fsBadge, true, false, WHITE, TextParagraph.TextAlign.CENTER);
                // 사업명 (우측)
                text(slide, page.projectNm, MARGIN + 46 + BADGE_W + 6, 3,
                        CONT_W - 46 - BADGE_W - 6, HDR_LINE_H - 3,
                        fsProjectNm, false, false, GRAY_TEXT, TextParagraph.TextAlign.RIGHT);

                // ── 헤더 행2: 소목차 타이틀 바 (baseColor 풀폭) ──────────────
                addRect(slide, 0, HDR_LINE_H, slideW, BAR_H, cBase);
                text(slide, page.sectionTitle, MARGIN, HDR_LINE_H + 4,
                        CONT_W, BAR_H - 8, fsBarTitle, true, false, WHITE, null);

                // ── 본문: 렌더링 이미지 ────────────────────────────────────
                if (page.imageBytes != null && page.imageBytes.length > 0) {
                    addImgLetterbox(pptx, slide, page.imageBytes, 0, IMAGE_Y, slideW, IMAGE_H, cBg);
                } else {
                    // 이미지 없음 → 회색 플레이스홀더 + 안내 텍스트
                    addRect(slide, 0, IMAGE_Y, slideW, IMAGE_H, tint(cBg, 0.25f));
                    text(slide, "이미지를 불러올 수 없습니다.",
                            MARGIN, IMAGE_Y + IMAGE_H / 2 - 10, CONT_W, 24,
                            10, false, false, GRAY_TEXT, TextParagraph.TextAlign.CENTER);
                }

                // ── 푸터: 밝은 회색 바 (#F8F9FA, 프레임 프롬프트와 동일) ──
                addRect(slide, 0, FOOTER_Y, slideW, FOOTER_H, cFooter);
                // 발주기관 (좌)
                text(slide, page.orgNm, MARGIN, FOOTER_Y + 3,
                        CONT_W / 3, FOOTER_H - 6, fsFooter, false, false, DARK_TEXT, null);
                // 페이지 번호 (중앙)
                text(slide, page.pageLabel, 0, FOOTER_Y + 3,
                        slideW, FOOTER_H - 6, fsFooter, true, false, DARK_TEXT,
                        TextParagraph.TextAlign.CENTER);
                // 제안사명 (우)
                text(slide, page.submitterNm, MARGIN, FOOTER_Y + 3,
                        CONT_W, FOOTER_H - 6, fsFooter, false, false, DARK_TEXT,
                        TextParagraph.TextAlign.RIGHT);
            }

            pptx.write(out);
            return out.toByteArray();
        }
    }

    /**
     * TB_PT_TEMPLATE JSON 레이아웃 기반 제안서 PPTX 생성.
     * HEADER_COMPONENTS_JSON / FOOTER_COMPONENTS_JSON 슬롯을 파싱해 동적으로 렌더링.
     *
     * cover(001) / section_divider(002) 슬라이드는 헤더/푸터 합성에서 제외.
     *
     * @param pages                슬라이드별 PageInfo (layoutTypeCd 포함)
     * @param docSize              "a4" | "43" | "169"
     * @param bgColor              배경색 hex
     * @param baseColor            기본색 hex
     * @param accentColor          강조색 hex
     * @param headerComponentsJson HEADER_COMPONENTS_JSON (TB_PT_TEMPLATE)
     * @param footerComponentsJson FOOTER_COMPONENTS_JSON (TB_PT_TEMPLATE)
     * @param frameImageBytes      LLM이 생성한 프레임 이미지 bytes (null이면 슬롯 코드 렌더링 폴백)
     */
    public static byte[] buildProposalDocWithImages(
            List<PageInfo> pages,
            String docSize,
            String bgColor, String baseColor, String accentColor,
            String headerComponentsJson, String footerComponentsJson,
            byte[] frameImageBytes) throws IOException {

        // ── 페이지 크기 결정 ─────────────────────────────────────────────────
        final int slideW, slideH;
        if ("a4".equalsIgnoreCase(docSize)) {
            slideW = 595; slideH = 842;
        } else if ("43".equals(docSize)) {
            slideW = 720; slideH = 540;
        } else {
            slideW = 720; slideH = 405;
        }

        Color cBg     = parseHex(bgColor,     new Color(0xFF, 0xFF, 0xFF));
        Color cBase   = parseHex(baseColor,   new Color(0x5B, 0x4F, 0xE9));
        Color cAccent = parseHex(accentColor, new Color(0xE0, 0x8A, 0x2C));

        // 템플릿 JSON 파싱
        Gson localGson = new Gson();
        TemplateLayout headerLayout = parseTemplateLayout(headerComponentsJson, localGson);
        TemplateLayout footerLayout = parseTemplateLayout(footerComponentsJson, localGson);

        // 헤더/푸터 높이: JSON에 height(%) 명시 시 slideH 대비 퍼센트로 환산,
        // 미지정 시 프론트(ProposalStepTemplateGen.vue)의 headerHeightPct=9 / footerHeightPct=5와 동일한 비율 사용.
        final double HEADER_PCT_DEFAULT = 0.09;   // 프론트 headerHeightPct와 동일
        final double FOOTER_PCT_DEFAULT = 0.05;   // 프론트 footerHeightPct와 동일

        int HEADER_H = (headerLayout != null && headerLayout.height > 0)
                ? (int)(headerLayout.height * slideH / 100.0)
                : (int)(slideH * HEADER_PCT_DEFAULT);
        int FOOTER_H = (footerLayout != null && footerLayout.height > 0)
                ? (int)(footerLayout.height * slideH / 100.0)
                : (int)(slideH * FOOTER_PCT_DEFAULT);

        double scale = slideH / 842.0;
        HEADER_H = Math.max(40, HEADER_H);
        FOOTER_H = Math.max(16, FOOTER_H);

        int IMAGE_Y  = HEADER_H;
        int IMAGE_H  = slideH - HEADER_H - FOOTER_H;
        int FOOTER_Y = slideH - FOOTER_H;

        // 프레임 이미지 사용 여부 (LLM이 생성한 헤더/푸터 프레임 이미지)
        final boolean useFrameImage = (frameImageBytes != null && frameImageBytes.length > 0);

        try (XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            pptx.setPageSize(new Dimension(slideW, slideH));

            for (PageInfo page : pages) {
                XSLFSlide slide = pptx.createSlide();

                boolean skipHeaderFooter = "001".equals(page.layoutTypeCd)
                        || "002".equals(page.layoutTypeCd);

                if (useFrameImage && !skipHeaderFooter) {
                    // ── 이미지+이미지 방식 ────────────────────────────────────
                    // 1) 프레임 이미지 전체 배경 (헤더·푸터 디자인 포함)
                    addImgLetterbox(pptx, slide, frameImageBytes, 0, 0, slideW, slideH, cBg);
                    // 2) 동적 텍스트 오버레이 (divider·배지 배경은 프레임 이미지에 이미 있으므로 스킵)
                    renderHeaderSlots(slide, headerLayout, page, slideW, HEADER_H, scale, cBase, cAccent, true);
                    renderFooterSlots(slide, footerLayout, page, slideW, FOOTER_H, FOOTER_Y, scale);
                    // 3) 본문 인포그래픽 오버레이
                    if (page.imageBytes != null && page.imageBytes.length > 0) {
                        addImgLetterbox(pptx, slide, page.imageBytes, 0, IMAGE_Y, slideW, IMAGE_H, null);
                    }
                } else {
                    // ── 코드 기반 방식 (프레임 없거나 cover/divider 슬라이드) ──
                    addRect(slide, 0, 0, slideW, slideH, cBg);

                    if (!skipHeaderFooter) {
                        // 헤더 배경 + 슬롯 (배경 rect 포함)
                        addRect(slide, 0, 0, slideW, HEADER_H, new Color(0xFF, 0xFF, 0xFF));
                        renderHeaderSlots(slide, headerLayout, page, slideW, HEADER_H, scale, cBase, cAccent, false);
                        // 푸터 배경 + 슬롯 (프레임 프롬프트·프론트 미리보기와 동일: #F8F9FA)
                        addRect(slide, 0, FOOTER_Y, slideW, FOOTER_H, new Color(0xF8, 0xF9, 0xFA));
                        renderFooterSlots(slide, footerLayout, page, slideW, FOOTER_H, FOOTER_Y, scale);
                        // 본문 이미지
                        if (page.imageBytes != null && page.imageBytes.length > 0) {
                            addImgLetterbox(pptx, slide, page.imageBytes, 0, IMAGE_Y, slideW, IMAGE_H, cBg);
                        } else {
                            addRect(slide, 0, IMAGE_Y, slideW, IMAGE_H, tint(cBg, 0.25f));
                            text(slide, "이미지를 불러올 수 없습니다.",
                                    30, IMAGE_Y + IMAGE_H / 2 - 10, slideW - 60, 24,
                                    10, false, false, GRAY_TEXT, TextParagraph.TextAlign.CENTER);
                        }
                    } else {
                        // cover/section_divider: 이미지 전체 슬라이드 채움
                        if (page.imageBytes != null && page.imageBytes.length > 0) {
                            addImgLetterbox(pptx, slide, page.imageBytes, 0, 0, slideW, slideH, cBg);
                        } else {
                            addRect(slide, 0, 0, slideW, slideH, tint(cBg, 0.25f));
                            text(slide, "이미지를 불러올 수 없습니다.",
                                    30, slideH / 2 - 10, slideW - 60, 24,
                                    10, false, false, GRAY_TEXT, TextParagraph.TextAlign.CENTER);
                        }
                        // 간지: 재사용 배경 위에 대목차번호/명/하위목차 텍스트 오버레이
                        if ("002".equals(page.layoutTypeCd)) {
                            renderDividerTextOverlay(slide, page, slideW, slideH, cBase, cAccent);
                        }
                    }
                }
            }

            pptx.write(out);
            return out.toByteArray();
        }
    }

    /**
     * placeholder → 실제 값 치환.
     * type 기반 동적값(org_name/page_number/company_name)은 text 필드 유무와 관계없이 항상 우선.
     * (슬롯 편집기가 미리보기용 예시값을 text에 저장해도 실제 출력은 PageInfo 동적값을 사용하도록)
     */
    private static String resolveSlotText(TemplateSlot slot, PageInfo page,
                                           Color cBase, Color cAccent) {
        // type 기반 동적값 우선 — text(placeholder) 존재 여부와 무관하게 항상 실제 값 반환
        if ("org_name".equals(slot.type))     return page.orgNm;
        if ("page_number".equals(slot.type))  return page.pageLabel;
        if ("company_name".equals(slot.type)) return page.submitterNm;

        // 그 외: placeholder 문자열 치환 경로 (헤더 슬롯 — chapterBadge, projectNm 등)
        String t = slot.placeholder;
        if (t == null || t.isEmpty()) return "";
        return t
            .replace("{chapter_no}",    page.chapterRoman)
            .replace("{project_nm}",    page.projectNm)
            .replace("{chapter_title}", page.sectionTitle)
            .replace("{breadcrumb}",    page.sectionTitle)
            .replace("{org_nm}",        page.orgNm)
            .replace("{submitter_nm}",  page.submitterNm)
            .replace("{page_number}",   page.pageLabel)
            .replace("{sub_toc_list}",  page.subTocList != null ? page.subTocList : "");
    }

    /**
     * 간지(section_divider) 텍스트 오버레이.
     * 본문형 헤더/푸터의 resolveSlotText와 동일하게,
     * 재사용 배경 이미지 위에 {chapter_no}/{chapter_title}/{sub_toc_list} 실제값을 PPTX 텍스트로 올린다.
     * (이미지 픽셀의 플레이스홀더를 지우는 방식이 아님 — 이미지에는 글자가 없어야 함)
     */
    private static void renderDividerTextOverlay(XSLFSlide slide, PageInfo page,
                                                  int slideW, int slideH,
                                                  Color cBase, Color cAccent) {
        int margin = Math.max(28, slideW / 24);
        int leftW  = (int)(slideW * 0.52) - margin;
        int rightX = (int)(slideW * 0.55);
        int rightW = slideW - rightX - margin;
        int centerY = (int)(slideH * 0.42);
        Color titleColor = (cBase != null) ? cBase : DARK_TEXT;
        Color listColor  = darken(titleColor, 0.15f);

        // 대목차 번호 — 좌측
        String chapterNo = nvl(page.chapterRoman, "");
        if (!chapterNo.isEmpty()) {
            text(slide, chapterNo, margin, centerY - 90, leftW, 56,
                    Math.max(28, slideH / 12.0), true, false, titleColor, null);
        }

        // 구분선 (본문형 accent 라인과 유사한 역할)
        addRect(slide, margin, centerY - 22, Math.min(120, leftW / 3), 3, cAccent);

        // 대목차명 — 좌측 (핵심)
        String chapterTitle = nvl(page.sectionTitle, "");
        if (!chapterTitle.isEmpty()) {
            text(slide, chapterTitle, margin, centerY - 10, leftW, 90,
                    Math.max(20, slideH / 18.0), true, false, titleColor, null);
        }

        // 하위 목차 리스트 — 우측 ({sub_toc_list} 치환 결과)
        String subToc = page.subTocList != null ? page.subTocList.trim() : "";
        if (!subToc.isEmpty()) {
            String[] lines = subToc.split("\\r?\\n");

            // ── 가용 높이 기반 행간/폰트 동적 조정 ──────────────────────────
            // A4(842pt)에서 slideH/18=46, slideH/32=26이 되어 항목이 많으면 하단이 잘리므로,
            // 상한값(22pt 행간, 13pt 폰트)을 두고 항목 수에 따라 추가 축소
            int availH = slideH - margin * 2;
            int lineH  = Math.min(22, Math.max(16, slideH / 20));
            double fontSize = Math.min(13.0, Math.max(10.0, slideH / 65.0));

            // 항목이 많아 가용 높이를 초과하면 행간·폰트를 비례 축소
            int blockH = lines.length * lineH;
            if (blockH > availH) {
                lineH    = Math.max(14, availH / Math.max(1, lines.length));
                fontSize = Math.max(9.0, lineH * 0.65);
                blockH   = lines.length * lineH;
            }

            // 슬라이드 중앙 기준 배치, 상·하단 margin 이내로 클램프
            int startY = centerY - blockH / 2;
            startY = Math.min(startY, slideH - margin - blockH);
            startY = Math.max(margin, startY);

            for (int i = 0; i < lines.length; i++) {
                int itemY = startY + i * lineH;
                if (itemY + lineH > slideH - margin) break;  // 하단 초과 시 중단
                String line = lines[i] != null ? lines[i].trim() : "";
                if (line.isEmpty()) continue;
                text(slide, line, rightX, itemY, rightW, lineH,
                        fontSize, false, false, listColor, null);
            }
        }
    }

    private static TextParagraph.TextAlign parseAlign(String align) {
        if ("center".equalsIgnoreCase(align)) return TextParagraph.TextAlign.CENTER;
        if ("right".equalsIgnoreCase(align))  return TextParagraph.TextAlign.RIGHT;
        return null;
    }

    /**
     * 슬롯의 유효 width (%, 컨테이너 너비 기준).
     * LLM이 width를 생략한 경우 known-key 기본값 → x 기반 나머지 공간 순으로 적용.
     */
    private static int slotEffectiveWidth(TemplateSlot slot) {
        if (slot.width > 0) return slot.width;
        switch (slot.key != null ? slot.key : "") {
            case "divider": return 100;          // 구분선 → 전체 폭
            case "left":    return 30;           // 푸터 3분할
            case "center":  return 20;
            case "right":   return 30;
            default:        return Math.max(10, 100 - slot.x); // x 이후 나머지 공간
        }
    }

    /**
     * 슬롯의 유효 height (%, 컨테이너 높이 기준).
     * LLM이 height를 생략한 경우 known-key 기본값 → y 기반 나머지 공간 순으로 적용.
     */
    private static int slotEffectiveHeight(TemplateSlot slot) {
        if (slot.height > 0) return slot.height;
        switch (slot.key != null ? slot.key : "") {
            case "divider": return 3;            // 구분선 → 얇게 (3%)
            default:        return Math.max(15, 100 - slot.y); // y 이후 나머지 공간
        }
    }

    /**
     * 텍스트 실제 길이 기반 폭 추정 (pt). 한글/한자 등 전각 문자는 fontSize와 거의 동일한 폭,
     * 영문/숫자 등 반각 문자는 약 0.55배 폭으로 근사.
     */
    private static int estimateTextWidthPt(String text, double fontSizePt) {
        if (text == null || text.isEmpty()) return 0;
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += (c > 0x3000) ? fontSizePt * 1.0 : fontSizePt * 0.55;
        }
        return (int) Math.ceil(width);
    }

    private static int scalePt(int val, double scale) {
        return (int)(val * scale);
    }

    /**
     * 헤더 슬롯 순회·렌더링 공통 메서드.
     *
     * @param textOverlayOnly true  — 프레임 이미지 경로: divider rect·배지 배경 스킵, 텍스트만 오버레이
     *                        false — 코드 렌더링 경로: divider rect·배지 배경(bgSlot)도 함께 그림
     */
    private static void renderHeaderSlots(XSLFSlide slide,
            TemplateLayout headerLayout, PageInfo page,
            int slideW, int HEADER_H, double scale,
            Color cBase, Color cAccent, boolean textOverlayOnly) {
        if (headerLayout == null || headerLayout.slots == null) return;
        for (TemplateSlot slot : headerLayout.slots) {
            if (slot == null) continue;
            String resolvedText = resolveSlotText(slot, page, cBase, cAccent);
            int sx = (int)(slot.x * slideW   / 100.0);
            int sy = (int)(slot.y * HEADER_H / 100.0);
            int sh = Math.max(1, (int)(slotEffectiveHeight(slot) * HEADER_H / 100.0));
            // chapterBadge: JSON에 height 미지정 시 fontSize 기반 정사각형 크기로 override.
            // slotEffectiveHeight의 100-y fallback이 배지를 과도하게 크게 만들어
            // 인접 chapterTitle 슬롯과 겹치는 문제 방지. height 명시된 경우는 그 값 존중.
            if ("chapterBadge".equals(slot.key) && slot.height == 0) {
                double fsBadge = slot.fontSize > 0 ? slot.fontSize * scale : 9 * scale;
                sh = Math.max(16, (int)(fsBadge * 1.8));
            }
            // 폰트 크기 — sw 계산(텍스트 길이 기반)에 먼저 필요하므로 여기서 산출
            double fs = slot.fontSize > 0 ? slot.fontSize * scale : 9 * scale;
            // sw 계산: ① chapterBadge → 정사각형(sh),
            //          ② width 미지정 텍스트 슬롯 → 텍스트 길이 기반 추정 (정렬-좌표 불일치 방지),
            //          ③ 그 외(divider, width 명시, known-key) → 기존 slotEffectiveWidth 경로
            int sw;
            if ("chapterBadge".equals(slot.key) && slot.width == 0) {
                sw = sh;  // 정사각형 배지
            } else if (slot.width == 0 && !"divider".equals(slot.key) && !"divider".equals(slot.type)
                    && resolvedText != null && !resolvedText.isEmpty()) {
                // width 미지정 텍스트 슬롯 — 텍스트 길이 기반 폭 추정 (여유 패딩 6pt 추가)
                // 큰 박스(100-x%) + align=right 조합으로 x 좌표가 무력화되는 현상 방지
                int estimatedPt = estimateTextWidthPt(resolvedText, fs) + 6;
                sw = Math.max(1, estimatedPt);
            } else {
                sw = Math.max(1, (int)(slotEffectiveWidth(slot) * slideW / 100.0));
            }
            // 우측/하단 경계 클램핑 — sx+sw > slideW 또는 sy+sh > HEADER_H 방지
            if (sx + sw > slideW) sw = Math.max(1, slideW - sx);
            if (sy + sh > HEADER_H) sh = Math.max(1, HEADER_H - sy);
            if ("divider".equals(slot.key) || "divider".equals(slot.type)) {
                // 프레임 이미지 경로: divider는 프레임에 이미 그려져 있으므로 스킵
                if (!textOverlayOnly) {
                    addRect(slide, sx, sy, sw, sh,
                            slot.bgColor != null ? parseHex(slot.bgColor, cAccent) : cAccent);
                }
            } else if (resolvedText != null && !resolvedText.isEmpty()) {
                Color textColor = slot.color != null ? parseHex(slot.color, DARK_TEXT) : DARK_TEXT;
                // chapterBadge: bgColor 기본값 = baseColor (흰색 텍스트가 보이도록)
                Color bgSlot = slot.bgColor != null ? parseHex(slot.bgColor, null)
                             : "chapterBadge".equals(slot.key) ? cBase
                             : null;
                // chapterBadge는 정사각형 배지라 박스 높이(sh) 유지, 그 외 텍스트 슬롯은
                // fontSize*1.4 한 줄 높이 + noWrap으로 인접 슬롯 겹침 방지
                boolean isChapterBadge = "chapterBadge".equals(slot.key);
                int shText = isChapterBadge ? sh : Math.max(12, (int)(fs * 1.4));
                // 프레임 이미지 경로: 배지 배경은 프레임에 이미 있으므로 스킵
                if (!textOverlayOnly && bgSlot != null) {
                    addRect(slide, sx, sy, sw, shText, bgSlot);
                }
                text(slide, resolvedText, sx, sy, sw, shText, fs,
                        "bold".equalsIgnoreCase(slot.fontWeight), false, textColor,
                        parseAlign(slot.align), !isChapterBadge);
            }
        }
    }

    /**
     * 푸터 슬롯 순회·렌더링 공통 메서드.
     * 푸터는 divider·배지 배경이 없으므로 textOverlayOnly 구분 불필요.
     */
    private static void renderFooterSlots(XSLFSlide slide,
            TemplateLayout footerLayout, PageInfo page,
            int slideW, int FOOTER_H, int FOOTER_Y, double scale) {
        if (footerLayout == null || footerLayout.slots == null) return;
        for (TemplateSlot slot : footerLayout.slots) {
            if (slot == null) continue;
            String resolvedText = resolveSlotText(slot, page, null, null);
            int sx = (int)(slot.x * slideW  / 100.0);
            int sy = FOOTER_Y + (int)(slot.y * FOOTER_H / 100.0);
            int sw = Math.max(1, (int)(slotEffectiveWidth(slot) * slideW / 100.0));
            // 우측 경계 클램핑 — sx+sw > slideW 방지 (예: right 슬롯 x=90%+width=30% → 120% 초과)
            if (sx + sw > slideW) sw = Math.max(1, slideW - sx);
            if (resolvedText != null && !resolvedText.isEmpty()) {
                Color textColor = slot.color != null ? parseHex(slot.color, DARK_TEXT) : DARK_TEXT;
                double fs = slot.fontSize > 0 ? slot.fontSize * scale : 8 * scale;
                // 한 줄 높이 + noWrap — 푸터 슬롯 겹침 방지
                int shLine = Math.max(10, (int)(fs * 1.4));
                text(slide, resolvedText, sx, sy, sw, shLine, fs,
                        "bold".equalsIgnoreCase(slot.fontWeight), false, textColor,
                        parseAlign(slot.align), true);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 템플릿 레이아웃 검증 — 공개 결과 DTO
    // ═══════════════════════════════════════════════════════════════════════════

    /** 템플릿 JSON 검증 결과. ProposalServiceImpl 등 외부에서 사용. */
    public static class TemplateValidationResult {
        public final boolean hasInvalidSlot;
        public final String msg;
        TemplateValidationResult(boolean hasInvalidSlot, String msg) {
            this.hasInvalidSlot = hasInvalidSlot;
            this.msg = msg;
        }
    }

    /** 템플릿 JSON 파싱용 내부 DTO */
    private static class TemplateLayout {
        List<TemplateSlot> slots;
        int height;
        boolean hasInvalidSlot;
    }

    private static class TemplateSlot {
        String key;
        String type;
        String placeholder;
        int x, y, width, height;
        double fontSize;
        String fontWeight;
        String color;
        String bgColor;
        String align;
        int borderRadius;
    }

    /**
     * HEADER_COMPONENTS_JSON / FOOTER_COMPONENTS_JSON 파싱.
     * 실제 JSON 구조: {"body": {"componentKey": {"x":%, "y":%, "text":"...", "type":"...", "style":{...}}}}
     * x/y/width/height 값은 모두 0-100 범위의 퍼센트 값.
     */
    private static TemplateLayout parseTemplateLayout(String json, Gson gson) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("body")) return null;
            JsonObject body = root.getAsJsonObject("body");

            TemplateLayout layout = new TemplateLayout();
            layout.slots = new ArrayList<>();
            layout.height = 0; // 기본값 사용 (header=64, footer=28)

            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject comp = entry.getValue().getAsJsonObject();

                TemplateSlot slot = new TemplateSlot();
                slot.key         = entry.getKey();
                slot.type        = comp.has("type")  ? comp.get("type").getAsString()  : null;
                slot.placeholder = comp.has("text")  ? comp.get("text").getAsString()  : null;
                slot.x           = comp.has("x")     ? comp.get("x").getAsInt()        : 0;
                slot.y           = comp.has("y")     ? comp.get("y").getAsInt()        : 0;

                if (comp.has("style") && comp.get("style").isJsonObject()) {
                    JsonObject style = comp.getAsJsonObject("style");
                    slot.width        = style.has("width")        ? style.get("width").getAsInt()        : 0;
                    slot.height       = style.has("height")       ? style.get("height").getAsInt()       : 0;
                    slot.fontSize     = style.has("fontSize")     ? style.get("fontSize").getAsDouble()  : 0;
                    slot.fontWeight   = style.has("fontWeight")   ? style.get("fontWeight").getAsString(): null;
                    slot.color        = style.has("color")        ? style.get("color").getAsString()     : null;
                    // bgColor fallback: bgColor 없으면 bg 값 사용 (하위 호환)
                    slot.bgColor      = style.has("bgColor") ? style.get("bgColor").getAsString()
                                      : style.has("bg")      ? style.get("bg").getAsString()
                                      : null;
                    slot.align        = style.has("align")        ? style.get("align").getAsString()     : null;
                    slot.borderRadius = style.has("borderRadius") ? style.get("borderRadius").getAsInt() : 0;

                    // divider 컴포넌트: color → bgColor, thickness → height fallback
                    if ("divider".equals(slot.key) || "divider".equals(slot.type)) {
                        if (slot.bgColor == null && style.has("color")) {
                            slot.bgColor = style.get("color").getAsString();
                        }
                        if (slot.height == 0 && style.has("thickness")) {
                            slot.height = style.get("thickness").getAsInt();
                        }
                    }
                }

                layout.slots.add(slot);
            }

            return layout.slots.isEmpty() ? null : layout;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 헤더/푸터 JSON 레이아웃을 파싱하고 슬롯 유효성을 검증한다.
     * - width/height == 0인 텍스트 슬롯 감지
     * - pairwise 사각형 겹침 감지
     *
     * @param json         HEADER_COMPONENTS_JSON 또는 FOOTER_COMPONENTS_JSON
     * @param ptProjectId  로그용 프로젝트 ID
     * @return 검증 결과 (hasInvalidSlot=true이면 문제 있음)
     */
    public static TemplateValidationResult validateTemplateJson(String json, String ptProjectId) {
        Gson localGson = new Gson();
        TemplateLayout layout = parseTemplateLayout(json, localGson);
        if (layout == null) {
            return new TemplateValidationResult(false, null);
        }
        List<String> errors = new ArrayList<>();
        // 퍼센트 기반 좌표이므로 100×100 정규화 공간 사용
        validateLayout(layout, 100, 100, ptProjectId, errors);
        return new TemplateValidationResult(layout.hasInvalidSlot,
                errors.isEmpty() ? null : String.join(", ", errors));
    }

    /** 파싱된 레이아웃의 슬롯 유효성을 검사하고 문제가 있으면 errors 리스트에 추가한다. */
    private static void validateLayout(TemplateLayout layout, int containerW, int containerH,
                                        String ptProjectId, List<String> errors) {
        if (layout == null || layout.slots == null) return;

        List<TemplateSlot> validSizeSlots = new ArrayList<>();

        for (TemplateSlot slot : layout.slots) {
            if (slot == null) continue;
            boolean isDivider = "divider".equals(slot.key) || "divider".equals(slot.type);

            // divider가 아닌 텍스트 슬롯의 width/height 누락 검증
            if (!isDivider && (slot.placeholder != null || slot.type != null)) {
                if (slot.width == 0 || slot.height == 0) {
                    logger.warn("[PT Layout] 슬롯 width/height 누락 (slot={}, ptProjectId={})", slot.key, ptProjectId);
                    errors.add(slot.key + " 슬롯 width/height 누락");
                    layout.hasInvalidSlot = true;
                }
            }

            // 겹침 검사 대상: width/height가 정의된 슬롯만 (divider 제외 — 경계선이므로 의도적으로 겹침)
            if (slot.width > 0 && slot.height > 0 && !isDivider) {
                validSizeSlots.add(slot);
            }
        }

        // pairwise 겹침 검사
        for (int i = 0; i < validSizeSlots.size(); i++) {
            for (int j = i + 1; j < validSizeSlots.size(); j++) {
                TemplateSlot a = validSizeSlots.get(i);
                TemplateSlot b = validSizeSlots.get(j);
                int ax = (int)(a.x * containerW / 100.0);
                int ay = (int)(a.y * containerH / 100.0);
                int aw = (int)(a.width  * containerW / 100.0);
                int ah = (int)(a.height * containerH / 100.0);
                int bx = (int)(b.x * containerW / 100.0);
                int by = (int)(b.y * containerH / 100.0);
                int bw = (int)(b.width  * containerW / 100.0);
                int bh = (int)(b.height * containerH / 100.0);
                if (rectsOverlap(ax, ay, aw, ah, bx, by, bw, bh)) {
                    logger.warn("[PT Layout] 슬롯 겹침 감지 (slot1={}, slot2={}, ptProjectId={})", a.key, b.key, ptProjectId);
                    errors.add(a.key + "과 " + b.key + " 슬롯 겹침");
                    layout.hasInvalidSlot = true;
                }
            }
        }
    }

    /** 두 사각형의 교차 여부 반환. */
    private static boolean rectsOverlap(int ax, int ay, int aw, int ah,
                                         int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /**
     * 로마숫자 변환 (1~12 → Unicode 로마숫자 Ⅰ~Ⅻ, 초과 시 숫자 반환).
     */
    public static String toRomanNumeral(int n) {
        String[] ROMAN = { "", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ" };
        return (n >= 1 && n <= 12) ? ROMAN[n] : String.valueOf(n);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 레이아웃 1: 표지형 (cover)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addCoverSlide(XMLSlideShow pptx, XSLFPictureData wavePd,
            String title, List<String> keywords, List<String> content,
            String subtitle, String headline,
            Color cBg, Color cBase, Color cAccent, Color cIce) {

        XSLFSlide slide = pptx.createSlide();
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBg);

        // 우측 웨이브 장식 (x=374pt ≈ 5.2in, y=65pt ≈ 0.9in)
        addPd(slide, wavePd, 374, 65, 346, 340);

        // 서브타이틀 (accentColor, 소형)
        if (!subtitle.isEmpty()) {
            text(slide, subtitle, MARGIN, 68, CONT_W, 32, 15, true, false, cAccent, null);
        }

        // 대제목 (baseColor, 굵고 크게)
        text(slide, nvl(title, "제안서"), MARGIN, 101, CONT_W, 86, 34, true, false, cBase, null);

        // 핵심 설명 (headline → grayText)
        if (!headline.isEmpty()) {
            text(slide, headline, MARGIN, 173, CONT_W, 32, 13, false, false, GRAY_TEXT, null);
        }

        // 프로세스 아이콘 row (keywords → 원형 배지 + 화살표 연결)
        if (!keywords.isEmpty()) {
            int n       = keywords.size();
            int badgeW  = 68;   // 원형 배지 크기 (pt)
            int imgW    = 34;   // 아이콘 이미지 크기 (pt)
            int rowY    = 255;
            int stepGap = CONT_W / n; // 슬라이드 폭에 균등 배치

            for (int i = 0; i < n; i++) {
                int cx     = MARGIN + i * stepGap + (stepGap - badgeW) / 2;
                String kw  = keywords.get(i);
                String typ = ProposalIconUtil.pickIcon(kw, i);
                byte[] png = ProposalIconUtil.generateIcon(typ, toHex(cAccent), 128);

                // 원형 배지
                addOval(slide, cx, rowY, badgeW, badgeW, cIce);
                // 아이콘 이미지 (원 중앙)
                int off = (badgeW - imgW) / 2;
                addImg(pptx, slide, png, cx + off, rowY + off, imgW, imgW);
                // 라벨 텍스트
                text(slide, kw, cx - 10, rowY + badgeW + 6, badgeW + 20, 24,
                        9.5, false, false, cBase, TextParagraph.TextAlign.CENTER);

                // 카드 사이 → 화살표
                if (i < n - 1) {
                    int arrowX = cx + badgeW + 2;
                    int arrowY = rowY + badgeW / 2 - 9;
                    int arrowW = stepGap - badgeW - 4;
                    text(slide, "→", arrowX, arrowY, arrowW, 18, 10, false, false,
                            new Color(0xC0, 0xC8, 0xE0), TextParagraph.TextAlign.CENTER);
                }
            }
        }

        // 하단 메타 정보 (content → 발주기관/제출자 등)
        if (!content.isEmpty()) {
            StringBuilder meta = new StringBuilder();
            for (String c : content) {
                if (meta.length() > 0) meta.append("  ");
                meta.append(c);
            }
            text(slide, meta.toString(), MARGIN, SLIDE_H - 42, CONT_W, 34,
                    9.5, false, false, GRAY_TEXT, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 레이아웃 2: 키워드+리스트형 (keyword_list)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addKeywordListSlide(XMLSlideShow pptx, XSLFPictureData wavePd,
            String sTitle, String subtitle, String headline,
            List<String> keywords, List<String> content, String notes,
            Color cBg, Color cBase, Color cAccent, Color cIce) {

        XSLFSlide slide = pptx.createSlide();
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBg);

        // 공통 헤더 (제목 + 서브제목 + headline 배너)
        int y = drawCommonHeader(slide, sTitle, subtitle, headline, cBase, cAccent);

        // ② 키워드 pill 태그 가로 나열
        if (!keywords.isEmpty()) {
            int px = MARGIN;
            for (String kw : keywords) {
                int pillW = Math.max(55, kw.length() * 14 + 24);
                if (px + pillW > SLIDE_W - MARGIN) break; // 슬라이드 밖 초과 방지
                addRect(slide, px, y, pillW, 26, cIce);
                text(slide, kw, px, y, pillW, 26, 10, true, false, cBase,
                        TextParagraph.TextAlign.CENTER);
                px += pillW + 10;
            }
            y += 36;
        }

        // ③ 콘텐츠 항목 (원형 아이콘 배지 + 텍스트 줄) — 배열 길이만큼 자동 증가
        int itemStep = 42;
        int badgeW   = 32;
        for (int i = 0; i < content.size(); i++) {
            String item = content.get(i);
            if (item == null || item.trim().isEmpty()) continue;
            int iy = y + i * itemStep;
            if (iy + itemStep > SLIDE_H - 36) break;

            String typ = ProposalIconUtil.pickIcon(item, i);
            byte[] png = ProposalIconUtil.generateIcon(typ, toHex(cAccent), 128);

            addOval(slide, MARGIN, iy, badgeW, badgeW, cIce);
            addImg(pptx, slide, png, MARGIN + 8, iy + 8, 16, 16);
            text(slide, item.trim(), MARGIN + badgeW + 10, iy + 4,
                    CONT_W - badgeW - 10, badgeW, 12.5, false, false, DARK_TEXT, null);
        }

        // ④ 우측 하단 웨이브 장식 (소형)
        int wW = 180, wH = 120;
        addPd(slide, wavePd, SLIDE_W - wW - 12, SLIDE_H - wH - 12, wW, wH);

        addNotes(pptx, slide, notes);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 레이아웃 3: 3단 플로우형 (process_cards)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addProcessCardsSlide(XMLSlideShow pptx,
            String sTitle, String subtitle, String headline,
            List<String> keywords, List<String> content, String notes,
            Color cBg, Color cBase, Color cAccent, Color cIce) {

        XSLFSlide slide = pptx.createSlide();
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBg);

        int y = drawCommonHeader(slide, sTitle, subtitle, headline, cBase, cAccent);

        // 카드 수: keywords 개수 기준 2~4개
        int n      = Math.min(4, Math.max(2, keywords.isEmpty() ? 2 : keywords.size()));
        int arrW   = 16;  // 카드 사이 화살표 너비
        int cardW  = (CONT_W - (n - 1) * arrW) / n;
        int cardH  = SLIDE_H - y - 20;

        // content 항목을 n개 카드에 균등 분배
        int per = content.isEmpty() ? 0 : Math.max(1, (content.size() + n - 1) / n);

        for (int i = 0; i < n; i++) {
            int cx      = MARGIN + i * (cardW + arrW);
            // 홀짝 카드 배경 — tint로 명도 차이
            Color cardBg = (i % 2 == 1) ? cIce : tint(cBg, 0.40f);

            addRect(slide, cx, y, cardW, cardH, cardBg);

            // 상단 아이콘 배지
            String kw  = i < keywords.size() ? keywords.get(i) : "";
            String typ = ProposalIconUtil.pickIcon(kw, i);
            byte[] png = ProposalIconUtil.generateIcon(typ, toHex(cAccent), 128);
            addOval(slide, cx + 14, y + 14, 36, 36, cIce);
            addImg(pptx, slide, png, cx + 23, y + 23, 18, 18);

            // 카드 제목 (keywords[i])
            if (!kw.isEmpty()) {
                text(slide, kw, cx + 14, y + 58, cardW - 28, 26, 10.5, true, false, cAccent, null);
            }

            // bullet 항목 (content 균등 분배)
            if (per > 0) {
                int from    = i * per;
                int to      = Math.min(from + per, content.size());
                int bulletY = y + 88;
                for (int j = from; j < to; j++) {
                    if (bulletY + 22 > y + cardH - 8) break;
                    text(slide, "• " + content.get(j), cx + 14, bulletY, cardW - 28, 22,
                            9.5, false, false, DARK_TEXT, null);
                    bulletY += 24;
                }
            }

            // 카드 사이 화살표
            if (i < n - 1) {
                int ax = cx + cardW + 1;
                int ay = y + cardH / 2 - 8;
                text(slide, "→", ax, ay, arrW, 16, 9, false, false,
                        new Color(0xC0, 0xC8, 0xE0), TextParagraph.TextAlign.CENTER);
            }
        }

        addNotes(pptx, slide, notes);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 레이아웃 4: 2×N 그리드 카드형 (grid_cards)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addGridCardsSlide(XMLSlideShow pptx,
            String sTitle, String subtitle, String headline,
            List<String> keywords, List<String> content, String notes,
            Color cBase, Color cAccent, Color cGridCard, Color cGridBadge, Color cSubAccent) {

        XSLFSlide slide = pptx.createSlide();
        // 다크 배경 풀블리드
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBase);

        // 제목 (흰색)
        text(slide, sTitle, MARGIN, 30, CONT_W, 40, 24, true, false, WHITE, null);

        // 서브제목 또는 headline (accentColor 밝은 톤)
        String sub2 = !subtitle.isEmpty() ? subtitle : headline;
        if (!sub2.isEmpty()) {
            text(slide, sub2, MARGIN, 68, CONT_W, 28, 13, true, false, cSubAccent, null);
        }

        // 그리드 카드
        int n    = Math.max(content.size(), keywords.size());
        if (n == 0) { addNotes(pptx, slide, notes); return; }

        int cols  = n <= 4 ? 2 : 3;                           // 5개 이상이면 3열
        int rows  = (n + cols - 1) / cols;
        int gridY = 104;
        int gridH = SLIDE_H - gridY - 14;
        int gapX  = 16, gapY = 12;
        int cardW = (CONT_W - (cols - 1) * gapX) / cols;
        int cardH = (gridH - (rows - 1) * gapY) / rows;

        // 카드 설명 색: 연한 회색
        Color descColor = new Color(0xC7, 0xCB, 0xE8);

        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            int cx  = MARGIN + col * (cardW + gapX);
            int cy  = gridY  + row * (cardH + gapY);

            // 카드 배경
            addRect(slide, cx, cy, cardW, cardH, cGridCard);

            // 아이콘 원 배지
            int badgeS = 36;
            addOval(slide, cx + 14, cy + 14, badgeS, badgeS, cGridBadge);
            String kw  = i < keywords.size() ? keywords.get(i) : "";
            String ref = kw.isEmpty() && i < content.size() ? content.get(i) : kw;
            String typ = ProposalIconUtil.pickIcon(ref, i);
            byte[] png = ProposalIconUtil.generateIcon(typ, toHex(cSubAccent), 128);
            addImg(pptx, slide, png, cx + 23, cy + 23, 18, 18);

            // 소제목 (keywords[i], 흰색)
            if (!kw.isEmpty()) {
                text(slide, kw, cx + 14 + badgeS + 8, cy + 14,
                        cardW - badgeS - 36, badgeS, 13, true, false, WHITE, null);
            }

            // 설명 (content[i])
            if (i < content.size() && !content.get(i).isEmpty()) {
                text(slide, content.get(i), cx + 14, cy + 14 + badgeS + 8,
                        cardW - 28, cardH - 14 - badgeS - 20, 10.5, false, false, descColor, null);
            }
        }

        addNotes(pptx, slide, notes);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 공통 헤더 (keyword_list / process_cards 공유)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 슬라이드 상단 공통 헤더를 그리고 콘텐츠 시작 y 좌표를 반환한다.
     * 구성: 제목(cBase) → 서브제목(cAccent) → headline 배너(cBase 채움, WHITE 텍스트)
     */
    private static int drawCommonHeader(XSLFSlide slide,
            String sTitle, String subtitle, String headline,
            Color cBase, Color cAccent) {
        // 슬라이드 제목
        text(slide, sTitle, MARGIN, 30, CONT_W, 38, 22, true, false, cBase, null);
        int y = 68;

        // 서브제목
        if (!subtitle.isEmpty()) {
            text(slide, subtitle, MARGIN, y, CONT_W, 26, 12.5, true, false, cAccent, null);
            y += 28;
        }

        // headline 배너
        if (!headline.isEmpty()) {
            addRect(slide, MARGIN, y, CONT_W, 42, cBase);
            text(slide, headline, MARGIN + 14, y + 3, CONT_W - 28, 36,
                    12.5, true, false, WHITE, null);
            y += 50;
        }

        return y;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 발표자 노트
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addNotes(XMLSlideShow pptx, XSLFSlide slide, String notes) {
        if (notes == null || notes.trim().isEmpty()) return;
        try {
            XSLFNotes ns = pptx.getNotesSlide(slide);
            if (ns == null) return;
            for (XSLFShape shape : ns.getShapes()) {
                if (shape instanceof XSLFTextShape) {
                    XSLFTextParagraph p = ((XSLFTextShape) shape).addNewTextParagraph();
                    XSLFTextRun r = p.addNewTextRun();
                    r.setText(notes);
                    r.setFontSize(12.0);
                    r.setFontFamily(FONT);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 레이아웃 5: 인포그래픽형 (infographic) — 3구역 구조
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * infographic 슬라이드 — 3구역 구조.
     *
     * <pre>
     * ┌─────────────────────────────────────┐  ← y=0
     * │ 헤더존 (INFOGRAPHIC_HEADER_H=62pt 고정) │
     * │  title 22pt bold / subtitle 11pt gray│
     * │  하단 accentColor 구분선 2pt            │
     * ├─────────────────────────────────────┤  ← y=62
     * │ 본문존 (이미지 1장만, 위에 아무것도 금지)│
     * │  letterbox 삽입 (원본 비율 유지)       │
     * ├─────────────────────────────────────┤  ← y=SLIDE_H-32 (headline 있을 때만)
     * │ 푸터존 (32pt, headline 배너, 선택적)   │
     * └─────────────────────────────────────┘  ← y=SLIDE_H
     * </pre>
     *
     * <p>버그 방지 규칙:
     * <ol>
     *   <li>본문존에는 이미지 오브젝트 단 하나만. 겹치는 헤더 바/텍스트박스 절대 추가 금지.</li>
     *   <li>이미지 삽입 시 letterbox(원본 비율 유지) 처리 — 강제 스트레치 금지.</li>
     *   <li>헤더존 높이는 INFOGRAPHIC_HEADER_H 상수로 고정 — 텍스트 길이와 무관.</li>
     * </ol>
     */
    /**
     * 목차 슬라이드 (toc).
     * keywords[] = 섹션명, content[] = 각 섹션 한 줄 설명
     */
    private static void addTocSlide(XMLSlideShow pptx, XSLFPictureData wavePd,
            String sTitle, List<String> kw, List<String> ct,
            Color cBg, Color cBase, Color cAccent, Color cIce) {

        XSLFSlide slide = pptx.createSlide();
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBg);

        // 우측 웨이브 장식
        if (wavePd != null) {
            addPd(slide, wavePd, SLIDE_W - 220, 0, 220, SLIDE_H);
        }

        // 좌측 accentColor 세로 강조선
        addRect(slide, MARGIN - 12, 16, 4, SLIDE_H - 32, cAccent);

        // 제목
        String tocTitle = (sTitle == null || sTitle.isEmpty()) ? "목   차" : sTitle;
        text(slide, tocTitle, MARGIN, 14, 320, 34, 22, true, false, cBase, null);

        // 구분선
        addRect(slide, MARGIN, 52, CONT_W - 220, 2, tint(cBase, 0.75f));

        // 섹션 항목 목록
        int count  = kw.size();
        int startY = 62;
        int availH = SLIDE_H - startY - 16;
        int itemH  = (count > 0) ? Math.min(46, availH / count) : 46;

        for (int i = 0; i < count; i++) {
            int y = startY + i * itemH;

            // 번호 배지 (원형 accentColor)
            addOval(slide, MARGIN, y + 3, 22, 22, cAccent);
            text(slide, String.format("%02d", i + 1),
                    MARGIN + 1, y + 5, 22, 18, 9, true, false, WHITE, TextParagraph.TextAlign.CENTER);

            // 섹션명
            String kwText = (i < kw.size() && kw.get(i) != null) ? kw.get(i) : "";
            text(slide, kwText, MARGIN + 30, y + 2, CONT_W - 260, 20, 13, true, false, cBase, null);

            // 설명
            String ctText = (i < ct.size() && ct.get(i) != null) ? ct.get(i) : "";
            if (!ctText.isEmpty()) {
                text(slide, ctText, MARGIN + 30, y + 22, CONT_W - 260, 16, 9.5, false, false, GRAY_TEXT, null);
            }

            // 항목 하단 구분선 (마지막 항목 제외)
            if (i < count - 1) {
                addRect(slide, MARGIN + 30, y + itemH - 2, CONT_W - 260, 1, tint(cBase, 0.88f));
            }
        }
    }

    /**
     * 섹션 간지 슬라이드 (section_divider).
     * title   = 섹션 번호 (예: "01")
     * subtitle = 섹션 제목
     * headline = 한 줄 설명
     */
    private static void addSectionDividerSlide(XMLSlideShow pptx,
            String sTitle, String subtitle, String headline,
            Color cBase, Color cAccent) {

        XSLFSlide slide = pptx.createSlide();

        // 풀블리드 배경 (baseColor)
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBase);

        // 우하단 대형 섹션 번호 (ghost — baseColor보다 약간 밝게)
        Color cGhost = tint(cBase, 0.22f);
        text(slide, nvl(sTitle, ""), SLIDE_W - 260, SLIDE_H - 180, 260, 180, 130, true, false, cGhost, null);

        // accentColor 가로 강조선 (좌측 중앙 위)
        int centerY = SLIDE_H / 2;
        addRect(slide, MARGIN, centerY - 36, 64, 5, cAccent);

        // 섹션 제목 (subtitle)
        text(slide, nvl(subtitle, ""), MARGIN, centerY - 24, CONT_W - 80, 70, 34, true, false, WHITE, null);

        // 한 줄 설명 (headline)
        if (headline != null && !headline.isEmpty()) {
            Color cWhiteSoft = tint(WHITE, 0.35f);
            text(slide, headline, MARGIN, centerY + 52, CONT_W - 80, 28, 13, false, false, cWhiteSoft, null);
        }
    }

    private static void addInfographicSlide(XMLSlideShow pptx,
            String sTitle, String subtitle, String headline, String notes,
            String b64Image, Color cBg, Color cBase, Color cAccent) {

        XSLFSlide slide = pptx.createSlide();
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, cBg);

        // ── 헤더존 (y=0 ~ INFOGRAPHIC_HEADER_H, 고정) ─────────────────────────
        text(slide, sTitle, MARGIN, 8, CONT_W, 32, 22, true, false, cBase, null);
        if (!subtitle.isEmpty()) {
            text(slide, subtitle, MARGIN, 38, CONT_W, 20, 11, false, false, GRAY_TEXT, null);
        }
        // accentColor 구분선 (헤더존 하단)
        addRect(slide, 0, INFOGRAPHIC_HEADER_H - 2, SLIDE_W, 2, cAccent);

        // ── 본문존 좌표 계산 ────────────────────────────────────────────────────
        // subtitle이 이미 헤더에 있으면 headline 푸터 생략 (중복 방지)
        boolean hasFooter = !headline.isEmpty() && subtitle.isEmpty();
        int footerH = hasFooter ? INFOGRAPHIC_FOOTER_H : 0;
        int bodyY   = INFOGRAPHIC_HEADER_H;             // 62pt
        int bodyH   = SLIDE_H - INFOGRAPHIC_HEADER_H - footerH;  // 본문존 높이

        // ── 본문존 (이미지 1장만 — 이 블록 안에서 다른 도형/텍스트박스 추가 금지) ─
        try {
            byte[] imgBytes = Base64.getDecoder().decode(b64Image);
            addImgLetterbox(pptx, slide, imgBytes, 0, bodyY, SLIDE_W, bodyH, cBg);
        } catch (Exception e) {
            // base64 디코딩 실패 → 빈 본문존 (연한 배경으로 표시)
            addRect(slide, 0, bodyY, SLIDE_W, bodyH, tint(cBg, 0.30f));
        }

        // ── 푸터존 (headline 배너, subtitle이 없고 headline이 있을 때만) ─────────
        if (hasFooter) {
            addRect(slide, 0, SLIDE_H - footerH, SLIDE_W, footerH, cBase);
            text(slide, headline, MARGIN, SLIDE_H - footerH + 6, CONT_W, footerH - 12,
                    11.5, true, false, WHITE, null);
        }

        addNotes(pptx, slide, notes);
    }

    /**
     * PNG/JPEG 이미지를 지정 구역 안에 원본 비율 유지(letterbox)로 삽입.
     * 구역 비율과 이미지 비율이 다를 때 남는 공간은 bgColor로 채운다.
     * 강제 스트레치 없음.
     */
    private static void addImgLetterbox(XMLSlideShow pptx, XSLFSlide slide,
            byte[] imgBytes, int zoneX, int zoneY, int zoneW, int zoneH, Color bgColor) {
        // 구역 배경 먼저 채움 (bgColor=null 이면 스킵 — 이미지+이미지 오버레이 시)
        if (bgColor != null) addRect(slide, zoneX, zoneY, zoneW, zoneH, bgColor);

        try {
            // 이미지 실제 픽셀 크기 읽기
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imgBytes));
            if (bi == null) {
                // 디코딩 실패 → 구역 전체에 강제 삽입 (최후 수단)
                addImg(pptx, slide, imgBytes, zoneX, zoneY, zoneW, zoneH);
                return;
            }
            int imgW = bi.getWidth();
            int imgH = bi.getHeight();

            // letterbox 계산: 구역 안에 이미지 비율 유지로 최대 크기 배치
            double zoneAspect = (double) zoneW / zoneH;
            double imgAspect  = (double) imgW  / imgH;

            int destW, destH, destX, destY;
            if (imgAspect >= zoneAspect) {
                // 이미지가 구역보다 가로가 더 넓음 → 가로를 구역에 맞추고 상하 여백
                destW = zoneW;
                destH = (int) Math.round((double) zoneW / imgAspect);
                destX = zoneX;
                destY = zoneY + (zoneH - destH) / 2;
            } else {
                // 이미지가 구역보다 세로가 더 높음 → 세로를 구역에 맞추고 좌우 여백
                destH = zoneH;
                destW = (int) Math.round((double) zoneH * imgAspect);
                destX = zoneX + (zoneW - destW) / 2;
                destY = zoneY;
            }
            addImg(pptx, slide, imgBytes, destX, destY, destW, destH);
        } catch (Exception e) {
            // 크기 읽기 실패 → 구역 전체에 삽입 (비율 왜곡 감수)
            addImg(pptx, slide, imgBytes, zoneX, zoneY, zoneW, zoneH);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 도형 / 이미지 헬퍼
    // ═══════════════════════════════════════════════════════════════════════════

    private static void addRect(XSLFSlide slide, int x, int y, int w, int h, Color color) {
        XSLFAutoShape s = slide.createAutoShape();
        s.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        s.setAnchor(new Rectangle(x, y, w, h));
        s.setFillColor(color);
        s.setLineColor(color);
    }

    private static void addOval(XSLFSlide slide, int x, int y, int w, int h, Color color) {
        XSLFAutoShape s = slide.createAutoShape();
        s.setShapeType(org.apache.poi.sl.usermodel.ShapeType.ELLIPSE);
        s.setAnchor(new Rectangle(x, y, w, h));
        s.setFillColor(color);
        s.setLineColor(color);
    }

    /** byte[] PNG → 지정 위치/크기로 슬라이드에 삽입 */
    private static void addImg(XMLSlideShow pptx, XSLFSlide slide,
            byte[] png, int x, int y, int w, int h) {
        try {
            XSLFPictureData pd = pptx.addPicture(png, XSLFPictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle(x, y, w, h));
        } catch (Exception ignored) {}
    }

    /** 기존 PictureData(wavePd 등)를 지정 위치/크기로 삽입 (중복 저장 방지) */
    private static void addPd(XSLFSlide slide, XSLFPictureData pd,
            int x, int y, int w, int h) {
        try {
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle(x, y, w, h));
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 텍스트 박스 헬퍼
    // ═══════════════════════════════════════════════════════════════════════════

    private static void text(XSLFSlide slide, String txt,
            int x, int y, int w, int h,
            double fs, boolean bold, boolean italic,
            Color color, TextParagraph.TextAlign align) {
        text(slide, txt, x, y, w, h, fs, bold, italic, color, align, false);
    }

    /**
     * 텍스트박스 생성. noWrap=true 이면 word-wrap을 끄고 한 줄로만 렌더링한다.
     * 슬롯 기반 헤더/푸터 렌더링에서 좁은 x 위치로 인한 줄바꿈·겹침 방지용.
     */
    private static void text(XSLFSlide slide, String txt,
            int x, int y, int w, int h,
            double fs, boolean bold, boolean italic,
            Color color, TextParagraph.TextAlign align, boolean noWrap) {
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(new Rectangle(x, y, w, h));
        if (noWrap) tb.setWordWrap(false);
        tb.clearText();   // createTextBox()가 만든 기본 빈 문단 제거 — 없으면 텍스트가 두 번째 줄로 밀림
        XSLFTextParagraph p = tb.addNewTextParagraph();
        if (align != null) p.setTextAlign(align);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(nvl(txt, ""));
        r.setFontSize(fs);
        r.setBold(bold);
        r.setItalic(italic);
        r.setFontColor(color);
        r.setFontFamily(FONT);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 색상 유틸
    // ═══════════════════════════════════════════════════════════════════════════

    private static Color parseHex(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() >= 6) {
                return new Color(
                        Integer.parseInt(h.substring(0, 2), 16),
                        Integer.parseInt(h.substring(2, 4), 16),
                        Integer.parseInt(h.substring(4, 6), 16));
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    /** Color → "#RRGGBB" */
    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 색 밝히기 (factor: 0=원색, 1=흰색) */
    private static Color tint(Color c, float factor) {
        return new Color(
                clamp(c.getRed()   + (int)((255 - c.getRed())   * factor)),
                clamp(c.getGreen() + (int)((255 - c.getGreen()) * factor)),
                clamp(c.getBlue()  + (int)((255 - c.getBlue())  * factor)));
    }

    /** 색 어둡게 (factor: 0=원색, 1=검정) */
    private static Color darken(Color c, float factor) {
        return new Color(
                clamp((int)(c.getRed()   * (1 - factor))),
                clamp((int)(c.getGreen() * (1 - factor))),
                clamp((int)(c.getBlue()  * (1 - factor))));
    }

    /** RGB에 고정 delta 더해 밝히기 */
    private static Color lighten(Color c, int delta) {
        return new Color(clamp(c.getRed() + delta), clamp(c.getGreen() + delta), clamp(c.getBlue() + delta));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ═══════════════════════════════════════════════════════════════════════════
    // 문자열 / 목록 유틸
    // ═══════════════════════════════════════════════════════════════════════════

    private static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj instanceof List) {
            List<Object> raw = (List<Object>) obj;
            List<String> result = new ArrayList<>();
            for (Object o : raw) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static String nvl(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s.trim();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 컴포넌트 기반 빌드 — ComponentPageInfo DTO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 컴포넌트 기반 빌드용 페이지 단위 입력 데이터.
     * 표지·간지는 이미지 bytes, 일반 슬라이드는 SlideVO 텍스트 필드를 담는다.
     */
    public static class ComponentPageInfo {
        public final String chapterRoman;
        public final String sectionTitle;
        public final String pageLabel;
        public final String projectNm;
        public final String orgNm;
        public final String submitterNm;
        public final String layoutTypeCd;
        public final String subTocList;
        // 슬라이드 컬러 팔레트 인덱스 (colorIndex % bases.size() 로 base/accent 선택)
        // 표지(001) / 간지(002)는 0 고정, 일반 슬라이드는 SlideVO.colorIndex 값을 그대로 전달
        public final int colorIndex;
        // 표지·간지 배경
        public final byte[] imageBytes;
        // 일반 슬라이드 컨텐츠
        public final String eyebrowTxt;
        public final String titleTxt;
        public final String subtitleTxt;
        public final String highlightBannerTxt;
        public final String componentsJson;
        public final String conclusionRibbonTxt;

        /** 표지(001) / 간지(002) 생성자 — imageBytes 기반 (colorIndex=0 고정) */
        public ComponentPageInfo(byte[] imageBytes, String chapterRoman, String sectionTitle,
                                  String pageLabel, String projectNm, String orgNm, String submitterNm,
                                  String layoutTypeCd, String subTocList) {
            this.colorIndex        = 0;
            this.imageBytes        = imageBytes;
            this.chapterRoman      = nvl(chapterRoman, "Ⅰ");
            this.sectionTitle      = nvl(sectionTitle, "");
            this.pageLabel         = nvl(pageLabel, "");
            this.projectNm         = nvl(projectNm, "");
            this.orgNm             = nvl(orgNm, "");
            this.submitterNm       = nvl(submitterNm, "");
            this.layoutTypeCd      = layoutTypeCd;
            this.subTocList        = nvl(subTocList, "");
            this.eyebrowTxt        = null;
            this.titleTxt          = null;
            this.subtitleTxt       = null;
            this.highlightBannerTxt = null;
            this.componentsJson    = null;
            this.conclusionRibbonTxt = null;
        }

        /** 일반 슬라이드 생성자 — componentsJson 기반 (SlideVO.colorIndex를 그대로 전달) */
        public ComponentPageInfo(String chapterRoman, String sectionTitle, String pageLabel,
                                  String projectNm, String orgNm, String submitterNm, String layoutTypeCd,
                                  String eyebrowTxt, String titleTxt, String subtitleTxt,
                                  String highlightBannerTxt, String componentsJson, String conclusionRibbonTxt,
                                  int colorIndex) {
            this.colorIndex        = colorIndex;
            this.imageBytes        = null;
            this.chapterRoman      = nvl(chapterRoman, "Ⅰ");
            this.sectionTitle      = nvl(sectionTitle, "");
            this.pageLabel         = nvl(pageLabel, "");
            this.projectNm         = nvl(projectNm, "");
            this.orgNm             = nvl(orgNm, "");
            this.submitterNm       = nvl(submitterNm, "");
            this.layoutTypeCd      = layoutTypeCd;
            this.subTocList        = "";
            this.eyebrowTxt        = eyebrowTxt;
            this.titleTxt          = nvl(titleTxt, "");
            this.subtitleTxt       = subtitleTxt;
            this.highlightBannerTxt = highlightBannerTxt;
            this.componentsJson    = componentsJson;
            this.conclusionRibbonTxt = conclusionRibbonTxt;
        }

        /** renderHeaderSlots / renderFooterSlots 재사용을 위한 PageInfo 변환 */
        public PageInfo toPageInfo() {
            return new PageInfo(imageBytes, chapterRoman, sectionTitle, pageLabel,
                    projectNm, orgNm, submitterNm, layoutTypeCd, subTocList);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 컴포넌트 기반 빌드 — 공개 API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * componentsJson 기반 제안서 PPTX/PDF 빌드 (텍스트 편집 가능).
     * <p>슬라이드 본문을 PNG 이미지가 아닌 POI 네이티브 도형/텍스트박스로 렌더링한다.
     * 헤더·푸터는 TB_PT_TEMPLATE JSON 레이아웃을 그대로 재사용한다.
     *
     * @param pages                ComponentPageInfo 리스트 (순서대로 슬라이드 생성)
     * @param docSize              "a4" | "43" | "169"
     * @param bgColor              배경색 hex
     * @param baseColor            기본색 hex
     * @param accentColor          강조색 hex
     * @param headerComponentsJson TB_PT_TEMPLATE.HEADER_COMPONENTS_JSON (null 허용 → 기본 헤더)
     * @param footerComponentsJson TB_PT_TEMPLATE.FOOTER_COMPONENTS_JSON (null 허용 → 기본 푸터)
     */
    public static byte[] buildProposalDocFromComponents(
            List<ComponentPageInfo> pages,
            String docSize,
            String bgColor, List<String> bases, List<String> accents,
            String headerComponentsJson, String footerComponentsJson) throws IOException {

        // ── 슬라이드 크기 결정 ────────────────────────────────────────────────
        final int slideW, slideH;
        if ("a4".equalsIgnoreCase(docSize)) {
            slideW = 595; slideH = 842;
        } else if ("43".equals(docSize)) {
            slideW = 720; slideH = 540;
        } else {
            slideW = 720; slideH = 405; // 16:9 기본
        }

        Color cBg = parseHex(bgColor, new Color(0xFF, 0xFF, 0xFF));
        // cBase / cAccent는 슬라이드별로 루프 안에서 page.colorIndex 기반으로 계산

        // 16:9(405) 기준 스케일
        double scale = slideH / 405.0;

        // 헤더/푸터 템플릿 파싱
        Gson localGson = new Gson();
        TemplateLayout headerLayout = parseTemplateLayout(headerComponentsJson, localGson);
        TemplateLayout footerLayout = parseTemplateLayout(footerComponentsJson, localGson);

        final double HEADER_PCT_DEFAULT = 0.09;
        final double FOOTER_PCT_DEFAULT = 0.05;

        int HEADER_H = (headerLayout != null && headerLayout.height > 0)
                ? (int)(headerLayout.height * slideH / 100.0)
                : (int)(slideH * HEADER_PCT_DEFAULT);
        int FOOTER_H = (footerLayout != null && footerLayout.height > 0)
                ? (int)(footerLayout.height * slideH / 100.0)
                : (int)(slideH * FOOTER_PCT_DEFAULT);
        HEADER_H = Math.max(30, HEADER_H);
        FOOTER_H = Math.max(14, FOOTER_H);

        int BODY_Y   = HEADER_H;
        int BODY_H   = slideH - HEADER_H - FOOTER_H;
        int FOOTER_Y = slideH - FOOTER_H;
        int MARGIN   = Math.max(18, (int)(28 * scale));

        try (XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            pptx.setPageSize(new Dimension(slideW, slideH));

            for (ComponentPageInfo page : pages) {
                XSLFSlide slide = pptx.createSlide();

                // ── 슬라이드별 컬러 결정 (colorIndex 기반 팔레트 회전) ─────────
                int ci = page.colorIndex;
                Color cBase   = bases.isEmpty()   ? new Color(0x5B, 0x4F, 0xE9)
                        : parseHex(bases.get(ci % bases.size()),     new Color(0x5B, 0x4F, 0xE9));
                Color cAccent = accents.isEmpty() ? new Color(0xE0, 0x8A, 0x2C)
                        : parseHex(accents.get(ci % accents.size()), new Color(0xE0, 0x8A, 0x2C));

                // 전체 배경
                addRect(slide, 0, 0, slideW, slideH, cBg);

                if ("001".equals(page.layoutTypeCd)) {
                    // 표지 — 전체 채움
                    if (page.imageBytes != null && page.imageBytes.length > 0) {
                        addImgLetterbox(pptx, slide, page.imageBytes, 0, 0, slideW, slideH, cBg);
                    }
                    continue;
                }

                if ("002".equals(page.layoutTypeCd)) {
                    // 간지 — 이미지 배경 + 텍스트 오버레이
                    if (page.imageBytes != null && page.imageBytes.length > 0) {
                        addImgLetterbox(pptx, slide, page.imageBytes, 0, 0, slideW, slideH, cBg);
                    }
                    renderDividerTextOverlay(slide, page.toPageInfo(), slideW, slideH, cBase, cAccent);
                    continue;
                }

                // 일반 슬라이드: 헤더 + 바디(컴포넌트) + 푸터
                // ── 헤더 ─────────────────────────────────────────────────────
                addRect(slide, 0, 0, slideW, HEADER_H, new Color(0xFF, 0xFF, 0xFF));
                if (headerLayout != null) {
                    renderHeaderSlots(slide, headerLayout, page.toPageInfo(),
                            slideW, HEADER_H, scale, cBase, cAccent, false);
                } else {
                    // 기본 헤더: 소목차 타이틀 바
                    addRect(slide, 0, 0, slideW, HEADER_H, cBase);
                    text(slide, page.sectionTitle, MARGIN, 4, slideW - MARGIN * 2, HEADER_H - 8,
                            Math.max(8, 12 * scale), true, false, WHITE, null);
                }

                // ── 바디 — componentsJson 기반 렌더링 ─────────────────────────
                renderSlideBodyFromComponents(slide, page, 0, BODY_Y, slideW, BODY_H,
                        MARGIN, cBase, cAccent, cBg, scale);

                // ── 푸터 ─────────────────────────────────────────────────────
                addRect(slide, 0, FOOTER_Y, slideW, FOOTER_H, new Color(0xF8, 0xF9, 0xFA));
                if (footerLayout != null) {
                    renderFooterSlots(slide, footerLayout, page.toPageInfo(),
                            slideW, FOOTER_H, FOOTER_Y, scale);
                } else {
                    // 기본 푸터: 기관명 | 페이지 | 제안사
                    double fsF = Math.max(5, 6.5 * scale);
                    text(slide, page.orgNm, MARGIN, FOOTER_Y + 3, (slideW - MARGIN * 2) / 3, FOOTER_H - 6,
                            fsF, false, false, DARK_TEXT, null);
                    text(slide, page.pageLabel, 0, FOOTER_Y + 3, slideW, FOOTER_H - 6,
                            fsF, true, false, DARK_TEXT, TextParagraph.TextAlign.CENTER);
                    text(slide, page.submitterNm, MARGIN, FOOTER_Y + 3, slideW - MARGIN * 2, FOOTER_H - 6,
                            fsF, false, false, DARK_TEXT, TextParagraph.TextAlign.RIGHT);
                }
            }

            pptx.write(out);
            return out.toByteArray();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 슬라이드 바디 렌더링 (컴포넌트 기반)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void renderSlideBodyFromComponents(
            XSLFSlide slide, ComponentPageInfo page,
            int bx, int by, int bw, int bh, int margin,
            Color cBase, Color cAccent, Color cBg, double scale) {

        addRect(slide, bx, by, bw, bh, cBg);

        int contX = bx + margin;
        int contW = bw - margin * 2;
        int curY  = by + Math.max(4, (int)(8 * scale));

        // ── 1. eyebrowTxt ───────────────────────────────────────────────────
        String eyebrow = nvl(page.eyebrowTxt, "");
        if (!eyebrow.isEmpty()) {
            int h = Math.max(9, (int)(12 * scale));
            text(slide, eyebrow.toUpperCase(), contX, curY, contW, h,
                    Math.max(5, 6.5 * scale), false, false, cAccent, null);
            curY += h + Math.max(2, (int)(3 * scale));
        }

        // ── 2. titleTxt ─────────────────────────────────────────────────────
        String title = nvl(page.titleTxt, "");
        if (!title.isEmpty()) {
            int h = Math.max(14, (int)(22 * scale));
            text(slide, title, contX, curY, contW, h,
                    Math.max(8, 14 * scale), true, false, DARK_TEXT, null);
            curY += h + Math.max(2, (int)(3 * scale));
        }

        // ── 3. subtitleTxt ──────────────────────────────────────────────────
        String subtitle = nvl(page.subtitleTxt, "");
        if (!subtitle.isEmpty()) {
            int h = Math.max(10, (int)(14 * scale));
            text(slide, subtitle, contX, curY, contW, h,
                    Math.max(6, 8.5 * scale), false, false, GRAY_TEXT, null);
            curY += h + Math.max(2, (int)(3 * scale));
        }

        // ── 4. 구분선 ──────────────────────────────────────────────────────
        addRect(slide, contX, curY, contW, 1, new Color(0xE5, 0xE7, 0xEB));
        curY += 1 + Math.max(4, (int)(6 * scale));

        // ── 5. 결론 리본 (하단 고정, 먼저 높이 계산) ──────────────────────
        int bodyBottom = by + bh;
        String ribbon = nvl(page.conclusionRibbonTxt, "");
        int ribbonH = ribbon.isEmpty() ? 0 : Math.max(14, (int)(22 * scale));

        // ── 6. highlightBannerTxt ───────────────────────────────────────────
        String banner = nvl(page.highlightBannerTxt, "");
        if (!banner.isEmpty()) {
            int h = Math.max(14, (int)(22 * scale));
            addRect(slide, contX, curY, contW, h, tint(cBase, 0.88f));
            addRect(slide, contX, curY, 3, h, cBase);
            text(slide, banner, contX + 8, curY + Math.max(2, (int)(3 * scale)),
                    contW - 12, h - Math.max(4, (int)(6 * scale)),
                    Math.max(6, 8 * scale), true, false, cBase, null);
            curY += h + Math.max(4, (int)(6 * scale));
        }

        // ── 7. componentsJson 렌더링 (가용 영역 균등 분할) ─────────────────
        int compAreaH = bodyBottom - curY - (ribbonH > 0 ? ribbonH + Math.max(4, (int)(6 * scale)) : Math.max(4, (int)(4 * scale)));
        String compsJson = page.componentsJson;
        if (compsJson != null && !compsJson.trim().isEmpty()) {
            try {
                com.google.gson.JsonArray comps = JsonParser.parseString(compsJson).getAsJsonArray();
                int count = comps.size();
                if (count > 0 && compAreaH > 0) {
                    int gap = Math.max(4, (int)(6 * scale));
                    int eachH = (compAreaH - gap * (count - 1)) / count;
                    int compY = curY;
                    for (int i = 0; i < count; i++) {
                        com.google.gson.JsonObject comp = comps.get(i).getAsJsonObject();
                        String type = comp.has("type") ? comp.get("type").getAsString() : "";
                        com.google.gson.JsonObject content = extractCompContent(comp);
                        int thisH = (i == count - 1) ? (bodyBottom - (ribbonH > 0 ? ribbonH + Math.max(4, (int)(6 * scale)) : Math.max(4, (int)(4 * scale))) - compY) : eachH;
                        if (content != null && !type.isEmpty() && thisH > 4) {
                            renderSlideComponent(slide, type, content, contX, compY, contW, thisH, cBase, cAccent, scale);
                        }
                        compY += eachH + gap;
                    }
                }
            } catch (Exception ignored) {
                // JSON 파싱 실패 시 빈 영역으로 처리
            }
        }

        // ── 8. conclusionRibbonTxt ──────────────────────────────────────────
        if (!ribbon.isEmpty()) {
            int ribbonY = bodyBottom - ribbonH - Math.max(2, (int)(3 * scale));
            addRect(slide, contX, ribbonY, contW, ribbonH, tint(cAccent, 0.85f));
            addRect(slide, contX, ribbonY, 3, ribbonH, cAccent);
            text(slide, ribbon, contX + 8, ribbonY + Math.max(2, (int)(3 * scale)),
                    contW - 12, ribbonH - Math.max(4, (int)(6 * scale)),
                    Math.max(6, 8 * scale), false, true, darken(cAccent, 0.25f), null);
        }
    }

    /** componentsJson 항목에서 content 객체 추출 (포맷 1/2/3 통합) */
    private static com.google.gson.JsonObject extractCompContent(com.google.gson.JsonObject comp) {
        // 포맷 1: { type, content: {...} }
        if (comp.has("content") && comp.get("content").isJsonObject()) {
            return comp.getAsJsonObject("content");
        }
        // 포맷 2: { type, cards:[] } 등 (content 래퍼 없음)
        if (comp.has("type")) {
            com.google.gson.JsonObject synthetic = new com.google.gson.JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : comp.entrySet()) {
                if (!"type".equals(entry.getKey())) synthetic.add(entry.getKey(), entry.getValue());
            }
            return synthetic.size() > 0 ? synthetic : null;
        }
        return null;
    }

    /** JsonObject 에서 문자열 값 추출 (null 안전) */
    private static String jsonStr(com.google.gson.JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        String v = obj.get(key).getAsString().trim();
        return v.isEmpty() ? def : v;
    }

    /** 컴포넌트 타입별 렌더러 디스패처 */
    private static void renderSlideComponent(XSLFSlide slide, String type,
            com.google.gson.JsonObject content,
            int x, int y, int w, int h,
            Color cBase, Color cAccent, double scale) {
        int pad = Math.max(2, (int)(3 * scale));
        // 각 컴포넌트 사이 내부 패딩
        int ix = x + pad; int iy = y + pad; int iw = w - pad * 2; int ih = h - pad * 2;
        if (iw <= 0 || ih <= 0) return;
        switch (type) {
            case "card_grid":         renderCompCardGrid(slide, content, ix, iy, iw, ih, cBase, cAccent, scale); break;
            case "process_flow":      renderCompProcessFlow(slide, content, ix, iy, iw, ih, cBase, scale); break;
            case "requirement_table": renderCompRequirementTable(slide, content, ix, iy, iw, ih, cBase, scale); break;
            case "credential_grid":   renderCompCredentialGrid(slide, content, ix, iy, iw, ih, cBase, cAccent, scale); break;
            case "icon_chip_group":   renderCompIconChipGroup(slide, content, ix, iy, iw, ih, cBase, scale); break;
            case "step_flow_bar":     renderCompStepFlowBar(slide, content, ix, iy, iw, ih, cBase, scale); break;
            case "callout_box":       renderCompCalloutBox(slide, content, ix, iy, iw, ih, scale); break;
            default: break;
        }
    }

    // ── card_grid ─────────────────────────────────────────────────────────────

    private static void renderCompCardGrid(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, Color cAccent, double scale) {
        if (!content.has("cards") || !content.get("cards").isJsonArray()) return;
        com.google.gson.JsonArray cards = content.getAsJsonArray("cards");
        int count = cards.size();
        if (count == 0) return;

        int gap  = Math.max(4, (int)(8 * scale));
        int cardW = (w - gap * (count - 1)) / count;

        for (int i = 0; i < count; i++) {
            com.google.gson.JsonObject card = cards.get(i).getAsJsonObject();
            String cardTitle = jsonStr(card, "title", "");
            String cardDesc  = jsonStr(card, "desc", "");
            int cx = x + i * (cardW + gap);

            // 카드 배경
            addRect(slide, cx, y, cardW, h, new Color(0xF9, 0xFA, 0xFB));
            // 좌측 강조 바
            addRect(slide, cx, y, Math.max(2, (int)(3 * scale)), h, cBase);

            int innerX  = cx + Math.max(5, (int)(8 * scale));
            int innerW  = cardW - Math.max(7, (int)(12 * scale));
            int padTop  = Math.max(4, (int)(6 * scale));
            int titleH  = Math.max(10, (int)(15 * scale));
            int descH   = h - padTop - titleH - Math.max(4, (int)(6 * scale));

            text(slide, cardTitle, innerX, y + padTop, innerW, titleH,
                    Math.max(6, 8.5 * scale), true, false, DARK_TEXT, null);
            if (!cardDesc.isEmpty() && descH > 0) {
                text(slide, cardDesc, innerX, y + padTop + titleH + 2, innerW, descH,
                        Math.max(5, 7 * scale), false, false, GRAY_TEXT, null);
            }
        }
    }

    // ── process_flow ─────────────────────────────────────────────────────────

    private static void renderCompProcessFlow(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, double scale) {
        if (!content.has("steps") || !content.get("steps").isJsonArray()) return;
        com.google.gson.JsonArray steps = content.getAsJsonArray("steps");
        int count = steps.size();
        if (count == 0) return;

        int arrowW = Math.max(10, (int)(16 * scale));
        int stepW  = (w - arrowW * (count - 1)) / count;
        int numSz  = Math.max(12, (int)(18 * scale));
        Color stepBg = new Color(0xF3, 0xF4, 0xF6);

        for (int i = 0; i < count; i++) {
            com.google.gson.JsonObject step = steps.get(i).getAsJsonObject();
            String stepTitle = jsonStr(step, "title", "");
            String stepDesc  = jsonStr(step, "desc", "");
            int sx = x + i * (stepW + arrowW);

            // 단계 배경
            addRect(slide, sx, y, stepW, h, stepBg);

            // 번호 원
            int numX = sx + (stepW - numSz) / 2;
            int numY = y + Math.max(4, (int)(6 * scale));
            addOval(slide, numX, numY, numSz, numSz, cBase);
            text(slide, String.valueOf(i + 1), numX, numY, numSz, numSz,
                    Math.max(5, 7 * scale), true, false, WHITE, TextParagraph.TextAlign.CENTER);

            int textY   = numY + numSz + Math.max(4, (int)(6 * scale));
            int titleH  = Math.max(10, (int)(14 * scale));
            int descH   = h - (textY - y) - titleH - Math.max(4, (int)(6 * scale));

            text(slide, stepTitle, sx + 3, textY, stepW - 6, titleH,
                    Math.max(5.5, 7.5 * scale), true, false, DARK_TEXT, TextParagraph.TextAlign.CENTER);
            if (!stepDesc.isEmpty() && descH > 0) {
                text(slide, stepDesc, sx + 3, textY + titleH + 2, stepW - 6, descH,
                        Math.max(5, 6.5 * scale), false, false, GRAY_TEXT, TextParagraph.TextAlign.CENTER);
            }

            // 화살표
            if (i < count - 1) {
                int arrowX = sx + stepW + 2;
                int arrowY = y + h / 2 - Math.max(4, (int)(6 * scale));
                text(slide, "▶", arrowX, arrowY, arrowW - 4, Math.max(10, (int)(12 * scale)),
                        Math.max(6, 8 * scale), false, false, new Color(0xD1, 0xD5, 0xDB),
                        TextParagraph.TextAlign.CENTER);
            }
        }
    }

    // ── requirement_table ────────────────────────────────────────────────────

    private static void renderCompRequirementTable(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, double scale) {
        if (!content.has("rows") || !content.get("rows").isJsonArray()) return;
        com.google.gson.JsonArray rows = content.getAsJsonArray("rows");
        int rowCount = rows.size();
        if (rowCount == 0) return;

        // 컬럼 폭 (요구사항:18%, 요구내용:40%, 대응방안:나머지)
        int col1W = (int)(w * 0.18);
        int col2W = (int)(w * 0.40);
        int col3W = w - col1W - col2W;
        int[] colWidths = {col1W, col2W, col3W};
        String[] headers = {"요구사항", "요구내용", "대응방안"};

        int headerH  = Math.max(14, (int)(18 * scale));
        int dataRowH = Math.max(10, (h - headerH) / Math.max(1, rowCount));
        Color border = new Color(0xE5, 0xE7, 0xEB);

        // 헤더 행
        int xOff = x;
        for (int c = 0; c < 3; c++) {
            addRect(slide, xOff, y, colWidths[c], headerH, cBase);
            text(slide, headers[c], xOff + 4, y + 2, colWidths[c] - 8, headerH - 4,
                    Math.max(5, 7 * scale), true, false, WHITE, null);
            xOff += colWidths[c];
        }

        // 데이터 행
        for (int r = 0; r < rowCount; r++) {
            com.google.gson.JsonObject row = rows.get(r).getAsJsonObject();
            String[] cells = {
                jsonStr(row, "reqNo", ""),
                jsonStr(row, "reqContent", ""),
                jsonStr(row, "response", "")
            };
            int rowY = y + headerH + r * dataRowH;
            Color rowBg = (r % 2 == 0) ? new Color(0xFF, 0xFF, 0xFF) : new Color(0xF9, 0xFA, 0xFB);
            xOff = x;
            for (int c = 0; c < 3; c++) {
                addRect(slide, xOff, rowY, colWidths[c], dataRowH, rowBg);
                // 하단 경계선
                addRect(slide, xOff, rowY + dataRowH - 1, colWidths[c], 1, border);
                text(slide, cells[c], xOff + 4, rowY + 2, colWidths[c] - 8, dataRowH - 4,
                        Math.max(4.5, 6 * scale), c == 0, false, DARK_TEXT, null);
                xOff += colWidths[c];
            }
        }
    }

    // ── credential_grid ──────────────────────────────────────────────────────

    private static void renderCompCredentialGrid(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, Color cAccent, double scale) {
        if (!content.has("items") || !content.get("items").isJsonArray()) return;
        com.google.gson.JsonArray items = content.getAsJsonArray("items");
        int count = items.size();
        if (count == 0) return;

        int cols   = Math.min(2, count);
        int rows   = (count + cols - 1) / cols;
        int gap    = Math.max(4, (int)(8 * scale));
        int itemW  = (w - gap * (cols - 1)) / cols;
        int itemH  = (h - gap * (rows - 1)) / rows;

        for (int i = 0; i < count; i++) {
            com.google.gson.JsonObject item = items.get(i).getAsJsonObject();
            String itemTitle = jsonStr(item, "title", "");
            String itemDesc  = jsonStr(item, "desc", "");
            int col = i % cols;
            int row = i / cols;
            int ix  = x + col * (itemW + gap);
            int iy  = y + row * (itemH + gap);

            // 배경
            addRect(slide, ix, iy, itemW, itemH, new Color(0xF9, 0xFA, 0xFB));
            // 상단 강조 바 (accent)
            int barH = Math.max(2, (int)(3 * scale));
            addRect(slide, ix, iy, itemW, barH, cAccent);

            int padH   = Math.max(4, (int)(6 * scale));
            int padTop = barH + Math.max(4, (int)(6 * scale));
            int titleH = Math.max(10, (int)(14 * scale));
            int descH  = itemH - padTop - titleH - Math.max(4, (int)(6 * scale));

            text(slide, itemTitle, ix + padH, iy + padTop, itemW - padH * 2, titleH,
                    Math.max(6, 8 * scale), true, false, DARK_TEXT, null);
            if (!itemDesc.isEmpty() && descH > 0) {
                text(slide, itemDesc, ix + padH, iy + padTop + titleH + 2, itemW - padH * 2, descH,
                        Math.max(5, 6.5 * scale), false, false, GRAY_TEXT, null);
            }
        }
    }

    // ── icon_chip_group ──────────────────────────────────────────────────────

    private static void renderCompIconChipGroup(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, double scale) {
        if (!content.has("chips") || !content.get("chips").isJsonArray()) return;
        com.google.gson.JsonArray chips = content.getAsJsonArray("chips");

        int chipH  = Math.max(12, (int)(18 * scale));
        int padHor = Math.max(6, (int)(10 * scale));
        int gap    = Math.max(4, (int)(6 * scale));
        int curX   = x;
        int curY   = y + Math.max(2, (int)(4 * scale));

        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).isJsonNull()) continue;
            String label = chips.get(i).getAsString().trim();
            if (label.isEmpty()) continue;

            // 칩 폭 추정 (문자당 약 5.5pt × scale)
            int chipW = (int)(label.length() * 5.5 * Math.min(scale, 1.4)) + padHor * 2;
            chipW = Math.max(28, Math.min(w / 2, chipW));

            // 줄 바꿈
            if (curX + chipW > x + w && curX > x) {
                curX = x;
                curY += chipH + gap;
            }
            if (curY + chipH > y + h) break;

            addRect(slide, curX, curY, chipW, chipH, cBase);
            text(slide, label, curX + 3, curY, chipW - 6, chipH,
                    Math.max(5, 6.5 * scale), false, false, WHITE, TextParagraph.TextAlign.CENTER);
            curX += chipW + gap;
        }
    }

    // ── step_flow_bar ────────────────────────────────────────────────────────

    private static void renderCompStepFlowBar(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, Color cBase, double scale) {
        if (!content.has("steps") || !content.get("steps").isJsonArray()) return;
        com.google.gson.JsonArray steps = content.getAsJsonArray("steps");
        int count = steps.size();
        if (count == 0) return;

        // 상단 경계선
        addRect(slide, x, y, w, 1, new Color(0xE5, 0xE7, 0xEB));

        int gap    = Math.max(2, (int)(3 * scale));
        int stepW  = (w - gap * (count - 1)) / count;
        int contentY = y + Math.max(4, (int)(5 * scale));
        int contentH = h - Math.max(4, (int)(5 * scale));
        Color inactiveBg = new Color(0xE5, 0xE7, 0xEB);
        Color inactiveFg = new Color(0x9C, 0xA3, 0xAF);

        for (int i = 0; i < count; i++) {
            com.google.gson.JsonObject step = steps.get(i).getAsJsonObject();
            String label  = jsonStr(step, "label", "");
            boolean active = step.has("active") && !step.get("active").isJsonNull()
                    && step.get("active").getAsBoolean();
            int sx = x + i * (stepW + gap);
            addRect(slide, sx, contentY, stepW, contentH, active ? cBase : inactiveBg);
            text(slide, label, sx + 2, contentY + 2, stepW - 4, contentH - 4,
                    Math.max(5, 6.5 * scale), active, false,
                    active ? WHITE : inactiveFg, TextParagraph.TextAlign.CENTER);
        }
    }

    // ── callout_box ──────────────────────────────────────────────────────────

    private static void renderCompCalloutBox(XSLFSlide slide, com.google.gson.JsonObject content,
            int x, int y, int w, int h, double scale) {
        String calloutText = jsonStr(content, "text", "");
        String tone        = jsonStr(content, "tone", "info");

        Color bgCol, fgCol;
        if ("warning".equals(tone)) {
            bgCol = new Color(0xFF, 0xF7, 0xED);
            fgCol = new Color(0xC2, 0x41, 0x0C);
        } else {
            bgCol = new Color(0xEF, 0xF6, 0xFF);
            fgCol = new Color(0x1D, 0x4E, 0xD8);
        }

        addRect(slide, x, y, w, h, bgCol);
        // 좌측 강조 바
        addRect(slide, x, y, Math.max(2, (int)(3 * scale)), h, fgCol);

        int padH = Math.max(6, (int)(10 * scale));
        int padV = Math.max(3, (int)(5 * scale));
        text(slide, calloutText,
                x + Math.max(2, (int)(3 * scale)) + padH, y + padV,
                w - Math.max(2, (int)(3 * scale)) - padH * 2, h - padV * 2,
                Math.max(6, 8 * scale), false, false, fgCol, null);
    }
}
