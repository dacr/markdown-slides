# Common operations, also a cheat sheet : `make help`

PORT     ?= $(or $(MARKDOWN_SLIDES_LISTEN_PORT),8048)
URL      ?= http://127.0.0.1:$(PORT)
JAR      := out/assembly.dest/out.jar
JVM_OPTS := -Xms128m -Xmx2g --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
            --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED

.PHONY: all help compile test run run-shared jar run-jar open format clean status check-mill

all: test

help:
	@grep -E '^# [a-z-]+ +:' Makefile | sed 's/^# //'

# compile            : compile the application
compile:
	mill --no-server compile

# test               : run the unit tests
test:
	mill --no-server test

# run                : start the application from the sources, then browse http://127.0.0.1:8048
run:
	mill --no-server run

# run-shared         : start it for participants to follow from their own devices (every network interface)
run-shared:
	MARKDOWN_SLIDES_LISTEN_HOST=0.0.0.0 mill --no-server run

# jar                : build the standalone executable jar (out/assembly.dest/out.jar)
jar:
	mill --no-server assembly

# run-jar            : start the application from the standalone jar
run-jar: jar
	java $(JVM_OPTS) -jar $(JAR)

# open               : open the user interface in the browser
open:
	xdg-open $(URL)

# status             : synchronization status of the running application (needs curl and jq)
status:
	@curl -s $(URL)/api/status | jq

# format             : format the sources (scalafmt)
format:
	scalafmt

# check-mill         : the mill version pinned in build.mill must be the one the flake provides
check-mill:
	@pinned=$$(sed -n 's#^//| mill-version: ##p' build.mill); provided=$$(mill --version 2>/dev/null | sed -n 's/^Mill Build Tool version //p'); \
	if [ "$$pinned" = "$$provided" ]; then echo "mill $$pinned : aligned"; else echo "build.mill pins $$pinned but the flake provides $$provided"; exit 1; fi

# clean              : remove the build outputs (not the data, in ~/.markdown-slides)
clean:
	mill --no-server clean
	rm -rf out
