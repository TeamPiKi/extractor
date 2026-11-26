package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.contracts.extraction.v1.ExtractionPolicy;
import com.depromeet.piki.extractor.domain.AuthorizedDomains;
import com.depromeet.piki.extractor.domain.BlockedDomains;
import com.depromeet.piki.extractor.domain.DeclaredIdentity;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

/**
 * 한 환경의 추출 정책. 값의 원장은 그 환경의 호출자이고, 못 읽었을 때의 규칙은 TeamPiKi/infra 의
 * contracts/extraction-api.md 8절이 정본이다.
 */
record ExtractionPolicySnapshot(
    long version,
    String linkModel,
    String imageModel,
    DeclaredIdentity identity,
    BlockedDomains blockedDomains,
    AuthorizedDomains authorizedDomains,
    String imageBucket
) {

    static final ExtractionPolicySnapshot DEFAULT = new ExtractionPolicySnapshot(
        0, "", "", DeclaredIdentity.DEFAULT, BlockedDomains.NONE, AuthorizedDomains.NONE, "");

    static ExtractionPolicySnapshot parse(String json) {
        ExtractionPolicy.Builder builder = ExtractionPolicy.newBuilder();
        try {
            JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("정책 배포본을 해석하지 못했다", e);
        }
        ExtractionPolicy policy = builder.build();
        return new ExtractionPolicySnapshot(
            policy.getVersion(),
            policy.getLinkModel(),
            policy.getImageModel(),
            DeclaredIdentity.fromRequest(policy.getIdentity()),
            BlockedDomains.fromRequest(policy.getBlockedDomainsList()),
            new AuthorizedDomains(policy.getAuthorizedDomainsList()),
            policy.getImageBucket()
        );
    }

    boolean hasImageBucket() {
        return !imageBucket.isBlank();
    }
}
