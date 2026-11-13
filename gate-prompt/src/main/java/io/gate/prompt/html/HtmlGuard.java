package io.gate.prompt.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.owasp.html.HtmlChangeListener;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * AI 출력을 HTML로 화면에 넣기 전의 관문 (설계안 2·3장).
 * <ul>
 *   <li>허용 태그만 남긴다: {@code p, br, ul, ol, li, b, i, em, strong}. <b>속성은 하나도 허용하지 않는다</b>
 *       (on* 이벤트, style, href·src 링크가 모두 사라진다)</li>
 *   <li>그 밖의 태그는 지우고, {@code script}·{@code style} 같은 태그의 내용은 글자로도 남기지 않는다</li>
 *   <li>꺾쇠·따옴표 같은 글자는 이스케이프되어 글자로만 보인다</li>
 * </ul>
 * 막지 않고 정리한다(요약 기능이 계속 동작하도록). 무엇을 지웠는지는 결과에 남겨 기록·경고에 쓴다.
 */
public final class HtmlGuard {

    public static final String REMOVED = "PROMPT-HTML-REMOVED";
    public static final List<String> ALLOWED_TAGS = List.of("p", "br", "ul", "ol", "li", "b", "i", "em", "strong");

    /** 위험 신호로 보는 제거 항목: 스크립트 실행·외부 요청·화면 위장이 가능한 것. */
    static final List<String> DANGEROUS_TAGS = List.of("script", "iframe", "object", "embed", "img", "svg", "a",
            "form", "input", "button", "style", "link", "meta", "base", "math", "video", "audio", "source");

    /**
     * @param html              화면에 넣어도 되는 HTML
     * @param removedTags       지운 태그 (소문자, 중복 포함)
     * @param removedAttributes 지운 속성 ("태그.속성" 형식)
     */
    public record Result(String html, List<String> removedTags, List<String> removedAttributes) {
        public Result {
            removedTags = List.copyOf(removedTags);
            removedAttributes = List.copyOf(removedAttributes);
        }

        public boolean changed() {
            return !removedTags.isEmpty() || !removedAttributes.isEmpty();
        }

        /** 스크립트·링크·이벤트 속성처럼 공격 신호로 볼 만한 것을 지웠는지. */
        public boolean dangerousContentRemoved() {
            return removedTags.stream().anyMatch(DANGEROUS_TAGS::contains) || !removedAttributes.isEmpty();
        }
    }

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements(ALLOWED_TAGS.toArray(String[]::new))
            .toFactory();

    private HtmlGuard() {
    }

    public static Result sanitize(String aiHtml) {
        if (aiHtml == null || aiHtml.isEmpty()) {
            return new Result("", List.of(), List.of());
        }
        List<String> tags = new ArrayList<>();
        List<String> attrs = new ArrayList<>();
        String clean = POLICY.sanitize(aiHtml, new HtmlChangeListener<Void>() {
            @Override
            public void discardedTag(Void context, String elementName) {
                tags.add(elementName.toLowerCase(Locale.ROOT));
            }

            @Override
            public void discardedAttributes(Void context, String tagName, String... attributeNames) {
                for (String a : attributeNames) {
                    attrs.add(tagName.toLowerCase(Locale.ROOT) + "." + a.toLowerCase(Locale.ROOT));
                }
            }
        }, null);
        return new Result(clean, tags, attrs);
    }
}
