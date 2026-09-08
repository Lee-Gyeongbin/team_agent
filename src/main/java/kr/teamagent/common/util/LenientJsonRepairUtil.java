package kr.teamagent.common.util;

import com.google.gson.JsonParser;

/**
 * LLM 응답 JSON이 max_tokens 초과로 중간에 잘린 경우,
 * 완전한 원소들까지만 살려서 파싱 가능한 JSON으로 복구하는 유틸리티.
 *
 * 사용처: ProposalServiceImpl.extractStage1FromLargeRfp — Stage1 청크 파싱 실패 시 lenient repair
 */
public class LenientJsonRepairUtil {

    private static final int MAX_RETRIES = 5;

    /**
     * 잘린 JSON 문자열 복구 시도.
     *
     * @param raw LLM 원본 응답 (```json 코드블록 포함 가능, 끝이 잘렸을 수 있음)
     * @return 복구된 유효한 JSON 문자열. 복구 불가 시 null.
     */
    public static String repairJson(String raw) {
        if (raw == null) return null;

        // 코드블록 제거 (LLM이 ```json ... ``` 으로 감쌀 경우 대비)
        String json = stripCodeBlock(raw.trim());
        if (json.isEmpty()) return null;

        // 1단계: 원본 그대로 파싱 시도 (정상 케이스 — repair 불필요)
        if (tryParse(json)) return json;

        // 2단계: 마지막 완전한 객체 끝(})을 찾아 이후 잘린 부분 제거 후 닫는 괄호 추가 반복
        String work = json;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            int lastClose = findLastObjectClose(work);
            if (lastClose < 0) break; // }가 하나도 없으면 복구 불가

            // lastClose까지만 잘라내고 닫는 괄호 추가
            String truncated = work.substring(0, lastClose + 1);
            String repaired = appendMissingClosers(truncated);
            if (repaired != null && tryParse(repaired)) {
                return repaired;
            }

            // 한 단계 더 이전 }로 후퇴
            work = work.substring(0, lastClose);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 유틸
    // ─────────────────────────────────────────────────────────────

    /** LLM 코드블록(``` 또는 ```json) 제거 */
    private static String stripCodeBlock(String s) {
        if (!s.startsWith("```")) return s;
        int nl = s.indexOf('\n');
        if (nl != -1) s = s.substring(nl + 1);
        int last = s.lastIndexOf("```");
        if (last > 0) s = s.substring(0, last);
        return s.trim();
    }

    /** Gson으로 파싱 가능한지만 확인 (파싱 결과는 버림) */
    private static boolean tryParse(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 문자열 전체를 앞에서부터 스캔하여 문자열 리터럴 밖에 등장하는
     * 마지막 '}' 의 인덱스를 반환. 없으면 -1.
     *
     * 이스케이프 문자(\")와 문자열 내부의 { } [ ] 는 카운트에서 제외한다.
     */
    private static int findLastObjectClose(String s) {
        boolean inString = false;
        boolean escape = false;
        int lastClose = -1;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '}') {
                lastClose = i;
            }
        }
        return lastClose;
    }

    /**
     * 잘린 JSON 문자열을 앞에서부터 스캔하여 열려 있는 { [ 에 대응하는
     * 닫는 괄호 } ] 를 LIFO 순서로 뒤에 추가한다.
     *
     * 복구 결과 문자열을 반환. 스택이 비정상적으로 커지면(≥1000) null 반환.
     */
    private static String appendMissingClosers(String s) {
        boolean inString = false;
        boolean escape = false;
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                stack.push('}');
            } else if (c == '[') {
                stack.push(']');
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
                // 스택 언더플로(잘못된 JSON 구조)는 무시하고 계속
            }

            if (stack.size() >= 1000) return null; // 비정상 입력 방지
        }

        if (stack.isEmpty()) return s; // 이미 닫혀있음

        StringBuilder sb = new StringBuilder(s);
        // 문자열이 문자열 리터럴 중간에서 잘린 경우 따옴표 먼저 닫기
        if (inString) sb.append('"');
        while (!stack.isEmpty()) {
            sb.append(stack.pop());
        }
        return sb.toString();
    }
}
