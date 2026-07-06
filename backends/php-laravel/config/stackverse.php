<?php

return [
    'port' => (int) env('PORT', 8080),

    'oidc' => [
        'issuer_uri' => env('OIDC_ISSUER_URI', 'http://localhost:8180/realms/stackverse'),
        'jwks_uri' => env('OIDC_JWKS_URI'),
        'audience' => env('OIDC_AUDIENCE', 'stackverse-api'),
    ],

    'seed_messages_dir' => env('SEED_MESSAGES_DIR', base_path('../../spec/messages')),

    'otel_enabled' => strtolower((string) env('OTEL_SDK_DISABLED', 'true')) === 'false',
];
