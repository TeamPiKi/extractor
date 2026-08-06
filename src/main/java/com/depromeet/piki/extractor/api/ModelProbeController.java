package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.probe.ModelProbeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 모델 프로브 API (docs/api-contract.md). 추출 API 와 경로를 나눈 것은 자원이 다르기 때문이다 — 이쪽은
 * 상품을 추출하지 않고 "모델이 쓸 만한가"만 답한다.
 *
 * <p>성공 응답에 body 를 두지 않는다. 호출자가 알아야 할 것은 "통과했는가" 하나이고, 그건 status 로 충분하다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/models")
public class ModelProbeController {

    private final ModelProbeService modelProbeService;

    @PostMapping("/probe")
    @ResponseStatus(HttpStatus.OK)
    public void probe(@RequestBody ModelProbeRequest request) {
        // 정상 호출자는 여기 닿지 않는다 — 두 필드는 계약상 필수이고, 빠졌다면 호출자 구현 버그다.
        // 그래도 명시적으로 400 을 던지는 이유: 안 잡으면 아래에서 NPE 가 나 500 이 되는데, 계약상 500 은
        // 일시 실패라 호출자가 재시도한다. 필드가 빠진 요청은 몇 번을 보내도 같은 결과이므로, 재시도가
        // 무의미하다는 사실이 status 에 드러나야 한다. 이 경로는 확정 거절(422)도 아니다 — 모델이 나쁜 게
        // 아니라 요청이 잘못된 것이라, 호출자 화면에 "이 모델은 못 쓴다"고 안내되면 그것도 거짓말이다.
        if (request.model() == null || request.model().isBlank() || request.target() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model 과 target 은 필수입니다.");
        }
        modelProbeService.probe(request.model().trim(), request.target());
    }
}
