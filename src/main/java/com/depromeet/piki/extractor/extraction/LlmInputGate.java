package com.depromeet.piki.extractor.extraction;

import java.util.Objects;
import org.jsoup.nodes.Document;

/**
 * "LLM 이 볼 게 아무것도 없는가" 판정 — LLM fallback 직전의 게이트. 빈 CSR 셸을 LLM 에 넘기면 "모르겠다"
 * 대신 그럴듯한 상품을 지어내고(에이블리 mobile.* 실측: 같은 URL 15회 중 200 이 8회, 전부 서로 다른
 * 실존하지 않는 상품·죽은 이미지 URL), 지어낸 값은 형식이 유효해 응답 경계도 호출자(core) 검증도 통과한다.
 * 입력이 비었음을 아는 이 지점이 유일한 차단 기회다.
 *
 * <p>{@link EmptyShellDetector}(에스컬레이션 축)와 판정을 분리하는 이유는 오탐 비용의 비대칭이다 — 그쪽
 * 오탐은 헤드리스 1회 낭비(fail-open)로 그치지만, 이 게이트의 오탐은 확정 실패(422)로 굳는다. 그래서 길이
 * 임계("짧다") 같은 판단을 두지 않고 사실만 본다: 가시 텍스트가 전혀 없고, 데이터 script(JSON-LD·data
 * island — {@link GeminiHtmlExtractor} sanitize 가 보존하는 것)도 없다. 환각 사고 케이스는 렌더 후에도
 * 0자였다. jsoup 의 {@code text()} 는 script 내용을 세지 않으므로 데이터 script 유무는 별도 조건이 진다.
 *
 * <p>수용한 잔존 위험: 가시 텍스트가 몇십 자(접근성 링크·푸터 보일러플레이트)뿐인 셸은 게이트를 지나 LLM 으로
 * 간다 — 그런 셸은 실측된 바 없고, 임계 마진으로 선제 차단하면 그 마진 크기가 자의적이 되어 정상 미니멀
 * 페이지를 확정 실패로 굳힐 수 있다. 감시는 {@code via=llm} 로그의 html 크기·host 분포로 한다(셸은 본문이
 * 극단적으로 작다).
 */
final class LlmInputGate {

    private LlmInputGate() {
    }

    static boolean hasNothingForLlm(Document document) {
        Objects.requireNonNull(document, "document");
        return document.body().text().isEmpty() && !DataScripts.hasDataScript(document);
    }
}
