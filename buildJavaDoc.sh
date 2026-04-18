#!/bin/bash

java -jar chunk-norris/build/libs/chunk-norris-0.0.1-SNAPSHOT-all.jar -o prjxp.jsonl -r . -wl java
java -jar tibed/build/libs/tibed-0.0.1-SNAPSHOT-all.jar -i prjxp.jsonl
java -jar golden-retriever/build/libs/golden-retriever-0.0.1-SNAPSHOT-all.jar -src prjxp-common/src/main/java,chunk-norris/src/main/java,tibed/src/main/java,golden-retriever/src/main/java
