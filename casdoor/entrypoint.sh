#!/bin/sh
set -e

rand() { head -c 18 /dev/urandom | od -A n -t x1 | tr -d ' \n' | head -c "$1"; }

# Use explicitly-provided values or generate secure random ones
CASDOOR_CLIENT_ID="${CASDOOR_CLIENT_ID:-twclient001}"
CASDOOR_CLIENT_SECRET="${CASDOOR_CLIENT_SECRET:-$(rand 32)}"
CASDOOR_ADMIN_PASS="${CASDOOR_ADMIN_PASS:-$(rand 16)}"
CASDOOR_PLAYER_PASS="${CASDOOR_PLAYER_PASS:-$(rand 16)}"
CASDOOR_MOD_PASS="${CASDOOR_MOD_PASS:-$(rand 16)}"
CASDOOR_PUBLISHER_PASS="${CASDOOR_PUBLISHER_PASS:-$(rand 16)}"
CASDOOR_PLAYER1_PASS="${CASDOOR_PLAYER1_PASS:-$(rand 16)}"
CASDOOR_PLAYER2_PASS="${CASDOOR_PLAYER2_PASS:-$(rand 16)}"
CASDOOR_PLAYER3_PASS="${CASDOOR_PLAYER3_PASS:-$(rand 16)}"
CASDOOR_MOD_TWIAT_PASS="${CASDOOR_MOD_TWIAT_PASS:-$(rand 16)}"
CASDOOR_MOD_DODEC_PASS="${CASDOOR_MOD_DODEC_PASS:-$(rand 16)}"
CASDOOR_PUB_TWIAT_PASS="${CASDOOR_PUB_TWIAT_PASS:-$(rand 16)}"
CASDOOR_PUB_DODEC_PASS="${CASDOOR_PUB_DODEC_PASS:-$(rand 16)}"
CASDOOR_PUB_FLAT_PASS="${CASDOOR_PUB_FLAT_PASS:-$(rand 16)}"

echo ""
echo "================================================================"
echo "  Casdoor seed credentials (first-run only; DB wins after that)"
echo "  Client ID            : $CASDOOR_CLIENT_ID"
echo "  Client Secret        : $CASDOOR_CLIENT_SECRET"
echo "  admin                : admin                 / $CASDOOR_ADMIN_PASS"
echo "  player               : player                / $CASDOOR_PLAYER_PASS"
echo "  player1              : player1               / $CASDOOR_PLAYER1_PASS"
echo "  player2              : player2               / $CASDOOR_PLAYER2_PASS"
echo "  player3              : player3               / $CASDOOR_PLAYER3_PASS"
echo "  mod (generic)        : mod                   / $CASDOOR_MOD_PASS"
echo "  mod three-wizards    : mod-three-wizards     / $CASDOOR_MOD_TWIAT_PASS"
echo "  mod dodecahedron     : mod-dodecahedron      / $CASDOOR_MOD_DODEC_PASS"
echo "  publisher (generic)  : publisher             / $CASDOOR_PUBLISHER_PASS"
echo "  publisher 3-wizards  : publisher-three-wizards / $CASDOOR_PUB_TWIAT_PASS"
echo "  publisher dodecahedr : publisher-dodecahedron  / $CASDOOR_PUB_DODEC_PASS"
echo "  publisher flatland   : publisher-flatland      / $CASDOOR_PUB_FLAT_PASS"
echo "================================================================"
echo ""

sed \
  -e "s|CASDOOR_CLIENT_ID_VALUE|$CASDOOR_CLIENT_ID|g" \
  -e "s|CASDOOR_CLIENT_SECRET_VALUE|$CASDOOR_CLIENT_SECRET|g" \
  -e "s|CASDOOR_ADMIN_PASS_VALUE|$CASDOOR_ADMIN_PASS|g" \
  -e "s|CASDOOR_PLAYER_PASS_VALUE|$CASDOOR_PLAYER_PASS|g" \
  -e "s|CASDOOR_MOD_PASS_VALUE|$CASDOOR_MOD_PASS|g" \
  -e "s|CASDOOR_PUBLISHER_PASS_VALUE|$CASDOOR_PUBLISHER_PASS|g" \
  -e "s|CASDOOR_PLAYER1_PASS_VALUE|$CASDOOR_PLAYER1_PASS|g" \
  -e "s|CASDOOR_PLAYER2_PASS_VALUE|$CASDOOR_PLAYER2_PASS|g" \
  -e "s|CASDOOR_PLAYER3_PASS_VALUE|$CASDOOR_PLAYER3_PASS|g" \
  -e "s|CASDOOR_MOD_TWIAT_PASS_VALUE|$CASDOOR_MOD_TWIAT_PASS|g" \
  -e "s|CASDOOR_MOD_DODEC_PASS_VALUE|$CASDOOR_MOD_DODEC_PASS|g" \
  -e "s|CASDOOR_PUB_TWIAT_PASS_VALUE|$CASDOOR_PUB_TWIAT_PASS|g" \
  -e "s|CASDOOR_PUB_DODEC_PASS_VALUE|$CASDOOR_PUB_DODEC_PASS|g" \
  -e "s|CASDOOR_PUB_FLAT_PASS_VALUE|$CASDOOR_PUB_FLAT_PASS|g" \
  /init_data.json.tpl > /tmp/init_data.json

exec /server
