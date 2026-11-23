package com.depromeet.piki.extractor.api;

import com.depromeet.piki.contracts.extraction.v1.RuleProbeRequest;
import com.depromeet.piki.contracts.extraction.v1.RuleProbeResult;
import com.depromeet.piki.extractor.domain.BlockedDomains;
import com.depromeet.piki.extractor.domain.DeclaredIdentity;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.ExtractionDeadline;
import com.depromeet.piki.extractor.extraction.RuleProbeService;
import com.depromeet.piki.extractor.extraction.rule.InvalidRuleException;
import com.depromeet.piki.extractor.extraction.rule.MallRule;
import com.depromeet.piki.extractor.extraction.rule.RuleApplication;
import com.depromeet.piki.extractor.extraction.rule.RuleReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 몰별 규칙 시험 추출 API. 계약은 TeamPiKi/infra contracts/extraction-api.md 의 RuleProbe. */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/rules")
public class RuleProbeController {

    private final RuleProbeService ruleProbeService;

    @PostMapping("/probe")
    public RuleProbeResult probe(@RequestBody RuleProbeRequest request) {
        if (request.getUrl().isBlank() || !request.hasRule()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 과 rule 은 필수입니다.");
        }
        ProductLink link = ProductLink.parse(request.getUrl());
        MallRule rule;
        try {
            rule = MallRule.compile(request.getRule());
        } catch (InvalidRuleException e) {
            // 페이지가 아니라 입력의 문제라 실패 code 가 아니라 200 의 필드로 답한다
            return RuleProbeResult.newBuilder().setRuleError(e.getMessage()).build();
        }
        DeclaredIdentity identity = DeclaredIdentity.fromRequest(request.getIdentity());
        BlockedDomains blockedDomains = BlockedDomains.fromRequest(request.getBlockedDomainsList());
        log.info("rule probe request rule={} render={} url={}", rule.id(), rule.render(), link.safeLogString());
        RuleProbeService.Outcome outcome = ExtractionDeadline.withinLinkBudget(
            () -> ruleProbeService.probe(rule, link, request.getAuthorized(), identity, blockedDomains));
        return resultOf(outcome);
    }

    private static RuleProbeResult resultOf(RuleProbeService.Outcome outcome) {
        RuleProbeResult.Builder result = RuleProbeResult.newBuilder()
            .setFinalUrl(outcome.finalUrl().value().toString())
            .setByCommon(readingOf(outcome.common()));
        RuleApplication applied = outcome.applied();
        if (applied == null) {
            return result.build();
        }
        return result
            .setMatched(true)
            .setByRule(readingOf(applied.reading()))
            .addAllUnreadFields(applied.reading().unreadFields())
            .setDiscarded(applied.discarded())
            .setNoDiscountApplied(applied.noDiscountApplied())
            .build();
    }

    private static RuleProbeResult.Reading readingOf(RuleReading reading) {
        RuleProbeResult.Reading.Builder builder = RuleProbeResult.Reading.newBuilder();
        if (reading.name().isRead()) {
            builder.setName(reading.name().value());
        }
        if (reading.imageUrl().isRead()) {
            builder.setImageUrl(reading.imageUrl().value());
        }
        if (reading.currentPrice().isRead()) {
            builder.setCurrentPrice(reading.currentPrice().value());
        }
        if (reading.originalPrice().isRead()) {
            builder.setOriginalPrice(reading.originalPrice().value());
        }
        return builder.build();
    }

    private static RuleProbeResult.Reading readingOf(ProductSnapshot common) {
        RuleProbeResult.Reading.Builder builder = RuleProbeResult.Reading.newBuilder();
        if (common == null) {
            return builder.build();
        }
        if (common.name() != null) {
            builder.setName(common.name());
        }
        if (common.imageUrl() != null) {
            builder.setImageUrl(common.imageUrl());
        }
        if (common.currentPrice() != null) {
            builder.setCurrentPrice(common.currentPrice());
        }
        if (common.originalPrice() != null) {
            builder.setOriginalPrice(common.originalPrice());
        }
        return builder.build();
    }
}
