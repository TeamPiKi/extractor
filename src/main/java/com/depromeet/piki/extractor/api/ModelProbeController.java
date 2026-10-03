package com.depromeet.piki.extractor.api;

import com.depromeet.piki.contracts.extraction.v1.ModelProbeRequest;
import com.depromeet.piki.extractor.probe.ModelProbeService;
import com.depromeet.piki.extractor.probe.ProbeTarget;
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
        // 빠진 필드는 호출자 구현 버그라 400 이다. 5xx 면 계약상 일시 실패로 읽혀 같은 요청을 재시도하고,
        // 422 면 모델이 아니라 요청이 잘못됐는데 호출자 화면에 "이 모델은 못 쓴다"고 안내된다.
        if (request.getModel().isBlank()) {
            throw missingRequiredField();
        }
        modelProbeService.probe(request.getModel().trim(), targetOf(request.getTarget()));
    }

    /** default 를 두지 않는다 - 계약에 target 이 늘면 여기가 컴파일에서 깨져야 한다. */
    private static ProbeTarget targetOf(ModelProbeRequest.Target target) {
        return switch (target) {
            case LINK -> ProbeTarget.LINK;
            case IMAGE -> ProbeTarget.IMAGE;
            // 생략과 모르는 문자열은 파서가 둘 다 UNSPECIFIED 로 읽는다.
            case TARGET_UNSPECIFIED, UNRECOGNIZED -> throw missingRequiredField();
        };
    }

    private static ResponseStatusException missingRequiredField() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "model 과 target 은 필수입니다.");
    }
}
