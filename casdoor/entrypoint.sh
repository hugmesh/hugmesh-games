#!/bin/sh
set -e

rand() { head -c 18 /dev/urandom | od -A n -t x1 | tr -d ' \n' | head -c "$1"; }

# Use explicitly-provided values or generate secure random ones
CASDOOR_CLIENT_ID="${CASDOOR_CLIENT_ID:-twclient001}"
CASDOOR_CLIENT_SECRET="${CASDOOR_CLIENT_SECRET:-$(rand 32)}"
CASDOOR_ADMIN_PASS="${CASDOOR_ADMIN_PASS:-$(rand 16)}"
CASDOOR_PLAYER_PASS="${CASDOOR_PLAYER_PASS:-$(rand 16)}"

echo ""
echo "================================================================"
echo "  Casdoor seed credentials (first-run only; DB wins after that)"
echo "  Client ID     : $CASDOOR_CLIENT_ID"
echo "  Client Secret : $CASDOOR_CLIENT_SECRET"
echo "  Admin user    : admin / $CASDOOR_ADMIN_PASS"
echo "  Player user   : player / $CASDOOR_PLAYER_PASS"
echo "================================================================"
echo ""

sed \
  -e "s|CASDOOR_CLIENT_ID_VALUE|$CASDOOR_CLIENT_ID|g" \
  -e "s|CASDOOR_CLIENT_SECRET_VALUE|$CASDOOR_CLIENT_SECRET|g" \
  -e "s|CASDOOR_ADMIN_PASS_VALUE|$CASDOOR_ADMIN_PASS|g" \
  -e "s|CASDOOR_PLAYER_PASS_VALUE|$CASDOOR_PLAYER_PASS|g" \
  /init_data.json.tpl > /tmp/init_data.json

exec /server
