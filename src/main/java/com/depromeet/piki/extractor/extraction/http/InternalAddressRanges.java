package com.depromeet.piki.extractor.extraction.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 내부 대역 판정. 목록의 정본은 TeamPiKi/infra 의 {@code contracts/internal-ranges.txt} 이고 renderer 와 서버 벽이 같은
 * 파일을 읽는다. 빌드가 그 파일을 classpath 에 싣는다(build.gradle.kts).
 *
 * <p>IPv4 를 담은 IPv6({@code ::ffff:a.b.c.d})는 따로 풀지 않는다 — JDK 가 그런 주소를 Inet4Address 로 돌려준다.
 */
final class InternalAddressRanges {

    private static final String CONTRACT = "/contracts/internal-ranges.txt";

    /** 정본이 빠진 빌드는 판정 없이 뜨지 않고 이 클래스를 처음 쓰는 빈 생성에서 기동이 실패한다. */
    static final InternalAddressRanges CONTRACT_RANGES = load();

    private final List<Range> ranges;

    private InternalAddressRanges(List<Range> ranges) {
        this.ranges = ranges;
    }

    boolean contains(InetAddress address) {
        return ranges.stream().anyMatch(range -> range.contains(address));
    }

    private static InternalAddressRanges load() {
        try (InputStream in = InternalAddressRanges.class.getResourceAsStream(CONTRACT)) {
            if (in == null) {
                throw new IllegalStateException("내부 대역 정본이 classpath 에 없다: " + CONTRACT);
            }
            List<Range> ranges = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(line -> line.split("#", 2)[0].strip())
                .filter(entry -> !entry.isEmpty())
                .map(Range::parse)
                .toList();
            return new InternalAddressRanges(ranges);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Range(byte[] network, int prefixLength) {

        /** 정본의 주소는 IP 리터럴이라 getByName 이 이름 조회를 하지 않는다. */
        static Range parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            try {
                return new Range(InetAddress.getByName(parts[0]).getAddress(), Integer.parseInt(parts[1]));
            } catch (UnknownHostException | RuntimeException e) {
                throw new IllegalStateException("내부 대역 정본의 깨진 줄: " + cidr, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
