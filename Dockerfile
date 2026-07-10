# 추출 서비스 컨테이너. 인프라(terraform)는 Docker 베이스까지만 책임지고, 이 이미지가 앱 배포 단위다.
# 멀티아치(temurin 은 arm64/amd64 둘 다 제공) — prod 박스(t4g/arm64)와 로컬(macOS arm64) 공용.
# 빌드: ./gradlew bootJar && docker build -t piki-extractor .
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY build/libs/piki-extractor-*.jar app.jar

# 계약 문서(docs/api-contract.md)의 기본 포트. 시크릿(GEMINI_API_KEY)은 이미지에 안 굽는다 —
# 실행 시점에 SSM Parameter Store 에서 읽어 env 로 주입한다(배포 스크립트 소관).
EXPOSE 8090

# --enable-native-access: zstd-jni(렌더 응답 해제)의 JNI 로드 허용 — Java 25 는 경고, 미래 JDK 는 차단(JEP 472).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
