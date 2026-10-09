#!/bin/bash
cd /Users/casi/Projekte/prjxp && ./gradlew :mcp-server:test --no-daemon -q 2>&1 | tail -50
