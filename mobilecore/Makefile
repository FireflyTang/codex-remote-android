GO ?= ../.tools/go/bin/go

.PHONY: test android-compile aar

test:
	$(GO) test ./...

android-compile:
	mkdir -p build
	GOOS=android GOARCH=arm64 CGO_ENABLED=0 $(GO) test -c -o build/mobilecore-android-arm64.test .

aar:
	./build-aar.sh
