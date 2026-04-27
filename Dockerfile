# Stage 1: Build native image with GraalVM
FROM ghcr.io/graalvm/graalvm-community:21 AS builder
WORKDIR /app

# Install UPX for binary compression (reduces binary size ~50-70%)
RUN microdnf install -y wget xz && \
    wget -qO /tmp/upx.tar.xz \
        https://github.com/upx/upx/releases/download/v4.2.4/upx-4.2.4-amd64_linux.tar.xz && \
    tar -xf /tmp/upx.tar.xz -C /tmp && \
    mv /tmp/upx-4.2.4-amd64_linux/upx /usr/local/bin/upx && \
    rm -rf /tmp/upx* && \
    microdnf clean all

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies separately so this layer is cached on pom.xml changes
RUN chmod +x mvnw && ./mvnw -Pnative dependency:go-offline -DskipTests -B -q

COPY src ./src

# Build native image (native-image is already included in GraalVM 21+)
RUN ./mvnw -Pnative package -DskipTests -B

# Compress the native binary – cuts binary size by 50-70%
RUN upx --best --lzma /app/target/djong-gateway

# Stage 2: Extract libz – arch-independent (works on amd64 and arm64)
FROM debian:bookworm-slim AS libdeps
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends zlib1g && \
    rm -rf /var/lib/apt/lists/* && \
    # Resolve the actual versioned .so file (not the symlink) to a fixed path
    find /usr/lib -name "libz.so.1.*" ! -type l | head -1 | xargs -I{} cp {} /libz.so.1

# Stage 3: Distroless base with glibc – ~19 MB (vs ~84 MB debian:bookworm-slim)
FROM gcr.io/distroless/base-debian12:nonroot
# Copy the resolved libz (~100 KB) to a dedicated directory
COPY --from=libdeps --chmod=755 /libz.so.1 /usr/local/lib/libz.so.1
# Ensure the binary is executable by the nonroot user (uid=65532)
COPY --from=builder --chmod=755 /app/target/djong-gateway /djong-gateway

# Tell the dynamic linker where to find the extra library
ENV LD_LIBRARY_PATH=/usr/local/lib
EXPOSE 9018
ENTRYPOINT ["/djong-gateway"]
