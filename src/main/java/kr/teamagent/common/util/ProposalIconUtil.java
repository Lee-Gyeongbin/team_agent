package kr.teamagent.common.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PROPOSAL 에이전트 — Java2D 기반 아이콘 PNG 생성 유틸.
 * 외부 라이브러리 없이 java.awt / javax.imageio만 사용.
 */
public final class ProposalIconUtil {

    private ProposalIconUtil() {}

    // ─── 퍼블릭 API ──────────────────────────────────────────────────────────────

    /**
     * 아이콘 PNG 바이트 생성.
     *
     * @param type     BOLT|SHIELD|BRAIN|DATABASE|CHART|EXCHANGE|CHECK|CLOUD|LOCK|ROCKET|SERVER|CLOCK
     * @param colorHex 아이콘 그리기 색상 (예: "FFFFFF" 또는 "#6C63F6"), null이면 흰색
     * @param size     픽셀 단위 정사각형 크기
     * @return PNG 바이트 배열
     */
    public static byte[] generateIcon(String type, String colorHex, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 투명 배경
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, size, size);
        g.setComposite(AlphaComposite.SrcOver);

        Color iconColor = parseHex(colorHex, Color.WHITE);
        g.setColor(iconColor);

        String t = (type == null) ? "BOLT" : type.toUpperCase().trim();
        switch (t) {
            case "BOLT":     drawBolt(g, size, iconColor);     break;
            case "SHIELD":   drawShield(g, size, iconColor);   break;
            case "BRAIN":    drawBrain(g, size, iconColor);    break;
            case "DATABASE": drawDatabase(g, size, iconColor); break;
            case "CHART":    drawChart(g, size, iconColor);    break;
            case "EXCHANGE": drawExchange(g, size, iconColor); break;
            case "CHECK":    drawCheck(g, size, iconColor);    break;
            case "CLOUD":    drawCloud(g, size, iconColor);    break;
            case "LOCK":     drawLock(g, size, iconColor);     break;
            case "ROCKET":   drawRocket(g, size, iconColor);   break;
            case "SERVER":   drawServer(g, size, iconColor);   break;
            case "CLOCK":    drawClock(g, size, iconColor);    break;
            default:         drawBolt(g, size, iconColor);     break;
        }

