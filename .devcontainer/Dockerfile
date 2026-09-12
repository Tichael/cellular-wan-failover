FROM mcr.microsoft.com/devcontainers/base:ubuntu

ARG CMDLINE_TOOLS_VERSION="15859902"
ARG ANDROID_COMPILE_SDK="36"
ARG ANDROID_BUILD_TOOLS="36.0.0"
ARG ANDROID_NDK_VERSION="27.2.12479018"

# Install OpenJDK 21, build utilities, Python, Go, and networking diagnostic tools
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive && \
    apt-get install -y --no-install-recommends \
        openjdk-21-jdk \
        curl \
        git \
        git-lfs \
        unzip \
        zip \
        python3 \
        python3-pip \
        python3-venv \
        iptables \
        iproute2 \
        iputils-ping \
        net-tools \
        dnsutils \
        dnsmasq-base \
        socat \
        usbutils \
        golang \
        wireguard-tools \
        shellcheck \
        tcpdump \
        conntrack \
        ethtool \
        iperf3 && \
    rm -rf /var/lib/apt/lists/*

# Install Python test, linting, and development tools for Raspberry Pi Gateway
RUN pip3 install --no-cache-dir --break-system-packages \
        pytest \
        pytest-mock \
        ruff \
        mypy

USER vscode
WORKDIR /home/vscode

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV ANDROID_HOME=/home/vscode/android-sdk
ENV ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}"
ENV ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}"
ENV PATH="$PATH:$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Install Android SDK Command Line Tools & Platform Tools
RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    curl -sL https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip -o cmdline-tools.zip && \
    unzip -q cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools && \
    rm -rf cmdline-tools.zip && \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest

# Accept licenses and install platform tools, compile SDK, build tools & NDK
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" \
               "platforms;android-${ANDROID_COMPILE_SDK}" \
               "platforms;android-35" \
               "platforms;android-34" \
               "build-tools;${ANDROID_BUILD_TOOLS}" \
               "build-tools;35.0.0" \
               "build-tools;34.0.0" \
               "ndk;${ANDROID_NDK_VERSION}"

WORKDIR /workspace
