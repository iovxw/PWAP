#!/bin/bash
set -e

cd "$(dirname "$0")"

echo ">> Running go mod tidy..."
go mod tidy

echo ">> Building libcore.aar..."
gomobile bind -androidapi 21 \
  -trimpath \
  -ldflags='-s -w' \
  -tags='with_quic,with_wireguard,with_utls' \
  -v . || exit 1

rm -f libcore-sources.jar

PROJ=../app/libs
mkdir -p $PROJ
cp -f libcore.aar $PROJ/
echo ">> Installed to $PROJ/libcore.aar"