        g.dispose();
        return toPngBytes(img);
    }

    /**
     * 웨이브 배경 PNG 생성 (1200×700).
     *
     * @param baseColorHex   베이스 색 (우하단)
     * @param accentColorHex 강조 색 (좌상단)
     * @return PNG 바이트 배열
     */
    public static byte[] generateWave(String baseColorHex, String accentColorHex) {
        int W = 1200, H = 700;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 투명 배경
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, W, H);
        g.setComposite(AlphaComposite.SrcOver);

        Color base   = parseHex(baseColorHex,   new Color(0x1B, 0x25, 0x59));
        Color accent = parseHex(accentColorHex, new Color(0x6C, 0x63, 0xF6));

        GradientPaint grad = new GradientPaint(0, 0, accent, W, H, base);

        // path1 (opacity=0.12)
        Path2D path1 = new Path2D.Double();
        path1.moveTo(1200, 700);
        path1.lineTo(1200, 150);
        path1.curveTo(1000, 80,  850, 250, 700, 300);
        path1.curveTo(550,  350, 500, 180, 600, 80);
        path1.curveTo(700,  -20, 950, 20,  1200, 150);
        path1.closePath();

        g.setPaint(grad);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
        g.fill(path1);

        // path2 (opacity=0.9)
        Path2D path2 = new Path2D.Double();
        path2.moveTo(1200, 700);
        path2.lineTo(1200, 300);
        path2.curveTo(1050, 220, 950, 380, 820, 400);
        path2.curveTo(700,  420, 680, 280, 760, 220);
        path2.curveTo(860,  150, 1050, 220, 1200, 300);
        path2.closePath();

        g.setPaint(grad);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
        g.fill(path2);

        g.dispose();
        return toPngBytes(img);
    }

    /**
     * 텍스트 키워드 기반 아이콘 타입 자동 선택.
     *
     * @param text  콘텐츠 텍스트
     * @param index 인덱스 (기본 순환용)
     * @return 아이콘 타입 문자열
     */
    public static String pickIcon(String text, int index) {
        if (text != null) {
            String t = text.toLowerCase();
            if (contains(t, "데이터", "이관", "db", "database"))       return "DATABASE";
            if (contains(t, "ai", "인공지능", "머신", "딥러닝", "학습")) return "BRAIN";
            if (contains(t, "보안", "인증", "암호", "방화"))             return "SHIELD";
            if (contains(t, "성능", "확장", "속도", "고성능"))           return "BOLT";
            if (contains(t, "클라우드", "cloud"))                        return "CLOUD";
            if (contains(t, "일정", "기간", "기한", "스케줄", "타임"))   return "CLOCK";
            if (contains(t, "서버", "server", "인프라"))                 return "SERVER";
            if (contains(t, "검증", "품질", "테스트", "qa", "완료"))     return "CHECK";
            if (contains(t, "전략", "추진", "목표", "비전"))             return "ROCKET";
            if (contains(t, "통합", "연계", "인터페이스", "api"))        return "EXCHANGE";
            if (contains(t, "분석", "지표", "보고", "차트", "통계"))     return "CHART";
            if (contains(t, "잠금", "lock", "접근"))                     return "LOCK";
        }
        String[] rotation = {
            "BOLT", "SHIELD", "BRAIN", "DATABASE", "CHART", "EXCHANGE",
            "CHECK", "CLOUD", "LOCK", "ROCKET", "SERVER", "CLOCK"
        };
        return rotation[Math.abs(index) % rotation.length];
    }

    // ─── 아이콘 드로잉 구현 ───────────────────────────────────────────────────────

    /** BOLT: Z자형 번개 */
    private static void drawBolt(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.08;
        // 상단 삼각형 (왼쪽위 → 중앙오른쪽 → 중앙왼쪽)
        Path2D top = new Path2D.Double();
        top.moveTo(s * 0.30, m);
        top.lineTo(s * 0.68, m);
        top.lineTo(s * 0.42, s * 0.52);
        top.lineTo(s * 0.60, s * 0.52);
        top.closePath();

        // 하단 삼각형 (중앙 → 아래 → 왼쪽하단)
        Path2D bot = new Path2D.Double();
        bot.moveTo(s * 0.40, s * 0.48);
        bot.lineTo(s * 0.58, s * 0.48);
        bot.lineTo(s * 0.32, s - m);
        bot.lineTo(s * 0.70, s * 0.48);
        bot.closePath();

        Path2D bolt = new Path2D.Double();
        bolt.moveTo(s * 0.58, m);
        bolt.lineTo(s * 0.28, s * 0.52);
        bolt.lineTo(s * 0.46, s * 0.52);
        bolt.lineTo(s * 0.20, s - m);
        bolt.lineTo(s * 0.72, s * 0.42);
        bolt.lineTo(s * 0.54, s * 0.42);
        bolt.lineTo(s * 0.82, m);
        bolt.closePath();
        g.fill(bolt);
    }

    /** SHIELD: 방패 */
    private static void drawShield(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.10;
        Path2D shield = new Path2D.Double();
        shield.moveTo(s * 0.50, m * 0.8);
        shield.lineTo(s - m, s * 0.30);
        shield.curveTo(s - m, s * 0.65, s * 0.50, s - m * 0.6, s * 0.50, s - m * 0.5);
        shield.curveTo(s * 0.50, s - m * 0.6, m, s * 0.65, m, s * 0.30);
        shield.closePath();

        g.fill(shield);

        // 내부 체크 (흰색이 아닌 배경과의 대비를 위해 역색)
        Color inner = new Color(
            Math.max(0, c.getRed()   - 80),
            Math.max(0, c.getGreen() - 80),
            Math.max(0, c.getBlue()  - 80), 180);
        g.setColor(inner);
        Path2D check = new Path2D.Double();
        check.moveTo(s * 0.32, s * 0.50);
        check.lineTo(s * 0.45, s * 0.63);
        check.lineTo(s * 0.68, s * 0.38);
        float sw = (float)(s * 0.07);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.equals(Color.WHITE) ? new Color(0x1B, 0x25, 0x59) : Color.WHITE);
        g.draw(check);
        g.setStroke(new BasicStroke(1f));
    }

    /** BRAIN: 뇌 (원 + 주변 소원 + 연결선) */
    private static void drawBrain(Graphics2D g, int s, Color c) {
        g.setColor(c);
        float sw = Math.max(2f, s * 0.06f);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // 중앙 큰 원 (outline)
        double cr = s * 0.22;
        double cx = s * 0.50, cy = s * 0.50;
        g.draw(new Ellipse2D.Double(cx - cr, cy - cr, cr * 2, cr * 2));

        // 주변 4개 소원 + 연결선
        double sr2 = s * 0.10;
        double[][] pts = {
            {cx, cy - cr * 1.5},   // 상
            {cx + cr * 1.5, cy},   // 우
            {cx, cy + cr * 1.5},   // 하
            {cx - cr * 1.5, cy}    // 좌
        };
        double[] angles = {-Math.PI/2, 0, Math.PI/2, Math.PI};
        for (int i = 0; i < 4; i++) {
            double px = pts[i][0], py = pts[i][1];
            g.draw(new Ellipse2D.Double(px - sr2, py - sr2, sr2 * 2, sr2 * 2));
            // 연결선 (큰 원 테두리까지)
            double dx = px - cx, dy = py - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            double ex = cx + dx / len * cr, ey = cy + dy / len * cr;
            double sx2 = px - dx / len * sr2, sy2 = py - dy / len * sr2;
            g.draw(new Line2D.Double(ex, ey, sx2, sy2));
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** DATABASE: 실린더 (납작 Ellipse 3개 쌓기) */
    private static void drawDatabase(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.10;
        double dw = s - m * 2;
        double dh = s * 0.18;
        double gap = (s - m * 2 - dh * 3) / 2.0;
        // 3개 납작 실린더 (위에서 아래)
        for (int i = 0; i < 3; i++) {
            double y0 = m + i * (dh + gap);
            // body rect
            g.fillRect((int)(m), (int)(y0 + dh / 2), (int)dw, (int)(dh / 2));
            // 상단 타원
            g.fill(new Ellipse2D.Double(m, y0, dw, dh));
            // 하단 타원 테두리 (body 바닥)
            Color darker = new Color(
                Math.max(0, c.getRed()   - 60),
                Math.max(0, c.getGreen() - 60),
                Math.max(0, c.getBlue()  - 60));
            g.setColor(darker);
            g.draw(new Ellipse2D.Double(m, y0 + dh / 2, dw, dh));
            g.setColor(c);
        }
    }

    /** CHART: 막대 3개 + 상승 라인 */
    private static void drawChart(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.10;
        double bw = (s - m * 2) / 5.0; // 막대 폭
        double gap = bw * 0.6;
        double[] heights = {s * 0.35, s * 0.55, s * 0.75};
        double baseY = s - m;

        for (int i = 0; i < 3; i++) {
            double bx = m + i * (bw + gap);
            double by = baseY - heights[i];
            g.fill(new Rectangle2D.Double(bx, by, bw, heights[i]));
        }

        // 상승 꺾은선
        float sw = Math.max(2f, s * 0.05f);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color lineC = new Color(c.getRed(), c.getGreen(), c.getBlue(), 160);
        g.setColor(lineC);
        Path2D line = new Path2D.Double();
        for (int i = 0; i < 3; i++) {
            double bx = m + i * (bw + gap) + bw / 2;
            double by = baseY - heights[i];
            if (i == 0) line.moveTo(bx, by);
            else        line.lineTo(bx, by);
        }
        g.draw(line);
        g.setStroke(new BasicStroke(1f));
    }

    /** EXCHANGE: 오른쪽 화살표 + 왼쪽 화살표 (두 방향) */
    private static void drawExchange(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.12;
        double aw = s - m * 2;
        double ah = s * 0.16;
        double headW = aw * 0.32;
        double headH = ah * 1.2;

        // 위쪽: 오른쪽 화살표
        double y1 = s * 0.20;
        Path2D right = arrowRight(m, y1, aw, ah, headW, headH);
        g.fill(right);

        // 아래쪽: 왼쪽 화살표
        double y2 = s * 0.58;
        Path2D left = arrowLeft(m, y2, aw, ah, headW, headH);
        g.fill(left);
    }

    /** CHECK: 두꺼운 체크마크 */
    private static void drawCheck(Graphics2D g, int s, Color c) {
        g.setColor(c);
        float sw = Math.max(3f, s * 0.12f);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D check = new Path2D.Double();
        check.moveTo(s * 0.15, s * 0.52);
        check.lineTo(s * 0.42, s * 0.78);
        check.lineTo(s * 0.85, s * 0.25);
        g.draw(check);
        g.setStroke(new BasicStroke(1f));
    }

    /** CLOUD: 겹치는 타원 3개 → Area 합집합 */
    private static void drawCloud(Graphics2D g, int s, Color c) {
        g.setColor(c);
        // 큰 원 (오른쪽)
        Ellipse2D big   = new Ellipse2D.Double(s * 0.30, s * 0.20, s * 0.52, s * 0.50);
        // 중간 원 (왼쪽)
        Ellipse2D mid   = new Ellipse2D.Double(s * 0.12, s * 0.32, s * 0.42, s * 0.42);
        // 작은 원 (왼쪽 하단)
        Ellipse2D small = new Ellipse2D.Double(s * 0.08, s * 0.46, s * 0.30, s * 0.30);
        // 하단 직사각형 (구름 아랫면 채우기)
        Rectangle2D base = new Rectangle2D.Double(s * 0.10, s * 0.53, s * 0.80, s * 0.20);

        Area area = new Area(big);
        area.add(new Area(mid));
        area.add(new Area(small));
        area.add(new Area(base));
        g.fill(area);
    }

    /** LOCK: U자형 shackle + 바디 + keyhole */
    private static void drawLock(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m = s * 0.18;
        double bw = s - m * 2;
        double bh = s * 0.42;
        double by = s * 0.50;

        // 하단 바디
        g.fill(new RoundRectangle2D.Double(m, by, bw, bh, s * 0.10, s * 0.10));

        // 상단 U자 shackle
        float sw = Math.max(3f, s * 0.10f);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double arcX = m + bw * 0.20;
        double arcW = bw * 0.60;
        double arcH = s * 0.48;
        g.draw(new Arc2D.Double(arcX, s * 0.08, arcW, arcH, 0, 180, Arc2D.OPEN));
        g.setStroke(new BasicStroke(1f));

        // keyhole 원 + 아래 직사각형
        Color bg = new Color(c.getRed(), c.getGreen(), c.getBlue(), 60);
        g.setColor(bg);
        double kr = s * 0.09;
        double kx = s * 0.50 - kr, ky = by + bh * 0.20;
        g.fill(new Ellipse2D.Double(kx, ky, kr * 2, kr * 2));
        g.fill(new Rectangle2D.Double(s * 0.50 - kr * 0.5, ky + kr * 1.2, kr, kr * 1.2));
    }

    /** ROCKET: 로켓 */
    private static void drawRocket(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double cx = s * 0.50;
        double m  = s * 0.10;

        // 바디 (타원)
        double bw = s * 0.32, bh = s * 0.55;
        double bx = cx - bw / 2, by = s * 0.18;
        g.fill(new Ellipse2D.Double(bx, by, bw, bh));

        // 상단 노즈콘 (삼각형)
        Path2D nose = new Path2D.Double();
        nose.moveTo(cx, m * 0.5);
        nose.lineTo(cx - bw / 2, by + bh * 0.25);
        nose.lineTo(cx + bw / 2, by + bh * 0.25);
        nose.closePath();
        g.fill(nose);

        // 왼쪽 날개
        Path2D leftWing = new Path2D.Double();
        leftWing.moveTo(bx, by + bh * 0.55);
        leftWing.lineTo(m * 0.5, s - m);
        leftWing.lineTo(bx, by + bh * 0.82);
        leftWing.closePath();
        g.fill(leftWing);

        // 오른쪽 날개
        Path2D rightWing = new Path2D.Double();
        rightWing.moveTo(bx + bw, by + bh * 0.55);
        rightWing.lineTo(s - m * 0.5, s - m);
        rightWing.lineTo(bx + bw, by + bh * 0.82);
        rightWing.closePath();
        g.fill(rightWing);

        // 하단 불꽃 (타원)
        Color flame = new Color(
            Math.min(255, c.getRed()   + 50),
            Math.min(255, c.getGreen() + 50),
            Math.min(255, c.getBlue()  + 50), 180);
        g.setColor(flame);
        g.fill(new Ellipse2D.Double(cx - bw * 0.18, by + bh * 0.85, bw * 0.36, s * 0.18));
    }

    /** SERVER: 납작 라운드렉트 3개 + LED 원 */
    private static void drawServer(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m  = s * 0.10;
        double sw2 = s - m * 2;
        double sh = s * 0.18;
        double gap = (s - m * 2 - sh * 3) / 2.0;
        double r  = sh * 0.35;

        for (int i = 0; i < 3; i++) {
            double y0 = m + i * (sh + gap);
            g.fill(new RoundRectangle2D.Double(m, y0, sw2, sh, r, r));

            // 우측 LED 원 (약간 어두운 색)
            Color dark = new Color(
                Math.max(0, c.getRed()   - 60),
                Math.max(0, c.getGreen() - 60),
                Math.max(0, c.getBlue()  - 60));
            g.setColor(dark);
            double ledR = sh * 0.22;
            double ledX = m + sw2 - ledR * 2 - sh * 0.15;
            double ledY = y0 + sh / 2 - ledR;
            g.fill(new Ellipse2D.Double(ledX, ledY, ledR * 2, ledR * 2));
            g.setColor(c);
        }
    }

    /** CLOCK: 원 outline + 시침 + 분침 */
    private static void drawClock(Graphics2D g, int s, Color c) {
        g.setColor(c);
        double m  = s * 0.10;
        double cr = (s - m * 2) / 2.0;
        double cx = s / 2.0, cy = s / 2.0;

        float sw2 = Math.max(2f, s * 0.07f);
        g.setStroke(new BasicStroke(sw2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(m, m, cr * 2, cr * 2));

        // 중심점
        g.fill(new Ellipse2D.Double(cx - sw2, cy - sw2, sw2 * 2, sw2 * 2));

        float hw = Math.max(2f, s * 0.06f);
        g.setStroke(new BasicStroke(hw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // 시침 (12시 방향 → 짧은 선)
        g.draw(new Line2D.Double(cx, cy, cx, cy - cr * 0.50));
        // 분침 (3시 방향 → 긴 선)
        g.draw(new Line2D.Double(cx, cy, cx + cr * 0.70, cy));

        g.setStroke(new BasicStroke(1f));
    }

    // ─── 화살표 헬퍼 ──────────────────────────────────────────────────────────────

    private static Path2D arrowRight(double x, double y, double w, double ah, double headW, double headH) {
        double bodyH = ah;
        double bodyY = y + (headH - bodyH) / 2;
        Path2D p = new Path2D.Double();
        p.moveTo(x, bodyY);
        p.lineTo(x + w - headW, bodyY);
        p.lineTo(x + w - headW, y);
        p.lineTo(x + w, y + headH / 2);
        p.lineTo(x + w - headW, y + headH);
        p.lineTo(x + w - headW, bodyY + bodyH);
        p.lineTo(x, bodyY + bodyH);
        p.closePath();
        return p;
    }

    private static Path2D arrowLeft(double x, double y, double w, double ah, double headW, double headH) {
        double bodyH = ah;
        double bodyY = y + (headH - bodyH) / 2;
        Path2D p = new Path2D.Double();
        p.moveTo(x + w, bodyY);
        p.lineTo(x + headW, bodyY);
        p.lineTo(x + headW, y);
        p.lineTo(x, y + headH / 2);
        p.lineTo(x + headW, y + headH);
        p.lineTo(x + headW, bodyY + bodyH);
        p.lineTo(x + w, bodyY + bodyH);
        p.closePath();
        return p;
    }

    // ─── 유틸 ─────────────────────────────────────────────────────────────────────

    private static boolean contains(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    public static Color parseHex(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() >= 6) {
                int r = Integer.parseInt(h.substring(0, 2), 16);
                int g = Integer.parseInt(h.substring(2, 4), 16);
                int b = Integer.parseInt(h.substring(4, 6), 16);
                return new Color(r, g, b);
            }
        } catch (Exception ignore) {}
        return fallback;
    }

    private static byte[] toPngBytes(BufferedImage img) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "PNG", baos);
        } catch (IOException e) {
            // 무시 — 빈 배열 반환
        }
        return baos.toByteArray();
    }
}
