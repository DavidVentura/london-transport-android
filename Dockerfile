FROM registry.gitlab.com/fdroid/fdroidserver:buildserver

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools

USER root

RUN sdkmanager "platforms;android-36" "build-tools;36.0.0"

RUN mkdir -p /home/vagrant/build/dev.davidv.londontransport /home/vagrant/.gradle && \
    chmod -R a+rwX /home/vagrant

USER vagrant
WORKDIR /home/vagrant/build/dev.davidv.londontransport

CMD ["./gradlew", "--no-daemon", "assembleRelease"]
