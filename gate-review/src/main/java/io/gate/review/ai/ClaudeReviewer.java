package io.gate.review.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import io.gate.review.SourceFile;
import io.gate.review.staticscan.StaticHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claude로 보안 검토를 한다 (공식 Anthropic Java SDK).
 * <ul>
 *   <li>모델 기본값 Claude Opus 5.5 ({@value #DEFAULT_MODEL}). 사고(thinking)는 항상 켜져 있고 깊이는 effort로 조절</li>
 *   <li>구조화 출력(JSON 스키마)으로 형식을 강제하고, 형식이 틀리면 1회 다시 요청</li>
 *   <li>지침은 프롬프트 캐싱(지침이 바뀌지 않으므로 같은 검토를 반복할 때 입력 비용이 줄어든다)</li>
 *   <li>거절(refusal)·길이 초과·오류는 예외를 던지지 않고 상태로 돌려준다. 호출하는 쪽은 정적 분석 결과만 낸다</li>
 *   <li>파일이 많으면 잘라 내지 않고 여러 요청으로 나눈다</li>
 * </ul>
 */
public final class ClaudeReviewer implements AiReviewer {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final long MAX_TOKENS = 16_000L;
    /** 요청 하나에 넣을 코드 글자 수 상한 (약 7만 5천 토큰). 넘으면 파일 단위로 나눈다. */
    static final int MAX_CHARS_PER_REQUEST = 300_000;

    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;

    public ClaudeReviewer(AnthropicClient client, String model, OutputConfig.Effort effort) {
        this.client = client;
        this.model = model;
        this.effort = effort;
    }

    /** {@code ANTHROPIC_API_KEY}가 있을 때만 만든다. 없으면(예: 포크 PR) 빈 값 → 정적 분석만 한다. */
    public static Optional<ClaudeReviewer> fromEnv(Function<String, String> env, String model,
            OutputConfig.Effort effort) {
        String key = env.apply("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        // 주소를 명시한다: 주변 환경 변수(ANTHROPIC_BASE_URL 등)가 검토 대상 코드를 다른 곳으로 보내지 못하게
        return Optional.of(new ClaudeReviewer(AnthropicOkHttpClient.builder().baseUrl(OFFICIAL_BASE_URL).apiKey(key)
                .build(), model, effort));
    }

    static final String OFFICIAL_BASE_URL = "https://api.anthropic.com";

    @Override
    public AiResult review(List<SourceFile> files, List<StaticHit> staticHints) {
        List<AiFinding> all = new ArrayList<>();
        long in = 0;
        long out = 0;
        for (List<SourceFile> batch : batches(files)) {
            List<StaticHit> hints = hintsFor(batch, staticHints);
            Attempt a = attempt(batch, hints);
            in += a.inputTokens;
            out += a.outputTokens;
            if (a.result == null && a.retry) {
                a = attempt(batch, hints); // 형식 오류는 1회만 다시 시도 (두 시도의 토큰을 모두 센다)
                in += a.inputTokens;
                out += a.outputTokens;
            }
            if (a.result == null) {
                return new AiResult(a.status, List.of(), model, in, out, a.detail);
            }
            all.addAll(a.result);
        }
        return new AiResult(AiResult.Status.OK, all, model, in, out, null);
    }

    /** 한 번의 요청 결과. result가 null이면 실패. */
    private record Attempt(List<AiFinding> result, AiResult.Status status, String detail, boolean retry,
            long inputTokens, long outputTokens) {
    }

    private Attempt attempt(List<SourceFile> batch, List<StaticHit> hints) {
        Message response;
        try {
            response = client.messages().create(params(model, effort, batch, hints));
        } catch (NotFoundException e) {
            return fail(AiResult.Status.ERROR, "model or endpoint not found: " + model, false);
        } catch (RateLimitException e) {
            return fail(AiResult.Status.ERROR, "rate limited (the SDK already retried)", false);
        } catch (AnthropicServiceException e) {
            return fail(AiResult.Status.ERROR, "API error " + e.statusCode() + " "
                    + e.errorType().map(Object::toString).orElse(""), false);
        } catch (AnthropicIoException e) {
            return fail(AiResult.Status.ERROR, "network error: " + e.getMessage(), false);
        }
        long in = response.usage().inputTokens();
        long out = response.usage().outputTokens();
        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
            String why = response.stopDetails().map(d -> String.valueOf(d.category())).orElse("unspecified");
            return new Attempt(null, AiResult.Status.REFUSED, "model declined (category: " + why + ")", false, in,
                    out);
        }
        if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
            return new Attempt(null, AiResult.Status.ERROR, "response truncated at max_tokens", false, in, out);
        }
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
                .collect(Collectors.joining());
        try {
            return new Attempt(AiResponseParser.parse(text), AiResult.Status.OK, null, false, in, out);
        } catch (IllegalArgumentException e) {
            return new Attempt(null, AiResult.Status.ERROR, e.getMessage(), true, in, out);
        }
    }

    private static Attempt fail(AiResult.Status status, String detail, boolean retry) {
        return new Attempt(null, status, detail, retry, 0, 0);
    }

    /** 요청 본문. 테스트에서 실제 호출 없이 모양을 확인할 수 있게 분리했다. */
    static MessageCreateParams params(String model, OutputConfig.Effort effort, List<SourceFile> files,
            List<StaticHit> hints) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> e : ReviewPrompt.schema().entrySet()) {
            schema.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(ReviewPrompt.SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder()
                        .effort(effort)
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .addUserMessage(ReviewPrompt.userMessage(files, hints))
                .build();
    }

    static List<List<SourceFile>> batches(List<SourceFile> files) {
        List<List<SourceFile>> out = new ArrayList<>();
        List<SourceFile> current = new ArrayList<>();
        int size = 0;
        for (SourceFile f : files) {
            if (!current.isEmpty() && size + f.content().length() > MAX_CHARS_PER_REQUEST) {
                out.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(f);
            size += f.content().length();
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private static List<StaticHit> hintsFor(List<SourceFile> batch, List<StaticHit> hints) {
        var paths = batch.stream().map(SourceFile::path).collect(Collectors.toSet());
        return hints.stream().filter(h -> paths.contains(h.path())).toList();
    }
}
