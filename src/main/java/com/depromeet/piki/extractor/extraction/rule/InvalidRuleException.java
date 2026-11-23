package com.depromeet.piki.extractor.extraction.rule;

/** 해석하지 못한 규칙. 계약상 그 규칙 하나만 버린다. */
public class InvalidRuleException extends RuntimeException {

    public InvalidRuleException(String message) {
        super(message);
    }
}
