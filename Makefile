.PHONY: test build release verify-reproducible keystore

LONDON_TRANSPORT_KEYSTORE ?= android_fdroid_london_transport.jks
KEY_ALIAS ?= key0

test:
	./gradlew test

build:
	./build.sh

release: build
	./sign-apks.sh "$(LONDON_TRANSPORT_KEYSTORE)" "$(KEY_ALIAS)"

# Two containerised builds of the same commit have to land on the same bytes, otherwise F-Droid
# cannot verify what we publish.
verify-reproducible:
	./build.sh
	@sha256sum unsigned/app-release-unsigned.apk > unsigned/first-build.sha256
	./build.sh
	@sha256sum -c unsigned/first-build.sha256
	@rm -f unsigned/first-build.sha256

keystore:
	@test ! -f "$(LONDON_TRANSPORT_KEYSTORE)" || { echo "$(LONDON_TRANSPORT_KEYSTORE) already exists"; exit 1; }
	keytool -genkeypair -v -keystore "$(LONDON_TRANSPORT_KEYSTORE)" -alias "$(KEY_ALIAS)" \
	  -keyalg RSA -keysize 4096 -validity 10000
