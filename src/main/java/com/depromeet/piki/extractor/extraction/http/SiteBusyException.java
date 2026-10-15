package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import java.time.Duration;

/**
 * 같은 사이트의 동시 fetch 자리가 나지 않은 실패. 일시 실패다. {@link PageFetchException} 이 아니라서 헤드리스로
 * 넘어가지 않는다. 넘기면 같은 사이트를 렌더 서버 IP 로 다시 요청해 상한이 무력해진다.
 */
public final class SiteBusyException extends ExtractionException {

    private SiteBusyException(String message) {
        super(message, ExtractionErrorCode.UPSTREAM_ERROR, false, null);
    }

    static SiteBusyException waited(Duration maxWait) {
        return new SiteBusyException("같은 사이트의 동시 fetch 자리를 " + maxWait + " 동안 얻지 못했다.");
    }
}
