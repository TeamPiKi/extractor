package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 응답 매퍼가 도메인 이름으로 계약 enum 을 찾으므로, 목록이 어긋나면 그 경로의 성공 응답이 500 으로 샌다. */
class ExtractionMethodContractTest {

    @Test
    @DisplayName("ExtractionMethod 상수 집합은 계약 enum 의 값 집합과 정확히 일치한다")
    void enumMatchesContract() {
        Set<String> contract = Arrays.stream(com.depromeet.piki.contracts.extraction.v1.ExtractionMethod.values())
            .filter(value -> value != com.depromeet.piki.contracts.extraction.v1.ExtractionMethod.UNRECOGNIZED)
            .filter(value -> value.getNumber() != 0)
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> domain = Arrays.stream(ExtractionMethod.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(contract, domain);
    }
}
