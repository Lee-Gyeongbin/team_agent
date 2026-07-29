package kr.teamagent.common.util;

import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.*;

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
        /** 챕터 로마숫자 ("Ⅱ" 등) */
        public final String chapterRoman;
        /** 소목차 제목 */
        public final String sectionTitle;
        /** 페이지 라벨 ("Ⅱ-1" 등) */
        public final String pageLabel;
        /** 사업명 (헤더 우측) */
        public final String projectNm;
        /** 발주기관명 (푸터 좌측) */
        public final String orgNm;
        /** 제안사명 (푸터 우측) */
        public final String submitterNm;

        public PageInfo(byte[] imageBytes, String chapterRoman, String sectionTitle,
                        String pageLabel, String projectNm, String orgNm, String submitterNm) {
            this.imageBytes   = imageBytes;
            this.chapterRoman = chapterRoman != null ? chapterRoman : "Ⅰ";
            this.sectionTitle = sectionTitle != null ? sectionTitle : "";
            this.pageLabel    = pageLabel    != null ? pageLabel    : "";
            this.projectNm    = projectNm   != null ? projectNm    : "";
            this.orgNm        = orgNm       != null ? orgNm        : "";
            this.submitterNm  = submitterNm != null ? submitterNm  : "";
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
        Color cFooter = tint(cBase, 0.88f);

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

                // ── 푸터: 연한 baseColor 바 ──────────────────────────────
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
        // 구역 배경 먼저 채움 (letterbox 여백 색)
        addRect(slide, zoneX, zoneY, zoneW, zoneH, bgColor);

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
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(new Rectangle(x, y, w, h));
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
}
