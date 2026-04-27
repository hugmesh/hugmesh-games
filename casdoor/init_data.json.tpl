{
  "organizations": [
    {
      "owner": "admin",
      "name": "threewizards",
      "displayName": "Snapdragon Games",
      "websiteUrl": "http://localhost:4200",
      "favicon": "",
      "passwordType": "plain",
      "phonePrefix": "1",
      "defaultAvatar": "",
      "tags": [],
      "isProfilePublic": false
    }
  ],
  "applications": [
    {
      "owner": "admin",
      "name": "threewizards-app",
      "displayName": "Snapdragon Games",
      "logo": "",
      "homepageUrl": "http://localhost:4200",
      "description": "Snapdragon Games — Player Portal",
      "organization": "threewizards",
      "cert": "cert-built-in",
      "enablePassword": true,
      "enableSignUp": false,
      "clientId": "CASDOOR_CLIENT_ID_VALUE",
      "clientSecret": "CASDOOR_CLIENT_SECRET_VALUE",
      "redirectUris": [
        "http://localhost:4200",
        "http://localhost:4200/callback",
        "http://localhost:4200/silent-refresh.html"
      ],
      "tokenFormat": "JWT",
      "expireInHours": 168,
      "refreshExpireInHours": 336,
      "grantTypes": ["authorization_code", "password", "client_credentials", "token", "id_token"],
      "signingCert": "cert-built-in",
      "formOffset": 2
    }
  ],
  "users": [
    {
      "owner": "threewizards",
      "name": "admin",
      "id": "tw-admin-001",
      "type": "normal-user",
      "password": "CASDOOR_ADMIN_PASS_VALUE",
      "displayName": "Admin",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": true,
      "isForbidden": false,
      "isDeleted": false,
      "score": 2000,
      "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "player",
      "id": "tw-player-001",
      "type": "normal-user",
      "password": "CASDOOR_PLAYER_PASS_VALUE",
      "displayName": "Player",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false,
      "isForbidden": false,
      "isDeleted": false,
      "score": 2000,
      "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "mod",
      "id": "tw-mod-001",
      "type": "normal-user",
      "password": "CASDOOR_MOD_PASS_VALUE",
      "displayName": "Moderator",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false,
      "isForbidden": false,
      "isDeleted": false,
      "score": 2000,
      "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "publisher",
      "id": "tw-publisher-001",
      "type": "normal-user",
      "password": "CASDOOR_PUBLISHER_PASS_VALUE",
      "displayName": "Publisher",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false,
      "isForbidden": false,
      "isDeleted": false,
      "score": 2000,
      "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "player1",
      "id": "tw-player-002",
      "type": "normal-user",
      "password": "CASDOOR_PLAYER1_PASS_VALUE",
      "displayName": "Player 1",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "player2",
      "id": "tw-player-003",
      "type": "normal-user",
      "password": "CASDOOR_PLAYER2_PASS_VALUE",
      "displayName": "Player 2",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "player3",
      "id": "tw-player-004",
      "type": "normal-user",
      "password": "CASDOOR_PLAYER3_PASS_VALUE",
      "displayName": "Player 3",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "mod-three-wizards",
      "id": "tw-mod-twiat-001",
      "type": "normal-user",
      "password": "CASDOOR_MOD_TWIAT_PASS_VALUE",
      "displayName": "Mod — Three Wizards",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "mod-dodecahedron",
      "id": "tw-mod-dodec-001",
      "type": "normal-user",
      "password": "CASDOOR_MOD_DODEC_PASS_VALUE",
      "displayName": "Mod — Dodecahedron",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "publisher-three-wizards",
      "id": "tw-pub-twiat-001",
      "type": "normal-user",
      "password": "CASDOOR_PUB_TWIAT_PASS_VALUE",
      "displayName": "Publisher — Three Wizards",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    },
    {
      "owner": "threewizards",
      "name": "publisher-dodecahedron",
      "id": "tw-pub-dodec-001",
      "type": "normal-user",
      "password": "CASDOOR_PUB_DODEC_PASS_VALUE",
      "displayName": "Publisher — Dodecahedron",
      "email": "",
      "signupApplication": "threewizards-app",
      "isAdmin": false, "isForbidden": false, "isDeleted": false,
      "score": 2000, "ranking": 1
    }
  ],
  "roles": [
    {
      "owner": "threewizards",
      "name": "admin",
      "displayName": "Administrator",
      "description": "Full admin console access; can manage uploads, roles, and federation manifest.",
      "users": ["threewizards/admin"],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "player",
      "displayName": "Player",
      "description": "Standard player; can access the portal and play unlocked levels.",
      "users": [
        "threewizards/player",
        "threewizards/player1",
        "threewizards/player2",
        "threewizards/player3"
      ],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "uploader",
      "displayName": "Game Uploader",
      "description": "Can upload + replace game bundles; cannot manage roles.",
      "users": [],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "mod",
      "displayName": "Moderator",
      "description": "Per-game moderator: can stop sessions and kick players for the games an admin or publisher has scoped them to (mod_game_scopes table). Cannot upload games or grant roles.",
      "users": [
        "threewizards/mod",
        "threewizards/mod-three-wizards",
        "threewizards/mod-dodecahedron"
      ],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "publisher",
      "displayName": "Publisher",
      "description": "Can upload game bundles AND grant the mod role to other users for those games. Cannot grant publisher role; that's admin-only.",
      "users": [
        "threewizards/publisher",
        "threewizards/publisher-three-wizards",
        "threewizards/publisher-dodecahedron"
      ],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "play-three-wizards",
      "displayName": "Play: Three Wizards",
      "description": "Permits launching the Three Wizards in a Trenchcoat game.",
      "users": [
        "threewizards/admin",
        "threewizards/player",
        "threewizards/player1",
        "threewizards/player2",
        "threewizards/player3"
      ],
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "play-dodecahedron",
      "displayName": "Play: Dodecahedron Demo",
      "description": "Permits launching the Dodecahedron Demo game.",
      "users": [
        "threewizards/admin",
        "threewizards/player",
        "threewizards/player1",
        "threewizards/player2",
        "threewizards/player3"
      ],
      "isEnabled": true
    }
  ],
  "permissions": [
    {
      "owner": "threewizards",
      "name": "upload-games",
      "displayName": "Upload games",
      "description": "POST /api/admin/games/upload + replace registered games.",
      "users": [],
      "roles": ["threewizards/admin", "threewizards/uploader", "threewizards/publisher"],
      "resource": "/api/admin/games/upload",
      "actions": ["Read", "Write"],
      "effect": "Allow",
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "moderate-sessions",
      "displayName": "Moderate sessions",
      "description": "Stop sessions and kick players for the games scoped via mod_game_scopes. Per-game scoping enforced at the BFF layer; this permission only signals the role exists.",
      "users": [],
      "roles": ["threewizards/admin", "threewizards/mod"],
      "resource": "/api/mod/**",
      "actions": ["Read", "Write"],
      "effect": "Allow",
      "isEnabled": true
    },
    {
      "owner": "threewizards",
      "name": "publish-games",
      "displayName": "Publish games",
      "description": "Upload bundles + grant the mod role to other users for the publisher's games.",
      "users": [],
      "roles": ["threewizards/admin", "threewizards/publisher"],
      "resource": "/api/publisher/**",
      "actions": ["Read", "Write"],
      "effect": "Allow",
      "isEnabled": true
    }
  ]
}
