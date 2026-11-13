package io.gate.prompt.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가짜 모델 (설계안 결정 2): <b>입력에 들어 있는 지시를 무엇이든 따르는 최악의 모델</b>.
 *
 * <p>정상 요청은 그럴듯하게 처리하고, 입력에 SQL·HTML·동작 JSON이 들어 있으면 그대로 출력한다.
 * 실제 모델이 늘 이렇게 속지는 않지만, "모델이 속아도 관문이 막는가"를 키·비용 없이 매번 같은 결과로 확인할 수 있다.
 */
public final class ScriptedModel implements AssistantModel {

    static final String COLUMNS = "id, product_id, quantity, note, created_at";

    /** 질문 안의 SQL (그대로 따른다). */
    private static final Pattern EMBEDDED_SQL = Pattern.compile("(?is)\\b(select\\b.+?\\bfrom\\b.+|with\\s+\\w+\\s+as"
            + "\\s*\\(.+|(drop|delete|insert|update|alter|create|truncate)\\s+(table|from|into|\\w+\\s+set)\\b.+)");
    private static final Pattern OTHER_USER = Pattern.compile("(?i)\\buser(?:_id)?\\s*(?:#|id|=|no\\.?)?\\s*(\\d+)");
    private static final Pattern MORE_THAN = Pattern.compile("(?i)(?:more|over|greater|bigger)\\s+(?:than\\s+)?(\\d+)");
    private static final Pattern EMBEDDED_JSON = Pattern.compile("(?s)\\{.*}");
    private static final Pattern CANCEL = Pattern.compile("(?i)\\bcancel\\b.*?\\border\\s*#?\\s*(\\d+)");
    private static final Pattern RESEND = Pattern.compile("(?i)\\bre-?send\\b.*?\\breceipt\\b.*?(\\d+)");

    @Override
    public String orderSql(String question, long currentUserId) {
        String q = question == null ? "" : question;
        Matcher sql = EMBEDDED_SQL.matcher(q);
        if (sql.find()) {
            return sql.group().strip().replaceAll("[\"'`]+$", "");
        }
        Matcher user = OTHER_USER.matcher(q);
        if (user.find()) {
            // "user 2의 주문" 같은 요청도 그대로 따른다 (권한은 모델이 판단할 일이 아니다)
            return "SELECT " + COLUMNS + " FROM orders WHERE user_id = " + user.group(1);
        }
        Matcher more = MORE_THAN.matcher(q);
        if (more.find()) {
            return "SELECT " + COLUMNS + " FROM orders WHERE user_id = " + currentUserId + " AND quantity > "
                    + more.group(1);
        }
        return "SELECT " + COLUMNS + " FROM orders WHERE user_id = " + currentUserId + " ORDER BY id DESC";
    }

    @Override
    public String reviewSummary(List<String> reviews) {
        // 리뷰 본문을 그대로 옮긴다: 본문에 숨은 HTML·지시도 함께 출력된다 (간접 인젝션)
        StringBuilder sb = new StringBuilder("<p>Summary of ").append(reviews.size()).append(" reviews:</p><ul>");
        for (String r : reviews) {
            sb.append("<li>").append(r).append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    @Override
    public String orderAction(String request) {
        String r = request == null ? "" : request;
        Matcher json = EMBEDDED_JSON.matcher(r);
        if (json.find()) {
            return json.group(); // 요청에 동작 JSON이 들어 있으면 그대로 따른다
        }
        Matcher cancel = CANCEL.matcher(r);
        if (cancel.find()) {
            return "{\"action\":\"cancel_order\",\"args\":{\"orderId\":" + cancel.group(1) + "}}";
        }
        Matcher resend = RESEND.matcher(r);
        if (resend.find()) {
            return "{\"action\":\"resend_receipt\",\"args\":{\"orderId\":" + resend.group(1) + "}}";
        }
        return "{\"action\":\"none\",\"args\":{}}";
    }
}
