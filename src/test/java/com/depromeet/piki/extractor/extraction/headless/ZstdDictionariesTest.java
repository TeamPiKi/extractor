package com.depromeet.piki.extractor.extraction.headless;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 사전 로딩 규약(파일명 = 사전ID, renderer compress.py 의 DICT_ID 와 대칭)과 오설정 fail-fast 를 검증한다.
class ZstdDictionariesTest {

    @Test
    @DisplayName("디렉토리의 사전 파일들을 파일명을 ID 로 로드하고, 미보유 ID 는 empty 를 돌려준다")
    void loadsDictionaryFilesByFileName(@TempDir Path dir) throws IOException {
        byte[] mallV1 = "mall boilerplate v1".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("mall-v1.dict"), mallV1);
        Files.write(dir.resolve("mall-v2.dict"), "mall boilerplate v2".getBytes(StandardCharsets.UTF_8));

        ZstdDictionaries dictionaries = ZstdDictionaries.load(dir.toString());

        assertArrayEquals(mallV1, dictionaries.find("mall-v1.dict").orElseThrow());
        assertTrue(dictionaries.find("mall-v2.dict").isPresent());
        assertTrue(dictionaries.find("unknown.dict").isEmpty());
    }

    @Test
    @DisplayName("빈 설정(기본값)은 사전 없음으로 동작한다 — 파일 IO 없이 어떤 ID 조회도 empty")
    void blankDirMeansNoDictionaries() {
        ZstdDictionaries dictionaries = ZstdDictionaries.load("");

        assertTrue(dictionaries.find("mall-v1.dict").isEmpty());
    }

    @Test
    @DisplayName("디렉토리가 아닌 경로(없는 경로·파일)는 부팅 fail-fast 다 — 런타임 일시 실패로 오설정이 숨지 않게")
    void nonDirectoryFailsFast(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("not-a-dir"), new byte[] {1});

        assertThrows(IllegalStateException.class, () -> ZstdDictionaries.load(dir.resolve("missing").toString()));
        assertThrows(IllegalStateException.class, () -> ZstdDictionaries.load(file.toString()));
    }
}
