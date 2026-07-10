package com.depromeet.piki.extractor.extraction.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// renderer 가 zstd 사전 압축으로 보낸 응답(X-Zstd-Dict: <사전ID>)을 해제할 사전 보관소.
// 사전ID = 파일명 — renderer compress.py 의 DICT_ID(ZSTD_DICT_PATH 의 basename) 규약과 대칭이다.
//
// 롤아웃 규약: 사전 파일을 이 서비스의 디렉토리에 먼저 배포한 뒤 renderer 의 ZSTD_DICT_PATH 를 켠다.
// 그래서 renderer 가 아직 안 쓰는 사전을 미리 들고 있는 것은 정상이고, 반대로 미보유 사전ID 가 오는 것은
// 롤아웃 순서 위반 — 조회한 쪽(HttpHeadlessRenderer)이 일시 실패로 번역한다.
final class ZstdDictionaries {

    private static final Logger log = LoggerFactory.getLogger(ZstdDictionaries.class);

    private final Map<String, byte[]> dictsById;

    private ZstdDictionaries(Map<String, byte[]> dictsById) {
        this.dictsById = Map.copyOf(dictsById);
    }

    // 부팅 시 디렉토리의 사전 파일을 전부 로드한다. 오설정(없는 경로·파일 지정)은 부팅에서 fail-fast —
    // 런타임 첫 사전 응답에서 터지면 일시 실패로 위장돼 recover 재시도 뒤에 숨는다(baseUrl 검증과 같은 결).
    static ZstdDictionaries load(String dictDir) {
        if (dictDir.isBlank()) {
            return new ZstdDictionaries(Map.of());
        }
        Path dir = Path.of(dictDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                "product.extract.headless.zstd-dict-dir [" + dictDir + "] 가 디렉토리가 아니다 — "
                    + "사전 파일을 먼저 배포했는지(롤아웃 규약), 경로가 맞는지 확인해야 한다."
            );
        }
        try (Stream<Path> files = Files.list(dir)) {
            Map<String, byte[]> dicts = new HashMap<>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                dicts.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
            log.info("zstd 사전 {}개 로드: {}", dicts.size(), dicts.keySet());
            return new ZstdDictionaries(dicts);
        } catch (IOException e) {
            throw new IllegalStateException("zstd 사전 디렉토리 [" + dictDir + "] 읽기에 실패했다.", e);
        }
    }

    // 테스트 편의 팩토리 — 파일 IO 없이 조립한다.
    static ZstdDictionaries none() {
        return new ZstdDictionaries(Map.of());
    }

    static ZstdDictionaries of(String id, byte[] dict) {
        return new ZstdDictionaries(Map.of(id, dict));
    }

    Optional<byte[]> find(String id) {
        return Optional.ofNullable(dictsById.get(id));
    }
}
