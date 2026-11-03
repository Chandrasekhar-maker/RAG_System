#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: ./upload.sh <path-to-pdf>"
    exit 1
fi

if [ ! -f "$1" ]; then
    echo "Error: File '$1' not found!"
    exit 1
fi

echo "Uploading '$1' to RAG system..."
curl -X POST "http://localhost:8080/upload" -F "file=@$1"
echo ""
