#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: ./ask.sh \"Your question here\""
    exit 1
fi

echo "Querying RAG system: '$1'..."
echo ""
curl -s -G "http://localhost:8080/rag" --data-urlencode "question=$1"
echo ""
