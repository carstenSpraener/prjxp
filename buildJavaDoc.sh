#!/bin/bash

java -jar chunk-norris/build/libs/chunk-norris-0.0.1-SNAPSHOT-all.jar -o prjxp.jsonl -r . -wl java

java -jar tibed/build/libs/tibed-0.0.1-SNAPSHOT-all.jar -i prjxp-enriched.jsonl \
    --chroma.database=prjxp-enriched

java -jar golden-retriever/build/libs/golden-retriever-0.0.1-SNAPSHOT-all.jar \
    -src prjxp-common/src/main/java,chunk-norris/src/main/java,tibed/src/main/java,golden-retriever/src/main/java \
    --chat.api-kind=ollama \
    --chat.api-url=http://192.168.1.228:11434 \
    --chat.api-key=none \
    --chat.modelName=deepseek-coder-v2:16b
#
# You can specify other chat models, server and apis like:
# gldrtrvr.chat.modelName: Whatever is available to you (gemma-4-32b, qwen3-coder:30b, deepseek-code-v2:16B)
# gldrtrvr.chat.api-kind: What kind of server do you use? ollama, openai, gemini
# gldrtrvr.chat.api-key: if you need a api-key set it here. If not set none or any other string
# gldrtrvr.chat.api-url: The address of your chat server
#
#
