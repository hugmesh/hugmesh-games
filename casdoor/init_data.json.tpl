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
    }
  ]
}
