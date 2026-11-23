package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule;
import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

/** 규칙을 배포본에 실리는 모양(JSON) 그대로 적어 만든다 — 와이어 표기가 어긋나면 테스트가 먼저 깨진다. */
public final class RuleFixtures {

    public static final String OLIVE_YOUNG = """
        {
          "id": 3,
          "host": "oliveyoung.co.kr",
          "fetch": "RENDER",
          "name": {"css": {"selector": "h3[class*='GoodsDetailInfo_title__']"}},
          "currentPrice": {"css": {"selector": "[class^='GoodsDetailInfo_price__'] span"}},
          "originalPrice": {"css": {"selector": "[class*='GoodsDetailInfo_price-before'] span"}},
          "imageUrl": {"css": {"selector": "meta[property='og:image']", "attribute": "content"}},
          "absentOriginalPrice": "NO_DISCOUNT"
        }""";

    private RuleFixtures() {
    }

    public static ExtractionRule proto(String json) {
        ExtractionRule.Builder builder = ExtractionRule.newBuilder();
        try {
            JsonFormat.parser().merge(json, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
        return builder.build();
    }

    public static MallRule rule(String json) {
        return MallRule.compile(proto(json));
    }

    public static MallRules rules(String... jsons) {
        ExtractionRuleSet.Builder set = ExtractionRuleSet.newBuilder().setVersion(1);
        for (String json : jsons) {
            set.addRules(proto(json));
        }
        return MallRules.compile(set.build());
    }
}
