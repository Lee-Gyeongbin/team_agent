package kr.teamagent.common.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * 번역 에이전트의 .docx / .txt 파일 추출·재조립 및 텍스트 결과의 파일 변환을 담당하는 유틸.
 */
public class TranslationDocUtil {

    public static class Segment {
        private final String id;
        private final String text;

        public Segment(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }
    }

    private TranslationDocUtil() {
    }

    public static List<Segment> extractSegments(byte[] bytes, String ext) throws IOException {
        if ("docx".equalsIgnoreCase(ext)) {
            return extractDocxSegments(bytes);
        }
        return extractTxtSegments(bytes);
    }

    public static byte[] textToDocxBytes(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = String.valueOf(text).split("\n", -1);
            for (String line : lines) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] textToTxtBytes(String text) {
        return String.valueOf(text).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Segment> extractDocxSegments(byte[] bytes) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (int p = 0; p < paragraphs.size(); p++) {
                List<XWPFRun> runs = paragraphs.get(p).getRuns();
                for (int r = 0; r < runs.size(); r++) {
                    String text = runs.get(r).getText(0);
                    if (text != null && !text.isEmpty()) {
                        segments.add(new Segment(p + "_" + r, text));
                    }
                }
            }
        }
        return segments;
    }

    private static List<Segment> extractTxtSegments(byte[] bytes) {
        List<Segment> segments = new ArrayList<>();
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.trim().isEmpty()) {
                segments.add(new Segment(String.valueOf(i), line));
            }
        }
        return segments;
    }
}
