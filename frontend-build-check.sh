#!/bin/bash
cd /Users/casi/Projekte/prjxp/frontend
npx ng build 2>&1 | tail -50
echo "EXIT_CODE=$?"
